package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass

/**
 * ONE THING YOU CONSUMED, AND WHEN — the media log's record.
 *
 * Michael, 2026-08-23: "we should also have a read history, watch history. and listen history
 * available in the log/history section", and then the sentence that decided what this is:
 * "once we have that media log, I would be able to have the llm/ask make slides from the article,
 * or create critical thinking questions, or create pictures, or create a mind map, etc etc
 * (article, video, podcast...)."
 *
 * So this is not a list you look at. It is the INDEX OF A CORPUS YOU ACT ON, and every decision
 * below follows from that.
 *
 * ── IT POINTS, IT DOES NOT HOLD ────────────────────────────────────────────────────────────────
 *
 * No article body, no transcript. Both are already on disk, keyed by this very [id]
 * (`FeedCache.loadContent` / `loadTranscript`), and copying them here would mean two truths about
 * one article and a synced sidecar that grows without bound — a log you could not keep on a device
 * is not a log. The ⚗ door resolves the material at the moment it is asked for.
 *
 * ── WHY NOT [ListenState] ──────────────────────────────────────────────────────────────────────
 *
 * They share an [id] deliberately — the entry URL, so the two records line up per item — but they
 * do different jobs. ListenState is RESUMABLE STATE: where the playhead is, overwritten constantly,
 * and meaningless once you have finished. This is HISTORY: that you did this, and when. Folding
 * them would make "what did I read in July" a question about playhead positions.
 *
 * ── EVERYTHING, NOT JUST WHAT YOU KEPT ─────────────────────────────────────────────────────────
 *
 * His ruling, and the right one: the failure mode of recording everything is a long list, while the
 * failure mode of recording only what you starred is a log that does not contain the thing you are
 * looking for — which is precisely what a history is for. Age-pruning handles the length.
 *
 * Merge: newest [updated] wins per id, EXCEPT [done], which only ever goes forward, and [at], which
 * takes the LATER of the two — engaging with something on one device does not un-engage it on
 * another, and the honest answer to "when did I last read this" is the latest any copy saw.
 */
@JsonClass(generateAdapter = true)
data class MediaLogEntry(
    /** The entry URL — the same identity [ListenState] merges on, so the two line up per item. */
    var id: String = "",
    /** [READ], [WATCH] or [LISTEN]. The feed lens's own word for the thing, kept as the wire value
     *  so a lens and its history can never disagree about what kind something is. */
    var kind: String = READ,
    var title: String = "",
    /** The feed / show / channel it came from. */
    var source: String = "",
    /** Where it lives — kept beside the id because the id may BE the url, and a log row has to be
     *  able to open the thing even when the entry has long since aged out of every feed cache. */
    var url: String = "",
    /**
     * The Miniflux entry id — THE KEY THE MATERIAL IS FILED UNDER.
     *
     * [id] is the entry URL, which is the right thing to MERGE on (stable across devices, and the
     * identity ListenState already uses) but is not what the caches are keyed by: `FeedCache`
     * stores the readable copy and the transcript under the numeric entry id. Without this, the ⚗
     * door would have to go looking for the entry by URL in whatever the feed cache happens to
     * still hold — and the row most likely to have aged out of that cache is the OLD one, which is
     * exactly the row you are reaching into the log to find.
     *
     * 0 for synthetic rows that never had one; the material resolver then simply finds nothing,
     * which is the honest outcome rather than a wrong article.
     */
    var feedId: Long = 0L,
    /** When you last engaged with it (epoch millis). The log's sort key and its date grouping. */
    var at: Long = 0L,
    var done: Boolean = false,
    var updated: Long = System.currentTimeMillis(),
    /** Delete tombstone (0 = live) so an id-union merge keeps deletes across devices. */
    var deletedAt: Long = 0L,
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    companion object {
        const val READ = "read"
        const val WATCH = "watch"
        const val LISTEN = "listen"

        /** The three kinds, in the order the feed lenses wear them (The Read · The Watch · The
         *  Listen) — one vocabulary, so the log's filter and the drawer cannot drift. */
        val KINDS = listOf(READ, WATCH, LISTEN)

        fun glyph(kind: String): String = when (kind) {
            WATCH -> "📺"
            LISTEN -> "🎧"
            else -> "📖"
        }

        fun label(kind: String): String = when (kind) {
            WATCH -> "Watched"
            LISTEN -> "Listened"
            else -> "Read"
        }

        /**
         * Fold [remote] into [local] by id, newest write winning — except [done] (monotonic: once
         * ANY copy finished it, the merged record has) and [at] (the LATER of the two: engaging on
         * one device does not un-engage it on another).
         */
        fun merge(local: List<MediaLogEntry>, remote: List<MediaLogEntry>): MutableList<MediaLogEntry> {
            val byId = LinkedHashMap<String, MediaLogEntry>()
            for (l in local) byId[l.id] = l
            for (r in remote) {
                val cur = byId[r.id]
                byId[r.id] = when {
                    cur == null -> r
                    else -> {
                        val winner = if (r.updated > cur.updated) r else cur
                        winner.copy(
                            done = winner.done || cur.done || r.done,
                            at = maxOf(cur.at, r.at),
                        )
                    }
                }
            }
            return byId.values.toMutableList()
        }
    }
}
