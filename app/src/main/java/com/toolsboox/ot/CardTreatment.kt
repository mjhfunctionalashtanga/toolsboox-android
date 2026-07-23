package com.toolsboox.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Skeuomorphic treatments baked into a card's pixels: a paper ground, a hairline edge and
 * translucent tape over the top corners.
 *
 * Baked rather than stored as a flag, so the look syncs to every device with the image itself
 * and costs nothing to render. Grayscale throughout — these read the same on e-ink.
 *
 * Lives here rather than on a Fragment because two callers need it: the "Shapes & cute cuts"
 * menu, which applies one on demand, and [com.toolsboox.plugin.calendar.ot.PickingsPlacement],
 * which applies one by default the moment a card is placed.
 */
object CardTreatment {
    private const val EDGE = 0xFF9A9A9A.toInt()
    private const val WELL = 0xFFB8B8B8.toInt()
    private const val TAPE_FILL = 0x59AFAFAF     // translucent gray — the card ghosts through
    private const val TAPE_EDGE = 0x66808080

    /**
     * A plain card: even paper margin all round, hairline edge, optional tape at the top corners.
     *
     * This is what an ink crop wants. A crop arrives as bare handwriting on transparency, and
     * without a ground it reads as marks that happen to be lying on the page rather than as a
     * thing someone put there. The margin and the edge are what make it an object.
     */
    fun card(src: Bitmap, tape: Boolean = true): Bitmap {
        val side = (src.width * 0.05f).coerceAtLeast(12f)
        val overhang = if (tape) (src.width * 0.06f).coerceAtLeast(16f) else 0f
        val out = Bitmap.createBitmap(
            (src.width + side * 2 + overhang * 2).toInt(),
            (src.height + side * 2 + overhang).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(out)
        val frame = RectF(
            overhang, overhang,
            overhang + src.width + side * 2, overhang + src.height + side * 2
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        c.drawRect(frame, paint)
        c.drawBitmap(src, frame.left + side, frame.top + side, null)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        paint.color = EDGE
        c.drawRect(frame, paint)
        if (tape) {
            drawTape(c, frame.left, frame.top, -35f, src.width)
            drawTape(c, frame.right, frame.top, 35f, src.width)
        }
        return out
    }

    /**
     * A classic polaroid: even white border, deep chin at the bottom, hairline outline —
     * optionally with two tape strips over the top corners.
     */
    fun polaroid(src: Bitmap, tape: Boolean): Bitmap {
        val side = (src.width * 0.06f).coerceAtLeast(14f)
        val chin = (src.width * 0.22f).coerceAtLeast(48f)
        // Room for the tape to hang past the frame's top corners.
        val overhang = if (tape) (src.width * 0.06f).coerceAtLeast(16f) else 0f
        val out = Bitmap.createBitmap(
            (src.width + side * 2 + overhang * 2).toInt(),
            (src.height + side + chin + overhang).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(out)
        val frame = RectF(
            overhang, overhang, overhang + src.width + side * 2, overhang + src.height + side + chin
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        c.drawRect(frame, paint)
        c.drawBitmap(src, frame.left + side, frame.top + side, null)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        paint.color = EDGE
        c.drawRect(frame, paint)          // the print's edge
        paint.color = WELL
        c.drawRect(frame.left + side, frame.top + side,
            frame.left + side + src.width, frame.top + side + src.height, paint)  // photo well
        if (tape) {
            drawTape(c, frame.left, frame.top, -35f, src.width)
            drawTape(c, frame.right, frame.top, 35f, src.width)
        }
        return out
    }

    /** Just the tape, no frame — two strips across the image's top corners. */
    fun tapeOnly(src: Bitmap): Bitmap {
        val overhang = (src.width * 0.06f).coerceAtLeast(16f)
        val out = Bitmap.createBitmap(
            (src.width + overhang * 2).toInt(), (src.height + overhang).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(out)
        c.drawBitmap(src, overhang, overhang, null)
        drawTape(c, overhang, overhang, -35f, src.width)
        drawTape(c, overhang + src.width, overhang, 35f, src.width)
        return out
    }

    /** One translucent tape strip centred on ([x], [y]), rotated by [angle] degrees. */
    fun drawTape(c: Canvas, x: Float, y: Float, angle: Float, refWidth: Int) {
        val tw = (refWidth * 0.26f).coerceAtLeast(60f)
        val th = (refWidth * 0.085f).coerceAtLeast(22f)
        c.save()
        c.rotate(angle, x, y)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = TAPE_FILL
        c.drawRect(x - tw / 2, y - th / 2, x + tw / 2, y + th / 2, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        paint.color = TAPE_EDGE
        c.drawRect(x - tw / 2, y - th / 2, x + tw / 2, y + th / 2, paint)
        c.restore()
    }
}
