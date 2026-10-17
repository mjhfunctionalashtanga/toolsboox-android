package com.toolsboox.plugin.calendar.ui

import android.graphics.Matrix
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.databinding.FragmentCalendarBinding
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.databinding.ToolbarDrawingBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarYear
import com.toolsboox.plugin.calendar.ot.CalendarUtils
import com.toolsboox.plugin.calendar.ot.CalendarYearNavigator
import com.toolsboox.plugin.calendar.ot.CalendarYearPage
import com.toolsboox.plugin.calendar.ot.CalendarYearPageNotes
import com.toolsboox.ui.plugin.SurfaceFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

/**
 * Calendar year view fragment.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@AndroidEntryPoint
class CalendarYearFragment @Inject constructor() : SurfaceFragment() {

    /**
     * The Firebase analytics.
     */
    @Inject
    lateinit var firebaseAnalytics: FirebaseAnalytics

    /**
     * The presenter of the fragment.
     */
    @Inject
    lateinit var presenter: CalendarYearPresenter

    // The Google Drive sync presenter.
    @Inject
    lateinit var syncPresenter: CalendarGoogleDriveSyncPresenter

    /**
     * The calendar utils.
     */
    @Inject
    lateinit var utils: CalendarUtils

    /**
     * The inflated layout.
     */
    override val view = R.layout.fragment_calendar

    /**
     * The view binding.
     */
    private lateinit var binding: FragmentCalendarBinding

    /**
     * The current date.
     */
    private var currentDate: LocalDate = LocalDate.now()

    /**
     * Style of calendar view.
     */
    private var calendarStyle: String? = null

    /**
     * Page of notes view.
     */
    private var notePage: String? = null

    /**
     * The current locale.
     */
    private var locale: Locale = Locale.getDefault()

    /**
     * The timer job.
     */
    private lateinit var timer: Job

    /**
     * The data class.
     */
    private lateinit var calendarYear: CalendarYear

    /**
     * The pattern data class.
     */
    private lateinit var calendarPattern: CalendarPattern

    /**
     * SurfaceView provide method.
     *
     * @return the actual surfaceView
     */
    override fun provideSurfaceView(): SurfaceView = binding.surfaceView

    /**
     * Provide toolbar of drawing's bindings.
     *
     * @return the actual bindings of toolbar of drawings
     */
    override fun provideToolbarDrawing(): ToolbarDrawingBinding = binding.toolbarDrawing

    /**
     * Stroke changed callback.
     *
     * @param strokes the actual strokes
     */
    override fun onStrokeChanged(strokes: MutableList<Stroke>) {
        // A walk STATION never stores ink — the raw pen path arrives here regardless of the
        // touch router, so the refusal has to stand at the save (the -29 rule, every period).
        if (isStation(notePage)) return
        val normalizedStrokes = Stroke.listDeepCopy(strokes)
        if (notePage != null) {
            calendarYear.noteStrokes[notePage!!] = normalizedStrokes
        } else {
            calendarYear.calendarStrokes[calendarStyle ?: CalendarYear.DEFAULT_STYLE] = normalizedStrokes
        }

        calendarPattern.updateYear(calendarYear)

        presenter.save(this, binding, calendarYear, calendarPattern, currentDate)
    }

    /**
     * On side switched event.
     */
    override fun onSideSwitched() {
        utils.updateToolbar(binding, true)

        if (notePage != null)
            CalendarNavigator.toYearNote(this, currentDate, notePage!!)
        else
            CalendarNavigator.toYearPage(this, currentDate, CalendarYear.DEFAULT_STYLE)
    }

    /**
     * OnViewCreated hook.
     *
     * @param view the parent view
     * @param savedInstanceState the saved instance state
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentCalendarBinding.bind(view)

        val savedLocaleLanguageTag = sharedPreferences.getString("calendarLocale", Locale.getDefault().toLanguageTag())
        if (savedLocaleLanguageTag != null) {
            if (Locale.forLanguageTag(savedLocaleLanguageTag).toLanguageTag() == savedLocaleLanguageTag) {
                locale = Locale.forLanguageTag(savedLocaleLanguageTag)
                Timber.i("Locale switched to: ${locale.toLanguageTag()}")
            }
        }

        currentDate = LocalDate.ofYearDay(LocalDate.now().year, 1)
        arguments?.getString("year")?.toIntOrNull()?.let {
            Timber.i("Set year to '$it' from parameter")
            currentDate = LocalDate.ofYearDay(it, 1)
        }
        calendarStyle = arguments?.getString("calendarStyle") ?: CalendarYear.DEFAULT_STYLE
        notePage = arguments?.getString("notePage")

        calendarYear = CalendarYear(currentDate.year, locale)

        binding.navigatorImageView.setOnTouchListener { view, motionEvent ->
            CalendarYearNavigator.onTouchEvent(view, motionEvent, this@CalendarYearFragment, calendarYear)
        }

        binding.surfaceView.setOnHoverListener { _, motionEvent ->
            return@setOnHoverListener callback(motionEvent, true)
        }
        binding.surfaceView.setOnTouchListener { view, motionEvent ->
            if (callback(motionEvent, false)) return@setOnTouchListener true
            if (handleZoomPanTouch(motionEvent)) return@setOnTouchListener true

            val rawGesture = gestureListener.onTouchEvent(gestureDetector, view, motionEvent)
            val gestureResult = if (twoFingerGesture) rawGesture else OnGestureListener.NONE

            if (isStation(notePage)) {
                // A station is read-only: gestures walk, the pen writes nothing.
                if (motionEvent.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) {
                    when (gestureResult) {
                        com.toolsboox.ot.OnGestureListener.UTD -> binding.toolbarDrawing.toolbarSwipeUp.performClick()
                        com.toolsboox.ot.OnGestureListener.DTU -> binding.toolbarDrawing.toolbarSwipeDown.performClick()
                    }
                }
                true
            } else if (notePage != null) {
                // Page 0's up-swipe backs onto the walk (All Picks) — the walk reverses the way
                // it came, every granularity the same (-29's rule).
                if (notePage!!.toIntOrNull() == 0 && gestureResult == com.toolsboox.ot.OnGestureListener.UTD &&
                    motionEvent.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) {
                    CalendarNavigator.toYearNote(this, currentDate, STATION_PICKS)
                    true
                } else CalendarYearPageNotes.onTouchEvent(
                    view, motionEvent, gestureResult, this@CalendarYearFragment, calendarYear, notePage!!
                )
            } else {
                CalendarYearPage.onTouchEvent(
                    view, motionEvent, gestureResult, this@CalendarYearFragment, calendarYear
                )
            }
        }

        toolbar.toolbarPager.visibility = View.GONE

        // THE YEAR WALK (DESIGN-period-walk-2026-08-27 §1): face → ALL STARS → ALL PICKS →
        // the period's note — the daily ritual walk at this granularity, stations as read-only
        // pointers over the period's day files.
        binding.toolbarDrawing.toolbarSwipeUp.setOnClickListener {
            when {
                isStation(notePage) -> {
                    val base = notePage!!.substringBefore('#')
                    val idx = notePage!!.substringAfter('#', "0").toIntOrNull() ?: 0
                    when {
                        // A paged station steps back through its own pages before leaving.
                        idx > 0 -> CalendarNavigator.toYearNote(this, currentDate, "$base#${idx - 1}")
                        base == STATION_PICKS -> CalendarNavigator.toYearNote(this, currentDate, STATION_STARS)
                        else -> CalendarNavigator.toYearPage(this, currentDate, CalendarYear.DEFAULT_STYLE)
                    }
                }
                notePage != null -> {
                    val page = notePage!!.toIntOrNull() ?: 0
                    if (page == 0) CalendarNavigator.toYearNote(this, currentDate, STATION_PICKS)
                    else CalendarNavigator.toYearNote(this, currentDate, "${page - 1}")
                }
                else -> Unit
            }
        }
        binding.toolbarDrawing.toolbarSwipeDown.setOnClickListener {
            when {
                isStation(notePage) -> {
                    val base = notePage!!.substringBefore('#')
                    val idx = notePage!!.substringAfter('#', "0").toIntOrNull() ?: 0
                    when {
                        // More pages in this station (his ruling: size to content, never overlap)?
                        idx + 1 < stationPageCount -> CalendarNavigator.toYearNote(this, currentDate, "$base#${idx + 1}")
                        base == STATION_STARS -> CalendarNavigator.toYearNote(this, currentDate, STATION_PICKS)
                        else -> CalendarNavigator.toYearNote(this, currentDate, "0")
                    }
                }
                notePage != null -> {
                    val page = notePage!!.toIntOrNull() ?: 0
                    CalendarNavigator.toYearNote(this, currentDate, "${page + 1}")
                }
                else -> CalendarNavigator.toYearNote(this, currentDate, STATION_STARS)
            }
        }
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener {
            // Jump back to today's day page from the year view.
            CalendarNavigator.toDayPage(this, LocalDate.now())
        }

        // The rail, as on the day and Weeks pages: the upstream toolbar's slot carries both the
        // ink tools and the nav pill's unpacked actions, and the pill itself never comes up.
        setupAlmanacRail(
            surfaceKey = "year",
            isAtPresent = { currentDate.year == LocalDate.now().year },
            extraTools = if (notePage?.toIntOrNull() != null) listOf(
                GoItem("▤", "Page template…") { showPeriodTemplatePicker() }
            ) else emptyList(),
            // The bottom pill's corner offers the pen tools on every page that takes ink, the
            // period's face too (parity with iOS, review 2026-10-11) — never on a walk station,
            // which stores none: there the corner is ⛶ only.
            takesInk = !isStation(notePage)
        ) { CalendarNavigator.toYearPage(this, LocalDate.now(), CalendarYear.DEFAULT_STYLE) }

        utils.updateToolbar(binding)
        initializeSurface(true)
    }

    /**
     * OnResume hook.
     */
    override fun onResume() {
        super.onResume()

        binding.templateImageView.setImageBitmap(templateBitmap)
        binding.navigatorImageView.setImageBitmap(navigatorBitmap)
        updateNavigator(true)

        timer = GlobalScope.launch(Dispatchers.Main) {
            presenter.load(this@CalendarYearFragment, binding, currentDate, locale)
            syncPresenter.backgroundSync(this@CalendarYearFragment, UUID.randomUUID())
        }
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()

        toolbar.toolbarPager.visibility = View.GONE
        timer.cancel()
    }

    /**
     * Sync template ImageView transform when zoom/pan changes.
     */
    override fun onTransformChanged(matrix: Matrix) {
        if (::binding.isInitialized) {
            binding.templateImageView.scaleType = ImageView.ScaleType.MATRIX
            // Identity while the template drawn again at the zoomed resolution is up (16× with no
            // loss of clarity, Michael 2026-10-12) — it already carries the matrix.
            binding.templateImageView.imageMatrix = templateMatrixFor(matrix)
        }
    }

    /** Where the template at the zoomed resolution shows (SurfaceFragment.ensureSharpTemplate). */
    override fun templateView(): ImageView? = if (::binding.isInitialized) binding.templateImageView else null

    /**
     * Reload the current page.
     *
     * @param calendarYear the data class
     * @param calendarPattern the pattern data class
     */
    fun renderPage(calendarYear: CalendarYear, calendarPattern: CalendarPattern) {
        this.calendarYear = calendarYear
        this.calendarPattern = calendarPattern
        updateNavigator()

        if (isStation(notePage)) {
            renderStation(notePage!!)
            return
        }
        if (notePage != null) {
            // ▤ PER PAGE (DESIGN-period-walk-2026-08-27 §3): explicit sheet wins; else the legacy
            // global pref keeps its two meanings so pre-existing pages look unchanged.
            val page = notePage!!.toIntOrNull() ?: 0
            val tplStore = com.toolsboox.plugin.calendar.ot.NotePageTemplates
            val tpl = tplStore.templateFor(requireContext(), periodAnchor(), "year#$page")
                ?: if (sharedPreferences.getInt("calendarNoteTemplate", 0) == 1) tplStore.GRID
                else tplStore.LINES
            val noteStrokes = calendarYear.noteStrokes[notePage] ?: listOf()
            drawTemplate { c -> com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.drawPeriodNotePage(c, tpl, page) }
            applyStrokes(Stroke.listDeepCopy(noteStrokes), true)
        } else {
            val calendarStrokes = calendarYear.calendarStrokes[calendarStyle ?: CalendarYear.DEFAULT_STYLE] ?: listOf()
            drawTemplate { c -> CalendarYearPage.drawPage(this.requireContext(), c, calendarYear, calendarPattern) }
            applyStrokes(Stroke.listDeepCopy(calendarStrokes), true)
        }
    }

    /**
     * Update navigator bar.
     *
     * @param first flag of first start
     */
    private fun updateNavigator(first: Boolean = false) {
        if (first) return

        val year = currentDate.year

        val pageTitle = getString(R.string.calendar_year_title).format(year)
        toolbar.root.title = getString(R.string.calendar_main_title, pageTitle)

        CalendarYearNavigator.draw(this.requireContext(), navigatorCanvas, calendarYear, calendarPattern)

        firebaseAnalytics.logEvent("calendarYear") {
            param("currentDate", currentDate.format(DateTimeFormatter.ISO_DATE))
        }
    }

    /**
     * Show the progress bar.
     */
    override fun showLoading() {
        binding.mainProgress.visibility = View.VISIBLE
    }

    /**
     * Hide the progress bar.
     */
    override fun hideLoading() {
        binding.mainProgress.visibility = View.INVISIBLE
    }

    /** The year's one identity for per-page templates — its first day. */
    private fun periodAnchor(): java.time.LocalDate = java.time.LocalDate.of(currentDate.year, 1, 1)

    /** The ▤ picker for a period note page — plain papers only (lines · grid · dots);
     *  pickings waits for the boards-are-paper arc. Reached from the rail wrench's Tools. */
    private fun showPeriodTemplatePicker() {
        val ctx = context ?: return
        val page = notePage?.toIntOrNull() ?: return
        val tplStore = com.toolsboox.plugin.calendar.ot.NotePageTemplates
        val key = "year#$page"
        val current = tplStore.templateFor(ctx, periodAnchor(), key)
            ?: if (sharedPreferences.getInt("calendarNoteTemplate", 0) == 1) tplStore.GRID
            else tplStore.LINES
        val ids = listOf(tplStore.LINES, tplStore.GRID, tplStore.DOTS)
        val labels = ids.map {
            tplStore.glyph(it) + "  " + tplStore.label(it) + if (it == current) "  ·  here" else ""
        }.toTypedArray()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Template")
            .setItems(labels) { _, which ->
                if (ids[which] != current) {
                    tplStore.set(ctx, periodAnchor(), key, ids[which])
                    CalendarNavigator.toYearNote(this, currentDate, notePage!!)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    /** How many pages the station under the pen currently holds — set by [renderStation] on
     *  arrival, read by the skipper to know whether "down" means the next page or the next
     *  station. One is always safe: a stale count only shortens the in-station walk. */
    private var stationPageCount = 1

    /**
     * Draw a walk station: aggregate the year's day files (IO, on demand — never at list time)
     * and paint the scoped register. Read-only; the touch router and onStrokeChanged both refuse
     * ink. Only files that EXIST are loaded, newest first, capped at 62 files / 120 grams — a
     * year is 365 candidates and parsing them all would repeat the 35-second inventory mistake;
     * the station can't show more than the cap anyway. Per-day tombstones honoured.
     */
    private fun renderStation(station: String) {
        val ctx = requireContext()
        val label = "${currentDate.year}"
        val dates: List<java.time.LocalDate> = run {
                val first = java.time.LocalDate.of(currentDate.year, 1, 1)
                (0L until first.plusYears(1).toEpochDay() - first.toEpochDay()).map { off -> first.plusDays(off) }
            }
        GlobalScope.launch(Dispatchers.Main) {
            val grams: List<com.toolsboox.da.ImageElement> = kotlinx.coroutines.withContext(Dispatchers.IO) {
                // The one factory outside Hilt (a bare CalendarDayService() once read every day as
                // "corrupt" here, -322) and the one read-only gather all four stations share.
                val svc = com.toolsboox.plugin.calendar.fi.CalendarDayService.standalone(ctx)
                val root = com.toolsboox.ot.LedgerPaths.documentsRoot(ctx)
                val existing = dates.sortedDescending().filter { d ->
                    java.io.File(root, "calendar/${d.year}/${"%02d".format(d.monthValue)}/day-$d-v2.json").exists()
                }.take(62)
                com.toolsboox.plugin.calendar.ot.StationGrams.gather(svc, root, existing, locale, cap = 120)
            }
            if (!isAdded) return@launch
            // KIND-PAGED (Michael, mid-build 08-27): the station sizes to its CONTENT — a filter
            // page per kind for the stars (the read / the watch / the listen / the mail), capacity
            // chunks for both stations — and the skipper steps through the pages. Overlap never.
            val base = station.substringBefore('#')
            val idx = station.substringAfter('#', "0").toIntOrNull() ?: 0
            val pages = if (base == STATION_STARS)
                com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.starsStationPages(
                    grams.filter { el -> el.page == "intake" }, label)
            else {
                val picksKey = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
                com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.picksStationPages(
                    grams.filter { el -> el.page == picksKey || el.page.startsWith("$picksKey#") }, label)
            }
            stationPageCount = pages.size
            val (header, chunk) = pages[idx.coerceIn(0, pages.size - 1)]
            drawTemplate(decodesPictures = true) { c -> com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.drawStationGrid(c, chunk, header, ctx) }
            applyStrokes(listOf(), true)
        }
    }

    companion object {
        /** The walk stations' notePage keys — navigation addresses only, NEVER storage keys.
         *  A paged station rides "#<n>" suffixes on the same addresses. */
        const val STATION_STARS = "allstars"
        const val STATION_PICKS = "allpicks"
        fun isStation(p: String?): Boolean =
            p != null && (p.substringBefore('#') == STATION_STARS || p.substringBefore('#') == STATION_PICKS)
    }
}
