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

    private external fun nativeSnapshotFingerprint(snapshotJson: String): String

    private external fun nativeRouteCheck(rulesJson: String, queryJson: String): String

    private external fun nativeKernelStart(appStateJson: String): String

    private external fun nativeKernelStop(): String

    private external fun nativeKernelDrainReports(): String

    private external fun nativeKernelHealth(): String
}
