package app.tunnellock.strict

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.tunnellock.R
import app.tunnellock.core.parser.LinkExtractor
import app.tunnellock.data.AppLog

/**
 * The personal side of "open a link in the secure space": it shows up in the share sheet
 * (Share → TunnelLock) so a link from an email opens on the app's secure-space copy
 * instead of the personal one. Takes a shared or viewed link and hands it to the secure
 * space; if TunnelLock there has not set up the route yet, it says to open it once.
 */
class ShareLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = linkFrom(intent)
        if (url == null) {
            Toast.makeText(this, R.string.open_link_no_url, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        open(this, url)
        finish()
    }

    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> LinkExtractor.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        Intent.ACTION_VIEW -> intent.dataString?.let { LinkExtractor.firstUrl(it) ?: it }
        else -> null
    }

    companion object {
        private const val TAG = "OpenLink"

        /** Sends [url] to the secure space; from the secure space it opens straight away. */
        fun open(activity: Activity, url: String) {
            if (Policy.isProfileOwner(activity)) {
                activity.startActivity(OpenLinkActivity.intent(url).setClass(activity, OpenLinkActivity::class.java))
                return
            }
            val sent = runCatching { SecureSpace.forward(activity, OpenLinkActivity.intent(url)) }.getOrDefault(false)
            if (sent) {
                AppLog.i(TAG, "a link was sent to the secure space")
                Toast.makeText(activity, R.string.open_link_sent, Toast.LENGTH_SHORT).show()
            } else {
                AppLog.w(TAG, "no route to the secure space for the link yet")
                Toast.makeText(activity, R.string.open_link_open_space, Toast.LENGTH_LONG).show()
                SecureSpace.profile(activity)?.let { SecureSpace.open(activity, it) }
            }
        }
    }
}
