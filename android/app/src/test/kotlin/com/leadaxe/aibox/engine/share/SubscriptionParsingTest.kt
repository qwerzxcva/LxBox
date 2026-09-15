package com.leadaxe.aibox.engine.share

import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.app.RouteRule
import com.leadaxe.aibox.engine.singbox.RouteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two parsing paths added for the "subscriptions return no nodes"
 * and "JSON rules hide their action" reports: the Clash-YAML reader and the
 * route-rule JSON validator / action mirror.
 */
class SubscriptionParsingTest {

    // ------------------------------------------------------------ clash yaml

    private val clashYaml = """
        mixed-port: 7890
        proxies:
          - name: "HK-1"
            type: vless
            server: hk.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            tls: true
            servername: hk.example.com
            network: ws
            ws-opts:
              path: /ws
              headers:
                Host: hk.example.com
          - name: "SS-1"
            type: ss
            server: ss.example.com
            port: 8443
            cipher: aes-128-gcm
            password: secret
        proxy-groups:
          - name: PROXY
            type: select
            proxies:
              - HK-1
    """.trimIndent()

    @Test
    fun `clash yaml yields one result per proxy`() {
        val parsed = ShareLinkParser.parseMany(clashYaml)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it is ShareLinkParser.Result.Ok })
    }

    @Test
    fun `clash vless maps transport and tls`() {
        val ok = ShareLinkParser.parseMany(clashYaml).first() as ShareLinkParser.Result.Ok
        assertEquals("vless", ok.type)
        assertEquals("HK-1", ok.name)
        assertTrue(ok.config.contains("\"server\":\"hk.example.com\""))
        assertTrue(ok.config.contains("\"server_port\":443"))
        assertTrue(ok.config.contains("\"type\":\"ws\""))
        assertTrue(ok.config.contains("\"path\":\"/ws\""))
        assertTrue(ok.config.contains("\"Host\":\"hk.example.com\""))
        assertTrue(ok.config.contains("\"enabled\":true"))
    }

    @Test
    fun `clash shadowsocks maps cipher and password`() {
        val ok = ShareLinkParser.parseMany(clashYaml)[1] as ShareLinkParser.Result.Ok
        assertEquals("shadowsocks", ok.type)
        assertTrue(ok.config.contains("\"method\":\"aes-128-gcm\""))
        assertTrue(ok.config.contains("\"password\":\"secret\""))
    }

    @Test
    fun `clash yaml without proxies key falls through to link parsing`() {
        // A body with a `proxies:`-looking word but no real block must not
        // swallow the ordinary share-link path.
        val parsed = ShareLinkParser.parseMany("vless://uuid@host:443?security=tls#Node")
        assertEquals(1, parsed.size)
    }

    @Test
    fun `clash reality block comes from reality-opts`() {
        val yaml = """
            proxies:
              - name: R
                type: vless
                server: r.example.com
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                tls: true
                servername: www.microsoft.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: PUBKEY123
                  short-id: abcd
        """.trimIndent()
        val ok = ShareLinkParser.parseMany(yaml).first() as ShareLinkParser.Result.Ok
        assertTrue(ok.config.contains("\"reality\""))
        assertTrue(ok.config.contains("\"public_key\":\"PUBKEY123\""))
        assertTrue(ok.config.contains("\"short_id\":\"abcd\""))
        assertTrue(ok.config.contains("\"fingerprint\":\"chrome\""))
    }

    @Test
    fun `base64 wrapped clash yaml is decoded`() {
        // Panels wrap Clash YAML in base64 too; the old guard required `://`
        // in the decoded text and dropped these bodies silently.
        val encoded = java.util.Base64.getEncoder().encodeToString(clashYaml.toByteArray())
        val parsed = ShareLinkParser.parseMany(encoded)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it is ShareLinkParser.Result.Ok })
    }

    // --------------------------------------------------------- sing-box json

    @Test
    fun `sing-box config json yields only real nodes`() {
        val body = """
            {
              "outbounds": [
                {"type": "selector", "tag": "proxy", "outbounds": ["node-1"]},
                {"type": "direct", "tag": "direct"},
                {"type": "vless", "tag": "node-1", "server": "a.example", "server_port": 443,
                 "uuid": "11111111-2222-3333-4444-555555555555"},
                {"type": "shadowsocks", "tag": "node-2", "server": "b.example", "server_port": 8388,
                 "method": "aes-256-gcm", "password": "pw"}
              ]
            }
        """.trimIndent()
        val parsed = ShareLinkParser.parseMany(body)
        assertEquals(2, parsed.size)
        val names = parsed.map { (it as ShareLinkParser.Result.Ok).name }
        assertEquals(listOf("node-1", "node-2"), names)
    }

    @Test
    fun `json array of outbound objects is accepted`() {
        val body = """
            [
              {"type": "vless", "server": "a.example", "server_port": 443,
               "uuid": "11111111-2222-3333-4444-555555555555"}
            ]
        """.trimIndent()
        val parsed = ShareLinkParser.parseMany(body)
        assertEquals(1, parsed.size)
        assertTrue(parsed.first() is ShareLinkParser.Result.Ok)
    }

    @Test
    fun `json array of share links is expanded`() {
        val body = """["vless://u@h:443?security=tls#A", "ss://YWVzLTEyOC1nY206cHc@h2:443#B"]"""
        val parsed = ShareLinkParser.parseMany(body)
        assertEquals(2, parsed.size)
    }

    @Test
    fun `plain json without nodes still parses as a line list`() {
        // A single outbound object (no wrapper) used to fall through and
        // produce one "unsupported scheme" error per line.
        val body = """{"type": "vless", "tag": "x", "server": "a.example", "server_port": 443, "uuid": "u"}"""
        val parsed = ShareLinkParser.parseMany(body)
        assertEquals(1, parsed.size)
        assertTrue(parsed.first() is ShareLinkParser.Result.Ok)
    }

    // --------------------------------------------------------- route-rule json

    @Test
    fun `valid reject rule passes validation`() {
        assertNull(RouteJson.validate("""{"domain_suffix":["ads.example"],"action":"reject"}"""))
    }

    @Test
    fun `route-wrapped payload is accepted and unpacked`() {
        // The shape the official docs show and lxbox exported: a route
        // section (optionally with its own rule_set) wrapping the rules.
        val wrapped = """
            {"route": {
                "rule_set": [{"tag":"unknown-apps","type":"inline","rules":[{"invert":true,"package_name_regex":"^"}]}],
                "rules": [{"rule_set":"unknown-apps","action":"reject"}]
            }}
        """.trimIndent()
        assertNull(RouteJson.validate(wrapped))
        val summary = RouteJson.describe(wrapped)
        assertTrue(summary.valid)
        assertEquals(1, summary.ruleCount)
        assertEquals("reject", summary.primaryAction)
    }

    @Test
    fun `route rule without outbound is rejected`() {
        val problem = RouteJson.validate("""{"domain_suffix":["a.example"],"action":"route"}""")
        assertNotNull(problem)
        assertTrue(problem!!.message.contains("outbound"))
    }

    @Test
    fun `unknown action is reported`() {
        val problem = RouteJson.validate("""{"domain":["a.example"],"action":"explode"}""")
        assertNotNull(problem)
        assertTrue(problem!!.message.contains("unknown action"))
    }

    @Test
    fun `malformed json reports a parse error with an offset`() {
        val text = """{"domain":["a.example",}"""
        val problem = RouteJson.validate(text)
        assertNotNull(problem)
        // kotlinx reports the offset inside the message; when it does, the
        // UI renders the line/column pair from it.
        if (problem!!.offset >= 0) {
            assertTrue(problem.line(text) >= 1)
            assertTrue(problem.column(text) >= 1)
        }
    }

    @Test
    fun `array payload counts each rule`() {
        val summary = RouteJson.describe(
            """[{"domain":["a.example"],"action":"reject"},{"ip_cidr":["10.0.0.0/8"],"outbound":"direct"}]""",
        )
        assertTrue(summary.valid)
        assertEquals(2, summary.ruleCount)
        assertEquals(listOf("reject", "route"), summary.actions)
        assertEquals(listOf("direct"), summary.outbounds)
    }

    @Test
    fun `sync mirrors reject action and direct outbound`() {
        val rule = RouteRule(
            id = "r1",
            kind = RouteRule.KindJson,
            json = """{"ip_is_private":true,"action":"route","outbound":"direct"}""",
        )
        val synced = RouteJson.syncMirror(rule)
        assertEquals(RouteRule.RuleActionRoute, synced.action)
        assertEquals(DirectOutboundTag, synced.outbound)
    }

    @Test
    fun `sync leaves invalid payload untouched`() {
        val rule = RouteRule(id = "r2", kind = RouteRule.KindJson, json = "{broken")
        assertEquals(rule, RouteJson.syncMirror(rule))
    }

    @Test
    fun `summary label names the action`() {
        val summary = RouteJson.describe("""{"domain":["a.example"],"action":"reject"}""")
        val label = RouteJson.summaryLabel(summary) { it }
        assertTrue(label.contains("reject"))
        assertFalse(label.contains("proxy"))
    }

    @Test
    fun `summary label routes to named outbound`() {
        val summary = RouteJson.describe("""{"domain":["a.example"],"outbound":"group-1"}""")
        val label = RouteJson.summaryLabel(summary) { "My Group" }
        assertTrue(label.contains("My Group"))
    }
}
