package com.toolsboox.ot

/**
 * Shared HTML → plain-text conversion for the Ledger's e-ink surfaces.
 *
 * One implementation consolidating the handful of near-identical strippers that had
 * accumulated across the mail, calendar, feeds and chat plugins. [toPlain] drops
 * `<script>`/`<style>` blocks (case-insensitive, spanning newlines), strips the
 * remaining tags, decodes the common named/numeric entities those call sites handled,
 * and collapses all whitespace to single spaces, trimmed.
 *
 * Deliberately regex-based (no HTML parser, no new dependency). Call sites keep their
 * own post-processing (e.g. `.take(180)`) by passing the [toPlain] result through it.
 */
object HtmlText {

    private val scriptStyle = Regex(
        "<(script|style)[^>]*>.*?</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val tags = Regex("<[^>]+>")
    private val whitespace = Regex("\\s+")

    /**
     * The union of the entities the individual copies decoded. `&amp;` is first so that
     * doubly-encoded input decodes the way the originals did (they applied it before the
     * others). Numeric forms sit beside their named equivalents.
     */
    private val entities = listOf(
        "&amp;" to "&", "&#038;" to "&",
        "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&#039;" to "'",
        "&nbsp;" to " ",
        "&rsquo;" to "’", "&#8217;" to "’",
        "&lsquo;" to "‘", "&#8216;" to "‘",
        "&ldquo;" to "“", "&#8220;" to "“",
        "&rdquo;" to "”", "&#8221;" to "”",
        "&mdash;" to "—", "&#8212;" to "—",
        "&ndash;" to "–", "&#8211;" to "–",
        "&hellip;" to "…", "&#8230;" to "…"
    )

    /**
     * Convert an HTML fragment to a single line of plain text: `<script>`/`<style>`
     * blocks removed, remaining tags removed, the common entities decoded, and all
     * whitespace collapsed to single spaces and trimmed.
     */
    fun toPlain(html: String): String {
        var s = scriptStyle.replace(html, " ")
        s = tags.replace(s, " ")
        for ((a, b) in entities) s = s.replace(a, b)
        return whitespace.replace(s, " ").trim()
    }
}
