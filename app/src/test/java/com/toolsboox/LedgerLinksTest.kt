package com.toolsboox

import com.toolsboox.plugin.calendar.ot.LedgerLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link extractor, which runs over the output of HANDWRITING RECOGNITION.
 *
 * That is the whole reason these tests are strict. A false tag is noise in an index and costs a
 * scroll; a false link is an edge in the rhizome, which means it is a line on the Map and a claim
 * that two of Michael's ideas belong together. The extractor has to be harder to fool than the
 * OCR is to confuse — a lone hallucinated bracket must not mint a relationship.
 */
class LedgerLinksTest {

    private fun targets(s: String) = LedgerLinks.extract(s).map { it.target }

    @Test
    fun `a plain double-bracket link is found`() {
        assertEquals(listOf("Collider"), targets("see [[Collider]] for the argument"))
    }

    @Test
    fun `the pipe form keeps target and label apart`() {
        val link = LedgerLinks.extract("as in [[project_named_notes|the naming work]]").single()
        assertEquals("project_named_notes", link.target)
        assertEquals("the naming work", link.label)
        assertEquals("the naming work", link.shown)
    }

    @Test
    fun `shown falls back to the target when there is no label`() {
        assertEquals("Collider", LedgerLinks.extract("[[Collider]]").single().shown)
    }

    @Test
    fun `single brackets are not links`() {
        // The commonest OCR confusion, and the one that would flood the graph: ordinary prose
        // brackets, and Markdown's own link syntax, must both stay inert.
        assertTrue(targets("a [note] in brackets").isEmpty())
        assertTrue(targets("[label](https://example.com)").isEmpty())
    }

    @Test
    fun `brackets with no letter inside cannot mint a link`() {
        // A stray mark recognised as brackets around a digit, a dash or nothing at all.
        assertTrue(targets("[[]]").isEmpty())
        assertTrue(targets("[[ ]]").isEmpty())
        assertTrue(targets("[[42]]").isEmpty())
        assertTrue(targets("[[--]]").isEmpty())
    }

    @Test
    fun `code is not linked`() {
        assertTrue(targets("`arr[[0]]`").isEmpty())
        assertTrue(targets("```\n[[not a link]]\n```").isEmpty())
    }

    @Test
    fun `the same target written twice on a page counts once`() {
        assertEquals(listOf("Collider"), targets("[[Collider]] and again [[collider]]"))
    }

    @Test
    fun `normalise folds case and collapses whitespace`() {
        assertEquals("the collider", LedgerLinks.normalise("  The   Collider "))
        // The failure this prevents: a link written by hand on Tuesday missing the note named on
        // Monday by a capital letter, and silently creating a second empty destination.
        assertEquals(LedgerLinks.normalise("Oxford Summer"), LedgerLinks.normalise("oxford  summer"))
    }

    @Test
    fun `resolution is by normalised name`() {
        val names = listOf("Oxford Summer Series", "Collider")
        assertTrue(LedgerLinks.resolves("collider", names))
        assertTrue(LedgerLinks.resolves("  Oxford   Summer Series ", names))
        assertFalse(LedgerLinks.resolves("Rooms", names))
    }

    @Test
    fun `an unresolved link is still extracted`() {
        // Linking to something not yet written is an ordinary act in a handwritten ledger — a note
        // to yourself that the thing should exist. Extraction must not depend on the target being
        // real, or you could never link forward.
        assertEquals(listOf("A Note I Have Not Written"), targets("[[A Note I Have Not Written]]"))
    }

    @Test
    fun `multiple links on one line all come through, in order`() {
        assertEquals(
            listOf("One", "Two", "Three"),
            targets("[[One]] then [[Two]] and finally [[Three]].")
        )
    }

    @Test
    fun `a target may contain spaces and punctuation but not brackets`() {
        assertEquals(listOf("Rooms: a memoir"), targets("[[Rooms: a memoir]]"))
        assertTrue(targets("[[a[b]]").isEmpty())
    }
}
