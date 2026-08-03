package com.toolsboox.plugin.chat.fi

import android.content.Context
import com.toolsboox.ot.HtmlText
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.da.Section
import com.toolsboox.plugin.chat.nw.EmbeddingIndex
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    // Thread-safe, unlike a shared SimpleDateFormat: cite() runs on multiple dispatchers outside
    // the CorpusCache lock, and a garbled date corrupts the citation identity key.
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /**
     * The corpus, remembered.
     *
     * gather() used to re-read EVERY day file — base64 media, stroke arrays and all — plus every
     * cached feed article, on every open of Missed Connections, the Map and Ask. (Roots and
     * Sprouts were two more callers of this walk; retiring them took two whole-corpus reads with
     * them, which is most of what this debloat bought at runtime.)
     * The ledger changes a file or two a day; the walk re-paid for all of them every time. So:
     * one process-wide cache of extracted snippets keyed by source file + mtime, mirrored to a
     * small on-disk index, so even the first open after a restart re-parses only what actually
     * changed. Day files that DO need re-parsing go through [CalendarDayService.loadCorpusSlice],
     * which skips the strokes and image payloads the corpus never quotes.
     */
    private object CorpusCache {
        class Entry(val mtime: Long, val snippets: List<CorpusSnippet>)
        val files = HashMap<String, Entry>()
        /** Keys the person has thrown out of the corpus for good — citations (and, for the missed-
         *  rhizome surface, entry URLs). Stronger than a surface's mute/dismiss: a tombstoned
         *  thing is never indexed again, so it can't resurface in Roots, Sprouts, Missed, the Map
         *  or Ask no matter how many times the walk re-reads its file. */
        val tombstones = HashSet<String>()
        var indexLoaded = false
        var dirty = false
    }

    /** Walk `calendar/**/day-*-v2.json` under [rootPath] and flatten to snippets in [scope]. */
    fun gather(rootPath: File, scope: Set<Section> = Section.ALL): List<CorpusSnippet> = synchronized(CorpusCache) {
        loadIndexOnce()
        val out = ArrayList<CorpusSnippet>()
        val live = HashSet<String>()
        val calendarRoot = File(rootPath, "calendar")
        if (calendarRoot.isDirectory) {
            calendarRoot.walkTopDown()
                .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                // A day file is cached with ALL its sections — it carries several at once, and the
                // walk is the expensive thing — then trimmed to [scope] on the way out.
                .forEach { file -> out += cached(file, live) { daySnippets(file) } }
        }
        out += gatherExtras(scope, live)
        prune(live)
        saveIndexIfDirty()
        return dejunk(out, scope)
    }

    /**
     * The corpus's quality floor, applied on the way out (the cache keeps everything raw, so the
     * rules can tighten later without a re-walk).
     *
     * What goes: anything tombstoned by hand; feed entries seen in several cached views (one
     * snippet per article is the truth); feed text that is a bare URL or too short to be an
     * article (a real one has sentences — a "Comments" stub does not); and, for the machine-
     * written sections (feed + OCR), the SAME text arriving from many files, which is template
     * cruft, kept once rather than dropped so nothing vanishes outright. The handmade sections —
     * planner, tasks, notes, books — are left whole on purpose: "call mom" three days running is
     * recurrence, and recurrence is exactly what Roots counts.
     */
    private fun dejunk(all: List<CorpusSnippet>, scope: Set<Section>): List<CorpusSnippet> {
        val machineMade = setOf(Section.FEED, Section.SECTIONS)
        // How often each machine-made text recurs across DIFFERENT source citations.
        val seenText = HashMap<String, Int>()
        val seenFeed = HashSet<String>()
        var tombstoned = 0; var dup = 0; var thin = 0; var template = 0
        val kept = all.filter { s ->
            if (s.section !in scope) return@filter false
            if (s.citation in CorpusCache.tombstones) { tombstoned++; return@filter false }
            if (s.section == Section.FEED) {
                if (!seenFeed.add(s.title + "|" + s.source)) { dup++; return@filter false }
                val t = s.text.trim()
                if (t.length < 60 || t.matches(Regex("^https?://\\S+$"))) { thin++; return@filter false }
            }
            if (s.section in machineMade && s.text.length >= 30) {
                val n = (seenText[s.text] ?: 0) + 1
                seenText[s.text] = n
                if (n >= 2) { template++; return@filter false }
            }
            true
        }
        if (all.size != kept.size) timber.log.Timber.i(
            "Corpus de-junk: kept ${kept.size} of ${all.size} in scope " +
                "(tombstoned=$tombstoned dup=$dup thin=$thin template=$template)")
        return kept
    }

    // MARK: - Tombstones ("remove from corpus")

    /** Throw [key] (a snippet citation, or a missed-rhizome entry URL) out of the corpus for
     *  good. Persisted; survives re-gathers and process death. */
    fun exclude(key: String) = synchronized(CorpusCache) {
        loadIndexOnce()
        if (CorpusCache.tombstones.add(key)) saveTombstones()
    }

    /** Is [key] tombstoned? For surfaces that surface things gather() doesn't emit (Missed). */
    fun isExcluded(key: String): Boolean = synchronized(CorpusCache) {
        loadIndexOnce()
        key in CorpusCache.tombstones
    }

    /** The per-file seam: answer from the cache while [file] is unchanged, else [parse] and remember. */
    private fun cached(file: File, live: MutableSet<String>, parse: () -> List<CorpusSnippet>): List<CorpusSnippet> {
        val key = file.absolutePath
        live += key
        val mtime = file.lastModified()
        CorpusCache.files[key]?.let { if (it.mtime == mtime) return it.snippets }
        val fresh = parse()
        CorpusCache.files[key] = CorpusCache.Entry(mtime, fresh)
        CorpusCache.dirty = true
        return fresh
    }

    /** One day file → its snippets, through the slim decoder; the full loader is the fallback for
     *  a file the slim shape can't read, so nothing that used to surface stops surfacing. */
    private fun daySnippets(file: File): List<CorpusSnippet> {
        calendarDayService.loadCorpusSlice(file)?.let { d ->
            return snippetsOfParts(
                d.year, d.month, d.day, d.events, d.readingEvents,
                d.textElements, d.avGrams, d.ledgerItems, Section.ALL)
        }
        return calendarDayService.load(file)?.let { snippetsOf(it, Section.ALL) } ?: emptyList()
    }

    /** Text captured outside the day JSON: the OCR'd page sections ([SectionStore]) and the typed
     *  Text Notes. Both live in filesDir, so they need a Context — this is what makes handwriting and
     *  quick notes searchable alongside everything else. Each source file goes through the same
     *  mtime cache as the day files; only a family whose section is out of [scope] is skipped
     *  entirely (skipped ≠ pruned — see [prune]). */
    private fun gatherExtras(scope: Set<Section>, live: MutableSet<String>): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        if (Section.NOTES in scope) {
            File(context.filesDir, "text-notes").listFiles()
                ?.filter { it.isFile && it.name.startsWith("notes-") && it.name.endsWith(".json") }
                ?.forEach { f -> out += cached(f, live) { noteSnippets(f) } }
        }
        if (Section.FEED in scope) {
            // FULL feed articles from the offline cache (list-*.json metadata + content-<id>.html
            // parsed text) — Ask can now read what you read, not just what you annotated. Cached
            // per list file: a refresh rewrites the list, which is what re-parses its articles.
            val cacheDir = File(context.filesDir, "feed-cache")
            cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("list-") && it.name.endsWith(".json") }
                ?.forEach { f -> out += cached(f, live) { feedSnippets(cacheDir, f) } }
        }
        if (Section.ANNOTATIONS in scope) {
            // Handwriting captured off the planner pages (roster notes, article margins) — OCR'd once
            // at save time and kept with provenance by [com.toolsboox.plugin.chat.da.AnnotationCorpus].
            // One small index file; cheap enough to read straight every time.
            out += com.toolsboox.plugin.chat.da.AnnotationCorpus.snippets(context)
        }
        if (Section.SECTIONS in scope) {
            File(context.filesDir, "page-sections").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.forEach { f -> out += cached(f, live) { sectionSnippets(f) } }
        }
        return out
    }

    private fun noteSnippets(f: File): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        val ds = f.name.removePrefix("notes-").removeSuffix(".json")
        val date = parseDate(ds)
        val arr = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val body = o.optString("body").trim()
            if (title.isEmpty() && body.isEmpty()) continue
            out += CorpusSnippet(
                Section.NOTES, date, title.ifBlank { "Text note" }, ds,
                body.ifBlank { title }, cite(date, "note", title.ifBlank { "note" }))
        }
        return out
    }

    private fun feedSnippets(cacheDir: File, f: File): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        val seen = HashSet<Long>()
        val arr = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0)
            if (id == 0L || !seen.add(id)) continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            val html = File(cacheDir, "content-$id.html").takeIf { it.exists() }?.readText()
                ?: o.optString("content")
            val text = HtmlText.toPlain(html)
            if (text.isEmpty()) continue
            val date = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .parse(o.optString("publishedAt").take(10))
            }.getOrNull() ?: Date()
            out += CorpusSnippet(
                Section.FEED, date, title, o.optString("feedTitle"),
                text.take(1500), cite(date, "feed", title))
        }
        return out
    }

    private fun sectionSnippets(f: File): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        val name = f.name.removeSuffix(".json")
        if (name.length < 11) return out
        val ds = name.takeLast(10)                       // trailing YYYY-MM-DD
        val pageKey = name.dropLast(11).ifBlank { "default" }  // drop the date + its hyphen
        val date = parseDate(ds)
        val obj = runCatching { org.json.JSONObject(f.readText()) }.getOrNull() ?: return out
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
        return out
    }

    // MARK: - The on-disk index

    private fun indexFile(): File = File(context.filesDir, "corpus-index.json")
    private fun tombstoneFile(): File = File(context.filesDir, "corpus-tombstones.json")

    /** The tombstones on disk — a small flat array, written on every exclusion (they're rare). */
    private fun saveTombstones() {
        runCatching {
            val arr = org.json.JSONArray()
            for (k in CorpusCache.tombstones) arr.put(k)
            tombstoneFile().writeText(arr.toString())
        }
    }

    /** Fill the in-memory cache from the last session's index — once per process, quiet on any
     *  damage (a bad index just means a one-time cold walk, exactly like before it existed). */
    private fun loadIndexOnce() {
        if (CorpusCache.indexLoaded) return
        CorpusCache.indexLoaded = true
        runCatching {
            val tf = tombstoneFile()
            if (tf.isFile) {
                val arr = org.json.JSONArray(tf.readText())
                for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }
                    ?.let { CorpusCache.tombstones += it }
            }
        }
        runCatching {
            val f = indexFile()
            if (!f.isFile) return
            val files = org.json.JSONObject(f.readText()).optJSONObject("files") ?: return
            for (key in files.keys()) {
                val o = files.optJSONObject(key) ?: continue
                val arr = o.optJSONArray("s") ?: continue
                val snippets = ArrayList<CorpusSnippet>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONArray(i) ?: continue
                    val section = runCatching { Section.valueOf(s.optString(0)) }.getOrNull() ?: continue
                    snippets += CorpusSnippet(
                        section, Date(s.optLong(1)), s.optString(2), s.optString(3),
                        s.optString(4), s.optString(5), s.optString(6))
                }
                CorpusCache.files[key] = CorpusCache.Entry(o.optLong("m"), snippets)
            }
        }
    }

    /** Mirror the cache to disk when something changed — atomically, so a killed process leaves
     *  the previous index rather than half of a new one. */
    private fun saveIndexIfDirty() {
        if (!CorpusCache.dirty) return
        CorpusCache.dirty = false
        runCatching {
            val files = org.json.JSONObject()
            for ((key, e) in CorpusCache.files) {
                val arr = org.json.JSONArray()
                for (s in e.snippets) arr.put(org.json.JSONArray().apply {
                    put(s.section.name); put(s.date.time); put(s.title); put(s.source)
                    put(s.text); put(s.citation); put(s.own)
                })
                files.put(key, org.json.JSONObject().apply { put("m", e.mtime); put("s", arr) })
            }
            val f = indexFile()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(org.json.JSONObject().apply { put("v", 1); put("files", files) }.toString())
            // Never delete the good copy to make room for the rename — a kill between the
            // delete and the rename loses the only local copy. Files.move replaces in one step.
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // ATOMIC_MOVE can be unsupported on some filesystems; fall back to a plain replace.
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Forget entries whose source file is GONE — not merely out of this gather's scope or root,
     *  which is why absence from [live] alone isn't enough to evict. */
    private fun prune(live: Set<String>) {
        val it = CorpusCache.files.keys.iterator()
        while (it.hasNext()) {
            val key = it.next()
            if (key in live) continue
            if (!File(key).exists()) { it.remove(); CorpusCache.dirty = true }
        }
    }

    private fun parseDate(ds: String): Date = runCatching {
        val p = ds.split("-")
        val c = Calendar.getInstance(); c.clear(); c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 12, 0, 0); c.time
    }.getOrDefault(Date())

    /** Extract the in-scope snippets from a single day. */
    fun snippetsOf(day: CalendarDay, scope: Set<Section>): List<CorpusSnippet> =
        snippetsOfParts(day.year, day.month, day.day, day.events, day.readingEvents,
            day.textElements, day.avGrams, day.ledgerItems, scope)

    /** The extraction itself, off the parts a day carries — one body serving both the full
     *  [CalendarDay] and the slim corpus decode, so the two can never drift. */
    private fun snippetsOfParts(
        year: Int, month: Int, dayOfMonth: Int,
        events: List<com.toolsboox.plugin.calendar.da.v1.CalendarEvent>,
        readingEvents: List<ReadingEvent>,
        textElements: List<com.toolsboox.da.TextElement>,
        avGrams: List<com.toolsboox.da.Attachment>,
        ledgerItems: List<com.toolsboox.plugin.calendar.da.v2.LedgerItem>,
        scope: Set<Section>
    ): List<CorpusSnippet> {
        val out = ArrayList<CorpusSnippet>()
        val dayDate = dayDate(year, month, dayOfMonth)

        if (Section.BOOKS in scope || Section.ARTICLES in scope) {
            for (e in readingEvents) {
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
            for (t in textElements) {
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
            for (a in avGrams) {
                out += CorpusSnippet(
                    section = Section.MEDIA, date = a.date ?: dayDate, title = "A/V gram",
                    source = a.kind.name.lowercase(), text = "[${a.kind.name.lowercase()}] ${a.filename}",
                    citation = cite(a.date ?: dayDate, "a/v", a.filename)
                )
            }
        }
        if (Section.TASKS in scope) {
            for (li in ledgerItems) {
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
            for (ev in events) {
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
        return "${dayFmt.format(date.toInstant().atZone(ZoneId.systemDefault()))} · $kind · $short"
    }

    private fun dayDate(year: Int, month: Int, day: Int): Date {
        val c = Calendar.getInstance()
        c.clear(); c.set(year, month - 1, day, 12, 0, 0)
        return c.time
    }
}
