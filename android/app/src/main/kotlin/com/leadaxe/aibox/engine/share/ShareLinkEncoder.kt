package com.leadaxe.aibox.engine.share

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Builds share links from a parsed node config — the inverse of
 * [ShareLinkParser]. Only vless / shadowsocks are round-trippable (the two
 * protocols AIBox ships); everything else yields null and the UI hides the
 * share action for that node.
 *
 * Round-trip guarantee: parse(encode(parse(link))) == parse(link) for the
 * fields the parsers read. Extras the parser kept in the config JSON (ECH,
 * reality pbk/sid, ws ping_interval…) are carried into the query string
 * where a standard parameter exists, and are otherwise silently dropped —
 * a share link must stay importable by other clients (v2rayNG, NekoBox,
 * Streisand), which reject unknown params on some forks.
 */
object ShareLinkEncoder {

    /** The share link for [name], or null when the type can't be encoded. */
    fun encode(type: String, configJson: String, name: String): String? = runCatching {
        val obj = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.parseToJsonElement(configJson).jsonObject
        when (type) {
            "vless" -> vless(obj, name)
            "shadowsocks" -> shadowsocks(obj, name)
            else -> null
        }
    }.getOrNull()

    // vless://uuid@host:port?type=ws&security=tls&sni=…#name
    private fun vless(o: JsonObject, name: String): String {
        val uuid = o.str("uuid")
        val host = o.str("server")
        val port = o.int("server_port")
        val transport = (o["transport"] as? JsonObject)?.str("type") ?: "tcp"
        val tls = o["tls"] as? JsonObject
        val security = when {
            tls?.get("reality") != null -> "reality"
            tls != null -> "tls"
            else -> "none"
        }
        val q = mutableListOf<String>()
        q += "type=$transport"
        q += "security=$security"
        if (transport != "tcp") {
            (o["transport"] as? JsonObject)?.let { tr ->
                tr.str("path")?.let { q += "path=${enc(it)}" }
                tr.str("service_name")?.let { q += "serviceName=${enc(it)}" }
                tr.str("host")?.let { q += "host=${enc(it)}" }
                (tr["headers"] as? JsonObject)?.str("Host")?.let { q += "host=${enc(it)}" }
            }
        }
        tls?.str("server_name")?.let { q += "sni=${enc(it)}" }
        (tls?.get("utls") as? JsonObject)?.str("fingerprint")?.let { q += "fp=${enc(it)}" }
        (tls?.get("reality") as? JsonObject)?.let { r ->
            r.str("public_key")?.let { q += "pbk=${enc(it)}" }
            r.str("short_id")?.let { q += "sid=${enc(it)}" }
        }
        o.str("flow")?.let { q += "flow=${enc(it)}" }
        return "vless://$uuid@$host:$port?${q.joinToString("&")}#${enc(name)}"
    }

    // ss://base64(method:password)@host:port#name  (SIP002, the widely
    // accepted 2022 form; the legacy plain form confuses some clients)
    private fun shadowsocks(o: JsonObject, name: String): String {
        val method = o.str("method")
        val password = o.str("password")
        val host = o.str("server")
        val port = o.int("server_port")
        val userinfo = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$method:$password".toByteArray(Charsets.UTF_8))
        return "ss://$userinfo@$host:$port#${enc(name)}"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private val enc = { raw: String -> java.net.URLEncoder.encode(raw, "UTF-8") }
}
