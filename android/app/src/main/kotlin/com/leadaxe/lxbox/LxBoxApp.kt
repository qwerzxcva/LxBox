package com.leadaxe.lxbox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.leadaxe.lxbox.app.AppStateStore
import com.leadaxe.lxbox.engine.vpn.LxVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LxBoxApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appStateStore: AppStateStore by lazy { AppStateStore(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(LxVpnService.LxPlatformNotificationChannelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    LxVpnService.LxPlatformNotificationChannelId,
                    "VPN service",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent notification for the active VPN connection."
                },
            )
        }
    }
}