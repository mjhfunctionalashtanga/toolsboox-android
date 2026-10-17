package com.toolsboox

import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.NotePageCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * The export door's copy engine, pinned (DESIGN-notes-ia-2026-08-22, verb #1: "Add to a new note
 * — export and transplant are the same door"). The contract under test is the one the merge
 * depends on: copies are ADDITIVE (source keys, strokes and elements untouched), land under the
 * new note's keys in selection order (base, then "#n"), and carry FRESH stroke/element ids —
 * a shared id on two pages of one day file is what the strokeId-only union merge cannot survive.
 */
class NotePageCopyTest {

    private fun stroke(color: Int = -16777216, width: Float = 3.0f, style: Int = Stroke.STYLE_CALLIGRAPHY) =
        Stroke(
            UUID.randomUUID(), 1L,
            listOf(StrokePoint(10f, 20f, 0.5f, 1L), StrokePoint(30f, 40f, 0.7f, 2L)),
            color, width, style
        )

    private fun day(): CalendarDay = CalendarDay(
        2026, 8, 22, Locale.US, mutableListOf(), mutableListOf(), false, 7
    )

    @Test
    fun `pages land under the new base in selection order`() {
        val d = day()
        d.noteStrokes["0"] = listOf(stroke())
        d.noteStrokes["write"] = listOf(stroke())
        val dest = NotePageCopy.copyInto(d, listOf("0", "write"), "write-1755900000000")
        assertEquals(listOf("write-1755900000000", "write-1755900000000#1"), dest)
        assertEquals(1, d.noteStrokes["write-1755900000000"]?.size)
        assertEquals(1, d.noteStrokes["write-1755900000000#1"]?.size)
    }

    @Test
    fun `strokes remint identity and keep geometry and style — the stampAt rule`() {
        val d = day()
        val src = stroke(color = -65536, width = 5.5f, style = Stroke.STYLE_MARKER)
        d.noteStrokes["0"] = listOf(src)
        NotePageCopy.copyInto(d, listOf("0"), "write-1755900000000")
        val copy = d.noteStrokes["write-1755900000000"]!!.single()
        assertNotEquals("a copy on the same day file must not share a strokeId", src.strokeId, copy.strokeId)
        assertEquals(src.strokePoints.map { it.x to it.y }, copy.strokePoints.map { it.x to it.y })
        assertEquals(src.color, copy.color)
        assertEquals(src.strokeWidth, copy.strokeWidth)
        assertEquals(src.inkStyle, copy.inkStyle)
    }

    @Test
    fun `elements remint ids and retag their page, keeping lineage`() {
        val d = day()
        d.noteStrokes["pickings"] = emptyList()
        val img = ImageElement(
            x = 1f, y = 2f, width = 3f, height = 4f, data = "abc",
            page = "pickings", gramId = "lineage-1", attachmentId = "att-1"
        )
        val txt = TextElement(x = 5f, y = 6f, text = "hello", pageKey = "pickings")
        d.imageElements.add(img)
        d.textElements.add(txt)
        NotePageCopy.copyInto(d, listOf("pickings"), "pickings-1755900000000")
        val imgCopy = d.imageElements.single { it.page == "pickings-1755900000000" }
        val txtCopy = d.textElements.single { it.pageKey == "pickings-1755900000000" }
        // Fresh identity: the day's element lists are FLAT and union by elementId, so a shared id
        // would be one element on two pages — the union keeps one and loses the other.
        assertNotEquals(img.elementId, imgCopy.elementId)
        assertNotEquals(txt.elementId, txtCopy.elementId)
        // Lineage and the shared attachment ride along: "where used" groups the copy with its
        // original, and a duplicated A/V gram shares one attachment by design.
        assertEquals("lineage-1", imgCopy.gramId)
        assertEquals("att-1", imgCopy.attachmentId)
        assertEquals("hello", txtCopy.text)
    }

    @Test
    fun `the copy is additive — sources keep their keys, ids and counts`() {
        val d = day()
        val src = stroke()
        d.noteStrokes["0"] = listOf(src)
        val img = ImageElement(x = 1f, y = 2f, width = 3f, height = 4f, data = "abc", page = "0")
        d.imageElements.add(img)
        NotePageCopy.copyInto(d, listOf("0"), "sketch-1755900000000")
        assertEquals(src.strokeId, d.noteStrokes["0"]!!.single().strokeId)
        assertTrue(d.imageElements.any { it.elementId == img.elementId && it.page == "0" })
        assertEquals(2, d.imageElements.size)
        assertEquals(setOf("0", "sketch-1755900000000"), d.noteStrokes.keys)
    }

    @Test
    fun `a blank source page still registers its destination key`() {
        // Merely visiting a page writes its key, and the new note's pager counts pages off the
        // keys — a three-page selection must arrive as a three-page note, blank middle included.
        val d = day()
        d.noteStrokes["0"] = listOf(stroke())
        d.noteStrokes["2"] = listOf(stroke())
        val dest = NotePageCopy.copyInto(d, listOf("0", "1", "2"), "write-1755900000000")
        assertEquals(3, dest.size)
        assertEquals(emptyList<Stroke>(), d.noteStrokes["write-1755900000000#1"])
    }
}
