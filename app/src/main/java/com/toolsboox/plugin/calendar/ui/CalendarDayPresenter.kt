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
     * @param seedStartHour the start hour to stamp onto this day IF it does not exist yet. It is
     *   never applied to a day that loaded — see [CalendarDayService.loadOrNull].
     * @param locale the default locale
     */
    fun load(
        fragment: CalendarDayFragment, binding: FragmentCalendarBinding,
        currentDate: LocalDate, seedStartHour: Int, locale: Locale
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

                    // An unreadable day file (OOM-large, corrupt, a newer app's enum — the service's
                    // DayRead.Unreadable) renders blank so the page still opens — but a save over
                    // that blank render would discard the file's real content. When a file EXISTS
                    // and still wouldn't load, mark the day read-only: render the blank, say so,
                    // and refuse every save until a load succeeds. The flag is set on every load,
                    // so navigating to another day (or a repaired file) clears it. (Every other
                    // writer gets the same refusal from CalendarDayService.loadForWrite.)
                    // The same three-way read every writer refuses on — so a BLANK file (nothing in
                    // it to lose: DayRead.Missing) opens as a fresh, writable day here exactly as it
                    // does for them, instead of read-only on the page and writable everywhere else.
                    val dayRead = calendarDayService.read(rootPath, currentDate)
                    val loadedCalendarDay = (dayRead as? CalendarDayService.DayRead.Loaded)?.day
                    val dayReadOnly = dayRead is CalendarDayService.DayRead.Unreadable
                    // [seedStartHour] is spent only here, on a day that has no file yet.
                    // A loaded day keeps its own hour untouched — the re-stamp that used to sit
                    // below this line is what cost Michael 116 days of his chosen 7am; see
                    // CalendarDayService.loadOrNull for the full account.
                    val calendarDay = loadedCalendarDay ?: CalendarDay(
                        currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
                        mutableListOf(), mutableListOf(), true, seedStartHour
                    )
                    val calendarPattern = calendarPatternService.load(rootPath, currentDate, locale)
                    var calendarEvents = calendarEventsService.loadEvents(fragment, currentDate)

                    // Task carry-over retired here (Michael's ruling, 2026-08-10): opening today
                    // no longer copies yesterday's undone tasks into the file or stamps typed
                    // text boxes onto the page. A task stays on its own day; "still open" is
                    // drawn render-only by the page (OpenTasks + the ghost rows) and answered by
                    // the Boards/Tasks views. The reflow repair pass retired with it — nothing
                    // machine-places task boxes anymore, so there is nothing to repair.

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
                                fragment.showMessage(READ_ONLY_REFUSED, binding.root)
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
                        fragment.showMessage(READ_ONLY_REFUSED, binding.root)
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
                } catch (e: CalendarDayService.UnreadableDayException) {
                    // The service's own backstop: the file under this open page has stopped reading
                    // since the page loaded it (something else read it and found it unreadable). The
                    // page's copy is older than whatever is on disk now, so it goes read-only, the
                    // same as a day that opened unreadable — until the next load clears it.
                    withContext(Dispatchers.Main) {
                        fragment.dayReadOnly = true
                        fragment.runOnActivity {
                            fragment.showMessage(READ_ONLY_REFUSED, binding.root)
                        }
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) { fragment.somethingHappened(e) }
                }
            } finally {
                if (showProgress) withContext(Dispatchers.Main) { fragment.runOnActivity { fragment.hideLoading() } }
            }
        }
    }

    /**
     * [save], AWAITED: true once the day is on disk. For a write whose bookkeeping must follow the
     * bytes, not race them — the ❖ landing clears the summon's pending record only after this
     * (2026-10-12 review: a kill between the fire-and-forget [save] and the record's removal lost
     * the card AND marked the ink as read, with nothing left to resume). Same guards, same locks.
     * Call on Main: the read-only flag and the root are taken before the write leaves it, so a page
     * turn mid-write (a detached fragment) can't strand the card. THROWS
     * [CalendarDayService.UnreadableDayException] when the day file has stopped reading under the
     * page (the page is marked read-only first) — the caller says which page won't read.
     */
    suspend fun saveNow(
        fragment: CalendarDayFragment, calendarDay: CalendarDay, calendarPattern: CalendarPattern,
        currentDate: LocalDate,
    ): Boolean {
        if (fragment.dayReadOnly) {
            Timber.w("Refusing save for $currentDate: day file exists but wouldn't load")
            return false
        }
        val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)
        return withContext(Dispatchers.IO) {
            try {
                val emptyStrokes = calendarDay.calendarStrokes[CalendarDay.DEFAULT_STYLE]?.isEmpty() ?: true
                calendarDay.hasLanes = calendarDay.hasLanes or emptyStrokes
                CalendarPatternService.mutex.withLock {
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(currentDate) {
                        calendarDayService.save(rootPath, currentDate, calendarDay)
                    }
                    calendarPatternService.save(rootPath, currentDate, calendarPattern)
                }
                true
            } catch (e: CalendarDayService.UnreadableDayException) {
                // The service's own refusal (day-file guard): the file under this open page has
                // stopped reading since the page loaded it. Not a plain "didn't save" — the page goes
                // read-only, as [save] makes it, and the refusal goes UP: a ❖ landing that awaited
                // this says the reply is kept (SynthSummons.land), never that it was written back
                // (2026-10-16 review, item 3).
                Timber.w(e, "saveNow refused for $currentDate: the day file won't read")
                withContext(Dispatchers.Main) { fragment.dayReadOnly = true }
                throw e
            } catch (e: Exception) {
                // IOException, or a pen-up mutating the day mid-serialise: not on disk — say false,
                // the caller keeps its record, and the next per-stroke save carries the card anyway.
                Timber.w(e, "saveNow failed for $currentDate")
                false
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
                        com.toolsboox.plugin.calendar.ot.DayLocks.withDay(nextDate) {
                            // A next day that won't read refuses (UnreadableDayException is an
                            // IOException → somethingHappened below), never a fresh day saved over it.
                            val calendarDay = calendarDayService.loadForWriteOrThrow(rootPath, nextDate, fromCalendarDay.startHour, fromCalendarDay.locale)
                            val calendarPattern = calendarPatternService.load(rootPath, nextDate, fromCalendarDay.locale)

                            val existsStrokes = calendarDay.calendarStrokes[fromCalendarStyle]?.toMutableList() ?: mutableListOf()
                            existsStrokes.addAll(strokes)
                            calendarDay.calendarStrokes[fromCalendarStyle] = existsStrokes.toList()

                            calendarPattern.updateDay(calendarDay)
                            calendarDayService.save(rootPath, nextDate, calendarDay)
                            calendarPatternService.save(rootPath, nextDate, calendarPattern)
                        }
                    }
                } catch (e: CalendarDayService.UnreadableDayException) {
                    // Not the generic "it'll retry": this one won't, until that page reads again.
                    // The eraser has already lifted the ink off this page, so it goes back.
                    withContext(Dispatchers.Main) {
                        fragment.runOnActivity {
                            fragment.restoreProcrastinated(currentDate, fromCalendarStyle, strokes)
                            fragment.showMessage(
                                "${e.whichPage.replaceFirstChar { it.uppercase() }} won't read, so the ink stays here",
                                binding.root
                            )
                        }
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

    companion object {
        /** What a read-only day page (its file wouldn't read) says when a save is refused. */
        const val READ_ONLY_REFUSED = "Couldn't read this day — showing blank, not saving."
    }
}
