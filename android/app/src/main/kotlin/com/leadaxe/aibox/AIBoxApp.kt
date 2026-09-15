package com.leadaxe.aibox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.leadaxe.aibox.app.AppStateStore
import com.leadaxe.aibox.engine.vpn.VpnRelay
import com.leadaxe.aibox.engine.vpn.AIVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AIBoxApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appStateStore: AppStateStore by lazy { AppStateStore(this, appScope) }

    /**
     * UI-process view of the VPN engine. The service runs in `:vpn`; the UI
     * sees its state via the relay's broadcasts.
     */
    val vpnRelay: VpnRelay by lazy { VpnRelay(this) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    private fun ensureNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(AIVpnService.AIPlatformNotificationChannelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    AIVpnService.AIPlatformNotificationChannelId,
                    "VPN service",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent notification for the active VPN connection."
                },
            )
        }
    }
}