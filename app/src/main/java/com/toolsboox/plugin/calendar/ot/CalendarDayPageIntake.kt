package com.toolsboox.plugin.calendar.ot

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.Creator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.michaelfilter.da.IntakePageData

/**
 * The Intake named note page ("intake"), part of the field-ledger swipe cycle.
 *
 * Four quarters — THE READ / THE WATCH / THE LISTEN / EMAIL — each a pure grid of up to 8 grams
 * OF ITS OWN KIND. A gram lands here when you star a feed item (★ → Intake) or file an email; it
 * carries its quarter in [ImageElement.intakeKind] (the Email quarter keeps the legacy "educate"
 * key). The grams ARE the content — no typing. Moving a gram graduates it into a Pickings page
 * (where its object accumulates); a checkmark on its corner leaves a link back to that page.
 * Aesthetic matches Pickings: mono headers, thin rules, plenty of white.
 */
class CalendarDayPageIntake : Creator {

    companion object {

        const val INTAKE_PAGE = "intake"

        private const val left = 40f
        private const val right = 1364f
        private const val gap = 36f
        private const val colMidR = (left + right) / 2f - gap / 2f  // 666
        private const val colMidL = (left + right) / 2f + gap / 2f  // 702

        private const val row1Top = 60f
        private const val row1Bottom = 910f
        private const val row2Top = 1000f
        // No bottom picks strip any more — the four quarters own the whole page.
        private const val row2Bottom = 1830f

        private const val GRAMS_PER_PANEL = 8
        private const val gramCols = 2
        private const val gramRows = 4
        private const val cellGap = 12f
        private const val cellPad = 16f   // inner padding of a panel before its grid

        // High-quality bitmap paint for gram thumbnails — filtered + dithered so a card downscaled
        // into its cell stays smooth and legible, and holds up when the page is zoomed to read it.
        private val gramPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true; isDither = true }

        // The ✓-corner target on each gram: tap it to graduate the gram into its own Pickings page.
        // Once graduated it wears the check and the whole gram becomes a link into that board.
        private const val cornerSize = 34f
        private val cornerFill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        private val cornerBorder = Paint().apply {
            color = Color.argb(200, 0, 0, 0); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true
        }
        private val checkPaint = TextPaint().apply {
            color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = cornerSize * 0.82f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
        }

        /** A quarter of the intake page — one media kind, one grid of grams. */
        data class IntakePanel(val kindKey: String, val title: String, val rect: RectF)

        /**
         * The four quarters: READ (top-left), WATCH (top-right), LISTEN (bottom-left),
         * EMAIL (bottom-right — legacy "educate" storage key).
         */
        val panels = listOf(
            IntakePanel("read", "THE READ", RectF(left, row1Top, colMidR, row1Bottom)),
            IntakePanel("watch", "THE WATCH", RectF(colMidL, row1Top, right, row1Bottom)),
            IntakePanel("listen", "THE LISTEN", RectF(left, row2Top, colMidR, row2Bottom)),
            IntakePanel("educate", "EMAIL", RectF(colMidL, row2Top, right, row2Bottom))
        )

        // ---- gram hit-testing ---------------------------------------------------------------

        /** A drawn gram and where it landed, so a tap can move / checkmark / open it — recorded at
         *  draw time, the PickingsCover/DayEventHits discipline. */
        data class IntakeGram(
            val elementId: String, val page: String, val kindKey: String,
            val sourceLink: String, val rect: RectF, val corner: RectF,
            val graduatedTo: String
        )

        @Volatile
        private var grams: List<IntakeGram> = emptyList()

        /** The gram under a canvas-space point, or null. */
        fun gramAt(x: Float, y: Float): IntakeGram? = grams.firstOrNull { it.rect.contains(x, y) }

        /** The gram whose ✓-corner a canvas-space point falls in, or null (checked before [gramAt]). */
        fun cornerAt(x: Float, y: Float): IntakeGram? = grams.firstOrNull { it.corner.contains(x, y) }

        /** The panel a canvas-space point falls in, or null (used to file a fresh gram by quarter). */
        fun panelAt(x: Float, y: Float): IntakePanel? = panels.firstOrNull { it.rect.contains(x, y) }

        /** How far each overflow card peeks out from under the one on top of it. */
        private const val STACK_PEEK = 26f

        /** The grams of one kind on the intake page, newest first. NOT capped — past
         *  [GRAMS_PER_PANEL] the quarter stacks them (see the draw loop), because dropping a
         *  quarter's ninth gram silently loses something you starred. */
        private fun gramsFor(kindKey: String, calendarDay: CalendarDay?): List<ImageElement> =
            calendarDay?.imageElements
                ?.filter { it.page == INTAKE_PAGE && it.intakeKind == kindKey && !it.decorative && it.data.isNotBlank() }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()

        /**
         * Draw the intake page: four quarters, each a grid of just its own grams.
         *
         * @param canvas the canvas
         * @param intakeData retained for call-site compatibility; unused now that the page is grams
         *        only (typed capture is gone).
         * @param calendarDay the loaded day whose grams fill the quarters (null — the notes-preview
         *        fallback — draws empty quarters and clears the hit-test list).
         */
        @Suppress("UNUSED_PARAMETER")
        fun drawPage(canvas: Canvas, intakeData: IntakePageData, calendarDay: CalendarDay? = null) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)

            val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val headerPaint = TextPaint().apply {
                color = Color.BLACK; textAlign = Paint.Align.LEFT; textSize = 40f; typeface = monoBold; isAntiAlias = true
            }
            val panelBorder = Paint().apply {
                color = Color.argb(170, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val cellBorder = Paint().apply {
                color = Color.argb(150, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val dashed = Paint().apply {
                color = Color.argb(90, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
            }
            val hintPaint = TextPaint().apply {
                color = Color.argb(120, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 24f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC); isAntiAlias = true
            }

            val recorded = mutableListOf<IntakeGram>()
            for (panel in panels) {
                val r = panel.rect

                // Panel title above the box, pickings-style; then the border.
                canvas.drawText(panel.title, r.left, r.top - 14f, headerPaint)
                canvas.drawRect(r, panelBorder)

                val kind = gramsFor(panel.kindKey, calendarDay)
                if (kind.isEmpty()) {
                    val slot = RectF(r.left + cellPad, r.top + cellPad, r.right - cellPad, r.top + cellPad + 150f)
                    canvas.drawRect(slot, dashed)
                    canvas.drawText(emptyHint(panel.kindKey), slot.centerX(), slot.centerY() + 8f, hintPaint)
                    continue
                }

                val gridLeft = r.left + cellPad
                val gridTop = r.top + cellPad
                val gridW = r.width() - 2 * cellPad
                val gridH = r.height() - 2 * cellPad
                val cellW = (gridW - (gramCols - 1) * cellGap) / gramCols
                val cellH = (gridH - (gramRows - 1) * cellGap) / gramRows

                // Past the last cell the extras FAN on it like a hand of cards. They are drawn
                // oldest-first so the newest ends up on top, and the newest takes the deepest
                // offset — so what shows of each card underneath is its TOP edge, the way a fanned
                // hand reads. Clamped inside the panel, so a deep pile leans tighter rather than
                // spilling into the quarter below. Mirrors the iPad's IntakeLayout.gramFrame.
                val overflow = (kind.size - GRAMS_PER_PANEL).coerceAtLeast(0)
                kind.asReversed().forEachIndexed { rev, img ->
                    val i = kind.size - 1 - rev            // back to newest-first index
                    val slot = i.coerceAtMost(GRAMS_PER_PANEL - 1)
                    val col = slot % gramCols
                    val rowi = slot / gramCols
                    val cl = gridLeft + col * (cellW + cellGap)
                    var ct = gridTop + rowi * (cellH + cellGap)
                    if (overflow > 0 && i >= GRAMS_PER_PANEL - 1) {
                        val depth = (kind.size - 1) - i     // 0 for the oldest of the pile
                        val room = (r.bottom - cellPad - (ct + cellH)).coerceAtLeast(0f)
                        val step = if (overflow > 0) minOf(STACK_PEEK, room / overflow) else 0f
                        ct += step * (overflow - depth)
                    }
                    val cell = RectF(cl, ct, cl + cellW, ct + cellH)
                    drawGramInCell(canvas, img, cell)
                    canvas.drawRect(cell, cellBorder)

                    // ✓-corner target (top-right): empty box until graduated, checked after.
                    val corner = RectF(
                        cell.right - cornerSize - 8f, cell.top + 8f,
                        cell.right - 8f, cell.top + 8f + cornerSize
                    )
                    drawCorner(canvas, corner, img.graduatedTo.isNotBlank())

                    recorded.add(
                        IntakeGram(
                            img.elementId.toString().lowercase(), img.page, panel.kindKey,
                            img.sourceLink, cell, corner, img.graduatedTo
                        )
                    )
                }
            }
            grams = recorded
        }

        /** The corner target: a small white box, checked with ✓ once the gram has graduated. */
        private fun drawCorner(canvas: Canvas, box: RectF, checked: Boolean) {
            canvas.drawRoundRect(box, 6f, 6f, cornerFill)
            canvas.drawRoundRect(box, 6f, 6f, cornerBorder)
            if (checked) canvas.drawText("✓", box.centerX(), box.centerY() + box.height() * 0.30f, checkPaint)
        }

        private fun emptyHint(kindKey: String): String = when (kindKey) {
            "read" -> "star Reads to fill this"
            "watch" -> "star Watches to fill this"
            "listen" -> "star Listens to fill this"
            else -> "filed emails land here"
        }

        /**
         * Decode SMALL and center-crop the gram into its cell — bounds first, then an inSampleSize
         * near cell size, so eight full-page base64 decodes per panel don't drag the page turn. One
         * decode per cell, recycled as soon as it's on the canvas (the PickingsCover rule).
         */
        private fun drawGramInCell(canvas: Canvas, img: ImageElement, cell: RectF) {
            runCatching {
                val bytes = Base64.decode(img.data, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                // Decode with 2× headroom over the cell so zooming the page still reads crisply —
                // the cards are intentionally small at 1× (eight to a quarter) and meant to be zoomed.
                val target = maxOf(cell.width(), cell.height()) * 2f
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching
                // Center-crop to the cell's aspect so a card reads as a card, not a sliver.
                val cellAspect = cell.width() / cell.height()
                val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
                val src = if (bmpAspect > cellAspect) {
                    val w = (bmp.height * cellAspect).toInt().coerceIn(1, bmp.width)
                    val x0 = (bmp.width - w) / 2
                    Rect(x0, 0, x0 + w, bmp.height)
                } else {
                    val h = (bmp.width / cellAspect).toInt().coerceIn(1, bmp.height)
                    val y0 = (bmp.height - h) / 2
                    Rect(0, y0, bmp.width, y0 + h)
                }
                canvas.drawBitmap(bmp, src, cell, gramPaint)
                bmp.recycle()
            }
        }
    }
}
