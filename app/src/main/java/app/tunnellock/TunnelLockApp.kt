package app.tunnellock

import android.app.Application
import app.tunnellock.data.AppStore
import app.tunnellock.vpn.Notifications

class TunnelLockApp : Application() {
    lateinit var store: AppStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        Notifications.createChannel(this)
    }
}
