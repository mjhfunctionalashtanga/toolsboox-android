package com.toolsboox

import com.toolsboox.plugin.calendar.ot.LedgerProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stamp's citation list, now fed from BOTH places a page's provenance lives.
 *
 * The case that forced this: a Gram Picks page has no text at all — its whole citation list is
 * its grams' sourceLink doors — so a stamp read from text alone left the one page made entirely
 * of sources looking sourceless ("does it link out to all of the provenance?", the 08-15 page).
 * The rules under test: element doors lead, internal doors (ledger:// book://) stay home, the
 * two lists dedupe, and the cap holds.
 */
class ProvenanceLinksTest {

    @Test
    fun `a textless page cites its elements`() {
        val links = LedgerProvenance.mergeSourceLinks(
            listOf("https://aeon.co/essays/attention", "https://arstechnica.com/ai/"), "")
        assertEquals(listOf("https://aeon.co/essays/attention", "https://arstechnica.com/ai/"), links)
    }

    @Test
    fun `element doors lead and text links follow`() {
        val links = LedgerProvenance.mergeSourceLinks(
            listOf("https://element.example/a"),
            "see https://text.example/b for more")
        assertEquals(listOf("https://element.example/a", "https://text.example/b"), links)
    }

    @Test
    fun `internal doors stay home`() {
        val links = LedgerProvenance.mergeSourceLinks(
            listOf("ledger://2026-08-12/grampicks", "book:///shelf/siddhartha.epub", "https://kept.example/x"), "")
        assertEquals(listOf("https://kept.example/x"), links)
    }

    @Test
    fun `the same door named twice is one door`() {
        val links = LedgerProvenance.mergeSourceLinks(
            listOf("https://one.example/piece"),
            "quoted from https://one.example/piece today")
        assertEquals(listOf("https://one.example/piece"), links)
    }

    @Test
    fun `the cap holds at eight`() {
        val many = (1..12).map { "https://site$it.example/p" }
        val links = LedgerProvenance.mergeSourceLinks(many, "")
        assertEquals(8, links.size)
        assertTrue(links.first().startsWith("https://site1"))
    }
}
