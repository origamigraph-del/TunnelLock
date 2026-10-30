package app.tunnellock.core

import app.tunnellock.core.update.FeedEntry
import app.tunnellock.core.update.UpdateFeed
import app.tunnellock.core.update.UpdatePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {
    private fun release(version: Int, priority: String, id: String = "r$version") =
        """### TunnelLock 0.1.$version

        notes

        <!-- tunnellock-update:{"kind":"release","id":"$id","version":$version,"name":"0.1.$version","priority":"$priority","fa":"یادداشت $version","en":"note $version"} -->"""

    @Test
    fun parsesAReleaseEntry() {
        val entry = UpdateFeed.parse(release(80, "important")) as FeedEntry.Release
        assertEquals(80, entry.version)
        assertEquals("0.1.80", entry.name)
        assertEquals(UpdatePriority.IMPORTANT, entry.priority)
        assertEquals("یادداشت 80", entry.notesFa)
    }

    @Test
    fun ignoresCommentsWithoutAnEntry() {
        assertNull(UpdateFeed.parse("### TunnelLock log\n```text\nline\n```"))
        assertNull(UpdateFeed.parse("<!-- tunnellock-update:{not json} -->"))
        assertNull(UpdateFeed.parse("""<!-- tunnellock-update:{"kind":"release","version":80,"priority":"someday"} -->"""))
    }

    @Test
    fun theHighestSkippedPriorityWins() {
        val entries = listOf(release(70, "critical"), release(76, "critical"), release(80, "optional"), release(85, "recommended"))
            .mapNotNull { UpdateFeed.parse(it) }
        val status = UpdateFeed.status(entries, installedVersion = 75)
        assertEquals(listOf(76, 80, 85), status.pending.map { it.version })
        assertEquals(85, status.latest?.version)
        // A security fix in a skipped version keeps the update urgent.
        assertEquals(UpdatePriority.CRITICAL, status.priority)
        assertNull(UpdateFeed.status(entries, installedVersion = 85).priority)
    }

    @Test
    fun upstreamNewsOnlyWhileTheAppHasNotMovedOn() {
        val upstream = UpdateFeed.parse(
            """<!-- tunnellock-update:{"kind":"upstream","component":"Xray core","current":"v26.10.21","latest":"v26.11.2","appVersion":80} -->""",
        ) as FeedEntry.Upstream
        assertEquals("v26.11.2", upstream.latest)
        assertEquals(1, UpdateFeed.status(listOf(upstream), installedVersion = 80).upstream.size)
        assertEquals(1, UpdateFeed.status(listOf(upstream), installedVersion = 75).upstream.size)
        assertTrue(UpdateFeed.status(listOf(upstream), installedVersion = 81).upstream.isEmpty())
    }

    @Test
    fun longNotesAreCut() {
        val long = "x".repeat(5_000)
        val entry = UpdateFeed.parse(
            """<!-- tunnellock-update:{"kind":"release","id":"a","version":1,"priority":"optional","fa":"$long","en":""} -->""",
        ) as FeedEntry.Release
        assertEquals(2_000, entry.notesFa.length)
    }

    @Test
    fun onlyTheBuildMayWriteTheFeed() {
        val page = org.json.JSONArray()
            .put(org.json.JSONObject().put("user", org.json.JSONObject().put("login", "github-actions[bot]")).put("body", release(80, "important")))
            .put(org.json.JSONObject().put("user", org.json.JSONObject().put("login", "someone")).put("body", release(99, "critical")))
            .put(org.json.JSONObject().put("user", org.json.JSONObject().put("login", "github-actions[bot]")).put("body", "### TunnelLock log"))
        val entries = UpdateFeed.entriesFromPage(page.toString())
        assertEquals(listOf(80), entries.map { (it as FeedEntry.Release).version })
    }

    @Test
    fun entriesSurviveEncoding() {
        val entries = listOf(
            UpdateFeed.parse(release(80, "critical"))!!,
            FeedEntry.Upstream("OpenVPN", "v2.6.23", "v2.6.24", 80),
        )
        assertEquals(entries, entries.map { UpdateFeed.decode(UpdateFeed.encode(it)) })
    }
}
