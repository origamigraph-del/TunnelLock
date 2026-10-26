package app.tunnellock.strict

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import app.tunnellock.TunnelLockApp
import app.tunnellock.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** An app the user picked in the personal profile, to be added to the secure space. */
data class CloneTarget(
    val packageName: String,
    val label: String,
    /** base.apk and split APKs of the personal installation. */
    val paths: List<String>,
    /** System apps are switched on instead of installed. */
    val system: Boolean,
)

enum class CloneStatus { WAITING, WORKING, CONFIRM, DONE, FAILED }

data class CloneItem(val target: CloneTarget, val status: CloneStatus = CloneStatus.WAITING, val message: String? = null)

/**
 * Adds apps to the secure space one at a time. Android asks the user to confirm
 * every install (a non-managed device has no silent way); the confirmation is
 * started by [CloneActivity] while it is on screen.
 */
object CloneQueue {
    private const val TAG = "Clone"
    private const val ACTION_STATUS = "app.tunnellock.action.INSTALL_STATUS"
    private const val EXTRA_PACKAGE = "pkg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<CloneItem>>(emptyList())
    val items: StateFlow<List<CloneItem>> = _items

    /** An install confirmation waiting for [CloneActivity] to show it. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm

    fun takeConfirm(): Intent? = _confirm.getAndUpdate { null }

    @Synchronized
    fun add(targets: List<CloneTarget>) {
        val busy = _items.value.filter { it.status != CloneStatus.DONE && it.status != CloneStatus.FAILED }
        val fresh = targets.filter { t -> busy.none { it.target.packageName == t.packageName } }
        _items.value = _items.value.filter { item -> fresh.none { it.packageName == item.target.packageName } } +
            fresh.map { CloneItem(it) }
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter { it.status != CloneStatus.DONE && it.status != CloneStatus.FAILED }
    }

    /** Starts the next waiting app unless one is in progress. */
    @Synchronized
    fun processNext(context: Context) {
        if (_items.value.any { it.status == CloneStatus.WORKING || it.status == CloneStatus.CONFIRM }) return
        val next = _items.value.firstOrNull { it.status == CloneStatus.WAITING } ?: return
        val target = next.target
        setStatus(target.packageName, CloneStatus.WORKING)
        val app = context.applicationContext
        scope.launch {
            try {
                if (target.system) {
                    Policy.enableSystemApp(app, target.packageName)
                    finished(app, target.packageName, success = true, message = null)
                } else {
                    install(app, target)
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "adding ${target.packageName} failed", e)
                finished(app, target.packageName, success = false, message = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Copies the APK files of the personal installation into an install session and commits it. */
    private fun install(context: Context, target: CloneTarget) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(target.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            target.paths.forEachIndexed { index, path ->
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
                .putExtra(EXTRA_PACKAGE, target.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                status,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        AppLog.i(TAG, "install session for ${target.packageName} committed (${target.paths.size} APK files)")
    }

    internal fun onStatus(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                setStatus(pkg, CloneStatus.CONFIRM)
                _confirm.value = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PackageInstaller.STATUS_SUCCESS -> finished(context, pkg, success = true, message = null)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                AppLog.w(TAG, "install of $pkg failed: $message")
                finished(context, pkg, success = false, message = message)
            }
        }
    }

    private fun finished(context: Context, pkg: String, success: Boolean, message: String?) {
        setStatus(pkg, if (success) CloneStatus.DONE else CloneStatus.FAILED, message)
        if (success) {
            AppLog.i(TAG, "$pkg added to the secure space")
            // A new app is not in use yet; keep it closed while we are not connected.
            val store = (context.applicationContext as TunnelLockApp).store
            if (store.pauseWhenDisconnected.value) AppGate.closeIfDisconnected(context, listOf(pkg))
        }
        processNext(context)
    }

    @Synchronized
    private fun setStatus(pkg: String, status: CloneStatus, message: String? = null) {
        _items.update { list ->
            list.map { if (it.target.packageName == pkg) it.copy(status = status, message = message) else it }
        }
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CloneQueue.onStatus(context, intent)
}
