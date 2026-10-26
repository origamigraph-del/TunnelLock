package app.tunnellock.tile

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import app.tunnellock.R

/**
 * Quick Settings tile of the personal copy: connects or disconnects the VPN of
 * the secure space. The tunnel runs in the other profile, whose state cannot be
 * read from here, so the tile is a plain button.
 */
class ToggleTileService : TileService() {

    companion object {
        /** Asks Android to put the tile in Quick Settings. [onResult] gets true if it is there now. */
        @RequiresApi(33)
        fun requestAdd(context: Context, onResult: (Boolean) -> Unit) {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, ToggleTileService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_notification),
                context.mainExecutor,
            ) { result ->
                onResult(
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                )
            }
        }
    }

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(R.string.tile_subtitle)
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        val intent = Intent(this, TileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
