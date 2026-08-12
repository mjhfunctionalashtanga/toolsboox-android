package com.toolsboox

import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.ot.StrokeClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * THE PASTE KEEPS THE PEN — the 08-12 "Calligraphy effect seems to disappear" fix, pinned.
 *
 * [StrokeClipboard.stampAt] re-mints every stroke (fresh UUID, moved points) and used to lean on
 * the Stroke constructor's defaults for everything else, so a pasted calligraphy stroke came back
 * as 3.0-wide black ballpoint. These tests copy styled ink, stamp it elsewhere, and assert that
 * ONLY geometry and identity changed: nib (inkStyle), colour and width must ride through the mint
 * untouched, for every nib the pen dialog offers.
 */
class StrokeClipboardStampTest {

    private fun stroke(
        x: Float, y: Float, color: Int, width: Float, style: Int
    ) = Stroke(
        strokeId = UUID.randomUUID(),
        timestamp = 42L,
        strokePoints = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 10f, y + 5f, 0.7f, 8L)),
        color = color,
        strokeWidth = width,
        inkStyle = style
    )

    @Test
    fun `stamped calligraphy keeps its nib, colour and width`() {
        val clipboard = StrokeClipboard()
        val red = 0xFFB00020.toInt()
        clipboard.copy(listOf(stroke(100f, 200f, red, 5.5f, Stroke.STYLE_CALLIGRAPHY)))

        val stamped = clipboard.stampAt(300f, 400f)

        assertEquals(1, stamped.size)
        assertEquals(Stroke.STYLE_CALLIGRAPHY, stamped[0].inkStyle)
        assertEquals(red, stamped[0].color)
        assertEquals(5.5f, stamped[0].strokeWidth, 0.0001f)
    }

    @Test
    fun `every nib style survives the mint`() {
        val clipboard = StrokeClipboard()
        val styles = listOf(
            Stroke.STYLE_NORMAL, Stroke.STYLE_CALLIGRAPHY, Stroke.STYLE_FOUNTAIN, Stroke.STYLE_MARKER
        )
        clipboard.copy(styles.mapIndexed { i, s -> stroke(50f * i, 60f, 0xFF000000.toInt() + i, 2f + i, s) })

        val stamped = clipboard.stampAt(0f, 0f)

        assertEquals(styles, stamped.map { it.inkStyle })
        // Colour and width stay per-stroke, in order.
        assertEquals(listOf(2f, 3f, 4f, 5f), stamped.map { it.strokeWidth })
    }

    @Test
    fun `the mint still re-mints identity and moves geometry`() {
        val clipboard = StrokeClipboard()
        val original = stroke(100f, 200f, -16777216, 3f, Stroke.STYLE_CALLIGRAPHY)
        clipboard.copy(listOf(original))

        val stamped = clipboard.stampAt(150f, 260f)

        // A pasted stroke is the same ink with a NEW identity...
        assertNotEquals(original.strokeId, stamped[0].strokeId)
        // ...whose bounding-box top-left lands exactly at the target.
        assertEquals(150f, stamped[0].strokePoints.minOf { it.x }, 0.0001f)
        assertEquals(260f, stamped[0].strokePoints.minOf { it.y }, 0.0001f)
    }
}
