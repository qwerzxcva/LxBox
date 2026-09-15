package com.leadaxe.aibox.engine.vpn

import androidx.annotation.Keep

/**
 * JNI bridge to `libhev_tun.so` — the hev-socks5-tunnel core behind
 * [HevTunMode], the lightweight TUN path that forwards the VPN interface
 * straight into a loopback SOCKS5 server without running the sing-box box.
 *
 * The native side is fail-safe: every call collapses to false/`null` when
 * the library is missing.
 */
@Keep
object HevTun {

    /** True when the native library loaded successfully. */
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = runCatching { System.loadLibrary("hev_tun") }.isSuccess
    }

    fun isRunning(): Boolean = available && runCatching { nativeIsRunning() }.getOrDefault(false)

    /**
     * Starts the tunnel. [configYaml] is a hev-socks5-tunnel YAML document;
     * [tunFd] is the established VPN interface fd. The fd ownership moves to
     * the native layer — the caller must not close it.
     */
    fun start(configYaml: String, tunFd: Int): Boolean =
        available && runCatching { nativeStart(configYaml, tunFd) }.getOrDefault(false)

    fun stop(): Boolean = available && runCatching { nativeStop() }.getOrDefault(false)

    private external fun nativeStart(config: String, tunFd: Int): Boolean

    private external fun nativeStop(): Boolean

    private external fun nativeIsRunning(): Boolean
}
