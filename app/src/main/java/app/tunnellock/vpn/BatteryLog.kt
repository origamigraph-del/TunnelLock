package app.tunnellock.vpn

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import app.tunnellock.data.AppLog
import java.io.File
import java.util.Locale

/**
 * What TunnelLock costs in battery, written to the app log every 15 minutes and
 * whenever the screen, charger, power saving or idle state changes, so a day's
 * log shows where energy goes. Android does not tell an app its own battery
 * use, so this logs what drives it: CPU time (TunnelLock and OpenVPN), data
 * through the tunnel and health checks, next to the battery level and current.
 */
class BatteryLog(private val context: Context) {

    private class Sample(
        val time: Long,
        val level: Int,
        val appCpuMs: Long,
        val openVpnCpuMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    private var last: Sample? = null
    private val clockTicks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

    @Synchronized
    fun sample(reason: String, phase: Phase, healthChecks: Int) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.let { it * 100 / scale } ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        // Microamperes on most phones (the sign differs between makers).
        val current = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE && it != 0 }
            ?.let { "${it / 1000} mA" } ?: "current n/a"
        val power = context.getSystemService(PowerManager::class.java)
        val uid = android.os.Process.myUid()
        val now = Sample(
            time = SystemClock.elapsedRealtime(),
            level = level,
            appCpuMs = android.os.Process.getElapsedCpuTime(),
            openVpnCpuMs = openVpnCpuMs(),
            rxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0),
            txBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
        )
        val before = last
        last = now
        val since = before?.let { " in ${(now.time - it.time) / 60_000} min" }.orEmpty()
        val levelChange = before?.takeIf { it.level >= 0 && level >= 0 }?.let { " (${signed(level - it.level)}%$since)" }.orEmpty()
        val cpu = before?.let { "CPU +${seconds(now.appCpuMs - it.appCpuMs)}, OpenVPN +${seconds(now.openVpnCpuMs - it.openVpnCpuMs)}" }
            ?: "CPU ${seconds(now.appCpuMs)} so far"
        val data = before?.let { "tunnel ↓${megabytes(now.rxBytes - it.rxBytes)} ↑${megabytes(now.txBytes - it.txBytes)}" }
            ?: "tunnel ↓${megabytes(now.rxBytes)} ↑${megabytes(now.txBytes)} so far"
        AppLog.i(
            TAG,
            "$reason: $level%$levelChange, ${if (charging) "charging" else "on battery"}, $current, ${temperature}°C | " +
                "screen ${if (power.isInteractive) "on" else "off"}, idle ${yesNo(power.isDeviceIdleMode)}, " +
                "saver ${yesNo(power.isPowerSaveMode)} | $cpu | $data | $healthChecks checks | $phase | ${network()}",
        )
    }

    /**
     * TunnelLock's way out and the VPNs it can see (its own, or one in the personal profile
     * on phones that show it): to understand clashes with another VPN.
     */
    @Suppress("DEPRECATION")
    private fun network(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val out = when {
            caps == null -> "no network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "via a VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
        val vpns = cm.allNetworks.count { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        "net $out, VPNs seen $vpns"
    }.getOrDefault("net ?")

    /** CPU time of the OpenVPN process (a child of ours; /proc shows only our own processes). */
    private fun openVpnCpuMs(): Long {
        val me = android.os.Process.myPid()
        return File("/proc").listFiles { f -> f.name.all { it.isDigit() } }.orEmpty().sumOf { dir ->
            runCatching {
                val stat = File(dir, "stat").readText()
                if (!stat.contains("libovpnexec")) return@runCatching 0L
                // Fields after "(name) ": state ppid ... utime(12th) stime(13th)
                val fields = stat.substringAfterLast(')').trim().split(' ')
                if (fields[1].toInt() != me) return@runCatching 0L
                (fields[11].toLong() + fields[12].toLong()) * 1000 / clockTicks
            }.getOrDefault(0L)
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms.coerceAtLeast(0) / 1000.0)

    private fun megabytes(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes.coerceAtLeast(0) / 1_048_576.0)

    private fun signed(n: Int) = if (n > 0) "+$n" else "$n"

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private companion object {
        const val TAG = "Battery"
    }
}
