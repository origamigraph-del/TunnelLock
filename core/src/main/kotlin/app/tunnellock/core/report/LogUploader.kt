package app.tunnellock.core.report

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * Sends a log to the developer by posting it as a comment on a GitHub issue of
 * the project's (private) repository, where it can be read without the user
 * copying it by hand.
 */
object LogUploader {
    /** GitHub limits a comment to 65 536 characters; keep room for the header. */
    private const val MAX_LOG_CHARS = 60_000

    /** Formats a comment: a title, a few detail lines, and the log in a code block. */
    fun format(title: String, details: List<String>, log: String): String {
        val trimmed = if (log.length > MAX_LOG_CHARS) "…(older lines trimmed)…\n" + log.takeLast(MAX_LOG_CHARS) else log
        return buildString {
            append("### ").append(title).append('\n')
            details.forEach { append("- ").append(it).append('\n') }
            append("\n```text\n")
            append(trimmed.replace("```", "'''"))
            append("\n```\n")
        }
    }

    /**
     * A log too long for one comment, as gzip + base64 in pieces that each fit
     * one. Joined in order, they decode with `base64 -d | gunzip`.
     */
    fun compressedParts(log: String): List<String> {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(log.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).chunked(MAX_LOG_CHARS)
    }

    fun formatPart(title: String, index: Int, total: Int, chunk: String): String = buildString {
        append("### ").append(title).append(" — complete log, part ").append(index).append('/').append(total).append('\n')
        append("gzip + base64: join all parts in order, then `base64 -d | gunzip`.\n\n```text\n")
        append(chunk)
        append("\n```\n")
    }

    /**
     * Blocking. Posts a log: one readable comment (the details and the newest
     * lines), plus the complete log in compressed parts when it does not fit.
     * Returns the first comment's URL.
     */
    fun postLog(token: String, repo: String, issue: Int, title: String, details: List<String>, log: String): String {
        val url = post(token, repo, issue, format(title, details, log))
        if (log.length > MAX_LOG_CHARS) {
            val parts = compressedParts(log)
            parts.forEachIndexed { i, chunk -> post(token, repo, issue, formatPart(title, i + 1, parts.size, chunk)) }
        }
        return url
    }

    /** Blocking. Posts [body] on issue [issue] of [repo] ("owner/name"); returns the comment's URL. */
    fun post(token: String, repo: String, issue: Int, body: String): String {
        val connection = URL("https://api.github.com/repos/$repo/issues/$issue/comments").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("User-Agent", "TunnelLock")
        try {
            connection.outputStream.use { it.write(JSONObject().put("body", body).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${response.take(160)}")
            return runCatching { JSONObject(response).optString("html_url") }.getOrDefault("")
        } finally {
            connection.disconnect()
        }
    }
}
