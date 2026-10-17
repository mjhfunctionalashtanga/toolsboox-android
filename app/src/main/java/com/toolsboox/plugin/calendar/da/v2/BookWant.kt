package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass

/**
 * One book the fleet WANTS EVERYWHERE — the record behind Michael's 2026-08-27 ruling: "if
 * comics and books are downloaded to one device, it's downloaded to all devices."
 *
 * One record per book id in the synced sidecar `book-wants.json` (hub root, beside
 * `books-index.json`). A want is minted where reading intent already lives — a ghost tap, a book
 * open, an add — and the sync pass answers it from both sides: a device HOLDING the bytes uploads
 * them to the hub regardless of the carry-declared gate (a request is not a mirror), and every
 * device fetches a wanted book it lacks, bypassing [com.toolsboox.plugin.reader.ui.LibraryPolicy]
 * exactly as a tapped ghost does — the person asking IS the policy, and the want is the ask made
 * fleet-visible.
 *
 * WIRE (additive-stable for the iOS twin — do not rename fields):
 * ```
 * [ { "id":        [LibraryBook.keyFor] — the manifest's own book id,
 *     "wantedBy":  the device that asked (its settings name, else model) — display only,
 *     "at":        epoch ms of the ask (refreshed on a re-ask, which keeps a live want young),
 *     "deletedAt": tombstone, 0 = live } , … ]
 * ```
 *
 * Merge: newest [stamp] wins per id, id-union with tombstones — [LibraryBook.merge]'s physics.
 * Growth is bounded twice: a want is one small record per book actually touched (never the
 * 20GB backlog), and [com.toolsboox.plugin.reader.ui.BookWantsStore.prune] retires records
 * older than its keep window, the [com.toolsboox.plugin.calendar.ot.MediaLogStore] rule.
 */
@JsonClass(generateAdapter = true)
data class BookWant(
    /** [LibraryBook.keyFor] of the book — the manifest id, so the two files never disagree. */
    var id: String = "",
    /** Who asked — display grade, never merge input. */
    var wantedBy: String = "",
    /** Epoch ms of the (latest) ask. */
    var at: Long = 0L,
    /** Tombstone (0 = live) so an id-union merge keeps retirements across devices. */
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    /** The record's clock — the newer of its two acts, [LibraryBook.stamp]'s rule. */
    val stamp: Long get() = maxOf(at, deletedAt)

    companion object {
        /** Id-union, newest stamp wins — the house merge, [LibraryBook.merge] verbatim. */
        fun merge(local: List<BookWant>, remote: List<BookWant>): MutableList<BookWant> {
            val byId = LinkedHashMap<String, BookWant>()
            for (l in local) byId[l.id] = l
            for (r in remote) {
                val cur = byId[r.id]
                if (cur == null || r.stamp > cur.stamp) byId[r.id] = r
            }
            return byId.values.toMutableList()
        }
    }
}
