package com.toolsboox.plugin.calendar.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.DateFormat
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.da.Attachment
import com.toolsboox.databinding.FragmentReadingLogBinding
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.util.LedgerExport
import com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Date
import javax.inject.Inject

/**
 * Notes & Annotations — the dedicated log of book highlights, starred articles and AV grams
 * (`CalendarDay.readingEvents` + `avGrams`), newest first, over a widening range and
 * filterable by origin (Book / Read / Watch / Listen / Picking / AV). Mirrors the iPad
 * `ReadingTimelineView`; the day page's Notes strip only shows today's.
 */
@AndroidEntryPoint
class ReadingLogFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var chatService: com.toolsboox.plugin.chat.nw.LedgerChatService

    @Inject
    lateinit var miniflux: com.toolsboox.plugin.feeds.nw.MinifluxClient

    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    companion object {
        /** The synthesis basket — survives navigation within the session. */
        val basket = mutableListOf<LogItem>()

        /** Sentinel pageKey: this item lives on the Text Notes screen, not a day-note surface. */
        const val PAGEKEY_TEXT_NOTES = "::text-notes"

        /** The basket's answer refused by today's page (day-file guard) — iOS's line
         *  (`DayFileWords.couldntWriteOnToday`); the answer is held for [Try again]. */
        const val BASKET_REFUSED = "Couldn't write on today — its page won't read"

        /** A door with a button stays long enough for a pen to reach it on e-ink. */
        private const val BASKET_RETRY_MS = 8_000
    }

    override val view = R.layout.fragment_reading_log

    private lateinit var binding: FragmentReadingLogBinding
    private lateinit var adapter: ReadingEventAdapter

    /** The period level, chosen from the almanac-style top bar; ‹ › page within it. */
    private enum class Range(val label: String) {
        DAY("Day"), WEEK("Week"), MONTH("Month"), QUARTER("Quarter"), YEAR("Year"), ALL("All")
    }
    // Day-based to match the unified Almanac navigator (the day nav steps ‹ ›).
    private var range = Range.DAY
    private var navBar: CalendarNavBarHost? = null

    /** Anchor date inside the currently-shown window; ‹ › shift it by one level unit. */
    private var anchor: LocalDate = LocalDate.now()

    private val weekFields get() = WeekFields.of(java.util.Locale.getDefault())

    /** Multi-select origin filter (empty = All). */
    private val origins = mutableSetOf<LogOrigin>()
    /** ★ only. */
    private var starredOnly = false
    /** Opt-in feed window: "off" (default — the general feed would be noise) | "unread" | "read" | "all".
     *  Feed articles enter the log AND search only when this is on. */
    private var feedMode = "off"
    /** "new" | "old" | "az" | "kind". */
    private var sortMode = "new"
    private var allItems: List<LogItem> = emptyList()
    private var lastShown: List<LogItem> = emptyList()
    private var searchQuery: String = ""

    // --- Search state — the two engines' pools, kept apart so chips re-filter without re-searching.

    /** The coarse search scopes — light chips over both engines' results. */
    private enum class SearchScope(val label: String, val origins: Set<LogOrigin>?) {
        ALL("All", null),
        HANDWRITING("Handwriting", setOf(LogOrigin.INK)),
        ARTICLES("Articles", setOf(LogOrigin.READ, LogOrigin.WATCH, LogOrigin.LISTEN,
            LogOrigin.FEED, LogOrigin.REPLY, LogOrigin.BOOK)),
        TASKS("Tasks", setOf(LogOrigin.TASK)),
        NOTES("Notes", setOf(LogOrigin.NOTE, LogOrigin.PICKING, LogOrigin.CARD, LogOrigin.AV))
    }
    private var searchScope = SearchScope.ALL

    /** Debounce: a keystroke schedules the search 250ms out; the next keystroke reschedules. */
    private val searchDebounce = android.os.Handler(android.os.Looper.getMainLooper())

    /** Stale-result guard: bumped per search; a slower layer landing late checks it and stands down. */
    private var searchSeq = 0

    /** The text engine's hits (exact/fuzzy word matches) and the semantic engine's extras. */
    private var textHits: List<LogItem> = emptyList()
    private var semanticHits: List<LogItem> = emptyList()
    private var semanticPending = false

    private fun filterPrefs() = requireContext().getSharedPreferences("ledger_log_filters", android.content.Context.MODE_PRIVATE)

    private fun loadFilterState() {
        val p = filterPrefs()
        origins.clear()
        p.getStringSet("origins", emptySet())?.forEach { n ->
            runCatching { origins.add(LogOrigin.valueOf(n)) }
        }
        starredOnly = p.getBoolean("starred_only", false)
        feedMode = p.getString("feed_mode", "off") ?: "off"
        sortMode = p.getString("sort_mode", "new") ?: "new"
    }

    private fun saveFilterState() {
        filterPrefs().edit()
            .putStringSet("origins", origins.map { it.name }.toSet())
            .putBoolean("starred_only", starredOnly)
            .putString("feed_mode", feedMode)
            .putString("sort_mode", sortMode)
            .apply()
    }

    /** Inclusive-start / exclusive-end bounds of the current window (null = unbounded/All). */
    private fun windowStart(): LocalDate? = when (range) {
        Range.DAY -> anchor
        Range.WEEK -> anchor.with(weekFields.dayOfWeek(), 1)
        Range.MONTH -> anchor.withDayOfMonth(1)
        Range.QUARTER -> anchor.withDayOfMonth(1).withMonth((anchor.monthValue - 1) / 3 * 3 + 1)
        Range.YEAR -> anchor.withDayOfYear(1)
        Range.ALL -> null
    }
    private fun windowEnd(): LocalDate? = when (range) {
        Range.DAY -> windowStart()!!.plusDays(1)
        Range.WEEK -> windowStart()!!.plusWeeks(1)
        Range.MONTH -> windowStart()!!.plusMonths(1)
        Range.QUARTER -> windowStart()!!.plusMonths(3)
        Range.YEAR -> windowStart()!!.plusYears(1)
        Range.ALL -> null
    }
    private fun shiftAnchor(dir: Int) {
        anchor = when (range) {
            Range.DAY -> anchor.plusDays(dir.toLong())
            Range.WEEK -> anchor.plusWeeks(dir.toLong())
            Range.MONTH -> anchor.plusMonths(dir.toLong())
            Range.QUARTER -> anchor.plusMonths(3L * dir)
            Range.YEAR -> anchor.plusYears(dir.toLong())
            Range.ALL -> anchor
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentReadingLogBinding.bind(view)

        loadFilterState()
        // Honour a preset origin chosen from the History menu (one-shot).
        ReadingLogSelection.origin?.let { origins.clear(); origins.add(it); ReadingLogSelection.origin = null }

        adapter = ReadingEventAdapter(emptyList(), onOpen = ::openItem, onLong = ::showRhizome, onMedia = ::openMedia)
        binding.readingRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.readingRecycler.adapter = adapter
        binding.readingRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // The unified Almanac navigator (same as the feed / Tasks & Events): arrows step
        // the day; the day/week/month slots jump into the calendar.
        navBar = CalendarNavBarHost(
            requireContext(), binding.navigatorImageView, this,
            onStepDay = { d -> val dir = if (d.isBefore(anchor)) -1 else 1; shiftAnchor(dir); renderNav(); load() },
            onSelectPeriod = { g, d ->
                range = when (g) {
                    "week" -> Range.WEEK; "month" -> Range.MONTH
                    "quarter" -> Range.QUARTER; "year" -> Range.YEAR; else -> Range.DAY
                }
                anchor = d; renderNav(); load()
            }
        )

        binding.originButton.text = originLabel()
        binding.originButton.setOnClickListener { showFilterModal() }

        // Hamburger = the unified "jump to any section" menu.
        binding.gotoButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        // Capture an A/V gram (photo / voice / video note-to-self) into today's day.
        binding.gramButton.setOnClickListener { captureAvGram { saveGram(it) } }

        binding.exportButton.setOnClickListener { promptExport() }
        // 🔎 Ask — hand the current search text to Ask my Ledger (its initial_query prefills
        // and asks); blank just opens Ask.
        binding.askButton.setOnClickListener {
            val q = binding.searchField.text?.toString()?.trim().orEmpty()
            val args = if (q.isNotEmpty()) androidx.core.os.bundleOf("initial_query" to q) else null
            NavHostFragment.findNavController(this).navigate(R.id.action_to_ledger_chat, args)
        }
        // Debounced (~250ms): search runs off-main once typing settles, not per keystroke.
        binding.searchField.doAfterTextChanged {
            val q = it?.toString().orEmpty()
            if (q == searchQuery) return@doAfterTextChanged
            searchQuery = q
            searchDebounce.removeCallbacksAndMessages(null)
            searchDebounce.postDelayed({ if (isAdded) load() }, 250)
        }
        buildScopeChips()

        // The hub's Ask field lands here having ALREADY typed. Setting the text is enough to run
        // the search — the watcher above debounces and loads — so this deliberately does nothing
        // else, and in particular does not raise the keyboard over the results you asked for.
        // Applied before the focusSearch block so a seeded arrival wins the keyboard question.
        ReadingLogSelection.seedQuery?.let { seed ->
            ReadingLogSelection.seedQuery = null
            ReadingLogSelection.focusSearch = false
            if (seed.isNotBlank()) binding.searchField.setText(seed)
        }

        // The hub's 🔍 Search row lands here wanting to TYPE — hand it the keyboard.
        if (ReadingLogSelection.focusSearch) {
            ReadingLogSelection.focusSearch = false
            binding.searchField.requestFocus()
            binding.searchField.post {
                (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(binding.searchField, 0)
            }
        }

        // Floating nav pill: ‹ up · ☀ today-then-menu · down ›.
        binding.readingPageUp.setOnClickListener {
            binding.readingRecycler.scrollBy(0, -(binding.readingRecycler.height * 9 / 10))
        }
        binding.readingPageDown.setOnClickListener {
            binding.readingRecycler.scrollBy(0, binding.readingRecycler.height * 9 / 10)
        }
        binding.readingGoto.setOnClickListener {
            if (range == Range.DAY && anchor == LocalDate.now()) {
                showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
            } else {
                range = Range.DAY; anchor = LocalDate.now(); renderNav(); load()
            }
        }
        // The floating pill and the header ☰ retire into the rail: ☰ Hub is the same accordion
        // door, and the pill's buttons — origin filter, A/V gram capture, export, the ‹ ☀ ›
        // trio — ride as rail icons. The search field, Ask and the scope chips stay in the
        // page: they are the surface's content, not chrome.
        binding.readingPill.visibility = View.GONE
        binding.gotoButton.visibility = View.GONE
        setupActionRail(
            binding.readingRail, "reading_log",
            actions = { listOf(
                com.toolsboox.ot.TuckPanel.Item(0, "Origins filter", glyph = "🗂") {
                    binding.originButton.performClick()
                },
                // 📊 Your year… — the log read as a whole, for a span. A door in the room where
                // the log lives (iOS wears the same glyph on the log's header row).
                com.toolsboox.ot.TuckPanel.Item(0, com.toolsboox.plugin.calendar.ot.LedgerYear.DOOR_TITLE, glyph = "📊") {
                    NavHostFragment.findNavController(this).navigate(R.id.action_to_year_in_ledger)
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_camera, "Capture pick") {
                    binding.gramButton.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(0, "Export", glyph = "⬆") {
                    binding.exportButton.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up, "Page up") {
                    binding.readingPageUp.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today") {
                    binding.readingGoto.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down, "Page down") {
                    binding.readingPageDown.performClick()
                }
            ) }
        )

        renderNav()
        load()
    }

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    /** Redraw the Almanac navigator strip for the current anchor day. */
    private fun renderNav() {
        lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.loadForDisplay(root, anchor, null, loc) }.getOrNull()
                    ?: CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                val p = runCatching { calendarPatternService.load(root, anchor, loc) }.getOrNull()
                cd to p
            }
            pat?.let { navBar?.render(day, it) }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun originLabel(): String {
        val base = when {
            origins.isEmpty() -> getString(R.string.reading_log_origin_all)
            origins.size <= 2 -> origins.joinToString("+") { it.label }
            else -> "${origins.size} kinds"
        }
        val star = if (starredOnly) "★ " else ""
        val feed = if (feedMode != "off") " +feed" else ""
        return "$star$base$feed"
    }

    /**
     * The filter modal: pick one or several kinds, ★ starred only, whether (and which) feed
     * articles join the log, and the sort. E-ink friendly — plain rows, no animation; every
     * tap redraws its own row in place. Filters persist across sessions.
     */
    private fun showFilterModal() {
        val ctx = requireContext()
        fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(8))
        }
        fun header(t: String) = android.widget.TextView(ctx).apply {
            text = t; textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, px(12), 0, px(2))
        }
        fun row(label: () -> String, onTap: (android.widget.TextView) -> Unit) =
            android.widget.TextView(ctx).apply {
                text = label(); textSize = 17f; setPadding(px(6), px(9), px(6), px(9))
                setOnClickListener { onTap(this); text = label() }
            }

        box.addView(header("Show"))
        box.addView(row({ (if (starredOnly) "★" else "☆") + "  Starred only" }) { starredOnly = !starredOnly })
        for (o in LogOrigin.values()) {
            box.addView(row({ (if (o in origins) "☑" else "☐") + "  ${o.mark}  ${o.label}" }) {
                if (o in origins) origins.remove(o) else origins.add(o)
            })
        }

        box.addView(header("Feed articles (off keeps the log quiet)"))
        val feedRows = mutableListOf<android.widget.TextView>()
        for ((mode, label) in listOf("off" to "Off", "unread" to "Unread", "read" to "Read", "all" to "All")) {
            val r = row({ (if (feedMode == mode) "◉" else "○") + "  $label" }) {
                feedMode = mode
                feedRows.forEach { fr -> fr.text = (fr.tag as () -> String)() }
            }
            r.tag = { (if (feedMode == mode) "◉" else "○") + "  $label" }
            feedRows.add(r); box.addView(r)
        }

        box.addView(header("Sort"))
        val sortRows = mutableListOf<android.widget.TextView>()
        for ((mode, label) in listOf("new" to "Newest first", "old" to "Oldest first", "az" to "A–Z", "kind" to "By kind")) {
            val r = row({ (if (sortMode == mode) "◉" else "○") + "  $label" }) {
                sortMode = mode
                sortRows.forEach { sr -> sr.text = (sr.tag as () -> String)() }
            }
            r.tag = { (if (sortMode == mode) "◉" else "○") + "  $label" }
            sortRows.add(r); box.addView(r)
        }

        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Filter the Log")
            .setView(scroll)
            .setNegativeButton("Clear") { _, _ ->
                origins.clear(); starredOnly = false; feedMode = "off"; sortMode = "new"
                saveFilterState(); binding.originButton.text = originLabel(); load()
            }
            .setPositiveButton("Done") { _, _ ->
                saveFilterState(); binding.originButton.text = originLabel(); load()
            }
            .show()
    }

    private fun load() {
        // Typing turns the Log into THE search: both engines, all time. Blank = browse the window.
        if (searchQuery.isNotBlank()) { runSearch(); return }
        binding.scopeRowScroll.visibility = View.GONE
        adapter.highlight = ""
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        val start = windowStart()
        val end = windowEnd()
        lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) { gather(start, end) }
            binding.progress.visibility = View.INVISIBLE
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = allItems.filter { item ->
            (origins.isEmpty() || item.origin in origins) && (!starredOnly || item.starred)
        }
        val shown = when (sortMode) {
            "old" -> filtered.sortedBy { it.millis }
            "az" -> filtered.sortedBy { it.title.lowercase().removePrefix("★ ") }
            "kind" -> filtered.sortedWith(compareBy({ it.origin.ordinal }, { -it.millis }))
            else -> filtered.sortedByDescending { it.millis }
        }
        adapter.submit(shown)
        lastShown = shown
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    // MARK: - Search: one field, two engines, merged results.

    /**
     * The search proper. Two engines answer the same query:
     *
     *  TEXT — the whole OCR/text layer: everything the browse walk carries (highlights, tasks,
     *  planner text, grams, intake) PLUS the corpus layers the walk doesn't — OCR'd page sections,
     *  handwritten annotations, typed Text Notes, and FULL cached feed-article text — matched
     *  case/diacritic-insensitively, every word of the query somewhere in the item.
     *
     *  SEMANTIC — [LedgerCorpusService.retrieveHybrid] over the same cached corpus, the very call
     *  Ask grounds on: the query embeds once, cosine against the vector cache, so "grief" finds
     *  the highlight about mourning that never used the word.
     *
     * The text layer paints first (it's local and fast); the semantic layer streams in beneath it
     * as "Related — semantic" when the embeddings come back. [searchSeq] guards both landings.
     */
    private fun runSearch() {
        val seq = ++searchSeq
        val q = searchQuery
        binding.scopeRowScroll.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        adapter.highlight = q
        semanticHits = emptyList()
        semanticPending = true
        // Grabbed on main while surely attached — the IO layers below must not requireContext().
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val toks = com.toolsboox.plugin.calendar.ot.LedgerSearch.tokens(q)
            // Text layer: the log's own items (all time) + the corpus-only layers.
            val text = withContext(Dispatchers.IO) {
                val local = gather(null, null).filter {
                    com.toolsboox.plugin.calendar.ot.LedgerSearch.matches(
                        toks, it.title + " " + it.meta + " " + it.body)
                }
                local + corpusTextHits(toks)
            }
            if (seq != searchSeq || !isAdded) return@launch
            allItems = text
            textHits = dedupe(text)
            paintSearch()
            // Semantic layer: embeddings need a key and a few letters to mean anything.
            val related = if (toks.sumOf { it.length } >= 3) {
                withContext(Dispatchers.IO) { semanticRelated(appCtx, q, toks) }
            } else emptyList()
            if (seq != searchSeq || !isAdded) return@launch
            semanticHits = related
            semanticPending = false
            binding.progress.visibility = View.INVISIBLE
            paintSearch()
        }
    }

    /** Merge the two engines' pools under their headers, chip-filtered, and show them. */
    private fun paintSearch() {
        fun scoped(items: List<LogItem>) = searchScope.origins?.let { o -> items.filter { it.origin in o } } ?: items
        val toks = com.toolsboox.plugin.calendar.ot.LedgerSearch.tokens(searchQuery)
        val matches = scoped(textHits).sortedWith(
            // Title hits outrank body hits; recency breaks ties — relevance without a scoreboard.
            compareByDescending<LogItem> {
                com.toolsboox.plugin.calendar.ot.LedgerSearch.matches(toks, it.title)
            }.thenByDescending { it.millis })
        val related = scoped(semanticHits)
        val shown = ArrayList<LogItem>(matches.size + related.size)
        matches.forEachIndexed { i, it ->
            shown += if (i == 0) it.copy(header = "MATCHES · ${matches.size}") else it
        }
        related.forEachIndexed { i, it ->
            shown += if (i == 0) it.copy(header = "RELATED — SEMANTIC") else it
        }
        adapter.submit(shown)
        lastShown = shown
        if (!semanticPending) binding.progress.visibility = View.INVISIBLE
        binding.emptyText.visibility = if (shown.isEmpty() && !semanticPending) View.VISIBLE else View.GONE
    }

    /** The corpus layers the browse walk does NOT carry, text-matched: OCR'd page sections,
     *  handwritten annotations, Text Notes, and full cached feed-article text. Rides the corpus
     *  service's mtime cache, so after the first walk this is an in-memory filter. */
    private fun corpusTextHits(toks: List<String>): List<LogItem> {
        val sections = setOf(
            com.toolsboox.plugin.chat.da.Section.SECTIONS,
            com.toolsboox.plugin.chat.da.Section.ANNOTATIONS,
            com.toolsboox.plugin.chat.da.Section.NOTES,
            com.toolsboox.plugin.chat.da.Section.FEED
        )
        return runCatching { corpusService.gather(documentsRoot(), sections) }
            .getOrDefault(emptyList())
            .filter { com.toolsboox.plugin.calendar.ot.LedgerSearch.matches(
                toks, it.title + " " + it.source + " " + it.text) }
            .map { snippetItem(it, toks) }
    }

    /** The semantic engine: the full corpus through the same hybrid retrieval Ask uses, minus
     *  anything the text layer already shows. Empty without an embeddings key — never mislabel
     *  keyword fallback as semantic. Blocking (network) — call on IO. */
    private fun semanticRelated(ctx: android.content.Context, q: String, toks: List<String>): List<LogItem> {
        com.toolsboox.plugin.chat.nw.EmbeddingIndex.apiKey(ctx) ?: return emptyList()
        val already = textHits.map { itemKey(it) }.toHashSet()
        return runCatching {
            val all = corpusService.gather(documentsRoot())
            corpusService.retrieveHybrid(all, q, 24)
        }.getOrDefault(emptyList())
            .map { snippetItem(it, toks) }
            .filter { itemKey(it) !in already }
    }

    /** One corpus snippet → one log row, its body windowed around the match, its origin and
     *  pageKey set so the row is a DOOR (see [goToSource]) and the scope chips can sort it.
     *  The snippet's citation rides along — the key "Remove from corpus" tombstones. */
    private fun snippetItem(s: com.toolsboox.plugin.chat.da.CorpusSnippet, toks: List<String>): LogItem {
        val day = s.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        val body = com.toolsboox.plugin.calendar.ot.LedgerSearch.window(s.text, toks)
        val (origin, pageKey) = when (s.section) {
            com.toolsboox.plugin.chat.da.Section.SECTIONS -> LogOrigin.INK to s.source.takeIf { it.isNotBlank() }
            com.toolsboox.plugin.chat.da.Section.ANNOTATIONS -> LogOrigin.INK to null
            com.toolsboox.plugin.chat.da.Section.NOTES -> LogOrigin.NOTE to PAGEKEY_TEXT_NOTES
            com.toolsboox.plugin.chat.da.Section.FEED -> LogOrigin.FEED to null
            com.toolsboox.plugin.chat.da.Section.PLANNER -> LogOrigin.NOTE to s.source.takeIf { it.isNotBlank() }
            com.toolsboox.plugin.chat.da.Section.BOOKS -> LogOrigin.BOOK to null
            com.toolsboox.plugin.chat.da.Section.ARTICLES -> LogOrigin.READ to null
            com.toolsboox.plugin.chat.da.Section.MEDIA -> LogOrigin.AV to null
            com.toolsboox.plugin.chat.da.Section.TASKS -> LogOrigin.TASK to null
            // Mail joined the corpus 08-23 ("does it have access to the feeds (email, rss,
            // book)?"); a matched letter reads as correspondence in the log's vocabulary.
            com.toolsboox.plugin.chat.da.Section.MAIL -> LogOrigin.REPLY to null
        }
        val meta = if (s.section == com.toolsboox.plugin.chat.da.Section.FEED)
            listOf(s.source, s.citation).filter { it.isNotBlank() }.joinToString(" · ")
        else s.citation
        return LogItem(origin, s.title, meta, body, null, s.date.time,
            day = day, pageKey = pageKey, citation = s.citation)
    }

    /** The identity a thing keeps across both engines: folded title + its day. */
    private fun itemKey(i: LogItem): String {
        val t = com.toolsboox.plugin.calendar.ot.LedgerSearch.fold(
            i.title.removePrefix("★ ").take(80)).folded
        return t + "|" + (i.day ?: java.time.Instant.ofEpochMilli(i.millis)
            .atZone(ZoneId.systemDefault()).toLocalDate())
    }

    private fun dedupe(items: List<LogItem>): List<LogItem> {
        val seen = HashSet<String>()
        return items.filter { seen.add(itemKey(it)) }
    }

    /** The scope chips: plain text toggles (e-ink friendly), redrawn in place on tap. */
    private fun buildScopeChips() {
        val ctx = requireContext()
        fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
        binding.scopeRow.removeAllViews()
        val chips = mutableListOf<android.widget.TextView>()
        for (s in SearchScope.values()) {
            val tv = android.widget.TextView(ctx).apply {
                textSize = 14f
                setPadding(px(12), px(5), px(12), px(5))
                setOnClickListener {
                    searchScope = s
                    chips.forEach { c -> restyleChip(c, (c.tag as SearchScope) == s) }
                    if (searchQuery.isNotBlank()) paintSearch()
                }
                tag = s
                text = s.label
            }
            restyleChip(tv, s == searchScope)
            chips.add(tv)
            binding.scopeRow.addView(tv)
        }
    }

    private fun restyleChip(tv: android.widget.TextView, on: Boolean) {
        tv.setTextColor(if (on) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        tv.setBackgroundColor(if (on) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT)
        tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** Export the currently-shown highlights & annotations as Markdown or CSV. */
    private fun promptExport() {
        val items = lastShown
        if (items.isEmpty()) { showMessage(getString(R.string.reading_log_empty)); return }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(R.string.reading_log_export)
            .setItems(arrayOf("Markdown (.md)", "CSV (.csv)")) { _, which ->
                val ctx = requireContext().applicationContext
                if (which == 0) LedgerExport.share(ctx, LedgerExport.markdown(items), "ledger-highlights.md", "text/markdown")
                else LedgerExport.share(ctx, LedgerExport.csv(items), "ledger-highlights.csv", "text/csv")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Walk the day files inside [start, end) (nulls = unbounded), flattening to log items. */
    private fun gather(start: LocalDate?, end: LocalDate?): List<LogItem> {
        val calendarRoot = File(documentsRoot(), "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val ctx = requireContext().applicationContext
        val df = DateFormat.getDateFormat(ctx)
        val tf = DateFormat.getTimeFormat(ctx)
        fun stamp(d: Date) = "${df.format(d)} · ${tf.format(d)}"
        val out = mutableListOf<LogItem>()
        // read/watch/listen/educate → the log's origins (educate folds into Read).
        val intakeKinds = listOf(
            "read" to LogOrigin.READ, "watch" to LogOrigin.WATCH,
            "listen" to LogOrigin.LISTEN, "educate" to LogOrigin.READ
        )

        // The Synthesize index's names, read once for the walk (the Log's ❖ rows).
        val synthNames by lazy {
            com.toolsboox.plugin.calendar.ot.SynthPageStore.list(ctx).associate { it.key to it.name }
        }
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val dayDate = dateOf(file.name)
                if (dayDate != null) {
                    if (start != null && dayDate.isBefore(start)) return@forEach
                    if (end != null && !dayDate.isBefore(end)) return@forEach
                }
                val day = runCatching { calendarDayService.load(file) }
                    .onFailure { Timber.w(it, "reading-log: skipping ${file.name}") }.getOrNull() ?: return@forEach
                // Every item carries its home day — the rhizome edge back to the page it lives on.
                fun put(item: LogItem) { out.add(item.copy(day = dayDate)) }

                // Collapse the double log (iOS parity): starring writes art-<id> and each
                // annotation appends art-<id>-xxxx — the same article showed two or three
                // times. EVERY LINE WITH ITS OWN PASSAGE KEEPS ITS ROW (design critique #5,
                // Michael 2026-10-12: one highlight is one Log line — two passages from one
                // article were folded into one row and the second vanished); the bare lines — a
                // star, a "read" — fold into the article's richest row, as before. A note about
                // the whole piece keeps its row. iOS's gatherLog takes the same rule. Books keep
                // every highlight.
                val passthrough = mutableListOf<ReadingEvent>()
                val byUrl = LinkedHashMap<String, MutableList<ReadingEvent>>()
                for (e in day.readingEvents) {
                    val u = e.url
                    // Correspondence events (source starts with "↩", wire kind stays ARTICLE
                    // for cross-platform decode) are their own rows, never collapsed into the
                    // star they answer.
                    if (e.kind != ReadingEvent.Kind.ARTICLE || u.isNullOrBlank() ||
                        e.source?.startsWith("↩") == true) { passthrough.add(e); continue }
                    byUrl.getOrPut(u) { mutableListOf() }.add(e)
                }
                val mergedByUrl = LinkedHashMap<String, ReadingEvent>()
                for ((u, group) in byUrl) {
                    // Lines with a passage: one row each (the same passage twice is one row — the
                    // copy that carries the note or media, not whichever came first: review
                    // 2026-10-12 #15, the plain twin hid the note).
                    val passages = group.filter { !it.excerpt.isNullOrBlank() }
                        .groupBy { it.excerpt!!.replace(Regex("\\s+"), " ").trim() }.values
                        .map { same -> same.maxByOrNull { (it.note?.length ?: 0) + 1000 * (it.attachments?.size ?: 0) } ?: same.first() }
                    // A note about the whole piece (the highlights' Note with nothing selected,
                    // "mfnote-…") keeps its own row too; every other passage-less line — the star,
                    // its blurb, a "read" — folds into the article's richest row.
                    val wholeNotes = group.filter { it.excerpt.isNullOrBlank() && it.id.startsWith("mfnote-") &&
                        (!it.note.isNullOrBlank() || !it.attachments.isNullOrEmpty()) }
                    val rest = group.filter { it.excerpt.isNullOrBlank() && it !in wholeNotes }
                    val rows = passages + wholeNotes
                    if (rows.isEmpty()) {
                        // The old rule for a piece with no passage: one row, the richest, the
                        // star OR'd across them.
                        val keep = rest.maxByOrNull { (it.note ?: "").length } ?: rest.first()
                        mergedByUrl[u] = keep.copy(starred = rest.any { it.starred },
                            published = keep.published ?: rest.firstNotNullOfOrNull { it.published })
                        continue
                    }
                    val richest = rows.maxByOrNull { ((it.note ?: "") + (it.excerpt ?: "")).length } ?: rows.first()
                    for (e in rows) {
                        val folded = if (e === richest) e.copy(
                            starred = e.starred || rest.any { it.starred },
                            published = e.published ?: rest.firstNotNullOfOrNull { it.published }) else e
                        mergedByUrl[u + "\u0000" + e.id] = folded
                    }
                }
                for (e in passthrough + mergedByUrl.values) {
                    val o = when {
                        e.source?.startsWith("↩") == true -> LogOrigin.REPLY   // correspondence
                        e.kind == ReadingEvent.Kind.BOOK -> LogOrigin.BOOK
                        else -> LogOrigin.READ
                    }
                    // A star lives on two days (starred + published; see logStar). Each row
                    // clarifies the OTHER date: the star-day row shows "published MMM d",
                    // the published-day companion carries "★ starred MMM d" in its note.
                    val pubLabel = e.published?.takeIf { !e.id.endsWith("-pub") }?.let {
                        "published " + java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(it)
                    }
                    val meta = listOfNotNull(e.source?.takeIf { it.isNotBlank() }, stamp(e.date), pubLabel)
                        .joinToString(" · ")
                    // The passage, then the typed note UNDER it (add-to-pick pin §6 — the note used to
                    // hide behind the excerpt); a line with no passage reads as its note.
                    val passage = e.excerpt?.takeIf { it.isNotBlank() }?.trim()
                    val body = (passage ?: e.note).orEmpty().trim()
                    val noteUnder = if (passage != null) e.note?.trim().orEmpty() else ""
                    // Captured media rides on the event's attachments — the strip shows every one;
                    // the detail card keeps a photo (the handwriting when there is no photo).
                    val atts = e.attachments.orEmpty()
                    val photo = atts.firstOrNull { it.kind == Attachment.Kind.PHOTO && !it.filename.startsWith("ink-") }
                        ?: atts.firstOrNull { it.kind == Attachment.Kind.PHOTO }
                    val audio = atts.firstOrNull { it.kind == Attachment.Kind.AUDIO }
                    val media = atts.map { a ->
                        LogMedia(a.kind, attachmentPath(a), a.duration?.toInt() ?: 0, ink = a.filename.startsWith("ink-"))
                    }
                    put(LogItem(o, e.title.ifBlank { getString(R.string.reading_log_untitled) }, meta, body,
                        e.url, e.date.time, imagePath = photo?.let { attachmentPath(it) },
                        audioPath = audio?.let { attachmentPath(it) }, starred = e.starred, eventId = e.id,
                        note = noteUnder, media = media))
                }

                val fallback = dayDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L

                // A HIGHLIGHT IS ONE ROW (iOS's gatherLog, PlannerShell.swift; review 2026-10-12
                // R2 #6 — Michael 2026-10-12: "It goes in the day it was picked's log"): the card an
                // article highlight placed is that highlight's line, so it gets no row of its own
                // beside it; nor does the voice or video note that makes it sound (it rides the
                // line's own row). Matched by the article and the passage, as the page draws them.
                val highlighted = day.readingEvents.mapNotNull { e ->
                    val u = e.url; val x = e.excerpt
                    if (e.kind != ReadingEvent.Kind.ARTICLE || u.isNullOrBlank() || x.isNullOrBlank()) null
                    else com.toolsboox.plugin.feeds.ot.ArticleKey.of(u) + "|" +
                        com.toolsboox.plugin.feeds.ot.ArticleMarkSet.norm(com.toolsboox.plugin.calendar.ot.ArticlePickIndex.passageOf(x))
                }.toHashSet()
                val lineCards = if (highlighted.isEmpty()) emptyList() else day.imageElements.filter { img ->
                    img.sourceLink.startsWith("http", ignoreCase = true) && img.cardText.isNotBlank() &&
                        (com.toolsboox.plugin.feeds.ot.ArticleKey.of(img.sourceLink) + "|" +
                            com.toolsboox.plugin.feeds.ot.ArticleMarkSet.norm(
                                com.toolsboox.plugin.calendar.ot.ArticlePickIndex.passageOf(img.cardText))) in highlighted
                }
                val lineCardIds = lineCards.map { it.elementId }.toHashSet()
                // Every clip that rides a line is in that line's strip already (review 2026-10-16 #9:
                // only each card's ONE media slot was skipped, so a pick's second and third clips
                // showed twice) — skip them all, and whatever a line card's face plays.
                val lineClips = (lineCards.mapNotNull { it.attachmentId.ifBlank { null } } +
                    lineCards.flatMap { c -> c.faceSlots.orEmpty().map { it.a } } +
                    day.readingEvents.flatMap { e -> e.attachments.orEmpty().map { it.id } })
                    .map { it.lowercase() }.toHashSet()

                // AV grams are the daily video/audio "note to self" recordings — all AV.
                for (a in day.avGrams) {
                    if (a.id.lowercase() in lineClips) continue
                    val label = when (a.kind) {
                        Attachment.Kind.VIDEO -> getString(R.string.reading_log_av_video)
                        Attachment.Kind.AUDIO -> getString(R.string.reading_log_av_audio)
                        Attachment.Kind.PHOTO -> getString(R.string.reading_log_av_photo)
                    }
                    val meta = listOfNotNull(a.duration?.let { mmss(it) }, a.date?.let { stamp(it) }).joinToString(" · ")
                    val path = attachmentPath(a)
                    put(LogItem(LogOrigin.AV, label, meta, "", null, a.date?.time ?: fallback,
                        imagePath = if (a.kind == Attachment.Kind.PHOTO) path else null,
                        audioPath = if (a.kind == Attachment.Kind.AUDIO) path else null))
                }

                // Pickings — the typed quotes on the day's Pickings page.
                for (t in day.textElements.filter { it.pageKey == "pickings" && it.text.isNotBlank() }) {
                    put(LogItem(LogOrigin.PICKING, t.text.trim(), stamp(Date(fallback)), "", null, fallback))
                }

                // Every addition to a surface — tasks/events, text notes on any other page, and
                // placed cards — so the Ledger Log is a full record, not only reading highlights.
                for (item in day.ledgerItems.filter { it.text.isNotBlank() }) {
                    val label = when {
                        item.kind == com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT -> "Event"
                        item.done -> "✓ Completed"      // task completion shows up as its own activity
                        else -> "Task"
                    }
                    val meta = listOfNotNull(item.time, stamp(item.date)).joinToString(" · ")
                    put(LogItem(LogOrigin.TASK, item.text.trim(), meta, label, null, item.date.time))
                }
                for (t in day.textElements.filter { it.pageKey != "pickings" && it.text.isNotBlank() }) {
                    put(LogItem(LogOrigin.NOTE, t.text.trim(), stamp(Date(t.timestamp)), "", null, t.timestamp))
                }
                // A highlight's line card is that line's row (above) — never a row, nor a fold, of
                // its own. Then ❖ ONE ROW PER SYNTHESIS (synth-list-links §5): a `synthesize-*`
                // page's cards and the pick linking to it fold into "❖ <page name>", whose Go to
                // opens the page; every other card keeps its own row.
                val synthFold = com.toolsboox.plugin.calendar.ot.SynthListLanding.logFold(
                    day.imageElements.filter { it.elementId !in lineCardIds }, dayDate)
                for (r in synthFold.rows) {
                    val name = synthNames[r.key]?.takeIf { it.isNotBlank() } ?: r.pickName.ifBlank { "Synthesize" }
                    put(LogItem(LogOrigin.CARD, "❖ $name", stamp(Date(r.millis)), r.text,
                        com.toolsboox.plugin.calendar.ot.SynthSources.pageLink(r.date.toString(), r.key), r.millis))
                }
                for (img in synthFold.rest) {
                    put(LogItem(LogOrigin.CARD, "Card · ${img.page.ifBlank { "day" }}",
                        stamp(Date(img.timestamp)), "", null, img.timestamp))
                }

                // Intake — links filed to Read / Watch / Listen / Educate (MichaelFilter),
                // from the day's intake sidecar. One item per typed line.
                dayDate?.let { d ->
                    val intake = IntakePageStore.load(ctx, d)
                    for ((kind, o) in intakeKinds) {
                        intake.typedFor(kind).lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                            val url = ShareTextParser.extractUrls(line).firstOrNull()
                            put(LogItem(o, line, stamp(Date(fallback)), "", url, fallback))
                        }
                    }
                }
            }
        // Feed articles are OPT-IN (feedMode): the general feed inside the log — or its
        // search — is noise by default, but the toggle folds the window's published
        // articles in as 📰 rows (unread / read / all).
        if (feedMode != "off") {
            val creds = feedCreds()
            if (creds != null) {
                val (fUrl, fTok) = creds
                val status = when (feedMode) { "unread" -> "unread"; "read" -> "read"; else -> null }
                val zone = ZoneId.systemDefault()
                val res = if (searchQuery.isNotBlank())
                    miniflux.search(fUrl, fTok, searchQuery, 100)
                else {
                    val after = (start ?: LocalDate.now().minusYears(1)).atStartOfDay(zone).toEpochSecond()
                    val before = (end ?: LocalDate.now().plusDays(1)).atStartOfDay(zone).toEpochSecond()
                    miniflux.fetchPublishedWindow(fUrl, fTok, status, after, before)
                }
                if (res is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok) {
                    for (fe in res.value) {
                        if (fe.id <= 0) continue   // synthetic rows never belong here
                        // search= has no status filter — apply the unread/read choice here.
                        if (searchQuery.isNotBlank() && status != null && (status == "read") != fe.read) continue
                        val pubDay = runCatching { LocalDate.parse(fe.publishedAt.take(10)) }.getOrNull()
                        val millis = pubDay?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
                        out.add(LogItem(
                            LogOrigin.FEED, fe.title,
                            listOfNotNull(
                                fe.feedTitle.takeIf { it.isNotBlank() },
                                pubDay?.toString(),
                                if (fe.read) "read" else "unread"
                            ).joinToString(" · "),
                            "", fe.url, millis, day = pubDay, starred = fe.starred
                        ))
                    }
                }
            }
        }
        return out.sortedByDescending { it.millis }
    }

    /** Miniflux URL+token from the feeds plugin's encrypted prefs; null when unconfigured. */
    private fun feedCreds(): Pair<String, String>? = runCatching {
        val ctx = requireContext()
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        val p = androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx, com.toolsboox.plugin.feeds.ui.FeedsFragment.PREFS, masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val u = p.getString(com.toolsboox.plugin.feeds.ui.FeedsFragment.KEY_URL, "").orEmpty()
        val t = p.getString(com.toolsboox.plugin.feeds.ui.FeedsFragment.KEY_TOKEN, "").orEmpty()
        if (u.isBlank() || t.isBlank()) null else u to t
    }.getOrNull()

    private fun mmss(seconds: Double): String {
        val s = seconds.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Parse the day from a `day-YYYY-MM-DD-v2.json` filename. */
    private fun dateOf(name: String): LocalDate? = runCatching {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    private fun attachmentPath(a: Attachment): String = File(attachmentsDir(), a.filename).absolutePath

    /** Append a freshly-captured A/V gram to today's CalendarDay, then refresh the log. */
    private fun saveGram(att: Attachment) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    val today = LocalDate.now()
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                        // Throws (→ logged below) rather than save a fresh day over an unreadable one.
                        val day = calendarDayService.loadForWriteOrThrow(root, today, null, java.util.Locale.getDefault())
                        day.avGrams.add(att)
                        calendarDayService.save(root, today, day)
                    }
                }.onFailure { Timber.w(it, "failed to save A/V gram") }
            }
            load()
        }
    }

    /** Tapping a note/annotation slides up a detail popup with its full info + actions. */
    private fun openItem(item: LogItem) {
        showItemDetail(item)
    }

    /** Press the basket's gathered items through one of the three engines; the artifact lands
     *  on TODAY'S Synthesize page — the Log feeds the pressure chamber directly. */
    private fun synthesizeBasket() {
        val ctx = requireContext()
        com.toolsboox.plugin.calendar.ot.SynthEngines.pick(
            ctx, "Synthesize ${basket.size} items", present = { showModal(it) }) { engine ->
            val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
            if (creds == null) {
                android.widget.Toast.makeText(ctx, "Add your AI key in Ask my Ledger settings", android.widget.Toast.LENGTH_LONG).show()
                return@pick
            }
            val (provider, key, model) = creds
            val items = basket.toList()
            android.widget.Toast.makeText(ctx, "Synthesizing…", android.widget.Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                // Reply items (correspondence) with a link press their FULL text into the
                // engine — the enriched reply is the richest version, so synthesis reads
                // the essay, not just the headline. (Network fetch, so built here on IO,
                // not while assembling the dialog.)
                val material = items.joinToString("\n") { it2 ->
                    val full = if (it2.origin == LogOrigin.REPLY && !it2.url.isNullOrBlank()) {
                        val r = miniflux.fetchPageReadable(it2.url)
                        if (r is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok)
                            "\n" + android.text.Html.fromHtml(r.value, android.text.Html.FROM_HTML_MODE_LEGACY)
                                .toString().replace(Regex("\\s+"), " ").trim().take(1800)
                        else ""
                    } else ""
                    "• [${it2.origin.label}] ${it2.title}" +
                        (if (it2.body.isNotBlank()) " — ${it2.body}" else "") + full
                }.take(9000)
                val res = chatService.run(provider, key, model, engine.prompt, material)
                when (res) {
                    is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok ->
                        landBasketAnswer(ctx, engine.name, res.answer.trim())
                    is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err -> withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "⚠ ${res.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * The basket's answer onto today's Synthesize page. Today's file that won't read is never saved
     * over — and the answer is NOT thrown away for it (2026-10-16 review, item 9: he had to ask
     * again): it is held, and [Try again] writes THAT answer once the page reads, without asking
     * the engine again. The basket is cleared only once the answer is on the page. Off the main
     * thread; says where it went (or why not) on it.
     */
    private suspend fun landBasketAnswer(ctx: android.content.Context, engineName: String, answer: String) {
        val today = LocalDate.now()
        val attempt = runCatching {
            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                // The writer's door: a today-file that won't read THROWS, never saved over.
                val day = calendarDayService.loadForWriteOrThrow(documentsRoot(), today, null, java.util.Locale.getDefault())
                val y = (day.textElements.filter { it.pageKey == "synthesize" }
                    .maxOfOrNull { it.y } ?: 60f) + 140f
                day.textElements.add(com.toolsboox.da.TextElement(
                    x = 80f, y = y.coerceAtMost(1600f), width = 1200f, height = 60f,
                    text = "$engineName\n\n$answer", pageKey = "synthesize"))
                calendarDayService.save(documentsRoot(), today, day)
            }
        }.onFailure { Timber.w(it, "the basket's answer didn't land") }
        withContext(Dispatchers.Main) {
            if (attempt.isSuccess) {
                basket.clear()
                android.widget.Toast.makeText(ctx, "On today's Synthesize page", android.widget.Toast.LENGTH_LONG).show()
                return@withContext
            }
            val words = if (attempt.exceptionOrNull() is com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException)
                BASKET_REFUSED else "Couldn't write on today's Synthesize page."
            // (This fragment's `view` is its layout id — the standing view is requireView's.)
            val v = if (isAdded) runCatching { requireView() }.getOrNull() else null
            if (v != null) {
                com.google.android.material.snackbar.Snackbar.make(v, words, BASKET_RETRY_MS)
                    .setAction("Try again") {
                        lifecycleScope.launch(Dispatchers.IO) { landBasketAnswer(ctx, engineName, answer) }
                    }.show()
            } else android.widget.Toast.makeText(ctx, words, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The rhizome menu — every log item is a zettel that reaches its neighbors. Long-press:
     * open the DAY it lives on, open its SOURCE in the reader, PULL THE THREAD (search the
     * whole log for kin), or SEND it to today's Pickings to work with it in ink.
     */
    private fun showRhizome(item: LogItem) {
        val ctx = requireContext()
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        // ＋ Add to this pick (add-to-pick pin §2 D4): a line whose highlight has a pick gets the Add
        // ladder TIED to it — onto this line, the same card re-faced.
        addToPickRow(item)?.let { rows.add(it) }
        item.day?.let { d ->
            rows.add("☀  Open its day · $d" to {
                CalendarNavigator.toDayPage(this, d, com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE)
            })
        }
        item.url?.takeIf { it.startsWith("http") }?.let { url ->
            rows.add("📰  Open source in reader" to {
                com.toolsboox.plugin.feeds.ui.FeedSelection.entry = com.toolsboox.plugin.feeds.da.FeedEntry(
                    id = 0, title = item.title.removePrefix("⭐ "), feedTitle = "", url = url,
                    author = null, content = "", publishedAt = "", starred = false
                )
                com.toolsboox.plugin.feeds.ui.FeedSelection.list = emptyList()
                androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.FeedArticleFragment)
            })
        }
        rows.add("＃  Pull the thread" to {
            // Kin across the WHOLE log: widen to All time and search the item's key words.
            val words = item.title.removePrefix("⭐ ").replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .trim().split(Regex("\\s+")).take(4).joinToString(" ")
            if (words.isNotBlank()) {
                range = Range.ALL
                searchQuery = words
                binding.searchField.setText(words)
                renderNav()
                load()
            }
        })
        // The categorical KIN edge (the notes analog of a card's contact / a gram's space): every
        // item of this kind across all time — computed over the whole synced day-JSON corpus.
        rows.add("▸  Kin — same kind (${item.origin.label})" to {
            origins.clear(); origins.add(item.origin)
            range = Range.ALL
            searchQuery = ""
            binding.searchField.setText("")
            binding.originButton.text = originLabel()
            saveFilterState()
            renderNav()
            load()
        })
        // The synthesis BASKET — gather a few items from anywhere in history, then press them
        // together through an engine. The result lands on today's Synthesize page.
        val inBasket = basket.any { it.millis == item.millis && it.title == item.title }
        rows.add((if (inBasket) "⊖  Remove from synthesis basket" else "⊕  Add to synthesis basket (${basket.size})") to {
            if (inBasket) basket.removeAll { it.millis == item.millis && it.title == item.title }
            else basket.add(item)
            android.widget.Toast.makeText(ctx, "${basket.size} in the basket", android.widget.Toast.LENGTH_SHORT).show()
        })
        if (basket.isNotEmpty()) {
            rows.add("❖  Synthesize ${basket.size} selected…" to { synthesizeBasket() })
            rows.add("∅  Empty the basket" to {
                basket.clear()
                android.widget.Toast.makeText(ctx, "Basket emptied", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        // ⁂ Pick — the item becomes a GRAM through the same medium chooser every pick uses
        // (handwriting / text / audio / video), landing on today's Notes page as a placed card.
        rows.add("⁂  Pick it — a card for your Pickings" to {
            val quote = listOf(item.title.removePrefix("★ "), item.body)
                .filter { it.isNotBlank() }.joinToString("\n\n")
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
                this, calendarDayService, documentsRoot(),
                itemText = quote.take(600),
                originLabel = (item.origin.label + (item.day?.let { " · $it" } ?: "")).take(80),
                sourceUrl = item.url.orEmpty()
            ) { kind, sink -> captureAvGramDirect(kind, sink) }
        })
        // A corpus-surfaced result can be thrown out of the corpus for good — tombstoned, so no
        // re-gather on any surface (search, Roots, Ask…) brings it back.
        item.citation?.let { c ->
            rows.add("🗑  Remove from corpus" to {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { corpusService.exclude(c) } }
                    if (isAdded) load()
                }
            })
        }
        rows.add("❝  Send to today's Pickings" to {
            lifecycleScope.launch(Dispatchers.IO) {
                val today = LocalDate.now()
                val wrote = com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    // A today-file that won't read is never saved over — say so instead.
                    val day = calendarDayService.loadForWrite(documentsRoot(), today, null, java.util.Locale.getDefault())
                        ?: return@withDay false
                    day.textElements.add(com.toolsboox.da.TextElement(
                        x = 80f, y = 120f, width = 760f, height = 60f,
                        text = item.title.removePrefix("⭐ ") + (if (item.body.isNotBlank()) "\n" + item.body else ""),
                        pageKey = "pickings"
                    ))
                    calendarDayService.save(documentsRoot(), today, day)
                    true
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx,
                        if (wrote) "On today's Pickings" else "Couldn't write on today — its page won't read",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(item.title.take(60).ifBlank { "Item" })
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** `＋ Add to this pick` for a line whose highlight has a pick (pin §2 D4), or null. */
    private fun addToPickRow(item: LogItem): Pair<String, () -> Unit>? {
        val id = item.eventId ?: return null
        val app = requireContext().applicationContext
        val mark = com.toolsboox.plugin.feeds.ot.PickAdds.markOfLine(app, id, item.url) ?: return null
        return com.toolsboox.plugin.calendar.ot.AddToPickWords.ADD to {
            val engine = com.toolsboox.plugin.feeds.ot.PickAdds(app, calendarDayService, { documentsRoot() }) { msg ->
                com.toolsboox.plugin.feeds.ot.AddToPick.say(app, msg)
            }
            com.toolsboox.plugin.feeds.ot.AddToPick.showLadder(this, com.toolsboox.plugin.feeds.ot.AddToPick.Tied(
                engine, com.toolsboox.plugin.feeds.ot.PickAdds.articleOf(mark, item.url.orEmpty()), mark) { msg ->
                com.toolsboox.plugin.feeds.ot.AddToPick.main {
                    msg?.let { com.toolsboox.plugin.feeds.ot.AddToPick.say(app, it) }
                    if (isAdded) load()
                }
            })
        }
    }

    /** A read-only detail card for one log item — title, source/date, image, full
     *  body/excerpt/note — with Open-link / Play / Photo actions where relevant. */
    private fun showItemDetail(item: LogItem) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(22), px(20), px(22), px(8))
        }
        col.addView(android.widget.TextView(ctx).apply {
            text = item.title.ifBlank { getString(R.string.reading_log_untitled) }
            textSize = 20f; setTextColor(android.graphics.Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val meta = listOf(item.origin.label, item.meta).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) col.addView(android.widget.TextView(ctx).apply {
            text = meta; textSize = 13f; setTextColor(android.graphics.Color.DKGRAY)
            setPadding(0, px(4), 0, 0)
        })
        item.imagePath?.let { p ->
            if (File(p).exists()) col.addView(android.widget.ImageView(ctx).apply {
                setImageURI(Uri.fromFile(File(p)))
                adjustViewBounds = true
                setPadding(0, px(12), 0, 0)
            })
        }
        if (item.body.isNotBlank()) col.addView(android.widget.TextView(ctx).apply {
            text = item.body; textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            setPadding(0, px(12), 0, 0); setLineSpacing(0f, 1.15f)
        })

        // ＋ Add to this pick — a line whose highlight has a pick (add-to-pick pin §2 D4).
        val addRow = addToPickRow(item)
        val addBtn = addRow?.let { (label, _) ->
            android.widget.Button(ctx).apply { text = label; isAllCaps = false; setPadding(0, px(8), 0, 0) }
                .also { col.addView(it) }
        }

        // Add this item (A/V gram with its transcription, highlight, note…) onto a pickings board.
        val pickBtn = android.widget.Button(ctx).apply {
            text = "❝  Add to Pickings"; isAllCaps = false; setPadding(0, px(8), 0, 0)
        }
        col.addView(pickBtn)

        // …or make it something to DO. An annotation you can only look at is a dead end; this
        // turns it into a card on today with its picture riding along, assignable like any other.
        val taskBtn = android.widget.Button(ctx).apply {
            text = "🗒  Make it a task"; isAllCaps = false; setPadding(0, px(8), 0, 0)
        }
        col.addView(taskBtn)

        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        val builder = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setView(scroll)
        // "Go to" takes you to the page/source the item came from.
        builder.setPositiveButton("Go to") { _, _ -> goToSource(item) }
        item.audioPath?.let { path ->
            builder.setNeutralButton("Play") { _, _ -> toggleAudio(path) }
        }
        builder.setNegativeButton("Close", null)
        val dialog = builder.show()
        pickBtn.setOnClickListener { dialog.dismiss(); addLogItemToPickings(item) }
        addBtn?.setOnClickListener { dialog.dismiss(); addRow.second() }
        taskBtn.setOnClickListener { dialog.dismiss(); makeTaskFromLogItem(item) }
    }

    /**
     * Turn a log item into a task card on today.
     *
     * The words become the task's text so it reads in a list, and the item's own picture — a
     * panel card, a photo, an A/V gram's poster — rides along as the card's ink face, so opening
     * it shows the thing itself rather than a summary of it. Its origin goes in `source`, which
     * is the edge back to where it came from.
     */
    private fun makeTaskFromLogItem(item: LogItem) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val today = java.time.LocalDate.now()

            val crop = withContext(Dispatchers.IO) {
                // The item's own picture if it has one; otherwise nothing — a wordless card is
                // worse than a plain one.
                item.imagePath?.takeIf { java.io.File(it).exists() }?.let { path ->
                    runCatching {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(path, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= 900) sample *= 2
                        val bmp = android.graphics.BitmapFactory.decodeFile(
                            path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        ) ?: return@runCatching null
                        val baos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        bmp.recycle()
                        android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    }.getOrNull()
                }
            }

            val text = listOf(item.title, item.body).firstOrNull { it.isNotBlank() }?.trim()
                ?.let { if (it.length > 140) it.take(139).trimEnd() + "…" else it }
                ?: "From the log"

            val task = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                id = "log-" + java.util.UUID.randomUUID().toString().lowercase(),
                kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                text = text,
                date = java.util.Date(),
                crop = crop,
                display = if (crop != null) com.toolsboox.plugin.calendar.da.v2.LedgerItem.Display.INK
                    else com.toolsboox.plugin.calendar.da.v2.LedgerItem.Display.TEXT,
                source = "log",
                stage = "todo"
            )

            // Already there? Say so and stop. Tapping "make a task" twice on the same log item —
            // or on something you'd already written by hand — used to mint a second item with a
            // different id, which nothing downstream could tell from a real second task.
            // null = today's file is there but won't read: nothing was written, and no task is pushed.
            val already: Boolean? = withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                        val day = calendarDayService.loadForWrite(root, today, null, java.util.Locale.getDefault())
                            ?: return@withDay null
                        if (com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe.containsTask(day.ledgerItems, text)) {
                            true
                        } else {
                            day.ledgerItems.add(task)
                            calendarDayService.save(root, today, day)
                            false
                        }
                    }
                }.getOrDefault(false)
            }
            if (already == null) {
                android.widget.Toast.makeText(ctx, "Couldn't make the task — today's page won't read", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!already) {
                withContext(Dispatchers.IO) {
                    runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, task) }
                }
            }

            android.widget.Toast.makeText(
                ctx,
                when {
                    already -> "🗒 Already on today"
                    crop != null -> "🗒 Task made — picture rode along"
                    else -> "🗒 Task made on today"
                },
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Render a card from a log item (A/V gram → its Whisper transcription; else its text) and place
     *  it onto a pickings board via the shared chooser. */
    private fun addLogItemToPickings(item: LogItem) {
        showMessage(getString(R.string.ledger_educate_looking_up), binding.root)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val transcript = item.audioPath?.let {
                    com.toolsboox.plugin.calendar.nw.Transcribe.audio(requireContext(), File(it))
                }
                val head = if (item.origin == LogOrigin.AV) "🎬 ${item.title.ifBlank { "A/V pick" }}" else item.title
                val cardText = listOf(head, transcript ?: item.body).filter { it.isNotBlank() }.joinToString("\n\n")
                    .ifBlank { "A/V pick" }
                com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                    cardText, null, null,
                    com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.Format.SQUARE.w,
                    com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.Format.SQUARE.h)
            }
            val srcDate = java.time.Instant.ofEpochMilli(item.millis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val src = when {
                !item.url.isNullOrBlank() && item.url!!.startsWith("http") -> item.url!!
                item.origin == LogOrigin.PICKING -> "ledger://$srcDate/pickings"
                item.origin == LogOrigin.AV -> "ledger://$srcDate/day"
                else -> ""
            }
            com.toolsboox.plugin.calendar.ot.PickingsPlacement.chooseAndPlace(
                this@ReadingLogFragment, calendarDayService, documentsRoot(), bmp,
                sourceLink = src, sourceLabel = item.title)
        }
    }

    /** Navigate to wherever a log item came from: article link, book, or the day page. */
    private fun goToSource(item: LogItem) {
        val date = item.day ?: java.time.Instant.ofEpochMilli(item.millis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        // A ❖ synthesis row names its page outright — `ledger://<date>/<key>`, the page's own day
        // even when its pick sits on the next one (synth-list-links §5).
        item.url?.takeIf { it.startsWith("ledger://") }?.removePrefix("ledger://")?.let { ref ->
            val d = runCatching { LocalDate.parse(ref.substringBefore('/')) }.getOrNull()
            val k = ref.substringAfter('/', "")
            if (d != null && k.isNotBlank()) { CalendarNavigator.toDayNote(this, d, k); return }
        }
        // A pageKey is the most precise door — the exact note SURFACE of its day (an OCR'd
        // section's page, a planner text's page), or the Text Notes screen for a typed note.
        item.pageKey?.let { pk ->
            if (pk == PAGEKEY_TEXT_NOTES) {
                NavHostFragment.findNavController(this).navigate(R.id.action_to_text_notes)
            } else {
                CalendarNavigator.toDayNote(this, date, pk)
            }
            return
        }
        // A corpus feed hit knows its title but not its URL (the corpus doesn't carry links);
        // the offline feed cache does — look it up there and open the in-pane reader.
        if (item.origin == LogOrigin.FEED && item.url.isNullOrBlank()) {
            openFeedByTitle(item, date); return
        }
        when (item.origin) {
            LogOrigin.PICKING -> CalendarNavigator.toDayNote(this, date, "pickings")
            LogOrigin.AV -> CalendarNavigator.toDayPage(this, date, CalendarDay.DEFAULT_STYLE)
            LogOrigin.BOOK ->
                androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_reader)
            else -> {
                val url = item.url
                if (!url.isNullOrBlank()) {
                    // Open the article inside the Feed Ledger's in-pane reader, ON the passage
                    // (Michael 2026-10-12, the highlights plan: "Log → the passage", one tap) and
                    // in the text it was highlighted in (critique #7: the feed's text, Reader view or
                    // the live page — a passage made in one isn't always in the others).
                    // id = 0 marks a server-less entry (never sent to Miniflux — item.millis
                    // as id could mark a REAL entry read) unless the highlight knows its real
                    // entry; blank content makes the reader load the live page. The line's body
                    // is NEVER the article's content any more — that opened the excerpt alone, as
                    // if it were the whole piece (design §4, ReadingLogFragment:1219).
                    val nav = androidx.navigation.fragment.NavHostFragment.findNavController(this)
                    val app = requireContext().applicationContext
                    lifecycleScope.launch {
                        val (entryId, view, content) = withContext(Dispatchers.IO) {
                            val mark = item.eventId?.let { id ->
                                runCatching {
                                    com.toolsboox.plugin.feeds.ot.ArticleNoteStore.list(app,
                                        com.toolsboox.plugin.feeds.ot.ArticleKey.of(url),
                                        com.toolsboox.plugin.feeds.ot.ArticleKey.clean(url))
                                        .firstOrNull { it.logId == id || it.id == id }
                                }.getOrNull()
                            }
                            val eid = mark?.entryId ?: 0L
                            val feedText = if (eid > 0 && mark?.view == com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED)
                                cachedFeedText(app, eid).orEmpty() else ""
                            Triple(eid, mark?.view, feedText)
                        }
                        com.toolsboox.plugin.feeds.ui.FeedSelection.pendingMarkId = item.eventId
                        com.toolsboox.plugin.feeds.ui.FeedSelection.pendingView = view
                        com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry =
                            com.toolsboox.plugin.feeds.da.FeedEntry(
                                id = entryId, title = item.title, feedTitle = "",
                                url = url, author = null,
                                content = content,
                                publishedAt = java.time.Instant.ofEpochMilli(item.millis).toString(),
                                starred = false
                            )
                        runCatching { nav.navigate(R.id.action_to_feeds) }
                    }
                } else CalendarNavigator.toDayPage(this, date, CalendarDay.DEFAULT_STYLE)
            }
        }
    }

    /** The feed's own text for entry [id] from the offline list caches, or null. */
    private fun cachedFeedText(context: android.content.Context, id: Long): String? = runCatching {
        File(context.filesDir, "feed-cache").listFiles()
            ?.filter { it.isFile && it.name.startsWith("list-") && it.name.endsWith(".json") }
            ?.asSequence()
            ?.mapNotNull { f -> runCatching { org.json.JSONArray(f.readText()) }.getOrNull() }
            ?.flatMap { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            ?.firstOrNull { it.optLong("id") == id }
            ?.optString("content")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Find a corpus-surfaced article's URL in the offline feed cache by title, then open the
     *  in-pane reader on it; falls back to the day page when the cache no longer holds it. */
    private fun openFeedByTitle(item: LogItem, date: LocalDate) {
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                val cacheDir = File(requireContext().filesDir, "feed-cache")
                cacheDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("list-") && it.name.endsWith(".json") }
                    ?.asSequence()
                    ?.mapNotNull { f -> runCatching { org.json.JSONArray(f.readText()) }.getOrNull() }
                    ?.flatMap { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
                    ?.firstOrNull { it.optString("title").trim() == item.title.trim() }
                    ?.let { o ->
                        com.toolsboox.plugin.feeds.da.FeedEntry(
                            id = 0L, title = o.optString("title"),
                            feedTitle = o.optString("feedTitle"), url = o.optString("url"),
                            author = null, content = "",
                            publishedAt = o.optString("publishedAt"), starred = false
                        )
                    }
            }
            if (entry != null && entry.url.isNotBlank()) {
                com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry = entry
                NavHostFragment.findNavController(this@ReadingLogFragment).navigate(R.id.action_to_feeds)
            } else CalendarNavigator.toDayPage(this@ReadingLogFragment, date, CalendarDay.DEFAULT_STYLE)
        }
    }

    /** Play a captured voice memo through the shared player — same transport + modal as everything
     *  else, and it keeps playing if you leave the log. Tapping again while active opens the modal. */
    private fun toggleAudio(path: String) {
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        if (player.isActive) { player.showModal(requireContext()); return }
        if (!File(path).exists()) { showMessage(R.string.reader_capture_failed); return }
        player.startAudio(requireContext(), getString(R.string.reading_log_av_audio), null, null, path)
        player.showModal(requireContext())
    }

    /** A tap on the strip (add-to-pick pin §6): the photo / handwriting full screen, the VIDEO
     *  played (not the audio player), the voice note through the shared transport. */
    private fun openMedia(m: LogMedia) {
        if (!File(m.path).exists()) { showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.NOT_HERE_YET); return }
        when (m.kind) {
            Attachment.Kind.PHOTO -> openImage(m.path)
            Attachment.Kind.VIDEO -> com.toolsboox.plugin.calendar.ot.AvPlayback.play(requireContext(), "video", File(m.path), "", "", 0)
            Attachment.Kind.AUDIO -> toggleAudio(m.path)
        }
    }

    private fun openImage(path: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", File(path)
        )
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onPause() {
        super.onPause()
        // The shared LedgerPlayer intentionally keeps playing after you leave the log.
    }

    override fun onDestroyView() {
        // A debounced search still in flight must not land on a torn-down view.
        searchDebounce.removeCallbacksAndMessages(null)
        searchSeq++
        super.onDestroyView()
    }


    override fun showLoading() {}
    override fun hideLoading() {}
}

/** Preset origin for the annotations log, set from the History menu before navigating. */
object ReadingLogSelection {
    var origin: LogOrigin? = null

    /** Set by the hub's 🔍 Search row: land with the search field focused, keyboard up. */
    var focusSearch = false

    /**
     * Set by the hub's Ask field: land with this already typed and already searching.
     *
     * The quick-search field in the drawer is a way IN to this screen, not a second search engine —
     * it collects the words and hands them over, and this is the handover. Consumed once (cleared
     * on read) for the same reason [focusSearch] is: a sticky value would re-seed the screen every
     * time you came back to it, overwriting whatever you had since typed.
     */
    var seedQuery: String? = null
}
