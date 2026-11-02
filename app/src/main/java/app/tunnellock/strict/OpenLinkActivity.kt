package app.tunnellock.strict

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.tunnellock.R
import app.tunnellock.data.AppLog

/**
 * Inside the secure space: opens a link the personal side handed over, so an activation
 * link from an email lands on the app's copy in the secure space (right IP and location)
 * instead of the personal copy. Disabled in the manifest and enabled only inside the
 * secure space, like CloneActivity, so the personal copy never answers this itself.
 */
class OpenLinkActivity : Activity() {

    companion object {
        const val ACTION_OPEN_LINK = "app.tunnellock.action.OPEN_LINK"
        const val EXTRA_URL = "url"
        private const val TAG = "OpenLink"

        fun intent(url: String): Intent =
            Intent(ACTION_OPEN_LINK).addCategory(Intent.CATEGORY_DEFAULT).putExtra(EXTRA_URL, url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (!Policy.isProfileOwner(this) || url.isNullOrBlank()) {
            finish()
            return
        }
        try {
            // Opened without our package set, so the secure space resolves it to the app
            // installed here (or a chooser among the secure-space apps), never our own UI.
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(view)
            AppLog.i(TAG, "opened a link in the secure space: ${redact(url)}")
        } catch (e: Exception) {
            AppLog.w(TAG, "no app in the secure space opened the link: ${e.message}")
            Toast.makeText(this, R.string.open_link_none, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /** A link can carry a one-time token: keep host and path, drop the query, for the log. */
    private fun redact(url: String): String = runCatching {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}${uri.path.orEmpty()}" + if (uri.query != null) "?…" else ""
    }.getOrDefault("(unreadable)")
}
