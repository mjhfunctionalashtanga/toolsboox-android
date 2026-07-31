package com.toolsboox.plugin.calendar.ui

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.databinding.FragmentCalendarBinding
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v1.ReadingProgress
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.fi.CalendarEventsService
import com.toolsboox.plugin.calendar.fi.CalendarPatternService
import com.toolsboox.plugin.calendar.ot.LedgerTaskCarryOver
import com.toolsboox.ui.plugin.FragmentPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import javax.inject.Inject

/**
 * Calendar day presenter.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayPresenter @Inject constructor() : FragmentPresenter() {

    /**
     * The calendar day service.
     */
    @Inject
    lateinit var calendarDayService: CalendarDayService

    /**
     * The calendar pattern service.
     */
    @Inject
    lateinit var calendarPatternService: CalendarPatternService

    /**
     * The calendar events service.
     */
    @Inject
    lateinit var calendarEventsService: CalendarEventsService

    /**
     * Load the daily calendar data.
     *
     * @param fragment the fragment
     * @param binding the data binding
     * @param currentDate the current date
     * @param defaultStartHour the default start hour
     * @param locale the default locale
     */
    fun load(
        fragment: CalendarDayFragment, binding: FragmentCalendarBinding,
        currentDate: LocalDate, defaultStartHour: Int, locale: Locale
    ) {
        if (!checkPermissions(fragment, binding.root)) return

        if (!fragment.checkPermission(Manifest.permission.READ_CALENDAR)) {
            fragment.showError(null, R.string.main_read_calendar_permission_missing, binding.root)
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.showLoading() } }

                try {
                    val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)

                    // The service treats an unreadable day file (OOM-large, corrupt beyond the
                    // blank-file fallback) as unwritten so the page still renders — but a save over
                    // that blank render would discard the file's real content. When a file EXISTS
                    // and still wouldn't load, mark the day read-only: render the blank, say so,
                    // and refuse every save until a load succeeds. The flag is set on every load,
                    // so navigating to another day (or a repaired file) clears it.
                    val loadedCalendarDay = calendarDayService.loadOrNull(rootPath, currentDate, defaultStartHour, locale)
                    val dayReadOnly = loadedCalendarDay == null && calendarDayService.exists(rootPath, currentDate)
                    val calendarDay = loadedCalendarDay ?: CalendarDay(
                        currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
                        mutableListOf(), mutableListOf(), true, defaultStartHour
                    )
                    val calendarPattern = calendarPatternService.load(rootPath, currentDate, locale)
                    var calendarEvents = calendarEventsService.loadEvents(fragment, currentDate)
                    // The setting is authoritative when it names an hour; see CalendarDayService.
                    calendarDay.startHour =
                        if (defaultStartHour >= 0) defaultStartHour else calendarDay.startHour ?: defaultStartHour

                    // The measuring context for the Tasks rows: the page face has to be the one the
                    // surface actually draws with, or a row's height is measured against the wrong
                    // metrics and the packing is off by a line.
                    val measureCtx = fragment.context?.applicationContext

                    var dayDirty = false
                    // Carry-over would prune yesterday's tasks after copying them into a blank
                    // today that will never be saved — losing them from both days — so a
                    // read-only day skips it (and the reflow rewrite) entirely.
                    if (currentDate.isEqual(LocalDate.now()) && !dayReadOnly) {
                        val yesterday = currentDate.minusDays(1)
                        val yesterdayCalendarDay = calendarDayService.load(rootPath, yesterday, defaultStartHour, locale)

                        if (LedgerTaskCarryOver.carryOver(yesterdayCalendarDay, calendarDay, measureCtx)) {
                            CalendarPatternService.mutex.withLock {
                                val yesterdayPattern = calendarPatternService.load(rootPath, yesterday, locale)
                                yesterdayPattern.updateDay(yesterdayCalendarDay)
                                calendarDayService.save(rootPath, yesterday, yesterdayCalendarDay)
                                calendarPatternService.save(rootPath, yesterday, yesterdayPattern)
                            }
                            dayDirty = true
                        }
                    }

                    // Repair pass: days written by the old fixed-pitch placement hold task boxes
                    // that draw straight through the row below them. Re-laying them on measured
                    // heights is idempotent, so a day is rewritten once and then goes quiet.
                    if (!dayReadOnly && LedgerTaskCarryOver.reflow(measureCtx, calendarDay)) dayDirty = true

                    if (dayDirty) {
                        CalendarPatternService.mutex.withLock {
                            calendarPattern.updateDay(calendarDay)
                            calendarDayService.save(rootPath, currentDate, calendarDay)
                            calendarPatternService.save(rootPath, currentDate, calendarPattern)
                        }
                    }

                    if (currentDate >= LocalDate.now()) {
                        calendarDay.readingProgress.clear()
                    }
                    if (calendarDay.readingProgress.isEmpty()) {
                        calendarDay.readingProgress.addAll(readingProgress(fragment, currentDate))
                    }

                    if (currentDate < LocalDate.now()) {
                        if (calendarDay.events.isEmpty()) {
                            calendarDay.events.addAll(calendarEvents)
                        } else {
                            calendarEvents = calendarDay.events
                        }
                    } else {
                        // The refill rebuilds today from the device calendar — but "journal-"
                        // events are written by the iPhone's From-your-day picker straight into
                        // the day file (no device-calendar twin), so they must ride through or
                        // a same-day Boox render (and its save) would silently drop them.
                        val journalEvents = calendarDay.events.filter { it.id.startsWith("journal-") }
                        calendarDay.events.clear()
                        calendarDay.events.addAll(calendarEvents)
                        calendarDay.events.addAll(journalEvents)
                    }

                    withContext(Dispatchers.Main) {
                        fragment.dayReadOnly = dayReadOnly
                        if (dayReadOnly) {
                            fragment.runOnActivity {
                                fragment.showMessage("Couldn't read this day — showing blank, not saving.", binding.root)
                            }
                        }
                        fragment.renderPage(calendarDay, calendarPattern, calendarEvents)
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) { fragment.somethingHappened(e) }
                }
            } finally {
                withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.hideLoading() } }
            }
        }
    }

    /**
     * Save the day to the storage.
     *
     * @param fragment the fragment
     * @param binding the data binding
     * @param calendarDay the data class
     * @param calendarPattern the pattern data class
     * @param currentDate the current date
     */
    fun save(
        fragment: CalendarDayFragment, binding: FragmentCalendarBinding,
        calendarDay: CalendarDay, calendarPattern: CalendarPattern, currentDate: LocalDate,
        showProgress: Boolean = true
    ) {
        if (!checkPermissions(fragment, binding.root)) return

        GlobalScope.launch(Dispatchers.IO) {
            // Every save of the day file funnels through here, which makes this the one place
            // the read-only guard has to hold: a day that rendered blank because its file
            // wouldn't load must never be written over — the on-disk bytes are the only copy
            // of its real content. The flag is read on Main, where load sets it. Per-stroke
            // saves are refused silently (a snackbar per pen-up would flash the e-ink);
            // explicit actions get the message again.
            val dayReadOnly = withContext(Dispatchers.Main) { fragment.dayReadOnly }
            if (dayReadOnly) {
                Timber.w("Refusing save for $currentDate: day file exists but wouldn't load")
                if (showProgress) withContext(Dispatchers.Main) {
                    fragment.runOnActivity {
                        fragment.showMessage("Couldn't read this day — showing blank, not saving.", binding.root)
                    }
                }
                return@launch
            }
            try {
                // Per-stroke saves pass showProgress=false: flashing mainProgress VISIBLE/
                // INVISIBLE on every pen-up forces an e-ink refresh + relayout, which on the
                // Go 6 Gen 2 reads as a lag/freeze right when the pen lifts. The write itself
                // is already off the main thread, so the indicator adds nothing here.
                if (showProgress) withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.showLoading() } }

                try {
                    val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)

                    val emptyStrokes = calendarDay.calendarStrokes[CalendarDay.DEFAULT_STYLE]?.isEmpty() ?: true
                    calendarDay.hasLanes = calendarDay.hasLanes or emptyStrokes

                    CalendarPatternService.mutex.withLock {
                        // Under the day lock so a background placement's load→mutate→save
                        // (PickingsPlacement, starred mail) can't interleave with this write.
                        com.toolsboox.plugin.calendar.ot.DayLocks.withDay(currentDate) {
                            calendarDayService.save(rootPath, currentDate, calendarDay)
                        }
                        calendarPatternService.save(rootPath, currentDate, calendarPattern)
                    }
                    // (No widget poke here — CalendarDayService.save notifies the widgets itself
                    // now, guarded to today's file, so every save path refreshes them, not just
                    // this one. A second broadcast from here would just double the e-ink churn.)
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) { fragment.somethingHappened(e) }
                }
            } finally {
                if (showProgress) withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.hideLoading() } }
            }
        }
    }

    /**
     * Procrastinate the strokes to the next day.
     *
     * @param fragment the fragment
     * @param binding the data binding
     * @param strokes the strokes
     * @param currentDate the current date
     * @param fromCalendarDay the calendar day of the strokes
     * @param fromCalendarStyle the calendar style of the strokes
     */
    fun procrastinate(
        fragment: CalendarDayFragment, binding: FragmentCalendarBinding, strokes: List<Stroke>,
        currentDate: LocalDate, fromCalendarDay: CalendarDay, fromCalendarStyle: String
    ) {
        if (!checkPermissions(fragment, binding.root)) return

        val nextDate = currentDate.plusDays(1)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.showLoading() } }

                try {
                    CalendarPatternService.mutex.withLock {
                        val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)
                        val calendarDay = calendarDayService.load(rootPath, nextDate, fromCalendarDay.startHour, fromCalendarDay.locale)
                        val calendarPattern = calendarPatternService.load(rootPath, nextDate, fromCalendarDay.locale)

                        val existsStrokes = calendarDay.calendarStrokes[fromCalendarStyle]?.toMutableList() ?: mutableListOf()
                        existsStrokes.addAll(strokes)
                        calendarDay.calendarStrokes[fromCalendarStyle] = existsStrokes.toList()

                        calendarPattern.updateDay(calendarDay)
                        calendarDayService.save(rootPath, nextDate, calendarDay)
                        calendarPatternService.save(rootPath, nextDate, calendarPattern)
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) { fragment.somethingHappened(e) }
                }
            } finally {
                withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.hideLoading() } }
            }
        }
    }

    /**
     * Provides the authors, title, progress and lastAccess fields of reading progress of current day.
     *
     * @param fragment the fragment
     * @param currentDate the current date
     * @return the list of reading progress
     */
    private fun readingProgress(fragment: CalendarDayFragment, currentDate: LocalDate): List<ReadingProgress> {
        val result = mutableListOf<ReadingProgress>()

        val startEpoch = currentDate.atStartOfDay().atZone(ZoneId.systemDefault()).toEpochSecond() * 1000L
        val endEpoch = startEpoch + 24 * 60 * 60 * 1000L

        val resolver: ContentResolver = fragment.requireContext().contentResolver
        val uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
        val projection = arrayOf("authors", "title", "progress", "lastAccess")
        resolver.query(uri, projection, null, null, null)?.use {
            while (it.moveToNext()) {
                val authors = it.getString(0)
                val title = it.getString(1)
                val progress = it.getString(2)
                val lastAccess = it.getLong(3)

                if (title == null) continue
                if (lastAccess < startEpoch) continue
                if (lastAccess >= endEpoch) continue
                result.add(ReadingProgress(authors, title, progress, Date(lastAccess)))
            }
        }

        Timber.i("Reading progress: $result")
        return result
    }
}
