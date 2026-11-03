package app.tunnellock.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.tunnellock.R
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.geo.CoordinateParser
import app.tunnellock.data.AppLog
import app.tunnellock.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Feeds a user-set fake location to Android's location providers, the same way a working
 * spoofer (e.g. "Fake GPS") does. It runs from the personal profile, where Developer Options
 * can make TunnelLock the mock-location app (a work-profile app cannot be picked there). A
 * test provider replaces the real provider device-wide, so the faked place is what every app
 * reads -- the secure-space apps included -- which is the only no-root way to fake them.
 * While on, the whole phone's location is the faked one.
 */
class MockLocationService : Service() {

    companion object {
        private const val CHANNEL = "mock_location"
        private const val NOTIFICATION_ID = 5

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MockLocationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mock by lazy { MockLocation(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        val store = (application as TunnelLockApp).store
        scope.launch {
            store.mockLocation.collect { value ->
                val coordinates = CoordinateParser.parse(value)
                if (coordinates == null) {
                    mock.stop()
                    stopSelf()
                } else {
                    mock.apply(coordinates)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mock.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.mock_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mock_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { AppLog.w("Location", "mock foreground start failed: ${it.message}") }
    }
}
