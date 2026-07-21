package com.toolsboox

import com.toolsboox.plugin.calendar.ot.TaskEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- splitting a date off the words --------------------------------------------------------

    @Test
    fun `a trailing weekday becomes the due date`() {
        val (text, due) = TaskEntry.splitTrailingDue("call Dad fri", today)
        assertEquals("call Dad", text)
        assertEquals(LocalDate.of(2026, 7, 24), due)
    }

    @Test
    fun `a dangling preposition goes with the date`() {
        val (text, due) = TaskEntry.splitTrailingDue("meet Sam on tuesday", today)
        assertEquals("meet Sam", text)
        assertEquals(LocalDate.of(2026, 7, 28), due)
    }

    @Test
    fun `two words that are one date are read together`() {
        val (text, due) = TaskEntry.splitTrailingDue("book flights in 3 days", today)
        assertEquals("book flights", text)
        assertEquals(today.plusDays(3), due)
    }

    @Test
    fun `ordinary words are left entirely alone`() {
        // The common case, and the one that must never misfire: `parseDue` answers "today" for
        // anything it doesn't recognise, so only a word resolving to some OTHER day counts.
        for (t in listOf("call Dad", "email Bob", "get bent", "fix the shoulder thing")) {
            val (text, due) = TaskEntry.splitTrailingDue(t, today)
            assertEquals(t, text)
            assertNull(due)
        }
    }

    @Test
    fun `a task that is nothing but a date keeps its words`() {
        // "friday" alone is a task called friday, not an empty task due Friday.
        val (text, due) = TaskEntry.splitTrailingDue("friday", today)
        assertEquals("friday", text)
        assertNull(due)
    }

    @Test
    fun `a single word is never split`() {
        val (text, due) = TaskEntry.splitTrailingDue("dentist", today)
        assertEquals("dentist", text)
        assertNull(due)
    }

    // --- splitting a time off the words ---------------------------------------------------------

    @Test
    fun `a trailing clock time is read`() {
        assertEquals("call Dad" to "15:00", TaskEntry.splitTrailingTime("call Dad 3pm"))
        assertEquals("standup" to "09:30", TaskEntry.splitTrailingTime("standup 09:30"))
        assertEquals("yoga" to "18:45", TaskEntry.splitTrailingTime("yoga 6:45pm"))
    }

    @Test
    fun `midnight and noon are not confused`() {
        assertEquals("shift" to "00:00", TaskEntry.splitTrailingTime("shift 12am"))
        assertEquals("lunch" to "12:00", TaskEntry.splitTrailingTime("lunch 12pm"))
    }

    @Test
    fun `a dangling at goes with the time`() {
        assertEquals("meet Sam" to "15:00", TaskEntry.splitTrailingTime("meet Sam at 3pm"))
    }

    @Test
    fun `a bare number is a day of the month, not an hour`() {
        // "call Dad 24" is the 24th. Only an explicit am/pm or a colon makes it a clock time —
        // otherwise every task ending in a number would silently acquire one.
        assertEquals("call Dad 24" to null, TaskEntry.splitTrailingTime("call Dad 24"))
        assertEquals("run 5" to null, TaskEntry.splitTrailingTime("run 5"))
    }

    @Test
    fun `nonsense is left in the name rather than guessed at`() {
        assertEquals("call Dad" to null, TaskEntry.splitTrailingTime("call Dad"))
        assertEquals("meet at 99pm" to null, TaskEntry.splitTrailingTime("meet at 99pm"))
        assertEquals("3pm" to null, TaskEntry.splitTrailingTime("3pm"))
    }
}
