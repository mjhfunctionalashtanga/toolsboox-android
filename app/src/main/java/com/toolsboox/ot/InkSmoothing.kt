package com.toolsboox.ot

import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent

/**
 * The one stroke-geometry treatment every dialog pad shares — [InkPadView], the Edit-in-ink
 * overlay, and anything else that captures ink in a plain [android.view.View].
 *
 * Two facts the page surface knows that the pads didn't, and which together are the whole
 * "sloppy pen" complaint:
 *
 * 1. A Boox digitiser samples far faster than a dialog View is dispatched, and Android BATCHES
 *    the extra samples into [MotionEvent.getHistorySize]. A pad that reads only `event.x` throws
 *    away half to two-thirds of every stroke — worst exactly where handwriting curves fastest.
 * 2. A `lineTo` polyline kinks at every kept sample. The page bakes quadratic Béziers through
 *    segment midpoints (each raw point the control handle), which is what a pen line is.
 *
 * Kept as pure geometry so a pad's own Stroke types stay its own business.
 */
object InkSmoothing {

    /** Movements under this many view-px are digitiser jitter, not intent — the pad-scale twin
     *  of the page surface's 3-design-px epsilon. */
    private const val EPSILON = 1.5f

    private fun far(lx: Float, ly: Float, x: Float, y: Float): Boolean {
        val dx = x - lx; val dy = y - ly
        return dx * dx + dy * dy >= EPSILON * EPSILON
    }

    /** Every sample [event] carries — the batched history first, then the current point —
     *  appended to [points] as (x, y) pairs, jitter filtered against the last kept point. */
    fun appendMotion(event: MotionEvent, points: MutableList<Pair<Float, Float>>) {
        var last = points.lastOrNull()
        fun add(x: Float, y: Float) {
            val l = last
            if (l != null && !far(l.first, l.second, x, y)) return
            val p = x to y
            points.add(p); last = p
        }
        for (i in 0 until event.historySize) add(event.getHistoricalX(i), event.getHistoricalY(i))
        add(event.x, event.y)
    }

    /** [appendMotion] for pads that keep [PointF]s. */
    fun appendMotionF(event: MotionEvent, points: MutableList<PointF>) {
        var last = points.lastOrNull()
        fun add(x: Float, y: Float) {
            val l = last
            if (l != null && !far(l.x, l.y, x, y)) return
            val p = PointF(x, y)
            points.add(p); last = p
        }
        for (i in 0 until event.historySize) add(event.getHistoricalX(i), event.getHistoricalY(i))
        add(event.x, event.y)
    }

    /**
     * Rebuild [out] from the captured points as quadratic Béziers through segment midpoints.
     * The construction the page bakes with — control ON the point, curve through the midpoints —
     * plus a closing `lineTo` so live ink reaches the nib instead of stopping half a segment
     * back. A single point renders as a dot rather than as nothing: a tap is still ink.
     */
    fun rebuild(points: List<Pair<Float, Float>>, out: Path) {
        out.rewind()
        if (points.isEmpty()) return
        val (x0, y0) = points[0]
        out.moveTo(x0, y0)
        if (points.size == 1) { out.lineTo(x0 + 0.01f, y0); return }
        for (i in 1 until points.size - 1) {
            val (cx, cy) = points[i]
            val (nx, ny) = points[i + 1]
            out.quadTo(cx, cy, (cx + nx) / 2f, (cy + ny) / 2f)
        }
        val (xe, ye) = points[points.size - 1]
        out.lineTo(xe, ye)
    }

    /** [rebuild] for pads that keep [PointF]s. */
    fun rebuildF(points: List<PointF>, out: Path) {
        out.rewind()
        if (points.isEmpty()) return
        out.moveTo(points[0].x, points[0].y)
        if (points.size == 1) { out.lineTo(points[0].x + 0.01f, points[0].y); return }
        for (i in 1 until points.size - 1) {
            val c = points[i]
            val n = points[i + 1]
            out.quadTo(c.x, c.y, (c.x + n.x) / 2f, (c.y + n.y) / 2f)
        }
        out.lineTo(points.last().x, points.last().y)
    }
}
