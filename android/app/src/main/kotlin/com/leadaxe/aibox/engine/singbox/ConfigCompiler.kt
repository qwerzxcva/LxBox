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
import com.leadaxe.aibox.app.OutboundGroup
import com.leadaxe.aibox.app.ProxySelectorTag
import com.leadaxe.aibox.app.RouteRule
import com.leadaxe.aibox.app.TunInboundTag
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    fun compile(state: AppState, ruleSetDir: File): JsonObject = buildJsonObject {
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
        putJsonArray("inbounds") {
            add(compileTunInbound(state))
            // Subscription fetches ride the same DNS/routing rules as normal
            // traffic when the user picks "proxy" (or when auto falls back).
            add(compileLocalProxyInbound())
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
            put("final", ProxySelectorTag)
            put("auto_detect_interface", true)
        }
    }

    // ------------------------------------------------------------------ dns

    private fun compileDns(state: AppState, ruleSetDir: File): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            state.dnsServers.filter { it.enabled }.forEach { server ->
                add(compileDnsServer(server))
            }
            if (state.enableFakeIp) {
                add(compileFakeIpServer(state))
            }
        }
        val rules = compileDnsRules(state)
        if (rules.isNotEmpty()) {
            putJsonArray("rules") { rules.forEach(::add) }
        }
        if (state.dnsStrategy.isNotBlank()) put("strategy", state.dnsStrategy)
        if (state.dnsClientSubnet.isNotBlank()) put("client_subnet", state.dnsClientSubnet)
        put("final", pickFinalServer(state))
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
                // sing-box-lx extension: several resolvers behind one tag.
                // Members are referenced by tag; order is not meaningful.
                put("type", "group")
                putJsonArray("servers") { server.groupServers.forEach(::add) }
                put("mode", server.groupMode.ifBlank { "stable" })
                if (server.groupErrorTtl.isNotBlank()) put("error_ttl", server.groupErrorTtl)
                if (server.groupWinTtl.isNotBlank()) put("win_ttl", server.groupWinTtl)
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

    private fun compileDnsRules(state: AppState): List<JsonObject> = buildList {
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
                    put("server", pickFinalServer(state))
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
        // User rules.
        state.dnsRules.filter { it.enabled }.forEach { rule ->
            when (rule.kind) {
                DnsRule.KindJson -> addAll(compileJsonDnsRule(rule))
                else -> compileInlineDnsRule(rule)?.let(::add)
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
            }
            else -> {
                // Plain route: sing-box infers the action from `server`.
                if (rule.server.isNotBlank()) builder.put("server", rule.server)
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
    private fun pickFinalServer(state: AppState): String {
        val explicit = state.finalDnsServer
        if (explicit.isNotBlank() && state.dnsServers.any { it.enabled && it.tag == explicit }) {
            return explicit
        }
        return state.dnsServers.firstOrNull { it.enabled && it.type != "local" }?.tag
            ?: state.dnsServers.firstOrNull { it.enabled }?.tag
            ?: FakeIpServerTag
    }

    /**
     * Resolver handed to `route.default_domain_resolver`. Must be a concrete
     * server (local / udp / tls / …): fakeip cannot resolve names.
     */
    private fun pickDefaultResolver(state: AppState): String? =
        state.dnsServers.firstOrNull { it.enabled && it.type != "local" }?.tag
            ?: state.dnsServers.firstOrNull { it.enabled }?.tag

    // ------------------------------------------------------------------ tun

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
        put("strict_route", false)
        put("stack", "mixed")
        put("endpoint_independent_nat", true)
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
            add(JsonObject(parsed.toMutableMap().apply {
                put("type", JsonPrimitive(profile.type))
                put("tag", JsonPrimitive(profile.tag))
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
                    if (group.interval.isNotBlank()) put("interval", group.interval)
                    if (group.tolerance > 0) put("tolerance", group.tolerance)
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

    // ----------------------------------------------------------------- route

    private fun compileRouteRules(state: AppState): List<JsonObject> = buildList {
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
        state.routeRules.filter { it.enabled }.forEach { rule ->
            when (rule.kind) {
                RouteRule.KindJson -> addAll(compileJsonRule(rule))
                else -> compileInlineRule(rule)?.let(::add)
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
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (parsed) {
            is JsonObject -> listOf(parsed)
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    /** Compiles an inline rule (default or logical) into a sing-box route rule. */
    private fun compileInlineRule(rule: RouteRule): JsonObject? {
        if (rule.isLogical) {
            // Logical children are pure matchers: compiled as default rules
            // stripped of their action.
            val children = rule.rules
                .filter { it.enabled }
                .mapNotNull(::compileInlineRuleMatcher)
            if (children.isEmpty()) return null
            return buildJsonObject {
                put("type", "logical")
                put("mode", rule.logicalMode)
                putJsonArray("rules") { children.forEach(::add) }
                if (rule.invert) put("invert", true)
                putRuleAction(rule)
            }
        }
        val matcher = compileInlineRuleMatcher(rule) ?: return null
        return buildJsonObject {
            for ((k, v) in matcher) put(k, v)
            if (rule.invert) put("invert", true)
            putRuleAction(rule)
        }
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

    private fun JsonObjectBuilder.putRuleAction(rule: RouteRule) {
        when (rule.action) {
            RouteRule.RuleActionReject -> put("action", "reject")
            RouteRule.RuleActionResolve -> put("action", "resolve")
            else -> {
                put("action", "route")
                put("outbound", rule.outbound.ifBlank { ProxySelectorTag })
            }
        }
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
}