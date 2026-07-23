package com.toolsboox

import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.da.v2.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edge that makes the surface more than the sum of its parts.
 *
 * Three properties are load-bearing and everything here is really testing one of them:
 *
 *  - an edge can be asked about from EITHER end, so there is no direction you have to be standing
 *    in to see what a thing joins;
 *  - the same edge made twice, on two devices with no coordinator, is ONE edge;
 *  - an edge whose far end has gone still behaves — it lists, it describes itself, it just does
 *    not resolve. Broken at a point, the rhizome carries on along its other lines.
 */
class ConnectionTest {

    private val task = LedgerUri.task("t-1")
    private val note = LedgerUri.element("2026-07-21", "intake", "e-9")
    private val who = LedgerUri.contact("c-3")

    @Test
    fun `the same pair joined twice is one edge`() {
        val a = Connection.of(note, task, Connection.SOURCE)
        val b = Connection.of(note, task, Connection.SOURCE)
        assertEquals(a.id, b.id)
    }

    @Test
    fun `direction is part of what an edge claims`() {
        // "the note is the source of the task" and the reverse are different statements.
        assertNotEquals(
            Connection.of(note, task, Connection.SOURCE).id,
            Connection.of(task, note, Connection.SOURCE).id
        )
    }

    @Test
    fun `kind is part of identity`() {
        assertNotEquals(
            Connection.of(note, task, Connection.SOURCE).id,
            Connection.of(note, task, Connection.MENTIONS).id
        )
    }

    @Test
    fun `an edge is visible from either end`() {
        val all = listOf(Connection.of(note, task, Connection.SOURCE))
        assertEquals(1, Connection.touching(all, note).size)
        assertEquals(1, Connection.touching(all, task).size)
        assertEquals(task, Connection.touching(all, note).first().otherEnd(note))
        assertEquals(note, Connection.touching(all, task).first().otherEnd(task))
    }

    @Test
    fun `something that is not an end has no other end`() {
        assertNull(Connection.of(note, task).otherEnd(who))
    }

    @Test
    fun `a tombstoned edge stops being seen`() {
        val dead = Connection.of(note, task).apply { deletedAt = 1L }
        assertTrue(Connection.touching(listOf(dead), note).isEmpty())
    }

    @Test
    fun `anything may be joined to anything`() {
        // No type check anywhere: a handwritten element to a contact is as valid as task to task.
        val all = listOf(
            Connection.of(note, who, Connection.ASSIGNED),
            Connection.of(task, note, Connection.ABOUT),
            Connection.of("https://mjh.yoga/notes", task, Connection.SOURCE)
        )
        assertEquals(2, Connection.touching(all, note).size)
        assertEquals(2, Connection.touching(all, task).size)
    }

    @Test
    fun `an edge into nothing still holds and still reads`() {
        val gone = LedgerUri.element("2019-01-01", "default", "deleted-long-ago")
        val all = listOf(Connection.of(task, gone))
        val edge = Connection.touching(all, task).single()
        assertEquals(gone, edge.otherEnd(task))
        assertEquals("2019-01-01", LedgerUri.describe(edge.otherEnd(task)))
    }

    @Test
    fun `merge keeps the newer write`() {
        val mine = Connection.of(note, task).apply { note = "mine"; updated = 100 }
        val theirs = mine.copy(note = "theirs", updated = 200)
        // A newer remote lands.
        assertEquals("theirs", Connection.merge(listOf(mine), listOf(theirs)).single().note)
        // A newer local is NOT trampled by an older remote coming back the other way.
        val newerLocal = mine.copy(note = "newer local", updated = 300)
        assertEquals("newer local", Connection.merge(listOf(newerLocal), listOf(theirs)).single().note)
    }

    @Test
    fun `a delete travels and an older copy cannot resurrect it`() {
        val live = Connection.of(note, task).apply { updated = 100 }
        val killed = live.copy(deletedAt = 200, updated = 200)
        assertTrue(Connection.merge(listOf(live), listOf(killed)).single().isDeleted)
        // The stale device pushes its live copy back; the tombstone is newer and holds.
        assertTrue(Connection.merge(listOf(killed), listOf(live)).single().isDeleted)
    }

    @Test
    fun `merge unions rather than replaces`() {
        val a = Connection.of(note, task)
        val b = Connection.of(task, who, Connection.ASSIGNED)
        assertEquals(2, Connection.merge(listOf(a), listOf(b)).size)
    }
}
