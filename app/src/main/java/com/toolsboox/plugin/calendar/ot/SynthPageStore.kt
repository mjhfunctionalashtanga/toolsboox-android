package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/** One Synthesize page: its note-page [key], display [name], and the [date] it lives on. */
data class SynthPage(val key: String, var name: String, val date: LocalDate)

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
    private const val DIR = "synth-index"
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
                SynthPage(o.optString("key"), o.optString("name"), d)
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, pages: List<SynthPage>) {
        runCatching {
            val arr = JSONArray()
            for (p in pages) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString()))
            file(context).writeText(arr.toString())
        }.onFailure { Timber.w(it, "synth index save failed") }
    }

    /** Start a new topic page, homed on [date] (today by default). */
    fun add(context: Context, name: String, date: LocalDate = LocalDate.now()): SynthPage {
        val pages = list(context)
        val page = SynthPage("synthesize-${System.currentTimeMillis()}",
            name.ifBlank { "Synthesis ${pages.size + 1}" }, date)
        pages.add(0, page); save(context, pages)
        sync(context)
        return page
    }

    fun rename(context: Context, key: String, name: String) {
        val pages = list(context)
        pages.firstOrNull { it.key == key }?.let { it.name = name; save(context, pages) }
        sync(context)
    }

    fun delete(context: Context, key: String) {
        save(context, list(context).filterNot { it.key == key })
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
                            byKey[k] = SynthPage(k, o.optString("name"), d)
                    }
                }
                byKey.values.toMutableList()
            }
            if (merged.map { it.key } != local.map { it.key }) save(context, merged)
            val arr = JSONArray()
            for (p in merged) arr.put(JSONObject()
                .put("key", p.key).put("name", p.name).put("date", p.date.toString()))
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(), arr.toString())
        }
    }
}
