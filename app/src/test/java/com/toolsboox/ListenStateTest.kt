package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.ListenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listen sidecar's merge canon, tested where it can never be argued with.
 *
 * Two rules carry the whole file and both come from the calendar merge:
 *
 *  - newest [ListenState.updated] wins per episode — position is just the latest fact about a
 *    playhead, whichever device wrote it;
 *  - EXCEPT `done`, which is monotonic. "Heard" is a fact about the listener, not about any
 *    device's copy, and a stale device pushing an old position must never un-hear an episode.
 *
 * Plus the two little playback judgments ([ListenState.doneAt], [ListenState.resumePointMs])
 * that decide when a playhead counts as finished and when a stored spot is worth jumping to —
 * pure arithmetic, so they live here rather than being re-derived by eye in the player.
 */
class ListenStateTest {

    private fun ep(
        id: String = "https://pod.example/ep1",
        pos: Long = 0L, dur: Long = 3_600_000L, done: Boolean = false,
        updated: Long = 0L, deletedAt: Long = 0L
    ) = ListenState(id = id, positionMs = pos, durationMs = dur, done = done, updated = updated, deletedAt = deletedAt)

    // ---- newer wins ----------------------------------------------------------------------

    @Test
    fun `merge keeps the newer playhead`() {
        val mine = ep(pos = 600_000L, updated = 100L)
        val theirs = ep(pos = 1_800_000L, updated = 200L)
        assertEquals(1_800_000L, ListenState.merge(listOf(mine), listOf(theirs)).single().positionMs)
        // A newer local is NOT trampled by an older remote coming back the other way.
        assertEquals(1_800_000L, ListenState.merge(listOf(theirs), listOf(mine)).single().positionMs)
    }

    @Test
    fun `merge unions rather than replaces`() {
        val a = ep(id = "https://pod.example/a")
        val b = ep(id = "https://pod.example/b")
        assertEquals(2, ListenState.merge(listOf(a), listOf(b)).size)
    }

    // ---- done is monotonic ---------------------------------------------------------------

    @Test
    fun `a stale device cannot un-hear an episode`() {
        val heard = ep(pos = 3_550_000L, done = true, updated = 100L)
        // The other device paused at the halfway point LATER (a re-listen) — its position wins,
        // but the episode stays heard.
        val relisten = ep(pos = 1_800_000L, done = false, updated = 200L)
        val merged = ListenState.merge(listOf(heard), listOf(relisten)).single()
        assertEquals(1_800_000L, merged.positionMs)
        assertTrue(merged.done)
        // And in the other direction — the heard copy arriving as remote — the same.
        val merged2 = ListenState.merge(listOf(relisten), listOf(heard)).single()
        assertTrue(merged2.done)
    }

    @Test
    fun `done travels forward with a newer write`() {
        val unheard = ep(done = false, updated = 100L)
        val finished = ep(pos = 3_600_000L, done = true, updated = 200L)
        assertTrue(ListenState.merge(listOf(unheard), listOf(finished)).single().done)
    }

    // ---- tombstones ----------------------------------------------------------------------

    @Test
    fun `a delete travels and an older copy cannot resurrect it`() {
        val live = ep(updated = 100L)
        val killed = ep(updated = 200L, deletedAt = 200L)
        assertTrue(ListenState.merge(listOf(live), listOf(killed)).single().isDeleted)
        assertTrue(ListenState.merge(listOf(killed), listOf(live)).single().isDeleted)
    }

    // ---- the finish line -----------------------------------------------------------------

    @Test
    fun `ninety-five percent played is heard`() {
        assertTrue(ListenState.doneAt(positionMs = 950L, durationMs = 1_000L))
        assertTrue(ListenState.doneAt(positionMs = 1_000L, durationMs = 1_000L))
        assertFalse(ListenState.doneAt(positionMs = 949L, durationMs = 1_000L))
    }

    @Test
    fun `an unknown duration has no finish line`() {
        // 95% of nothing is not a finish line — a stream whose length never arrived can play for
        // an hour without being declared heard.
        assertFalse(ListenState.doneAt(positionMs = 3_600_000L, durationMs = 0L))
    }

    // ---- where to drop the playhead ------------------------------------------------------

    @Test
    fun `a stored spot worth jumping to is jumped to`() {
        assertEquals(600_000L, ListenState.resumePointMs(ep(pos = 600_000L)))
    }

    @Test
    fun `the first ten seconds are not worth resuming to`() {
        // You'd land where you started anyway; a seek would be ceremony.
        assertEquals(0L, ListenState.resumePointMs(ep(pos = 9_000L)))
        assertEquals(0L, ListenState.resumePointMs(null))
    }

    @Test
    fun `a finished episode restarts from the top`() {
        // Resuming into the outro it already heard is not resuming.
        assertEquals(0L, ListenState.resumePointMs(ep(pos = 3_590_000L, dur = 3_600_000L, done = true)))
    }
}
