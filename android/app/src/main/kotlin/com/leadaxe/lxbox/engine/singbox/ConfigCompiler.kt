package com.leadaxe.lxbox.engine.singbox

import com.leadaxe.lxbox.app.AppState
import com.leadaxe.lxbox.app.ClashModeDirect
import com.leadaxe.lxbox.app.ClashModeGlobal
import com.leadaxe.lxbox.app.DirectOutboundTag
import com.leadaxe.lxbox.app.DnsOutboundTag
import com.leadaxe.lxbox.app.DnsServerState
import com.leadaxe.lxbox.app.ProxySelectorTag
import com.leadaxe.lxbox.app.RouteRule
import com.leadaxe.lxbox.app.TunInboundTag
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
 * Layout mirrors the proven AsteriskBOX compiler: managed rules first, then the
 * injected rules (sniff / hijack-dns), then clash-mode shortcuts, and finally the
 * catch-all `final` outbound.
 */
object ConfigCompiler {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param ruleSetDir directory where downloaded rule-set caches live
     *        (`<id>.srs` / `<id>.json`).
     */
    fun compile(state: AppState, ruleSetDir: File): JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("level", state.logLevel)
            put("timestamp", true)
        }
        put("dns", compileDns(state))
        putJsonArray("inbounds") { add(compileTunInbound(state)) }
        putJsonArray("outbounds") { compileOutbounds(state).forEach(::add) }
        putJsonObject("route") {
            putJsonArray("rules") { compileRouteRules(state).forEach(::add) }
            val ruleSets = compileRuleSets(state, ruleSetDir)
            if (ruleSets.isNotEmpty()) {
                putJsonArray("rule_set") { ruleSets.forEach(::add) }
            }
            put("final", ProxySelectorTag)
            put("auto_detect_interface", true)
        }
    }

    // ------------------------------------------------------------------ dns

    private fun compileDns(state: AppState): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            state.dnsServers.forEach { server -> add(compileDnsServer(server)) }
        }
        if (state.dnsStrategy.isNotBlank()) put("strategy", state.dnsStrategy)
        put("final", state.dnsServers.firstOrNull()?.tag ?: "dns-remote")
        put("independent_cache", true)
        // Bound the DNS cache so long-running sessions don't OOM on a phone
        // that sees lots of unique subdomains (e.g. CDN fan-out).
        put("cache_capacity", 4096)
        // Stop caching answers for hosts we just told the OS to skip via
        // fake-IP; those would otherwise pin a fake address past the TTL.
        put("cache_hint", true)
    }

    private fun compileDnsServer(server: DnsServerState): JsonObject = buildJsonObject {
        put("tag", server.tag)
        when (server.type) {
            "local" -> put("type", "local")
            "direct" -> {
                put("type", "local")
                put("detour", DirectOutboundTag)
            }
            else -> {
                put("type", server.type)
                put("server", server.address)
                if (server.detour.isNotBlank()) put("detour", server.detour)
            }
        }
    }

    // ------------------------------------------------------------------ tun

    private fun compileTunInbound(state: AppState): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", TunInboundTag)
        put("mtu", state.tunMtu.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU))
        putJsonArray("address") {
            add("172.18.0.1/30")
            if (state.enableIpv6) add("fdfe:dcba:9876::1/64")
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
        add(buildJsonObject {
            put("type", "selector")
            put("tag", ProxySelectorTag)
            putJsonArray("outbounds") {
                nodeTags.forEach(::add)
                add(DirectOutboundTag)
            }
            val selected = state.selectedOutbound
            if (selected.isNotBlank() && (selected in nodeTags || selected == DirectOutboundTag)) {
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
        add(buildJsonObject {
            put("type", "direct")
            put("tag", DirectOutboundTag)
        })
        add(buildJsonObject {
            put("type", "dns")
            put("tag", DnsOutboundTag)
        })
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
        val referenced = collectReferencedRuleSetTags(state.routeRules)
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
}
