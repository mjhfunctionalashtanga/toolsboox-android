package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.toolsboox.R
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.NavigatorRenderer
import com.toolsboox.ui.plugin.ScreenFragment
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Hosts the REAL Almanac day navigator (the same 1404×140 strip the day page draws)
 * inside any list/feed surface — so Tasks & Events, History, the Feed and the garden
 * pages navigate with the exact same bar, not a look-alike.
 *
 * The surface supplies its `navigatorImageView` and an [onStepDay] callback; the
 * arrows then move that surface's own anchor date in place, while the day/week/
 * month/quarter/year slots jump into the calendar just like on the day page.
 *
 * Filtering surfaces pass [onSelectPeriod], and then the strip tells the truth about
 * the filter instead of always dressing itself as today: the slot for the ACTIVE
 * granularity is the focal one (year filter → "2026" is the big slot with the accent
 * bar under it), and the arrows step by that granularity — a month filter's carets
 * walk months, not days. Surfaces that never filter (Roots, Map) get a quiet strip
 * with no focal slot at all, because there the date is a place to LEAVE from, and a
 * huge bold "24" was reading like a stale title claiming a focus the page didn't have.
 */
class CalendarNavBarHost(
    private val context: Context,
    private val imageView: ImageView,
    private val fragment: ScreenFragment,
    private val onStepDay: (LocalDate) -> Unit,
    // List surfaces pass this to filter by the tapped level instead of jumping to
    // the calendar page (day/week/month/quarter/year).
    private val onSelectPeriod: ((String, LocalDate) -> Unit)? = null,
) {
    private val bitmap = Bitmap.createBitmap(1404, 140, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var currentDay: CalendarDay? = null
    private var currentPattern: CalendarPattern? = null

    /**
     * The active filter granularity — "day" / "week" / "month" / "quarter" / "year", or null for
     * no filter (an anchored-but-unfiltered strip: nothing focal, nothing underlined). Filtering
     * surfaces open on "day" because their lists open scoped to the anchor day; leave-from
     * surfaces open on null.
     */
    var granularity: String? = if (onSelectPeriod != null) "day" else null
        private set

    init {
        imageView.setImageBitmap(bitmap)
        imageView.setOnTouchListener { v, e -> onTouch(v, e) }
    }

    /** Redraw the strip for [day] using its year's [pattern] (dots for filled pages). */
    fun render(day: CalendarDay, pattern: CalendarPattern) {
        currentDay = day
        currentPattern = pattern
        redraw()
    }

    /**
     * Move the focus from outside — a surface clearing its filter back to "everything" passes
     * null, one restoring a saved filter passes the level. Redraws from the retained day.
     */
    fun setGranularity(g: String?) {
        granularity = g
        redraw()
    }

    private fun redraw() {
        val day = currentDay ?: return
        val pattern = currentPattern ?: return
        canvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        drawStrip(day, pattern)
        imageView.invalidate()
    }

    /**
     * The day page's six slots — day, weekday, week, month, quarter, year — but with the FOCAL
     * emphasis following [granularity] rather than always sitting on the day. Layout and dots
     * mirror CalendarDayNavigator.draw so the strip stays the same bar everywhere.
     */
    private fun drawStrip(calendarDay: CalendarDay, calendarPattern: CalendarPattern) {
        val date = LocalDate.of(calendarDay.year, calendarDay.month, calendarDay.day)
        val locale = calendarDay.locale

        val dayOfYear = date.dayOfYear
        val monthName = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val quarter = (date.monthValue - 1) / 3 + 1
        val weekOfYear = date.get(WeekFields.of(locale).weekOfWeekBasedYear())
        val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

        // Each slot keeps its day-page base emphasis unless it IS the active filter, which
        // promotes it to focal; the day slot steps down to normal when the focus is elsewhere
        // (or nowhere), so an unfiltered strip has no slot pretending to be the point.
        fun emph(slot: String, base: NavigatorRenderer.Emphasis) =
            if (granularity == slot) NavigatorRenderer.Emphasis.FOCAL else base

        NavigatorRenderer.render(context, canvas, listOf(
            NavigatorRenderer.Slot("${date.dayOfMonth}", emph("day", NavigatorRenderer.Emphasis.NORMAL),
                calendarPattern.getDayPages(dayOfYear) > 0, calendarPattern.getDayNotes(dayOfYear)),
            NavigatorRenderer.Slot(dayOfWeek, NavigatorRenderer.Emphasis.NORMAL, false, 0),
            NavigatorRenderer.Slot(context.getString(R.string.week_abbreviation, weekOfYear),
                emph("week", NavigatorRenderer.Emphasis.MUTED),
                calendarPattern.getWeekPages(weekOfYear) > 0, calendarPattern.getWeekNotes(weekOfYear)),
            NavigatorRenderer.Slot(monthName, emph("month", NavigatorRenderer.Emphasis.NORMAL),
                calendarPattern.getMonthPages(date.monthValue) > 0, calendarPattern.getMonthNotes(date.monthValue)),
            NavigatorRenderer.Slot(context.getString(R.string.quarter_abbreviation, quarter),
                emph("quarter", NavigatorRenderer.Emphasis.MUTED),
                calendarPattern.getQuarterPages(quarter) > 0, calendarPattern.getQuarterNotes(quarter)),
            NavigatorRenderer.Slot("${date.year}", emph("year", NavigatorRenderer.Emphasis.MUTED),
                calendarPattern.getYearPages() > 0, calendarPattern.getYearNotes()),
        ),
            // The anchor day's moon phase in the corner the retired hamburger freed — the same
            // badge the day page's strip wears, so the bar stays one bar everywhere.
            cornerBadge = com.toolsboox.plugin.calendar.ot.WeatherMoon.moon(date).first)
    }

    /** One step of the active granularity — the carets walk the filter's unit, never reset. */
    private fun step(date: LocalDate, dir: Long): LocalDate = when (granularity) {
        "week" -> date.plusWeeks(dir)
        "month" -> date.plusMonths(dir)
        "quarter" -> date.plusMonths(3L * dir)
        "year" -> date.plusYears(dir)
        else -> date.plusDays(dir)
    }

    private fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        val day = currentDay ?: return false
        if (motionEvent.action != MotionEvent.ACTION_UP) return true
        val date = LocalDate.of(day.year, day.month, day.day)
        val locale = day.locale
        val px = motionEvent.x * 1404.0f / view.width

        // Drawn slots (see drawStrip()): [0]=Day, [1]=weekday, [2]=Week, [3]=Month, [4]=Quarter,
        // [5]=Year. On a filtering surface a slot tap FILTERS in place; otherwise it opens the
        // calendar page for that period — but the arrows always move THIS surface's anchor.
        when (NavigatorRenderer.slotAt(px, 6)) {
            NavigatorRenderer.ARROW_PREV -> onStepDay(step(date, -1L))
            NavigatorRenderer.ARROW_NEXT -> onStepDay(step(date, 1L))
            0, 1 -> select("day", date) { CalendarNavigator.toDayPage(fragment, date) }
            2 -> select("week", date) { CalendarNavigator.toWeekPage(fragment, date, locale) }
            3 -> select("month", date) { CalendarNavigator.toMonthPage(fragment, date) }
            4 -> select("quarter", date) { CalendarNavigator.toQuarterPage(fragment, date) }
            5 -> select("year", date) { CalendarNavigator.toYearPage(fragment, date) }
        }
        return true
    }

    /** A period tap: filter in place when the surface filters (moving the focal slot with it),
     *  else the day page's behavior — jump into the calendar. */
    private fun select(g: String, date: LocalDate, navigate: () -> Unit) {
        val filter = onSelectPeriod
        if (filter != null) {
            granularity = g
            redraw()
            filter(g, date)
        } else {
            navigate()
        }
    }
}
