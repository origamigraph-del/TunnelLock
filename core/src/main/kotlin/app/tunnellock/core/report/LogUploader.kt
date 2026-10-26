package app.tunnellock.core.report

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
