package com.toolsboox.plugin.reader.ui

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * WHEN a book was opened — the history the shelf's period filter reads.
 *
 * Michael, 2026-08-05: "use the almanac nav at the top of the bookshelf and use that to filter
 * books opened on that filtered period at the top, then another listing order."
 *
 * That needs something the shelf did not have: a record of when you READ a thing, as opposed to
 * when the file changed. The old proxy was `File.setLastModified` on open — a hack that worked only
 * because the shelf was app-owned files. It cannot survive the library becoming a pointer: you
 * cannot stamp a file inside a declared tree, and you should not want to. Touching Michael's own
 * Calibre library to record that Ledger looked at something would make every book appear freshly
 * changed to Syncthing, and push a whole library across the mesh for a read.
 *
 * So opens are recorded HERE, keyed by the book's shelf name, and the library is left alone.
 */
object BookOpens {

    private const val PREFS = "ledger_book_opens"

    /** Newest-first opens, capped. A shelf history is for finding what you were reading lately —
     *  beyond a few dozen visits to one book the older ones answer no question anyone asks. */
    private const val MAX_PER_BOOK = 40

    /** Record that [name] was opened now. */
    fun record(context: Context, name: String, at: Long = System.currentTimeMillis()) {
        if (name.isBlank()) return
        val p = context.getSharedPreferences(PREFS, 0)
        val kept = (listOf(at) + opens(context, name)).distinct().sortedDescending().take(MAX_PER_BOOK)
        p.edit().putString(key(name), kept.joinToString(",")).apply()
    }

    /** Every recorded open of [name], newest first. */
    fun opens(context: Context, name: String): List<Long> =
        context.getSharedPreferences(PREFS, 0).getString(key(name), null)
            ?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.sortedDescending()
            ?: emptyList()

    /** When [name] was last opened, or null if never. */
    fun lastOpen(context: Context, name: String): Long? = opens(context, name).firstOrNull()

    /**
     * The books opened between [from] and [to] inclusive, most-recently-opened first.
     *
     * Takes the shelf's own [entries] rather than reading the store's whole keyset, so a book
     * opened once and since deleted from the library cannot haunt the list — the history is a fact
     * about reading, the shelf is the fact about what exists, and the shelf wins.
     */
    fun openedBetween(
        context: Context, entries: List<BookshelfSource.Entry>, from: LocalDate, to: LocalDate,
    ): List<BookshelfSource.Entry> {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return entries
            .mapNotNull { e ->
                val hit = opens(context, e.name).firstOrNull { it in start until end } ?: return@mapNotNull null
                e to hit
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** The day an epoch-millis open falls on, for grouping. */
    fun dayOf(at: Long): LocalDate =
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

    // Names carry slashes (a subfolder) and spaces; SharedPreferences keys tolerate both, but a
    // prefix keeps the store readable and makes a future migration greppable.
    private fun key(name: String) = "open:$name"
}
