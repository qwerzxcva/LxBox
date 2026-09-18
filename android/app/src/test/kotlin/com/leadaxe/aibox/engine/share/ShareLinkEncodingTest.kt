package com.leadaxe.aibox.engine.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip contract for [ShareLinkEncoder]: a link parsed and re-encoded
 * must parse back to the same server fields, and links for the two shipped
 * protocols must survive; anything else yields null (UI hides the share
 * action rather than emitting a link other clients would reject).
 */
class ShareLinkEncodingTest {

    @Test
    fun `vless link round-trips through encode and parse`() {
        val link = "vless://uuid-abc@example.com:443?type=ws&security=tls" +
            "&path=%2Fws&host=cdn.example.com&sni=example.com&fp=chrome&flow=xtls-rprx-vision#My%20Node"
        val parsed = ShareLinkParser.parse(link)
        assertTrue(parsed is ShareLinkParser.Result.Ok)
        val ok = parsed as ShareLinkParser.Result.Ok
        val encoded = ShareLinkEncoder.encode(ok.type, ok.config, ok.name)
        assertNotNull(encoded)
        val reparsed = ShareLinkParser.parse(encoded!!)
        assertTrue(reparsed is ShareLinkParser.Result.Ok)
        val again = reparsed as ShareLinkParser.Result.Ok
        assertEquals("vless", again.type)
        assertEquals(ok.config, again.config)
        assertEquals("My Node", again.name)
    }

    @Test
    fun `reality vless carries pbk and sid through the loop`() {
        val link = "vless://u@h.example:443?security=reality&pbk=PubKey123&sid=ab12&sni=real.example#R"
        val parsed = ShareLinkParser.parse(link) as ShareLinkParser.Result.Ok
        val encoded = ShareLinkEncoder.encode(parsed.type, parsed.config, parsed.name)!!
        assertTrue(encoded.contains("pbk=PubKey123"))
        assertTrue(encoded.contains("sid=ab12"))
        val reparsed = ShareLinkParser.parse(encoded) as ShareLinkParser.Result.Ok
        assertEquals(parsed.config, reparsed.config)
    }

    @Test
    fun `shadowsocks SIP002 link round-trips`() {
        val method = "aes-256-gcm"
        val password = "secret-pass"
        val userinfo = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$method:$password".toByteArray(Charsets.UTF_8))
        val link = "ss://$userinfo@1.2.3.4:8388#SS%20Node"
        val parsed = ShareLinkParser.parse(link)
        assertTrue(parsed is ShareLinkParser.Result.Ok)
        val ok = parsed as ShareLinkParser.Result.Ok
        val encoded = ShareLinkEncoder.encode(ok.type, ok.config, ok.name)!!
        assertTrue(encoded.startsWith("ss://"))
        val reparsed = ShareLinkParser.parse(encoded) as ShareLinkParser.Result.Ok
        assertEquals(ok.config, reparsed.config)
    }

    @Test
    fun `unsupported types yield null`() {
        assertNull(ShareLinkEncoder.encode("http", """{"server":"h"}""", "n"))
    }
}
