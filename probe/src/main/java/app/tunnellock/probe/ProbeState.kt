package app.tunnellock.probe

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Counters(val online: Int = 0, val blocked: Int = 0, val leaks: Int = 0, val ipChanges: Int = 0, val direct: Int = 0)

data class LastRound(val verdict: Verdict, val summary: String, val time: Long)

/** Results, counters, settings and the log of the probe. Shared by the service and the screen. */
object ProbeState {
    private const val MAX_LINES = 3000
    private const val LEAK_CHANNEL = "leak"
    const val STATUS_CHANNEL = "probe"

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _last = MutableStateFlow<LastRound?>(null)
    val last: StateFlow<LastRound?> = _last

    private val _counters = MutableStateFlow(Counters())
    val counters: StateFlow<Counters> = _counters

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private var baseline: Set<String> = emptySet()
    private var previousVerdict: Verdict? = null
    private var loaded = false

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    // ---- settings ------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences("probe", Context.MODE_PRIVATE)

    fun intervalSeconds(context: Context) = prefs(context).getInt("interval", 3)
    fun setIntervalSeconds(context: Context, seconds: Int) = prefs(context).edit().putInt("interval", seconds).apply()
    fun homeCountry(context: Context) = prefs(context).getString("home", "IR").orEmpty()
    fun setHomeCountry(context: Context, value: String) = prefs(context).edit().putString("home", value.trim().uppercase()).apply()
    fun realIp(context: Context) = prefs(context).getString("realIp", "").orEmpty()
    fun autoSend(context: Context) = prefs(context).getBoolean("autoSend", true)
    fun setAutoSend(context: Context, value: Boolean) = prefs(context).edit().putBoolean("autoSend", value).apply()
    fun setRealIp(context: Context, value: String) = prefs(context).edit().putString("realIp", value.trim()).apply()

    // ---- log -----------------------------------------------------------------

    private fun logFile(context: Context) = File(context.filesDir, "probe.log")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = logFile(context)
        if (file.exists()) _lines.value = runCatching { file.readLines().takeLast(MAX_LINES) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun log(context: Context, message: String) {
        val line = "${time.format(Date())} $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
        runCatching {
            val file = logFile(context)
            file.appendText(line + "\n")
            if (file.length() > 1_000_000) file.writeText(_lines.value.joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun clear(context: Context) {
        _lines.value = emptyList()
        _counters.value = Counters()
        _last.value = null
        baseline = emptySet()
        previousVerdict = null
        runCatching { logFile(context).delete() }
    }

    @Synchronized
    fun resetBaseline(context: Context) {
        baseline = emptySet()
        log(context, "-- exit IP baseline reset")
    }

    fun text(lastLines: Int = 600): String = _lines.value.takeLast(lastLines).joinToString("\n")

    /** The lines of the last test session (from its "probe started" line). */
    fun sessionText(): String {
        val lines = _lines.value
        val start = lines.indexOfLast { it.contains("-- probe started") }.coerceAtLeast(0)
        return lines.drop(start).joinToString("\n")
    }

    private val flushing = AtomicBoolean(false)

    /**
     * Queues [text] for the developer and tries to send it. A test usually ends
     * while the VPN is down (no internet), so whatever fails stays in the outbox
     * and goes out with [flushOutbox] once the internet is back.
     */
    fun sendInBackground(context: Context, text: String) {
        val app = context.applicationContext
        val outbox = File(app.filesDir, "outbox").apply { mkdirs() }
        runCatching { File(outbox, "${System.currentTimeMillis()}.log").writeText(text) }
        flushOutbox(app)
    }

    /** Sends queued logs, oldest first. Cheap to call often: no-op while sending or when empty. */
    fun flushOutbox(context: Context) {
        if (!ProbeReport.available) return
        val app = context.applicationContext
        val pending = File(app.filesDir, "outbox").listFiles()?.sortedBy { it.name }.orEmpty()
        if (pending.isEmpty() || !flushing.compareAndSet(false, true)) return
        Thread {
            try {
                for (file in pending) {
                    val result = ProbeReport.send(app, file.readText())
                    if (result.isSuccess) {
                        file.delete()
                        log(app, "-- log sent to the developer")
                    } else {
                        log(app, "-- log not sent yet (${result.exceptionOrNull()?.message}); will retry when online")
                        break
                    }
                }
            } finally {
                flushing.set(false)
            }
        }.start()
    }

    // ---- one round -----------------------------------------------------------

    @Synchronized
    fun record(context: Context, results: List<CheckResult>) {
        val c = ProbeLogic.classify(results, baseline, homeCountry(context), realIp(context), inSecureSpace(context))
        baseline = c.baseline
        val network = networkLabel(context)
        val details = results.joinToString(" | ") { r ->
            if (r.reached) {
                listOfNotNull(r.name, r.ip ?: r.error, r.country, "${r.ms}ms").joinToString(" ")
            } else {
                "${r.name} ${r.error}"
            }
        }
        val mark = when (c.verdict) {
            Verdict.ONLINE -> "OK     "
            Verdict.BLOCKED -> "BLOCKED"
            Verdict.LEAK -> "LEAK!! "
            Verdict.IP_CHANGED -> "NEW-IP "
            Verdict.DIRECT -> "DIRECT "
        }
        if (previousVerdict != null && previousVerdict != c.verdict) {
            log(context, "== $previousVerdict -> ${c.verdict}")
        }
        previousVerdict = c.verdict
        log(context, "$mark net=$network | $details" + (c.reason?.let { "  <- $it" } ?: ""))
        _counters.update {
            when (c.verdict) {
                Verdict.ONLINE -> it.copy(online = it.online + 1)
                Verdict.BLOCKED -> it.copy(blocked = it.blocked + 1)
                Verdict.LEAK -> it.copy(leaks = it.leaks + 1)
                Verdict.IP_CHANGED -> it.copy(ipChanges = it.ipChanges + 1)
                Verdict.DIRECT -> it.copy(direct = it.direct + 1)
            }
        }
        val summary = when (c.verdict) {
            Verdict.BLOCKED -> network
            else -> results.firstOrNull { it.ip != null }?.let { listOfNotNull(it.ip, it.country).joinToString(" ") } ?: network
        }
        _last.value = LastRound(c.verdict, summary, System.currentTimeMillis())
        if (c.verdict == Verdict.LEAK) notifyLeak(context, c.reason.orEmpty())
    }

    /** What the app's own default network is: VPN, Wi-Fi, cellular, or none (blocked). */
    /** True in the work profile (the secure space), where every exit must be the VPN. */
    fun inSecureSpace(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(UserManager::class.java).isManagedProfile
        } else {
            android.os.Process.myUid() / 100_000 != 0
        }

    private fun networkLabel(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val kinds = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WiFi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Eth")
        }
        return kinds.joinToString("+").ifEmpty { "other" }
    }

    // ---- notifications -------------------------------------------------------

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(LEAK_CHANNEL, context.getString(R.string.channel_leak), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun notifyLeak(context: Context, reason: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, LEAK_CHANNEL)
            .setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(context.getString(R.string.leak_title))
            .setContentText(reason)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(2, notification)
    }
}
