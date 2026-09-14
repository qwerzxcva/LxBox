package com.leadaxe.lxbox.engine.vpn

import android.content.Context
import com.leadaxe.lxbox.app.AppState
import com.leadaxe.lxbox.engine.singbox.ConfigCompiler
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
    private val platform: LxPlatform,
) : CommandServerHandler, io.nekohasekai.libbox.CommandClientHandler {

    companion object {
        private const val TAG = "BoxEngine"
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

    /** Token to derive a fresh per-run command server secret. */
    private val startSequence = AtomicLong(0)

    @Volatile
    private var server: CommandServer? = null

    fun isRunning(): Boolean = server != null

    /**
     * Forward the device's screen + lock state to the kernel so it can
     * throttle housekeeping (GC, logging, idle timers) accordingly. Safe
     * to call before [start] — the values are recorded and replayed once
     * the command server attaches.
     */
    fun recordPowerState(screenOn: Boolean, deviceLocked: Boolean) {
        runCatching { server?.recordScreenState(screenOn) }
        runCatching { server?.recordLockState(deviceLocked) }
    }

    fun start(state: AppState): Job = launchCommand { runStart(state) }

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
            ensureLibboxInitialised()
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

    private fun attachClient() {
        // The CommandServer is the kernel-facing IPC, but it doesn't push
        // status to its handler — status flows the other way, from a
        // CommandClient back to CommandClientHandler. We self-host a
        // CommandClient here so the Home tab can show live uplink/downlink
        // counters and goroutine counts without re-implementing them.
        val opts = io.nekohasekai.libbox.CommandClientOptions().apply {
            statusInterval = RUNTIME_PUSH_INTERVAL_MS
            addCommand(io.nekohasekai.libbox.Libbox.CommandStatus)
        }
        val client = io.nekohasekai.libbox.CommandClient(this, opts)
        runCatching { client.connect() }
            .onFailure { Log.w(TAG, "status client connect failed", it) }
    }

    private fun filesDir(): File = context.filesDir
    private fun workDir(): File = File(context.filesDir, "box").apply { mkdirs() }

    private fun newSecret(): String =
        "lx-${startSequence.incrementAndGet()}-${System.nanoTime()}"

    private fun safeCloseServer() {
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

