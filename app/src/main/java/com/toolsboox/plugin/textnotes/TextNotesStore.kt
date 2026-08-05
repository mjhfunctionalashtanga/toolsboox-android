package com.toolsboox.plugin.textnotes

import android.content.Context
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/** One titled text note. [updatedAt] is the last-edit time, used to merge across devices. */
data class TextNote(val id: String, var title: String, var body: String, var updatedAt: Long = 0L)

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

    private fun parse(text: String): MutableList<TextNote> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TextNote(o.optString("id"), o.optString("title"), o.optString("body"), o.optLong("u", 0L))
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun serialize(notes: List<TextNote>): String = JSONArray().apply {
        for (n in notes) put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("u", n.updatedAt))
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
        return note
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
