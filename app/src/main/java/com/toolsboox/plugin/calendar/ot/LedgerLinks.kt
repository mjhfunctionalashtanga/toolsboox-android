package com.toolsboox.plugin.calendar.ot

import android.content.Context
import java.time.LocalDate

/**
 * `[[Wiki links]]` written BY HAND, harvested from the same OCR pass that finds `#tags`.
 *
 * Michael, 2026-08-05, on notes becoming real Markdown files: "being able to link notes from within
 * notes feels like a great byproduct. deeper interlinking."
 *
 * It is cheaper than it sounds, and the reason is [LedgerTags]. That object already solves the hard
 * half: a token written by hand on a page, recognised when the ink is OCR'd, recorded against
 * (date, page), and — the part that matters most — JOINED INTO [ConnectionStore], the rhizome the
 * Map draws from. A link is the same shape with a different bracket, so this is deliberately its
 * twin rather than a new mechanism.
 *
 * What that buys, and why it is worth having:
 *
 *     you handwrite [[Collider]] on a page
 *       → the OCR pass recognises it
 *         → the link is recorded, and Collider ↔ this page joins the rhizome
 *           → the pair is an EDGE on the Map
 *             → the Map saves to a Grid note with the provenance intact (GramComposer)
 *
 * So the interlinking is not a Markdown feature bolted on for Obsidian's benefit; it is the same
 * graph the app already thinks in, reachable with a pen. Markdown just happens to spell it the same
 * way, which is why the export needs no translation layer.
 *
 * Like tags, the occurrence store is per device and the EDGES sync; [all] and [backlinks] merge the
 * synced edges back in (2026-10-11), so a link written on the other device lists here too, and a
 * link cut on either device stops listing. PARITY: iOS's backlinks()/all() still read only its
 * own store — the same merge is what its port has to adopt.
 *
 * A link differs from a tag in exactly one way, and the difference is the whole point: a tag is a
 * LABEL (many pages wear it, it names no page), while a link has a TARGET (one named note, which
 * may or may not exist yet). Hence [resolve] and [backlinks], which tags have no use for.
 */
object LedgerLinks {

    private const val PREFS = "ledger_links"

    /**
     * `[[Target]]`, or `[[Target|shown words]]`.
     *
     * The pattern is strict for the same reason [LedgerTags]' is: this runs over the output of
     * handwriting recognition, where a lone bracket is among the likeliest characters to be
     * hallucinated. Requiring BOTH doubled brackets and at least one letter inside means a stray
     * mark cannot mint a link — a false tag is noise in an index, but a false link is an edge on
     * your Map and a wrong claim about how two ideas relate.
     *
     * The pipe form exists because handwriting is slow: `[[project_ledger_named_notes|the naming
     * work]]` lets a long address read as a short phrase in the sentence.
     */
    private val LINK = Regex("""\[\[\s*([^\[\]|]*[\p{L}][^\[\]|]*?)\s*(?:\|([^\[\]]*))?]]""")

    // Code carries brackets that are not links. Reused verbatim from LedgerTags' reasoning: an
    // array index in a fenced block is not a claim about your ideas.
    private val CODE_FENCE = Regex("""```.*?```|~~~.*?~~~""", RegexOption.DOT_MATCHES_ALL)
    private val CODE_SPAN = Regex("""`[^`\n]*`""")

    // Compiled once: [normalise] runs per name per target wherever links are resolved.
    private val WHITESPACE = Regex("""\s+""")

    private fun withoutCode(text: String): String =
        CODE_SPAN.replace(CODE_FENCE.replace(text, " "), " ")

    /** The canonical URI for a link target — a node in the same rhizome tags live in. */
    fun linkUri(target: String): String = "note://${normalise(target)}"

    /**
     * The comparison form of a target name.
     *
     * Case-folded and whitespace-collapsed, because a link written by hand on Tuesday and the note
     * it points at named on Monday will not agree about capitals or double spaces, and a link that
     * misses by a capital letter is worse than no link — it silently creates a second, empty
     * destination that looks real.
     */
    fun normalise(target: String): String =
        target.trim().lowercase().replace(WHITESPACE, " ")

    /** Every distinct target linked in [text], in the form they were written. */
    fun extract(text: String): List<Link> =
        LINK.findAll(withoutCode(text))
            .map { Link(it.groupValues[1].trim(), it.groupValues[2].trim().ifEmpty { null }) }
            .distinctBy { normalise(it.target) }
            .toList()

    /** One link as written: where it points, and what it reads as in the sentence. */
    data class Link(val target: String, val label: String? = null) {
        val shown: String get() = label ?: target
    }

    /**
     * Record every `[[link]]` in a page's recognised [text], and join each target to the page in
     * the rhizome.
     *
     * Deliberately a no-op on empty text rather than a clear: an OCR pass that recognises nothing
     * (a photographed page, a failed call, a page whose ink hasn't been captured yet) must not be
     * read as "this page has no links now" and quietly unmake the graph.
     */
    fun record(context: Context, date: LocalDate, pageKey: String, text: String) =
        recordLinks(context, date, pageKey, extract(text))

    /**
     * Record already-extracted [links] against one page — [record]'s body, for a caller that read
     * several texts itself (the typed-box harvest reads each box on its own, so a bracket in one
     * box can never pair with another's, then records the page's links once).
     */
    fun recordLinks(context: Context, date: LocalDate, pageKey: String, links: List<Link>) {
        if (links.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val source = "ledger://$date/$pageKey"
        for (link in links) {
            val key = normalise(link.target)
            // ONE OCCURRENCE PER (target, date, page), same rule as tags: writing a link twice on
            // one page does not make the page appear twice in the backlinks.
            val occs = prefs.getStringSet("link:$key:from", emptySet())!!.toMutableSet()
            occs.add("$date|$pageKey")
            editor.putStringSet("link:$key:from", occs)
            // Keep the prettiest spelling seen — the first non-lowercase form wins, so an index
            // built from handwriting still reads as "Collider" rather than "collider".
            if (prefs.getString("link:$key:name", null) == null) {
                editor.putString("link:$key:name", link.target)
            }
            // THE JOIN. This is the line that makes a handwritten link an edge on the Map.
            ConnectionStore.connect(
                context, source, linkUri(link.target),
                fromLabel = pageKey, toLabel = link.target
            )
        }
        editor.apply()
    }

    /**
     * Whether [target] is already recorded as linked from (date, page) — so a caller that harvests on
     * every save (the typed-box harvest) records only what is NEW, rather than rewriting and
     * re-syncing the whole graph per link per save (review of 6b46141e; iOS's harvest batches new
     * edges only for the same reason). The handwriting pass keeps [record], which re-joins as before.
     */
    fun isRecorded(context: Context, target: String, date: LocalDate, pageKey: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("link:${normalise(target)}:from", emptySet())!!
            .contains("$date|$pageKey")

    /**
     * Every page that links TO [target] — the backlinks, newest first: this device's occurrences
     * merged with the synced graph's `ledger://D/P → note://target` edges, so a [[link]] written on
     * the iPad lists here too (Michael 2026-10-11: "The links aren't quite working yet"). One row
     * per (date, page); element ends count as their page.
     */
    fun backlinks(context: Context, target: String): List<Occurrence> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = normalise(target)
        val view = edgeView(context)
        // A link CUT (on the Map, on either device) after this device recorded it: its edge is
        // tombstoned with no live twin, so the backlink goes too — the same removal rule as tags
        // (review of dbe1a38d). Filtered, not pruned: a re-harvest revives the edge and the row.
        val gone = view?.removed?.get(key).orEmpty()
        val local = prefs.getStringSet("link:$key:from", emptySet())!!
            .mapNotNull { raw ->
                val parts = raw.split("|")
                if (parts.size < 2) return@mapNotNull null
                runCatching { Occurrence(LocalDate.parse(parts[0]), parts[1]) }.getOrNull()
            }
            .filterNot { (it.date to it.pageKey) in gone }
        val held = local.mapTo(HashSet()) { it.date to it.pageKey }
        val synced = view?.pages?.get(key).orEmpty().filter { held.add(it.date to it.pageKey) }
        return (local + synced).sortedByDescending { it.date }
    }

    /** Where a link was written. */
    data class Occurrence(val date: LocalDate, val pageKey: String)

    /**
     * The OBJECTS (not pages) that link to [target] — a Text Note's [[links]] (harvested on save,
     * 2026-10-11), on either device — as (uri, label) pairs, for the Links index's backlink rows.
     */
    fun objectBacklinks(context: Context, target: String): List<Pair<String, String>> =
        edgeView(context)?.objects?.get(normalise(target)).orEmpty()

    /**
     * Every target that has ever been linked, prettiest-spelling first, alphabetical — this device's
     * and every target the synced graph carries (a [[link]] written on the iPad, or in a Text Note).
     */
    fun all(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val local = prefs.all.keys
            .filter { it.startsWith("link:") && it.endsWith(":name") }
            .mapNotNull { prefs.getString(it, null) }
        val synced = edgeView(context)?.names?.values.orEmpty()
        return (local + synced)
            .distinctBy { normalise(it) }
            .sortedBy { it.lowercase() }
    }

    /**
     * Record the `[[links]]` in an OBJECT's text — a Text Note, which is not a page — as edges from
     * [uri] to each target, so the Links index lists the note under the target's backlinks and the
     * Map draws the edge (Michael 2026-10-11, Text Notes harvest on save). PARITY: iOS records a
     * Text Note's #tags only, not its [[links]] — its `recordObjectLinks` twin is still to come.
     * No page occurrence: there is no page. Only targets this object doesn't already join (or joins
     * under an older label) are written, because each write rewrites and re-syncs the graph and this
     * runs on every save. Additive, like the tag harvest: a link deleted from the text keeps its
     * edge until it is cut on the Map.
     */
    fun recordObjectLinks(context: Context, uri: String, label: String, text: String): Int {
        if (uri.isBlank()) return 0
        val links = extract(text)
        if (links.isEmpty()) return 0
        val existing = ConnectionStore.touching(context, uri)
            .filter { it.from == uri && it.to.startsWith("note://") }
            .associateBy { it.to }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var n = 0
        for (link in links) {
            val to = linkUri(link.target)
            val have = existing[to]
            if (prefs.getString("link:${normalise(link.target)}:name", null) == null) {
                editor.putString("link:${normalise(link.target)}:name", link.target)
            }
            if (have != null && have.fromLabel == label) continue
            ConnectionStore.connect(context, uri, to, fromLabel = label, toLabel = link.target)
            n++
        }
        editor.apply()
        return n
    }

    // ── THE SYNCED GRAPH'S VIEW OF LINKS ─────────────────────────────────────────────────────

    /** What the graph says about links, per normalised target: its prettiest spelling, the pages
     *  that link to it, and the objects (Text Notes) that do. */
    internal class EdgeLinks(
        val names: Map<String, String>,
        val pages: Map<String, List<Occurrence>>,
        val objects: Map<String, List<Pair<String, String>>>,
        /** Whole-page link edges TOMBSTONED with no live twin, per target — cut since recorded. */
        val removed: Map<String, Set<Pair<LocalDate, String>>> = emptyMap(),
    )

    /**
     * Read the live LINK edges out of the graph. Pure, so it is a JVM test.
     *
     * A link edge is told from any other edge into `note://` by its own shape: both apps write
     * `note://<normalised target>` with the target's spelling as `toLabel`, so normalise(toLabel)
     * equals the body. A text note joined to a page (`note://<id>`, labelled with its title) fails
     * that test and is not mistaken for a link to a note named after its id. Tag edges are never
     * links.
     */
    internal fun deriveFromEdges(edges: List<com.toolsboox.plugin.calendar.da.v2.Connection>): EdgeLinks {
        val names = HashMap<String, String>()
        val pages = HashMap<String, LinkedHashMap<Pair<LocalDate, String>, Occurrence>>()
        val objects = HashMap<String, LinkedHashMap<String, Pair<String, String>>>()
        val live = HashMap<String, HashSet<Pair<LocalDate, String>>>()
        val dead = HashMap<String, HashSet<Pair<LocalDate, String>>>()
        for (e in edges) {
            if (!e.to.startsWith("note://") || e.from.startsWith("tag://")) continue
            val body = e.to.removePrefix("note://")
            if (body.isBlank() || e.toLabel.isBlank() || normalise(e.toLabel) != body) continue
            val end = LedgerTags.endOf(e.from)
            if (end != null && !end.element) {
                (if (e.isDeleted) dead else live).getOrPut(body) { HashSet() }.add(end.date to end.page)
            }
            if (e.isDeleted) continue
            // The prettiest spelling: one that isn't all lowercase beats one that is.
            val cur = names[body]
            if (cur == null || (cur == cur.lowercase() && e.toLabel != e.toLabel.lowercase())) names[body] = e.toLabel
            val page = LedgerTags.pageOfEnd(e.from)
            if (page != null) {
                pages.getOrPut(body) { LinkedHashMap() }.putIfAbsent(page, Occurrence(page.first, page.second))
            } else if (!e.from.startsWith("ledger://")) {
                objects.getOrPut(body) { LinkedHashMap() }
                    .putIfAbsent(e.from, e.from to e.fromLabel.ifBlank { com.toolsboox.ot.LedgerUri.describe(e.from) })
            }
        }
        val removed = dead.mapValues { (body, gone) -> gone - live[body].orEmpty() }.filterValues { it.isNotEmpty() }
        return EdgeLinks(names, pages.mapValues { it.value.values.toList() }, objects.mapValues { it.value.values.toList() }, removed)
    }

    /** [deriveFromEdges] over the live graph, memoized on [ConnectionStore.generation] — the Links
     *  index asks per target per redraw, and the walk is over every edge. */
    @Volatile private var edgeMemo: Pair<Long, EdgeLinks>? = null

    private fun edgeView(context: Context): EdgeLinks? {
        val gen = ConnectionStore.generation
        edgeMemo?.let { if (it.first == gen) return it.second }
        val view = runCatching { deriveFromEdges(ConnectionStore.snapshot(context)) }.getOrNull() ?: return null
        edgeMemo = gen to view
        return view
    }

    /**
     * Whether anything in the ledger is actually NAMED [target] — i.e. whether this link lands.
     *
     * An unresolved link is not an error and must not be presented as one. In a ledger you write by
     * hand, linking to something you have not written yet is a perfectly ordinary act: it is a note
     * to yourself that the thing should exist. The UI's job is to show the difference (a link that
     * lands vs one that doesn't) and offer to make the missing one, not to complain.
     */
    fun resolves(target: String, names: List<String>): Boolean {
        val n = normalise(target)
        return names.any { normalise(it) == n }
    }
}
