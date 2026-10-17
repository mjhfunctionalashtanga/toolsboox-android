package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass

/**
 * One SESSION of attention — how long, on what medium, at what thing. The record behind
 * Michael's 2026-08-27 stats ruling: "how much reading total, estimated time for reading, how
 * much time reading feeds, how much time email, how much time book, how much time comic etc."
 *
 * MEDIUM-GENERAL FROM DAY ONE, deliberately: one wire shape for every medium, so feeds and mail
 * sessions join later as call sites, never as wire changes. Session-per-row (not a running
 * total), so "time by medium/day/thing" is a pure aggregation over the file and no merge ever
 * has to add durations — a row is a fact that happened, and facts union.
 *
 * WIRE (`reading-sessions.json`, hub root — additive-stable for the iOS twin):
 * ```
 * [ { "id":        session uuid, minted at start — the union key,
 *     "medium":    "book" | "comic" | "feed" | "mail" | "listen" — the ruled vocabulary,
 *     "ref":       what was attended: the book's [ReadingState.keyFor] id, a feed url, a mail id,
 *     "device":    where — display grade, never merge input,
 *     "start":     epoch ms,
 *     "durationMs": how long, written once at close,
 *     "deletedAt": tombstone, 0 = live } , … ]
 * ```
 *
 * Merge: id-union, newest [stamp] wins per id — the house rule. Prune by age at sync
 * ([com.toolsboox.plugin.calendar.ot.ReadingSessionStore]), the MediaLogStore window.
 */
@JsonClass(generateAdapter = true)
data class ReadingSession(
    /** Session uuid — the union key; every row is its own fact. */
    var id: String = "",
    /** "book" | "comic" | "feed" | "mail" | "listen". */
    var medium: String = "",
    /** What was attended — a book id, a feed url, a mail id. */
    var ref: String = "",
    /** Which device — display only. */
    var device: String = "",
    /** Epoch ms the session began. */
    var start: Long = 0L,
    /** Length in ms, written at close. */
    var durationMs: Long = 0L,
    /** Tombstone (0 = live). */
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L
    val stamp: Long get() = maxOf(start + durationMs, deletedAt)

    companion object {
        const val BOOK = "book"
        const val COMIC = "comic"
        const val FEED = "feed"
        const val MAIL = "mail"
        const val LISTEN = "listen"

        /** Id-union, newest stamp wins — [LibraryBook.merge]'s physics. */
        fun merge(local: List<ReadingSession>, remote: List<ReadingSession>): MutableList<ReadingSession> {
            val byId = LinkedHashMap<String, ReadingSession>()
            for (l in local) byId[l.id] = l
            for (r in remote) {
                val cur = byId[r.id]
                if (cur == null || r.stamp > cur.stamp) byId[r.id] = r
            }
            return byId.values.toMutableList()
        }
    }
}
