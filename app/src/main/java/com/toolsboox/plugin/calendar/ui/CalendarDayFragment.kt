package com.toolsboox.plugin.calendar.ui

import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent
import com.toolsboox.R
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import com.toolsboox.databinding.FragmentCalendarBinding
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.databinding.ToolbarDrawingBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.*
import com.toolsboox.plugin.michaelfilter.da.IntakePageData
import com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
import com.toolsboox.ui.plugin.SurfaceFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import javax.inject.Inject
import kotlin.math.abs

/**
 * Calendar day view fragment.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@AndroidEntryPoint
class CalendarDayFragment @Inject constructor() : SurfaceFragment() {

    /**
     * The Firebase analytics.
     */
    @Inject
    lateinit var firebaseAnalytics: FirebaseAnalytics

    /**
     * The presenter of the fragment.
     */
    @Inject
    lateinit var presenter: CalendarDayPresenter

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
    private var calendarStyle: String = CalendarDay.DEFAULT_STYLE

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
    private lateinit var calendarDay: CalendarDay

    /**
     * The pattern data class.
     */
    private lateinit var calendarPattern: CalendarPattern

    /**
     * Typed content of the MichaelFilter intake page (loaded lazily per day).
     */
    private var intakePageData: IntakePageData? = null

    // Finger-tap tracking on the intake page (tap-to-type strips).
    private var intakeTapDownX: Float = 0f
    private var intakeTapDownY: Float = 0f
    private var intakeTapDownAt: Long = 0L

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
        val strokesCopy = Stroke.listDeepCopy(strokes)
        if (notePage != null) {
            calendarDay.noteStrokes[notePage!!] = strokesCopy
        } else {
            calendarDay.calendarStrokes[calendarStyle] = strokesCopy
        }

        calendarPattern.updateDay(calendarDay)

        // Per-stroke save: suppress the loading indicator so its VISIBLE/INVISIBLE flash
        // doesn't trigger an e-ink refresh on every pen-up (reads as lag/freeze on lift).
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
    }

    /**
     * Text elements changed callback.
     *
     * @param textElements the current text elements
     */
    override fun onTextElementsChanged(textElements: MutableList<TextElement>) {
        calendarDay.textElements = textElements
        calendarPattern.updateDay(calendarDay)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
    }

    /**
     * Image elements changed callback — persist inserted/moved/resized/deleted images.
     *
     * @param imageElements the current image elements
     */
    override fun onImageElementsChanged(imageElements: MutableList<ImageElement>) {
        // Per-page images: tag the current page's images, keep every other page's untouched.
        val pageKey = notePage ?: "default"
        imageElements.forEach { it.page = pageKey }
        val others = calendarDay.imageElements.filter { it.page != pageKey }
        calendarDay.imageElements = (others + imageElements).toMutableList()
        calendarPattern.updateDay(calendarDay)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
    }

    /**
     * On strokes procrastinated event.
     *
     * @param strokes the strokes to procrastinate
     */
    override fun onStrokesProcrastinated(strokes: List<Stroke>) {
        firebaseAnalytics.logEvent("procrastinator", null)
        presenter.procrastinate(this, binding, Stroke.listDeepCopy(strokes), currentDate, calendarDay, calendarStyle)
    }

    /**
     * On side switched event.
     */
    override fun onSideSwitched() {
        utils.updateToolbar(binding, true)

        if (notePage != null)
            CalendarNavigator.toDayNote(this, currentDate, notePage!!)
        else
            CalendarNavigator.toDayPage(this, currentDate, CalendarDay.DEFAULT_STYLE)
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
            arguments?.getString("month")?.toIntOrNull()?.let { month ->
                Timber.i("Set year and month to '$year'/'$month' from parameter")
                currentDate = LocalDate.of(year, month, 1)
                arguments?.getString("day")?.toIntOrNull()?.let { day ->
                    Timber.i("Set year, month and day to '$year'/'$month'/'$day' from parameter")
                    currentDate = LocalDate.of(year, month, day)
                }
            }
        }
        calendarStyle = arguments?.getString("calendarStyle") ?: CalendarDay.DEFAULT_STYLE
        notePage = arguments?.getString("notePage")

        val defaultStartHour = sharedPreferences.getInt("calendarStartHour", 5)
        calendarDay = CalendarDay(
            currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
            mutableListOf(), mutableListOf(), true, defaultStartHour
        )

        binding.navigatorImageView.setOnTouchListener { view, motionEvent ->
            CalendarDayNavigator.onTouchEvent(view, motionEvent, this@CalendarDayFragment, calendarDay)
        }

        binding.surfaceView.setOnHoverListener { _, motionEvent ->
            return@setOnHoverListener callback(motionEvent, true)
        }
        binding.surfaceView.setOnTouchListener { view, motionEvent ->
            if (callback(motionEvent, false)) return@setOnTouchListener true
            if (handleZoomPanTouch(motionEvent)) return@setOnTouchListener true

            val rawGesture = gestureListener.onTouchEvent(gestureDetector, view, motionEvent)
            val gestureResult = if (twoFingerGesture) rawGesture else OnGestureListener.NONE

            if (notePage == "intake" && handleIntakeTap(motionEvent, gestureResult))
                return@setOnTouchListener true

            if (notePage != null)
                CalendarDayPageNotes.onTouchEvent(
                    view, motionEvent, gestureResult, this@CalendarDayFragment, calendarDay, notePage!!
                )
            else {
                CalendarDayPage.onTouchEvent(
                    view, motionEvent, gestureResult, this@CalendarDayFragment, calendarDay
                )
            }
        }

        toolbar.toolbarPager.visibility = View.GONE

        binding.toolbarDrawing.toolbarSwipeUp.setOnClickListener {
            if (notePage != null) {
                when (notePage) {
                    "pickings" -> CalendarNavigator.toDayPage(this, currentDate, CalendarDay.DEFAULT_STYLE)
                    "gratitude" -> CalendarNavigator.toDayNote(this, currentDate, "pickings")
                    "intake" -> CalendarNavigator.toDayNote(this, currentDate, "gratitude")
                    else -> {
                        val page = notePage!!.toIntOrNull() ?: 0
                        if (page == 0) {
                            CalendarNavigator.toDayNote(this, currentDate, "intake")
                        } else {
                            CalendarNavigator.toDayNote(this, currentDate, "${page - 1}")
                        }
                    }
                }
            } else {
                CalendarNavigator.toWeekPage(this, currentDate, locale)
            }
        }
        binding.toolbarDrawing.toolbarSwipeDown.setOnClickListener {
            if (notePage != null) {
                when (notePage) {
                    "pickings" -> CalendarNavigator.toDayNote(this, currentDate, "gratitude")
                    "gratitude" -> CalendarNavigator.toDayNote(this, currentDate, "intake")
                    "intake" -> CalendarNavigator.toDayNote(this, currentDate, "0")
                    else -> {
                        val page = notePage!!.toIntOrNull() ?: 0
                        CalendarNavigator.toDayNote(this, currentDate, "${page + 1}")
                    }
                }
            } else {
                CalendarNavigator.toDayNote(this, currentDate, "pickings")
            }
        }
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener {
            // Always jump to today's day page (Default style), regardless of what
            // date you're currently viewing or whether you're on a note page.
            CalendarNavigator.toDayPage(this, LocalDate.now(), CalendarDay.DEFAULT_STYLE)
        }

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

        val defaultStartHour = sharedPreferences.getInt("calendarStartHour", 5)
        timer = GlobalScope.launch(Dispatchers.Main) {
            presenter.load(this@CalendarDayFragment, binding, currentDate, defaultStartHour, locale)
            syncPresenter.backgroundSync(this@CalendarDayFragment, UUID.randomUUID())
        }
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()

        // Leaving the intake page counts as "page exit" — hand any typed content
        // that has not been delivered yet to the intake queue.
        if (notePage == "intake") {
            intakePageData?.let { data ->
                context?.let { ctx -> IntakePageStore.dispatch(ctx, currentDate, data) }
            }
        }

        toolbar.toolbarPager.visibility = View.GONE
        timer.cancel()
        syncPresenter.backgroundSync(this@CalendarDayFragment, UUID.randomUUID())
    }

    override fun onTransformChanged(matrix: Matrix) {
        if (::binding.isInitialized) {
            binding.templateImageView.scaleType = ImageView.ScaleType.MATRIX
            binding.templateImageView.imageMatrix = matrix
        }
    }

    /**
     * Reload the current page.
     *
     * @param calendarDay the data class
     * @param calendarPattern the pattern data class
     * @param calendarEvents the calendar events
     */
    fun renderPage(
        calendarDay: CalendarDay, calendarPattern: CalendarPattern, calendarEvents: List<CalendarEvent>
    ) {
        this.calendarDay = calendarDay
        this.calendarPattern = calendarPattern
        updateNavigator()

        // Load text elements from the calendar data
        setTextElements(calendarDay.textElements)
        val imgPageKey = notePage ?: "default"
        setImageElements(calendarDay.imageElements.filter { it.page == imgPageKey }.toMutableList())

        if (notePage != null) {
            binding.toolbarDrawing.toolbarProcrastinator.visibility = View.GONE
            val noteTemplate = sharedPreferences.getInt("calendarNoteTemplate", 0)
            val noteStrokes = calendarDay.noteStrokes[notePage] ?: listOf()
            if (notePage == "intake") {
                // Intake page: draw the template with the day's typed panel texts in place.
                val intakeData = IntakePageStore.load(requireContext(), currentDate).also { intakePageData = it }
                CalendarDayPageIntake.drawPage(templateCanvas, intakeData)
            } else {
                CalendarDayPageNotes.drawPage(this.requireContext(), templateCanvas, calendarDay, noteTemplate, notePage!!)
            }
            applyStrokes(Stroke.listDeepCopy(noteStrokes), true)
        } else {
            binding.toolbarDrawing.toolbarProcrastinator.visibility = View.VISIBLE
            val calendarStrokes = calendarDay.calendarStrokes[calendarStyle] ?: listOf()
            CalendarDayPage.drawPage(this.requireContext(), templateCanvas, calendarDay, calendarEvents)
            applyStrokes(Stroke.listDeepCopy(calendarStrokes), true)
        }
        // The template was just drawn into templateBitmap; force the ImageView to repaint so
        // named pages (pickings/gratitude) reliably show on first navigation, not only after a re-swipe.
        binding.templateImageView.invalidate()
        redrawImageSelectionIfActive()
    }

    /**
     * Detect a finger tap on one of the intake page's tap-to-type strips and
     * open the typed-text dialog for that panel.
     *
     * @param motionEvent the motion event
     * @param gestureResult the gesture result (taps must not be swipes)
     * @return true when the tap was consumed
     */
    private fun handleIntakeTap(motionEvent: MotionEvent, gestureResult: Int): Boolean {
        if (motionEvent.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                intakeTapDownX = motionEvent.x
                intakeTapDownY = motionEvent.y
                intakeTapDownAt = System.currentTimeMillis()
            }

            MotionEvent.ACTION_UP -> {
                val dx = abs(motionEvent.x - intakeTapDownX)
                val dy = abs(motionEvent.y - intakeTapDownY)
                val dt = System.currentTimeMillis() - intakeTapDownAt
                if (!twoFingerGesture && gestureResult == OnGestureListener.NONE &&
                    dx < 30f && dy < 30f && dt in 1..600
                ) {
                    val canvasPts = screenToCanvas(motionEvent.x, motionEvent.y)
                    CalendarDayPageIntake.typedZoneAt(canvasPts[0], canvasPts[1])?.let { kindKey ->
                        showIntakeTypedDialog(kindKey)
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Show the typed-text dialog of an intake panel; on OK, store the text,
     * render it in place and dispatch new URLs / educate notes to the queue.
     *
     * @param kindKey the panel kind (read|watch|listen|educate)
     */
    private fun showIntakeTypedDialog(kindKey: String) {
        val ctx = context ?: return
        val data = intakePageData ?: IntakePageStore.load(ctx, currentDate).also { intakePageData = it }
        val panelTitle = CalendarDayPageIntake.panels.firstOrNull { it.kindKey == kindKey }?.title ?: kindKey

        val editText = EditText(ctx)
        editText.hint = getString(R.string.michaelfilter_intake_typed_dialog_hint)
        editText.setSingleLine(false)
        editText.setLines(5)
        editText.setText(data.typedFor(kindKey))
        editText.setSelection(editText.text?.length ?: 0)

        val container = FrameLayout(ctx)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, 0, margin, 0)
        editText.layoutParams = params
        container.addView(editText)

        AlertDialog.Builder(ctx)
            .setTitle(panelTitle)
            .setView(container)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                data.setTypedFor(kindKey, editText.text.toString())
                IntakePageStore.save(ctx, currentDate, data)
                val queued = IntakePageStore.dispatch(ctx, currentDate, data)

                CalendarDayPageIntake.drawPage(templateCanvas, data)
                binding.templateImageView.invalidate()

                if (queued > 0) {
                    showMessage(getString(R.string.michaelfilter_intake_queued_count, queued), binding.root)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create().show()
        editText.requestFocus()
    }

    /**
     * Update navigator bar.
     *
     * @param first flag of first start
     */
    private fun updateNavigator(first: Boolean = false) {
        if (first) return

        val titleDate = currentDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

        val pageTitle = getString(R.string.calendar_day_title).format(titleDate)
        toolbar.root.title = getString(R.string.calendar_main_title, pageTitle)

        CalendarDayNavigator.draw(this.requireContext(), navigatorCanvas, calendarDay, calendarPattern)

        firebaseAnalytics.logEvent("calendarDay") {
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
