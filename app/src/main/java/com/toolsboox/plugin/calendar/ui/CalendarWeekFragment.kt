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
import com.toolsboox.plugin.calendar.da.v2.CalendarMonth
import com.toolsboox.plugin.calendar.da.v2.CalendarWeek
import com.toolsboox.plugin.calendar.ot.CalendarUtils
import com.toolsboox.plugin.calendar.ot.CalendarWeekNavigator
import com.toolsboox.plugin.calendar.ot.CalendarWeekPage
import com.toolsboox.plugin.calendar.ot.CalendarWeekPageNotes
import com.toolsboox.ui.plugin.SurfaceFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.*
import javax.inject.Inject

/**
 * Calendar week view fragment.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@AndroidEntryPoint
class CalendarWeekFragment @Inject constructor() : SurfaceFragment() {

    /**
     * The Firebase analytics.
     */
    @Inject
    lateinit var firebaseAnalytics: FirebaseAnalytics

    /**
     * The presenter of the fragment.
     */
    @Inject
    lateinit var presenter: CalendarWeekPresenter

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
    private lateinit var calendarWeek: CalendarWeek

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
        // A walk STATION never stores ink — it is a pointer over the days, and the raw pen path
        // arrives here regardless of the touch router, so the refusal has to stand at the save.
        if (isStation(notePage)) return
        val normalizedStrokes = Stroke.listDeepCopy(strokes)
        if (notePage != null) {
            calendarWeek.noteStrokes[notePage!!] = normalizedStrokes
        } else {
            calendarWeek.calendarStrokes[calendarStyle ?: CalendarMonth.DEFAULT_STYLE] = normalizedStrokes
        }

        calendarPattern.updateWeek(calendarWeek)

        presenter.save(this, binding, calendarWeek, calendarPattern, currentDate)
    }

    /**
     * On side switched event.
     */
    override fun onSideSwitched() {
        utils.updateToolbar(binding, true)

        if (notePage != null)
            CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, notePage!!)
        else
            CalendarNavigator.toWeekPage(this, currentDate, calendarWeek.locale, CalendarWeek.DEFAULT_STYLE)
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

        currentDate = LocalDate.now()
        arguments?.getString("year")?.toIntOrNull()?.let { year ->
            Timber.i("Set year to '$year' from parameter")
            currentDate = LocalDate.of(year, 1, 1)
            arguments?.getString("weekOfYear")?.toIntOrNull()?.let { weekOfYear ->
                val weekFields = WeekFields.of(locale)
                Timber.i("Set year and week to '$year'/'$weekOfYear' from parameter")
                currentDate = LocalDate.ofYearDay(year, 1)
                    .with(weekFields.weekOfYear(), weekOfYear.toLong())
                    .with(weekFields.dayOfWeek(), 1)
            }
        }
        calendarStyle = arguments?.getString("calendarStyle") ?: CalendarWeek.DEFAULT_STYLE
        notePage = arguments?.getString("notePage")

        val weekOfWeekBasedYear = WeekFields.of(locale).weekOfWeekBasedYear()
        calendarWeek = CalendarWeek(currentDate.year, currentDate.get(weekOfWeekBasedYear), locale)

        binding.navigatorImageView.setOnTouchListener { view, motionEvent ->
            CalendarWeekNavigator.onTouchEvent(view, motionEvent, this@CalendarWeekFragment, calendarWeek)
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
                // A station is read-only: gestures walk, the pen writes nothing (the grams live
                // on their days — writing here would need a store the design rules out).
                if (motionEvent.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) {
                    when (gestureResult) {
                        com.toolsboox.ot.OnGestureListener.UTD -> binding.toolbarDrawing.toolbarSwipeUp.performClick()
                        com.toolsboox.ot.OnGestureListener.DTU -> binding.toolbarDrawing.toolbarSwipeDown.performClick()
                    }
                }
                true
            } else if (notePage != null) {
                // Page 0's up-swipe backs onto the walk (All Picks), not straight to the week
                // page — the walk must reverse the way it came.
                if (notePage!!.toIntOrNull() == 0 && gestureResult == com.toolsboox.ot.OnGestureListener.UTD &&
                    motionEvent.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER) {
                    CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, STATION_PICKS)
                    true
                } else CalendarWeekPageNotes.onTouchEvent(
                    view, motionEvent, gestureResult, this@CalendarWeekFragment, calendarWeek, notePage!!
                )
            } else {
                CalendarWeekPage.onTouchEvent(
                    view, motionEvent, gestureResult, this@CalendarWeekFragment, calendarWeek
                )
            }
        }

        toolbar.toolbarPager.visibility = View.GONE

        // THE WEEK WALK (DESIGN-period-walk-2026-08-27 §1): the skipper steps Week page →
        // All Stars · WEEK → All Picks · WEEK → the week's note — the daily ritual walk,
        // generalized to this granularity. The stations are read-only POINTERS over the week's
        // seven day files; grams still live on their days.
        binding.toolbarDrawing.toolbarSwipeUp.setOnClickListener {
            when {
                isStation(notePage) -> {
                    val base = notePage!!.substringBefore('#')
                    val idx = notePage!!.substringAfter('#', "0").toIntOrNull() ?: 0
                    when {
                        // A paged station steps back through its own pages before leaving.
                        idx > 0 -> CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, "$base#${idx - 1}")
                        base == STATION_PICKS -> CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, STATION_STARS)
                        else -> CalendarNavigator.toWeekPage(this, currentDate, calendarWeek.locale, CalendarWeek.DEFAULT_STYLE)
                    }
                }
                notePage != null -> {
                    val page = notePage!!.toIntOrNull() ?: 0
                    if (page == 0) {
                        CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, STATION_PICKS)
                    } else {
                        CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, "${page - 1}")
                    }
                }
                else -> CalendarNavigator.toMonthPage(this, currentDate)
            }
        }
        binding.toolbarDrawing.toolbarSwipeDown.setOnClickListener {
            when {
                isStation(notePage) -> {
                    val base = notePage!!.substringBefore('#')
                    val idx = notePage!!.substringAfter('#', "0").toIntOrNull() ?: 0
                    when {
                        // More pages in this station (his ruling: size to content, never overlap)?
                        idx + 1 < stationPageCount -> CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, "$base#${idx + 1}")
                        base == STATION_STARS -> CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, STATION_PICKS)
                        else -> CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, "0")
                    }
                }
                notePage != null -> {
                    val page = notePage!!.toIntOrNull() ?: 0
                    CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, "${page + 1}")
                }
                else -> CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, STATION_STARS)
            }
        }
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener {
            // Jump back to today's day page from the week view.
            CalendarNavigator.toDayPage(this, LocalDate.now())
        }

        // The rail takes the Weeks page — the very page whose upstream toolbar the rail's idiom
        // came from. The old popout retires into its descendant: TuckPanel supersedes the
        // toolbar's own chrome in the same slot, the floating nav pill never comes up, and the
        // header ☰ retires with the rail's Hub button carrying the same door (one door, and the
        // tucked strip can never be hidden). `calendarToolbarSide` stays the one side truth.
        setupAlmanacRail(
            surfaceKey = "week",
            isAtPresent = {
                val wf = java.time.temporal.WeekFields.ISO; val n = LocalDate.now()
                currentDate.get(wf.weekOfWeekBasedYear()) == n.get(wf.weekOfWeekBasedYear()) &&
                    currentDate.get(wf.weekBasedYear()) == n.get(wf.weekBasedYear())
            },
            extraTools = if (notePage?.toIntOrNull() != null) listOf(
                GoItem("▤", "Page template…") { showPeriodTemplatePicker() }
            ) else emptyList(),
            // The bottom pill's corner offers the pen tools on every page that takes ink, the
            // period's face too (parity with iOS, review 2026-10-11) — never on a walk station,
            // which stores none: there the corner is ⛶ only.
            takesInk = !isStation(notePage)
        ) { CalendarNavigator.toWeekPage(this, LocalDate.now(), calendarWeek.locale, CalendarWeek.DEFAULT_STYLE) }

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
            presenter.load(this@CalendarWeekFragment, binding, currentDate, locale)
            syncPresenter.backgroundSync(this@CalendarWeekFragment, UUID.randomUUID())
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
     * Sync the template ImageView transform with the zoom/pan matrix.
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
     * @param calendarWeek the data class
     * @param calendarPattern the pattern data class
     */
    fun renderPage(calendarWeek: CalendarWeek, calendarPattern: CalendarPattern) {
        this.calendarWeek = calendarWeek
        this.calendarPattern = calendarPattern
        updateNavigator()

        if (isStation(notePage)) {
            renderStation(notePage!!)
            return
        }
        if (notePage != null) {
            // ▤ PER PAGE (DESIGN-period-walk-2026-08-27 §3): the page's explicit sheet wins;
            // without one, the legacy global pref keeps its two meanings (0 lines, 1 grid) so
            // every page inked before this build looks exactly as it always did.
            val page = notePage!!.toIntOrNull() ?: 0
            val tplStore = com.toolsboox.plugin.calendar.ot.NotePageTemplates
            val tpl = tplStore.templateFor(requireContext(), periodAnchor(), "week#$page")
                ?: if (sharedPreferences.getInt("calendarNoteTemplate", 0) == 1) tplStore.GRID
                else tplStore.LINES
            val noteStrokes = calendarWeek.noteStrokes[notePage] ?: listOf()
            drawTemplate { c -> com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.drawPeriodNotePage(c, tpl, page) }
            applyStrokes(Stroke.listDeepCopy(noteStrokes), true)
        } else {
            val calendarStrokes = calendarWeek.calendarStrokes[calendarStyle ?: CalendarMonth.DEFAULT_STYLE] ?: listOf()
            drawTemplate { c -> CalendarWeekPage.drawPage(this.requireContext(), c, calendarWeek, calendarPattern) }
            applyStrokes(Stroke.listDeepCopy(calendarStrokes), true)
        }
    }

    /** How many pages the station under the pen currently holds — set by [renderStation] on
     *  arrival, read by the skipper to know whether "down" means the next page or the next
     *  station. One is always safe: a stale count only shortens the in-station walk. */
    private var stationPageCount = 1

    /**
     * Draw a walk station: aggregate the week's seven day files (IO, on demand — never at list
     * time) and paint the scoped register. Read-only; applyStrokes(empty) keeps the ink layer
     * clear, and the touch router above never lets the pen write here. Per-day tombstones are
     * honoured — a deleted gram still sitting in its file must not resurface in the window.
     */
    private fun renderStation(station: String) {
        val ctx = requireContext()
        val wf = WeekFields.of(calendarWeek.locale)
        val monday = currentDate.with(wf.dayOfWeek(), 1)
        val label = "WEEK ${currentDate.get(wf.weekOfWeekBasedYear())}"
        GlobalScope.launch(Dispatchers.Main) {
            val grams: List<com.toolsboox.da.ImageElement> = kotlinx.coroutines.withContext(Dispatchers.IO) {
                // The one factory outside Hilt (a bare CalendarDayService() once read every day as
                // "corrupt" here, -322) and the one read-only gather all four stations share.
                val svc = com.toolsboox.plugin.calendar.fi.CalendarDayService.standalone(ctx)
                val root = com.toolsboox.ot.LedgerPaths.documentsRoot(ctx)
                com.toolsboox.plugin.calendar.ot.StationGrams.gather(
                    svc, root, (0L..6L).map { monday.plusDays(it) }, calendarWeek.locale)
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

    /** The week's one identity for per-page templates: its Monday in the calendar's own locale
     *  (the -22 week-number law), so the sidecar row survives whatever date opened the week. */
    private fun periodAnchor(): java.time.LocalDate =
        currentDate.with(WeekFields.of(calendarWeek.locale).dayOfWeek(), 1)

    /** The ▤ picker for a period note page — the plain papers only (lines · grid · dots);
     *  pickings waits for the boards-are-paper arc. Reached from the rail wrench's Tools. */
    private fun showPeriodTemplatePicker() {
        val ctx = context ?: return
        val page = notePage?.toIntOrNull() ?: return
        val tplStore = com.toolsboox.plugin.calendar.ot.NotePageTemplates
        val key = "week#$page"
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
                    CalendarNavigator.toWeekNote(this, currentDate, calendarWeek.locale, notePage!!)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    companion object {
        /** The walk stations' notePage keys — navigation addresses only, NEVER storage keys:
         *  the touch router refuses ink on them, so no period file ever grows these entries.
         *  A paged station rides "#<n>" suffixes on the same addresses. */
        const val STATION_STARS = "allstars"
        const val STATION_PICKS = "allpicks"
        fun isStation(p: String?): Boolean =
            p != null && (p.substringBefore('#') == STATION_STARS || p.substringBefore('#') == STATION_PICKS)
    }

    /**
     * Update navigator bar.
     *
     * @param first flag of first start
     */
    private fun updateNavigator(first: Boolean = false) {
        if (first) return

        val locale = calendarWeek.locale
        val year = currentDate.year
        val weekOfYearField = WeekFields.of(locale).weekOfWeekBasedYear()
        val weekOfYear = currentDate.get(weekOfYearField)

        val pageTitle = getString(R.string.calendar_week_title).format(year, weekOfYear)
        toolbar.root.title = getString(R.string.calendar_main_title, pageTitle)

        CalendarWeekNavigator.draw(this.requireContext(), navigatorCanvas, calendarWeek, calendarPattern)

        firebaseAnalytics.logEvent("calendarWeek") {
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
}
