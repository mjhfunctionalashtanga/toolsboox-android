package com.toolsboox

import com.toolsboox.ot.TuckPanel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tuck panel's one piece of pure arithmetic: where a dragged tab docks. The promise worth
 * pinning is that the tab goes where the hand let go of it NEAREST — a drag toward an edge must
 * never dock the opposite one — and that a dead-centre release still answers deterministically
 * (vertical edges win, because a vertical list of rows is the panel's native shape).
 */
class TuckPanelTest {

    // A portrait Boox-ish screen; the numbers only need to make the geometry unambiguous.
    private val w = 1404f
    private val h = 1872f

    @Test
    fun `release near each edge docks that edge`() {
        assertEquals(TuckPanel.SIDE_LEFT, TuckPanel.nearestSide(10f, h / 2, w, h))
        assertEquals(TuckPanel.SIDE_RIGHT, TuckPanel.nearestSide(w - 10f, h / 2, w, h))
        assertEquals(TuckPanel.SIDE_TOP, TuckPanel.nearestSide(w / 2, 10f, w, h))
        assertEquals(TuckPanel.SIDE_BOTTOM, TuckPanel.nearestSide(w / 2, h - 10f, w, h))
    }

    @Test
    fun `corners split by which edge is truly closer`() {
        // Just inside the top-left corner, a hair closer to the left edge than the top.
        assertEquals(TuckPanel.SIDE_LEFT, TuckPanel.nearestSide(40f, 50f, w, h))
        // …and the mirror case, a hair closer to the top.
        assertEquals(TuckPanel.SIDE_TOP, TuckPanel.nearestSide(50f, 40f, w, h))
    }

    @Test
    fun `dead centre of a square is deterministic, and vertical wins`() {
        // On a square screen the centre is equidistant from all four edges. The tie must not
        // depend on float whims: left is the documented winner.
        assertEquals(TuckPanel.SIDE_LEFT, TuckPanel.nearestSide(500f, 500f, 1000f, 1000f))
    }

    @Test
    fun `landscape flip changes nothing about the answer's frame`() {
        // The same physical spot (near the long edge's middle) docks the same physical edge
        // whichever way the numbers come in — the edge-relative promise, in miniature.
        assertEquals(TuckPanel.SIDE_BOTTOM, TuckPanel.nearestSide(h / 2, w - 10f, h, w))
    }
}
