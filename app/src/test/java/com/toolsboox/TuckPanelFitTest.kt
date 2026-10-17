package com.toolsboox

import com.toolsboox.ot.TuckPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's column fit, pinned. Michael 2026-10-10: "scrollable if there are more buttons" —
 * every seat stays in the column with its caption; the cells give up size, then the column
 * scrolls. Nothing folds into More for height and no caption hides for it (this overrides plan v6
 * §2.3's fold-and-drop-captions ladder). The fit is computed from the whole seat list before the
 * first layout (plan v6 §2.1).
 */
class TuckPanelFitTest {

    private val density = 2.0f
    private fun px(dp: Int) = (dp * density).toInt()
    private val base = px(40)   // ledger_toolbar_button at the Standard dial

    /** The old buttonSide() for the LAST button, which also set the rail's width. */
    private fun oldSide(availPx: Int, count: Int): Int {
        val avail = availPx - px(16) - count * px(2)
        return minOf(base, avail / count).coerceAtLeast(px(36))
    }

    /** What [n] seats of [f]'s cells take in the column, gap included. */
    private fun used(n: Int, f: TuckPanel.Fit) = n * (f.cellPx + TuckPanel.cellMarginsPx(density)) + px(TuckPanel.CLUSTER_GAP_DP)

    @Test
    fun `portrait fits bare at full size and nothing scrolls`() {
        for (h in listOf(732, 760)) {   // B6, Palma
            val f = TuckPanel.fitColumn(px(h), density, base, 14, labelled = false)
            assertEquals(base, f.cellPx)
            assertFalse(f.scrolls)
        }
    }

    @Test
    fun `a short bare column shrinks to the finger floor then scrolls`() {
        // A landscape Palma (412dp): fourteen 36dp cells don't fit, so the column scrolls.
        val f = TuckPanel.fitColumn(px(412), density, base, 14, labelled = false)
        assertEquals(px(TuckPanel.MIN_CELL_DP), f.cellPx)
        assertTrue(f.scrolls)
        for (h in 200..1000 step 4) for (n in 1..24) {
            val g = TuckPanel.fitColumn(px(h), density, base, n, labelled = false)
            assertTrue("finger-sized", g.cellPx >= px(TuckPanel.MIN_CELL_DP))
            assertTrue("never wider than the base", g.cellPx <= base)
            assertEquals("h=$h n=$n", used(n, g) > px(h), g.scrolls)
            // It scrolls only once the cells are as small as a finger allows.
            if (g.scrolls) assertEquals(px(TuckPanel.MIN_CELL_DP), g.cellPx)
        }
    }

    @Test
    fun `captions never hide for height`() {
        for (h in 160..1000 step 2) for (n in 1..24) {
            val f = TuckPanel.fitColumn(px(h), density, base, n, labelled = true)
            assertTrue("h=$h n=$n", f.captionSp >= 9f)
            assertTrue("h=$h n=$n", f.cellPx == px(48) || f.cellPx == px(40))
            assertEquals("h=$h n=$n", used(n, f) > px(h), f.scrolls)
        }
    }

    @Test
    fun `labelled cells step 48 then 40 then scroll`() {
        // 14 seats on a 732dp column take 48dp labelled cells.
        val b6 = TuckPanel.fitColumn(px(732), density, base, 14, labelled = true)
        assertEquals(px(48), b6.cellPx)
        assertEquals(10f, b6.captionSp)
        assertFalse(b6.scrolls)
        // 17 seats: 40dp cells, 9sp captions, still no scroll.
        val seventeen = TuckPanel.fitColumn(px(732), density, base, 17, labelled = true)
        assertEquals(px(40), seventeen.cellPx)
        assertEquals(9f, seventeen.captionSp)
        assertFalse(seventeen.scrolls)
        // 19 seats: the same labelled 40dp cells, and the column scrolls (it used to fold two).
        val nineteen = TuckPanel.fitColumn(px(732), density, base, 19, labelled = true)
        assertEquals(px(40), nineteen.cellPx)
        assertEquals(9f, nineteen.captionSp)
        assertTrue(nineteen.scrolls)
    }

    @Test
    fun `the width side is the old rule's number`() {
        for (h in 300..1000 step 7) {
            for (n in 1..20) {
                assertEquals(oldSide(px(h), n), TuckPanel.widthSide(px(h), density, base, n))
            }
        }
        // A Compact dial's sub-36dp button still floors at 36dp, as before.
        assertEquals(px(36), TuckPanel.widthSide(px(732), density, px(32), 14))
    }

    @Test
    fun `the comfortable foot only trades 48dp cells for 40dp ones`() {
        for (h in 300..900 step 2) for (n in 1..20) for (labelled in listOf(false, true)) {
            val plain = TuckPanel.fitColumn(px(h), density, base, n, labelled)
            val comfy = TuckPanel.fitColumn(px(h), density, base, n, labelled,
                comfortPx = px(TuckPanel.PALM_COMFORT_DP))
            assertEquals("h=$h n=$n never scrolls a column", plain.scrolls, comfy.scrolls)
            assertEquals("h=$h n=$n", plain.captionSp > 0f, comfy.captionSp > 0f)
            assertTrue(comfy.cellPx <= plain.cellPx)
        }
    }

    @Test
    fun `captions come from the shared vocabulary and stay short`() {
        val cases = mapOf(
            "Quick tools" to "More", "Other side" to "Side", "Tuck away" to "Tuck",
            "Present" to "Today", "Section up" to "Up", "Up / previous" to "Up",
            "Down / notes" to "Down", "Lasso select" to "Lasso", "Read aloud" to "Listen",
            "Level: Week" to "Level", "Oldest first — tap for newest" to "Oldest",
            "Draw…" to "Draw", "All Picks" to "All Picks", "Hub" to "Hub",
            "Sleep timer" to "Sleep", "Origins filter" to "Origins", "Previous email" to "Previous",
            // The list surfaces' S3 words, already (review 2026-10-10).
            "Back up" to "Up", "By stage" to "Stages", "New task" to "New", "New note" to "New",
            "Shelf controls" to "More", "Screen controls" to "More", "Capture pick" to "Capture"
        )
        for ((name, caption) in cases) assertEquals(name, caption, TuckPanel.captionFor(name))
        val own = TuckPanel.Item(0, "fine calligraphy pen", caption = "Pen") {}
        assertEquals("Pen", TuckPanel.captionFor(own))
    }

    @Test
    fun `labels default on where captions have the room`() {
        assertTrue(TuckPanel.labelsDefault(572, 772))    // B6
        assertTrue(TuckPanel.labelsDefault(412, 824))    // Palma
        assertFalse(TuckPanel.labelsDefault(320, 640))   // a narrow phone
        assertFalse(TuckPanel.labelsDefault(400, 560))   // a short screen
    }
}
