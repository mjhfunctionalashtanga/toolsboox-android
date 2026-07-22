package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.toolsboox.ot.Creator
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ui.CalendarDayFragment
import java.time.LocalDate

/**
 * Create daily template of calendar plugin notes.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayPageNotes : Creator {

    companion object {

        // Cell width
        private const val cew = 1300.0f

        // Cell height
        private const val ceh = 50.0f

        // Left offset
        private const val lo = (1404.0f - 1 * cew) / 2.0f

        // Top offset
        private const val to = (1872.0f - 35 * ceh) / 2.0f

        /**
         * Process touch event on the calendar page and navigate to the view of calendar.
         *
         * @param view the surface view
         * @param motionEvent the motion event
         * @param gestureResult the gesture result
         * @param fragment the parent fragment
         * @param calendarDay the calendar data class
         * @param notePage current notePage
         * @return true
         */
        fun onTouchEvent(
            view: View, motionEvent: MotionEvent, gestureResult: Int,
            fragment: CalendarDayFragment, calendarDay: CalendarDay, notePage: String
        ): Boolean {
            if (motionEvent.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return true

            val year = calendarDay.year
            val month = calendarDay.month
            val day = calendarDay.day
            val locale = calendarDay.locale

            val localDate = LocalDate.of(year, month, day)

            // Any pickings board (default or a named "pickings-…") navigates like the classic one.
            val np = if (PickingsStore.isPickings(notePage)) "pickings" else notePage

            when (gestureResult) {
                OnGestureListener.UTD -> {
                    when (np) {
                        "pickings" -> CalendarNavigator.toDayPage(fragment, localDate)
                        "gratitude" -> CalendarNavigator.toDayNote(fragment, localDate, "pickings")
                        "intake" -> CalendarNavigator.toDayNote(fragment, localDate, "gratitude")
                        else -> {
                            val page = notePage.toIntOrNull() ?: 0
                            if (page == 0) {
                                CalendarNavigator.toDayNote(fragment, localDate, "intake")
                            } else {
                                CalendarNavigator.toDayNote(fragment, localDate, "${page - 1}")
                            }
                        }
                    }
                    return true
                }

                OnGestureListener.DTU -> {
                    when (np) {
                        "pickings" -> CalendarNavigator.toDayNote(fragment, localDate, "gratitude")
                        "gratitude" -> CalendarNavigator.toDayNote(fragment, localDate, "intake")
                        "intake" -> CalendarNavigator.toDayNote(fragment, localDate, "0")
                        else -> {
                            val page = notePage.toIntOrNull() ?: 0
                            CalendarNavigator.toDayNote(fragment, localDate, "${page + 1}")
                        }
                    }
                    return true
                }
            }

            return true
        }

        /**
         * Draw the daily template of calendar plugin notes.
         *
         * @param context the context
         * @param canvas the canvas
         * @param calendarDay data class
         * @param template the template code
         * @param notePage current notePage
         */
        fun drawPage(context: Context, canvas: Canvas, calendarDay: CalendarDay, template: Int, notePage: String) {
            if (notePage == "gratitude") {
                drawGratitudePage(canvas)
                return
            }
            if (PickingsStore.isPickings(notePage)) {
                drawPickingsPage(canvas)
                return
            }
            if (notePage == "intake") {
                // Fallback template only — CalendarDayFragment.renderPage draws the
                // intake page directly with the day's typed panel data.
                CalendarDayPageIntake.drawPage(canvas, com.toolsboox.plugin.michaelfilter.da.IntakePageData())
                return
            }
            if (notePage == "synthesize" || notePage == "brainstorm") {
                drawBrainstormPage(canvas)
                return
            }
            if (notePage == "grid") {
                drawGridNotesPage(canvas)
                return
            }

            val page = notePage.toIntOrNull() ?: 0

            canvas.drawRect(0.0f, 0.0f, 1404.0f, 1872.0f, Creator.fillWhite)

            // Title in the top margin so this freeform surface reads as "NOTES" — distinct from the
            // "WRITE" page (post-Synthesize), which shares this same ruled template.
            canvas.drawText(
                (if (notePage == "write") "WRITE" else "NOTES") + "  ·  Page ${page + 1}",
                lo, to - 16.0f, Creator.textDefaultBlack)

            canvas.drawText("${page + 1}", lo + cew - 10.0f, to + 3 * ceh - 10.0f, Creator.textBigGray20Right)

            if (template == 0) {
                // Roomier rule than the 50px day-grid: real handwriting needs ~65px lines
                // (the old spacing forced two rows per written line). Same frame, fewer rows.
                val rows = 27
                val rh = (35 * ceh) / rows
                canvas.drawLine(lo, to + 0 * ceh, lo + cew, to + 0 * ceh, Creator.lineDefaultBlack)
                for (i in 1 until rows) {
                    canvas.drawLine(lo, to + i * rh, lo + cew, to + i * rh, Creator.lineDefaultGrey50)
                }
                canvas.drawLine(lo, to + 35 * ceh, lo + cew, to + 35 * ceh, Creator.lineDefaultBlack)
            } else if (template == 1) {
                canvas.drawLine(lo, to + 0 * ceh, lo + cew, to + 0 * ceh, Creator.lineDefaultBlack)
                for (i in 1..34) {
                    canvas.drawLine(lo, to + i * ceh, lo + cew, to + i * ceh, Creator.lineDefaultGrey50)
                }
                canvas.drawLine(lo, to + 35 * ceh, lo + cew, to + 35 * ceh, Creator.lineDefaultBlack)

                canvas.drawLine(lo, to + 0 * ceh, lo, to + 35 * ceh, Creator.lineDefaultBlack)
                for (i in 1..25) {
                    canvas.drawLine(lo + i * 50.0f, to + 0 * ceh, lo + i * 50.0f, to + 35 * ceh, Creator.lineDefaultGrey50)
                }
                canvas.drawLine(lo + 26 * 50.0f, to + 0 * ceh, lo + 26 * 50.0f, to + 35 * ceh, Creator.lineDefaultBlack)
            }
        }

        /**
         * Grid Notes: the Notes surface with a square grid instead of rules — the same freeform
         * ink-and-image canvas, but graph paper. It suits laying out shapes and connectors, boxes
         * and arrows, where lined paper fights you. Always a grid, whatever the notes template is
         * set to, since choosing "Grid Notes" already said what you wanted.
         */
        private fun drawGridNotesPage(canvas: Canvas) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)
            canvas.drawText("GRID NOTES", lo, to - 16.0f, Creator.textDefaultBlack)

            val step = 50.0f
            val bottom = to + 35 * ceh
            // Grid inside the same frame the ruled notes use, so it aligns with the margins.
            var x = lo
            while (x <= lo + cew + 0.5f) {
                val edge = x <= lo + 0.5f || x >= lo + cew - 0.5f
                canvas.drawLine(x, to, x, bottom, if (edge) Creator.lineDefaultBlack else Creator.lineDefaultGrey50)
                x += step
            }
            var y = to
            while (y <= bottom + 0.5f) {
                val edge = y <= to + 0.5f || y >= bottom - 0.5f
                canvas.drawLine(lo, y, lo + cew, y, if (edge) Creator.lineDefaultBlack else Creator.lineDefaultGrey50)
                y += step
            }
        }

        /**
         * Brainstorm / whiteboard page: a full-page light dot grid to arrange gram cards (dropped
         * as images via "Here") and write freely around them.
         */
        private fun drawBrainstormPage(canvas: Canvas) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)
            val dot = Paint().apply {
                color = Color.argb(90, 0, 0, 0); style = Paint.Style.FILL; isAntiAlias = true
            }
            val step = 48f
            val margin = 24f
            var y = margin
            while (y <= 1872f - margin) {
                var x = margin
                while (x <= 1404f - margin) {
                    canvas.drawCircle(x, y, 2.2f, dot)
                    x += step
                }
                y += step
            }
        }

        /**
         * Draw the gratitude / journal page: two columns at the top
         * (3 Things I'm Grateful For + The Best Thing That Happened Today),
         * then a wide Doodle area below.
         */
        private fun drawGratitudePage(canvas: Canvas) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)

            val robotBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val robotPlain = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

            val headerPaint = TextPaint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
                textSize = 42f
                typeface = robotBold
                isAntiAlias = true
            }
            val headerCenterPaint = TextPaint(headerPaint).apply {
                textAlign = Paint.Align.CENTER
            }
            val numberPaint = TextPaint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
                textSize = 36f
                typeface = robotPlain
                isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = Color.argb(140, 0, 0, 0)
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            val dashedBorder = Paint().apply {
                color = Color.argb(100, 0, 0, 0)
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                isAntiAlias = true
            }

            val outerLeft = 60f
            val outerRight = 1344f
            val pageMidGap = 30f
            val colLeft = outerLeft
            val colMidRight = (outerLeft + outerRight) / 2f - pageMidGap / 2f  // 672
            val colMidLeft = (outerLeft + outerRight) / 2f + pageMidGap / 2f   // 732
            val colRight = outerRight

            val headerY = 130f
            val firstLineY = 200f
            val lineSpacing = 70f
            val numLines = 11
            val bottomOfColumns = firstLineY + (numLines - 1) * lineSpacing  // 200 + 10*70 = 900

            // --- Left column: 3 Things I'm Grateful For ---
            canvas.drawText("3 THINGS I'M GRATEFUL FOR", colLeft, headerY, headerPaint)
            // Three numbered slots, each with multiple lines below
            val slotsPerItem = numLines / 3  // 3
            val numberCol = colLeft
            val textIndent = colLeft + 60f
            for (i in 0 until numLines) {
                val y = firstLineY + i * lineSpacing
                if (i % slotsPerItem == 0) {
                    val n = (i / slotsPerItem) + 1
                    canvas.drawText("$n.", numberCol, y - 10f, numberPaint)
                }
                canvas.drawLine(textIndent, y, colMidRight, y, linePaint)
            }

            // --- Right column: The Best Thing That Happened Today ---
            val rightColCenter = (colMidLeft + colRight) / 2f
            canvas.drawText("THE BEST THING TODAY", rightColCenter, headerY, headerCenterPaint)
            for (i in 0 until numLines) {
                val y = firstLineY + i * lineSpacing
                canvas.drawLine(colMidLeft, y, colRight, y, linePaint)
            }

            // --- Doodle area (full width) ---
            val doodleHeaderY = bottomOfColumns + 80f
            canvas.drawText("DOODLE", colLeft, doodleHeaderY, headerPaint)
            val doodleTop = doodleHeaderY + 25f
            val doodleBottom = 1820f
            canvas.drawRect(colLeft, doodleTop, colRight, doodleBottom, dashedBorder)
        }

        /**
         * Draw the Pickings page: a NOTES writing zone, a QUOTES writing zone, and two
         * dashed image boxes below. Strokes save under the "pickings" notePage key, so the
         * sync can route this page to michaeljoelhall.com (journal CPT) + mjh.yoga /notes/.
         */
        private fun drawPickingsPage(canvas: Canvas) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)

            val robotBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val headerPaint = TextPaint().apply {
                color = Color.BLACK; textAlign = Paint.Align.LEFT; textSize = 40f; typeface = robotBold; isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = Color.argb(140, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val dashedBorder = Paint().apply {
                color = Color.argb(100, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
            }

            val panelBorder = Paint().apply {
                color = Color.argb(170, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }

            val left = 40f
            val right = 1364f
            val gap = 36f
            val pageCenter = (left + right) / 2f
            val colMidR = pageCenter - gap / 2f   // right edge of the left column
            val colMidL = pageCenter + gap / 2f   // left edge of the right column
            val lineSpacing = 60f

            // Two tall writing panels side by side, NOTES (left) + QUOTES (right). No title — bigger boxes.
            val panelTop = 60f
            val panelBottom = 1150f
            canvas.drawText("NOTES", left, panelTop - 14f, headerPaint)
            canvas.drawRect(left, panelTop, colMidR, panelBottom, panelBorder)
            run {
                var y = panelTop + lineSpacing
                while (y < panelBottom - 12f) { canvas.drawLine(left + 14f, y, colMidR - 14f, y, linePaint); y += lineSpacing }
            }
            canvas.drawText("QUOTES", colMidL, panelTop - 14f, headerPaint)
            canvas.drawRect(colMidL, panelTop, right, panelBottom, panelBorder)
            run {
                var y = panelTop + lineSpacing
                while (y < panelBottom - 12f) { canvas.drawLine(colMidL + 14f, y, right - 14f, y, linePaint); y += lineSpacing }
            }

            // Two square image tiles below, sized to the column width.
            val imgHeaderY = panelBottom + 50f
            val boxTop = imgHeaderY + 18f
            val boxSize = colMidR - left
            val boxBottom = boxTop + boxSize
            canvas.drawText("IMAGE 1", left, imgHeaderY, headerPaint)
            canvas.drawText("IMAGE 2", colMidL, imgHeaderY, headerPaint)
            canvas.drawRect(left, boxTop, colMidR, boxBottom, dashedBorder)
            canvas.drawRect(colMidL, boxTop, right, boxBottom, dashedBorder)
        }
    }
}
