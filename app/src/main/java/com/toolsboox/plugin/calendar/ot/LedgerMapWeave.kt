package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The word-rhizome map: what the Map draws when there are no drawn edges to draw.
 *
 * The connection graph only exists where things have been touched — an imported archive has
 * decades of material and essentially no edges, so the old Map opened onto "nothing is connected
 * yet" over twenty-four years of history. This is the iOS Map's fallback, ported: the same
 * corpus, threads and crossings as Roots (identical pipeline — [LedgerCorpusService.gather] in
 * [Spiral.SCOPE], [Spiral.isSubstantial], [Spiral.dedupe], then [Rhizome]), laid out as a
 * picture. Threads ring the centre sized by heat; the crossings — objects sitting in more than
 * one thread, the joins — hang off the threads they join.
 *
 * Reuses the corpus path on purpose: gather() keeps a process-wide, disk-mirrored mtime cache of
 * snippets, so weaving never re-reads an unchanged day file. The built weave is then remembered
 * for the session in [Cache] (the [RootsCache] pattern) so reopening the Map paints before the
 * disk is touched.
 */
object LedgerMapWeave {

    /** The centre of the woven picture. `weave:` uris are synthetic — never ledger addresses. */
    const val ROOT = "weave:root"
    private const val THREAD_PREFIX = "weave:t:"
    private const val ITEM_PREFIX = "weave:i:"

    /**
     * How much history feeds one weave. The corpus helper itself keeps everything, uncapped; the
     * inverted index over a 24-year archive is both slow to build and — worse — a picture of who
     * you were rather than what you are circling. So the weave reads the MOST RECENT entries up
     * to this many, and [Weave.subtitle] says so out loud: a capped map announces its window
     * instead of silently truncating.
     */
    const val MAX_WOVEN = 4000

    fun isThread(uri: String): Boolean = uri.startsWith(THREAD_PREFIX)
    fun isItem(uri: String): Boolean = uri.startsWith(ITEM_PREFIX)
    fun isWeaveNode(uri: String): Boolean = uri == ROOT || isThread(uri) || isItem(uri)
    fun termOf(uri: String): String = uri.removePrefix(THREAD_PREFIX)
    fun itemIndexOf(uri: String): Int? = uri.removePrefix(ITEM_PREFIX).toIntOrNull()

    /** A built weave: everything the Map needs to draw and to answer taps, in one immutable bag. */
    data class Weave(
        val adjacency: Map<String, List<String>>,
        val labels: Map<String, String>,
        /** Node weight for the view — thread heat scaled to 1..100, the measure Roots sorts by. */
        val weights: Map<String, Int>,
        val threadsByTerm: Map<String, Rhizome.Thread>,
        /** The snippets the weave was built over; thread members / item indices point into this. */
        val snippets: List<CorpusSnippet>,
        val wovenFrom: Int,
        val totalCorpus: Int,
        val oldestWoven: Date?
    ) {
        val isEmpty: Boolean get() = adjacency.isEmpty()

        /** Nothing in the ledger AT ALL — the only case the Map's "nothing yet" copy is honest for. */
        val corpusIsEmpty: Boolean get() = totalCorpus == 0

        /** The provenance line: what this picture was woven from, window included when capped. */
        fun subtitle(): String {
            if (isEmpty) return ""
            val capped = wovenFrom < totalCorpus
            return if (capped && oldestWoven != null) {
                val fmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                "Word rhizomes — woven from your $wovenFrom most recent entries (back to ${fmt.format(oldestWoven)})"
            } else {
                "Word rhizomes — woven from all $wovenFrom entries"
            }
        }
    }

    val EMPTY = Weave(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), 0, 0, null)

    /**
     * Build the weave. Blocking (disk via gather()) — call from an IO context.
     *
     * Adjacency is bidirectional (root ↔ thread ↔ crossing) so the existing tap-to-walk works:
     * focus a thread and its crossings ring it with the root a step away; focus a crossing and
     * every thread it joins rings IT, which is the join made visible. All ordering is explicit —
     * threads hottest-first (as [Rhizome.threads] returns them), crossings newest-first — so the
     * same ledger draws the same picture every time.
     */
    fun build(
        corpusService: LedgerCorpusService,
        rootPath: File,
        window: Pair<java.time.LocalDate, java.time.LocalDate>? = null
    ): Weave {
        val gathered = corpusService.gather(rootPath, Spiral.SCOPE)
            .filter { Spiral.isSubstantial(it.text) }
            .let { list -> Spiral.dedupe(list) { it.text } }
        if (gathered.isEmpty()) return EMPTY

        // The almanac filter: weave only what was written in the window (`[start, end)`), while
        // totalCorpus keeps counting the whole gather — corpusIsEmpty means the LEDGER is empty,
        // and a scoped weave must not borrow that claim for one quiet month.
        val scoped = if (window == null) gathered else {
            val zone = java.time.ZoneId.systemDefault()
            gathered.filter {
                val d = it.date.toInstant().atZone(zone).toLocalDate()
                !d.isBefore(window.first) && d.isBefore(window.second)
            }
        }
        if (scoped.isEmpty()) return EMPTY.copy(totalCorpus = gathered.size)

        val all =
            if (scoped.size > MAX_WOVEN) scoped.sortedByDescending { it.date.time }.take(MAX_WOVEN)
            else scoped
        val threads = Rhizome.threads(all.map { it.text + " " + it.title }, all.map { it.date.time })
        if (threads.isEmpty()) return EMPTY.copy(totalCorpus = gathered.size)
        val crossings = Rhizome.crossings(threads)

        val adj = HashMap<String, MutableList<String>>()
        val labels = HashMap<String, String>()
        val weights = HashMap<String, Int>()
        val byTerm = HashMap<String, Rhizome.Thread>()

        fun link(a: String, b: String) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }

        labels[ROOT] = "Threads"
        weights[ROOT] = 100
        val maxHeat = (threads.maxOfOrNull { it.heat } ?: 1.0).coerceAtLeast(0.0001)
        for (t in threads) {
            val tid = THREAD_PREFIX + t.term
            byTerm[t.term] = t
            labels[tid] = t.term
            weights[tid] = ((t.heat / maxHeat) * 100).roundToInt().coerceAtLeast(1)
            link(ROOT, tid)
            // This thread's crossings, newest first — the leaves the layout will seat.
            val kids = crossings.entries
                .filter { t.term in it.value }
                .sortedByDescending { all.getOrNull(it.key)?.date?.time ?: 0L }
            for ((idx, _) in kids) {
                val snip = all.getOrNull(idx) ?: continue
                val iid = ITEM_PREFIX + idx
                if (iid !in labels) labels[iid] = snip.text.trim().take(60)
                link(tid, iid)
            }
        }
        // A crossing's neighbours are its threads — hottest first, so when it becomes the focus
        // the ring reads in the same order the rest of the surface does.
        for ((uri, list) in adj) {
            if (isItem(uri)) list.sortByDescending { weights[it] ?: 0 }
        }
        return Weave(
            adjacency = adj, labels = labels, weights = weights, threadsByTerm = byTerm,
            snippets = all, wovenFrom = all.size, totalCorpus = gathered.size,
            oldestWoven = all.minByOrNull { it.date.time }?.date
        )
    }

    /**
     * The last built weave, kept for the session so reopening the Map paints before the disk is
     * touched. Keyed by day, verified by a background re-build that replaces it only when the
     * ledger actually changed — the same shape as Roots' cache. Deliberately NOT persisted: the
     * expensive part is the corpus walk, and [LedgerCorpusService] already mirrors that to disk,
     * so even a cold rebuild after restart re-parses only the files that changed.
     */
    object Cache {
        var day: java.time.LocalDate? = null
        var weave: Weave? = null
    }
}
