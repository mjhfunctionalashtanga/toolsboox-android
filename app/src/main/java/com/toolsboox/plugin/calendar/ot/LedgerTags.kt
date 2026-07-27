package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.RectF
import java.time.LocalDate

/**
 * Handwritten `#hashtag` tags on note pages — the lightweight alternative to NAMING every page.
 * You write `#something` on a page; when the page's ink is OCR'd (capture sections), the tag is
 * harvested and recorded as appearing on that (date, pageKey), and — crucially — joined to the page
 * in the [ConnectionStore] rhizome, so tags and connections reinforce one another into one navigable
 * graph. A tag is reached by [tagUri] ("tag://<tag>"); a page by the usual "ledger://<date>/<page>".
 *
 * The `#` glyph is the fragile part of handwriting OCR, so the extractor is strict about the WORD
 * (a letter start, then word chars) and tolerant that a stray hash-ish mark may occasionally slip a
 * tag through — better a visible stray than a silently-missed tag. Recognized tags surface in the
 * Tags index (hub → Notes → Tags) so a bad OCR is fixable rather than lost.
 *
 * Storage is a local SharedPreferences map for the occurrence list; the tag↔page EDGES ride the
 * connection graph, which already syncs across devices, so the rhizome carries tags even though this
 * occurrence cache is device-local (a synced occurrence store can follow later). An occurrence is
 * "date|pageKey" held in a StringSet, so no delimiter can collide with a page key — OPTIONALLY
 * suffixed "|l,t,r,b" with the design-space rect of the CAPTURE ZONE the tag was written in, so a
 * jump from the Tags index can land on the tag's MARK (zoom/center that zone) rather than the page.
 * Occurrences with no rect (legacy captures) parse to a null location and just open the page.
 *
 * The rect is a WORD box when the harvest can place the tag, else the zone. Android asks the vision
 * LLM ([com.toolsboox.plugin.calendar.nw.VisionOcr.recognizeTagBoxes]) for each tag's APPROXIMATE
 * normalized box — the model already reads the ink, so it can point at where it read the word — and
 * falls back to the zone rect for any tag it can't place; iOS records EXACT Apple Vision boxes. Both
 * feed the same occurrence rect → [com.toolsboox.ui.plugin.SurfaceFragment.focusOnRect] plumbing, so
 * an Android tag lands on an estimated word box and an iOS tag on an exact one, with no downstream
 * difference. [record] still records at the zone level from bare text (no bitmap); [recordTag] takes
 * a single known tag + its word rect for the harvest's per-word path.
 */
object LedgerTags {

    private const val PREFS = "ledger_tags"

    /** A hash (not preceded by a word char or another hash) then a word: a letter, then letters /
     *  digits / _ / - (2–41 chars total). Case-folded on store so `#Ashtanga` and `#ashtanga` merge. */
    private val HASHTAG = Regex("""(?<![\w#])#([\p{L}][\p{L}\p{N}_-]{1,40})""")

    /** The canonical URI for a tag node in the rhizome. */
    fun tagUri(tag: String): String = "tag://${tag.lowercase()}"

    /** The extracted, case-folded tags in [text] (deduped), or empty. */
    fun extract(text: String): Set<String> =
        HASHTAG.findAll(text).map { it.groupValues[1].lowercase() }.toSet()

    /**
     * Harvest `#tags` from a page's recognized [text] and record each as appearing on (date, page),
     * and join the tag to the page in the connection graph. No-op when the text carries no tags.
     *
     * [zoneRect], when given, is the design-space (1404×1872) rect of the capture zone this [text]
     * came from; it rides on the occurrence so a jump can land on the tag's mark. Omit it (or pass
     * null) to record a plain page-level occurrence, as before.
     */
    fun record(context: Context, date: LocalDate, pageKey: String, text: String, zoneRect: RectF? = null) {
        val tags = extract(text)
        if (tags.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (tag in tags) putOccurrence(context, prefs, editor, date, pageKey, tag, zoneRect)
        editor.apply()
    }

    /**
     * Record a single, already-extracted [tag] (case-folded, no leading '#') on (date, page) with an
     * explicit [wordRect] in design space (1404×1872) — the harvest's per-word path, once it has a
     * word box for the tag (Android: an LLM-estimated box; iOS: an exact Vision box). Pass null to
     * fall back to a plain page-level occurrence. Same storage + rhizome join as [record].
     */
    fun recordTag(context: Context, date: LocalDate, pageKey: String, tag: String, wordRect: RectF? = null) {
        val t = tag.trim().lowercase()
        if (t.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        putOccurrence(context, prefs, editor, date, pageKey, t, wordRect)
        editor.apply()
    }

    /** Write one tag occurrence (with optional [rect]) into [editor] and join tag↔page in the graph. */
    private fun putOccurrence(
        context: Context, prefs: android.content.SharedPreferences, editor: android.content.SharedPreferences.Editor,
        date: LocalDate, pageKey: String, tag: String, rect: RectF?
    ) {
        val loc = rect?.let { "|${it.left},${it.top},${it.right},${it.bottom}" } ?: ""
        val occ = "$date|$pageKey$loc"
        if (prefs.getString("tag:$tag:created", null) == null) {
            editor.putString("tag:$tag:created", System.currentTimeMillis().toString())
        }
        val occs = prefs.getStringSet("tag:$tag:occ", emptySet())!!.toMutableSet()
        occs.add(occ)
        editor.putStringSet("tag:$tag:occ", occs)
        // The tag joins the page in the rhizome — one graph for tags AND connections.
        ConnectionStore.connect(
            context, tagUri(tag), "ledger://$date/$pageKey",
            fromLabel = "#$tag", toLabel = pageKey
        )
    }

    /**
     * A tag with its created-date and every (date, page, zoneRect?) it appears on. The rect is the
     * design-space zone the tag was written in when known (see [record]); null for legacy
     * occurrences, which just open the page. Kept as a Triple so `.first`/`.second` still read as
     * (date, page) for every existing caller, with the location added as `.third`.
     */
    data class TagInfo(val tag: String, val created: Long, val occurrences: List<Triple<LocalDate, String, RectF?>>)

    /** Parse one stored occurrence — "date|pageKey" or "date|pageKey|l,t,r,b" — or null if unusable. */
    private fun parseOccurrence(o: String): Triple<LocalDate, String, RectF?>? {
        val parts = o.split('|')
        if (parts.size < 2) return null
        val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        val rect = parts.getOrNull(2)?.let { s ->
            val c = s.split(',').mapNotNull { it.toFloatOrNull() }
            if (c.size == 4) RectF(c[0], c[1], c[2], c[3]) else null
        }
        return Triple(date, parts[1], rect)
    }

    /** Every tag seen, most-used first. */
    fun list(context: Context): List<TagInfo> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys
            .filter { it.startsWith("tag:") && it.endsWith(":occ") }
            .mapNotNull { key ->
                val tag = key.removePrefix("tag:").removeSuffix(":occ")
                val created = prefs.getString("tag:$tag:created", null)?.toLongOrNull() ?: 0L
                val occs = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { parseOccurrence(it) }
                if (occs.isEmpty()) null else TagInfo(tag, created, occs)
            }
            .sortedByDescending { it.occurrences.size }
    }

    /** The (date, page, zoneRect?) occurrences of one tag, newest first. */
    fun pagesFor(context: Context, tag: String): List<Triple<LocalDate, String, RectF?>> =
        list(context).firstOrNull { it.tag == tag.lowercase() }?.occurrences?.sortedByDescending { it.first }
            ?: emptyList()

    /** Every tag seen on [date] (any page), most-used first — for the day's tag header on page one. */
    fun tagsFor(context: Context, date: LocalDate): List<String> =
        list(context)
            .filter { info -> info.occurrences.any { it.first == date } }
            .map { it.tag }

    /** A page identity for co-occurrence: two tags "share a page" when they land on the same one. */
    private fun Triple<LocalDate, String, RectF?>.pageId(): String = "${first}|${second}"

    /**
     * The other tags that share at least one page with [tag] — its RELATED tags — heaviest (most
     * shared pages) first. This is the "co-occurrence" a tag's rhizome shows as neighbours: the tag
     * web read from one node. Pure occurrence-store read, no graph write.
     */
    fun relatedTags(context: Context, tag: String): List<Pair<String, Int>> {
        val t = tag.lowercase()
        val all = list(context)
        val mine = all.firstOrNull { it.tag == t } ?: return emptyList()
        val myPages = mine.occurrences.map { it.pageId() }.toSet()
        if (myPages.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.tag != t }
            .map { other -> other.tag to other.occurrences.count { it.pageId() in myPages } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .toList()
    }

    /**
     * Every tag↔tag co-occurrence across the whole ledger, as unordered `(tagA, tagB, sharedPages)`
     * triples, heaviest first — the edges the Map draws so the tag web is visible. Cheap: one pass
     * over the occurrence store to bucket tags by page, then count the pairs each page contributes.
     * The caller caps + logs; the pair count is bounded by tags² and is small in practice.
     */
    fun coOccurrences(context: Context): List<Triple<String, String, Int>> {
        val all = list(context)
        if (all.size < 2) return emptyList()
        val pageTags = HashMap<String, MutableSet<String>>()
        for (info in all) for (occ in info.occurrences) {
            pageTags.getOrPut(occ.pageId()) { mutableSetOf() }.add(info.tag)
        }
        val pairCount = HashMap<Pair<String, String>, Int>()
        for (tags in pageTags.values) {
            if (tags.size < 2) continue
            val sorted = tags.sorted()
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                val key = sorted[i] to sorted[j]
                pairCount[key] = (pairCount[key] ?: 0) + 1
            }
        }
        return pairCount.entries
            .sortedByDescending { it.value }
            .map { Triple(it.key.first, it.key.second, it.value) }
    }
}
