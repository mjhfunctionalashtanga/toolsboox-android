package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import java.io.File
import java.time.LocalDate

/**
 * ⚡ Today's quick-wins GLIMPSE — the top one or two shortest paths to victory, shown right on the
 * day page beside the GardenDoors Roots band, so the quickest thing worth finishing is glanceable
 * without opening the ⚡ Quick Wins surface. It complements the Roots band: the band shows what your
 * graph keeps circling (roots, sprouts, missed rhizomes), this shows what your graph says to finish.
 *
 * There is exactly ONE notion of "quickest to finish" — [QuickWinsEngine]'s — and a second would
 * drift the moment one of them learned something. So this computes nothing of its own: it asks the
 * engine for the current wins OFF the render path and PARKS the top two the GardenDoors way — in
 * `ledger_quickwins_glimpse` prefs keyed by day. The render path reads the parked answer in
 * microseconds and never waits on the engine's file walk or its rhyme embeddings; a cold cache shows
 * a quiet "…" until the background walk lands, and a ledger with no wins simply parks an empty list.
 */
object QuickWinsGlimpse {

    /** One glimpsed win: the line to show and the win id it routes to (for a future direct path). */
    data class Line(val id: String, val text: String)

    private const val PREFS = "ledger_quickwins_glimpse"
    private const val MAX = 2

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The parked glimpse for [day]: null when nothing has been computed for it yet ("still cooking",
     * the render path's cue to show a quiet "…"), an empty list when it was computed and no win
     * qualifies ("we looked, nothing"), the lines otherwise. The GardenDoors null-vs-empty contract.
     */
    fun cachedFor(context: Context, day: LocalDate): List<Line>? {
        val p = prefs(context)
        if (p.getString("day", "") != day.toString()) return null
        val n = p.getInt("count", 0).coerceIn(0, MAX)
        return (0 until n).mapNotNull { i ->
            val text = p.getString("$i.text", "").orEmpty()
            if (text.isBlank()) null else Line(p.getString("$i.id", "").orEmpty(), text)
        }
    }

    /**
     * Ask the engine for today's wins and park the top [MAX] as the glimpse. IO + possibly network
     * (the engine's rhymes mean embeddings) — call from an IO context, never the render path. Fails
     * soft: a thin ledger, a missing AI key or a failed walk all park an empty glimpse, the same
     * quiet degrade GardenDoors makes.
     */
    fun compute(
        context: Context, corpusService: LedgerCorpusService,
        calendarDayService: CalendarDayService, root: File, day: LocalDate
    ): List<Line> {
        val wins = runCatching {
            QuickWinsEngine.fresh(context, corpusService, calendarDayService, root, day)
        }.getOrDefault(emptyList())
        val lines = wins.take(MAX).map { w ->
            Line(w.id, w.text.replace(Regex("\\s+"), " ").trim())
        }
        val e = prefs(context).edit()
            .putString("day", day.toString())
            .putInt("count", lines.size)
        for (i in 0 until MAX) {
            if (i < lines.size) e.putString("$i.id", lines[i].id).putString("$i.text", lines[i].text)
            else e.putString("$i.id", "").putString("$i.text", "")
        }
        e.apply()
        return lines
    }
}
