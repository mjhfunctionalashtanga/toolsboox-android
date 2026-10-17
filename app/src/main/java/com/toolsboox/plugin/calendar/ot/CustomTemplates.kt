package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import timber.log.Timber
import java.io.File

/**
 * TEMPLATES A PERSON BRINGS — imported stationery, kept for later, worn per page.
 *
 * Michael, 2026-08-24: "it would be wise to sort how users import templates and store them for
 * later etc." The built-ins ([NotePageTemplates]) are drawn code; a custom template is a PICTURE
 * — a page of lined stationery, a planner sheet, letterhead — imported once and offered in the
 * same ▤ picker forever after.
 *
 * ── THE SHAPE ─────────────────────────────────────────────────────────────────────────────────
 *
 *  • An import is COPIED (re-encoded to PNG, sampled to the page's 1404×1872 world) into
 *    `filesDir/page-templates/custom/<slug>.png`. The original file is never referenced again —
 *    a template must not die when a Download is cleaned up.
 *  • The DIRECTORY is the registry: id = "custom:<slug>", name = the slug made readable. No
 *    index file to corrupt or merge.
 *  • The id rides the SAME per-page sidecar as the built-ins ([NotePageTemplates.set]) — so on
 *    an older build, or a device that lacks the bytes, [NotePageTemplates.resolve]'s
 *    unknown-id fall-through shows the family default: never a wrong page, only yesterday's
 *    look. (Template BYTES are local-per-device for now; the ids sync, the pictures don't —
 *    the hub route for bytes is the books pattern, queued.)
 *  • A custom sheet OWNS its page like Gratitude does — no NOTES chrome over someone's
 *    stationery. Ink rides its own layer above, untouched by any of this.
 */
object CustomTemplates {

    const val PREFIX = "custom:"
    private const val DIR = "page-templates/custom"

    /** Page world — the note canvas the sheet is drawn onto. */
    private const val PAGE_W = 1404
    private const val PAGE_H = 1872

    data class Entry(val id: String, val name: String, val file: File)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    /** Every imported template, alphabetical by name. */
    fun list(context: Context): List<Entry> =
        dir(context).listFiles { f -> f.isFile && f.extension.lowercase() == "png" }
            .orEmpty()
            .map { f ->
                val slug = f.nameWithoutExtension
                Entry(PREFIX + slug, slug.replace('-', ' ').replaceFirstChar { it.uppercase() }, f)
            }
            .sortedBy { it.name.lowercase() }

    /**
     * Import a picked image as a template named [name]. Decoded sampled (a photo of stationery
     * can be 12 MP; the page world never needs more than 1404×1872), drawn onto a white page —
     * transparency becomes paper, not black — and kept as PNG.
     */
    fun import(context: Context, uri: Uri, name: String): Entry? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PAGE_W && bounds.outHeight / (sample * 2) >= PAGE_H) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val page = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            val scale = minOf(PAGE_W.toFloat() / src.width, PAGE_H.toFloat() / src.height)
            val w = src.width * scale
            val h = src.height * scale
            val dst = RectF((PAGE_W - w) / 2f, (PAGE_H - h) / 2f, (PAGE_W + w) / 2f, (PAGE_H + h) / 2f)
            drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        val slug = name.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
            .ifBlank { "template" }
        // Never overwrite a namesake silently — "-2" the way a filesystem would.
        var file = File(dir(context), "$slug.png")
        var n = 2
        while (file.exists()) { file = File(dir(context), "$slug-$n.png"); n++ }
        file.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        page.recycle()
        cached = null   // the cache may hold a stale namesake
        Entry(PREFIX + file.nameWithoutExtension, name, file)
    }.onFailure { Timber.w(it, "template import failed") }.getOrNull()

    fun delete(context: Context, id: String) {
        file(context, id)?.delete()
        if (cached?.first == id) cached = null
    }

    private fun file(context: Context, id: String): File? {
        if (!id.startsWith(PREFIX)) return null
        val slug = id.removePrefix(PREFIX)
        if (slug.contains('/') || slug.contains("..")) return null
        return File(dir(context), "$slug.png").takeIf { it.exists() }
    }

    /** One-deep cache: a redraw storm re-reads the same sheet; two pages rarely interleave. */
    @Volatile
    private var cached: Pair<String, Bitmap>? = null

    /**
     * Draw [id]'s sheet onto the page canvas. FALSE when the bytes aren't on this device —
     * the caller falls through to the family default, never a blank page.
     */
    fun draw(context: Context, canvas: Canvas, id: String): Boolean {
        val bmp = cached?.takeIf { it.first == id }?.second ?: run {
            val f = file(context, id) ?: return false
            val b = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return false
            cached = id to b
            b
        }
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bmp, null, Rect(0, 0, PAGE_W, PAGE_H), Paint(Paint.FILTER_BITMAP_FLAG))
        return true
    }
}
