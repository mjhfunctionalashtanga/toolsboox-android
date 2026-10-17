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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** How many tiles one folder section draws before offering the rest — see the note at the
     *  section draw. A screenful, not a library. */
    private val SECTION_TILE_CAP = 12

    /** How many cover tiles the shelf will draw at once before folding to folder rows. Each tile
     *  holds a 320×460 bitmap; this is a memory budget wearing a UI number, and it is the one that
     *  stopped the app being killed on a real library. */
    private val SHELF_TILE_BUDGET = 60

    private var level = Level.WEEK
    /** When set, the shelf shows only this folder — the folder index's "show me just this".
     *  Null is the whole shelf, which is where every arrival starts. */
    private var folderFilter: String? = null

    /**
     * The last walk's result, kept so a REDRAW is not a RE-WALK.
     *
     * "Bookshelf loads crazy slow." Two reasons, and this is the first: every `load()` walked the
     * declared tree again — dozens of SAF `listFiles` queries, each a ContentResolver round trip —
     * and load() runs on arrival, on every folder filter change, on every density change and after
     * every import. Filtering to one series re-read the entire library to draw fewer things.
     *
     * Invalidated by the acts that can actually change what is on disk (declaring a folder,
     * importing, deleting) and by a pull-to-refresh; NOT by anything that merely changes how the
     * shelf is drawn, which is the whole point.
     */
    private var shelfCache: List<BookshelfSource.Entry>? = null
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

        // One jump per page, no glide: Page ↑/↓ are panel seats now, and a smooth scroll is a run
        // of partial e-ink refreshes and ghosting on the B6 (review 2026-10-11, #6) — Feeds and
        // the Net turn their pages instantly the same way.
        binding.shelfPageUp.setOnClickListener {
            binding.shelfScroll.scrollBy(0, -(binding.shelfScroll.height * 9 / 10))
        }
        binding.shelfPageDown.setOnClickListener {
            binding.shelfScroll.scrollBy(0, binding.shelfScroll.height * 9 / 10)
        }
        binding.shelfPill.visibility = View.GONE
        binding.gotoButton.visibility = View.GONE

        // THE ONE PANEL LANGUAGE (Michael 2026-10-11: one design language for every tuck panel,
        // both apps — the iPhone shelf wears the same column):
        //   ⛶ | Today · Nib · More | Page ↑ · Page ↓ | Folders · Search · Add · Continue | Side
        // (☰ Hub left the column 2026-10-12, Michael: "Those too." — More's first row and the left
        // edge's Back have it.)
        // Monochrome faces (▶ → the play/pause Continue, 🗄 → books on a shelf, ＋ → the plus,
        // ☀ → the house's Today), Page ↑/↓ out of the retired pill and onto the column (they were
        // unreachable), Search new (parity with iOS), and ⛶ in place of ✕ Tuck — a remembered tuck
        // comes back as full screen, the [⛶] pill at the nav line's leading end.
        shelfRail = setupPanel(
            binding.shelfRail, "bookshelf", com.toolsboox.ot.PanelSeats.Dress.SHELF,
            fullPillHost = binding.shelfNavRow,
        ) { seat -> shelfSeat(seat) }
        setupShelfSearch()

        binding.titleText.text = "Bookshelf"
        renderNav()
        load()
    }

    /** The shelf's panel — kept for More's ☐ Tool labels. */
    private var shelfRail: com.toolsboox.ot.TuckPanel? = null

    /**
     * The shelf's own seats on the one panel language (spec §4.12, Michael 2026-10-11). Hub, Nib
     * and Side are the shared doors ([setupPanel]).
     */
    private fun shelfSeat(s: com.toolsboox.ot.PanelSeats.Seat): com.toolsboox.ot.TuckPanel.Item? = when (s) {
        // ☀ Today: this week's shelf; already there, today's day page (spec Q2). Hold: the day page.
        com.toolsboox.ot.PanelSeats.Seat.TODAY -> com.toolsboox.ot.PanelFaces.item(s, "Today — this week's shelf",
            longPress = { openTodaysPage() }) {
            val now = LocalDate.now()
            if (level == Level.WEEK && windowStart() <= now && now <= windowEnd()) openTodaysPage()
            else { level = Level.WEEK; anchor = now; renderNav(); load() }
        }
        // THE WRENCH — the shelf's own controls (spacing, rescan, the Books folder, the hub's
        // carry, trackers, sources), and ☐ Tool labels with them.
        com.toolsboox.ot.PanelSeats.Seat.MORE ->
            com.toolsboox.ot.PanelFaces.item(s, "Shelf controls") { showShelfControls() }
        com.toolsboox.ot.PanelSeats.Seat.PAGE_UP ->
            com.toolsboox.ot.PanelFaces.item(s) { binding.shelfPageUp.performClick() }
        com.toolsboox.ot.PanelSeats.Seat.PAGE_DOWN ->
            com.toolsboox.ot.PanelFaces.item(s) { binding.shelfPageDown.performClick() }
        // THE FOLDER INDEX — "there is no simple way from the current bookshelf to pull up a
        // comic" (Michael, 2026-08-24): lists the folders and jumps. Boxed while the shelf is
        // narrowed to one.
        com.toolsboox.ot.PanelSeats.Seat.FOLDERS ->
            com.toolsboox.ot.PanelFaces.item(s, "Folders", on = folderFilter != null) { showFolderJump() }
        // Search the shelf by title and folder (parity with the iPhone shelf) — boxed while the
        // field stands.
        com.toolsboox.ot.PanelSeats.Seat.SEARCH ->
            com.toolsboox.ot.PanelFaces.item(s, if (shelfSearchOn) "Put the search away" else "Search books",
                on = shelfSearchOn) { toggleShelfSearch() }
        // ONE ADD DOOR: this device · the catalog · Audiobookshelf · a folder.
        com.toolsboox.ot.PanelSeats.Seat.ADD ->
            com.toolsboox.ot.PanelFaces.item(s, "Add a book") { addBook() }
        // THE SERIES TRACKER (the Mihon lesson): per series, exactly where your hand left off.
        com.toolsboox.ot.PanelSeats.Seat.CONTINUE ->
            com.toolsboox.ot.PanelFaces.item(s, "Continue reading") { showContinueReading() }
        else -> null
    }

    /** The search field stands (the panel's Search seat). */
    private var shelfSearchOn = false
    /** What the shelf is narrowed to — lower-cased; empty is the whole shelf. */
    private var shelfQuery = ""
    private val shelfSearchDebounce = android.os.Handler(android.os.Looper.getMainLooper())

    private fun setupShelfSearch() {
        binding.shelfSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = e?.toString()?.trim()?.lowercase().orEmpty()
                if (q == shelfQuery) return
                shelfQuery = q
                shelfSearchDebounce.removeCallbacksAndMessages(null)
                // A beat for e-ink: one redraw per pause, not one per letter.
                shelfSearchDebounce.postDelayed({ if (isAdded) load() }, 250)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // A search still standing when the view comes back (a book opened from the results, then
        // Back) comes back WITH its field and words — never a shelf narrowed by a hidden query
        // (review 2026-10-11, #5). The watcher sees the same query and stays quiet.
        if (shelfSearchOn) {
            binding.shelfSearch.visibility = View.VISIBLE
            if (binding.shelfSearch.text.toString().trim().lowercase() != shelfQuery)
                binding.shelfSearch.setText(shelfQuery)
        }
        binding.shelfSearch.setOnEditorActionListener { v, _, _ ->
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
    }

    /** Summon the shelf's search (focus + the keyboard), or put it away (the whole shelf back). */
    private fun toggleShelfSearch() {
        shelfSearchOn = !shelfSearchOn
        val field = binding.shelfSearch
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        if (shelfSearchOn) {
            field.visibility = View.VISIBLE
            field.requestFocus()
            field.postDelayed({
                if (isAdded && field.isAttachedToWindow)
                    imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        } else {
            shelfSearchDebounce.removeCallbacksAndMessages(null)
            imm?.hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
            field.visibility = View.GONE
            if (field.text.isNotEmpty()) field.setText("")
            if (shelfQuery.isNotEmpty()) { shelfQuery = ""; load() }
        }
        rebuildActionRail("bookshelf")
    }

    /** A book the search lets through: its shelf title, its file name, or its folder (series,
     *  author — whatever the library keeps the folders by). */
    private fun matchesShelfQuery(title: String, name: String, folder: String): Boolean =
        shelfQuery.isEmpty() || title.lowercase().contains(shelfQuery) ||
            name.lowercase().contains(shelfQuery) || folder.lowercase().contains(shelfQuery)

    override fun onResume() {
        super.onResume()
        load()
        // The drawer's "Add a book…" arrives with the add menu wanted.
        requireContext().getSharedPreferences("ledger_reader_prefs", 0).let { p ->
            if (p.getBoolean("open_add_book_on_arrival", false)) {
                p.edit().putBoolean("open_add_book_on_arrival", false).apply()
                binding.root.post { if (isAdded) addBook() }
            }
        }
        // Ask the hub what the fleet has while the shelf draws what this device has. The ghost
        // cards render from the CACHED manifest (instant, offline-honest); this refresh is the
        // cheap read-side pull, and re-renders only when the merge actually learned something —
        // which is what stops refresh→load→refresh from becoming a loop.
        com.toolsboox.plugin.reader.ui.LibraryHub.refreshManifest(
            requireContext().applicationContext
        ) { activity?.runOnUiThread { if (isAdded) { shelfCache = null; load() } } }
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
            shelfCache = null   // a book just landed in the tree
            load()
        }
    }

    private val pickFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        BookshelfSource.declare(requireContext(), uri)
        // A new tree means the cached listing describes a library that is no longer the shelf.
        shelfCache = null
        folderFilter = null
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
    /** Cover extraction is serialised through this — see the note at the fill site. Two, not one:
     *  one leaves the IO thread idle while a bitmap is handed to the view, and more than two buys
     *  nothing on a device with one slow flash chip. */
    private val coverPermits = Semaphore(2)

    /**
     * Jump to a folder of the shelf — the index a deep library needs.
     *
     * A shelf pointed at a real library is not a list, it is a TREE flattened into sections, and
     * his has dozens: every mihon series is its own folder four levels down. Scrolling to one is
     * not navigation, it is searching by eye. This names them, with counts, and narrows the shelf
     * to the one chosen — with "All books" always first, because the way OUT of a filter has to be
     * as obvious as the way in.
     */
    /** The shelf's wrench: how it is laid out, and the housekeeping that is not "add a book". */
    private fun showShelfControls() {
        val carry = LibraryHub.carryDeclared(requireContext())
        val carryLabel = when (carry) {
            "small" -> "small books"; "all" -> "everything"; else -> "off"
        }
        showIconMenu("Shelf", listOf(
            // ☰ Hub, the first row (Michael 2026-10-12): the column's Hub seat went — this row and
            // the left edge's Back are the way to the main menu now.
            "☰  Hub" to { openHub() },
            "▦  Spacing · ${ShelfDensity.current(requireContext()).label}" to { showDensityMenu() },
            // A re-walk on demand, since the listing is cached now and a book added by another app
            // (mihon finishing a download, Syncthing landing a file) cannot announce itself.
            "⟳  Rescan the folder" to { shelfCache = null; load() },
            "🗂  Books folder · ${BookshelfSource.declaredName(requireContext()) ?: "app storage"}" to {
                chooseFolder()
            },
            // THE GATE for declared-tree bytes riding the library hub — what lets a comic
            // mihon downloaded on one device be fetched on another with no Syncthing between
            // them. Off by default: the folder's bytes are somebody else's (Syncthing's,
            // mihon's) until this person says the hub may carry them. "Small" respects the
            // same size ceiling the fetch side already honours; wifi-only guards uploads too.
            "☁  Hub carries folder books · $carryLabel" to { showCarryDeclaredMenu() },
            // WHERE THE LIBRARY LIVES (09-13): Ultrabridge, Google Drive, or whatever the day
            // sync uses — the library's own choice, so a 20 GB shelf can ride Drive while the
            // planner keeps riding the VPS.
            "🗄  Library over · ${when (LibraryHub.libraryBackend(requireContext())) { "drive" -> "Google Drive"; "webdav" -> "Ultrabridge"; else -> "same as sync" }}" to { showLibraryBackendMenu() },
            // 📈 TRACKERS (09-13): Suwayomi's six, signed in here; a series is bound from its
            // own hold menu, and the reader pushes every finished chapter.
            "📈  Trackers…" to { showTrackersMenu() },
            // 🧭 SOURCES (09-13, "I really only need the sources"): BatCave, ReadAllComics,
            // Comick… browsed here; a series' chapters download on Suwayomi and reach the shelf
            // through the catalog it already pulls from.
            "🧩  Comic sources (your server)…" to { showSourcesMenu() },
            // Every panel's More carries ☐ Tool labels (the one panel language, 2026-10-11).
            toolLabelsRow(shelfRail).let { "${it.emoji}  ${it.label}" to it.action },
        ))
    }

    // ── Sources (Suwayomi's extensions, browsed from the shelf) ──────────────────────────────

    private fun srcBg(work: () -> Unit) = Thread(work).apply { isDaemon = true }.start()
    private fun srcUi(block: () -> Unit) { activity?.runOnUiThread { if (isAdded) block() } }

    private fun showSourcesMenu() {
        val srv = trackerServerOrAsk() ?: return
        showMessage("🧭 Asking Suwayomi for its sources…")
        srcBg {
            val r = runCatching { com.toolsboox.plugin.reader.nw.SuwayomiSources.sources(srv) }
            srcUi {
                val list = r.getOrElse { showMessage(it.message ?: "Couldn't reach Suwayomi."); return@srcUi }
                if (list.isEmpty()) { showMessage("Suwayomi has no sources installed yet — add extensions in its web UI."); return@srcUi }
                showIconMenu("Sources", list.map { src -> "${src.name}${if (src.lang != "en") " · ${src.lang}" else ""}" to { showSourceMenu(srv, src) } })
            }
        }
    }

    private fun showSourceMenu(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, src: com.toolsboox.plugin.reader.nw.SuwayomiSources.Source) {
        val rows = mutableListOf<Pair<String, () -> Unit>>(
            "🔍  Search ${src.name}…" to { sourceSearch(srv, src) },
            "🔥  Popular" to { sourceBrowse(srv, src, "POPULAR", "") },
        )
        if (src.supportsLatest) rows += "🆕  Latest" to { sourceBrowse(srv, src, "LATEST", "") }
        rows += "🌐  Open in Suwayomi" to {
            findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true,
                com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_TARGET to "${srv.base}/sources/${src.id}/popular"))
        }
        showIconMenu(src.name, rows)
    }

    private fun sourceSearch(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, src: com.toolsboox.plugin.reader.nw.SuwayomiSources.Source) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Series title"; isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Search ${src.name}").setView(input)
            .setPositiveButton("Search") { d, _ -> d.dismiss(); val q = input.text.toString().trim(); if (q.isNotBlank()) sourceBrowse(srv, src, "SEARCH", q) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun sourceBrowse(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, src: com.toolsboox.plugin.reader.nw.SuwayomiSources.Source, type: String, query: String, page: Int = 1) {
        showMessage("🧭 ${src.name}…")
        srcBg {
            val r = runCatching { com.toolsboox.plugin.reader.nw.SuwayomiSources.browse(srv, src.id, type, query, page) }
            srcUi {
                val (list, more) = r.getOrElse { showMessage(it.message ?: "${src.name} didn't answer."); return@srcUi }
                if (list.isEmpty()) { showMessage("${src.name} has nothing for that."); return@srcUi }
                val rows = list.map { s -> "${if (s.inLibrary) "●  " else ""}${s.title}" to { showSeriesMenu(srv, src, s) } }.toMutableList()
                if (more) rows += "→  More…" to { sourceBrowse(srv, src, type, query, page + 1) }
                showIconMenu(if (query.isBlank()) "${src.name} · ${type.lowercase().replaceFirstChar { it.uppercase() }}" else "${src.name} · “$query”", rows)
            }
        }
    }

    private fun showSeriesMenu(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, src: com.toolsboox.plugin.reader.nw.SuwayomiSources.Source, s: com.toolsboox.plugin.reader.nw.SuwayomiSources.Series) {
        showMessage("🧭 Fetching chapters…")
        srcBg {
            val r = runCatching { com.toolsboox.plugin.reader.nw.SuwayomiSources.chapters(srv, s.id) }
            srcUi {
                val chapters = r.getOrElse { showMessage(it.message ?: "Couldn't fetch the chapters."); return@srcUi }
                val pending = chapters.filter { !it.downloaded }
                val rows = mutableListOf<Pair<String, () -> Unit>>()
                fun queue(ids: List<Int>, what: String) {
                    showMessage("⬇ Queuing $what…")
                    srcBg {
                        val ok = runCatching {
                            if (!s.inLibrary) com.toolsboox.plugin.reader.nw.SuwayomiSources.addToLibrary(srv, s.id)
                            com.toolsboox.plugin.reader.nw.SuwayomiSources.download(srv, ids)
                        }.isSuccess
                        srcUi { showMessage(if (ok) "⬇ ${ids.size} chapter${if (ids.size == 1) "" else "s"} downloading on Suwayomi — they reach the catalog as they land" else "Couldn't queue the download.") }
                    }
                }
                rows += "📖  ${chapters.size} chapters · ${chapters.count { it.downloaded }} downloaded" to { }
                if (pending.isNotEmpty()) {
                    rows += "⬇  Download all ${pending.size}" to { queue(pending.map { it.id }, "all") }
                    if (pending.size > 5) rows += "⬇  Download the latest 5" to { queue(pending.takeLast(5).map { it.id }, "the latest 5") }
                    rows += "⬇  Pick chapters…" to {
                        showIconMenu(s.title, pending.map { c -> "${c.name}" to { queue(listOf(c.id), c.name) } })
                    }
                }
                // The series' record travels with its chapters (09-13): written here from the
                // server's own knowledge of it, before any chapter lands.
                run { val app = requireContext().applicationContext; srcBg { runCatching { com.toolsboox.plugin.reader.nw.ComicSeriesStore.recordFromServer(app, srv, s.id, "Comics/${com.toolsboox.plugin.reader.nw.ComicSeriesStore.safe(s.title)}") } } }
                if (!s.inLibrary) rows += "📚  Add to Suwayomi's library" to {
                    srcBg { val ok = runCatching { com.toolsboox.plugin.reader.nw.SuwayomiSources.addToLibrary(srv, s.id) }.isSuccess; srcUi { showMessage(if (ok) "📚 In the library · ${s.title}" else "Couldn't add it.") } }
                }
                rows += "🌐  Open in Suwayomi" to {
                    findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                        com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true,
                        com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_TARGET to com.toolsboox.plugin.reader.nw.SuwayomiSources.webUrl(srv, s.id)))
                }
                showIconMenu(s.title, rows)
            }
        }
    }

    private fun showLibraryBackendMenu() {
        val ctx = requireContext()
        val cur = LibraryHub.libraryBackend(ctx)
        fun mark(m: String) = if (m == cur) "●" else "○"
        showIconMenu("Library over", listOf(
            "${mark("auto")}  Same as the day sync" to { LibraryHub.setLibraryBackend(ctx, "auto") },
            "${mark("webdav")}  Ultrabridge (WebDAV)" to { LibraryHub.setLibraryBackend(ctx, "webdav") },
            "${mark("drive")}  Google Drive" to {
                LibraryHub.setLibraryBackend(ctx, "drive")
                if (com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(ctx) == null)
                    showMessage("Sign in to Google first — Settings › Calendar › Connect", binding.root)
            },
        ))
    }

    /** The three grades of the declared-bytes gate, with the cost said out loud. */
    private fun showCarryDeclaredMenu() {
        val ctx = requireContext()
        val cur = LibraryHub.carryDeclared(ctx)
        fun mark(m: String) = if (m == cur) "●" else "○"
        showIconMenu("Hub carries folder books", listOf(
            "${mark("off")}  Off — entries only; other devices see ghosts they can't fetch" to {
                LibraryHub.setCarryDeclared(ctx, "off")
            },
            "${mark("small")}  Small books — up to the size ceiling (wifi only if set)" to {
                LibraryHub.setCarryDeclared(ctx, "small")
            },
            "${mark("all")}  Everything — big comics too; uploads can take a while" to {
                LibraryHub.setCarryDeclared(ctx, "all")
            },
        ))
    }

    /** Per series (folder): resume the newest in-progress chapter, or offer the one after the
     *  last finished — newest-touched series first, capped to what a menu can hold. */
    private fun showContinueReading() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val all = shelfCache ?: withContext(Dispatchers.IO) { BookshelfSource.list(ctx) }
                .also { shelfCache = it }
            if (!isAdded) return@launch
            val rows = withContext(Dispatchers.IO) {
                val touched = ReaderPositionStore.lastReadTimes(ctx)
                val done = com.toolsboox.plugin.calendar.ot.MediaLogStore.loadAll(ctx)
                    .filter { it.done && it.id.startsWith("book:") }
                    .map { it.id.removePrefix("book:") }.toSet()
                val out = mutableListOf<Triple<Long, String, BookshelfSource.Entry>>()
                for ((folder, sibs0) in all.groupBy { it.folder }) {
                    val sibs = sibs0.sortedBy { it.title.lowercase() }   // the next-book walk's own order
                    // Where the hand last was in this series — by the position file's clock.
                    // Match the extension-stripped key the position store files under (the
                    // today-zone bug, same root — 09-03).
                    val here = sibs.filter { touched.containsKey(it.name.substringBeforeLast('.')) }
                        .maxByOrNull { touched[it.name.substringBeforeLast('.')] ?: 0L } ?: continue
                    val ts = touched[here.name.substringBeforeLast('.')] ?: 0L
                    val key = com.toolsboox.plugin.calendar.da.v2.ReadingState
                        .keyFor(here.folder, here.name, here.sizeBytes)
                    val target = if (key in done) {
                        val i = sibs.indexOfFirst { it.name == here.name && it.sizeBytes == here.sizeBytes }
                        sibs.getOrNull(i + 1) ?: continue   // series finished outright — no row
                    } else here
                    val leaf = folder.substringAfterLast('/').ifBlank { "Shelf root" }
                    out += Triple(ts, "▶  $leaf  ·  ${target.title}", target)
                }
                out.sortedByDescending { it.first }.take(14)
            }
            if (!isAdded) return@launch
            if (rows.isEmpty()) {
                showMessage("Nothing mid-read — open something and it lands here.", binding.root)
                return@launch
            }
            // WITH FACES (the podcast finder's treatment, 09-03: "beautify directories and
            // surfacers"): the cover beside each series, loaded off-main as they land.
            fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
            val list = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            val dlg = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Continue reading")
                .setView(android.widget.ScrollView(ctx).apply { addView(list) })
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            for ((_, label, e) in rows) {
                val cover = android.widget.ImageView(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(60))
                        .apply { marginEnd = dp(12) }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
                list.addView(android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                    isClickable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    addView(cover)
                    addView(android.widget.TextView(ctx).apply {
                        text = label.removePrefix("\u25b6").trim()
                        textSize = 15.5f; setTextColor(0xFF000000.toInt())
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    setOnClickListener { dlg.dismiss(); open(e) }
                })
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { BookCovers.cover(ctx, e) }.getOrNull()
                    }
                    if (isAdded && bmp != null) cover.setImageBitmap(bmp)
                }
            }
            showModal(dlg)
        }
    }

    private fun showFolderJump() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val all = shelfCache ?: withContext(Dispatchers.IO) { BookshelfSource.list(ctx) }
                .also { shelfCache = it }
            if (!isAdded) return@launch
            val counts = all.groupingBy { it.folder }.eachCount()
            val folders = counts.keys.sortedBy { it.lowercase() }
            if (folders.isEmpty()) { showMessage("Nothing on the shelf yet."); return@launch }
            // The leaf leads, the parent trails — the same treatment the section headings get, and
            // for the same reason: a full path reads as a path, not as the name of a thing.
            val rows = listOf(("📚  All books  ·  ${all.size}") to { folderFilter = null; load() }) +
                folders.map { f ->
                    val leaf = f.substringAfterLast('/').ifBlank { "Shelf root" }
                    val parent = f.substringBeforeLast('/', "").substringAfterLast('/')
                    val label = if (parent.isBlank() || parent == leaf) leaf else "$leaf  ·  $parent"
                    ("🗂  $label  ·  ${counts[f]}") to { folderFilter = f; load() }
                }
            showIconMenu("Go to a folder", rows)
        }
    }

    private fun addBook() {
        showIconMenu("Add a book", listOf(
            "📄  From this device…" to {
                importBook.launch(arrayOf(
                    "application/epub+zip", "application/pdf",
                    "application/x-mobipocket-ebook", "application/zip", "audio/*", "*/*"
                ))
            },
            "🌐  From the catalog…" to { openCatalog() },
            "🎧  From Audiobookshelf…" to { showAbsBrowser() },
            "🗂  Point at a folder…" to { chooseFolder() },
        ))
    }

    private fun chooseFolder() {
        runCatching { pickFolder.launch(BookshelfSource.pickIntent()) }
    }

    // ── Audiobookshelf ────────────────────────────────────────────────────────────────────────
    // Libraries → items → one item, each a menu; audiobooks stream through the one player,
    // ebook-only items download onto the shelf. See Audiobookshelf.kt for the why.

    private fun showAbsBrowser() {
        val ctx = requireContext()
        if (Audiobookshelf.config(ctx) == null) { showAbsSettings(); return }
        showMessage("Asking Audiobookshelf…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Audiobookshelf.libraries(ctx) } }
            if (!isAdded) return@launch
            val libs = result.getOrElse { e ->
                showIconMenu("Audiobookshelf", listOf(
                    "⚠  ${e.message ?: "The server didn't answer."}" to { showAbsBrowser() },
                    "⚙  Audiobookshelf settings…" to { showAbsSettings() }
                ))
                return@launch
            }
            if (libs.size == 1) { showAbsLibrary(libs[0], 0); return@launch }
            showIconMenu("Audiobookshelf", libs.map { l ->
                ((if (l.mediaType == "podcast") "🎙  " else "🎧  ") + l.name) to { showAbsLibrary(l, 0) }
            } + listOf("⚙  Audiobookshelf settings…" to { showAbsSettings() }))
        }
    }

    private fun showAbsLibrary(lib: Audiobookshelf.Library, page: Int) {
        val ctx = requireContext()
        showMessage("Fetching ${lib.name}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Audiobookshelf.items(ctx, lib.id, page) to runCatching { Audiobookshelf.progress(ctx) }.getOrDefault(emptyMap()) }
            }
            if (!isAdded) return@launch
            val (pageData, progress) = result.getOrElse { e ->
                showIconMenu(lib.name, listOf(
                    "⚠  ${e.message ?: "The library didn't answer."}" to { showAbsLibrary(lib, page) },
                    "⚙  Audiobookshelf settings…" to { showAbsSettings() }
                ))
                return@launch
            }
            val (items, total) = pageData
            if (items.isEmpty()) { showIconMenu(lib.name, listOf("Nothing here." to {})); return@launch }
            val rows = items.map { it ->
                val p = progress[it.id]
                val pct = p?.let { pr -> if (pr.isFinished) "done" else if (pr.duration > 0) "${(pr.currentTime / pr.duration * 100).toInt()}%" else null }
                val glyph = when { it.isPodcast -> "🎙"; it.hasAudio -> "🎧"; else -> "📖" }
                val line = buildString {
                    append(glyph).append("  ").append(it.title)
                    if (it.author.isNotBlank()) append(" · ").append(it.author)
                    if (it.isPodcast && it.numEpisodes > 0) append(" · ").append(it.numEpisodes).append(" episodes")
                    else if (it.duration > 0) append(" · ").append(Audiobookshelf.clock(it.duration))
                    if (pct != null) append(" · ").append(pct)
                }
                line to { showAbsItem(it, lib, page) }
            }
            val more = if ((page + 1) * Audiobookshelf.PAGE < total) listOf("⤓  More… (${total - (page + 1) * Audiobookshelf.PAGE} left)" to { showAbsLibrary(lib, page + 1) }) else emptyList()
            val back = if (page > 0) listOf("⤒  Previous page" to { showAbsLibrary(lib, page - 1) }) else emptyList()
            showIconMenu("${lib.name} · ${page * Audiobookshelf.PAGE + 1}–${page * Audiobookshelf.PAGE + items.size} of $total",
                listOf("🔎  Search this library…" to { searchAbsLibrary(lib, page) }) + rows + back + more + listOf("⚙  Audiobookshelf settings…" to { showAbsSettings() }))
        }
    }

    /** The server's search across the whole library (typing into the list is not enough for 2,000 titles). */
    private fun searchAbsLibrary(lib: Audiobookshelf.Library, page: Int) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Title, author or narrator"; setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        fun run() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            showMessage("Searching ${lib.name}…")
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Audiobookshelf.search(ctx, lib.id, q) } }
                if (!isAdded) return@launch
                val hits = r.getOrElse { showMessage(it.message ?: "The search didn't answer."); return@launch }
                if (hits.isEmpty()) { showMessage("Nothing in ${lib.name} matches “$q”."); return@launch }
                showIconMenu("“$q” · ${hits.size}", hits.map { it ->
                    val glyph = when { it.isPodcast -> "🎙"; it.hasAudio -> "🎧"; else -> "📖" }
                    ("$glyph  ${it.title}" + (if (it.author.isNotBlank()) " · ${it.author}" else "")) to { showAbsItem(it, lib, page) }
                } + listOf("🗂  Back to ${lib.name}" to { showAbsLibrary(lib, page) }))
            }
        }
        val d = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Search ${lib.name}")
            .setView(android.widget.FrameLayout(ctx).apply { setPadding(48, 8, 48, 0); addView(input) })
            .setPositiveButton("Search") { _, _ -> run() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> d.dismiss(); run(); true }
        showModal(d)
    }

    private fun showAbsItem(minified: Audiobookshelf.Item, lib: Audiobookshelf.Library, page: Int) {
        val ctx = requireContext()
        showMessage("Opening ${minified.title}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Audiobookshelf.item(ctx, minified.id) to runCatching { Audiobookshelf.progress(ctx) }.getOrDefault(emptyMap()) }
            }
            if (!isAdded) return@launch
            val (item, progress) = result.getOrElse { e -> showMessage(e.message ?: "Couldn't open it."); return@launch }
            val rows = ArrayList<Pair<String, () -> Unit>>()
            if (item.isPodcast) {
                item.episodes.take(60).forEach { ep ->
                    val p = progress[item.id + "/" + ep.id]
                    val tag = when { p?.isFinished == true -> " · heard"; p != null && p.currentTime > 30 -> " · ${Audiobookshelf.clock(p.currentTime)} in"; else -> "" }
                    rows += ("▶  ${ep.title}" + (if (ep.duration > 0) " · ${Audiobookshelf.clock(ep.duration)}" else "") + tag) to { absPlay(item, ep.id, false) }
                }
                if (rows.isEmpty()) rows += "No episodes yet." to {}
            } else if (item.hasAudio) {
                val p = progress[item.id]
                val resume = p?.takeIf { !it.isFinished && it.currentTime > 30 }
                rows += (if (resume != null) "▶  Listen · from ${Audiobookshelf.clock(resume.currentTime)}" else "▶  Listen") to { absPlay(item, null, false) }
                if (resume != null || p?.isFinished == true) rows += "↺  Start over" to { absPlay(item, null, true) }
                if (item.chapters.isNotEmpty()) rows += "☰  ${item.chapters.size} chapters · ${Audiobookshelf.clock(item.duration)}" to {}
            }
            if (item.hasEbook) rows += "📖  Download the ${item.ebookFormat.ifBlank { "book" }.uppercase()} onto the shelf" to { absDownload(item) }
            // THE OTHER WAY ROUND (Michael, 09-11: "I can find the audiobook but I can't link
            // them"): from the recording to the book on the shelf. Pick the book; it opens, and
            // the reader pairs it with this recording as its contents load.
            if (item.hasAudio || item.tracks.isNotEmpty()) rows += "📖  Read along with a book on the shelf…" to { pickBookForRecording(item) }
            rows += "🗂  Back to ${lib.name}" to { showAbsLibrary(lib, page) }
            val sub = listOfNotNull(item.author.ifBlank { null }, item.narrator.ifBlank { null }?.let { "read by $it" }).joinToString(" · ")
            showIconMenu(if (sub.isBlank()) item.title else "${item.title}\n$sub", rows)
        }
    }

    /** The shelf's books, the likeliest match for the recording first; the pick opens the book
     *  with the pairing pending (ReaderFragment pairs it once the contents are in). */
    private fun pickBookForRecording(item: Audiobookshelf.Item) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val all = shelfCache ?: withContext(Dispatchers.IO) { BookshelfSource.list(ctx) }
            val t = ReadAlongPairing.normalizeTitle(item.title)
            val audio = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac")
            val books = all.filter { it.name.substringAfterLast('.', "").lowercase() !in audio }
            val ranked = books.map { e ->
                val k = ReadAlongPairing.normalizeTitle(e.name.substringBeforeLast('.'))
                val sc = (if (t.isBlank()) 0.0 else ReadAlongPairing.similarity(t, k)) + (if (t.isNotBlank() && k.contains(t)) 0.3 else 0.0)
                e to sc
            }.sortedByDescending { it.second }.map { it.first }.take(24)
            if (ranked.isEmpty()) { showMessage("No books on the shelf yet."); return@launch }
            showIconMenu("Read along with…\n${item.title}", ranked.map { e ->
                "📖  ${e.name.substringBeforeLast('.')}" to {
                    ctx.getSharedPreferences("ledger_reader_prefs", 0).edit().putString("pending_pair_abs_item", item.id).apply()
                    open(e)
                }
            })
        }
    }

    private fun absPlay(item: Audiobookshelf.Item, episodeId: String?, startOver: Boolean) {
        val ctx = requireContext().applicationContext
        showMessage("Starting…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AbsPlayback.play(ctx, item, episodeId, startOver) } }
            if (!isAdded) return@launch
            r.onFailure { showMessage(it.message ?: "Couldn't start it.") }
        }
    }

    private fun absDownload(item: Audiobookshelf.Item) {
        val ctx = requireContext()
        showMessage("Fetching ${item.title}…")
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { runCatching { Audiobookshelf.downloadEbook(ctx, item) }.getOrNull() }
            if (!isAdded) return@launch
            if (name == null) { showMessage("Couldn't fetch ${item.title}."); return@launch }
            showMessage("On the shelf · ${item.title}")
            LibraryHub.pushAdded(ctx.applicationContext, "", name)
            load()
        }
    }

    private fun showAbsSettings() = AbsSettingsDialog.show(this) { showAbsBrowser() }

    /**
     * The spacing ladder as a menu — three named rungs, current one marked, same radio grammar
     * as the carry menu two doors down. Choosing a rung is ONE relayout: prefs first, then a
     * single [load] (the shelf's normal draw-once pass) and a rail rebuild so "Shelf: Cozy"
     * tells the truth. No preview, no incremental reflow — e-ink pays per paint.
     */
    private fun showDensityMenu() {
        val ctx = requireContext()
        val cur = ShelfDensity.current(ctx)
        showIconMenu("Shelf spacing", ShelfDensity.entries.map { d ->
            val mark = if (d == cur) "●" else "○"
            "$mark  ${d.label} · ${d.columns} across" to {
                ShelfDensity.set(ctx, d)
                rebuildActionRail("bookshelf")
                load()
            }
        })
    }

    /**
     * The catalog, opened from the shelf.
     *
     * It still LIVES in the reader — one implementation of "pull a book down" — but sending
     * someone to another screen and telling them which menu to find was not a door, it was
     * directions. The flag makes the reader open the browser on arrival.
     */
    // The on-device extension loader (the keiyoushi bridge, -95 → -264) is GONE (Michael,
    // 09-13, "yes" to one build on Google Play): Play forbids executing code fetched from
    // anywhere but Play. Comic sources are the person's own server now (Suwayomi, below);
    // Mihon on an Android device still feeds the shelf through its declared folder.

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
                val cd = runCatching { calendarDayService.loadForDisplay(root, anchor, null, loc) }.getOrNull()
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

    companion object {
        /** A book name the today page's Bookshelf zone asked to open — consumed once by [load]. */
        var pendingOpenName: String? = null
    }

    /** Which [load] is the newest — see the note at its launch. */
    private var loadGen = 0

    private fun load() {
        val ctx = requireContext()
        // The Folders seat is boxed while a folder narrows the shelf — one cell, in place.
        rebuildActionRail("bookshelf")
        binding.shelfColumn.removeAllViews()
        binding.progress.visibility = View.VISIBLE
        // The newest load draws; an older one still in flight (a search keystroke's, a resume's)
        // stops at its next step instead of appending its sections under the new ones.
        val gen = ++loadGen
        lifecycleScope.launch {
            // TIMED, because six builds were spent reasoning about which phase was slow instead of
            // asking. `adb logcat -s BookshelfPerf` says it in one line.
            val t0 = System.currentTimeMillis()
            val cacheHit = shelfCache != null
            val walked = shelfCache ?: withContext(Dispatchers.IO) { BookshelfSource.list(ctx) }
                .also { shelfCache = it }
            val tWalk = System.currentTimeMillis()
            if (!isAdded || gen != loadGen) return@launch
            pendingOpenName?.let { want ->
                pendingOpenName = null
                walked.firstOrNull { it.name == want }?.let { open(it); return@launch }
            }
            // THE SEARCH narrows what the shelf draws, as the folder filter does — never what it
            // walked, so putting it away is instant.
            val query = shelfQuery
            val all = if (query.isEmpty()) walked else walked.filter { matchesShelfQuery(it.title, it.name, it.folder) }
            val read = withContext(Dispatchers.IO) {
                BookOpens.openedBetween(ctx, all, windowStart(), windowEnd())
            }
            // THE NUDGE — what the fleet has that this shelf doesn't, from the cached manifest
            // only (no network on the render path, ever: the ghost row is the ghost-ROW grammar,
            // draw first and fetch only when asked). Grouped by folder so a ghost stands on the
            // same shelf row its book will land on.
            val ghosts = withContext(Dispatchers.IO) {
                // Against the WHOLE walk: what the fleet has that this shelf lacks is a question about
                // the shelf, not about what the search lets through (else every unmatched book here
                // would come back as a "fetch?" ghost).
                LibraryHub.offered(ctx, walked)
                    // THE FILTER APPLIES TO GHOSTS TOO. It did not, and that is the bug behind both
                    // "clicking a folder ends up opening all books" and "it says it may not have
                    // reached the library yet": the section loop walks `byFolder.keys + ghosts.keys`,
                    // so with a filter set every OTHER folder still arrived through the ghost half
                    // and rendered its fetch-me tiles. Narrowing one source and not the other is
                    // not a filter, it is a filter with a hole in it.
                    .filter { folderFilter == null || it.folder == folderFilter }
                    .filter { query.isEmpty() || matchesShelfQuery(it.name, it.name, it.folder) }
                    .groupBy { it.folder }
            }
            removedButLocal = withContext(Dispatchers.IO) { LibraryHub.removedButLocal(ctx, walked) }
            val tHub = System.currentTimeMillis()
            if (!isAdded || gen != loadGen) return@launch
            binding.progress.visibility = View.GONE

            if (query.isNotEmpty() && all.isEmpty() && ghosts.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text = "Nothing on the shelf matches “${binding.shelfSearch.text.toString().trim()}”."
                return@launch
            }
            if (all.isEmpty() && ghosts.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text =
                    "No books yet.\n\nAdd one with ＋, pull from the 🌐 catalog, " +
                        "or point 🗂 at the folder your library already lives in."
                return@launch
            }
            binding.emptyText.visibility = View.GONE

            folderFilter?.let { f ->
                binding.shelfColumn.addView(
                    heading("🗄  ${f.substringAfterLast('/')} only · tap to show all").apply {
                        isClickable = true
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        setOnClickListener { folderFilter = null; load() }
                    }
                )
            }
            if (folderFilter == null && read.isNotEmpty()) {
                binding.shelfColumn.addView(heading("Read this ${level.label.lowercase()} · ${read.size}"))
                binding.shelfColumn.addView(gridOf(read.map { tile(it) }))
            }
            // Everything, by the folders the library already keeps. `folder` is "" at the shelf
            // root, which sorts first and reads as the shelf itself rather than as a nameless group.
            // Folder keys are the UNION of local and offered: a folder that exists only on other
            // devices still shows here, ghosts and all — that IS the library being fleet property.
            // The filter narrows what the shelf DRAWS, never what it walked — so clearing it is
            // instant and no state can go stale behind it.
            val byFolder = all.filter { folderFilter == null || it.folder == folderFilter }
                .groupBy { it.folder }
            // Folded when there is no filter AND the shelf would otherwise draw more covers than a
            // device can hold. The threshold is in TILES, not folders, because that is what costs.
            var drawnTiles = 0
            val foldedShelf = folderFilter == null &&
                byFolder.values.sumOf { minOf(it.size, SECTION_TILE_CAP) } > SHELF_TILE_BUDGET
            // Say what happened, or a shelf of bare rows reads as covers that failed to load.
            if (foldedShelf) binding.shelfColumn.addView(
                heading("${byFolder.size} folders · ${all.size} books — tap a folder to see its covers")
            )
            for (folder in (byFolder.keys + ghosts.keys).sortedBy { it.lowercase() }) {
                val books = byFolder[folder].orEmpty().sortedBy { it.title.lowercase() }
                val offered = ghosts[folder].orEmpty().sortedBy { it.name.lowercase() }
                // A FLEET-ONLY FOLDER SAYS SO. `folders` is the union of what is on this device
                // and what the fleet's manifest knows, so a folder can appear with NOTHING local in
                // it — every tile a "fetch?" ghost. Tapping into one and finding only "couldn't
                // fetch — may not have reached the library yet" reads as the shelf being broken,
                // when it is the shelf being honest about a folder that lives on another device.
                // Saying it on the row means he never opens one expecting his comics.
                val localHere = books.size
                // A COMIC SERIES IS ONE ROW, NOT A FOLDER OF CHAPTER TILES (Michael, 09-13, on the
                // Palma: "the comic folders and stuff are confusing in the reader"): the series'
                // cover and name, its source, how many chapters and how far along, and the next
                // one to read. Tap it and the chapters are the only section (read ✓ on each).
                if (folderFilter == null && isComicSeries(folder, books, offered)) {
                    binding.shelfColumn.addView(seriesRow(folder, books, offered))
                    continue
                }
                binding.shelfColumn.addView(
                    folderHeading(
                        folder,
                        books.size + offered.size,
                        elsewhere = localHere == 0 && offered.isNotEmpty()
                    )
                )
                // …AND ONLY THE FIRST FEW, unless this folder is the one being looked at.
                //
                // The second half of "loads crazy slow": every tile is a real view hierarchy —
                // a column, an ImageView and a TextView — inflated on the MAIN THREAD. A shelf of
                // four hundred books is over a thousand views built before anything can be drawn,
                // which on e-ink is seconds of nothing. Sections show a screenful and offer the
                // rest; the folder filter (🗄) is the way to see one series whole, and when it is
                // applied there is only one section, so nothing is capped.
                // A LIBRARY IS A LIST OF SHELVES, NOT A WALL OF COVERS.
                //
                // The per-section cap was the wrong unit and it is why this still died: 12 tiles is
                // nothing, but he has FORTY-FIVE folders, so the shelf was still building 540 tiles
                // and holding 540 cover bitmaps. At 320×460 that is over 300 MB against a 256 MB
                // heap — the low-memory killer took the app AND the launcher with it.
                //
                // So when the library is big, an unfiltered shelf draws FOLDER ROWS and no covers
                // at all: forty-five rows you can read, instead of a wall you have to scroll. Tap
                // one and it opens as the only section, with every cover and no cap — which is
                // what "show me this series" should cost. A small shelf (his ebooks alone) is
                // unchanged: below the threshold everything still draws as covers, because that is
                // the nicer thing when it fits.
                if (foldedShelf) continue
                val cap = if (folderFilter != null) Int.MAX_VALUE else SECTION_TILE_CAP
                val shownBooks = books.take(cap)
                val shownGhosts = if (shownBooks.size < cap) offered.take(cap - shownBooks.size) else emptyList()
                binding.shelfColumn.addView(
                    gridOf(shownBooks.map { tile(it) } + shownGhosts.map { ghostTile(it) })
                )
                drawnTiles += shownBooks.size + shownGhosts.size
                val hidden = (books.size + offered.size) - (shownBooks.size + shownGhosts.size)
                if (hidden > 0) binding.shelfColumn.addView(
                    heading("＋ $hidden more in this folder — tap to see them all").apply {
                        isClickable = true
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        setOnClickListener { folderFilter = folder; load() }
                    }
                )
            }
            android.util.Log.i(
                "BookshelfPerf",
                "walk=${tWalk - t0}ms(cache=$cacheHit) hub=${tHub - tWalk}ms " +
                    "views=${System.currentTimeMillis() - tHub}ms " +
                    "total=${System.currentTimeMillis() - t0}ms " +
                    "books=${all.size} folders=${byFolder.size} tiles=$drawnTiles folded=$foldedShelf"
            )
        }
    }

    private fun isComicFile(name: String) = name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".cbr") }

    private fun isComicSeries(folder: String, books: List<BookshelfSource.Entry>, offered: List<com.toolsboox.plugin.calendar.da.v2.LibraryBook>): Boolean =
        folder.isNotBlank() && (books.isNotEmpty() || offered.isNotEmpty()) &&
            books.all { isComicFile(it.name) } && offered.all { isComicFile(it.name) }

    private fun chapterOrder(names: List<String>): List<String> {
        val numbered = names.mapIndexed { i, n -> n to com.toolsboox.plugin.reader.nw.TrackerBridge.chapterNumber(n, i) }
        return numbered.sortedWith(compareBy({ it.second }, { it.first.lowercase() })).map { it.first }
    }

    /** The series card: cover · name · source · chapters read · the next one. */
    private fun seriesRow(folder: String, books: List<BookshelfSource.Entry>, offered: List<com.toolsboox.plugin.calendar.da.v2.LibraryBook>): View {
        val ctx = requireContext()
        val leaf = folder.substringAfterLast('/')
        val parent = folder.substringBeforeLast('/', "").substringAfterLast('/')
        val source = parent.replace(Regex("""\s*\([A-Za-z-]+\)$"""), "").takeIf { it.isNotBlank() && it != "Comics" && it != leaf }.orEmpty()
        val ordered = chapterOrder(books.map { it.name })
        val opened = ordered.filter { BookOpens.lastOpen(ctx, it) != null }
        val lastOpened = opened.maxByOrNull { BookOpens.lastOpen(ctx, it) ?: 0L }
        val next = lastOpened?.let { last -> ordered.dropWhile { it != last }.drop(1).firstOrNull { BookOpens.lastOpen(ctx, it) == null } ?: last } ?: ordered.firstOrNull()
        val total = ordered.size + offered.size
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { folderFilter = folder; load() }
            books.firstOrNull()?.let { first -> setOnLongClickListener { showBookMenu(first); true } }
        }
        val art = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(102)).apply { marginEnd = dp(12) }
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(ctx).apply { text = leaf; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFF000000.toInt()); maxLines = 2 })
        if (source.isNotBlank()) col.addView(TextView(ctx).apply { text = source; textSize = 12f; setTextColor(0xFF777777.toInt()) })
        col.addView(TextView(ctx).apply {
            text = "$total chapter${if (total == 1) "" else "s"} · ${opened.size} read" + (if (offered.isNotEmpty()) " · ${offered.size} on the hub" else "")
            textSize = 12.5f; setTextColor(0xFF555555.toInt()); setPadding(0, dp(2), 0, 0)
        })
        next?.let { n ->
            col.addView(TextView(ctx).apply {
                text = (if (opened.isEmpty()) "▶ Start: " else "▶ Continue: ") + n.substringBeforeLast('.')
                textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFF222222.toInt()); maxLines = 1
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { books.firstOrNull { it.name == n }?.let { open(it) } }
            })
        }
        row.addView(art); row.addView(col)
        // The cover: the synced series record's, else the first chapter's — off the main thread.
        val app = ctx.applicationContext; val first = books.firstOrNull()
        Thread {
            val bmp = runCatching { com.toolsboox.plugin.reader.nw.ComicSeriesStore.get(app, folder)?.let { com.toolsboox.plugin.reader.nw.ComicSeriesStore.cover(it) } }.getOrNull()
                ?: first?.let { runCatching { BookCovers.cover(app, it) }.getOrNull() }
            if (bmp != null) art.post { if (isAdded) art.setImageBitmap(bmp) }
        }.apply { isDaemon = true }.start()
        return row
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
    private fun folderHeading(folder: String, count: Int, elsewhere: Boolean = false): TextView {
        val carry = LibraryHub.carry(requireContext(), folder)
        // THE LEAF, NOT THE PATH. A declared tree three or four levels deep produces folder keys
        // like "ReadAllComics (EN)/Uncanny X-Force by Rick Remender Omnibus", and a heading that
        // long is read as a path rather than as the name of the thing beneath it — which for
        // comics it IS: the shelf groups by folder, so a comics folder is a SERIES and its books
        // are that series' chapters.
        //
        // The full path stays the identity ([folder] is what the carry policy and the fleet
        // manifest key on); only the label is shortened. Where a leaf would be ambiguous the
        // parent still shows, quietly, because two series can share a name across sources.
        val leaf = folder.substringAfterLast('/')
        val parent = folder.substringBeforeLast('/', "").substringAfterLast('/')
        val shown = if (parent.isBlank() || parent == leaf) leaf else "$leaf  ·  $parent"
        val label = if (folder.isBlank()) "All books"
            else if (elsewhere) "☁  $shown" else "🗂  $shown"
        val suffix = (if (carry == LibraryPolicy.Carry.ON_OPEN) " · on-open" else "") +
            if (elsewhere) " · on another device" else ""
        // TAP GOES IN; HOLD CONFIGURES. The tap used to open the carry menu — "should new books in
        // this folder download themselves here" — which is a SETTING about the folder, offered
        // where a person is trying to LOOK at the folder. Michael: "when I click a folder from the
        // bookshelf it ends up opening all books but saying it's the shelf", which is that menu
        // answered in the affirmative: a carry mode that fetches everything the fleet has.
        //
        // A destructive-ish, bandwidth-spending setting should never be what a tap does. The tap
        // now shows the folder; the carry menu keeps its place on the hold, where the rest of this
        // app puts a row's settings.
        return heading("$label · $count$suffix").apply {
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { folderFilter = folder; load() }
            setOnLongClickListener { showCarryMenu(folder); true }
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
     *
     * The column count comes off the [ShelfDensity] ladder — read at build time, so the whole
     * page is laid at ONE density per [load] pass even if the pref changes mid-flight.
     */
    private fun gridOf(tiles: List<View>): View {
        val ctx = requireContext()
        val columns = ShelfDensity.current(ctx).columns
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
        // Cover height, padding and title size all ride the one density rung — three numbers,
        // one decision (see [ShelfDensity]). Cozy is byte-for-byte the old hard-coded tile.
        val d = ShelfDensity.current(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(d.padDp), dp(d.padDp), dp(d.padDp), dp(d.padDp))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { open(entry) }
            setOnLongClickListener { showBookMenu(entry); true }
        }
        val art = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(d.coverDp))
        }
        col.addView(art)
        col.addView(TextView(ctx).apply {
            text = (if (isComicFile(entry.name) && BookOpens.lastOpen(ctx, entry.name) != null) "✓ " else "") + entry.title
            textSize = d.titleSp
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFF000000.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        lifecycleScope.launch {
            // ONE COVER AT A TIME (2026-08-24). Every tile used to launch its own extraction, which
            // was invisible on a shelf of a few dozen epubs and fatal on a shelf of hundreds of
            // comics: several hundred coroutines each opening an archive and decoding a page, all
            // at once. Even with the decode now sampled (see BookCovers.decodeSampled), the
            // CONCURRENCY alone is enough to take the app down — and on an e-ink device it also
            // starves the very redraw the covers are for.
            //
            // A permit rather than a queue of my own: the tiles already arrive in shelf order, so
            // the covers fill top-down, which is the order he is looking at them in.
            coverPermits.withPermit {
                if (!isAdded) return@withPermit
                val bmp = withContext(Dispatchers.IO) { BookCovers.cover(ctx, entry) }
                if (isAdded) art.setImageBitmap(bmp)
            }
        }
        return col
    }

    /**
     * Open a book — and COPY IT OFF THE MAIN THREAD, which is the whole of this function's history.
     *
     * A book behind a declared tree has to be materialised into the cache before the reader can
     * seek in it ([BookshelfSource.materialise]), and that is a byte-for-byte copy. It ran here,
     * synchronously, on the UI thread. For a three-megabyte epub nobody noticed. For one of his
     * comics it is 280 MB, and the device froze for ELEVEN SECONDS — "Skipped 707 frames", the log
     * said, which is an ANR in everything but name and is exactly the "keeps crashing" he reported.
     *
     * A big file also deserves to say it is working: the copy is announced, because an e-ink screen
     * that does not change for several seconds reads as a device that has died.
     */
    private fun open(entry: BookshelfSource.Entry) {
        val ctx = requireContext()
        BookOpens.record(ctx, entry.name)
        // Reading it is wanting it everywhere (the 08-27 ruling) — idempotent, keeps the want young.
        BookWantsStore.want(ctx.applicationContext, LibraryHub.idFor(entry), LibraryHub.deviceName(ctx))
        // Only a tree-backed entry needs the copy; a plain file opens instantly and should not be
        // made to wait behind a spinner it does not need.
        // A tree entry is STREAMED by the reader now — no copy, so nothing to announce and nothing
        // to wait for. Only the legacy path (an entry that is neither file nor tree) still copies.
        val needsCopy = entry.file == null && entry.uri == null
        // SAY HOW BIG, because the wait is proportional to it and silence reads as a hang.
        //
        // A book behind a declared tree is copied into cache before the reader can seek in it, and
        // one of his comics is 280 MB — a real minute of flash on this device even off the main
        // thread. "Opening…" for a minute with no number looks broken; "Opening · 280 MB" looks
        // like work. (Removing the copy entirely — serving the reader straight from the tree —
        // is the right end state and is a change to how the reader is fed, not a message.)
        if (needsCopy) {
            val mb = entry.sizeBytes / (1024 * 1024)
            showMessage(
                if (mb >= 40) "Opening ${entry.title} · ${mb} MB — this one takes a moment"
                else "Opening ${entry.title}…"
            )
        }
        if (entry.uri != null) { finishOpenStreamed(entry); return }
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { BookshelfSource.materialise(ctx, entry) }
            if (!isAdded) return@launch
            if (f == null) { showMessage("Couldn't open ${entry.title}"); return@launch }
            finishOpen(entry, f)
        }
    }

    /** Hand the reader a tree entry to STREAM — the no-copy open. */
    private fun finishOpenStreamed(entry: BookshelfSource.Entry) {
        val uri = entry.uri ?: return
        requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
            .putString("current_book_uri", uri.toString())
            .putString("current_book_name", entry.name)
            .remove("current_book_path")
            .putString(
                "current_reading_id",
                com.toolsboox.plugin.calendar.da.v2.ReadingState.keyFor(entry.folder, entry.name, entry.sizeBytes)
            )
            .apply()
        runCatching { findNavController().navigate(R.id.action_to_reader) }
    }

    private fun finishOpen(entry: BookshelfSource.Entry, f: java.io.File) {
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
        // Same density rung as the real tiles — a ghost stands on the same shelf, so it must be
        // the same size as the book it will become, at every rung of the ladder.
        val d = ShelfDensity.current(ctx)
        val title = BookshelfSource.displayTitle(book.name, book.folder)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(d.padDp), dp(d.padDp), dp(d.padDp), dp(d.padDp))
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(d.coverDp))
        })
        col.addView(TextView(ctx).apply {
            text = "$title — fetch?"
            textSize = d.titleSp
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
            // The tap IS the ask — file it fleet-wide (his 08-27 ruling), so even when the hub
            // doesn't hold the bytes yet, the device that does answers on its next pass.
            BookWantsStore.want(ctx.applicationContext, book.id, LibraryHub.deviceName(ctx))
            LibraryHub.fetch(ctx.applicationContext, book) { ok ->
                if (!isAdded) return@fetch
                if (ok) {
                    showMessage("On the shelf · $title")
                    load()
                } else {
                    // The want just filed turns the old dead end into a promise the fleet keeps:
                    // the holding device uploads on its next hourly pass, this one fetches after.
                    showMessage("Asked the fleet for $title — it'll arrive after the holding device's next pass.")
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
        // The book's marks, FROM THE SHELF — you should not have to open a book to read what
        // you wrote in it, or to leave a note on it. Same store the reader uses (keyed by name
        // without extension, the fleet-portable key), so nothing here is a second system.
        val noteKey = entry.name.substringBeforeLast('.')
        val markCount = BookNoteStore.list(ctx, noteKey, BookNote.ANNOTATION).size +
            BookNoteStore.list(ctx, noteKey, BookNote.BOOKMARK).size +
            BookNoteStore.list(ctx, noteKey, BookNote.NOTE).size
        rows += (if (markCount > 0) "🗒  Marks & notes ($markCount)…" else "🗒  Marks & notes…") to {
            BookNotesPanel.bind(noteKey)
            BookNotesPanel.show(
                ctx, noteKey, emptyList(), BookNote.ANNOTATION,
                showModal = { showModal(it) },
                onJump = { open(entry) },   // the passage lives in the book — open it there
                onUnmark = { }              // nothing drawn to undraw; the store forgets it
            )
        }
        rows += "🌐  Keep this everywhere" to {
            BookWantsStore.want(ctx.applicationContext, id, LibraryHub.deviceName(ctx))
            showMessage("The fleet will carry ${entry.title}.")
        }
        rows += "🖍  Note on this book…" to {
            BookNotesPanel.bind(noteKey)
            BookNotesPanel.editNote(ctx, null, showModal = { showModal(it) }) {
                showMessage("Saved · a note on ${entry.title}")
            }
        }
        // 📕 THE SERIES' RECORD (09-13): what the source knew — cover, description, status,
        // chapter count — synced beside the chapters.
        val sr = com.toolsboox.plugin.reader.nw.ComicSeriesStore.get(ctx, entry.folder)
        if (sr != null) {
            rows += "📕  ${sr.title} · ${sr.source}${if (sr.status.isNotBlank()) " · ${sr.status}" else ""} · ${sr.chapters.size} ch" to { showComicSeriesInfo(sr) }
        } else if (entry.name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".cbr") } && entry.folder.isNotBlank()) {
            // No record yet (a Mihon download, a hub arrival): ask the server for the series'
            // face in the background — the next hold shows it.
            val app = ctx.applicationContext; val folder = entry.folder
            Thread { runCatching { com.toolsboox.plugin.reader.nw.ComicSeriesStore.ensure(app, folder) } }.apply { isDaemon = true }.start()
        }
        // 📈 TRACKERS (09-13): a comic series bound to AniList / MyAnimeList / Kitsu / … through
        // Suwayomi's trackers; every finished chapter in the reader pushes the progress.
        if (entry.name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".cbr") } && entry.folder.isNotBlank()) {
            val bound = com.toolsboox.plugin.reader.nw.TrackerStore.bound(ctx, entry.folder)
            val prog = if (bound != null && bound.lastChapter > 0f) " · read #${if (bound.lastChapter % 1f == 0f) bound.lastChapter.toInt() else bound.lastChapter}${if (bound.total > 0) "/${bound.total}" else ""}" else ""
            rows += (if (bound != null) "📈  Tracked on ${bound.trackerName} · ${bound.title}$prog" else "📈  Track this series…") to {
                if (bound != null) showTrackedMenu(entry, bound) else trackSeries(entry)
            }
        }
        val tombstone = removedButLocal[id]
        if (tombstone != null) {
            // Another device removed this book from the LIBRARY; this device still holds a copy.
            // The tombstone is a question here, never an action — no merge result ever deletes a
            // local file without this person's own answer.
            rows += "🕊  Removed from the library — keep your copy?" to { showKeepOrDelete(entry, id) }
        } else {
            // Two ways out (Michael, 09-13: "when I finish a comic book, I don't need it on there
            // anymore unless I wanna keep it… same with finished books"). Both are the person's
            // own answer on this device — declared tree or not; nothing else ever deletes there.
            rows += "✓  Finished — delete everywhere…" to { confirmRemove(entry, id) }
            rows += "🗑  Delete from this device" to {
                if (LibraryHub.deleteHere(ctx.applicationContext, entry)) showMessage("Deleted here · ${entry.title}")
                else showMessage("Couldn't delete ${entry.title}.")
                load()
            }
        }
        showIconMenu(entry.title, rows)
    }

    private fun showComicSeriesInfo(sr: com.toolsboox.plugin.reader.nw.ComicSeriesStore.Series) {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt()) }
        com.toolsboox.plugin.reader.nw.ComicSeriesStore.cover(sr)?.let { bmp ->
            col.addView(android.widget.ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true; maxHeight = (260 * dp).toInt(); scaleType = android.widget.ImageView.ScaleType.FIT_START })
        }
        val meta = listOf(sr.author, sr.artist.takeIf { it != sr.author }.orEmpty(), sr.status, sr.genre).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) col.addView(android.widget.TextView(ctx).apply { text = meta; textSize = 13f; setTextColor(0xFF444444.toInt()) })
        col.addView(android.widget.TextView(ctx).apply { text = sr.description.ifBlank { "No description from ${sr.source}." }; textSize = 14f; setPadding(0, (6 * dp).toInt(), 0, 0) })
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(sr.title)
            .setView(androidx.core.widget.NestedScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Close", null)
            .apply { if (sr.url.startsWith("http")) setNeutralButton("Open on ${sr.source}") { _, _ ->
                findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                    com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true,
                    com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_TARGET to sr.url))
            } }
            .show()
    }

    // ── Trackers ─────────────────────────────────────────────────────────────────────────────

    private fun trackerServerOrAsk(): com.toolsboox.plugin.reader.nw.TrackerBridge.Server? {
        val srv = com.toolsboox.plugin.reader.nw.TrackerBridge.server(requireContext())
        if (srv == null) showTrackerServerDialog()
        return srv
    }

    private fun showTrackerServerDialog() {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        val url = android.widget.EditText(ctx).apply { hint = "Suwayomi address (suwayomi.mjh.yoga)"; isSingleLine = true }
        val user = android.widget.EditText(ctx).apply { hint = "User"; isSingleLine = true }
        val pass = android.widget.EditText(ctx).apply { hint = "Password"; isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(url); col.addView(user); col.addView(pass)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Trackers run on your Suwayomi")
            .setMessage("The trackers (AniList, MyAnimeList, Kitsu, MangaUpdates, Shikimori, Bangumi) are Suwayomi's. Name the server once.")
            .setView(col)
            .setPositiveButton("Save") { d, _ ->
                d.dismiss()
                com.toolsboox.plugin.reader.nw.TrackerBridge.setServer(ctx, url.text.toString(), user.text.toString(), pass.text.toString())
                showMessage("Saved · Trackers")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Bookshelf › wrench › Trackers: the comics trackers (Komga, Comic Vine, Metron — the ones
     *  that know DC and Marvel), then the manga trackers Suwayomi offers. */
    private fun showTrackersMenu() {
        val ctx = requireContext()
        val ct = com.toolsboox.plugin.reader.nw.ComicTrackers
        val head = mutableListOf<Pair<String, () -> Unit>>(
            "${if (ct.komga(ctx) != null) "●" else "○"}  Komga${ct.komga(ctx)?.let { " · " + it.base.removePrefix("https://") } ?: " · your comics server"}" to { showKomgaDialog() },
            "${if (ct.comicVineKey(ctx).isNotBlank()) "●" else "○"}  Comic Vine${if (ct.comicVineKey(ctx).isNotBlank()) " · key set" else " · API key"}" to { showComicVineDialog() },
            "${if (ct.metron(ctx) != null) "●" else "○"}  Metron${ct.metron(ctx)?.let { " · " + it.user } ?: " · sign in"}" to { showMetronDialog() },
        )
        val srv = com.toolsboox.plugin.reader.nw.TrackerBridge.server(ctx)
        if (srv == null) { showIconMenu("Trackers", head + ("⚙  Suwayomi server (manga trackers)…" to { showTrackerServerDialog() })); return }
        showMessage("📈 Asking Suwayomi…")
        Thread {
            val list = runCatching { com.toolsboox.plugin.reader.nw.TrackerBridge.trackers(srv) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val trackers = list.getOrElse { showIconMenu("Trackers", head); showMessage(it.message ?: "Couldn't reach Suwayomi's trackers."); return@runOnUiThread }
                val rows = head + trackers.map { t ->
                    "${if (t.loggedIn) "●" else "○"}  ${t.name}${if (t.loggedIn) " · signed in" else ""}" to {
                        if (t.loggedIn) {
                            showIconMenu(t.name, listOf("Sign out" to {
                                Thread { runCatching { com.toolsboox.plugin.reader.nw.TrackerBridge.logout(srv, t.id) }; activity?.runOnUiThread { showMessage("Signed out of ${t.name}") } }.start()
                            }))
                        } else if (t.authUrl.isNotBlank()) {
                            // OAuth: the tracker's own page in The Net; the redirect comes back to
                            // SiteWebFragment, which hands the URL to Suwayomi.
                            com.toolsboox.plugin.reader.nw.TrackerBridge.setPendingLogin(ctx, t.id)
                            findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                                com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true,
                                com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_TARGET to t.authUrl))
                        } else showTrackerCredentialsDialog(srv, t)
                    }
                } + ("⚙  Suwayomi server…" to { showTrackerServerDialog() })
                showIconMenu("Trackers", rows)
            }
        }.start()
    }

    private fun threeFieldDialog(title: String, message: String, fields: List<Pair<String, String>>, secretLast: Boolean, onSave: (List<String>) -> Unit) {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        val edits = fields.mapIndexed { i, (hint, value) ->
            android.widget.EditText(ctx).apply {
                this.hint = hint; isSingleLine = true; setText(value)
                if (secretLast && i == fields.lastIndex) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also { col.addView(it) }
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title).setMessage(message).setView(col)
            .setPositiveButton("Save") { d, _ -> d.dismiss(); onSave(edits.map { it.text.toString() }); showMessage("Saved · $title") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showKomgaDialog() {
        val ctx = requireContext(); val k = com.toolsboox.plugin.reader.nw.ComicTrackers.komga(ctx)
        threeFieldDialog("Komga", "Your comics server. A series tracked here has its chapters marked read on the server as you finish them — every Komga client sees it.",
            listOf("Address (comics.mjh.yoga)" to (k?.base ?: ""), "Email" to (k?.user ?: ""), "Password" to (k?.pass ?: "")), true) { v ->
            com.toolsboox.plugin.reader.nw.ComicTrackers.setKomga(ctx, v[0], v[1], v[2])
        }
    }

    private fun showComicVineDialog() {
        val ctx = requireContext()
        threeFieldDialog("Comic Vine", "The comics database — every DC and Marvel volume. A free API key from comicvine.gamespot.com/api. The read record stays in Ledgable.",
            listOf("API key" to com.toolsboox.plugin.reader.nw.ComicTrackers.comicVineKey(ctx)), true) { v ->
            com.toolsboox.plugin.reader.nw.ComicTrackers.setComicVineKey(ctx, v[0])
        }
    }

    private fun showMetronDialog() {
        val ctx = requireContext(); val m = com.toolsboox.plugin.reader.nw.ComicTrackers.metron(ctx)
        threeFieldDialog("Metron", "The open comics metadata database (metron.cloud) — a free account. The read record stays in Ledgable.",
            listOf("Username" to (m?.user ?: ""), "Password" to (m?.pass ?: "")), true) { v ->
            com.toolsboox.plugin.reader.nw.ComicTrackers.setMetron(ctx, v[0], v[1])
        }
    }

    private fun showTrackerCredentialsDialog(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, t: com.toolsboox.plugin.reader.nw.TrackerBridge.Tracker) {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        val user = android.widget.EditText(ctx).apply { hint = "User"; isSingleLine = true }
        val pass = android.widget.EditText(ctx).apply { hint = "Password"; isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        col.addView(user); col.addView(pass)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Sign in to ${t.name}").setView(col)
            .setPositiveButton("Sign in") { d, _ ->
                d.dismiss()
                Thread {
                    val ok = runCatching { com.toolsboox.plugin.reader.nw.TrackerBridge.loginCredentials(srv, t.id, user.text.toString(), pass.text.toString()) }.getOrDefault(false)
                    activity?.runOnUiThread { showMessage(if (ok) "Signed in to ${t.name}" else "${t.name} didn't accept that.") }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** "Track this series…": tracker → search (title prefilled) → pick → bound. */
    private fun trackSeries(entry: BookshelfSource.Entry) {
        val ctx = requireContext()
        val series = com.toolsboox.plugin.reader.nw.TrackerBridge.seriesOf(entry.folder).ifBlank { entry.folder.substringAfterLast('/') }
        val comics = com.toolsboox.plugin.reader.nw.ComicTrackers.providers(ctx)
            .map { p -> "📈  ${p.name}" to { comicTrackSearch(p, entry, series) } }
        val srv = com.toolsboox.plugin.reader.nw.TrackerBridge.server(ctx)
        if (srv == null) {
            if (comics.isEmpty()) { showMessage("Set up a tracker first — Bookshelf › wrench › Trackers"); showTrackersMenu(); return }
            showIconMenu("Track “$series” on", comics); return
        }
        Thread {
            val list = runCatching { com.toolsboox.plugin.reader.nw.TrackerBridge.trackers(srv) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val signedIn = list.getOrDefault(emptyList()).filter { it.loggedIn }
                val manga = signedIn.map { t -> "📈  ${t.name} (manga)" to { trackSearch(srv, t, entry, series) } }
                if (comics.isEmpty() && manga.isEmpty()) { showMessage("Set up a tracker first — Bookshelf › wrench › Trackers"); showTrackersMenu(); return@runOnUiThread }
                showIconMenu("Track “$series” on", comics + manga)
            }
        }.start()
    }

    /** Komga / Comic Vine / Metron: search (title prefilled) → pick → bound. */
    private fun comicTrackSearch(p: com.toolsboox.plugin.reader.nw.ComicTrackers.Provider, entry: BookshelfSource.Entry, series: String) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { setText(series); isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Search ${p.name}").setView(input)
            .setPositiveButton("Search") { d, _ ->
                d.dismiss()
                val q = input.text.toString().trim().ifBlank { series }
                showMessage("📈 Searching ${p.name}…")
                Thread {
                    val hits = runCatching { com.toolsboox.plugin.reader.nw.ComicTrackers.search(ctx.applicationContext, p.key, q) }
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        val list = hits.getOrElse { showMessage(it.message ?: "Search failed."); return@runOnUiThread }
                        if (list.isEmpty()) { showMessage("${p.name} has nothing for “$q”."); return@runOnUiThread }
                        showIconMenu("${p.name} · “$q”", list.take(15).map { h ->
                            "${h.title}${if (h.detail.isNotBlank()) " · ${h.detail}" else ""}" to {
                                val b = com.toolsboox.plugin.reader.nw.TrackerBridge.Bound(0, 0, p.name, 0, h.title, h.url,
                                    provider = p.key, remoteId = h.remoteId, total = h.total)
                                com.toolsboox.plugin.reader.nw.TrackerStore.bind(ctx.applicationContext, entry.folder, b)
                                showMessage("📈 Tracked on ${p.name} · ${h.title}")
                            }
                        })
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun trackSearch(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, t: com.toolsboox.plugin.reader.nw.TrackerBridge.Tracker, entry: BookshelfSource.Entry, series: String) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { setText(series); isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Search ${t.name}").setView(input)
            .setPositiveButton("Search") { d, _ ->
                d.dismiss()
                val q = input.text.toString().trim().ifBlank { series }
                showMessage("📈 Searching ${t.name}…")
                Thread {
                    val hits = runCatching { com.toolsboox.plugin.reader.nw.TrackerBridge.search(srv, t.id, q) }
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        val list = hits.getOrElse { showMessage(it.message ?: "Search failed."); return@runOnUiThread }
                        if (list.isEmpty()) { showMessage("${t.name} has nothing for “$q”."); return@runOnUiThread }
                        showIconMenu("${t.name} · “$q”", list.take(12).map { h ->
                            "${h.title}${if (h.totalChapters > 0) " · ${h.totalChapters} ch" else ""}" to { trackBind(srv, t, entry, h) }
                        })
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun trackBind(srv: com.toolsboox.plugin.reader.nw.TrackerBridge.Server, t: com.toolsboox.plugin.reader.nw.TrackerBridge.Tracker, entry: BookshelfSource.Entry, hit: com.toolsboox.plugin.reader.nw.TrackerBridge.Hit) {
        val app = requireContext().applicationContext
        showMessage("📈 Binding…")
        Thread {
            val r = runCatching {
                val (mangaId, title) = com.toolsboox.plugin.reader.nw.TrackerBridge.libraryManga(srv, entry.folder)
                    ?: throw com.toolsboox.plugin.reader.nw.TrackerBridge.TrackerException("Suwayomi's ${com.toolsboox.plugin.reader.nw.TrackerBridge.sourceOf(entry.folder)?.first ?: "source"} can't find “${com.toolsboox.plugin.reader.nw.TrackerBridge.seriesOf(entry.folder)}” — install that source on Suwayomi first.")
                val recordId = com.toolsboox.plugin.reader.nw.TrackerBridge.bind(srv, mangaId, t.id, hit.remoteId)
                    ?: throw com.toolsboox.plugin.reader.nw.TrackerBridge.TrackerException("${t.name} didn't bind.")
                val b = com.toolsboox.plugin.reader.nw.TrackerBridge.Bound(recordId, t.id, t.name, mangaId, hit.title, hit.url)
                com.toolsboox.plugin.reader.nw.TrackerStore.bind(app, entry.folder, b)
                b
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                r.onSuccess { showMessage("📈 Tracked on ${it.trackerName} · ${it.title}") }
                    .onFailure { showMessage(it.message ?: "Couldn't bind.") }
            }
        }.start()
    }

    private fun showTrackedMenu(entry: BookshelfSource.Entry, bound: com.toolsboox.plugin.reader.nw.TrackerBridge.Bound) {
        val app = requireContext().applicationContext
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        if (bound.remoteUrl.startsWith("http")) rows += "🌐  Open on ${bound.trackerName}" to {
            findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true,
                com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_TARGET to bound.remoteUrl))
        }
        rows += "🔁  Track on another…" to { trackSeries(entry) }
        rows += "✕  Stop tracking" to {
            com.toolsboox.plugin.reader.nw.TrackerStore.unbind(app, entry.folder)
            if (bound.provider == com.toolsboox.plugin.reader.nw.ComicTrackers.SUWAYOMI)
                com.toolsboox.plugin.reader.nw.TrackerBridge.server(app)?.let { srv -> Thread { com.toolsboox.plugin.reader.nw.TrackerBridge.unbind(srv, bound.recordId) }.start() }
            showMessage("Stopped tracking · ${bound.title}")
        }
        showIconMenu("${bound.trackerName} · ${bound.title}", rows)
    }

    /** The guarded delete: what it does fleet-wide is said BEFORE it happens, in its own words. */
    private fun confirmRemove(entry: BookshelfSource.Entry, id: String) {
        val ctx = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(entry.title)
            .setMessage(
                "Finished with this book?\n\nIt is deleted from this device and from the library's " +
                    "hub now. Other devices keep their copies and are asked, not told."
            )
            .setPositiveButton("Delete everywhere") { d, _ ->
                d.dismiss()
                val gone = LibraryHub.deleteEverywhere(ctx.applicationContext, entry)
                showMessage(if (gone) "Deleted everywhere · ${entry.title}" else "Couldn't delete ${entry.title}.")
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
        // The person's own answer deletes the copy wherever it sits — app directory or declared
        // tree (09-13; the old declared-tree refusal was for merges and passes, never for this).
        run {
            rows += "🗑  Delete my copy too" to {
                val gone = BookshelfSource.delete(ctx.applicationContext, entry)
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
