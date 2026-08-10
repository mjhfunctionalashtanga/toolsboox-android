package com.toolsboox.plugin.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The picture on the front of a book.
 *
 * Michael's punchlist: "All Books surface with pictures of books and an add option." A shelf of
 * filenames is a list; a shelf of covers is a shelf, and the difference is entirely about how fast
 * you find the one you meant.
 *
 * Three sources, in descending order of how much they tell you:
 *
 *  • EPUB — the real cover, pulled out of the zip. No OPF parsing: the manifest would be the
 *    correct route and it costs a second pass over the archive to answer a question the FILENAMES
 *    already answer nearly always. An image whose path says "cover" is the cover.
 *  • CBZ — the first image in the archive, which is the front of a comic by construction.
 *  • Anything else (PDF, audiobooks, formats we cannot open) — a DRAWN cover: title and author on
 *    a book-shaped card. Not a placeholder, a legible object; the point of the shelf is to be
 *    scannable, and a grid of identical grey rectangles is worse than a grid of filenames.
 *
 * Cached to disk by name+size, because unzipping every book on every shelf draw is precisely the
 * kind of work that makes an e-ink device feel broken. Size is in the key so a replaced book — a
 * better scan, a fixed EPUB — gets a fresh cover rather than the stale one forever.
 */
object BookCovers {

    private const val W = 320
    private const val H = 460

    private fun cacheDir(context: Context) = File(context.cacheDir, "covers").apply { mkdirs() }

    private fun cacheFile(context: Context, entry: BookshelfSource.Entry) =
        File(cacheDir(context), "${entry.sizeBytes}-${entry.name.replace('/', '_')}.png")

    /**
     * The cover for [entry]. Blocking — call it off the main thread.
     *
     * Never returns null: a book with no extractable art still gets a drawn one, so the caller
     * never has to decide what an absent cover looks like and the grid never has a hole in it.
     */
    fun cover(context: Context, entry: BookshelfSource.Entry): Bitmap {
        val cached = cacheFile(context, entry)
        if (cached.exists()) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }
        val art = runCatching { extract(context, entry) }.getOrNull()
        val bmp = art ?: drawn(entry)
        runCatching {
            cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        return bmp
    }

    /** Whether a cover is already cached — lets a caller draw the shelf now and fill art in after. */
    fun isCached(context: Context, entry: BookshelfSource.Entry): Boolean =
        cacheFile(context, entry).exists()

    private fun extract(context: Context, entry: BookshelfSource.Entry): Bitmap? = when (entry.extension) {
        "epub" -> fromZip(context, entry) { path ->
            val p = path.lowercase()
            p.contains("cover") && (p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png"))
        }
        "cbz" -> fromZip(context, entry) { path ->
            val p = path.lowercase()
            p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png")
        }
        "pdf" -> firstPdfPage(context, entry)
        else -> null
    }

    /**
     * A PDF's first page, rendered. Michael, 2026-08-08: "PDFs need covers — they're not loading."
     *
     * They were not loading because nothing tried: the extractor knew about zips and a PDF is not
     * one, so every PDF fell through to a drawn card. Which was honest but wrong — a PDF HAS a
     * cover, it is just page one, and a scanned book whose cover is its first page is exactly the
     * case where a title card helps least.
     *
     * PdfRenderer needs a seekable file descriptor, so an entry behind a declared tree has to be
     * materialised first. That is a copy of a possibly-large file for a thumbnail, which is why the
     * result is cached like every other cover and why this runs only when the cover is first asked
     * for rather than on a shelf scan.
     */
    private fun firstPdfPage(context: Context, entry: BookshelfSource.Entry): Bitmap? {
        val file = BookshelfSource.materialise(context, entry) ?: return null
        return runCatching {
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount < 1) return@use null
                    renderer.openPage(0).use { page ->
                        // Rendered at the tile's own aspect, then fitted — asking the renderer for
                        // the shelf size directly would squash a landscape page.
                        val scale = minOf(
                            W.toFloat() / page.width.coerceAtLeast(1),
                            H.toFloat() / page.height.coerceAtLeast(1)
                        )
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        // White first: a PDF page renders with transparency where it has no ink,
                        // and a transparent thumbnail reads as black in most viewers.
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        fit(bmp)
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * First archive member matching [wanted], decoded and fitted.
     *
     * Streamed, and it stops at the first hit: a book is tens of megabytes and the cover is
     * usually in the first few entries, so reading to the end of the archive to be thorough would
     * cost seconds per book for no better answer.
     */
    private fun fromZip(
        context: Context, entry: BookshelfSource.Entry, wanted: (String) -> Boolean,
    ): Bitmap? {
        val src = openStream(context, entry) ?: return null
        return src.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && wanted(e.name)) {
                        val bytes = zip.readBytes()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) return@use fit(bmp)
                    }
                    e = zip.nextEntry
                }
                null
            }
        }
    }

    private fun openStream(context: Context, entry: BookshelfSource.Entry): java.io.InputStream? =
        when {
            entry.file != null -> runCatching { entry.file.inputStream() }.getOrNull()
            entry.uri != null -> runCatching { context.contentResolver.openInputStream(entry.uri) }.getOrNull()
            else -> null
        }

    /** Scale to the shelf tile, letterboxed onto white — covers have every aspect ratio there is. */
    private fun fit(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val r = minOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val w = src.width * r
        val h = src.height * r
        c.drawBitmap(src, null, RectF((W - w) / 2f, (H - h) / 2f, (W + w) / 2f, (H + h) / 2f), null)
        return out
    }

    /** A cover for a book that has none: title and author, set large, on a bordered card. */
    private fun drawn(entry: BookshelfSource.Entry): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        }
        c.drawRect(4f, 4f, W - 4f, H - 4f, border)
        // A spine line, so a drawn cover still reads as a BOOK at a glance rather than as a note.
        c.drawLine(28f, 8f, 28f, H - 8f, border)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 30f
        }
        var y = 90f
        for (line in wrap(entry.title, tp, W - 70f, 7)) {
            c.drawText(line, 46f, y, tp)
            y += 38f
        }
        val kind = entry.extension.uppercase()
        if (kind.isNotBlank()) {
            val kp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY; textSize = 22f
            }
            c.drawText(kind, 46f, H - 34f, kp)
        }
        return out
    }

    private fun wrap(text: String, paint: TextPaint, width: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) <= width) line = StringBuilder(candidate)
            else {
                if (line.isNotEmpty()) out += line.toString()
                line = StringBuilder(w)
                if (out.size == maxLines) return out
            }
        }
        if (out.size < maxLines && line.isNotEmpty()) out += line.toString()
        return out
    }
}
