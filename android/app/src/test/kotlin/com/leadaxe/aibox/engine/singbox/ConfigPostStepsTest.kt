package com.leadaxe.aibox.engine.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports of the reference client's 2.24.0 compile-time fixes, verified as
 * unit tests here so the rules keep their documented semantics:
 *
 *  - §442: urltest with interval > idle_timeout must not stop the core from
 *    starting; idle_timeout is raised to the interval, the interval stays.
 *  - §444: an explicit REALITY fingerprint from the node source is kept
 *    as-is; `chrome` is only filled in where there was no choice.
 */
class ConfigPostStepsTest {

    // ------------------------------------------------------------- urltest

    @Test
    fun `interval over idle timeout raises idle_timeout`() {
        val config = Json.parseToJsonElement(
            """{"outbounds":[{"type":"urltest","tag":"g","outbounds":["a"],"interval":"3h","idle_timeout":"30m"}]}""",
        ).jsonObject
        val (healed, notes) = ConfigPostSteps.sanitizeUrltestTimings(config)
        val group = ((healed["outbounds"] as? JsonArray)!![0] as kotlinx.serialization.json.JsonObject)
        assertEquals("3h", (group["interval"] as JsonPrimitive).content, )
        assertEquals("3h", (group["idle_timeout"] as JsonPrimitive).content)
        assertEquals(1, notes.size)
        assertTrue(notes[0].second.contains("raised"))
    }

    @Test
    fun `missing interval uses core default 3m`() {
        // A group with no interval but a 1m idle_timeout would die on the
        // core's defaults too — idle_timeout rises to 3m.
        val config = Json.parseToJsonElement(
            """{"outbounds":[{"type":"urltest","tag":"g","outbounds":["a"],"idle_timeout":"1m"}]}""",
        ).jsonObject
        val (healed, _) = ConfigPostSteps.sanitizeUrltestTimings(config)
        val group = ((healed["outbounds"] as? JsonArray)!![0] as kotlinx.serialization.json.JsonObject)
        assertEquals("3m", (group["idle_timeout"] as JsonPrimitive).content)
    }

    @Test
    fun `balanced pair is untouched`() {
        val config = Json.parseToJsonElement(
            """{"outbounds":[{"type":"urltest","tag":"g","outbounds":["a"],"interval":"1h","idle_timeout":"2h"}]}""",
        ).jsonObject
        val (healed, notes) = ConfigPostSteps.sanitizeUrltestTimings(config)
        val group = ((healed["outbounds"] as? JsonArray)!![0] as kotlinx.serialization.json.JsonObject)
        // The original idle_timeout stays exactly as served.
        assertEquals("2h", (group["idle_timeout"] as JsonPrimitive).content)
        assertEquals(0, notes.size)
    }

    @Test
    fun `duration grammar includes days and rejects garbage`() {
        assertEquals(86_400L * 1_000_000_000L, ConfigPostSteps.parseCoreDurationNanos("1d"))
        assertEquals(90L * 1_000_000_000L, ConfigPostSteps.parseCoreDurationNanos("90s"))
        assertEquals(3L * 3_600_000_000_000L, ConfigPostSteps.parseCoreDurationNanos(" 3h "))
        assertEquals(null, ConfigPostSteps.parseCoreDurationNanos("-5m"))
        assertEquals(null, ConfigPostSteps.parseCoreDurationNanos("soon"))
    }

    @Test
    fun `garbage duration is left for the kernel to reject`() {
        val config = Json.parseToJsonElement(
            """{"outbounds":[{"type":"urltest","tag":"g","outbounds":["a"],"interval":"soon","idle_timeout":"30m"}]}""",
        ).jsonObject
        val (healed, notes) = ConfigPostSteps.sanitizeUrltestTimings(config)
        // The malformed interval is left in place for the kernel to reject
        // with its own message — masking it would hide a real config error.
        val group = ((healed["outbounds"] as? JsonArray)!![0] as kotlinx.serialization.json.JsonObject)
        assertEquals("30m", (group["idle_timeout"] as JsonPrimitive).content)
        assertEquals(0, notes.size)
    }

    // ---------------------------------------------------- utls fingerprints

    private fun outbound(fp: String?, reality: Boolean = true): String {
        val tls = buildString {
            append("\"tls\":{\"enabled\":true")
            if (reality) append(",\"reality\":{\"enabled\":true,\"public_key\":\"pbk\"}")
            if (fp != null) append(",\"utls\":{\"enabled\":true,\"fingerprint\":\"$fp\"}")
            append("}")
        }
        return """{"type":"vless","tag":"n","server":"s","server_port":443,"uuid":"u",$tls}"""
    }

    @Test
    fun `explicit reality fingerprint is kept as-is`() {
        // §444: firefox under REALITY is the node source's choice; rewriting
        // it to chrome broke servers that expect it.
        val config = Json.parseToJsonElement("""{"outbounds":[${outbound("firefox")}]}""").jsonObject
        val (healed, notes) = ConfigPostSteps.healUtlsFingerprints(config)
        val node = (healed["outbounds"] as JsonArray)[0].jsonObject
        val fp = ((node["tls"] as kotlinx.serialization.json.JsonObject)["utls"] as kotlinx.serialization.json.JsonObject)["fingerprint"]
        assertEquals("firefox", (fp as JsonPrimitive).content)
        assertEquals(0, notes.size)
    }

    @Test
    fun `blank and random fingerprints under reality become chrome`() {
        val config = Json.parseToJsonElement(
            """{"outbounds":[${outbound(null)}, ${outbound("random")}]}""",
        ).jsonObject
        val (healed, _) = ConfigPostSteps.healUtlsFingerprints(config)
        val nodes = (healed["outbounds"] as? JsonArray)!!
        for (node in nodes) {
            val tls = node.jsonObject["tls"] as kotlinx.serialization.json.JsonObject
            val utls = tls["utls"] as kotlinx.serialization.json.JsonObject
            assertEquals("chrome", (utls["fingerprint"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `xray aliases and case are canonicalised silently`() {
        val config = Json.parseToJsonElement(
            """{"outbounds":[${outbound("hellochrome_120")}, ${outbound("QQ")}]}""",
        ).jsonObject
        val (healed, notes) = ConfigPostSteps.healUtlsFingerprints(config)
        val nodes = (healed["outbounds"] as? JsonArray)!!
        assertEquals(
            "chrome",
            (((nodes[0].jsonObject["tls"] as kotlinx.serialization.json.JsonObject)["utls"] as kotlinx.serialization.json.JsonObject)["fingerprint"] as JsonPrimitive).content,
        )
        assertEquals(
            "qq",
            (((nodes[1].jsonObject["tls"] as kotlinx.serialization.json.JsonObject)["utls"] as kotlinx.serialization.json.JsonObject)["fingerprint"] as JsonPrimitive).content,
        )
        assertEquals(0, notes.size)
    }

    @Test
    fun `unknown junk becomes chrome with a warning entry`() {
        val config = Json.parseToJsonElement("""{"outbounds":[${outbound("banana")}]}""").jsonObject
        val (healed, notes) = ConfigPostSteps.healUtlsFingerprints(config)
        val node = (healed["outbounds"] as JsonArray)[0].jsonObject
        val fp = (((node["tls"] as kotlinx.serialization.json.JsonObject)["utls"] as kotlinx.serialization.json.JsonObject)["fingerprint"] as JsonPrimitive).content
        assertEquals("chrome", fp)
        assertEquals(1, notes.size)
        assertEquals("n", notes[0].first)
        assertEquals("banana", notes[0].second)
    }

    @Test
    fun `reality without utls block gets a minimal one`() {
        val config = Json.parseToJsonElement("""{"outbounds":[${outbound(null)}]}""").jsonObject
        val (healed, _) = ConfigPostSteps.healUtlsFingerprints(config)
        val node = (healed["outbounds"] as JsonArray)[0].jsonObject
        val tls = node["tls"] as kotlinx.serialization.json.JsonObject
        val utls = tls["utls"] as kotlinx.serialization.json.JsonObject
        assertTrue((utls["enabled"] as JsonPrimitive).booleanOrNull == true)
    }

    @Test
    fun `non-reality explicit fingerprints are also canonicalised but kept`() {
        val config = Json.parseToJsonElement("""{"outbounds":[${outbound("FireFox", reality = false)}]}""").jsonObject
        val (healed, _) = ConfigPostSteps.healUtlsFingerprints(config)
        val node = (healed["outbounds"] as JsonArray)[0].jsonObject
        val fp = (((node["tls"] as kotlinx.serialization.json.JsonObject)["utls"] as kotlinx.serialization.json.JsonObject)["fingerprint"] as JsonPrimitive).content
        assertEquals("firefox", fp)
    }

    @Test
    fun `plain tls node without reality keeps its fingerprint untouched`() {
        val config = Json.parseToJsonElement("""{"outbounds":[${outbound("safari", reality = false)}]}""").jsonObject
        val (healed, _) = ConfigPostSteps.healUtlsFingerprints(config)
        val node = (healed["outbounds"] as JsonArray)[0].jsonObject
        val fp = (((node["tls"] as kotlinx.serialization.json.JsonObject)["utls"] as kotlinx.serialization.json.JsonObject)["fingerprint"] as JsonPrimitive).content
        assertEquals("safari", fp)
        assertFalse(fp == "chrome")
    }
}
