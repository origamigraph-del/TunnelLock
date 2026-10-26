package app.tunnellock.tile

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import app.tunnellock.R
import app.tunnellock.data.AppLog
import app.tunnellock.strict.Policy
import app.tunnellock.ui.MainActivity
import app.tunnellock.vpn.TunnelState
import app.tunnellock.vpn.TunnelVpnService

/**
 * Inside the secure space: connects or disconnects, then closes. Reached from
 * the personal tile through the cross-profile forwarder. Disabled in the
 * manifest and enabled only inside the secure space, like CloneActivity.
 */
class ToggleActivity : Activity() {

    companion object {
        const val ACTION_TOGGLE = "app.tunnellock.action.TOGGLE"
        private const val TAG = "Tile"

        fun intent(): Intent = Intent(ACTION_TOGGLE).addCategory(Intent.CATEGORY_DEFAULT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Policy.isProfileOwner(this)) toggle()
        finish()
    }

    private fun toggle() {
        val connect = !TunnelState.status.value.phase.isActive
        if (connect && VpnService.prepare(this) != null) {
            // No VPN permission yet: the main screen asks for it.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        AppLog.i(TAG, if (connect) "connect from the quick tile" else "disconnect from the quick tile")
        TunnelVpnService.send(this, if (connect) TunnelVpnService.ACTION_CONNECT else TunnelVpnService.ACTION_DISCONNECT)
        Toast.makeText(this, if (connect) R.string.tile_connecting else R.string.tile_disconnected, Toast.LENGTH_SHORT).show()
    }
}
