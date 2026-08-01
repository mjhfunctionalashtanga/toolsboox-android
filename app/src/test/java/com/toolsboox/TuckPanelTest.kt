package com.toolsboox

import com.toolsboox.ot.TuckPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's sizing rule, pinned: a rail is always exactly ONE button wide plus its side air —
 * whatever the pill-scale dial does to the button — and the tucked strip is always slimmer than
 * any button the dial can produce, so tucked can never be mistaken for open.
 */
class TuckPanelTest {

    // ledger_toolbar_button is 40dp; these are that button at the pill dial's steps on a
    // 2.0-density screen (Compact ~0.8, Standard 1.0, Expanded ~1.3).
    private val dialSteps = listOf(64, 80, 104)
    private val density = 2.0f

    @Test
    fun `rail width is one button plus air at every dial step`() {
        val air = (6 * density).toInt()
        for (side in dialSteps) {
            assertEquals(side + air, TuckPanel.railWidth(side, air))
        }
    }

    @Test
    fun `the tucked strip is slimmer than any button the dial produces`() {
        val stripPx = (TuckPanel.STRIP_DP * density).toInt()
        for (side in dialSteps) {
            assertTrue(
                "strip ($stripPx px) must read as a strip beside a $side px button",
                stripPx < side / 2
            )
        }
    }
}
