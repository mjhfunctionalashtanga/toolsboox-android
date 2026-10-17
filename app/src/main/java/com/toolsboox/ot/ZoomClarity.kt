package com.toolsboox.ot

import com.toolsboox.da.StrokePoint
import java.util.Locale

/**
 * NO LOSS OF CLARITY TO 16× (Michael 2026-10-12, iPhone: "I want these to work at 8x zoom with no
 * loss of clarity. Heck, 16x zoom would be meaningful.").
 *
 * The ink surface itself was always vector — [com.toolsboox.ui.plugin.SurfaceFragment.applyStrokes]
 * draws every stroke, picture and text box through the view matrix onto the SurfaceView — but two
 * things on the glass were page-space BITMAPS, one page unit to one pixel (1404×1872), magnified by
 * the matrix:
 *  - the B6 daemon's mirror: the shadow bitmap blitted with no filtering
 *    ([BigmeFastInk.syncOverlay] → `drawBitmap(page, transform, null)`), so at 16× of the portrait
 *    rest (11 screen px a unit) every ink edge, photo and letter stood in 11px stair-steps — and
 *    every pen pause re-pushed it (the debounced bake forces the mirror);
 *  - the page template (ruling, headers, the Today dashboard) in its ImageView, the same size of
 *    bitmap through `imageMatrix` — bilinear, so soft.
 * Past [SHARP_MIN_SCALE] screen px a unit, once the hand is off the glass, both are drawn AGAIN at
 * the zoomed resolution: the B6 mirror from the strokes themselves into a view-sized buffer (the
 * same pixels the surface shows), the template from its recorded drawing (a template of decoded
 * pictures — the grams — from its drawing run again, each picture decoded for its size on the
 * glass, [pictureDecodeTarget]). Mid-gesture they stay on
 * the cheap page-space bitmaps, as today. This object is the arithmetic and the two log lines, so the
 * tests pin them without a View in sight.
 */
object ZoomClarity {

    /** Screen px per page unit past which the page-space bitmaps are being magnified. At or below it
     *  (the B6 portrait rest 0.68, landscape 0.95, its full-screen landscape 1.03 — never past the
     *  rest) nothing changes from what shipped. */
    const val SHARP_MIN_SCALE = 1.0f

    /** The display's frame budget the pan line counts against (60 Hz), in ms. */
    const val FRAME_BUDGET_MS = 16.7

    /** Draw again at the zoomed resolution: the page is magnified past its rest AND past the
     *  bitmaps' own resolution. A landscape rest that happens to sit a hair over 1:1 (the B6's
     *  full-screen 1.03) is the rest, drawn as it always was. */
    fun wantsSharp(totalScale: Float, zoomedPastRest: Boolean): Boolean =
        zoomedPastRest && totalScale > SHARP_MIN_SCALE

    /** How long fingers known to be on the glass may rest without a single event before the
     *  gesture watchdog stops waiting for their lift and settles anyway (a lift some other layer
     *  took never says so). */
    const val GESTURE_HOLD_MS = 3_000L

    /**
     * The pinch/pan watchdog, fired [quietMs] after the last event its handler saw: how much longer
     * to wait (ms), or 0 = settle now. Fingers lifted — or never seen to be down — settle at once:
     * that is the lift the handler missed. Fingers still down are the gesture resting mid-pan
     * (review of 241e9212: a 600 ms pause with the fingers down settled under a still hand — a
     * forced B6 refresh, then another at the real lift); they get [GESTURE_HOLD_MS] of quiet first.
     */
    fun watchdogWait(fingersDown: Boolean, quietMs: Long, holdMs: Long = GESTURE_HOLD_MS): Long =
        if (!fingersDown || quietMs >= holdMs) 0L else holdMs - quietMs.coerceAtLeast(0L)

    /**
     * The size (px, the longer side) to decode a template's picture at, for a slot of [slotUnits]
     * page units drawn at [drawScale] screen px a unit on a canvas whose longer side is
     * [canvasMaxPx]. At rest (scale 1, the page-space template) it is the 2× headroom the slots have
     * always had; drawn again at a zoom ([com.toolsboox.ui.plugin.SurfaceFragment.drawTemplate]
     * decodesPictures) it is the slot's size on the glass — never past the canvas itself, since a
     * slot bigger than the glass is never wholly on it (a 12 MP photo stays bounded) — and never
     * below the rest's.
     */
    fun pictureDecodeTarget(slotUnits: Float, drawScale: Float, canvasMaxPx: Int): Float {
        val rest = slotUnits * 2f
        val onGlass = minOf(slotUnits * drawScale, canvasMaxPx.toFloat())
        return maxOf(rest, onGlass)
    }

    /** The pad (page units) a stroke's bounds get before the visible-window test: wide enough for
     *  the widest nib (a marker is 2.2× its base, a calligraphy edge swings wider). */
    fun strokePad(strokeWidth: Float): Float = maxOf(24f, strokeWidth * 4f)

    /**
     * Whether a stroke through [points] can show in the page window [l]..[r] × [t]..[b] (page units)
     * once grown by [pad] — the cull that keeps a 16× redraw to what is on the glass. A point inside
     * answers at once; otherwise the stroke's bounding box must meet the window (a long line can
     * cross it with no sample inside). An empty stroke shows nothing.
     */
    fun meets(points: List<StrokePoint>, l: Float, t: Float, r: Float, b: Float, pad: Float): Boolean {
        if (points.isEmpty()) return false
        val wl = l - pad
        val wt = t - pad
        val wr = r + pad
        val wb = b + pad
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x in wl..wr && p.y in wt..wb) return true
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return maxX >= wl && minX <= wr && maxY >= wt && minY <= wb
    }

    /**
     * One pan (or pinch) gesture's frames, for its log line. A frame is one move the page followed:
     * its [frame] time is the work from the move's arrival to the surface post — what decides whether
     * that move made the display's frame. [zoomed]: the zoom changed during the gesture (the settle
     * then logs the zoom line too).
     */
    class PanMeter {
        var frames = 0
            private set
        var zoomed = false
        private var firstNs = 0L
        private var worstNs = 0L
        private var overBudget = 0

        /** One frame, from [startNs] to [endNs] (System.nanoTime). */
        fun frame(startNs: Long, endNs: Long) {
            if (frames == 0) firstNs = startNs
            frames++
            val d = (endNs - startNs).coerceAtLeast(0L)
            if (d > worstNs) worstNs = d
            if (d / 1e6 > FRAME_BUDGET_MS) overBudget++
        }

        /** The gesture's line at its lift ([nowNs]); [glideMs] the momentum after it (0 on e-ink,
         *  which draws no glide). iOS Diag's words (LedgerCore PanFrames), so a pasted log reads the
         *  same from both apps. Android draws a frame per move, so its "worst frame" is the longest
         *  frame's WORK (move to post) — iOS's is the longest display-link gap; both say whether the
         *  page kept the display's rate. */
        fun line(nowNs: Long, glideMs: Long = 0L): String =
            panLine(frames, if (frames == 0) 0L else (nowNs - firstNs) / 1_000_000L,
                worstNs / 1e6, overBudget, glideMs)

        fun reset() {
            frames = 0
            zoomed = false
            firstNs = 0L
            worstNs = 0L
            overBudget = 0
        }
    }

    /** "pan: N frames over T ms, worst frame X ms, Y over 16.7 ms, glide Z ms" — word for word iOS's. */
    fun panLine(frames: Int, overMs: Long, worstMs: Double, over: Int, glideMs: Long): String =
        String.format(Locale.US, "pan: %d frames over %d ms, worst frame %.1f ms, %d over 16.7 ms, glide %d ms",
            frames, overMs, worstMs, over, glideMs)

    /** "zoom: k× settled, redraw R ms, sharp at scale S" — iOS ZoomSettleLine's words: [overRest] the
     *  zoom over the resting posture (the ceiling is 16), [scale] the screen px per page unit the page
     *  was drawn at. Like iOS's PencilKit caveat, anything left on a page-space bitmap while the page
     *  is magnified past it ([onPageBitmap]: "B6 mirror", "template") is named after the shared words. */
    fun zoomLine(overRest: Float, redrawMs: Long, scale: Float, onPageBitmap: List<String> = emptyList()): String =
        String.format(Locale.US, "zoom: %.1f× settled, redraw %d ms, sharp at scale %.2f", overRest, redrawMs, scale) +
            onPageBitmap.joinToString("") { ", $it on the page bitmap" }
}
