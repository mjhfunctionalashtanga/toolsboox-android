package com.toolsboox.ot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Does a stroke read as a ring drawn round something?
 *
 * The previous test measured each point's distance from the centroid and asked how many sat near
 * the mean — a test for a CIRCLE. People do not draw circles round things; they draw round the
 * shape of the thing. Circling one row of the Tasks panel means a loop about 470 wide and 50 tall,
 * whose radii run from roughly 25 at the flat sides to 235 at the ends. No threshold on the spread
 * of those numbers can be satisfied by a genuine ring and refused by ordinary handwriting, so
 * circle-to-lasso simply did not work in the tasks strip, which is exactly where a list of short
 * wide rows makes you want it most.
 *
 * Normalising by the bounding box first — dividing x by half its width and y by half its height —
 * turns any ellipse into the unit circle, and the same band test then measures what it was always
 * meant to measure: *is this loop a consistent distance around its own centre*, whatever shape
 * that centre's surroundings are.
 *
 * A straight line normalises to something degenerate rather than a ring, but the guard against
 * that is angular coverage, not the band: a line's points bear only two ways from their centroid,
 * so it covers ~30° of the 270° required. That has always been the real discriminator; the band
 * was only ever meant to reject wobble.
 */
object RingFit {

    /** How far from the mean normalised radius a point may sit and still count as "on the ring". */
    const val BAND = 0.45f

    /** Fraction of points that must sit within [BAND]. */
    const val BAND_FRACTION = 0.55f

    /** Degrees the stroke must WIND about its centre — a ring nets ±360, a bow nets ~0. */
    const val COVERAGE_DEGREES = 270f

    /**
     * Endpoint gap, as a multiple of the mean radius, below which the loop counts as closed.
     *
     * Measured against real gesture shapes rather than chosen: rings come in at 0.00 (joined) to
     * 1.02 (left 60° open), and every non-ring at 2.45 or worse — a bow 2.56, a scribble 2.45, an
     * L-corner 3.25, a straight line 3.87. 1.5 sits in the empty middle with room either side.
     */
    const val CLOSURE_RATIO = 1.5f

    /** Below this bounding-box diagonal it is a degenerate loop, not a gesture. */
    const val MIN_DIAGONAL = 60f

    /** Fewer points than this and there is nothing to fit — a Boox capture can be sparse. */
    const val MIN_POINTS = 6

    data class Result(
        /** Reads as a ring on shape alone. Enclosure of other ink is judged separately. */
        val isRing: Boolean,
        val sizeOk: Boolean,
        val closedish: Boolean,
        val bandFraction: Float,
        val coverageDegrees: Float,
        val closureOverRadius: Float,
        val diagonal: Float
    )

    private val EMPTY = Result(false, false, false, 0f, 0f, 0f, 0f)

    fun fit(xs: FloatArray, ys: FloatArray): Result {
        val n = xs.size
        if (n < MIN_POINTS || ys.size != n) return EMPTY

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var sumX = 0f; var sumY = 0f
        for (i in 0 until n) {
            if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i]
            sumX += xs[i]; sumY += ys[i]
        }
        val diagonal = hypot(maxX - minX, maxY - minY)
        val sizeOk = diagonal >= MIN_DIAGONAL
        val cx = sumX / n
        val cy = sumY / n

        // Normalise by the bounding half-extents so an ellipse of any aspect becomes a circle.
        // The floor keeps a perfectly flat loop from dividing by zero; coverage rejects it anyway.
        val halfW = ((maxX - minX) / 2f).coerceAtLeast(1f)
        val halfH = ((maxY - minY) / 2f).coerceAtLeast(1f)

        val radii = FloatArray(n)
        var sumR = 0f
        for (i in 0 until n) {
            val nx = (xs[i] - cx) / halfW
            val ny = (ys[i] - cy) / halfH
            val r = hypot(nx, ny)
            radii[i] = r; sumR += r
        }
        val rBar = sumR / n
        if (rBar < 0.01f) return Result(false, sizeOk, false, 0f, 0f, 0f, diagonal)

        val band = BAND * rBar
        val bandFraction = radii.count { abs(it - rBar) <= band }.toFloat() / n

        // How far the stroke WINDS about its own centre, signed and unwrapped — not how many
        // compass bins its points happen to land in.
        //
        // Spread is not winding. A shallow bow drawn under a word puts points all round the
        // clock once you normalise its 348×11 bounding box, so a bin-counting test called it a
        // ring; but it sweeps out and comes back, netting near zero. A real ring nets ±360°. The
        // distinction matters more than the one it replaces, because a false lasso interrupts
        // you mid-sentence whereas a missed one just means drawing the loop again.
        //
        // Bearings are taken in NORMALISED space so a flat ellipse's points spread evenly round
        // the clock instead of bunching at its two ends.
        var sweep = 0f
        var previous = atan2((ys[0] - cy) / halfH, (xs[0] - cx) / halfW)
        for (i in 1 until n) {
            val ang = atan2((ys[i] - cy) / halfH, (xs[i] - cx) / halfW)
            var d = ang - previous
            // Unwrap: the shortest way round is the way the hand actually went.
            if (d > PI.toFloat()) d -= 2f * PI.toFloat()
            if (d < -PI.toFloat()) d += 2f * PI.toFloat()
            sweep += d
            previous = ang
        }
        val coverageDegrees = abs(sweep) * 180f / PI.toFloat()

        val closureDist = hypot((xs[n - 1] - xs[0]) / halfW, (ys[n - 1] - ys[0]) / halfH)
        val closureOverRadius = closureDist / rBar
        // AND, not OR. It used to accept a loop that merely swept far enough even if its ends
        // finished nowhere near each other, and a shallow bow drawn under a word does exactly
        // that: normalised, it winds 280° about a centroid sitting inside its curve. Winding
        // alone cannot tell a ring from a bow; where the hand ENDED can.
        val closedish = closureOverRadius <= CLOSURE_RATIO

        val isRing = sizeOk &&
            bandFraction >= BAND_FRACTION &&
            coverageDegrees >= COVERAGE_DEGREES &&
            closedish

        return Result(isRing, sizeOk, closedish, bandFraction, coverageDegrees, closureOverRadius, diagonal)
    }
}
