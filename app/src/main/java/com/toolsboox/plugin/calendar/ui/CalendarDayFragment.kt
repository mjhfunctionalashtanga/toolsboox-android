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
        // The intake page is a PICTURE of its grams — they're painted into the template's grid,
        // not laid out at their own element coordinates — so the generic canvas menu, which
        // hit-tests element.x/y, could never find one and a hold there did nothing. Resolve the
        // hold against the same recorded cell geometry the tap handler uses.
        if (notePage == CalendarDayPageIntake.INTAKE_PAGE) {
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

    /** The named note bases that own an inline ‹ N › sub-page pager (like the numeric notes do). */
    private fun isSubPageableBase(base: String?): Boolean =
        base == "write" || base == "grid" || base == "sketch" || base == "synthesize"

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
    override fun provideDisableRawInkCapture(): Boolean = notePage == "intake"

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

    /** Grid Notes snaps dragged objects to its 50px grid; other pages don't snap. */
    override fun snapStep(): Float = if (baseNotePage(notePage) == "grid") 50f else 0f

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
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Intake a link")
            .setView(box)
            .setPositiveButton("Place") { _, _ -> placeLinkObject(urlIn.text.toString().trim(), titleIn.text.toString().trim(), cx, cy, alsoFile = false) }
            .setNeutralButton("Place & file to Later") { _, _ -> placeLinkObject(urlIn.text.toString().trim(), titleIn.text.toString().trim(), cx, cy, alsoFile = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    override fun extraCreationGroups(cx: Float, cy: Float): List<List<com.toolsboox.ot.LedgerContextMenu.Item>> {
        val ctx = context ?: return emptyList()
        if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(notePage)) return listOf(listOf(
            com.toolsboox.ot.LedgerContextMenu.Item("⚗  Synthesize the day…") {
                com.toolsboox.plugin.calendar.ot.SynthEngines.pick(ctx, "Synthesize the day") { e ->
                    runEngine(e, pageMaterial())
                }
            }
        ))
        return when (baseNotePage(notePage)) {
            "write" -> listOf(listOf(
                com.toolsboox.ot.LedgerContextMenu.Item("→  Share essay…") { shareEssay() }
            ))
            // Any other note page (pickings, notes, gratitude) is a SELECTION BASKET: copy a few
            // cards/quotes onto it, then run an engine on just this page's gathering.
            null -> emptyList()
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
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
            .show()
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
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Email the essay").setView(box)
            .setPositiveButton("Send") { _, _ ->
                val to = toIn.text.toString().trim()
                if (to.isNotBlank()) sendEssay(site.site, site.user, site.pass, "email", title, tags, to)
            }
            .setNegativeButton("Cancel", null).show()
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
            val text = currentTextElements().filter { it.text.isNotBlank() }.joinToString("\n\n") { it.text.trim() }
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
                    crop = element.data,
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
                        val day = calendarDayService.load(root, today, null, java.util.Locale.getDefault())
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
            if (isNotesTagSurface(notePage)) {
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

            if (notePage == "intake" && handleIntakeTap(motionEvent, gestureResult))
                return@setOnTouchListener true

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

        // Retire the fixed pen strip on the day page — the floating pills + gear now cover
        // everything. The real buttons stay in the (hidden) layout so performClick still
        // drives the Onyx ink actions; nothing about drawing changes.
        binding.toolbarDrawing.root.visibility = View.GONE

        // Floating nav widget: reuse the existing nav actions so nothing about drawing
        // changes. ↑ ↓ step the day's sections (dates live on the top bar's ‹ ›).
        binding.navWidget.visibility = View.VISIBLE
        binding.navUp.setOnClickListener { binding.toolbarDrawing.toolbarSwipeUp.performClick() }
        binding.navDown.setOnClickListener { binding.toolbarDrawing.toolbarSwipeDown.performClick() }

        // Hide-nav toggle: collapse the almanac strip to give the page its full height back. The
        // drawing canvas is a fixed 1404×1872 logical space mapped onto the surface by a
        // fit-to-screen matrix (SurfaceFragment), and the template rides that SAME matrix
        // (onTransformChanged) — so a taller surface just scales the whole page up uniformly with
        // template and ink still in register. The choice persists in the shared "ledger_ui" prefs.
        binding.navHideButton.bringToFront()
        binding.navHideButton.setOnClickListener {
            val nowHidden = !com.toolsboox.plugin.calendar.ot.AlmanacNav.isHidden(requireContext())
            com.toolsboox.plugin.calendar.ot.AlmanacNav.setHidden(requireContext(), nowHidden)
            applyAlmanacNavHidden(nowHidden)
        }
        applyAlmanacNavHidden(com.toolsboox.plugin.calendar.ot.AlmanacNav.isHidden(requireContext()))

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
            // "≡ Notes" idea, for cohesion across the note surfaces. Synthesize opens its synthesis
            // directory (today's + the named topics + New/Rename); write/grid/sketch open a jump
            // across THIS base's sub-pages (+ New page + Go to a date).
            binding.notePagerLabel.isClickable = true
            binding.notePagerLabel.setOnClickListener {
                if (base == "synthesize") com.toolsboox.plugin.feeds.ui.showSynthPicker(this)
                else showNoteSubPageJump(base)
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
            binding.notePagerLabel.setOnClickListener { com.toolsboox.plugin.feeds.ui.showPickingsPicker(this) }
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
        val labels = (ordered.map { if (it.toString() == notePage) "Page ${it + 1}  ·  here" else "Page ${it + 1}" }
            + "＋  New page" + "📅  Go to a date…").toTypedArray()
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Jump to")
            .setItems(labels) { _, which ->
                when {
                    which < ordered.size -> {
                        val target = ordered[which]
                        if (target.toString() != notePage) CalendarNavigator.toDayNote(this, currentDate, target.toString())
                    }
                    which == ordered.size -> CalendarNavigator.toDayNote(this, currentDate, newPage.toString())
                    else -> showNoteDatePicker()   // navigate notes BY DATE
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        // The rows only exist once the list has laid out; apply the reading font then.
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
    }

    /** The named-base sibling of [showNotePageJump]: a directory across one base's own sub-pages
     *  (write / grid / sketch — keyed "<base>", "<base>#1", …) on this day, plus New page and
     *  Go-to-a-date. Same Text-Notes directory design, reached from the ‹ N › label beneath the
     *  almanac nav so every note surface is findable the same way. */
    private fun showNoteSubPageJump(base: String) {
        val ctx = context ?: return
        val here = notePageSubIndex(notePage)
        val subs = sortedSetOf(here)   // the page you're on always lists
        if (::calendarDay.isInitialized) {
            fun consider(key: String) { if (baseNotePage(key) == base) subs.add(notePageSubIndex(key)) }
            calendarDay.noteStrokes.filterValues { it.isNotEmpty() }.keys.forEach { consider(it) }
            calendarDay.imageElements.forEach { consider(it.page) }
        }
        val ordered = subs.toList()
        val newSub = (ordered.maxOrNull() ?: -1) + 1
        fun keyFor(sub: Int) = if (sub == 0) base else "$base#$sub"
        val labels = (ordered.map { "Page ${it + 1}" + if (it == here) "  ·  here" else "" }
            + "＋  New page" + "📅  Go to a date…").toTypedArray()
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(base.replaceFirstChar { it.uppercase() })
            .setItems(labels) { _, which ->
                when {
                    which < ordered.size -> {
                        val s = ordered[which]
                        if (s != here) CalendarNavigator.toDayNote(this, currentDate, keyFor(s))
                    }
                    which == ordered.size -> CalendarNavigator.toDayNote(this, currentDate, keyFor(newSub))
                    else -> showNoteDatePicker()   // navigate this surface BY DATE
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
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
        val narrow = resources.configuration.screenWidthDp < 520
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
                add(GoItem("🔖", "Star Sort") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "intake") })
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
        val narrow = resources.configuration.screenWidthDp < 520
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
        val onWrite = baseNotePage(notePage) == "write"
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
     * ledger://<date>/<pageKey> → that ledger page; book://<path> → the reader; http(s) → the browser.
     */
    override fun onImageSource(element: com.toolsboox.da.ImageElement) {
        val link = element.sourceLink
        when {
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
                val bytes = android.util.Base64.decode(img.data, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
        "selfexec" -> R.drawable.ic_refresh
        else -> if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(currentNotePage())) R.drawable.ic_swap else R.drawable.ic_pencil
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
        "selfexec" -> "🐘"
        else -> if (com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(currentNotePage())) "🔬" else "✒"
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
    }

    /**
     * Show or hide the almanac navigator strip (persisted via [com.toolsboox.plugin.calendar.ot.AlmanacNav]).
     * Hiding it drops the strip out of the layout so the page surface reflows up into the reclaimed
     * band; the corner chevron flips to an "expand" glyph and becomes the thin affordance that
     * brings the strip back. A full EPD refresh clears any ghost of the removed strip.
     */
    private fun applyAlmanacNavHidden(hidden: Boolean) {
        if (!::binding.isInitialized) return
        binding.navigatorImageView.visibility = if (hidden) View.GONE else View.VISIBLE
        // The ▦ apps/almanac jump by the carets rides with the strip — it's a navigator affordance.
        binding.goAppsButton.visibility = if (hidden) View.GONE else View.VISIBLE
        binding.navHideButton.setImageResource(if (hidden) R.drawable.ic_nav_down else R.drawable.ic_nav_up)
        binding.navHideButton.bringToFront()
        binding.root.post { if (isAdded) forceFullEpdRefresh() }
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
                CalendarDayPageIntake.drawPage(templateCanvas, intakeData, calendarDay)
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
                    val cx = canvasPts[0]; val cy = canvasPts[1]
                    fun intakeElement(id: String) = calendarDay.imageElements.firstOrNull {
                        it.elementId.toString().lowercase() == id
                    }
                    // The ✓-corner graduates the gram into its own Pickings page (or opens that page
                    // once graduated). Checked before the body so the corner wins the tap.
                    CalendarDayPageIntake.cornerAt(cx, cy)?.let { gram ->
                        intakeElement(gram.elementId)?.let { el ->
                            if (el.graduatedTo.isBlank()) graduateIntakeGram(el)
                            else CalendarNavigator.toDayNote(this, currentDate, el.graduatedTo)
                        }
                        return true
                    }
                    // A tap on the gram body: open its Pickings page if graduated, else its source.
                    CalendarDayPageIntake.gramAt(cx, cy)?.let { gram ->
                        intakeElement(gram.elementId)?.let { el ->
                            if (el.graduatedTo.isNotBlank())
                                CalendarNavigator.toDayNote(this, currentDate, el.graduatedTo)
                            else onImageSource(el)
                        }
                        return true
                    }
                }
            }
        }

        return false
    }

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
                    val bytes = android.util.Base64.decode(element.data, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                            calendarDayService, root, bmp, currentDate, board.key,
                            sourceLink = element.sourceLink, sourceLabel = element.sourceLabel,
                            treatment = false, cardText = element.cardText, sourceFeed = element.sourceFeed
                        )
                    }
                    // Persist the graduation on the intake gram.
                    DayLocks.withDay(currentDate) {
                        val day = calendarDayService.load(root, currentDate, null, java.util.Locale.getDefault())
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
                templateCanvas, com.toolsboox.plugin.michaelfilter.da.IntakePageData(), calendarDay
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

    /** One card already on a Pickings board, offered back to the intake page. */
    private data class PickingGram(
        val date: LocalDate, val boardName: String, val data: String,
        val label: String, val link: String, val cardText: String, val feed: String
    )

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
        val kindTitle = CalendarDayPageIntake.panels.firstOrNull { it.kindKey == kindKey }?.title ?: "STAR SORT"
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
            groups.add(listOf(item("🗑  Remove from Star Sort") { confirmRemoveIntakeGram(element) }))
        }

        com.toolsboox.ot.LedgerContextMenu.show(
            provideSurfaceView(), pressX, pressY,
            if (element != null) "GRAM" else kindTitle, groups
        )
    }

    /** This intake gram onto a Pickings board — the same chooser every other card gets. */
    private fun intakeGramToPickings(element: ImageElement) {
        val bmp = runCatching {
            val bytes = android.util.Base64.decode(element.data, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return
        placeGramToPickings(bmp)
    }

    /** Taking a card off Star Sort is a deletion, so it asks — and says which one it means. */
    private fun confirmRemoveIntakeGram(element: ImageElement) {
        val what = element.cardText.ifBlank { element.sourceLabel }.ifBlank { "this gram" }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Remove from Star Sort?")
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

    /** Repaint the intake template in place (after a gram arrives or leaves). */
    private fun redrawIntakePage() {
        if (notePage != CalendarDayPageIntake.INTAKE_PAGE) return
        CalendarDayPageIntake.drawPage(
            templateCanvas, intakePageData ?: com.toolsboox.plugin.michaelfilter.da.IntakePageData(), calendarDay
        )
        binding.templateImageView.invalidate()
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
    private fun bringPickingIntoIntake(kindKey: String, kindTitle: String) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt())
        }
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Bring in a picking → $kindTitle")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        listCol.addView(android.widget.TextView(ctx).apply {
            text = "Reading your boards…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0)
        })

        lifecycleScope.launch {
            val picks = withContext(Dispatchers.IO) { gatherPickingGrams() }
            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (picks.isEmpty()) {
                listCol.addView(android.widget.TextView(ctx).apply {
                    text = "No pickings with cards yet."; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0)
                })
                return@launch
            }
            for (p in picks) {
                // Downsampled decode — a couple of hundred full-res faces in one list is an OOM
                // on e-ink RAM (the pickGramForReply rule).
                val thumb = runCatching {
                    val bytes = android.util.Base64.decode(p.data, android.util.Base64.DEFAULT)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val target = px(76)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull() ?: continue
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(8), px(6), px(8))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(com.toolsboox.ot.InkMount.wrap(ctx,
                    android.widget.ImageView(ctx).apply {
                        setImageBitmap(thumb); adjustViewBounds = true
                        layoutParams = FrameLayout.LayoutParams(px(76), px(76))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }, taped = false).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(10) }
                })
                row.addView(android.widget.TextView(ctx).apply {
                    text = "${p.label}\n❝ ${p.boardName} · ${p.date}"
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                row.setOnClickListener { dialog.dismiss(); fileIntoIntake(p, kindKey, kindTitle) }
                listCol.addView(row)
            }
        }
        showModal(dialog)
    }

    /** Every card sitting on a Pickings board across the recent window, newest day first. */
    private fun gatherPickingGrams(): List<PickingGram> {
        val out = mutableListOf<PickingGram>()
        val calendarRoot = java.io.File(documentsRoot(), "calendar")
        if (!calendarRoot.exists()) return out
        val ctx = context ?: return out
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .sortedByDescending { it.name }.take(120)
            .forEach { file ->
                val m = Regex("day-(\\d{4})-(\\d{2})-(\\d{2})").find(file.name) ?: return@forEach
                val ld = runCatching {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }.getOrNull() ?: return@forEach
                val day = runCatching { calendarDayService.load(file) }.getOrNull() ?: return@forEach
                val names = runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, ld).associate { it.key to it.name }
                }.getOrNull().orEmpty()
                for (img in day.imageElements) {
                    if (img.data.isBlank() || img.decorative) continue
                    if (!com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(img.page)) continue
                    out.add(PickingGram(
                        ld, names[img.page] ?: "Pickings", img.data,
                        img.cardText.ifBlank { img.sourceLabel }.ifBlank { "Picking" }.take(80),
                        img.sourceLink, img.cardText, img.sourceFeed
                    ))
                    if (out.size >= 200) return out
                }
            }
        return out
    }

    /** File a chosen picking into the intake quarter — the starred-gram path, verbatim. */
    private fun fileIntoIntake(pick: PickingGram, kindKey: String, kindTitle: String) {
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = android.util.Base64.decode(pick.data, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching false
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, com.toolsboox.ot.LedgerPaths.documentsRoot(appCtx), bmp,
                        currentDate, CalendarDayPageIntake.INTAKE_PAGE,
                        sourceLink = pick.link, sourceLabel = pick.label,
                        // The card already wears its treatment in its own pixels — taping it
                        // twice is the one-decoration contract's whole point.
                        treatment = false, cardText = pick.cardText, sourceFeed = pick.feed,
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
