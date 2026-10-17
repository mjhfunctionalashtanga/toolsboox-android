package com.toolsboox.plugin.feeds.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import com.toolsboox.databinding.FragmentFeedArticleBinding
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.plugin.feeds.nw.MinifluxClient
import com.toolsboox.plugin.feeds.ot.ArticleSanitizer
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Reads one feed entry (Miniflux content) in a WebView, with Star / Note / open-in-browser.
 * Star and Note both write a ReadingEvent(article) into today's CalendarDay so the article
 * (and any note) joins the shared corpus + sync — mirroring the book reader's highlights.
 */
@AndroidEntryPoint
class FeedArticleFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var miniflux: MinifluxClient

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_feed_article

    private lateinit var binding: FragmentFeedArticleBinding
    private var entry: FeedEntry? = null
    private var parsed = false

    /** This piece's highlights and notes — the same controller as the Feeds pane's (Michael
     *  2026-10-12): Highlight keeps a passage and its one gram pick; a note joins that pick; the
     *  article's picks are drawn back on it. */
    private val highlights: com.toolsboox.plugin.feeds.ot.ArticleHighlights by lazy {
        com.toolsboox.plugin.feeds.ot.ArticleHighlights(
            fragment = this, service = calendarDayService, root = { documentsRoot() },
            web = { if (::binding.isInitialized) binding.articleWeb else null },
            captureAv = { kind, sink -> captureAvGramDirect(kind, sink) },
            choosePicture = { sink -> captureGalleryDirect(sink) },
            star = { entry?.takeIf { it.id > 0 }?.let { e -> { star(e) } } },
            switchView = { view ->
                when (view) {
                    com.toolsboox.plugin.reader.ui.BookNote.VIEW_READER -> if (!parsed) toggleParse()
                    com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED -> if (parsed) toggleParse()
                    else -> entry?.url?.takeIf { it.startsWith("http") }?.let {
                        highlights.setView(com.toolsboox.plugin.reader.ui.BookNote.VIEW_LIVE)
                        binding.articleWeb.loadUrl(it)
                    }
                }
            },
        )
    }

    /** Marks are an article's: a piece with a web address. */
    private fun marksOn(e: FeedEntry?) = e != null && e.url.startsWith("http", ignoreCase = true)

    /** Aa Type's "Import font…" (Michael, 2026-10-11) — registered at construction like every
     *  launcher; the result goes to the open panel, which chooses the new face at once. */
    private val importFont = com.toolsboox.ot.ReadingFonts.importLauncher(this) { r ->
        aaPanel?.takeIf { it.isShowing }?.onImported(r)
            ?: r.onSuccess { showMessage("Font added · ${it.label}") }
                .onFailure { showMessage(it.message ?: "Couldn't add that font") }
    }
    private var aaPanel: com.toolsboox.ui.plugin.AaTypePanel? = null

    /** Aa Type on the ARTICLE profile; changes reach this piece live, debounced (e-ink). */
    private fun openAaType() {
        aaPanel = com.toolsboox.ui.plugin.AaTypePanel(
            this, com.toolsboox.ot.ReadingType.Profile.ARTICLE, importFont,
            onChange = { p -> if (p == com.toolsboox.ot.ReadingType.Profile.ARTICLE) scheduleArticleType() },
        ).also { it.show() }
    }

    private val applyArticleTypeLater = Runnable {
        if (isAdded && getView() != null && ::binding.isInitialized) {
            paintArticleGround()
            binding.articleWeb.evaluateJavascript(com.toolsboox.ot.ReadingTypes.articleLiveJs(requireContext()), null)
        }
    }

    private fun scheduleArticleType() {
        if (!::binding.isInitialized) return
        binding.articleWeb.removeCallbacks(applyArticleTypeLater)
        binding.articleWeb.postDelayed(applyArticleTypeLater, com.toolsboox.ot.AaType.LIVE_DEBOUNCE_MS)
    }

    /** The WebView's own ground in the article's page colour — every page here is one the app
     *  builds — so a Black page doesn't flash white between pieces (review, Michael 2026-10-11). */
    private fun paintArticleGround() {
        val c = com.toolsboox.ot.ReadingTypes.articleGround(requireContext())
        if (paintedGround != c) { paintedGround = c; binding.articleWeb.setBackgroundColor(c) }
    }
    private var paintedGround: Int? = null

    /** Every article load: the ground first, then the page. */
    private fun loadArticle(e: FeedEntry, content: String) {
        paintArticleGround()
        highlights.setView(if (parsed) com.toolsboox.plugin.reader.ui.BookNote.VIEW_READER
            else com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED)
        binding.articleWeb.loadDataWithBaseURL(articleBaseUrl(), buildHtml(e, content), "text/html", "UTF-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedArticleBinding.bind(view)

        val e = FeedSelection.entry
        if (e == null) {
            showMessage(R.string.feeds_article_missing)
            return
        }

        // JS on so we can read the text selection for highlight-to-annotation.
        binding.articleWeb.settings.javaScriptEnabled = true
        // The long-hold menu's verbs, as in the Feeds pane (the approved plan gave this reader
        // them; Michael 2026-10-12): Highlight keeps the passage and its one pick, Note opens the
        // chooser with it in hand, Search Ledger runs it through the Log's search.
        binding.articleWeb.onSelectionAction = { verb ->
            when (verb) {
                com.toolsboox.plugin.feeds.ot.SelectionMenuWebView.Verb.HIGHLIGHT ->
                    if (marksOn(entry)) highlights.highlight() else entry?.let { annotate(it) }
                com.toolsboox.plugin.feeds.ot.SelectionMenuWebView.Verb.NOTE -> entry?.let { annotate(it) }
                com.toolsboox.plugin.feeds.ot.SelectionMenuWebView.Verb.SEARCH ->
                    com.toolsboox.plugin.feeds.ot.WebSelectionBridge.probe(binding.articleWeb) { sel ->
                        val q = sel.lineSequence().firstOrNull()?.trim().orEmpty()
                        if (q.isBlank()) return@probe
                        com.toolsboox.plugin.calendar.ui.ReadingLogSelection.seedQuery = q
                        androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_reading_log)
                    }
            }
        }

        // Tapping a link opens a menu (Open / Later / Copy). HOLDING a link opens the same
        // menu (finger on the link → Save to Later List); holding plain text still falls
        // through to native selection so passages stay highlightable.
        binding.articleWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                // The highlight must survive the pen-nib tap on ✍ (the Onyx collapse race —
                // see WebSelectionBridge). This reader probed live and lost it every time.
                com.toolsboox.plugin.feeds.ot.WebSelectionBridge.wire(view)
                // Aa Type moved while this page was loading: bring its type block up to date. A
                // no-op when it already is — no re-lay, no refresh (Michael 2026-10-11).
                applyArticleTypeLater.run()
                // The piece's highlights and gram picks, drawn back (Michael 2026-10-12).
                if (marksOn(entry)) highlights.onPageFinished(view)
            }
            // The reading faces (Aa Type, Michael 2026-10-11) answer from inside the app; every
            // other request goes on as before.
            override fun shouldInterceptRequest(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val ctx = context ?: return null
                return com.toolsboox.ot.ReadingFonts.intercept(ctx, request?.url)
            }
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView, request: android.webkit.WebResourceRequest
            ): Boolean {
                val url = request.url?.toString().orEmpty()
                // A tap on a drawn highlight (article-marks.js): its chooser.
                if (highlights.handleUrl(url)) return true
                if (url.startsWith("http")) { showLinkMenu(url); return true }
                // Swallow everything else rather than letting the WebView try to load it. An
                // in-app `ledger://` link (the Bluesky reader's "Answer this" door writes one) has
                // no handler on THIS screen, and a scheme the WebView cannot resolve replaces the
                // article the user was reading with an ERR_UNKNOWN_URL_SCHEME page. Doing nothing
                // is the correct behaviour for a link this surface has no way to honour.
                return true
            }
        }
        binding.articleWeb.setOnLongClickListener {
            val hit = binding.articleWeb.hitTestResult
            val url = hit.extra
            when {
                url.isNullOrBlank() || !url.startsWith("http") -> false
                // A bare picture held: it becomes a photo gram for this article ("a cute photo gram").
                hit.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                    val a = entry
                    com.toolsboox.plugin.feeds.ot.FeedNoteGram.showImageMenu(
                        this, calendarDayService, documentsRoot(), url,
                        a?.title.orEmpty(), a?.feedTitle.orEmpty(), a?.url.orEmpty())
                    true
                }
                // An image that is ALSO a link keeps its link menu, with the photo-gram option added.
                hit.type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> { showLinkMenu(url); true }
                hit.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    showLinkMenu(url, imageUrl = url); true
                }
                else -> false
            }
        }

        // The floating pill retires into the rail — every button unpacked to an icon, the ☰'s
        // long-press article menu kept as the rail item's own hold. Hidden buttons keep the one
        // wiring for anything that performClicks them.
        binding.artPill.visibility = View.GONE
        // A READER OUTSIDE ITS LIST (PanelSeats.Flags.outsideList — iOS's saved-article cover, the
        // same rule on both apps, Michael 2026-10-12): this article was opened from a gram, a link,
        // a source — not from the Feeds list — so the left edge's Back goes back to whatever opened
        // it, never to the list or its drawer. It keeps ☰ Hub at the head and the Feeds seat (the
        // drawer, one tap; hold: this article's menu) while every other panel gives them up.
        articleRail = setupActionRail(
            binding.artRail, "article",
            keepHub = true,
            actions = { listOf(
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_menu, "Feeds",
                    longPress = { showArticleMenu() }) { binding.artMenu.performClick() },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today's page") {
                    binding.artToday.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(
                    if (entry?.starred == true) R.drawable.ic_starred else R.drawable.ic_star,
                    "Star") { binding.artStar.performClick() },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_reader_view, "Reader view") {
                    binding.artParse.performClick()
                },
                // Aa Type, one tap — beside Reader view, as the book reader's Aa seat and the iOS
                // article rail's "Aa" (Michael 09-11: "more clearly a reader setting situation").
                // It was only on the ☰ hold (review, Michael 2026-10-11); that row stays too.
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_text_size, getString(R.string.aa_type)) {
                    openAaType()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_pencil, "Annotate") {
                    binding.artAnnotate.performClick()
                },
                // Every highlight and note on this piece (Michael 2026-10-12: "annotations").
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_pf_book_notes, "Annotations", caption = "Annotations") {
                    if (marksOn(entry)) highlights.showAnnotations()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up2, getString(R.string.feeds_article_first)) {
                    binding.artPrev.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down2, getString(R.string.feeds_article_last)) {
                    binding.artNext.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_left, getString(R.string.reader_prev)) {
                    binding.artPageUp.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_right, getString(R.string.reader_next)) {
                    binding.artPageDown.performClick()
                }
            ) }
        )
        binding.artMenu.setOnClickListener { openRssDirectory() }
        binding.artToday.setOnClickListener {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_calendar_day)
        }
        binding.artMenu.setOnLongClickListener { showArticleMenu(); true }
        binding.artStar.setOnClickListener { entry?.let { star(it) } }
        binding.artParse.setOnClickListener { toggleParse() }
        binding.artPageUp.setOnClickListener { pageWeb(false) }
        binding.artPageDown.setOnClickListener { pageWeb(true) }
        binding.artPrev.setOnClickListener { goToNeighbor(-1) }
        binding.artNext.setOnClickListener { goToNeighbor(1) }
        binding.artAnnotate.setOnClickListener { entry?.let { annotate(it) } }
        setupTapZones()
        // FULL SCREEN IN A READER LEAVES NOTHING ON THE PAGE (Michael 2026-10-12): tucked, the
        // notes pill goes too (the strip stays — its tap opens the column, the way back); a tap in
        // the middle of the piece brings the pill back, a page turn puts it away again
        // ([com.toolsboox.ot.ReaderFullScreen]). The inline transport goes and comes with it
        // (review 2026-10-12, as iOS's Now Playing does): playback carries on, the peek shows it.
        // With the column out, that middle tap tucks it — full screen, as on the iPhone (Michael
        // 2026-10-12: "yes"), the ✕'s own tuck (TuckPanel.readerMiddleTap).
        articleRail?.onReaderChrome = { shown ->
            runCatching { NowPlayingCard.away(binding.artTransport, !shown) }
        }
        articleRail?.reader = true
        com.toolsboox.plugin.feeds.ot.ArticleFullScreen.wire(binding.articleWeb, { articleRail })

        showEntry(e)
    }

    /** This article's column — a reader's ([com.toolsboox.ot.TuckPanel.reader]). */
    private var articleRail: com.toolsboox.ot.TuckPanel? = null

    // --- Capy-style navigation: tap zones + volume keys, both toggle-able ---

    private fun navPrefs() = requireContext().getSharedPreferences("ledger_reader_nav", 0)

    /** An article is read: the left edge's first Back goes back to whatever opened it (the 09-11
     *  rule); that screen's own Back opens the Hub (Michael 2026-10-12). */
    override val edgeReader: Boolean get() = true

    /** Horizontal ⇄ vertical pill, per the wrench toggle (persisted). */
    private fun applyArticlePillOrientation() {
        val vertical = navPrefs().getBoolean("article_pill_vertical", false)
        (binding.artPill as? android.widget.LinearLayout)?.orientation =
            if (vertical) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        applyGripOrientation(binding.artGrip, vertical)
    }
    private fun tapZonesOn() = navPrefs().getBoolean("tap_zones", false)
    // Default ON, matching the book reader and the articles list. It was the only reading surface
    // that defaulted off, so volume keys worked in a book, stopped in an article, and came back in
    // the next book — which reads as a broken key rather than a setting.
    private fun volumeTurnOn() = navPrefs().getBoolean("volume_turn", true)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapZones() {
        binding.tapZones.visibility = if (tapZonesOn()) View.VISIBLE else View.GONE
        binding.tapLeft.setOnTouchListener(MultiTap { onTapZone(right = false, count = it) })
        binding.tapRight.setOnTouchListener(MultiTap { onTapZone(right = true, count = it) })
    }

    /** 1 tap = page, 2 = prev/next article, 3 = feed-list drawer (L) / next feed (R). */
    private fun onTapZone(right: Boolean, count: Int) {
        when (count) {
            1 -> pageWeb(right)
            2 -> goToNeighbor(if (right) 1 else -1)
            else -> if (right) nextFeed() else findNavController().popBackStack()
        }
    }

    /** Page the article a screenful — instantly (e-ink: the next part just appears, no scroll). */
    private fun pageWeb(down: Boolean) {
        articleRail?.readerPageTurn()   // before the page moves (ReaderFullScreen)
        val step = (binding.articleWeb.height * 9 / 10).coerceAtLeast(1)
        binding.articleWeb.scrollBy(0, if (down) step else -step)
    }

    /**
     * ☰ pulls up the directory as an overlay — WITHOUT leaving the article. Picking a feed view
     * returns to the list in that mode; the Ledger sections (almanac/history/…) navigate away.
     */
    /** Link tapped in the article → Open / Add to Later / Copy (moved off long-press so holding can
     *  select). When the held thing was an image-anchor, [imageUrl] adds the photo-gram option.
     *  Through [showModal] like the note chooser — an unrouted popover over an ink-capable
     *  surface leaves the hardware pen armed under it, and the stylus's taps go to the ink. */
    private fun showLinkMenu(url: String, imageUrl: String? = null) {
        val items = mutableListOf("🌐  Open", "🔖  Save to Later List", "📋  Copy link")
        if (imageUrl != null) items.add(com.toolsboox.plugin.feeds.ot.FeedNoteGram.photoGramLabel(requireContext()))
        showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(url)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    1 -> {
                        // A link the reader already knows carries its own presentation — title,
                        // excerpt, featured image — so the Later row shows the thing, not the URL.
                        val known = (listOfNotNull(entry) + FeedSelection.list).firstOrNull { it.url == url }
                        com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                            requireContext(), java.time.LocalDate.now(), "read", url,
                            known?.title, excerpt = known?.blurb, image = known?.imageUrl)
                        showMessage("Saved to Later List", binding.root)
                    }
                    2 -> {
                        val cb = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                        showMessage("Link copied", binding.root)
                    }
                    3 -> {
                        val a = entry
                        com.toolsboox.plugin.feeds.ot.FeedNoteGram.savePhotoGram(
                            this, calendarDayService, documentsRoot(), imageUrl ?: url,
                            a?.title.orEmpty(), a?.feedTitle.orEmpty(), a?.url.orEmpty())
                    }
                }
            }.create())
    }

    /** ☰ from the article: land back on the feeds list with THE feeds directory open — one
     *  directory implementation (FeedsFragment.showFeedDirectory), one look, everywhere. */
    private fun openRssDirectory() {
        FeedSelection.openDirectoryOnArrival = true
        findNavController().popBackStack()
    }

    /** Jump to the first article of the next feed source in the list. */
    private fun nextFeed() {
        val cur = entry ?: return
        val list = FeedSelection.list
        val i = list.indexOfFirst { it.id == cur.id }
        val next = if (i >= 0) list.drop(i + 1).firstOrNull { it.feedTitle != cur.feedTitle } else null
        if (next != null) showEntry(next) else showMessage(R.string.feeds_article_last)
    }

    /** Counts quick taps (ignoring drags) and fires the total after a short window. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class MultiTap(val onCount: (Int) -> Unit) : View.OnTouchListener {
        private var count = 0
        private var downX = 0f; private var downY = 0f
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val fire = Runnable { if (count > 0) onCount(count); count = 0 }
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
                android.view.MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(e.x - downX) < 40 && kotlin.math.abs(e.y - downY) < 40) {
                        count++; handler.removeCallbacks(fire); handler.postDelayed(fire, 320)
                    }
                    return true
                }
            }
            return false
        }
    }

    /** Long-press ☰: browser, read-aloud, and reader settings (tap-zone / volume page-turn). */
    private fun showArticleMenu() {
        val tapOn = tapZonesOn(); val volOn = volumeTurnOn()
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        // Read-aloud is a small state machine: idle → Read; speaking → Pause + Player + Stop; paused → Resume + Stop.
        val readItems: List<Pair<String, () -> Unit>> = when {
            player.isSpeaking -> listOf(
                "⏸  Pause reading" to { player.toggle() },
                // Inline, not modal — the article is where this playback lives (08-12 rule).
                "🎛  Player" to { seatTransport() },
                "⏹  Stop reading" to { player.stop(); seatTransport() })
            player.isPaused -> listOf(
                "▶  Resume reading" to { player.toggle() },
                "⏹  Stop reading" to { player.stop(); seatTransport() })
            else -> listOf("🔊  Read aloud" to { readAloud() })
        }
        val fixed: List<Pair<String, () -> Unit>> = listOf(
            "🌐  Open in browser" to {
                entry?.url?.takeIf { it.isNotBlank() }?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }; Unit
            },
            "🔖  Save to Later List" to {
                val e = entry
                val u = e?.url?.takeIf { it.isNotBlank() }
                if (u != null) {
                    // The open article IS the known entry — save its blurb + featured image along.
                    com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                        requireContext(), java.time.LocalDate.now(), "read", u, e.title,
                        excerpt = e.blurb, image = e.imageUrl)
                    showMessage("Saved to Later List", binding.root)
                } else showMessage("No link on this article", binding.root)
            },
            ((if (tapOn) "☑" else "☐") + "  Tap-zone paging") to { navPrefs().edit().putBoolean("tap_zones", !tapOn).apply(); setupTapZones() },
            ((if (volOn) "☑" else "☐") + "  Volume page-turn") to { navPrefs().edit().putBoolean("volume_turn", !volOn).apply(); Unit },
            "🔄  Rotate screen" to {
                val a = requireActivity()
                a.requestedOrientation =
                    if (a.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            },
            // The quick-note toggle rides every wrench (the reader-only row stranded it).
            ((if ((activity as? com.toolsboox.ui.main.MainActivity)?.quickNoteVisible() != false)
                "🖉  Hide the quick-note button" else "🖉  Show the quick-note button") to {
                val main = activity as? com.toolsboox.ui.main.MainActivity
                main?.setQuickNoteVisible(!(main.quickNoteVisible()))
            })
        )
        val transcriptItems: List<Pair<String, () -> Unit>> =
            entry?.transcriptUrl?.let { url -> listOf("📄  Transcript" to { showTranscript(url) }) } ?: emptyList()
        // How the words are set (Aa Type, Michael 2026-10-11) — LAST, so every row that was here
        // keeps its place (muscle memory); the rail's Aa Type seat is the one-tap door.
        val typeItems: List<Pair<String, () -> Unit>> = listOf("Aa  Type…" to { openAaType() })
        val all = fixed.take(1) + readItems + transcriptItems + fixed.drop(1) + typeItems
        showIconMenu(null, all)
    }

    /** Fetch + show the episode transcript (parity with the iPad). Off-thread fetch, then a
     *  scrollable dialog. Only offered when the feed shipped a transcript. */
    private fun showTranscript(url: String) {
        showMessage("Loading transcript…", binding.root)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { com.toolsboox.plugin.feeds.nw.TranscriptLoader.fetch(url) }
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                .setTitle(entry?.title?.take(60) ?: "Transcript")
                .setMessage(text ?: "Couldn't load the transcript.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Read the article aloud via the process-wide player (keeps playing after you leave). */
    private fun readAloud() {
        val e = entry
        binding.articleWeb.evaluateJavascript(
            "(document.body && document.body.innerText) || ''"
        ) { raw ->
            val player = com.toolsboox.ui.plugin.LedgerPlayer
            player.start(requireContext(), e?.title, e?.feedTitle?.ifBlank { null }, e?.imageUrl, com.toolsboox.plugin.feeds.ot.WebSelectionBridge.unquote(raw))
            // No popup, ever, on this surface — but the transport does arrive ON the page
            // (08-12): the inline card seats itself, seek + ¶ progress + ⏹, and stays across
            // navigation until a stop verb. The modal was the wonk; a card in the surface isn't.
            seatTransport()
        }
    }

    /** Seat (or clear) the inline Now Playing transport over the article — NowPlayingCard.seat. */
    private fun seatTransport() {
        runCatching { NowPlayingCard.seat(binding.artTransport) }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to an article that is still speaking/playing re-seats its inline
        // transport (playback itself never noticed the navigation — LedgerPlayer is app-scoped).
        seatTransport()
        // Volume-key page turn (opt-in), routed through the host activity.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = handler@{ up ->
            if (!volumeTurnOn()) return@handler false
            pageWeb(!up)
            true
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null
    }

    /** Filled star when starred, outline when not — on the hidden pill button and the rail alike. */
    private fun updateStar() {
        binding.artStar.setImageResource(if (entry?.starred == true) R.drawable.ic_starred else R.drawable.ic_star)
        rebuildActionRail("article")
    }

    /** Make the current view obvious: the reader-view icon shows the active-tool chip while in
     *  the parsed / full-article view, and no chip on the feed content. */
    private fun updateParse() {
        binding.artParse.setBackgroundResource(if (parsed) R.drawable.tool_active_bg else android.R.color.transparent)
    }

    /** Render an entry (feed content), refresh the star glyph, mark it read on the server. */
    private fun showEntry(e: FeedEntry) {
        // A new piece is a turn: whatever the centre tap brought back goes.
        articleRail?.readerPageTurn()
        entry = e
        parsed = false
        highlights.open(
            if (marksOn(e)) com.toolsboox.plugin.feeds.ot.ArticleHighlights.Article(
                e.id, e.url, e.title, e.feedTitle, com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED) else null,
            jumpTo = FeedSelection.pendingMarkId.also { FeedSelection.pendingMarkId = null })
        // Synthetic rows (Later/Pickings, negative ids) have no Miniflux entry to star and no
        // day-file lineage to annotate - the buttons sat there visibly dead ("for good reason",
        // as Michael put it). Dead controls hide instead.
        val real = e.id > 0
        binding.artStar.visibility = if (real) View.VISIBLE else View.GONE
        binding.artAnnotate.visibility = if (real) View.VISIBLE else View.GONE
        loadArticle(e, e.content)
        updateStar()
        updateParse()
        // A content-less entry (opened from a Stars & Events row, where only the URL rides along)
        // fetches its readable text immediately — no blank page, no manual ¶ tap.
        if (e.content.isBlank() && e.url.startsWith("http")) toggleParse()
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        // Synthetic entries (id=0) aren't on the server — nothing to mark read.
        if (url.isNotBlank() && token.isNotBlank() && e.id != 0L) {
            lifecycleScope.launch(Dispatchers.IO) { miniflux.markRead(url, token, e.id) }
        }
    }

    /** Step to the previous/next article in the list the reader was opened from. */
    private fun goToNeighbor(delta: Int) {
        val cur = entry ?: return
        val list = FeedSelection.list
        val i = list.indexOfFirst { it.id == cur.id }
        val ni = i + delta
        if (i >= 0 && ni in list.indices) showEntry(list[ni])
        else showMessage(if (delta > 0) R.string.feeds_article_last else R.string.feeds_article_first)
    }

    /** Toggle Miniflux's readability parse (full original content) vs the feed's content. */
    private fun toggleParse() {
        val e = entry ?: return
        if (parsed) {
            parsed = false
            loadArticle(e, e.content)
            updateParse()
            return
        }
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        showMessage(R.string.feeds_article_parsing)
        lifecycleScope.launch {
            // A synthetic entry (id=0, opened from a Stars & Events row) isn't on the server —
            // Miniflux would 400 on its id. Fetch + readability-strip the page directly instead.
            val res = withContext(Dispatchers.IO) {
                if (e.id == 0L) miniflux.fetchPageReadable(e.url)
                else miniflux.fetchContent(url, token, e.id)
            }
            when (res) {
                is MinifluxClient.Result.Ok -> {
                    parsed = true
                    loadArticle(e, res.value.ifBlank { e.content })
                    updateParse()
                }
                is MinifluxClient.Result.Err -> showMessage("⚠️ " + res.message)
            }
        }
    }

    /** A real https baseUrl gives the WebView document a valid origin/referer. A null baseUrl leaves
     *  embeds on an opaque origin, which makes YouTube fail with "error 150" (embedding not allowed).
     *  But the origin must NOT be the entry's own url: that field is feed-supplied, this WebView's
     *  cookie jar holds real logged-in sessions (see SiteWebFragment), and feed HTML rendered on a
     *  session origin could read those cookies. Any real https origin satisfies YouTube, so every
     *  article loads on the sanitizer's sentinel instead. */
    private fun articleBaseUrl(): String = ArticleSanitizer.BASE_URL

    /**
     * YouTube refuses many embeds in a WebView (error 150/152 — embedding disabled), leaving a
     * dead player you can't even tap. Rewrite every YouTube iframe into a tappable thumbnail card
     * that links to the watch page — the link menu / external open handles it from there.
     */
    private fun rewriteYouTubeEmbeds(content: String): String {
        val iframe = Regex(
            """<iframe[^>]*src=["'][^"']*(?:youtube(?:-nocookie)?\.com/embed/|youtu\.be/)([A-Za-z0-9_\-]{6,})[^"']*["'][^>]*>\s*</iframe>""",
            RegexOption.IGNORE_CASE
        )
        return iframe.replace(content) { m ->
            val id = m.groupValues[1]
            """<a href="https://www.youtube.com/watch?v=$id" style="display:block;text-decoration:none">
               <img src="https://img.youtube.com/vi/$id/hqdefault.jpg" style="width:100%;display:block"/>
               <span style="display:block;padding:8px 0;font-weight:bold;color:inherit">▶ Watch on YouTube</span>
               </a>"""
        }
    }

    private fun buildHtml(e: FeedEntry, rawContent: String): String {
        // Somebody else's HTML: allowlist-clean it (scripts/on*/javascript: gone, relative media
        // absolutised against the entry url) BEFORE the YouTube rewrite — the sanitizer lets the
        // YouTube embed iframes through, and cleaning first keeps the rewrite's own inline-styled
        // thumbnail card intact. The selection bridge only reads text, so cleaning costs it nothing.
        val content = rewriteYouTubeEmbeds(ArticleSanitizer.clean(rawContent, e.url))
        val meta = listOf(e.feedTitle, e.author ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        // Face, size, spacing, width and colours are Aa Type's article profile — the same block the
        // in-pane reader carries, so the two views finally agree (Michael 2026-10-11). This page
        // keeps only its frame (pictures, players, code).
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>${com.toolsboox.ot.AaType.ARTICLE_FRAME_CSS}</style>${com.toolsboox.ot.AaType.articleStyleTag(com.toolsboox.ot.ReadingTypes.articleCss(requireContext()))}${if (marksOn(e)) highlights.headScript() else ""}</head><body>
            <h1>${escape(e.title)}</h1>
            <div class="meta">${escape(meta)}</div>
            $content
            </body></html>
        """.trimIndent()
    }

    private fun star(e: FeedEntry) {
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        val wasStarred = e.starred
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                miniflux.toggleStar(url, token, e.id)
                if (!wasStarred) logEvent(e, note = e.blurb.ifBlank { null }, starred = true)
            }
            entry = e.copy(starred = !wasStarred)
            updateStar()
            showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
        }
    }

    /** Highlight → annotate: read the current selection, then choose the note's MEDIUM. A saved
     *  note IS a gram — [com.toolsboox.plugin.feeds.ot.FeedNoteGram] places it on today's Notes
     *  page, same shared flow as the in-pane reader. Works with no selection. */
    private fun annotate(e: FeedEntry) {
        // An article's note is its highlights' chooser (ArticleHighlights, Michael 2026-10-12).
        if (marksOn(e)) { highlights.note(); return }
        com.toolsboox.plugin.feeds.ot.WebSelectionBridge.probe(binding.articleWeb) { sel ->
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.show(
                this, calendarDayService, documentsRoot(),
                selection = sel,
                articleTitle = e.title, feedTitle = e.feedTitle, articleUrl = e.url,
                captureAv = { kind, sink -> captureAvGramDirect(kind, sink) },
                logEvent = { excerpt, note ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { logEvent(e, excerpt = excerpt, note = note) }
                    }
                },
                star = { star(e) }
            )
        }
    }

    /** Append an article ReadingEvent (star marker, highlight passage, note, and/or media). */
    private fun logEvent(
        e: FeedEntry, excerpt: String? = null, note: String? = null,
        attachment: com.toolsboox.da.Attachment? = null, starred: Boolean = false
    ) {
        try {
            val root = documentsRoot()
            val today = LocalDate.now()
            // Under the day lock (design Phase 0): an unlocked load→save here could drop a card or a
            // line another writer was saving to today at the same moment.
            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
            // A today-file that won't read throws here (caught below) — never saved over.
            val day = calendarDayService.loadForWriteOrThrow(root, today, null, Locale.getDefault())
            day.readingEvents.add(
                ReadingEvent(
                    id = "art-${e.id}-${UUID.randomUUID().toString().take(8)}",
                    kind = ReadingEvent.Kind.ARTICLE,
                    date = Date(),
                    title = e.title,
                    source = e.feedTitle.ifBlank { null },
                    url = e.url.ifBlank { null },
                    excerpt = excerpt,
                    note = note,
                    attachments = attachment?.let { mutableListOf(it) },
                    starred = starred
                )
            )
            calendarDayService.save(root, today, day)
            }
        } catch (ex: Exception) {
            Timber.w(ex, "failed to log article event")
        }
    }


    private fun prefs() = EncryptedSharedPreferences.create(
        requireContext(), FeedsFragment.PREFS,
        MasterKey.Builder(requireContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun showLoading() {}
    override fun hideLoading() {}

    override fun onDestroyView() {
        if (::binding.isInitialized) binding.articleWeb.destroy()
        super.onDestroyView()
    }
}
