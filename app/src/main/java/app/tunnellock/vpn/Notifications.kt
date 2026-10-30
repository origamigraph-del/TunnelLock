package app.tunnellock.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.tunnellock.R
import app.tunnellock.ui.MainActivity

object Notifications {
    private const val CHANNEL = "tunnel"
    private const val UPDATES = "updates"
    private const val UPDATES_URGENT = "updates_urgent"
    const val ID = 1
    private const val UPDATE_ID = 3

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(UPDATES, context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(UPDATES_URGENT, context.getString(R.string.notification_channel_updates_urgent), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** A new version (or anything else that needs one); opens TunnelLock, which shows the details. */
    fun showUpdate(context: Context, title: String, text: String, urgent: Boolean) {
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, if (urgent) UPDATES_URGENT else UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(UPDATE_ID, notification) }
    }

    fun build(context: Context, status: TunnelStatus): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val active = status.phase.isActive
        val toggle = PendingIntent.getForegroundService(
            context,
            1,
            Intent(context, TunnelVpnService::class.java)
                .setAction(if (active) TunnelVpnService.ACTION_DISCONNECT else TunnelVpnService.ACTION_CONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(status.phase.title))
            .setContentText(status.serverName ?: context.getString(status.phase.description))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0,
                context.getString(if (active) R.string.action_disconnect else R.string.action_connect),
                toggle,
            )
            .build()
    }

    fun update(context: Context, status: TunnelStatus) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(ID, build(context, status))
        }
    }
}
