package com.toolsboox

import com.toolsboox.ot.TuckPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one panel language on the list and reading surfaces (Michael 2026-10-11): ⛶ at the head in
 * place of ✕ Tuck, blocks packed from the top with fixed 12dp gaps, a saved tuck carried over as
 * full screen, and the redress rule — the same seats again move no pixel.
 */
class TuckPanelListModeTest {

    private val density = 1.875f   // the B6
    private fun px(dp: Int) = (dp * density).toInt()
    private val base = px(40)

    @Test
    fun `the old column keeps its one 16dp gap unless told`() {
        for (h in 300..1000 step 7) for (n in 1..24) for (labelled in listOf(true, false))
            assertEquals(
                TuckPanel.fitColumn(px(h), density, base, n, labelled),
                TuckPanel.fitColumn(px(h), density, base, n, labelled, gapsPx = px(TuckPanel.CLUSTER_GAP_DP)))
    }

    @Test
    fun `three 12dp block gaps are what the fit budgets`() {
        val gaps = 3 * px(TuckPanel.BLOCK_GAP_DP)
        for (h in 300..1000 step 3) for (n in 1..24) {
            val f = TuckPanel.fitColumn(px(h), density, base, n, labelled = true, gapsPx = gaps)
            val used = n * (f.cellPx + TuckPanel.cellMarginsPx(density)) + gaps
            assertEquals("h=$h n=$n", used > px(h), f.scrolls)
        }
        // The B6 Net: ⛶ + 14 seats in a ~732dp column beneath the system bar fits at 40dp cells
        // with captions, nothing scrolling — every seat in view.
        val net = TuckPanel.fitColumn(px(732), density, base, 15, labelled = true, gapsPx = gaps)
        assertFalse(net.scrolls)
        assertTrue(net.captionSp >= 9f)
    }

    @Test
    fun `Side is in view at every height - pinned under the scroll exactly when the column scrolls`() {
        // Review 2026-10-11, F1 (Michael's field report: "Side changed on tuck not available"): on
        // the B6 in landscape (~548-572dp of column) the Net's ⛶ + 15 seats outrun the column, and
        // Side — the last seat — fell off the end. The foot is pinned whenever the fit scrolls.
        val gaps = TuckPanel.blockGaps(blocks = 3, hasFoot = true) * px(TuckPanel.BLOCK_GAP_DP)
        for (h in listOf(548, 560, 572)) {
            val net = TuckPanel.fitColumn(px(h), density, base, 15, labelled = true, gapsPx = gaps)
            assertTrue("landscape h=$h scrolls", net.scrolls)
            assertTrue("landscape h=$h pins Side", TuckPanel.footPinned(net.scrolls))
        }
        // Portrait fits: Side rides one gap after the room, spare room under it (no elastic gap).
        val portrait = TuckPanel.fitColumn(px(732), density, base, 15, labelled = true, gapsPx = gaps)
        assertFalse(portrait.scrolls)
        assertFalse(TuckPanel.footPinned(portrait.scrolls))
        // The gaps: one between blocks and one before a foot; none for an empty column.
        assertEquals(3, TuckPanel.blockGaps(3, hasFoot = true))
        assertEquals(2, TuckPanel.blockGaps(3, hasFoot = false))
        assertEquals(4, TuckPanel.blockGaps(4, hasFoot = true))   // the player block under the frame
        assertEquals(0, TuckPanel.blockGaps(0, hasFoot = true))
    }

    @Test
    fun `a list's full screen answers at once - no ink-page debounce`() {
        // Review 2026-10-11, F14: the 1.5s is the B6 daemon's; a list has no daemon.
        assertTrue(TuckPanel.LIST_DEBOUNCE_MS < TuckPanel.FULL_DEBOUNCE_MS)
        assertTrue(TuckPanel.LIST_DEBOUNCE_MS <= 500L)
    }

    @Test
    fun `a tucked list surface comes back in full screen, once`() {
        // tuck_<key>_open = false and no chrome_full_<key> yet → full screen.
        assertTrue(TuckPanel.migratedFull(hasFull = false, full = false, hasOpen = true, open = false))
        // Open, or never set → the column.
        assertFalse(TuckPanel.migratedFull(hasFull = false, full = false, hasOpen = true, open = true))
        assertFalse(TuckPanel.migratedFull(hasFull = false, full = false, hasOpen = false, open = true))
        // The new key, once written, wins over any old tuck key.
        assertFalse(TuckPanel.migratedFull(hasFull = true, full = false, hasOpen = true, open = false))
        assertTrue(TuckPanel.migratedFull(hasFull = true, full = true, hasOpen = true, open = true))
    }

    @Test
    fun `the redress rule - a name may change under a face, a face may not`() {
        val a = TuckPanel.Item(7, "Read aloud", caption = "Listen") {}
        assertTrue(TuckPanel.sameFace(a, TuckPanel.Item(7, "Listen along", caption = "Listen") {}))
        assertTrue(TuckPanel.sameFace(a, TuckPanel.Item(7, "Read aloud", caption = "Listen", longPress = {}) {}))
        assertFalse(TuckPanel.sameFace(a, TuckPanel.Item(8, "Read aloud", caption = "Listen") {}))
        assertFalse(TuckPanel.sameFace(a, TuckPanel.Item(7, "Read aloud", caption = "Play") {}))
        assertFalse(TuckPanel.sameFace(a, TuckPanel.Item(7, "Read aloud", caption = "Listen", activeId = "x") {}))
        assertFalse(TuckPanel.sameFace(a, TuckPanel.Item(0, "Read aloud", glyph = "»", caption = "Listen") {}))
        // A caption derived from the name counts as the face: "Star this" → "Star", "Unstar" → "Unstar".
        assertFalse(TuckPanel.sameFace(TuckPanel.Item(1, "Star this") {}, TuckPanel.Item(1, "Unstar") {}))
    }

    @Test
    fun `a box marks what's on - on and off are the same face, flipped in place`() {
        // Michael 2026-10-11: "a box + bold caption marks what's on". The drawer opening, a search
        // summoned, Reader view — the seat keeps its face, so the redress rule flips one cell's box
        // instead of repainting the column (no B6 flash for a toggle).
        val off = TuckPanel.Item(3, "Feeds drawer", caption = "Feeds") {}
        val on = TuckPanel.Item(3, "Feeds drawer", caption = "Feeds", on = true) {}
        assertTrue(on.on && !off.on)
        assertTrue(TuckPanel.sameFace(off, on))
    }

    @Test
    fun `a dragged floating pill keeps its place as a fraction of its travel`() {
        // Michael 2026-10-12 (iPhone 247): "The Fullscreen button/notes nib in the feed list could be
        // movable too." The book reader's floating [⛶] pill: a 1000×1400 page, a 90×90 pill.
        val (fx, fy) = TuckPanel.listPillFractions(455f, 1310f, travelX = 910, travelY = 1310)
        assertEquals(0.5f, fx, 0.001f)
        assertEquals(1f, fy, 0.001f)
        // Laid out top-left at the 6px margin, it is carried back to the same spot…
        val (tx, ty) = TuckPanel.listPillTranslation(fx, fy, left = 6, top = 6, travelX = 910, travelY = 1310)
        assertEquals(449f, tx, 0.001f)
        assertEquals(1304f, ty, 0.001f)
        // …and a turned page (1400×1000) puts it at the same relative place: halfway across, flush down.
        val (rx, ry) = TuckPanel.listPillTranslation(fx, fy, left = 6, top = 6, travelX = 1310, travelY = 910)
        assertEquals(649f, rx, 0.001f)
        assertEquals(904f, ry, 0.001f)
        // Out-of-range spots and a pill wider than its page stay on the page's origin side.
        assertEquals(0f to 0f, TuckPanel.listPillFractions(-40f, 5000f, travelX = 0, travelY = -10))
        assertEquals(1f to 0f, TuckPanel.listPillFractions(2000f, -3f, travelX = 910, travelY = 1310))
        assertEquals(-6f to -6f, TuckPanel.listPillTranslation(0.7f, 2f, left = 6, top = 6, travelX = -20, travelY = 0))
    }
}
