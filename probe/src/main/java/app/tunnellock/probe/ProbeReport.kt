package app.tunnellock.probe

import android.content.Context
import android.os.Build
import app.tunnellock.core.report.LogUploader

/** Sends the probe log to the developer (a comment on the repo's log issue). */
object ProbeReport {
    val available: Boolean get() = BuildConfig.LOG_TOKEN.isNotEmpty()

    /** Blocking. */
    fun send(context: Context, log: String): Result<String> = runCatching {
        val workProfile = ProbeState.inSecureSpace(context)
        val details = listOf(
            "TunnelProbe ${BuildConfig.VERSION_NAME}, ${if (workProfile) "secure space" else "personal profile"} (user ${android.os.Process.myUid() / 100_000})",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "home country ${ProbeState.homeCountry(context)}, real IP set: ${ProbeState.realIp(context).isNotEmpty()}",
        )
        LogUploader.post(BuildConfig.LOG_TOKEN, BuildConfig.LOG_REPO, BuildConfig.LOG_ISSUE, LogUploader.format("TunnelProbe log", details, log))
    }
}
