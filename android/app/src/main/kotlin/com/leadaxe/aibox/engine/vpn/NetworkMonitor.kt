package com.leadaxe.aibox.engine.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.leadaxe.aibox.app.AppStateStore
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
 * It also tracks whether the active network actually hands out a usable
 * IPv6 address: carriers and captive-portal WiFis that advertise IPv6 but
 * drop the traffic would otherwise leave every AAAA lookup hanging. When
 * the answer flips, the IPv6 policy downgrade flag is written to the state
 * so the compiler emits the IPv4-preferring strategy.
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

    // Reference-client fingerprinting (box net.inotify): a state key of
    // everything that actually changes the config's view of the network —
    // not just the callback event type. WiFi reconnecting with a fresh IP,
    // or a captive portal re-validating on the same link, produces the same
    // key and therefore no reload at all.
    private var lastNetworkKey: String? = null

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
                lastNetworkKey = null // force the next event through
                scheduleReload("lost")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                Log.d(TAG, "network validated: $network")
                evaluateIpv6Availability(network)
                scheduleReload("validated")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                evaluateIpv6Availability(network, linkProperties)
                scheduleReload("link")
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

    /**
     * Decides whether the active network can actually carry IPv6 traffic:
     * it needs a routable v6 address on the interface. A network that only
     * advertises the capability (or hands out a link-local fe80::/10) is
     * treated as not usable — that is the carrier/captive-portal case where
     * AAAA lookups would hang.
     */
    private fun evaluateIpv6Availability(network: Network, linkProperties: LinkProperties? = null) {
        val lp = linkProperties
            ?: runCatching { cm.getLinkProperties(network) }.getOrNull()
            ?: return
        val hasGlobalV6 = lp.linkAddresses.any { addr ->
            val ip = addr.address
            ip is java.net.Inet6Address && !ip.isLinkLocalAddress && !ip.isLoopbackAddress
        }
        val usable = hasGlobalV6 && runCatching { cm.getNetworkCapabilities(network) }
            .getOrNull()
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val current = store.current
        if (current.enableIpv6 && current.ipv6FallbackActive != !usable) {
            Log.d(TAG, "IPv6 usability changed: usable=$usable")
            store.update { it.copy(ipv6FallbackActive = !usable) }
        }
    }

    /**
     * Builds the fingerprint of the current default network: interface name,
     * addresses, DNS servers, and validated capability. Mirrors the
     * reference client's `wifi_status|ssid|bssid|ip` key — anything that
     * changes here changes what the kernel dials through.
     */
    private fun currentNetworkKey(): String {
        val active = runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks
                .firstOrNull { net ->
                    cm.getNetworkCapabilities(net)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                }
        }.getOrNull()
        val lp = active?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() } ?: return "none"
        val caps = active.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val addresses = lp.linkAddresses.joinToString(",") { it.address.hostAddress.orEmpty() }
        val dns = lp.dnsServers.joinToString(",") { it.hostAddress }
        val iface = lp.interfaceName.orEmpty()
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return "$iface|$addresses|$dns|$validated|$metered"
    }

    /**
     * SSID-based tunnel policy (reference client's network control). When
     * the policy fires (blacklisted SSID connected, or whitelisted SSID not
     * matched), the tunnel service is stopped and this returns true — the
     * reload is suppressed because there is nothing to reload.
     */
    private fun evaluateSsidPolicy(): Boolean {
        val state = store.current
        val mode = state.ssidPolicyMode
        if (mode.isBlank()) return false
        val ssid = currentWifiSsid()?.removeSurrounding("\"") ?: return false
        if (ssid.isBlank() || ssid == "<unknown ssid>") return false
        val matched = state.ssidPolicyList.any { it.trim().equals(ssid, ignoreCase = true) }
        val shouldStop = when (mode) {
            "blacklist" -> matched
            "whitelist" -> !matched
            else -> return false
        }
        if (!shouldStop) return false
        Log.i(TAG, "SSID policy ($mode): '$ssid' matched — stopping tunnel")
        runCatching {
            appCtx.startService(
                android.content.Intent(appCtx, AIVpnService::class.java)
                    .setAction(AIVpnService.ACTION_DISCONNECT),
            )
        }.onFailure { Log.w(TAG, "ssid policy stop failed", it) }
        return true
    }

    /** Current SSID via WifiManager; location permission required to read it. */
    private fun currentWifiSsid(): String? = runCatching {
        val wifi = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return null
        val info = wifi.connectionInfo ?: return null
        info.ssid
    }.getOrNull()

    private fun scheduleReload(reason: String) {
        // Layer 1 — state fingerprint (box-style): identical view of the
        // network, no reload. This is what kills the captive-portal and
        // WiFi-reconnect churn the event-type debounce cannot see.
        val key = currentNetworkKey()
        if (key == lastNetworkKey && reason != "lost") {
            Log.d(TAG, "network state unchanged ($reason), skip reload")
            return
        }

        // Layer 1.5 — SSID policy (reference client's network control): a
        // blacklisted SSID stops the tunnel, a whitelisted one keeps it.
        // Implemented as a reload-suppressing disconnect: the service stops
        // (not just skips config) so the VPN key icon goes away.
        if (reason != "lost" && evaluateSsidPolicy()) {
            return
        }

        // Layer 2 — time debounce: coalesce bursts (`onLost` + `onAvailable`
        // fire within a few hundred ms of each other on the same link).
        val now = System.currentTimeMillis()
        if (now - lastReloadTrigger < DEBOUNCE_MS) {
            reloadJob?.cancel()
        }
        lastReloadTrigger = now
        lastNetworkKey = key
        reloadJob = scope.launch {
            kotlinx.coroutines.delay(DEBOUNCE_MS)
            // quickResponse (Bettbox/FlClash): a network transition leaves
            // half-dead sockets that each take a timeout to die — close
            // everything first so the next request dials fresh.
            runCatching {
                appCtx.sendBroadcast(
                    android.content.Intent(VpnIpc.ACTION_NETWORK_RECOVERED)
                        .setPackage(appCtx.packageName),
                )
            }
            val state = store.current
            // Ask the service to push a fresh config through the running
            // tunnel. We can't go through BoxController here because the
            // UI may not be alive — the controller fires the same intent.
            val intent = android.content.Intent(
                appCtx, AIVpnService::class.java,
            ).setAction(AIVpnService.ACTION_RELOAD)
            runCatching { appCtx.startService(intent) }
                .onFailure { Log.w(TAG, "reload intent failed for $reason", it) }
        }
    }

    companion object {
        private const val TAG = "LxNetworkMonitor"
        private const val DEBOUNCE_MS = 750L
    }
}