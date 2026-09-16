package com.leadaxe.aibox.engine.singbox

import com.leadaxe.aibox.app.RouteRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the routing flow the core executes after planning: package first,
 * then keyword → suffix → exact domain, then addresses; and the dedupe
 * priority keyword > suffix > exact domain, broader CIDR > narrower,
 * earlier rule > later rule — with the safety valves (never widen a rule,
 * never prune logical/inverted rules).
 */
class RulePlannerTest {

    private fun rule(
        id: String = "r1",
        keywords: List<String> = emptyList(),
        suffixes: List<String> = emptyList(),
        domains: List<String> = emptyList(),
        cidrs: List<String> = emptyList(),
        packages: List<String> = emptyList(),
        ruleSets: List<String> = emptyList(),
        ports: List<String> = emptyList(),
        action: String = RouteRule.RuleActionRoute,
        outbound: String = "proxy",
        type: String = RouteRule.RuleTypeDefault,
        invert: Boolean = false,
        subRules: List<RouteRule> = emptyList(),
    ) = RouteRule(
        id = id, kind = RouteRule.KindInline, type = type, invert = invert,
        domainKeyword = keywords, domainSuffix = suffixes, domain = domains,
        ipCidr = cidrs, packageName = packages, ruleSet = ruleSets, port = ports,
        action = action, outbound = outbound, rules = subRules,
    )

    // ------------------------------------------------------------- ordering

    @Test fun `app rule sorts before domain rules`() {
        val app = rule(id = "app", packages = listOf("com.foo"), outbound = "app-exit")
        val dom = rule(id = "dom", suffixes = listOf("a.com"), outbound = "dom-exit")
        val result = RulePlanner.prepare(listOf(dom, app))
        assertEquals(listOf("app", "dom"), result.map { it.id })
    }

    @Test fun `keyword before suffix before exact domain`() {
        val exact = rule(id = "exact", domains = listOf("x.com"), outbound = "e")
        val suffix = rule(id = "suffix", suffixes = listOf("y.com"), outbound = "s")
        val keyword = rule(id = "kw", keywords = listOf("z"), outbound = "k")
        val result = RulePlanner.prepare(listOf(exact, suffix, keyword))
        assertEquals(listOf("kw", "suffix", "exact"), result.map { it.id })
    }

    @Test fun `cidr rules rank with the keyword tier`() {
        // User spec: keyword = ip_cidr = domain_regex share one strength
        // tier, above suffix and exact. Same-tier keeps the user's order.
        val ip = rule(id = "ip", cidrs = listOf("10.0.0.0/8"), outbound = "d")
        val dom = rule(id = "dom", suffixes = listOf("a.com"), outbound = "p")
        val result = RulePlanner.prepare(listOf(ip, dom))
        assertEquals(listOf("ip", "dom"), result.map { it.id })
    }

    @Test fun `order inside a phase follows the user's arrangement`() {
        val a = rule(id = "a", suffixes = listOf("a.com"), outbound = "1")
        val b = rule(id = "b", suffixes = listOf("b.com"), outbound = "2")
        val c = rule(id = "c", suffixes = listOf("c.com"), outbound = "3")
        val result = RulePlanner.prepare(listOf(a, b, c))
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    // --------------------------------------------------- within a single rule

    @Test fun `exact domain covered by keyword is dropped`() {
        val (kw, sfx, dom) = RulePlanner.dedupeDomains(
            keywords = listOf("baidu"),
            suffixes = emptyList(),
            domains = listOf("baidu.com", "tieba.baidu.com", "example.com"),
        )
        assertEquals(listOf("baidu"), kw)
        assertTrue(sfx.isEmpty())
        assertEquals(listOf("example.com"), dom)
    }

    @Test fun `exact domain covered by suffix is dropped, lookalike survives`() {
        val (_, sfx, dom) = RulePlanner.dedupeDomains(
            keywords = emptyList(),
            suffixes = listOf("example.com"),
            domains = listOf("example.com", "www.example.com", "notexample.com"),
        )
        assertEquals(listOf("example.com"), sfx)
        assertEquals(listOf("notexample.com"), dom)
    }

    @Test fun `suffix covered by keyword is dropped`() {
        val (_, sfx, _) = RulePlanner.dedupeDomains(
            keywords = listOf("baidu"),
            suffixes = listOf("baidu.com", "cdn.example.com"),
            domains = emptyList(),
        )
        assertEquals(listOf("cdn.example.com"), sfx)
    }

    @Test fun `narrow suffix under a broader sibling is dropped`() {
        val (_, sfx, _) = RulePlanner.dedupeDomains(
            keywords = emptyList(),
            suffixes = listOf("example.com", "a.example.com"),
            domains = emptyList(),
        )
        assertEquals(listOf("example.com"), sfx)
    }

    @Test fun `casing and leading dots are normalised`() {
        val (_, sfx, dom) = RulePlanner.dedupeDomains(
            keywords = listOf("Baidu"),
            suffixes = listOf(".BAIDU.com"),
            domains = listOf("TieBa.Baidu.Com"),
        )
        assertTrue(sfx.isEmpty())
        assertTrue(dom.isEmpty())
    }

    // --------------------------------------------------------------- ip dedupe

    @Test fun `narrower cidr inside earlier broader one is dropped`() {
        assertEquals(
            listOf("10.0.0.0/8", "192.168.0.0/16"),
            RulePlanner.dedupeIpCidrs(listOf("10.0.0.0/8", "10.1.2.0/24", "192.168.0.0/16")),
        )
    }

    @Test fun `sibling prefixes both survive`() {
        assertEquals(
            listOf("10.0.0.0/9", "10.128.0.0/9"),
            RulePlanner.dedupeIpCidrs(listOf("10.0.0.0/9", "10.128.0.0/9")),
        )
    }

    @Test fun `ipv6 and ipv4 do not subsume each other`() {
        assertEquals(
            listOf("2001:db8::/32", "10.0.0.0/8"),
            RulePlanner.dedupeIpCidrs(listOf("2001:db8::/32", "2001:db8:1::/48", "10.0.0.0/8")),
        )
    }

    // ----------------------------------------------------- across-rule dedupe

    @Test fun `later duplicate names collapse into the earlier rule`() {
        val first = rule(id = "a", suffixes = listOf("google.com"), outbound = "proxy")
        val second = rule(
            id = "b", suffixes = listOf("google.com"),
            domains = listOf("maps.google.com"), outbound = "proxy",
        )
        val result = RulePlanner.prepare(listOf(first, second))
        // "b" lost everything -> fully covered by "a" -> dropped.
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test fun `keyword earlier covers later suffix and exact domain`() {
        val first = rule(id = "a", keywords = listOf("baidu"), outbound = "proxy")
        val second = rule(
            id = "b", suffixes = listOf("baidu.com"),
            domains = listOf("tieba.baidu.com"), outbound = "proxy",
        )
        assertEquals(listOf("a"), RulePlanner.prepare(listOf(first, second)).map { it.id })
    }

    @Test fun `identical exact domains cross-prune`() {
        val first = rule(id = "a", domains = listOf("example.com"), outbound = "proxy")
        val second = rule(id = "b", domains = listOf("example.com", "other.org"), outbound = "proxy")
        val result = RulePlanner.prepare(listOf(first, second))
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(listOf("other.org"), result.first { it.id == "b" }.domain)
    }

    @Test fun `broader cidr earlier covers later narrower one`() {
        val first = rule(id = "a", cidrs = listOf("10.0.0.0/8"), outbound = "proxy")
        val second = rule(id = "b", cidrs = listOf("10.1.0.0/16"), outbound = "proxy")
        assertEquals(listOf("a"), RulePlanner.prepare(listOf(first, second)).map { it.id })
    }

    @Test fun `different targets never cross-prune`() {
        val first = rule(id = "a", suffixes = listOf("google.com"), outbound = "proxy")
        val second = rule(id = "b", suffixes = listOf("google.com"), outbound = "direct")
        val result = RulePlanner.prepare(listOf(first, second))
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(listOf("google.com"), result.first { it.id == "b" }.domainSuffix)
    }

    @Test fun `a constrained rule cannot vouch for a later rule`() {
        // First rule matches only a *subset* (package AND suffix); it must not
        // let the later pure-domain rule lose its suffix.
        val first = rule(
            id = "a", suffixes = listOf("google.com"),
            packages = listOf("com.foo"), outbound = "proxy",
        )
        val second = rule(id = "b", suffixes = listOf("google.com"), outbound = "proxy")
        val result = RulePlanner.prepare(listOf(first, second))
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(listOf("google.com"), result.first { it.id == "b" }.domainSuffix)
    }

    // ------------------------------------------------------------ safety valves

    @Test fun `logical rules are passed through and never pruned`() {
        val first = rule(id = "a", suffixes = listOf("google.com"), outbound = "proxy")
        val logical = rule(
            id = "logic", type = RouteRule.RuleTypeLogical,
            subRules = listOf(rule(id = "sub", suffixes = listOf("google.com"))),
            outbound = "proxy",
        )
        val result = RulePlanner.prepare(listOf(first, logical))
        assertEquals(listOf("a", "logic"), result.map { it.id })
        assertTrue(result.first { it.id == "logic" }.rules.isNotEmpty())
    }

    @Test fun `inverted rules are never pruned`() {
        val first = rule(id = "a", suffixes = listOf("google.com"), outbound = "proxy")
        val inverted = rule(id = "b", suffixes = listOf("google.com"), invert = true, outbound = "proxy")
        val result = RulePlanner.prepare(listOf(first, inverted))
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(listOf("google.com"), result.first { it.id == "b" }.domainSuffix)
    }

    @Test fun `a rule with other AND conditions is not emptied`() {
        // port + domain: dropping the domain would turn the rule into
        // "any traffic to these ports", which is wider than intended.
        val first = rule(id = "a", suffixes = listOf("game.com"), outbound = "proxy")
        val second = rule(
            id = "b", suffixes = listOf("game.com"), ports = listOf("443"),
            outbound = "proxy",
        )
        val result = RulePlanner.prepare(listOf(first, second))
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals(listOf("game.com"), result.first { it.id == "b" }.domainSuffix)
    }

    @Test fun `empty rule lists survive planning`() {
        assertTrue(RulePlanner.prepare(emptyList()).isEmpty())
    }

    @Test fun `disabled rules are not emitted`() {
        val disabled = rule(id = "off", suffixes = listOf("a.com")).copy(enabled = false)
        val live = rule(id = "on", suffixes = listOf("b.com"))
        val result = RulePlanner.prepare(listOf(disabled, live))
        assertEquals(listOf("on"), result.map { it.id })
    }
}
