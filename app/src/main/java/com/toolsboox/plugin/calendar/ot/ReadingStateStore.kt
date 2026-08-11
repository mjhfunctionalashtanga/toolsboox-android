package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.ReadingState
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * Per-book reading position, in one synced sidecar (`reading-state.json` at the hub root),
 * id-union merged like [BoardsStore]. Wire shape and the folder-qualified `name#size` key are
 * documented on [ReadingState]; wire-compatible with the future iOS twin.
 *
 * Who writes here: the reader's relocate handler, once per page turn. The local write happens on
 * every turn (losing a page turn to a crash would be losing the only thing this file holds), but
 * the PUSH is debounced: a chapter read at one page per few seconds would otherwise queue a WebDAV
 * round trip per page onto the sidecar executor, behind which every other sidecar would wait.
 * Five seconds of stillness — a reader who has stopped turning pages — is the sync moment;
 * [flush] is the close-the-book moment that doesn't wait for stillness.
 */
object ReadingStateStore {
    private const val PATH = "reading-state.json"

    /** How long the pages must be still before the position rides to the hub. */
    private const val SYNC_QUIET_MS = 5_000L

    private val adapter = Moshi.Builder().build()
        .adapter<List<ReadingState>>(Types.newParameterizedType(List::class.java, ReadingState::class.java))

    private fun file(context: Context) = File(context.filesDir, PATH)

    fun loadAll(context: Context): MutableList<ReadingState> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    fun saveAll(context: Context, states: List<ReadingState>) {
        runCatching { file(context).writeText(adapter.toJson(states)) }
            .onFailure { Timber.w(it, "reading-state save failed") }
    }

    /** The stored state for one book, or null when this fleet has never opened it. */
    fun get(context: Context, id: String): ReadingState? =
        loadAll(context).firstOrNull { it.id == id && !it.isDeleted }

    /**
     * The sync key for a book that lives as a plain file under [shelfRoot] (the default shelf, or
     * an import that landed there) — the [ReadingState.keyFor] discipline applied to a File. A
     * file OUTSIDE the root (defensive; shouldn't happen) gets folder "", which still yields a
     * stable per-name key.
     */
    fun idForShelfFile(shelfRoot: File, f: File): String {
        val root = runCatching { shelfRoot.canonicalPath }.getOrDefault(shelfRoot.path)
        val parent = runCatching { f.parentFile?.canonicalPath }.getOrNull() ?: ""
        val folder =
            if (parent.startsWith(root)) parent.removePrefix(root).trim(File.separatorChar).replace(File.separatorChar, '/')
            else ""
        return ReadingState.keyFor(folder, f.name, f.length())
    }

    /** Record the reader's spot for [id]: local write now, hub push after [SYNC_QUIET_MS] of no
     *  further turns. Blank locators are ignored — foliate reports one on some non-paginated
     *  states and an empty locator would overwrite a real one with nothing. */
    fun record(context: Context, id: String, locator: String, percent: Double) {
        if (id.isBlank() || locator.isBlank()) return
        val all = loadAll(context)
        all.removeAll { it.id == id }
        all.add(
            ReadingState(
                id = id, locator = locator, percent = percent.coerceIn(0.0, 1.0),
                updated = System.currentTimeMillis(),
                deletedAt = 0L   // reading a book un-tombstones it
            )
        )
        saveAll(context, all)
        scheduleSync(context.applicationContext)
    }

    // Debounce lives here rather than in the reader so EVERY writer gets it for free, and so the
    // pending push survives the fragment being torn down (the app context is what's captured).
    private val main = Handler(Looper.getMainLooper())
    private var pendingSync: Runnable? = null

    private fun scheduleSync(appContext: Context) {
        pendingSync?.let { main.removeCallbacks(it) }
        val r = Runnable { pendingSync = null; sync(appContext) }
        pendingSync = r
        main.postDelayed(r, SYNC_QUIET_MS)
    }

    /** Push now — the book is closing (or the reader is going to background) and "after five quiet
     *  seconds" may be after the process is gone. No-op when nothing is pending. */
    fun flush(context: Context) {
        val r = pendingSync ?: return
        main.removeCallbacks(r)
        pendingSync = null
        sync(context.applicationContext)
    }

    /** Pull the remote file, [ReadingState.merge] it, save, and push — the [BoardsStore] round
     *  trip on the shared single-thread sidecar executor. [onMerged] (background thread) receives
     *  the merged list once the pull lands — the reader uses it to RE-jump when another device's
     *  position turns out newer than the local spot it already resumed to. */
    fun sync(context: Context, onMerged: ((List<ReadingState>) -> Unit)? = null) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val remote = runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
                ReadingState.merge(local, remote)
            }
            saveAll(context, merged)
            LedgerSidecarSync.push(context, PATH, adapter.toJson(merged))
            onMerged?.invoke(merged)
        }
    }
}
