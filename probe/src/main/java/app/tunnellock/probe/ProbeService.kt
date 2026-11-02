package app.tunnellock.probe

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps probing in the background, so the user can switch networks or servers while it logs. */
class ProbeService : Service() {

    companion object {
        private const val ACTION_STOP = "app.tunnellock.probe.STOP"
        private const val EXTRA_PRECISE = "precise"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ProbeService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProbeService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    @Volatile private var pulse: Pulse? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastBatteryLog = 0L
    private var lastCpuMs = 0L
    private var lastLocationLog = 0L
    @Volatile private var locating = false

    /**
     * An idle listener kept registered while we have permission. getLastKnownLocation returns
     * null until something actually asks a provider for a fix, so without this the log never
     * sees the location the secure-space apps would get. Registering here also mirrors what a
     * real app does (it requests updates), which is exactly what must receive the device-wide
     * fake set from the personal profile.
     */
    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {}
        override fun onLocationChanged(locations: List<android.location.Location>) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The channel must exist before the first notification: the service can be
        // started (or restarted by Android) without the screen ever having been opened.
        ProbeState.createChannels(this)
        showForeground(notification(getString(R.string.status_starting)))
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The emulator test turns precise mode on this way (the service is not exported).
        if (intent?.hasExtra(EXTRA_PRECISE) == true) ProbeState.setPrecise(this, intent.getBooleanExtra(EXTRA_PRECISE, false))
        if (loop == null) {
            // Every probe request must open a new connection.
            System.setProperty("http.keepAlive", "false")
            ProbeState.load(this)
            ProbeState.setRunning(true)
            ProbeState.log(this, "-- probe started (${profileLabel()}, every ${ProbeState.intervalSeconds(this)}s)")
            // The CPU must not sleep between rounds while a test runs.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TunnelProbe:loop")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            loop = scope.launch {
                while (isActive) {
                    val started = System.currentTimeMillis()
                    val results = Checks.runAll()
                    ProbeState.record(this@ProbeService, results)
                    logBatteryIfDue()
                    logLocationIfDue()
                    // Internet works right now: a good moment for logs that could not be sent before.
                    if (results.any { it.reached }) ProbeState.flushOutbox(this@ProbeService)
                    ProbeState.last.value?.let { last ->
                        val text = getString(R.string.status_line, last.verdict.name, last.summary)
                        notify(notification(text))
                    }
                    val wait = ProbeState.intervalSeconds(this@ProbeService) * 1000L - (System.currentTimeMillis() - started)
                    delay(wait.coerceAtLeast(500))
                }
            }
            // Precise mode runs next to the rounds and follows its switch while the test runs.
            scope.launch {
                while (isActive) {
                    if (ProbeState.precise(this@ProbeService)) {
                        val current = Pulse(this@ProbeService).also { pulse = it }
                        current.run { ProbeState.precise(this@ProbeService) }
                        current.finish()
                        pulse = null
                    }
                    delay(1_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pulse?.finish()
        runCatching { getSystemService(android.location.LocationManager::class.java)?.removeUpdates(locationListener) }
        scope.cancel()
        runCatching { wakeLock?.release() }
        ProbeState.setRunning(false)
        ProbeState.log(this, "-- probe stopped")
        // Each finished test goes to the developer on its own.
        if (ProbeReport.available && ProbeState.autoSend(this)) ProbeState.sendInBackground(this, ProbeState.sessionText(this))
        super.onDestroy()
    }

    /**
     * Every 15 minutes: the battery level and the probe's own CPU time, so its
     * share can be told apart from TunnelLock's in a day-long test.
     */
    private fun logBatteryIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (lastBatteryLog != 0L && now - lastBatteryLog < 15 * 60 * 1000L) return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.let { it * 100 / scale } ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cpuMs = android.os.Process.getElapsedCpuTime()
        val cpu = if (lastBatteryLog == 0L) "${cpuMs / 1000} s so far" else "+${(cpuMs - lastCpuMs) / 1000} s in ${(now - lastBatteryLog) / 60_000} min"
        ProbeState.log(this, "-- battery $level%${if (charging) " (charging)" else ""}, probe CPU $cpu")
        lastBatteryLog = now
        lastCpuMs = cpuMs
    }

    /**
     * Every 30 s: the location the apps in the secure space actually read. This shows whether
     * a fake location reaches here, and whether the real position leaks from any provider.
     */
    private fun logLocationIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (lastLocationLog != 0L && now - lastLocationLog < 30_000L) return
        lastLocationLog = now
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ProbeState.log(this, "-- location: no permission (grant it to check what apps see)")
            return
        }
        ensureLocating()
        val manager = getSystemService(android.location.LocationManager::class.java)
        val seen = listOf("gps", "network", "fused", "passive").mapNotNull { provider ->
            val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: return@mapNotNull null
            val ageS = (now - location.elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0) / 1000
            val mock = if (android.os.Build.VERSION.SDK_INT >= 31 && location.isMock) " [mock]" else ""
            "$provider ${"%.5f".format(location.latitude)},${"%.5f".format(location.longitude)}$mock ${ageS}s"
        }
        ProbeState.log(this, "-- location: " + if (seen.isEmpty()) "none yet" else seen.joinToString(" | "))
    }

    /**
     * Start listening to every provider once permission is granted, so a fix actually arrives
     * (otherwise getLastKnownLocation stays null) and so the device-wide fake set from the
     * personal profile is delivered here, in the secure space, exactly as to a real app.
     */
    private fun ensureLocating() {
        if (locating) return
        val manager = getSystemService(android.location.LocationManager::class.java) ?: return
        val providers = buildList {
            add("gps"); add("network"); add("passive")
            if (Build.VERSION.SDK_INT >= 31) add("fused")
        }
        val ok = mutableListOf<String>()
        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 1_000L, 0f, locationListener, android.os.Looper.getMainLooper())
                ok += provider
            }
        }
        if (ok.isNotEmpty()) {
            locating = true
            ProbeState.log(this, "-- location listening on ${ok.joinToString("/")}")
        }
    }

    private fun profileLabel(): String = "user ${android.os.Process.myUid() / 100_000}"

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProbeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ProbeState.STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun notify(notification: Notification) {
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    private fun showForeground(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }
}
