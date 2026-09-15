package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.app.ProxySelectorTag
import com.leadaxe.aibox.app.RouteRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Parses and validates raw sing-box route-rule JSON pasted into a JSON-kind
 * [RouteRule].
 *
 * Two jobs:
 *
 *  1. **Recognise the action.** A JSON rule without `action` silently
 *     inherits the kernel default (`route` with an empty outbound, which
 *     matches everything and keeps the main selector as the exit). The
 *     editor used to present every JSON rule as if it routed to the proxy,
 *     which is wrong for `reject` / `resolve` payloads and for rules that
 *     pick their own outbound. [describe] extracts what the payload
 *     actually says so the list can show it.
 *  2. **Validate before saving.** A malformed payload is currently accepted
 *     (it simply compiles to nothing), which reads as "the rule silently
 *     stopped working". [validate] reports the first problem with its
 *     position, so the editor can point the user at it.
 */
internal object RouteJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** What a JSON payload describes, once parsed. */
    data class Summary(
        /** True when the payload is one or more JSON objects. */
        val valid: Boolean,
        val ruleCount: Int = 0,
        /** Distinct actions found, in first-seen order ("route", "reject", …). */
        val actions: List<String> = emptyList(),
        /** Distinct outbound tags referenced by route actions, first-seen order. */
        val outbounds: List<String> = emptyList(),
        /** The rule carries at least one matcher key (not an action-only stub). */
        val hasMatchers: Boolean = false,
    ) {
        /** `reject` when the payload only rejects, else the first outbound. */
        val primaryAction: String get() = actions.firstOrNull().orEmpty()
        val primaryOutbound: String get() = outbounds.firstOrNull().orEmpty()
    }

    /** Keys that belong to the action rather than to the match. */
    private val actionKeys = setOf("action", "outbound", "client_subnet", "strategy", "override_address", "override_port")

    /**
     * Actions the kernel understands. Mirrors constant/rule.go; kept local so
     * validation does not need the core.
     */
    val knownActions = setOf("route", "route-options", "reject", "hijack-dns", "sniff", "resolve")

    /**
     * Validates [text] as a sing-box route-rule payload.
     *
     * Accepts a single object or an array of objects, the two shapes
     * `compileJsonRule` understands. Returns null when the payload is usable,
     * otherwise a human-readable problem description; [offset] points at the
     * first offending character when the underlying parser reported one, so
     * the UI can render the line.
     */
    fun validate(text: String): Problem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Problem("empty", -1)

        val element = try {
            json.parseToJsonElement(trimmed)
        } catch (t: Throwable) {
            return Problem(parseMessage(t), parseOffset(t))
        }

        val rules = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.mapIndexed { index, item ->
                item as? JsonObject
                    ?: return Problem("array entry ${index + 1} is not an object", -1)
            }
            else -> return Problem("top level must be an object or an array of objects", -1)
        }
        if (rules.isEmpty()) return Problem("the array is empty", -1)

        rules.forEachIndexed { index, rule -> validateRule(rule, index, rules.size)?.let { return it } }
        return null
    }

    private fun validateRule(rule: JsonObject, index: Int, total: Int): Problem? {
        val where = if (total > 1) "rule ${index + 1}: " else ""

        val action = (rule["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (action != null && action !in knownActions) {
            return Problem(where + "unknown action \"$action\" (expected one of ${knownActions.sorted().joinToString(", ")})", -1)
        }

        // Route actions (explicit or implied) need something to route to —
        // an empty outbound is what makes a JSON rule quietly hit the default
        // selector. `final` is the one legitimate exception (the kernel reads
        // it from route.final rather than the rule).
        val effective = action ?: "route"
        if (effective == "route") {
            val outbound = (rule["outbound"] as? JsonPrimitive)?.content.orEmpty()
            if (outbound.isBlank() && "override_address" !in rule) {
                return Problem(
                    where + "a route rule needs an \"outbound\" (or an explicit \"action\": \"reject\"/\"resolve\"/\"sniff\"/\"hijack-dns\")",
                    -1,
                )
            }
        }

        val matchers = rule.keys.filterNot { it in actionKeys }
        if (matchers.isEmpty() && effective == "route") {
            return Problem(where + "no match conditions — the rule would match every connection", -1)
        }

        return null
    }

    /** A validation failure. [offset] is a character index when known, else -1. */
    data class Problem(val message: String, val offset: Int) {
        /** 1-based line number for [offset], or -1 when not derivable. */
        fun line(text: String): Int {
            if (offset < 0 || offset > text.length) return -1
            return text.take(offset).count { it == '\n' } + 1
        }

        fun column(text: String): Int {
            if (offset < 0 || offset > text.length) return -1
            val lastNewline = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
            return offset - lastNewline
        }
    }

    private fun parseMessage(t: Throwable): String {
        val raw = t.message ?: return "invalid JSON"
        // kotlinx.serialization messages carry the offset in the text; keep
        // the human part and drop the coordinates (we render our own).
        return raw.substringBefore(" at path").substringBefore(" at offset").trim().ifBlank { "invalid JSON" }
    }

    private fun parseOffset(t: Throwable): Int {
        val text = t.message ?: return -1
        val match = Regex("offset (\\d+)").find(text) ?: return -1
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: -1
    }

    /**
     * Parses a payload into its action/outbound summary. Invalid payloads
     * come back as [Summary.valid] = false with no detail — callers that
     * need the reason call [validate].
     */
    fun describe(text: String): Summary {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Summary(valid = false)
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            ?: return Summary(valid = false)
        val rules = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.filterIsInstance<JsonObject>()
            else -> return Summary(valid = false)
        }
        if (rules.isEmpty()) return Summary(valid = false)

        val actions = LinkedHashSet<String>()
        val outbounds = LinkedHashSet<String>()
        var hasMatchers = false
        for (rule in rules) {
            // Nested logical rules carry their own action inside `rules`;
            // the payload's effective behaviour is still whatever the outer
            // object (or, when absent, the kernel default) says.
            collectActions(rule, actions, outbounds)
            if (rule.keys.any { it !in actionKeys }) hasMatchers = true
        }
        return Summary(
            valid = true,
            ruleCount = rules.size,
            actions = actions.toList(),
            outbounds = outbounds.toList(),
            hasMatchers = hasMatchers,
        )
    }

    private fun collectActions(
        rule: JsonObject,
        actions: MutableSet<String>,
        outbounds: MutableSet<String>,
    ) {
        val action = (rule["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: if ((rule["outbound"] as? JsonPrimitive)?.content?.isNotBlank() == true) "route" else ""
        if (action.isNotBlank()) actions += action
        if (action == "route" || action.isEmpty()) {
            (rule["outbound"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { outbounds += it }
        }
        (rule["rules"] as? JsonArray)?.forEach { child ->
            (child as? JsonObject)?.let { collectActions(it, actions, outbounds) }
        }
    }

    /**
     * Applies the JSON payload's own action to a JSON-kind [RouteRule] so the
     * rest of the app (rule list, planner, compiler) sees the same behaviour
     * the kernel would run. The payload itself is never rewritten: only the
     * rule's mirror fields are updated, with the payload staying the source
     * of truth at compile time.
     */
    fun syncMirror(rule: RouteRule): RouteRule {
        val summary = describe(rule.json)
        if (!summary.valid) return rule
        val action = when {
            summary.actions.isEmpty() -> RouteRule.RuleActionRoute
            summary.actions.all { it == "reject" } -> RouteRule.RuleActionReject
            summary.actions.any { it == "reject" } -> RouteRule.RuleActionReject
            summary.actions.all { it == "resolve" } -> RouteRule.RuleActionResolve
            summary.actions.any { it == "resolve" } && summary.outbounds.isEmpty() -> RouteRule.RuleActionResolve
            else -> RouteRule.RuleActionRoute
        }
        val outbound = summary.outbounds.firstOrNull()
            ?: if (action == RouteRule.RuleActionRoute) ProxySelectorTag else rule.outbound
        val direct = summary.outbounds.firstOrNull() == DirectOutboundTag
        return rule.copy(
            action = action,
            outbound = if (action == RouteRule.RuleActionRoute) outbound else rule.outbound,
            // `direct` in the payload means the rule really goes direct; the
            // editor shows the same target instead of the proxy placeholder.
            ipFamily = rule.ipFamily,
        ).let { if (direct) it.copy(outbound = DirectOutboundTag) else it }
    }

    /** Human-facing one-line description of what the payload does. */
    fun summaryLabel(summary: Summary, outboundLabel: (String) -> String): String {
        if (!summary.valid) return "invalid JSON"
        val parts = buildList {
            add(if (summary.ruleCount == 1) "1 rule" else "${summary.ruleCount} rules")
            if (!summary.hasMatchers) add("no matchers")
            val action = summary.primaryAction.ifBlank { "route" }
            when (action) {
                "reject" -> add("reject")
                "route" -> add("route -> " + (summary.primaryOutbound.takeIf { it.isNotBlank() }?.let(outboundLabel) ?: "?"))
                else -> add(action)
            }
        }
        return parts.joinToString(" · ")
    }

    /** Direct access to the underlying JSON element for callers that need it. */
    fun parseElement(text: String): JsonElement? = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()
}
