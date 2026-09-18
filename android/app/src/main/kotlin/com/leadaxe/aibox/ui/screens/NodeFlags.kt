package com.leadaxe.aibox.ui.screens

/**
 * Region-flag extraction for node names.
 *
 * Subscription panels prefix node names with a regional-indicator flag
 * emoji ("🇭🇰 香港 01", "🇺🇸 US · Premium"). Grouping by the flag (not the
 * free-text prefix) is what makes the per-rule node filter readable: one
 * row per country/region, regardless of how the panel spells the city.
 *
 * Regional-indicator symbols are U+1F1E6..U+1F1FF and always come in pairs
 * (two code points = one flag). A node without a flag prefix folds into the
 * empty-string bucket, rendered as a generic globe.
 */
object NodeFlags {

    /** The leading flag emoji, or "" when the name does not start with one. */
    fun flagOf(name: String): String {
        val cps = name.codePoints().toArray()
        if (cps.size < 2) return ""
        if (!isRegional(cps[0]) || !isRegional(cps[1])) return ""
        return String(cps, 0, 2)
    }

    /**
     * Display label for a flag bucket: the flag itself, or a globe for the
     * flag-less group. [fallback] names the flag-less group in text UIs.
     */
    fun labelOf(flag: String, fallback: String): String =
        if (flag.isNotEmpty()) flag else fallback

    private fun isRegional(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF
}
