package com.toolsboox

import com.toolsboox.ot.InkViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full screen gives the drawing surface the column's room, and the page RE-CENTRES and fits it
 * (Michael 2026-10-11: "Recentering/zooming after closing the tuck panel makes sense" — his yes to
 * "when you tap ⛶, the page re-centres and zooms to fill the space the panel left; opening the
 * panel puts it back where it was"). B6 portrait fits the WHOLE page — the chosen default, pending
 * Michael's pick between fit-page and fit-width — and landscape fits the width and keeps its top
 * row. Pinned on the B6 numbers from the -307…-311 field traces: a 115px column,
 * a 1072×1345 portrait row (Normal surface 957×1345) and a 1448×969 landscape one (1333×969).
 */
class TuckPanelViewportTest {

    private val row = 1448
    private val slot = 115
    private val normalW = row - slot

    private val pageW = 1404f
    private val pageH = 1872f

    @Test
    fun `the room is lent only once the surface really reaches into the slot`() {
        // Docked left. Normal: the surface starts where the column ends — nothing lent.
        assertEquals(0 to 0, InkViewport.borrowedRoom(slot, row, row, slot, dockedLeft = true))
        // Full screen: the surface runs from the edge — the slot is lent on the left.
        assertEquals(slot to 0, InkViewport.borrowedRoom(0, row, row, slot, dockedLeft = true))
        // Docked right: the same on the other side.
        assertEquals(0 to 0, InkViewport.borrowedRoom(0, normalW, row, slot, dockedLeft = false))
        assertEquals(0 to slot, InkViewport.borrowedRoom(0, row, row, slot, dockedLeft = false))
        // Never more than the slot, never more than the surface, never negative.
        assertEquals(0 to 0, InkViewport.borrowedRoom(400, row, row, slot, dockedLeft = true))
        assertEquals(40 to 0, InkViewport.borrowedRoom(0, 40, row, slot, dockedLeft = true))
    }

    /**
     * One surface as SurfaceFragment lays it: [left] on the glass, [w]×[h], the page resting in
     * the whole of it (updateTransformMatrix: centred origin, clampEdge with the surface as frame).
     */
    private class Surface(val left: Float, val w: Float, val h: Float) {
        fun rest(landscapeFitsWhole: Boolean = false) = InkViewport.restFit(w, h, landscapeFitsWhole)
        fun fit() = InkViewport.fitScale(w, h)

        /** The page's leading edge on the GLASS at [total] scale and a centred-origin [pan]. */
        fun glassEdge(total: Float, pan: Float, overscroll: Float = 0f): Float {
            val page = 1404f * total
            val baseOff = (w - page) / 2f
            return left + InkViewport.clampEdge(baseOff + pan, page, w, 0f, w,
                overscroll, overscroll, restAtStart = w > h)
        }

        /** The clamped horizontal pan, as updateTransformMatrix writes it back. */
        fun clampPanX(total: Float, pan: Float): Float = glassEdge(total, pan) - left - (w - 1404f * total) / 2f

        /** The clamped vertical pan, as updateTransformMatrix writes it back. */
        fun clampPanY(total: Float, pan: Float, overscroll: Float = 0f): Float =
            InkViewport.clampCentredPan(pan, 1872f * total, h, overscroll)

        /** The page point under this surface's centre — the matrix inverted, independent of
         *  InkViewport: screen = c·total + (len − page·total)/2 + pan. */
        fun centreX(total: Float, pan: Float): Float = (w / 2f - (w - 1404f * total) / 2f - pan) / total
        fun centreY(total: Float, pan: Float): Float = (h / 2f - (h - 1872f * total) / 2f - pan) / total

        /** The page row at this surface's top edge. */
        fun topRow(total: Float, pan: Float): Float = (0f - (h - 1872f * total) / 2f - pan) / total
    }

    // B6 portrait: a 1072px row; Normal hands 115px of it to the column on either side.
    private val portraitNormalLeftDock = Surface(slot.toFloat(), 957f, 1345f)
    private val portraitNormalRightDock = Surface(0f, 957f, 1345f)
    private val portraitFull = Surface(0f, 1072f, 1345f)   // either dock: the column is GONE

    // B6 landscape: the -307 trace's 1448px row.
    private val landNormalLeftDock = Surface(slot.toFloat(), normalW.toFloat(), 969f)
    private val landNormalRightDock = Surface(0f, normalW.toFloat(), 969f)
    private val landFull = Surface(0f, row.toFloat(), 969f)

    @Test
    fun `closing re-centres the resting page and fits it to the whole surface, both docks`() {
        // PORTRAIT: the whole page, bigger and centred — nothing below the fold.
        val docked = portraitNormalLeftDock.rest()
        val full = portraitFull.rest()
        assertEquals(0.6816f, docked.total, 1e-4f)   // 957/1404: width-bound in Normal
        assertEquals(0.7185f, full.total, 1e-4f)     // 1345/1872: height-bound once the room is lent
        assertEquals(1f, full.zoom, 0f)              // the whole page, not a width-fill
        assertEquals(1345f, pageH * full.total, 0.01f)
        assertEquals(1008.75f, pageW * full.total, 0.01f)
        // Normal: flush against the column docked left, against the screen's edge docked right…
        assertEquals(slot.toFloat(), portraitNormalLeftDock.glassEdge(docked.total, 0f), 0.01f)
        assertEquals(0f, portraitNormalRightDock.glassEdge(docked.total, 0f), 0.01f)
        // …full screen: centred in the 1072px row, ~31.6px a side, whichever side it docks.
        assertEquals((1072f - 1008.75f) / 2f, portraitFull.glassEdge(full.total, 0f), 0.01f)
        // A stray pan can't slide a resting page off centre.
        for (pan in listOf(-400f, -31f, 31f, 400f)) {
            assertEquals(31.625f, portraitFull.glassEdge(full.total, pan), 0.01f)
            assertEquals(0f, portraitFull.clampPanY(full.total, pan), 0f)
        }

        // LANDSCAPE: fills the new width, leading edge at the surface's.
        val ld = landNormalLeftDock.rest()
        val lf = landFull.rest()
        assertEquals(0.9494f, ld.total, 1e-4f)   // 1333/1404
        assertEquals(1.0313f, lf.total, 1e-4f)   // 1448/1404
        assertEquals(row.toFloat(), pageW * lf.total, 0.01f)
        assertEquals(slot.toFloat(), landNormalLeftDock.glassEdge(ld.total, 0f), 0.01f)
        assertEquals(0f, landNormalRightDock.glassEdge(ld.total, 0f), 0.01f)
        assertEquals(0f, landFull.glassEdge(lf.total, 0f), 0.01f)

        // A surface that letterboxes landscape (the today dashboard) keeps its whole page.
        val box = landFull.rest(landscapeFitsWhole = true)
        assertEquals(1f, box.zoom, 0f)
        assertEquals(969f, pageH * box.total, 0.01f)
    }

    @Test
    fun `a hand zoom keeps its magnification over the new fit and its centred point`() {
        val zoom = 2.0f
        for (normal in listOf(portraitNormalLeftDock, portraitNormalRightDock)) {
            val oldTotal = normal.fit() * zoom
            val oldPanX = normal.clampPanX(oldTotal, -120f)
            val oldPanY = normal.clampPanY(oldTotal, 300f)
            val cx = InkViewport.centreOf(oldPanX, oldTotal, pageW)
            val cy = InkViewport.centreOf(oldPanY, oldTotal, pageH)
            // The anchor really is the point under the centre (the matrix, inverted by hand).
            assertEquals(normal.centreX(oldTotal, oldPanX), cx, 0.01f)
            assertEquals(normal.centreY(oldTotal, oldPanY), cy, 0.01f)

            // ⛶ closes: the fit grows (0.6816 → 0.7185), the magnification over it holds.
            val newTotal = portraitFull.fit() * zoom
            assertEquals(0.7185f / 0.6816f, newTotal / oldTotal, 1e-3f)
            val newPanX = portraitFull.clampPanX(newTotal, InkViewport.panCentring(cx, newTotal, pageW))
            val newPanY = portraitFull.clampPanY(newTotal, InkViewport.panCentring(cy, newTotal, pageH))
            // The clamp leaves it be, and the same page point sits under the centre.
            assertEquals(InkViewport.panCentring(cx, newTotal, pageW), newPanX, 0.01f)
            assertEquals(cx, portraitFull.centreX(newTotal, newPanX), 0.01f)
            assertEquals(cy, portraitFull.centreY(newTotal, newPanY), 0.01f)
        }
    }

    @Test
    fun `opening restores the docked fit exactly`() {
        for ((normal, dockEdge) in listOf(portraitNormalLeftDock to slot.toFloat(), portraitNormalRightDock to 0f)) {
            // The resting whole page through refitForRoom's rest path (re-derive, keep the top
            // row, clamp), out to full screen and back: its scale and its spot on the glass return.
            val restDocked = normal.rest().total
            val restFull = portraitFull.rest().total
            val restPanFull = portraitFull.clampPanY(restFull,
                InkViewport.panKeepingTopRow(restDocked, 0f, restFull, 1345f))
            val restPanBack = normal.clampPanY(restDocked,
                InkViewport.panKeepingTopRow(restFull, restPanFull, restDocked, 1345f))
            assertEquals(0f, restPanFull, 0f)
            assertEquals(0f, restPanBack, 0f)
            assertEquals(31.625f, portraitFull.glassEdge(restFull, 0f), 0.01f)
            assertEquals(dockEdge, normal.glassEdge(normal.rest().total, 0f), 0.01f)

            // A hand's close-up: same magnification, same centred point, same pan.
            val zoom = 2.5f
            val t0 = normal.fit() * zoom
            val px0 = normal.clampPanX(t0, 210f)
            val py0 = normal.clampPanY(t0, -480f)
            val t1 = portraitFull.fit() * zoom
            val px1 = portraitFull.clampPanX(t1,
                InkViewport.panCentring(InkViewport.centreOf(px0, t0, pageW), t1, pageW))
            val py1 = portraitFull.clampPanY(t1,
                InkViewport.panCentring(InkViewport.centreOf(py0, t0, pageH), t1, pageH))
            val t2 = normal.fit() * zoom
            val px2 = normal.clampPanX(t2,
                InkViewport.panCentring(InkViewport.centreOf(px1, t1, pageW), t2, pageW))
            val py2 = normal.clampPanY(t2,
                InkViewport.panCentring(InkViewport.centreOf(py1, t1, pageH), t2, pageH))
            assertEquals(t0, t2, 0f)
            assertEquals(px0, px2, 0.01f)
            assertEquals(py0, py2, 0.01f)
            assertEquals(normal.glassEdge(t0, px0), normal.glassEdge(t2, px2), 0.01f)
        }
        // Landscape, scrolled down the day: out to full screen and back lands on the same row.
        for (normal in listOf(landNormalLeftDock, landNormalRightDock)) {
            val t0 = normal.rest().total
            val p0 = normal.clampPanY(t0, -333f)
            val t1 = landFull.rest().total
            val p1 = landFull.clampPanY(t1, InkViewport.panKeepingTopRow(t0, p0, t1, 969f))
            val p2 = normal.clampPanY(t0, InkViewport.panKeepingTopRow(t1, p1, t0, 969f))
            assertEquals(p0, p2, 0.01f)
            assertEquals(0f, landFull.glassEdge(t1, 0f), 0.01f)
            assertEquals(normal.left, normal.glassEdge(normal.rest().total, 0f), 0.01f)
        }
    }

    @Test
    fun `a landscape refit keeps its top row`() {
        val t0 = landNormalLeftDock.rest().total
        val t1 = landFull.rest().total
        // At the page's top it stays at the top: one max pan maps onto the other.
        val top0 = (pageH * t0 - 969f) / 2f
        val top1 = (pageH * t1 - 969f) / 2f
        assertEquals(top1, InkViewport.panKeepingTopRow(t0, top0, t1, 969f), 0.01f)
        assertEquals(0f, landFull.topRow(t1, top1), 0.01f)
        // Scrolled down the day, the row at the top edge is the same row after the refit, inside
        // the new bounds, so the clamp leaves it there.
        for (pan in listOf(top0, 400f, 0f, -250f, -top0 + 1f)) {
            val p0 = landNormalLeftDock.clampPanY(t0, pan)
            val p1 = InkViewport.panKeepingTopRow(t0, p0, t1, 969f)
            assertEquals(p1, landFull.clampPanY(t1, p1), 0.01f)
            assertEquals("pan=$pan", landNormalLeftDock.topRow(t0, p0), landFull.topRow(t1, p1), 0.01f)
        }
        // Nothing to scale from is no move.
        assertEquals(42f, InkViewport.panKeepingTopRow(0f, 42f, t1, 969f), 0f)
    }

    @Test
    fun `a landscape close-up still fills the new width, and comes back exactly`() {
        // A two-finger scroll down the width-filled day nudges the span and takes the zoom by
        // hand (review 2026-10-11): the bare zoom kept the page 1334px wide on a 1448px surface,
        // because landscape's fit is height-bound and doesn't move on ⛶.
        assertEquals(landNormalLeftDock.fit(), landFull.fit(), 0f)
        for (normal in listOf(landNormalLeftDock, landNormalRightDock)) {
            val r0 = normal.rest()
            val r1 = landFull.rest()
            val zoom = r0.zoom * 1.01f                     // a hair past the width-fill, by hand
            val t0 = normal.fit() * zoom
            assertEquals(normal.w * 1.01f, pageW * t0, 0.05f)
            val z1 = InkViewport.zoomOverRest(zoom, r0, r1)
            val t1 = landFull.fit() * z1
            // Full screen: the same 1% over the NEW width-fill — the page covers the surface.
            assertEquals(row * 1.01f, pageW * t1, 0.05f)
            assertEquals(r1.total / r0.total, t1 / t0, 1e-5f)
            // The centred point holds through the clamp (pan inside the new bounds).
            val px0 = normal.clampPanX(t0, -4f)
            val py0 = normal.clampPanY(t0, -200f)
            val cx = InkViewport.centreOf(px0, t0, pageW)
            val cy = InkViewport.centreOf(py0, t0, pageH)
            val px1 = landFull.clampPanX(t1, InkViewport.panCentring(cx, t1, pageW))
            val py1 = landFull.clampPanY(t1, InkViewport.panCentring(cy, t1, pageH))
            assertEquals(cx, landFull.centreX(t1, px1), 0.01f)
            assertEquals(cy, landFull.centreY(t1, py1), 0.01f)
            val edge = landFull.glassEdge(t1, px1)
            assertTrue("edge=$edge", edge <= 0f && edge + pageW * t1 >= row - 0.01f)
            // Opening runs the ratio back: the docked zoom returns exactly.
            assertEquals(zoom, InkViewport.zoomOverRest(z1, r1, r0), 1e-6f)
        }
        // Portrait rests at zoom 1 on both sides: the hand's zoom is kept as it is.
        assertEquals(2.5f, InkViewport.zoomOverRest(2.5f, portraitNormalLeftDock.rest(), portraitFull.rest()), 0f)
        // A letterboxing surface (zoom 1 rests) keeps the bare zoom too.
        assertEquals(1.5f, InkViewport.zoomOverRest(1.5f,
            landNormalLeftDock.rest(landscapeFitsWhole = true), landFull.rest(landscapeFitsWhole = true)), 0f)
        // A storm through an intermediate width multiplies out to the direct answer.
        val mid = Surface(0f, 1390f, 969f)
        val via = InkViewport.zoomOverRest(
            InkViewport.zoomOverRest(2f, landNormalLeftDock.rest(), mid.rest()), mid.rest(), landFull.rest())
        assertEquals(InkViewport.zoomOverRest(2f, landNormalLeftDock.rest(), landFull.rest()), via, 1e-5f)
    }

    @Test
    fun `a height-bound fit an ulp past the surface rests centred, never low`() {
        // The full-screen portrait fit is sh/1872; on some heights 1872·(sh/1872) lands a float
        // step past sh (review 2026-10-11). With the day page's half-screen overscroll an exact
        // compare let the +36px top-row shift stand: the page rested low with its foot cut off.
        var overshooting = 0
        for (h in 900..2100) {
            val sh = h.toFloat()
            val full = InkViewport.restFit(sh * 0.8f, sh, landscapeFitsWhole = false)
            val page = pageH * full.total
            if (page > sh) overshooting++
            for (pan in listOf(-sh, -36.4f, 36.4f, sh)) {
                assertEquals("h=$h pan=$pan", 0f, InkViewport.clampCentredPan(pan, page, sh, sh / 2f), 0f)
            }
            assertEquals("h=$h", true, InkViewport.fits(page, sh))
        }
        // The case is real, not hypothetical (B6's 1345 is exact; dozens of heights are not).
        assertEquals(true, overshooting > 0)
        // A page really taller than the surface still scrolls, overscroll included.
        assertEquals(-(1600f - 1345f) / 2f, InkViewport.clampCentredPan(-300f, 1600f, 1345f, 0f), 0f)
        assertEquals(-300f, InkViewport.clampCentredPan(-300f, 1600f, 1345f, 672.5f), 0f)
    }

    @Test
    fun `a fit an ulp wider than its frame still rests and never slides`() {
        // Float rounding: the width-fill often lands an ulp past the surface (review 2026-10-11).
        for (slack in listOf(1e-4f, 1e-3f, 0.4f)) {
            for (surface in listOf(landNormalLeftDock, landNormalRightDock, landFull)) {
                val page = surface.w + slack
                for (pan in listOf(-10_000f, -60f, 0f, 60f, 10_000f)) {
                    // A sideways drift (any pan, even with the day page's half-screen overscroll)
                    // leaves it at its rest: the column's edge docked left, the screen's docked
                    // right and in full screen.
                    for (ovs in listOf(0f, surface.w / 2f)) {
                        assertEquals(surface.left, surface.glassEdge(page / 1404f, pan, ovs), 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `a toggle is never read as a turn, a real rotation always is`() {
        // ⛶ on the B6: the Normal frame holds 1333x969 while the surface goes 1333 -> 1448.
        assertEquals(false, InkViewport.turned(normalW, 969, normalW, 969))
        // A near-square window (split screen) where W - slot < H < W: the surface's own shape
        // would turn on ⛶ (1000x1050 -> 1115x1050), its Normal frame never does.
        assertEquals(false, InkViewport.turned(1000, 1050, 1000, 1050))
        // A real rotation turns the Normal frame too, in Normal and in full screen.
        assertEquals(true, InkViewport.turned(normalW, 969, 969, 1333))
        assertEquals(true, InkViewport.turned(969 - slot, 1333, normalW, 969))
        // No size yet is no turn.
        assertEquals(false, InkViewport.turned(0, 0, normalW, 969))
    }

    @Test
    fun `with no room lent the clamp is the Normal clamp exactly`() {
        val view = 1333f
        for (page in listOf(900f, 1333f, 1800f, 2600f)) {
            for (pan in listOf(-900f, -20f, 0f, 20f, 900f)) {
                for (ovs in listOf(0f, 666f)) {
                    val baseOff = (view - page) / 2f
                    val got = InkViewport.clampEdge(baseOff + pan, page, view, 0f, view, ovs, ovs,
                        restAtStart = true) - baseOff
                    // The clamp SurfaceFragment carried before the home frame.
                    val want = if (page > view) {
                        val maxPan = (page - view) / 2f + ovs
                        pan.coerceIn(-maxPan, maxPan)
                    } else -baseOff
                    assertEquals("page=$page pan=$pan ovs=$ovs", want, got, 0.01f)
                }
            }
        }
    }
}
