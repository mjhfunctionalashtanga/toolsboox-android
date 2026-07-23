package com.toolsboox.plugin.chat.fi

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.da.Section
import com.toolsboox.plugin.chat.nw.EmbeddingIndex
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val calendarDayService: CalendarDayService,
    @ApplicationContext private val context: Context
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
        out += gatherExtras(scope)
        return out
    }

    /** Text captured outside the day JSON: the OCR'd page sections ([SectionStore]) and the typed
     *  Text Notes. Both live in filesDir, so they need a Context — this is what makes handwriting and
     *  quick notes searchable alongside everything else. */
    private fun gatherExtras(scope: Set<Section>): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        if (Section.NOTES in scope) {
            File(context.filesDir, "text-notes").listFiles()
                ?.filter { it.isFile && it.name.startsWith("notes-") && it.name.endsWith(".json") }
                ?.forEach { f ->
                    val ds = f.name.removePrefix("notes-").removeSuffix(".json")
                    val date = parseDate(ds)
                    val arr = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return@forEach
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = o.optString("title").trim()
                        val body = o.optString("body").trim()
                        if (title.isEmpty() && body.isEmpty()) continue
                        out += CorpusSnippet(
                            Section.NOTES, date, title.ifBlank { "Text note" }, ds,
                            body.ifBlank { title }, cite(date, "note", title.ifBlank { "note" }))
                    }
                }
        }
        if (Section.FEED in scope) {
            // FULL feed articles from the offline cache (list-*.json metadata + content-<id>.html
            // parsed text) — Ask can now read what you read, not just what you annotated.
            val cacheDir = File(context.filesDir, "feed-cache")
            val seen = HashSet<Long>()
            cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("list-") && it.name.endsWith(".json") }
                ?.forEach { f ->
                    val arr = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return@forEach
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optLong("id", 0)
                        if (id == 0L || !seen.add(id)) continue
                        val title = o.optString("title").trim()
                        if (title.isEmpty()) continue
                        val html = File(cacheDir, "content-$id.html").takeIf { it.exists() }?.readText()
                            ?: o.optString("content")
                        val text = html.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), "")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ").trim()
                        if (text.isEmpty()) continue
                        val date = runCatching {
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .parse(o.optString("publishedAt").take(10))
                        }.getOrNull() ?: Date()
                        out += CorpusSnippet(
                            Section.FEED, date, title, o.optString("feedTitle"),
                            text.take(1500), cite(date, "feed", title))
                    }
                }
        }
        if (Section.ANNOTATIONS in scope) {
            // Handwriting captured off the planner pages (roster notes, article margins) — OCR'd once
            // at save time and kept with provenance by [com.toolsboox.plugin.chat.da.AnnotationCorpus].
            out += com.toolsboox.plugin.chat.da.AnnotationCorpus.snippets(context)
        }
        if (Section.SECTIONS in scope) {
            File(context.filesDir, "page-sections").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.forEach { f ->
                    val name = f.name.removeSuffix(".json")
                    if (name.length < 11) return@forEach
                    val ds = name.takeLast(10)                       // trailing YYYY-MM-DD
                    val pageKey = name.dropLast(11).ifBlank { "default" }  // drop the date + its hyphen
                    val date = parseDate(ds)
                    val obj = runCatching { org.json.JSONObject(f.readText()) }.getOrNull() ?: return@forEach
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.startsWith("__")) continue            // signature/internal keys
                        val v = obj.optString(k).trim()
                        if (v.isEmpty()) continue
                        out += CorpusSnippet(
                            Section.SECTIONS, date, "Section: $k", pageKey, v,
                            cite(date, "section", "$pageKey/$k"))
                    }
                }
        }
        return out
    }

    private fun parseDate(ds: String): Date = runCatching {
        val p = ds.split("-")
        val c = Calendar.getInstance(); c.clear(); c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 12, 0, 0); c.time
    }.getOrDefault(Date())

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
                    citation = cite(e.date, if (isBook) "book" else "article", e.title),
                    own = e.note?.trim().orEmpty()
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
        if (Section.TASKS in scope) {
            for (li in day.ledgerItems) {
                val text = li.text.trim(); if (text.isEmpty()) continue
                val isTask = li.kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK
                val kindLabel = if (isTask) "task" else "event"
                val status = if (isTask && li.done) " (done)" else ""
                val timeLabel = li.time?.let { " @ $it" } ?: ""
                out += CorpusSnippet(
                    section = Section.TASKS, date = li.date,
                    title = kindLabel.replaceFirstChar { it.uppercase() } + timeLabel,
                    source = li.source ?: "", text = text + status,
                    citation = cite(li.date, kindLabel, text)
                )
            }
            for (ev in day.events) {
                val title = ev.title.trim(); if (title.isEmpty()) continue
                val d = Date(ev.startDate)
                val body = listOf(title, ev.description.trim()).filter { it.isNotEmpty() }.joinToString(" — ")
                out += CorpusSnippet(
                    section = Section.TASKS, date = d, title = "Event", source = "",
                    text = body, citation = cite(d, "event", title)
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

    /**
     * Hybrid retrieval: blend semantic similarity (OpenAI embeddings) with the keyword overlap and
     * recency boost. Cosine leads (catches paraphrase / fuzzy questions), keyword + recency refine
     * (nails exact terms and favours recent entries). Falls back to [retrieve] when no embedding key
     * is configured or the embedding call fails, so it's never worse than keyword-only.
     * Blocking (network) — call from an IO context.
     */
    fun retrieveHybrid(snippets: List<CorpusSnippet>, question: String, k: Int = 24): List<CorpusSnippet> {
        if (snippets.isEmpty()) return emptyList()
        val apiKey = EmbeddingIndex.apiKey(context) ?: return retrieve(snippets, question, k)
        val cache = EmbeddingIndex.loadCache(context)
        val need = snippets.filter { it.text.isNotBlank() && EmbeddingIndex.hash(it.text) !in cache }
        if (need.isNotEmpty()) {
            val vecs = EmbeddingIndex.embed(apiKey, need.map { it.text }) ?: return retrieve(snippets, question, k)
            if (vecs.size != need.size) return retrieve(snippets, question, k)
            need.forEachIndexed { i, s -> cache[EmbeddingIndex.hash(s.text)] = vecs[i] }
            EmbeddingIndex.saveCache(context, cache)
        }
        val qv = EmbeddingIndex.embed(apiKey, listOf(question))?.firstOrNull()
            ?: return retrieve(snippets, question, k)
        val terms = tokenize(question)
        val now = System.currentTimeMillis()
        val scored = snippets.map { s ->
            val sv = cache[EmbeddingIndex.hash(s.text)]
            val cos = if (sv != null) cosine(qv, sv) else 0.0
            val words = tokenize(s.title + " " + s.source + " " + s.text)
            val overlap = if (terms.isEmpty()) 0.0 else terms.count { it in words }.toDouble() / terms.size
            val ageDays = ((now - s.date.time).coerceAtLeast(0)) / 86_400_000.0
            val recency = 1.0 / (1.0 + ageDays / 30.0)
            s to (0.7 * cos + 0.2 * overlap + 0.1 * recency)
        }
        return scored.sortedByDescending { it.second }.take(k).map { it.first }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
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
