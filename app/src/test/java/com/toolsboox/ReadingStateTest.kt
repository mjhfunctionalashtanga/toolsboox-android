package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.ReadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading sidecar's merge and its key.
 *
 * The merge is the plain house rule — id-union, newest write wins, tombstones travel — with no
 * monotonic flag anywhere: re-reading backwards is reading, and a locator that moves toward the
 * front of the book on the newest write is the truth, not a regression to be guarded against.
 *
 * The key is where the design lives: folder-qualified name + "#" + size, because the shelf is a
 * pointer at a synced folder — paths differ per device, the folder structure and the bytes do
 * not — and because a book REPLACED in the folder (different bytes) must start its own record
 * rather than inherit a locator minted against a file that no longer exists.
 */
class ReadingStateTest {

    private fun book(
        id: String = ReadingState.keyFor("Fiction", "Collider.epub", 123_456L),
        locator: String = "epubcfi(/6/4!/4/2/1:0)", percent: Double = 0.25,
        updated: Long = 0L, deletedAt: Long = 0L
    ) = ReadingState(id = id, locator = locator, percent = percent, updated = updated, deletedAt = deletedAt)

    // ---- the key -------------------------------------------------------------------------

    @Test
    fun `a shelf-root book keys as name and size`() {
        assertEquals("Collider.epub#123456", ReadingState.keyFor("", "Collider.epub", 123_456L))
    }

    @Test
    fun `a foldered book carries its folder in the key`() {
        assertEquals(
            "Fiction/DeLillo/Collider.epub#123456",
            ReadingState.keyFor("Fiction/DeLillo", "Collider.epub", 123_456L)
        )
    }

    @Test
    fun `the same name in two folders is two books`() {
        assertNotEquals(
            ReadingState.keyFor("Fiction", "notes.epub", 1L),
            ReadingState.keyFor("Poetry", "notes.epub", 1L)
        )
    }

    @Test
    fun `a replaced file is a new book`() {
        // A better scan, a fixed EPUB: different bytes, so the old locator (minted against the
        // old bytes) must not be applied to it.
        assertNotEquals(
            ReadingState.keyFor("Fiction", "Collider.epub", 123_456L),
            ReadingState.keyFor("Fiction", "Collider.epub", 999_999L)
        )
    }

    // ---- newer wins ----------------------------------------------------------------------

    @Test
    fun `merge keeps the newer read`() {
        val mine = book(locator = "epubcfi(/6/4!/4/2/1:0)", percent = 0.25, updated = 100L)
        val theirs = book(locator = "epubcfi(/6/8!/4/2/1:0)", percent = 0.60, updated = 200L)
        val merged = ReadingState.merge(listOf(mine), listOf(theirs)).single()
        assertEquals("epubcfi(/6/8!/4/2/1:0)", merged.locator)
        assertEquals(0.60, merged.percent, 1e-9)
        // A newer local is NOT trampled by an older remote coming back the other way.
        assertEquals("epubcfi(/6/8!/4/2/1:0)", ReadingState.merge(listOf(theirs), listOf(mine)).single().locator)
    }

    @Test
    fun `reading backwards on the newest write is still the truth`() {
        // No monotonic guard on position: the newest write wins even when it points earlier in
        // the book — going back to re-read a chapter IS where the reader now stands.
        val ahead = book(percent = 0.80, updated = 100L)
        val backTracked = book(percent = 0.40, updated = 200L)
        assertEquals(0.40, ReadingState.merge(listOf(ahead), listOf(backTracked)).single().percent, 1e-9)
    }

    @Test
    fun `merge unions rather than replaces`() {
        val a = book(id = ReadingState.keyFor("", "a.epub", 1L))
        val b = book(id = ReadingState.keyFor("", "b.epub", 2L))
        assertEquals(2, ReadingState.merge(listOf(a), listOf(b)).size)
    }

    // ---- tombstones ----------------------------------------------------------------------

    @Test
    fun `a delete travels and an older copy cannot resurrect it`() {
        val live = book(updated = 100L)
        val killed = book(updated = 200L, deletedAt = 200L)
        assertTrue(ReadingState.merge(listOf(live), listOf(killed)).single().isDeleted)
        assertTrue(ReadingState.merge(listOf(killed), listOf(live)).single().isDeleted)
    }
}
