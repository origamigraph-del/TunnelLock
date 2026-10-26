package app.tunnellock.data

import app.tunnellock.BuildConfig
import app.tunnellock.core.parser.LinkParser
import app.tunnellock.core.parser.ParseReport
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SubscriptionResult(val report: ParseReport, val usage: SubscriptionUsage?)

/**
 * Downloads a subscription. Panels decide the format from the request headers:
 * Marzban, for example, answers a browser-like "Accept: text/html" with its web
 * page, and some panels only serve known clients. So we ask for any content
 * type, and if the answer has no usable servers we retry with a v2rayNG
 * User-Agent, which every panel understands.
 */
object SubscriptionFetcher {
    private const val TAG = "Subscription"
    private val userAgents = listOf("TunnelLock/${BuildConfig.VERSION_NAME}", "v2rayNG/1.10.24")

    fun fetch(url: String): SubscriptionResult {
        val host = runCatching { URL(url).host }.getOrDefault("?")
        var problem = "no response"
        for (userAgent in userAgents) {
            val agent = userAgent.substringBefore('/')
            try {
                val response = get(url, userAgent)
                AppLog.i(
                    TAG,
                    "$host [$agent]: HTTP ${response.code}, ${response.contentType ?: "no content-type"}, ${response.body.length} chars",
                )
                if (response.code !in 200..299) {
                    problem = "HTTP ${response.code}"
                    continue
                }
                if (looksLikeHtml(response.body)) {
                    problem = "the server sent a web page instead of a subscription"
                    continue
                }
                val report = LinkParser.parseReport(response.body)
                AppLog.i(TAG, "$host [$agent]: ${report.servers.size} servers, unsupported ${report.skipped}")
                if (report.servers.isNotEmpty()) return SubscriptionResult(report, parseUsage(response.userInfo))
                problem = if (report.skipped.isNotEmpty()) {
                    "no supported servers (unsupported: ${report.skipped.entries.joinToString { "${it.key}×${it.value}" }})"
                } else {
                    "no servers found (${describe(response.body)})"
                }
            } catch (e: Exception) {
                problem = "${e.javaClass.simpleName}: ${e.message}"
                AppLog.w(TAG, "$host [$agent]: download failed", e)
            }
        }
        throw IOException(problem)
    }

    private class Response(val code: Int, val contentType: String?, val userInfo: String?, val body: String)

    private fun get(url: String, userAgent: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "*/*")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, connection.contentType, connection.getHeaderField("subscription-userinfo"), body)
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeHtml(body: String): Boolean {
        val start = body.trimStart().take(64).lowercase()
        return start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<head")
    }

    /** A short, secret-free description of an unrecognised body. */
    private fun describe(body: String): String {
        val trimmed = body.trimStart()
        return when {
            trimmed.isEmpty() -> "empty response"
            trimmed.startsWith("proxies:") || trimmed.contains("\nproxies:") -> "Clash YAML format, not supported"
            trimmed.startsWith("{") && trimmed.contains("\"outbounds\"") -> "JSON with outbounds"
            else -> "starts with \"${trimmed.take(16).replace(Regex("[A-Za-z0-9]"), "x")}\""
        }
    }

    /** "upload=1; download=2; total=3; expire=4" */
    private fun parseUsage(header: String?): SubscriptionUsage? {
        if (header.isNullOrBlank()) return null
        val values = header.split(';').mapNotNull { part ->
            val key = part.substringBefore('=').trim().lowercase()
            val value = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            key to value
        }.toMap()
        if (values.isEmpty()) return null
        return SubscriptionUsage(
            usedBytes = (values["upload"] ?: 0) + (values["download"] ?: 0),
            totalBytes = values["total"] ?: 0,
            expiresAt = values["expire"] ?: 0,
        )
    }
}
