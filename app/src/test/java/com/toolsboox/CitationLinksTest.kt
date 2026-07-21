package com.toolsboox

import com.toolsboox.plugin.calendar.ot.CitationLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A regex over model output is exactly the sort of thing that looks right and quietly links the
 * wrong span — or stops linking at all the first time the model reformats a citation.
 */
class CitationLinksTest {

    @Test
    fun it_finds_a_citation_in_our_own_format() {
        val text = "You wrote about this on [2026-07-13 · book · Light on Yoga] and again later."

        val links = CitationLinks.find(text)

        assertEquals(1, links.size)
        assertEquals(LocalDate.of(2026, 7, 13), links[0].date)
        assertEquals("the span must cover the date and kind, not the whole bracket",
            "2026-07-13 · book", text.substring(links[0].start, links[0].endExclusive).trim())
    }

    @Test
    fun a_bare_date_still_links() {
        // The model often drops our format and just writes the date. Linking nothing in that case
        // would make the feature vanish exactly when it reformats.
        val links = CitationLinks.find("Back on 2026-03-01 you said the opposite.")

        assertEquals(1, links.size)
        assertEquals(LocalDate.of(2026, 3, 1), links[0].date)
        assertNull("no kind means the day sheet", links[0].notePage)
    }

    @Test
    fun a_pickings_citation_opens_the_pickings_page() {
        val links = CitationLinks.find("[2026-05-02 · pickings · a quote you kept]")

        assertEquals("pickings", links.single().notePage)
    }

    @Test
    fun other_kinds_go_to_the_day_itself() {
        for (kind in listOf("book", "article", "note", "task", "event", "a/v", "feed")) {
            val links = CitationLinks.find("[2026-05-02 · $kind · whatever]")
            assertEquals("$kind should open the day", 1, links.size)
            assertNull("$kind lives on the day, not its own page", links.single().notePage)
        }
    }

    @Test
    fun several_citations_in_one_answer_are_all_found_and_do_not_overlap() {
        val text = "First [2026-01-02 · note · a] then [2026-01-09 · book · b] and [2026-02-01 · task · c]."

        val links = CitationLinks.find(text)

        assertEquals(3, links.size)
        assertEquals(
            listOf(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 9), LocalDate.of(2026, 2, 1)),
            links.map { it.date }
        )
        for (i in 0 until links.size - 1) {
            assertTrue("spans must not overlap", links[i].endExclusive <= links[i + 1].start)
        }
    }

    @Test
    fun an_impossible_date_is_not_linked() {
        // A month 13 parses as digits but is not a day you can open.
        assertTrue(CitationLinks.find("see 2026-13-45 for details").isEmpty())
    }

    @Test
    fun prose_with_no_dates_yields_nothing() {
        assertTrue(CitationLinks.find("There is nothing in your ledger about that.").isEmpty())
    }

    @Test
    fun a_number_that_merely_looks_datelike_is_left_alone() {
        // Version strings and ranges shouldn't become doors.
        assertTrue(CitationLinks.find("build 1.06.05-02 shipped").isEmpty())
    }
}
