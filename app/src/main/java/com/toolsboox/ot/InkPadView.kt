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
 * message, a card comment, a booking note. [InkMount] is its counterpart for ink that is SHOWN.
 *
 * Deliberately a plain [View] with plain [Canvas] drawing rather than the Onyx note engine: a
 * real surface composites to the panel through its own window, which doesn't survive being put
 * inside a dialog. This one is small, predictable, and works everywhere.
 *
 * Each stroke carries its own colour and width, so undo and the colour toolbar behave the way
 * they do on a creation page.
 */
class InkPadView(context: Context) : View(context) {

    private class Stroke(val path: Path, val color: Int, val strokeWidth: Float,
                         val points: MutableList<Pair<Float, Float>> = mutableListOf())

    private val strokes = mutableListOf<Stroke>()
    private var current: Path? = null

    var penColor: Int = Color.BLACK
    var penWidth: Float = 4f

    /** Stroke eraser: while on, touches delete whole strokes they cross instead of drawing. */
    var eraseMode: Boolean = false

    init {
        setBackgroundColor(Color.WHITE)
    }

    private fun paintFor(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (eraseMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                eraseAt(event.x, event.y)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                current = Path().also {
                    it.moveTo(event.x, event.y)
                    strokes.add(Stroke(it, penColor, penWidth).apply { points.add(event.x to event.y) })
                }
            MotionEvent.ACTION_MOVE -> {
                current?.lineTo(event.x, event.y)
                strokes.lastOrNull()?.points?.add(event.x to event.y)
            }
            MotionEvent.ACTION_UP -> current = null
        }
        invalidate()
        return true
    }

    /** Delete any stroke passing within a fingertip of (x, y) — whole strokes, like the page's
     *  scribble eraser: partial-path surgery isn't worth it on a note-sized pad. */
    private fun eraseAt(x: Float, y: Float) {
        val r = 24f * resources.displayMetrics.density / 2.5f
        val hit = strokes.filter { st -> st.points.any { (px, py) ->
            val dx = px - x; val dy = py - y; dx * dx + dy * dy < r * r
        } }
        if (hit.isNotEmpty()) { strokes.removeAll(hit); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (s in strokes) canvas.drawPath(s.path, paintFor(s.color, s.strokeWidth))
    }

    fun clear() {
        strokes.clear(); current = null; invalidate()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1); current = null; invalidate()
        }
    }

    fun isBlank() = strokes.isEmpty()

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
        if (r < l || b < t) return null
        val air = pad / 2f + 2f
        return android.graphics.RectF(l - air, t - air, r + air, b + air)
    }

    /** The written card as a bitmap, or null when nothing has been written. */
    fun render(): Bitmap? {
        if (strokes.isEmpty() || width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        for (s in strokes) c.drawPath(s.path, paintFor(s.color, s.strokeWidth))
        return bmp
    }

    companion object {
        private val WIDTHS = floatArrayOf(2.5f, 4f, 7f)
        private const val ACCENT = 0xFF2F6F96.toInt()
        private const val RED = "#B00020"
        private const val BLUE = "#1A4E8A"

        /**
         * The pen toolbar that belongs above a pad: undo, three inks, and a fine↔bold cycle.
         *
         * Lives here rather than in one dialog so every surface that composes ink gets the same
         * controls — the alternative is four toolbars drifting apart the way the four pads did.
         */
        fun penBar(context: Context, pad: InkPadView): android.widget.LinearLayout {
            val dp = context.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()

            val bar = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(px(2), px(2), px(2), px(4))
            }

            fun swatch(color: Int): android.widget.TextView {
                lateinit var tv: android.widget.TextView
                tv = android.widget.TextView(context).apply {
                    text = "●"; textSize = 22f; setTextColor(color); setPadding(px(6), 0, px(6), 0)
                    setOnClickListener {
                        pad.penColor = color
                        // The active ink is the underlined one.
                        (parent as? android.widget.LinearLayout)?.let { row ->
                            for (i in 0 until row.childCount) (row.getChildAt(i) as? android.widget.TextView)?.paintFlags = 0
                        }
                        tv.paintFlags = Paint.UNDERLINE_TEXT_FLAG
                    }
                }
                return tv
            }

            bar.addView(android.widget.TextView(context).apply {
                text = "↶"; textSize = 20f; setTextColor(ACCENT); setPadding(px(4), 0, px(14), 0)
                setOnClickListener { pad.undo() }
            })
            bar.addView(swatch(Color.BLACK).also { it.paintFlags = Paint.UNDERLINE_TEXT_FLAG })
            bar.addView(swatch(Color.parseColor(RED)))
            bar.addView(swatch(Color.parseColor(BLUE)))
            bar.addView(android.widget.Space(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
            })

            var widthIdx = 1
            bar.addView(android.widget.TextView(context).apply {
                text = "\u2712 width"; textSize = 14f; setTextColor(ACCENT); setPadding(px(8), 0, px(4), 0)
                setOnClickListener { widthIdx = (widthIdx + 1) % WIDTHS.size; pad.penWidth = WIDTHS[widthIdx] }
            })

            // Stroke eraser: inverted while armed, the pressed-state grammar the page tools use.
            bar.addView(android.widget.TextView(context).apply {
                text = " \u25E8 erase "; textSize = 14f; setTextColor(ACCENT); setPadding(px(10), px(2), px(6), px(2))
                setOnClickListener {
                    pad.eraseMode = !pad.eraseMode
                    if (pad.eraseMode) { setBackgroundColor(0xFF000000.toInt()); setTextColor(Color.WHITE) }
                    else { setBackgroundColor(0x00000000); setTextColor(ACCENT) }
                }
            })

            return bar
        }
    }
}
