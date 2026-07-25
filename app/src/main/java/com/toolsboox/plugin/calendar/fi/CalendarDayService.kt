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
     * @param defaultStartHour the default start hour
     * @param locale the current locale
     */
    fun load(rootPath: File, currentDate: LocalDate, defaultStartHour: Int?, locale: Locale): CalendarDay {
        val calendarDay = CalendarDay(
            currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
            mutableListOf(), mutableListOf(), true, defaultStartHour
        )

        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val day = currentDate.format(DateTimeFormatter.ofPattern("dd"))

        val loadedCalendarDay = load(rootPath, "$year/$month/", "day-$year-$month-$day") ?: calendarDay
        // A concrete start-hour SETTING wins over whatever the day cached. It used to be the other
        // way — the day's saved value took precedence — so once a day had been opened at the old
        // 5am default, changing the setting to 7am never took on that day. When the caller passes
        // no setting (null), or "no fixed start" (< 0), the day keeps its own value.
        loadedCalendarDay.startHour =
            if (defaultStartHour != null && defaultStartHour >= 0) defaultStartHour
            else loadedCalendarDay.startHour ?: defaultStartHour

        return loadedCalendarDay
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
        val json = try {
            item.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "Could not read ${item.name}; treating as unwritten")
            return null
        }
        if (json.isBlank()) {
            Timber.w("Empty day file ${item.name}; treating as unwritten")
            return null
        }

        return try {
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
    }

    /**
     * A slim decode of a v2 day file — only the `ledgerItems`, so the Boards gather can walk every
     * day without paying to parse the (large) stroke arrays. Moshi ignores unknown keys, so the
     * strokes/elements simply aren't read. Mirrors iOS `DayLite`. Returns empty on any problem.
     */
    @com.squareup.moshi.JsonClass(generateAdapter = true)
    data class DayLiteTasks(val ledgerItems: List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> = emptyList())

    fun loadLedgerItems(item: File): List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> {
        if (!item.exists() || !item.name.startsWith("day-") || !item.absolutePath.endsWith("-v2.json")) return emptyList()
        val json = try { item.readText(Charsets.UTF_8) } catch (e: Exception) { return emptyList() }
        if (json.isBlank()) return emptyList()
        return try {
            moshi.adapter(DayLiteTasks::class.java).fromJson(json)?.ledgerItems ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "Corrupt day file ${item.name} (lite); skipping")
            emptyList()
        }
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