package com.toolsboox

import com.toolsboox.plugin.calendar.ot.LedgerDocument
import com.toolsboox.plugin.calendar.ot.LedgerDocuments
import com.toolsboox.plugin.feeds.ui.pickNamedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * FOLLOW A LINK picks ONE note for a name (Michael 2026-10-11: "The links aren't quite working
 * yet"): matched the way links compare, newest first, and a same-day tie broken by kind in iOS
 * LedgerLinks.named()'s order so both apps open the same note.
 */
class LocateNoteTest {

    private val mon = LocalDate.parse("2026-10-05")
    private val tue = LocalDate.parse("2026-10-06")

    private fun doc(key: String, title: String, date: LocalDate) = LedgerDocument(key, title, date, true) { 1 }

    @Test
    fun `matches the link's comparison form`() {
        val names = listOf(LedgerDocuments.GRID to doc("grid-1", "Long  Run", mon))
        assertEquals("grid-1", pickNamedNote("  long run ", names)?.second?.key)
        assertNull(pickNamedNote("short run", names))
        assertNull(pickNamedNote("   ", names))
    }

    @Test
    fun `the newest note of a name wins`() {
        val names = listOf(
            LedgerDocuments.WRITE to doc("write-1", "Collider", mon),
            LedgerDocuments.PICKINGS to doc("pickings-2", "Collider", tue),
        )
        assertEquals("pickings-2", pickNamedNote("Collider", names)?.second?.key)
    }

    @Test
    fun `a same-day tie breaks by kind as iOS walks`() {
        val names = listOf(
            LedgerDocuments.PICKINGS to doc("pickings-2", "Collider", tue),
            LedgerDocuments.TEXT_NOTES to doc("tn-3", "Collider", tue),
            LedgerDocuments.JOT to doc("sketch-4", "Collider", tue),
            LedgerDocuments.WRITE to doc("write-1", "Collider", tue),
        )
        assertEquals("write-1", pickNamedNote("collider", names)?.second?.key)
        assertEquals("sketch-4", pickNamedNote("collider", names.filter { it.first != LedgerDocuments.WRITE })?.second?.key)
        assertEquals("tn-3", pickNamedNote("collider", names.filter { it.first == LedgerDocuments.PICKINGS || it.first == LedgerDocuments.TEXT_NOTES })?.second?.key)
    }
}
