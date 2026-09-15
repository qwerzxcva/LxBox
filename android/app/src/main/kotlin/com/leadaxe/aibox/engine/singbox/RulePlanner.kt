package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.RouteRule

/**
 * Compile-time route planner. Prepares the rule table the core will execute
 * so it reflects the documented routing flow instead of the editor order.
 *
 * ## Kernel semantics this encodes (verified against the vendored source)
 *  - Rules are evaluated top-to-bottom; **first hit wins**.
 *  - Within one rule, ordinary items (package, port, network, protocol, …)
 *    are **AND'ed** (`abstractDefaultRule.matchInner`).
 *  - The *destination address* family — exact domain, suffix, keyword,
 *    regex, `ip_cidr` — forms **one OR group** (`matchAnyItem` over
 *    `destinationAddressItems` / destination CIDR items). Matching any one
 *    of them satisfies the group; emptying the group removes the
 *    constraint entirely and would widen the rule.
 *
 * ## What the planner does
 *  1. **Phase ordering**, preserving the user's relative order inside each
 *     phase (stable sort):
 *       Package → DomainKeyword → DomainSuffix → DomainExact → Other
 *     An app-level match short-circuits first; domain names are decided
 *     before addresses; the keyword → suffix → exact order is the
 *     precedence the user asked for, and it is also the dedupe priority.
 *  2. **Redundancy elimination** (plain, non-inverted inline rules only):
 *       - within a rule: keyword beats suffix beats exact domain; a broader
 *         CIDR beats a narrower one;
 *       - across rules: only a rule whose constraints are *just* the
 *         destination address can vouch for a later rule (a rule with an
 *         extra AND condition covers a smaller set, so it cannot make a
 *         later entry dead). Coverage requires the same action target.
 *     A rule whose destination group would be emptied while other AND
 *     conditions remain is left untouched — pruning there would widen it.
 *     A rule that loses every constraint is dropped: the earlier rule
 *     already routes all of its traffic.
 *
 * Logical, inverted and JSON rules pass through verbatim (their semantics
 * cannot be approximated by list pruning) and never vouch for other rules.
 */
internal object RulePlanner {

    /** Execution buckets, in evaluation order. */
    enum class Phase(val rank: Int) {
        Package(0),
        DomainKeyword(1),
        DomainSuffix(2),
        DomainExact(3),
        Other(4),
    }

    /** Action identity: two rules are interchangeable iff these agree. */
    data class ActionKey(val action: String, val outbound: String)

    // -------------------------------------------------------------- classify

    /** Matcher classes across the whole rule tree. */
    private data class Flags(
        var package_: Boolean = false,
        var keyword: Boolean = false,
        var suffix: Boolean = false,   // suffix, regex or rule_set family
        var exact: Boolean = false,
    )

    private fun collect(rule: RouteRule, into: Flags) {
        into.package_ = into.package_ || rule.packageName.isNotEmpty()
        into.keyword = into.keyword || rule.domainKeyword.isNotEmpty()
        into.suffix = into.suffix || rule.domainSuffix.isNotEmpty() ||
            rule.domainRegex.isNotEmpty() || rule.ruleSet.isNotEmpty()
        into.exact = into.exact || rule.domain.isNotEmpty()
        rule.rules.forEach { collect(it, into) }
    }

    fun classify(rule: RouteRule): Phase {
        val flags = Flags()
        collect(rule, flags)
        return when {
            flags.package_ -> Phase.Package
            flags.keyword -> Phase.DomainKeyword
            flags.suffix -> Phase.DomainSuffix
            flags.exact -> Phase.DomainExact
            else -> Phase.Other
        }
    }

    /** Plain rules have OR semantics inside the destination group; prunable. */
    fun prunable(rule: RouteRule): Boolean =
        rule.kind == RouteRule.KindInline && !rule.isLogical && !rule.invert &&
            rule.combine != RouteRule.CombineOr

    /**
     * True when the rule constrains nothing but the destination address — the
     * only shape that can vouch for a later rule's entries. Any extra AND
     * condition (package, port, network, protocol, SSID, source-side or
     * `ip_is_private`) shrinks the matched set and disqualifies it.
     */
    private fun destinationOnly(rule: RouteRule): Boolean =
        rule.packageName.isEmpty() && rule.port.isEmpty() && rule.sourcePort.isEmpty() &&
            rule.portRange.isEmpty() && rule.sourcePortRange.isEmpty() &&
            rule.network.isEmpty() && rule.protocol.isEmpty() &&
            rule.wifiSsid.isEmpty() && rule.wifiBssid.isEmpty() &&
            rule.sourceIpCidr.isEmpty() && !rule.sourceIpIsPrivate && !rule.ipIsPrivate

    private fun hasOtherConstraint(rule: RouteRule): Boolean = !destinationOnly(rule)

    private fun hasDestinationAddress(rule: RouteRule): Boolean =
        rule.domain.isNotEmpty() || rule.domainSuffix.isNotEmpty() ||
            rule.domainKeyword.isNotEmpty() || rule.domainRegex.isNotEmpty() ||
            rule.ipCidr.isNotEmpty() || rule.ruleSet.isNotEmpty()

    // ---------------------------------------------------------- domain dedupe

    fun normDomain(s: String) = s.trim().lowercase().removePrefix(".")

    /**
     * keyword > suffix > exact domain, within one rule. An exact domain is
     * covered when a keyword is a substring of it, or a suffix equals it /
     * sits above it on a label boundary; a suffix is covered when a keyword
     * is a substring of it or a broader suffix of the same rule contains it.
     * Exact domains never prune each other here.
     */
    fun dedupeDomains(
        keywords: List<String>,
        suffixes: List<String>,
        domains: List<String>,
    ): Triple<List<String>, List<String>, List<String>> {
        val kws = keywords.map(::normDomain).filter { it.isNotEmpty() }.distinct()
        val sfxs = suffixes.map(::normDomain).filter { it.isNotEmpty() }.distinct()
        val doms = domains.map(::normDomain).filter { it.isNotEmpty() }.distinct()

        fun hitByKeyword(v: String) = kws.any { it in v }
        fun hitBySuffix(v: String, s: String) = v == s || v.endsWith(".$s")

        val keptSuffixes = sfxs
            .filterNot { hitByKeyword(it) }
            .filter { s -> sfxs.none { o -> o != s && o.length < s.length && hitBySuffix(s, o) } }
        val keptDomains = doms.filter { d ->
            !hitByKeyword(d) && keptSuffixes.none { s -> hitBySuffix(d, s) }
        }
        return Triple(kws, keptSuffixes, keptDomains)
    }

    // -------------------------------------------------------------- ip dedupe

    private data class Cidr(val bytes: ByteArray, val prefix: Int) {
        override fun equals(other: Any?) = other is Cidr &&
            prefix == other.prefix && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * prefix + bytes.contentHashCode()
    }

    private fun parseCidr(s: String): Cidr? = runCatching {
        val text = s.trim()
        val slash = text.lastIndexOf('/')
        if (slash <= 0) return@runCatching null
        val addrText = text.substring(0, slash).trim('[', ']')
        val prefix = text.substring(slash + 1).toIntOrNull() ?: return@runCatching null
        val bytes = if (':' in addrText) {
            if (prefix !in 0..128) return@runCatching null
            java.net.InetAddress.getByName(addrText).address
        } else {
            if (prefix !in 0..32) return@runCatching null
            val parts = addrText.split('.')
            if (parts.size != 4) return@runCatching null
            ByteArray(4) { i -> (parts[i].toIntOrNull() ?: return@runCatching null).toByte() }
        }
        Cidr(bytes, prefix)
    }.getOrNull()

    private fun contains(outer: Cidr, inner: Cidr): Boolean {
        if (outer.bytes.size != inner.bytes.size) return false
        if (outer.prefix > inner.prefix) return false
        val fullBytes = outer.prefix / 8
        for (i in 0 until fullBytes) {
            if (outer.bytes[i] != inner.bytes[i]) return false
        }
        val rem = outer.prefix % 8
        if (rem != 0) {
            val mask = (0xFF shl (8 - rem)) and 0xFF
            if ((outer.bytes[fullBytes].toInt() and mask) != (inner.bytes[fullBytes].toInt() and mask)) {
                return false
            }
        }
        return true
    }

    /** Broader CIDRs kept earlier subsume narrower ones (first entry wins). */
    fun dedupeIpCidrs(cidrs: List<String>): List<String> {
        val kept = ArrayList<Pair<String, Cidr?>>(cidrs.size)
        for (raw in cidrs) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val parsed = parseCidr(text)
            if (parsed == null) {
                if (kept.none { it.first == text }) kept += text to null
                continue
            }
            if (kept.any { it.second != null && contains(it.second as Cidr, parsed) }) continue
            if (kept.none { it.first == text }) kept += text to parsed
        }
        return kept.map { it.first }.distinct()
    }

    // ------------------------------------------------------------- the table

    /** Mutable coverage sets for one action target. */
    private class Seen {
        val keywords = LinkedHashSet<String>()
        val suffixes = LinkedHashSet<String>()
        val domains = LinkedHashSet<String>()
        val cidrTexts = LinkedHashSet<String>()
        val cidrs = ArrayList<Cidr>()
        val tags = LinkedHashSet<String>()
    }

    private fun actionOf(rule: RouteRule) = ActionKey(rule.action, rule.outbound)

    /**
     * Prepares the execution table: deduped, phase-ordered, stable inside
     * each phase. Pure function — the editor state is never touched.
     */
    fun prepare(rules: List<RouteRule>): List<RouteRule> {
        val prepared = ArrayList<Pair<RouteRule, Phase>>(rules.size)
        val seenByAction = HashMap<ActionKey, Seen>()

        for (rule in rules.filter { it.enabled }) {
            val phase = classify(rule)
            if (!prunable(rule)) {
                prepared += rule to phase
                continue
            }
            val seen = seenByAction.getOrPut(actionOf(rule)) { Seen() }
            val survivor = prune(rule, seen)
            if (survivor == null) continue // fully redundant: drop
            if (destinationOnly(survivor)) register(survivor, seen)
            prepared += survivor to phase
        }

        return prepared
            .sortedWith(compareBy({ it.second.rank })) // stable inside a phase
            .map { it.first }
    }

    /**
     * Returns the rule with redundant entries removed, the original instance
     * when nothing changed, or null when the rule became fully redundant.
     */
    private fun prune(rule: RouteRule, seen: Seen): RouteRule? {
        // 1) within-rule priority
        val (ownKw, ownSfx, ownDom) = dedupeDomains(rule.domainKeyword, rule.domainSuffix, rule.domain)
        val ownIp = dedupeIpCidrs(rule.ipCidr)

        // 2) what earlier same-target destination-only rules already cover
        fun coveredBySeen(v: String) =
            seen.keywords.any { it in v } ||
                seen.suffixes.any { s -> v == s || v.endsWith(".$s") }

        val keptKw = ownKw.filterNot { kw -> seen.keywords.any { it in kw } }
        val keptSfx = ownSfx.filterNot { coveredBySeen(it) }
        val keptDom = ownDom.filterNot { coveredBySeen(it) || it in seen.domains }
        val keptIp = ownIp.filterNot { text ->
            val parsed = parseCidr(text)
            (parsed != null && seen.cidrs.any { contains(it, parsed) }) || text in seen.cidrTexts
        }
        val keptTags = rule.ruleSet.filterNot { it.trim() in seen.tags }

        // 3) guard: never empty the destination group while other AND
        //    conditions remain — the rule would start matching more traffic.
        val destinationEmptied = keptKw.isEmpty() && keptSfx.isEmpty() && keptDom.isEmpty() &&
            rule.domainRegex.isEmpty() && keptIp.isEmpty() && keptTags.isEmpty()
        if (destinationEmptied && hasOtherConstraint(rule) && hasDestinationAddress(rule)) {
            return rule // leave it alone rather than widening it
        }
        if (destinationEmptied && !hasOtherConstraint(rule)) {
            // nothing left to match anywhere in the rule
            if (hasDestinationAddress(rule)) return null
        }

        val changed = keptKw.size != rule.domainKeyword.size ||
            keptSfx.size != rule.domainSuffix.size ||
            keptDom.size != rule.domain.size ||
            keptIp.size != rule.ipCidr.size ||
            keptTags.size != rule.ruleSet.size
        if (!changed) return rule

        return rule.copy(
            domainKeyword = keptKw,
            domainSuffix = keptSfx,
            domain = keptDom,
            ipCidr = keptIp,
            ruleSet = keptTags,
        )
    }

    private fun register(rule: RouteRule, seen: Seen) {
        rule.domainKeyword.mapTo(seen.keywords) { normDomain(it) }
        rule.domainSuffix.mapTo(seen.suffixes) { normDomain(it) }
        rule.domain.mapTo(seen.domains) { normDomain(it) }
        rule.ipCidr.forEach { raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEach
            seen.cidrTexts += text
            parseCidr(text)?.let { seen.cidrs += it }
        }
        rule.ruleSet.mapTo(seen.tags) { it.trim() }
    }
}
