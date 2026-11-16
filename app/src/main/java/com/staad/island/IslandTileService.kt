package com.staad.island

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: turn the island on or off without opening the app.
 * The overlay itself lives in the accessibility service, so when it is not
 * running yet the tile opens the app instead of silently doing nothing.
 */
class IslandTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (Prefs.get(this@IslandTileService).getBoolean(Prefs.ENABLED, true)) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            icon = Icon.createWithResource(this@IslandTileService, R.drawable.ic_pill)
            label = "Island"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs.get(this)
        val enabled = !prefs.getBoolean(Prefs.ENABLED, true)
        prefs.edit().putBoolean(Prefs.ENABLED, enabled).apply()
        IslandBus.reloadPrefs()

        if (enabled && !IslandBus.isOverlayRunning) {
            // Nothing can draw until the accessibility service is on; send the
            // user to the setup screen rather than pretending it worked.
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
            return
        }
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
