package com.toolsboox.plugin.feeds.ot

import android.content.Context
import com.toolsboox.plugin.reader.ui.BookNote
import com.toolsboox.plugin.reader.ui.MarkFile
import java.io.File

/**
 * WHERE AN ARTICLE'S HIGHLIGHTS ARE — the book's mark record ([BookNote]) and its synced file
 * engine ([MarkFile]), filed per article under [ArticleKey.of] (Michael 2026-10-12, the approved
 * highlights+notes plan).
 *
 * SIXTEEN FILES, NOT ONE (design critique #3): `reader/articlenotes/<0-f>.json`, by the key's first
 * hex character. Articles change far more often than books, and a mark file is pulled and pushed
 * whole — one growing file would be re-sent on every highlight. Its own files rather than the book
 * file because a Boox still on -195 rewrites booknotes.json on every merge and would strip the
 * article fields (the separate-file call, verified in the design).
 *
 * PULL ON OPEN AT MOST EVERY FEW MINUTES; PUSH ONLY ON A CHANGE ([refresh], [MarkFile.sync]).
 *
 * The mark holds where the passage is, the passage, and pointers — its day, its one pick, its Log
 * line. What is SAID about it lives on the Log line (critique #4).
 */
object ArticleNoteStore {

    /** Pull a bucket on article open at most this often. */
    const val PULL_EVERY_MS = 3 * 60_000L

    private val buckets: Map<Char, MarkFile> = ("0123456789abcdef").associate { b ->
        b to MarkFile({ File(File(it.filesDir, "reader/articlenotes").apply { mkdirs() }, "$b.json") },
            "reader/articlenotes/$b.json")
    }

    private fun fileFor(key: String): MarkFile = buckets.getValue(ArticleKey.bucket(key))

    /**
     * Every live mark on the article [key], in reading order. A key that finds nothing falls back
     * to the cleaned URL stored on each mark ([cleanUrl], critique #12) — the last chance before a
     * mark made by the other app is shown as missing.
     */
    fun list(context: Context, key: String, cleanUrl: String = ""): List<BookNote> =
        listWithTombstones(context, key, cleanUrl).filter { it.deletedAt == null }

    /** [list] with the removed marks still in it — what the page's assembly needs, so a removed
     *  highlight's card and line stay claimed ([ArticleMarkSet.assemble]). */
    fun listWithTombstones(context: Context, key: String, cleanUrl: String = ""): List<BookNote> {
        val own = fileFor(key).rawList(context, key)
        // The fallback walks every bucket — each parsed ONCE and kept until it changes
        // ([MarkFile.allNotes]), not re-read per key (review 2026-10-12 #5).
        val found = if (own.isNotEmpty() || cleanUrl.isBlank()) own else {
            buckets.values.asSequence().flatMap { it.allNotes(context).asSequence() }
                .filter { ArticleKey.looseEquals(it.url, cleanUrl) }.toList()
        }
        return found.sortedBy { it.fraction }
    }

    /** The live mark [id] on [key], or null. */
    fun get(context: Context, key: String, id: String): BookNote? =
        stored(context, key, id)?.takeIf { it.deletedAt == null }

    /** The mark [id] on [key] as stored — a tombstone included — or null when it was never here. */
    fun stored(context: Context, key: String, id: String): BookNote? =
        fileFor(key).rawList(context, key).firstOrNull { it.id == id }

    /**
     * Change the STORED mark [id] in place — read, changed and written under the file's lock, so a
     * change made since the caller read it is kept (review 2026-10-12 #11: callers wrote back a copy
     * read before a card render, losing whatever landed in between). [change] gets the live mark and
     * returns its next version, or null for "leave it"; a tombstone is never revived. Returns the
     * mark as written, or null when nothing was.
     */
    fun update(context: Context, key: String, id: String, change: (BookNote) -> BookNote?): BookNote? {
        if (key.isBlank()) return null
        var out: BookNote? = null
        val wrote = fileFor(key).mutate(context, key) { list ->
            val held = list.firstOrNull { it.id == id && it.deletedAt == null } ?: return@mutate null
            val next = change(held)?.copy(updatedAt = System.currentTimeMillis()) ?: return@mutate null
            out = next
            list.map { if (it.id == id) next else it }
        }
        if (wrote) fileFor(key).sync(context)
        return out
    }

    /** Insert or replace [mark] (by id) under [key], then sync that bucket. */
    fun put(context: Context, key: String, mark: BookNote) {
        if (key.isBlank()) return
        fileFor(key).mutate(context, key) { list -> list.filter { it.id != mark.id } + mark }
        fileFor(key).sync(context)
    }

    /** Insert [mark] only if no mark with its id exists yet (a deterministic adoption id made on
     *  two devices at once lands once). True when it was written. */
    fun putIfAbsent(context: Context, key: String, mark: BookNote): Boolean {
        if (key.isBlank()) return false
        val wrote = fileFor(key).mutate(context, key) { list ->
            if (list.any { it.id == mark.id }) null else list + mark
        }
        if (wrote) fileFor(key).sync(context)
        return wrote
    }

    /** Tombstone [id] — a delete must travel, or the other device resurrects it. */
    fun delete(context: Context, key: String, id: String) {
        val now = System.currentTimeMillis()
        fileFor(key).mutate(context, key) { list ->
            list.map { if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it }
        }
        fileFor(key).sync(context)
    }

    /**
     * Bring the other devices' marks for [key] in — at most once per [PULL_EVERY_MS] per bucket —
     * and call [onChanged] (on the sync thread) only when the local file actually changed, so the
     * page is redrawn only for something new (e-ink: usually zero extra refreshes).
     */
    fun refresh(context: Context, key: String, onChanged: () -> Unit) {
        val f = fileFor(key)
        val now = System.currentTimeMillis()
        if (now - f.lastPullAt < PULL_EVERY_MS) return
        f.lastPullAt = now   // claimed now, so two opens in a row queue one pull, not two
        f.sync(context) { changed -> if (changed) onChanged() }
    }
}
