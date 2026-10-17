package com.toolsboox

import com.toolsboox.plugin.calendar.ot.ArticlePickIndex
import com.toolsboox.plugin.calendar.ot.ArticlePickIndex.Hit
import com.toolsboox.plugin.feeds.ot.ArticleMarkSet
import com.toolsboox.plugin.reader.ui.BookNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * WHAT AN ARTICLE WEARS (Michael 2026-10-12: "gram picks should highlight back too"): its marks,
 * then its gram picks, then its old Log highlights — one highlight per passage — read at paint
 * time, no migration.
 */
class ArticleMarkSetTest {

    private val d1 = LocalDate.of(2026, 10, 1)
    private val d2 = LocalDate.of(2026, 10, 12)
    private val key = "a-0123456789abcdef"

    private fun pick(id: String, text: String, date: LocalDate = d1) = Hit(date, key, text, elementId = id, page = "grampicks")
    private fun line(id: String, text: String, date: LocalDate = d1, noted: Boolean = false) =
        Hit(date, key, text, lineId = id, noted = noted)
    private fun mark(id: String, text: String, pickId: String = "", logId: String = "") = BookNote(
        id = id, type = BookNote.ANNOTATION, text = text,
        cfi = ArticleMarkSet.anchorJson(text, "before ", " after"), pickId = pickId, logId = logId, day = d2.toString())

    @Test
    fun `a mark leads and claims its own pick and line - drawn once`() {
        val m = mark("mfhl-1-aaaa", "The passage.", pickId = "P1", logId = "mfhl-1-aaaa")
        val shown = ArticleMarkSet.assemble(listOf(m), listOf(
            pick("P1", "The passage."), line("mfhl-1-aaaa", "The passage.", noted = true)))
        assertEquals(1, shown.size)
        val s = shown.single()
        assertEquals("mfhl-1-aaaa", s.id)
        assertEquals("before ", s.prefix)
        assertTrue("its line carries a note", s.noted)
        assertEquals("P1", s.pick?.elementId)
    }

    @Test
    fun `old picks highlight back - one per passage, the newer standing for both, paired with its line`() {
        val shown = ArticleMarkSet.assemble(emptyList(), listOf(
            pick("P-old", "A  passage\nfrom the piece.", d1),
            pick("P-new", "A passage from the piece.", d2),
            line("art-7-xx", "A passage from the piece.", d2, noted = true),
            pick("P-other", "Another passage.", d1)))
        assertEquals(listOf("pick:P-new", "pick:P-other"), shown.map { it.id })
        assertEquals("art-7-xx", shown.first().line?.lineId)
        assertTrue(shown.first().noted)
        assertEquals(d2, shown.first().cardDay)
        assertEquals(d2, shown.first().lineDay)
    }

    @Test
    fun `an old pick and an old line a week apart - the note finds the line on ITS day, the card on its own`() {
        // An old Make pick on the 1st; an iOS ❝ line of the same passage on the 8th (no line on the
        // 1st to pair with). One highlight — and its line is NOT on the card's day.
        val d8 = LocalDate.of(2026, 10, 8)
        val s = ArticleMarkSet.assemble(emptyList(), listOf(
            pick("P1", "The passage.", d1), line("ios-9", "The passage.", d8))).single()
        assertEquals("pick:P1", s.id)
        assertEquals(d1, s.cardDay)
        assertEquals(d8, s.lineDay)
    }

    @Test
    fun `a mark's own days - iOS's logDay and pickDay first, the wire's day, then the day it was made`() {
        val made = java.time.ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val bare = BookNote(id = "b", type = BookNote.ANNOTATION, createdAt = made)
        assertEquals(LocalDate.of(2026, 10, 3), bare.lineDay)
        assertEquals(LocalDate.of(2026, 10, 3), bare.cardDay)
        val wire = bare.copy(day = d2.toString())
        assertEquals(d2, wire.lineDay); assertEquals(d2, wire.cardDay)
        val split = wire.copy(logDay = "2026-10-08", pickDay = d1.toString())
        assertEquals(LocalDate.of(2026, 10, 8), split.lineDay)
        assertEquals(d1, split.cardDay)
        // The index's own find wins over what the mark remembers (it is where the thing IS).
        val shown = ArticleMarkSet.Shown("m", "x", mark = split, pick = pick("P", "x", d2), line = line("L", "x", d2))
        assertEquals(d2, shown.cardDay); assertEquals(d2, shown.lineDay)
    }

    @Test
    fun `a card or line beside its own tombstone never highlights back`() {
        val day = com.toolsboox.plugin.calendar.da.v2.CalendarDay(2026, 10, 1, startHour = null)
        val live = java.util.UUID.randomUUID(); val dead = java.util.UUID.randomUUID()
        fun card(id: java.util.UUID) = com.toolsboox.da.ImageElement(
            elementId = id, x = 0f, y = 0f, width = 1f, height = 1f, data = "", page = "grampicks",
            sourceLink = "https://e.com/a", cardText = "Words $id")
        day.imageElements.add(card(live)); day.imageElements.add(card(dead))
        fun ev(id: String) = com.toolsboox.plugin.calendar.da.v2.ReadingEvent(id = id,
            kind = com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.ARTICLE, date = java.util.Date(),
            title = "T", url = "https://e.com/a", excerpt = "Line $id")
        day.readingEvents.add(ev("keep")); day.readingEvents.add(ev("gone"))
        day.deletedElementIds.add(dead.toString().uppercase()); day.deletedElementIds.add("gone")
        val hits = ArticlePickIndex.hitsOf(d1, day)
        assertEquals(listOf(live.toString()), hits.filter { it.elementId.isNotBlank() }.map { it.elementId })
        assertEquals(listOf("keep"), hits.filter { it.lineId.isNotBlank() }.map { it.lineId })
    }

    @Test
    fun `removing a card takes it off and tombstones it, held or not`() {
        val day = com.toolsboox.plugin.calendar.da.v2.CalendarDay(2026, 10, 1, startHour = null)
        val id = java.util.UUID.randomUUID()
        day.imageElements.add(com.toolsboox.da.ImageElement(
            elementId = id, x = 0f, y = 0f, width = 1f, height = 1f, data = "", page = "grampicks"))
        assertTrue(com.toolsboox.plugin.calendar.ot.MarkCard.dropFrom(day, id.toString().uppercase()))
        assertTrue(day.imageElements.isEmpty())
        assertEquals(listOf(id.toString()), day.deletedElementIds)
        // Not held here (it was on a neighbouring day): still tombstoned, once, and says so.
        assertFalse(com.toolsboox.plugin.calendar.ot.MarkCard.dropFrom(day, id.toString()))
        assertEquals(1, day.deletedElementIds.size)
        assertTrue(com.toolsboox.plugin.calendar.ot.MarkCard.Located(d1, "grampicks").inAllPicks)
        assertFalse(com.toolsboox.plugin.calendar.ot.MarkCard.Located(d1, "pickings-2#b").inAllPicks)
    }

    @Test
    fun `an old Log highlight with no card is drawn too - unless a mark or pick already draws it`() {
        val shown = ArticleMarkSet.assemble(listOf(mark("m", "Kept.")), listOf(
            line("ios-1", "Kept."), line("ios-2", "Only a line."), pick("P", "Picked."), line("ios-3", "Picked.")))
        assertEquals(listOf("m", "pick:P", "line:ios-2"), shown.map { it.id })
    }

    @Test
    fun `tombstoned marks and whole-piece notes draw nothing`() {
        val gone = mark("g", "Gone.").copy(deletedAt = 5L)
        val note = BookNote(id = "n", type = BookNote.NOTE, text = "")
        assertTrue(ArticleMarkSet.assemble(listOf(gone, note), emptyList()).isEmpty())
    }

    @Test
    fun `a removed highlight whose card was kept on a board does not paint back`() {
        // "Keep the card": the mark is tombstoned, the card stays — and stays claimed.
        val removed = mark("m", "Kept card.", pickId = "P9", logId = "m").copy(deletedAt = 7L)
        val shown = ArticleMarkSet.assemble(listOf(removed), listOf(pick("P9", "Kept card.")))
        assertTrue(shown.isEmpty())
        // An old pick removed before it was ever a mark: adopt-then-delete claims it the same way.
        val adopted = BookNote(id = "mfpk-p7", type = BookNote.ANNOTATION, text = "Old.", pickId = "P7", deletedAt = 3L)
        assertTrue(ArticleMarkSet.assemble(listOf(adopted), listOf(pick("P7", "Old."))).isEmpty())
    }

    @Test
    fun `adoption ids are deterministic - two devices make one mark`() {
        val p = ArticleMarkSet.Shown("pick:ABC-1", "x", pick = pick("ABC-1", "x"))
        assertEquals("mfpk-abc-1", ArticleMarkSet.adoptedId(p))
        val l1 = ArticleMarkSet.Shown("line:mfhl-3-2", "First passage.", line = line("mfhl-3-2", "First passage."))
        val l2 = ArticleMarkSet.Shown("line:mfhl-3-2", "Second passage.", line = line("mfhl-3-2", "Second passage."))
        // iOS's old count-based ids collide across devices; the passage tells them apart.
        assertFalse(ArticleMarkSet.adoptedId(l1) == ArticleMarkSet.adoptedId(l2))
        assertEquals(ArticleMarkSet.adoptedId(l1), ArticleMarkSet.adoptedId(l1.copy()))
    }

    @Test
    fun `passages compare the way the page script reads them`() {
        assertEquals("It's \"so\" - here", ArticleMarkSet.norm("  It’s “so”\u00A0-\n\nhere  "))
        assertEquals("soft hyphen", ArticleMarkSet.norm("soft\u00AD hyphen"))
    }

    @Test
    fun `a stored selection loses the link lines the bridge appended`() {
        assertEquals("The passage.", ArticlePickIndex.passageOf("The passage.\nsome link → https://x.example/a\nhttps://y.example/b"))
        assertEquals("Two lines\nof passage.", ArticlePickIndex.passageOf("Two lines\nof passage."))
        assertEquals("https://only.example", ArticlePickIndex.passageOf("https://only.example"))
    }

    @Test
    fun `which cards highlight back - a passage card from the web, not a link card or a sticker`() {
        assertTrue(ArticlePickIndex.isPassagePick("grampicks", "https://e.com/a", "words", false, ""))
        assertTrue(ArticlePickIndex.isPassagePick("pickings-2", "https://e.com/a", "words", false, ""))
        assertFalse(ArticlePickIndex.isPassagePick("intake", "https://e.com/a", "Title", false, "read"))
        assertFalse(ArticlePickIndex.isPassagePick("grampicks", "https://e.com/a", "", false, ""))
        assertFalse(ArticlePickIndex.isPassagePick("grampicks", "book:///x.epub", "words", false, ""))
        assertFalse(ArticlePickIndex.isPassagePick("grampicks", "https://e.com/a", "words", true, ""))
    }

    @Test
    fun `the paint list carries the anchor`() {
        val json = ArticleMarkSet.paintJson(ArticleMarkSet.assemble(listOf(mark("m", "P.")), emptyList()))
        assertTrue(json.contains("\"prefix\":\"before \""))
        assertNull(ArticleMarkSet.Shown("x", "t").mark)
    }
}
