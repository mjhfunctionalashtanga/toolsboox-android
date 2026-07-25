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
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> withDay(date: LocalDate, block: () -> T): T =
        synchronized(locks.getOrPut(date.toString()) { Any() }) { block() }
}
