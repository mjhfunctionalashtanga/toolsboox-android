package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.toolsboox.R
import com.toolsboox.ot.Creator
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ui.CalendarDayFragment
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.*

/**
 * Create navigator of daily template of calendar plugin.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayNavigator {

    companion object {
        // Cell width
        private const val cew = 65.0f

        // Cell height
        private const val ceh = 120.0f

        // Left offset
        private const val lo = (1404.0f - 20 * cew) / 2.0f

        // Top offset
        private const val to = (140.4f - 1 * ceh) / 2.0f

        /**
         * Process touch event on the calendar navigator and navigate to the view of calendar.
         *
         * @param view the surface view
         * @param motionEvent the motion event
         * @param fragment the parent fragment
         * @param calendarDay the calendar data class
         * @return true
         */
        fun onTouchEvent(
            view: View, motionEvent: MotionEvent, fragment: CalendarDayFragment, calendarDay: CalendarDay
        ): Boolean {
            val year = calendarDay.year
            val month = calendarDay.month
            val day = calendarDay.day
            val locale = calendarDay.locale

            val localDate = LocalDate.of(year, month, day)

            when (motionEvent.action) {
                MotionEvent.ACTION_UP -> {
                    val px = motionEvent.x * 1404.0f / view.width
                    // Slots drawn by NavigatorRenderer: [Day, DoW, Week, Month, Quarter, Year]
                    when (NavigatorRenderer.hitTest(px, 5)) {
                        NavigatorRenderer.HIT_PREV -> CalendarNavigator.toDayPage(fragment, localDate.minusDays(1L))
                        NavigatorRenderer.HIT_NEXT -> CalendarNavigator.toDayPage(fragment, localDate.plusDays(1L))
                        0, 1 -> CalendarNavigator.toDayPage(fragment, localDate)
                        2 -> CalendarNavigator.toWeekPage(fragment, localDate, locale)
                        3 -> CalendarNavigator.toMonthPage(fragment, localDate)
                        4 -> CalendarNavigator.toQuarterPage(fragment, localDate)
                        5 -> CalendarNavigator.toYearPage(fragment, localDate)
                    }
                    return true
                }
            }

            return true
        }

        /**
         * Draw the navigator of daily template of calendar plugin.
         *
         * @param context the context
         * @param canvas the canvas
         * @param calendarDay data class
         * @param calendarPattern the calendar pattern
         */
        fun draw(context: Context, canvas: Canvas, calendarDay: CalendarDay, calendarPattern: CalendarPattern) {
            val currentDate = LocalDate.of(calendarDay.year, calendarDay.month, calendarDay.day)
            val locale = calendarDay.locale

            val year = currentDate.year
            val dayOfYear = currentDate.dayOfYear
            val monthOfYear = currentDate.monthValue
            val monthName = currentDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val quarterOfYear = (currentDate.monthValue - 1) / 3 + 1
            val day = currentDate.dayOfMonth
            val weekOfYear = currentDate.plusWeeks(0L).get(WeekFields.of(locale).weekOfWeekBasedYear())
            val dayOfWeek = currentDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

            NavigatorRenderer.render(context, canvas, listOf(
                NavigatorRenderer.Slot("$day", NavigatorRenderer.Emphasis.FOCAL,
                    calendarPattern.getDayPages(dayOfYear) > 0, calendarPattern.getDayNotes(dayOfYear)),
                NavigatorRenderer.Slot(dayOfWeek, NavigatorRenderer.Emphasis.NORMAL, false, 0),
                NavigatorRenderer.Slot(context.getString(R.string.week_abbreviation, weekOfYear),
                    NavigatorRenderer.Emphasis.MUTED,
                    calendarPattern.getWeekPages(weekOfYear) > 0, calendarPattern.getWeekNotes(weekOfYear)),
                NavigatorRenderer.Slot(monthName, NavigatorRenderer.Emphasis.NORMAL,
                    calendarPattern.getMonthPages(monthOfYear) > 0, calendarPattern.getMonthNotes(monthOfYear)),
                NavigatorRenderer.Slot(context.getString(R.string.quarter_abbreviation, quarterOfYear),
                    NavigatorRenderer.Emphasis.MUTED,
                    calendarPattern.getQuarterPages(quarterOfYear) > 0, calendarPattern.getQuarterNotes(quarterOfYear)),
                NavigatorRenderer.Slot("$year", NavigatorRenderer.Emphasis.MUTED,
                    calendarPattern.getYearPages() > 0, calendarPattern.getYearNotes()),
            ))
        }
    }
}
