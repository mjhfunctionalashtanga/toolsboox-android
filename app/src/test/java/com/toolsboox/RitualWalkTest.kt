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
        // NARROWED by his 2026-08-23 morning ruling ("the Daily category can be All Stars, Gram
        // Picks, Notes … Self Executive can be eliminated") — the walk shrank because the NOTE
        // absorbed the rest: pickings and gratitude are templates a note page wears now, so
        // stepping to them as separate stations would be stepping to the same paper twice.
        //
        // Then LED BY TODAY the same evening: "the page skipper can go today > all stars > gram
        // picks > todays note … that way when I slide with my finger from today it goes to all
        // stars, then gram pics, then todays note." Today is the schedule face itself, which is why
        // it is the one station that is not a day PAGE.
        // "Gram Picks" became "All Picks" on 2026-08-27 — Michael's vocabulary ruling: picks are
        // the atoms, Pickings is a template, so the shelf reads "All Stars / All Picks". Display
        // label only; the station key stays "grampicks" (asserted below), the wire unchanged.
        assertEquals(
            // "Notes", iOS's name for the stop, since the walk keeps the page's day (2026-10-11).
            listOf("Today", "All Stars", "All Picks", "Notes"),
            RitualWalk.ALL.map { it.label }
        )
    }

    @Test
    fun `the stations the 08-23 ruling took off the walk stay off it`() {
        // The 2026-08-04 punchlist put Self Executive, Quick Wins, Missed Connections and Gratitude
        // ON the walk; the 08-23 ruling took them off again, and this test inverted with it rather
        // than being deleted — an assertion that once caught a real bug is worth keeping pointed the
        // other way. Their SURFACES all still exist and keep their own doors; what changed is that
        // the ⚡ stepper no longer stops at them.
        val labels = RitualWalk.ALL.map { it.label }
        for (gone in listOf("Self Executive", "Quick Wins", "Missed Connections", "Gratitude", "Pickings")) {
            assertTrue("$gone was ruled off the walk but is still a station", gone !in labels)
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
        // "0" is the NOTES station itself since the 08-23 narrowing, so the off-walk example has to
        // be a page that is genuinely off it: a numbered note past the first, and a named board.
        assertNull(RitualWalk.next("3"))
        assertNull(RitualWalk.prev("3"))
        // …and Today leads, so nothing steps back off it inside the walk (the fragment exits to
        // the week almanac there, which is deliberately not the walk's business).
        assertNull(RitualWalk.prev(RitualWalk.TODAY))
        assertEquals("intake", RitualWalk.next(RitualWalk.TODAY)?.key)
        assertNull(RitualWalk.station("pickings-1753900000000"))
        assertNull(RitualWalk.station("synthesize"))
    }

    @Test
    fun `only Today is not a day page, and no non-page key leaks into pageKeys`() {
        // Quick Wins and Missed Connections left the walk; TODAY joined it as the schedule FACE,
        // which is a page of the day but not a NOTE page — it has no key strokes are stored under.
        // pageKeys is what the fragment matches its own notePage against, and a face key leaking
        // into it would let the day claim to be standing on a station while holding no page.
        assertEquals(listOf(RitualWalk.TODAY), RitualWalk.ALL.filter { !it.isDayPage }.map { it.key })
        assertTrue(RitualWalk.TODAY !in RitualWalk.pageKeys)
        assertEquals(RitualWalk.ALL.drop(1).map { it.key }, RitualWalk.pageKeys)
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
