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
    val citation: String,
    /**
     * The reader's OWN words about this — a highlight's note, not the passage.
     *
     * Kept apart from [text] because the two are not the same evidence. The passage is someone
     * else's writing that happened to be worth marking; this is why it was marked. The star
     * pipeline on mjh.yoga makes the same distinction and calls it the lens.
     */
    val own: String = ""
)

/**
 * The parts of the Ledger a question can be scoped to. The chat UI offers "everything"
 * or any subset ("or use picks").
 */
enum class Section(val label: String) {
    BOOKS("Book highlights"),
    ARTICLES("Feed annotations"),
    FEED("Feed articles"),
    PLANNER("Planner text"),
    MEDIA("A/V grams"),
    SECTIONS("Page sections (OCR)"),
    NOTES("Text notes"),
    ANNOTATIONS("Handwritten notes"),
    TASKS("Tasks & events");

    companion object {
        val ALL: Set<Section> get() = values().toSet()
    }
}
