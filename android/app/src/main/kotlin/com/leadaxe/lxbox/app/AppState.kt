package com.leadaxe.lxbox.app

import kotlinx.serialization.Serializable

/** Root immutable application state, persisted as a single JSON document. */
@Serializable
data class AppState(
    val schemaVersion: Int = 1,
    val proxyRunning: Boolean = false,
    /** Clash-style mode: Rule / Global / Direct. */
    val clashMode: String = ClashModeRule,
    /** Selected node tag inside the `proxy` selector. */
    val selectedOutbound: String = "",
    val outbounds: List<OutboundProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val routeRules: List<RouteRule> = emptyList(),
    val ruleSets: List<RuleSetResource> = emptyList(),
    val dnsServers: List<DnsServerState> = defaultDnsServers(),
    /** Route rule `hijack-dns` for port 53 plus local DNS resolution (借鉴 AsteriskBOX). */
    val hijackDns: Boolean = true,
    val dnsStrategy: String = "",
    val enableSniffer: Boolean = true,
    val snifferProtocols: List<String> = listOf("http", "tls", "quic"),
    val snifferTimeout: String = "1s",
    val enableIpv6: Boolean = false,
    val tunMtu: Int = 9000,
    val logLevel: String = "warn",
    val appendLogToFile: Boolean = true,
    val perAppProxyEnabled: Boolean = false,
    /** true = only selected apps proxied (whitelist); false = selected apps bypass (blacklist). */
    val perAppProxyWhitelist: Boolean = false,
    val perAppProxyPackages: Set<String> = emptySet(),
    val colorMode: Int = ColorModeSystem,
    val speedTestUrl: String = "https://cp.cloudflare.com/generate_204",
)

const val ClashModeRule = "Rule"
const val ClashModeGlobal = "Global"
const val ClashModeDirect = "Direct"
val ClashModes = listOf(ClashModeRule, ClashModeGlobal, ClashModeDirect)

const val ColorModeSystem = 0
const val ColorModeLight = 1
const val ColorModeDark = 2

const val ProxySelectorTag = "proxy"
const val DirectOutboundTag = "direct"
const val BlockOutboundTag = "block"
const val DnsOutboundTag = "dns-out"
const val TunInboundTag = "tun-in"

val SnifferProtocolOptions = listOf(
    "http", "tls", "quic", "stun", "dns", "bittorrent", "dtls", "ssh", "rdp", "ntp",
)

val SingBoxLogLevels = listOf("trace", "debug", "info", "warn", "error", "fatal", "panic")

val DnsServerTypes = listOf("local", "direct", "udp", "tcp", "tls", "https", "quic", "h3")

val DnsStrategies = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")

/** A single proxy node. `config` holds the sing-box outbound JSON object (tag injected at build). */
@Serializable
data class OutboundProfile(
    val id: String,
    val name: String,
    val type: String,
    val config: String,
    val subscriptionId: String? = null,
) {
    val tag: String get() = "node-$id"
}

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdatedEpochMillis: Long = 0,
)

/**
 * Routing rule. Exactly two kinds (spec: the old inline/srs/json trio becomes two):
 * - [KindInline] — visual editor; may reference managed rule sets (former "srs" rules
 *   are now inline rules with `ruleSets` attached).
 * - [KindJson] — raw sing-box route-rule JSON pasted or imported from a file.
 */
@Serializable
data class RouteRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: String = KindInline,
    // ----- inline: structure -----
    /** "default" matches its own fields; "logical" combines `rules` with `logicalMode`. */
    val type: String = RuleTypeDefault,
    val logicalMode: String = LogicalAnd,
    val invert: Boolean = false,
    val rules: List<RouteRule> = emptyList(),
    // ----- inline: match fields (default rules) -----
    val domain: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val domainRegex: List<String> = emptyList(),
    val ipCidr: List<String> = emptyList(),
    val sourceIpCidr: List<String> = emptyList(),
    val sourcePort: List<String> = emptyList(),
    val sourcePortRange: List<String> = emptyList(),
    val port: List<String> = emptyList(),
    val portRange: List<String> = emptyList(),
    val network: List<String> = emptyList(),
    val protocol: List<String> = emptyList(),
    val packageName: List<String> = emptyList(),
    val wifiSsid: List<String> = emptyList(),
    val wifiBssid: List<String> = emptyList(),
    val sourceIpIsPrivate: Boolean = false,
    val ipIsPrivate: Boolean = false,
    /** Managed rule-set tags (this is where the old standalone SRS rules live now). */
    val ruleSet: List<String> = emptyList(),
    // ----- inline: action -----
    /** "route" → outbound tag; "reject"; "resolve" (resolve-only advanced). */
    val action: String = RuleActionRoute,
    val outbound: String = ProxySelectorTag,
    // ----- json kind -----
    val json: String = "",
) {
    val isLogical: Boolean get() = type == RuleTypeLogical

    companion object {
        const val KindInline = "inline"
        const val KindJson = "json"
        const val RuleTypeDefault = "default"
        const val RuleTypeLogical = "logical"
        const val LogicalAnd = "and"
        const val LogicalOr = "or"
        const val RuleActionRoute = "route"
        const val RuleActionReject = "reject"
        const val RuleActionResolve = "resolve"
    }
}

/**
 * A managed rule set — the successor of the old standalone "srs" rule kind.
 * Remote `.srs`/`.json` rule sets are cached on disk and referenced by inline
 * route rules through [RouteRule.ruleSet].
 */
@Serializable
data class RuleSetResource(
    val id: String,
    val tag: String,
    /** "binary" (.srs) or "source" (.json). */
    val format: String = "binary",
    val url: String = "",
    /** 0 = never auto-update. */
    val updateIntervalHours: Int = 168,
    val lastUpdatedEpochMillis: Long = 0,
) {
    val extension: String get() = if (format == "source") "json" else "srs"
}

@Serializable
data class DnsServerState(
    val id: String,
    val name: String,
    val type: String = "https",
    /** Host / IP / URL, matching the sing-box server address for `type`. */
    val address: String = "",
    /** Optional outbound tag used to reach this server; empty = direct routing. */
    val detour: String = "",
) {
    val tag: String get() = "dns-$id"
}

fun defaultDnsServers(): List<DnsServerState> = listOf(
    DnsServerState(
        id = "remote",
        name = "Remote",
        type = "https",
        address = "1.1.1.1",
        detour = ProxySelectorTag,
    ),
    DnsServerState(
        id = "direct",
        name = "Direct",
        type = "local",
        address = "",
    ),
)
