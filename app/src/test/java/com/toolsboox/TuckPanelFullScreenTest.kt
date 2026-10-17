package com.toolsboox

import com.toolsboox.ot.TuckPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ink column beneath the date strip, pinned on the real devices (plan v6 S1.2, Michael
 * 2026-10-10): saved tucked carries over as full screen per surface; the column stands beneath the
 * strip ("The date strip should take up the whole top, the full should start beneath it"), and
 * where its seats outrun it the column scrolls — zero folds, captions on (Michael 2026-10-10:
 * "scrollable if there are more buttons").
 */
class TuckPanelFullScreenTest {

    @Test
    fun `saved tucked becomes full screen and the new key wins once written`() {
        // Never tucked, never toggled: Normal.
        assertFalse(TuckPanel.migratedFull(hasFull = false, full = false, hasOpen = false, open = true))
        // Left open: Normal. Left tucked: full screen (N22, 1:1).
        assertFalse(TuckPanel.migratedFull(hasFull = false, full = false, hasOpen = true, open = true))
        assertTrue(TuckPanel.migratedFull(hasFull = false, full = false, hasOpen = true, open = false))
        // Once the surface has its own flag, the old tuck key no longer speaks.
        assertFalse(TuckPanel.migratedFull(hasFull = true, full = false, hasOpen = true, open = false))
        assertTrue(TuckPanel.migratedFull(hasFull = true, full = true, hasOpen = true, open = true))
    }

    @Test
    fun `the first fit already stands beneath the date strip`() {
        // A real B6 portrait screen (1448px) under a 50dp strip at 1.875: the column is 1355px.
        assertEquals(1355, TuckPanel.columnBeneathStrip(1448, (50 * 1.875f).toInt()))
        // No strip (a gutter rail): the whole height, as before.
        assertEquals(1448, TuckPanel.columnBeneathStrip(1448, 0))
        // Never negative, whatever a stale measure says.
        assertEquals(0, TuckPanel.columnBeneathStrip(40, 94))
        assertEquals(1448, TuckPanel.columnBeneathStrip(1448, -5))
    }

    /** The day column: ⛶, Hub, Today (directly under Hub — Michael 2026-10-10: "I would like today
     *  below hub on today in the tuck panel"), Pen, Eraser, Lasso, Synth, Undo, Redo, More, Up,
     *  Down, Sections, Side. */
    private val daySeats = 14

    /** The almanac column: ⛶, Hub, Pen, Eraser, Lasso, Undo, Redo, More, Up, Down / notes,
     *  Present, Side. */
    private val almanacSeats = 12

    /** One device: its density and its column height beneath the 50dp strip. */
    private class Device(val density: Float, screenPx: Int, statusBarDp: Int = 0) {
        fun p(dp: Int) = (dp * density).toInt()
        val columnPx = TuckPanel.columnBeneathStrip(screenPx - p(statusBarDp), p(50))
        fun fit(seats: Int) = TuckPanel.fitColumn(columnPx, density, p(40), seats, labelled = true,
            comfortPx = p(TuckPanel.PALM_COMFORT_DP))
        /** What [seats] of [f]'s cells take, the cluster gap included. */
        fun used(seats: Int, f: TuckPanel.Fit) =
            seats * (f.cellPx + TuckPanel.cellMarginsPx(density)) + p(TuckPanel.CLUSTER_GAP_DP)
    }

    // The B6 Pro is 1072 × 1448 at 1.875; the Palma 2 Pro 824 × 1648 at 2.0 (412dp across).
    private val b6Portrait = Device(1.875f, 1448)
    private val b6Landscape = Device(1.875f, 1072)
    private val palmaPortrait = Device(2.0f, 1648)
    private val palmaLandscape = Device(2.0f, 824)

    @Test
    fun `the fit counts the margins the cells really take`() {
        // dp(1) above + dp(1) below, each rounded down: 2px on a B6 (px(2) would say 3), 4px on a Palma.
        assertEquals(2, TuckPanel.cellMarginsPx(1.875f))
        assertEquals(4, TuckPanel.cellMarginsPx(2.0f))
    }

    @Test
    fun `a portrait B6 fits every seat labelled with a comfortable foot`() {
        val d = b6Portrait
        for (seats in listOf(daySeats, almanacSeats)) {
            val f = d.fit(seats)
            assertEquals("40dp cells, not 48dp ones that leave a few dp of foot", d.p(40), f.cellPx)
            assertEquals(9f, f.captionSp)
            assertFalse(f.scrolls)
            val foot = d.columnPx - d.used(seats, f)
            assertTrue("foot ${foot / d.density}dp", foot >= d.p(TuckPanel.PALM_COMFORT_DP))
        }
        // A 24dp status bar costs the foot ~24dp: still labelled 40dp cells, still no scroll.
        val bar = Device(1.875f, 1448, statusBarDp = 24)
        assertEquals(bar.p(40), bar.fit(daySeats).cellPx)
        assertFalse(bar.fit(daySeats).scrolls)
    }

    @Test
    fun `a landscape B6 scrolls the day column with captions on and fits the almanac`() {
        val d = b6Landscape
        // 14 labelled 40dp seats outrun the 979px column: the column scrolls, captions stay.
        // (It used to fold ▲ ▼ ⇄ into More·3.)
        val day = d.fit(daySeats)
        assertEquals(d.p(40), day.cellPx)
        assertEquals(9f, day.captionSp)
        assertTrue(day.scrolls)
        // The almanac's twelve fit as they are, labelled.
        val almanac = d.fit(almanacSeats)
        assertEquals(9f, almanac.captionSp)
        assertFalse(almanac.scrolls)
        // With a 24dp status bar the almanac scrolls too — still captioned.
        val bar = Device(1.875f, 1072, statusBarDp = 24)
        assertTrue(bar.fit(almanacSeats).scrolls)
        assertEquals(9f, bar.fit(almanacSeats).captionSp)
    }

    @Test
    fun `a Palma fits in portrait and scrolls in landscape, captions on`() {
        for (seats in listOf(daySeats, almanacSeats)) {
            val portrait = palmaPortrait.fit(seats)
            assertTrue(portrait.captionSp >= 9f)
            assertFalse("portrait seats=$seats", portrait.scrolls)
            // Landscape: 412dp less the strip is a 362dp column — both columns scroll. (The day
            // column used to fold seven seats here, the almanac five.)
            val landscape = palmaLandscape.fit(seats)
            assertEquals(palmaLandscape.p(40), landscape.cellPx)
            assertEquals(9f, landscape.captionSp)
            assertTrue("landscape seats=$seats", landscape.scrolls)
        }
    }

    @Test
    fun `every ink column keeps its captions and scrolls only when it must`() {
        for (d in listOf(b6Portrait, b6Landscape, palmaPortrait, palmaLandscape)) {
            for (seats in listOf(daySeats, almanacSeats)) {
                val f = d.fit(seats)
                assertTrue(f.captionSp > 0f)
                assertEquals(d.used(seats, f) > d.columnPx, f.scrolls)
            }
        }
    }
}
