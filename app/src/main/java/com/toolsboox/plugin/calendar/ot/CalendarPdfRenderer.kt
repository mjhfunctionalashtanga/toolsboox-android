package com.toolsboox.plugin.calendar.ot

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import java.io.File
import java.io.FileOutputStream

/**
 * Renders calendar page strokes to PDF documents using android.graphics.pdf.PdfDocument.
 *
 * Page dimensions default to 1404x1872 to match Boox e-ink portrait mode.
 * All rendering is CPU-bound and must run on Dispatchers.Default, not Main.
 */
object CalendarPdfRenderer {

    /**
     * Everything on one exported page: handwriting plus the typed/pasted text boxes and
     * inserted images. The export path historically shipped strokes only, so pasted text
     * (and images) never reached the OCR/notes pipeline — this carries them through.
     */
    data class PageContent(
        val calendarStrokes: Map<String, List<Stroke>>,
        val noteStrokes: Map<String, List<Stroke>>,
        val textElements: List<TextElement> = emptyList(),
        val imageElements: List<ImageElement> = emptyList()
    )

    /**
     * Render one [LedgerPanel] to a PNG "card": start from the drawn template [template]
     * (lines/headers/typed text/images already on it), composite the page's handwriting
     * [strokeLists] on top, then crop to the panel's rect. The keystone of panel → card →
     * (OCR) → annotation. Coordinates are in the template's own pixel space.
     */
    fun renderCard(template: Bitmap, strokeLists: List<List<Stroke>>, rect: RectF): Bitmap {
        val full = template.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(full)
        val paint = createStrokePaint()
        for (list in strokeLists) for (stroke in list) drawStroke(canvas, paint, stroke)
        val l = rect.left.toInt().coerceIn(0, full.width - 1)
        val t = rect.top.toInt().coerceIn(0, full.height - 1)
        val w = rect.width().toInt().coerceIn(1, full.width - l)
        val h = rect.height().toInt().coerceIn(1, full.height - t)
        return Bitmap.createBitmap(full, l, t, w, h)
    }

    /**
     * Render just [strokes] (no template) cropped to [rect], scaled to [targetWidth] — the ink
     * thumbnail for a structured item's "Ink" face in a list.
     */
    fun renderInk(strokes: List<Stroke>, rect: RectF, targetWidth: Int = 520): Bitmap {
        val rw = rect.width().coerceAtLeast(1f)
        val scale = targetWidth / rw
        val w = targetWidth.coerceIn(1, 2000)
        val h = (rect.height() * scale).toInt().coerceIn(1, 3000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-rect.left, -rect.top)
        val paint = createStrokePaint()
        for (s in strokes) drawStroke(canvas, paint, s)
        canvas.restore()
        return bmp
    }

    /**
     * Render a single calendar page (calendar strokes + note strokes) to a one-page PDF.
     *
     * @param strokes the calendar strokes map (style key -> stroke list)
     * @param noteStrokes the note strokes map (page key -> stroke list)
     * @param outputFile the destination PDF file
     * @param pageWidth the page width in pixels (default 1404)
     * @param pageHeight the page height in pixels (default 1872)
     */
    fun renderPageToPdf(
        strokes: Map<String, List<Stroke>>,
        noteStrokes: Map<String, List<Stroke>>,
        outputFile: File,
        pageWidth: Int = 1404,
        pageHeight: Int = 1872
    ) {
        val pdf = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(Color.WHITE)

            val paint = createStrokePaint()
            drawAllStrokes(canvas, paint, strokes)
            drawAllStrokes(canvas, paint, noteStrokes)

            pdf.finishPage(page)

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /**
     * Render multiple calendar pages into a single multi-page PDF.
     * Each entry in [pages] produces one PDF page, in order.
     *
     * @param pages list of (label, PageContent) pairs
     * @param outputFile the destination PDF file
     * @param pageWidth the page width in pixels (default 1404)
     * @param pageHeight the page height in pixels (default 1872)
     */
    fun renderMonthToPdf(
        pages: List<Pair<String, PageContent>>,
        outputFile: File,
        pageWidth: Int = 1404,
        pageHeight: Int = 1872
    ) {
        if (pages.isEmpty()) return

        val pdf = PdfDocument()
        try {
            val paint = createStrokePaint()

            pages.forEachIndexed { index, (_, content) ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(Color.WHITE)
                // Same z-order as the live canvas: images under, strokes over, text on top.
                drawImageElements(canvas, content.imageElements)
                drawAllStrokes(canvas, paint, content.calendarStrokes)
                drawAllStrokes(canvas, paint, content.noteStrokes)
                drawTextElements(canvas, content.textElements)

                pdf.finishPage(page)
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /**
     * Create a reusable Paint configured for stroke rendering.
     */
    private fun createStrokePaint(): Paint {
        return Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            strokeWidth = 3f
        }
    }

    /**
     * Draw all strokes from a stroke map onto a canvas.
     */
    private fun drawAllStrokes(
        canvas: Canvas,
        paint: Paint,
        strokeMap: Map<String, List<Stroke>>
    ) {
        for ((_, strokeList) in strokeMap) {
            for (stroke in strokeList) {
                drawStroke(canvas, paint, stroke)
            }
        }
    }

    /**
     * Draw a single stroke as a path on the canvas.
     * Pressure (StrokePoint.p) modulates the stroke width for a more natural look.
     */
    private fun drawStroke(canvas: Canvas, basePaint: Paint, stroke: Stroke) {
        val points = stroke.strokePoints
        if (points.size < 2) return

        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }

        val avgPressure = points.map { it.p }.average().toFloat().coerceIn(0.1f, 2.0f)
        val paint = Paint(basePaint).apply {
            color = stroke.color
            strokeWidth = stroke.strokeWidth * avgPressure
        }

        canvas.drawPath(path, paint)
    }

    /**
     * Draw typed/pasted text boxes, word-wrapped to each box's width — matching the live
     * canvas so what OCR sees equals what the user saw. Uses the default sans typeface
     * (no Context here to load the app font; OCR doesn't care about the exact face).
     */
    private fun drawTextElements(canvas: Canvas, textElements: List<TextElement>) {
        for (element in textElements) {
            if (element.text.isEmpty()) continue
            val tp = TextPaint().apply {
                isAntiAlias = true
                color = element.color
                textSize = element.fontSize
                typeface = Typeface.SANS_SERIF
            }
            val width = element.width.coerceAtLeast(80f).toInt()
            val layout = StaticLayout.Builder
                .obtain(element.text, 0, element.text.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(element.x, element.y)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Draw inserted images (inline base64 PNG) into their canvas-space rects. */
    /**
     * Render a whole page — its note strokes, dropped grams (image elements), and text — to a
     * bitmap cropped to the content's bounds. Null when the page is empty. Same z-order as the
     * live canvas (images under, strokes over, text on top). Used to attach a Picking/Note to a
     * reply as one faithful image.
     */
    fun renderPageToBitmap(
        noteStrokes: List<Stroke>,
        imageElements: List<ImageElement> = emptyList(),
        textElements: List<TextElement> = emptyList(),
        targetWidth: Int = 1000
    ): Bitmap? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun grow(x: Float, y: Float) { if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y }
        for (s in noteStrokes) for (p in s.strokePoints) grow(p.x, p.y)
        for (e in imageElements) { grow(e.x, e.y); grow(e.x + e.width, e.y + e.height) }
        for (t in textElements) { grow(t.x, t.y); grow(t.x + 300f, t.y + 40f) }
        if (minX > maxX || minY > maxY) return null   // empty page

        val pad = 30f
        val rect = RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val rw = rect.width().coerceAtLeast(1f)
        val scale = targetWidth / rw
        val w = targetWidth.coerceIn(1, 2000)
        val h = (rect.height() * scale).toInt().coerceIn(1, 3000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-rect.left, -rect.top)
        drawImageElements(canvas, imageElements)
        val paint = createStrokePaint()
        for (s in noteStrokes) drawStroke(canvas, paint, s)
        drawTextElements(canvas, textElements)
        canvas.restore()
        return bmp
    }

    private fun drawImageElements(canvas: Canvas, imageElements: List<ImageElement>) {
        if (imageElements.isEmpty()) return
        val imgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        for (element in imageElements) {
            try {
                val bytes = Base64.decode(element.data, Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                canvas.drawBitmap(
                    bmp, null,
                    RectF(element.x, element.y, element.x + element.width, element.y + element.height),
                    imgPaint
                )
            } catch (e: Exception) {
                // Skip an undecodable image rather than fail the whole page.
            }
        }
    }
}
