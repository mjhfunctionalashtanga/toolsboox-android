package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale

/**
 * A LOG LINE REMOVED STAYS REMOVED; AN EDITED ONE STAYS EDITED (design critique #2, Michael
 * 2026-10-12: an article highlight's Log line is edited in place — "It goes in the day it was
 * picked's log" — and removed with its highlight). Search, the corpus, Your Year and the Journal
 * read these lines, so the merge itself must keep them right.
 */
class ReadingEventMergeTest {

    private fun day(updated: Long) = CalendarDay(
        year = 2026, month = 10, day = 12, locale = Locale.US, startHour = null, updated = Date(updated))

    private fun line(id: String, note: String? = null, updatedAt: Long? = null) = ReadingEvent(
        id = id, kind = ReadingEvent.Kind.ARTICLE, date = Date(1_000L), title = "T",
        url = "https://example.com/a", excerpt = "the passage", note = note,
        updatedAt = updatedAt?.let { Date(it) })

    @Test
    fun `a removed line never comes back from the other device - whichever day is newer`() {
        val removedHere = day(2_000L).apply { ReadingEventEdit.tombstone(this, "mfhl-1-aaaa") }
        val stillThere = day(1_000L).apply { readingEvents.add(line("mfhl-1-aaaa")) }
        assertTrue(CalendarDayMerger.merge(removedHere, stillThere).readingEvents.isEmpty())
        // The other way round: the device that still has it saved LATER.
        val removedEarlier = day(1_000L).apply { ReadingEventEdit.tombstone(this, "mfhl-1-aaaa") }
        val savedLater = day(3_000L).apply { readingEvents.add(line("mfhl-1-aaaa")) }
        assertTrue(CalendarDayMerger.merge(removedEarlier, savedLater).readingEvents.isEmpty())
        // Case doesn't matter (iOS writes upper-case ids).
        val upper = day(1_000L).apply { deletedElementIds.add("MFHL-1-AAAA") }
        assertTrue(CalendarDayMerger.merge(upper, savedLater).readingEvents.isEmpty())
    }

    @Test
    fun `an edited line keeps its newer copy even when its day is the older one`() {
        val edited = day(1_000L).apply { readingEvents.add(line("m", note = "my note", updatedAt = 5_000L)) }
        val stale = day(9_000L).apply { readingEvents.add(line("m", note = null)) }
        assertEquals("my note", CalendarDayMerger.merge(edited, stale).readingEvents.single().note)
        assertEquals("my note", CalendarDayMerger.merge(stale, edited).readingEvents.single().note)
        // Two edits: the later one.
        val later = day(500L).apply { readingEvents.add(line("m", note = "second", updatedAt = 6_000L)) }
        assertEquals("second", CalendarDayMerger.merge(edited, later).readingEvents.single().note)
    }

    @Test
    fun `lines without updatedAt merge exactly as before - the newer day's copy`() {
        val newer = day(2_000L).apply { readingEvents.add(line("x", note = "newer day")) }
        val older = day(1_000L).apply { readingEvents.add(line("x", note = "older day")); readingEvents.add(line("y")) }
        val merged = CalendarDayMerger.merge(newer, older).readingEvents
        assertEquals(listOf("x", "y"), merged.map { it.id })
        assertEquals("newer day", merged.first().note)
        // Idempotent: merging the result again changes nothing.
        val again = CalendarDayMerger.merge(day(2_000L).apply { readingEvents.addAll(merged) }, older).readingEvents
        assertEquals(merged, again)
    }
}
