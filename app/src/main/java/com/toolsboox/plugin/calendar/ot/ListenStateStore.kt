package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.ListenState
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * Per-episode playhead, in one synced sidecar (`listen-state.json` at the hub root), id-union
 * merged like [BoardsStore]. Wire shape documented on [ListenState]; wire-compatible with the
 * future iOS twin.
 *
 * Who writes here: [com.toolsboox.ui.plugin.LedgerPlayer], for any track that carries a capture
 * identity (a podcast episode). Tracks WITHOUT one — TTS read-aloud, voice memos, audiobook
 * files — are deliberately untracked for now: a voice memo has no cross-device identity worth
 * syncing, and audiobooks join when the library manifest gives them one (Build B).
 *
 * The write cadence is split on purpose:
 *  - [record] with sync=false is the 15-second heartbeat during playback — local disk only,
 *    because a WebDAV round trip every 15s would keep the radio hot for the length of a podcast.
 *  - [record] with sync=true is the punctuation — pause, stop, finish — the moments after which
 *    another device might realistically be the next to press play.
 * A crash mid-playback therefore loses at most 15s locally and syncs on the next punctuation,
 * which is the same durability the day JSON accepts.
 */
object ListenStateStore {
    private const val PATH = "listen-state.json"
    private val adapter = Moshi.Builder().build()
        .adapter<List<ListenState>>(Types.newParameterizedType(List::class.java, ListenState::class.java))

    private fun file(context: Context) = File(context.filesDir, PATH)

    fun loadAll(context: Context): MutableList<ListenState> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    fun saveAll(context: Context, states: List<ListenState>) {
        runCatching { file(context).writeText(adapter.toJson(states)) }
            .onFailure { Timber.w(it, "listen-state save failed") }
    }

    /** The stored state for one episode, or null when this fleet has never played it. */
    fun get(context: Context, id: String): ListenState? =
        loadAll(context).firstOrNull { it.id == id && !it.isDeleted }

    /**
     * Note the playhead for [id]. `done` only ever goes forward: it latches true the moment the
     * position crosses [ListenState.doneAt] and no later, earlier position un-hears it.
     * [sync] pushes the merged file to the hub; false keeps the write local (the heartbeat case).
     */
    fun record(context: Context, id: String, positionMs: Long, durationMs: Long, sync: Boolean,
               audioUrl: String = "", title: String = "", show: String = "", artworkUrl: String = "") {
        if (id.isBlank()) return
        val all = loadAll(context)
        val cur = all.firstOrNull { it.id == id }
        val next = ListenState(
            id = id,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            done = (cur?.done == true) || ListenState.doneAt(positionMs, durationMs),
            // The offline ask is carried forward, not re-decided here: this function is about the
            // playhead, and a record rebuilt from scratch every heartbeat would otherwise drop the
            // "keep this" the moment you pressed play on it.
            wantOffline = cur?.wantOffline == true,
            // THE ENCLOSURE, carried on the playhead (09-03: Continue tried to play the episode
            // PAGE because the audio URL only got stored on an explicit download). Now every
            // heartbeat records the real media URL, so the resume is self-sufficient on any device.
            audioUrl = audioUrl.ifBlank { cur?.audioUrl.orEmpty() },
            title = title.ifBlank { cur?.title.orEmpty() },
            show = show.ifBlank { cur?.show.orEmpty() },
            artworkUrl = artworkUrl.ifBlank { cur?.artworkUrl.orEmpty() },
            updated = System.currentTimeMillis(),
            deletedAt = 0L   // playing an episode un-tombstones it: hearing it is the louder fact
        )
        if (cur != null) all.remove(cur)
        all.add(next)
        saveAll(context, all)
        if (sync) sync(context)
    }

    /**
     * Ask for (or give back) an episode's offline copy — the half of "keep this" that TRAVELS.
     *
     * Written through the same record the playhead uses, and synced immediately, because the whole
     * point is the other device: it opens, merges, finds an episode it was asked for and has no
     * file for, and fetches it ([FeedsFragment.fulfilOfflineAsks]). The playhead was already
     * syncing, so the episode arrives where you left off.
     *
     * Everything but the flag is preserved: asking for a file must not move your place in it.
     */
    fun setWantOffline(context: Context, id: String, want: Boolean, audioUrl: String = "",
                       title: String = "", show: String = "", artworkUrl: String = "") {
        if (id.isBlank()) return
        val all = loadAll(context)
        val cur = all.firstOrNull { it.id == id }
        val next = (cur ?: ListenState(id = id)).copy(
            wantOffline = want,
            // Keep whatever we already knew if the caller had nothing to add.
            audioUrl = audioUrl.ifBlank { cur?.audioUrl.orEmpty() },
            // The ask NAMES what it asks for (10-12, the iOS setWantOffline twin): an ask made
            // before any play used to sync with no title, show or picture, so the other device's
            // fetched copy arrived as a bare file name. Blank-safe, like [record].
            title = title.ifBlank { cur?.title.orEmpty() },
            show = show.ifBlank { cur?.show.orEmpty() },
            artworkUrl = artworkUrl.ifBlank { cur?.artworkUrl.orEmpty() },
            updated = System.currentTimeMillis(),
            deletedAt = 0L
        )
        if (cur != null) all.remove(cur)
        all.add(next)
        saveAll(context, all)
        sync(context)
    }

    /** Take an episode OFF the in-progress shelf everywhere (09-19, the iOS 216 twin): the
     *  tombstone rides the sidecar, so the iPhone drops the row too. Playing it again
     *  un-tombstones it ([record]) — hearing is the louder fact. */
    fun tombstone(context: Context, id: String) = mutate(context, id) { cur ->
        (cur ?: ListenState(id = id)).copy(wantOffline = false, updated = System.currentTimeMillis(),
            deletedAt = System.currentTimeMillis())
    }

    /** Mark heard: off the shelf, the playhead kept, the file free to age out. */
    fun markDone(context: Context, id: String) = mutate(context, id) { cur ->
        (cur ?: ListenState(id = id)).copy(done = true, updated = System.currentTimeMillis(), deletedAt = 0L)
    }

    private fun mutate(context: Context, id: String, next: (ListenState?) -> ListenState) {
        if (id.isBlank()) return
        val all = loadAll(context)
        val cur = all.firstOrNull { it.id == id }
        val n = next(cur)
        if (cur != null) all.remove(cur)
        all.add(n)
        saveAll(context, all)
        sync(context)
    }

    /** Every episode this fleet has asked to keep offline — what a device fetches on arrival. */
    fun wanted(context: Context): List<ListenState> =
        loadAll(context).filter { !it.isDeleted && it.wantOffline }

    /** Pull the remote file, [ListenState.merge] it, save, and push — the [BoardsStore] round trip
     *  on the shared single-thread sidecar executor (ordering is the correctness mechanism there). */
    fun sync(context: Context, onMerged: ((List<ListenState>) -> Unit)? = null) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val remote = runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
                ListenState.merge(local, remote)
            }
            saveAll(context, merged)
            LedgerSidecarSync.push(context, PATH, adapter.toJson(merged))
            onMerged?.invoke(merged)
        }
    }
}
