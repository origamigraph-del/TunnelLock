package app.tunnellock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tunnellock.BuildConfig
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.xray.CoreOptions
import app.tunnellock.core.xray.XrayConfigBuilder
import app.tunnellock.data.StoredServer
import app.tunnellock.data.Subscription
import app.tunnellock.strict.AppCloner
import app.tunnellock.strict.AppItem
import app.tunnellock.strict.InstallEvent
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TunnelLockApp
    val store = app.store
    val status = TunnelState.status

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _apps = MutableStateFlow<List<AppItem>?>(null)
    val apps: StateFlow<List<AppItem>?> = _apps

    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing: StateFlow<Set<String>> = _installing

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updating: StateFlow<Set<String>> = _updating

    init {
        viewModelScope.launch {
            AppCloner.events.collect { event ->
                when (event) {
                    is InstallEvent.Installed -> say(R.string.msg_app_added, event.label)
                    is InstallEvent.Failed -> say(R.string.msg_app_add_failed, event.label, event.reason)
                }
                refreshApps()
            }
        }
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
        val parsed = runCatching { LinkParser.parseAll(trimmed) }.getOrElse { emptyList() }
        if (parsed.isEmpty()) {
            val reason = runCatching { LinkParser.parse(trimmed); "" }.exceptionOrNull()?.message.orEmpty()
            say(R.string.msg_import_failed, reason)
            return
        }
        val count = store.addServers(parsed)
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
                val body = withContext(Dispatchers.IO) { fetch(sub.url) }
                val parsed = LinkParser.parseAll(body)
                if (parsed.isEmpty()) {
                    say(R.string.msg_subscription_empty, sub.name)
                } else {
                    store.replaceSubscriptionServers(sub.id, parsed)
                    say(R.string.msg_subscription_updated, sub.name, parsed.size)
                }
            } catch (e: Exception) {
                say(R.string.msg_subscription_failed, sub.name, e.message.orEmpty())
            } finally {
                _updating.update { it - sub.id }
            }
        }
    }

    fun deleteSubscription(sub: Subscription) = store.removeSubscription(sub.id)

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "TunnelLock/${BuildConfig.VERSION_NAME}")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // ---- apps --------------------------------------------------------------

    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { AppCloner.listApps(app) }
        }
    }

    fun addApp(item: AppItem) {
        if (item.packageName in _installing.value) return
        _installing.update { it + item.packageName }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AppCloner.install(app, item) }
            } catch (e: Exception) {
                say(R.string.msg_app_add_failed, item.label, e.message.orEmpty())
            } finally {
                _installing.update { it - item.packageName }
            }
        }
    }

    // ---- settings ----------------------------------------------------------

    fun setAutoConnect(enabled: Boolean) = store.setAutoConnect(enabled)

    fun setPauseWhenDisconnected(enabled: Boolean) = store.setPauseWhenDisconnected(enabled)

    fun reapplyPolicies() {
        Policy.finalizeProfile(app)
        say(if (Policy.isLockdownActive(app)) R.string.msg_lockdown_ok else R.string.msg_lockdown_failed)
    }
}
