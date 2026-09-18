package com.leadaxe.aibox.engine.vpn

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-settings tile (experimental, FlClash/CMFA style): one tap in the
 * notification shade connects or disconnects the tunnel, mirroring the
 * home dial. The tile state follows the last state broadcast the relay
 * saw in this process — the VPN process is the source of truth, the tile
 * just mirrors it cheaply.
 *
 * Gated on the experimental quick-tile flag: when the user turns the flag
 * off the tile demotes itself to STATE_UNAVAILABLE on the next refresh.
 */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val store = (application as? com.leadaxe.aibox.AIBoxApp)?.appStateStore ?: return
        val exp = store.current.experimental
        if (!exp.enabled || !exp.quickTile) return

        val running = tunnelUp()
        val intent = Intent(this, AIVpnService::class.java).setPackage(packageName)
        if (running) {
            intent.action = AIVpnService.ACTION_DISCONNECT
        } else {
            // Mirror the home dial: only VpnService.prepare consent can be
            // answered from an Activity, so the tile starts optimistically
            // and the system consent dialog (if ever needed) opens via the
            // app UI. After the first grant this just works.
            intent.action = AIVpnService.ACTION_CONNECT
        }
        startService(intent)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val store = (application as? com.leadaxe.aibox.AIBoxApp)?.appStateStore
        val exp = store?.current?.experimental
        if (exp == null || !exp.enabled || !exp.quickTile) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        tile.state = if (tunnelUp()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    /**
     * Tile lives in the main process; the :vpn process is the source of
     * truth, mirrored here by the relay's state broadcasts. A cold main
     * process (no relay listening yet) reads as "down" — acceptable for a
     * tile, the next state broadcast fixes it.
     */
    private fun tunnelUp(): Boolean {
        val relay = (application as? com.leadaxe.aibox.AIBoxApp)?.vpnRelay ?: return false
        relay.startListening()
        return relay.state.value is BoxState.Connected || relay.state.value is BoxState.Starting
    }
}
