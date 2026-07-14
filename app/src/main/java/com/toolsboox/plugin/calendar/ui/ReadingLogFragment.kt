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

    /** Widening windows, cycled by the range button. */
    private enum class Range(val label: String, val days: Long?) {
        MONTH("Month", 31), QUARTER("Quarter", 92), YEAR("Year", 366), ALL("All", null)
    }
    private var range = Range.MONTH

    /** Origin filter (null = All). The AV chip is the union of Watch/Listen/AV captures. */
    private var origin: LogOrigin? = null
    private var allItems: List<LogItem> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentReadingLogBinding.bind(view)

        adapter = ReadingEventAdapter(emptyList(), onOpen = ::openItem)
        binding.readingRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.readingRecycler.adapter = adapter
        binding.readingRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        binding.rangeButton.text = range.label
        binding.rangeButton.setOnClickListener {
            range = Range.values()[(range.ordinal + 1) % Range.values().size]
            binding.rangeButton.text = range.label
            load()
        }

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
        lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) { gather(range) }
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

    /** Walk the day files in range, flatten reading events + AV grams to log items. */
    private fun gather(range: Range): List<LogItem> {
        val calendarRoot = File(documentsRoot(), "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val cutoff = range.days?.let { LocalDate.now().minusDays(it) }
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
                if (cutoff != null && dayDate?.isBefore(cutoff) == true) return@forEach
                val day = runCatching { calendarDayService.load(file) }
                    .onFailure { Timber.w(it, "reading-log: skipping ${file.name}") }.getOrNull() ?: return@forEach

                for (e in day.readingEvents) {
                    val o = if (e.kind == ReadingEvent.Kind.BOOK) LogOrigin.BOOK else LogOrigin.READ
                    val meta = listOfNotNull(e.source?.takeIf { it.isNotBlank() }, stamp(e.date)).joinToString(" · ")
                    val body = (e.excerpt?.takeIf { it.isNotBlank() } ?: e.note).orEmpty().trim()
                    out.add(LogItem(o, e.title.ifBlank { getString(R.string.reading_log_untitled) }, meta, body, e.url, e.date.time))
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
