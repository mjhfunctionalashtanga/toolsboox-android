package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/** One Synthesize page: its note-page [key], display [name], and the [date] it lives on. */
// The Saber folder label rides every row (Michael, 09-02) — "" = unfiled. Additive and
// tolerant on both forks THE SAME DAY: either fork rewriting this index without the field
// would strip every filing — which is why BOTH write sites and BOTH read sites carry it.
data class SynthPage(val key: String, var name: String, val date: LocalDate, var folder: String = "")

/**
 * Registry of named Synthesize pages.
 *
 * Modelled on [PickingsStore] but with one deliberate difference: pickings are per-day and reset,
 * whereas a synthesis is a TOPIC you return to over weeks. So this registry is global — one file,
 * not one per day — and each page remembers the day it was made on. The page's CONTENT still rides
 * that day's JSON under its key (reusing the whole day-note surface), but the page persists in the
 * index and is reached from anywhere.
 *
 * That persistence is also the coarse boundary the pipeline needs: surrogacy work on its own page,
 * Android work on another, each bounded by what you carried onto it — so the connection layer's
 * well-meant links across unrelated worlds never feed the wrong outline.
 *
 * The default daily "synthesize" page is left to [PickingsStore]-style per-day behaviour and is
 * NOT listed here; this is only the named topic pages.
 */
object SynthPageStore {
    const val DIR = "synth-index"
    private const val FILE = "pages.json"
    const val DEFAULT_KEY = "synthesize"

    /** True for the daily page or any named topic page. */
    fun isSynth(key: String?): Boolean =
        key != null && (key == DEFAULT_KEY || key.startsWith("synthesize-"))

    private fun file(context: Context) =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, FILE) }

    /** Every named topic page, newest first. The daily page is not one of these. */
    fun list(context: Context): MutableList<SynthPage> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
                SynthPage(o.optString("key"), o.optString("name"), d, o.optString("folder"))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, pages: List<SynthPage>) {
        runCatching {
            val arr = JSONArray()
            for (p in pages) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString())
                .put("folder", p.folder))
            file(context).writeText(arr.toString())
        }.onFailure { Timber.w(it, "synth index save failed") }
    }

    /** Start a new topic page, homed on [date] (today by default). [key] is chosen by a caller that
     *  wrote the page before naming it (❖ Synthesize this list picks it at ask time, so a reply that
     *  lands after a kill lands under the same key) — and a key already listed is simply returned. */
    fun add(context: Context, name: String, date: LocalDate = LocalDate.now(),
            key: String = "synthesize-${System.currentTimeMillis()}"): SynthPage {
        val pages = list(context)
        pages.firstOrNull { it.key == key }?.let { return it }
        val page = SynthPage(key,
            name.ifBlank { LedgerDefaultName.forDate(date, pages.map { it.name }) }, date)
        pages.add(0, page); save(context, pages)
        sync(context)
        return page
    }

    /** File (or unfile) a topic page — key-scoped, like everything in this index. */
    fun setFolder(context: Context, key: String, folder: String) {
        val pages = list(context)
        pages.firstOrNull { it.key == key }?.let { it.folder = folder; save(context, pages); sync(context) }
    }

    fun rename(context: Context, key: String, name: String) {
        val pages = list(context)
        pages.firstOrNull { it.key == key }?.let { it.name = name; save(context, pages) }
        LedgerDocumentTombstones.forget(context, DIR, key)
        sync(context)
    }

    /** Drop the entry AND record that it is gone — see [WritePageStore.delete] and
     *  [LedgerDocumentTombstones] for why an absence cannot express a deletion here. Synthesize
     *  merges on the key alone (its keys are unique), so that is the headstone's id. */
    fun delete(context: Context, key: String) {
        save(context, list(context).filterNot { it.key == key })
        LedgerDocumentTombstones.add(context, DIR, key)
        sync(context)
    }

    /** The display name for any synth key — the daily page included. */
    fun nameOf(context: Context, key: String?): String = when {
        key == DEFAULT_KEY || key == null -> "Synthesize"
        else -> list(context).firstOrNull { it.key == key }?.name ?: "Synthesis"
    }

    private fun remotePath() = "$DIR/$FILE"

    /** Round-trip the registry so a topic page made on one device appears on the others. */
    fun sync(context: Context) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            // Headstones first — see [WritePageStore.sync]. Same epochs round trip, its own paths,
            // invisible to an iOS reader of pages.json.
            val dead = LedgerDocumentTombstones.roundTrip(context, DIR)

            val local = list(context)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath())
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byKey = LinkedHashMap<String, SynthPage>()
                for (p in local) byKey[p.key] = p
                runCatching {
                    val arr = JSONArray(remoteText)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i); val k = o.optString("key")
                        val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull()
                        if (k.isNotBlank() && d != null && !byKey.containsKey(k))
                            byKey[k] = SynthPage(k, o.optString("name"), d, o.optString("folder"))
                    }
                }
                byKey.values.toMutableList()
            }
            merged.removeAll { it.key in dead }
            if (merged.map { it.key } != local.map { it.key }) save(context, merged)
            val arr = JSONArray()
            for (p in merged) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString())
                .put("folder", p.folder))
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(), arr.toString())
        }
    }
}
