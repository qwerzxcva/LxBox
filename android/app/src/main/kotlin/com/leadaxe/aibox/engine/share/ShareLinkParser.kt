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
     * Clash-style YAML detection: these bodies start with a top-level key
     * (`proxies:`, `proxy-groups:`, …) rather than a share link. Panels serve
     * this format at least as often as base64 link lists, and without a
     * branch for it the whole subscription came back as "unsupported
     * scheme" — one error per line, zero nodes.
     */
    private val yamlProxiesKey = Regex("^\\s*proxies\\s*:", RegexOption.MULTILINE)

    /**
     * Parses a Clash configuration's `proxies:` block into sing-box outbound
     * JSON. Supports the two protocols this client ships (vless, ss) plus
     * the transport/TLS knobs that map 1:1 onto sing-box options.
     *
     * This is a targeted reader, not a YAML implementation: it understands
     * the indentation-based shape Clash configs actually use for the proxy
     * list (block mappings, scalars, and inline `[a, b]` / `{k: v}` values)
     * and ignores everything else. YAML features that don't appear in that
     * subtree (anchors, multi-document, block scalars) are out of scope.
     */
    fun parseClashYaml(text: String): List<Result> {
        val entries = extractProxies(text)
        if (entries.isEmpty()) return emptyList()
        return entries.map { entry ->
            val type = entry["type"].orEmpty().lowercase()
            when (type) {
                "vless" -> clashVless(entry)
                "ss" -> clashShadowsocks(entry)
                else -> Result.Err("unsupported proxy type: $type", entry.toString())
            }
        }
    }

    /**
     * Pulls every mapping under the top-level `proxies:` key. Returns one
     * map per list item; nested maps (ws-opts, reality-opts, …) are flattened
     * into dotted keys (`ws-opts.headers.Host`) so callers stay simple.
     *
     * A stack of (indent, key) tracks the open blocks: a scalar line deeper
     * than the previous key means that key opened a block, and its own keys
     * get the dotted prefix until the indentation returns to a shallower
     * level.
     */
    private fun extractProxies(text: String): List<Map<String, String>> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimEnd().trimStart().startsWith("proxies:") }
        if (start < 0) return emptyList()
        val out = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null
        // Indentation of the `proxies:` key; items live deeper than it.
        val baseIndent = lines[start].indexOf('p')
        var started = false
        // Open blocks inside the current list item: indent → key.
        val stack = ArrayDeque<Pair<Int, String>>()

        for (i in start + 1 until lines.size) {
            val raw = lines[i]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) continue
            // Back to a top-level key: the block ended.
            if (indent <= baseIndent && !raw.trimStart().startsWith("-")) break
            val trimmed = raw.trim()

            if (trimmed.startsWith("- ") || trimmed == "-") {
                // A new list item: flush the previous one and reset nesting.
                current?.let { out += it }
                current = mutableMapOf()
                stack.clear()
                started = true
                val rest = trimmed.removePrefix("-").trim()
                if (rest.isNotEmpty()) {
                    val key = parseClashKey(rest)
                    if (key != null) {
                        val value = rest.substringAfter(':', missingDelimiterValue = "").trim()
                        if (value.isNotEmpty()) current[key] = unquote(value) else {
                            // Bare `- key:` opens a block at the dash indent.
                            stack.addLast(indent to key)
                        }
                    }
                }
                continue
            }
            if (!started) {
                // The block opened without a dash ("proxies:\n  name: x" is
                // invalid Clash, but be forgiving about the first key).
                current = mutableMapOf()
                started = true
            }
            val map = current ?: continue

            // Pop blocks the current line has left behind.
            while (stack.isNotEmpty() && indent <= stack.last().first) stack.removeLast()
            val prefix = stack.joinToString(".") { it.second }

            val key = parseClashKey(trimmed) ?: continue
            val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
            if (value.isEmpty()) {
                // `key:` with no value — opens a nested block.
                stack.addLast(indent to key)
                continue
            }
            val full = if (prefix.isEmpty()) key else "$prefix.$key"
            map[full] = unquote(value)
        }
        current?.let { out += it }
        return out
    }

    /** The bare key of a `key: value` line, unquoted; null when there is none. */
    private fun parseClashKey(line: String): String? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        return line.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'").takeIf { it.isNotEmpty() }
    }

    private fun unquote(value: String): String = value
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .trim()

    private fun clashVless(e: Map<String, String>): Result {
        val server = e["server"] ?: return Result.Err("missing server", e.toString())
        val port = e["port"]?.toIntOrNull() ?: return Result.Err("missing port", e.toString())
        val uuid = e["uuid"] ?: return Result.Err("missing uuid", e.toString())
        val name = e["name"].orEmpty().ifBlank { "$server:$port" }
        val tls = e["tls"]?.equals("true", ignoreCase = true) == true
        val reality = e["reality-opts.public-key"] != null

        val config = buildConfigJson {
            put("server", server)
            put("server_port", port)
            put("uuid", uuid)
            e["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            // transport
            when (e["network"]?.lowercase()) {
                "ws" -> put("transport", buildJsonObject {
                    put("type", "ws")
                    put("path", e["ws-opts.path"] ?: "/")
                    e["ws-opts.headers.Host"]?.let { host ->
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                    e["ws-opts.max-early-data"]?.toIntOrNull()?.let { put("max_early_data", it) }
                    e["ws-opts.early-data-header-name"]?.let { put("early_data_header_name", it) }
                    // AIBox kernel extension: keepalive ping interval.
                    e["ws-opts.ping-interval"]?.takeIf { it.isNotBlank() }?.let { put("ping_interval", it) }
                })
                "grpc" -> put("transport", buildJsonObject {
                    put("type", "grpc")
                    e["grpc-opts.grpc-service-name"]?.let { put("service_name", it) }
                })
                "http" -> put("transport", buildJsonObject {
                    put("type", "http")
                    put("path", e["http-opts.path"] ?: "/")
                })
            }
            // TLS / reality
            if (tls || reality) {
                put("tls", buildJsonObject {
                    put("enabled", true)
                    e["servername"]?.takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                    e["client-fingerprint"]?.let { fp ->
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    e["alpn"]?.let { alpn ->
                        put("alpn", buildJsonArray { splitList(alpn).forEach(::add) })
                    }
                    e["skip-cert-verify"]?.toBoolean()?.let { if (it) put("insecure", true) }
                    val publicKey = e["reality-opts.public-key"]
                    if (publicKey != null) {
                        put("reality", buildJsonObject {
                            put("enabled", true)
                            put("public_key", publicKey)
                            e["reality-opts.short-id"]?.let { put("short_id", it) }
                        })
                    }
                })
            }
        }
        return Result.Ok("vless", config, name)
    }

    private fun clashShadowsocks(e: Map<String, String>): Result {
        val server = e["server"] ?: return Result.Err("missing server", e.toString())
        val port = e["port"]?.toIntOrNull() ?: return Result.Err("missing port", e.toString())
        val method = e["cipher"] ?: e["method"] ?: return Result.Err("missing cipher", e.toString())
        val password = e["password"] ?: return Result.Err("missing password", e.toString())
        val name = e["name"].orEmpty().ifBlank { "$server:$port" }
        val config = buildConfigJson {
            put("server", server)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return Result.Ok("shadowsocks", config, name)
    }

    /** `[a, b]` or `a,b` into its parts, quoted or not. */
    private fun splitList(raw: String): List<String> = raw
        .removePrefix("[")
        .removeSuffix("]")
        .split(',')
        .map { unquote(it) }
        .filter { it.isNotEmpty() }

    /**
     * Parses a subscription body.
     *
     * The shapes seen in the wild:
     *  1. Plain text: one share link per line (plus optional `#` comments).
     *  2. Whole-body base64 (V2RayN, most commercial panels): a single
     *     opaque blob that decodes to shape 1 (or to a Clash YAML body).
     *  3. Clash-style YAML (`proxies:` block).
     *  4. A JSON document: a sing-box configuration (anything carrying an
     *     `outbounds` array), a bare array of outbound objects, or one
     *     outbound object. Pretty-printed JSON spans many lines and would
     *     otherwise be fed to the line parser one token at a time.
     */
    fun parseMany(text: String): List<Result> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Shape 4: a JSON document (checked first — it is unambiguous).
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJsonDocument(trimmed)?.let { return it }
        }

        // Shape 3: a Clash-style YAML configuration, whose `proxies:` key is
        // the reliable tell.
        if (yamlProxiesKey.containsMatchIn(trimmed)) {
            val parsed = parseClashYaml(trimmed)
            if (parsed.isNotEmpty()) return parsed
        }

        // Shape 2: the entire body is one base64 blob.
        if (!trimmed.contains('\n')) {
            val decoded = runCatching { decodeBase64(trimmed) }.getOrNull()
            if (decoded != null && looksLikeSubscription(decoded)) {
                return parseMany(decoded)
            }
        } else {
            // Multi-line bodies occasionally still carry a base64 blob split
            // across lines with whitespace padding (panels that wrap at 76
            // chars). Try the whitespace-stripped whole body too.
            val stripped = trimmed.filterNot { it.isWhitespace() }
            if (stripped.length > 32) {
                val decoded = runCatching { decodeBase64(stripped) }.getOrNull()
                if (decoded != null && looksLikeSubscription(decoded)) {
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
     * True when [text] is worth recursing into after a base64 decode. The
     * old check only accepted `://`, which silently dropped base64-wrapped
     * Clash YAML and sing-box JSON bodies — both contain no share links.
     */
    private fun looksLikeSubscription(text: String): Boolean =
        text.contains("://") ||
            yamlProxiesKey.containsMatchIn(text) ||
            (text.trimStart().startsWith("{") && text.contains("\"outbounds\""))

    /** Outbound types that are config plumbing, never importable nodes. */
    private val plumbingTypes =
        setOf("direct", "block", "dns", "selector", "urltest", "loadbalance")

    /**
     * Parses a whole-body JSON document into nodes. Recognises a sing-box
     * configuration (an object with `outbounds`), a bare array of outbound
     * objects, an array of share-link strings, and a single outbound
     * object. Returns null when none of those shapes match, so the caller
     * falls through to the line parser.
     */
    private fun parseJsonDocument(text: String): List<Result>? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val outbounds: List<JsonObject> = when (element) {
            is JsonObject -> {
                val array = element["outbounds"] as? JsonArray
                when {
                    array != null -> array.filterIsInstance<JsonObject>()
                    element["type"] != null && element["server"] != null -> listOf(element)
                    else -> return null
                }
            }
            is JsonArray -> {
                val links = element.mapNotNull {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                }
                if (links.size == element.size && links.any { it.contains("://") }) {
                    return links.flatMap(::parseMany)
                }
                element.filterIsInstance<JsonObject>()
            }
            else -> return null
        }

        val results = ArrayList<Result>(outbounds.size)
        for (outbound in outbounds) {
            val type = (outbound["type"] as? JsonPrimitive)?.content?.lowercase().orEmpty()
            when {
                type in plumbingTypes -> Unit // not a node
                type == "vless" || type == "shadowsocks" ->
                    results += Result.Ok(type, outbound.toString(), outboundName(outbound))
                type.isNotEmpty() ->
                    results += Result.Err("unsupported proxy type: $type", outbound.toString())
            }
        }
        return results
    }

    /** Node display name: its tag, else server:port. */
    private fun outboundName(node: JsonObject): String =
        (node["tag"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: defaultName(
                (node["server"] as? JsonPrimitive)?.content ?: "node",
                (node["server_port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            )

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