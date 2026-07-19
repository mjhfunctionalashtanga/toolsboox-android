package com.toolsboox.plugin.feeds.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import android.os.Build
import android.os.Environment
import com.toolsboox.databinding.FragmentFeedsBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.plugin.feeds.nw.MinifluxClient
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
 * The in-app Feed Ledger — a native Miniflux list. Tapping an entry opens it in the
 * article reader; the star glyph saves it into the day JSON (shared corpus + sync).
 * Credentials (URL + token) live in EncryptedSharedPreferences.
 */
@AndroidEntryPoint
class FeedsFragment @Inject constructor() : ScreenFragment(), com.toolsboox.ui.plugin.ReturnAnchorProvider {

    /** If an article is open, stash a return anchor so the Day page can jump straight back to it. */
    override fun prepareReturnAnchor() {
        val e = currentArticle
        if (e != null) {
            FeedSelection.pendingInPaneEntry = e
            FeedSelection.list = adapter.current()
            com.toolsboox.ui.plugin.LedgerReturn.set(R.id.action_to_feeds, e.title)
        } else {
            com.toolsboox.ui.plugin.LedgerReturn.clear()
        }
    }

    @Inject
    lateinit var miniflux: MinifluxClient

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    private var navBar: com.toolsboox.plugin.calendar.ui.CalendarNavBarHost? = null
    private var navAnchor: java.time.LocalDate = java.time.LocalDate.now()
    private var navGranularity: String = "day"   // day|week|month|quarter|year (from the nav slots)

    override val view = R.layout.fragment_feeds

    private lateinit var binding: FragmentFeedsBinding
    private lateinit var adapter: FeedEntryAdapter
    private var loading = false
    private var allEntries: List<FeedEntry> = emptyList()

    override fun onResume() {
        super.onResume()
        // Returning from the article with "open the directory" flagged (the ☰ path): entries are
        // already loaded, so fire it here — the refresh success path only covers fresh loads.
        if (FeedSelection.openDirectoryOnArrival && allEntries.isNotEmpty()) {
            FeedSelection.openDirectoryOnArrival = false
            showFeedDirectory()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedsBinding.bind(view)

        adapter = FeedEntryAdapter(emptyList(), onOpen = ::openEntry, onStar = ::toggleStar)
        binding.feedsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.feedsRecycler.adapter = adapter
        binding.feedsRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        // "Mark as read on scroll": as rows scroll off the top, mark them read (Miniflux + the list).
        binding.feedsRecycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || markReadMode() != "scroll") return
                val first = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                val list = adapter.current()
                for (i in 0 until first) list.getOrNull(i)?.let { markEntryRead(it, notify = false) }
            }
        })

        val prefs = prefs()
        binding.urlEdit.setText(prefs.getString(KEY_URL, ""))
        binding.tokenEdit.setText(prefs.getString(KEY_TOKEN, ""))

        // The pill's wrench now opens the standalone controls modal (mark-read / rotate / layout /
        // feed settings) — one wrench, not two.
        binding.settingsButton.setOnClickListener { showFeedControls() }
        binding.saveSettingsButton.setOnClickListener {
            prefs().edit()
                .putString(KEY_URL, binding.urlEdit.text.toString().trim())
                .putString(KEY_TOKEN, binding.tokenEdit.text.toString().trim())
                .apply()
            binding.settingsPanel.visibility = View.GONE
            showMessage(R.string.feeds_saved)
            refresh()
        }
        binding.refreshButton.setOnClickListener { refresh() }
        // Top button = ☰ jump-to-section menu; the RSS-feed button (pill centre) toggles
        // the directory drawer (their locations are swapped).
        // Top-left = RSS button that pops the directory drawer in/out.
        binding.ledgerButton.setImageResource(R.drawable.ic_feed)
        binding.ledgerButton.setOnClickListener {
            applyDirectoryDrawer(binding.directoryScroll.visibility != View.VISIBLE)
        }
        applyDirectoryDrawer(prefs.getBoolean(KEY_DIR_OPEN, true))
        binding.viewToggleButton.setOnClickListener {
            mode = if (mode == "stars") "feed" else "stars"
            refresh()
        }

        // Persistent floating nav pill: ↑↓ page the list, centre ☰ opens the directory.
        // On e-ink the page must *appear* instantly — a plain scrollBy, never a smooth animation.
        binding.feedsPageUp.setOnClickListener { pageBy(-1) }
        binding.feedsPageDown.setOnClickListener { pageBy(1) }
        // Pill centre = the RSS-feed button that pops the directory drawer in/out.
        // Pill centre = today-then-menu (unified across surfaces): first tap returns to
        // today's live feed; a second tap (already on today) opens the section menu.
        binding.feedsGoto.setImageResource(R.drawable.ic_nav_today)
        binding.feedsGoto.setOnClickListener {
            if (navGranularity == "day" && navAnchor == java.time.LocalDate.now()) {
                showLedgerDirectory()
            } else {
                navGranularity = "day"; navAnchor = java.time.LocalDate.now()
                renderNav(); onDateChanged()
            }
        }
        // In-pane article actions (visible only while reading): star · note · reader-toggle · TTS.
        binding.feedsStar.setOnClickListener { currentArticle?.let { starArticle(it) } }
        binding.feedsNote.setOnClickListener { currentArticle?.let { noteArticle(it) } }
        binding.feedsParsed.setOnClickListener {
            showParsed = !showParsed; renderArticle()
            showMessage(if (showParsed) "Reader view" else "Original", binding.root)
        }
        binding.feedsTts.setOnClickListener { toggleArticleTts() }
        // Touch pagination in the in-pane article reader (like the book reader): tap sides to turn.
        binding.articleTapLeft.setOnClickListener { pageBy(-1) }
        binding.articleTapRight.setOnClickListener { pageBy(1) }
        binding.feedsLater.setOnClickListener { currentArticle?.let { addToLater(it) } }
        // Shrunk article-drawer: ⏫ last article · ‹ page up · › page down · ⏬ next article.
        binding.feedsDwPrev.setOnClickListener { openAdjacentArticle(-1) }
        binding.feedsDwPgup.setOnClickListener { pageBy(-1) }
        binding.feedsDwPgdn.setOnClickListener { pageBy(1) }
        binding.feedsDwNext.setOnClickListener { openAdjacentArticle(1) }

        binding.feedsPill.bringToFront()
        // Grip tap: while reading, shrink the pill to a slim drawer (↑ ↓ · next · back); tapping
        // again drops the full set back out.
        makeDraggable(binding.feedsGrip, binding.feedsPill, "feeds_pill") {
            if (currentArticle != null) { pillShrunk = !pillShrunk; applyArticlePill() }
        }

        applyFeedsPillOrientation()

        // System Back closes an open in-pane article (returns to the list) before leaving.
        articleBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeArticlePane(restoreDrawer = false) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, articleBackCallback!!)

        // The real Almanac navigator strip (same as Tasks & Events) — arrows step the
        // date in place; the day/week/month slots jump into the calendar.
        navBar = com.toolsboox.plugin.calendar.ui.CalendarNavBarHost(
            requireContext(), binding.navigatorImageView, this,
            onStepDay = { d ->
                val dir = if (d.isBefore(navAnchor)) -1 else 1
                navAnchor = stepByGranularity(navAnchor, dir)
                renderNav(); onDateChanged()
            },
            onSelectPeriod = { g, d -> navGranularity = g; navAnchor = d; renderNav(); onDateChanged() }
        )
        renderNav()

        // Honour the view/kind chosen from the hub (feed / stars / later, + read/watch/listen).
        val (m, k) = FeedSelection.consume()
        mode = m; kindFilter = k
        refresh()

        // The article's ☰ asks the list to pop the RSS directory open on return.
        if (FeedSelection.openDirectory) {
            FeedSelection.openDirectory = false
            binding.root.post { showFeedDirectory() }
        }
        // Open an article straight into the in-pane reader (from a gram's "Go to source").
        FeedSelection.pendingInPaneEntry?.let { e ->
            FeedSelection.pendingInPaneEntry = null
            binding.root.post { openEntry(e) }
        }
    }

    /** Current view: "feed" (unread RSS) · "stars" · "later" · "local" (no-server RSS). */
    private var mode: String = "feed"
    /** Optional read/watch/listen lens. */
    private var kindFilter: String? = null
    /** Selected local subscription (null = all local) when mode == "local". */
    private var localSub: com.toolsboox.plugin.feeds.nw.LocalSub? = null
    /** The active smart feed (saved search) when mode == "smart". */
    private var smartFeed: com.toolsboox.plugin.feeds.nw.SmartFeed? = null
    /** Enabled only while an in-pane article is open (so Back returns to the list). */
    private var articleBackCallback: androidx.activity.OnBackPressedCallback? = null
    /** The article currently open in the in-pane reader (for star / note / parsed toggle). */
    private var currentArticle: FeedEntry? = null
    /** Reader-view (parsed) vs original (unparsed) for the in-pane article. */
    private var showParsed: Boolean = true

    /** OPML file picker (parity with the iPad's fileImporter). */
    private val opmlPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use {
                    com.toolsboox.plugin.feeds.nw.LocalFeedStore.importOpml(
                        requireContext(), com.toolsboox.plugin.feeds.nw.RssParser.opmlUrls(it))
                } ?: 0
            }
            showMessage("Imported $n feed(s)", binding.root)
            renderDirectory()
        }
    }

    private fun applyKind(entries: List<FeedEntry>): List<FeedEntry> {
        val k = kindFilter ?: return entries
        return entries.filter { it.kind == k }
    }

    private fun switchToLocal(sub: com.toolsboox.plugin.feeds.nw.LocalSub?) {
        mode = "local"; localSub = sub; kindFilter = null; refresh()
    }

    private fun loadLocal() {
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.feeds.nw.LocalFeedStore.entries(requireContext(), localSub)
            }
            loading = false
            binding.progress.visibility = View.INVISIBLE
            allEntries = entries
            adapter.submit(entries)
            if (entries.isEmpty()) showEmpty("No local feed items yet.") else binding.emptyText.visibility = View.GONE
            renderDirectory()
        }
    }

    private fun showAddLocalFeed() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "https://example.com/feed.xml"; setSingleLine(); setText("https://")
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Add local feed")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.length > 8) lifecycleScope.launch {
                    val sub = withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.feeds.nw.LocalFeedStore.add(requireContext(), url)
                    }
                    if (sub == null) showMessage("Not a valid feed", binding.root) else switchToLocal(sub)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportOpml() {
        runCatching {
            val opml = com.toolsboox.plugin.feeds.nw.LocalFeedStore.exportOpml(requireContext())
            val dir = java.io.File(requireContext().cacheDir, "cards").apply { mkdirs() }
            val file = java.io.File(dir, "ledger-feeds.opml")
            file.writeText(opml, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file)
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/xml")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export OPML"))
        }.onFailure { showMessage("Export failed", binding.root) }
    }

    /** In-feed search: filter the loaded list by title/blurb (Miniflux + local). */
    private fun showFeedSearch() {
        val input = android.widget.EditText(requireContext()).apply { hint = "Search all feeds"; setSingleLine() }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim().lowercase()
                // Search across ALL loaded feeds/lenses (not just the current view); empty clears back.
                adapter.submit(if (q.isEmpty()) applyKind(allEntries) else allEntries.filter {
                    it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q) ||
                        it.feedTitle.lowercase().contains(q)
                })
            }
            .setNeutralButton("Clear") { _, _ -> adapter.submit(applyKind(allEntries)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        if (loading) return
        // Changing feed/view returns to the list from any open in-pane article.
        if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
        binding.viewToggleButton.setText(if (mode == "stars") R.string.feeds_view_unread else R.string.feeds_view_starred)
        if (mode == "later") { loadLaterList(); return }
        if (mode == "local") { loadLocal(); return }
        if (mode == "smart") { loadSmart(); return }
        if (mode == "asklog") { loadAskLog(); return }
        if (mode == "pickings") { loadPickingsFeed(); return }

        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) {
            binding.settingsPanel.visibility = View.VISIBLE
            showEmpty(getString(R.string.feeds_need_creds))
            return
        }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (mode) {
                    "stars" -> miniflux.fetchStarred(url, token)
                    "read" -> miniflux.fetchRead(url, token)
                    else -> miniflux.fetchUnread(url, token)
                }
            }
            loading = false
            binding.progress.visibility = View.INVISIBLE
            when (result) {
                is MinifluxClient.Result.Ok -> {
                    allEntries = result.value
                    com.toolsboox.plugin.feeds.nw.FeedCache.saveEntries(requireContext(), cacheKey(), result.value)
                    prefetchParsed(url, token, result.value)   // download readable (parsed) versions offline
                    val filter = FeedSelection.filterFeedTitle
                    FeedSelection.filterFeedTitle = null
                    var shown = applyKind(result.value)
                    if (filter != null) shown = shown.filter { it.feedTitle == filter }
                    shown = filterByNavDay(shown)
                    adapter.submit(shown)
                    if (shown.isEmpty()) showEmpty(getString(R.string.feeds_empty))
                    else binding.emptyText.visibility = View.GONE
                    renderDirectory()
                    if (FeedSelection.openDirectoryOnArrival) {
                        FeedSelection.openDirectoryOnArrival = false
                        showFeedDirectory()
                    }
                }
                is MinifluxClient.Result.Err -> {
                    // Offline: fall back to the cached (offline-readable) copy.
                    val cached = com.toolsboox.plugin.feeds.nw.FeedCache.loadEntries(requireContext(), cacheKey())
                    if (cached.isNotEmpty()) {
                        allEntries = cached
                        adapter.submit(filterByNavDay(applyKind(cached)))
                        renderDirectory()
                    } else showEmpty("⚠️ " + result.message)
                }
            }
        }
    }

    private fun cacheKey(): String = "${mode}_${kindFilter ?: "all"}"

    /** Best-effort: fetch + cache the parsed (readability) HTML for each entry so the
     *  in-pane reader has an offline-readable version. Runs in the background. */
    private fun prefetchParsed(url: String, token: String, entries: List<FeedEntry>) {
        lifecycleScope.launch(Dispatchers.IO) {
            for (e in entries.take(60)) {
                if (com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(requireContext(), e.id) != null) continue
                val res = runCatching { miniflux.fetchContent(url, token, e.id) }.getOrNull()
                if (res is MinifluxClient.Result.Ok && res.value.isNotBlank()) {
                    com.toolsboox.plugin.feeds.nw.FeedCache.saveContent(requireContext(), e.id, res.value)
                }
            }
        }
    }

    /** Later List: the read-later links intaked across recent days (MichaelFilter intake
     *  sidecar), as feed rows — read/watch/listen by the intake kind. */
    private fun loadLaterList() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                // Pull recent days' intake from other devices first, so links filed elsewhere show up.
                val ctx = requireContext().applicationContext
                for (d in 0L..14L) runCatching {
                    com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.pullLatest(ctx, java.time.LocalDate.now().minusDays(d))
                }
                gatherLaterList()
            }
            binding.progress.visibility = View.INVISIBLE
            allEntries = entries
            val shown = applyKind(entries)
            adapter.submit(shown)
            if (shown.isEmpty()) showEmpty(getString(R.string.feeds_later_empty))
            else binding.emptyText.visibility = View.GONE
        }
    }

    /** Feed Pickings: your pickings boards (that hold grams or ink) surfaced as feed rows. Tapping
     *  a row jumps straight to that board. */
    private fun loadPickingsFeed() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { gatherPickingsBoards() }
            binding.progress.visibility = View.INVISIBLE
            allEntries = entries
            adapter.submit(entries)
            if (entries.isEmpty()) showEmpty("No pickings boards with content yet.")
            else binding.emptyText.visibility = View.GONE
        }
    }

    private fun gatherPickingsBoards(): List<FeedEntry> {
        val ctx = requireContext().applicationContext
        val root = documentsRoot()
        val locale = java.util.Locale.getDefault()
        val out = mutableListOf<FeedEntry>()
        var id = 0L
        for (d in 0L..60L) {
            val date = java.time.LocalDate.now().minusDays(d)
            val boards = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, date)
            val day = runCatching { calendarDayService.load(root, date, null, locale) }.getOrNull() ?: continue
            for (b in boards) {
                val grams = day.imageElements.count { it.page == b.key }
                val strokes = day.noteStrokes[b.key]?.size ?: 0
                if (grams == 0 && strokes == 0) continue        // only boards with content
                val bits = listOfNotNull(
                    if (grams > 0) "$grams gram${if (grams == 1) "" else "s"}" else null,
                    if (strokes > 0) "$strokes stroke${if (strokes == 1) "" else "s"}" else null
                ).joinToString(" · ")
                out += FeedEntry(
                    id = id++, title = b.name, feedTitle = "Pickings · $date",
                    url = "ledger://pickings/$date/${b.key}", author = null,
                    content = "<p>$bits</p>", publishedAt = date.toString(),
                    starred = false, category = "pickings-board"
                )
            }
        }
        return out
    }

    /** Saved Ask-my-Ledger answers, surfaced as a local feed. */
    private fun loadAskLog() {
        val entries = com.toolsboox.plugin.feeds.nw.AskFeedStore.list(requireContext())
        allEntries = entries
        adapter.submit(applyKind(entries))
        if (entries.isEmpty()) showEmpty("No saved answers yet — save one from Ask my Ledger.")
        else binding.emptyText.visibility = View.GONE
    }

    private fun gatherLaterList(): List<FeedEntry> {
        val ctx = requireContext().applicationContext
        val out = mutableListOf<FeedEntry>()
        val kinds = listOf("read", "watch", "listen", "educate")
        var idSeed = 0L
        for (d in 0L..120L) {
            val date = java.time.LocalDate.now().minusDays(d)
            val data = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.load(ctx, date)
            for (kind in kinds) {
                data.typedFor(kind).lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(line).firstOrNull() ?: line
                    val title = line.replace(url, "").trim().ifBlank { url }
                    // Reuse the RSS kind field via category so applyKind() sees read/watch/listen.
                    out += FeedEntry(
                        id = idSeed++, title = title, feedTitle = "Later · $kind",
                        url = url, author = null, content = "", publishedAt = date.toString(),
                        starred = false, category = if (kind == "educate") "read" else kind
                    )
                }
            }
        }
        return out
    }

    private fun showEmpty(text: String) {
        binding.emptyText.text = text
        binding.emptyText.visibility = View.VISIBLE
    }

    /** Open the article IN the right pane (feed directory + list stay put); Back returns.
     *  A podcast/"listen" entry with real audio plays through the player instead of opening the pane. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openEntry(entry: FeedEntry) {
        // A Feed Pickings row jumps straight to its board (ledger://pickings/<date>/<key>).
        if (entry.url.startsWith("ledger://pickings/")) {
            val parts = entry.url.removePrefix("ledger://pickings/").split("/")
            if (parts.size >= 2) {
                val date = runCatching { java.time.LocalDate.parse(parts[0]) }.getOrNull() ?: java.time.LocalDate.now()
                com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(this, date, parts.drop(1).joinToString("/"))
            }
            return
        }
        if (entry.kind == "listen" && entry.audioUrl != null) { playEntryAudio(entry); return }
        if (markReadMode() != "off") markEntryRead(entry)   // per the user's "mark as read" setting
        FeedSelection.entry = entry
        FeedSelection.list = adapter.current()
        binding.articleWeb.settings.apply {
            javaScriptEnabled = true
            // YouTube (and other) iframe players need DOM storage + a WebChromeClient, and must be
            // allowed to start without a gesture — otherwise the player errors out (150/152).
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Reliable double-tap + pinch zoom (no on-screen zoom buttons). Needs a wide viewport
            // + overview mode so double-tap actually has something to zoom to.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.articleWeb.webChromeClient = android.webkit.WebChromeClient()
        binding.articleTitle.text = entry.title
        currentArticle = entry
        renderArticle()
        binding.feedsRecycler.visibility = View.GONE
        binding.emptyText.visibility = View.GONE
        // Give the reader the full pane — collapse the left directory drawer (not persisted).
        setDirectoryDrawerVisible(false)
        binding.articlePane.visibility = View.VISIBLE
        binding.articleTapZones.visibility = if (prefs().getBoolean("feeds_tap_zones", true)) View.VISIBLE else View.GONE
        binding.articleBack.setOnClickListener { closeArticlePane(restoreDrawer = false) }
        // Open-externally fallback: a video whose owner blocks embedding errors (150/152) in the
        // in-pane player — this opens the original URL in the YouTube app / browser instead.
        val ext = entry.url.takeIf { it.startsWith("http", ignoreCase = true) }
        binding.articleBrowser.visibility = if (ext != null) View.VISIBLE else View.GONE
        binding.articleBrowser.setOnClickListener {
            ext?.let {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(it)))
                }.onFailure { showMessage(R.string.feeds_open_external_failed, binding.root) }
            }
        }
        articleBackCallback?.isEnabled = true
        // Keep the shrunk/expanded drawer state across next/last-article jumps (don't reset it here).
        applyArticlePill()
    }

    /** Render the open article as parsed (reader view) or unparsed (original). */
    private fun renderArticle() {
        val entry = currentArticle ?: return
        val parsed = com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(requireContext(), entry.id)
        val body = if (showParsed && !parsed.isNullOrBlank()) parsed else entry.content
        val httpUrl = entry.url.takeIf { it.startsWith("http", ignoreCase = true) }
        // No stored/parsed article (e.g. a gram's external source, content left blank) → load the
        // live page directly in this pane's WebView rather than showing an empty article.
        if (body.isBlank() && httpUrl != null) {
            binding.articleWeb.loadUrl(httpUrl)
            return
        }
        // A real https baseUrl gives the document a valid origin/referer; a null baseUrl makes YouTube
        // (and other) embeds fail with "error 150" (embedding-not-allowed for the opaque origin).
        val base = httpUrl ?: "https://www.youtube.com"
        binding.articleWeb.loadDataWithBaseURL(base, buildArticleHtml(entry, body), "text/html", "UTF-8", null)
    }

    /** Whether the pill is shrunk to the slim article drawer (↑ ↓ · next · back). */
    private var pillShrunk = false

    /**
     * Show the right pill buttons for the current mode: the list, the full article view, or the
     * shrunk article drawer (only ↑/↓ + next + back). The grip toggles shrunk while reading.
     */
    private fun applyArticlePill() {
        val inArticle = currentArticle != null
        if (!inArticle) pillShrunk = false
        val shrunk = inArticle && pillShrunk
        val fullArticle = inArticle && !shrunk
        fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE
        // List/full controls collapse away in the slim drawer.
        binding.refreshButton.visibility = vis(!shrunk)
        binding.settingsButton.visibility = vis(!shrunk)
        binding.feedsGoto.visibility = vis(!shrunk)
        // ↑/↓ page-nav is present in every mode.
        binding.feedsPageUp.visibility = View.VISIBLE
        binding.feedsPageDown.visibility = View.VISIBLE
        // ↑/↓ list-paging carrots hide in the shrunk drawer (the drawer has its own ‹ › page nav).
        binding.feedsPageUp.visibility = vis(!shrunk); binding.feedsPageDown.visibility = vis(!shrunk)
        // Article actions only in the full article view.
        binding.feedsStar.visibility = vis(fullArticle); binding.feedsNote.visibility = vis(fullArticle)
        binding.feedsParsed.visibility = vis(fullArticle); binding.feedsTts.visibility = vis(fullArticle)
        binding.feedsLater.visibility = vis(fullArticle)
        // The shrunk drawer's own four controls.
        binding.feedsDwPrev.visibility = vis(shrunk); binding.feedsDwPgup.visibility = vis(shrunk)
        binding.feedsDwPgdn.visibility = vis(shrunk); binding.feedsDwNext.visibility = vis(shrunk)
    }

    /** Open the previous (dir=-1) or next (dir=+1) article in the displayed list. */
    private fun openAdjacentArticle(dir: Int) {
        val cur = currentArticle ?: return
        val list = applyKind(allEntries)
        val i = list.indexOfFirst { it.id == cur.id }
        val j = i + dir
        if (i >= 0 && j in list.indices) openEntry(list[j])
        else showMessage(if (dir > 0) R.string.feeds_no_next else R.string.feeds_no_prev, binding.root)
    }

    /** Play an entry's audio through the shared player — the offline copy if we cached it, else the
     *  stream. Used for "listen" feed entries (podcasts). */
    private fun playEntryAudio(e: FeedEntry) {
        val url = e.audioUrl ?: return
        val src = com.toolsboox.plugin.feeds.nw.LaterMedia.playableSource(requireContext(), url)
        com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
            requireContext(), e.title, e.feedTitle.ifBlank { null }, e.imageUrl, src
        )
        com.toolsboox.ui.plugin.LedgerPlayer.showModal(requireContext())
    }

    /** Add an entry to today's Later List (no star needed). For media (a podcast/video enclosure) we
     *  also kick off a background download so it's available offline. */
    private fun addToLater(e: FeedEntry) {
        val kind = when (e.kind) { "watch" -> "watch"; "listen" -> "listen"; else -> "read" }
        val link = e.audioUrl ?: e.url
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val today = java.time.LocalDate.now()
            val data = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.load(ctx, today)
            val line = listOf(e.title.trim(), link).filter { it.isNotBlank() }.joinToString(" ")
            val existing = data.typedFor(kind)
            if (!existing.contains(link)) {
                data.setTypedFor(kind, if (existing.isBlank()) line else existing.trimEnd() + "\n" + line)
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.save(ctx, today, data)
            }
        }
        val media = e.audioUrl
        if (media != null) {
            com.toolsboox.plugin.feeds.nw.LaterMedia.download(requireContext(), media)
            showMessage(R.string.feeds_added_later_dl, binding.root)
        } else {
            showMessage(R.string.feeds_added_later, binding.root)
        }
    }

    private fun closeArticlePane(restoreDrawer: Boolean = true) {
        binding.articlePane.visibility = View.GONE
        binding.articleWeb.loadUrl("about:blank")
        binding.feedsRecycler.visibility = View.VISIBLE
        // On an explicit Back, return straight to the article list — don't pop the drawer open as an
        // intermediate step. Only restore it when the article was closed by picking a feed (in the drawer).
        setDirectoryDrawerVisible(restoreDrawer && prefs().getBoolean(KEY_DIR_OPEN, true))
        articleBackCallback?.isEnabled = false
        currentArticle = null
        applyArticlePill()
        // Intentionally do NOT stop the player — read-aloud keeps going after you leave the article;
        // control it from the "▶ Now Playing" modal.
    }

    /** Render a card from article text and place it on a pickings board (chooser). */
    private fun articleGramToPickings(text: String) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                    text.ifBlank { "Clipping" }, null, null,
                    com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.Format.SQUARE.w,
                    com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.Format.SQUARE.h)
            }
            val src = currentArticle?.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: ""
            com.toolsboox.plugin.calendar.ot.PickingsPlacement.chooseAndPlace(
                this@FeedsFragment, calendarDayService, documentsRoot(), bmp,
                sourceLink = src, sourceLabel = currentArticle?.title ?: "")
        }
    }

    /** Star the open article. `toggleStar` is the single place that logs the star to the Ledger
     *  (local + Miniflux paths), so we don't log a second time here — that used to double-log. */
    private fun starArticle(e: FeedEntry) {
        toggleStar(e)
    }

    /** Read the current selection (if any), then note/annotate the open article. */
    private fun noteArticle(e: FeedEntry) {
        binding.articleWeb.evaluateJavascript(
            "(function(){var s=window.getSelection&&window.getSelection();return s?s.toString():'';})()"
        ) { raw ->
            val sel = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()?.trim().orEmpty()
            val ctx = requireContext()
            val input = android.widget.EditText(ctx).apply { hint = "Note" }
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dpPx(20), dpPx(8), dpPx(20), 0); addView(input)
            }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(if (sel.isNotBlank()) "Highlight + note" else "Note")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val n = input.text.toString().trim()
                    if (n.isNotBlank() || sel.isNotBlank()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            logArticleEvent(e, excerpt = sel.ifBlank { null }, note = n.ifBlank { null })
                        }
                        showMessage("Saved to Ledger Log", binding.root)
                    }
                }
                .setNeutralButton("❝ Pickings") { _, _ ->
                    val cardText = listOf(sel.ifBlank { e.blurb }, "— ${e.feedTitle}").filter { it.isNotBlank() }.joinToString("\n\n")
                    articleGramToPickings(cardText)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun logArticleEvent(e: FeedEntry, excerpt: String? = null, note: String? = null) {
        runCatching {
            val root = documentsRoot()
            val today = java.time.LocalDate.now()
            val day = calendarDayService.load(root, today, null, java.util.Locale.getDefault())
            day.readingEvents.add(
                com.toolsboox.plugin.calendar.da.v2.ReadingEvent(
                    id = "art-${e.id}-${java.util.UUID.randomUUID().toString().take(8)}",
                    kind = com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.ARTICLE,
                    date = java.util.Date(), title = e.title,
                    source = e.feedTitle.ifBlank { null }, url = e.url.ifBlank { null },
                    excerpt = excerpt, note = note
                )
            )
            calendarDayService.save(root, today, day)
        }
    }

    /** Read the open article aloud via the process-wide player (keeps playing after you leave),
     *  and open the transport modal. If something is already playing, just open the modal. */
    private fun toggleArticleTts() {
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        if (!player.isActive) {
            val e = currentArticle ?: return
            // Prefer the reader-view (readability) full text over the raw RSS content, which is often
            // just the title/byline blurb — that's why TTS "couldn't get past" them. Collapse runaway
            // whitespace so the engine doesn't stall between chunks.
            val parsed = com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(requireContext(), e.id)
            val body = if (!parsed.isNullOrBlank()) parsed else e.content
            val plain = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("\\s+"), " ").trim()
            player.start(
                requireContext(), e.title, e.feedTitle.ifBlank { null }, e.imageUrl,
                listOf(e.title, plain).filter { it.isNotBlank() }.joinToString(". ")
            )
        }
        player.showModal(requireContext())
    }

    private fun buildArticleHtml(e: FeedEntry, content: String = e.content): String {
        val meta = listOf(e.feedTitle, e.author ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 24px 28px; color: #000; background: #fff;
                     font-family: serif; font-size: 18px; line-height: 1.6; }
              h1 { font-size: 24px; line-height: 1.25; }
              .meta { color: #666; font-size: 13px; margin-bottom: 16px; }
              img { max-width: 100%; height: auto; }
              iframe { max-width: 100%; border: 0; }
              @supports (aspect-ratio: 16 / 9) { iframe { width: 100%; height: auto; aspect-ratio: 16 / 9; } }
              a { color: #000; } pre, code { white-space: pre-wrap; }
            </style></head><body>
            <h1>${esc(e.title)}</h1>
            <div class="meta">${esc(meta)}</div>
            $content
            </body></html>
        """.trimIndent()
    }

    /** Stepping/selecting shows all feeds from that window; today's day = the live view.
     *  Past windows are mostly read, so pull the read timeline for filtering. */
    private fun onDateChanged() {
        kindFilter = null
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        mode = if (liveToday) "feed" else "read"
        refresh()
    }

    private fun stepByGranularity(date: java.time.LocalDate, dir: Int): java.time.LocalDate = when (navGranularity) {
        "week" -> date.plusWeeks(dir.toLong())
        "month" -> date.plusMonths(dir.toLong())
        "quarter" -> date.plusMonths(3L * dir)
        "year" -> date.plusYears(dir.toLong())
        else -> date.plusDays(dir.toLong())
    }

    /** [start, end) of the current window (granularity + anchor). */
    private fun navWindow(): Pair<java.time.LocalDate, java.time.LocalDate> {
        val a = navAnchor
        return when (navGranularity) {
            "week" -> {
                val s = a.with(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).dayOfWeek(), 1)
                s to s.plusWeeks(1)
            }
            "month" -> { val s = a.withDayOfMonth(1); s to s.plusMonths(1) }
            "quarter" -> {
                val s = a.withDayOfMonth(1).withMonth((a.monthValue - 1) / 3 * 3 + 1); s to s.plusMonths(3)
            }
            "year" -> { val s = a.withDayOfYear(1); s to s.plusYears(1) }
            else -> a to a.plusDays(1)
        }
    }

    /** Keep only articles published inside the navigator window (live view when it's today's day). */
    private fun filterByNavDay(entries: List<FeedEntry>): List<FeedEntry> {
        if (navGranularity == "day" && navAnchor == java.time.LocalDate.now()) return entries
        val (start, end) = navWindow()
        return entries.filter { val d = publishedDate(it); d != null && !d.isBefore(start) && d.isBefore(end) }
    }

    private fun publishedDate(e: FeedEntry): java.time.LocalDate? = runCatching {
        java.time.OffsetDateTime.parse(e.publishedAt)
            .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()

    /** Redraw the Almanac navigator strip for the current anchor date. */
    private fun renderNav() {
        lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, navAnchor, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        navAnchor.year, navAnchor.monthValue, navAnchor.dayOfMonth, startHour = null)
                val p = runCatching { calendarPatternService.load(root, navAnchor, loc) }.getOrNull()
                cd to p
            }
            pat?.let { navBar?.render(day, it) }
        }
    }

    /** Page up (-1) / down (+1): the in-pane article when open, else the article list. */
    private fun pageBy(dir: Int) {
        if (binding.articlePane.visibility == View.VISIBLE) {
            binding.articleWeb.scrollBy(0, dir * binding.articleWeb.height * 9 / 10)
        } else {
            binding.feedsRecycler.scrollBy(0, dir * binding.feedsRecycler.height * 9 / 10)
        }
    }

    /** Pop the left feed-directory drawer in/out (persisted, no animation). */
    private fun applyDirectoryDrawer(open: Boolean) {
        setDirectoryDrawerVisible(open)
        prefs().edit().putBoolean(KEY_DIR_OPEN, open).apply()
    }

    /** Show/hide the drawer views WITHOUT touching the persisted preference — used to give the
     *  article reader full width while it's open, then restore the drawer to its saved state. */
    private fun setDirectoryDrawerVisible(open: Boolean) {
        val vis = if (open) View.VISIBLE else View.GONE
        binding.directoryScroll.visibility = vis
        binding.directoryDivider.visibility = vis
    }

    /** Set the current view/kind and reload (used by the dropdown rows). */
    private fun switchTo(newMode: String, kind: String?) {
        mode = newMode; kindFilter = kind; refresh()
    }

    private fun switchToSmart(feed: com.toolsboox.plugin.feeds.nw.SmartFeed) {
        mode = "smart"; smartFeed = feed; kindFilter = null; refresh()
    }

    /** Smart feed: server-wide search when online, filter the last-loaded entries when offline. */
    private fun loadSmart() {
        val feed = smartFeed ?: return
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) { showEmpty(getString(R.string.feeds_need_creds)); return }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { miniflux.search(url, token, feed.query) }
            loading = false
            binding.progress.visibility = View.INVISIBLE
            when (result) {
                is MinifluxClient.Result.Ok -> {
                    // (Miniflux search spans all statuses; this list has no per-entry read flag.)
                    val shown = result.value
                    allEntries = shown
                    adapter.submit(shown)
                    if (shown.isEmpty()) showEmpty(getString(R.string.feeds_empty))
                    else binding.emptyText.visibility = View.GONE
                }
                is MinifluxClient.Result.Err -> {
                    // Offline: filter whatever's already loaded by the saved query.
                    val q = feed.query.lowercase()
                    val cached = allEntries.filter {
                        it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q)
                    }
                    if (cached.isNotEmpty()) adapter.submit(cached)
                    else showEmpty("⚠️ " + result.message)
                }
            }
        }
    }

    /** Prompt for a new smart feed (name + search terms) and save it. */
    private fun promptAddSmartFeed() {
        val ctx = requireContext()
        val name = android.widget.EditText(ctx).apply { hint = "Name (e.g. Ashtanga)" }
        val query = android.widget.EditText(ctx).apply { hint = "Search terms" }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(8), dpPx(20), 0)
            addView(name); addView(query)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("New smart feed")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val q = query.text.toString().trim()
                if (q.isNotBlank()) {
                    val n = name.text.toString().trim().ifBlank { q }
                    com.toolsboox.plugin.feeds.nw.SmartFeedStore.add(
                        ctx, com.toolsboox.plugin.feeds.nw.SmartFeed(java.util.UUID.randomUUID().toString(), n, q))
                    showFeedDirectory()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Tapping a category drills one level down: a sub-menu of the specific feeds inside it
     *  (plus "All"). Tapping a feed filters to just that feed. */
    private fun showCategory(label: String) {
        val inCat = allEntries.filter { it.categoryLabel == label }
        val feeds = inCat.map { it.feedTitle }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        if (feeds.size <= 1) { adapter.submit(inCat); return }
        val rows = listOf<Pair<String, () -> Unit>>("📰  All — $label" to { adapter.submit(inCat) }) +
            feeds.map { f -> ("📰  $f" to { adapter.submit(inCat.filter { it.feedTitle == f }) }) }
        showDirectory(listOf(label to rows))
    }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Feed-specific settings (shown from the wrench) — as a dialog so it works while reading. */
    /** The wrench modal: mark-all-read / refresh, rotate the screen, flip the pill's orientation. */
    private fun showFeedControls() {
        val vertical = prefs().getBoolean("feeds_pill_vertical", false)
        val mode = markReadMode()
        fun tick(m: String) = if (mode == m) "◉" else "○"
        showDirectory(listOf(
            "Feed" to listOf(
                "✓  Mark all read" to { markAllRead() },
                "🔄  Refresh" to { refresh() }
            ),
            "Mark as read" to listOf(
                "${tick("open")}  On open" to { prefs().edit().putString("feeds_mark_read", "open").apply() },
                "${tick("scroll")}  On scroll past" to { prefs().edit().putString("feeds_mark_read", "scroll").apply() },
                "${tick("off")}  Off (manual only)" to { prefs().edit().putString("feeds_mark_read", "off").apply() }
            ),
            "Reading" to listOf(
                ((if (prefs().getBoolean("feeds_tap_zones", true)) "☑" else "☐") + "  Tap sides to turn") to {
                    val on = prefs().getBoolean("feeds_tap_zones", true)
                    prefs().edit().putBoolean("feeds_tap_zones", !on).apply()
                    binding.articleTapZones.visibility = if (!on) View.VISIBLE else View.GONE
                }
            ),
            "Screen" to listOf(
                "🔄  Rotate screen" to { cycleScreenOrientation() }
            ),
            "Layout" to listOf(
                ("🔀  Pill layout · " + (if (vertical) "horizontal" else "vertical")) to {
                    prefs().edit().putBoolean("feeds_pill_vertical", !vertical).apply(); applyFeedsPillOrientation()
                }
            ),
            "Settings" to listOf(
                "⚙  Feed settings…" to { showFeedSettings() }
            )
        ))
    }

    /** Mark every entry currently in view as read on Miniflux, then refresh so they drop out. */
    private fun markAllRead() {
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty(); val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) { showMessage(R.string.feeds_need_creds); return }
        val ids = allEntries.map { it.id }
        if (ids.isEmpty()) { showMessage(R.string.feeds_nothing_to_mark); return }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { miniflux.setStatus(url, token, ids, "read") }
            refresh()
            // Undoable — one Snackbar action flips them all back to unread.
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, "Cleared ${ids.size} — marked read", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction("Undo") {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { miniflux.setStatus(url, token, ids, "unread") }
                    refresh()
                }
            }.show()
        }
    }

    /** How opening/scrolling marks entries read: "open" (default) · "scroll" · "off". */
    private fun markReadMode(): String = prefs().getString("feeds_mark_read", "open") ?: "open"

    /** Mark one entry read: locally (greys the row) + on Miniflux (durable). Idempotent. */
    private fun markEntryRead(entry: FeedEntry, notify: Boolean = true) {
        if (FeedReadState.isRead(entry.id)) return
        FeedReadState.mark(entry.id)
        if (notify) adapter.notifyDataSetChanged()
        val p = prefs(); val u = p.getString(KEY_URL, "").orEmpty(); val tk = p.getString(KEY_TOKEN, "").orEmpty()
        if (u.isNotBlank() && tk.isNotBlank() && !com.toolsboox.plugin.feeds.nw.LocalFeedStore.isLocal(entry.id))
            lifecycleScope.launch(Dispatchers.IO) { runCatching { miniflux.markRead(u, tk, entry.id) } }
    }

    /** Apply the persisted pill orientation to the feeds pill (vertical on narrow screens by choice). */
    private fun applyFeedsPillOrientation() {
        binding.feedsPill.orientation =
            if (prefs().getBoolean("feeds_pill_vertical", false)) android.widget.LinearLayout.VERTICAL
            else android.widget.LinearLayout.HORIZONTAL
    }

    private fun showFeedSettings() {
        val ctx = requireContext()
        val p = prefs()
        fun label(t: String) = android.widget.TextView(ctx).apply {
            text = t; textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, dpPx(10), 0, dpPx(2))
        }
        val urlIn = android.widget.EditText(ctx).apply {
            setText(p.getString(KEY_URL, "")); hint = getString(R.string.feeds_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val tokIn = android.widget.EditText(ctx).apply {
            setText(p.getString(KEY_TOKEN, "")); hint = getString(R.string.feeds_token_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val readerDefault = android.widget.CheckBox(ctx).apply { text = "Reader view by default"; isChecked = showParsed }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(4), dpPx(20), 0)
            addView(label(getString(R.string.feeds_url_hint))); addView(urlIn)
            addView(label(getString(R.string.feeds_token_hint))); addView(tokIn)
            addView(readerDefault)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Feed settings")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                p.edit().putString(KEY_URL, urlIn.text.toString().trim())
                    .putString(KEY_TOKEN, tokIn.text.toString().trim()).apply()
                showParsed = readerDefault.isChecked
                if (currentArticle != null) renderArticle()
                showMessage(R.string.feeds_saved); refresh()
            }
            .setNeutralButton("Refresh") { _, _ -> refresh() }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Populate the persistent left directory pane (two-pane feed): a read/unread toggle, All,
     * Stars, the Read/Watch/Listen lenses with their categories, and the Later List — each with
     * its unread count. Selecting a row filters the right list. Replaces the floating dropdown.
     */
    private fun renderDirectory() {
        if (!::binding.isInitialized) return
        val container = binding.feedsDirectory
        container.removeAllViews()
        // SLIM per-feed pane: just the INDIVIDUAL FEEDS of the current lens, small type, unread
        // counts. The full tree (lenses, categories, Later, local, OPML…) lives in the accordion
        // drawer (☰) — one directory style; this pane is a quick filter, toggled by the RSS button.
        val ctx = requireContext()
        fun row(label: String, bold: Boolean, selected: Boolean, onClick: () -> Unit) {
            val tv = android.widget.TextView(ctx).apply {
                text = label
                textSize = if (bold) 15f else 13.5f
                setTextColor(0xFF000000.toInt())
                setPadding(dpPx(10), dpPx(9), dpPx(8), dpPx(9))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                if (selected) setBackgroundColor(0x22000000)
                setOnClickListener {
                    // Selecting a feed while reading returns to the list.
                    if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
                    onClick()
                }
            }
            container.addView(tv)
        }
        val pool = applyKind(allEntries)
        // Ladder up: the slim pane's back row opens the full feeds directory.
        row("‹  Directory", true, false) { showFeedDirectory() }
        row("📰  All", true, false) { adapter.submit(filterByNavDay(pool)) }
        pool.map { it.feedTitle }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }.forEach { f ->
            val unread = pool.count { it.feedTitle == f && !it.read }
            row(if (unread > 0) "$f · $unread" else f, false, false) {
                adapter.submit(filterByNavDay(pool.filter { it.feedTitle == f }))
            }
        }
    }

    /**
     * The Feed Ledger (RSS) hamburger — feeds only: All and Stars as standalone rows at the
     * top, then Read / Watch / Listen folders (listing their 📖/📺/🎧 categories) and the
     * Later List. The broader Ledger (almanac/history/personal) lives on its own button.
     */
    private fun showFeedDirectory() {
        fun categoriesOf(k: String) =
            allEntries.filter { it.kind == k }.mapNotNull { it.categoryLabel }.distinct().sortedBy { it.lowercase() }
        // Each lens drills two levels deep inline: category → its individual feeds (indented).
        // FOCUSED opening: only the folder matching where you came from (current mode/kind)
        // starts expanded — the rest sit collapsed instead of sprawling.
        val focusLens = if (mode == "feed") kindFilter else null
        fun lens(emoji: String, label: String, k: String) = Folder(emoji, label,
            listOf<Pair<String, () -> Unit>>("$emoji  All $label" to { switchTo("feed", k) }) +
            categoriesOf(k).flatMap { c ->
                val inCat = allEntries.filter { it.categoryLabel == c }
                val feeds = inCat.map { it.feedTitle }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
                listOf<Pair<String, () -> Unit>>("🗂  $c" to { adapter.submit(inCat) }) +
                    if (feeds.size > 1) feeds.map { f -> ("      · $f" to { adapter.submit(inCat.filter { it.feedTitle == f }) }) }
                    else emptyList()
            },
            expanded = focusLens == k
        )
        showAccordion(
            listOf(
                // Back rung of the ladder: today-page menu, Feed Ledger expanded.
                Folder("‹", "Today menu", action = {
                    FeedSelection.openTodayHubOnArrival = true
                    androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_calendar_day)
                }),
                // Order per Michael: Later · The Read · The Watch · The Listen · Smart Feed · Ask · Search.
                Folder("🔖", "Later", listOf(
                    "🔖  All" to { switchTo("later", null) },
                    "📖  The Read" to { switchTo("later", "read") },
                    "📺  The Watch" to { switchTo("later", "watch") },
                    "🎧  The Listen" to { switchTo("later", "listen") }
                ), expanded = mode == "later"),
                lens("📖", "The Read", "read"),
                lens("📺", "The Watch", "watch"),
                lens("🎧", "The Listen", "listen"),
                Folder("#", "Smart Feed",
                    com.toolsboox.plugin.feeds.nw.SmartFeedStore.all(requireContext()).map { sf ->
                        ("#  ${sf.name}" to { switchToSmart(sf) })
                    } + ("➕  Add smart feed…" to { promptAddSmartFeed() }),
                    expanded = false),
                Folder("💬", "Ask", action = { switchTo("asklog", null) }),
                Folder("🔬", "Search", action = { showFeedSearch() }),
                // Kept available below the requested set.
                Folder("📰", "All", action = { switchTo("feed", null) }),
                Folder("⭐", "Stars", action = { switchTo("stars", null) }),
                Folder("❝", "Feed Pickings", action = { switchTo("pickings", null) })
            )
        )
    }

    /**
     * The broader Ledger directory (second header button): Almanac, History, Later, and
     * Personal, plus the surfaces to jump to — so you can leave the feed for the rest of the
     * Ledger without going through the day page.
     */
    private fun showLedgerDirectory() = showAccordion(ledgerDirectoryFolders(this))

    private fun toggleStar(entry: FeedEntry) {
        if (com.toolsboox.plugin.feeds.nw.LocalFeedStore.isLocal(entry.id)) {
            com.toolsboox.plugin.feeds.nw.LocalFeedStore.setStar(requireContext(), entry.url, !entry.starred)
            // Newly starred → log to today's Ledger too, exactly like a Miniflux star, so local-feed
            // stars aren't silently missing from the Notes / Ledger Log.
            if (!entry.starred) lifecycleScope.launch(Dispatchers.IO) { logStar(entry) }
            showMessage(if (entry.starred) R.string.feeds_unstarred else R.string.feeds_starred)
            refresh()
            return
        }
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val wasStarred = entry.starred
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                miniflux.toggleStar(url, token, entry.id)
                // Newly starred → log to today's Ledger so it joins the corpus + sync.
                if (!wasStarred) logStar(entry)
            }
            showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
            refresh()
        }
    }

    /** Append a starred article to today's CalendarDay as a ReadingEvent(article). */
    private fun logStar(entry: FeedEntry) {
        try {
            val root = documentsRoot()
            val today = LocalDate.now()
            val day = calendarDayService.load(root, today, null, Locale.getDefault())
            day.readingEvents.add(
                ReadingEvent(
                    id = "art-${entry.id}",
                    kind = ReadingEvent.Kind.ARTICLE,
                    date = Date(),
                    title = entry.title,
                    source = entry.feedTitle.ifBlank { null },
                    url = entry.url.ifBlank { null },
                    note = entry.blurb.ifBlank { null },
                    starred = true
                )
            )
            calendarDayService.save(root, today, day)
        } catch (e: Exception) {
            Timber.w(e, "failed to log starred article")
        }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    private fun prefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            requireContext(), PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun showLoading() {}
    override fun hideLoading() {}

    companion object {
        const val PREFS = "ledger_feeds_prefs"
        const val KEY_URL = "miniflux_url"
        const val KEY_TOKEN = "miniflux_token"
        const val KEY_DIR_OPEN = "feeds_dir_open"
    }
}

/** Hand the tapped entry to the article fragment without stuffing it through nav args. */
object FeedSelection {
    var entry: FeedEntry? = null

    /** Set by the day-page directory: open the feeds directory drawer as soon as entries load. */
    var openDirectoryOnArrival = false

    /** Back-ladder: land on the day page with the today menu open, Feed Ledger expanded. */
    var openTodayHubOnArrival = false

    /** An article to open directly in the feed's in-pane reader on next open (e.g.
     *  from a gram's "Go to source"). Consumed once by FeedsFragment. */
    var pendingInPaneEntry: FeedEntry? = null

    /** The list the article was opened from, so the reader can page prev/next. */
    var list: List<FeedEntry> = emptyList()

    /** A feed title the Feed Ledger should filter to on next open (set from the day-page hub). */
    var filterFeedTitle: String? = null

    /** Which view to open: "feed" (RSS unread), "stars", or "later" (read-later intake). */
    var mode: String? = null
    /** Optional read/watch/listen lens to filter to. */
    var kind: String? = null

    /** Set by the article's ☰ so the feed list pops the RSS directory open on return. */
    var openDirectory: Boolean = false

    /** Consume the pending mode/kind (one-shot) after the fragment applies it. */
    fun consume(): Pair<String, String?> {
        val m = mode ?: "feed"; val k = kind
        mode = null; kind = null
        return m to k
    }
}
