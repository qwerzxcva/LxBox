package com.leadaxe.lxbox.engine.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.leadaxe.lxbox.app.AppState
import com.leadaxe.lxbox.app.AppStateStore

/**
 * UI-facing facade over the VPN service. The UI never talks to [BoxEngine]
 * directly; instead it asks [BoxController] to start/stop, and the controller
 * either fires an `Intent` at the [LxVpnService] (which then owns the engine)
 * or relays a `RELOAD` if the service is already running.
 *
 * `isRunning` is derived from the live engine state (not a guessed flag) so
 * the UI never disagrees with the actual service state.
 */
class BoxController(
    private val context: Context,
    private val store: AppStateStore,
) {

    /** True iff a live [BoxEngine] is reporting `Connected` or `Starting`. */
    val isRunning: Boolean
        get() = when (BoxEngine.shared()?.state?.value) {
            is BoxState.Starting, is BoxState.Connected -> true
            else -> false
        }

    fun prepareVpn(activity: Activity): Intent? = VpnService.prepare(activity)

    fun start(activity: Activity): Boolean {
        if (VpnService.prepare(activity) != null) return false
        val intent = Intent(activity, LxVpnService::class.java)
            .setAction(LxVpnService.ACTION_CONNECT)
        activity.startForegroundService(intent)
        return true
    }

    /** Start without an Activity in hand — only safe after consent is granted. */
    fun startFromBackground() {
        val intent = Intent(context, LxVpnService::class.java)
            .setAction(LxVpnService.ACTION_CONNECT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop() {
        val intent = Intent(context, LxVpnService::class.java)
            .setAction(LxVpnService.ACTION_DISCONNECT)
        context.startService(intent)
    }

    /**
     * Push a fresh config snapshot to a running service. Persists [state] first so
     * the on-disk state always matches what the engine is running.
     */
    fun reload(state: AppState) {
        store.update { _ -> state }
        val intent = Intent(context, LxVpnService::class.java)
            .setAction(LxVpnService.ACTION_RELOAD)
        context.startService(intent)
    }
}