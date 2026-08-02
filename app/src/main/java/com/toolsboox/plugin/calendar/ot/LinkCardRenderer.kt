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
        "mail" -> "✉"
        // The reply gram — a letter being ANSWERED, not merely kept. The chip is the visible
        // reply mark: on a board of ✉ MAIL cards, ↩ REPLY is the one that owes somebody words.
        "reply" -> "↩"
        else -> "🔖"
    }

    private fun kindWord(kind: String): String = when (kind) {
        "watch" -> "Watch"; "listen" -> "Listen"; "mail" -> "Mail"; "reply" -> "Reply"; else -> "Read"
    }

    /**
     * The card face: kind chip → featured image → title → excerpt → divider → "↗ Feed · host" →
     * the address itself, in mono, with the scheme stripped.
     *
     * Michael: "Link grams should include title link featured image excerpt." All four, in the
     * order the Later List row already reads them — picture on top, then title, then description.
     * That row was the only place anyone had written down what a saved link is supposed to look
     * like, so the face follows it rather than inventing a second answer. The kind chip stays
     * above the picture because the Intake quarters are read by it at a glance.
     *
     * This face used to draw title-then-image with no excerpt band at all, while the iPad's
     * [FeedNoteGram.linkFace] drew the full six bands — the two forks are meant to be one face,
     * and a card that reads differently on each device is a card he can't trust to arrange.
     *
     * EVERY BAND IS OPTIONAL AND THE HEIGHT CLOSES OVER WHAT IS ABSENT. No picture and the title
     * rides straight under the chip; no excerpt and the divider follows the title; no address (the
     * mail gram passes url "") and the source line stands alone. A link with nothing but a title
     * is a SHORT card, never a tall one with a hole in it.
     *
     * [thumb] (optional) is the entry's featured image. [sourceName] (optional) is the feed or site
     * name; when present the source line reads "↗ Feed · host" instead of the bare host.
     * [excerpt] (optional) is the piece's own first words — its blurb, not a summary.
     */
    fun render(url: String, title: String, kind: String, thumb: Bitmap? = null,
               sourceName: String = "", excerpt: String = "", W: Int = 1080): Bitmap {
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: url
        val pad = W * 0.08f
        val contentW = W - 2 * pad

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 58f
        }
        val label = title.ifBlank { host }
        val titleLayout = StaticLayout.Builder.obtain(label, 0, label.length, titlePaint,
            contentW.toInt()).setLineSpacing(10f, 1f).setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()

        // The excerpt: the piece's own first words, in the reading serif at a size that says
        // standfirst rather than pull-quote. Five lines is the cap — past that it stops being a
        // taste of the article and starts being the article.
        val blurb = excerpt.trim()
        val blurbPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.SERIF; textSize = 38f
        }
        val blurbLayout = if (blurb.isEmpty()) null else
            StaticLayout.Builder.obtain(blurb, 0, blurb.length, blurbPaint, contentW.toInt())
                .setLineSpacing(8f, 1f).setMaxLines(5)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val blurbH = blurbLayout?.height?.toFloat() ?: 0f
        val blurbGap = if (blurbH > 0f) 22f else 0f

        // Thumb band: scaled to the content width, tall images centre-cropped to a calm cap so
        // one portrait photo doesn't turn the card into a poster.
        val thumbH = thumb?.let { (contentW * it.height / it.width.coerceAtLeast(1)).coerceAtMost(620f) } ?: 0f
        val thumbGap = if (thumb != null) 30f else 0f

        // The address itself, under the source line: the card should say where it GOES, not only
        // who published it — that's the "link" in Michael's four. Dropped when it would merely
        // repeat the host word for word.
        val address = addressLine(url, host)
        val addressH = if (address.isEmpty()) 0f else 46f

        // Height: kind chip + picture + title + excerpt + source block + padding. The ceiling
        // rises with the two added bands: a card carrying a full-width photo AND a five-line
        // excerpt runs past the old 1600, and a ceiling that clips is how a face loses the very
        // excerpt it was rendered to show.
        val h = (pad + 64f + 28f + thumbH + thumbGap + titleLayout.height + blurbGap + blurbH +
            40f + 44f + addressH + pad).toInt().coerceIn(420, 1800)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paper)

        // Kind chip: glyph + word, up top. For watch/listen this is the at-a-glance mark the
        // intake board reads by (▶ WATCH / 🎧 LISTEN).
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 34f
        }
        c.drawText("${kindGlyph(kind)}  ${kindWord(kind).uppercase()}", pad, pad + 40f, chipPaint)

        var y = pad + 64f + 28f

        // Thumbnail: full content width; source rect centre-cropped when the height was capped.
        if (thumb != null) {
            val naturalH = contentW * thumb.height / thumb.width.coerceAtLeast(1)
            val src = if (naturalH > thumbH) {
                val keep = (thumb.height * thumbH / naturalH).toInt().coerceAtLeast(1)
                val top = ((thumb.height - keep) / 2).coerceAtLeast(0)
                android.graphics.Rect(0, top, thumb.width, top + keep)
            } else null
            c.drawBitmap(thumb, src,
                android.graphics.RectF(pad, y, pad + contentW, y + thumbH), Paint(Paint.FILTER_BITMAP_FLAG))
            y += thumbH + thumbGap
        }

        // Title.
        c.save(); c.translate(pad, y); titleLayout.draw(c); c.restore()
        y += titleLayout.height

        // Excerpt.
        if (blurbLayout != null) {
            y += blurbGap
            c.save(); c.translate(pad, y); blurbLayout.draw(c); c.restore()
            y += blurbH
        }
        y += 40f

        // Divider + source: "↗ Feed · host" when the feed's name is known, else the bare host.
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; strokeWidth = 2f }
        c.drawLine(pad, y, W - pad, y, rulePaint)
        val hostPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC); textSize = 34f
        }
        val source = listOf(sourceName.trim(), host).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        c.drawText(ellipsize("↗  $source", hostPaint, contentW), pad, y + 40f, hostPaint)

        if (address.isNotEmpty()) {
            val addrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = muted; typeface = Typeface.MONOSPACE; textSize = 28f
            }
            c.drawText(ellipsize(address, addrPaint, contentW), pad, y + 86f, addrPaint)
        }
        return bmp
    }

    /** The address as a card wants to read it: scheme, "www." and trailing slash dropped. Empty
     *  when there's no URL to speak of, or when the whole address IS the host and the source line
     *  above already said it — a card should not print the same word twice. */
    private fun addressLine(url: String, host: String): String {
        var s = url.trim()
        if (!s.startsWith("http", ignoreCase = true)) return ""
        for (p in listOf("https://", "http://")) if (s.startsWith(p, ignoreCase = true)) s = s.drop(p.length)
        if (s.startsWith("www.", ignoreCase = true)) s = s.drop(4)
        s = s.trimEnd('/')
        return if (s.equals(host, ignoreCase = true)) "" else s
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }
}
