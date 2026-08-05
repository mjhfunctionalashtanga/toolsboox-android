package com.toolsboox

import com.toolsboox.plugin.calendar.ot.NotesMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The mirror's file format — the part that decides whether a round-trip is safe.
 *
 * The stakes are asymmetric and worth naming. A failure to WRITE the mirror loses a copy, which is
 * annoying. A failure to PARSE it loses whatever Michael typed in Obsidian, because the next save
 * would overwrite the file with the device's older copy. So front matter has to survive being
 * written and read by the same code, and a file that is NOT ours has to be recognised as not ours
 * rather than half-understood.
 */
class NotesMirrorTest {

    private val date = LocalDate.of(2026, 8, 5)

    @Test
    fun `front matter round-trips`() {
        val text = NotesMirror.frontMatter(date, "abc123", 1754400000000L) + "The body.\n\nMore."
        val (head, body) = NotesMirror.parse(text)
        assertEquals("2026-08-05", head["date"])
        assertEquals("abc123", head["ledger-id"])
        assertEquals("1754400000000", head["updated"])
        assertEquals("The body.\n\nMore.", body)
    }

    @Test
    fun `a file with no front matter is all body`() {
        // Someone drops a plain .md into the folder. It is not ours, and treating a first line as
        // metadata would silently eat it.
        val (head, body) = NotesMirror.parse("# Just a heading\n\nSome text.")
        assertTrue(head.isEmpty())
        assertEquals("# Just a heading\n\nSome text.", body)
    }

    @Test
    fun `an unterminated front matter block is not treated as metadata`() {
        val text = "---\ndate: 2026-08-05\nnever closed"
        val (head, body) = NotesMirror.parse(text)
        assertTrue(head.isEmpty())
        assertEquals(text, body)
    }

    @Test
    fun `a body containing a horizontal rule survives`() {
        // "---" is ordinary Markdown. The parser must stop at the FIRST closing fence and hand the
        // rest over untouched, or every note with a section break loses its tail.
        val text = NotesMirror.frontMatter(date, "id1", 1L) + "Above.\n\n---\n\nBelow."
        val (_, body) = NotesMirror.parse(text)
        assertEquals("Above.\n\n---\n\nBelow.", body)
    }

    @Test
    fun `filenames are filesystem-safe and carry the id`() {
        val name = NotesMirror.filename("What/Now: a note?", "xyz")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('?'))
        assertTrue(name.contains("xyz"))
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun `a blank title still yields a usable filename`() {
        val name = NotesMirror.filename("   ", "id9")
        assertTrue(name.startsWith("Note"))
        assertTrue(name.contains("id9"))
    }

    @Test
    fun `the id makes two same-titled notes two files`() {
        // Without this they would share a filename and each save would clobber the other — the
        // quiet data loss this design most needs to avoid.
        assertFalse(NotesMirror.filename("Monday", "a") == NotesMirror.filename("Monday", "b"))
    }

    @Test
    fun `a very long title is truncated but still ends in md`() {
        val name = NotesMirror.filename("x".repeat(400), "id")
        assertTrue(name.length < 140)
        assertTrue(name.endsWith(".md"))
    }
}
