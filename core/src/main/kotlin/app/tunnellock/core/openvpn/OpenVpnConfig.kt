package app.tunnellock.core.openvpn

import app.tunnellock.core.model.Protocol
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.core.parser.ParseException

/** One server address of an .ovpn file. */
data class OvpnRemote(val host: String, val port: Int, val proto: String)

/** What an .ovpn file needs from the user before it can connect. */
data class OvpnNeeds(val credentials: Boolean, val keyPassword: Boolean)

/**
 * Reads OpenVPN client configs (.ovpn) and turns them into the config the
 * OpenVPN process runs. Certificates and keys must be inline (`<ca>…</ca>`):
 * the app cannot read files the config points to.
 */
object OpenVpnConfig {

    /** Options that hand control to scripts, files, the system or another manager. Dropped from every config. */
    private val REMOVED = setOf(
        // Scripts and plugins.
        "up", "down", "down-pre", "up-restart", "route-up", "route-pre-down", "ipchange", "tls-verify",
        "auth-user-pass-verify", "client-connect", "client-disconnect", "learn-address", "script-security", "plugin",
        // Process, files and logging: TunnelLock runs and watches the process itself.
        "daemon", "log", "log-append", "status", "status-version", "writepid", "syslog", "cd", "chroot",
        "user", "group", "mlock", "tmp-dir", "dev-node", "iproute", "lladdr", "engine", "askpass",
        // Higher levels can put connection details into the app's log.
        "verb",
        // TunnelLock's own management connection replaces any other.
        "management", "management-client", "management-query-passwords", "management-hold", "management-signal",
        "management-forget-disconnect", "management-external-key", "management-external-cert", "management-up-down",
        "management-client-auth", "management-log-cache", "management-query-proxy", "management-query-remote",
        "management-client-user", "management-client-group",
        // Windows only.
        "block-outside-dns", "register-dns", "ip-win32", "route-method", "win-sys", "tap-sleep", "dhcp-renew",
        "dhcp-release", "show-net-up", "service", "windows-driver",
    )

    /** Options whose argument names a file, unless the same `<tag>` block is inline. */
    private val FILE_OPTIONS = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "pkcs12", "extra-certs", "secret",
        "crl-verify", "http-proxy-user-pass",
    )

    private val BLOCK_START = Regex("""^<([A-Za-z0-9-]+)>$""")

    private sealed interface Item {
        /** A directive (or a comment / blank line, with [name] null). */
        data class Line(val text: String, val name: String?, val args: List<String>) : Item

        /** An inline `<tag>…</tag>` block, kept verbatim. */
        data class Block(val tag: String, val text: String) : Item
    }

    /**
     * True if [text] is an OpenVPN client config rather than links or JSON. Only
     * looks at the option names, so a broken config still reaches [parse] and
     * gets a precise error.
     */
    fun looksLikeOvpn(text: String): Boolean {
        val input = text.trimStart().removePrefix("﻿")
        if (input.startsWith("{") || input.startsWith("[")) return false
        val words = input.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            .map { line -> line.split(' ', '\t').first().removePrefix("--").lowercase() }
            .toSet()
        val hasRemote = "remote" in words || "<connection>" in words
        return hasRemote && (words.any { it in setOf("client", "tls-client", "pull", "dev", "proto") } || "<ca>" in words)
    }

    /** Parses an .ovpn file. Throws [ParseException] with the reason when TunnelLock cannot run it. */
    fun parse(text: String, name: String? = null): ServerProfile {
        val items = items(text)
        validate(items)
        val remote = remotes(items).firstOrNull() ?: throw ParseException("OpenVPN config has no remote server")
        return ServerProfile(
            name = name?.takeIf { it.isNotBlank() } ?: remote.host,
            protocol = Protocol.OPENVPN,
            address = remote.host,
            port = remote.port,
            method = remote.proto,
            rawConfig = text.trim().removePrefix("﻿"),
        )
    }

    fun remotes(text: String): List<OvpnRemote> = remotes(items(text))

    fun needs(text: String): OvpnNeeds {
        val items = items(text)
        val names = items.filterIsInstance<Item.Line>().mapNotNull { it.name }.toSet()
        val blocks = items.filterIsInstance<Item.Block>()
        val credentials = "auth-user-pass" in names && blocks.none { it.tag == "auth-user-pass" }
        val keyPassword = blocks.any { it.tag == "key" && it.text.contains("ENCRYPTED") }
        return OvpnNeeds(credentials, keyPassword)
    }

    /**
     * The config the OpenVPN process runs: the user's config without the options
     * TunnelLock must control, plus the management connection through which the
     * app provides the TUN interface, the login and the key password.
     */
    fun runtimeConfig(text: String, managementSocket: String, guiVersion: String): String {
        val items = items(text)
        validate(items)
        val names = items.filterIsInstance<Item.Line>().mapNotNull { it.name }.toSet()
        val inlineLogin = items.any { it is Item.Block && it.tag == "auth-user-pass" }
        val out = StringBuilder()
        for (item in items) {
            when (item) {
                is Item.Block -> out.append(item.text).append('\n')
                is Item.Line -> when {
                    item.name == null || item.name in REMOVED -> Unit
                    // The login comes inline or from the app (asked over management), never from a file.
                    item.name == "auth-user-pass" -> if (!inlineLogin) out.append("auth-user-pass\n")
                    // A file name next to the same inline block: the block is what we have.
                    item.name in FILE_OPTIONS && item.args.firstOrNull().let { it != null && it != "[inline]" } -> {
                        if (item.name == "tls-auth") item.args.getOrNull(1)?.let { out.append("key-direction ").append(it).append('\n') }
                    }
                    else -> out.append(item.text).append('\n')
                }
            }
        }
        out.append("\n# Added by TunnelLock\n")
        if ("dev" !in names) out.append("dev tun\n")
        out.append("management ").append(managementSocket).append(" unix\n")
        out.append("management-client\n")
        out.append("management-query-passwords\n")
        out.append("management-hold\n")
        out.append("setenv IV_GUI_VER \"").append(guiVersion.replace("\"", "")).append("\"\n")
        out.append("ifconfig-nowarn\n")
        // Reconnects keep the TUN interface instead of rebuilding it.
        if ("persist-tun" !in names) out.append("persist-tun\n")
        out.append("verb 3\n")
        // The default waits up to 5 minutes between attempts.
        if ("connect-retry" !in names) out.append("connect-retry 2 30\n")
        return out.toString()
    }

    // ---- parsing ----------------------------------------------------------------

    private fun items(text: String): List<Item> {
        val lines = text.removePrefix("﻿").lines()
        val items = mutableListOf<Item>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val start = BLOCK_START.matchEntire(line)
            if (start != null) {
                val tag = start.groupValues[1].lowercase()
                val end = (i + 1 until lines.size).firstOrNull { lines[it].trim().equals("</$tag>", ignoreCase = true) }
                    ?: throw ParseException("OpenVPN config: <$tag> is never closed")
                items += Item.Block(tag, (i..end).joinToString("\n") { lines[it].trimEnd() })
                i = end + 1
                continue
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                items += Item.Line(line, null, emptyList())
            } else {
                val tokens = tokenize(line)
                items += Item.Line(line, tokens.first().removePrefix("--").lowercase(), tokens.drop(1))
            }
            i++
        }
        return items
    }

    /** Splits an option line like OpenVPN does: whitespace, with "double" or 'single' quotes. */
    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == quote -> {
                    quote = null
                }
                quote == '"' && c == '\\' && i + 1 < line.length -> current.append(line[++i])
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    inToken = true
                }
                c.isWhitespace() -> if (inToken) {
                    tokens += current.toString()
                    current.clear()
                    inToken = false
                }
                // A comment after the option.
                (c == '#' || c == ';') && !inToken -> break
                else -> {
                    current.append(c)
                    inToken = true
                }
            }
            i++
        }
        if (inToken) tokens += current.toString()
        return tokens
    }

    private fun validate(items: List<Item>) {
        val lines = items.filterIsInstance<Item.Line>().filter { it.name != null }
        val tags = items.filterIsInstance<Item.Block>().map { it.tag }.toSet()
        for (line in lines) {
            val name = line.name ?: continue
            val arg = line.args.firstOrNull()
            when {
                name == "server" || name == "mode" && arg == "server" ->
                    throw ParseException("This is an OpenVPN server config, not a client config")
                name == "dev" && arg?.startsWith("tap") == true || name == "dev-type" && arg == "tap" ->
                    throw ParseException("TAP mode (dev tap) is not supported on Android; the config must use dev tun")
                name.startsWith("pkcs11") || name == "cryptoapicert" ->
                    throw ParseException("Smart-card keys ($name) are not supported")
                name in FILE_OPTIONS && arg != null && arg != "[inline]" && name !in tags ->
                    throw ParseException(
                        "The config points to the file \"$arg\" ($name). Export the config with the certificates inside it (inline).",
                    )
            }
        }
    }

    private fun remotes(items: List<Item>): List<OvpnRemote> {
        val lines = items.filterIsInstance<Item.Line>().toMutableList()
        // <connection> blocks hold their own remote lines.
        items.filterIsInstance<Item.Block>().filter { it.tag == "connection" }.forEach { block ->
            block.text.lines().drop(1).dropLast(1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val tokens = tokenize(line)
                    if (tokens.isNotEmpty()) lines += Item.Line(line, tokens.first().lowercase(), tokens.drop(1))
                }
        }
        val defaultPort = lines.lastOrNull { it.name == "port" || it.name == "rport" }?.args?.firstOrNull()?.toIntOrNull() ?: 1194
        val defaultProto = lines.lastOrNull { it.name == "proto" }?.args?.firstOrNull() ?: "udp"
        return lines.filter { it.name == "remote" && it.args.isNotEmpty() }.map { line ->
            OvpnRemote(
                host = line.args[0],
                port = line.args.getOrNull(1)?.toIntOrNull() ?: defaultPort,
                proto = simpleProto(line.args.getOrNull(2) ?: defaultProto),
            )
        }
    }

    /** "tcp-client", "tcp4" and "udp6" read as tcp / udp. */
    private fun simpleProto(proto: String): String = if (proto.lowercase().startsWith("tcp")) "tcp" else "udp"
}
