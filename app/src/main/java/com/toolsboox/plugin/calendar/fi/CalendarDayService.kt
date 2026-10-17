package com.toolsboox.plugin.calendar.fi

import com.squareup.moshi.Moshi
import okio.buffer
import okio.source
import com.toolsboox.plugin.calendar.da.v1.CalendarSyncItem
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import timber.log.Timber
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

/**
 * Calendar day data service.
 *
 * ── ONE WAY TO BUILD IT, AND IT IS ALWAYS READY (2026-10-12) ─────────────────────────────────────
 *
 * The Moshi used to be a FIELD (`@Inject lateinit var moshi`), with a public no-arg constructor
 * beside it, so `CalendarDayService()` compiled anywhere and threw on its first decode. On Android
 * -322 that is exactly what the detached Synth landing built: every load threw "lateinit property
 * moshi has not been initialized", the load read the throw as a corrupt day, and the reply never
 * landed. The Moshi is now a constructor value. There is no no-arg constructor to reach for:
 *  - Hilt builds it through the `@Inject` constructor (Moshi + application context);
 *  - code that lives outside Hilt (widgets, the player, the share sheet) calls [standalone];
 *  - JVM tests pass a Moshi (and no context) through the one-argument constructor.
 *
 * ── A DAY THAT WON'T READ IS NOT AN EMPTY DAY ──────────────────────────────────────────────────
 *
 * [read] tells the three cases apart — no file ([DayRead.Missing]), a file that would not become a
 * day ([DayRead.Unreadable]: a decode error, an unknown enum from a newer app, a torn write, an
 * 86 MB photo day that runs out of memory), and a day ([DayRead.Loaded]). Writers go through
 * [loadForWrite], which hands back null for an unreadable file so the caller cannot save a fresh
 * day over it; readers go through [loadForDisplay], which shows an empty day and logs at ERROR
 * (kept in the diagnostics ring). The old `load(root, date, hour, locale)` — the door that quietly
 * turned an unreadable file into a fresh day for anyone, writer or not — is gone, so every caller
 * had to choose.
 *
 * Under the call sites, [save] keeps its own guard (2026-10-16): a v2 file whose last [read] was
 * unreadable is not written over, whoever asks — it throws [UnreadableDayException] instead. So a
 * writer added later that reads through a reader's door and then saves fails loudly rather than
 * replacing the day. See [unreadableTargets].
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayService @Inject constructor(
    /**
     * The Moshi instance — constructor-bound, so a service that cannot decode a day cannot exist.
     */
    val moshi: Moshi,
    /**
     * The application context — the media-ref save pass, the widget poke and the start-hour
     * setting. Hilt always supplies it (a non-null binding into a nullable slot); null only on a
     * JVM bench (the one-argument constructor below).
     */
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context?,
) {

    /**
     * A context-free service for JVM unit tests: it reads and writes day files like the real one,
     * but skips the media-ref pass, the widget poke and the start-hour setting (no device). App
     * code uses [standalone] or injection.
     */
    @androidx.annotation.VisibleForTesting
    constructor(moshi: Moshi) : this(moshi, null)

    companion object {
        /**
         * Process-wide per-target-file locks for [save]'s write+rename — companion, because
         * services are minted freely (Hilt injects them per consumer, and the widget code
         * hand-wires its own instances), so an instance-level lock would only guard a writer
         * against itself. See the comment inside [save] for the tear this closes.
         */
        private val saveLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /**
         * THE BACKSTOP UNDER EVERY WRITER (2026-10-16 review). The `-v2.json` files [read] last
         * found unreadable, by absolute path. [save] refuses to write over one of them, so a future
         * writer that reads through [loadForDisplay] or `loadOrNull ?: CalendarDay(...)` and then
         * saves still cannot bring the data loss back — the call-site discipline is no longer the
         * only thing standing between an unreadable day and a fresh one saved over it.
         *
         * An entry clears when a later [read] of that file loads it (or finds it gone or blank), and
         * when [save] finds the file gone or emptied — nothing left to lose, so a repair that sets
         * the bytes aside first may install. Process-wide, like [saveLocks], because services are
         * minted per consumer and the reader that saw the bad file is rarely the writer.
         * Only v2 names are kept: [save] writes nothing else (a v1 file is renamed to `.backup`).
         */
        private val unreadableTargets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * The sentence a person reads when a write was refused because the day won't read —
         * "Couldn't <doing> — today's page won't read" — or null when [t] is some other failure
         * (which keeps its own message). [doing] is the verb phrase: "save the map", "check it off".
         */
        fun refusal(t: Throwable?, doing: String): String? =
            (t as? UnreadableDayException)?.let { "Couldn't $doing — ${it.whichPage} won't read" }

        /** "today's page", "tomorrow's page", "yesterday's page", or "the Oct 3 page". */
        fun pageName(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
            today -> "today's page"
            today.plusDays(1) -> "tomorrow's page"
            today.minusDays(1) -> "yesterday's page"
            else -> "the " + date.format(
                DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy", Locale.US)
            ) + " page"
        }

        @Volatile
        private var standaloneCache: CalendarDayService? = null

        /**
         * THE factory for code that lives outside Hilt — the home-screen widgets, the player
         * capture, the share sheet. The ledger's own Moshi ([com.toolsboox.ot.LedgerMoshi]) and the
         * application context, exactly what the injected copy carries; one per process.
         */
        fun standalone(context: android.content.Context): CalendarDayService =
            standaloneCache ?: synchronized(this) {
                standaloneCache ?: CalendarDayService(
                    com.toolsboox.ot.LedgerMoshi.standard(), context.applicationContext
                ).also { standaloneCache = it }
            }
    }

    /**
     * What is on disk for one day — the three answers a writer must never confuse.
     */
    sealed class DayRead {
        /** No day file (or only an empty one — nothing there to lose). A fresh day is right. */
        object Missing : DayRead()

        /**
         * A file with bytes that would not become a day. Shown empty to a reader; NEVER saved
         * over by a writer — the bytes may be the only copy of that day.
         */
        data class Unreadable(val file: File, val cause: Throwable?) : DayRead()

        /** The day. */
        data class Loaded(val day: CalendarDay) : DayRead()
    }

    /** A writer's refusal: [file] is there and would not read, so nothing was written over it. */
    class UnreadableDayException(val file: File, cause: Throwable?) :
        java.io.IOException("${file.name} is there but would not read — not writing over it", cause) {
        /** Which page that is, in words — see [pageName]; "that day's page" for a name with no date. */
        val whichPage: String
            get() = com.toolsboox.plugin.calendar.ot.PickingsCards.dateOf(file.name)?.let { pageName(it) }
                ?: "that day's page"
    }

    /**
     * Returns with the sync item of the data class.
     *
     * @param userId the user ID
     * @param calendarDay the data class
     * @return the calendar sync item data class
     */
    fun getItem(userId: UUID, calendarDay: CalendarDay): CalendarSyncItem {
        val year = "%04d".format(calendarDay.year)
        val month = "%02d".format(calendarDay.month)
        val day = "%02d".format(calendarDay.day)
        return CalendarSyncItem(userId, "$year/$month/", "day-$year-$month-$day", "v2", calendarDay.created, calendarDay.updated)
    }

    /**
     * Load the data class from the sync item.
     *
     * @param calendarSyncItem the calendar sync item
     * @return the data class
     */
    fun fromSyncItem(calendarSyncItem: CalendarSyncItem): CalendarDay? {
        if (!calendarSyncItem.baseName.startsWith("day-")) return null
        // A sync item can reach here with no content (unreadable local file, failed cloud
        // download) — degrade to null so the sync skips the item instead of crashing the
        // app from a background thread. Malformed JSON degrades the same way.
        val json = calendarSyncItem.json ?: return null

        runCatching {
            when (calendarSyncItem.version) {
                "v1" -> {
                    moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarDay::class.java)
                        .fromJson(json)?.let { return CalendarDay.convert(it) }
                }

                "v2" -> {
                    moshi.adapter(CalendarDay::class.java)
                        .fromJson(json)?.let { return it }
                }
            }
        }

        return null
    }

    /**
     * The day to WRITE to: the day on disk, a fresh day when there is none — and null when a file is
     * there that would not read. Null is the refusal: the caller must not save (and should say it
     * couldn't), because saving a fresh day + its one new item over that file is how a day is lost.
     * Every writer in the app comes through here; see the class comment.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param seedStartHour the start hour to stamp onto a day that does not exist yet — and ONLY
     *   onto such a day. A loaded day keeps whatever hour it was written with; see [loadOrNull].
     *   NULL means "I have no opinion", and the synced setting answers instead: two dozen callers
     *   here (starring an article, filing a letter, a Notebot task) mint today's file as a side
     *   effect of doing something else and have no business naming an hour, but the day they leave
     *   behind is still a real day that the day page will later open. Before this they wrote a null
     *   and the page drew off whatever the reader happened to default to.
     * @param locale the current locale
     * @return the day to mutate and save, or null when the file exists but wouldn't read
     */
    fun loadForWrite(rootPath: File, currentDate: LocalDate, seedStartHour: Int?, locale: Locale): CalendarDay? =
        try {
            loadForWriteOrThrow(rootPath, currentDate, seedStartHour, locale)
        } catch (e: UnreadableDayException) {
            null
        }

    /**
     * [loadForWrite] for a writer that already turns an exception into "couldn't save that" — the
     * card placements, the share sheet, the Notebot tools all run inside a `runCatching` whose
     * failure branch says so. Throws [UnreadableDayException] (an IOException) where [loadForWrite]
     * returns null.
     */
    fun loadForWriteOrThrow(rootPath: File, currentDate: LocalDate, seedStartHour: Int?, locale: Locale): CalendarDay =
        when (val r = read(rootPath, currentDate)) {
            is DayRead.Loaded -> r.day
            is DayRead.Missing -> fresh(currentDate, seedStartHour, locale)
            is DayRead.Unreadable -> {
                Timber.e(r.cause, "REFUSING to write ${r.file.name}: the file is there but would not read — not saving a fresh day over it")
                throw UnreadableDayException(r.file, r.cause)
            }
        }

    /**
     * The day to CHANGE, for a writer that only edits a day that EXISTS — re-face a card, change or
     * take off a Log line, land a reply on the page it was summoned from: the day, null when there
     * is no file (nothing to edit — and nothing is minted), and [UnreadableDayException] when there
     * is one that would not read. So a null here can only ever mean "not on this device", never a
     * fresh day. iOS's `loadDayToEdit`.
     */
    fun loadToEdit(rootPath: File, currentDate: LocalDate): CalendarDay? =
        when (val r = read(rootPath, currentDate)) {
            is DayRead.Loaded -> r.day
            is DayRead.Missing -> null
            is DayRead.Unreadable -> {
                Timber.e(r.cause, "REFUSING to edit ${r.file.name}: the file is there but would not read")
                throw UnreadableDayException(r.file, r.cause)
            }
        }

    /**
     * The day to SHOW: the day on disk, or a fresh empty day when there is none or it would not
     * read (the unreadable case is logged at ERROR by [read]). Never save what this returns — a
     * caller that saves is a writer and goes through [loadForWrite].
     */
    fun loadForDisplay(rootPath: File, currentDate: LocalDate, seedStartHour: Int?, locale: Locale): CalendarDay =
        (read(rootPath, currentDate) as? DayRead.Loaded)?.day ?: fresh(currentDate, seedStartHour, locale)

    /** A day that has never been written. */
    private fun fresh(currentDate: LocalDate, seedStartHour: Int?, locale: Locale): CalendarDay = CalendarDay(
        currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
        mutableListOf(), mutableListOf(), true, seedStartHour ?: settingStartHour()
    )

    /**
     * What is on disk for [currentDate]: the v2 file when it is there, the v1 file only when there
     * is no v2. A v2 file that won't read is [DayRead.Unreadable] — never a reason to fall back to a
     * stale v1 copy that a writer would then save over the v2.
     */
    fun read(rootPath: File, currentDate: LocalDate): DayRead {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val day = currentDate.format(DateTimeFormatter.ofPattern("dd"))
        return read(rootPath, "$year/$month/", "day-$year-$month-$day")
    }

    private fun read(rootPath: File, path: String, baseName: String): DayRead {
        val fullPath = File(rootPath, "calendar/$path")
        val v2 = read(File(fullPath, "$baseName-v2.json"))
        if (v2 !is DayRead.Missing) return v2
        return read(File(fullPath, "$baseName.json"))
    }

    /**
     * The synced start-hour setting, or null on a bench with no context (the JVM unit tests
     * construct this service by hand). Null there is the honest answer — a test has no device
     * setting — and the day it mints simply keeps the null it was asked for.
     */
    private fun settingStartHour(): Int? =
        appContext?.let { ctx ->
            runCatching { com.toolsboox.plugin.calendar.ot.PagePrefs.settingHour(ctx) }.getOrNull()
        }

    /**
     * Load the data class from JSON file on the specified path, or null when nothing loadable.
     *
     * Callers who must tell "no file yet" apart from "a file exists but wouldn't load" — the
     * distinction the read-only guard in CalendarDayPresenter lives on — use [read] or
     * [loadForWrite] (or pair this with [exists]); null here means either.
     *
     * THE DAY OWNS ITS OWN START HOUR, and this method is where that stopped being true. It used to
     * overwrite [CalendarDay.startHour] with the device's setting on every load, on the theory that
     * changing the spinner should retake days already opened at the old default. The theory is
     * wrong, and the cost was Michael's archive: the schedule grid is laid out FROM the start hour
     * while ink is stored in absolute page coordinates, so re-stamping a written day slides the
     * printed rows out from under unchanged handwriting — and because the value was rewritten on the
     * way IN, the next ordinary save (a pen stroke, a carried task) wrote the device's opinion back
     * to the file. Opening a day was enough to lose its hour. Of 124 synced days, only six still
     * carried the 7 he had chosen.
     *
     * So nothing is stamped here now. A day keeps what it was written with, forever; a day written
     * without an opinion keeps the null, and the renderers resolve it against the synced setting via
     * [com.toolsboox.plugin.calendar.ot.PagePrefs.startHourOf]. The setting seeds NEW days only —
     * see [load]'s `seedStartHour`.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param locale the current locale (unused for the start hour, kept for the call shape)
     * @return the data class, or null when no readable day file exists
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadOrNull(rootPath: File, currentDate: LocalDate, locale: Locale): CalendarDay? {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val day = currentDate.format(DateTimeFormatter.ofPattern("dd"))

        return (read(rootPath, "$year/$month/", "day-$year-$month-$day") as? DayRead.Loaded)?.day
    }

    /**
     * True when a day file (v2 or v1) exists on disk for this date — readable or not.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @return true when a file is present
     */
    fun exists(rootPath: File, currentDate: LocalDate): Boolean {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val day = currentDate.format(DateTimeFormatter.ofPattern("dd"))

        val fullPath = File(rootPath, "calendar/$year/$month/")
        return File(fullPath, "day-$year-$month-$day-v2.json").exists() ||
                File(fullPath, "day-$year-$month-$day.json").exists()
    }

    /**
     * Load the data class from JSON file on the specified path — the v2 file, or the v1 file when
     * there is no v2. Null when there is no day OR it would not read; a caller that saves after a
     * null must tell those apart with [read] / [exists].
     *
     * @param rootPath the root path
     * @param path the path
     * @param baseName the base name
     * @return the data class
     */
    fun load(rootPath: File, path: String, baseName: String): CalendarDay? =
        (read(rootPath, path, baseName) as? DayRead.Loaded)?.day

    /**
     * Load the data class from one day file: the day, or null when there is none or it would not
     * read (logged at ERROR by [read]). A caller that saves after a null uses [read] instead.
     *
     * @param item the file
     * @return optional data class instance
     */
    fun load(item: File): CalendarDay? = (read(item) as? DayRead.Loaded)?.day

    /**
     * Read one day file and say which of the three it is — see [DayRead].
     *
     * @param item the file
     */
    fun read(item: File): DayRead {
        if (!item.name.startsWith("day-")) return DayRead.Missing
        // Every answer about a v2 file updates the backstop [save] consults (see
        // [unreadableTargets]): unreadable goes in, anything else comes out.
        val r = readUnguarded(item)
        if (item.name.endsWith("-v2.json")) {
            if (r is DayRead.Unreadable) unreadableTargets.add(item.absolutePath)
            else unreadableTargets.remove(item.absolutePath)
        }
        return r
    }

    private fun readUnguarded(item: File): DayRead {
        if (!item.exists()) return DayRead.Missing

        // An empty or corrupt file (interrupted write, half-synced) must not blow up the
        // whole day load — Moshi throws EOFException on empty input, which used to surface
        // as the "check your network status" error and leave the page blank.
        // Throwable, not Exception. OutOfMemoryError is an Error, so it walked straight past a
        // `catch (e: Exception)` and killed the process — which is exactly what happened: one day
        // page held a 3150×4200 16-bit PNG photograph inline, 84 MB of base64 in an 86 MB file, and
        // readText's StringBuffer doubling asked for a 256 MB allocation. The Drive background sync
        // loads EVERY day file to build its inventory and runs on every page open, so the app died
        // seconds after launch, over and over — taking the WebDAV sync pass down with it each time
        // and making a data problem look like a flaky network.
        //
        // A day that won't read is [DayRead.Unreadable] — NOT "unwritten". It used to be returned
        // as null, the same null as "no file", and roughly forty writers then saved a fresh day
        // plus their one new item over the real file. Readers still render it empty (and this logs
        // at ERROR, which the diagnostics ring keeps); writers get null from [loadForWrite] and
        // refuse. The durable fix for the photo day is to stop putting megabytes of base64 inside
        // the day JSON at all; LedgerImageCodec now writes photographs as JPEG, and
        // externalize-on-save (below, in [save]) moves the rest out.
        val json = try {
            item.readText(Charsets.UTF_8)
        } catch (e: Throwable) {
            Timber.e(e, "UNREADABLE day file ${item.name} (${item.length()} bytes): could not read it — shown empty, never written over")
            return DayRead.Unreadable(item, e)
        }
        // Blank is "nothing there": no bytes to lose, so a writer may start the day fresh.
        if (json.isBlank()) {
            Timber.w("Empty day file ${item.name}; treating as unwritten")
            return DayRead.Missing
        }

        val day = try {
            Timber.i("Try to load from ${item.name}")
            if (item.absolutePath.endsWith("-v2.json")) {
                moshi.adapter(CalendarDay::class.java).fromJson(json)
            } else {
                moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarDay::class.java)
                    .fromJson(json)?.let { CalendarDay.convert(it) }
            }
        } catch (e: Throwable) {
            Timber.e(e, "UNREADABLE day file ${item.name} (${item.length()} bytes): would not decode — shown empty, never written over")
            return DayRead.Unreadable(item, e)
        } ?: run {
            Timber.e("UNREADABLE day file ${item.name}: decoded to nothing — shown empty, never written over")
            return DayRead.Unreadable(item, null)
        }

        // A removed Log line is never read back, even when an older build's merge left it in the
        // file beside its tombstone (CalendarDayMerger.liveLines). Every full reader — the Log and
        // its search, Your Year, the day page — comes through here, and a writer that loads then
        // saves sheds the stray line for good.
        day?.let { d ->
            val live = CalendarDayMerger.liveLines(d.readingEvents, d.deletedElementIds)
            if (live !== d.readingEvents) d.readingEvents = live.toMutableList()
        }

        // ── The backfill that costs nothing ───────────────────────────────────────────────────
        //
        // A card index has to cope with two things a save hook cannot see: the boards that already
        // existed before there was an index, and the day files `CalendarWebDavSyncService.writeLocal`
        // installs straight from downloaded bytes (deliberately — parsing tens of megabytes on a
        // Boox to decide whether to keep a download "would cost more than the sync"). Both leave a
        // day on disk that the index has never met.
        //
        // Rather than a start-up sweep, the repair rides the decodes that were happening anyway.
        // This method is the app's only full day decode, and it is called by everything that walks
        // history: opening a day page, the Drive sync's inventory pass, the "Bring in a picking"
        // gather over 120 days. The expensive part — reading and parsing the file — has already
        // been paid for by the caller; the index takes the result on its way past, and only when
        // its sidecar is older than the file. So the ledger backfills itself a day at a time,
        // always off the main thread (every one of those callers is), and never because a menu
        // opened. The explicit, bounded repair for a day you are asking about right now lives in
        // PickingsCards.backfill.
        runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards.dateOf(item.name)?.let {
                com.toolsboox.plugin.calendar.ot.PickingsCards.refreshIfStale(it, day, item)
                // The article pick index rides the same free backfill (Michael 2026-10-12: "gram
                // picks should highlight back too") — see ArticlePickIndex.
                com.toolsboox.plugin.calendar.ot.ArticlePickIndex.refreshIfStale(it, day, item)
            }
        }
        return DayRead.Loaded(day)
    }

    /**
     * The slimmest decode there is — only what [com.toolsboox.plugin.calendar.ot.ArticlePickIndex]
     * reads: each card's id, page, source, label and words; each Log line's id, kind, url, passage,
     * note and attachment names; the element tombstones. Streamed from the file (no whole-file
     * String), and Moshi skips the strokes and every base64 face without materialising them. Null on
     * any problem.
     *
     * EVERY FIELD NULLABLE (review 2026-10-12, #15): a literal JSON `null` in any one of them — a
     * line some writer saved with `"note":null` — threw on a non-null field and emptied that whole
     * day's index, silently, until the file changed again. The index reads `?: ""` instead.
     */
    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class DayLitePicks(
        val imageElements: List<PickLite?>? = emptyList(),
        val readingEvents: List<LineLite?>? = emptyList(),
        val deletedElementIds: List<String?>? = emptyList(),
    )

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class PickLite(
        val elementId: String? = "",
        val page: String? = "",
        val sourceLink: String? = "",
        val sourceLabel: String? = "",
        val cardText: String? = "",
        val decorative: Boolean? = false,
        val intakeKind: String? = "",
    )

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class LineLite(
        val id: String? = "",
        val kind: String? = "",
        val url: String? = "",
        val excerpt: String? = "",
        val source: String? = "",
        val note: String? = "",
        val attachments: List<IdLite?>? = emptyList(),
        val starred: Boolean? = false,
    )

    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class IdLite(val id: String? = "", val filename: String? = "")

    fun loadPicksSlice(item: File): DayLitePicks? {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return null
        return try {
            item.source().buffer().use { moshi.adapter(DayLitePicks::class.java).fromJson(it) }
        } catch (e: Throwable) {
            Timber.w(e, "Unreadable day file ${item.name} (picks-lite); skipping")
            null
        }
    }

    /**
     * A slim decode of a v2 day file — only the `ledgerItems`, so the Boards gather can walk every
     * day without paying to parse the (large) stroke arrays. Moshi ignores unknown keys, so the
     * strokes/elements simply aren't read. Mirrors iOS `DayLite`. Returns empty on any problem.
     */
    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class DayLiteTasks(
        val ledgerItems: List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> = emptyList(),
        // Deletion tombstones ride the same slim decode: deletedItemIds is the dedicated item
        // list (wire name shared with iOS); deletedElementIds also counts because pre-split
        // builds recorded item deletions there.
        val deletedItemIds: List<String> = emptyList(),
        val deletedElementIds: List<String> = emptyList(),
    )

    /** The raw tasks slice of a day file — items AND their deletion tombstones (Quick Wins
     *  needs both to retire a lineage whose newest copy was deleted). Null on any problem. */
    fun loadTasksSlice(item: File): DayLiteTasks? {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return null
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Throwable) {
            Timber.e(e, "UNREADABLE day file ${item.name} (slice): could not read it; skipping")
            return null
        }
        if (json.isBlank()) return null
        return try {
            moshi.adapter(DayLiteTasks::class.java).fromJson(json)
        } catch (e: Exception) {
            Timber.e(e, "UNREADABLE day file ${item.name} (lite); skipping")
            null
        }
    }

    /** The slice's tombstoned item ids, lowercased (Android UUIDs are lowercase, iOS UPPERCASE —
     *  a case-mismatched tombstone silently resurrects the item, same trap as the merge). */
    fun deadItemIds(slice: DayLiteTasks): Set<String> {
        val dead = HashSet<String>(slice.deletedItemIds.size + slice.deletedElementIds.size)
        slice.deletedItemIds.forEach { dead.add(it.lowercase()) }
        slice.deletedElementIds.forEach { dead.add(it.lowercase()) }
        return dead
    }

    fun loadLedgerItems(item: File): List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> {
        val slice = loadTasksSlice(item) ?: return emptyList()
        // Defensive: a tombstoned id must never surface as a live item, even when a partial
        // write or a pre-tombstone merge left the copy in the file alongside its tombstone.
        val dead = deadItemIds(slice)
        return if (dead.isEmpty()) slice.ledgerItems
        else slice.ledgerItems.filter { it.id.lowercase() !in dead }
    }

    /**
     * A slim decode for the LOG gather (Reply-with-Log picker, Ask corpus walks): reading
     * events + text elements + ledger items, WITHOUT the stroke arrays that dominate a day
     * file's bytes. The "All" range walks up to ~1000 files — full decodes there churn
     * memory for data the caller never reads.
     */
    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class DayLiteLog(
        val readingEvents: List<com.toolsboox.plugin.calendar.da.v2.ReadingEvent> = emptyList(),
        val textElements: List<com.toolsboox.da.TextElement> = emptyList(),
        val ledgerItems: List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> = emptyList(),
        /** Read only so a removed line stays out ([CalendarDayMerger.liveLines]). */
        val deletedElementIds: List<String> = emptyList(),
    )

    fun loadLogSlice(item: File): DayLiteLog? {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return null
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Throwable) {
            Timber.e(e, "UNREADABLE day file ${item.name} (slice): could not read it; skipping")
            return null
        }
        if (json.isBlank()) return null
        return try {
            moshi.adapter(DayLiteLog::class.java).fromJson(json)?.let { s ->
                val live = CalendarDayMerger.liveLines(s.readingEvents, s.deletedElementIds)
                if (live === s.readingEvents) s else s.copy(readingEvents = live)
            }
        } catch (e: Exception) {
            Timber.e(e, "UNREADABLE day file ${item.name} (log-lite); skipping")
            null
        }
    }

    /**
     * A slim decode for the CORPUS gather (Ask, Missed Connections, the Map — Roots and Sprouts
     * read through it too until they retired):
     * exactly what the corpus quotes — dates, reading events, typed text, A/V gram names,
     * tasks, device events — and none of what it doesn't. The stroke arrays and the base64
     * image payloads that dominate a day file's bytes are skipped by Moshi's reader instead
     * of being materialised, so walking a year of days no longer pays for a year of media.
     */
    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class DayLiteCorpus(
        val year: Int = 0,
        val month: Int = 1,
        val day: Int = 1,
        val events: List<com.toolsboox.plugin.calendar.da.v1.CalendarEvent> = emptyList(),
        val readingEvents: List<com.toolsboox.plugin.calendar.da.v2.ReadingEvent> = emptyList(),
        val textElements: List<com.toolsboox.da.TextElement> = emptyList(),
        val avGrams: List<com.toolsboox.da.Attachment> = emptyList(),
        val ledgerItems: List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> = emptyList(),
        /** Read only so a removed line stays out of the corpus ([CalendarDayMerger.liveLines]). */
        val deletedElementIds: List<String> = emptyList(),
    )

    fun loadCorpusSlice(item: File): DayLiteCorpus? {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return null
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Throwable) {
            Timber.e(e, "UNREADABLE day file ${item.name} (slice): could not read it; skipping")
            return null
        }
        if (json.isBlank()) return null
        return try {
            moshi.adapter(DayLiteCorpus::class.java).fromJson(json)?.let { s ->
                val live = CalendarDayMerger.liveLines(s.readingEvents, s.deletedElementIds)
                if (live === s.readingEvents) s else s.copy(readingEvents = live)
            }
        } catch (e: Exception) {
            Timber.e(e, "UNREADABLE day file ${item.name} (corpus-lite); skipping")
            null
        }
    }

    /**
     * Convert the data class to JSON.
     *
     * @param calendarDay the calendar day
     * @return the JSON
     */
    fun json(calendarDay: CalendarDay): String {
        val adapter = moshi.adapter(CalendarDay::class.java)
        return adapter.toJson(calendarDay)
    }

    /**
     * Save the data class to JSON file on the specified path.
     *
     * Throws [UnreadableDayException] (an IOException) when the target's last [read] found it
     * unreadable — see [unreadableTargets] — and nothing is written.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param calendarDay the data class
     */
    fun save(rootPath: File, currentDate: LocalDate, calendarDay: CalendarDay) {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val day = currentDate.format(DateTimeFormatter.ofPattern("dd"))

        calendarDay.created = calendarDay.created ?: Date.from(Instant.now())
        calendarDay.updated = Date.from(Instant.now())
        save(rootPath, "$year/$month/", "day-$year-$month-$day", calendarDay)
    }

    /**
     * A day this device does not hold, minted ONLY to carry removal markers ([ids] into
     * `deletedElementIds`) to the copy that does — a highlight's Log line or card removed here before
     * its day ever synced to this device (review 2026-10-12, #1: the removal must travel even when
     * the line isn't here yet).
     *
     * It DEFERS to every other copy of the day: `updated` is the epoch (not now — [save] would stamp
     * now), `created` and the start hour unset, nothing on it. Both merges take the NEWER copy's
     * day-level fields wholesale ([CalendarDayMerger.merge], `newer.deepCopy()`), so a carrier stamped
     * now would hand its blank day-level fields to the real day (the start-hour archive damage
     * [loadOrNull] documents); at the epoch the real day is always the newer one and the carrier adds
     * only its markers. Never called over an existing file — the caller checks [exists] first.
     */
    fun saveRemovalCarrier(rootPath: File, currentDate: LocalDate, ids: Collection<String>) {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val day = currentDate.format(DateTimeFormatter.ofPattern("dd"))
        val carrier = CalendarDay(currentDate.year, currentDate.monthValue, currentDate.dayOfMonth,
            Locale.getDefault(), mutableListOf(), mutableListOf(), true, null)
        carrier.deletedElementIds = ids.distinct().sorted().toMutableList()
        carrier.updated = Date(0L)
        save(rootPath, "$year/$month/", "day-$year-$month-$day", carrier)
    }

    fun save(rootPath: File, path: String, baseName: String, calendarDay: CalendarDay) {
        val fullPath = File(rootPath, "calendar/$path")
        fullPath.mkdirs()

        // ── Externalize-on-save (Phase W, WIRE-MEDIA-BY-REFERENCE.md) ─────────────────────────
        //
        // This is the same one-throat property the card index and the widget poke ride, applied
        // to pixels: every placement, transform, dedupe, board move and sync write-back funnels
        // through here, so externalizing at this seam covers all of them without touching a
        // single placement writer. Over-threshold inline base64 moves to the content-addressed
        // media store and the element keeps only its ref; blob-write failures leave the payload
        // inline (the day file stays the copy of record until the bytes are safely on disk).
        // The media dir comes from the app context — the same LedgerPaths root every resolver
        // reads — not from rootPath, so a ref written here is a ref the readers can find.
        // Guarded: an externalization surprise must cost that element its migration, never the
        // save. (In JVM tests appContext is null and the pass is exercised directly.)
        val ctx = appContext
        if (com.toolsboox.ot.LedgerMedia.EMIT_MEDIA_REFS && ctx != null) {
            runCatching {
                com.toolsboox.ot.LedgerMedia.externalizeDay(
                    calendarDay, com.toolsboox.ot.LedgerMedia.mediaDir(ctx)
                )
            }.onFailure { Timber.w(it, "media externalization failed for $baseName; saving inline") }
        }

        // Write to a temp file first, then atomically move it into place. A direct
        // write to the final name leaves a truncated, unparseable file if the
        // process is killed mid-write (low-memory kill, crash, battery death) —
        // and that corrupt file then gets pushed downstream by the sync worker.
        // The atomic rename guarantees the final file is always a complete day.
        //
        // TWO HARDENINGS on top of that, both learned from a torn today-file on one of Michael's
        // devices ("day file exists but wouldn't load"), 2026-08:
        //
        //  1. The temp name is UNIQUE PER SAVE. It used to be the one fixed name
        //     "$baseName-v2.json.tmp" — so two concurrent saves of the same day opened FileWriters
        //     on the SAME temp file and interleaved their bytes; worse, the first writer's
        //     ATOMIC_MOVE renamed that inode to the real name while the second writer's fd was
        //     still open on it, and an fd follows its inode through a rename — the second writer
        //     kept writing INTO THE INSTALLED TARGET. "Atomic" replaced the day with a tear.
        //     With a per-save suffix, no two writers can ever share a temp inode.
        //
        //  2. The write+rename runs under a PROCESS-WIDE PER-TARGET LOCK. Callers are supposed to
        //     hold DayLocks around their whole load→mutate→save (and the widget flow now never
        //     writes at all — it queues through TaskDoneQueue) — but this file is the last line,
        //     and the lock makes save() itself torn-write-proof even for a future writer that
        //     forgets the discipline. Everything is one process (no android:process in the
        //     manifest), so a JVM lock genuinely covers all writers. Concurrent same-day saves
        //     then serialize; last-writer-wins on content is a lost UPDATE (DayLocks' job), but
        //     never a lost FILE.
        val target = File(fullPath, "$baseName-v2.json")
        synchronized(saveLocks.getOrPut(target.absolutePath) { Any() }) {
            // The backstop: the last read of this file found bytes that would not become a day, so
            // this save — whoever it is from — would replace a day nobody has seen with whatever
            // this caller holds. Every writer is meant to refuse before it gets here
            // (loadForWrite); this is for the one that forgets. A file since emptied or removed
            // has nothing left to lose, so the entry just clears.
            if (target.absolutePath in unreadableTargets) {
                if (target.exists() && target.length() > 0L) {
                    Timber.e("REFUSING to save over ${target.name}: its last read would not decode — not writing over it")
                    throw UnreadableDayException(target, null)
                }
                unreadableTargets.remove(target.absolutePath)
            }
            // Sweep temp leavings of earlier saves of THIS day (a process killed mid-write
            // strands one). Deterministic prefix + suffix keeps the sweep exact; the sync
            // workers only pick up *-v2.json / *.json names, so strays never travel, but they
            // shouldn't accumulate either.
            fullPath.listFiles { f ->
                f.name.startsWith("$baseName-v2.json.") && f.name.endsWith(".tmp")
            }?.forEach { runCatching { it.delete() } }

            val temp = File(fullPath, "$baseName-v2.json.${System.nanoTime()}.tmp")
            // PrintWriter swallows IOExceptions — on a full disk it "succeeds" with a truncated
            // temp file, and the atomic move would then replace the good day with garbage.
            // checkError() surfaces the failure so we abort before the move.
            PrintWriter(FileWriter(temp)).use {
                it.write(json(calendarDay))
                it.flush()
                if (it.checkError()) {
                    temp.delete()
                    throw java.io.IOException("write failed (disk full?) for $baseName-v2.json")
                }
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                // ATOMIC_MOVE can be unsupported on some filesystems; fall back to a
                // plain replace, which is still safer than writing the target directly.
                Timber.w(e, "Atomic move unavailable for $baseName-v2.json; falling back to replace")
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // ── The placed-card index rides the save ──────────────────────────────────────────────
        //
        // THIS IS THE ONE THROAT. Every way a card can appear on, move around, or leave a board
        // ends here: the cross-surface "Send this pick to" chooser (PickingsPlacement.place), the
        // day page's own drag / resize / rotate / duplicate / delete / z-order / auto-arrange (all
        // of which funnel through SurfaceFragment.onImageElementsChanged → the day fragment →
        // CalendarDayPresenter.save), graduating an intake gram into its own board, "Add to
        // pickings", the synthesized-question groups placed from the reader and from mail, Ask's
        // answer cards, the intake removal, and the sync merge's write-back of a remote device's
        // placements. Instrumenting each of them separately would have been ten edits and one
        // eventual miss — and a card index that silently misses a path is worse than no index,
        // because the directory then shows a stale list with total confidence.
        //
        // It costs nothing that isn't already paid: the day object is in memory, having just been
        // serialised, and the index reads a dozen scalar fields off each element and touches not
        // one byte of the inline base64. The write itself is skipped when only strokes changed.
        // Guarded, because a save that succeeded must not be reported as failed over a sidecar.
        runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards.dateOf(baseName)?.let {
                com.toolsboox.plugin.calendar.ot.PickingsCards.record(it, calendarDay)
                // The article pick index — the same throat (ArticlePickIndex).
                com.toolsboox.plugin.calendar.ot.ArticlePickIndex.record(it, calendarDay)
            }
        }.onFailure { Timber.w(it, "card index update failed for $baseName") }

        // The home-screen widgets mirror TODAY's file, so the same one-throat property the card
        // index rides is the right place to tell them it changed — pen saves, gram placements,
        // starred-mail drops and sync write-backs all pass through here, and instrumenting each
        // separately would eventually miss one. Guarded to today's file: a sync merge rewriting
        // last March must not re-render eight widgets per historical day. DEBOUNCED, because the
        // day page saves per pen-up: a writing session used to broadcast a full ten-widget
        // redraw on every lifted pen. Trailing-edge, so the last save of the burst always
        // reaches the home screen; each provider still renders off this same JSON on its own
        // background thread.
        runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards.dateOf(baseName)?.let {
                if (it == LocalDate.now() && ctx != null) {
                    com.toolsboox.plugin.calendar.widget.CalendarWidgetProvider.refreshAllDebounced(ctx)
                }
            }
        }.onFailure { Timber.w(it, "widget refresh failed for $baseName") }

        // Try to rename the old v1 file to .backup. REPLACE_EXISTING: if a .backup already
        // exists this used to throw FileAlreadyExistsException AFTER the save succeeded,
        // crashing callers that treated save() as infallible.
        val source = File(fullPath, "$baseName.json")
        if (source.exists()) {
            runCatching {
                Files.move(
                    source.toPath(), source.toPath().resolveSibling("$baseName.json.backup"),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.onFailure { Timber.w(it, "v1 backup rename failed for $baseName.json") }
        }
    }
}