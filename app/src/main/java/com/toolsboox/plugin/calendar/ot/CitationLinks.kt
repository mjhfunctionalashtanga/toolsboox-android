package com.toolsboox.plugin.calendar.ot

import java.time.LocalDate

/**
 * Turning a cited answer into doors.
 *
 * Everything the corpus hands the model is cited as `yyyy-MM-dd · kind · label`, so every claim in
 * an answer already carries the date it came from. This finds those, so the citation can be tapped
 * and the thing itself looked at — the difference between the machine saying you wrote something
 * and being able to go and read it.
 *
 * Kept out of the fragment so it can be tested: a regex over model output is exactly the sort of
 * thing that looks right and quietly matches the wrong span.
 */
object CitationLinks {

    /** One citation found in an answer: where it sits, and where tapping it should go. */
    data class Link(val start: Int, val endExclusive: Int, val date: LocalDate, val notePage: String?)

    // yyyy-MM-dd, optionally followed by " · kind". The kind is matched loosely because the model
    // renders citations however it likes; insisting on our exact format would mean linking nothing
    // the moment it decided to reformat.
    private val RE = Regex("""(\d{4}-\d{2}-\d{2})(\s*·\s*([\p{L}/ ]{1,16}))?""")

    fun find(text: String): List<Link> = RE.findAll(text).mapNotNull { m ->
        val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
        Link(m.range.first, m.range.last + 1, date, notePageFor(m.groupValues[3].trim().lowercase()))
    }.toList()

    /**
     * Which page on that day to open, or null for the day sheet itself.
     *
     * Only kinds that genuinely live on their own page get one; everything else belongs to the day,
     * which is where a highlight, a task or a recording is actually found.
     */
    private fun notePageFor(kind: String): String? = when {
        kind.startsWith("picking") -> "pickings"
        kind.startsWith("gratitude") -> "gratitude"
        else -> null
    }
}
