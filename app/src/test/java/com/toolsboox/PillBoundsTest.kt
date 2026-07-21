package com.toolsboox

import com.toolsboox.ot.PillBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clamp, at the screen sizes that break it.
 *
 * A Boox Note is 1404px wide and every pill fits, so the old clamp looked correct for as long as
 * nobody opened it on a phone. A Palma is 824px: a horizontal pill is wider than the screen, the
 * two limits swap over, and `max.coerceAtLeast(min)` pinned the pill where it sat.
 */
class PillBoundsTest {

    /** Boox Note-sized parent with a pill that comfortably fits. */
    @Test
    fun `a pill that fits can reach either edge`() {
        // Laid out at x=200..800 in a 1404-wide parent.
        val r = PillBounds.range(start = 200, end = 800, parentSize = 1404)
        assertEquals(-200f, r.start, 0.01f)   // flush left
        assertEquals(604f, r.endInclusive, 0.01f)  // flush right
    }

    @Test
    fun `a pill already flush left can still move right`() {
        val r = PillBounds.range(start = 0, end = 600, parentSize = 1404)
        assertEquals(0f, r.start, 0.01f)
        assertEquals(804f, r.endInclusive, 0.01f)
    }

    /** Palma: 824px wide, horizontal pill 1000px. This is the case that was immovable. */
    @Test
    fun `a pill wider than the screen can still be slid to both of its ends`() {
        val r = PillBounds.range(start = 0, end = 1000, parentSize = 824)
        // Both ends reachable: 0 shows its left end, -176 brings its right end into view.
        assertEquals(-176f, r.start, 0.01f)
        assertEquals(0f, r.endInclusive, 0.01f)
        assertTrue("an oversized pill must have somewhere to go", r.endInclusive > r.start)
    }

    @Test
    fun `the oversized range spans exactly the overflow`() {
        val parent = 824
        val pillWidth = 1000
        val r = PillBounds.range(start = 0, end = pillWidth, parentSize = parent)
        assertEquals((pillWidth - parent).toFloat(), r.endInclusive - r.start, 0.01f)
    }

    @Test
    fun `an oversized pill laid out off-origin still spans the overflow`() {
        val r = PillBounds.range(start = 40, end = 1040, parentSize = 824)
        assertEquals(-40f, r.endInclusive, 0.01f)
        assertEquals(-216f, r.start, 0.01f)
        assertEquals(176f, r.endInclusive - r.start, 0.01f)
    }

    /** A vertical pill taller than a short landscape screen — the same failure, other axis. */
    @Test
    fun `a pill taller than the screen can be slid up and down`() {
        val r = PillBounds.range(start = 0, end = 900, parentSize = 500)
        assertEquals(-400f, r.start, 0.01f)
        assertEquals(0f, r.endInclusive, 0.01f)
    }

    @Test
    fun `a pill exactly the size of its parent has nowhere to go and that is fine`() {
        val r = PillBounds.range(start = 0, end = 824, parentSize = 824)
        assertEquals(0f, r.start, 0.01f)
        assertEquals(0f, r.endInclusive, 0.01f)
    }

    @Test
    fun `the range is always orderable so coerceIn can never throw`() {
        val cases = listOf(
            Triple(0, 600, 1404), Triple(200, 800, 1404), Triple(0, 1000, 824),
            Triple(40, 1040, 824), Triple(0, 824, 824), Triple(700, 1400, 824),
            Triple(0, 0, 0), Triple(-50, 300, 824)
        )
        for ((s, e, p) in cases) {
            val r = PillBounds.range(s, e, p)
            assertTrue("range out of order for ($s,$e,$p)", r.start <= r.endInclusive)
            // And a wild restored position always lands inside it.
            assertTrue(99999f.coerceIn(r) in r)
            assertTrue((-99999f).coerceIn(r) in r)
        }
    }
}
