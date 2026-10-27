package app.tunnellock.probe

import android.content.Context
import android.os.SystemClock
import app.tunnellock.core.probe.PulseTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Precise mode: a STUN ping (one small UDP packet each way) every second through
 * the tunnel, so a break of a few seconds shows up with its start and end, not
 * only when a full round happens to hit it. The pings reuse one socket, so the
 * tunnel carries them as one long-lived flow; [PulseTracker] judges the answers.
 */
class Pulse(private val context: Context) {

    private companion object {
        const val HOST = "stun.l.google.com"
        const val PORT = 19302
        const val TICK_MS = 1_000L
        const val SUMMARY_MS = 15 * 60 * 1000L

        /** A fresh socket (a new flow through the tunnel) after this many seconds without an answer. */
        const val NEW_SOCKET_AFTER = 5
    }

    private val lock = Any()
    private val tracker = PulseTracker()
    private val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var socket: DatagramSocket? = null
    private var finished = false

    /** Pings until [enabled] turns false, the coroutine is cancelled or [finish] is called. */
    suspend fun run(enabled: () -> Boolean) = withContext(Dispatchers.IO) {
        ProbeState.log(context, "~~ precise mode on: a ping every second through the tunnel (UDP STUN, $HOST)")
        var address: InetAddress? = null
        var quietTicks = 0
        var lastSummary = SystemClock.elapsedRealtime()
        val buffer = ByteArray(512)
        while (currentCoroutineContext().isActive && enabled()) {
            val tickEnd = SystemClock.elapsedRealtime() + TICK_MS
            val seq = synchronized(lock) {
                if (finished) return@withContext
                tracker.sent(System.currentTimeMillis())
            }
            val current = runCatching {
                val target = address ?: InetAddress.getAllByName(HOST).let { all -> all.firstOrNull { it is Inet4Address } ?: all.first() }
                address = target
                val open = socket ?: synchronized(lock) {
                    if (finished) return@withContext
                    DatagramSocket().also { socket = it }
                }
                val request = ProbeLogic.stunRequest(ProbeLogic.pingTransaction(session, seq))
                open.send(DatagramPacket(request, request.size, target, PORT))
                open
            }.getOrElse { socket } // A failed send (no route, blocked) is a lost ping.
            var answers = 0
            while (true) {
                val left = tickEnd - SystemClock.elapsedRealtime()
                if (left <= 0) break
                if (current == null) {
                    delay(left)
                    break
                }
                try {
                    current.soTimeout = left.toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    current.receive(packet)
                    val (answered, ip) = ProbeLogic.parsePingAnswer(buffer, packet.length, session) ?: continue
                    answers++
                    synchronized(lock) {
                        if (finished) return@withContext
                        tracker.answered(answered, System.currentTimeMillis(), ip).forEach(::logEvent)
                    }
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    // Closed by finish(), or broken: the next round opens a new one.
                    synchronized(lock) { if (socket === current) socket = null }
                    runCatching { current.close() }
                    delay((tickEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    break
                }
            }
            quietTicks = if (answers > 0) 0 else quietTicks + 1
            if (quietTicks >= NEW_SOCKET_AFTER) {
                quietTicks = 0
                synchronized(lock) { socket?.close(); socket = null }
            }
            if (SystemClock.elapsedRealtime() - lastSummary >= SUMMARY_MS) {
                lastSummary = SystemClock.elapsedRealtime()
                synchronized(lock) { if (!finished) logSummary() }
            }
        }
    }

    /** Ends precise mode: a break still going on and the last counts go into the log. Safe from any thread. */
    fun finish() {
        synchronized(lock) { finishLocked() }
    }

    private fun finishLocked() {
        if (finished) return
        finished = true
        socket?.close()
        socket = null
        tracker.silence(System.currentTimeMillis())?.let { gap ->
            ProbeState.log(
                context,
                "~~ BREAK ${clock.format(Date(gap.from))} -> still no answer when precise mode stopped (${seconds(gap.ms)})" +
                    if (gap.probePaused) ", TunnelProbe itself was paused by Android" else "",
            )
        }
        logSummary()
        ProbeState.log(context, "~~ precise mode off")
    }

    private fun logEvent(event: PulseTracker.Event) {
        when (event) {
            is PulseTracker.Event.FirstAnswer ->
                ProbeState.log(context, "~~ pings answered: exit IP ${event.ip ?: "?"} (UDP), ${event.rttMs} ms")
            is PulseTracker.Event.BreakOver -> {
                val gap = event.gap
                ProbeState.log(
                    context,
                    "~~ BREAK ${clock.format(Date(gap.from))} -> ${clock.format(Date(gap.to))}: no answer for ${seconds(gap.ms)}" +
                        if (gap.probePaused) " (TunnelProbe itself was paused by Android, not a network break)" else "",
                )
            }
            is PulseTracker.Event.IpChanged -> {
                val realIp = ProbeState.realIp(context)
                if (realIp.isNotEmpty() && event.to == realIp) {
                    ProbeState.log(context, "~~ LEAK!! exit IP (UDP) changed ${event.from} -> ${event.to}, your real IP")
                    ProbeState.notifyLeak(context, "exit IP (UDP) is your real IP")
                } else {
                    ProbeState.log(context, "~~ exit IP (UDP) changed: ${event.from} -> ${event.to}")
                }
            }
        }
    }

    private fun logSummary() {
        val s = tracker.summary()
        if (s.sent == 0) return
        ProbeState.log(
            context,
            "~~ pings: ${s.sent} sent, ${s.answered} answered, ${s.lost} lost | ${s.breaks} breaks, " +
                "${s.shortGaps} single gaps | ${s.medianRttMs ?: "-"} ms typical, ${s.maxRttMs ?: "-"} ms slowest",
        )
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)
}
