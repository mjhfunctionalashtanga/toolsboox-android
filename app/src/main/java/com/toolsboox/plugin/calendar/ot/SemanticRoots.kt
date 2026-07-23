package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.nw.EmbeddingIndex

/**
 * Meaning over the corpus — the Android mirror of the iPad's `SemanticRoots`.
 *
 * The roots (see [Rhizome]) find where two things share a WORD. This finds where two things share a
 * MEANING though they share no word: the crossing that a keyword index can never see. It leans on
 * the on-device [EmbeddingIndex] — the same OpenAI embeddings + persistent vector cache that "Ask my
 * Ledger" already uses for hybrid retrieval — so a snippet is embedded once and then compared for
 * free thereafter.
 *
 * Every call is blocking (it may reach the network to embed anything new) and must be made off the
 * main thread. Absent an embeddings key it returns nothing rather than guessing — a semantic surface
 * with no semantics is honestly empty, not quietly keyword-shaped.
 */
object SemanticRoots {

    /** One corpus snippet paired with how hard it rhymes with the query. */
    data class Neighbor(val snippet: CorpusSnippet, val score: Double)

    /** Two things you kept that mean the same though nothing links them. */
    data class Crossing(val a: CorpusSnippet, val b: CorpusSnippet, val score: Double)

    /** Cosine similarity of two vectors — the shape of "these mean the same". */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    /**
     * Embed [texts] using and updating the shared vector cache. Returns text→vector for everything
     * that could be embedded; an empty map when there's no key or the call fails, so callers degrade
     * to "nothing rhymes" rather than crashing.
     */
    private fun vectors(context: Context, texts: List<String>): Map<String, FloatArray> {
        val wanted = texts.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val apiKey = EmbeddingIndex.apiKey(context) ?: return emptyMap()
        val cache = EmbeddingIndex.loadCache(context)
        val need = wanted.filter { EmbeddingIndex.hash(it) !in cache }
        if (need.isNotEmpty()) {
            val vecs = EmbeddingIndex.embed(apiKey, need) ?: return emptyMap()
            if (vecs.size != need.size) return emptyMap()
            need.forEachIndexed { i, t -> cache[EmbeddingIndex.hash(t)] = vecs[i] }
            EmbeddingIndex.saveCache(context, cache)
        }
        val out = HashMap<String, FloatArray>(wanted.size)
        for (t in wanted) cache[EmbeddingIndex.hash(t)]?.let { out[t] = it }
        return out
    }

    /** The vector for one piece of text, or null when embeddings aren't available. */
    fun vector(context: Context, text: String): FloatArray? = vectors(context, listOf(text))[text]

    /**
     * Embed a whole batch at once (chunked + cached under the hood) and hand back text→vector — for
     * callers like Missed Rhizomes that score many things against many things and want one round of
     * embedding rather than a call per item. Empty when embeddings aren't configured.
     */
    fun vectorsFor(context: Context, texts: List<String>): Map<String, FloatArray> = vectors(context, texts)

    /**
     * The [topN] snippets in [corpus] whose meaning rhymes hardest with [query], each at or above
     * [minScore]. Near-identical matches (a snippet that IS the query) are dropped so a rhyme is a
     * rhyme, not an echo.
     */
    fun neighbors(
        context: Context, query: String, corpus: List<CorpusSnippet>,
        topN: Int = 2, minScore: Double = 0.55
    ): List<Neighbor> {
        if (query.isBlank() || corpus.isEmpty()) return emptyList()
        val vecs = vectors(context, listOf(query) + corpus.map { it.text })
        val qv = vecs[query] ?: return emptyList()
        return corpus.mapNotNull { s ->
            val sv = vecs[s.text] ?: return@mapNotNull null
            val c = cosine(qv, sv)
            if (c >= minScore && c < 0.985) Neighbor(s, c) else null
        }.sortedByDescending { it.score }.take(topN)
    }

    /**
     * The emergent crossings across the whole [corpus]: the pairs that mean the same though nothing
     * links them. Bounded to the most recent [cap] snippets (pairwise is O(n²)); a pair must score at
     * or above [minScore] and stay below a near-duplicate ceiling. No single snippet is allowed to
     * monopolise the harvest — it may anchor at most a couple of crossings — so the top [topN] read as
     * a spread of ideas rather than one loud one repeated.
     */
    fun meaningCrossings(
        context: Context, corpus: List<CorpusSnippet>,
        cap: Int = 260, topN: Int = 40, minScore: Double = 0.60
    ): List<Crossing> {
        if (corpus.size < 2) return emptyList()
        // Most recent first, then capped — the harvest should taste of what you've been making.
        val pool = corpus.sortedByDescending { it.date.time }.take(cap)
        val vecMap = vectors(context, pool.map { it.text })
        val items = pool.mapNotNull { s -> vecMap[s.text]?.let { s to it } }
        if (items.size < 2) return emptyList()

        val found = ArrayList<Crossing>()
        for (i in items.indices) {
            val (sa, va) = items[i]
            for (j in i + 1 until items.size) {
                val (sb, vb) = items[j]
                if (sa.text == sb.text) continue
                val c = cosine(va, vb)
                // >= minScore is a real rhyme; < 0.97 keeps out the same thought that reached the
                // corpus by two roads (a picking also OCR'd, a note also a text box).
                if (c >= minScore && c < 0.97) found.add(Crossing(sa, sb, c))
            }
        }
        found.sortByDescending { it.score }

        val used = HashMap<String, Int>()
        val out = ArrayList<Crossing>(topN)
        for (c in found) {
            val ka = c.a.citation + "|" + c.a.text.take(24)
            val kb = c.b.citation + "|" + c.b.text.take(24)
            if ((used[ka] ?: 0) >= 2 || (used[kb] ?: 0) >= 2) continue
            used[ka] = (used[ka] ?: 0) + 1
            used[kb] = (used[kb] ?: 0) + 1
            out.add(c)
            if (out.size >= topN) break
        }
        return out
    }
}
