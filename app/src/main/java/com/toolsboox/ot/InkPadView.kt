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

    private class Stroke(val path: Path, val color: Int, val strokeWidth: Float)

    private val strokes = mutableListOf<Stroke>()
    private var current: Path? = null

    var penColor: Int = Color.BLACK
    var penWidth: Float = 4f

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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                current = Path().also { it.moveTo(event.x, event.y); strokes.add(Stroke(it, penColor, penWidth)) }
            MotionEvent.ACTION_MOVE -> current?.lineTo(event.x, event.y)
            MotionEvent.ACTION_UP -> current = null
        }
        invalidate()
        return true
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
                text = "✒ width"; textSize = 14f; setTextColor(ACCENT); setPadding(px(8), 0, px(4), 0)
                setOnClickListener { widthIdx = (widthIdx + 1) % WIDTHS.size; pad.penWidth = WIDTHS[widthIdx] }
            })

            return bar
        }
    }
}
