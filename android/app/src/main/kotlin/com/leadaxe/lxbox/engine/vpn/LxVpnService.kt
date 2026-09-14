package com.leadaxe.lxbox.engine.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.app.AppStateStore
import com.leadaxe.lxbox.app.MainActivity
import com.leadaxe.lxbox.engine.share.SubscriptionFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground VPN service. Owns the live tun fd and the [BoxEngine] that talks
 * to libbox. UI / system reach it through explicit Intents declared in the
 * manifest under [MainActivity]:
 *
 *  - `ACTION_CONNECT` — establish tun and start the box.
 *  - `ACTION_DISCONNECT` — stop the box and tear the tun down.
 *  - `ACTION_RELOAD` — push a fresh config without dropping the tun.
 */
class LxVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var platform: LxPlatform
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
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = (application as LxBoxApp).appStateStore
        platform = LxPlatform(this)
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
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(state.tunMtu)
            .addAddress(ADDRESS_IPV4, ADDRESS_PREFIX)
            .addRoute(ROUTE_ALL, 0)
            .addDnsServer(LOOPBACK_IPV4)
            .setBlocking(true)
            .setConfigureIntent(configureIntent)

        if (state.enableIpv6) {
            builder.addAddress(ADDRESS_IPV6, ADDRESS_PREFIX_IPV6)
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
            runCatching { SubscriptionFetcher(this@LxVpnService).refreshStaleRuleSets(state.ruleSets) }
        }
        observeState()
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
    }

    private fun updateNotificationConnected() {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val notification = Notification.Builder(this, LxPlatformNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(com.leadaxe.lxbox.R.string.app_name))
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
        val notification = Notification.Builder(this, LxPlatformNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(com.leadaxe.lxbox.R.string.app_name))
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
        private const val TAG = "LxVpnService"
        private const val SESSION_NAME = "L×Box"
        private const val NOTIFICATION_ID = 0x4C58

        const val LxPlatformNotificationChannelId = "lxbox.vpn"

        const val ACTION_CONNECT = "com.leadaxe.lxbox.engine.CONNECT"
        const val ACTION_DISCONNECT = "com.leadaxe.lxbox.engine.DISCONNECT"
        const val ACTION_RELOAD = "com.leadaxe.lxbox.engine.RELOAD"

        // 10.0.0.0/8 — RFC1918 private block; sing-box uses the link-local 172.17.0.0/16
        // address space by default in newer configs, but 10/8 is what every
        // existing rule template we mirror uses.
        private const val ADDRESS_IPV4 = "172.17.0.2"
        private const val ADDRESS_PREFIX = 16
        private const val ROUTE_ALL = "0.0.0.0"
        private const val LOOPBACK_IPV4 = "127.0.0.1"

        // IPv6 — single /128 route via the documentation prefix.
        private const val ADDRESS_IPV6 = "fdfe:dcba:9876::2"
        private const val ADDRESS_PREFIX_IPV6 = 64
        private const val ROUTE_ALL_V6 = "::"
    }
}