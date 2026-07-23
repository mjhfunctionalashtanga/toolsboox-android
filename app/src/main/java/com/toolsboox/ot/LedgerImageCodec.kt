package com.toolsboox.ot

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * How a picture is encoded before it goes into a day file.
 *
 * Everything used to be `PNG, 100`, which is right for ink and wrong for photographs. PNG is
 * lossless, so a photograph keeps every sensor artefact at full cost: the 3150×4200 camera shot
 * that truncated `day-2026-09-11-v2.json` was 28.5 MB of PNG, and 39 MB once base64 had made it a
 * third larger again to live inside JSON. The same photograph as JPEG is a couple of hundred
 * kilobytes and indistinguishable on a 16-greyscale e-ink panel.
 *
 * Ink stays PNG, and that is not a detail. Handwriting is a few hard-edged black strokes on white
 * — exactly the content PNG compresses best and JPEG destroys, ringing grey fringes around every
 * line. On a panel that renders sixteen greys, those fringes are visible in a way they never are
 * on a phone.
 *
 * So the choice is by what the picture IS, not by where it is going.
 */
object LedgerImageCodec {

    /**
     * Quality for photographs. 82 is where JPEG stops being distinguishable from the source on a
     * greyscale panel while still being roughly twenty times smaller than the PNG.
     */
    const val PHOTO_QUALITY = 82

    /** Bytes for a photograph — a camera capture, a gallery pick, an article's picture. */
    fun photo(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it)
    }.toByteArray()

    /** Bytes for ink, line art, a rendered card — anything with hard edges or transparency. */
    fun ink(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /**
     * Whether [bitmap] should be treated as a photograph.
     *
     * A bitmap that carries real transparency must stay PNG whatever else it looks like, because
     * JPEG has no alpha and would flatten it onto black. Otherwise: ink is overwhelmingly pure
     * black and pure white, so a picture whose pixels are mostly NOT at the extremes is a
     * photograph. Sampled on a grid rather than every pixel — this runs on a Boox, and the answer
     * doesn't get better for looking at twelve million pixels instead of four hundred.
     */
    fun looksPhotographic(bitmap: Bitmap): Boolean {
        if (bitmap.hasAlpha() && hasTransparentPixels(bitmap)) return false
        val steps = 20
        var midtones = 0
        var counted = 0
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = bitmap.width * i / steps
                val y = bitmap.height * j / steps
                if (x >= bitmap.width || y >= bitmap.height) continue
                val p = bitmap.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                counted++
                if (luma in 24..231) midtones++
            }
        }
        if (counted == 0) return false
        return midtones * 100 / counted >= 25
    }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        val steps = 12
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = bitmap.width * i / steps
                val y = bitmap.height * j / steps
                if (x >= bitmap.width || y >= bitmap.height) continue
                if ((bitmap.getPixel(x, y) ushr 24) < 250) return true
            }
        }
        return false
    }
}
