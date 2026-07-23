package com.toolsboox.plugin.chat.da

import android.content.Context
import com.toolsboox.ot.LedgerUri
import org.json.JSONObject
import java.io.File
import java.util.Date

/**
 * The bridge that turns cute-but-mute ink into roots. When you finish an annotation on a surface that
 * isn't a planner page — a roster note about a person, a margin scribble on an article — its OCR'd
 * text is kept here with provenance (what it's about, where it came from). [LedgerCorpusService] folds
 * these into the corpus under [Section.ANNOTATIONS], so what you scribbled threads with everything
 * else in Roots, Sprouts and Missed Rhizomes. Your own words, woven. Mirrors iOS `AnnotationCorpus`.
 *
 * Unlike iOS — which OCRs the ink here at save time — the Android callers already have the text (they
 * OCR with ML Kit / [com.toolsboox.plugin.calendar.ot.PanelOcr] before calling), so [record] takes the
 * finished text. One entry per `surface:id`; re-recording the same key overwrites, a blank one forgets.
 */
object AnnotationCorpus {

    private fun file(context: Context): File =
        File(context.filesDir, "annotations-corpus").apply { mkdirs() }.let { File(it, "index.json") }

    private fun load(context: Context): MutableMap<String, JSONObject> {
        val f = file(context)
        if (!f.exists()) return LinkedHashMap()
        return runCatching {
            val obj = JSONObject(f.readText())
            val out = LinkedHashMap<String, JSONObject>()
            val keys = obj.keys()
            while (keys.hasNext()) { val k = keys.next(); obj.optJSONObject(k)?.let { out[k] = it } }
            out
        }.getOrDefault(LinkedHashMap())
    }

    private fun save(context: Context, map: Map<String, JSONObject>) {
        runCatching {
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, v)
            // Atomic: write a temp file and rename over the index, so a process kill mid-write leaves the
            // old good file intact rather than a truncated one that load() would read as empty and then
            // overwrite — which would silently discard the whole corpus.
            val dest = file(context)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(dest)) { dest.writeText(obj.toString()); tmp.delete() }
        }
    }

    /**
     * Record an annotation's text with provenance. [surface]+[id] make a key unique across surfaces
     * (e.g. "crm" + "crmnote-42-1690000000"). [uri] is the object's LedgerUri, [label] its human name
     * (the person, the article title). Blank [text] forgets the entry so a cleared note doesn't linger.
     *
     * Synchronized: callers invoke this from Dispatchers.IO, so two roster saves could otherwise
     * interleave the load→mutate→save and lose one annotation.
     */
    @Synchronized
    fun record(context: Context, surface: String, id: String, uri: String, label: String, text: String) {
        val key = "$surface:$id"
        val clean = text.trim()
        val map = load(context)
        if (clean.isEmpty()) { if (map.remove(key) != null) save(context, map); return }
        map[key] = JSONObject().apply {
            put("key", key); put("uri", uri); put("label", label); put("text", clean)
            put("updated", System.currentTimeMillis())
        }
        save(context, map)
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        val map = load(context)
        if (map.remove(key) != null) save(context, map)
    }

    /** The annotation snippets for the corpus — first-party text (your own hand), tagged with where it
     *  was written so a crossing keeps its provenance. */
    fun snippets(context: Context): List<CorpusSnippet> = load(context).values.map { e ->
        val label = e.optString("label").ifBlank { LedgerUri.describe(e.optString("uri")) }
        val date = Date(e.optLong("updated", System.currentTimeMillis()))
        CorpusSnippet(
            section = Section.ANNOTATIONS, date = date, title = label,
            source = "", text = e.optString("text"),
            citation = cite(date, label)
        )
    }

    private fun cite(date: Date, label: String): String {
        val short = if (label.length > 40) label.take(37) + "…" else label
        val ds = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(date)
        return "$ds · annotation · $short"
    }
}
