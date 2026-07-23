package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/** One synthesized idea: a [text] line, its [kind] (question/prompt/outline) and where it came [from]. */
data class SynthesisIdea(val text: String, val kind: String, val from: String)

/**
 * The running list of ideas the Synthesize/Write pipeline has generated for a day — every "3
 * questions", writing prompt, and outline line lands here so they can be reviewed and dropped onto
 * the Synthesize grid as gram cards later, instead of only living as loose text boxes.
 */
object SynthesisIdeaStore {
    private const val DIR = "synthesis-ideas"

    private fun fileFor(context: Context, date: LocalDate) =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, "$date.json") }

    fun list(context: Context, date: LocalDate): MutableList<SynthesisIdea> {
        val f = fileFor(context, date)
        return runCatching {
            if (!f.exists()) mutableListOf()
            else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    SynthesisIdea(o.optString("text"), o.optString("kind"), o.optString("from"))
                }.toMutableList()
            }
        }.getOrDefault(mutableListOf())
    }

    private fun save(context: Context, date: LocalDate, ideas: List<SynthesisIdea>) {
        runCatching {
            val arr = JSONArray()
            for (i in ideas) arr.put(JSONObject().put("text", i.text).put("kind", i.kind).put("from", i.from))
            fileFor(context, date).writeText(arr.toString())
        }.onFailure { Timber.w(it, "synthesis idea save failed for $date") }
    }

    /** Append [lines] as ideas, de-duplicating against what's already stored for the day. */
    fun add(context: Context, date: LocalDate, lines: List<String>, kind: String, from: String) {
        if (lines.isEmpty()) return
        val ideas = list(context, date)
        val seen = ideas.map { it.text.trim() }.toHashSet()
        for (line in lines) {
            val t = line.trim()
            if (t.isNotEmpty() && seen.add(t)) ideas.add(SynthesisIdea(t, kind, from))
        }
        save(context, date, ideas)
        sync(context, date)
    }

    private fun remotePath(date: LocalDate) = "$DIR/$date.json"

    /**
     * Round-trip the day's idea bank through WebDAV so ideas generated on one device (e.g. from a
     * book on the Boox) show up in the grid picker on another. Merges by idea text (union), writes the
     * merged list back, and pushes it. Fire-and-forget; no-op without Ultrabridge creds.
     */
    fun sync(context: Context, date: LocalDate) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = list(context, date)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath(date))
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byText = LinkedHashMap<String, SynthesisIdea>()
                for (i in local) byText[i.text.trim()] = i
                runCatching {
                    val arr = JSONArray(remoteText)
                    for (j in 0 until arr.length()) {
                        val o = arr.getJSONObject(j); val t = o.optString("text").trim()
                        if (t.isNotEmpty() && !byText.containsKey(t))
                            byText[t] = SynthesisIdea(o.optString("text"), o.optString("kind"), o.optString("from"))
                    }
                }
                byText.values.toMutableList()
            }
            if (merged.size != local.size) save(context, date, merged)
            val arr = JSONArray()
            for (i in merged) arr.put(JSONObject().put("text", i.text).put("kind", i.kind).put("from", i.from))
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(date), arr.toString())
        }
    }
}
