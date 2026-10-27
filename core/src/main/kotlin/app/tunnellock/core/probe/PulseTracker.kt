package app.tunnellock.core.probe

/**
 * Judges TunnelProbe's precise mode: one tiny ping a second through the tunnel.
 * What counts is silence: while the tunnel works an answer arrives about every
 * second, so a longer wait between two answers is a break, whether the pings
 * were lost or only held up. Lost pings and slow answers go into the summary.
 *
 * All times are milliseconds on one clock (wall time, so the log can show them).
 * Not thread-safe: the caller feeds it from one thread.
 */
class PulseTracker(
    /** Longer silence than this is a break (two pings in a row unanswered, or answers held up). */
    private val breakMs: Long = 2_500,
    /** About one lost ping: counted in the summary only. */
    private val shortGapMs: Long = 1_700,
    /** A ping still unanswered after this is lost. */
    private val forgetMs: Long = 30_000,
) {
    /** No answer from [from] (the last one before) to [to] (the first one after). */
    data class Break(val from: Long, val to: Long, val probePaused: Boolean) {
        val ms: Long get() = to - from
    }

    sealed interface Event {
        /** The first answer: the pings get through, so silences from now on are breaks. */
        data class FirstAnswer(val at: Long, val ip: String?, val rttMs: Long) : Event

        data class BreakOver(val gap: Break) : Event

        /** The exit IP the ping server sees changed (another server, or traffic left without the VPN). */
        data class IpChanged(val at: Long, val from: String, val to: String) : Event
    }

    data class Summary(
        val sent: Int,
        val answered: Int,
        val lost: Int,
        val breaks: Int,
        val shortGaps: Int,
        val medianRttMs: Long?,
        val maxRttMs: Long?,
    )

    private val inFlight = LinkedHashMap<Int, Long>()
    private var nextSeq = 0
    private var lastSentAt: Long? = null
    private var lastArrival: Long? = null
    private var lastIp: String? = null

    /** The longest pause between two pings since the last answer: Android froze the probe itself. */
    private var maxSendGap = 0L

    private var sent = 0
    private var answered = 0
    private var lost = 0
    private var breaks = 0
    private var shortGaps = 0
    private val rtts = mutableListOf<Long>()

    /** A ping goes out (or was meant to: a failed send counts too). Returns its number. */
    fun sent(at: Long): Int {
        lastSentAt?.let { maxSendGap = maxOf(maxSendGap, at - it) }
        lastSentAt = at
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val ping = iterator.next()
            if (at - ping.value < forgetMs) break
            iterator.remove()
            lost++
        }
        sent++
        val seq = nextSeq++
        inFlight[seq] = at
        return seq
    }

    /** An answer to ping [seq] arrived. Answers must be fed in the order they arrive. */
    fun answered(seq: Int, at: Long, ip: String?): List<Event> {
        val sentAt = inFlight.remove(seq) ?: return emptyList() // a duplicate, or long forgotten
        answered++
        val rtt = at - sentAt
        rtts += rtt
        val events = mutableListOf<Event>()
        val last = lastArrival
        if (last == null) {
            events += Event.FirstAnswer(at, ip, rtt)
        } else {
            val gap = at - last
            if (gap > breakMs) {
                breaks++
                events += Event.BreakOver(Break(last, at, probePaused = maxSendGap > breakMs))
            } else if (gap > shortGapMs) {
                shortGaps++
            }
        }
        lastArrival = maxOf(at, last ?: at)
        maxSendGap = 0
        if (ip != null) {
            lastIp?.takeIf { it != ip }?.let { events += Event.IpChanged(at, it, ip) }
            lastIp = ip
        }
        return events
    }

    /** The break still going on at [now], if any (for when the test stops). */
    fun silence(now: Long): Break? {
        val last = lastArrival ?: return null
        val pausedNow = lastSentAt?.let { now - it > breakMs } ?: false
        return if (now - last > breakMs) Break(last, now, probePaused = pausedNow || maxSendGap > breakMs) else null
    }

    /** Counts since the last summary, then starts counting again. */
    fun summary(): Summary {
        val sorted = rtts.sorted()
        val result = Summary(
            sent = sent,
            answered = answered,
            lost = lost,
            breaks = breaks,
            shortGaps = shortGaps,
            medianRttMs = sorted.getOrNull(sorted.size / 2),
            maxRttMs = sorted.lastOrNull(),
        )
        sent = 0
        answered = 0
        lost = 0
        breaks = 0
        shortGaps = 0
        rtts.clear()
        return result
    }
}
