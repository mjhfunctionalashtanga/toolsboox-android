package com.toolsboox

import com.toolsboox.da.ImageElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes
import com.toolsboox.plugin.calendar.ot.SynthLanding
import com.toolsboox.plugin.calendar.ot.SynthListLanding
import com.toolsboox.plugin.calendar.ot.SynthSources
import com.toolsboox.plugin.calendar.ot.SynthVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * ❖ SYNTHESIZE THIS LIST → THE PAGE, ITS PICK AND ITS ONE LOG ROW (synth-list-links §4, §5).
 *
 * Michael, 2026-10-12: "it can create that page as a new synth page, and add a pick to all picks".
 * Pins the pure half the run writes through — one placement for the live page and the day file,
 * idempotent so a reply that lands again after a kill doubles nothing, the pick where Gram Picks
 * puts any arrival, and the Log folding a synthesis into one row (iOS SynthListLog.fold's rule).
 */
class SynthListLandingTest {

    private val day0 = LocalDate.of(2026, 10, 12)
    private val key = SynthSources.pageKey(1760290000000)
    private val home = SynthSources.pageLink(day0.toString(), key)

    private fun day(d: LocalDate = day0) = CalendarDay(year = d.year, month = d.monthValue, day = d.dayOfMonth,
        locale = Locale.US, startHour = 7)

    private fun card(label: String, text: String, aspect: Float = 0.5f, link: String = home) =
        SynthListLanding.Card("iVBORw0K", aspect, SynthLanding.CARD_W, label, link, text)

    private fun plan(cards: List<SynthListLanding.Card>, chunks: List<String>, pickDate: LocalDate? = day0) =
        SynthListLanding.Plan(day0, key, cards, chunks, pickDate?.let {
            SynthListLanding.Pick(it, "iVBORw0K", 760, 380, home, "❖ Themes · Starred", "❖ The reply")
        })

    @Test
    fun `the page's cards stack from the top in order and flow onto the next sub-page`() {
        val d = day()
        val cards = (1..6).map { card(if (it == 1) SynthSources.REPLY_LABEL else "[${it - 1}] T$it", "c$it", aspect = 0.5f) }
        val landed = SynthListLanding.place(d, key, cards)
        val mine = d.imageElements
        assertEquals(6, mine.size)
        assertEquals(SynthLanding.TOP, mine[0].y, 0f)
        assertEquals(SynthLanding.TOP + 280f + SynthLanding.GAP, mine[1].y, 0f)
        // 280-tall cards: five fit (the fifth ends at 1636), the sixth (1660 + 280 > 1832) flows to "#1".
        assertEquals(key, mine[4].page)
        assertEquals("$key#1", mine[5].page)
        assertEquals(SynthLanding.TOP, mine[5].y, 0f)
        assertEquals(key, landed.firstKey)
        assertEquals("$key#1", landed.lastKey)
        assertEquals(6, landed.cards)
        assertTrue(mine.all { it.x == SynthLanding.LEFT && it.width == SynthLanding.CARD_W })
        assertEquals("[1] T2", mine[1].sourceLabel)
    }

    @Test
    fun `on a fresh page the Sources card takes the right-hand column's top - the reply flows on the left`() {
        val d = day()
        // Three 1000-tall reply chunks (two pages' worth on the left), a cited card, and a Sources card.
        val cards = listOf(
            card(SynthSources.REPLY_LABEL, "one", aspect = 1000f / 560f),
            card(SynthSources.REPLY_LABEL, "two", aspect = 1000f / 560f),
            card(SynthSources.REPLY_LABEL, "three", aspect = 1000f / 560f),
            card("[2] Breath first", "[2] Breath first", aspect = 0.5f, link = "https://example.com/breath"),
            card("Sources · Starred", "[1] A <https://a.example/>", aspect = 1500f / 560f))
        val landed = SynthListLanding.place(d, key, cards)
        val sources = d.imageElements.single { it.sourceLabel == "Sources · Starred" }
        assertEquals(SynthListLanding.RIGHT, sources.x, 0f)
        assertEquals(SynthListLanding.RIGHT_TOP, sources.y, 0f)
        assertEquals(key, sources.page)   // page one, whatever the reply's length
        assertTrue(SynthListLanding.RIGHT + sources.width <= 1404f)
        assertTrue(SynthLanding.LEFT + SynthLanding.CARD_W < SynthListLanding.RIGHT)
        // The left column starts at the top beside it, and flows onto the run's "#1", "#2" (iOS
        // SynthListLandingTests pins the same pages and the same y).
        val left = d.imageElements.filter { it.sourceLabel != "Sources · Starred" }
        assertTrue(left.all { it.x == SynthLanding.LEFT })
        assertEquals(SynthLanding.TOP, left[0].y, 0f)
        assertEquals(listOf(key, "$key#1", "$key#2", "$key#2"), left.map { it.page })
        assertEquals(SynthLanding.TOP + 1000f + SynthLanding.GAP, left[3].y, 0.01f)
        // Added in the order given; the reveal still goes to the reply's first card.
        assertEquals(cards.map { it.label }, d.imageElements.map { it.sourceLabel })
        assertEquals(key, landed.firstKey)
        assertEquals(SynthLanding.LEFT, landed.x, 0f)
        assertEquals(5, landed.cards)
    }

    @Test
    fun `the Sources card stands clear of the voice header on both apps - the reply's column keeps the top`() {
        // The header sits at the page's top: Android's top margin (above the frame rule, y 61);
        // iOS's Synthesize title bar, right-aligned to x 1288 from x 700 over y 112–176, its tap
        // rect padded to 188. The right-hand column starts below both; the reply's column (90–650)
        // is left of iOS's header and below Android's, so it keeps TOP 140 (merge 2026-10-16).
        val iosHeaderLeft = 700f - 16f
        val iosHeaderBottom = 176f + 12f
        assertTrue(SynthListLanding.RIGHT_TOP >= iosHeaderBottom)
        assertTrue(SynthLanding.LEFT + SynthLanding.CARD_W < iosHeaderLeft)
        assertTrue(SynthLanding.TOP > 61f)
        val d = day()
        SynthListLanding.place(d, key, listOf(card(SynthSources.REPLY_LABEL, "one"),
            card("Sources · Starred", "[1] A <https://a.example/>", aspect = 1500f / 560f)))
        val (reply, sources) = d.imageElements
        assertEquals(SynthLanding.TOP, reply.y, 0f)
        assertEquals(SynthListLanding.RIGHT_TOP, sources.y, 0f)
        assertTrue(sources.y + sources.height <= SynthLanding.FOOT)
        // Too tall for the column below the header: it flows in the one column instead.
        val d2 = day()
        SynthListLanding.place(d2, key, listOf(card("Sources · Starred", "x", aspect = 1650f / 560f)))
        assertEquals(SynthLanding.LEFT, d2.imageElements.single().x, 0f)
    }

    @Test
    fun `a follow-up in a persona's voice is a reply on its run - its numbers offer rows, a breadcrumb's never`() {
        val run = com.toolsboox.plugin.calendar.ui.SynthListRun
        val d = day()
        fun el(label: String, link: String, text: String, page: String = key) =
            ImageElement(x = 90f, y = 140f, width = 560f, height = 300f, data = "", page = page,
                sourceLink = link, sourceLabel = label, cardText = text)
        d.imageElements.add(el("Sources · Starred", home, "[1] A — F <https://a.example/> #41\n[2] B <https://b.example/>"))
        // Spiral answered the follow-up on page two of the run, citing [2]: a reply (SynthVoice.isReply),
        // though its label alone reads like any "❖ <page>" card.
        val spiral = el(SynthVoice.label(com.toolsboox.plugin.chat.nw.PersonaStore.SPIRAL), home, "It came back to [2].", page = "$key#1")
        assertEquals(SynthSources.Role.Other, SynthSources.role(spiral.sourceLabel))
        assertEquals(SynthSources.Role.Reply, SynthSources.role(spiral.sourceLabel, spiral.sourceLink, spiral.page))
        assertEquals(listOf(2), run.rowsFor(d, "$key#1", spiral).map { it.source.n })
        // The Correspondent's card reads as it always did.
        val corr = el(SynthSources.REPLY_LABEL, home, "As [1] says.")
        assertEquals(listOf(1), run.rowsFor(d, key, corr).map { it.source.n })
        // A "❖ <page>" breadcrumb points away from the page it stands on: no rows, whatever it cites.
        val crumb = el("❖ Themes · Elsewhere", SynthSources.pageLink(day0.toString(), SynthSources.pageKey(1760290000009)), "See [1].")
        assertEquals(SynthSources.Role.Other, SynthSources.role(crumb.sourceLabel, crumb.sourceLink, crumb.page))
        assertTrue(run.rowsFor(d, key, crumb).isEmpty())
        // The run's own pick on All Picks is no reply either (the slinks ❖ pick case of the voice fixture).
        val pick = el("❖ Themes · Starred", home, "❖ The reply [1]", page = CalendarDayPageNotes.GRAM_PICKS)
        assertEquals(SynthSources.Role.Other, SynthSources.role(pick.sourceLabel, pick.sourceLink, pick.page))
    }

    @Test
    fun `a page's reply stickies wear their voice's label - and a landing again finds them by it`() {
        val spiral = SynthVoice.label(com.toolsboox.plugin.chat.nw.PersonaStore.SPIRAL)
        val p = SynthListLanding.Plan(day0, key, listOf(card(spiral, "Chunk one"), card("Sources · Starred", "[1] A <https://a.example/>")),
            listOf("Chunk one"), null, replyLabel = spiral)
        val d = day()
        val first = SynthListLanding.mint(d, day0, p)
        assertEquals(listOf(spiral, "Sources · Starred"), first.elements.map { it.sourceLabel })
        d.imageElements.addAll(first.elements)
        assertTrue(SynthListLanding.mint(d, day0, p).elements.isEmpty())
        // A plan with no voice is the Correspondent's — the label every list page is born with.
        assertEquals(SynthSources.REPLY_LABEL, SynthListLanding.Plan(day0, key, emptyList(), emptyList(), null).replyLabel)
    }

    @Test
    fun `a page that already holds something keeps the one column - the Sources card flows last`() {
        val d = day()
        d.imageElements.add(ImageElement(x = 90f, y = 140f, width = 560f, height = 200f, data = "", page = key))
        SynthListLanding.place(d, key, listOf(card(SynthSources.REPLY_LABEL, "one"),
            card("Sources · Starred", "[1] A <https://a.example/>")))
        val sources = d.imageElements.single { it.sourceLabel == "Sources · Starred" }
        assertEquals(SynthLanding.LEFT, sources.x, 0f)
        assertTrue(sources.y > 340f)
        // A Sources card taller than the column never goes up the right; it flows.
        val d2 = day()
        SynthListLanding.place(d2, key, listOf(card("Sources · Starred", "x", aspect = 1800f / 560f)))
        assertEquals(SynthLanding.LEFT, d2.imageElements.single().x, 0f)
    }

    @Test
    fun `a follow-up flows past the sub-pages the run already has - and mints without touching the day`() {
        // iOS testAFollowUpFlowsPastTheSubPagesTheRunAlreadyHas: the summoned page full, "#1" taken
        // by the reply's flow → the new source cards go to the run's next UNUSED sub-page, "#2".
        val d = day()
        d.imageElements.add(ImageElement(x = 90f, y = 140f, width = 560f, height = 1600f, data = "", page = key))
        d.imageElements.add(ImageElement(x = 90f, y = 140f, width = 560f, height = 300f, data = "", page = "$key#1"))
        // SynthListRun.followUpCards' plan: cards only, no reply chunks, no pick.
        val follow = SynthListLanding.Plan(day0, key, listOf(card("[3] C", "[3] C"), card("[4] D", "[4] D")), emptyList(), null)
        val minted = SynthListLanding.mint(d, day0, follow)
        assertEquals(listOf("$key#2", "$key#2"), minted.elements.map { it.page })
        assertEquals(listOf(SynthLanding.TOP, SynthLanding.TOP + 280f + SynthLanding.GAP), minted.elements.map { it.y })
        assertEquals("$key#2", minted.landed?.lastKey)
        assertEquals(2, d.imageElements.size)
    }

    @Test
    fun `a source card's own link wins - carried onto another run it opens its own article, the id only when vouched`() {
        val run = com.toolsboox.plugin.calendar.ui.SynthListRun
        val d = day()
        fun el(label: String, link: String, text: String, page: String = key) =
            ImageElement(x = 90f, y = 140f, width = 560f, height = 300f, data = "", page = page,
                sourceLink = link, sourceLabel = label, cardText = text)
        d.imageElements.add(el("Sources · Starred", home, "[3] Run's three — F <https://run.example/3> #77"))
        // The run's own [3]: the run's line, its id.
        val mine = run.rowsFor(d, key, el("[3] Run's three", "https://run.example/3", "[3] Run's three — F"))
        assertEquals(listOf(SynthSources.Source(3, "Run's three — F", "https://run.example/3", 77)), mine.map { it.source })
        // A [3] carried in from another synthesis: ITS article and words, no id — tap and hold alike.
        val carried = run.rowsFor(d, key, el("[3] Elsewhere", "https://elsewhere.example/", "[3] Elsewhere — G\nA blurb"))
        assertEquals(listOf(SynthSources.Source(3, "Elsewhere — G", "https://elsewhere.example/", 0)), carried.map { it.source })
        assertEquals("↗ [3] Elsewhere — G", carried.single().label)
        // A run whose Sources card is gone: the card still opens its own link, its words from the label.
        val bare = SynthSources.pageKey(1760290000002)
        assertEquals(listOf(SynthSources.Source(5, "Five", "https://five.example/", 0)),
            run.rowsFor(d, bare, el("[5] Five", "https://five.example/", "", page = bare)).map { it.source })
        // Off a Synthesize page, nothing.
        assertTrue(run.rowsFor(d, "notes", el("[3] Run's three", "https://run.example/3", "")).isEmpty())
    }

    @Test
    fun `a run flowed onto its sub-pages - every page of it reads the one Sources card, the Log folds it into one row`() {
        // Both apps flow a full Synthesize page onto "#n" (merge 2026-10-16: iOS's "<name> · page N"
        // continuation documents are gone), so the run's numbers live on the run and nowhere else.
        val d = day()
        val lines = "[1] A — F <https://a.example/> #41\n[2] B <https://b.example/>"
        fun el(page: String, label: String, link: String, text: String) =
            ImageElement(x = 90f, y = 140f, width = 560f, height = 300f, data = "", page = page,
                sourceLink = link, sourceLabel = label, cardText = text)
        val reply = el(key, SynthSources.REPLY_LABEL, home, "As [1] says.")
        val cont = el("$key#1", SynthSources.REPLY_LABEL, home, "And [2].")
        val card2 = el("$key#2", "[2] B", "https://b.example/", "[2] B")
        d.imageElements.addAll(listOf(reply, cont, el("$key#2", "Sources · Starred", home, lines), card2))
        val run = com.toolsboox.plugin.calendar.ui.SynthListRun
        assertEquals(lines, run.sourcesOn(d, key))                // page one: on a later sub-page
        assertEquals(lines, run.sourcesOn(d, "$key#1"))           // a sub-page without it
        assertEquals(lines, run.sourcesOn(d, "$key#2"))           // the sub-page it stands on
        assertEquals(41L, run.entryIdFor(d, key, el(key, "[1] A", "https://a.example/", "[1] A — F")))
        assertEquals(0L, run.entryIdFor(d, key, el(key, "[1] A", "https://elsewhere.example/", "[1] A — F")))
        assertEquals("", run.sourcesOn(d, "notes"))
        // A page reads its own run's Sources card, never another conversation's — even a page of
        // the same day whose stickies (carried there) link this one.
        val other = SynthSources.pageKey(1760290009999)
        d.imageElements.add(el(other, "Sources · Feeds", SynthSources.pageLink(day0.toString(), other), "[1] Z <https://z.example/>"))
        assertEquals("[1] Z <https://z.example/>", run.sourcesOn(d, other))
        val bare = SynthSources.pageKey(1760290000001)
        d.imageElements.add(el(bare, SynthSources.REPLY_LABEL, home, "Carried [1]."))
        assertEquals("", run.sourcesOn(d, bare))
        // The Log: the run's every sub-page and the pick fold into page one's one row; another
        // document is its own row, whatever its cards link.
        val pick = SynthListLanding.pickElement(d, plan(emptyList(), emptyList()).pick!!)
        d.imageElements.add(pick)
        val fold = SynthListLanding.logFold(d.imageElements, day0)
        val row = fold.rows.single { it.key == key }
        assertEquals(5, row.cards)   // two stickies, the Sources card, the [2] card, the pick
        assertEquals("Themes · Starred", row.pickName)
        assertEquals(setOf(key, other, bare), fold.rows.map { it.key }.toSet())
        assertEquals(1, fold.rows.single { it.key == bare }.cards)
    }

    @Test
    fun `a landing done twice writes nothing the second time - page or pick`() {
        val d = day()
        val p = plan(listOf(card(SynthSources.REPLY_LABEL, "Chunk one"), card(SynthSources.REPLY_LABEL, "Chunk two"),
            card("Sources · Starred", "[1] A — F <https://a.example/>")), listOf("Chunk one", "Chunk two"))
        val first = SynthListLanding.mint(d, day0, p)
        assertEquals(4, first.elements.size)   // three cards + the pick
        assertTrue(first.pageDone && first.pickDone)
        assertTrue(d.imageElements.isEmpty())   // mint never touches the day it reads
        d.imageElements.addAll(first.elements)
        val again = SynthListLanding.mint(d, day0, p)
        assertTrue(again.elements.isEmpty())
        assertEquals(first.landed?.firstKey, again.landed?.firstKey)
        assertEquals(first.landed?.y ?: -1f, again.landed?.y ?: -2f, 0f)
    }

    @Test
    fun `the pick lands on Gram Picks where any arrival does - and a run across midnight writes two days`() {
        val d = day()
        repeat(4) { d.imageElements.add(ImageElement(x = 0f, y = 0f, width = 10f, height = 10f, data = "", page = CalendarDayPageNotes.GRAM_PICKS)) }
        val pick = SynthListLanding.pickElement(d, plan(emptyList(), emptyList()).pick!!)
        val w = 1404f * 0.29f
        assertEquals(CalendarDayPageNotes.GRAM_PICKS, pick.page)
        assertEquals(w, pick.width, 0.01f)
        assertEquals(60f + (4 % 3) * (w + 30f), pick.x, 0.01f)
        assertEquals(120f + (4 / 3) * (pick.height + 30f), pick.y, 0.01f)
        assertEquals(home, pick.sourceLink)
        assertEquals("❖ Themes · Starred", pick.sourceLabel)
        assertTrue(!pick.edgeBaked)

        val next = day0.plusDays(1)
        val p = plan(listOf(card(SynthSources.REPLY_LABEL, "x")), listOf("x"), pickDate = next)
        assertEquals(listOf(day0, next), SynthListLanding.days(p))
        val onPageDay = SynthListLanding.mint(day(), day0, p)
        assertTrue(onPageDay.elements.none { it.page == CalendarDayPageNotes.GRAM_PICKS })
        val onPickDay = SynthListLanding.mint(day(next), next, p)
        assertEquals(listOf(CalendarDayPageNotes.GRAM_PICKS), onPickDay.elements.map { it.page })
        assertNull(onPickDay.landed)
    }

    @Test
    fun `the Log folds a synthesis into one row that opens its page - every other card keeps its own`() {
        val d = day()
        val p = plan(listOf(card(SynthSources.REPLY_LABEL, "The reply [1]"), card("[1] A", "[1] A — F", link = "https://a.example/"),
            card("Sources · Starred", "[1] A — F <https://a.example/>")), listOf("The reply [1]"))
        d.imageElements.addAll(SynthListLanding.mint(d, day0, p).elements)
        d.imageElements.add(ImageElement(x = 0f, y = 0f, width = 10f, height = 10f, data = "", page = "notes",
            sourceLink = "https://b.example/", sourceLabel = "B"))
        val fold = SynthListLanding.logFold(d.imageElements, day0)
        assertEquals(1, fold.rows.size)
        val r = fold.rows[0]
        assertEquals(key, r.key)
        assertEquals(day0, r.date)
        assertEquals(4, r.cards)   // three on the page + the pick
        assertEquals("Themes · Starred", r.pickName)
        assertEquals("❖ The reply", r.text)
        assertEquals(listOf("notes"), fold.rest.map { it.page })

        // The pick on the NEXT day (a run across midnight) still opens the page on ITS day.
        val tomorrow = day(day0.plusDays(1))
        tomorrow.imageElements.add(SynthListLanding.pickElement(tomorrow, p.pick!!))
        val f2 = SynthListLanding.logFold(tomorrow.imageElements, day0.plusDays(1))
        assertEquals(day0, f2.rows.single().date)
    }

    @Test
    fun `a summon on a Sources page is cleaned before it is kept, and a list run's ready line carries its door`() {
        val run = com.toolsboox.plugin.calendar.ui.SynthListRun
        val base = com.toolsboox.plugin.calendar.ot.PendingSummon(id = "a", date = day0.toString(), pageKey = key,
            mode = "delta", startedAt = 1L, pageName = "Themes · Starred")
        val sources = "[1] A <https://a.example/>\n[2] B <https://b.example/>"
        // SynthSummons.askAndLand keeps — and lands — the cleaned reply: [7] goes, [2, 9] → [2].
        assertEquals("As [1] says. And [2] too.", run.cleanReply(base.copy(sources = sources), "As [1] says [7]. And [2, 9] too."))
        // No Sources card on the page, or the list run's own row (it cleans at its landing): untouched.
        assertEquals("As [7] says.", run.cleanReply(base, "As [7] says."))
        assertEquals("As [7] says.", run.cleanReply(base.copy(mode = "list", sources = sources), "As [7] says."))
        // The next foreground's one message carries [Go to it] only for a list run's "Ready" line.
        val ready = base.copy(mode = "list", stage = com.toolsboox.plugin.calendar.ot.SynthPending.NOTICE,
            notice = SynthSources.ready("Themes · Starred"))
        assertTrue(run.isReady(ready))
        assertTrue(!run.isReady(ready.copy(notice = SynthSources.killed("Themes · Starred"))))
        assertTrue(!run.isReady(ready.copy(mode = "delta")))
        assertTrue(!run.isReady(ready.copy(stage = com.toolsboox.plugin.calendar.ot.SynthPending.REPLIED)))
    }

    @Test
    fun `the Log's fold key - iOS SynthSources logFoldKey, case for case`() {
        assertTrue(SynthSources.isSynthesizeDocument("synthesize-1760290000000"))
        assertTrue(SynthSources.isSynthesizeDocument("synthesize-1760290000000#2"))
        assertTrue(!SynthSources.isSynthesizeDocument("synthesize"))
        assertTrue(!SynthSources.isSynthesizeDocument("synthesize-"))
        assertTrue(!SynthSources.isSynthesizeDocument("synthesize-abc"))
        assertTrue(!SynthSources.isSynthesizeDocument("write-1760290000000"))
        assertEquals("synthesize-17", SynthSources.logFoldKey("synthesize-17", "https://x.example/"))
        assertEquals("synthesize-17", SynthSources.logFoldKey("grampicks", "ledger://2026-10-12/synthesize-17"))
        assertEquals("synthesize-17", SynthSources.logFoldKey("grampicks", "ledger://2026-10-12/synthesize-17#e"))
        assertNull(SynthSources.logFoldKey("grampicks", "ledger://2026-10-12/synthesize"))
        assertNull(SynthSources.logFoldKey("synthesize", ""))
        assertNull(SynthSources.logFoldKey("0", "https://example.com"))
        assertNull(SynthSources.logFoldKey("grampicks", "ledger://feed?url=x"))
        assertEquals("ledger://2026-10-12/synthesize-17", SynthSources.pageLink("2026-10-12", "synthesize-17"))
        assertEquals("synthesize-17", SynthSources.pageKey(17))
    }
}
