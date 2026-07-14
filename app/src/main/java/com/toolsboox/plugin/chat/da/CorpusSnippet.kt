package com.toolsboox.plugin.chat.da

import java.util.Date

/**
 * One retrievable piece of the Ledger corpus — a book highlight, a starred/annotated
 * article, a planner text box, or an A/V gram reference — flattened to a common shape
 * with a human-readable citation so "Ask my Ledger" can quote its sources.
 */
data class CorpusSnippet(
    val section: Section,
    /** When it happened (highlight/star time, or the day the text box lives on). */
    val date: Date,
    /** Book/article/day title. */
    val title: String,
    /** Author (book), feed (article), or page (planner) — may be blank. */
    val source: String,
    /** The searchable/quotable content. */
    val text: String,
    /** Where it came from, for the citation line, e.g. "2026-07-13 · book". */
    val citation: String
)

/**
 * The parts of the Ledger a question can be scoped to. The chat UI offers "everything"
 * or any subset ("or use picks").
 */
enum class Section(val label: String) {
    BOOKS("Book highlights"),
    ARTICLES("Feed annotations"),
    PLANNER("Planner text"),
    MEDIA("A/V grams");

    companion object {
        val ALL: Set<Section> get() = values().toSet()
    }
}
