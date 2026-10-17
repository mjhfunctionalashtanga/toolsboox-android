package com.toolsboox

import android.content.ContextWrapper
import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import com.toolsboox.plugin.calendar.ot.YearInLedgerLoader
import com.toolsboox.plugin.chat.da.Section
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * EVERY READER OF THE LOG DROPS A REMOVED HIGHLIGHT AND SHOWS THE EDITED NOTE (design critique #2,
 * Michael 2026-10-12: removing the highlight removes its line; a note added later goes on the SAME
 * line, on the day it was highlighted). The merge keeps this right between two devices
 * (ReadingEventMergeTest); these pin the read side — a day file that still carries a line BESIDE its
 * tombstone (an older build's merge — the Boox fleet on -195, iOS before 248 — or a WebDAV pull of a
 * day this device never had) shows nothing of it to the Log, its search, the corpus, Your Year or the
 * Reply-with-Log picker, and the next writer sheds it for good.
 */
class LogLineTombstoneReadTest {

    private val moshi = Moshi.Builder()
        .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
    private val service = CalendarDayService(moshi)

    private class FilesDirContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private val removedPassage = "A sentence you highlighted and then took back"
    private val keptPassage = "A sentence that stays highlighted"

    /** 12 Oct 2026, as an older build's merge leaves it: the removed line is back, its tombstone
     *  (upper-case, as iOS writes it) still in the file; the kept line carries its edited note. */
    private fun writeDay(root: File): File {
        val dir = File(root, "calendar/2026/10").apply { mkdirs() }
        return File(dir, "day-2026-10-12-v2.json").apply {
            writeText(
                """
                {
                  "year":2026,"month":10,"day":12,"locale":"en-US",
                  "events":[],"readingProgress":[],"hasLanes":true,"startHour":null,
                  "calendarStrokes":{},"calendarValues":{},"noteStrokes":{},
                  "textElements":[],"imageElements":[],
                  "readingEvents":[
                    {"id":"mfhl-42-aaaa1111","kind":"article","date":1791800000000,"title":"On Practice",
                     "source":"Ashtanga Tech","url":"https://ashtanga.tech/on-practice",
                     "excerpt":"$removedPassage","note":"a note on the removed one"},
                    {"id":"mfhl-42-bbbb2222","kind":"article","date":1791800100000,"title":"On Practice",
                     "source":"Ashtanga Tech","url":"https://ashtanga.tech/on-practice",
                     "excerpt":"$keptPassage","note":"the edited note","updatedAt":1791900000000}
                  ],
                  "avGrams":[],
                  "deletedStrokeIds":[],"deletedElementIds":["MFHL-42-AAAA1111"],
                  "created":1791700000000,"updated":1791900000000
                }
                """.trimIndent()
            )
        }
    }

    private fun root(): File = Files.createTempDirectory("log-tombstones").toFile()

    @Test
    fun `the full load - the Log, its search, Your Year, the day page - never sees the removed line`() {
        val root = root()
        val file = writeDay(root)
        val day = service.load(file)!!
        assertEquals(listOf("mfhl-42-bbbb2222"), day.readingEvents.map { it.id })
        // The edited note and its stamp survive the decode.
        assertEquals("the edited note", day.readingEvents.single().note)
        assertEquals(1791900000000L, day.readingEvents.single().updatedAt?.time)
        // Your Year walks the same loader.
        val zone = ZoneOffset.UTC
        val from = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yearDays = YearInLedgerLoader.days(service, root, from, to, zone)
        assertEquals(listOf("mfhl-42-bbbb2222"), yearDays.flatMap { d -> d.readingEvents.map { it.id } })
    }

    @Test
    fun `the slim Log and corpus decodes drop it too`() {
        val file = writeDay(root())
        assertEquals(listOf("mfhl-42-bbbb2222"), service.loadLogSlice(file)!!.readingEvents.map { it.id })
        val corpus = service.loadCorpusSlice(file)!!
        assertEquals(listOf("mfhl-42-bbbb2222"), corpus.readingEvents.map { it.id })
        assertEquals("the edited note", corpus.readingEvents.single().note)
    }

    @Test
    fun `Ask's corpus has the kept passage and not the removed one`() {
        val root = root()
        writeDay(root)
        val corpus = LedgerCorpusService(service, FilesDirContext(Files.createTempDirectory("files").toFile()))
        val texts = corpus.gather(root, Section.ALL).joinToString("\n") { it.text }
        assertTrue(texts.contains(keptPassage))
        assertFalse(texts.contains(removedPassage))
        assertFalse(texts.contains("a note on the removed one"))
    }

    @Test
    fun `a writer that loads and saves sheds the stray line - and keeps the tombstone`() {
        val root = root()
        val file = writeDay(root)
        val d = LocalDate.of(2026, 10, 12)
        service.save(root, d, service.load(file)!!)
        val raw = JSONObject(file.readText())
        val ids = raw.getJSONArray("readingEvents").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(listOf("mfhl-42-bbbb2222"), ids)
        val dead = raw.getJSONArray("deletedElementIds").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(dead.any { it.equals("mfhl-42-aaaa1111", ignoreCase = true) })
        // And the edit's stamp travels on the wire, so the other device's merge keeps it.
        assertTrue(raw.getJSONArray("readingEvents").getJSONObject(0).has("updatedAt"))
    }

    @Test
    fun `removing then editing in place - through the real day files - every reader agrees`() {
        val root = root()
        val d = LocalDate.of(2026, 10, 12)
        val day = CalendarDay(2026, 10, 12, locale = Locale.US, startHour = null)
        day.readingEvents.add(line("mfhl-1-aaaa", removedPassage))
        day.readingEvents.add(line("mfhl-1-bbbb", keptPassage))
        service.save(root, d, day)
        assertTrue(ReadingEventEdit.remove(service, root, d, "mfhl-1-aaaa"))
        assertEquals(d, ReadingEventEdit.update(service, root, d, "mfhl-1-bbbb") { it.copy(note = "said later") })
        val file = File(root, "calendar/2026/10/day-2026-10-12-v2.json")
        val full = service.load(file)!!.readingEvents
        assertEquals(listOf("mfhl-1-bbbb"), full.map { it.id })
        assertEquals("said later", full.single().note)
        assertTrue(full.single().updatedAt != null)
        assertEquals(listOf("said later"), service.loadLogSlice(file)!!.readingEvents.map { it.note })
        assertEquals(listOf("said later"), service.loadCorpusSlice(file)!!.readingEvents.map { it.note })
        // The other device still holds the removed line and the old note, and saved its day LATER:
        // the merge keeps the removal and the edit.
        val theirs = CalendarDay(2026, 10, 12, locale = Locale.US, startHour = null, updated = Date(Long.MAX_VALUE / 2))
        theirs.readingEvents.add(line("mfhl-1-aaaa", removedPassage))
        theirs.readingEvents.add(line("mfhl-1-bbbb", keptPassage))
        val merged = CalendarDayMerger.merge(service.load(file)!!, theirs)
        assertEquals(listOf("mfhl-1-bbbb"), merged.readingEvents.map { it.id })
        assertEquals("said later", merged.readingEvents.single().note)
    }

    @Test
    fun `nothing dead - the same list comes back, no copy`() {
        val lines = listOf(line("a", "x"), line("b", "y"))
        assertSame(lines, CalendarDayMerger.liveLines(lines, emptyList()))
        assertSame(lines, CalendarDayMerger.liveLines(lines, listOf("some-card-uuid")))
        assertEquals(listOf("b"), CalendarDayMerger.liveLines(lines, listOf("A")).map { it.id })
    }

    private fun line(id: String, passage: String) = ReadingEvent(
        id = id, kind = ReadingEvent.Kind.ARTICLE, date = Date(1_000L), title = "On Practice",
        url = "https://ashtanga.tech/on-practice", excerpt = passage)
}
