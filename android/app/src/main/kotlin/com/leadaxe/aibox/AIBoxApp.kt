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
import kotlinx.coroutines.launch

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
        scheduleStaleSubscriptionRefresh()
    }

    /**
     * Auto-refresh: on every app start, subscriptions whose
     * `updateIntervalHours` elapsed are refetched in the background. The
     * UI-process store is the single source of truth, so results written
     * here are visible everywhere; a failure keeps the previous nodes.
     */
    private fun scheduleStaleSubscriptionRefresh() {
        appScope.launch {
            val store = appStateStore
            val state = store.current
            val now = System.currentTimeMillis()
            val stale = state.subscriptions.filter { sub ->
                sub.updateIntervalHours > 0 &&
                    now - sub.lastUpdatedEpochMillis >= sub.updateIntervalHours * 3_600_000L
            }
            if (stale.isEmpty()) return@launch
            val fetcher = com.leadaxe.aibox.engine.share.SubscriptionFetcher(this@AIBoxApp)
            for (sub in stale) {
                runCatching { fetcher.fetch(sub, dnsServers = store.current.dnsServers) }
                    .onSuccess { r ->
                        store.update { st ->
                            st.copy(
                                outbounds = st.outbounds.filterNot { it.subscriptionId == sub.id } + r.outbounds,
                                subscriptions = st.subscriptions.map {
                                    if (it.id == sub.id) it.copy(lastUpdatedEpochMillis = System.currentTimeMillis()) else it
                                },
                            )
                        }
                    }
            }
        }
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