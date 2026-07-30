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

        // STARS IS A REGISTER, NOT A SORTING BOARD.
        //
        // This was a 2x2 of quarters you filed things into — "Star Sort". Michael: "Instead of being
        // an inbox, it's a list of everything that has gotten a star that day, not an inbox, a
        // reference." A list is what it now looks like: five full-width bands stacked down the page,
        // one per kind, each a single row of cards. Quarters read as four boxes to sort between;
        // bands read as a register you scan. The working surface where grams are actually written on
        // and rearranged is Gram Picks — see [CalendarDayPageNotes.GRAM_PICKS].
        private const val bandHeight = 300f
        private const val bandTitleSpace = 30f
        private const val bandGap = 12f
        // Room above the first band for the page title ("ALL STARS \u00B7 <window>"). Both are 40px
        // mono bold, so they need a clear gap or they read as one mashed line — at 130 the title sat
        // 30px off the first band's own title and the two ran together.
        private const val bandFirstTop = 156f
        private const val pageTitleBaseline = 72f
        private fun bandTop(i: Int) = bandFirstTop + i * (bandHeight + bandTitleSpace + bandGap)
        private fun band(i: Int) = RectF(left, bandTop(i), right, bandTop(i) + bandHeight)

        // Per-band grid: one row of six across the full width. The default stays 2x4 so any panel
        // declared without a shape keeps the old quarter behaviour.
        private const val gramCols = 2
        private const val gramRows = 4
        private const val bandCols = 6
        private const val bandRows = 1
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

        /** A band of the Stars page — one kind, one grid of grams. [cols]/[rows] let a wide band be
         *  a single row while the legacy quarter shape stays 2x4. */
        data class IntakePanel(
            val kindKey: String, val title: String, val rect: RectF,
            val cols: Int = gramCols, val rows: Int = gramRows
        )

        /**
         * The five kinds of star, in reading order down the page. BOOKS is the one Michael named as
         * missing ("the only Star type missing is books on the page"); EMAIL keeps its legacy
         * "educate" storage key so every gram already filed there still lands in its band.
         */
        val panels = listOf(
            IntakePanel("read", "THE READ", band(0), bandCols, bandRows),
            IntakePanel("watch", "THE WATCH", band(1), bandCols, bandRows),
            IntakePanel("listen", "THE LISTEN", band(2), bandCols, bandRows),
            IntakePanel("books", "THE BOOKS", band(3), bandCols, bandRows),
            IntakePanel("educate", "EMAIL", band(4), bandCols, bandRows)
        )

        /**
         * Where a NEW star of [kindKey] should land: the next free slot inside that kind's band.
         *
         * Stars arrive organised — you never file one — but they are ordinary elements from the
         * moment they land, so this only chooses a starting point. Drag one into another band and it
         * stays there; nothing re-flows it, because a register that rearranges what you moved is not
         * yours. [taken] is how many the band already holds, so arrivals stack across, then down,
         * then simply overlap once the band is full (visible and draggable, never dropped).
         *
         * Returns null for an unknown kind, so a caller with no band falls back to plain placement.
         */
        fun bandSlotFor(kindKey: String, taken: Int, cardW: Float, cardH: Float): Pair<Float, Float>? {
            val panel = panels.firstOrNull { it.kindKey == kindKey } ?: return null
            val r = panel.rect
            val cols = panel.cols
            val cellW = (r.width() - 2 * cellPad - (cols - 1) * cellGap) / cols
            val col = taken % cols
            val row = taken / cols
            val x = r.left + cellPad + col * (cellW + cellGap)
            // Rows step by the card's own height once past the first, and everything is clamped
            // inside the band so an arrival can't be dropped off the page.
            val y = r.top + cellPad + row * (cardH * 0.18f)
            return x.coerceIn(0f, (1404f - cardW).coerceAtLeast(0f)) to
                y.coerceIn(0f, (1872f - cardH).coerceAtLeast(0f))
        }

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

        /** The grams of one kind on the Stars page, newest first. NOT capped — past a band's own
         *  cols x rows the band stacks them (see the draw loop), because dropping the overflow would
         *  silently lose something you starred, and a register that omits entries is not a register. */
        private fun gramsFor(
            kindKey: String, calendarDay: CalendarDay?, scoped: List<ImageElement>? = null
        ): List<ImageElement> =
            (scoped ?: calendarDay?.imageElements)
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
        /**
         * Draw the All Stars register.
         *
         * @param scopedGrams when non-null, the grams to show INSTEAD of [calendarDay]'s own — the
         *   almanac header's period filter collects these across a week / month / quarter / year, so
         *   the page can answer "everything I starred this month" and not only today. Null keeps the
         *   single-day reading, which costs one already-loaded file.
         * @param scopeLabel what the header says after "ALL STARS ·", e.g. "TODAY" or "WEEK 30".
         */
        fun drawPage(
            canvas: Canvas, intakeData: IntakePageData, calendarDay: CalendarDay? = null,
            scopedGrams: List<ImageElement>? = null, scopeLabel: String = "TODAY"
        ) {
            // Today's own stars are live elements, so they must not ALSO be printed as part of the
            // wider-window record — identity by timestamp, which is what the day JSON carries.
            val todayStamps = calendarDay?.imageElements
                ?.filter { it.page == INTAKE_PAGE }?.map { it.timestamp }?.toHashSet() ?: HashSet()
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

            // The register names its own window. Without this the page looks identical whether it
            // holds today's stars or the whole month's, which is the one thing a filter must never
            // leave ambiguous.
            canvas.drawText("ALL STARS \u00B7 $scopeLabel", left, pageTitleBaseline, headerPaint)

            // TODAY'S STARS ARE NOT PAINTED HERE — they are real ImageElements and the element
            // layer draws them, exactly as it does on a Pickings board.
            //
            // They used to be BOTH. CalendarDayFragment hands every element on this page to
            // setImageElements (imgPageKey = "intake"), so each gram was already live and movable at
            // its own x/y — and this loop painted a second copy of it into a band cell. Two copies,
            // in different places. The painted one is the one the eye goes to and it cannot move,
            // because it is part of the template picture: that, and not a missing feature, is what
            // "Star Sort's grams are not selectable/movable" was. Michael: "all stars should be
            // manipulatable just like pickings." They now are, because the photograph of them is
            // gone and only the thing itself remains.
            //
            // The bands stay as printed guides, and [bandFor] places each new arrival inside the one
            // for its kind — so stars still land organised, then behave like any other object.
            //
            // The ONE thing still painted is a WIDER WINDOW: when the almanac filter reaches past
            // today, other days' stars are drawn here as a printed record. They cannot be live
            // elements of this page — they belong to their own days — so a printed record behind
            // today's live objects is the honest rendering, and it needs no mode: your own stars are
            // always the real ones.
            val recorded = mutableListOf<IntakeGram>()
            if (scopedGrams != null) {
                for (panel in panels) {
                    val r = panel.rect
                    val past = gramsFor(panel.kindKey, null, scopedGrams)
                        .filter { it.timestamp !in todayStamps }
                    if (past.isEmpty()) continue
                    val gridLeft = r.left + cellPad
                    val gridTop = r.top + cellPad
                    val gridW = r.width() - 2 * cellPad
                    val gridH = r.height() - 2 * cellPad
                    val cols = panel.cols
                    val perPanel = panel.cols * panel.rows
                    val cellW = (gridW - (cols - 1) * cellGap) / cols
                    val cellH = (gridH - (panel.rows - 1) * cellGap) / panel.rows
                    val overflow = (past.size - perPanel).coerceAtLeast(0)
                    past.asReversed().forEachIndexed { rev, img ->
                        val i = past.size - 1 - rev
                        val slotIdx = i.coerceAtMost(perPanel - 1)
                        val cl = gridLeft + (slotIdx % cols) * (cellW + cellGap)
                        var ct = gridTop + (slotIdx / cols) * (cellH + cellGap)
                        if (overflow > 0 && i >= perPanel - 1) {
                            val depth = (past.size - 1) - i
                            val room = (r.bottom - cellPad - (ct + cellH)).coerceAtLeast(0f)
                            val step = minOf(STACK_PEEK, room / overflow)
                            ct += step * (overflow - depth)
                        }
                        val cell = RectF(cl, ct, cl + cellW, ct + cellH)
                        drawGramInCell(canvas, img, cell)
                        canvas.drawRect(cell, cellBorder)
                    }
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
            "books" -> "star Books to fill this"
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
