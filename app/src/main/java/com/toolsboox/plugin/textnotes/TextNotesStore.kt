package com.toolsboox.plugin.textnotes

import android.content.Context
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * One titled text note. [updatedAt] is the last-edit time, used to merge across devices.
 *
 * [images] is the ink a bridged note carries (DESIGN-notes-bridge-2026-08-22, "Bridged Boox notes
 * carry their ink"): media-lane refs ("media/<sha256>.<png|jpg>") the VPS mirror attaches to notes
 * that reproduce a Boox-device note, rendered read-only beneath the body. The legacy-key rule,
 * applied to a brand-new key: default empty, so every note written before the field existed — and
 * every note that simply has no ink — parses and behaves exactly as it always did.
 */
data class TextNote(
    val id: String, var title: String, var body: String, var updatedAt: Long = 0L,
    val images: List<String> = emptyList()
)

/**
 * Local-first persistence for Text Notes — MULTIPLE titled notes per day. Stored as a JSON array at
 * filesDir/text-notes/notes-YYYY-MM-DD.json. Instant, no network in the write path (Simplenote feel).
 *
 * WebDAV is a true round-trip, NOT push-only: [sync] pulls the remote copy and merges it with local
 * by note id (newest [updatedAt] wins per note, union of ids), so two devices editing different notes
 * on the same day no longer clobber each other. Old single-note .txt files migrate on first load.
 */
object TextNotesStore {
    private const val REMOTE_DIR = "text-notes"
    private fun dir(context: Context) = File(context.filesDir, "text-notes").apply { mkdirs() }
    private fun jsonFile(context: Context, date: LocalDate) = File(dir(context), "notes-$date.json")
    private fun legacyTxt(context: Context, date: LocalDate) = File(dir(context), "notes-$date.txt")
    private fun remotePath(date: LocalDate) = "$REMOTE_DIR/notes-$date.json"

    // internal, not private: parse/serialize ARE the wire contract (the VPS bridge writes what
    // parse reads), and a contract is a plain JVM unit test — LedgerMedia's pattern.
    internal fun parse(text: String): MutableList<TextNote> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TextNote(
                o.optString("id"), o.optString("title"), o.optString("body"), o.optLong("u", 0L),
                // A store that rewrites files wholesale on every save MUST carry what it read, or
                // "unknown keys are ignored" quietly becomes "unknown keys are deleted": without
                // this, the first device edit of a bridged day would strip its ink and push the
                // loss fleet-wide through sync.
                images = o.optJSONArray("images")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { s -> s.isNotBlank() } }
                } ?: emptyList()
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    internal fun serialize(notes: List<TextNote>): String = JSONArray().apply {
        for (n in notes) put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("u", n.updatedAt)
            // Only when there is ink: an imageless note's JSON stays byte-for-byte what this store
            // always wrote, so nothing downstream sees a new key it didn't ask about.
            .apply { if (n.images.isNotEmpty()) put("images", JSONArray(n.images)) })
    }.toString()

    fun load(context: Context, date: LocalDate): MutableList<TextNote> {
        val f = jsonFile(context, date)
        if (f.exists()) return parse(f.readText())
        // Migrate a legacy single-note .txt into one titled note.
        val legacy = legacyTxt(context, date)
        if (legacy.exists()) {
            val body = runCatching { legacy.readText() }.getOrDefault("")
            if (body.isNotBlank()) {
                val notes = mutableListOf(TextNote(newId(), "Note", body, System.currentTimeMillis()))
                save(context, date, notes); runCatching { legacy.delete() }
                return notes
            }
        }
        return mutableListOf()
    }

    fun save(context: Context, date: LocalDate, notes: List<TextNote>) {
        runCatching { jsonFile(context, date).writeText(serialize(notes)) }
            .onFailure { Timber.w(it, "text notes save failed for $date") }
        mirrorLater(context, date)
    }

    /**
     * Push this day to the Markdown mirror, if one is declared.
     *
     * OFF THE WRITE PATH, on a plain thread. The write above is what makes typing feel instant —
     * "no network in the write path (Simplenote feel)" is this store's whole design — and a mirror
     * pass touches a SAF tree, which can be a network mount. A save that waited for it would put
     * cloud latency behind every keystroke-flush.
     *
     * Failures are silent by design: the mirror is a copy, and a copy that could not be written is
     * not a reason to interrupt someone writing. It will be caught by the next save, or by the
     * mirror's own pass when the folder comes back.
     */
    private fun mirrorLater(context: Context, date: LocalDate) {
        val app = context.applicationContext
        Thread {
            runCatching { com.toolsboox.plugin.calendar.ot.NotesMirror.mirror(app, date) }
        }.start()
    }

    /** Append a new titled note (used by Ask-my-Ledger create). Returns the created note. */
    fun addNote(context: Context, date: LocalDate, title: String, body: String): TextNote {
        val notes = load(context, date)
        val note = TextNote(newId(), title.ifBlank { "Note" }, body, System.currentTimeMillis())
        notes.add(note); save(context, date, notes); sync(context, date, null)
        harvestLater(context, listOf(note))   // a note made by Ask, Share or Capture is a save too
        return note
    }

    /**
     * HARVEST #tags AND [[links]] FROM TEXT NOTES ON SAVE (Michael 2026-10-11: "tags aren't
     * complete" · "The links aren't quite working yet"). Text Notes are where he types Markdown,
     * and until now the B6 recorded nothing from them. PARITY: iOS records #tags only, from its
     * editor's persist() — not [[links]], and nothing for a note made by Ask/Share/Capture.
     *
     * A text note is an OBJECT, not a page — `note://<id>` ([com.toolsboox.ot.LedgerUri.note], the
     * same address iOS uses) — so its tags are tag ↔ object edges ([LedgerTags.recordObject]) and
     * show in a tag's "Also tagged" section, and its links are object → `note://<target>` edges
     * ([com.toolsboox.plugin.calendar.ot.LedgerLinks.recordObjectLinks]) that list under the
     * target's backlinks. Both ride the synced graph, so the other device sees them.
     *
     * Only what is NEW (or re-labelled after a title change) is written — every graph write rewrites
     * and re-syncs the whole file. Additive, as iOS: a tag deleted from the text keeps its edge until
     * it is cut in the picker (a picker-made tag looks the same, and must not be silently undone).
     * Title and body both count, the same text iOS reads ("title\nbody").
     */
    fun harvest(context: Context, notes: List<TextNote>) {
        val tags = com.toolsboox.plugin.calendar.ot.LedgerTags
        val links = com.toolsboox.plugin.calendar.ot.LedgerLinks
        for (n in notes) {
            if (n.title.isBlank() && n.body.isBlank()) continue
            val uri = com.toolsboox.ot.LedgerUri.note(n.id)
            val label = n.title.ifBlank { "Note" }
            val text = n.title + "\n" + n.body
            val written = tags.extract(text)
            if (written.isNotEmpty()) {
                val joined = com.toolsboox.plugin.calendar.ot.ConnectionStore.touching(context, uri)
                    .filter { it.to == uri && it.from.startsWith("tag://") }
                    .associateBy { it.from.removePrefix("tag://") }
                val fresh = written.filter { t -> joined[t]?.toLabel != label }
                if (fresh.isNotEmpty()) tags.recordObject(context, uri, label, fresh.joinToString(" ") { "#$it" })
            }
            links.recordObjectLinks(context, uri, label, text)
        }
    }

    /** [harvest] off the caller's thread — the editor saves on the main thread, and every graph
     *  write is a file rewrite. A snapshot of the notes is taken first: the editor keeps mutating
     *  its own objects. */
    fun harvestLater(context: Context, notes: List<TextNote>) {
        val app = context.applicationContext
        val snapshot = notes.map { it.copy() }
        graphWork.execute {
            runCatching { harvest(app, snapshot) }.onFailure { Timber.w(it, "text notes harvest failed") }
        }
    }

    /**
     * A DELETED Text Note lets go of the graph (review of dbe1a38d): every edge touching
     * `note://<id>` — the tags and [[links]] its harvest wrote, and any join to a page — is cut.
     * [ConnectionStore.disconnect] tombstones and the tombstone syncs, so the other device lets go
     * too; without it "Also tagged" and the Links index kept a row for a note that is gone, and the
     * tap said "Nothing named “tn-…” yet". On the same single thread as [harvestLater], so a harvest
     * queued before the delete cannot land after it and put the edges back.
     */
    fun forgetLater(context: Context, id: String) {
        if (id.isBlank()) return
        val app = context.applicationContext
        graphWork.execute {
            runCatching {
                val uri = com.toolsboox.ot.LedgerUri.note(id)
                for (edge in com.toolsboox.plugin.calendar.ot.ConnectionStore.touching(app, uri)) {
                    com.toolsboox.plugin.calendar.ot.ConnectionStore.disconnect(app, edge.id)
                }
            }.onFailure { Timber.w(it, "text notes forget failed") }
        }
    }

    /** One background thread for every Text Notes graph write — harvests and forgets run in the
     *  order they were asked for. */
    private val graphWork = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "text-notes-graph").apply { isDaemon = true }
    }

    fun newId(): String = "tn-${System.currentTimeMillis()}-${(0..9999).random()}"

    /**
     * Every day that has a notes file, newest first — from the FILENAMES, nothing parsed.
     *
     * The whole-ledger directory lists text notes across all time, and it has to build that list
     * while a menu opens. Note files are small, but there is one per day forever, so the directory
     * takes the days from this and reads only the days it is actually about to show.
     */
    fun dates(context: Context): List<LocalDate> =
        dir(context).listFiles()
            ?.mapNotNull { f ->
                val stem = f.name.removePrefix("notes-")
                if (!f.name.startsWith("notes-") || !f.name.endsWith(".json")) null
                else runCatching { LocalDate.parse(stem.removeSuffix(".json")) }.getOrNull()
            }
            ?.sortedDescending()
            ?: emptyList()

    /** Merge two note lists by id: keep every id, and for shared ids take the newer [updatedAt]. */
    private fun merge(a: List<TextNote>, b: List<TextNote>): MutableList<TextNote> {
        val byId = LinkedHashMap<String, TextNote>()
        for (n in a) byId[n.id] = n
        for (n in b) {
            val cur = byId[n.id]
            if (cur == null || n.updatedAt > cur.updatedAt) byId[n.id] = n
        }
        return byId.values.toMutableList()
    }

    /**
     * Round-trip the day's notes through WebDAV: pull remote, merge with local by id, save the merged
     * result locally, and push it back. Fire-and-forget from the UI (page-leave / open / add). When the
     * merge changed the local copy, [onMerged] is invoked on a background thread so the caller can reload.
     */
    fun sync(context: Context, date: LocalDate, onMerged: (() -> Unit)?) {
        LedgerSidecarSync.background {
            val local = load(context, date)
            val remoteText = LedgerSidecarSync.pull(context, remotePath(date))
            val merged = if (remoteText.isNullOrBlank()) local else merge(local, parse(remoteText))
            // Persist + push only when something actually differs, to avoid needless churn.
            val mergedText = serialize(merged)
            if (mergedText != serialize(local)) { save(context, date, merged); onMerged?.invoke() }
            LedgerSidecarSync.push(context, remotePath(date), mergedText)
        }
    }
}
