package com.leadaxe.aibox.engine.vpn

import com.leadaxe.aibox.app.AppState
import java.io.File

/**
 * Lightweight TUN mode (hev-socks5-tunnel): an optional path where the VPN
 * interface feeds straight into a loopback SOCKS5 server — a tiny C core
 * (lwIP based) instead of the full sing-box engine. Intended for low-end
 * devices or "just give me a proxy" sessions: no rule engine, no DNS
 * splitting, everything goes to the selected node.
 */
object HevTunMode {

    const val CONFIG_FILE = "hev-tun.yml"
    const val SOCKS_PORT = 7780

    fun enabled(state: AppState): Boolean = state.hevTunMode

    /** Renders the hev-socks5-tunnel YAML for the given state. */
    fun configYaml(state: AppState): String = buildString {
        append("tunnel:\n")
        append("  mtu: 8500\n")
        append("  ipv4: 198.18.0.1\n")
        if (state.enableIpv6) append("  ipv6: 'fc00::1'\n")
        append("  icmp: 'off'\n")
        append("socks5:\n")
        append("  port: $SOCKS_PORT\n")
        append("  address: 127.0.0.1\n")
        append("  udp: 'udp'\n")
        append("misc:\n")
        append("  log-level: warn\n")
    }

    fun writeConfig(filesDir: File, state: AppState): File =
        File(filesDir, CONFIG_FILE).apply { writeText(configYaml(state)) }
}
