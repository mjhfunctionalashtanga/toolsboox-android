package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * THE TITLE HE WROTE WITH HIS OWN HAND — kept as ink, beside the name index, never inside it.
 *
 * Michael asked "Can I hand write the title?", and then said what it should do: "Keep the ink, put
 * it on page one — it always goes at the top right or something like that with a show/don't show
 * toggle." Both halves matter and they are different things:
 *
 *  • The TEXT is what a title IS to the app. [WritePageStore] / [SynthPageStore] hold it, the
 *    directory searches and sorts it, the day-page header draws it, and the ‹ N › menu lists it. A
 *    title that exists only as a picture cannot be found, and findability is the entire reason
 *    naming exists — so the handwriting is recognised into an editable field he must read and can
 *    correct BEFORE anything is saved (see `promptTitle`), and that corrected string is the name.
 *  • The INK is what the title LOOKS like in his hand. It is his writing, and throwing it away the
 *    moment a machine had read it would be the same bargain the lasso OCR refused to make. So it is
 *    kept, drawn once — on page ONE of the document, top right — and can be turned off.
 *
 * ── WHY THE INK IS NOT IN pages.json ──────────────────────────────────────────────────────────
 *
 * `pages.json` is a few hundred bytes of key/name/date that round-trips through WebDAV on every
 * rename. A PNG inlined into it — base64, so a third larger again — would turn the smallest, most
 * frequently-synced file in the fork into a file whose size is set by how ornately he writes. This
 * fork has been burned twice by exactly that shape (the 86 MB day file; the card index that
 * deliberately stores a rectangle and not the pixels — see [PickingsCards]'s "What is NOT here"),
 * and the lesson both times was the same: an index holds identities, and the payload lives beside
 * it under a name the index can compute.
 *
 * So: one PNG per document at `filesDir/<index dir>/title-ink/<id>.png`, beside `pages.json` rather
 * than in it, plus one small manifest `title-ink.json` in the same directory carrying the show flag.
 * The manifest is ALSO the file inventory — it lists every id that has a face — which is what lets
 * [sync] fetch the other device's faces without a WebDAV directory walk (Depth:infinity is a 403 on
 * this server, and a Depth:1 walk to answer "which ids exist" is a request per rename).
 *
 * ── WHY THE ID IS THE INDEX'S OWN IDENTITY ────────────────────────────────────────────────────
 *
 * [idFor] does not invent a key. It reproduces, exactly, the rule each store already uses to find a
 * row: Write's daily page is scoped by key AND date (every day shares the key "write", so a global
 * scope would let one day's face stand in for all of them), and a minted document is unique by key
 * alone and looked up by key alone. Getting this wrong in either direction is invisible until it
 * isn't: a date-scoped named document would lose its face the moment one of its pages was carried
 * to another day by "📅 Go to a date…", and a globally-scoped daily page would paint the 21st's
 * handwriting onto the 22nd.
 *
 * ── UNTITLING KEEPS THE FACE ──────────────────────────────────────────────────────────────────
 *
 * [LedgerDocuments.untitle] is a rename to BLANK rather than a removal of the index row, and its
 * note says why: "A blank name costs one row and strands nothing." That choice is what this store
 * relies on. Untitling leaves the PNG exactly where it is, so untitle → retitle returns the face he
 * wrote; only [LedgerDocuments.forget], which runs when the pages themselves have already been
 * erased, calls [forget] here. The page stops DRAWING the ink while the document is untitled, which
 * is a different question and answered where the drawing is (a written title with no title under it
 * would be the page contradicting the directory).
 */
object LedgerTitleInk {

    /** The faces live in a folder beside the index file, not in it. */
    private const val SUBDIR = "title-ink"

    /** Show flags + the id inventory [sync] needs. Beside `pages.json`, never inside it. */
    private const val MANIFEST = "title-ink.json"

    /**
     * What a stored face is capped at. The Boox panel is 1404 px wide and the band this is drawn
     * into is ~52 px tall, so 160 px of stored height is already double what the e-ink can show —
     * the headroom is for the iPad twin, which renders the same page at a higher scale. Beyond that
     * it is bytes nobody can see, on a file that syncs.
     */
    private const val STORE_MAX_WIDTH = 1200
    private const val STORE_MAX_HEIGHT = 160

    /**
     * One row of the manifest.
     *
     * [gone] is a headstone rather than an absent row for the reason [LedgerDocumentTombstones]
     * exists at all: the merge below is a union, and an absence loses a union — a face deleted here
     * would be handed straight back by the next device's copy of the manifest. [at] is the
     * last-write clock the merge resolves on.
     */
    data class Face(val id: String, val show: Boolean, val gone: Boolean, val at: Long)

    /**
     * Which surfaces can hold a written title.
     *
     * The multi-page document surfaces, and for the same reason they are the ones
     * [LedgerDocuments.canDelete] admits: a Write, Synthesize, Grid or Jot document is a piece you
     * return to, whose title has nowhere to live but a menu — which is precisely the gap a title
     * drawn on the page fills. A Pickings board is one page of a day and its name already rides its
     * own row in the picker; a Text Note IS its title field. Neither has a page-one header for a
     * face to sit beside, so offering the pen there would promise something the template cannot draw.
     *
     * Grid and Jot did not arrive here for free, and that is worth recording: this list, [idFor] and
     * [indexDir] are three explicit whitelists, so a surface that gains a name index still has no
     * face until it is named in all three. The template had to be taught to DRAW one as well (see
     * [CalendarDayPageNotes.drawPage]) — a grid page's margin had no title in it at all before this.
     */
    fun supports(surface: String?): Boolean =
        surface == LedgerDocuments.WRITE || surface == LedgerDocuments.SYNTHESIZE ||
            surface == LedgerDocuments.GRID || surface == LedgerDocuments.JOT

    /**
     * The id a document's face is filed under — the SAME identity its name index looks a row up by.
     *
     * [date] therefore matters only for Write's daily page. Callers that have a document's home
     * date and callers that only have the day on screen both get the right answer for a minted
     * document, because a minted key is unique and ignores the date entirely; for the daily page
     * the day on screen IS the scope. Null when the surface or the key can hold no name at all —
     * the daily Synthesize page being the one that reaches here, exactly as in
     * [LedgerDocuments.canRename].
     */
    fun idFor(surface: String?, key: String?, date: LocalDate): String? {
        val base = key?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return null
        return when (surface) {
            LedgerDocuments.WRITE ->
                if (base == WritePageStore.DEFAULT_KEY) WritePageStore.idOf(base, date) else base
            LedgerDocuments.SYNTHESIZE ->
                if (base == SynthPageStore.DEFAULT_KEY) null else base
            // Write's rule again, because Grid and Jot ARE Write's rule: their daily page is scoped
            // by key AND date (every day shares "grid"), a minted document is unique by key alone.
            LedgerDocuments.GRID ->
                if (base == GridPageStore.defaultKey) GridPageStore.idOf(base, date) else base
            LedgerDocuments.JOT ->
                if (base == JotPageStore.defaultKey) JotPageStore.idOf(base, date) else base
            else -> null
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────────────────────────

    /** Whether this document has a written face on disk at all — what makes the show/hide row
     *  honest, and what the page asks before it goes looking for a bitmap. */
    fun has(context: Context, surface: String?, key: String?, date: LocalDate): Boolean {
        val id = idFor(surface, key, date) ?: return false
        val row = manifest(context, surface!!)[id]
        if (row != null && row.gone) return false
        return fileFor(context, surface, id).exists()
    }

    /** Whether the face is currently drawn on page one. Defaults to TRUE: he wrote it in order to
     *  have it, and a face that arrived invisible would look like the writing had been thrown away. */
    fun isShown(context: Context, surface: String?, key: String?, date: LocalDate): Boolean {
        val id = idFor(surface, key, date) ?: return false
        val row = manifest(context, surface!!)[id] ?: return true
        return !row.gone && row.show
    }

    /**
     * The face to draw, or null when there is none, it is hidden, or the file will not decode.
     *
     * Memoized, and that is not a micro-optimisation either: this is called from
     * [CalendarDayPageNotes.drawPage], which runs on every page turn and every template repaint, on
     * e-ink. Decoding even a small PNG on that path once per draw is a cost with no upside, since
     * the answer changes only when something in this file writes it — so every writer drops the memo
     * and nothing else has to think about it.
     */
    fun face(context: Context, surface: String?, key: String?, date: LocalDate): Bitmap? {
        val id = idFor(surface, key, date) ?: return null
        val cacheKey = "$surface/$id"
        // The memo is consulted BEFORE the manifest, not after, and holds the drawable answer rather
        // than the decoded file: a hidden face and a missing one are both "nothing to draw", and
        // caching only the bitmap would still have read and parsed the manifest on every repaint to
        // decide which. Every writer here drops the whole memo, so a stale null cannot outlive the
        // toggle that caused it.
        synchronized(lock) { if (memo.containsKey(cacheKey)) return memo[cacheKey] }
        val file = fileFor(context, surface!!, id)
        val bmp = if (!file.exists() || !isShown(context, surface, key, date)) null
        else runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .onFailure { Timber.w(it, "title ink decode failed for $id") }.getOrNull()
        synchronized(lock) {
            memo[cacheKey] = bmp
            while (memo.size > 6) memo.remove(memo.keys.first())
        }
        return bmp
    }

    // ── Writing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Keep [written] as this document's face and show it.
     *
     * Called only from the naming dialog, and only alongside the name the recognition produced and
     * he approved — the two halves are written together on purpose, because a face saved without a
     * name is the picture-only title this whole store exists to avoid.
     *
     * Saving a face un-hides it. Writing a new title by hand is a positive act; leaving it invisible
     * because the previous face for this document had been toggled off months ago would read as the
     * save having silently failed.
     */
    fun put(context: Context, surface: String?, key: String?, date: LocalDate, written: Bitmap) {
        val id = idFor(surface, key, date) ?: return
        runCatching {
            val fitted = fit(written)
            val bytes = ByteArrayOutputStream()
                .also { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (fitted !== written) fitted.recycle()
            fileFor(context, surface!!, id).writeBytes(bytes)
            writeRow(context, surface, Face(id, show = true, gone = false, at = System.currentTimeMillis()))
        }.onFailure { Timber.w(it, "title ink write failed for $id") }
        sync(context, surface)
    }

    /** Turn the face on or off for this document. The ink itself is untouched — "don't show" is a
     *  preference about the page, not a deletion, and the row survives so the other devices agree. */
    fun setShown(context: Context, surface: String?, key: String?, date: LocalDate, show: Boolean) {
        val id = idFor(surface, key, date) ?: return
        writeRow(context, surface!!, Face(id, show = show, gone = false, at = System.currentTimeMillis()))
        sync(context, surface)
    }

    /**
     * Drop the face and record that it is gone.
     *
     * Called from [LedgerDocuments.forget], i.e. only after the document's PAGES have already been
     * erased — never from [LedgerDocuments.untitle], which is a rename to blank precisely so that
     * everything hanging off the row (this included) survives an untitle/retitle cycle.
     */
    fun forget(context: Context, surface: String?, key: String?, date: LocalDate) {
        val id = idFor(surface, key, date) ?: return
        runCatching { fileFor(context, surface!!, id).delete() }
        writeRow(context, surface!!, Face(id, show = false, gone = true, at = System.currentTimeMillis()))
        sync(context, surface)
    }

    // ── Sync ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Round-trip the manifest, then the faces the manifest says the other side has and we do not.
     *
     * The order matters and is the opposite of the obvious one: faces are pushed BEFORE the manifest
     * is, so a device that reads the manifest always finds the file it names. A manifest that
     * arrived first would send the other device to a 404 and, because a miss is silent here, it
     * would look like a face that simply never synced.
     *
     * Nothing is re-uploaded once the two sides agree: a local face is pushed only when the remote
     * row is missing or older, and the merged manifest we push at the end makes them equal — so the
     * next sync finds nothing to do. That is what keeps this off the "every rename ships a PNG"
     * road that [PickingsCards] refuses to walk at all (it syncs nothing, being derivable; a
     * handwritten title is authored, so losing it loses something no device can recompute).
     */
    fun sync(context: Context, surface: String?) {
        if (!supports(surface)) return
        val dir = indexDir(surface) ?: return
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = manifest(context, surface!!)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                .pull(context, "$dir/$MANIFEST")
            val remote = decode(remoteText)

            // Newest write wins per id, both directions — the same last-writer rule the show flag
            // needs to mean anything across devices, and the only rule under which a headstone can
            // beat the copy of the row that predates it.
            val merged = LinkedHashMap<String, Face>(local)
            for ((id, r) in remote) {
                val l = merged[id]
                if (l == null || r.at > l.at) merged[id] = r
            }

            for ((id, row) in merged) {
                val file = fileFor(context, surface, id)
                if (row.gone) { runCatching { file.delete() }; continue }
                val remoteRow = remote[id]
                val localRow = local[id]
                if (!file.exists()) {
                    // The other device has a face we've never seen — fetch it. A miss is silent and
                    // harmless: the manifest row stays, so the next sync tries again.
                    if (remoteRow != null) pullFace(context, dir, id, file)
                } else if (localRow != null && (remoteRow == null || remoteRow.at < localRow.at)) {
                    pushFace(context, dir, id, file)
                }
            }

            if (merged != local) saveManifest(context, surface, merged.values.toList())
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                .push(context, "$dir/$MANIFEST", encode(merged.values.toList()))
        }
    }

    private fun pushFace(context: Context, dir: String, id: String, file: File) {
        val svc = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.service(context) ?: return
        runCatching {
            svc.uploadBytes(file.readBytes(), "$dir/$SUBDIR/${safe(id)}.png", PNG_MEDIA_TYPE)
        }.onFailure { Timber.w(it, "title ink push failed for $id") }
    }

    private fun pullFace(context: Context, dir: String, id: String, file: File) {
        val svc = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.service(context) ?: return
        runCatching {
            val bytes = svc.download("$dir/$SUBDIR/${safe(id)}.png") ?: return
            if (bytes.isEmpty()) return
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            dropMemo()
        }.onFailure { Timber.w(it, "title ink pull failed for $id") }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────

    /** A face goes up as a PNG, not as JSON. [UltrabridgeWebDavService.uploadBytes] defaults to
     *  application/json because every other sidecar is; a server or proxy that believes the header
     *  would be entitled to mangle a binary body sent under it. */
    private val PNG_MEDIA_TYPE = "image/png".toMediaType()

    private val lock = Any()

    /** id → decoded face (null meaning "looked, there isn't one"), dropped by every writer. */
    private val memo = LinkedHashMap<String, Bitmap?>()

    private fun dropMemo() { synchronized(lock) { memo.clear() } }

    /** Which index directory this surface's names live in — the faces go in a folder inside it, so
     *  a document's title and the picture of its title are never in two unrelated places. */
    private fun indexDir(surface: String?): String? = when (surface) {
        LedgerDocuments.WRITE -> WritePageStore.DIR
        LedgerDocuments.SYNTHESIZE -> SynthPageStore.DIR
        LedgerDocuments.GRID -> GridPageStore.dir
        LedgerDocuments.JOT -> JotPageStore.dir
        else -> null
    }

    /**
     * A file name from an id, readable and URL-safe.
     *
     * Readable rather than hashed on purpose: "write-1753900000000.png" and "write-2026-07-30.png"
     * can be matched to a row in `pages.json` by eye when something has gone wrong on a device that
     * is 3,000 miles away, and an md5 cannot. The only characters an id can carry that a WebDAV path
     * would have to escape are the '|' of Write's date scope and the ':' nothing here uses, so the
     * substitution is total and collisions are not reachable: keys are "write" / "write-<millis>" /
     * "synthesize-<millis>" and dates are ISO.
     */
    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "-")

    private fun dir(context: Context, surface: String): File =
        File(File(context.filesDir, indexDir(surface) ?: "write-index"), SUBDIR).apply { mkdirs() }

    private fun fileFor(context: Context, surface: String, id: String): File =
        File(dir(context, surface), "${safe(id)}.png")

    private fun manifestFile(context: Context, surface: String): File =
        File(File(context.filesDir, indexDir(surface) ?: "write-index").apply { mkdirs() }, MANIFEST)

    private fun manifest(context: Context, surface: String): Map<String, Face> =
        decode(runCatching { manifestFile(context, surface).takeIf { it.exists() }?.readText() }.getOrNull())

    private fun decode(text: String?): Map<String, Face> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            val arr = JSONObject(text).optJSONArray("ink") ?: JSONArray()
            val out = LinkedHashMap<String, Face>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out[id] = Face(
                    id = id,
                    show = o.optBoolean("show", true),
                    gone = o.optBoolean("gone", false),
                    at = o.optLong("at", 0L),
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** `{"ink":[{"id":…,"show":…,"gone":…,"at":…}]}` — the shape iOS reads and writes unchanged.
     *  An object with one named array rather than a bare array, so a later field (a caption, a
     *  chosen corner) has somewhere to go that isn't a fifth key on every row. */
    private fun encode(rows: List<Face>): String {
        val arr = JSONArray()
        for (r in rows) arr.put(
            JSONObject().put("id", r.id).put("show", r.show).put("gone", r.gone).put("at", r.at)
        )
        return JSONObject().put("ink", arr).toString()
    }

    private fun saveManifest(context: Context, surface: String, rows: List<Face>) {
        runCatching { manifestFile(context, surface).writeText(encode(rows)) }
            .onFailure { Timber.w(it, "title ink manifest save failed") }
        dropMemo()
    }

    private fun writeRow(context: Context, surface: String, row: Face) {
        synchronized(lock) {
            val rows = LinkedHashMap(manifest(context, surface))
            rows[row.id] = row
            saveManifest(context, surface, rows.values.toList())
        }
    }

    /** Scale to fit inside the store caps, preserving aspect. Returns the source untouched when it
     *  already fits, so the ordinary one-line title is never resampled for nothing. */
    private fun fit(src: Bitmap): Bitmap {
        val scale = minOf(
            1f,
            STORE_MAX_WIDTH / src.width.toFloat(),
            STORE_MAX_HEIGHT / src.height.toFloat()
        )
        if (scale >= 1f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
