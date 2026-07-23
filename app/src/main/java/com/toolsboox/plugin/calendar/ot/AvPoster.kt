package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import com.toolsboox.da.Attachment
import java.io.File

/**
 * The still face of an A/V gram.
 *
 * An audio or video gram rides the board as an ordinary picking whose image is this poster, so
 * every surface that already draws grams shows it without knowing anything about media. Nothing
 * animates until it's tapped.
 *
 * Video posters are the clip's own first frame — real data. Audio posters are a drawn card: a
 * cassette, a play mark, the length. Deliberately NOT a waveform, since without decoding the
 * audio any squiggle would only be pretending to be data.
 *
 * Drawn for e-ink: black on white, one line thick, no alpha or gradients.
 */
object AvPoster {

    private const val W = 640
    private const val H = 420

    /** Poster for [file], or null if a video frame couldn't be read and no card applies. */
    fun poster(file: File, kind: Attachment.Kind, durationMs: Int, title: String): Bitmap? = when (kind) {
        Attachment.Kind.VIDEO -> videoPoster(file, durationMs, title) ?: card(durationMs, title, video = true)
        Attachment.Kind.AUDIO -> card(durationMs, title, video = false)
        Attachment.Kind.PHOTO -> null
    }

    /** Length in milliseconds straight off the file, or 0 when it can't be read. */
    fun durationMs(file: File): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** mm:ss, the way a tape counter reads. */
    fun clock(durationMs: Int): String {
        if (durationMs <= 0) return ""
        val total = durationMs / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** The clip's first readable frame, letterboxed onto the poster with a play mark over it. */
    private fun videoPoster(file: File, durationMs: Int, title: String): Bitmap? {
        val mmr = MediaMetadataRetriever()
        val frame = try {
            mmr.setDataSource(file.absolutePath)
            mmr.getFrameAtTime(0) ?: mmr.frameAtTimeFallback()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }

        val src = frame ?: return null
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        // Fit the frame inside the poster, both axes, centred.
        val scale = minOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val fw = src.width * scale
        val fh = src.height * scale
        val dst = RectF((W - fw) / 2f, (H - fh) / 2f, (W + fw) / 2f, (H + fh) / 2f)
        canvas.drawBitmap(src, Rect(0, 0, src.width, src.height), dst, null)
        runCatching { src.recycle() }

        frameAndMarks(canvas, durationMs, title, playMark = true)
        return out
    }

    private fun MediaMetadataRetriever.frameAtTimeFallback(): Bitmap? =
        runCatching { getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()

    /** The drawn card, used for audio and for video whose first frame wouldn't read. */
    private fun card(durationMs: Int, title: String, video: Boolean): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        }

        // A cassette: body, two spools, and the window between them.
        val body = RectF(120f, 130f, W - 120f, H - 150f)
        canvas.drawRoundRect(body, 14f, 14f, line)
        val window = RectF(body.left + 34f, body.top + 30f, body.right - 34f, body.top + 108f)
        canvas.drawRoundRect(window, 8f, 8f, line)
        val spoolY = window.centerY()
        canvas.drawCircle(window.left + 52f, spoolY, 26f, line)
        canvas.drawCircle(window.right - 52f, spoolY, 26f, line)
        canvas.drawLine(window.left + 52f, spoolY, window.right - 52f, spoolY, line)

        if (video) {
            // A strip of sprocket holes along the bottom says "this one moves".
            var x = body.left + 22f
            while (x < body.right - 22f) {
                canvas.drawRect(x, body.bottom - 34f, x + 16f, body.bottom - 16f, line)
                x += 30f
            }
        }

        playMark(canvas, W / 2f, body.bottom + 6f, 0f)
        marks(canvas, durationMs, title)
        return out
    }

    /** Border, play mark and captions over an already-drawn poster. */
    private fun frameAndMarks(canvas: Canvas, durationMs: Int, title: String, playMark: Boolean) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        canvas.drawRect(2f, 2f, W - 2f, H - 2f, line)
        if (playMark) playMark(canvas, W / 2f, H / 2f, 44f)
        marks(canvas, durationMs, title)
    }

    /** A play triangle in a ring, on a white disc so it reads over any frame. */
    private fun playMark(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val r = if (radius > 0f) radius else 34f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }

        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, line)

        val t = android.graphics.Path().apply {
            val s = r * 0.52f
            moveTo(cx - s * 0.6f, cy - s)
            lineTo(cx - s * 0.6f, cy + s)
            lineTo(cx + s * 0.95f, cy)
            close()
        }
        canvas.drawPath(t, solid)
    }

    /** Title along the top, tape-counter length along the bottom. */
    private fun marks(canvas: Canvas, durationMs: Int, title: String) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 30f; textAlign = Paint.Align.CENTER
        }

        val name = title.trim()
        if (name.isNotEmpty()) {
            canvas.drawText(ellipsise(name, text, W - 80f), W / 2f, 62f, text)
        }

        val length = clock(durationMs)
        if (length.isNotEmpty()) {
            text.textSize = 26f
            canvas.drawText(length, W / 2f, H - 34f, text)
        }
    }

    private fun ellipsise(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}
