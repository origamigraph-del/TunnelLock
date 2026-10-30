package app.tunnellock.core.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How soon an update should be installed. */
enum class UpdatePriority {
    /** Small changes: shown in the app only. */
    OPTIONAL,

    /** New features or improvements. */
    RECOMMENDED,

    /** Servers or protocols that stop working without it, or a serious bug. */
    IMPORTANT,

    /** A leak or security fix: update the same day. */
    CRITICAL,
    ;

    companion object {
        fun parse(value: String?): UpdatePriority? = entries.find { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

sealed interface FeedEntry {
    /** A published version of TunnelLock ([version] is its versionCode). */
    data class Release(
        val id: String,
        val version: Int,
        val name: String,
        val priority: UpdatePriority,
        val notesFa: String,
        val notesEn: String,
    ) : FeedEntry

    /**
     * A newer [component] (the Xray core, OpenVPN) than the one in TunnelLock
     * [appVersion], the newest version when this was written: a new build is needed.
     */
    data class Upstream(val component: String, val current: String, val latest: String, val appVersion: Int) : FeedEntry
}

/**
 * The update feed: comments that the build on GitHub posts on the repository's
 * updates issue, each carrying one entry as JSON in an HTML comment:
 *
 *     <!-- tunnellock-update:{"kind":"release","id":"...","version":80,"name":"0.1.80",
 *          "priority":"important","fa":"...","en":"..."} -->
 *     <!-- tunnellock-update:{"kind":"upstream","component":"Xray core","current":"v26.10.21",
 *          "latest":"v26.11.2","appVersion":80} -->
 *
 * Only the text is used: links are never taken from the feed.
 */
object UpdateFeed {
    const val MARKER = "tunnellock-update:"
    private const val MAX_NOTES = 2_000
    private val ENTRY = Regex("""<!--\s*tunnellock-update:(\{.*?\})\s*-->""", RegexOption.DOT_MATCHES_ALL)

    fun parse(commentBody: String): FeedEntry? {
        val json = ENTRY.find(commentBody)?.groupValues?.get(1) ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return when (o.optString("kind")) {
            "release" -> {
                val version = o.optInt("version", -1)
                val priority = UpdatePriority.parse(o.optString("priority")) ?: return null
                if (version < 0) return null
                FeedEntry.Release(
                    id = o.optString("id"),
                    version = version,
                    name = o.optString("name").ifEmpty { version.toString() },
                    priority = priority,
                    notesFa = o.optString("fa").take(MAX_NOTES),
                    notesEn = o.optString("en").take(MAX_NOTES),
                )
            }
            "upstream" -> {
                val component = o.optString("component").ifEmpty { return null }
                FeedEntry.Upstream(component, o.optString("current"), o.optString("latest"), o.optInt("appVersion", -1))
            }
            else -> null
        }
    }

    /** The entry as the JSON of its comment marker (to keep the last result on the phone). */
    fun encode(entry: FeedEntry): String = when (entry) {
        is FeedEntry.Release -> JSONObject()
            .put("kind", "release").put("id", entry.id).put("version", entry.version).put("name", entry.name)
            .put("priority", entry.priority.name.lowercase()).put("fa", entry.notesFa).put("en", entry.notesEn)
        is FeedEntry.Upstream -> JSONObject()
            .put("kind", "upstream").put("component", entry.component).put("current", entry.current)
            .put("latest", entry.latest).put("appVersion", entry.appVersion)
    }.toString()

    fun decode(json: String): FeedEntry? = parse("<!-- $MARKER$json -->")

    /** Who may write the feed: the repository's own build. Comments by anyone else are ignored. */
    const val AUTHOR = "github-actions[bot]"

    /** The entries in one page of issue comments (GitHub's JSON), from the build's comments only. */
    fun entriesFromPage(json: String): List<FeedEntry> {
        val comments = JSONArray(json)
        return (0 until comments.length()).mapNotNull { i ->
            val comment = comments.optJSONObject(i) ?: return@mapNotNull null
            if (comment.optJSONObject("user")?.optString("login") != AUTHOR) return@mapNotNull null
            parse(comment.optString("body"))
        }
    }

    /**
     * Reads the feed from issue [issue] of [repo]: the first page and the newest two,
     * which hold the entries that matter even after hundreds of releases. Blocking.
     */
    fun fetch(token: String, repo: String, issue: Int): List<FeedEntry> {
        val base = "https://api.github.com/repos/$repo/issues/$issue/comments?per_page=100"
        val (first, link) = get(base, token)
        val last = link?.let { LAST_PAGE.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
        val pages = listOf(1, last - 1, last).filter { it in 1..last }.distinct()
        return pages.flatMap { page -> entriesFromPage(if (page == 1) first else get("$base&page=$page", token).first) }
    }

    private val LAST_PAGE = Regex("""[?&]page=(\d+)>;\s*rel="last"""")

    private fun get(url: String, token: String): Pair<String, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "TunnelLock")
            val code = connection.responseCode
            if (code != 200) throw IOException("GitHub answered HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() } to connection.getHeaderField("Link")
        } finally {
            connection.disconnect()
        }
    }

    /** What an installed TunnelLock needs to know. */
    data class Status(
        /** Versions newer than the installed one, oldest first. */
        val pending: List<FeedEntry.Release>,
        /** Newer components that even the newest TunnelLock does not have yet. */
        val upstream: List<FeedEntry.Upstream>,
    ) {
        val latest: FeedEntry.Release? get() = pending.lastOrNull()

        /** The highest priority among the versions skipped so far: an urgent fix stays urgent. */
        val priority: UpdatePriority? get() = pending.maxOfOrNull { it.priority }
    }

    fun status(entries: List<FeedEntry>, installedVersion: Int): Status {
        val pending = entries.filterIsInstance<FeedEntry.Release>()
            .filter { it.version > installedVersion }
            .groupBy { it.version }.map { (_, same) -> same.last() }
            .sortedBy { it.version }
        val upstream = entries.filterIsInstance<FeedEntry.Upstream>()
            .filter { installedVersion <= it.appVersion }
            .groupBy { it.component }.map { (_, same) -> same.last() }
        return Status(pending, upstream)
    }
}
