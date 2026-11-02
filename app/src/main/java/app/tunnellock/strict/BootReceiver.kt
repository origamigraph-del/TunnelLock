package app.tunnellock.strict

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.geo.CoordinateParser
import app.tunnellock.vpn.MockLocationService

/**
 * Right after the phone (or the secure space) starts nothing is in use yet, so
 * the protected apps stay closed until the VPN connects. The always-on VPN
 * starts the tunnel itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = (context.applicationContext as TunnelLockApp).store
        if (store.pauseWhenDisconnected.value) AppGate.closeIfDisconnected(context)
        // Keep faking the location across reboots (the personal profile, where it is set).
        if (CoordinateParser.parse(store.mockLocation.value) != null) {
            runCatching { MockLocationService.start(context) }
        }
    }
}
