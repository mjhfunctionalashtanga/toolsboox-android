package com.toolsboox.plugin.calendar.fi

import com.squareup.moshi.Moshi
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

/**
 * Calendar pattern data service.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarPatternService @Inject constructor() {

    // Companion object
    companion object {

        // Mutex of mutual exclusion of the service.
        val mutex = Mutex()
    }

    /**
     * The Moshi instance.
     */
    @Inject
    lateinit var moshi: Moshi

    /**
     * Load the data class from JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param locale the current locale
     */
    fun load(rootPath: File, currentDate: LocalDate, locale: Locale): CalendarPattern {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))

        val path = File(rootPath, "calendar/$year/")

        val baseName = "pattern-$year"

        val calendarPattern = CalendarPattern(currentDate.year, locale).fill()

        load(File(path, "$baseName-v1.json"))?.let { return it }
        load(File(path, "$baseName.json"))?.let { return it }

        return calendarPattern
    }

    /**
     * Load the data class from JSON file on the specified path.
     *
     * @param item the file
     * @return optional data class instance
     */
    fun load(item: File): CalendarPattern? {
        if (item.exists()) {
            // A corrupt or unreadable file must not throw out of the presenter — but the caller
            // falls back to a fresh filled pattern, and a later save would replace the file. So
            // the bytes are quarantined aside first: a truncated-but-recoverable pattern can be
            // repaired by hand, an overwritten one cannot. Throwable, not Exception —
            // OutOfMemoryError is an Error and walks straight past `catch (e: Exception)`.
            try {
                FileReader(item).use { fileReader ->
                    Timber.i("Try to load from ${item.name}")
                    if (item.absolutePath.endsWith("-v1.json")) {
                        moshi.adapter(CalendarPattern::class.java)
                            .fromJson(fileReader.readText())?.let { return it }
                    } else {
                        moshi.adapter(CalendarPattern::class.java)
                            .fromJson(fileReader.readText())?.let { return it }
                    }
                }
            } catch (e: Throwable) {
                val quarantine = File(item.parentFile, "${item.name}.corrupt-${System.currentTimeMillis() / 1000}")
                Timber.w(e, "Corrupt ${item.name}; quarantining to ${quarantine.name}")
                runCatching { Files.move(item.toPath(), quarantine.toPath()) }
                return null
            }
        }

        return null
    }

    /**
     * Save the data class to JSON file on the specified path.
     *
     * @param rootPath the root path
     * @param currentDate the current date
     * @param calendarPattern the data class
     */
    fun save(rootPath: File, currentDate: LocalDate, calendarPattern: CalendarPattern) {
        val year = currentDate.format(DateTimeFormatter.ofPattern("yyyy"))

        val path = File(rootPath, "calendar/$year/")
        path.mkdirs()

        val baseName = "pattern-$year"

        Timber.i("Try to save of ${baseName}-v1.json to $path")
        // Write to a temp file first, then atomically move it into place. A direct write to the
        // final name leaves a truncated, unparseable file if the process is killed mid-write —
        // and the pattern has no remote copy (day-only sync), so that would be the only copy gone.
        val target = File(path, "$baseName-v1.json")
        val temp = File(path, "$baseName-v1.json.tmp")
        // PrintWriter swallows IOExceptions — on a full disk it "succeeds" with a truncated
        // temp file. checkError() surfaces the failure so we abort before the move.
        PrintWriter(FileWriter(temp)).use {
            val adapter = moshi.adapter(CalendarPattern::class.java)
            it.write(adapter.toJson(calendarPattern))
            it.flush()
            if (it.checkError()) {
                temp.delete()
                throw java.io.IOException("write failed (disk full?) for $baseName-v1.json")
            }
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // ATOMIC_MOVE can be unsupported on some filesystems; fall back to a
            // plain replace, which is still safer than writing the target directly.
            Timber.w(e, "Atomic move unavailable for $baseName-v1.json; falling back to replace")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // Try to rename the old file to .backup
        val source = File(path, "$baseName.json")
        if (source.exists()) {
            Files.move(source.toPath(), source.toPath().resolveSibling("$baseName.json.backup"))
        }
    }
}