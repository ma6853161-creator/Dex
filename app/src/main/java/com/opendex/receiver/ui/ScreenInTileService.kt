package com.opendex.receiver.ui

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Screen In" quick-settings toggle. Role-equivalent to SmartMirroring's
 * ScreenSharingTile (same idea: one tap arms the tablet to receive an
 * incoming mirror session) — reimplemented independently, our own service
 * and protocol underneath.
 *
 * Note: a QS Tile alone cannot start a foreground service reliably on all
 * OEM skins when the screen is off; MainActivity remains the primary entry
 * point, this tile is a convenience shortcut that launches it.
 */
class ScreenInTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_AUTO_START, true)
        }
        startActivityAndCollapse(intent)
    }

    private fun updateTileState() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(com.opendex.receiver.R.string.tile_label)
            updateTile()
        }
    }
}
