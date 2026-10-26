package app.tunnellock.strict

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.tunnellock.ui.MainActivity
import app.tunnellock.vpn.TunnelVpnService

/** Launched by the system inside the secure space as soon as it has been created. */
class ProvisioningSuccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Policy.finalizeProfile(this)
        runCatching { TunnelVpnService.send(this, TunnelVpnService.ACTION_GUARD) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        setResult(RESULT_OK)
        finish()
    }
}
