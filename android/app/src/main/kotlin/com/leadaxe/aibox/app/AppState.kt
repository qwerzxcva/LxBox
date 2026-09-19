package com.leadaxe.aibox.app

import com.leadaxe.aibox.R
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
    /**
     * Built-in route rule: send any packet addressed to the fake-IP range
     * straight to direct. Protects against fake-IP leakage when an app
     * caches a fake address past the tunnel's lifetime.
     */
    val fakeIpBypass: Boolean = true,
    /**
     * Unknown traffic — connections whose owning app cannot be identified
     * (background / foreign processes, e.g. tethering or root daemons).
     * This is lxbox's "unknown traffic" rule: it is NOT the rule fallback.
     * Empty = let them bypass the VPN (direct) so they are not silently
     * proxied; set to a tag or "proxy"/"block" to steer them explicitly.
     */
    val unknownTrafficOutbound: String = "",
    /**
     * Fallback rule — the last rule of the routing table, applied to traffic
     * that matched NO rule above. Kept strictly separate from
     * [unknownTrafficOutbound] which keys off app attribution.
     * "direct" (default) → the direct outbound; "proxy" → the proxy exit.
     */
    // Default PROXY: a fresh install has no rules, so whatever this says
    // IS the routing for all traffic. Direct-by-default meant a new user
    // "connected" and every packet bypassed the proxy — the tunnel looked
    // up while nothing was actually proxied (user report: connected but
    // nothing loads). Proxy-through-the-load-balancer is what "connect"
    // promises; direct remains one tap away in the fallback card.
    val fallbackRouteMode: String = "proxy",
    /**
     * One-time migration marker (see AppStateStore.load): the fallback
     * default flipped direct→proxy, and pre-existing saved states carry
     * the old value. Bumped once; never reused.
     */
    val routingFallbackMigration: Int = 0,

    /**
     * Load balancing for the single built-in proxy exit group (the outbound
     * group editor is gone): strategy, top-N (use the N fastest nodes
     * concurrently), and the sticky-session TTL.
     */
    val lbStrategy: String = "round-robin",
    val lbTopN: Int = 1,
    val lbTtl: String = "",
    // Hash dimensions for consistent-hashing / sticky-sessions (the
    // kernel's loadbalance outbound hash_key). Destination is always part
    // of the key (eTLD+1 domain first, else the resolved IP); these extend
    // it. Inert under round-robin, which hashes nothing.
    val lbHashSourceIp: Boolean = false,
    val lbHashDestinationIp: Boolean = false,
    val lbHashPort: Boolean = false,
    val lbHashProtocol: Boolean = false,
    /**
     * Reject broken IPv6 (built-in): when the default interface has no
     * public IPv6 (2000::/3), reject all IPv6-destined connections instead
     * of letting them time out on a dead route (a logical AND rule:
     * ip_version=6 AND NOT default_interface_address 2000::/3, no_drop).
     */
    val rejectBrokenIpv6: Boolean = false,
    /**
     * Lightweight TUN mode: forward the VPN interface through hev-socks5-
     * tunnel into a loopback SOCKS5 server instead of the full sing-box
     * engine. Skips the rule engine — every connection goes to the node
     * the tunnel's SOCKS5 server is bound to. Requires the core's local
     * socks5 inbound (see [enableLocalSocks5]).
     */
    val hevTunMode: Boolean = false,
    /**
     * Expose local socks5 / HTTP proxy inbounds on the loopback interface so
     * other apps (or the same device via 127.0.0.1) can use AIBox without
     * the TUN. Traffic rides the same rules and DNS as the tunnel.
     */
    val enableLocalSocks5: Boolean = false,
    val localSocks5Port: Int = 2081,
    val enableLocalHttp: Boolean = false,
    val localHttpPort: Int = 2082,
    /**
     * Auto-start/stop of the local HTTP/SOCKS5 inbounds: when any of these
     * packages has a live connection, the inbounds are switched on; after
     * [localProxyHoldMs] without one, switched off. Empty = no auto
     * trigger — the inbounds follow [enableLocalSocks5]/[enableLocalHttp]
     * manually as before.
     */
    val localProxyAutoTrigger: Boolean = false,
    val localProxyTriggerPackages: List<String> = emptyList(),
    val localProxyHoldMs: Long = 60_000L,
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
    /**
     * Per-exit fallback resolver (see [finalDnsServer] for the value
     * grammar). Key = "proxy" or "direct": when a query's connection falls
     * through to the route fallback ([fallbackRouteMode]), the DNS fallback
     * for THAT exit applies — e.g. the direct fallback can be the ISP
     * resolver while the proxy fallback is a remote DoH. An exit without
     * an entry falls back to [finalDnsServer].
     */
    val finalDnsServerByExit: Map<String, String> = emptyMap(),

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
     * Fake-IP scope (user-facing choice, three values):
     *  - [FakeIpScopeAll] — every lookup is faked (the sing-box default).
     *  - [FakeIpScopeProxyOnly] — only domains that route rules send to a
     *    proxy get fake addresses; direct-bound domains resolve normally
     *    (auto exclusion list from the direct-target route rules).
     *  - [FakeIpScopeDirectOnly] — the inverse: only direct-bound domains
     *    get fake addresses, everything headed for a proxy resolves
     *    normally (proxy-bound matchers become the exclusion list).
     *
     * The user's [fakeIpFilter] list always merges on top of whichever
     * auto list the scope generates.
     */
    val fakeIpScope: String = FakeIpScopeAll,
    /**
     * When true, [fakeIpFilter] is an exclusion list instead of an inclusion
     * list: matching domains bypass the fake pool and are resolved normally.
     */
    val fakeIpFilterExclude: Boolean = false,
    /** IPv4 fake range; empty = sing-box default (198.18.0.0/15). */
    val fakeIpInet4Range: String = "",
    /** IPv6 fake range; empty = sing-box default (fc00::/18). */
    val fakeIpInet6Range: String = "",
    /**
     * Block HTTPS/SVCB records (RFC 9460) while fake-IP is on: an empty
     * NOERROR is answered for HTTPS-type queries so apps fall back to
     * plain A/AAAA, and the HTTPS blobs never leak to the fallback DNS
     * (which cannot resolve fake-IP domains usefully).
     */
    val fakeIpBlockHttps: Boolean = false,

    // ------------------------------------------------------------- sniffer
    val enableSniffer: Boolean = true,
    val snifferProtocols: List<String> = listOf("http", "tls", "quic"),
    val snifferTimeout: String = "1s",

    // ---------------------------------------------------------------- tun
    val enableIpv6: Boolean = false,
    /**
     * Address-family policy applied to DNS answers and outbound dialing
     * when [enableIpv6] is on: how the resolver picks between A and AAAA.
     * Values match the core's domain strategy (as_is / prefer_ipv4 /
     * prefer_ipv6 / ipv4_only / ipv6_only).
     */
    val ipFamilyPolicy: String = "prefer_ipv6",
    /**
     * Runtime downgrade: when the active network has no usable IPv6, the
     * compiler is asked to fall back to the IPv4 preference automatically
     * and remember why (see NetworkMonitor).
     */
    val ipv6FallbackActive: Boolean = false,
    /**
     * ECS auto-fill: when a name resolves through the proxy (a proxied DNS
     * server or a resolve action on a proxy-routed rule) and no explicit
     * client_subnet is set, present the exit node's address as the EDNS
     * client subnet so geo-aware answers match the exit location.
     */
    val autoEcsFromNode: Boolean = true,
    /**
     * Fixed ECS for direct-bound lookups when the rules table ends in the
     * fallback (direct mode). Rule-level and global subnets win over it, so
     * a rule that pins its own ECS never collides; empty = no fixed subnet
     * and the global/node chain applies. Proxy-mode fallback keeps the
     * node-address ECS from [autoEcsFromNode] — the exit's locality.
     */
    val fallbackEcsDirect: String = "",
    /** Node address resolved at connect time, used by [autoEcsFromNode]. */
    val nodeEcsAddress: String = "",
    val tunMtu: Int = 9000,
    /** TUN interface IPv4 address (CIDR). */
    val tunInet4Address: String = "172.19.0.1/30",
    /** TUN interface IPv6 address (CIDR). Used when [enableIpv6]. */
    val tunInet6Address: String = "fdfe:dcba:9876::1/126",
    /** DNS server(s) advertised to the OS; empty = "auto" (tun address). */
    val tunDnsAddresses: List<String> = emptyList(),
    /**
     * Carry UDP inside the VLESS TCP stream (packet-addr encoding, the
     * XrayNG-style UDP-over-TCP). Applied to vless outbounds at compile
     * time unless the node config sets its own packet_encoding.
     */
    val udpOverTcp: Boolean = false,

    // ------------------------------------------------------------- engine
    val logLevel: String = "warn",
    val appendLogToFile: Boolean = true,

    // ------------------------------------------------------------- mux
    /**
     * Multiplex every eligible outbound over one connection.
     *
     * VLESS nodes that carry a `flow` value are exempt: the kernel rejects
     * mux combined with flow (`flow` pins a distinct stream style), so those
     * nodes always dial their own connection.
     */
    val muxEnabled: Boolean = false,
    /** [MuxProtocolH2mux] | [MuxProtocolSmux] | [MuxProtocolYamux]. */
    val muxProtocol: String = MuxProtocolH2mux,
    /** Aggregate cap on concurrent multiplexed connections; 0 = engine default. */
    val muxMaxConnections: Int = 0,
    /** Streams below this count open a new connection early; 0 = default. */
    val muxMinStreams: Int = 0,
    /** Hard cap on concurrent streams per connection; 0 = default. */
    val muxMaxStreams: Int = 0,
    /** Pad mux frames to obscure traffic shape (h2mux/yamux). */
    val muxPadding: Boolean = false,
    /** Brutal congestion control, an alternative to BBR on lossy links. */
    val muxBrutalEnabled: Boolean = false,
    val muxBrutalUpMbps: Int = 0,
    val muxBrutalDownMbps: Int = 0,

    // ------------------------------------------------- keepalive & probing
    /**
     * TCP keep-alive idle time for outbound connections, seconds; 0 = the
     * kernel default. Longer values cut needless keep-alive wake-ups on
     * idle links; shorter ones detect dead peers sooner.
     */
    val tcpKeepAliveIdleSeconds: Int = 0,
    /**
     * Node health probing: run a url-test pass over every group each
     * [healthCheckIntervalMinutes] minutes while the tunnel is up, so the
     * delay column stays fresh and auto groups re-select on degradation.
     * 0 = off (probes happen only on manual refresh).
     */
    val healthCheckIntervalMinutes: Int = 0,
    /** Per-probe timeout for the manual/health checks, milliseconds. */
    val speedTestTimeoutMs: Int = 5_000,

    // ----- QUIC compatibility (experimental) -----
    /**
     * Disable QUIC GSO (generic segmentation offload) inside the kernel.
     * Some SoC/kernel combos stall or add latency with GSO; the kernel
     * reads this at every QUIC connection creation.
     */
    val quicDisableGso: Boolean = false,
    /**
     * Disable QUIC explicit congestion notification (ECN). Some carrier
     * NATs drop ECN-marked packets, stalling QUIC sessions.
     */
    val quicDisableEcn: Boolean = false,
    /** Persist rule-set / fake-IP caches to disk (experimental.cache_file). */
    val enableCacheFile: Boolean = true,
    /**
     * DPI hardening for TLS outbounds (VLESS+Reality/TLS). `none` = off;
     * `record` splits the ClientHello into TLS records (cheap, preferred);
     * `packet` splits it across TCP segments (stronger against SNI
     * regexes, slower — for censored networks only).
     */
    val tlsFragmentMode: String = "none",
    val tlsFragmentFallbackDelay: String = "",

    // ------------------------------------------------------- privacy guards
    /**
     * Applies FLAG_SECURE to the engine surfaces: the recents thumbnail,
     * screen recordings and non-system overlays cannot capture node lists,
     * subscriptions or live traffic. Mirrors the rsxm-security slice
     * (`blockScreenshots`); mikuRay-style fail-closed privacy posture.
     */
    val blockScreenshots: Boolean = false,
    /**
     * Allows plaintext DNS upstreams (udp/tcp/dhcp). Off by default so the
     * rsxm-security leader keeps every DNS query encrypted (DoT/DoH/DoQ);
     * flip on only when the local network requires its resolver.
     */
    val allowInsecureDns: Boolean = false,
    /**
     * SNTP sync inside the core. Reality and SS2022 both reject handshakes
     * outside a narrow clock window; a drifting device clock otherwise
     * reads as "the node broke".
     */
    val enableNtp: Boolean = true,
    val ntpServer: String = "time.apple.com",

    // ---------------------------------------------------------- per-app
    val perAppProxyEnabled: Boolean = false,
    /** true = only selected apps proxied (whitelist); false = selected apps bypass (blacklist). */
    val perAppProxyWhitelist: Boolean = false,
    val perAppProxyPackages: Set<String> = emptySet(),

    // ------------------------------------------------- experimental flags
    /**
     * Feature flags ported from the reference clients (v2rayNG, FlClash,
     * karing, nekobox, CMFA, throne, mikuRay). Each stays dormant until
     * enabled here — nothing applies to any page or to the compiled config
     * unless both [ExperimentalFeatures.enabled] and the per-feature flag
     * are on. The gates live in the compiler / UI call sites, not here.
     */
    val experimental: ExperimentalFeatures = ExperimentalFeatures(),

    // ----------------------------------------------------------------- ui
    val colorMode: Int = ColorModeSystem,
    /** "" = follow system, otherwise a BCP-47-ish tag: "en", "zh". */
    val language: String = "",
    val speedTestUrl: String = "https://cp.cloudflare.com/generate_204",

    // ------------------------------------------------------------ autostart
    /**
     * Boot autostart (user opt-in): re-establish the tunnel after a reboot
     * or app update when it was running before. The VPN consent grant is
     * persisted by the system, so no activity is needed.
     */
    val bootAutoStart: Boolean = false,
    /**
     * Mirrors "the tunnel is connected" so [BootReceiver] can tell a reboot
     * that killed an active tunnel from one where the user had switched off.
     * Written by the VPN service on state transitions.
     */
    val vpnWasRunning: Boolean = false,

    // ------------------------------------------------- network policy (box)
    /**
     * SSID-based tunnel policy (reference client's network control): when
     * non-empty, the tunnel state follows the matched network — disconnect
     * on a blacklisted SSID, connect-level enforcement on a whitelisted one.
     * Empty = no SSID policy.
     */
    val ssidPolicyMode: String = "",
    /** SSIDs the policy applies to. */
    val ssidPolicyList: List<String> = emptyList(),

    // ------------------------------------------------------- download aids
    /**
     * Optional GitHub access token for rule-set/subscription downloads that
     * hit api.github.com rate limits (60/hr unauthenticated). Stored in the
     * same state file as everything else — device-local.
     */
    val githubToken: String = "",
    /**
     * Mirror prefix for GitHub downloads (raw.githubusercontent.com / 
     * github.com release URLs), e.g. "https://ghfast.top". Empty = direct.
     * Reference client's use_ghproxy/url_ghproxy pair.
     */
    val githubMirror: String = "",
)

const val ClashModeRule = "Rule"
const val ClashModeGlobal = "Global"
const val ClashModeDirect = "Direct"
val ClashModes = listOf(ClashModeRule, ClashModeGlobal, ClashModeDirect)

const val ColorModeSystem = 0
const val ColorModeLight = 1
const val ColorModeDark = 2

const val ProxySelectorTag = "proxy"
const val LoadBalanceTag = "lb"
const val DirectOutboundTag = "direct"
const val BlockOutboundTag = "block"
const val DnsOutboundTag = "dns-out"
const val TunInboundTag = "tun-in"
const val FakeIpServerTag = "dns-fakeip"

/**
 * Built-in final-DNS shortcuts (see [AppState.finalDnsServer]): resolve
 * through the main proxy selector's exit, or straight over the local
 * network — without pinning a specific server tag.
 */
const val DnsFinalProxy = "final:proxy"
const val DnsFinalDirect = "final:direct"
const val DnsFinalReject = "final:reject"

/** FakeIP scope values (see [AppState.fakeIpScope]). */
const val FakeIpScopeAll = "all"

/** [AppState.fallbackRouteMode]: rules-list fallback goes direct or proxy. */
const val FallbackRouteDirect = "direct"
const val FallbackRouteProxy = "proxy"
const val FakeIpScopeProxyOnly = "proxyOnly"
const val FakeIpScopeDirectOnly = "directOnly"

/** Languages the UI can be switched to; "" means "follow system". */
val LanguageOptions = listOf("", "en", "zh")

val SnifferProtocolOptions = listOf(
    "http", "tls", "quic", "stun", "dns", "bittorrent", "dtls", "ssh", "rdp", "ntp",
)

val SingBoxLogLevels = listOf("trace", "debug", "info", "warn", "error", "fatal", "panic")

// ----- multiplex -----

const val MuxProtocolH2mux = "h2mux"
const val MuxProtocolSmux = "smux"
const val MuxProtocolYamux = "yamux"

val MuxProtocols = listOf(MuxProtocolH2mux, MuxProtocolSmux, MuxProtocolYamux)

/**
 * DNS server transport types. `local` uses the OS resolver; `direct` forces
 * the request through the direct outbound; everything else maps to the
 * sing-box server type of the same name.
 */
// local/direct are compiler-synthesized (final:proxy / final:direct
// shortcuts) and never user-selectable; hosts is a local table, not a
// network server, but stays here because its entries are configured
// per-server in the form card.
val DnsServerTypes = listOf("hosts", "udp", "tcp", "tls", "https", "quic", "h3", "group")

/**
 * DNS hosts entries for the `hosts` transport: domain → IP (one per line
 * in the UI, `domain=ip`). The kernel answers these locally.
 */
val DnsServerTypesWithHosts = DnsServerTypes

val DnsStrategies = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")

/** A single proxy node. `config` holds the sing-box outbound JSON object (tag injected at build). */
@Serializable
data class OutboundProfile(
    val id: String,
    val name: String,
    val type: String,
    val config: String,
    val subscriptionId: String? = null,
    /**
     * User edits merged over the subscription-provided config at compile
     * time, so a refresh does not wipe hand-made changes. A JSON object
     * whose keys override the node's top-level fields (server, port,
     * uuid, tls.*, …). Empty = no override.
     */
    val override: String = "",
    /** True when the user edited [override]; such nodes skip auto-name. */
    val edited: Boolean = false,
    /**
     * Per-node domain resolution strategy for dialing the node's own
     * server address (mikuRay's "domain strategy"): as_is / prefer_ipv4 /
     * prefer_ipv6 / ipv4_only / ipv6_only. Empty = follow the global
     * default_domain_resolver.
     */
    val domainStrategy: String = "",
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
    // Panel traffic accounting (the standard `subscription-userinfo`
    // response header, ClashMeta/v2rayNG style). All zero = the panel did
    // not report; the card then hides the usage row entirely.
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Epoch millis when the plan expires; 0 = unknown/unlimited. */
    val expireEpochMillis: Long = 0,
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
    /**
     * Custom resolver address entered in the subscription editor when none
     * of the saved DNS servers fit. Only DoH endpoints (https:// or h3) can
     * be queried without a full DNS stack; other values fall back to the
     * system resolver. Empty = use [dnsServer] / system.
     */
    val customDnsServer: String = "",
    /** Drop duplicate nodes (same type + server + port + credentials) on import. */
    val deduplicate: Boolean = true,
    /**
     * User-Agent presented to the panel. Panels increasingly serve
     * different node sets per client, and some reject unknown agents
     * outright. [UserAgentSingBox] is the safest default.
     */
    val userAgent: String = UserAgentSingBox,
    /**
     * TLS fingerprint to present while fetching. On Android the platform
     * stack is BoringSSL-backed and already Chrome-like; the value is
     * persisted so a custom TLS provider can honour it later.
     */
    val tlsFingerprint: String = FingerprintChrome,
    /**
     * Send a stable per-install identifier in the `x-hwid` header. Some
     * panels bind a subscription to the first device that fetched it and
     * serve an empty list to everyone else; masking a consistent fake
     * device id keeps re-installs working.
     */
    val maskHwid: Boolean = true,
    /**
     * Rewrite every tls-enabled vless node from this subscription to speak
     * ECH (encrypted client hello). Empty [echQueryServerName] lets the
     * core fetch the ECH config list itself from the node's DNS HTTPS
     * record; a value pins that lookup to an explicit name.
     */
    val enableEch: Boolean = false,
    val echQueryServerName: String = "",
    /** Explicit base64 ECH config list; empty = core auto-fetches. */
    val echConfig: String = "",
    /**
     * Auto-refresh interval in hours; 0 = manual only. The UI offers presets
     * (off / 12h / 24h / 72h); the fetcher checks staleness lazily.
     */
    val updateIntervalHours: Int = 0,
    /**
     * Add this subscription's host as a domain-suffix route rule, steering
     * the panel's traffic by the chosen fetch mode: proxy → the main
     * selector, direct (and auto) → direct. The rule is materialised into
     * [AppState.routeRules] at save/refresh time with [RulePresets]-style
     * managed ids so it stays visible and removable.
     */
    val routeBySuffix: Boolean = false,
    /**
     * User override of the display name; empty = derived from the panel's
     * `profile-title` header (or the URL host) on first successful fetch.
     */
    val autoName: Boolean = true,
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

// ----- client impersonation for subscription fetches -----

const val UserAgentSingBox = "singbox"
const val UserAgentMihomo = "mihomo"
const val UserAgentFlClash = "flclash"
const val UserAgentV2rayNG = "v2rayng"
const val UserAgentClashMeta = "clashmeta"

val UserAgentOptions = listOf(
    UserAgentSingBox, UserAgentMihomo, UserAgentFlClash, UserAgentV2rayNG, UserAgentClashMeta,
)

/** Wire form of [UserAgentOptions] — what actually goes out on the header. */
fun userAgentHeader(choice: String): String = when (choice) {
    UserAgentMihomo -> "mihomo/1.19.11"
    UserAgentFlClash -> "FlClash/0.8.80"
    UserAgentV2rayNG -> "v2rayNG/1.10.11"
    UserAgentClashMeta -> "clash-meta/1.19.11"
    else -> "sing-box/1.14.0"
}

const val FingerprintAuto = "auto"
const val FingerprintChrome = "chrome"
const val FingerprintIOS = "ios"
const val FingerprintFirefox = "firefox"
const val FingerprintEdge = "edge"

val FingerprintOptions = listOf(
    FingerprintAuto, FingerprintChrome, FingerprintIOS, FingerprintFirefox, FingerprintEdge,
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
    /**
     * How this rule's own conditions combine.
     *
     * "" (legacy) / [CombineAnd] — the kernel-native default rule: ordinary
     * items (package, port, …) are AND'ed, the destination-address family
     * forms one OR group.
     *
     * [CombineOr] — every condition family is OR'ed: matching any of the
     * listed domains, packages, ports, … is enough. Compiles to a nested
     * logical OR so the two shapes stay distinguishable.
     *
     * New sub-rules default to [CombineOr]; existing rules keep "" so their
     * compiled behaviour does not change under them.
     */
    val combine: String = "",
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
    /**
     * Node filter for proxy-exit rules: when non-empty, the compiler builds
     * a urltest/loadbalance group from exactly these node tags instead of
     * the global selector — karing's "pick which nodes serve this rule".
     */
    val nodeFilter: List<String> = emptyList(),
    /** EDNS client subnet for the resolve action, e.g. "1.2.3.0/24". Empty = off. */
    val clientSubnet: String = "",
    /**
     * Destination override for this rule (kernel route action's
     * override_address/override_port): rewrite where the connection goes
     * after the match fires. Formats: "host", "host:port", or a bare port
     * number ("8443" = keep the original host, change the port only).
     * Empty = no rewrite.
     */
    val overrideAddress: String = "",
    /**
     * DNS linkage, lxbox-style: when [syncDnsServer] is set, the compiler
     * derives a DNS rule from this route rule's domain matchers at compile
     * time — the domain list steers DNS without duplicating entries in the
     * DNS rule list. IP-only matchers never produce one (they match on
     * resolved addresses, not names).
     */
    val syncDnsServer: String = "",
    /**
     * Per-rule address-family filter. `""` = inherit the global policy;
     * otherwise `ipv4_only` / `ipv6_only` / `both` — with `both` honouring
     * [ipPreference] when the user wants a bias inside the rule.
     */
    val ipFamily: String = "",
    /** Bias used when [ipFamily] = "both": "prefer_ipv6" or "prefer_ipv4". */
    val ipPreference: String = "prefer_ipv6",
    // ----- json kind -----
    val json: String = "",
    /**
     * Set when a built-in preset created this rule (see RulePresets). Lets
     * the routes page show the preset as installed and remove its rules in
     * one tap; null for hand-made rules.
     */
    val presetId: String? = null,
) {
    val isLogical: Boolean get() = type == RuleTypeLogical

    companion object {
        const val KindInline = "inline"
        const val KindJson = "json"
        const val RuleTypeDefault = "default"
        const val RuleTypeLogical = "logical"
        const val LogicalAnd = "and"
        const val LogicalOr = "or"
        const val CombineAnd = "and"
        const val CombineOr = "or"
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
    /**
     * Idle timeout, e.g. "30m" — how long an idle group stops probing
     * itself. The kernel rejects `interval > idle_timeout` at group start;
     * the compiler raises this automatically when needed (post step).
     * Empty = engine default (30m).
     */
    val idleTimeout: String = "",
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
    /** UrlTest only: one shared delay number for the group instead of per-node. */
    val unifiedDelay: Boolean = false,
    /**
     * Include/exclude alias filter (reference client's group member
     * filtering): a regex matched against each member's display name when
     * the group compiles. Both empty = all members.
     */
    val includeRegex: String = "",
    val excludeRegex: String = "",
    /**
     * Load-balance strategy (kernel `loadbalance` outbound): round-robin /
     * consistent-hashing (same domain sticks to the same node) /
     * sticky-sessions (same source+destination sticks until TTL). Empty =
     * not a load-balance group.
     */
    val lbStrategy: String = "",
    /**
     * Load-balance TTL for sticky sessions, e.g. "1h". Empty = engine default.
     */
    val lbTtl: String = "",
    /**
     * Top-N load balancing: use the N fastest nodes simultaneously (by the
     * kernel's urltest ordering). 0 or 1 = single fastest node. This is the
     * "允许同时使用 n 个节点" behaviour: N nodes serve traffic concurrently.
     */
    val lbTopN: Int = 0,
) {
    val tag: String get() = "group-$id"

    companion object {
        const val KindSelector = "selector"
        const val KindUrlTest = "urltest"
        const val ModeLeastTest = "least_test"
        const val ModeFallback = "fallback"
        const val ModeRoundRobin = "round_robin"
        const val ModeLoadBalance = "loadbalance"
    }
}

val OutboundGroupKinds = listOf(OutboundGroup.KindSelector, OutboundGroup.KindUrlTest)

/**
 * Balance modes for urltest groups.
 *
 * [OutboundGroup.ModeFallback] approximates the "use the first working
 * node, switch only when it dies" behaviour other clients call a fallback
 * group: the kernel keeps the current node unless the probe failure list
 * forces a change, which a long interval plus a wide tolerance produces.
 * sing-box has no dedicated fallback outbound type, so this compiles to a
 * urltest group with those settings rather than pretending otherwise.
 */
val OutboundGroupModes = listOf(
    OutboundGroup.ModeLeastTest,
    OutboundGroup.ModeFallback,
    OutboundGroup.ModeRoundRobin,
    OutboundGroup.ModeLoadBalance,
)

/** Kernel loadbalance strategies (protocol/group/loadbalance.go). */
const val LbStrategyRoundRobin = "round-robin"
const val LbStrategyConsistentHashing = "consistent-hashing"
const val LbStrategyStickySessions = "sticky-sessions"

val LbStrategies = listOf(LbStrategyRoundRobin, LbStrategyConsistentHashing, LbStrategyStickySessions)

/** Domain resolution strategies for outbound dialing (mikuRay's list). */
val DomainStrategyOptions = listOf(
    "", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only",
)

val StickyHashComponents = listOf("process", "domain", "source_ip", "dest_ip", "dest_port")

/** Subscription auto-update presets: hours → label resource. */
val UpdateIntervalOptions: List<Pair<Int, Int>> = listOf(
    0 to R.string.subs_interval_off,
    12 to R.string.subs_interval_12h,
    24 to R.string.subs_interval_24h,
    72 to R.string.subs_interval_72h,
)

/** Node health probe interval presets: minutes → label resource. */
val ProbeIntervalOptions: List<Pair<Int, Int>> = listOf(
    0 to R.string.subs_interval_off,
    5 to R.string.probe_5m,
    15 to R.string.probe_15m,
    30 to R.string.probe_30m,
    60 to R.string.probe_60m,
)

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
    /** hosts transport: `domain=ip` entries answered locally. */
    val hostsEntries: List<String> = emptyList(),
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
    /** EDNS client subnet applied by this rule's action, e.g. "1.2.3.0/24". Empty = off. */
    val clientSubnet: String = "",
    /** Match DNS responses with these rcodes (NOERROR, NXDOMAIN, SERVFAIL…). */
    val responseRcode: List<String> = emptyList(),
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
    "SVCB", "RT", "NAPTR", "KX", "CERT", "DNAME", "APL", "DS", "SSHFP",
    "IPSECKEY", "RRSIG", "NSEC", "DNSKEY", "NSEC3", "NSEC3PARAM", "TLSA",
    "SMIMEA", "HIP", "CDS", "CDNSKEY", "OPENPGPKEY", "CSYNC", "ZONEMD",
    "SVCB-HTTPS", "SPF", "CAA", "DLV", "ANY",
)

/** DNS response rcodes accepted by the core's response_rcode matcher. */
val DnsResponseRcodes = listOf(
    "NOERROR", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "REFUSED", "BADVERS",
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

/**
 * Feature gates ported from the reference clients (v2rayNG, FlClash,
 * karing, nekobox, CMFA, throne, mikuRay). Each stays dormant until the
 * user enables it here — nothing applies to any page or to the compiled
 * config unless both [enabled] and the per-feature flag are on. Gates are
 * checked at the compiler / UI call sites, never inside the data model.
 */
@kotlinx.serialization.Serializable
data class ExperimentalFeatures(
    /** Master switch: when false every individual flag is ignored. */
    val enabled: Boolean = false,

    /** v2rayNG/FlClash/CMFA: TUN per-app include/exclude package lists. */
    val perAppProxy: Boolean = false,

    /** FlClash/CMFA/v2rayNG: quick-settings tile to connect/disconnect. */
    val quickTile: Boolean = false,

    /** v2rayNG: one-tap url-test over every node, sorted by latency. */
    val batchSpeedTest: Boolean = false,

    /** nekobox/karing: persistent per-node latency history (cache file). */
    val latencyHistory: Boolean = false,
)

/**
 * A cheap fingerprint over every field the config compiler consumes. The
 * VPN process watches this: when it changes while the tunnel is up, the
 * running config is stale and a reload (recompile + swap) applies the
 * edits live — no disconnect/reconnect dance. UI-only fields (theme,
 * language, screenshot lock…) are deliberately excluded so flipping them
 * never bounces the tunnel.
 */
fun AppState.configFingerprint(): Int = listOf(
    outbounds, subscriptions, subscriptionGroups, outboundGroups,
    routeRules, ruleSets, dnsServers, dnsRules,
    enableFakeIp, fakeIpScope, fakeIpFilter, fakeIpFilterExclude,
    fakeIpInet4Range, fakeIpInet6Range, fakeIpBlockHttps,
    hijackDns, enableSniffer, snifferProtocols, snifferTimeout,
    selectedOutbound, unknownTrafficOutbound, fallbackRouteMode,
    finalDnsServerByExit, fallbackEcsDirect,
    dnsClientSubnet, dnsStrategy, dnsIndependentCache, dnsCacheCapacity,
    lbStrategy, lbTopN, lbTtl, lbHashSourceIp, lbHashDestinationIp,
    lbHashPort, lbHashProtocol,
    muxProtocol, muxMaxConnections, muxMinStreams, muxMaxStreams,
    muxPadding, muxBrutalEnabled, muxBrutalUpMbps, muxEnabled,
    enableIpv6, ipFamilyPolicy,
    tunMtu, tunInet4Address, tunInet6Address, tunDnsAddresses,
    perAppProxyEnabled, perAppProxyWhitelist, perAppProxyPackages,
    healthCheckIntervalMinutes, speedTestUrl,
    udpOverTcp, tcpKeepAliveIdleSeconds,
    enableNtp, ntpServer, quicDisableGso, quicDisableEcn,
    enableLocalSocks5, localSocks5Port, enableLocalHttp, localHttpPort,
    autoEcsFromNode, nodeEcsAddress,
    experimental,
).hashCode()
