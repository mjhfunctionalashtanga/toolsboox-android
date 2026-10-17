package com.toolsboox

import com.toolsboox.plugin.feeds.ui.IncomingPlace
import com.toolsboox.plugin.feeds.ui.IncomingPlace.Door
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remembered place (Michael 2026-10-11, Q1: "going back to where you were on incoming should
 * be easy"): every way in lands where you were, a door moves only the lens it names, and ☀ Today
 * is the one-tap reset to plain Unread for the lens you're in. Feeds only since The Mail got its
 * own room the same day (its place is MailPlaceTest's).
 */
class IncomingPlaceTest {

    private val readingAeon = IncomingPlace(
        mode = "both", kind = "read", view = "both",
        category = "Essays", feed = "Aeon", pickedLens = "read",
    )

    @Test
    fun `the plain door and a cold start land on the whole place`() {
        assertEquals(readingAeon, IncomingPlace.arrive(readingAeon, null))
    }

    @Test
    fun `a door to the same lens keeps the feed and the view`() {
        val p = IncomingPlace.arrive(readingAeon, Door(mode = "feed", kind = "read"))
        assertEquals("both", p.mode)
        assertEquals("Aeon", p.feed)
        assertEquals("Essays", p.category)
    }

    @Test
    fun `a door to another lens drops the pick but keeps your view`() {
        val p = IncomingPlace.arrive(readingAeon, Door(mode = "feed", kind = "listen"))
        assertEquals("listen", p.kind)
        assertEquals("both", p.mode)
        assertNull(p.feed); assertNull(p.category); assertNull(p.pickedLens)
    }

    @Test
    fun `a door that names a view gets it`() {
        assertEquals("stars", IncomingPlace.arrive(readingAeon, Door(mode = "stars", kind = null)).mode)
    }

    @Test
    fun `a stray mail door lands on everything - The Mail has its own room`() {
        val p = IncomingPlace.arrive(readingAeon, Door(mode = "feed", kind = "mail"))
        assertNull(p.kind); assertEquals("both", p.mode); assertNull(p.feed)
    }

    @Test
    fun `the Later List door is the whole list - only the plain door brings a lane back`() {
        val later = IncomingPlace(mode = "later", laterLane = "watch")
        assertNull(IncomingPlace.arrive(later, Door(mode = "later", kind = null)).laterLane)
        assertEquals("read", IncomingPlace.arrive(later, Door(mode = "later", kind = null, laterLane = "read")).laterLane)
        assertNull(IncomingPlace.arrive(later, Door(mode = "feed", kind = "listen")).laterLane)
        assertEquals("watch", IncomingPlace.arrive(later, null).laterLane)
    }

    @Test
    fun `a door to Starred never inherits a hidden feed pick`() {
        // Go to feed saves a no-lens pick; Hub ⭐ Starred names no lens either — it asked for the
        // starred pile, not for that feed's slice of it.
        val goneToFeed = IncomingPlace.goToFeed(IncomingPlace(), "Aeon")
        val starred = IncomingPlace.arrive(goneToFeed, Door(mode = "stars", kind = null))
        assertEquals("stars", starred.mode); assertNull(starred.feed)
        // A door that asks for your view of that same lens keeps it.
        assertEquals("Aeon", IncomingPlace.arrive(goneToFeed, Door(mode = "feed", kind = null)).feed)
    }

    @Test
    fun `Today is plain Unread for the lens you're in`() {
        val t = IncomingPlace.today(readingAeon)
        assertEquals("feed", t.mode); assertEquals("feed", t.view); assertEquals("read", t.kind)
        assertNull(t.feed); assertNull(t.category)
        assertTrue(IncomingPlace.isHome(t))
        // Starred is a view: Today is the way out of it.
        assertEquals("feed", IncomingPlace.today(IncomingPlace(mode = "stars")).mode)
    }

    @Test
    fun `home means plain Unread`() {
        assertFalse(IncomingPlace.isHome(readingAeon))
        assertFalse(IncomingPlace.isHome(IncomingPlace(mode = "stars")))
        assertTrue(IncomingPlace.isHome(IncomingPlace(kind = "listen")))
    }

    @Test
    fun `capture keeps places, not moments`() {
        // A transient list search's smart feed leaves the last place standing.
        assertNull(IncomingPlace.capture(IncomingPlace(mode = "smart", smartId = "")))
        assertEquals("s1", IncomingPlace.capture(IncomingPlace(mode = "smart", smartId = "s1"))?.smartId)
        // A past date's edition keeps its lens at the standing view.
        val ed = IncomingPlace.capture(IncomingPlace(mode = "edition", kind = "watch", view = "read"))!!
        assertEquals("read", ed.mode); assertEquals("watch", ed.kind)
        // The view is the deliberate one (👁), not whatever is on screen: a drawer lens tap's All
        // is kept as the place's mode, while lens doors still land on your Unread.
        val lensTap = IncomingPlace.capture(IncomingPlace(mode = "both", kind = "read", view = "feed"))!!
        assertEquals("both", lensTap.mode); assertEquals("feed", lensTap.view)
        assertEquals("feed", IncomingPlace.arrive(lensTap, Door(mode = "feed", kind = "listen")).mode)
        // Starred is a moment: the last place stands.
        assertNull(IncomingPlace.capture(IncomingPlace(mode = "stars")))
        // A mail lens is never a Feeds place (The Mail keeps its own — MailPlace).
        assertNull(IncomingPlace.capture(IncomingPlace(mode = "feed", kind = "mail")))
        // A pick made under another lens isn't kept.
        assertNull(IncomingPlace.capture(readingAeon.copy(kind = "listen"))?.feed)
        assertEquals("Aeon", IncomingPlace.capture(readingAeon)?.feed)
        // Retired or unknown modes aren't places.
        assertNull(IncomingPlace.capture(IncomingPlace(mode = "pickings")))
    }

    @Test
    fun `Go to feed lands on everything, narrowed to the feed`() {
        val p = IncomingPlace.goToFeed(readingAeon.copy(kind = "listen", pickedLens = "listen"), "Hidden Brain")
        assertNull(p.kind); assertEquals("both", p.mode); assertEquals("Hidden Brain", p.feed); assertNull(p.pickedLens)
    }

    @Test
    fun `a piece never opens over a stored mail lens`() {
        val p = IncomingPlace.forPiece(IncomingPlace(mode = "stars", kind = "mail", view = "read"))
        assertNull(p.kind); assertEquals("read", p.mode)
        assertEquals(readingAeon, IncomingPlace.forPiece(readingAeon))
    }
}
