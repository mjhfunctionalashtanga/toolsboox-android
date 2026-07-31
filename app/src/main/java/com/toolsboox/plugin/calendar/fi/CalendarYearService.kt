package com.toolsboox.plugin.calendar.fi

import com.squareup.moshi.Moshi
import com.toolsboox.plugin.calendar.da.v1.CalendarSyncItem
import com.toolsboox.plugin.calendar.da.v2.CalendarYear
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
 * Calendar year data service.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarYearService @Inject constructor() {
    /**
     * The Moshi instance.
     */
    @Inject
    lateinit var moshi: Moshi

    /**
     * Returns with the sync item of the data class.
     *
     * @param userId the user ID
     * @param calendarYear the data class
     * @return the calendar sync item data class
     */
    fun getItem(userId: UUID, calendarYear: CalendarYear): CalendarSyncItem {
        val year = "%04d".format(calendarYear.year)
        return CalendarSyncItem(userId, "$year/", "year-$year", "v2", calendarYear.created, calendarYear.updated)
    }

    /**
     * Load the data class from the sync item.
     *
     * @param calendarSyncItem the calendar sync item
     * @return the data class
     */
    fun fromSyncItem(calendarSyncItem: CalendarSyncItem): CalendarYear? {
        if (!calendarSyncItem.baseName.startsWith("year-")) return null

        when (calendarSyncItem.version) {
            "v1" -> {
                moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarYear::class.java)
                    .fromJson(calendarSyncItem.json!!)?.let { return CalendarYear.convert(it) }
            }

            "v2" -> {
                moshi.adapter(CalendarYear::class.java)
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
     * @return the data class
     */
    fun load(rootPath: File, currentDate: LocalDate, locale: Locale): CalendarYear {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))
        val calendarYear = CalendarYear(currentDate.year, locale)

        return load(rootPath, "$year/", "year-$year") ?: calendarYear
    }

    /**
     * Load the data class from JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param path the path
     * @param baseName the base name
     * @return the data class
     */
    fun load(rootPath: File, path: String, baseName: String): CalendarYear? {
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
    fun load(item: File): CalendarYear? {
        if (!item.exists()) return null
        if (!item.name.startsWith("year-")) return null

        // A corrupt or unreadable file must not throw out of the presenter — but the caller
        // falls back to a fresh empty year, and a later save would replace the file. So the
        // bytes are quarantined aside first: a truncated-but-recoverable year can be repaired
        // by hand, an overwritten one cannot. Throwable, not Exception — OutOfMemoryError is
        // an Error and walks straight past `catch (e: Exception)`.
        try {
            FileReader(item).use { fileReader ->
                Timber.i("Try to load from ${item.name}")
                if (item.absolutePath.endsWith("-v2.json")) {
                    moshi.adapter(CalendarYear::class.java)
                        .fromJson(fileReader.readText())?.let { return it }
                } else {
                    moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarYear::class.java)
                        .fromJson(fileReader.readText())?.let { return CalendarYear.convert(it) }
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
     * @param calendarYear the calendar year
     * @return the JSON
     */
    fun json(calendarYear: CalendarYear): String {
        val adapter = moshi.adapter(CalendarYear::class.java)
        return adapter.toJson(calendarYear)
    }

    /**
     * Save the data class to JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param calendarYear the data class
     */
    fun save(rootPath: File, currentDate: LocalDate, calendarYear: CalendarYear) {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))

        calendarYear.created = calendarYear.created ?: Date.from(Instant.now())
        calendarYear.updated = Date.from(Instant.now())
        save(rootPath, "$year/", "year-$year", calendarYear)
    }

    fun save(rootPath: File, path: String, baseName: String, calendarYear: CalendarYear) {
        val fullPath = File(rootPath, "calendar/$path")
        fullPath.mkdirs()

        // Write to a temp file first, then atomically move it into place. A direct write to the
        // final name leaves a truncated, unparseable file if the process is killed mid-write —
        // and year ink has no remote copy (day-only sync), so that would be the only copy gone.
        val target = File(fullPath, "$baseName-v2.json")
        val temp = File(fullPath, "$baseName-v2.json.tmp")
        // PrintWriter swallows IOExceptions — on a full disk it "succeeds" with a truncated
        // temp file. checkError() surfaces the failure so we abort before the move.
        PrintWriter(FileWriter(temp)).use {
            it.write(json(calendarYear))
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