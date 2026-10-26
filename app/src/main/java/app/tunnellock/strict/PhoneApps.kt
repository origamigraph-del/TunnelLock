package app.tunnellock.strict

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
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

    /** Returns false if the secure space did not accept the hand-over (it may need to be opened once first). */
    fun send(context: Context, apps: List<PhoneApp>): Boolean {
        val targets = apps.map { app ->
            CloneTarget(
                packageName = app.packageName,
                label = app.label,
                paths = listOf(app.info.sourceDir) + app.info.splitSourceDirs.orEmpty(),
                system = app.system,
            )
        }
        return try {
            context.startActivity(CloneActivity.handOverIntent(targets).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.i("Clone", "handed ${targets.size} apps to the secure space")
            true
        } catch (e: Exception) {
            AppLog.w("Clone", "hand-over failed", e)
            false
        }
    }
}
