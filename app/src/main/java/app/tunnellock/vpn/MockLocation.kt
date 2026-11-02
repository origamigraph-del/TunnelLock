package app.tunnellock.vpn

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import app.tunnellock.core.geo.Coordinates
import app.tunnellock.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/** Whether the fake location is running, so the screen can explain what to do. */
enum class MockStatus {
    /** Turned off. */
    OFF,

    /** A location is being fed to the apps. */
    ACTIVE,

    /**
     * Turned on, but Android does not let TunnelLock mock the location yet: the user
     * must pick it as the mock-location app in Developer Options.
     */
    NEEDS_DEVELOPER_OPTION,

    /** The protected apps are given no location at all (the real one is withheld). */
    BLOCKED,
}

object MockLocationState {
    val status = MutableStateFlow(MockStatus.OFF)
}

/**
 * An optional fake GPS location for the whole secure space: while on, the chosen
 * coordinates are fed to Android's location providers so the protected apps read
 * that place instead of the real one. Android allows one mock-location app for the
 * whole device, chosen by the user in Developer Options, so this is one location for
 * every protected app, not a separate one per app.
 *
 * This is about the device's GPS/location, which is separate from the exit IP: where
 * an app's traffic leaves is set by its server (see per-app servers).
 */
class MockLocation(private val context: Context) {

    private companion object {
        const val TAG = "Location"
        const val UPDATE_MS = 1_000L
    }

    private val manager = context.getSystemService(LocationManager::class.java)

    /**
     * Every provider the apps might read. "fused" matters most: Google Play Services feeds
     * it from Wi-Fi and cell, so without mocking it the real position leaks even while GPS
     * is faked. Each provider is handled on its own, so one it will not accept (fused throws
     * on some phones) does not stop the rest.
     */
    private val providers: List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }
    private val lock = Any()
    private var target: Coordinates? = null
    private var worker: Thread? = null
    private val added = mutableSetOf<String>()

    val status: StateFlow<MockStatus> get() = MockLocationState.status

    /** Feeds [coordinates], starting if needed; or stops when it is null. */
    fun apply(coordinates: Coordinates?) {
        synchronized(lock) {
            target = coordinates
            if (coordinates == null) {
                stopLocked()
            } else if (worker == null) {
                worker = thread(name = "mock-location", isDaemon = true) { loop() }
            }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        target = null
        worker = null // the loop sees target == null and exits
        added.forEach { provider -> runCatching { manager.removeTestProvider(provider) } }
        added.clear()
        MockLocationState.status.value = MockStatus.OFF
    }

    private fun loop() {
        var reported = false
        while (true) {
            val here = synchronized(lock) { if (worker == Thread.currentThread()) target else null } ?: return
            val ok = providers.any { push(it, here) }
            val status = if (ok) MockStatus.ACTIVE else MockStatus.NEEDS_DEVELOPER_OPTION
            MockLocationState.status.value = status
            if (!reported) {
                reported = true
                if (ok) {
                    AppLog.i(TAG, "fake location on: ${here.format()}")
                } else {
                    AppLog.w(TAG, "fake location is on but TunnelLock is not the mock-location app (Developer Options)")
                }
            }
            runCatching { Thread.sleep(UPDATE_MS) }.onFailure { return }
        }
    }

    /** Registers [provider] once and sets the location; false means Android refused (not the mock app). */
    private fun push(provider: String, at: Coordinates): Boolean = runCatching {
        if (provider !in added) {
            runCatching { manager.removeTestProvider(provider) }
            addProvider(provider)
            manager.setTestProviderEnabled(provider, true)
            added += provider
        }
        manager.setTestProviderLocation(provider, location(provider, at))
        true
    }.getOrElse {
        added.remove(provider)
        false
    }

    @Suppress("DEPRECATION")
    private fun addProvider(provider: String) {
        manager.addTestProvider(
            provider,
            false, false, false, false, true, true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE,
        )
    }

    private fun location(provider: String, at: Coordinates) = Location(provider).apply {
        latitude = at.latitude
        longitude = at.longitude
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        altitude = 0.0
        if (Build.VERSION.SDK_INT >= 26) {
            verticalAccuracyMeters = 5f
            bearingAccuracyDegrees = 5f
            speedAccuracyMetersPerSecond = 5f
        }
    }
}
