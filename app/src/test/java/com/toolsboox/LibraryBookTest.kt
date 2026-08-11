package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.LibraryBook
import com.toolsboox.plugin.calendar.da.v2.ReadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library manifest's merge and its id discipline.
 *
 * The merge is the house rule — id-union, newer wins, tombstones travel — with one wrinkle worth
 * pinning: the wire has NO `updated` field (the ruled shape is id·name·folder·size·hash·addedBy·
 * added·deletedAt), so the record's clock is DERIVED — the newer of `added` and `deletedAt`. These
 * tests are what keep that derivation honest: a delete must beat the add it deletes, and a genuine
 * re-add must beat the delete it undoes, with nothing but those two stamps to argue from.
 *
 * The id is [ReadingState.keyFor] verbatim, and the test for that is not decoration: the manifest,
 * the reading-position sidecar and the shelf cache all name a book `folder/name#size`, and the day
 * one of them drifts is the day a fetched book stands beside its own ghost forever.
 */
class LibraryBookTest {

    private fun book(
        id: String = LibraryBook.keyFor("Fiction", "Collider.epub", 123_456L),
        added: Long = 0L, deletedAt: Long = 0L
    ) = LibraryBook(
        id = id, name = "Collider.epub", folder = "Fiction", size = 123_456L,
        hash = "", addedBy = "Boox Go 6", added = added, deletedAt = deletedAt
    )

    // ---- the id -------------------------------------------------------------------------

    @Test
    fun `the manifest id is the reading-state id, byte for byte`() {
        assertEquals(
            ReadingState.keyFor("Fiction/DeLillo", "Collider.epub", 123_456L),
            LibraryBook.keyFor("Fiction/DeLillo", "Collider.epub", 123_456L)
        )
        assertEquals("Collider.epub#123456", LibraryBook.keyFor("", "Collider.epub", 123_456L))
    }

    @Test
    fun `the hub path preserves the folder structure`() {
        assertEquals("books/Fiction/Collider.epub", book().remotePath)
        assertEquals(
            "books/at-the-root.epub",
            LibraryBook(id = "x", name = "at-the-root.epub", folder = "").remotePath
        )
    }

    // ---- the derived clock --------------------------------------------------------------

    @Test
    fun `a record's stamp is its most recent act`() {
        assertEquals(100L, book(added = 100L).stamp)
        assertEquals(200L, book(added = 100L, deletedAt = 200L).stamp)
        // A re-add: added newer than the old tombstone — the add is the newest act.
        assertEquals(300L, book(added = 300L, deletedAt = 200L).stamp)
    }

    // ---- the merge ----------------------------------------------------------------------

    @Test
    fun `merge unions rather than replaces`() {
        val a = book(id = LibraryBook.keyFor("", "a.epub", 1L))
        val b = book(id = LibraryBook.keyFor("", "b.epub", 2L))
        assertEquals(2, LibraryBook.merge(listOf(a), listOf(b)).size)
    }

    @Test
    fun `a delete travels and an older copy cannot resurrect it`() {
        val live = book(added = 100L)
        val killed = book(added = 100L, deletedAt = 200L)
        assertTrue(LibraryBook.merge(listOf(live), listOf(killed)).single().isDeleted)
        assertTrue(LibraryBook.merge(listOf(killed), listOf(live)).single().isDeleted)
    }

    @Test
    fun `a genuine re-add beats the tombstone it undoes`() {
        // The book left the library at t=200 and came back at t=300 — the fleet should have it.
        val killed = book(added = 100L, deletedAt = 200L)
        val readded = book(added = 300L, deletedAt = 0L)
        assertFalse(LibraryBook.merge(listOf(killed), listOf(readded)).single().isDeleted)
        assertFalse(LibraryBook.merge(listOf(readded), listOf(killed)).single().isDeleted)
    }

    @Test
    fun `newer metadata wins per id in both directions`() {
        val old = book(added = 100L).copy(addedBy = "Old Device")
        val new_ = book(added = 200L).copy(addedBy = "New Device")
        assertEquals("New Device", LibraryBook.merge(listOf(old), listOf(new_)).single().addedBy)
        // The newer LOCAL is not trampled by an older remote coming back the other way.
        assertEquals("New Device", LibraryBook.merge(listOf(new_), listOf(old)).single().addedBy)
    }

    @Test
    fun `an empty remote leaves local untouched — a failed pull is not an empty library`() {
        val a = book(added = 100L)
        assertEquals(listOf(a), LibraryBook.merge(listOf(a), emptyList()))
    }
}
