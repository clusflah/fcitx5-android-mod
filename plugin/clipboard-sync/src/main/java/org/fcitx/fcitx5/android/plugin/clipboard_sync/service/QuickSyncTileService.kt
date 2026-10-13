package org.fcitx.fcitx5.android.plugin.clipboard_sync.service

import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ServerStore

class QuickSyncTileService : TileService() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isActive = prefs.getBoolean("quick_sync", true)
        val newState = !isActive
        
        // Update preference
        prefs.edit().putBoolean("quick_sync", newState).apply()
        
        // Update tile
        updateTileState()
    }

    private fun updateTileState() {
        val isActive = prefs.getBoolean("quick_sync", true)
        val tile = qsTile ?: return
        
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick_sync_tile_label)
        // show which server is in use (the list may fail over between several)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val active = ServerStore.load(prefs).find { it.id == ServerStore.activeId(prefs) }
            tile.subtitle = if (isActive) active?.label else null
        }

        tile.updateTile()
    }
}
