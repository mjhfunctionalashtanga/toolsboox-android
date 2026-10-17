package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.ArticlePickIndex
import com.toolsboox.plugin.calendar.ot.ArticlePickIndex.Hit
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import com.toolsboox.plugin.feeds.ot.ArticleKey
import com.toolsboox.plugin.feeds.ot.ArticleMarkSet
import com.toolsboox.plugin.reader.ui.BookNote
import com.toolsboox.plugin.reader.ui.MarkFile
import com.toolsboox.plugin.reader.ui.MarkJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The 2026-10-12 review of the article highlights (Michael: "gram picks should highlight back too";
 * "removing the highlight removes its pick") — each finding that could be pinned off a device.
 */
class ArticleMarksReviewTest {

    private val moshi = Moshi.Builder()
        .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
    private val service = CalendarDayService(moshi)
    private fun root(): File = Files.createTempDirectory("marks-review").toFile()

    private val url = "https://ashtanga.tech/on-practice"
    private val key = ArticleKey.of(url)
    private val d1 = LocalDate.of(2026, 10, 1)
    private val d2 = LocalDate.of(2026, 10, 12)

    private fun pick(id: String, text: String, date: LocalDate = d1, label: String = "", page: String = "grampicks") =
        Hit(date, key, text, elementId = id, page = page, label = label)
    private fun line(id: String, text: String, date: LocalDate = d1, noted: Boolean = false) =
        Hit(date, key, text, lineId = id, noted = noted)
    private fun mark(id: String, text: String, pickId: String = "", logId: String = "", deleted: Long? = null) = BookNote(
        id = id, type = BookNote.ANNOTATION, text = text, cfi = ArticleMarkSet.anchorJson(text, "", ""),
        pickId = pickId, logId = logId, day = d2.toString(), deletedAt = deleted)

    // ── #4: false highlights ──────────────────────────────────────────────────────────────────

    @Test
    fun `a link card whose words are the title or its own label is not a highlight`() {
        val shown = ArticleMarkSet.assemble(emptyList(), listOf(
            pick("P-title", "On  Practice"),                                   // a link placed on a day page
            pick("P-label", "On Practice · The Shala", label = "On Practice · The Shala"),
            line("L-title", "on practice"),
            pick("P-real", "The breath leads; the body follows.")), title = "On Practice")
        assertEquals(listOf("pick:P-real"), shown.map { it.id })
    }

    @Test
    fun `podcast moments and listen lines leave the index - a passage-less note stays, with its words`() {
        assertTrue(ArticlePickIndex.isMomentLink("https://pod.example/ep7#t=1421"))
        assertTrue(ArticlePickIndex.isMomentLink("https://pod.example/ep7#a=1&t=23:41.5"))
        assertFalse(ArticlePickIndex.isMomentLink("https://pod.example/ep7#t="))
        assertFalse(ArticlePickIndex.isMomentLink("https://blog.example/post#section-2"))
        val day = CalendarDay(2026, 10, 12, locale = Locale.US, startHour = null)
        day.imageElements.add(card("@ 23:41", "https://pod.example/ep7#t=1421"))
        day.imageElements.add(card("A real passage.", url, label = "On Practice"))
        day.readingEvents.add(ev("listen-1", "@ 23:41", "https://pod.example/ep7"))
        day.readingEvents.add(ev("mfnote-0-aa", null, url, note = "What I made of the whole piece"))
        day.readingEvents.add(ev("star-1", null, url))                                // a bare ★
        day.readingEvents.add(ev("art-5", null, url, note = "The piece's own blurb", starred = true))
        val hits = ArticlePickIndex.hitsOf(d2, day)
        assertEquals(listOf("A real passage."), hits.filter { it.elementId.isNotBlank() }.map { it.text })
        assertEquals("On Practice", hits.first { it.elementId.isNotBlank() }.label)
        val lines = hits.filter { it.lineId.isNotBlank() }
        assertEquals(listOf("mfnote-0-aa"), lines.map { it.lineId })
        assertEquals("", lines.single().text)
        assertEquals("What I made of the whole piece", lines.single().note)
        // …and a passage-less line draws nothing.
        assertTrue(ArticleMarkSet.assemble(emptyList(), lines).isEmpty())
    }

    @Test
    fun `a literal JSON null in one line no longer empties the day's index`() {
        val root = root()
        val f = File(root, "calendar/2026/10").apply { mkdirs() }.resolve("day-2026-10-12-v2.json")
        f.writeText("""{"imageElements":[{"elementId":"e1","page":"grampicks","sourceLink":"$url","sourceLabel":null,
            "cardText":"A passage.","decorative":null,"intakeKind":null,"data":""}],
            "readingEvents":[{"id":"l1","kind":"article","url":"$url","excerpt":"Another.","source":null,"note":null,
            "attachments":[{"id":"a1","filename":"ink-x.png"}]}],"deletedElementIds":[null]}""")
        val slice = service.loadPicksSlice(f)
        assertNotNull(slice)
        assertEquals("A passage.", slice!!.imageElements!!.single()!!.cardText)
        assertEquals("ink-x.png", slice.readingEvents!!.single()!!.attachments!!.single()!!.filename)
    }

    // ── #3: removing every source of a passage ────────────────────────────────────────────────

    @Test
    fun `removing a drawn passage takes its hidden twins - not a card a removed mark kept`() {
        val drawn = mark("mfhl-1-aaaa", "The passage.", pickId = "P1", logId = "mfhl-1-aaaa")
        val hits = listOf(
            pick("P1", "The passage."), line("mfhl-1-aaaa", "The passage."),
            pick("P-old-make-pick", "The  passage."),                 // the old Make pick of it
            line("ios-7", "The passage.", d2),                         // another device's ❝ line
            pick("P-kept", "The passage."),                            // kept on a board by an earlier removal
            pick("P-other", "Another passage."))
        val removedEarlier = mark("mfpk-p-kept", "The passage.", pickId = "P-kept", deleted = 5L)
        val marks = listOf(drawn, removedEarlier)
        val target = ArticleMarkSet.assemble(marks, hits).first { it.id == drawn.id }
        val ids = ArticleMarkSet.sourcesOf(target, marks, hits).map { it.id }
        assertEquals(listOf("mfhl-1-aaaa", "pick:P-old-make-pick", "line:ios-7"), ids)
        // Each source adopts under its own deterministic id — what the removal tombstones.
        val adopted = ArticleMarkSet.sourcesOf(target, marks, hits).drop(1).map { ArticleMarkSet.adoptedId(it) }
        assertEquals("mfpk-p-old-make-pick", adopted[0])
        assertTrue(adopted[1].startsWith("ios-7-"))
    }

    @Test
    fun `once removed, a twin and a kept card stay claimed - the passage does not paint back`() {
        val hits = listOf(pick("P1", "The passage."), pick("P2", "The passage."))
        val gone = listOf(mark("mfpk-p1", "The passage.", pickId = "P1", deleted = 9L),
            mark("mfpk-p2", "The passage.", pickId = "P2", deleted = 9L))
        assertTrue(ArticleMarkSet.assemble(gone, hits).isEmpty())
    }

    // ── #1: a removal travels even when the line isn't here ───────────────────────────────────

    @Test
    fun `a line not in this copy of its day - the removal is recorded there all the same`() {
        val root = root()
        val day = CalendarDay(2026, 10, 1, locale = Locale.US, startHour = 7)
        service.save(root, d1, day)
        assertFalse(ReadingEventEdit.remove(service, root, d1, "mfhl-9-iphone"))
        val here = service.loadOrNull(root, d1, Locale.US)!!
        assertTrue(here.deletedElementIds.any { it.equals("mfhl-9-iphone", ignoreCase = true) })
        assertEquals(7, here.startHour)
        // The iPhone's copy arrives with the line: the merge drops it.
        val theirs = CalendarDay(2026, 10, 1, locale = Locale.US, startHour = 7, updated = Date(Long.MAX_VALUE / 2))
        theirs.readingEvents.add(ReadingEvent(id = "mfhl-9-iphone", kind = ReadingEvent.Kind.ARTICLE,
            date = Date(1_000L), title = "On Practice", url = url, excerpt = "The passage."))
        assertTrue(CalendarDayMerger.merge(here, theirs).readingEvents.isEmpty())
    }

    @Test
    fun `a day this device never held gets a removal carrier that defers to the real day`() {
        val root = root()
        assertFalse(service.exists(root, d1))
        ReadingEventEdit.remove(service, root, d1, "mfhl-9-iphone")
        val carrier = service.loadOrNull(root, d1, Locale.US)!!
        assertEquals(0L, carrier.updated?.time)
        assertNull(carrier.startHour)
        assertTrue(carrier.deletedElementIds.contains("mfhl-9-iphone"))
        // The real day — its start hour, its other lines — wins every day-level field; the carrier
        // adds only its marker.
        val real = CalendarDay(2026, 10, 1, locale = Locale.US, startHour = 7, updated = Date(1_700_000_000_000L))
        real.readingEvents.add(ReadingEvent(id = "mfhl-9-iphone", kind = ReadingEvent.Kind.ARTICLE,
            date = Date(1_000L), title = "t", url = url, excerpt = "The passage."))
        real.readingEvents.add(ReadingEvent(id = "keep", kind = ReadingEvent.Kind.ARTICLE,
            date = Date(1_000L), title = "t", url = url, excerpt = "Kept."))
        for (merged in listOf(CalendarDayMerger.merge(carrier, real), CalendarDayMerger.merge(real, carrier))) {
            assertEquals(7, merged.startHour)
            assertEquals(listOf("keep"), merged.readingEvents.map { it.id })
            assertEquals(1_700_000_000_000L, merged.updated?.time)
        }
    }

    // ── #2: a past day is never minted fresh, nor overwritten when it won't load ──────────────

    @Test
    fun `a line for a past day that isn't here - nothing written, the caller hears so`() {
        val root = root()
        val e = ReadingEvent(id = "mfpk-x", kind = ReadingEvent.Kind.ARTICLE, date = Date(), title = "t", url = url)
        assertFalse(ReadingEventEdit.addIfAbsent(service, root, d1, e))
        assertFalse(service.exists(root, d1))
        // A day file that exists but won't load is left exactly as it is — and (the day-file guard,
        // merged after) the writer says so with the refusal, so the caller names the page.
        val f = File(root, "calendar/2026/10").apply { mkdirs() }.resolve("day-2026-10-01-v2.json")
        f.writeText("{ torn")
        assertTrue(runCatching { ReadingEventEdit.addIfAbsent(service, root, d1, e) }.exceptionOrNull()
            is CalendarDayService.UnreadableDayException)
        assertEquals("{ torn", f.readText())
        assertTrue(runCatching { ReadingEventEdit.recordRemoval(service, root, d1, listOf("x")) }.exceptionOrNull()
            is CalendarDayService.UnreadableDayException)
        assertEquals("{ torn", f.readText())
        // Today is how a day begins: it is made.
        assertTrue(ReadingEventEdit.addIfAbsent(service, root, LocalDate.now(), e))
        assertTrue(service.exists(root, LocalDate.now()))
    }

    // ── #9: a failed pull doesn't push over the server ────────────────────────────────────────

    @Test
    fun `a null pull after the file was seen pushes nothing - two in a row, it really is gone`() {
        val f = Files.createTempDirectory("marks").toFile().resolve("m.json")
        val file = MarkFile({ error("no context in a JVM test") }, "reader/test.json")
        file.mutateAt(f, "k") { it + BookNote(id = "a", type = BookNote.ANNOTATION, updatedAt = 5) }
        // Never seen: a first sync pushes on a null, as before.
        assertTrue(file.pushIfMissing(null))
        val remote = JSONObject().put("k", JSONArray().put(MarkJson.toJson(BookNote(id = "a", type = BookNote.ANNOTATION, updatedAt = 5)))).toString()
        assertTrue(file.pushIfMissing(remote))
        file.mergeAfterPull(f, remote, true)
        // Seen, then a null (a failed GET, or gone): this round pushes nothing…
        val once = file.pushIfMissing(null)
        assertFalse(once)
        assertNull(file.mergeAfterPull(f, null, once).second)
        // …the second null in a row does.
        val twice = file.pushIfMissing(null)
        assertTrue(twice)
        assertNotNull(file.mergeAfterPull(f, null, twice).second)
    }

    @Test
    fun `the parsed copy follows every write - and a file replaced behind its back`() {
        val f = Files.createTempDirectory("marks").toFile().resolve("m.json")
        val file = MarkFile({ error("no context in a JVM test") }, "reader/test.json")
        file.mutateAt(f, "k") { it + BookNote(id = "a", type = BookNote.ANNOTATION) }
        assertEquals(listOf("a"), file.rawListAt(f, "k").map { it.id })
        file.mutateAt(f, "k") { it + BookNote(id = "b", type = BookNote.ANNOTATION) }
        assertEquals(listOf("a", "b"), file.rawListAt(f, "k").map { it.id })
        f.writeText(JSONObject().put("k", JSONArray().put(MarkJson.toJson(BookNote(id = "z", type = BookNote.ANNOTATION)))).put("k2", JSONArray()).toString())
        f.setLastModified(f.lastModified() + 5_000)
        assertEquals(listOf("z"), file.rawListAt(f, "k").map { it.id })
    }

    private fun card(text: String, link: String, label: String = "") = ImageElement(
        elementId = UUID.randomUUID(), x = 0f, y = 0f, width = 10f, height = 10f, data = "",
        page = "grampicks", sourceLink = link, sourceLabel = label, cardText = text)

    private fun ev(id: String, excerpt: String?, link: String, note: String? = null, starred: Boolean = false) = ReadingEvent(
        id = id, kind = ReadingEvent.Kind.ARTICLE, date = Date(1_000L), title = "On Practice", url = link,
        excerpt = excerpt, note = note, starred = starred)
}
