package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.ClashModeDirect
import com.leadaxe.aibox.app.ClashModeGlobal
import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.app.DnsOutboundTag
import com.leadaxe.aibox.app.DnsRule
import com.leadaxe.aibox.app.DnsRuleActionReject
import com.leadaxe.aibox.app.DnsRuleActionRouteOptions
import com.leadaxe.aibox.app.DnsServerState
import com.leadaxe.aibox.app.FakeIpServerTag
import com.leadaxe.aibox.app.MuxProtocolH2mux
import com.leadaxe.aibox.app.OutboundGroup
import com.leadaxe.aibox.app.ProxySelectorTag
import com.leadaxe.aibox.app.RouteRule
import com.leadaxe.aibox.app.TunInboundTag
import com.leadaxe.aibox.app.defaultUrlTestInterval
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Compiles [AppState] into a complete sing-box configuration document.
 *
 * Layout mirrors the proven AsteriskBOX / LxBox-Flutter compiler: managed
 * rules first, then the injected rules (sniff / hijack-dns), then clash-mode
 * shortcuts, and finally the catch-all `final` outbound.
 *
 * DNS is the other half of the picture: [compileDns] emits the server list
 * (including a fake-IP pool when enabled), the user's DNS rules, and the
 * `final` fallback server. `default_domain_resolver` ties the route resolver
 * back to the first concrete DNS server so sing-box can resolve node
 * hostnames without a chicken-and-egg deadlock.
 */
object ConfigCompiler {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Localhost port of the mixed inbound used for subscription fetching.
     * Fixed so [com.leadaxe.aibox.engine.share.SubscriptionFetcher] can dial
     * it without having to ask the box at runtime. 0 = the last compiled
     * config did not include the inbound (no box running / older config).
     */
    @Volatile
    private var localProxyPort: Int = 0

    /** Port handed to the fetcher; null when no box is running. */
    fun currentLocalProxyPort(): Int? = localProxyPort.takeIf { it > 0 }

    private fun compileLocalProxyInbound(): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", LocalProxyInboundTag)
        put("listen", "127.0.0.1")
        put("listen_port", SubscriptionFetchPort)
        // No sniffing here — this inbound only carries the app's own
        // subscription fetches, which are plain HTTP(S) to the panel.
    }

    /**
     * @param ruleSetDir directory where downloaded rule-set caches live
     *        (`<id>.srs` / `<id>.json`).
     */
    fun compile(state: AppState, ruleSetDir: File): JsonObject {
        val raw = compileInner(state, ruleSetDir)
        // Post steps (ported from the reference client's 2.24.0): repair
        // shapes the kernel rejects at start or mis-serves. Both return the
        // repaired config; the repair lists feed the compile log.
        val (fingerprintHealed, fingerprintNotes) = ConfigPostSteps.healUtlsFingerprints(raw)
        val (timingsHealed, timingNotes) = ConfigPostSteps.sanitizeUrltestTimings(fingerprintHealed)
        if (fingerprintNotes.isNotEmpty() || timingNotes.isNotEmpty()) {
            android.util.Log.w(
                "ConfigPostSteps",
                "utls repairs: $fingerprintNotes; urltest timing repairs: $timingNotes",
            )
        }
        return timingsHealed
    }

    private fun compileInner(state: AppState, ruleSetDir: File): JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("level", state.logLevel)
            put("timestamp", true)
        }
        if (state.enableCacheFile) {
            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("path", File(ruleSetDir, "cache.db").absolutePath)
                    if (state.enableFakeIp) put("store_fakeip", true)
                }
            }
        }
        put("dns", compileDns(state, ruleSetDir))
        if (state.enableNtp) {
            // Reality session tickets and SS2022's ±30 s replay window both
            // derive from wall-clock time; a drifted device clock shows up
            // as "node suddenly stopped working". The core syncs via SNTP
            // itself when enabled.
            putJsonObject("ntp") {
                put("enabled", true)
                put("server", state.ntpServer.ifBlank { "time.apple.com" })
                put("server_port", 123)
                put("interval", "30m")
                put("detour", DirectOutboundTag)
            }
        }
        putJsonArray("inbounds") {
            add(compileTunInbound(state))
            // Subscription fetches ride the same DNS/routing rules as normal
            // traffic when the user picks "proxy" (or when auto falls back).
            add(compileLocalProxyInbound())
            state.let {
                if (it.enableLocalSocks5) add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "local-socks5")
                    put("listen", "127.0.0.1")
                    put("listen_port", it.localSocks5Port.coerceIn(1024, 65535))
                })
                if (it.enableLocalHttp) add(buildJsonObject {
                    put("type", "http")
                    put("tag", "local-http")
                    put("listen", "127.0.0.1")
                    put("listen_port", it.localHttpPort.coerceIn(1024, 65535))
                })
            }
        }
        localProxyPort = SubscriptionFetchPort
        putJsonArray("outbounds") { compileOutbounds(state).forEach(::add) }
        putJsonObject("route") {
            putJsonArray("rules") { compileRouteRules(state).forEach(::add) }
            val ruleSets = compileRuleSets(state, ruleSetDir)
            if (ruleSets.isNotEmpty()) {
                putJsonArray("rule_set") { ruleSets.forEach(::add) }
            }
            // sing-box 1.12+: resolvers for outbound server names must be
            // pinned, otherwise a node hostname lookup has no route to go
            // through when the proxy chain is the only way out.
            val resolverTag = pickDefaultResolver(state)
            if (resolverTag != null) put("default_domain_resolver", resolverTag)
            // "Unknown traffic" exit: the route `final` catch-all. Empty =
            // the main proxy selector.
            put("final", state.unknownTrafficOutbound.ifBlank { ProxySelectorTag })
            put("auto_detect_interface", true)
        }
    }

    // ------------------------------------------------------------------ dns

    private fun compileDns(state: AppState, ruleSetDir: File): JsonObject = buildJsonObject {
        // Fail-closed (upstream §443): a DNS server whose detour points at an
        // outbound that does not exist makes the kernel fail at start
        // ("outbound detour not found"). Instead of dying, drop the server
        // and let the sanitiser below redirect whatever pointed at it —
        // queries must never silently fall to the system resolver behind a
        // censored network.
        val liveOutboundTags = buildSet {
            add(DirectOutboundTag)
            add(ProxySelectorTag)
            state.outbounds.forEach { add(it.tag) }
            state.outboundGroups.filter { it.enabled }.forEach { add(it.tag) }
        }
        val usableServers = state.dnsServers.filter { server ->
            server.enabled && (server.detour.isBlank() || server.detour in liveOutboundTags)
        }
        putJsonArray("servers") {
            usableServers.forEach { server ->
                add(compileDnsServer(server))
            }
            if (state.enableFakeIp) {
                add(compileFakeIpServer(state))
            }
        }
        val finalServer = pickFinalServer(state, usableServers)
        val rules = compileDnsRules(state, finalServer)
        if (rules.isNotEmpty()) {
            putJsonArray("rules") { rules.forEach(::add) }
        }
        // Fail-closed last line: when servers were dropped, append an
        // unconditional reject so a query that survives every rule fails
        // loudly instead of leaking to the first (possibly system) server.
        if (usableServers.size < state.dnsServers.count { it.enabled }) {
            putJsonArray("rules") {
                add(buildJsonObject { put("action", "reject") })
            }
        }
        // Global family policy: the explicit DNS strategy wins; otherwise
        // the IPv6 policy applies (with a runtime downgrade to IPv4 when the
        // active network turned out to have no usable IPv6).
        val globalStrategy = state.dnsStrategy.ifBlank {
            when {
                !state.enableIpv6 -> ""
                state.ipv6FallbackActive -> "prefer_ipv4"
                state.ipFamilyPolicy == "as_is" -> ""
                state.ipFamilyPolicy.isNotBlank() -> state.ipFamilyPolicy
                else -> "prefer_ipv6"
            }
        }
        if (globalStrategy.isNotBlank()) put("strategy", globalStrategy)
        if (state.dnsClientSubnet.isNotBlank()) put("client_subnet", state.dnsClientSubnet)
        put("final", finalServer)
        put("independent_cache", state.dnsIndependentCache)
        if (state.dnsCacheCapacity > 0) put("cache_capacity", state.dnsCacheCapacity)
    }

    private fun compileDnsServer(server: DnsServerState): JsonObject = buildJsonObject {
        put("tag", server.tag)
        when (server.type) {
            "local" -> put("type", "local")
            "direct" -> {
                put("type", "local")
                put("detour", DirectOutboundTag)
            }
            "group" -> {
                // reF1nd kernel DNS group: fans every query out to all
                // members in parallel and answers with the fastest response
                // (`fastest response from <tag>`). There is no mode/TTL
                // config — the old sing-box-lx group semantics are gone.
                put("type", "group")
                putJsonArray("servers") { server.groupServers.forEach(::add) }
            }
            else -> {
                put("type", server.type)
                put("server", server.address)
                if (server.detour.isNotBlank()) put("detour", server.detour)
                when (server.type) {
                    "tls", "https", "quic", "h3" -> putJsonObject("tls") {
                        if (server.tlsServerName.isNotBlank()) put("server_name", server.tlsServerName)
                        if (server.insecure) put("insecure", true)
                    }
                }
            }
        }
        if (server.strategy.isNotBlank()) put("strategy", server.strategy)
        if (server.domainResolver.isNotBlank()) {
            put("domain_resolver", server.domainResolver)
        }
        if (server.clientSubnet.isNotBlank()) put("client_subnet", server.clientSubnet)
    }

    /** Built-in fake-IP pool — one server that synthesises addresses locally. */
    private fun compileFakeIpServer(state: AppState): JsonObject = buildJsonObject {
        put("tag", FakeIpServerTag)
        put("type", "fakeip")
        if (state.fakeIpInet4Range.isNotBlank()) {
            put("inet4_range", state.fakeIpInet4Range)
        } else {
            put("inet4_range", "198.18.0.0/15")
        }
        if (state.enableIpv6) {
            if (state.fakeIpInet6Range.isNotBlank()) {
                put("inet6_range", state.fakeIpInet6Range)
            } else {
                put("inet6_range", "fc00::/18")
            }
        }
    }

    private fun compileDnsRules(state: AppState, finalServer: String): List<JsonObject> = buildList {
        // Fake-IP rules run first: when the pool is on, lookups that should
        // be faked also need the "rest" routed through the normal chain, or
        // every domain ends up on the fake range and rule matching breaks.
        if (state.enableFakeIp) {
            val filter = state.fakeIpFilter.map { it.trim() }.filter { it.isNotEmpty() }
            if (filter.isEmpty()) {
                // Everything is faked; `final` still resolves for the
                // domains excluded by rule ordering.
                add(buildJsonObject { put("server", FakeIpServerTag) })
            } else if (state.fakeIpFilterExclude) {
                // Blacklist: the listed suffixes bypass the pool and go to
                // the normal chain; everything else is faked.
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { filter.forEach(::add) }
                    put("server", finalServer)
                })
                add(buildJsonObject { put("server", FakeIpServerTag) })
            } else {
                // Whitelist: only the listed suffixes are faked.
                add(buildJsonObject {
                    putJsonArray("domain_suffix") { filter.forEach(::add) }
                    put("server", FakeIpServerTag)
                })
            }
        }
        // Route-rule derived DNS rules are materialised as JSON dnsRules at
        // save time (RoutesScreen), so they appear in the DNS tab exactly
        // like any other rule — the `sync-<id>` name marks their origin.
        // Nothing is injected here any more: no duplication, and editing or
        // deleting the derived rule is the same interaction as for the
        // hand-made ones.
        // User rules.
        state.dnsRules.filter { it.enabled }.forEach { rule ->
            when (rule.kind) {
                DnsRule.KindJson -> addAll(compileJsonDnsRule(rule))
                else -> compileInlineDnsRule(rule)?.let(::add)
            }
        }
    }

    /**
     * Derives the DNS rule promised by [RouteRule.syncDnsServer]: the
     * route rule's domain-class matchers, routed to that server. Returns
     * null for IP-only rules (nothing name-based to steer).
     */
    private fun compileRouteRuleDns(rule: RouteRule): JsonObject? {
        // Kept for reference/parity tests; the materialised JSON dnsRules
        // produced at save time carry the same shape.
        fun matcherOf(domain: List<String>, suffix: List<String>, keyword: List<String>, regex: List<String>, ruleSets: List<String>): JsonObject? {
            val obj = buildJsonObject {
                putStringList("domain", domain)
                putStringList("domain_suffix", suffix)
                putStringList("domain_keyword", keyword)
                putStringList("domain_regex", regex)
                putStringList("rule_set", ruleSets)
            }
            return if (obj.isEmpty()) null else obj
        }
        return if (rule.isLogical) {
            val children = rule.rules.mapNotNull { sub ->
                matcherOf(sub.domain, sub.domainSuffix, sub.domainKeyword, sub.domainRegex, sub.ruleSet)
            }
            if (children.isEmpty()) return null
            buildJsonObject {
                put("type", "logical")
                put("mode", rule.logicalMode)
                putJsonArray("rules") { children.forEach(::add) }
                put("server", rule.syncDnsServer)
            }
        } else {
            val matcher = matcherOf(rule.domain, rule.domainSuffix, rule.domainKeyword, rule.domainRegex, rule.ruleSet)
                ?: return null
            buildJsonObject {
                for ((k, v) in matcher) put(k, v)
                put("server", rule.syncDnsServer)
            }
        }
    }

    private fun compileDnsRuleAction(builder: JsonObjectBuilder, rule: DnsRule) {
        when (rule.action) {
            DnsRuleActionReject -> {
                builder.put("action", "predefined")
                builder.put("rcode", "NXDOMAIN")
            }
            DnsRuleActionRouteOptions -> {
                builder.put("action", "route-options")
                if (rule.server.isNotBlank()) builder.put("server", rule.server)
                if (rule.clientSubnet.isNotBlank()) builder.put("client_subnet", rule.clientSubnet)
            }
            else -> {
                // Plain route: sing-box infers the action from `server`.
                // The route action embeds the route-options struct, so
                // client_subnet is legal without switching actions.
                if (rule.server.isNotBlank()) builder.put("server", rule.server)
                if (rule.clientSubnet.isNotBlank()) builder.put("client_subnet", rule.clientSubnet)
            }
        }
    }

    private fun compileInlineDnsRule(rule: DnsRule): JsonObject? {
        if (rule.isLogical) {
            val children = rule.rules.filter { it.enabled }.mapNotNull(::compileInlineDnsMatcher)
            if (children.isEmpty()) return null
            return buildJsonObject {
                put("type", "logical")
                put("mode", rule.logicalMode)
                putJsonArray("rules") { children.forEach(::add) }
                if (rule.invert) put("invert", true)
                compileDnsRuleAction(this, rule)
            }
        }
        val matcher = compileInlineDnsMatcher(rule) ?: return null
        return buildJsonObject {
            for ((k, v) in matcher) put(k, v)
            if (rule.invert) put("invert", true)
            compileDnsRuleAction(this, rule)
        }
    }

    private fun compileInlineDnsMatcher(rule: DnsRule): JsonObject? {
        val obj = buildJsonObject {
            putStringList("domain", rule.domain)
            putStringList("domain_suffix", rule.domainSuffix)
            putStringList("domain_keyword", rule.domainKeyword)
            putStringList("domain_regex", rule.domainRegex)
            putStringList("rule_set", rule.ruleSet)
            putStringList("query_type", rule.queryType)
            putStringList("package_name", rule.packageName)
            putStringList("network", rule.network)
            putStringList("protocol", rule.protocol)
            putStringList("clash_mode", rule.clashMode)
            putStringList("response_rcode", rule.responseRcode)
        }
        return if (obj.isEmpty()) null else obj
    }

    private fun compileJsonDnsRule(rule: DnsRule): List<JsonObject> {
        val text = rule.json.trim()
        if (text.isEmpty()) return emptyList()
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (parsed) {
            is JsonObject -> listOf(parsed)
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    /**
     * The `final` DNS server. A non-empty [AppState.finalDnsServer] wins
     * (the explicit "兜底" choice). Otherwise: first enabled concrete server,
     * skipping fakeip — queries that reach `final` have already run the
     * rule gauntlet and should be resolved, not faked.
     */
    private fun pickFinalServer(state: AppState, usableServers: List<DnsServerState>): String {
        val explicit = state.finalDnsServer
        if (explicit.isNotBlank() && usableServers.any { it.tag == explicit }) {
            return explicit
        }
        return usableServers.firstOrNull { it.type != "local" }?.tag
            ?: usableServers.firstOrNull()?.tag
            ?: FakeIpServerTag
    }

    /**
     * Resolver handed to `route.default_domain_resolver`. Must be a concrete
     * server (local / udp / tls / …): fakeip cannot resolve names.
     */
    private fun pickDefaultResolver(state: AppState): String? {
        val liveOutboundTags = buildSet {
            add(DirectOutboundTag)
            add(ProxySelectorTag)
            state.outbounds.forEach { add(it.tag) }
            state.outboundGroups.filter { it.enabled }.forEach { add(it.tag) }
        }
        // A resolver whose detour dangles cannot serve its own lookups.
        val usable = state.dnsServers.filter {
            it.enabled && (it.detour.isBlank() || it.detour in liveOutboundTags)
        }
        return usable.firstOrNull { it.type != "local" }?.tag
            ?: usable.firstOrNull()?.tag
    }

    // ------------------------------------------------------------------ tun

    /**
     * TUN inbound. Only fields the user actually uses are emitted — the
     * kernel allocates state for every option it sees, so a lean inbound is
     * the cheapest power saving there is. Notables:
     *
     *  - `strict_route` is deliberately omitted: `false` is the default and
     *    a false value costs a policy-routing setup on some kernels.
     *  - `endpoint_independent_nat` is off by default; it pins extra state
     *    per flow and only matters for game consoles behind the tunnel.
     *  - `stack` is user-selected (mixed default); `mixed` runs the system
     *    stack for TCP and gVisor for UDP in one TUN instance.
     */
    private fun compileTunInbound(state: AppState): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", TunInboundTag)
        put("mtu", state.tunMtu.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU))
        putJsonArray("address") {
            add(state.tunInet4Address.ifBlank { "172.19.0.1/30" })
            if (state.enableIpv6) {
                add(state.tunInet6Address.ifBlank { "fdfe:dcba:9876::1/126" })
            }
        }
        put("auto_route", true)
        // The `stack` option is deprecated in sing-box 1.15 and the legacy
        // system/gvisor/mixed implementations are on the removal path;
        // omitting it selects sing-tun's own Go stack (slab pools, splice,
        // batched IO), which the upstream changelog credits with better
        // peak performance, energy efficiency, and memory usage.
        // Power-saving: bypass the tun entirely for traffic that's already
        // on the LAN or destined for the local device. Without this every
        // LAN packet (Chromecast discovery, AirPlay, SMB, mDNS, printer
        // probes, SSDP) crosses the tun, wakes the box's event loop, and
        // goes out through the proxy chain — wasting CPU and battery for
        // no benefit.
        putJsonArray("inet4_route_exclude_address") {
            add("10.0.0.0/8")
            add("172.16.0.0/12")
            add("192.168.0.0/16")
            add("127.0.0.0/8")
            add("169.254.0.0/16")
            add("224.0.0.0/4")
            add("255.255.255.255/32")
        }
    }

    // -------------------------------------------------------------- outbound

    private fun compileOutbounds(state: AppState): List<JsonObject> = buildList {
        val nodeTags = state.outbounds.map { it.tag }
        val groupTags = state.outboundGroups.filter { it.enabled }.map { it.tag }
        // Main selector: nodes first, then groups, then direct. Keeping the
        // nodes first mirrors the Flutter client's ordering and makes the
        // Home-tab chip list read top-down like a server list.
        add(buildJsonObject {
            put("type", "selector")
            put("tag", ProxySelectorTag)
            putJsonArray("outbounds") {
                nodeTags.forEach(::add)
                groupTags.forEach(::add)
                add(DirectOutboundTag)
            }
            val selected = state.selectedOutbound
            val selectable = nodeTags + groupTags
            if (selected.isNotBlank() && (selected in selectable || selected == DirectOutboundTag)) {
                put("default", selected)
            }
            put("interrupt_exist_connections", true)
        })
        state.outbounds.forEach { profile ->
            val parsed = runCatching { json.parseToJsonElement(profile.config).jsonObject }
                .getOrNull() ?: return@forEach
            // User overrides win over the subscription's values: a deep
            // merge keeps nodes edited by hand stable across refreshes.
            val effective = applyNodeOverride(parsed, profile.override)
            add(JsonObject(effective.toMutableMap().apply {
                put("type", JsonPrimitive(profile.type))
                put("tag", JsonPrimitive(profile.tag))
                // UDP-over-TCP: let the VLESS stream carry datagrams, so
                // UDP survives servers/firewalls that blackhole real UDP.
                // Skip nodes that set their own packet_encoding.
                if (state.udpOverTcp && profile.type == "vless" && "packet_encoding" !in parsed) {
                    put("packet_encoding", JsonPrimitive("packetaddr"))
                }
                // DPI hardening: split the TLS ClientHello so SNI regexes
                // and reassembly-based detectors lose their anchor. Applied
                // only to nodes that actually speak TLS.
                applyTlsFragment(state, parsed)
                // Multiplexing: one carrying connection for many streams.
                applyMultiplex(state, parsed)
                // TCP keep-alive idle seconds (0 = kernel default).
                applyKeepAlive(state, parsed)
            }))
        }
        // User-configured groups, after their constituent nodes.
        state.outboundGroups.filter { it.enabled }.forEach { group ->
            add(compileOutboundGroup(group, state))
        }
        add(buildJsonObject {
            put("type", "direct")
            put("tag", DirectOutboundTag)
        })
        add(buildJsonObject {
            put("type", "dns")
            put("tag", DnsOutboundTag)
        })
    }

    /**
     * Compiles one [OutboundGroup] entry.
     *
     * `urltest` groups in sing-box-lx accept the same `mode` / `balancer`
     * extension keys as the standalone urltest outbound; we only emit them
     * for `round_robin`, because `least_test` is the upstream default and
     * sending it explicitly on an older core would fail the config check.
     *
     * [OutboundGroup.ModeFallback] is a UI-level concept: the kernel has no
     * fallback outbound type, so it compiles to a sticky urltest — long
     * probe interval, wide tolerance — which switches only when the current
     * member stops answering. Emitting `mode: "fallback"` would be rejected
     * at load time.
     */
    private fun compileOutboundGroup(group: OutboundGroup, state: AppState): JsonObject =
        buildJsonObject {
            put("type", group.kind)
            put("tag", group.tag)
            val members = group.members.ifEmpty {
                // A group with no explicit members falls back to every node,
                // so a freshly created group still produces a usable config.
                state.outbounds.map { it.tag }
            }
            putJsonArray("outbounds") { members.forEach(::add) }
            when (group.kind) {
                OutboundGroup.KindSelector -> {
                    if (group.selected.isNotBlank() && group.selected in members) {
                        put("default", group.selected)
                    }
                    put("interrupt_exist_connections", true)
                }
                else -> {
                    put("url", group.url.ifBlank { state.speedTestUrl })
                    val fallback = group.mode == OutboundGroup.ModeFallback
                    val interval = group.interval.ifBlank {
                        if (fallback) FALLBACK_PROBE_INTERVAL else defaultUrlTestInterval()
                    }
                    put("interval", interval)
                    if (group.unifiedDelay) put("urltest_unified_delay", true)
                    // Idle timeout: how long an idle group stops probing
                    // itself. The kernel rejects interval > idle_timeout at
                    // group start; the post step also raises it as a safety
                    // net, and here we honour an explicit user value.
                    if (group.idleTimeout.isNotBlank()) {
                        put("idle_timeout", group.idleTimeout)
                    }
                    val tolerance = if (group.tolerance > 0) {
                        group.tolerance
                    } else if (fallback) {
                        FALLBACK_TOLERANCE_MS
                    } else {
                        0
                    }
                    if (tolerance > 0) put("tolerance", tolerance)
                    if (group.mode == OutboundGroup.ModeRoundRobin) {
                        put("mode", group.mode)
                        putJsonObject("balancer") {
                            if (group.pool > 0) put("pool", group.pool)
                            if (group.poolTolerance > 0) put("pool_tolerance", group.poolTolerance)
                            val hash = group.stickyHash.filter { it.isNotBlank() }
                            if (hash.isNotEmpty()) {
                                putJsonArray("sticky_hash") { hash.forEach(::add) }
                            }
                        }
                    }
                }
            }
        }

    /**
     * TCP keep-alive idle time, in seconds, on the outbound dialer. The
     * kernel default keeps the OS default; a user value trades battery
     * (each probe wakes the radio) against dead-peer detection latency.
     */
    private fun MutableMap<String, JsonElement>.applyKeepAlive(state: AppState, node: JsonObject) {
        val idleSeconds = state.tcpKeepAliveIdleSeconds
        if (idleSeconds <= 0) return
        if ("tcp_keep_alive" in node) return // node keeps its own value
        put("tcp_keep_alive", JsonPrimitive("${idleSeconds}s"))
    }

    /**
     * Deep-merges the user's override JSON over a node's config: objects
     * merge recursively, everything else replaces. Invalid override JSON
     * is ignored (the base config wins) rather than failing the whole build.
     */
    private fun applyNodeOverride(base: JsonObject, overrideJson: String): JsonObject {
        if (overrideJson.isBlank()) return base
        val override = runCatching { json.parseToJsonElement(overrideJson).jsonObject }.getOrNull()
            ?: return base
        return mergeJsonObjects(base, override)
    }

    private fun mergeJsonObjects(base: JsonObject, override: JsonObject): JsonObject {
        val merged = base.toMutableMap()
        for ((key, value) in override) {
            val existing = merged[key]
            merged[key] = if (existing is JsonObject && value is JsonObject) {
                mergeJsonObjects(existing, value)
            } else {
                value
            }
        }
        return JsonObject(merged)
    }

    /**
     * Injects the multiplex block into an outbound. Rules the kernel
     * enforces:
     *
     *  - Nodes that carry a `flow` value (VLESS xtls-rprx-vision) cannot
     *    multiplex — the kernel rejects the combination at load time, so
     *    they are skipped here rather than failing the whole config.
     *  - Only vless / shadowsocks outbounds reach this point (the protocol
     *    diet left both with mux support).
     *  - A node that ships its own `multiplex` block keeps it.
     */
    private fun MutableMap<String, JsonElement>.applyMultiplex(state: AppState, node: JsonObject) {
        if (!state.muxEnabled) return
        if ("multiplex" in node) return
        val type = (node["type"] as? JsonPrimitive)?.content ?: return
        if (type !in setOf("vless", "shadowsocks")) return
        val flow = (node["flow"] as? JsonPrimitive)?.content
        if (!flow.isNullOrBlank()) return
        val brutal = state.muxBrutalEnabled && state.muxBrutalUpMbps > 0 && state.muxBrutalDownMbps > 0
        put("multiplex", buildJsonObject {
            put("enabled", true)
            put("protocol", state.muxProtocol.ifBlank { MuxProtocolH2mux })
            if (state.muxMaxConnections > 0) put("max_connections", state.muxMaxConnections)
            if (state.muxMinStreams > 0) put("min_streams", state.muxMinStreams)
            if (state.muxMaxStreams > 0) put("max_streams", state.muxMaxStreams)
            if (state.muxPadding) put("padding", true)
            if (brutal) {
                putJsonObject("brutal") {
                    put("enabled", true)
                    put("up_mbps", state.muxBrutalUpMbps)
                    put("down_mbps", state.muxBrutalDownMbps)
                }
            }
        })
    }

    /**
     * Injects TLS fragmentation into a node's `tls` block when the global
     * DPI-hardening mode is on. `record` maps to `record_fragment` (extra
     * TLS records, cheap); `packet` maps to `fragment` (splits across TCP
     * segments). Nodes that set their own fragment flags win.
     */
    private fun MutableMap<String, JsonElement>.applyTlsFragment(state: AppState, node: JsonObject) {
        if (state.tlsFragmentMode == "none") return
        val tls = node["tls"]?.jsonObject ?: return
        val updated = tls.toMutableMap()
        when (state.tlsFragmentMode) {
            "record" -> if ("record_fragment" !in tls) updated["record_fragment"] = JsonPrimitive(true)
            "packet" -> if ("fragment" !in tls) updated["fragment"] = JsonPrimitive(true)
        }
        if (state.tlsFragmentFallbackDelay.isNotBlank() && "fragment_fallback_delay" !in tls) {
            updated["fragment_fallback_delay"] = JsonPrimitive(state.tlsFragmentFallbackDelay)
        }
        put("tls", JsonObject(updated))
    }

    // ----------------------------------------------------------------- route

    private fun compileRouteRules(state: AppState): List<JsonObject> = buildList {
        // Built-in: fake-IP bypass. Any packet addressed into the fake pool
        // goes straight to direct — an app that cached a fake address past
        // the tunnel's lifetime must not loop it back through the proxy.
        if (state.fakeIpBypass && state.enableFakeIp) {
            val v4 = state.fakeIpInet4Range.ifBlank { "198.18.0.0/15" }
            add(buildJsonObject {
                putJsonArray("ip_cidr") { add(v4) }
                put("action", "route")
                put("outbound", DirectOutboundTag)
            })
            if (state.enableIpv6) {
                val v6 = state.fakeIpInet6Range.ifBlank { "fc00::/18" }
                add(buildJsonObject {
                    putJsonArray("ip_cidr") { add(v6) }
                    put("action", "route")
                    put("outbound", DirectOutboundTag)
                })
            }
        }
        // Power-saving: route private IPs straight to direct. With LAN bypass
        // in the tun this should rarely fire, but apps that bind to RFC1918
        // and then try to reach other RFC1918 destinations will still go
        // through the proxy without this rule. Keeping it cheap: a single
        // ip_is_private match.
        add(buildJsonObject {
            put("ip_is_private", true)
            put("action", "route")
            put("outbound", DirectOutboundTag)
        })
        // User rules go through the planner: package rules first, then
        // name-based, then address-based — with redundant entries (keyword /
        // suffix / exact-domain / CIDR, earlier rule > later rule) already
        // removed. The core sees a table that is sorted and deduped, so its
        // first-hit-wins scan is both cheaper and matches the documented
        // routing flow (app → name → address).
        RulePlanner.prepare(state.routeRules).forEach { rule ->
            when (rule.kind) {
                RouteRule.KindJson -> addAll(compileJsonRule(rule))
                else -> compileInlineRule(rule, state)?.let(::add)
            }
        }
        // Injected: sniff (visual sniffer settings) — before hijack-dns like AsteriskBOX.
        // Sniff only over TCP; UDP packets are tiny and re-running the
        // sniffer on every QUIC/DNS hop chews CPU without buying anything
        // (the sniffer mostly exists to attach SNI/HTTPHost to outbound
        // connections, which are TCP by definition).
        if (state.enableSniffer) {
            add(buildJsonObject {
                put("action", "sniff")
                putJsonArray("network") { add("tcp") }
                putJsonArray("sniffer") {
                    state.snifferProtocols.forEach(::add)
                }
                put("timeout", state.snifferTimeout.ifBlank { "1s" })
            })
        }
        // Injected: DNS hijack for anything still speaking plain :53.
        if (state.hijackDns) {
            add(buildJsonObject {
                put("port", 53)
                put("action", "hijack-dns")
            })
        }
        // Clash-mode shortcuts (Global / Direct bypass the managed rules).
        add(buildJsonObject {
            put("clash_mode", ClashModeGlobal)
            put("action", "route")
            put("outbound", ProxySelectorTag)
        })
        add(buildJsonObject {
            put("clash_mode", ClashModeDirect)
            put("action", "route")
            put("outbound", DirectOutboundTag)
        })
    }

    private fun compileJsonRule(rule: RouteRule): List<JsonObject> {
        val text = rule.json.trim()
        if (text.isEmpty()) return emptyList()
        // Accept the wrapper shapes too (a full config / route section with
        // the rules under route.rules) — same contract as the editor's
        // validator, so what it accepts is what compiles.
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (parsed) {
            is JsonObject -> {
                val route = parsed["route"] as? JsonObject
                when {
                    route != null ->
                        (route["rules"] as? JsonArray)?.filterIsInstance<JsonObject>()
                            ?: listOf(route)
                    parsed["rules"] is JsonArray && parsed["domain"].isNullish() &&
                        parsed["ip_cidr"].isNullish() && parsed["package_name"].isNullish() ->
                        (parsed["rules"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                    else -> listOf(parsed)
                }
            }
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    private fun JsonElement?.isNullish(): Boolean =
        this == null || this is JsonArray && this.isEmpty()

    /** Compiles an inline rule (default or logical) into a sing-box route rule. */
    private fun compileInlineRule(rule: RouteRule, state: AppState): JsonObject? {
        val matcher = compileInlineMatcher(rule) ?: return null
        return buildJsonObject {
            for ((k, v) in matcher) put(k, v)
            if (rule.invert) put("invert", true)
            putRuleAction(rule, state)
        }
    }

    /**
     * Compiles the match portion of any inline rule, without its action.
     * Handles the three shapes: a plain default rule, a rule whose own
     * conditions combine with OR, and a logical container over sub-rules.
     * Logical children recurse through this same function, so sub-rules keep
     * their own combine mode when nested (the kernel accepts nested logical
     * rules as long as only the outermost one carries an action).
     */
    private fun compileInlineMatcher(rule: RouteRule): JsonObject? {
        if (rule.isLogical) {
            val children = rule.rules
                .filter { it.enabled }
                .mapNotNull(::compileInlineMatcher)
            if (children.isEmpty()) return null
            return buildJsonObject {
                put("type", "logical")
                put("mode", rule.logicalMode)
                putJsonArray("rules") { children.forEach(::add) }
            }
        }
        // Per-sub-rule combine mode: "or" turns the rule's own condition
        // families into a logical OR (matching any one of them suffices),
        // "" / "and" keeps the kernel-native default rule.
        if (rule.combine == RouteRule.CombineOr) {
            val groups = inlineOrGroups(rule)
            if (groups.isEmpty()) return null
            return buildJsonObject {
                put("type", "logical")
                put("mode", "or")
                putJsonArray("rules") { groups.forEach(::add) }
            }
        }
        return compileInlineRuleMatcher(rule)
    }

    /**
     * Splits a rule's match fields into per-family objects for an OR
     * combination. The destination-address family (domain / suffix /
     * keyword / regex / ip_cidr / rule_set) stays together in one group,
     * matching the kernel's own OR group; everything else gets its own
     * branch so `package_name` OR `domain` behaves as the user expects.
     */
    private fun inlineOrGroups(rule: RouteRule): List<JsonObject> = buildList {
        val address = buildJsonObject {
            putStringList("domain", rule.domain)
            putStringList("domain_suffix", rule.domainSuffix)
            putStringList("domain_keyword", rule.domainKeyword)
            putStringList("domain_regex", rule.domainRegex)
            putStringList("ip_cidr", rule.ipCidr)
            putStringList("rule_set", rule.ruleSet)
        }
        if (address.isNotEmpty()) add(address)
        if (rule.packageName.isNotEmpty()) add(buildJsonObject { putStringList("package_name", rule.packageName) })
        if (rule.wifiSsid.isNotEmpty() || rule.wifiBssid.isNotEmpty()) {
            add(buildJsonObject {
                putStringList("wifi_ssid", rule.wifiSsid)
                putStringList("wifi_bssid", rule.wifiBssid)
            })
        }
        val ports = buildJsonObject {
            putIntList("source_port", rule.sourcePort)
            putStringList("source_port_range", rule.sourcePortRange)
            putIntList("port", rule.port)
            putStringList("port_range", rule.portRange)
        }
        if (ports.isNotEmpty()) add(ports)
        val transport = buildJsonObject {
            putStringList("network", rule.network)
            putStringList("protocol", rule.protocol)
            when (rule.ipFamily) {
                "ipv4_only" -> put("ip_version", 4)
                "ipv6_only" -> put("ip_version", 6)
            }
        }
        if (transport.isNotEmpty()) add(transport)
        if (rule.sourceIpCidr.isNotEmpty()) {
            add(buildJsonObject { putStringList("source_ip_cidr", rule.sourceIpCidr) })
        }
        val flags = buildJsonObject {
            if (rule.sourceIpIsPrivate) put("source_ip_is_private", true)
            if (rule.ipIsPrivate) put("ip_is_private", true)
        }
        if (flags.isNotEmpty()) add(flags)
    }

    /**
     * Compiles the match portion of a default rule. Returns null when the rule has
     * no effective matcher at all (sing-box would reject a match-less rule).
     */
    private fun compileInlineRuleMatcher(rule: RouteRule): JsonObject? {
        if (rule.kind == RouteRule.KindJson) return null
        val obj = buildJsonObject {
            putStringList("domain", rule.domain)
            putStringList("domain_suffix", rule.domainSuffix)
            putStringList("domain_keyword", rule.domainKeyword)
            putStringList("domain_regex", rule.domainRegex)
            putStringList("ip_cidr", rule.ipCidr)
            putStringList("source_ip_cidr", rule.sourceIpCidr)
            // Per-rule address family: ipv4_only / ipv6_only as a match item
            // (only one core value at a time), while "both" with a
            // preference is expressed through the rule's resolve strategy.
            when (rule.ipFamily) {
                "ipv4_only" -> put("ip_version", 4)
                "ipv6_only" -> put("ip_version", 6)
            }
            putIntList("source_port", rule.sourcePort)
            putStringList("source_port_range", rule.sourcePortRange)
            putIntList("port", rule.port)
            putStringList("port_range", rule.portRange)
            putStringList("network", rule.network)
            putStringList("protocol", rule.protocol)
            putStringList("package_name", rule.packageName)
            putStringList("wifi_ssid", rule.wifiSsid)
            putStringList("wifi_bssid", rule.wifiBssid)
            if (rule.sourceIpIsPrivate) put("source_ip_is_private", true)
            if (rule.ipIsPrivate) put("ip_is_private", true)
            putStringList("rule_set", rule.ruleSet)
        }
        return if (obj.isEmpty()) null else obj
    }

    private fun JsonObjectBuilder.putRuleAction(rule: RouteRule, state: AppState) {
        when (rule.action) {
            RouteRule.RuleActionReject -> put("action", "reject")
            RouteRule.RuleActionResolve -> {
                put("action", "resolve")
                val subnet = effectiveClientSubnet(rule, state)
                if (subnet.isNotBlank()) put("client_subnet", subnet)
                // The resolve action's strategy uses the same family policy
                // as the rule's own matcher.
                rule.ipFamilyStrategy()?.let { put("strategy", it) }
            }
            else -> {
                put("action", "route")
                put("outbound", rule.outbound.ifBlank { ProxySelectorTag })
            }
        }
    }

    /**
     * ECS resolution, in priority order: the rule's explicit subnet, then
     * the global default, then (auto mode) the exit node's address so proxy
     * lookups carry the exit's locality instead of the user's. Empty = no
     * client_subnet is emitted at all.
     */
    private fun effectiveClientSubnet(rule: RouteRule, state: AppState): String {
        rule.clientSubnet.trim().let { if (it.isNotBlank()) return it }
        val global = state.dnsClientSubnet.trim()
        if (global.isNotBlank()) return global
        if (state.autoEcsFromNode && rule.outbound.ifBlank { ProxySelectorTag } != DirectOutboundTag) {
            val node = state.nodeEcsAddress.trim()
            if (node.isNotBlank()) {
                // ECS wants a subnet: a bare address becomes a /24 (v4) or
                // /56 (v6) so resolvers accept it.
                return if (':' in node) "$node/56" else "$node/24"
            }
        }
        return ""
    }

    /**
     * The rule's address-family filter as a core strategy string. A rule
     * that says nothing inherits the global policy (null = emit nothing so
     * the global strategy applies). `both` with a preference is expressed as
     * the corresponding prefer_* strategy.
     */
    private fun RouteRule.ipFamilyStrategy(): String? = when (ipFamily) {
        "ipv4_only" -> "ipv4_only"
        "ipv6_only" -> "ipv6_only"
        "both" -> if (ipPreference == "prefer_ipv4") "prefer_ipv4" else "prefer_ipv6"
        else -> null
    }

    // -------------------------------------------------------------- rule set

    private fun compileRuleSets(state: AppState, dir: File): List<JsonObject> {
        // Only emit rule sets that are actually referenced by an enabled inline rule.
        val referenced = collectReferencedRuleSetTags(state.routeRules) +
            collectReferencedDnsRuleSetTags(state.dnsRules)
        return state.ruleSets
            .filter { it.tag in referenced }
            .map { rs ->
                val cache = File(dir, "${rs.id}.${rs.extension}")
                buildJsonObject {
                    put("tag", rs.tag)
                    put("format", rs.format)
                    if (cache.isFile && cache.length() > 0) {
                        put("type", "local")
                        put("path", cache.absolutePath)
                    } else {
                        put("type", "remote")
                        put("url", rs.url)
                        put("download_detour", DirectOutboundTag)
                    }
                }
            }
    }

    private fun collectReferencedRuleSetTags(rules: List<RouteRule>): Set<String> = buildSet {
        fun visit(rule: RouteRule) {
            if (!rule.enabled) return
            if (rule.kind == RouteRule.KindInline) {
                addAll(rule.ruleSet)
                rule.rules.forEach(::visit)
            }
        }
        rules.forEach(::visit)
    }

    private fun collectReferencedDnsRuleSetTags(rules: List<DnsRule>): Set<String> = buildSet {
        fun visit(rule: DnsRule) {
            if (!rule.enabled) return
            if (rule.kind == DnsRule.KindInline) {
                addAll(rule.ruleSet)
                rule.rules.forEach(::visit)
            }
        }
        rules.forEach(::visit)
    }

    // -------------------------------------------------------------- helpers

    private fun JsonObjectBuilder.putStringList(key: String, values: List<String>) {
        val clean = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isNotEmpty()) putJsonArray(key) { clean.forEach(::add) }
    }

    private fun JsonObjectBuilder.putIntList(key: String, values: List<String>) {
        val clean = values.mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..65535 }
            .distinct()
        if (clean.isNotEmpty()) putJsonArray(key) { clean.forEach(::add) }
    }

    private const val MIN_TUN_MTU = 1280
    private const val MAX_TUN_MTU = 9000

    /** Tag of the localhost mixed inbound used by subscription fetches. */
    const val LocalProxyInboundTag = "sub-fetch-in"

    /** Port the box listens on for subscription fetches (loopback only). */
    const val SubscriptionFetchPort = 2080

    /** Sticky-probe settings used to emulate a fallback group (see above). */
    private const val FALLBACK_PROBE_INTERVAL = "10m"
    private const val FALLBACK_TOLERANCE_MS = 100_000
}