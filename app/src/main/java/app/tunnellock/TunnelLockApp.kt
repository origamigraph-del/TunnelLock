package app.tunnellock

import android.app.Application
import app.tunnellock.data.AppLog
import app.tunnellock.data.AppStore
import app.tunnellock.vpn.Notifications

class TunnelLockApp : Application() {
    lateinit var store: AppStore
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // A new process means the previous one ended (force stop, crash, update, or the system).
        AppLog.i("App", "process started")
        store = AppStore(this)
        Notifications.createChannel(this)
    }
}
