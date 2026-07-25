package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/** One pickings page for a day: its note-page [key] (drives strokes/images storage) + display [name]. */
data class PickingPage(val key: String, var name: String)

/**
 * Registry of the pickings pages for each day. A day can hold several named pickings boards; the
 * default one keeps the legacy key "pickings" so existing content still shows. Extra boards use keys
 * like "pickings-<timestamp>". The board CONTENT (strokes, dropped gram images) rides the day JSON
 * under each key — this registry only tracks which boards exist and their names.
 */
object PickingsStore {
    private const val DIR = "pickings-index"
    const val DEFAULT_KEY = "pickings"

    fun isPickings(key: String?): Boolean = key != null && (key == DEFAULT_KEY || key.startsWith("pickings-"))

    private fun fileFor(context: Context, date: LocalDate) =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, "$date.json") }

    /** The day's pickings pages, always including the default board first. */
    fun list(context: Context, date: LocalDate): MutableList<PickingPage> {
        val f = fileFor(context, date)
        val pages = runCatching {
            if (!f.exists()) mutableListOf()
            else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it); PickingPage(o.optString("key"), o.optString("name"))
                }.toMutableList()
            }
        }.getOrDefault(mutableListOf())
        if (pages.none { it.key == DEFAULT_KEY }) pages.add(0, PickingPage(DEFAULT_KEY, "Pickings"))
        return pages
    }

    /**
     * Only the boards the index actually RECORDS for [date] — nothing invented. Empty when the day
     * has no index file at all, which keeps "no boards were ever made here" distinguishable from
     * [list]'s "the default board exists in principle". The daily cover leans on that difference:
     * it walks back through past days looking for boards someone really touched, and fabricating
     * a default for every silent day would make each morning look like fourteen.
     */
    fun listSaved(context: Context, date: LocalDate): List<PickingPage> {
        val f = fileFor(context, date)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it); PickingPage(o.optString("key"), o.optString("name"))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, date: LocalDate, pages: List<PickingPage>) {
        runCatching {
            val arr = JSONArray()
            for (p in pages) arr.put(JSONObject().put("key", p.key).put("name", p.name))
            fileFor(context, date).writeText(arr.toString())
        }.onFailure { Timber.w(it, "pickings index save failed for $date") }
    }

    /** Create a new named board and return it. */
    fun add(context: Context, date: LocalDate, name: String): PickingPage {
        val pages = list(context, date)
        val page = PickingPage("pickings-${System.currentTimeMillis()}", name.ifBlank { "Pickings ${pages.size + 1}" })
        pages.add(page); save(context, date, pages)
        sync(context, date)
        return page
    }

    fun rename(context: Context, date: LocalDate, key: String, name: String) {
        val pages = list(context, date)
        pages.firstOrNull { it.key == key }?.let { it.name = name; save(context, date, pages) }
        sync(context, date)
    }

    fun nameOf(context: Context, date: LocalDate, key: String): String =
        list(context, date).firstOrNull { it.key == key }?.name ?: "Pickings"

    private fun remotePath(date: LocalDate) = "$DIR/$date.json"

    /**
     * Round-trip the day's board registry through WebDAV so named boards created on one device appear
     * on the others. Merges by board key (union of keys; a locally-renamed board keeps its local name),
     * writes the merged list back, and pushes it. Fire-and-forget; no-op without Ultrabridge creds.
     */
    fun sync(context: Context, date: LocalDate) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = list(context, date)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath(date))
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byKey = LinkedHashMap<String, PickingPage>()
                for (p in local) byKey[p.key] = p
                runCatching {
                    val arr = JSONArray(remoteText)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i); val k = o.optString("key")
                        if (k.isNotBlank() && !byKey.containsKey(k)) byKey[k] = PickingPage(k, o.optString("name"))
                    }
                }
                byKey.values.toMutableList()
            }
            if (merged.map { it.key } != local.map { it.key }) save(context, date, merged)
            val arr = JSONArray()
            for (p in merged) arr.put(JSONObject().put("key", p.key).put("name", p.name))
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(date), arr.toString())
        }
    }
}
