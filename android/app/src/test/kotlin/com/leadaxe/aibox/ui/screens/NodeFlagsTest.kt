package com.leadaxe.aibox.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeFlagsTest {

    @Test
    fun `extracts leading regional-indicator flag`() {
        assertEquals("🇭🇰", NodeFlags.flagOf("🇭🇰 香港 01"))
        assertEquals("🇺🇸", NodeFlags.flagOf("🇺🇸 US Premium"))
        assertEquals("🇯🇵", NodeFlags.flagOf("🇯🇵Tokyo"))
    }

    @Test
    fun `no flag prefix folds into empty bucket`() {
        assertEquals("", NodeFlags.flagOf("香港 01"))
        assertEquals("", NodeFlags.flagOf("US server"))
        assertEquals("", NodeFlags.flagOf(""))
        assertEquals("", NodeFlags.flagOf("🇭"))  // lone regional indicator
    }

    @Test
    fun `flag-less bucket renders as globe fallback`() {
        assertEquals("🌐", NodeFlags.labelOf("", "🌐"))
        assertEquals("🇭🇰", NodeFlags.labelOf("🇭🇰", "🌐"))
    }
}
