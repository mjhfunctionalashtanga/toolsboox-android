package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.MediaLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media log's merge, pinned. It is a SYNCED sidecar, so these rules are what stop two devices
 * disagreeing about your own reading history — the failure they prevent is silent and only shows up
 * as a row that quietly went missing weeks later.
 */
class MediaLogMergeTest {

    private fun e(id: String, at: Long, updated: Long, done: Boolean = false, title: String = "") =
        MediaLogEntry(id = id, kind = MediaLogEntry.READ, title = title, at = at, done = done, updated = updated)

    @Test
    fun `newest write wins per id`() {
        val old = e("a", at = 100, updated = 100, title = "old")
        val new = e("a", at = 200, updated = 200, title = "new")
        val merged = MediaLogEntry.merge(listOf(old), listOf(new))
        assertEquals(1, merged.size)
        assertEquals("new", merged.first().title)
    }

    @Test
    fun `engaging on one device never un-engages it on another — at takes the later`() {
        // The stale device writes last (its clock, its updated) but saw an OLDER engagement. The
        // honest answer to "when did I last read this" is the latest any copy saw, so `at` is a max
        // rather than whatever the winning record happened to carry.
        val recent = e("a", at = 900, updated = 100)
        val staleButNewerWrite = e("a", at = 100, updated = 500)
        assertEquals(900L, MediaLogEntry.merge(listOf(recent), listOf(staleButNewerWrite)).first().at)
        assertEquals(900L, MediaLogEntry.merge(listOf(staleButNewerWrite), listOf(recent)).first().at)
    }

    @Test
    fun `done only ever goes forward`() {
        val finished = e("a", at = 100, updated = 100, done = true)
        val notYet = e("a", at = 200, updated = 999, done = false)
        assertTrue(MediaLogEntry.merge(listOf(finished), listOf(notYet)).first().done)
        assertTrue(MediaLogEntry.merge(listOf(notYet), listOf(finished)).first().done)
    }

    @Test
    fun `rows from both sides survive, and a tombstone travels`() {
        val mine = e("a", at = 100, updated = 100)
        val theirs = e("b", at = 100, updated = 100)
        assertEquals(2, MediaLogEntry.merge(listOf(mine), listOf(theirs)).size)
        // A forget is a write like any other: it wins by being newer, and stays deleted.
        val dead = mine.copy(deletedAt = 500, updated = 500)
        val merged = MediaLogEntry.merge(listOf(mine), listOf(dead)).first { it.id == "a" }
        assertTrue(merged.isDeleted)
    }

    @Test
    fun `the three kinds are the feed lenses' own words`() {
        // One vocabulary: the log's filter and the drawer's lenses must never drift apart.
        assertEquals(listOf("read", "watch", "listen"), MediaLogEntry.KINDS)
        assertEquals(MediaLogEntry.KINDS.size, MediaLogEntry.KINDS.map { MediaLogEntry.glyph(it) }.toSet().size)
        assertEquals(MediaLogEntry.KINDS.size, MediaLogEntry.KINDS.map { MediaLogEntry.label(it) }.toSet().size)
    }
}
