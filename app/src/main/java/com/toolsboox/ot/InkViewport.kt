package com.toolsboox.ot

import com.toolsboox.ui.plugin.SurfaceFragment

/**
 * Full screen gives an ink page the column's room, and the page RE-CENTRES and fits it (Michael
 * 2026-10-11: "Recentering/zooming after closing the tuck panel makes sense" — his yes to "when you
 * tap ⛶, the page re-centres and zooms to fill the space the panel left; opening the panel puts it
 * back where it was"). That reverses the same morning's "it needn't center nor move", which this
 * object used to build as a home frame that never slid.
 *
 * ONE FIT FRAME: THE WHOLE SURFACE. In full screen the SurfaceView runs on under the hidden column,
 * and every resting posture — the fit, the landscape width-fill and its start anchor, the centred
 * portrait — is worked out in the surface as it stands, exactly as Normal always did. So closing
 * the panel refits the page to the wider surface:
 *  - PORTRAIT shows the WHOLE page, bigger and centred, nothing below the fold — the chosen
 *    default (whole page), pending Michael's pick between fit-page and fit-width: 957×1345 →
 *    1072×1345, scale 0.6816 → 0.7185, ~31.6px each side on either dock.
 *  - LANDSCAPE fills the new width and keeps the row that was at the top ([panKeepingTopRow]):
 *    1333×969 → 1448×969, scale 0.9494 → 1.0313, leading edge at the surface's.
 *  - A close-up a HAND took keeps its magnification over the RESTING posture ([zoomOverRest] —
 *    so a landscape close-up grows with the width-fill) and the page point it had centred
 *    ([centreOf] / [panCentring]); a hand's pinch-out to the whole page stays the whole page.
 * Opening the panel runs the same arithmetic back, so the docked fit returns exactly.
 *
 * [borrowedRoom] and [turned] remain, as DETECTORS only: they let SurfaceFragment know a resize was
 * ⛶ and never a turn (no B6 recreate; the Onyx limit rect re-armed at once), never where the page
 * rests. Pure arithmetic, so the tests pin it without a View in sight.
 */
object InkViewport {

    /** Half a pixel: a page within this of the surface's length FITS it (rounding, not a zoom). */
    const val FIT_SLACK_PX = 0.5f

    /**
     * The surface px the hidden column lends, as (left, right): the part of the surface lying in
     * the column's Normal slot ([slotPx] wide, on the [dockedLeft] edge of a [parentWidth] row).
     * Zero on both sides while the surface stops at the slot — Normal, or a layout pass that
     * hasn't moved the surface yet. A detector: the surface less this room is the NORMAL frame,
     * which [turned] and SurfaceFragment's ⛶ test compare across a resize.
     */
    fun borrowedRoom(surfaceLeft: Int, surfaceRight: Int, parentWidth: Int, slotPx: Int,
                     dockedLeft: Boolean): Pair<Int, Int> {
        val cap = minOf(slotPx, surfaceRight - surfaceLeft).coerceAtLeast(0)
        return if (dockedLeft) (slotPx - surfaceLeft).coerceIn(0, cap) to 0
        else 0 to (surfaceRight - (parentWidth - slotPx)).coerceIn(0, cap)
    }

    /**
     * Whether a resize turned the page between portrait and landscape, judged on the NORMAL frames
     * ([prevNormalW]×[prevH] before, [normalW]×[h] after) — so ⛶ lending or taking back the
     * column's room never reads as a rotation, even on a near-square window where the slot alone
     * would turn the surface's own shape (review 2026-10-11). With no room lent it is the old
     * surface test.
     */
    fun turned(prevNormalW: Int, prevH: Int, normalW: Int, h: Int): Boolean =
        prevNormalW > 0 && prevH > 0 && normalW > 0 && h > 0 && (prevNormalW < prevH) != (normalW < h)

    /** A posture: [base] is the fit-to-page scale, [zoom] the magnification over it. */
    data class Fit(val base: Float, val zoom: Float) {
        val total: Float get() = base * zoom
    }

    /** The fit-to-page scale of a [pageW]×[pageH] page on a [w]×[h] surface — the matrix's base. */
    fun fitScale(w: Float, h: Float,
                 pageW: Float = SurfaceFragment.CANVAS_WIDTH.toFloat(),
                 pageH: Float = SurfaceFragment.CANVAS_HEIGHT.toFloat()): Float =
        minOf(w / pageW, h / pageH)

    /**
     * The resting posture on a [w]×[h] surface — the whole surface, in Normal and in full screen
     * alike (Michael 2026-10-11). LANDSCAPE fills the width (and scrolls down), unless the surface
     * letterboxes the whole page instead ([landscapeFitsWhole], the today dashboard); PORTRAIT
     * shows the whole page, zoom 1 over the fit — so a portrait surface grown by the column's room
     * gets the whole page bigger, never a width-fill that would push its foot below the fold.
     *
     * The surface's OWN shape decides, in full screen too, so a near-square split-screen window
     * whose lent room turns it wider than tall (1000×1050 → 1115×1050) rests as landscape while
     * the panel is closed and goes back to portrait when it opens — a posture, never a turn
     * ([turned] still judges the Normal frame; review 2026-10-11, accepted as consistent).
     */
    fun restFit(w: Float, h: Float, landscapeFitsWhole: Boolean,
                pageW: Float = SurfaceFragment.CANVAS_WIDTH.toFloat(),
                pageH: Float = SurfaceFragment.CANVAS_HEIGHT.toFloat(),
                minZoom: Float = SurfaceFragment.MIN_ZOOM,
                maxZoom: Float = SurfaceFragment.MAX_ZOOM): Fit {
        val base = fitScale(w, h, pageW, pageH)
        val zoom = if (w > h && !landscapeFitsWhole) (w / (pageW * base)).coerceIn(minZoom, maxZoom) else 1f
        return Fit(base, zoom)
    }

    /**
     * The zoom ceiling (over the fit) on a surface resting at [restZoom]: [overRest] × the rest
     * (Michael 2026-10-12: "Heck, 16x zoom would be meaningful" — [SurfaceFragment.MAX_ZOOM] is 16).
     * The box, ＋ and the pinch all stop here, so portrait reaches 16 over the fit and the B6's
     * landscape width-fill (1.834) reaches 29.3 — iOS ZoomBox's `maxFactor × rest`. A rest below
     * the fit (never) counts as the fit.
     */
    fun ceiling(restZoom: Float, overRest: Float = SurfaceFragment.MAX_ZOOM): Float =
        maxOf(restZoom, SurfaceFragment.MIN_ZOOM) * overRest

    /**
     * A hand's [zoom] carried across a refit from the [oldRest] posture to the [newRest] one: the
     * magnification is kept RELATIVE TO THE REST (Michael 2026-10-11, ⛶ re-centres and fits;
     * review 2026-10-11). In landscape the fit is height-bound and doesn't move on ⛶ (B6 0.5176
     * at 1333×969 and at 1448×969) — only the width-fill rest does — so a zoom kept bare left a
     * scrolled page 1334px wide on a 1448px surface. Portrait rests at zoom 1 on both sides, so
     * there it is the zoom itself. Successive ratios multiply out, so a resize storm can't drift.
     * The ceiling is the NEW rest's ([ceiling], [maxOverRest] × it), so a landscape close-up at 16×
     * its rest stays 16× its rest across ⛶ instead of being cut to a bare 16 over the fit.
     */
    fun zoomOverRest(zoom: Float, oldRest: Fit, newRest: Fit,
                     minZoom: Float = SurfaceFragment.MIN_ZOOM,
                     maxOverRest: Float = SurfaceFragment.MAX_ZOOM): Float {
        if (oldRest.zoom <= 0f || newRest.zoom <= 0f) return zoom
        return (zoom * newRest.zoom / oldRest.zoom).coerceIn(minZoom, ceiling(newRest.zoom, maxOverRest))
    }

    /** Whether a page [page] long FITS a [view] long surface — within [FIT_SLACK_PX], so a fit
     *  that rounded an ulp past the frame it filled still rests (review 2026-10-11). */
    fun fits(page: Float, view: Float): Boolean = page <= view + FIT_SLACK_PX

    /**
     * The centred-origin pan on the axis with no start anchor (vertical): a page that [fits]
     * rests centred (0), a bigger one may move until its edge meets the surface's, plus
     * [overscroll]. The half-pixel slack matters here as on the horizontal clamp: the height-bound
     * full-screen portrait fit (sh/1872) comes out one float step past sh on ~58 heights, and an
     * exact compare let a +36px top-row shift through on the day page's half-screen overscroll,
     * resting the page low with its foot cut off (review 2026-10-11).
     */
    fun clampCentredPan(pan: Float, page: Float, view: Float, overscroll: Float): Float {
        if (fits(page, view)) return 0f
        val maxPan = (page - view) / 2f + overscroll
        return pan.coerceIn(-maxPan, maxPan)
    }

    /**
     * The vertical pan that keeps the page row at the top of a [viewH]-tall surface where it was
     * while the page's scale goes from [oldTotal] to [newTotal] — the landscape refit on ⛶ (Michael
     * 2026-10-11), so a page scrolled down the day doesn't jump back to its top. The pan is
     * measured from the centred page, as SurfaceFragment stores it: the row at the top is
     * y = H/2 − (viewH/2 + pan)/total, and holding y gives pan' = (viewH/2 + pan)·new/old − viewH/2.
     * The page's top stays the top (one max pan maps to the other) and the mapping runs back
     * exactly. Only a page taller than the surface has a row to keep: SurfaceFragment skips this
     * when the page [fits] (the portrait whole-page rest), so the rest is centred, not nudged.
     */
    fun panKeepingTopRow(oldTotal: Float, oldPanY: Float, newTotal: Float, viewH: Float): Float {
        if (oldTotal <= 0f || newTotal <= 0f) return oldPanY
        return (viewH / 2f + oldPanY) * (newTotal / oldTotal) - viewH / 2f
    }

    /** The page point (design units, along a page [pageLen] long) under the surface's centre for
     *  a [pan] at [total] scale: screen centre = c·total + (view − page·total)/2 + pan. */
    fun centreOf(pan: Float, total: Float, pageLen: Float): Float = pageLen / 2f - pan / total

    /** The pan that puts page point [c] under the surface's centre at [total] scale — [centreOf]
     *  inverted, so a hand zoom keeps the spot it centred across any refit. */
    fun panCentring(c: Float, total: Float, pageLen: Float): Float = total * (pageLen / 2f - c)

    /**
     * Where the page's leading edge may stand on one axis (surface px), given where it stands now
     * ([edge]). [page] is the scaled page's length, [view] the surface's, [homeStart]/[homeLen] the
     * span the page rests in — since the 2026-10-11 re-centre always the whole surface (0, [view]),
     * which makes this the Normal clamp exactly; [homeOverscroll]/[viewOverscroll] are the pan
     * margins past its edges.
     *
     * A page that fits RESTS — at the start ([restAtStart], the landscape anchor) or centred. A
     * bigger page (a width-fill read down the page, or a hand's zoom) may stand anywhere that keeps
     * the surface covered, plus the overscroll margin.
     *
     * "Fits" carries [FIT_SLACK_PX]: the float fit often comes out an ulp wider than the frame it
     * filled (review 2026-10-11 — ~12% of screen sizes), and an exact compare read that resting
     * page as zoomed, so a sideways drift could slide it off its rest.
     */
    fun clampEdge(edge: Float, page: Float, view: Float, homeStart: Float, homeLen: Float,
                  homeOverscroll: Float, viewOverscroll: Float, restAtStart: Boolean): Float {
        if (fits(page, homeLen)) return homeStart + if (restAtStart) 0f else (homeLen - page) / 2f
        val lo = minOf(homeStart + homeLen - page - homeOverscroll,
            if (page > view) view - page - viewOverscroll else 0f)
        val hi = maxOf(homeStart + homeOverscroll,
            if (page > view) viewOverscroll else view - page)
        return edge.coerceIn(lo, hi)
    }
}
