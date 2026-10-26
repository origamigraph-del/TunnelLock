package app.tunnellock.core

import app.tunnellock.core.report.LogUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

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

    @Test
    fun compressedPartsRebuildTheWholeLog() {
        // A day of log lines that do not compress well, so it needs several parts.
        val random = java.util.Random(7)
        val log = (1..40_000).joinToString("\n") { "12:00:${it % 60} I/Tag: ${random.nextLong()} ${random.nextInt()}" }
        val parts = LogUploader.compressedParts(log)
        assertTrue(parts.size > 1)
        parts.forEachIndexed { i, chunk ->
            val body = LogUploader.formatPart("TunnelLock log", i + 1, parts.size, chunk)
            assertTrue(body.length < 65_536)
            assertTrue(body.contains("part ${i + 1}/${parts.size}"))
        }
        val decoded = GZIPInputStream(Base64.getDecoder().decode(parts.joinToString("")).inputStream())
            .readBytes().toString(Charsets.UTF_8)
        assertEquals(log, decoded)
    }
}
