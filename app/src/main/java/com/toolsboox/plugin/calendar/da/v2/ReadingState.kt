package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass

/**
 * Where you are in one book, fleet-wide.
 *
 * One record per book in the synced sidecar `reading-state.json` (hub root — the file the
 * 2026-08-11 Listen/Library design names). Both forks read through foliate, so the locator (a CFI
 * for EPUB, foliate's own position string otherwise) is portable: close the book on the Boox,
 * open it on the iPad at the same paragraph.
 *
 * WIRE (byte-stable for the iOS twin — do not rename fields):
 * ```
 * [ { "id":        folder-qualified name + "#" + size — see [keyFor],
 *     "locator":   the foliate CFI/position string, exactly as foliate reported it,
 *     "percent":   fraction read, 0.0–1.0 (foliate's own fraction, for shelf/almanac display),
 *     "updated":   epoch ms of the write, the merge's only clock,
 *     "deletedAt": tombstone, 0 = live } , … ]
 * ```
 *
 * Keyed by the shelf's folder-qualified name plus byte size — the same name+size discipline
 * [com.toolsboox.plugin.reader.ui.BookshelfSource.materialise] uses for its cache — because the
 * shelf is a POINTER at a synced folder: the path differs per device, but the folder structure and
 * the bytes are level everywhere, so `folder/name#size` is the one name the whole fleet agrees on.
 * Size rides in the key so a book REPLACED in the folder (a better scan, a fixed EPUB) starts its
 * own record rather than inheriting a locator minted against different bytes.
 *
 * Merge: newest [updated] wins per id, id-union with tombstones — the plain house rule
 * ([Connection.merge]); position has no monotonic flag because re-reading backwards is reading.
 *
 * Coexists with the older `reader/positions.json` ([ReaderPositionStore]) rather than replacing
 * it in place: devices on earlier builds keep reading and writing the old file, this one carries
 * the richer key and the percent, and the reader writes BOTH until the fleet has crossed over.
 */
@JsonClass(generateAdapter = true)
data class ReadingState(
    /** [keyFor] of the book — folder-qualified name + "#" + size in bytes. */
    var id: String = "",
    /** The foliate CFI/position string, verbatim. */
    var locator: String = "",
    /** Fraction read, 0.0–1.0. */
    var percent: Double = 0.0,
    var updated: Long = System.currentTimeMillis(),
    /** Delete tombstone (0 = live) so an id-union merge keeps deletes across devices. */
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    companion object {
        /**
         * The fleet-wide name of one book: its shelf-relative folder path, its filename, and its
         * byte size. `""` folder (a book at the shelf root) yields plain `name#size`.
         *
         * "#" as the joint because "/" already means folder inside the qualified name and a
         * filename cannot contain it — the key splits unambiguously on its LAST "#" if anything
         * ever needs to read it apart.
         */
        fun keyFor(folder: String, name: String, sizeBytes: Long): String =
            (if (folder.isEmpty()) name else "$folder/$name") + "#" + sizeBytes

        /**
         * Fold [remote] into [local] by id, newest write winning. A tombstone is just another
         * write, so a delete on one device travels like any edit and cannot be resurrected by an
         * older copy.
         */
        fun merge(local: List<ReadingState>, remote: List<ReadingState>): MutableList<ReadingState> {
            val byId = LinkedHashMap<String, ReadingState>()
            for (l in local) byId[l.id] = l
            for (r in remote) {
                val cur = byId[r.id]
                if (cur == null || r.updated > cur.updated) byId[r.id] = r
            }
            return byId.values.toMutableList()
        }
    }
}
