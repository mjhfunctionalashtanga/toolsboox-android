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
    lateinit var miniflux: com.toolsboox.plugin.feeds.nw.MinifluxClient

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

    // Stars dialog guard: block stacking + ghost-tap reopen (see onCanvasSingleTap).
    private var starsDialogShowing = false
    private var starsDialogDismissedAt = 0L

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

    // Exclude the floating nav + tool pills from the raw stylus reader so the stylus
    // can drag/tap them (and never inks a stray dot over them).
    override fun provideExcludeViews(): List<View> =
        if (::binding.isInitialized) listOf(binding.navWidget, binding.toolWidget) else emptyList()

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

    /** Download any missing star featured-image thumbnails (small, grayscale-friendly PNGs) and
     *  re-render the page when new ones land — the "tiny card" look for Stars & Events. */
    private fun warmStarThumbs(events: List<CalendarEvent>) {
        val ctx = context ?: return
        val wanted = calendarDay.readingEvents.mapNotNull { it.image?.takeIf { u -> u.startsWith("http") } }
            .distinct().filter { !java.io.File(com.toolsboox.plugin.calendar.ot.CalendarDayPage.starThumbFile(ctx, it).absolutePath).exists() }
        if (wanted.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            var got = 0
            for (url in wanted.take(8)) {
                runCatching {
                    val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                    val side = minOf(bmp.width, bmp.height)
                    val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                    val small = android.graphics.Bitmap.createScaledBitmap(square, 128, 128, true)
                    com.toolsboox.plugin.calendar.ot.CalendarDayPage.starThumbFile(ctx, url).outputStream().use {
                        small.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                    }
                    bmp.recycle(); if (square !== bmp) square.recycle(); small.recycle()
                    got++
                }
            }
            if (got > 0) withContext(Dispatchers.Main) {
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
        return when (notePage) {
            // Synthesize page: the engine LIBRARY (built-ins + your own prompts) on the day's gathering.
            "synthesize" -> listOf(listOf(
                com.toolsboox.ot.LedgerContextMenu.Item("⚗  Synthesize the day…") {
                    com.toolsboox.plugin.calendar.ot.SynthEngines.pick(ctx, "Synthesize the day") { e ->
                        runEngine(e, pageMaterial())
                    }
                }
            ))
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

    /** Tap in the Stars & Events section → the day's starred articles; picking one opens its link.
     *  Region: right column (x 670–1270), rows below the weather strip (y from to+21·ceh down). */
    override fun onCanvasSingleTap(cx: Float, cy: Float): Boolean {
        val to = 61f; val ceh = 50f
        if (cx < 670f || cx > 1270f || cy < to + 21 * ceh || cy > to + 35 * ceh) return false
        val stars = calendarDay.readingEvents.filter { !it.url.isNullOrBlank() }
        if (stars.isEmpty()) return false
        // One at a time, with a LONG beat after dismissal — e-ink ghost taps were re-opening
        // this dialog over and over ("star menu continues to pop up"). The stale-event gate
        // in SurfaceFragment.onSingleTapConfirmed is the real fix; this is the backstop.
        if (starsDialogShowing || System.currentTimeMillis() - starsDialogDismissedAt < 1500L) return true
        // Compact custom list (tight rows) — the stock dialog rows sprawl once there are many stars.
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val list = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(6), px(4), px(6), px(8))
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Stars · open").setView(scroll)
            .setNegativeButton("Close", null).create()
        starsDialogShowing = true
        dialog.setOnDismissListener {
            starsDialogShowing = false
            starsDialogDismissedAt = System.currentTimeMillis()
        }
        for (ev in stars) {
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(px(10), px(6), px(10), px(6))
            }
            row.addView(android.widget.TextView(ctx).apply {
                text = "★  " + (ev.excerpt?.takeIf { e -> e.isNotBlank() } ?: ev.title)
                textSize = 14f; setTextColor(0xFF000000.toInt()); maxLines = 2
            })
            val src = ev.source ?: ""
            if (src.isNotBlank()) {
                row.addView(android.widget.TextView(ctx).apply {
                    text = src; textSize = 11f; setTextColor(0xFF777777.toInt()); maxLines = 1
                })
            }
            row.setOnClickListener {
                dialog.dismiss()
                openStarInLedger(ev)
            }
            list.addView(row)
        }
        dialog.show()
        return true
    }

    /** Open a starred article in the Ledger's own feed reader. First choice: find the REAL
     *  Miniflux entry by URL (search by title, match by url) so the article opens with its feed
     *  context — full content, star state, prev/next through the search results. Fallback when
     *  offline / not found: a URL-only entry whose readable text auto-fetches on open. */
    private fun openStarInLedger(ev: com.toolsboox.plugin.calendar.da.v2.ReadingEvent) {
        val url = ev.url ?: return
        val ctx = requireContext()
        lifecycleScope.launch {
            val real = withContext(Dispatchers.IO) {
                runCatching {
                    val p = androidx.security.crypto.EncryptedSharedPreferences.create(
                        ctx, com.toolsboox.plugin.feeds.ui.FeedsFragment.PREFS,
                        androidx.security.crypto.MasterKey.Builder(ctx)
                            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    val base = p.getString(com.toolsboox.plugin.feeds.ui.FeedsFragment.KEY_URL, "").orEmpty()
                    val token = p.getString(com.toolsboox.plugin.feeds.ui.FeedsFragment.KEY_TOKEN, "").orEmpty()
                    if (base.isBlank() || token.isBlank()) return@runCatching null
                    // Punctuation breaks Miniflux's full-text search (tsquery) — search on plain
                    // words only. URLs are compared NORMALIZED (scheme/www/query/slash stripped),
                    // since feed URLs often differ from the starred one by tracking params.
                    fun norm(u: String) = u.substringBefore('#').substringBefore('?')
                        .removeSuffix("/").removePrefix("https://").removePrefix("http://").removePrefix("www.")
                    val q = ev.title.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                        .trim().replace(Regex("\\s+"), " ").split(" ").take(6).joinToString(" ")
                    if (q.isBlank()) return@runCatching null
                    val res = miniflux.search(base, token, q, 50)
                    val results = (res as? com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok)?.value ?: return@runCatching null
                    val hit = results.firstOrNull { r -> norm(r.url) == norm(url) }
                        ?: results.firstOrNull { r -> r.title.equals(ev.title, ignoreCase = true) }
                        ?: results.firstOrNull { r -> r.title.contains(q, ignoreCase = true) }
                    if (hit != null) hit to results else null
                }.getOrNull()
            }
            if (real != null) {
                com.toolsboox.plugin.feeds.ui.FeedSelection.entry = real.first
                com.toolsboox.plugin.feeds.ui.FeedSelection.list = real.second
            } else {
                com.toolsboox.plugin.feeds.ui.FeedSelection.entry = com.toolsboox.plugin.feeds.da.FeedEntry(
                    id = 0, title = ev.title, feedTitle = ev.source ?: "", url = url,
                    author = null, content = "", publishedAt = "", starred = true
                )
                com.toolsboox.plugin.feeds.ui.FeedSelection.list = emptyList()
            }
            androidx.navigation.fragment.NavHostFragment.findNavController(this@CalendarDayFragment)
                .navigate(R.id.FeedArticleFragment)
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
                    val day = calendarDayService.load(root, today, null, java.util.Locale.getDefault())
                    day.ledgerItems.add(item)
                    calendarDayService.save(root, today, day)
                    runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item) }
                    withContext(Dispatchers.Main) {
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
            for (item in orphaned) {
                if (item.id !in calendarDay.deletedElementIds) calendarDay.deletedElementIds.add(item.id)
            }
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

        binding.toolbarDrawing.toolbarSwipeUp.setOnClickListener {
            if (notePage != null) {
                when (if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage)) "pickings" else notePage) {
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
                when (if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(notePage)) "pickings" else notePage) {
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
        // Calendar button + top-left hamburger both open the consolidated Ledger hub.
        binding.toolbarDrawing.toolbarCalendarView.setOnClickListener { showLedgerHub() }
        binding.goAppsButton.visibility = View.VISIBLE
        binding.goAppsButton.setOnClickListener { showLedgerHub() }
        binding.goSectionsButton.visibility = View.GONE

        // Retire the fixed pen strip on the day page — the floating pills + gear now cover
        // everything. The real buttons stay in the (hidden) layout so performClick still
        // drives the Onyx ink actions; nothing about drawing changes.
        binding.toolbarDrawing.root.visibility = View.GONE

        // Floating nav widget: reuse the existing nav actions so nothing about drawing
        // changes. ↑ ↓ step the day's sections (dates live on the top bar's ‹ ›).
        binding.navWidget.visibility = View.VISIBLE
        binding.navUp.setOnClickListener { binding.toolbarDrawing.toolbarSwipeUp.performClick() }
        binding.navDown.setOnClickListener { binding.toolbarDrawing.toolbarSwipeDown.performClick() }

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
        val stamp = currentDate.toString()
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
        val cached = if (fresh) null else com.toolsboox.plugin.calendar.ot.SpiralRing.next(ctxForCache)
        if (cached != null) {
            spiralPick = RootsLine(cached.text, emptyList(), cached.citation)
            binding.spiralLine.setOnClickListener {
                findNavController().navigate(R.id.action_to_ledger_roots)
            }
            binding.spiralLine.visibility = View.VISIBLE
            positionSpiralLine()
            return
        }
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
            binding.spiralLine.setOnClickListener {
                findNavController().navigate(R.id.action_to_ledger_roots)
            }
            binding.spiralLine.visibility = View.VISIBLE
            positionSpiralLine()
        }
    }

    /**
     * What the four lines of the Roots band say.
     *
     * The band is small and fixed, so the text has to be designed for it rather than truncated
     * into it. Three things, in the order they answer the reader's questions: WHY this came back,
     * WHAT it says, and WHERE it came from.
     *
     * The middle part is trimmed at a sentence end where there is one and at a word boundary
     * otherwise — never mid-word. A fragment cut at "the patchwork of cross-bor…" reads as
     * something broken; the same passage ended at a full stop reads as a quotation, and the whole
     * thing is one tap from the page that holds all of it anyway.
     */
    private fun spiralLineText(line: RootsLine, lines: Int, charsPerLine: Int): CharSequence {
        val snippet = line.snippet
        val cite = line.citation.substringAfter("· ", "").trim()
        val tail = if (cite.isBlank()) "" else "  — $cite"
        // The lead-in is the first thing to go. Zoomed out there may be room for two lines, and
        // two lines of the passage plus where it came from beats one line of each — "you have
        // been circling internet · people" is the least of the three once space is short, because
        // the Roots page says it too and the passage does not appear anywhere else on this screen.
        val lead = if (lines < 3 || line.shared.isEmpty()) ""
            else getString(R.string.spiral_lead_circling, line.shared.joinToString(" · "))
        // Budget the passage by what is actually left after the lead-in's line and the tail, so
        // the provenance always lands instead of being the thing that falls off the bottom.
        val bodyLines = (lines - if (lead.isEmpty()) 0 else 1).coerceAtLeast(1)
        // When there genuinely isn't room for both, the TAIL goes — not the passage.
        //
        // The floor of 24 defeated the budgeting it was part of: with one narrow line the budget
        // clamped up to 24, the tail added ~60 more, and 84 characters went into a box that fits
        // eight — so `maxLines` ellipsised, and the thing that fell off the end was the tail. The
        // exact failure this was written to fix, reintroduced by the guard against it.
        val room = bodyLines * charsPerLine
        val keepTail = room - tail.length >= 24
        val shownTail = if (keepTail) tail else ""
        val body = trimToWhole(snippet, (room - shownTail.length).coerceAtLeast(12))

        val text = android.text.SpannableStringBuilder()
        val leadStart = text.length
        if (lead.isNotEmpty()) { text.append(lead); text.append("\n") }
        val bodyStart = text.length; text.append(body)
        val tailStart = text.length; text.append(shownTail)

        // The lead-in and the provenance are context; the passage is the thing. Size and weight
        // say so, rather than punctuation trying to.
        if (bodyStart > leadStart) {
            text.setSpan(android.text.style.RelativeSizeSpan(0.82f), leadStart, bodyStart, 0)
            text.setSpan(android.text.style.ForegroundColorSpan(0xFF666666.toInt()), leadStart, bodyStart, 0)
        }
        if (shownTail.isNotEmpty()) {
            text.setSpan(android.text.style.RelativeSizeSpan(0.82f), tailStart, text.length, 0)
            text.setSpan(android.text.style.ForegroundColorSpan(0xFF888888.toInt()), tailStart, text.length, 0)
        }
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

    /**
     * The Roots band's paper, in design space (1404×1872).
     *
     * Between the Tasks grid — which now ends at row 14 — and the Stars & Events title at
     * `to + 18*ceh`. The template paints the band's title bar and its closing rule; this is the
     * space left between them for the line itself. Kept next to those numbers on purpose: if
     * `CalendarDayPage` moves the band, this has to move with it or the text lands on a rule.
     */
    private val rootsBand = android.graphics.RectF(
        20f + 600f + 60f,                       // lo + cew + 60 — the text inset the panels use
        (1872f - 35 * 50f) / 2f + 14 * 50f,     // to + 14*ceh, under the title bar
        20f + 2 * 600f + 50f - 10f,             // lo + 2*cew + 50, less a hair of right margin
        (1872f - 35 * 50f) / 2f + 18 * 50f      // to + 18*ceh, the closing rule
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

        spiralPick?.let { line ->
            // Pass one: as much as could plausibly fit, so the measurement sees real wrapping.
            val fits = linesThatFit(spiralLineText(line, 6, perLine))
            // Pass two: built for the room there actually is, so the lead-in is dropped and the
            // passage shortened rather than the citation falling off the bottom.
            v.text = spiralLineText(line, fits, perLine)
            v.maxLines = fits
        }
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
     * The sunshine's sections switcher: pick a section and land on its TODAY page; the
     * center emoji then adopts that section (Day/Intake/Gratitude/Pickings/Notes all show
     * their glyph on the pill). Feed / Bookshelf / Ask / Log open their own surfaces.
     */
    private fun showSectionSwitcher() = showGoModal(
        listOf(
            getString(R.string.go_group_day) to buildList {
                // The daily flow in ritual order: Intake → Pickings → Gratitude → Synthesize
                // → Write. Mid-flow, a ⚡ fast-lane to the next step rides on top.
                ritualNextStep()?.let { (page, glyph, label) ->
                    add(GoItem("⚡", "Next · $label") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), page) })
                }
                add(GoItem("☀︎", "Day") { CalendarNavigator.toDayPage(this@CalendarDayFragment, LocalDate.now(), CalendarDay.DEFAULT_STYLE) })
                add(GoItem("🔖", "Intake") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "intake") })
                add(GoItem("❝", "Pickings") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "pickings") })
                add(GoItem("🙏", "Gratitude") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "gratitude") })
                add(GoItem("🔬", "Synthesize") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "synthesize") })
                add(GoItem("✍", "Write") { CalendarNavigator.toDayNote(this@CalendarDayFragment, LocalDate.now(), "write") })
                // Notes lives on the floating pen button (tap = last location, hold = Text
                // Notes) — off this modal per the field notes, one entry point not two.
                add(GoItem("📰", "Feed") { findNavController().navigate(R.id.action_to_feeds) })
                add(GoItem("📚", "Bookshelf") { findNavController().navigate(R.id.action_to_reader) })
                add(GoItem("💬", "Ask") { findNavController().navigate(R.id.action_to_ledger_chat) })
                add(GoItem("🕘", "Log") {
                    ReadingLogSelection.origin = null
                    findNavController().navigate(R.id.action_to_reading_log)
                })
            }
        ),
        anchorTop = false
    )

    /** The next station of the daily ritual after the page we're on, or null off-flow. */
    private fun ritualNextStep(): Triple<String, String, String>? = when (currentNotePage()) {
        "intake" -> Triple("pickings", "❝", "Pickings")
        "pickings" -> Triple("gratitude", "🙏", "Gratitude")
        "gratitude" -> Triple("synthesize", "🔬", "Synthesize")
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
        val onSynth = notePage == "synthesize"
        val onWrite = notePage == "write"
        val tools = buildList {
            add(GoItem("🖊️", "Add text") { binding.toolbarDrawing.toolbarText.performClick() })
            add(GoItem("🖼️", "Add image") { binding.toolbarDrawing.toolbarImage.performClick() })
            if (onSynth) add(GoItem("🃏", "Card…") { showCardMenu() })
            add(GoItem("❝", "Pickings…") { managePickings() })
            // Redundant with the VPS server OCR — off by default, re-enable in Settings.
            if (sharedPreferences.getBoolean(com.toolsboox.plugin.calendar.ot.LedgerExtractor.AUTO_EXTRACT_ENABLED_KEY, false))
                add(GoItem("🗒", "Extract tasks & events") { extractStructured() })
            add(GoItem("📄", "Whole page → text") { wholePageToText() })
            add(GoItem("🗂", "Capture sections") { captureSections() })   // auto-capture toggle now lives in Settings
            if (onSynth) add(GoItem("🔬", "Synthesize · 3 questions") { synthesizeQuestions() })
            if (onSynth || onWrite) add(GoItem("✍", "Writing prompt → Write") { writingPrompts() })
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
            "🕸  Already a $what · $words" to { showItemRhizome(item) }
        }

        showIconMenu(title, existing + listOf<Pair<String, () -> Unit>>(
            flip to {
                selectionAddMode = !adding
                showSelectionMenu(strokes, !adding)
            }
        ) + actions + listOf(
            "📋  Copy text" to { copyTextFromSelection(strokes) },
            "🎓  Educate me" to { educateFromSelection(strokes) },
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
                val prompt = zone.aiPrompt
                if (prompt != null) {
                    val r = com.toolsboox.plugin.chat.nw.LedgerChatService()
                        .run(creds.first, creds.second, creds.third, prompt, text)
                    if (r is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok) values[zone.id + ".ai"] = r.answer
                }
            }
            // Signatures are written per zone as each is read, so an unreadable zone still counts
            // as "seen" and isn't retried on every page-leave. Save only if something moved.
            if (any) com.toolsboox.plugin.calendar.ot.SectionStore.save(appCtx, pageKey, date, values)
        }
    }

    // ---- Synthesize → Write AI pipeline (pickings / books) --------------------------------------

    /** Text of the current page: its typed boxes + OCR of its ink. The raw material for synthesis. */
    private fun pageOcrText(creds: Triple<String, String, String>): String? {
        val pk = notePage ?: "default"
        val typed = if (::calendarDay.isInitialized)
            calendarDay.textElements.filter { it.pageKey == pk }.joinToString("\n") { it.text }.trim() else ""
        val strokes = currentPageStrokes()
        val inked = if (strokes.isNotEmpty()) {
            val rect = android.graphics.RectF(0f, 0f, 1404f, 1872f)
            val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(strokes, rect, 2000)
            com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third) ?: ""
        } else ""
        return listOf(typed, inked).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
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
            val srcPage = notePage?.takeIf { it != "synthesize" } ?: "pickings"
            val srcLabel = if (ps.isPickings(srcPage)) ps.nameOf(requireContext(), currentDate, srcPage)
                else srcPage.replaceFirstChar { it.uppercase() }
            com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(requireContext(), currentDate, lines, "question", srcLabel)
            placeSourcedQuestions(lines, "synthesize", "ledger://$currentDate/$srcPage", srcLabel,
                refresh = notePage == "synthesize")
            showMessage("Placed 3 questions on your Synthesize page — answer them, then head to Write.", binding.root)
            if (notePage != "synthesize") CalendarNavigator.toDayNote(this@CalendarDayFragment, currentDate, "synthesize")
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
        val creds = aiCreds() ?: run { showMessage(getString(R.string.ledger_ai_key_needed), binding.root); return }
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                val text = pageOcrText(creds) ?: return@withContext emptyList<String>()
                runLlm(creds,
                    "Sketch a CLASSIC bullet-pointed essay outline from the reader's notes below: a one-line thesis, " +
                        "then Intro, three Body points each with 1–2 sub-bullets, and a Conclusion. Keep each line short. " +
                        "Output ONLY the outline, one bullet per line.", text)
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
                    val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                        idea.text, "— ${idea.from}", null, 1080, 1080)
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                    val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    val w = 1404f * 0.42f; val h = w * bmp.height / bmp.width
                    val count = calendarDay.imageElements.count { it.page == pageKey }
                    val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (1404f - w).coerceAtLeast(0f))
                    val y = (120f + (count / 3) * (h + 30f)).coerceIn(0f, (1872f - h).coerceAtLeast(0f))
                    calendarDay.imageElements.add(com.toolsboox.da.ImageElement(
                        x = x, y = y, width = w, height = h, data = base64, page = pageKey))
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
    private fun sectionIcon(): Int = when (currentNotePage()) {
        null, "default", CalendarDay.DEFAULT_STYLE -> R.drawable.ic_nav_today
        "pickings" -> R.drawable.ic_quote
        "gratitude" -> R.drawable.ic_heart
        "intake" -> R.drawable.ic_bookmark
        "write" -> R.drawable.ic_edit
        "synthesize" -> R.drawable.ic_swap
        else -> R.drawable.ic_pencil
    }

    private fun sectionEmoji(): String = when (currentNotePage()) {
        // Text-presentation sun (VS15) renders as a solid black glyph — high contrast on e-ink,
        // unlike the washed-out yellow colour emoji. Base glyphs (no VS16) throughout, so the
        // pill's glyph matches the monochrome icons the modals render for the same sections.
        null, "default", CalendarDay.DEFAULT_STYLE -> "☀︎"
        "pickings" -> "❝"
        "gratitude" -> "🙏"
        "intake" -> "🔖"
        "write" -> "✍"
        "synthesize" -> "🔬"
        else -> "✒"
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
            // Zones still capture per-section (captureSections), but we no longer paint
            // boxes/labels over the page — the original template provides the sectioning.
            applyStrokes(Stroke.listDeepCopy(noteStrokes), true)
        } else {
            binding.toolbarDrawing.toolbarProcrastinator.visibility = View.VISIBLE
            val calendarStrokes = calendarDay.calendarStrokes[calendarStyle] ?: listOf()
            CalendarDayPage.drawPage(this.requireContext(), templateCanvas, calendarDay, calendarEvents)
            warmStarThumbs(calendarEvents)
            applyStrokes(Stroke.listDeepCopy(calendarStrokes), true)
        }
        // The template was just drawn into templateBitmap; force the ImageView to repaint so
        // named pages (pickings/gratitude) reliably show on first navigation, not only after a re-swipe.
        binding.templateImageView.invalidate()
        redrawImageSelectionIfActive()

        // A picker/camera result may have arrived before this load finished.
        consumeDeferredImageInsert()

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

        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
