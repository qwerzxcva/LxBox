package com.leadaxe.aibox.engine.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.AppStateStore
import com.leadaxe.aibox.app.MainActivity
import com.leadaxe.aibox.engine.share.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Foreground VPN service. Owns the live tun fd and the [BoxEngine] that talks
 * to libbox. UI / system reach it through explicit Intents declared in the
 * manifest under [MainActivity]:
 *
 *  - `ACTION_CONNECT` — establish tun and start the box.
 *  - `ACTION_DISCONNECT` — stop the box and tear the tun down.
 *  - `ACTION_RELOAD` — push a fresh config without dropping the tun.
 */
class AIVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var platform: AIPlatform
    private lateinit var engine: BoxEngine
    private lateinit var store: AppStateStore
    private lateinit var networkMonitor: NetworkMonitor
    private var stateJob: Job? = null
    @Volatile
    private var lastConnectionsFingerprint: String? = null

    private val screenLockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_ON ->
                    engine.recordPowerState(screenOn = true, deviceLocked = false)
                android.content.Intent.ACTION_SCREEN_OFF ->
                    engine.recordPowerState(screenOn = false, deviceLocked = true)
                android.content.Intent.ACTION_USER_PRESENT -> {
                    engine.recordPowerState(screenOn = true, deviceLocked = false)
                    // Wake recovery: Doze/screen-off leaves stale sockets; a
                    // wake without network reset re-arms the kernel timers
                    // and lets the next push re-evaluate.
                    engine.resume()
                }
                android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    // Deep suspend, FlClash/Bettbox style: only once the OS
                    // itself entered Doze (not on every screen-off) do we
                    // pause the tunnel and close idle connections.
                    val pm = getSystemService(android.os.PowerManager::class.java)
                    val dozing = pm?.isDeviceIdleMode == true
                    if (dozing) {
                        engine.suspend()
                    } else {
                        engine.resume(forceNetworkReset = true)
                    }
                }
                VpnIpc.ACTION_REQUEST_SYNC ->
                    // A fresh UI process asked for the current state; reply on
                    // the same channel the periodic updates use.
                    broadcastState(engine.state.value)
                VpnIpc.ACTION_PING -> {
                    val nodeId = intent?.getStringExtra(VpnIpc.EXTRA_PING_NODE_ID) ?: return
                    val nodeTag = intent.getStringExtra(VpnIpc.EXTRA_PING_NODE_TAG) ?: return
                    val url = intent.getStringExtra(VpnIpc.EXTRA_PING_URL) ?: return
                    val reply = Intent(VpnIpc.ACTION_PING_RESULT).setPackage(packageName)
                        .putExtra(VpnIpc.EXTRA_PING_NODE_ID, nodeId)
                    scope.launch {
                        // The kernel's URLTest RPC is fire-and-forget; the
                        // delay arrives on the outbounds stream. Await it
                        // here so the UI's "measuring" state always resolves
                        // to either a number or an error. Timeout is the
                        // user's speedTestTimeoutMs (default 5s).
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            engine.pingOutboundAwaiting(
                                nodeTag,
                                timeoutMillis = store.current.speedTestTimeoutMs
                                    .coerceIn(1_000, 30_000).toLong(),
                            )
                        }
                        result.fold(
                            onSuccess = { reply.putExtra(VpnIpc.EXTRA_PING_DELAY, it) },
                            onFailure = {
                                reply.putExtra(VpnIpc.EXTRA_PING_ERROR, it.message ?: "failed")
                            },
                        )
                        sendBroadcast(reply)
                    }
                }
                VpnIpc.ACTION_CLOSE_CONNECTION -> {
                    val id = intent?.getStringExtra(VpnIpc.EXTRA_CLOSE_CONNECTION_ID) ?: return
                    engine.closeConnection(id)
                }
                VpnIpc.ACTION_CONNECTIONS_PAUSED -> {
                    engine.connectionsPaused =
                        intent?.getBooleanExtra(VpnIpc.EXTRA_CONNECTIONS_PAUSED, false) ?: false
                }
                VpnIpc.ACTION_NETWORK_RECOVERED -> {
                    // Close every live connection so nothing survives the
                    // interface change half-dead (quickResponse pattern).
                    engine.closeAllForRecovery()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = (application as AIBoxApp).appStateStore
        platform = AIPlatform(this)
        engine = BoxEngine(this, platform)
        networkMonitor = NetworkMonitor(this, store, scope)
        BoxEngine.install(engine)
        // Seed the kernel's power model with a sensible default. The
        // correct values arrive through screen/lock callbacks below; this
        // first record prevents the box from assuming "background, screen
        // off" while the service is still warming up.
        engine.recordPowerState(screenOn = true, deviceLocked = false)

        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            // UI-process requests (crossing the :vpn process boundary).
            addAction(VpnIpc.ACTION_REQUEST_SYNC)
            addAction(VpnIpc.ACTION_PING)
            addAction(VpnIpc.ACTION_CLOSE_CONNECTION)
            addAction(VpnIpc.ACTION_CONNECTIONS_PAUSED)
            addAction(VpnIpc.ACTION_NETWORK_RECOVERED)
        }
        // RECEIVER_NOT_EXPORTED is mandatory on Android 13+ for runtime
        // registered receivers, otherwise the system throws on register.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenLockReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenLockReceiver, filter)
        }
    }

    override fun onBind(intent: Intent): android.os.IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST be called within 5s of startForegroundService,
        // even for RELOAD — otherwise the system ANRs the service. We only
        // switch the notification to the "connected" text once the engine
        // reports BoxState.Connected, but the foreground promotion itself
        // happens unconditionally.
        startForegroundCompat()
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect()
            ACTION_DISCONNECT -> handleDisconnect()
            ACTION_RELOAD -> handleReload()
            ACTION_CLEAR_DNS_CACHE -> handleClearDnsCache()
            else -> {
                Log.w(TAG, "unknown action: ${intent?.action}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        handleDisconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenLockReceiver) }
        stateJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        networkMonitor.stop()
        engine.stop()
        // `engine.stop` releases the libbox command server which itself
        // closes the tun fd it inherited; clearTun just guards the case
        // where the box never got that far.
        platform.clearTun()
        BoxEngine.clear()
        super.onDestroy()
    }

    // ----------------- action handlers --------------------------------------

    private fun handleConnect() {
        val state = store.current
        // ECS auto: resolve the selected node's server host once, off the
        // main thread, and stash it for the compiler. A literal IP is used
        // as-is; a domain waits for the resolver (the compiler falls back to
        // the global subnet when it is still empty).
        scope.launch {
            val node = resolveNodeEcsAddress(state)
            if (node != null && node != state.nodeEcsAddress) {
                store.update { it.copy(nodeEcsAddress = node) }
            }
        }
        val configureIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ipv4 = parseCidr(state.tunInet4Address) ?: ParseResult("172.19.0.1", 30)
        val ipv6 = parseCidr(state.tunInet6Address) ?: ParseResult("fdfe:dcba:9876::1", 126)

        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(state.tunMtu)
            .addAddress(ipv4.address, ipv4.prefix)
            .addRoute(ROUTE_ALL, 0)
            .setBlocking(true)
            .setConfigureIntent(configureIntent)

        // Advertised DNS: user override, else the peer address of the tun
        // subnet — that address falls inside the tunnel route so the OS
        // sends :53 into sing-box where `hijack-dns` picks it up.
        val dnsForOs = state.tunDnsAddresses.map { it.trim() }.filter { it.isNotEmpty() }
        if (dnsForOs.isNotEmpty()) {
            dnsForOs.forEach { builder.addDnsServer(it) }
        } else {
            builder.addDnsServer(ipv4.peerAddress())
        }

        if (state.enableIpv6) {
            builder.addAddress(ipv6.address, ipv6.prefix)
                .addRoute(ROUTE_ALL_V6, 0)
        }

        val fd: ParcelFileDescriptor = try {
            builder.establish() ?: run {
                fail("failed to establish tun")
                return
            }
        } catch (t: Throwable) {
            fail(t.message ?: "tun establish failed")
            return
        }
        platform.setTun(fd)
        if (HevTunMode.enabled(state)) {
            // Lightweight TUN: skip the box entirely and feed the interface
            // into hev-socks5-tunnel, which dials a loopback SOCKS5 server.
            // That server is the sing-box local socks5 inbound (started
            // below with the full engine so subscription fetches and per-
            // app paths still work) — everything reaching HEV goes to the
            // selected node through it.
            val cfg = HevTunMode.writeConfig(filesDir, state)
            if (!HevTun.start(cfg.readText(), fd.detachFd())) {
                fail("hev tunnel failed to start (lib missing or fd invalid)")
                return
            }
        }
        engine.start(state)
        networkMonitor.start()
        // Scheduled health probe: a url-test pass over every group each
        // healthCheckIntervalMinutes while the tunnel is up (leastPing-style
        // observatory, interval user-chosen). The kernel's URLTest result
        // stream feeds the delay columns and auto-group re-selection.
        scheduleHealthProbe(state)
        // Per-app auto-start of the local HTTP/SOCKS5 inbounds: when a
        // trigger package connects, the inbounds switch on (config reload
        // with the flags temporarily forced); after the hold window without
        // trigger traffic they switch back off.
        localProxyTrigger = LocalProxyTrigger(store, scope) {
            // Just rebuild the config: the compiler reads the trigger flag
            // and adds/removes the inbounds. No state mutation, so the
            // user's manual switches and any concurrent edits survive.
            runCatching { handleReload() }
        }
        localProxyTrigger?.start()
        // Refresh stale rule sets in the background after the box is up;
        // we don't gate the connection on the fetch — `compileRuleSets`
        // already falls back to the remote URL when the local cache is
        // missing or empty, so first-launch users get a working tunnel
        // while the rule files trickle in.
        scope.launch {
            val fetcher = SubscriptionFetcher(this@AIVpnService)
            // First pass: cache missing rule sets (freshly materialised or
            // previously failed downloads).
            runCatching { fetcher.refreshStaleRuleSets(state.ruleSets) }
            observeState()
        }
    }

    private var healthProbeJob: kotlinx.coroutines.Job? = null
    private var localProxyTrigger: LocalProxyTrigger? = null

    private fun scheduleHealthProbe(state: AppState) {
        healthProbeJob?.cancel()
        val intervalMinutes = state.healthCheckIntervalMinutes
        if (intervalMinutes <= 0) return
        healthProbeJob = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(intervalMinutes * 60_000L)
                val current = store.current
                val url = current.speedTestUrl
                val groups = current.outboundGroups.filter { it.enabled }
                for (group in groups) {
                    runCatching { engine.pingOutbound(group.tag, url) }
                }
                // Piggyback: retry rule-set downloads that failed earlier —
                // a cached file is missing for exactly those, and the
                // compile-side remote fallback covers the gap in between.
                if (current.ruleSets.isNotEmpty()) {
                    runCatching {
                        SubscriptionFetcher(this@AIVpnService)
                            .refreshStaleRuleSets(current.ruleSets)
                    }
                }
                // Sequential per group: the kernel fires the probe for the
                // whole group; members' delays arrive on the outbounds
                // stream and re-selection happens kernel-side.
            }
        }
    }

    internal data class ParseResult(val address: String, val prefix: Int) {
        /**
         * The other usable host in a /30 or /126 point-to-point subnet.
         * Used as the OS-advertised DNS address: it routes through the
         * tunnel (hence into sing-box's DNS hijack) without colliding
         * with the interface address itself.
         */
        fun peerAddress(): String {
            if (prefix != 30 && prefix != 126) return address
            val idx = address.lastIndexOf('.')
            if (idx > 0) {
                val last = address.substring(idx + 1).toIntOrNull() ?: return address
                val peer = (last and 0xFC) + 2
                return address.substring(0, idx + 1) + peer
            }
            // IPv6: bump the last group by 1 (::1 -> ::2), good enough for
            // the /126 addresses we allow here.
            val hexIdx = address.lastIndexOf(':') + 1
            val group = address.substring(hexIdx).toIntOrNull(16) ?: return address
            return address.substring(0, hexIdx) + (group + 1).toString(16)
        }
    }

    /**
     * The server address of the outbound the tunnel will use — the ECS
     * value the compiler presents to proxied DNS lookups. Only the direct
     * form is resolvable here (a domain would need the tunnel itself), so
     * domains return null and the global subnet stays in charge.
     */
    private fun resolveNodeEcsAddress(state: AppState): String? {
        val selected = state.selectedOutbound.ifBlank { state.outbounds.firstOrNull()?.tag.orEmpty() }
        if (selected.isBlank()) return null
        val profile = state.outbounds.firstOrNull { it.tag == selected } ?: return null
        val server = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(profile.config)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("server")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull() ?: return null
        if (server.isBlank()) return null
        return runCatching {
            java.net.InetAddress.getByName(server).hostAddress
        }.getOrNull()
    }

    private fun parseCidr(cidr: String): ParseResult? {
        val s = cidr.trim()
        if (s.isEmpty()) return null
        val slash = s.lastIndexOf('/')
        if (slash <= 0 || slash == s.length - 1) return null
        val addr = s.substring(0, slash)
        val prefix = s.substring(slash + 1).toIntOrNull() ?: return null
        return ParseResult(addr, prefix)
    }

    private fun handleDisconnect() {
        healthProbeJob?.cancel()
        healthProbeJob = null
        localProxyTrigger?.stop()
        localProxyTrigger = null
        if (HevTun.isRunning()) HevTun.stop()
        networkMonitor.stop()
        engine.stop()
        platform.clearTun()
        stopForegroundCompat()
        stopSelf()
    }

    private fun handleReload() {
        val state = store.current
        engine.reload(state)
    }

    /**
     * Flushes the kernel's DNS caches (client cache, reverse mapping) and
     * asks the platform resolver to follow. Runs in the :vpn process where
     * the engine actually lives — the UI process has no engine handle.
     */
    private fun handleClearDnsCache() {
        scope.launch {
            val result = engine.clearDNSCache()
            Log.i(TAG, "clear dns cache: ${result.isSuccess}")
        }
    }

    /**
     * Exports the kernel log ring to a file in app-external files, then
     * broadcasts the path. The log is sanitised: node servers, UUIDs,
     * passwords and SNI values are stripped so a shared log never leaks
     * node configuration (rsxm-security posture).
     */
    private fun handleExportLogs() {
        scope.launch(Dispatchers.IO) {
            val sensitive = buildList {
                engine.lastConfigSnapshot?.let { cfg ->
                    // servers / uuid / password extraction from the compiled JSON
                    runCatching {
                        val obj = kotlinx.serialization.json.Json.parseToJsonElement(cfg)
                            .let { it as? kotlinx.serialization.json.JsonObject }
                        obj?.get("outbounds")?.let { ob ->
                            (ob as? kotlinx.serialization.json.JsonArray)?.forEach { o ->
                                val m = o as? kotlinx.serialization.json.JsonObject ?: return@forEach
                                (m["server"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let(::add)
                                (m["uuid"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::add)
                                (m["password"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::add)
                                ((m["tls"] as? kotlinx.serialization.json.JsonObject)?.get("server_name")
                                    as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::add)
                            }
                        }
                    }
                }
            }
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, "aibox-log.txt")
            file.bufferedWriter().use { w ->
                w.appendLine("AIBox kernel log export — ${java.util.Date()}")
                w.appendLine("sanitised: ${sensitive.size} config patterns masked")
                w.appendLine()
                synchronized(BoxEngine.sharedLog) {
                    BoxEngine.sharedLog.forEach { (lvl, msg) ->
                        var line = msg
                        sensitive.forEach { s -> line = line.replace(s, "***") }
                        w.appendLine("[\$lvl] \$line")
                    }
                }
            }
            // Broadcast the path so the UI can offer a share intent.
            sendBroadcast(
                android.content.Intent(BROADCAST_LOGS_EXPORTED)
                    .setPackage(packageName)
                    .putExtra("path", file.absolutePath),
            )
        }
    }

    // ----------------- state observation ------------------------------------

    private fun observeState() {
        stateJob?.cancel()
        // Family-policy changes come from the network monitor (IPv6 turned
        // unusable → downgrade to IPv4). They only take effect once the
        // running config is recompiled, so watch the state slice that
        // matters and reload on a flip. Debounced: the monitor can fire a
        // burst of callbacks while a network settles.
        scope.launch {
            var lastFallback: Boolean? = null
            store.state.collect { st ->
                val fallback = st.enableIpv6 && st.ipv6FallbackActive
                if (lastFallback != null && lastFallback != fallback) {
                    Log.d(TAG, "IPv6 fallback flipped to $fallback — reloading")
                    handleReload()
                }
                lastFallback = fallback
            }
        }
        stateJob = scope.launch {
            engine.state.collect { st ->
                broadcastState(st)
                // Persist the "tunnel was up" flag so BootReceiver can tell
                // a reboot that killed an active tunnel from a clean stop.
                val wasRunning = st is BoxState.Connected
                if (store.current.vpnWasRunning != wasRunning) {
                    store.update { it.copy(vpnWasRunning = wasRunning) }
                }
                when (st) {
                    is BoxState.Connected -> updateNotificationConnected()
                    is BoxState.Error -> {
                        Log.e(TAG, "engine error: ${st.message}")
                        stopForegroundCompat()
                        stopSelf()
                    }
                    else -> Unit
                }
            }
        }
        // Runtime counters ride a second, lighter broadcast so the UI can
        // update its traffic readout without the process-locals the engine
        // keeps in memory.
        scope.launch {
            engine.runtime.collect { rt ->
                val intent = Intent(VpnIpc.ACTION_RUNTIME)
                    .setPackage(packageName)
                    .putExtra(VpnIpc.EXTRA_UPLINK, rt.uplinkBytes)
                    .putExtra(VpnIpc.EXTRA_DOWNLINK, rt.downlinkBytes)
                    .putExtra(VpnIpc.EXTRA_UPLINK_TOTAL, rt.uplinkTotalBytes)
                    .putExtra(VpnIpc.EXTRA_DOWNLINK_TOTAL, rt.downlinkTotalBytes)
                    .putExtra(VpnIpc.EXTRA_GOROUTINES, rt.goroutines)
                    .putExtra(VpnIpc.EXTRA_MEMORY, rt.memoryBytes)
                    .putExtra(VpnIpc.EXTRA_CONNECTIONS_IN, rt.connectionsIn)
                    .putExtra(VpnIpc.EXTRA_CONNECTIONS_OUT, rt.connectionsOut)
                sendBroadcast(intent)
            }
        }
        // Connection snapshot relay: the connections page is the only
        // consumer, and every UI pause/hide still costs a JSON serialise —
        // so skip pushes while nothing changed.
        scope.launch {
            engine.connections.collect { list ->
                localProxyTrigger?.onConnections(list)
                val json = VpnIpc.connectionsToJson(list)
                // Rust path first (fast native fingerprint); the plain JSON
                // comparison is the fallback when the .so is unavailable.
                val rust = com.leadaxe.aibox.engine.rust.AiboxCore.snapshotFingerprint(json)
                val fingerprint = rust?.substringBefore(',').orEmpty()
                    .ifBlank { list.joinToString(",") { c -> "${c.id}:${c.uplinkTotal}:${c.downlinkTotal}:${c.closedAt}" } }
                if (fingerprint == lastConnectionsFingerprint) return@collect
                lastConnectionsFingerprint = fingerprint
                val payload = rust?.substringAfter(',') ?: json
                val intent = Intent(VpnIpc.ACTION_CONNECTIONS)
                    .setPackage(packageName)
                    .putExtra(VpnIpc.EXTRA_CONNECTIONS_JSON, payload)
                sendBroadcast(intent)
            }
        }
    }

    private fun broadcastState(st: BoxState) {
        val intent = Intent(VpnIpc.ACTION_STATE)
            .setPackage(packageName)
            .putExtra(VpnIpc.EXTRA_STATE, VpnIpc.stateName(st))
        if (st is BoxState.Error) intent.putExtra(VpnIpc.EXTRA_ERROR, st.message)
        if (st is BoxState.Connected) intent.putExtra(VpnIpc.EXTRA_SINCE, st.sinceEpochMillis)
        sendBroadcast(intent)
    }

    private fun updateNotificationConnected() {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val notification = Notification.Builder(this, AIPlatformNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(com.leadaxe.aibox.R.string.app_name))
            .setContentText("Connected")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, launchIntent(),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun fail(reason: String) {
        Log.e(TAG, "vpn setup failed: $reason")
        stopForegroundCompat()
        stopSelf()
    }

    // ----------------- foreground plumbing ----------------------------------

    private fun startForegroundCompat() {
        val pending = PendingIntent.getActivity(
            this, 0, launchIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, AIPlatformNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(com.leadaxe.aibox.R.string.app_name))
            .setContentText("VPN service is starting…")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun launchIntent(): Intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    companion object {
        private const val TAG = "AIVpnService"
        private const val SESSION_NAME = "L×Box"
        private const val NOTIFICATION_ID = 0x4C58

        const val AIPlatformNotificationChannelId = "aibox.vpn"

        const val ACTION_CONNECT = "com.leadaxe.aibox.engine.CONNECT"
        const val ACTION_DISCONNECT = "com.leadaxe.aibox.engine.DISCONNECT"
        const val ACTION_RELOAD = "com.leadaxe.aibox.engine.RELOAD"
        const val ACTION_CLEAR_DNS_CACHE = "com.leadaxe.aibox.engine.CLEAR_DNS_CACHE"
        const val ACTION_EXPORT_LOGS = "com.leadaxe.aibox.engine.EXPORT_LOGS"
        const val BROADCAST_LOGS_EXPORTED = "com.leadaxe.aibox.engine.LOGS_EXPORTED"

        private const val ROUTE_ALL = "0.0.0.0"
        private const val ROUTE_ALL_V6 = "::"
    }
}