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
        target.trim().lowercase().replace(Regex("""\s+"""), " ")

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
    fun record(context: Context, date: LocalDate, pageKey: String, text: String) {
        val links = extract(text)
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

    /** Every page that links TO [target] — the backlinks, newest first. */
    fun backlinks(context: Context, target: String): List<Occurrence> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet("link:${normalise(target)}:from", emptySet())!!
            .mapNotNull { raw ->
                val parts = raw.split("|")
                if (parts.size < 2) return@mapNotNull null
                runCatching { Occurrence(LocalDate.parse(parts[0]), parts[1]) }.getOrNull()
            }
            .sortedByDescending { it.date }
    }

    /** Where a link was written. */
    data class Occurrence(val date: LocalDate, val pageKey: String)

    /** Every target that has ever been linked, prettiest-spelling first, alphabetical. */
    fun all(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys
            .filter { it.startsWith("link:") && it.endsWith(":name") }
            .mapNotNull { prefs.getString(it, null) }
            .distinctBy { normalise(it) }
            .sortedBy { it.lowercase() }
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
