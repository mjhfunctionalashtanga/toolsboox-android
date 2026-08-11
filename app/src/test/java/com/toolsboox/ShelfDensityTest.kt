package com.toolsboox

import com.toolsboox.plugin.reader.ui.ShelfDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bookshelf spacing ladder — the shape of the rungs, not the prefs plumbing.
 *
 * Each rung bundles every number the grid draws with (columns, cover height, padding, title
 * size) precisely so they cannot drift apart; what a test can hold is that the bundle stays
 * COHERENT — a rung with more columns must have smaller covers, or "Compact" and "Large" stop
 * meaning what they say. COZY is pinned to the shelf's original hard-coded numbers because an
 * untouched setting must change nothing for anyone who never opens the menu.
 */
class ShelfDensityTest {

    /** More columns ⇒ smaller covers, smaller pad, smaller title — strictly, at every step. */
    @Test
    fun `the ladder is monotonic`() {
        val rungs = ShelfDensity.entries
        for (i in 1 until rungs.size) {
            val tighter = rungs[i - 1]
            val looser = rungs[i]
            assertTrue("columns must fall down the ladder", tighter.columns > looser.columns)
            assertTrue("covers must grow down the ladder", tighter.coverDp < looser.coverDp)
            assertTrue("padding must grow down the ladder", tighter.padDp < looser.padDp)
            assertTrue("titles must grow down the ladder", tighter.titleSp < looser.titleSp)
        }
    }

    /** COZY is the historical shelf, byte for byte: 4 across, 150dp covers, 6dp pad, 12sp. */
    @Test
    fun `cozy is exactly the shelf as it always drew`() {
        val cozy = ShelfDensity.COZY
        assertEquals(4, cozy.columns)
        assertEquals(150, cozy.coverDp)
        assertEquals(6, cozy.padDp)
        assertEquals(12f, cozy.titleSp, 0.001f)
    }

    /** Prefs store the key string, so keys must be unique and stable-looking. */
    @Test
    fun `rung keys are unique`() {
        val keys = ShelfDensity.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() && it == it.lowercase() })
    }
}
