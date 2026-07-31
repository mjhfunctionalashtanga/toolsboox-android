package com.toolsboox.plugin.calendar.fi

import com.squareup.moshi.Moshi
import com.toolsboox.plugin.calendar.da.v1.CalendarSyncItem
import com.toolsboox.plugin.calendar.da.v2.CalendarMonth
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
 * Calendar month data service.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarMonthService @Inject constructor() {
    /**
     * The Moshi instance.
     */
    @Inject
    lateinit var moshi: Moshi

    /**
     * Returns with the sync item of the data class.
     *
     * @param userId the user ID
     * @param calendarMonth the data class
     * @return the calendar sync item data class
     */
    fun getItem(userId: UUID, calendarMonth: CalendarMonth): CalendarSyncItem {
        val year = "%04d".format(calendarMonth.year)
        val month = "%02d".format(calendarMonth.month)
        return CalendarSyncItem(userId, "$year/$month/", "month-$year-$month", "v2", calendarMonth.created, calendarMonth.updated)
    }

    /**
     * Load the data class from the sync item.
     *
     * @param calendarSyncItem the calendar sync item
     * @return the data class
     */
    fun fromSyncItem(calendarSyncItem: CalendarSyncItem): CalendarMonth? {
        if (!calendarSyncItem.baseName.startsWith("month-")) return null

        when (calendarSyncItem.version) {
            "v1" -> {
                moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarMonth::class.java)
                    .fromJson(calendarSyncItem.json!!)?.let { return CalendarMonth.convert(it) }
            }

            "v2" -> {
                moshi.adapter(CalendarMonth::class.java)
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
     * @param locale the current locale
     */
    fun load(rootPath: File, currentDate: LocalDate, locale: Locale): CalendarMonth {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))
        val calendarMonth = CalendarMonth(currentDate.year, currentDate.monthValue, locale)

        return load(rootPath, "$year/$month/", "month-$year-$month") ?: calendarMonth
    }

    /**
     * Load the data class from JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param path the path
     * @param baseName the base name
     * @return the data class
     */
    fun load(rootPath: File, path: String, baseName: String): CalendarMonth? {
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
    fun load(item: File): CalendarMonth? {
        if (!item.exists()) return null
        if (!item.name.startsWith("month-")) return null

        // A corrupt or unreadable file must not throw out of the presenter — but the caller
        // falls back to a fresh empty month, and a later save would replace the file. So the
        // bytes are quarantined aside first: a truncated-but-recoverable month can be repaired
        // by hand, an overwritten one cannot. Throwable, not Exception — OutOfMemoryError is
        // an Error and walks straight past `catch (e: Exception)`.
        try {
            FileReader(item).use { fileReader ->
                Timber.i("Try to load from ${item.name}")
                if (item.absolutePath.endsWith("-v2.json")) {
                    moshi.adapter(CalendarMonth::class.java)
                        .fromJson(fileReader.readText())?.let { return it }
                } else {
                    moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarMonth::class.java)
                        .fromJson(fileReader.readText())?.let { return CalendarMonth.convert(it) }
                }
            }
        } catch (e: Throwable) {
            val quarantine = File(item.parentFile, "${item.name}.corrupt-${System.currentTimeMillis() / 1000}")
            Timber.w(e, "Corrupt ${item.name}; quarantining to ${quarantine.name}")
            runCatching { Files.move(item.toPath(), quarantine.toPath()) }
            return null
        }

        return null
    }

    /**
     * Convert the data class to JSON.
     *
     * @param calendarMonth the calendar month
     * @return the JSON
     */
    fun json(calendarMonth: CalendarMonth): String {
        val adapter = moshi.adapter(CalendarMonth::class.java)
        return adapter.toJson(calendarMonth)
    }

    /**
     * Save the data class to JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param calendarMonth the data class
     */
    fun save(rootPath: File, currentDate: LocalDate, calendarMonth: CalendarMonth) {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = currentDate.format(DateTimeFormatter.ofPattern("MM"))

        calendarMonth.created = calendarMonth.created ?: Date.from(Instant.now())
        calendarMonth.updated = Date.from(Instant.now())
        save(rootPath, "$year/$month/", "month-$year-$month", calendarMonth)
    }

    fun save(rootPath: File, path: String, baseName: String, calendarMonth: CalendarMonth) {
        val fullPath = File(rootPath, "calendar/$path")
        fullPath.mkdirs()

        // Write to a temp file first, then atomically move it into place. A direct write to the
        // final name leaves a truncated, unparseable file if the process is killed mid-write —
        // and month ink has no remote copy (day-only sync), so that would be the only copy gone.
        val target = File(fullPath, "$baseName-v2.json")
        val temp = File(fullPath, "$baseName-v2.json.tmp")
        // PrintWriter swallows IOExceptions — on a full disk it "succeeds" with a truncated
        // temp file. checkError() surfaces the failure so we abort before the move.
        PrintWriter(FileWriter(temp)).use {
            it.write(json(calendarMonth))
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

        // Try to rename the old file to .backup
        val source = File(fullPath, "$baseName.json")
        if (source.exists()) {
            Files.move(source.toPath(), source.toPath().resolveSibling("$baseName.json.backup"))
        }
    }
}