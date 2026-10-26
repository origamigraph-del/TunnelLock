package app.tunnellock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tunnellock.strict.Policy
import app.tunnellock.ui.theme.TunnelLockTheme

/**
 * Two faces of the same app: in the personal profile it only creates/opens the
 * secure space; inside the secure space (where it is the profile owner) it is
 * the real VPN client.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val inSecureSpace = Policy.isProfileOwner(this)
        // Profiles created by older versions get the current rules too.
        if (inSecureSpace) Policy.ensurePolicies(this)
        setContent {
            TunnelLockTheme {
                if (inSecureSpace) MainScreen() else SetupScreen()
            }
        }
    }
}
