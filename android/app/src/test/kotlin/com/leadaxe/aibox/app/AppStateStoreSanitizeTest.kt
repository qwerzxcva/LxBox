package com.leadaxe.aibox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reference-consistency pass. The DNS-group case
 * is the one that bit: group members are DNS server tags — a different
 * namespace from outbound tags — and the first version of the sanitiser
 * checked them against the outbound set, deleting every group on save.
 */
class AppStateStoreSanitizeTest {

    private fun sanitize(state: AppState): AppState = sanitizeAppStateReferences(state)

    private fun server(id: String, type: String = "https", detour: String = "") = DnsServerState(
        id = id,
        name = id,
        type = type,
        address = if (type == "local") "" else "1.1.1.1",
        detour = detour,
    )

    @Test
    fun `dns group members survive the sanitiser`() {
        // Both members are real servers (the editor only offers real ones);
        // the sanitiser must not mistake dns-* tags for outbound tags.
        val memberA = server("a")
        val memberB = server("b", type = "tls", detour = ProxySelectorTag)
        val group = server("g", type = "group").copy(
            groupServers = listOf(memberA.tag, memberB.tag),
        )
        val state = AppState(
            outbounds = listOf(
                OutboundProfile(id = "n1", name = "n1", type = "vless", config = "{}"),
            ),
            dnsServers = listOf(memberA, memberB, group),
        )
        val cleaned = sanitize(state)
        val cleanedGroup = cleaned.dnsServers.first { it.id == "g" }
        assertEquals(listOf("dns-a", "dns-b"), cleanedGroup.groupServers)
    }

    @Test
    fun `dns group member pointing at a deleted server is dropped`() {
        val member = server("a")
        val group = server("g", type = "group").copy(
            groupServers = listOf(member.tag, "dns-gone"),
        )
        val state = AppState(dnsServers = listOf(member, group))
        val cleaned = sanitize(state)
        val cleanedGroup = cleaned.dnsServers.first { it.id == "g" }
        assertEquals(listOf("dns-a"), cleanedGroup.groupServers)
    }

    @Test
    fun `dns group that lost every member is removed`() {
        val group = server("g", type = "group").copy(groupServers = listOf("dns-gone"))
        val state = AppState(dnsServers = listOf(group))
        val cleaned = sanitize(state)
        assertFalse(cleaned.dnsServers.any { it.id == "g" })
    }

    @Test
    fun `dangling dns detour falls back to the proxy selector`() {
        val dangling = server("a", detour = "node-deleted")
        val state = AppState(
            outbounds = listOf(
                OutboundProfile(id = "n1", name = "n1", type = "vless", config = "{}"),
            ),
            dnsServers = listOf(dangling),
        )
        val cleaned = sanitize(state)
        assertEquals(ProxySelectorTag, cleaned.dnsServers.first().detour)
    }

    @Test
    fun `disabled dns servers still count as live for group membership`() {
        // disabled ≠ deleted: unchecking a member server must not silently
        // uncheck it from the group.
        val member = server("a").copy(enabled = false)
        val group = server("g", type = "group").copy(groupServers = listOf(member.tag))
        val state = AppState(dnsServers = listOf(member, group))
        val cleaned = sanitize(state)
        assertEquals(listOf("dns-a"), cleaned.dnsServers.first { it.id == "g" }.groupServers)
    }

    @Test
    fun `empty outbound group is dropped but a group over real nodes stays`() {
        val node = OutboundProfile(id = "n1", name = "n1", type = "vless", config = "{}")
        val empty = OutboundGroup(id = "g1", name = "empty")
        val full = OutboundGroup(id = "g2", name = "full", members = listOf(node.tag))
        val cleaned = sanitize(AppState(outbounds = listOf(node), outboundGroups = listOf(empty, full)))
        assertFalse(cleaned.outboundGroups.any { it.id == "g1" })
        assertTrue(cleaned.outboundGroups.any { it.id == "g2" })
    }
}
