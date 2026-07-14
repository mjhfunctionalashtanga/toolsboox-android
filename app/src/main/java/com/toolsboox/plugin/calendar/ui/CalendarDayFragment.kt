package com.toolsboox.plugin.calendar.ui

import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
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

    // Finger long-press tracking ("pen writes, finger manages" element menu).
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressDownX: Float = 0f
    private var longPressDownY: Float = 0f
    private var longPressPending: Boolean = false
    private var longPressFired: Boolean = false
    private val longPressRunnable = Runnable {
        longPressPending = false
        longPressFired = true
        val canvasPts = screenToCanvas(longPressDownX, longPressDownY)
        handleCanvasLongPress(canvasPts[0], canvasPts[1], longPressDownX, longPressDownY)
    }

    /**
     * SurfaceView provide method.
     *
     * @return the actual surfaceView
     */
    override fun provideSurfaceView(): SurfaceView = binding.surfaceView

    /**
     * Element changes need calendarDay/calendarPattern; both load asynchronously.
     */
    override fun isPageDataReady(): Boolean =
        ::calendarDay.isInitialized && ::calendarPattern.isInitialized

    /**
     * The current note page key ("pickings", "gratitude", "intake", "0"...),
     * or null on the plain day page. Used by the day navigator to keep the
     * prev/next day arrows within the current page group.
     */
    fun currentNotePage(): String? = notePage

    /**
     * The intake page needs normal Android touch over the surface for its
     * tap-to-type strips; the Onyx raw session would swallow it system-wide,
     * so that page runs on the MotionEvent capture + software render path.
     */
    override fun provideDisableRawInkCapture(): Boolean = notePage == "intake"

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
        // Per-page text boxes: tag the current page's, keep every other page's.
        val pageKey = notePage ?: "default"
        textElements.forEach { it.pageKey = pageKey }
        val others = calendarDay.textElements.filter { it.pageKey != pageKey }
        calendarDay.textElements = (others + textElements).toMutableList()
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
     * A text box finished a drag. On the intake page, a box carrying a link
     * dropped onto a panel files that link into the panel's lane — placement
     * is the gesture that runs the pipeline; sharing alone queues nothing.
     */
    override fun onTextBoxDropped(element: TextElement) {
        if (notePage != "intake") return
        val bounds = textElementBounds(element)
        val panel = CalendarDayPageIntake.panels.firstOrNull {
            it.rect.contains(bounds.centerX(), bounds.centerY())
        } ?: return
        val url = element.sourceUrl
            ?: com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(element.text).firstOrNull()
            ?: return
        val title = element.text.lines().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
        val submission = com.toolsboox.plugin.michaelfilter.da.IntakeSubmission(
            linkUrl = url, linkKind = panel.kindKey, linkTitle = title
        )
        com.toolsboox.plugin.michaelfilter.nw.IntakeQueue.enqueue(requireContext(), submission)
        com.toolsboox.plugin.michaelfilter.nw.IntakeQueue.scheduleDrain(requireContext())
        Timber.i("Text box dropped on ${panel.kindKey}: $url")
        android.widget.Toast.makeText(requireContext(), "Filed to ${panel.title}", android.widget.Toast.LENGTH_SHORT).show()
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

        // Share-to-Ledger: an image shared from another app arrives as a uri argument.
        // Consume it (so back-stack re-creation doesn't re-insert) and queue the insert;
        // it completes in renderPage once the page data has loaded.
        arguments?.getString("sharedImageUri")?.let { shared ->
            arguments?.remove("sharedImageUri")
            Timber.i("Shared image queued for insert: $shared")
            queueSharedImageInsert(android.net.Uri.parse(shared))
        }
        arguments?.getString("sharedText")?.let { shared ->
            val sharedUrl = arguments?.getString("sharedUrl")
            arguments?.remove("sharedText")
            arguments?.remove("sharedUrl")
            Timber.i("Shared text queued for insert (${shared.length} chars, url=$sharedUrl)")
            queueSharedTextInsert(shared, sharedUrl)
        }

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
            if (handleFingerLongPress(motionEvent)) return@setOnTouchListener true
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
        // Calendar button opens the "Go to" panel (day sections + Ledger surfaces),
        // floating opposite the pen strip. Almanac views live on the top date bar.
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener { showDirectoriesModal() }
        // Top-left hamburger on the date bar → full directories (Ledger surfaces).
        binding.goAppsButton.visibility = View.VISIBLE
        binding.goAppsButton.setOnClickListener { showDirectoriesModal() }
        binding.goSectionsButton.visibility = View.GONE

        // Retire the fixed pen strip on the day page — the floating pills + gear now cover
        // everything. The real buttons stay in the (hidden) layout so performClick still
        // drives the Onyx ink actions; nothing about drawing changes.
        binding.toolbarDrawing.root.visibility = View.GONE

        // Floating nav widget: reuse the existing nav actions so nothing about drawing
        // changes. ‹ › step the date; ↑ ↓ cycle the day's sections.
        binding.navWidget.visibility = View.VISIBLE
        binding.navUp.setOnClickListener { binding.toolbarDrawing.toolbarSwipeUp.performClick() }
        binding.navDown.setOnClickListener { binding.toolbarDrawing.toolbarSwipeDown.performClick() }
        binding.navLeft.setOnClickListener { CalendarNavigator.toDayPage(this, currentDate.minusDays(1), CalendarDay.DEFAULT_STYLE) }
        binding.navRight.setOnClickListener { CalendarNavigator.toDayPage(this, currentDate.plusDays(1), CalendarDay.DEFAULT_STYLE) }

        // Floating tool selector: each button drives the real (hidden) toolbar action,
        // so the Onyx ink wiring is unchanged. Long-press the eraser to clear the page.
        binding.toolWidget.visibility = View.VISIBLE
        binding.toolPen.setOnClickListener { binding.toolbarDrawing.toolbarPen.performClick() }
        // Long-press the pen → pick ballpoint vs calligraphy (shows the active one).
        binding.toolPen.setOnLongClickListener {
            val opts = arrayOf("Ballpoint", "Calligraphy")
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_drawing_toolbar_pen)
                .setSingleChoiceItems(opts, if (penIsCalligraphy()) 1 else 0) { d, w ->
                    setPenCalligraphy(w == 1)
                    d.dismiss()
                }
                .show()
            true
        }
        binding.toolEraser.setOnClickListener { binding.toolbarDrawing.toolbarEraser.performClick() }
        binding.toolEraser.setOnLongClickListener { binding.toolbarDrawing.toolbarTrash.performClick(); true }
        binding.toolLasso.setOnClickListener { binding.toolbarDrawing.toolbarLasso.performClick() }
        binding.toolUndo.setOnClickListener { binding.toolbarDrawing.toolbarUndo.performClick() }
        binding.toolRedo.setOnClickListener { binding.toolbarDrawing.toolbarRedo.performClick() }

        // Bottom pill button → this day's sections (Today/Pickings/Gratitude/Later/Notes).
        // Gear = flip/rotate/settings/finger/add.
        binding.navGoto.setOnClickListener { showSectionsModal() }
        binding.navGear.setOnClickListener { showWidgetGearMenu() }
        applyWidgetOrientation()

        // Lift the floating overlays above the drawing surface without elevation (which
        // renders as an ugly black shadow-box on e-ink).
        binding.goAppsButton.bringToFront()
        binding.toolWidget.bringToFront()
        binding.navWidget.bringToFront()

        // Repositionable pills: drag the grip to move a pill anywhere (persisted).
        makeDraggable(binding.toolGrip, binding.toolWidget, "tool")
        makeDraggable(binding.navGrip, binding.navWidget, "nav")

        utils.updateToolbar(binding)
        // Inset the date bar so the top-left hamburger sits in its own gutter (no caret overlap).
        (binding.navigatorImageView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = (52 * resources.displayMetrics.density).toInt()
            binding.navigatorImageView.layoutParams = it
        }
        initializeSurface(true)
    }

    /**
     * iPad-style "Go to" modal — jump straight to a day-page section (Schedule,
     * Pickings, Gratitude, Later List, Notes) or a Ledger surface (Reader, Feed
     * Ledger, Ask my Ledger, Cloud) without leaving for a dashboard. Reached from
     * the toolbar's calendar-view button.
     */
    /** Flip the floating pills between a horizontal and vertical layout. Defaults to
     *  vertical on narrow (phone-size) screens so the two pills don't collide; the gear's
     *  "Flip layout" overrides and persists the choice. */
    private fun applyWidgetOrientation() {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val narrow = resources.configuration.screenWidthDp < 520
        val vertical = prefs.getBoolean("vertical", narrow)
        val o = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        binding.navWidget.orientation = o
        binding.toolWidget.orientation = o
    }

    private fun showWidgetGearMenu() {
        val labels = arrayOf("Flip layout", "Reset pill positions", "Rotate screen", "Add text", "Add image", "Finger / hand", "Settings")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.calendar_drawing_toolbar_settings)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
                        prefs.edit().putBoolean("vertical", !prefs.getBoolean("vertical", false)).apply()
                        applyWidgetOrientation()
                    }
                    1 -> {
                        requireContext().getSharedPreferences("ledger_widgets", 0).edit()
                            .remove("nav_tx").remove("nav_ty").remove("tool_tx").remove("tool_ty").apply()
                        for (v in listOf(binding.navWidget, binding.toolWidget)) { v.translationX = 0f; v.translationY = 0f }
                    }
                    2 -> binding.toolbarDrawing.toolbarRotate.performClick()
                    3 -> binding.toolbarDrawing.toolbarText.performClick()
                    4 -> binding.toolbarDrawing.toolbarImage.performClick()
                    5 -> binding.toolbarDrawing.toolbarHandTouch.performClick()
                    6 -> binding.toolbarDrawing.toolbarSettings.performClick()
                }
            }
            .show()
    }

    private data class GoItem(val emoji: String, val label: String, val action: () -> Unit)

    /** Top-left hamburger → full directories: the Ledger surfaces. */
    private fun showDirectoriesModal() = showGoModal(
        listOf(
            getString(R.string.go_group_ledger) to listOf(
                GoItem("📚", "Bookshelf") { findNavController().navigate(R.id.action_to_reader) },
                GoItem("📰", "Feed Ledger") { findNavController().navigate(R.id.action_to_feeds) },
                GoItem("💬", "Ask my Ledger") { findNavController().navigate(R.id.action_to_ledger_chat) },
                GoItem("☁️", "Cloud") { CalendarNavigator.toCloudSync(this) }
            )
        ),
        anchorTop = true
    )

    /** Bottom pill → this day's sections. */
    private fun showSectionsModal() = showGoModal(
        listOf(
            getString(R.string.go_group_day) to listOf(
                GoItem("📅", "Today") { CalendarNavigator.toDayPage(this, LocalDate.now(), CalendarDay.DEFAULT_STYLE) },
                GoItem("❝", "Pickings") { CalendarNavigator.toDayNote(this, currentDate, "pickings") },
                GoItem("🙏", "Gratitude") { CalendarNavigator.toDayNote(this, currentDate, "gratitude") },
                GoItem("🔖", "Later List") { CalendarNavigator.toDayNote(this, currentDate, "intake") },
                GoItem("✒️", "Notes") { CalendarNavigator.toDayNote(this, currentDate, "0") }
            )
        ),
        anchorTop = false
    )

    /**
     * Shared builder for the floating panels — a compact emoji list. Directories anchor
     * top-left (by the hamburger); sections anchor bottom (by the pill).
     */
    private fun showGoModal(groups: List<Pair<String, List<GoItem>>>, anchorTop: Boolean) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        for ((header, items) in groups) {
            val tv = TextView(requireContext())
            tv.text = header.uppercase()
            tv.setTextColor(0xFF8A8A8A.toInt()); tv.textSize = 11f; tv.letterSpacing = 0.08f
            tv.setPadding(dp(14), dp(10), dp(14), dp(2))
            list.addView(tv)
            for (item in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<ImageView>(R.id.go_icon).visibility = View.GONE
                r.findViewById<TextView>(R.id.go_label).text = "${item.emoji}  ${item.label}"
                r.setOnClickListener { dialog.dismiss(); item.action() }
                list.addView(r)
            }
        }

        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.width = dp(220)
            if (anchorTop) {          // directories, under the top-left hamburger
                lp.gravity = Gravity.START or Gravity.TOP
                lp.x = dp(8); lp.y = dp(54)
            } else {                  // sections, up from the bottom pill
                lp.gravity = Gravity.END or Gravity.BOTTOM
                lp.x = dp(10); lp.y = dp(80)
            }
            w.attributes = lp
        }
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

        // Load this page's text elements and images from the calendar data
        val imgPageKey = notePage ?: "default"
        setTextElements(calendarDay.textElements.filter { it.pageKey == imgPageKey }.toMutableList())
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

        // A picker/camera result may have arrived before this load finished.
        consumeDeferredImageInsert()
    }

    /**
     * Finger long-press detection ("pen writes, finger manages"): a held finger
     * on the canvas opens the element menu (create on empty canvas, select on an
     * existing image/text box); a quick tap completes a pending text-box move.
     * Runs BEFORE callback()/gestures; pre-fire events pass through untouched.
     *
     * @param motionEvent the motion event
     * @return true when the event was consumed by long-press handling
     */
    private fun handleFingerLongPress(motionEvent: MotionEvent): Boolean {
        val tool = motionEvent.getToolType(0)
        val isFinger = tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN

        // The pen cancels any pending long-press and is never affected itself.
        if (!isFinger) {
            if (longPressPending) {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressPending = false
            }
            return false
        }

        // After the long-press fired, swallow the remainder of that gesture so it
        // can't also register as a tap/swipe under the menu.
        if (longPressFired && motionEvent.actionMasked != MotionEvent.ACTION_DOWN) {
            if (motionEvent.actionMasked == MotionEvent.ACTION_UP ||
                motionEvent.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                longPressFired = false
            }
            return true
        }

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                // Not while manipulating an element (finger drags move/resize there)
                // and only for a single finger.
                if (motionEvent.pointerCount == 1 && !isImageModeActive()) {
                    longPressDownX = motionEvent.x
                    longPressDownY = motionEvent.y
                    longPressPending = true
                    longPressHandler.postDelayed(longPressRunnable, 550L)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressPending = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressPending &&
                    (abs(motionEvent.x - longPressDownX) > 30f || abs(motionEvent.y - longPressDownY) > 30f)
                ) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    longPressPending = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressPending = false
            }
        }

        return false
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
        // Finger taps only. TOOL_TYPE_UNKNOWN is accepted because injected events
        // (adb input tap, accessibility) carry it; real pen taps are STYLUS and stay ink.
        val toolType = motionEvent.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_FINGER && toolType != MotionEvent.TOOL_TYPE_UNKNOWN) return false

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
                // NOTE: deliberately no twoFingerGesture check — with the one-finger
                // gestures toolbar toggle enabled, SurfaceFragment sets
                // twoFingerGesture=true for EVERY single-finger touch (the flag really
                // means "gesture recognition armed"), which would veto all taps.
                // gestureResult==NONE + movement slop + tap duration are sufficient.
                if (gestureResult == OnGestureListener.NONE &&
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
