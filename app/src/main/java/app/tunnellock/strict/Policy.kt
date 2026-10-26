package app.tunnellock.strict

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import app.tunnellock.R
import app.tunnellock.tile.ToggleActivity
import app.tunnellock.tile.ToggleTileService

/**
 * Everything that needs TunnelLock to be the owner of the secure space (a work profile).
 * The calls are no-ops when the app runs anywhere else.
 */
object Policy {
    private const val TAG = "Policy"

    fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun isProfileOwner(context: Context): Boolean = dpm(context).isProfileOwnerApp(context.packageName)

    fun canCreateProfile(context: Context): Boolean =
        dpm(context).isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)

    fun provisioningIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin(context))

    /** Runs inside the new profile right after it is created. Safe to call again. */
    fun finalizeProfile(context: Context) {
        val dpm = dpm(context)
        val admin = admin(context)
        runCatching { dpm.setProfileName(admin, context.getString(R.string.profile_name)) }
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
        ensurePolicies(context)
        runCatching { dpm.setProfileEnabled(admin) }
    }

    /**
     * Everything the secure space needs, re-applied on every start so profiles
     * created by older versions get new rules too. Idempotent.
     */
    fun ensurePolicies(context: Context) {
        if (!isProfileOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        // Work profiles forbid installing apps by default; copying apps in needs it.
        listOf(
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
        ).forEach { restriction -> runCatching { dpm.clearUserRestriction(admin, restriction) } }
        // Let the personal copy of TunnelLock hand over the apps the user picked,
        // and connect or disconnect from its Quick Settings tile.
        runCatching {
            dpm.clearCrossProfileIntentFilters(admin)
            listOf(CloneActivity.ACTION_CLONE_APPS, ToggleActivity.ACTION_TOGGLE).forEach { action ->
                dpm.addCrossProfileIntentFilter(
                    admin,
                    IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                    DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
                )
            }
        }.onFailure { Log.e(TAG, "cross-profile filter failed", it) }
        // Disabled in the manifest so the personal copy never answers these intents itself.
        val pm = context.packageManager
        listOf(CloneActivity::class.java, ToggleActivity::class.java).forEach { activity ->
            pm.setComponentEnabledSetting(
                ComponentName(context, activity),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        // The tile belongs to the personal side.
        pm.setComponentEnabledSetting(
            ComponentName(context, ToggleTileService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (!isLockdownActive(context)) enforceLockdown(context)
        // Older versions paused the leak-test tool too; let it run again.
        runCatching { dpm.setPackagesSuspended(admin, arrayOf(PROBE_PACKAGE), false) }
    }

    /**
     * Always-on VPN with lockdown: Android blocks every app in the secure space
     * unless its traffic goes through TunnelLock's VPN, even if TunnelLock crashes.
     */
    fun enforceLockdown(context: Context): Boolean = try {
        dpm(context).setAlwaysOnVpnPackage(admin(context), context.packageName, true)
        true
    } catch (e: Exception) {
        Log.e(TAG, "setAlwaysOnVpnPackage failed", e)
        false
    }

    fun isLockdownActive(context: Context): Boolean {
        if (!isProfileOwner(context)) return false
        val dpm = dpm(context)
        val admin = admin(context)
        val pkg = runCatching { dpm.getAlwaysOnVpnPackage(admin) }.getOrNull()
        if (pkg != context.packageName) return false
        return if (Build.VERSION.SDK_INT >= 29) {
            runCatching { dpm.isAlwaysOnVpnLockdownEnabled(admin) }.getOrDefault(false)
        } else {
            true
        }
    }

    /**
     * The protected apps: user apps installed in the secure space (except
     * TunnelLock itself) plus the system apps the user added.
     */
    fun protectedApps(context: Context): List<ApplicationInfo> {
        val addedSystem = addedSystemApps(context)
        return context.packageManager.getInstalledApplications(0).filter {
            it.packageName != context.packageName &&
                (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.packageName in addedSystem)
        }
    }

    fun protectedPackages(context: Context): List<String> = protectedApps(context).map { it.packageName }

    /** System apps cannot be installed from an APK; the profile owner switches them on instead. */
    fun enableSystemApp(context: Context, pkg: String) {
        dpm(context).enableSystemApp(admin(context), pkg)
        runCatching { dpm(context).setApplicationHidden(admin(context), pkg, false) }
        prefs(context).edit().putStringSet(KEY_SYSTEM_APPS, addedSystemApps(context) + pkg).apply()
    }

    fun removeSystemApp(context: Context, pkg: String) {
        runCatching { dpm(context).setApplicationHidden(admin(context), pkg, true) }
        prefs(context).edit().putStringSet(KEY_SYSTEM_APPS, addedSystemApps(context) - pkg).apply()
    }

    fun isAddedSystemApp(context: Context, pkg: String) = pkg in addedSystemApps(context)

    private fun addedSystemApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SYSTEM_APPS, emptySet()).orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences("tunnellock", Context.MODE_PRIVATE)

    private const val KEY_SYSTEM_APPS = "addedSystemApps"
    private const val KEY_NEVER_PAUSE = "neverPause"

    /** Apps the user wants to open even while disconnected. Their internet is still cut. */
    fun neverPausePackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_NEVER_PAUSE, emptySet()).orEmpty()

    fun setNeverPause(context: Context, pkg: String, value: Boolean) {
        val current = neverPausePackages(context)
        prefs(context).edit().putStringSet(KEY_NEVER_PAUSE, if (value) current + pkg else current - pkg).apply()
    }

    /** Forgets the choice for apps that are no longer in the secure space. */
    fun pruneNeverPause(context: Context, installed: Collection<String>) {
        val current = neverPausePackages(context)
        val kept = current.filter { it in installed }.toSet()
        if (kept.size != current.size) prefs(context).edit().putStringSet(KEY_NEVER_PAUSE, kept).apply()
    }

    fun setSuspended(context: Context, packages: List<String>, suspended: Boolean) {
        // The leak-test tool keeps running (and logging) while disconnected, and so do the apps
        // the user chose to keep openable; their traffic is still blocked.
        val targets = if (suspended) packages - PROBE_PACKAGE - neverPausePackages(context) else packages
        if (targets.isEmpty() || !isProfileOwner(context)) return
        runCatching { dpm(context).setPackagesSuspended(admin(context), targets.toTypedArray(), suspended) }
            .onFailure { Log.e(TAG, "setPackagesSuspended failed", it) }
    }

    private const val PROBE_PACKAGE = "app.tunnellock.probe"

    fun setAllSuspended(context: Context, suspended: Boolean) {
        if (!isProfileOwner(context)) return
        setSuspended(context, protectedPackages(context), suspended)
    }

    fun isSuspended(context: Context, pkg: String): Boolean =
        runCatching { dpm(context).isPackageSuspended(admin(context), pkg) }.getOrDefault(false)
}
