package app.tunnellock.core.xray

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.model.StreamConfig
import org.json.JSONArray
import org.json.JSONObject

data class CoreOptions(
    /** Add the TUN inbound that reads the VpnService file descriptor. */
    val useTun: Boolean = true,
    val mtu: Int = 1500,
    /** DNS servers Xray resolves app queries with; reached through the proxy. */
    val dnsServers: List<String> = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"),
    /** UseIPv4 keeps apps from trying IPv6 the server may not have. */
    val queryStrategy: String = "UseIPv4",
    val logLevel: String = "warning",
)

/**
 * Apps that go through their own server instead of the main one, by Android UID.
 * A null [profile] (the server is gone or cannot run on Xray) cuts them off.
 */
data class AppRoute(val uids: List<Int>, val profile: ServerProfile?)

/**
 * Builds the Xray JSON config. Everything that enters the TUN goes to the proxy;
 * DNS queries are answered by Xray's own resolver, whose upstream (DoH) also goes
 * through the proxy. Nothing is sent out directly, so there is no leak path.
 */
object XrayConfigBuilder {
    const val TUN_TAG = "tun-in"
    const val PROXY_TAG = "proxy"
    const val DNS_TAG = "dns-out"
    const val APP_TAG_PREFIX = "tl-app-"
    const val APP_BLOCK_TAG = "tl-block"

    /**
     * Xray names a connection's app by its UID once TunnelLock registers a lookup;
     * "-1" is a connection whose app Android could not tell.
     */
    const val UNKNOWN_APP = "-1"

    fun build(profile: ServerProfile, options: CoreOptions = CoreOptions(), appRoutes: List<AppRoute> = emptyList()): String {
        require(profile.protocol.isXray) { "${profile.protocol.label} does not run on Xray" }
        val config = if (profile.protocol == Protocol.CUSTOM) buildCustom(profile, options) else buildStandard(profile, options)
        return if (appRoutes.isEmpty()) config else addAppRoutes(JSONObject(config), appRoutes).toString()
    }

    /** Whether a server can be some apps' own server (a plain Xray outbound, not a whole JSON config). */
    fun canRouteApps(profile: ServerProfile): Boolean = profile.protocol.isXray && profile.protocol != Protocol.CUSTOM

    /**
     * Each app with its own server goes only there: its rule comes first (its DNS
     * included), and if that server fails it has no internet, never the main server.
     * A connection whose app is unknown is dropped rather than risk the wrong server
     * (DNS aside, which stays with the main server's resolver).
     */
    private fun addAppRoutes(root: JSONObject, appRoutes: List<AppRoute>): JSONObject {
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val rules = JSONArray()
        appRoutes.filter { it.uids.isNotEmpty() }.forEachIndexed { i, route ->
            val target = route.profile?.takeIf { canRouteApps(it) }?.let { server ->
                val tag = APP_TAG_PREFIX + (i + 1)
                outbounds.put(outbound(server).put("tag", tag))
                tag
            } ?: APP_BLOCK_TAG
            rules.put(JSONObject().put("process", JSONArray(route.uids.map { it.toString() })).put("outboundTag", target))
        }
        rules.put(
            JSONObject()
                .put("process", JSONArray().put(UNKNOWN_APP))
                .put("port", "0-52,54-65535")
                .put("outboundTag", APP_BLOCK_TAG),
        )
        outbounds.put(JSONObject().put("tag", APP_BLOCK_TAG).put("protocol", "blackhole"))
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val existing = routing.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until existing.length()) rules.put(existing.get(i))
        routing.put("rules", rules)
        return root
    }

    private fun buildStandard(profile: ServerProfile, options: CoreOptions): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", options.logLevel))
        root.put(
            "dns",
            JSONObject()
                .put("servers", JSONArray(options.dnsServers))
                .put("queryStrategy", options.queryStrategy),
        )
        root.put("inbounds", if (options.useTun) JSONArray().put(tunInbound(TUN_TAG, options, null)) else JSONArray())
        root.put(
            "outbounds",
            JSONArray()
                .put(outbound(profile))
                .put(JSONObject().put("tag", DNS_TAG).put("protocol", "dns"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")),
        )
        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("inboundTag", JSONArray().put(TUN_TAG))
                            .put("port", "53")
                            .put("outboundTag", DNS_TAG),
                    ),
                ),
        )
        return root.toString()
    }

    /**
     * A user-supplied config keeps its outbounds, routing and DNS; only the
     * inbounds are replaced by our TUN inbound. The TUN inbound takes the tag of
     * the config's first inbound so routing rules written for it still match.
     */
    private fun buildCustom(profile: ServerProfile, options: CoreOptions): String {
        val root = JSONObject(requireNotNull(profile.rawConfig) { "Custom profile without JSON" })
        val firstInbound = root.optJSONArray("inbounds")?.optJSONObject(0)
        val tag = firstInbound?.optString("tag")?.takeIf { it.isNotEmpty() } ?: TUN_TAG
        root.put(
            "inbounds",
            if (options.useTun) JSONArray().put(tunInbound(tag, options, firstInbound?.optJSONObject("sniffing"))) else JSONArray(),
        )
        root.remove("remarks")
        return root.toString()
    }

    private fun tunInbound(tag: String, options: CoreOptions, sniffing: JSONObject?): JSONObject =
        JSONObject()
            .put("tag", tag)
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("mtu", options.mtu))
            .put(
                "sniffing",
                sniffing ?: JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls")),
            )

    fun outbound(p: ServerProfile): JSONObject {
        val o = JSONObject().put("tag", PROXY_TAG)
        when (p.protocol) {
            Protocol.VLESS -> {
                o.put("protocol", "vless")
                o.put(
                    "settings",
                    JSONObject()
                        .put("address", p.address)
                        .put("port", p.port)
                        .put("id", p.secret)
                        .put("encryption", p.method ?: "none")
                        .putOpt("flow", p.flow),
                )
            }
            Protocol.VMESS -> {
                o.put("protocol", "vmess")
                o.put(
                    "settings",
                    JSONObject()
                        .put("address", p.address)
                        .put("port", p.port)
                        .put("id", p.secret)
                        .put("security", p.method ?: "auto"),
                )
            }
            Protocol.TROJAN -> {
                o.put("protocol", "trojan")
                o.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", p.address)
                                .put("port", p.port)
                                .put("password", p.secret)
                                .putOpt("flow", p.flow),
                        ),
                    ),
                )
            }
            Protocol.SHADOWSOCKS -> {
                o.put("protocol", "shadowsocks")
                o.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", p.address)
                                .put("port", p.port)
                                .put("method", p.method)
                                .put("password", p.secret),
                        ),
                    ),
                )
            }
            Protocol.HYSTERIA2 -> {
                o.put("protocol", "hysteria")
                o.put("settings", JSONObject().put("version", 2).put("address", p.address).put("port", p.port))
            }
            Protocol.CUSTOM -> error("Custom profiles carry their own outbounds")
            Protocol.OPENVPN -> error("OpenVPN does not run on Xray")
        }
        if (p.protocol != Protocol.SHADOWSOCKS) {
            val stream = streamSettings(p.stream)
            if (p.protocol == Protocol.HYSTERIA2) {
                stream.put("hysteriaSettings", JSONObject().put("version", 2).put("auth", p.secret))
                p.stream.obfsPassword?.let { password ->
                    val salamander = JSONObject().put("type", "salamander").put("settings", JSONObject().put("password", password))
                    stream.put("finalmask", JSONObject().put("udp", JSONArray().put(salamander)))
                }
            }
            o.put("streamSettings", stream)
        }
        return o
    }

    internal fun streamSettings(s: StreamConfig): JSONObject {
        val network = when (s.network) {
            "ws" -> "ws"
            "xhttp" -> "xhttp"
            "kcp" -> "kcp"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "hysteria" -> "hysteria"
            else -> "raw"
        }
        val o = JSONObject().put("network", network).put("security", s.security)
        when (s.security) {
            "tls" -> o.put(
                "tlsSettings",
                JSONObject()
                    .putOpt("serverName", s.sni ?: s.host)
                    .putOpt("fingerprint", s.fingerprint?.lowercase())
                    .putOpt("alpn", s.alpn.takeIf { it.isNotEmpty() }?.let { JSONArray(it) })
                    .putOpt("pinnedPeerCertSha256", s.pinnedPeerCertSha256)
                    .putOpt("verifyPeerCertByName", s.verifyPeerCertByName),
            )
            "reality" -> o.put(
                "realitySettings",
                JSONObject()
                    .putOpt("serverName", s.sni)
                    .put("fingerprint", s.fingerprint?.lowercase() ?: "chrome")
                    .putOpt("publicKey", s.realityPublicKey)
                    .putOpt("shortId", s.realityShortId)
                    .putOpt("spiderX", s.realitySpiderX)
                    .putOpt("mldsa65Verify", s.realityMldsa65Verify),
            )
        }
        when (network) {
            "raw" -> if (s.headerType == "http") {
                val request = JSONObject()
                    .put("path", JSONArray().put(s.path ?: "/"))
                    .putOpt("headers", s.host?.let { host -> JSONObject().put("Host", JSONArray(host.split(',').map { it.trim() })) })
                o.put("rawSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", request)))
            }
            "ws" -> o.put("wsSettings", JSONObject().put("path", s.path ?: "/").putOpt("host", s.host))
            "httpupgrade" -> o.put("httpupgradeSettings", JSONObject().put("path", s.path ?: "/").putOpt("host", s.host))
            "xhttp" -> {
                val x = JSONObject()
                    .put("path", s.path ?: "/")
                    .putOpt("host", s.host)
                    .put("mode", s.mode ?: "auto")
                s.xhttpExtra?.let { extra -> runCatching { JSONObject(extra) }.getOrNull()?.let { x.put("extra", it) } }
                o.put("xhttpSettings", x)
            }
            "grpc" -> o.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", s.serviceName ?: "")
                    .put("multiMode", s.mode == "multi")
                    .putOpt("authority", s.authority),
            )
            "kcp" -> o.put("kcpSettings", JSONObject())
        }
        return o
    }
}
