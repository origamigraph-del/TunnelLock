package app.tunnellock.data

import android.content.Context
import app.tunnellock.BuildConfig
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.update.FeedEntry
import app.tunnellock.core.update.UpdateFeed
import app.tunnellock.core.update.UpdatePriority
import app.tunnellock.vpn.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Looks for newer versions of TunnelLock and says how urgent installing them is.
 * The feed is the repository's updates issue (see [UpdateFeed]); it is read
 * directly, outside the tunnel, like the log upload. Servers in the user's
 * subscriptions that this version cannot read raise the urgency: new protocols
 * need a new version.
 */
object UpdateChecker {
    private const val TAG = "Updates"
    private const val PREFS = "updates"
    const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Info(
        val status: UpdateFeed.Status,
        /** Servers in the subscriptions this version cannot read, by link type. */
        val unreadable: Map<String, Int>,
        val checkedAt: Long,
    ) {
        /** The feed's priority; servers that cannot be read already make a newer version important. */
        val priority: UpdatePriority?
            get() {
                val feed = status.priority ?: return null
                return if (unreadable.isNotEmpty()) maxOf(feed, UpdatePriority.IMPORTANT) else feed
            }

        /** Worth a card on the main screen (an optional update is only in Settings). */
        val worthShowing: Boolean
            get() = (priority ?: UpdatePriority.OPTIONAL) >= UpdatePriority.RECOMMENDED ||
                unreadable.isNotEmpty() || status.upstream.isNotEmpty()
    }

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info
    private val mutex = Mutex()

    /** Needs the log token: the repository is private. */
    val available: Boolean get() = BuildConfig.LOG_TOKEN.isNotEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Notifications about new versions. On unless turned off. */
    fun notificationsOn(context: Context) = prefs(context).getBoolean("notify", true)

    fun setNotificationsOn(context: Context, value: Boolean) = prefs(context).edit().putBoolean("notify", value).apply()

    /** The last result, kept across restarts. */
    fun load(context: Context) {
        if (_info.value != null) return
        val p = prefs(context)
        val checkedAt = p.getLong("checkedAt", 0)
        if (checkedAt == 0L) return
        val entries = runCatching {
            val array = JSONArray(p.getString("entries", "[]"))
            (0 until array.length()).mapNotNull { UpdateFeed.decode(array.getString(it)) }
        }.getOrDefault(emptyList())
        _info.value = Info(UpdateFeed.status(entries, BuildConfig.VERSION_CODE), unreadable(context), checkedAt)
    }

    /** Checks if the last check is older than [maxAgeMs]. */
    suspend fun checkIfDue(context: Context, notify: Boolean, maxAgeMs: Long = CHECK_EVERY_MS) {
        if (!available) return
        if (System.currentTimeMillis() - prefs(context).getLong("checkedAt", 0) < maxAgeMs) return
        check(context, notify)
    }

    suspend fun check(context: Context, notify: Boolean): Result<Info> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!available) throw IllegalStateException("no log token in this build")
                val entries = UpdateFeed.fetch(BuildConfig.LOG_TOKEN, BuildConfig.LOG_REPO, BuildConfig.UPDATE_ISSUE)
                val now = System.currentTimeMillis()
                val info = Info(UpdateFeed.status(entries, BuildConfig.VERSION_CODE), unreadable(context), now)
                val kept = JSONArray()
                (info.status.pending + info.status.upstream).forEach { kept.put(UpdateFeed.encode(it)) }
                prefs(context).edit().putLong("checkedAt", now).putString("entries", kept.toString()).apply()
                _info.value = info
                AppLog.i(TAG, describe(info))
                if (notify && notificationsOn(context)) notifyIfNeeded(context, info)
                info
            }.onFailure { AppLog.w(TAG, "check failed: ${it.message}") }
        }
    }

    private fun describe(info: Info): String {
        val latest = info.status.latest
        return buildString {
            append(if (latest == null) "up to date (${BuildConfig.VERSION_NAME})" else "${info.status.pending.size} newer, latest ${latest.name}, ${info.priority}")
            if (info.unreadable.isNotEmpty()) append(", unreadable servers ${info.unreadable}")
            info.status.upstream.forEach { append(", newer ${it.component} ${it.latest}") }
        }
    }

    private fun unreadable(context: Context): Map<String, Int> {
        val store = (context.applicationContext as? TunnelLockApp)?.store ?: return emptyMap()
        val total = mutableMapOf<String, Int>()
        store.subscriptions.value.forEach { sub -> sub.unreadable.forEach { (kind, n) -> total[kind] = (total[kind] ?: 0) + n } }
        return total
    }

    /** What to tell, and how often to repeat it while it is not installed. */
    private class Notice(val key: String, val title: String, val text: String, val urgent: Boolean, val repeatMs: Long?)

    private fun notice(context: Context, info: Info): Notice? {
        val latest = info.status.latest
        val priority = info.priority
        if (latest != null && priority != null && priority >= UpdatePriority.RECOMMENDED) {
            val text = buildString {
                append(notes(context, latest))
                if (info.unreadable.isNotEmpty()) append("\n").append(unreadableText(context, info.unreadable))
            }
            return Notice(
                key = "release:${latest.version}:$priority",
                title = context.getString(R.string.update_title, latest.name, priorityLabel(context, priority)),
                text = text,
                urgent = priority >= UpdatePriority.IMPORTANT,
                repeatMs = when (priority) {
                    UpdatePriority.CRITICAL -> DAY_MS
                    UpdatePriority.IMPORTANT -> 3 * DAY_MS
                    else -> null
                },
            )
        }
        if (info.unreadable.isNotEmpty()) {
            return Notice(
                key = "unreadable:${info.unreadable.keys.sorted()}",
                title = context.getString(R.string.update_unreadable_title),
                text = unreadableText(context, info.unreadable) + "\n" + context.getString(R.string.update_no_version_yet),
                urgent = false,
                repeatMs = null,
            )
        }
        val upstream = info.status.upstream.firstOrNull() ?: return null
        return Notice(
            key = "upstream:${upstream.component}:${upstream.latest}",
            title = context.getString(R.string.update_upstream_title, upstream.component),
            text = upstreamText(context, upstream),
            urgent = false,
            repeatMs = null,
        )
    }

    private fun notifyIfNeeded(context: Context, info: Info) {
        val notice = notice(context, info) ?: return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val due = notice.key != p.getString("notifiedKey", null) ||
            notice.repeatMs != null && now - p.getLong("notifiedAt", 0) >= notice.repeatMs
        if (!due) return
        Notifications.showUpdate(context, notice.title, notice.text, notice.urgent)
        p.edit().putString("notifiedKey", notice.key).putLong("notifiedAt", now).apply()
        AppLog.i(TAG, "notified: ${notice.key}")
    }

    // ---- texts (also used by the screens) ------------------------------------

    fun priorityLabel(context: Context, priority: UpdatePriority): String = context.getString(
        when (priority) {
            UpdatePriority.CRITICAL -> R.string.update_priority_critical
            UpdatePriority.IMPORTANT -> R.string.update_priority_important
            UpdatePriority.RECOMMENDED -> R.string.update_priority_recommended
            UpdatePriority.OPTIONAL -> R.string.update_priority_optional
        },
    )

    /** The release notes in the app's language (the other one if missing). */
    fun notes(context: Context, release: FeedEntry.Release): String {
        val persian = context.resources.configuration.locales[0].language == "fa"
        val (first, second) = if (persian) release.notesFa to release.notesEn else release.notesEn to release.notesFa
        return first.ifBlank { second }.trim()
    }

    fun unreadableText(context: Context, unreadable: Map<String, Int>): String =
        context.getString(R.string.update_unreadable, unreadable.values.sum(), unreadable.keys.sorted().joinToString(", "))

    fun upstreamText(context: Context, upstream: FeedEntry.Upstream): String =
        context.getString(R.string.update_upstream, upstream.component, upstream.latest, upstream.current)

    /** Where the newest APKs are: the repository's latest release (never a link from the feed). */
    fun downloadUrl(apk: String) = "https://github.com/${BuildConfig.LOG_REPO}/releases/latest/download/$apk"
}
