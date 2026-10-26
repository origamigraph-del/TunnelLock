package app.tunnellock.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.model.Protocol
import app.tunnellock.core.openvpn.OpenVpnConfig
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParseException
import app.tunnellock.core.parser.ParsedServer
import app.tunnellock.core.xray.CoreOptions
import app.tunnellock.core.xray.XrayConfigBuilder
import app.tunnellock.data.AppLog
import app.tunnellock.data.StoredServer
import app.tunnellock.data.Subscription
import app.tunnellock.data.SubscriptionUpdater
import app.tunnellock.strict.AppGate
import app.tunnellock.strict.Policy
import app.tunnellock.vpn.TunnelState
import app.tunnellock.vpn.TunnelVpnService
import app.tunnellock.vpn.XrayCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TunnelLockApp
    val store = app.store
    val status = TunnelState.status

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _protectedApps = MutableStateFlow<List<ProtectedApp>?>(null)
    val protectedApps: StateFlow<List<ProtectedApp>?> = _protectedApps

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    val updating: StateFlow<Set<String>> = SubscriptionUpdater.updating

    private val _loginRequest = MutableStateFlow<LoginRequest?>(null)

    /** An OpenVPN server waiting for its login (new import or edit). */
    val loginRequest: StateFlow<LoginRequest?> = _loginRequest

    init {
        // Opening the app is a good moment to refresh subscriptions older than 12 hours (if turned on).
        viewModelScope.launch { SubscriptionUpdater.updateStale(store) }
    }

    private fun say(res: Int, vararg args: Any) {
        _messages.tryEmit(app.getString(res, *args))
    }

    // ---- tunnel ------------------------------------------------------------

    fun toggleConnection() {
        val action = if (status.value.phase.isActive) TunnelVpnService.ACTION_DISCONNECT else TunnelVpnService.ACTION_CONNECT
        TunnelVpnService.send(app, action)
    }

    fun ensureGuard() {
        TunnelVpnService.send(app, TunnelVpnService.ACTION_GUARD)
    }

    // ---- servers -----------------------------------------------------------

    fun importText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            say(R.string.msg_nothing_to_import)
            return
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            addSubscription(app.getString(R.string.subscription_default_name), trimmed)
            return
        }
        if (OpenVpnConfig.looksLikeOvpn(trimmed)) {
            importOvpn(trimmed, null)
            return
        }
        val report = runCatching { LinkParser.parseReport(trimmed) }.getOrNull()
        if (report == null || report.servers.isEmpty()) {
            val unsupported = report?.skipped?.filterKeys { it != "other" }
            val reason = if (!unsupported.isNullOrEmpty()) {
                "unsupported: " + unsupported.entries.joinToString { "${it.key}×${it.value}" }
            } else {
                runCatching { LinkParser.parse(trimmed); "" }.exceptionOrNull()?.message.orEmpty()
            }
            AppLog.w("Import", "nothing imported: $reason")
            say(R.string.msg_import_failed, reason)
            return
        }
        val count = store.addServers(report.servers)
        say(R.string.msg_imported, count)
    }

    /** Opens an .ovpn file picked by the user. */
    fun importOvpnFile(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val text = app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > MAX_OVPN_BYTES) throw IOException("file is too large for an .ovpn")
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException("cannot open the file")
                    name?.substringBeforeLast('.') to text
                }
            }
            file.onSuccess { (name, text) -> importOvpn(text, name) }
                .onFailure { say(R.string.msg_file_read_failed, it.message.orEmpty()) }
        }
    }

    private fun importOvpn(text: String, name: String?) {
        val parsed = try {
            ParsedServer(OpenVpnConfig.parse(text, name), text.trim())
        } catch (e: ParseException) {
            AppLog.w("Import", "OpenVPN config not usable: ${e.message}")
            say(R.string.msg_import_failed, e.message.orEmpty())
            return
        }
        val needs = OpenVpnConfig.needs(text)
        if (needs.credentials || needs.keyPassword) {
            _loginRequest.value = LoginRequest(parsed.profile.name, needs.credentials, needs.keyPassword, parsed = parsed)
        } else {
            val stored = store.addOpenVpn(parsed, name, null, null, null)
            say(R.string.msg_ovpn_imported, stored.name)
        }
    }

    fun editLogin(server: StoredServer) {
        val needs = runCatching { OpenVpnConfig.needs(server.link) }.getOrNull() ?: return
        _loginRequest.value = LoginRequest(
            name = server.name,
            needsUser = needs.credentials || !needs.keyPassword,
            needsKey = needs.keyPassword,
            server = server,
        )
    }

    fun submitLogin(request: LoginRequest, name: String, username: String, password: String, keyPassword: String) {
        _loginRequest.value = null
        if (request.parsed != null) {
            val stored = store.addOpenVpn(request.parsed, name, username.trim(), password, keyPassword)
            say(R.string.msg_ovpn_imported, stored.name)
        } else if (request.server != null) {
            store.setLogin(request.server.id, username.trim(), password, keyPassword)
            say(R.string.msg_login_saved, request.server.name)
            // A running connection picks up the new login by reconnecting.
            if (store.selectedId.value == request.server.id && status.value.phase.isActive) {
                TunnelVpnService.send(app, TunnelVpnService.ACTION_CONNECT)
            }
        }
    }

    fun dismissLogin() {
        _loginRequest.value = null
    }

    fun select(server: StoredServer) {
        val changed = store.selectedId.value != server.id
        store.select(server.id)
        // Switching servers while connected reconnects to the new one.
        if (changed && status.value.phase.isActive) TunnelVpnService.send(app, TunnelVpnService.ACTION_CONNECT)
    }

    fun delete(server: StoredServer) = store.removeServer(server.id)

    fun testAll() {
        if (_testing.value) return
        _testing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(3)
            store.servers.value.map { server ->
                async {
                    gate.withPermit {
                        val profile = store.profileOf(server)
                        val delay = when {
                            profile == null -> -1L
                            // OpenVPN cannot be tested through Xray: a TCP server is at least reachable or not.
                            profile.protocol == Protocol.OPENVPN -> if (profile.method == "tcp") tcpDelay(profile.address, profile.port) else null
                            else -> {
                                val config = XrayConfigBuilder.build(profile, CoreOptions(useTun = false))
                                XrayCore.testServer(app, config, "https://www.gstatic.com/generate_204")
                            }
                        }
                        if (delay != null) store.setLatency(server.id, delay)
                    }
                }
            }.awaitAll()
            _testing.value = false
        }
    }

    fun addSubscription(name: String, url: String) {
        val sub = store.addSubscription(name.ifBlank { app.getString(R.string.subscription_default_name) }, url.trim())
        updateSubscription(sub)
    }

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch {
            val outcome = SubscriptionUpdater.update(store, sub) ?: return@launch
            outcome.onSuccess { result ->
                val skipped = result.report.skipped.values.sum()
                if (skipped > 0) {
                    say(R.string.msg_subscription_updated_skipped, sub.name, result.report.servers.size, skipped)
                } else {
                    say(R.string.msg_subscription_updated, sub.name, result.report.servers.size)
                }
            }.onFailure { e ->
                say(R.string.msg_subscription_failed, sub.name, e.message.orEmpty())
            }
        }
    }

    fun deleteSubscription(sub: Subscription) = store.removeSubscription(sub.id)

    private fun tcpDelay(host: String, port: Int): Long = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), 5_000) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    private companion object {
        const val MAX_OVPN_BYTES = 1_000_000
    }

    // ---- apps --------------------------------------------------------------

    fun refreshProtectedApps() {
        viewModelScope.launch {
            _protectedApps.value = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val installed = Policy.protectedApps(app)
                Policy.pruneNeverPause(app, installed.map { it.packageName })
                val neverPause = Policy.neverPausePackages(app)
                installed.map { info ->
                    ProtectedApp(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        info = info,
                        system = Policy.isAddedSystemApp(app, info.packageName),
                        paused = Policy.isSuspended(app, info.packageName),
                        neverPause = info.packageName in neverPause,
                    )
                }.sortedBy { it.label.lowercase() }
            }
        }
    }

    fun setNeverPause(item: ProtectedApp, value: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AppGate.setNeverPause(app, item.packageName, value)
            AppLog.i("Apps", "never-pause ${if (value) "on" else "off"} for one app")
            refreshProtectedApps()
        }
    }

    fun removeApp(context: Context, item: ProtectedApp) {
        if (item.system) {
            Policy.removeSystemApp(app, item.packageName)
            refreshProtectedApps()
        } else {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.packageName}")))
        }
    }

    /** Apps are picked in the personal copy of TunnelLock: only it can see the phone's apps. */
    fun openPhoneApps(context: Context) {
        val crossProfile = context.getSystemService(CrossProfileApps::class.java)
        val personal = crossProfile.targetUserProfiles.firstOrNull()
        if (personal == null) {
            say(R.string.msg_personal_app_missing)
            return
        }
        crossProfile.startMainActivity(ComponentName(context, MainActivity::class.java), personal)
    }

    // ---- settings ----------------------------------------------------------

    fun setAutoConnect(enabled: Boolean) = store.setAutoConnect(enabled)

    fun setPauseWhenDisconnected(enabled: Boolean) = store.setPauseWhenDisconnected(enabled)

    fun setFailover(enabled: Boolean) = store.setFailover(enabled)

    fun setAutoUpdateSubscriptions(enabled: Boolean) {
        store.setAutoUpdateSubscriptions(enabled)
        if (enabled) viewModelScope.launch { SubscriptionUpdater.updateStale(store) }
    }

    fun reapplyPolicies() {
        Policy.finalizeProfile(app)
        say(if (Policy.isLockdownActive(app)) R.string.msg_lockdown_ok else R.string.msg_lockdown_failed)
    }
}

/** The OpenVPN login dialog: for a new server ([parsed]) or an existing one ([server]). */
data class LoginRequest(
    val name: String,
    val needsUser: Boolean,
    val needsKey: Boolean,
    val parsed: ParsedServer? = null,
    val server: StoredServer? = null,
)

data class ProtectedApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    /** A system app switched on in the secure space (removed by hiding, not uninstalling). */
    val system: Boolean,
    val paused: Boolean,
    /** Can be opened while disconnected (still no internet). */
    val neverPause: Boolean,
)
