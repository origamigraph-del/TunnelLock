package app.tunnellock.vpn

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import app.tunnellock.BuildConfig
import app.tunnellock.core.openvpn.OpenVpnConfig
import app.tunnellock.data.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
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
 * The login is given over the socket when OpenVPN asks for it: it is never put
 * into OpenVPN's config file or the log. The config file (it may hold inline
 * keys) is deleted as soon as OpenVPN has read it.
 *
 * Every start is a separate [Run]; events carry its id so a session can ignore
 * anything from an earlier run.
 */
class OpenVpnEngine(private val context: Context, private val host: Host) {

    interface Host {
        /** Builds the VPN interface OpenVPN asked for; null refuses. Called on the management thread. */
        fun openTun(tun: OpenVpnTun): ParcelFileDescriptor?

        fun protect(fd: Int): Boolean

        fun onEvent(runId: Int, event: Event)
    }

    sealed interface Event {
        /** OpenVPN reports CONNECTED: the tunnel carries traffic. */
        data object Connected : Event

        /** The tunnel went down; OpenVPN reconnects on its own. */
        data class Reconnecting(val reason: String) : Event

        /** OpenVPN ended and will not come back by itself. */
        data class Stopped(val reason: String, val loginRejected: Boolean = false) : Event

        /**
         * Still not connected after [STALL_MS]. [serverAnswered]: the server's first packet
         * arrived and then the handshake got stuck, the usual sign of OpenVPN being filtered;
         * otherwise nothing came back at all. Sent again if the server answers later.
         */
        data class Stalled(val serverAnswered: Boolean) : Event
    }

    private companion object {
        const val TAG = "OpenVPN"
        const val STALL_MS = 20_000L
        val NEED_OK = Regex("""^>NEED-OK:Need '([^']+)' confirmation(?: MSG:(.*))?$""")
        val PASSWORD_NEED = Regex("""^>PASSWORD:Need '([^']+)' (username/password|password)""")
        val TIMESTAMP = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d """)

        /** OpenVPN echoes management commands; it hides the password but not the username. */
        val USERNAME_ECHO = Regex("""(CMD 'username '[^']*' ).*""")
        val TUN_REQUESTS = setOf("IFCONFIG", "IFCONFIG6", "ROUTE", "ROUTE6", "DNSSERVER", "DNS6SERVER", "DNSDOMAIN")
    }

    /** One OpenVPN process and everything that belongs to it. */
    private class Run(val id: Int, val login: OpenVpnLogin, dir: File) {
        val socketFile = File(dir, "mgmt")
        val configFile = File(dir, "client.conf")
        var process: Process? = null
        var listener: LocalSocket? = null
        var server: LocalServerSocket? = null
        var managementThread: Thread? = null
        @Volatile var socket: LocalSocket? = null

        /** Stopped on purpose or exited: nothing from it reaches the session any more. */
        @Volatile var ended = false
        @Volatile var connected = false

        /** Since when it has been trying to connect (elapsedRealtime), and whether the server answered meanwhile. */
        @Volatile var waitingSince = SystemClock.elapsedRealtime()
        @Volatile var serverAnswered = false

        /** What the last [Event.Stalled] said, null if none since [waitingSince]. */
        var stallReported: Boolean? = null

        /** The server refused the login since the last successful connection. */
        @Volatile var loginRejected = false
        @Volatile var problem: String? = null

        // Management thread only.
        var pending = OpenVpnTun()
        var roundDone = false
        var opened: OpenVpnTun? = null

        private val writeLock = Any()

        // No flush(): LocalSocket's flush waits until OpenVPN has read the bytes,
        // and OpenVPN does not read while it is resolving a host name.
        fun send(command: String) {
            synchronized(writeLock) {
                socket?.outputStream?.write((command + "\n").toByteArray())
            }
        }

        fun sendWithFd(command: String, fd: FileDescriptor) {
            synchronized(writeLock) {
                val s = socket ?: return
                s.setFileDescriptorsForSend(arrayOf(fd))
                try {
                    s.outputStream.write((command + "\n").toByteArray())
                } finally {
                    s.setFileDescriptorsForSend(null)
                }
            }
        }
    }

    private val lock = Any()
    private var current: Run? = null
    private var nextId = 0

    /** Set when the service goes away: no new runs after that. */
    @Volatile private var shutDown = false

    val running: Boolean get() = synchronized(lock) { current != null }

    /** Starts OpenVPN with an .ovpn config and returns the run's id. Throws if it cannot start at all. */
    fun start(ovpn: String, login: OpenVpnLogin): Int {
        stop()
        if (shutDown) throw IOException("TunnelLock is stopping")
        val exe = File(context.applicationInfo.nativeLibraryDir, "libovpnexec.so")
        if (!exe.exists()) throw IOException("OpenVPN is not part of this build")
        val dir = File(context.noBackupFilesDir, "openvpn").apply { mkdirs() }
        val run = synchronized(lock) { Run(++nextId, login, dir) }
        run.socketFile.delete()
        try {
            run.configFile.writeText(
                OpenVpnConfig.runtimeConfig(
                    ovpn,
                    run.socketFile.absolutePath,
                    "TunnelLock ${BuildConfig.VERSION_NAME}",
                    context.cacheDir.absolutePath,
                ),
            )
            val listener = LocalSocket()
            run.listener = listener
            listener.bind(LocalSocketAddress(run.socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            run.server = LocalServerSocket(listener.fileDescriptor)
            run.process = ProcessBuilder(exe.absolutePath, "--config", run.configFile.absolutePath)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            run.ended = true
            cleanUp(run)
            throw e
        }
        val process = requireNotNull(run.process)
        synchronized(lock) { current = run }
        AppLog.i(TAG, "started (run ${run.id})")
        thread(name = "openvpn-output", isDaemon = true) { readOutput(run, process) }
        run.managementThread = thread(name = "openvpn-management", isDaemon = true) { manage(run) }
        thread(name = "openvpn-exit", isDaemon = true) { watchExit(run, process) }
        thread(name = "openvpn-stall", isDaemon = true) { watchStall(run) }
        if (shutDown) {
            stop()
            throw IOException("TunnelLock is stopping")
        }
        return run.id
    }

    /** Stops OpenVPN (SIGTERM, then by force). Safe to call when not running. */
    fun stop() {
        val run = take() ?: return
        run.process?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
        }
        cleanUp(run)
        AppLog.i(TAG, "stopped (run ${run.id})")
    }

    /** Ends OpenVPN without waiting, for good (the service's onDestroy, on the main thread). */
    fun kill() {
        shutDown = true
        val run = take() ?: return
        run.process?.destroy()
        cleanUp(run)
    }

    /** The phone switched networks: OpenVPN moves its connection or reconnects. */
    fun networkChanged() {
        val run = synchronized(lock) { current } ?: return
        runCatching { run.send("network-change") }
    }

    private fun take(): Run? {
        val run = synchronized(lock) { current.also { current = null } } ?: return null
        run.ended = true
        return run
    }

    private fun emit(run: Run, event: Event) {
        val live = synchronized(lock) { current === run }
        if (live && !run.ended) host.onEvent(run.id, event)
    }

    // ---- management -------------------------------------------------------------

    private fun manage(run: Run) {
        val client = try {
            run.server?.accept() ?: return
        } catch (e: IOException) {
            return
        }
        if (run.ended) {
            runCatching { client.close() }
            return
        }
        run.socket = client
        // OpenVPN has read its config (with any inline keys) before connecting here.
        run.configFile.delete()
        run.socketFile.delete()
        runCatching { run.send("state on") }

        val input = client.inputStream
        val buffer = ByteArray(4096)
        val line = ByteArrayOutputStream()
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                // Sockets to protect (PROTECTFD, and the reply to network-change) arrive
                // as descriptors with the bytes: protect and close each one right away.
                client.ancillaryFileDescriptors?.forEach { protectAndClose(it) }
                for (i in 0 until n) {
                    val b = buffer[i]
                    if (b == '\n'.code.toByte()) {
                        handle(run, line.toString(Charsets.UTF_8.name()).trimEnd('\r'))
                        line.reset()
                    } else {
                        line.write(b.toInt())
                    }
                }
            }
        } catch (e: IOException) {
            // OpenVPN exited or the socket was closed.
        }
    }

    private fun protectAndClose(fd: FileDescriptor?) {
        if (fd == null) return
        runCatching { ParcelFileDescriptor.dup(fd).use { host.protect(it.fd) } }
        runCatching { Os.close(fd) }
    }

    private fun handle(run: Run, line: String) {
        if (run.ended) return
        when {
            line.startsWith(">HOLD:") -> run.send("hold release")
            line.startsWith(">STATE:") -> onState(run, line.removePrefix(">STATE:"))
            line.startsWith(">NEED-OK:") -> NEED_OK.find(line)?.let { onNeedOk(run, it.groupValues[1], it.groupValues[2]) }
            line.startsWith(">PASSWORD:") -> onPassword(run, line)
            line.startsWith(">FATAL:") -> {
                run.problem = line.removePrefix(">FATAL:")
                AppLog.w(TAG, "fatal: ${run.problem}")
            }
            line.startsWith("ERROR:") -> AppLog.w(TAG, "management: $line")
        }
    }

    private fun onState(run: Run, value: String) {
        // time,STATE,detail,local ip,remote ip,...
        val fields = value.split(',')
        val state = fields.getOrNull(1).orEmpty()
        val detail = fields.getOrNull(2).orEmpty()
        AppLog.i(TAG, "state $state" + if (detail.isNotEmpty()) " ($detail)" else "")
        when (state) {
            "CONNECTED" -> {
                run.loginRejected = false
                if (!run.connected) {
                    run.connected = true
                    emit(run, Event.Connected)
                }
            }
            "RECONNECTING" -> if (run.connected) {
                run.connected = false
                run.waitingSince = SystemClock.elapsedRealtime()
                run.serverAnswered = false
                run.stallReported = null
                emit(run, Event.Reconnecting(detail))
            }
            "EXITING" -> if (detail.isNotEmpty()) run.problem = detail
        }
    }

    private fun onPassword(run: Run, line: String) {
        if (line.startsWith(">PASSWORD:Verification Failed")) {
            // Also sent for temporary failures and expired tokens, after which OpenVPN
            // reconnects by itself; a later CONNECTED clears it.
            run.loginRejected = true
            AppLog.w(TAG, "the server rejected the login")
            return
        }
        // Other PASSWORD lines (auth tokens) carry secrets: never log them.
        val need = PASSWORD_NEED.find(line) ?: return
        val type = need.groupValues[1]
        val login = run.login
        when (type) {
            "Auth" -> {
                val withUser = need.groupValues[2] == "username/password"
                if (login.password.isNullOrEmpty() || withUser && login.username.isNullOrEmpty()) {
                    run.loginRejected = true
                    run.problem = "the server needs a username and password"
                    run.send("signal SIGTERM")
                } else {
                    if (withUser) run.send("username 'Auth' ${quote(login.username.orEmpty())}")
                    run.send("password 'Auth' ${quote(login.password.orEmpty())}")
                }
            }
            "Private Key" -> {
                val key = login.keyPassword
                if (key.isNullOrEmpty()) {
                    run.problem = "the private key needs a password"
                    run.send("signal SIGTERM")
                } else {
                    run.send("password 'Private Key' ${quote(key)}")
                }
            }
            // The VPN login must never go to anything else (an HTTP proxy, for example).
            else -> {
                run.problem = "the config asks for a '$type' login, which TunnelLock does not support"
                run.send("signal SIGTERM")
            }
        }
    }

    private fun onNeedOk(run: Run, type: String, message: String) {
        if (run.roundDone && type in TUN_REQUESTS) {
            run.pending = OpenVpnTun()
            run.roundDone = false
        }
        when (type) {
            // The descriptor was protected when it arrived.
            "PROTECTFD" -> reply(run, type, "ok")
            "IFCONFIG" -> {
                // "local netmask-or-peer mtu topology"
                val parts = message.trim().split(' ')
                val prefix = if (parts.getOrNull(3) == "subnet") prefixOf(parts.getOrNull(1)) else 32
                run.pending = run.pending.copy(
                    ipv4 = parts.getOrNull(0),
                    ipv4Prefix = prefix,
                    mtu = parts.getOrNull(2)?.toIntOrNull() ?: run.pending.mtu,
                )
                reply(run, type, "ok")
            }
            "IFCONFIG6" -> {
                // "address/bits mtu"
                val address = message.trim().substringBefore(' ')
                run.pending = run.pending.copy(
                    ipv6 = address.substringBefore('/'),
                    ipv6Prefix = address.substringAfter('/', "128").toIntOrNull() ?: 128,
                )
                reply(run, type, "ok")
            }
            "DNSSERVER", "DNS6SERVER" -> {
                val server = message.trim()
                if (server.isNotEmpty() && server !in run.pending.dns) run.pending = run.pending.copy(dns = run.pending.dns + server)
                reply(run, type, "ok")
            }
            "DNSDOMAIN" -> {
                run.pending = run.pending.copy(domain = message.trim().ifEmpty { null })
                reply(run, type, "ok")
            }
            "PERSIST_TUN_ACTION" -> {
                reply(run, type, if (run.opened != null && run.opened == run.pending) "NOACTION" else "OPEN_BEFORE_CLOSE")
                run.roundDone = true
            }
            "OPENTUN" -> {
                val tun = run.pending
                val pfd = runCatching { host.openTun(tun) }
                    .onFailure { AppLog.w(TAG, "opening the tunnel interface failed", it) }
                    .getOrNull()
                if (pfd == null) {
                    reply(run, type, "cancel")
                } else {
                    run.sendWithFd("needok 'OPENTUN' ok", pfd.fileDescriptor)
                    run.opened = tun
                    AppLog.i(TAG, "tunnel interface ready (mtu ${tun.mtu}, ${tun.dns.size} dns)")
                }
                run.roundDone = true
            }
            // Routes are not needed: TunnelLock sends everything into the tunnel.
            else -> reply(run, type, "ok")
        }
    }

    private fun reply(run: Run, type: String, answer: String) = run.send("needok '$type' $answer")

    // ---- process ----------------------------------------------------------------

    private fun readOutput(run: Run, process: Process) {
        runCatching {
            process.inputStream.bufferedReader().forEachLine {
                // The server's first packet (TCP or UDP): it is there and speaks OpenVPN.
                if ("TLS: Initial packet from" in it) run.serverAnswered = true
                AppLog.i(TAG, it.replace(TIMESTAMP, "").replace(USERNAME_ECHO, "$1[...]'"))
            }
        }
    }

    /** Tells the session when connecting takes long, and why it probably does. */
    private fun watchStall(run: Run) {
        while (!run.ended) {
            runCatching { Thread.sleep(2_000) }
            if (run.ended || run.connected) continue
            if (SystemClock.elapsedRealtime() - run.waitingSince < STALL_MS) continue
            val answered = run.serverAnswered
            if (run.stallReported == answered) continue
            run.stallReported = answered
            AppLog.w(
                TAG,
                if (answered) {
                    "not connected after ${STALL_MS / 1000} s: the server answered, then the handshake got stuck (typical of OpenVPN being filtered)"
                } else {
                    "not connected after ${STALL_MS / 1000} s: no answer from the server (address, port or key wrong, server down, or blocked)"
                },
            )
            emit(run, Event.Stalled(answered))
        }
    }

    private fun watchExit(run: Run, process: Process) {
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        // Let the management thread read OpenVPN's last lines (why it ended).
        run.managementThread?.join(2_000)
        val wasCurrent = synchronized(lock) { (current === run).also { if (it) current = null } }
        // Stopped on purpose: stop() cleans up and nobody needs to hear about it.
        if (!wasCurrent) return
        run.ended = true
        cleanUp(run)
        val reason = run.problem ?: "OpenVPN exited (code $code)"
        AppLog.w(TAG, "ended: $reason")
        host.onEvent(run.id, Event.Stopped(reason, loginRejected = run.loginRejected))
    }

    private fun cleanUp(run: Run) {
        runCatching { run.socket?.close() }
        run.socket = null
        // accept() does not return when its socket is closed: wake it with a
        // connection first. (Once OpenVPN has connected, the file is gone.)
        if (run.server != null && run.socketFile.exists()) {
            runCatching {
                LocalSocket().use { it.connect(LocalSocketAddress(run.socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
            }
        }
        runCatching { run.server?.close() }
        runCatching { run.listener?.close() }
        run.socketFile.delete()
        run.configFile.delete()
    }

    private fun prefixOf(netmask: String?): Int =
        netmask?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }
            ?.sumOf { Integer.bitCount(it) } ?: 32

    /** Quotes a value for a management command. */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""
}
