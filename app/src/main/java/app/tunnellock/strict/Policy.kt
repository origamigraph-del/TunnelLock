package app.tunnellock.strict

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.util.Log
import app.tunnellock.R

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
        // Work profiles forbid installing apps by default; copying apps in needs it.
        listOf(
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
        ).forEach { restriction -> runCatching { dpm.clearUserRestriction(admin, restriction) } }
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
        enforceLockdown(context)
        runCatching { dpm.setProfileEnabled(admin) }
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

    /** User apps installed in the secure space, except TunnelLock itself. These are the protected apps. */
    fun protectedPackages(context: Context): List<String> =
        context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .map { it.packageName }

    fun setSuspended(context: Context, packages: List<String>, suspended: Boolean) {
        if (packages.isEmpty() || !isProfileOwner(context)) return
        runCatching { dpm(context).setPackagesSuspended(admin(context), packages.toTypedArray(), suspended) }
            .onFailure { Log.e(TAG, "setPackagesSuspended failed", it) }
    }

    fun setAllSuspended(context: Context, suspended: Boolean) {
        if (!isProfileOwner(context)) return
        setSuspended(context, protectedPackages(context), suspended)
    }

    fun isSuspended(context: Context, pkg: String): Boolean =
        runCatching { dpm(context).isPackageSuspended(admin(context), pkg) }.getOrDefault(false)
}
