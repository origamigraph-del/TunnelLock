package app.tunnellock.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SSLException

/** One attempt to reach the internet. [reached] = a server answered, i.e. traffic left the phone. */
data class CheckResult(
    val name: String,
    val reached: Boolean,
    val ip: String? = null,
    val country: String? = null,
    val ms: Long = 0,
    val error: String? = null,
)

/**
 * Four independent ways out, run in parallel:
 * - "cf":     HTTPS to 1.1.1.1 by IP (works without DNS), reports exit IP and country
 * - "cf-dns": the same through DNS (www.cloudflare.com)
 * - "ipify":  HTTPS to api64.ipify.org (exit IPv4 or IPv6)
 * - "stun":   UDP STUN request to Google (exit IP of UDP traffic)
 */
object Checks {
    private const val TIMEOUT_MS = 5_000

    suspend fun runAll(): List<CheckResult> = coroutineScope {
        listOf(
            async(Dispatchers.IO) { http("cf", "https://1.1.1.1/cdn-cgi/trace", ProbeLogic::parseTrace) },
            async(Dispatchers.IO) { http("cf-dns", "https://www.cloudflare.com/cdn-cgi/trace", ProbeLogic::parseTrace) },
            async(Dispatchers.IO) { http("ipify", "https://api64.ipify.org") { body -> body.trim().takeIf { it.length in 3..45 } to null } },
            async(Dispatchers.IO) { stun("stun", "stun.l.google.com", 19302) },
        ).awaitAll()
    }

    private fun http(name: String, url: String, parse: (String) -> Pair<String?, String?>): CheckResult {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            // A fresh connection every time: a pooled one could hide a change of route.
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache")
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    // Any HTTP answer still means the request got out.
                    return CheckResult(name, reached = true, ms = elapsed(), error = "HTTP $code")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val (ip, country) = parse(body)
                CheckResult(name, reached = true, ip = ip, country = country, ms = elapsed())
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            CheckResult(name, reached = false, ms = elapsed(), error = shortError(e))
        }
    }

    private fun stun(name: String, host: String, port: Int): CheckResult {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val address = InetAddress.getByName(host)
                val transactionId = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val request = ProbeLogic.stunRequest(transactionId)
                socket.send(DatagramPacket(request, request.size, address, port))
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val ip = ProbeLogic.parseStunResponse(buffer, packet.length, transactionId)
                CheckResult(name, reached = true, ip = ip, ms = elapsed(), error = if (ip == null) "bad answer" else null)
            }
        } catch (e: Exception) {
            CheckResult(name, reached = false, ms = elapsed(), error = shortError(e))
        }
    }

    private fun shortError(e: Exception): String = when (e) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "dns-fail"
        is NoRouteToHostException -> "no-route"
        is ConnectException -> "refused"
        is SSLException -> "tls-fail"
        is SocketException -> e.message?.let { msg ->
            when {
                msg.contains("reset", ignoreCase = true) -> "reset"
                msg.contains("EPERM") || msg.contains("not permitted") -> "blocked-by-system"
                msg.contains("unreachable", ignoreCase = true) -> "unreachable"
                else -> "socket"
            }
        } ?: "socket"
        is IOException -> if (e.message?.contains("end of stream") == true) "closed" else "io(${e.message?.take(40)})"
        else -> "${e.javaClass.simpleName}(${e.message?.take(40)})"
    }
}

enum class Verdict {
    /** Reached the internet through the expected exit (the VPN). */
    ONLINE,

    /** Nothing got out. This is what a working kill switch looks like while the VPN is down. */
    BLOCKED,

    /** Got out from the home country or the real IP: traffic bypassed the VPN. */
    LEAK,

    /** Got out through an exit IP never seen before: a server change, or a leak. */
    IP_CHANGED,

    /** Got out directly, but this copy runs outside the secure space, where that is normal. */
    DIRECT,
}

data class Classification(val verdict: Verdict, val reason: String?, val baseline: Set<String>)

/** Pure parsing and decision logic (no Android, no network). */
object ProbeLogic {
    private const val MAGIC_COOKIE = 0x2112A442

    /**
     * [baseline] holds the exit IPs seen while online; it starts with the first
     * successful round and grows only with rounds that share an IP with it.
     */
    fun classify(
        results: List<CheckResult>,
        baseline: Set<String>,
        homeCountry: String,
        realIp: String,
        inSecureSpace: Boolean = true,
    ): Classification {
        val reached = results.filter { it.reached }
        val ips = reached.mapNotNull { it.ip }.toSet()
        val home = homeCountry.trim()
        val real = realIp.trim()
        return when {
            !inSecureSpace && reached.isNotEmpty() ->
                Classification(Verdict.DIRECT, "outside the secure space there is no VPN; this is the real exit", baseline)
            home.isNotEmpty() && reached.any { it.country.equals(home, ignoreCase = true) } ->
                Classification(Verdict.LEAK, "exit country is $home", baseline)
            real.isNotEmpty() && real in ips ->
                Classification(Verdict.LEAK, "exit IP is your real IP", baseline)
            reached.isEmpty() -> Classification(Verdict.BLOCKED, null, baseline)
            baseline.isEmpty() || ips.isEmpty() || ips.any { it in baseline } ->
                Classification(Verdict.ONLINE, null, baseline + ips)
            else -> Classification(Verdict.IP_CHANGED, "new exit IP ${ips.joinToString()}", baseline)
        }
    }

    /** Cloudflare /cdn-cgi/trace body: "ip=1.2.3.4\nloc=DE\n..." */
    fun parseTrace(body: String): Pair<String?, String?> {
        val values = body.lineSequence().mapNotNull { line ->
            val key = line.substringBefore('=', "")
            if (key.isEmpty()) null else key to line.substringAfter('=')
        }.toMap()
        return values["ip"] to values["loc"]
    }

    fun stunRequest(transactionId: ByteArray): ByteArray =
        ByteBuffer.allocate(20)
            .putShort(0x0001) // Binding Request
            .putShort(0)
            .putInt(MAGIC_COOKIE)
            .put(transactionId)
            .array()

    /** A transaction ID that carries a ping's number after a per-run random [session] prefix (8 bytes). */
    fun pingTransaction(session: ByteArray, seq: Int): ByteArray = ByteBuffer.allocate(12).put(session).putInt(seq).array()

    /** The ping number and mapped address of an answer to one of [session]'s pings, or null for anything else. */
    fun parsePingAnswer(data: ByteArray, length: Int, session: ByteArray): Pair<Int, String?>? {
        if (length < 20) return null
        val transactionId = data.copyOfRange(8, 20)
        if (!transactionId.copyOfRange(0, 8).contentEquals(session)) return null
        if (((data[0].toInt() and 0xff) shl 8 or (data[1].toInt() and 0xff)) != 0x0101) return null
        return ByteBuffer.wrap(transactionId, 8, 4).int to parseStunResponse(data, length, transactionId)
    }

    /** Returns the mapped address of a STUN Binding Success response, or null. */
    fun parseStunResponse(data: ByteArray, length: Int, transactionId: ByteArray): String? {
        if (length < 20) return null
        fun u16(at: Int) = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
        if (u16(0) != 0x0101) return null
        if (!data.copyOfRange(8, 20).contentEquals(transactionId)) return null
        val end = minOf(length, 20 + u16(2))
        var pos = 20
        while (pos + 4 <= end) {
            val type = u16(pos)
            val size = u16(pos + 2)
            val value = pos + 4
            if ((type == 0x0020 || type == 0x0001) && value + 4 <= end) {
                val xored = type == 0x0020
                val family = data[value + 1].toInt() and 0xff
                val addressLength = if (family == 0x01) 4 else if (family == 0x02) 16 else 0
                if (addressLength > 0 && value + 4 + addressLength <= end) {
                    val raw = data.copyOfRange(value + 4, value + 4 + addressLength)
                    if (xored) {
                        val mask = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(transactionId).array()
                        for (i in raw.indices) raw[i] = (raw[i].toInt() xor mask[i].toInt()).toByte()
                    }
                    return InetAddress.getByAddress(raw).hostAddress
                }
            }
            pos = value + (size + 3) / 4 * 4
        }
        return null
    }
}
