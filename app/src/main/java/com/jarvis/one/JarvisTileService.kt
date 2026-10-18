package com.jarvis.one

import android.content.Intent
import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class JarvisTileService : TileService() {
    override fun onClick() {
        super.onClick()
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
        if (JarvisAccessibilityService.instance != null) {
            JarvisAccessibilityService.openPanel(this)
        } else {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PANEL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(this, 83, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pending)
            } else {
                startActivity(intent)
            }
        }
    }
}
