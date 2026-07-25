package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import com.toolsboox.da.ImageElement
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * "Export asset" — a gram rendered as a clean, high-resolution PNG for use OUTSIDE the app:
 * Canva, Descript, a WordPress post, anywhere a media asset goes.
 *
 * The stored bitmap is decoded at its FULL stored size (never the on-screen scale — a gram shrunk
 * to a thumbnail on the page still exports at the resolution it was captured at). On Android every
 * edge treatment (polaroid/tape/torn edge) is already baked into the element's pixels
 * (see [ImageElement.edgeBaked]), so the stored bytes ARE what the page shows; rotation is the one
 * render-time transform, and it is applied here so the export looks exactly like the page. A ~24px
 * transparent margin frames the asset so it drops into a layout without touching its neighbours.
 *
 * Mirrors iOS `AssetExport.swift` (which additionally bakes the render-time GramEdge).
 */
object AssetExport {

    /** Transparent breathing room around the exported pixels. */
    const val MARGIN = 24

    /**
     * Render [element] as an export-ready ARGB bitmap: full stored resolution, rotation applied,
     * [MARGIN] transparent pixels on every side. Null when the stored bytes don't decode.
     */
    fun render(element: ImageElement): Bitmap? {
        val src = try {
            val bytes = Base64.decode(element.data, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Timber.w(e, "asset export: stored bytes did not decode"); null
        } ?: return null
        return compose(listOf(src to element.rotation))
    }

    /**
     * Compose one or more (bitmap, rotation°) pairs onto a single transparent canvas, each at its
     * full stored size, side by side in the order given. The single-gram path is just a one-item
     * call. Rotation grows the slot to the rotated bounding box so nothing clips.
     */
    private fun compose(parts: List<Pair<Bitmap, Float>>): Bitmap? {
        if (parts.isEmpty()) return null
        // Rotated bounding box per part.
        val boxes = parts.map { (bmp, deg) ->
            val rad = Math.toRadians(deg.toDouble())
            val c = Math.abs(Math.cos(rad)); val s = Math.abs(Math.sin(rad))
            val w = (bmp.width * c + bmp.height * s).toInt().coerceAtLeast(1)
            val h = (bmp.width * s + bmp.height * c).toInt().coerceAtLeast(1)
            w to h
        }
        val totalW = boxes.sumOf { it.first } + MARGIN * (parts.size + 1)
        val totalH = (boxes.maxOf { it.second }) + MARGIN * 2
        val out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)   // starts fully transparent
        var x = MARGIN
        for (i in parts.indices) {
            val (bmp, deg) = parts[i]
            val (bw, bh) = boxes[i]
            val cx = x + bw / 2f
            val cy = MARGIN + boxes.maxOf { it.second } / 2f
            canvas.save()
            if (deg != 0f) canvas.rotate(deg, cx, cy)
            canvas.drawBitmap(bmp, cx - bmp.width / 2f, cy - bmp.height / 2f, null)
            canvas.restore()
            x += bw + MARGIN
        }
        return out
    }

    /**
     * "gram-<yyyymmdd>-<short-title-slug>.png". Title falls through the element's own words:
     * media title → source label → the card's text → "asset".
     */
    fun filename(element: ImageElement): String {
        val title = element.mediaTitle.ifBlank { element.sourceLabel }
            .ifBlank { element.cardText }.ifBlank { "asset" }
        return filename(title)
    }

    fun filename(title: String): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val slug = title.lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifBlank { "asset" }
        return "gram-$date-$slug.png"
    }

    /**
     * Write [bitmap] as a PNG into the FileProvider-visible exports cache
     * (`cache/exports/`, mapped in file_paths.xml) and return the file. Null on any I/O failure.
     */
    fun writeTemp(context: Context, bitmap: Bitmap, filename: String): File? = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    } catch (e: Exception) {
        Timber.w(e, "asset export: temp write failed"); null
    }

    /** The PNG bytes of [bitmap] — for the media-library upload path. */
    fun pngBytes(bitmap: Bitmap): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }
}
