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
    /** UrlTest / selector groups built on top of [outbounds]. */
    val outboundGroups: List<OutboundGroup> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** Folders that group [subscriptions] in the UI. */
    val subscriptionGroups: List<SubscriptionGroup> = emptyList(),
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
    /**
     * Explicit fallback resolver tag. Empty = automatic (first enabled
     * non-local server). Set this to pin the catch-all resolver — the
     * "兜底" choice in the DNS group UI.
     */
    val finalDnsServer: String = "",

    // -------------------------------------------------------------- fake-ip
    /** Built-in fake-IP pool for the `dns.fakeip` server (see ConfigCompiler). */
    val enableFakeIp: Boolean = false,
    /**
     * Fake-IP rewrite filter. When non-empty only matching domains are
     * answered from the fake pool; everything else falls through to the
     * normal DNS chain. Empty = rewrite everything (sing-box default).
     */
    val fakeIpFilter: List<String> = emptyList(),
    /**
     * When true, [fakeIpFilter] is an exclusion list instead of an inclusion
     * list: matching domains bypass the fake pool and are resolved normally.
     */
    val fakeIpFilterExclude: Boolean = false,
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
val DnsServerTypes = listOf("local", "direct", "udp", "tcp", "tls", "https", "quic", "h3", "group")

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

/**
 * Remote node source.
 *
 * [groupId] optionally files the subscription under a user-created group so
 * the Subscriptions tab can render folders; null = ungrouped.
 */
@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdatedEpochMillis: Long = 0,
    val groupId: String? = null,
    /**
     * How to reach the subscription endpoint.
     * "direct" / "proxy" / "auto" (try direct, fall back to proxy).
     */
    val fetchVia: String = FetchViaAuto,
    /**
     * Resolver used while fetching this subscription; a DNS server tag from
     * [AppState.dnsServers]. Empty = system resolver.
     */
    val dnsServer: String = "",
    /** Drop duplicate nodes (same type + server + port + credentials) on import. */
    val deduplicate: Boolean = true,
)

/** A folder that groups subscriptions in the UI. Purely presentational. */
@Serializable
data class SubscriptionGroup(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
)

const val FetchViaAuto = "auto"
const val FetchViaDirect = "direct"
const val FetchViaProxy = "proxy"

val FetchViaOptions = listOf(FetchViaAuto, FetchViaDirect, FetchViaProxy)

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
 * Outbound group: several nodes (or other groups) behind one tag with a
 * selection strategy.
 *
 *  - [KindSelector] — manual choice; the user picks the active member.
 *  - [KindUrlTest]  — automatic latency-based selection, re-probed every
 *    [interval]. sing-box-lx additionally offers an LX load-balancing
 *    mode ([mode] = [ModeRoundRobin]) that rotates a pool of the fastest
 *    members instead of always using one.
 *
 * Members are tags of [OutboundProfile]s (or other groups); order is the
 * declaration order inside the group.
 */
@Serializable
data class OutboundGroup(
    val id: String,
    val name: String = "",
    val kind: String = KindUrlTest,
    val members: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** UrlTest probe URL; empty = the global `speedTestUrl`. */
    val url: String = "",
    /** Probe interval, e.g. "3m". Empty = engine default (3m). */
    val interval: String = "",
    /** Latency tolerance in ms (url-test); 0 = engine default (50). */
    val tolerance: Int = 0,
    /** LX extension: "least_test" (default) or "round_robin". */
    val mode: String = ModeLeastTest,
    /** LX round_robin: how many of the fastest members form the pool. 0 = 3. */
    val pool: Int = 0,
    /** LX round_robin: pool tolerance in ms; 0 = keep-live-fill. */
    val poolTolerance: Int = 0,
    /** LX round_robin sticky-hash components: process/domain/source_ip/dest_ip/dest_port. */
    val stickyHash: List<String> = defaultStickyHash(),
    /** Selector only: currently chosen member tag. Empty = first member. */
    val selected: String = "",
) {
    val tag: String get() = "group-$id"

    companion object {
        const val KindSelector = "selector"
        const val KindUrlTest = "urltest"
        const val ModeLeastTest = "least_test"
        const val ModeRoundRobin = "round_robin"
    }
}

val OutboundGroupKinds = listOf(OutboundGroup.KindSelector, OutboundGroup.KindUrlTest)
val OutboundGroupModes = listOf(OutboundGroup.ModeLeastTest, OutboundGroup.ModeRoundRobin)
val StickyHashComponents = listOf("process", "domain", "source_ip", "dest_ip", "dest_port")

fun defaultStickyHash(): List<String> = listOf("process", "domain")

fun defaultUrlTestInterval(): String = "3m"

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
 *
 * `type = "group"` is a sing-box-lx extension: it wraps several other DNS
 * server tags behind one tag with a selection strategy, so a single dead
 * resolver no longer stalls name resolution. Group entries must reference
 * the tags of other servers in this list.
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
    // ----- group type (sing-box-lx) -----
    /** Member server TAGS for `type = "group"`. Order is not meaningful. */
    val groupServers: List<String> = emptyList(),
    /** group selection strategy: [DnsGroupStable] | [DnsGroupFastest] | [DnsGroupParallel]. */
    val groupMode: String = DnsGroupStable,
    /** How long an error record lives, e.g. "2m". Empty = engine default. */
    val groupErrorTtl: String = "",
    /** How long a win record lives (fastest only), e.g. "5m". Empty = default. */
    val groupWinTtl: String = "",
) {
    val tag: String get() = "dns-$id"
}

/** Group modes supported by the sing-box-lx DNS group server. */
const val DnsGroupStable = "stable"
const val DnsGroupFastest = "fastest"
const val DnsGroupParallel = "parallel"

val DnsGroupModes = listOf(DnsGroupStable, DnsGroupFastest, DnsGroupParallel)

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