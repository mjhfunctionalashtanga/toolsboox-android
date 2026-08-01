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
        // Leaving the screen is the other natural flush point for the Bluesky "seen" batch — the
        // size threshold alone would strand a half-batch on the device every time he read six posts
        // and went back to the day page, and those six would be unread again tomorrow.
        flushBlueskySeen()
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
        // The header's ▦ hub and RSS drawer buttons retire with the rail: the rail's ☰ Hub is
        // the same door, and the drawer toggle rides the rail with the rest of the surface's
        // actions (one door per action — the navigator strip keeps the whole header width).
        binding.feedsHubButton.visibility = View.GONE
        binding.ledgerButton.visibility = View.GONE
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

        // BOTH floating pills retire — the tucked action rail carries the paging trio, the
        // occasional controls AND the article actions, adapting its dress to the mode the way
        // the pill's visibility dance used to (see applyArticlePill, which now re-dresses the
        // rail). The pills' views stay in the layout, hidden, so nothing referencing them
        // breaks; their buttons keep answering performClick.
        binding.feedsPill.visibility = View.GONE
        binding.feedsNavPill.visibility = View.GONE
        setupActionRail(
            binding.feedsRail, "feeds",
            actions = { feedsRailActions() },
            hub = { showLedgerDirectory() }
        )

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
        mode = m; kindFilter = k; laterLane = FeedSelection.consumeLaterLane()
        // Arriving on a Later lane from the hub opens the folder it belongs to, so the drawer shows
        // where you are instead of a folded row and a list you can't account for.
        if (laterLane != null) a11y.edit().putBoolean("feeds_later_open", true).apply()
        // Same truth for the media lenses: arriving on The Listen must fold whatever lens the
        // drawer last remembered open (The Read stayed expanded beside a Listen list — "not
        // correct ux"). The single-open rule the pane's own taps keep applies to arrivals too.
        if (k != null) a11y.edit().putString("feeds_lens_open", k).apply()
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
    /**
     * Which Later List LANE is showing (null = the whole list), when `mode == "later"`.
     *
     * Its own field rather than reusing [kindFilter], even though three of the four lanes share
     * their names with the media lenses. A lane is where a link was FILED — a fact on disk, one
     * field of one day file — while [kindFilter] is a guess the row makes about itself from its
     * category and its URL. Folding them together would mean the 📧 Email lane (whose links are
     * mostly articles) either vanished from its own lens or dragged half The Read in with it.
     */
    private var laterLane: String? = null
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

    /**
     * Synthetic row id → the post's AT URI, for the Bluesky source.
     *
     * The URI does not go on [FeedEntry] and should not: that model is shared by every source in
     * this screen, and widening it for one source's key is how a lean data class becomes a union of
     * everything anyone ever needed. It also could not ride in `url`, which has to stay the
     * bsky.app permalink so "open externally" and the long-press both work. So the mapping is held
     * beside the list, rebuilt whenever the timeline is loaded.
     */
    private val bskyUriById = HashMap<Long, String>()

    /**
     * Posts read but not yet reported to the site.
     *
     * Batched because the server's contract says to batch: one request per row as the list scrolls
     * would be a cellular round trip per post, which on a Boox costs both time and battery. Flushed
     * when the batch is big enough to be worth a request, and again on the way out of the screen —
     * whichever comes first. Kept (not cleared) when a flush fails, because a silently-dropped
     * batch means those posts come back unread on the next refresh and he reads them twice.
     */
    private val pendingBskySeen = LinkedHashSet<String>()

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
        // Guarded: a pasted URL is work — a stray touch outside must not throw it away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Add feed")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.length > 8) lifecycleScope.launch { addFeedByUrl(url) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
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
        if (mode == "bsky") { loadBlueskyFeed(); return }

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
        val lens = kindFilter
        val viewMode = mode
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val base = when (viewMode) {
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
                // A lens is a QUESTION PUT TO THE SERVER, not a sieve held over the front page.
                // Unread/Read/All each fetch one flat page (50/100/150 rows) ordered by publish
                // date across every feed at once — so a medium that publishes rarely never makes
                // that page. Michael's podcasts publish an episode a day against text feeds that
                // publish dozens, and The Listen showed "(nothing in this lens yet)" while whole
                // 🎧 folders sat on the server unread. Ask the folders themselves, exactly as the
                // iPad does (FeedStore `.media(categoryIDs:)` merges `entries(categoryID:)` per
                // category), and union the result with the flat page — the page still contributes
                // audio that lives in a plainly-named folder, which is what `kind` catches by
                // enclosure and category ids never would.
                if (lens != null) unionLensCategories(url, token, viewMode, lens, base) else base
            }
            loading = false
            binding.progress.visibility = View.INVISIBLE
            when (result) {
                is MinifluxClient.Result.Ok -> {
                    allEntries = withOfflineListen(result.value)
                    com.toolsboox.plugin.feeds.nw.FeedCache.saveEntries(requireContext(), cacheKey(), result.value)
                    prefetchParsed(url, token, result.value)   // download readable (parsed) versions offline
                    // Opportunistic janitor — parsed HTML accrued forever (the .versions lesson) —
                    // and the auto-keep sweep rides with it. Auto-keep used to run right here, on
                    // Main: it stats every candidate file, and now that each kept episode also
                    // files a row in the offline index it writes that JSON too. Small work, but
                    // file work, and file work on the UI thread of an e-ink device is a stutter
                    // on every single feed load. Newest-first; the count cap trims the rest.
                    val appCtx = requireContext().applicationContext
                    val loaded = result.value
                    lifecycleScope.launch(Dispatchers.IO) {
                        com.toolsboox.plugin.feeds.nw.FeedCache.prune(appCtx)
                        com.toolsboox.plugin.feeds.nw.LaterMedia.keepRecent(appCtx, loaded)
                    }
                    val filter = FeedSelection.filterFeedTitle
                    FeedSelection.filterFeedTitle = null
                    var shown = applyKind(allEntries)
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
                    val cached = withOfflineListen(
                        com.toolsboox.plugin.feeds.nw.FeedCache.loadEntries(requireContext(), cacheKey()))
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

    /**
     * Union DOWNLOADED audio into The Listen — episodes that live on this device but have aged out
     * of whatever the server just handed back.
     *
     * The server's recent window and Michael's downloads are two different sets. He keeps the last
     * eight episodes offline for the subway; the unread page moves on within a day or two. Without
     * this the file is on the Boox, the ⬇ says it's downloaded, and The Listen never shows it —
     * the episode simply stops existing as far as the lens is concerned. The iPad has always
     * unioned these in (`FeedLedgerView.load`: `episodes = live + downloadedOnly`); this is the
     * Android half, now that [LaterMedia] keeps a metadata index to rebuild the rows from.
     *
     * Rebuilt rows carry the episode's REAL entry id, so one that later returns to the live list
     * dedupes against itself and its star / mark-read still reach the server. They're marked read
     * (that's why they aged out) and filed under a 🎧 Downloaded folder so the drawer says plainly
     * where they came from.
     */
    private fun withOfflineListen(entries: List<FeedEntry>): List<FeedEntry> {
        if (kindFilter != "listen") return entries
        val present = entries.mapTo(HashSet()) { it.id }
        val extra = com.toolsboox.plugin.feeds.nw.LaterMedia.downloaded(requireContext())
            .filter { it.entryId !in present }
            .map { i ->
                FeedEntry(
                    id = i.entryId, title = i.title, feedTitle = i.feedTitle, url = i.sourceUrl,
                    author = null, content = "", publishedAt = i.publishedAt,
                    starred = false, read = true, category = "🎧 Downloaded",
                    enclosureImage = i.imageUrl, enclosureAudio = i.audioUrl
                )
            }
        // Aged-out downloads TRAIL the live list — they are older by definition, and pushing them
        // to the top would bury today's episodes under last month's.
        return if (extra.isEmpty()) entries else entries + extra
    }

    /** Miniflux category list, held for the life of the screen. The lens fetch below needs the
     *  (id, title) map on every reload and the folder set changes about as often as Michael adds
     *  a subscription — re-asking for it on each lens tap is a round trip for an answer we
     *  already have. Blank on failure so the union simply degrades to the flat page. */
    private var categoriesCache: List<Pair<Long, String>>? = null

    /**
     * Merge the lens's own folders into [base] — the per-medium server fetch, mirroring the iPad's
     * `.media(categoryIDs:)` source.
     *
     * Only the corpus views compose this way. Starred and the Edition window are DEFINED by
     * something other than the medium (a bookmark; a publish window), so pouring whole 🎧 folders
     * into them would put unstarred episodes under the ⭐ chip and items from outside the window
     * into the Edition — the chip would stay lit over a list that no longer obeyed it.
     *
     * Blocking; call from [Dispatchers.IO].
     */
    private fun unionLensCategories(
        url: String, token: String, viewMode: String, lens: String,
        base: MinifluxClient.Result<List<FeedEntry>>
    ): MinifluxClient.Result<List<FeedEntry>> {
        if (viewMode !in setOf("feed", "read", "both")) return base
        val cats = categoriesCache
            ?: (miniflux.categories(url, token) as? MinifluxClient.Result.Ok)?.value?.also { categoriesCache = it }
            ?: return base
        val ids = cats.filter { (_, title) -> FeedEntry.categoryKind(title) == lens }.map { it.first }
        if (ids.isEmpty()) return base
        val status = when (viewMode) { "feed" -> "unread"; "read" -> "read"; else -> null }
        val extra = ids.flatMap { id ->
            (miniflux.fetchCategory(url, token, id, status) as? MinifluxClient.Result.Ok)?.value.orEmpty()
        }
        if (extra.isEmpty()) return base
        // The flat page still leads: it is what every other view shows, and an entry that appears
        // in both must keep the copy the rest of the screen is holding (same id, same read/star
        // state). distinctBy keeps the first, so `base + extra` is the right order.
        val merged = (((base as? MinifluxClient.Result.Ok)?.value).orEmpty() + extra)
            .distinctBy { it.id }
            // Sorted on the parsed instant, not on the timestamp STRING: feeds hand back offsets
            // as well as Z, and "…T09:00:00-04:00" sorts before "…T08:00:00Z" as text while being
            // the later moment — which is how a merged list ends up shuffled by timezone.
            .sortedByDescending {
                runCatching { java.time.OffsetDateTime.parse(it.publishedAt).toEpochSecond() }.getOrDefault(0L)
            }
        return MinifluxClient.Result.Ok(merged)
    }

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
     *  sidecar), as feed rows — narrowed to one lane when the drawer's 🔖 folder is drilled into. */
    private fun loadLaterList() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            // Render what's on THIS device immediately — never block the list on network pulls
            // (14 sequential cross-device fetches left the Later list blank/spinning "not loading").
            val local = withContext(Dispatchers.IO) { gatherLaterList() }
            binding.progress.visibility = View.INVISIBLE
            allEntries = local
            // The Later list is the WHOLE backlog ("a finite feed to clear"), NOT filtered by the
            // timeline nav — nav-filtering it hid every item saved on a day other than the one the
            // navigator happened to sit on — and NOT filtered by the media lens either. The lens
            // asks a row to guess its own medium from its category and URL; the Later List's axis
            // is the LANE it was filed into, which is a fact, and which `gatherLaterList` has
            // already applied. Running both meant arriving here with 🎧 The Listen open and being
            // told the whole list was empty.
            adapter.submit(local)
            if (local.isEmpty()) showEmpty(laterEmptyText())
            else binding.emptyText.visibility = View.GONE

            // Then catch up on the other devices' filings in the background, and put them on screen
            // if any arrived. TWO SWEEPS, because they answer different questions: the recent one
            // asks "what changed on days I already have" (a round trip each, so it stays short),
            // and the listing one asks "which days exist at all that I have never seen" — which is
            // the only question that can help a device where he does no filing. See
            // IntakePageStore.pullMissingDays for why a second date window was not the answer.
            withContext(Dispatchers.IO) {
                val ctx = requireContext().applicationContext
                val store = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
                for (d in 0L..14L) runCatching {
                    store.pullLatest(ctx, java.time.LocalDate.now().minusDays(d))
                }
                // Single-flight with a floor: this method runs again on every reload and every
                // lane tap, and the sweep is a PROPFIND plus a GET per unseen day. A completed
                // sweep buys a few minutes of quiet rather than re-running because he tapped 📧.
                if (System.currentTimeMillis() - lastLaterBackfillMs > 5 * 60_000L) {
                    lastLaterBackfillMs = System.currentTimeMillis()
                    runCatching { store.pullMissingDays(ctx) }
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
                    adapter.submit(merged)
                }
                if (merged.isEmpty()) {
                    // STILL EMPTY AFTER BOTH SWEEPS — the one moment "can this device even reach
                    // the others" is worth a round trip, and the only moment it is. A probe on
                    // every load would put a request in front of a list that already had its
                    // answer. Re-shown unconditionally rather than only when `changed`, because
                    // an empty list that stays empty is the case where nothing changed and the
                    // explanation is the only thing that did.
                    withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                            .probe(requireContext().applicationContext)
                    }
                    if (isAdded) showEmpty(laterEmptyText())
                } else if (changed) {
                    binding.emptyText.visibility = View.GONE
                }
            }
        }
    }

    /** When the Later List's listing sweep last ran — see [loadLaterList]. On the fragment rather
     *  than in the store because it is a UI-pacing decision, not a fact about the data. */
    private var lastLaterBackfillMs = 0L

    /**
     * The Later List's empty state, which has to say WHICH emptiness it means.
     *
     * "Nothing on your Later List yet" is true of the whole list and a lie about one lane — a man
     * who has filed four hundred links and drilled into 📧 Email would be told he has never filed
     * anything. So a lane names itself and points back at the list it belongs to, which is one tap
     * up in the same drawer.
     *
     * And there is a second emptiness underneath both of those, which was the whole reason the Go 6
     * showed nothing: this list's contents come from the intake sidecars, which are pulled across
     * WebDAV, and a device with no WebDAV configured can never see a link filed anywhere else. That
     * is not "you have not saved anything yet" — it is "this device is not part of the ledger yet",
     * and only one of those two sentences tells you what to do. [LedgerSidecarSync.explainEmpty]
     * appends it when it applies and stays silent when it doesn't, so a configured device with an
     * empty list still reads as an empty list rather than as a fault.
     */
    private fun laterEmptyText(): String {
        val own = laterLane?.let { lane ->
            val label = com.toolsboox.plugin.feeds.nw.LaterFeed.LANES
                .firstOrNull { it.first == lane }?.third ?: lane
            "Nothing filed under $label yet — the rest of your Later List is one row up in 🔖."
        } ?: getString(R.string.feeds_later_empty)
        return com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
            .explainEmpty(requireContext(), own)
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
            // Board NAMES ride the same cross-device sidecar the Later List does (PickingsStore's
            // per-day index), so this list inherits the same silence: an unconfigured device shows
            // no boards made anywhere else and blames it on you. Same sentence, same conditions.
            if (entries.isEmpty()) showEmpty(
                com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                    .explainEmpty(requireContext(), "No pickings boards with content yet.")
            )
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

    /* ---------------------------------------------------------------
     * Bluesky — the following-timeline, as one more source in this list
     * ------------------------------------------------------------- */

    /**
     * WHY THE TIMELINE LIVES HERE AND NOT ON A SCREEN OF ITS OWN.
     *
     * [com.toolsboox.plugin.calendar.ui.BlueskyFragment] is the CORRESPONDENCE surface: the replies
     * awaiting an answer and the ink pad he answers them on. It is a work queue — a short list you
     * empty. A timeline is the opposite: a long list you skim, page through with the volume keys,
     * star out of, search, and stop reading in the middle of. Everything that makes that bearable
     * on e-ink already exists in this fragment and in nothing else the app has — list → in-pane
     * reader, volume paging, tap zones, mark-read-on-scroll, the search field with its scopes, the
     * star that writes a ReadingEvent into the day JSON. Building a second list surface for Bluesky
     * would have been re-implementing all of it, worse, and then having two places where "mark as
     * read" means something slightly different.
     *
     * So Bluesky is a SOURCE, exactly as Pickings, Later and the Ask log are sources: its posts
     * become [FeedEntry] rows in the band of synthetic ids, and every behaviour of this screen
     * applies to them for free. Replying is the one thing this screen cannot do, and that is
     * precisely the thing BlueskyFragment already does — so the reader hands off to it rather than
     * growing a second composer.
     *
     * AVATARS ARE NOT SHOWN, and that is a decision rather than an omission. Every item carries an
     * avatar URL and it would be trivial to put it in the row's thumbnail slot. On a monochrome
     * panel a 76dp avatar is a dithered grey smudge that tells you nothing — faces are exactly the
     * kind of image e-ink renders worst — and it would evict the one image on the row that IS worth
     * its width: the post's own attached picture, which about a sixth of real posts have. Identity
     * is carried as text instead (display name and @handle on the meta line), which is legible at
     * every text tier and costs no network round trip per row.
     */
    private fun loadBlueskyFeed() {
        val ctx = requireContext()
        if (!com.toolsboox.plugin.calendar.nw.BlueskyReply.config(ctx).ready) {
            allEntries = emptyList()
            adapter.submit(emptyList())
            showEmpty(
                "The Bluesky bridge isn't configured yet.\n\n" +
                    "Open 🦋 Bluesky from the Ledger directory and paste the shared POSSE secret " +
                    "there — this list reads the same bridge, so setting it once covers both."
            )
            return
        }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val posts = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.BlueskyFeed.recent(ctx, 200)
            }
            loading = false
            binding.progress.visibility = View.INVISIBLE
            if (!isAdded) return@launch
            // Rebuilt from scratch: ids are positional, so a shorter timeline would otherwise leave
            // the tail of the previous load's mapping pointing at posts no longer on screen.
            bskyUriById.clear()
            val entries = posts.mapIndexed { i, p -> blueskyEntry(p, BSKY_ID_BASE - i) }
            allEntries = entries
            adapter.submit(entries)
            if (entries.isEmpty()) showEmpty("Nothing in the timeline mirror yet. ↻ to check again.")
            else binding.emptyText.visibility = View.GONE
            renderDirectory()
        }
    }

    /**
     * One mirrored post as a feed row.
     *
     * The mapping is doing real work in three places. A skeet has NO TITLE — it is a paragraph —
     * so the first line stands in as one and the whole text is the body; without that the row would
     * show a blank headline over a blurb, which is how every "posts as articles" list looks wrong.
     * Its `category` is deliberately left null so the meta line falls back to the feed title and
     * shows the handle rather than the word "bluesky". And the attached images go into the content
     * as real `<img>` tags, which is what makes [FeedEntry.imageUrl] — and therefore the row
     * thumbnail — the post's own picture.
     */
    private fun blueskyEntry(
        post: com.toolsboox.plugin.calendar.nw.BlueskyFeed.Item, id: Long
    ): FeedEntry {
        bskyUriById[id] = post.uri
        val text = post.text.trim()
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        // A post with no words at all is a picture post; saying so beats an empty row.
        val head = firstLine.ifBlank { if (post.images.isNotEmpty()) "(image)" else "(no text)" }
        // Most of a real timeline is replies (about three in five of his), and a reply read out of
        // its thread is confusing unless it says it is one. The glyph is the same ↳ the app uses
        // for nested things elsewhere; the parent itself is one tap away on bsky.app.
        val title = (if (post.isReply) "↳  " else "") + head.take(110)
        return FeedEntry(
            id = id,
            title = title,
            feedTitle = "Bluesky · @" + post.handle.ifBlank { "unknown" },
            url = post.url,
            author = post.author.takeIf { it.isNotBlank() },
            content = blueskyHtml(post),
            publishedAt = blueskyIso(post.createdAt),
            starred = false,
            read = post.seen,
            category = null,
        )
    }

    /**
     * The post as the in-pane reader's body: an answer door, the text, then any pictures.
     *
     * Everything from the post is ESCAPED. The site already runs the text through `wp_kses_post`,
     * so nothing dangerous survives the mirror — but this is a stranger's words being written into
     * a WebView, kses allows a generous tag set, and the text is meant to be read as the plain
     * prose it is. Escaping means a post containing "<3" or "a > b" shows what was typed instead of
     * losing it to a parser, which is the failure the server's own comment worries about from the
     * other side.
     *
     * The answer door is a `ledger://` link rather than a button because the reader IS a WebView:
     * there is no view here to hang a button on, and [openEntry] already intercepts main-frame
     * navigations, so one extra branch there turns a link into an in-app action.
     */
    private fun blueskyHtml(post: com.toolsboox.plugin.calendar.nw.BlueskyFeed.Item): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val sb = StringBuilder()
        // Argument-less on purpose. The obvious shape — the AT URI in the link's path — means
        // percent-encoding `at://did:plc:…/app.bsky.feed.post/…` into a URL and trusting the
        // WebView not to normalise the escaped slashes on the way back out, which is exactly the
        // sort of thing that works on one WebView build and not the next. The reader already knows
        // which post is open, so the link only has to say "this one".
        sb.append("<p><a href=\"").append(BSKY_REPLY_SCHEME)
            .append("\"><b>✍&nbsp;&nbsp;Answer this on Bluesky</b></a></p>")
        if (post.isReply) {
            sb.append("<p style=\"color:#666;font-size:14px\">↳ a reply in a thread — hold the ")
                .append("row, or use ⧉, to open the whole thread on bsky.app</p>")
        }
        for (para in post.text.split(Regex("\n{2,}"))) {
            if (para.isBlank()) continue
            sb.append("<p>").append(esc(para.trim()).replace("\n", "<br />")).append("</p>")
        }
        for (image in post.images) sb.append("<p><img src=\"").append(esc(image)).append("\"/></p>")
        return sb.toString()
    }

    /**
     * The site hands out `created_at` site-local with no offset ("2026-07-29 11:47:03") — it did
     * the timezone maths so no client has to. The row's date formatter wants an offset date-time,
     * so this attaches the DEVICE's zone, which is the same choice
     * [com.toolsboox.plugin.calendar.nw.WPPublish.parseWpDate] makes for WordPress's identically
     * shaped dates: when the zones agree (the normal case, this being his own site) the wall clock
     * is preserved exactly, and when they do not, a timeline is still ordered correctly because the
     * server sorted it before we ever saw it.
     */
    private fun blueskyIso(local: String): String = runCatching {
        java.time.LocalDateTime
            .parse(local.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime()
            .toString()
    }.getOrElse { local }

    /** Saved Ask-my-Ledger answers, surfaced as a local feed. */
    private fun loadAskLog() {
        val entries = com.toolsboox.plugin.feeds.nw.AskFeedStore.list(requireContext())
        allEntries = entries
        adapter.submit(applyKind(entries))
        if (entries.isEmpty()) showEmpty("No saved answers yet — save one from Ask my Ledger.")
        else binding.emptyText.visibility = View.GONE
    }

    /**
     * The Later List's rows. The synthesis itself moved to
     * [com.toolsboox.plugin.feeds.nw.LaterFeed] when the list grew a star and a delete: those two
     * verbs need a way back from a row to the LINE it was read out of, and a source that has to
     * remember something is a thing with a name rather than a private helper on a screen. This
     * stays as the fragment's one door onto it — the lane narrowing rides along, so every caller
     * gets the same list the drawer is currently pointed at.
     */
    private fun gatherLaterList(): List<FeedEntry> =
        com.toolsboox.plugin.feeds.nw.LaterFeed.rows(requireContext(), laterLane)

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
                // The Bluesky reader's "Answer this" door. Handing off to the surface that already
                // owns replying rather than growing a second composer here — see [loadBlueskyFeed].
                if (url.startsWith(BSKY_REPLY_SCHEME)) {
                    openBlueskyComposer(bskyUriById[currentArticle?.id] ?: "")
                    return true
                }
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

    /**
     * Hand a Bluesky post to the surface that can actually answer it.
     *
     * The composer is not reimplemented here and must not be: [BlueskyFragment] enforces the one
     * rule the whole reply path exists for — handwriting is recognised into an editable field he
     * reads and corrects before anything is sent — and a second composer would be a second place
     * for that rule to be forgotten. The AT URI travels through [BlueskyTarget] the same way a
     * tapped entry travels through [FeedSelection]: a one-shot handoff rather than nav args, which
     * is this app's existing idiom for exactly this.
     *
     * Read-only posts are the honest limit here. The site's reply queue answers COMMENTS on his own
     * syndicated posts — a reply threaded under something of his — and there is no route that posts
     * to an arbitrary skeet. So BlueskyFragment opens the pad when the queue holds this post and
     * says plainly that it cannot when it does not, rather than offering a Send that would 404.
     */
    private fun openBlueskyComposer(uri: String) {
        if (uri.isBlank()) { showMessage("No Bluesky reference on that post", binding.root); return }
        com.toolsboox.plugin.calendar.ui.BlueskyTarget.pendingUri = uri
        runCatching { findNavController().navigate(R.id.action_to_bluesky) }
            .onFailure { showMessage("Couldn't open Bluesky", binding.root) }
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
            // The skin escapes everything feed-supplied, but the document still must not adopt the
            // entry's (feed-supplied) url as its origin — the sanitizer's sentinel is a real https
            // origin, which is all the tap-to-play embed needs. See ArticleSanitizer.BASE_URL.
            binding.articleWeb.loadDataWithBaseURL(
                com.toolsboox.plugin.feeds.ot.ArticleSanitizer.BASE_URL,
                buildYouTubeHtml(entry, ytId), "text/html", "UTF-8", null)
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
        // (and other) embeds fail with "error 150" (embedding-not-allowed for the opaque origin). But
        // NOT the entry's own url: that field is feed-supplied, and this WebView's cookie jar holds
        // real logged-in sessions (see SiteWebFragment). Any real https origin satisfies YouTube, so
        // the article loads on the sanitizer's sentinel — see ArticleSanitizer.BASE_URL.
        binding.articleWeb.loadDataWithBaseURL(
            com.toolsboox.plugin.feeds.ot.ArticleSanitizer.BASE_URL,
            buildArticleHtml(entry, body), "text/html", "UTF-8", null)
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
        // The rail wears the same mode: list dress or article dress, and the star's fill follows
        // the open article — this call is the rail's whole "visibility dance".
        rebuildActionRail("feeds")
    }

    /**
     * The rail's surface actions, by mode — the retired pills' buttons unpacked into icons.
     * Every action drives the hidden pill button where one exists, so the single wiring the
     * pill had keeps working for anything else that performClicks it (the day-page rule).
     */
    private fun feedsRailActions(): List<com.toolsboox.ot.TuckPanel.Item> {
        val base = listOf(
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_feed, "Feeds drawer") {
                binding.ledgerButton.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today") {
                binding.feedsGoto.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up, "Page up") {
                binding.feedsPageUp.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down, "Page down") {
                binding.feedsPageDown.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_refresh, getString(R.string.feeds_refresh)) {
                binding.refreshButton.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_wrench, getString(R.string.feeds_settings)) {
                binding.settingsButton.performClick()
            }
        )
        if (currentArticle == null) return base
        // Reading dress: the article actions join, prev/next article included — the rail has
        // the height the pill never had, so the shrunk-drawer compromise isn't needed.
        return base + listOf(
            com.toolsboox.ot.TuckPanel.Item(
                if (currentArticle?.starred == true) R.drawable.ic_starred else R.drawable.ic_star,
                "Star") { binding.feedsStar.performClick() },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_pencil, "Note") {
                binding.feedsNote.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_reader_view, "Reader view") {
                binding.feedsParsed.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_speaker, "Read aloud") {
                binding.feedsTts.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_go_later, getString(R.string.feeds_add_later)) {
                binding.feedsLater.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up2, getString(R.string.feeds_prev_article)) {
                binding.feedsDwPrev.performClick()
            },
            com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down2, getString(R.string.feeds_next_article)) {
                binding.feedsDwNext.performClick()
            }
        )
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
        // The capture identity rides in with the play call: it is what grows the ★ and 📝 on the
        // transport (modal and drawer card both), so a playing podcast can be starred/annotated
        // from the player itself — the Listen flow never opens the article pane where the
        // reader's own star lives. The entry URL is the episode page; a synthetic row (downloaded
        // audio aged off the server) may have none, so the enclosure URL stands in.
        com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
            requireContext(), e.title, e.feedTitle.ifBlank { null }, e.imageUrl, src,
            capture = com.toolsboox.ui.plugin.LedgerPlayer.Capture(
                title = e.title, feedTitle = e.feedTitle,
                url = e.url.ifBlank { url }, imageUrl = e.imageUrl, excerpt = e.blurb
            )
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
            com.toolsboox.plugin.feeds.nw.LaterMedia.download(requireContext(), media, e)
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
        // Somebody else's HTML: allowlist-clean it (scripts/on*/javascript: gone, relative media
        // absolutised against the entry url) BEFORE the YouTube rewrite — the sanitizer lets the
        // YouTube embed iframes through, and cleaning first keeps the rewrite's own inline-styled
        // thumbnail card intact. Same pass as the standalone Articles reader.
        val safeBody = rewriteYouTubeEmbeds(com.toolsboox.plugin.feeds.ot.ArticleSanitizer.clean(content, e.url))
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
            $safeBody
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
        // Quotes escaped too — [thumb] below is feed-supplied and lands inside a src="…" attribute.
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
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
              <img src="${esc(thumb)}"/><div class="cue"><span>▶</span></div>
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
            if (isAdded) {
                // The live feed (day granularity, anchored on today) is UNFILTERED — every
                // unread item, yesterday's included (see filterByNavDay's escape). The strip
                // used to dress today as the focal slot anyway, promising a day filter it
                // wasn't applying — "the almanac says today's date but it's pulling up
                // yesterday's feeds". The strip already has the honest idiom for this:
                // anchored-but-not-filtered draws NO focal slot. Claim a focus only when a
                // filter is really on.
                navBar?.setGranularity(
                    if (navGranularity == "day" && navAnchor == java.time.LocalDate.now()) null
                    else navGranularity
                )
                navBar?.render(day, pat)
            }
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
        // A view switch ends any Later selection in progress — the checkboxes belong to the list
        // they were ticked on, and a bar promising "Delete (3)" over a different corpus is a bar
        // that deletes rows nobody is looking at. (No-op when not selecting.)
        exitLaterSelection()
        // Picking a lens while browsing a PAST window stays in that window (read timeline),
        // instead of snapping to the live unread view — the date and the lens compose.
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        mode = if (newMode == "feed" && !liveToday) "read" else newMode
        kindFilter = kind
        // Leaving the Later List drops its lane with it: a lane is a narrowing of THAT corpus and
        // means nothing over any other, and a stale one would silently narrow the list the next
        // time he came back — the drawer saying "Later List" while showing only 📧 Email.
        if (newMode != "later") laterLane = null
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
        // Guarded: a name and search terms being typed are work — a stray touch outside must
        // not throw them away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
            .create())
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
                },
                // The inline Now Playing transport in the drawer, off unless asked for — the ▦
                // hub's "▶️ Now Playing" row reaches the same controls from every surface, so the
                // card is a convenience, not the only door. Re-render immediately: a display
                // toggle whose effect waits for the next playback is a toggle that looks broken.
                ((if (nowPlayingCardOn()) "☑" else "☐") + "  Now Playing card in drawer") to {
                    prefs().edit().putBoolean("feeds_now_playing_card", !nowPlayingCardOn()).apply()
                    renderDirectory()
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

    /**
     * A HOLD on a folder row in the left-hand directory — a lens, a category, or a single feed.
     *
     * "Clear" beside the VIEWS chips acts on the list AS DISPLAYED, which means emptying one
     * category meant first drilling into it. A folder is a thing in its own right, so it gets its
     * own gesture: hold it, and the option is right there for that folder and nothing else. The
     * menu itself is the deliberate second step (a hold on e-ink can be accidental), so choosing
     * the row does the thing — no second confirm, and an Undo that reaches both sides.
     */
    private fun showFolderHoldMenu(label: String, entries: List<FeedEntry>) {
        val unread = entries.filterNot { it.read || FeedReadState.isRead(it.id) }
        val items = arrayOf(
            if (unread.isEmpty()) "✓  Nothing unread here" else "✓  Mark all as read  ·  ${unread.size}"
        )
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(label)
            .setItems(items) { _, _ -> if (unread.isNotEmpty()) markFolderRead(label, unread) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Mark every unread entry of one folder read — locally first (so the rows grey out and the
     * drawer's counts drop at once, and so synthetic rows with no server side still clear), then
     * in ONE batched Miniflux call for the entries that really live there. Undoable.
     */
    private fun markFolderRead(label: String, unread: List<FeedEntry>) {
        val ids = unread.map { it.id }
        val idSet = ids.toSet()
        // Only real Miniflux entries have positive ids — Later / Pickings / local-feed rows are
        // synthetic and negative, and a small synthetic id would address someone ELSE's entry.
        val serverIds = ids.filter { it > 0 }
        ids.forEach { FeedReadState.mark(it) }
        allEntries = allEntries.map { if (it.id in idSet) it.copy(read = true) else it }
        adapter.submit(adapter.current().map { if (it.id in idSet) it.copy(read = true) else it })
        renderDirectory()

        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val onServer = serverIds.isNotEmpty() && url.isNotBlank() && token.isNotBlank()
        lifecycleScope.launch {
            if (onServer) {
                val res = withContext(Dispatchers.IO) { miniflux.setStatus(url, token, serverIds, "read") }
                if (res is MinifluxClient.Result.Err) {
                    android.widget.Toast.makeText(
                        requireContext(), "⚠ Mark-read didn't reach Miniflux — ${res.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, "Marked ${ids.size} read in “$label”",
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction("Undo") {
                ids.forEach { FeedReadState.unmark(it) }
                lifecycleScope.launch {
                    if (onServer) withContext(Dispatchers.IO) { miniflux.setStatus(url, token, serverIds, "unread") }
                    refresh()
                }
            }.show()
            // Reload so an Unread view drops them and the counts come back from the source.
            refresh()
        }
    }

    /** How opening/scrolling marks entries read: "open" (default) · "scroll" · "off". */
    private fun markReadMode(): String = prefs().getString("feeds_mark_read", "open") ?: "open"

    /** Whether the drawer seats the inline Now Playing card while something plays. Default OFF:
     *  the hub's "▶️ Now Playing" row already reaches the transport from every surface, and the
     *  card is a permanent block above the views for whoever didn't ask for it. */
    private fun nowPlayingCardOn(): Boolean = prefs().getBoolean("feeds_now_playing_card", false)

    /** Long-press a row → mark every article ABOVE it read (the common reader gesture; parity with
     *  the iPad's "Mark above as read" context item). Confirmed first, because on e-ink a long-press
     *  can be accidental and this is a bulk change. Marks the same "above" set the scroll-mode
     *  handler does — everything before this row in the current list. */
    private fun markAboveAsRead(entry: FeedEntry) {
        // A hold on a Later List row means something else entirely. "Mark above as read" is
        // meaningless here — nothing on disk records whether a filed link has been read, so the
        // gesture would grey a screenful of rows until the next load and no further. The list's own
        // two verbs take the gesture instead.
        if (com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(entry.id)) { showLaterRowMenu(entry); return }
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

    /**
     * A HOLD on a Later List row — the verbs the list is FOR: keep this one, or be done with it,
     * one at a time or by the screenful (Select… and Clear-above are "faster than one at a time",
     * as asked; both drain through [removeLaterRows], the same path as the single delete).
     *
     * Michael: "I would like to be able to star items from the later list tho and delete them."
     * Both shipped hidden, and the reasoning was sound as far as it went — a later-list entry is a
     * "title — url" LINE inside an intake day file, there is no server entry behind it, and a
     * gesture that writes nowhere is worse than a missing one. The answer was to build the places
     * (LaterStars, IntakePageStore.unfileLink) rather than to accept the absence.
     *
     * DELETE SITS LAST, and that is the iPad's "destructive before the star so a full swipe can't
     * reach it" translated into this fork's idiom. There are no swipes on a Boox row; the gestures
     * are a tap (open), a hold (this), and the star glyph. So the accident this menu has to defend
     * against is a different one: a hold on e-ink can be accidental — the panel is slow enough that
     * a tap you thought didn't register becomes a long press — and a menu that opens under a finger
     * already coming down gets its FIRST row chosen. So the first row is the harmless one, and
     * "I flicked it and it vanished" stays a thing this list cannot do to something you saved on
     * purpose. Same reason [showFolderHoldMenu] treats the menu itself as the deliberate second
     * step and asks for no further confirmation: choosing a row does the thing.
     */
    private fun showLaterRowMenu(entry: FeedEntry) {
        // Clearing-above only exists where there IS an above; on the top row the menu doesn't
        // offer a verb that can only apologize.
        val aboveCount = adapter.current().indexOfFirst { it.id == entry.id }
        val items = mutableListOf<Pair<String, () -> Unit>>(
            (if (entry.starred) "☆  Unstar" else "★  Star") to { toggleStar(entry) },
            // Select… puts the list in checkbox mode with THIS row already ticked — the row you
            // held is the row you meant. Harmless on its own: nothing is removed until the bar's
            // Delete, so it can sit second without weakening the first-row defence above.
            "☑  Select rows…" to { enterLaterSelection(entry.id) },
        )
        if (aboveCount > 0) items.add(
            // The feeds list's "mark above as read" gesture, translated to the list where "done
            // with it" means REMOVE (a filed link has no read flag) — and confirmed with its
            // count, same as over there, because it's one hold acting on a screenful.
            "⇞  Clear all above…" to { clearLaterAbove(entry) })
        items.add(
            "🗑  Remove from Later List" to { removeLaterRows(listOf(entry)) })
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(entry.title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Clear above" on a Later List row: remove every row ABOVE this one in the CURRENT view —
     *  the lane/search narrowing included, so what clears is exactly what is on screen above your
     *  finger. Confirmed with the count first: it is one hold acting on a screenful, the same
     *  reasoning [markAboveAsRead] gives, and unlike a mark-read there is no Undo on the other
     *  side of this one. */
    private fun clearLaterAbove(entry: FeedEntry) {
        val list = adapter.current()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx <= 0) return
        val above = list.take(idx).filter { com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(it.id) }
        if (above.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Clear ${above.size} above this?")
            .setPositiveButton("Clear") { _, _ -> removeLaterRows(above) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Remove Later rows — one or many — through the ONE removal path the single delete has always
     * used: [com.toolsboox.plugin.feeds.nw.LaterFeed.remove] → `IntakePageStore.unfileLink`, which
     * writes the LaterRemovals tombstone per link so the cross-device merge can't resurrect a
     * bulk-cleared screen any more than a single delete. A private bulk writer here would be a
     * second opinion about what "removed" means.
     *
     * Rows whose line had already gone (reloaded list, another device got there first) stay
     * silent, as the single delete always has — the count reported is what actually came off.
     */
    private fun removeLaterRows(rows: List<FeedEntry>) {
        if (rows.isEmpty()) return
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val removedIds = withContext(Dispatchers.IO) {
                rows.mapNotNull { r ->
                    if (com.toolsboox.plugin.feeds.nw.LaterFeed.remove(appCtx, r.id)) r.id else null
                }.toSet()
            }
            if (!isAdded || removedIds.isEmpty()) return@launch
            // Drop them from the list in place instead of reloading: loadLaterList re-walks
            // 120 day files and would flash the whole panel on e-ink to communicate the
            // disappearance of rows it already knows are gone.
            allEntries = allEntries.filterNot { it.id in removedIds }
            val shown = adapter.current().filterNot { it.id in removedIds }
            adapter.submit(shown)
            if (shown.isEmpty()) showEmpty(laterEmptyText())
            showMessage(
                if (removedIds.size == 1) "Removed from Later List"
                else "Removed ${removedIds.size} from Later List",
                binding.root
            )
        }
    }

    /** Enter Later List selection mode: checkboxes on every row, the held row pre-ticked, and the
     *  Delete/Cancel bar pinned above the list. Delete runs [removeLaterRows] on the ticked set —
     *  no further confirm: ticking boxes one by one IS the deliberate act, the same reasoning
     *  [showLaterRowMenu] gives for its own rows. */
    private fun enterLaterSelection(seedId: Long) {
        adapter.onSelectionChanged = { n ->
            binding.laterSelectCount.text = if (n == 1) "1 selected" else "$n selected"
            binding.laterSelectDelete.isEnabled = n > 0
        }
        adapter.beginSelection(seedId)
        binding.laterSelectBar.visibility = View.VISIBLE
        binding.laterSelectDivider.visibility = View.VISIBLE
        binding.laterSelectDelete.setOnClickListener {
            val chosen = adapter.current().filter { it.id in adapter.selectedIds }
            exitLaterSelection()
            removeLaterRows(chosen)
        }
        binding.laterSelectCancel.setOnClickListener { exitLaterSelection() }
    }

    private fun exitLaterSelection() {
        adapter.endSelection()
        binding.laterSelectBar.visibility = View.GONE
        binding.laterSelectDivider.visibility = View.GONE
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
        // A Bluesky row has its own "read" on the far side — the site's `seen` flag, which is what
        // stops the same morning's posts coming back on every refresh and is shared with the iPad.
        // Collected rather than sent: see [pendingBskySeen].
        // `!entry.read` because the site already knows about a post it handed us as seen — the
        // session read-tracker above is per-launch, so without this every re-opened post would put
        // its URI back in the batch and buy a request that could only be a no-op.
        bskyUriById[entry.id]?.takeIf { !entry.read }?.let { uri ->
            val ready = synchronized(pendingBskySeen) {
                pendingBskySeen.add(uri); pendingBskySeen.size >= BSKY_SEEN_BATCH
            }
            if (ready) flushBlueskySeen()
        }
    }

    /**
     * Send the accumulated "seen" batch.
     *
     * On [lifecycleScope] and the APPLICATION context: the scope survives onPause (it is cancelled
     * at DESTROY, not at PAUSE), which is what lets the leaving-the-screen flush actually complete,
     * and the app context is what keeps the request from holding the fragment's own.
     *
     * The URIs leave the pending set only after the site has accepted them. A flush that fails
     * therefore leaves the work to be redone on the next one rather than dropping it — dropping a
     * batch means those posts come back unread, and he reads the same morning twice.
     */
    private fun flushBlueskySeen() {
        val batch = synchronized(pendingBskySeen) { pendingBskySeen.toList() }
        if (batch.isEmpty()) return
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            if (com.toolsboox.plugin.calendar.nw.BlueskyFeed.markSeen(appCtx, batch)) {
                synchronized(pendingBskySeen) { pendingBskySeen.removeAll(batch.toSet()) }
            }
        }
    }

    // (The pill-orientation and list-gutter machinery retired with the pills: the rail is in
    // the layout flow, so the list is clear of it by construction — no reserved margins, no
    // measured clearance, nothing to keep in step.)

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
        // Guarded: typed hosts and secrets are work — a stray touch outside must not throw
        // them away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
            .create())
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
        fun row(
            label: String, bold: Boolean, selected: Boolean,
            onLongPress: (() -> Unit)? = null, onClick: () -> Unit
        ) {
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
                if (onLongPress != null) setOnLongClickListener { onLongPress(); true }
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
        //
        // Behind a wrench toggle now, OFF by default — Michael: the strip "becomes redundant/
        // should be hideable since we have the now-playing in the sidebar available pretty much
        // everywhere". The hub's "▶️ Now Playing" row (ledgerDirectoryFolders, reachable from this
        // page's own ▦ button) carries the full transport modal whenever anything is playing, so
        // hiding the card loses no control — only the permanent block above the views. The fold
        // survives for whoever turns the card back on.
        if (com.toolsboox.ui.plugin.LedgerPlayer.isActive && nowPlayingCardOn()) {
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
            // Stacked, not in a row: three labeled radios never fit the slim pane's width in
            // portrait — the third clipped mid-word and the 10sp labels were squint-reading on
            // e-ink (Michael: "All / This feed / This category are not legible in portrait").
            // Three short rows always fit, at a size that reads at arm's length.
            orientation = android.widget.RadioGroup.VERTICAL
            fun radio(lbl: String, sc: String) = android.widget.RadioButton(ctx).apply {
                text = lbl; textSize = 13f; setPadding(dpPx(1), 0, dpPx(6), 0); isChecked = searchScope == sc
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
        // TWO CAPTIONS, because there were two kinds of chip under one heading. The four above
        // really are view axes — states that COMPOSE with wherever you are browsing. Clear WRITES
        // to the list, and Intake puts something INTO one. A control that acts, sitting under a
        // heading that says VIEWS, is the same category confusion the tool pill's star was: a
        // filter read as "star all of these". So the acting ones are named as what they are rather
        // than trusted to look different.
        //
        // 🔖 Later left this block. It was never a view either — it swapped the corpus — and
        // READING the Later List now happens at the 🔖 Later List folder down in FEEDS, with the
        // rest of the feeds, which is what Michael asked for. What stays here is the other half of
        // that list: the place you put something in. He kept the button where his thumb already
        // goes ("the current later button can become a later list intake spot"); it just stopped
        // pretending to be a lens.
        section("ACTIONS")
        // One chip to a row, as the Later and Clear chips already were: paired, "Intake a link…"
        // ellipsizes to "Intake a l…" in a Palma-width drawer, and a control whose name is cut off
        // is a control you have to press to find out about.
        chipPair(stateChip("📋 Intake a link…", false) { intakeLinkFromClipboard() })
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
        // "later" is NOT one of them any more. The Later List has its own axis — the four lanes
        // under 🔖, which are where a link was FILED rather than what a row guesses it is — and
        // letting the media lens compose on top meant tapping 📖 The Read while standing in the
        // Later List silently intersected the two and could empty the list outright. Tapping a lens
        // from here now means what it says: go and read that lens.
        val composable = mode in setOf("feed", "read", "both", "edition", "stars")
        fun lensRow(emoji: String, title: String, lens: String) {
            val open = lensOpen == lens
            row(
                "$emoji  ${if (open) "▾" else "▸"}  $title", true, kindFilter == lens,
                onLongPress = { showFolderHoldMenu(title, allEntries.filter { it.kind == lens }) }
            ) {
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
            fun subRow(
                label: String, indent: Int, header: Boolean, selected: Boolean,
                onLongPress: (() -> Unit)? = null, onClick: () -> Unit
            ) {
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
                    // A HOLD on a folder is the folder's own gesture: mark everything in it read.
                    if (onLongPress != null) setOnLongClickListener { onLongPress(); true }
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
                subRow(
                    if (unread > 0) "$caret  $unread · $c" else "$caret  $c", 24, true, false,
                    onLongPress = { showFolderHoldMenu(c, inCat) }
                ) {
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
                    subRow(
                        if (fu > 0) "$fu · $f" else f, 44, false, slimFeedFilter == f,
                        onLongPress = { showFolderHoldMenu(f, inFeed) }
                    ) {
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
        /**
         * "🔖 Later List" — the links you filed, as a feed among the feeds.
         *
         * Michael: "I'd love later list to appear in feeds like a feed instead of this, instead
         * under '🎧 The Listen' like '🔖 Later List'." So it sits directly under The Listen, at the
         * end of the media block and above everything that isn't a lens — not down in MORE with
         * Pickings and Bluesky, which are sources you look AT; this is the one you keep.
         *
         * It borrows the media lenses' SHAPE — a folder that opens a list and drops its children
         * out beneath — but its children are the intake LANES rather than Miniflux categories. That
         * grouping is the one thing the list would have lost by being flat, and the drawer already
         * speaks the axis, so it comes across as folders rather than as headers inside a list. It
         * costs nothing to serve: a lane is a filter over rows synthesized from the same store, not
         * four more sources.
         *
         * Same dual gesture as a lens: opening it shows the WHOLE Later List and drops its lanes
         * out; folding it goes back to All, because unlike a lens the folder is not a filter over
         * the current list — it IS the list, so there is nothing left to be standing in once it
         * closes.
         */
        fun laterFolder() {
            val open = a11y.getBoolean("feeds_later_open", false)
            row("🔖  ${if (open) "▾" else "▸"}  Later List", true, mode == "later" && laterLane == null) {
                if (open) {
                    a11y.edit().putBoolean("feeds_later_open", false).apply()
                    laterLane = null
                    renderDirectory()
                    if (mode == "later") switchTo("both", null)
                } else {
                    a11y.edit().putBoolean("feeds_later_open", true).apply()
                    laterLane = null
                    renderDirectory()
                    switchTo("later", null)
                }
            }
            if (!open) return
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(dpPx(1), com.toolsboox.ot.LedgerTheme.accent(ctx))
                    cornerRadius = dpPx(8).toFloat()
                }
                setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(4))
            }
            for ((lane, label, _) in com.toolsboox.plugin.feeds.nw.LaterFeed.LANES) {
                val selected = mode == "later" && laterLane == lane
                box.addView(android.widget.TextView(ctx).apply {
                    text = label
                    textSize = 12.5f
                    setTextColor(0xFF000000.toInt())
                    if (selected) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    }
                    setPadding(dpPx(18), dpPx(7), dpPx(6), dpPx(7))
                    maxLines = 1
                    isClickable = true
                    setOnClickListener {
                        if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
                        laterLane = lane
                        renderDirectory()
                        switchTo("later", null)
                    }
                })
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
        // 📧 Emails — mail as a sibling of the media lenses (Michael: "email can be put right
        // into feeds, just like The Listen — and then the drop down is the accounts"). First
        // cut of the mail/feeds unification: the folder rides the same single-open lens state,
        // its dropdown lists the configured accounts, and a tap lands on the Mail surface with
        // that account already the filter (the filter is a pref, the same channel the inbox's
        // own account rows use — the nav graph's doors take no arguments). The full merge —
        // messages as rows IN this list — is the next step; this makes mail a room off the
        // same hallway now.
        run {
            val accounts = com.toolsboox.plugin.mail.MailAccountStore.all(ctx)
            val open = lensOpen == "email"
            fun goToMail(accountId: String?) {
                requireContext().getSharedPreferences("ledger_mail_inbox", 0).edit()
                    .putString("account_filter", accountId ?: "").apply()
                androidx.navigation.fragment.NavHostFragment.findNavController(this@FeedsFragment)
                    .navigate(R.id.action_to_mail_inbox)
            }
            row("📧  ${if (open) "▾" else "▸"}  Emails", true, false) {
                if (open) {
                    a11y.edit().putString("feeds_lens_open", "").apply()
                    renderDirectory()
                } else {
                    a11y.edit().putString("feeds_lens_open", "email").apply()
                    renderDirectory()
                }
            }
            if (open) {
                val box = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke(dpPx(1), com.toolsboox.ot.LedgerTheme.accent(ctx))
                        cornerRadius = dpPx(8).toFloat()
                    }
                    setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(4))
                }
                fun mailRow(label: String, onClick: () -> Unit) {
                    box.addView(android.widget.TextView(ctx).apply {
                        text = label; textSize = 12.5f; setTextColor(0xFF000000.toInt())
                        setPadding(dpPx(18), dpPx(7), dpPx(6), dpPx(7))
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        isClickable = true
                        setOnClickListener { onClick() }
                    })
                }
                mailRow("✉  All accounts") { goToMail(null) }
                for (a in accounts) mailRow("·  ${a.display}") { goToMail(a.id) }
                if (accounts.isEmpty()) mailRow("(no accounts yet — set one up in Mail)") { goToMail(null) }
                container.addView(box, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dpPx(6), dpPx(1), dpPx(6), dpPx(4)) })
            }
        }
        laterFolder()
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
        // The timeline sits with the other non-Miniflux sources rather than among the media lenses:
        // it is a source, not a medium, and it composes with none of the view chips (there is no
        // "starred Bluesky" on the server side). Same 🦋 the Ledger directory's Bluesky row wears,
        // so the two doors to the same bridge are recognisably one thing.
        row("🦋  Bluesky", true, mode == "bsky") { switchTo("bsky", null) }
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
     * FILE WHATEVER LINK IS ON THE CLIPBOARD, then show the Later List so the row you just made is
     * the confirmation.
     *
     * This is what the 🔖 Later chip became. Michael: "the current later button can become a later
     * list intake spot." Reading the list moved down to the 🔖 Later List folder with the rest of
     * the feeds; the button stayed where his thumb already goes and changed jobs, from a door into
     * the list to the place you put something IN it.
     *
     * It PASTES rather than asking, because a link you want to keep is nearly always the thing you
     * just copied, and the alternative — a text field in the drawer — is a keyboard and two taps on
     * an e-ink panel to do what the clipboard already knows. No modal confirmation either: the
     * thing that proves it worked is the list it landed in, one row down in this same drawer, with
     * the item at the top of it.
     *
     * The lane is inferred from the host by [ShareTextParser.inferKind] — the same function the
     * share-to-file flow uses, so a YouTube link files under 📺 The Watch whether it arrives by
     * share sheet or by hand, and filing goes through [IntakePageStore.fileLink], the same write
     * path the share target and both readers take. So a link filed here is dressed with its og:
     * face, cached for offline reading, published to the personal RSS feed and merged across
     * devices exactly like every other one. Nothing about it is a second, lesser kind of filing.
     */
    private fun intakeLinkFromClipboard() {
        val ctx = requireContext()
        val clip = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager)?.primaryClip
        val text = (0 until (clip?.itemCount ?: 0))
            .mapNotNull { clip?.getItemAt(it)?.coerceToText(ctx)?.toString() }
            .joinToString("\n")
        val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(text).firstOrNull()
        if (url == null) {
            // The honest report, not a shrug: he pressed a button and it has to say what happened.
            showMessage("Nothing on the clipboard to file — copy a link first.", binding.root)
            return
        }
        val kind = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.inferKind(url)
        val lane = com.toolsboox.plugin.feeds.nw.LaterFeed.LANES.firstOrNull { it.first == kind }?.third ?: "The Read"
        // fileLink does network work (the og: fetch, the article cache, the publish) on its own
        // daemon threads, but the day-file read/merge/write in front of them is disk — off main.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                    ctx.applicationContext, java.time.LocalDate.now(), kind, url, null)
            }
            if (!isAdded) return@launch
            val host = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: "link"
            showMessage("Filed to $lane · $host", binding.root)
            // Open the list ON the lane it landed in, and drop the folder open so the lane it chose
            // is visible rather than merely asserted by a toast that is already fading.
            requireContext().getSharedPreferences("ledger_a11y", 0)
                .edit().putBoolean("feeds_later_open", true).apply()
            laterLane = kind
            renderDirectory()
            switchTo("later", null)
        }
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
        if (com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(entry.id)) {
            // A Later List row stars into its OWN sidecar, and deliberately skips the ceremony
            // every other star performs. There is no Miniflux entry to POST to and never will be —
            // that much it shares with the Pickings rows below — but the important half is what it
            // does NOT do: no logStar, so no Log line, no Star Sort gram, no webhook. A filed link
            // is already IN the ledger, because filing it is what put it there and minted its gram.
            // Starring it again is triage inside a list you already keep, not a new act of keeping,
            // and running the ceremony would drop a DUPLICATE gram on Star Sort for something
            // already sitting on Star Sort. (See LaterFeed.setStar, which is the only writer.)
            com.toolsboox.plugin.feeds.nw.LaterFeed.setStar(requireContext(), entry.url, !entry.starred)
            showMessage(if (entry.starred) R.string.feeds_unstarred else R.string.feeds_starred)
            reflectStar(entry, !entry.starred)
            return
        }
        if (entry.id <= 0) {
            // Pickings / Bluesky / Ask-log rows have synthetic negative ids and no Miniflux entry:
            // star into the Ledger corpus only — never send a synthetic id to the server.
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
            // …and on the rail, which wears the same star.
            rebuildActionRail("feeds")
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
                thumb = entry.imageUrl?.let { FeedThumbCache.get(it) },
                excerpt = entry.blurb
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
        // (The Later List's band is -1.2e9 and the 99 million below it — it owns its ids now that
        // it hashes them for stability rather than counting down. See LaterFeed.ID_BASE.)
        private const val BSKY_ID_BASE = -1_300_000_000L

        /** How many read posts are worth a request. Small enough that a normal reading session
         *  flushes at least once before he leaves; large enough that skimming a screenful is one
         *  round trip and not twenty. */
        private const val BSKY_SEEN_BATCH = 20

        /** The in-reader "answer this" link. Not a real scheme anyone registers — it exists only to
         *  be recognised by [openEntry]'s navigation interceptor and turned into a fragment jump.
         *  Which post it means is the one the reader has open, so it carries no argument. */
        const val BSKY_REPLY_SCHEME = "ledger://bsky-reply"
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
    /** Optional Later List LANE ("read"/"watch"/"listen"/"educate") to open narrowed to. Its own
     *  channel and not [kind]: a lane is where a link was filed, a kind is what a row looks like. */
    var laterLane: String? = null

    /** Set by the article's ☰ so the feed list pops the RSS directory open on return. */
    var openDirectory: Boolean = false

    /** Consume the pending mode/kind (one-shot) after the fragment applies it. */
    fun consume(): Pair<String, String?> {
        val m = mode ?: "feed"; val k = kind
        mode = null; kind = null
        return m to k
    }

    /** Consume the pending Later List lane (one-shot), alongside [consume]. */
    fun consumeLaterLane(): String? {
        val l = laterLane; laterLane = null; return l
    }
}
