package com.toolsboox

import com.toolsboox.ot.TuckPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's glyph faces, kept inside their buttons — the 08-12 "Playback speed in the pop-out
 * is too big by just a bit" fix, pinned.
 *
 * [TuckPanel.fittedGlyphTextSize] is the pure arithmetic under drawGlyph: a face that measures
 * wider than its square bitmap (the transport's "1.25×" speed value) scales down proportionally
 * to fit; a face that already fits (every single-glyph button) keeps the house size untouched.
 */
class TuckPanelGlyphFitTest {

    @Test
    fun `a single glyph keeps the house size`() {
        // Measures well inside the box — must come back bit-identical, never scaled up.
        assertEquals(36.4f, TuckPanel.fittedGlyphTextSize(36.4f, 30f, 47.8f), 0.0f)
    }

    @Test
    fun `an overwide face scales down to exactly fit`() {
        // "1.25×" at single-glyph size measures ~96px against a ~48px face.
        val fitted = TuckPanel.fittedGlyphTextSize(36.4f, 96f, 47.8f)
        assertEquals(36.4f * 47.8f / 96f, fitted, 0.0001f)
        // Linear width scaling: at the fitted size the string measures exactly maxWidth.
        assertEquals(47.8f, 96f * (fitted / 36.4f), 0.001f)
        assertTrue(fitted < 36.4f)
    }

    @Test
    fun `degenerate measurements never divide by zero or grow the face`() {
        assertEquals(36.4f, TuckPanel.fittedGlyphTextSize(36.4f, 0f, 47.8f), 0.0f)
        // Exactly at the limit is a fit, not an overrun.
        assertEquals(36.4f, TuckPanel.fittedGlyphTextSize(36.4f, 47.8f, 47.8f), 0.0f)
    }
}
