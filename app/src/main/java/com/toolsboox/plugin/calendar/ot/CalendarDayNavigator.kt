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
import com.toolsboox.ui.plugin.ScreenFragment
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
            view: View, motionEvent: MotionEvent, fragment: ScreenFragment, calendarDay: CalendarDay,
            onStepDay: ((LocalDate) -> Unit)? = null,
            // List surfaces (feed / notes) pass this to FILTER by the tapped level
            // ("day"/"week"/"month"/"quarter"/"year") instead of opening the calendar page.
            onSelectPeriod: ((String, LocalDate) -> Unit)? = null
        ): Boolean {
            val year = calendarDay.year
            val month = calendarDay.month
            val day = calendarDay.day
            val locale = calendarDay.locale

            val localDate = LocalDate.of(year, month, day)

            when (motionEvent.action) {
                MotionEvent.ACTION_UP -> {
                    val px = motionEvent.x * 1404.0f / view.width
                    // Drawn slots (see draw()): [0]=Day (focal), [1]=weekday, [2]=Week, [3]=Month, [4]=Quarter, [5]=Year.
                    // On a list surface (onStepDay/onSelectPeriod set) the arrows move that
                    // surface's date in place and slots FILTER; on the day page they navigate.
                    when (NavigatorRenderer.slotAt(px, 6)) {
                        NavigatorRenderer.ARROW_PREV ->
                            if (onStepDay != null) onStepDay(localDate.minusDays(1L))
                            else toSameDayPage(fragment, localDate.minusDays(1L))
                        NavigatorRenderer.ARROW_NEXT ->
                            if (onStepDay != null) onStepDay(localDate.plusDays(1L))
                            else toSameDayPage(fragment, localDate.plusDays(1L))
                        0, 1 ->
                            if (onSelectPeriod != null) onSelectPeriod("day", localDate)
                            else CalendarNavigator.toDayPage(fragment, localDate)
                        2 ->
                            if (onSelectPeriod != null) onSelectPeriod("week", localDate)
                            else CalendarNavigator.toWeekPage(fragment, localDate, locale)
                        3 ->
                            if (onSelectPeriod != null) onSelectPeriod("month", localDate)
                            else CalendarNavigator.toMonthPage(fragment, localDate)
                        4 ->
                            if (onSelectPeriod != null) onSelectPeriod("quarter", localDate)
                            else CalendarNavigator.toQuarterPage(fragment, localDate)
                        5 ->
                            if (onSelectPeriod != null) onSelectPeriod("year", localDate)
                            else CalendarNavigator.toYearPage(fragment, localDate)
                    }
                }
            }

            return true
        }

        /**
         * Navigate to another day while staying in the current page group:
         * on a named/numbered note page (pickings, gratitude, intake, 0...),
         * open the SAME page of the target day; on the plain day page, open
         * the target day's day page. A day with no ink yet for that page just
         * shows its empty template (normal named-page behavior).
         *
         * @param fragment the fragment
         * @param targetDate the target date
         */
        private fun toSameDayPage(fragment: ScreenFragment, targetDate: LocalDate) {
            val notePage = (fragment as? CalendarDayFragment)?.currentNotePage()
            if (notePage != null) {
                CalendarNavigator.toDayNote(fragment, targetDate, notePage)
            } else {
                CalendarNavigator.toDayPage(fragment, targetDate)
            }
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
            ),
                // The day's moon phase, seated in the corner the retired hamburger freed — it
                // used to hide down in the page's weather line; the header margin gives it a
                // proper home at a glance.
                cornerBadge = WeatherMoon.moon(currentDate).first)
        }
    }
}
