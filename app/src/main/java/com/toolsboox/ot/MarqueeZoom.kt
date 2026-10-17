package com.toolsboox.ot

/**
 * ZOOM TO A BOX — the ink panels' Zoom seat (Michael 2026-10-12: "Add a zoom button with marquee
 * zoom to the tuck panels for places like today notes all picks all stars write panels etc").
 *
 * Zoom box: the NEXT drag — finger or pen — draws a dashed box instead of ink; on lift the page
 * zooms so the box fills the surface, centred on it, and the tool you held comes back (a one-shot
 * that ends itself, never a mode to find your way out of — the hold-to-act canon). A drag shorter
 * than [MIN_DRAG_DP] lays nothing and zooms nothing. While the page is magnified past its resting
 * fit ([pastRest]) the seat is boxed.
 *
 * THE SEAT REPEATS ITS LAST CHOICE (Michael 2026-10-12: "Since Double tapping zooms out, instead of
 * making the other option zoom to fit, we can make the other option 10% more or 10% less like + or
 * -" → "We can make the zoom button the last used option for one click zoom in / out adjustments.
 * Or zoom to box :)"). The hold offers [Choice] — Zoom box · Zoom + · Zoom − — and the one picked
 * becomes the tap, remembered on this device ([CHOICE_KEY], the bottom pill's corner's mechanism:
 * BottomPill.CHOSEN_KEY / chosenFor). The seat's face says which. Zoom to fit left the menu: the
 * double-tap is the way back to the whole page. ＋/− [step] ×/÷ [STEP] about the middle of what you
 * see, within [rest, the pinch's ceiling], and a landing within [REST_SNAP] of the rest is the rest.
 *
 * The maths is the comic reader's hold-then-drag box (reader-embed.js commitLassoZoom: the drawn
 * box mapped back through the transform on screen, `grow = min(viewW / boxW, viewH / boxH)`, the
 * box's centre brought to the middle) on the ink surface's own algebra — [SurfaceFragment]'s
 * `focusOnRect`: screen = c·total + (view − page·total)/2 + pan, so pan = total·(page/2 − c). The
 * caller's updateTransformMatrix then clamps the pan to the page's edges, as it does for every
 * zoom. Pure arithmetic, so the tests pin it without a View in sight.
 */
object MarqueeZoom {

    /** A drag shorter than this (dp, its diagonal) is a slip, not a box: no zoom, the tool back
     *  (Michael 2026-10-12, "a drag shorter than ~20pt cancels"). */
    const val MIN_DRAG_DP = 20f

    /** How far past the resting zoom counts as "zoomed" — the slack every other zoom test in the
     *  surface keeps (isZoomed's 1.01), so a refit that rounded an ulp can't box the seat. */
    const val PAST_REST_SLACK = 1.01f

    /** An axis-aligned box, left < right and top < bottom (surface px or page units). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centreX: Float get() = (left + right) / 2f
        val centreY: Float get() = (top + bottom) / 2f
    }

    /** The box a drag from ([x0], [y0]) to ([x1], [y1]) spans, whichever way it was dragged. */
    fun box(x0: Float, y0: Float, x1: Float, y1: Float): Box =
        Box(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

    /** The drag is a box, not a slip: its diagonal reaches [minPx] (= [MIN_DRAG_DP] in px). */
    fun isBox(x0: Float, y0: Float, x1: Float, y1: Float, minPx: Float): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        return dx * dx + dy * dy >= minPx * minPx
    }

    /** Where the page lands: the magnification over the fit and the centred-origin pan. [atRest]:
     *  a ＋/− step landed exactly on the rest ([stepLanding]); the box never says so. */
    data class Landing(val zoom: Float, val panX: Float, val panY: Float, val atRest: Boolean = false)

    /**
     * The landing that makes [page] (a box in PAGE units, the drawn box mapped back through the
     * matrix on screen) fill a [viewW]×[viewH] surface whose fit-to-page scale is [base]: the zoom
     * that shows the WHOLE box (the smaller of the two axes' ratios — so a long flat drag zooms by
     * its length, and never crops what was boxed), clamped to [minZoom]..[maxZoom], and the pan that
     * puts the box's centre in the surface's middle. Null when nothing can be computed (no surface,
     * no fit, a box with no extent at all).
     */
    fun landing(
        page: Box, viewW: Float, viewH: Float, base: Float,
        pageW: Float, pageH: Float, minZoom: Float, maxZoom: Float
    ): Landing? {
        if (viewW <= 0f || viewH <= 0f || base <= 0f) return null
        if (page.width <= 0f && page.height <= 0f) return null
        // An axis with no extent asks nothing of the zoom (its ratio is unbounded); the other decides.
        val byW = if (page.width > 0f) viewW / (page.width * base) else Float.MAX_VALUE
        val byH = if (page.height > 0f) viewH / (page.height * base) else Float.MAX_VALUE
        val zoom = minOf(byW, byH).coerceIn(minZoom, maxZoom)
        val total = base * zoom
        return Landing(zoom, total * (pageW / 2f - page.centreX), total * (pageH / 2f - page.centreY))
    }

    /** The page is magnified past its resting zoom [restZoom] — the Zoom seat is boxed. A pinch out
     *  to less than the rest (a landscape letterbox) is not. */
    fun pastRest(zoom: Float, restZoom: Float): Boolean = zoom > restZoom * PAST_REST_SLACK

    /**
     * What the seat's tap repeats — the hold's three, in the hold's order; [key] is what is stored,
     * [caption] the seat's word under its face (≤ 8 characters), [label] the hold's row, [spoken] what
     * a screen reader says the tap will do. The same words on both apps (iOS ZoomBox.Choice).
     */
    enum class Choice(val key: String, val caption: String, val label: String, val spoken: String) {
        BOX("box", "Zoom", "Zoom box", "Zoom box"),
        IN("in", "Zoom +", "Zoom + 10%", "Zoom in 10%"),
        OUT("out", "Zoom −", "Zoom − 10%", "Zoom out 10%"),
    }

    /** The seat's remembered choice, one per device (`ledger_widgets`, beside the bottom pill's
     *  corner pick, BottomPill.CHOSEN_KEY). */
    const val CHOICE_KEY = "zoom_seat_choice"

    /** The seat's choice for a stored key, pure: nothing stored (or a key this build doesn't know)
     *  is Zoom box — the seat as it first shipped. */
    fun choiceFor(stored: String?): Choice = Choice.entries.firstOrNull { it.key == stored } ?: Choice.BOX

    /** What a tap on Zoom does, by the state it finds (Michael 2026-10-12). */
    enum class Tap {
        /** The box is armed and not yet drawn: the tap takes it back — the visible way out, whatever
         *  the choice (a box is only ever out because Zoom box was chosen). */
        DISARM,
        /** Zoom box: arm the box — the next drag draws it. */
        ARM,
        /** Zoom +: one [STEP] in. */
        IN,
        /** Zoom −: one [STEP] out, never past the rest. */
        OUT
    }

    /** The tap repeats the last choice; an armed box is put away first ("tap while zoomed = back to
     *  the rest" retired with Zoom to fit — the double-tap does that job now). */
    fun tap(armed: Boolean, choice: Choice): Tap = when {
        armed -> Tap.DISARM
        choice == Choice.IN -> Tap.IN
        choice == Choice.OUT -> Tap.OUT
        else -> Tap.ARM
    }

    /** One ＋/− step: 10% more or 10% less of the zoom on the glass (Michael 2026-10-12). */
    const val STEP = 1.10f

    /** A step landing this close above the rest IS the rest — the pinch's rest snap on iOS
     *  (PlannerShell.handleZoom, ZoomBox.restSnap), shared so one rest means one thing. */
    const val REST_SNAP = 1.02f

    /** Floating slack for "already there": a zoom within this ratio of its bound stays put. */
    private const val SAME = 1e-4f

    /**
     * The zoom one ＋ ([inward]) or − step lands on, pure, or null when it stays put — − at (or
     * below, a landscape pinch-out's letterbox) the rest, ＋ at the ceiling — so a tap that moves
     * nothing draws nothing. [zoom] ×/÷ [STEP], clamped to [[restZoom], [maxZoom]] (a ＋ from below
     * the rest lands at least on it), and within [REST_SNAP] of the rest exactly the rest. A − never
     * zooms in and a ＋ never zooms out.
     */
    fun step(zoom: Float, restZoom: Float, maxZoom: Float, inward: Boolean): Float? {
        if (zoom <= 0f || restZoom <= 0f) return null
        val hi = maxOf(maxZoom, restZoom)
        if (inward) {
            if (zoom >= hi * (1f - SAME)) return null
            val z = (zoom * STEP).coerceIn(restZoom, hi)
            return if (z <= restZoom * REST_SNAP) restZoom else z
        }
        if (zoom <= restZoom * (1f + SAME)) return null
        val z = (zoom / STEP).coerceIn(restZoom, hi)
        return if (z <= restZoom * REST_SNAP) restZoom else z
    }

    /**
     * Would [choice] move the page now? The box can always go out; ＋ stops at the ceiling and − at
     * the rest ([step] says null) — there the seat's face dims, as iOS's does (ZoomBox.stepMoves), the
     * tap does nothing, and the hold's row says where it stopped ([holdRow], [stoppedSay]).
     */
    fun moves(choice: Choice, zoom: Float, restZoom: Float, maxZoom: Float): Boolean = when (choice) {
        Choice.BOX -> true
        Choice.IN -> step(zoom, restZoom, maxZoom, inward = true) != null
        Choice.OUT -> step(zoom, restZoom, maxZoom, inward = false) != null
    }

    /**
     * Where one ＋/− step lands the page: [step]'s zoom, and the pan that keeps the page point in the
     * middle of the surface where it was ("± step around the middle of what you see") — the
     * surface's own algebra ([InkViewport.centreOf] / [InkViewport.panCentring]: screen centre =
     * c·total + (view − page·total)/2 + pan, so at a fixed base the pan scales with the zoom). The
     * caller's updateTransformMatrix clamps it to the page's edges as for every zoom. [atRest]: the
     * step landed on the rest, so the zoom goes back to the machinery. Null = nothing moves.
     */
    fun stepLanding(zoom: Float, panX: Float, panY: Float, base: Float,
                    restZoom: Float, maxZoom: Float, pageW: Float, pageH: Float,
                    inward: Boolean): Landing? {
        if (base <= 0f) return null
        val z = step(zoom, restZoom, maxZoom, inward) ?: return null
        val oldTotal = base * zoom
        val total = base * z
        val px = InkViewport.panCentring(InkViewport.centreOf(panX, oldTotal, pageW), total, pageW)
        val py = InkViewport.panCentring(InkViewport.centreOf(panY, oldTotal, pageH), total, pageH)
        return Landing(z, px, py, atRest = z == restZoom)
    }

    /** The seat is boxed while its box is armed or the page is magnified past the rest. */
    fun seatOn(armed: Boolean, zoomedPastRest: Boolean): Boolean = armed || zoomedPastRest

    /**
     * The hold's row for [choice] (review 2026-10-12): always all three, in the hold's order, so the
     * menu keeps its shape and the ✓ never leaves with a row. Where [choice] can't move now ([moves]
     * false) the row says where it stopped — iOS greys that row; a GoItem has no grey, so it wears
     * words instead — and its tap only says so ([stoppedSay]), the choice left as it was.
     */
    fun holdRow(choice: Choice, chosen: Boolean, moves: Boolean): String {
        val stopped = if (moves) null else when (choice) {
            Choice.BOX -> null
            Choice.IN -> "most zoom"
            Choice.OUT -> "whole page"
        }
        return choice.label + (stopped?.let { " ($it)" } ?: "") + (if (chosen) "  ✓" else "")
    }

    /** What a hold row that can't move says when tapped — nothing moves and the choice stays. */
    fun stoppedSay(choice: Choice): String? = when (choice) {
        Choice.BOX -> null
        Choice.IN -> "Already at the most zoom"
        Choice.OUT -> "Already at the whole page"
    }

    /** The seat's hold as a screen reader names it — iOS's "Hold to choose: Zoom box, Zoom in 10%,
     *  Zoom out 10%" (TalkBack says "double-tap and hold to …"), the bottom pill corner's form. */
    val HOLD_SPOKEN: String = "Choose: " + Choice.entries.joinToString(", ") { it.spoken }
}

