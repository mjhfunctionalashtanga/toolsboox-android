package com.toolsboox

import com.toolsboox.plugin.calendar.ot.PickHarvest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pick Harvest's parser.
 *
 * The counts are Michael's ("three notes and up to three quotes") and they are a CEILING, not a
 * target — a piece with one quote worth keeping should yield one, and a model that pads to three
 * has produced two cards that will sit next to his own words claiming to be from the source.
 *
 * That is the whole reason this is tested rather than trusted: harvested cards end up arranged
 * beside things he wrote by hand, and months later a page does not remember which was which.
 */
class PickHarvestTest {

    private val good = """
        NOTES
        - The argument turns on a distinction the author never states.
        - His examples are all from one decade, which he doesn't mention.
        - The counterargument in section 4 is stronger than the thesis.

        QUOTES
        - "Practice is what remains when motivation has gone."
        - "We measured the easy thing because it was easy."
    """.trimIndent()

    @Test
    fun `notes and quotes are pulled into their own lists`() {
        val h = PickHarvest.parse(good)
        assertEquals(3, h.notes.size)
        assertEquals(2, h.quotes.size)
        assertTrue(h.notes[0].startsWith("The argument turns"))
        assertEquals("Practice is what remains when motivation has gone.", h.quotes[0])
    }

    @Test
    fun `surrounding quotation marks are stripped so the card is not double-quoted`() {
        // The card renders its own ❝ mark; a literal pair of quotes inside it reads as a typo.
        val h = PickHarvest.parse("QUOTES\n- “Curly quotes too.”")
        assertEquals("Curly quotes too.", h.quotes.single())
    }

    @Test
    fun `a lowercase or colon-suffixed heading still counts`() {
        // A model that wrote "Notes:" has done what was asked; failing on the spelling of a
        // heading would throw away a correct harvest.
        val h = PickHarvest.parse("Notes:\n- one\nQuotes:\n- two")
        assertEquals(listOf("one"), h.notes)
        assertEquals(listOf("two"), h.quotes)
    }

    @Test
    fun `no quotes at all is a valid harvest`() {
        // "If nothing in it is worth quoting exactly, return no quotes" — a real answer about a
        // piece of writing, and the parser must not treat it as a failure.
        val h = PickHarvest.parse("NOTES\n- Only observations here.")
        assertEquals(1, h.notes.size)
        assertTrue(h.quotes.isEmpty())
    }

    @Test
    fun `the counts are a ceiling`() {
        val many = "NOTES\n" + (1..6).joinToString("\n") { "- note $it" } +
            "\nQUOTES\n" + (1..6).joinToString("\n") { "- quote $it" }
        val h = PickHarvest.parse(many)
        assertEquals(3, h.notes.size)
        assertEquals(3, h.quotes.size)
    }

    @Test
    fun `prose outside the lists is ignored rather than harvested`() {
        // Models like to introduce themselves. A preamble is not a note, and a card reading
        // "Here is what I found:" would be worse than no card.
        val h = PickHarvest.parse(
            "Here's what I found in the piece:\n\nNOTES\n- a real one\n\nHope that helps!"
        )
        assertEquals(listOf("a real one"), h.notes)
    }

    @Test
    fun `bullets before any heading are dropped, not guessed at`() {
        // Without a heading there is no way to know whether a line is an observation or a
        // quotation — and guessing wrong turns a paraphrase into a quotation, which is the one
        // failure this feature must not have.
        val h = PickHarvest.parse("- orphan line\nNOTES\n- kept")
        assertEquals(listOf("kept"), h.notes)
        assertTrue(h.quotes.isEmpty())
    }
}
