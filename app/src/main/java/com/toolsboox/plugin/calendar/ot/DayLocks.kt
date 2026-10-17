package com.toolsboox.plugin.calendar.ot

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-day locks for day-JSON load-modify-save cycles.
 *
 * Several writers touch the same day file: the open day page's per-pen-up save, background
 * placements (share-in link cards, Pickings placement), and starred-mail→to-do. Each does a
 * whole-file load→mutate→save, so two interleaved writers silently drop each other's items.
 * Wrap every such cycle in [withDay] so writes to one date serialize against each other.
 */
object DayLocks {
    @PublishedApi internal val locks = ConcurrentHashMap<String, Any>()

    // Inline (audit 09-10) so a caller's `return@withContext` works inside the block — the wrap has
    // to be a no-cost habit at every load→mutate→save site. NO suspend calls inside: the monitor
    // is a JVM lock, and the compiler's "suspension point inside a critical section" check is the
    // only thing standing between you and an IllegalMonitorStateException on resume.
    inline fun <T> withDay(date: LocalDate, block: () -> T): T =
        synchronized(locks.getOrPut(date.toString()) { Any() }) { block() }

    /** The lock for the day a `YYYY-MM-DD…` file belongs to; files that aren't a day run unlocked. */
    inline fun <T> withDayOf(file: java.io.File, block: () -> T): T {
        val m = DAY_IN_NAME.find(file.name) ?: return block()
        val date = runCatching { LocalDate.parse(m.value) }.getOrNull() ?: return block()
        return withDay(date, block)
    }

    @PublishedApi internal val DAY_IN_NAME = Regex("\\d{4}-\\d{2}-\\d{2}")
}
