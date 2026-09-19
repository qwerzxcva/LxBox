package com.leadaxe.aibox.engine.vpn

import android.content.Context
import android.util.Log
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.engine.singbox.ConfigCompiler
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Core VPN / sing-box engine. Owns the libbox [CommandServer] lifecycle and
 * serialises state transitions through a single command channel.
 *
 * Construction is cheap; [start] / [stop] / [reload] must only be invoked from
 * inside the VPN service so that the libbox [PlatformInterface] (which holds
 * the live tun fd) is set before [start] runs.
 */
class BoxEngine internal constructor(
    private val context: Context,
    private val platform: AIPlatform,
) : CommandServerHandler, io.nekohasekai.libbox.CommandClientHandler {

    companion object {
        private const val TAG = "BoxEngine"

        /** Process-wide kernel log ring shared with the UI (LogViewerPage). */
        val sharedLog = ArrayDeque<Pair<Int, String>>(2000)
        private const val COMMAND_PORT = 8964
        /** ms between status pushes — longer = less CPU, less UI responsiveness. */
        private const val RUNTIME_PUSH_INTERVAL_MS = 1000L

        @Volatile
        private var shared: BoxEngine? = null

        @Volatile
        private var libboxInitialised: Boolean = false

        /**
         * Returns the engine the VPN service created, or `null` when no
         * service is currently alive. The UI uses this to subscribe to
         * `state` / `runtime` without holding its own reference.
         */
        fun shared(): BoxEngine? = shared

        internal fun install(engine: BoxEngine) {
            shared = engine
        }

        internal fun clear() {
            shared = null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<BoxState>(BoxState.Idle)
    val state: StateFlow<BoxState> = _state.asStateFlow()

    private val _runtime = MutableStateFlow(BoxRuntimeSnapshot())
    val runtime: StateFlow<BoxRuntimeSnapshot> = _runtime.asStateFlow()

    /** Connection live-view as materialised from the kernel's event stream. */
    @kotlinx.serialization.Serializable
    data class ConnectionInfo(
        val id: String,
        val network: String,
        val inbound: String,
        val source: String,
        val destination: String,
        val domain: String,
        val protocol: String,
        val outbound: String,
        val outboundType: String,
        val chain: List<String>,
        val rule: String,
        val createdAt: Long,
        val closedAt: Long,
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val processPath: String,
        val userId: Int,
        val packageNames: List<String>,
    ) {
        val active: Boolean get() = closedAt == 0L
        /** First hop of the chain is what the user picked (node or direct). */
        val groupKey: String get() = chain.firstOrNull() ?: outbound.ifBlank { "unknown" }
    }

    private val _connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    @Volatile
    var connectionsPaused: Boolean = false

    private fun connectionInfoFrom(c: io.nekohasekai.libbox.Connection): ConnectionInfo {
        val chain = buildList {
            val it = c.chain()
            while (it.hasNext()) add(it.next())
        }
        val proc = c.processInfo
        val packages = buildList {
            proc?.packageNames()?.let { p ->
                while (p.hasNext()) add(p.next())
            }
        }
        return ConnectionInfo(
            id = c.id,
            network = c.network,
            inbound = c.inbound,
            source = c.source,
            destination = c.destination,
            domain = c.domain,
            protocol = c.protocol,
            outbound = c.outbound,
            outboundType = c.outboundType,
            chain = chain,
            rule = c.rule,
            createdAt = c.createdAt,
            closedAt = c.closedAt,
            uplinkTotal = c.uplinkTotal,
            downlinkTotal = c.downlinkTotal,
            processPath = proc?.processPath.orEmpty(),
            userId = proc?.userID?.toInt() ?: -1,
            packageNames = packages,
        )
    }

    /** Token to derive a fresh per-run command server secret. */
    private val startSequence = AtomicLong(0)

    @Volatile
    private var server: CommandServer? = null

    fun isRunning(): Boolean = server != null

    /**
     * Forward the device's screen + lock state to the kernel (power-report
     * model + idle suspension triggers). Both hooks exist on the libbox
     * CommandServer of the reF1nd core we ship.
     */
    fun recordPowerState(screenOn: Boolean, deviceLocked: Boolean) {
        val s = server ?: return
        runCatching { s.recordScreenState(screenOn) }
        runCatching { s.recordLockState(deviceLocked) }
    }

    /**
     * Deep suspend (Doze / screen off): closes idle connections and lets the
     * kernel's pause manager stop its timers. `wake` resumes and
     * `wakeNow(force)` additionally resets the network state — used after
     * transitions where cached routes/sockets are known to be stale.
     */
    fun suspend() {
        val s = server ?: return
        runCatching { s.pause() }
    }

    fun resume(forceNetworkReset: Boolean = false) {
        val s = server ?: return
        runCatching {
            if (forceNetworkReset) s.wakeNow() else s.wake()
        }
    }

    /** Quick network-change recovery: drop every active connection. */
    fun closeAllForRecovery(): Result<Unit> = closeAllConnections()

    fun start(state: AppState): Job = launchCommand {
        // Watchdog: decode/checkConfig can wedge on a pathological config;
        // a "connecting" state that outlives this budget is surfaced as an
        // error instead of hanging the dial forever.
        val done = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            runStart(state)
        }
        if (done == null && _state.value is BoxState.Starting) {
            _state.value = BoxState.Error("config startup timed out (30s)")
            safeCloseServer()
            platform.discardTun()
            Result.failure(IllegalStateException("startup timeout"))
        } else {
            done ?: Result.failure(IllegalStateException("startup timeout"))
        }
    }

    fun stop(): Job = launchCommand { runStop() }

    fun reload(state: AppState): Job = launchCommand { runReload(state) }

    private fun launchCommand(block: suspend () -> Result<Unit>): Job =
        scope.launch { block() }

    private suspend fun runStart(state: AppState): Result<Unit> = mutex.withLock {
        if (server != null) return@withLock Result.success(Unit)
        _state.value = BoxState.Starting
        try {
            val cfg = ConfigCompiler
                .compile(state, ruleSetDir = workDir()).toString()
            lastConfigSnapshot = cfg
            ensureLibboxInitialised()
            // Pre-flight check (reference client's `sing-box check` pattern):
            // construct the whole instance off the hot path and discard it.
            // A config the kernel would reject at start now fails HERE with
            // the kernel's own message, before any tun fd is opened or a
            // half-started service needs rolling back.
            io.nekohasekai.libbox.Libbox.checkConfig(cfg)
            applyQuicCompat(state)
            val s = CommandServer(this, platform).also { server = it }
            s.startOrReloadService(cfg, state.toOverrideOptions())
            attachClient()
            _state.value = BoxState.Connected(System.currentTimeMillis())
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.value = BoxState.Error(t.message ?: t.javaClass.simpleName)
            safeCloseServer()
            // Drop the tun fd that the service just established: the box
            // never accepted ownership, leaving the fd alive would keep an
            // invisible VPN profile open in the system settings.
            platform.discardTun()
            Result.failure(t)
        }
    }

    private suspend fun runStop(): Result<Unit> = mutex.withLock {
        val s = server ?: return@withLock Result.success(Unit)
        _state.value = BoxState.Stopping
        try {
            s.closeService()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            safeCloseServer()
            _state.value = BoxState.Idle
        }
    }

    private suspend fun runReload(state: AppState): Result<Unit> = mutex.withLock {
        val s = server ?: return@withLock Result.failure(IllegalStateException("not started"))
        try {
            val cfg = ConfigCompiler
                .compile(state, ruleSetDir = workDir()).toString()
            lastConfigSnapshot = cfg
            s.startOrReloadService(cfg, state.toOverrideOptions())
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Called from the VPN service when the tun fd becomes available. */
    internal fun openTunFd(): Int {
        // The platform layer holds the fd once the VpnService.Builder has established
        // it. We just hand it back to libbox via [platform.openTunFromEngine].
        return platform.openTunFromEngine()
    }

    private fun ensureLibboxInitialised() {
        if (libboxInitialised) return
        libboxInitialised = true
        val opts = SetupOptions().apply {
            basePath = filesDir().absolutePath
            workingPath = workDir().absolutePath
            tempPath = context.cacheDir.absolutePath
            commandServerListenPort = COMMAND_PORT
            commandServerSecret = newSecret()
            logMaxLines = 1000
            // ask the kernel to publish power-related counters on the
            // command channel — the UI shows them on the Home tab and they
            // also give us a free way to detect when the box is fighting
            // the system (e.g. wakelock counts ballooning).
            powerReportEnabled = true
        }
        Libbox.setup(opts)
    }

    /** Re-derived per-start so each VPN session has its own client + secret. */
    private var currentSecret: String = ""

    /** Last compiled config, kept for the log exporter's sanitiser. */
    @Volatile
    var lastConfigSnapshot: String? = null
        private set

    /**
     * QUIC compatibility switches, applied before the service starts so the
     * first QUIC connection already honours them (the kernel reads the env
     * lazily per connection, so this also reaches later sessions on reload).
     */
    private fun applyQuicCompat(state: AppState) {
        runCatching {
            io.nekohasekai.libbox.Libbox.setQuicGoGsoDisabled(state.quicDisableGso)
            io.nekohasekai.libbox.Libbox.setQuicGoEcnDisabled(state.quicDisableEcn)
        }.onFailure { Log.w("BoxEngine", "quic compat switches failed: ${it.message}") }
    }

    /** Live command channel; kept so the UI can request a latency probe. */
    @Volatile
    private var client: io.nekohasekai.libbox.CommandClient? = null

    private fun attachClient() {
        // The CommandServer is the kernel-facing IPC, but it doesn't push
        // status to its handler — status flows the other way, from a
        // CommandClient back to CommandClientHandler. We self-host a
        // CommandClient here so the Home tab can show live uplink/downlink
        // counters and goroutine counts without re-implementing them.
        val opts = io.nekohasekai.libbox.CommandClientOptions().apply {
            statusInterval = RUNTIME_PUSH_INTERVAL_MS
            addCommand(io.nekohasekai.libbox.Libbox.CommandStatus)
            addCommand(io.nekohasekai.libbox.Libbox.CommandConnections)
        }
        val c = io.nekohasekai.libbox.CommandClient(this, opts)
        runCatching { c.connect() }
            .onFailure { Log.w(TAG, "status client connect failed", it) }
        client = c
    }

    /**
     * Latency probe. The reF1nd kernel exposes `urlTest(tag)` (async) rather
     * than lx's blocking `urlTestOutbound`; the group's new delay value is
     * pushed back through [writeGroups], so this call only triggers the
     * probe. The UI reads fresh numbers from the next group snapshot.
     */
    fun pingOutbound(tag: String, url: String, timeoutMillis: Int = 3000): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("engine not running"))
        return runCatching { c.urlTest(tag) }
    }

    // ----------------- CommandClientHandler callbacks -----------------

    override fun connected() {
        // Fired once the CommandClient attaches to the running CommandServer.
        // Nothing to do here — the engine state machine is the source of
        // truth for the UI; runtime counters follow via writeStatus().
    }

    override fun disconnected(message: String?) {
        Log.d(TAG, "status client disconnected: ${message.orEmpty()}")
    }

    override fun writeStatus(status: io.nekohasekai.libbox.StatusMessage) {
        _runtime.value = BoxRuntimeSnapshot(
            uplinkBytes = status.uplink,
            downlinkBytes = status.downlink,
            uplinkTotalBytes = status.uplinkTotal,
            downlinkTotalBytes = status.downlinkTotal,
            goroutines = status.goroutines,
            memoryBytes = status.memory,
            connectionsIn = status.connectionsIn,
            connectionsOut = status.connectionsOut,
        )
    }

    override fun initializeClashMode(modes: io.nekohasekai.libbox.StringIterator, current: String?) {
        // Clash mode is owned by AppState; ignore the kernel echo for now.
    }

    override fun updateClashMode(mode: String?) {
        // Same as initializeClashMode — AppState drives this, not libbox.
    }

    override fun setDefaultLogLevel(level: Int) = Unit

    override fun clearLogs() = Unit

    override fun writeLogs(logs: io.nekohasekai.libbox.LogIterator) {
        while (logs.hasNext()) {
            val entry = logs.next()
            sharedLog.addLast(entry.level to entry.message)
            while (sharedLog.size > 2000) sharedLog.removeFirst()
        }
    }

    /**
     * Group snapshots: the tag → delay map feeds single-node ping replies.
     * `URLTest` is fire-and-forget in the kernel — the measured delay comes
     * back through this stream, keyed by the outbound tag. The service maps
     * tags to pending ping requests and answers them.
     */
    override fun writeGroups(groups: io.nekohasekai.libbox.OutboundGroupIterator) {
        while (groups.hasNext()) {
            val group = groups.next()
            val items = group.items
            while (items.hasNext()) {
                val item = items.next()
                pendingPingReplies.remove(item.tag)?.invoke(item.urlTestDelay)
            }
        }
    }

    override fun writeOutbounds(outbounds: io.nekohasekai.libbox.OutboundGroupItemIterator) {
        while (outbounds.hasNext()) {
            val item = outbounds.next()
            pendingPingReplies.remove(item.tag)?.invoke(item.getURLTestDelay())
        }
    }

    /**
     * Pending single-node probes, keyed by the outbound tag (multi-slot:
     * the batch "test all" fires N probes at once and the kernel streams
     * their delays back on the same outbounds snapshot, so a single
     * callback slot made every concurrent waiter but one starve — the
     * button appeared to do nothing at all).
     */
    private val pendingPingReplies =
        java.util.concurrent.ConcurrentHashMap<String, (Int) -> Unit>()

    /**
     * Blocking variant of [pingOutbound] for the VPN process: triggers the
     * probe and waits for the delay to arrive through the outbounds stream
     * (the kernel's URLTest RPC itself is fire-and-forget). Returns the
     * measured delay, or a failure when the probe timed out or errored.
     */
    fun pingOutboundAwaiting(tag: String, timeoutMillis: Long = 8_000): Result<Int> {
        val c = client ?: return Result.failure(IllegalStateException("engine not running"))
        val latch = java.util.concurrent.CountDownLatch(1)
        val delayBox = java.util.concurrent.atomic.AtomicInteger(-1)
        // Register BEFORE firing the RPC: the snapshot that answers it can
        // land on any thread, and a late registration would miss it.
        pendingPingReplies[tag] = { delay ->
            if (delay > 0) delayBox.set(delay)
            latch.countDown()
        }
        return try {
            runCatching { c.urlTest(tag) }.getOrElse {
                return Result.failure(it)
            }
            if (!latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Result.failure(IllegalStateException("probe timed out"))
            } else {
                val measured = delayBox.get()
                if (measured > 0) Result.success(measured)
                else Result.failure(IllegalStateException("no delay reported"))
            }
        } finally {
            pendingPingReplies.remove(tag)
        }
    }

    override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents) {
        if (connectionsPaused) return
        val updates = mutableListOf<ConnectionInfo>()
        val closedIds = mutableListOf<String>()
        val it = events.iterator()
        while (it.hasNext()) {
            val e = it.next()
            when (e.type.toLong()) {
                io.nekohasekai.libbox.Libbox.ConnectionEventNew ->
                    e.connection?.let { updates += connectionInfoFrom(it) }
                io.nekohasekai.libbox.Libbox.ConnectionEventUpdate ->
                    e.connection?.let { updates += connectionInfoFrom(it) }
                io.nekohasekai.libbox.Libbox.ConnectionEventClosed ->
                    closedIds += e.id
            }
        }
        if (events.reset) {
            // Full snapshot: replaces whatever we tracked before.
            val active = updates.associateBy { it.id }
            val closed = _connections.value
                .filter { it.id in closedIds }
                .map { it.copy(closedAt = System.currentTimeMillis()) }
                .associateBy { it.id }
            _connections.value = (active + closed).values.toList()
            return
        }
        _connections.update { current ->
            val byId = current.associateBy { it.id }.toMutableMap()
            updates.forEach { byId[it.id] = it }
            closedIds.forEach { id ->
                byId[id]?.let { byId[id] = it.copy(closedAt = it.closedAt.ifZero { System.currentTimeMillis() }) }
            }
            byId.values.toList()
        }
    }

    private fun Long.ifZero(default: () -> Long): Long = if (this != 0L) this else default()

    /** Closes one connection by id; the kernel pushes a CLOSED event after. */
    fun closeConnection(id: String): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("engine not running"))
        return runCatching { c.closeConnection(id) }
    }

    /** Closes every active connection. */
    fun closeAllConnections(): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("engine not running"))
        return runCatching { c.closeConnections() }
    }

    /** Clears the in-memory connection view (both live and history). */
    fun clearConnections() {
        _connections.value = emptyList()
    }

    /**
     * Flushes the kernel's DNS client cache, the reverse mapping, and asks
     * the platform resolver to do the same. Fails when the box is down.
     */
    fun clearDNSCache(): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("engine not running"))
        return runCatching { c.clearDNSCache() }
    }

    private fun filesDir(): File = context.filesDir
    private fun workDir(): File = File(context.filesDir, "box").apply { mkdirs() }

    private fun newSecret(): String =
        "lx-${startSequence.incrementAndGet()}-${System.nanoTime()}"

    private fun safeCloseServer() {
        runCatching { client?.disconnect() }
        client = null
        runCatching { server?.close() }
        server = null
    }

    // ----------------- CommandServerHandler callbacks -----------------

    override fun serviceStop() {
        // libbox itself asked us to stop (e.g. fatal error or on-revoke).
        // Drop the tun fd defensively: libbox has already closed its copy,
        // but our handle might still be attached if openTun was never
        // invoked (rare — only on early failure paths).
        scope.launch {
            mutex.withLock {
                safeCloseServer()
                platform.discardTun()
                _state.value = BoxState.Idle
            }
        }
    }

    override fun serviceReload() {
        scope.launch {
            _state.update { it }
            // The caller (UI) issues a follow-up reload with fresh AppState;
            // this callback is only used to nudge listeners that the box
            // reloaded its own internal state (outbounds/groups/log level).
        }
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
        // Persisted via the service / AppState — not used yet.
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        // Empty status: "no system proxy"; system proxy isn't a feature on Android.
        return SystemProxyStatus().apply {
            // All fields default to false/empty.
        }
    }

    override fun writeDebugMessage(message: String) {
        // Forward to a logging sink when needed.
    }

    override fun connectSSHAgent(): Int = -1

    override fun triggerNativeCrash() {
        // No-op: crash triggers are a debug aid only.
    }
}

