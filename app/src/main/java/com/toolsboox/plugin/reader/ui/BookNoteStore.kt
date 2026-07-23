package com.toolsboox.plugin.reader.ui

import android.content.Context
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * One mark a reader left in a book — Readest's `BookNote` shape, kept deliberately.
 *
 * Readest keeps a SINGLE flat list per book (`config.booknotes`) and lets [type] discriminate
 * between a bookmark, a highlight and a note, rather than three parallel stores. That is what
 * lets one sidebar render both tabs off one array, one merge rule cover all of them, and a
 * bookmark be promoted into an annotation without moving house. We copy the shape.
 *
 * Two departures, both forced by this app rather than chosen:
 *
 * - [chapter]/[fraction] instead of resolving the group from the CFI at render time. Readest
 *   binary-searches the TOC with `CFI.compare`; we have no CFI comparator on the Kotlin side,
 *   so we record where the reader WAS when the mark was made. Same grouping, read at the only
 *   moment we can read it.
 * - [image], a base64 PNG, for a hand-written note. Readest stores media by hash in a blob
 *   directory; a note this small rides inline, the way `LedgerItem.crop` already does here.
 *
 * [deletedAt] is a tombstone, not a nicety: without it a delete on one Boox is simply absent
 * from that device's file and the other device's copy resurrects it on the next merge.
 */
data class BookNote(
    val id: String,
    /** [ANNOTATION] · [BOOKMARK] · [NOTE]. */
    val type: String,
    /** Where in the book — empty for a book-level [NOTE], which is about the whole thing. */
    val cfi: String = "",
    /** The TOC label the reader was standing in; the panel groups by this. */
    val chapter: String = "",
    /** 0..1 through the book — orders marks inside a chapter, standing in for `CFI.compare`. */
    val fraction: Double = 0.0,
    /** The highlighted passage, or a bookmark's label. */
    val text: String = "",
    /** The reader's own words. */
    val note: String = "",
    /** A hand-written note as a base64 PNG. */
    val image: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
) {
    companion object {
        const val ANNOTATION = "annotation"
        const val BOOKMARK = "bookmark"
        const val NOTE = "note"

        fun newId(): String = "bn-${UUID.randomUUID()}"
    }
}

/**
 * The book's marks, portable across devices. Keyed by book NAME (not the device-local file
 * path, which differs per device) exactly as [ReaderPositionStore] is, so a book synced onto
 * two Boox carries one set of bookmarks and highlights.
 *
 * Stored as filesDir/reader/booknotes.json ({ bookName: [ note, … ] }) and round-tripped
 * through WebDAV, merged per NOTE by id (newest updatedAt wins) — per-book timestamping would
 * make two devices' marks fight instead of pooling.
 *
 * A book highlight is still logged as a `ReadingEvent` onto today's `CalendarDay` as well: that
 * is the day-shaped record (the Ledger Log, the timeline). This store is the book-shaped one.
 * Neither can do the other's job — you cannot list one book's marks without opening every day
 * file, and you cannot place a mark on a day timeline from a per-book array.
 */
object BookNoteStore {
    private const val REMOTE = "reader/booknotes.json"

    private fun file(context: Context) =
        File(File(context.filesDir, "reader").apply { mkdirs() }, "booknotes.json")

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(file(context).readText()) }.getOrDefault(JSONObject())

    private fun write(context: Context, obj: JSONObject) =
        runCatching {
            // Atomic (temp+rename): this file is sync-authoritative — a truncated write would
            // push garbage to every device (the state.json lesson).
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Timber.w(it, "book notes save failed") }.let {}

    private fun toJson(n: BookNote): JSONObject = JSONObject()
        .put("id", n.id).put("type", n.type).put("cfi", n.cfi)
        .put("chapter", n.chapter).put("fraction", n.fraction)
        .put("text", n.text).put("note", n.note)
        .put("image", n.image ?: JSONObject.NULL)
        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
        .put("deletedAt", n.deletedAt ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject): BookNote? {
        val id = o.optString("id").ifBlank { return null }
        return BookNote(
            id = id,
            type = o.optString("type").ifBlank { BookNote.ANNOTATION },
            cfi = o.optString("cfi"),
            chapter = o.optString("chapter"),
            fraction = o.optDouble("fraction", 0.0).let { if (it.isNaN()) 0.0 else it },
            text = o.optString("text"),
            note = o.optString("note"),
            image = o.optString("image").takeIf { it.isNotBlank() },
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            deletedAt = o.optLong("deletedAt").takeIf { it > 0L }
        )
    }

    private fun rawList(context: Context, book: String): List<BookNote> {
        val arr = read(context).optJSONArray(book) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
    }

    /** Every live mark of [type] in [book], in reading order. Tombstones stay out of sight. */
    fun list(context: Context, book: String, type: String? = null): List<BookNote> =
        rawList(context, book)
            .filter { it.deletedAt == null && (type == null || it.type == type) }
            .sortedBy { it.fraction }

    /** Insert or replace [note] (by id) and push the merged file. */
    fun put(context: Context, book: String, note: BookNote) {
        if (book.isBlank()) return
        val kept = rawList(context, book).filter { it.id != note.id } + note
        save(context, book, kept)
    }

    /** Tombstone [id] — see [BookNote.deletedAt] for why this isn't a removal. */
    fun delete(context: Context, book: String, id: String) {
        val now = System.currentTimeMillis()
        save(context, book, rawList(context, book).map {
            if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it
        })
    }

    /** The live bookmark at [cfi], if this page already carries one. */
    fun bookmarkAt(context: Context, book: String, cfi: String): BookNote? =
        if (cfi.isBlank()) null
        else list(context, book, BookNote.BOOKMARK).firstOrNull { it.cfi == cfi }

    /**
     * Adopt highlights saved before this store existed — they were a bare set of CFIs in
     * SharedPreferences, with the passage itself living only on the day it was made. Bring the
     * locations across (once, only the ones we don't already hold) so a long-standing book's
     * marks appear in the panel instead of looking lost.
     */
    fun adoptLegacyHighlights(context: Context, book: String, cfis: Set<String>) {
        if (cfis.isEmpty()) return
        val known = rawList(context, book).map { it.cfi }.toSet()
        val fresh = cfis.filter { it.isNotBlank() && it !in known }
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        save(context, book, rawList(context, book) + fresh.map {
            BookNote(id = BookNote.newId(), type = BookNote.ANNOTATION, cfi = it, createdAt = now, updatedAt = now)
        })
    }

    private fun save(context: Context, book: String, notes: List<BookNote>) {
        val obj = read(context)
        obj.put(book, JSONArray().apply { notes.forEach { put(toJson(it)) } })
        write(context, obj)
        sync(context)
    }

    /** Pull the remote file, merge per note (newest updatedAt wins), save, and push. */
    fun sync(context: Context, onMerged: (() -> Unit)? = null) {
        LedgerSidecarSync.background {
            val local = read(context)
            val remoteText = LedgerSidecarSync.pull(context, REMOTE)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val remote = runCatching { JSONObject(remoteText) }.getOrDefault(JSONObject())
                val out = JSONObject()
                for (key in (local.keys().asSequence() + remote.keys().asSequence()).toSet()) {
                    val byId = linkedMapOf<String, BookNote>()
                    for (side in listOf(local, remote)) {
                        val arr = side.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            val n = arr.optJSONObject(i)?.let { fromJson(it) } ?: continue
                            val held = byId[n.id]
                            if (held == null || n.updatedAt > held.updatedAt) byId[n.id] = n
                        }
                    }
                    out.put(key, JSONArray().apply { byId.values.forEach { put(toJson(it)) } })
                }
                out
            }
            write(context, merged)
            LedgerSidecarSync.push(context, REMOTE, merged.toString())
            onMerged?.invoke()
        }
    }
}
