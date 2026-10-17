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
 *
 * THE ARTICLE MARK (Michael 2026-10-12: "Yes add card to all picks. It goes in the day it was
 * picked's log." / "Highlight should make a gram pick anyway."). An RSS article's highlight is
 * the same record in its own file ([ArticleNoteStore]): [cfi] carries the text anchor
 * (`{"exact","prefix","suffix"}`), [text] the passage, and the fields below say where it lives
 * in the ledger — the article ([url]/[title]/[source]/[entryId]/[view]), the day it was made
 * ([day]), its ONE gram pick on that day ([pickId]) and its Log line ([logId]). Everything SAID
 * about the passage — typed note, ink, voice, photo — lives on that Log line (design critique
 * #4, one home); the mark is only where the passage is. All additive and defaulted: the book file
 * never writes them (toJson skips empties), so a -195 Boox rewriting booknotes.json strips
 * nothing it ever had.
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
    /**
     * How the mark is DRAWN: "highlight" · "underline" · "squiggly" · "strike" (Readest's set).
     *
     * Additive and defaulted, so every mark already on disk keeps working and reads as a
     * highlight — which is what it was. Only meaningful for [ANNOTATION]; a bookmark has no
     * passage to draw over.
     */
    val style: String = STYLE_HIGHLIGHT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    // ── Article marks only (see the class note). Empty on every book mark. ──
    /** The article's cleaned URL ([com.toolsboox.plugin.feeds.ot.ArticleKey.clean]) — the key's
     *  fallback when the two apps ever clean a URL differently (design critique #12). */
    val url: String = "",
    val title: String = "",
    /** The feed / publication. */
    val source: String = "",
    /** The Miniflux entry id the mark was made on (0 = none/unknown) — the Log's reopen. */
    val entryId: Long = 0L,
    /** Which text it was made on: [VIEW_FEED] · [VIEW_READER] · [VIEW_LIVE] (critique #7). */
    val view: String = "",
    /** yyyy-MM-dd — THE WIRE'S ONE DAY: the day it was made, where its Log line and its pick live
     *  unless [logDay] / [pickDay] say otherwise. iOS writes and reads it too. */
    val day: String = "",
    /** The elementId of its ONE gram pick on [cardDay] ("" = none: an adopted Log-only highlight). */
    val pickId: String = "",
    /** The id of its Log line on [lineDay] (a new mark's own id; an adopted line keeps its own). */
    val logId: String = "",
    /**
     * The day its Log line is on, and the day its card is on — iOS's own fields (BookMarksStore.swift
     * `logDay` / `pickDay`), carried here so the two apps read each other's marks the same way and an
     * Android rewrite of the shared file never strips them (Michael 2026-10-12: "It goes in the day it
     * was picked's log"). They differ only for an ADOPTED old highlight whose card and Log line were
     * made on different days (an old Make pick on Monday, an iOS ❝ line on Tuesday) — one [day] could
     * not point at both, and the note's line was looked for on the card's day and made again there.
     */
    val logDay: String = "",
    val pickDay: String = "",
    /**
     * THE SHAPE ITS PICK WAS DESIGNED IN (add-to-pick pin §3, Michael 2026-10-12: a designed studio
     * card keeps its design when something is added) — null = never designed: free / shrink /
     * picture on. Every later add re-faces the pick in this format, fit and picture setting. A real
     * field here (not left in [extra]); iOS `BookMark.face`, the same JSON.
     */
    val face: MarkFace? = null,
    /**
     * A designed Series' cards 2…n — copies placed beside its pick (review 2026-10-16 #15): kept so
     * the next design takes them off instead of piling more up. Empty on every other mark, and then
     * not written. iOS keeps it as an unmodelled key (BookMark.extra) until it reads it too.
     */
    val pickCopies: List<String> = emptyList(),
    /**
     * Keys this build doesn't model, as a JSON object — kept and written back, so a field a newer
     * build (either app) adds to a shared mark survives this one's rewrite of the file. Never read.
     */
    val extra: String = "",
) {
    /** The day its Log line is on: its own, else the wire's [day], else the day it was made. */
    val lineDay: java.time.LocalDate? get() = dayOf(logDay) ?: dayOf(day) ?: madeDay
    /** The day its card is on: its own, else the wire's [day], else the day it was made (iOS
     *  `cardDayISO`, the same order). */
    val cardDay: java.time.LocalDate? get() = dayOf(pickDay) ?: dayOf(day) ?: madeDay
    private val madeDay: java.time.LocalDate?
        get() = createdAt.takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }

    companion object {
        private fun dayOf(s: String): java.time.LocalDate? =
            s.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

        const val STYLE_HIGHLIGHT = "highlight"
        const val STYLE_UNDERLINE = "underline"
        const val STYLE_SQUIGGLY = "squiggly"
        const val STYLE_STRIKE = "strike"

        /** The picker's order — plain marks first, then the two that say something ABOUT the
         *  passage rather than just pointing at it. */
        val STYLES = listOf(STYLE_HIGHLIGHT, STYLE_UNDERLINE, STYLE_SQUIGGLY, STYLE_STRIKE)

        fun styleLabel(style: String): String = when (style) {
            STYLE_UNDERLINE -> "＿  Underline"
            STYLE_SQUIGGLY -> "∿  Squiggle"
            STYLE_STRIKE -> "—  Strike through"
            else -> "🖍  Highlight"
        }

        const val ANNOTATION = "annotation"
        const val BOOKMARK = "bookmark"
        const val NOTE = "note"

        const val VIEW_FEED = "feed"
        const val VIEW_READER = "reader"
        const val VIEW_LIVE = "live"

        fun newId(): String = "bn-${UUID.randomUUID()}"
    }
}

/**
 * A pick's designed shape — the wire's `face` on a mark: `{"format":"free|square|portrait|landscape|story",
 * "fit":"shrink|pullquote|series","picture":true}`. Absent = free / shrink / true.
 */
data class MarkFace(val format: String = FORMAT_FREE, val fit: String = FIT_SHRINK, val picture: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("format", format).put("fit", fit).put("picture", picture)

    companion object {
        const val FORMAT_FREE = "free"
        const val FIT_SHRINK = "shrink"
        const val FIT_PULLQUOTE = "pullquote"
        const val FIT_SERIES = "series"
        val FORMATS = listOf(FORMAT_FREE, "square", "portrait", "landscape", "story")
        val FITS = listOf(FIT_SHRINK, FIT_PULLQUOTE, FIT_SERIES)

        fun fromJson(o: JSONObject?): MarkFace? = o?.let {
            MarkFace(
                format = it.optString("format").takeIf { f -> f in FORMATS } ?: FORMAT_FREE,
                fit = it.optString("fit").takeIf { f -> f in FITS } ?: FIT_SHRINK,
                picture = if (it.has("picture")) it.optBoolean("picture", true) else true,
            )
        }
    }
}

/**
 * ONE SYNCED MARK FILE — `{ key: [ note, … ] }` on disk and on the sync backend, merged per note by
 * id (newest updatedAt wins, tombstones kept). The book file and every article bucket are one of
 * these; the logic is written once.
 *
 * THE RACE THIS CLOSES (design critique #1, 2026-10-12 — a data-loss bug the one-tap article
 * Highlight would have hit within days): `sync()` used to read the local file, PULL (seconds on a
 * B6), then write the merge of THAT snapshot back — while `put()` wrote on the main thread in the
 * meantime. A mark saved during the pull was overwritten by the old copy, and the next sync pushed
 * the loss everywhere. Now every write is a read-modify-write under [lock], and the sync's write
 * re-reads the file AFTER the pull and merges the pull into what is there now — under the same
 * lock. A put during the pull lands; the sync's merge keeps it; its own queued sync pushes it.
 * Books get the same protection.
 *
 * And it pushes only when the remote lacks something (critique #3): a pull that changed nothing
 * costs no upload.
 */
internal class MarkFile(private val localFile: (Context) -> File, val remote: String) {
    private val lock = Any()

    /** When this file was last pulled — the article buckets' "pull at most every few minutes". */
    @Volatile var lastPullAt = 0L

    private fun readUnlocked(f: File): JSONObject =
        runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())

    /**
     * The file as last parsed, by key (review 2026-10-12 #5): an article open that found no marks
     * walked all sixteen buckets and re-read and re-parsed each WHOLE file once per key in it —
     * keys × file size, on the main thread, growing with every highlighted article. Reads now parse
     * a file once and keep it until it changes. Exact, not a guess: every writer of these files is
     * this class (each put, each sync's merge — [writeUnlocked] drops the copy), and the stamp
     * (path, length, mtime) catches a file replaced behind its back.
     */
    private var parsed: Triple<String, Pair<Long, Long>, Map<String, List<BookNote>>>? = null

    private fun parsedUnlocked(f: File): Map<String, List<BookNote>> {
        val stamp = f.length() to f.lastModified()
        parsed?.let { (path, st, map) -> if (path == f.absolutePath && st == stamp) return map }
        val obj = readUnlocked(f)
        val map = obj.keys().asSequence().toList().associateWith { notesOf(obj, it) }
        parsed = Triple(f.absolutePath, stamp, map)
        return map
    }

    private fun writeUnlocked(f: File, obj: JSONObject) =
        runCatching {
            parsed = null
            // Atomic (temp+rename): this file is sync-authoritative — a truncated write would
            // push garbage to every device (the state.json lesson).
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            // Never delete the good copy to make room for the rename — a kill between the
            // delete and the rename loses the only local copy. Files.move replaces in one step.
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // ATOMIC_MOVE can be unsupported on some filesystems; fall back to a plain replace.
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { Timber.w(it, "mark file save failed ($remote)") }.let {}

    /** Every note under [key], tombstones included, as stored. */
    fun rawList(context: Context, key: String): List<BookNote> = rawListAt(localFile(context), key)

    internal fun rawListAt(f: File, key: String): List<BookNote> = synchronized(lock) { parsedUnlocked(f)[key].orEmpty() }

    /** Every key in the file — the article fallback walks these. */
    fun keys(context: Context): List<String> {
        val f = localFile(context)
        return synchronized(lock) { parsedUnlocked(f).keys.toList() }
    }

    /** Every note in the file, tombstones included, parsed once — the article fallback's walk. */
    fun allNotes(context: Context): List<BookNote> {
        val f = localFile(context)
        return synchronized(lock) { parsedUnlocked(f).values.flatten() }
    }

    /** Read-modify-write [key]'s list under the lock; [change] returns the new list, or null for
     *  "nothing to write". */
    fun mutate(context: Context, key: String, change: (List<BookNote>) -> List<BookNote>?): Boolean =
        mutateAt(localFile(context), key, change)

    internal fun mutateAt(f: File, key: String, change: (List<BookNote>) -> List<BookNote>?): Boolean {
        synchronized(lock) {
            val obj = readUnlocked(f)
            val next = change(notesOf(obj, key)) ?: return false
            obj.put(key, JSONArray().apply { next.forEach { put(MarkJson.toJson(it)) } })
            writeUnlocked(f, obj)
        }
        return true
    }

    /**
     * Pull the remote file, merge per note (newest updatedAt wins) INTO WHAT IS ON DISK NOW, save,
     * and push when the remote lacks something. [onMerged] hears whether the local file changed.
     */
    fun sync(context: Context, onMerged: ((changed: Boolean) -> Unit)? = null) {
        LedgerSidecarSync.background {
            val remoteText = LedgerSidecarSync.pull(context, remote)
            lastPullAt = System.currentTimeMillis()
            val (changed, pushText) = mergeAfterPull(localFile(context), remoteText, pushIfMissing(remoteText))
            pushText?.let { LedgerSidecarSync.push(context, remote, it) }
            onMerged?.invoke(changed)
        }
    }

    /**
     * A NULL PULL IS NOT "THE SERVER HAS NOTHING" ONCE THE FILE WAS SEEN (review 2026-10-12 #9). The
     * transport answers null for a missing file AND for a failed GET, and must not say which
     * ([com.toolsboox.plugin.calendar.nw.HubTransport.get]); pushing on every null let one transient
     * failure followed by a good PUT replace the server copy — the other devices' marks and
     * deletions — until each of them pushed again. So: once this run has pulled the file, the next
     * null pull pushes nothing; a second null in a row means the file really is gone, and the local
     * copy goes up again. A file never seen (a first sync, a new bucket) pushes on the first null,
     * as before.
     */
    @Volatile private var remoteSeen = false
    @Volatile private var nullsSinceSeen = 0

    internal fun pushIfMissing(remoteText: String?): Boolean {
        if (remoteText != null) { remoteSeen = true; nullsSinceSeen = 0; return true }
        if (!remoteSeen) return true
        nullsSinceSeen++
        if (nullsSinceSeen < 2) return false
        remoteSeen = false; nullsSinceSeen = 0
        return true
    }

    /**
     * The sync's write, after the network: RE-READ the file (critique #1 — a put that landed while
     * the pull was out is in this read, so the merge keeps it), merge [remoteText] in, write when
     * that changed anything, and return (changed, the text to push — null when the remote already
     * has everything). Under the same lock as every put. Pure enough to test: MarkFileTest.
     */
    internal fun mergeAfterPull(f: File, remoteText: String?, pushWhenMissing: Boolean = true): Pair<Boolean, String?> {
        val remoteObj = remoteText?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        synchronized(lock) {
            val local = readUnlocked(f)
            val merged = if (remoteObj == null) local else MarkJson.merge(local, remoteObj)
            val changed = !MarkJson.same(local, merged)
            if (changed) writeUnlocked(f, merged)
            val remoteBehind = if (remoteObj == null) pushWhenMissing else !MarkJson.same(remoteObj, merged)
            return changed to (if (remoteBehind && merged.length() > 0) merged.toString() else null)
        }
    }

    private fun notesOf(obj: JSONObject, key: String): List<BookNote> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { MarkJson.fromJson(it) } }
    }
}

/** The mark JSON — one shape for the book file and the article buckets. Pure: MarkFileTest. */
internal object MarkJson {
    /** Every key [toJson] writes — anything else on a stored mark rides in [BookNote.extra]. */
    private val KNOWN = setOf("id", "type", "cfi", "chapter", "fraction", "text", "note", "image", "style",
        "createdAt", "updatedAt", "deletedAt", "url", "title", "source", "entryId", "view", "day",
        "pickId", "logId", "logDay", "pickDay", "face", "pickCopies")

    fun toJson(n: BookNote): JSONObject = (n.extra.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
        .put("id", n.id).put("type", n.type).put("cfi", n.cfi)
        .put("chapter", n.chapter).put("fraction", n.fraction)
        .put("text", n.text).put("note", n.note)
        .put("image", n.image ?: JSONObject.NULL)
        .put("style", n.style)
        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
        .put("deletedAt", n.deletedAt ?: JSONObject.NULL)
        .apply {
            // The article fields only when set: a book mark's JSON stays byte-for-byte what it was.
            if (n.url.isNotEmpty()) put("url", n.url)
            if (n.title.isNotEmpty()) put("title", n.title)
            if (n.source.isNotEmpty()) put("source", n.source)
            if (n.entryId != 0L) put("entryId", n.entryId)
            if (n.view.isNotEmpty()) put("view", n.view)
            if (n.day.isNotEmpty()) put("day", n.day)
            if (n.pickId.isNotEmpty()) put("pickId", n.pickId)
            if (n.logId.isNotEmpty()) put("logId", n.logId)
            if (n.logDay.isNotEmpty()) put("logDay", n.logDay)
            if (n.pickDay.isNotEmpty()) put("pickDay", n.pickDay)
            n.face?.let { put("face", it.toJson()) }
            if (n.pickCopies.isNotEmpty()) put("pickCopies", org.json.JSONArray(n.pickCopies))
        }

    fun fromJson(o: JSONObject): BookNote? {
        val id = o.optString("id").ifBlank { return null }
        return BookNote(
            id = id,
            type = o.optString("type").ifBlank { BookNote.ANNOTATION },
            cfi = o.optString("cfi"),
            chapter = o.optString("chapter"),
            fraction = o.optDouble("fraction", 0.0).let { if (it.isNaN()) 0.0 else it },
            text = o.optString("text"),
            note = o.optString("note"),
            image = o.optString("image").takeIf { it.isNotBlank() && it != "null" },
            style = o.optString("style").takeIf { it.isNotBlank() } ?: BookNote.STYLE_HIGHLIGHT,
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            deletedAt = o.optLong("deletedAt").takeIf { it > 0L },
            url = o.optString("url"),
            title = o.optString("title"),
            source = o.optString("source"),
            entryId = o.optLong("entryId"),
            view = o.optString("view"),
            day = o.optString("day"),
            pickId = o.optString("pickId"),
            logId = o.optString("logId"),
            logDay = o.optString("logDay").takeIf { it != "null" }.orEmpty(),
            pickDay = o.optString("pickDay").takeIf { it != "null" }.orEmpty(),
            face = MarkFace.fromJson(o.optJSONObject("face")),
            pickCopies = o.optJSONArray("pickCopies")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }.orEmpty(),
            extra = o.keys().asSequence().filter { it !in KNOWN }.toList().takeIf { it.isNotEmpty() }
                ?.let { ks -> JSONObject().apply { ks.forEach { k -> put(k, o.opt(k)) } }.toString() }.orEmpty(),
        )
    }

    /** Per key, per id: the newer updatedAt wins (local first on a tie, so a re-merge is quiet). */
    fun merge(local: JSONObject, remote: JSONObject): JSONObject {
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
        return out
    }

    /** The same notes at the same versions under the same keys — order and formatting aside. */
    fun same(a: JSONObject, b: JSONObject): Boolean = signature(a) == signature(b)

    private fun signature(o: JSONObject): Map<String, Map<String, Long>> =
        o.keys().asSequence().associateWith { key ->
            val arr = o.optJSONArray(key) ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .associate { it.optString("id") to it.optLong("updatedAt") }
        }.filterValues { it.isNotEmpty() }
}

/**
 * The book's marks, portable across devices. Keyed by book NAME (not the device-local file
 * path, which differs per device) exactly as [ReaderPositionStore] is, so a book synced onto
 * two Boox carries one set of bookmarks and highlights.
 *
 * Stored as filesDir/reader/booknotes.json ({ bookName: [ note, … ] }) and round-tripped
 * through WebDAV, merged per NOTE by id (newest updatedAt wins) — per-book timestamping would
 * make two devices' marks fight instead of pooling. The file and its sync are [MarkFile]'s — the
 * same engine the article buckets ride, race-proofed once for both (critique #1).
 *
 * A book highlight is still logged as a `ReadingEvent` onto today's `CalendarDay` as well: that
 * is the day-shaped record (the Ledger Log, the timeline). This store is the book-shaped one.
 * Neither can do the other's job — you cannot list one book's marks without opening every day
 * file, and you cannot place a mark on a day timeline from a per-book array.
 */
object BookNoteStore {
    private val file = MarkFile(
        { File(File(it.filesDir, "reader").apply { mkdirs() }, "booknotes.json") },
        "reader/booknotes.json")

    /** Every live mark of [type] in [book], in reading order. Tombstones stay out of sight. */
    fun list(context: Context, book: String, type: String? = null): List<BookNote> =
        file.rawList(context, book)
            .filter { it.deletedAt == null && (type == null || it.type == type) }
            .sortedBy { it.fraction }

    /** Insert or replace [note] (by id) and push the merged file. */
    fun put(context: Context, book: String, note: BookNote) {
        if (book.isBlank()) return
        file.mutate(context, book) { list -> list.filter { it.id != note.id } + note }
        sync(context)
    }

    /**
     * Change the STORED live mark [id] in place under the file's lock — [change] sees the mark as
     * it is now, not a copy read before a card render (review 2026-10-12 #11), and returns its next
     * version or null to leave it. Returns what was written, or null.
     */
    fun update(context: Context, book: String, id: String, change: (BookNote) -> BookNote?): BookNote? {
        if (book.isBlank()) return null
        var out: BookNote? = null
        val wrote = file.mutate(context, book) { list ->
            val held = list.firstOrNull { it.id == id && it.deletedAt == null } ?: return@mutate null
            val next = change(held)?.copy(updatedAt = System.currentTimeMillis()) ?: return@mutate null
            out = next
            list.map { if (it.id == id) next else it }
        }
        if (wrote) sync(context)
        return out
    }

    /** Tombstone [id] — see [BookNote.deletedAt] for why this isn't a removal. */
    fun delete(context: Context, book: String, id: String) {
        val now = System.currentTimeMillis()
        file.mutate(context, book) { list ->
            list.map { if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it }
        }
        sync(context)
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
        val now = System.currentTimeMillis()
        val wrote = file.mutate(context, book) { list ->
            val known = list.map { it.cfi }.toSet()
            val fresh = cfis.filter { it.isNotBlank() && it !in known }
            if (fresh.isEmpty()) null
            else list + fresh.map {
                BookNote(id = BookNote.newId(), type = BookNote.ANNOTATION, cfi = it, createdAt = now, updatedAt = now)
            }
        }
        if (wrote) sync(context)
    }

    /** Pull the remote file, merge per note (newest updatedAt wins), save, and push. */
    fun sync(context: Context, onMerged: (() -> Unit)? = null) {
        file.sync(context) { onMerged?.invoke() }
    }
}
