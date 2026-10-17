package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * THE TEMPLATE IS A PROPERTY OF THE PAGE, NOT THE IDENTITY OF ITS STORE.
 *
 * Michael's ruling (DESIGN-notes-ia-2026-08-22): "Dot, Graph, Line are all just templates. …
 * whatever templates a person wants to use per page." Until now a page's look was decided by its
 * KEY FAMILY — "write" meant ruled, "grid" meant squares, "sketch" meant dots — and the ▤ Template
 * sheet was a written refusal to change it, because changing the family meant moving the ink
 * between stores. This index dissolves that refusal by architecture: the key (and so the store,
 * the sync route and every byte of ink) stays exactly where it is, and only the BACKGROUND the
 * page is drawn on is recorded here.
 *
 * ── THE SIDECAR ───────────────────────────────────────────────────────────────────────────────
 *
 * `page-templates/pages.json`, following [LedgerDocumentStore]'s pattern: a JSON array of rows
 * read with opt*, so an older build (and iOS, until its twin lands) ignores the file entirely and
 * renders the family default — never a wrong page, only yesterday's look. One row per page that
 * was explicitly re-templated:
 *
 *     [{"date":"2026-08-22","key":"write#1","template":"dots","at":1755861600000}]
 *
 * The id a merge resolves on is "date|key" and the NEWER `at` wins — the calendar-merge rule
 * (newer side wins) rather than the document stores' local-wins union, because a template is a
 * VALUE that gets overwritten, not an entry that gets created once. No tombstones: choosing a
 * template can never delete anything, and "back to the family default" is just another value.
 *
 * The day JSON is untouched — no wire change, no fixture churn, no rekeying of
 * [com.toolsboox.plugin.calendar.da.v2.CalendarDay.noteStrokes].
 */
object NotePageTemplates {

    /** The four the picker offers: Lines · Grid · Dots · Pickings. Self Executive is demoted by
     *  ruling — it renders where it exists, it is never offered — so it has no id here. */
    const val LINES = "lines"
    const val GRID = "grid"
    const val DOTS = "dots"
    const val PICKINGS = "pickings"

    /**
     * The gratitude sheet as a TEMPLATE, not only a station (queued 08-23).
     *
     * It was already the right shape for this: [CalendarDayPageNotes.drawGratitudePage] takes a
     * canvas and nothing else — no date, no header, no station chrome — so it generalizes to a
     * background exactly as the other four do. The station keeps its own door; what this adds is
     * that ANY note page can wear the sheet, which is what "whatever templates a person wants to
     * use per page" (DESIGN-notes-ia-2026-08-22) has meant all along.
     *
     * LAST in [IDS] deliberately: the order is the picker's order, and the four plain sheets are
     * what a page usually wants. A prompted sheet belongs at the end of the list, not in the middle
     * of the paper.
     */
    const val GRATITUDE = "gratitude"
    val IDS = listOf(LINES, GRID, DOTS, PICKINGS, GRATITUDE)

    private const val DIR = "page-templates"
    private const val FILE = "pages.json"

    /** The picker's word for each template. "Dots", not "Jots": the surface FAMILY is called Jots
     *  ([LedgerDocuments.label]), the BACKGROUND it wears is dots — this list names backgrounds. */
    fun label(id: String): String = when (id) {
        GRID -> "Grid"
        DOTS -> "Dots"
        PICKINGS -> "Pickings"
        GRATITUDE -> "Gratitude"
        else -> "Lines"
    }

    /** The glyphs the hub and directory already wear for the same surfaces ([LedgerDocuments.glyph]) —
     *  one vocabulary, not a second set of marks for the same four things. */
    fun glyph(id: String): String = when (id) {
        GRID -> "📈"
        DOTS -> "⌱"
        PICKINGS -> "❝"
        GRATITUDE -> "🙏"
        else -> "✍"
    }

    private data class Row(val date: LocalDate, val key: String, val template: String, val at: Long)

    private fun idOf(date: LocalDate, key: String) = "$key|$date"

    private fun file(context: Context) =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, FILE) }

    private fun list(context: Context): MutableList<Row> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return parse(runCatching { f.readText() }.getOrDefault(""))
    }

    private fun parse(text: String): MutableList<Row> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
            val t = o.optString("template").takeIf { id -> id in IDS } ?: return@mapNotNull null
            val k = o.optString("key").takeIf { k -> k.isNotBlank() } ?: return@mapNotNull null
            Row(d, k, t, o.optLong("at"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun save(context: Context, rows: List<Row>) {
        runCatching { file(context).writeText(encode(rows)) }
            .onFailure { Timber.w(it, "$DIR save failed") }
    }

    private fun encode(rows: List<Row>): String {
        val arr = JSONArray()
        for (r in rows) arr.put(
            JSONObject().put("date", r.date.toString()).put("key", r.key)
                .put("template", r.template).put("at", r.at)
        )
        return arr.toString()
    }

    /** The page's EXPLICITLY chosen template, or null when it has only ever worn its family's —
     *  null is the signal [resolve] needs to fall through to the key family. */
    fun templateFor(context: Context, date: LocalDate, pageKey: String): String? =
        list(context).firstOrNull { it.date == date && it.key == pageKey }?.template

    /** Record the page's template and round-trip it. The strokes stay put — only the sheet under
     *  them changes, which is the whole of what property-not-identity means. */
    fun set(context: Context, date: LocalDate, pageKey: String, template: String) {
        if (template !in IDS) return
        val rows = list(context)
        rows.removeAll { it.date == date && it.key == pageKey }
        rows.add(0, Row(date, pageKey, template, System.currentTimeMillis()))
        save(context, rows)
        sync(context)
    }

    /**
     * THE RESOLUTION ORDER, as one pure function so it can be pinned by a plain JVM test:
     *
     *   1. the page's explicit choice ([templateFor]), when it is a known id;
     *   2. else the page's key-family implied template — write→lines, grid→grid,
     *      sketch/synthesize/grampicks→dots, pickings→pickings;
     *   3. else, for a NUMERIC page, the global "calendarNoteTemplate" pref as seed (0→lines,
     *      1→grid) — which is why that Settings row now reads "default template for new pages";
     *   4. else lines.
     *
     * Station keys (gratitude / intake / selfexec) never reach this: their renderers are legacy
     * and untouched, and the picker is never offered on them.
     */
    fun resolve(explicit: String?, pageKey: String?, seed: Int): String {
        // An imported sheet is a valid explicit choice — the bytes are checked at DRAW time
        // ([CustomTemplates.draw] falls through to the family default when this device lacks
        // them), so resolution can pass the id along without touching disk.
        if (explicit != null && explicit.startsWith(CustomTemplates.PREFIX)) return explicit

        if (explicit != null && explicit in IDS) return explicit
        val base = pageKey?.substringBefore('#') ?: return LINES
        // The named stores' key shape: "write" / "write-<millis>" (same for grid, sketch,
        // synthesize, pickings) — the same family test [LedgerDocumentStore.isMine] runs.
        fun family(f: String) = base == f || base.startsWith("$f-")
        return when {
            family("write") -> LINES
            family("grid") -> GRID
            // The standing daily Doodle wears dots by family (DESIGN-homepages-2026-08-22:
            // "dot-grid or blank: the page you open just to move the pen") — and, like any
            // page, an explicit choice above can re-sheet it.
            family("sketch") || family("synthesize") || base == "brainstorm" || base == "grampicks" ||
                base == DatedNote.DOODLE -> DOTS
            family("pickings") -> PICKINGS
            base.toIntOrNull() != null -> if (seed == 1) GRID else LINES
            else -> LINES
        }
    }

    /**
     * Round-trip the index so a template chosen on one device lands on the others. Merged on
     * "key|date" with the NEWER `at` winning — a template is a value you overwrite, so the
     * document stores' local-wins union would pin every device to its own first answer.
     */
    fun sync(context: Context) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = list(context)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, "$DIR/$FILE")
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, Row>()
                for (r in local) byId[idOf(r.date, r.key)] = r
                for (r in parse(remoteText)) {
                    val id = idOf(r.date, r.key)
                    val mine = byId[id]
                    if (mine == null || r.at > mine.at) byId[id] = r
                }
                byId.values.toMutableList()
            }
            if (merged != local) save(context, merged)
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, "$DIR/$FILE", encode(merged))
        }
    }
}
