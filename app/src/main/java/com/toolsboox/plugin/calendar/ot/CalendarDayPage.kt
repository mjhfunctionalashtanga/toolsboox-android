package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Canvas
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import com.toolsboox.R
import com.toolsboox.ot.Creator
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ui.CalendarDayFragment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Create daily template of calendar plugin.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayPage {

    companion object {
        // Cell width
        private const val cew = 645.0f  // widened 07-24: the old 600 left a ~200px dead band right of Tasks ("margin far greater than it needs")

        // Cell height
        private const val ceh = 50.0f

        // Left offset
        private const val lo = 20.0f

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
         * @return true
         */
        fun onTouchEvent(
            view: View, motionEvent: MotionEvent, gestureResult: Int,
            fragment: CalendarDayFragment, calendarDay: CalendarDay
        ): Boolean {
            if (motionEvent.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return true

            val year = calendarDay.year
            val month = calendarDay.month
            val day = calendarDay.day
            val locale = calendarDay.locale

            val localDate = LocalDate.of(year, month, day)

            when (gestureResult) {
                OnGestureListener.LTR -> {
                    CalendarNavigator.toDayPage(fragment, localDate.minusDays(1L))
                    return true
                }

                OnGestureListener.RTL -> {
                    CalendarNavigator.toDayPage(fragment, localDate.plusDays(1L))
                    return true
                }

                OnGestureListener.UTD -> {
                    CalendarNavigator.toWeekPage(fragment, localDate, locale)
                    return true
                }

                OnGestureListener.DTU -> {
                    CalendarNavigator.toDayNote(fragment, localDate, "pickings")
                    return true
                }
            }

            return true
        }

        /** A drawn quick-win row and where it landed, so a tap can go straight to the doing —
         *  recorded at draw time, hit-tested on tap, exactly the PickingsCover/DayEventHits
         *  discipline: the panel is pixels, and a second guess at its layout would drift. */
        data class WinRow(val win: QuickWinsEngine.Win, val rect: android.graphics.RectF)

        @Volatile
        private var winRows: List<WinRow> = emptyList()

        /** The quick win under a canvas-space point, or null. */
        fun winAt(x: Float, y: Float): QuickWinsEngine.Win? =
            winRows.firstOrNull { it.rect.contains(x, y) }?.win

        /** Forget the recorded rows — called when a page without the panel is drawn, so a stale
         *  rectangle can't send a tap on some other page off to a task. */
        fun clearWinRows() {
            winRows = emptyList()
        }

        /** A drawn quick-wins GLIMPSE line and where it landed. Same record-at-draw discipline as
         *  [WinRow]: the glimpse sits in the bottom slice of the Roots band (its own pixels), so a
         *  tap resolves against the rectangle recorded when it was drawn, never a second guess. */
        data class GlimpseRow(val line: QuickWinsGlimpse.Line, val rect: android.graphics.RectF)

        @Volatile
        private var glimpseRows: List<GlimpseRow> = emptyList()

        /** The glimpse line under a canvas-space point, or null. */
        fun glimpseAt(x: Float, y: Float): QuickWinsGlimpse.Line? =
            glimpseRows.firstOrNull { it.rect.contains(x, y) }?.line

        /** Forget the recorded glimpse rows — the [clearWinRows] rule, for the same reason. */
        fun clearGlimpseRows() {
            glimpseRows = emptyList()
        }

        /**
         * Draw the daily template of calendar plugin.
         *
         * @param context the context
         * @param canvas the canvas
         * @param calendarDay data class
         * @param calendarEvents the list of calendar events
         * @param quickWins the parked quick wins (today only; empty when cold or on other days)
         * @param quickWinsGlimpse the parked glimpse lines: null = still cooking (draw a quiet "…"),
         *   empty = nothing qualifies (draw nothing), the lines otherwise. Today only.
         */
        fun drawPage(
            context: Context, canvas: Canvas, calendarDay: CalendarDay, calendarEvents: List<CalendarEvent>,
            quickWins: List<QuickWinsEngine.Win> = emptyList(),
            quickWinsGlimpse: List<QuickWinsGlimpse.Line>? = emptyList()
        ) {
            val schedulesText = context.getString(R.string.calendar_day_schedules)
            val tasksText = context.getString(R.string.calendar_day_tasks)
            val rootsText = context.getString(R.string.calendar_day_roots)
            val allDayText = context.getString(R.string.calendar_day_all_day)
            val locale = calendarDay.locale

            canvas.drawRect(0.0f, 0.0f, 1404.0f, 1872.0f, Creator.fillWhite)

            // Schedules title
            canvas.drawRect(lo, to, lo + cew, to + ceh, Creator.fillGrey80)
            canvas.drawText(schedulesText, lo + 10.0f, to + ceh - 10.0f, Creator.textDefaultWhite)

            calendarEvents.sortedWith(compareBy({ it.startDate }, { it.endDate }))
            val laneOne = mutableListOf<CalendarEvent>()
            val laneTwo = mutableListOf<CalendarEvent>()
            val laneFull = mutableListOf<CalendarEvent>()
            val outside = mutableListOf<CalendarEvent>()

            // THE DAY, THEN THE SETTING, NEVER A LITERAL — and never `!!`. The non-null assertion
            // was only ever safe because the loader stamped a value onto every day on the way past;
            // that stamp is gone (it cost Michael 116 days of his chosen 7am), so a day may honestly
            // arrive with no opinion and [PagePrefs] answers for it. Resolved identically in
            // [DayEventHits], so the tap targets can never disagree with the pixels.
            val startHour = PagePrefs.startHourOf(context, calendarDay)
            if (startHour < 0) {
                outside.addAll(calendarEvents)
            } else if (!calendarDay.hasLanes) {
                outside.addAll(calendarEvents)
            } else {
                for (event in calendarEvents) {
                    if (event.allDay) {
                        outside.add(event)
                        continue
                    }

                    val startLocalDate = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (startLocalDate.hour * 60 + startLocalDate.minute < startHour * 60) {
                        outside.add(event)
                        continue
                    }
                    val endLocalDate = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (endLocalDate.hour * 60 + endLocalDate.minute > (startHour + 17) * 60) {
                        outside.add(event)
                        continue
                    }

                    if (checkOverlap(event, laneOne)) {
                        if (checkOverlap(event, laneTwo)) {
                            outside.add(event)
                        } else {
                            laneTwo.add(event)
                        }
                    } else {
                        laneOne.add(event)
                    }
                }

                for (event in calendarEvents) {
                    if (event.allDay) continue
                    val startLocalDate = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (startLocalDate.hour * 60 + startLocalDate.minute < startHour * 60) continue
                    val endLocalDate = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (endLocalDate.hour * 60 + endLocalDate.minute > (startHour + 17) * 60) continue

                    if (!checkFullWidth(event, laneOne, laneTwo)) {
                        laneFull.add(event)
                    }
                }
            }

            val notesTitle: MutableList<String> = mutableListOf()
            val notesLeft: MutableList<String> = mutableListOf()
            val notesRight: MutableList<String> = mutableListOf()

            calendarDay.readingProgress.take(8).forEach {
                if (it.authors == null) notesTitle.add(it.title)
                else notesTitle.add("${it.authors}: ${it.title}")

                notesLeft.add(it.progress ?: "-")
                notesRight.add(DateFormat.getTimeFormat(context).format(it.lastAccess))
            }

            if (notesTitle.size < 8) {
                outside.take(8 - notesTitle.size).forEach {
                    notesTitle.add(it.title)
                    if (it.allDay) {
                        notesLeft.add(allDayText)
                        notesRight.add("")
                    } else {
                        val startLocalDate = Instant.ofEpochMilli(it.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        val endLocalDate = Instant.ofEpochMilli(it.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        val startDate = startLocalDate.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))
                        val endDate = endLocalDate.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))

                        notesLeft.add(startDate)
                        notesRight.add(endDate)
                    }
                }
            }

            // Quick Wins: the stars that used to fill these rows already surface in the feeds,
            // so the rows go to the system's shortest paths to victory instead — the same wins
            // the ⚡ surface computes, read from QuickWinsEngine's parked answer (never computed
            // here; the render path must not wait on a file walk or the network). "⚡" marks the
            // row; a tap goes straight to the doing — mail, rolodex, or the task's home day.
            // Book reading-progress rows and outside events keep their places above.
            val winStart = notesTitle.size
            val rowWins = mutableListOf<QuickWinsEngine.Win>()
            if (notesTitle.size < 7) {
                quickWins.take(minOf(6, 7 - notesTitle.size)).forEach { w ->
                    // Row 1: ⚡ + the task. Row 2: why it's a win (its day rides on the right).
                    notesTitle.add("⚡  ${w.text}")
                    notesLeft.add(w.reasons.joinToString("  ·  "))
                    notesRight.add(
                        w.sourceDay.format(DateTimeFormatter.ofPattern("MMM d"))
                    )
                    rowWins.add(w)
                }
            }

            val notesCalsText = if (outside.isEmpty()) {
                context.getString(R.string.calendar_day_quick_wins)
            } else {
                context.getString(R.string.calendar_day_quick_wins_events).format(outside.size)
            }

            // Schedules grid
            canvas.drawLine(lo, to + ceh, lo + cew, to + ceh, Creator.lineDefaultBlack)
            for (i in 1..34) {
                if (i % 2 == 1) {
                    canvas.drawLine(lo, to + i * ceh, lo + cew, to + i * ceh, Creator.lineDefaultGrey50)
                    canvas.drawText(":00", lo + 115.0f, to + 35.0f + i * ceh, Creator.textSmallBlackRight)
                    if (startHour > -1) {
                        val localTime = LocalTime.of(i / 2 + startHour, 0, 0)
                        if (DateFormat.is24HourFormat(context)) {
                            val hourText = localTime.format(DateTimeFormatter.ofPattern("HH"))
                            canvas.drawText(hourText, lo + 40.0f, to + 20.0f + (i + 1) * ceh, Creator.text60BlackCenter)
                        } else {
                            val hourText = localTime.format(DateTimeFormatter.ofPattern("h"))
                            val ampmText = localTime.format(DateTimeFormatter.ofPattern("a"))
                            canvas.drawText(hourText, lo + 70.0f, to + 40.0f + (i) * ceh, Creator.textDefaultBlackRight)
                            canvas.drawText(ampmText, lo + 70.0f, to + 30.0f + (i + 1) * ceh, Creator.textDefaultBlackRight)
                        }
                    }
                } else {
                    canvas.drawLine(lo + 80.0f, to + i * ceh, lo + cew, to + i * ceh, Creator.lineDefaultGrey50)
                    canvas.drawRect(lo + 80.0f, to + i * ceh, lo + cew, to + i * ceh + ceh, Creator.fillGrey20)
                    canvas.drawText(":30", lo + 115.0f, to + 35.0f + i * ceh, Creator.textSmallBlackRight)
                }
            }
            canvas.drawLine(lo, to + 35 * ceh, lo + cew, to + 35 * ceh, Creator.lineDefaultBlack)
            canvas.drawLine(lo + 120.0f, to + ceh, lo + 120.0f, to + 35 * ceh, Creator.lineDefaultBlack)

            if (laneOne.isNotEmpty()) {
                drawEventLane(canvas, startHour, laneOne, 120.0f, (cew - 120.0f) / 2)
            }
            if (laneTwo.isNotEmpty()) {
                drawEventLane(canvas, startHour, laneTwo, 120.0f + (cew - 120.0f) / 2, (cew - 120.0f) / 2)
            }
            if (laneFull.isNotEmpty()) {
                drawEventLane(canvas, startHour, laneFull, 120.0f, cew - 120.0f)
            }

            // Tasks title
            canvas.drawRect(lo + cew + 50.0f, to, lo + 2 * cew + 50.0f, to + ceh, Creator.fillGrey80)
            canvas.drawText(tasksText, lo + cew + 60.0f, to + ceh - 10.0f, Creator.textDefaultWhite)

            // Tasks grid.
            //
            // Twelve rows, not sixteen: four go to the Roots band below. There was briefly a
            // write-in strip taking two more — lassoing a written task and tapping "→ item" does
            // the same job without deleting your ink, so the rows came back. — the
            // spiral's one line sits where you already look when you're deciding what to do,
            // between what you have to do and what you've been reading. At the foot of the page
            // it was out of the way in the sense of being ignorable.
            canvas.drawLine(lo + cew + 50.0f, to + ceh, lo + 2 * cew + 50.0f, to + ceh, Creator.lineDefaultBlack)
            for (i in 1..12) {
                canvas.drawLine(
                    lo + cew + 50.0f, to + i * ceh, lo + 2 * cew + 50.0f, to + i * ceh,
                    Creator.lineDefaultGrey50
                )
                if (i % 2 == 0) {
                    canvas.drawRect(
                        lo + cew + 50.0f, to + i * ceh, lo + 2 * cew + 50.0f, to + i * ceh + ceh,
                        Creator.fillGrey20
                    )
                }
                canvas.drawRect(
                    lo + cew + 60.0f, to + i * ceh + 10.0f, lo + cew + 90.0f, to + i * ceh + 40.0f,
                    Creator.lineDefaultGrey50
                )
            }
            canvas.drawLine(
                lo + cew + 50.0f, to + 13 * ceh, lo + 2 * cew + 50.0f, to + 13 * ceh,
                Creator.lineDefaultBlack
            )
            canvas.drawLine(
                lo + cew + 100.0f, to + ceh, lo + cew + 100.0f, to + 13 * ceh,
                Creator.lineDefaultBlack
            )

            // Roots band: title bar, then empty paper the live line is drawn over. The text
            // itself is a view, not template ink, because it changes with the ledger and the
            // template is baked per day.
            canvas.drawRect(lo + cew + 50.0f, to + 13 * ceh, lo + 2 * cew + 50.0f, to + 14 * ceh, Creator.fillGrey80)
            canvas.drawText(rootsText, lo + cew + 60.0f, to + 14 * ceh - 10.0f, Creator.textDefaultWhite)
            canvas.drawLine(
                lo + cew + 50.0f, to + 18 * ceh, lo + 2 * cew + 50.0f, to + 18 * ceh,
                Creator.lineDefaultBlack
            )

            // The compact ⚡ glimpse was REMOVED. On Android the Quick Wins already render as the
            // panel rows below (reading · outside · wins), and the weather/temp line sits directly
            // under the Quick Wins title — leaving no clear space for a separate peek without running
            // over it. The doors now own the WHOLE Roots band above; the wins live in the panel rows.
            glimpseRows = emptyList()

            // Quick Wins title (carries the outside-event count when there are any — those
            // events still live in these rows; only the stars moved out).
            canvas.drawRect(lo + cew + 50.0f, to + 18 * ceh, lo + 2 * cew + 50.0f, to + 19 * ceh, Creator.fillGrey80)
            canvas.drawText(notesCalsText, lo + cew + 60.0f, to + 19 * ceh - 10.0f, Creator.textDefaultWhite)
            // Notes grid
            canvas.drawLine(
                lo + cew + 50.0f,
                to + 19 * ceh,
                lo + 2 * cew + 50.0f,
                to + 19 * ceh,
                Creator.lineDefaultBlack
            )
            for (i in 20..35) {
                canvas.drawLine(
                    lo + cew + 50.0f, to + i * ceh, lo + 2 * cew + 50.0f, to + i * ceh,
                    Creator.lineDefaultGrey50
                )
                if (i % 2 == 0) {
                    canvas.drawRect(
                        lo + cew + 50.0f, to + i * ceh, lo + 2 * cew + 50.0f, to + i * ceh + ceh,
                        Creator.fillGrey20
                    )
                }
            }
            canvas.drawLine(
                lo + cew + 50.0f, to + 35 * ceh, lo + 2 * cew + 50.0f, to + 35 * ceh,
                Creator.lineDefaultBlack
            )

            // Weather + moon — a subtle line, then a compact hour-by-hour temperature sparkline,
            // right below the Notes & Other events bar.
            val wmDate = java.time.LocalDate.of(calendarDay.year, calendarDay.month, calendarDay.day)
            Creator.drawEllipsizedText(
                canvas, WeatherMoon.summary(context, wmDate),
                Creator.textSmallBlack, lo + cew + 60.0f, to + 20 * ceh - 12.0f, cew
            )
            val temps = WeatherMoon.hourly(context, wmDate)
            if (temps.size >= 2) {
                val x0 = lo + cew + 60.0f
                val x1 = lo + 2 * cew + 40.0f
                val yBot = to + 21 * ceh - 6.0f
                val yTop = to + 20 * ceh + 10.0f
                val minT = temps.minOrNull() ?: 0.0f
                val range = ((temps.maxOrNull() ?: 0.0f) - minT).coerceAtLeast(1.0f)
                val n = temps.size
                fun px(i: Int) = x0 + (x1 - x0) * i / (n - 1)
                fun py(t: Float) = yBot - (t - minT) / range * (yBot - yTop)
                for (i in 1 until n) {
                    canvas.drawLine(px(i - 1), py(temps[i - 1]), px(i), py(temps[i]), Creator.lineDefaultBlack)
                }
                // Dot the current hour.
                val nowH = java.time.LocalTime.now().hour.coerceIn(0, n - 1)
                val dot = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(px(nowH), py(temps[nowH]), 6.0f, dot)
            }

            // Panel rows (shifted one slot down to sit under the weather/moon line): reading
            // progress, then outside events, then the quick wins. Each win's row rectangle is
            // recorded as it is drawn, so the fragment's tap can never disagree with a pixel.
            val recorded = mutableListOf<WinRow>()
            for (i in 0..6) {
                if (i < notesTitle.size) {
                    val textX = lo + cew + 60.0f
                    Creator.drawEllipsizedText(
                        canvas, notesTitle[i], Creator.textDefaultBlack,
                        textX, to + (22 + i * 2) * ceh - 10.0f, cew - (textX - lo - cew - 60.0f)
                    )
                    canvas.drawText(
                        notesLeft[i], textX, to + (23 + i * 2) * ceh - 10.0f,
                        Creator.textSmallBlack
                    )
                    canvas.drawText(
                        notesRight[i], lo + cew + 40.0f + cew, to + (23 + i * 2) * ceh - 10.0f,
                        Creator.textSmallBlackRight
                    )
                    if (i >= winStart) {
                        rowWins.getOrNull(i - winStart)?.let { w ->
                            recorded.add(WinRow(w, android.graphics.RectF(
                                lo + cew + 50.0f, to + (21 + i * 2) * ceh,
                                lo + 2 * cew + 50.0f, to + (23 + i * 2) * ceh
                            )))
                        }
                    }
                }
            }
            winRows = recorded
        }

        private fun drawEventLane(canvas: Canvas, startHour: Int, lane: MutableList<CalendarEvent>, llo: Float, lw: Float) {
            for (event in lane) {
                val startLocalDate = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val endLocalDate = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val cehs = (startLocalDate.hour * 60 + startLocalDate.minute - startHour * 60) / 30.0f * ceh + 1.0f * ceh
                val cehe = (endLocalDate.hour * 60 + endLocalDate.minute - startHour * 60) / 30.0f * ceh + 1.0f * ceh
                canvas.drawRect(lo + llo + 5.0f, to + cehs, lo + llo + lw - 5.0f, to + cehe, Creator.fillGrey10)
                canvas.drawRect(lo + llo + 5.0f, to + cehs, lo + llo + lw - 5.0f, to + cehe, Creator.lineDefaultBlack)

                Creator.drawEllipsizedText(
                    canvas, event.title, Creator.textSmallBlack,
                    lo + llo + 15.0f, to + cehs + ceh * 0.66f - 10.0f, lw - 20.0f
                )
            }
        }

        private fun checkOverlap(event: CalendarEvent, lane: MutableList<CalendarEvent>): Boolean {
            for (laneEvent in lane) {
                if (event.startDate in laneEvent.startDate..<laneEvent.endDate) return true
                if (laneEvent.startDate in event.startDate..<event.endDate) return true
            }

            return false
        }

        private fun checkFullWidth(event: CalendarEvent, laneOne: MutableList<CalendarEvent>, laneTwo: MutableList<CalendarEvent>): Boolean {
            for (laneEvent in laneOne) {
                if (laneEvent.id == event.id) continue
                if (event.startDate in laneEvent.startDate..<laneEvent.endDate) return true
                if (laneEvent.startDate in event.startDate..<event.endDate) return true
            }

            for (laneEvent in laneTwo) {
                if (laneEvent.id == event.id) continue
                if (event.startDate in laneEvent.startDate..<laneEvent.endDate) return true
                if (laneEvent.startDate in event.startDate..<event.endDate) return true
            }

            laneOne.remove(event)
            laneTwo.remove(event)

            return false
        }
    }
}
