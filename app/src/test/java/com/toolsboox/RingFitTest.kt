package com.toolsboox

import com.toolsboox.ot.RingFit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circling a task on the day page.
 *
 * The Tasks panel is 16 rows of roughly 470×50 in canvas space. A loop drawn round one row is a
 * 9:1 ellipse — and the old fit measured distance from the centroid against a mean, so those
 * radii ran 25→235 and no band threshold could accept it. Circle-to-lasso worked everywhere
 * except the one place a list of short wide rows makes you reach for it.
 */
class RingFitTest {

    /** An ellipse of [w]×[h] about ([cx],[cy]), sampled [n] times, optionally left open. */
    private fun ellipse(
        cx: Float, cy: Float, w: Float, h: Float, n: Int = 40, sweep: Float = 360f
    ): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(n); val ys = FloatArray(n)
        for (i in 0 until n) {
            val t = (sweep * i / (n - 1)) * PI.toFloat() / 180f
            xs[i] = cx + w / 2f * cos(t)
            ys[i] = cy + h / 2f * sin(t)
        }
        return xs to ys
    }

    // --- the case that was broken ---------------------------------------------------------------

    @Test
    fun `a loop round one task row reads as a ring`() {
        // Tasks column geometry: ~470 wide, one 50px row, circled with a little margin.
        val (xs, ys) = ellipse(cx = 965f, cy = 136f, w = 470f, h = 56f)
        val r = RingFit.fit(xs, ys)
        assertTrue("bandFraction=${r.bandFraction} coverage=${r.coverageDegrees}", r.isRing)
    }

    @Test
    fun `a loop round three task rows reads as a ring`() {
        val (xs, ys) = ellipse(cx = 965f, cy = 186f, w = 470f, h = 156f)
        assertTrue(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `an ordinary round loop still reads as a ring`() {
        val (xs, ys) = ellipse(cx = 700f, cy = 900f, w = 300f, h = 300f)
        assertTrue(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `a wobbly hand-drawn ring still reads as a ring`() {
        val (xs, ys) = ellipse(cx = 700f, cy = 900f, w = 300f, h = 240f)
        // Push every third point in and out — the wobble a real hand leaves.
        for (i in xs.indices step 3) { xs[i] *= 1.06f; ys[i] *= 0.95f }
        assertTrue(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `a ring left open at the top still reads as a ring`() {
        val (xs, ys) = ellipse(cx = 965f, cy = 136f, w = 470f, h = 56f, sweep = 300f)
        assertTrue(RingFit.fit(xs, ys).isRing)
    }

    // --- and the things that must NOT become lassos ---------------------------------------------

    @Test
    fun `a horizontal line is not a ring`() {
        val n = 30
        val xs = FloatArray(n) { 700f + it * 12f }
        val ys = FloatArray(n) { 900f }
        assertFalse(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `a line with a slight bow is not a ring`() {
        val n = 30
        val xs = FloatArray(n) { 700f + it * 12f }
        val ys = FloatArray(n) { 900f + (it - 15) * (it - 15) * 0.05f }
        assertFalse(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `a back-and-forth scribble is not a ring`() {
        val n = 40
        val xs = FloatArray(n) { 700f + it * 10f }
        val ys = FloatArray(n) { 900f + if (it % 2 == 0) 18f else -18f }
        assertFalse(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `a tiny loop below the size floor is not a ring`() {
        val (xs, ys) = ellipse(cx = 700f, cy = 900f, w = 30f, h = 26f)
        val r = RingFit.fit(xs, ys)
        assertFalse(r.sizeOk)
        assertFalse(r.isRing)
    }

    @Test
    fun `too few points is not a ring`() {
        val xs = floatArrayOf(0f, 10f, 20f)
        val ys = floatArrayOf(0f, 10f, 0f)
        assertFalse(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `an L-shaped corner is not a ring`() {
        val n = 30
        val xs = FloatArray(n) { if (it < 15) 700f + it * 14f else 700f + 14 * 14f }
        val ys = FloatArray(n) { if (it < 15) 900f else 900f + (it - 15) * 14f }
        assertFalse(RingFit.fit(xs, ys).isRing)
    }

    @Test
    fun `mismatched coordinate arrays are refused rather than crashing`() {
        assertFalse(RingFit.fit(FloatArray(10), FloatArray(3)).isRing)
    }
}
