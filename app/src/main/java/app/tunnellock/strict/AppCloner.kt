package app.tunnellock.strict

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.IntentCompat
import app.tunnellock.TunnelLockApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

data class AppItem(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    /** Installed in the secure space (= protected). */
    val installedHere: Boolean,
)

sealed interface InstallEvent {
    data class Installed(val label: String) : InstallEvent
    data class Failed(val label: String, val reason: String) : InstallEvent
}

/**
 * Copies apps from the personal profile into the secure space. Android asks the
 * user to confirm each install; a non-managed device offers no silent way.
 */
object AppCloner {
    private const val ACTION_STATUS = "app.tunnellock.action.INSTALL_STATUS"
    const val EXTRA_LABEL = "label"

    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = _events

    internal fun emit(event: InstallEvent) {
        _events.tryEmit(event)
    }

    /**
     * User apps on the device. Apps that exist only in the personal profile are
     * returned too (MATCH_UNINSTALLED_PACKAGES), with [AppItem.installedHere] = false.
     */
    fun listApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        return pm.getInstalledApplications(flags)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { info -> info.sourceDir?.let { File(it).canRead() } == true }
            .map { info ->
                AppItem(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    info = info,
                    installedHere = info.flags and ApplicationInfo.FLAG_INSTALLED != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Blocking: copies the APK files into an install session and commits it. */
    fun install(context: Context, item: AppItem) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(item.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            val files = listOf(item.info.sourceDir) + item.info.splitSourceDirs.orEmpty()
            files.forEachIndexed { index, path ->
                val file = File(path)
                file.inputStream().use { input ->
                    session.openWrite("part$index.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }
            val status = Intent(context, InstallStatusReceiver::class.java)
                .setAction(ACTION_STATUS)
                .putExtra(EXTRA_LABEL, item.label)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                status,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(AppCloner.EXTRA_LABEL).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val store = (context.applicationContext as TunnelLockApp).store
                if (pkg != null && store.pauseWhenDisconnected.value) AppGate.closeIfDisconnected(context, listOf(pkg))
                AppCloner.emit(InstallEvent.Installed(label))
            }
            else -> AppCloner.emit(
                InstallEvent.Failed(label, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"),
            )
        }
    }
}
