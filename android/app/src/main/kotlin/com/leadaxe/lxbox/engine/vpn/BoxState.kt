package com.leadaxe.lxbox.engine.vpn

import kotlinx.serialization.Serializable

/** State of the VPN / sing-box engine. */
sealed interface BoxState {
    data object Idle : BoxState
    data object Starting : BoxState
    data class Connected(val sinceEpochMillis: Long) : BoxState
    data object Stopping : BoxState
    data class Error(val message: String) : BoxState
}

@Serializable
data class BoxRuntimeSnapshot(
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val uplinkTotalBytes: Long = 0,
    val downlinkTotalBytes: Long = 0,
    val goroutines: Int = 0,
    val memoryBytes: Long = 0,
    val connectionsIn: Int = 0,
    val connectionsOut: Int = 0,
)

/** What [BoxEngine] knows how to do. The VPN service and the UI both call into this. */
sealed interface BoxCommand {
    /** Build a config from [state], set up tun, and start the box. */
    data class Start(val state: com.leadaxe.lxbox.app.AppState) : BoxCommand

    /** Tear everything down, drop tun, cancel notification. */
    data object Stop : BoxCommand

    /** Push a new config without dropping the tun. */
    data class Reload(val state: com.leadaxe.lxbox.app.AppState) : BoxCommand
}