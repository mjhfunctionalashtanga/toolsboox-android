package com.toolsboox.ot

import com.toolsboox.da.StrokePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ❖ SHAPE SNAP — the Notate lesson (github.com/alexdremov/notate), taken whole: a rough shape
 * drawn and then HELD still at the pen's end becomes clean geometry. No menu, no mode, no
 * button — the DWELL is the ask, and it is a gesture handwriting never makes (nobody rests a
 * still nib on the page for a third of a second mid-word). A stroke that ends with a lift keeps
 * its ink exactly as drawn.
 *
 * Michael's constitution, 09-02: every surface should make it "very easy to create essays and
 * outlines and notes and charts and pages" — a chart is boxes, arrows and rings, and this is
 * those three without a single trip to a picker.
 *
 * CONSERVATIVE BY CONTRACT. A wrong snap eats handwriting, which is the one unforgivable act on
 * a notes page, so every classifier here prefers "leave the ink alone" over "probably a shape":
 * the dwell must be real, the fit residuals small, and anything ambiguous returns null. The
 * caller feeds the result back through the ORDINARY stroke-commit path — the snap changes the
 * points and nothing else, so no id is reminted (the stroke-doubling lesson), no vendor ink path
 * runs any differently, and undo undoes it like any stroke.
 */
object ShapeSnap {

    /** How long the nib must rest, and how still, for the stroke to be asking. */
    private const val DWELL_MS = 320L
    private const val DWELL_RADIUS = 9f

    /** Fit tolerances, as fractions of the shape's size — loose enough for a freehand box,
     *  tight enough that cursive never qualifies. */
    private const val LINE_MAX_DEV_FRAC = 0.055f
    private const val CLOSE_FRAC = 0.22f          // endpoints this near (of perimeter/4) = closed
    private const val CIRCLE_BAND_FRAC = 0.16f    // radial deviation band around the mean radius
    private const val MIN_SIZE_PX = 46f           // a dot held still is a dot, not a shape

    /** The nib rested at the end: the tail of the stroke stays within [DWELL_RADIUS] of the last
     *  point for at least [DWELL_MS] (points carry per-stroke relative millis in [StrokePoint.t]). */
    fun heldAtEnd(points: List<StrokePoint>): Boolean {
        if (points.size < 8) return false
        val last = points.last()
        if (last.t <= 0L) return false            // no timing on this path — never guess
        var i = points.size - 1
        while (i > 0 && hypot(points[i].x - last.x, points[i].y - last.y) <= DWELL_RADIUS) i--
        val restStart = points[i + 1].t
        return last.t - restStart >= DWELL_MS
    }

    /**
     * The snapped replacement for [points], or null when the ink is not confidently a line,
     * rectangle, triangle or ellipse. Pressure rides as the input's median so the drawn weight
     * survives; times are zeroed (stored strokes carry t=0 — the standing wire shape).
     */
    fun fit(points: List<StrokePoint>): List<StrokePoint>? {
        // The rest tail is the ASK, not the shape — drop it before fitting.
        val body = trimDwellTail(points)
        if (body.size < 8) return null
        val xs = body.map { it.x }; val ys = body.map { it.y }
        val w = (xs.max() - xs.min()); val h = (ys.max() - ys.min())
        if (maxOf(w, h) < MIN_SIZE_PX) return null
        val p = median(body.map { it.p })

        line(body, p)?.let { return it }
        val closed = isClosed(body)
        if (!closed) return null
        circle(body, p)?.let { return it }
        return polygon(body, p)
    }

    // ── pieces ─────────────────────────────────────────────────────────────────

    private fun trimDwellTail(points: List<StrokePoint>): List<StrokePoint> {
        val last = points.last()
        var i = points.size - 1
        while (i > 0 && hypot(points[i].x - last.x, points[i].y - last.y) <= DWELL_RADIUS) i--
        return points.subList(0, (i + 2).coerceAtMost(points.size))
    }

    private fun median(v: List<Float>): Float =
        v.sorted().let { it[it.size / 2] }.coerceIn(0.05f, 1f)

    private fun isClosed(b: List<StrokePoint>): Boolean {
        val per = pathLength(b)
        val gap = hypot(b.first().x - b.last().x, b.first().y - b.last().y)
        return per > 0 && gap <= per / 4f * CLOSE_FRAC * 4f && gap <= per * CLOSE_FRAC
    }

    private fun pathLength(b: List<StrokePoint>): Float {
        var d = 0f
        for (i in 1 until b.size) d += hypot(b[i].x - b[i - 1].x, b[i].y - b[i - 1].y)
        return d
    }

    /** An open stroke whose every point hugs the chord is a ruled line. */
    private fun line(b: List<StrokePoint>, p: Float): List<StrokePoint>? {
        if (isClosed(b)) return null
        val a = b.first(); val z = b.last()
        val len = hypot(z.x - a.x, z.y - a.y)
        if (len < MIN_SIZE_PX) return null
        val maxDev = b.maxOf { pt -> pointToSegment(pt.x, pt.y, a.x, a.y, z.x, z.y) }
        if (maxDev > len * LINE_MAX_DEV_FRAC) return null
        // Near-horizontal/vertical lines settle onto the axis — a chart's rules should rule.
        var (x1, y1, x2, y2) = listOf(a.x, a.y, z.x, z.y)
        val ang = abs(atan2(y2 - y1, x2 - x1))
        if (ang < 0.08f || abs(ang - Math.PI.toFloat()) < 0.08f) { val m = (y1 + y2) / 2f; y1 = m; y2 = m }
        if (abs(ang - (Math.PI / 2).toFloat()) < 0.08f) { val m = (x1 + x2) / 2f; x1 = m; x2 = m }
        return sampled(listOf(x1 to y1, x2 to y2), closed = false, p = p)
    }

    /** A closed stroke whose radii stay in a narrow band around their mean is a circle;
     *  distinctly wider than tall (or the reverse) within the band test becomes an ellipse. */
    private fun circle(b: List<StrokePoint>, p: Float): List<StrokePoint>? {
        val cx = b.map { it.x }.average().toFloat()
        val cy = b.map { it.y }.average().toFloat()
        val rx = b.maxOf { abs(it.x - cx) }
        val ry = b.maxOf { abs(it.y - cy) }
        if (minOf(rx, ry) < MIN_SIZE_PX / 2) return null
        // Normalised radial deviation against the ellipse the extents suggest.
        var dev = 0f
        for (pt in b) {
            val nr = hypot((pt.x - cx) / rx, (pt.y - cy) / ry)
            dev = maxOf(dev, abs(nr - 1f))
        }
        if (dev > CIRCLE_BAND_FRAC * 2.2f) return null
        val n = 72
        val pts = (0..n).map { i ->
            val a = 2 * Math.PI * i / n
            (cx + rx * cos(a).toFloat()) to (cy + ry * sin(a).toFloat())
        }
        return sampled(pts, closed = false, p = p)   // ring already closes on itself
    }

    /** RDP corners on a closed stroke: three make a triangle, four a rectangle (squared up when
     *  it is nearly axis-aligned). Anything else stays ink. */
    private fun polygon(b: List<StrokePoint>, p: Float): List<StrokePoint>? {
        val per = pathLength(b)
        val simplified = rdp(b, per * 0.035f)
        val corners = simplified.dropLast(1)
        return when (corners.size) {
            3 -> sampled(corners.map { it.x to it.y }, closed = true, p = p)
            4 -> {
                val xs = corners.map { it.x }; val ys = corners.map { it.y }
                val axisish = corners.indices.all { i ->
                    val j = (i + 1) % 4
                    val dx = abs(corners[j].x - corners[i].x); val dy = abs(corners[j].y - corners[i].y)
                    minOf(dx, dy) < maxOf(dx, dy) * 0.22f
                }
                if (axisish) {
                    val l = xs.min(); val r = xs.max(); val t = ys.min(); val btm = ys.max()
                    sampled(listOf(l to t, r to t, r to btm, l to btm), closed = true, p = p)
                } else sampled(corners.map { it.x to it.y }, closed = true, p = p)
            }
            else -> null
        }
    }

    private fun rdp(b: List<StrokePoint>, eps: Float): List<StrokePoint> {
        if (b.size < 3) return b
        var maxD = 0f; var idx = 0
        val a = b.first(); val z = b.last()
        for (i in 1 until b.size - 1) {
            val d = pointToSegment(b[i].x, b[i].y, a.x, a.y, z.x, z.y)
            if (d > maxD) { maxD = d; idx = i }
        }
        return if (maxD > eps) {
            rdp(b.subList(0, idx + 1), eps).dropLast(1) + rdp(b.subList(idx, b.size), eps)
        } else listOf(a, z)
    }

    private fun pointToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    /** Clean points along the polyline at drawing density, all t=0, one pressure. */
    private fun sampled(vertices: List<Pair<Float, Float>>, closed: Boolean, p: Float): List<StrokePoint> {
        val verts = if (closed) vertices + vertices.first() else vertices
        val out = mutableListOf<StrokePoint>()
        val step = 6f
        for (i in 1 until verts.size) {
            val (x1, y1) = verts[i - 1]; val (x2, y2) = verts[i]
            val d = hypot(x2 - x1, y2 - y1)
            val n = maxOf(1, (d / step).toInt())
            for (k in 0 until n) {
                val t = k.toFloat() / n
                out.add(StrokePoint(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, p, 0L))
            }
        }
        val (lx, ly) = verts.last()
        out.add(StrokePoint(lx, ly, p, 0L))
        return out
    }
}
