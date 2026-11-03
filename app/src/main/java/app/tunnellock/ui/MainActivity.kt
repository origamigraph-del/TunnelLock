package app.tunnellock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tunnellock.TunnelLockApp
import app.tunnellock.core.geo.CoordinateParser
import app.tunnellock.strict.CloneActivity
import app.tunnellock.strict.OpenLinkActivity
import app.tunnellock.strict.PhoneApps
import app.tunnellock.strict.Policy
import app.tunnellock.vpn.MockLocationService
import app.tunnellock.ui.theme.TunnelLockTheme

/**
 * Two faces of the same app: in the personal profile it creates/opens the
 * secure space and picks the apps to protect; inside the secure space (where it
 * is the profile owner) it is the real VPN client.
 */
class MainActivity : ComponentActivity() {
    private var inSecureSpace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        inSecureSpace = Policy.isProfileOwner(this)
        // Profiles created by older versions get the current rules too.
        if (inSecureSpace) Policy.ensurePolicies(this)
        // Resume the fake location if one is set. It only reaches apps in the same Android
        // user, so to fake the secure-space apps it must run in the secure space itself -- the
        // app starts it here, in the foreground, where Android allows a foreground service.
        if (CoordinateParser.parse((application as TunnelLockApp).store.mockLocation.value) != null) {
            runCatching { MockLocationService.start(this) }
        }
        setContent {
            TunnelLockTheme {
                if (inSecureSpace) MainScreen() else SetupScreen()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard can only be read while focused.
        if (hasFocus && inSecureSpace) {
            val targets = PhoneApps.takeFromClipboard(this)
            if (targets.isNotEmpty()) startActivity(CloneActivity.localIntent(this, targets))
            // A link handed over from the personal side (when the cross-profile forwarder is not reachable).
            PhoneApps.takeLinkFromClipboard(this)?.let { url ->
                runCatching { startActivity(OpenLinkActivity.intent(url).setClass(this, OpenLinkActivity::class.java)) }
            }
        }
    }
}
