package com.toolsboox

import com.toolsboox.plugin.calendar.ot.TaskEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Reading a due date out of a box the size of a thumbnail.
 *
 * The rule that matters most is the fallback: anything unreadable means TODAY. A task with a
 * wrong date is worse than one with an obvious date — you'll see today's on today's list and can
 * move it, whereas a task silently filed three weeks out is a task you have lost. Every test here
 * is really checking that nothing can quietly disappear into the future.
 */
class TaskEntryTest {

    // A Tuesday, so weekday arithmetic has somewhere to go in both directions.
    private val today: LocalDate = LocalDate.of(2026, 7, 21)

    private fun due(s: String?) = TaskEntry.parseDue(s, today)

    // --- the fallback ---------------------------------------------------------------------------

    @Test
    fun `nothing written means today`() {
        assertEquals(today, due(null))
        assertEquals(today, due(""))
        assertEquals(today, due("   "))
    }

    @Test
    fun `OCR mud means today rather than somewhere random`() {
        assertEquals(today, due("...."))
        assertEquals(today, due("~~"))
        assertEquals(today, due("qxz"))
        assertEquals(today, due("99/99"))
        assertEquals(today, due("0"))
    }

    // --- words ----------------------------------------------------------------------------------

    @Test
    fun `today and tomorrow`() {
        assertEquals(today, due("today"))
        assertEquals(today, due("tod"))
        assertEquals(today.plusDays(1), due("tomorrow"))
        assertEquals(today.plusDays(1), due("tom"))
        assertEquals(today.plusDays(1), due("tmrw"))
    }

    @Test
    fun `a weekday means the NEXT one, never today`() {
        // Today IS Tuesday — "tue" must mean next week, not zero days away.
        assertEquals(LocalDate.of(2026, 7, 28), due("tue"))
        assertEquals(LocalDate.of(2026, 7, 24), due("fri"))
        assertEquals(LocalDate.of(2026, 7, 24), due("friday"))
        assertEquals(LocalDate.of(2026, 7, 26), due("sun"))
    }

    @Test
    fun `weekday case and trailing punctuation do not matter`() {
        assertEquals(LocalDate.of(2026, 7, 24), due("Fri."))
        assertEquals(LocalDate.of(2026, 7, 24), due("  FRIDAY  "))
    }

    // --- durations ------------------------------------------------------------------------------

    @Test
    fun `days and weeks`() {
        assertEquals(today.plusDays(3), due("3d"))
        assertEquals(today.plusDays(3), due("in 3 days"))
        assertEquals(today.plusWeeks(2), due("2w"))
        assertEquals(today.plusWeeks(1), due("1 week"))
    }

    // --- dates ----------------------------------------------------------------------------------

    @Test
    fun `day and month`() {
        assertEquals(LocalDate.of(2026, 8, 3), due("3/8"))
        assertEquals(LocalDate.of(2026, 8, 3), due("3-8"))
    }

    @Test
    fun `a number that cannot be a month settles the order`() {
        // 25 can't be a month, so 12/25 is December 25th however it was written.
        assertEquals(LocalDate.of(2026, 12, 25), due("12/25"))
    }

    @Test
    fun `a date already gone means next year`() {
        // 1 March is behind us; nobody sets a task for last March.
        assertEquals(LocalDate.of(2027, 3, 1), due("1/3"))
    }

    @Test
    fun `a bare day of the month rolls forward`() {
        assertEquals(LocalDate.of(2026, 7, 24), due("24"))   // still ahead this month
        assertEquals(LocalDate.of(2026, 8, 3), due("3"))     // gone → next month
    }

    // --- geometry -------------------------------------------------------------------------------
    //
    // Only the row count is asserted here. The rects are `android.graphics.RectF`, which is a
    // stub under plain JUnit — asserting on it would test the stub returning zero, not the
    // layout, and a test that passes for the wrong reason is worse than no test. The geometry is
    // checked against the drawn page on the device instead.

    @Test
    fun `the grid leaves room for a two-row strip`() {
        assertTrue(TaskEntry.GRID_ROWS in 6..14)
    }
}
