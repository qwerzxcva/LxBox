package com.leadaxe.aibox.engine.vpn

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IPC contract between the VPN process and the UI process.
 *
 * The service runs in its own process (`:vpn`) so the sing-box core keeps
 * running — and keeps its memory — when the UI is swiped away, and so the
 * system can reclaim the UI process without taking the tunnel down. Both
 * sides meet here: the service broadcasts state and runtime snapshots, the
 * UI broadcasts requests (sync, ping) and listens for answers.
 */
object VpnIpc {

    const val ACTION_STATE = "com.leadaxe.aibox.vpn.STATE"
    const val ACTION_RUNTIME = "com.leadaxe.aibox.vpn.RUNTIME"
    const val ACTION_REQUEST_SYNC = "com.leadaxe.aibox.vpn.REQUEST_SYNC"
    const val ACTION_PING = "com.leadaxe.aibox.vpn.PING"
    const val ACTION_PING_RESULT = "com.leadaxe.aibox.vpn.PING_RESULT"
    const val ACTION_CONNECTIONS = "com.leadaxe.aibox.vpn.CONNECTIONS"
    const val ACTION_CLOSE_CONNECTION = "com.leadaxe.aibox.vpn.CLOSE_CONNECTION"
    const val ACTION_CONNECTIONS_PAUSED = "com.leadaxe.aibox.vpn.CONNECTIONS_PAUSED"
    const val ACTION_NETWORK_RECOVERED = "com.leadaxe.aibox.vpn.NETWORK_RECOVERED"

    const val EXTRA_STATE = "state"
    const val EXTRA_ERROR = "error"
    const val EXTRA_SINCE = "since"

    const val EXTRA_UPLINK = "uplink"
    const val EXTRA_DOWNLINK = "downlink"
    const val EXTRA_UPLINK_TOTAL = "uplinkTotal"
    const val EXTRA_DOWNLINK_TOTAL = "downlinkTotal"
    const val EXTRA_GOROUTINES = "goroutines"
    const val EXTRA_MEMORY = "memory"
    const val EXTRA_CONNECTIONS_IN = "connectionsIn"
    const val EXTRA_CONNECTIONS_OUT = "connectionsOut"

    const val EXTRA_PING_NODE_ID = "nodeId"
    const val EXTRA_PING_NODE_TAG = "nodeTag"
    const val EXTRA_PING_URL = "url"
    const val EXTRA_PING_DELAY = "delay"
    const val EXTRA_PING_ERROR = "pingError"

    const val EXTRA_CONNECTIONS_JSON = "connectionsJson"
    const val EXTRA_CLOSE_CONNECTION_ID = "closeConnId"
    const val EXTRA_CONNECTIONS_PAUSED = "connectionsPaused"

    /** State names as they travel over the wire. */
    const val STATE_IDLE = "idle"
    const val STATE_STARTING = "starting"
    const val STATE_CONNECTED = "connected"
    const val STATE_STOPPING = "stopping"
    const val STATE_ERROR = "error"

    fun stateName(state: BoxState): String = when (state) {
        BoxState.Idle -> STATE_IDLE
        BoxState.Starting -> STATE_STARTING
        is BoxState.Connected -> STATE_CONNECTED
        BoxState.Stopping -> STATE_STOPPING
        is BoxState.Error -> STATE_ERROR
    }

    /** Rebuilds a [BoxState] from the wire form; null when unrecognised. */
    fun stateFrom(name: String?, error: String?, since: Long): BoxState? = when (name) {
        STATE_IDLE -> BoxState.Idle
        STATE_STARTING -> BoxState.Starting
        STATE_CONNECTED -> BoxState.Connected(since)
        STATE_STOPPING -> BoxState.Stopping
        STATE_ERROR -> BoxState.Error(error.orEmpty())
        else -> null
    }

    /** Receiver flag that keeps the registration private to this app. */
    fun receiverFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Context.RECEIVER_NOT_EXPORTED
    } else {
        0
    }

    private val connectionsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Serialises the connection snapshot for the cross-process broadcast. */
    fun connectionsToJson(list: List<BoxEngine.ConnectionInfo>): String =
        connectionsJson.encodeToString(ConnectionSnapshot.serializer(), ConnectionSnapshot(list))

    /** Parses a broadcast connection snapshot; empty list on malformed input. */
    fun connectionsFromJson(json: String?): List<BoxEngine.ConnectionInfo> = runCatching {
        connectionsJson.decodeFromString(
            ConnectionSnapshot.serializer(),
            json.orEmpty(),
        ).connections
    }.getOrDefault(emptyList())

    @kotlinx.serialization.Serializable
    data class ConnectionSnapshot(val connections: List<BoxEngine.ConnectionInfo>)
}

/**
 * UI-process view of the VPN engine. Translates the service's broadcasts
 * into StateFlows the Compose screens can collect, and forwards requests
 * back (sync on start, latency probes).
 *
 * A UI process that starts while the service is already running asks for a
 * sync; until the answer arrives, [state] is null and the Home screen shows
 * the idle dial rather than claiming a connection state it cannot verify.
 */
class VpnRelay(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<BoxState?>(null)
    val state: StateFlow<BoxState?> = _state.asStateFlow()

    private val _runtime = MutableStateFlow(BoxRuntimeSnapshot())
    val runtime: StateFlow<BoxRuntimeSnapshot> = _runtime.asStateFlow()

    private val _pings = MutableStateFlow<Map<String, PingResult>>(emptyMap())
    val pings: StateFlow<Map<String, PingResult>> = _pings.asStateFlow()

    /** Live + historical connection view, relayed from the VPN process. */
    private val _connections = MutableStateFlow<List<BoxEngine.ConnectionInfo>>(emptyList())
    val connections: StateFlow<List<BoxEngine.ConnectionInfo>> = _connections.asStateFlow()

    @Volatile
    var connectionsPaused: Boolean = false
        private set

    data class PingResult(val delayMillis: Int?, val error: String?)

    private var registered = false

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VpnIpc.ACTION_STATE -> {
                    val parsed = VpnIpc.stateFrom(
                        intent.getStringExtra(VpnIpc.EXTRA_STATE),
                        intent.getStringExtra(VpnIpc.EXTRA_ERROR),
                        intent.getLongExtra(VpnIpc.EXTRA_SINCE, System.currentTimeMillis()),
                    )
                    if (parsed != null) _state.value = parsed
                }
                VpnIpc.ACTION_RUNTIME -> {
                    _runtime.value = BoxRuntimeSnapshot(
                        uplinkBytes = intent.getLongExtra(VpnIpc.EXTRA_UPLINK, 0),
                        downlinkBytes = intent.getLongExtra(VpnIpc.EXTRA_DOWNLINK, 0),
                        uplinkTotalBytes = intent.getLongExtra(VpnIpc.EXTRA_UPLINK_TOTAL, 0),
                        downlinkTotalBytes = intent.getLongExtra(VpnIpc.EXTRA_DOWNLINK_TOTAL, 0),
                        goroutines = intent.getIntExtra(VpnIpc.EXTRA_GOROUTINES, 0),
                        memoryBytes = intent.getLongExtra(VpnIpc.EXTRA_MEMORY, 0),
                        connectionsIn = intent.getIntExtra(VpnIpc.EXTRA_CONNECTIONS_IN, 0),
                        connectionsOut = intent.getIntExtra(VpnIpc.EXTRA_CONNECTIONS_OUT, 0),
                    )
                }
                VpnIpc.ACTION_CONNECTIONS -> {
                    val json = intent.getStringExtra(VpnIpc.EXTRA_CONNECTIONS_JSON) ?: return
                    _connections.value = VpnIpc.connectionsFromJson(json)
                }
                VpnIpc.ACTION_PING_RESULT -> {
                    val nodeId = intent.getStringExtra(VpnIpc.EXTRA_PING_NODE_ID) ?: return
                    val delay = intent.getIntExtra(VpnIpc.EXTRA_PING_DELAY, -1)
                    val error = intent.getStringExtra(VpnIpc.EXTRA_PING_ERROR)
                    _pings.value = _pings.value + (
                        nodeId to PingResult(
                            delayMillis = delay.takeIf { it >= 0 },
                            error = error,
                        )
                    )
                }
            }
        }
    }

    fun startListening() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(VpnIpc.ACTION_STATE)
            addAction(VpnIpc.ACTION_RUNTIME)
            addAction(VpnIpc.ACTION_PING_RESULT)
            addAction(VpnIpc.ACTION_CONNECTIONS)
        }
        appContext.registerReceiver(receiver, filter, VpnIpc.receiverFlags())
        requestSync()
    }

    fun stopListening() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** Asks a running service to re-broadcast its current state. */
    fun requestSync() {
        send(VpnIpc.ACTION_REQUEST_SYNC)
    }

    /** Asks the service to probe one node; the result arrives via broadcast. */
    fun requestPing(nodeId: String, nodeTag: String, url: String) {
        _pings.value = _pings.value + (nodeId to PingResult(null, null))
        send(VpnIpc.ACTION_PING) {
            putExtra(VpnIpc.EXTRA_PING_NODE_ID, nodeId)
            putExtra(VpnIpc.EXTRA_PING_NODE_TAG, nodeTag)
            putExtra(VpnIpc.EXTRA_PING_URL, url)
        }
    }

    /** Asks the service to kill one connection by kernel id. */
    fun requestCloseConnection(id: String) {
        send(VpnIpc.ACTION_CLOSE_CONNECTION) {
            putExtra(VpnIpc.EXTRA_CLOSE_CONNECTION_ID, id)
        }
    }

    /** Toggles the service-side connection recording. */
    fun setConnectionsPaused(paused: Boolean) {
        connectionsPaused = paused
        send(VpnIpc.ACTION_CONNECTIONS_PAUSED) {
            putExtra(VpnIpc.EXTRA_CONNECTIONS_PAUSED, paused)
        }
    }

    private fun send(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(action).setPackage(appContext.packageName)
        extras?.invoke(intent)
        appContext.sendBroadcast(intent)
    }
}