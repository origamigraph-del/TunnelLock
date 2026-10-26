package app.tunnellock.strict

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.os.UserHandle
import app.tunnellock.ui.MainActivity

/** Personal-profile side: reaching the copy of TunnelLock inside the secure space. */
object SecureSpace {

    fun profile(context: Context): UserHandle? =
        context.getSystemService(CrossProfileApps::class.java).targetUserProfiles.firstOrNull()

    /**
     * Sends [intent] into the secure space through Android's cross-profile forwarder
     * (set up by [Policy.ensurePolicies]), targeted explicitly the way Shelter does.
     * Returns false if the secure space has not set up that route yet; throws if
     * starting it fails.
     */
    fun forward(context: Context, intent: Intent): Boolean {
        val forwarder = context.packageManager.queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo.packageName != context.packageName }
            ?: return false
        intent.component = ComponentName(forwarder.activityInfo.packageName, forwarder.activityInfo.name)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Opens TunnelLock in the secure space; its start also sets up the routes for next time. */
    fun open(context: Context, space: UserHandle) {
        context.getSystemService(CrossProfileApps::class.java)
            .startMainActivity(ComponentName(context, MainActivity::class.java), space)
    }
}
