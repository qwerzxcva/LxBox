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
import com.leadaxe.aibox.app.AppStateStore
import com.leadaxe.aibox.app.MainActivity
import com.leadaxe.aibox.engine.share.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

    private val screenLockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_ON ->
                    engine.recordPowerState(screenOn = true, deviceLocked = false)
                android.content.Intent.ACTION_SCREEN_OFF ->
                    engine.recordPowerState(screenOn = false, deviceLocked = true)
                android.content.Intent.ACTION_USER_PRESENT ->
                    engine.recordPowerState(screenOn = true, deviceLocked = false)
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
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            engine.pingOutbound(nodeTag, url)
                        }
                        if (result.isFailure) {
                            reply.putExtra(VpnIpc.EXTRA_PING_ERROR, result.exceptionOrNull()?.message ?: "failed")
                        }
                        sendBroadcast(reply)
                    }
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
            // UI-process requests (crossing the :vpn process boundary).
            addAction(VpnIpc.ACTION_REQUEST_SYNC)
            addAction(VpnIpc.ACTION_PING)
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
        engine.start(state)
        networkMonitor.start()
        // Refresh stale rule sets in the background after the box is up;
        // we don't gate the connection on the fetch — `compileRuleSets`
        // already falls back to the remote URL when the local cache is
        // missing or empty, so first-launch users get a working tunnel
        // while the rule files trickle in.
        scope.launch {
            runCatching { SubscriptionFetcher(this@AIVpnService).refreshStaleRuleSets(state.ruleSets) }
        }
        observeState()
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

    // ----------------- state observation ------------------------------------

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            engine.state.collect { st ->
                broadcastState(st)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

        private const val ROUTE_ALL = "0.0.0.0"
        private const val ROUTE_ALL_V6 = "::"
    }
}