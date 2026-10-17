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
    /**
     * "I want this one on the device" — the DOWNLOAD INTENT, and it rides here rather than beside
     * the file because it is a fact about the listener, not about a device's copy.
     *
     * Michael, 2026-08-23: "I want to be able to read the entry and then choose to download from
     * there. This will then be syncable across devices for when I open up Ledgable it'll download
     * it on another device and keep my location etc." Asking for an episode on the Boox is asking
     * for it everywhere, so the ask syncs and each device fetches its own copy when it next looks.
     *
     * NOT monotonic, unlike [done]: un-wanting an episode is a real thing to do, so newest write
     * wins. [done] stays monotonic because un-hearing is not.
     */
    var wantOffline: Boolean = false,
    /**
     * The enclosure URL, carried so the ASK IS SELF-SUFFICIENT.
     *
     * [id] is the episode PAGE, which is the right identity to merge on but is not something you
     * can download. Without this, a second device holding "he wants this one" would have to go
     * looking for the episode in its own feed cache to find out what file that means — and the
     * device most likely to be missing that cache entry is exactly the one that hasn't opened the
     * feed lately, which is the device the ask exists for.
     */
    var audioUrl: String = "",
    /** WHAT this is, beside where you are in it — title, show, artwork. Without them a device
     *  that lacked the feed entry named the episode by its file name ("strange namings with no
     *  provenance", Michael, 09-04). Additive with defaults; iOS carries the same three. */
    var title: String = "",
    var show: String = "",
    var artworkUrl: String = "",
    var updated: Long = System.currentTimeMillis(),
    /** Delete tombstone (0 = live) so an id-union merge keeps deletes across devices. */
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    /** A name a person can recognise: the title when the state carries one, else the file name
     *  dressed as well as it can be — see [humanize]. (09-07: "the *.mp3 naming nomenclature is
     *  not helpful for identification on the user end.") */
    val displayTitle: String get() = title.ifBlank { humanize(id.ifBlank { audioUrl }) }

    companion object {
        /** The URL's last path component with the extension and query gone, dashes/underscores to
         *  spaces, percent-escapes decoded; a bare id or hash names nothing and reads "Episode". */
        fun humanize(url: String): String {
            var last = runCatching { java.net.URI(url).path?.substringAfterLast('/') }.getOrNull()
                ?: url.substringAfterLast('/')
            last = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
            last = last.substringBefore('?')
            val dot = last.lastIndexOf('.')
            if (dot > 0 && last.length - dot <= 5) last = last.substring(0, dot)
            var s = last.replace(Regex("[-_+]+"), " ").replace(Regex("\\s+"), " ").trim()
            if (s.count { it.isLetter() } < 3) return "Episode"
            if (s == s.lowercase()) s = s.replaceFirstChar { it.uppercase() }
            return s
        }

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
         *
         * The NAMING fields ([title], [show], [artworkUrl], [audioUrl]) are filled, not raced: a
         * blank one on the winner takes the loser's value (2026-10-12, Listen artwork — a newer
         * heartbeat written before the show's cover was known, or an older build that never wrote
         * art, used to wipe the picture the other device had already stored). They describe the
         * episode, not the playhead, so a blank is "this copy didn't know", never "unset it".
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
                        val loser = if (winner === r) cur else r
                        // [done] is monotonic — once ANY copy has heard it, the merged record has.
                        // [wantOffline] is not: it follows the winner, so putting an episode back
                        // sticks the same way asking for it did.
                        val heard = if (!winner.done && (cur.done || r.done)) winner.copy(done = true) else winner
                        fillNames(heard, loser)
                    }
                }
            }
            return byId.values.toMutableList()
        }

        /** [winner] with each blank naming field taken from [other]; the same instance when
         *  nothing was missing (so an all-filled merge allocates nothing). */
        private fun fillNames(winner: ListenState, other: ListenState): ListenState {
            if (winner.title.isNotBlank() && winner.show.isNotBlank() &&
                winner.artworkUrl.isNotBlank() && winner.audioUrl.isNotBlank()) return winner
            return winner.copy(
                title = winner.title.ifBlank { other.title },
                show = winner.show.ifBlank { other.show },
                artworkUrl = winner.artworkUrl.ifBlank { other.artworkUrl },
                audioUrl = winner.audioUrl.ifBlank { other.audioUrl }
            )
        }
    }
}
