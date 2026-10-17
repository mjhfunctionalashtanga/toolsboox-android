package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.ImageElement
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * The grams a WEEK / MONTH / QUARTER / YEAR station shows — the live (untombstoned) image elements
 * of the given days' files, in the order given.
 *
 * One read for all four station fragments (they each carried a copy of this loop, and each copy got
 * its own bare `CalendarDayService()` on -322: every day read as "corrupt" and the stations never
 * had a gram). READ-ONLY: a day that won't read is skipped (the service logs it at ERROR) and
 * nothing here is ever saved.
 */
object StationGrams {

    /**
     * @param dates the days to read, in display order
     * @param cap stop reading further days once this many grams are in hand (a day already read
     *   is kept whole, as the stations always did)
     */
    fun gather(
        service: CalendarDayService, root: File, dates: List<LocalDate>, locale: Locale,
        cap: Int = Int.MAX_VALUE,
    ): List<ImageElement> {
        val out = mutableListOf<ImageElement>()
        for (d in dates) {
            if (out.size >= cap) break
            val day = runCatching { service.loadOrNull(root, d, locale) }.getOrNull() ?: continue
            val dead = day.deletedElementIds.toHashSet()
            out += day.imageElements.filter { el -> el.elementId.toString() !in dead }
        }
        return out
    }
}
