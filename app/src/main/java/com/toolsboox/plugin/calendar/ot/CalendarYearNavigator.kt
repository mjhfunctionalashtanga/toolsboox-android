package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.toolsboox.R
import com.toolsboox.ot.Creator
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarYear
import com.toolsboox.plugin.calendar.ui.CalendarYearFragment
import java.time.LocalDate

/**
 * Create navigator of yearly template of calendar plugin.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarYearNavigator {

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
         * @param calendarYear the calendar data class
         * @return true
         */
        fun onTouchEvent(
            view: View, motionEvent: MotionEvent, fragment: CalendarYearFragment, calendarYear: CalendarYear
        ): Boolean {
            val year = calendarYear.year

            val localDate = LocalDate.of(year, 1, 1)

            when (motionEvent.action) {
                MotionEvent.ACTION_UP -> {
                    val px = motionEvent.x * 1404.0f / view.width
                    // Slots drawn by NavigatorRenderer: [Year]
                    when (NavigatorRenderer.hitTest(px, 1)) {
                        NavigatorRenderer.HIT_PREV -> CalendarNavigator.toYearPage(fragment, localDate.minusYears(1L))
                        NavigatorRenderer.HIT_NEXT -> CalendarNavigator.toYearPage(fragment, localDate.plusYears(1L))
                        0 -> CalendarNavigator.toYearPage(fragment, localDate)
                    }
                    return true
                }
            }

            return true
        }

        /**
         * Draw the navigator of quarterly template of calendar plugin.
         *
         * @param context the context
         * @param canvas the canvas
         * @param calendarYear data class
         * @param calendarPattern the calendar pattern
         */
        fun draw(context: Context, canvas: Canvas, calendarYear: CalendarYear, calendarPattern: CalendarPattern) {
            val year = calendarYear.year

            NavigatorRenderer.render(context, canvas, listOf(
                NavigatorRenderer.Slot("$year", NavigatorRenderer.Emphasis.FOCAL,
                    calendarPattern.getYearPages() > 0, calendarPattern.getYearNotes()),
            ))
        }
    }
}
