package com.toolsboox

import com.toolsboox.plugin.calendar.widget.ScheduleSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Schedule widget's fold row, pinned as arithmetic against the template's 35 rows
 * (header + 34 half-hour cells): the fold sits on the day's OWN 1:00pm gridline —
 * `2 * (13 - startHour) + 1` — not on the literal 17 that was only 1pm for startHour = 5.
 * Whatever the fold, the two crops tile rows 0..35 completely, so the invariant worth
 * pinning is placement + bounds, not coverage.
 */
class ScheduleSplitTest {

    @Test
    fun `startHour 5 keeps the historical fold — row 17 IS 1pm there`() {
        assertEquals(17, ScheduleSplit.splitRow(5))
    }

    @Test
    fun `the fold tracks the day's start hour to the 1pm gridline`() {
        // Hour h occupies rows 2*(h - startHour) + 1 and the one below; the fold is 1pm's top line.
        assertEquals(13, ScheduleSplit.splitRow(7))   // 7am day: 1pm is 6 hours in
        assertEquals(19, ScheduleSplit.splitRow(4))   // 4am day: 1pm is 9 hours in
        assertEquals(27, ScheduleSplit.splitRow(0))   // midnight day: 1pm is 13 hours in
        assertEquals(3, ScheduleSplit.splitRow(12))   // noon day: 1pm is the first full hour
    }

    @Test
    fun `days where 1pm is not usefully inside the range fold at the middle`() {
        // NO_GRID (-1) draws no hours at all — nothing for a 1pm fold to mean.
        assertEquals(ScheduleSplit.MIDDLE_ROW, ScheduleSplit.splitRow(-1))
        // A start at/after 1pm puts the whole column in the afternoon.
        assertEquals(ScheduleSplit.MIDDLE_ROW, ScheduleSplit.splitRow(13))
        assertEquals(ScheduleSplit.MIDDLE_ROW, ScheduleSplit.splitRow(23))
        // Garbage hours must not produce a fold outside the drawn rows.
        assertEquals(ScheduleSplit.MIDDLE_ROW, ScheduleSplit.splitRow(24))
        assertEquals(ScheduleSplit.MIDDLE_ROW, ScheduleSplit.splitRow(99))
    }

    @Test
    fun `every fold leaves at least one full hour on each side of the 35 rows`() {
        for (h in -2..25) {
            val row = ScheduleSplit.splitRow(h)
            assertTrue("fold $row for startHour $h must keep 2+ cells above", row >= 3)
            assertTrue(
                "fold $row for startHour $h must keep 2+ cells below",
                row <= ScheduleSplit.TOTAL_ROWS - 2
            )
        }
    }
}
