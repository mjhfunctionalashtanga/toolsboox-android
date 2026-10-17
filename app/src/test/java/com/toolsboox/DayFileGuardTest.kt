package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.da.ImageElement
import com.toolsboox.di.NetworkModule
import com.toolsboox.ot.LedgerMoshi
import com.toolsboox.plugin.calendar.da.v1.CalendarSyncItem
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.fi.CalendarDayService.DayRead
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.ot.GramTopicHarvest
import com.toolsboox.plugin.calendar.ot.MarkCard
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import com.toolsboox.plugin.calendar.ot.StationGrams
import com.toolsboox.plugin.calendar.ot.SynthFace
import com.toolsboox.plugin.calendar.ot.SynthLanding
import com.toolsboox.plugin.calendar.ui.CalendarGoogleDriveSyncPresenter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A DAY THAT WON'T READ IS NOT AN EMPTY DAY (2026-10-12, after Android -322).
 *
 * The field bug was a bare `CalendarDayService()` whose field-injected Moshi was never set: every
 * load threw, the service called the throw "corrupt day file … treating as unwritten", and handed
 * back a fresh empty day. The Synth landing happened to refuse; about forty other writers would
 * have saved that fresh day plus their one new item straight over the real file. This suite pins
 * the two halves of the fix:
 *
 *  1. A service cannot be built without its Moshi, and the two ways the app builds one
 *     ([CalendarDayService.standalone]'s [LedgerMoshi] and Hilt's [NetworkModule.provideMoshi])
 *     both read a real cross-platform fixture day (`convergence-fixtures/day-b.json`).
 *  2. Whatever makes a day unreadable — a misconfigured Moshi, a torn write, an enum value only
 *     a newer app knows — every WRITER door refuses, and the file on disk is byte-identical after.
 *
 * And each site the -322 sweep fixed reads the fixture through the code it now runs.
 */
class DayFileGuardTest {

    private val date: LocalDate = LocalDate.of(2026, 7, 30)

    private fun svc() = CalendarDayService(LedgerMoshi.standard())

    private fun root(): File = Files.createTempDirectory("day-guard").toFile()

    private fun fixtureBytes(): ByteArray {
        javaClass.classLoader?.getResourceAsStream("day-b.json")?.use { return it.readBytes() }
        for (f in listOf(File("../convergence-fixtures/day-b.json"), File("convergence-fixtures/day-b.json")))
            if (f.exists()) return f.readBytes()
        throw AssertionError("convergence fixture day-b.json not found")
    }

    private fun dayFile(root: File) = File(root, "calendar/2026/07/day-2026-07-30-v2.json")

    /** The fixture, installed as 2026-07-30's v2 day file. */
    private fun install(root: File, bytes: ByteArray = fixtureBytes()): File =
        dayFile(root).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }

    /** The fixture decoded once, directly, as the expectation the sites are checked against. */
    private fun fixtureDay(): CalendarDay =
        LedgerMoshi.standard().adapter(CalendarDay::class.java).fromJson(String(fixtureBytes()))!!

    private fun assertUntouched(file: File, before: ByteArray) =
        assertArrayEquals("the day file must be byte-identical", before, file.readBytes())

    // ── 1. The right Moshi, both ways the app gets one, reads a real day ──────────────────────

    @Test
    fun `the factory's Moshi and Hilt's Moshi both read the fixture day whole`() {
        val want = fixtureDay()
        for ((label, moshi) in listOf("LedgerMoshi.standard" to LedgerMoshi.standard(),
                                     "NetworkModule.provideMoshi" to NetworkModule.provideMoshi())) {
            val root = root(); install(root)
            val r = CalendarDayService(moshi).read(root, date)
            assertTrue("$label: $r", r is DayRead.Loaded)
            val day = (r as DayRead.Loaded).day
            assertEquals(label, want.ledgerItems.map { it.id }, day.ledgerItems.map { it.id })
            assertEquals(label, want.imageElements.map { it.elementId }, day.imageElements.map { it.elementId })
            assertEquals(label, want.readingEvents.map { it.id }, day.readingEvents.map { it.id })
            assertEquals(label, 4, day.ledgerItems.size)
        }
    }

    // ── 2. A misconfigured service cannot clobber a real day file ─────────────────────────────

    @Test
    fun `a misconfigured service reads the day as unreadable and writes nothing over it`() {
        val root = root()
        val file = install(root)
        val before = file.readBytes()
        val wrong = CalendarDayService(Moshi.Builder().build())   // no Locale / Date / UUID adapters

        assertTrue(wrong.read(root, date) is DayRead.Unreadable)
        assertNull("loadOrNull", wrong.loadOrNull(root, date, Locale.US))
        assertNull("the writer door refuses", wrong.loadForWrite(root, date, null, Locale.US))
        try {
            wrong.loadForWriteOrThrow(root, date, null, Locale.US)
            fail("loadForWriteOrThrow must throw on an unreadable day")
        } catch (e: CalendarDayService.UnreadableDayException) {
            assertEquals(file.name, e.file.name)
        }
        // A reader may show it empty…
        val shown = wrong.loadForDisplay(root, date, null, Locale.US)
        assertTrue(shown.ledgerItems.isEmpty() && shown.imageElements.isEmpty())
        // …and even a direct save of that empty day cannot land: save()'s own backstop refuses a
        // file whose last read was unreadable (and the JSON build would fail before the atomic move
        // anyway), so the real file is never replaced. Exception, not Throwable: the AssertionError
        // fail() throws must escape this catch, or the check could never fail.
        try {
            wrong.save(root, date, shown)
            fail("a service that cannot encode a day must not save one")
        } catch (e: Exception) { /* expected */ }
        assertUntouched(file, before)
        // The right service still reads every byte of it.
        assertEquals(4, svc().loadOrNull(root, date, Locale.US)!!.ledgerItems.size)
    }

    // ── 3. Every way a day turns unreadable, every writer door refuses ────────────────────────

    /** The fixture cut in half — a torn write. */
    private fun torn(): ByteArray = fixtureBytes().let { it.copyOf(it.size / 2) }

    /** The fixture with one reading event of a kind only iOS knows ("card"). Android's enum has no
     *  fallback, so the whole day fails to decode — and must not then be written over. */
    private fun withIosOnlyKind(): ByteArray {
        val o = org.json.JSONObject(String(fixtureBytes()))
        o.getJSONArray("readingEvents").getJSONObject(0).put("kind", "card")
        return o.toString().toByteArray()
    }

    /** [block] must throw the day-file guard's refusal — the exception that carries its words. */
    private fun refused(label: String, block: () -> Any?) {
        try {
            block(); fail("$label must refuse")
        } catch (e: CalendarDayService.UnreadableDayException) {
            assertTrue("$label: the words name the page: ${e.whichPage}", e.whichPage.endsWith("page"))
        }
    }

    private fun line(id: String) = ReadingEvent(id = id, kind = ReadingEvent.Kind.ARTICLE, date = Date(1_000L),
        title = "On Practice", url = "https://ashtanga.tech/on-practice", excerpt = "The passage.")

    @Test
    fun `a torn day and a newer app's enum are refused by every writer door, byte-identical after`() {
        for ((label, bytes) in listOf("torn" to torn(), "iOS-only enum" to withIosOnlyKind())) {
            val root = root()
            val file = install(root, bytes)
            val s = svc()

            assertTrue("$label: read", s.read(root, date) is DayRead.Unreadable)
            assertNull("$label: loadForWrite", s.loadForWrite(root, date, 7, Locale.US))
            try {
                s.loadForWriteOrThrow(root, date, 7, Locale.US); fail("$label: must throw")
            } catch (e: CalendarDayService.UnreadableDayException) { /* expected */ }
            // The editor's door refuses by throwing — never null, which means "not here".
            refused("$label: loadToEdit") { s.loadToEdit(root, date) }
            // The Synth landing (the -322 site) refuses through it, so its caller keeps the reply owed.
            refused("$label: synth landing") {
                SynthLanding.landInDayFile(s, root, date, "synthesize", listOf(SynthFace("x", "iVBORw0K", 0.5f)))
            }
            // The article marks' Log-line writers (merged after the guard) refuse the same way.
            refused("$label: log line add") { ReadingEventEdit.addIfAbsent(s, root, date, line("mfhl-1-aaaa")) }
            refused("$label: log line edit") { ReadingEventEdit.update(s, root, date, "mfhl-1-aaaa") { it.copy(note = "x") } }
            refused("$label: log line removal") { ReadingEventEdit.remove(s, root, date, "mfhl-1-aaaa") }
            refused("$label: removal record") { ReadingEventEdit.recordRemoval(s, root, date, listOf("mfhl-1-aaaa")) }
            refused("$label: mark card removal") { MarkCard.remove(s, root, date, UUID.randomUUID().toString()) }
            assertNull("$label: the null door", ReadingEventEdit.loadForWrite(s, root, date))
            // Readers show nothing and write nothing.
            assertTrue("$label: station", StationGrams.gather(s, root, listOf(date), Locale.US).isEmpty())
            assertTrue("$label: harvest pool", GramTopicHarvest.gather(s, root, "", today = date).isEmpty())
            assertTrue("$label: display", s.loadForDisplay(root, date, null, Locale.US).ledgerItems.isEmpty())

            assertUntouched(file, bytes)
        }
    }

    /**
     * The old v2→v1 fallback: a v2 file that won't read used to hand back a stale v1 copy sitting
     * beside it, and a writer then saved that v1 content over the v2. Unreadable v2 is unreadable.
     */
    @Test
    fun `an unreadable v2 never falls back to a stale v1 copy`() {
        val root = root()
        val v2 = install(root, torn())
        val v1 = File(v2.parentFile, "day-2026-07-30.json")
        v1.writeText("""{"year":2026,"month":7,"day":30,"locale":"en-US","strokes":[],"notesStrokes":[]}""")
        val s = svc()

        assertTrue(s.read(root, date) is DayRead.Unreadable)
        assertNull(s.loadOrNull(root, date, Locale.US))
        assertNull(s.loadForWrite(root, date, null, Locale.US))
        assertTrue("the v1 copy is still there", v1.exists())

        // With NO v2 at all, the v1 copy is the day, as it always was.
        v2.delete()
        assertTrue(s.read(root, date) is DayRead.Loaded)
    }

    @Test
    fun `no file, or an empty one, is a fresh day a writer may start`() {
        val s = svc()
        val root = root()
        assertTrue(s.read(root, date) is DayRead.Missing)
        val fresh = s.loadForWrite(root, date, 6, Locale.US)
        assertNotNull(fresh)
        assertEquals(6, fresh!!.startHour)

        // A blank file holds nothing to lose.
        install(root, ByteArray(0))
        assertTrue(s.read(root, date) is DayRead.Missing)
        assertNotNull(s.loadForWrite(root, date, 6, Locale.US))
    }

    // ── 3b. The writers merged in after the guard (article marks, Synth landings) ───────────────

    /**
     * Today that won't read refuses an article-mark write too (today is otherwise how a day begins —
     * a missing today IS made), and a past day that isn't here mints nothing (null / false, as the
     * article marks always did). The words name the page.
     */
    @Test
    fun `article-mark writers - today unreadable refuses, today missing is made, a missing past day is not`() {
        val s = svc()
        val root = root()
        val today = LocalDate.now()
        val todayFile = File(root, "calendar/%04d/%02d/day-%s-v2.json".format(today.year, today.monthValue, today))
        todayFile.parentFile!!.mkdirs(); todayFile.writeBytes(torn())
        val before = todayFile.readBytes()
        try {
            ReadingEventEdit.addIfAbsent(s, root, today, line("mfnote-1-aaaa")); fail("today unreadable must refuse")
        } catch (e: CalendarDayService.UnreadableDayException) {
            assertEquals("Couldn't save that note — today's page won't read", CalendarDayService.refusal(e, "save that note"))
        }
        assertUntouched(todayFile, before)

        todayFile.delete()
        assertTrue("a missing today is made", ReadingEventEdit.addIfAbsent(s, root, today, line("mfnote-1-aaaa")))
        assertTrue(s.loadOrNull(root, today, Locale.US)!!.readingEvents.any { it.id == "mfnote-1-aaaa" })

        val other = root()
        assertNull("a past day that isn't here", s.loadToEdit(other, date))
        assertTrue(!ReadingEventEdit.addIfAbsent(s, other, date, line("x")))
        assertNull(ReadingEventEdit.update(s, other, date, "x") { it })
        assertTrue(!s.exists(other, date))
    }

    /** The line's own day decides: a NEIGHBOUR that won't read is skipped (the line is found, or
     *  the removal recorded, on the day itself) — only the line's own day refuses. */
    @Test
    fun `an unreadable neighbour is skipped, the line's own day is edited`() {
        val s = svc()
        val root = root(); install(root)
        val prev = File(root, "calendar/2026/07/day-2026-07-29-v2.json").also { it.writeBytes(torn()) }
        val prevBefore = prev.readBytes()
        val id = fixtureDay().readingEvents.first().id
        assertEquals(date, ReadingEventEdit.update(s, root, date, id) { it.copy(note = "said later") })
        assertEquals("said later", s.loadOrNull(root, date, Locale.US)!!.readingEvents.first { it.id == id }.note)
        // Not on its own day nor a readable neighbour: recorded on its own day, not refused.
        assertTrue(!ReadingEventEdit.remove(s, root, date, "mfhl-9-iphone"))
        assertTrue(s.loadOrNull(root, date, Locale.US)!!.deletedElementIds.any { it.equals("mfhl-9-iphone", true) })
        assertUntouched(prev, prevBefore)
    }

    // ── 4. Each fixed site reads the fixture day ──────────────────────────────────────────────

    private fun live(day: CalendarDay): List<ImageElement> {
        val dead = day.deletedElementIds.toHashSet()
        return day.imageElements.filter { it.elementId.toString() !in dead }
    }

    /** CalendarWeek/Month/Quarter/Year stations (each had a bare service): the one shared gather. */
    @Test
    fun `the week, month, quarter and year stations read the fixture's grams`() {
        val root = root(); install(root)
        val got = StationGrams.gather(svc(), root, listOf(date.minusDays(1), date, date.plusDays(1)), Locale.US)
        val want = live(fixtureDay())
        assertTrue("the fixture carries grams", want.isNotEmpty())
        assertEquals(want.map { it.elementId }, got.map { it.elementId })
    }

    /** SurfaceFragment's 🌾 topic harvest (and LedgerDirectory's harvests through GramComposer):
     *  the gram pool reads the fixture day through the service it is handed. */
    @Test
    fun `the topic harvest pool reads the fixture's grams`() {
        val root = root(); install(root)
        val got = GramTopicHarvest.gather(svc(), root, excludeIdentity = "", today = date.plusDays(2))
        val want = fixtureDay().imageElements.filter {
            !it.decorative && (it.data.isNotBlank() || it.dataRef.isNotBlank())
        }
        assertTrue(want.isNotEmpty())
        assertEquals(want.map { it.elementId }.toSet(), got.map { it.elementId }.toSet())
    }

    /** The Synth landing (the -322 site itself), on a real day: lands, and keeps everything else. */
    @Test
    fun `the synth landing writes onto the fixture day and keeps all of it`() {
        val root = root(); install(root)
        val s = svc()
        val want = fixtureDay()
        val landed = SynthLanding.landInDayFile(s, root, date, "synthesize", listOf(SynthFace("reply", "iVBORw0K", 0.5f)))
        assertNotNull(landed)
        val after = s.loadOrNull(root, date, Locale.US)!!
        assertEquals(want.ledgerItems.map { it.id }, after.ledgerItems.map { it.id })
        assertEquals(want.readingEvents.map { it.id }, after.readingEvents.map { it.id })
        assertEquals(want.imageElements.size + 1, after.imageElements.size)
        assertEquals(want.noteStrokes.keys, after.noteStrokes.keys - "synthesize")
    }

    /** The writer door on a real day: the day itself, not a fresh one — and a save round-trips it. */
    @Test
    fun `the writer door hands back the fixture day and a save keeps every item`() {
        val root = root(); install(root)
        val s = svc()
        val day = s.loadForWrite(root, date, 9, Locale.US)!!
        assertEquals(4, day.ledgerItems.size)
        assertEquals("a loaded day keeps its own (null) hour, the seed is for new days", null, day.startHour)
        day.ledgerItems.add(day.ledgerItems.first().copy(id = "added-${UUID.randomUUID()}", date = Date()))
        s.save(root, date, day)
        assertEquals(5, s.loadOrNull(root, date, Locale.US)!!.ledgerItems.size)
    }

    // ── 5. The Drive sync's cloud-only install ────────────────────────────────────────────────

    private fun driveItem(version: String = "v2", baseName: String = "day-2026-07-30") =
        CalendarSyncItem(UUID.randomUUID(), "2026/07/", baseName, version, Date(), Date())

    /**
     * The Drive inventory is built by decoding every file, so an unreadable local day is missing
     * from it and its Drive copy looks "cloud-only".
     * [CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker] asks the one file the install
     * would write — the day's `-v2.json` — through the same read every writer trusts.
     */
    @Test
    fun `a cloud-only day installs over nothing or a blank file, never over one that won't read`() {
        val s = svc()
        val root = root()
        // Nothing there: install.
        assertNull(CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker(root, driveItem(), s))
        // A blank file holds nothing to lose — the install heals it, as it did before the guard.
        install(root, ByteArray(0))
        assertNull(CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker(root, driveItem(), s))
        // A torn file and an iOS-only enum are both blocked, whatever the Drive item's version.
        for (bytes in listOf(torn(), withIosOnlyKind())) {
            val file = install(root, bytes)
            for (v in listOf("v2", "v1")) {
                val b = CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker(root, driveItem(v), s)
                assertTrue("$v: $b", b is DayRead.Unreadable)
                assertEquals(file.canonicalPath, (b as DayRead.Unreadable).file.canonicalPath)
            }
        }
    }

    /** A readable local v2 that still looks cloud-only is a version mismatch (a stale v1 item on
     *  Drive): reported as Loaded, which the background pass skips rather than converting the v1
     *  over the day. */
    @Test
    fun `a readable local day blocks a stale v1 Drive item from being converted over it`() {
        val root = root(); install(root)
        val b = CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker(root, driveItem("v1"), svc())
        assertTrue("$b", b is DayRead.Loaded)
    }

    /** Only the v2 file is the install's target: a stale unreadable v1 `day-X.json` with no v2 does
     *  not block (save() only renames it to .backup), and non-day items are never blocked here. */
    @Test
    fun `a stale unreadable v1 file and non-day items do not block the install`() {
        val root = root()
        val v1 = File(root, "calendar/2026/07/day-2026-07-30.json").also { it.parentFile!!.mkdirs(); it.writeBytes(torn()) }
        assertTrue(svc().read(v1) is DayRead.Unreadable)
        assertNull(CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker(root, driveItem(), svc()))

        val week = File(root, "calendar/2026/week-2026-31-v1.json").also { it.parentFile!!.mkdirs(); it.writeText("{") }
        assertTrue(week.exists())
        val weekItem = CalendarSyncItem(UUID.randomUUID(), "2026/", "week-2026-31", "v1", Date(), Date())
        assertNull(CalendarGoogleDriveSyncPresenter.cloudOnlyInstallBlocker(root, weekItem, svc()))
    }

    // ── 6. The backstop inside save() ─────────────────────────────────────────────────────────

    /**
     * The writer that forgets: reads through a READER's door ([CalendarDayService.loadForDisplay]
     * or loadOrNull ?: fresh) and saves. save() itself refuses, because the last read of that file
     * was unreadable — the file stays byte-identical.
     */
    @Test
    fun `save refuses a file whose last read was unreadable, whoever calls it`() {
        for ((label, bytes) in listOf("torn" to torn(), "iOS-only enum" to withIosOnlyKind())) {
            val root = root()
            val file = install(root, bytes)
            val reader = svc()
            val shown = reader.loadForDisplay(root, date, null, Locale.US)
            val careless = svc()   // a different instance: the guard is process-wide
            try {
                careless.save(root, date, shown)
                fail("$label: save must refuse")
            } catch (e: CalendarDayService.UnreadableDayException) {
                assertEquals(file.name, e.file.name)
            }
            try {
                careless.save(root, date, (careless.loadOrNull(root, date, Locale.US)
                    ?: CalendarDay(2026, 7, 30, Locale.US, mutableListOf(), mutableListOf(), true, null)))
                fail("$label: save must refuse after a loadOrNull too")
            } catch (e: CalendarDayService.UnreadableDayException) { /* expected */ }
            assertUntouched(file, bytes)
        }
    }

    /** The guard lifts the moment there is nothing left to protect, or the file reads again. */
    @Test
    fun `the backstop lifts once the file reads again, or is gone or blank`() {
        val s = svc()
        val fresh = { CalendarDay(2026, 7, 30, Locale.US, mutableListOf(), mutableListOf(), true, null) }

        // Repaired in place (a sync installs good bytes) and read again: writable.
        run {
            val root = root(); install(root, torn())
            assertTrue(s.read(root, date) is DayRead.Unreadable)
            install(root)
            val day = s.loadForWrite(root, date, null, Locale.US)!!
            day.ledgerItems.add(day.ledgerItems.first().copy(id = "added-${UUID.randomUUID()}", date = Date()))
            s.save(root, date, day)
            assertEquals(5, s.loadOrNull(root, date, Locale.US)!!.ledgerItems.size)
        }
        // Set aside (the bytes moved somewhere safe), then written: nothing left to lose.
        run {
            val root = root(); val file = install(root, torn())
            assertTrue(s.read(root, date) is DayRead.Unreadable)
            assertTrue(file.renameTo(File(file.parentFile, file.name + ".corrupt")))
            s.save(root, date, fresh())
            assertTrue(s.read(root, date) is DayRead.Loaded)
        }
        // Emptied: nothing left to lose either.
        run {
            val root = root(); val file = install(root, torn())
            assertTrue(s.read(root, date) is DayRead.Unreadable)
            file.writeBytes(ByteArray(0))
            s.save(root, date, fresh())
            assertTrue(s.read(root, date) is DayRead.Loaded)
        }
    }

    // ── 7. What a refusal says ────────────────────────────────────────────────────────────────

    @Test
    fun `a refusal names the page, and other failures keep their own message`() {
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("today's page", CalendarDayService.pageName(today, today))
        assertEquals("tomorrow's page", CalendarDayService.pageName(today.plusDays(1), today))
        assertEquals("yesterday's page", CalendarDayService.pageName(today.minusDays(1), today))
        assertEquals("the Oct 3 page", CalendarDayService.pageName(LocalDate.of(2026, 10, 3), today))
        assertEquals("the Jul 30, 2025 page", CalendarDayService.pageName(LocalDate.of(2025, 7, 30), today))

        val e = CalendarDayService.UnreadableDayException(File("day-2020-01-02-v2.json"), null)
        assertEquals("Couldn't save the map — the Jan 2, 2020 page won't read",
            CalendarDayService.refusal(e, "save the map"))
        assertEquals("Couldn't x — that day's page won't read",
            CalendarDayService.refusal(CalendarDayService.UnreadableDayException(File("odd.json"), null), "x"))
        assertNull(CalendarDayService.refusal(java.io.IOException("disk full"), "save the map"))
        assertNull(CalendarDayService.refusal(null, "save the map"))
    }
}
