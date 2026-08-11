package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass

/**
 * One book the FLEET has, whether or not this device carries it.
 *
 * One record per book in the synced manifest `books-index.json` (hub root — the file the
 * 2026-08-11 Listen/Library design names, Build B). The manifest is what makes the library a
 * fleet property rather than a device property: the files themselves live under `books/` on the
 * hub with their folder structure preserved, and every device compares this manifest against its
 * own shelf on its sync tick — fetching what policy allows, offering the rest as ghost cards.
 *
 * WIRE (byte-stable for the iOS twin and the Mac ledger — do not rename fields):
 * ```
 * [ { "id":        folder-qualified name + "#" + size — [ReadingState.keyFor], the one name the
 *                  whole fleet agrees on for a book,
 *     "name":      the filename, exactly as it sits in the folder,
 *     "folder":    shelf-relative folder path, "" for the shelf root,
 *     "size":      byte size — redundant with the id's tail, carried flat so a device deciding
 *                  whether to fetch doesn't parse keys to learn what the fetch costs,
 *     "hash":      sha1 of the bytes, lowercase hex. CHEAP INTEGRITY, NOT CRYPTO: it answers
 *                  "did the whole file arrive" after a fetch, nothing more,
 *     "addedBy":   the device that first pushed it (its settings name, else its model) — for the
 *                  shelf to say where a book came from, never for merge logic,
 *     "added":     epoch ms of the add,
 *     "deletedAt": tombstone, 0 = live } , … ]
 * ```
 *
 * There is deliberately NO separate `updated` field: the wire above is the ruled shape, and a
 * manifest record only ever changes by being added or being tombstoned. So the record's clock is
 * derived — [stamp], the newer of the two acts — and the merge compares on that. A re-add after a
 * delete (the book comes back to the fleet) writes a fresh `added` newer than the old `deletedAt`
 * and wins; an old device replaying a stale live record against a newer tombstone loses. Same
 * physics as [ReadingState.merge], one field fewer on the wire.
 *
 * What a tombstone MEANS is ruled and worth restating: it says "removed from the library", never
 * "delete this device's file". A device holding a local copy of a tombstoned book keeps it until
 * its own person confirms — the shelf asks "removed from the library — keep your copy?" and takes
 * either answer. No merge result ever reaches into local files.
 */
@JsonClass(generateAdapter = true)
data class LibraryBook(
    /** [ReadingState.keyFor] of the book — folder-qualified name + "#" + size in bytes. */
    var id: String = "",
    /** The filename as it sits in its folder. */
    var name: String = "",
    /** Shelf-relative folder path, "" at the shelf root. */
    var folder: String = "",
    /** Byte size — what a fetch costs, flat for the policy check. */
    var size: Long = 0L,
    /** sha1 of the bytes, lowercase hex; "" when the pusher couldn't read them. Integrity, not crypto. */
    var hash: String = "",
    /** Who pushed it — display only, never merge input. */
    var addedBy: String = "",
    /** Epoch ms of the add. */
    var added: Long = 0L,
    /** Delete tombstone (0 = live) so an id-union merge keeps deletes across devices. */
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    /**
     * The record's clock: the newer of its two possible acts. `added` and `deletedAt` are both
     * writes; whichever happened later is when this record last said something true.
     */
    val stamp: Long get() = maxOf(added, deletedAt)

    /**
     * Where the bytes live on the hub: `books/<folder>/<name>`, the shelf's folder structure
     * preserved so the hub tree IS the library tree — the Mac's mirror folder and Calibre both
     * read it as-is, nothing translated.
     */
    val remotePath: String
        get() = if (folder.isEmpty()) "books/$name" else "books/$folder/$name"

    companion object {
        /** The fleet-wide name of one book — delegated to [ReadingState.keyFor] so the manifest,
         *  the reading sidecar and the shelf cache can never drift on what a book is called. */
        fun keyFor(folder: String, name: String, sizeBytes: Long): String =
            ReadingState.keyFor(folder, name, sizeBytes)

        /**
         * Fold [remote] into [local] by id, the newer [stamp] winning — id-union with tombstones,
         * the house merge. A tombstone is just another write: a delete travels like an add and an
         * older live copy cannot resurrect it, while a genuinely newer re-add can.
         */
        fun merge(local: List<LibraryBook>, remote: List<LibraryBook>): MutableList<LibraryBook> {
            val byId = LinkedHashMap<String, LibraryBook>()
            for (l in local) byId[l.id] = l
            for (r in remote) {
                val cur = byId[r.id]
                if (cur == null || r.stamp > cur.stamp) byId[r.id] = r
            }
            return byId.values.toMutableList()
        }
    }
}
