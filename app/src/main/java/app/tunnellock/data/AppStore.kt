package app.tunnellock.data

import android.content.Context
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParsedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredServer(
    val id: String,
    val name: String,
    /** The original share link or Xray JSON; re-parsed whenever the server is used. */
    val link: String,
    val protocol: String,
    val address: String,
    val subscriptionId: String? = null,
    /** null = not tested, -1 = test failed. */
    val latencyMs: Long? = null,
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long = 0,
    /** From the panel's Subscription-Userinfo header, when it sends one. */
    val usage: SubscriptionUsage? = null,
)

data class SubscriptionUsage(
    val usedBytes: Long,
    /** 0 = unlimited. */
    val totalBytes: Long,
    /** Unix seconds, 0 = never. */
    val expiresAt: Long,
)

/** Small persistent store (SharedPreferences + JSON). Lives in the secure-space copy of the app. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("tunnellock", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(readServers())
    val servers: StateFlow<List<StoredServer>> = _servers

    private val _subscriptions = MutableStateFlow(readSubscriptions())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedId: StateFlow<String?> = _selectedId

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect

    /**
     * Pause the protected apps while you are disconnected on purpose (so they
     * cannot be opened). A connection that drops mid-use never pauses them:
     * they stay open and only lose internet.
     */
    private val _pauseWhenDisconnected = MutableStateFlow(prefs.getBoolean(KEY_PAUSE_WHEN_DISCONNECTED, true))
    val pauseWhenDisconnected: StateFlow<Boolean> = _pauseWhenDisconnected

    @Synchronized
    fun addServers(parsed: List<ParsedServer>, subscriptionId: String? = null): Int {
        val added = parsed.map { it.toStored(subscriptionId) }
        saveServers(_servers.value + added)
        if (_selectedId.value == null || _servers.value.none { it.id == _selectedId.value }) {
            added.firstOrNull()?.let { select(it.id) }
        }
        return added.size
    }

    /** Replaces a subscription's servers, keeping the selection when a server with the same link survives. */
    @Synchronized
    fun replaceSubscriptionServers(subscriptionId: String, parsed: List<ParsedServer>, usage: SubscriptionUsage?) {
        val selectedLink = _servers.value.find { it.id == _selectedId.value }?.link
        val fresh = parsed.map { it.toStored(subscriptionId) }
        saveServers(_servers.value.filter { it.subscriptionId != subscriptionId } + fresh)
        fresh.find { it.link == selectedLink }?.let { select(it.id) }
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == subscriptionId) it.copy(updatedAt = System.currentTimeMillis(), usage = usage ?: it.usage) else it
        }
        saveSubscriptions(_subscriptions.value)
    }

    @Synchronized
    fun removeServer(id: String) {
        saveServers(_servers.value.filter { it.id != id })
    }

    @Synchronized
    fun setLatency(id: String, latencyMs: Long) {
        saveServers(_servers.value.map { if (it.id == id) it.copy(latencyMs = latencyMs) else it })
    }

    fun select(id: String) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun setAutoConnect(enabled: Boolean) {
        _autoConnect.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun setPauseWhenDisconnected(enabled: Boolean) {
        _pauseWhenDisconnected.value = enabled
        prefs.edit().putBoolean(KEY_PAUSE_WHEN_DISCONNECTED, enabled).apply()
    }

    @Synchronized
    fun addSubscription(name: String, url: String): Subscription {
        val sub = Subscription(UUID.randomUUID().toString(), name, url)
        saveSubscriptions(_subscriptions.value + sub)
        return sub
    }

    @Synchronized
    fun removeSubscription(id: String) {
        saveSubscriptions(_subscriptions.value.filter { it.id != id })
        saveServers(_servers.value.filter { it.subscriptionId != id })
    }

    fun selectedServer(): StoredServer? = _servers.value.find { it.id == _selectedId.value }

    fun selectedProfile(): ServerProfile? = selectedServer()?.let { profileOf(it) }

    fun profileOf(server: StoredServer): ServerProfile? =
        runCatching { LinkParser.parse(server.link).copy(name = server.name) }.getOrNull()

    private fun ParsedServer.toStored(subscriptionId: String?) = StoredServer(
        id = UUID.randomUUID().toString(),
        name = profile.name,
        link = source,
        protocol = profile.protocol.label,
        address = profile.displayAddress,
        subscriptionId = subscriptionId,
    )

    private fun saveServers(list: List<StoredServer>) {
        _servers.value = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("link", it.link)
                    .put("protocol", it.protocol)
                    .put("address", it.address)
                    .putOpt("subscriptionId", it.subscriptionId)
                    .putOpt("latencyMs", it.latencyMs),
            )
        }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun readServers(): List<StoredServer> {
        val array = runCatching { JSONArray(prefs.getString(KEY_SERVERS, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            StoredServer(
                id = o.optString("id"),
                name = o.optString("name"),
                link = o.optString("link"),
                protocol = o.optString("protocol"),
                address = o.optString("address"),
                subscriptionId = o.optString("subscriptionId").takeIf { it.isNotEmpty() },
                latencyMs = if (o.has("latencyMs")) o.optLong("latencyMs") else null,
            )
        }
    }

    private fun saveSubscriptions(list: List<Subscription>) {
        _subscriptions.value = list
        val array = JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("updatedAt", it.updatedAt)
            it.usage?.let { u ->
                o.put("used", u.usedBytes).put("total", u.totalBytes).put("expires", u.expiresAt)
            }
            array.put(o)
        }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, array.toString()).apply()
    }

    private fun readSubscriptions(): List<Subscription> {
        val array = runCatching { JSONArray(prefs.getString(KEY_SUBSCRIPTIONS, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val usage = if (o.has("used")) {
                SubscriptionUsage(o.optLong("used"), o.optLong("total"), o.optLong("expires"))
            } else {
                null
            }
            Subscription(o.optString("id"), o.optString("name"), o.optString("url"), o.optLong("updatedAt"), usage)
        }
    }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_SUBSCRIPTIONS = "subscriptions"
        const val KEY_SELECTED = "selected"
        const val KEY_AUTO_CONNECT = "autoConnect"
        const val KEY_PAUSE_WHEN_DISCONNECTED = "pauseWhenDisconnected"
    }
}
