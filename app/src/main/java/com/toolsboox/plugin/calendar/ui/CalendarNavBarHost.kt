package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.widget.ImageView
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.CalendarDayNavigator
import com.toolsboox.ui.plugin.ScreenFragment
import java.time.LocalDate

/**
 * Hosts the REAL Almanac day navigator (the same 1404×140 strip the day page draws
 * via [CalendarDayNavigator]) inside any list/feed surface — so Tasks & Events,
 * History and the Feed navigate with the exact same bar, not a look-alike.
 *
 * The surface supplies its `navigatorImageView` and an [onStepDay] callback; the
 * arrows then move that surface's own anchor date in place, while the day/week/
 * month/quarter/year slots jump into the calendar just like on the day page.
 */
class CalendarNavBarHost(
    private val context: Context,
    private val imageView: ImageView,
    fragment: ScreenFragment,
    onStepDay: (LocalDate) -> Unit,
    // List surfaces pass this to filter by the tapped level instead of jumping to
    // the calendar page (day/week/month/quarter/year).
    onSelectPeriod: ((String, LocalDate) -> Unit)? = null,
) {
    private val bitmap = Bitmap.createBitmap(1404, 140, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var currentDay: CalendarDay? = null

    init {
        imageView.setImageBitmap(bitmap)
        imageView.setOnTouchListener { v, e ->
            val d = currentDay ?: return@setOnTouchListener false
            CalendarDayNavigator.onTouchEvent(v, e, fragment, d, onStepDay, onSelectPeriod)
        }
    }

    /** Redraw the strip for [day] using its year's [pattern] (dots for filled pages). */
    fun render(day: CalendarDay, pattern: CalendarPattern) {
        currentDay = day
        canvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        CalendarDayNavigator.draw(context, canvas, day, pattern)
        imageView.invalidate()
    }
}
