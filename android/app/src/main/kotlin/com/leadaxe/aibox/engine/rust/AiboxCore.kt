package com.leadaxe.aibox.engine.rust

import androidx.annotation.Keep

/**
 * Bridge to the Rust core (`libaibox_core.so`, sources in `rust/aibox-core`).
 *
 * Phase 1 hosts the connection-snapshot fingerprinting: the VPN process runs
 * it on every push tick, so it is the first hot path moved off the Kotlin
 * runtime. Methods are fail-safe — any error collapses to "no fingerprint",
 * which re-enables the plain broadcast path.
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

    private external fun nativeSnapshotFingerprint(snapshotJson: String): String

    private external fun nativeRouteCheck(rulesJson: String, queryJson: String): String
}
