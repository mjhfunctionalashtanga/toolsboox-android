package com.toolsboox.plugin.calendar

import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.plugin.calendar.da.v2.*
import com.toolsboox.ui.plugin.ScreenFragment
import timber.log.Timber
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.*

/**
 * Navigator methods of calendar plugin.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
object CalendarNavigator {

    /**
     * One-shot: a design-space (1404×1872) zone rect to zoom/center on the day page's FIRST render
     * after navigation — the "tag → land on its mark" jump. Set by [toDayNote] (null clears it, so
     * an ordinary navigation never inherits a stale focus) and consumed once by the day fragment.
     */
    var pendingFocusRect: android.graphics.RectF? = null

    /**
     * Navigate to the settings of calendar.
     *
     * @param fragment the fragment
     */
    fun toSettings(fragment: ScreenFragment) {
        val bundle = bundleOf()

        Timber.i("Navigate to the calendar settings")
        fragment.findNavController().navigate(R.id.action_to_calendar_settings, bundle)
    }

    /**
     * Navigate to the cloud sync of calendar.
     *
     * @param fragment the fragment
     */
    fun toCloudSync(fragment: ScreenFragment) {
        val bundle = bundleOf()

        Timber.i("Navigate to the calendar cloud sync")
//        fragment.findNavController().navigate(R.id.action_to_calendar_cloud_sync, bundle)
        fragment.findNavController().navigate(R.id.action_to_calendar_google_drive_sync, bundle)
    }

    /**
     * Remember where the free-form Notes surface is open (numeric pages only — Intake,
     * Gratitude etc. are their own destinations, not "Notes").
     */
    fun rememberNoteLocation(context: android.content.Context, date: LocalDate, notePage: String) {
        if (notePage.toIntOrNull() == null) return
        context.getSharedPreferences("ledger_notes", 0).edit()
            .putString("last_note_date", date.toString())
            .putString("last_note_page", notePage)
            .apply()
    }

    /**
     * Open TODAY's notes, resuming the page you were on if you were on it today.
     *
     * This used to resume the remembered DATE as well, with no bound on how old it was — so once a
     * note had been written on the 21st, every later tap on "Notes" opened the 21st, forever. That
     * is not a navigation annoyance, it is a data-loss bug: the page looks like a notes page, gives
     * no indication which day it belongs to, and so the next thing written lands on top of what is
     * already there. Michael reported it three ways without realising they were one thing — "I keep
     * getting sent back to the 21st when I click the quick jump button for Notes", "it keeps
     * returning me to Friday's Notes", and, from the iPad, "the handwritten notes are layering on
     * one another, ignoring the date/days". They were, because the surface WAS ignoring the date.
     * The proof is in the ink: day-2026-07-26's grid page has its lines written over each other.
     *
     * Resuming a page number within the same day is genuinely useful, so that half stays. Resuming
     * a date across days is the half that overwrites your work, so it goes: these are numeric
     * per-day lined pages ([rememberNoteLocation] stores nothing else), and a per-day page's day is
     * today. A named Write/Synthesize document is the thing that legitimately spans days, and it has
     * its own key and its own directory.
     */
    fun toLastDayNote(fragment: ScreenFragment) {
        val p = fragment.requireContext().getSharedPreferences("ledger_notes", 0)
        val today = LocalDate.now()
        val remembered = runCatching { LocalDate.parse(p.getString("last_note_date", "") ?: "") }.getOrNull()
        val page = if (remembered == today) (p.getString("last_note_page", "0") ?: "0") else "0"
        toDayNote(fragment, today, page)
    }

    /**
     * Navigate to the daily calendar notes.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param notePage navigate to the note page
     * @param focusRect optional design-space zone rect to zoom/center on first render (tag → mark)
     */
    fun toDayNote(fragment: ScreenFragment, localDate: LocalDate, notePage: String, focusRect: android.graphics.RectF? = null) {
        pendingFocusRect = focusRect
        val year = localDate.year
        val month = localDate.monthValue
        val day = localDate.dayOfMonth

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("month", "$month")
        bundle.putString("day", "$day")
        bundle.putString("notePage", notePage)

        Timber.i("Navigate to the '$year-$month-$day' ($notePage) daily calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_day, bundle)
    }

    /**
     * Navigate to the daily calendar page.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param calendarStyle to the calendar style
     */
    fun toDayPage(
        fragment: ScreenFragment, localDate: LocalDate, calendarStyle: String = CalendarDay.DEFAULT_STYLE
    ) {
        val year = localDate.year
        val month = localDate.monthValue
        val day = localDate.dayOfMonth

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("month", "$month")
        bundle.putString("day", "$day")
        bundle.putString("calendarStyle", calendarStyle)

        Timber.i("Navigate to the '$year-$month-$day' ($calendarStyle) daily calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_day, bundle)
    }

    /**
     * Navigate to the monthly calendar notes.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param notePage navigate to the note page
     */
    fun toMonthNote(fragment: ScreenFragment, localDate: LocalDate, notePage: String) {
        val year = localDate.year
        val month = localDate.monthValue

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("month", "$month")
        bundle.putString("notePage", notePage)

        Timber.i("Navigate to the '$year-$month' ($notePage) monthly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_month, bundle)
    }

    /**
     * Navigate to the monthly calendar page.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param calendarStyle to the calendar style
     */
    fun toMonthPage(
        fragment: ScreenFragment, localDate: LocalDate, calendarStyle: String = CalendarMonth.DEFAULT_STYLE
    ) {
        val year = localDate.year
        val month = localDate.monthValue

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("month", "$month")
        bundle.putString("calendarStyle", calendarStyle)

        Timber.i("Navigate to the '$year-$month' ($calendarStyle) monthly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_month, bundle)
    }

    /**
     * Navigate to the quarterly calendar notes.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param notePage navigate to the note page
     */
    fun toQuarterNote(fragment: ScreenFragment, localDate: LocalDate, notePage: String) {
        val year = localDate.year
        val month = localDate.monthValue
        val quarter = (month - 1) / 3 + 1

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("quarter", "$quarter")
        bundle.putString("notePage", notePage)

        Timber.i("Navigate to the '$year-$quarter' ($notePage) quarterly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_quarter, bundle)
    }

    /**
     * Navigate to the quarterly calendar page.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param calendarStyle to the calendar style
     */
    fun toQuarterPage(
        fragment: ScreenFragment, localDate: LocalDate, calendarStyle: String = CalendarQuarter.DEFAULT_STYLE
    ) {
        val year = localDate.year
        val month = localDate.monthValue
        val quarter = (month - 1) / 3 + 1

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("quarter", "$quarter")
        bundle.putString("calendarStyle", calendarStyle)

        Timber.i("Navigate to the '$year-$quarter' ($calendarStyle) quarterly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_quarter, bundle)
    }

    /**
     * Navigate to the weekly calendar notes.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param notePage navigate to the note page
     */
    fun toWeekNote(fragment: ScreenFragment, localDate: LocalDate, locale: Locale, notePage: String) {
        val year = localDate.year
        val weekOfWeekBasedYear = WeekFields.of(locale).weekOfWeekBasedYear()
        val weekOfYear = localDate.plusWeeks(0L).get(weekOfWeekBasedYear)

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("weekOfYear", "$weekOfYear")
        bundle.putString("notePage", notePage)

        Timber.i("Navigate to the '$year-$weekOfYear' ($notePage) weekly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_week, bundle)
    }

    /**
     * Navigate to the weekly calendar page.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param calendarStyle to the calendar style
     */
    fun toWeekPage(
        fragment: ScreenFragment, localDate: LocalDate, locale: Locale, calendarStyle: String = CalendarWeek.DEFAULT_STYLE
    ) {
        val year = localDate.year
        val weekOfWeekBasedYear = WeekFields.of(locale).weekOfWeekBasedYear()
        val weekOfYear = localDate.plusWeeks(0L).get(weekOfWeekBasedYear)

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("weekOfYear", "$weekOfYear")
        bundle.putString("calendarStyle", calendarStyle)

        Timber.i("Navigate to the '$year-$weekOfYear' ($calendarStyle) weekly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_week, bundle)
    }

    /**
     * Navigate to the yearly calendar notes.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param notePage navigate to the note page
     */
    fun toYearNote(fragment: ScreenFragment, localDate: LocalDate, notePage: String) {
        val year = localDate.year

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("notePage", notePage)

        Timber.i("Navigate to the '$year' ($notePage) yearly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_year, bundle)
    }

    /**
     * Navigate to the yearly calendar page.
     *
     * @param fragment the fragment
     * @param localDate the local date
     * @param calendarStyle to the calendar style
     */
    fun toYearPage(
        fragment: ScreenFragment, localDate: LocalDate, calendarStyle: String = CalendarYear.DEFAULT_STYLE
    ) {
        val year = localDate.year

        val bundle = bundleOf()
        bundle.putString("year", "$year")
        bundle.putString("calendarStyle", calendarStyle)

        Timber.i("Navigate to the '$year' ($calendarStyle) yearly calendar")
        fragment.findNavController().navigate(R.id.action_to_calendar_year, bundle)
    }
}