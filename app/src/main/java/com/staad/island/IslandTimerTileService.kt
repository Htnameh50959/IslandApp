package com.staad.island

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: open the island's timer / stopwatch card from the
 * shade, so a timer can be started by a single tap without looking for the
 * island on the camera. When the overlay isn't running there is nothing to
 * draw on, so the tile opens the app instead.
 */
class IslandTimerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (IslandBus.isOverlayRunning) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            icon = Icon.createWithResource(this@IslandTimerTileService, R.drawable.ic_timer)
            label = "Island timer"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (IslandBus.isOverlayRunning) {
            IslandBus.postTimers()
            return
        }
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
