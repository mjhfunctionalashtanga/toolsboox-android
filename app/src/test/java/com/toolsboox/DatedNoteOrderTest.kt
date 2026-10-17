package com.toolsboox

import com.toolsboox.plugin.calendar.ot.DatedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dated-note walk, pinned (DESIGN-notes-ia-2026-08-22: the day IS the note; the pager walks
 * the existing keys — never rekeys them). The order is a contract with the ‹ N › pager's "3/7"
 * indicator and with the number-tap modal's pages list, so it is asserted as a whole list rather
 * than property by property.
 */
class DatedNoteOrderTest {

    @Test
    fun `the dated walk is the numeric run alone — families are their own books`() {
        // TWO BOOKS PER DAY (Michael, 2026-08-27): the family daily pads no longer ride the
        // dated walk — a second book's pages must never read as pages of the dated note. Their
        // ink still renders; they walk on their OWN pager.
        assertEquals(
            listOf("0", "1", "2"),
            DatedNote.order(listOf("grid", "write", "2", "sketch", "0", "pickings", "1"), "0")
        )
    }

    @Test
    fun `family pads and their sub-pages are not dated pages`() {
        for (key in listOf("write", "write#1", "grid", "sketch", "pickings", "write-3"))
            assertFalse("$key is its own book, not a page of the dated note", DatedNote.isDatedPage(key))
    }

    @Test
    fun `the page you stand on always lists, inked or not`() {
        assertEquals(listOf("5"), DatedNote.order(emptyList(), "5"))
        // …and never lists twice when it also holds ink.
        assertEquals(listOf("0", "3"), DatedNote.order(listOf("0", "3"), "3"))
    }

    @Test
    fun `named documents and stations are not pages of the dated note`() {
        // A named document is a NAMED note; a station keeps its own door.
        for (key in listOf(
            "write-1785354515034", "pickings-1753900000000", "synthesize", "synthesize-1753900000000",
            "intake", "gratitude", "selfexec", "grampicks", "textnote"
        )) assertFalse("$key must not join the dated note", DatedNote.isDatedPage(key))
        for (key in listOf("0", "12", "doodle"))
            assertTrue("$key belongs to the dated note", DatedNote.isDatedPage(key))
    }

    @Test
    fun `the daily doodle sits right after the numeric run, before the families`() {
        // The standing daily Doodle (DESIGN-homepages-2026-08-22) is a page of the dated note,
        // seated between the numbered pages and the template families.
        assertTrue(DatedNote.isDatedPage(DatedNote.DOODLE))
        assertEquals(
            listOf("0", "1", "doodle"),
            DatedNote.order(listOf("write", "doodle", "1", "pickings", "0"), "0")
        )
        // Standing on it lists it, inked or not — first ink is what mints it.
        assertEquals(listOf("doodle"), DatedNote.order(emptyList(), "doodle"))
    }

    @Test
    fun `sparse numeric runs keep their numbers' order`() {
        assertEquals(
            listOf("0", "2", "10"),
            DatedNote.order(listOf("10", "2", "sketch", "0"), "2")
        )
    }

    @Test
    fun `family sub-pages of either fork's spelling are their own book's pages`() {
        // TWO BOOKS PER DAY (08-27): the cross-fork sub-page spellings ("write#1" / "write-1")
        // still parse as pages — of the FAMILY's book, on its own pager — never of the dated
        // note. Both spellings refuse membership here identically, so a synced day reads the
        // same on both forks.
        for (key in listOf("write-1", "grid-2", "sketch-12", "pickings-3", "write#1"))
            assertFalse("$key is its own book's page, not the dated note's", DatedNote.isDatedPage(key))
    }

    @Test
    fun `a minted document's millis tail never reads as a sub-page`() {
        // The store's epoch-millis convention is the whole difference between "write-1" (a page)
        // and "write-1785354515034" (a NAMED note that keeps its own door).
        assertFalse(DatedNote.isDatedPage("write-1785354515034"))
        assertFalse(DatedNote.isDatedPage("write-"))
        assertFalse(DatedNote.isDatedPage("write-x"))
        assertFalse(DatedNote.isDatedPage("doodle-1"))       // the daily Doodle has no sub-pages
        assertFalse(DatedNote.isDatedPage("synthesize-1"))   // a station, not a family
        // Under the 08-27 ruling every family key is its own book, millis tail or none.
        assertFalse(DatedNote.isDatedPage("write-123456789"))
        assertFalse(DatedNote.isDatedPage("write-1234567890"))
    }

    @Test
    fun `family keys of both spellings stay off the dated walk together`() {
        assertEquals(
            listOf("0"),
            DatedNote.order(listOf("write#2", "grid", "write-3", "write", "0", "write-1"), "0")
        )
    }

}
