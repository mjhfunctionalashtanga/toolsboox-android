package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass

/**
 * Where you are in one episode, fleet-wide.
 *
 * One record per episode in the synced sidecar `listen-state.json` (hub root — the file the
 * 2026-08-11 Listen/Library design names). Subscriptions already sync (Miniflux IS the
 * subscription store); this is the half that didn't — pause a podcast on the Boox, pick it up on
 * the iPad at the same minute.
 *
 * WIRE (byte-stable for the iOS twin — do not rename fields):
 * ```
 * [ { "id":         the episode's entry URL — the same identity the player's ★ capture carries,
 *     "positionMs": playhead, milliseconds from the top,
 *     "durationMs": the whole episode, 0 when the player never learned it,
 *     "done":       heard — true once >95% played, and true FOREVER (monotonic, the calendar canon),
 *     "updated":    epoch ms of the write, the merge's only clock,
 *     "deletedAt":  tombstone, 0 = live } , … ]
 * ```
 *
 * Keyed by the entry URL rather than a minted UUID because the URL is the one name every device
 * already agrees on with no coordinator — it is what [com.toolsboox.ui.plugin.LedgerPlayer.Capture]
 * carries, what the star files, what Miniflux serves. Two devices that both play the episode
 * converge on one record, the same reasoning as [Connection.idFor].
 *
 * Merge: newest [updated] wins per id, EXCEPT [done], which only ever goes forward. "Heard" is a
 * fact about the listener, not about a device's copy of the file, and a stale device pushing an
 * old position must not un-hear an episode — the same rule the calendar merge holds for a task's
 * done flag.
 */
@JsonClass(generateAdapter = true)
data class ListenState(
    /** The episode's entry URL (falling back to the enclosure URL when the entry has none). */
    var id: String = "",
    var positionMs: Long = 0L,
    var durationMs: Long = 0L,
    var done: Boolean = false,
    var updated: Long = System.currentTimeMillis(),
    /** Delete tombstone (0 = live) so an id-union merge keeps deletes across devices. */
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    companion object {
        /** "Near the end" / "heard": the last 5% is credits and outro, not listening left to do. */
        const val DONE_NUMERATOR = 95L

        /** Below this there is nothing worth resuming TO — you'd land where you started anyway. */
        const val RESUME_MIN_MS = 10_000L

        /** Whether a playhead at [positionMs] of [durationMs] counts as having heard the episode.
         *  False when the duration was never learned — 95% of nothing is not a finish line. */
        fun doneAt(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs * 100 >= durationMs * DONE_NUMERATOR

        /**
         * Where to drop the playhead when this episode opens: the stored spot when it is worth
         * jumping to (>10s in, not near the end), else 0 — a finished episode restarts from the
         * top rather than resuming into the outro it already heard.
         */
        fun resumePointMs(state: ListenState?): Long {
            val s = state ?: return 0L
            if (s.isDeleted) return 0L
            if (s.positionMs <= RESUME_MIN_MS) return 0L
            if (doneAt(s.positionMs, s.durationMs)) return 0L
            return s.positionMs
        }

        /**
         * Fold [remote] into [local] by id, newest write winning — except [done], which is
         * monotonic: once ANY copy has heard the episode, the merged record has, whatever its
         * other fields say. A tombstone is just another write and travels the same way
         * ([Connection.merge]'s rule).
         */
        fun merge(local: List<ListenState>, remote: List<ListenState>): MutableList<ListenState> {
            val byId = LinkedHashMap<String, ListenState>()
            for (l in local) byId[l.id] = l
            for (r in remote) {
                val cur = byId[r.id]
                byId[r.id] = when {
                    cur == null -> r
                    else -> {
                        val winner = if (r.updated > cur.updated) r else cur
                        if (!winner.done && (cur.done || r.done)) winner.copy(done = true) else winner
                    }
                }
            }
            return byId.values.toMutableList()
        }
    }
}
