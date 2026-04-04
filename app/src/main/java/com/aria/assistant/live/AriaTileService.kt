package com.aria.assistant.live

import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class AriaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("live_mode_enabled", false)
        
        prefs.edit().putBoolean("live_mode_enabled", !isEnabled).apply()
        
        if (!isEnabled) {
            // It was disabled, now enabling
            if (ConsentStore.isSessionActive(this)) {
                LiveModeController.startService(this)
            }
        } else {
            // It was enabled, now disabling
            LiveModeController.stop(this)
        }
        
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
            .getBoolean("live_mode_enabled", false)
            
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isEnabled) "ARIA: Live On" else "ARIA: Live Off"
        tile.updateTile()
    }
}
