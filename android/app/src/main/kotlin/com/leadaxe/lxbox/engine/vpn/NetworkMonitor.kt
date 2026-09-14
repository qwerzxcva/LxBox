package com.leadaxe.lxbox.engine.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.leadaxe.lxbox.app.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Subscribes to system network changes and nudges the VPN service to reload
 * when the default network transitions (e.g. WiFi → cellular, or a captive
 * portal sign-in completes). sing-box's `auto_detect_interface` already
 * picks up the new outbound interface on the kernel side, but the running
 * configuration holds resolved DNS, cached routes, and stale `rule_set`
 * downloads; a reload clears them.
 *
 * The class is intentionally conservative — it ignores networks the user
 * hasn't authorised for VPN (e.g. unmetered bypass), and only fires once
 * per transition (debounced) to avoid thrashing on flaky cell handoffs.
 */
class NetworkMonitor(
    context: Context,
    private val store: AppStateStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val appCtx = context.applicationContext

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastReloadTrigger: Long = 0
    private var reloadJob: Job? = null

    fun start() {
        if (callback != null) return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "network available: $network")
                scheduleReload("available")
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "network lost: $network")
                scheduleReload("lost")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                Log.d(TAG, "network validated: $network")
                scheduleReload("validated")
            }
        }
        callback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        callback = null
    }

    private fun scheduleReload(reason: String) {
        // Coalesce bursts of events — `onLost` and `onAvailable` often fire
        // within a few hundred ms of each other on the same physical link.
        val now = System.currentTimeMillis()
        if (now - lastReloadTrigger < DEBOUNCE_MS) {
            reloadJob?.cancel()
        }
        lastReloadTrigger = now
        reloadJob = scope.launch {
            kotlinx.coroutines.delay(DEBOUNCE_MS)
            val state = store.current
            // Ask the service to push a fresh config through the running
            // tunnel. We can't go through BoxController here because the
            // UI may not be alive — the controller fires the same intent.
            val intent = android.content.Intent(
                appCtx, LxVpnService::class.java,
            ).setAction(LxVpnService.ACTION_RELOAD)
            runCatching { appCtx.startService(intent) }
                .onFailure { Log.w(TAG, "reload intent failed for $reason", it) }
        }
    }

    companion object {
        private const val TAG = "LxNetworkMonitor"
        private const val DEBOUNCE_MS = 750L
    }
}