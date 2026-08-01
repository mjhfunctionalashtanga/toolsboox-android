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

    companion object {
        /** A node whose uri starts with this is a `#tag` — drawn with the inset chip rule. */
        private const val TAG_PREFIX = "tag://"
    }

    var onNodeTap: ((String) -> Unit)? = null
    var onNodeHold: ((String) -> Unit)? = null

    private var placed: List<MindMap.Placed> = emptyList()
    /** Connection count per uri — how many edges each node carries; sizes its box. */
    private var degree: Map<String, Int> = emptyMap()
    private val boxes = mutableListOf<Pair<RectF, String>>()

    /**
     * Vertical pan, in pixels, for the volume keys. The layout fits the whole picture to the
     * panel, so this is a nudge rather than a scroll: it lets a box clipped at an edge be read,
     * and it gives the hardware keys the same "page it" meaning they carry everywhere else.
     * Clamped to one panel each way so the picture can never be paged out of reach.
     */
    private var panY = 0f

    /** Shift the picture by [dy] pixels (positive = down). One clean redraw, no animation. */
    fun panBy(dy: Float) {
        val limit = height.toFloat().coerceAtLeast(1f)
        val next = (panY + dy).coerceIn(-limit, limit)
        if (next != panY) { panY = next; invalidate() }
    }

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
    // A tag node wears a second, inset rule — a chip frame — so a `#tag` reads as a tag and not a
    // page at a glance. Hairline black, no fill: the only e-ink-safe way to say "different kind".
    private val tagLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = dp(1f)
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
        panY = 0f   // a new picture opens centred — a leftover pan would look like a broken map
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        boxes.clear()
        drawMap(canvas, width.toFloat(), height.toFloat(), panY, boxes)
    }

    /**
     * The whole laid-out map as a bitmap, ready for a PNG — the export door's renderer.
     *
     * Not a screenshot: [pan] is forced to zero, so a picture nudged half off the panel with the
     * volume keys still exports centred and whole, and the radial layout already fits every ring
     * inside the given bounds, so panel-sized geometry IS the entire graph — nothing off-screen
     * to lose. [scale] buys resolution on top of that (2× reads crisply when the PNG lands in a
     * chat or a document). White is baked in because this view never paints its own background —
     * on the panel the layout behind it is white, but a bare export would be transparent, and
     * transparent PNGs read as solid black in most viewers.
     */
    fun exportBitmap(scale: Float = 2f): android.graphics.Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(
            (w * scale).toInt(), (h * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        drawMap(c, w.toFloat(), h.toFloat(), pan = 0f, hits = null)
        return bmp
    }

    /**
     * The one drawing routine, shared by the live panel and [exportBitmap] — so the export can
     * never drift out of step with what the panel shows. [hits] collects the tappable boxes when
     * this is the panel and is null for an export, where nothing is tappable.
     */
    private fun drawMap(canvas: Canvas, w: Float, h: Float, pan: Float, hits: MutableList<Pair<RectF, String>>?) {
        if (placed.isEmpty()) return

        val cx = w / 2f
        // Boxes are laid out (and remembered for hit-testing) in already-panned screen space,
        // so taps keep landing on what the finger sees without any coordinate translation.
        val cy = h / 2f + pan
        // Leave a margin so the outer ring's boxes stay on the panel rather than half off it.
        val rx = w / 2f - dp(70f)
        val ry = h / 2f - dp(46f)

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
            // Full words, not "Synthes…". A label that overruns the box wraps onto a second line
            // before the ellipsis is ever reached for — the box grows downward by one line-height,
            // which the layout absorbs: the ring gaps (INNER_R→OUTER_R in unit space, times the
            // margins above) are several line-heights at any panel size this runs on, and boxes
            // are filled white and drawn after the lines, so even a close pair stays legible.
            // The hit-target below is the real (possibly taller) box, so taps track the eye.
            val lines = wrapLabel(p.label, maxW)
            val tw = lines.maxOf { textPaint.measureText(it) }
            val padX = dp(10f)
            val padY = dp(7f)
            val bh = textPaint.fontSpacing * lines.size + padY * 2
            val bw = tw + padX * 2

            val x = px(p); val y = py(p)
            val box = RectF(x - bw / 2f, y - bh / 2f, x + bw / 2f, y + bh / 2f)
            val r = dp(6f)
            canvas.drawRoundRect(box, r, r, boxFill)
            boxLine.strokeWidth = dp(if (focus) 3.5f else if (p.depth == 1) 1f + 2f * w1 else 1.4f)
            canvas.drawRoundRect(box, r, r, boxLine)
            // Tag nodes get the inset chip rule. Size still tracks heft through the shared degree
            // weighting above (a tag on many pages carries a high degree, so it draws big and bold
            // like any load-bearing node); the frame is what marks it as a tag rather than a page.
            if (p.uri.startsWith(TAG_PREFIX)) {
                val inset = dp(3f)
                val inner = RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
                val ir = (r - inset).coerceAtLeast(dp(2f))
                canvas.drawRoundRect(inner, ir, ir, tagLine)
            }

            // Each line centred on its own share of the block, the block centred on the node.
            lines.forEachIndexed { i, line ->
                val lineCy = y + (i - (lines.size - 1) / 2f) * textPaint.fontSpacing
                val baseline = lineCy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(line, x, baseline, textPaint)
            }
            hits?.add(box to p.uri)
        }
    }

    /**
     * A label as the box will carry it: one line when it fits, two when it must, and only a label
     * still overrunning TWO lines gets the ellipsis — the complaint this answers was ordinary
     * words ("Synthesize", a contact's name) being eaten mid-word, and the last-resort case is a
     * genuinely enormous label, where a third line would swallow the ring.
     */
    private fun wrapLabel(text: String, maxWidth: Float): List<String> {
        if (textPaint.measureText(text) <= maxWidth) return listOf(text)
        // Break at the last space that fits, so whole words survive; a single word wider than the
        // box breaks mid-word, which still reads better than losing its tail entirely.
        val fit = textPaint.breakText(text, true, maxWidth, null).coerceAtLeast(1)
        val cut = text.lastIndexOf(' ', fit - 1).takeIf { it > 0 } ?: fit
        val first = text.substring(0, cut).trim()
        val rest = text.substring(cut).trim()
        if (rest.isEmpty()) return listOf(first)
        return listOf(first, ellipsize(rest, maxWidth))
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
