package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.BookWant
import com.toolsboox.plugin.calendar.da.v2.ReadingSession
import com.toolsboox.plugin.reader.ui.BookOpens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wants, the sessions, and the opens merge — the three sidecars the 08-27 library ruling
 * added ("downloaded to one device, downloaded to all" + time-by-medium stats). All three are
 * the house physics: id-union, newest stamp wins, tombstones travel; opens are pure facts and
 * simply union. Pinned here so the wire never drifts under the iOS twin.
 */
class BookWantTest {

    // ---- wants --------------------------------------------------------------------------

    @Test
    fun `a fresher ask beats a stale one, and a re-ask revives a tombstone`() {
        val stale = BookWant(id = "a#1", wantedBy = "Palma", at = 100L)
        val fresh = BookWant(id = "a#1", wantedBy = "Tab 8", at = 200L)
        val merged = BookWant.merge(listOf(stale), listOf(fresh))
        assertEquals(1, merged.size)
        assertEquals("Tab 8", merged.first().wantedBy)

        val dead = BookWant(id = "a#1", at = 100L, deletedAt = 300L)
        val revived = BookWant(id = "a#1", at = 400L, deletedAt = 0L)
        val out = BookWant.merge(listOf(dead), listOf(revived)).first()
        assertFalse(out.isDeleted)
        assertEquals(400L, out.at)
    }

    @Test
    fun `a newer tombstone retires a live want`() {
        val live = BookWant(id = "b#2", at = 100L)
        val dead = BookWant(id = "b#2", at = 100L, deletedAt = 500L)
        assertTrue(BookWant.merge(listOf(live), listOf(dead)).first().isDeleted)
    }

    @Test
    fun `union keeps both books`() {
        val merged = BookWant.merge(
            listOf(BookWant(id = "a#1", at = 1L)), listOf(BookWant(id = "b#2", at = 2L)))
        assertEquals(setOf("a#1", "b#2"), merged.mapTo(mutableSetOf()) { it.id })
    }

    // ---- sessions -----------------------------------------------------------------------

    @Test
    fun `sessions union by id and time-by-medium is pure addition`() {
        val a = ReadingSession(id = "rs-1", medium = ReadingSession.BOOK, start = 10L, durationMs = 100L)
        val b = ReadingSession(id = "rs-2", medium = ReadingSession.COMIC, start = 20L, durationMs = 200L)
        val c = ReadingSession(id = "rs-3", medium = ReadingSession.BOOK, start = 30L, durationMs = 50L)
        val merged = ReadingSession.merge(listOf(a, b), listOf(b, c))
        assertEquals(3, merged.size)
        val byMedium = merged.filter { !it.isDeleted }
            .groupBy { it.medium }.mapValues { (_, r) -> r.sumOf { it.durationMs } }
        assertEquals(150L, byMedium[ReadingSession.BOOK])
        assertEquals(200L, byMedium[ReadingSession.COMIC])
    }

    // ---- opens --------------------------------------------------------------------------

    @Test
    fun `opens union newest-first and cap`() {
        val merged = BookOpens.mergeOpens(
            mapOf("x.epub" to listOf(5L, 3L)),
            mapOf("x.epub" to listOf(4L, 3L), "y.cbz" to listOf(9L)))
        assertEquals(listOf(5L, 4L, 3L), merged["x.epub"])
        assertEquals(listOf(9L), merged["y.cbz"])
    }
}
