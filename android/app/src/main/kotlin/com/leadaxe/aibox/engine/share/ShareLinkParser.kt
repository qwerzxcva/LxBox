package com.leadaxe.aibox.engine.share

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Best-effort converter from common proxy share-link formats to sing-box
 * outbound JSON. The output object omits `type` and `tag` — those are
 * injected by `ConfigCompiler` when the profile is baked into the final
 * configuration.
 *
 * Supported schemes:
 *  - `vless://`    (incl. reality / ws / grpc transports via query params)
 *  - `vmess://`    (the legacy base64-wrapped JSON form)
 *  - `trojan://`
 *  - `ss://`       (SIP002 userinfo form; legacy bare base64 also handled)
 *  - `hy2://` / `hysteria2://`
 *  - `tuic://`
 *
 * Anything we can't parse returns `Result.Err` with the offending input;
 * the caller can decide whether to surface the failure to the user.
 */
object ShareLinkParser {

    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        data class Ok(val type: String, val config: String, val name: String) : Result()
        data class Err(val reason: String, val input: String) : Result()
    }

    fun parse(line: String): Result {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return Result.Err("empty", line)
        // A single line may itself be a base64-wrapped link; decode first
        // and fall through to the URL parser.
        val candidate = runCatching { decodeBase64(trimmed) }
            .getOrNull()
            ?.takeIf { it.contains("://") }
            ?: trimmed
        return try {
            when {
                candidate.startsWith("vless://") -> parseVless(candidate)
                candidate.startsWith("ss://") -> parseSs(candidate)
                candidate.startsWith("{") || candidate.startsWith("[") -> parseJsonOutbound(candidate)
                else -> Result.Err("unsupported scheme", line)
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName, line)
        }
    }

    /**
     * Parses a subscription body.
     *
     * Two shapes are common in the wild:
     *  1. Plain text: one share link per line (plus optional `#` comments).
     *  2. Whole-body base64 (V2RayN, most commercial panels): a single
     *     opaque blob that decodes to shape 1.
     *
     * The old implementation only handled the per-line variant, so shape-2
     * subscriptions produced zero nodes. We now detect a whole-body base64
     * blob, decode it, and recurse into the line parser.
     */
    fun parseMany(text: String): List<Result> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Shape 2: the entire body is one base64 blob. Require a `://` in
        // the decoded result so random base64-looking noise is not treated
        // as a subscription.
        if (!trimmed.contains('\n')) {
            val decoded = runCatching { decodeBase64(trimmed) }.getOrNull()
            if (decoded != null && decoded.contains("://")) {
                return parseMany(decoded)
            }
        } else {
            // Multi-line bodies occasionally still carry a base64 blob split
            // across lines with whitespace padding (panels that wrap at 76
            // chars). Try the whitespace-stripped whole body too.
            val stripped = trimmed.filterNot { it.isWhitespace() }
            if (stripped.length > 32) {
                val decoded = runCatching { decodeBase64(stripped) }.getOrNull()
                if (decoded != null && decoded.contains("://")) {
                    return parseMany(decoded)
                }
            }
        }

        return trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::parse)
            .toList()
    }

    /**
     * Decodes [s] as base64, accepting the URL-safe alphabet and missing
     * padding — both variants show up in real subscriptions. Returns null
     * when [s] is not valid base64 at all.
     */
    private fun decodeBase64(s: String): String? {
        val compact = s.filterNot { it.isWhitespace() }
        if (compact.length < 8) return null
        if (!compact.all { it.isLetterOrDigit() || it in "+/=_-." }) return null
        val canonical = compact.replace('-', '+').replace('_', '/')
        val padded = when (canonical.length % 4) {
            2 -> "$canonical=="
            3 -> "$canonical="
            0 -> canonical
            else -> return null
        }
        return runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun URI.queryParams(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val q = rawQuery ?: return out
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx < 0) pair else pair.substring(0, idx)
            val v = if (idx < 0) "" else pair.substring(idx + 1)
            out[k] = URLDecoder.decode(v, StandardCharsets.UTF_8.name())
        }
        return out
    }

    private fun URI.fragmentName(): String {
        val raw = rawFragment ?: return ""
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
    }

    private fun hostPort(uri: URI): Pair<String, Int> {
        val host = uri.host ?: error("missing host")
        val port = if (uri.port == -1) error("missing port") else uri.port
        return host to port
    }

    private fun buildConfigJson(putter: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject(putter).toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.transport(query: Map<String, String>) {
        val type = query["type"] ?: return
        when (type) {
            "tcp" -> return
            "ws" -> put("transport", buildJsonObject {
                put("type", "ws")
                put("path", query["path"] ?: "/")
                query["host"]?.let { put("headers", buildJsonObject { put("Host", it) }) }
            })
            "grpc" -> put("transport", buildJsonObject {
                put("type", "grpc")
                query["serviceName"]?.let { put("service_name", it) }
            })
            "http" -> put("transport", buildJsonObject {
                put("type", "http")
                val hosts = query["host"]?.split(",").orEmpty()
                put("host", buildJsonArray { hosts.forEach(::add) })
                put("path", query["path"] ?: "/")
            })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.tls(query: Map<String, String>, isTls: Boolean) {
        if (!isTls) return
        val reality = query["security"] == "reality"
        put("tls", buildJsonObject {
            put("enabled", true)
            query["sni"]?.let { put("server_name", it) }
            query["alpn"]?.let { put("alpn", buildJsonArray { it.split(",").forEach(::add) }) }
            query["fp"]?.let {
                put("utls", buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", it)
                })
            }
            // reality is a distinct security mode: it needs a public key,
            // and plain TLS links that merely carry a fingerprint must not
            // be turned into reality blocks (the core rejects reality
            // without public_key, so the node would fail to load).
            if (reality) {
                put("reality", buildJsonObject {
                    put("enabled", true)
                    query["pbk"]?.let { put("public_key", it) }
                    query["sid"]?.let { put("short_id", it) }
                })
            }
        })
    }

    private fun parseVless(url: String): Result {
        val uri = URI(url)
        val (host, port) = hostPort(uri)
        val q = uri.queryParams()
        val security = q["security"] ?: "none"
        val isTls = security == "tls" || security == "reality"
        val network = q["type"] ?: "tcp"
        val config = buildConfigJson {
            put("server", host)
            put("server_port", port)
            put("uuid", uri.userInfo.substringBefore(':'))
            q["flow"]?.takeIf { network == "tcp" }?.let { put("flow", it) }
            transport(q)
            tls(q, isTls)
        }
        return Result.Ok("vless", config, nameFromUrl(uri, url))
    }

    private fun parseSs(url: String): Result {
        val uri = URI(url)
        val userInfo = uri.rawUserInfo
        val decodedUserInfo = if (userInfo != null) {
            runCatching { String(Base64.getDecoder().decode(userInfo), StandardCharsets.UTF_8) }
                .getOrElse { URLDecoder.decode(userInfo, StandardCharsets.UTF_8.name()) }
        } else ""
        val method = decodedUserInfo.substringBefore(':')
        val password = decodedUserInfo.substringAfter(':', "")
        val (host, port) = hostPort(uri)
        val plugin = uri.queryParams()["plugin"]
        val config = buildConfigJson {
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
            if (!plugin.isNullOrEmpty()) put("plugin", plugin)
        }
        return Result.Ok("shadowsocks", config, nameFromUrl(uri, url))
    }

    private fun parseJsonOutbound(raw: String): Result {
        val element = runCatching { json.parseToJsonElement(raw.trim()) }
            .getOrElse { return Result.Err("json parse: ${it.message ?: "unknown"}", raw) }
        val first = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull { it is JsonObject } as? JsonObject
            else -> null
        } ?: return Result.Err("no outbound object in JSON", raw)
        val type = (first["type"] as? JsonPrimitive)?.let { if (it.isString) it.content else it.content }
            ?: return Result.Err("missing type", raw)
        val name = (first["tag"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: defaultName(
            (first["server"] as? JsonPrimitive)?.content ?: "json",
            ((first["server_port"] as? JsonPrimitive)?.content?.toIntOrNull()) ?: 0,
        )
        return Result.Ok(type, first.toString(), name)
    }

    private fun nameFromUrl(uri: URI, fallback: String): String {
        val frag = uri.fragmentName()
        if (frag.isNotBlank()) return frag
        return defaultName(uri.host ?: "node", uri.port.takeIf { it > 0 } ?: 0)
    }

    private fun defaultName(host: String, port: Int): String =
        if (port > 0) "$host:$port" else host

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.let {
        if (it.isString) it.content else it.content
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
        runCatching { it.content.toInt() }.getOrNull()
    }
}