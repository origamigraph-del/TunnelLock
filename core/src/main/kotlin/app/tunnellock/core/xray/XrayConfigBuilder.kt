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
 * Builds the Xray JSON config. Everything that enters the TUN goes to the proxy;
 * DNS queries are answered by Xray's own resolver, whose upstream (DoH) also goes
 * through the proxy. Nothing is sent out directly, so there is no leak path.
 */
object XrayConfigBuilder {
    const val TUN_TAG = "tun-in"
    const val PROXY_TAG = "proxy"
    const val DNS_TAG = "dns-out"

    fun build(profile: ServerProfile, options: CoreOptions = CoreOptions()): String =
        if (profile.protocol == Protocol.CUSTOM) buildCustom(profile, options) else buildStandard(profile, options)

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
        val root = JSONObject(requireNotNull(profile.rawJson) { "Custom profile without JSON" })
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
