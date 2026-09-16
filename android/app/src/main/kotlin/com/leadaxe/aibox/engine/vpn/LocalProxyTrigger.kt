package com.leadaxe.aibox.engine.vpn

import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-start/stop of the local HTTP/SOCKS5 inbounds based on which apps are
 * making connections.
 *
 * The use case: a desktop tool (or another device on the LAN via a hotspot)
 * needs a proxy only while a specific app on the phone is actually working
 * — keeping the inbound always-on wastes battery and invites other apps to
 * silently route through it.
 *
 * Mechanics:
 *
 *  - The trigger watches the live connection stream the engine already
 *    produces; a connection's [BoxEngine.ConnectionInfo.packageNames] is
 *    how an app is identified.
 *  - When any connection from a trigger package is seen, the configured
 *    inbounds are switched on (kernel reload) and a hold timer starts.
 *  - While trigger traffic keeps appearing the hold timer refreshes; when
 *    it stops, the inbound switches off after the idle hold elapses — so a
 *    short gap between requests does not flap the tunnel.
 *
 * Flapping is bounded: a reload only happens on a state *transition*, and
 * the hold window (default 60s) is far longer than the poll cadence.
 */
class LocalProxyTrigger(
    private val store: AppStateStore,
    private val scope: CoroutineScope,
    private val onEngagedChange: () -> Unit,
) {

    /** Remaining hold time in ms; 0 = the inbound should be off. */
    @Volatile
    private var holdUntil: Long = 0

    /** Whether the inbounds are currently engaged (trigger window open). */
    @Volatile
    private var engaged: Boolean = false

    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                tick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        holdUntil = 0
        if (engaged) {
            engaged = false
        }
    }



    /**
     * Feeds a connection snapshot in. Called from the engine's connections
     * collector (VPN process), so this runs at the push cadence.
     *
     * Hold is refreshed by ANY live connection from a trigger package — the
     * window is "time since the trigger app was last seen", not a fixed
     * duration per request. Local-inbound connections (desktop tool →
     * local-socks5/local-http) carry no package name, but once the inbounds
     * are engaged they exist only because a trigger app is using them, so
     * they count too — otherwise the inbounds would close mid-use.
     */
    fun onConnections(connections: List<BoxEngine.ConnectionInfo>) {
        val state = store.current
        val triggers = state.localProxyTriggerPackages
        if (triggers.isEmpty()) return
        val hit = connections.any { conn ->
            when {
                !conn.active -> false
                conn.inbound == "local-socks5" || conn.inbound == "local-http" -> engaged
                else -> conn.packageNames.any { it in triggers }
            }
        }
        if (hit) {
            holdUntil = System.currentTimeMillis() + state.localProxyHoldMs
        }
    }

    private suspend fun tick() {
        val state = store.current
        val now = System.currentTimeMillis()
        val wantsOn = state.localProxyAutoTrigger && now < holdUntil
        if (wantsOn != engaged) {
            engaged = wantsOn
            isEngaged = wantsOn
            onEngagedChange()
        }
    }

    companion object {
        /** How often the hold timer is re-evaluated. */
        const val POLL_MS = 5_000L

        /**
         * Read by the compiler on every config build (VPN process). Static
         * because the compiler has no handle on the trigger instance.
         */
        @Volatile
        var isEngaged: Boolean = false
            private set

        /** Default hold window after the last trigger-package request. */
        const val DEFAULT_HOLD_MS = 60_000L
    }
}
