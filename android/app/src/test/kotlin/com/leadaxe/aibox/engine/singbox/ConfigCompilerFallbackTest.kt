package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.BlockOutboundTag
import com.leadaxe.aibox.app.ClashModeRule
import com.leadaxe.aibox.app.DnsFinalProxy
import com.leadaxe.aibox.app.FallbackRouteDirect
import com.leadaxe.aibox.app.FallbackRouteProxy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
    fun `fallback rule pins proxy or direct ahead of the mode shortcuts`() {
        // Proxy fallback: an explicit catch-all proxy rule closes the table.
        val proxy = compile(AppState(fallbackRouteMode = FallbackRouteProxy))
        val last = fallbackRuleOf(proxy)!!
        assertEquals("lb", last["outbound"]!!.jsonPrimitive.content)
        assertEquals(ClashModeRule, last["clash_mode"]!!.jsonPrimitive.content)

        // Direct fallback: same position, direct outbound.
        val direct = compile(AppState(fallbackRouteMode = FallbackRouteDirect))
        assertEquals("direct", fallbackRuleOf(direct)!!["outbound"]!!.jsonPrimitive.content)

        // Default: fallback = direct, so a catch-all direct rule exists.
        val none = compile(AppState())
        assertEquals("direct", fallbackRuleOf(none)!!["outbound"]!!.jsonPrimitive.content)
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
    fun `sanitize keeps the final dns shortcuts`() {
        val state = AppState(finalDnsServer = DnsFinalProxy)
        val sanitized = com.leadaxe.aibox.app.sanitizeAppStateReferences(state)
        // Before the fix the shortcut was wiped as a dangling server tag.
        assertEquals(DnsFinalProxy, sanitized.finalDnsServer)
    }
}
