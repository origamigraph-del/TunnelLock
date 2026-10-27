package app.tunnellock.core

import app.tunnellock.core.probe.PulseTracker
import app.tunnellock.core.probe.PulseTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseTrackerTest {

    /** Pings every second from [from] to [until]; [answer] gives a ping's answer delay, or null if lost. */
    private fun PulseTracker.run(from: Long, until: Long, ip: String = "1.2.3.4", answer: (Long) -> Long?): List<Event> {
        val answers = sortedMapOf<Long, MutableList<Int>>()
        val events = mutableListOf<Event>()
        var t = from
        while (t < until) {
            val seq = sent(t)
            answer(t)?.let { answers.getOrPut(t + it) { mutableListOf() } += seq }
            // Answers that arrive before the next ping goes out.
            while (answers.isNotEmpty() && answers.firstKey() < t + 1000) {
                val at = answers.firstKey()
                answers.remove(at)!!.forEach { events += answered(it, at, ip) }
            }
            t += 1000
        }
        answers.forEach { (at, seqs) -> seqs.forEach { events += answered(it, at, ip) } }
        return events
    }

    @Test
    fun steadyAnswersAreNoBreak() {
        val tracker = PulseTracker()
        val events = tracker.run(0, 60_000) { 120 }
        assertEquals(1, events.size)
        assertTrue(events[0] is Event.FirstAnswer)
        val summary = tracker.summary()
        assertEquals(60, summary.sent)
        assertEquals(60, summary.answered)
        assertEquals(0, summary.breaks)
        assertEquals(120L, summary.medianRttMs)
    }

    @Test
    fun lostPingsMakeOneBreakWithItsEdges() {
        val tracker = PulseTracker()
        // Down from 10 s to 15 s: the pings sent at 10..14 s are lost.
        val events = tracker.run(0, 30_000) { t -> if (t in 10_000 until 15_000) null else 100 }
        val breaks = events.filterIsInstance<Event.BreakOver>()
        assertEquals(1, breaks.size)
        assertEquals(9_100L, breaks[0].gap.from)
        assertEquals(15_100L, breaks[0].gap.to)
        assertFalse(breaks[0].gap.probePaused)
        tracker.sent(60_000) // later pings forget the lost ones
        assertEquals(5, tracker.summary().lost)
    }

    @Test
    fun heldUpAnswersAreABreakToo() {
        val tracker = PulseTracker()
        // The server stalls from 10 s to 15 s: everything sent meanwhile is answered at 15 s.
        val events = tracker.run(0, 30_000) { t -> if (t in 10_000 until 15_000) 15_000 - t else 100 }
        val breaks = events.filterIsInstance<Event.BreakOver>()
        assertEquals(1, breaks.size)
        assertEquals(9_100L, breaks[0].gap.from)
        assertEquals(15_000L, breaks[0].gap.to)
        assertEquals(0, tracker.summary().lost)
    }

    @Test
    fun oneLostPingIsOnlyAShortGap() {
        val tracker = PulseTracker()
        val events = tracker.run(0, 20_000) { t -> if (t == 8_000L) null else 100 }
        assertTrue(events.none { it is Event.BreakOver })
        assertEquals(1, tracker.summary().shortGaps)
    }

    @Test
    fun aFrozenProbeIsNotCalledANetworkBreak() {
        val tracker = PulseTracker()
        tracker.run(0, 10_000) { 100 }
        // Android froze the probe after its ping at 9 s: none went out until 30 s.
        val events = tracker.run(30_000, 35_000) { 100 }
        val gap = events.filterIsInstance<Event.BreakOver>().single().gap
        assertTrue(gap.probePaused)
        assertEquals(9_100L, gap.from)
        assertEquals(30_100L, gap.to)
    }

    @Test
    fun nothingCountsBeforeTheFirstAnswer() {
        val tracker = PulseTracker()
        // Started while the VPN was down: 20 s without answers, then it comes up.
        val events = tracker.run(0, 30_000) { t -> if (t < 20_000) null else 100 }
        assertTrue(events.none { it is Event.BreakOver })
        assertEquals(20_100L, (events.first() as Event.FirstAnswer).at)
        assertNull(PulseTracker().silence(100_000))
    }

    @Test
    fun silenceAtTheEndIsReported() {
        val tracker = PulseTracker()
        tracker.run(0, 20_000) { t -> if (t >= 10_000) null else 100 }
        val gap = tracker.silence(20_000)!!
        assertEquals(9_100L, gap.from)
        assertFalse(gap.probePaused)
        assertNull(tracker.silence(11_000))
    }

    @Test
    fun exitIpChangeIsAnEvent() {
        val tracker = PulseTracker()
        tracker.run(0, 5_000, ip = "1.2.3.4") { 100 }
        val events = tracker.run(5_000, 10_000, ip = "5.6.7.8") { 100 }
        val change = events.filterIsInstance<Event.IpChanged>().single()
        assertEquals("1.2.3.4", change.from)
        assertEquals("5.6.7.8", change.to)
    }

    @Test
    fun duplicateAnswersAreIgnored() {
        val tracker = PulseTracker()
        val seq = tracker.sent(0)
        assertEquals(1, tracker.answered(seq, 100, null).size)
        assertTrue(tracker.answered(seq, 150, null).isEmpty())
        assertEquals(1, tracker.summary().answered)
    }
}
