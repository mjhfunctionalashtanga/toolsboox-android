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
            AlertDialog.Builder(ctx)
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
        if (notePage != "synthesize") return emptyList()
        return listOf(listOf(
            com.toolsboox.ot.LedgerContextMenu.Item("?  3 Questions") { runSynthesis("questions", pageMaterial()) },
            com.toolsboox.ot.LedgerContextMenu.Item("✎  Writing Prompt") { runSynthesis("prompt", pageMaterial()) },
            com.toolsboox.ot.LedgerContextMenu.Item("≡  Essay Outline") { runSynthesis("outline", pageMaterial()) }
        ))
    }

    override fun onSynthesizeText(element: com.toolsboox.da.TextElement) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("Synthesize this")
            .setItems(arrayOf("?  3 Questions", "✎  Writing Prompt", "≡  Essay Outline")) { _, which ->
                val kind = listOf("questions", "prompt", "outline")[which]
                runSynthesis(kind, element.text)
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun runSynthesis(kind: String, material: String) {
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
        val (heading, prompt) = when (kind) {
            "questions" -> "? 3 Questions" to
                "From the gathered material, pose the 3 most GENERATIVE questions it raises — questions that would push the author's own thinking further, not comprehension checks. Numbered, one line each. No preamble."
            "prompt" -> "✎ Writing Prompt" to
                "From the gathered material, write ONE vivid writing prompt (2–3 sentences) the author could start writing from immediately, in second person. No preamble."
            else -> "≡ Essay Outline" to
                "From the gathered material, draft an essay outline: a working title on the first line, then 4–6 section headers, each with one guiding sentence. Plain text, no markdown. No preamble."
        }
        android.widget.Toast.makeText(ctx, "Synthesizing…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { chatService.run(provider, key, model, prompt, material) }
            when (res) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok ->
                    placeGeneratedText("$heading\n\n${res.answer.trim()}")
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
        // Compact custom list (tight rows) — the stock dialog rows sprawl once there are many stars.
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val list = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(6), px(4), px(6), px(8))
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        val dialog = AlertDialog.Builder(ctx).setTitle("Stars · open").setView(scroll)
            .setNegativeButton("Close", null).create()
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
        AlertDialog.Builder(ctx)
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
            AlertDialog.Builder(requireContext())
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
    private fun applyWidgetOrientation() {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val narrow = resources.configuration.screenWidthDp < 520
        val vertical = prefs.getBoolean("vertical", narrow)
        val o = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        binding.navWidget.orientation = o
        binding.toolWidget.orientation = o
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
     * their glyph on the pill). Feed / AV / Almanac open their own surfaces.
     */
    private fun showSectionSwitcher() = showGoModal(
        listOf(
            getString(R.string.go_group_day) to listOf(
                GoItem("☀︎", "Day") { CalendarNavigator.toDayPage(this, LocalDate.now(), CalendarDay.DEFAULT_STYLE) },
                GoItem("🔖", "Intake") { CalendarNavigator.toDayNote(this, LocalDate.now(), "intake") },
                GoItem("🙏", "Gratitude") { CalendarNavigator.toDayNote(this, LocalDate.now(), "gratitude") },
                GoItem("❝", "Pickings") { CalendarNavigator.toDayNote(this, LocalDate.now(), "pickings") },
                GoItem("✒️", "Notes") { CalendarNavigator.toDayNote(this, LocalDate.now(), "0") },
                GoItem("📰", "Feed") { findNavController().navigate(R.id.action_to_feeds) },
                GoItem("🎬", "AV") { findNavController().navigate(R.id.action_to_reading_log) },
                GoItem("📆", "Almanac") { CalendarNavigator.toWeekPage(this, LocalDate.now(), locale) }
            )
        ),
        anchorTop = false
    )

    /** Mark which tool is active on the floating pill (mirrors the hidden toolbar's tint). */
    private fun markActiveTool(active: View) {
        for (v in listOf(binding.toolPen, binding.toolEraser, binding.toolLasso)) {
            v.setBackgroundResource(if (v === active) R.drawable.tool_active_bg else 0)
        }
    }

    /** Pen style picker (ballpoint vs calligraphy), showing the active choice. */
    private fun showPenStylePicker() {
        val opts = arrayOf("Ballpoint", "Calligraphy")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.calendar_drawing_toolbar_pen)
            .setSingleChoiceItems(opts, if (penIsCalligraphy()) 1 else 0) { d, w ->
                setPenCalligraphy(w == 1)
                d.dismiss()
            }
            .show()
    }

    /** Minimize a floating pill to grip + one button + expander; toggle back on tap. */
    private fun togglePill(which: String) {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val key = "${which}_collapsed"
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply()
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

    /** Pick the floating-pill layout — an explicit Horizontal / Vertical choice, persisted. */
    private fun flipPillLayout() {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val narrow = resources.configuration.screenWidthDp < 520
        val current = if (prefs.getBoolean("vertical", narrow)) 1 else 0
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pill_layout_title)
            .setSingleChoiceItems(arrayOf(getString(R.string.pill_layout_horizontal), getString(R.string.pill_layout_vertical)), current) { d, which ->
                prefs.edit().putBoolean("vertical", which == 1).apply()
                applyWidgetOrientation()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            if (onSynth || onWrite) add(GoItem("✍️", "Writing prompt → Write") { writingPrompts() })
            if (onSynth) add(GoItem("🗒", "Essay outline → Write") { essayOutline() })
            if (onSynth) add(GoItem("🃏", "Ideas → grid") { showSynthesisIdeas() })
            add(GoItem("👆", "Finger / hand") { binding.toolbarDrawing.toolbarHandTouch.performClick() })
            add(GoItem("🔄", "Rotate screen") { binding.toolbarDrawing.toolbarRotate.performClick() })
        }
        showGoModal(
            listOf(
                "Tools" to tools,
                "Layout" to listOf(
                    GoItem("🔀", "Pill Layout") { flipPillLayout() },
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

    /** The lasso "card" chip: what should the circled strokes become? */
    override fun onSelectionExtract(strokes: List<com.toolsboox.da.Stroke>) {
        if (!::calendarDay.isInitialized || strokes.isEmpty()) return
        showIconMenu(getString(R.string.ledger_selection_title), listOf(
            "🗒  Create task" to { createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK) },
            "📆  Create event" to { createLedgerItem(strokes, com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) },
            "🃏  Create gram" to { gramStudioFromSelection(strokes) },
            "🎬  A/V gram" to { recordAvGram() },
            "📋  Copy text" to { copyTextFromSelection(strokes) },
            "🎓  Educate me" to { educateFromSelection(strokes) },
            "🔍  Find in Ledger" to { findInLedgerFromSelection(strokes) }
        ))
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
        showModal(androidx.appcompat.app.AlertDialog.Builder(ctx)
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
                        com.toolsboox.plugin.calendar.ot.LedgerExtractor.itemWithText(strokes, kind, t, "lasso-ai", dueDate())
                    else null
                } else null
            } ?: com.toolsboox.plugin.calendar.ot.LedgerExtractor.extractStrokes(strokes, kind, "lasso", dueDate())

            if (item == null) { showMessage(R.string.ledger_extract_unreadable, binding.root); return@launch }
            confirmLedgerItem(item, kind) {
                calendarDay.ledgerItems.add(item)
                calendarPattern.updateDay(calendarDay)
                presenter.save(this@CalendarDayFragment, binding, calendarDay, calendarPattern, currentDate, showProgress = false)
                // App-authoritative push: a confirmed task becomes a CalDAV VTODO, a confirmed event a
                // Google Calendar entry (idempotent by id; each no-ops for the other kind).
                lifecycleScope.launch(Dispatchers.IO) {
                    com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item)
                    com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(requireContext(), item)
                }
                val label = if (kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT) "event" else "task"
                showMessage(getString(R.string.ledger_extract_added, label, item.text), binding.root)
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
        showModal(androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
                val text = withContext(Dispatchers.IO) {
                    com.toolsboox.plugin.calendar.nw.Transcribe.audio(requireContext(), java.io.File(attachmentsDir(), att.filename))
                }
                val head = if (kind == com.toolsboox.da.Attachment.Kind.VIDEO) "🎥 Video gram" else "🎤 Audio gram"
                showGramStudio(listOf(head, text ?: "").filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { head })
            }
        } else {
            showMessage(getString(R.string.gram_capture_saved), binding.root)
        }
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

    /** Multi-format gram studio: pick square/portrait/landscape/story, preview, then Share or drop Here. */
    private fun showGramStudio(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val ctx = requireContext()
        val fmt = arrayOf(com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.Format.SQUARE)
        fun make() = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(t, null, null, fmt[0].w, fmt[0].h)
        val preview = android.widget.ImageView(ctx).apply { adjustViewBounds = true; setImageBitmap(make()) }
        val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        for (f in com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.Format.values()) {
            row.addView(android.widget.Button(ctx).also {
                it.text = f.label; it.isAllCaps = false; it.textSize = 12f
                it.setOnClickListener { fmt[0] = f; preview.setImageBitmap(make()) }
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val padPx = (16 * resources.displayMetrics.density).toInt()
        val pickBtn = android.widget.Button(ctx).also { it.text = "❝  Add to Pickings"; it.isAllCaps = false }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(row)
            addView(preview, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.42f).toInt()
            ).apply { topMargin = padPx })
            addView(pickBtn)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Gram")
            .setView(android.widget.ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("Share") { _, _ -> shareGramBitmap(make()) }
            .setNeutralButton("Here") { _, _ -> placeGramBitmap(make()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        pickBtn.setOnClickListener { dialog.dismiss(); placeGramToPickings(make()) }
        // Pause the Onyx pen while the gram studio is up, or stylus taps freeze the surface.
        showModal(dialog)
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Add gram to Pickings")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> addGramToPickingPage(bmp, com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY)
                    1 -> {
                        val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
                        androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle("New pickings").setView(input)
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Pickings · $currentDate")
            .setItems(labels) { _, which ->
                when {
                    which < pages.size -> CalendarNavigator.toDayNote(this, currentDate, pages[which].key)
                    which == pages.size -> {
                        val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
                        androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle("New pickings").setView(input)
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
                        androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle("Rename pickings").setView(input)
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
        val sig = strokesSignature(strokes)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val values = com.toolsboox.plugin.calendar.ot.SectionStore.load(appCtx, pageKey, date)
            if (values["__sig"] == sig) return@launch   // unchanged since last OCR
            for (zone in zones) {
                if (zone.kind != com.toolsboox.plugin.calendar.ot.ZoneKind.TEXT) continue
                val inZone = strokes.filter {
                    android.graphics.RectF.intersects(
                        zone.rect, com.toolsboox.plugin.calendar.ot.LedgerExtractor.boundsOf(listOf(it)))
                }
                if (inZone.isEmpty()) continue
                val bmp = com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderInk(inZone, zone.rect, 1600)
                val text = com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, creds.third)
                    ?: continue
                if (text.isBlank()) continue
                values[zone.id] = text
                val prompt = zone.aiPrompt
                if (prompt != null) {
                    val r = com.toolsboox.plugin.chat.nw.LedgerChatService()
                        .run(creds.first, creds.second, creds.third, prompt, text)
                    if (r is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok) values[zone.id + ".ai"] = r.answer
                }
            }
            // Persist the signature even if nothing OCR'd, so we don't retry unchanged ink each leave.
            values["__sig"] = sig
            com.toolsboox.plugin.calendar.ot.SectionStore.save(appCtx, pageKey, date, values)
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
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
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
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(ctx)
            .setTitle(R.string.webhook_manage)
            .setItems(labels.toTypedArray()) { d, which ->
                if (which == hooks.size) addWebhookDialog()
                else {
                    val h = hooks[which]
                    AlertDialog.Builder(ctx).setTitle(h.name)
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
        AlertDialog.Builder(ctx).setTitle(R.string.webhook_add).setView(box)
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
        else -> R.drawable.ic_pencil
    }

    private fun sectionEmoji(): String = when (currentNotePage()) {
        // Text-presentation sun (VS15) renders as a solid black glyph — high contrast on e-ink,
        // unlike the washed-out yellow colour emoji.
        null, "default", CalendarDay.DEFAULT_STYLE -> "☀︎"
        "pickings" -> "❝"
        "gratitude" -> "🙏"
        "intake" -> "🔖"
        else -> "✒️"
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
