package com.toolsboox

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.da.Attachment
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ListenState
import com.toolsboox.plugin.calendar.da.v2.MediaLogEntry
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.da.v2.ReadingSession
import com.toolsboox.plugin.calendar.da.v2.ReadingState
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.LedgerYear
import com.toolsboox.plugin.calendar.ot.YearInLedgerLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/**
 * YOUR YEAR IN THE LEDGER — the totals, pinned over a synthetic set of day files and a
 * listen-state file. Three days inside the week (Wed 9 · Thu 10 · Fri 11 Sep 2026), one day
 * outside it (30 Aug) that the month excludes and the year includes. The iOS twin
 * (`LedgerYearTests`) builds the same set and asserts the same numbers: the engine is the
 * cross-fork contract here, the way the merges are for the sidecars.
 */
class YearInLedgerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 9, 11)

    private val service = CalendarDayService(com.toolsboox.ot.LedgerMoshi.standard())

    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun date(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = Date(ms(y, m, d, h, min))

    private fun stroke() = Stroke(
        strokeId = UUID.randomUUID(),
        strokePoints = listOf(StrokePoint(1f, 1f, 1f), StrokePoint(2f, 2f, 1f))
    )

    private fun day(y: Int, m: Int, d: Int) = CalendarDay(y, m, d, startHour = 7)

    private fun root(): File = File(
        System.getProperty("java.io.tmpdir"), "year-in-ledger-${System.nanoTime()}"
    ).apply { mkdirs() }

    /** Three day files + one outside the week, written and read back through the service, so the
     *  engine sees exactly the JSON the day pages carry. */
    private fun writeDays(root: File) {
        // Wed 9 Sep — a book highlight at night, a starred article (with its -pub companion),
        // one picking, one voice gram, ink on the page.
        val d9 = day(2026, 9, 9)
        d9.readingEvents.addAll(listOf(
            ReadingEvent("bookhl-1", ReadingEvent.Kind.BOOK, date(2026, 9, 9, 21, 15),
                "Middlemarch", source = "George Eliot", excerpt = "It is a narrow mind…"),
            ReadingEvent("art-1", ReadingEvent.Kind.ARTICLE, date(2026, 9, 9, 8, 30),
                "On attention", source = "Aeon", url = "https://aeon.co/a", starred = true),
            ReadingEvent("art-1-pub", ReadingEvent.Kind.ARTICLE, date(2026, 9, 9, 8, 30),
                "On attention", source = "Aeon", url = "https://aeon.co/a", starred = true),
        ))
        d9.textElements.add(TextElement(x = 10f, y = 10f, text = "a picking", timestamp = ms(2026, 9, 9, 9, 0), pageKey = "pickings"))
        d9.avGrams.add(Attachment("g1", Attachment.Kind.AUDIO, "g1.m4a", duration = 30.0, date = date(2026, 9, 9, 10, 0)))
        d9.calendarStrokes[CalendarDay.DEFAULT_STYLE] = listOf(stroke())
        service.save(root, LocalDate.of(2026, 9, 9), d9)

        // Thu 10 Sep — a highlight with a note, an annotated article carrying a photo, ink in the notes.
        val d10 = day(2026, 9, 10)
        d10.readingEvents.addAll(listOf(
            ReadingEvent("bookhl-2", ReadingEvent.Kind.BOOK, date(2026, 9, 10, 21, 40),
                "Middlemarch", source = "George Eliot", excerpt = "…", note = "the key sentence"),
            ReadingEvent("art-2", ReadingEvent.Kind.ARTICLE, date(2026, 9, 10, 21, 50),
                "Slow reading", source = "Aeon", url = "https://aeon.co/b", excerpt = "…", note = "yes",
                attachments = mutableListOf(Attachment("p1", Attachment.Kind.PHOTO, "p1.jpg"))),
        ))
        d10.noteStrokes["0"] = listOf(stroke())
        service.save(root, LocalDate.of(2026, 9, 10), d10)

        // Fri 11 Sep — one highlight, one photo gram, no ink.
        val d11 = day(2026, 9, 11)
        d11.readingEvents.add(
            ReadingEvent("bookhl-3", ReadingEvent.Kind.BOOK, date(2026, 9, 11, 21, 5),
                "The Dispossessed", source = "Ursula K. Le Guin", excerpt = "…")
        )
        d11.avGrams.add(Attachment("g2", Attachment.Kind.PHOTO, "g2.jpg", date = date(2026, 9, 11, 7, 0)))
        service.save(root, LocalDate.of(2026, 9, 11), d11)

        // Sun 30 Aug — outside the week and the month; inside the year.
        val d30 = day(2026, 8, 30)
        d30.readingEvents.add(
            ReadingEvent("bookhl-0", ReadingEvent.Kind.BOOK, date(2026, 8, 30, 20, 0),
                "The Dispossessed", source = "Ursula K. Le Guin", excerpt = "…")
        )
        d30.calendarStrokes[CalendarDay.DEFAULT_STYLE] = listOf(stroke())
        service.save(root, LocalDate.of(2026, 8, 30), d30)
    }

    /** The listen-state sidecar as the wire carries it, through the store's own adapter shape. */
    private fun listenStates(): List<ListenState> {
        val json = """
        [
          {"id":"https://pod.example/ep1","positionMs":1800000,"durationMs":3600000,"done":false,"updated":${ms(2026, 9, 10, 18, 0)},"deletedAt":0},
          {"id":"https://pod.example/ep2","positionMs":3420000,"durationMs":3600000,"done":true,"updated":${ms(2026, 9, 8, 7, 0)},"deletedAt":0},
          {"id":"https://pod.example/ep3","positionMs":1200000,"durationMs":3600000,"done":false,"updated":${ms(2026, 8, 1, 7, 0)},"deletedAt":0},
          {"id":"https://pod.example/gone","positionMs":600000,"durationMs":3600000,"done":false,"updated":${ms(2026, 9, 9, 7, 0)},"deletedAt":${ms(2026, 9, 9, 8, 0)}}
        ]
        """.trimIndent()
        val adapter = Moshi.Builder().build()
            .adapter<List<ListenState>>(Types.newParameterizedType(List::class.java, ListenState::class.java))
        return adapter.fromJson(json)!!
    }

    private fun input(root: File, span: LedgerYear.Span): LedgerYear.Input {
        val (from, to) = span.bounds(today, zone)
        return LedgerYear.Input(
            days = YearInLedgerLoader.days(service, root, from, to, zone),
            listen = listenStates(),
            sessions = listOf(
                ReadingSession(id = "s1", medium = "book", ref = "b#1", start = ms(2026, 9, 9, 21, 0), durationMs = 30 * 60_000L),
                ReadingSession(id = "s2", medium = "comic", ref = "c#1", start = ms(2026, 9, 10, 20, 0), durationMs = 15 * 60_000L),
                ReadingSession(id = "s3", medium = "feed", ref = "f#1", start = ms(2026, 9, 10, 8, 0), durationMs = 10 * 60_000L),
            ),
            mediaLog = listOf(
                MediaLogEntry(id = "https://aeon.co/a", kind = "read", title = "On attention", source = "Aeon", at = ms(2026, 9, 9, 8, 25)),
                MediaLogEntry(id = "https://aeon.co/b", kind = "read", title = "Slow reading", source = "Aeon", at = ms(2026, 9, 10, 21, 45)),
                MediaLogEntry(id = "https://yt.example/v", kind = "watch", title = "A talk", source = "YouTube", at = ms(2026, 9, 10, 12, 0)),
                MediaLogEntry(id = "https://old.example/x", kind = "read", title = "Old", source = "Old", at = ms(2026, 8, 30, 12, 0)),
            ),
            readingStates = listOf(
                ReadingState(id = "Novels/Middlemarch.epub#1234", locator = "epubcfi(/6/4)", percent = 0.97, updated = ms(2026, 9, 10, 22, 0)),
                ReadingState(id = "Dune.epub#99", locator = "epubcfi(/6/2)", percent = 0.5, updated = ms(2026, 9, 10, 22, 0)),
            ),
            bookOpens = listOf(
                LedgerYear.BookOpen("Middlemarch", listOf(ms(2026, 9, 9, 21, 0), ms(2026, 9, 10, 21, 0))),
                LedgerYear.BookOpen("Dune", listOf(ms(2026, 8, 30, 9, 0))),
            ),
            letters = listOf(
                LedgerYear.Letter("Jane", "jane@example.com", ms(2026, 9, 9, 9, 0), sent = false),
                LedgerYear.Letter("Jane", "jane@example.com", ms(2026, 9, 10, 9, 0), sent = false),
                LedgerYear.Letter("Jane", "jane@example.com", ms(2026, 9, 10, 10, 0), sent = true),
                LedgerYear.Letter("", "bob@example.com", ms(2026, 8, 30, 9, 0), sent = false),
            ),
        )
    }

    private fun review(span: LedgerYear.Span): LedgerYear.Review {
        val root = root()
        writeDays(root)
        return LedgerYear.review(input(root, span), span, today, zone)
    }

    // ── The totals ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `week totals`() {
        val r = review(LedgerYear.Span.WEEK)
        assertEquals("Middlemarch this week; Dune was August", 1, r.booksOpened)
        assertEquals("Middlemarch crossed 95% on Thursday", 1, r.booksFinished)
        assertEquals("book + comic sessions; the feed session is not a book", 45L, r.bookMinutes)
        assertEquals("two read rows this week; the watch row is not an article", 2, r.articlesRead)
        assertEquals("one star — its -pub companion is the same star", 1, r.articlesStarred)
        assertEquals("three book excerpts + one article excerpt", 4, r.highlights)
        assertEquals("book note + article note + the picking", 3, r.notes)
        assertEquals("30 + 57 minutes heard; the tombstoned row is gone, ep3 was August", 87L, r.listenMinutes)
        assertEquals(2, r.episodes)
        assertEquals(1, r.episodesFinished)
        assertEquals(2, r.lettersReceived)
        assertEquals(1, r.lettersSent)
        assertEquals(1, r.voiceMemos)
        assertEquals("one attachment photo + one photo gram", 2, r.photos)
        assertEquals(0, r.videos)
        assertEquals("Wed on the page, Thu in the notes; Fri had none", 2, r.inkDays)
        assertEquals("Wed · Thu · Fri all had something logged", 3, r.streak)
        assertEquals("the 9 pm highlights outnumber the morning stars", 21, r.busiestHour)

        assertEquals(listOf(LedgerYear.Ranked("George Eliot", 2), LedgerYear.Ranked("Ursula K. Le Guin", 1)), r.topAuthors)
        assertEquals(listOf(LedgerYear.Ranked("Aeon", 2)), r.topFeeds)
        assertEquals(listOf(LedgerYear.Ranked("Jane", 3)), r.topCorrespondents)
        assertFalse(r.isEmpty)
    }

    @Test
    fun `month excludes August and year includes it`() {
        val month = review(LedgerYear.Span.MONTH)
        assertEquals(4, month.highlights)
        assertEquals(2, month.inkDays)
        assertEquals(2, month.lettersReceived)

        val year = review(LedgerYear.Span.YEAR)
        assertEquals("Dune's August open counts in the year", 2, year.booksOpened)
        assertEquals(5, year.highlights)
        assertEquals(3, year.inkDays)
        assertEquals(3, year.articlesRead)
        assertEquals(107L, year.listenMinutes)
        assertEquals(3, year.episodes)
        assertEquals(3, year.lettersReceived)
        assertEquals("30 Aug stands alone; the September run is the long one", 3, year.streak)
        assertEquals("a tie sorts by name, so both forks print the same order",
            listOf(LedgerYear.Ranked("George Eliot", 2), LedgerYear.Ranked("Ursula K. Le Guin", 2)), year.topAuthors)
        assertEquals("a nameless letter is counted under its address",
            listOf(LedgerYear.Ranked("Jane", 3), LedgerYear.Ranked("bob@example.com", 1)), year.topCorrespondents)

        val all = review(LedgerYear.Span.ALL)
        assertEquals(5, all.highlights)
    }

    @Test
    fun `an empty span reads as empty`() {
        val r = LedgerYear.review(LedgerYear.Input(), LedgerYear.Span.WEEK, today, zone)
        assertTrue(r.isEmpty)
        assertNull(r.busiestHour)
        assertEquals("—", r.stats.last().number)
        assertTrue(LedgerYear.shareText(r, zone).contains(LedgerYear.EMPTY_WORDS))
    }

    // ── The words ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `rows wear the shared words`() {
        val r = review(LedgerYear.Span.WEEK)
        assertEquals(listOf(
            "1  book opened",
            "1  book finished",
            "0.8  hours with a book open",
            "2  articles read",
            "1  article starred",
            "4  highlights kept",
            "3  notes kept",
            "1.5  hours listened",
            "2  letters received",
            "1  letter sent",
            "1  voice memo kept",
            "2  photos kept",
            "2  days with ink on them",
            "3  days in your longest streak",
            "9 pm  the hour you read most",
        ), r.stats.map { "${it.number}  ${it.words}" })
        assertEquals("across 2 episodes · 1 finished", r.stats.first { it.id == "listenHours" }.detail)
        assertEquals(LedgerYear.hourBars(r.hourBlocks), r.stats.last().detail)
    }

    @Test
    fun `share text is the same content as plain text`() {
        val r = review(LedgerYear.Span.WEEK)
        val text = LedgerYear.shareText(r, zone)
        assertTrue(text.startsWith("YOUR YEAR IN THE LEDGER\nThis week · 7 Sep – 11 Sep 2026\n"))
        assertTrue(text.contains("\n4  highlights kept\n"))
        assertTrue(text.contains("\nTOP AUTHORS\n1. George Eliot — 2 highlights\n2. Ursula K. Le Guin — 1 highlight\n"))
        assertTrue(text.contains("\nTOP FEEDS\n1. Aeon — 2 marks\n"))
        assertTrue(text.contains("\nTOP CORRESPONDENTS\n1. Jane — 3 letters"))
    }

    // ── The little judgments ──────────────────────────────────────────────────────────────────

    @Test
    fun `week starts on Monday`() {
        val (from, to) = LedgerYear.Span.WEEK.bounds(today, zone)
        assertEquals("Friday 11 Sep's week began on Monday 7 Sep", ms(2026, 9, 7), from)
        assertEquals("and runs to the start of tomorrow", ms(2026, 9, 12), to)
        assertEquals(ms(2026, 9, 1), LedgerYear.Span.MONTH.bounds(today, zone).first)
        assertEquals(ms(2026, 1, 1), LedgerYear.Span.YEAR.bounds(today, zone).first)
        assertEquals(0L, LedgerYear.Span.ALL.bounds(today, zone).first)
    }

    @Test
    fun `hours text`() {
        assertEquals("0", LedgerYear.hoursText(0))
        assertEquals("1.5", LedgerYear.hoursText(87 * 60_000L))
        assertEquals("0.8", LedgerYear.hoursText(45 * 60_000L))
        assertEquals("12", LedgerYear.hoursText(12 * 3_600_000L))
        assertEquals("three minutes rounds up to a tenth, not down to nothing", "0.1", LedgerYear.hoursText(3 * 60_000L))
    }

    @Test
    fun `hour text`() {
        assertEquals("12 midnight", LedgerYear.hourText(0))
        assertEquals("6 am", LedgerYear.hourText(6))
        assertEquals("12 noon", LedgerYear.hourText(12))
        assertEquals("9 pm", LedgerYear.hourText(21))
    }

    @Test
    fun `hour bars are text-sized blocks`() {
        val lines = LedgerYear.hourBars(listOf(0, 0, 2, 0, 0, 10)).split("\n")
        assertEquals(6, lines.size)
        assertEquals("late       ▮▮▮▮▮▮▮▮▮▮", lines[5])
        assertEquals("morning    ▮▮▯▯▯▯▯▯▯▯", lines[2])
        assertEquals("night      ▯▯▯▯▯▯▯▯▯▯", lines[0])
    }

    @Test
    fun `streak and book title`() {
        assertEquals(3, LedgerYear.longestRun(setOf(1L, 2L, 3L, 7L, 8L, 20L)))
        assertEquals(0, LedgerYear.longestRun(emptySet()))
        assertEquals("Middlemarch", LedgerYear.bookTitle("Novels/Middlemarch.epub#1234"))
        assertEquals("Dune", LedgerYear.bookTitle("Dune.epub#99"))
        assertEquals("no-hash", LedgerYear.bookTitle("no-hash"))
    }
}
