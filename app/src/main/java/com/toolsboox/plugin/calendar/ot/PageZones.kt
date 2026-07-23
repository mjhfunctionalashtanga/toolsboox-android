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

    /**
     * A whole blank page is one zone.
     *
     * Pickings has a designed layout, so it gets carved into labelled parts. A numbered note page
     * has no layout at all — it is paper — so the only honest zone is the page.
     *
     * **No `aiPrompt`, deliberately.** Pickings' zones run their OCR through a model that tidies
     * prose or extracts the strongest line, which is right for a page whose purpose is to produce
     * something. It is wrong here: this text becomes the corpus the spiral and the roots are
     * computed from, and a thread is supposed to be a word YOU keep coming back to. Run it through
     * a model that "fixes obvious slips" first and the roots start measuring the model's
     * vocabulary instead of yours — which is the same failure as reading your pasted newspapers,
     * arriving by a politer road.
     */
    private val WHOLE_PAGE = bands(count = 4)

    /**
     * A blank page, cut into horizontal bands.
     *
     * One zone per page would be correct and wasteful: add a line at the foot of a full page and
     * the whole page goes back to the model, so the cost of a page grows with how often you return
     * to it rather than with how much you wrote. Bands make re-reading proportional to what
     * changed — each carries its own ink signature, so writing in one leaves the other three alone.
     *
     * Four is a guess with a reason: fewer and a band is most of a page; more and ordinary
     * handwriting starts straddling boundaries often enough that the saving is eaten by re-reading
     * two bands instead of one.
     *
     * Bands are BUCKETS, not crops — a stroke belongs to the band its middle falls in, and the
     * image sent for each band is the bounding box of the strokes that landed in it. So a line
     * written across a boundary is read whole by one band rather than sliced in half by two, which
     * is the thing that would have made this quietly worse than doing nothing.
     */
    private fun bands(count: Int): List<PageZone> {
        // The bands must tile the WHOLE page, edge to edge, not the comfortable middle of it.
        //
        // A stroke belongs to the band its centre falls in, so a band that stops short of the
        // margin means ink written up in the header space or hard against a side belongs to no
        // band at all — never OCR'd, never given a signature, and so invisible to the corpus for
        // good rather than merely until next time. Writing into the margin is a normal thing to
        // do on paper, and the failure would be completely silent.
        //
        // Costs nothing to extend: bands are buckets, and each one renders the bounding box of
        // the strokes that landed in it rather than its own rectangle, so a wide band does not
        // mean a wide image.
        val h = PAGE_H / count
        return (0 until count).map { i ->
            PageZone("page$i", "✎ Page ${i + 1}", RectF(0f, i * h, PAGE_W, (i + 1) * h), ZoneKind.TEXT)
        }
    }

    /** Each note-page's own zone layout (keyed by the fragment's notePage string). */
    fun zones(notePage: String?): List<PageZone> = when (notePage) {
        // The numbered note pages — the freeform notebook, and where nearly all the handwriting
        // actually is. Until these had a zone, `autoCaptureSections` returned immediately on every
        // one of them, so tens of thousands of strokes never reached the corpus and the roots were
        // computed almost entirely from pasted article text.
        null -> emptyList()          // the day page: schedule + tasks + free space, see below
        "gratitude" -> WHOLE_PAGE
        "pickings" -> listOf(
            PageZone("notes", "✎ Notes", RectF(80f, 190f, 1324f, 710f), ZoneKind.TEXT,
                "Tidy these handwritten notes into clean prose, fixing obvious OCR slips. Keep the meaning."),
            PageZone("quotes", "❝ Quotes", RectF(80f, 760f, 1324f, 1220f), ZoneKind.TEXT,
                "Extract the single strongest quotable line from this text, verbatim."),
            PageZone("image1", "Image 1", RectF(80f, 1280f, 680f, 1780f), ZoneKind.IMAGE),
            PageZone("image2", "Image 2", RectF(724f, 1280f, 1324f, 1780f), ZoneKind.IMAGE),
        )
        // A numbered page is paper. Anything else named (intake, write, synthesize) has its own
        // pipeline that already produces text, so OCR'ing it would double-count.
        else -> if (notePage.toIntOrNull() != null) WHOLE_PAGE else emptyList()
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
