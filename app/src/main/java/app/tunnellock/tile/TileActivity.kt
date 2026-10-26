package app.tunnellock.tile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.tunnellock.R
import app.tunnellock.data.AppLog
import app.tunnellock.strict.SecureSpace
import app.tunnellock.ui.MainActivity

/** Personal side of the tile: hands the toggle to the secure space and closes at once. */
class TileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val space = SecureSpace.profile(this)
        try {
            when {
                // No secure space yet: show the setup screen.
                space == null -> startActivity(Intent(this, MainActivity::class.java))
                SecureSpace.forward(this, ToggleActivity.intent()) -> AppLog.i(TAG, "toggle sent to the secure space")
                else -> {
                    // The secure space sets up the route on its next start; until then the user taps connect there.
                    AppLog.w(TAG, "no route for the toggle yet, opening the secure space")
                    Toast.makeText(this, R.string.tile_open_space, Toast.LENGTH_LONG).show()
                    SecureSpace.open(this, space)
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "tile action failed", e)
            Toast.makeText(this, getString(R.string.tile_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        const val TAG = "Tile"
    }
}
