package app.tunnellock.vpn

import android.os.SystemClock
import app.tunnellock.core.model.ServerProfile
import app.tunnellock.data.AppLog
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** What the user entered for an SSH server. */
data class SshLogin(val username: String, val password: String?, val privateKey: String?, val keyPassphrase: String?)

/**
 * An SSH account as the tunnel. TunnelLock holds the SSH connection (plain, inside TLS,
 * or after an HTTP upgrade for WebSocket servers) and serves a SOCKS5 proxy on
 * 127.0.0.1 that Xray sends the apps' traffic to; every connection becomes an SSH
 * "direct-tcpip" channel, opened by the server. SSH carries TCP only: UDP is refused,
 * so apps fall back to TCP and DNS goes through Xray's resolver over TCP.
 *
 * The proxy wants a random login, so no other app on the phone can use it. The
 * server's host key is remembered on the first connection (or pinned by the link) and
 * a different one is refused: TLS and WebSocket only disguise the connection, SSH's
 * own key check is what proves the server.
 */
class SshTunnel(private val hostKeys: HostKeys, private val onProblem: (IOException) -> Unit) {

    interface HostKeys {
        fun get(server: String): String?
        fun put(server: String, fingerprint: String)
    }

    class LoginException(message: String) : IOException(message)
    class HostKeyException(message: String) : IOException(message)

    /** Where Xray finds the proxy. */
    data class Bridge(val port: Int, val user: String, val password: String)

    private companion object {
        const val TAG = "SSH"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val RETRY_MS = 3_000L

        /** A rejected login or a changed host key will not change in seconds. */
        const val RETRY_AFTER_REFUSAL_MS = 60_000L
        const val KEEPALIVE_MS = 15_000
    }

    private val lock = Any()
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "ssh-bridge").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    private var profile: ServerProfile? = null
    private var login: SshLogin? = null
    private var session: Session? = null
    private var lastFailure = 0L
    private var lastError: IOException? = null

    /** Starts the proxy for [profile]; the SSH connection is made by [connect] or the first request. */
    fun start(profile: ServerProfile, login: SshLogin): Bridge {
        stop()
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val user = "tunnellock"
        val password = randomToken()
        synchronized(lock) {
            this.profile = profile
            this.login = login
            lastFailure = 0L
            lastError = null
        }
        server = socket
        thread(name = "ssh-accept", isDaemon = true) { accept(socket, user, password) }
        return Bridge(socket.localPort, user, password)
    }

    /** Connects now, so a wrong login or a changed host key is reported right away. */
    fun connect() {
        session()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        val old = synchronized(lock) {
            profile = null
            session.also { session = null }
        }
        runCatching { old?.disconnect() }
    }

    // ---- SSH --------------------------------------------------------------------

    private fun session(): Session = synchronized(lock) {
        session?.takeIf { it.isConnected }?.let { return it }
        val p = profile ?: throw IOException("SSH tunnel stopped")
        val l = login ?: throw IOException("SSH tunnel stopped")
        val now = SystemClock.elapsedRealtime()
        // Many requests arrive at once while the server is down: one attempt answers them all.
        val wait = if (lastError is LoginException || lastError is HostKeyException) RETRY_AFTER_REFUSAL_MS else RETRY_MS
        if (now - lastFailure < wait) throw lastError ?: IOException("SSH not connected")
        try {
            open(p, l).also {
                session = it
                lastError = null
                AppLog.i(TAG, "connected to ${p.displayAddress} (${p.stream.network})")
            }
        } catch (e: Exception) {
            val error = explain(e)
            lastFailure = now
            lastError = error
            AppLog.w(TAG, "connecting failed: ${error.message}")
            if (error is LoginException || error is HostKeyException) onProblem(error)
            throw error
        }
    }

    private fun open(p: ServerProfile, l: SshLogin): Session {
        val jsch = JSch()
        l.privateKey?.takeIf { it.isNotBlank() }?.let { key ->
            jsch.addIdentity("key", key.trim().toByteArray(), null, l.keyPassphrase?.takeIf { it.isNotEmpty() }?.toByteArray())
        }
        jsch.setHostKeyRepository(PinnedHostKey("${p.address}:${p.port}", p.hostKey))
        val s = jsch.getSession(l.username, p.address, p.port)
        l.password?.takeIf { it.isNotEmpty() }?.let { s.setPassword(it) }
        s.setUserInfo(Answers(l.password))
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.setSocketFactory(Transport(p))
        s.setServerAliveInterval(KEEPALIVE_MS)
        s.setServerAliveCountMax(3)
        s.connect(CONNECT_TIMEOUT_MS)
        return s
    }

    private fun explain(e: Exception): IOException {
        val message = e.message.orEmpty()
        return when {
            e is IOException && e !is java.net.SocketException -> e
            "Auth fail" in message || "Auth cancel" in message || "USERAUTH fail" in message ->
                LoginException("the server rejected the username, password or key")
            "HostKey has been changed" in message ->
                HostKeyException("the server's host key changed: a different server answered")
            else -> IOException(message.ifEmpty { e.javaClass.simpleName })
        }
    }

    /** Remembers the host key of the first connection, or checks the one the link pins. */
    private inner class PinnedHostKey(private val server: String, private val pinned: String?) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.CHANGED
            val seen = fingerprint(key)
            val expected = pinned?.trimEnd('=') ?: hostKeys.get(server)
            return when {
                expected == null -> {
                    hostKeys.put(server, seen)
                    AppLog.i(TAG, "remembering the host key of $server: $seen")
                    HostKeyRepository.OK
                }
                expected == seen -> HostKeyRepository.OK
                else -> {
                    AppLog.w(TAG, "host key of $server changed: expected $expected, got $seen")
                    HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "tunnellock"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers "password" prompts (keyboard-interactive) with the password; nothing else. */
    private class Answers(private val password: String?) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?) = !password.isNullOrEmpty()
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (password.isNullOrEmpty() || prompt == null) return null
            if (prompt.any { !it.contains("password", ignoreCase = true) }) return null
            return Array(prompt.size) { password }
        }
    }

    /**
     * How the SSH connection reaches the server. TLS here is camouflage (servers behind
     * stunnel or a CDN usually have no certificate a phone would trust); the host key
     * check above is what proves the server.
     */
    private class Transport(private val p: ServerProfile) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.tcpNoDelay = true
            val tls = p.stream.network == "tls" || p.stream.network == "wss"
            val socket = if (tls) wrapTls(plain, host, port) else plain
            if (p.stream.network == "ws" || p.stream.network == "wss") upgrade(socket, host)
            return socket
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

        private fun wrapTls(plain: Socket, host: String, port: Int): Socket {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(AnyCertificate), SecureRandom())
            val name = p.stream.sni ?: p.stream.host ?: host
            val ssl = context.socketFactory.createSocket(plain, name, port, true) as SSLSocket
            if (!looksLikeIp(name)) {
                runCatching { ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(name)) } }
            }
            ssl.soTimeout = CONNECT_TIMEOUT_MS
            ssl.startHandshake()
            ssl.soTimeout = 0
            return ssl
        }

        /** HTTP upgrade, then the stream carries SSH as it is (what SSH-over-WebSocket servers expect). */
        private fun upgrade(socket: Socket, host: String) {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val request = "GET ${p.stream.path ?: "/"} HTTP/1.1\r\n" +
                "Host: ${p.stream.host ?: host}\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "User-Agent: Mozilla/5.0\r\n\r\n"
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            // Byte by byte, so nothing after the headers (the server's SSH greeting) is taken.
            val input = socket.getInputStream()
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw IOException("the server closed the connection during the WebSocket upgrade")
                headers.append(b.toChar())
                if (headers.length > 16_384) throw IOException("WebSocket upgrade answer too long")
            }
            val status = headers.lineSequence().first().split(' ').getOrNull(1).orEmpty()
            if (status != "101" && !status.startsWith("2")) {
                throw IOException("WebSocket upgrade refused: ${headers.lineSequence().first().take(80)}")
            }
            socket.soTimeout = 0
        }
    }

    private object AnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ---- SOCKS5 proxy for Xray --------------------------------------------------

    private fun accept(socket: ServerSocket, user: String, password: String) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            workers.execute { serve(client, user, password) }
        }
    }

    private fun serve(client: Socket, user: String, password: String) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            // Greeting: only username/password (RFC 1929).
            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (methods.none { it.toInt() == 2 }) {
                output.write(byteArrayOf(5, 0xFF.toByte()))
                return
            }
            output.write(byteArrayOf(5, 2))
            if (input.readUnsignedByte() != 1) return
            val u = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val pw = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val allowed = MessageDigest.isEqual(u, user.toByteArray()) && MessageDigest.isEqual(pw, password.toByteArray())
            output.write(byteArrayOf(1, if (allowed) 0 else 1))
            if (!allowed) return
            // Request: CONNECT only (SSH cannot carry UDP).
            if (input.readUnsignedByte() != 5) return
            val command = input.readUnsignedByte()
            input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                else -> return reply(output, 8)
            } ?: return reply(output, 4)
            val port = input.readUnsignedShort()
            if (command != 1) return reply(output, 7)
            channel = try {
                (session().openChannel("direct-tcpip") as ChannelDirectTCPIP).apply {
                    setHost(host)
                    setPort(port)
                    setOrgIPAddress("127.0.0.1")
                    setOrgPort(client.port)
                    // The server's data goes straight to Xray; its end closes the socket.
                    setOutputStream(output)
                }
            } catch (e: Exception) {
                return reply(output, 1)
            }
            val toServer = channel.outputStream
            try {
                channel.connect(15_000)
            } catch (e: Exception) {
                return reply(output, 5)
            }
            reply(output, 0)
            client.soTimeout = 0
            val buffer = ByteArray(16_384)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                toServer.write(buffer, 0, n)
                toServer.flush()
            }
            // Xray is done sending: tell the server and let the answer finish.
            runCatching { toServer.close() }
            val until = SystemClock.elapsedRealtime() + 60_000
            while (!channel.isClosed && !client.isClosed && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        } catch (e: Exception) {
            // The client or the server went away.
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { client.close() }
        }
    }

    private fun reply(output: OutputStream, code: Int) {
        runCatching { output.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)) }
    }

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) })
}

/** The server's host key as OpenSSH prints it. */
internal fun fingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

private fun looksLikeIp(value: String) = value.all { it.isDigit() || it == '.' } || value.contains(':')
