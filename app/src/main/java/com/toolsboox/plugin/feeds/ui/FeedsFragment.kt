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
        // Volume keys move the page here too. The book reader and the full-screen article both
        // had this; the list you actually spend the time in did not, so putting the device down
        // and picking it up meant reaching for the screen again. Whichever is in front gets the
        // keys — the in-pane article when it's open, otherwise the list itself.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = handler@{ up ->
            if (!volumeTurnOn()) return@handler false
            if (binding.articlePane.visibility == View.VISIBLE) {
                val step = (binding.articleWeb.height * 9 / 10).coerceAtLeast(1)
                binding.articleWeb.scrollBy(0, if (up) -step else step)
            } else {
                val step = (binding.feedsRecycler.height * 9 / 10).coerceAtLeast(1)
                // E-ink: the list must JUMP a page, never smooth-scroll (which ghosts/smears).
                binding.feedsRecycler.scrollBy(0, if (up) -step else step)
            }
            true
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null
    }

    /** Shared with the full-screen article and the book reader, so one setting covers reading. */
    private fun volumeTurnOn() =
        requireContext().getSharedPreferences("ledger_reader_nav", 0).getBoolean("volume_turn", true)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedsBinding.bind(view)

        adapter = FeedEntryAdapter(emptyList(), onOpen = ::openEntry, onStar = ::toggleStar,
                                   onLongPress = ::markAboveAsRead)
        // Text-size tier lives in the shared a11y prefs (set from the wrench). Migrate the
        // short-lived boolean toggle if it was flipped on.
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        if (prefs().getBoolean("feeds_large_rows", false) && a11y.getString("feed_text_size", null) == null) {
            a11y.edit().putString("feed_text_size", "large").apply()
        }
        adapter.textTier = a11y.getString("feed_text_size", "medium") ?: "medium"
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
        // ▦ hub, same door the day page's top-left button opens.
        binding.feedsHubButton.setOnClickListener { showLedgerDirectory() }
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
            // "Home" means today AND unfiltered — a slim-pane feed filter counts as being away,
            // so the first tap clears it back to the live list; only a second tap opens the menu.
            if (slimFeedFilter == null && navGranularity == "day" && navAnchor == java.time.LocalDate.now()) {
                showLedgerDirectory()
            } else {
                slimFeedFilter = null
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
        binding.feedsDwNext.setOnClickListener { openAdjacentArticle(1) }

        binding.feedsPill.bringToFront()
        // Grip tap: while reading, shrink the pill to a slim drawer (↑ ↓ · next · back); tapping
        // again drops the full set back out.
        cyclePillOnTap(
            binding.feedsGrip, binding.feedsPill, "feeds_pill", "feeds_pill_vertical",
            alsoOnTap = {
                // While an article is open, the grip CYCLES the reading modal: full-horizontal →
                // full-vertical → slim drawer → back. This gives the vertical flip by the handle (like
                // the other pills) without losing the slim shrunk drawer.
                if (currentArticle != null) {
                    val vertical = prefs().getBoolean("feeds_pill_vertical", false)
                    when {
                        !pillShrunk && !vertical -> {
                            prefs().edit().putBoolean("feeds_pill_vertical", true).apply()
                            applyFeedsPillOrientation()
                        }
                        !pillShrunk && vertical -> { pillShrunk = true; applyArticlePill() }
                        else -> {
                            pillShrunk = false
                            prefs().edit().putBoolean("feeds_pill_vertical", false).apply()
                            applyFeedsPillOrientation(); applyArticlePill()
                        }
                    }
                    true
                } else false
            }
        )

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

    /** Slim-pane per-feed filter (null = unfiltered) — counts as "away from home" for the goto. */
    private var slimFeedFilter: String? = null
    /** Optional read/watch/listen lens. */
    private var kindFilter: String? = null
    /** Sidebar search scope: "all" · "feed" (the selected feed) · "category" (the current lens). */
    private var searchScope = "all"
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
            hint = "Feed URL — or any YouTube channel/video link"; setSingleLine(); setText("https://")
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Add feed")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.length > 8) lifecycleScope.launch { addFeedByUrl(url) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One paste box, two doors: a YouTube link resolves to the channel's real RSS feed and
     *  subscribes properly (Miniflux when configured, under The Watch); anything else is a
     *  plain local-feed add, exactly as before. */
    private suspend fun addFeedByUrl(url: String) {
        val yt = com.toolsboox.plugin.feeds.nw.YouTubeResolver
        if (yt.isYouTube(url) && !yt.isYouTubeFeed(url)) { addYouTubeFeed(url); return }
        val sub = withContext(Dispatchers.IO) {
            com.toolsboox.plugin.feeds.nw.LocalFeedStore.add(requireContext(), url)
        }
        if (sub == null) showMessage("Not a valid feed", binding.root) else switchToLocal(sub)
    }

    /**
     * Paste a YouTube link — a video, a @handle, a /channel/ page — and it becomes a channel
     * subscription: dig the channel id out of the page, build the channel's RSS URL, and
     * subscribe. With Miniflux configured the feed is created SERVER-SIDE and landed in the
     * 📺 category so its entries wear The Watch lens on every device; with no server it falls
     * back to a local sub, same as any other feed URL.
     */
    private suspend fun addYouTubeFeed(url: String) {
        binding.progress.visibility = View.VISIBLE
        val channel = withContext(Dispatchers.IO) {
            com.toolsboox.plugin.feeds.nw.YouTubeResolver.resolve(url)
        }
        binding.progress.visibility = View.INVISIBLE
        if (channel == null) {
            showMessage("Couldn't find a channel in that YouTube link", binding.root)
            return
        }
        val p = prefs()
        val base = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (base.isBlank() || token.isBlank()) {
            // No server — the channel still arrives, as a local feed.
            val sub = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.feeds.nw.LocalFeedStore.add(requireContext(), channel.rssUrl)
            }
            if (sub == null) showMessage("Couldn't load the channel feed", binding.root)
            else { showMessage("Subscribed to ${channel.title}", binding.root); switchToLocal(sub) }
            return
        }
        val result = withContext(Dispatchers.IO) {
            // Land it under the 📺 lens: the first existing 📺-prefixed category (the shared
            // emoji grammar that makes kind == "watch"), or a fresh "📺 The Watch" when none.
            val watchCat = when (val cats = miniflux.categories(base, token)) {
                is MinifluxClient.Result.Ok ->
                    cats.value.firstOrNull { it.second.trim().startsWith("📺") }?.first
                        ?: (miniflux.createCategory(base, token, "📺 The Watch") as? MinifluxClient.Result.Ok)?.value
                is MinifluxClient.Result.Err -> null
            }
            miniflux.createFeed(base, token, channel.rssUrl, watchCat)
        }
        when (result) {
            is MinifluxClient.Result.Ok -> {
                showMessage("Subscribed to ${channel.title} · The Watch", binding.root)
                refresh()
            }
            is MinifluxClient.Result.Err ->
                showMessage("Couldn't subscribe — ${result.message}", binding.root)
        }
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
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
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
                    "both" -> miniflux.fetchEverything(url, token)
                    "edition" -> {
                        val (s, e) = navWindow()
                        val zone = java.time.ZoneId.systemDefault()
                        miniflux.fetchPublishedWindow(
                            url, token, null,
                            s.atStartOfDay(zone).toEpochSecond(), e.atStartOfDay(zone).toEpochSecond(),
                            limit = 300
                        )
                    }
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
                    // Auto-keep the latest audio episodes offline (parity with the iPad's keep-latest;
                    // podcast-app behaviour, no star). Newest-first; the count cap trims the rest.
                    com.toolsboox.plugin.feeds.nw.LaterMedia.keepRecent(
                        requireContext(), result.value.mapNotNull { it.audioUrl })
                    // Opportunistic janitor — parsed HTML accrued forever (the .versions lesson).
                    val appCtx = requireContext().applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        com.toolsboox.plugin.feeds.nw.FeedCache.prune(appCtx)
                        com.toolsboox.plugin.feeds.nw.LaterMedia.prune(appCtx)
                    }
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

    private fun cacheKey(): String =
        if (mode == "edition") "edition_${navGranularity}_${navAnchor}_${kindFilter ?: "all"}"
        else "${mode}_${kindFilter ?: "all"}"

    /** Best-effort: fetch + cache the parsed (readability) HTML for each entry so the
     *  in-pane reader has an offline-readable version. Runs in the background. The 400 window
     *  matches iOS (prefetch 400 / cap 800 / 60-day age) so both devices carry the same
     *  offline depth. */
    private fun prefetchParsed(url: String, token: String, entries: List<FeedEntry>) {
        // The app context, captured once: a 400-entry sweep easily outlives this view, and
        // requireContext() mid-loop on a detached fragment is a crash, not a cache miss.
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            var bypassBudget = 30   // pre-unlock at most N paywall stubs per sweep (mirrors iOS)
            for (e in entries.take(400)) {
                if (com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(appCtx, e.id) != null) continue
                val res = runCatching { miniflux.fetchContent(url, token, e.id) }.getOrNull()
                var content = if (res is MinifluxClient.Result.Ok) res.value else ""
                // Pre-unlock a paywalled stub through the bypass ahead of time, so the reader has the
                // full article offline. Bounded + gentle so a big sweep doesn't hammer archive.today.
                if (bypassBudget > 0 && e.url.isNotBlank() &&
                    com.toolsboox.plugin.feeds.nw.PaywallBypass.looksTruncated(content.ifBlank { null })) {
                    bypassBudget--
                    com.toolsboox.plugin.feeds.nw.PaywallBypass.readable(e.url)?.let { content = it }
                    runCatching { Thread.sleep(400) }
                }
                if (content.isNotBlank()) {
                    com.toolsboox.plugin.feeds.nw.FeedCache.saveContent(appCtx, e.id, content)
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
            // Render what's on THIS device immediately — never block the list on network pulls
            // (14 sequential cross-device fetches left the Later list blank/spinning "not loading").
            val local = withContext(Dispatchers.IO) { gatherLaterList() }
            binding.progress.visibility = View.INVISIBLE
            allEntries = local
            // The Later list is the WHOLE backlog ("a finite feed to clear"), NOT filtered by
            // the timeline nav — nav-filtering it hid every item saved on a day other than the
            // one the navigator happened to sit on.
            val shown = applyKind(local)
            adapter.submit(shown)
            if (shown.isEmpty()) showEmpty(getString(R.string.feeds_later_empty))
            else binding.emptyText.visibility = View.GONE

            // Then pull other devices' intake in the background and refresh if new links arrived.
            withContext(Dispatchers.IO) {
                val ctx = requireContext().applicationContext
                for (d in 0L..14L) runCatching {
                    com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.pullLatest(ctx, java.time.LocalDate.now().minusDays(d))
                }
            }
            if (mode == "later" && isAdded) {
                val merged = withContext(Dispatchers.IO) { gatherLaterList() }
                // Content comparison, not a size check — a same-count swap (one added, one
                // deduped) used to leave today's additions invisible until re-entry.
                val changed = merged.map { it.url + "·" + it.title }.toSet() !=
                    local.map { it.url + "·" + it.title }.toSet()
                if (changed) {
                    allEntries = merged
                    val m = applyKind(merged)
                    adapter.submit(m)
                    if (m.isEmpty()) showEmpty(getString(R.string.feeds_later_empty))
                    else binding.emptyText.visibility = View.GONE
                }
            }
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
        // Synthetic rows must NEVER collide with real Miniflux entry ids (small positive ints)
        // or LocalFeedStore ids (-1000 .. -1e9): pickings boards live in their own negative band.
        var id = PICKINGS_ID_BASE
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
                    id = id--, title = b.name, feedTitle = "Pickings · $date",
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
        // Own negative band — see PICKINGS_ID_BASE: a synthetic id must never reach Miniflux.
        var idSeed = LATER_ID_BASE
        for (d in 0L..120L) {
            val date = java.time.LocalDate.now().minusDays(d)
            val data = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.load(ctx, date)
            for (kind in kinds) {
                // Reversed: lines are appended oldest-first within a day, so walk them backwards to
                // surface the NEWEST-added link first instead of burying it under earlier saves.
                data.typedFor(kind).lines().reversed().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(line).firstOrNull() ?: line
                    // Presentation metadata saved with the link (entry blurb + image, or the og:
                    // fetch) — what makes the row informative instead of a bare URL.
                    val meta = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.linkMeta(data, url)
                    // A link saved without a title reads as its host ("nytimes.com"), not the
                    // raw URL; stripping the URL also drops the " — " separator leftovers.
                    val title = meta?.get("title")?.takeIf { it.isNotBlank() }
                        ?: line.replace(url, "").trim().trim('—', '-', ' ')
                            .ifBlank { runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url }
                    // Same link re-filed on another day shows once (newest day wins — we walk newest-first).
                    if (out.any { it.url == url && it.title == title }) return@forEach
                    // The offline parsed copy saved at file time IS the entry content — the
                    // article opens in the reader like any other feed entry, no connection
                    // needed. Older items without a copy get one fetched now (background).
                    val cached = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.cachedArticle(ctx, url)
                    if (cached == null) com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.cacheArticle(ctx, url)
                    // No offline copy (yet)? The saved excerpt stands in, so the row still says
                    // what the thing is. The image rides the enclosure slot: FeedEntry.imageUrl
                    // prefers an inline <img> from the article, then falls back to it.
                    val excerpt = meta?.get("excerpt")?.takeIf { it.isNotBlank() }
                    val content = cached ?: excerpt?.let { "<p>$it</p>" }.orEmpty()
                    // Reuse the RSS kind field via category so applyKind() sees read/watch/listen.
                    out += FeedEntry(
                        id = idSeed--, title = title, feedTitle = "Later · $kind",
                        url = url, author = null, content = content, publishedAt = date.toString(),
                        starred = false, category = if (kind == "educate") "read" else kind,
                        enclosureImage = meta?.get("image")?.takeIf { it.startsWith("http") }
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
        // Links get a menu, tap or HOLD — same as the standalone Articles reader. Without this
        // the in-pane reader either navigated away inside the pane or ignored the hold entirely
        // ("I can't hold on a link and bring up a menu").
        binding.articleWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                // Sub-frame loads pass through untouched: the watch skin swaps a YouTube iframe
                // in at tap time, and intercepting ITS navigation with the link menu would
                // cancel the very embed the tap asked for. The menu is for main-frame link taps.
                if (request?.isForMainFrame == false) return false
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith("http")) { showPaneLinkMenu(url); return true }
                return false
            }
        }
        binding.articleWeb.setOnLongClickListener {
            val hit = binding.articleWeb.hitTestResult
            val url = hit.extra
            when {
                url.isNullOrBlank() || !url.startsWith("http") -> false
                // A bare picture held: it becomes a photo gram for this article ("a cute photo gram").
                hit.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                    val a = currentArticle
                    com.toolsboox.plugin.feeds.ot.FeedNoteGram.showImageMenu(
                        this, calendarDayService, documentsRoot(), url,
                        a?.title.orEmpty(), a?.feedTitle.orEmpty(), a?.url.orEmpty())
                    true
                }
                // An image that is ALSO a link keeps its link menu, with the photo-gram option added.
                hit.type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> { showPaneLinkMenu(url); true }
                hit.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    showPaneLinkMenu(url, imageUrl = url); true
                }
                else -> false
            }
        }
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
        // A YouTube entry under The Watch gets the skinned episode page — thumbnail with a
        // tap-to-play embed, tappable chapter rows, the description cleaned of timestamp
        // cruft — instead of whatever iframe soup the feed shipped. (Non-YouTube watch
        // entries fall through to the plain article path unchanged.)
        val ytId = if (entry.kind == "watch") entry.youTubeId else null
        if (ytId != null) {
            val base = entry.url.takeIf { it.startsWith("http", ignoreCase = true) } ?: "https://www.youtube.com"
            binding.articleWeb.loadDataWithBaseURL(base, buildYouTubeHtml(entry, ytId), "text/html", "UTF-8", null)
            kickChapterResolve(entry)
            return
        }
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
        // Main-pill controls collapse away in the slim drawer.
        binding.refreshButton.visibility = vis(!shrunk)
        binding.settingsButton.visibility = vis(!shrunk)
        // The side nav pill (↑ ☰ ↓ — the "day picker") is INDEPENDENT of the main pill's shrunk
        // state: minimizing the reader modal must NOT take the day picker with it (Michael). So its
        // three buttons stay put in every mode.
        binding.feedsGoto.visibility = View.VISIBLE
        binding.feedsPageUp.visibility = View.VISIBLE
        binding.feedsPageDown.visibility = View.VISIBLE
        // Article actions only in the full article view.
        binding.feedsStar.visibility = vis(fullArticle); binding.feedsNote.visibility = vis(fullArticle)
        // Filled star when the open article is starred (parity with the standalone reader).
        binding.feedsStar.setImageResource(
            if (currentArticle?.starred == true) R.drawable.ic_starred else R.drawable.ic_star)
        binding.feedsParsed.visibility = vis(fullArticle); binding.feedsTts.visibility = vis(fullArticle)
        binding.feedsLater.visibility = vis(fullArticle)
        // Article jump (⏫ last / ⏬ next article) lives ONLY in the slim shrunk drawer. On the full
        // modal it read as stray double-carrots that "don't go anywhere", so it stays with its drawer.
        binding.feedsDwPrev.visibility = vis(shrunk); binding.feedsDwNext.visibility = vis(shrunk)
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
        // The Podcasting 2.0 layer rides in after the transport starts: chapters (2.0 tag or
        // description timestamps) + the transcript hook resolve in the background and attach
        // to THIS session — the Now Playing card grows its chapter row the moment they land.
        com.toolsboox.plugin.feeds.nw.FeedChapters.attachToPlayer(requireContext(), e)
        // No popup on play — the inline Now Playing card seats in the drawer instead
        // (the auto-modal on top of the card read as two competing surfaces).
        renderDirectory()   // surface the "▶️ Now Playing" row in the drawer right away
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
                // The entry knows what it is — carry its title, blurb and featured image so the
                // Later row shows the thing, not a bare URL.
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.rememberLinkMeta(
                    ctx, today, link, e.title, e.blurb, e.imageUrl)
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

    /** Star the open article. `toggleStar` is the single place that logs the star to the Ledger
     *  (local + Miniflux paths), so we don't log a second time here — that used to double-log. */
    private fun starArticle(e: FeedEntry) {
        toggleStar(e)
    }

    /** Read the current selection (if any), then note/annotate the open article. A saved note
     *  IS a gram now — [FeedNoteGram] asks for its medium and places it on today's Notes page. */
    private fun noteArticle(e: FeedEntry) {
        binding.articleWeb.evaluateJavascript(
            "(function(){var s=window.getSelection&&window.getSelection();return s?s.toString():'';})()"
        ) { raw ->
            val sel = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()?.trim().orEmpty()
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.show(
                this, calendarDayService, documentsRoot(),
                selection = sel, articleTitle = e.title, feedTitle = e.feedTitle, articleUrl = e.url,
                captureAv = { kind, sink -> captureAvGramDirect(kind, sink) },
                logEvent = { excerpt, note ->
                    lifecycleScope.launch(Dispatchers.IO) { logArticleEvent(e, excerpt = excerpt, note = note) }
                }
            )
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
        renderDirectory()   // seat the inline Now Playing card; the drawer card is the transport, no auto-popup
    }

    private fun buildArticleHtml(e: FeedEntry, content: String = e.content): String {
        val meta = listOf(e.feedTitle, e.author ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              /* 44px side gutter matches FeedArticleFragment — wide enough that the
                 vertical pill at its smallest parks in the margin without covering text. */
              body { margin: 24px 44px; color: #000; background: #fff;
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
            ${rewriteYouTubeEmbeds(content)}
            </body></html>
        """.trimIndent()
    }

    /**
     * YouTube blocks many embeds in a WebView (error 150/152 — owner disabled embedded playback),
     * leaving a dead player. Rewrite every YouTube iframe into a tappable thumbnail that opens the
     * watch page — same fix the standalone article view already uses, now in the in-pane reader too.
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
               <span style="display:block;padding:8px 0;font-weight:bold;color:#000">▶ Watch on YouTube</span>
               </a>"""
        }
    }

    /**
     * The Watch's skinned episode page for a YouTube entry. The video leads: full-width
     * thumbnail with a solid ▶ badge — tapping swaps in the real embed (autoplay, and
     * `?start=SECONDS` when a chapter row asked for a jump). Under it: title, channel · date
     * in the small mono meta voice, the CHAPTER TABLE as tappable rows (times + titles, the
     * same Chapter shape the player uses), then the description with the timestamp lines
     * removed — they became the table, no need to say them twice. Solid black rules on
     * white throughout; nothing shaded to vanish on e-ink.
     */
    private fun buildYouTubeHtml(e: FeedEntry, videoId: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val chaptersLib = com.toolsboox.plugin.feeds.nw.FeedChapters
        val chapters = chaptersLib.quick(requireContext(), e)
        val date = publishedDate(e)?.format(
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.getDefault())
        ).orEmpty()
        val meta = listOf(e.feedTitle, date).filter { it.isNotBlank() }.joinToString("  ·  ")
        // The description as clean prose: line structure kept, chapter lines lifted out
        // (they render as the table above), runs of blank lines collapsed.
        val desc = chaptersLib.htmlToLines(e.content)
            .filterNot { chapters.isNotEmpty() && chaptersLib.isStampLine(it) }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        val thumb = e.enclosureImage?.takeIf { it.startsWith("http") }
            ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        val chapterRows = chapters.joinToString("\n") { c ->
            """<div class="ch" onclick="play(${c.startSec})"><span class="t">${c.clock}</span><span>${esc(c.title)}</span></div>"""
        }
        val chapterBlock = if (chapters.isEmpty()) ""
        else """<div class="chaps">$chapterRows</div>"""
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; color: #000; background: #fff; font-family: serif; }
              .wrap { padding: 18px 22px 28px; }
              #player { position: relative; width: 100%; background: #000; }
              #player img { width: 100%; display: block; }
              #player .cue { position: absolute; top: 0; left: 0; right: 0; bottom: 0;
                             display: flex; align-items: center; justify-content: center; }
              #player .cue span { font-size: 30px; color: #000; background: #fff;
                                  border: 2px solid #000; border-radius: 999px;
                                  padding: 14px 26px; line-height: 1; }
              #player iframe { width: 100%; aspect-ratio: 16 / 9; border: 0; display: block; }
              h1 { font-size: 22px; line-height: 1.3; margin: 16px 0 4px; }
              .meta { font-family: ui-monospace, Menlo, monospace; font-size: 12.5px;
                      color: #444; margin: 0 0 14px; }
              .chaps { border: 1px solid #000; border-radius: 8px; margin: 0 0 16px; overflow: hidden; }
              .ch { display: flex; gap: 12px; padding: 11px 12px; border-top: 1px solid #000; }
              .ch:first-child { border-top: 0; }
              .ch .t { font-family: ui-monospace, Menlo, monospace; font-weight: bold;
                       min-width: 52px; text-align: right; }
              .desc { font-size: 16px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: break-word; }
            </style></head><body>
            <div id="player" onclick="if(!played)play(0)">
              <img src="$thumb"/><div class="cue"><span>▶</span></div>
            </div>
            <div class="wrap">
              <h1>${esc(e.title)}</h1>
              <div class="meta">${esc(meta)}</div>
              $chapterBlock
              <div class="desc">${esc(desc)}</div>
            </div>
            <script>
              var played = false;
              function play(s) {
                played = true;
                var p = document.getElementById('player');
                p.innerHTML = '<iframe src="https://www.youtube-nocookie.com/embed/$videoId?start=' + s +
                  '&autoplay=1&rel=0&playsinline=1" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>';
              }
            </script>
            </body></html>
        """.trimIndent()
    }

    /** One entry, one upgrade pass: the skin renders instantly from the quick (cache/description)
     *  chapters, while the full resolve — which may walk back to the feed XML for a real
     *  `podcast:chapters` document — runs behind it and re-renders ONCE if it found more. */
    private var chapterResolveKickedFor: Long? = null
    private fun kickChapterResolve(entry: FeedEntry) {
        if (chapterResolveKickedFor == entry.id) return
        chapterResolveKickedFor = entry.id
        val quick = com.toolsboox.plugin.feeds.nw.FeedChapters.quick(requireContext(), entry)
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val full = runCatching {
                com.toolsboox.plugin.feeds.nw.FeedChapters.forEntry(appCtx, entry)
            }.getOrDefault(quick)
            if (full != quick) withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isAdded && currentArticle?.id == entry.id) renderArticle()
            }
        }
    }

    /** Stepping/selecting keeps the chosen lens (The Watch stays The Watch across dates);
     *  today = the live view, past windows pull the read timeline for filtering. */
    private fun onDateChanged() {
        // Today = the live unread feed. Any other window = that day's EDITION: everything
        // published inside the window (read and unread alike), fetched server-side by
        // published date — stepping the date nav pages through finished daily papers.
        // (The old mode="read" showed only read items that happened to be in the last-100
        // fetch, so past days were incomplete or empty.)
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        mode = if (liveToday) "feed" else "edition"
        updateWindowLabel()
        refresh()
    }


    /** Link menu for the in-pane reader - mirrors the standalone Articles reader's. When the held
     *  thing was an image-anchor, [imageUrl] adds the photo-gram option alongside the link ones. */
    private fun showPaneLinkMenu(url: String, imageUrl: String? = null) {
        val items = mutableListOf("\ud83c\udf10  Open", "\ud83d\udd16  Save to Later List", "\ud83d\udccb  Copy link")
        if (imageUrl != null) items.add(com.toolsboox.plugin.feeds.ot.FeedNoteGram.photoGramLabel(requireContext()))
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(url)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    1 -> {
                        // A link the reader already knows carries its own presentation \u2014 title,
                        // excerpt, featured image \u2014 so the Later row shows the thing, not the URL.
                        val known = (listOfNotNull(currentArticle) + allEntries).firstOrNull { it.url == url }
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
                        val a = currentArticle
                        com.toolsboox.plugin.feeds.ot.FeedNoteGram.savePhotoGram(
                            this, calendarDayService, documentsRoot(), imageUrl ?: url,
                            a?.title.orEmpty(), a?.feedTitle.orEmpty(), a?.url.orEmpty())
                    }
                }
            }.show()
    }

    /** Retired on-device (07-24): with the strip's focal slot now showing the active filter,
     *  a words-label underneath said the same thing twice — and its tap-for-today read as
     *  "the W30 disappeared". The strip is the one truth; empty states still name the window. */
    private fun updateWindowLabel() {
        binding.feedsWindowLabel.visibility = View.GONE
    }

    /** The active window, named plainly: "Week 30 · Jul 20–26" / "July 2026" / "Q3 2026". */
    private fun windowLabel(): String {
        val loc = java.util.Locale.getDefault()
        val md = java.time.format.DateTimeFormatter.ofPattern("MMM d", loc)
        val (start, end) = navWindow()
        return when (navGranularity) {
            "week" -> {
                val wk = navAnchor.get(java.time.temporal.WeekFields.of(loc).weekOfWeekBasedYear())
                "Week $wk · ${start.format(md)}–${end.minusDays(1).format(md)}"
            }
            "month" -> navAnchor.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", loc))
            "quarter" -> "Q${(navAnchor.monthValue - 1) / 3 + 1} ${navAnchor.year}"
            "year" -> "${navAnchor.year}"
            else -> navAnchor.format(java.time.format.DateTimeFormatter.ofPattern("EEE · MMM d", loc))
        }
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
    }.getOrNull() ?: runCatching {
        // Later-list rows carry a bare date — the day the link was ADDED (that's the day
        // they should file under in the timeline, not their publication date).
        java.time.LocalDate.parse(e.publishedAt)
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
                // A missing/failed pattern must not kill the strip: an unrendered
                // CalendarNavBarHost never sets its currentDay, and a day-less strip swallows
                // every touch — the date filter would render once, then silently no-op.
                val p = runCatching { calendarPatternService.load(root, navAnchor, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(navAnchor.year, loc).fill()
                cd to p
            }
            if (isAdded) navBar?.render(day, pat)
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
        // Picking a lens while browsing a PAST window stays in that window (read timeline),
        // instead of snapping to the live unread view — the date and the lens compose.
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        mode = if (newMode == "feed" && !liveToday) "read" else newMode
        kindFilter = kind
        refresh()
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
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
        // Accessibility: Small / Medium / Large for the feed rows and for every modal.
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        val feedTier = a11y.getString("feed_text_size", "medium")
        fun ft(m: String) = if (feedTier == m) "◉" else "○"
        fun setFeedTier(m: String) {
            a11y.edit().putString("feed_text_size", m).apply()
            adapter.textTier = m
        }
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
            // Menu and dialog sizes moved to Settings → Text size…; this one stays because it
            // sizes the feed's own rows, which is this screen's business and nobody else's.
            "Feed text size" to listOf(
                "${ft("small")}  Small" to { setFeedTier("small") },
                "${ft("medium")}  Medium" to { setFeedTier("medium") },
                "${ft("large")}  Large" to { setFeedTier("large") }
            ),
            "Screen" to listOf(
                "🔄  Rotate screen" to { cycleScreenOrientation() },
                "🧭  Auto-rotate (gyro)" to { toggleAutoRotate() },
                ((if (volumeTurnOn()) "☑" else "☐") + "  Volume keys scroll") to {
                    requireContext().getSharedPreferences("ledger_reader_nav", 0)
                        .edit().putBoolean("volume_turn", !volumeTurnOn()).apply()
                    Unit
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
        // Act on the list AS DISPLAYED — drilling into a feed/category/lens narrows the
        // adapter, not allEntries, and clearing "everything loaded" from inside a category
        // wiped articles the user never saw. Synthetic rows (negative ids: Later, Pickings,
        // local feeds) have no server entry — sending their ids would mark unrelated real
        // Miniflux entries read.
        val ids = adapter.current().map { it.id }.filter { it > 0 }
        if (ids.isEmpty()) { showMessage(R.string.feeds_nothing_to_mark); return }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { miniflux.setStatus(url, token, ids, "read") }
            if (ok is MinifluxClient.Result.Err) {
                android.widget.Toast.makeText(requireContext(), "⚠ Mark-read failed — ${ok.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
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

    /** Long-press a row → mark every article ABOVE it read (the common reader gesture; parity with
     *  the iPad's "Mark above as read" context item). Confirmed first, because on e-ink a long-press
     *  can be accidental and this is a bulk change. Marks the same "above" set the scroll-mode
     *  handler does — everything before this row in the current list. */
    private fun markAboveAsRead(entry: FeedEntry) {
        val list = adapter.current()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx <= 0) return
        val above = list.take(idx).filterNot { FeedReadState.isRead(it.id) }
        if (above.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Mark ${above.size} above as read?")
            .setPositiveButton("Mark read") { _, _ ->
                above.forEach { markEntryRead(it, notify = false) }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Mark one entry read: locally (greys the row) + on Miniflux (durable). Idempotent. */
    private fun markEntryRead(entry: FeedEntry, notify: Boolean = true) {
        if (FeedReadState.isRead(entry.id)) return
        FeedReadState.mark(entry.id)
        if (notify) adapter.notifyDataSetChanged()
        val p = prefs(); val u = p.getString(KEY_URL, "").orEmpty(); val tk = p.getString(KEY_TOKEN, "").orEmpty()
        // Only real Miniflux entries have positive ids — synthetic rows (Later, Pickings,
        // local feeds) are all negative and must never be pushed to the server, where a
        // small synthetic id would address someone ELSE's entry.
        if (u.isNotBlank() && tk.isNotBlank() && entry.id > 0)
            lifecycleScope.launch(Dispatchers.IO) { runCatching { miniflux.markRead(u, tk, entry.id) } }
    }

    /** Apply the persisted pill orientation to the feeds pill (vertical on narrow screens by choice). */
    private fun applyFeedsPillOrientation() {
        val vertical = prefs().getBoolean("feeds_pill_vertical", false)
        binding.feedsPill.orientation =
            if (vertical) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        applyGripOrientation(binding.feedsGrip, vertical)
        // The right-edge nav trio is static (never passes through makeDraggable), so it must
        // opt into the modal-size dial itself or it stays full-width in the reserved gutter.
        applyPillSizing(binding.feedsNavPill)
        keepListClearOfPill()
    }

    /**
     * The vertical strip fits IN the right margin; the rows take the rest.
     *
     * The right edge carries the paging trio (always vertical) and, when flipped, the main
     * pill. Rather than letting them park over the row's thumbnail column, the list reserves
     * exactly the widest strip's width as its end gutter — the strip sits in that margin, and
     * every row's title/blurb/image get the full remaining width. A horizontal main pill still
     * gets its clearance at the bottom.
     *
     * Measured from the pills rather than hard-coded, so it stays right when the main pill is
     * flipped or collapsed (and when the shared pill sizing slims down, the margin narrows with
     * it). `clipToPadding=false` keeps the scroll range whole: rows still travel the full
     * height, they just come to rest somewhere you can reach them.
     */
    private fun keepListClearOfPill() {
        binding.feedsPill.post {
            if (!isAdded) return@post
            val vertical = binding.feedsPill.orientation == android.widget.LinearLayout.VERTICAL
            val gap = (4 * resources.displayMetrics.density).toInt()
            val navW = if (binding.feedsNavPill.visibility == View.VISIBLE) binding.feedsNavPill.width else 0
            val mainW = if (vertical) binding.feedsPill.width else 0
            val end = maxOf(navW, mainW).let { if (it > 0) it + gap else 0 }
            val bottom = if (vertical) 0 else binding.feedsPill.height + gap
            binding.feedsRecycler.clipToPadding = false
            binding.feedsRecycler.setPaddingRelative(
                binding.feedsRecycler.paddingStart, binding.feedsRecycler.paddingTop, end, bottom
            )
        }
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
        // On-star reactions (both off/empty by default) — see StarHooks.
        val hookUrlIn = android.widget.EditText(ctx).apply {
            setText(p.getString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_URL, ""))
            hint = "https://… (POST each star here)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val hookSecretIn = android.widget.EditText(ctx).apply {
            setText(p.getString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_SECRET, ""))
            hint = "Shared secret (optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val autoSynth = android.widget.CheckBox(ctx).apply {
            text = "Ask 3 questions on every star (uses your AI key)"
            isChecked = p.getBoolean(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_AUTOSYNTH, false)
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(4), dpPx(20), 0)
            addView(label(getString(R.string.feeds_url_hint))); addView(urlIn)
            addView(label(getString(R.string.feeds_token_hint))); addView(tokIn)
            addView(readerDefault)
            addView(label("When you star an item"))
            addView(label("Send to a webhook")); addView(hookUrlIn); addView(hookSecretIn)
            addView(autoSynth)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Feed settings")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                p.edit().putString(KEY_URL, urlIn.text.toString().trim())
                    .putString(KEY_TOKEN, tokIn.text.toString().trim())
                    .putString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_URL, hookUrlIn.text.toString().trim())
                    .putString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_SECRET, hookSecretIn.text.toString().trim())
                    .putBoolean(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_AUTOSYNTH, autoSynth.isChecked)
                    .apply()
                showParsed = readerDefault.isChecked
                if (currentArticle != null) renderArticle()
                showMessage(R.string.feeds_saved); refresh()
            }
            .setNeutralButton("Refresh") { _, _ -> refresh() }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Populate the persistent left directory pane (two-pane feed). The drawer reads top-down as
     * Michael specced it: VIEWS (All/Unread/Read/Starred/Later — a state toggle that COMPOSES
     * with everything below — plus Clear, which acts on the visible list) → FEEDS (The Read/
     * Watch/Listen as two-level accordions: medium → categories → feed names, plus Asks &
     * Answers) → SEARCH (search + smart feeds) → MORE. Selecting a row filters the right list.
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
                // Crisp selection with no monochrome shade: bold + underline, never a gray fill.
                if (selected) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                }
                setOnClickListener {
                    // Selecting a feed while reading returns to the list.
                    if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
                    onClick()
                }
            }
            container.addView(tv)
        }
        // One directory, one style, no ladder: VIEWS → FEEDS → SEARCH → SOURCES → MORE, all in
        // this single drawer. Replaces the old slim-pane ⇄ accordion split you had to climb between.
        fun section(title: String) {
            container.addView(android.widget.TextView(ctx).apply {
                text = title
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                letterSpacing = 0.08f
                setPadding(dpPx(11), dpPx(13), dpPx(8), dpPx(3))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }


        // Now Playing at the very top while a podcast/read-aloud is going — the TRANSPORT ITSELF,
        // inline, not a row that opens the modal: Michael wants the controls "a layer sooner (the
        // sidebar), not a popup from the sidebar". The card drives whichever backend is live
        // (podcast audio or TTS), keeps its own 1s clock while attached, and asks for a re-render
        // when playback ends so it folds away. (Surfaced when playback starts, see playEntryAudio.)
        if (com.toolsboox.ui.plugin.LedgerPlayer.isActive) {
            container.addView(NowPlayingCard.build(ctx) { if (isAdded) renderDirectory() })
        }

        // ── VIEWS ── All · Unread · Read · Starred · Later: a persistent STATE TOGGLE, not five
        // destinations. The view composes with the medium and category below it — pick Starred,
        // then a category under The Read, and the list is starred ∩ read ∩ that category. (The
        // composition rides on the existing plumbing: the view decides which corpus refresh()
        // loads into allEntries, kindFilter narrows it by medium, and category rows narrow that
        // again.) Unread/Read absorbed the old SHOW section — one axis, one place. The active
        // view is a solid black chip: it must stay visibly ON while you browse the folders below.
        fun stateChip(label: String, selected: Boolean, onClick: () -> Unit) =
            android.widget.TextView(ctx).apply {
                text = label; textSize = 13.5f
                gravity = android.view.Gravity.CENTER
                setPadding(dpPx(4), dpPx(7), dpPx(4), dpPx(7))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpPx(8).toFloat()
                    if (selected) setColor(0xFF000000.toInt())
                    else { setColor(0xFFFFFFFF.toInt()); setStroke(dpPx(1), 0xFF000000.toInt()) }
                }
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
                    onClick()
                }
            }
        // Two chips to a row so all five fit without eating the drawer's height.
        fun chipPair(vararg chips: android.widget.TextView) {
            container.addView(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                for (c in chips) addView(c, android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(dpPx(2), dpPx(1), dpPx(2), dpPx(1)) })
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpPx(4), 0, dpPx(4), 0) })
        }
        // Search at the very TOP — it was buried. A "SEARCH" section: a LIVE-filtering field (types
        // filter as you go; the keyboard Search action alone didn't fire reliably on e-ink) + three
        // tiny scope radios — All · This feed · This category — starting on All.
        section("SEARCH")
        val searchField = android.widget.EditText(ctx).apply {
            hint = "Search"; textSize = 13f; setSingleLine()
            setPadding(dpPx(10), dpPx(7), dpPx(10), dpPx(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpPx(18).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dpPx(1), 0xFF000000.toInt())
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        fun searchBase(): List<FeedEntry> = when (searchScope) {
            "feed" -> slimFeedFilter?.let { f -> allEntries.filter { it.feedTitle == f } } ?: applyKind(allEntries)
            "category" -> kindFilter?.let { k -> allEntries.filter { it.kind == k } } ?: applyKind(allEntries)
            else -> applyKind(allEntries)
        }
        fun runSearch() {
            val q = searchField.text.toString().trim().lowercase()
            if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
            adapter.submit(if (q.isEmpty()) searchBase() else searchBase().filter {
                it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q) || it.feedTitle.lowercase().contains(q)
            })
        }
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { runSearch() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        container.addView(searchField, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dpPx(4), dpPx(2), dpPx(4), dpPx(2)) })
        container.addView(android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            fun radio(lbl: String, sc: String) = android.widget.RadioButton(ctx).apply {
                text = lbl; textSize = 10f; setPadding(dpPx(1), 0, dpPx(6), 0); isChecked = searchScope == sc
                setOnClickListener { searchScope = sc; runSearch() }
            }
            addView(radio("All", "all")); addView(radio("This feed", "feed")); addView(radio("This category", "category"))
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dpPx(4), 0, dpPx(4), dpPx(4)) })
        section("VIEWS")
        chipPair(
            stateChip("📰 All", mode == "both" || mode == "edition") { switchTo("both", kindFilter) },
            stateChip("● Unread", mode == "feed") { switchTo("feed", kindFilter) }
        )
        chipPair(
            stateChip("○ Read", mode == "read") { switchTo("read", kindFilter) },
            stateChip("⭐ Starred", mode == "stars") { switchTo("stars", kindFilter) }
        )
        chipPair(stateChip("🔖 Later", mode == "later") { switchTo("later", kindFilter) })
        // Clear rides with the view controls, not at the bottom of a scroll: it acts on the
        // LIST AS DISPLAYED, so it belongs beside the chips that decide what's displayed.
        // (Undo snackbar included — markAllRead holds the swept ids until it lapses.)
        chipPair(stateChip("🧹 Clear", false) { markAllRead() })

        // ── FEEDS ── the media folders. One tap on The Read does BOTH things — loads that
        // medium's All (within the active view) in the right panel AND drops its categories out
        // right beneath (▸/▾ caret, indented rows inside the accent outline: the same accordion
        // idiom as showAccordion). A second tap folds it back AND clears the medium — that's the
        // exit back to the view's plain All. Opening one lens folds the others (single-open).
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        val lensOpen = a11y.getString("feeds_lens_open", "") ?: ""
        // Views the medium filter can ride on; anything else (smart/local/…) snaps back to All.
        val composable = mode in setOf("feed", "read", "both", "edition", "stars", "later")
        fun lensRow(emoji: String, title: String, lens: String) {
            val open = lensOpen == lens
            row("$emoji  ${if (open) "▾" else "▸"}  $title", true, kindFilter == lens) {
                // Redraw the drawer NOW (refresh()'s re-render is async and skips some error
                // paths), then retune the right panel.
                if (open) {
                    a11y.edit().putString("feeds_lens_open", "").apply()
                    kindFilter = null
                    renderDirectory(); refresh()
                } else {
                    a11y.edit().putString("feeds_lens_open", lens).apply()
                    if (composable) { kindFilter = lens; renderDirectory(); refresh() }
                    else { renderDirectory(); switchTo("feed", lens) }
                }
            }
            if (!open) return
            // The dropdown: this medium's categories, unread-counted, drawn from ALL loaded
            // entries (pool may already be narrowed to this lens; that's fine — same rows).
            val inLens = allEntries.filter { it.kind == lens }
            val cats = inLens.mapNotNull { it.categoryLabel?.takeIf { c -> c.isNotBlank() } }
                .distinct().sortedBy { it.lowercase() }
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(dpPx(1), com.toolsboox.ot.LedgerTheme.accent(ctx))
                    cornerRadius = dpPx(8).toFloat()
                }
                setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(4))
            }
            fun subRow(label: String, indent: Int, header: Boolean, selected: Boolean, onClick: () -> Unit) {
                box.addView(android.widget.TextView(ctx).apply {
                    text = label
                    textSize = if (header) 13.5f else 12.5f
                    setTextColor(0xFF000000.toInt())
                    if (header || selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    if (selected) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    // Narrow Palma drawer: pull the indent in and let a long feed name WRAP to a
                    // second line instead of truncating — no marquee (its constant repaint ghosts
                    // on e-ink), just two static lines that stay readable.
                    setPadding(dpPx((indent - 6).coerceAtLeast(4)), dpPx(7), dpPx(6), dpPx(7))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    setOnClickListener {
                        if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
                        onClick()
                    }
                })
            }
            // The categories fold too, same dual gesture one level down ("those feed lists
            // should be collapsable, but still pull up the all when you select it"): tap a
            // category → its All loads on the right AND its feed names drop out beneath,
            // indented deeper and lighter. Tap again → just folds. One open category per
            // medium, remembered beside the lens state.
            val catOpen = a11y.getString("feeds_cat_open_$lens", "") ?: ""
            // Feeds with no Miniflux folder still need a home now that the flat SOURCES list is
            // gone — they gather under an Uncategorised pseudo-category at the bottom of each
            // medium, behaving exactly like a real one.
            val groups = cats.map { c -> c to inLens.filter { it.categoryLabel == c } }.toMutableList()
            val strays = inLens.filter { it.categoryLabel.isNullOrBlank() && it.feedTitle.isNotBlank() }
            if (strays.isNotEmpty()) groups.add("Uncategorised" to strays)
            if (groups.isEmpty()) subRow("(nothing in this lens yet)", 24, false, false) {
                slimFeedFilter = null
                adapter.submit(filterByNavDay(inLens))
            }
            for ((c, inCat) in groups) {
                val unread = inCat.count { !it.read }
                val cOpen = catOpen == c
                val caret = if (cOpen) "▾" else "▸"
                subRow(if (unread > 0) "$caret  $unread · $c" else "$caret  $c", 24, true, false) {
                    if (cOpen) {
                        // Fold only — the right panel stays where the reader left it.
                        a11y.edit().putString("feeds_cat_open_$lens", "").apply()
                        renderDirectory()
                    } else {
                        a11y.edit().putString("feeds_cat_open_$lens", c).apply()
                        slimFeedFilter = null
                        renderDirectory()
                        adapter.submit(filterByNavDay(inCat))
                    }
                }
                if (!cOpen) continue
                for (f in inCat.map { it.feedTitle }.filter { it.isNotBlank() }
                    .distinct().sortedBy { it.lowercase() }) {
                    val inFeed = inCat.filter { it.feedTitle == f }
                    val fu = inFeed.count { !it.read }
                    subRow(if (fu > 0) "$fu · $f" else f, 44, false, slimFeedFilter == f) {
                        slimFeedFilter = f
                        renderDirectory()
                        adapter.submit(filterByNavDay(inFeed))
                    }
                }
            }
            container.addView(box, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpPx(6), dpPx(1), dpPx(6), dpPx(4)) })
        }
        section("FEEDS")
        lensRow("📖", "The Read", "read")
        lensRow("📺", "The Watch", "watch")
        lensRow("🎧", "The Listen", "listen")
        // Asks & Answers rides with the media folders per Michael's list, but it's a different
        // corpus (the local AskFeedStore Q&A log, mode "asklog") with no Miniflux categories —
        // so it's a plain folder row: no caret, no dropdown, and the view toggle doesn't compose.
        row("💬  Asks & Answers", true, mode == "asklog") { switchTo("asklog", null) }

        section("SEARCH")
        row("🔬  Search…", true, false) { showFeedSearch() }
        com.toolsboox.plugin.feeds.nw.SmartFeedStore.all(ctx).forEach { sf ->
            row("#  ${sf.name}", false, mode == "smart" && smartFeed?.name == sf.name) { switchToSmart(sf) }
        }
        row("➕  Add smart feed…", false, false) { promptAddSmartFeed() }

        // (No SOURCES section anymore: it had become a full duplicate of the FEEDS accordion —
        // "theres just a second list below it that says all feeds" — since categories now nest
        // their feeds right where they are. Clear moved up beside the VIEWS chips; feeds with
        // no folder live under each medium's Uncategorised. The old SHOW section is likewise
        // gone — Unread/Read/All are the VIEWS axis. Smart feeds live under SEARCH.)

        // ── MORE ── the rest of the iPad's set: Pickings, local (no-server) feeds, OPML.
        section("MORE")
        row("❝  Pickings", true, mode == "pickings") { switchTo("pickings", null) }
        row("📡  Local feeds", true, mode == "local" && localSub == null) { switchToLocal(null) }
        com.toolsboox.plugin.feeds.nw.LocalFeedStore.subscriptions(ctx).forEach { sub ->
            row("·  ${sub.title}", false, mode == "local" && localSub?.id == sub.id) { switchToLocal(sub) }
        }
        row("➕  Add feed by URL…", false, false) { showAddLocalFeed() }
        row("⬆  Import OPML…", false, false) { opmlPicker.launch("*/*") }
        row("⬇  Export OPML…", false, false) { exportOpml() }
    }

    /**
     * Opening "the feed directory" now just opens the ONE unified drawer (VIEWS · FEEDS · SEARCH · MORE) —
     * there's no separate accordion to ladder into anymore. Kept as a named entry point because a
     * few arrival paths (onResume, smart-feed-added) call it to pop the drawer open.
     */
    private fun showFeedDirectory() {
        applyDirectoryDrawer(true)
        renderDirectory()
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
            reflectStar(entry, !entry.starred)
            return
        }
        if (entry.id <= 0) {
            // Later/Pickings rows have synthetic negative ids and no Miniflux entry: star into
            // the Ledger corpus only — never send a synthetic id to the server.
            if (!entry.starred) lifecycleScope.launch(Dispatchers.IO) { logStar(entry) }
            showMessage(if (entry.starred) R.string.feeds_unstarred else R.string.feeds_starred)
            reflectStar(entry, !entry.starred)
            return
        }
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val wasStarred = entry.starred
        lifecycleScope.launch {
            // Honest UI: only toast success and log to the corpus when the server call
            // succeeded. Discarding the Result meant offline stars toasted "Starred",
            // entered the Ledger, and silently never reached Miniflux — shelf and corpus
            // then disagreed forever.
            val res = withContext(Dispatchers.IO) { miniflux.toggleStar(url, token, entry.id) }
            when (res) {
                is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok -> {
                    // Newly starred → log to today's Ledger so it joins the corpus + sync.
                    if (!wasStarred) withContext(Dispatchers.IO) { logStar(entry) }
                    showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
                    reflectStar(entry, !wasStarred)
                }
                is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Err -> {
                    android.widget.Toast.makeText(
                        requireContext(), "⚠ Star didn't reach Miniflux — ${res.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Reflect a star LOCALLY — flip the model + repaint the single row, and never call
     *  refresh(): that reloads the feed and closes any open in-pane article, which is what
     *  kicked you out of what you were reading when you starred it. Counts don't change on a
     *  star (unread membership is unchanged), so the drawer is left untouched — no e-ink flash. */
    private fun reflectStar(entry: FeedEntry, nowStarred: Boolean) {
        allEntries = allEntries.map { if (it.id == entry.id) it.copy(starred = nowStarred) else it }
        adapter.setStarred(entry.id, nowStarred)
        if (currentArticle?.id == entry.id) {
            currentArticle = currentArticle?.copy(starred = nowStarred)
            // Reflect the filled/hollow star on the reader toolbar immediately.
            binding.feedsStar.setImageResource(if (nowStarred) R.drawable.ic_starred else R.drawable.ic_star)
        }
    }

    /** Append a starred article to today's CalendarDay as a ReadingEvent(article) — and mint
     *  its gram onto today's Intake page. Every newly-starred path (Miniflux, local feed,
     *  synthetic Later/Pickings row) comes through here, on Dispatchers.IO. */
    private fun logStar(entry: FeedEntry) {
        try {
            val root = documentsRoot()
            val today = LocalDate.now()
            val day = calendarDayService.load(root, today, null, Locale.getDefault())
            // Idempotent by id: star → unstar → star used to append duplicate art-<id>
            // events that rode sync to every device.
            val id = "art-${entry.id}"
            val pubDay = runCatching { LocalDate.parse(entry.publishedAt.take(10)) }.getOrNull()
            val pubDate = pubDay?.atTime(12, 0)?.atZone(java.time.ZoneId.of("UTC"))
                ?.toInstant()?.let { Date.from(it) }
            day.readingEvents.removeAll { it.id == id }
            day.readingEvents.add(
                ReadingEvent(
                    id = id,
                    kind = ReadingEvent.Kind.ARTICLE,
                    date = Date(),
                    title = entry.title,
                    source = entry.feedTitle.ifBlank { null },
                    url = entry.url.ifBlank { null },
                    note = entry.blurb.ifBlank { null },
                    starred = true,
                    published = pubDate
                )
            )
            calendarDayService.save(root, today, day)
            // A star lives on TWO days: today (when you starred it) and the day the piece
            // was published — the Log's windowed scan finds each in its own day file. The
            // companion carries the star date in its note as the clarifying label.
            if (pubDay != null && pubDay != today && pubDate != null) {
                val pub = calendarDayService.load(root, pubDay, null, Locale.getDefault())
                val pubId = "$id-pub"
                pub.readingEvents.removeAll { it.id == pubId }
                pub.readingEvents.add(
                    ReadingEvent(
                        id = pubId,
                        kind = ReadingEvent.Kind.ARTICLE,
                        date = pubDate,
                        title = entry.title,
                        source = entry.feedTitle.ifBlank { null },
                        url = entry.url.ifBlank { null },
                        note = "★ starred ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}",
                        starred = true,
                        published = pubDate
                    )
                )
                calendarDayService.save(root, pubDay, pub)
            }
        } catch (e: Exception) {
            Timber.w(e, "failed to log starred article")
        }
        // Grams for stars: the ★ also lands the entry as a movable link-card gram on today's
        // Intake page — the read-later board Michael arranges and writes pen notes around.
        // Render + place stay on this IO thread; the row's cached thumbnail is reused when the
        // list already loaded it; deduped by sourceLink inside (re-stars don't stack twins).
        runCatching {
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.placeStarGram(
                this, calendarDayService, documentsRoot(),
                title = entry.title, feedTitle = entry.feedTitle, url = entry.url,
                kind = entry.kind, imageUrl = entry.imageUrl,
                thumb = entry.imageUrl?.let { FeedThumbCache.get(it) }
            )
        }.onFailure { Timber.w(it, "intake gram failed") }
        // Generic, opt-in on-star reactions (webhook-out / in-app synthesis). No-ops unless
        // the user configured them in Feed settings.
        runCatching {
            com.toolsboox.plugin.feeds.nw.StarHooks.onNewStar(
                requireContext().applicationContext, prefs(), entry
            ) { title, body, srcUrl -> writeCorrespondence(title, body, srcUrl) }
        }.onFailure { Timber.w(it, "star hooks failed") }
    }

    /** Write a correspondence ReadingEvent ("the Ledger wrote back") into today's day. The
     *  "↩ " source prefix makes the Log render it as a reply and keeps it out of the feed. */
    private fun writeCorrespondence(title: String, body: String, sourceUrl: String?) {
        runCatching {
            val root = documentsRoot()
            val today = LocalDate.now()
            val day = calendarDayService.load(root, today, null, Locale.getDefault())
            day.readingEvents.add(
                ReadingEvent(
                    id = "reply-${java.util.UUID.randomUUID()}",
                    kind = ReadingEvent.Kind.ARTICLE,        // wire kind (Boox/iOS both decode)
                    date = Date(),
                    title = title,
                    source = "↩ the Ledger wrote back",       // "↩" flags it as correspondence
                    url = sourceUrl?.ifBlank { null },
                    excerpt = body.ifBlank { null }
                )
            )
            calendarDayService.save(root, today, day)
        }.onFailure { Timber.w(it, "failed to write correspondence") }
    }


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

        // Synthetic-row id bands, below LocalFeedStore's (-1000 .. -1e9) so a made-up id
        // can never collide with a real Miniflux or local-feed entry. Rows count DOWN
        // from their base (id--), so each band stays disjoint.
        private const val PICKINGS_ID_BASE = -1_100_000_000L
        private const val LATER_ID_BASE = -1_200_000_000L
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
