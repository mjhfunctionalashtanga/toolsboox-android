package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import java.io.File
import java.time.LocalDate

/**
 * ⚡ The one notion of a quick win.
 *
 * The compute lived inside [com.toolsboox.plugin.calendar.ui.QuickWinsFragment] until the day
 * page's Stars & Events panel wanted the same answer — and two computations of "what's quickest
 * to finish" would drift the moment one of them learned something. So the walk, the lineage
 * collapse, the scoring and the companions all live here, and both surfaces ask the same object.
 *
 * The day page reads through [cachedFor] — microseconds, never a file walk — the GardenDoors
 * pattern: the render path shows the parked answer, and [fresh] (IO, maybe network for the
 * rhymes) re-earns it in the background, keyed by day + a hash of the day files, so a checked-off
 * task or a new carry-over refreshes the panel on the next render without ever making it wait.
 */
object QuickWinsEngine {

    /** One win: an undone task worth doing next, with why and what comes with it. */
    data class Win(
        val id: String, val text: String, val uri: String, val sourceDay: LocalDate,
        val committed: Boolean, val degree: Int,
        val reasons: List<String>, val companions: List<String>,
        /** The person this task is assigned to, when it is — the tap-to-go paths route on it. */
        val contactId: String?
    )

    /** Where a tapped win should take you — resolved from its own words. */
    enum class Go { EMAIL, CALL, HOME }

    // "Write that email" / "reply to Sarah" reads as mail; "call the studio" reads as phone.
    // Plain word tests, not NLP: a win is one short task line, and its verb is its intent.
    private val EMAIL_WORDS = Regex("""\b(e-?mail|reply|respond|write (back|to)|inbox)\b""", RegexOption.IGNORE_CASE)
    private val CALL_WORDS = Regex("""\b(call|phone|ring|dial|text|voicemail)\b""", RegexOption.IGNORE_CASE)

    /** How a tap on [w] should land: mail surface, rolodex, or the task's home day. */
    fun goKind(w: Win): Go = when {
        EMAIL_WORDS.containsMatchIn(w.text) -> Go.EMAIL
        CALL_WORDS.containsMatchIn(w.text) -> Go.CALL
        else -> Go.HOME
    }

    // ---- the parked answer (the GardenDoors pattern, in memory) ---------------------------------

    @Volatile private var cachedDay: LocalDate? = null
    @Volatile private var cachedHash: Long = 0L
    @Volatile private var cachedWins: List<Win>? = null

    /** The parked wins for [day], or null when nothing has been computed for it yet. A computed-
     *  but-empty list comes back as emptyList() — "we looked, nothing qualifies" — which is a
     *  different fact from null's "still cooking". */
    fun cachedFor(day: LocalDate): List<Win>? = if (cachedDay == day) cachedWins else null

    /**
     * A cheap fingerprint of the ledger: every day file's name and mtime folded together. Any
     * save — a check-off, a carry-over, fresh ink that grew a task — moves it, and a moved hash
     * is the only thing that makes [fresh] re-walk. IO (a directory walk), so call it where
     * [fresh] is called: off the render path.
     */
    fun ledgerHash(root: File): Long {
        val calendarRoot = File(root, "calendar")
        if (!calendarRoot.isDirectory) return 0L
        var h = 1125899906842597L
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { h = h * 31 + it.name.hashCode(); h = h * 31 + it.lastModified() }
        return h
    }

    /**
     * The current answer, earned or remembered: when [day]'s parked wins were computed from the
     * ledger as it stands, hand them straight back; otherwise run [compute] and park the result.
     * IO + possibly network (the rhymes mean embeddings) — never the render path.
     */
    fun fresh(
        ctx: Context, corpusService: LedgerCorpusService,
        calendarDayService: CalendarDayService, root: File, day: LocalDate
    ): List<Win> {
        val hash = ledgerHash(root)
        val parked = cachedWins
        if (cachedDay == day && cachedHash == hash && parked != null) return parked
        val wins = runCatching { compute(ctx, corpusService, calendarDayService, root) }
            .getOrDefault(emptyList())
        cachedDay = day
        cachedHash = hash
        cachedWins = wins
        return wins
    }

    // ---- the compute (moved whole from QuickWinsFragment) ---------------------------------------

    /** A basket page — finishing something connected to one completes work you already started. */
    private fun isBasket(uri: String): Boolean =
        uri.contains("/synthesize") || uri.contains("/pickings") || uri.contains("/write")

    private fun fileDate(name: String): LocalDate? = runCatching {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    /** A short human tag for a rhymed snippet — its title, else its citation. */
    private fun rhymeTag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    fun compute(
        ctx: Context, corpusService: LedgerCorpusService,
        calendarDayService: CalendarDayService, root: File
    ): List<Win> {
        val calendarRoot = File(root, "calendar")
        if (!calendarRoot.isDirectory) return emptyList()

        // Every non-blank task COPY — done ones included, with the day each lives on. The done
        // ones matter: carry-over duplicates an unfinished task onto the next day under the same
        // id (and pre-dedupe twins say the same words under different ids), and when the user
        // finally checks it off only the newest copy gets `done` — the older copies stay undone
        // as history, by design. A file-by-file walk that only asks "is this copy undone?" keeps
        // resurrecting finished tasks from those historical copies.
        data class Task(val item: LedgerItem, val day: LocalDate)
        val copies = ArrayList<Task>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val day = fileDate(file.name) ?: return@forEach
                val items = runCatching { calendarDayService.loadLedgerItems(file) }.getOrNull() ?: return@forEach
                for (li in items) {
                    if (li.kind == LedgerItem.Kind.TASK && li.text.trim().isNotEmpty())
                        copies.add(Task(li, day))
                }
            }
        if (copies.isEmpty()) return emptyList()

        // Collapse the copies into lineages — same words = same task, the carry-over's own rule —
        // and let each lineage's NEWEST day speak for it. Done there means the task is finished
        // (suppress it); undone there is live, which also lets a task re-created after an old
        // completion count as fresh work. The newest copy is the one Done should save to, so it
        // becomes the win's sourceDay.
        val tasks = copies
            .groupBy { LedgerTaskDedupe.key(it.item.text).ifEmpty { it.item.id } }
            .values.mapNotNull { lineage ->
                val newestDay = lineage.maxOf { it.day }
                val newest = lineage.filter { it.day == newestDay }
                if (newest.any { it.item.done }) null else newest.first()
            }
        if (tasks.isEmpty()) return emptyList()

        val edges = ConnectionStore.loadAll(ctx).filter { it.deletedAt == 0L }

        // First pass — leverage (graph) + ease (short text). Cheap, no semantics.
        data class Scored(val t: Task, val es: List<Connection>, val committed: Boolean, val score: Double)
        val scored = ArrayList<Scored>()
        for (t in tasks) {
            val uri = LedgerUri.task(t.item.id)
            val es = edges.filter { it.from == uri || it.to == uri }
            val committed = es.any { isBasket(it.from) || isBasket(it.to) }
            val len = t.item.text.length
            val ease = if (len < 60) 1.0 else if (len < 120) 0.5 else 0.0
            val score = (if (committed) 3.0 else 0.0) + minOf(es.size.toDouble(), 5.0) * 0.6 + ease
            if (score >= 0.5) scored.add(Scored(t, es, committed, score))
        }
        val top = scored.sortedByDescending { it.score }.take(8)
        if (top.isEmpty()) return emptyList()

        // Second pass — companions + a rhyme, bounded to the finalists (semantics on <= 8).
        val corpus = corpusService.gather(root, Spiral.SCOPE)
            .filter { Spiral.isSubstantial(it.text) }
            .let { Spiral.dedupe(it) { s -> s.text } }

        return top.map { s ->
            val uri = LedgerUri.task(s.t.item.id)
            val reasons = ArrayList<String>()
            if (s.committed) reasons.add("in a basket")
            if (s.es.isNotEmpty()) reasons.add("connects ${s.es.size}")

            val companions = ArrayList<String>()
            for (e in s.es.take(4)) {
                val other = e.otherEnd(uri) ?: continue
                val label = e.otherLabel(uri).ifBlank { LedgerUri.describe(other) }
                if (label.isNotBlank()) companions.add(label.take(28))
            }
            val rhymes = SemanticRoots.neighbors(ctx, s.t.item.text, corpus, topN = 2, minScore = 0.55)
            for (r in rhymes) companions.add(rhymeTag(r.snippet).take(28))
            rhymes.firstOrNull()?.let { reasons.add("rhymes with ${rhymeTag(it.snippet).take(20)}") }

            Win(
                id = s.t.item.id, text = s.t.item.text, uri = uri, sourceDay = s.t.day,
                committed = s.committed, degree = s.es.size,
                reasons = reasons, companions = companions.distinct().sorted(),
                contactId = s.t.item.contactId?.takeIf { it.isNotBlank() })
        }
    }
}
