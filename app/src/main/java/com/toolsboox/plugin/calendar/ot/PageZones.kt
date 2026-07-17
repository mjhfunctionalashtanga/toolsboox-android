package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Android parity of the iOS zone engine. Every page type declares its OWN layout of labeled
 * zones; each zone captures independently — ink in a TEXT zone is OCR'd (and optionally run
 * through its own AI prompt), an IMAGE zone holds a placed image — and is saved per section.
 * Rects are in the 1404×1872 page design space (same as the templates).
 */
enum class ZoneKind { TEXT, IMAGE }

data class PageZone(
    val id: String,
    val label: String,
    val rect: RectF,          // design space (1404×1872): left, top, right, bottom
    val kind: ZoneKind,
    val aiPrompt: String? = null
)

object PageZones {
    const val PAGE_W = 1404f
    const val PAGE_H = 1872f

    /** Each note-page's own zone layout (keyed by the fragment's notePage string). */
    fun zones(notePage: String?): List<PageZone> = when (notePage) {
        "pickings" -> listOf(
            PageZone("notes", "✎ Notes", RectF(80f, 190f, 1324f, 710f), ZoneKind.TEXT,
                "Tidy these handwritten notes into clean prose, fixing obvious OCR slips. Keep the meaning."),
            PageZone("quotes", "❝ Quotes", RectF(80f, 760f, 1324f, 1220f), ZoneKind.TEXT,
                "Extract the single strongest quotable line from this text, verbatim."),
            PageZone("image1", "Image 1", RectF(80f, 1280f, 680f, 1780f), ZoneKind.IMAGE),
            PageZone("image2", "Image 2", RectF(724f, 1280f, 1324f, 1780f), ZoneKind.IMAGE),
        )
        else -> emptyList()
    }
}

/** Captured section values per note-page + day: filesDir/page-sections/{pageKey}-{date}.json. */
object SectionStore {
    private const val DIR = "page-sections"

    private fun fileFor(context: Context, pageKey: String, date: LocalDate): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "$pageKey-$date.json")
    }

    fun load(context: Context, pageKey: String, date: LocalDate): MutableMap<String, String> {
        val f = fileFor(context, pageKey, date)
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.getString(it) }
            map
        }.getOrDefault(mutableMapOf())
    }

    fun save(context: Context, pageKey: String, date: LocalDate, values: Map<String, String>) {
        runCatching {
            val obj = JSONObject()
            values.forEach { (k, v) -> obj.put(k, v) }
            fileFor(context, pageKey, date).writeText(obj.toString(), Charsets.UTF_8)
        }
    }
}
