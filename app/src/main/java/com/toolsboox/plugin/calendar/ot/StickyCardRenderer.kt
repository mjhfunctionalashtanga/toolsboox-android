package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint

/**
 * THE CORRESPONDENT'S STICKY (Michael, 08-30: "Synth picks can look like stickies") — the face
 * a ⟡ reply wears: a sticky note left in the book, distinguishable at a glance from the reader's
 * own picks (QuoteCardRenderer's serif quote-cards). Monochrome-honest: a clean border and a
 * FOLDED CORNER say "sticky" on e-ink where drop shadows and tint would just dither to mush; no
 * tilt — straight is the safe charm on a panel that hates anti-aliased edges. The voice is the
 * machine's typed sans, never a serif quote and never fake handwriting.
 */
object StickyCardRenderer {

    private val paper = Color.WHITE
    private val ink = Color.rgb(0x1F, 0x1F, 0x1F)
    private val muted = Color.rgb(0x5A, 0x5A, 0x5A)

    /**
     * THE THREADED FACE (Michael, 08-30: "Can I reply back on the sticky instead? That way the
     * full convo carries") — one sticky, the whole exchange. [text] is the accumulated thread:
     * blocks separated by blank lines, "✎ " blocks = the reader's turns (drawn italic — typed
     * renderings of his OCR'd words; actual ink regions turn to soup at card scale after two
     * turns), "⟡ " blocks = the machine's. Past [capPx] of body the face WINDOWS THE TAIL —
     * newest turns visible, a header saying how much rides beneath — while the element's
     * cardText always carries the WHOLE exchange, so the one pick travels complete and a
     * re-Synth anywhere hands the correspondent its full history.
     */
    /** A rendered thread face plus what the ladder had to do to fit it: [scale] is the type
     *  size actually used (1f full, down to the readable floor), [dropped] how many earlier
     *  turns the face had to window at that floor — the caller's graduation signal (Michael,
     *  08-30: shrink the type as needed; past honest readability the sticky goes to its own
     *  page, "each sticky is a note page"). */
    data class ThreadFace(val bmp: Bitmap, val dropped: Int, val scale: Float)

    /** Type sizes the thread face may step down through — the last is the readable e-ink floor. */
    private val THREAD_TYPE_LADDER = listOf(1f, 0.85f, 0.72f)

    fun renderThread(text: String, source: String, W: Int = 760, capPx: Int = 2100): Bitmap =
        renderThreadFace(text, source, W, capPx).bmp

    /** The growth ladder: full type first; a longer thread steps the type down the ladder so
     *  more turns fit the face; only at the floor does the face window the tail (and the
     *  caller should graduate the thread to its own page instead of living windowed). */
    fun renderThreadFace(text: String, source: String, W: Int = 760, capPx: Int = 2100): ThreadFace {
        for (scale in THREAD_TYPE_LADDER) {
            val f = renderThreadAt(text, source, W, capPx, scale)
            if (f.dropped == 0) return f
        }
        return renderThreadAt(text, source, W, capPx, THREAD_TYPE_LADDER.last())
    }

    private fun renderThreadAt(text: String, source: String, W: Int, capPx: Int, scale: Float): ThreadFace {
        val blocks = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
        val pad = W * 0.09f
        val contentW = (W - 2 * pad).toInt()
        fun paintFor(block: String) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (block.startsWith("✎")) muted else ink
            typeface = if (block.startsWith("✎"))
                Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC) else Typeface.SANS_SERIF
            textSize = W * 0.052f * scale
        }
        fun layoutOf(block: String) = StaticLayout.Builder
            .obtain(block, 0, block.length, paintFor(block), contentW)
            .setLineSpacing(W * 0.010f * scale, 1f).build()
        val gap = W * 0.030f
        // Window from the tail: keep the newest blocks that fit the cap.
        val kept = mutableListOf<Pair<String, StaticLayout>>()
        var h = 0f
        for (block in blocks.reversed()) {
            val l = layoutOf(block)
            if (kept.isNotEmpty() && h + l.height + gap > capPx) break
            kept.add(0, block to l); h += l.height + gap
        }
        val dropped = blocks.size - kept.size
        val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.SANS_SERIF; textSize = W * 0.040f
        }
        val header = if (dropped > 0) {
            val t = "⋯ $dropped earlier turn${if (dropped == 1) "" else "s"} ride in the card"
            StaticLayout.Builder.obtain(t, 0, t.length, headPaint, contentW).build()
        } else null
        val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.SANS_SERIF; textSize = W * 0.040f
        }
        val srcLine = StaticLayout.Builder.obtain(source, 0, source.length, srcPaint, contentW)
            .setMaxLines(1).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val H = (pad + (header?.let { it.height + gap } ?: 0f).toFloat() + h + pad * 0.6f + srcLine.height + pad)
            .coerceAtLeast(W * 0.72f).toInt()

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paper)
        drawChrome(c, W, H)
        var y = pad
        header?.let { c.save(); c.translate(pad, y); it.draw(c); c.restore(); y += it.height + gap }
        for ((_, l) in kept) { c.save(); c.translate(pad, y); l.draw(c); c.restore(); y += l.height + gap }
        c.save(); c.translate(pad, H - pad * 0.55f - srcLine.height); srcLine.draw(c); c.restore()
        return ThreadFace(bmp, dropped, scale)
    }

    /** The border + folded corner — shared by the plain face and the thread face. */
    private fun drawChrome(c: Canvas, W: Int, H: Int) {
        val fold = W * 0.095f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = Paint.Style.STROKE; strokeWidth = W * 0.006f
        }
        val edge = border.strokeWidth / 2f
        val p = Path().apply {
            moveTo(W - fold, edge)
            lineTo(edge, edge); lineTo(edge, H - edge); lineTo(W - edge, H - edge)
            lineTo(W - edge, fold)
        }
        c.drawPath(p, border)
        c.drawLine(W - fold, edge, W - edge, fold, border)
        val underside = Path().apply {
            moveTo(W - fold, edge); lineTo(W - fold, fold); lineTo(W - edge, fold); close()
        }
        c.drawPath(underside, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })
        c.drawPath(underside, border)
    }

    /** [W] px render width; height sizes to the text with a squarish floor (a sticky is stout,
     *  never a banner). [mini] tightens padding + type for the little link-stickies. */
    fun render(text: String, source: String, W: Int = 760, mini: Boolean = false): Bitmap {
        val pad = W * (if (mini) 0.075f else 0.09f)
        val fold = W * (if (mini) 0.11f else 0.095f)
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.SANS_SERIF
            textSize = W * (if (mini) 0.075f else 0.058f)
        }
        val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.SANS_SERIF
            textSize = W * (if (mini) 0.052f else 0.040f)
        }
        val contentW = (W - 2 * pad).toInt()
        val body = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, contentW)
            .setLineSpacing(W * 0.010f, 1f).build()
        val srcLine = StaticLayout.Builder.obtain(source, 0, source.length, srcPaint, contentW)
            .setMaxLines(1).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        // Squarish floor: a near-empty sticky still reads as a sticky, not a strip.
        val H = (pad + body.height + pad * 0.6f + srcLine.height + pad)
            .coerceAtLeast(W * (if (mini) 0.52f else 0.72f)).toInt()

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paper)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = Paint.Style.STROKE; strokeWidth = W * 0.006f
        }
        // The body outline, minus the corner the fold owns.
        val edge = border.strokeWidth / 2f
        val p = Path().apply {
            moveTo(W - fold, edge)
            lineTo(edge, edge); lineTo(edge, H - edge); lineTo(W - edge, H - edge)
            lineTo(W - edge, fold)
        }
        c.drawPath(p, border)
        // The folded corner: the diagonal, and the fold's underside as a bordered triangle —
        // the one monochrome mark everyone reads as "sticky".
        c.drawLine(W - fold, edge, W - edge, fold, border)
        val underside = Path().apply {
            moveTo(W - fold, edge); lineTo(W - fold, fold); lineTo(W - edge, fold); close()
        }
        c.drawPath(underside, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })
        c.drawPath(underside, border)

        c.save(); c.translate(pad, pad); body.draw(c); c.restore()
        c.save(); c.translate(pad, H - pad * 0.55f - srcLine.height); srcLine.draw(c); c.restore()
        return bmp
    }
}
