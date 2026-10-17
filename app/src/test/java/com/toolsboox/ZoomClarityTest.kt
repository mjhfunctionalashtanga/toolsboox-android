package com.toolsboox

import com.toolsboox.da.StrokePoint
import com.toolsboox.ot.DiagnosticsLog
import com.toolsboox.ot.InkViewport
import com.toolsboox.ot.ZoomClarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 16× with no loss of clarity (Michael 2026-10-12: "I want these to work at 8x zoom with no loss of
 * clarity. Heck, 16x zoom would be meaningful.") and the pan's own record ("Panning after zoom feels
 * stilted"): when the page is drawn again at the zoomed resolution, what a redraw draws, and the two
 * log lines both apps write, word for word.
 */
class ZoomClarityTest {

    private fun pts(vararg xy: Float) = xy.toList().chunked(2).map { StrokePoint(it[0], it[1], 0.5f) }

    @Test
    fun `drawn again only past the rest and past the page bitmaps' own resolution`() {
        // The B6 rests: portrait 0.68 and landscape 0.95 screen px a unit, full-screen landscape 1.03
        // — a hair past 1:1 but the rest: drawn exactly as shipped.
        assertFalse(ZoomClarity.wantsSharp(0.68f, zoomedPastRest = false))
        assertFalse(ZoomClarity.wantsSharp(1.03f, zoomedPastRest = false))
        // Past the rest but still under 1:1 (a portrait ＋ 10%): the page bitmaps are shrunk, not
        // magnified — nothing to sharpen.
        assertFalse(ZoomClarity.wantsSharp(0.75f, zoomedPastRest = true))
        // A double-tap (2×) and the ceiling: drawn again.
        assertTrue(ZoomClarity.wantsSharp(1.36f, zoomedPastRest = true))
        val portrait = InkViewport.restFit(957f, 1345f, landscapeFitsWhole = false)
        assertTrue(ZoomClarity.wantsSharp(portrait.base * InkViewport.ceiling(portrait.zoom), zoomedPastRest = true))
        // 16× the portrait rest is 10.9 screen px per page unit: the stair-step the B6 used to show.
        assertEquals(10.9f, portrait.base * InkViewport.ceiling(portrait.zoom), 0.05f)
    }

    @Test
    fun `a redraw draws the strokes the window can show`() {
        // A 100×100 window at (1000, 1000).
        val l = 1000f; val t = 1000f; val r = 1100f; val b = 1100f
        assertTrue("a sample inside", ZoomClarity.meets(pts(1050f, 1050f), l, t, r, b, 0f))
        assertTrue("a line across it with no sample inside",
            ZoomClarity.meets(pts(900f, 1050f, 1200f, 1050f), l, t, r, b, 0f))
        assertFalse("far off", ZoomClarity.meets(pts(10f, 10f, 200f, 300f), l, t, r, b, 24f))
        // Just outside, within the nib's pad: drawn (its edge shows).
        assertTrue(ZoomClarity.meets(pts(1110f, 1050f, 1120f, 1060f), l, t, r, b, 24f))
        assertFalse(ZoomClarity.meets(pts(1110f, 1050f, 1120f, 1060f), l, t, r, b, 5f))
        assertFalse("an empty stroke", ZoomClarity.meets(emptyList(), l, t, r, b, 24f))
        // The pad covers the widest nib (a marker is 2.2× its base) with room to spare.
        assertTrue(ZoomClarity.strokePad(3f) >= 3f * 2.2f)
        assertTrue(ZoomClarity.strokePad(12f) >= 12f * 2.2f)
    }

    @Test
    fun `the gesture watchdog waits out fingers resting on the glass`() {
        // The lift the handler missed: fingers last seen lifting (or never seen down) settle at once.
        assertEquals(0L, ZoomClarity.watchdogWait(fingersDown = false, quietMs = 600L))
        // Fingers still down, 600 ms without an event: a pan resting mid-gesture, not its end
        // (review of 241e9212 — it settled under a still hand, then again at the real lift).
        assertEquals(ZoomClarity.GESTURE_HOLD_MS - 600L, ZoomClarity.watchdogWait(fingersDown = true, quietMs = 600L))
        // …until the hold runs out with no event at all: some other layer took the lift.
        assertEquals(0L, ZoomClarity.watchdogWait(fingersDown = true, quietMs = ZoomClarity.GESTURE_HOLD_MS))
        assertEquals(0L, ZoomClarity.watchdogWait(fingersDown = true, quietMs = ZoomClarity.GESTURE_HOLD_MS + 500L))
        // A clock that ran backwards never asks for more than the whole hold.
        assertEquals(ZoomClarity.GESTURE_HOLD_MS, ZoomClarity.watchdogWait(fingersDown = true, quietMs = -40L))
    }

    @Test
    fun `a template's pictures are decoded for the size they take on the glass`() {
        // A stations-grid slot (six to a row) is ~262 page units on its long side.
        val slot = 262f
        // At rest, into the page-space template (1404×1872, scale 1): the 2× headroom, as always.
        assertEquals(524f, ZoomClarity.pictureDecodeTarget(slot, 1f, 1872), 0.01f)
        // A double-tap on the B6 portrait (1.36 px a unit): still the rest's — never less.
        assertEquals(524f, ZoomClarity.pictureDecodeTarget(slot, 1.36f, 1345), 0.01f)
        // 16× the portrait rest (10.9 px a unit): the slot would be 2,856 px on a 1,345 px glass —
        // decoded for the glass, not for a slot bigger than it (a 12 MP photo stays bounded).
        assertEquals(1345f, ZoomClarity.pictureDecodeTarget(slot, 10.9f, 1345), 0.01f)
        // A small slot at 16× is decoded for exactly its size there.
        assertEquals(436f, ZoomClarity.pictureDecodeTarget(40f, 10.9f, 1345), 0.01f)
    }

    @Test
    fun `the pan line - frames, the worst one, those over a 60 Hz frame, the glide`() {
        val m = ZoomClarity.PanMeter()
        val ms = 1_000_000L
        m.frame(0L, 5 * ms)              // 5 ms
        m.frame(16 * ms, 36 * ms)        // 20 ms — over the 16.7 ms budget
        m.frame(40 * ms, 48 * ms)        // 8 ms
        assertEquals(3, m.frames)
        assertEquals("pan: 3 frames over 100 ms, worst frame 20.0 ms, 1 over 16.7 ms, glide 0 ms", m.line(100 * ms))
        m.reset()
        assertEquals(0, m.frames)
        assertFalse(m.zoomed)
        assertEquals("pan: 0 frames over 0 ms, worst frame 0.0 ms, 0 over 16.7 ms, glide 0 ms", m.line(5 * ms))
        assertEquals("pan: 42 frames over 700 ms, worst frame 12.3 ms, 0 over 16.7 ms, glide 350 ms",
            ZoomClarity.panLine(42, 700L, 12.34, 0, 350L))
    }

    @Test
    fun `the zoom line - over the rest, the redraw, the scale it was drawn at`() {
        assertEquals("zoom: 16.0× settled, redraw 42 ms, sharp at scale 10.91",
            ZoomClarity.zoomLine(16f, 42L, 10.906f))
        assertEquals("zoom: 1.0× settled, redraw 3 ms, sharp at scale 0.68",
            ZoomClarity.zoomLine(1f, 3L, 0.6816f))
        // Anything left on a page-space bitmap past it is named, after the shared words (iOS's
        // PencilKit-ceiling caveat's place).
        assertEquals("zoom: 8.0× settled, redraw 12 ms, sharp at scale 5.45, B6 mirror on the page bitmap",
            ZoomClarity.zoomLine(8f, 12L, 5.453f, listOf("B6 mirror")))
    }

    @Test
    fun `the diagnostics ring keeps both lines and nothing that merely starts with pan`() {
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "SurfaceFragment",
            "pan: 3 frames over 100 ms, worst frame 20.0 ms, 1 over 16.7 ms, glide 0 ms"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "SurfaceFragment",
            "zoom: 16.0× settled, redraw 42 ms, sharp at scale 10.91"))
        assertFalse(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "TuckPanel", "panel opened"))
        assertFalse(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "ComicReader", "zoomed to panel 3"))
    }
}
