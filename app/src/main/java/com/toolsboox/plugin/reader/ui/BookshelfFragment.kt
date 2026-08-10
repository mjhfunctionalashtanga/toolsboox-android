package com.toolsboox.plugin.reader.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.toolsboox.R
import com.toolsboox.databinding.FragmentBookshelfBinding
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import androidx.navigation.fragment.findNavController
import com.toolsboox.plugin.calendar.ui.CalendarNavBarHost
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/**
 * THE SHELF — every book you have, as covers, filtered by when you read them.
 *
 * Michael's punchlist №6, in two parts. First: "All Books surface with pictures of books and an add
 * option" — because "All books" used to navigate to the reader holding whatever was last open,
 * which is not a shelf and not what the words say. Second, on the shape: "use the almanac nav at
 * the top of the bookshelf and use that to filter books opened on that filtered period at the top,
 * then another listing order."
 *
 * So the page has two bands, and the order between them is the argument:
 *
 *  • READ THIS <period> — what you actually opened in the window the almanac is showing, most
 *    recent first. This is the band that answers "where was I", which is the question you have
 *    almost every time you come to a shelf.
 *  • EVERYTHING — the whole library beneath it, grouped by the folders it already lives in.
 *
 * A book can appear in both, deliberately. The top band is not a filter over the bottom one; it is
 * a different question about the same shelf, and hiding a book from the library because you read it
 * on Tuesday would be strange.
 *
 * Modelled on [com.toolsboox.plugin.calendar.ui.NotesTagsFragment] down to the layout skeleton: the
 * same almanac strip, rail and paging pill. A shelf and a note list are different contents in one
 * surface grammar.
 */
@AndroidEntryPoint
class BookshelfFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_bookshelf

    private lateinit var binding: FragmentBookshelfBinding
    private var navBar: CalendarNavBarHost? = null

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    /** The almanac ladder this shelf scopes to. Day is present here, unlike Notes & Tags: "what did
     *  I read today" is a real question about a shelf in a way "what did I write today" is not,
     *  because the day page already answers the second one. */
    private enum class Level(val g: String, val label: String) {
        DAY("day", "Day"), WEEK("week", "Week"), MONTH("month", "Month"), YEAR("year", "Year");

        fun next(): Level = entries[(ordinal + 1) % entries.size]

        companion object {
            fun of(g: String?): Level = entries.firstOrNull { it.g == g } ?: WEEK
        }
    }

    private var level = Level.WEEK
    private var anchor: LocalDate = LocalDate.now()

    private val weekFields get() = WeekFields.of(Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentBookshelfBinding.bind(view)

        navBar = CalendarNavBarHost(
            requireContext(), binding.navigatorImageView, this,
            onStepDay = { d: LocalDate ->
                shiftAnchor(if (d.isBefore(anchor)) -1 else 1); renderNav(); load()
            },
            onSelectPeriod = { g: String, d: LocalDate ->
                level = Level.of(g); anchor = d; renderNav(); load()
            }
        )

        binding.gotoButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        binding.levelButton.setOnClickListener { level = level.next(); renderNav(); load() }

        binding.shelfPageUp.setOnClickListener {
            binding.shelfScroll.smoothScrollBy(0, -(binding.shelfScroll.height * 9 / 10))
        }
        binding.shelfPageDown.setOnClickListener {
            binding.shelfScroll.smoothScrollBy(0, binding.shelfScroll.height * 9 / 10)
        }
        binding.shelfPill.visibility = View.GONE
        binding.gotoButton.visibility = View.GONE

        setupActionRail(
            binding.shelfRail, "bookshelf",
            actions = { listOf(
                com.toolsboox.ot.TuckPanel.Item(0, "Level: ${level.label}", glyph = level.label.take(1)) {
                    level = level.next(); renderNav(); load()
                },
                // THE ADD OPTION he asked for, in three flavours, because "add a book" honestly
                // means three different acts: one off this device, one off a catalog, and — the
                // one that adds the most books per tap — pointing at a folder full of them.
                com.toolsboox.ot.TuckPanel.Item(0, "Add a book", glyph = "＋") { addBook() },
                com.toolsboox.ot.TuckPanel.Item(0, "Catalog", glyph = "🌐") { openCatalog() },
                com.toolsboox.ot.TuckPanel.Item(0, "Books folder", glyph = "🗂") { chooseFolder() },
                com.toolsboox.ot.TuckPanel.Item(0, "Today", glyph = "☀") {
                    level = Level.WEEK; anchor = LocalDate.now(); renderNav(); load()
                },
            ) },
            hub = { showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this)) }
        )

        binding.titleText.text = "Bookshelf"
        renderNav()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun showLoading() { binding.progress.visibility = View.VISIBLE }

    override fun hideLoading() { binding.progress.visibility = View.GONE }

    // ── The three ways to add ─────────────────────────────────────────────────────────────────

    private val importBook = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                val display = queryDisplayName(uri) ?: "Imported book.epub"
                val ok = BookshelfSource.writeInto(requireContext(), display) { out ->
                    requireContext().contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
                }
                if (ok) display else null
            }
            if (!isAdded) return@launch
            showMessage(if (name != null) "On the shelf · $name" else "Couldn't add that one.")
            load()
        }
    }

    private val pickFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        BookshelfSource.declare(requireContext(), uri)
        load()
    }

    /**
     * ＋ is a door to ALL the ways of adding, not just the file picker.
     *
     * Michael, 2026-08-08: "OPDS in bookshelf needs access thru settings, the '+' sign, and from
     * other doors." He is right about the principle and it is worth stating: a person who wants a
     * book does not know yet whether it is on this device, in the catalog, or in a folder they
     * have not pointed at. Making them pick the METHOD before they can see the options is asking
     * them to answer a question they came here to have answered.
     */
    private fun addBook() {
        showIconMenu("Add a book", listOf(
            "📄  From this device…" to {
                importBook.launch(arrayOf(
                    "application/epub+zip", "application/pdf",
                    "application/x-mobipocket-ebook", "application/zip", "audio/*", "*/*"
                ))
            },
            "🌐  From the catalog…" to { openCatalog() },
            "🗂  Point at a folder…" to { chooseFolder() },
        ))
    }

    private fun chooseFolder() {
        runCatching { pickFolder.launch(BookshelfSource.pickIntent()) }
    }

    /**
     * The catalog, opened from the shelf.
     *
     * It still LIVES in the reader — one implementation of "pull a book down" — but sending
     * someone to another screen and telling them which menu to find was not a door, it was
     * directions. The flag makes the reader open the browser on arrival.
     */
    private fun openCatalog() {
        requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
            .putBoolean("open_catalog_on_arrival", true).apply()
        runCatching { findNavController().navigate(R.id.action_to_reader) }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    // ── Nav ───────────────────────────────────────────────────────────────────────────────────

    private fun shiftAnchor(dir: Int) {
        anchor = when (level) {
            Level.DAY -> anchor.plusDays(dir.toLong())
            Level.WEEK -> anchor.plusWeeks(dir.toLong())
            Level.MONTH -> anchor.plusMonths(dir.toLong())
            Level.YEAR -> anchor.plusYears(dir.toLong())
        }
    }

    private fun windowStart(): LocalDate = when (level) {
        Level.DAY -> anchor
        Level.WEEK -> anchor.with(weekFields.dayOfWeek(), 1)
        Level.MONTH -> anchor.withDayOfMonth(1)
        Level.YEAR -> anchor.withDayOfYear(1)
    }

    private fun windowEnd(): LocalDate = when (level) {
        Level.DAY -> anchor
        Level.WEEK -> windowStart().plusDays(6)
        Level.MONTH -> windowStart().plusMonths(1).minusDays(1)
        Level.YEAR -> windowStart().plusYears(1).minusDays(1)
    }

    private fun periodLabel(): String {
        val start = windowStart()
        val last = windowEnd()
        val loc = Locale.getDefault()
        return when (level) {
            Level.DAY -> "Day · ${start.month.getDisplayName(TextStyle.SHORT, loc)} ${start.dayOfMonth}, ${start.year}"
            Level.WEEK -> {
                val ms = start.month.getDisplayName(TextStyle.SHORT, loc)
                val me = last.month.getDisplayName(TextStyle.SHORT, loc)
                "Week · $ms ${start.dayOfMonth} – $me ${last.dayOfMonth}, ${last.year}"
            }
            Level.MONTH -> "Month · ${start.month.getDisplayName(TextStyle.FULL, loc)} ${start.year}"
            Level.YEAR -> "Year · ${start.year}"
        }
    }

    private fun renderNav() {
        binding.levelButton.text = level.label
        rebuildActionRail("bookshelf")
        binding.periodText.text = periodLabel()
        lifecycleScope.launch {
            val root = documentsRoot()
            val loc = Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, anchor, null, loc) }.getOrNull()
                    ?: CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                cd to runCatching { calendarPatternService.load(root, anchor, loc) }.getOrNull()
            }
            pat?.let { navBar?.render(day, it) }
            navBar?.setGranularity(level.g)
        }
    }

    // ── The shelf ─────────────────────────────────────────────────────────────────────────────

    private fun load() {
        val ctx = requireContext()
        binding.shelfColumn.removeAllViews()
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { BookshelfSource.list(ctx) }
            if (!isAdded) return@launch
            val read = withContext(Dispatchers.IO) {
                BookOpens.openedBetween(ctx, all, windowStart(), windowEnd())
            }
            if (!isAdded) return@launch
            binding.progress.visibility = View.GONE

            if (all.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text =
                    "No books yet.\n\nAdd one with ＋, pull from the 🌐 catalog, " +
                        "or point 🗂 at the folder your library already lives in."
                return@launch
            }
            binding.emptyText.visibility = View.GONE

            if (read.isNotEmpty()) {
                binding.shelfColumn.addView(heading("Read this ${level.label.lowercase()} · ${read.size}"))
                binding.shelfColumn.addView(grid(read))
            }
            // Everything, by the folders the library already keeps. `folder` is "" at the shelf
            // root, which sorts first and reads as the shelf itself rather than as a nameless group.
            val byFolder = all.groupBy { it.folder }
            for (folder in byFolder.keys.sortedBy { it.lowercase() }) {
                val books = byFolder.getValue(folder).sortedBy { it.title.lowercase() }
                binding.shelfColumn.addView(
                    heading(if (folder.isBlank()) "All books · ${books.size}" else "🗂  $folder · ${books.size}")
                )
                binding.shelfColumn.addView(grid(books))
            }
        }
    }

    private fun heading(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF444444.toInt())
        setPadding(dp(10), dp(16), dp(10), dp(6))
    }

    /**
     * A row-wrapping grid of covers.
     *
     * Hand-laid rather than a RecyclerView with a GridLayoutManager: this is e-ink, the list is
     * drawn once per period change, and a recycler's view reuse buys nothing when nothing scrolls
     * smoothly anyway. Covers fill in asynchronously — the shelf draws immediately with the drawn
     * fallback and swaps in real art as each is extracted, so a first visit to a large library is
     * never a blank screen behind a spinner.
     */
    private fun grid(books: List<BookshelfSource.Entry>): View {
        val ctx = requireContext()
        val columns = 4
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for ((i, b) in books.withIndex()) {
            if (i % columns == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                outer.addView(row)
            }
            row!!.addView(tile(b), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Pad the last row so four books and five books have the same tile width.
        val remainder = books.size % columns
        if (remainder != 0) repeat(columns - remainder) {
            row!!.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        }
        return outer
    }

    private fun tile(entry: BookshelfSource.Entry): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { open(entry) }
            setOnLongClickListener { showBookMenu(entry); true }
        }
        val art = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }
        col.addView(art)
        col.addView(TextView(ctx).apply {
            text = entry.title
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFF000000.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { BookCovers.cover(ctx, entry) }
            if (isAdded) art.setImageBitmap(bmp)
        }
        return col
    }

    private fun open(entry: BookshelfSource.Entry) {
        BookOpens.record(requireContext(), entry.name)
        val f = BookshelfSource.materialise(requireContext(), entry)
        if (f == null) { showMessage("Couldn't open ${entry.title}"); return }
        requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
            .putString("current_book_path", f.absolutePath).apply()
        runCatching { findNavController().navigate(R.id.action_to_reader) }
    }

    private fun showBookMenu(entry: BookshelfSource.Entry) {
        val last = BookOpens.lastOpen(requireContext(), entry.name)
        val when_ = last?.let { "Last opened ${BookOpens.dayOf(it)}" } ?: "Not opened yet"
        showIconMenu(entry.title, listOf(
            "📖  Read" to { open(entry) },
            "🕘  $when_" to { },
        ))
    }
}
