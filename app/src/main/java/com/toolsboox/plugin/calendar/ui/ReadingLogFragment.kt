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
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
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

    override val view = R.layout.fragment_reading_log

    private lateinit var binding: FragmentReadingLogBinding
    private lateinit var adapter: ReadingEventAdapter

    /** The period level, chosen from the almanac-style top bar; ‹ › page within it. */
    private enum class Range(val label: String) {
        DAY("Day"), WEEK("Week"), MONTH("Month"), QUARTER("Quarter"), YEAR("Year"), ALL("All")
    }
    private var range = Range.MONTH

    /** Anchor date inside the currently-shown window; ‹ › shift it by one level unit. */
    private var anchor: LocalDate = LocalDate.now()

    private val weekFields get() = WeekFields.of(java.util.Locale.getDefault())

    /** Origin filter (null = All). The AV chip is the union of Watch/Listen/AV captures. */
    private var origin: LogOrigin? = null
    private var allItems: List<LogItem> = emptyList()

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
    /** Human title for the current window, shown between the carets like the almanac bar. */
    private fun periodTitle(): String {
        val s = windowStart() ?: return getString(R.string.reading_log_range_all)
        fun fmt(skeleton: String) = DateFormat.format(
            DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), skeleton),
            Date.from(s.atStartOfDay(ZoneId.systemDefault()).toInstant())
        ).toString()
        return when (range) {
            Range.DAY -> fmt("EEE MMM d yyyy")
            Range.WEEK -> "Week ${s.get(weekFields.weekOfWeekBasedYear())} · ${s.year}"
            Range.MONTH -> fmt("MMMM yyyy")
            Range.QUARTER -> "Q${(s.monthValue - 1) / 3 + 1} ${s.year}"
            Range.YEAR -> "${s.year}"
            Range.ALL -> getString(R.string.reading_log_range_all)
        }
    }
    private fun updatePeriodBar() {
        binding.periodTitle.text = periodTitle()
        val bounded = range != Range.ALL
        binding.prevButton.visibility = if (bounded) View.VISIBLE else View.INVISIBLE
        binding.nextButton.visibility = if (bounded) View.VISIBLE else View.INVISIBLE
        // Highlight the active level chip in the almanac-style ladder.
        for (i in 0 until binding.levelRow.childCount) {
            val chip = binding.levelRow.getChildAt(i) as? android.widget.TextView ?: continue
            val active = chip.tag == range
            chip.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            chip.paintFlags = if (active) chip.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                else chip.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    /** Build the Day · Week · Month · Quarter · Year · All ladder into the level row. */
    private fun buildLevelChips() {
        val row = binding.levelRow
        row.removeAllViews()
        for (r in Range.values()) {
            val chip = android.widget.TextView(requireContext()).apply {
                text = r.label; tag = r; textSize = 15f
                setTextColor(0xFF000000.toInt())
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, p / 2, p, p / 2)
                setOnClickListener {
                    range = r; anchor = LocalDate.now()
                    updatePeriodBar(); load()
                }
            }
            row.addView(chip)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentReadingLogBinding.bind(view)

        // Honour a preset origin chosen from the History menu (one-shot).
        ReadingLogSelection.origin?.let { origin = it; ReadingLogSelection.origin = null }

        adapter = ReadingEventAdapter(emptyList(), onOpen = ::openItem)
        binding.readingRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.readingRecycler.adapter = adapter
        binding.readingRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        // Almanac-style top bar: the Day/Week/Month/Quarter/Year/All ladder + period pager.
        buildLevelChips()
        binding.prevButton.setOnClickListener { shiftAnchor(-1); updatePeriodBar(); load() }
        binding.nextButton.setOnClickListener { shiftAnchor(1); updatePeriodBar(); load() }

        binding.originButton.text = originLabel()
        binding.originButton.setOnClickListener {
            // Cycle: All → Book → Read → Watch → Listen → Picking → AV → All
            val order = listOf<LogOrigin?>(null) + LogOrigin.values().toList()
            origin = order[(order.indexOf(origin) + 1) % order.size]
            binding.originButton.text = originLabel()
            applyFilter()
        }

        binding.gotoButton.setOnClickListener {
            NavHostFragment.findNavController(this).navigate(R.id.action_to_calendar_day)
        }

        updatePeriodBar()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun originLabel(): String = origin?.label ?: getString(R.string.reading_log_origin_all)

    private fun load() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        val start = windowStart(); val end = windowEnd()
        lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) { gather(start, end) }
            binding.progress.visibility = View.INVISIBLE
            applyFilter()
        }
    }

    private fun applyFilter() {
        val o = origin
        val shown = if (o == null) allItems else allItems.filter { it.origin == o }
        adapter.submit(shown)
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
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

                for (e in day.readingEvents) {
                    val o = if (e.kind == ReadingEvent.Kind.BOOK) LogOrigin.BOOK else LogOrigin.READ
                    val meta = listOfNotNull(e.source?.takeIf { it.isNotBlank() }, stamp(e.date)).joinToString(" · ")
                    val body = (e.excerpt?.takeIf { it.isNotBlank() } ?: e.note).orEmpty().trim()
                    out.add(LogItem(o, e.title.ifBlank { getString(R.string.reading_log_untitled) }, meta, body, e.url, e.date.time, e.image))
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
                    out.add(LogItem(LogOrigin.AV, label, meta, "", null, a.date?.time ?: fallback))
                }

                // Pickings — the typed quotes on the day's Pickings page.
                for (t in day.textElements.filter { it.pageKey == "pickings" && it.text.isNotBlank() }) {
                    out.add(LogItem(LogOrigin.PICKING, t.text.trim(), stamp(Date(fallback)), "", null, fallback))
                }

                // Intake — links filed to Read / Watch / Listen / Educate (MichaelFilter),
                // from the day's intake sidecar. One item per typed line.
                dayDate?.let { d ->
                    val intake = IntakePageStore.load(ctx, d)
                    for ((kind, o) in intakeKinds) {
                        intake.typedFor(kind).lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                            val url = ShareTextParser.extractUrls(line).firstOrNull()
                            out.add(LogItem(o, line, stamp(Date(fallback)), "", url, fallback))
                        }
                    }
                }
            }
        return out.sortedByDescending { it.millis }
    }

    private fun mmss(seconds: Double): String {
        val s = seconds.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Parse the day from a `day-YYYY-MM-DD-v2.json` filename. */
    private fun dateOf(name: String): LocalDate? = runCatching {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    /** Anything with a link opens it; book highlights / grams / pickings show a hint. */
    private fun openItem(item: LogItem) {
        val url = item.url
        if (!url.isNullOrBlank()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } else {
            showMessage(getString(R.string.reading_log_open_hint, item.title))
        }
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
