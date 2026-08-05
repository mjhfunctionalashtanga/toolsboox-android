package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import com.toolsboox.plugin.feeds.nw.FeedCache
import com.toolsboox.ot.HtmlText
import java.io.File
import java.time.LocalDate

/**
 * MISSED CONNECTIONS — the things from his feeds he set down without using.
 *
 * Lifted out of `MissedRhizomesFragment`, where it had been a private method, for one reason: the
 * Synthesize and Brainstorm prompts now instruct the model to "draw on his Missed Connections as
 * well as what he hands you", and a prompt that tells a model to reach for material it was never
 * given does not produce restraint — it produces invention. The whole house rule is never invent;
 * asking for something unreachable is the surest way to get a fabrication.
 *
 * So the surface and the model read the same function. Whatever ✧ Missed Connections shows you is
 * exactly what Ask can reach.
 *
 * Michael's own framing of why these matter, from the prompt: "Those are the pieces most likely to
 * make something new, precisely because he set them down without using them."
 */
object MissedRhizomes {

    data class Find(
        val id: String, val entryTitle: String, val entryUrl: String, val feedName: String,
        val quote: String, val rootTag: String, val rootText: String, val score: Double,
        // The day the skipped thing was published — what a date filter on this surface means.
        val published: LocalDate?
    )

    /**
     * The finds. [corpusService] and [root] come from the caller because this reads BOTH sides —
     * the feed cache for what was skipped, and the corpus for what was kept — and only the caller
     * knows which root it is standing in.
     */
    fun discover(
        ctx: Context,
        corpusService: LedgerCorpusService,
        root: File,
        minScore: Double = 0.60,
        topN: Int = 30,
    ): List<Find> {
        fun strip(html: String): String = HtmlText.toPlain(html)
        // What you genuinely walked past: cached, still UNREAD, never starred — deduped across views.
        // The Miniflux read flag now rides along in the offline cache ([FeedCache] persists `read`), so
        // "skipped" is true unread here, not merely "unstarred"; the session read-tracker
        // ([FeedReadState]) folds in anything opened this run. Read or starred = you engaged with it.
        val cacheDir = File(ctx.filesDir, "feed-cache")
        val lists = cacheDir.listFiles { f -> f.isFile && f.name.startsWith("list-") && f.name.endsWith(".json") }
            ?: return emptyList()
        val seen = HashSet<String>()
        data class Entry(
            val title: String, val url: String, val feed: String, val body: String,
            val published: LocalDate?
        )
        // The cache stamps entries with whatever the feed said: Miniflux hands over RFC 3339,
        // Later rows a bare date — but a LOCAL subscription passes the raw RSS pubDate through
        // untouched, which is usually RFC 1123 ("Wed, 23 Jul 2026 …"). That last shape parsed to
        // null here, and a null date vanished under every filter — the whole of "no missed
        // rhizomes in 2026". So: try each shape the cache actually holds, then the first ten
        // characters as a date of last resort.
        fun published(raw: String): LocalDate? =
            runCatching {
                java.time.OffsetDateTime.parse(raw)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
            }.getOrNull()
                ?: runCatching {
                    java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
                }.getOrNull()
                ?: runCatching { LocalDate.parse(raw) }.getOrNull()
                ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        val unsurfaced = ArrayList<Entry>()
        for (lf in lists) {
            val key = lf.name.removePrefix("list-").removeSuffix(".json")
            for (e in FeedCache.loadEntries(ctx, key)) {
                val engaged = e.starred || e.read ||
                    com.toolsboox.plugin.feeds.ui.FeedReadState.isRead(e.id)
                if (engaged || e.url.isBlank() || !seen.add(e.url)) continue
                // Thrown out by hand ("Remove from corpus") — tombstoned, never re-offered.
                if (corpusService.isExcluded(e.url)) continue
                val body = strip(e.content)
                unsurfaced.add(Entry(e.title, e.url, e.feedTitle, body, published(e.publishedAt)))
            }
        }
        if (unsurfaced.isEmpty()) return emptyList()
        val entries = unsurfaced.take(150)

        // Everything you've kept: the roots corpus (books/feeds/planner/annotations engaged with).
        val roots = corpusService.gather(root, Spiral.SCOPE)
            .filter { Spiral.isSubstantial(it.text) }
            .let { Spiral.dedupe(it) { s -> s.text } }
            .takeLast(300)
        if (roots.isEmpty()) return emptyList()

        // One round of embedding for both sides, then compare in memory.
        fun entryText(e: Entry) = (e.title + ". " + e.body).trim()
        val vecMap = SemanticRoots.vectorsFor(ctx, entries.map { entryText(it) } + roots.map { it.text })
        if (vecMap.isEmpty()) return emptyList()
        val rootVecs = roots.mapNotNull { s -> vecMap[s.text]?.let { s to it } }
        if (rootVecs.isEmpty()) return emptyList()

        val out = ArrayList<Find>()
        for (e in entries) {
            if (e.body.length <= 40) continue
            val ev = vecMap[entryText(e)] ?: continue
            var best: Pair<CorpusSnippet, Double>? = null
            for ((s, sv) in rootVecs) {
                val c = SemanticRoots.cosine(ev, sv)
                if (c > (best?.second ?: -1.0)) best = s to c
            }
            val b = best ?: continue
            if (b.second < minScore || b.second >= 0.97) continue
            out.add(Find(
                id = e.url, entryTitle = e.title, entryUrl = e.url, feedName = e.feed,
                quote = bestSentence(e.body, b.first.text) ?: e.body.take(220),
                rootTag = tag(b.first), rootText = b.first.text.take(200), score = b.second,
                published = e.published))
        }
        return out.sortedByDescending { it.score }.take(topN)
    }

    /** What a matched root is CALLED on a find's card. */
    private fun tag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    /** The sentence in [body] that rhymes hardest with [rootText] — the quote a find shows. */
    private fun bestSentence(body: String, rootText: String): String? {
        val rootWords = tokenize(rootText)
        if (rootWords.isEmpty()) return null
        val sentences = body.split(Regex("[.!?]"))
            .map { it.trim() }.filter { it.length >= 30 }.take(12)
        var best: Pair<String, Int>? = null
        for (s in sentences) {
            val overlap = tokenize(s).count { it in rootWords }
            if (overlap > (best?.second ?: 0)) best = s.take(240) to overlap
        }
        return best?.first
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.toSet()
}
