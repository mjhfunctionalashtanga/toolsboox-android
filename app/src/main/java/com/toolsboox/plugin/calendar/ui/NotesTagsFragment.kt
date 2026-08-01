package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentNotesTagsBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.fi.CalendarPatternService
import com.toolsboox.plugin.calendar.ot.LedgerTags
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/**
 * **Notes & Tags** — "it's like Feeds", but the corpus is your own days: a period-filtered LIST
 * of the days inside a window (a week / month / quarter / year around an anchor date) that carry
 * note content and/or handwritten `#tags`. Each row shows the day and that day's tags; tapping it
 * jumps straight to that day's Notes page (page "0").
 *
 * The **Almanac navigator is the filter** (per Michael): the same 1404×140 strip the Feed and the
 * Reading-log carry ([CalendarNavBarHost]) — its period slots re-scope the list (week → month →
 * quarter → year widens; stepping down narrows) and the carets walk the window; the level pill
 * cycles the same ladder. The header spells out the current period and level.
 *
 * Reached from the hub (Notes → 🗓 Notes & Tags) and — the point of it — from the DAY page: on a
 * notes surface the almanac strip's Week slot opens THIS list scoped to the week instead of the
 * Week almanac (see [CalendarDayFragment]'s navigator handler + [open]).
 */
@AndroidEntryPoint
class NotesTagsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: CalendarPatternService

    override val view = R.layout.fragment_notes_tags

    private lateinit var binding: FragmentNotesTagsBinding
    private var navBar: CalendarNavBarHost? = null

    /** The almanac ladder level this list is scoped to. Day is deliberately absent — this surface
     *  is about a PERIOD of days, so the narrowest window it offers is a week. */
    private enum class Level(val g: String, val label: String) {
        WEEK("week", "Week"), MONTH("month", "Month"), QUARTER("quarter", "Quarter"), YEAR("year", "Year");

        fun next(): Level = entries[(ordinal + 1) % entries.size]

        companion object {
            fun of(g: String?): Level = entries.firstOrNull { it.g == g } ?: WEEK
        }
    }

    private var level = Level.WEEK

    /** Anchor date inside the currently-shown window; the carets shift it by one level unit. */
    private var anchor: LocalDate = LocalDate.now()

    private val weekFields get() = WeekFields.of(Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_ANCHOR = "anchorDate"
        const val ARG_LEVEL = "level"

        /** Open the list scoped to [anchor] at [level] ("week"/"month"/"quarter"/"year"). */
        fun open(fragment: ScreenFragment, anchor: LocalDate, level: String) {
            NavHostFragment.findNavController(fragment).navigate(
                R.id.action_to_notes_tags,
                bundleOf(ARG_ANCHOR to anchor.toString(), ARG_LEVEL to level)
            )
        }
    }

    /** One day that made the cut, with the tags seen on it and whether it holds note ink/content. */
    private data class Row(val date: LocalDate, val tags: List<String>, val hasNotes: Boolean)

    // MARK: - Window

    /** Inclusive start of the current window. */
    private fun windowStart(): LocalDate = when (level) {
        Level.WEEK -> anchor.with(weekFields.dayOfWeek(), 1)
        Level.MONTH -> anchor.withDayOfMonth(1)
        Level.QUARTER -> anchor.withDayOfMonth(1).withMonth((anchor.monthValue - 1) / 3 * 3 + 1)
        Level.YEAR -> anchor.withDayOfYear(1)
    }

    /** Exclusive end of the current window. */
    private fun windowEnd(): LocalDate = when (level) {
        Level.WEEK -> windowStart().plusWeeks(1)
        Level.MONTH -> windowStart().plusMonths(1)
        Level.QUARTER -> windowStart().plusMonths(3)
        Level.YEAR -> windowStart().plusYears(1)
    }

    private fun shiftAnchor(dir: Int) {
        anchor = when (level) {
            Level.WEEK -> anchor.plusWeeks(dir.toLong())
            Level.MONTH -> anchor.plusMonths(dir.toLong())
            Level.QUARTER -> anchor.plusMonths(3L * dir)
            Level.YEAR -> anchor.plusYears(dir.toLong())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentNotesTagsBinding.bind(view)

        // Honour the anchor + level handed in (from the day page's almanac interception, or the hub).
        arguments?.getString(ARG_ANCHOR)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull()?.let { anchor = it } }
        arguments?.getString(ARG_LEVEL)?.let { level = Level.of(it) }

        // The REAL Almanac navigator (same strip as the feed / Reading-log): a period slot re-scopes
        // the list to that level in place, the carets walk the window, and nothing navigates away.
        navBar = CalendarNavBarHost(
            requireContext(), binding.navigatorImageView, this,
            onStepDay = { d -> val dir = if (d.isBefore(anchor)) -1 else 1; shiftAnchor(dir); renderNav(); load() },
            onSelectPeriod = { g, d ->
                // Day narrows to the week (the surface's narrowest window); the rest map straight across.
                level = Level.of(if (g == "day") "week" else g)
                anchor = d; renderNav(); load()
            }
        )

        // ▦ hub, top-left as on every surface — the same directory accordion the feed carries.
        binding.gotoButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }

        // The level pill cycles the ladder: Week → Month → Quarter → Year (widen), wrapping back.
        binding.levelButton.setOnClickListener { level = level.next(); renderNav(); load() }

        // Floating nav pill: ‹ page-up · ☀ today-then-menu · page-down ›.
        binding.notesPageUp.setOnClickListener {
            binding.notesScroll.smoothScrollBy(0, -(binding.notesScroll.height * 9 / 10))
        }
        binding.notesPageDown.setOnClickListener {
            binding.notesScroll.smoothScrollBy(0, binding.notesScroll.height * 9 / 10)
        }
        binding.notesGoto.setOnClickListener {
            // "Home" = this week around today AND unfiltered-onward; the first tap returns there,
            // a second (already home) opens the section menu (the unified feed-carrot behavior).
            if (level == Level.WEEK && anchor == LocalDate.now()) {
                showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
            } else {
                level = Level.WEEK; anchor = LocalDate.now(); renderNav(); load()
            }
        }
        // The floating pill and the header ☰ retire into the rail: ☰ Hub is the same accordion
        // door, the level cycle wears the current level's initial (W/M/Q/Y — re-dressed on each
        // cycle), and the ‹ ☀ › trio ride as icons.
        binding.notesPill.visibility = View.GONE
        binding.gotoButton.visibility = View.GONE
        setupActionRail(
            binding.notesRail, "notes_tags",
            actions = { listOf(
                com.toolsboox.ot.TuckPanel.Item(0, "Level: ${level.label}",
                    glyph = level.label.take(1)) {
                    binding.levelButton.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up, "Page up") {
                    binding.notesPageUp.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today") {
                    binding.notesGoto.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down, "Page down") {
                    binding.notesPageDown.performClick()
                }
            ) }
        )

        renderNav()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** Redraw the Almanac navigator strip for the current anchor, with the level's slot focal. */
    private fun renderNav() {
        binding.levelButton.text = level.label
        // The rail's level button wears the level's initial — keep it current with the cycle.
        rebuildActionRail("notes_tags")
        binding.periodText.text = periodLabel()
        lifecycleScope.launch {
            val root = documentsRoot()
            val loc = Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, anchor, null, loc) }.getOrNull()
                    ?: CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                val p = runCatching { calendarPatternService.load(root, anchor, loc) }.getOrNull()
                cd to p
            }
            pat?.let { navBar?.render(day, it) }
            navBar?.setGranularity(level.g)   // the level's slot is the focal one on the strip
        }
    }

    /** "Week · Jul 21 – Jul 27, 2026" and so on, spelling out the window the list is showing. */
    private fun periodLabel(): String {
        val start = windowStart()
        val last = windowEnd().minusDays(1)
        val loc = Locale.getDefault()
        return when (level) {
            Level.WEEK -> {
                val ms = start.month.getDisplayName(TextStyle.SHORT, loc)
                val me = last.month.getDisplayName(TextStyle.SHORT, loc)
                "Week · $ms ${start.dayOfMonth} – $me ${last.dayOfMonth}, ${last.year}"
            }
            Level.MONTH -> "Month · ${start.month.getDisplayName(TextStyle.FULL, loc)} ${start.year}"
            Level.QUARTER -> "Quarter · Q${(start.monthValue - 1) / 3 + 1} ${start.year}"
            Level.YEAR -> "Year · ${start.year}"
        }
    }

    // MARK: - Load

    private fun load() {
        val start = windowStart()
        val end = windowEnd()
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { gather(start, end) }
            if (!isAdded) return@launch
            binding.progress.visibility = View.INVISIBLE
            render(rows)
        }
    }

    /**
     * Scan the window cheaply: one directory walk collects the day files that exist inside it, and
     * the tag occurrence store (read once) gives every day's #tags. A day makes the list when it
     * has tags OR note content — its strokes / placed cards / typed text on any note page.
     */
    private fun gather(start: LocalDate, end: LocalDate): List<Row> {
        val ctx = requireContext().applicationContext

        // #tags per day in the window — LedgerTags.list() reads the whole occurrence store once.
        val tagsByDate = HashMap<LocalDate, LinkedHashSet<String>>()
        for (info in LedgerTags.list(ctx)) {
            for ((d, _) in info.occurrences) {
                if (!d.isBefore(start) && d.isBefore(end)) {
                    tagsByDate.getOrPut(d) { LinkedHashSet() }.add(info.tag)
                }
            }
        }

        // The day files that exist inside the window (one walk; only these get loaded).
        val dayFiles = HashMap<LocalDate, File>()
        val calendarRoot = File(documentsRoot(), "calendar")
        if (calendarRoot.exists()) {
            calendarRoot.walkTopDown()
                .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                .forEach { f ->
                    val d = dateOf(f.name) ?: return@forEach
                    if (!d.isBefore(start) && d.isBefore(end)) dayFiles[d] = f
                }
        }

        val candidates = (tagsByDate.keys + dayFiles.keys).toSortedSet(compareByDescending { it })
        val out = mutableListOf<Row>()
        for (date in candidates) {
            val tags = tagsByDate[date]?.toList().orEmpty()
            val hasNotes = dayFiles[date]?.let { f ->
                val day = runCatching { calendarDayService.load(f) }
                    .onFailure { Timber.w(it, "notes-tags: skipping ${f.name}") }.getOrNull()
                day != null && dayHasNotes(day)
            } ?: false
            if (tags.isNotEmpty() || hasNotes) out.add(Row(date, tags, hasNotes))
        }
        return out
    }

    /** A day "has note content" when any note page holds ink, a placed card, or typed text. */
    private fun dayHasNotes(day: CalendarDay): Boolean =
        day.noteStrokes.values.any { it.isNotEmpty() } ||
            day.imageElements.isNotEmpty() ||
            day.textElements.any { it.text.isNotBlank() }

    /** Parse the day from a `day-YYYY-MM-DD-v2.json` filename. */
    private fun dateOf(name: String): LocalDate? = runCatching {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    // MARK: - Render

    private fun render(rows: List<Row>) {
        binding.periodText.text = periodLabel()
        binding.notesColumn.removeAllViews()
        if (rows.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            return
        }
        binding.emptyText.visibility = View.GONE
        for (row in rows) binding.notesColumn.addView(rowView(row))
        com.toolsboox.ot.ReadingSize.apply(binding.notesScroll)
    }

    /** One list row: the formatted date over that day's tags; tapping jumps to the day's Notes page. */
    private fun rowView(row: Row): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(12), dp(6), dp(12))
            isClickable = true
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, android.R.drawable.list_selector_background)
            setOnClickListener { CalendarNavigator.toDayNote(this@NotesTagsFragment, row.date, "0") }
        }
        box.addView(TextView(ctx).apply {
            text = formatDate(row.date)
            textSize = 17f; setTextColor(0xFF000000.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val sub = if (row.tags.isNotEmpty()) row.tags.joinToString("  ") { "#$it" }
        else "notes"
        box.addView(TextView(ctx).apply {
            text = sub
            textSize = 14f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(3), 0, 0)
        })
        // A hairline divider, e-ink friendly (matches the RecyclerView DividerItemDecoration look).
        val divider = View(ctx).apply {
            setBackgroundColor(0xFFDDDDDD.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(box)
            addView(divider)
        }
        return wrap
    }

    private fun formatDate(date: LocalDate): String {
        val loc = Locale.getDefault()
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc)
        val mon = date.month.getDisplayName(TextStyle.SHORT, loc)
        return "$dow · $mon ${date.dayOfMonth}, ${date.year}"
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
