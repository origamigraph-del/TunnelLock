package app.tunnellock.core.parser

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.model.StreamConfig
import app.tunnellock.core.openvpn.OpenVpnConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class ParseException(message: String) : Exception(message)

/** A parsed server plus the exact text it came from (kept so it can be stored and shared again). */
data class ParsedServer(val profile: ServerProfile, val source: String)

/** Result of reading many servers: what was understood, and how many lines of each kind were not. */
data class ParseReport(val servers: List<ParsedServer>, val skipped: Map<String, Int>)

/**
 * Parses share links (vless://, vmess://, trojan://, ss://), full Xray JSON
 * configs, OpenVPN .ovpn files and subscription bodies into [ServerProfile]s.
 */
object LinkParser {

    /** Parses a single link, a single Xray JSON object, or a whole .ovpn file. */
    fun parse(text: String): ServerProfile {
        val input = text.trim()
        if (input.startsWith("{")) return parseCustomJson(input)
        if (OpenVpnConfig.looksLikeOvpn(input)) return OpenVpnConfig.parse(input)
        val scheme = input.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> parseVless(input)
            "vmess" -> parseVmess(input)
            "trojan" -> parseTrojan(input)
            "ss" -> parseShadowsocks(input)
            "hysteria2", "hy2" -> parseHysteria2(input)
            "ssh" -> SshLink.parse(input)
            "" -> throw ParseException("Not a link")
            else -> throw ParseException("Unsupported link type: $scheme")
        }
    }

    /**
     * Parses text that may hold many servers: one link per line, a base64
     * subscription body, a JSON object, or a JSON array of Xray configs.
     * Lines that cannot be parsed are skipped.
     */
    fun parseMany(text: String): List<ServerProfile> = parseAll(text).map { it.profile }

    /** Like [parseMany], but keeps the source text of every server. */
    fun parseAll(text: String): List<ParsedServer> = parseReport(text).servers

    /** Parses everything it can and counts the lines it could not use, by link type. */
    fun parseReport(text: String): ParseReport {
        val input = text.trim().removePrefix("\uFEFF")
        if (input.isEmpty()) return ParseReport(emptyList(), emptyMap())
        // An .ovpn file is one server, not one per line.
        if (OpenVpnConfig.looksLikeOvpn(input)) return ParseReport(listOf(ParsedServer(OpenVpnConfig.parse(input), input)), emptyMap())
        if (input.startsWith("[")) {
            val array = try {
                JSONArray(input)
            } catch (e: Exception) {
                throw ParseException("Invalid JSON array")
            }
            val servers = (0 until array.length()).mapNotNull { i ->
                val source = array.optJSONObject(i)?.toString() ?: return@mapNotNull null
                runCatching { ParsedServer(parseCustomJson(source), source) }.getOrNull()
            }
            val skipped = array.length() - servers.size
            return ParseReport(servers, if (skipped > 0) mapOf("json" to skipped) else emptyMap())
        }
        if (input.startsWith("{")) return ParseReport(listOf(ParsedServer(parseCustomJson(input), input)), emptyMap())

        val body = if (!input.contains("://")) {
            decodeBase64OrNull(input.filterNot { it.isWhitespace() }) ?: input
        } else {
            input
        }
        val servers = mutableListOf<ParsedServer>()
        val skipped = linkedMapOf<String, Int>()
        body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val parsed = runCatching { ParsedServer(parse(line), line) }.getOrNull()
            if (parsed != null) {
                servers += parsed
            } else {
                val kind = line.substringBefore("://", "").lowercase().takeIf { it.isNotEmpty() && it.length <= 12 } ?: "other"
                skipped[kind] = (skipped[kind] ?: 0) + 1
            }
        }
        return ParseReport(servers, skipped)
    }

    // ---- VLESS -------------------------------------------------------------

    private fun parseVless(link: String): ServerProfile {
        val uri = SimpleUri.parse(link)
        val q = uri.query
        val uuid = uri.userInfo.ifEmpty { throw ParseException("VLESS link has no user id") }
        return ServerProfile(
            name = uri.fragment.ifEmpty { uri.host },
            protocol = Protocol.VLESS,
            address = uri.host,
            port = uri.port,
            secret = uuid,
            method = q["encryption"]?.takeIf { it.isNotEmpty() } ?: "none",
            flow = q["flow"]?.takeIf { it.isNotEmpty() },
            stream = streamFromQuery(q, defaultSecurity = "none"),
        )
    }

    // ---- Trojan ------------------------------------------------------------

    private fun parseTrojan(link: String): ServerProfile {
        val uri = SimpleUri.parse(link)
        val password = uri.userInfo.ifEmpty { throw ParseException("Trojan link has no password") }
        return ServerProfile(
            name = uri.fragment.ifEmpty { uri.host },
            protocol = Protocol.TROJAN,
            address = uri.host,
            port = uri.port,
            secret = password,
            flow = uri.query["flow"]?.takeIf { it.isNotEmpty() },
            stream = streamFromQuery(uri.query, defaultSecurity = "tls"),
        )
    }

    // ---- VMess -------------------------------------------------------------

    private fun parseVmess(link: String): ServerProfile {
        val body = link.substringAfter("://").substringBefore('#')
        val decoded = decodeBase64OrNull(body)
        if (decoded == null || !decoded.trim().startsWith("{")) {
            // Newer URI style: vmess://uuid@host:port?type=ws&security=tls#name
            val uri = SimpleUri.parse(link)
            return ServerProfile(
                name = uri.fragment.ifEmpty { uri.host },
                protocol = Protocol.VMESS,
                address = uri.host,
                port = uri.port,
                secret = uri.userInfo.ifEmpty { throw ParseException("VMess link has no user id") },
                method = uri.query["encryption"]?.takeIf { it.isNotEmpty() } ?: "auto",
                stream = streamFromQuery(uri.query, defaultSecurity = "none"),
            )
        }
        val j = try {
            JSONObject(decoded)
        } catch (e: Exception) {
            throw ParseException("Invalid VMess JSON")
        }
        val address = j.optString("add").trim().ifEmpty { throw ParseException("VMess link has no address") }
        val port = j.opt("port")?.toString()?.trim()?.toIntOrNull() ?: throw ParseException("VMess link has no port")
        val id = j.optString("id").trim().ifEmpty { throw ParseException("VMess link has no user id") }
        val net = j.optString("net").trim().lowercase().ifEmpty { "tcp" }
        val type = j.optString("type").trim().takeIf { it.isNotEmpty() && it != "none" }
        val host = j.optString("host").trim().takeIf { it.isNotEmpty() }
        val path = j.optString("path").trim().takeIf { it.isNotEmpty() }
        val tls = j.optString("tls").trim().lowercase()
        val network = normalizeNetwork(net)
        val stream = StreamConfig(
            network = network,
            security = if (tls == "tls" || tls == "reality") tls else "none",
            sni = j.optString("sni").trim().takeIf { it.isNotEmpty() },
            fingerprint = j.optString("fp").trim().takeIf { it.isNotEmpty() },
            alpn = splitList(j.optString("alpn")),
            host = host,
            path = if (network == "grpc" || network == "kcp") null else path,
            serviceName = if (network == "grpc") path else null,
            seed = if (network == "kcp") path else null,
            mode = if (network == "grpc" || network == "xhttp") type else null,
            headerType = if (network == "tcp" || network == "kcp") type else null,
            realityPublicKey = j.optString("pbk").trim().takeIf { it.isNotEmpty() },
            realityShortId = j.optString("sid").trim().takeIf { it.isNotEmpty() },
            realitySpiderX = j.optString("spx").trim().takeIf { it.isNotEmpty() },
        )
        return ServerProfile(
            name = j.optString("ps").trim().ifEmpty { address },
            protocol = Protocol.VMESS,
            address = address,
            port = port,
            secret = id,
            method = j.optString("scy").trim().ifEmpty { "auto" },
            stream = stream,
        )
    }

    // ---- Shadowsocks -------------------------------------------------------

    private fun parseShadowsocks(link: String): ServerProfile {
        val noScheme = link.substringAfter("://")
        val name = percentDecode(noScheme.substringAfter('#', ""))
        val main = noScheme.substringBefore('#').substringBefore('?').trimEnd('/')

        val method: String
        val password: String
        val hostPort: String
        if (main.contains('@')) {
            val userInfoRaw = main.substringBeforeLast('@')
            hostPort = main.substringAfterLast('@')
            val userInfo = percentDecode(userInfoRaw).let { plain ->
                if (plain.contains(':')) plain else decodeBase64OrNull(userInfoRaw) ?: plain
            }
            method = userInfo.substringBefore(':')
            password = userInfo.substringAfter(':', "")
        } else {
            // Legacy: ss://base64(method:password@host:port)
            val decoded = decodeBase64OrNull(main) ?: throw ParseException("Invalid Shadowsocks link")
            val userInfo = decoded.substringBeforeLast('@')
            hostPort = decoded.substringAfterLast('@')
            method = userInfo.substringBefore(':')
            password = userInfo.substringAfter(':', "")
        }
        if (method.isEmpty() || password.isEmpty()) throw ParseException("Shadowsocks link has no method or password")
        val (host, port) = SimpleUri.splitHostPort(hostPort)
        return ServerProfile(
            name = name.ifEmpty { host },
            protocol = Protocol.SHADOWSOCKS,
            address = host,
            port = port,
            secret = password,
            method = method.lowercase(),
        )
    }

    // ---- Hysteria2 ---------------------------------------------------------

    private fun parseHysteria2(link: String): ServerProfile {
        val uri = SimpleUri.parse(link)
        val q = uri.query
        return ServerProfile(
            name = uri.fragment.ifEmpty { uri.host },
            protocol = Protocol.HYSTERIA2,
            address = uri.host,
            port = uri.port,
            secret = uri.userInfo,
            stream = StreamConfig(
                network = "hysteria",
                security = "tls",
                sni = (q["sni"] ?: q["peer"])?.takeIf { it.isNotEmpty() },
                alpn = splitList(q["alpn"]).ifEmpty { listOf("h3") },
                pinnedPeerCertSha256 = q["pinSHA256"]?.takeIf { it.isNotEmpty() },
                obfsPassword = if (q["obfs"] == "salamander") q["obfs-password"]?.takeIf { it.isNotEmpty() } else null,
            ),
        )
    }

    // ---- Xray JSON ---------------------------------------------------------

    private fun parseCustomJson(text: String): ServerProfile {
        val j = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ParseException("Invalid JSON")
        }
        val outbounds = j.optJSONArray("outbounds")
        if (outbounds == null || outbounds.length() == 0) throw ParseException("Xray config has no outbounds")
        val (address, port) = firstServerOf(outbounds)
        return ServerProfile(
            name = j.optString("remarks").trim().ifEmpty { address.ifEmpty { "Xray config" } },
            protocol = Protocol.CUSTOM,
            address = address,
            port = port,
            rawConfig = j.toString(),
        )
    }

    private fun firstServerOf(outbounds: JSONArray): Pair<String, Int> {
        for (i in 0 until outbounds.length()) {
            val settings = outbounds.optJSONObject(i)?.optJSONObject("settings") ?: continue
            val target = settings.optJSONArray("vnext")?.optJSONObject(0)
                ?: settings.optJSONArray("servers")?.optJSONObject(0)
                ?: settings.takeIf { it.has("address") }
                ?: continue
            val address = target.optString("address")
            if (address.isNotEmpty()) return address to target.optInt("port")
        }
        return "" to 0
    }

    // ---- helpers -----------------------------------------------------------

    private fun streamFromQuery(q: Map<String, String>, defaultSecurity: String): StreamConfig {
        val network = normalizeNetwork(q["type"]?.lowercase()?.ifEmpty { null } ?: "tcp")
        val security = q["security"]?.lowercase()?.takeIf { it.isNotEmpty() } ?: defaultSecurity
        val headerType = q["headerType"]?.takeIf { it.isNotEmpty() && it != "none" }
        return StreamConfig(
            network = network,
            security = if (security == "xtls") "tls" else security,
            sni = (q["sni"] ?: q["peer"])?.takeIf { it.isNotEmpty() },
            fingerprint = q["fp"]?.takeIf { it.isNotEmpty() },
            alpn = splitList(q["alpn"]),
            pinnedPeerCertSha256 = q["pcs"]?.takeIf { it.isNotEmpty() },
            verifyPeerCertByName = q["vcn"]?.takeIf { it.isNotEmpty() },
            realityPublicKey = q["pbk"]?.takeIf { it.isNotEmpty() },
            realityShortId = q["sid"]?.takeIf { it.isNotEmpty() },
            realitySpiderX = q["spx"]?.takeIf { it.isNotEmpty() },
            realityMldsa65Verify = q["pqv"]?.takeIf { it.isNotEmpty() },
            host = q["host"]?.takeIf { it.isNotEmpty() },
            path = if (network == "kcp") null else q["path"]?.takeIf { it.isNotEmpty() },
            serviceName = q["serviceName"]?.takeIf { it.isNotEmpty() }
                ?: if (network == "grpc") q["path"]?.takeIf { it.isNotEmpty() } else null,
            authority = q["authority"]?.takeIf { it.isNotEmpty() },
            mode = q["mode"]?.takeIf { it.isNotEmpty() },
            headerType = headerType,
            seed = q["seed"]?.takeIf { it.isNotEmpty() } ?: if (network == "kcp") q["path"] else null,
            xhttpExtra = q["extra"]?.takeIf { it.isNotEmpty() },
        )
    }

    private fun normalizeNetwork(net: String): String = when (net) {
        "raw", "tcp" -> "tcp"
        "websocket", "ws" -> "ws"
        "splithttp", "xhttp" -> "xhttp"
        "mkcp", "kcp" -> "kcp"
        "grpc", "gun" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "hysteria" -> "hysteria"
        "h2", "http", "h3", "quic" -> throw ParseException("Transport \"$net\" was removed from Xray; use XHTTP")
        else -> throw ParseException("Unsupported transport: $net")
    }

    private fun splitList(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    internal fun decodeBase64OrNull(value: String): String? {
        val cleaned = value.trim().replace("\n", "").replace("\r", "")
        if (cleaned.isEmpty()) return null
        val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
        for (decoder in decoders) {
            val padded = cleaned.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }
            val bytes = runCatching { decoder.decode(padded) }.getOrNull() ?: continue
            val text = bytes.toString(Charsets.UTF_8)
            if (text.none { it == '�' }) return text
        }
        return null
    }

    internal fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.reset()
            }
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }
}

/** Minimal, lenient URI splitter for share links (java.net.URI rejects many real-world links). */
internal data class SimpleUri(
    val userInfo: String,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String,
) {
    companion object {
        fun parse(link: String): SimpleUri {
            val noScheme = link.trim().substringAfter("://")
            val fragment = LinkParser.percentDecode(noScheme.substringAfter('#', "")).trim()
            val beforeFragment = noScheme.substringBefore('#')
            val rawQuery = beforeFragment.substringAfter('?', "")
            val authority = beforeFragment.substringBefore('?').trimEnd('/')
            val userInfo = LinkParser.percentDecode(authority.substringBeforeLast('@', ""))
            val (host, port) = splitHostPort(authority.substringAfterLast('@'))
            val query = rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .associate { part ->
                    LinkParser.percentDecode(part.substringBefore('=')) to
                        LinkParser.percentDecode(part.substringAfter('=', ""))
                }
            return SimpleUri(userInfo, host, port, query, fragment)
        }

        fun splitHostPort(hostPort: String): Pair<String, Int> {
            val value = hostPort.trim().trimEnd('/')
            val host: String
            val portText: String
            if (value.startsWith("[")) {
                host = value.substringAfter('[').substringBefore(']')
                portText = value.substringAfter("]:", "")
            } else {
                host = value.substringBeforeLast(':')
                portText = value.substringAfterLast(':', "")
            }
            val port = portText.takeWhile { it.isDigit() }.toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                throw ParseException("Invalid server address: $hostPort")
            }
            return host to port
        }
    }
}
