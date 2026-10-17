package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.toolsboox.da.Attachment
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Box
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Font
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Kind
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Piece
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Shape
import java.io.File

/**
 * DRAWS THE FACE [PickFaceLayout] laid out — the one card every pick wears now, with what was added
 * to it ON it (Michael 2026-10-12: "Let the photo/video/note show on the pick."): the typed note, the
 * handwriting itself, the photo and the video's first frame with ▶ in a band, each voice note a 🎤
 * chip. iOS twin: App/PickFace.swift (UIGraphicsImageRenderer at scale 1, px). [QuoteCardRenderer]
 * delegates here, so every quote card and every highlight pick is one face.
 */
object PickFace {

    private val paper = Color.rgb(0xFB, 0xF8, 0xF1)
    private val ink = Color.rgb(0x1F, 0x1F, 0x1F)
    private val accent = Color.rgb(0x2A, 0x2A, 0x2A)
    private val muted = Color.rgb(0x6A, 0x6A, 0x6A)

    /** A picture on the pick, decoded: a photo, or a video's first frame (null: none read). */
    data class Visual(val kind: Kind, val id: String, val image: Bitmap?, val seconds: Int = 0)

    /**
     * What rides on a pick, ready to draw — the line's attachments decoded ([fromLine]), or the
     * studio's waiting adds. Empty = today's quote card.
     */
    data class Adds(
        val note: String = "",
        val ink: Bitmap? = null, val inkId: String = "",
        /** Newest first. */
        val visuals: List<Visual> = emptyList(),
        /** Newest first — id + seconds. */
        val voices: List<Piece> = emptyList(),
        /** The selection's own picture ("Picture on the pick"), shown only with nothing of yours. */
        val picture: Bitmap? = null,
    ) {
        val isEmpty: Boolean get() = note.isBlank() && ink == null && visuals.isEmpty() && voices.isEmpty() && picture == null
        companion object { val NONE = Adds() }
    }

    data class Rendered(val bitmap: Bitmap, val layout: PickFaceLayout.Layout)

    fun typeface(f: Font): Typeface = when (f) {
        Font.SERIF -> Typeface.SERIF
        Font.SERIF_BOLD -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        Font.SERIF_ITALIC -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        Font.SANS_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        Font.SANS_ITALIC -> Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    }

    private fun paint(f: Font, size: Int, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; typeface = typeface(f); textSize = size.toFloat()
    }

    private fun staticLayout(text: String, p: TextPaint, width: Int, spacing: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setLineSpacing(spacing.toFloat(), 1f).build()

    /** The device's fonts as a [PickFaceLayout.Measure]. */
    object RealMeasure : PickFaceLayout.Measure {
        override fun height(text: String, size: Int, font: Font, width: Int, spacing: Int): Int =
            if (text.isEmpty()) 0 else staticLayout(text, paint(font, size), width, spacing).height
        override fun width(text: String, size: Int, font: Font): Int =
            kotlin.math.ceil(paint(font, size).measureText(text).toDouble()).toInt()
        override fun lineHeight(size: Int, font: Font): Int =
            paint(font, size).fontMetricsInt.let { it.descent - it.ascent }
    }

    /** The ladder asks the same heights again stage after stage — each a StaticLayout; asked once. */
    private class Memo(private val m: PickFaceLayout.Measure) : PickFaceLayout.Measure {
        private val heights = HashMap<String, Int>()
        override fun height(text: String, size: Int, font: Font, width: Int, spacing: Int): Int =
            heights.getOrPut("$size|${font.ordinal}|$width|$spacing|${text.length}|${text.hashCode()}") {
                m.height(text, size, font, width, spacing)
            }
        override fun width(text: String, size: Int, font: Font): Int = m.width(text, size, font)
        override fun lineHeight(size: Int, font: Font): Int = m.lineHeight(size, font)
    }

    /** The shape a W×H request names: the four formats, and H = 0 (or any other size at 1080 wide)
     *  as Free. Null for a size that is no pick shape (the synthesis back card) — drawn as before. */
    fun shapeOf(w: Int, h: Int): Shape? = when {
        h <= 0 && w == 1080 -> Shape.FREE
        else -> Shape.entries.firstOrNull { it != Shape.FREE && it.w == w && it.h == h }
    }

    /**
     * Lay out and draw a pick. [passage] is the card's words (a passage, or a note's words for a
     * whole-piece note); [source] the footer line.
     */
    fun render(
        shape: Shape, passage: String, source: String?, adds: Adds = Adds.NONE,
        author: String? = null, cover: Bitmap? = null,
    ): Rendered {
        val inkPiece = adds.ink?.let { Piece(Kind.INK, adds.inkId, it.width, it.height) }
        val visuals = adds.visuals.map { v ->
            Piece(v.kind, v.id, v.image?.width ?: 16, v.image?.height ?: 9, v.seconds)
        }
        val picture = adds.picture?.let { Piece(Kind.PHOTO, "", it.width, it.height) }
        val input = PickFaceLayout.Input(
            shape = shape, passage = passage, note = adds.note, ink = inkPiece, visuals = visuals,
            voices = adds.voices, picture = picture, source = source.orEmpty(), author = author.orEmpty(),
            cover = cover?.let { Piece(Kind.PHOTO, "", it.width, it.height) })
        val l = PickFaceLayout.layout(input, Memo(RealMeasure))
        val bmp = Bitmap.createBitmap(l.w, l.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paper)

        // The big “ — every version.
        c.drawText("“", l.markX.toFloat(), l.markBaseline.toFloat(), paint(Font.SERIF_BOLD, l.markSize, accent))

        drawText(c, l.passage.text, l.passage.box, paint(Font.SERIF, l.passage.size), l.passageSpacing)
        l.note?.let { drawText(c, it.text, it.box, paint(Font.SERIF_ITALIC, it.size, muted), l.noteSpacing) }
        val inkBox = l.ink
        if (adds.ink != null && inkBox != null)
            c.drawBitmap(adds.ink, null, rect(inkBox), Paint(Paint.FILTER_BITMAP_FLAG))

        val L = shape == Shape.LANDSCAPE
        val images: Map<String, Visual> = adds.visuals.associateBy { it.id }
        for (t in l.tiles) {
            val img = if (t.piece.id.isEmpty()) adds.picture else images[t.piece.id]?.image
            drawTile(c, t, img, if (L) 0f else l.radius.toFloat(), L)
        }
        for (chip in l.chips) drawChip(c, chip, l)

        drawFooter(c, l, source.orEmpty(), author.orEmpty(), cover)
        return Rendered(bmp, l)
    }

    private fun rect(b: Box) = RectF(b.x.toFloat(), b.y.toFloat(), b.right.toFloat(), b.bottom.toFloat())

    private fun drawText(c: Canvas, text: String, b: Box, p: TextPaint, spacing: Int) {
        if (text.isEmpty()) return
        val sl = staticLayout(text, p, b.w, spacing)
        c.save(); c.translate(b.x.toFloat(), b.y.toFloat()); sl.draw(c); c.restore()
    }

    /** Aspect-fill [img] into [b], centred, clipped to a rounded rect of [radius]. */
    private fun fill(c: Canvas, img: Bitmap, b: Box, radius: Float) {
        val dst = rect(b)
        val scale = maxOf(b.w.toFloat() / img.width, b.h.toFloat() / img.height)
        val sw = (b.w / scale).toInt().coerceIn(1, img.width)
        val sh = (b.h / scale).toInt().coerceIn(1, img.height)
        val sx = (img.width - sw) / 2; val sy = (img.height - sh) / 2
        c.save()
        if (radius > 0f) c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        c.drawBitmap(img, Rect(sx, sy, sx + sw, sy + sh), dst, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    private fun s(L: Boolean, v: Int) = if (L) (v * 2 + 1) / 3 else v

    private fun drawTile(c: Canvas, t: PickFaceLayout.Tile, img: Bitmap?, radius: Float, L: Boolean) {
        val b = t.box
        if (img != null) fill(c, img, b, radius)
        else {
            // No frame read (or the picture isn't here yet): an ink tile, so the ▶ still reads.
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
            if (radius > 0f) c.drawRoundRect(rect(b), radius, radius, p) else c.drawRect(rect(b), p)
            if (t.piece.kind == Kind.VIDEO) {
                val label = AddToPickWords.videoNoPoster(PickFaceLayout.clock(t.piece.seconds))
                val tp = paint(Font.SANS_BOLD, s(L, 30), paper)
                val w = tp.measureText(label)
                c.drawText(label, b.x + (b.w - w) / 2f, (t.disc?.bottom ?: b.y) + s(L, 30) * 1.6f, tp)
            }
        }
        t.disc?.let { d ->
            val cx = d.x + d.w / 2f; val cy = d.y + d.h / 2f; val r = d.w / 2f
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper; alpha = 0xD9 })
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink; style = Paint.Style.STROKE; strokeWidth = s(L, 4).toFloat()
            })
            val tw = s(L, 52) / 2f; val th = s(L, 60) / 2f; val nudge = s(L, 6).toFloat()
            c.drawPath(Path().apply {
                moveTo(cx - tw + nudge, cy - th); lineTo(cx - tw + nudge, cy + th); lineTo(cx + tw + nudge, cy); close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        }
        t.pill?.let { pill(c, it, t.pillText, s(L, 30), s(L, 12)) }
        t.more?.let { pill(c, it, t.moreText, s(L, 32), s(L, 12)) }
    }

    /** Paper words on an ink pill (α 0.75), round ends. */
    private fun pill(c: Canvas, b: Box, text: String, size: Int, padX: Int) {
        val r = b.h / 2f
        c.drawRoundRect(rect(b), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 0xBF })
        val tp = paint(Font.SANS_BOLD, size, paper)
        val fm = tp.fontMetricsInt
        val base = b.y + (b.h - (fm.descent - fm.ascent)) / 2f - fm.ascent
        c.drawText(text, (b.x + padX).toFloat(), base, tp)
    }

    private fun drawChip(c: Canvas, chip: PickFaceLayout.Chip, l: PickFaceLayout.Layout) {
        val b = chip.box
        val r = b.h / 2f
        c.drawRoundRect(rect(b), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper })
        val ring = l.chipRing.toFloat()
        c.drawRoundRect(RectF(b.x + ring / 2, b.y + ring / 2, b.right - ring / 2, b.bottom - ring / 2), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = ring })
        val tp = paint(Font.SANS_BOLD, l.chipText, ink)
        val tw = tp.measureText(chip.label)
        val fm = tp.fontMetricsInt
        val base = b.y + (b.h - (fm.descent - fm.ascent)) / 2f - fm.ascent
        c.drawText(chip.label, b.x + (b.w - tw) / 2f, base, tp)
    }

    private fun drawFooter(c: Canvas, l: PickFaceLayout.Layout, source: String, author: String, cover: Bitmap?) {
        val f = l.footer
        f.cover?.let { b -> if (cover != null) c.drawBitmap(cover, null, rect(b), Paint(Paint.FILTER_BITMAP_FLAG)) }
        f.divider?.let { c.drawRect(rect(it), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }) }
        if (source.isNotBlank()) {
            val sp = paint(Font.SANS_BOLD, f.sourceSize)
            c.drawText(ellipsize(source.trim(), sp, f.width.toFloat()), f.x.toFloat(), f.sourceBaseline.toFloat(), sp)
        }
        if (author.isNotBlank() && f.authorSize > 0) {
            val ap = paint(Font.SANS_ITALIC, f.authorSize, muted)
            c.drawText(ellipsize(author.trim(), ap, f.width.toFloat()), f.x.toFloat(), f.authorBaseline.toFloat(), ap)
        }
    }

    private fun ellipsize(text: String, p: TextPaint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    // ── From a Log line to what rides on the pick ──────────────────────────────────────────────

    /** Handwriting rides the line as a PHOTO attachment named ink-… (both apps already). */
    const val INK_PREFIX = PickFaceAdds.INK_PREFIX

    fun isInk(a: Attachment) = PickFaceAdds.isInk(a)

    /** [PickFaceAdds.newestFirst] — the line's attachments newest first, by date (iOS's rule). */
    fun newestFirst(attachments: List<Attachment>): List<Attachment> = PickFaceAdds.newestFirst(attachments)

    private fun isVisual(a: Attachment) = PickFaceAdds.isVisual(a)

    /** [PickFaceAdds.missing] — the drawn files not on this device yet, worth waiting for. */
    fun missing(dir: File, attachments: List<Attachment>): List<Attachment> = PickFaceAdds.missing(dir, attachments)

    /**
     * What rides on a line, decoded for the face — the newest handwriting (trimmed to its ink + 16),
     * your photos and videos newest first (a video as its first frame), your voice notes newest
     * first (by date — [newestFirst]). A photo or handwriting not on this device is LEFT OFF the
     * face, never drawn as a dark tile (iOS's rule); a video whose frame can't be read keeps its
     * place as the ink tile with ▶ (pin §7). Off the main thread: it decodes files.
     */
    fun fromLine(dir: File, note: String?, attachments: List<Attachment>, picture: Bitmap? = null): Adds {
        val newest = newestFirst(attachments)
        val inkAtt = newest.firstOrNull { isInk(it) && File(dir, it.filename).exists() }
        val inkBmp = inkAtt?.let { decode(File(dir, it.filename), 1400) }?.let { trimInk(it) }
        val shown = mutableListOf<Visual>()
        val rest = mutableListOf<Visual>()
        for (a in newest.filter { isVisual(it) }) {
            val kind = if (a.kind == Attachment.Kind.VIDEO) Kind.VIDEO else Kind.PHOTO
            if (shown.size >= 2) { rest += Visual(kind, a.id, null, a.duration?.toInt() ?: 0); continue }
            val f = File(dir, a.filename)
            if (kind == Kind.VIDEO) {
                val img = if (f.exists()) runCatching { AvPoster.firstFrame(f) }.getOrNull()?.let { fit(it, 1024) } else null
                shown += Visual(kind, a.id, img, seconds(a, f))
            } else {
                val img = decode(f, 1024) ?: continue
                shown += Visual(kind, a.id, img, 0)
            }
        }
        val voices = newest.filter { it.kind == Attachment.Kind.AUDIO }
            .map { Piece(Kind.VOICE, it.id, seconds = it.duration?.toInt() ?: 0) }
        return Adds(note = note.orEmpty(), ink = inkBmp, inkId = if (inkBmp != null) inkAtt?.id.orEmpty() else "",
            visuals = shown + rest, voices = voices, picture = picture)
    }

    private fun seconds(a: Attachment, f: File): Int =
        a.duration?.toInt()?.takeIf { it > 0 }
            ?: if (a.kind == Attachment.Kind.VIDEO && f.exists()) AvPoster.durationMs(f) / 1000 else 0

    /** Decode a local file no bigger than [max] on its long edge; null when it isn't here. */
    fun decode(file: File, max: Int): Bitmap? = runCatching {
        if (!file.exists()) return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        fit(bmp, max)
    }.getOrNull()

    private fun fit(bmp: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= max) return bmp
        val r = max.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true)
    }

    /** The ink, trimmed to what was written + a 16 px margin (pin §4) — the pad's white kept. */
    fun trimInk(src: Bitmap, margin: Int = 16): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                if (Color.alpha(c) < 16) continue
                if (Color.red(c) > 232 && Color.green(c) > 232 && Color.blue(c) > 232) continue
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        val x0 = (minX - margin).coerceAtLeast(0); val y0 = (minY - margin).coerceAtLeast(0)
        val x1 = (maxX + margin + 1).coerceAtMost(w); val y1 = (maxY + margin + 1).coerceAtMost(h)
        return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
    }
}
