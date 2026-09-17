package com.leadaxe.aibox.ui

import com.leadaxe.aibox.app.OutboundProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a node name like "🇭🇰 HK 01" into its flag emoji + region label,
 * and aggregates nodes by region for the Subscriptions and RouteRule pickers.
 *
 * The flag emoji is always the first two regional-indicator letters
 * (U+1F1E6–U+1F1FF) in the name. Everything before or around it is ignored
 * — subscription panels stuff all kinds of suffixes ("GIA", "01", "VIP",
 * "ISP-BGP"), so we don't try to parse that.
 */
object FlagParser {

    /** Regional indicator A code point — the start of any flag emoji. */
    private const val RI_BEGIN = 0x1F1E6
    private const val RI_END = 0x1F1FF

    /** Returns true when this code point is a regional indicator A–Z. */
    private fun isRegionalIndicator(cp: Int): Boolean = cp in RI_BEGIN..RI_END

    /**
     * Extracts the first flag emoji from [name], or null if the name has no
     * two consecutive regional indicators. Skips VS16 and zero-width joiners
     * so it works even when the panel adds variation selectors.
     */
    fun extractFlag(name: String): String? {
        var i = 0
        val codepoints = name.codePoints().toArray()
        while (i + 1 < codepoints.size) {
            val a = codepoints[i]
            val b = codepoints[i + 1]
            if (isRegionalIndicator(a) && isRegionalIndicator(b)) {
                return String(Character.toChars(a)) + String(Character.toChars(b))
            }
            i++
        }
        return null
    }

    /** Two-letter ISO country code from a flag emoji, or null. */
    fun flagToCountryCode(flag: String): String? {
        val cps = flag.codePoints().toArray()
        if (cps.size != 2) return null
        val a = cps[0]
        val b = cps[1]
        if (!isRegionalIndicator(a) || !isRegionalIndicator(b)) return null
        val letterA = (a - RI_BEGIN + 'A'.code).toChar()
        val letterB = (b - RI_BEGIN + 'A'.code).toChar()
        return "$letterA$letterB"
    }

    /**
     * Human-readable region label. Falls back to the two-letter code when
     * we don't have a mapping — the flag emoji itself still identifies it.
     */
    fun regionLabel(flag: String): String {
        val code = flagToCountryCode(flag) ?: return "Unknown"
        return KNOWN_REGIONS[code] ?: code
    }

    /**
     * Strips the leading flag emoji + any whitespace from [name].
     * Keeps "🇭🇰 HK-01" → "HK-01" so the title bar is cleaner.
     */
    fun stripLeadingFlag(name: String): String {
        val flag = extractFlag(name) ?: return name.trim()
        val trimmed = name.trimStart()
        if (trimmed.startsWith(flag)) {
            return trimmed.removePrefix(flag).trimStart('-', ' ', '_', ':')
        }
        return name.trim()
    }

    // --- JSON helpers for reading server/port from OutboundProfile.config ---

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses [config] into a JsonObject, or null if malformed. */
    fun parseConfig(config: String): JsonObject? = try {
        json.parseToJsonElement(config).jsonObject
    } catch (_: Exception) {
        null
    }

    /** Server hostname or IP from config, empty string when absent. */
    fun serverOf(node: OutboundProfile): String {
        val obj = parseConfig(node.config) ?: return ""
        return obj["server"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    /** Port number as String from config, empty string when absent. */
    fun portOf(node: OutboundProfile): String {
        val obj = parseConfig(node.config) ?: return ""
        return obj["port"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    /** Display line: "vless · hk1.example.com:443" or just "vless". */
    fun protocolLine(node: OutboundProfile): String {
        val server = serverOf(node)
        val port = portOf(node)
        return if (server.isNotBlank() && port.isNotBlank()) {
            "${node.type} · $server:$port"
        } else if (server.isNotBlank()) {
            "${node.type} · $server"
        } else {
            node.type
        }
    }
}

/**
 * One group of nodes under the same flag emoji. Rendered as a collapsible
 * row in both the Subscriptions nodes list and the RouteRule outbound picker.
 */
data class RegionGroup(
    val flagEmoji: String,   // 🇭🇰
    val regionName: String,  // Hong Kong
    val nodes: List<OutboundProfile>,
) {
    /** Stable key for LazyColumn items and remember maps. */
    val key: String get() = flagEmoji
}

/** Sentinel used for nodes that carry no flag at all. */
const val UNKNOWN_FLAG = "\uD83C\uDF10" // 🌐

/**
 * Aggregates [nodes] by their leading flag emoji. Nodes without a flag all
 * land in one "Unknown" bucket. The returned list is sorted:
 *
 *   1. Known major regions first (HK, SG, JP, US, UK, CN …)
 *   2. Alphabetically by region label
 *   3. Unknown bucket always last
 */
fun groupByRegion(nodes: List<OutboundProfile>): List<RegionGroup> {
    val buckets = LinkedHashMap<String, MutableList<OutboundProfile>>()
    for (node in nodes) {
        val flag = FlagParser.extractFlag(node.name) ?: UNKNOWN_FLAG
        buckets.getOrPut(flag) { mutableListOf() }.add(node)
    }
    // Sort each bucket's nodes by the original subscription name, config order preserved.
    buckets.values.forEach { list.sortBy { it.name } }

    val groups = buckets.map { (flag, members) ->
        RegionGroup(
            flagEmoji = flag,
            regionName = if (flag == UNKNOWN_FLAG) "Unknown" else FlagParser.regionLabel(flag),
            nodes = members,
        )
    }

    val priority = listOf("HK", "SG", "JP", "US", "UK", "CN", "TW", "KR", "AU", "DE", "FR", "NL", "CH", "AE", "IN", "TH", "ID", "MY")
    return groups.sortedWith(
        compareBy<RegionGroup> { g ->
            val code = FlagParser.flagToCountryCode(g.flagEmoji)
            val idx = if (code != null) priority.indexOf(code) else -1
            if (idx >= 0) idx else 9999
        }.thenBy { it.regionName }
    )
}

// --- Region label table. Covers the flags the three big providers offer. ---

private val KNOWN_REGIONS = mapOf(
    // Asia
    "HK" to "Hong Kong",
    "MO" to "Macau",
    "SG" to "Singapore",
    "JP" to "Japan",
    "KR" to "South Korea",
    "TW" to "Taiwan",
    "CN" to "China",
    "IN" to "India",
    "TH" to "Thailand",
    "VN" to "Vietnam",
    "MY" to "Malaysia",
    "ID" to "Indonesia",
    "PH" to "Philippines",
    "BD" to "Bangladesh",
    "PK" to "Pakistan",
    "LA" to "Laos",
    "KH" to "Cambodia",
    "MM" to "Myanmar",
    "NP" to "Nepal",
    "LK" to "Sri Lanka",
    // Middle East
    "AE" to "United Arab Emirates",
    "SA" to "Saudi Arabia",
    "QA" to "Qatar",
    "BH" to "Bahrain",
    "KW" to "Kuwait",
    "TR" to "Turkey",
    "IL" to "Israel",
    "JO" to "Jordan",
    // Europe
    "UK" to "United Kingdom",
    "GB" to "United Kingdom",
    "DE" to "Germany",
    "FR" to "France",
    "NL" to "Netherlands",
    "CH" to "Switzerland",
    "IT" to "Italy",
    "ES" to "Spain",
    "PT" to "Portugal",
    "PL" to "Poland",
    "RU" to "Russia",
    "UA" to "Ukraine",
    "SE" to "Sweden",
    "NO" to "Norway",
    "FI" to "Finland",
    "DK" to "Denmark",
    "IE" to "Ireland",
    "BE" to "Belgium",
    "AT" to "Austria",
    "CZ" to "Czechia",
    "GR" to "Greece",
    "RO" to "Romania",
    "BG" to "Bulgaria",
    "HU" to "Hungary",
    // Americas
    "US" to "United States",
    "CA" to "Canada",
    "BR" to "Brazil",
    "AR" to "Argentina",
    "MX" to "Mexico",
    "CL" to "Chile",
    "CO" to "Colombia",
    "PE" to "Peru",
    // Oceania
    "AU" to "Australia",
    "NZ" to "New Zealand",
    // Africa
    "ZA" to "South Africa",
    "EG" to "Egypt",
    "KE" to "Kenya",
    "NG" to "Nigeria",
    "MA" to "Morocco",
)
