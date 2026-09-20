package com.leadaxe.aibox.engine.rust

import androidx.annotation.Keep

/**
 * Bridge to the Rust core (`libaibox_core.so`, sources in `rust/aibox-core`).
 *
 * Phase 1 hosted the connection-snapshot fingerprinting: the VPN process runs
 * it on every push tick, so it is the first hot path moved off the Kotlin
 * runtime. Phase 2 adds the RSXM route-check engine, and Phase 3 exposes the
 * micro-kernel Conductor (kernelStart / kernelStop / reports / health).
 *
 * Methods are fail-safe — any error collapses to "no answer" (null), which
 * keeps the Go engine authoritative and never stalls the VPN path.
 */
@Keep
object AiboxCore {

    /** True when the native library loaded and answered at least once. */
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = runCatching { System.loadLibrary("aibox_core") }.isSuccess
    }

    /**
     * Computes "[<fingerprint>,<snapshotJson>]" from a connection-snapshot
     * JSON array. Null when the native layer is unavailable — callers fall
     * back to the Kotlin fingerprint path.
     */
    fun snapshotFingerprint(snapshotJson: String): String? = if (!available) {
        null
    } else {
        runCatching { nativeSnapshotFingerprint(snapshotJson) }.getOrNull()
    }

    /**
     * RSXM route-check: replays the compiled sing-box rule table against
     * one query and returns the explained decision as JSON
     * (`rule_index` / `action` / `outbound` / `reason`), or null when the
     * native engine is unavailable. Errors come back as `{"error": ...}`.
     */
    fun routeCheck(rulesJson: String, queryJson: String): String? = if (!available) {
        null
    } else {
        runCatching { nativeRouteCheck(rulesJson, queryJson) }.getOrNull()
    }

    /**
     * Boots the Rust micro-kernel Conductor: registers every leader
     * (rules / dns / dialer / power / security / stats / tun) and hands each
     * its own opaque config slice. Returns "ok", or "partial; <failures>";
     * null when the native core is unavailable (the Go engine still runs).
     */
    fun kernelStart(appStateJson: String): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelStart(appStateJson) }.getOrNull()
    }

    /** Stops the Rust Conductor. Safe to call repeatedly; null when unavailable. */
    fun kernelStop(): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelStop() }.getOrNull()
    }

    /**
     * Drains the Conductor report bus as JSON: `[{module,kind,at,message}...]`.
     * Used by the log viewer to surface leader events without polling.
     */
    fun kernelDrainReports(): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelDrainReports() }.getOrNull()
    }

    /** Per-module health as JSON: `[{module,health}...]`. */
    fun kernelHealth(): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelHealth() }.getOrNull()
    }

    /**
     * Asks the Rust DNS engine for its decision on one query name — the
     * shadow-DNS-to-real step: fakeip allocation, cache hits, hosts
     * overrides and rule-based reject/steer happen in Rust and come back
     * as `{"action": "...", ...}`. Null when the kernel is not running.
     * The route-check page renders this next to the Go engine's answer so
     * both stacks can be compared live.
     */
    fun kernelDnsDecide(name: String, qtype: String): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelDnsDecide(name, qtype) }.getOrNull()
    }

    /**
     * Hands the tun fd to the Rust packet engine: HEV starts with its
     * SOCKS5 hop aimed at the rsxm server, so the data path is
     * tun -> HEV -> rsxm -> node with no sing-box hop. Returns "ok" or an
     * error string.
     */
    fun kernelTunStart(tunFd: Int, socksPort: Int): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelTunStart(tunFd, socksPort) }.getOrNull()
    }

    /** Stops the HEV packet engine (rsxm data plane). */
    fun kernelTunStop(): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelTunStop() }.getOrNull()
    }

    /**
     * Forwards a screen / charging edge to the Rust power leader and returns
     * the directive the other leaders should follow now ("run" | "throttle" |
     * "deep_pause"), or null when the kernel is not running. This is the only
     * path the battery-saving throttle has — the conductor owns no platform
     * hooks, so the screen-broadcast receiver must call this.
     */
    fun kernelPowerEvent(screenOn: Boolean, charging: Boolean): String? = if (!available) {
        null
    } else {
        runCatching { nativeKernelPowerEvent(screenOn, charging) }.getOrNull()
    }

    private external fun nativeSnapshotFingerprint(snapshotJson: String): String

    private external fun nativeRouteCheck(rulesJson: String, queryJson: String): String

    private external fun nativeKernelStart(appStateJson: String): String

    private external fun nativeKernelStop(): String

    private external fun nativeKernelDrainReports(): String

    private external fun nativeKernelHealth(): String

    private external fun nativeKernelPowerEvent(screenOn: Boolean, charging: Boolean): String

    private external fun nativeKernelDnsDecide(name: String, qtype: String): String

    private external fun nativeKernelTunStart(tunFd: Int, socksPort: Int): String

    private external fun nativeKernelTunStop(): String
}
