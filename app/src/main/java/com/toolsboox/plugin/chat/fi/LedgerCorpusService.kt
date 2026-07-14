package com.toolsboox.plugin.chat.fi

import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.da.Section
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Gathers the Ledger corpus from the on-disk day JSON and does lightweight retrieval
 * for "Ask my Ledger". Deliberately simple + dependency-free (keyword + recency
 * ranking, no embeddings yet) so it's fast on a Boox and unit-testable; an embedding
 * step can slot in later behind the same [retrieve] seam.
 */
class LedgerCorpusService @Inject constructor(
    private val calendarDayService: CalendarDayService
) {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Walk `calendar/**/day-*-v2.json` under [rootPath] and flatten to snippets in [scope]. */
    fun gather(rootPath: File, scope: Set<Section> = Section.ALL): List<CorpusSnippet> {
        val calendarRoot = File(rootPath, "calendar")
        if (!calendarRoot.isDirectory) return emptyList()
        val out = ArrayList<CorpusSnippet>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file -> calendarDayService.load(file)?.let { out += snippetsOf(it, scope) } }
        return out
    }

    /** Extract the in-scope snippets from a single day. */
    fun snippetsOf(day: CalendarDay, scope: Set<Section>): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        val dayDate = dayDate(day)

        if (Section.BOOKS in scope || Section.ARTICLES in scope) {
            for (e in day.readingEvents) {
                val isBook = e.kind == ReadingEvent.Kind.BOOK
                val section = if (isBook) Section.BOOKS else Section.ARTICLES
                if (section !in scope) continue
                val body = listOfNotNull(e.excerpt?.trim(), e.note?.trim())
                    .filter { it.isNotEmpty() }.joinToString(" — ")
                    .ifEmpty { e.title }
                out += CorpusSnippet(
                    section = section, date = e.date, title = e.title,
                    source = e.source ?: "", text = body,
                    citation = cite(e.date, if (isBook) "book" else "article", e.title)
                )
            }
        }
        if (Section.PLANNER in scope) {
            for (t in day.textElements) {
                val text = t.text.trim()
                if (text.isEmpty()) continue
                out += CorpusSnippet(
                    section = Section.PLANNER, date = dayDate, title = "Planner",
                    source = t.pageKey, text = text,
                    citation = cite(dayDate, "planner", t.pageKey)
                )
            }
        }
        if (Section.MEDIA in scope) {
            for (a in day.avGrams) {
                out += CorpusSnippet(
                    section = Section.MEDIA, date = a.date ?: dayDate, title = "A/V gram",
                    source = a.kind.name.lowercase(), text = "[${a.kind.name.lowercase()}] ${a.filename}",
                    citation = cite(a.date ?: dayDate, "a/v", a.filename)
                )
            }
        }
        return out
    }

    /**
     * Rank [snippets] against [question] by shared-word overlap with a mild recency
     * boost, and return the top [k]. A snippet with zero keyword overlap is dropped
     * unless nothing matches, in which case the most-recent [k] are returned so the
     * model still has *something* to ground on.
     */
    fun retrieve(snippets: List<CorpusSnippet>, question: String, k: Int = 24): List<CorpusSnippet> {
        if (snippets.isEmpty()) return emptyList()
        val terms = tokenize(question)
        val now = System.currentTimeMillis()
        val scored = snippets.map { s ->
            val words = tokenize(s.title + " " + s.source + " " + s.text)
            val overlap = if (terms.isEmpty()) 0 else terms.count { it in words }
            val ageDays = ((now - s.date.time).coerceAtLeast(0)) / 86_400_000.0
            val recency = 1.0 / (1.0 + ageDays / 30.0)   // ~half weight at 30 days
            s to (overlap * 10.0 + recency)
        }
        val matched = scored.filter { it.second >= 1.0 && (terms.isEmpty() || it.first.let { s -> tokenize(s.text + " " + s.title + " " + s.source).any { w -> w in terms } }) }
        val pool = if (matched.isNotEmpty()) matched else scored
        return pool.sortedByDescending { it.second }.take(k).map { it.first }
    }

    /** Assemble retrieved snippets into a compact, cite-able context block for the LLM. */
    fun buildContext(snippets: List<CorpusSnippet>): String =
        snippets.joinToString("\n\n") { "[${it.citation}] ${it.title}${if (it.source.isNotBlank()) " (${it.source})" else ""}: ${it.text}" }

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private fun cite(date: Date, kind: String, label: String): String {
        val short = if (label.length > 40) label.take(37) + "…" else label
        return "${dayFmt.format(date)} · $kind · $short"
    }

    private fun dayDate(day: CalendarDay): Date {
        val c = Calendar.getInstance()
        c.clear(); c.set(day.year, day.month - 1, day.day, 12, 0, 0)
        return c.time
    }
}
