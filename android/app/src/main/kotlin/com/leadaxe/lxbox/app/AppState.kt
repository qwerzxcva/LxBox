package com.leadaxe.lxbox.app

import kotlinx.serialization.Serializable

/** Root immutable application state, persisted as a single JSON document. */
@Serializable
data class AppState(
    val schemaVersion: Int = 2,
    val proxyRunning: Boolean = false,
    /** Clash-style mode: Rule / Global / Direct. */
    val clashMode: String = ClashModeRule,
    /** Selected node tag inside the `proxy` selector. */
    val selectedOutbound: String = "",
    val outbounds: List<OutboundProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val routeRules: List<RouteRule> = emptyList(),
    val ruleSets: List<RuleSetResource> = emptyList(),

    // ------------------------------------------------------------------ dns
    val dnsServers: List<DnsServerState> = defaultDnsServers(),
    val dnsRules: List<DnsRule> = emptyList(),
    /** Route rule `hijack-dns` for port 53 plus local DNS resolution (借鉴 AsteriskBOX). */
    val hijackDns: Boolean = true,
    val dnsStrategy: String = "",
    /** Extra DNS cache knobs (0 = engine default). */
    val dnsCacheCapacity: Int = 4096,
    val dnsIndependentCache: Boolean = true,
    /** SNI-based DNS rules should attach client subnet to queries. */
    val dnsClientSubnet: String = "",

    // -------------------------------------------------------------- fake-ip
    /** Built-in fake-IP pool for the `dns.fakeip` server (see ConfigCompiler). */
    val enableFakeIp: Boolean = false,
    /**
     * Fake-IP rewrite filter. When non-empty only matching domains are
     * answered from the fake pool; everything else falls through to the
     * normal DNS chain. Empty = rewrite everything (sing-box default).
     */
    val fakeIpFilter: List<String> = emptyList(),
    /** IPv4 fake range; empty = sing-box default (198.18.0.0/15). */
    val fakeIpInet4Range: String = "",
    /** IPv6 fake range; empty = sing-box default (fc00::/18). */
    val fakeIpInet6Range: String = "",
    /** TTL (seconds) for fake-IP answers; 0 = engine default. */
    val fakeIpTtl: Int = 0,

    // ------------------------------------------------------------- sniffer
    val enableSniffer: Boolean = true,
    val snifferProtocols: List<String> = listOf("http", "tls", "quic"),
    val snifferTimeout: String = "1s",

    // ---------------------------------------------------------------- tun
    val enableIpv6: Boolean = false,
    val tunMtu: Int = 9000,
    /** TUN interface IPv4 address (CIDR). */
    val tunInet4Address: String = "172.19.0.1/30",
    /** TUN interface IPv6 address (CIDR). Used when [enableIpv6]. */
    val tunInet6Address: String = "fdfe:dcba:9876::1/126",
    /** DNS server(s) advertised to the OS; empty = "auto" (tun address). */
    val tunDnsAddresses: List<String> = emptyList(),

    // ------------------------------------------------------------- engine
    val logLevel: String = "warn",
    val appendLogToFile: Boolean = true,
    /** Persist rule-set / fake-IP caches to disk (experimental.cache_file). */
    val enableCacheFile: Boolean = true,

    // ---------------------------------------------------------- per-app
    val perAppProxyEnabled: Boolean = false,
    /** true = only selected apps proxied (whitelist); false = selected apps bypass (blacklist). */
    val perAppProxyWhitelist: Boolean = false,
    val perAppProxyPackages: Set<String> = emptySet(),

    // ----------------------------------------------------------------- ui
    val colorMode: Int = ColorModeSystem,
    /** "" = follow system, otherwise a BCP-47-ish tag: "en", "zh". */
    val language: String = "",
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
const val FakeIpServerTag = "dns-fakeip"

/** Languages the UI can be switched to; "" means "follow system". */
val LanguageOptions = listOf("", "en", "zh")

val SnifferProtocolOptions = listOf(
    "http", "tls", "quic", "stun", "dns", "bittorrent", "dtls", "ssh", "rdp", "ntp",
)

val SingBoxLogLevels = listOf("trace", "debug", "info", "warn", "error", "fatal", "panic")

/**
 * DNS server transport types. `local` uses the OS resolver; `direct` forces
 * the request through the direct outbound; everything else maps to the
 * sing-box server type of the same name.
 */
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

/**
 * DNS server. `detour` selects the outbound used to reach the server —
 * empty means "direct"; [ProxySelectorTag] routes through the main proxy
 * selector; any node tag routes through that specific node. This is the
 * "DNS group / mirror with an explicit exit" feature: the same physical
 * server can be instantiated several times with different detours.
 */
@Serializable
data class DnsServerState(
    val id: String,
    val name: String,
    val type: String = "https",
    /** Host / IP / URL, matching the sing-box server address for `type`. */
    val address: String = "",
    /** Outbound tag used to reach this server; empty = direct routing. */
    val detour: String = "",
    val enabled: Boolean = true,
    /** Optional DNS strategy override for this server ("" = inherit global). */
    val strategy: String = "",
    /** Per-server resolver for the server's own domain name. */
    val domainResolver: String = "",
    /** 0 = engine default. */
    val cacheCapacity: Int = 0,
    /** EDNS client subnet, e.g. "1.2.3.0/24". */
    val clientSubnet: String = "",
    /** TLS server_name override (https/tls/quic/h3 types). */
    val tlsServerName: String = "",
    /** Skip TLS certificate verification (https/tls/quic/h3 types). */
    val insecure: Boolean = false,
) {
    val tag: String get() = "dns-$id"
}

/**
 * DNS rule. Mirrors the route-rule shape at a smaller scale: match on
 * domain / rule-set / query-type / process and route the query to a
 * specific DNS server (or to the fake-IP pool).
 */
@Serializable
data class DnsRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: String = KindInline,
    /** "default" or "logical". */
    val type: String = RouteRule.RuleTypeDefault,
    val logicalMode: String = RouteRule.LogicalAnd,
    val invert: Boolean = false,
    val rules: List<DnsRule> = emptyList(),
    // ----- match -----
    val domain: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val domainRegex: List<String> = emptyList(),
    val ruleSet: List<String> = emptyList(),
    val queryType: List<String> = emptyList(),
    val packageName: List<String> = emptyList(),
    val network: List<String> = emptyList(),
    val protocol: List<String> = emptyList(),
    val clashMode: List<String> = emptyList(),
    val invertApplied: Boolean = false,
    // ----- action -----
    /** Target DNS server tag; empty = "first available". */
    val server: String = "",
    /** "route" | "route-options" | "reject"; handled by [DnsRuleAction]. */
    val action: String = DnsRuleActionRoute,
    // ----- json kind -----
    val json: String = "",
) {
    val isLogical: Boolean get() = type == RouteRule.RuleTypeLogical

    companion object {
        const val KindInline = RouteRule.KindInline
        const val KindJson = RouteRule.KindJson
    }
}

const val DnsRuleActionRoute = "route"
const val DnsRuleActionRouteOptions = "route-options"
const val DnsRuleActionReject = "reject"

val DnsRuleActions = listOf(DnsRuleActionRoute, DnsRuleActionRouteOptions, DnsRuleActionReject)

/** Query types surfaced in the DNS rule editor, in the order sing-box lists them. */
val DnsQueryTypes = listOf(
    "A", "AAAA", "CNAME", "NS", "MX", "TXT", "PTR", "SRV", "SOA", "HTTPS",
)

/**
 * Default DNS servers. `remote` / proxy-chain resolvers route through the
 * proxy selector; `direct` uses the OS resolver for LAN lookups; `fakeip`
 * is opt-in via [AppState.enableFakeIp].
 */
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
        detour = DirectOutboundTag,
    ),
)

// ------------------------------------------------------------------ helpers

/** Outbound choices offered when picking a DNS server's exit. */
val DnsDetourDirect = ""
val DnsDetourProxy = ProxySelectorTag

/** Common cache-file path used by sing-box `experimental.cache_file`. */
const val CacheFileName = "cache.db"