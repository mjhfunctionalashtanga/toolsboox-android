package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.MarkCard
import com.toolsboox.plugin.calendar.ot.MarkWords
import com.toolsboox.plugin.calendar.ot.SynthListLanding
import com.toolsboox.plugin.calendar.ot.SynthWords
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Locale

/**
 * THE DAY-FILE GUARD'S WORDS, both apps (2026-10-16 review of the guard merge, items 1 and 6:
 * the new refusal lines differed between the apps, and each suite pinned its own wording, so
 * nothing caught the drift). `convergence-fixtures/day-guard-words.json` is the one table — committed
 * byte-identically to both repos; iOS runs the same cases — and this suite reads it.
 *
 * Also the pure halves of the landing fixes that ride with it: a kept reply's line said once per
 * window (item 10), and a list landing refused BEFORE anything is written (item 5).
 */
class DayGuardWordsGoldenTest {

    private fun fixture(name: String): String {
        javaClass.classLoader?.getResourceAsStream(name)?.use { return it.readBytes().decodeToString() }
        for (f in listOf(File("../convergence-fixtures/$name"), File("convergence-fixtures/$name"))) {
            if (f.exists()) return f.readText()
        }
        throw AssertionError("convergence fixture not found: $name")
    }

    private val table by lazy { JSONObject(fixture("day-guard-words.json")) }

    /** The refusal a day that won't read throws, for the fixture's "day". */
    private fun refusedOn(iso: String) =
        CalendarDayService.UnreadableDayException(File("day-$iso-v2.json"), null)

    // ── The table ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `every Synth refusal line is the table's line`() {
        val cases = table.getJSONArray("synth")
        assertTrue(cases.length() >= 4)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val e = refusedOn(c.getString("day"))
            val got = when (val fn = c.getString("fn")) {
                "replyKept" -> SynthWords.replyKept(c.getString("place"), e.whichPage)
                "citedCardsKept" -> SynthWords.citedCardsKept(e.whichPage)
                "driveByRefused" -> SynthWords.driveByRefused(e.whichPage)
                else -> throw AssertionError("unknown fn $fn")
            }
            assertEquals("${c.getString("fn")}(${c.optString("place")})", c.getString("expected"), got)
        }
    }

    @Test
    fun `every mark refusal is the table's verb, in the guard's shape`() {
        val verbs = mapOf(
            "logLine" to MarkWords.LOG_IT,
            "changeLine" to MarkWords.CHANGE_THE_LINE,
            "changePick" to MarkWords.CHANGE_THE_PICK,
            "lineOff" to MarkWords.TAKE_THE_LINE_OFF,
            "pickOff" to MarkWords.TAKE_THE_PICK_OFF,
            "copyToTodaysNote" to MarkWords.PUT_ON_TODAYS_NOTE,
        )
        val cases = table.getJSONArray("marks")
        val seen = mutableSetOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val site = c.getString("site")
            seen += site
            if (site == "highlight") {
                assertEquals("the new highlight's refusal", c.getString("expected"), MarkWords.HIGHLIGHT_REFUSED)
                continue
            }
            val verb = verbs[site] ?: throw AssertionError("unknown site $site")
            assertEquals("$site's verb", c.getString("doing"), verb)
            assertEquals("$site's line", c.getString("expected"), CalendarDayService.refusal(refusedOn(c.getString("day")), verb))
        }
        assertEquals("every Android verb is in the table", verbs.keys + "highlight", seen)
    }

    @Test
    fun `the kept replies' refusals are the table's`() {
        val cases = table.getJSONArray("replies")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            when (val site = c.getString("site")) {
                "logBasket" -> assertEquals(c.getString("expected"),
                    com.toolsboox.plugin.calendar.ui.ReadingLogFragment.BASKET_REFUSED)
                "starReply" -> {
                    assertEquals(c.getString("doing"), com.toolsboox.plugin.feeds.ui.FeedsFragment.KEEP_THE_REPLY)
                    assertEquals(c.getString("expected"), CalendarDayService.refusal(refusedOn(c.getString("day")),
                        com.toolsboox.plugin.feeds.ui.FeedsFragment.KEEP_THE_REPLY))
                }
                else -> throw AssertionError("unknown site $site")
            }
        }
    }

    // ── A kept reply's line, once per window (item 10) ──────────────────────────────────────

    @Test
    fun `a kept reply's line is not said again within the window, and is once it has passed`() {
        val r = SynthWords.Repeats(windowMs = 15_000L)
        val line = SynthWords.replyKept("Synthesize", "today's page")
        assertTrue("the first time", r.shouldSay(line, 1_000L))
        assertFalse("a return 5 s later", r.shouldSay(line, 6_000L))
        assertFalse("still within the window", r.shouldSay(line, 15_999L))
        assertTrue("past the window", r.shouldSay(line, 16_000L))
        assertTrue("another line is said at once", r.shouldSay(SynthWords.citedCardsKept("today's page"), 16_001L))
        assertTrue("…and the first again after it", r.shouldSay(line, 16_002L))
        assertEquals(15_000L, SynthWords.KEPT_REPEAT_MS)
    }

    // ── A list landing refused before anything is written (item 5) ──────────────────────────

    private fun service(): CalendarDayService =
        CalendarDayService(Moshi.Builder().add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build())

    private fun dayFile(root: File, d: LocalDate): File =
        File(root, "calendar/%04d/%02d/day-%s-v2.json".format(d.year, d.monthValue, d))

    private fun plan(page: LocalDate, pick: LocalDate) = SynthListLanding.Plan(page, "synthesize-1", emptyList(), emptyList(),
        SynthListLanding.Pick(pick, "iVBORw0K", 10, 10, "ledger://$page/synthesize-1", "❖ A page", "a pick"))

    @Test
    fun `a landing across midnight whose pick day won't read is refused before the page's day is touched`() {
        val root = Files.createTempDirectory("guard-words").toFile()
        val svc = service()
        val pageDay = LocalDate.of(2026, 10, 15)
        val pickDay = pageDay.plusDays(1)
        svc.save(root, pageDay, CalendarDay(pageDay.year, pageDay.monthValue, pageDay.dayOfMonth, Locale.US, startHour = 7))
        val before = dayFile(root, pageDay).readText()
        dayFile(root, pickDay).apply { parentFile?.mkdirs(); writeText("{ not a day") }

        val refused = runCatching { SynthListLanding.refuseUnreadable(svc, root, plan(pageDay, pickDay)) }.exceptionOrNull()
        assertTrue("refused: $refused", refused is CalendarDayService.UnreadableDayException)
        assertEquals("the pick's day is the one named", dayFile(root, pickDay).name,
            (refused as CalendarDayService.UnreadableDayException).file.name)
        assertEquals("the page's day is untouched", before, dayFile(root, pageDay).readText())
        assertEquals("{ not a day", dayFile(root, pickDay).readText())
    }

    @Test
    fun `a landing whose days read, or aren't there yet, goes ahead`() {
        val root = Files.createTempDirectory("guard-words").toFile()
        val svc = service()
        val pageDay = LocalDate.of(2026, 10, 15)
        svc.save(root, pageDay, CalendarDay(pageDay.year, pageDay.monthValue, pageDay.dayOfMonth, Locale.US, startHour = 7))
        SynthListLanding.refuseUnreadable(svc, root, plan(pageDay, pageDay))
        SynthListLanding.refuseUnreadable(svc, root, plan(pageDay, pageDay.plusDays(1)))   // no file: the writer mints it
    }

    // ── A highlight's pick day, read first (item 8) ─────────────────────────────────────────

    @Test
    fun `the day a pick's removal writes is the day it is found on, else its own`() {
        val root = Files.createTempDirectory("guard-words").toFile()
        val svc = service()
        val d = LocalDate.of(2026, 10, 15)
        val next = d.plusDays(1)
        val day = CalendarDay(next.year, next.monthValue, next.dayOfMonth, Locale.US, startHour = 7)
        val el = com.toolsboox.da.ImageElement(x = 0f, y = 0f, width = 10f, height = 10f, data = "", page = "grampicks")
        day.imageElements.add(el)
        svc.save(root, next, day)
        assertEquals("found a day on", next, MarkCard.removalDay(svc, root, d, el.elementId.toString()))
        assertEquals("nowhere here: its own day", d, MarkCard.removalDay(svc, root, d, java.util.UUID.randomUUID().toString()))
        assertNotNull(svc.loadOrNull(root, next, Locale.US))
    }
}
