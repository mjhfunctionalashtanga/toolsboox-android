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
    // flag — the GardenDoors shape. The panel never computes on the render path; it shows what's
    // parked and lets the background walk re-earn it when the ledger's hash has moved.
    private var quickWinsShown: List<QuickWinsEngine.Win> = emptyList()
    private var quickWinsCooking = false

    // The ⚡ Quick Wins glimpse in the Roots band's bottom slice — the same parked-answer shape as
    // above, keyed to its own prefs by QuickWinsGlimpse. null = still cooking (the template draws a
    // quiet "…"); empty = nothing qualifies. The render path never computes it; the background walk
    // re-earns it and the page re-renders only when the top wins would actually change.
    private var quickWinsGlimpseShown: List<QuickWinsGlimpse.Line>? = emptyList()
    private var quickWinsGlimpseCooking = false

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

    // Exclude the floating nav + tool pills (and the notes pager) from the raw stylus reader
    // so the stylus can drag/tap them (and never inks a stray dot over them).
    override fun provideExcludeViews(): List<View> =
        if (::binding.isInitialized) listOf(binding.navWidget, binding.toolWidget, binding.notePager) else emptyList()

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
    /** Set whenever ink changes; gates the auto-capture of section zones on page-leave so an
     *  unchanged page never re-runs (and re-bills) the paid vision OCR. */
    private var sectionsDirty = false

    override fun onStrokeChanged(strokes: MutableList<Stroke>) {
        val strokesCopy = Stroke.listDeepCopy(strokes)
        if (notePage != null) {
            calendarDay.noteStrokes[notePage!!] = strokesCopy
        } else {
            calendarDay.calendarStrokes[calendarStyle] = strokesCopy
        }

        calendarPattern.updateDay(calendarDay)
        sectionsDirty = true

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
        for (el in textElements) {
            if (el.text.isBlank()) continue
            val rect = android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + 60f)
            for (tag in com.toolsboox.plugin.calendar.ot.LedgerTags.extract(el.text)) {
                com.toolsboox.plugin.calendar.ot.LedgerTags.recordTag(ctx, currentDate, pageKey, tag, rect)
            }
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
        val urlIn = android.widget.EditText(ctx).apply { hint = "Paste a link — website, video, podcast"; setSingleLine() }
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
                        calendarDayService.load(documentsRoot(), currentDate, null, java.util.Locale.getDefault())
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
        openRhizome(uri, element.sourceLabel.ifBlank { "This gram" })
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
                .show()
        }
    }

    /** What the Quick Wins panel may draw RIGHT NOW: the engine's parked answer, today only.
     *  Yesterday's panel keeps its books and events but offers no wins — a win is a "do this
     *  next", and next only exists today (the GardenDoors rule, for the GardenDoors reason). */
    private fun quickWinsForPanel(): List<QuickWinsEngine.Win> =
        if (currentDate == LocalDate.now()) QuickWinsEngine.cachedFor(currentDate).orEmpty()
        else emptyList()

    /** Re-earn the parked wins in the background — once per render, keyed by day + ledger-hash
     *  inside [QuickWinsEngine.fresh], so an unchanged ledger costs one directory walk and no
     *  compute. When the answer moves, the page re-renders with the new rows. */
    private fun warmQuickWins(events: List<CalendarEvent>) {
        if (currentDate != LocalDate.now() || quickWinsCooking) return
        quickWinsCooking = true
        val ctx = requireContext().applicationContext
        val root = documentsRoot()
        val day = currentDate
        lifecycleScope.launch {
            val wins = withContext(Dispatchers.IO) {
                runCatching {
                    QuickWinsEngine.fresh(ctx, corpusService, calendarDayService, root, day)
                }.onFailure { Timber.w(it, "quick wins: compute failed") }.getOrNull()
            }
            quickWinsCooking = false
            if (!isAdded || wins == null) return@launch
            // Redraw only when the rows would actually change — a same-answer walk must not
            // cost an e-ink flash.
            if (day == currentDate && notePage == null && wins != quickWinsShown) {
                runCatching { renderPage(calendarDay, calendarPattern, events) }
            }
        }
    }

    /** What the Quick Wins glimpse may draw RIGHT NOW: QuickWinsGlimpse's parked lines, today only.
     *  null while still cooking (the template shows a quiet "…"); empty on other days and when the
     *  ledger yields no wins. Today-only for the GardenDoors reason — a "do this next" is only ever
     *  today's, and paging back must not re-earn it. */
    private fun quickWinsGlimpseForPanel(): List<QuickWinsGlimpse.Line>? =
        if (currentDate == LocalDate.now())
            QuickWinsGlimpse.cachedFor(requireContext(), currentDate)
        else emptyList()

    /** Re-earn the parked glimpse in the background — QuickWinsGlimpse.compute reads through
     *  [QuickWinsEngine.fresh], which memoises by day + ledger-hash, so the glimpse rides the SAME
     *  walk the panel already pays for (whoever asks first pays; the other reads) — no parallel
     *  wins system. Re-renders only when the top lines would actually change, sparing an e-ink flash. */
    private fun warmQuickWinsGlimpse(events: List<CalendarEvent>) {
        if (currentDate != LocalDate.now() || quickWinsGlimpseCooking) return
        quickWinsGlimpseCooking = true
        val ctx = requireContext().applicationContext
        val root = documentsRoot()
        val day = currentDate
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching {
                    QuickWinsGlimpse.compute(ctx, corpusService, calendarDayService, root, day)
                }.onFailure { Timber.w(it, "quick wins glimpse: compute failed") }.getOrNull()
            }
            quickWinsGlimpseCooking = false
            if (!isAdded || lines == null) return@launch
            if (day == currentDate && notePage == null && lines != quickWinsGlimpseShown) {
                runCatching { renderPage(calendarDay, calendarPattern, events) }
            }
        }
    }

    // ------------------------------------------------------------------
    // The Synthesize engines — the pressure chamber of the Timeline.
    // Page-level (long-press empty canvas on the Synthesize page): the day's gathered
    // material runs through one of three transformations. Object-level ("Synthesize…"
    // on any text box, any page): the same engines on just that one picking.
    // ------------------------------------------------------------------

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
                sharedPreferences.getInt("calendarStartHour", 5), locale)
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
        // Send / Export is the SAME sheet on every making surface. It used to be Write's alone
        // ("Share essay…", three destinations); Synthesize, Pickings and Notes had no way off the
        // device but a gram share, which is why a synthesis could not become a draft without being
        // retyped somewhere else. One row, one sheet, seven destinations — see [LedgerSendExport].
        val sendExport = if (notePage != null) listOf(listOf(
            com.toolsboox.ot.LedgerContextMenu.Item("→  Send / Export…") { showSendExport() }
        )) else emptyList()
        if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage)) return bring + tagPage + sendExport + listOf(listOf(
            com.toolsboox.ot.LedgerContextMenu.Item("⚗  Synthesize the day…") {
                com.toolsboox.plugin.calendar.ot.SynthEngines.pick(ctx, "Synthesize the day") { e ->
                    runEngine(e, pageMaterial())
                }
            }
        ))
        // A named Write document gets Write's own menu (text editor, share essay) — those are the
        // tools of the surface, not of one particular key on it.
        return bring + tagPage + sendExport + when (if (com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(notePage))
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
                    com.toolsboox.plugin.calendar.ot.SynthEngines.pick(ctx, "Synthesize this page") { e ->
                        runEngine(e, thisPageMaterial())
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

    /** Just THIS page's gathered text — the page as a selection basket. */
    private fun thisPageMaterial(): String =
        currentTextElements().filter { it.text.isNotBlank() }
            .joinToString("\n") { "• ${it.text.trim()}" }.take(6000)

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
    private fun showSendExport() {
        val ctx = requireContext()
        val pageKey = notePage ?: "default"
        val document = com.toolsboox.plugin.calendar.ot.LedgerDocuments
            .documentFor(ctx, notePage, currentDate)
        val title = document?.title?.takeIf { it.isNotBlank() }
            ?: "$currentDate · ${notePage ?: "day"}"
        com.toolsboox.plugin.calendar.ot.LedgerSendExport.show(
            this,
            com.toolsboox.plugin.calendar.ot.LedgerSendExport.Payload(
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
                // as the hub row, the day chip and the directory do; a page with no document
                // surface (the day page itself) says "Day" rather than inventing a fifth name.
                surface = com.toolsboox.plugin.calendar.ot.LedgerDocuments.surfaceOf(notePage)
                    ?.let { com.toolsboox.plugin.calendar.ot.LedgerDocuments.label(it) } ?: "Day",
                date = currentDate,
                pageKey = notePage
            )
        )
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
            android.widget.Toast.makeText(ctx, "Set up the web bridge first (Boards → Web bridge…)", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Send to")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
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
                .setNegativeButton("Cancel", null).show()
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
        com.toolsboox.plugin.calendar.ot.SynthEngines.pick(ctx, "Synthesize this") { e ->
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

    private fun runEngine(engine: com.toolsboox.plugin.calendar.ot.SynthEngines.Engine, material: String) {
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
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { chatService.run(provider, key, model, engine.prompt, material) }
            when (res) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok ->
                    placeGeneratedText("${engine.name}\n\n${res.answer.trim()}")
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err ->
                    android.widget.Toast.makeText(ctx, "⚠ ${res.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
        if (notePage != null) return false
        // The Roots-band glimpse sits in its own slice above the panel; a tap there opens the ⚡
        // Quick Wins surface — where each win carries its ✧ Path to victory — matching how
        // openGardenDoor routes a band door to the surface that owns it.
        if (com.toolsboox.plugin.calendar.ot.CalendarDayPage.glimpseAt(cx, cy) != null) {
            findNavController().navigate(R.id.action_to_quick_wins)
            return true
        }
        val win = com.toolsboox.plugin.calendar.ot.CalendarDayPage.winAt(cx, cy)
        Timber.i("quick-win tap: cx=%.0f cy=%.0f hit=%s", cx, cy, win?.text ?: "∅")
        if (win == null) return false
        goToWin(win)
        return true
    }

    /** Take a win where its doing lives — and bring what's needed to do it right there.
     *  EMAIL → the compose screen, already addressed, when the win's contact carries an email
     *  address (the task words seed the subject, so the letter opens knowing what it's about);
     *  a win with no addressable person still lands in the inbox, where the mail it's probably
     *  about is waiting with its Reply.
     *  CALL → the rolodex, opened straight onto the win's contact when it carries one, so the
     *  number is on screen. HOME → the day the task lives on — UNLESS that day is the one we're
     *  already on (a today-sourced win), in which case a tap would be a silent no-op ("nothing
     *  happens, it flashes"); those open the ⚡ Quick Wins surface instead, where the win carries
     *  its ✧ Path to victory. Every navigate is guarded so a stale/absent action can't dead-end. */
    private fun goToWin(win: com.toolsboox.plugin.calendar.ot.QuickWinsEngine.Win) {
        val go = com.toolsboox.plugin.calendar.ot.QuickWinsEngine.goKind(win)
        Timber.i("goToWin: kind=%s sourceDay=%s current=%s", go, win.sourceDay, currentDate)
        runCatching {
            when (go) {
                com.toolsboox.plugin.calendar.ot.QuickWinsEngine.Go.EMAIL -> {
                    // contacts.json is one small local file — a synchronous read on a tap is the
                    // same bargain the rolodex list itself makes.
                    val contact = win.contactId?.let {
                        com.toolsboox.plugin.calendar.ot.ContactStore.get(requireContext(), it)
                    }
                    if (contact != null && contact.email.isNotBlank())
                        findNavController().navigate(
                            R.id.action_to_mail_compose,
                            androidx.core.os.bundleOf(
                                com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_TO_EMAIL to contact.email,
                                com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_TO_NAME to contact.name,
                                com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_SUBJECT to win.text.take(120)
                            )
                        )
                    else findNavController().navigate(R.id.action_to_mail_inbox)
                }
                com.toolsboox.plugin.calendar.ot.QuickWinsEngine.Go.CALL ->
                    findNavController().navigate(
                        R.id.action_to_rolodex,
                        androidx.core.os.bundleOf(RolodexFragment.ARG_CONTACT_ID to win.contactId)
                    )
                com.toolsboox.plugin.calendar.ot.QuickWinsEngine.Go.HOME ->
                    if (win.sourceDay == currentDate)
                        findNavController().navigate(R.id.action_to_quick_wins)
                    else
                        CalendarNavigator.toDayPage(this, win.sourceDay)
            }
        }.onFailure {
            // A dead nav action must never be a silent flash — fall back to the ⚡ surface.
            Timber.w(it, "goToWin: navigation failed; opening Quick Wins surface")
            runCatching { findNavController().navigate(R.id.action_to_quick_wins) }
        }
    }

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
                    DayLocks.withDay(today) {
                        // The presenter's read-only rule holds for this load→mutate→save too: a
                        // day file that exists but wouldn't load must not be saved over.
                        val day = calendarDayService.loadOrNull(root, today, null, java.util.Locale.getDefault())
                            ?: if (calendarDayService.exists(root, today)) return@withDay
                            else calendarDayService.load(root, today, null, java.util.Locale.getDefault())
                        day.ledgerItems.add(item)
                        calendarDayService.save(root, today, day)
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
            .show()
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

        val defaultStartHour = sharedPreferences.getInt("calendarStartHour", 5)
        calendarDay = CalendarDay(
            currentDate.year, currentDate.monthValue, currentDate.dayOfMonth, locale,
            mutableListOf(), mutableListOf(), true, defaultStartHour
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
            if (handleFingerLongPress(motionEvent)) return@setOnTouchListener true
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

        // The stepper walks the day's sections in RITUAL order, matching iOS (PlannerModel.cycle):
        // Day → Intake → Pickings → Gratitude → Self Executive → Synthesize → Write → the numeric
        // Notes tail. Named pickings boards and topic syntheses fold onto their base station so the
        // step never strands you on an unlisted key. Up from Day still exits to the Week almanac —
        // the Boox convention, and where the iOS ring goes too.
        fun stepStation(): String? = when {
            com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage) -> "pickings"
            com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage) -> "synthesize"
            // A named Write document folds onto the Write station the same way a named board folds
            // onto Pickings — otherwise ↑/↓ from an essay would strand you on an unlisted key.
            com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(notePage) -> "write"
            // A named sub-page (write#2) folds onto its base for the ritual step — write#2's ↑/↓
            // walk write's neighbours (synthesize ↔ write ↔ "0"), same as the base page.
            else -> baseNotePage(notePage)
        }
        binding.toolbarDrawing.toolbarSwipeUp.setOnClickListener {
            if (notePage != null) {
                when (stepStation()) {
                    "intake" -> CalendarNavigator.toDayPage(this, currentDate, CalendarDay.DEFAULT_STYLE)
                    "pickings" -> CalendarNavigator.toDayNote(this, currentDate, "intake")
                    "gratitude" -> CalendarNavigator.toDayNote(this, currentDate, "pickings")
                    "selfexec" -> CalendarNavigator.toDayNote(this, currentDate, "gratitude")
                    "synthesize" -> CalendarNavigator.toDayNote(this, currentDate, "selfexec")
                    "write" -> CalendarNavigator.toDayNote(this, currentDate, "synthesize")
                    else -> {
                        val page = baseNotePage(notePage)?.toIntOrNull() ?: 0
                        if (page == 0) {
                            CalendarNavigator.toDayNote(this, currentDate, "write")
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
                when (stepStation()) {
                    "intake" -> CalendarNavigator.toDayNote(this, currentDate, "pickings")
                    "pickings" -> CalendarNavigator.toDayNote(this, currentDate, "gratitude")
                    "gratitude" -> CalendarNavigator.toDayNote(this, currentDate, "selfexec")
                    "selfexec" -> CalendarNavigator.toDayNote(this, currentDate, "synthesize")
                    "synthesize" -> CalendarNavigator.toDayNote(this, currentDate, "write")
                    "write" -> CalendarNavigator.toDayNote(this, currentDate, "0")
                    else -> {
                        val page = baseNotePage(notePage)?.toIntOrNull() ?: 0
                        CalendarNavigator.toDayNote(this, currentDate, "${page + 1}")
                    }
                }
            } else {
                CalendarNavigator.toDayNote(this, currentDate, "intake")
            }
        }
        // Calendar button + top-left hamburger both open the consolidated Ledger hub.
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener { showLedgerHub() }
        binding.goAppsButton.visibility = View.VISIBLE
        binding.goAppsButton.setOnClickListener { showLedgerHub() }
        bindDirectoryChip()

        // Retire the fixed pen strip on the day page — the floating pills + gear now cover
        // everything. The real buttons stay in the (hidden) layout so performClick still
        // drives the Onyx ink actions; nothing about drawing changes.
        binding.toolbarDrawing.root.visibility = View.GONE

        // Floating nav widget: reuse the existing nav actions so nothing about drawing
        // changes. ↑ ↓ step the day's sections (dates live on the top bar's ‹ ›).
        binding.navWidget.visibility = View.VISIBLE
        binding.navUp.setOnClickListener { binding.toolbarDrawing.toolbarSwipeUp.performClick() }
        binding.navDown.setOnClickListener { binding.toolbarDrawing.toolbarSwipeDown.performClick() }

        // Inline ‹ N › pager. Two families carry it, never the ritual stations:
        //  • Numeric notes ("0","1",…): ‹ › reuse the stepper's ↑/↓ (which walk the numeric tail),
        //    and tapping the number opens the jump picker.
        //  • The named write / grid / sketch surfaces: these get their OWN sub-page series keyed
        //    "<base>#<n>". Their ‹ › must NOT run through the stepper (that walks sections) — next
        //    always EXTENDS to the next sub-page, prev floors at the base. The number is subIndex+1.
        val pagerBase = baseNotePage(notePage)
        if (notePage?.toIntOrNull() != null) {
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.VISIBLE
            binding.notePagerNext.visibility = View.VISIBLE
            binding.notePagerLabel.text = ((notePage?.toIntOrNull() ?: 0) + 1).toString()   // 1-indexed, matches the header
            binding.notePagerPrev.setOnClickListener { binding.toolbarDrawing.toolbarSwipeUp.performClick() }
            binding.notePagerNext.setOnClickListener { binding.toolbarDrawing.toolbarSwipeDown.performClick() }
            binding.notePagerLabel.setOnClickListener { showNotePageJump() }
        } else if (isSubPageableBase(pagerBase)) {
            val base = pagerBase!!
            val sub = notePageSubIndex(notePage)
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.VISIBLE
            binding.notePagerNext.visibility = View.VISIBLE
            binding.notePagerLabel.text = (sub + 1).toString()
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
        } else if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(baseNotePage(notePage))) {
            // A NAMED synthesis topic ("synthesize-1753…") is deliberately not sub-pageable — see
            // [isSubPageableBase], which must stay in step with what the almanac-as-filter counts as
            // a notes surface. That left it the one document surface with no ‹ N › control at all,
            // so the topic pages Michael actually keeps his synthesis work on were the only pages
            // in the app you could not name from. It gets Pickings' answer instead: a single chip,
            // no linear ‹ ›, opening the same menu — which knows not to offer pages to a base that
            // has none.
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.GONE
            binding.notePagerNext.visibility = View.GONE
            binding.notePagerLabel.text = com.toolsboox.plugin.calendar.ot.LedgerDocuments
                .glyph(com.toolsboox.plugin.calendar.ot.LedgerDocuments.SYNTHESIZE)
            binding.notePagerLabel.isClickable = true
            binding.notePagerLabel.setOnClickListener {
                showNoteSubPageJump(baseNotePage(notePage) ?: return@setOnClickListener)
            }
        } else if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage)) {
            // Pickings carries the same directory affordance beneath the almanac nav: a single ❝
            // chip (no linear ‹ › series — boards aren't an ordered run) that opens the boards
            // directory (every board + New/Rename), the Text-Notes "≡ Notes" cohesion.
            binding.notePager.visibility = View.VISIBLE
            binding.notePagerPrev.visibility = View.GONE
            binding.notePagerNext.visibility = View.GONE
            binding.notePagerLabel.text = "❝"
            binding.notePagerLabel.isClickable = true
            // The day you're on and the board you're in — the pager's chip used to pass neither, so
            // it reopened the same "today's boards" list the day chip above was fixed to stop giving.
            binding.notePagerLabel.setOnClickListener {
                com.toolsboox.plugin.feeds.ui.showPickingsPicker(this, currentDate, notePage)
            }
        }

        // Floating tool selector: each button drives the real (hidden) toolbar action,
        // so the Onyx ink wiring is unchanged. The active tool is marked on the pill so
        // you can always tell what the stylus is doing. Long-press the eraser to clear.
        binding.toolWidget.visibility = View.VISIBLE
        binding.toolPen.setOnClickListener { binding.toolbarDrawing.toolbarPen.performClick(); markActiveTool(binding.toolPen) }
        // Long-press the pen → pick ballpoint vs calligraphy (shows the active one).
        binding.toolPen.setOnLongClickListener { showPenStylePicker(); true }
        binding.toolEraser.setOnClickListener { binding.toolbarDrawing.toolbarEraser.performClick(); markActiveTool(binding.toolEraser) }
        binding.toolEraser.setOnLongClickListener {
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                .setTitle(R.string.calendar_drawing_toolbar_eraser)
                .setItems(arrayOf(getString(R.string.eraser_clear_page))) { d, _ ->
                    binding.toolbarDrawing.toolbarTrash.performClick(); d.dismiss()
                }
                .show()
            true
        }
        binding.toolLasso.setOnClickListener { binding.toolbarDrawing.toolbarLasso.performClick(); markActiveTool(binding.toolLasso) }
        binding.toolUndo.setOnClickListener { binding.toolbarDrawing.toolbarUndo.performClick() }
        binding.toolRedo.setOnClickListener { binding.toolbarDrawing.toolbarRedo.performClick() }
        // Wrench now lives on the tool pill (quick tools/layout menu).
        binding.toolGear.setOnClickListener { showWidgetGearMenu() }

        // Center pill button (between ↑ ↓) shows the CURRENT section's emoji. Tapping it
        // jumps to TODAY's version of the section you're on; once you're already on today,
        // a further tap opens the Ledger hub (the iPad feed-carrot behavior).
        binding.navGoto.setImageResource(sectionIcon())
        binding.navGoto.setOnClickListener { onCenterTapped() }
        // Long-press the center button → open the section menu directly (tap = today-then-menu).
        binding.navGoto.setOnLongClickListener { showSectionSwitcher(); true }
        applyWidgetOrientation()

        // Lift the floating overlays above the drawing surface without elevation (which
        // renders as an ugly black shadow-box on e-ink).
        binding.goAppsButton.bringToFront()
        binding.toolWidget.bringToFront()
        binding.navWidget.bringToFront()
        binding.notePager.bringToFront()

        // Repositionable pills: drag the grip to move a pill anywhere (persisted).
        // A plain tap on the grip collapses/expands the pill (grip = the obvious handle).
        makeDraggable(binding.toolGrip, binding.toolWidget, "tool") { togglePill("tool") }
        makeDraggable(binding.navGrip, binding.navWidget, "nav") { togglePill("nav") }

        // Collapse/expand is entirely on the grip (tap the handle) — no separate carets.
        applyPillCollapse()
        // Pen is the default tool — reflect that on the pill from the start.
        markActiveTool(binding.toolPen)

        utils.updateToolbar(binding)
        // Inset the date bar so the top-left hamburger sits in its own gutter (no caret overlap).
        (binding.navigatorImageView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = (52 * resources.displayMetrics.density).toInt()
            binding.navigatorImageView.layoutParams = it
        }
        initializeSurface(true)
    }

    /**
     * The pager's jump picker: every numbered page holding content (ink or placed elements),
     * sorted, plus a "New page" row that lands one past the last.
     */
    /** Navigate notes by DATE: a date picker that opens the same note page on the chosen day. */
    private fun showNoteDatePicker() {
        val d = currentDate
        android.app.DatePickerDialog(requireContext(), { _, y, m, day ->
            CalendarNavigator.toDayNote(this, java.time.LocalDate.of(y, m + 1, day), notePage ?: "0")
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun showNotePageJump() {
        val ctx = context ?: return
        val pages = sortedSetOf<Int>()
        if (::calendarDay.isInitialized) {
            calendarDay.noteStrokes.filterValues { it.isNotEmpty() }.keys.mapNotNullTo(pages) { it.toIntOrNull() }
            calendarDay.imageElements.mapNotNullTo(pages) { it.page.toIntOrNull() }
        }
        notePage?.toIntOrNull()?.let { pages.add(it) }   // the page you're on always lists
        val newPage = (pages.maxOrNull() ?: -1) + 1
        val ordered = pages.toList()

        // THIS PAGE'S TAGS, first and tappable.
        //
        // The tags were already drawn in the top margin, but painted into the page canvas with
        // nothing to touch — and drawn as one ellipsized line, so past a few they aren't even
        // visible. They lead the directory instead: tapping one zooms to the mark it was written
        // at (the same focusOnRect the tag index lands with), and the list shows every tag whether
        // or not the drawn strip had room for it. iOS carries these as chips under the almanac bar;
        // on e-ink the directory is where a page's sub-navigation already lives.
        val marks = com.toolsboox.plugin.calendar.ot.LedgerTags
            .marksOn(ctx, currentDate, notePage ?: "default")

        // …AND THE SHELF. Every other note surface's ‹ N › ends on "🗂 All …" — the door out of
        // this page's own pages and into everything you have. Notes was the one that didn't, so
        // from the surface you write on most there was no way to reach the directory at all.
        // Michael, on the Boox: "the notes don't look like they have a directory."
        //
        // It opens the root rather than a Notes-only list because Notes has no document store to
        // list — a numbered page is not a named thing — so the honest destination is the shelf
        // itself, where Notes is a folder of days beside the kinds that do have names.
        val labels = (marks.map { "#${it.first}" }
            + ordered.map { if (it.toString() == notePage) "Page ${it + 1}  ·  here" else "Page ${it + 1}" }
            + "＋  New page" + "📅  Go to a date…" + "🗂  All notes & documents…").toTypedArray()
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Jump to")
            .setItems(labels) { _, which ->
                val p = which - marks.size
                when {
                    which < marks.size -> {
                        // No rect (a legacy occurrence) → nothing to zoom to; the page is already open.
                        marks[which].second?.let { focusOnRect(it) }
                    }
                    p < ordered.size -> {
                        val target = ordered[p]
                        if (target.toString() != notePage) CalendarNavigator.toDayNote(this, currentDate, target.toString())
                    }
                    p == ordered.size -> CalendarNavigator.toDayNote(this, currentDate, newPage.toString())
                    p == ordered.size + 1 -> showNoteDatePicker()   // navigate notes BY DATE
                    else -> com.toolsboox.plugin.feeds.ui.showLedgerRootDirectory(this, currentDate)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
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

        // This page's tags lead the directory here too — see showNotePageJump. Every making
        // surface answers "what's on this page" the same way.
        for ((tag, rect) in
            com.toolsboox.plugin.calendar.ot.LedgerTags.marksOn(ctx, currentDate, notePage ?: base)) {
            rows += "#$tag" to { rect?.let { focusOnRect(it) }; Unit }
        }

        if (surface != null) {
            // The shelf, capped. A man with sixty essays should not meet all sixty in a menu he
            // opened to turn a page — and needn't, because "🗂 All …" at the foot is the same shelf
            // with a sort and a filter field on it. The document you're standing in leads the list
            // whatever the cap, so the menu can always answer "which one am I in".
            val all = docs0.forSurface(ctx, surface, currentDate, knownNotePageKeys())
            val shown = (all.filter { it.key == base } + all.filterNot { it.key == base })
                .take(DOCUMENTS_IN_PAGER_MENU)
            for (d in shown) {
                val label = docs0.glyph(surface) + "  " + d.title + if (d.key == base) "  ·  here" else ""
                rows += label to { if (d.key != base) CalendarNavigator.toDayNote(this, d.date, d.key) }
            }
        }

        for (s in ordered) {
            rows += ("Page ${s + 1}" + if (s == here) "  ·  here" else "") to {
                if (s != here) CalendarNavigator.toDayNote(this, currentDate, keyFor(s))
            }
        }
        if (paged) {
            rows += "＋  New page" to { CalendarNavigator.toDayNote(this, currentDate, keyFor(newSub)) }
        }

        // The document you are standing in, resolved once: the naming rows need to know whether it
        // has a title (so "Remove the title" isn't offered on one that has none) and the heading
        // needs the title itself.
        val doc = docs0.documentFor(ctx, base, currentDate, knownNotePageKeys())

        if (surface != null) {
            rows += "＋  New ${docs0.noun(surface)}…" to {
                com.toolsboox.plugin.feeds.ui.promptNewDocument(this, surface, currentDate)
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
            rows += "🗂  All notes & documents…" to {
                com.toolsboox.plugin.feeds.ui.showLedgerRootDirectory(this, currentDate)
            }
        }

        // A named Write document is titled by its NAME here, not by its raw key — "write-1753…" as
        // a dialog title tells you nothing, and the document's title is the whole point of naming
        // it. An UNnamed document keeps the surface's own name ("Write"), because the date default
        // is already the day the whole screen is showing and repeating it says nothing.
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
        dialog.show()
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    /** The page keys this day already holds, so a document's page count comes off the day in hand
     *  rather than a second streamed read of the file. Empty before the day has loaded, which
     *  [com.toolsboox.plugin.calendar.ot.LedgerDocuments.subPageCount] correctly reads as "go and
     *  ask the file". */
    private fun knownNotePageKeys(): Set<String> =
        if (!::calendarDay.isInitialized) emptySet()
        else calendarDay.noteStrokes.keys + calendarDay.imageElements.map { it.page }

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
        CalendarDayPageNotes.drawPage(requireContext(), templateCanvas, calendarDay, noteTemplate, page)
        binding.templateImageView.invalidate()
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
    /**
     * The spiral, as one line at the foot of the page.
     *
     * Something you made once, come back around — and, when it can say so, what you have been
     * circling that brought it back. One line rather than a card because the day page is a
     * writing surface first: the full-card version crowded it so badly the page stopped reading
     * as a page. The whole breakdown lives on the Roots screen; this is only the door to it.
     *
     * It stays hidden when nothing qualifies, so a thin ledger shows an empty page rather than an
     * apology.
     */
    private fun showSpiralLine() {
        binding.spiralLine.visibility = View.GONE
        // Once a day is enough. Choosing a pick walks EVERY day file — a hundred megabytes here —
        // and this ran on every resume of the day page, which is every page turn, since paging
        // navigates and builds a new fragment. The answer only changes when the ledger does, and
        // the ring below keeps the last few, so re-deriving it on each turn was pure cost.
        val ctxForCache = context ?: return
        val cachePrefs = ctxForCache.getSharedPreferences("ledger_spiral_ring", 0)
        // The stamp keys to TODAY, not the viewed date: keyed to the page, flipping back
        // through history re-ran the choose per page turn and pushed stale picks into the
        // widget's ring.
        val stamp = LocalDate.now().toString()
        val fresh = cachePrefs.getString("picked_on", "") != stamp
        // The day page only. The band it sits in is drawn by CalendarDayPage between Tasks and
        // Stars & Events; a note page is bare paper, so the line landed in the middle of nothing
        // and looked like a stray caption. Same fragment draws both, which is how it got there.
        if (currentNotePage() != null) return
        val ctx = context ?: return
        // Already chosen for this day: show what the ring is holding rather than walking the ledger
        // again. Same entry the widget shows, which is a feature — the page and the home screen
        // agreeing is less confusing than each having its own idea.
        //
        // Falls THROUGH when the ring is empty. The stamp and the ring live in the same prefs but
        // are not the same fact: a stamp saying "picked today" with nothing behind it drew an
        // empty band all day, with no way out of it.
        // A past page shows what was already picked — the ring's holding — and never
        // recomputes or pushes; the choose walk and the widget ring belong to today only.
        val today = currentDate == LocalDate.now()
        val cached = if (fresh && today) null else com.toolsboox.plugin.calendar.ot.SpiralRing.next(ctxForCache)
        if (cached != null) {
            spiralPick = RootsLine(cached.text, emptyList(), cached.citation)
        } else if (today) {
            lifecycleScope.launch {
                val chosen = withContext(Dispatchers.IO) {
                    runCatching {
                        val all = corpusService.gather(
                            documentsRoot(), com.toolsboox.plugin.calendar.ot.Spiral.SCOPE)
                            .filter { com.toolsboox.plugin.calendar.ot.Spiral.isSubstantial(it.text) }
                            .let { com.toolsboox.plugin.calendar.ot.Spiral.dedupe(it) { s -> s.text } }
                        com.toolsboox.plugin.calendar.ot.Spiral.choose(
                            ctx, all,
                            textOf = { it.text + " " + it.title },
                            dateOf = { it.date.time },
                            keyOf = { com.toolsboox.plugin.calendar.ot.Spiral.keyOf(it.citation, it.text) },
                            ownOf = { it.own }
                        )
                    }.onFailure { Timber.w(it, "spiral: choose failed") }.getOrNull()
                }
                if (!isAdded || chosen == null) return@launch
                cachePrefs.edit().putString("picked_on", stamp).apply()
                val snippet = chosen.item.text.replace(Regex("\\s+"), " ").trim()
                if (snippet.isEmpty()) return@launch
                // Held, because how much of it can be SHOWN depends on the band's size on screen,
                // which changes with zoom — so the text has to be rebuilt, not merely re-clipped.
                spiralPick = RootsLine(snippet, chosen.shared, chosen.item.citation)
                // Feed the widget's ring. It can't choose a pick itself — that means walking every day
                // file — so the app hands over what it chose and the home screen rotates through them.
                com.toolsboox.plugin.calendar.ot.SpiralRing.push(
                    requireContext().applicationContext, snippet, chosen.item.citation)
                refreshBandVisibility()
            }
        }
        wireBandTouch()
        showGardenDoors(ctx)
        refreshBandVisibility()
    }

    /**
     * The other two garden doors — 🌱 the day's sprout and ⁂ its missed rhizome — read from
     * [com.toolsboox.plugin.calendar.ot.GardenDoors]' parked answer, never computed here: the
     * choosing means embeddings, and the day page's render path must not wait on a network.
     * Cold cache kicks the choice off in the background and the band shows its quiet "…" until
     * the answer lands. TODAY only — flipping back through old days must not re-run embeddings
     * per page turn, and yesterday's doors were yesterday's.
     */
    private fun showGardenDoors(ctx: android.content.Context) {
        if (currentDate != java.time.LocalDate.now()) { gardenDoors = null; return }
        gardenDoors = com.toolsboox.plugin.calendar.ot.GardenDoors.cachedFor(ctx, currentDate)
        if (gardenDoors != null || gardenDoorsCooking) return
        gardenDoorsCooking = true
        val appCtx = ctx.applicationContext
        val root = documentsRoot()
        val day = currentDate
        lifecycleScope.launch {
            val doors = withContext(Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.GardenDoors.compute(appCtx, corpusService, root, day)
                }.onFailure { Timber.w(it, "garden doors: compute failed") }.getOrNull()
            }
            gardenDoorsCooking = false
            if (!isAdded) return@launch
            if (day == currentDate) gardenDoors = doors
            refreshBandVisibility()
        }
    }

    /** The band shows when it has ANY door (or is still cooking one); a thin ledger keeps its
     *  empty page rather than an apology. One place decides, so the pick paths can't disagree. */
    private fun refreshBandVisibility() {
        if (!isAdded || currentNotePage() != null) return
        val any = spiralPick != null || gardenDoors?.rooted != null || gardenDoors?.tag != null ||
            gardenDoors?.sprout != null || gardenDoors?.missed != null || gardenDoorsCooking
        binding.spiralLine.visibility = if (any) View.VISIBLE else View.GONE
        if (any) positionSpiralLine()
    }

    /**
     * The band's finger discipline, the pickings-cover way: a stylus touch is consumed without
     * a click (the band is page furniture, not paper — pen marks here would be strays), a finger
     * tap resolves to the DOOR under it via the laid-out text, and the hold gets the same Pick
     * menu the garden surfaces carry. The down-point is recorded because click/long-click fire
     * with no coordinates of their own.
     */
    @Suppress("ClickableViewAccessibility")
    private fun wireBandTouch() {
        val v = binding.spiralLine
        v.setOnTouchListener { _, ev ->
            val tool = ev.getToolType(0)
            if (tool != MotionEvent.TOOL_TYPE_FINGER && tool != MotionEvent.TOOL_TYPE_UNKNOWN)
                return@setOnTouchListener true
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                bandTouchX = ev.x; bandTouchY = ev.y
            }
            false
        }
        v.setOnClickListener { openGardenDoor(bandDoorAt(bandTouchX, bandTouchY)) }
        v.setOnLongClickListener {
            val door = bandDoorAt(bandTouchX, bandTouchY) ?: return@setOnLongClickListener false
            gardenDoorHoldMenu(door)
            true
        }
    }

    /** Which door the finger landed on — resolved through the TextView's own layout, so the hit
     *  zones are exactly the characters each door occupies, at any zoom. Null between doors. */
    private fun bandDoorAt(x: Float, y: Float): Any? {
        val v = binding.spiralLine
        val layout = v.layout ?: return null
        val line = layout.getLineForVertical((y - v.totalPaddingTop).toInt().coerceAtLeast(0))
        val off = layout.getOffsetForHorizontal(line, (x - v.totalPaddingLeft).coerceAtLeast(0f))
        return doorRanges.firstOrNull { off in it.first }?.second
    }

    /** Walk through a door: each entry opens the surface that owns it; the band's dead space
     *  keeps the old contract and opens Roots, where the whole breakdown lives. */
    private fun openGardenDoor(door: Any?) {
        val gd = door as? com.toolsboox.plugin.calendar.ot.GardenDoors.Door
        when (gd?.kind) {
            // The rising door names a move — "weave a synthesis?" — so its tap STARTS that move:
            // a fresh named Synthesis page for the tag, pre-seeded with the pages it took root on.
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_ROOTED -> weaveRootedSynthesis(gd)
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_SPROUT ->
                findNavController().navigate(R.id.action_to_sprouts)
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_MISSED ->
                findNavController().navigate(R.id.action_to_missed_rhizomes)
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_TAG,
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_TAG2 ->
                findNavController().navigate(R.id.action_to_seeds)
            else -> findNavController().navigate(R.id.action_to_ledger_roots)
        }
    }

    /**
     * Tap the rising door → begin the synthesis it invites. The rooted `#tag` is exactly ripe to
     * weave, so this opens a fresh named [SynthPageStore] page titled with the tag and pre-seeds it
     * with the tag's rhyme and the pages it took root on — the material to pull together — then
     * lands you on it to work. Reuses the existing synthesis seam ([SynthPageStore.add] +
     * [placeTextBoxes] + the day-note surface), never a parallel one. Off-render, cheap: the
     * occurrences and rhyme are graph reads already in prefs.
     */
    private fun weaveRootedSynthesis(gd: com.toolsboox.plugin.calendar.ot.GardenDoors.Door) {
        val ctx = context ?: return
        val tag = gd.key.removePrefix("tag://").ifBlank { return }
        lifecycleScope.launch {
            val seed = withContext(Dispatchers.IO) {
                val page = com.toolsboox.plugin.calendar.ot.SynthPageStore.add(ctx, "#$tag", currentDate)
                val occ = com.toolsboox.plugin.calendar.ot.LedgerTags.pagesFor(ctx, tag)
                val rhyme = com.toolsboox.plugin.calendar.ot.LedgerTags
                    .relatedTags(ctx, tag).firstOrNull()?.first
                val lines = buildList {
                    add("Synthesize #$tag — it just took root.")
                    if (rhyme != null) add("Rhymes with #$rhyme — what's the through-line?")
                    for ((date, pageKey, _) in occ.take(8)) add("• $date · $pageKey")
                }
                page.key to lines
            }
            if (!isAdded) return@launch
            placeTextBoxes(seed.second, seed.first, refresh = false)
            showMessage("Started a synthesis for #$tag — the pages it rooted on are on the page.", binding.root)
            CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, seed.first)
        }
    }

    /** Hold on a door: the same Pick the garden surfaces carry — the medium chooser with the
     *  item's text as the quote in hand — plus its surface and the hard removal. */
    private fun gardenDoorHoldMenu(door: Any) {
        val gd = door as? com.toolsboox.plugin.calendar.ot.GardenDoors.Door
        val root = door as? RootsLine
        val text = gd?.text ?: root?.snippet ?: return
        val origin = gd?.origin?.ifBlank { null }
            ?: root?.citation?.substringAfter("· ", "")?.trim().orEmpty()
        // The surface a door leads to. For the rising door, tap already STARTS the synthesis, so the
        // hold-menu's surface is the tag's own bed (Seeds) rather than re-running the weave.
        val surfaceLabel = when (gd?.kind) {
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_ROOTED -> "🌰  Open Seeds"
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_SPROUT -> "🌱  Open Sprouts"
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_MISSED -> "✧  Open Missed Rhizomes"
            com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_TAG -> "🌰  Open Seeds"
            else -> "🌿  Open Roots"
        }
        val surfaceAction: () -> Unit =
            if (gd?.kind == com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_ROOTED)
                { { findNavController().navigate(R.id.action_to_seeds) } }
            else { { openGardenDoor(door) } }
        showIconMenu(text.take(80), listOf(
            "⁂  Pick — make it a gram" to {
                com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
                    this, calendarDayService, documentsRoot(),
                    itemText = text.take(600),
                    originLabel = origin.ifBlank { "from your roots" }.take(80),
                    sourceUrl = gd?.url.orEmpty()
                ) { kind, sink -> captureAvGramDirect(kind, sink) }
            },
            surfaceLabel to surfaceAction,
            "🗑  Remove from corpus" to { removeGardenDoor(door) }
        ))
    }

    /** Tombstone the thing behind a door and close the door now — the root re-picks fresh (the
     *  gather refuses tombstones), the computed doors simply stand empty for the day. */
    private fun removeGardenDoor(door: Any) {
        val ctx = context ?: return
        val appCtx = ctx.applicationContext
        val gd = door as? com.toolsboox.plugin.calendar.ot.GardenDoors.Door
        val key = gd?.key ?: (door as? RootsLine)?.citation ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { corpusService.exclude(key) }
                if (gd != null) com.toolsboox.plugin.calendar.ot.GardenDoors.drop(appCtx, gd.kind)
            }
            if (!isAdded) return@launch
            if (gd != null) {
                gardenDoors = when (gd.kind) {
                    com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_ROOTED -> gardenDoors?.copy(rooted = null)
                    com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_SPROUT -> gardenDoors?.copy(sprout = null)
                    com.toolsboox.plugin.calendar.ot.GardenDoors.KIND_TAG -> gardenDoors?.copy(tag = null)
                    else -> gardenDoors?.copy(missed = null)
                }
            } else {
                // A fresh choice, now that the gather refuses this one.
                ctx.getSharedPreferences("ledger_spiral_ring", 0).edit().putString("picked_on", "").apply()
                spiralPick = null
                showSpiralLine()
            }
            refreshBandVisibility()
            showMessage("Removed from the corpus — it won't be offered again", binding.root)
        }
    }

    /**
     * What the Roots band says: up to three garden doors, one line each.
     *
     * 🌿 the root (what keeps coming back — the spiral's pick), 🌱 the sprout (an emergent
     * crossing), ⁂ the missed rhizome (a connection through something unpicked). The glyph
     * leads, the passage carries the line at full size and black — the doors are for reading,
     * not decoration — and the provenance rides small and grey at the end, first overboard when
     * the line is tight. Spare lines go to the root's passage, because the passage is the point.
     *
     * Bodies are trimmed at a sentence end where there is one and at a word boundary otherwise —
     * never mid-word. A fragment cut at "the patchwork of cross-bor…" reads as something broken;
     * the same passage ended at a full stop reads as a quotation, and each line is one tap from
     * the surface that holds all of it anyway.
     *
     * Side effect: rebuilds [doorRanges], the char-span → door map the band's taps resolve
     * against — recorded at build time, hit-tested on tap, exactly the pickings-cover pattern.
     */
    private fun bandText(lines: Int, charsPerLine: Int): CharSequence {
        val doors = ArrayList<Pair<String, Any>>()
        // The rising door LEADS — a `#tag` that just took root is the one thing that MOVED today, so
        // it takes the top slot when a fresh promotion exists. It is the sharp form of the tag door
        // (same rising-tag family, same 🌰), so when it shows, the perennial tag door steps down —
        // one 🌰 line, not two — and the band varies day to day instead of repeating the steady best.
        val rooted = gardenDoors?.rooted
        rooted?.let { doors += "🌰" to it }
        spiralPick?.let { doors += "🌿" to it }
        // The hot tag rides behind the root — the graph-grounded door that stands even without
        // embeddings — but only when no fresher rooted door has already spoken for that family.
        if (rooted == null) gardenDoors?.tag?.let { doors += "🌰" to it }
        gardenDoors?.sprout?.let { doors += "🌱" to it }
        gardenDoors?.missed?.let { doors += "⁂" to it }
        // Thin day: fill an open slot with the runner-up hot tag (take(lines) below caps it) so the
        // band reads full instead of leaving a gap.
        gardenDoors?.tag2?.let { doors += "🌰" to it }

        val text = android.text.SpannableStringBuilder()
        if (doors.isEmpty()) {
            // Still cooking (refreshBandVisibility only shows the band when something is, or
            // will be, here) — a quiet ellipsis, not an apology.
            doorRanges = emptyList()
            text.append("…")
            text.setSpan(android.text.style.ForegroundColorSpan(0xFF888888.toInt()), 0, text.length, 0)
            return text
        }

        val shown = doors.take(lines.coerceAtLeast(1))
        val spare = (lines - shown.size).coerceAtLeast(0)
        val ranges = ArrayList<Pair<IntRange, Any>>()
        for ((i, pair) in shown.withIndex()) {
            val (glyph, door) = pair
            val isRoot = door is RootsLine
            val rowLines = 1 + if (isRoot) spare else 0
            val (rawBody, rawTail) = when (door) {
                is RootsLine -> door.snippet to door.citation.substringAfter("· ", "").trim()
                is com.toolsboox.plugin.calendar.ot.GardenDoors.Door -> door.text to door.origin
                else -> "" to ""
            }
            val tail = if (rawTail.isBlank()) "" else "  — $rawTail"
            // When there genuinely isn't room for both, the TAIL goes — not the passage.
            val room = rowLines * charsPerLine - 3                 // the glyph and its gap
            val shownTail = if (room - tail.length >= 24) tail else ""
            val body = trimToWhole(
                rawBody.replace(Regex("\\s+"), " ").trim(),
                (room - shownTail.length).coerceAtLeast(12))

            if (i > 0) text.append("\n")
            val start = text.length
            text.append(glyph).append("  ").append(body)
            val tailStart = text.length
            text.append(shownTail)
            if (shownTail.isNotEmpty()) {
                text.setSpan(android.text.style.RelativeSizeSpan(0.82f), tailStart, text.length, 0)
                text.setSpan(android.text.style.ForegroundColorSpan(0xFF888888.toInt()), tailStart, text.length, 0)
            }
            ranges += (start until text.length) to door
        }
        doorRanges = ranges
        return text
    }

    /**
     * [text] cut to at most [limit] characters, ending somewhere a reader would stop.
     *
     * Prefers the last sentence end past halfway — so the result is a whole thought — and falls
     * back to the last word boundary with an ellipsis. Returns the text untouched when it already
     * fits, so a short note never acquires a "…" it doesn't need.
     */
    private fun trimToWhole(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val window = text.take(limit)
        val sentence = window.indexOfLast { it == '.' || it == '!' || it == '?' }
        if (sentence > limit / 2) return window.take(sentence + 1)
        val space = window.lastIndexOf(' ')
        return (if (space > limit / 2) window.take(space) else window).trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * What the Roots band is showing.
     *
     * Deliberately NOT the Spiral's own Pick: the band is filled from two places — a fresh choice,
     * and the cached ring — and when they carried different shapes the cached one bypassed the
     * whole fitting pass, so its text kept the XML's line limit and was sliced by the panel below.
     * One shape means one path through the measuring.
     */
    private data class RootsLine(val snippet: String, val shared: List<String>, val citation: String)
    private var spiralPick: RootsLine? = null

    /** Today's computed doors (🌱/⁂), null while cold; the flag keeps a slow compute single. */
    private var gardenDoors: com.toolsboox.plugin.calendar.ot.GardenDoors.Doors? = null
    private var gardenDoorsCooking = false

    /** Char-span → door, rebuilt by [bandText] — the band's tap zones, in text space. */
    private var doorRanges: List<Pair<IntRange, Any>> = emptyList()
    private var bandTouchX = 0f
    private var bandTouchY = 0f

    /**
     * The Roots band's paper, in design space (1404×1872).
     *
     * Between the Tasks grid — which now ends at row 14 — and the Stars & Events title at
     * `to + 18*ceh`. The template paints the band's title bar and its closing rule; this is the
     * space left between them for the line itself. Kept next to those numbers on purpose: if
     * `CalendarDayPage` moves the band, this has to move with it or the text lands on a rule.
     */
    private val rootsBand = android.graphics.RectF(
        20f + 645f + 60f,                       // lo + cew + 60 — the text inset the panels use
        (1872f - 35 * 50f) / 2f + 14 * 50f,     // to + 14*ceh, under the title bar
        20f + 2 * 645f + 50f - 10f,             // lo + 2*cew + 50, less a hair of right margin
        // to + 18*ceh — the doors now own the WHOLE Roots band (rows 14→18). The ⚡ Quick Wins
        // glimpse moved OUT to its own section below the row-18 title bar (it was splitting the band).
        (1872f - 35 * 50f) / 2f + 18f * 50f
    )

    /**
     * Put the spiral line on its patch of paper.
     *
     * The line is a view over a drawn surface, so it can't be positioned by constraints — it has
     * to follow the same transform the ink does, or it drifts off the band the moment the page is
     * zoomed or the toolbar changes sides. Mapping the band through the surface's own matrix is
     * the only thing that stays right in every case, since that matrix IS what "where the page is
     * on screen" means here.
     */
    private fun positionSpiralLine() {
        if (!isAdded) return
        if (currentNotePage() != null) {
            binding.spiralLine.visibility = View.GONE
            return
        }
        val v = binding.spiralLine
        if (v.visibility != View.VISIBLE) return
        val mapped = android.graphics.RectF(rootsBand)
        surfaceTransform().mapRect(mapped)
        if (mapped.width() < 1f || mapped.height() < 1f) return
        // The matrix maps into the SURFACE's coordinates; the overlay is a sibling in the parent,
        // so it needs the surface's own offset added or it lands a toolbar's width to the left.
        mapped.offset(binding.surfaceView.left.toFloat(), binding.surfaceView.top.toFloat())
        // Bound by maxWidth rather than by layoutParams: the ConstraintSet in CalendarUtils is
        // re-applied on load and would overwrite an explicit width/height straight back to
        // WRAP_CONTENT, leaving the line laid out at nothing. maxWidth survives that.
        v.maxWidth = mapped.width().toInt()
        v.translationX = mapped.left
        v.translationY = mapped.top
        // Sized in PAGE space, like everything else drawn on this page.
        //
        // 25 design units is `Creator.textSmallBlack` — the weather-and-moon line under Stars &
        // Events. Matching it is the whole point: the spiral line is page furniture, so it should
        // be the size the page's own small text is, and grow and shrink with the paper rather
        // than to some independent rule. Scaled by ReadingSize on top, so "make text bigger"
        // still means something here.
        //
        // Because the size scales with the band, the same amount of text fits at every zoom —
        // which is duller than it sounds and much better: what you see doesn't depend on how far
        // you happen to be zoomed in.
        val pageScale = mapped.height() / rootsBand.height()
        val size = 25f * pageScale * com.toolsboox.ot.ReadingSize.scale(requireContext())
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)

        val padding = (v.paddingTop + v.paddingBottom).toFloat()
        val available = mapped.height() - padding
        val innerWidth = (mapped.width() - v.paddingStart - v.paddingEnd).toInt().coerceAtLeast(1)

        // MEASURE, don't estimate.
        //
        // Two guesses at this were both wrong — size × 1.25, then the font's own `fontSpacing` —
        // and both left the last line sliced by the Stars & Events bar. The honest answer is to
        // lay the text out and ask where each line actually ends: `getLineBottom` is the real
        // number, and no arithmetic on font metrics reproduces it reliably once wrapping and
        // spans are involved.
        //
        // Two passes, because the text depends on how many lines there are and the lines depend
        // on the text: build it generously, measure what fits, then build it again for that.
        fun linesThatFit(text: CharSequence): Int {
            val layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, v.paint, innerWidth).build()
            var n = layout.lineCount
            while (n > 1 && layout.getLineBottom(n - 1) > available) n--
            return n
        }
        v.maxHeight = mapped.height().toInt()                // the band, and not a pixel more

        // Characters per line from the font's own average width, not a magic 0.52 multiplier.
        val perLine = (innerWidth / v.paint.measureText("n").coerceAtLeast(1f))
            .toInt().coerceIn(8, 200)

        // Pass one: as much as could plausibly fit, so the measurement sees real wrapping.
        val fits = linesThatFit(bandText(6, perLine))
        // Pass two: built for the room there actually is, so a door's body shortens rather than
        // the door below it falling off the bottom. This second build is also the one whose
        // doorRanges the taps resolve against — built for exactly the text on screen.
        v.text = bandText(fits, perLine)
        v.maxLines = fits
        v.requestLayout()
    }

    /**
     * Each pill keeps its own orientation.
     *
     * They used to share one, so turning the tools turned the navigator with it — which is never
     * what you meant, since the two sit in different places and get in each other's way in
     * different directions.
     */
    private fun applyWidgetOrientation() {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        // The same fallback every other pill uses: the shape you last chose, and only a
        // screen-width guess before you have ever chosen one. Two copies of this rule would drift.
        val narrow = pillDefaultVertical()
        for ((key, pair) in listOf(
            "nav" to (binding.navWidget to binding.navGrip),
            "tool" to (binding.toolWidget to binding.toolGrip)
        )) {
            val vertical = prefs.getBoolean("${key}_vertical", narrow)
            pair.first.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            applyGripOrientation(pair.second, vertical)
        }
    }

    /**
     * Center-pill tap: when you're not on today, jump to TODAY's version of the section
     * you're currently on; once you're already on today, a tap opens the Ledger hub. This
     * is the iPad feed-carrot pattern — first tap goes home, the next reveals the menu.
     */
    private fun onCenterTapped() {
        val today = LocalDate.now()
        if (currentDate != today) {
            // First tap from a past day → jump to today's version of the current section.
            val np = currentNotePage()
            if (np != null) CalendarNavigator.toDayNote(this, today, np)
            else CalendarNavigator.toDayPage(this, today, CalendarDay.DEFAULT_STYLE)
        } else {
            // Already on today → open the sections switcher.
            showSectionSwitcher()
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
    private fun bindDirectoryChip() {
        val chip = binding.directoryChip
        val page = notePage
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        // Only the surfaces that accumulate DOCUMENTS get a chip; a page you only ever have one of
        // has nothing to list, and a chip over it would be chrome for its own sake. Pickings was
        // the one wired here at first — Write and Synthesize now qualify too, on the same query,
        // because they are the same kind of thing (a titled document with pages). Grid and Jot
        // joined them when they gained stores, and they did so without a line changing here: the
        // chip asks the surface, not the key, so "which grid am I in, and what else is there" is
        // answered by the code that already answered it for writing.
        val surface = docs.surfaceOf(page)
        if (surface == null) {
            chip.visibility = View.GONE
            return
        }
        // Hand the query the keys of the day we ALREADY have loaded rather than making it re-read
        // the day file off disk — the chip rebinds on every page turn, and a multi-megabyte day
        // re-read per turn is exactly the kind of cost that shows up as a sluggish e-ink refresh.
        val keys: Set<String> = if (::calendarDay.isInitialized)
            (calendarDay.noteStrokes.filterValues { it.isNotEmpty() }.keys +
                calendarDay.imageElements.map { it.page } +
                calendarDay.textElements.map { it.pageKey }).toSet()
        else emptySet()
        val siblings = docs.forSurface(requireContext(), surface, currentDate, keys)
        val here = siblings.firstOrNull { it.key == page?.substringBefore('#') }
        val title = here?.title ?: docs.dateTitle(currentDate)
        // The count only appears when there IS more than one — otherwise it reads as a promise of
        // siblings that aren't there.
        chip.text = if (siblings.size > 1) "${docs.glyph(surface)}  $title  ·  ${siblings.size}  ▾"
        else "${docs.glyph(surface)}  $title  ▾"
        chip.visibility = View.VISIBLE
        chip.setOnClickListener {
            // The day you're ON and the document you're IN — the two things the old hub route could
            // not pass, which is what made its picker describe someone else's day.
            com.toolsboox.plugin.feeds.ui.showDocumentDirectory(this, surface, currentDate, page, keys)
        }
        com.toolsboox.ot.LedgerFonts.applyTree(chip)
        chip.bringToFront()
    }

    private fun showSectionSwitcher() {
        val onGarden = currentNotePage() in listOf("gratitude", "selfexec")
        val currentFolder = if (onGarden) "Garden" else "Flow"

        val sections: Pair<String, List<GoItem>> = if (onGarden) {
            "Garden" to buildList {
                add(GoItem("🙏", "Gratitude") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "gratitude") })
                add(GoItem("🐘", "Self Executive") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "selfexec") })
                add(GoItem("🌿", "Roots") { findNavController().navigate(R.id.action_to_ledger_roots) })
                add(GoItem("🌱", "Sprouts") { findNavController().navigate(R.id.action_to_sprouts) })
                add(GoItem("✧", "Missed Rhizomes") { findNavController().navigate(R.id.action_to_missed_rhizomes) })
                add(GoItem("🗺", "Map") { findNavController().navigate(R.id.action_to_ledger_map) })
            }
        } else {
            getString(R.string.go_group_day) to buildList {
                // The Flow flow in ritual order: Intake → Pickings → Synthesize → Write. Mid-flow,
                // a ⚡ fast-lane to the next step rides on top.
                ritualNextStep()?.let { (page, _, label) ->
                    add(GoItem("⚡", "Next · $label") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), page) })
                }
                add(GoItem("☀︎", "Day") { CalendarNavigator.toDayPage(this@CalendarDayFragment, LocalDate.now(), CalendarDay.DEFAULT_STYLE) })
                add(GoItem("★", "All Stars") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "intake") })
                add(GoItem("❝", "Pickings") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "pickings") })
                add(GoItem("🔬", "Synthesize") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "synthesize") })
                add(GoItem("✍", "Write") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "write") })
            }
        }

        // Sibling folders: the other top-level doors (current one dropped), each opening its head.
        val siblings = buildList {
            add(GoItem("⤳", "Flow") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "intake") })
            add(GoItem("📰", "Feed") { findNavController().navigate(R.id.action_to_feeds) })
            add(GoItem("🗒", "Desk") { findNavController().navigate(R.id.action_to_quick_wins) })
            add(GoItem("🪴", "Garden") { findNavController().navigate(R.id.action_to_ledger_roots) })
            add(GoItem("📚", "Bookshelf") { findNavController().navigate(R.id.action_to_reader) })
            add(GoItem("💬", "Ask") { findNavController().navigate(R.id.action_to_ledger_chat) })
            add(GoItem("🕘", "Log") {
                ReadingLogSelection.origin = null
                findNavController().navigate(R.id.action_to_reading_log)
            })
        }.filter { it.label != currentFolder }

        showGoModal(listOf(sections, "Go to" to siblings), anchorTop = false)
    }

    /** The next station of the daily ritual after the page we're on, or null off-flow. */
    private fun ritualNextStep(): Triple<String, String, String>? = when (currentNotePage()) {
        "intake" -> Triple("pickings", "❝", "Pickings")
        "pickings" -> Triple("gratitude", "🙏", "Gratitude")
        // Self Executive sits between Gratitude and Synthesize — same chain the stepper walks
        // and the same one iOS runs (PlannerModel.ritualNext); skipping it here made the ⚡
        // fast-lane and the stepper disagree about what "next" means.
        "gratitude" -> Triple("selfexec", "🐘", "Self Executive")
        "selfexec" -> Triple("synthesize", "🔬", "Synthesize")
        "synthesize" -> Triple("write", "✍", "Write")
        else -> null
    }

    /** Mark which tool is active on the floating pill (mirrors the hidden toolbar's tint). */
    private fun markActiveTool(active: View) {
        for (v in listOf(binding.toolPen, binding.toolEraser, binding.toolLasso)) {
            v.setBackgroundResource(if (v === active) R.drawable.tool_active_bg else 0)
        }
    }

    /** Pen style picker (ballpoint vs calligraphy), showing the active choice. */
    private fun showPenStylePicker() {
        val opts = arrayOf("Ballpoint", "Calligraphy")
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(R.string.calendar_drawing_toolbar_pen)
            .setSingleChoiceItems(opts, if (penIsCalligraphy()) 1 else 0) { d, w ->
                setPenCalligraphy(w == 1)
                d.dismiss()
            }
            .show()
    }

    /** Minimize a floating pill to grip + one button + expander; toggle back on tap. */
    /**
     * The handle IS the control: tap it again and again and the pill walks its four states.
     *
     *   wide open → wide collapsed → tall open → tall collapsed → wide open …
     *
     * There are only four ways a pill can be, so a dedicated Horizontal/Vertical row in a menu
     * was a button to reach a thing you are already touching. Collapsing and turning are the same
     * gesture now, and one fewer row sits in the menu.
     *
     * Orientation is shared by both pills (they should never disagree); collapse is per-pill, so
     * tapping the tools handle turns both and folds only the tools.
     */
    private fun togglePill(which: String) {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val key = "${which}_collapsed"
        val narrow = pillDefaultVertical()
        val before = prefs.getBoolean("${which}_vertical", narrow)
        // Shared with every other pill's handle — folds, then turns, one change per tap.
        val (_, after) = advancePillState(key, "${which}_vertical", narrow)
        if (after != before) applyWidgetOrientation()
        applyPillCollapse()
    }

    /** Apply the persisted collapsed/expanded state to both pills. */
    private fun applyPillCollapse() {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val navCollapsed = prefs.getBoolean("nav_collapsed", false)
        val toolCollapsed = prefs.getBoolean("tool_collapsed", false)

        // Collapse the nav pill to grip + center emoji (↑ ↓ hide); expand by tapping the grip.
        val navHidden = listOf(binding.navUp, binding.navDown)
        for (v in navHidden) v.visibility = if (navCollapsed) View.GONE else View.VISIBLE

        val toolHidden = listOf(
            binding.toolEraser, binding.toolLasso, binding.toolUndo, binding.toolRedo, binding.toolGear
        )
        for (v in toolHidden) v.visibility = if (toolCollapsed) View.GONE else View.VISIBLE
    }

    /** Return both pills to their anchored home positions. */
    private fun resetPillPositions() {
        requireContext().getSharedPreferences("ledger_widgets", 0).edit()
            .remove("nav_px").remove("nav_py").remove("tool_px").remove("tool_py").apply()
        for (v in listOf(binding.navWidget, binding.toolWidget)) { v.translationX = 0f; v.translationY = 0f }
    }

    /** Wrench on the nav pill → the quick tools/layout shortcut (a subset of the hub). */
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
            if (onSynth) add(GoItem("🃏", "Card…") { showCardMenu() })
            add(GoItem("❝", "Pickings…") { managePickings() })
            // Redundant with the VPS server OCR — off by default, re-enable in Settings.
            if (sharedPreferences.getBoolean(com.toolsboox.plugin.calendar.ot.LedgerExtractor.AUTO_EXTRACT_ENABLED_KEY, false))
                add(GoItem("🗒", "Extract tasks & events") { extractStructured() })
            add(GoItem("📄", "Whole page → text") { wholePageToText() })
            add(GoItem("🗂", "Capture sections") { captureSections() })   // auto-capture toggle now lives in Settings
            if (onSynth) add(GoItem("🔬", "Synthesize · 3 questions") { synthesizeQuestions() })
            if (onSynth || onWrite) add(GoItem("✍", "Writing prompt → Write") { writingPrompts() })
            if (onSynth) add(GoItem("🗺", "Map this page") { mapThisPage() })
            if (onSynth) add(GoItem("🗒", "Essay outline → Write") { essayOutline() })
            if (onSynth) add(GoItem("🃏", "Ideas → grid") { showSynthesisIdeas() })
            add(GoItem("👆", "Finger / hand") { binding.toolbarDrawing.toolbarHandTouch.performClick() })
            add(GoItem("🔄", "Rotate screen") { binding.toolbarDrawing.toolbarRotate.performClick() })
        }
        showGoModal(
            listOf(
                "Tools" to tools,
                "Layout" to listOf(
                    // Modal text size lived ONLY on the feed wrench, which is why it couldn't be
                    // found from the page you spend the day on. Same setting, reachable here.
                    GoItem("🎯", "Reset pill positions") { resetPillPositions() },
                    // BOTH pills, one switch. Each handle already folds its own pill down to a
                    // grip, which is the right control when you want the tools out of the way and
                    // the pager still there — and the wrong one when the answer is "the page has
                    // furniture on it". Michael: "hide pill leaves the pageskipper. It should
                    // hide." Reachable back from Settings → Legibility, since this wrench is
                    // itself on a pill.
                    GoItem(
                        if (pillsHidden()) "🫧" else "🫥",
                        if (pillsHidden()) "Show the floating pills" else "Hide the floating pills"
                    ) { togglePillsHidden() },
                    GoItem("⚙️", "Settings") { binding.toolbarDrawing.toolbarSettings.performClick() }
                )
            ),
            anchorTop = false
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
     * Auto-extract structured tasks (Tasks section) + calendar events (Schedule section) from the
     * day's handwriting via on-device ink OCR, grouped by row. Re-running refreshes the auto items
     * (source="auto") and leaves any lasso/manual ones. Only meaningful on the default day page.
     */
    private fun extractStructured() {
        if (!::calendarDay.isInitialized) return
        if (notePage != null) { showMessage(R.string.ledger_extract_day_only); return }
        val strokes = currentPageStrokes()
        val panels = com.toolsboox.plugin.calendar.ot.LedgerPanel.forPage(null)
        val tasksRect = panels.firstOrNull { it.id == "tasks" }?.rect ?: return
        val schedRect = panels.firstOrNull { it.id == "schedule" }?.rect ?: return
        lifecycleScope.launch {
            val tasks = com.toolsboox.plugin.calendar.ot.LedgerExtractor
                .extractPanel(strokes, tasksRect, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK, "auto", dueDate())
            val events = com.toolsboox.plugin.calendar.ot.LedgerExtractor
                .extractPanel(strokes, schedRect, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT, "auto", dueDate())
            // Deliberately NOT tombstoned: this removal is a refresh, not a deletion — the same
            // rows come straight back under fresh ids, and tombstoning the old ids would retire
            // their Quick Wins lineage (same words, tombstone day == the new copies' day) the
            // moment the user re-extracts. Deleting an auto item for real (erasing its ink, the
            // list/pile/kanban deletes) goes through tombstoneLedgerItem like any other item.
            calendarDay.ledgerItems.removeAll { it.source == "auto" }
            calendarDay.ledgerItems.addAll(tasks + events)
            calendarPattern.updateDay(calendarDay)
            presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
            showMessage(getString(R.string.ledger_extract_done, tasks.size, events.size), binding.root)
        }
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

        // ADD TO always asks WHICH one — that is the entire difference from Create, and a
        // half-built version of it that silently made a new card was worse than not having it.
        val actions: List<Pair<String, () -> Unit>> = if (adding) listOf(
            "🗒  Task" to { addSelectionToExistingItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK) },
            "📆  Event" to { addSelectionToExistingItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) },
            "🃏  Gram" to { addSelectionToPickings(strokes) },
            "🎬  A/V gram" to { recordAvGram() },
            "❝  Picking" to { addSelectionToPickings(strokes) }
        ) else listOf(
            "🗒  Task" to { createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK) },
            "📆  Event" to { createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) },
            "🃏  Gram" to { gramStudioFromSelection(strokes) },
            "🎬  A/V gram" to { recordAvGram() },
            "❝  Picking" to { createPickingFromSelection(strokes) }
        )

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
            "⁂  Already a $what · $words" to { showItemRhizome(item) }
        }

        showIconMenu(title, existing + listOf<Pair<String, () -> Unit>>(
            flip to {
                selectionAddMode = !adding
                showSelectionMenu(strokes, !adding)
            }
        ) + actions + listOf(
            "📋  Copy text" to { copyTextFromSelection(strokes) },
            "🔎  Ask about this" to { askAboutSelection(strokes) },
            "🎓  Educate me" to { educateFromSelection(strokes) },
            "🎓  Gram to Educate Me" to { gramSelectionToEducateMe(strokes) },
            "🔍  Find in Ledger" to { findInLedgerFromSelection(strokes) }
        ))
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
            .show()
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
     */
    private fun createLedgerItem(strokes: List<com.toolsboox.da.Stroke>, kind: com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind) {
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
                            .itemWithText(strokes, kind, t.trim(), "lasso-ai", dueDate())
                    else null
                } else null
            // No key, no signal, or the call failed: the Boox's own on-device ink OCR. The point
            // of it is that it works on a plane, so everything after this must work there too.
            } ?: com.toolsboox.plugin.calendar.ot.LedgerExtractor.extractStrokes(strokes, kind, "lasso", dueDate())

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
                val head = if (kind == com.toolsboox.da.Attachment.Kind.VIDEO) "🎥 Video gram" else "🎤 Audio gram"
                if (placed) showMessage("$head → today's Pickings", binding.root)
                showGramStudio(listOf(head, text ?: "").filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { head })
            }
        } else {
            showMessage(getString(R.string.gram_capture_saved), binding.root)
        }
    }

    /** The day holds the filenames, keyed by attachment id — so this is where the blob is found. */
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
        com.toolsboox.plugin.calendar.ot.GramStudio.show(
            this, text,
            onShare = { cards -> cards.forEach { shareGramBitmap(it) } },
            onPickings = { cards -> cards.forEach { placeGramToPickings(it) } },
            onHere = { cards -> cards.forEach { placeGramBitmap(it) } }
        )
    }

    /**
     * Jump back to a gram's origin from its long-press menu. Routes the stored source link:
     * ledger://<date>/<pageKey> → that ledger page; book://<path> → the reader; mail://<id> → that
     * letter in the inbox; http(s) → the browser.
     */
    override fun onImageSource(element: com.toolsboox.da.ImageElement) {
        val link = element.sourceLink
        when {
            link.startsWith("mail://") -> {
                // A starred email's gram files its address as mail://<id> (MailInboxFragment writes
                // it so rhizome edges and grams share one name for the letter). Without this arm the
                // router fell through in silence — "Open the source" on a mail gram, and the All
                // Stars tap that funnels here, did nothing at all. The handoff rides a pending
                // static, the same shape FeedSelection.pendingInPaneEntry uses for the feeds pane.
                com.toolsboox.plugin.mail.ui.MailInboxFragment.pendingOpenId = link.removePrefix("mail://")
                findNavController().navigate(R.id.action_to_mail_inbox)
            }
            link.startsWith("ledger://") -> {
                val rest = link.removePrefix("ledger://")
                val slash = rest.indexOf('/')
                val dateStr = if (slash >= 0) rest.substring(0, slash) else rest
                val pageKey = if (slash >= 0) rest.substring(slash + 1) else ""
                val date = runCatching { java.time.LocalDate.parse(dateStr) }.getOrNull() ?: currentDate
                if (pageKey.isBlank() || pageKey == "day") CalendarNavigator.toDayPage(this, date)
                else CalendarNavigator.toDayNote(this, date, pageKey)
            }
            link.startsWith("book://") -> {
                requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
                    .putString("current_book_path", link.removePrefix("book://")).apply()
                findNavController().navigate(R.id.action_to_reader)
            }
            link.startsWith("http") -> {
                // Prefer the Feed Ledger's in-pane reader (clean native render, an "open original ↗"
                // inside) over a raw WebView, which renders external pages badly on e-ink.
                com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry =
                    com.toolsboox.plugin.feeds.da.FeedEntry(
                        id = link.hashCode().toLong(),
                        title = element.sourceLabel.ifBlank { "Source" },
                        feedTitle = "", url = link, author = null,
                        // Blank content is the sentinel that tells the reader to load the live page
                        // (there's no stored/parsed article for an arbitrary gram source URL).
                        content = "",
                        publishedAt = java.time.Instant.now().toString(), starred = false
                    )
                findNavController().navigate(R.id.action_to_feeds)
            }
        }
    }

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

    /** Choose a pickings board and drop this gram card onto it (current day). */
    private fun placeGramToPickings(bmp: android.graphics.Bitmap) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val boards = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, currentDate)
        val saved = boards.filter { it.key != com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY }
        val labels = (listOf("❝  Today's Pickings", "＋  New pickings…") + saved.map { "❝  ${it.name}" }).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Add gram to Pickings")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> addGramToPickingPage(bmp, com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY)
                    1 -> {
                        val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
                        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("New pickings").setView(input)
                            .setPositiveButton("Create") { _, _ ->
                                val page = com.toolsboox.plugin.calendar.ot.PickingsStore.add(ctx, currentDate, input.text.toString().trim())
                                addGramToPickingPage(bmp, page.key)
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    else -> addGramToPickingPage(bmp, saved[which - 2].key)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Switch to / create / rename a pickings board for the VIEWED day — reachable straight from the
     * page so a new pickings starts against the date you're on, not always today (the ▦ hub's picker
     * hardcodes today, which is why named boards "didn't show" on other days).
     */
    private fun managePickings() {
        val ctx = requireContext()
        val ps = com.toolsboox.plugin.calendar.ot.PickingsStore
        ps.sync(ctx, currentDate)   // pull other devices' board names
        val pages = ps.list(ctx, currentDate)
        val labels = (pages.map { "❝  ${it.name}" } +
            listOf("＋  New pickings…", "✎  Rename this pickings…")).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Pickings · $currentDate")
            .setItems(labels) { _, which ->
                when {
                    which < pages.size -> CalendarNavigator.toDayNote(this, currentDate, pages[which].key)
                    which == pages.size -> {
                        val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
                        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("New pickings").setView(input)
                            .setPositiveButton("Create") { _, _ ->
                                val page = ps.add(ctx, currentDate, input.text.toString().trim())
                                CalendarNavigator.toDayNote(this, currentDate, page.key)
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    else -> {
                        val key = if (ps.isPickings(notePage)) notePage!! else ps.DEFAULT_KEY
                        val input = android.widget.EditText(ctx).apply {
                            setText(pages.firstOrNull { it.key == key }?.name ?: ""); setSingleLine()
                        }
                        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Rename pickings").setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                ps.rename(ctx, currentDate, key, input.text.toString().trim())
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Encode the gram card and add it as an image element on [pageKey] of the current day. */
    private fun addGramToPickingPage(bmp: android.graphics.Bitmap, pageKey: String) {
        if (!::calendarDay.isInitialized) return
        val max = 1200
        val longest = maxOf(bmp.width, bmp.height)
        val scaled = if (longest > max)
            android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width * max / longest, bmp.height * max / longest, true) else bmp
        val baos = java.io.ByteArrayOutputStream(); scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        val w = (1404f * 0.42f).coerceAtMost(scaled.width.toFloat()); val h = w * scaled.height / scaled.width
        val count = calendarDay.imageElements.count { it.page == pageKey }
        val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (1404f - w).coerceAtLeast(0f))
        val y = (120f + (count / 3) * (h + 30f)).coerceIn(0f, (1872f - h).coerceAtLeast(0f))
        // Remember the ledger page this card was grammed from so the placed gram can jump back to it.
        val srcPage = notePage ?: "day"
        val srcLabel =
            if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage))
                com.toolsboox.plugin.calendar.ot.PickingsStore.nameOf(requireContext(), currentDate, srcPage)
            else notePage?.replaceFirstChar { it.uppercase() } ?: "Day"
        calendarDay.imageElements.add(com.toolsboox.da.ImageElement(
            x = x, y = y, width = w, height = h, data = base64, page = pageKey,
            sourceLink = "ledger://$currentDate/$srcPage", sourceLabel = srcLabel))
        calendarPattern.updateDay(calendarDay)
        presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
        showMessage("Added to pickings.", binding.root)
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
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share gram"))
        }.onFailure { showMessage("Share failed", binding.root) }
    }

    private fun placeGramBitmap(bmp: android.graphics.Bitmap) {
        runCatching { queueSharedImageInsert(gramFile(bmp)) }
            .onFailure { showMessage("Place failed", binding.root) }
    }

    /** Ask-my-Ledger AI creds (provider, key, model) for the vision OCR, or null if unset. */
    private fun aiCreds(): Triple<String, String, String>? {
        val prefs = EncryptedSharedPreferences.create(
            requireContext(), "ledger_chat_encrypted_prefs",
            MasterKey.Builder(requireContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val provider = prefs.getString("ledger_chat_provider", "anthropic") ?: "anthropic"
        val key = prefs.getString("ledger_chat_api_key_$provider", "")?.trim().orEmpty()
        val default = if (provider == "openai") "gpt-4o" else "claude-sonnet-5"
        val chatModel = prefs.getString("ledger_chat_model_$provider", default) ?: default
        // OCR can run on a different (e.g. faster/cheaper) model than chat. An override set in the
        // OCR-model picker wins, but only for the matching provider family; blank = use the chat model.
        val ocrOverride = com.toolsboox.ui.plugin.OcrModel.override(requireContext(), provider)
        val model = ocrOverride ?: chatModel
        return if (key.isBlank()) null else Triple(provider, key, model)
    }

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
        sectionsDirty = false   // reset up-front; a stroke during capture re-marks it
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
            showMessage("Placed 3 questions on your Synthesize page — answer them, then head to Write.", binding.root)
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
                .show()
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
            .show()
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
            .show()
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
            .show()
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
                        .show()
                }
                d.dismiss()
            }
            .show()
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
            .show()
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
        // Single source of truth: the same directory the feed/book/reader/chat screens use, with
        // this day's recently-opened books folded into the Bookshelf folder.
        val books: List<Pair<String, () -> Unit>> =
            recentBooks().map { f -> ("📖  " + f.nameWithoutExtension) to { openBookInReader(f) } }
        showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this, books))
    }

    /** The three most-recently-opened books (recency = file mtime, touched on open). */
    private fun recentBooks(): List<java.io.File> =
        java.io.File(requireContext().filesDir, "reader/books").listFiles()
            ?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(3) ?: emptyList()

    /** Open a specific book straight into the reader. */
    private fun openBookInReader(f: java.io.File) {
        requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
            .putString("current_book_path", f.absolutePath).apply()
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

    /** The emoji for the section currently on screen (drives the bottom pill button). */
    /** Monochrome section glyph for the pill centre (high-contrast on e-ink). */
    @androidx.annotation.DrawableRes
    private fun sectionIcon(): Int = when (baseNotePage(currentNotePage())) {
        null, "default", CalendarDay.DEFAULT_STYLE -> R.drawable.ic_nav_today
        "pickings" -> R.drawable.ic_quote
        "gratitude" -> R.drawable.ic_heart
        "intake" -> R.drawable.ic_bookmark
        "write" -> R.drawable.ic_edit
        "synthesize" -> R.drawable.ic_swap
        "grid" -> R.drawable.ic_reader_view
        "sketch" -> R.drawable.ic_edit
        com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS -> R.drawable.ic_bookmark
        "selfexec" -> R.drawable.ic_refresh
        // Named documents wear their surface's glyph, not the generic note pencil — a piece of
        // writing you named is still Write.
        else -> when {
            com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(currentNotePage()) -> R.drawable.ic_swap
            com.toolsboox.plugin.calendar.ot.WritePageStore.isWrite(currentNotePage()) -> R.drawable.ic_edit
            com.toolsboox.plugin.calendar.ot.GridPageStore.isMine(currentNotePage()) -> R.drawable.ic_reader_view
            com.toolsboox.plugin.calendar.ot.JotPageStore.isMine(currentNotePage()) -> R.drawable.ic_edit
            else -> R.drawable.ic_pencil
        }
    }

    private fun sectionEmoji(): String = when (baseNotePage(currentNotePage())) {
        // Text-presentation sun (VS15) renders as a solid black glyph — high contrast on e-ink,
        // unlike the washed-out yellow colour emoji. Base glyphs (no VS16) throughout, so the
        // pill's glyph matches the monochrome icons the modals render for the same sections.
        null, "default", CalendarDay.DEFAULT_STYLE -> "☀︎"
        "pickings" -> "❝"
        "gratitude" -> "🙏"
        "intake" -> "🔖"
        "write" -> "✍"
        "synthesize" -> "🔬"
        "grid" -> "📈"
        "sketch" -> "⌱"
        com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS -> "◈"
        "selfexec" -> "🐘"
        // Named documents wear their surface's glyph — a grid you named is still a grid.
        else -> when {
            com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(currentNotePage()) -> "🔬"
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
    /**
     * OnResume hook.
     */
    override fun onResume() {
        super.onResume()

        binding.templateImageView.setImageBitmap(templateBitmap)
        binding.navigatorImageView.setImageBitmap(navigatorBitmap)
        updateNavigator(true)

        // The feeds directory's back button lands here with the today menu open, Feed Ledger
        // folder expanded — the last rung of the slim-pane ⇄ directory ⇄ today-menu ladder.
        if (com.toolsboox.plugin.feeds.ui.FeedSelection.openTodayHubOnArrival) {
            com.toolsboox.plugin.feeds.ui.FeedSelection.openTodayHubOnArrival = false
            binding.root.post {
                showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this, expandFeedLedger = true))
            }
        }

        val defaultStartHour = sharedPreferences.getInt("calendarStartHour", 5)
        val appCtx = requireContext().applicationContext
        timer = GlobalScope.launch(Dispatchers.Main) {
            presenter.load(this@CalendarDayFragment, binding, currentDate, defaultStartHour, locale)
            syncPresenter.backgroundSync(this@CalendarDayFragment, UUID.randomUUID())
            // Weather is IP-based + cached ~1h; if it just refreshed, redraw so the header badge shows.
            if (com.toolsboox.plugin.calendar.ot.WeatherMoon.refresh(appCtx) && isAdded && isResumed) {
                presenter.load(this@CalendarDayFragment, binding, currentDate, defaultStartHour, locale)
            }
        }
        maybeShowReturnChip()
        showSpiralLine()

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
            anchor.clear()
            runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(action) }
        }
        bar.show()
    }

    /**
     * OnPause hook.
     */
    override fun onPause() {
        super.onPause()

        // Release the hardware page-key handler so it doesn't page a stale surface.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null

        // Auto-capture this page's section zones on leave — only when the ink changed (sectionsDirty)
        // and the setting is on, so an unchanged page never re-runs the paid vision OCR.
        if (sectionsDirty && ::calendarDay.isInitialized &&
            sharedPreferences.getBoolean("autoCaptureSections", true)) {
            runCaptureSections(silent = true)
        }

        // Leaving the intake page counts as "page exit" — hand any typed content
        // that has not been delivered yet to the intake queue.
        if (notePage == "intake") {
            intakePageData?.let { data ->
                context?.let { ctx -> IntakePageStore.dispatch(ctx, currentDate, data) }
            }
        }

        toolbar.toolbarPager.visibility = View.GONE
        timer.cancel()
        // Auto-OCR this page's sections in the background as we leave, so section text is always
        // fresh without a manual capture (skips when the ink is unchanged).
        runCatching { autoCaptureSections() }
        syncPresenter.backgroundSync(this@CalendarDayFragment, UUID.randomUUID())
        // Leaving any ink page — a page turn, a surface switch, the screen going dark — is the
        // moment this page should reach the other devices. The cheap day-JSON mirror only; the
        // heavy PDF pass keeps its own schedule and its own foreground guard.
        context?.let { com.toolsboox.plugin.calendar.nw.QuickDayMirror.fire(it, "page-leave") }
    }

    override fun onTransformChanged(matrix: Matrix) {
        // The spiral line sits on a patch of the drawn page, so it has to move with the page.
        if (::binding.isInitialized) positionSpiralLine()
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
        // The load runs on a lifecycle-detached scope; if this fragment is already paused/detached
        // (a fast second page turn replaced it), don't post an intermediate page's strokes onto the
        // surface — that's another way old ink lands over the destination page.
        if (!isAdded || !isResumed) return
        this.calendarDay = calendarDay
        this.calendarPattern = calendarPattern
        updateNavigator()

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
            // A notes page shows no Quick Wins rows — drop the recorded rectangles so a stale
            // one can't send a tap on this page off to a task (the PickingsCover.clear rule).
            CalendarDayPage.clearWinRows()
            CalendarDayPage.clearGlimpseRows()
            if (notePage == "intake") {
                // Intake page: four quarters, each a grid of just its own grams (Email / Read /
                // Watch / Listen). No typing — the grams are the content; tap one to open it.
                val intakeData = IntakePageStore.load(requireContext(), currentDate).also { intakePageData = it }
                CalendarDayPageIntake.drawPage(templateCanvas, intakeData, calendarDay, context = requireContext())
            } else {
                CalendarDayPageNotes.drawPage(this.requireContext(), templateCanvas, calendarDay, noteTemplate, notePage!!)
            }
            // Zones still capture per-section (captureSections), but we no longer paint
            // boxes/labels over the page — the original template provides the sectioning.
            applyStrokes(Stroke.listDeepCopy(live(noteStrokes)), true)
        } else {
            binding.toolbarDrawing.toolbarProcrastinator.visibility = View.VISIBLE
            val calendarStrokes = calendarDay.calendarStrokes[calendarStyle] ?: listOf()
            quickWinsShown = quickWinsForPanel()
            quickWinsGlimpseShown = quickWinsGlimpseForPanel()
            CalendarDayPage.drawPage(
                this.requireContext(), templateCanvas, calendarDay, calendarEvents,
                quickWinsShown, quickWinsGlimpseShown
            )
            warmQuickWins(calendarEvents)
            warmQuickWinsGlimpse(calendarEvents)
            applyStrokes(Stroke.listDeepCopy(live(calendarStrokes)), true)
        }
        // The template was just drawn into templateBitmap; force the ImageView to repaint so
        // named pages (pickings/gratitude) reliably show on first navigation, not only after a re-swipe.
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
                    .at(calendarDay, calendarDay.events, p[0], p[1]) ?: return false
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
            .show()
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
                    sharedPreferences.getInt("calendarStartHour", 5), locale)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // handleIntakeTap is gone. It resolved finger taps against the cells the template had painted
    // its grams into — a hit-test table for a picture. All Stars' grams are real elements now, so
    // the element layer resolves taps (and the ✓-corner became the "Give it its own board" verb on
    // the ordinary gram menu). Keeping a second, parallel tap path over stale rects would only ever
    // disagree with the live one.

    /**
     * Graduate an intake gram into its own Pickings page: make a board named for the gram, place a
     * copy of its face there (where the object's further pickings then accumulate), and stamp the
     * intake gram with that board key — so it wears a ✓ and becomes a link into the board.
     */
    private fun graduateIntakeGram(element: com.toolsboox.da.ImageElement) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val boardKey = withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    val name = element.cardText.ifBlank { element.sourceLabel }
                        .ifBlank { "Picking" }.take(40)
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
                        val day = calendarDayService.loadOrNull(root, currentDate, null, java.util.Locale.getDefault())
                            ?: if (calendarDayService.exists(root, currentDate)) return@withDay
                            else calendarDayService.load(root, currentDate, null, java.util.Locale.getDefault())
                        day.imageElements.firstOrNull { it.elementId == element.elementId }
                            ?.graduatedTo = board.key
                        calendarDayService.save(root, currentDate, day)
                    }
                    board.key
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (boardKey == null) {
                showMessage("Couldn't graduate that gram", binding.root)
                return@launch
            }
            // Reflect it immediately: stamp the in-memory gram and redraw the intake page's ✓.
            element.graduatedTo = boardKey
            CalendarDayPageIntake.drawPage(
                templateCanvas, com.toolsboox.plugin.michaelfilter.da.IntakePageData(), calendarDay,
                context = context
            )
            binding.templateImageView.invalidate()
            showMessage("Picked into its own board — tap ✓ to open it", binding.root)
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
        val boardName: String, val label: String, val glyph: String
    )

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
        // A gram held → its own quarter; empty paper → the quarter under the finger; neither →
        // The Read, so the menu still works when the hold lands in a gutter.
        val panel = CalendarDayPageIntake.panelAt(cx, cy)
        val kindKey = gram?.kindKey ?: panel?.kindKey ?: CalendarDayPageIntake.panels.first().kindKey
        val kindTitle = CalendarDayPageIntake.panels.firstOrNull { it.kindKey == kindKey }?.title ?: "ALL STARS"
        val element = gram?.let { g ->
            calendarDay.imageElements.firstOrNull { it.elementId.toString().lowercase() == g.elementId }
        }

        fun item(label: String, action: () -> Unit) = com.toolsboox.ot.LedgerContextMenu.Item(label, action)
        val groups = mutableListOf<List<com.toolsboox.ot.LedgerContextMenu.Item>>()

        groups.add(listOf(item("❝  Bring in a picking") { bringPickingIntoIntake(kindKey, kindTitle) }))

        if (element != null) {
            groups.add(listOfNotNull(
                if (element.graduatedTo.isBlank())
                    item("✓  Give it its own board") { graduateIntakeGram(element) }
                else
                    item("❝  Open its board") { CalendarNavigator.toDayNote(this, currentDate, element.graduatedTo) },
                if (element.sourceLink.isNotBlank()) item("🔗  Open the source") { onImageSource(element) } else null,
                if (element.sourceFeed.isNotBlank()) item("📰  Go to its feed") { onImageGoToFeed(element) } else null,
                item("❝  Add to Pickings…") { intakeGramToPickings(element) },
                item("🕸  Rhizome") { onImageRhizome(element) },
                item("🔎  Where used") { onImageWhereUsed(element) }
            ))
            groups.add(listOf(item("🗑  Remove from All Stars") { confirmRemoveIntakeGram(element) }))
        }

        com.toolsboox.ot.LedgerContextMenu.show(
            provideSurfaceView(), pressX, pressY,
            if (element != null) "GRAM" else kindTitle, groups
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
            sharedPreferences.getInt("calendarStartHour", 5), locale
        )
    }

    override fun onImageGraduate(element: ImageElement) = graduateIntakeGram(element)

    override fun onImageOpenBoard(element: ImageElement) {
        if (element.graduatedTo.isNotBlank())
            CalendarNavigator.toDayNote(this, currentDate, element.graduatedTo)
    }

    override fun onImageMoveTo(element: ImageElement) {
        if (!::calendarDay.isInitialized) return
        val ctx = requireContext()
        val boards = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, currentDate)
        val gramPicks = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS

        // (label, pageKey, intakeKind) — intakeKind only means anything on the intake page.
        val targets = mutableListOf<Triple<String, String, String>>()
        targets.add(Triple("◈  Gram Picks", gramPicks, ""))
        for (b in boards) targets.add(Triple("❝  ${b.name}", b.key, ""))
        for (p in CalendarDayPageIntake.panels)
            targets.add(Triple("★  All Stars · ${p.title}", CalendarDayPageIntake.INTAKE_PAGE, p.kindKey))
        targets.add(Triple("🔬  Synthesize", "synthesize", ""))

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
            .show()
    }

    // The quarter-to-quarter picker that used to live here is gone. It existed because a painted
    // gram could not be dragged, so changing its band needed a dialog; a real element just gets
    // dragged into the band you want, which is what "manipulatable just like pickings" means.
    // Correcting a mis-detected kind still works — drag it across and it stays.

    /** Taking a card off Star Sort is a deletion, so it asks — and says which one it means. */
    private fun confirmRemoveIntakeGram(element: ImageElement) {
        val what = element.cardText.ifBlank { element.sourceLabel }.ifBlank { "this gram" }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Remove from All Stars?")
            .setMessage(what.take(160))
            .setPositiveButton("Remove") { _, _ -> removeIntakeGram(element) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            CalendarDayPageIntake.drawPage(
                templateCanvas, intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData(),
                calendarDay, null, "TODAY", context = context
            )
            binding.templateImageView.invalidate()
            return
        }
        // A wider window means reading other days off disk, so paint the day's own stars now and
        // swap in the fuller set when it lands. The page is never blank and never blocks the pen.
        CalendarDayPageIntake.drawPage(
            templateCanvas, intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData(),
            calendarDay, null, starsScopeLabel() + " …", context = context
        )
        binding.templateImageView.invalidate()
        val scope = starsScope
        lifecycleScope.launch {
            val grams = withContext(Dispatchers.IO) { collectScopedStars(scope) }
            if (!isAdded || notePage != CalendarDayPageIntake.INTAKE_PAGE || starsScope != scope) return@launch
            CalendarDayPageIntake.drawPage(
                templateCanvas, intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData(),
                calendarDay, grams, starsScopeLabel(), context = context
            )
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
            showMessage(if (ok) "Brought in ${p.label}" else "Couldn't bring that picking in", binding.root)
        }
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

        listCol.addView(note("Reading your boards…"))

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
                    text = "${p.label}\n❝ ${p.boardName} · ${p.date}"
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

            val boards = cards.filter { PickingsStore.isPickings(it.page) }
            if (boards.isEmpty()) continue
            val byKey = names.getOrPut(date) {
                runCatching { PickingsStore.list(ctx, date).associate { it.key to it.name } }
                    .getOrNull().orEmpty()
            }
            for (c in boards) {
                out.add(PickingGram(
                    date = date,
                    page = c.page,
                    id = c.id,
                    boardName = byKey[c.page.substringBefore('#')] ?: byKey[c.page] ?: "Pickings",
                    label = PickingsCards.labelFor(c),
                    glyph = PickingsCards.glyphFor(c)
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
                    true
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            if (!ok) { showMessage("Couldn't bring that picking in", binding.root); return@launch }
            showMessage("Brought into $kindTitle", binding.root)
            // Reload so the page draws from the day file the placement just wrote.
            presenter.load(this@CalendarDayFragment, binding, currentDate,
                sharedPreferences.getInt("calendarStartHour", 5), locale)
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
