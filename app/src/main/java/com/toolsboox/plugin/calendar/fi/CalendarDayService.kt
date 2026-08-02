package com.toolsboox.plugin.calendar.fi

import com.squareup.moshi.Moshi
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
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayService @Inject constructor() {
    /**
     * The Moshi instance.
     */
    @Inject
    lateinit var moshi: Moshi

    /**
     * The application context — only for the home-screen widget poke below. Injected as a field
     * like [moshi] so the no-arg constructor (and every existing call site) stays untouched.
     */
    @Inject
    @dagger.hilt.android.qualifiers.ApplicationContext
    lateinit var appContext: android.content.Context

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

        when (calendarSyncItem.version) {
            "v1" -> {
                moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarDay::class.java)
                    .fromJson(calendarSyncItem.json!!)?.let { return CalendarDay.convert(it) }
            }

            "v2" -> {
                moshi.adapter(CalendarDay::class.java)
                    .fromJson(calendarSyncItem.json!!)?.let { return it }
            }
        }

        return null
    }

    /**
     * Load the data class from JSON file on the specified path.
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
     */
    fun load(rootPath: File, currentDate: LocalDate, seedStartHour: Int?, locale: Locale): CalendarDay {
        return loadOrNull(rootPath, currentDate, locale) ?: CalendarDay(
            currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
            mutableListOf(), mutableListOf(), true, seedStartHour ?: settingStartHour()
        )
    }

    /**
     * The synced start-hour setting, or null on a bench with no context (the JVM unit tests
     * construct this service by hand). Null there is the honest answer — a test has no device
     * setting — and the day it mints simply keeps the null it was asked for.
     */
    private fun settingStartHour(): Int? =
        if (::appContext.isInitialized)
            runCatching { com.toolsboox.plugin.calendar.ot.PagePrefs.settingHour(appContext) }.getOrNull()
        else null

    /**
     * Load the data class from JSON file on the specified path, or null when nothing loadable.
     *
     * Callers who must tell "no file yet" apart from "a file exists but wouldn't load" — the
     * distinction the read-only guard in CalendarDayPresenter lives on — pair this with [exists],
     * instead of taking [load]'s fresh-day fallback and losing the difference.
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

        return load(rootPath, "$year/$month/", "day-$year-$month-$day")
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
     * Load the data class from JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param path the path
     * @param baseName the base name
     * @return the data class
     */
    fun load(rootPath: File, path: String, baseName: String): CalendarDay? {
        val fullPath = File(rootPath, "calendar/$path")

        load(File(fullPath, "$baseName-v2.json"))?.let { return it }
        load(File(fullPath, "$baseName.json"))?.let { return it }

        return null
    }

    /**
     * Load the data class from JSON file on the specified path.
     *
     * @param item the file
     * @return optional data class instance
     */
    fun load(item: File): CalendarDay? {
        if (!item.exists()) return null
        if (!item.name.startsWith("day-")) return null

        // An empty or corrupt file (interrupted write, half-synced) must not blow up the
        // whole day load — Moshi throws EOFException on empty input, which used to surface
        // as the "check your network status" error and leave the page blank. Treat any
        // unreadable/blank/corrupt day file as "unwritten" so the caller falls back to a
        // fresh empty day and the page still renders.
        // Throwable, not Exception. OutOfMemoryError is an Error, so it walked straight past a
        // `catch (e: Exception)` and killed the process — which is exactly what happened: one day
        // page held a 3150×4200 16-bit PNG photograph inline, 84 MB of base64 in an 86 MB file, and
        // readText's StringBuffer doubling asked for a 256 MB allocation. The Drive background sync
        // loads EVERY day file to build its inventory and runs on every page open, so the app died
        // seconds after launch, over and over — taking the WebDAV sync pass down with it each time
        // and making a data problem look like a flaky network.
        //
        // A day too large to read is treated as unwritten, the same as a corrupt one. That is the
        // least-bad answer available here (see the caller: it renders a fresh empty day), and it is
        // strictly better than dying — but it is NOT harmless, because a save over that empty day
        // would discard the file's real content. That save is what the read-only guard in
        // CalendarDayPresenter now blocks: a day whose file EXISTS but wouldn't load renders blank
        // and refuses every save until a load succeeds. The durable fix is to stop putting
        // megabytes of base64 inside the day JSON at all; LedgerImageCodec now writes photographs
        // as JPEG, and externalising blobs entirely is the follow-on.
        val json = try {
            item.readText(Charsets.UTF_8)
        } catch (e: Throwable) {
            Timber.w(e, "Could not read ${item.name} (${item.length()} bytes); treating as unwritten")
            return null
        }
        if (json.isBlank()) {
            Timber.w("Empty day file ${item.name}; treating as unwritten")
            return null
        }

        val day = try {
            Timber.i("Try to load from ${item.name}")
            if (item.absolutePath.endsWith("-v2.json")) {
                moshi.adapter(CalendarDay::class.java).fromJson(json)
            } else {
                moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarDay::class.java)
                    .fromJson(json)?.let { CalendarDay.convert(it) }
            }
        } catch (e: Exception) {
            Timber.w(e, "Corrupt day file ${item.name}; treating as unwritten")
            null
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
        if (day != null) runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards.dateOf(item.name)?.let {
                com.toolsboox.plugin.calendar.ot.PickingsCards.refreshIfStale(it, day, item)
            }
        }
        return day
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
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Exception) { return null }
        if (json.isBlank()) return null
        return try {
            moshi.adapter(DayLiteTasks::class.java).fromJson(json)
        } catch (e: Exception) {
            Timber.w(e, "Corrupt day file ${item.name} (lite); skipping")
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
    )

    fun loadLogSlice(item: File): DayLiteLog? {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return null
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Exception) { return null }
        if (json.isBlank()) return null
        return try {
            moshi.adapter(DayLiteLog::class.java).fromJson(json)
        } catch (e: Exception) {
            Timber.w(e, "Corrupt day file ${item.name} (log-lite); skipping")
            null
        }
    }

    /**
     * A slim decode for the CORPUS gather (Ask, Roots, Sprouts, Missed Rhizomes, the Map):
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
    )

    fun loadCorpusSlice(item: File): DayLiteCorpus? {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return null
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Exception) { return null }
        if (json.isBlank()) return null
        return try {
            moshi.adapter(DayLiteCorpus::class.java).fromJson(json)
        } catch (e: Exception) {
            Timber.w(e, "Corrupt day file ${item.name} (corpus-lite); skipping")
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
        // save. (In JVM tests appContext is uninitialized and the pass is exercised directly.)
        if (com.toolsboox.ot.LedgerMedia.EMIT_MEDIA_REFS && ::appContext.isInitialized) {
            runCatching {
                com.toolsboox.ot.LedgerMedia.externalizeDay(
                    calendarDay, com.toolsboox.ot.LedgerMedia.mediaDir(appContext)
                )
            }.onFailure { Timber.w(it, "media externalization failed for $baseName; saving inline") }
        }

        // Write to a temp file first, then atomically move it into place. A direct
        // write to the final name leaves a truncated, unparseable file if the
        // process is killed mid-write (low-memory kill, crash, battery death) —
        // and that corrupt file then gets pushed downstream by the sync worker.
        // The atomic rename guarantees the final file is always a complete day.
        val target = File(fullPath, "$baseName-v2.json")
        val temp = File(fullPath, "$baseName-v2.json.tmp")
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

        // ── The placed-card index rides the save ──────────────────────────────────────────────
        //
        // THIS IS THE ONE THROAT. Every way a card can appear on, move around, or leave a board
        // ends here: the cross-surface "Send this gram to" chooser (PickingsPlacement.place), the
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
            }
        }.onFailure { Timber.w(it, "card index update failed for $baseName") }

        // The home-screen widgets mirror TODAY's file, so the same one-throat property the card
        // index rides is the right place to tell them it changed — pen saves, gram placements,
        // starred-mail drops and sync write-backs all pass through here, and instrumenting each
        // separately would eventually miss one. Guarded to today's file: a sync merge rewriting
        // last March must not re-render eight widgets per historical day. A cheap broadcast —
        // each provider re-renders off this same JSON on its own background thread.
        runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards.dateOf(baseName)?.let {
                if (it == LocalDate.now() && ::appContext.isInitialized) {
                    com.toolsboox.plugin.calendar.widget.CalendarWidgetProvider.refreshAll(appContext)
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