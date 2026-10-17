package com.toolsboox

import com.toolsboox.plugin.calendar.ot.AddToPickGolden
import com.toolsboox.plugin.calendar.ot.PickFaceLayout
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.FakeMeasure
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Input
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Kind
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Piece
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FACE (add-to-pick pin §4, Michael 2026-10-12: "Let the photo/video/note show on the pick.").
 * The twin of iOS PickFaceLayoutTests: the same rules over the same cases, and both hold the
 * rendered golden equal to the byte-identical add-to-pick.golden.
 */
class PickFaceLayoutTest {

    private fun lay(shape: Shape, passage: Int = 150, note: Int = 0, ink: Piece? = null,
                    visuals: List<Piece> = emptyList(), voices: List<Piece> = emptyList()) =
        PickFaceLayout.layout(Input(shape, AddToPickGolden.prose(passage),
            if (note > 0) AddToPickGolden.prose(note) else "", ink, visuals, voices,
            source = AddToPickGolden.SOURCE), FakeMeasure)

    private val photo = Piece(Kind.PHOTO, "p1", 4032, 3024)
    private val video = Piece(Kind.VIDEO, "v1", 1920, 1080, 41)

    @Test
    fun `the ladders are the pin's, int for int`() {
        assertEquals(listOf(60, 56, 53, 50, 47, 44, 41, 39, 37, 35, 33, 31, 30), PickFaceLayout.ladder(60, 30))
        assertEquals(listOf(48, 45, 42, 39, 37, 35, 33, 31, 30), PickFaceLayout.ladder(48, 30))
        assertEquals(listOf(40, 38, 36, 34, 32, 30, 28, 26, 24, 23, 22, 21, 20), PickFaceLayout.ladder(40, 20))
        assertEquals(listOf(32, 30, 28, 26, 24, 23, 22, 21, 20), PickFaceLayout.ladder(32, 20))
        assertEquals(listOf(40, 38, 36, 34, 32, 30), PickFaceLayout.ladder(40, 30))
        assertEquals(listOf(27, 25, 23, 22, 21, 20), PickFaceLayout.ladder(27, 20))
    }

    @Test
    fun `the fake measurer is the pin's`() {
        // charW = (60+1)/2 = 30 · 150 chars → 4500/888 → 6 lines · (75 + 12).
        assertEquals(6 * (75 + 12), FakeMeasure.height(AddToPickGolden.prose(150), 60, PickFaceLayout.Font.SERIF, 888, 12))
        assertEquals(6 * 16, FakeMeasure.width("🎤 0:23", 32, PickFaceLayout.Font.SANS_BOLD))
        assertEquals(0, FakeMeasure.height("", 60, PickFaceLayout.Font.SERIF, 888, 12))
    }

    @Test
    fun `the big mark is drawn in every version - the look never changes when something is added`() {
        for (shape in Shape.entries) {
            val bare = lay(shape)
            val dressed = lay(shape, note = 80, ink = Piece(Kind.INK, "i", 900, 300), visuals = listOf(photo),
                voices = listOf(Piece(Kind.VOICE, "a", seconds = 23)))
            assertEquals(shape.key, bare.markSize, dressed.markSize)
            assertEquals(shape.key, bare.markBaseline, dressed.markBaseline)
            assertEquals(shape.key, bare.passage.box.y, dressed.passage.box.y)
            assertEquals(shape.key, bare.passage.box.x, dressed.passage.box.x)
        }
    }

    @Test
    fun `fixed shapes never change size because of an add`() {
        for (shape in Shape.entries - Shape.FREE) for (c in AddToPickGolden.CASES) {
            val l = PickFaceLayout.layout(AddToPickGolden.input(shape, c), FakeMeasure)
            assertEquals("${shape.key}·${c.name}", shape.w, l.w)
            assertEquals("${shape.key}·${c.name}", shape.h, l.h)
        }
    }

    @Test
    fun `free is as tall as it needs, 560 to 2600`() {
        assertEquals(560, lay(Shape.FREE, passage = 0).h)
        val dressed = lay(Shape.FREE, visuals = listOf(photo))
        assertTrue(dressed.h > lay(Shape.FREE).h)
        assertTrue(lay(Shape.FREE, passage = 4000).h <= 2600)
    }

    @Test
    fun `words stack down, the band and chips up from the footer`() {
        val l = lay(Shape.STORY, visuals = listOf(photo), voices = listOf(Piece(Kind.VOICE, "a", seconds = 23)))
        val footerTop = l.h - l.pad - 120
        val chip = l.chips.single()
        assertEquals(footerTop - 24, chip.box.bottom)
        assertEquals(chip.box.y - 16, l.tiles.single().box.bottom)
        assertTrue(l.passage.box.bottom < l.tiles.single().box.y - 36)
    }

    @Test
    fun `two pictures share the band, 432 each, and more than two wear +N`() {
        val l = lay(Shape.PORTRAIT, visuals = listOf(photo, video, Piece(Kind.PHOTO, "p3", 800, 600)))
        assertEquals(2, l.tiles.size)
        assertEquals(432, l.tiles[0].box.w); assertEquals(432, l.tiles[1].box.w)
        assertEquals(l.pad + 432 + 24, l.tiles[1].box.x)
        assertEquals("+1", l.tiles[1].moreText)
        assertNotNull(l.tiles[1].disc)
        assertEquals("0:41", l.tiles[1].pillText)
    }

    @Test
    fun `landscape puts the visual in the right column and the words keep theirs`() {
        val l = lay(Shape.LANDSCAPE, visuals = listOf(photo))
        assertEquals(PickFaceLayout.Box(768, 0, 512, 720), l.tiles.single().box)
        assertEquals(640, l.textWidth)
        assertEquals(1152, lay(Shape.LANDSCAPE).textWidth)
        val two = lay(Shape.LANDSCAPE, visuals = listOf(photo, video))
        assertEquals(PickFaceLayout.Box(768, 364, 512, 356), two.tiles[1].box)
        assertEquals(two.h - 64, two.footer.sourceBaseline)
        assertEquals(null, two.footer.divider)
    }

    @Test
    fun `the ladder folds the band into a chip before it cuts the note`() {
        val l = PickFaceLayout.layout(AddToPickGolden.input(Shape.SQUARE, AddToPickGolden.CASES.last()), FakeMeasure)
        assertEquals(5, l.step)
        assertTrue(l.tiles.isEmpty())
        assertEquals("📷", l.chips.first().label)
        assertTrue(l.slots.any { it.kind == Kind.PHOTO && it.id == "p1" })
        assertFalse(l.note!!.truncated)
    }

    @Test
    fun `adds never cut the passage - a passage alone past its floor grows the card`() {
        val l = lay(Shape.SQUARE, passage = 2400, visuals = listOf(photo))
        assertTrue(l.h > 1080)
        assertEquals(30, l.passage.size)
    }

    @Test
    fun `every slot of a laid-out face is inside it`() {
        for (shape in Shape.entries) for (c in AddToPickGolden.CASES) {
            val l = PickFaceLayout.layout(AddToPickGolden.input(shape, c), FakeMeasure)
            for (s in l.slots) {
                val r = PickFaceLayout.perMille(s.box, l.w, l.h)
                assertTrue("${shape.key}·${c.name} ${s.kind}", r.all { it in 0..1000 })
            }
        }
    }

    @Test
    fun `the rendered golden equals the golden file`() {
        val rendered = AddToPickGolden.text()
        runCatching { java.io.File("build/add-to-pick.actual").writeText(rendered) }
        val golden = javaClass.getResourceAsStream("/add-to-pick.golden")?.bufferedReader()?.readText()
        assertNotNull("add-to-pick.golden missing from test resources", golden)
        assertEquals(golden, rendered)
    }
}
