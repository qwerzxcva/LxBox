package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.BlockOutboundTag
import com.leadaxe.aibox.app.ClashModeRule
import com.leadaxe.aibox.app.DnsFinalProxy
import com.leadaxe.aibox.app.DnsFinalDirect
import com.leadaxe.aibox.app.FallbackRouteDirect
import com.leadaxe.aibox.app.FallbackRouteProxy
import com.leadaxe.aibox.app.LoadBalanceTag
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Per-mode fallback DNS and the rules-list fallback rule (user request:
 * 兜底按模式拆分，规则列表末尾是可选直连/代理的兜底规则).
 */
class ConfigCompilerFallbackTest {

    private fun compile(state: AppState): JsonObject {
        // No rule-set materialisation is exercised here: an empty state with
        // no ruleSets never touches the directory.
        val dir = File("/tmp/aibox-test-rulesets").apply { mkdirs() }
        return ConfigCompiler.compile(state, dir)
    }

    private fun routeRulesOf(config: JsonObject) =
        config["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun dnsFinalOf(config: JsonObject) =
        config["dns"]!!.jsonObject["final"]?.jsonPrimitive?.content

    @Test
    fun `final dns follows the route fallback exit`() {
        // The route fallback picks the exit; the DNS fallback row for THAT
        // exit applies.
        val state = AppState(
            fallbackRouteMode = FallbackRouteProxy,
            finalDnsServerByExit = mapOf("proxy" to DnsFinalProxy, "direct" to "final:direct"),
        )
        val proxy = compile(state)
        assertEquals("dns-final-proxy", dnsFinalOf(proxy))
        val direct = compile(state.copy(fallbackRouteMode = FallbackRouteDirect))
        assertEquals("dns-final-direct", dnsFinalOf(direct))
        // No fallback mode → no exit key → global (automatic first server).
        val none = compile(state.copy(fallbackRouteMode = ""))
        assertEquals("dns-remote", dnsFinalOf(none))
    }
    /** The fallback rule is the last rule of the *rule-mode* table: it sits
     *  before the Global/Direct bypass shortcuts, which must stay last. */
    private fun fallbackRuleOf(config: JsonObject): JsonObject? {
        val rules = routeRulesOf(config)
        // Walk from the end; the fallback is the first clash_mode=Rule entry.
        for (i in rules.indices.reversed()) {
            val mode = rules[i]["clash_mode"]?.jsonPrimitive?.content
            if (mode == ClashModeRule) return rules[i]
        }
        return null
    }

    @Test
    fun `fallback mode drives route final, not a duplicate rule`() {
        // The fallback IS sing-box's route.final — no extra rule is emitted
        // (a clash_mode=Rule copy used to shadow the unknown-traffic choice).
        val proxy = compile(AppState(fallbackRouteMode = FallbackRouteProxy))
        assertEquals(
            LoadBalanceTag,
            proxy["route"]!!.jsonObject["final"]!!.jsonPrimitive.content,
        )
        assertNull(fallbackRuleOf(proxy))

        val direct = compile(AppState(fallbackRouteMode = FallbackRouteDirect))
        assertEquals(
            "direct",
            direct["route"]!!.jsonObject["final"]!!.jsonPrimitive.content,
        )

        // Default: fallback = PROXY — a fresh install has no rules, so the
        // default governs all traffic; direct-by-default meant "connected"
        // with everything bypassing the proxy.
        val none = compile(AppState())
        assertEquals(
            LoadBalanceTag,
            none["route"]!!.jsonObject["final"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `reject target on unknown traffic wins over the fallback mode`() {
        // The user pinned block/reject as the final exit: it is stricter
        // than both fallback modes, so no extra rule is appended (the
        // route.final=block still applies).
        val config = compile(
            AppState(
                fallbackRouteMode = FallbackRouteProxy,
                unknownTrafficOutbound = BlockOutboundTag,
            ),
        )
        assertEquals(BlockOutboundTag, config["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertNull(fallbackRuleOf(config))
    }

    @Test
    fun `bootstrap resolver is local, never proxy-routed`() {
        // Regression for "tunnel up but nothing resolves": the default
        // domain resolver resolves the proxy node's own server domain
        // BEFORE the tunnel exists. A proxy-routed resolver deadlocks the
        // bootstrap — tunnel waits for the node, node waits for the tunnel.
        val config = compile(AppState())
        val resolver = config["route"]!!.jsonObject["default_domain_resolver"]
            ?.jsonPrimitive?.content
        // Must be the local/direct resolver (dns-direct is the local shadow
        // server the compiler materialises).
        assertTrue(
            resolver == null || resolver == "dns-direct" ||
                resolver.startsWith("dns-final-os"),
        )
    }

    @Test
    fun `sanitize keeps the final dns shortcuts`() {
        val state = AppState(finalDnsServer = DnsFinalProxy)
        val sanitized = com.leadaxe.aibox.app.sanitizeAppStateReferences(state)
        // Before the fix the shortcut was wiped as a dangling server tag.
        assertEquals(DnsFinalProxy, sanitized.finalDnsServer)
    }

    @Test
    fun `fake-ip pool escape is a hijack-dns action`() {
        // Regression for "connected but nothing resolves": the pool bypass
        // used to route 198.18/15 to direct, blackholing every connection
        // an app made to a cached fake address. It then briefly pointed at
        // the legacy `dns` outbound, which sing-box 1.13 removed (config
        // failed at decode). The pool must be answered by the hijack-dns
        // RULE ACTION.
        val config = compile(AppState(enableFakeIp = true))
        val first = routeRulesOf(config).first()
        assertEquals("198.18.0.0/15", first["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("hijack-dns", first["action"]?.jsonPrimitive?.content)
        assertNull(first["outbound"])
    }

    @Test
    fun `legacy dns outbound is never emitted`() {
        // sing-box 1.13 hard-rejects the `dns` outbound type at decode.
        val config = compile(AppState(enableFakeIp = true))
        val types = config["outbounds"]!!.jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue("no dns outbound may be emitted", "dns" !in types)
    }

    @Test
    fun `fallback direct ECS rides the shadow resolver`() {
        val state = AppState(
            fallbackRouteMode = FallbackRouteDirect,
            fallbackEcsDirect = "1.2.3.0/24",
            // The shadow server only materialises when the fallback DNS is
            // the direct shortcut; that is the row our ECS rides on.
            finalDnsServer = DnsFinalDirect,
        )
        val config = compile(state)
        val servers = config["dns"]!!.jsonObject["servers"]!!.jsonArray
        val shadow = servers.map { it.jsonObject }
            .firstOrNull { it["tag"]?.jsonPrimitive?.content == "dns-final-direct" }
        assertNotNull("final-direct shadow server expected", shadow)
        assertEquals(
            "1.2.3.0/24",
            shadow!!["client_subnet"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `rule ECS wins over the direct fallback ECS`() {
        // DNS-side: a DNS rule that pins its own subnet keeps it — the
        // fallback subnet lives on the shadow server only and never
        // overrides rule-level choices.
        val dnsRule = com.leadaxe.aibox.app.DnsRule(
            id = "ecs-dns-rule",
            domainSuffix = listOf("example.com"),
            server = "dns-https-1",
            clientSubnet = "9.9.9.0/24",
        )
        val state = AppState(
            fallbackRouteMode = FallbackRouteDirect,
            fallbackEcsDirect = "1.2.3.0/24",
            dnsRules = listOf(dnsRule),
        )
        val config = compile(state)
        val dnsRules = config["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val mine = dnsRules.firstOrNull {
            it["client_subnet"]?.jsonPrimitive?.content == "9.9.9.0/24"
        }
        assertNotNull("the rule's own subnet must survive", mine)
        assertTrue(
            dnsRules.none {
                it["client_subnet"]?.jsonPrimitive?.content == "1.2.3.0/24"
            },
        )
    }
}
