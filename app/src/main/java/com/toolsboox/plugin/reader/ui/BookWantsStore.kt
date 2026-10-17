package com.toolsboox.plugin.reader.ui

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.BookWant
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * The WANTS — `book-wants.json` at the hub root, the fleet's list of books that should be
 * everywhere (his 2026-08-27 ruling; wire and physics on [BookWant]). The
 * [com.toolsboox.plugin.calendar.ot.ReadingStateStore] pattern: local file always current,
 * pull-merge-push round trips on the shared sidecar executor, quiet on failure.
 *
 * Minting is cheap and idempotent — a re-ask refreshes [BookWant.at], which keeps a book the
 * fleet still reads young against [prune]'s window. [LibraryHub.syncPass] is the one consumer:
 * it uploads wanted bytes it holds and fetches wanted bytes it lacks.
 */
object BookWantsStore {
    private const val PATH = "book-wants.json"

    /** A want unrefreshed for half a year has been answered or abandoned; either way the
     *  record has stopped saying anything a pass needs. The MediaLogStore prune rule. */
    private const val KEEP_DAYS = 180L

    private val adapter = Moshi.Builder().build()
        .adapter<List<BookWant>>(Types.newParameterizedType(List::class.java, BookWant::class.java))

    private fun file(context: Context) = File(context.filesDir, PATH)

    fun loadAll(context: Context): MutableList<BookWant> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    private fun saveAll(context: Context, wants: List<BookWant>) {
        runCatching { file(context).writeText(adapter.toJson(wants)) }
            .onFailure { Timber.w(it, "book-wants save failed") }
    }

    /** The live wants, as this device last knew them. Local read — the sync pass merges first. */
    fun liveIds(context: Context): Set<String> =
        loadAll(context).filter { !it.isDeleted }.mapTo(HashSet()) { it.id }

    /**
     * File (or refresh) a want for [id]. Fire-and-forget from any thread; the local write is
     * immediate, the hub push rides the sidecar executor. Idempotent by design — every book
     * open re-asks, and the refresh is what keeps a read book's want alive.
     */
    fun want(context: Context, id: String, device: String) {
        if (id.isBlank()) return
        val app = context.applicationContext
        val all = loadAll(app)
        all.removeAll { it.id == id }
        all.add(BookWant(id = id, wantedBy = device, at = System.currentTimeMillis(), deletedAt = 0L))
        saveAll(app, all)
        LedgerSidecarSync.background { runCatching { sync(app) } }
    }

    /**
     * Pull → merge → prune → save → push-if-changed, the manifest's own round-trip shape.
     * Called from [want]'s background hop and from [LibraryHub.syncPass] (already off-main).
     * Returns the merged live list for the pass to consume.
     */
    fun sync(context: Context): List<BookWant> {
        val remoteText = LedgerSidecarSync.pull(context, PATH)
        val remote: List<BookWant> =
            if (remoteText.isNullOrBlank()) emptyList()
            else runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
        val merged = BookWant.merge(loadAll(context), remote)
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val kept = merged.filter { it.stamp >= cutoff }
        saveAll(context, kept)
        if (kept != remote) {
            LedgerSidecarSync.push(context, PATH, adapter.toJson(kept))
        }
        return kept
    }
}
