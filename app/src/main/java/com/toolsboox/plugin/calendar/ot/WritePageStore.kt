package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/** One Write document: its note-page [key], display [name], and the [date] it was started on. */
data class WritePage(val key: String, var name: String, val date: LocalDate)

/**
 * Registry of Write documents — the store Write never had.
 *
 * Pickings had [PickingsStore] and Synthesize had [SynthPageStore], but Write was only ever the bare
 * key "write" plus its "write#n" sub-pages: one implicit, unnameable document per day, with no way
 * to say what you were writing or to keep two pieces apart. Every other making surface could answer
 * "which one of these am I in"; the one surface actually meant for long-form couldn't.
 *
 * Deliberately modelled on [SynthPageStore] line for line — same global index file, same three
 * fields, same pull-merge-push sync — because Write and Synthesize are the SAME shape in Michael's
 * model (nameable, multi-page, name defaults to the start date). Two stores that agree on their
 * idiom can't drift; two that were each improvised would.
 *
 * NOTHING here touches page content. A document's strokes/images/text still ride the day JSON under
 * its key exactly as before, so every day already written under the plain "write" key keeps its
 * content and simply gains a title. That is why the daily document is NOT recorded here on creation:
 * it exists by virtue of the key, and the index only ever holds the exceptions — the days you
 * renamed, and the standalone named documents.
 *
 * One rule differs from [SynthPageStore], and it is the reason Write can name its daily page at all:
 * an entry whose key is [DEFAULT_KEY] is scoped to its DATE, not to its key. Every day shares the
 * key "write" (that's what makes existing content reachable), so a global index keyed on key alone
 * could hold a name for exactly one day in all of history. Named documents ("write-<millis>") are
 * unique by key and are looked up by key alone, as elsewhere.
 */
object WritePageStore {
    const val DIR = "write-index"
    private const val FILE = "pages.json"
    const val DEFAULT_KEY = "write"

    /** The identity two devices merge an entry on — key AND date, because every day shares the key
     *  "write". [LedgerDocumentTombstones] is handed this same function so a headstone can never
     *  address something different from what the merge addresses. */
    fun idOf(key: String, date: LocalDate) = "$key|$date"

    private fun idOf(p: WritePage) = idOf(p.key, p.date)

    /** True for the daily Write page or any named Write document (sub-page tails included). */
    fun isWrite(key: String?): Boolean {
        val base = key?.substringBefore('#') ?: return false
        return base == DEFAULT_KEY || base.startsWith("write-")
    }

    private fun file(context: Context) =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, FILE) }

    /**
     * Every recorded document: the standalone named ones AND the renamed daily pages. Newest first.
     * A day whose Write page was never renamed appears nowhere in here — [LedgerDocuments] supplies
     * the implicit daily document for the date being viewed, so an unnamed day is a titled document
     * regardless (titled by its date), not an absence.
     */
    fun list(context: Context): MutableList<WritePage> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
                WritePage(o.optString("key"), o.optString("name"), d)
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    /** Only the standalone named documents — the daily-page renames filtered out. */
    fun documents(context: Context): List<WritePage> = list(context).filter { it.key != DEFAULT_KEY }

    fun save(context: Context, pages: List<WritePage>) {
        runCatching {
            val arr = JSONArray()
            for (p in pages) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString()))
            file(context).writeText(arr.toString())
        }.onFailure { Timber.w(it, "write index save failed") }
    }

    /** Start a new named document, homed on [date] (today by default). */
    fun add(context: Context, name: String, date: LocalDate = LocalDate.now()): WritePage {
        val pages = list(context)
        val page = WritePage("write-${System.currentTimeMillis()}",
            name.ifBlank { "Writing ${pages.count { it.key != DEFAULT_KEY } + 1}" }, date)
        pages.add(0, page); save(context, pages)
        sync(context)
        return page
    }

    /**
     * Name a document. [date] only matters for the daily page, which is date-scoped (see the class
     * note): renaming the 21st's Write page must not rename the 22nd's. Naming a daily page for the
     * first time INSERTS its entry — the index holds only the days you bothered to title.
     */
    fun rename(context: Context, key: String, name: String, date: LocalDate) {
        val pages = list(context)
        val existing = pages.firstOrNull { it.key == key && (key != DEFAULT_KEY || it.date == date) }
        if (existing != null) existing.name = name
        else pages.add(0, WritePage(key, name, date))
        save(context, pages)
        // Writing under an id that was deleted un-deletes it. The daily page is the case that
        // matters: delete the 21st's writing, write on the 21st again, name it — without this the
        // merge would keep subtracting the entry you just made and the name would evaporate on the
        // next sync. A blank name is NOT a re-creation (that is untitling, see LedgerDocuments), but
        // it is still an entry the user wants kept, so the headstone goes either way.
        LedgerDocumentTombstones.forget(context, DIR, idOf(key, date))
        sync(context)
    }

    /**
     * Drop the entry AND record that it is gone.
     *
     * The record is the whole point — see [LedgerDocumentTombstones]. Removing the entry alone is
     * what this method used to do, and it did not work: [sync] folds the server's copy back in
     * whenever the local side lacks it, so the deletion was undone within seconds by a background
     * thread and nothing said so.
     */
    fun delete(context: Context, key: String, date: LocalDate) {
        save(context, list(context).filterNot { it.key == key && (key != DEFAULT_KEY || it.date == date) })
        LedgerDocumentTombstones.add(context, DIR, idOf(key, date))
        sync(context)
    }

    /**
     * The explicit name of a document, or null when it has never been named — null is the signal
     * [LedgerDocuments] needs to fall back to the date, so this must NOT invent a placeholder.
     */
    fun nameOf(context: Context, key: String?, date: LocalDate): String? {
        val base = key?.substringBefore('#') ?: return null
        if (!isWrite(base)) return null
        return list(context)
            .firstOrNull { it.key == base && (base != DEFAULT_KEY || it.date == date) }
            ?.name?.takeIf { it.isNotBlank() }
    }

    private fun remotePath() = "$DIR/$FILE"

    /**
     * Round-trip the registry so a document named on one device appears on the others. Merges on
     * key AND date rather than key alone: the daily pages all share the key "write", so a key-only
     * merge (what [SynthPageStore] can safely do, its keys being unique) would let one day's title
     * swallow every other day's.
     */
    fun sync(context: Context) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            // Headstones first: the union of what every device knows to be deleted is what the
            // entry merge below is allowed to keep. Round-tripped exactly like the index itself, on
            // its own path, so an iOS reader of pages.json never sees it.
            val dead = LedgerDocumentTombstones.merge(
                context,
                DIR,
                com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                    .pull(context, LedgerDocumentTombstones.remotePath(DIR))
            )
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(
                context, LedgerDocumentTombstones.remotePath(DIR), LedgerDocumentTombstones.encode(dead)
            )

            val local = list(context)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath())
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, WritePage>()
                for (p in local) byId[idOf(p)] = p
                runCatching {
                    val arr = JSONArray(remoteText)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i); val k = o.optString("key")
                        val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull()
                        if (k.isNotBlank() && d != null && !byId.containsKey("$k|$d"))
                            byId["$k|$d"] = WritePage(k, o.optString("name"), d)
                    }
                }
                byId.values.toMutableList()
            }
            // Subtract the headstones from BOTH sides. The remote side is the resurrection this
            // exists to stop; the local side is belt and braces for a device that deleted while
            // offline and whose own file was rewritten by an older build in the meantime.
            merged.removeAll { idOf(it) in dead }
            if (merged.map { idOf(it) } != local.map { idOf(it) }) save(context, merged)
            val arr = JSONArray()
            for (p in merged) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString()))
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(), arr.toString())
        }
    }
}
