package com.toolsboox.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * The small hand-writing pad used wherever ink is COMPOSED inside a dialog — a reply, a chat
 * message, a card comment, a booking note, the ✍ Handwriting annotation. [InkMount] is its
 * counterpart for ink that is SHOWN.
 *
 * Deliberately a plain [View] with plain [Canvas] drawing rather than the Onyx note engine: a
 * real surface composites to the panel through its own window, which doesn't survive being put
 * inside a dialog. This one is small, predictable, and works everywhere.
 *
 * Each stroke carries its own colour, width and nib, so undo and the colour toolbar behave the
 * way they do on a creation page. Beyond freehand ink the pad also takes PLACED TEXT (typed or
 * pasted, set down where the next tap lands) and DRAGGED SHAPES (box/round/line/arrow) — the
 * page surface's eraser/stylus vocabulary, scaled to a pad (Michael: "can that be made to look
 * more like our current eraser/stylus design").
 */
class InkPadView(context: Context) : View(context) {

    private class Stroke(val path: Path, val color: Int, val strokeWidth: Float,
                         val calligraphy: Boolean = false,
                         val points: MutableList<Pair<Float, Float>> = mutableListOf())

    /** A piece of typed/pasted text set down on the pad — erasable and undoable like a stroke. */
    private class Placed(val text: String, val x: Float, val y: Float, val color: Int)

    /** One drawn thing, in draw order — so undo peels the pad back element by element
     *  regardless of whether the last thing down was ink, a shape or a word. */
    private sealed class Op {
        class Ink(val stroke: Stroke) : Op()
        class Text(val placed: Placed) : Op()
    }

    private val ops = mutableListOf<Op>()
    private var current: Path? = null

    var penColor: Int = Color.BLACK
    var penWidth: Float = 4f

    /** Calligraphy nib: strokes lay down a broad 45° edge instead of a round point — the same
     *  ballpoint-vs-calligraphy choice the page pen holds, emulated with offset passes because
     *  a dialog pad has no pressure engine. */
    var calligraphyMode: Boolean = false

    /** Stroke eraser: while on, touches delete whole strokes (and placed text) they cross
     *  instead of drawing. */
    var eraseMode: Boolean = false

    /** Armed text: the next tap sets this string down where the finger lands, then disarms.
     *  Fed by the pen bar's Ⓣ (typed) and ⎘ (clipboard) buttons. */
    var pendingText: String? = null

    /** Armed shape (one of [SHAPE_BOX], [SHAPE_ROUND], [SHAPE_LINE], [SHAPE_ARROW]) — the next
     *  drag draws it corner-to-corner, live, and commits it as an ordinary erasable stroke. */
    var pendingShape: String? = null
    private var shapeStartX = 0f
    private var shapeStartY = 0f

    init {
        setBackgroundColor(Color.WHITE)
    }

    private val strokes: List<Stroke> get() = ops.mapNotNull { (it as? Op.Ink)?.stroke }
    private val texts: List<Placed> get() = ops.mapNotNull { (it as? Op.Text)?.placed }

    private fun paintFor(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = 20f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.SERIF
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (eraseMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                eraseAt(event.x, event.y)
            }
            return true
        }
        // Armed text: the tap is the placement, not a stroke.
        pendingText?.let { t ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                ops.add(Op.Text(Placed(t, event.x, event.y, penColor)))
                pendingText = null
                invalidate()
            }
            return true
        }
        // Armed shape: the drag stretches it corner-to-corner; up commits it as a stroke.
        pendingShape?.let { kind ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shapeStartX = event.x; shapeStartY = event.y
                    ops.add(Op.Ink(Stroke(Path(), penColor, penWidth)))
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    (ops.lastOrNull() as? Op.Ink)?.stroke?.let { s ->
                        buildShape(kind, s, shapeStartX, shapeStartY, event.x, event.y)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) pendingShape = null
                }
            }
            invalidate()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                current = Path().also {
                    it.moveTo(event.x, event.y)
                    ops.add(Op.Ink(Stroke(it, penColor, penWidth, calligraphyMode)
                        .apply { points.add(event.x to event.y) }))
                }
            MotionEvent.ACTION_MOVE -> {
                current?.lineTo(event.x, event.y)
                (ops.lastOrNull() as? Op.Ink)?.stroke?.points?.add(event.x to event.y)
            }
            MotionEvent.ACTION_UP -> current = null
        }
        invalidate()
        return true
    }

    /** Rebuild [s]'s path as the armed shape between the anchor and the finger, and refresh its
     *  point list so the stroke-eraser can find the shape's outline later. */
    private fun buildShape(kind: String, s: Stroke, x0: Float, y0: Float, x1: Float, y1: Float) {
        s.path.reset(); s.points.clear()
        val l = minOf(x0, x1); val t = minOf(y0, y1)
        val r = maxOf(x0, x1); val b = maxOf(y0, y1)
        when (kind) {
            SHAPE_BOX -> s.path.addRect(l, t, r, b, Path.Direction.CW)
            SHAPE_ROUND -> s.path.addOval(android.graphics.RectF(l, t, r, b), Path.Direction.CW)
            SHAPE_LINE -> { s.path.moveTo(x0, y0); s.path.lineTo(x1, y1) }
            SHAPE_ARROW -> {
                s.path.moveTo(x0, y0); s.path.lineTo(x1, y1)
                // The head: two short barbs off the tip, sized to the line.
                val ang = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                val len = 26f * resources.displayMetrics.density / 2.5f
                for (side in intArrayOf(-1, 1)) {
                    val a = ang + Math.PI - side * Math.PI / 7
                    s.path.moveTo(x1, y1)
                    s.path.lineTo(x1 + (len * Math.cos(a)).toFloat(), y1 + (len * Math.sin(a)).toFloat())
                }
            }
        }
        // Sample the outline for the eraser (a Path can be drawn but not asked where it went).
        val pm = android.graphics.PathMeasure(s.path, false)
        do {
            val step = 12f
            var d = 0f
            val pos = FloatArray(2)
            while (d <= pm.length) {
                if (pm.getPosTan(d, pos, null)) s.points.add(pos[0] to pos[1])
                d += step
            }
        } while (pm.nextContour())
    }

    /** Delete any stroke or placed text passing within a fingertip of (x, y) — whole elements,
     *  like the page's scribble eraser: partial surgery isn't worth it on a note-sized pad. */
    private fun eraseAt(x: Float, y: Float) {
        val r = 24f * resources.displayMetrics.density / 2.5f
        val hit = ops.filter { op ->
            when (op) {
                is Op.Ink -> op.stroke.points.any { (px, py) ->
                    val dx = px - x; val dy = py - y; dx * dx + dy * dy < r * r
                }
                is Op.Text -> {
                    val p = textPaint(op.placed.color)
                    val w = p.measureText(op.placed.text)
                    x >= op.placed.x - r && x <= op.placed.x + w + r &&
                        y >= op.placed.y - p.textSize - r && y <= op.placed.y + r
                }
            }
        }
        if (hit.isNotEmpty()) { ops.removeAll(hit); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawOps(canvas)
    }

    /** Draw every element in the order it was set down, on-screen and in [render] alike. */
    private fun drawOps(canvas: Canvas) {
        for (op in ops) when (op) {
            is Op.Ink -> drawStroke(canvas, op.stroke)
            is Op.Text -> canvas.drawText(op.placed.text, op.placed.x, op.placed.y, textPaint(op.placed.color))
        }
    }

    /** A round-nib stroke is one pass; the calligraphy nib is the same path swept along a 45°
     *  edge — thin passes offset along the nib axis, which is how a broad edge reads without a
     *  pressure engine (thick across the nib, thin along it). */
    private fun drawStroke(canvas: Canvas, s: Stroke) {
        if (!s.calligraphy) {
            canvas.drawPath(s.path, paintFor(s.color, s.strokeWidth))
            return
        }
        val sweep = s.strokeWidth * 1.8f
        val passes = 5
        val thin = paintFor(s.color, (s.strokeWidth * 0.55f).coerceAtLeast(1.5f))
        for (i in 0 until passes) {
            val d = sweep * (i / (passes - 1f) - 0.5f)
            canvas.save()
            canvas.translate(d * 0.7071f, -d * 0.7071f)
            canvas.drawPath(s.path, thin)
            canvas.restore()
        }
    }

    fun clear() {
        ops.clear(); current = null; invalidate()
    }

    fun undo() {
        if (ops.isNotEmpty()) {
            ops.removeAt(ops.size - 1); current = null; invalidate()
        }
    }

    fun isBlank() = ops.isEmpty()

    /**
     * The box everything written actually occupies, in view pixels, padded by half a pen width so a
     * stroke's own thickness isn't sliced off — or null when the pad is empty.
     *
     * Exists for callers that keep the writing rather than just reading it: a title written on a
     * pad three inches tall is one line of ink in the top third of it, and storing the whole pad
     * would store mostly blank paper and then scale the writing down to nothing to make it fit.
     * The iPad twin crops the same way for the same reason (`canvas.drawing.bounds` in
     * `InkTaskStrip`), so a face written on either device lands the same size on the other.
     */
    fun inkBounds(): android.graphics.RectF? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var pad = 0f
        for (s in strokes) {
            for ((x, y) in s.points) {
                if (x < l) l = x; if (y < t) t = y
                if (x > r) r = x; if (y > b) b = y
            }
            if (s.strokeWidth > pad) pad = s.strokeWidth
        }
        for (p in texts) {
            val paint = textPaint(p.color)
            val w = paint.measureText(p.text)
            if (p.x < l) l = p.x; if (p.y - paint.textSize < t) t = p.y - paint.textSize
            if (p.x + w > r) r = p.x + w; if (p.y > b) b = p.y
        }
        if (r < l || b < t) return null
        val air = pad / 2f + 2f
        return android.graphics.RectF(l - air, t - air, r + air, b + air)
    }

    /** The written card as a bitmap, or null when nothing has been written. */
    fun render(): Bitmap? {
        if (ops.isEmpty() || width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        drawOps(c)
        return bmp
    }

    companion object {
        private val WIDTHS = floatArrayOf(2.5f, 4f, 7f)
        private const val ACCENT = 0xFF2F6F96.toInt()
        private const val RED = "#B00020"
        private const val BLUE = "#1A4E8A"
        private const val GREEN = "#1E6B34"

        const val SHAPE_BOX = "box"
        const val SHAPE_ROUND = "round"
        const val SHAPE_LINE = "line"
        const val SHAPE_ARROW = "arrow"

        /**
         * The pen toolbar that belongs above a pad: undo, the page palette, a fine↔bold cycle,
         * the calligraphy nib, placed text, clipboard paste, dragged shapes, and the stroke
         * eraser — the current eraser/stylus design, scaled to a pad.
         *
         * Lives here rather than in one dialog so every surface that composes ink gets the same
         * controls — the alternative is four toolbars drifting apart the way the four pads did.
         * The row scrolls sideways rather than wrapping: a dialog on a phone is narrower than
         * the full set, and a second row would push the pad below a writing hand.
         */
        fun penBar(context: Context, pad: InkPadView): android.widget.LinearLayout {
            val dp = context.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()

            val bar = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(px(2), px(2), px(2), px(4))
            }

            // The armed-tool buttons invert while armed (the pressed-state grammar the page
            // tools use), and arming any one disarms the others — one nib in the hand at a time.
            lateinit var refreshArmed: () -> Unit

            fun swatch(color: Int): android.widget.TextView {
                lateinit var tv: android.widget.TextView
                tv = android.widget.TextView(context).apply {
                    text = "●"; textSize = 22f; setTextColor(color); setPadding(px(5), 0, px(5), 0)
                    setOnClickListener {
                        pad.penColor = color
                        // The active ink is the underlined one.
                        (parent as? android.widget.LinearLayout)?.let { row ->
                            for (i in 0 until row.childCount) {
                                val c = row.getChildAt(i) as? android.widget.TextView ?: continue
                                if (c.text == "●") c.paintFlags = 0
                            }
                        }
                        tv.paintFlags = Paint.UNDERLINE_TEXT_FLAG
                    }
                }
                return tv
            }

            bar.addView(android.widget.TextView(context).apply {
                text = "↶"; textSize = 20f; setTextColor(ACCENT); setPadding(px(4), 0, px(10), 0)
                setOnClickListener { pad.undo() }
            })
            bar.addView(swatch(Color.BLACK).also { it.paintFlags = Paint.UNDERLINE_TEXT_FLAG })
            bar.addView(swatch(Color.parseColor(RED)))
            bar.addView(swatch(Color.parseColor(BLUE)))
            bar.addView(swatch(Color.parseColor(GREEN)))
            bar.addView(android.widget.Space(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
            })

            var widthIdx = 1
            bar.addView(android.widget.TextView(context).apply {
                text = "✒"; textSize = 16f; setTextColor(ACCENT); setPadding(px(6), 0, px(4), 0)
                setOnClickListener { widthIdx = (widthIdx + 1) % WIDTHS.size; pad.penWidth = WIDTHS[widthIdx] }
            })

            // 🖋 the calligraphy nib — a mode, not a one-shot, so it stays down until tapped off.
            val calligBtn = android.widget.TextView(context).apply {
                text = " 🖋 "; textSize = 15f; setTextColor(ACCENT); setPadding(px(5), px(2), px(5), px(2))
                setOnClickListener {
                    pad.calligraphyMode = !pad.calligraphyMode
                    pad.eraseMode = false
                    refreshArmed()
                }
            }
            bar.addView(calligBtn)

            // Ⓣ typed text → the next tap places it. The prompt is the page's text-box idea at
            // pad scale; placement by tap keeps the choice with the hand, not a dialog.
            val textBtn = android.widget.TextView(context).apply {
                text = " Ⓣ "; textSize = 15f; setTextColor(ACCENT); setPadding(px(5), px(2), px(5), px(2))
                setOnClickListener {
                    val input = android.widget.EditText(context).apply { hint = "Text — then tap the pad to place it" }
                    androidx.appcompat.app.AlertDialog.Builder(ModalScale.wrap(context))
                        .setTitle("Text")
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { d, _ ->
                            val t = input.text.toString().trim()
                            if (t.isNotEmpty()) { pad.pendingText = t; pad.pendingShape = null; pad.eraseMode = false }
                            refreshArmed(); d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            bar.addView(textBtn)

            // ⎘ paste — whatever text the clipboard holds, placed by the next tap.
            val pasteBtn = android.widget.TextView(context).apply {
                text = " ⎘ "; textSize = 15f; setTextColor(ACCENT); setPadding(px(5), px(2), px(5), px(2))
                setOnClickListener {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (t.isEmpty()) {
                        android.widget.Toast.makeText(context, "Nothing on the clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        pad.pendingText = t.take(300); pad.pendingShape = null; pad.eraseMode = false
                        android.widget.Toast.makeText(context, "Tap the pad to place it", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    refreshArmed()
                }
            }
            bar.addView(pasteBtn)

            // Shapes: each tap cycles what the next drag draws — ▭ box, ◯ round, ╱ line,
            // ➔ arrow — and the button wears the armed shape's face.
            val shapes = listOf(SHAPE_BOX to "▭", SHAPE_ROUND to "◯", SHAPE_LINE to "╱", SHAPE_ARROW to "➔")
            var shapeIdx = -1
            val shapeBtn = android.widget.TextView(context).apply {
                text = " ▭ "; textSize = 15f; setTextColor(ACCENT); setPadding(px(5), px(2), px(5), px(2))
                setOnClickListener {
                    shapeIdx = if (pad.pendingShape == null) 0 else (shapeIdx + 1) % shapes.size
                    pad.pendingShape = shapes[shapeIdx].first
                    pad.pendingText = null; pad.eraseMode = false
                    text = " ${shapes[shapeIdx].second} "
                    refreshArmed()
                }
            }
            bar.addView(shapeBtn)

            // Stroke eraser: inverted while armed, the pressed-state grammar the page tools use.
            val eraseBtn = android.widget.TextView(context).apply {
                text = " ◨ "; textSize = 15f; setTextColor(ACCENT); setPadding(px(6), px(2), px(6), px(2))
                setOnClickListener {
                    pad.eraseMode = !pad.eraseMode
                    pad.calligraphyMode = false
                    pad.pendingText = null; pad.pendingShape = null
                    refreshArmed()
                }
            }
            bar.addView(eraseBtn)

            refreshArmed = {
                fun dress(v: android.widget.TextView, armed: Boolean) {
                    if (armed) { v.setBackgroundColor(0xFF000000.toInt()); v.setTextColor(Color.WHITE) }
                    else { v.setBackgroundColor(0x00000000); v.setTextColor(ACCENT) }
                }
                dress(calligBtn, pad.calligraphyMode)
                dress(textBtn, pad.pendingText != null)
                dress(pasteBtn, false)
                dress(shapeBtn, pad.pendingShape != null)
                dress(eraseBtn, pad.eraseMode)
                if (pad.pendingShape == null) shapeBtn.text = " ▭ "
            }
            refreshArmed()

            // The full set outgrows a phone dialog; the row scrolls sideways instead of wrapping
            // (a second row would push the pad down under the writing hand). Same outer type as
            // ever, so every existing mount keeps working untouched.
            val scroller = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            return android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(scroller, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
