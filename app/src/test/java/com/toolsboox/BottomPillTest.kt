package com.toolsboox

import com.toolsboox.ot.BottomPill
import com.toolsboox.ot.BottomPill.Action
import com.toolsboox.ot.PillBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BOTTOM PILL (Michael 2026-10-11: "The one top left and the one bottom right") — the
 * top-left full-screen pill and the notes circle, one oblong `[corner | notes]` at the foot.
 * Pinned: the corner's four in the ruled order, the remembered pick and its fall-back on a face,
 * and the pill keeping clear of the tuck panel's column.
 */
class BottomPillTest {

    @Test
    fun `an ink page offers the four, in the ruled order`() {
        // "Put only one tuck item (eraser, pen, undo, full screen) and let you hold the button to
        // select one of the other options" — Full screen first, as the column's first seat is ⛶.
        assertEquals(listOf(Action.FULL, Action.PEN, Action.ERASER, Action.UNDO), BottomPill.offered(true))
    }

    @Test
    fun `a page with no pen tools offers Full screen only`() {
        // A read-only walk station (a period's All Stars / All Picks) — iOS's section pane.
        assertEquals(listOf(Action.FULL), BottomPill.offered(false))
    }

    @Test
    fun `the tap repeats the last pick`() {
        // "repeat whatever was last, long hold you change"
        for (a in Action.entries) assertEquals(a, BottomPill.chosenFor(a.key, hasPenTools = true))
    }

    @Test
    fun `nothing picked yet is Full screen`() {
        assertEquals(Action.FULL, BottomPill.chosenFor(null, hasPenTools = true))
        assertEquals(Action.FULL, BottomPill.chosenFor("bogus", hasPenTools = true))
    }

    @Test
    fun `a page with no pen tools wears Full screen and keeps the pick for the next ink page`() {
        // A read-only station has no pen tools on its corner: it shows ⛶ whatever was picked, and
        // the stored pick is untouched (chosenFor never writes), so the next ink page shows it again.
        for (a in listOf(Action.PEN, Action.ERASER, Action.UNDO)) {
            assertEquals(Action.FULL, BottomPill.chosenFor(a.key, hasPenTools = false))
            assertEquals(a, BottomPill.chosenFor(a.key, hasPenTools = true))
        }
    }

    @Test
    fun `a pick only picks a tool up, never puts it down`() {
        // Review 2026-10-11 (iOS's pickCorner): Pen with the pen in hand would throw the chooser
        // up; Eraser with the eraser in hand would put it down. Both only become the tap.
        assertFalse(BottomPill.pickRuns(Action.PEN, BottomPill.HAND_PEN))
        assertFalse(BottomPill.pickRuns(Action.ERASER, BottomPill.HAND_ERASER))
        assertTrue(BottomPill.pickRuns(Action.PEN, BottomPill.HAND_ERASER))
        assertTrue(BottomPill.pickRuns(Action.PEN, BottomPill.HAND_LASSO))
        assertTrue(BottomPill.pickRuns(Action.ERASER, BottomPill.HAND_PEN))
        // Undo and Full screen always run: neither is a tool you hold.
        for (hand in listOf(null, BottomPill.HAND_PEN, BottomPill.HAND_ERASER, BottomPill.HAND_LASSO)) {
            assertTrue(BottomPill.pickRuns(Action.UNDO, hand))
            assertTrue(BottomPill.pickRuns(Action.FULL, hand))
        }
    }

    @Test
    fun `the tool in hand shows on the corner in full screen`() {
        // Review 2026-10-11: pick Eraser, then Undo — the face says ↶ while the eraser is live,
        // and the next stroke erased with nothing on screen saying why. A badge says it now.
        assertEquals(BottomPill.HAND_ERASER, BottomPill.badgeFor(Action.UNDO, BottomPill.HAND_ERASER))
        assertEquals(BottomPill.HAND_ERASER, BottomPill.badgeFor(Action.FULL, BottomPill.HAND_ERASER))
        assertEquals(BottomPill.HAND_ERASER, BottomPill.badgeFor(Action.PEN, BottomPill.HAND_ERASER))
        assertEquals(BottomPill.HAND_LASSO, BottomPill.badgeFor(Action.ERASER, BottomPill.HAND_LASSO))
        // The Eraser face with the eraser in hand says it itself — boxed, no badge; the pen never
        // wears one (it is the resting tool).
        assertNull(BottomPill.badgeFor(Action.ERASER, BottomPill.HAND_ERASER))
        assertTrue(BottomPill.boxedFor(Action.ERASER, BottomPill.HAND_ERASER))
        assertTrue(BottomPill.boxedFor(Action.PEN, BottomPill.HAND_PEN))
        for (a in Action.entries) assertNull(BottomPill.badgeFor(a, BottomPill.HAND_PEN))
        assertFalse(BottomPill.boxedFor(Action.UNDO, BottomPill.HAND_ERASER))
    }

    @Test
    fun `the corner says what its tap does, as iOS's does`() {
        assertEquals("Leave full screen", BottomPill.cornerTitle(Action.FULL, full = true, hand = BottomPill.HAND_PEN))
        assertEquals("Full screen", BottomPill.cornerTitle(Action.FULL, full = false, hand = BottomPill.HAND_PEN))
        assertEquals("Eraser — tap for the pen", BottomPill.cornerTitle(Action.ERASER, false, BottomPill.HAND_ERASER))
        assertEquals("Pen — tap to choose a pen", BottomPill.cornerTitle(Action.PEN, false, BottomPill.HAND_PEN))
        assertEquals("Undo · eraser in hand", BottomPill.cornerTitle(Action.UNDO, true, BottomPill.HAND_ERASER))
        assertEquals("Leave full screen · lasso in hand", BottomPill.cornerTitle(Action.FULL, true, BottomPill.HAND_LASSO))
        assertEquals("Leave full screen", BottomPill.rowTitle(Action.FULL, full = true))
        assertEquals("Pen", BottomPill.rowTitle(Action.PEN, full = true))
    }

    @Test
    fun `stored keys stay stable`() {
        // The key is what a device remembers; renaming one would silently re-point the corner.
        assertEquals(listOf("full", "pen", "eraser", "undo"), Action.entries.map { it.key })
    }

    @Test
    fun `no full screen button twice - the column's head stands down while the corner wears it`() {
        // Michael 2026-10-12 (iPhone 247): "No need to see the full screen expand button on the tuck
        // if its on the note nib." Nothing picked, a stale pick, or a page with no pen tools: the
        // corner wears ⛶, so the column's ⛶ head goes.
        assertFalse(BottomPill.columnWearsFull(null, hasPenTools = true))
        assertFalse(BottomPill.columnWearsFull("bogus", hasPenTools = true))
        assertFalse(BottomPill.columnWearsFull(Action.FULL.key, hasPenTools = true))
        for (a in Action.entries) assertFalse(BottomPill.columnWearsFull(a.key, hasPenTools = false))
        // Pen, Eraser or Undo on the corner: the column keeps ⛶, so full screen is still one tap
        // (never behind the corner's hold — the no-feature-loss rule).
        for (a in listOf(Action.PEN, Action.ERASER, Action.UNDO))
            assertTrue(a.name, BottomPill.columnWearsFull(a.key, hasPenTools = true))
        // Exactly one ⛶ on every ink page, whatever the pick.
        for (stored in listOf<String?>(null) + Action.entries.map { it.key }) for (tools in listOf(true, false)) {
            val onCorner = BottomPill.chosenFor(stored, tools) == Action.FULL
            assertTrue("$stored/$tools", onCorner != BottomPill.columnWearsFull(stored, tools))
        }
    }

    @Test
    fun `the pill keeps clear of a left-docked column`() {
        // A portrait B6 row (1072px): the pill laid out at x=41..208 (22dp margin, two 44dp
        // halves), the day column 111px wide plus a 11px gap at the left edge.
        val r = PillBounds.rangeAvoiding(start = 41, end = 208, parentSize = 1072, avoidStart = 122, avoidEnd = 0)
        assertEquals(81f, r.start, 0.01f)        // its left edge stops at the column's
        assertEquals(864f, r.endInclusive, 0.01f) // and it may still reach the far edge
    }

    @Test
    fun `the pill keeps clear of a right-docked column`() {
        val r = PillBounds.rangeAvoiding(start = 41, end = 208, parentSize = 1072, avoidStart = 0, avoidEnd = 122)
        assertEquals(-41f, r.start, 0.01f)
        assertEquals(742f, r.endInclusive, 0.01f)
    }

    @Test
    fun `no column means the plain clamp`() {
        assertEquals(PillBounds.range(41, 208, 1072), PillBounds.rangeAvoiding(41, 208, 1072, 0, 0))
    }

    @Test
    fun `a pill that cannot clear the column still stays on screen`() {
        // A 300px window with a 250px column: no free band holds a 167px pill.
        val r = PillBounds.rangeAvoiding(start = 41, end = 208, parentSize = 300, avoidStart = 250, avoidEnd = 0)
        assertEquals(PillBounds.range(41, 208, 300), r)
        assertTrue(r.start <= r.endInclusive)
    }
}
