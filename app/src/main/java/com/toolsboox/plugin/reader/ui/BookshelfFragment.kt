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
        // Ask the hub what the fleet has while the shelf draws what this device has. The ghost
        // cards render from the CACHED manifest (instant, offline-honest); this refresh is the
        // cheap read-side pull, and re-renders only when the merge actually learned something —
        // which is what stops refresh→load→refresh from becoming a loop.
        com.toolsboox.plugin.reader.ui.LibraryHub.refreshManifest(
            requireContext().applicationContext
        ) { activity?.runOnUiThread { if (isAdded) load() } }
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
            // Push on add: the moment the book is on THIS shelf it belongs to the fleet.
            // Fire-and-forget off-main; a failed push is retried by the next sync pass.
            name?.let { LibraryHub.pushAdded(requireContext(), "", it) }
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

    /** Manifest tombstones this device still holds a copy of, keyed by book id — refreshed by
     *  [load], read by [showBookMenu] so the "keep your copy?" question is asked where the book
     *  is, not in a settings screen nobody visits. */
    private var removedButLocal: Map<String, com.toolsboox.plugin.calendar.da.v2.LibraryBook> = emptyMap()

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
            // THE NUDGE — what the fleet has that this shelf doesn't, from the cached manifest
            // only (no network on the render path, ever: the ghost row is the ghost-ROW grammar,
            // draw first and fetch only when asked). Grouped by folder so a ghost stands on the
            // same shelf row its book will land on.
            val ghosts = withContext(Dispatchers.IO) {
                LibraryHub.offered(ctx, all).groupBy { it.folder }
            }
            removedButLocal = withContext(Dispatchers.IO) { LibraryHub.removedButLocal(ctx, all) }
            if (!isAdded) return@launch
            binding.progress.visibility = View.GONE

            if (all.isEmpty() && ghosts.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text =
                    "No books yet.\n\nAdd one with ＋, pull from the 🌐 catalog, " +
                        "or point 🗂 at the folder your library already lives in."
                return@launch
            }
            binding.emptyText.visibility = View.GONE

            if (read.isNotEmpty()) {
                binding.shelfColumn.addView(heading("Read this ${level.label.lowercase()} · ${read.size}"))
                binding.shelfColumn.addView(gridOf(read.map { tile(it) }))
            }
            // Everything, by the folders the library already keeps. `folder` is "" at the shelf
            // root, which sorts first and reads as the shelf itself rather than as a nameless group.
            // Folder keys are the UNION of local and offered: a folder that exists only on other
            // devices still shows here, ghosts and all — that IS the library being fleet property.
            val byFolder = all.groupBy { it.folder }
            for (folder in (byFolder.keys + ghosts.keys).sortedBy { it.lowercase() }) {
                val books = byFolder[folder].orEmpty().sortedBy { it.title.lowercase() }
                val offered = ghosts[folder].orEmpty().sortedBy { it.name.lowercase() }
                binding.shelfColumn.addView(
                    folderHeading(folder, books.size + offered.size)
                )
                binding.shelfColumn.addView(
                    gridOf(books.map { tile(it) } + offered.map { ghostTile(it) })
                )
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
     * A folder's heading, and its door: tapping it opens the carry menu, because the question
     * "should new books in this folder download themselves here" belongs on the folder, at the
     * moment you are looking at it — not in a settings tree three screens away. The current mode
     * rides in the heading so the answer is visible without asking.
     */
    private fun folderHeading(folder: String, count: Int): TextView {
        val carry = LibraryHub.carry(requireContext(), folder)
        val label = if (folder.isBlank()) "All books" else "🗂  $folder"
        val suffix = if (carry == LibraryPolicy.Carry.ON_OPEN) " · on-open" else ""
        return heading("$label · $count$suffix").apply {
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showCarryMenu(folder) }
        }
    }

    /** Carry: auto / on-open, per folder — the device's own selection of what it hauls. */
    private fun showCarryMenu(folder: String) {
        val ctx = requireContext()
        val cur = LibraryHub.carry(ctx, folder)
        val rows = LibraryPolicy.Carry.entries.map { mode ->
            val mark = if (mode == cur) "●" else "○"
            "$mark  ${mode.label}" to {
                LibraryHub.setCarry(ctx, folder, mode)
                load()
            }
        }
        showIconMenu(if (folder.isBlank()) "All books" else folder, rows)
    }

    /**
     * A row-wrapping grid of covers.
     *
     * Hand-laid rather than a RecyclerView with a GridLayoutManager: this is e-ink, the list is
     * drawn once per period change, and a recycler's view reuse buys nothing when nothing scrolls
     * smoothly anyway. Covers fill in asynchronously — the shelf draws immediately with the drawn
     * fallback and swaps in real art as each is extracted, so a first visit to a large library is
     * never a blank screen behind a spinner.
     *
     * Takes tiles rather than entries so real books and ghost cards share one grid: a ghost is a
     * different tile in the same row, not a different surface.
     */
    private fun gridOf(tiles: List<View>): View {
        val ctx = requireContext()
        val columns = 4
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for ((i, t) in tiles.withIndex()) {
            if (i % columns == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                outer.addView(row)
            }
            row!!.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Pad the last row so four books and five books have the same tile width.
        val remainder = tiles.size % columns
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
            .putString("current_book_path", f.absolutePath)
            // The sync key rides beside the path: only THIS entry knows the folder-qualified
            // name+size ("reading-state.json" identity) — the materialised cache file the reader
            // restores from is named "<size>-<name>" and could not answer for itself.
            .putString(
                "current_reading_id",
                com.toolsboox.plugin.calendar.da.v2.ReadingState.keyFor(entry.folder, entry.name, entry.sizeBytes)
            )
            .apply()
        runCatching { findNavController().navigate(R.id.action_to_reader) }
    }

    /**
     * A GHOST CARD — a book the library has that this shelf hasn't fetched.
     *
     * Render-only, the ghost-row grammar: the card is drawn from the cached manifest and moves no
     * bytes until tapped. Tapping fetches — bypassing the carry/wifi/size policy on purpose,
     * because a person asking IS the policy. The card names who added the book and what it weighs,
     * which is exactly the information the tap decision needs on a metered morning.
     *
     * No fetch ever runs in a widget process; this tile and the sync worker are the only two
     * callers of a download, both squarely in the app's own process.
     */
    private fun ghostTile(book: com.toolsboox.plugin.calendar.da.v2.LibraryBook): View {
        val ctx = requireContext()
        val title = book.name.substringBeforeLast('.', book.name)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true
            alpha = 0.45f   // the ghost of it
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        // An empty cover-shaped frame where the art will be once it is here.
        col.addView(TextView(ctx).apply {
            text = "⬇"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF666666.toInt())
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        })
        col.addView(TextView(ctx).apply {
            text = "$title — fetch?"
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFF444444.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        val mb = book.size / (1024.0 * 1024.0)
        col.addView(TextView(ctx).apply {
            text = String.format(Locale.getDefault(), "%.1f MB · %s", mb, book.addedBy.ifBlank { "the fleet" })
            textSize = 10f
            maxLines = 1
            setTextColor(0xFF888888.toInt())
        })
        col.setOnClickListener {
            showMessage("Fetching $title…")
            LibraryHub.fetch(ctx.applicationContext, book) { ok ->
                if (!isAdded) return@fetch
                if (ok) {
                    showMessage("On the shelf · $title")
                    load()
                } else {
                    // Honest about the seam: a declared-tree book's bytes may not have reached
                    // the hub yet (they ride Syncthing until step E), and that is not an error.
                    showMessage("Couldn't fetch $title — it may not have reached the library yet.")
                }
            }
        }
        return col
    }

    private fun showBookMenu(entry: BookshelfSource.Entry) {
        val ctx = requireContext()
        val last = BookOpens.lastOpen(ctx, entry.name)
        val when_ = last?.let { "Last opened ${BookOpens.dayOf(it)}" } ?: "Not opened yet"
        val id = LibraryHub.idFor(entry)
        val rows = mutableListOf(
            "📖  Read" to { open(entry) },
            "🕘  $when_" to { },
        )
        val tombstone = removedButLocal[id]
        if (tombstone != null) {
            // Another device removed this book from the LIBRARY; this device still holds a copy.
            // The tombstone is a question here, never an action — no merge result ever deletes a
            // local file without this person's own answer.
            rows += "🕊  Removed from the library — keep your copy?" to { showKeepOrDelete(entry, id) }
        } else if (entry.file != null) {
            // Only an app-directory book can be removed from here. A declared-tree book gets no
            // delete row at all — the declared-tree refusal: that folder belongs to whatever put
            // the books in it (Syncthing, Calibre, a person with a cable), and the app does not
            // reach into it destructively, full stop.
            rows += "🗑  Remove from the library…" to { confirmRemove(entry, id) }
        }
        showIconMenu(entry.title, rows)
    }

    /** The guarded delete: what it does fleet-wide is said BEFORE it happens, in its own words. */
    private fun confirmRemove(entry: BookshelfSource.Entry, id: String) {
        val ctx = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(entry.title)
            .setMessage(
                "Remove this book from the library?\n\nIt is deleted from this device now. Other " +
                    "devices keep their copies and are asked, not told — the library just stops " +
                    "listing it."
            )
            .setPositiveButton("Remove") { d, _ ->
                d.dismiss()
                val gone = entry.file?.delete() ?: false
                if (gone) {
                    LibraryHub.recordDelete(ctx.applicationContext, id)
                    showMessage("Removed · ${entry.title}")
                } else showMessage("Couldn't remove ${entry.title}.")
                load()
            }
            .setNegativeButton("Keep it") { d, _ -> d.dismiss() }
            .show()
    }

    /** The other end of a tombstone: the fleet said "removed", this device's person decides. */
    private fun showKeepOrDelete(entry: BookshelfSource.Entry, id: String) {
        val ctx = requireContext()
        val rows = mutableListOf(
            "📚  Keep my copy" to {
                LibraryHub.ackKeep(ctx.applicationContext, id)
                showMessage("Kept · ${entry.title}")
                load()
            }
        )
        // Deleting the local copy is only offered where the app is allowed to delete at all —
        // an app-directory file. For a declared-tree copy the only answer is "keep": the refusal
        // again, and the honest one, because that file is Syncthing's to manage.
        if (entry.file != null) {
            rows += "🗑  Delete my copy too" to {
                val gone = entry.file?.delete() ?: false
                // Already tombstoned fleet-wide — nothing to record; ack so the question does
                // not outlive the book.
                LibraryHub.ackKeep(ctx.applicationContext, id)
                showMessage(if (gone) "Deleted · ${entry.title}" else "Couldn't delete ${entry.title}.")
                load()
            }
        }
        showIconMenu("${entry.title} — removed from the library", rows)
    }
}
