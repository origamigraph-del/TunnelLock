package app.tunnellock.strict

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.UserHandle
import app.tunnellock.data.AppLog

/** An app installed in the personal profile. */
data class PhoneApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val system: Boolean,
    val inSecureSpace: Boolean,
)

/** Personal-profile side of adding apps: list the phone's apps and hand the picked ones to the secure space. */
object PhoneApps {

    fun list(context: Context): List<PhoneApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val inSecureSpace = secureSpacePackages(context)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                PhoneApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    info = info,
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    inSecureSpace = info.packageName in inSecureSpace,
                )
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    /** Apps with a launcher icon in the secure space, as seen from the personal profile. */
    private fun secureSpacePackages(context: Context): Set<String> {
        val profile = context.getSystemService(CrossProfileApps::class.java).targetUserProfiles.firstOrNull()
            ?: return emptySet()
        return runCatching {
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(null, profile)
                .map { it.componentName.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    sealed interface SendResult {
        /** Forwarded into the secure space by Android. */
        data object Sent : SendResult

        /** No forwarding route yet: handed over through the clipboard and the secure space was opened. */
        data object SentViaClipboard : SendResult

        data class Failed(val reason: String) : SendResult
    }

    /**
     * Hands the picked apps to TunnelLock in the secure space. The normal route is
     * the cross-profile forwarder (set up by [Policy.ensurePolicies], targeted
     * explicitly the way Shelter does). If the secure space has not set that up yet
     * (it happens on its next start), the list goes over the shared clipboard and the
     * secure-space app is opened, which also applies the rules for next time.
     */
    fun send(context: Context, space: UserHandle, apps: List<PhoneApp>): SendResult {
        val targets = apps.map { app ->
            CloneTarget(
                packageName = app.packageName,
                label = app.label,
                paths = listOf(app.info.sourceDir) + app.info.splitSourceDirs.orEmpty(),
                system = app.system,
            )
        }
        try {
            if (SecureSpace.forward(context, CloneActivity.handOverIntent(targets))) {
                AppLog.i(TAG, "handed ${targets.size} apps to the secure space")
                return SendResult.Sent
            }
            AppLog.w(TAG, "no forwarding route to the secure space yet, using the clipboard")
        } catch (e: Exception) {
            AppLog.w(TAG, "forwarding failed, trying the clipboard", e)
        }
        return try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(CloneActivity.CLIPBOARD_LABEL, CloneActivity.encode(targets)))
            SecureSpace.open(context, space)
            AppLog.i(TAG, "handed ${targets.size} apps over through the clipboard")
            SendResult.SentViaClipboard
        } catch (e: Exception) {
            AppLog.w(TAG, "hand-over failed", e)
            SendResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Inside the secure space: takes a hand-over left on the clipboard by [send].
     * Returns the apps, or an empty list if the clipboard holds something else.
     */
    fun takeFromClipboard(context: Context): List<CloneTarget> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard.primaryClipDescription?.label != CloneActivity.CLIPBOARD_LABEL) return emptyList()
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return emptyList()
        val targets = CloneActivity.decode(text)
        if (targets.isNotEmpty()) {
            runCatching { clipboard.clearPrimaryClip() }
            AppLog.i(TAG, "took ${targets.size} apps from the clipboard hand-over")
        }
        return targets
    }

    private const val TAG = "Clone"
}
