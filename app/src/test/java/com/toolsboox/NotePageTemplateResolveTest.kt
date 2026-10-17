package com.toolsboox

import com.toolsboox.plugin.calendar.ot.NotePageTemplates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolution order of a note page's template, pinned ([NotePageTemplates.resolve]):
 *
 *   1. the page's explicit per-page choice, when it is a known id;
 *   2. else the key family's implied sheet — write→lines, grid→grid,
 *      sketch/synthesize/grampicks→dots, pickings→pickings;
 *   3. else, for numeric pages, the global "calendarNoteTemplate" seed (0→lines, 1→grid);
 *   4. else lines.
 *
 * Pure on purpose, so the order that the ▤ row, the picker's current mark and the renderer all
 * share can be tested without a device.
 */
class NotePageTemplateResolveTest {

    @Test
    fun `an explicit choice wins over the key family`() {
        assertEquals(NotePageTemplates.DOTS, NotePageTemplates.resolve(NotePageTemplates.DOTS, "write", 0))
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(NotePageTemplates.LINES, "grid#2", 1))
        assertEquals(NotePageTemplates.PICKINGS, NotePageTemplates.resolve(NotePageTemplates.PICKINGS, "3", 0))
        assertEquals(NotePageTemplates.GRID, NotePageTemplates.resolve(NotePageTemplates.GRID, "sketch-1753000000000", 0))
    }

    @Test
    fun `an unknown explicit value falls through to the family — additive sidecar, older vocabulary`() {
        // A future build may record ids this one doesn't know; they must read as "no choice",
        // never as a crash or a wrong family.
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve("selfexec", "write", 0))
        assertEquals(NotePageTemplates.GRID, NotePageTemplates.resolve("banana", "grid", 0))
    }

    @Test
    fun `the key family implies its own sheet, sub-pages and minted keys included`() {
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(null, "write", 1))
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(null, "write-1753000000000#2", 1))
        assertEquals(NotePageTemplates.GRID, NotePageTemplates.resolve(null, "grid", 0))
        assertEquals(NotePageTemplates.GRID, NotePageTemplates.resolve(null, "grid-1753000000000", 0))
        assertEquals(NotePageTemplates.DOTS, NotePageTemplates.resolve(null, "sketch", 0))
        assertEquals(NotePageTemplates.DOTS, NotePageTemplates.resolve(null, "sketch#3", 0))
        assertEquals(NotePageTemplates.DOTS, NotePageTemplates.resolve(null, "synthesize-1753000000000", 0))
        assertEquals(NotePageTemplates.DOTS, NotePageTemplates.resolve(null, "grampicks", 0))
        // The standing daily Doodle wears dots by family (DESIGN-homepages-2026-08-22).
        assertEquals(NotePageTemplates.DOTS, NotePageTemplates.resolve(null, "doodle", 0))
        assertEquals(NotePageTemplates.PICKINGS, NotePageTemplates.resolve(null, "pickings", 0))
        assertEquals(NotePageTemplates.PICKINGS, NotePageTemplates.resolve(null, "pickings-1753000000000", 0))
    }

    @Test
    fun `numeric pages seed from the global pref`() {
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(null, "0", 0))
        assertEquals(NotePageTemplates.GRID, NotePageTemplates.resolve(null, "7", 1))
        // An out-of-range pref reads as the default, exactly as the old 2-value int did.
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(null, "0", 9))
    }

    @Test
    fun `everything else is lines`() {
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(null, null, 1))
        assertEquals(NotePageTemplates.LINES, NotePageTemplates.resolve(null, "somekey", 1))
    }

    @Test
    fun `the picker's offer, in order — Self Executive is demoted and absent`() {
        // The list IS the picker's order, and it is pinned on both forks against the same literals:
        // a page can be re-sheeted on either device and must find the same vocabulary waiting.
        // Gratitude sits LAST — the four plain sheets are what a page usually wants, and a prompted
        // sheet belongs at the end of the list rather than in the middle of the paper.
        assertEquals(
            listOf(
                NotePageTemplates.LINES,
                NotePageTemplates.GRID,
                NotePageTemplates.DOTS,
                NotePageTemplates.PICKINGS,
                NotePageTemplates.GRATITUDE,
            ),
            NotePageTemplates.IDS
        )
        assertEquals(
            listOf("Lines", "Grid", "Dots", "Pickings", "Gratitude"),
            NotePageTemplates.IDS.map { NotePageTemplates.label(it) }
        )
        // Every offered id has its own glyph — a picker row with a duplicated mark is unreadable.
        assertEquals(NotePageTemplates.IDS.size, NotePageTemplates.IDS.map { NotePageTemplates.glyph(it) }.toSet().size)
        // Gratitude is a TEMPLATE now, so resolve must honour it as an explicit choice; the station
        // key it shares its name with is a different thing and keeps its own door.
        assertEquals(
            NotePageTemplates.GRATITUDE,
            NotePageTemplates.resolve(NotePageTemplates.GRATITUDE, "0", 0)
        )
    }

    // AN IMPORTED SHEET IS A VALID CHOICE (2026-08-24, "sort how users import templates"):
    // "custom:*" passes through resolution — the bytes are checked at draw time, and a device
    // without them falls to the family default there, never here.
    @Test
    fun `a custom template id passes through`() {
        assertEquals("custom:my-stationery", NotePageTemplates.resolve("custom:my-stationery", "write", 0))
        assertEquals("custom:x", NotePageTemplates.resolve("custom:x", "grid#2", 1))
    }
}
