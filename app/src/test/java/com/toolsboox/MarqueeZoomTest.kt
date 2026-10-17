package com.toolsboox

import com.toolsboox.ot.InkViewport
import com.toolsboox.ot.MarqueeZoom
import com.toolsboox.ot.PanelFaces
import com.toolsboox.ot.PanelSeats
import com.toolsboox.ot.PanelSeats.Dress
import com.toolsboox.ot.PanelSeats.Platform
import com.toolsboox.ui.plugin.SurfaceFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zoom to a box (Michael 2026-10-12: "Add a zoom button with marquee zoom to the tuck panels for
 * places like today notes all picks all stars write panels etc"): the drawn box fills the surface,
 * centred, within the zoom limits; a slip under ~20dp zooms nothing; the seat is boxed while the
 * page is magnified past its rest; the tap repeats the last choice (box, ＋ or −). Pinned on the
 * B6's Normal portrait surface (957×1345) and its landscape one (1333×969), the numbers
 * TuckPanelViewportTest uses.
 */
class MarqueeZoomTest {

    private val pageW = SurfaceFragment.CANVAS_WIDTH.toFloat()
    private val pageH = SurfaceFragment.CANVAS_HEIGHT.toFloat()
    private val minZ = SurfaceFragment.MIN_ZOOM
    private val maxZ = SurfaceFragment.MAX_ZOOM

    /** Where page point c lands on a surface `view` long at `total` scale and `pan` — the
     *  surface's own matrix (centred origin + pan). */
    private fun screenOf(c: Float, total: Float, page: Float, view: Float, pan: Float) =
        c * total + (view - page * total) / 2f + pan

    @Test
    fun `the box is the drag's, whichever way it was dragged`() {
        val a = MarqueeZoom.box(300f, 400f, 100f, 50f)
        assertEquals(MarqueeZoom.Box(100f, 50f, 300f, 400f), a)
        assertEquals(MarqueeZoom.box(100f, 50f, 300f, 400f), a)
        assertEquals(200f, a.width)
        assertEquals(350f, a.height)
        assertEquals(200f, a.centreX)
        assertEquals(225f, a.centreY)
    }

    @Test
    fun `a drag under 20dp is a slip - no zoom`() {
        val minPx = MarqueeZoom.MIN_DRAG_DP * 2f   // a 2× screen: 40px
        assertFalse(MarqueeZoom.isBox(100f, 100f, 100f, 100f, minPx))   // a tap
        assertFalse(MarqueeZoom.isBox(100f, 100f, 120f, 130f, minPx))   // 36px
        assertTrue(MarqueeZoom.isBox(100f, 100f, 124f, 132f, minPx))    // 40px exactly
        assertTrue(MarqueeZoom.isBox(100f, 100f, 600f, 100f, minPx))    // a long flat drag is a box
    }

    @Test
    fun `the box fills the surface and its centre lands in the middle`() {
        val w = 957f
        val h = 1345f
        val base = InkViewport.fitScale(w, h)
        // The page's top-left quarter: 702×936 page units around (351, 468).
        val box = MarqueeZoom.Box(0f, 0f, 702f, 936f)
        val land = MarqueeZoom.landing(box, w, h, base, pageW, pageH, minZ, maxZ)
        assertNotNull(land)
        // The whole box shows: the tighter axis decides (here the width — 957/(702·0.6816) = 2).
        val total = base * land!!.zoom
        assertEquals(minOf(w / (702f * base), h / (936f * base)), land.zoom, 1e-4f)
        assertTrue("box width fits", 702f * total <= w + 0.5f)
        assertTrue("box height fits", 936f * total <= h + 0.5f)
        // Its centre is the surface's centre.
        assertEquals(w / 2f, screenOf(box.centreX, total, pageW, w, land.panX), 0.01f)
        assertEquals(h / 2f, screenOf(box.centreY, total, pageH, h, land.panY), 0.01f)
    }

    @Test
    fun `landscape - a box on the width-filled page grows to fill the wide surface`() {
        val w = 1333f
        val h = 969f
        val rest = InkViewport.restFit(w, h, landscapeFitsWhole = false)
        // A wide band 600×200 page units: the height can't decide (it would overshoot the width).
        val box = MarqueeZoom.Box(400f, 900f, 1000f, 1100f)
        val land = MarqueeZoom.landing(box, w, h, rest.base, pageW, pageH, minZ, maxZ)!!
        assertEquals(w / (600f * rest.base), land.zoom, 1e-4f)
        assertTrue(land.zoom > rest.zoom)
        assertTrue(MarqueeZoom.pastRest(land.zoom, rest.zoom))
    }

    @Test
    fun `the zoom is clamped to the surface's limits`() {
        val w = 957f
        val h = 1345f
        val base = InkViewport.fitScale(w, h)
        // A tiny box asks for far more than 16×: the ceiling holds, still centred on it.
        val tiny = MarqueeZoom.Box(700f, 900f, 710f, 910f)
        val t = MarqueeZoom.landing(tiny, w, h, base, pageW, pageH, minZ, maxZ)!!
        assertEquals(maxZ, t.zoom)
        assertEquals(w / 2f, screenOf(tiny.centreX, base * t.zoom, pageW, w, t.panX), 0.01f)
        // A box bigger than the page asks for less than the fit: the floor holds.
        val huge = MarqueeZoom.Box(-500f, -500f, pageW + 500f, pageH + 500f)
        assertEquals(minZ, MarqueeZoom.landing(huge, w, h, base, pageW, pageH, minZ, maxZ)!!.zoom)
    }

    @Test
    fun `the ceiling is 16 times the rest - and the smallest box reaches it on every posture`() {
        // Michael 2026-10-12: "Heck, 16x zoom would be meaningful" — iOS ZoomBox's maxFactor × rest.
        assertEquals(16f, SurfaceFragment.MAX_ZOOM)
        assertEquals(16f, InkViewport.ceiling(1f))
        val land = InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false)
        assertEquals(16f * land.zoom, InkViewport.ceiling(land.zoom), 1e-4f)
        assertTrue("landscape reaches past a bare 16 over the fit", InkViewport.ceiling(land.zoom) > 29f)
        // The smallest box a drag may draw (MIN_DRAG_DP of diagonal; on a 2× screen a 28px square),
        // drawn on the page at its rest: portrait, landscape, both in Normal and full screen.
        val side = MarqueeZoom.MIN_DRAG_DP * 2f / kotlin.math.sqrt(2f)
        for ((w, h) in listOf(957f to 1345f, 1072f to 1345f, 1333f to 969f, 1448f to 969f)) {
            val rest = InkViewport.restFit(w, h, landscapeFitsWhole = false)
            val page = side / rest.total
            val box = MarqueeZoom.Box(700f, 900f, 700f + page, 900f + page)
            val l = MarqueeZoom.landing(box, w, h, rest.base, pageW, pageH, minZ, InkViewport.ceiling(rest.zoom))!!
            assertEquals("${w}x$h", 16f, l.zoom / rest.zoom, 1e-3f)
            assertEquals(w / 2f, screenOf(box.centreX, rest.base * l.zoom, pageW, w, l.panX), 0.05f)
        }
    }

    @Test
    fun `plus walks from the rest to 16 times it in 30 taps, then stops - minus walks all the way back`() {
        for (rest in listOf(1f, InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false).zoom)) {
            val ceil = InkViewport.ceiling(rest)
            var z = rest
            var taps = 0
            while (true) { z = MarqueeZoom.step(z, rest, ceil, inward = true) ?: break; taps++ }
            assertEquals("1.1^29 = 15.86 < 16 ≤ 1.1^30", 30, taps)
            assertEquals(ceil, z, 1e-3f)
            assertFalse(MarqueeZoom.moves(MarqueeZoom.Choice.IN, z, rest, ceil))
            assertTrue(MarqueeZoom.moves(MarqueeZoom.Choice.OUT, z, rest, ceil))
            while (true) { z = MarqueeZoom.step(z, rest, ceil, inward = false) ?: break }
            assertEquals(rest, z)
        }
    }

    @Test
    fun `a landscape close-up at 16 times its rest keeps it across full screen`() {
        // The ⛶ carry re-reads the ceiling from the NEW rest — a bare 16 over the fit would have
        // halved it (29.3 → 16 on the B6's wider full-screen width-fill).
        val normal = InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false)
        val full = InkViewport.restFit(1448f, 969f, landscapeFitsWhole = false)
        val carried = InkViewport.zoomOverRest(InkViewport.ceiling(normal.zoom), normal, full)
        assertEquals(16f, carried / full.zoom, 1e-3f)
        // …and back.
        assertEquals(InkViewport.ceiling(normal.zoom), InkViewport.zoomOverRest(carried, full, normal), 1e-3f)
    }

    @Test
    fun `a flat drag zooms by its length, never to the ceiling`() {
        val w = 957f
        val h = 1345f
        val base = InkViewport.fitScale(w, h)
        val line = MarqueeZoom.Box(200f, 500f, 900f, 500f)   // no height at all
        val l = MarqueeZoom.landing(line, w, h, base, pageW, pageH, minZ, maxZ)!!
        assertEquals(w / (700f * base), l.zoom, 1e-4f)
        // No extent on either axis, or no surface: nothing to land.
        assertNull(MarqueeZoom.landing(MarqueeZoom.Box(5f, 5f, 5f, 5f), w, h, base, pageW, pageH, minZ, maxZ))
        assertNull(MarqueeZoom.landing(line, 0f, h, base, pageW, pageH, minZ, maxZ))
    }

    @Test
    fun `zoomed means past the rest - the landscape width-fill is the rest, not zoomed`() {
        // Portrait rests at zoom 1.
        assertFalse(MarqueeZoom.pastRest(1f, 1f))
        assertFalse(MarqueeZoom.pastRest(1.005f, 1f))   // a refit's rounding
        assertTrue(MarqueeZoom.pastRest(2f, 1f))
        // Landscape rests width-filled (zoom > 1): that is the rest, so the seat stays unboxed and
        // a tap arms the box instead of "zooming back" to where it already is.
        val rest = InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false)
        assertTrue(rest.zoom > 1.01f)
        assertFalse(MarqueeZoom.pastRest(rest.zoom, rest.zoom))
        // A pinch out below the rest (the letterbox) is not "zoomed" either.
        assertFalse(MarqueeZoom.pastRest(1f, rest.zoom))
    }

    @Test
    fun `the tap repeats the last choice - an armed box is put away first`() {
        // Michael 2026-10-12: "We can make the zoom button the last used option for one click zoom
        // in / out adjustments. Or zoom to box :)" — "tap while zoomed = back to the rest" retired.
        for (c in MarqueeZoom.Choice.entries)
            assertEquals("$c", MarqueeZoom.Tap.DISARM, MarqueeZoom.tap(armed = true, choice = c))
        assertEquals(MarqueeZoom.Tap.ARM, MarqueeZoom.tap(armed = false, choice = MarqueeZoom.Choice.BOX))
        assertEquals(MarqueeZoom.Tap.IN, MarqueeZoom.tap(armed = false, choice = MarqueeZoom.Choice.IN))
        assertEquals(MarqueeZoom.Tap.OUT, MarqueeZoom.tap(armed = false, choice = MarqueeZoom.Choice.OUT))
        // The box shows what's on: armed, or the page magnified.
        assertTrue(MarqueeZoom.seatOn(armed = true, zoomedPastRest = false))
        assertTrue(MarqueeZoom.seatOn(armed = false, zoomedPastRest = true))
        assertFalse(MarqueeZoom.seatOn(armed = false, zoomedPastRest = false))
    }

    @Test
    fun `the choice is remembered by its key - nothing stored is Zoom box`() {
        assertEquals(MarqueeZoom.Choice.BOX, MarqueeZoom.choiceFor(null))
        assertEquals(MarqueeZoom.Choice.BOX, MarqueeZoom.choiceFor("fit"))   // a key this build doesn't know
        for (c in MarqueeZoom.Choice.entries) assertEquals(c, MarqueeZoom.choiceFor(c.key))
        // The hold's order and the words both apps share (iOS ZoomBox.Choice).
        assertEquals(listOf("box", "in", "out"), MarqueeZoom.Choice.entries.map { it.key })
        assertEquals(listOf("Zoom", "Zoom +", "Zoom −"), MarqueeZoom.Choice.entries.map { it.caption })
        assertEquals(listOf("Zoom box", "Zoom + 10%", "Zoom − 10%"), MarqueeZoom.Choice.entries.map { it.label })
        assertEquals(listOf("Zoom box", "Zoom in 10%", "Zoom out 10%"), MarqueeZoom.Choice.entries.map { it.spoken })
        for (c in MarqueeZoom.Choice.entries) assertTrue("${c.caption} fits a caption", c.caption.length <= 8)
        assertEquals("zoom_seat_choice", MarqueeZoom.CHOICE_KEY)
    }

    @Test
    fun `a step is 10 percent more or 10 percent less`() {
        assertEquals(2.2f, MarqueeZoom.step(2f, 1f, maxZ, inward = true)!!, 1e-5f)
        assertEquals(2f, MarqueeZoom.step(2.2f, 1f, maxZ, inward = false)!!, 1e-5f)
        assertEquals(1.1f, MarqueeZoom.step(1f, 1f, maxZ, inward = true)!!, 1e-5f)
        // A second ＋ is ten percent of where you are, not of the rest (iOS: 1.1 → 1.21).
        assertEquals(1.21f, MarqueeZoom.step(1.1f, 1f, maxZ, inward = true)!!, 1e-5f)
        // A hair past the rest (a carried zoom) still steps — onto the rest itself.
        assertEquals(1f, MarqueeZoom.step(1.005f, 1f, maxZ, inward = false)!!)
    }

    @Test
    fun `plus then minus comes back - zoom and place`() {
        val base = InkViewport.fitScale(957f, 1345f)
        val a = MarqueeZoom.stepLanding(2f, 40f, -30f, base, 1f, maxZ, pageW, pageH, inward = true)!!
        val b = MarqueeZoom.stepLanding(a.zoom, a.panX, a.panY, base, 1f, maxZ, pageW, pageH, inward = false)!!
        assertEquals(2f, b.zoom, 1e-5f)
        assertEquals(40f, b.panX, 1e-3f)
        assertEquals(-30f, b.panY, 1e-3f)
    }

    @Test
    fun `a step near an edge never pulls the page off - the surface's own clamp takes the pan`() {
        // At 2× against the top edge (the most the pan may travel), a − about the middle asks for a
        // pan past what 1.82× allows; updateTransformMatrix's clamp (InkViewport.clampCentredPan)
        // lands it on the new edge — the page's top still at the surface's top.
        val h = 1345f
        val base = InkViewport.fitScale(957f, h)
        val top = (pageH * base * 2f - h) / 2f
        val l = MarqueeZoom.stepLanding(2f, 0f, top, base, 1f, maxZ, pageW, pageH, inward = false)!!
        val scaled = pageH * base * l.zoom
        val y = InkViewport.clampCentredPan(l.panY, scaled, h, 0f)
        assertEquals((scaled - h) / 2f, y, 0.01f)
        assertEquals(0f, screenOf(0f, base * l.zoom, pageH, h, y), 0.01f)
    }

    @Test
    fun `full screen and landscape step from their own rest`() {
        // Landscape rests width-filled (zoom > 1 over the fit): ＋ grows that, and − from one step
        // past it lands on that fill — never on the letterbox at zoom 1 below it.
        val w = 1448f
        val h = 969f
        val rest = InkViewport.restFit(w, h, landscapeFitsWhole = false)
        val up = MarqueeZoom.stepLanding(rest.zoom, 0f, 0f, rest.base, rest.zoom, maxZ, pageW, pageH, inward = true)!!
        assertEquals(rest.zoom * 1.1f, up.zoom, 1e-4f)
        val back = MarqueeZoom.stepLanding(up.zoom, up.panX, up.panY, rest.base, rest.zoom, maxZ, pageW, pageH, inward = false)!!
        assertEquals(rest.zoom, back.zoom)
        assertTrue(back.atRest)
        assertNull(MarqueeZoom.stepLanding(back.zoom, back.panX, back.panY, rest.base, rest.zoom, maxZ, pageW, pageH, inward = false))
    }

    @Test
    fun `minus stops at the rest and plus at the pinch's ceiling - a step that moves nothing is null`() {
        assertNull(MarqueeZoom.step(1f, 1f, maxZ, inward = false))
        assertEquals(1f, MarqueeZoom.step(1.05f, 1f, maxZ, inward = false)!!)   // past the rest: to it
        assertNull(MarqueeZoom.step(maxZ, 1f, maxZ, inward = true))
        assertEquals(maxZ, MarqueeZoom.step(15f, 1f, maxZ, inward = true)!!)   // the ceiling, not past
        // Landscape rests width-filled (zoom > 1): − stops there, not at the letterbox below it.
        val rest = InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false).zoom
        assertNull(MarqueeZoom.step(rest, rest, maxZ, inward = false))
        assertEquals(rest, MarqueeZoom.step(rest * 1.05f, rest, maxZ, inward = false)!!)
        assertEquals(rest * 1.3f / 1.1f, MarqueeZoom.step(rest * 1.3f, rest, maxZ, inward = false)!!, 1e-4f)
    }

    @Test
    fun `a choice moves the page unless it stands at its limit - the box always can`() {
        // Where it can't, the face dims and the hold leaves its row out (iOS ZoomBox.stepMoves).
        for (z in listOf(0.8f, 1f, 3f, maxZ)) assertTrue(MarqueeZoom.moves(MarqueeZoom.Choice.BOX, z, 1f, maxZ))
        assertFalse(MarqueeZoom.moves(MarqueeZoom.Choice.OUT, 1f, 1f, maxZ))
        assertFalse(MarqueeZoom.moves(MarqueeZoom.Choice.OUT, 1.00005f, 1f, maxZ))   // a refit's ulp is the rest
        assertTrue(MarqueeZoom.moves(MarqueeZoom.Choice.OUT, 1.2f, 1f, maxZ))
        assertTrue(MarqueeZoom.moves(MarqueeZoom.Choice.IN, 1f, 1f, maxZ))
        assertFalse(MarqueeZoom.moves(MarqueeZoom.Choice.IN, maxZ, 1f, maxZ))
        assertTrue(MarqueeZoom.moves(MarqueeZoom.Choice.IN, 15.9f, 1f, maxZ))
        // Below a landscape rest (the letterbox): − has nowhere to go, ＋ goes up to the rest.
        val rest = InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false).zoom
        assertFalse(MarqueeZoom.moves(MarqueeZoom.Choice.OUT, 1f, rest, maxZ))
        assertTrue(MarqueeZoom.moves(MarqueeZoom.Choice.IN, 1f, rest, maxZ))
    }

    @Test
    fun `a landing within the rest snap is the rest`() {
        // 1.12 ÷ 1.1 = 1.018: inside the pinch's 1.02 snap — the rest itself, never "1.8% zoomed".
        assertEquals(1f, MarqueeZoom.step(1.12f, 1f, maxZ, inward = false)!!)
        // 1.13 ÷ 1.1 = 1.027: past it — a real zoom stays.
        assertEquals(1.13f / 1.1f, MarqueeZoom.step(1.13f, 1f, maxZ, inward = false)!!, 1e-5f)
        assertEquals(1.02f, MarqueeZoom.REST_SNAP)
        assertEquals(1.10f, MarqueeZoom.STEP)
    }

    @Test
    fun `below the rest - plus lands at least on the rest, minus never zooms in`() {
        // A landscape pinch-out to the whole page (the letterbox) sits below the width-fill rest.
        val rest = InkViewport.restFit(1333f, 969f, landscapeFitsWhole = false).zoom
        assertEquals(rest, MarqueeZoom.step(1f, rest, maxZ, inward = true)!!)            // 1.1 < rest: up to it
        assertEquals(rest, MarqueeZoom.step(rest / 1.09f, rest, maxZ, inward = true)!!)  // lands inside the snap
        assertNull(MarqueeZoom.step(1f, rest, maxZ, inward = false))
    }

    @Test
    fun `ten steps in and ten out come back to the rest exactly`() {
        var z = 1f
        repeat(10) { z = MarqueeZoom.step(z, 1f, maxZ, inward = true)!! }
        assertEquals(Math.pow(1.1, 10.0).toFloat(), z, 1e-3f)
        repeat(10) { z = MarqueeZoom.step(z, 1f, maxZ, inward = false) ?: z }
        assertEquals(1f, z)
        assertNull(MarqueeZoom.step(z, 1f, maxZ, inward = false))
    }

    @Test
    fun `a step keeps the middle of what you see in the middle`() {
        val w = 957f
        val h = 1345f
        val base = InkViewport.fitScale(w, h)
        val zoom = 2f
        val panX = 120f
        val panY = -340f
        // The page point under the surface's middle now.
        val cx = InkViewport.centreOf(panX, base * zoom, pageW)
        val cy = InkViewport.centreOf(panY, base * zoom, pageH)
        for (inward in listOf(true, false)) {
            val l = MarqueeZoom.stepLanding(zoom, panX, panY, base, 1f, maxZ, pageW, pageH, inward)!!
            assertEquals(if (inward) 2.2f else 2f / 1.1f, l.zoom, 1e-5f)
            assertEquals(w / 2f, screenOf(cx, base * l.zoom, pageW, w, l.panX), 0.01f)
            assertEquals(h / 2f, screenOf(cy, base * l.zoom, pageH, h, l.panY), 0.01f)
            assertFalse(l.atRest)
        }
        // A step that lands on the rest says so — the zoom goes back to the machinery.
        val home = MarqueeZoom.stepLanding(1.1f, 0f, 0f, base, 1f, maxZ, pageW, pageH, inward = false)!!
        assertEquals(1f, home.zoom)
        assertTrue(home.atRest)
        // Nothing to do, or nothing measured: nothing lands.
        assertNull(MarqueeZoom.stepLanding(1f, 0f, 0f, base, 1f, maxZ, pageW, pageH, inward = false))
        assertNull(MarqueeZoom.stepLanding(2f, 0f, 0f, 0f, 1f, maxZ, pageW, pageH, inward = true))
    }

    @Test
    fun `the face says which - three distinct faces, Zoom box the seat's own`() {
        // iOS testTheFaceAndTheWordsSayWhichOne's twin: the seat and its full-screen cell wear the
        // tap's choice, and the box is the face the seat table gives Zoom.
        val faces = MarqueeZoom.Choice.entries.map { PanelFaces.zoomFace(it) }
        assertEquals(3, faces.toSet().size)
        assertTrue(faces.all { it != 0 })
        assertEquals(PanelFaces.face(PanelSeats.Seat.ZOOM)!!.iconRes, PanelFaces.zoomFace(MarqueeZoom.Choice.BOX))
    }

    @Test
    fun `the hold keeps all three rows and the tick, saying where a step stopped`() {
        // Zoom − chosen, the page at rest: − can't move, but its row and its ✓ stay.
        assertEquals("Zoom box", MarqueeZoom.holdRow(MarqueeZoom.Choice.BOX, chosen = false, moves = true))
        assertEquals("Zoom + 10%", MarqueeZoom.holdRow(MarqueeZoom.Choice.IN, chosen = false, moves = true))
        assertEquals("Zoom − 10% (whole page)  ✓",
            MarqueeZoom.holdRow(MarqueeZoom.Choice.OUT, chosen = true, moves = false))
        // At the ceiling: ＋ says so.
        assertEquals("Zoom + 10% (most zoom)  ✓",
            MarqueeZoom.holdRow(MarqueeZoom.Choice.IN, chosen = true, moves = false))
        assertEquals("Zoom box  ✓", MarqueeZoom.holdRow(MarqueeZoom.Choice.BOX, chosen = true, moves = true))
        // A stopped row's tap only says so; the box always moves.
        assertNull(MarqueeZoom.stoppedSay(MarqueeZoom.Choice.BOX))
        assertEquals("Already at the most zoom", MarqueeZoom.stoppedSay(MarqueeZoom.Choice.IN))
        assertEquals("Already at the whole page", MarqueeZoom.stoppedSay(MarqueeZoom.Choice.OUT))
    }

    @Test
    fun `the hold names itself to a screen reader as iOS's does`() {
        // iOS: "Hold to choose: Zoom box, Zoom in 10%, Zoom out 10%".
        assertEquals("Choose: Zoom box, Zoom in 10%, Zoom out 10%", MarqueeZoom.HOLD_SPOKEN)
    }

    @Test
    fun `the Zoom seat sits directly after Redo in the ink pages' pens, on both apps`() {
        for (p in Platform.entries) {
            val hand = PanelSeats.layout(Dress.DAY, p)!!.hand
            assertEquals("$p", hand.indexOf(PanelSeats.Seat.REDO) + 1, hand.indexOf(PanelSeats.Seat.ZOOM))
        }
        assertEquals("Zoom", PanelSeats.Seat.ZOOM.caption)
        assertEquals("zoom", PanelSeats.Seat.ZOOM.id)
        assertEquals(PanelSeats.Kind.WRITE, PanelSeats.Seat.ZOOM.kind)
        // Only the ink pages wear it.
        for (d in Dress.entries) if (d != Dress.DAY) for (p in Platform.entries)
            assertTrue("${d.id}/$p", PanelSeats.layout(d, p)?.all?.contains(PanelSeats.Seat.ZOOM) != true)
    }
}
