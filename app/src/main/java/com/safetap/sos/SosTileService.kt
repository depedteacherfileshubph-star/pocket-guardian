package com.safetap.sos

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Optional visible alternative to the volume buttons: a Quick Settings tile the user adds
 * themselves. Tapping it opens the SOS screen (with the same cancel countdown).
 * Needs real-device testing; on a locked phone Android normally asks to unlock first.
 */
class SosTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Pocket Guardian"
            updateTile()
        }
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // API < 34 branch only
    override fun onClick() {
        val i = Intent(this, SosActivity::class.java)
            .putExtra(SosActivity.EXTRA_SOURCE, "Quick Settings tile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
