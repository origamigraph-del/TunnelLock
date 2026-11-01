package app.tunnellock.data

import android.content.Context
import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParsedServer
import app.tunnellock.core.parser.SshLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredServer(
    val id: String,
    val name: String,
    /** The original share link, Xray JSON or .ovpn file; re-parsed whenever the server is used. */
    val link: String,
    val protocol: String,
    val address: String,
    val subscriptionId: String? = null,
    /** null = not tested, -1 = test failed. */
    val latencyMs: Long? = null,
    /** OpenVPN and SSH login, entered by the user (never part of the config or link). */
    val username: String? = null,
    val password: String? = null,
    /** OpenVPN: the private key's password. SSH: the passphrase of [privateKey]. */
    val keyPassword: String? = null,
    /** SSH: a private key (OpenSSH or PEM text), when the account uses one. */
    val privateKey: String? = null,
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long = 0,
    /** From the panel's Subscription-Userinfo header, when it sends one. */
    val usage: SubscriptionUsage? = null,
    /** Entries of the last download this version could not read, by link type ("tuic", "vless"...). */
    val unreadable: Map<String, Int> = emptyMap(),
)

/**
 * An app's own server, by link (ids change when a subscription is refreshed). After a
 * refresh that changed the link it is found again by name, only within its subscription.
 */
data class AppServer(val link: String, val name: String, val subscriptionId: String? = null)

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

    /** When the server stops answering, switch to the fastest other server that works. Off by default. */
    private val _failover = MutableStateFlow(prefs.getBoolean(KEY_FAILOVER, false))
    val failover: StateFlow<Boolean> = _failover

    /**
     * Apps that use their own server instead of the main one (package -> server).
     * Empty by default: every app uses the main server.
     */
    private val _appServers = MutableStateFlow(readAppServers())
    val appServers: StateFlow<Map<String, AppServer>> = _appServers

    /** Refresh subscriptions older than 12 hours in the background. Off by default. */
    private val _autoUpdateSubscriptions = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE_SUBSCRIPTIONS, false))
    val autoUpdateSubscriptions: StateFlow<Boolean> = _autoUpdateSubscriptions

    @Synchronized
    fun addServers(parsed: List<ParsedServer>, subscriptionId: String? = null): Int {
        val added = parsed.map { it.toStored(subscriptionId) }
        saveServers(_servers.value + added)
        if (_selectedId.value == null || _servers.value.none { it.id == _selectedId.value }) {
            added.firstOrNull()?.let { select(it.id) }
        }
        return added.size
    }

    /**
     * Replaces a subscription's servers. If the selected server came from it, the
     * selection moves to the same link, else the same name, else its first server,
     * so a refresh never leaves nothing selected.
     */
    @Synchronized
    fun replaceSubscriptionServers(
        subscriptionId: String,
        parsed: List<ParsedServer>,
        usage: SubscriptionUsage?,
        unreadable: Map<String, Int> = emptyMap(),
    ) {
        val selected = _servers.value.find { it.id == _selectedId.value }
        val fresh = parsed.map { it.toStored(subscriptionId) }
        saveServers(_servers.value.filter { it.subscriptionId != subscriptionId } + fresh)
        if (selected != null && selected.subscriptionId == subscriptionId) {
            (fresh.find { it.link == selected.link } ?: fresh.find { it.name == selected.name } ?: fresh.firstOrNull())
                ?.let { select(it.id) }
        }
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == subscriptionId) {
                it.copy(updatedAt = System.currentTimeMillis(), usage = usage ?: it.usage, unreadable = unreadable)
            } else {
                it
            }
        }
        saveSubscriptions(_subscriptions.value)
    }

    /** Adds one OpenVPN server with the login the user entered. */
    @Synchronized
    fun addOpenVpn(parsed: ParsedServer, name: String?, username: String?, password: String?, keyPassword: String?): StoredServer {
        val server = parsed.toStored(null).copy(
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.profile.name,
            username = username?.takeIf { it.isNotEmpty() },
            password = password?.takeIf { it.isNotEmpty() },
            keyPassword = keyPassword?.takeIf { it.isNotEmpty() },
        )
        saveServers(_servers.value + server)
        if (_selectedId.value == null || _servers.value.none { it.id == _selectedId.value }) select(server.id)
        return server
    }

    /** Adds an SSH server entered in the form, or replaces server [replaceId] with it. */
    @Synchronized
    fun saveSsh(link: String, name: String, address: String, login: StoredServer, replaceId: String? = null): StoredServer {
        val existing = replaceId?.let { id -> _servers.value.find { it.id == id } }
        // Edited or added again: the next connection learns the server's key anew.
        existing?.let { forgetSshHostKey(it.link) }
        forgetSshHostKey(link)
        val server = (existing ?: StoredServer(UUID.randomUUID().toString(), name, link, "SSH", address)).copy(
            name = name,
            link = link,
            protocol = "SSH",
            address = address,
            username = login.username?.takeIf { it.isNotEmpty() },
            password = login.password?.takeIf { it.isNotEmpty() },
            keyPassword = login.keyPassword?.takeIf { it.isNotEmpty() },
            privateKey = login.privateKey?.takeIf { it.isNotBlank() },
        )
        saveServers(if (existing != null) _servers.value.map { if (it.id == existing.id) server else it } else _servers.value + server)
        if (_selectedId.value == null || _servers.value.none { it.id == _selectedId.value }) select(server.id)
        return server
    }

    /** SSH host keys seen on the first connection, by [SshLink.hostKeyId]. */
    fun sshHostKey(server: String): String? = prefs.getString(KEY_SSH_HOST_KEYS_PREFIX + server, null)

    fun setSshHostKey(server: String, fingerprint: String) = prefs.edit().putString(KEY_SSH_HOST_KEYS_PREFIX + server, fingerprint).apply()

    private fun forgetSshHostKey(link: String) {
        val profile = runCatching { LinkParser.parse(link) }.getOrNull()?.takeIf { it.protocol == Protocol.SSH } ?: return
        prefs.edit().remove(KEY_SSH_HOST_KEYS_PREFIX + SshLink.hostKeyId(profile)).apply()
    }

    @Synchronized
    fun setLogin(id: String, username: String?, password: String?, keyPassword: String?) {
        saveServers(
            _servers.value.map {
                if (it.id != id) {
                    it
                } else {
                    it.copy(
                        username = username?.takeIf { v -> v.isNotEmpty() },
                        password = password?.takeIf { v -> v.isNotEmpty() },
                        keyPassword = keyPassword?.takeIf { v -> v.isNotEmpty() },
                    )
                }
            },
        )
    }

    @Synchronized
    fun removeServer(id: String) {
        _servers.value.find { it.id == id }?.let { forgetSshHostKey(it.link) }
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

    /** Selects the server with this link. Ids change when a subscription is refreshed; links do not. */
    @Synchronized
    fun selectByLink(link: String) {
        _servers.value.find { it.link == link }?.let { select(it.id) }
    }

    fun setAutoConnect(enabled: Boolean) {
        _autoConnect.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun setPauseWhenDisconnected(enabled: Boolean) {
        _pauseWhenDisconnected.value = enabled
        prefs.edit().putBoolean(KEY_PAUSE_WHEN_DISCONNECTED, enabled).apply()
    }

    fun setFailover(enabled: Boolean) {
        _failover.value = enabled
        prefs.edit().putBoolean(KEY_FAILOVER, enabled).apply()
    }

    fun setAutoUpdateSubscriptions(enabled: Boolean) {
        _autoUpdateSubscriptions.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_SUBSCRIPTIONS, enabled).apply()
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

    /** Gives [pkg] its own server, or back to the main server with null. */
    @Synchronized
    fun setAppServer(pkg: String, server: StoredServer?) {
        val updated = if (server == null) {
            _appServers.value - pkg
        } else {
            _appServers.value + (pkg to AppServer(server.link, server.name, server.subscriptionId))
        }
        saveAppServers(updated)
    }

    /** Forgets apps that are no longer in the secure space. */
    @Synchronized
    fun pruneAppServers(installed: Collection<String>) {
        val kept = _appServers.value.filterKeys { it in installed }
        if (kept.size != _appServers.value.size) saveAppServers(kept)
    }

    /**
     * The server an app was given; null if it is gone. A subscription refresh may change
     * its link: then the one server of the same name in the same subscription is it.
     * Anything less certain is no match, so the app is cut off rather than moved.
     */
    fun serverFor(choice: AppServer): StoredServer? {
        val servers = _servers.value
        servers.find { it.link == choice.link }?.let { return it }
        val subscription = choice.subscriptionId ?: return null
        return servers.filter { it.subscriptionId == subscription && it.name == choice.name }.singleOrNull()
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
                    .putOpt("latencyMs", it.latencyMs)
                    .putOpt("username", it.username)
                    .putOpt("password", it.password)
                    .putOpt("keyPassword", it.keyPassword)
                    .putOpt("privateKey", it.privateKey),
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
                username = o.optString("username").takeIf { it.isNotEmpty() },
                password = o.optString("password").takeIf { it.isNotEmpty() },
                keyPassword = o.optString("keyPassword").takeIf { it.isNotEmpty() },
                privateKey = o.optString("privateKey").takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun saveAppServers(map: Map<String, AppServer>) {
        _appServers.value = map
        val o = JSONObject()
        map.forEach { (pkg, server) ->
            o.put(pkg, JSONObject().put("link", server.link).put("name", server.name).putOpt("subscriptionId", server.subscriptionId))
        }
        prefs.edit().putString(KEY_APP_SERVERS, o.toString()).apply()
    }

    private fun readAppServers(): Map<String, AppServer> {
        val o = runCatching { JSONObject(prefs.getString(KEY_APP_SERVERS, "{}") ?: "{}") }.getOrElse { JSONObject() }
        return o.keys().asSequence().mapNotNull { pkg ->
            val v = o.optJSONObject(pkg) ?: return@mapNotNull null
            pkg to AppServer(v.optString("link"), v.optString("name"), v.optString("subscriptionId").takeIf { it.isNotEmpty() })
        }.toMap()
    }

    private fun saveSubscriptions(list: List<Subscription>) {
        _subscriptions.value = list
        val array = JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("updatedAt", it.updatedAt)
            it.usage?.let { u ->
                o.put("used", u.usedBytes).put("total", u.totalBytes).put("expires", u.expiresAt)
            }
            if (it.unreadable.isNotEmpty()) o.put("unreadable", JSONObject(it.unreadable as Map<*, *>))
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
            val unreadable = o.optJSONObject("unreadable")?.let { u -> u.keys().asSequence().associateWith { k -> u.optInt(k) } }.orEmpty()
            Subscription(o.optString("id"), o.optString("name"), o.optString("url"), o.optLong("updatedAt"), usage, unreadable)
        }
    }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_SUBSCRIPTIONS = "subscriptions"
        const val KEY_SELECTED = "selected"
        const val KEY_AUTO_CONNECT = "autoConnect"
        const val KEY_PAUSE_WHEN_DISCONNECTED = "pauseWhenDisconnected"
        const val KEY_FAILOVER = "failover"
        const val KEY_AUTO_UPDATE_SUBSCRIPTIONS = "autoUpdateSubscriptions"
        const val KEY_APP_SERVERS = "appServers"
        const val KEY_SSH_HOST_KEYS_PREFIX = "sshHostKey:"
    }
}
