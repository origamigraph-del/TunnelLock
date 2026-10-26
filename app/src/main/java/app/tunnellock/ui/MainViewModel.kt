package app.tunnellock.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.xray.CoreOptions
import app.tunnellock.core.xray.XrayConfigBuilder
import app.tunnellock.data.AppLog
import app.tunnellock.data.StoredServer
import app.tunnellock.data.Subscription
import app.tunnellock.data.SubscriptionFetcher
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updating: StateFlow<Set<String>> = _updating

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
                        val delay = if (profile == null) {
                            -1L
                        } else {
                            val config = XrayConfigBuilder.build(profile, CoreOptions(useTun = false))
                            XrayCore.testServer(app, config, "https://www.gstatic.com/generate_204")
                        }
                        store.setLatency(server.id, delay)
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
        if (sub.id in _updating.value) return
        _updating.update { it + sub.id }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(sub.url) }
                store.replaceSubscriptionServers(sub.id, result.report.servers, result.usage)
                val skipped = result.report.skipped.values.sum()
                if (skipped > 0) {
                    say(R.string.msg_subscription_updated_skipped, sub.name, result.report.servers.size, skipped)
                } else {
                    say(R.string.msg_subscription_updated, sub.name, result.report.servers.size)
                }
            } catch (e: Exception) {
                say(R.string.msg_subscription_failed, sub.name, e.message.orEmpty())
            } finally {
                _updating.update { it - sub.id }
            }
        }
    }

    fun deleteSubscription(sub: Subscription) = store.removeSubscription(sub.id)

    // ---- apps --------------------------------------------------------------

    fun refreshProtectedApps() {
        viewModelScope.launch {
            _protectedApps.value = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                Policy.protectedApps(app).map { info ->
                    ProtectedApp(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        info = info,
                        system = Policy.isAddedSystemApp(app, info.packageName),
                        paused = Policy.isSuspended(app, info.packageName),
                    )
                }.sortedBy { it.label.lowercase() }
            }
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

    fun reapplyPolicies() {
        Policy.finalizeProfile(app)
        say(if (Policy.isLockdownActive(app)) R.string.msg_lockdown_ok else R.string.msg_lockdown_failed)
    }
}

data class ProtectedApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    /** A system app switched on in the secure space (removed by hiding, not uninstalling). */
    val system: Boolean,
    val paused: Boolean,
)
