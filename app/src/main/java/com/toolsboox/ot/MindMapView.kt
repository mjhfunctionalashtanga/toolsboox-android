package com.toolsboox.ot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * Draws a [MindMap] layout, and nothing else.
 *
 * Built for the panel it lives on: pure black on white, flat strokes, no shadows, no gradients,
 * no animation. Every one of those is a grey wash on e-ink, and a map is a thing you stare at.
 */
class MindMapView(context: Context) : View(context) {

    var onNodeTap: ((String) -> Unit)? = null
    var onNodeHold: ((String) -> Unit)? = null

    private var placed: List<MindMap.Placed> = emptyList()
    /** Connection count per uri — how many edges each node carries; sizes its box. */
    private var degree: Map<String, Int> = emptyMap()
    private val boxes = mutableListOf<Pair<RectF, String>>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Solid black, like everything else here — the old mid-gray edge sat under a "pure black
    // on white" header and washed out on the panel it was built for.
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = dp(1.5f)
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = dp(2f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER
    }

    /**
     * [degree] is each node's connection count. The iOS Map swells a disc with its weight
     * (MapView.nodeRadius: focus fixed, inner nodes grow with degree, outer dots stay small);
     * boxes can't swell a radius, so here weight reads as a heavier rule and larger type on
     * the inner ring — the busy nodes look load-bearing, e-ink-simple, no fills.
     */
    fun setGraph(nodes: List<MindMap.Placed>, degree: Map<String, Int> = emptyMap()) {
        placed = nodes
        this.degree = degree
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        boxes.clear()
        if (placed.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f
        // Leave a margin so the outer ring's boxes stay on the panel rather than half off it.
        val rx = width / 2f - dp(70f)
        val ry = height / 2f - dp(46f)

        fun px(p: MindMap.Placed) = cx + p.x * rx
        fun py(p: MindMap.Placed) = cy + p.y * ry

        val byUri = placed.associateBy { it.uri }

        // Lines first, so a box always sits on top of the line that reaches it.
        for (p in placed) {
            if (p.depth == 0) continue
            val parent = byUri[p.parent] ?: continue
            canvas.drawLine(px(parent), py(parent), px(p), py(p), edgePaint)
        }

        // Normalise weight within THIS picture, so the busiest node on any map reads full-size.
        val maxDeg = placed.filter { it.depth == 1 }
            .maxOfOrNull { degree[it.uri] ?: 0 }?.coerceAtLeast(1) ?: 1

        for (p in placed) {
            val focus = p.depth == 0
            // 0…1 importance for the inner ring — the box's rule and type grow with it. The
            // spread is deliberately wide (11.5→15.5sp, hairline→3dp): the first cut was subtle
            // enough to disappear into e-ink's grey, and a weight you can't see isn't a weight.
            val w1 = if (p.depth == 1) (degree[p.uri] ?: 0).toFloat() / maxDeg else 0f
            textPaint.textSize = dp(if (focus) 15f else if (p.depth == 1) 11.5f + 4f * w1 else 11.5f)
            textPaint.isFakeBoldText = focus || w1 >= 0.75f

            val maxW = dp(if (focus) 190f else 150f)
            val label = ellipsize(p.label, maxW)
            val tw = textPaint.measureText(label)
            val padX = dp(10f)
            val padY = dp(7f)
            val h = textPaint.fontSpacing + padY * 2
            val w = tw + padX * 2

            val x = px(p); val y = py(p)
            val box = RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
            val r = dp(6f)
            canvas.drawRoundRect(box, r, r, boxFill)
            boxLine.strokeWidth = dp(if (focus) 3.5f else if (p.depth == 1) 1f + 2f * w1 else 1.4f)
            canvas.drawRoundRect(box, r, r, boxLine)

            val baseline = y - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, x, baseline, textPaint)
            boxes += box to p.uri
        }
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.length > 1 && textPaint.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hit(e.x, e.y)?.let { onNodeTap?.invoke(it); return true }
            return false
        }
        override fun onLongPress(e: MotionEvent) {
            hit(e.x, e.y)?.let { onNodeHold?.invoke(it) }
        }
    })

    /** Last box wins: they are drawn in order, so the one on top is the one you meant. */
    private fun hit(x: Float, y: Float): String? =
        boxes.lastOrNull { (r, _) -> r.contains(x, y) }?.second

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
