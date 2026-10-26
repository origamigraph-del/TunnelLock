package app.tunnellock.core

import app.tunnellock.core.report.LogUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogUploaderTest {
    @Test
    fun formatsHeaderDetailsAndCodeBlock() {
        val body = LogUploader.format("TunnelProbe log", listOf("Android 16", "user 10"), "line 1\nline ```2```")
        assertTrue(body.startsWith("### TunnelProbe log\n- Android 16\n- user 10\n"))
        assertTrue(body.contains("```text\nline 1\nline '''2'''\n```"))
    }

    @Test
    fun trimsLongLogsToGithubLimit() {
        val body = LogUploader.format("t", emptyList(), "x".repeat(100_000) + "END")
        assertTrue(body.length < 65_536)
        assertTrue(body.contains("END"))
        assertTrue(body.contains("trimmed"))
        assertFalse(body.contains("x".repeat(60_001)))
        assertEquals(1, Regex("```text").findAll(body).count())
    }
}
