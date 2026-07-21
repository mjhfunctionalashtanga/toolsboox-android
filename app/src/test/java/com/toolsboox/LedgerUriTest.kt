package com.toolsboox

import com.toolsboox.ot.LedgerUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Addresses for the things a connection joins.
 *
 * These strings get written into JSON and read back by a different platform, possibly a version
 * of it that has never heard of the scheme it is holding. So the rule under test is: an address
 * always survives a round trip, and an unknown one parses cleanly instead of throwing — it simply
 * doesn't resolve to anything.
 */
class LedgerUriTest {

    @Test
    fun `a page address round-trips`() {
        val uri = LedgerUri.page("2026-07-21", "intake")
        assertEquals("ledger://2026-07-21/intake", uri)
        val ref = LedgerUri.parse(uri)!!
        assertEquals("2026-07-21", ref.date)
        assertEquals("intake", ref.pageKey)
        assertFalse(ref.isElement)
        assertEquals(uri, ref.toString())
    }

    @Test
    fun `an element address carries its element id`() {
        val uri = LedgerUri.element("2026-07-21", "intake", "abc-123")
        val ref = LedgerUri.parse(uri)!!
        assertTrue(ref.isElement)
        assertEquals("abc-123", ref.fragment)
        assertEquals("intake", ref.pageKey)
        assertEquals(uri, ref.toString())
    }

    @Test
    fun `a page with no key reads as the day page`() {
        assertEquals("default", LedgerUri.parse("ledger://2026-07-21/")!!.pageKey)
        assertEquals("default", LedgerUri.parse("ledger://2026-07-21")!!.pageKey)
    }

    @Test
    fun `sidecar objects address by id`() {
        assertEquals("task", LedgerUri.parse(LedgerUri.task("t1"))!!.scheme)
        assertEquals("t1", LedgerUri.parse(LedgerUri.task("t1"))!!.body)
        assertEquals("contact", LedgerUri.parse(LedgerUri.contact("c1"))!!.scheme)
        assertEquals("clipping", LedgerUri.parse(LedgerUri.clipping("k1"))!!.scheme)
    }

    @Test
    fun `a web url keeps its own fragment`() {
        // The '#' in a web link belongs to the page, not to us — eating it would land the reader
        // at the top of the article instead of the passage that was saved.
        val ref = LedgerUri.parse("https://mjh.yoga/notes#anchor")!!
        assertTrue(ref.isWeb)
        assertNull(ref.fragment)
        assertEquals("mjh.yoga/notes#anchor", ref.body)
    }

    @Test
    fun `rubbish parses to null rather than throwing`() {
        assertNull(LedgerUri.parse(null))
        assertNull(LedgerUri.parse(""))
        assertNull(LedgerUri.parse("   "))
        assertNull(LedgerUri.parse("not-an-address"))
        assertNull(LedgerUri.parse("://nothing"))
        assertNull(LedgerUri.parse("ledger://"))
    }

    @Test
    fun `an unknown scheme survives instead of being rejected`() {
        val ref = LedgerUri.parse("gramophone://42")!!
        assertEquals("gramophone", ref.scheme)
        assertEquals("42", ref.body)
        assertEquals("gramophone://42", ref.toString())
    }

    @Test
    fun `describe gives something readable for each kind`() {
        assertEquals("2026-07-21 · intake", LedgerUri.describe("ledger://2026-07-21/intake"))
        assertEquals("2026-07-21", LedgerUri.describe("ledger://2026-07-21/default"))
        assertEquals("a task", LedgerUri.describe(LedgerUri.task("t1")))
        assertEquals("mjh.yoga", LedgerUri.describe("https://mjh.yoga/notes"))
    }
}
