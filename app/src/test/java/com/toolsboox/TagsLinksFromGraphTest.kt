package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.ot.LedgerLinks
import com.toolsboox.plugin.calendar.ot.LedgerTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tags and links ACROSS DEVICES (Michael 2026-10-11: "tags aren't complete" · "The links aren't
 * quite working yet"): the occurrence stores are per device, the edges sync — so the indexes read
 * the synced edges back in. These pin what counts as a page, an object, and a link.
 */
class TagsLinksFromGraphTest {

    private val d = LocalDate.parse("2026-10-11")

    private fun edge(from: String, to: String, fromLabel: String = "", toLabel: String = "", deleted: Long = 0L) =
        Connection(id = "$from>$to", from = from, to = to, fromLabel = fromLabel, toLabel = toLabel,
            created = 10L, updated = 10L, deletedAt = deleted)

    @Test
    fun `an edge end names a page, a sub-page, or the page an element sits on`() {
        assertEquals(d to "0", LedgerTags.pageOfEnd("ledger://2026-10-11/0"))
        assertEquals(d to "grid-1753#2", LedgerTags.pageOfEnd("ledger://2026-10-11/grid-1753#2"))
        assertEquals(d to "0", LedgerTags.pageOfEnd("ledger://2026-10-11/0#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21"))
        assertEquals(d to "write-1753#2",
            LedgerTags.pageOfEnd("ledger://2026-10-11/write-1753#2#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21"))
        assertNull(LedgerTags.pageOfEnd("note://tn-1-2"))
        assertNull(LedgerTags.pageOfEnd("ledger://not-a-date/0"))
    }

    @Test
    fun `tag edges become pages and objects, live ones only, either direction`() {
        val view = LedgerTags.deriveFromEdges(listOf(
            edge("tag://ashtanga", "ledger://2026-10-11/0", "#ashtanga", "0"),
            edge("tag://ashtanga", "ledger://2026-10-11/0#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21"),  // a gram → its page
            edge("tag://ashtanga", "note://tn-1-2", "#ashtanga", "Morning practice"),
            edge("email://abc", "tag://ashtanga", "Re: Mysore", "#ashtanga"),                     // reversed
            edge("tag://ashtanga", "ledger://2026-10-11/3", deleted = 99L),                       // tombstoned
            edge("tag://ashtanga", "tag://yoga"),                                                  // tag ↔ tag
        ))
        assertEquals(listOf(d to "0"), view.pages["ashtanga"])
        val objects = view.objects["ashtanga"].orEmpty()
        assertEquals(listOf("note://tn-1-2", "email://abc"), objects.map { it.uri })
        assertEquals("Morning practice", objects[0].label)
        assertEquals("Re: Mysore", objects[1].label)
        assertNull(view.pages["yoga"])
    }

    @Test
    fun `link edges are told from a text note joined to a page`() {
        val view = LedgerLinks.deriveFromEdges(listOf(
            edge("ledger://2026-10-11/0", "note://collider", "0", "collider"),
            edge("ledger://2026-10-11/write-1753#2", "note://collider", "write-1753#2", "Collider"),
            edge("note://tn-1-2", "note://collider", "Morning practice", "Collider"),          // a Text Note's link
            edge("ledger://2026-10-11/0", "note://tn-9-9", "0", "Shopping list"),             // a text note, not a link
            edge("tag://x", "note://collider", "#x", "collider"),                             // a tag, not a link
            edge("ledger://2026-10-11/4", "note://collider", "4", "Collider", deleted = 5L),  // tombstoned
        ))
        assertEquals(setOf("collider"), view.names.keys)
        assertEquals("Collider", view.names["collider"])   // the prettiest spelling wins
        assertEquals(listOf("0", "write-1753#2"), view.pages["collider"]?.map { it.pageKey })
        assertEquals(listOf("note://tn-1-2" to "Morning practice"), view.objects["collider"])
        assertTrue(view.pages["tn-9-9"] == null)
        // The tombstoned link from page 4 is a CUT the backlinks must honour.
        assertEquals(setOf(d to "4"), view.removed["collider"])
    }

    @Test
    fun `an iOS sub-page key reads as Android's, and document keys pass through`() {
        assertEquals("grid#2", LedgerTags.androidPageKey("grid-2"))
        assertEquals("sketch#11", LedgerTags.androidPageKey("sketch-11"))
        assertEquals("write-1753900000000#3", LedgerTags.androidPageKey("write-1753900000000-3"))
        assertEquals("grid", LedgerTags.androidPageKey("grid-0"))
        // A document's own key (a 10+ digit stamp) is not "sub-page 1.75 trillion".
        assertEquals("grid-1753900000000", LedgerTags.androidPageKey("grid-1753900000000"))
        assertEquals("write-1234567890", LedgerTags.androidPageKey("write-1234567890"))
        assertEquals("grid-1753", LedgerTags.androidPageKey("grid-1753"))   // too long for a page index
        // Android's own keys, numbered Notes pages and unknown bases are untouched.
        assertEquals("grid#2", LedgerTags.androidPageKey("grid#2"))
        assertEquals("0", LedgerTags.androidPageKey("0"))
        assertEquals("pickings-2", LedgerTags.androidPageKey("pickings-2"))
        assertEquals(d to "grid#2", LedgerTags.pageOfEnd("ledger://2026-10-11/grid-2"))
        val gram = LedgerTags.endOf("ledger://2026-10-11/grid-2#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21")
        assertEquals(LedgerTags.PageEnd(d, "grid#2", element = true), gram)
    }

    @Test
    fun `a tombstoned whole-page tag edge with no live twin is a removal, grams are told apart`() {
        val view = LedgerTags.deriveFromEdges(listOf(
            edge("tag://x", "ledger://2026-10-11/0", deleted = 99L),                        // unticked on the iPad
            edge("tag://x", "ledger://2026-10-11/1", deleted = 99L),                        // tombstoned …
            edge("ledger://2026-10-11/1", "tag://x"),                                       // … but live the other way
            edge("tag://x", "ledger://2026-10-11/2#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21", deleted = 99L), // a gram's, not the page's
            edge("tag://x", "ledger://2026-10-11/3#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21"),  // live gram on page 3
            edge("tag://x", "ledger://2026-10-11/4"),                                       // live page 4
        ))
        assertEquals(setOf(d to "0"), view.removed["x"])
        assertEquals(setOf(d to "1", d to "4"), view.wholePages["x"])
        // Page 3 lists (the gram is found through its page) but is not a whole-page edge.
        assertEquals(setOf(d to "1", d to "3", d to "4"), view.pages["x"]?.toSet())
    }
}
