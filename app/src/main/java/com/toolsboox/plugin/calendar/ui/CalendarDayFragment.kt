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
import androidx.lifecycle.lifecycleScope
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
import com.toolsboox.plugin.feeds.ui.promptTitle
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v1.CalendarPattern
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.*
import com.toolsboox.plugin.michaelfilter.da.IntakePageData
import com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
import com.toolsboox.ui.plugin.ScreenFragment.GoItem
import com.toolsboox.ui.plugin.SurfaceFragment
import dagger.hilt.android.AndroidEntryPoint
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * How many picking rows "Bring in a picking" draws before it offers "…and N more". The Ledger
 * Directory's page size, deliberately the same number: it is the same unroll gesture on the same
 * kind of list, and two different page sizes would be two different lists to the hand.
 *
 * It is also the face budget. Faces are decoded only for rows that were rendered, so unrolling is
 * what pays for the next page of them.
 */
private const val PICK_ROWS_PAGE = 40

/**
 * How many un-indexed days one pass of the picker will DECODE before it stops and says so.
 *
 * The old gather parsed a hundred and twenty day files every single time it opened; this pays that
 * cost only for days [com.toolsboox.plugin.calendar.ot.PickingsCards] has never met, and each one
 * paid for repairs that day's sidecar permanently. So the number is a first-run allowance that
 * shrinks to nothing by itself, not a standing tax — and whatever it does not reach is COUNTED and
 * shown, never quietly dropped.
 */
private const val PICK_DAY_BUDGET = 40

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

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var chatService: com.toolsboox.plugin.chat.nw.LedgerChatService

    /** Reads the whole ledger as citable snippets — what the spiral line draws from. */
    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    /**
     * The inflated layout.
     */
    override val view = R.layout.fragment_calendar

    /**
     * The view binding.
     */
    private lateinit var binding: FragmentCalendarBinding

    /**
     * The tucked action rail — the day page is the first surface converted to the Weeks side
     * toolbar's tuck-in/tuck-out idiom (see [com.toolsboox.ot.TuckPanel]). One slim in-flow
     * icon column carries the whole action layer: the tools the floating tool pill used to
     * float and the nav actions the nav pill carried. Both pills no longer show here — on a
     * converted surface the rail IS the way (Ledgable doesn't do modes, so there is no
     * per-surface rail-vs-pills preference to leave behind); tucking the rail to its edge
     * strip is what "hide the tools" means now.
     */
    private var tuckPanel: com.toolsboox.ot.TuckPanel? = null

    /** The day rail boxes the live tool here, not the almanac slot (plan v6 S1.0b). */
    override fun inkRail(): com.toolsboox.ot.TuckPanel? = tuckPanel

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
     * Which window All Stars is showing: "day" (default), "week", "month", "quarter", "year".
     *
     * Set by tapping a period slot in the almanac header, which on this page FILTERS rather than
     * navigates — Michael: "a place where items that got stars from the different places within
     * Ledger and the day almanac top header menu can filter". The register is the same page
     * throughout; only how far back it reaches changes. Deliberately not persisted: a filter you
     * left on last week would silently answer a different question than the one the page appears to
     * be asking, and the page's whole job is to be unambiguous about its window.
     */
    private var starsScope: String = "day"

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
     * True when this day's file exists on disk but could not be read (OOM-large, corrupt beyond
     * the blank-file fallback in CalendarDayService.load) — the page renders blank and the
     * presenter refuses every save, because a save over the blank would discard the file's real
     * content. Set fresh by every presenter.load, so navigating away (or a repaired file) clears
     * it; it never sticks to another day.
     */
    var dayReadOnly = false

    /**
     * Typed content of the MichaelFilter intake page (loaded lazily per day).
     */
    private var intakePageData: IntakePageData? = null

    // Finger-tap tracking on the intake page (tap-to-type strips).
    private var eventTapDownX = 0f
    private var eventTapDownY = 0f
    private var eventTapDownAt = 0L
    private var intakeTapDownX: Float = 0f
    private var intakeTapDownY: Float = 0f
    private var intakeTapDownAt: Long = 0L
    // The daily Pickings cover's tiles — same finger-tap discipline as the intake strips.
    private var pickingsTapDownX: Float = 0f
    private var pickingsTapDownY: Float = 0f
    private var pickingsTapDownAt: Long = 0L

    // Quick Wins panel state: today's parked wins (drawn into the template) and the single-compute
    // flag. The panel never computes on the render path; it shows what's parked and lets the
    // background walk re-earn it when the ledger's hash has moved.
    //
    // There was a SECOND copy of this pair for the compact ⚡ glimpse that used to sit in the Roots
    // band's bottom slice. The glimpse had already been drawn down to nothing and the band itself is
    // retired, so the second parked answer — and the background corpus walk that kept it warm — went
    // with it. One wins system, one walk, one place it shows.
    private var openTasksShown: List<com.toolsboox.plugin.calendar.ot.OpenTasks.Open> = emptyList()
    private var openTasksCooking = false
    /** The events the page was last drawn with — so an off-render repaint (a ghost row marked
     *  done) redraws the schedule it actually had, not an empty one. */
    private var lastRenderedEvents: List<CalendarEvent> = emptyList()

    // Finger long-press tracking ("pen writes, finger manages" element menu).
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressDownX: Float = 0f
    private var longPressDownY: Float = 0f
    private var longPressPending: Boolean = false
    private var longPressArmed: Boolean = false
    private var longPressFired: Boolean = false
    // The timer only ARMS the long-press; the menu opens on the NEXT finger event that
    // proves the finger is still down (MOVE, or an UP whose own hold time qualifies).
    // Opening directly from the timer raced queued input during main-thread stalls: a
    // quick double-tap's UP sat unprocessed while the 550ms runnable fired, so the menu
    // opened over what should have been a double-tap zoom.
    private val longPressRunnable = Runnable {
        if (longPressPending) {
            longPressPending = false
            longPressArmed = true
        }
    }

    private fun fireCanvasLongPress() {
        longPressArmed = false
        longPressFired = true
        val canvasPts = screenToCanvas(longPressDownX, longPressDownY)
        // A hold landing on a pickings-cover tile is the tile's own gesture (hide it), not
        // the canvas menu's: this path consumes every still-finger hold on the surface, so
        // the cover's tap handler downstream never sees one.
        if (notePage == com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY) {
            com.toolsboox.plugin.calendar.ot.PickingsCover.tileAt(canvasPts[0], canvasPts[1])?.let { tile ->
                showPickingsTileHideDialog(tile)
                return
            }
        }
        // All Stars used to need its own hold menu, because its grams were painted into the template
        // and the generic canvas menu — which hit-tests element.x/y — could never find one. They are
        // real elements now, so the ordinary menu finds them, and the page keeps its own menu only
        // for a hold on EMPTY paper (bringing a picking into a band).
        if (notePage == CalendarDayPageIntake.INTAKE_PAGE &&
            imageElementAt(canvasPts[0], canvasPts[1]) == null
        ) {
            showIntakeHoldMenu(canvasPts[0], canvasPts[1], longPressDownX, longPressDownY)
            return
        }
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
     *
     * "READY" MEANS LOADED **AND CURRENT** — not merely loaded once. Michael's 08-12 punchlist:
     * "Adding an image doesn't render the image immediately — leaving the page and returning
     * does render." The mechanics, provable from the lifecycle: launching the system picker or
     * camera pauses this fragment; the activity result is dispatched when the fragment is
     * STARTED — i.e. BEFORE onResume — and onResume then launches presenter.load, an async
     * re-read of the day file. The old check answered true (calendarDay was initialized from
     * the previous session), so the insert landed in the in-memory day and queued its async
     * save … and then renderPage arrived with the freshly-read (pre-save) disk copy, REPLACED
     * calendarDay and repainted WITHOUT the new image. The save still completed — the element
     * was on disk — which is exactly why leaving and returning showed it: the bug was never
     * "not inserted", it was "inserted into a snapshot a scheduled reload was about to
     * overwrite". Requiring isResumed (the pre-resume dispatch) and no in-flight resume reload
     * (the post-resume window) routes those inserts through the existing deferral
     * (deferredInsertUri), which renderPage consumes AFTER the fresh data is on screen — so
     * the insert lands in the current day, paints immediately, and nothing races the save.
     */
    override fun isPageDataReady(): Boolean =
        ::calendarDay.isInitialized && ::calendarPattern.isInitialized &&
            isResumed && !resumeReloadPending

    /**
     * True from onResume's presenter.load launch until the renderPage it produces has run —
     * the window in which an element insert would be applied to data a reload is about to
     * replace. See [isPageDataReady].
     */
    private var resumeReloadPending = false

    /**
     * The current note page key ("pickings", "gratitude", "intake", "0"...),
     * or null on the plain day page. Used by the day navigator to keep the
     * prev/next day arrows within the current page group.
     */
    fun currentNotePage(): String? = notePage

    /**
     * Sub-page key convention: a named note surface (write / grid / sketch) may hold multiple
     * sub-pages, keyed "<base>" for the first and "<base>#<n>" for n≥1 (e.g. write, write#1,
     * write#2). [baseNotePage] strips the "#n" tail so a sub-page behaves like its base at every
     * site that switches on the page string (icon, folder home, ritual stepping, template) — the
     * ONLY place the tail matters is paging. [notePageSubIndex] is the sub-page number (0 for base).
     * Numeric notes ("0", "1", …) carry no "#" and are unaffected.
     */
    private fun baseNotePage(page: String?): String? = page?.substringBefore('#')

    private fun notePageSubIndex(page: String?): Int =
        page?.substringAfter('#', "")?.toIntOrNull() ?: 0

    /** The named note bases that own an inline ‹ N › sub-page pager (like the numeric notes do).
     *  A named Write DOCUMENT ("write-1753…") pages exactly like the daily one — the document is
     *  what's named; the pages inside it are still a run. (Named SYNTHESIZE topics deliberately
     *  stay off this list: they have never had a sub-page series, and adding one would also change
     *  what the almanac-as-filter interception below counts as a notes surface on pages Michael has
     *  real content in. Their directory therefore lists them as single-page, which is what they are.) */
    private fun isSubPageableBase(base: String?): Boolean =
        base == "write" || base == "synthesize" ||
            // NAMED synth topics page like every other document now — the correspondence "runs
            // across sub-pages" by design (§4), and a reply that flowed past a full page used to
            // land on a "#1" no pager could reach: the field's "it doesn't present any follow
            // up." Widening this widens isNotesTagSurface too, which is correct — a topic page
            // is a notes surface by every other measure.
            com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(base) ||
            base == com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS ||
            com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(base) ||
            // "grid"/"sketch" used to be literals here. They are their stores' questions now, so a
            // NAMED grid or jot pages exactly like the daily one — the document is what's named;
            // the pages inside it are still a run. Widening this also widens [isNotesTagSurface],
            // which is correct: a named grid is a notes surface by every other measure too.
            com.toolsboox.plugin.calendar.ot.GridPageStore.isMine(base) ||
            com.toolsboox.plugin.calendar.ot.JotPageStore.isMine(base)

    /**
     * Whether the current surface is a NOTES page for the almanac-as-filter interception: the
     * numbered note pages plus the write / grid / sketch note pages (and their #n sub-pages) — NOT
     * the plain day (null / Default) and NOT the ritual Flow/Garden stations (intake, pickings,
     * gratitude, selfexec, synthesize), which keep the ordinary almanac navigation.
     */
    private fun isNotesTagSurface(page: String?): Boolean {
        if (page == null || page == CalendarDay.DEFAULT_STYLE || page == "default") return false
        val base = baseNotePage(page)
        return isSubPageableBase(base) || page.toIntOrNull() != null
    }

    /**
     * The intake page needs normal Android touch over the surface for its
     * tap-to-type strips; the Onyx raw session would swallow it system-wide,
     * so that page runs on the MotionEvent capture + software render path.
     */
    // Ink is ALLOWED on All Stars. It was blocked because the page was a picture of its grams and
    // writing on a picture goes nowhere useful; now the grams are ordinary elements on an ordinary
    // page, so the pen behaves as it does on a Pickings board — you can write around and between
    // your stars. Michael: "all stars should be manipulatable just like pickings."
    override fun provideDisableRawInkCapture(): Boolean = false

    // Exclude only the notes pager (and its › tab) from the raw stylus reader. The today dashboard is DRAWN INTO the
    // page now (part of the template bitmap), NOT floating Views — its Tasks/Memo zones must INK
    // (write-by-hand), and its Notes/Doodle taps are page-region hit-rects ([onCanvasSingleTap]),
    // so there is nothing here to exclude. The old notes band is retired and stays hidden. Both
    // pills left this list with the surface's conversion to the tucked action rail — the rail lives
    // BESIDE the surface, not over it, so it needs no exclusion. The full-screen pill and its
    // flyout join it (plan v6 S1.2): the flyout opens over the page.
    // The pager's › tab too (review 2026-10-10): it stands on the page, in the pager's own top-right
    // spot, so a pen tap on it reaches it (the B6 forwards it) instead of inking under it. On Onyx
    // the pager's rect, excluded while it showed, already covers that spot until the next re-apply,
    // which then excludes the tab itself — its arrival never costs the reader a cold start.
    override fun provideExcludeViews(): List<View> =
        if (::binding.isInitialized) listOf(binding.notePager, binding.notePagerTab) + inkChromeViews()
        else emptyList()

    /** Rendered note-page minis for the dashboard's Notes zone, keyed by "pageKey:strokeCount" so a
     *  page that gained ink re-renders and an unchanged one is drawn from cache — e-ink cheap, no
     *  fades, at most three alive. Cleared when the day changes. */
    private val noteThumbCache = HashMap<String, android.graphics.Bitmap>()

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
    /** The ‹ › steppers tucked for this page visit — see onStrokeChanged's quiet-chrome note. */
    private var pagerSteppersTucked = false

    override fun onStrokeChanged(strokes: MutableList<Stroke>) {
        // QUIET CHROME WHILE THE HAND WRITES (field, 08-27 "the pills block the writing area";
        // 08-29 "the pill sort of blocks the usable writing space"). The first stroke of a page
        // visit collapses the WHOLE pill to its › tab, standing where the pill's › stood (review
        // 2026-10-10) — no chrome over the sheet while the hand works; the tab (or any pagination,
        // which rebuilds the fragment) brings the pill back. Two discrete states, no mode.
        if (!pagerSteppersTucked && binding.notePager.visibility == View.VISIBLE) {
            pagerSteppersTucked = true
            binding.notePager.visibility = View.GONE
            binding.notePagerTab.visibility = View.VISIBLE
            binding.notePagerTab.bringToFront()
        }
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
        // Tombstone any text box removed from this page so the union merge can't resurrect it.
        val currentIds = textElements.map { it.elementId.toString() }.toSet()
        calendarDay.textElements
            .filter { it.pageKey == pageKey && it.elementId.toString() !in currentIds }
            .forEach { if (it.elementId.toString() !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(it.elementId.toString()) }
        val others = calendarDay.textElements.filter { it.pageKey != pageKey }
        calendarDay.textElements = (others + textElements).toMutableList()
        harvestTypedTags(pageKey, textElements)
        calendarPattern.updateDay(calendarDay)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
    }

    /**
     * Harvest `#tags` from the page's TYPED text boxes.
     *
     * Tags were only ever harvested from OCR'd handwriting ([harvestTags], behind the Vision pass),
     * so a tag you TYPED was never recorded and pulling that tag up didn't list the note. Tags are
     * the naming system now — "if I search tag, and then select it to search in Notes it'll surface
     * all notes tagged that way" — so marking has to be enough on its own. Waiting on a recognition
     * pass that may never run isn't marking.
     *
     * Typed text needs no recognition, so this runs on every save. The box's own frame is the mark,
     * which is a truer landing spot than an estimated OCR word box: it's exactly the box you typed
     * in. [LedgerTags.recordTag] keeps one occurrence per (tag, date, page), so re-saving — or
     * dragging the box somewhere else — updates the mark instead of piling up rows.
     *
     * Deliberately NOT grams' `cardText`: that's the source author's words, not yours, so harvesting
     * it would let anything you file plant tags in your own vocabulary. Mirrors iOS
     * `PlannerShell.harvestTypedTags`.
     */
    private fun harvestTypedTags(pageKey: String, textElements: List<TextElement>) {
        val ctx = context ?: return
        // [[links]] typed in a box count exactly as #tags typed there do (iOS harvestTypedTags,
        // Michael 2026-10-11: "The links aren't quite working yet") — a link that only worked when
        // handwritten would make the pen a requirement instead of an option. Read PER BOX, so a
        // code fence or a stray bracket in one box can't pair with another's; each target joins
        // the page ONCE per harvest however many boxes repeat it.
        val links = LinkedHashMap<String, com.toolsboox.plugin.calendar.ot.LedgerLinks.Link>()
        for (el in textElements) {
            if (el.text.isBlank()) continue
            val rect = android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + 60f)
            for (tag in com.toolsboox.plugin.calendar.ot.LedgerTags.extract(el.text)) {
                com.toolsboox.plugin.calendar.ot.LedgerTags.recordTag(ctx, currentDate, pageKey, tag, rect)
            }
            for (l in com.toolsboox.plugin.calendar.ot.LedgerLinks.extract(el.text)) {
                links.putIfAbsent(com.toolsboox.plugin.calendar.ot.LedgerLinks.normalise(l.target), l)
            }
        }
        // NEW links only: this runs on every text save, and each recorded link rewrites and
        // re-syncs the whole graph file — a link this page already carries has nothing to add.
        links.values.removeAll {
            com.toolsboox.plugin.calendar.ot.LedgerLinks.isRecorded(ctx, it.target, currentDate, pageKey)
        }
        if (links.isNotEmpty()) {
            com.toolsboox.plugin.calendar.ot.LedgerLinks.recordLinks(ctx, currentDate, pageKey, links.values.toList())
        }
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
        // Tombstone any card removed from this page so the union merge can't resurrect it.
        val currentIds = imageElements.map { it.elementId.toString() }.toSet()
        calendarDay.imageElements
            .filter { it.page == pageKey && it.elementId.toString() !in currentIds }
            .forEach { if (it.elementId.toString() !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(it.elementId.toString()) }
        val others = calendarDay.imageElements.filter { it.page != pageKey }
        calendarDay.imageElements = (others + imageElements).toMutableList()
        // ALL STARS FILES ON THE DROP — the one moment position is evidence of intent. A card
        // whose centre was left inside another band's box adopts that band's kind, then the
        // settle pass pulls every card onto its own kind's shelf ("Correcting a mis-detected
        // kind still works — drag it across and it stays"). This mutates the same instances the
        // element layer holds, and every commit path repaints after this callback, so the
        // settled positions are what land on screen; the template redraw below re-sizes the
        // bands and re-teaches the hit-test map.
        if (pageKey == CalendarDayPageIntake.INTAKE_PAGE &&
            CalendarDayPageIntake.fileAllStarsDrop(calendarDay)
        ) redrawIntakePage()
        calendarPattern.updateDay(calendarDay)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
    }

    /** "Where used": every day + page a gram with the same content is placed on — the rhizomatic web. */
    /** Re-draw a text card's face from edited words — the quote card, taped like when it was placed. */
    override fun renderCard(text: String, element: ImageElement): android.graphics.Bitmap? {
        val face = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
            text, element.sourceLabel.ifBlank { null }, null, 1080, 0)
        return com.toolsboox.ot.CardTreatment.card(face)
    }

    /** Grid Notes snaps dragged objects to its 50px grid; other pages don't snap. Asked of the
     *  STORE, not of the literal key: a named grid ("grid-1753…") draws the same squares, so it must
     *  snap to them — the alternative is a document that stops behaving like a grid the moment you
     *  title it, which is the exact bug the template branch beside it carries a note about. */
    override fun snapStep(): Float =
        if (com.toolsboox.plugin.calendar.ot.GridPageStore.isMine(notePage)) 50f else 0f

    /** This page's element address, for the connection graph. */
    private fun elementUri(elementId: java.util.UUID): String =
        com.toolsboox.ot.LedgerUri.element(currentDate.toString(), notePage ?: "default", elementId.toString())

    /**
     * The connectors to draw: every edge whose BOTH ends are elements on THIS page.
     *
     * A hand-drawn link is a real connection, so it lives in the same graph as everything else and
     * surfaces in the rhizome and the Map; here we just pull back the ones that happen to join two
     * things you can see at once, to draw the line between them.
     */
    override fun pageConnectors(): List<Pair<java.util.UUID, java.util.UUID>> {
        if (!::calendarDay.isInitialized) return emptyList()
        val pk = notePage ?: "default"
        // Both images and text boxes can be an end of a connector, so both count as ids here.
        val ids = (calendarDay.imageElements.filter { it.page == pk }.map { it.elementId.toString() } +
            calendarDay.textElements.filter { it.pageKey == pk }.map { it.elementId.toString() }).toSet()
        if (ids.isEmpty()) return emptyList()
        val here = "${com.toolsboox.ot.LedgerUri.page(currentDate.toString(), notePage ?: "default")}#"
        return com.toolsboox.plugin.calendar.ot.ConnectionStore.loadAll(requireContext())
            .asSequence()
            .filter { !it.isDeleted && it.from.startsWith(here) && it.to.startsWith(here) }
            .mapNotNull { e ->
                val a = e.from.substringAfterLast('#'); val b = e.to.substringAfterLast('#')
                if (a in ids && b in ids)
                    runCatching { java.util.UUID.fromString(a) to java.util.UUID.fromString(b) }.getOrNull()
                else null
            }.toList()
    }

    /** Draw a link from this gram to another on the page — a real edge, shown as a line. */
    override fun onImageConnect(element: ImageElement) =
        // A shape connects AS the text it circled — the words become the edge, not the shape's name.
        beginConnect(element.elementId,
            (if (element.distortable) enclosedText(element).ifBlank { element.sourceLabel } else element.sourceLabel)
                .ifBlank { "Card" })

    override fun onTextConnect(element: com.toolsboox.da.TextElement) =
        beginConnect(element.elementId, element.text.take(40).ifBlank { "Text" })

    /**
     * Gather a connected GROUP into a synthesis.
     *
     * A group is a cluster of things you joined by hand on the page — regions you circled, cards,
     * text, all connected. This walks the whole cluster from the tapped element, collects what
     * each node carries (the words a shape gathered, a card's label, a note's text), and drops
     * them onto a Synthesize page as text to work from. Separate clusters on one sheet become
     * separate syntheses — which is the whole point of drawing them apart.
     */
    /** "✒ Synth this…" on a held gram — only where the correspondence lives (synth pages, this
     *  build; any note when P3 opens the doors). Reply cards are re-Synthable by ruling. */
    override fun imageSynthRow(element: ImageElement): com.toolsboox.ot.LedgerContextMenu.Item? =
        // ONE RULE EVERYWHERE (the two-mode model): on the conversation page the card is the
        // subject and the reply lands in place; on any other page the card drive-bys into its
        // own note (the dedupe guard opens an existing conversation instead of re-minting).
        com.toolsboox.ot.LedgerContextMenu.Item("❖ Synthesize…") {
            // THE ENGINES WITH THEIR PICK DOORS (Michael, 09-14: "I selected synthesize from the
            // long hold on a gram pick and it presented me with the list of personas and none of
            // them match that workflow"): the same list the rail's ❖ offers — grams from my
            // sites, a topic, group picks — plus the engines, run on this pick's own words. The
            // Correspondent's reply, which used to be this row's only act, is the last door.
            val ctx = requireContext()
            val words = listOf(element.cardText, element.mediaTitle, element.sourceLabel, element.sourceLink)
                .firstOrNull { it.isNotBlank() }.orEmpty()
            // The door wears the page's voice — the page's voice is who answers it.
            val onSynth = onSynthPage()
            val voice = if (onSynth) synthVoiceOn(notePage ?: "").persona else com.toolsboox.plugin.calendar.ot.SynthVoice.CORRESPONDENT
            com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
                ctx, "Synthesize this pick", present = { showModal(it) },
                onTopic = { t -> proposeTopic(t) },
                onSiteLinks = { gramsFromSites() },
                onGroup = { groupPicksIntoSections() },
                onCorrespond = {
                    if (onSynthPage()) synthCorrespond(subjects = listOf(element))
                    else driveBySynthCards(listOf(element))
                },
                correspondLabel = "✒  " + com.toolsboox.plugin.calendar.ot.SynthVoice.correspondRow(voice),
            ) { e -> runEngine(e, words) }
        }

    /** "❖ Synth with ▸" on a held pick (Michael, 2026-10-12, ruling 1): the voice list, then — on
     *  the conversation page — the page takes that voice and the pick is the subject, in place (a
     *  single reply card threads); anywhere else it is the drive-by in that voice. */
    override fun imageSynthWithRow(element: ImageElement): com.toolsboox.ot.LedgerContextMenu.Item? =
        com.toolsboox.ot.LedgerContextMenu.Item("❖ Synth with ▸") {
            showSynthVoiceList(SYNTH_WITH_TITLE) { voice ->
                if (onSynthPage()) {
                    setSynthPageVoice(voice)
                    synthCorrespond(subjects = listOf(element), voice = voice)
                } else driveBySynthCards(listOf(element), voice = voice)
            }
        }

    /** "☑ Make a task…" on a held pick — the card's words become a task, through the very
     *  machinery the page selection's ☑ Task uses ([dispatchTask]), so the confirm sheet, the
     *  date parsing and the CalDAV push are one implementation rather than two that drift.
     *
     *  The pack carries no strokes: a card is not ink on this page, it is an object placed on it,
     *  so `itemWithText` falls to its textual shape — which is the same shape an emailed star
     *  mints, and the right one here. */
    override fun imageTaskRow(element: ImageElement): com.toolsboox.ot.LedgerContextMenu.Item? =
        com.toolsboox.ot.LedgerContextMenu.Item("☑ Make a task…") {
            lifecycleScope.launch {
                val creds = aiCreds()
                val material = withContext(Dispatchers.IO) {
                    if (creds != null) runCatching { resolveCardMaterial(element, creds) }.getOrDefault(element.cardText)
                    else element.cardText.ifBlank { element.sourceLabel }
                }
                if (!isAdded) return@launch
                val text = material.trim().ifBlank { element.sourceLabel.trim() }
                if (text.isBlank()) {
                    showMessage("That card holds nothing readable to make a task from.", binding.root)
                    return@launch
                }
                dispatchTask(DispatchPack(text, ByteArray(0), emptyList()))
            }
        }

    /** "🔎 Ask about this…" on a held pick — the card's material to Ask, provenance riding
     *  (AskBridge, same door the lasso's Ask uses). */
    override fun imageAskRow(element: ImageElement): com.toolsboox.ot.LedgerContextMenu.Item? =
        com.toolsboox.ot.LedgerContextMenu.Item("🔎 Ask about this…") {
            lifecycleScope.launch {
                val creds = aiCreds()
                val material = withContext(Dispatchers.IO) {
                    if (creds != null) resolveCardMaterial(element, creds)
                    else element.cardText.ifBlank { element.sourceLabel }
                }
                if (!isAdded) return@launch
                com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
                    this@CalendarDayFragment,
                    material.ifBlank { element.sourceLabel.ifBlank { "this card" } },
                    element.sourceLabel.ifBlank { "a card on the page" },
                    com.toolsboox.ot.LedgerUri.page(currentDate.toString(), notePage ?: "default"),
                    "the Ledger")
            }
        }

    override fun onImageSynthesizeGroup(element: ImageElement) {
        val here = "${com.toolsboox.ot.LedgerUri.page(currentDate.toString(), notePage ?: "default")}#"
        val edges = com.toolsboox.plugin.calendar.ot.ConnectionStore.loadAll(requireContext())
            .filter { !it.isDeleted && it.from.startsWith(here) && it.to.startsWith(here) }
        // Walk the connected component from this element.
        val start = elementUri(element.elementId)
        val adj = HashMap<String, MutableSet<String>>()
        val label = HashMap<String, String>()
        for (e in edges) {
            adj.getOrPut(e.from) { mutableSetOf() }.add(e.to)
            adj.getOrPut(e.to) { mutableSetOf() }.add(e.from)
            if (e.fromLabel.isNotBlank()) label[e.from] = e.fromLabel
            if (e.toLabel.isNotBlank()) label[e.to] = e.toLabel
        }
        val seen = linkedSetOf(start); val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            adj[n]?.forEach { if (seen.add(it)) queue.add(it) }
        }
        // Each node's words: its recorded label, else what it carries now (circled text, card text).
        val lines = seen.mapNotNull { uri ->
            label[uri]?.takeIf { it.isNotBlank() }
                ?: uri.substringAfterLast('#').let { id ->
                    calendarDay.imageElements.firstOrNull { it.elementId.toString() == id }
                        ?.let { enclosedText(it).ifBlank { it.sourceLabel } }
                        ?: calendarDay.textElements.firstOrNull { it.elementId.toString() == id }?.text
                }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        if (lines.size < 2) {
            showMessage("Connect a few things into a group first, then synthesize it.", binding.root); return
        }
        // Drop the group's material onto today's Synthesize page and go there to work it.
        placeTextBoxes(lines, "synthesize", refresh = false)
        showMessage("Sent ${lines.size} to Synthesize.", binding.root)
        CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "synthesize")
    }

    /** The two ends were touched — record the edge and draw the line. */
    override fun onConnectComplete(fromId: java.util.UUID, toId: java.util.UUID, fromLabel: String, toLabel: String) {
        com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
            requireContext(), elementUri(fromId), elementUri(toId),
            com.toolsboox.plugin.calendar.da.v2.Connection.ABOUT,
            fromLabel = fromLabel, toLabel = toLabel)
        redrawSurface()
        showMessage("Linked.", binding.root)
        // A shape can circle HANDWRITING, not just text boxes. If either end is a shape whose
        // region holds ink and no typed text, read that ink and let it become the edge's words —
        // so a capsule drawn around a scrawled note carries the note, the same as around a label.
        refineConnectionWithInk(fromId, toId)
    }

    private fun refineConnectionWithInk(fromId: java.util.UUID, toId: java.util.UUID) {
        val creds = aiCreds() ?: return
        val strokes = currentPageStrokes()
        if (strokes.isEmpty()) return
        for (id in listOf(fromId, toId)) {
            val el = calendarDay.imageElements.firstOrNull { it.elementId == id && it.distortable } ?: continue
            if (enclosedText(el).isNotBlank()) continue   // typed text already spoke for it
            val rect = android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + el.height)
            val inside = strokes.filter { s ->
                val pts = s.strokePoints
                if (pts.isEmpty()) false else {
                    val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
                    val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
                    rect.contains(cx, cy)
                }
            }
            if (inside.isEmpty()) continue
            lifecycleScope.launch {
                val ocr = withContext(Dispatchers.IO) {
                    val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(inside, rect, 1200)
                    com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
                }
                if (!ocr.isNullOrBlank() && isAdded) {
                    // Re-connect (idempotent by ends) to update just this end's label with the read.
                    com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                        requireContext(), elementUri(fromId), elementUri(toId),
                        com.toolsboox.plugin.calendar.da.v2.Connection.ABOUT,
                        fromLabel = if (id == fromId) ocr.trim().take(60) else "",
                        toLabel = if (id == toId) ocr.trim().take(60) else "")
                    redrawSurface()
                }
            }
        }
    }

    /**
     * A link gram that points at one of your own notes.
     *
     * Deliberately the same OBJECT as a web link — a card with `sourceLink`, movable, connectable,
     * tappable — because the difference between "a thing I read" and "a thing I wrote" is a fact
     * about the address, not about the kind of thing it is. Making internal links a separate
     * species would mean two of everything downstream: two cards, two tap behaviours, two ways to
     * appear on the Map.
     */
    private fun placeNoteLink(
        link: com.toolsboox.plugin.calendar.ot.LedgerLinks.Link, title: String, cx: Float, cy: Float
    ) {
        val ctx = requireContext()
        val uri = com.toolsboox.plugin.calendar.ot.LedgerLinks.linkUri(link.target)
        val label = title.ifBlank { link.shown }
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val bmp = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                    uri, label, "note")
                val reloaded = DayLocks.withDay(currentDate) {
                    runCatching {
                        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                            calendarDayService, documentsRoot(), bmp, currentDate, notePage ?: "default",
                            sourceLink = uri, sourceLabel = label, cardText = title)
                    }
                    runCatching {
                        calendarDayService.loadOrNull(documentsRoot(), currentDate, java.util.Locale.getDefault())
                    }.getOrNull()
                }
                // The join, so a pasted link and a handwritten one land in the same graph.
                runCatching {
                    com.toolsboox.plugin.calendar.ot.LedgerLinks.record(
                        ctx, currentDate, notePage ?: "default", "[[${link.target}]]")
                }
                reloaded
            }
            if (!isAdded) return@launch
            // Additive refresh, exactly as the web-link path does it: a pen-up save racing the
            // placement writes the fragment's older imageElements to disk, so the in-memory list
            // is the surviving truth — fold the placed card in and let the next save persist it.
            if (::calendarDay.isInitialized && fresh != null) {
                val have = calendarDay.imageElements.map { it.elementId.toString() }.toSet()
                fresh.imageElements
                    .filter { it.elementId.toString() !in have && it.elementId.toString() !in calendarDay.deletedElementIds }
                    .forEach { calendarDay.imageElements.add(it) }
                setImageElements(calendarDay.imageElements.filter { it.page == (notePage ?: "default") }.toMutableList())
            }
            showMessage("Linked · ${link.shown}", binding.root)
        }
    }

    /**
     * Intake a link as an object.
     *
     * A website, a video, a podcast — all the same thing: paste the address, and it lands on the
     * page as a card you can move, connect and tap back to open, not a line of blue text. The kind
     * is guessed from the host (read / watch / listen) the way a shared link is, and you can file
     * it to the matching Later lane at the same time — placed AND filed, or just placed.
     */
    override fun onIntakeLink(cx: Float, cy: Float) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val urlIn = android.widget.EditText(ctx).apply {
            hint = "A link, or [[a note]]"; setSingleLine()
        }
        val titleIn = android.widget.EditText(ctx).apply { hint = "Title (optional)"; setSingleLine() }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
            addView(urlIn); addView(titleIn)
        }
        // Guarded: a pasted link and its title are work being typed — a stray touch outside
        // must not throw them away. Cancel and the back gesture remain the ways out.
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Intake a link")
            .setView(box)
            .setPositiveButton("Place") { _, _ -> placeLinkObject(urlIn.text.toString().trim(), titleIn.text.toString().trim(), cx, cy, alsoFile = false) }
            .setNeutralButton("Place & file to Later") { _, _ -> placeLinkObject(urlIn.text.toString().trim(), titleIn.text.toString().trim(), cx, cy, alsoFile = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    private fun placeLinkObject(rawUrl: String, title: String, cx: Float, cy: Float, alsoFile: Boolean) {
        var url = rawUrl.trim()
        if (url.isBlank()) return
        // AN ADDRESS INSIDE THE LEDGER IS STILL AN ADDRESS. `[[Some note]]` makes a link gram
        // pointing at one of your own notes instead of at the web — the same card, the same tap-
        // to-open, and the same join into the rhizome that a handwritten [[link]] makes, so a note
        // you linked to by pasting and a note you linked to with the pen are the same edge.
        //
        // Only the bracket form is treated this way. Guessing that an un-bracketed word was meant
        // as a note rather than a bare domain would be wrong about "obsidian.md" forever.
        val asNote = com.toolsboox.plugin.calendar.ot.LedgerLinks.extract(url).firstOrNull()
        if (asNote != null) {
            placeNoteLink(asNote, title, cx, cy)
            return
        }
        if (!url.startsWith("http", ignoreCase = true)) url = "https://$url"
        val kind = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.inferKind(url)
        val label = title.ifBlank { url }
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val bmp = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(url, title, kind)
                // The placement is a load-modify-save of the same day file the open page
                // re-saves wholesale on every pen-up — serialize the cycle, and reload while
                // still holding the lock so the refresh below can't miss the element just
                // written. Day files can run tens of MB, so the reload stays off Main too.
                val reloaded = DayLocks.withDay(currentDate) {
                    runCatching {
                        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                            calendarDayService, documentsRoot(), bmp, currentDate, notePage ?: "default",
                            sourceLink = url, sourceLabel = label, cardText = title)
                    }
                    runCatching {
                        calendarDayService.loadOrNull(documentsRoot(), currentDate, java.util.Locale.getDefault())
                    }.getOrNull()
                }
                if (alsoFile) runCatching {
                    com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                        requireContext(), currentDate, kind, url, title.ifBlank { null })
                }
                reloaded
            }
            // Refresh so the new card shows on the page you're on. Additive, not wholesale:
            // a pen-up save racing the placement writes the fragment's older imageElements
            // to disk, so the in-memory list is the surviving truth — fold the placed card
            // in and let the next save re-persist it.
            if (::calendarDay.isInitialized && fresh != null) {
                val have = calendarDay.imageElements.map { it.elementId.toString() }.toSet()
                fresh.imageElements
                    .filter { it.elementId.toString() !in have && it.elementId.toString() !in calendarDay.deletedElementIds }
                    .forEach { calendarDay.imageElements.add(it) }
                setImageElements(calendarDay.imageElements.filter { it.page == (notePage ?: "default") }.toMutableList())
            }
            showMessage(if (alsoFile) "Placed, and filed to Later." else "Link placed.", binding.root)
        }
    }

    /** Open Feed Ledger showing only the feed this gram was clipped from. */
    override fun onImageGoToFeed(element: ImageElement) {
        if (element.sourceFeed.isBlank()) return
        com.toolsboox.plugin.feeds.ui.FeedSelection.filterFeedTitle = element.sourceFeed
        findNavController().navigate(R.id.action_to_feeds)
    }

    /** Everything this gram joins, asked from the gram's own end. */
    override fun onImageRhizome(element: ImageElement) {
        val uri = com.toolsboox.plugin.calendar.ot.LegacyEdges.adopt(requireContext(), element, currentDate)
        openRhizome(uri, element.sourceLabel.ifBlank { "This pick" })
    }

    /** Everything this text box joins — for a dropped link, the thing it points at. */
    override fun onTextRhizome(element: com.toolsboox.da.TextElement) {
        val uri = com.toolsboox.plugin.calendar.ot.LegacyEdges.adopt(requireContext(), element, currentDate)
        openRhizome(uri, element.text.take(60).ifBlank { "This text" })
    }

    private fun openRhizome(uri: String, label: String) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to uri,
                LedgerRhizomeFragment.ARG_LABEL to label
            )
        )
    }

    override fun onImageWhereUsed(element: ImageElement) {
        val key = contentKey(element.gramId, element.data)
        if (key.isBlank()) return
        lifecycleScope.launch {
            val places = withContext(Dispatchers.IO) { gramPlacements(key) }
            val ctx = context ?: return@launch
            if (places.isEmpty()) {
                android.widget.Toast.makeText(ctx, "Not placed anywhere else yet", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = places.map { p ->
                "${p.date}  ·  ${if (p.page == "day") "Day page" else p.page.replaceFirstChar { it.uppercase() }}"
            }.toTypedArray()
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Where used · ${places.size}")
                .setItems(labels) { _, which ->
                    CalendarNavigator.toDayPage(this@CalendarDayFragment, places[which].date, CalendarDay.DEFAULT_STYLE)
                }
                .setNegativeButton("Close", null)
                .showModal()
        }
    }

    /** What the ghost rows may draw RIGHT NOW: the parked still-open answer, today only.
     *  Yesterday's page is history — nothing ghosts onto it. */
    private fun openTasksForPanel(): List<com.toolsboox.plugin.calendar.ot.OpenTasks.Open> =
        if (currentDate == LocalDate.now())
            com.toolsboox.plugin.calendar.ot.OpenTasks.cachedFor(currentDate).orEmpty()
        else emptyList()

    /** Re-earn the still-open answer in the background — the render path never walks the disk.
     *  When the answer moves, the page re-renders with the new ghost rows. */
    private fun warmOpenTasks(events: List<CalendarEvent>) {
        if (currentDate != LocalDate.now() || openTasksCooking) return
        openTasksCooking = true
        val root = documentsRoot()
        val day = currentDate
        val todayItems = if (::calendarDay.isInitialized) calendarDay.ledgerItems.toList() else emptyList()
        lifecycleScope.launch {
            val open = withContext(Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.OpenTasks.fresh(calendarDayService, root, day, todayItems)
                }.onFailure { Timber.w(it, "open tasks: gather failed") }.getOrNull()
            }
            openTasksCooking = false
            if (!isAdded || open == null) return@launch
            // Redraw only when the rows would actually change — a same-answer walk must not
            // cost an e-ink flash.
            if (day == currentDate && notePage == null && open != openTasksShown) {
                runCatching { renderPage(calendarDay, calendarPattern, events) }
            }
        }
    }

    /** A tapped ghost row: the two verbs a still-open task needs from here. Titled with WHAT
     *  this row is — "Still open since <date>" — because the menu is where a ghost gets to
     *  explain itself (Michael's 08-12 page: the ghost rows must self-explain). The task's own
     *  words ride on the second line so you still know which task you tapped. */
    private fun ghostMenu(open: com.toolsboox.plugin.calendar.ot.OpenTasks.Open) {
        val since = open.sourceDay.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        showIconMenu("Still open since $since\n${open.item.text.take(80)}", listOf(
            "✓  Done" to { markGhostDone(open) },
            "↗  Open its day" to {
                CalendarNavigator.toDayPage(this, open.sourceDay, CalendarDay.DEFAULT_STYLE)
            }
        ))
    }

    /** Mark a still-open task done ON ITS OWN DAY — the file the task actually lives in. */
    private fun markGhostDone(open: com.toolsboox.plugin.calendar.ot.OpenTasks.Open) {
        val root = documentsRoot()
        val appCtx = requireContext().applicationContext
        val events = lastRenderedEvents
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val d = open.sourceDay
                    val loc = java.util.Locale.getDefault()
                    val item = DayLocks.withDay(d) {
                        val cd = calendarDayService.loadForWriteOrThrow(root, d, null, loc)
                        val item = cd.ledgerItems.firstOrNull { it.id == open.item.id } ?: return@runCatching false
                        item.done = true
                        // The same cross-day save the Tasks list's checkbox makes — no pattern
                        // rewrite; done is monotonic through the merge either way.
                        calendarDayService.save(root, d, cd)
                        item
                    }
                    // The network push waits outside the day lock (a suspension inside it is a compile error).
                    runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(appCtx, item) }
                    true
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            if (done) {
                com.toolsboox.plugin.calendar.ot.OpenTasks.invalidate()
                showMessage("✓ ${open.item.text.take(60)}", binding.root)
                warmOpenTasks(events)
            } else showMessage("Couldn't reach that task — open its day instead.", binding.root)
        }
    }

    // ------------------------------------------------------------------
    // The Synthesize engines — the pressure chamber of the Timeline.
    // Page-level (long-press empty canvas on the Synthesize page): the day's gathered
    // material runs through one of three transformations. Object-level ("Synthesize…"
    // on any text box, any page): the same engines on just that one picking.
    // ------------------------------------------------------------------

    /** 🏷 Tag the OPEN page — the page-shaped write ([LedgerTags.recordTag]), so the tag
     *  index lists this exact page and tapping the tag walks you back to it. The vocabulary
     *  leads (the TagPicker lesson: you choose from what you already say); typing is for a
     *  genuinely new name. */
    private fun tagCurrentPage(pageKey: String) {
        val ctx = context ?: return
        val existing = com.toolsboox.plugin.calendar.ot.LedgerTags.list(ctx)
            .sortedByDescending { it.occurrences.maxOfOrNull { o -> o.first } ?: java.time.LocalDate.MIN }
            .take(10).map { it.tag }
        val input = android.widget.EditText(ctx).apply { hint = "New tag…"; isSingleLine = true }
        fun apply(tag: String) {
            val clean = tag.trim().removePrefix("#").lowercase()
            if (clean.isBlank()) return
            com.toolsboox.plugin.calendar.ot.LedgerTags.recordTag(ctx, currentDate, pageKey, clean)
            showMessage("🏷 #$clean \u2192 this page", binding.root)
        }
        val b = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("🏷  Tag this page")
            .setView(android.widget.FrameLayout(ctx).apply { setPadding(48, 8, 48, 0); addView(input) })
            .setPositiveButton("Tag") { _, _ -> apply(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
        val choices = (listOf("☆ Outline") + existing.map { "#$it" }).toTypedArray()
        b.setItems(choices) { _, i -> apply(if (i == 0) "outline" else existing[i - 1]) }
        showModal(b.create())
    }

    /** Tag one gram, at its page address — the same `ledger://<date>/<page>#<element>` shape iOS
     *  uses, so an edge made on either device lands on the same node. */
    override fun onTagElement(element: com.toolsboox.da.ImageElement) {
        val ctx = context ?: return
        val uri = "ledger://$currentDate/${notePage ?: "default"}#${element.elementId}"
        com.toolsboox.ot.TagPicker.show(
            ctx, uri,
            element.cardText.ifBlank { element.sourceLabel }.ifBlank { "gram" },
            showModal = { showModal(it) }
        )
    }

    /**
     * Send the whole page to be read and tagged.
     *
     * The material is what the page carries in WORDS — its typed boxes and the card text on its
     * grams. Handwriting that hasn't been captured contributes nothing, which is honest: the model
     * can only read what has actually been read.
     *
     * A Pickings board goes in as a BOARD, so the prompt asks what the collection is about rather
     * than what any one card says — the tag that names a gathering is the one worth having.
     */
    /**
     * The typed Write surface: a title, a Markdown body, and the way out.
     *
     * A dialog rather than a whole screen, because that is this codebase's modal idiom and because
     * a full-screen editor on e-ink buys nothing a large dialog doesn't. Ink and text don't convert
     * into one another — the strokes stay in the day file and the draft in its own store, so
     * leaving one for the other and coming back finds both as they were. A conversion would have to
     * guess, and guessing loses work.
     */
    /**
     * What you posted on THIS day, out of the Field Ledger archive — and the two things you can do
     * with one.
     *
     * Each post also JOINS its day in the connection graph, which is the half that makes the
     * archive *inform* the tags rather than sit beside them: `journal://<id>` ↔ the day's page is an
     * edge in the same graph tags live in, so a post from 2011 is a node the rhizome walks, and
     * anything tagged on that day is one hop from what you posted then. `connect` derives edge ids
     * from their endpoints, so revisiting a day is idempotent and the synced sidecar doesn't grow.
     *
     * An archive post IS a gram — a face with a source behind it is the whole gram contract — so it
     * goes where grams go: onto a Pickings board to keep, or into Star Sort to be triaged with
     * everything else that came in.
     */
    /**
     * A card face for an archive post: its words, its date, and where it came from.
     *
     * Drawn here rather than reaching into the feed plugin's private card builder — that one is
     * wired to an article and a pad, and prising it apart for one caller would leave two things to
     * keep in step. [com.toolsboox.ot.CardTreatment.card] then gives it the same paper and tape
     * every other placed card wears, so an archive post sits on a board looking like its neighbours.
     */
    private fun archiveCardFace(item: com.toolsboox.plugin.calendar.ot.JournalItem): android.graphics.Bitmap {
        val w = 760
        val pad = 44f
        val words = item.title.ifBlank { item.dateString }
        val body = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); textSize = 34f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
        }
        val foot = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF666666.toInt(); textSize = 22f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
        }
        val layout = android.text.StaticLayout.Builder
            .obtain(words, 0, words.length, body, (w - pad * 2).toInt())
            .setMaxLines(8).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val h = (pad * 2 + layout.height + 44).toInt().coerceAtLeast(200)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        c.save(); c.translate(pad, pad); layout.draw(c); c.restore()
        c.drawText("${item.dateString}  ·  Field Ledger", pad, h - pad / 2, foot)
        return com.toolsboox.ot.CardTreatment.card(bmp)
    }

    private fun showDayArchive() {
        val ctx = context ?: return
        showMessage("Reading the archive…", binding.root)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.JournalCorpus.refreshIfStale(ctx)
                com.toolsboox.plugin.calendar.ot.JournalCorpus.itemsOn(ctx, currentDate)
            }
            if (!isAdded) return@launch
            if (found.isEmpty()) {
                showMessage("Nothing posted on this day.", binding.root); return@launch
            }
            val dayUri = "ledger://$currentDate/default"
            withContext(Dispatchers.IO) {
                for (item in found) {
                    com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                        ctx, com.toolsboox.ot.LedgerUri.journal(item.id), dayUri,
                        fromLabel = item.title.ifBlank { "archive post" }, toLabel = item.dateString)
                }
            }
            val labels = found.map { "📓  ${it.title.ifBlank { it.dateString }}" }.toTypedArray()
            showModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Posted on $currentDate")
                .setItems(labels) { _, which -> archiveItemActions(found[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .create())
        }
    }

    private fun archiveItemActions(item: com.toolsboox.plugin.calendar.ot.JournalItem) {
        val ctx = context ?: return
        val rows = arrayOf("🌐  Open the post", "❝  Make it a picking", "★  Send to All Stars", "🏷  Tag…")
        showModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(item.title.ifBlank { item.dateString })
            .setItems(rows) { _, which ->
                when (which) {
                    0 -> runCatching {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.permalink)))
                    }
                    1 -> placeArchiveGram(item, com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY, "")
                    2 -> placeArchiveGram(item, CalendarDayPageIntake.INTAKE_PAGE, "read")
                    else -> com.toolsboox.ot.TagPicker.show(
                        ctx, com.toolsboox.ot.LedgerUri.journal(item.id),
                        item.title.ifBlank { item.dateString },
                        showModal = { showModal(it) })
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** Render an archive post as a card face and file it — the shared placement path, not a fork. */
    private fun placeArchiveGram(
        item: com.toolsboox.plugin.calendar.ot.JournalItem, boardKey: String, intakeKind: String
    ) {
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val face = archiveCardFace(item)
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, com.toolsboox.ot.LedgerPaths.documentsRoot(appCtx), face,
                        currentDate, boardKey,
                        sourceLink = item.permalink, sourceLabel = item.dateString,
                        treatment = false, cardText = item.title, intakeKind = intakeKind)
                    true
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            showMessage(if (ok) "Placed." else "Couldn't place that.", binding.root)
            if (ok) presenter.load(this@CalendarDayFragment, binding, currentDate,
                PagePrefs.settingHour(requireContext()), locale)
        }
    }

    private fun showWriteTextEditor() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val pageKey = notePage ?: "write"
        val existing = com.toolsboox.plugin.calendar.ot.WriteDraftStore.draft(ctx, currentDate, pageKey)

        val titleIn = android.widget.EditText(ctx).apply {
            hint = "Title"; setSingleLine(); textSize = 20f
            setText(existing?.title.orEmpty())
        }
        val bodyIn = android.widget.EditText(ctx).apply {
            hint = "Write in Markdown…"
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(existing?.markdown.orEmpty())
            textSize = 16f
            minLines = 14
            isVerticalScrollBarEnabled = true
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
            addView(titleIn)
            addView(bodyIn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt()))
        }

        fun persist() {
            val title = titleIn.text.toString().trim()
            val md = bodyIn.text.toString()
            com.toolsboox.plugin.calendar.ot.WriteDraftStore.put(ctx, currentDate, pageKey, title, md)
            // Tags go through `record` — a Write page IS a ledger page, so its tags belong in the
            // tag index as openable rows rather than only as graph edges.
            com.toolsboox.plugin.calendar.ot.LedgerTags.record(
                ctx, currentDate, pageKey, title + "\n" + md)
            // …and the same text through the link harvester. [[Wiki links]] ride the tag pass
            // because they are the same act — a token written into the page that means "this
            // belongs with that" — and they join the same rhizome, so a link written here is an
            // edge on the Map without anything else being told about it.
            com.toolsboox.plugin.calendar.ot.LedgerLinks.record(
                ctx, currentDate, pageKey, title + "\n" + md)
        }

        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Write")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Save") { _, _ -> persist(); showMessage("Saved.", binding.root) }
            .setNeutralButton("Send…") { _, _ -> persist(); shareEssay() }
            .setNegativeButton("Cancel", null)
            .create()
        // The guarded door: a whole essay can be sitting in this field unsaved — a palm outside
        // the dialog must not cost it. Cancel and the back gesture remain the ways out.
        showGuardedModal(dialog)
    }

    private fun suggestTagsForPage() {
        val ctx = context ?: return
        val creds = aiCreds() ?: return
        val pageKey = notePage ?: "default"
        val uri = "ledger://$currentDate/$pageKey"

        val parts = mutableListOf<String>()
        if (::calendarDay.isInitialized) {
            calendarDay.textElements.filter { it.pageKey == pageKey && it.text.isNotBlank() }
                .forEach { parts.add(it.text) }
            calendarDay.imageElements.filter { it.page == pageKey && it.cardText.isNotBlank() }
                .forEach { parts.add(it.cardText) }
        }
        val text = parts.joinToString("\n\n")
        if (text.isBlank()) { showMessage("Nothing written here to read yet.", binding.root); return }

        val subject = if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(pageKey)) {
            val name = runCatching {
                com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, currentDate)
                    .firstOrNull { it.key == pageKey }?.name
            }.getOrNull() ?: "Pickings"
            com.toolsboox.plugin.calendar.ot.TagSuggest.Subject.Board(name)
        } else {
            com.toolsboox.plugin.calendar.ot.TagSuggest.Subject.Page(pageKey)
        }

        showMessage("Reading the page…", binding.root)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.TagSuggest.suggest(
                    ctx, text, subject, creds.first, creds.second, creds.third)
            }
            if (!isAdded) return@launch
            if (found.isEmpty()) { showMessage("No tags suggested.", binding.root); return@launch }
            showTagSuggestions(uri, pageKey, found)
        }
    }

    /**
     * Review what the model proposed, then apply what you agree with.
     *
     * A review step rather than silent autotagging: these tags are how the ledger names its own
     * material, and a name applied without being read is a name you don't know you have. Everything
     * arrives ticked so agreeing is quick — but the yes is yours.
     */
    private fun showTagSuggestions(
        uri: String, label: String,
        found: List<com.toolsboox.plugin.calendar.ot.TagSuggest.Suggestion>
    ) {
        val ctx = context ?: return
        val labels = found.map { s ->
            "#${s.tag}" + (if (s.isNew) "  (new)" else "") +
                (if (s.why.isNotBlank()) "\n${s.why}" else "")
        }.toTypedArray()
        val checked = BooleanArray(found.size) { true }
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Suggested tags")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Apply") { _, _ ->
                found.forEachIndexed { i, s ->
                    if (checked[i]) {
                        // A PAGE gets a page occurrence, so it lists in the tag index as a row you
                        // can open — not just an edge in the graph.
                        com.toolsboox.plugin.calendar.ot.LedgerTags.record(
                            ctx, currentDate, label, "#${s.tag}")
                    }
                }
                showMessage("Tagged.", binding.root)
            }
            .setNegativeButton("Not now", null)
            .create()
        showModal(dialog)
    }

    override fun extraCreationGroups(cx: Float, cy: Float): List<List<com.toolsboox.ot.LedgerContextMenu.Item>> {
        val ctx = context ?: return emptyList()
        // Every making surface gets the way to reach BACKWARD for material you already gathered.
        // Intake got this door first (hold a quarter); it belongs on the pages where you actually
        // make something out of the day, which is all of them but the day page itself.
        val bring = if (notePage != null) listOf(listOf(
            com.toolsboox.ot.LedgerContextMenu.Item("❝  Bring in a picking…") { bringPickingOntoPage(cx, cy) }
        )) else emptyList()
        // ⟡ Synth in the page long-hold (his 08-30 roster) — the summon beside the page's own
        // acts, on the surfaces hosting the correspondence (P3 opens every note).
        // RESTORED (2026-10-12): the synth early-return below dropped this row, so no Synthesize
        // page ever offered it. It wears the page's voice — "❖  Synth — Spiral writes back".
        val synth = if (onSynthPage())
            listOf(listOf(com.toolsboox.ot.LedgerContextMenu.Item(
                "❖  " + com.toolsboox.plugin.calendar.ot.SynthVoice.writeBackRow(synthVoiceOn(notePage ?: "").persona)) {
                synthCorrespond()
            })) else emptyList()
        // Tag the PAGE itself — a Pickings board, a synthesis, or the day. Distinct from tagging
        // the grams on it: a board's tag names what the COLLECTION is about.
        val tagPage = listOf(listOfNotNull(
            if (aiCreds() != null) com.toolsboox.ot.LedgerContextMenu.Item("✨  Suggest tags…") {
                suggestTagsForPage()
            } else null,
            com.toolsboox.ot.LedgerContextMenu.Item("🏷  Tag this page…") {
                com.toolsboox.ot.TagPicker.show(
                    ctx, "ledger://$currentDate/${notePage ?: "default"}",
                    notePage ?: currentDate.toString(),
                    showModal = { showModal(it) })
            }
        ))
        // NO SECOND SEND DOOR (his 08-31 field read: "there are also two send/export doors in the
        // same menu to the same panel"). This group used to add "→ Send / Export…" for every note
        // page — but the roster's own "↥ Send / export…" calls `onPageSendExport()`, which THIS
        // fragment overrides to `showSendExport()`: the very same sheet. Two rows, two glyphs, two
        // spellings, one destination. The base roster keeps the seat; this group stops competing
        // for it.
        //
        // What takes its place is what he actually reached into this menu for (08-31: "I need to
        // be able to send multiple pages at once as an email … from a note — so export and email
        // and select the pages from the day's note to do so"). The single-page sheet answers "send
        // THIS page"; these two answer "send these pages", which is the question a multi-page note
        // makes you ask. Both open the page picker first, so the selection is made where the note
        // is, not guessed from where the finger landed.
        // (09-13: the email / export / Bluesky trio left this menu — all three live inside the
        // roster's one "↥ Send / export…" sheet now.)
        val sendExport: List<List<com.toolsboox.ot.LedgerContextMenu.Item>> = emptyList()
        if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage)) return synth + bring + tagPage + sendExport + listOf(listOf(
            com.toolsboox.ot.LedgerContextMenu.Item("❖  Synthesize the day…") {
                com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
                    ctx, "Synthesize the day", present = { showModal(it) }) { e ->
                    runEngine(e, pageMaterial())
                }
            }
        ))
        // A named Write document gets Write's own menu (text editor, share essay) — those are the
        // tools of the surface, not of one particular key on it.
        return synth + bring + tagPage + sendExport + when (if (com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(notePage))
            "write" else baseNotePage(notePage)) {
            "write" -> listOf(listOf(
                // Hand or text, per Write sub-page. Neither converts the other.
                com.toolsboox.ot.LedgerContextMenu.Item("⌨  Write in text…") { showWriteTextEditor() },
                // Kept alongside Send / Export rather than replaced by it: "Share essay" asks for a
                // title and tags first, which is what a piece of WRITING wants and what a Pickings
                // board does not. The general sheet is the floor; this stays the specialised door.
                com.toolsboox.ot.LedgerContextMenu.Item("→  Share essay…") { shareEssay() }
            ))
            // The day page: what you POSTED on this day, out of the archive. It belongs here and
            // not on a making surface — this is the day's own record, not material for a page.
            null -> listOf(listOf(
                com.toolsboox.ot.LedgerContextMenu.Item("📓  Posted this day…") { showDayArchive() }
            ))
            else -> listOf(listOf(
                com.toolsboox.ot.LedgerContextMenu.Item("⚗  Synthesize this page…") {
                    // Through the modal door — shown raw, this list was THE "stylus on
                    // 'Synthesize' pop up from notes freezes the app" (08-15): the raw ink
                    // session kept the stylus and no tap ever reached the dialog.
                    com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
                        ctx, "Synthesize this page", present = { showModal(it) },
                        onTopic = { t -> proposeTopic(t) },
                        onSiteLinks = { gramsFromSites() }, onGroup = { groupPicksIntoSections() }) { e ->
                        runEngine(e, thisPageMaterial(), pickInk = true)
                    }
                }
            ))
        }
    }

    /** Lay this synthesis out as a map — its pieces clustered by where they came from. */
    private fun mapThisPage() {
        findNavController().navigate(
            R.id.action_to_ledger_map,
            androidx.core.os.bundleOf(
                LedgerMapFragment.ARG_PAGE_DATE to currentDate.toString(),
                LedgerMapFragment.ARG_PAGE_KEY to (notePage ?: "synthesize")
            )
        )
    }

    /** Just THIS page's material — the page as a selection basket: its text boxes AND its picks
     *  (Michael, 09-11: "take those picks… and make a new note" — All Picks is a page of grams, and
     *  a material that saw only text boxes synthesized it to nothing). A pick contributes the words
     *  its card was drawn from (or its source's name), its source, and its link. */
    private fun thisPageMaterial(): String {
        val pk = notePage ?: "default"
        val parts = currentTextElements().filter { it.text.isNotBlank() }
            .map { "• ${it.text.trim()}" }.toMutableList()
        calendarDay.imageElements.filter { it.page == pk }.forEach { e ->
            val words = e.cardText.trim().ifBlank { e.sourceLabel.trim() }
            if (words.isBlank()) return@forEach
            val src = e.sourceLabel.trim().ifBlank { e.sourceFeed }
            val link = if (e.sourceLink.startsWith("http")) " (${e.sourceLink})" else ""
            parts.add("• [pick${if (src.isBlank()) "" else " · $src"}] $words$link")
        }
        return parts.joinToString("\n").take(6000)
    }

    /** The page's provenance — every pick's source, once. Appended to what an engine writes and
     *  read by the Bluesky share. */
    private fun pageSources(): List<Pair<String, String>> {
        val pk = notePage ?: "default"
        val seen = HashSet<String>(); val out = ArrayList<Pair<String, String>>()
        calendarDay.imageElements.filter { it.page == pk }.forEach { e ->
            val label = e.sourceLabel.trim()
            val link = if (e.sourceLink.startsWith("http")) e.sourceLink else ""
            val key = link.ifBlank { label }
            if (key.isBlank() || !seen.add(key)) return@forEach
            out.add((label.ifBlank { link }) to link)
        }
        return out
    }

    /**
     * 🔗 GRAMS FROM MY SITES (Michael, 09-13: "take the note I made yesterday with poses and daily
     * cards and convert each of those poses or exercises into an individual link that uses the
     * featured image from the site"). The page is read (typed lines, picks, ink as words), the
     * engine lists the named things on it, [SiteLinker] finds each one's page on his sites and its
     * Open Graph card, and each becomes a link gram on THIS page — movable, tappable, with the
     * site's picture — placed through the same door a shared link lands by.
     */
    /**
     * REFERENCE PAGES for a prompt (Michael, 09-13: "a prompt like 'organize the ideas here into a
     * class sequence, create a gram for each link for the ashtanga.tech references' on a synthesis
     * page would bring up gram picks, links and annotations"): the sites' pages closest in meaning
     * to [material] (up to forty), as a block the engine can cite by exact URL. Every URL it then
     * cites becomes a link gram on the page ([placeCitedSiteGrams]). Empty without a catalogue or
     * an embedding key, so the prompt is exactly what it was before.
     */
    private fun siteReferenceBlock(material: String): String {
        val ctx = context ?: return ""
        // The body lives in SynthSummons now — a resumed ❖ summon (no page standing) needs it too.
        return SynthSummons.siteReferenceBlock(ctx, material)
    }

    /** Every URL in [answer] that points at one of his sites → a link gram beside the answer.
     *  (A ❖ reply's cited grams go through [SynthSummons] instead — onto the page the reply landed
     *  on, which need not be the one on screen.) */
    private fun placeCitedSiteGrams(answer: String) {
        val ctx = context ?: return
        if (com.toolsboox.plugin.calendar.nw.SiteLinker.citedSiteUrls(ctx, answer).isEmpty()) return
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { placeSiteGrams(citedSitePairs(ctx, answer)) }
            if (isAdded) showMessage(report + " for the cited pages", binding.root)
        }
    }

    /**
     * Elements a background writer put in this day's file while the page stood (a ❖ reply's cited
     * grams) — MERGED into the day in hand, never reloaded over it (a reload would drop ink not yet
     * saved), then the page on screen redrawn with them.
     */
    internal fun mergeFileElements() {
        if (!isAdded || !::calendarDay.isInitialized) return
        val date = currentDate
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                DayLocks.withDay(date) {
                    runCatching { calendarDayService.loadOrNull(documentsRoot(), date, java.util.Locale.getDefault()) }.getOrNull()
                }
            }
            if (!isAdded || !::calendarDay.isInitialized || fresh == null || currentDate != date) return@launch
            val have = calendarDay.imageElements.map { it.elementId.toString() }.toSet()
            val add = fresh.imageElements
                .filter { it.elementId.toString() !in have && it.elementId.toString() !in calendarDay.deletedElementIds }
            if (add.isEmpty()) return@launch
            calendarDay.imageElements.addAll(add)
            val here = notePage
            if (here != null && add.any { it.page == here }) {
                setImageElements(calendarDay.imageElements.filter { it.page == here }.toMutableList())
                redrawSurface()
            }
        }
    }

    private fun gramsFromSites() {
        val ctx = requireContext()
        // Three ways in: what's NAMED on this page (one gram per name), a TOPIC you type (the
        // closest pages in meaning — study guides, functional-ashtanga pieces, posts), or this
        // page AS the topic (Michael, 09-13: "do it semantically for topics, too").
        showIconMenu("Grams from my sites", listOf(
            // ☐/☑ ON A NEW PAGE (Michael, 09-14: "have an option for make grams on new page"): the
            // same doors, landing on a fresh note named for what was found; the walk goes there.
            (if (siteGramsToNewPage) "☑  On a new page" else "☐  On a new page") to { siteGramsToNewPage = !siteGramsToNewPage; gramsFromSites() },
            "🔤  Everything named on this page" to { gramsFromPageItems() },
            "🧭  A topic…" to {
                val input = android.widget.EditText(ctx).apply { hint = "A topic — \"hip hinge\", \"breath ratio\", \"the shoulder in backbends\""; setSingleLine() }
                val dp = resources.displayMetrics.density
                val box = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0); addView(input)
                }
                showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Pages about…").setView(box)
                    .setPositiveButton("Find") { _, _ -> input.text.toString().trim().takeIf { it.isNotBlank() }?.let { gramsFromTopic(it) } }
                    .setNegativeButton(android.R.string.cancel, null).create())
            },
            "📄  This page as the topic" to {
                val creds = aiCreds()
                val ink = if (creds == null) "" else runCatching { pageInkText(creds) }.getOrNull().orEmpty()
                val topic = (thisPageMaterial() + "\n" + ink).trim().take(1500)
                if (topic.isBlank()) showMessage("Nothing on this page yet.", binding.root) else gramsFromTopic(topic, label = "this page")
            },
            // 🖼 THE POSTER (Michael, 09-14: "something that would end up looking like an
            // infographic… grams and text like a poster or flyer"): a topic → its pages → a
            // composed page: title, a lead sentence, named sections each with its line and its
            // grams three across at gallery size. Always a fresh note.
            "🖼  Poster from a topic…" to {
                val input = android.widget.EditText(ctx).apply { hint = "The poster's topic — \"hip opening\", \"the count\", \"backbends\""; setSingleLine() }
                val dp = resources.displayMetrics.density
                val box = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0); addView(input)
                }
                showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Poster from a topic").setView(box)
                    .setPositiveButton("Compose") { _, _ -> input.text.toString().trim().takeIf { it.isNotBlank() }?.let { posterFromTopic(it) } }
                    .setNegativeButton(android.R.string.cancel, null).create())
            }
        ))
    }

    /**
     * A COMPOSED WALK DOWN A NOTE, page after page: text bands and gram grids laid top to bottom,
     * and when the page is full the walk continues on the note's next sub-page (`base#n`), the same
     * pages the ‹ › pager turns. Cards are re-seated (x/y/w/h/page), never re-minted.
     */
    private inner class PageWalk(private val base: String, startSub: Int, private val cols: Int) {
        private val margin = 60f; private val gap = 24f; private val pageW = 1404f; private val pageH = 1872f
        private var sub = startSub
        private var y = margin
        private val cellW = (pageW - 2 * margin - (cols - 1) * gap) / cols
        private var maxSub = startSub
        // A DATED NUMERIC NOTE'S PAGES ARE "2", "3", "4" — the ‹ › pager's run (Michael, 09-14:
        // "when I try to add a new page to notes… it says one of one again"): only a NAMED note
        // hangs "#n" sub-pages off its base. The first cut hung "#1" off "2" and made a page the
        // pager could not count.
        fun key(): String = base.toIntOrNull()?.let { (it + sub).toString() } ?: (if (sub == 0) base else "$base#$sub")
        fun pageCount() = maxSub - (if (sub > maxSub) sub else 0) + 1
        private fun turn() { sub += 1; y = margin; maxSub = maxOf(maxSub, sub) }
        private fun need(h: Float) { if (y + h > pageH - margin && y > margin) turn() }
        fun text(day: com.toolsboox.plugin.calendar.da.v2.CalendarDay, text: String, h: Float, size: Float) {
            need(h)
            day.textElements.add(TextElement(x = margin, y = y, width = pageW - 2 * margin, height = h, text = text, fontSize = size, pageKey = key()))
            y += h + 10f
        }
        fun section(day: com.toolsboox.plugin.calendar.da.v2.CalendarDay, header: String, lead: String) {
            need(60f + (if (lead.isNotBlank()) 56f else 0f) + cellW * 0.6f)   // keep a header with its first row
            text(day, header, 60f, 34f)
            if (lead.isNotBlank()) text(day, lead, 50f, 22f)
        }
        fun grid(cards: List<ImageElement>) {
            var colY = FloatArray(cols) { y }
            for (e in cards) {
                val h = cellW * (e.height / maxOf(e.width, 1f))
                var c = (0 until cols).minByOrNull { colY[it] } ?: 0
                if (colY[c] + h > pageH - margin && colY[c] > margin) { turn(); colY = FloatArray(cols) { y }; c = 0 }
                e.x = margin + c * (cellW + gap); e.y = colY[c]; e.width = cellW; e.height = h; e.rotation = 0f
                e.page = key(); e.timestamp = System.currentTimeMillis()
                colY[c] += h + gap
            }
            y = (colY.maxOrNull() ?: y) + 30f
        }
    }

    /** The poster: see the menu row. Every step says so; the walk ends on the new note. */
    private fun posterFromTopic(topic: String) {
        val ctx = requireContext()
        val creds = aiCreds() ?: run {
            android.widget.Toast.makeText(ctx, "Add your AI key in Ask my Ledger settings", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val sites = com.toolsboox.plugin.calendar.nw.SiteStore.all(ctx).filter { it.url.trim().startsWith("http") }
        if (sites.isEmpty()) { showMessage("Add a site first — Settings › Sites", binding.root); return }
        showMessage("Finding pages about “${topic.take(40)}”…", binding.root)
        lifecycleScope.launch {
            fun say(msg: String) { if (isAdded) showMessage(msg, binding.root) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val hits = runCatching { com.toolsboox.plugin.calendar.nw.SiteLinker.topic(ctx, topic, sites, n = 12) }.getOrDefault(emptyList())
                    if (hits.isEmpty()) return@withContext "Nothing close on your sites — or no OpenAI key for the meaning search (Ask my Ledger settings)." to null
                    withContext(Dispatchers.Main) { say("Composing the poster from ${hits.size} pages…") }
                    val material = "TOPIC: $topic\n" + hits.mapIndexed { i, h -> "$i: ${h.title} · ${h.excerpt.take(160)}" }.joinToString("\n")
                    val res = chatService.run(creds.first, creds.second, creds.third, com.toolsboox.plugin.calendar.nw.SiteLinker.POSTER_PROMPT, material)
                    val answer = (res as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)?.answer
                        ?: return@withContext (((res as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err)?.message ?: "The engine said nothing.") to null)
                    val json = runCatching { org.json.JSONObject(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)) }.getOrNull()
                        ?: return@withContext "The engine's poster didn't parse." to null
                    val title = json.optString("title").ifBlank { topic.take(48) }
                    val intro = json.optString("intro")
                    val secs = json.optJSONArray("sections") ?: org.json.JSONArray()
                    data class Sec(val title: String, val lead: String, val items: List<Int>)
                    val used = HashSet<Int>()
                    val sections = (0 until secs.length()).map { i ->
                        val o = secs.getJSONObject(i); val arr = o.optJSONArray("items") ?: org.json.JSONArray()
                        Sec(o.optString("title"), o.optString("lead"), (0 until arr.length()).map { arr.optInt(it) }.filter { it in hits.indices && used.add(it) })
                    }.filter { it.items.isNotEmpty() }.toMutableList()
                    val rest = hits.indices.filter { it !in used }
                    if (rest.isNotEmpty()) sections += Sec("More", "", rest)
                    // The note, then the cards on it (the placement mints ids; the layout re-seats them).
                    val target = startSiteGramNote(title) ?: return@withContext "Couldn't start a note for the poster." to null
                    withContext(Dispatchers.Main) { say("Placing ${hits.size} grams on “${target.name}”…") }
                    placeSiteGrams(hits.map { it.title to it }, target)
                    // THE LAYOUT — title · intro · [header · lead · grams in columns] …, and when a
                    // page fills, THE NEXT PAGE of the note (Michael, 09-14: "shrink the grams way
                    // more… or be smart enough to put them on a second page"). Gallery size: three
                    // across for a few, four or five for many.
                    var pages = 1
                    DayLocks.withDay(target.date) {
                        val day = calendarDayService.loadForWriteOrThrow(documentsRoot(), target.date, null, java.util.Locale.getDefault())
                        val cards = day.imageElements.filter { it.page == target.key }
                        val byUrl = cards.associateBy { it.sourceLink.trimEnd('/') }
                        val walk = PageWalk(target.key, 0, cols = if (hits.size > 12) 5 else if (hits.size > 6) 4 else 3)
                        day.textElements.removeAll { it.pageKey.substringBefore('#') == target.key }
                        walk.text(day, title, 120f, 52f)
                        if (intro.isNotBlank()) walk.text(day, intro, 80f, 26f)
                        for (sec in sections) {
                            walk.section(day, "§ ${sec.title}", sec.lead)
                            walk.grid(sec.items.mapNotNull { i -> byUrl[hits[i].url.trimEnd('/')] })
                        }
                        pages = walk.pageCount()
                        calendarDayService.save(documentsRoot(), target.date, day)
                    }
                    "🖼 “${target.name}” · ${sections.size} sections · ${hits.size} grams" + (if (pages > 1) " · $pages pages" else "") to target
                }
            }.getOrElse { ("Couldn't compose the poster: " + (it.message ?: it.javaClass.simpleName)) to null }
            if (!isAdded) return@launch
            val (report, target) = outcome
            timber.log.Timber.i("posterFromTopic: $report")
            if (target != null) { say(report); openSiteGramNote(target) }
            else showModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Poster from a topic").setMessage(report).setPositiveButton("OK", null).create())
        }
    }

    /** A topic's closest pages across the sites, placed as grams. */
    private fun gramsFromTopic(topic: String, label: String = topic.take(40)) {
        val ctx = requireContext()
        val sites = com.toolsboox.plugin.calendar.nw.SiteStore.all(ctx).filter { it.url.trim().startsWith("http") }
        if (sites.isEmpty()) { showMessage("Add a site first — Settings › Sites", binding.root); return }
        showMessage("Finding pages about $label…", binding.root)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val hits = runCatching { com.toolsboox.plugin.calendar.nw.SiteLinker.topic(ctx, topic, sites) }.getOrDefault(emptyList())
                if (hits.isEmpty()) return@withContext "Nothing close on your sites — or no OpenAI key for the meaning search (Ask my Ledger settings)."
                val target = if (siteGramsToNewPage) startSiteGramNote(label) else null
                val r = placeSiteGrams(hits.map { it.title to it }, target)
                if (target != null) openSiteGramNote(target)
                r
            }
            if (isAdded) showMessage(report, binding.root)
        }
    }

    /** Each (item, page) pair becomes a link gram on this page: the page's Open Graph card, the
     *  site's picture, the item as the card's words. Runs off the main thread; merges when done. */
    /** Where grams land: this page (null) or a fresh note ([SiteGramTarget]) — "make grams on a new
     *  page" (Michael, 09-14). */
    data class SiteGramTarget(val date: LocalDate, val key: String, val name: String)

    /** ☐/☑ On a new page — the grams doors' switch; session state, a tool in the hand. */
    private var siteGramsToNewPage = false

    private fun startSiteGramNote(name: String): SiteGramTarget? {
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        val n = docs.startNote(requireContext().applicationContext, docs.GRID, name.trim().take(48).ifBlank { "Grams" }, LocalDate.now()) ?: return null
        return SiteGramTarget(n.date, n.key, n.name)
    }

    private fun openSiteGramNote(t: SiteGramTarget) {
        binding.root.post { if (isAdded) CalendarNavigator.toDayNote(this, t.date, t.key) }
    }

    private fun placeSiteGrams(pairs: List<Pair<String, com.toolsboox.plugin.calendar.nw.SiteLinker.Hit>>, target: SiteGramTarget? = null): String {
        val pk = target?.key ?: notePage ?: "default"
        val date = target?.date ?: currentDate
        val (placed, missed) = placeSiteGramCards(calendarDayService, documentsRoot(), pairs, date, pk)
        if (target != null) {
            // Another page: nothing to merge into the one on screen — the walk goes there.
            return "Placed $placed gram" + (if (placed == 1) "" else "s") + " on “${target.name}”" +
                (if (missed.isEmpty()) "" else " · couldn't card: ${missed.joinToString(", ")}")
        }
        val fresh = DayLocks.withDay(currentDate) {
            runCatching { calendarDayService.loadOrNull(documentsRoot(), currentDate, java.util.Locale.getDefault()) }.getOrNull()
        }
        binding.root.post {
            if (isAdded && ::calendarDay.isInitialized && fresh != null) {
                val have = calendarDay.imageElements.map { it.elementId.toString() }.toSet()
                fresh.imageElements
                    .filter { it.elementId.toString() !in have && it.elementId.toString() !in calendarDay.deletedElementIds }
                    .forEach { calendarDay.imageElements.add(it) }
                setImageElements(calendarDay.imageElements.filter { it.page == pk }.toMutableList())
            }
        }
        return "Placed $placed gram" + (if (placed == 1) "" else "s") +
            (if (missed.isEmpty()) "" else " · couldn't card: ${missed.joinToString(", ")}")
    }

    private fun gramsFromPageItems() {
        val ctx = requireContext()
        val creds = aiCreds() ?: run {
            android.widget.Toast.makeText(ctx, "Add your AI key in Ask my Ledger settings", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val sites = com.toolsboox.plugin.calendar.nw.SiteStore.all(ctx).filter { it.url.trim().startsWith("http") }
        if (sites.isEmpty()) { showMessage("Add a site first — Settings › Sites", binding.root); return }
        showMessage("Reading the ink on this page…", binding.root)
        // IT TALKS THE WHOLE WAY (Michael, 09-14: "it says that it's reading the ink and then
        // nothing happens"): the ink read, the engine's ask and a dozen site look-ups can run for
        // minutes, and one line at the start read as a hang. Every step says so; the names are
        // looked up four at a time; and the end lands in a dialog, not a line that scrolls away.
        lifecycleScope.launch {
            fun say(msg: String) { if (isAdded) showMessage(msg, binding.root) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val ink = runCatching { pageInkText(creds) }.getOrNull().orEmpty().trim()
                    val material = thisPageMaterial() + (if (ink.isBlank()) "" else "\n• [ink] " + ink.take(2500))
                    if (material.isBlank()) return@withContext "Nothing on this page to link." to 0
                    withContext(Dispatchers.Main) { say("Asking the engine what's named here…") }
                    val res = chatService.run(creds.first, creds.second, creds.third,
                        com.toolsboox.plugin.calendar.nw.SiteLinker.ITEMS_PROMPT, material.take(8000))
                    val answer = (res as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)?.answer
                        ?: return@withContext (((res as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err)?.message ?: "The engine said nothing.") to 0)
                    val items = answer.lines().map { it.trim().trimStart('-', '•', '*', '·', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                        .filter { it.length in 2..80 }.distinctBy { it.lowercase() }.take(24)
                    if (items.isEmpty()) return@withContext "No named postures or exercises found on this page." to 0
                    withContext(Dispatchers.Main) { say("Finding ${items.size} name${if (items.size == 1) "" else "s"} on your sites…") }
                    val found = java.util.concurrent.ConcurrentHashMap<String, com.toolsboox.plugin.calendar.nw.SiteLinker.Hit>()
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    val sem = kotlinx.coroutines.sync.Semaphore(4)
                    kotlinx.coroutines.coroutineScope {
                        for (item in items) launch {
                            sem.acquire()
                            try {
                                runCatching { com.toolsboox.plugin.calendar.nw.SiteLinker.find(ctx, item, sites) }.getOrNull()?.let { found[item] = it }
                            } finally { sem.release() }
                            val n = done.incrementAndGet()
                            if (n % 3 == 0 || n == items.size) withContext(Dispatchers.Main) { say("Finding pages… $n/${items.size}") }
                        }
                    }
                    val pairs = items.mapNotNull { item -> found[item]?.let { item to it } }
                    val missed = items.filter { !found.containsKey(it) }
                    withContext(Dispatchers.Main) { say("Placing ${pairs.size} gram${if (pairs.size == 1) "" else "s"}…") }
                    val target = if (siteGramsToNewPage && pairs.isNotEmpty()) startSiteGramNote(items.take(3).joinToString(", ")) else null
                    val report = placeSiteGrams(pairs, target)
                    if (target != null) openSiteGramNote(target)
                    (report + (if (missed.isEmpty()) "" else "\n\nNo page for: ${missed.joinToString(", ")}")) to pairs.size
                }
            }.getOrElse { ("Couldn't make the grams: " + (it.message ?: it.javaClass.simpleName)) to 0 }
            if (!isAdded) return@launch
            val (report, placed) = outcome
            timber.log.Timber.i("gramsFromPageItems: $report")
            if (placed > 0) say(report.lineSequence().first())
            else showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Grams from my sites").setMessage(report).setPositiveButton("OK", null).create())
        }
    }

    /**
     * 🗂 GROUP PICKS INTO SECTIONS (Michael, 09-13: "ask you to group gram picks into sections on the
     * notes page so I can easily come in with them"). The engine sorts the page's picks by theme;
     * the page is re-laid as headed bands — a text header, then that section's grams three across —
     * with anything the engine left out under "Other". Positions change; nothing is deleted.
     */
    private fun groupPicksIntoSections() {
        val ctx = requireContext()
        val creds = aiCreds() ?: run {
            android.widget.Toast.makeText(ctx, "Add your AI key in Ask my Ledger settings", android.widget.Toast.LENGTH_LONG).show(); return
        }
        if (!::calendarDay.isInitialized) return
        val pk = notePage ?: "default"
        val picks = calendarDay.imageElements.filter { it.page == pk }.sortedWith(compareBy({ it.y }, { it.x }))
        if (picks.size < 2) { showMessage("Fewer than two picks on this page — nothing to group.", binding.root); return }
        showMessage("Sorting ${picks.size} picks with the engine — a few seconds…", binding.root)
        lifecycleScope.launch {
            val sections = withContext(Dispatchers.IO) {
                val material = picks.mapIndexed { i, e ->
                    "$i: " + e.cardText.trim().ifBlank { e.sourceLabel.trim() }.ifBlank { e.sourceFeed }.take(160)
                }.joinToString("\n")
                val res = chatService.run(creds.first, creds.second, creds.third,
                    com.toolsboox.plugin.calendar.nw.SiteLinker.GROUP_PROMPT, material)
                val answer = (res as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)?.answer ?: return@withContext null
                runCatching {
                    val json = answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)
                    val arr = org.json.JSONObject(json).getJSONArray("sections")
                    val out = mutableListOf<Pair<String, List<Int>>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val idx = o.getJSONArray("items"); val list = mutableListOf<Int>()
                        for (j in 0 until idx.length()) list += idx.getInt(j)
                        out += o.optString("title").ifBlank { "Section ${i + 1}" } to list
                    }
                    out
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (sections.isNullOrEmpty()) { showMessage("Couldn't sort them — try again.", binding.root); return@launch }
            // Lay the page out: header · that section's grams in columns · next header — and when
            // the page fills, the note's next sub-page (Michael, 09-14: "shrink the grams way
            // more… or put them on a second page"). Gallery size: 3 across for a few, 4 or 5 for many.
            val used = HashSet<Int>()
            val bands = sections.map { (t, idx) -> t to idx.filter { it in picks.indices && used.add(it) } }
                .filter { it.second.isNotEmpty() } + listOf("Other" to picks.indices.filter { it !in used })
            calendarDay.textElements.removeAll { it.pageKey == pk && it.text.startsWith("§ ") }
            val walk = PageWalk(baseNotePage(pk) ?: pk, notePageSubIndex(pk),
                cols = if (picks.size > 12) 5 else if (picks.size > 6) 4 else 3)
            for ((title, idx) in bands) {
                if (idx.isEmpty()) continue
                walk.section(calendarDay, "§ $title", "")
                walk.grid(idx.map { picks[it] })
            }
            val pagesUsed = walk.pageCount()
            presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            setImageElements(calendarDay.imageElements.filter { it.page == pk }.toMutableList())
            setTextElements(calendarDay.textElements.filter { it.pageKey == pk }.toMutableList())
            showMessage("${bands.count { it.second.isNotEmpty() }} sections" + (if (pagesUsed > 1) " across $pagesUsed pages — ‹ › walks them" else ""), binding.root)
        }
    }

    /** 🧭 PROPOSE A TOPIC: the picks, highlights and notes across the Ledger that rhyme with a topic
     *  he named, written up as ONE aggregated piece — sources cited by tag — onto a new Write note,
     *  and the walk goes there. The Ask-output rule: Ask produces, Notes hold. */
    private fun proposeTopic(topic: String) {
        val ctx = requireContext()
        val creds = aiCreds() ?: run {
            android.widget.Toast.makeText(ctx, "Add your AI key in Ask my Ledger settings", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val (provider, key, model) = creds
        android.widget.Toast.makeText(ctx, "Gathering what rhymes with \u201C$topic\u201D\u2026", android.widget.Toast.LENGTH_SHORT).show()
        val root = documentsRoot()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val corpus = runCatching {
                    corpusService.gather(root, com.toolsboox.plugin.calendar.ot.Spiral.SCOPE)
                        .filter { com.toolsboox.plugin.calendar.ot.Spiral.isSubstantial(it.text) }
                        .let { com.toolsboox.plugin.calendar.ot.Spiral.dedupe(it) { s -> s.text } }
                }.getOrDefault(emptyList())
                val found = com.toolsboox.plugin.calendar.ot.SemanticRoots.neighbors(ctx, topic, corpus, topN = 16, minScore = 0.42)
                if (found.isEmpty()) return@withContext null
                val lines = found.map { r -> "• [${r.snippet.citation}] ${r.snippet.text.take(500)}" }
                val material = "TOPIC: $topic\n\nThe author's own picks, highlights and notes that rhyme with it:\n" + lines.joinToString("\n")
                val answer = chatService.run(provider, key, model, com.toolsboox.plugin.calendar.ot.SynthEngines.TOPIC_PROMPT, material.take(9000))
                val sources = found.joinToString("\n") { "• [${it.snippet.citation}]" }
                answer to sources
            }
            if (res == null) {
                android.widget.Toast.makeText(ctx, "Nothing in your Ledger rhymes with that yet.", android.widget.Toast.LENGTH_LONG).show(); return@launch
            }
            val (answer, sources) = res
            when (answer) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
                    val made = docs.startNote(ctx, docs.WRITE, ("🧭 $topic").take(60), java.time.LocalDate.now())
                    if (made == null) { android.widget.Toast.makeText(ctx, "Couldn't start a note for that.", android.widget.Toast.LENGTH_LONG).show(); return@launch }
                    withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.feeds.ui.writeTextOntoNote(ctx, calendarDayService, made,
                            "🧭 $topic\n\n${answer.answer.trim()}\n\nSources:\n$sources")
                    }
                    com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(this@CalendarDayFragment, made.date, made.key)
                }
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err ->
                    android.widget.Toast.makeText(ctx, "⚠ ${answer.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 🦋 THIS PAGE onto Bluesky with its provenance: the page as the picture (strokes, picks, text),
     *  the words = what the page says (its text boxes, else its ink read as words), then "— via" its
     *  picks' sources, and the first pick's link as the post's link (Michael, 09-11). */
    private fun sharePageToBluesky() {
        val ctx = requireContext()
        if (!com.toolsboox.plugin.calendar.nw.BlueskyReply.config(ctx).ready) {
            android.widget.Toast.makeText(ctx, "Bluesky bridge not configured — Sites › Bluesky", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val pk = notePage ?: "default"
        val typed = calendarDay.textElements.filter { it.pageKey == pk && it.text.isNotBlank() }
            .joinToString("\n") { it.text.trim() }
        val sources = pageSources()
        val creds = aiCreds()
        val bmp = renderPageBitmap()
        val gramId = "page-${currentDate}-$pk-${System.currentTimeMillis() / 1000}"
        android.widget.Toast.makeText(ctx, "Sharing the page\u2026", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val scale = (1600f / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
                val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val bytes = java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                var lead = typed
                if (lead.isBlank() && creds != null) lead = runCatching { pageInkText(creds) }.getOrNull().orEmpty().trim()
                val via = sources.take(3).map { it.first }.filter { it.isNotBlank() }.joinToString(" · ")
                var text = lead.take(200).trim() + (if (lead.length > 200) "\u2026" else "")
                if (via.isNotBlank()) text += (if (text.isBlank()) "" else "\n") + "\u2014 via $via"
                if (text.length > 270) text = text.take(270)
                val alt = "A page from the Ledger" + (if (via.isBlank()) "" else " \u00B7 $via")
                com.toolsboox.plugin.calendar.nw.BlueskyReply.shareGram(
                    ctx.applicationContext, text, b64, alt = alt, link = sources.firstOrNull()?.second.orEmpty(), gramId = gramId)
            }
            if (isAdded) android.widget.Toast.makeText(requireContext(), r.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The shared Send / Export sheet, for whichever making surface is open — Write, Synthesize,
     * Pickings or a note page. See [com.toolsboox.plugin.calendar.ot.LedgerSendExport].
     *
     * Both halves of the payload are handed over as lambdas rather than values, and that is not
     * fastidiousness: [renderPageBitmap] composites the whole 1404×1872 canvas and the text half
     * may run the page through the vision model, so building either one eagerly would make the
     * menu itself take seconds to open on e-ink — for a sheet where four of the seven rows need
     * neither. The text is the page's typed boxes plus, when there is a key and there is ink, its
     * recognition; a Write page that was typed rather than written hands over its markdown draft,
     * because that IS the page and OCR of it would only be a worse copy.
     */
    /** The page long-hold's "↥ Send / export…" reaches the real sheet here (the base falls
     *  back to the page-image share on surfaces without one). */
    // ONE DOOR, ONE SHEET (Michael, 09-13: "export/send… send as email… Export… send as Bluesky —
    // all four are redundant and the two exports open up different panels for no reason"). The
    // page long-hold's "↥ Send / export…" opens the page picker (this page ticked) and then THE
    // verb sheet — which now seats the single-page sheet's text doors too, so there is nothing
    // the other panel had that this one hasn't.
    override fun onPageSendExport() { showExportPagePicker() }

    /** A Write piece minted to answer a letter leads with its reply door — the errand the piece
     *  exists for. Empty for every ordinary page. */
    private fun replyLead(): List<Pair<String, () -> Unit>> {
        val replyIntent = com.toolsboox.plugin.mail.MailReplyDrafts.forWrite(requireContext(), notePage, currentDate)
            ?: return emptyList()
        return listOf("↩  Send as reply to ${replyIntent.sender}" to { sendWriteReply(replyIntent) })
    }

    /** This page as the send sheet's payload — words (draft → OCR → text elements), picture, provenance. */
    private fun sendExportPayload(): com.toolsboox.plugin.calendar.ot.LedgerSendExport.Payload {
        val ctx = requireContext()
        val pageKey = notePage ?: "default"
        val document = com.toolsboox.plugin.calendar.ot.LedgerDocuments
            .documentFor(ctx, notePage, currentDate)
        val title = document?.title?.takeIf { it.isNotBlank() }
            ?: "$currentDate · ${notePage ?: "day"}"
        return com.toolsboox.plugin.calendar.ot.LedgerSendExport.Payload(
                title = title,
                text = {
                    val draft = com.toolsboox.plugin.calendar.ot.WriteDraftStore.draft(ctx, currentDate, pageKey)
                    if (draft != null && draft.markdown.isNotBlank()) draft.markdown
                    else aiCreds()?.let { pageOcrText(it) }
                        ?: currentTextElements().filter { it.text.isNotBlank() }
                            .joinToString("\n\n") { it.text.trim() }
                },
                bitmap = { runCatching { renderPageBitmap() }.getOrNull() },
                // Where this page lives, so the export can say so — see [LedgerProvenance]. The
                // surface name comes from LedgerDocuments so the stamp says "Synthesize" exactly
                // as the hub row, the day chip and the directory do. Gram Picks is named here
                // even though LedgerDocuments deliberately declines it a surface (it has no
                // document store) — a publish stamped "Day" hid the one thing the page is. A
                // page with no name at all (the day page itself) says "Day".
                surface = when {
                    baseNotePage(notePage) == com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS ->
                        "All Picks"
                    else -> com.toolsboox.plugin.calendar.ot.LedgerDocuments.surfaceOf(notePage)
                        ?.let { com.toolsboox.plugin.calendar.ot.LedgerDocuments.label(it) } ?: "Day"
                },
                date = currentDate,
                pageKey = notePage,
                // The page's element-carried provenance: every gram on this page (sub-pages of the
                // same base included) that knows where it came from. On Gram Picks this list IS
                // the citation list — the page has no text — and on any other page it enriches
                // what the text alone would say.
                links = if (::calendarDay.isInitialized) {
                    val base = baseNotePage(notePage) ?: "default"
                    calendarDay.imageElements
                        .filter { it.page.substringBefore('#') == base && it.sourceLink.startsWith("http") }
                        .map { it.sourceLink }.distinct()
                } else emptyList()
        )
    }

    /**
     * The reply door on a Write piece that answers a letter: gather the piece's words, hold one
     * breath with the recipient named, then send through the ONE reply seam
     * ([com.toolsboox.plugin.mail.ui.MailVerbs.sendReplyText] — same threading, same keep-forever
     * bookkeeping as the quick-reply dialog, no second SMTP path).
     *
     * The words are the piece's TEXT: its typed markdown draft when there is one, else the ink
     * read by the vision model, else the page's text boxes — exactly what the Send / Export
     * sheet's own text lambda produces, so "what would this send" has one answer per page. A
     * piece that yields no words (ink only, no AI key) is told so and pointed at the sheet's
     * ✉ Email row, which sends the page as a picture — that path already exists and a reply
     * seam that silently sent an empty body would be worse than none.
     *
     * The letter itself is looked up fresh from [InboxStore] so the send gets the real message
     * where it still exists; a letter the window has scrolled past is rebuilt from the intent's
     * remembered address and subject — enough for [MailSync.sendReply], which needs only the
     * account-bearing id, the reply address and the subject to thread under.
     */
    private fun sendWriteReply(intent: com.toolsboox.plugin.mail.MailReplyDrafts.Intent) {
        val ctx = requireContext()
        val pageKey = notePage ?: "default"
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val draft = com.toolsboox.plugin.calendar.ot.WriteDraftStore.draft(ctx, currentDate, pageKey)
                if (draft != null && draft.markdown.isNotBlank()) draft.markdown
                else aiCreds()?.let { pageOcrText(it) }
                    ?: currentTextElements().filter { it.text.isNotBlank() }
                        .joinToString("\n\n") { it.text.trim() }
            }.orEmpty().trim()
            if (!isAdded) return@launch
            if (text.isBlank()) {
                showMessage(
                    "Nothing typed or recognised on this piece yet — ✉ Email can send the page as a picture.",
                    binding.root
                )
                return@launch
            }
            // THE HANDWRITING ITSELF, alongside the recognised words.
            //
            // Michael, 2026-08-04: "Reply to Rebecca from gram worked great, but I'm not sure it
            // included the handwriting as an image. I would like it to." It did not — this path
            // OCR'd the page and sent the transcription, so a letter written by hand arrived as
            // typed text. The point of writing by hand is that the reader sees the hand.
            //
            // The text still leads the message: it is what threads, quotes and searches, and some
            // readers will never open an attachment. The picture rides with it rather than
            // replacing it.
            val ink = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = renderPageBitmap()
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                    listOf(com.toolsboox.plugin.mail.SmtpClient.Attachment(
                        filename = "handwritten-${currentDate}.png",
                        mimeType = "image/png",
                        bytes = baos.toByteArray(),
                    ))
                }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            val m = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.mail.InboxStore.messages(ctx).firstOrNull { it.id == intent.mailId }
            } ?: run {
                // Not on this device (cleared, or drafted on the other one). A follow-up to a
                // letter you SENT goes to its recipient, which the reply rules read from To
                // (2026-10-10 review — iOS 1dac92da made the same fix).
                val ownLetter = com.toolsboox.plugin.mail.InboxStore.isSent(intent.mailId)
                com.toolsboox.plugin.mail.InboxMessage(
                    id = intent.mailId, account = "", fromName = intent.senderName,
                    fromEmail = intent.senderEmail, subject = intent.subject,
                    snippet = "", body = "", date = 0L,
                    toName = if (ownLetter) intent.senderName else "",
                    toEmail = if (ownLetter) intent.senderEmail else "",
                )
            }
            if (!isAdded) return@launch
            // One held breath, with the recipient and the thread named — a reply leaves the
            // device and lands in front of a person, so it is never one tap.
            val who = intent.sender + (if (intent.senderEmail.isNotBlank()) " <${intent.senderEmail}>" else "")
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Send as reply?")
                .setMessage(
                    "To $who\n${intent.reSubject}" +
                        // The route said before Send, as iOS says it.
                        (if (com.toolsboox.plugin.mail.MailThreads.crmHost(m) != null) " · via FluentCRM" else "") +
                        (if (ink.isEmpty()) "" else "\n✍ with your handwriting attached") +
                        "\n\n${text.take(400)}${if (text.length > 400) "…" else ""}"
                )
                .setPositiveButton("Send") { _, _ ->
                    com.toolsboox.plugin.mail.ui.MailVerbs.sendReplyText(
                        this@CalendarDayFragment, m, text, attachments = ink)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showModal()
        }
    }

    /** Write → Share: the handwritten page leaves as a WP draft, an email, or a community post. */
    private fun shareEssay() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val titleIn = android.widget.EditText(ctx).apply { hint = "Essay title"; setSingleLine() }
        val tagsIn = android.widget.EditText(ctx).apply { hint = "Tags (comma separated)"; setSingleLine() }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0); addView(titleIn); addView(tagsIn)
        }
        // Guarded: the title and tags are work being typed — a stray touch outside must not
        // throw them away.
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Share essay")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val title = titleIn.text.toString().trim()
                if (title.isBlank()) {
                    android.widget.Toast.makeText(ctx, "It needs a title", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pickEssayDestination(title, tagsIn.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    private fun pickEssayDestination(title: String, tags: String) {
        val ctx = requireContext()
        val boards = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx)
        val community = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx)
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        if (boards.ready) rows.add("📄  WordPress draft — ${boards.site.removePrefix("https://")}" to {
            sendEssay(boards.site, boards.user, boards.pass, "draft", title, tags, null)
        })
        if (community.ready) {
            rows.add("📄  WordPress draft — ${community.site.removePrefix("https://")}" to {
                sendEssay(community.site, community.user, community.pass, "draft", title, tags, null)
            })
            rows.add("👥  Community space…" to { postEssayToSpace(title) })
        }
        val mailSite = if (boards.ready) boards else if (community.ready)
            com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(community.site, community.user, community.pass, 0) else null
        if (mailSite != null) rows.add("✉  Email…" to { promptEssayEmail(mailSite, title, tags) })
        if (rows.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Add your site first (Settings → Your sites)", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Send to")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton("Cancel", null)
            .showModal()
    }

    private fun promptEssayEmail(site: com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config, title: String, tags: String) {
        val ctx = requireContext()
        val toIn = android.widget.EditText(ctx).apply {
            hint = "to@example.com"; setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(toIn)
        }
        // Guarded: an address being typed is work — a stray touch outside must not throw it away.
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Email the essay").setView(box)
            .setPositiveButton("Send") { _, _ ->
                val to = toIn.text.toString().trim()
                if (to.isNotBlank()) sendEssay(site.site, site.user, site.pass, "email", title, tags, to)
            }
            .setNegativeButton("Cancel", null).create())
    }

    private fun postEssayToSpace(title: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val spaces = withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.spaces(ctx) }
            if (spaces.isEmpty()) {
                android.widget.Toast.makeText(ctx, "Couldn't load spaces", android.widget.Toast.LENGTH_SHORT).show(); return@launch
            }
            val labels = spaces.map { (if (it.privacy == "public") "🌐  " else "🔒  ") + it.title }.toTypedArray()
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Post to space")
                .setItems(labels) { _, which ->
                    val space = spaces[which]
                    lifecycleScope.launch(Dispatchers.IO) {
                        val png = pagePng()
                        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
                        val status = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.postGram(
                            ctx, b64, title, "essay-" + java.util.UUID.randomUUID().toString().lowercase(), space.id)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null).showModal()
        }
    }

    private fun sendEssay(site: String, user: String, pass: String, dest: String, title: String, tags: String, to: String?) {
        val ctx = requireContext()
        android.widget.Toast.makeText(ctx, "Sending…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val png = pagePng()
            // A TYPED Write page leaves as HTML, converted from its Markdown at the moment it
            // goes — the only moment the HTML matters. A handwritten one leaves as it always did.
            val draft = com.toolsboox.plugin.calendar.ot.WriteDraftStore
                .draft(ctx, currentDate, notePage ?: "write")
            val text = if (draft != null && draft.markdown.isNotBlank())
                com.toolsboox.plugin.calendar.ot.MarkdownHtml.html(draft.markdown)
            else currentTextElements().filter { it.text.isNotBlank() }.joinToString("\n\n") { it.text.trim() }
            val status = com.toolsboox.plugin.calendar.nw.LedgerEssay.send(site, user, pass, dest, title, tags, text, to, png)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The current page (ink + elements) rendered to PNG bytes — the essay's true face. */
    private fun pagePng(): ByteArray {
        val bmp = renderPageBitmap()
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    override fun onSynthesizeText(element: com.toolsboox.da.TextElement) {
        val ctx = context ?: return
        com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
            ctx, "Synthesize this", present = { showModal(it) }) { e ->
            runEngine(e, element.text)
        }
    }

    /** The day's gathered material: every text box on every page of today + starred excerpts. */
    private fun pageMaterial(): String {
        val parts = mutableListOf<String>()
        calendarDay.textElements.filter { it.text.isNotBlank() }.forEach {
            parts.add("• [${it.pageKey.ifBlank { "day" }}] ${it.text.trim()}")
        }
        calendarDay.readingEvents.forEach { e ->
            val body = e.excerpt?.takeIf { it.isNotBlank() } ?: e.title
            parts.add("• [star · ${e.source ?: "read"}] $body")
        }
        return parts.joinToString("\n").take(6000)
    }

    /**
     * The handwriting BAKED INTO this page's picks — a note written on a pick rides inside its
     * card's pixels (FeedNoteGram.inkFace), where no text field and no page-stroke OCR sees it.
     * One vision pass over a contact sheet of the page's cards (at most twelve, scaled) reads
     * them all together; the result rides into the engine as "[pick ink]" lines.
     */
    private fun picksInkText(creds: Triple<String, String, String>): String? {
        val ctx = context ?: return null
        val pk = notePage ?: "default"
        val cards = calendarDay.imageElements.filter { it.page == pk }.take(12)
            .mapNotNull { e -> runCatching { com.toolsboox.ot.LedgerMedia.resolveBitmap(ctx, e.data, e.dataRef) }.getOrNull() }
        if (cards.isEmpty()) return null
        val w = 900
        val scaled = cards.map { b -> val h = (w * b.height / b.width.coerceAtLeast(1)).coerceIn(60, 1400)
            android.graphics.Bitmap.createScaledBitmap(b, w, h, true) }
        val total = scaled.sumOf { it.height + 24 }
        val sheet = android.graphics.Bitmap.createBitmap(w, total, android.graphics.Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(sheet); c.drawColor(android.graphics.Color.WHITE)
        var y = 0f
        for (b in scaled) { c.drawBitmap(b, 0f, y, null); y += b.height + 24 }
        return com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(
            sheet, creds.first, creds.second, creds.third)?.takeIf { it.isNotBlank() }
    }

    private fun runEngine(engine: com.toolsboox.plugin.calendar.ot.SynthEngines.Engine, material: String,
                          /** Also read the handwriting baked into this page's picks (All Picks and
                           *  any page of grams) — see [picksInkText]. */
                          pickInk: Boolean = false) {
        val ctx = requireContext()
        if (material.isBlank()) {
            android.widget.Toast.makeText(ctx, "Nothing gathered yet — add some pickings or notes first", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
        if (creds == null) {
            android.widget.Toast.makeText(ctx, "Add your AI key in Ask my Ledger settings", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val (provider, key, model) = creds
        android.widget.Toast.makeText(ctx, "Synthesizing…", android.widget.Toast.LENGTH_SHORT).show()
        // The ink read as words rides in with the material (the strokes are half the page), and
        // the page's picks come back out as Sources under the answer — the note carries where it
        // came from.
        val sources = pageSources()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val ink = runCatching { pageInkText(creds) }.getOrNull().orEmpty().trim()
                val picksInk = if (pickInk) runCatching { picksInkText(creds) }.getOrNull().orEmpty().trim() else ""
                val withInk = material +
                    (if (ink.isBlank()) "" else "\n• [ink] " + ink.take(2500)) +
                    (if (picksInk.isBlank()) "" else "\n• [pick ink — the handwriting on the picks] " + picksInk.take(2500))
                chatService.run(provider, key, model, engine.prompt + siteReferenceBlock(withInk), withInk)
            }
            when (res) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                    val provenance = if (sources.isEmpty()) "" else "\n\nSources:\n" +
                        sources.joinToString("\n") { (l, u) -> "• $l" + (if (u.isBlank()) "" else " \u2014 $u") }
                    placeGeneratedText("${engine.name}\n\n${res.answer.trim()}$provenance")
                    placeCitedSiteGrams(res.answer)
                    // The pipeline's missing wire, Michael's 08-17 ruling: "the synthesis/shell
                    // should lead to a write" — gram picks → pickings → synthesize → WRITE.
                    // The artifact just landed; the door to the next station rides it.
                    offerBeginTheWrite(engine.name, res.answer.trim())
                }
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err ->
                    android.widget.Toast.makeText(ctx, "⚠ ${res.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * "→ Begin the Write": mint a named Lines document seeded with the shell the synthesis just
     * made — the last hop of the walked path Michael ruled on the 08-17 "Ledger iOS" note
     * ("Synthesize being the shell maker and thesis finder", "the synthesis/shell should lead to
     * a write"). Offered on the snackbar, never automatic: the artifact may be one try of
     * several, and only the keeper deserves a document. The shell rides in as the piece's TYPED
     * draft — outline on top, editable, publishable — and the hand takes over from there.
     */
    private fun offerBeginTheWrite(engineName: String, shell: String) {
        val ctx = context ?: return
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, "Synthesis placed", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("→ Begin the Write") {
                val today = LocalDate.now()
                // The title: the shell's own first line (a thesis-finder's answer usually leads
                // with the thesis), markdown heading marks stripped; the engine's name is the
                // fallback for an answer that opens mid-sentence.
                val title = shell.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.trim()?.trimStart('#', ' ')?.take(60)
                    ?.ifBlank { null } ?: engineName.trim().ifBlank { "Writing" }
                val page = com.toolsboox.plugin.calendar.ot.WritePageStore.add(ctx, title, today)
                val id = com.toolsboox.plugin.calendar.ot.WriteDraftStore.id(today, page.key)
                val drafts = com.toolsboox.plugin.calendar.ot.WriteDraftStore.load(ctx, today)
                    .filterNot { it.id == id }
                com.toolsboox.plugin.calendar.ot.WriteDraftStore.save(
                    ctx, today,
                    drafts + com.toolsboox.plugin.calendar.ot.WriteDraft(
                        id = id, title = title, markdown = shell,
                        updatedAt = System.currentTimeMillis()))
                CalendarNavigator.toDayNote(this, today, page.key)
            }.show()
    }

    /** Tap a Quick Wins row → GO, no popup. The rows are pixels in the template, so the tap
     *  resolves against the rectangles [CalendarDayPage] recorded when it drew them (the
     *  PickingsCover/DayEventHits discipline) — never a second guess at the layout. Where it
     *  goes is the win's own verb: "reply to Sarah" lands in mail, "call the studio" lands on
     *  the contact in the rolodex, and anything else lands on the task's home day, where the
     *  ink and companions around it are. The old stars dialog is gone with the stars — they
     *  surface in the feeds now. */
    override fun onCanvasSingleTap(cx: Float, cy: Float): Boolean {
        // All Stars: a tap on a starred gram OPENS it — the graduated board when it has one, else
        // its source (the letter for mail://, the in-pane reader for a feed, the browser for the
        // web). The long-press menu always offered "Open the source"; the tap is the promise the
        // grid makes just by looking like a launcher, and until now it kept nothing ("clicking on
        // an email doesn't take me to the email"). Only a tap that LANDS on a gram is consumed —
        // anywhere else falls through so the tap-to-type strips and page gestures keep working.
        if (notePage == "intake") {
            val gram = com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.gramAt(cx, cy) ?: return false
            val element = calendarDay.imageElements.firstOrNull {
                it.elementId.toString().lowercase() == gram.elementId
            } ?: return false
            when {
                element.graduatedTo.isNotBlank() ->
                    CalendarNavigator.toDayNote(this, currentDate, element.graduatedTo)
                element.sourceLink.isNotBlank() -> onImageSource(element)
                else -> return false
            }
            return true
        }
        if (notePage != null) {
            // ❖ THE VOICE HEADER (Michael, 2026-10-12: "Please put the persona at the top of the
            // note") — a finger tap on it asks who answers next. Only the setting changes: no summon.
            if (onSynthPage() && com.toolsboox.plugin.calendar.ot.SynthVoiceHeader
                    .hitAt(currentDate.toString(), notePage!!, cx, cy)) {
                showSynthVoiceList(WHO_ANSWERS_TITLE) { voice ->
                    setSynthPageVoice(voice)   // redraws the header
                    showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.answersNext(voice), binding.root)
                }
                return true
            }
            // ⟡ cards answer a TAP with the correspondence itself (Michael, 08-30: "Click the
            // sticky, the synth correspondence appears" — and the correspondence IS a page:
            // "it's a note page really"). The card's ledger:// provenance names that page;
            // tap = open it. Standing on the page already → fall through (you are IN the
            // correspondence). Hold keeps the verb menu; drag/move untouched — only a tap
            // that LANDS on a ⟡ card is consumed.
            val el = imageElementAt(cx, cy)
            // ❖ A Synthesize page's links (synth-list-links §4): a source card opens its article in
            // the reader here; a reply that cites, and the Sources card, offer their ↗ rows. Every
            // other card (and a reply citing nothing) falls through unchanged.
            if (el != null && ::calendarDay.isInitialized &&
                com.toolsboox.plugin.calendar.ui.SynthListRun.onTap(this, calendarDay, notePage.orEmpty(), el)) return true
            if (el != null && (el.sourceLabel.startsWith("⟡") || el.sourceLabel.startsWith("❖")) &&
                el.sourceLink.startsWith("ledger://")) {
                val ref = el.sourceLink.removePrefix("ledger://")
                val slash = ref.indexOf('/')
                if (slash > 0) {
                    val date = runCatching { java.time.LocalDate.parse(ref.substring(0, slash)) }.getOrNull()
                    val key = ref.substring(slash + 1)
                    if (date != null && key.isNotBlank() && !(date == currentDate && key == notePage)) {
                        CalendarNavigator.toDayNote(this, date, key)
                        return true
                    }
                }
            }
            // A tap on what was added to a pick plays or opens THAT one (add-to-pick pin §5);
            // outside every slot the tap does what it always did.
            if (el != null) com.toolsboox.plugin.feeds.ot.AddToPick.slotAt(el, cx, cy)?.let { slot ->
                com.toolsboox.plugin.feeds.ot.AddToPick.openSlot(this, calendarDayService, documentsRoot(), el, slot,
                    calendarDay.avGrams.toList())
                return true
            }
            return false
        }
        // The dashboard is page ink now: its taps are lightweight page-region hit-rects (the
        // PickingsCover idiom), recorded by CalendarDayPage.drawDashboardRight. A finger tap on a
        // Notes mini or the Doodle area OPENS that page; a tap on a "→" promote glyph gathers the
        // strokes written in the Tasks/Memo region and makes a LedgerItem. Anywhere else falls
        // through so the pen keeps inking those write-by-hand regions.
        com.toolsboox.plugin.calendar.ot.CalendarDayPage.dashHitAt(cx, cy)?.let { hit ->
            when (hit.kind) {
                CalendarDayPage.DashHit.Kind.NOTE ->
                    CalendarNavigator.toDayNote(this, currentDate, hit.pageKey)
                // DOODLE is no longer a tap target — the Doodle region is inked directly on
                // the today page (draw on it in place), so a tap there falls through to ink.
                CalendarDayPage.DashHit.Kind.DOODLE -> return false
                // A BOOKSHELF cover opens its book: through the shelf with a pending open —
                // the shelf's own open() owns positions, formats and the declared-tree seam.
                CalendarDayPage.DashHit.Kind.BOOK -> {
                    com.toolsboox.plugin.reader.ui.BookshelfFragment.pendingOpenName = hit.pageKey
                    findNavController().navigate(R.id.action_to_reader)
                }
                CalendarDayPage.DashHit.Kind.TASK_PROMOTE -> promoteTasks()
                CalendarDayPage.DashHit.Kind.MEMO_PROMOTE -> memoPromoteMenu()
            }
            return true
        }
        val ghost = com.toolsboox.plugin.calendar.ot.CalendarDayPage.ghostAt(cx, cy)
        Timber.i("ghost-row tap: cx=%.0f cy=%.0f hit=%s", cx, cy, ghost?.item?.text ?: "∅")
        if (ghost == null) return false
        ghostMenu(ghost)
        return true
    }

    // The today dashboard (notePage == null) is drawn into a portrait 1404×1872 sheet whose right
    // column stacks four zones down the full height. In landscape it FILLS THE ENTIRE WIDTH and
    // scrolls down — NOT a letterbox-fit-whole (Michael: "it should take up FULL WIDTH when
    // landscape … the extra space on the right instead of room to scroll down"). Returning false
    // here takes [refitZoomToWidth]'s width-fill branch, whose totalScale is exactly sw/1404: the
    // page is as wide as the surface, taller than the screen, and the lower zones (Notes minis,
    // Doodle) are reached by scrolling — the one-finger pan + panOverscroll below bring the bottom
    // Doodle box all the way under the pen. An earlier build letterboxed the whole sheet (every zone
    // on-screen at zoom 1.0); Michael rejected the side-gaps, so full-width is the ruling.
    override fun landscapeFitsWholePage(): Boolean = false

    // On the dashboard the Doodle box wants magnification to write into, and once zoomed the reader
    // must still be able to scroll to the other zones — so a one-finger drag pans here rather than
    // being swallowed ("can't scroll anymore"). Two-finger pan and the pen (stylus, a separate path)
    // are unchanged; other surfaces keep the palm-safe two-finger default.
    // TWO FINGERS TO PAN, EVERYWHERE (Michael, 09-14: "I want to be able to pan and zoom using
    // two fingers instead of one… when I attempt to write I frequently move the canvas because
    // palm rejection isn't working"). The day page used to let ONE finger pan while zoomed; a
    // resting palm was that finger. The hand button on the toolbar still opens one-finger
    // gestures for anyone who wants them, as an explicit choice.
    override fun oneFingerPanWhenZoomed(): Boolean = false

    /** The today-page dashboard gets half a surface of overscroll so the bottom Doodle
     *  box can be panned all the way under the pen (the "it kinda can pan" reach gap). */
    override fun panOverscrollPx(surfaceDim: Float): Float =
        if (notePage == null) surfaceDim * 0.5f else 0f

    /** "Pin to Board…": file this gram onto a kanban board as a card that shows the picture.
     *  The PNG rides in the card's `crop` (base64, display=INK) — same wire shape as iOS. */
    override fun onImagePinToBoard(element: ImageElement) {
        val ctx = context ?: return
        val boards = com.toolsboox.plugin.calendar.ot.BoardsStore.list(ctx)
        val labels = (boards.map { it.name.ifBlank { "Untitled" } } + "Unfiled (All boards)").toTypedArray()
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Pin to which board?")
            .setItems(labels) { _, which ->
                val boardId = if (which < boards.size) boards[which].id else ""
                val today = LocalDate.now()
                val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                    id = "li-" + java.util.UUID.randomUUID().toString().lowercase(),
                    kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                    text = element.sourceLabel.ifBlank { "Gram" },
                    date = java.util.Date(),
                    // Phase R writers speak inline base64, so a ref'd gram's face is resolved
                    // back to it here; a missing blob pins a blank face rather than failing.
                    crop = com.toolsboox.ot.LedgerMedia.resolveBase64(ctx, element.data, element.dataRef) ?: "",
                    display = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Display.INK,
                    source = "gram", stage = "todo", board = boardId
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val root = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
                    else java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")
                    // Same-file discipline as the share-in path: the open page re-saves today's
                    // file wholesale on every pen-up, so this cycle must serialize against it.
                    val pinned = DayLocks.withDay(today) {
                        // The presenter's read-only rule holds for this load→mutate→save too: a
                        // day file that exists but wouldn't load must not be saved over.
                        // A day being minted here is a day that does not exist yet, which is the
                        // one moment a device gets to supply a start hour — so it seeds from the
                        // synced setting rather than writing a null the renderers then have to
                        // guess at.
                        val day = calendarDayService.loadForWrite(
                            root, today, PagePrefs.settingHour(ctx), java.util.Locale.getDefault()
                        ) ?: return@withDay false
                        day.ledgerItems.add(item)
                        calendarDayService.save(root, today, day)
                        true
                    }
                    if (!pinned) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "Couldn't pin — today's page won't read", android.widget.Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item) }
                    withContext(Dispatchers.Main) {
                        // When the open page IS today, hand its in-memory copy the item too —
                        // otherwise the next pen-up save overwrites the file without it.
                        if (today == currentDate && ::calendarDay.isInitialized &&
                            calendarDay.ledgerItems.none { it.id == item.id }
                        ) calendarDay.ledgerItems.add(item)
                        android.widget.Toast.makeText(ctx, "Pinned to board", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .showModal()
    }

    private data class GramPlace(val date: LocalDate, val page: String, val millis: Long)

    /** Content-address key: lineage id if set, else md5 of the bytes (same md5 as iOS → cross-device). */
    private fun contentKey(gramId: String?, data: String): String =
        if (!gramId.isNullOrBlank()) gramId else com.toolsboox.ot.CryptoUtils.md5Hash(data.toByteArray())

    private fun gramPlacements(key: String): List<GramPlace> {
        val root = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
        else java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")
        val calendarRoot = java.io.File(root, "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val out = mutableListOf<GramPlace>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val m = Regex("day-(\\d{4})-(\\d{2})-(\\d{2})").find(file.name) ?: return@forEach
                val ld = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return@forEach
                val day = runCatching { calendarDayService.load(file) }.getOrNull() ?: return@forEach
                for (img in day.imageElements) if (contentKey(img.gramId, img.data) == key) {
                    out.add(GramPlace(ld, img.page.ifBlank { "day" }, img.timestamp))
                }
            }
        return out.sortedByDescending { it.millis }
    }

    /**
     * A text box finished a drag. On the intake page, a box carrying a link
     * dropped onto a panel files that link into the panel's lane — placement
     * is the gesture that runs the pipeline; sharing alone queues nothing.
     */
    override fun onTextBoxDropped(element: TextElement) {
        if (notePage != "intake") return
        val bounds = textElementBounds(element)
        // Resolved against the geometry as DRAWN — the bands are sized to their content, so the
        // boxes on screen are the only honest answer to "which band was this dropped on".
        val panel = CalendarDayPageIntake.panelAt(bounds.centerX(), bounds.centerY()) ?: return
        val url = element.sourceUrl
            ?: com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(element.text).firstOrNull()
            ?: return
        val title = element.text.lines().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
        // This drop does one thing — send the link to MichaelFilter — so with no key saved it says
        // so instead of "Filed to …" over a send that would never happen. A turned-down key still
        // queues (kept, re-sent once a key is saved) but says what is wrong rather than "Filed".
        val connector = com.toolsboox.plugin.michaelfilter.nw.MichaelFilterConnector
        if (!connector.isConnected(requireContext())) {
            android.widget.Toast.makeText(requireContext(), connector.NOT_CONNECTED, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val submission = com.toolsboox.plugin.michaelfilter.da.IntakeSubmission(
            linkUrl = url, linkKind = panel.kindKey, linkTitle = title
        )
        com.toolsboox.plugin.michaelfilter.nw.IntakeQueue.enqueue(requireContext(), submission)
        com.toolsboox.plugin.michaelfilter.nw.IntakeQueue.scheduleDrain(requireContext())
        Timber.i("Text box dropped on ${panel.kindKey}: $url")
        val said = if (connector.isTurnedDown(requireContext())) connector.KEY_TURNED_DOWN else "Filed to ${panel.title}"
        android.widget.Toast.makeText(requireContext(), said, android.widget.Toast.LENGTH_SHORT).show()
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
     * The next day refused the procrastinated ink — its page won't read, so nothing was written
     * there. The eraser had already taken that ink off THIS page and tombstoned it (the move runs
     * after the pen lifts), so without this the refusal would have dropped it: put it back where it
     * was. Only while the same day and schedule style are still up; a page turned since will show
     * the file, which the erase's own save already reached. (A task whose every stroke went with
     * the ink stays deleted — its item is not rebuilt here.)
     */
    fun restoreProcrastinated(date: LocalDate, style: String, strokes: List<Stroke>) {
        if (!isAdded || !::calendarDay.isInitialized || !::calendarPattern.isInitialized) return
        if (date != currentDate || style != calendarStyle || notePage != null || strokes.isEmpty()) return
        val ids = strokes.mapTo(HashSet()) { it.strokeId.toString().lowercase() }
        calendarDay.deletedStrokeIds.removeAll { it.lowercase() in ids }
        val current = calendarDay.calendarStrokes[style].orEmpty()
        val have = current.mapTo(HashSet()) { it.strokeId }
        val restored = current + Stroke.listDeepCopy(strokes.filter { it.strokeId !in have })
        calendarDay.calendarStrokes[style] = restored
        calendarPattern.updateDay(calendarDay)
        applyStrokes(Stroke.listDeepCopy(restored), true)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
    }

    /**
     * Erased strokes MUST be tombstoned (deletedStrokeIds) or the union sync-merge resurrects them
     * on the next open ("erase with no tombstone can resurrect", per CalendarDayMerger).
     */
    override fun onStrokesDeleted(strokeIds: List<java.util.UUID>) {
        if (!::calendarDay.isInitialized || strokeIds.isEmpty()) return
        val gone = strokeIds.map { it.toString() }.toSet()
        var changed = false
        for (s in gone) {
            if (s !in calendarDay.deletedStrokeIds) { calendarDay.deletedStrokeIds.add(s); changed = true }
        }
        // An OCR'd task/note follows its ink: once every stroke that produced it is erased, drop
        // the ledger item too (and tombstone it) so erasing on the day page really removes the task.
        val orphaned = calendarDay.ledgerItems.filter { item ->
            item.strokeIds.isNotEmpty() &&
                item.strokeIds.all { it in gone || it in calendarDay.deletedStrokeIds }
        }
        if (orphaned.isNotEmpty()) {
            calendarDay.ledgerItems.removeAll(orphaned)
            // Erasing every stroke that produced the item IS deleting it — user intent, so it
            // gets the full item tombstone (auto-extracted or not), or sync re-adds it.
            for (item in orphaned) calendarDay.tombstoneLedgerItem(item.id)
            changed = true
        }
        if (changed) {
            calendarPattern.updateDay(calendarDay)
            presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        }
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
        // What the note button toggles against: whether THIS view is a free-form NOTE page (numeric)
        // and which date it's on — so tap 1 resumes your note and tap 2 flips to that day's page.
        requireContext().getSharedPreferences("ledger_notes", 0).edit()
            .putBoolean("on_note_page", notePage?.toIntOrNull() != null)
            .putString("current_view_date", currentDate.toString())
            .apply()
        // "Notes" entries reopen this exact page later (numeric pages only).
        notePage?.let { CalendarNavigator.rememberNoteLocation(requireContext(), currentDate, it) }

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
            // A shared LINK comes in as an object — a link card you can move and connect — not a
            // pasted line of text. This is the natural intake the wrench button was standing in
            // for; sharing from another app IS the button. Anything without a URL is still text.
            if (!sharedUrl.isNullOrBlank()) {
                Timber.i("Shared link → link object ($sharedUrl)")
                val title = shared.substringBefore('\n').trim().takeIf { it != sharedUrl }.orEmpty()
                placeLinkObject(sharedUrl, title, CANVAS_WIDTH / 2f, CANVAS_HEIGHT / 2f, alsoFile = false)
            } else {
                Timber.i("Shared text queued for insert (${shared.length} chars)")
                queueSharedTextInsert(shared, sharedUrl)
            }
        }

        // The placeholder day the surface holds until the presenter's load lands. It does not
        // exist on disk yet, which is the one moment a device gets to supply a start hour.
        calendarDay = CalendarDay(
            currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
            mutableListOf(), mutableListOf(), true, PagePrefs.settingHour(requireContext())
        )

        binding.navigatorImageView.setOnTouchListener { view, motionEvent ->
            // On a NOTES surface (write / grid / sketch / numbered note page — NOT the plain day and
            // NOT a ritual Flow/Garden station) the almanac strip becomes a FILTER: tapping a period
            // slot opens the period-scoped "Notes & Tags" list for that level instead of jumping to
            // the Week/Month/… almanac. The plain day page and the ritual pages keep the almanac
            // behaviour unchanged (onSelectPeriod stays null for them). Bounded to this handler.
            // ALL STARS: the almanac strip filters the register instead of navigating. A period slot
            // widens the window in place; the day slot returns it to today's stars. Nothing leaves
            // the page, because the page IS the answer — it just reaches further back.
            if (notePage == CalendarDayPageIntake.INTAKE_PAGE) {
                CalendarDayNavigator.onTouchEvent(
                    view, motionEvent, this@CalendarDayFragment, calendarDay,
                    onSelectPeriod = { level, _ ->
                        starsScope = if (level == "day") "day" else level
                        redrawIntakePage()
                        showMessage("All Stars · ${starsScopeLabel()}", binding.root)
                    }
                )
            } else if (isNotesTagSurface(notePage)) {
                CalendarDayNavigator.onTouchEvent(
                    view, motionEvent, this@CalendarDayFragment, calendarDay,
                    onSelectPeriod = { level, date ->
                        // The day slot still means "go to the day page"; the period slots open the list.
                        if (level == "day") CalendarNavigator.toDayPage(this@CalendarDayFragment, date)
                        else NotesTagsFragment.open(this@CalendarDayFragment, date, level)
                    }
                )
            } else {
                CalendarDayNavigator.onTouchEvent(view, motionEvent, this@CalendarDayFragment, calendarDay)
            }
        }

        binding.surfaceView.setOnHoverListener { _, motionEvent ->
            return@setOnHoverListener callback(motionEvent, true)
        }
        binding.surfaceView.setOnTouchListener { view, motionEvent ->
            // Zoom's armed box (Michael 2026-10-12) owns the next drag: a finger that starts it
            // slowly must not open the page's long-press menu instead.
            if (!marqueeLive() && handleFingerLongPress(motionEvent)) return@setOnTouchListener true
            if (callback(motionEvent, false)) return@setOnTouchListener true
            if (handleZoomPanTouch(motionEvent)) return@setOnTouchListener true

            val rawGesture = gestureListener.onTouchEvent(gestureDetector, view, motionEvent)
            val gestureResult = if (twoFingerGesture) rawGesture else OnGestureListener.NONE

            // No intake tap interception any more. It existed to hit-test grams against the
            // template's painted cells; the grams are real elements, so the element layer resolves a
            // tap by itself — the same code path every other making surface uses.

            // The daily Pickings cover: its recent-board tiles are pixels in the template, so a
            // tap resolves against the rectangles PickingsCover recorded when it drew the band.
            if (notePage == com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY &&
                handlePickingsCoverTap(motionEvent, gestureResult)
            ) return@setOnTouchListener true

            if (notePage == null && handleEventTap(motionEvent, gestureResult))
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

        binding.toolbarDrawing.toolbarSwipeUp.setOnClickListener { ritualStepBack() }
        binding.toolbarDrawing.toolbarSwipeDown.setOnClickListener { ritualStepForward() }
        // Calendar button (hidden with the upstream toolbar group; kept wired for performClick).
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener { showLedgerHub() }
        // The header's top-left ☰ retired with the rail: its one job on this page was
        // showLedgerHub(), and the rail's Hub button is the same door — two hamburgers to one
        // hub is chrome saying the same thing twice (Michael: "the hamburger in the upper left
        // becomes unnecessary with the hamburger on the popout"). The rail — or its tucked
        // strip, which can never be hidden with it — is now the one way in. When the rail
        // spreads to other surfaces, their ☰ retires with the same conversion.
        binding.goAppsButton.visibility = View.GONE
        bindDirectoryChip()
        // The notes band binds against the placeholder day here (just the standing Doodle) and
        // rebinds in renderPage once the day's real pages are known.
        bindNotesBand()

        // BOTH floating pills retired from this page — the tucked action rail (built on the
        // toolbar's own slot in the layout, see the TuckPanel construction below) carries the
        // nav layer AND the tools now, the way the Weeks side toolbar always carried its own.
        // The pills' views stay in the layout, hidden, so nothing else referencing them breaks.
        binding.navWidget.visibility = View.GONE
        binding.toolWidget.visibility = View.GONE

        // Inline ‹ N › pager. Two families carry it, never the ritual stations:
        //  • Numeric notes ("0","1",…): ‹ › walk the whole DATED NOTE — the day's numeric run plus
        //    its write/grid/sketch/pickings pages, one note ([com.toolsboox.plugin.calendar.ot.DatedNote]).
        //    The label is the position within it ("3/7"); tapping it opens the jump picker.
        //  • The named write / grid / sketch surfaces: these get their OWN sub-page series keyed
        //    "<base>#<n>". Their ‹ › must NOT run through the stepper (that walks sections) — next
        //    always EXTENDS to the next sub-page, prev floors at the base. The number is subIndex+1.
        val pagerBase = baseNotePage(notePage)
        // The daily Doodle rides the dated-note pager with the numeric run: it is a page of the
        // dated note ([DatedNote.DOODLE], seated right after the numerics), so ‹ › walk on and
        // off it exactly as they walk the numbered pages.
        if (notePage?.toIntOrNull() != null || notePage == com.toolsboox.plugin.calendar.ot.DatedNote.DOODLE) {
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.VISIBLE
            binding.notePagerNext.visibility = View.VISIBLE
            updateDatedNotePagerLabel()
            binding.notePagerPrev.setOnClickListener { datedNoteStep(-1) }
            binding.notePagerNext.setOnClickListener { datedNoteStep(+1) }
            binding.notePagerLabel.setOnClickListener { showNotePageJump() }
            // HOLD: the three verbs (create · most recent · text note). The tap stays the pages of
            // THIS note; the hold is how you leave it for another one.
            binding.notePagerLabel.setOnLongClickListener {
                com.toolsboox.plugin.feeds.ui.showDatedNoteHoldSheet(this, currentDate)
                true
            }
        } else if (isSubPageableBase(pagerBase)) {
            val base = pagerBase!!
            val sub = notePageSubIndex(notePage)
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.VISIBLE
            binding.notePagerNext.visibility = View.VISIBLE
            refreshNotePagerLabel()
            binding.notePagerPrev.setOnClickListener {
                if (sub > 0) {
                    val target = if (sub - 1 == 0) base else "$base#${sub - 1}"
                    CalendarNavigator.toDayNote(this, currentDate, target)
                }
            }
            binding.notePagerNext.setOnClickListener {
                CalendarNavigator.toDayNote(this, currentDate, "$base#${sub + 1}")
            }
            // Tap the number → this kind's DIRECTORY, right beneath the almanac nav — the Text-Notes
            // "≡ Notes" idea, for cohesion across the note surfaces.
            //
            // ONE destination for all of them now. Synthesize used to peel off here into
            // showSynthPicker while write/grid/sketch got the sub-page jump, so the same control on
            // two surfaces Michael calls the same shape opened two different menus: one that could
            // name a document but not list its pages, one that could list pages but not name
            // anything. [showNoteSubPageJump] is both, and the full sortable directory is still one
            // row inside it ("🗂 All …") for when the menu isn't enough.
            binding.notePagerLabel.isClickable = true
            binding.notePagerLabel.setOnClickListener { showNoteSubPageJump(base) }
            binding.notePagerLabel.setOnLongClickListener {
                com.toolsboox.plugin.feeds.ui.showDatedNoteHoldSheet(this, currentDate)
                true
            }
        } else if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage)) {
            // BOARDS ARE PAPER (Michael, ruled 08-27): a board's chip opens THE SAME menu every
            // note opens — showNoteSubPageJump, with ▤/✎/export in the same seats — not a
            // different panel ("pickings seems to open a different panel of sorts than notes").
            // The boards directory survives as a row INSIDE that menu (see the ❝ row there);
            // one open path, one panel, one menu shape. No ‹ › series — boards aren't a run.
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.GONE
            binding.notePagerNext.visibility = View.GONE
            refreshNotePagerLabel()
            binding.notePagerLabel.isClickable = true
            binding.notePagerLabel.setOnClickListener {
                showNoteSubPageJump(baseNotePage(notePage) ?: return@setOnClickListener)
            }
            binding.notePagerLabel.setOnLongClickListener {
                com.toolsboox.plugin.feeds.ui.showDatedNoteHoldSheet(this, currentDate)
                true
            }
        }

        // THE SUMMON LEFT THE PILL for the tuck-panel rail (Michael, 08-31: "leaving the
        // button at the top doesn't make sense if you're scrolled down. I see why you wanted
        // them in the tuck panel") — the rail's ❖ is reachable at any scroll and never tucks
        // away with the pill. The pill keeps only its paging duties.
        binding.notePagerSynth.visibility = View.GONE
        binding.notePagerSynth.setOnClickListener(null)
        binding.notePagerTab.setOnLongClickListener(null)
        // THE PILL BAND EATS ITS STRAYS (field, 08-30: "the glyph goes to All Picks" — a finger
        // aiming at pill chrome that lands between children fell THROUGH the row onto the page's
        // drawn chrome beneath, whose tap router can navigate — board tiles, station furniture).
        // A clickable no-op parent consumes what its children don't; child taps keep priority.
        binding.notePager.isClickable = true
        binding.notePager.setOnClickListener { /* the band is chrome, not page */ }

        // The writing-state tab (see onStrokeChanged): tap = bring the pill back for this visit.
        binding.notePagerTab.setOnClickListener {
            pagerSteppersTucked = false
            binding.notePagerTab.visibility = View.GONE
            binding.notePager.visibility = View.VISIBLE
            binding.notePager.bringToFront()
        }

        // Lift the floating overlay above the drawing surface without elevation (which
        // renders as an ugly black shadow-box on e-ink).
        binding.notePager.bringToFront()

        // The tucked action rail — the Weeks side toolbar's tuck-in/tuck-out idiom, rebuilt on
        // the toolbar's own slot in the constraint chain (in flow: the page ends where the rail
        // begins, so the pen stays honest with no exclude rects and no modal pause). Top
        // cluster: the tools the floating pill used to float. Bottom cluster: the nav actions
        // the retired nav pill carried, plus the ⇄ edge-hop the toolbar always had. Every
        // button drives the real hidden toolbar action, so the Onyx ink wiring is unchanged.
        // Plan v6 S1.2 (Michael 2026-10-10): an ink rail — ⛶ leads it, directly under the date
        // strip that spans the whole top, instead of a tucked strip; full screen's way back is the
        // bottom pill's corner (Michael 2026-10-11: "The one top left and the one bottom right"):
        // ⛶ · Pen · Eraser · Undo, on the day face and its notes alike.
        val rail = newInkRail(binding.toolbarDrawing, "day", sideIsLeft = { !railOnRight() })
        tuckPanel = rail
        // The pen tools the pill's corner offers too: one item each, so the column seat and the
        // corner are the same action (and the same hold).
        val penItem = com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_toolbar_pen,
            getString(R.string.calendar_drawing_toolbar_pen), activeId = "pen",
            // Holding the pen opens the FULL chooser — every nib, width, colour (Michael 2026-10-11:
            // "not just a radio button for either :P"); the old two-way picker is gone.
            longPress = { if (showPenChooser()) rail.markActive("pen") }) {
            binding.toolbarDrawing.toolbarPen.performClick(); rail.markActive("pen")
        }
        val eraserItem = com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_toolbar_eraser,
            getString(R.string.calendar_drawing_toolbar_eraser), activeId = "eraser",
            longPress = { showClearPageConfirm() }) {
            binding.toolbarDrawing.toolbarEraser.performClick(); rail.markActive("eraser")
        }
        val undoItem = com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_toolbar_undo, "Undo") {
            binding.toolbarDrawing.toolbarUndo.performClick()
        }
        val redoItem = com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_toolbar_redo, "Redo") {
            binding.toolbarDrawing.toolbarRedo.performClick()
        }
        // Zoom: one item for the column's seat and full screen's row (the pill's PenTools).
        val zoomItem = zoomSeatItem()
        rail.setClusters(
            top = listOf(
                // ☰ Hub led the rail (Michael: "hamburger at the top of the popout") until Michael
                // 2026-10-12 ("Those too."): it is the wrench's first row now, and the left edge's
                // Back opens it ([openHub] — this page's own hub, the recent books folded in).
                // ☀ Today sat directly under ☰ Hub (Michael 2026-10-10: "I would like today below
                // hub on today in the tuck panel") — it leads now, still near the top, out of
                // where the hand rests.
                // The nav pill's centre button, as an icon: jump to today — a past day goes to
                // today's version of this section, a section on today goes to today's page.
                // (It no longer doubles as the sections menu when already on today; that is 🗂
                // Sections, in the nav cluster.)
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today") {
                    onCenterTapped()
                },
                // The wrench is the rail's More: its own rows only. Seats never fold into it for
                // height — a column that runs short scrolls (Michael 2026-10-10: "scrollable if
                // there are more buttons"). It stands directly under Today since ☰ Hub became its
                // first row (review 2026-10-12): the frame's Today · More, as on every panel and
                // on iOS, and the touch route to the Hub up where ☰ stood — on a B6 whose Back
                // carries no edge it is the only one. Pen and the tools below keep the places
                // they had under ☰ Hub · Today.
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_wrench, "Quick tools") {
                    showWidgetGearMenu()
                },
                penItem,
                eraserItem,
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_toolbar_lasso,
                    getString(R.string.calendar_drawing_toolbar_lasso), activeId = "lasso") {
                    binding.toolbarDrawing.toolbarLasso.performClick(); if (lassoArmed()) rail.markActive("lasso")
                },
                // ❖ THE SUMMON, seated in real chrome (Michael, 08-31) — one button, the
                // settled two-mode model: on the conversation page it answers the page (the
                // circle rides when one is live, else the delta); on any other page it is
                // the drive-by and takes the circle — never "sometimes a note, sometimes not".
                // Its HOLD (Michael, 2026-10-12, the hold-to-act canon): "Synth with…" — the voice
                // list; a live circle rides along.
                com.toolsboox.ot.TuckPanel.Item(0, "Synth", glyph = "❖", longPress = { synthWithFromRail() }) {
                    val sel = currentLassoSelection()
                    if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(
                            baseNotePage(notePage) ?: notePage)) {
                        if (sel.isNotEmpty()) synthCorrespond(selection = sel) else synthCorrespond()
                    } else {
                        if (sel.isNotEmpty()) driveBySynthInk(sel)
                        // No circle: THE PAGE is the selection (Michael, 09-13, on All Picks:
                        // "it tells me to circle some ink first… I want it to pull up a
                        // synthesized page with all the picks, their provenance and the
                        // handwritten sayings attached to them"). Same door as the long-hold's
                        // "Synthesize this page…", picks' ink read too.
                        else com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
                            requireContext(), "Synthesize this page", present = { showModal(it) },
                            onTopic = { t -> proposeTopic(t) },
                            onSiteLinks = { gramsFromSites() }, onGroup = { groupPicksIntoSections() }) { e ->
                            runEngine(e, thisPageMaterial(), pickInk = true)
                        }
                    }
                },
                undoItem,
                redoItem,
                // Zoom to a box (Michael 2026-10-12: "Add a zoom button with marquee zoom to the tuck
                // panels for places like today notes all picks all stars write panels etc") —
                // directly after Redo, the same seat on every page this rail serves: the day face,
                // its notes, All Picks, All Stars, Write, Grid, Jot, Synthesize and the stations.
                zoomItem
            ),
            bottom = listOf(
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up, "Section up") {
                    binding.toolbarDrawing.toolbarSwipeUp.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down, "Section down") {
                    binding.toolbarDrawing.toolbarSwipeDown.performClick()
                },
                // 🗂 Sections — the 📧 Mail door too (Michael 2026-10-10: "'sections' should have email").
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_folder, "Sections") {
                    showSectionSwitcher()
                },
                // The toolbar's own edge-hop: performClick runs the real switch-side handler
                // (onSideSwitched → updateToolbar flips the shared side pref + re-navigate), so
                // the rail hops edges by exactly the mechanism the Weeks toolbar hops.
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_toolbar_switch_side, "Other side") {
                    binding.toolbarDrawing.toolbarSwitchSide.performClick()
                }
            )
        )
        // The bottom pill's corner (Michael 2026-10-11): ⛶ · Pen · Eraser · Undo, the column's own
        // items, on every day page — the day face takes ink too, and iOS's corner offers the four
        // on every page of ink (parity, review 2026-10-11; a corner that flipped between ⛶ and Pen
        // at each walk step changed its tap under the hand).
        rail.setPenTools(com.toolsboox.ot.TuckPanel.PenTools(penItem, eraserItem, undoItem, redoItem,
            pickUpPen = { pickUpPen(); rail.markActive("pen") }, zoom = zoomItem))
        // Pen is the default tool — box it on the rail from the start.
        rail.markActive("pen")
        // …and Zoom boxed if the page already stands magnified.
        syncZoomSeat()

        utils.updateToolbar(binding)
        // (The date bar's 52dp hamburger inset left with the hamburger; since 2026-10-10 the strip
        // spans the whole top and the rail starts beneath it — CalendarUtils.updateToolbar.)
        initializeSurface(true)
    }

    // ── THE DATED NOTE ──────────────────────────────────────────────────────────────────────────
    //
    // The day file already IS the dated note (DESIGN-notes-ia-2026-08-22): every page kind lives
    // in one noteStrokes map, so the unification is PRESENTATION ONLY — the pager walks the
    // existing keys in [com.toolsboox.plugin.calendar.ot.DatedNote]'s stable order and never
    // rekeys them (rekeying would break the byte-identical iOS convergence fixtures).

    companion object {
        /** The voice list's titles — the same words on iOS (Michael, 2026-10-12). */
        const val SYNTH_WITH_TITLE = "Synth with…"
        const val WHO_ANSWERS_TITLE = "Who answers next?"

        /** One-shot: a gather's completion asked for ⚗ — the next arrival on the minted note
         *  opens the engine picker itself, so "Synthesize it" is genuinely one tap (Michael's
         *  answer #3 on DESIGN-gather-verbs). The pending-open idiom, same as the widget's. */
        var pendingSynthPicker = false

        /**
         * The day page standing on screen (set onResume, cleared onPause) — where a ❖ reply lands
         * when the fragment that summoned it is gone. Every page turn, ladder step and B6
         * re-layout rebuilds this fragment, so a 40–75 s summon routinely outlives its summoner:
         * the -322 field log shows three summons, three rebuilds, three replies to dead fragments
         * (Michael, 2026-10-12). Weak, so a leaked reference can never pin a dead view.
         */
        @Volatile
        private var liveRef: java.lang.ref.WeakReference<CalendarDayFragment>? = null

        /** The resumed day page, if any. */
        fun live(): CalendarDayFragment? = liveRef?.get()?.takeIf { it.isAdded && it.isResumed }

        /** The resumed day page showing [date], if any. */
        fun liveOn(date: java.time.LocalDate): CalendarDayFragment? = live()?.takeIf { it.currentDate == date }

        /** The day the resumed page shows — the "today" the landing words are relative to. */
        fun liveDate(): java.time.LocalDate? = live()?.currentDate

        /** When the persona set and the pages' voices were last round-tripped on the way in
         *  (elapsedRealtime) — app-wide, since every page turn makes a new fragment. */
        @Volatile
        private var voicesResumeSyncAt = 0L

        /** Due at most once a minute: true, and the clock reset, when it is. */
        private fun voicesResumeSyncDue(): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            if (voicesResumeSyncAt != 0L && now - voicesResumeSyncAt < 60_000L) return false
            voicesResumeSyncAt = now
            return true
        }

        /**
         * A sync merged a persona or a page voice that came from another device: the header on the
         * Synthesize page standing NOW redraws — whichever fragment that is (a sync started by one
         * page routinely finishes under the next; review of b29cabbc: the header kept "❖ Spiral"
         * after Spiral was deleted on the iPad, while ❖ answered as the Correspondent). Any thread.
         */
        fun redrawSynthHeader() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                live()?.takeIf { it.onSynthPage() }?.rerenderStandingPage()
            }
        }

        /** Each URL in [answer] on one of his sites, paired with what the catalogue knows of it —
         *  the cited pages a reply's link grams are made from. Blocking (the catalogue). */
        fun citedSitePairs(
            ctx: android.content.Context, answer: String,
        ): List<Pair<String, com.toolsboox.plugin.calendar.nw.SiteLinker.Hit>> {
            val urls = com.toolsboox.plugin.calendar.nw.SiteLinker.citedSiteUrls(ctx, answer)
            if (urls.isEmpty()) return emptyList()
            val sites = com.toolsboox.plugin.calendar.nw.SiteStore.all(ctx)
            val cat = runCatching { com.toolsboox.plugin.calendar.nw.SiteLinker.catalogue(ctx, sites) }.getOrDefault(emptyList())
            return urls.map { u ->
                val e = cat.firstOrNull { it.url.trimEnd('/') == u.trimEnd('/') }
                val host = runCatching { java.net.URI(u).host }.getOrNull().orEmpty()
                (e?.title ?: u) to com.toolsboox.plugin.calendar.nw.SiteLinker.Hit(u, e?.title ?: u, e?.siteName ?: host, 0)
            }
        }

        /**
         * Each (item, page) pair → a link gram on [pageKey] of [date] in the DAY FILE: the page's
         * Open Graph card, the site's picture, the item as the card's words. Blocking (fetches,
         * renders, writes under the day lock). Returns (placed, items that couldn't be carded). A
         * page standing on [date] merges them in afterwards ([mergeFileElements]).
         */
        fun placeSiteGramCards(
            service: com.toolsboox.plugin.calendar.fi.CalendarDayService, root: java.io.File,
            pairs: List<Pair<String, com.toolsboox.plugin.calendar.nw.SiteLinker.Hit>>,
            date: LocalDate, pageKey: String,
        ): Pair<Int, List<String>> {
            var placed = 0; val missed = mutableListOf<String>()
            for ((item, hit) in pairs) {
                // The site's own index already said what the page is and wears — skip the fetch then.
                val meta = if (hit.image != null) com.toolsboox.plugin.calendar.nw.SiteLinker.Meta(hit.title, hit.excerpt.ifBlank { null }, hit.image)
                           else com.toolsboox.plugin.calendar.nw.SiteLinker.meta(hit.url)
                val thumb = meta.image?.let { com.toolsboox.plugin.calendar.nw.SiteLinker.bitmap(it) }
                val title = meta.title ?: hit.title
                val card = runCatching {
                    com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                        hit.url, title, "read", thumb = thumb, sourceName = hit.siteName, excerpt = meta.description.orEmpty())
                }.getOrNull() ?: run { missed += item; continue }
                DayLocks.withDay(date) {
                    runCatching {
                        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                            service, root, card, date, pageKey,
                            sourceLink = hit.url, sourceLabel = title, cardText = item, sourceFeed = hit.siteName)
                    }
                }
                placed++
            }
            return placed to missed
        }
    }

    /** The dated note's page walk for the day in hand: the content-bearing keys plus the page you
     *  are standing on. Empty-day safe — before the load lands the walk is just the current page. */
    private fun datedNotePages(): List<String> {
        val keys: Collection<String> = if (::calendarDay.isInitialized)
            calendarDay.noteStrokes.filterValues { it.isNotEmpty() }.keys +
                calendarDay.imageElements.map { it.page }
        else emptySet()
        return com.toolsboox.plugin.calendar.ot.DatedNote.order(keys, notePage)
    }

    /** "3/7" — the position within the dated note. Set at pager wiring AND re-run when the day's
     *  load lands ([renderPage]), because the placeholder day knows none of its pages yet. */
    private fun updateDatedNotePagerLabel() {
        if (!::binding.isInitialized) return
        if (notePage?.toIntOrNull() == null && notePage != com.toolsboox.plugin.calendar.ot.DatedNote.DOODLE) return
        val pages = datedNotePages()
        val pos = pages.indexOf(notePage).takeIf { it >= 0 } ?: 0
        binding.notePagerLabel.text = "${pos + 1}/${pages.size}"
    }

    /** One ‹ › step through the dated note. Off the front = back onto the ritual walk, where page
     *  0's ‹ always led; off the end = a fresh numeric page, the note's growth edge. */
    private fun datedNoteStep(delta: Int) {
        val pages = datedNotePages()
        val pos = pages.indexOf(notePage).takeIf { it >= 0 } ?: 0
        val next = pos + delta
        when {
            next < 0 -> ritualStepBack()
            next >= pages.size -> {
                val newPage = (pages.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1) + 1
                CalendarNavigator.toDayNote(this, currentDate, newPage.toString())
            }
            else -> CalendarNavigator.toDayNote(this, currentDate, pages[next])
        }
    }

    /** Navigate notes by DATE: a date picker that opens the same note page on the chosen day. */
    private fun showNoteDatePicker() {
        val d = currentDate
        // Pen-guarded like showModal (DatePickerDialog is the platform AlertDialog, not
        // androidx): the Bigme daemon routes pen by view bounds, not Z-order — an
        // unguarded dialog over the ink surface gets its taps eaten by the canvas
        // underneath and "freezes" (the pager-modal field report).
        android.app.DatePickerDialog(requireContext(), { _, y, m, day ->
            CalendarNavigator.toDayNote(this, java.time.LocalDate.of(y, m + 1, day), notePage ?: "0")
        }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            setOnShowListener { onModalShown() }
            setOnDismissListener { onModalDismissed() }
            show()
        }
    }

    /**
     * The dated note's own menu, reduced to the ruling's shape (DESIGN-notes-ia-2026-08-22):
     * this note's PAGES — the whole dated note, each row wearing its page's template glyph —
     * then THE THREE DOORS: saved notes, new named note, export. Nothing else, except what
     * belongs to the pages themselves (＋ New page, the ▤ template property, and the date —
     * every day is a dated note, so "go to a date" is how you open another one).
     *
     * Export is a real door now (increment 3): page selection → the verb sheet — add to a new
     * note, PNG(s) or one stitched PNG, PDF — with the dispatch verbs (email / post / Bluesky /
     * task) visible but deferred to the next build. See the ⇪ EXPORT block below.
     */
    private fun showNotePageJump() {
        val ctx = context ?: return
        val pages = datedNotePages()
        val newPage = (pages.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: -1) + 1
        val pageKey = notePage ?: "0"

        // Rows as (label, action) pairs — the sub-page menu's model; the old `which - marks.size`
        // index arithmetic doesn't survive a list whose sections vary in length.
        val rows = mutableListOf<Pair<String, () -> Unit>>()

        // THE PAGE'S OWN VERBS LEAD (Michael, field, 08-27: "I can't change the templates easily …
        // that UX is kinda whack" — the ▤ he reached for sat below a page run that could be a
        // dozen rows long). What you DO to this page comes first; where you can GO comes after.
        rows += "▤  Template · ${
            com.toolsboox.plugin.calendar.ot.NotePageTemplates.label(resolvedPageTemplate(pageKey))
        }" to { showNoteTemplatePicker(pageKey) }

        // THE DATED NOTE'S PAGES — inline while the run is short; folded behind ONE row once it
        // isn't (his field verdict: "such a long list … this ux doesn't really work"). The
        // submenu rows wear each page's RESOLVED template glyph, as the inline run always did.
        fun pageRows(): List<Pair<String, () -> Unit>> = pages.mapIndexed { i, key ->
            val glyph = com.toolsboox.plugin.calendar.ot.NotePageTemplates.glyph(resolvedPageTemplate(key))
            (glyph + "  Page ${i + 1}" + if (key == notePage) "  ·  here" else "") to {
                if (key != notePage) CalendarNavigator.toDayNote(this, currentDate, key)
            }
        }
        if (pages.size <= 4) rows += pageRows()
        else rows += "🗐  Pages · ${pages.size}…" to { showIconMenu("Pages", pageRows()) }
        rows += "＋  New page" to { CalendarNavigator.toDayNote(this, currentDate, newPage.toString()) }
        // Page "0" is the day's standing page — its verb is CLEAR (content dies, the page
        // stays, as the day's pages always do); any other page deletes outright.
        run {
            val here = notePage ?: "0"
            if (here == "0") rows += "\ud83e\uddf9  Clear this page\u2026" to {
                deletePageContent(here, "Clear this page?")
            }
            else if (here.toIntOrNull() != null) rows += "\ud83d\uddd1  Delete this page\u2026" to {
                deletePageContent(here, "Delete this page?")
            }
        }
        // ＋ NEW NOTE — a second book on the day, distinct from a new PAGE of this one (Michael,
        // field, 08-27: "no option available in notes to create new note. New page is not the
        // same as new note"). The -27 trim cut this door too deep; it returns as the one mint
        // row, riding the -28 name pad (name it, or take the day's -1/-2/-3).
        rows += "＋  New note…" to {
            com.toolsboox.plugin.feeds.ui.showNewNotePicker(this, currentDate)
        }
        // ✎ NAME THIS NOTE — the day's own note is nameable now, like every other note
        // (Michael, 2026-08-23: "Everything gets a name, default name being the date"). Until he
        // names it, its header wears the date; naming it writes the WRITE daily entry, which is
        // date-scoped, so titling the 21st's note cannot title the 22nd's. The row says "Name
        // this", not "Rename", for the reason the named surfaces already give: most of the time
        // it has never had a name and is wearing its date, so "rename" would describe undoing
        // something he never did.
        rows += "✎  Name this note…" to {
            com.toolsboox.plugin.feeds.ui.promptRenameCurrentDocument(
                this,
                com.toolsboox.plugin.calendar.ot.LedgerDocuments.WRITE,
                com.toolsboox.plugin.calendar.ot.WritePageStore.DEFAULT_KEY,
                currentDate
            ) { redrawNoteTemplate() }
        }
        rows += "📅  Go to a date…" to { showNoteDatePicker() }

        // THE THREE DOORS. All notes opens THE NOTEBOOK — the one flat newest-first list where
        // dated and named notes stand together (the -19/-20 canon; Michael 08-27: the list should
        // read like a classic notes app). The kind-first root lives on behind the hub's Directory.
        // ONE NAME for the directory in both apps (Michael 2026-10-11: "there's no way to see a
        // notes directory right now") — the Hub and the family sheet already say "All notes…",
        // iOS takes the same name, and a third spelling here read as a different place.
        rows += "🗂  All notes…" to {
            com.toolsboox.plugin.feeds.ui.showNotebook(this, currentDate)
        }
        // Create-new-note, Save-as-a-note and Send-as-email left this menu (Michael, field,
        // 08-27: "a list of notes that is long and unclear"). Minting lives in the Notebook,
        // the hub and the family sheet; the other two live INSIDE ⇪ Export as its ➕ and ✉
        // verbs — one door instead of three saying overlapping things.
        // SAVE PAGES AS A NAMED NOTE — its own door (Michael, 2026-08-24: "I need a clear way to
        // handle saving a page/pages of a note as a named note").
        //
        // The verb already existed and was reachable only as one row inside "⇪ Export pages…",
        // which is the wrong word for it: exporting is what you do to send a thing OUT of the
        // ledger, and this keeps it IN — it mints a named note and copies the pages into it. A
        // person looking for "save this as a note" had no reason to open a menu called Export, and
        // that is a naming failure rather than a missing feature.

        // ✉ SEND AS AN EMAIL — its own door, through his OWN mail account.
        //
        // Michael, 2026-08-24: "I need an easy 'use as email' or 'send as email' option that makes
        // sense, too." There was one, and it did not make sense: it was a row inside "⇪ Export
        // pages…" that sent through the WEB BRIDGE — so it refused outright unless a WordPress
        // bridge was configured, and when it worked the letter did not come from him. He has real
        // IMAP/SMTP accounts in this app; a note that becomes an email should leave from one of
        // them, and he should SEE it before it goes.
        //
        // So this opens the composer he already knows, prefilled: the pages' words in the body,
        // the pages' picture attached. Nothing sends behind his back — the last step is the Send
        // button he has pressed a hundred times.

        // RESTORED (Michael, field, 08-31: "no option to send as email"). The door above is
        // described in the comment block that opens this section — his own 08-24 ruling, "I need
        // an easy 'use as email' or 'send as email' option that makes sense" — and its ROW had
        // gone missing while the comment stayed. `showEmailPagesPicker` was left with exactly one
        // reference in the whole file: its own declaration. A dead function under a live comment
        // is what a deleted menu row looks like six days later.
        //
        // It leads Export deliberately. Emailing pages is the errand; exporting them is the
        // general case, and the general case should not stand in front of the specific one.
        rows += "↥  Send / export…" to { showExportPagePicker() }

        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Jump to")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)   // pen-guarded: see showNoteDatePicker
        // The rows only exist once the list has laid out; apply the reading font then.
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    /** How many of a surface's documents the ‹ N › menu shows before deferring to the directory.
     *  Twelve is about a screenful on this hardware, which is the honest limit of a menu you read at
     *  a glance; past it you want a sort and a filter field, and those live one row down in
     *  "🗂 All …". */
    private val DOCUMENTS_IN_PAGER_MENU = 12

    /**
     * The named-base sibling of [showNotePageJump] — and, on the surfaces that have documents, the
     * Text-Notes save-and-title menu.
     *
     * It began as a jump across one base's own sub-pages ("<base>", "<base>#1", …) plus New page
     * and Go-to-a-date: the ≡ Notes idea, reached from the ‹ N › label beneath the almanac nav so
     * every note surface is findable the same way. What it did NOT carry was the other half of what
     * ≡ Notes is. In Text Notes the note on screen has a title field and its list has ＋ and Delete;
     * on Write the ‹ N › label listed pages and nothing else, so from the one surface actually meant
     * for long-form there was no way to start a named piece or to title the one you were in.
     * Synthesize meanwhile opened the whole document directory from the same control. Two surfaces
     * Michael calls "the same shape — nameable, multi-page documents, where the name defaults to the
     * start date if the user never renames it" answered the same tap two different ways, which is
     * exactly what "build the pattern ONCE and apply it to all four surfaces" was meant to stop.
     *
     * So: one menu, in the order the iPad settled on (`PlannerShell.pageDirectoryActions`) — the
     * page's #tags, this surface's DOCUMENTS with the one you're in marked, this document's PAGES,
     * ＋ New page, ＋ New <noun>…, ✎ Name this <noun>…, 📅 Go to a date…, 🗂 All <label>….
     *
     * Grid and Jot used to fall through it: one implicit pad per day with nothing to name, because
     * [com.toolsboox.plugin.calendar.ot.LedgerDocuments.surfaceOf] returned null for them, and a
     * control that would write nowhere isn't offered. They have stores now, so they get the whole
     * menu — documents, pages, naming, untitling, deleting — off the same `surface != null` test as
     * Write, without a line here knowing which surface it is. Gram Picks still falls through, and
     * still should: see the fallback row at the foot of this method.
     *
     * DELETION IS TWO ROWS, NOT ONE. This menu carried a note for a while explaining why it had no
     * Delete at all, and the note was right about the problem: "A text note IS its record, so
     * deleting the entry deletes the writing. A Write document's ink lives in the day JSON under its
     * key and the index holds only its title — so 'delete' would either drop the title and strand
     * the pages under a key nothing can reach again, or reach into the day file and destroy work.
     * That is a decision about what deletion MEANS here, not a missing button, and it is Michael's."
     *
     * He made it: both, separately, because they are genuinely different acts and one word cannot
     * carry both. ⌫ takes the title off and leaves every page where it is; 🗑 says how many pages
     * there are and that the ink goes with them, and asks. Neither can strand a page — see
     * [com.toolsboox.plugin.calendar.ot.LedgerDocuments.untitle] for why the untitle keeps the index
     * row, and [com.toolsboox.plugin.calendar.ot.LedgerDocumentPages] for how the delete finds every
     * page before it removes the row that reaches them.
     */
    private fun showNoteSubPageJump(base: String) {
        val ctx = context ?: return
        val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        val surface = docs0.surfaceOf(base)
        // Whether this base carries a "<base>#n" run at all. A named Synthesize topic does not (see
        // [isSubPageableBase]), so listing "Page 1 / ＋ New page" on one would offer a page the
        // pager can't reach and the directory correctly says doesn't exist.
        val paged = isSubPageableBase(base)

        val here = notePageSubIndex(notePage)
        val subs = sortedSetOf(here)   // the page you're on always lists
        if (::calendarDay.isInitialized) {
            fun consider(key: String) { if (baseNotePage(key) == base) subs.add(notePageSubIndex(key)) }
            calendarDay.noteStrokes.filterValues { it.isNotEmpty() }.keys.forEach { consider(it) }
            calendarDay.imageElements.forEach { consider(it.page) }
        }
        val ordered = if (paged) subs.toList() else emptyList()
        val newSub = (ordered.maxOrNull() ?: -1) + 1
        fun keyFor(sub: Int) = if (sub == 0) base else "$base#$sub"

        // Rows as (label, action) pairs rather than parallel arrays indexed by arithmetic. The old
        // `which - marks.size` chain was already three cases deep; folding documents and three
        // naming rows into it would have made the index maths the most fragile thing on the screen.
        val rows = mutableListOf<Pair<String, () -> Unit>>()

        // ▤ LEADS here too — the field ruling that reordered the dated menu (verbs first)
        // applies to every ‹ N › menu, or the hand learns two layouts for one idea.
        if (surface != null && docs0.templateOf(base) != null) {
            val tplKey = notePage ?: base
            rows += "▤  Template · ${
                com.toolsboox.plugin.calendar.ot.NotePageTemplates.label(resolvedPageTemplate(tplKey))
            }" to { showNoteTemplatePicker(tplKey) }
        }

        // THE OTHER-DOCUMENTS SHELF RETIRED (Michael, field, 08-27: "it also gives such a long
        // list of other notes that this ux doesn't really work"). The heading already answers
        // "which one am I in"; the NOTEBOOK is the shelf, one door below. The menu holds only
        // what belongs to THIS document.

        fun namedPageRows(): List<Pair<String, () -> Unit>> = ordered.map { s ->
            ("Page ${s + 1}" + if (s == here) "  ·  here" else "") to {
                if (s != here) CalendarNavigator.toDayNote(this, currentDate, keyFor(s))
            }
        }
        if (ordered.size <= 4) rows += namedPageRows()
        else rows += "🗐  Pages · ${ordered.size}…" to { showIconMenu("Pages", namedPageRows()) }
        if (paged) {
            rows += "＋  New page" to { CalendarNavigator.toDayNote(this, currentDate, keyFor(newSub)) }
            // Deleting the LAST page of a named note is the document delete's job (that verb
            // exists below); this one only trims a multi-page run.
            if (ordered.size > 1) rows += "\ud83d\uddd1  Delete this page\u2026" to {
                deletePageContent(notePage ?: base, "Delete this page?")
            }
        }

        // The document you are standing in, resolved once: the naming rows need to know whether it
        // has a title (so "Remove the title" isn't offered on one that has none) and the heading
        // needs the title itself.
        val doc = docs0.documentFor(ctx, base, currentDate, knownNotePageKeys())

        if (surface != null) {
            // The SAME mint door as the dated menu — "New note…", not "New <kind>…": the paper
            // is not a kind (08-27), and two spellings of one door is how menus stop being read.
            rows += "＋  New note…" to {
                com.toolsboox.plugin.feeds.ui.showNewNotePicker(this, currentDate)
            }
            if (docs0.canRename(surface, base)) {
                // "Name this", not "Rename". Most of the time the document has never had a name and
                // is wearing its date, so "rename" would describe undoing something you never did.
                rows += "✎  Name this ${docs0.noun(surface)}…" to {
                    com.toolsboox.plugin.feeds.ui.promptRenameCurrentDocument(
                        this, surface, base, currentDate
                    ) { redrawNoteTemplate() }
                }
            }
            // THE SHOW / DON'T-SHOW TOGGLE, and it lives here rather than in Settings on purpose.
            // Michael asked for the written title "with a show/don't show toggle", and what it
            // governs is one document's page one — so it belongs beside that document's other
            // verbs, on the menu you open from the page it affects, where the answer is visible the
            // instant you tap it. A global preference would have been a switch in another room
            // deciding what this page looks like.
            //
            // Only offered when there IS a face AND a title for it to be the face OF: a checkbox
            // governing nothing is a promise that something would appear if you ticked it, and an
            // untitled document draws no face however this is set (see [CalendarDayPageNotes]) —
            // its ink is being kept for the retitle, not shown. The date passed is the one the
            // document is filed under; [LedgerTitleInk.idFor] says why it only matters for the
            // daily page.
            val inkDate = doc?.date ?: currentDate
            if (doc != null && doc.named &&
                com.toolsboox.plugin.calendar.ot.LedgerTitleInk.has(ctx, surface, base, inkDate)) {
                val shownNow = com.toolsboox.plugin.calendar.ot.LedgerTitleInk
                    .isShown(ctx, surface, base, inkDate)
                rows += ((if (shownNow) "☑" else "☐") + "  Show the written title on page one") to {
                    com.toolsboox.plugin.calendar.ot.LedgerTitleInk
                        .setShown(ctx, surface, base, inkDate, !shownNow)
                    redrawNoteTemplate()
                }
            }
            // ⌫ only when there is a title to take off — on an untitled document it would be a row
            // offering to undo something that never happened, which is exactly the wording problem
            // "Name this" avoids one line up.
            if (doc != null && doc.named && docs0.canUntitle(surface, base)) {
                rows += "⌫  Remove this ${docs0.noun(surface)}'s title" to {
                    com.toolsboox.plugin.feeds.ui.untitleDocument(
                        this, surface, base, doc.date, doc.title
                    ) { redrawNoteTemplate() }
                }
            }
            if (docs0.canDelete(surface)) {
                // The count and the warning live in the confirmation, not in this label — a menu row
                // that already said "and its 4 pages" would be reading a day file to draw a menu.
                rows += "🗑  Delete this ${docs0.noun(surface)}…" to {
                    com.toolsboox.plugin.feeds.ui.confirmDeleteDocument(
                        this, surface, base, doc?.title ?: base, doc?.date ?: currentDate
                    ) {
                        // The page under you has just ceased to exist. The surface's own daily page
                        // for that day is the nearest place that still does.
                        CalendarNavigator.toDayNote(this, currentDate, docs0.defaultKey(surface))
                    }
                }
            }
        }

        rows += "📅  Go to a date…" to { showNoteDatePicker() }   // navigate this surface BY DATE
        if (surface != null) {
            rows += "🗂  All ${docs0.label(surface)}…" to {
                com.toolsboox.plugin.feeds.ui.showDocumentDirectory(this, surface, currentDate, notePage)
            }
            // Up a level: this surface's list, then EVERYTHING — the Notebook, from every surface's
            // menu, as iOS's surface foot already offers (review of 6b46141e, Michael 2026-10-11:
            // "there's no way to see a notes directory right now"). Pickings had its own copy
            // further down; it is this row now, so no menu lists it twice.
            rows += "🗂  All notes…" to {
                com.toolsboox.plugin.feeds.ui.showNotebook(this, currentDate)
            }
        } else {
            // GRAM PICKS, and now only Gram Picks.
            //
            // This branch was built for grid, jot and Gram Picks together: three sub-pageable
            // surfaces that arrived here rather than at the numbered-notes menu, and none of which
            // mapped to a document store, so the "🗂 All <label>…" row above never drew and they
            // were the surfaces you could page but never leave. Grid and Jot have real stores now —
            // "I think grid and jot should be savable" — so they take the ordinary `surface != null`
            // path with everything else on it, and this is the fallback's remaining tenant.
            //
            // Gram Picks is NOT being promoted and keeps it. It is an inbox you sort out of rather
            // than a shelf of things you keep, so there is no per-kind list for it to open; the root
            // — every kind at once — is the honest destination for a page that is nobody's document.
            // "All notes…" — the directory's one name in both apps (Michael 2026-10-11).
            rows += "🗂  All notes…" to {
                com.toolsboox.plugin.feeds.ui.showNotebook(this, currentDate)
            }
        }

        // A named Write document is titled by its NAME here, not by its raw key — "write-1753…" as
        // a dialog title tells you nothing, and the document's title is the whole point of naming
        // it. An UNnamed document keeps the surface's own name ("Write"), because the date default
        // is already the day the whole screen is showing and repeating it says nothing.
        // THE EXPORT DOORS, for named notes too — a named note was second-class at the export door
        // (single page only, through the per-page sheet); it carries the same page-selection verbs
        // as the dated note now, its NAME riding as the export's title (DESIGN-notes-room §2d).
        if (paged && surface != null && ordered.isNotEmpty()) {
            val notePages = ordered.map { keyFor(it) }
            val exportTitle = if (doc != null && doc.named) doc.title else null
            rows += "✉  Send page(s) as an email…" to {
                showPagePicker("Email which pages?", notePages) { emailPages(it, exportTitle) }
            }
            rows += "⇪  Export pages…" to {
                showPagePicker("Export which pages?", notePages) { showExportVerbSheet(it, exportTitle) }
            }
        }

        // The boards directory rides INSIDE the one menu now (boards-are-paper, 08-27) — the
        // chip that used to open it as a different panel opens this menu instead. The
        // pickings-specific directory is DEPRECATED (Michael, 08-28: "that pickings panel when you
        // select the little number on pickings is deprecated for sure"): the Notebook is the one
        // list, and its "🗂 All notes…" row now hangs beside "All <label>…" on every surface.

        // An UNnamed document takes the SURFACE's own name rather than its key capitalised. The key
        // was a good enough stand-in while the two agreed ("write" → "Write"); Jot's key is "sketch",
        // so capitalising it would head the menu with a word Michael has never called that surface.
        val heading = when {
            doc != null && doc.named -> doc.title
            surface != null -> docs0.label(surface)
            else -> base.replaceFirstChar { it.uppercase() }
        }
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(heading)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)   // pen-guarded: see showNoteDatePicker
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    /** The page keys this day already holds, so a document's page count comes off the day in hand
     *  rather than a second streamed read of the file. Empty before the day has loaded, which
     *  [com.toolsboox.plugin.calendar.ot.LedgerDocuments.subPageCount] correctly reads as "go and
     *  ask the file". */
    private fun knownNotePageKeys(): Set<String> =
        if (!::calendarDay.isInitialized) emptySet()
        else calendarDay.noteStrokes.keys + calendarDay.imageElements.map { it.page }

    // ── ▤ TEMPLATE ────────────────────────────────────────────────────────────────────────────
    //
    // THE TEMPLATE IS A PROPERTY OF THE PAGE, NOT THE IDENTITY OF ITS STORE.
    //
    // This block used to be a written refusal: a template WAS the store (Lines = WritePageStore,
    // Jots = JotPageStore, …), so switching one meant moving ink between key families, and the
    // sheet's whole job was to say why that couldn't be done to an inked page. Michael's ruling
    // (DESIGN-notes-ia-2026-08-22) dissolves it by architecture: "Dot, Graph, Line are all just
    // templates … whatever templates a person wants to use per page." The page's KEY — and so its
    // store, its sync route and every stroke — never moves; only the background it is drawn on is
    // recorded, per page, in [com.toolsboox.plugin.calendar.ot.NotePageTemplates]. Switching a
    // template on an inked page is therefore safe by construction: the strokes stay put, the
    // sheet under them changes, and switching back is one more tap.

    /** The sheet this page currently wears — its own recorded choice, else its key family's, else
     *  the numeric pages' global seed. One resolution ([NotePageTemplates.resolve]) for the ▤ row's
     *  label, the picker's current mark and the renderer, so they can never disagree. */
    private fun resolvedPageTemplate(pageKey: String): String {
        val tpl = com.toolsboox.plugin.calendar.ot.NotePageTemplates
        val explicit = context?.let { tpl.templateFor(it, currentDate, pageKey) }
        return tpl.resolve(explicit, pageKey, sharedPreferences.getInt("calendarNoteTemplate", 0))
    }

    /**
     * The ▤ Template picker: Lines · Grid · Dots · Pickings, the one you're on marked, tap = this
     * page wears that sheet from now on. Per PAGE — "write#2" can be dots while "write" stays
     * ruled — and allowed on inked pages, because a template change moves no ink.
     *
     * Self Executive is demoted by the same ruling: it renders wherever it already exists, but it
     * is not offered here, so no new page can be minted onto it.
     */
    private fun showNoteTemplatePicker(pageKey: String) {
        val ctx = context ?: return
        val tpl = com.toolsboox.plugin.calendar.ot.NotePageTemplates
        val cust = com.toolsboox.plugin.calendar.ot.CustomTemplates
        val current = resolvedPageTemplate(pageKey)
        val rows = tpl.IDS.map { id ->
            (tpl.glyph(id) + "  " + tpl.label(id) + if (id == current) "  ·  here" else "") to {
                if (id != current) {
                    tpl.set(ctx, currentDate, pageKey, id)
                    redrawNoteTemplate()
                }
            }
        } +
        // THE SHEETS A PERSON BROUGHT — same list, same tap; hold the row (well, tap 🗑 in the
        // submenu) to retire one. Import lands at the end: the paper you keep comes before the
        // door that adds more.
        cust.list(ctx).map { e ->
            ("▤  " + e.name + if (e.id == current) "  ·  here" else "") to {
                if (e.id != current) {
                    tpl.set(ctx, currentDate, pageKey, e.id)
                    redrawNoteTemplate()
                } else {
                    // Standing on it: offer the one destructive act, guarded by its own menu.
                    showIconMenu(e.name, listOf<Pair<String, () -> Unit>>(
                        "🗑  Remove this template" to {
                            cust.delete(ctx, e.id)
                            showMessage("Removed · ${e.name}")
                        }
                    ))
                }
                Unit
            }
        } +
        listOf<Pair<String, () -> Unit>>("＋  Import a template…" to {
            pendingTemplatePageKey = pageKey
            runCatching { importTemplateLauncher.launch("image/*") }
                .onFailure { showMessage("No picker available on this device.") }
            Unit
        })
        showIconMenu("Template", rows)
    }

    /** The page waiting for an imported sheet — set when the picker's ＋ row launches the chooser. */
    private var pendingTemplatePageKey: String? = null

    /** The import door: any image becomes a page-sized PNG in the template drawer, named after
     *  its file, offered in ▤ forever after — and worn by the page that asked, immediately. */
    private val importTemplateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val ctx = context ?: return@registerForActivityResult
        val pageKey = pendingTemplatePageKey ?: return@registerForActivityResult
        pendingTemplatePageKey = null
        if (uri == null) return@registerForActivityResult
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "Template"
        lifecycleScope.launch {
            val entry = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.CustomTemplates.import(ctx, uri, name)
            }
            if (!isAdded) return@launch
            if (entry == null) { showMessage("Couldn't read that image."); return@launch }
            com.toolsboox.plugin.calendar.ot.NotePageTemplates.set(ctx, currentDate, pageKey, entry.id)
            redrawNoteTemplate()
            showMessage("▤ ${entry.name} — imported, and this page wears it")
        }
    }

    /**
     * Repaint the note page's TEMPLATE in place.
     *
     * The header carries the document's name now, so naming a piece has to show on the page you
     * named it from rather than on the next one you happen to open. The same move
     * [redrawIntakePage] makes, and safe for the same reason: the template is its own bitmap and
     * the ink rides a separate layer, so redrawing one never disturbs the other.
     */
    private fun redrawNoteTemplate() {
        val page = notePage ?: return
        if (page == CalendarDayPageIntake.INTAKE_PAGE) { redrawIntakePage(); return }
        if (!::calendarDay.isInitialized) return
        val noteTemplate = sharedPreferences.getInt("calendarNoteTemplate", 0)
        drawTemplate { c -> CalendarDayPageNotes.drawPage(requireContext(), c, calendarDay, noteTemplate, page) }
        binding.templateImageView.invalidate()
        // The pill carries the note's name now — a rename must reach it in the same breath.
        refreshNotePagerLabel()
    }

    /**
     * THE ONE PILL'S LABEL (field, 08-29: "there are pills and carrots that replicate the name
     * and carrots of the same note"). The directory chip used to carry the note's NAME while the
     * pager pill carried the POSITION — two chrome elements for one note. The chip is retired
     * ([bindDirectoryChip]); the pill says both: a NAMED document reads "Name · n", an unnamed
     * one keeps the bare position (its name is the date, and the date strip already says so).
     * Named synthesis topics and boards read their name; the unnamed default board keeps ❝
     * (naming it "Pickings" would put a template word back in the chrome — the -24 ruling).
     */
    private fun refreshNotePagerLabel() {
        val page = notePage ?: return
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        val base = baseNotePage(page) ?: return
        fun short(t: String) = if (t.length <= 14) t else t.take(13).trimEnd() + "…"
        val doc = docs.documentFor(requireContext(), base, currentDate, knownNotePageKeys())
        when {
            isSubPageableBase(base) -> {
                val n = notePageSubIndex(page) + 1
                binding.notePagerLabel.text =
                    if (doc != null && doc.named) "${short(doc.title)} · $n" else n.toString()
            }
            com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(base) -> {
                binding.notePagerLabel.text =
                    if (doc != null && doc.named) short(doc.title) else docs.glyph(docs.SYNTHESIZE)
            }
            com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(page) -> {
                binding.notePagerLabel.text =
                    if (doc != null && doc.named) short(doc.title) else "❝"
            }
        }
    }

    // ── ⇪ EXPORT — the third door, real ─────────────────────────────────────────────────────────
    //
    // DESIGN-notes-ia-2026-08-22, Michael's answer #2: "Export = three verbs on a page selection —
    // Add to a new note (export and transplant are the same door), Export as PNG (multiple pages
    // may become multiple PNGs OR one single PNG stitched in page order), Export as PDF (the
    // selected pages, in order)." The dispatch verbs (email / post / Bluesky / task, carrying OCR
    // text + page image) are the NEXT build; they stand in the sheet so the door's final shape is
    // visible, and each says so when tapped.
    //
    // Selection first, verb second — two plain dialogs, no modes, every one pen-guarded through
    // [showModal] (the Onyx pen must pause under any dialog over the ink surface, or stylus taps
    // fall through and the popup "freezes").

    /** The stored content of any page of the loaded day, for the page-keyed render — one snapshot
     *  shape shared with the PDF path so PNG and PDF can never picture the same page differently. */
    override fun providePageContent(pageKey: String): com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.PageContent? {
        if (!::calendarDay.isInitialized) return null
        return com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.PageContent(
            calendarStrokes = emptyMap(),
            noteStrokes = mapOf(pageKey to (calendarDay.noteStrokes[pageKey] ?: emptyList())),
            textElements = calendarDay.textElements.filter { it.pageKey == pageKey },
            imageElements = calendarDay.imageElements.filter { it.page == pageKey }
        )
    }

    /** The sheet under a page's ink, at the page's own resolved template — the same drawPage the
     *  live surface renders with, so an exported page looks exactly like the page. */
    override fun drawPageTemplate(pageKey: String, canvas: android.graphics.Canvas) {
        if (!::calendarDay.isInitialized) return
        val ctx = context ?: return
        CalendarDayPageNotes.drawPage(
            ctx, canvas, calendarDay, sharedPreferences.getInt("calendarNoteTemplate", 0), pageKey
        )
    }

    /**
     * Step one of the export door: WHICH PAGES. The dated note's pages in [DatedNote.order], each
     * wearing its resolved template glyph (the jump menu's vocabulary), multi-select, with the page
     * you are standing on preselected — "this page" is the commonest export and costs zero taps.
     */
    /**
     * Choose which pages become the new note, then mint it — the same multi-select the export door
     * uses, ending in [exportAddToNewNote] rather than in a verb sheet.
     *
     * Sharing the picker rather than growing a second one: the two doors differ in what happens
     * AFTER the choice, and two page-pickers would be two places for the ‹ N › order to drift from.
     */
    private fun showSaveAsNotePicker() = showPagePicker("Save as a note") { exportAddToNewNote(it) }

    /**
     * Choose which pages become the letter, gather their words and their picture, and open the
     * composer prefilled — see the door's note for why this goes through the mail accounts rather
     * than the web bridge the export sheet's ✉ row uses.
     *
     * The gather is [dispatchPages], the same read every dispatch verb makes (typed drafts, text
     * elements, and handwriting through the vision model with on-device OCR as the no-signal
     * fallback) — so the words in the letter are exactly the words the other verbs would have sent.
     */
    private fun showEmailPagesPicker() = showPagePicker("Email which pages?") { emailPages(it) }

    /** The ONE outbound email rail for note pages: the user's own composer, prefilled — words in
     *  the body, picture attached, the Send button his to press. The export sheet's ✉ verb rides
     *  this too now; the bridge mail route retired from notes (DESIGN-notes-room-2026-08-27 §2d). */
    private fun emailPages(selection: List<String>, title: String? = null) {
        dispatchPages(selection) { pack ->
            // The letter is the page's words, then the page's PICKS — what each one says and the
            // door it came through. Without this the recipient got a picture of cards whose sources
            // were visible only as pixels; a Pickings page arrived as a wall of quotations with no
            // way to reach any of them. Whoever reads it can now follow every one.
            val cited = pack.elements.mapNotNull { p ->
                val head = p.label.ifBlank { p.words.take(80) }.trim()
                when {
                    head.isBlank() && p.link.isBlank() -> null
                    // A pick with a door reads as a citation; one without is still a quotation
                    // worth carrying, so it rides on its words alone rather than being dropped.
                    p.link.isBlank() -> "• $head"
                    head.isBlank() -> "• ${p.link}"
                    else -> "• $head — ${p.link}"
                }
            }.distinct()
            val words = buildString {
                append(pack.text.trim())
                if (cited.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("On these pages:\n")
                    append(cited.joinToString("\n"))
                }
            }.trim()
            // The picture rides as a FILE PATH, not base64 in the Bundle: a four-page stitch can
            // pass the binder's ~1MB transaction ceiling and a TransactionTooLargeException kills
            // the navigation with the words in it. The composer reads and deletes the file.
            val args = android.os.Bundle().apply {
                putString(com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_SUBJECT,
                    title ?: "Note — $currentDate")
                putString(com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_BODY, words)
                val f = java.io.File(requireContext().cacheDir, "mail-attach-${System.currentTimeMillis()}.png")
                runCatching { f.writeBytes(pack.png) }
                    .onSuccess {
                        putString(com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_ATTACH_PATH, f.absolutePath)
                    }
                putString(com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_ATTACH_NAME,
                    "note-$currentDate.png")
            }
            runCatching { findNavController().navigate(R.id.action_to_mail_compose, args) }
                .onFailure { showMessage("Couldn't open the composer.", binding.root) }
        }
    }

    private fun showExportPagePicker() = showPagePicker("Export which pages?") { showExportVerbSheet(it) }

    /**
     * The dated note's page multi-select — ONE picker, two doors ([showSaveAsNotePicker] and
     * [showExportPagePicker]). The current page arrives ticked, and the answer is in PAGE order,
     * not tap order ("the selected pages, in order" is the ruling's phrase).
     */
    /**
     * \ud83d\uddd1 DELETE A PAGE of a multi-page note (Michael, 08-28: "We also need to be able
     * to delete pages from multi page notes"). The content dies through the day JSON's OWN
     * tombstone lists — deletedStrokeIds / deletedElementIds (+ deletedItemIds in lockstep via
     * [CalendarDay.tombstoneLedgerItem]'s rule) — so the delete SYNCS and never resurrects
     * (the erased-strokes-stay-erased law). NO REKEYING: the walk lists existing keys, so a
     * deleted page simply leaves the walk and the numbering heals at the presentation layer.
     * Guarded confirm says what dies. On the day's standing page "0" the verb is CLEAR —
     * the page itself remains, as the day's pages always do.
     */
    private fun deletePageContent(pageKey: String, wording: String) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val strokes = calendarDay.noteStrokes[pageKey].orEmpty()
        val elements = calendarDay.imageElements.filter { it.page == pageKey }
        val texts = calendarDay.textElements.filter { it.pageKey == pageKey }
        val what = buildString {
            append(strokes.size).append(" strokes")
            if (elements.isNotEmpty()) append(" \u00b7 ").append(elements.size).append(" picks")
            if (texts.isNotEmpty()) append(" \u00b7 ").append(texts.size).append(" text boxes")
        }
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(wording)
            .setMessage("$what die, on every device. The ink cannot come back.")
            .setPositiveButton("Delete") { _, _ ->
                for (st in strokes) {
                    val id = st.strokeId.toString()
                    if (id !in calendarDay.deletedStrokeIds) calendarDay.deletedStrokeIds.add(id)
                }
                calendarDay.noteStrokes.remove(pageKey)
                for (el in elements) {
                    val id = el.elementId.toString()
                    if (id !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(id)
                }
                calendarDay.imageElements.removeAll(elements)
                for (t in texts) {
                    val id = t.elementId.toString()
                    if (id !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(id)
                }
                calendarDay.textElements.removeAll(texts)
                calendarPattern.updateDay(calendarDay)
                presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                // Stand somewhere real: the walk no longer lists the dead page.
                val base = baseNotePage(pageKey)
                if (notePage == pageKey) {
                    val left = datedNotePages().filter { it != pageKey }
                    CalendarNavigator.toDayNote(this, currentDate,
                        left.firstOrNull() ?: base ?: "0")
                } else redrawNoteTemplate()
            }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    private fun showPagePicker(
        title: String,
        pages: List<String> = datedNotePages(),
        onChosen: (List<String>) -> Unit,
    ) {
        val ctx = context ?: return
        if (pages.isEmpty()) return
        val labels = pages.mapIndexed { i, key ->
            com.toolsboox.plugin.calendar.ot.NotePageTemplates.glyph(resolvedPageTemplate(key)) +
                "  Page ${i + 1}" + if (key == notePage) "  ·  here" else ""
        }.toTypedArray()
        val checked = BooleanArray(pages.size) { pages[it] == notePage }
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Next") { _, _ ->
                val selection = pages.filterIndexed { i, _ -> checked[i] }
                if (selection.isEmpty()) showMessage("Nothing selected", binding.root)
                else onChosen(selection)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)   // pen-guarded: see showNoteDatePicker
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    /** Step two: THE VERB. The three file verbs, then THE FOUR PUBLISH DOORS Michael named on
     *  08-27 — ✉ Email (his own composer) · 📰 WordPress (draft or publish) · 🦋 Bluesky ·
     *  🌱 Community (Fluent) — then ☑ Task. Every dispatch carries BOTH the selection's words
     *  (typed drafts + text elements + handwriting OCR) and its picture (the 08-22 ruling). */
    private fun showExportVerbSheet(selection: List<String>, title: String? = null) {
        val noun = if (selection.size == 1) "1 page" else "${selection.size} pages"
        // THIS page alone: the single-page sheet's doors ride here too (reply lead, Ask, plain
        // text, Markdown, linked PDF) — one sheet, everything (09-13).
        val thisPageOnly = selection.size == 1 && selection.first() == (notePage ?: "default")
        val single = if (thisPageOnly) replyLead() +
            com.toolsboox.plugin.calendar.ot.LedgerSendExport.fileRows(this, sendExportPayload()) else emptyList()
        showIconMenu("Send / export · $noun", single + listOf(
            "➕  Add to a new note" to { exportAddToNewNote(selection) },
            "🖼  Export as PNG" to { exportPagesAsPng(selection) },
            "📄  Export as PDF" to { exportPagesAsPdf(selection) },
            "✉  Email" to { emailPages(selection, title) },
            "📰  WordPress" to { dispatchPages(selection) { pack -> dispatchWordPress(pack, title) } },
            // This page alone → the provenance share (picture, strokes, OCR, picks, links); a
            // multi-page pick → the first page as the picture with the pack's words.
            "🦋  Bluesky" to { if (thisPageOnly) sharePageToBluesky() else dispatchPages(selection) { pack -> dispatchBluesky(selection.first(), pack) } },
            "🌱  Community" to { dispatchPages(selection) { pack -> dispatchCommunity(pack, title) } },
            "☑  Task" to { dispatchPages(selection) { pack -> dispatchTask(pack) } }
        ))
    }

    /**
     * ➕ Add to a new note: mint a named note on the FIRST selected page's resolved template (the
     * naming pad every "new note" path shares), COPY the selection's content under the new note's
     * keys ([com.toolsboox.plugin.calendar.ot.NotePageCopy] — fresh stroke/element ids, originals
     * untouched), carry each page's worn sheet across, save, and go stand on it.
     *
     * A multi-page selection whose first page wears Pickings mints on Lines instead: a Pickings
     * board has no "#n" sub-page run ([isSubPageableBase]), so pages 2+ of a pickings-based note
     * would exist and be unreachable — the stranding the delete design forbids. The pages still
     * LOOK right, because each one's worn template is copied onto its new key below.
     */
    private fun exportAddToNewNote(selection: List<String>) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        val tpl = com.toolsboox.plugin.calendar.ot.NotePageTemplates
        var surface = when (resolvedPageTemplate(selection.first())) {
            tpl.GRID -> docs.GRID
            tpl.DOTS -> docs.JOT
            tpl.PICKINGS -> docs.PICKINGS
            else -> docs.WRITE
        }
        if (surface == docs.PICKINGS && selection.size > 1) surface = docs.WRITE
        promptTitle(
            this, surface,
            dialogTitle = "New ${docs.noun(surface)}",
            fieldHint = "What's this ${docs.noun(surface)} about?",
            seed = null,
            saveLabel = "Create",
        ) { name, face ->
            val made = docs.startNote(ctx, surface, name, currentDate)
            if (made == null) {
                showMessage("Couldn't start the note", binding.root)
                return@promptTitle
            }
            // The face is filed AFTER the note exists — a minted key is the store's to choose
            // (the promptNewDocument rule, kept).
            face?.let {
                com.toolsboox.plugin.calendar.ot.LedgerTitleInk.put(ctx, surface, made.key, made.date, it)
            }
            val seed = sharedPreferences.getInt("calendarNoteTemplate", 0)
            val destKeys = com.toolsboox.plugin.calendar.ot.NotePageCopy
                .copyInto(calendarDay, selection, made.key)
            // Each copied page keeps the sheet it was wearing — recorded only where the new key's
            // own family wouldn't already resolve to it, so the sidecar carries choices, not noise.
            selection.forEachIndexed { i, src ->
                val worn = resolvedPageTemplate(src)
                if (worn != tpl.resolve(null, destKeys[i], seed)) tpl.set(ctx, made.date, destKeys[i], worn)
            }
            calendarPattern.updateDay(calendarDay)
            presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            showMessage("Copied ${selection.size} page(s) to “${made.name}”", binding.root)
            CalendarNavigator.toDayNote(this, made.date, made.key)
        }
    }

    /** 🖼 One page shares straight away; more asks which shape — Michael's OR, offered as a
     *  question rather than answered by a preference in another room. */
    private fun exportPagesAsPng(selection: List<String>) {
        if (selection.size == 1) {
            runCatching { shareGramBitmap(renderPageBitmap(selection.first())) }
                .onFailure { showMessage("Export failed", binding.root) }
            return
        }
        showIconMenu("PNG · ${selection.size} pages", listOf(
            "🖼  Separate PNGs" to { exportSeparatePngs(selection) },
            "📜  One tall PNG" to { exportStitchedPng(selection) }
        ))
    }

    /** How many pages the tall stitch will hold in one bitmap. Each page is 1404×1872 ARGB —
     *  ~10.5 MB — so four stitched is ~42 MB plus the page being drawn; past that the single
     *  allocation is an OOM waiting for a long selection, and separate files say the same thing
     *  without the risk. */
    private val STITCH_PAGE_CAP = 4

    /** Separate PNGs: one file per page, one share sheet — the shareCardBitmaps pattern
     *  (ACTION_SEND_MULTIPLE + FileProvider uris), rendered page-at-a-time so only one page
     *  bitmap is ever held. */
    private fun exportSeparatePngs(selection: List<String>) {
        runCatching {
            val ctx = requireContext()
            val dir = java.io.File(ctx.cacheDir, "cards").apply { mkdirs() }
            val uris = ArrayList<android.net.Uri>()
            selection.forEachIndexed { i, key ->
                val bmp = renderPageBitmap(key)
                val file = java.io.File(dir, "note-$currentDate-p${i + 1}.png")
                file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                uris.add(androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file))
            }
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                    .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share pages"))
        }.onFailure { showMessage("Export failed", binding.root) }
    }

    /** The pages stitched vertically in page order, one bitmap. Callers cap the selection —
     *  see [STITCH_PAGE_CAP] for why a long stitch is an OOM, not a feature. */
    private fun stitchPages(selection: List<String>): android.graphics.Bitmap {
        val tall = android.graphics.Bitmap.createBitmap(
            CANVAS_WIDTH, CANVAS_HEIGHT * selection.size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(tall)
        selection.forEachIndexed { i, key ->
            val page = renderPageBitmap(key)
            canvas.drawBitmap(page, 0f, (i * CANVAS_HEIGHT).toFloat(), null)
            page.recycle()   // one page in flight beside the stitch, never the whole selection
        }
        return tall
    }

    /** One tall PNG: the pages stitched vertically in page order. Capped at [STITCH_PAGE_CAP];
     *  a longer selection falls back to separate files, and says so rather than silently thinning. */
    private fun exportStitchedPng(selection: List<String>) {
        if (selection.size > STITCH_PAGE_CAP) {
            showMessage("Too tall to stitch — sharing ${selection.size} separate PNGs", binding.root)
            exportSeparatePngs(selection)
            return
        }
        runCatching {
            shareGramBitmap(stitchPages(selection))
        }.onFailure { showMessage("Export failed", binding.root) }
    }

    /**
     * 📄 The selected pages, in order, one PDF page each at 1404×1872 —
     * [CalendarPdfRenderer.renderMonthToPdf]'s machinery, which is why the render runs on
     * Dispatchers.Default (that renderer's stated contract: CPU-bound, never Main). The content is
     * snapshotted on Main first, so the render can't race a pen-up's list swap.
     */
    private fun exportPagesAsPdf(selection: List<String>) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val pages = selection.mapIndexed { i, key -> "Page ${i + 1}" to providePageContent(key)!! }
        showMessage("Rendering PDF…", binding.root)
        lifecycleScope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
                    val f = java.io.File(dir, "note-$currentDate.pdf")
                    com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer
                        .renderMonthToPdf(pages, f, context = ctx)
                    f
                }.getOrNull()
            }
            if (file == null) { showMessage("PDF export failed", binding.root); return@launch }
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file)
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Send PDF"))
            }.onFailure { showMessage("Share failed", binding.root) }
        }
    }

    // ── Dispatch verbs — the export door's second half ──────────────────────────────────────────
    //
    // DESIGN-notes-ia-2026-08-22, the same answer's tail: "send the selection as an email, a
    // post, a Bluesky share, or a task — each carrying BOTH the OCR'd text of the page(s) AND
    // the page image(s)." No new rails: LedgerEssay carries email and WP drafts, postGram the
    // space gram, BlueskyReply.shareGram the Bluesky queue, and the task rides the same
    // confirm + LedgerTaskSync push the lasso mints through. This block reads the pages once
    // and hands the pack to the rail.

    /** What a dispatch carries — the selection's words and its picture. [png] pictures at most
     *  [STITCH_PAGE_CAP] pages (one plain, more stitched tall); [text] carries EVERY selected
     *  page regardless, so a long selection loses no words, only wall space. [strokes] is the
     *  selection's ink, kept for the task verb (a handwritten item points at its strokes). */
    private class DispatchPack(
        val text: String, val png: ByteArray, val strokes: List<com.toolsboox.da.Stroke>,
        /** The PICKS standing on the selected pages — their words and their doors, in page order.
         *  Its own field rather than folded into [text] because the verbs want it differently: a
         *  letter reads it as a citation list under the words, and the task verb must not swallow
         *  a page of URLs into the thing you have to do. Empty when the pages hold no picks. */
        val elements: List<PagePick> = emptyList()
    )

    /** One placed pick, as a dispatch sees it: what it says, and where it came from. */
    private class PagePick(val label: String, val words: String, val link: String)

    /**
     * Read the selection once, then hand the pack to [verb]. Words per page: a typed Write draft
     * rides as its markdown, text elements as they are, and handwriting through the vision model
     * when the Ask-my-Ledger key is set (far better on real handwriting) with the on-device ink
     * OCR as the no-signal fallback — the createLedgerItem ladder, applied per page. Content is
     * snapshotted on Main first so the OCR pass can't race a pen-up's swap (the PDF exporter's
     * rule); the vision pass is a network call per page, so the toast says what's happening.
     */
    private fun dispatchPages(selection: List<String>, verb: (DispatchPack) -> Unit) {
        if (!::calendarDay.isInitialized || selection.isEmpty()) return
        val ctx = requireContext()
        val pictured = selection.take(STITCH_PAGE_CAP)
        // The bitmap is HELD rather than encoded here, because the provenance band under it can
        // only be built once the page's words exist (the stamp merges the text's URLs with the
        // picks' exact ones). Encoding now and re-decoding later would cost a second ~42 MB
        // allocation for a four-page stitch; holding it across the OCR await costs the same
        // memory we already allocated, for a few seconds longer.
        val pageBmp = runCatching {
            if (pictured.size == 1) renderPageBitmap(pictured.first()) else stitchPages(pictured)
        }.getOrNull() ?: run { showMessage("Couldn't picture the pages", binding.root); return }
        // THE PICKS ON THESE PAGES (Michael, 08-31: "the links/provenance of the grams that are on
        // the page of that email … I'd like them included"). `dispatchPages` read drafts, text
        // boxes and handwriting and never once looked at `imageElements` — so a page made ENTIRELY
        // of picks, which is what a Pickings page is, dispatched as a picture with nothing under
        // it. Element links come first and are exact (minted at pick time), which is the same
        // reasoning `LedgerProvenance.mergeSourceLinks` states for the single-page stamp.
        val perPagePicks = selection.flatMap { key ->
            calendarDay.imageElements.filter { it.page == key && !it.decorative }.map { el ->
                PagePick(
                    label = el.sourceLabel.trim(),
                    words = el.cardText.trim(),
                    link = el.sourceLink.trim())
            }
        }.filter { it.label.isNotBlank() || it.words.isNotBlank() || it.link.isNotBlank() }
        // Main-thread snapshot: each page's draft, typed text and a copy of its stroke list.
        val perPage = selection.map { key ->
            Triple(
                com.toolsboox.plugin.calendar.ot.WriteDraftStore.draft(ctx, currentDate, key)
                    ?.markdown?.trim().orEmpty(),
                calendarDay.textElements.filter { it.pageKey == key && it.text.isNotBlank() }
                    .joinToString("\n") { it.text.trim() },
                calendarDay.noteStrokes[key].orEmpty().toList()
            )
        }
        if (selection.size > pictured.size)
            showMessage("First ${pictured.size} pages pictured — the words carry all ${selection.size}", binding.root)
        else
            showMessage("Reading the ink…", binding.root)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val creds = aiCreds()
                // map, not joinToString-with-transform: the on-device OCR suspends, and only an
                // inline lambda may hold a suspension point.
                perPage.map { (draft, typed, strokes) ->
                    val words = mutableListOf<String>()
                    if (draft.isNotBlank()) words.add(draft)
                    if (typed.isNotBlank()) words.add(typed)
                    if (strokes.isNotEmpty()) {
                        val viaVision = if (creds != null) {
                            val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                            val pad = 28f   // breathing room so edge letters aren't clipped
                            val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                            val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                            com.toolsboox.plugin.calendar.nw.VisionOcr
                                .recognize(bmp, creds.first, creds.second, creds.third)?.trim()
                        } else null
                        val ink = viaVision
                            ?: com.toolsboox.ot.OnyxHwr.recognize(requireContext().applicationContext, strokes)
                            ?: com.toolsboox.plugin.calendar.ot.PanelOcr.recognize(strokes).trim()
                        if (ink.isNotBlank()) words.add(ink)
                    }
                    words.joinToString("\n\n")
                }.filter { it.isNotBlank() }.joinToString("\n\n").trim()
            }
            if (!isAdded) return@launch
            // THE SAME BAND THE SINGLE-PAGE ROUTE DRAWS (`LedgerSendExport.pngOf`). Michael, 08-31:
            // "the png of the note and the links/provenance of the grams … I'd like them included."
            // The picture that leaves here now carries its own caption — surface, date, title and
            // the merged link list — so a stitched note is as findable a year later as a one-page
            // share already was. A failed stamp costs the caption, never the export: an uncaptioned
            // picture beats a toast saying the send failed because the OCR timed out.
            val png = withContext(Dispatchers.IO) {
                val stamped = runCatching {
                    val stamp = com.toolsboox.plugin.calendar.ot.LedgerProvenance.of(
                        requireContext(),
                        surface = "Note",
                        date = currentDate,
                        pageKey = selection.firstOrNull(),
                        title = "",
                        text = text,
                        elementLinks = perPagePicks.map { it.link }.filter { it.isNotBlank() })
                    com.toolsboox.plugin.calendar.ot.LedgerProvenance.stampPng(pageBmp, stamp)
                }.getOrNull()
                val out = stamped ?: pageBmp
                val baos = java.io.ByteArrayOutputStream()
                out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                if (stamped != null) stamped.recycle()
                pageBmp.recycle()
                baos.toByteArray()
            }
            verb(DispatchPack(text, png, perPage.flatMap { it.third }, perPagePicks))
        }
    }

    /** 📰 WordPress — draft or PUBLISH (the door Michael named on 08-27). Publish carries the
     *  same syndication contract as the single-page sheet: the `essay` tag (by slug — the term id
     *  is what the REST API takes and the slug is what `mjh_synd_sources()` matches) puts the post
     *  on the POSSE cron's Bluesky queue; a DRAFT is always saved untagged (the tag on a draft is
     *  a trap — LedgerSendExport's rule, kept). BOTH bridge sites can take the post — the boards
     *  bridge and the community bridge each carry WordPress credentials, and the old Post door
     *  offered drafts to either; losing the second site would be a regression, not a cleanup. */
    private fun dispatchWordPress(pack: DispatchPack, title: String? = null) {
        val ctx = requireContext()
        val boards = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx)
        val community = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx)
        val sites = mutableListOf<Pair<String, com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config>>()
        if (boards.ready) sites += boards.site.removePrefix("https://") to boards
        if (community.ready && community.site != boards.site) {
            sites += community.site.removePrefix("https://") to
                com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(
                    community.site, community.user, community.pass, 0)
        }
        if (sites.isEmpty()) {
            showMessage("Add your site first (Settings → Your sites)", binding.root); return
        }
        val titleIn = android.widget.EditText(ctx).apply {
            setText(title ?: "Note — $currentDate"); setSingleLine(); setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(titleIn)
        }
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("WordPress").setView(box)
            .setPositiveButton("Next") { _, _ ->
                val chosen = titleIn.text.toString().trim().ifBlank { "Note — $currentDate" }
                if (sites.size == 1) pickWordPressLanding(chosen, pack, sites.first().second)
                else showModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                    .setTitle("Which site?")
                    .setItems(sites.map { it.first }.toTypedArray()) { _, which ->
                        pickWordPressLanding(chosen, pack, sites[which].second)
                    }
                    .setNegativeButton("Cancel", null)
                    .create())
            }
            .setNegativeButton("Cancel", null).create())
    }

    private fun pickWordPressLanding(
        title: String, pack: DispatchPack, cfg: com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config
    ) {
        val rows = listOf<Pair<String, () -> Unit>>(
            "📄  Save as a draft" to { sendPackWordPress(title, pack, cfg, publish = false, syndicate = false) },
            "🌐  Publish — syndicates to Bluesky" to { sendPackWordPress(title, pack, cfg, publish = true, syndicate = true) },
            "🌐  Publish, no syndication" to { sendPackWordPress(title, pack, cfg, publish = true, syndicate = false) },
        )
        showModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("How should it land?")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton("Cancel", null)
            .create())
    }

    private fun sendPackWordPress(
        title: String, pack: DispatchPack,
        cfg: com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config,
        publish: Boolean, syndicate: Boolean,
    ) {
        showMessage(if (publish) "Publishing…" else "Saving draft…", binding.root)
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val wp = com.toolsboox.plugin.calendar.nw.WPPublish
            val media = wp.uploadMedia(ctx, pack.png, title, cfg)
            val wantsSyndication = publish && syndicate
            val tagIds = if (wantsSyndication) {
                val t = wp.termBySlug(ctx, "post_tag", "essay", cfg)
                    ?: wp.createTerm(ctx, "post_tag", "essay", cfg)
                listOfNotNull(t?.id)
            } else emptyList()
            val html = pack.text.split("\n\n").filter { it.isNotBlank() }.joinToString("") {
                "<p>" + android.text.TextUtils.htmlEncode(it.trim()).replace("\n", "<br>") + "</p>"
            }
            val result = wp.save(ctx, com.toolsboox.plugin.calendar.nw.WPPublish.Draft(
                title = title.ifBlank { "Untitled" },
                content = html,
                status = if (publish) "publish" else "draft",
                tags = tagIds,
                featuredMedia = media ?: 0,
            ), cfg = cfg)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                // The picture failing to upload is a FACT of the outcome, not a detail — a post
                // that went out wordless-of-image should say so, not read plain "Published".
                val pictureNote = if (media == null) " — the picture didn't upload" else ""
                showMessage(when {
                    result.ok && wantsSyndication && tagIds.isNotEmpty() ->
                        "Published · syndicating to Bluesky within 30 min$pictureNote"
                    result.ok && wantsSyndication ->
                        "Published — but the “essay” tag didn't stick, so it won't syndicate$pictureNote"
                    result.ok && publish -> "Published (no syndication)$pictureNote"
                    result.ok -> "Draft created$pictureNote"
                    else -> result.error ?: "WordPress refused it"
                }, binding.root)
            }
        }
    }

    /** 🌱 Fluent — the community space gram, its own door now rather than a row inside "Post"
     *  (Michael 08-27: "doors for publishing to wordpress, bluesky, email, and fluent"). */
    private fun dispatchCommunity(pack: DispatchPack, title: String? = null) {
        val ctx = requireContext()
        // The ACTIVE site's bridge first; failing that, any configured site whose bridge is ready
        // (Correspondence posts through the site in focus, and "sharing to community doesn't
        // work" from here was this sheet asking only the active site — 09-13).
        val sites = com.toolsboox.plugin.calendar.nw.SiteStore.all(ctx)
        val ready = sites.map { it to com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.configFor(ctx, it) }
            .filter { it.second.ready }
        val active = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx).takeIf { it.ready }
        val community = active ?: ready.firstOrNull()?.second
        if (community == null) {
            showMessage("No site has a community bridge yet — Settings › Sites: add the site's app password", binding.root); return
        }
        val communityName = ready.firstOrNull { it.second.site == community.site }?.first?.name ?: community.site
        val titleIn = android.widget.EditText(ctx).apply {
            setText(title ?: "Note — $currentDate"); setSingleLine(); setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(titleIn)
        }
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Community · $communityName").setView(box)
            .setPositiveButton("Next") { _, _ ->
                postPackToSpace(titleIn.text.toString().trim().ifBlank { "Note — $currentDate" }, pack, community)
            }
            .setNegativeButton("Cancel", null).create())
    }

    /** The space gram rides image + title (the rail's whole vocabulary — the words live in the
     *  WP-draft destination when they must travel whole). */
    private fun postPackToSpace(title: String, pack: DispatchPack, cfg: com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.Config) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val spaces = withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.spaces(ctx, cfg) }
            if (spaces.isEmpty()) {
                showMessage("Couldn't load ${cfg.site.removePrefix("https://")}'s spaces — check the site's app password", binding.root); return@launch
            }
            val labels = spaces.map { (if (it.privacy == "public") "🌐  " else "🔒  ") + it.title }.toTypedArray()
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Post to space")
                .setItems(labels) { _, which ->
                    val space = spaces[which]
                    lifecycleScope.launch(Dispatchers.IO) {
                        val b64 = android.util.Base64.encodeToString(pack.png, android.util.Base64.NO_WRAP)
                        val status = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.postGram(
                            ctx, b64, title, "note-" + java.util.UUID.randomUUID().toString().lowercase(), space.id, cfg = cfg)
                        withContext(Dispatchers.Main) { if (isAdded) showMessage(status, binding.root) }
                    }
                }
                .setNegativeButton("Cancel", null).showModal()
        }
    }

    /** 🦋 The gram→Bluesky queue: the FIRST page rides as the picture (Bluesky's card is one
     *  image — the stitch stays for mail), the pack's words prefill the composer under the
     *  300-grapheme ceiling, and the fuller words ride as the image's alt text — the one place
     *  a whole page's OCR fits a Bluesky post. Guarded: the words mid-edit are work. */
    private fun dispatchBluesky(firstPage: String, pack: DispatchPack) {
        val ctx = requireContext()
        val prefill = pack.text.take(280)   // room under the ceiling for hand-edits
        val input = android.widget.EditText(ctx).apply {
            setText(prefill); setSelection(prefill.length)
            gravity = android.view.Gravity.TOP; setLines(5)
            hint = "The post's words (the first page rides as the picture)"
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Share on Bluesky").setView(box)
            .setPositiveButton("Send") { _, _ ->
                val words = input.text.toString()
                // The page rendered fresh and Bluesky-sized on Main (the compositor reads live
                // fields); a full-resolution page doesn't need to test the poster's blob ceiling.
                val page = runCatching { renderPageBitmap(firstPage) }.getOrNull() ?: run {
                    showMessage("Couldn't picture the page", binding.root); return@setPositiveButton
                }
                val sized = if (page.width > 1000)
                    android.graphics.Bitmap.createScaledBitmap(
                        page, 1000, page.height * 1000 / page.width, true).also { page.recycle() }
                else page
                showMessage("Sharing…", binding.root)
                lifecycleScope.launch(Dispatchers.IO) {
                    val baos = java.io.ByteArrayOutputStream()
                    sized.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                    sized.recycle()
                    val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    val alt = pack.text.take(1000).ifBlank { "A handwritten page from the Ledger" }
                    val r = com.toolsboox.plugin.calendar.nw.BlueskyReply.shareGram(
                        ctx.applicationContext, words, b64, alt = alt, link = "",
                        gramId = "note-" + java.util.UUID.randomUUID().toString().lowercase())
                    withContext(Dispatchers.Main) { if (isAdded) showMessage(r.message, binding.root) }
                }
            }
            .setNegativeButton("Cancel", null).create())
    }

    /** ☑ The lasso's mint, fed by the pack: the selection's words become the item text (the
     *  confirm sheet is where a long page gets trimmed), the selection's ink IS the item's ink,
     *  and the confirmed task rides the same when-parse + CalDAV push as every handwritten task
     *  — live, never proposed: a hand-driven dispatch is a deliberate act. Per-page OCR rather
     *  than createLedgerItem's crop because selected pages share one canvas space — a combined
     *  crop would overlay pages into one unreadable image; the pack already read them page by
     *  page. */
    private fun dispatchTask(pack: DispatchPack) {
        if (pack.text.isBlank()) {
            showMessage(R.string.ledger_extract_unreadable, binding.root); return
        }
        val kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK
        // itemWithText refuses inkless selections; a typed-only page mints the same shape the
        // email star does — text, no stroke refs.
        val item = com.toolsboox.plugin.calendar.ot.LedgerExtractor
            .itemWithText(pack.strokes, kind, pack.text, "note", dueDate())
            ?: com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                id = "li-" + java.util.UUID.randomUUID().toString().lowercase(),
                kind = kind, text = pack.text.trim(), date = dueDate(), source = "note"
            )
        val whenParsed = withParsedWhen(item)
        confirmLedgerItem(whenParsed, kind) {
            calendarDay.ledgerItems.add(whenParsed)
            calendarPattern.updateDay(calendarDay)
            presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            // App-authoritative push, the createLedgerItem tail: a confirmed task becomes a
            // CalDAV VTODO (idempotent by id; the event push no-ops for a task).
            lifecycleScope.launch(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), whenParsed)
                com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(requireContext(), whenParsed)
            }
            showMessage(getString(R.string.ledger_extract_added, "task", whenParsed.text), binding.root)
        }
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
    // ── The Roots band is RETIRED ────────────────────────────────────────────────────────────────
    //
    // It ran the width of the day page's right column, between Tasks and Quick Wins, and it held
    // five computed "garden doors": the spiral's root pick, a hot #tag, a sprout, a missed rhizome,
    // and the rising "#tag just rooted" nudge. All five led to Roots, Seeds or Sprouts — the three
    // surfaces retired with it — and all five were DERIVED: not one of them held anything that
    // isn't still sitting in the day files, the tag index and the connection graph.
    //
    // What went with it, and why it is worth naming: the band was the most expensive thing on this
    // page. Choosing a root pick walked EVERY day file; the sprout and missed doors ran embeddings
    // over the whole corpus. Both were parked in prefs precisely because they could not be afforded
    // on the render path — which is a lot of machinery to keep warm for a line Michael read past.
    // The five rows it occupied have gone back to the Tasks grid, which had twelve for 240 open
    // tasks (see CalendarDayPage).
    //
    // Nothing addressed by a URI has moved. The tags still index, the connection graph still joins,
    // Missed Connections still finds what you skipped, and every page any door ever pointed at
    // opens from the Directory exactly as it did.


    /** The panel's ☀ Today, for the bottom pill's notes tray (Michael 2026-10-11: Today in the
     *  notes button's hold "with the same behaviour as the panel's Today"). */
    fun goToday() = onCenterTapped()

    /**
     * The rail's Today tap: when you're not on today, jump to TODAY's version of the section
     * you're currently on; once you're already on today, a tap opens the Ledger hub. This
     * is the iPad feed-carrot pattern — first tap goes home, the next reveals the menu.
     */
    private fun onCenterTapped() {
        val today = LocalDate.now()
        val np = currentNotePage()
        if (currentDate != today) {
            // From a past day → jump to today's version of the current section.
            if (np != null) CalendarNavigator.toDayNote(this, today, np)
            else CalendarNavigator.toDayPage(this, today, CalendarDay.DEFAULT_STYLE)
        } else if (np != null) {
            // On today but inside a section → the plain day page IS "today".
            //
            // The old else-arm opened the sections switcher here (the iPad feed-carrot
            // pattern). In the field that read as "the Today button opens menus instead of
            // going to today" (Michael, 08-14) — and the rail's nav cluster has its own 🗂
            // Sections button, so the second meaning was a duplicate door. One button,
            // one meaning: Today takes you to today.
            CalendarNavigator.toDayPage(this, today, CalendarDay.DEFAULT_STYLE)
        } else {
            // Already standing on today's page — nothing to jump to.
            showMessage("You're on today", binding.root)
        }
    }

    /**
     * The sunshine's sections switcher, folder-aware: the CURRENT folder's sections first, then
     * jump-links to the sibling folders — mirroring the hub's taxonomy so the two never drift. On a
     * Flow/day page that's "On this day" (Day · Intake · Pickings · Synthesize · Write); on a Garden
     * page (Gratitude / Self Executive) it's the Garden's own surfaces. Landing on a section adopts
     * its glyph on the pill; the sibling links open their folder's head.
     */
    /**
     * The directory bar under the date strip: what board you're standing in, and the door to the
     * rest of them.
     *
     * Pickings' only way in was the ▦ hub's "Pickings" row, which opened a picker that could not
     * describe the day you were on — so from a page you were actually working in there was nothing
     * that named the board or offered its siblings. A surface that accumulates pages needs to say
     * which one you're on; without it the boards exist but are unreachable from where you use them.
     *
     * Deliberately a LABEL that happens to be tappable rather than a button: it earns its space by
     * telling you where you are even when you never touch it. Same bargain the iPad's chip makes.
     */
    /**
     * RETIRED (field, 08-29): the chip and the ‹ N › pill both wore the same note's name and
     * doors — "pills and carrots that replicate the name and carrots of the same note." ONE
     * element per fact now: the pill carries name + position + steps ([refreshNotePagerLabel]),
     * its tap is the one note menu, its hold the family sheet. What the chip alone offered —
     * the per-kind shelf — lives where the canon put kind-first walks: behind the hub's
     * 🗂 Directory row (and the Notebook door inside the note menu answers "what else is
     * there" the classic way). The view stays in the layout, hidden, so nothing referencing
     * it breaks.
     */
    private fun bindDirectoryChip() {
        binding.directoryChip.visibility = View.GONE
    }

    /**
     * THE TODAY DASHBOARD is now DRAWN INTO the schedule template (CalendarDayPage.drawDashboardRight),
     * not laid on top as Views — Michael's key architectural correction (DESIGN-homepages-2026-08-22,
     * CORRECTION 3): "They're meant to be part of the page, not laid on top of the page." So this
     * method only retires the old overlays: the horizontal notes band (folded into the Notes zone)
     * and the now-removed dashboard View tree stay hidden. The zones (Tasks/Memo write-by-hand,
     * Notes minis, landscape Doodle) are page ink; their taps are page-region hit-rects resolved in
     * [onCanvasSingleTap] (the PickingsCover idiom), and their promotes read the recorded write regions.
     */
    private fun bindNotesBand() {
        if (!::binding.isInitialized) return
        // Both overlays are retired — the dashboard is part of the page bitmap now.
        binding.notesBand.visibility = View.GONE
    }

    /**
     * Promote the Tasks region's ink into a live task. "Tasks is meant to be a place to write by
     * hand" — you write tasks directly in the Tasks region (normal page strokes), then tap its "→"
     * glyph to gather the strokes whose centre falls in that region and make a LedgerItem through
     * the SAME [createLedgerItem] the lasso uses (source "task"; a handwritten task is a LIVE task,
     * never proposed). The ink stays on the page.
     */
    private fun promoteTasks() {
        if (notePage != null || !::calendarDay.isInitialized) return
        val rect = com.toolsboox.plugin.calendar.ot.CalendarDayPage.tasksWriteRect ?: return
        val strokes = strokesInRect(rect)
        if (strokes.isEmpty()) {
            showMessage("Nothing written in Tasks yet — jot a line, then tap →.", binding.root); return
        }
        createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK, "task")
    }

    /** The Memo "→" glyph: a memo can just BE a memo, or promote to a task or event on the ask. */
    private fun memoPromoteMenu() {
        if (notePage != null || !::calendarDay.isInitialized) return
        showIconMenu("Memo", listOf(
            "🗒  Promote to Task" to { promoteMemo(com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK) },
            "📆  Promote to Event" to { promoteMemo(com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) },
            "✎  Keep as a memo" to { showMessage("Kept as a memo.", binding.root) }
        ))
    }

    /** Strokes on the schedule face whose centroid falls inside a canvas-space region. */
    private fun strokesInRect(rect: android.graphics.RectF): List<com.toolsboox.da.Stroke> {
        val pageStrokes = calendarDay.calendarStrokes[calendarStyle] ?: emptyList()
        return pageStrokes.filter { st ->
            val pts = st.strokePoints
            if (pts.isEmpty()) return@filter false
            var cx = 0f; var cy = 0f
            for (p in pts) { cx += p.x; cy += p.y }
            cx /= pts.size; cy /= pts.size
            rect.contains(cx, cy)
        }
    }

    /**
     * A downscaled mini of a note page for the dashboard's Notes/Doodle tiles. Reuses
     * renderPageBitmap(pageKey) — the export door's page compositor — then samples it down to a
     * bounded width. Cached by page + stroke count so a page redraws only when its ink changed
     * (e-ink cheap, at most three alive). Null when the page has no ink and isn't the doodle.
     */
    /** A short human name for a dashboard note thumbnail, drawn below it: the document's
     *  label for a named/family page, else "Page N" for a numeric page. */
    private fun dashNoteTitle(pageKey: String): String {
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        pageKey.toIntOrNull()?.let { return "Page ${it + 1}" }
        return runCatching { docs.label(docs.surfaceOf(pageKey) ?: pageKey) }.getOrNull()
            ?: pageKey.substringBefore('-').replaceFirstChar { it.uppercase() }
    }

    private fun renderNoteThumb(pageKey: String, maxWidthPx: Int): android.graphics.Bitmap? {
        if (!::calendarDay.isInitialized) return null
        val strokeCount = calendarDay.noteStrokes[pageKey]?.size ?: 0
        val cacheKey = "$pageKey:$strokeCount:$maxWidthPx"
        noteThumbCache[cacheKey]?.let { return it }
        val full = runCatching { renderPageBitmap(pageKey) }.getOrNull() ?: return null
        val scale = maxWidthPx.toFloat() / full.width.toFloat()
        val mini = if (scale < 1f)
            android.graphics.Bitmap.createScaledBitmap(
                full, maxWidthPx, (full.height * scale).toInt().coerceAtLeast(1), true)
        else full
        // Keep the cache to the three-or-four tiles actually on screen.
        if (noteThumbCache.size > 8) noteThumbCache.clear()
        noteThumbCache[cacheKey] = mini
        return mini
    }

    /**
     * Promote the Memo region's ink into a task or event. The Memo region is DRAWN INTO the template
     * (a labeled sub-region of the top-right half); its canvas-space bounds are recorded at draw time
     * ([CalendarDayPage.memoWriteRect]). Gathers the strokes whose centre falls in that region and
     * hands them to the SAME [createLedgerItem] the lasso uses — vision/on-device OCR, when-parse,
     * the pen-guarded confirm — tagged source "memo". A handwritten item IS the item; the ink stays
     * on the page, and an un-promoted memo just stays a memo.
     */
    private fun promoteMemo(kind: com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind) {
        if (notePage != null || !::calendarDay.isInitialized) return
        val rect = com.toolsboox.plugin.calendar.ot.CalendarDayPage.memoWriteRect ?: return
        val memoStrokes = strokesInRect(rect)
        if (memoStrokes.isEmpty()) {
            showMessage("Nothing in the memo yet — jot a line, then promote.", binding.root)
            return
        }
        createLedgerItem(memoStrokes, kind, "memo")
    }

    private fun showSectionSwitcher() {
        // Every day page belongs to ONE folder now. Filter and Daily used to split this list —
        // what arrived versus what you do with yourself — and Michael collapsed the two ("yah,
        // daily"), because All Stars and Gram Picks are things touched daily, not a category
        // visited. The funnel keeps its ORDER, where the meaning actually lived (arrive → pick →
        // arrange); it just no longer has a box drawn round it.
        val currentFolder = "Daily"

        val sections: Pair<String, List<GoItem>> = "Daily" to buildList {
            // Mid-walk, a ⚡ fast-lane to the next station rides on top — the SAME chain the
            // stepper and the finger swipes read (ritualNextStep), so no two controls can
            // disagree about "next". On THIS page's day, as every step of the walk is (Michael
            // 2026-10-11: walking back down "Wednesday I think" — the day you turned to stays
            // the day; it used to jump to today's).
            ritualNextStep()?.let { (page, _, label) ->
                add(GoItem("⚡", "Next · $label") { CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, page) })
            }
            // Michael's 08-23 ruling: "the Daily category can be All Stars, Gram Picks, Notes."
            // Pickings and Gratitude are TEMPLATES a Notes page wears now, not destinations of
            // their own (the Notes IA model, carried to its end); their pages ride inside the
            // dated note's ‹ N › walk. Self Executive is ELIMINATED as a destination (history
            // still renders wherever it exists). Quick Wins and Missed Connections live in Ask.
            // The page in hand gets a name it can be found by — and two pages wearing the
            // same tag are LINKED, Supernote-style: the tag index is the corridor between
            // them ("tag a page… and go to the tag", 09-03).
            notePage?.let { pk ->
                add(GoItem("🏷", "Tag this page") { tagCurrentPage(pk) })
            }
            // THE JUMP (Michael, 09-03: "we just need to be able to jump to tags") — the tags
            // he writes in ink are already harvested with their spots; this is the corridor
            // back: tag index → its pages → the page. Two taps from any note to any tag.
            add(GoItem("#", "Jump to a tag") {
                com.toolsboox.plugin.feeds.ui.showTagIndex(this@CalendarDayFragment)
            })
            // ☆ THE OUTLINE — the pages starred into it, in order. Empty until the first star,
            // and it says so instead of opening a blank room.
            add(GoItem("☆", "Outline") {
                val t = com.toolsboox.plugin.calendar.ot.LedgerTags.list(requireContext())
                    .firstOrNull { it.tag == "outline" }
                if (t != null) com.toolsboox.plugin.feeds.ui.showTagPages(this@CalendarDayFragment, t)
                else showMessage("☆ Nothing outlined yet — 🏷 Tag a page and pick ☆ Outline.", binding.root)
            })
            // The walk's own stations, on THIS page's day like the ⚡ row above them (Michael
            // 2026-10-11: walking up to All Stars and back down keeps "Wednesday"; review
            // 2026-10-11 — Daily IS the walk, and these four jumped to today's). Today is ☀ Today's
            // job (the rail's seat, the notes tray) and ★ Daily's below, the room's door.
            add(GoItem("☀︎", "Day") { CalendarNavigator.toDayPage(this@CalendarDayFragment, currentDate, CalendarDay.DEFAULT_STYLE) })
            add(GoItem("★", "All Stars") { CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "intake") })
            add(GoItem("◈", "All Picks") {
                CalendarNavigator.toDayNote(
                    this@CalendarDayFragment, currentDate,
                    com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
                )
            })
            add(GoItem("✒", "Notes") { CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "0") })
            add(GoItem("🗺", "Map") { findNavController().navigate(R.id.action_to_ledger_map) })
        }

        // Sibling folders: the other top-level doors (current one dropped), each opening its head —
        // in the Hub's order (Michael 2026-10-11: "Today, Daily, Mail, Feeds, Desk, Bookshelf,
        // Notes, wordpress"): Daily · 📧 The Mail · 📰 Feeds (+ 🌐 The Net) · Desk · Bookshelf, then
        // Ask · Log as they were. (Notes rides in the Daily list above.)
        val siblings = buildList {
            // The Daily head is the day page itself — gratitude stopped being a door when it
            // became a template (08-23).
            add(GoItem("★", "Daily") { CalendarNavigator.toDayPage(this@CalendarDayFragment, LocalDate.now(), CalendarDay.DEFAULT_STYLE) })
            // 📧 THE MAIL keeps its one-tap home here (Michael 2026-10-10: "'sections' should have
            // email"; 2026-10-11: Mail is its own room) — the Hub's own Mail door, landing on the
            // mailbox you were in. Its own glyph too (✉ is Correspondence, plan v6 §1).
            val mailDoor = com.toolsboox.plugin.feeds.ui.IncomingDoors.Door.THE_MAIL
            add(GoItem(mailDoor.glyph, mailDoor.title) {
                com.toolsboox.plugin.feeds.ui.openMailRoom(findNavController())
            })
            // 📰 Feeds (Michael 2026-10-11: "Change "Incoming" to "Feeds" with the rss image" — 📰
            // is the glyph this modal draws as the RSS mark) through the normal door
            // (openFeedView, the Hub folder's own move), then the Feeds doors Sections carries,
            // in the shared order and with the shared names (🌐 The Net).
            add(GoItem("📰", com.toolsboox.plugin.feeds.ui.FEEDS_HOME) {
                com.toolsboox.plugin.feeds.ui.openFeedView(findNavController(), "both", null)
            })
            val feedsDoors = mapOf<com.toolsboox.plugin.feeds.ui.IncomingDoors.Door, () -> Unit>(
                com.toolsboox.plugin.feeds.ui.IncomingDoors.Door.THE_NET to {
                    findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                        SiteWebFragment.ARG_OPEN_WEB to true))
                },
            )
            for (door in com.toolsboox.plugin.feeds.ui.IncomingDoors.inOrder(feedsDoors.keys))
                add(GoItem(door.glyph, door.title, feedsDoors.getValue(door)))
            add(GoItem("🗒", "Desk") { findNavController().navigate(R.id.action_to_ledger_items) })
            add(GoItem("📚", "Bookshelf") { findNavController().navigate(R.id.action_to_reader) })
            add(GoItem("💬", "Ask") { findNavController().navigate(R.id.action_to_ledger_chat) })
            add(GoItem("🕘", "Log") {
                ReadingLogSelection.origin = null
                findNavController().navigate(R.id.action_to_reading_log)
            })
        }.filter { it.label != currentFolder }

        // From the rail, so it grows from the rail's edge (see railOnRight).
        showGoModal(listOf(sections, "Go to" to siblings), anchorTop = false, anchorEnd = railOnRight())
    }

    // ─── THE RITUAL WALK ────────────────────────────────────────────────────────────────────────
    //
    // ONE list, read by everything: the ⚡ fast-lane, the stepper's ↑/↓, the pager arrows and the
    // finger swipes (vertical and horizontal). It used to be three hand-written `when` blocks that
    // had to be kept saying the same thing, which is how the fast-lane and the stepper once came to
    // disagree about what "next" meant.
    //
    //     Day → All Stars → Gram Picks → Pickings → Self Executive → Quick Wins →
    //           Missed Connections → Gratitude → the numbered Notes tail
    //
    // The list itself now lives in [RitualWalk], because two of those stations are not pages in the
    // day file — Quick Wins and Missed Connections are surfaces of their own, and a walk that could
    // only name pages could not contain them. It is the Daily folder, in Daily's order, on purpose:
    // Daily IS the walk, and two ways of stating one order eventually disagree. They already had,
    // which is exactly what put "Day Skipper doesn't include Self Executive, Quick Wins, Missed
    // Connections, then Gratitude" on Michael's punchlist.
    //
    // A previous pass cut this to four stations on measured usage — selfexec 4 written days in 124,
    // against gratitude 44 and pickings 33 — and Michael has now put Self Executive back. Worth
    // recording why that is not a reversal: the measurement could see how often a page was WRITTEN
    // and could not see what it was FOR. Self Executive is a page he keeps deliberately.
    //
    // Gram Picks stays on the walk despite its single ink day, and that is not an inconsistency:
    // 158 grams have landed in it. It is a place you SORT, not a place you write, and its worth is
    // measured in what passes through it.
    //
    // What left the walk is still reachable, deliberately by another route: Self Executive from
    // Daily, Grid and Jot from Notes, Write from its own door, and every one of them plus every
    // Synthesize page from the Directory and from any `ledger://<date>/<page>` link. Leaving the
    // walk is not leaving the ledger.

    /** The walk's DAY PAGES, in order — the subset of [com.toolsboox.plugin.calendar.ot.RitualWalk] this fragment can host.
     *  The walk itself is longer than this: Quick Wins and Missed Connections are stations too,
     *  and they are surfaces of their own, so stepping onto one leaves this fragment entirely. */
    private val ritualWalk: List<String>
        get() = com.toolsboox.plugin.calendar.ot.RitualWalk.pageKeys

    /** The walk station this page stands on, or null when it is off the walk. A sub-page
     *  (grampicks#1) folds onto its base, whose neighbours it shares. (Pickings stopped being a
     *  station 08-23 — it is a template a Notes page wears; a board off the walk steps back onto
     *  Notes through the tail rule below, which is its honest neighbour now.) */
    private fun walkStation(): String? {
        return baseNotePage(notePage)?.takeIf { it in ritualWalk }
    }

    /** The next station of the walk after the page we're on, or null off-walk — what the ⚡
     *  fast-lane offers. Past the last station the walk ends: the numbered notes are a tail you
     *  page through, not a destination to be hurried towards. */
    private fun ritualNextStep(): Triple<String, String, String>? {
        val here = walkStation() ?: return null
        val next = com.toolsboox.plugin.calendar.ot.RitualWalk.next(here) ?: return null
        return Triple(next.key, next.glyph, next.label)
    }

    /** The glyph and the name a walk station wears in the fast-lane. Kept beside the walk so a
     *  station added there cannot arrive nameless. (Named apart from the page-wide [sectionEmoji]
     *  on purpose: that one answers "what am I looking at" for every page in the app, this one
     *  answers "what is the next step called" for the four on the walk.) */
    private fun walkGlyph(page: String): String = com.toolsboox.plugin.calendar.ot.RitualWalk.station(page)?.glyph ?: "🙏"

    private fun walkLabel(page: String): String = com.toolsboox.plugin.calendar.ot.RitualWalk.station(page)?.label ?: "Gratitude"

    /** Step to the PREVIOUS station (the stepper's ↑, a downward or rightward finger swipe).
     *  Up from the Day still exits to the Week almanac — the Boox convention, and where the iOS
     *  ring goes too. Every step carries [currentDate] — the flick, the rail's ▲▼, the pager's ‹
     *  and the volume keys all come through here — so walking back down to the day page lands on
     *  the day you turned to, never today (Michael 2026-10-11: "Wednesday I think"). */
    fun ritualStepBack() {
        if (notePage == null) {
            // Back from the walk's head still exits UP to the week almanac — the Boox convention,
            // and where the iOS ring goes too. This is the one step that leaves the day, which is
            // why it is the head's job and nobody else's.
            CalendarNavigator.toWeekPage(this, currentDate, locale)
            return
        }
        val here = walkStation()
        if (here != null) {
            val prev = com.toolsboox.plugin.calendar.ot.RitualWalk.prev(here)
            // `prev` is TODAY for the first note station, and TODAY's own open() is the schedule
            // face — so this is one path now instead of two that had to agree.
            if (prev == null) CalendarNavigator.toDayPage(this, currentDate, CalendarDay.DEFAULT_STYLE)
            else prev.open(this, currentDate)
            return
        }
        // Off the walk — the numbered notes and every named surface that left it. Page 0 steps back
        // onto the walk's last station, which is what makes the tail feel joined to it rather than
        // marooned; anything else steps to the previous number.
        val page = baseNotePage(notePage)?.toIntOrNull() ?: 0
        if (page == 0) com.toolsboox.plugin.calendar.ot.RitualWalk.last.open(this, currentDate)
        else CalendarNavigator.toDayNote(this, currentDate, "${page - 1}")
    }

    /** Step to the NEXT station (the stepper's ↓, an upward or leftward finger swipe). */
    fun ritualStepForward() {
        if (notePage == null) {
            // Standing on TODAY, which is the walk's own first station now — so step to the one
            // AFTER it (All Stars), not to `first`, which would re-open the page under your finger.
            val after = com.toolsboox.plugin.calendar.ot.RitualWalk.next(
                com.toolsboox.plugin.calendar.ot.RitualWalk.TODAY)
            (after ?: com.toolsboox.plugin.calendar.ot.RitualWalk.first).open(this, currentDate)
            return
        }
        val here = walkStation()
        if (here != null) {
            val next = com.toolsboox.plugin.calendar.ot.RitualWalk.next(here)
            // Past the last station (Notes itself now) the walk hands over to the dated note's
            // own ‹ N › walk — pickings, gratitude-to-be, the doodle, the numbered tail — rather
            // than looping back onto the page you are standing on.
            if (next == null) datedNoteStep(1)
            else next.open(this, currentDate)
            return
        }
        val page = baseNotePage(notePage)?.toIntOrNull() ?: 0
        CalendarNavigator.toDayNote(this, currentDate, "${page + 1}")
    }

    /**
     * Which edge the rail is docked on — the same `calendarToolbarSide` truth the ⇄ button
     * flips. The menus the rail opens ask this so they can grow from the rail's own edge
     * (Michael: "have it come from the same side as the popout").
     */
    private fun railOnRight(): Boolean =
        sharedPreferences.getString("calendarToolbarSide", "LEFT") == "RIGHT"

    /**
     * Clear the page — ONE confirm, reached by HOLDING the eraser.
     *
     * Michael, 2026-08-24: "the trashcan icon on the sidepanel is unnecessary, since we can erase
     * the page via the eraser button so it's redundant. Also, it asks twice if I want to erase it
     * all. Only needs to ask once."
     *
     * Both halves were the same mistake. This used to open a one-row menu that said "Clear page",
     * whose only row then fired the trash button's own "Are you sure?" — so a destructive act had
     * TWO doors in front of it, and the first one asked nothing. A confirm you answer twice is not
     * twice as safe; it is a confirm you learn to tap through, which is worse than one.
     *
     * The rail's trashcan went with it. A tool rail that carries both an eraser and a trashcan is
     * offering two faces for erasing, and the hold was always the real door.
     */
    private fun showClearPageConfirm() {
        binding.toolbarDrawing.toolbarTrash.performClick()
    }

    /** Wrench on the rail → the quick tools/layout shortcut (a subset of the hub). */
    private fun showWidgetGearMenu() {
        // The Synthesize→Write creative pipeline lives on the Synthesize page (Card / 3 questions /
        // outline) with the writing prompt also reachable from Write — so it doesn't clutter every
        // other page. "View tasks & events" left the wrench (it's in the ▦ hub).
        val onSynth = com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage)
        val onWrite = com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(notePage)
        val tools = buildList {
            // "Add text" / "Add image" left the wrench: hold-to-add (long-press on the page, bare
            // canvas or over an image) already offers "Text box" and "Add media…" everywhere, so
            // these were duplicates. Matches the iPad, where they were removed for the same reason.
            add(GoItem("🔷", "Simple shapes…") { openShapesPicker() })
            // 🧹 THE PANEL, CLEANED (09-13, the crescent on the ☰): hand the panel back from any
            // raw layer and repaint everything once, in full.
            add(GoItem("🧹", "Clean the screen") {
                runCatching { com.onyx.android.sdk.api.device.epd.EpdController.enablePost(provideSurfaceView(), 1) }
                forceFullEpdRefresh(cleanDaemon = true)
            })
            // ✒ Synth left the wrench (Michael, 08-30: "No wrench — it's a gram/annotation
            // add-on"; his own dedupe canon: wrench = settings, holds/rail = actions). The
            // summon lives on the ⟡ pill glyph, the lasso menu, and the gram hold.
            if (onSynth) add(GoItem("🃏", "Card…") { showCardMenu() })
            add(GoItem("❝", "Pickings") {
                CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate,
                    com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY)
            })
            add(GoItem("📄", "Whole page → text") { wholePageToText() })
            add(GoItem("🗂", "Capture sections") { captureSections() })   // auto-capture toggle now lives in Settings
            if (onSynth) add(GoItem("❖", "Synthesize · 3 questions") { synthesizeQuestions() })
            if (onSynth || onWrite) add(GoItem("✍", "Writing prompt → Lines") { writingPrompts() })
            if (onSynth) add(GoItem("🗺", "Map this page") { mapThisPage() })
            if (onSynth) add(GoItem("🗒", "Essay outline → Lines") { essayOutline() })
            if (onSynth) add(GoItem("🃏", "Ideas → grid") { showSynthesisIdeas() })
            add(GoItem("👆", "Finger / hand") { binding.toolbarDrawing.toolbarHandTouch.performClick() })
            add(GoItem("🔄", "Rotate screen") { binding.toolbarDrawing.toolbarRotate.performClick() })
            // The quick-note toggle rides EVERY wrench (Michael, field: hiding it from the
            // reader's wrench stranded it — no way back from surfaces without the row).
            val penShown = (activity as? com.toolsboox.ui.main.MainActivity)?.quickNoteVisible() ?: true
            add(GoItem("🖉", if (penShown) "Hide the quick-note button" else "Show the quick-note button") {
                (activity as? com.toolsboox.ui.main.MainActivity)?.setQuickNoteVisible(!penShown)
            })
        }
        // ☰ Hub, the first row (Michael 2026-10-12: the touch route to the main menu, since the
        // rail's Hub seat went — "Make sure we can get to hub with back swipe … or touch").
        showGoModal(
            withHub(listOf(
                "Tools" to tools,
                "Layout" to listOf(
                    // "Reset pill positions" and the master pill switch left this menu with the
                    // pills themselves: the rail replaced them on this surface, and it has no
                    // position to reset — ⛶ full screen is what "hide" means here now (plan v6
                    // S1.2, Michael 2026-10-10; the edge strip stays only on the list surfaces
                    // until S3). (Other surfaces' pills still answer to Settings → Legibility.)
                    // ☐ Tool labels — the captions under the rail's icons (plan v6 S1.1, Michael
                    // 2026-10-10: "make labels an option"). One global switch; one reconnect.
                    toolLabelsRow(tuckPanel),
                    GoItem("⚙️", "Settings") { binding.toolbarDrawing.toolbarSettings.performClick() }
                )
            )),
            anchorTop = false,
            // The wrench lives on the rail now, so its menu grows from the rail's edge too.
            anchorEnd = railOnRight()
        )
    }


    /** The card sources for this page: the whole page + each panel. */
    private fun cardChoices(): List<com.toolsboox.plugin.calendar.ot.LedgerPanel> =
        listOf(
            com.toolsboox.plugin.calendar.ot.LedgerPanel(
                "page", getString(R.string.card_whole_page),
                com.toolsboox.plugin.calendar.ot.LedgerPanel.Kind.DOODLE,
                android.graphics.RectF(0f, 0f, 1404f, 1872f)
            )
        ) + com.toolsboox.plugin.calendar.ot.LedgerPanel.forPage(notePage)

    /** Card flow: pick the source (whole page / a panel), then the action. */
    private fun showCardMenu() {
        val choices = cardChoices()
        showIconMenu(getString(R.string.card_pick_panel),
            choices.map { panel -> panel.title to { chooseCardAction(panel) } })
    }

    /**
     * The lasso "card" chip: what should the circled strokes become?
     *
     * Grouped as CREATE and ADD TO, because those are two different questions. Create makes a new
     * thing out of the ink; Add to files it into something that already exists. Flat, the list ran
     * to seven unlabelled entries and you had to read all of them to find out which kind of act
     * each one was.
     *
     * This is also now the only way handwriting becomes a task — the write-in strip is gone — so
     * it had better be the good one. It keeps the ink and links to it, and shows you the text
     * before committing.
     */
    override fun onSelectionExtract(strokes: List<com.toolsboox.da.Stroke>) {
        if (!::calendarDay.isInitialized || strokes.isEmpty()) return
        showSelectionMenu(strokes, adding = selectionAddMode)
    }

    /** Which half of the lasso menu you were last in — it usually stays the same for a while. */
    private var selectionAddMode = false

    /** An element-only lasso (either entry: the circle gesture or the toolbar tool) closed over
     *  cards: open the selection menu straight away — there is no ink selection to hang chips on. */
    override fun onLassoElementsOnly(bounds: android.graphics.RectF): Boolean {
        if (!::calendarDay.isInitialized) return false
        val page = notePage ?: "default"
        if (elementsInRegion(page, bounds).isEmpty()) return false
        showSelectionMenu(emptyList(), adding = selectionAddMode)
        return true
    }

    /**
     * One list, and a switch at the top saying what the list DOES.
     *
     * Task, event, gram, A/V gram and picking are the same five things whether you are making a
     * new one or filing into an existing one — so the difference is a mode, not a different menu.
     * Two labelled groups meant the same five words appeared twice and you had to read both to
     * find out which was which.
     */
    private fun showSelectionMenu(strokes: List<com.toolsboox.da.Stroke>, adding: Boolean) {
        val flip = if (adding) getString(R.string.ledger_selection_switch_create)
            else getString(R.string.ledger_selection_switch_add)
        val title = if (adding) getString(R.string.ledger_selection_add_to)
            else getString(R.string.ledger_selection_create)

        // ADD TO always asks WHICH one — that is the entire difference from Create. DEDUPED to
        // his 08-30 ruling ("some of this seems redundant"): ONE 🃏 Pick verb per half (the
        // region becomes/joins a pick; boards left the choosers in -44, onward travel is the
        // pick's own verbs — "❝ Picking" was the same act wearing a second name, and in ADD-TO
        // it was literally the same function twice). 🎬 A/V pick left the lasso outright: it
        // RECORDED new media and never read the circled ink — a misplaced door, not a variant
        // (it lives on in Add media and the pen tray).
        val actions: List<Pair<String, () -> Unit>> = if (adding) listOf(
            "🗒  Task" to { addSelectionToExistingItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK) },
            "📆  Event" to { addSelectionToExistingItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) },
            "🃏  Pick" to { addSelectionToPickings(strokes) }
        ) else listOf(
            "🗒  Task" to { createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK) },
            "📆  Event" to { createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) },
            "🃏  Pick" to { gramStudioFromSelection(strokes) }
        )

        // THE LASSO CAPTURES CARDS TOO (Michael, 08-30: "we'll also need multi-select with the
        // lasso"): elements under the LOOP join the selection and feed the batch verbs. The
        // region of truth is the closed loop's own bounds (lastLassoBounds) — the selected ink's
        // box missed cards inside the loop but outside the ink ("doesn't seem to work on grams").
        // Ink bounds remain only as the fallback for menu re-entries with no loop on record.
        val page = notePage ?: "default"
        val lassoedElements = runCatching {
            val region = lastLassoBounds ?: if (strokes.isEmpty()) null else
                com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes).let {
                    android.graphics.RectF(it.left, it.top, it.right, it.bottom)
                }
            // Reply stickies ride the capture too — re-Synthable by ruling.
            if (region == null) emptyList() else elementsInRegion(page, region)
        }.getOrDefault(emptyList())
        // The page's base decides the mode (2026-10-12): "synthesize#1" — page 2 and on of the daily
        // page — is the conversation too; the bare key test drove those pages to the drive-by.
        val onSynth = onSynthPage()
        // ❖ SYNTH WITH ▸ (Michael, 2026-10-12, ruling 1) — the voice list, then the same summon in
        // that voice: in place on the conversation page (the page takes the voice; circled ink +
        // circled cards are ONE exhibit, ink first), the drive-by anywhere else.
        val synthWith: Pair<String, () -> Unit> = "❖  Synth with ▸" to {
            showSynthVoiceList(SYNTH_WITH_TITLE) { voice ->
                if (onSynthPage()) {
                    setSynthPageVoice(voice)
                    synthCorrespond(selection = strokes.takeIf { it.isNotEmpty() },
                        subjects = lassoedElements.takeIf { it.isNotEmpty() }, voice = voice)
                } else if (strokes.isEmpty()) driveBySynthCards(lassoedElements, voice = voice)
                else driveBySynthInk(strokes, voice = voice, cards = lassoedElements)
            }
        }
        val batch: List<Pair<String, () -> Unit>> = if (lassoedElements.isEmpty()) emptyList() else buildList {
            // One rule everywhere (the two-mode model): the conversation page answers the set
            // in place; any other page drive-bys the set into its own note.
            add("❖  Synth the set · ${lassoedElements.size}" to {
                if (onSynth) synthCorrespond(subjects = lassoedElements)
                else driveBySynthCards(lassoedElements)
            })
            // "The circled cards" carries Synth with ▸ between its two rows; the ink menu carries
            // it once, under its own ❖ Synth row.
            if (strokes.isEmpty()) add(synthWith)
            add("🗑  Delete the set · ${lassoedElements.size}" to {
                // Pen-guarded (the Bigme rule: daemon input disabled around every occluding
                // surface — a bare .show() would let pen events route into the page beneath).
                showModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                    .setTitle("Delete ${lassoedElements.size} cards?")
                    .setMessage("The cards die on every device. The ink stays.")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteImageElements(lassoedElements)
                        calendarPattern.updateDay(calendarDay)
                        presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create())
            })
        }

        // If this handwriting ALREADY became something, say so first.
        //
        // The link was stored one way only: the item keeps the strokeIds, and the page keeps no
        // idea that its ink now stands for a task. So circling the words you turned into a task
        // offered to make a second one, and the only wired direction was the destructive one —
        // erase the ink and the item goes with it, silently.
        val already = itemsMadeFrom(strokes)
        val existing: List<Pair<String, () -> Unit>> = already.take(3).map { item ->
            val what = if (item.kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) "event" else "task"
            val words = item.text.ifBlank { getString(R.string.ledger_selection_handwritten) }.take(40)
            "🕸  Already a $what · $words" to { showItemRhizome(item) }
        }

        // An element-only loop (no ink selected) offers only the set's verbs — every other
        // row acts on strokes and would be a no-op wearing a label.
        if (strokes.isEmpty()) {
            if (batch.isNotEmpty()) showIconMenu("The circled cards", batch)
            return
        }

        showIconMenu(title, existing + listOf<Pair<String, () -> Unit>>(
            flip to {
                selectionAddMode = !adding
                showSelectionMenu(strokes, !adding)
            }
        ) + actions + listOf(
            "📋  Copy text" to { copyTextFromSelection(strokes) },
            // THE TWO-MODE MODEL (Michael, 08-31): the conversation page continues IN PLACE
            // (the circle is the prompt — nesting stops at depth 1); every other page is the
            // drive-by (always mints, sticky back). One rule, never "sometimes a note".
            "❖  " + (if (onSynth) "Synth this ink" else "Synth") to {
                if (onSynth) synthCorrespond(selection = strokes)
                else driveBySynthInk(strokes)
            },
            synthWith,
            "🔎  Ask about this" to { askAboutSelection(strokes) },
            // ONE Educate row (his ruling — the pair was redundant): mark-then-act, one act.
            // gramSelectionToEducateMe retired from the menu; its machinery stays for the
            // capture-verbs that still speak it.
            "🎓  Educate me" to { educateFromSelection(strokes) },
            "🔍  Find in Ledger" to { findInLedgerFromSelection(strokes) }
        ) + (if (onSynth)
            // DELIBERATE BRANCHING only (the nesting guard): the conversation page's default
            // Synth continues in place; this secondary door forks a genuinely new thread.
            listOf<Pair<String, () -> Unit>>("❖  Branch → new page…" to { driveBySynthInk(strokes) })
        else emptyList()) + batch)
    }

    /** The tasks or events these strokes were turned into — the back-pointer nothing else reads. */
    private fun itemsMadeFrom(strokes: List<com.toolsboox.da.Stroke>): List<com.toolsboox.plugin.calendar.da.v2.LedgerItem> {
        if (!::calendarDay.isInitialized || strokes.isEmpty()) return emptyList()
        val ids = strokes.map { it.strokeId.toString() }.toSet()
        return calendarDay.ledgerItems.filter { item -> item.strokeIds.any { it in ids } }
    }

    /** Walk from the ink to what it became, and on to everything that joins it. */
    private fun showItemRhizome(item: com.toolsboox.plugin.calendar.da.v2.LedgerItem) {
        val uri = com.toolsboox.plugin.calendar.ot.LegacyEdges.adopt(requireContext(), item, currentDate)
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to uri,
                LedgerRhizomeFragment.ARG_LABEL to item.text.ifBlank { getString(R.string.ledger_selection_handwritten) }
            )
        )
    }

    /**
     * Add to an EXISTING task or event: pick which one, and the circled ink becomes its face.
     *
     * A task you wrote by hand and a task already on the list are the same task — this is how the
     * handwriting gets attached to it, rather than making a second one that says the same thing.
     */
    private fun addSelectionToExistingItem(
        strokes: List<com.toolsboox.da.Stroke>,
        kind: com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind
    ) {
        val candidates = calendarDay.ledgerItems.filter { it.kind == kind && !it.done }
        if (candidates.isEmpty()) {
            showMessage(getString(R.string.ledger_selection_nothing_to_add_to), binding.root); return
        }
        val bmp = renderSelection(strokes) ?: return
        val labels = candidates.map { it.text.ifBlank { getString(R.string.ledger_selection_handwritten) }.take(50) }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(getString(R.string.ledger_selection_add_to))
            .setItems(labels.toTypedArray()) { _, which ->
                val target = candidates[which]
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)   // ink: hard edges
                val i = calendarDay.ledgerItems.indexOfFirst { it.id == target.id }
                if (i < 0) return@setItems
                calendarDay.ledgerItems[i] = target.copy(
                    crop = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP),
                    display = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Display.INK
                )
                presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                showMessage(getString(R.string.ledger_selection_added_to, labels[which]), binding.root)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    /** The circled ink, rendered as a card, onto a Pickings board of your choosing. */
    private fun addSelectionToPickings(strokes: List<com.toolsboox.da.Stroke>) {
        val bmp = renderSelection(strokes) ?: return
        com.toolsboox.plugin.calendar.ot.PickingsPlacement.chooseAndPlace(
            this, calendarDayService, documentsRoot(), bmp, currentDate,
            sourceLabel = currentDate.toString())
    }

    /** The circled ink into the Clippings library, to be placed anywhere later. */
    private fun addSelectionToClippings(strokes: List<com.toolsboox.da.Stroke>) {
        val bmp = renderSelection(strokes) ?: return
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)   // ink: hard edges, so PNG
        com.toolsboox.plugin.calendar.ot.ClippingsStore.add(
            requireContext(),
            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP),
            label = currentDate.toString())
        showMessage(getString(R.string.ledger_selection_clipped), binding.root)
    }

    /**
     * Lift a date and a time out of an item's OCR'd words, leaving the rest as its name.
     *
     * "meet Sam fri 3pm" is one gesture and three facts. Both readers are plain parsing rather
     * than a model call, so they work with no key and no signal — which is the whole reason the
     * on-device OCR fallback exists, and it would be odd for the step after it to need a network.
     */
    private fun withParsedWhen(
        item: com.toolsboox.plugin.calendar.da.v2.LedgerItem
    ): com.toolsboox.plugin.calendar.da.v2.LedgerItem {
        if (item.text.isBlank()) return item
        val (afterDate, due) = com.toolsboox.plugin.calendar.ot.TaskEntry
            .splitTrailingDue(item.text, currentDate)
        val (words, time) = com.toolsboox.plugin.calendar.ot.TaskEntry.splitTrailingTime(afterDate)
        if (due == null && time == null) return item
        val day = due ?: currentDate
        // A time makes the item's own moment; without one, noon UTC — the ledger's convention for
        // "this day" rather than an implied midnight.
        val at = if (time != null) {
            val (h, m) = time.split(":").map { it.toInt() }
            java.util.Date(day.atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        } else {
            java.util.Date(day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        }
        return item.copy(text = words.ifBlank { item.text }, date = at, time = time ?: item.time)
    }

    /** The selection as a bitmap, with a little air so edge strokes aren't shaved off. */
    private fun renderSelection(strokes: List<com.toolsboox.da.Stroke>): android.graphics.Bitmap? {
        if (strokes.isEmpty()) return null
        val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
        val pad = 28f
        val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        // Heavier than the page's own 3, because this render is downscaled twice more before it
        // is seen: once into the card and once by the panel. See renderInk.
        return com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600, 5f)
    }

    /**
     * CREATE a picking: a new sheet named by what you circled, with that on it.
     *
     * The circled words are already the title — reading them and asking you to type them again is
     * a question with the answer written on the page. So it names itself, and the ink goes on the
     * sheet too, so the page carries the thing in your own hand as well as in a label.
     *
     * And it does NOT leave the page. You circled something on the day you are working on, and
     * being teleported away mid-thought is the wrong default: the point of making a board is
     * usually to put several things on it, and the next one is back here. It offers to go, and
     * waits. The new board shows up in "Add to → Picking" straight away, so sending the next
     * thing there is two taps without ever moving.
     */
    private fun createPickingFromSelection(strokes: List<com.toolsboox.da.Stroke>) {
        val bmp = renderSelection(strokes) ?: return
        val creds = aiCreds()
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                val read = if (creds != null) {
                    val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                    val pad = 28f
                    val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                    val page = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                    com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(page, creds.first, creds.second, creds.third)
                } else null
                // Offline, or unreadable: the Boox's own ink OCR, then the date. A board with a
                // dull name is still a board; refusing to make one because the recogniser had a
                // bad moment is not a trade worth making.
                (read ?: com.toolsboox.plugin.calendar.ot.LedgerExtractor
                    .extractStrokes(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                        "lasso", dueDate())?.text)
                    ?.replace(Regex("\\s+"), " ")?.trim()?.take(48)
                    ?.ifBlank { null }
            } ?: currentDate.toString()

            if (!isAdded) return@launch
            val page = com.toolsboox.plugin.calendar.ot.PickingsStore.add(requireContext(), currentDate, name)
            withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                    calendarDayService, documentsRoot(), bmp, currentDate, page.key)
            }
            if (!isAdded) return@launch
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, getString(R.string.ledger_picking_created, page.name),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction(R.string.ledger_picking_go) {
                    CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, page.key)
                }
                .show()
        }
    }

    /** OCR the lassoed ink, then show it as selectable/editable text with a one-tap Copy — so a
     *  selection can leave the Ledger as plain text (clipboard), not only as a task/event/gram. */
    private fun copyTextFromSelection(strokes: List<com.toolsboox.da.Stroke>) {
        val creds = aiCreds()
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                val pad = 28f
                val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                if (creds != null)
                    com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third) else null
            }
            if (text.isNullOrBlank()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            showCopyText(text.trim())
        }
    }

    /** Editable/selectable OCR result with a Copy action (and system text-selection for partial copy). */
    private fun showCopyText(text: String) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setText(text); setTextIsSelectable(true); setSelection(text.length)
        }
        val padPx = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0); addView(input)
        }
        fun copy() {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger", input.text.toString()))
            showMessage("Copied to clipboard", binding.root)
        }
        showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Copy text")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Copy") { _, _ -> copy() }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /**
     * Turn the circled strokes into ONE task/event. High-quality first: render the ink to an image
     * and read it with the vision model (Ask-my-Ledger key) — far better on real handwriting; falls
     * back to the on-device ink OCR when no key is set or the call fails.
     *
     * [source] is the provenance stamped on the item: the lasso passes "lasso"/"lasso-ai" (the
     * default), the Memo strip passes "memo"/"memo-ai" so a jotted-then-promoted item says where it
     * was born. Either way it is a handwritten item, so it is a LIVE task — never proposed.
     */
    private fun createLedgerItem(
        strokes: List<com.toolsboox.da.Stroke>,
        kind: com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind,
        source: String = "lasso"
    ) {
        val visionSource = if (source.endsWith("-ai")) source else "$source-ai"
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) {
                val creds = aiCreds()
                if (creds != null) {
                    val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                    val pad = 28f   // breathing room so edge letters aren't clipped
                    val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                    val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                    val t = com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
                    if (!t.isNullOrBlank())
                        com.toolsboox.plugin.calendar.ot.LedgerExtractor
                            .itemWithText(strokes, kind, t.trim(), visionSource, dueDate())
                    else null
                } else null
            // No key, no signal, or the call failed: the Boox's own on-device ink OCR. The point
            // of it is that it works on a plane, so everything after this must work there too.
            } ?: com.toolsboox.plugin.calendar.ot.LedgerExtractor.extractStrokes(strokes, kind, source, dueDate())

            if (item == null) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            // Read the WHEN out of the words, whichever OCR produced them.
            //
            // This used to sit inside the vision branch only, so the same handwriting gave a due
            // date online and none offline — the sort of difference that reads as the app being
            // unreliable rather than as a feature with a fallback. It is plain parsing, so it
            // costs nothing and works with no signal; anything it can't read stays in the name,
            // where you can see it and fix it, rather than being guessed at.
            val whenParsed = withParsedWhen(item)
            confirmLedgerItem(whenParsed, kind) {
                calendarDay.ledgerItems.add(whenParsed)
                calendarPattern.updateDay(calendarDay)
                presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                // App-authoritative push: a confirmed task becomes a CalDAV VTODO, a confirmed event a
                // Google Calendar entry (idempotent by id; each no-ops for the other kind).
                lifecycleScope.launch(Dispatchers.IO) {
                    com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), whenParsed)
                    com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(requireContext(), whenParsed)
                }
                val label = if (kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) "event" else "task"
                // whenParsed, not item — the confirm dialog's edits live on the parsed copy, and
                // quoting the pre-edit words back made a correction look like it hadn't taken.
                showMessage(getString(R.string.ledger_extract_added, label, whenParsed.text), binding.root)
            }
        }
    }

    /**
     * Lasso → "Educate me": OCR the circled term (usually a band or a book), look it up with the
     * SAME LLM as Ask-my-Ledger ([LedgerChatService.educate], general-knowledge — not corpus-bound),
     * and file the answer + a canonical link into today's Later List **Educate** section. That store
     * (IntakePageStore) is what surfaces in the Feed, so the result lands "in a feed with a link".
     */
    private fun educateFromSelection(strokes: List<Stroke>) {
        val creds = aiCreds()
        if (creds == null) {
            showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return
        }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                val pad = 28f
                val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                val term = com.toolsboox.plugin.calendar.nw.VisionOcr
                    .recognize(bmp, creds.first, creds.second, creds.third)?.trim()
                if (term.isNullOrBlank()) null
                else term to com.toolsboox.plugin.chat.nw.LedgerChatService()
                    .educate(creds.first, creds.second, creds.third, term)
            }
            if (outcome == null) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            val (term, result) = outcome
            when (result) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                    val answer = result.answer.trim()
                    // The LLM ends with a canonical URL line; peel it off for the link + clean description.
                    val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(answer).lastOrNull()
                        ?: "https://www.google.com/search?q=" + android.net.Uri.encode(term)
                    val desc = answer.replace(url, "").trim()
                    withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                            requireContext(), currentDate, "educate", url, "$term — $desc"
                        )
                    }
                    showEducateResult(term, desc, url)
                }
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err ->
                    showMessage(result.message, binding.root)
            }
        }
    }

    /**
     * Lasso → "Find in Ledger": OCR the circled term and open Ask-my-Ledger seeded with it, so the
     * grounded chat searches the reader's OWN corpus (highlights, annotations, planner) for it.
     */
    private fun findInLedgerFromSelection(strokes: List<Stroke>) {
        val creds = aiCreds()
        if (creds == null) { showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val term = withContext(Dispatchers.IO) {
                val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                val pad = 28f
                val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)?.trim()
            }
            if (term.isNullOrBlank()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            findNavController().navigate(
                R.id.action_to_ledger_chat,
                androidx.core.os.bundleOf("initial_query" to term)
            )
        }
    }

    /**
     * Lasso → "Ask about this": OCR the circled ink and hand it to Ask my Ledger through
     * [com.toolsboox.plugin.calendar.ot.AskBridge] — the chat opens knowing this page is where
     * it came from and what already connects to it. Unreadable (or keyless-and-unreadable) ink
     * still goes: a blank selection means "push the whole thing", so the page itself is asked
     * about rather than the trip being refused.
     */
    private fun askAboutSelection(strokes: List<Stroke>) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { selectionText(strokes) }
            if (!isAdded) return@launch
            com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
                this@CalendarDayFragment, text, selectionPageLabel(),
                com.toolsboox.ot.LedgerUri.page(currentDate.toString(), notePage ?: "default"),
                "the Ledger")
        }
    }

    /**
     * Lasso → "Gram to Educate Me": the circled words become a quote-face question gram inside
     * the intake sheet's EDUCATE ME panel (via [com.toolsboox.plugin.calendar.ot.AskBridge]).
     * Distinct from "Educate me" above, which LOOKS the term up; this one files the question
     * itself to study later.
     */
    private fun gramSelectionToEducateMe(strokes: List<Stroke>) {
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { selectionText(strokes) }
            if (!isAdded) return@launch
            if (text.isNullOrBlank()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            com.toolsboox.plugin.calendar.ot.AskBridge.gramToEducateMe(
                this@CalendarDayFragment, text, selectionPageLabel(),
                com.toolsboox.ot.LedgerUri.page(currentDate.toString(), notePage ?: "default"),
                "the Ledger")
        }
    }

    /** The circled ink as words: vision OCR when a key is set, the on-device ink OCR otherwise —
     *  the same ladder as create/copy, shared so the Ask rows read handwriting identically. */
    private suspend fun selectionText(strokes: List<Stroke>): String? {
        val creds = aiCreds()
        val read = if (creds != null) {
            val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
            val pad = 28f
            val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
            val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
            com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
        } else null
        // SAY WHICH READER READ IT (Michael, 09-14, B6: "for whatever reason it can't transcribe
        // this"): cursive is the vision model's or nobody's. A silent fall to the on-device
        // readers gave garbage with no hint why — now the fall-through says so, once.
        if (creds == null) withContext(Dispatchers.Main) {
            if (isAdded) showMessage("No AI key on this device — read with the on-device recogniser (weak on cursive). Ask my Ledger settings → key.", binding.root)
        } else if (read == null) withContext(Dispatchers.Main) {
            // The REASON, not a list of guesses (09-14: "it still comes up couldn't read the
            // selection") — the API's own error line, or the network exception, then the fall.
            val why = com.toolsboox.plugin.calendar.nw.VisionOcr.lastFailure ?: "the model returned nothing"
            if (isAdded) showMessage("Vision read failed ($why) — fell back to the on-device recogniser, weak on cursive.", binding.root)
        }
        return (read ?: com.toolsboox.plugin.calendar.ot.LedgerExtractor
            .extractStrokes(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK, "lasso", dueDate())?.text)
            ?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
    }

    /** What to call this page as a source: the day, plus the named page when we're on one. */
    private fun selectionPageLabel(): String =
        "Ledger · $currentDate" + (notePage?.let { " · $it" } ?: "")

    /** Show the looked-up description with an option to open the link; it's already filed to the feed. */
    private fun showEducateResult(term: String, desc: String, url: String) {
        showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(term)
            .setMessage(desc + "\n\n" + getString(R.string.ledger_educate_filed))
            .setPositiveButton(R.string.ledger_educate_open) { _, _ ->
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .create())
    }

    /** The DUE date for a created item: the page's own day at noon UTC (matches the iPad convention). */
    private fun dueDate(): java.util.Date =
        java.util.Date(currentDate.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())

    /**
     * Confirmation box after "Create task/event" — shows the read-back text, the kind, and the DUE
     * date (the page's day, so a future page reads its own date). [onConfirm] commits the item.
     */
    private fun confirmLedgerItem(
        item: com.toolsboox.plugin.calendar.da.v2.LedgerItem,
        kind: com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind,
        onConfirm: () -> Unit
    ) {
        val isEvent = kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT
        val chosen = itemLocalDate(item)
        val due = chosen.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
        // Editable read-back so a shaky OCR is fixed here (parity with the iPad create sheet).
        val input = android.widget.EditText(requireContext()).apply {
            setText(item.text); setSelection(text.length); setSingleLine(false); maxLines = 3
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        val title = (if (isEvent) "Create event" else "Create task") + " · " + due + (item.time?.let { " · $it" } ?: "")
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Create") { _, _ -> item.text = input.text.toString().trim(); onConfirm() }
            // Due date defaults to the page's day; "Change date" sets it at finalization instead.
            .setNeutralButton("Change date") { _, _ ->
                item.text = input.text.toString().trim()   // keep edits when picking a date
                pickDueDate(chosen) { picked ->
                    item.date = java.util.Date(picked.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
                    confirmLedgerItem(item, kind, onConfirm)   // re-show with the new date
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        showModal(dialog)
    }

    /** The LedgerItem's date as a LocalDate (its due date lives in UTC millis). */
    private fun itemLocalDate(item: com.toolsboox.plugin.calendar.da.v2.LedgerItem): java.time.LocalDate =
        item.date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate()

    /** Native date picker for setting a due date at finalization; pauses raw ink like other modals. */
    private fun pickDueDate(initial: java.time.LocalDate, onPicked: (java.time.LocalDate) -> Unit) {
        val dp = android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d -> onPicked(java.time.LocalDate.of(y, m + 1, d)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth
        )
        dp.setOnShowListener { onModalShown() }
        dp.setOnDismissListener { onModalDismissed() }
        dp.show()
    }

    /** Turn the circled strokes into a card (share / save to Notes / webhook) via the card flow. */
    private fun createCardFromSelection(strokes: List<com.toolsboox.da.Stroke>) {
        val rect = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
        chooseCardAction(
            com.toolsboox.plugin.calendar.ot.LedgerPanel(
                "selection", getString(R.string.ledger_selection_card),
                com.toolsboox.plugin.calendar.ot.LedgerPanel.Kind.DOODLE, rect
            )
        )
    }

    /** Record an audio/video gram (or photo) like any other gram: capture → persist to the day's
     *  avGrams → transcribe (Whisper) → open the gram studio on the transcription so it can be shared,
     *  dropped Here, or added to Pickings. */
    private fun recordAvGram() {
        captureAvGram { onAvGramRecorded(it) }
    }

    /**
     * "Add media… → Voice / Video" on the page's hold-menu. Straight into the recorder: the
     * kind was already chosen a tap ago.
     */
    override fun onRecordAvGram(kind: com.toolsboox.da.Attachment.Kind) {
        captureAvGramDirect(kind) { onAvGramRecorded(it) }
    }

    private fun onAvGramRecorded(att: com.toolsboox.da.Attachment) {
        if (::calendarDay.isInitialized) {
            calendarDay.avGrams.add(att)
            calendarPattern.updateDay(calendarDay)
            presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        }
        val kind = att.kind
        if (kind == com.toolsboox.da.Attachment.Kind.AUDIO || kind == com.toolsboox.da.Attachment.Kind.VIDEO) {
            showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
            lifecycleScope.launch {
                val file = java.io.File(attachmentsDir(), att.filename)
                val text = withContext(Dispatchers.IO) {
                    com.toolsboox.plugin.calendar.nw.Transcribe.audio(requireContext(), file)
                }
                // File it where it can be seen: a card on today's board carrying its own poster
                // frame, so the recording is an object in the ledger rather than a loose blob.
                val placed = withContext(Dispatchers.IO) { placeAvGramOnPickings(att, file, text) }
                val head = if (kind == com.toolsboox.da.Attachment.Kind.VIDEO) "🎥 Video pick" else "🎤 Audio pick"
                if (placed) showMessage("$head → today's Pickings", binding.root)
                showGramStudio(listOf(head, text ?: "").filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { head })
            }
        } else {
            showMessage(getString(R.string.gram_capture_saved), binding.root)
        }
    }

    /** The day holds the filenames, keyed by attachment id — so this is where the blob is found. */
    override fun pickHoldRows(element: com.toolsboox.da.ImageElement): List<Pair<String, () -> Unit>> =
        if (!::calendarDay.isInitialized) emptyList()
        else com.toolsboox.plugin.feeds.ot.AddToPick.holdRows(this, calendarDayService, documentsRoot(), element,
            calendarDay.avGrams.toList()) { onExternalGramPlaced(notePage ?: "") }

    override fun resolveAvGramFile(element: com.toolsboox.da.ImageElement): java.io.File? {
        if (element.attachmentId.isBlank() || !::calendarDay.isInitialized) return null
        val att = calendarDay.avGrams.firstOrNull { it.id == element.attachmentId } ?: return null
        return java.io.File(attachmentsDir(), att.filename).takeIf { it.exists() }
    }

    /**
     * Put a just-recorded A/V gram on today's board as a picking.
     *
     * The title is the opening of the transcription when there is one — a recording ends up named
     * by its own first words — and otherwise just the kind and the day. Filing itself is
     * [AvGrams.file], shared with the reply composer and the floating pen button.
     *
     * Runs on IO. Returns whether a card was actually placed.
     */
    private fun placeAvGramOnPickings(
        att: com.toolsboox.da.Attachment, file: java.io.File, transcript: String?
    ): Boolean {
        val title = transcript?.trim().orEmpty()
            .lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.let { if (it.length > 48) it.take(47).trimEnd() + "\u2026" else it }
            ?: ""

        return com.toolsboox.plugin.calendar.ot.AvGrams.file(
            calendarDayService, documentsRoot(), file, att, currentDate, title
        )
    }

    /** Gram studio (Android parity): OCR the lassoed ink, then open the multi-format studio. */
    private fun gramStudioFromSelection(strokes: List<com.toolsboox.da.Stroke>) {
        val creds = aiCreds()
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(strokes)
                val pad = 28f
                val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 1600)
                if (creds != null)
                    com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third) else null
            }
            if (text.isNullOrBlank()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            showGramStudio(text)
        }
    }

    /** Multi-format gram studio (shared) — formats, long-text fit strategies, Share/Here/Pickings. */
    private fun showGramStudio(text: String) {
        // UNTIED (add-to-pick pin §2 D1): what's added waits in the sheet (its files already in the
        // store); the card that wears it — card 1 — carries its slots and its clip when placed.
        val app = requireContext().applicationContext
        val pending = com.toolsboox.plugin.feeds.ot.AddToPick.Pending(app, text)
        com.toolsboox.plugin.calendar.ot.GramStudio.show(
            this, text,
            onShare = { cards -> cards.forEach { shareGramBitmap(it) } },
            onPickings = { cards -> cards.forEachIndexed { i, c -> placeGramToPickings(c, pending.takeIf { i == 0 && !it.isEmpty }) } },
            // Here dresses card 1 too (review 2026-10-16 #6): its tap places and media slot, its
            // clips filed into this day's A/V list — a voice or video note recorded in the sheet
            // was drawn on the face and then reachable from nowhere. A page that opened read-only
            // (its file won't read) refuses it out loud ([studioRefusedReadOnly]) — and the adds
            // that card would have worn are kept ([keepStudioAdds]; merge-addpick review #4).
            onHere = { cards ->
                if (!studioRefusedReadOnly())
                    cards.forEachIndexed { i, c -> placeGramBitmap(c, pending.takeIf { i == 0 && !it.isEmpty }) }
                else keepStudioAdds(app, pending.attachments(), refusalSaid = true)
            },
            pending = pending,
            // Closed with no card placed: what was added is kept ([keepStudioAdds]).
            keepUnplaced = { atts -> keepStudioAdds(app, atts) }
        )
    }

    /**
     * A Pick sheet's adds that no card wears — the sheet closed unplaced, or the card's placement was
     * refused or backed out of (merge-addpick review #4: once ❝ or Here was pressed nothing kept
     * them) — filed where they can be found again. Main thread.
     *  - This page, open and writable: its A/V list, in memory (the page holds the day; a write
     *    behind its back loses to its next save).
     *  - This page is today and won't read: there is nowhere to keep them — said
     *    ([studioRefusedReadOnly], unless [refusalSaid]); the files stay in the attachment store.
     *  - Otherwise (a read-only page that isn't today, or the page has gone): today's A/V list on
     *    disk, through its guarded door ([com.toolsboox.plugin.calendar.ot.GramStudio.keepOnToday],
     *    which says so if today won't read either).
     */
    private fun keepStudioAdds(app: android.content.Context, atts: List<com.toolsboox.da.Attachment>,
                               refusalSaid: Boolean = false) {
        if (atts.isEmpty()) return
        val open = ::calendarDay.isInitialized && isAdded
        when {
            open && !dayReadOnly -> {
                atts.forEach { a -> if (calendarDay.avGrams.none { it.id == a.id }) calendarDay.avGrams.add(a) }
                calendarPattern.updateDay(calendarDay)
                presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            }
            open && currentDate == java.time.LocalDate.now() -> {
                Timber.w("pick sheet: today's page won't read — its %d adds stay in the attachment store", atts.size)
                if (!refusalSaid) studioRefusedReadOnly()
            }
            else -> com.toolsboox.plugin.calendar.ot.GramStudio.keepOnToday(app, atts)
        }
    }

    /**
     * Jump back to a gram's origin from its long-press menu. Routes the stored source link:
     * ledger://<date>/<pageKey> → that ledger page; book://<path> → the reader; mail://<id> → that
     * letter open in The Mail lens's article pane; http(s) → the browser.
     */
    override fun onImageSource(element: com.toolsboox.da.ImageElement) {
        val link = element.sourceLink
        when {
            link.startsWith("mail://") || link.startsWith("acct:") -> {
                // A starred email's gram files its address as mail://<id> (the mail star writes it
                // so rhizome edges and grams share one name for the letter). Without this arm the
                // router fell through in silence — "Open the source" on a mail gram, and the All
                // Stars tap that funnels here, did nothing at all. The letter's home is the feeds
                // pane's Mail lens now (the standalone inbox retired), so the handoff rides
                // FeedSelection: a pending id the lens consumes after its warm load by opening
                // that letter in the article pane — one-shot, cleared even when the store no
                // longer holds it. The list behind the pane rides the All view, so the letter's
                // own row is on screen whatever its read or star state. The bare
                // "acct:<accountId>:uid:<n>" spelling is iOS archaeology: the iPad wrote raw ids
                // before build 42 adopted the mail:// convention, and those grams are already on
                // synced boards. Route them too — the pending id simply won't match a letter this
                // device's accounts don't hold, and the lens is still the right room.
                // The Mail's own room since 2026-10-11 (Michael: "You can move mail ... out of
                // incoming"), on All so the letter's row is on screen whatever its state.
                com.toolsboox.plugin.feeds.ui.openMailRoom(
                    findNavController(), mode = "both", letterId = link.removePrefix("mail://"))
            }
            link.startsWith("ledger://") -> {
                val rest = link.removePrefix("ledger://")
                val slash = rest.indexOf('/')
                val dateStr = if (slash >= 0) rest.substring(0, slash) else rest
                val pageKey = if (slash >= 0) rest.substring(slash + 1) else ""
                val date = runCatching { java.time.LocalDate.parse(dateStr) }.getOrNull() ?: currentDate
                // "default" is LedgerUri.page's key for the day page itself (review of 6b46141e):
                // as a note key it drew a blank page called "default".
                if (pageKey.isBlank() || pageKey == "day" || pageKey == "default") CalendarNavigator.toDayPage(this, date)
                else CalendarNavigator.toDayNote(this, date, pageKey)
            }
            // A LINK TO ONE OF YOUR OWN NOTES — from a pasted [[note]] gram, or written by hand.
            //
            // Where it lands depends on whether the target exists yet, and BOTH outcomes are
            // ordinary. Linking forward to something unwritten is how you tell yourself a note
            // should exist; arriving at "nothing named that yet" and being offered the making of
            // it is the honest end of that gesture, and much better than an error about a broken
            // link — the link is not broken, the note is merely still owed.
            //
            // THE NAMED NOTE ITSELF (Michael 2026-10-11: "The links aren't quite working yet").
            // This used to open the first page that LINKS TO the target — another mention, not the
            // note — so a [[Collider]] gram never reached the note called Collider even when it
            // existed. Resolved by the Notebook's own all-time walk (followNoteLink, off the main
            // thread), the same list the Links index uses. An OWED target keeps the old landing —
            // another page that mentions it — so nothing this tap used to reach is lost.
            link.startsWith("note://") -> {
                // The URI body is the normalised target, which resolves the same (normalise is
                // idempotent).
                com.toolsboox.plugin.feeds.ui.followNoteLink(
                    this, link.removePrefix("note://"), currentDate to (notePage ?: "default"))
            }
            link.startsWith("book://") -> {
                requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
                    .putString("current_book_path", link.removePrefix("book://")).apply()
                findNavController().navigate(R.id.action_to_reader)
            }
            link.startsWith("http") -> {
                // Prefer the Feed Ledger's in-pane reader (clean native render, an "open original ↗"
                // inside) over a raw WebView, which renders external pages badly on e-ink — through
                // ArticleOpen (synth-list-links §3): the cached entry by id, then by address, then the
                // server by id, else the live page in the pane. The stand-in's id is 0, never
                // link.hashCode(): a positive hash marked some unrelated Miniflux entry read.
                com.toolsboox.plugin.feeds.ot.ArticleOpen.open(this, link,
                    com.toolsboox.plugin.calendar.ui.SynthListRun.entryIdFor(
                        if (::calendarDay.isInitialized) calendarDay else null, notePage, element),
                    com.toolsboox.plugin.calendar.ui.SynthListRun.titleFor(element), element.sourceFeed)
            }
        }
    }

    override fun imageLinkRows(element: com.toolsboox.da.ImageElement): List<com.toolsboox.ot.LedgerContextMenu.Item> =
        if (!::calendarDay.isInitialized) emptyList()
        else com.toolsboox.plugin.calendar.ui.SynthListRun.holdRows(this, calendarDay, notePage, element)

    /** Open a gram's external source INSIDE Ledger (a WebView), with a one-tap "open original" fallback
     *  to the browser — so "Go to source" keeps you in-app instead of ejecting to the system browser. */
    private fun openSourceInApp(url: String) {
        val ctx = requireContext()
        val web = android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true; settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true; settings.displayZoomControls = false
            webViewClient = android.webkit.WebViewClient()
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt())
            loadUrl(url)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setView(web)
            .setPositiveButton("Open original ↗") { _, _ ->
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }
            .setNegativeButton("Close", null)
            .create()
        // Over the drawing surface → pause the Onyx pen so stylus taps don't freeze.
        showModal(dialog)
    }

    /** Choose where this gram card goes (current day) — the one destination funnel, so the gram
     *  studio and the All Stars hold menu speak the same vocabulary as every other chooser: Gram
     *  Picks and the bands included, remembered-last-first, and the pick teaches the memory. The
     *  board list this chooser always carried is preserved inside that vocabulary; placement stays
     *  in-memory ([addGramToPickingPage]) because this fragment holds the open day, and a
     *  disk-level write behind its back is how a pen-up save loses a card. */
    private fun placeGramToPickings(bmp: android.graphics.Bitmap,
                                    pending: com.toolsboox.plugin.feeds.ot.AddToPick.Pending? = null) {
        if (!::calendarDay.isInitialized) { pending?.let { keepStudioAdds(requireContext().applicationContext, it.attachments()) }; return }
        val ctx = requireContext()
        val app = ctx.applicationContext
        com.toolsboox.plugin.calendar.ot.GramDestinations.choose(
            ctx, currentDate, "Send this pick to", this,
            // Backed out of the chooser: the adds this card was to wear are kept (review #4).
            onNone = pending?.let { p -> { keepStudioAdds(app, p.attachments()) } }
        ) { dest ->
            val d = dest.date ?: currentDate
            if (d == currentDate) {
                if (!studioRefusedReadOnly()) addGramToPickingPage(bmp, dest.key, dest.kind, pending)
                else pending?.let { keepStudioAdds(app, it.attachments(), refusalSaid = true) }
            }
            else Thread {
                // A note on ANOTHER day: through the disk path under the day lock — the open
                // day is not this day, so the in-memory add has nothing to race.
                // That day through the writer's door (PickingsPlacement.place): one that won't read
                // THROWS, never written over — and the card, its slots and its clips stay unplaced.
                val attempt = runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bmp, d, dest.key,
                        media = pending?.media(),
                        faceSlots = pending?.layout?.let { com.toolsboox.plugin.calendar.ot.PickingsPlacement.storedSlots(it, true) },
                        alsoOnDay = { day -> pending?.clips()?.forEach { c -> if (day.avGrams.none { it.id == c.id }) day.avGrams.add(c) } })
                }.onFailure { Timber.w(it, "pick sheet: the card for %s wasn't placed", d) }
                val failed = attempt.exceptionOrNull()
                // A day that won't read names itself, in the guard's shape (review #10).
                val words = if (failed == null) "Added to ${dest.name} \u00b7 $d"
                    else com.toolsboox.plugin.calendar.fi.CalendarDayService.refusal(failed,
                        com.toolsboox.plugin.calendar.ot.MarkWords.PLACE_IT) ?: "Couldn't place it"
                // Not placed: the adds it was to wear are kept (review #4) — on this page, or on
                // today when the page has gone.
                val act = activity
                if (act == null) {
                    if (failed != null) pending?.let { com.toolsboox.plugin.calendar.ot.GramStudio.keepOnToday(app, it.attachments()) }
                    return@Thread
                }
                act.runOnUiThread {
                    if (isAdded) showMessage(words, binding.root)
                    if (failed != null) pending?.let { keepStudioAdds(app, it.attachments()) }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * This page opened on a file that wouldn't read (read-only — [CalendarDayPresenter.load]): a Pick
     * sheet's card or adds placed onto it would only ever live in memory (every save is refused), so
     * the placement is refused out loud and nothing is added; what the sheet made stays in the
     * attachment store (and the caller keeps the adds, [keepStudioAdds]). Today says the shared
     * placement line ("Couldn't write on today — its page won't read",
     * [com.toolsboox.plugin.calendar.ot.MarkWords.TODAY_REFUSED] — iOS's placement says it too);
     * another day names its page in the guard's shape ("Couldn't place it — the Oct 3 page won't
     * read", [com.toolsboox.plugin.calendar.ot.MarkWords.PLACE_IT]; merge-addpick review #10).
     * True when refused.
     */
    private fun studioRefusedReadOnly(): Boolean {
        if (!dayReadOnly) return false
        Timber.w("pick sheet: the $currentDate page is read-only (its file won't read) — not placed")
        if (isAdded) showMessage(
            if (currentDate == java.time.LocalDate.now()) com.toolsboox.plugin.calendar.ot.MarkWords.TODAY_REFUSED
            else "Couldn't ${com.toolsboox.plugin.calendar.ot.MarkWords.PLACE_IT} — " +
                "${com.toolsboox.plugin.calendar.fi.CalendarDayService.pageName(currentDate)} won't read", binding.root)
        return true
    }

    /** Encode the gram card and add it as an image element on [pageKey] of the current day.
     *  [intakeKind] only means anything when the destination is the All Stars register — there
     *  the card lands aspect-fit into the next slot of its kind's band, the size and spot
     *  [CalendarDayPageIntake.arrivalFrame] chooses, exactly as [PickingsPlacement.place] places
     *  an intake arrival, so the two placement paths agree about what the register looks like. */
    private fun addGramToPickingPage(bmp: android.graphics.Bitmap, pageKey: String, intakeKind: String = "",
                                     pending: com.toolsboox.plugin.feeds.ot.AddToPick.Pending? = null) {
        if (!::calendarDay.isInitialized) return
        val max = 1200
        val longest = maxOf(bmp.width, bmp.height)
        val scaled = if (longest > max)
            android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width * max / longest, bmp.height * max / longest, true) else bmp
        val baos = java.io.ByteArrayOutputStream(); scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        val isIntakeArrival = pageKey == CalendarDayPageIntake.INTAKE_PAGE && intakeKind.isNotBlank()
        val w = (1404f * 0.42f).coerceAtMost(scaled.width.toFloat())
        val h = w * scaled.height / scaled.width
        val count = calendarDay.imageElements.count { it.page == pageKey }
        // The band slot carries the fitted SIZE as well as the position — the register aspect-fits
        // each arrival into the next slot of its kind's band (the shelf grows a row as it fills,
        // so where the slot sits only the whole day file can answer) — so an intake arrival takes
        // the whole rect; everywhere else keeps the natural card size and the plain stagger.
        val bandSlot = if (isIntakeArrival)
            CalendarDayPageIntake.arrivalFrame(intakeKind, calendarDay, w, h)
        else null
        val placedW = bandSlot?.width() ?: w
        val placedH = bandSlot?.height() ?: h
        val x = bandSlot?.left ?: (60f + (count % 3) * (w + 30f)).coerceIn(0f, (1404f - w).coerceAtLeast(0f))
        val y = bandSlot?.top ?: (120f + (count / 3) * (h + 30f)).coerceIn(0f, (1872f - h).coerceAtLeast(0f))
        // Remember the ledger page this card was grammed from so the placed gram can jump back to it.
        val srcPage = notePage ?: "day"
        val srcLabel =
            if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage))
                com.toolsboox.plugin.calendar.ot.PickingsStore.nameOf(requireContext(), currentDate, srcPage)
            else notePage?.replaceFirstChar { it.uppercase() } ?: "Day"
        calendarDay.imageElements.add(com.toolsboox.da.ImageElement(
            x = x, y = y, width = placedW, height = placedH, data = base64, page = pageKey,
            sourceLink = "ledger://$currentDate/$srcPage", sourceLabel = srcLabel,
            intakeKind = intakeKind).also { e ->
                // What was added in the sheet rides this card: its slots (the face is stored as
                // drawn — no tape here) and its clip, filed into the day's A/V list.
                pending?.let { p ->
                    p.stamp(e, treatment = false)
                    p.clips().forEach { c -> if (calendarDay.avGrams.none { it.id == c.id }) calendarDay.avGrams.add(c) }
                }
            })
        calendarPattern.updateDay(calendarDay)
        presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        if (notePage == CalendarDayPageIntake.INTAKE_PAGE && isIntakeArrival) redrawIntakePage()
        showMessage(
            "Added to ${com.toolsboox.plugin.calendar.ot.GramDestinations.name(requireContext(), pageKey, intakeKind, currentDate)}.",
            binding.root
        )
    }

    override fun onSharePage() {
        runCatching { shareGramBitmap(renderPageBitmap()) }.onFailure { showMessage("Share failed", binding.root) }
    }

    private fun gramFile(bmp: android.graphics.Bitmap): android.net.Uri {
        val dir = java.io.File(requireContext().cacheDir, "cards").apply { mkdirs() }
        val file = java.io.File(dir, "gram-${currentDate}-${bmp.width}x${bmp.height}.png")
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file)
    }

    private fun shareGramBitmap(bmp: android.graphics.Bitmap) {
        runCatching {
            val uri = gramFile(bmp)
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share pick"))
        }.onFailure { showMessage("Share failed", binding.root) }
    }

    private fun placeGramBitmap(bmp: android.graphics.Bitmap,
                                pending: com.toolsboox.plugin.feeds.ot.AddToPick.Pending? = null) {
        runCatching {
            // What was added in the sheet rides this card (the face is stored as drawn — no tape).
            if (pending != null) dressNextInsert = { e ->
                pending.stamp(e, treatment = false)
                if (::calendarDay.isInitialized)
                    pending.clips().forEach { c -> if (calendarDay.avGrams.none { it.id == c.id }) calendarDay.avGrams.add(c) }
            }
            queueSharedImageInsert(gramFile(bmp))
        }.onFailure {
            dressNextInsert = null; showMessage("Place failed", binding.root)
            // Not placed: what the card was to wear is kept (merge-addpick review #4).
            pending?.let { p -> keepStudioAdds(requireContext().applicationContext, p.attachments()) }
        }
    }

    /** Ask-my-Ledger AI creds (provider, key, model) for the vision OCR, or null if unset. The read
     *  lives in [SynthSummons.creds] now — a summon resumed with no page standing asks with it too. */
    private fun aiCreds(): Triple<String, String, String>? = SynthSummons.creds(requireContext())

    /** Having picked a source, choose Share / Save to Notes / Send to webhook. */
    private fun chooseCardAction(panel: com.toolsboox.plugin.calendar.ot.LedgerPanel) {
        showIconMenu(panel.title, listOf(
            "📤  Share" to { sharePanelAsCard(panel) },
            "📌  Save to Notes" to { savePanelAsNote(panel) },
            "🛰️  Send to webhook" to { sendPanelToWebhook(panel) }
        ))
    }

    /** Strokes of the current page (note page or the default calendar layer). */
    private fun currentPageStrokes(): List<Stroke> =
        if (notePage != null) calendarDay.noteStrokes[notePage] ?: emptyList()
        else calendarDay.calendarStrokes[calendarStyle] ?: emptyList()

    /** Draw the current page's zone boundaries + labels onto the template (design space 1404×1872). */
    private fun drawZones(canvas: android.graphics.Canvas, notePageKey: String?) {
        val zones = com.toolsboox.plugin.calendar.ot.PageZones.zones(notePageKey)
        if (zones.isEmpty()) return
        val solid = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; color = 0x55000000; strokeWidth = 2f; isAntiAlias = true
        }
        val dashed = android.graphics.Paint(solid).apply {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 9f), 0f)
        }
        val label = android.graphics.Paint().apply { color = 0x99000000.toInt(); textSize = 28f; isAntiAlias = true }
        for (z in zones) {
            canvas.drawRoundRect(z.rect, 14f, 14f,
                if (z.kind == com.toolsboox.plugin.calendar.ot.ZoneKind.IMAGE) dashed else solid)
            canvas.drawText(z.label, z.rect.left + 14f, z.rect.top + 38f, label)
        }
    }

    /**
     * Zone engine (Android parity of iOS): OCR the ink inside each TEXT zone of the current
     * page and store it under the zone's key; if the zone carries an AI prompt, run the OCR'd
     * text through the LLM and store the result under "<id>.ai". Saved per note-page + day.
     */
    private fun captureSections() = runCaptureSections(silent = false)

    /**
     * OCR the current page's capture zones (paid vision OCR + optional per-zone LLM) into the
     * section store. [silent] is the auto-on-page-leave path: no toasts, and it runs on a
     * detached scope so it finishes even as the fragment pauses. Needs an AI key.
     */
    private fun runCaptureSections(silent: Boolean) {
        val notePageKey = currentNotePage()
        val zones = com.toolsboox.plugin.calendar.ot.PageZones.zones(notePageKey)
        if (zones.isEmpty()) { if (!silent) showMessage("This page has no capture zones.", binding.root); return }
        val creds = aiCreds() ?: run {
            if (!silent) showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return
        }
        val strokes = currentPageStrokes()
        val pageKey = notePageKey ?: "default"
        val ctx = requireContext().applicationContext
        val date = currentDate
        if (!silent) showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        (if (silent) GlobalScope else lifecycleScope).launch {
            val n = withContext(Dispatchers.IO) {
                val values = com.toolsboox.plugin.calendar.ot.SectionStore.load(ctx, pageKey, date)
                for (zone in zones) {
                    if (zone.kind != com.toolsboox.plugin.calendar.ot.ZoneKind.TEXT) continue
                    val inZone = strokes.filter {
                        android.graphics.RectF.intersects(
                            zone.rect, com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(listOf(it)))
                    }
                    if (inZone.isEmpty()) continue
                    val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(inZone, zone.rect, 1600)
                    val text = com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
                    if (text.isNullOrBlank()) continue
                    values[zone.id] = text
                    // Harvest #hashtags from THIS zone's text. The bitmap was rendered from the whole
                    // zone rect, so a normalized tag box maps back through zone.rect; any tag the model
                    // can't place falls back to the zone rect (never worse than zone-level).
                    harvestTags(ctx, date, pageKey, text, bmp, zone.rect, zone.rect, creds)
                    val prompt = zone.aiPrompt
                    if (prompt != null) {
                        val r = com.toolsboox.plugin.chat.nw.LedgerChatService()
                            .run(creds.first, creds.second, creds.third, prompt, text)
                        if (r is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok) values[zone.id + ".ai"] = r.answer
                    }
                }
                com.toolsboox.plugin.calendar.ot.SectionStore.save(ctx, pageKey, date, values)
                values.keys.count { !it.endsWith(".ai") && !it.startsWith("__") }
            }
            if (!silent) showMessage("Captured $n section(s)", binding.root)
        }
    }

    /** One-tap: OCR the WHOLE current page to a single block of text (selectable + copyable). */
    private fun wholePageToText() {
        val creds = aiCreds()
        if (creds == null) { showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return }
        val strokes = currentPageStrokes()
        if (strokes.isEmpty()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val rect = android.graphics.RectF(0f, 0f, 1404f, 1872f)
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 2000)
                com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
            }
            if (text.isNullOrBlank()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            showCopyText(text.trim())
        }
    }

    /** A cheap signature of a page's ink so we only re-OCR when it actually changed. */
    private fun strokesSignature(strokes: List<Stroke>): String =
        "${strokes.size}:${strokes.sumOf { it.strokePoints.size }}"

    /** Auto-OCR every TEXT section of the current page in the background (no UI), so section text is
     *  always available without a manual "Capture sections". Skips when the ink is unchanged since
     *  the last capture (a persisted signature) to avoid needless vision calls. Fire-and-forget on
     *  an app-scoped coroutine because it runs as the page is being left. */
    @kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun autoCaptureSections() {
        if (!::calendarDay.isInitialized) return
        val notePageKey = currentNotePage()
        val zones = com.toolsboox.plugin.calendar.ot.PageZones.zones(notePageKey)
        if (zones.isEmpty()) return
        val creds = aiCreds() ?: return
        val strokes = currentPageStrokes()
        if (strokes.isEmpty()) return
        val appCtx = requireContext().applicationContext
        val pageKey = notePageKey ?: "default"
        val date = currentDate
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            var read = 0
            val values = com.toolsboox.plugin.calendar.ot.SectionStore.load(appCtx, pageKey, date)
            var any = false
            for (zone in zones) {
                if (zone.kind != com.toolsboox.plugin.calendar.ot.ZoneKind.TEXT) continue
                // A stroke belongs to the zone its MIDDLE falls in, not every zone it grazes.
                // Intersection double-counted a line sitting on a boundary — read once by each
                // side, so the corpus got the same thought twice and paid twice for it.
                val inZone = strokes.filter { s ->
                    val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(listOf(s))
                    zone.rect.contains(b.centerX(), b.centerY())
                }
                if (inZone.isEmpty()) continue
                // Per-zone signature: re-read only what changed. One signature for the whole page
                // meant a single new word anywhere sent every zone back to the model, so the cost
                // of a page tracked how often you returned to it rather than how much you wrote.
                val zoneSig = strokesSignature(inZone)
                if (values["__sig.${zone.id}"] == zoneSig) continue
                // Send the strokes' OWN bounds, not the band's rectangle: a band is a bucket, so
                // a line written across a boundary belongs whole to one band and must be rendered
                // whole, not clipped to the band's edge.
                val bounds = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(inZone)
                    .apply { inset(-24f, -24f) }
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(inZone, bounds, 1600)
                val text = com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
                    ?: continue          // network down / rate-limited: try again next time
                if (text.isBlank()) continue
                // The signature is stored only once the ink has actually been READ. Storing it
                // before the call meant a flat battery of wifi — a plane, a rate limit, an expired
                // key — marked the page as done forever: write thirty pages in the air, land, and
                // none of it ever enters the corpus because nothing about it changed afterwards.
                values["__sig.${zone.id}"] = zoneSig
                any = true
                read++
                values[zone.id] = text
                // Harvest #hashtags from THIS zone's text. Here the bitmap was rendered from the ink
                // BOUNDS (not the whole zone), so a normalized tag box maps back through `bounds`; the
                // zone rect is the fallback for any tag the model can't place.
                harvestTags(appCtx, date, pageKey, text, bmp, bounds, zone.rect, creds)
                val prompt = zone.aiPrompt
                if (prompt != null) {
                    val r = com.toolsboox.plugin.chat.nw.LedgerChatService()
                        .run(creds.first, creds.second, creds.third, prompt, text)
                    if (r is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok) values[zone.id + ".ai"] = r.answer
                }
            }
            // Signatures are written per zone as each is read, so an unreadable zone still counts
            // as "seen" and isn't retried on every page-leave. Save only if something moved.
            if (any) {
                com.toolsboox.plugin.calendar.ot.SectionStore.save(appCtx, pageKey, date, values)
            }
            // The Diag line (Settings › Diagnostics reads the Timber ring): what the page-leave
            // harvest cost, so a slow turn can be told from a slow read. Tagged "NotesHarvest" — the
            // ring keeps INFO only from its named rooms, and "harvest" is one of them
            // ([com.toolsboox.ot.DiagnosticsLog.KEPT_INFO_ROOMS]); untagged, this line was dropped
            // (review of dbe1a38d). PARITY: iOS has no such line yet — the same shape is its to add.
            // Only when a band was actually read — an unchanged page costs nothing to report.
            if (read > 0) timber.log.Timber.tag("NotesHarvest").i("Notes harvest · $date/$pageKey · read $read of ${zones.size} bands · ${
                android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /**
     * Record a zone's `#tags` at an (approximate) WORD rect instead of the whole zone. Runs only when
     * [text] actually contains a tag (the extra vision call is skipped — and free — otherwise), and
     * REUSES the already-rendered zone [bitmap] rather than re-rendering. The model returns each tag's
     * normalized box (0..1, top-left origin) relative to [bitmap]; [renderRect] is the design-space
     * rect that bitmap covers (the zone rect on manual capture, the ink bounds on auto-capture), so a
     * box maps to design space as left = renderRect.left + b.left*renderRect.width, etc. Any tag the
     * model doesn't box falls back to [zoneRect] — so this never lands worse than today's zone-level.
     * Android boxes are LLM-estimated (approximate); iOS records exact Apple Vision boxes; both feed
     * the same occurrence rect → focusOnRect jump.
     */
    private fun harvestTags(
        ctx: android.content.Context, date: java.time.LocalDate, pageKey: String,
        text: String, bitmap: android.graphics.Bitmap,
        renderRect: android.graphics.RectF, zoneRect: android.graphics.RectF,
        creds: Triple<String, String, String>
    ) {
        // [[WIKI LINKS]] COME OFF THE SAME PASS. This is the whole reason interlinking is cheap:
        // the expensive part — getting handwriting into text at all — has already been paid for
        // here, and a link is just a second token shape to look for in the result.
        //
        // Before the tag early-return, deliberately: a page can carry links and no tags, and
        // returning first would have made [[links]] work everywhere EXCEPT on handwriting, which
        // is the one place they most need to.
        com.toolsboox.plugin.calendar.ot.LedgerLinks.record(ctx, date, pageKey, text)

        val tags = com.toolsboox.plugin.calendar.ot.LedgerTags.extract(text)
        if (tags.isEmpty()) return
        val boxes = com.toolsboox.plugin.calendar.nw.VisionOcr
            .recognizeTagBoxes(bitmap, creds.first, creds.second, creds.third)
        for (tag in tags) {
            val nb = boxes[tag]
            val wordRect = if (nb != null) android.graphics.RectF(
                renderRect.left + nb.left * renderRect.width(),
                renderRect.top + nb.top * renderRect.height(),
                renderRect.left + nb.right * renderRect.width(),
                renderRect.top + nb.bottom * renderRect.height()
            ) else zoneRect
            com.toolsboox.plugin.calendar.ot.LedgerTags.recordTag(ctx, date, pageKey, tag, wordRect)
        }
    }

    // ---- Synthesize → Write AI pipeline (pickings / books) --------------------------------------

    /** Text of the current page: its typed boxes + OCR of its ink. The raw material for synthesis. */
    private fun pageOcrText(creds: Triple<String, String, String>): String? {
        val pk = notePage ?: "default"
        val typed = if (::calendarDay.isInitialized)
            calendarDay.textElements.filter { it.pageKey == pk }.joinToString("\n") { it.text }.trim() else ""
        val inked = pageInkText(creds).orEmpty()
        return listOf(typed, inked).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
    }

    /** OCR just the current page's handwriting, or null if there is none. */
    private fun pageInkText(creds: Triple<String, String, String>): String? {
        val strokes = currentPageStrokes()
        if (strokes.isEmpty()) return null
        val rect = android.graphics.RectF(0f, 0f, 1404f, 1872f)
        val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 2000)
        return com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(
            bmp, creds.first, creds.second, creds.third)?.takeIf { it.isNotBlank() }
    }

    /** Drop each line onto [pageKey] as a movable text box, save, and (optionally) show it now. */
    private fun placeTextBoxes(lines: List<String>, pageKey: String, refresh: Boolean) {
        if (!::calendarDay.isInitialized || lines.isEmpty()) return
        var y = 140f
        for (line in lines) {
            calendarDay.textElements.add(TextElement(
                x = 90f, y = y, width = 1220f, height = 100f, text = line.trim(), pageKey = pageKey))
            y += 120f
        }
        calendarPattern.updateDay(calendarDay)
        presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        if (refresh) setTextElements(calendarDay.textElements.filter { it.pageKey == pageKey }.toMutableList())
    }

    /**
     * Place synthesized questions as a GROUP: a compact source gram at the top (a real gram card, so
     * it carries the back link to where they came from and taps back), then the question text boxes
     * right under it. So a batch always shows what it came from and stays visually together.
     */
    private fun placeSourcedQuestions(
        lines: List<String>, pageKey: String, sourceLink: String, sourceLabel: String, refresh: Boolean
    ) {
        if (!::calendarDay.isInitialized || lines.isEmpty()) return
        var y = 140f
        if (sourceLink.isNotBlank()) runCatching {
            val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                "↩ Synthesized from\n$sourceLabel", null, null, 1100, 240)
            val baos = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            val w = 620f; val h = w * bmp.height / bmp.width
            calendarDay.imageElements.add(com.toolsboox.da.ImageElement(
                x = 90f, y = y, width = w, height = h, data = base64, page = pageKey,
                sourceLink = sourceLink, sourceLabel = sourceLabel))
            y += h + 30f
        }
        for (line in lines) {
            calendarDay.textElements.add(TextElement(
                x = 90f, y = y, width = 1220f, height = 100f, text = line.trim(), pageKey = pageKey))
            y += 120f
        }
        calendarPattern.updateDay(calendarDay)
        presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        if (refresh) {
            setTextElements(calendarDay.textElements.filter { it.pageKey == pageKey }.toMutableList())
            setImageElements(calendarDay.imageElements.filter { it.page == pageKey }.toMutableList())
        }
    }

    private fun runLlm(creds: Triple<String, String, String>, system: String, text: String): List<String> {
        val res = com.toolsboox.plugin.chat.nw.LedgerChatService().run(creds.first, creds.second, creds.third, system, text)
        return if (res is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)
            res.answer.lines().map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim() }.filter { it.isNotEmpty() }
        else emptyList()
    }

    /**
     * The raw material for synthesis: this page's text, or — when triggered from the empty
     * Synthesize grid — the typed text of every pickings page of the day, so Synthesize has
     * something to work from even before you've hand-gathered grams onto the grid.
     */
    private fun synthesisSource(creds: Triple<String, String, String>): String? {
        pageOcrText(creds)?.let { if (it.isNotBlank()) return it }
        if (!::calendarDay.isInitialized) return null
        val keys = com.toolsboox.plugin.calendar.ot.PickingsStore.list(requireContext(), currentDate)
            .map { it.key }.toSet()
        return calendarDay.textElements.filter { it.pageKey in keys }
            .joinToString("\n") { it.text }.trim().ifBlank { null }
    }

    // ── ✒ SYNTH — THE CORRESPONDENCE ────────────────────────────────────────────────────────
    //
    // The loop (DESIGN-synthesis-claude-2026-08-30): write → Synth → it reads the NEW ink →
    // it writes back ON the page → answer in ink → again. The page is the transcript. Canon
    // enforced here: ink is sacred (replies never overlay strokes — placed below the page's
    // content, flowing to the next sub-page when full); the machine writes in its OWN voice
    // (⟡ on a card face — Michael's ruling: "use pick gram aesthetics for the responses", so
    // a reply IS a first-class pick: movable, holdable, re-Synthable); the summon is explicit
    // (nothing watches, nothing auto-fires); the tool surface is read-only (a one-shot run —
    // if something should become a task, the reply says so and his hand does the minting).

    /** Chunk width the reply cards render at; QuoteCardRenderer auto-measures height. */
    private val SYNTH_CARD_RENDER_W = 1080

    /** Placed card width in design space — the pick width the boards use, a hair narrower
     *  than the text column so it reads as a CARD, not a wall. */
    private val SYNTH_CARD_PLACE_W = 1000f

    /**
     * THE PROMPT GRAMMAR (Michael, 08-30: "use grams as a synth prompt, or highlighted strokes,
     * or just regular strokes"): three summons, one loop.
     *  • no arguments — the delta: everything written since the last reply (the -60 default);
     *  • [selection] — lasso'd ink IS the prompt at full weight, the rest of the page rides as
     *    context, and the watermark advances only over what was read;
     *  • [subject] — a held gram is the subject, resolved to what the card STANDS FOR
     *    ([resolveCardMaterial]); the page rides as context and no ink is consumed.
     */
    @kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun synthCorrespond(
        selection: List<com.toolsboox.da.Stroke>? = null,
        subjects: List<com.toolsboox.da.ImageElement>? = null,
        /** Who answers: null = the PAGE's voice (every plain ❖ row, the rail, the page hold, the
         *  engines' door) — so the header is always true; "Synth with ▸" passes the voice it set. */
        voice: String? = null,
        /** What to say when there is nothing new to read — null = [SynthWords.NOTHING_NEW]. The rail
         *  hold's voice pick says who answers next instead: the pick did something. */
        nothingNew: String? = null,
    ) {
        val subject = subjects?.firstOrNull()
        // THE THREADED STICKY (Michael, 08-30: "Can I reply back on the sticky instead? That way
        // the full convo carries") — Synthing a single ⟡ reply card converses ON the card: the
        // reply APPENDS to the same element, one pick carrying the whole exchange. Breadcrumb
        // stickies (⟡ <page name>) keep resolving to their page — only the correspondence's own
        // cards thread: any voice's reply on its own conversation ([SynthVoice.isReply]).
        if (selection.isNullOrEmpty() && subjects?.size == 1 && subject != null &&
            com.toolsboox.plugin.calendar.ot.SynthVoice.isReply(subject.sourceLabel, subject.sourceLink, subject.page)) {
            threadedStickyTurn(subject, voice)
            return
        }
        // Circled ink AND circled cards (Synth with ▸) are ONE exhibit, ink first — a selection
        // turn: the watermark advances over the circled ink.
        val cardTurn = subject != null && selection.isNullOrEmpty()
        val pageKey = notePage ?: return
        if (!::calendarDay.isInitialized) return
        // ONE SUMMON PER PAGE AT A TIME (2026-10-12 review): the "read" watermark moves only once a
        // reply lands, so a second tap during the 35–75 s wait read the same ink again — the -322
        // log has three summons in ~80 s, and with landing fixed all three would now land.
        SynthSummons.stillWriting(currentDate, pageKey)?.let { showMessage(it, binding.root); return }
        val creds = aiCreds() ?: run { showMessage(SynthSummons.noKeyWords(), binding.root); return }
        val ctx = requireContext()

        // THE DELTA — Penombra's one real lesson. Stored strokes carry no reliable timestamps
        // (t=0 — the PanelOcr lesson), so the watermark is the sidecar's seen-id set. A lasso'd
        // selection overrides the delta (the circle IS the ask); a gram subject needs no ink.
        val pageStrokes = calendarDay.noteStrokes[pageKey].orEmpty()
        val prior = com.toolsboox.plugin.calendar.ot.SynthCorrespondence.row(ctx, currentDate, pageKey)
        val seen = prior?.seen ?: emptySet<String>()
        val delta = when {
            !selection.isNullOrEmpty() -> selection
            subject != null -> emptyList()
            else -> pageStrokes.filter { it.strokeId.toString() !in seen }
        }
        if (delta.isEmpty() && subject == null) {
            showMessage(nothingNew ?: com.toolsboox.plugin.calendar.ot.SynthWords.NOTHING_NEW, binding.root)
            return
        }

        showMessage(
            if (cardTurn) com.toolsboox.plugin.calendar.ot.SynthWords.READING_CARD
            else com.toolsboox.plugin.calendar.ot.SynthWords.READING_INK,
            binding.root)
        // Snapshot on Main (the dispatchPages rule): the OCR pass must not race a pen-up.
        val deltaCopy = delta.toList()
        // The watermark advances only over what was READ: the whole page on a delta turn, just
        // the circled ids on a selection turn, nothing on a card turn.
        val allIdsNow = when {
            !selection.isNullOrEmpty() -> selection.map { it.strokeId.toString() }
            subject != null -> emptyList()
            else -> pageStrokes.map { it.strokeId.toString() }
        }
        // WHO ANSWERS — resolved now, on this device (ruling 5: a voice that isn't here answers as
        // the Correspondent, and the words say so). The record carries it; a resume keeps it.
        val answering = if (voice != null)
            com.toolsboox.plugin.calendar.ot.SynthPageVoices.Voice(com.toolsboox.plugin.calendar.ot.SynthVoice.normalize(voice), "", emptyList())
            else synthVoiceOn(pageKey)
        val typed = calendarDay.textElements
            .filter { it.pageKey == pageKey && it.text.isNotBlank() }
            .joinToString("\n") { it.text.trim() }
        // The placed cards the correspondent can SEE (labels; §5a's full resolver is P4) —
        // its own prior replies excluded, they already ride the transcript.
        val cardLabels = calendarDay.imageElements
            .filter { it.page == pageKey && !it.sourceLabel.startsWith("⟡") && !it.sourceLabel.startsWith("❖") }
            .mapNotNull { el ->
                val label = el.sourceLabel.ifBlank { el.sourceLink }
                if (label.isBlank()) null else label
            }
        val transcript = com.toolsboox.plugin.calendar.ot.SynthCorrespondence
            .transcriptTail(ctx, currentDate, pageKey, turns = 10, voice = answering.persona)
        // A page made from a list carries its Sources card: the reply may cite [n] again, and a newly
        // cited one gets its card (synth-list-links §4, follow-ups).
        val pageSources = com.toolsboox.plugin.calendar.ui.SynthListRun.sourcesOn(calendarDay, pageKey)
        // The circle was TAKEN as the ask — dissolve it now, or the pill's next summon reads
        // this same stale ring instead of the new ink (the -67 field bug's second half).
        if (selection != null) clearLassoSelection()

        // DETACHED SCOPE ON PURPOSE (the harvest precedent; Michael, field, 08-31: "waiting
        // doesn't create a sticky") — a summon is a network round-trip, and the reply must land
        // even if the fragment pauses, the screen sleeps, or he pages away mid-wait.
        //
        // AND IT OUTLIVES THE PROCESS NOW (Michael, 2026-10-12, B6 -322: "It says reading. It says
        // writing back. It does not put in it's write back pic."). The field log: three summons,
        // each reply 35–75 s, the page rebuilt under every one of them, every reply sent down the
        // detached landing — which built a Moshi-less CalendarDayService and wrote nothing, said
        // nothing (see SynthPending's header). So: a PENDING RECORD is written before the read
        // (a "reading" stub) and again with the full request BEFORE the network call; the reply is
        // saved the moment it arrives; [SynthSummons.land] puts it on whichever day page stands on
        // that day (this one, or the one that replaced it), else in the day file with a service
        // that has its Moshi; the transcript turn is recorded only once the card stands; and a
        // summon the process didn't live to finish is re-asked or landed on the next foreground
        // ([SynthSummons.onForeground]). One `synth[<id>]` log trail per summon.
        val appCtx = ctx.applicationContext
        val summonDate = currentDate
        val mode = when {
            cardTurn -> SynthSummons.MODE_CARD
            !selection.isNullOrEmpty() -> SynthSummons.MODE_SELECTION
            else -> SynthSummons.MODE_DELTA
        }
        val rec = SynthSummons.newRecord(summonDate, pageKey, mode, synthPageName(pageKey), allIdsNow,
            answering.persona, answering.missing)
        SynthSummons.begin(rec)
        SynthSummons.log(rec, "start page=$pageKey date=$summonDate mode=$mode ${SynthSummons.voiceTag(rec.persona)} delta=${deltaCopy.size} " +
            "transcriptChars=${transcript.length} typed=${typed.length} cards=${cardLabels.size}")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.ot.SynthPending.put(appCtx, rec) }
                val inkText = withContext(Dispatchers.IO) {
                    // The card IS the material (§5a's ladder, in miniature until P4) — a lasso'd
                    // SET resolves card by card, joined as one exhibit (§5 multi selection).
                    val cards = subjects.orEmpty().joinToString("\n\n---\n\n") {
                        runCatching { resolveCardMaterial(it, creds) }.getOrDefault(it.cardText)
                    }.trim()
                    if (cardTurn) cards else {
                        val viaVision = runCatching {
                            val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(deltaCopy)
                            val pad = 28f
                            val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                            val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(deltaCopy, rect, 1600)
                            com.toolsboox.plugin.calendar.nw.VisionOcr
                                .recognize(bmp, creds.first, creds.second, creds.third)?.trim()
                        }.getOrNull()
                        val ink = viaVision
                            // ❖ The on-device rung: the firmware's own MyScript, no network — the
                            // recognizer his Notes app already trains on his hand daily. Null on
                            // Bigme/Viwoods or any refusal; the ladder falls through unchanged.
                            ?: com.toolsboox.ot.OnyxHwr.recognize(appCtx, deltaCopy)
                            ?: com.toolsboox.plugin.calendar.ot.PanelOcr.recognize(deltaCopy).trim()
                        listOf(ink, cards).filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
                    }
                }
                // Audit line for the field ("the responses don't make much sense"): what actually
                // reaches the model — delta size, transcript depth, material length.
                SynthSummons.log(rec, "read materialChars=${inkText.length}")
                if (inkText.isBlank()) {
                    withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.ot.SynthPending.remove(appCtx, rec.id) }
                    SynthSummons.say(appCtx, rec,
                        if (cardTurn) com.toolsboox.plugin.calendar.ot.SynthWords.CARD_UNREADABLE else com.toolsboox.plugin.calendar.ot.SynthWords.INK_UNREADABLE,
                        this@CalendarDayFragment)
                    return@launch
                }

                SynthSummons.progress(com.toolsboox.plugin.calendar.ot.SynthWords.writingBack(rec.persona), this@CalendarDayFragment)
                val content = buildString {
                    if (transcript.isNotBlank())
                        append("The correspondence so far, oldest first:\n").append(transcript).append("\n\n")
                    if (typed.isNotBlank()) append("Typed on the page:\n").append(typed).append("\n\n")
                    if (cardLabels.isNotEmpty())
                        append("Cards placed on the page: ").append(cardLabels.joinToString(" · ")).append("\n\n")
                    when {
                        cardTurn -> append("He put this card forward — THIS is the turn you are answering; respond to what it holds:\n").append(inkText)
                        !selection.isNullOrEmpty() -> append("He circled this ink — the circle is the ask, THIS is the turn you are answering:\n").append(inkText)
                        else -> append("He just wrote this in ink — THIS is the turn you are answering:\n").append(inkText)
                    }
                    if (pageSources.isNotBlank())
                        append("\n\n").append(com.toolsboox.plugin.calendar.ot.SynthSources.followUpBlock(pageSources))
                }
                SynthSummons.askAndLand(appCtx, creds,
                    rec.copy(content = content,
                        recordText = if (cardTurn) "[card] " + inkText.take(280) else inkText,
                        tail = transcript, tailTurns = if (transcript.isBlank()) 0 else 10,
                        sources = pageSources),
                    this@CalendarDayFragment)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // NEVER silent nothing (his ruling): whatever broke, it says so.
                SynthSummons.warn(rec, "summon threw", t)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { com.toolsboox.plugin.calendar.ot.SynthPending.remove(appCtx, rec.id) }
                }
                SynthSummons.say(appCtx, rec, com.toolsboox.plugin.calendar.ot.SynthWords.NO_REPLY, this@CalendarDayFragment)
            } finally {
                SynthSummons.end(rec)
            }
        }
    }

    // ── ❖ THE VOICES (Michael, 2026-10-12: Synth with a persona) ────────────────────────────

    /** On the conversation page — the daily Synthesize page or a topic, ANY sub-page ("synthesize#1"
     *  is page 2 of the daily page; the bare key test sent it to the drive-by). */
    private fun onSynthPage(): Boolean =
        com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(baseNotePage(notePage))

    /** Who answers on [pageKey] (any sub-page) of this day, on this device. */
    private fun synthVoiceOn(pageKey: String): com.toolsboox.plugin.calendar.ot.SynthPageVoices.Voice =
        runCatching {
            com.toolsboox.plugin.calendar.ot.SynthPageVoices.voiceFor(requireContext(), currentDate.toString(), pageKey)
        }.getOrDefault(com.toolsboox.plugin.calendar.ot.SynthPageVoices.Voice(
            com.toolsboox.plugin.calendar.ot.SynthVoice.CORRESPONDENT, "", emptyList()))

    /** The standing synth page now answers in [voice] — set at once, before any network call, so a
     *  failed summon retries in the chosen voice. A first choice on a page that already holds a
     *  reply card remembers the Correspondent wrote it. The header redraws on the next render. */
    private fun setSynthPageVoice(voice: String) {
        val page = notePage ?: return
        val base = page.substringBefore('#')
        val hasReply = ::calendarDay.isInitialized && calendarDay.imageElements.any {
            (it.page == base || it.page.startsWith("$base#")) &&
                com.toolsboox.plugin.calendar.ot.SynthVoice.isReply(it.sourceLabel, it.sourceLink, it.page)
        }
        runCatching {
            com.toolsboox.plugin.calendar.ot.SynthPageVoices.set(requireContext(), currentDate.toString(), base, voice, hasReply)
        }.onFailure { Timber.w(it, "synth voice: set failed") }
        rerenderStandingPage()
    }

    /** The page whose voices were last round-tripped on opening, and when — a redraw of the same
     *  page is not an opening (every render would otherwise be a network round trip). */
    private var synthVoicesSyncedFor: String? = null
    private var synthVoicesSyncedAt = 0L

    private fun syncSynthVoicesOnOpen() {
        val ctx = context?.applicationContext ?: return
        val id = "$currentDate|${notePage?.substringBefore('#')}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (id == synthVoicesSyncedFor && now - synthVoicesSyncedAt < 60_000L) return
        synthVoicesSyncedFor = id; synthVoicesSyncedAt = now
        // Both halves of what the header says: the page's voice, and whether that voice is still on
        // this device (a persona deleted on the iPad reads "isn't on this device" on opening).
        com.toolsboox.plugin.chat.nw.PersonaStore.sync(ctx) { redrawSynthHeader() }
        com.toolsboox.plugin.calendar.ot.SynthPageVoices.sync(ctx) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (isAdded && "$currentDate|${notePage?.substringBefore('#')}" == id) rerenderStandingPage()
            }
        }
    }

    /** Redraw the page standing now (its template carries the voice header). */
    private fun rerenderStandingPage() {
        if (!isAdded || view == null || !isResumed || !::calendarDay.isInitialized || !::calendarPattern.isInitialized) return
        runCatching { renderPage(calendarDay, calendarPattern, lastRenderedEvents) }
            .onFailure { Timber.w(it, "synth voice: re-render failed") }
    }

    /**
     * THE VOICE LIST — "Synth with…" (the rows, the rail hold) or "Who answers next?" (the header):
     * the Correspondent, then his live personas; the page's current voice wears ✓ (on a Synthesize
     * page only). Pen-guarded like every menu. Opening it round-trips the persona set.
     */
    private fun showSynthVoiceList(title: String, onPick: (String) -> Unit) {
        val ctx = context ?: return
        com.toolsboox.plugin.chat.nw.PersonaStore.sync(ctx) { redrawSynthHeader() }
        val current = if (onSynthPage()) synthVoiceOn(notePage ?: "").persona else null
        val rows = com.toolsboox.plugin.calendar.ot.SynthVoice.voiceRows(
            com.toolsboox.plugin.chat.nw.PersonaStore.all(ctx), current)
        showIconMenu(title, rows.map { row -> row.text to { onPick(row.persona) } })
    }

    /** The rail ❖'s hold: the voice list; a live circle rides along. On the conversation page the
     *  page takes the voice and answers the circle (else the delta) in place — with no new ink the
     *  pick still changed who answers, and says so; anywhere else it is the drive-by in that voice —
     *  the circle, or with none, THE PAGE as the material ([driveBySynthPage]). */
    private fun synthWithFromRail() {
        showSynthVoiceList(SYNTH_WITH_TITLE) { voice ->
            val sel = currentLassoSelection()
            if (onSynthPage()) {
                setSynthPageVoice(voice)
                if (sel.isNotEmpty()) synthCorrespond(selection = sel, voice = voice)
                else synthCorrespond(voice = voice,
                    nothingNew = com.toolsboox.plugin.calendar.ot.SynthWords.answersNext(voice))
            } else if (sel.isNotEmpty()) driveBySynthInk(sel, voice = voice)
            else driveBySynthPage(voice)
        }
    }

    /**
     * The rail hold with no circle, off the conversation page: THE PAGE is the material — its typed
     * text and picks ([thisPageMaterial]), its handwriting read as words, and the handwriting baked
     * into its picks — the rail tap's own "page as the selection" (Michael, 09-13) and iOS's
     * pageMaterialWithInk(.page). The words-only material said "write something first" on a page
     * full of ink, and a page of ink and picks lost what he wrote (review of b29cabbc). Only a page
     * with nothing on it says so.
     */
    private fun driveBySynthPage(voice: String) {
        if (!::calendarDay.isInitialized) return
        val creds = aiCreds() ?: run { showMessage(SynthSummons.noKeyWords(), binding.root); return }
        showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.READING_INK, binding.root)
        val words = thisPageMaterial()
        lifecycleScope.launch {
            // The engines' read ([runEngine]): the page's ink, then the picks' ink, off the main thread.
            val material = withContext(Dispatchers.IO) {
                val ink = runCatching { pageInkText(creds) }.getOrNull().orEmpty().trim()
                val picksInk = runCatching { picksInkText(creds) }.getOrNull().orEmpty().trim()
                (words +
                    (if (ink.isBlank()) "" else "\n• [ink] " + ink.take(2500)) +
                    (if (picksInk.isBlank()) "" else "\n• [pick ink — the handwriting on the picks] " + picksInk.take(2500))).trim()
            }
            if (!isAdded) return@launch
            if (material.isBlank()) showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.NOTHING_NEW, binding.root)
            else driveBySynthCore(material, "the page from $currentDate", null, voice)
        }
    }

    /** The page's name as he knows it — "Synthesize", or a topic page's own title — for the ❖ words. */
    private fun synthPageName(pageKey: String): String {
        val base = pageKey.substringBefore('#')
        val title = if (base.startsWith("synthesize-"))
            runCatching {
                com.toolsboox.plugin.calendar.ot.SynthPageStore.list(requireContext()).firstOrNull { it.key == base }?.name
            }.getOrNull()
        else null
        return com.toolsboox.plugin.calendar.ot.SynthWords.pageName(base, title)
    }

    // ── The ❖ landing, from this page's side ([SynthSummons.land] drives it) ─────────────────

    /** This page can take a ❖ card into its in-memory day NOW: standing, on [date], its data loaded
     *  and no resume reload in flight (a card added to the pre-reload snapshot is overwritten by
     *  the reload — [isPageDataReady]'s rule for every insert), and not read-only. */
    internal fun canTakeSynthLanding(date: java.time.LocalDate): Boolean =
        isAdded && view != null && currentDate == date && isPageDataReady() && !dayReadOnly

    /** Standing on [date] with its resume reload still in flight — worth a short wait. */
    internal fun isSynthHostLoading(date: java.time.LocalDate): Boolean =
        isAdded && view != null && isResumed && currentDate == date &&
            (resumeReloadPending || !::calendarDay.isInitialized)

    /** On screen to be heard: a paused page's snackbar times out unseen, so a summon that finishes
     *  while the app is away owes its words instead ([SynthSummons.say]). */
    internal fun canSpeak(): Boolean = isAdded && view != null && isResumed && ::binding.isInitialized

    internal fun sayOnPage(words: String) {
        runCatching { showMessage(words, binding.root) }
    }

    internal fun viewingPage(): String? = notePage

    /**
     * Land a reply's faces on this page's day — [com.toolsboox.plugin.calendar.ot.SynthLanding.place]
     * (below everything, flowing to the next sub-page, never over ink) — and DRAW THEM NOW when it
     * is the page on screen. Null when this page can't take it (the caller falls to the day file).
     * In memory only: the caller awaits [saveSynthLanding] before it clears the summon's record.
     */
    internal fun landSynthLive(
        rec: com.toolsboox.plugin.calendar.ot.PendingSummon,
        faces: List<com.toolsboox.plugin.calendar.ot.SynthFace>,
        resumed: Boolean = false,
    ): com.toolsboox.plugin.calendar.ot.SynthLanded? {
        val date = runCatching { java.time.LocalDate.parse(rec.date) }.getOrNull() ?: return null
        if (!canTakeSynthLanding(date)) return null
        // "Synth picks can look like stickies" (Michael, 08-30) — the correspondent leaves
        // STICKIES in the book: folded-corner face, typed sans, unmistakably not his picks. The
        // sourceLink is the settled design's scheme — a tap on a travelled reply card navigates
        // back to the turn's page. (-60 stamped synth://; those cards keep working.)
        // A RESUMED summon whose first landing beat the kill is already standing → the same answer.
        // The card wears the record's voice — "❖ Spiral" (ruling 2); a resumed Spiral reply is found as Spiral.
        val label = com.toolsboox.plugin.calendar.ot.SynthVoice.label(rec.persona)
        if (resumed) com.toolsboox.plugin.calendar.ot.SynthLanding.alreadyLanded(calendarDay, rec.pageKey, faces, label)?.let { return it }
        val landed = com.toolsboox.plugin.calendar.ot.SynthLanding.place(
            calendarDay, rec.pageKey, faces, "ledger://${rec.date}/${rec.pageKey}", label)
        // From here the card is in the day: nothing below may throw it into "couldn't be written".
        runCatching { calendarPattern.updateDay(calendarDay) }.onFailure { SynthSummons.warn(rec, "pattern update after landing failed", it) }
        val here = notePage
        if (here != null && (here == landed.firstKey || here == landed.lastKey || here == rec.pageKey)) {
            // The sticky must APPEAR now, not after a reload: the composite every element mutation
            // repaints with (images under, strokes over — on the B6 the forced daemon-overlay sync),
            // then the next-frame full refresh the image insert uses, because "an e-ink partial
            // update doesn't always show it" (SurfaceFragment.insertImageFromUri). The card is in
            // the day already — a repaint that throws must not turn into "couldn't be written".
            runCatching {
                setImageElements(calendarDay.imageElements.filter { it.page == here }.toMutableList())
                redrawSurface()
                provideSurfaceView().post { if (isAdded) forceFullEpdRefresh() }
            }.onFailure { SynthSummons.warn(rec, "repaint after landing failed", it) }
        }
        return landed
    }

    /**
     * The landed card ON DISK before the summon's record goes (2026-10-12 review): the page's own
     * save is fire-and-forget, so a kill or a reboot (the B6, 13:30:50) between it and the record's
     * removal lost the card AND marked the ink read, with nothing left to resume. Awaited, same
     * guards and locks; the root and the read-only flag are taken now, on Main, so a page turn
     * during the write can't strand it. False = not on disk (the record stays; see the caller).
     * Throws [com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException] when the
     * day file stopped reading under the page — the page is read-only from then, and the caller
     * says the reply is kept (2026-10-16 review, item 3).
     */
    internal suspend fun saveSynthLanding(): Boolean {
        if (!::calendarDay.isInitialized) return false
        return presenter.saveNow(this, calendarDay, calendarPattern, currentDate)
    }

    /**
     * Bring the FIRST reply card into view and say where it went (Michael, 2026-10-12): on this
     * page → pan to it (a landscape page is scrolled; the card can sit under the fold); flowed onto
     * the next sub-page of the page he summoned from → go there and pan to it; he's elsewhere →
     * the words name the page and nothing moves under his hand.
     */
    internal fun revealSynthLanding(
        rec: com.toolsboox.plugin.calendar.ot.PendingSummon,
        landed: com.toolsboox.plugin.calendar.ot.SynthLanded,
        words: String,
    ) {
        if (!canSpeak()) return
        val here = notePage
        val rect = android.graphics.RectF(landed.x, landed.y, landed.x + landed.w, landed.y + landed.h)
        when {
            here == landed.firstKey -> {
                sayOnPage(words)
                provideSurfaceView().post {
                    if (isAdded) {
                        val moved = revealCanvasRect(rect)
                        SynthSummons.log(rec, "in view page=$here y=%.0f moved=$moved".format(rect.top))
                    }
                }
            }
            here != null && here == rec.pageKey &&
                landed.firstKey.substringBefore('#') == here.substringBefore('#') -> {
                SynthSummons.log(rec, "taking him to page=${landed.firstKey} y=%.0f".format(rect.top))
                val reveal = CalendarNavigator.Reveal(currentDate, landed.firstKey, rect, words)
                CalendarNavigator.pendingReveal = reveal
                try {
                    CalendarNavigator.toDayNote(this, currentDate, landed.firstKey)
                } catch (t: Throwable) {
                    // Not left armed: it would pan and speak the next time that sub-page opens,
                    // hours later. The caller says the words where he is instead.
                    if (CalendarNavigator.pendingReveal === reveal) CalendarNavigator.pendingReveal = null
                    throw t
                }
            }
            else -> {
                // He is on another page of the day: the words name where it went, and the card is
                // panned into view the next time that page opens (silently — the words were said).
                sayOnPage(words)
                SynthSummons.armReveal(rec, landed)
            }
        }
    }

    /**
     * What a held card STANDS FOR — §5a's resolver ladder in miniature (P4 builds the full run):
     * a letter resolves to its kept body, a ledger/synth link to that page's cached transcript,
     * anything else to its own words (cardText → label+excerpt → vision OCR of the face).
     * "A pick with no richer source resolves here, and that is enough: the pick IS the material."
     * Blocking — call from Dispatchers.IO.
     */
    private fun resolveCardMaterial(
        el: com.toolsboox.da.ImageElement,
        creds: Triple<String, String, String>,
    ): String {
        val ctx = requireContext()
        val link = el.sourceLink
        // mail:// → the kept body (the keep pile holds every starred/replied letter whole).
        if (link.startsWith("mail://")) {
            val id = link.removePrefix("mail://")
            val m = com.toolsboox.plugin.mail.InboxStore.messages(ctx).firstOrNull { it.id == id }
            if (m != null) {
                val whole = com.toolsboox.plugin.mail.InboxStore.hydrate(ctx, m)
                val body = whole.body.ifBlank { whole.snippet }
                if (body.isNotBlank())
                    return "A letter from ${whole.fromName.ifBlank { whole.fromEmail }} — ${whole.subject}:\n" +
                        body.take(4000)
            }
        }
        // ledger:// (and -60's legacy synth://) → that page's cached correspondence transcript.
        val pageRef = when {
            link.startsWith("ledger://") -> link.removePrefix("ledger://")
            link.startsWith("synth://") -> link.removePrefix("synth://")
            else -> null
        }
        if (pageRef != null) {
            val slash = pageRef.indexOf('/')
            if (slash > 0) {
                val date = runCatching { java.time.LocalDate.parse(pageRef.substring(0, slash)) }.getOrNull()
                val key = pageRef.substring(slash + 1)
                if (date != null && key.isNotBlank()) {
                    val tail = com.toolsboox.plugin.calendar.ot.SynthCorrespondence
                        .transcriptTail(ctx, date, key, turns = 6)
                    val own = el.cardText.trim()
                    if (tail.isNotBlank() || own.isNotBlank())
                        return listOf(own, tail).filter { it.isNotBlank() }.joinToString("\n\n")
                }
            }
        }
        // The card's own words — a quote face, a reply card, a link card's excerpt.
        if (el.cardText.isNotBlank()) return el.cardText.trim()
        val labelled = listOf(el.sourceLabel, el.sourceLink).filter { it.isNotBlank() }.joinToString(" · ")
        // Last rung: read the face itself (it may BE ink).
        val fromFace = runCatching {
            val bytes = android.util.Base64.decode(el.data, android.util.Base64.NO_WRAP)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@runCatching null
            com.toolsboox.plugin.calendar.nw.VisionOcr
                .recognize(bmp, creds.first, creds.second, creds.third)?.trim()
                .also { bmp.recycle() }
        }.getOrNull()
        if (!fromFace.isNullOrBlank())
            return if (labelled.isBlank()) fromFace else "$labelled\n$fromFace"
        return labelled
    }

    /**
     * One turn ON the sticky: the reader wrote on the card (✍ Write on it bakes ink into the
     * face) and Synthed it — OCR the face, isolate his words (the face's text minus the lines
     * the transcript already holds), reply, APPEND both turns into cardText, and re-render the
     * face as the thread (✎ his turns italic, ⟡ the machine's; past the cap the face windows
     * the newest turns while cardText keeps everything — the pick travels whole, and a re-Synth
     * anywhere hands the correspondent its complete history). Same element, same id, same
     * provenance: the conversation IS the pick.
     */
    @kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun threadedStickyTurn(el: com.toolsboox.da.ImageElement, voice: String? = null) {
        if (!::calendarDay.isInitialized) return
        val pageKey = el.page
        SynthSummons.stillWriting(currentDate, pageKey)?.let { showMessage(it, binding.root); return }
        val creds = aiCreds() ?: run { showMessage(SynthSummons.noKeyWords(), binding.root); return }
        val ctx = requireContext().applicationContext
        showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.READING_STICKY, binding.root)
        val summonDate = currentDate
        // Writing on a card of a page made from a list (the B6's natural follow-up) keeps the page's
        // numbers, as the ink and selection summons do: the SOURCES block rides the ask, the reply
        // is cleaned against it, and a newly cited [n] gets its card (review 2026-10-12).
        val pageSources = com.toolsboox.plugin.calendar.ui.SynthListRun.sourcesOn(calendarDay, pageKey)
        // Detached on purpose (same law as the main summon): the reply lands even if he waits
        // the fragment away — and, since Michael's 2026-10-12 B6 report, even if the process dies:
        // the same pending record, the same 120 s bound (this path had none), the same landing
        // ladder ([SynthSummons.askAndLand]), the same `synth[<id>]` log trail.
        // The page's voice answers (null) — or the one "Synth with ▸" just set.
        val answering = if (voice != null)
            com.toolsboox.plugin.calendar.ot.SynthPageVoices.Voice(com.toolsboox.plugin.calendar.ot.SynthVoice.normalize(voice), "", emptyList())
            else synthVoiceOn(pageKey)
        val rec = SynthSummons.newRecord(summonDate, pageKey, SynthSummons.MODE_STICKY, synthPageName(pageKey), emptyList(),
            answering.persona, answering.missing)
        SynthSummons.begin(rec)
        SynthSummons.log(rec, "start page=$pageKey date=$summonDate mode=${SynthSummons.MODE_STICKY} ${SynthSummons.voiceTag(rec.persona)}")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.ot.SynthPending.put(ctx, rec) }
                val result = withContext(Dispatchers.IO) {
                    val bytes = runCatching {
                        android.util.Base64.decode(el.data, android.util.Base64.NO_WRAP)
                    }.getOrNull() ?: return@withContext null
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@withContext null
                    val faceText = com.toolsboox.plugin.calendar.nw.VisionOcr
                        .recognize(bmp, creds.first, creds.second, creds.third)?.trim().orEmpty()
                    bmp.recycle()
                    // His words = the face's lines the transcript doesn't already hold (the printed
                    // thread re-reads under OCR; subtraction leaves the handwriting).
                    fun norm(l: String) = l.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val known = el.cardText.lines().map { norm(it) }.filter { it.length > 6 }.toSet()
                    val readerTurn = faceText.lines()
                        .map { it.trim() }.filter { it.isNotBlank() }
                        .filterNot { line -> norm(line).let { n -> n.length > 6 && known.any { k -> k.contains(n) || n.contains(k) } } }
                        .joinToString("\n").trim()
                    readerTurn to faceText
                }
                SynthSummons.log(rec, "read faceChars=${result?.second?.length ?: -1} turnChars=${result?.first?.length ?: -1}")
                if (result == null || (result.first.isBlank() && result.second.isBlank())) {
                    withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.ot.SynthPending.remove(ctx, rec.id) }
                    SynthSummons.say(ctx, rec,
                        if (result == null) com.toolsboox.plugin.calendar.ot.SynthWords.STICKY_UNREADABLE else com.toolsboox.plugin.calendar.ot.SynthWords.NOTHING_NEW_ON_STICKY,
                        this@CalendarDayFragment)
                    return@launch
                }
                val (readerTurn, faceText) = result

                SynthSummons.progress(com.toolsboox.plugin.calendar.ot.SynthWords.writingBack(rec.persona), this@CalendarDayFragment)
                // THE PAGE IS THE STATE (Michael, 08-30: "It's a note page really but with your
                // responses as text and mine as strokes"): the correspondent answers from the
                // PAGE's transcript — the card is one voice in it, not a private thread.
                val tail = withContext(Dispatchers.IO) {
                    com.toolsboox.plugin.calendar.ot.SynthCorrespondence
                        .transcriptTail(ctx, summonDate, pageKey, turns = 6, voice = rec.persona)
                }
                val content = buildString {
                    if (tail.isNotBlank()) append("Earlier on this page:\n").append(tail).append("\n\n")
                    if (el.cardText.isNotBlank()) append("The card he wrote on says:\n").append(el.cardText).append("\n\n")
                    append("He wrote on the card, by hand:\n")
                    append(readerTurn.ifBlank { faceText })
                    append("\n\nReply in the correspondence — short, sized for a card.")
                    if (pageSources.isNotBlank())
                        append("\n\n").append(com.toolsboox.plugin.calendar.ot.SynthSources.followUpBlock(pageSources))
                }
                // The reply is a fresh typed card ON THE PAGE, below everything — the same element
                // grammar every summon uses. The card he inked keeps his handwriting exactly where
                // he put it (his turns are strokes/ink; the machine's are typed — the page carries
                // both voices). No face mutation, no bespoke thread state: the -66 accumulating
                // face is retired; the correspondence lives in the page + its transcript sidecar.
                SynthSummons.askAndLand(ctx, creds,
                    rec.copy(content = content, recordText = "[on the card] " + readerTurn.take(280),
                        tail = tail, tailTurns = if (tail.isBlank()) 0 else 6,
                        sources = pageSources),
                    this@CalendarDayFragment)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                SynthSummons.warn(rec, "sticky turn threw", t)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { com.toolsboox.plugin.calendar.ot.SynthPending.remove(ctx, rec.id) }
                }
                SynthSummons.say(ctx, rec, com.toolsboox.plugin.calendar.ot.SynthWords.NO_REPLY, this@CalendarDayFragment)
            } finally {
                SynthSummons.end(rec)
            }
        }
    }

    /**
     * THE DRIVE-BY (Michael's settled two-mode model, 08-31): on ANY page that is not the
     * conversation surface, lasso → ❖ Synth is always the same act — the circled material
     * becomes a NAMED note page (minted now), the correspondent's first reply lands THERE,
     * and a ❖ sticky carrying that reply lands back on the page you circled. Tap the sticky
     * = open the conversation; a rhizome edge ties the two pages both ways. Never "sometimes
     * a new note, sometimes not": the conversation page converses in place, every other page
     * drive-bys. You STAY on your page — the reply comes to you.
     */
    private fun driveBySynthInk(
        strokes: List<com.toolsboox.da.Stroke>,
        /** The voice the new conversation is born in ("" = the Correspondent, every plain row). */
        voice: String = com.toolsboox.plugin.calendar.ot.SynthVoice.CORRESPONDENT,
        /** Cards the same circle caught (Synth with ▸) — one exhibit with the ink, ink first. */
        cards: List<com.toolsboox.da.ImageElement> = emptyList(),
    ) {
        if (strokes.isEmpty() || !::calendarDay.isInitialized) return
        val creds = aiCreds() ?: run { showMessage(SynthSummons.noKeyWords(), binding.root); return }
        showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.READING_INK, binding.root)
        val copy = strokes.toList()
        val cardsCopy = cards.toList()
        val near = runCatching {
            val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(copy)
            android.graphics.RectF(b.left, b.top, b.right, b.bottom)
        }.getOrNull()
        clearLassoSelection()
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val viaVision = runCatching {
                    val b = com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(copy)
                    val pad = 28f
                    val rect = android.graphics.RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                    val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(copy, rect, 1600)
                    com.toolsboox.plugin.calendar.nw.VisionOcr
                        .recognize(bmp, creds.first, creds.second, creds.third)?.trim()
                }.getOrNull()
                val ink = viaVision
                    ?: com.toolsboox.ot.OnyxHwr.recognize(requireContext().applicationContext, copy)
                    ?: com.toolsboox.plugin.calendar.ot.PanelOcr.recognize(copy).trim()
                val held = cardsCopy.joinToString("\n\n---\n\n") {
                    runCatching { resolveCardMaterial(it, creds) }.getOrDefault(it.cardText)
                }.trim()
                listOf(ink, held).filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
            }
            if (!isAdded) return@launch
            if (text.isBlank()) { showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.INK_UNREADABLE, binding.root); return@launch }
            driveBySynthCore(text, "circled on $currentDate", near, voice)
        }
    }

    /** The drive-by with CARDS as the material — the lasso captures grams too, and the batch
     *  verb keeps one behavior everywhere off the conversation page. [voice] = Synth with ▸'s
     *  pick (null = a plain row: the Correspondent, and the conversation keeps its own voice). */
    private fun driveBySynthCards(elements: List<com.toolsboox.da.ImageElement>, voice: String? = null) {
        if (elements.isEmpty() || !::calendarDay.isInitialized) return
        // DEDUPE (Michael, 08-31: no nesting of note responses) — a card that already fronts
        // a conversation page (its provenance IS the page) is never re-minted: Synthing it
        // again opens the page it stands for, where the correspondence continues in place.
        if (elements.size == 1) {
            val link = elements[0].sourceLink
            val ref = when {
                link.startsWith("ledger://") -> link.removePrefix("ledger://")
                link.startsWith("synth://") -> link.removePrefix("synth://")
                else -> null
            }
            val slash = ref?.indexOf('/') ?: -1
            if (ref != null && slash > 0) {
                val date = runCatching { java.time.LocalDate.parse(ref.substring(0, slash)) }.getOrNull()
                val key = ref.substring(slash + 1)
                if (date != null && com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(key.substringBefore('#'))) {
                    // A chosen voice is set there first, so the page it opens on answers in it.
                    if (voice != null) runCatching {
                        com.toolsboox.plugin.calendar.ot.SynthPageVoices.set(requireContext(), date.toString(),
                            key.substringBefore('#'), voice, hasReply = true)
                    }
                    showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.conversationContinues(voice.orEmpty()), binding.root)
                    CalendarNavigator.toDayNote(this, date, key.substringBefore('#'))
                    return
                }
            }
        }
        val creds = aiCreds() ?: run { showMessage(SynthSummons.noKeyWords(), binding.root); return }
        showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.READING_CARDS, binding.root)
        val near = elements.firstOrNull()?.let {
            android.graphics.RectF(it.x, it.y, it.x + it.width, it.y + it.height)
        }
        clearLassoSelection()
        lifecycleScope.launch {
            val material = withContext(Dispatchers.IO) {
                elements.joinToString("\n\n---\n\n") {
                    runCatching { resolveCardMaterial(it, creds) }.getOrDefault(it.cardText)
                }.trim()
            }
            if (!isAdded) return@launch
            if (material.isBlank()) { showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.CARDS_UNREADABLE, binding.root); return@launch }
            driveBySynthCore(material, "cards from $currentDate", near, voice.orEmpty())
        }
    }

    /**
     * The drive-by's landing: mint the named page, seat the material there as the opening
     * quote card, fire the correspondent's first reply onto it, tie the rhizome edge, and
     * leave the reply-bearing ❖ sticky on the origin. Detached-scope from here down — the
     * reply must land even if he pages away while it's in flight (never silent nothing).
     *
     * THE DAY-FILE GUARD, at both ends (2026-10-16 review, item 2; iOS refuses before its ask too).
     * A drive-by keeps no record to land later, so a page that won't read is refused BEFORE the
     * ask — nothing asked, nothing minted ([com.toolsboox.plugin.calendar.ot.SynthWords.driveByRefused]).
     * And the day is WRITTEN FIRST: the new page's index row, the rhizome edge, the transcript turn
     * and the voice row follow only once the cards are on disk, so a page that stops reading during
     * the ask leaves no empty page and no orphan link behind (they used to be made before the write
     * that then refused).
     */
    @kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun driveBySynthCore(
        material: String, sourceLine: String, near: android.graphics.RectF?,
        /** The voice the conversation is born in — its first reply, its cards, its page's row. */
        voice: String = com.toolsboox.plugin.calendar.ot.SynthVoice.CORRESPONDENT,
    ) {
        // The day face is a page too: its elements live under "default" (LedgerUri.page's key for
        // the day page, and thisPageMaterial's) — a bare `notePage ?: return` made every drive-by
        // from it a silent nothing (review of b29cabbc), the new Synth with ▸ seats included.
        val originPage = notePage ?: "default"
        val originDate = currentDate
        val creds = aiCreds() ?: run { showMessage(SynthSummons.noKeyWords(), binding.root); return }
        val appCtx = requireContext().applicationContext
        val persona = com.toolsboox.plugin.calendar.ot.SynthVoice.normalize(voice)
        // This page opened on a file that wouldn't read: refused before anything is asked.
        if (dayReadOnly) {
            Timber.w("synth drive-by refused before the ask: the $originDate page is read-only (its file won't read)")
            showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.driveByRefused(
                com.toolsboox.plugin.calendar.fi.CalendarDayService.pageName(originDate)), binding.root)
            return
        }

        showMessage(com.toolsboox.plugin.calendar.ot.SynthWords.writingBack(persona), binding.root)
        // The drive-by rides the same ask (a real 120 s wall-clock bound — the old
        // withTimeoutOrNull-around-withContext could not return before the blocking call did) and
        // the same `synth[<id>]` trail as the page summon (Michael, 2026-10-12). It is NOT resumed
        // after a kill — its landing mints a page — so its record is the log's handle only.
        val rec = SynthSummons.newRecord(originDate, originPage, SynthSummons.MODE_DRIVEBY,
            if (originPage == "default") "the day page"
            else com.toolsboox.plugin.calendar.ot.SynthWords.pageName(originPage.substringBefore('#'), null), emptyList(), persona)
            .copy(content = "He circled this on another page and sent it over — open the correspondence; " +
                "THIS is the turn you are answering:\n" + material, attempts = 1)
        SynthSummons.log(rec, "start drive-by from page=$originPage date=$originDate ${SynthSummons.voiceTag(persona)} materialChars=${material.length}")
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            // ...and the file itself, read now: it may have stopped reading since this page loaded.
            val unreadable = withContext(Dispatchers.IO) {
                runCatching {
                    com.toolsboox.ui.share.ShareActions.service(appCtx)
                        .read(com.toolsboox.ot.LedgerPaths.documentsRoot(appCtx), originDate)
                }.getOrNull() as? com.toolsboox.plugin.calendar.fi.CalendarDayService.DayRead.Unreadable
            }
            if (unreadable != null) {
                val e = com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException(unreadable.file, unreadable.cause)
                SynthSummons.warn(rec, "drive-by refused before the ask: ${e.file.name} won't read — nothing asked, nothing minted")
                SynthSummons.say(appCtx, rec, com.toolsboox.plugin.calendar.ot.SynthWords.driveByRefused(e.whichPage), this@CalendarDayFragment)
                SynthSummons.flush(appCtx)
                return@launch
            }
            val answer = runCatching { SynthSummons.ask(appCtx, creds, rec) }.getOrNull()
            // EMPTY-MINT HYGIENE (Michael, 08-31: no husk pages) — a failed round-trip mints
            // NOTHING: no page, no edge, no sticky. Say so and stop.
            if (answer.isNullOrBlank()) {
                SynthSummons.say(appCtx, rec, com.toolsboox.plugin.calendar.ot.SynthWords.DRIVEBY_NO_REPLY, this@CalendarDayFragment)
                return@launch
            }
            // Name from the material's first words — renameable like any topic page.
            val name = material.lineSequence().first().trim().split(Regex("\\s+")).take(5)
                .joinToString(" ").take(48).ifBlank { "Synth" }
            // The page's key is chosen now; the page is LISTED (SynthPageStore.add with this key)
            // only once its cards are on disk — below.
            val page = com.toolsboox.plugin.calendar.ot.SynthPage("synthesize-${System.currentTimeMillis()}", name, originDate)

            // Everything below writes ELEMENTS — buildable whether or not the view survives.
            fun b64(bmp: android.graphics.Bitmap): String {
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                bmp.recycle()
                return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            }
            val minted = mutableListOf<com.toolsboox.da.ImageElement>()
            val srcBmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                material.take(620), source = sourceLine, note = null, W = SYNTH_CARD_RENDER_W, H = 0)
            val srcH = SYNTH_CARD_PLACE_W * srcBmp.height / srcBmp.width
            val opening = com.toolsboox.da.ImageElement(
                x = 90f, y = 140f, width = SYNTH_CARD_PLACE_W, height = srcH, data = b64(srcBmp),
                page = page.key, cardText = material.take(1200),
                sourceLink = "ledger://$originDate/$originPage",
                sourceLabel = sourceLine)
            minted.add(opening)
            // The reply under the opening card through THE ONE PLACEMENT every landing uses (2026-10-12
            // review): a long reply flows onto "<topic>#1" instead of stacking past the canvas foot,
            // where it could never be seen. Faces rendered off the main thread, like every summon's.
            val faces = withContext(Dispatchers.Default) { SynthSummons.faces(answer, persona) }
            val (replyCards, replyLanded) = com.toolsboox.plugin.calendar.ot.SynthLanding.placeUnder(
                originDate, opening, faces, "ledger://${page.date}/${page.key}",
                com.toolsboox.plugin.calendar.ot.SynthVoice.label(persona))
            minted.addAll(replyCards)
            SynthSummons.log(rec, "drive-by reply on page=${replyLanded.firstKey} y=%.0f cards=${replyLanded.cards} last=${replyLanded.lastKey}".format(replyLanded.y))
            // THE STICKY BACK ON HIS PAGE — the reply where he asked; tap = the conversation. Its
            // label stays the door ("❖ <page name>"); its face says whose reply it carries.
            val stickBmp = com.toolsboox.plugin.calendar.ot.StickyCardRenderer.render(
                "❖ " + answer.take(520),
                source = if (persona.isEmpty()) page.name
                    else page.name + " · " + com.toolsboox.plugin.calendar.ot.SynthVoice.shortName(persona),
                W = 760)
            val stickW = 560f
            val stickH = stickW * stickBmp.height / stickBmp.width
            val stickY = ((near?.bottom ?: 100f) + 24f).coerceIn(120f, 1872f - stickH - 40f)
            val stickX = (near?.left ?: 90f).coerceIn(60f, 1404f - stickW - 60f)
            minted.add(com.toolsboox.da.ImageElement(
                x = stickX, y = stickY, width = stickW, height = stickH, data = b64(stickBmp),
                page = originPage, cardText = answer,
                sourceLink = "ledger://${page.date}/${page.key}",
                sourceLabel = "❖ ${page.name}"))

            // 1. THE DAY FIRST. The page on that day — this one, or the page that replaced it
            // (Michael, 2026-10-12: every page turn rebuilds the fragment, so a long wait routinely
            // outlives this one) — takes the cards and its save is AWAITED; else the day file.
            val host = this@CalendarDayFragment.takeIf { it.canTakeSynthLanding(originDate) }
                ?: CalendarDayFragment.liveOn(originDate)?.takeIf { it.canTakeSynthLanding(originDate) }
            var refused: com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException? = null
            var ok = false
            if (host != null) {
                host.addSynthElements(minted, save = false)
                val saved = runCatching { host.saveSynthLanding() }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                refused = saved.exceptionOrNull() as? com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException
                if (refused != null) {
                    // The file stopped reading under the page (now read-only): its cards come back
                    // off — nothing of this drive-by stays in a copy that will never be saved.
                    host.dropSynthElements(minted)
                } else {
                    ok = true
                    if (saved.getOrDefault(false) != true)
                        SynthSummons.warn(rec, "drive-by: the page's save didn't take — its next save carries the cards")
                }
            } else {
                // The day file — with a service that HAS its Moshi. The bare CalendarDayService()
                // this used was the -322 field bug: its load threw "lateinit property moshi has not
                // been initialized", read as a corrupt day, and the drive-by vanished in silence.
                // The editor's door (day-file guard): a day that is there but won't read throws,
                // is never written over, and says which page won't read.
                val attempt = withContext(Dispatchers.IO) {
                    runCatching {
                        val service = com.toolsboox.ui.share.ShareActions.service(appCtx)
                        val root = com.toolsboox.ot.LedgerPaths.documentsRoot(appCtx)
                        DayLocks.withDay(originDate) {
                            val day = service.loadToEdit(root, originDate) ?: return@withDay false
                            day.imageElements.addAll(minted)
                            service.save(root, originDate, day)
                            true
                        }
                    }.onFailure { SynthSummons.warn(rec, "detached drive-by landing threw", it) }
                }
                ok = attempt.getOrDefault(false)
                refused = attempt.exceptionOrNull() as? com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException
            }
            val refusal = refused
            when {
                refusal != null -> {
                    // The page stopped reading during the ask: nothing written, nothing listed or
                    // tied — and the line is the one said before an ask (item 2).
                    SynthSummons.warn(rec, "drive-by landing refused: ${refusal.file.name} won't read — nothing written over it, no page made")
                    SynthSummons.say(appCtx, rec,
                        com.toolsboox.plugin.calendar.ot.SynthWords.driveByRefused(refusal.whichPage), this@CalendarDayFragment)
                }
                ok -> {
                    // 2. Only now, the cards on disk: the page is listed, tied, its first turn and
                    // its voice recorded. Each is logged if it fails — the cards stand either way.
                    runCatching { com.toolsboox.plugin.calendar.ot.SynthPageStore.add(appCtx, page.name, page.date, page.key) }
                        .onFailure { SynthSummons.warn(rec, "the new page didn't list", it) }
                    // ONE undirected rhizome edge ties the two pages (neighbours() reads both ends).
                    runCatching {
                        com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                            appCtx,
                            com.toolsboox.ot.LedgerUri.page(originDate.toString(), originPage),
                            com.toolsboox.ot.LedgerUri.page(page.date.toString(), page.key),
                            com.toolsboox.plugin.calendar.da.v2.Connection.ABOUT,
                            fromLabel = "the page it was circled on", toLabel = page.name)
                    }.onFailure { SynthSummons.warn(rec, "the rhizome edge didn't record", it) }
                    runCatching {
                        com.toolsboox.plugin.calendar.ot.SynthCorrespondence.record(
                            appCtx, originDate, page.key, emptyList(),
                            "[$sourceLine] " + material.take(280), answer, persona = persona)
                    }.onFailure { SynthSummons.warn(rec, "the transcript turn didn't record", it) }
                    // The minted page is born in that voice — written in this same landing step, so a
                    // failed round trip (no reply) leaves no row behind (empty-mint hygiene).
                    if (persona.isNotEmpty()) runCatching {
                        com.toolsboox.plugin.calendar.ot.SynthPageVoices.set(appCtx, page.date.toString(), page.key, persona, hasReply = false)
                    }.onFailure { SynthSummons.warn(rec, "the new page's voice didn't record", it) }
                    if (host != null) {
                        SynthSummons.log(rec, "landed live: page=${page.key} + sticky on page=$originPage y=%.0f".format(stickY))
                        host.sayOnPage(com.toolsboox.plugin.calendar.ot.SynthWords.driveByHere(page.name))
                    } else {
                        SynthSummons.log(rec, "landed in the day file: page=${page.key} + sticky on page=$originPage y=%.0f".format(stickY))
                        CalendarDayFragment.liveOn(originDate)?.let { runCatching { it.onExternalGramPlaced(originPage) } }
                        SynthSummons.say(appCtx, rec, com.toolsboox.plugin.calendar.ot.SynthWords.driveByElsewhere(page.name,
                            com.toolsboox.plugin.calendar.ot.SynthWords.day(originDate, CalendarDayFragment.liveDate() ?: LocalDate.now())), this@CalendarDayFragment)
                    }
                }
                else -> {
                    SynthSummons.warn(rec, "drive-by landing failed: day $originDate wouldn't load — nothing written")
                    SynthSummons.say(appCtx, rec,
                        com.toolsboox.plugin.calendar.ot.SynthWords.couldNotWrite("“${page.name}”"), this@CalendarDayFragment)
                }
            }
            SynthSummons.flush(appCtx)
        }
    }

    /** This page's in-memory day, for a landing to place against (❖ Synthesize this list mints its
     *  cards below what THIS copy holds — the copy its next save writes — then [addSynthElements]). */
    internal fun synthDay(): CalendarDay? = if (::calendarDay.isInitialized) calendarDay else null

    /** The drive-by's minted elements into this page's in-memory day — saved, and drawn now when
     *  one of them belongs to the page on screen (the same repaint as [landSynthLive]). [save]
     *  false: the caller awaits [saveSynthLanding] itself (❖ Synthesize this list clears its
     *  pending row only once the cards are on disk). */
    internal fun addSynthElements(minted: List<com.toolsboox.da.ImageElement>, save: Boolean = true) {
        calendarDay.imageElements.addAll(minted)
        calendarPattern.updateDay(calendarDay)
        if (save) presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        val here = notePage ?: "default"
        if (minted.any { it.page == here }) {
            setImageElements(calendarDay.imageElements.filter { it.page == here }.toMutableList())
            redrawSurface()
            provideSurfaceView().post { if (isAdded) forceFullEpdRefresh() }
        }
    }

    /** [addSynthElements] undone — the very elements, off the in-memory day and the surface — for
     *  a landing whose awaited save was refused (the day file stopped reading under the page). */
    internal fun dropSynthElements(minted: List<com.toolsboox.da.ImageElement>) {
        if (!::calendarDay.isInitialized) return
        calendarDay.imageElements.removeAll { el -> minted.any { it === el } }
        runCatching { calendarPattern.updateDay(calendarDay) }
        val here = notePage ?: "default"
        if (minted.any { it.page == here } && view != null) runCatching {
            setImageElements(calendarDay.imageElements.filter { it.page == here }.toMutableList())
            redrawSurface()
            provideSurfaceView().post { if (isAdded) forceFullEpdRefresh() }
        }
    }

    /** Synthesize: surface 3 generative questions from this page/pickings onto the Synthesize page. */
    private fun synthesizeQuestions() {
        val creds = aiCreds() ?: run { showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                val text = synthesisSource(creds) ?: return@withContext emptyList<String>()
                runLlm(creds,
                    "You are the reader's thinking partner. From their picking/notes below, propose EXACTLY three " +
                        "focused, generative questions worth answering to develop these ideas. Output ONLY the three " +
                        "questions, one per line, no numbering or preamble.", text).take(3)
            }
            if (lines.isEmpty()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            // Where these came from → the group's source gram back-link. From the empty grid we used
            // the pickings fallback, so point back at Pickings.
            val ps = com.toolsboox.plugin.calendar.ot.PickingsStore
            val srcPage = notePage?.takeIf { !com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(it) } ?: "pickings"
            val srcLabel = if (ps.isPickings(srcPage)) ps.nameOf(requireContext(), currentDate, srcPage)
                else srcPage.replaceFirstChar { it.uppercase() }
            com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(requireContext(), currentDate, lines, "question", srcLabel)
            // Land the questions on the synth page you are on if it is one, else the daily page.
            val destSynth = notePage?.takeIf { com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(it) } ?: "synthesize"
            placeSourcedQuestions(lines, destSynth, "ledger://$currentDate/$srcPage", srcLabel,
                refresh = com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage))
            showMessage("Placed 3 questions on your Synthesize page — answer them, then head to a Lines note.", binding.root)
            if (!com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage)) CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "synthesize")
        }
    }

    /** Write: offer 3 AI writing prompts from this page; the chosen one seeds the Write page. */
    private fun writingPrompts() {
        val creds = aiCreds() ?: run { showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val prompts = withContext(Dispatchers.IO) {
                val text = pageOcrText(creds) ?: return@withContext emptyList<String>()
                runLlm(creds,
                    "From the reader's picking/notes below, propose THREE distinct, compelling essay writing prompts " +
                        "that could grow from these ideas. Output ONLY the three prompts, one per line, no numbering.", text).take(3)
            }
            if (prompts.isEmpty()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(
                requireContext(), currentDate, prompts, "prompt", (notePage ?: "day").replaceFirstChar { it.uppercase() })
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                .setTitle("Pick a writing prompt")
                .setItems(prompts.toTypedArray()) { _, which ->
                    placeTextBoxes(listOf("Prompt: " + prompts[which]), "write", refresh = false)
                    CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "write")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showModal()
        }
    }

    /** Write: sketch a classic bulleted essay outline from this page → movable text boxes in Write. */
    private fun essayOutline() {
        // Pick the shape first. It was one hardcoded five-paragraph prompt — right for some
        // pieces, a straitjacket for others — so the outline you carry to Write now matches the
        // thing you are actually trying to write. Sibling of the Map's Draw-it-as personas.
        showIconMenu("Write it as…", com.toolsboox.ot.WritePersona.ALL.map { p ->
            p.label to { runWritePersona(p) }
        })
    }

    private fun runWritePersona(persona: com.toolsboox.ot.WritePersona.Persona) {
        // Trim the boundary before you skeleton. A synthesis page can pick up a stray line from
        // the wrong world — the corpus holds both surrogacy notes and Android build notes, and a
        // well-meant connection dragged one across — so before the outline is drawn you get to
        // untick what does not belong to THIS piece. Kept lines only reach the model.
        trimThenGenerate(persona.label) { kept -> generateOutline(persona, kept) }
    }

    /**
     * Show the page's own material as a checklist, everything on by default, and hand back what
     * survived. Grouped under the source it was synthesized from, so a whole off-topic batch can
     * go with its heading. The handwriting is not listed — ink you wrote by hand is yours and on
     * this page on purpose; this trims the placed text, which is where a foreign source rides in.
     */
    private fun trimThenGenerate(title: String, onKept: (List<String>) -> Unit) {
        val pk = notePage ?: "default"
        val lines = if (::calendarDay.isInitialized)
            calendarDay.textElements.filter { it.pageKey == pk && it.text.isNotBlank() }
                .map { it.text.trim() } else emptyList()
        if (lines.isEmpty()) { onKept(emptyList()); return }
        val ctx = requireContext()
        val checked = BooleanArray(lines.size) { true }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("What belongs in this piece?")
            .setMultiChoiceItems(lines.map { it.take(80) }.toTypedArray(), checked) { _, which, isOn ->
                checked[which] = isOn
            }
            .setPositiveButton(title) { _, _ ->
                onKept(lines.filterIndexed { i, _ -> checked[i] })
            }
            .setNeutralButton("All") { _, _ -> onKept(lines) }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    private fun generateOutline(persona: com.toolsboox.ot.WritePersona.Persona, kept: List<String>) {
        val creds = aiCreds() ?: run { showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                // Kept text plus this page's ink; the ink is read whole, the text is the trimmed set.
                val ink = pageInkText(creds)
                val text = (kept + listOfNotNull(ink)).joinToString("\n").trim()
                if (text.isBlank()) return@withContext emptyList<String>()
                runLlm(creds, persona.prompt, text)
            }
            if (lines.isEmpty()) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(
                requireContext(), currentDate, lines, "outline", (notePage ?: "day").replaceFirstChar { it.uppercase() })
            placeTextBoxes(lines, "write", refresh = false)
            showMessage("Outline placed in Write.", binding.root)
            CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "write")
        }
    }

    /**
     * The Synthesize idea bank: every generated question/prompt for the day, listed so you can drop
     * any of them onto the grid as gram cards (the whiteboard you then write from).
     */
    private fun showSynthesisIdeas() {
        val ctx = requireContext()
        com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.sync(ctx, currentDate)   // pull other devices' ideas
        val ideas = com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.list(ctx, currentDate)
        if (ideas.isEmpty()) {
            showMessage("No synthesized ideas yet — run Synthesize · 3 questions or a Writing prompt first.", binding.root)
            return
        }
        val labels = ideas.map {
            val tag = when (it.kind) { "question" -> "❓"; "prompt" -> "✍"; else -> "•" }
            "$tag  ${it.text}"
        }.toTypedArray()
        val checked = BooleanArray(ideas.size)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Ideas → grid")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Add to grid") { _, _ ->
                val picked = ideas.filterIndexed { i, _ -> checked[i] }
                if (picked.isNotEmpty()) addIdeaCards(picked)
            }
            .setNegativeButton("Close", null)
            .showModal()
    }

    /** Render each idea as a gram card and drop it, grid-staggered, onto the Synthesize page. */
    private fun addIdeaCards(ideas: List<com.toolsboox.plugin.calendar.ot.SynthesisIdea>) {
        if (!::calendarDay.isInitialized) return
        val pageKey = "synthesize"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                for (idea in ideas) {
                    val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(idea.text, "— ${idea.from}", null, 1080, 0)
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                    val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    val w = 1404f * 0.42f; val h = w * bmp.height / bmp.width
                    val count = calendarDay.imageElements.count { it.page == pageKey }
                    val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (1404f - w).coerceAtLeast(0f))
                    val y = (120f + (count / 3) * (h + 30f)).coerceIn(0f, (1872f - h).coerceAtLeast(0f))
                    calendarDay.imageElements.add(com.toolsboox.da.ImageElement(
                        x = x, y = y, width = w, height = h, data = base64, page = pageKey,
                        sourceLabel = "— ${idea.from}", cardText = idea.text))
                }
            }
            calendarPattern.updateDay(calendarDay)
            presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            if (notePage == pageKey)
                setImageElements(calendarDay.imageElements.filter { it.page == pageKey }.toMutableList())
            else CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, pageKey)
            showMessage("Added ${ideas.size} idea card(s) to the grid.", binding.root)
        }
    }

    /** A stroke belongs to a panel if its centroid falls inside the panel's rect. */
    private fun strokeInPanel(stroke: Stroke, rect: android.graphics.RectF): Boolean {
        val pts = stroke.strokePoints
        if (pts.isEmpty()) return false
        val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        return rect.contains(cx, cy)
    }

    /**
     * All text a panel yields: typed boxes in the region + handwriting (digital-ink OCR) +
     * printed text inside any inserted images (text-recognition OCR). Runs off the main thread.
     */
    private suspend fun panelText(panel: com.toolsboox.plugin.calendar.ot.LedgerPanel): String {
        val pageKey = notePage ?: "default"
        val typed = calendarDay.textElements
            .filter { it.pageKey == pageKey && android.graphics.RectF.intersects(textElementBounds(it), panel.rect) }
            .joinToString("\n") { it.text }.trim()
        val ink = com.toolsboox.plugin.calendar.ot.PanelOcr
            .recognize(currentPageStrokes().filter { strokeInPanel(it, panel.rect) })
        // Printed OCR of each inserted image in the panel — a plain loop so the suspend
        // recognizeImage() calls sit in the coroutine body, not a joinToString lambda.
        val panelImages = calendarDay.imageElements.filter {
            it.page == pageKey && android.graphics.RectF.intersects(
                android.graphics.RectF(it.x, it.y, it.x + it.width, it.y + it.height), panel.rect)
        }
        val printed = StringBuilder()
        for (img in panelImages) {
            val bmp = withContext(Dispatchers.Default) {
                com.toolsboox.ot.LedgerMedia.resolveBitmap(requireContext(), img.data, img.dataRef)
            } ?: continue
            val t = com.toolsboox.plugin.calendar.ot.PanelOcr.recognizeImage(bmp)
            if (t.isNotBlank()) printed.append(t).append('\n')
        }
        return listOf(typed, ink, printed.toString().trim())
            .filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    /**
     * Panel → Notes & Annotations: render the panel to a PNG (kept), OCR its handwriting
     * (+ any typed text in the region), and save a ReadingEvent carrying the text + image.
     * That lands it in the annotations log and the chat corpus (searchable / RAG-able).
     */
    private fun savePanelAsNote(panel: com.toolsboox.plugin.calendar.ot.LedgerPanel) {
        if (!::calendarDay.isInitialized) return
        val pageStrokes = currentPageStrokes()
        lifecycleScope.launch {
            val text = panelText(panel)
            val imagePath = try {
                val card = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer
                    .renderCard(templateBitmap, listOf(pageStrokes), panel.rect)
                val dir = java.io.File(requireContext().filesDir, "cards").apply { mkdirs() }
                val file = java.io.File(dir, "${panel.id}-$currentDate-${System.currentTimeMillis()}.png")
                file.outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                file.absolutePath
            } catch (e: Exception) { Timber.w(e, "panel card render failed"); null }

            calendarDay.readingEvents.add(
                com.toolsboox.plugin.calendar.da.v2.ReadingEvent(
                    id = "panelcard-${panel.id}-${System.currentTimeMillis()}",
                    kind = com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.BOOK,
                    date = java.util.Date(),
                    title = panel.title,
                    source = "Ledger · ${sectionEmoji()}",
                    excerpt = text.ifBlank { null },
                    image = imagePath
                )
            )
            calendarPattern.updateDay(calendarDay)
            presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            showMessage(getString(R.string.card_saved_to_notes, panel.title), binding.root)
        }
    }

    /** A short page label for webhook payloads (the note-page key, or "day"). */
    private fun pageLabel(): String = notePage ?: "day"

    /** Panel → configured webhook. Renders + OCRs the panel, queues the card for delivery.
     *  With no webhooks yet, drops straight into add/manage; the picker carries a manage row. */
    private fun sendPanelToWebhook(panel: com.toolsboox.plugin.calendar.ot.LedgerPanel) {
        val hooks = com.toolsboox.plugin.calendar.nw.PanelWebhookStore.list(requireContext())
        if (hooks.isEmpty()) { manageWebhooks(); return }
        val labels = hooks.map { it.name } + getString(R.string.webhook_add)
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(R.string.webhook_pick)
            .setItems(labels.toTypedArray()) { d, w ->
                if (w == hooks.size) manageWebhooks() else deliverPanel(panel, hooks[w])
                d.dismiss()
            }
            .showModal()
    }

    private fun deliverPanel(panel: com.toolsboox.plugin.calendar.ot.LedgerPanel, hook: com.toolsboox.plugin.calendar.nw.PanelWebhook) {
        if (!::calendarDay.isInitialized) return
        val pageStrokes = currentPageStrokes()
        lifecycleScope.launch {
            val text = panelText(panel)
            val png = withContext(Dispatchers.Default) {
                val card = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer
                    .renderCard(templateBitmap, listOf(pageStrokes), panel.rect)
                java.io.ByteArrayOutputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            }
            val job = com.toolsboox.plugin.calendar.nw.PanelWebhookJob(
                webhookName = hook.name, url = hook.url, key = hook.key,
                panelId = panel.id, title = panel.title, text = text,
                page = pageLabel(), dateMs = System.currentTimeMillis(), imageFile = ""
            )
            com.toolsboox.plugin.calendar.nw.PanelWebhookQueue.enqueue(requireContext(), job, png)
            com.toolsboox.plugin.calendar.nw.PanelWebhookQueue.scheduleDrain(requireContext())
            showMessage(getString(R.string.webhook_sent, panel.title, hook.name), binding.root)
        }
    }

    /** Add / remove webhook destinations (niche-industry templates). */
    private fun manageWebhooks() {
        val ctx = requireContext()
        val hooks = com.toolsboox.plugin.calendar.nw.PanelWebhookStore.list(ctx)
        val labels = hooks.map { "${it.name} — ${it.url}" } + getString(R.string.webhook_add)
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(R.string.webhook_manage)
            .setItems(labels.toTypedArray()) { d, which ->
                if (which == hooks.size) addWebhookDialog()
                else {
                    val h = hooks[which]
                    AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle(h.name)
                        .setMessage(h.url)
                        .setPositiveButton(R.string.webhook_remove) { _, _ ->
                            com.toolsboox.plugin.calendar.nw.PanelWebhookStore.remove(ctx, h.name)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .showModal()
                }
                d.dismiss()
            }
            .showModal()
    }

    private fun addWebhookDialog() {
        val ctx = requireContext()
        fun field(hint: String, pwd: Boolean = false) = EditText(ctx).apply {
            this.hint = hint; setSingleLine(); if (pwd) inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val name = field(getString(R.string.webhook_name_hint))
        val url = field(getString(R.string.webhook_url_hint)).apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val key = field(getString(R.string.webhook_key_hint), pwd = true)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0)
            addView(name); addView(url); addView(key)
        }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle(R.string.webhook_add).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = name.text.toString().trim(); val u = url.text.toString().trim()
                if (n.isNotEmpty() && (u.startsWith("http://") || u.startsWith("https://"))) {
                    com.toolsboox.plugin.calendar.nw.PanelWebhookStore.add(
                        ctx, com.toolsboox.plugin.calendar.nw.PanelWebhook(n, u, key.text.toString().trim().ifBlank { null })
                    )
                    showMessage(getString(R.string.webhook_saved, n), binding.root)
                } else showMessage(getString(R.string.webhook_invalid), binding.root)
            }
            .setNegativeButton(R.string.cancel, null)
            .showModal()
    }

    /**
     * Render one panel of the current page to a PNG card and hand it to the share sheet.
     * Composites the drawn template with this page's handwriting, crops to the panel.
     */
    private fun sharePanelAsCard(panel: com.toolsboox.plugin.calendar.ot.LedgerPanel) {
        if (!::calendarDay.isInitialized) return
        val strokeLists = if (notePage != null)
            listOf(calendarDay.noteStrokes[notePage] ?: emptyList())
        else
            listOf(calendarDay.calendarStrokes[calendarStyle] ?: emptyList())
        try {
            val card = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer
                .renderCard(templateBitmap, strokeLists, panel.rect)
            val dir = java.io.File(requireContext().cacheDir, "cards").apply { mkdirs() }
            val file = java.io.File(dir, "${panel.id}-$currentDate.png")
            file.outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "${panel.title} — $currentDate")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(share, panel.title))
        } catch (e: Exception) {
            Timber.w(e, "panel card render/share failed for ${panel.id}")
            showMessage(getString(R.string.card_render_failed), binding.root)
        }
    }

    /**
     * The Ledger hub — one collapsible popover mirroring the iPad's up/down/home
     * modal: the center of the nav pill (and the top-left hamburger) open it, and the
     * pill's ↑/↓ arrows step the same sections. Everything that used to be a separate
     * popup lives here: this day's sections, every directory, and the tools.
     */
    private fun showLedgerHub() {
        if (!isAdded) return
        // Single source of truth: the same directory the feed/book/reader/chat screens use, with
        // this day's recently-opened books folded into the Bookshelf folder.
        val books: List<Pair<String, () -> Unit>> =
            recentBooks().map { f -> ("📖  " + f.nameWithoutExtension) to { openBookInReader(f) } }
        // Every hub opening on this page comes from the rail (its Hub button — the top-left ☰
        // retired with the conversion), so the drawer grows from the rail's own edge.
        showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this, books), anchorEnd = railOnRight())
    }

    /** The Hub the wrench's first row and the left edge's Back open here: this page's own. */
    override fun openHub() = showLedgerHub()

    /** The three most-recently-opened books (recency = file mtime, touched on open). */
    private fun recentBooks(): List<java.io.File> =
        java.io.File(requireContext().filesDir, "reader/books").listFiles()
            ?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(3) ?: emptyList()

    /** Open a specific book straight into the reader. */
    private fun openBookInReader(f: java.io.File) {
        val shelf = java.io.File(requireContext().filesDir, "reader/books")
        requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
            .putString("current_book_path", f.absolutePath)
            // The reader restores a STREAMED book (uri + name) ahead of the path when both are
            // saved — so a shelf-file open that left the last tree book's keys standing opened
            // THAT book instead (Michael, 09-13: bell hooks's row opened The Shards). Retire
            // them, and write the sync id beside the path as the shelf's own open does.
            .remove("current_book_uri").remove("current_book_name")
            .putString("current_reading_id", com.toolsboox.plugin.calendar.ot.ReadingStateStore.idForShelfFile(shelf, f))
            .apply()
        f.setLastModified(System.currentTimeMillis())
        findNavController().navigate(R.id.action_to_reader)
    }

    /** Open the Feed Ledger in a given view/lens (feed / stars / read + read/watch/listen). */
    private fun openFeed(mode: String, kind: String?) {
        com.toolsboox.plugin.feeds.ui.FeedSelection.filterFeedTitle = null
        com.toolsboox.plugin.feeds.ui.FeedSelection.mode = mode
        com.toolsboox.plugin.feeds.ui.FeedSelection.kind = kind
        findNavController().navigate(R.id.action_to_feeds)
    }

    /** Open Notes & Annotations preset to a given origin (null = all). */
    private fun openReadingLog(origin: LogOrigin?) {
        ReadingLogSelection.origin = origin
        findNavController().navigate(R.id.action_to_reading_log)
    }

    // (sectionIcon(), the drawable twin of sectionEmoji below, left with the nav pill it drove —
    // the rail's Today button wears the fixed ic_nav_today face instead.)

    /** The section currently on screen as its monochrome glyph — labels what a gram was
     *  captured from (it used to drive the nav pill's centre button too). */
    private fun sectionEmoji(): String = when (baseNotePage(currentNotePage())) {
        // Text-presentation sun (VS15) renders as a solid black glyph — high contrast on e-ink,
        // unlike the washed-out yellow colour emoji. Base glyphs (no VS16) throughout, so the
        // pill's glyph matches the monochrome icons the modals render for the same sections.
        null, "default", CalendarDay.DEFAULT_STYLE -> "☀︎"
        "pickings" -> "❝"
        "gratitude" -> "🙏"
        "intake" -> "🔖"
        "write" -> "✍"
        "synthesize" -> "❖"
        "grid" -> "📈"
        "sketch" -> "⌱"
        com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS -> "◈"
        com.toolsboox.plugin.calendar.ot.DatedNote.DOODLE -> "🖊"
        "selfexec" -> "🐘"
        // Named documents wear their surface's glyph — a grid you named is still a grid.
        else -> when {
            com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(currentNotePage()) -> "❖"
            com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(currentNotePage()) -> "✍"
            com.toolsboox.plugin.calendar.ot.GridPageStore.isMine(currentNotePage()) -> "📈"
            com.toolsboox.plugin.calendar.ot.JotPageStore.isMine(currentNotePage()) -> "⌱"
            else -> "✒"
        }
    }

    /**
     * Shared builder for the floating panels — a compact emoji list. Directories anchor
     * top-left (by the hamburger); sections anchor bottom (by the pill).
     */
    /** The target page's template into the fresh templateCanvas, from the placeholder day — the
     *  same drawPage calls renderPage makes, minus strokes and dashboard thumbs. Best effort:
     *  a failure here costs nothing but the pre-draw. Fragment-owned surfaces only; the ink path
     *  (SurfaceFragment / BigmeFastInk) is untouched. */
    private fun primeTemplateForTarget() {
        runCatching {
            val ctx = requireContext()
            val np = notePage
            if (np != null) {
                if (np == "intake") {
                    val stars = IntakePageStore.load(ctx, currentDate)
                    drawTemplate(decodesPictures = true) { c -> CalendarDayPageIntake.drawPage(c, stars, calendarDay, context = ctx) }
                } else {
                    val sheet = sharedPreferences.getInt("calendarNoteTemplate", 0)
                    drawTemplate { c -> CalendarDayPageNotes.drawPage(ctx, c, calendarDay, sheet, np) }
                }
            } else {
                drawTemplate { c ->
                    CalendarDayPage.drawPage(ctx, c, calendarDay, lastRenderedEvents, emptyList(), null, recordHits = false)
                }
            }
        }
    }

    /**
     * OnResume hook.
     */
    override fun onResume() {
        super.onResume()
        // The page a ❖ reply finds when its summoner is gone (see [liveRef]).
        liveRef = java.lang.ref.WeakReference(this)

        // THE FIRST FRAME IS THE PAGE YOU ASKED FOR. super.onResume just minted a fresh, TRANSPARENT
        // template bitmap, and the real page only lands after the presenter's async load (a day-file
        // read plus a calendar-provider query). Every page jump is a fragment re-creation and the B6
        // recreates the whole activity on a flip, so on e-ink that gap showed whatever was retained
        // underneath — the day dashboard you jumped from, or the launcher's today widget (Michael,
        // 09-07: "whenever I rotate or page jump on the B6, it flashes the today page underneath").
        // notePage / currentDate / the placeholder calendarDay are already correct here, so draw
        // the TARGET's furniture now (ink-less); renderPage repaints it with the loaded day.
        primeTemplateForTarget()

        binding.templateImageView.setImageBitmap(templateBitmap)
        // setImageBitmap resets nothing about scaleType, but a fresh instance (the B6 recreates on
        // rotation) starts at the XML `fitXY` until the first transform lands — pin it to MATRIX now
        // so the template never flashes stretched-to-fit while the strokes are on [viewMatrix].
        syncTemplateToSurface()
        binding.navigatorImageView.setImageBitmap(navigatorBitmap)
        updateNavigator(true)

        // super.onResume just re-dressed the upstream toolbar (root tap → the shared collapse
        // state, button group VISIBLE); the rail takes its slot back before anything paints.
        tuckPanel?.assertTakeover()

        // The feeds directory's back button lands here with the today menu open, Feed Ledger
        // folder expanded — the last rung of the slim-pane ⇄ directory ⇄ today-menu ladder.
        if (com.toolsboox.plugin.feeds.ui.FeedSelection.openTodayHubOnArrival) {
            com.toolsboox.plugin.feeds.ui.FeedSelection.openTodayHubOnArrival = false
            binding.root.post {
                showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this, expandFeedLedger = true))
            }
        }

        val seedStartHour = PagePrefs.settingHour(requireContext())
        val appCtx = requireContext().applicationContext
        // The one synced page setting, pulled on the way in: a start hour chosen on the iPad has to
        // be in hand BEFORE the first day page of the session is minted, because a day is stamped
        // with it exactly once, at creation, and owns it from then on.
        PagePrefs.sync(appCtx)
        // ❖ The synced persona set and the pages' voices (Michael, 2026-10-12, ruling 5) — round-
        // tripped on the way in, so a voice chosen on the iPad answers here; a merge that changed
        // anything redraws the header on the page standing then. At most once a minute across the
        // app (review of b29cabbc): every page turn rebuilds this fragment, so an onResume is not
        // a coming-back, and a Synthesize page's opening round-trips its voices on its own.
        if (voicesResumeSyncDue()) {
            com.toolsboox.plugin.chat.nw.PersonaStore.sync(appCtx) { redrawSynthHeader() }
            com.toolsboox.plugin.calendar.ot.SynthPageVoices.sync(appCtx) { redrawSynthHeader() }
        }
        // Arm the insert deferral BEFORE the reload is launched: any picker/camera/share result
        // that fires between here and the load's renderPage must wait for the fresh data, or the
        // reload repaints over it (see isPageDataReady). renderPage clears the flag.
        resumeReloadPending = true
        timer = GlobalScope.launch(Dispatchers.Main) {
            // GlobalScope on purpose (the load must outlive a quick leave), so nothing thrown in
            // here may escape — an uncaught throwable in GlobalScope kills the process, and a
            // Snackbar on a view that died mid-load is exactly such a throwable (audit 09-10).
            runCatching {
                presenter.load(this@CalendarDayFragment, binding, currentDate, seedStartHour, locale)
                syncPresenter.backgroundSync(this@CalendarDayFragment, UUID.randomUUID())
                // Weather is IP-based + cached ~1h; if it just refreshed, redraw so the header badge shows.
                if (com.toolsboox.plugin.calendar.ot.WeatherMoon.refresh(appCtx) && isAdded && view != null && isResumed) {
                    presenter.load(this@CalendarDayFragment, binding, currentDate, seedStartHour, locale)
                }
            }.onFailure { timber.log.Timber.w(it, "day resume load") }
        }
        maybeShowReturnChip()

        // Pre-session appointment nudge: an opted-in class about to start prompts to record
        // and pull up its roster/notes (self-throttled, once-a-day-dismissable). Roster port.
        com.toolsboox.plugin.calendar.ui.AppointmentNudge.maybeShow(this) { recordAvGram() }

        // Hardware page-turn buttons (volume/page keycodes) paginate the day surface,
        // same as the nav pill's up/down.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = { up ->
            if (isResumed) {
                if (up) binding.toolbarDrawing.toolbarSwipeUp.performClick()
                else binding.toolbarDrawing.toolbarSwipeDown.performClick()
                true
            } else false
        }
    }

    /** If we arrived here from an open article/book (to jot a note), offer a one-tap jump back to
     *  exactly where we were. Cleared once used. */
    private fun maybeShowReturnChip() {
        val anchor = com.toolsboox.ui.plugin.LedgerReturn
        if (!anchor.isSet) return
        val label = anchor.label
        val text = if (label != null) "↩ Back to “$label”" else "↩ Back to where you were"
        val bar = com.google.android.material.snackbar.Snackbar.make(
            binding.root, text, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
        )
        bar.setAction("Back") {
            val action = anchor.navActionId
            val arm = anchor.onReturn
            anchor.clear()
            // Only the chip arms the source's reopen (see LedgerReturn.onReturn).
            arm?.invoke()
            runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(action) }
        }
        bar.show()
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()
        if (liveRef?.get() === this) liveRef = null

        // Release the hardware page-key handler so it doesn't page a stale surface.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null

        // Leaving the intake page counts as "page exit" — hand any typed content
        // that has not been delivered yet to the intake queue.
        if (notePage == "intake") {
            intakePageData?.let { data ->
                context?.let { ctx -> IntakePageStore.dispatch(ctx, currentDate, data) }
            }
        }

        toolbar.toolbarPager.visibility = View.GONE
        timer.cancel()
        // Auto-OCR this page's sections in the background as we leave — a page TURN is a leave too
        // (each page is its own fragment), so handwritten #tags and [[links]] are harvested on turn
        // and on leaving (Michael 2026-10-11: "Sure"). ONE pass, the per-band-signature one, behind
        // the Settings switch that promises it ("Read this page when I leave it" — which now says
        // it is also what reads handwritten #tags and [[links]], so turning it off is a choice made
        // knowing that, not a silent stop; review of dbe1a38d).
        // Until now a written-on page ran TWO passes here: runCaptureSections(silent) re-read every
        // band with ink, unguarded, and this one re-read the changed bands — so one new line on a
        // four-band page paid for five vision calls, and the switch gated only the first.
        // An unchanged page costs no call: each band's ink signature is compared first (the flag
        // that used to gate the first pass is gone with it).
        if (::calendarDay.isInitialized && sharedPreferences.getBoolean("autoCaptureSections", true)) {
            runCatching { autoCaptureSections() }
        }
        syncPresenter.backgroundSync(this@CalendarDayFragment, UUID.randomUUID())
        // Leaving any ink page — a page turn, a surface switch, the screen going dark — is the
        // moment this page should reach the other devices. The cheap day-JSON mirror only; the
        // heavy PDF pass keeps its own schedule and its own foreground guard.
        context?.let { com.toolsboox.plugin.calendar.nw.QuickDayMirror.fire(it, "page-leave") }
    }

    override fun onTransformChanged(matrix: Matrix) {
        // The Roots band's one live TextView used to be re-positioned here on every transform —
        // it was a view floating over drawn paper, so it had to follow the page's own matrix or it
        // drifted off its patch at any zoom. The band is retired; the page is drawn ink again.
        //
        // The template bitmap (dashboard, weather/moon strip, note minis — all baked in the 1404×1872
        // design space) and the SurfaceView strokes must project through the SAME matrix, or they
        // separate. The strokes always use [viewMatrix]; the template ImageView only tracks it when
        // it is put in MATRIX mode with this same matrix. That is the whole of the alignment — see
        // [syncTemplateToSurface], which re-asserts it wherever the template is (re)shown.
        syncTemplateToSurface(matrix)
    }

    /**
     * Pin the template ImageView to the live surface transform ([viewMatrix]) — the one matrix the
     * SurfaceView strokes already use — so the drawn dashboard and the ink stay on the same page.
     *
     * onTransformChanged fires this on every zoom/pan/rotation, but it is REACTIVE: between a
     * relayout that changes the surface size (rotation, the sidebar popping in/out — CORRECTION 3's
     * "the new parts don't pop in/out properly with the side bar") and the transform callback that
     * follows it, a [renderPage] can repaint the template with the PREVIOUS fit while the strokes
     * are already on the new one — the today page's landscape "squished / weather adrift / strokes
     * off the ruled zones" is exactly that half-beat of disagreement. Re-asserting the current
     * matrix at every (re)show closes the window. The XML default (`fitXY`) is left to the other
     * calendar faces (week/month/year) that share this layout and never enter MATRIX mode; only the
     * day dashboard, which owns [onTransformChanged], is pinned here.
     *
     * Landscape stays FULL-WIDTH: this copies whatever [refitZoomToWidth] computed (totalScale =
     * sw/1404, the page taller than the screen and scrolled), it never re-fits or letterboxes.
     */
    private fun syncTemplateToSurface(matrix: Matrix = surfaceTransform()) {
        if (!::binding.isInitialized) return
        binding.templateImageView.scaleType = ImageView.ScaleType.MATRIX
        // While the template drawn again at the zoomed resolution is up (16× with no loss of
        // clarity, Michael 2026-10-12) it already carries the matrix: the view wears identity.
        binding.templateImageView.imageMatrix = templateMatrixFor(matrix)
    }

    /** Where the template at the zoomed resolution shows (SurfaceFragment.ensureSharpTemplate). */
    override fun templateView(): ImageView? = if (::binding.isInitialized) binding.templateImageView else null

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
        // The load runs on a lifecycle-detached scope; if this fragment is already paused/detached
        // (a fast second page turn replaced it), don't post an intermediate page's strokes onto the
        // surface — that's another way old ink lands over the destination page.
        if (!isAdded || !isResumed) return
        // The resume reload has landed: from here on the in-memory day IS the file, so element
        // inserts may apply directly again (and the deferred one is consumed at the end of this
        // very render, onto the fresh data it was waiting for).
        resumeReloadPending = false
        this.calendarDay = calendarDay
        this.calendarPattern = calendarPattern
        this.lastRenderedEvents = calendarEvents
        updateNavigator()
        // REPAIR (09-14): -272's page walk wrote a numeric note's overflow onto "N#k" — a page the
        // dated pager cannot count. Such elements belong on page N+k; move them once, here.
        run {
            val bad = Regex("""^(\d+)#(\d+)$""")
            var moved = 0
            calendarDay.imageElements.forEach { e -> bad.find(e.page)?.let { m -> e.page = (m.groupValues[1].toInt() + m.groupValues[2].toInt()).toString(); moved++ } }
            calendarDay.textElements.forEach { t -> bad.find(t.pageKey)?.let { m -> t.pageKey = (m.groupValues[1].toInt() + m.groupValues[2].toInt()).toString(); moved++ } }
            if (moved > 0) {
                Timber.i("dated-note page repair: moved $moved elements off N#k pages")
                presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            }
        }

        // Load this page's text elements and images from the calendar data.
        //
        // TOMBSTONES ARE HONOURED HERE, not only in the merger: deleting a task removes the item
        // and its face from the file, but a union with a device that hadn't seen the delete yet
        // (or a pre-tombstone copy already sitting in the file) hands the face straight back — and
        // a deleted task drawn in the Tasks section is exactly what "tasks … that should have been
        // deleted" is. The tombstone lists are the authority on what may be drawn; anything they
        // name never reaches the surface, and the next save prunes it for good.
        val imgPageKey = notePage ?: "default"
        val deadElements = HashSet<String>(calendarDay.deletedElementIds.size + calendarDay.deletedItemIds.size)
        calendarDay.deletedElementIds.forEach { deadElements.add(it.lowercase()) }
        calendarDay.deletedItemIds.forEach { deadElements.add(it.lowercase()) }
        // ALL STARS SETTLES ON LOAD, before its elements reach the surface: the register's bands
        // are sized to their content, so a band's top moves when a band above it grows — and a
        // card's stored x/y may date from a geometry that no longer exists. The kind is the
        // authority on membership ([CalendarDayPageIntake.settleAllStars] — the iOS twin runs the
        // identical pass over the identical file, and if the two forks settled the same cards to
        // different shelves every sync would ping-pong positions). Settled in memory only; the
        // corrected coordinates ride the next ordinary save, because renderPage is a read path
        // and must not start writing day files.
        if (imgPageKey == CalendarDayPageIntake.INTAKE_PAGE)
            CalendarDayPageIntake.settleAllStars(calendarDay)
        setTextElements(
            calendarDay.textElements
                .filter { it.pageKey == imgPageKey && it.elementId.toString().lowercase() !in deadElements }
                .toMutableList()
        )
        setImageElements(
            calendarDay.imageElements
                .filter { it.page == imgPageKey && it.elementId.toString().lowercase() !in deadElements }
                .toMutableList()
        )
        // Same rule for ink: an erased task's strokes are tombstoned, and a resurrected copy must
        // not paint the handwriting back over the row.
        val deadStrokes = HashSet<String>(calendarDay.deletedStrokeIds.size)
        calendarDay.deletedStrokeIds.forEach { deadStrokes.add(it.lowercase()) }
        fun live(strokes: List<Stroke>): List<Stroke> =
            if (deadStrokes.isEmpty()) strokes
            else strokes.filterNot { it.strokeId.toString().lowercase() in deadStrokes }

        if (notePage != null) {
            binding.toolbarDrawing.toolbarProcrastinator.visibility = View.GONE
            val noteTemplate = sharedPreferences.getInt("calendarNoteTemplate", 0)
            val noteStrokes = calendarDay.noteStrokes[notePage] ?: listOf()
            // A notes page shows no ghost rows and no dashboard — drop the recorded rectangles so a
            // stale one can't send a tap on this page off to a task or a dashboard zone (PickingsCover.clear).
            CalendarDayPage.clearGhostRows()
            CalendarDayPage.clearDashHits()
            if (notePage == "intake") {
                // All Stars: five labeled bands (The Read / The Watch / The Listen / The Books /
                // The Mail), each a shelf of just its own grams. No typing — the grams are the
                // content; tap one to open it.
                val intakeData = IntakePageStore.load(requireContext(), currentDate).also { intakePageData = it }
                val ctx = requireContext()
                drawTemplate(decodesPictures = true) { c -> CalendarDayPageIntake.drawPage(c, intakeData, calendarDay, context = ctx) }
            } else {
                val ctx = requireContext()
                val np = notePage!!
                drawTemplate { c -> CalendarDayPageNotes.drawPage(ctx, c, calendarDay, noteTemplate, np) }
            }
            // ❖ Opening a Synthesize page round-trips the pages' voices: a voice chosen on another
            // device redraws this page's header when it arrives.
            if (onSynthPage()) syncSynthVoicesOnOpen()
            // Zones still capture per-section (captureSections), but we no longer paint
            // boxes/labels over the page — the original template provides the sectioning.
            applyStrokes(Stroke.listDeepCopy(live(noteStrokes)), true)
        } else {
            binding.toolbarDrawing.toolbarProcrastinator.visibility = View.VISIBLE
            val calendarStrokes = calendarDay.calendarStrokes[calendarStyle] ?: listOf()
            openTasksShown = openTasksForPanel()
            // The dashboard's Notes/Doodle minis, rendered by the fragment (it owns renderPageBitmap)
            // and painted INTO the schedule template by drawPage — part of the page, not Views.
            val doodleKey = com.toolsboox.plugin.calendar.ot.DatedNote.DOODLE
            // Each thumb carries the note's name, drawn BELOW the thumbnail by the template.
            val recentThumbs = datedNotePages().filterNot { it == doodleKey }.takeLast(3).map {
                CalendarDayPage.DashThumb(it, renderNoteThumb(it, 320), dashNoteTitle(it))
            }
            // Doodle no longer needs a preview thumb — it's inked directly on the page.
            val ctx = requireContext()
            drawTemplate { c -> CalendarDayPage.drawPage(ctx, c, calendarDay, calendarEvents, recentThumbs, null) }
            warmOpenTasks(calendarEvents)
            applyStrokes(Stroke.listDeepCopy(live(calendarStrokes)), true)
        }
        // The day's pages are known only now — the "3/7" pager indicator was wired against the
        // placeholder day and must learn the dated note's real length.
        updateDatedNotePagerLabel()
        // A gather's "⚗ Synthesize it" landed us here with the ask standing: open the engine
        // picker over the fresh note, once, after the surface has painted (one-shot flag, the
        // pending-open idiom).
        if (pendingSynthPicker && notePage != null) {
            pendingSynthPicker = false
            binding.root.post {
                val ctx = context ?: return@post
                com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
                    ctx, "Synthesize this page", present = { showModal(it) }) { e ->
                    // A gathered board's words live on its CARDS, not in text boxes — the page
                    // material for this hop reads both, or a gram-only harvest synthesizes air.
                    val cards = calendarDay.imageElements
                        .filter { it.page == (notePage ?: "default") }
                        .mapNotNull { el ->
                            listOf(el.cardText, el.mediaTitle, el.sourceLabel)
                                .firstOrNull { s -> s.isNotBlank() }
                        }
                    val material = (currentTextElements().filter { it.text.isNotBlank() }
                        .map { it.text.trim() } + cards)
                        .distinct().joinToString("\n") { "• $it" }.take(6000)
                    runEngine(e, material)
                }
            }
        }
        // …and the day face's notes band learns which pages today actually holds.
        bindNotesBand()
        // The template was just drawn into templateBitmap; force the ImageView to repaint so
        // named pages (pickings/gratitude) reliably show on first navigation, not only after a re-swipe.
        // Pin it to the live surface transform FIRST: a resize that triggered this render (rotation,
        // sidebar) may not have delivered its onTransformChanged yet, and repainting the template at
        // the stale fit while the strokes sit on the new one is the landscape "dashboard/weather/ink
        // don't line up" half-beat. Re-asserting the current matrix keeps the drawn page and the ink
        // on the same projection (full-width in landscape — this copies the fit, never re-letterboxes).
        syncTemplateToSurface()
        binding.templateImageView.invalidate()
        redrawImageSelectionIfActive()

        // A picker/camera result may have arrived before this load finished.
        consumeDeferredImageInsert()

        // Tag → mark: if we arrived from the Tags index with a focus rect, zoom/center that capture
        // zone once on this first render. Posted so the surface has been sized. Legacy occurrences
        // carry no rect, so pendingFocusRect stays null and the page opens unfocused, as before.
        CalendarNavigator.pendingFocusRect?.let { rect ->
            CalendarNavigator.pendingFocusRect = null
            provideSurfaceView().post { if (isAdded) focusOnRect(rect) }
        }
        // ❖ A reply that flowed onto this sub-page brought us here (Michael, 2026-10-12): pan its
        // first card into view — never a zoom — and say where it went. Keyed to this page and day.
        CalendarNavigator.pendingReveal?.takeIf { it.date == currentDate && it.page == notePage }?.let { r ->
            CalendarNavigator.pendingReveal = null
            provideSurfaceView().post { if (isAdded) revealCanvasRect(r.rect) }
            r.message?.let { showMessage(it, binding.root) }
        }

        // Wipe any stale Onyx hardware ink overlay from a fast previous page turn so it can't
        // ghost over this freshly rendered page ("overlapping strokes when paging quickly").
        // Posted so it runs after the template ImageView repaints, capturing the full clean frame.
        provideSurfaceView().post { forceFullEpdRefresh() }
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
            if (longPressPending || longPressArmed) {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressPending = false
                longPressArmed = false
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
                longPressArmed = false
                com.toolsboox.ot.LedgerContextMenu.dismissCurrent()   // clear any lingering menu
                // Armed in move/resize mode too, now — a hold that stays put opens the full menu,
                // while a drag past the slop cancels it and moves the element as before. Being in
                // move/resize used to seal off the menu entirely, so the only actions you could
                // reach were the on-canvas chips; a still finger reaches the rest again.
                if (motionEvent.pointerCount == 1) {
                    longPressDownX = motionEvent.x
                    longPressDownY = motionEvent.y
                    longPressPending = true
                    longPressHandler.postDelayed(longPressRunnable, 550L)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressPending = false
                longPressArmed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressArmed) {
                    // The finger is provably still down past the hold threshold — open the menu.
                    fireCanvasLongPress()
                    return true
                }
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
                if (longPressArmed) {
                    longPressArmed = false
                    // A held finger releasing still opens the menu; a quick tap whose UP was
                    // merely DELIVERED late (main-thread stall) does not — judge by the
                    // gesture's own timestamps, not by when we got around to processing it.
                    if (motionEvent.actionMasked == MotionEvent.ACTION_UP &&
                        motionEvent.eventTime - motionEvent.downTime >= 550L
                    ) {
                        fireCanvasLongPress()
                        longPressFired = false   // gesture is over; nothing further to swallow
                        return true
                    }
                }
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
    /**
     * Touch an event on the day page — on the schedule grid or in Stars & Events — and open it.
     *
     * The page is a picture, so an event drawn on it has never been touchable: you could see the
     * appointment and there was nothing to do about it. `DayEventHits` records the same rectangles
     * the page paints into, so a tap resolves against the layout rather than a second guess at it.
     *
     * Same tap discipline as the intake panels: finger only, small movement, short press — a pen
     * touch stays ink, and a drag stays a drag.
     */
    private fun handleEventTap(motionEvent: MotionEvent, gestureResult: Int): Boolean {
        if (!::calendarDay.isInitialized) return false
        val toolType = motionEvent.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_FINGER && toolType != MotionEvent.TOOL_TYPE_UNKNOWN) return false

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                eventTapDownX = motionEvent.x
                eventTapDownY = motionEvent.y
                eventTapDownAt = System.currentTimeMillis()
            }

            MotionEvent.ACTION_UP -> {
                val dx = abs(motionEvent.x - eventTapDownX)
                val dy = abs(motionEvent.y - eventTapDownY)
                val dt = System.currentTimeMillis() - eventTapDownAt
                if (gestureResult != OnGestureListener.NONE || dx >= 30f || dy >= 30f || dt !in 1..600) return false
                val p = screenToCanvas(motionEvent.x, motionEvent.y)
                val event = com.toolsboox.plugin.calendar.ot.DayEventHits
                    .at(requireContext(), calendarDay, calendarDay.events, p[0], p[1]) ?: return false
                showEventDetail(event)
                return true
            }
        }
        return false
    }

    /** What an event actually is: when, where, and what it says. */
    private fun showEventDetail(event: com.toolsboox.plugin.calendar.da.v1.CalendarEvent) {
        val ctx = context ?: return
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.Instant.ofEpochMilli(event.startDate).atZone(zone)
        val end = java.time.Instant.ofEpochMilli(event.endDate).atZone(zone)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
        val when_ = when {
            event.allDay -> getString(R.string.calendar_day_all_day)
            // An event running the whole day isn't flagged all-day but reads as one, and saying
            // "00:00 – 23:59" is a worse answer than saying so. This is also exactly why it sits
            // in Stars & Events rather than on the grid — see DayEventHits.
            start.hour == 0 && start.minute == 0 && end.hour >= 23 && end.minute >= 59 ->
                getString(R.string.calendar_day_all_day)
            else -> start.format(fmt) + "  –  " + end.format(
                java.time.format.DateTimeFormatter.ofPattern(
                    if (start.toLocalDate() == end.toLocalDate()) "HH:mm" else "EEE d MMM · HH:mm"))
        }
        // Where first, after when. An appointment you can't act on is a notification, and the
        // address is usually the only thing standing between the two.
        // Built as a view rather than setMessage(): one joined string gets the stock dialog's
        // body style for everything, so the time, the address and a paragraph of description all
        // arrive at the same weight in the system font. What it says was never the problem.
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val hyperlegible = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.atkinson_hyperlegible)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(6), px(20), 0)
        }
        fun line(text: String, size: Float, colour: Int, top: Int, bold: Boolean = false) {
            if (text.isBlank()) return
            col.addView(android.widget.TextView(ctx).apply {
                this.text = text
                textSize = size
                setTextColor(colour)
                typeface = if (bold) android.graphics.Typeface.create(hyperlegible, android.graphics.Typeface.BOLD)
                else hyperlegible
                setPadding(0, px(top), 0, 0)
            })
        }
        // When it is, first and loudest — it is the thing you opened this to find out.
        line(when_, 17f, 0xFF000000.toInt(), 0, bold = true)
        line(event.location.ifBlank { "" }.let { if (it.isBlank()) "" else "📍  $it" }, 15f, 0xFF333333.toInt(), 12)
        line(event.calendarName.ifBlank { "" }.let { if (it.isBlank()) "" else "🗓  $it" }, 13f, 0xFF777777.toInt(), 10)
        line(event.organizer.takeIf { it.contains("@") || it.contains(" ") }?.let { "👤  $it" } ?: "",
            13f, 0xFF777777.toInt(), 6)
        event.description.trim().takeIf { it.isNotBlank() && it != "-no-description-" }
            ?.let { line(it, 14f, 0xFF222222.toInt(), 16) }

        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        com.toolsboox.ot.ReadingSize.apply(scroll)

        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(event.title.ifBlank { getString(R.string.calendar_day_untitled_event) })
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.calendar_day_event_to_task) { _, _ -> makeTaskFromEvent(event) }
            .showModal()
    }

    /** Turn an event into a task on its own day — the thing you actually have to do about it. */
    private fun makeTaskFromEvent(event: com.toolsboox.plugin.calendar.da.v1.CalendarEvent) {
        val text = event.title.trim().ifBlank { return }
        val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
            id = "evt-" + java.util.UUID.randomUUID().toString().lowercase(),
            kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
            text = text,
            date = java.util.Date(event.startDate),
            source = "event",
            stage = "todo"
        )
        if (com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe.containsTask(calendarDay.ledgerItems, text)) {
            showMessage(getString(R.string.calendar_day_event_already_task), binding.root); return
        }
        calendarDay.ledgerItems.add(item)
        presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        lifecycleScope.launch(Dispatchers.IO) {
            com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item)
        }
        showMessage(getString(R.string.calendar_day_event_made_task, text.take(40)), binding.root)
    }

    /**
     * A finger tap on one of the daily Pickings cover's recent-board tiles opens that board on
     * its own day. Same tap discipline as the intake strips and the day page's events — finger
     * only, small movement, short press — so a pen touch stays ink and a drag stays a drag.
     * Anything outside a tile falls through untouched: the band must never swallow the page.
     */
    private fun handlePickingsCoverTap(motionEvent: MotionEvent, gestureResult: Int): Boolean {
        val toolType = motionEvent.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_FINGER && toolType != MotionEvent.TOOL_TYPE_UNKNOWN) return false

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pickingsTapDownX = motionEvent.x
                pickingsTapDownY = motionEvent.y
                pickingsTapDownAt = System.currentTimeMillis()
            }

            MotionEvent.ACTION_UP -> {
                val dx = abs(motionEvent.x - pickingsTapDownX)
                val dy = abs(motionEvent.y - pickingsTapDownY)
                val dt = System.currentTimeMillis() - pickingsTapDownAt
                if (gestureResult == OnGestureListener.NONE && dx < 30f && dy < 30f && dt in 1..600) {
                    val p = screenToCanvas(motionEvent.x, motionEvent.y)
                    com.toolsboox.plugin.calendar.ot.PickingsCover.tileAt(p[0], p[1])?.let { tile ->
                        CalendarNavigator.toDayNote(this, tile.date, tile.key)
                        return true
                    }
                }
                // A HOLD on a tile is handled by the long-press path ([fireCanvasLongPress]),
                // which consumes every still-finger hold before this handler runs.
            }
        }

        return false
    }

    /**
     * A HOLD on a tile asks to hide it — the continuity tile ("Thursday on Friday's page")
     * is automatic, so removal has to be a gesture, not an edit.
     */
    private fun showPickingsTileHideDialog(tile: com.toolsboox.plugin.calendar.ot.PickingsCover.Tile) {
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Hide \"${tile.name.ifBlank { "Pickings" }} · ${tile.date}\" from the cover?")
            .setPositiveButton("Hide") { _, _ ->
                com.toolsboox.plugin.calendar.ot.PickingsCover.hideTile(requireContext(), tile)
                presenter.load(this@CalendarDayFragment, binding, currentDate,
                    PagePrefs.settingHour(requireContext()), locale)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    // handleIntakeTap is gone. It resolved finger taps against the cells the template had painted
    // its grams into — a hit-test table for a picture. All Stars' grams are real elements now, so
    // the element layer resolves taps (and the ✓-corner became the "Give it its own board" verb on
    // the ordinary gram menu). Keeping a second, parallel tap path over stale rects would only ever
    // disagree with the live one.

    /**
     * Graduate an intake gram into its own pickings note: the name pad asks what to call it
     * (seeded from the gram; blank keeps the day-suffix default), a copy of its face lands there
     * (where the object's further pickings then accumulate), and the intake gram is stamped with
     * that note's key — so it wears a ✓ and becomes a link into the note.
     */
    private fun graduateIntakeGram(element: com.toolsboox.da.ImageElement) {
        if (context == null) return
        val seed = element.cardText.ifBlank { element.sourceLabel }.take(40).trim()
        promptTitle(
            this, com.toolsboox.plugin.calendar.ot.LedgerDocuments.PICKINGS,
            "Its own note", "Name (blank = today's)", seed.ifBlank { null }, "Create"
        ) { chosen, _ -> graduateIntakeGramNamed(element, chosen) }
    }

    private fun graduateIntakeGramNamed(element: com.toolsboox.da.ImageElement, chosenName: String) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val boardKey = withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    // Blank falls through to PickingsStore's day-suffix default (the -28 rule).
                    val name = chosenName.trim().take(48)
                    val board = com.toolsboox.plugin.calendar.ot.PickingsStore.add(ctx, currentDate, name)
                    // A copy of the gram's face onto the new board (already treated → no second tape).
                    com.toolsboox.ot.LedgerMedia.resolveBitmap(ctx, element.data, element.dataRef)?.let { bmp ->
                        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                            calendarDayService, root, bmp, currentDate, board.key,
                            sourceLink = element.sourceLink, sourceLabel = element.sourceLabel,
                            treatment = false, cardText = element.cardText, sourceFeed = element.sourceFeed
                        )
                    }
                    // Persist the graduation on the intake gram.
                    DayLocks.withDay(currentDate) {
                        // The presenter's read-only rule holds for this load→mutate→save too: a
                        // day file that exists but wouldn't load must not be saved over.
                        val day = calendarDayService.loadForWrite(
                            root, currentDate, PagePrefs.settingHour(ctx), java.util.Locale.getDefault()
                        ) ?: return@withDay
                        day.imageElements.firstOrNull { it.elementId == element.elementId }
                            ?.graduatedTo = board.key
                        calendarDayService.save(root, currentDate, day)
                    }
                    board.key
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (boardKey == null) {
                showMessage("Couldn't graduate that pick", binding.root)
                return@launch
            }
            // Reflect it immediately: stamp the in-memory gram and redraw the intake page's ✓.
            element.graduatedTo = boardKey
            val ctx = context
            drawTemplate(decodesPictures = true) { c ->
                CalendarDayPageIntake.drawPage(
                    c, com.toolsboox.plugin.michaelfilter.da.IntakePageData(), calendarDay,
                    context = ctx
                )
            }
            binding.templateImageView.invalidate()
            showMessage("Picked into its own note — tap the pick to open it", binding.root)
        }
    }

    // ─── Intake page: the hold menu ────────────────────────────────────────────────────────────
    // Star Sort fills itself — stars and filed mail arrive on their own — which left no way to put
    // something there ON PURPOSE. A hold is that way in: "Bring in a picking" reaches back into the
    // boards for a card you already made and files it under the quarter you held. When the hold
    // lands on a gram, the rest of the menu is that gram's own business, using the same actions the
    // page already offers it by tap (graduate / open its board / open the source) plus the gram
    // verbs the canvas menu gives every other card.

    /**
     * One card already on a Pickings board, offered back to the intake page — as a REFERENCE to
     * that card, never as a copy of it.
     *
     * ── Why this holds no pixels ──────────────────────────────────────────────────────────────
     *
     * It used to carry `data`: the card's complete base64 face, as a Java String, for as long as the
     * picker dialog was on screen. [gatherPickingGrams] filled two hundred of those from a hundred
     * and twenty fully-parsed day files. The 3150×4200 photograph that put one day file at 86 MB is
     * ~40 MB of base64, which is ~80 MB of UTF-16 String held live behind an open menu — and that
     * was the MEMORY FLOOR the "bring in a picking freezes then crashes" report landed on top of.
     * The main-thread decodes were fixed in `4aaaa268`; this was the part that commit left, with its
     * reason recorded honestly: "bounding it means either dropping a picking or restructuring
     * PickingGram to hold a file offset instead of the payload — neither is contained, and I didn't
     * want to silently hide his cards."
     *
     * The restructure is contained NOW because [PickingsCards] exists (`dab8cd18`). A card's label,
     * board page, day and rectangle are all in a small sidecar per day; the only thing that is not
     * is the face. So this is the index row plus the day it came from, and it is enough to LIST a
     * card. The payload is fetched from the day file for the ONE card you actually choose
     * ([fetchPicking]), and the faces shown in the list are decoded to thumbnail size and the
     * source bytes dropped immediately ([decodeGramThumb]).
     *
     * [id] is the element's own UUID, which is what makes the fetch exact — two cards clipped from
     * the same article carry the same label and the same source, and picking one must not bring the
     * other.
     */
    private data class PickingGram(
        val date: LocalDate, val page: String, val id: String,
        val boardName: String, val label: String, val glyph: String,
        // "❝" for a board's card, "◈" for one still in the Gram Picks inbox — the picker's rows
        // say which kind of place a card is coming from, since the two mean different things
        // (a board keeps its card; the inbox gives its card up — see [fromGramPicks]).
        val boardGlyph: String = "❝"
    ) {
        /** Whether this card lives on the Gram Picks inbox — sub-pages included. */
        val fromGramPicks: Boolean
            get() = page.substringBefore('#') == com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
    }

    /**
     * What one pass over the ledger found: the cards, and the days it could not answer for yet.
     *
     * [unread] is the honest half. The card index is repaired lazily — it rides the app's ordinary
     * decodes — so on a ledger that predates it, or one whose days arrived through
     * `CalendarWebDavSyncService.writeLocal` (which installs pulled day files straight from bytes and
     * can never call a save hook), there are days the index has simply never met. Those days are
     * decoded here, but only [PICK_DAY_BUDGET] of them per pass, because a decode is the expensive
     * thing this whole redesign exists to avoid doing a hundred and twenty times. Whatever is left
     * over is COUNTED and SHOWN, never dropped: the picker says how many days it has not read and
     * offers to read them, and every pass permanently repairs the days it did read.
     */
    private data class PickingHarvest(val rows: List<PickingGram>, val unread: Int)

    /**
     * The intake page's hold menu, resolved against [CalendarDayPageIntake]'s recorded cells.
     *
     * @param cx canvas x of the hold
     * @param cy canvas y of the hold
     * @param pressX press x in the surface view (menu anchor)
     * @param pressY press y in the surface view (menu anchor)
     */
    private fun showIntakeHoldMenu(cx: Float, cy: Float, pressX: Float, pressY: Float) {
        if (!::calendarDay.isInitialized) return
        val gram = CalendarDayPageIntake.gramAt(cx, cy)
        // A gram held → its own band; empty paper → the band under the finger; neither →
        // The Read, so the menu still works when the hold lands in a gutter.
        val panel = CalendarDayPageIntake.panelAt(cx, cy)
        val kindKey = gram?.kindKey ?: panel?.kindKey ?: CalendarDayPageIntake.kinds.first().kindKey
        val kindTitle = CalendarDayPageIntake.kinds.firstOrNull { it.kindKey == kindKey }?.title ?: "ALL STARS"
        val element = gram?.let { g ->
            calendarDay.imageElements.firstOrNull { it.elementId.toString().lowercase() == g.elementId }
        }

        fun item(label: String, action: () -> Unit) = com.toolsboox.ot.LedgerContextMenu.Item(label, action)
        val groups = mutableListOf<List<com.toolsboox.ot.LedgerContextMenu.Item>>()

        groups.add(listOf(item("❝  Bring in a picking") { bringPickingIntoIntake(kindKey, kindTitle) }))

        if (element != null) {
            groups.add(listOfNotNull(
                // A reply gram filed here (a letter being answered) leads with its errand — the
                // Write piece that answers it. Null for every ordinary starred card.
                com.toolsboox.plugin.mail.ui.MailVerbs.writeReplyItem(this, element.sourceLink),
                if (element.graduatedTo.isBlank())
                    item("✓  Give it its own note…") { graduateIntakeGram(element) }
                else
                    item("❝  Open its note") { CalendarNavigator.toDayNote(this, currentDate, element.graduatedTo) },
                if (element.sourceLink.isNotBlank()) item("🔗  Open the source") { onImageSource(element) } else null,
                if (element.sourceFeed.isNotBlank()) item("📰  Go to its feed") { onImageGoToFeed(element) } else null,
                item("❝  Add to Pickings…") { intakeGramToPickings(element) },
                item("🕸  Rhizome") { onImageRhizome(element) },
                item("🔎  Where used") { onImageWhereUsed(element) }
            ))
            groups.add(listOf(item("🗑  Remove from All Stars") { confirmRemoveIntakeGram(element) }))
        }

        // The pen pauses under this menu like every other one (plan v6 S1.0c, Michael 2026-10-10).
        com.toolsboox.ot.LedgerContextMenu.show(
            provideSurfaceView(), pressX, pressY,
            if (element != null) "GRAM" else kindTitle, groups,
            onShow = { onModalShown() }, onDismiss = { onModalDismissed() }
        )
    }

    /** This intake gram onto a Pickings board — the same chooser every other card gets. */
    private fun intakeGramToPickings(element: ImageElement) {
        val ctx = context ?: return
        val bmp = com.toolsboox.ot.LedgerMedia.resolveBitmap(ctx, element.data, element.dataRef) ?: return
        placeGramToPickings(bmp)
    }

    /**
     * Move a gram to another making surface: Gram Picks, a Pickings board, a Star Sort quarter, or
     * Synthesize.
     *
     * This is the other half of Gram Picks (Michael's proposal): an inbox is only useful if you can
     * empty it. Capture sends everything to one page; this sorts it onward, once, with the gram in
     * front of you. Before, the only way to change a gram's home was to delete it and re-grab it
     * from the original source — assuming the source was still to hand.
     *
     * A move is just a change of [ImageElement.page]. The one surface that needs more is Star Sort,
     * whose grams are painted into a grid keyed by [ImageElement.intakeKind] rather than laid out at
     * their own coordinates — so going there sets the quarter, and leaving clears it, or the gram
     * would carry a stale quarter that nothing reads.
     */
    /**
     * A gram was placed on the day by the in-pane feed reader while this page stayed on screen.
     *
     * RE-READ, never save. The card is already on disk (the placement wrote it under the day lock);
     * this fragment's in-memory copy is the stale one, so saving would be the very thing that loses
     * it. Ink is safe to discard here because it is already persisted — strokes commit on pen-up.
     */
    override fun onExternalGramPlaced(pageKey: String) {
        if (!isAdded || !isResumed) return
        presenter.load(
            this, binding, currentDate,
            PagePrefs.settingHour(requireContext()), locale
        )
    }

    /** A rotation repair re-lays the chrome — the pager pill (and the ✒ Synth button riding
     *  it) comes back, the same rule as pagination rebuilding the fragment with the full pill.
     *  Without this a flip could leave the summon control tucked behind the › tab or
     *  clipped off the stale-width row (Michael, B6 field: the button "disappears"). */
    override fun onGeometryRecovered() {
        if (pagerSteppersTucked) {
            pagerSteppersTucked = false
            binding.notePagerTab.visibility = View.GONE
            binding.notePager.visibility = View.VISIBLE
        }
        binding.notePager.bringToFront()
    }

    override fun onImageGraduate(element: ImageElement) = graduateIntakeGram(element)

    override fun onImageOpenBoard(element: ImageElement) {
        if (element.graduatedTo.isNotBlank())
            CalendarNavigator.toDayNote(this, currentDate, element.graduatedTo)
    }

    /**
     * ➕ "Add to a note…" — Michael's answer #4 on DESIGN-gather-verbs ("we should be able to
     * add to an existing one"): a COPY of this gram onto a board you already keep, where
     * [onImageMoveTo] relocates the original. Same destination vocabulary as Move (Gram Picks,
     * every named board), minus All Stars: the register is a record of what was starred, and
     * copying INTO a record would forge history.
     */
    override fun onImageAddToNote(element: ImageElement) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val boards = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, currentDate)
        val gramPicks = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
        val targets = mutableListOf<Pair<String, String>>()
        targets.add("◈  All Picks" to gramPicks)
        for (b in boards) targets.add("❝  ${b.name}" to b.key)
        val offered = targets.filterNot { it.second == element.page }
        if (offered.isEmpty()) { showMessage("Nowhere else to add it yet.", binding.root); return }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Add a copy to…")
            .setItems(offered.map { it.first }.toTypedArray()) { _, which ->
                val (label, pageKey) = offered[which]
                // A fresh identity, the same face and provenance — the NotePageCopy contract in
                // one element: the original never learns it was copied.
                val count = calendarDay.imageElements.count { it.page == pageKey }
                val copy = element.copy(
                    elementId = java.util.UUID.randomUUID(),
                    page = pageKey,
                    x = 80f + (count % 3) * 440f,
                    y = 120f + (count / 3) * 480f,
                    timestamp = System.currentTimeMillis(),
                )
                calendarDay.imageElements.add(copy)
                calendarPattern.updateDay(calendarDay)
                presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                showMessage("Added to ${label.substringAfter("  ")}.", binding.root)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    override fun onImageMoveTo(element: ImageElement) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val boards = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, currentDate)
        val gramPicks = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS

        // (label, pageKey, intakeKind) — intakeKind only means anything on the intake page.
        val targets = mutableListOf<Triple<String, String, String>>()
        targets.add(Triple("◈  All Picks", gramPicks, ""))
        for (b in boards) targets.add(Triple("❝  ${b.name}", b.key, ""))
        for (p in CalendarDayPageIntake.kinds)
            targets.add(Triple("★  All Stars · ${p.title}", CalendarDayPageIntake.INTAKE_PAGE, p.kindKey))
        // No "→ Synthesize" target any more: the surface retired, so offering to move a gram onto
        // it would be building new content on a page with no door of its own. Grams already sitting
        // on a synthesis stay exactly where they are and open with it.

        // Don't offer where it already is.
        val here = targets.filterNot { it.second == element.page && it.third == element.intakeKind }
        if (here.isEmpty()) return

        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Move to…")
            .setItems(here.map { it.first }.toTypedArray()) { _, which ->
                val (label, pageKey, kind) = here[which]
                element.page = pageKey
                element.intakeKind = kind
                setImageElements(
                    calendarDay.imageElements.filter { it.page == (notePage ?: "default") }.toMutableList()
                )
                calendarPattern.updateDay(calendarDay)
                presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                if (notePage == CalendarDayPageIntake.INTAKE_PAGE) redrawIntakePage()
                showMessage("Moved to ${label.substringAfter("  ")}.", binding.root)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    // The quarter-to-quarter picker that used to live here is gone. It existed because a painted
    // gram could not be dragged, so changing its band needed a dialog; a real element just gets
    // dragged into the band you want, which is what "manipulatable just like pickings" means.
    // Correcting a mis-detected kind still works — drag it across and it stays.

    /** Taking a card off Star Sort is a deletion, so it asks — and says which one it means. */
    private fun confirmRemoveIntakeGram(element: ImageElement) {
        val what = element.cardText.ifBlank { element.sourceLabel }.ifBlank { "this pick" }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Remove from All Stars?")
            .setMessage(what.take(160))
            .setPositiveButton("Remove") { _, _ -> removeIntakeGram(element) }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    /** Drop the gram from the page and tombstone it, so a sync union can't hand it back. */
    private fun removeIntakeGram(element: ImageElement) {
        calendarDay.imageElements.removeAll { it.elementId == element.elementId }
        val eid = element.elementId.toString()
        if (eid !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(eid)
        setImageElements(
            calendarDay.imageElements.filter { it.page == CalendarDayPageIntake.INTAKE_PAGE }.toMutableList()
        )
        calendarPattern.updateDay(calendarDay)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        redrawIntakePage()
    }

    /** Repaint the All Stars template in place (after a gram arrives or leaves, or the filter moves). */
    private fun redrawIntakePage() {
        if (notePage != CalendarDayPageIntake.INTAKE_PAGE) return
        if (starsScope == "day") {
            val stars = intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData()
            val ctx = context
            drawTemplate(decodesPictures = true) { c -> CalendarDayPageIntake.drawPage(c, stars, calendarDay, null, "TODAY", context = ctx) }
            binding.templateImageView.invalidate()
            return
        }
        // A wider window means reading other days off disk, so paint the day's own stars now and
        // swap in the fuller set when it lands. The page is never blank and never blocks the pen.
        run {
            val stars = intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData()
            val label = starsScopeLabel() + " …"
            val ctx = context
            drawTemplate(decodesPictures = true) { c -> CalendarDayPageIntake.drawPage(c, stars, calendarDay, null, label, context = ctx) }
        }
        binding.templateImageView.invalidate()
        val scope = starsScope
        lifecycleScope.launch {
            val grams = withContext(Dispatchers.IO) { collectScopedStars(scope) }
            if (!isAdded || notePage != CalendarDayPageIntake.INTAKE_PAGE || starsScope != scope) return@launch
            val stars = intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData()
            val label = starsScopeLabel()
            val ctx = context
            drawTemplate(decodesPictures = true) { c -> CalendarDayPageIntake.drawPage(c, stars, calendarDay, grams, label, context = ctx) }
            binding.templateImageView.invalidate()
        }
    }

    /**
     * Every starred gram inside the current window, gathered off the main thread.
     *
     * One directory walk finds the day files that exist in range, and only those are read — the same
     * discipline [NotesTagsFragment.gather] uses, and for the same reason: day files run to
     * megabytes, so "load the year" must mean "load the days that exist", not 365 attempts. A day
     * that won't parse is skipped rather than failing the window; a register that shows most of the
     * month beats one that shows an error.
     */
    private fun collectScopedStars(scope: String): List<ImageElement> {
        val (start, end) = starsWindow(scope)
        val root = java.io.File(documentsRoot(), "calendar")
        if (!root.exists()) return emptyList()
        val out = mutableListOf<ImageElement>()
        val re = Regex("""day-(\d{4})-(\d{2})-(\d{2})""")
        root.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { f ->
                val m = re.find(f.name) ?: return@forEach
                val d = runCatching {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }.getOrNull() ?: return@forEach
                if (d.isBefore(start) || !d.isBefore(end)) return@forEach
                val day = runCatching { calendarDayService.load(f) }
                    .onFailure { Timber.w(it, "All Stars: skipping ${f.name}") }.getOrNull() ?: return@forEach
                out += day.imageElements.filter { it.page == CalendarDayPageIntake.INTAKE_PAGE }
            }
        return out
    }

    /** Half-open [start, end) for the current filter level, anchored on the page's date. */
    private fun starsWindow(scope: String): Pair<LocalDate, LocalDate> = when (scope) {
        "week" -> {
            val monday = currentDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            monday to monday.plusWeeks(1)
        }
        "month" -> currentDate.withDayOfMonth(1) to currentDate.withDayOfMonth(1).plusMonths(1)
        "quarter" -> {
            val q = currentDate.withDayOfMonth(1).withMonth(((currentDate.monthValue - 1) / 3) * 3 + 1)
            q to q.plusMonths(3)
        }
        "year" -> currentDate.withDayOfYear(1) to currentDate.withDayOfYear(1).plusYears(1)
        else -> currentDate to currentDate.plusDays(1)
    }

    private fun starsScopeLabel(): String = when (starsScope) {
        "week" -> "WEEK ${currentDate.get(java.time.temporal.WeekFields.of(locale).weekOfWeekBasedYear())}"
        "month" -> currentDate.month.name
        "quarter" -> "Q${(currentDate.monthValue - 1) / 3 + 1}"
        "year" -> "${currentDate.year}"
        else -> "TODAY"
    }

    /**
     * Bring a card from a Pickings board onto the intake page, into the quarter that was held.
     *
     * Star Sort's grams normally arrive by themselves — a star, a filed email — and there was no
     * door for "put THAT one here". This is that door: the boards you already made, as faces you
     * can recognise, and the chosen one is filed under [kindKey] exactly as a starred gram would
     * be (same [PickingsPlacement] path, provenance carried, its card face already treated so it
     * isn't taped down twice).
     */
    private fun bringPickingIntoIntake(kindKey: String, kindTitle: String) =
        pickPickingGram("Bring in a picking → $kindTitle") { fileIntoIntake(it, kindKey, kindTitle) }

    /**
     * Bring a card from a Pickings board onto THIS page, where the finger pressed.
     *
     * The same door as the intake one, opening the other way: Star Sort files a picking into a
     * quarter, a making page just wants it here, next to what you're writing. Provenance rides
     * along ([placeGramAt] keeps the source link and card text), so a picking carried onto a
     * synthesis page still knows where it came from — that's the whole reason to move it rather
     * than paste a copy.
     */
    private fun bringPickingOntoPage(cx: Float, cy: Float) = pickPickingGram("Bring in a picking") { p ->
        // The payload is fetched here, for this one card, off the main thread — see [PickingGram].
        // `placeGramAt` itself must stay on Main (it touches the surface's element list and redraws),
        // and it is safe there because `4aaaa268` made it read the face's HEADER for its aspect ratio
        // and allocate no pixels at all.
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            // Resolved to base64 HERE, still on IO: `placeGramAt` runs on Main and reads only the
            // header, and a ref'd face's file read belongs off the main thread with the fetch.
            // Phase R placements write inline, so the new element carries the base64 either way.
            val pair = withContext(Dispatchers.IO) {
                fetchPicking(appCtx, p)?.let { el ->
                    com.toolsboox.ot.LedgerMedia.resolveBase64(appCtx, el.data, el.dataRef)?.let { el to it }
                }
            }
            if (!isAdded) return@launch
            val ok = pair != null && placeGramAt(
                pair.second, cx, cy,
                sourceLink = pair.first.sourceLink, sourceLabel = p.label,
                cardText = pair.first.cardText, sourceFeed = pair.first.sourceFeed
            )
            // Out of the inbox is a MOVE: a real board keeps its card (material being carried
            // around), but Gram Picks is the pile you sort out of, and a sort that copies never
            // empties the pile. Same-day originals leave through the in-memory day (this
            // fragment holds it, and a disk write behind its back would be resurrected by the
            // next pen-up save); other days' leave on disk, under their own day lock.
            if (ok && p.fromGramPicks) {
                if (!removeInboxOriginalInMemory(p)) {
                    withContext(Dispatchers.IO) { removeInboxOriginalOnDisk(appCtx, p) }
                }
            }
            showMessage(if (ok) "Brought in ${p.label}" else "Couldn't bring that picking in", binding.root)
        }
    }

    /**
     * Take a moved gram's ORIGINAL off the Gram Picks inbox, for a source day this fragment holds
     * in memory. Tombstoned as well as removed, or the sync union hands it back. Returns false
     * when the day isn't the one in hand — the disk path below owns that case.
     */
    private fun removeInboxOriginalInMemory(p: PickingGram): Boolean {
        if (!::calendarDay.isInitialized || p.date != currentDate) return false
        if (!calendarDay.imageElements.removeAll { it.elementId.toString() == p.id }) return false
        if (p.id !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(p.id)
        calendarPattern.updateDay(calendarDay)
        presenter.save(this, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        return true
    }

    /** The disk half of the move: another day's inbox gives its card up under that day's lock.
     *  MUST be called off the main thread — it loads and saves a whole day file. */
    private fun removeInboxOriginalOnDisk(ctx: android.content.Context, p: PickingGram) {
        runCatching {
            val root = com.toolsboox.ot.LedgerPaths.documentsRoot(ctx)
            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(p.date) {
                val day = calendarDayService.loadForWriteOrThrow(root, p.date, null, Locale.getDefault())
                if (day.imageElements.removeAll { it.elementId.toString() == p.id }) {
                    if (p.id !in day.deletedElementIds) day.deletedElementIds.add(p.id)
                    calendarDayService.save(root, p.date, day)
                }
            }
        }.onFailure { Timber.w(it, "couldn't take the moved gram off Gram Picks (${p.date})") }
    }

    /**
     * The picker itself: every card on your Pickings boards, and whatever the caller wants done with
     * the one you choose.
     *
     * ── What changed, and what did not ────────────────────────────────────────────────────────
     *
     * What did not: it is still a list of FACES. Michael's whole reason for this door is "the boards
     * you already made, as faces you can recognise", and a list of file names would not be that door.
     *
     * What did: the list is now built from [PickingsCards] — labels, boards and dates, out of small
     * per-day sidecars — and the faces arrive AFTERWARDS, for the rows actually on screen, decoded to
     * thumbnail size with the source bytes released immediately. Nothing holds a card's payload.
     * Before: 120 day files parsed and up to 200 complete base64 payloads (~80 MB of String for a
     * single oversized photograph) held for as long as the dialog stood open, and the dialog said
     * "Reading your boards…" for all of it. After: the rows appear off the index, the faces fill in
     * behind them, and the peak is one day file's decode plus a page of thumbnails.
     *
     * ── Two visible bounds, and no invisible ones ─────────────────────────────────────────────
     *
     * The old gather had two silent ones — newest 120 day files, first 200 cards — and a third that
     * nobody had noticed: `if (thumb == null) continue` dropped the row for any card whose face
     * failed to decode. All three are gone. Rows are paged [PICK_ROWS_PAGE] at a time behind an
     * "…and N more" row, which is the Ledger Directory's own unroll idiom rather than a second kind
     * of paging; a card whose face won't decode keeps its row and wears its glyph; and days the index
     * has not met are counted and offered, not skipped. See [PickingHarvest].
     */
    private fun pickPickingGram(title: String, onPick: (PickingGram) -> Unit) {
        val ctx = context ?: return
        val appCtx = ctx.applicationContext
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt())
        }
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun note(text: String) = android.widget.TextView(ctx).apply {
            this.text = text; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), px(6))
        }

        listCol.addView(note("Reading your notes…"))

        val thumbPx = px(76)
        var cap = PICK_ROWS_PAGE
        var faces: Job? = null

        // Declared before it is defined because the "…and N more" row it builds calls it again with a
        // bigger cap. Re-rendering is free — the rows are references, already in hand.
        lateinit var render: (PickingHarvest) -> Unit

        fun gather() {
            lifecycleScope.launch {
                val harvest = withContext(Dispatchers.IO) { gatherPickingGrams(appCtx) }
                if (!isAdded || !dialog.isShowing) return@launch
                render(harvest)
            }
        }

        render = { harvest ->
            faces?.cancel()
            listCol.removeAllViews()
            val shown = harvest.rows.take(cap)

            if (harvest.rows.isEmpty()) {
                listCol.addView(note(
                    if (harvest.unread > 0) "No pickings with cards yet — and ${harvest.unread} older days not read."
                    else "No pickings with cards yet."
                ))
            }

            // (row, its face slot) so the face pass can find the view that wants each card.
            val slots = mutableListOf<Pair<PickingGram, android.widget.ImageView>>()

            for (p in shown) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(8), px(6), px(8))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                // The glyph stands where the face will be until it arrives — and STAYS there if the
                // face cannot be decoded at all. A row you can read is a row you can choose; the old
                // loop skipped such a card entirely, which is the one thing this door must not do.
                val face = android.widget.ImageView(ctx).apply {
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(px(76), px(76))
                }
                val glyph = android.widget.TextView(ctx).apply {
                    text = p.glyph; textSize = 26f; setTextColor(0xFF888888.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(px(76), px(76))
                }
                val slot = FrameLayout(ctx).apply { addView(glyph); addView(face) }
                row.addView(com.toolsboox.ot.InkMount.wrap(ctx, slot, taped = false).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(10) }
                })
                row.addView(android.widget.TextView(ctx).apply {
                    text = "${p.label}\n${p.boardGlyph} ${p.boardName} · ${p.date}"
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                row.setOnClickListener { dialog.dismiss(); onPick(p) }
                listCol.addView(row)
                slots += p to face
            }

            if (harvest.rows.size > cap) {
                listCol.addView(android.widget.TextView(ctx).apply {
                    text = "…and ${harvest.rows.size - cap} more"
                    textSize = 14f; setTextColor(0xFF444444.toInt())
                    setPadding(px(6), px(12), px(6), px(12))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { cap += PICK_ROWS_PAGE; render(harvest) }
                })
            }

            // The days the index has never met. Tapping runs another pass, which reads another
            // [PICK_DAY_BUDGET] of them — and the reading STICKS, because every decode repairs that
            // day's sidecar on its way through CalendarDayService.load. So this row counts down.
            if (harvest.unread > 0) {
                listCol.addView(android.widget.TextView(ctx).apply {
                    text = "⟳  ${harvest.unread} older days not read yet"
                    textSize = 14f; setTextColor(0xFF444444.toInt())
                    setPadding(px(6), px(12), px(6), px(12))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { listCol.removeAllViews(); listCol.addView(note("Reading…")); gather() }
                })
            }

            // ── The faces ─────────────────────────────────────────────────────────────────────
            //
            // One day file open per DATE, not per card: a day whose board holds nine cards is read
            // once and gives up all nine thumbnails. The decoded day and every payload in it go out
            // of scope when the `withContext` returns, so what survives is a handful of 76dp
            // bitmaps — this is the whole difference between the old picker's memory and this one's.
            //
            // Only the rows that were actually RENDERED are wanted, which is what keeps this bounded:
            // an unrolled cap is what buys the next page of faces, and a ledger you never scroll
            // costs one page of them. Unrolling re-renders from scratch and so re-reads the days it
            // has already read — the simple thing rather than a face cache the dialog would have to
            // own and evict, and the re-read is of days that are by then in the OS page cache.
            // Cancelled by the next render, and abandoned as soon as the dialog goes away.
            faces = lifecycleScope.launch {
                for ((date, group) in slots.groupBy { it.first.date }) {
                    if (!isAdded || !dialog.isShowing) return@launch
                    val thumbs = withContext(Dispatchers.IO) {
                        val file = com.toolsboox.ot.LedgerPaths.dayFile(appCtx, date)
                            ?: return@withContext emptyMap<String, android.graphics.Bitmap>()
                        val day = runCatching { calendarDayService.load(file) }.getOrNull()
                            ?: return@withContext emptyMap()
                        val wanted = group.map { it.first.id }.toSet()
                        day.imageElements
                            // A face is inline `data` OR a media ref — an externalized gram is
                            // still a gram (data → dataRef → nothing, per the wire brief).
                            .filter { it.elementId.toString() in wanted && (it.data.isNotBlank() || it.dataRef.isNotBlank()) }
                            .mapNotNull { e ->
                                decodeGramThumb(e.data, e.dataRef, thumbPx, android.graphics.Bitmap.Config.RGB_565)
                                    ?.let { e.elementId.toString() to it }
                            }
                            .toMap()
                    }
                    if (!isAdded || !dialog.isShowing) return@launch
                    for ((p, view) in group) thumbs[p.id]?.let {
                        view.setImageBitmap(it)
                        (view.parent as? FrameLayout)?.getChildAt(0)?.visibility = View.GONE
                    }
                }
            }
        }

        // Shown first, THEN read: every guard in the render and the face pass asks
        // `dialog.isShowing`, and a pass that started before the dialog was up would answer that
        // question wrongly and abandon itself.
        showModal(dialog)
        gather()
    }

    /**
     * Decode a gram's base64 face at no more than [maxPx] on its long edge.
     *
     * NOTHING here may decode a gram at full resolution. A day file was found at 86 MB because a
     * single 3150×4200 16-bit PNG had been inlined into it, and that one card decodes to ~53 MB of
     * ARGB_8888 — one allocation big enough to take the app out on a Boox, and slow enough to look
     * like a hang on the way. Bounds first, then a power-of-two `inSampleSize`, so the cost of a
     * face is set by the size we're going to SHOW it at, not by whatever the source happened to be.
     *
     * Returns null on any failure (including OOM, which `runCatching` catches as a Throwable) —
     * a card that won't decode is skipped, never fatal.
     *
     * [config] exists for the picker's list, and only for it. `inSampleSize` is a power of two, so a
     * face asked for at 76dp lands anywhere up to twice that — around 370 KB apiece in ARGB_8888,
     * which across a page of rows is a new floor put in where the old one was just taken out. The
     * list is looking at grey paper on a grey screen, so it takes RGB_565 and halves them. Placement
     * ([fileIntoIntake]) keeps the full config, because those pixels are re-encoded and kept.
     */
    private fun decodeGramThumb(
        data: String, ref: String, maxPx: Int,
        config: android.graphics.Bitmap.Config = android.graphics.Bitmap.Config.ARGB_8888
    ): android.graphics.Bitmap? {
        // The bounded pattern itself now lives in [com.toolsboox.ot.LedgerMedia.resolveThumb],
        // where every surface that resolves a media ref shares it — the rule above (nothing
        // decodes a gram at full resolution) holds whether the face is inline or a `dataRef`.
        val ctx = context ?: return null
        return com.toolsboox.ot.LedgerMedia.resolveThumb(ctx, data, ref, maxPx, config)
    }

    /**
     * Every card sitting on a Pickings board, newest day first — as references, holding no pixels.
     *
     * ── The walk ──────────────────────────────────────────────────────────────────────────────
     *
     * Every day that has a day file, newest first. There is no 120-day window any more: that window
     * existed because each day inside it cost a full parse, and it silently truncated the answer —
     * a board made four months ago simply was not offered, with nothing on screen saying so. An
     * INDEXED day now costs one small sidecar read, so the window's whole justification is gone and
     * the ledger can be answered end to end.
     *
     * ── The two kinds of day ──────────────────────────────────────────────────────────────────
     *
     *  • INDEXED and fresh ([PickingsCards.isFresh] — two file stats, no decode): its cards come
     *    straight out of the sidecar. This is the ordinary case and it is nearly free.
     *  • NOT indexed, or behind the day file: the index cannot be assumed complete, because it is
     *    repaired lazily and because `CalendarWebDavSyncService.writeLocal` deliberately installs
     *    pulled day files without going near a save hook. Treating those days as empty would be
     *    exactly the silent hiding this rewrite is meant to end, so they are DECODED — through
     *    [com.toolsboox.plugin.calendar.fi.CalendarDayService.load], which repairs the sidecar as it
     *    passes, so a given day is paid for the expensive way at most once and every later opening
     *    of this picker is faster than the last.
     *
     * The decodes are budgeted at [PICK_DAY_BUDGET] per pass, because a hundred and twenty parses is
     * the wait the old picker was blamed for. What the budget did not reach is RETURNED AS A COUNT
     * ([PickingHarvest.unread]) and shown as a row the user can tap to read more — the bound is real,
     * so it is on screen, the way the directory's "…and N more" is.
     *
     * Nothing here retains a [CalendarDay]: the decoded day is scoped to one loop iteration and only
     * the index rows leave it.
     */
    private fun gatherPickingGrams(ctx: android.content.Context): PickingHarvest {
        val out = mutableListOf<PickingGram>()
        var budget = PICK_DAY_BUDGET
        var unread = 0
        // Board names are read per day, and only for days that turned out to have cards.
        val names = HashMap<LocalDate, Map<String, String>>()

        for (date in LedgerDocuments.dayDates(ctx)) {
            val cards: List<PickingsCards.Card> = if (PickingsCards.isFresh(ctx, date)) {
                PickingsCards.cards(ctx, date)
            } else if (budget > 0) {
                budget--
                val file = com.toolsboox.ot.LedgerPaths.dayFile(ctx, date)
                val day = file?.let { runCatching { calendarDayService.load(it) }.getOrNull() }
                if (day == null) emptyList() else PickingsCards.cardsIn(day)
            } else {
                unread++
                continue
            }

            // The inbox counts too: Gram Picks is where a picked gram waits, so "❝ Bring in a
            // picking…" — on Pickings, on Synthesize, on the All Stars bands — must be able to
            // draw from it, or the inbox would be a room the rest of the flow can't reach into.
            val gramPicks = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
            val boards = cards.filter {
                PickingsStore.isPickings(it.page) || it.page.substringBefore('#') == gramPicks
            }
            if (boards.isEmpty()) continue
            val byKey = names.getOrPut(date) {
                runCatching { PickingsStore.list(ctx, date).associate { it.key to it.name } }
                    .getOrNull().orEmpty()
            }
            for (c in boards) {
                val fromInbox = c.page.substringBefore('#') == gramPicks
                out.add(PickingGram(
                    date = date,
                    page = c.page,
                    id = c.id,
                    boardName = if (fromInbox) "All Picks"
                    else byKey[c.page.substringBefore('#')] ?: byKey[c.page] ?: "Pickings",
                    label = PickingsCards.labelFor(c),
                    glyph = PickingsCards.glyphFor(c),
                    boardGlyph = if (fromInbox) "◈" else "❝"
                ))
            }
        }
        return PickingHarvest(out, unread)
    }

    /**
     * The chosen card's element, read out of its day file — the only place a picking's payload is
     * ever loaded, and only ever one of them.
     *
     * This is what makes [PickingGram] able to hold nothing: the list carries identity, and identity
     * is enough until you commit. Matched on the element's own UUID rather than on the label, because
     * two clippings from the same article are the same label and the same source and a different
     * card. Null when the day cannot be read (the 86 MB defence in
     * [com.toolsboox.plugin.calendar.fi.CalendarDayService.load] returns null rather than throwing) or
     * when the index row has outlived the element — both of which the callers already say
     * "Couldn't bring that picking in" for.
     *
     * MUST be called off the main thread; both callers do.
     */
    private fun fetchPicking(ctx: android.content.Context, pick: PickingGram): ImageElement? {
        val file = com.toolsboox.ot.LedgerPaths.dayFile(ctx, pick.date) ?: return null
        val day = runCatching { calendarDayService.load(file) }.getOrNull() ?: return null
        return day.imageElements.firstOrNull {
            // Inline or ref'd — an externalized face is still a face (WIRE-MEDIA-BY-REFERENCE.md).
            it.elementId.toString() == pick.id && (it.data.isNotBlank() || it.dataRef.isNotBlank())
        }
    }

    /** File a chosen picking into the intake quarter — the starred-gram path, verbatim. */
    private fun fileIntoIntake(pick: PickingGram, kindKey: String, kindTitle: String) {
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // The payload arrives HERE, for this one card, and dies with this block — see
                    // [PickingGram] for why the picker no longer carries it.
                    val element = fetchPicking(appCtx, pick) ?: return@runCatching false
                    // Bounded decode, capped at what the placement is going to keep anyway
                    // (PickingsPlacement.MAX_DIM): the full-resolution decode that used to stand
                    // here allocated the whole source bitmap — ~53 MB for the 3150×4200 face that
                    // put a day file at 86 MB — only for `place` to immediately scale it down to
                    // 1200. The peak was the crash; the pixels were never wanted.
                    val bmp = decodeGramThumb(
                        element.data, element.dataRef, com.toolsboox.plugin.calendar.ot.PickingsPlacement.MAX_DIM
                    ) ?: return@runCatching false
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, com.toolsboox.ot.LedgerPaths.documentsRoot(appCtx), bmp,
                        currentDate, CalendarDayPageIntake.INTAKE_PAGE,
                        sourceLink = element.sourceLink, sourceLabel = pick.label,
                        // The card already wears its treatment in its own pixels — taping it
                        // twice is the one-decoration contract's whole point.
                        treatment = false, cardText = element.cardText, sourceFeed = element.sourceFeed,
                        intakeKind = kindKey
                    )
                    // Out of the inbox is a MOVE (a board keeps its card; the pile you sort out
                    // of gives it up). Disk-level here even for today: the reload below replaces
                    // the in-memory day, so the disk is the copy that has to be right.
                    if (pick.fromGramPicks) removeInboxOriginalOnDisk(appCtx, pick)
                    true
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            if (!ok) { showMessage("Couldn't bring that picking in", binding.root); return@launch }
            showMessage("Brought into $kindTitle", binding.root)
            // Reload so the page draws from the day file the placement just wrote.
            presenter.load(this@CalendarDayFragment, binding, currentDate,
                PagePrefs.settingHour(requireContext()), locale)
        }
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
