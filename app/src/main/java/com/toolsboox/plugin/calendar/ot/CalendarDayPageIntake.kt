package com.toolsboox.plugin.calendar.ot

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.toolsboox.ot.Creator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.michaelfilter.da.IntakePageData

/**
 * The MichaelFilter Intake named note page ("intake"), part of the field-ledger
 * swipe cycle right after the gratitude page.
 *
 * Four panels — THE READ / THE WATCH / THE LISTEN / EDUCATE ME — each with a
 * ruled handwriting zone (ink saves under noteStrokes["intake"] like any named
 * page) and a dashed tap-to-type strip at the bottom for cut-and-paste. Typed
 * text renders in place and is dispatched to the mjh.yoga intake endpoint via
 * IntakePageStore. Aesthetic matches the Pickings page (mono headers, thin
 * rules, dashed boxes, plenty of white).
 *
 * Below the panels, "today's picks": one quiet row of small thumbnails — what
 * got gram'd and pick'd today, across every page of the day — so the later
 * list shows the day's gathering at a glance. Each thumbnail is a door to the
 * page that holds it.
 */
class CalendarDayPageIntake : Creator {

    companion object {

        private const val left = 40f
        private const val right = 1364f
        private const val gap = 36f
        private const val colMidR = (left + right) / 2f - gap / 2f  // 666
        private const val colMidL = (left + right) / 2f + gap / 2f  // 702
        private const val lineSpacing = 60f

        private const val row1Top = 60f
        private const val row1Bottom = 900f
        private const val row2Top = 985f
        // Was 1825; the bottom band now belongs to the "today's picks" strip. The panels'
        // typed strips and OCR zones all derive from [panels], so they move in lockstep —
        // there is no second copy of this layout for a hit-test to disagree with.
        private const val row2Bottom = 1680f

        // Height of the tap-to-type strip at the bottom of each panel.
        private const val typedStripHeight = 190f

        /**
         * A panel of the intake page.
         */
        data class IntakePanel(val kindKey: String, val title: String, val rect: RectF) {
            val typedRect: RectF
                get() = RectF(rect.left, rect.bottom - typedStripHeight, rect.right, rect.bottom)
        }

        /**
         * The four panels: READ (top-left), WATCH (top-right),
         * LISTEN (bottom-left), EDUCATE ME (bottom-right).
         */
        val panels = listOf(
            IntakePanel("read", "THE READ", RectF(left, row1Top, colMidR, row1Bottom)),
            IntakePanel("watch", "THE WATCH", RectF(colMidL, row1Top, right, row1Bottom)),
            IntakePanel("listen", "THE LISTEN", RectF(left, row2Top, colMidR, row2Bottom)),
            IntakePanel("educate", "EDUCATE ME", RectF(colMidL, row2Top, right, row2Bottom))
        )

        /**
         * Hit-test a canvas-space point against the tap-to-type strips.
         *
         * @param x canvas x
         * @param y canvas y
         * @return the panel kind key, or null when the point is not on a strip
         */
        fun typedZoneAt(x: Float, y: Float): String? {
            val pad = 20f
            return panels.firstOrNull {
                val r = it.typedRect
                x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
            }?.kindKey
        }

        // ---- today's picks strip -----------------------------------------------------------

        /** The strip's geometry: one row of small squares under the LISTEN/EDUCATE panels. */
        private const val picksTitleY = 1728f
        private const val picksThumbTop = 1742f
        private const val picksThumbSize = 120f
        private const val picksThumbGap = 16f
        private const val MAX_PICKS = 8

        /** A drawn thumbnail and where it landed, so a tap can open the page that holds the
         *  gram — recorded at draw time, the PickingsCover/DayEventHits discipline. */
        data class Pick(val pageKey: String, val rect: RectF)

        @Volatile
        private var picks: List<Pick> = emptyList()

        /** The pick under a canvas-space point, or null. */
        fun pickAt(x: Float, y: Float): Pick? = picks.firstOrNull { it.rect.contains(x, y) }

        /**
         * Draw the strip: newest grams first, each decoded SMALL — bounds first, then an
         * inSampleSize that lands near thumbnail size — because a day's grams are full-page
         * base64 PNGs and eight full decodes on the render path would make the page turn drag.
         * One decode per tile, recycled as soon as it's on the canvas (the PickingsCover rule).
         */
        private fun drawPicksStrip(canvas: Canvas, calendarDay: CalendarDay?) {
            if (calendarDay == null) {
                picks = emptyList()
                return
            }
            val titlePaint = TextPaint().apply {
                color = Color.argb(150, 0, 0, 0); textAlign = Paint.Align.LEFT; textSize = 24f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
            }
            canvas.drawText("today's picks", left, picksTitleY, titlePaint)

            // Every page of the day feeds the strip — day page grams, notes "0", pickings
            // boards — newest first. Stickers stay out: decoration is not a picking.
            val grams = calendarDay.imageElements
                .filter { !it.decorative && it.data.isNotBlank() }
                .sortedByDescending { it.timestamp }
                .take(MAX_PICKS)

            if (grams.isEmpty()) {
                picks = emptyList()
                val dashed = Paint().apply {
                    color = Color.argb(90, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
                }
                val slot = RectF(left, picksThumbTop, left + 3 * (picksThumbSize + picksThumbGap), picksThumbTop + picksThumbSize)
                canvas.drawRect(slot, dashed)
                val hint = TextPaint().apply {
                    color = Color.argb(120, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 22f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC); isAntiAlias = true
                }
                canvas.drawText("grams you pick today land here", slot.centerX(), slot.centerY() + 8f, hint)
                return
            }

            val border = Paint().apply {
                color = Color.argb(170, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val recorded = mutableListOf<Pick>()
            grams.forEachIndexed { i, img ->
                val l = left + i * (picksThumbSize + picksThumbGap)
                val rect = RectF(l, picksThumbTop, l + picksThumbSize, picksThumbTop + picksThumbSize)
                runCatching {
                    val bytes = Base64.decode(img.data, Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= picksThumbSize &&
                        bounds.outHeight / (sample * 2) >= picksThumbSize
                    ) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching
                    // Center-crop into the square, so a wide card reads as a card, not a sliver.
                    val side = minOf(bmp.width, bmp.height)
                    val src = android.graphics.Rect(
                        (bmp.width - side) / 2, (bmp.height - side) / 2,
                        (bmp.width - side) / 2 + side, (bmp.height - side) / 2 + side
                    )
                    canvas.drawBitmap(bmp, src, rect, null)
                    bmp.recycle()
                }
                canvas.drawRect(rect, border)
                recorded.add(Pick(img.page, rect))
            }
            picks = recorded
        }

        /**
         * Draw the intake page template with the typed panel texts in place.
         *
         * @param canvas the canvas
         * @param intakeData the typed content of the day
         * @param calendarDay the loaded day, feeding the "today's picks" strip (null — the
         *        notes-preview fallback — draws the panels only and clears the strip's taps)
         */
        fun drawPage(canvas: Canvas, intakeData: IntakePageData, calendarDay: CalendarDay? = null) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)

            val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val monoPlain = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

            val headerPaint = TextPaint().apply {
                color = Color.BLACK; textAlign = Paint.Align.LEFT; textSize = 40f; typeface = monoBold; isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = Color.argb(140, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val panelBorder = Paint().apply {
                color = Color.argb(170, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val dashedBorder = Paint().apply {
                color = Color.argb(100, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
            }
            val hintPaint = TextPaint().apply {
                color = Color.argb(110, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 26f
                typeface = monoPlain; isAntiAlias = true
            }
            val typedPaint = TextPaint().apply {
                color = Color.BLACK; textSize = 26f; typeface = monoPlain; isAntiAlias = true
            }

            for (panel in panels) {
                val r = panel.rect
                val t = panel.typedRect

                // Panel title above the box, pickings-style.
                canvas.drawText(panel.title, r.left, r.top - 14f, headerPaint)

                // Panel border + ruled handwriting zone above the typed strip.
                canvas.drawRect(r, panelBorder)
                var y = r.top + lineSpacing
                while (y < t.top - 12f) {
                    canvas.drawLine(r.left + 14f, y, r.right - 14f, y, linePaint)
                    y += lineSpacing
                }

                // Tap-to-type strip (dashed, like the pickings image boxes).
                canvas.drawRect(t, dashedBorder)

                val typed = intakeData.typedFor(panel.kindKey).trim()
                if (typed.isEmpty()) {
                    canvas.drawText(
                        "TAP TO TYPE / PASTE",
                        (t.left + t.right) / 2f, (t.top + t.bottom) / 2f + 9f, hintPaint
                    )
                } else {
                    // Render the typed text in place, wrapped and clipped to the strip.
                    val pad = 14f
                    val width = (t.width() - 2 * pad).toInt().coerceAtLeast(50)
                    val layout = StaticLayout.Builder
                        .obtain(typed, 0, typed.length, typedPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                    canvas.save()
                    canvas.clipRect(t.left + pad, t.top + pad, t.right - pad, t.bottom - pad)
                    canvas.translate(t.left + pad, t.top + pad)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }

            drawPicksStrip(canvas, calendarDay)
        }
    }
}
