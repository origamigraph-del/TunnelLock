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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
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

/**
 * The tunnel. The TUN interface stays up for the whole life of the service, so
 * whenever the proxy is not working the protected apps' packets end up inside
 * the TUN and are dropped there; they never reach the normal network.
 *
 * In the secure space Android's always-on lockdown adds a second layer: if this
 * service is not running at all, the system itself blocks every app.
 *
 * Losing the connection only cuts the internet; the apps stay open. They are
 * paused (cannot be opened) only when the user disconnects on purpose and the
 * "pause when disconnected" setting is on.
 *
 * If the server keeps failing, the other servers are tested outside the tunnel
 * and the fastest one that works takes over (failover setting).
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

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A newly copied app is not in use yet; keep it closed while we are not connected.
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (store.pauseWhenDisconnected.value) AppGate.closeIfDisconnected(context, listOf(pkg))
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
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
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
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        commands.close()
        scope.cancel()
        XrayCore.stop()
        closeTun()
        // Apps are not paused here: someone may be using one. Lockdown cuts their internet.
        TunnelState.update { it.copy(phase = Phase.OFF, connectedSince = null) }
        super.onDestroy()
    }

    // ---- command handling (one at a time) -----------------------------------

    private suspend fun handle(command: Command) {
        val phase = TunnelState.status.value.phase
        when (command) {
            Command.Guard -> if (phase == Phase.OFF) enterGuard(pause = store.pauseWhenDisconnected.value)
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
                // Old connections are bound to the previous network; start over (the TUN stays up).
                startSession(SessionMode.NETWORK)
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
        val server = store.selectedServer()
        val profile = server?.let { store.profileOf(it) }
        if (server == null || profile == null) {
            enterGuard(getString(R.string.error_no_server), pause = mode == SessionMode.USER && store.pauseWhenDisconnected.value)
            return
        }
        val fd = ensureTun()
        if (fd == null) {
            setStatus { it.copy(phase = Phase.OFF, connectedSince = null, error = getString(R.string.error_vpn_permission)) }
            return
        }
        val config = XrayConfigBuilder.build(profile, CoreOptions(mtu = MTU))
        session = scope.launch { runSession(server, config, fd, mode) }
    }

    private suspend fun stopSession() {
        session?.cancelAndJoin()
        session = null
        XrayCore.stop()
    }

    /**
     * Keeps a server connection alive. "Connected" (and letting the apps run)
     * only happens after a real request went through the proxy.
     */
    private suspend fun runSession(first: StoredServer, firstConfig: String, fd: Int, mode: SessionMode) {
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
        while (true) {
            val started = runCatching { XrayCore.start(this, config, fd) }
            started.exceptionOrNull()?.let { AppLog.w(TAG, "core start failed", it) }
            if (started.isSuccess && checkConnectivity()) {
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
                        pause = mode == SessionMode.USER && store.pauseWhenDisconnected.value,
                    )
                    return
                }
                if (mode == SessionMode.USER && TunnelState.status.value.phase == Phase.CONNECTING && failures >= INITIAL_ATTEMPTS) {
                    XrayCore.stop()
                    enterGuard(getString(R.string.error_server_unreachable), pause = store.pauseWhenDisconnected.value)
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
            .mapNotNull { server -> store.profileOf(server)?.let { server to it } }
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
        return runCatching { best.first to XrayConfigBuilder.build(best.second, CoreOptions(mtu = MTU)) }.getOrNull()
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

    private fun checkConnectivity(): Boolean {
        val ok = TEST_URLS.any { XrayCore.measureDelay(it) >= 0 }
        if (!ok) AppLog.w(TAG, "health check through the tunnel failed")
        return ok
    }

    // ---- state changes --------------------------------------------------------

    /** Not connected. The TUN stays up (no internet); the apps are paused only if [pause]. */
    private fun enterGuard(error: String? = null, pause: Boolean) {
        if (ensureTun() == null) {
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

    @Synchronized
    private fun ensureTun(): Int? {
        tun?.let { return it.fd }
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            // IPv6 is routed into the tunnel too, so it can never bypass it.
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        // The core's own connections to the server must not loop back into the tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        tun = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        return tun?.fd
    }

    @Synchronized
    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
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
