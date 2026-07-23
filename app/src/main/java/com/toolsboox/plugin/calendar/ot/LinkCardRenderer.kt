package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import java.net.URI

/**
 * A link, drawn as a card you can put down.
 *
 * A website, a video, a podcast — the same object: a title, the place it lives, and a mark for
 * what kind of thing it is, so a link is no longer a line of blue text but a card that sits on a
 * page, moves, connects, and taps back to open. The Boox twin of "share to Ledger", but placed
 * rather than filed.
 *
 * Kind glyphs match the intake lanes: read / watch / listen. Baked flat and grayscale for e-ink.
 */
object LinkCardRenderer {

    private val paper = Color.rgb(0xFB, 0xF8, 0xF1)
    private val ink = Color.rgb(0x1F, 0x1F, 0x1F)
    private val muted = Color.rgb(0x6A, 0x6A, 0x6A)
    private val rule = Color.rgb(0xC8, 0xC8, 0xC8)

    fun kindGlyph(kind: String): String = when (kind) {
        "watch" -> "▶"
        "listen" -> "🎧"
        else -> "🔖"
    }

    private fun kindWord(kind: String): String = when (kind) {
        "watch" -> "Watch"; "listen" -> "Listen"; else -> "Read"
    }

    fun render(url: String, title: String, kind: String, W: Int = 1080): Bitmap {
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: url
        val pad = W * 0.08f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 58f
        }
        val label = title.ifBlank { host }
        val titleLayout = StaticLayout.Builder.obtain(label, 0, label.length, titlePaint,
            (W - 2 * pad).toInt()).setLineSpacing(10f, 1f).setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()

        // Height: kind chip + title + host line + padding.
        val h = (pad + 64f + 28f + titleLayout.height + 40f + 44f + pad).toInt().coerceIn(420, 1200)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paper)

        // Kind chip: glyph + word, up top.
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 34f
        }
        c.drawText("${kindGlyph(kind)}  ${kindWord(kind).uppercase()}", pad, pad + 40f, chipPaint)

        // Title.
        var y = pad + 64f + 28f
        c.save(); c.translate(pad, y); titleLayout.draw(c); c.restore()
        y += titleLayout.height + 30f

        // Divider + host.
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; strokeWidth = 2f }
        c.drawLine(pad, y, W - pad, y, rulePaint)
        y += 40f
        val hostPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC); textSize = 34f
        }
        val hostShown = "↗  " + host
        c.drawText(ellipsize(hostShown, hostPaint, W - 2 * pad), pad, y, hostPaint)
        return bmp
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }
}
