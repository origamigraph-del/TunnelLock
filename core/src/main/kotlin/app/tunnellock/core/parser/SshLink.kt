package app.tunnellock.core.parser

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.model.StreamConfig

/**
 * SSH accounts as links:
 *
 *     ssh://user[:password]@host[:port]?transport=tcp|tls|ws|wss&sni=...&host=...&path=...&hostkey=SHA256:...#name
 *
 * - transport: how the SSH connection gets to the server. tcp is plain SSH; tls wraps it in
 *   TLS with the given SNI (stunnel-style servers); ws and wss send an HTTP upgrade (Host
 *   header and path) first, plain or in TLS, and then speak SSH (the usual "SSH over
 *   WebSocket" servers, also behind a CDN).
 * - hostkey: the server's key fingerprint as OpenSSH prints it; without it the key seen on
 *   the first connection is remembered and a different one is refused later.
 *
 * Links TunnelLock writes itself never hold the password: that is kept with the server.
 */
object SshLink {
    val TRANSPORTS = listOf("tcp", "tls", "ws", "wss")
    const val DEFAULT_PORT = 22

    fun parse(link: String): ServerProfile {
        val input = link.trim()
        if (!input.startsWith("ssh://", ignoreCase = true)) throw ParseException("Not an SSH link")
        val uri = SimpleUri.parse(withPort(input))
        val q = uri.query
        val transport = q["transport"]?.lowercase()?.takeIf { it.isNotEmpty() } ?: "tcp"
        if (transport !in TRANSPORTS) throw ParseException("Unknown SSH transport: $transport")
        val user = uri.userInfo.substringBefore(':')
        val password = uri.userInfo.substringAfter(':', "")
        return ServerProfile(
            name = uri.fragment.ifEmpty { uri.host },
            protocol = Protocol.SSH,
            address = uri.host,
            port = uri.port,
            secret = password,
            username = user.ifEmpty { null },
            hostKey = q["hostkey"]?.takeIf { it.isNotEmpty() },
            stream = StreamConfig(
                network = transport,
                security = if (transport == "tls" || transport == "wss") "tls" else "none",
                sni = q["sni"]?.takeIf { it.isNotEmpty() },
                host = q["host"]?.takeIf { it.isNotEmpty() },
                path = q["path"]?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /** A link for a server entered in the form (without the password). */
    fun build(
        name: String,
        host: String,
        port: Int,
        username: String,
        transport: String = "tcp",
        sni: String? = null,
        wsHost: String? = null,
        path: String? = null,
        hostKey: String? = null,
    ): String {
        require(transport in TRANSPORTS) { "unknown transport $transport" }
        val address = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val query = listOfNotNull(
            "transport=$transport",
            sni?.takeIf { it.isNotBlank() }?.let { "sni=" + encode(it.trim()) },
            wsHost?.takeIf { it.isNotBlank() }?.let { "host=" + encode(it.trim()) },
            path?.takeIf { it.isNotBlank() }?.let { "path=" + encode(it.trim()) },
            hostKey?.takeIf { it.isNotBlank() }?.let { "hostkey=" + encode(it.trim()) },
        ).joinToString("&")
        return "ssh://${encode(username.trim())}@$address:$port?$query#${encode(name.trim())}"
    }

    /** SimpleUri wants a port; SSH has a default one. */
    private fun withPort(link: String): String {
        val rest = link.substring("ssh://".length)
        val authorityEnd = rest.indexOfFirst { it == '?' || it == '#' || it == '/' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val hostPart = authority.substringAfterLast('@')
        val hasPort = if (hostPart.startsWith("[")) hostPart.contains("]:") else hostPart.contains(':')
        if (hasPort) return link
        val atIndex = authority.lastIndexOf('@')
        val withDefault = (if (atIndex >= 0) authority.substring(0, atIndex + 1) else "") + hostPart + ":" + DEFAULT_PORT
        return "ssh://" + withDefault + rest.substring(authorityEnd)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
