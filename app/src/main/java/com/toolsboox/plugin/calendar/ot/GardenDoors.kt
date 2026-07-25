package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.HtmlText
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import java.io.File
import java.time.LocalDate

/**
 * Today's garden doors — the two computed lines the day page's Roots band shows beside the
 * spiral's root pick: 🌱 a sprout (two things you made that rhyme in meaning) and ⁂ a missed
 * rhizome (something you skipped in the feed that speaks to your roots).
 *
 * Choosing either means embeddings, and embeddings mean the network — nothing the day page's
 * render path can wait on. So the choice is made ONCE per day, off the page, and parked in
 * prefs the way [SpiralRing] parks the root pick; the band reads the parked answer in
 * microseconds and shows a quiet "…" the one time it is still cooking.
 *
 * The sprout is [SemanticRoots.meaningCrossings]' best crossing; the missed door is a trimmed
 * cut of MissedRhizomesFragment's discovery (best unread-entry-to-root rhyme). The trim is
 * deliberate duplication for now — the fragment's version carries UI concerns this door
 * doesn't — and a candidate for a future merge into one discovery seam.
 */
object GardenDoors {

    const val KIND_SPROUT = "sprout"
    const val KIND_MISSED = "missed"

    /** One door: the line to show, where it leads, and the corpus key a removal tombstones. */
    data class Door(
        val kind: String,
        val text: String,
        /** The small provenance tail — tags for a sprout, the feed for a missed find. */
        val origin: String,
        /** What [LedgerCorpusService.exclude] takes: a citation (sprout) or entry URL (missed). */
        val key: String,
        val url: String = ""
    )

    data class Doors(val sprout: Door?, val missed: Door?)

    private const val PREFS = "ledger_garden_doors"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The parked answer for [day], or null when it hasn't been computed yet. A computed-but-
     *  empty pair comes back as Doors(null, null) — "we looked, nothing qualifies" — which is a
     *  different fact from null's "still cooking". */
    fun cachedFor(context: Context, day: LocalDate): Doors? {
        val p = prefs(context)
        if (p.getString("day", "") != day.toString()) return null
        fun door(kind: String): Door? {
            val text = p.getString("$kind.text", "").orEmpty()
            if (text.isBlank()) return null
            return Door(
                kind, text,
                p.getString("$kind.origin", "").orEmpty(),
                p.getString("$kind.key", "").orEmpty(),
                p.getString("$kind.url", "").orEmpty()
            )
        }
        return Doors(door(KIND_SPROUT), door(KIND_MISSED))
    }

    /** Blank one door for the rest of the day — after "Remove from corpus", the removed thing
     *  must not keep standing in its own doorway until midnight. */
    fun drop(context: Context, kind: String) {
        prefs(context).edit()
            .putString("$kind.text", "").putString("$kind.origin", "")
            .putString("$kind.key", "").putString("$kind.url", "")
            .apply()
    }

    /**
     * Choose today's doors and park them. IO + network (embeddings) — call from an IO context,
     * never the render path. Each door fails soft on its own: no embeddings key, no feed cache,
     * a thin corpus all mean that door simply stays shut today.
     */
    fun compute(
        context: Context, corpusService: LedgerCorpusService, root: File, day: LocalDate
    ): Doors {
        val corpus = runCatching {
            corpusService.gather(root, Spiral.SCOPE)
                .filter { Spiral.isSubstantial(it.text) }
                .let { Spiral.dedupe(it) { s -> s.text } }
        }.getOrDefault(emptyList())
        val doors = Doors(
            runCatching { sproutDoor(context, corpus) }.getOrNull(),
            runCatching { missedDoor(context, corpusService, corpus) }.getOrNull()
        )
        prefs(context).edit()
            .putString("day", day.toString())
            .putString("${KIND_SPROUT}.text", doors.sprout?.text ?: "")
            .putString("${KIND_SPROUT}.origin", doors.sprout?.origin ?: "")
            .putString("${KIND_SPROUT}.key", doors.sprout?.key ?: "")
            .putString("${KIND_SPROUT}.url", "")
            .putString("${KIND_MISSED}.text", doors.missed?.text ?: "")
            .putString("${KIND_MISSED}.origin", doors.missed?.origin ?: "")
            .putString("${KIND_MISSED}.key", doors.missed?.key ?: "")
            .putString("${KIND_MISSED}.url", doors.missed?.url ?: "")
            .apply()
        return doors
    }

    private fun tag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    /** The strongest crossing, said in one breath: "a ⇄ b". */
    private fun sproutDoor(context: Context, corpus: List<CorpusSnippet>): Door? {
        if (corpus.isEmpty()) return null
        val c = SemanticRoots.meaningCrossings(context, corpus, cap = 160, topN = 1, minScore = 0.60)
            .firstOrNull() ?: return null
        val a = c.a.text.replace(Regex("\\s+"), " ").trim().take(120)
        val b = c.b.text.replace(Regex("\\s+"), " ").trim().take(120)
        return Door(
            KIND_SPROUT, "$a  ⇄  $b",
            origin = "${tag(c.a)} ⇄ ${tag(c.b)}".take(60),
            key = c.a.citation
        )
    }

    /** The deepest rhyme between something you skipped and something you kept — the fragment's
     *  discovery with the caps pulled in, since one line only needs one find. */
    private fun missedDoor(
        context: Context, corpusService: LedgerCorpusService, corpus: List<CorpusSnippet>
    ): Door? {
        if (corpus.isEmpty()) return null
        val cacheDir = File(context.filesDir, "feed-cache")
        val lists = cacheDir.listFiles { f ->
            f.isFile && f.name.startsWith("list-") && f.name.endsWith(".json")
        } ?: return null

        data class Entry(val title: String, val url: String, val feed: String, val body: String)
        val seen = HashSet<String>()
        val unsurfaced = ArrayList<Entry>()
        outer@ for (lf in lists) {
            val key = lf.name.removePrefix("list-").removeSuffix(".json")
            for (e in com.toolsboox.plugin.feeds.nw.FeedCache.loadEntries(context, key)) {
                val engaged = e.starred || e.read ||
                    com.toolsboox.plugin.feeds.ui.FeedReadState.isRead(e.id)
                if (engaged || e.url.isBlank() || !seen.add(e.url)) continue
                if (corpusService.isExcluded(e.url)) continue
                val body = HtmlText.toPlain(e.content)
                if (body.length <= 40) continue
                unsurfaced.add(Entry(e.title, e.url, e.feedTitle, body))
                if (unsurfaced.size >= 60) break@outer
            }
        }
        if (unsurfaced.isEmpty()) return null

        val roots = corpus.takeLast(150)
        fun entryText(e: Entry) = (e.title + ". " + e.body).trim()
        val vecs = SemanticRoots.vectorsFor(
            context, unsurfaced.map { entryText(it) } + roots.map { it.text })
        if (vecs.isEmpty()) return null
        val rootVecs = roots.mapNotNull { s -> vecs[s.text]?.let { s to it } }
        if (rootVecs.isEmpty()) return null

        var best: Triple<Entry, CorpusSnippet, Double>? = null
        for (e in unsurfaced) {
            val ev = vecs[entryText(e)] ?: continue
            for ((s, sv) in rootVecs) {
                val c = SemanticRoots.cosine(ev, sv)
                if (c >= 0.97) continue                    // near-identity is a mirror, not a rhyme
                if (c > (best?.third ?: 0.60)) best = Triple(e, s, c)
            }
        }
        val (e, s, _) = best ?: return null
        return Door(
            KIND_MISSED, e.title.replace(Regex("\\s+"), " ").trim().take(160),
            origin = "rhymes with ${tag(s)}".take(60),
            key = e.url, url = e.url
        )
    }
}
