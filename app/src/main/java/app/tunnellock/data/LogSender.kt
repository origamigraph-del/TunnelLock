package app.tunnellock.data

import android.content.Context
import android.os.Build
import app.tunnellock.BuildConfig
import app.tunnellock.core.report.LogUploader
import app.tunnellock.strict.Policy
import app.tunnellock.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends this app's log to the developer (a comment on the repo's log issue). */
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
                    add("protected apps: ${Policy.protectedPackages(context).size}")
                }
            }
            val body = LogUploader.format("TunnelLock log", details, AppLog.text())
            LogUploader.post(BuildConfig.LOG_TOKEN, BuildConfig.LOG_REPO, BuildConfig.LOG_ISSUE, body)
        }.onSuccess { AppLog.i("LogSender", "log sent") }
            .onFailure { AppLog.w("LogSender", "sending the log failed", it) }
    }
}
