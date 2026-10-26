package app.tunnellock.vpn

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import app.tunnellock.BuildConfig
import app.tunnellock.core.openvpn.OpenVpnConfig
import app.tunnellock.data.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The TUN interface OpenVPN asked for: what it sent in its NEED-OK requests before OPENTUN. */
data class OpenVpnTun(
    val ipv4: String? = null,
    val ipv4Prefix: Int = 32,
    val ipv6: String? = null,
    val ipv6Prefix: Int = 128,
    val mtu: Int = 1500,
    val dns: List<String> = emptyList(),
    val domain: String? = null,
)

/** What the user entered for an OpenVPN server. */
data class OpenVpnLogin(val username: String?, val password: String?, val keyPassword: String?)

/**
 * Runs OpenVPN 2 (libovpnexec.so, built for TARGET_ANDROID) and drives it over
 * its management interface: a UNIX socket in our private directory that
 * OpenVPN connects to. OpenVPN on Android never opens a TUN device or changes
 * routes itself; it asks with NEED-OK requests and the [Host] answers with the
 * VpnService interface (doc/android.txt and doc/management-notes.txt in the
 * OpenVPN sources).
 *
 * Passwords are given over the socket when OpenVPN asks; they are never written
 * to disk or to the log.
 */
class OpenVpnEngine(private val context: Context, private val host: Host) {

    interface Host {
        /** Builds the VPN interface OpenVPN asked for; null refuses. Called on the management thread. */
        fun openTun(tun: OpenVpnTun): ParcelFileDescriptor?

        fun protect(fd: Int): Boolean

        fun onEvent(event: Event)
    }

    sealed interface Event {
        /** OpenVPN reports CONNECTED: the tunnel carries traffic. */
        data object Connected : Event

        /** The tunnel went down; OpenVPN reconnects on its own. */
        data class Reconnecting(val reason: String) : Event

        /** OpenVPN ended and will not come back by itself. */
        data class Stopped(val reason: String, val loginRejected: Boolean = false) : Event
    }

    private companion object {
        const val TAG = "OpenVPN"
        val NEED_OK = Regex("""^>NEED-OK:Need '([^']+)' confirmation(?: MSG:(.*))?$""")
        val PASSWORD_NEED = Regex("""^>PASSWORD:Need '([^']+)' (username/password|password)""")
        val TIMESTAMP = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d """)
    }

    private val lock = Any()

    /** Every start gets a new number; callbacks from an older run are ignored. */
    @Volatile private var generation = 0
    private var process: Process? = null
    private var listener: LocalSocket? = null
    private var server: LocalServerSocket? = null
    @Volatile private var socket: LocalSocket? = null
    private var socketPath: String? = null
    private var configFile: File? = null

    val running: Boolean get() = synchronized(lock) { process != null }

    /** Starts OpenVPN with an .ovpn config. Throws if it cannot be started at all. */
    fun start(ovpn: String, login: OpenVpnLogin) {
        stop()
        val exe = File(context.applicationInfo.nativeLibraryDir, "libovpnexec.so")
        if (!exe.exists()) throw IOException("OpenVPN is not part of this build")
        val dir = File(context.noBackupFilesDir, "openvpn").apply { mkdirs() }
        val socketFile = File(dir, "mgmt").apply { delete() }
        val config = File(dir, "client.conf")
        config.writeText(OpenVpnConfig.runtimeConfig(ovpn, socketFile.absolutePath, "TunnelLock ${BuildConfig.VERSION_NAME}"))

        synchronized(lock) {
            val gen = ++generation
            val local = LocalSocket()
            local.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val listening = LocalServerSocket(local.fileDescriptor)
            val proc = try {
                ProcessBuilder(exe.absolutePath, "--config", config.absolutePath)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                runCatching { listening.close() }
                runCatching { local.close() }
                throw e
            }
            listener = local
            server = listening
            process = proc
            socketPath = socketFile.absolutePath
            configFile = config
            AppLog.i(TAG, "started")
            thread(name = "openvpn-output", isDaemon = true) { readOutput(proc) }
            thread(name = "openvpn-management", isDaemon = true) { manage(listening, login, gen) }
            thread(name = "openvpn-exit", isDaemon = true) { watchExit(proc, gen) }
        }
    }

    /** Stops OpenVPN (politely, then by force). Safe to call when not running. */
    fun stop() {
        val proc: Process
        synchronized(lock) {
            proc = process ?: return
            generation++
            process = null
        }
        runCatching { send("signal SIGTERM") }
        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
            proc.destroy()
            if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        cleanUp()
        AppLog.i(TAG, "stopped")
    }

    /** Ends OpenVPN without waiting (for the service's onDestroy, on the main thread). */
    fun kill() {
        val proc: Process
        synchronized(lock) {
            proc = process ?: return
            generation++
            process = null
        }
        proc.destroy()
        cleanUp()
    }

    /** The phone switched networks: OpenVPN moves its connection or reconnects. */
    fun networkChanged() {
        runCatching { send("network-change") }
    }

    // ---- management -------------------------------------------------------------

    private class Session(val login: OpenVpnLogin, val gen: Int) {
        val fds = ArrayDeque<FileDescriptor>()
        var pending = OpenVpnTun()

        /** Set after PERSIST_TUN_ACTION: the next ifconfig/route/dns request starts a new round. */
        var roundDone = false
        var opened: OpenVpnTun? = null
        var connected = false
        var loginRejected = false
        var lastProblem: String? = null
    }

    @Volatile private var session: Session? = null

    private fun manage(listening: LocalServerSocket, login: OpenVpnLogin, gen: Int) {
        val client = try {
            listening.accept()
        } catch (e: IOException) {
            return
        }
        if (gen != generation) {
            runCatching { client.close() }
            return
        }
        val s = Session(login, gen)
        socket = client
        session = s
        // The socket file is not needed once OpenVPN is connected.
        socketPath?.let { File(it).delete() }
        runCatching { send("state on") }

        val input = client.inputStream
        val buffer = ByteArray(4096)
        val line = ByteArrayOutputStream()
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                // Descriptors (for PROTECTFD) arrive with the bytes of the request.
                client.ancillaryFileDescriptors?.forEach { s.fds.add(it) }
                for (i in 0 until n) {
                    val b = buffer[i]
                    if (b == '\n'.code.toByte()) {
                        handle(s, line.toString(Charsets.UTF_8.name()).trimEnd('\r'))
                        line.reset()
                    } else {
                        line.write(b.toInt())
                    }
                }
            }
        } catch (e: IOException) {
            // OpenVPN exited or we closed the socket.
        } finally {
            while (s.fds.isNotEmpty()) runCatching { Os.close(s.fds.poll()) }
        }
    }

    private fun handle(s: Session, line: String) {
        if (s.gen != generation) return
        when {
            line.startsWith(">HOLD:") -> send("hold release")
            line.startsWith(">STATE:") -> onState(s, line.removePrefix(">STATE:"))
            line.startsWith(">NEED-OK:") -> NEED_OK.find(line)?.let { onNeedOk(s, it.groupValues[1], it.groupValues[2]) }
            line.startsWith(">PASSWORD:") -> onPassword(s, line)
            line.startsWith(">FATAL:") -> {
                s.lastProblem = line.removePrefix(">FATAL:")
                AppLog.w(TAG, "fatal: ${s.lastProblem}")
            }
            line.startsWith("ERROR:") -> AppLog.w(TAG, "management: $line")
        }
    }

    private fun onState(s: Session, value: String) {
        // time,STATE,detail,local ip,remote ip,...
        val fields = value.split(',')
        val state = fields.getOrNull(1).orEmpty()
        val detail = fields.getOrNull(2).orEmpty()
        AppLog.i(TAG, "state $state" + if (detail.isNotEmpty()) " ($detail)" else "")
        when (state) {
            "CONNECTED" -> if (!s.connected) {
                s.connected = true
                host.onEvent(Event.Connected)
            }
            "RECONNECTING" -> {
                if (detail.contains("auth-failure")) s.loginRejected = true
                if (s.connected) {
                    s.connected = false
                    host.onEvent(Event.Reconnecting(detail))
                }
            }
            "EXITING" -> if (detail.isNotEmpty()) s.lastProblem = detail
        }
    }

    private fun onPassword(s: Session, line: String) {
        if (line.startsWith(">PASSWORD:Verification Failed")) {
            s.loginRejected = true
            AppLog.w(TAG, "the server rejected the login")
            return
        }
        // Other PASSWORD lines (auth tokens) carry secrets: never log them.
        val need = PASSWORD_NEED.find(line) ?: return
        val type = need.groupValues[1]
        val withUser = need.groupValues[2] == "username/password"
        val login = s.login
        if (type == "Private Key") {
            val key = login.keyPassword
            if (key.isNullOrEmpty()) {
                s.lastProblem = "the private key needs a password"
                send("signal SIGTERM")
            } else {
                send("password 'Private Key' ${quote(key)}")
            }
            return
        }
        if (login.password.isNullOrEmpty() || withUser && login.username.isNullOrEmpty()) {
            s.loginRejected = true
            s.lastProblem = "the server needs a username and password"
            send("signal SIGTERM")
            return
        }
        if (withUser) send("username '$type' ${quote(login.username.orEmpty())}")
        send("password '$type' ${quote(login.password.orEmpty())}")
    }

    private fun onNeedOk(s: Session, type: String, message: String) {
        if (s.roundDone && type in setOf("IFCONFIG", "IFCONFIG6", "ROUTE", "ROUTE6", "DNSSERVER", "DNS6SERVER", "DNSDOMAIN")) {
            s.pending = OpenVpnTun()
            s.roundDone = false
        }
        when (type) {
            "PROTECTFD" -> {
                val fd = s.fds.poll()
                if (fd == null) {
                    AppLog.w(TAG, "PROTECTFD without a descriptor")
                } else {
                    runCatching { ParcelFileDescriptor.dup(fd).use { host.protect(it.fd) } }
                    runCatching { Os.close(fd) }
                }
                reply(type, "ok")
            }
            "IFCONFIG" -> {
                // "local netmask-or-peer mtu topology"
                val parts = message.trim().split(' ')
                val prefix = if (parts.getOrNull(3) == "subnet") prefixOf(parts.getOrNull(1)) else 32
                s.pending = s.pending.copy(
                    ipv4 = parts.getOrNull(0),
                    ipv4Prefix = prefix,
                    mtu = parts.getOrNull(2)?.toIntOrNull() ?: s.pending.mtu,
                )
                reply(type, "ok")
            }
            "IFCONFIG6" -> {
                // "address/bits mtu"
                val address = message.trim().substringBefore(' ')
                s.pending = s.pending.copy(
                    ipv6 = address.substringBefore('/'),
                    ipv6Prefix = address.substringAfter('/', "128").toIntOrNull() ?: 128,
                )
                reply(type, "ok")
            }
            "DNSSERVER", "DNS6SERVER" -> {
                val server = message.trim()
                if (server.isNotEmpty() && server !in s.pending.dns) s.pending = s.pending.copy(dns = s.pending.dns + server)
                reply(type, "ok")
            }
            "DNSDOMAIN" -> {
                s.pending = s.pending.copy(domain = message.trim().ifEmpty { null })
                reply(type, "ok")
            }
            "PERSIST_TUN_ACTION" -> {
                reply(type, if (s.opened != null && s.opened == s.pending) "NOACTION" else "OPEN_BEFORE_CLOSE")
                s.roundDone = true
            }
            "OPENTUN" -> {
                val tun = s.pending
                val pfd = runCatching { host.openTun(tun) }
                    .onFailure { AppLog.w(TAG, "opening the tunnel interface failed", it) }
                    .getOrNull()
                if (pfd == null) {
                    reply(type, "cancel")
                } else {
                    sendWithFd("needok 'OPENTUN' ok", pfd.fileDescriptor)
                    s.opened = tun
                    AppLog.i(TAG, "tunnel interface ready (mtu ${tun.mtu}, ${tun.dns.size} dns)")
                }
                s.roundDone = true
            }
            // Routes are not needed: TunnelLock sends everything into the tunnel.
            else -> reply(type, "ok")
        }
    }

    private fun reply(type: String, answer: String) = send("needok '$type' $answer")

    private fun send(command: String) {
        synchronized(lock) {
            val out = socket?.outputStream ?: return
            out.write((command + "\n").toByteArray())
            out.flush()
        }
    }

    private fun sendWithFd(command: String, fd: FileDescriptor) {
        synchronized(lock) {
            val s = socket ?: return
            s.setFileDescriptorsForSend(arrayOf(fd))
            try {
                s.outputStream.write((command + "\n").toByteArray())
                s.outputStream.flush()
            } finally {
                s.setFileDescriptorsForSend(null)
            }
        }
    }

    // ---- process ----------------------------------------------------------------

    private fun readOutput(proc: Process) {
        runCatching {
            proc.inputStream.bufferedReader().forEachLine { AppLog.i(TAG, it.replace(TIMESTAMP, "")) }
        }
    }

    private fun watchExit(proc: Process, gen: Int) {
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        val s = session?.takeIf { it.gen == gen }
        synchronized(lock) {
            // Stopped on purpose: stop() cleans up and nobody needs to hear about it.
            if (gen != generation) return
            process = null
            generation++
        }
        cleanUp()
        val reason = s?.lastProblem ?: "OpenVPN exited (code $code)"
        AppLog.w(TAG, "ended: $reason")
        host.onEvent(Event.Stopped(reason, loginRejected = s?.loginRejected == true))
    }

    private fun cleanUp() {
        val path: String?
        synchronized(lock) {
            runCatching { socket?.close() }
            socket = null
            path = socketPath
            // accept() does not return when its socket is closed: wake it with a connection first.
            // (Once OpenVPN has connected, the socket file is gone and this does nothing.)
            if (path != null && server != null) {
                runCatching { LocalSocket().use { it.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)) } }
            }
            runCatching { server?.close() }
            runCatching { listener?.close() }
            server = null
            listener = null
            socketPath = null
            configFile?.delete()
            configFile = null
        }
        path?.let { File(it).delete() }
    }

    private fun prefixOf(netmask: String?): Int =
        netmask?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }
            ?.sumOf { Integer.bitCount(it) } ?: 32

    /** Quotes a value for a management command. */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""
}
