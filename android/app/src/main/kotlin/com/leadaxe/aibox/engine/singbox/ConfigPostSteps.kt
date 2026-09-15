package com.leadaxe.aibox.engine.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compile-time post steps, ported from the reference client's 2.24.0 fixes.
 * Each step walks the finished config and repairs shapes the kernel would
 * reject at start (or silently mis-serve) — one place per rule, not a check
 * at every input, because the inputs are many (subscriptions, share links,
 * hand-pasted JSON, user groups) and the rules are few.
 */
object ConfigPostSteps {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The kernel's uTLS dictionary (`uTLSClientHelloID`, case-sensitive).
     * A fingerprint outside it is a fatal "unknown uTLS fingerprint" when
     * the outbound is constructed — one broken node from a subscription
     * would take the whole VPN down.
     */
    val kUtlsFingerprints = setOf(
        "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "firefox", "edge", "safari", "360", "qq",
        "ios", "android", "random", "randomized",
    )

    /**
     * Chrome family: the only dictionary names whose ClientHello carries the
     * X25519MLKEM768 key share that REALITY servers on Xray ≥ v26.9.8
     * require. Other names are silently proxied to the camouflage site
     * ("reality verification failed").
     */
    private val kChromeFamily = setOf(
        "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk",
    )

    /** Xray accepts raw uTLS library names (`hellochrome_120`, …); map by prefix. */
    private val xrayAliasPrefixes = listOf(
        "hellochrome" to "chrome",
        "hellofirefox" to "firefox",
        "helloedge" to "edge",
        "hellosafari" to "safari",
        "hello360" to "360",
        "helloqq" to "qq",
        "helloios" to "ios",
        "helloandroid" to "android",
        "hellorandom" to "random",
    )

    /**
     * Canonicalises uTLS fingerprints across every outbound:
     *
     *  - xray aliases (`hellochrome_120`) → dictionary name, silent;
     *  - case fixed, silent;
     *  - unknown junk → `chrome` + warning (the node is almost certainly
     *    alive — the fingerprint is client-side only);
     *  - REALITY nodes keep their explicit fingerprint **as-is** (upstream
     *    2.24.0 §444: rewriting firefox/safari/… to chrome broke servers
     *    that expect it); `chrome` is written only where there was no
     *    choice — missing, empty, or `random`, which parsers emit for a
     *    blank `fp` and is indistinguishable from an explicit one.
     *
     * Also removes the dead utls+reality-on-QUIC combination (hysteria2/tuic
     * are not in the protocol set any more, but a hand-pasted JSON could
     * still carry it).
     *
     * Returns the list of junk replacements (`tag` to original value) for
     * the compile log.
     */
    fun healUtlsFingerprints(config: JsonObject): Pair<JsonObject, List<Pair<String, String>>> {
        val healed = mutableListOf<Pair<String, String>>()
        val outbounds = config["outbounds"] as? JsonArray ?: return config to healed
        val newOutbounds = JsonArray(outbounds.map outboundMap@{ element ->
            val outbound = element as? JsonObject ?: return@outboundMap element
            val tls = outbound["tls"] as? JsonObject ?: return@outboundMap outbound
            val mutableTls = tls.toMutableMap()
            val mutableOutbound = outbound.toMutableMap()

            // QUIC transports cannot carry utls/reality — drop both rather
            // than shipping a node the kernel treats as dead.
            if ((mutableOutbound["type"] as? JsonPrimitive)?.content in setOf("hysteria2", "tuic")) {
                mutableTls.remove("utls")
                mutableTls.remove("reality")
                if (mutableTls.isEmpty()) {
                    mutableOutbound.remove("tls")
                    return@outboundMap JsonObject(mutableOutbound)
                }
                mutableOutbound["tls"] = JsonObject(mutableTls)
                return@outboundMap JsonObject(mutableOutbound)
            }

            val reality = mutableTls["reality"] as? JsonObject
            val realityOn = reality?.get("enabled")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
            val utls = mutableTls["utls"] as? JsonObject

            // REALITY without a uTLS block is a fatal "uTLS is required by
            // reality client" — restore a minimal one (a blank fingerprint
            // reads as chrome).
            if (realityOn && utls == null) {
                mutableTls["utls"] = JsonObject(
                    mutableMapOf("enabled" to JsonPrimitive(true)),
                )
            } else if (realityOn && utls?.get("enabled")?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) {
                val fixed = (mutableTls["utls"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                fixed["enabled"] = JsonPrimitive(true)
                mutableTls["utls"] = JsonObject(fixed)
            }

            val effectiveUtls = mutableTls["utls"] as? JsonObject
            if (effectiveUtls != null) {
                val fixedUtls = effectiveUtls.toMutableMap()
                val fp = (fixedUtls["fingerprint"] as? JsonPrimitive)?.content
                if (fp != null && fp.isNotBlank()) {
                    val normalized = normalizeFingerprint(fp)
                    if (normalized.value != fp) {
                        if (normalized.value.isEmpty()) {
                            fixedUtls.remove("fingerprint")
                        } else {
                            fixedUtls["fingerprint"] = JsonPrimitive(normalized.value)
                            if (normalized.junk) {
                                healed += (mutableOutbound["tag"] as? JsonPrimitive)?.content.orEmpty() to fp
                            }
                        }
                    }
                }
                if (realityOn) {
                    val current = (fixedUtls["fingerprint"] as? JsonPrimitive)?.content
                    if (current.isNullOrBlank() || current == "random") {
                        fixedUtls["fingerprint"] = JsonPrimitive("chrome")
                    }
                    // Any other dictionary value is the node source's choice — kept.
                }
                if (fixedUtls.isEmpty()) {
                    mutableTls.remove("utls")
                } else {
                    mutableTls["utls"] = JsonObject(fixedUtls)
                }
            }

            if (mutableTls.isEmpty()) {
                mutableOutbound.remove("tls")
            } else {
                mutableOutbound["tls"] = JsonObject(mutableTls)
            }
            JsonObject(mutableOutbound)
        })
        return JsonObject(config.toMutableMap().apply { put("outbounds", newOutbounds) }) to healed
    }

    private fun normalizeFingerprint(raw: String): FingerprintNormalization {
        val lower = raw.trim().lowercase()
        if (lower.isEmpty()) return FingerprintNormalization("", junk = false)
        if (lower in kUtlsFingerprints) return FingerprintNormalization(lower, junk = false)
        // xray library aliases share a prefix with their canonical name.
        for ((prefix, canonical) in xrayAliasPrefixes) {
            if (lower.startsWith(prefix)) return FingerprintNormalization(canonical, junk = false)
        }
        return FingerprintNormalization("chrome", junk = true)
    }

    private data class FingerprintNormalization(val value: String, val junk: Boolean)

    /**
     * urltest timing sanitizer (upstream 2.24.0 §442): the kernel builds the
     * group with a zero `interval` → 3m, zero `idle_timeout` → 30m, and
     * **rejects** `interval > idle_timeout` in the group constructor — a
     * config that passes `sing-box check` but kills the VPN at start. A
     * provider group that ships `interval: 3h` (or a user group with a rare
     * probe) hits exactly that.
     *
     * Policy (same as upstream): raise `idle_timeout` to the `interval`
     * value, never shorten the interval — the interval is how often the
     * provider asked for probes, and cutting it would probe their servers
     * more than configured. Durations parse with the kernel's own rules
     * (suffix `d` included); a value the kernel itself would reject is left
     * alone so the user sees the real config error.
     *
     * Returns the config and the list of repairs (`tag` to explanation).
     */
    fun sanitizeUrltestTimings(config: JsonObject): Pair<JsonObject, List<Pair<String, String>>> {
        val repairs = mutableListOf<Pair<String, String>>()
        val coreIntervalNs = 3L * 60 * 1_000_000_000L
        val coreIdleNs = 30L * 60 * 1_000_000_000L

        fun effectiveDuration(obj: JsonObject, key: String, defaultNs: Long): Pair<Long, String?>? {
            val element = obj[key] ?: return defaultNs to null
            // Numbers (bare 0) take the core default, same as a missing key.
            val number = (element as? JsonPrimitive)?.content?.toLongOrNull()
            if (element is JsonPrimitive && element.isString.not() && number == 0L) {
                return defaultNs to null
            }
            val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val ns = parseCoreDurationNanos(raw) ?: return null
            if (ns < 0) return null
            if (ns == 0L) return defaultNs to null
            return ns to raw
        }

        val outbounds = config["outbounds"] as? JsonArray ?: return config to repairs
        val newOutbounds = JsonArray(outbounds.map timingMap@{ element ->
            val outbound = element as? JsonObject ?: return@timingMap element
            if ((outbound["type"] as? JsonPrimitive)?.content != "urltest") {
                return@timingMap outbound
            }
            val interval = effectiveDuration(outbound, "interval", coreIntervalNs)
                ?: return@timingMap outbound // kernel will reject the raw value itself
            val idle = effectiveDuration(outbound, "idle_timeout", coreIdleNs)
                ?: return@timingMap outbound
            if (interval.first <= idle.first) return@timingMap outbound

            val target = interval.second ?: "3m" // the core's default literal
            val tag = (outbound["tag"] as? JsonPrimitive)?.content.orEmpty()
            val wasDefault = idle.second == null
            repairs += tag to
                ("interval " + (interval.second ?: "3m (core default)") +
                    " is greater than idle_timeout " + (idle.second ?: "30m (core default)") +
                    " — idle_timeout " + (if (wasDefault) "set" else "raised") + " to \"$target\"")
            JsonObject(outbound.toMutableMap().apply { put("idle_timeout", JsonPrimitive(target)) })
        })
        return JsonObject(config.toMutableMap().apply { put("outbounds", newOutbounds) }) to repairs
    }

    /** The kernel's duration grammar: number + unit, `d` included. */
    fun parseCoreDurationNanos(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val match = Regex("^(\\d+)(ns|us|µs|ms|s|m|h|d)$").find(text) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        return value * when (match.groupValues[2]) {
            "ns" -> 1L
            "us", "µs" -> 1_000L
            "ms" -> 1_000_000L
            "s" -> 1_000_000_000L
            "m" -> 60L * 1_000_000_000L
            "h" -> 3_600L * 1_000_000_000L
            "d" -> 86_400L * 1_000_000_000L
            else -> return null
        }
    }
}
