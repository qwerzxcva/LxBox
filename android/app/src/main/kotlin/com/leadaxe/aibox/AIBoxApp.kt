package com.leadaxe.aibox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.leadaxe.aibox.app.AppStateStore
import com.leadaxe.aibox.engine.vpn.AIVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AIBoxApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appStateStore: AppStateStore by lazy { AppStateStore(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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