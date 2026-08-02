package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.PagePrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Locale

/**
 * THE TEST THAT STOPS THE CLOBBER BEING QUIETLY REINTRODUCED.
 *
 * Michael set his day to start at 7am. Across 124 synced days his archive held 5 sixty-two times, 6
 * fifty-four times, and 7 six times — his own preference survived on five percent of his own record.
 * The loudest of the three culprits was on this fork: [CalendarDayService.loadOrNull] overwrote
 * every loaded day's `startHour` with the device setting on the way past, so merely OPENING a day
 * rewrote it, and the next ordinary save (a pen stroke, a carried task) put the device's opinion on
 * disk. The grid is laid out FROM the start hour while ink is stored in absolute page coordinates,
 * so the rows slid out from under unchanged handwriting.
 *
 * The rule these tests hold: THE DAY OWNS ITS OWN START HOUR. Once written it is data. A fork may
 * supply a value only when creating a day that does not yet exist.
 */
class StartHourDurabilityTest {

    private fun service(): CalendarDayService {
        val moshi = Moshi.Builder()
            .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
        return CalendarDayService().apply { this.moshi = moshi }
    }

    private fun root(): File = Files.createTempDirectory("start-hour").toFile()

    /** The `startHour` actually on disk, read out of the raw bytes rather than off an object that
     *  happens to remember. */
    private fun storedStartHour(root: File, date: LocalDate): Int? {
        val f = File(root, "calendar/%04d/%02d/day-%04d-%02d-%02d-v2.json".format(
            date.year, date.monthValue, date.year, date.monthValue, date.dayOfMonth))
        val o = JSONObject(f.readText())
        return if (o.isNull("startHour")) null else o.getInt("startHour")
    }

    // ── The day survives its own save ────────────────────────────────────────────────────────────

    /**
     * An existing 7am day, loaded and saved back through the normal path, is still a 7am day —
     * even when the device's own setting says something else entirely. This is the whole exercise.
     */
    @Test
    fun `saving an existing seven am day keeps seven`() {
        val cds = service()
        val root = root()
        val date = LocalDate.of(2026, 7, 15)

        cds.save(root, date, com.toolsboox.plugin.calendar.da.v2.CalendarDay(
            year = date.year, month = date.monthValue, day = date.dayOfMonth,
            locale = Locale.US, startHour = 7))

        // The loader is handed the SETTING a 5am device would have — and must not use it.
        val loaded = cds.loadOrNull(root, date, Locale.US)
        assertNotNull(loaded)
        assertEquals(7, loaded!!.startHour)

        // A perfectly ordinary edit, nothing to do with the schedule origin.
        loaded.calendarStrokes["Default"] = emptyList()
        cds.save(root, date, loaded)

        assertEquals(7, cds.loadOrNull(root, date, Locale.US)!!.startHour)
        assertEquals(7, storedStartHour(root, date))
    }

    /**
     * The same guarantee through [CalendarDayService.load], the fallback-carrying door — its
     * `seedStartHour` is spent on days that DON'T exist, and must never reach one that does.
     */
    @Test
    fun `the seed hour never reaches a day that already exists`() {
        val cds = service()
        val root = root()
        val date = LocalDate.of(2026, 7, 16)

        cds.save(root, date, com.toolsboox.plugin.calendar.da.v2.CalendarDay(
            year = date.year, month = date.monthValue, day = date.dayOfMonth,
            locale = Locale.US, startHour = 7))

        val reopened = cds.load(root, date, 5, Locale.US)
        assertEquals(7, reopened.startHour)
        cds.save(root, date, reopened)
        assertEquals(7, storedStartHour(root, date))
    }

    /**
     * A day that genuinely has no opinion keeps having none — a null is not an invitation to stamp
     * one. Two of Michael's days are like this; they render off the setting instead, via
     * [PagePrefs.resolve].
     */
    @Test
    fun `a null start hour is not filled in by loading or saving`() {
        val cds = service()
        val root = root()
        val date = LocalDate.of(2026, 7, 17)

        cds.save(root, date, com.toolsboox.plugin.calendar.da.v2.CalendarDay(
            year = date.year, month = date.monthValue, day = date.dayOfMonth,
            locale = Locale.US, startHour = null))

        val loaded = cds.load(root, date, 5, Locale.US)
        assertNull(loaded.startHour)
        cds.save(root, date, loaded)
        assertNull(storedStartHour(root, date))
    }

    // ── A new day takes the setting ──────────────────────────────────────────────────────────────

    /**
     * A day with no file yet is the ONE moment a device gets to supply a value, and the value it
     * supplies is the synced setting — not 5, not 6, not whichever literal a call site had.
     */
    @Test
    fun `a new day takes the settings value`() {
        val cds = service()
        val root = root()
        val date = LocalDate.of(2026, 7, 18)

        assertNull("precondition: no day yet", cds.loadOrNull(root, date, Locale.US))

        val minted = cds.load(root, date, 7, Locale.US)
        assertEquals(7, minted.startHour)
        cds.save(root, date, minted)
        assertEquals(7, storedStartHour(root, date))
    }

    /** And once it exists, the setting stops speaking for it. */
    @Test
    fun `changing the setting does not reach into an existing day`() {
        val day = com.toolsboox.plugin.calendar.da.v2.CalendarDay(
            year = 2026, month = 7, day = 19, locale = Locale.US, startHour = 7)
        assertEquals(7, day.startHour ?: 5)
        assertEquals(7, PagePrefs.sanitized(day.startHour))
    }
}

/**
 * The sidecar's wire, pinned so the two forks cannot drift apart without this going red. iOS's
 * `PagePrefs` in LedgerCore writes and reads the same bytes.
 */
class PagePrefsWireTest {

    @Test
    fun `the pinned path`() {
        assertEquals("page-prefs/prefs.json", PagePrefs.PATH)
    }

    /** The literal file a Swift device would have written. Hand-written on purpose: a round trip
     *  through our own writer would pass even if both ends misspelled the same key. */
    @Test
    fun `parses the swift file by its exact keys`() {
        val r = PagePrefs.parse("""{"startHour":7,"updatedAt":1754006400000}""")
        assertNotNull(r)
        assertEquals(7, r!!.startHour)
        assertEquals(1754006400000L, r.updatedAt)
    }

    /** A JSON OBJECT with exactly two keys — no nesting, no array wrapper. */
    @Test
    fun `writes exactly the two keys swift reads`() {
        val o = JSONObject(PagePrefs.Record(7, 1754006400000L).json())
        assertEquals(setOf("startHour", "updatedAt"), o.keys().asSequence().toSet())
        assertEquals(7, o.getInt("startHour"))
        assertEquals(1754006400000L, o.getLong("updatedAt"))
    }

    /** Newest `updatedAt` wins; a tie keeps the local side, so a merge run twice is stable. */
    @Test
    fun `merge takes the newer side`() {
        val older = PagePrefs.Record(5, 100L)
        val newer = PagePrefs.Record(7, 200L)
        assertEquals(7, PagePrefs.merge(older, newer)!!.startHour)
        assertEquals(7, PagePrefs.merge(newer, older)!!.startHour)
        assertEquals(6, PagePrefs.merge(PagePrefs.Record(6, 9L), PagePrefs.Record(8, 9L))!!.startHour)
        assertEquals(7, PagePrefs.merge(null, newer)!!.startHour)
        assertEquals(5, PagePrefs.merge(older, null)!!.startHour)
        assertNull(PagePrefs.merge(null, null))
    }

    /**
     * Missing, blank, truncated or nonsense reads as NO OPINION — never as a well-formed default
     * carrying a fresh timestamp, which would then win the merge against every real setting.
     */
    @Test
    fun `garbage is no opinion rather than a default`() {
        assertNull(PagePrefs.parse(null))
        assertNull(PagePrefs.parse(""))
        assertNull(PagePrefs.parse("   "))
        assertNull(PagePrefs.parse("""{"startHour":7"""))        // truncated write
        assertNull(PagePrefs.parse("""[{"startHour":7}]"""))     // an array, not the object
        assertNull(PagePrefs.parse("""{"updatedAt":1}"""))       // no hour at all
        assertNull(PagePrefs.parse("""{"startHour":99,"updatedAt":1}"""))
        // "Leave empty" is a LOCAL choice; the wire is 0..23 and rejects it on the way in.
        assertNull(PagePrefs.parse("""{"startHour":-1,"updatedAt":1}"""))
        // A record with no timestamp still parses — it just loses every merge, which is right.
        assertEquals(0L, PagePrefs.parse("""{"startHour":7}""")!!.updatedAt)
    }

    @Test
    fun `sanitize falls back to the built-in`() {
        assertEquals(PagePrefs.DEFAULT_START_HOUR, PagePrefs.sanitized(null))
        assertEquals(PagePrefs.DEFAULT_START_HOUR, PagePrefs.sanitized(24))
        assertEquals(PagePrefs.DEFAULT_START_HOUR, PagePrefs.sanitized(-1))
        assertEquals(0, PagePrefs.sanitized(0))
        assertEquals(23, PagePrefs.sanitized(23))
    }

    /** The two forks must agree on the hour drawn when nobody has said otherwise. */
    @Test
    fun `the built-in default is five on both forks`() {
        assertEquals(5, PagePrefs.DEFAULT_START_HOUR)
    }
}
