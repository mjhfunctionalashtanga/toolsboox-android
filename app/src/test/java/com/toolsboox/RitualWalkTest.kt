package com.toolsboox

import com.toolsboox.plugin.calendar.ot.RitualWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk, pinned.
 *
 * This exists because the walk and the Daily folder drifted apart once already: Daily was reordered
 * and the stepper was not, so the Day Skipper silently stopped visiting four of Daily's stations.
 * The failure was invisible from the code — both lists were individually reasonable — and only
 * showed up when Michael walked it on a device and wrote it on a punchlist.
 *
 * So the order is asserted literally here. If someone reorders Daily again, this test is the thing
 * that says the walk has to move with it.
 */
class RitualWalkTest {

    @Test
    fun `the walk is Daily, in Daily's order`() {
        assertEquals(
            listOf("All Stars", "Gram Picks", "Pickings", "Self Executive",
                   "Quick Wins", "Missed Connections", "Gratitude"),
            RitualWalk.ALL.map { it.label }
        )
    }

    @Test
    fun `the four stations Michael reported missing are all on it`() {
        val labels = RitualWalk.ALL.map { it.label }
        for (missing in listOf("Self Executive", "Quick Wins", "Missed Connections", "Gratitude")) {
            assertTrue("$missing is not on the walk", missing in labels)
        }
    }

    @Test
    fun `stepping forward visits every station in order and then ends`() {
        val visited = mutableListOf<String>()
        var here: String? = RitualWalk.first.key
        while (here != null) {
            visited += here
            here = RitualWalk.next(here)?.key
        }
        assertEquals(RitualWalk.ALL.map { it.key }, visited)
    }

    @Test
    fun `stepping back from the head and forward from the tail both end the walk`() {
        assertNull(RitualWalk.prev(RitualWalk.first.key))
        assertNull(RitualWalk.next(RitualWalk.last.key))
    }

    @Test
    fun `back and forward are exact inverses at every station`() {
        for (s in RitualWalk.ALL) {
            RitualWalk.next(s.key)?.let { assertEquals(s.key, RitualWalk.prev(it.key)?.key) }
            RitualWalk.prev(s.key)?.let { assertEquals(s.key, RitualWalk.next(it.key)?.key) }
        }
    }

    @Test
    fun `an off-walk page has no neighbours rather than falling to the first station`() {
        // The numbered notes tail and every named board must not resolve into the walk by accident:
        // indexOf returns -1 for them, and -1 + 1 == 0 would silently make "next" mean "All Stars".
        assertNull(RitualWalk.next("0"))
        assertNull(RitualWalk.prev("0"))
        assertNull(RitualWalk.station("synthesize"))
    }

    @Test
    fun `only the two standalone surfaces are non-page stations`() {
        assertEquals(
            listOf(RitualWalk.QUICK_WINS, RitualWalk.MISSED),
            RitualWalk.ALL.filter { !it.isDayPage }.map { it.key }
        )
        // pageKeys is what the day fragment matches its own notePage against — it must never
        // contain a surface key, or the fragment would claim to be standing on Quick Wins.
        assertTrue(RitualWalk.pageKeys.none { it == RitualWalk.QUICK_WINS || it == RitualWalk.MISSED })
    }

    @Test
    fun `every station has a glyph and a name, so none can arrive nameless in the fast-lane`() {
        assertTrue(RitualWalk.ALL.all { it.glyph.isNotBlank() && it.label.isNotBlank() })
        assertEquals(RitualWalk.ALL.size, RitualWalk.ALL.map { it.key }.distinct().size)
    }
}
