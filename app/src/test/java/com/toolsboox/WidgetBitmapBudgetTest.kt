package com.toolsboox

import com.toolsboox.plugin.calendar.widget.WidgetBitmapBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RemoteViews bitmap ceiling, pinned as arithmetic: the budget is one screen's pixel AREA
 * (a third under the platform's enforced 1.5×), a bitmap already inside it ships untouched at
 * its native size (the whole point of retiring the fixed 768px cap — no more shrink-then-let-
 * the-launcher-stretch round trip), and one that overshoots comes back exactly to budget by
 * scaling both sides, never past it.
 */
class WidgetBitmapBudgetTest {

    // A density-3 phone screen — the launchers where the old 768 cap visibly softened widgets.
    private val screenW = 1080
    private val screenH = 2340

    @Test
    fun `a widget-sized bitmap ships at native size — no cap, no upscale`() {
        // A 4-cell widget at density 3: ~1080 × 1200. Area ≈ 1.3M < the 2.5M budget. The old
        // fixed cap would have crushed this to 768 on the long side; the budget must not.
        assertEquals(1f, WidgetBitmapBudget.capScale(screenW, screenH, 1080, 1200), 0f)
        // Exactly at budget is still inside it.
        assertEquals(1f, WidgetBitmapBudget.capScale(screenW, screenH, screenW, screenH), 0f)
    }

    @Test
    fun `an overshooting bitmap is scaled by area back inside the budget`() {
        val rawW = 3000
        val rawH = 4000   // 12M px, far over the 2.5M budget
        val s = WidgetBitmapBudget.capScale(screenW, screenH, rawW, rawH)
        assertTrue("must shrink", s < 1f)
        val outArea = (rawW * s).toLong() * (rawH * s).toLong()
        val budget = screenW.toLong() * screenH.toLong()
        assertTrue("scaled area ($outArea) must be within the budget ($budget)", outArea <= budget)
        // …and not over-shrunk: within a couple of percent of the whole budget, since sqrt
        // scales area linearly — the seatbelt must not double as a quality tax.
        assertTrue("scaled area ($outArea) must USE the budget", outArea >= (budget * 95) / 100)
    }

    @Test
    fun `degenerate inputs never divide by zero`() {
        assertEquals(1f, WidgetBitmapBudget.capScale(screenW, screenH, 0, 100), 0f)
        assertEquals(1f, WidgetBitmapBudget.capScale(screenW, screenH, 100, 0), 0f)
        assertEquals(1f, WidgetBitmapBudget.capScale(0, 0, 100, 100), 0f)
    }
}
