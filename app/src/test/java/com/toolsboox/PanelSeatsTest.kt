package com.toolsboox

import com.toolsboox.ot.PanelFaces
import com.toolsboox.ot.PanelSeats
import com.toolsboox.ot.PanelSeats.Dress
import com.toolsboox.ot.PanelSeats.Flags
import com.toolsboox.ot.PanelSeats.Kind
import com.toolsboox.ot.PanelSeats.Platform
import com.toolsboox.ot.PanelSeats.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE PANEL LANGUAGE (Michael 2026-10-11: "The tuck panels are not working with the same design
 * language hierarchy" → plan → "Yes"). The twin of iOS PanelSeatsTests: the same assertions over
 * the same table, and both hold the rendered table equal to the byte-identical golden file.
 */
class PanelSeatsTest {

    /** Every (dress, platform, flags) the golden table pins, where that app has the dress. */
    private val cases: List<Triple<Dress, Platform, PanelSeats.Layout>> =
        PanelSeats.GOLDEN_CASES.flatMap { (dress, _, flags) ->
            Platform.entries.mapNotNull { p -> PanelSeats.layout(dress, p, flags)?.let { Triple(dress, p, it) } }
        }

    @Test
    fun `1 - the frame is Today, the door, More on every dress - Hub only outside a list`() {
        // Michael 2026-10-12: "Those too." — Hub and Feeds off every panel. The door is the Nib
        // on what you read; the Ledger's own pages have none (their Feeds door left with Hub).
        for ((dress, p, l) in cases) {
            val outside = l.frame.firstOrNull() == Seat.HUB
            val expected = listOfNotNull(
                Seat.HUB.takeIf { outside },
                Seat.FEEDS.takeIf { outside && dress == Dress.ARTICLE },
                Seat.TODAY, Seat.NIB.takeIf { !dress.ledger }, Seat.MORE)
            assertEquals("$dress/$p", expected, l.frame)
            assertEquals("$dress/$p More closes the frame", Seat.MORE, l.frame.last())
        }
    }

    @Test
    fun `1b - Hub and Feeds are off every panel on both apps, the ladder runs on both`() {
        // Michael 2026-10-12: "Those too." → "Make sure we can get to hub with back swipe in the
        // reader or touch or something as needed" → "Awesome".
        assertEquals(setOf(Platform.ANDROID, Platform.IOS), PanelSeats.EDGE_LADDER)
        assertEquals(setOf(Dress.FEED_LIST, Dress.ARTICLE, Dress.MAIL_LIST, Dress.LETTER),
            Dress.entries.filter { it.roomPane }.toSet())
        for ((dress, label, flags) in PanelSeats.GOLDEN_CASES) for (p in Platform.entries) {
            val l = PanelSeats.layout(dress, p, flags) ?: continue
            if (flags.outsideList) continue
            assertTrue("${dress.id}·$label/$p wears Hub", Seat.HUB !in l.all)
            assertTrue("${dress.id}·$label/$p wears Feeds", Seat.FEEDS !in l.all)
            // The room panes' seats left with them (the swipe's first rung; More's second row).
            assertTrue("${dress.id}·$label/$p wears Mail", Seat.MAIL !in l.all)
        }
        // Every seat that left has a named home on every dress either app has.
        for (dress in Dress.entries) for (p in Platform.entries) {
            if (PanelSeats.layout(dress, p) == null) continue
            assertEquals(Seat.HUB, PanelSeats.replacedByLadder(dress, p).first())
        }
        assertEquals(listOf(Seat.HUB, Seat.MAIL), PanelSeats.replacedByLadder(Dress.MAIL_LIST, Platform.ANDROID))
        assertEquals(listOf(Seat.HUB), PanelSeats.replacedByLadder(Dress.MAIL_LIST, Platform.IOS))
        assertEquals(listOf(Seat.HUB, Seat.FEEDS), PanelSeats.replacedByLadder(Dress.DAY, Platform.IOS))
        assertEquals(listOf(Seat.HUB), PanelSeats.replacedByLadder(Dress.BOOK, Platform.ANDROID))
    }

    @Test
    fun `1c - a reader outside its list keeps Hub, and the article its Feeds seat, on both apps`() {
        // iOS review 2026-10-12, Android parity: from a cover, a sheet, a gram's source, the left
        // edge goes back to whatever opened the piece — never to the list or its pane.
        val outside = Flags(outsideList = true)
        for (p in Platform.entries) {
            assertEquals(listOf(Seat.HUB, Seat.FEEDS, Seat.TODAY, Seat.NIB, Seat.MORE),
                PanelSeats.layout(Dress.ARTICLE, p, outside)!!.frame)
            assertEquals(listOf(Seat.HUB, Seat.TODAY, Seat.NIB, Seat.MORE),
                PanelSeats.layout(Dress.LETTER, p, outside)!!.frame)
            assertTrue(PanelSeats.replacedByLadder(Dress.ARTICLE, p, outside).isEmpty())
            // The flag means nothing on a dress that is no reader in a list.
            for (d in listOf(Dress.FEED_LIST, Dress.MAIL_LIST, Dress.BOOK, Dress.NET, Dress.SHELF, Dress.DAY))
                assertEquals("$d/$p", PanelSeats.layout(d, p), PanelSeats.layout(d, p, outside))
        }
        // And the room is the same in or out of the list.
        assertEquals(PanelSeats.layout(Dress.ARTICLE, Platform.ANDROID)!!.room,
            PanelSeats.layout(Dress.ARTICLE, Platform.ANDROID, outside)!!.room)
        assertTrue(PanelSeats.goldenTable().contains("\narticle·outside: full | hub feeds today nib more |"))
        assertTrue(PanelSeats.goldenTable().contains("\nletter·outside: full | hub today nib more |"))
    }

    @Test
    fun `2 - the foot is Side, or Close then Side, and Full is never in the body`() {
        for ((dress, p, l) in cases) {
            assertTrue("$dress/$p", l.foot == listOf(Seat.SIDE) || l.foot == listOf(Seat.CLOSE, Seat.SIDE))
            assertTrue("$dress/$p", Seat.FULL !in l.all)
            // Close is iOS's alone: Android leaves a screen by system back.
            if (p == Platform.ANDROID) assertTrue("$dress", Seat.CLOSE !in l.all)
        }
    }

    @Test
    fun `3 - ink dresses run the hand then the walk, reading dresses the turns then the room`() {
        for ((dress, p, l) in cases) when (dress) {
            Dress.DAY -> {
                assertTrue("$p", l.hand.all { it.kind == Kind.WRITE || it == Seat.SYNTH })
                assertTrue("$p", l.room.all { it.kind == Kind.WALK })
            }
            Dress.SECTION -> assertTrue(l.hand.all { it.kind == Kind.WALK })
            Dress.TYPED_PAGE -> assertTrue(l.room.all { it.kind == Kind.WALK })
            else -> {
                assertTrue("$dress/$p", l.hand.all { it.kind == Kind.TURN })
                assertTrue("$dress/$p turns in order", inOrder(l.hand, PanelSeats.TURN_ORDER))
            }
        }
    }

    @Test
    fun `4 - the room keeps its kind order, and each kind its own order`() {
        val readingDresses = Dress.entries.filter { it != Dress.DAY && it != Dress.SECTION && it != Dress.TYPED_PAGE }
        for ((dress, p, l) in cases) {
            if (dress !in readingDresses) continue
            val kinds = l.room.map { it.kind }
            val ranks = kinds.map { PanelSeats.ROOM_KINDS.indexOf(it) }
            assertTrue("$dress/$p room kinds $kinds", ranks.all { it >= 0 } && ranks == ranks.sorted())
            for (order in listOf(PanelSeats.INDEX_ORDER, PanelSeats.LIST_ORDER, PanelSeats.READING_ORDER,
                    PanelSeats.ANSWER_ORDER, PanelSeats.FILE_ORDER))
                assertTrue("$dress/$p ${l.room}", inOrder(l.room, order))
        }
    }

    @Test
    fun `5 - no seat twice on one dress`() {
        for ((dress, p, l) in cases) assertEquals("$dress/$p", l.all.size, l.all.toSet().size)
    }

    @Test
    fun `6 - nothing is lost on the Android dresses built so far`() {
        // The Net before (SiteWebFragment, -315): Where to? · Save for later · Marks & piles ·
        // Read aloud · Annotate · Bookmark · This page · Page up · Page down · Reload, with Hub ·
        // Other side · Tuck from the frame. Tuck became ⛶ (the head), the rest have seats.
        val netBefore = setOf(Seat.HUB, Seat.ADDRESS, Seat.LATER, Seat.PILES, Seat.LISTEN, Seat.ANNOTATE,
            Seat.BOOKMARK, Seat.MORE, Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.REFRESH, Seat.SIDE)
        val net = PanelSeats.layout(Dress.NET, Platform.ANDROID)!!.all.toSet() +
            PanelSeats.replacedByLadder(Dress.NET, Platform.ANDROID)
        assertTrue("net lost ${netBefore - net}", net.containsAll(netBefore))
        // The book reader before (ReaderFragment, -315): Hub · This book · ‹ · Highlight · › ·
        // Read aloud/Listen along · the wrench · Aa · Other side · Tuck.
        val bookBefore = setOf(Seat.HUB, Seat.BOOK, Seat.PAGE_BACK, Seat.MARK, Seat.PAGE_FORWARD,
            Seat.LISTEN, Seat.MORE, Seat.TYPE, Seat.SIDE)
        val book = PanelSeats.layout(Dress.BOOK, Platform.ANDROID)!!.all.toSet() +
            PanelSeats.replacedByLadder(Dress.BOOK, Platform.ANDROID)
        assertTrue("book lost ${bookBefore - book}", book.containsAll(bookBefore))
        // …and both gained the frame's Today and Nib, the book its Notes and Find (two taps → one).
        assertTrue(net.containsAll(listOf(Seat.TODAY, Seat.NIB)))
        assertTrue(book.containsAll(listOf(Seat.TODAY, Seat.NIB, Seat.NOTES, Seat.FIND)))

        // STEP 2 (Michael 2026-10-11): the Feeds list, the article and the Bookshelf.
        // The Feeds list before (IncomingRail, RSS, -315): Hub | Feeds · Today · Page ↑ · Page ↓ ·
        // Previous · Next · Refresh · More · Search · Show · Other side · Tuck.
        val feedsBefore = setOf(Seat.HUB, Seat.FEEDS, Seat.TODAY, Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREVIOUS,
            Seat.NEXT, Seat.REFRESH, Seat.MORE, Seat.SEARCH, Seat.SHOW, Seat.SIDE)
        assertNothingLost(Dress.FEED_LIST, feedsBefore)
        // The article before: the same shared seats, then Star · Note · Find · Reader · Type ·
        // Listen · Later · Synth. Refresh while reading lives in More now (rare; named).
        val articleBefore = setOf(Seat.HUB, Seat.FEEDS, Seat.TODAY, Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREVIOUS,
            Seat.NEXT, Seat.REFRESH, Seat.MORE, Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.TYPE,
            Seat.LISTEN, Seat.LATER, Seat.SYNTH, Seat.SIDE)
        assertNothingLost(Dress.ARTICLE, articleBefore)
        assertEquals(listOf(Seat.REFRESH), PanelSeats.movedToMore(Dress.ARTICLE, Platform.ANDROID))
        // The shelf before (BookshelfFragment, -315): Hub | Continue · Folders · Add a book · the
        // wrench · Today · Other side · Tuck.
        val shelfBefore = setOf(Seat.HUB, Seat.CONTINUE, Seat.FOLDERS, Seat.ADD, Seat.MORE, Seat.TODAY, Seat.SIDE)
        assertNothingLost(Dress.SHELF, shelfBefore)
        // Gains: the Nib on all three, Synth on the list, Search and Page ↑/↓ on the shelf (Page
        // ↑/↓ were only on its hidden pill).
        assertTrue(PanelSeats.layout(Dress.FEED_LIST, Platform.ANDROID)!!.all.containsAll(listOf(Seat.NIB, Seat.SYNTH)))
        assertTrue(PanelSeats.layout(Dress.SHELF, Platform.ANDROID)!!.all
            .containsAll(listOf(Seat.NIB, Seat.SEARCH, Seat.PAGE_UP, Seat.PAGE_DOWN)))
    }

    /** [dress]'s Android column, plus what it moved to More and what More's Hub row and the left
     *  edge's ladder have now ([PanelSeats.replacedByLadder]), covers every seat of [before]. */
    private fun assertNothingLost(dress: Dress, before: Set<Seat>) {
        val after = PanelSeats.layout(dress, Platform.ANDROID)!!.all.toSet() +
            PanelSeats.movedToMore(dress, Platform.ANDROID) + PanelSeats.replacedByLadder(dress, Platform.ANDROID)
        assertTrue("${dress.id} lost ${before - after}", after.containsAll(before))
    }

    @Test
    fun `6b - Incoming keeps every seat IncomingRail wears today`() {
        // The P3 build moves IncomingRail onto this table; the table already carries every seat
        // (its room-drawer seat — FEEDS, "Mail" in The Mail — left with Hub on 2026-10-12).
        val incoming = setOf(Seat.TODAY, Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREVIOUS, Seat.NEXT,
            Seat.REFRESH, Seat.MORE, Seat.SEARCH, Seat.SHOW)
        assertTrue(PanelSeats.layout(Dress.FEED_LIST, Platform.ANDROID)!!.all.containsAll(incoming))
        val letter = PanelSeats.layout(Dress.LETTER, Platform.ANDROID)!!.all
        assertTrue(letter.containsAll(listOf(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READ_AS, Seat.LISTEN,
            Seat.REPLY, Seat.TAG, Seat.PICK, Seat.TO_SYNTH, Seat.THREAD, Seat.LINKS, Seat.RHIZOME, Seat.DELETE)))
        val trash = PanelSeats.layout(Dress.LETTER, Platform.ANDROID, Flags(trash = true))!!.all
        assertTrue(Seat.RESTORE in trash && Seat.DELETE !in trash)
        assertTrue(Seat.DELETE !in PanelSeats.layout(Dress.LETTER, Platform.ANDROID, Flags(junk = true))!!.all)
        val mail = PanelSeats.layout(Dress.MAIL_LIST, Platform.ANDROID, Flags(canSweep = true))!!.all
        assertTrue(mail.containsAll(listOf(Seat.SWEEP, Seat.COMPOSE, Seat.ACCOUNTS)))
        assertEquals(listOf(Seat.HUB, Seat.MAIL), PanelSeats.replacedByLadder(Dress.MAIL_LIST, Platform.ANDROID))
        val article = PanelSeats.layout(Dress.ARTICLE, Platform.ANDROID)!!.all
        assertTrue(article.indexOf(Seat.SYNTH) < article.indexOf(Seat.LATER))
        // No Refresh while reading — More (seat 4) holds the slot.
        assertTrue(Seat.REFRESH !in article && Seat.REFRESH !in letter)
    }

    @Test
    fun `7 - captions are short and each names one meaning`() {
        for (s in Seat.entries) {
            // "Move back" and "Annotations" are Michael's words on both apps — the two longer.
            if (s != Seat.RESTORE && s != Seat.NOTES) assertTrue("${s.id} '${s.caption}'", s.caption.length <= 8)
            assertTrue(s.caption.isNotBlank())
        }
        // The notes door is "Notes", caption and spoken name, wherever it stands (Michael 2026-10-12,
        // iPhone 247: "In the reader the nib should be called notes"); the book's own notes pane,
        // which wore "Notes" beside it on the book reader, is "Annotations", caption and spoken name
        // (Michael 2026-10-12: "Instead of margin, those can be annotations") — iOS's word too.
        assertEquals("Notes", Seat.NIB.caption)
        assertEquals("Notes", Seat.NIB.voice)
        assertEquals("nib", Seat.NIB.id)
        assertEquals("Annotations", Seat.NOTES.caption)
        assertEquals("Annotations", Seat.NOTES.voice)
        assertEquals("bookNotes", Seat.NOTES.id)
        assertTrue(Seat.entries.none { it.caption == "Margin" })
        assertTrue(Seat.entries.none { it.caption == "Nib" || it.voice == "Nib" })
        // One caption, one seat — except the faces shared on purpose are still different words.
        val dup = Seat.entries.groupBy { it.caption }.filterValues { it.size > 1 }
        assertTrue("captions shared: $dup", dup.isEmpty())
        // Ids are unique and stable words.
        assertEquals(Seat.entries.size, Seat.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `8 - one pencil at most, and no two seats share a face on one Android dress`() {
        for ((dress, p, l) in cases) {
            assertTrue("$dress/$p", l.all.count { it in PanelSeats.PENCIL_FAMILY } <= 1)
            if (p != Platform.ANDROID) continue
            val faces = l.all.mapNotNull { PanelFaces.face(it) }
            assertEquals("$dress faces shared", faces.size, faces.toSet().size)
        }
    }

    @Test
    fun `8b - every Android face is monochrome, and the dresses built so far all have faces`() {
        for (s in Seat.entries) PanelFaces.face(s)?.glyph?.let {
            assertTrue("${s.id} wears '$it'", it in PanelSeats.MONO_GLYPHS)
        }
        for (dress in listOf(Dress.NET, Dress.BOOK, Dress.FEED_LIST, Dress.ARTICLE, Dress.SHELF))
            for (s in PanelSeats.layout(dress, Platform.ANDROID)!!.all + Seat.FULL)
                assertNotNull("${dress.id}/${s.id} has no face", PanelFaces.face(s))
        // The faces still to draw, named so the phase that needs them finds them (the Mail's step,
        // P4) — or iOS's alone.
        val pending = Seat.entries.filter { PanelFaces.face(it) == null }.map { it.id }.toSet()
        assertEquals(setOf("moveBack", "toSynth", "thread", "rhizome", "sweep", "readAs",
            "input", "yourDay", "byHand", "order", "lineBack", "lineForward", "backToPage"), pending)
    }

    @Test
    fun `8c - one face family - every drawable face is an ic_pf outline or an ink tool's own`() {
        // Review 2026-10-11, F5 (Michael: "Icons lack design language"): solid Material shapes beside
        // thin outlines read as two languages. Every face is drawn on the ink tools' geometry — the
        // 256 grid, 16-unit round strokes — and only an ON face may fill (Star's fill).
        val names = com.toolsboox.R.drawable::class.java.fields.associate { it.getInt(null) to it.name }
        val ink = setOf("ic_fullscreen", "ic_toolbar_pen", "ic_toolbar_eraser", "ic_toolbar_lasso",
            "ic_toolbar_undo", "ic_toolbar_redo", "ic_toolbar_trash", "ic_toolbar_switch_side")
        fun res(name: String) = listOf(java.io.File("src/main/res/drawable/$name.xml"),
            java.io.File("app/src/main/res/drawable/$name.xml")).firstOrNull { it.exists() }
        for (s in Seat.entries) {
            val id = PanelFaces.face(s)?.iconRes?.takeIf { it != 0 } ?: continue
            val name = names[id] ?: error("${s.id}: unknown drawable $id")
            assertTrue("${s.id} wears $name — not the panel family", name.startsWith("ic_pf_") || name in ink)
            if (!name.startsWith("ic_pf_")) continue
            val xml = res(name)?.readText() ?: continue   // run from another directory: names only
            assertTrue("$name is not on the 256 grid", xml.contains("android:viewportWidth=\"256\""))
            assertTrue("$name has no 16-unit stroke", xml.contains("android:strokeWidth=\"16\""))
            assertTrue("$name strokes without round caps", xml.contains("android:strokeLineCap=\"round\""))
        }
    }

    @Test
    fun `8d - a value may stand as the face while Tool labels are off, an emoji never`() {
        // Review 2026-10-11, F6: the player's speed and time left are the face when there is no
        // caption to carry them.
        for (v in listOf("1.25×", "1×", "0.8×", "12m", "end", "90m")) assertTrue(v, PanelSeats.isMonoGlyph(v))
        for (g in PanelSeats.MONO_GLYPHS) assertTrue(g, PanelSeats.isMonoGlyph(g))
        for (e in listOf("🌙", "👁", "👁✓", "▶", "⏸", "🔖", "★", "1.25×🌙", "")) assertTrue(e, !PanelSeats.isMonoGlyph(e))
    }

    @Test
    fun `9 - the rendered table equals the golden file`() {
        val rendered = PanelSeats.goldenTable()
        runCatching { java.io.File("build/panel-seats.actual").writeText(rendered) }
        val golden = javaClass.getResourceAsStream("/panel-seats.golden")?.bufferedReader()?.readText()
        assertNotNull("panel-seats.golden missing from test resources", golden)
        assertEquals(golden, rendered)
    }

    @Test
    fun `the doors - none on the pages you write, Nib on what you read`() {
        // The day page's Feeds door left with Hub (Michael 2026-10-12: "Those too.").
        for (d in listOf(Dress.DAY, Dress.SECTION, Dress.TYPED_PAGE)) assertNull(PanelSeats.door(d))
        for (d in listOf(Dress.NET, Dress.BOOK, Dress.SHELF, Dress.ARTICLE, Dress.LETTER, Dress.FEED_LIST))
            assertEquals(Seat.NIB, PanelSeats.door(d))
    }

    @Test
    fun `the Net as Michael will see it - Side last, the side setting's seat`() {
        val net = PanelSeats.layout(Dress.NET, Platform.ANDROID)!!
        assertEquals(listOf(Seat.TODAY, Seat.NIB, Seat.MORE), net.frame)
        assertEquals(listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.REFRESH), net.hand)
        assertEquals(listOf(Seat.ADDRESS, Seat.PILES, Seat.BOOKMARK, Seat.LISTEN, Seat.ANNOTATE, Seat.LATER), net.room)
        assertEquals(listOf(Seat.SIDE), net.foot)
        // iOS: its own ← Back in the hand, and Close before Side.
        val ios = PanelSeats.layout(Dress.NET, Platform.IOS)!!
        assertEquals(listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.BACK, Seat.REFRESH), ios.hand)
        assertEquals(listOf(Seat.CLOSE, Seat.SIDE), ios.foot)
    }

    @Test
    fun `the Feeds list as Michael will see it - the drawer on the left edge, Side last`() {
        val list = PanelSeats.layout(Dress.FEED_LIST, Platform.ANDROID)!!
        assertEquals(listOf(Seat.TODAY, Seat.NIB, Seat.MORE), list.frame)
        assertEquals(listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREVIOUS, Seat.NEXT, Seat.REFRESH), list.hand)
        assertEquals(listOf(Seat.SEARCH, Seat.SHOW, Seat.SYNTH), list.room)
        assertEquals(listOf(Seat.SIDE), list.foot)
        // Show only where the view can cycle; Order is iOS's (Android's store doesn't sort yet).
        assertEquals(listOf(Seat.SEARCH, Seat.SYNTH),
            PanelSeats.layout(Dress.FEED_LIST, Platform.ANDROID, Flags(showScope = false))!!.room)
        assertEquals(listOf(Seat.SEARCH, Seat.SHOW, Seat.ORDER, Seat.SYNTH),
            PanelSeats.layout(Dress.FEED_LIST, Platform.IOS)!!.room)
    }

    @Test
    fun `the article - no Refresh while reading, Synth before Later`() {
        val a = PanelSeats.layout(Dress.ARTICLE, Platform.ANDROID)!!
        assertEquals(listOf(Seat.TODAY, Seat.NIB, Seat.MORE), a.frame)
        assertEquals(listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREVIOUS, Seat.NEXT), a.hand)
        assertEquals(listOf(Seat.NOTES, Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.TYPE, Seat.LISTEN,
            Seat.SYNTH, Seat.LATER), a.room)
        assertEquals(listOf(Seat.SIDE), a.foot)
        // iOS also inks the piece (Annotate) and skips sentences while it reads aloud.
        assertEquals(listOf(Seat.NOTES, Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.TYPE, Seat.LISTEN,
            Seat.LINE_BACK, Seat.LINE_FORWARD, Seat.ANNOTATE, Seat.SYNTH, Seat.LATER),
            PanelSeats.layout(Dress.ARTICLE, Platform.IOS, Flags(readingAloud = true))!!.room)
    }

    @Test
    fun `the article's Annotations seat pushes nothing into More on either app`() {
        // Design critique #15 (Michael 2026-10-12, the approved highlights+notes plan): the new
        // seat leads the room on both apps, and every seat the article wore is still a seat — More
        // holds what it held (Android: Refresh while reading; iOS: nothing). The column scrolls
        // when it outruns the screen (captions stay, nothing folds — the one panel language).
        for (p in listOf(Platform.ANDROID, Platform.IOS)) for (f in listOf(Flags(), Flags(readingAloud = true), Flags(outsideList = true))) {
            val l = PanelSeats.layout(Dress.ARTICLE, p, f)!!
            assertEquals("$p $f", Seat.NOTES, l.room.first())
            val without = l.room - Seat.NOTES
            assertTrue("$p $f", without.containsAll(listOf(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.TYPE, Seat.LISTEN, Seat.SYNTH, Seat.LATER)))
        }
        assertEquals(listOf(Seat.REFRESH), PanelSeats.movedToMore(Dress.ARTICLE, Platform.ANDROID))
        assertEquals(emptyList<Seat>(), PanelSeats.movedToMore(Dress.ARTICLE, Platform.IOS))
        // A letter keeps its column: Annotations are the article's (letters are a later step).
        assertFalse(PanelSeats.layout(Dress.LETTER, Platform.ANDROID)!!.all.contains(Seat.NOTES))
    }

    @Test
    fun `the shelf`() {
        val shelf = PanelSeats.layout(Dress.SHELF, Platform.ANDROID)!!
        assertEquals(listOf(Seat.TODAY, Seat.NIB, Seat.MORE), shelf.frame)
        assertEquals(listOf(Seat.PAGE_UP, Seat.PAGE_DOWN), shelf.hand)
        assertEquals(listOf(Seat.FOLDERS, Seat.SEARCH, Seat.ADD, Seat.CONTINUE), shelf.room)
        assertEquals(listOf(Seat.SIDE), shelf.foot)
        assertEquals(listOf(Seat.CLOSE, Seat.SIDE), PanelSeats.layout(Dress.SHELF, Platform.IOS)!!.foot)
    }

    @Test
    fun `the book reader`() {
        val book = PanelSeats.layout(Dress.BOOK, Platform.ANDROID)!!
        assertEquals(listOf(Seat.PAGE_BACK, Seat.PAGE_FORWARD), book.hand)
        assertEquals(listOf(Seat.BOOK, Seat.NOTES, Seat.FIND, Seat.TYPE, Seat.LISTEN, Seat.MARK), book.room)
        // Annotate is iOS's until "Ink on books" lands on Android.
        assertEquals(Seat.ANNOTATE, PanelSeats.layout(Dress.BOOK, Platform.IOS)!!.room.last())
    }

    @Test
    fun `dresses one app lacks are null there, and tagged in the table`() {
        assertNull(PanelSeats.layout(Dress.SECTION, Platform.ANDROID))
        assertNull(PanelSeats.layout(Dress.LISTEN, Platform.ANDROID))
        assertTrue(PanelSeats.goldenLine(Dress.LISTEN, "", Flags()).startsWith("ios:listen: full | today nib more |"))
        // A shared dress tags its one-app seats where they stand.
        assertEquals("shelf: full | today nib more | pageUp pageDown | folders search add continueReading | ios:close side",
            PanelSeats.goldenLine(Dress.SHELF, "", Flags()))
    }

    @Test
    fun `the merge tags one-app seats where they stand`() {
        assertEquals(listOf("pageUp", "pageDown", "ios:back", "refresh"),
            PanelSeats.tagged(listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.REFRESH),
                listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.BACK, Seat.REFRESH)))
        assertEquals(listOf("and:feeds", "search", "ios:order", "synth"),
            PanelSeats.tagged(listOf(Seat.FEEDS, Seat.SEARCH, Seat.SYNTH), listOf(Seat.SEARCH, Seat.ORDER, Seat.SYNTH)))
        // Where both apps wear a seat, they wear it in the same order (the merge relies on it).
        for ((dress, _, flags) in PanelSeats.GOLDEN_CASES) {
            val a = PanelSeats.layout(dress, Platform.ANDROID, flags) ?: continue
            val i = PanelSeats.layout(dress, Platform.IOS, flags) ?: continue
            for (b in 0 until 4) {
                val shared = a.blocks[b].filter { it in i.blocks[b] }
                assertEquals("$dress block $b", shared, i.blocks[b].filter { it in a.blocks[b] })
            }
        }
    }

    /** [seats] keep [order]'s relative order for the seats they share with it. */
    private fun inOrder(seats: List<Seat>, order: List<Seat>): Boolean {
        val ranks = seats.filter { it in order }.map { order.indexOf(it) }
        return ranks == ranks.sorted()
    }
}
