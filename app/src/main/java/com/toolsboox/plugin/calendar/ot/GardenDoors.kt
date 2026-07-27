package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.HtmlText
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Today's garden doors — the computed lines the day page's Roots band shows beside the spiral's
 * root pick: 🌰 a hot tag (the recurring `#tag` most alive in the graph right now, and what it
 * rhymes with), 🌱 a sprout (two things you made that rhyme in meaning) and ⁂ a missed rhizome
 * (something you skipped in the feed that speaks to your roots).
 *
 * Choosing the sprout or missed door means embeddings, and embeddings mean the network — nothing
 * the day page's render path can wait on. So the choice is made ONCE per day, off the page, and
 * parked in prefs the way [SpiralRing] parks the root pick; the band reads the parked answer in
 * microseconds and shows a quiet "…" the one time it is still cooking.
 *
 * The tag door is the exception, and the reason to bother: it is a pure GRAPH read — the
 * [LedgerTags] occurrence lists (now that `#hashtag`s join their pages in the [ConnectionStore]
 * rhizome) plus tag co-occurrence — with no embeddings anywhere. So it costs nothing and, unlike
 * its siblings, has something to say on a ledger with no AI key, which is most of them. A young
 * tag already recurring reads as "taking root" — a seed on its way to a root — folding the Seeds
 * incubator into the band; an older recurring tag is simply hot.
 *
 * The sprout is [SemanticRoots.meaningCrossings]' best crossing; the missed door is a trimmed
 * cut of MissedRhizomesFragment's discovery (best unread-entry-to-root rhyme). The trim is
 * deliberate duplication for now — the fragment's version carries UI concerns this door
 * doesn't — and a candidate for a future merge into one discovery seam.
 *
 * ## The rising door — the dynamic lead
 *
 * The four doors above surface the STEADY STATE — the best/most-alive thing in a slowly-changing
 * corpus — so they barely move day to day. The rising door ([KIND_ROOTED]) is the fix: it fires
 * only on a DELTA, and the delta implies its own action. Its source is
 * [com.toolsboox.plugin.calendar.ui.SeedPromotionStore] — the moment a `#tag` graduates Seed→Sprout
 * (its roots network just formed). A tag taking root and an actionable nudge are the SAME event:
 * the tag is exactly ripe to weave together. So the door reads
 * "🌰 #surrogacy just rooted — 6 pages, rhymes with #ethics → weave a synthesis?" and, when a
 * fresh promotion exists, it LEADS the band — superseding the perennial [KIND_TAG] door (they are
 * the same rising-tag family, so only one shows). Pure graph read, no embeddings.
 *
 * A **novelty window** keeps the band from repeating: every door parked for a day is stamped in
 * [SHOWN_PREFS] by its corpus key, and the rising door steps aside for a tag it already led within
 * the last [NOVELTY_DAYS] — unless nothing else qualifies, so a lone signal is never swallowed.
 * A promotion counts as "fresh" for [RISING_WINDOW_DAYS] after it graduates.
 */
object GardenDoors {

    const val KIND_SPROUT = "sprout"
    const val KIND_MISSED = "missed"
    const val KIND_TAG = "tag"

    /** The rising door: a `#tag` that JUST graduated Seed→Sprout, and the synthesis it invites. */
    const val KIND_ROOTED = "rooted"

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

    data class Doors(
        val sprout: Door?, val missed: Door?, val tag: Door? = null, val rooted: Door? = null
    )

    private const val PREFS = "ledger_garden_doors"

    /** Novelty ledger: the epoch-day each door key was last parked into the band, so a door shown
     *  recently can step aside and the band varies day to day. Separate file from the parked answer
     *  because it OUTLIVES the day — it is read on the next day's compute. */
    private const val SHOWN_PREFS = "ledger_garden_doors_shown"

    /** Don't let the rising door re-lead with the same tag within this many days. */
    private const val NOVELTY_DAYS = 3L

    /** A Seed→Sprout promotion counts as "just rooted" for this many days after it graduates. */
    private const val RISING_WINDOW_DAYS = 4L

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
        return Doors(door(KIND_SPROUT), door(KIND_MISSED), door(KIND_TAG), door(KIND_ROOTED))
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
        val sprout = runCatching { sproutDoor(context, corpus) }.getOrNull()
        val missed = runCatching { missedDoor(context, corpusService, corpus) }.getOrNull()
        // Graph-only — needs neither the corpus nor a key, so it stands even when the two
        // rhyme-doors fail soft on a thin ledger or a missing embeddings key.
        val tag = runCatching { tagDoor(context) }.getOrNull()
        // The rising door leads when a promotion is fresh AND hasn't led lately (novelty). Its
        // last-resort form is allowed to re-show only when it would otherwise be the day's ONLY
        // door — a lone signal is never swallowed by its own novelty rule.
        var rooted = runCatching { risingDoor(context, day, allowRecentlyShown = false) }.getOrNull()
        if (rooted == null && tag == null && sprout == null && missed == null)
            rooted = runCatching { risingDoor(context, day, allowRecentlyShown = true) }.getOrNull()
        val doors = Doors(sprout, missed, tag, rooted)
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
            .putString("${KIND_TAG}.text", doors.tag?.text ?: "")
            .putString("${KIND_TAG}.origin", doors.tag?.origin ?: "")
            .putString("${KIND_TAG}.key", doors.tag?.key ?: "")
            .putString("${KIND_TAG}.url", "")
            .putString("${KIND_ROOTED}.text", doors.rooted?.text ?: "")
            .putString("${KIND_ROOTED}.origin", doors.rooted?.origin ?: "")
            .putString("${KIND_ROOTED}.key", doors.rooted?.key ?: "")
            .putString("${KIND_ROOTED}.url", "")
            .apply()
        // Stamp the novelty ledger with what actually LEADS/shows: rooted supersedes tag (same
        // rising-tag family, one glyph), so record whichever of the two the band will draw.
        val shownKeys = buildList {
            if (doors.rooted != null) doors.rooted!!.key.let { if (it.isNotBlank()) add(it) }
            else doors.tag?.key?.let { if (it.isNotBlank()) add(it) }
            doors.sprout?.key?.let { if (it.isNotBlank()) add(it) }
            doors.missed?.key?.let { if (it.isNotBlank()) add(it) }
        }
        recordShown(context, shownKeys, day)
        return doors
    }

    private fun shownPrefs(context: Context) =
        context.getSharedPreferences(SHOWN_PREFS, Context.MODE_PRIVATE)

    /** Stamp each parked door key with the day it was shown, so tomorrow's compute can vary. */
    private fun recordShown(context: Context, keys: Collection<String>, day: LocalDate) {
        if (keys.isEmpty()) return
        val e = shownPrefs(context).edit()
        val epochDay = day.toEpochDay()
        for (k in keys) e.putLong("shown:$k", epochDay)
        e.apply()
    }

    /** True when [key] led the band within the last [days] days — the novelty guard. */
    private fun shownWithin(context: Context, key: String, day: LocalDate, days: Long): Boolean {
        val last = shownPrefs(context).getLong("shown:$key", Long.MIN_VALUE)
        if (last == Long.MIN_VALUE) return false
        return (day.toEpochDay() - last) in 0..days
    }

    /**
     * The rising door: a `#tag` that JUST took root (graduated Seed→Sprout within
     * [RISING_WINDOW_DAYS]), and the synthesis its ripeness invites. Freshest graduation first;
     * a tag that already led the band within [NOVELTY_DAYS] steps aside (unless [allowRecentlyShown],
     * the last-resort pass when it would be the day's only door). Pure graph read — the promotion
     * timestamps from [com.toolsboox.plugin.calendar.ui.SeedPromotionStore] plus the tag's own
     * occurrences and co-occurrence rhyme from [LedgerTags]; no embeddings, no key, no corpus.
     */
    private fun risingDoor(context: Context, day: LocalDate, allowRecentlyShown: Boolean): Door? {
        val promoted = com.toolsboox.plugin.calendar.ui.SeedPromotionStore.all(context)
        if (promoted.isEmpty()) return null
        val now = System.currentTimeMillis()
        val fresh = promoted
            .map { it to com.toolsboox.plugin.calendar.ui.SeedPromotionStore.promotedAt(context, it) }
            .filter { it.second > 0L && (now - it.second) / 86_400_000L <= RISING_WINDOW_DAYS }
            .sortedByDescending { it.second }
        if (fresh.isEmpty()) return null
        val tags = LedgerTags.list(context)
        for ((tag, _) in fresh) {
            val key = LedgerTags.tagUri(tag)
            if (!allowRecentlyShown && shownWithin(context, key, day, NOVELTY_DAYS)) continue
            val info = tags.firstOrNull { it.tag == tag } ?: continue
            val pages = info.occurrences.map { "${it.first}|${it.second}" }.toSet().size
            val rhyme = LedgerTags.relatedTags(context, tag).firstOrNull()?.first
                ?.let { ", rhymes with #$it" } ?: ""
            val text = "#$tag just rooted — $pages page${if (pages == 1) "" else "s"}$rhyme" +
                "  →  weave a synthesis?"
            return Door(KIND_ROOTED, text, origin = "a seed that just took root", key = key)
        }
        return null
    }

    private fun tag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    /**
     * The hot `#tag` — the recurring idea most alive in the graph right now, and the tag or page
     * it rhymes with.
     *
     * Pure graph read, no embeddings: [LedgerTags.list] dates every tag and lists the pages it
     * appears on (the same occurrences that seed the [ConnectionStore] rhizome). A tag scores on
     * two things — how recently it was written (only tags seen in the last three weeks count as
     * "now") and how often it recurs — with a firm bonus when a tag both is YOUNG and already
     * recurs, because that is a seed taking root, exactly the thing the Seeds bed watches for.
     *
     * Its rhyme is the other tag it shares the most pages with — a real crossing in the tag graph —
     * or, when nothing co-occurs, a page it joins in the connection graph. Either gives the glance
     * a companion, the way the quick wins carry theirs. Null when there are no tags, or none has
     * been touched lately, so the door stays shut rather than showing a stale tag.
     */
    private fun tagDoor(context: Context): Door? {
        val tags = LedgerTags.list(context)
        if (tags.isEmpty()) return null
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        data class Hot(
            val info: LedgerTags.TagInfo, val score: Double,
            val taking: Boolean, val isNew: Boolean
        )
        val hot = tags.mapNotNull { info ->
            val lastSeen = info.occurrences.maxByOrNull { it.first }?.first ?: return@mapNotNull null
            val seenDaysAgo = ChronoUnit.DAYS.between(lastSeen, today).coerceAtLeast(0L)
            if (seenDaysAgo > 21) return@mapNotNull null           // trending means recently alive
            val createdDaysAgo = if (info.created > 0L) (now - info.created) / 86_400_000L else Long.MAX_VALUE
            val isNew = createdDaysAgo in 0..14
            val heft = minOf(info.occurrences.size, 12)
            val taking = isNew && info.occurrences.size >= 2       // young AND already recurring
            val score = (22 - seenDaysAgo) + heft * 1.5 + (if (taking) 6.0 else 0.0)
            Hot(info, score, taking, isNew)
        }.maxByOrNull { it.score } ?: return null

        // Its rhyme: the other tag sharing the most pages with it, else a page it joins in the graph.
        val pages = hot.info.occurrences.mapTo(HashSet()) { "${it.first}|${it.second}" }
        val coTag = tags.asSequence()
            .filter { it.tag != hot.info.tag }
            .map { it to it.occurrences.count { o -> "${o.first}|${o.second}" in pages } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
        val rhyme = coTag?.let { "#${it.tag}" }
            ?: ConnectionStore.neighbours(context, LedgerTags.tagUri(hot.info.tag))
                .firstOrNull { !it.startsWith("tag://") }
                ?.let { LedgerUri.describe(it) }?.takeIf { it.isNotBlank() }

        val lead = when {
            hot.taking -> "#${hot.info.tag} is taking root"
            hot.isNew -> "#${hot.info.tag} · a new seed"
            else -> "#${hot.info.tag}"
        }
        return Door(
            KIND_TAG,
            if (rhyme != null) "$lead  ·  rhymes with $rhyme" else lead,
            origin = "${hot.info.occurrences.size}× across your pages",
            key = LedgerTags.tagUri(hot.info.tag)
        )
    }

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
