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

    companion object {
        /** The synthesis basket — survives navigation within the session. */
        val basket = mutableListOf<LogItem>()
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

        adapter = ReadingEventAdapter(emptyList(), onOpen = ::openItem, onLong = ::showRhizome)
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
        binding.searchField.doAfterTextChanged {
            val q = it?.toString().orEmpty()
            if (q != searchQuery) { searchQuery = q; load() }
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
        binding.readingPill.bringToFront()
        makeDraggable(binding.readingGrip, binding.readingPill, "reading_pill")

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
                val cd = runCatching { calendarDayService.load(root, anchor, null, loc) }.getOrNull()
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
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
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        // A live search scans the whole corpus (all time); browsing honors the period window.
        val searching = searchQuery.isNotBlank()
        val start = if (searching) null else windowStart()
        val end = if (searching) null else windowEnd()
        lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) { gather(start, end) }
            binding.progress.visibility = View.INVISIBLE
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = searchQuery.trim().lowercase()
        val filtered = allItems.filter { item ->
            (origins.isEmpty() || item.origin in origins) &&
                (!starredOnly || item.starred) &&
                (q.isEmpty() ||
                    item.title.lowercase().contains(q) ||
                    item.meta.lowercase().contains(q) ||
                    item.body.lowercase().contains(q))
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

    /** Export the currently-shown highlights & annotations as Markdown or CSV. */
    private fun promptExport() {
        val items = lastShown
        if (items.isEmpty()) { showMessage(getString(R.string.reading_log_empty)); return }
        AlertDialog.Builder(requireContext())
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
                // times. One card per article URL per day; the richest (note/excerpt)
                // absorbs the others' star flag. Books keep every highlight.
                val mergedByUrl = LinkedHashMap<String, ReadingEvent>()
                val passthrough = mutableListOf<ReadingEvent>()
                for (e in day.readingEvents) {
                    val u = e.url
                    // Correspondence events (source starts with "↩", wire kind stays ARTICLE
                    // for cross-platform decode) are their own rows, never collapsed into the
                    // star they answer.
                    if (e.kind != ReadingEvent.Kind.ARTICLE || u.isNullOrBlank() ||
                        e.source?.startsWith("↩") == true) { passthrough.add(e); continue }
                    val prev = mergedByUrl[u]
                    if (prev == null) { mergedByUrl[u] = e; continue }
                    val richer = ((e.note ?: "") + (e.excerpt ?: "")).length >
                        ((prev.note ?: "") + (prev.excerpt ?: "")).length
                    val keep = if (richer) e else prev
                    val other = if (richer) prev else e
                    mergedByUrl[u] = keep.copy(
                        starred = keep.starred || other.starred,
                        published = keep.published ?: other.published
                    )
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
                    val body = (e.excerpt?.takeIf { it.isNotBlank() } ?: e.note).orEmpty().trim()
                    // Captured media rides on the event's attachments — show a photo thumb / play a memo.
                    val photo = e.attachments?.firstOrNull { it.kind == Attachment.Kind.PHOTO }
                    val audio = e.attachments?.firstOrNull { it.kind == Attachment.Kind.AUDIO }
                    put(LogItem(o, e.title.ifBlank { getString(R.string.reading_log_untitled) }, meta, body,
                        e.url, e.date.time, imagePath = photo?.let { attachmentPath(it) },
                        audioPath = audio?.let { attachmentPath(it) }, starred = e.starred))
                }

                val fallback = dayDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L

                // AV grams are the daily video/audio "note to self" recordings — all AV.
                for (a in day.avGrams) {
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
                for (img in day.imageElements) {
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
                    val day = calendarDayService.load(root, today, null, java.util.Locale.getDefault())
                    day.avGrams.add(att)
                    calendarDayService.save(root, today, day)
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
        com.toolsboox.plugin.calendar.ot.SynthEngines.pick(ctx, "Synthesize ${basket.size} items") { engine ->
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
                    is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                        val today = LocalDate.now()
                        val day = calendarDayService.load(documentsRoot(), today, null, java.util.Locale.getDefault())
                        val y = (day.textElements.filter { it.pageKey == "synthesize" }
                            .maxOfOrNull { it.y } ?: 60f) + 140f
                        day.textElements.add(com.toolsboox.da.TextElement(
                            x = 80f, y = y.coerceAtMost(1600f), width = 1200f, height = 60f,
                            text = "${engine.name}\n\n${res.answer.trim()}", pageKey = "synthesize"))
                        calendarDayService.save(documentsRoot(), today, day)
                        withContext(Dispatchers.Main) {
                            basket.clear()
                            android.widget.Toast.makeText(ctx, "On today's Synthesize page", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err -> withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "⚠ ${res.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
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
        // The synthesis BASKET — gather a few items from anywhere in history, then press them
        // together through an engine. The result lands on today's Synthesize page.
        val inBasket = basket.any { it.millis == item.millis && it.title == item.title }
        rows.add((if (inBasket) "⊖  Remove from synthesis basket" else "⊕  Add to synthesis basket (${basket.size})") to {
            if (inBasket) basket.removeAll { it.millis == item.millis && it.title == item.title }
            else basket.add(item)
            android.widget.Toast.makeText(ctx, "${basket.size} in the basket", android.widget.Toast.LENGTH_SHORT).show()
        })
        if (basket.isNotEmpty()) {
            rows.add("⚗  Synthesize ${basket.size} selected…" to { synthesizeBasket() })
            rows.add("∅  Empty the basket" to {
                basket.clear()
                android.widget.Toast.makeText(ctx, "Basket emptied", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        rows.add("❝  Send to today's Pickings" to {
            lifecycleScope.launch(Dispatchers.IO) {
                val today = LocalDate.now()
                val day = calendarDayService.load(documentsRoot(), today, null, java.util.Locale.getDefault())
                day.textElements.add(com.toolsboox.da.TextElement(
                    x = 80f, y = 120f, width = 760f, height = 60f,
                    text = item.title.removePrefix("⭐ ") + (if (item.body.isNotBlank()) "\n" + item.body else ""),
                    pageKey = "pickings"
                ))
                calendarDayService.save(documentsRoot(), today, day)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "On today's Pickings", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        AlertDialog.Builder(ctx)
            .setTitle(item.title.take(60).ifBlank { "Item" })
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton("Close", null)
            .show()
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

        // Add this item (A/V gram with its transcription, highlight, note…) onto a pickings board.
        val pickBtn = android.widget.Button(ctx).apply {
            text = "❝  Add to Pickings"; isAllCaps = false; setPadding(0, px(8), 0, 0)
        }
        col.addView(pickBtn)

        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        val builder = AlertDialog.Builder(ctx).setView(scroll)
        // "Go to" takes you to the page/source the item came from.
        builder.setPositiveButton("Go to") { _, _ -> goToSource(item) }
        item.audioPath?.let { path ->
            builder.setNeutralButton("Play") { _, _ -> toggleAudio(path) }
        }
        builder.setNegativeButton("Close", null)
        val dialog = builder.show()
        pickBtn.setOnClickListener { dialog.dismiss(); addLogItemToPickings(item) }
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
                val head = if (item.origin == LogOrigin.AV) "🎬 ${item.title.ifBlank { "A/V gram" }}" else item.title
                val cardText = listOf(head, transcript ?: item.body).filter { it.isNotBlank() }.joinToString("\n\n")
                    .ifBlank { "A/V gram" }
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
        val date = java.time.Instant.ofEpochMilli(item.millis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        when (item.origin) {
            LogOrigin.PICKING -> CalendarNavigator.toDayNote(this, date, "pickings")
            LogOrigin.AV -> CalendarNavigator.toDayPage(this, date, CalendarDay.DEFAULT_STYLE)
            LogOrigin.BOOK ->
                androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_reader)
            else -> {
                val url = item.url
                if (!url.isNullOrBlank()) {
                    // Open the article inside the Feed Ledger's in-pane reader.
                    // id = 0 marks a server-less entry (never sent to Miniflux — item.millis
                    // as id could mark a REAL entry read); blank content makes the reader
                    // load the live page. The old placeholder body ("Opening from your
                    // Ledger…") was treated as real content and just… sat there.
                    com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry =
                        com.toolsboox.plugin.feeds.da.FeedEntry(
                            id = 0L, title = item.title, feedTitle = "",
                            url = url, author = null,
                            content = item.body,
                            publishedAt = java.time.Instant.ofEpochMilli(item.millis).toString(),
                            starred = false
                        )
                    androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_feeds)
                } else CalendarNavigator.toDayPage(this, date, CalendarDay.DEFAULT_STYLE)
            }
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

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    override fun showLoading() {}
    override fun hideLoading() {}
}

/** Preset origin for the annotations log, set from the History menu before navigating. */
object ReadingLogSelection {
    var origin: LogOrigin? = null
}
