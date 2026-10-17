package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Renders a text highlight/annotation into a shareable "cute card" bitmap — the Boox mirror of
 * the iPad's `QuoteCardView`. Cards come from annotations/highlights (a quoted passage + source),
 * not just lasso'd ink. Portrait 1080×1350 (matching the iPad's 540×675 @2×).
 */
object QuoteCardRenderer {

    private val paper = Color.rgb(0xFB, 0xF8, 0xF1)
    private val ink = Color.rgb(0x1F, 0x1F, 0x1F)
    private val accent = Color.rgb(0x2A, 0x2A, 0x2A)
    private val muted = Color.rgb(0x6A, 0x6A, 0x6A)

    /** Gram formats (px) — square / portrait / landscape / story. Mirrors the iOS studio. */
    enum class Format(val w: Int, val h: Int, val label: String) {
        SQUARE(1080, 1080, "Square"), PORTRAIT(1080, 1350, "Portrait"),
        LANDSCAPE(1280, 720, "Landscape"), STORY(1080, 1920, "Story")
    }

    /**
     * [H] = 0 sizes the card to its text instead of clipping it.
     *
     * A card with a fixed height draws the quote from the top and the footer at the bottom, so a
     * passage longer than the box runs straight off the end — which is what made the synthesis
     * cards square with their last lines missing. Passing H = 0 measures the wrapped quote and
     * note first and makes the bitmap exactly tall enough (within sane bounds), so a card always
     * shows all of what it says.
     */
    fun render(quote: String, source: String? = null, note: String? = null,
               W: Int = 1080, H: Int = 1350,
               author: String? = null, cover: Bitmap? = null,
               /** What rides on the pick (add-to-pick pin §4) — drawn ON the face. */
               adds: PickFace.Adds? = null): Bitmap {
        // ONE FACE (add-to-pick pin §4): every pick shape — Free (H = 0) and the four formats — is
        // PickFace's layout, int for int with iOS QuoteCard; the typed note rides as the face's
        // note. A size that is no pick shape (the synthesis back card) draws as it always did.
        val shape = PickFace.shapeOf(W, H)
        if (shape != null) {
            val base = adds ?: PickFace.Adds.NONE
            val words = listOf(note?.trim().orEmpty(), base.note.trim()).filter { it.isNotEmpty() }.joinToString("\n")
            return PickFace.render(shape, quote.trim(), source, base.copy(note = words), author, cover).bitmap
        }
        return legacy(quote, source, note, W, H, author, cover)
    }

    /** The card as drawn before the one face — kept for sizes that are no pick shape. */
    private fun legacy(quote: String, source: String?, note: String?, W: Int, H: Int,
                       author: String?, cover: Bitmap?): Bitmap {
        val PAD = W * 0.089f

        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = if (quote.length > 220) 180f else 240f
        }
        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.SERIF
            textSize = if (quote.length > 220) 48f else 60f
        }
        val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textSize = 40f
        }
        // Footer band: cover thumbnail / divider + one or two lines of source & author.
        val footerBand = 120f

        // THE WHOLE PASSAGE, ALWAYS (Michael, 09-13, of the iOS twin: "it could not capture all
        // of the highlight" — this card clipped the same way: 48pt past 220 chars and nothing
        // past that, so a long highlight ran under the footer and off the canvas). The type
        // steps DOWN until the passage fits the room the card has, to a 30px floor; past the
        // floor a fixed-height card GROWS (to 2600) rather than lose a line.
        val nh = if (note.isNullOrBlank()) 0f else 36f + measure(note.trim(), notePaint, W, PAD, 8f)
        val top = PAD + markPaint.textSize * 0.5f + 24f
        val chrome = top + nh + 48f + footerBand + PAD
        var qh = measure(quote.trim(), quotePaint, W, PAD, 12f)
        // A fixed card shrinks the type to its room; a free card (H = 0) may grow to 2600 first
        // and shrinks only past that — either way no line is lost.
        val room = (if (H > 0) H else 2600) - chrome
        while (qh > room && quotePaint.textSize > 30f) {
            quotePaint.textSize = (quotePaint.textSize - maxOf(1f, quotePaint.textSize * 0.06f)).coerceAtLeast(30f)
            qh = measure(quote.trim(), quotePaint, W, PAD, 12f)
        }
        val needed = (chrome + qh).toInt()
        val height = if (H > 0 && needed <= H) H else needed.coerceIn(560, 2600)

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paper)

        // Opening quotation mark — big, bold, accent.
        canvas.drawText("“", PAD, PAD + markPaint.textSize * 0.72f, markPaint)

        var y = PAD + markPaint.textSize * 0.5f
        y += 24f
        y = drawWrapped(canvas, quote.trim(), quotePaint, y, extraLineSpacing = 12f, w = W, pad = PAD)

        // Optional note — italic, muted accent.
        if (!note.isNullOrBlank()) {
            y += 36f
            y = drawWrapped(canvas, note.trim(), notePaint, y, extraLineSpacing = 8f, w = W, pad = PAD)
        }
        @Suppress("NAME_SHADOWING") val H = height

        // Footer: (optional) book cover thumbnail on the left, then title (source) + author.
        val footerY = H - PAD
        var textX = PAD
        if (cover != null) {
            val cw = 150f
            val ch = (cw * cover.height / cover.width.coerceAtLeast(1)).coerceAtLeast(1f)
            val dst = android.graphics.RectF(PAD, footerY - ch, PAD + cw, footerY)
            canvas.drawBitmap(cover, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            textX = PAD + cw + 28f
        } else {
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            canvas.drawRect(PAD, footerY - 92f, PAD + 108f, footerY - 84f, dividerPaint)
        }
        val textW = W - textX - PAD
        val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 40f
        }
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC); textSize = 36f
        }
        // With a cover, stack title over author beside it; without, keep source on one line.
        if (!source.isNullOrBlank())
            canvas.drawText(ellipsize(source.trim(), srcPaint, textW),
                textX, footerY - (if (!author.isNullOrBlank()) 56f else 30f), srcPaint)
        if (!author.isNullOrBlank())
            canvas.drawText(ellipsize(author.trim(), authorPaint, textW), textX, footerY - 12f, authorPaint)
        return bmp
    }

    /** Height the wrapped [text] will occupy at the card width — for sizing before drawing. */
    private fun measure(text: String, paint: TextPaint, w: Int, pad: Float, extraLineSpacing: Float): Float {
        val width = (w - 2 * pad).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(extraLineSpacing, 1f).build().height.toFloat()
    }

    /** Draw [text] wrapped to the card width starting at [y]; returns the y after the last line. */
    private fun drawWrapped(canvas: Canvas, text: String, paint: TextPaint, y: Float,
                            extraLineSpacing: Float, w: Int, pad: Float): Float {
        val width = (w - 2 * pad).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(extraLineSpacing, 1f)
            .build()
        canvas.save()
        canvas.translate(pad, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }
}
