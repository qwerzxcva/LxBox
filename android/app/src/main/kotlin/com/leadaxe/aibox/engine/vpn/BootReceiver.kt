package com.leadaxe.aibox.engine.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores the tunnel after a reboot (or an app update, which kills the
 * `:vpn` process silently).
 *
 * Constraints that shape this receiver:
 *
 *  - **User opt-in.** `bootAutoStart` is off by default; auto-raising a VPN
 *    consent dialog unprompted is hostile, and Android 10+ forbids background
 *    activity launches anyway. The user enables it in Settings.
 *  - **Only when the user had it on.** The receiver re-connects only if the
 *    tunnel was running when the device shut down (persisted flag) — or, for
 *    `MY_PACKAGE_REPLACED`, if it is supposed to be running now.
 *  - **VPN consent is one-time**: `VpnService.prepare` was already accepted,
 *    so `startFromBackground` can establish without an activity.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as com.leadaxe.aibox.AIBoxApp
        val state = app.appStateStore.current
        if (!state.bootAutoStart) {
            Log.d(TAG, "boot autostart disabled by user")
            return
        }
        if (state.outbounds.isEmpty() && state.outboundGroups.isEmpty()) {
            Log.d(TAG, "boot autostart skipped: no nodes configured")
            return
        }
        // MY_PACKAGE_REPLACED: only restore if the tunnel was actually up
        // before the update. BOOT_COMPLETED: restore if the user left it on.
        val wasRunning = app.appStateStore.current.vpnWasRunning
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && !wasRunning) {
            Log.d(TAG, "app updated while tunnel down — not starting")
            return
        }
        Log.i(TAG, "boot autostart: launching tunnel")
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, AIVpnService::class.java)
            .setAction(AIVpnService.ACTION_CONNECT)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(appContext, serviceIntent)
        }.onFailure { Log.w(TAG, "boot start failed", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
