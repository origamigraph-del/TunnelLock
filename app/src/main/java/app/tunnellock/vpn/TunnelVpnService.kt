package app.tunnellock.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.openvpn.OpenVpnConfig
import app.tunnellock.core.xray.AppRoute
import app.tunnellock.core.xray.CoreOptions
import app.tunnellock.core.xray.XrayConfigBuilder
import app.tunnellock.data.AppLog
import app.tunnellock.data.AppStore
import app.tunnellock.data.StoredServer
import app.tunnellock.data.SubscriptionUpdater
import app.tunnellock.strict.AppGate
import app.tunnellock.strict.Policy
import app.tunnellock.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tunnel. While connecting, connected or reconnecting the TUN interface
 * stays up, so whenever the proxy is not working the protected apps' packets
 * end up inside the TUN and are dropped there; they never reach the normal network.
 *
 * In the secure space Android's always-on lockdown adds a second layer: with no
 * VPN interface at all (disconnected, or this service not running) the system
 * itself blocks every app. So when disconnected under lockdown the interface is
 * closed and the VPN key leaves the status bar; without lockdown it stays up.
 *
 * Losing the connection only cuts the internet; the apps stay open. They are
 * paused (cannot be opened) only when the user disconnects on purpose and the
 * "pause when disconnected" setting is on.
 *
 * If the server keeps failing, the other servers are tested outside the tunnel
 * and the fastest one that works takes over (failover setting).
 *
 * OpenVPN servers run in the OpenVPN process instead of Xray. OpenVPN asks for
 * its own interface (with the address its server assigned); until it does,
 * TunnelLock's interface keeps blocking, and the kill switch works the same.
 */
class TunnelVpnService : VpnService() {

    companion object {
        private const val TAG = "TunnelVpn"
        const val ACTION_GUARD = "app.tunnellock.action.GUARD"
        const val ACTION_CONNECT = "app.tunnellock.action.CONNECT"
        const val ACTION_DISCONNECT = "app.tunnellock.action.DISCONNECT"

        private const val MTU = 1500
        private val TEST_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val INITIAL_ATTEMPTS = 3
        private const val MAX_BACKOFF_MS = 30_000L

        /** Failed attempts on one server before the others are tested. */
        private const val FAILOVER_AFTER = 3
        private const val FAILOVER_CANDIDATES = 12
        private const val FAILOVER_PARALLEL = 4
        private const val SUBSCRIPTION_CHECK_MS = 60 * 60 * 1000L
        private const val BATTERY_SAMPLE_MS = 15 * 60 * 1000L

        /** A connect tap gives up after at most this long (OpenVPN keeps retrying on its own otherwise). */
        private const val OPENVPN_MAX_CONNECT_TIMEOUT_MS = 180_000L

        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TunnelVpnService::class.java).setAction(action),
            )
        }
    }

    private sealed interface Command {
        data object Guard : Command
        data object SystemStart : Command
        data object Connect : Command
        data object Disconnect : Command
        data object NetworkChanged : Command
        data object Revoke : Command
    }

    private enum class SessionMode {
        /** The user tapped connect: give up (and lock) if the server never answers. */
        USER,

        /** The service was restarted by the system: reconnect quietly and keep trying. */
        RESTORE,

        /** The network changed or dropped mid-use: keep trying, apps stay open. */
        NETWORK,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var session: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private lateinit var store: AppStore

    @Volatile private var currentNetwork: Network? = null
    @Volatile private var networkKnown = false
    private var lastSubscriptionCheck = 0L

    /** Apps the current interface leaves out: apps with their own server that cannot be routed. */
    private var tunExcluded: Set<String> = emptySet()

    /** What the current interface was built for, when OpenVPN asked for it; null for TunnelLock's own. */
    private var openVpnTun: OpenVpnTun? = null
    @Volatile private var openVpnEvents: Channel<Pair<Int, OpenVpnEngine.Event>>? = null
    private val openVpnHost = object : OpenVpnEngine.Host {
        override fun openTun(tun: OpenVpnTun): ParcelFileDescriptor? = establishOpenVpnTun(tun)

        override fun protect(fd: Int): Boolean = this@TunnelVpnService.protect(fd)

        override fun onEvent(runId: Int, event: OpenVpnEngine.Event) {
            openVpnEvents?.trySend(runId to event)
        }
    }
    private val openVpn by lazy { OpenVpnEngine(this, openVpnHost) }

    private val batteryLog by lazy { BatteryLog(this) }

    /** Health checks since the last battery sample (each one wakes the radio). */
    private val healthChecks = AtomicInteger()

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> "screen on"
                Intent.ACTION_SCREEN_OFF -> "screen off"
                Intent.ACTION_POWER_CONNECTED -> "charger connected"
                Intent.ACTION_POWER_DISCONNECTED -> "charger disconnected"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power saving changed"
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "idle mode changed"
                else -> return
            }
            scope.launch { sampleBattery(reason) }
        }
    }

    private fun sampleBattery(reason: String) {
        runCatching { batteryLog.sample(reason, TunnelState.status.value.phase, healthChecks.getAndSet(0)) }
            .onFailure { AppLog.w(TAG, "battery sample failed", it) }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // An update of an app that is in use is left alone.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            // Reinstalled, an app gets a new UID: its own-server rule must follow it.
            if (pkg in store.appServers.value && TunnelState.status.value.phase.isActive) commands.trySend(Command.Connect)
            if (intent.action == Intent.ACTION_PACKAGE_ADDED) AppGate.closeNewApp(context, pkg)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = currentNetwork
            currentNetwork = network
            if (networkKnown && previous != network) commands.trySend(Command.NetworkChanged)
            networkKnown = true
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
                currentNetwork = null
                commands.trySend(Command.NetworkChanged)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = (application as TunnelLockApp).store
        scope.launch { Policy.ensurePolicies(this@TunnelVpnService) }
        scope.launch { for (command in commands) handle(command) }
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            sampleBattery("battery at start")
            while (true) {
                delay(BATTERY_SAMPLE_MS)
                sampleBattery("battery")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val command = when (intent?.action) {
            ACTION_CONNECT -> Command.Connect
            ACTION_DISCONNECT -> Command.Disconnect
            ACTION_GUARD -> Command.Guard
            // Started by the system: always-on VPN, boot, or restart after being killed.
            else -> Command.SystemStart
        }
        commands.trySend(command)
        return START_STICKY
    }

    override fun onRevoke() {
        commands.trySend(Command.Revoke)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        commands.close()
        scope.cancel()
        XrayCore.stop()
        openVpn.kill()
        closeTun()
        // Apps are not paused here: someone may be using one. Lockdown cuts their internet.
        TunnelState.update { it.copy(phase = Phase.OFF, connectedSince = null) }
        super.onDestroy()
    }

    // ---- command handling (one at a time) -----------------------------------

    private suspend fun handle(command: Command) {
        val phase = TunnelState.status.value.phase
        when (command) {
            // Opening the app while the service is not running (e.g. its process was killed):
            // the same as a restart by the system, so the apps are not paused behind the user.
            Command.Guard -> if (phase == Phase.OFF) {
                if (store.autoConnect.value && store.selectedServer() != null) {
                    startSession(SessionMode.RESTORE)
                } else {
                    enterGuard(pause = store.pauseWhenDisconnected.value)
                }
            }
            Command.SystemStart -> if (!phase.isActive) {
                if (store.autoConnect.value && store.selectedServer() != null) {
                    // Possibly restarted while an app was in use: reconnect without touching the apps.
                    startSession(SessionMode.RESTORE)
                } else if (phase == Phase.OFF) {
                    enterGuard(pause = store.pauseWhenDisconnected.value)
                }
            }
            Command.Connect -> startSession(SessionMode.USER)
            Command.Disconnect -> {
                stopSession()
                enterGuard(pause = store.pauseWhenDisconnected.value)
            }
            Command.NetworkChanged -> if (phase == Phase.CONNECTED || phase == Phase.RECONNECTING) {
                if (openVpn.running) {
                    // OpenVPN moves its connection itself (or reconnects).
                    openVpn.networkChanged()
                } else {
                    // Old connections are bound to the previous network; start over (the TUN stays up).
                    startSession(SessionMode.NETWORK)
                }
            }
            Command.Revoke -> {
                stopSession()
                closeTun()
                setStatus { it.copy(phase = Phase.OFF, connectedSince = null, error = getString(R.string.error_revoked)) }
                stopSelf()
            }
        }
    }

    private suspend fun startSession(mode: SessionMode) {
        stopSession()
        // A connect tap that fails pauses the apps again only if they were paused before it
        // (not when switching servers while the apps are in use).
        val pauseOnFailure = mode == SessionMode.USER && store.pauseWhenDisconnected.value && TunnelState.status.value.appsPaused
        val server = store.selectedServer()
        val profile = server?.let { store.profileOf(it) }
        if (server == null || profile == null) {
            enterGuard(getString(R.string.error_no_server), pauseOnFailure)
            return
        }
        // Apps with their own server that this main server cannot route are left out of the
        // interface, which only cuts them off if Android's lockdown is on: otherwise refuse.
        val cutOff = if (routesPossible(profile)) emptySet() else store.appServers.value.keys
        if (cutOff.isNotEmpty() && !Policy.isLockdownActive(this)) {
            AppLog.w(TAG, "${cutOff.size} apps with their own server cannot be routed and lockdown is off: not connecting")
            enterGuard(getString(R.string.error_own_server_unprotected), pauseOnFailure)
            return
        }
        if (profile.protocol == Protocol.OPENVPN) {
            if (ensureTun() == null) {
                setStatus { it.copy(phase = Phase.OFF, connectedSince = null, error = getString(R.string.error_vpn_permission)) }
                return
            }
            session = scope.launch { runOpenVpnSession(server, profile, mode, pauseOnFailure) }
            return
        }
        // Xray reads from TunnelLock's own interface, not one built for an OpenVPN server.
        val fd = ensureTun(fresh = openVpnTun != null, excluded = cutOff)
        if (fd == null) {
            setStatus { it.copy(phase = Phase.OFF, connectedSince = null, error = getString(R.string.error_vpn_permission)) }
            return
        }
        val config = xrayConfig(profile, log = true)
        session = scope.launch { runSession(server, config, fd, mode, pauseOnFailure) }
    }

    private suspend fun stopSession() {
        session?.cancelAndJoin()
        session = null
        XrayCore.stop()
        openVpn.stop()
        openVpnEvents = null
    }

    /**
     * Keeps a server connection alive. "Connected" (and letting the apps run)
     * only happens after a real request went through the proxy.
     */
    private suspend fun runSession(first: StoredServer, firstConfig: String, fd: Int, mode: SessionMode, pauseOnFailure: Boolean) {
        var server = first
        var config = firstConfig
        if (mode == SessionMode.NETWORK) {
            markDropped(server.name)
        } else {
            setStatus { it.copy(phase = Phase.CONNECTING, serverName = server.name, connectedSince = null, error = null) }
        }
        // Servers already given a chance since the last good connection (by link: ids change on refresh).
        val tried = mutableSetOf(server.link)
        var failures = 0
        var backoff = 1_000L
        var everConnected = false
        while (true) {
            val started = runCatching { XrayCore.start(this, config, fd) }
            started.exceptionOrNull()?.let { AppLog.w(TAG, "core start failed", it) }
            if (started.isSuccess && checkConnectivity()) {
                everConnected = true
                markConnected(server)
                tried.clear()
                tried += server.link
                failures = 0
                backoff = 1_000L
                var misses = 0
                while (misses < 2) {
                    delay(CHECK_INTERVAL_MS)
                    misses = if (checkConnectivity()) 0 else misses + 1
                    if (misses == 0) refreshSubscriptionsIfDue()
                }
                // Lost mid-use: internet is cut inside the tunnel, the apps stay open.
                markDropped(server.name)
            } else {
                failures++
                if (started.isFailure || failures % FAILOVER_AFTER == 0) {
                    val next = findFallback(tried)
                    if (next != null) {
                        server = next.first
                        config = next.second
                        tried += server.link
                        failures = 0
                        backoff = 1_000L
                        setStatus { it.copy(serverName = server.name) }
                        continue
                    }
                    // Nothing else works right now: later rounds may try every server again.
                    if (mode != SessionMode.USER) {
                        tried.clear()
                        tried += server.link
                    }
                }
                if (started.isFailure) {
                    // A broken config will not fix itself by retrying.
                    XrayCore.stop()
                    enterGuard(
                        getString(R.string.error_core_start, started.exceptionOrNull()?.message.orEmpty()),
                        pause = pauseOnFailure && !everConnected,
                    )
                    return
                }
                if (mode == SessionMode.USER && TunnelState.status.value.phase == Phase.CONNECTING && failures >= INITIAL_ATTEMPTS) {
                    XrayCore.stop()
                    enterGuard(getString(R.string.error_server_unreachable), pause = pauseOnFailure)
                    return
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Tests the servers not in [exclude] side by side, outside the tunnel, and
     * returns the fastest one that works with its tunnel config. Null when
     * failover is off, there is no network, or nothing works.
     */
    private suspend fun findFallback(exclude: Set<String>): Pair<StoredServer, String>? {
        if (!store.failover.value || currentNetwork == null) return null
        val candidates = store.servers.value
            .filter { it.link !in exclude }
            .sortedWith(compareBy({ latencyRank(it.latencyMs) }, { it.latencyMs ?: Long.MAX_VALUE }))
            // Only Xray servers: OpenVPN servers cannot be tested side by side.
            .mapNotNull { server -> store.profileOf(server)?.takeIf { it.protocol.isXray }?.let { server to it } }
            .take(FAILOVER_CANDIDATES)
        if (candidates.isEmpty()) return null
        AppLog.i(TAG, "failover: testing ${candidates.size} other servers")
        val gate = Semaphore(FAILOVER_PARALLEL)
        // Started in the service scope: a disconnect must not wait for tests stuck in native code.
        val results = candidates.map { (server, profile) ->
            scope.async {
                gate.withPermit {
                    val ms = runCatching {
                        val test = XrayConfigBuilder.build(profile, CoreOptions(useTun = false))
                        XrayCore.testServer(this@TunnelVpnService, test, TEST_URLS.first())
                    }.getOrDefault(-1L)
                    Triple(server, profile, ms)
                }
            }
        }.awaitAll()
        val working = results.filter { it.third >= 0 }
        // When nothing answers the network is more likely at fault; keep the old results.
        if (working.isNotEmpty()) results.forEach { store.setLatency(it.first.id, it.third) }
        val best = working.minByOrNull { it.third }
        if (best == null) {
            AppLog.w(TAG, "failover: no other server answered")
            return null
        }
        AppLog.i(TAG, "failover: ${working.size} answered, switching to the fastest (${best.third} ms)")
        return runCatching { best.first to xrayConfig(best.second) }.getOrNull()
    }

    /** The tunnel config for a main server, with the apps that have their own server. */
    private fun xrayConfig(profile: ServerProfile, log: Boolean = false): String {
        val routes = if (routesPossible(profile)) appRoutes() else emptyList()
        if (log && routes.isNotEmpty()) {
            val cutOff = routes.filter { route -> route.profile?.let { XrayConfigBuilder.canRouteApps(it) } != true }.sumOf { it.uids.size }
            AppLog.i(TAG, "own servers: ${routes.sumOf { it.uids.size }} apps on ${routes.size} servers" + if (cutOff > 0) ", $cutOff cut off (server gone)" else "")
        }
        return XrayConfigBuilder.build(profile, CoreOptions(mtu = MTU), routes)
    }

    /**
     * Whether apps can be sent to their own servers while [main] is the main server: it
     * must run on Xray without FakeDNS, and Android 10 or newer must tell Xray each
     * connection's app. Otherwise those apps are cut off.
     */
    private fun routesPossible(main: ServerProfile): Boolean =
        Build.VERSION.SDK_INT >= 29 && XrayConfigBuilder.canCarryAppRoutes(main)

    /** Apps with their own server, grouped by server. Empty when every app uses the main server. */
    private fun appRoutes(): List<AppRoute> {
        val choices = store.appServers.value
        if (choices.isEmpty()) return emptyList()
        val uids = linkedMapOf<StoredServer?, MutableList<Int>>()
        for ((pkg, choice) in choices) {
            val uid = runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: continue
            uids.getOrPut(store.serverFor(choice)) { mutableListOf() } += uid
        }
        return uids.map { (server, list) -> AppRoute(list, server?.let { store.profileOf(it) }) }
    }


    /** Tested and working first, then never tested, then failed. */
    private fun latencyRank(latencyMs: Long?): Int = when {
        latencyMs == null -> 1
        latencyMs < 0 -> 2
        else -> 0
    }

    /** Old subscriptions are refreshed while connected, when downloads are most likely to work. */
    private fun refreshSubscriptionsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (lastSubscriptionCheck != 0L && now - lastSubscriptionCheck < SUBSCRIPTION_CHECK_MS) return
        lastSubscriptionCheck = now
        scope.launch { SubscriptionUpdater.updateStale(store) }
    }

    /**
     * Runs an OpenVPN server. OpenVPN reconnects by itself; this follows its
     * state, gives up on a connect tap that never succeeds, and starts OpenVPN
     * again if it ends while in use. Once the apps have been running, giving up
     * never pauses them: they stay open without internet.
     */
    private suspend fun runOpenVpnSession(server: StoredServer, profile: ServerProfile, mode: SessionMode, pauseOnFailure: Boolean) {
        if (mode == SessionMode.NETWORK) {
            markDropped(server.name)
        } else {
            setStatus { it.copy(phase = Phase.CONNECTING, serverName = server.name, connectedSince = null, error = null) }
        }
        val config = requireNotNull(profile.rawConfig)
        val login = OpenVpnLogin(server.username, server.password, server.keyPassword)
        val events = Channel<Pair<Int, OpenVpnEngine.Event>>(Channel.UNLIMITED)
        openVpnEvents = events
        // OpenVPN gives each remote about 20 s (server-poll-timeout) before trying the next.
        val remotes = runCatching { OpenVpnConfig.remotes(config).size }.getOrDefault(1).coerceAtLeast(1)
        val connectTimeout = (30_000L + 30_000L * remotes).coerceAtMost(OPENVPN_MAX_CONNECT_TIMEOUT_MS)
        var everConnected = false
        var backoff = 2_000L
        // Why connecting takes long, once OpenVPN can tell: shown while it keeps trying.
        var stallHint: String? = null
        fun pauseNow() = pauseOnFailure && !everConnected
        while (true) {
            val runId = try {
                openVpn.start(config, login)
            } catch (e: Exception) {
                AppLog.w(TAG, "OpenVPN did not start", e)
                enterGuard(getString(R.string.error_openvpn_start, e.message.orEmpty()), pauseNow())
                return
            }
            wait@ while (true) {
                val received = if (mode == SessionMode.USER && !everConnected) {
                    withTimeoutOrNull(connectTimeout) { events.receive() }
                } else {
                    events.receive()
                }
                if (received == null) {
                    AppLog.w(TAG, "OpenVPN did not connect in time")
                    openVpn.stop()
                    enterGuard(stallHint ?: getString(R.string.error_server_unreachable), pauseNow())
                    return
                }
                val (id, event) = received
                // Left over from an earlier run.
                if (id != runId) continue
                when (event) {
                    OpenVpnEngine.Event.Connected -> {
                        everConnected = true
                        backoff = 2_000L
                        stallHint = null
                        markConnected(server)
                    }
                    is OpenVpnEngine.Event.Stalled -> {
                        val hint = getString(if (event.serverAnswered) R.string.error_openvpn_stuck else R.string.error_openvpn_no_answer)
                        stallHint = hint
                        setStatus { if (it.phase == Phase.CONNECTED) it else it.copy(error = hint) }
                    }
                    // Lost mid-use: OpenVPN holds the tunnel (no internet) while it reconnects.
                    is OpenVpnEngine.Event.Reconnecting -> markDropped(server.name)
                    is OpenVpnEngine.Event.Stopped -> {
                        if (event.loginRejected) {
                            enterGuard(getString(R.string.error_openvpn_login), pauseNow())
                            return
                        }
                        if (mode == SessionMode.USER && !everConnected) {
                            enterGuard(getString(R.string.error_openvpn_stopped, event.reason), pauseNow())
                            return
                        }
                        // Ended by itself while in use: the interface keeps blocking; start it again.
                        markDropped(server.name)
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                        break@wait
                    }
                }
            }
        }
    }

    private fun checkConnectivity(): Boolean {
        healthChecks.incrementAndGet()
        val ok = TEST_URLS.any { XrayCore.measureDelay(it) >= 0 }
        if (!ok) AppLog.w(TAG, "health check through the tunnel failed")
        return ok
    }

    // ---- state changes --------------------------------------------------------

    /**
     * Not connected: no internet for the apps (Android's lockdown, or TunnelLock's own
     * interface where lockdown is not active); they are paused only if [pause].
     */
    private fun enterGuard(error: String? = null, pause: Boolean) {
        if (Policy.isLockdownActive(this)) {
            // Android's lockdown already blocks every app while no VPN is up. Without an
            // interface the VPN key leaves the status bar, so it shows only when connected.
            closeTun()
        } else if (ensureTun() == null) {
            setStatus { it.copy(phase = Phase.OFF, connectedSince = null, error = error ?: getString(R.string.error_vpn_permission)) }
            return
        }
        val update = {
            setStatus {
                TunnelStatus(phase = Phase.GUARDING, error = error, appsPaused = pause || it.appsPaused)
            }
        }
        if (pause) AppGate.closeAll(this, update) else update()
    }

    private fun markConnected(server: StoredServer) {
        // After a failover the working server becomes the selected one.
        if (store.selectedServer()?.link != server.link) store.selectByLink(server.link)
        AppGate.open(this) {
            setStatus {
                TunnelStatus(
                    phase = Phase.CONNECTED,
                    serverName = server.name,
                    connectedSince = System.currentTimeMillis(),
                    appsPaused = false,
                )
            }
        }
        refreshSubscriptionsIfDue()
    }

    private fun markDropped(name: String) {
        setStatus { it.copy(phase = Phase.RECONNECTING, serverName = name, connectedSince = null, error = null) }
    }

    private fun setStatus(transform: (TunnelStatus) -> TunnelStatus) {
        val before = TunnelState.status.value
        TunnelState.update(transform)
        val after = TunnelState.status.value
        if (before.phase != after.phase || before.error != after.error) {
            AppLog.i(TAG, "${before.phase} -> ${after.phase}" + (after.error?.let { " ($it)" } ?: ""))
        }
        Notifications.update(this, after)
    }

    // ---- TUN ------------------------------------------------------------------

    /**
     * TunnelLock's own interface. [fresh] replaces one that was built for
     * OpenVPN. A new interface takes over before the old one closes, so nothing
     * gets out in between.
     */
    @Synchronized
    private fun ensureTun(fresh: Boolean = false, excluded: Set<String> = emptySet()): Int? {
        val current = tun
        if (current != null && !fresh && excluded == tunExcluded) return current.fd
        val builder = baseBuilder(MTU, excluded)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            // IPv6 is routed into the tunnel too, so it can never bypass it.
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
        val created = establish(builder) ?: return current?.fd
        tun = created
        tunExcluded = excluded
        openVpnTun = null
        current?.let { runCatching { it.close() } }
        return created.fd
    }

    /**
     * The interface OpenVPN asked for, with the address its server assigned.
     * Everything is still routed into it (whatever routes the server pushes),
     * IPv6 included, so nothing can go around the tunnel.
     */
    @Synchronized
    private fun establishOpenVpnTun(request: OpenVpnTun): ParcelFileDescriptor? {
        val current = tun
        // OpenVPN cannot send apps to their own servers: they are left out, so lockdown cuts them off.
        val excluded = store.appServers.value.keys
        // A reconnect asking for the same interface gets the one we have.
        if (current != null && openVpnTun == request && excluded == tunExcluded) return current
        val builder = baseBuilder(request.mtu.coerceIn(576, 9000), excluded)
            .addAddress(request.ipv4 ?: "172.19.0.1", if (request.ipv4 != null) request.ipv4Prefix.coerceIn(1, 32) else 30)
            .addRoute("0.0.0.0", 0)
            .addAddress(request.ipv6 ?: "fdfe:dcba:9876::1", if (request.ipv6 != null) request.ipv6Prefix.coerceIn(1, 128) else 126)
            .addRoute("::", 0)
        for (dns in request.dns.ifEmpty { listOf("1.1.1.1") }) runCatching { builder.addDnsServer(dns) }
        request.domain?.let { domain -> runCatching { builder.addSearchDomain(domain) } }
        val created = establish(builder) ?: return null
        tun = created
        tunExcluded = excluded
        openVpnTun = request
        current?.let { runCatching { it.close() } }
        return created
    }

    private fun baseBuilder(mtu: Int, excluded: Set<String>): Builder {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(mtu)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        // The engines' own connections to the server must not loop back into the tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }
        excluded.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        return builder
    }

    private fun establish(builder: Builder): ParcelFileDescriptor? = try {
        builder.establish()
    } catch (e: Exception) {
        Log.e(TAG, "establish failed", e)
        null
    }

    @Synchronized
    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
        tunExcluded = emptySet()
        openVpnTun = null
    }

    private fun startInForeground() {
        val notification = Notifications.build(this, TunnelState.status.value)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID, notification)
            }
        } catch (e: Exception) {
            // The VPN keeps working without the notification; the system still holds it bound.
            Log.w(TAG, "startForeground failed", e)
        }
    }
}
