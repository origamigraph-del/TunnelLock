package app.tunnellock.data

import android.content.Context
import android.os.Build
import app.tunnellock.BuildConfig
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.report.LogUploader
import app.tunnellock.strict.Policy
import app.tunnellock.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends this app's whole log (a full day fits) to the developer: comments on
 * the repo's log issue, the newest lines readable and everything in compressed parts.
 */
object LogSender {
    val available: Boolean get() = BuildConfig.LOG_TOKEN.isNotEmpty()

    suspend fun send(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val inSecureSpace = Policy.isProfileOwner(context)
            val details = buildList {
                add("TunnelLock ${BuildConfig.VERSION_NAME}, ${if (inSecureSpace) "secure space" else "personal side"} (user ${android.os.Process.myUid() / 100_000})")
                add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                if (inSecureSpace) {
                    val status = TunnelState.status.value
                    add("phase ${status.phase}, apps paused ${status.appsPaused}, lockdown ${Policy.isLockdownActive(context)}")
                    add("protected apps: ${Policy.protectedPackages(context).size}, always openable: ${Policy.neverPausePackages(context).size}")
                    val store = (context.applicationContext as TunnelLockApp).store
                    add(
                        "servers ${store.servers.value.size}, subscriptions ${store.subscriptions.value.size}, " +
                            "failover ${store.failover.value}, pause ${store.pauseWhenDisconnected.value}, auto-connect ${store.autoConnect.value}",
                    )
                }
            }
            LogUploader.postLog(BuildConfig.LOG_TOKEN, BuildConfig.LOG_REPO, BuildConfig.LOG_ISSUE, "TunnelLock log", details, AppLog.fullText())
        }.onSuccess { AppLog.i("LogSender", "log sent") }
            .onFailure { AppLog.w("LogSender", "sending the log failed", it) }
    }
}
