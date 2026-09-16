package com.leadaxe.aibox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subscription panel-domain routing (routeBySuffix): materialising,
 * replacing and removing the managed suffix rule.
 */
class SubscriptionSuffixRuleTest {

    private fun sub(
        url: String = "https://panel.example.com/token",
        fetchVia: String = FetchViaDirect,
        route: Boolean = true,
        name: String = "My Panel",
    ) = Subscription(
        id = "s1",
        name = name,
        url = url,
        fetchVia = fetchVia,
        routeBySuffix = route,
    )

    @Test
    fun `materialises the panel host as a suffix rule`() {
        val rule = subscriptionSuffixRule(sub())!!
        assertEquals(listOf("panel.example.com"), rule.domainSuffix)
        // direct (and auto) fetch → the panel itself goes direct
        assertEquals(DirectOutboundTag, rule.outbound)
        assertEquals(subscriptionRuleId("s1"), rule.id)
    }

    @Test
    fun `proxy fetch mode routes the panel through the selector`() {
        val rule = subscriptionSuffixRule(sub(fetchVia = FetchViaProxy))!!
        assertEquals(ProxySelectorTag, rule.outbound)
    }

    @Test
    fun `toggling off removes the managed rule without touching others`() {
        val other = RouteRule(id = "own", name = "hand-made")
        val withRule = listOf(other) + listOf(subscriptionSuffixRule(sub())!!)
        val cleaned = withRule.withSubscriptionRule(sub(route = false))
        assertEquals(listOf(other), cleaned)
    }

    @Test
    fun `re-saving replaces the rule instead of duplicating it`() {
        val once = emptyList<RouteRule>().withSubscriptionRule(sub())
        val twice = once.withSubscriptionRule(sub(url = "https://moved.example.org/sub"))
        assertEquals(1, twice.size)
        assertEquals(listOf("moved.example.org"), twice.single().domainSuffix)
    }

    @Test
    fun `url without a host yields no rule`() {
        assertNull(subscriptionSuffixRule(sub(url = "not a url")))
        val rules = emptyList<RouteRule>().withSubscriptionRule(sub(url = "not a url"))
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `www prefix is stripped from the suffix`() {
        val rule = subscriptionSuffixRule(sub(url = "https://www.example.com/x"))!!
        assertEquals(listOf("example.com"), rule.domainSuffix)
    }

    @Test
    fun `disabled subscriptions still own their rule removal`() {
        val rules = emptyList<RouteRule>().withSubscriptionRule(sub())
            .withSubscriptionRule(sub(route = false))
        assertFalse(rules.any { it.presetId == subscriptionRuleId("s1") })
    }
}
