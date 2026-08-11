package com.toolsboox.plugin.calendar.ui

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.DateFormat
import android.view.View
import android.graphics.Bitmap
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.databinding.FragmentLedgerItemsBinding
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.Contact
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.ContactStore
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
import java.util.Locale
import javax.inject.Inject

/** Marks a synthetic row as a site booking (the rest of the id is the booking id). */
private const val BOOKING_PREFIX = "booking-"

/**
 * Boards & Tasks — the structured items extracted from the days' handwriting, seen two ways.
 *
 * ONE fragment, two renderings of the same drawer: the LIST (per day, or filtered across a
 * period; rows show text or ink per the item's own toggle, tasks check off, events show their
 * time) and the COLUMNS (the whole ledger's tasks arranged into To do / Doing / Waiting / Done
 * swimlanes, ‹ › moving a card between stages). The ▤/☰ rail button flips the lens IN PLACE and
 * the choice is remembered, so the hub's one "Boards & Tasks" door lands where you left off.
 * This used to be two fragments over one dataset routed by whichever you last used — the seam
 * the old router's comment promised someone would close. Closed.
 *
 * Edits (done / stage / display) persist back into the item's own day JSON so they sync, from
 * either lens, through the same [persist] / [deleteItems] plumbing.
 */
@AndroidEntryPoint
class LedgerItemsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    /** Reads the whole ledger as citable snippets — what Prep-from-schedule draws from. */
    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    override val view = R.layout.fragment_ledger_items

    private lateinit var binding: FragmentLedgerItemsBinding
    private lateinit var adapter: LedgerItemAdapter
    private var contactsById: Map<String, Contact> = emptyMap()
    private var navBar: CalendarNavBarHost? = null
    // "day" = the single anchor day; "week"/"month"/"quarter"/"year" = filter across that period.
    private var navPeriod: String = "day"
    // Each shown item's source day file, so a done-toggle/delete saves to the right day when filtering.
    private val itemSourceDay = HashMap<String, LocalDate>()

    private var anchor: LocalDate = LocalDate.now()
    private var day: CalendarDay? = null

    // ── The columns lens ───────────────────────────────────────────────────────────────────────
    // Stage order across the board. Waiting sits between Doing and Done — the lane for work that
    // sits on someone else, which until now had nowhere to stand but "todo" (where it nagged) or
    // "done" (where it lied). A stage VALUE, not a new wire key: an older reader that only knows
    // todo/doing/done folds "waiting" into To do (see [colOf]), which is honest degradation.
    private val stageOrder = listOf("todo", "doing", "waiting", "done")

    /** Which lens is up: false = list, true = columns. Mirrors the remembered pref
     *  (`ledger_tasks_view`) that used to route between two fragments and now just names a mode. */
    private var columnsMode = false

    private var selectedBoard: String? = null
    private var boards: List<com.toolsboox.plugin.calendar.da.v2.Board> = emptyList()

    // Opt-in: fold DUE-DATED Site board cards into both lenses (read-only), so Local reads as one
    // board over both sources. Default off — zero change until turned on.
    private var includeSite: Boolean = false
    private fun kanbanPrefs() =
        requireContext().getSharedPreferences("ledger_kanban", android.content.Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerItemsBinding.bind(view)

        adapter = LedgerItemAdapter(emptyList(), emptyMap(), ::persist, ::onEnterSelection, ::updateSelectionBar,
            onOpenCard = ::openCard, onShowRhizome = ::showRhizome,
            onAsk = ::askAboutItem, onEducate = ::educateFromItem)
        binding.itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.itemsRecycler.adapter = adapter
        binding.itemsRecycler.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        // Reuse the real Almanac navigator: arrows step this surface's date in place,
        // the day/week/month slots jump into the calendar (see CalendarNavBarHost).
        navBar = CalendarNavBarHost(requireContext(), binding.navigatorImageView, this,
            onStepDay = { d -> anchor = d; load() },
            // Tapping a period slot (Day/Week/Month/Quarter/Year) FILTERS the list to that period
            // instead of opening the almanac page for it.
            onSelectPeriod = { period, d -> navPeriod = period; anchor = d; load() })
        binding.ledgerButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        binding.addTaskButton.setOnClickListener { showItemEntry(LedgerItem.Kind.TASK) }
        binding.addEventButton.setOnClickListener { showItemEntry(LedgerItem.Kind.EVENT) }

        // Floating nav pill: ‹ up · ☀ today-then-menu · down ›.
        binding.itemsPageUp.setOnClickListener {
            binding.itemsRecycler.scrollBy(0, -(binding.itemsRecycler.height * 9 / 10))
        }
        binding.itemsPageDown.setOnClickListener {
            binding.itemsRecycler.scrollBy(0, binding.itemsRecycler.height * 9 / 10)
        }
        binding.itemsGoto.setOnClickListener {
            if (anchor == LocalDate.now()) {
                showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
            } else {
                anchor = LocalDate.now(); load()
            }
        }
        // The floating pill and the header's directory button retire into the rail: ☰ Hub is
        // the same accordion door, and the pill's ‹ ☀ › trio ride as rail icons. The add bar
        // (＋ Task / ＋ Event / ☑ Select) stays where it is — that is capture, not chrome.
        binding.itemsPill.visibility = View.GONE
        binding.ledgerButton.visibility = View.GONE
        binding.itemsBoard.setOnClickListener { showBoardPicker() }
        // The remembered lens has to be read BEFORE the rail dresses itself — the rail's action
        // provider branches on it, and a rail dressed for the wrong lens offers verbs the screen
        // doesn't have.
        columnsMode = com.toolsboox.plugin.feeds.ui.tasksModeIsStage(requireContext())
        // The display-mode flip. The list and the swimlane board are the SAME drawer seen two
        // ways — "a note is a place, a board is a lens" — so the hub has one Boards & Tasks door
        // and the choice of lens lives in here, remembered, rather than being a second row in the
        // menu that made a view look like a place. Both lenses are this one fragment now, so the
        // flip is a re-dress in place, not a navigation; the rail re-asks its actions and offers
        // whichever verbs the visible lens actually has.
        setupActionRail(
            binding.itemsRail, "items",
            actions = { if (columnsMode) listOf(
                com.toolsboox.ot.TuckPanel.Item(0, "New task", glyph = "＋") {
                    binding.addTaskButton.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(0, "Boards", glyph = "▤") {
                    showBoardPicker()
                },
                com.toolsboox.ot.TuckPanel.Item(0, "List", glyph = "☰") {
                    setColumnsMode(false)
                }
            ) else listOf(
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up, "Page up") {
                    binding.itemsPageUp.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today") {
                    binding.itemsGoto.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down, "Page down") {
                    binding.itemsPageDown.performClick()
                },
                com.toolsboox.ot.TuckPanel.Item(0, "By stage", glyph = "▤") {
                    setColumnsMode(true)
                }
            ) }
        )
        applyMode()

        // Enter bulk-select without hunting for a long-press; the whole row then toggles.
        binding.selectButton.setOnClickListener { adapter.startEmptySelection() }
        binding.selectAllButton.setOnClickListener { adapter.selectAll() }
        binding.deleteSelectedButton.setOnClickListener { deleteSelected() }
        binding.cancelSelectionButton.setOnClickListener { adapter.clearSelection() }
        attachSwipeToDelete()

        load()
    }

    /** Swipe a row left/right to delete that single task/event (disabled during bulk-select). */
    private fun attachSwipeToDelete() {
        val cb = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView,
                                a: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                                b: androidx.recyclerview.widget.RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: androidx.recyclerview.widget.RecyclerView,
                                      vh: androidx.recyclerview.widget.RecyclerView.ViewHolder): Int {
                if (adapter.selecting) return 0
                val item = adapter.currentItems().getOrNull(vh.adapterPosition)
                if (item != null && adapter.isReadOnly(item.id)) return 0   // read-only site cards aren't deletable
                return super.getSwipeDirs(rv, vh)
            }
            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                val item = adapter.currentItems().getOrNull(pos) ?: return
                // Confirm; on cancel, snap the row back.
                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                    .setTitle("Delete this ${if (item.kind == LedgerItem.Kind.EVENT) "event" else "task"}?")
                    .setMessage(item.text.ifBlank { "(handwritten)" })
                    .setPositiveButton("Delete") { _, _ -> deleteItems(listOf(item)) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> adapter.notifyItemChanged(pos) }
                    .setOnCancelListener { adapter.notifyItemChanged(pos) }
                    .show()
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(cb).attachToRecyclerView(binding.itemsRecycler)
    }

    private fun onEnterSelection() { updateSelectionBar() }

    /** Reflect selection state: show the action bar (with count) vs the add buttons. */
    private fun updateSelectionBar() {
        val selecting = adapter.selecting
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.addBar.visibility = if (selecting) View.GONE else View.VISIBLE
        val n = adapter.selectedIds.size
        binding.selectionCount.text = if (n == 0) "Tap items to select" else "$n selected"
    }

    private fun deleteSelected() {
        val ids = adapter.selectedIds.toSet()
        if (ids.isEmpty()) { adapter.clearSelection(); return }
        val d = day ?: return
        val toDelete = d.ledgerItems.filter { it.id in ids }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Delete ${toDelete.size} item(s)?")
            .setPositiveButton("Delete") { _, _ -> adapter.clearSelection(); deleteItems(toDelete) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Remove [toDelete] from the day JSON (so they stop syncing) and their VTODOs. */
    private fun deleteItems(toDelete: List<LedgerItem>) {
        lifecycleScope.launch {
            // Local removal + tombstone only — this is the fast path the UI waits on.
            withContext(Dispatchers.IO) {
                val root = documentsRoot()
                // Group by source day so a filtered (period) delete saves to the right day file each.
                toDelete.groupBy { itemSourceDay[it.id] ?: anchor }.forEach { (srcDay, group) ->
                    runCatching {
                        val cd = calendarDayService.load(root, srcDay, null, Locale.getDefault())
                        // Item + tombstone + the ON-PAGE face it wears, in one place — see
                        // CalendarDay.deleteLedgerItems. Ink tasks are strokes (by strokeIds),
                        // typed tasks are text boxes matched on their words; both are tombstoned
                        // so neither the item nor its face returns through a sync union.
                        cd.deleteLedgerItems(group)
                        calendarDayService.save(root, srcDay, cd)
                    }.onFailure { Timber.w(it, "ledger items: delete save failed") }
                }
            }
            // Refresh the list right away — don't make the user wait on the network.
            load()
        }
        // Fire the remote VTODO/event deletes in the background; they must not block the UI.
        val app = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            toDelete.forEach { runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.deleteTask(app, it) } }
            toDelete.forEach { runCatching { com.toolsboox.plugin.calendar.nw.LedgerEventSync.deleteEvent(app, it) } }
        }
    }

    override fun onResume() { super.onResume(); load() }

    /** Render whichever lens is up. Both stand on the [TaskWalkCache]-backed gathers, so neither
     *  re-reads a day file that hasn't changed since the last look. */
    private fun load() {
        if (columnsMode) loadColumns() else loadList()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val root = documentsRoot()
            val period = navPeriod
            val (d, strokes, pattern) = withContext(Dispatchers.IO) {
                itemSourceDay.clear()
                val map = HashMap<String, Stroke>()
                val cd: CalendarDay? = if (period == "day") {
                    val loaded = runCatching { calendarDayService.load(root, anchor, null, Locale.getDefault()) }
                        .onFailure { Timber.w(it, "ledger items: load failed") }.getOrNull()
                    loaded?.calendarStrokes?.values?.forEach { l -> l.forEach { map[it.strokeId.toString()] = it } }
                    loaded?.noteStrokes?.values?.forEach { l -> l.forEach { map[it.strokeId.toString()] = it } }
                    loaded?.ledgerItems?.forEach { itemSourceDay[it.id] = anchor }
                    loaded
                } else {
                    // Filter mode: gather items across the whole period, tagging each with its day.
                    val (start, end) = periodRange(period, anchor)
                    val gathered = gatherRange(root, start, end, map)
                    CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                        .also { it.ledgerItems = gathered.toMutableList() }
                }
                val pat = runCatching { calendarPatternService.load(root, anchor, Locale.getDefault()) }
                    .onFailure { Timber.w(it, "ledger items: pattern load failed") }.getOrNull()
                Triple(cd, map, pat)
            }
            day = d
            contactsById = withContext(Dispatchers.IO) {
                ContactStore.list(requireContext().applicationContext).associateBy { it.id }
            }
            renderBirthdays()
            // Redraw the reused Almanac navigator for this date.
            val navDay = d ?: CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
            pattern?.let { navBar?.render(navDay, it) }
            // Tasks first, then events; done tasks sink to the bottom of the task group.
            // Deduped on the words on the way in, so a task that got born twice under two ids
            // reads as one row here even on days whose file already holds both. The file is left
            // as it is — carry-over collapses the pair for good the next time it runs, and a list
            // that hides a row is recoverable in a way that a save which deletes one is not.
            // Tombstoned ids are hidden the same way, defensively: a deleted item shouldn't be
            // in the file at all, but if a pre-tombstone merge left a copy, it must not show.
            // (Range mode's `d` is synthetic with empty tombstone lists; its gather already
            // filters through loadLedgerItems.)
            val dead = ((d?.deletedItemIds ?: emptyList()) + (d?.deletedElementIds ?: emptyList())).toSet()
            val items = com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe
                .dedupe((d?.ledgerItems ?: emptyList()).filterNot { it.id in dead })
                .sortedWith(
                    // Soonest first. It used to sort by `top` — where the item happens to sit on
                    // the page — which is the order you wrote things in, not the order they
                    // matter in. With the Tasks panel now smaller, what shows without scrolling
                    // should be what is closest to due. Done still sinks, and events follow
                    // tasks; ties fall back to position so the page and the list agree.
                    compareBy(
                        { it.kind != LedgerItem.Kind.TASK },
                        { it.kind == LedgerItem.Kind.TASK && it.done },
                        { it.date.time },
                        { it.top }
                    )
                )
            adapter = LedgerItemAdapter(
                items, strokes, ::persist, ::onEnterSelection, ::updateSelectionBar, ::assign,
                { id -> contactsById[id] },
                onReadOnlyTap = { item ->
                    // Two kinds of read-only row land here now: a Site card (deep-link to its
                    // board) and a booking (open the sheet). Same rail, different destination.
                    val bookingId = item.id.removePrefix(BOOKING_PREFIX).toLongOrNull()
                        ?.takeIf { item.id.startsWith(BOOKING_PREFIX) }
                    if (bookingId != null) {
                        BookingSheet.open(this@LedgerItemsFragment, bookingId)
                    } else {
                        val bid = item.board.toIntOrNull() ?: 0
                        findNavController().navigate(
                            R.id.action_to_site_boards,
                            if (bid > 0) androidx.core.os.bundleOf("site_board_id" to bid) else null
                        )
                    }
                },
                onOpenCard = ::openCard,
                onShowRhizome = ::showRhizome,
                onAsk = ::askAboutItem, onEducate = ::educateFromItem
            )
            binding.itemsRecycler.adapter = adapter
            attachSwipeToDelete()
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            mergeSiteDueCards(items)
            // The Spiral learner card used to auto-render here — one guessed thing from the
            // corpus, full article body and all, at the foot of the tasks. Retired on Michael's
            // screenshot and ruling (2026-08-10): a surface that guesses has no seat on the
            // task list. Spiral's machinery survives for the surfaces that ASK it.
            binding.learnerCard.visibility = View.GONE
        }
    }

    // ── The columns lens ───────────────────────────────────────────────────────────────────────

    /** Flip the lens in place: remember the choice, re-dress the chrome and the rail, re-render. */
    private fun setColumnsMode(columns: Boolean) {
        if (columnsMode == columns) return
        columnsMode = columns
        com.toolsboox.plugin.feeds.ui.setTasksModeStage(requireContext(), columns)
        applyMode()
        rebuildActionRail("items")
        load()
    }

    /**
     * Dress the chrome for the visible lens — only what differs is touched, so a flip repaints
     * the content cell and the header, not the whole screen.
     *
     * The board view reads the whole ledger, so the day navigator would promise a scoping it does
     * not do; the header shows the board name instead. Bulk-select and ＋Event are list verbs —
     * selection lives in the recycler, and events have no lane on a board of tasks. ＋Task serves
     * both lenses (a card born while a board is open joins that board, see [createManual]).
     */
    private fun applyMode() {
        binding.itemsColumns.visibility = if (columnsMode) View.VISIBLE else View.GONE
        binding.itemsRecycler.visibility = if (columnsMode) View.GONE else View.VISIBLE
        binding.navigatorImageView.visibility = if (columnsMode) View.GONE else View.VISIBLE
        binding.itemsBoard.visibility = if (columnsMode) View.VISIBLE else View.GONE
        binding.selectButton.visibility = if (columnsMode) View.GONE else View.VISIBLE
        binding.addEventButton.visibility = if (columnsMode) View.GONE else View.VISIBLE
        if (columnsMode) {
            adapter.clearSelection()
            binding.emptyText.visibility = View.GONE
            binding.birthdaysContainer.visibility = View.GONE
            binding.learnerCard.visibility = View.GONE
        }
    }

    /** The columns render: every task across the ledger, bucketed by stage, filtered by board. */
    private fun loadColumns() {
        includeSite = kanbanPrefs().getBoolean("include_site", false)
        lifecycleScope.launch {
            boards = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.BoardsStore.list(requireContext())
            }
            updateBoardLabel()
            val root = documentsRoot()
            val all = withContext(Dispatchers.IO) { itemSourceDay.clear(); gatherAllTasks(root) }
            val tasks = selectedBoard?.let { b -> all.filter { it.board == b } } ?: all
            renderColumn(binding.colTodoCards, binding.colTodoHead, "TO DO", tasks.filter { colOf(it) == "todo" }, "todo")
            renderColumn(binding.colDoingCards, binding.colDoingHead, "DOING", tasks.filter { colOf(it) == "doing" }, "doing")
            renderColumn(binding.colWaitingCards, binding.colWaitingHead, "WAITING", tasks.filter { colOf(it) == "waiting" }, "waiting")
            renderColumn(binding.colDoneCards, binding.colDoneHead, "DONE", tasks.filter { colOf(it) == "done" }.take(40), "done")
            // Local is on screen; now fold in dated Site cards and site bookings
            // (background, read-only, opt-in).
            if (includeSite && selectedBoard == null) {
                mergeSiteColumnCards()
                mergeSiteColumnBookings()
            }
        }
    }

    /**
     * Every task in the ledger — the walk the whole board stands on. Through [TaskWalkCache], so
     * only the files that changed since the last walk are re-decoded; the decode itself stays the
     * slim ledgerItems-only read (mirrors iOS DayLite). Each item is tagged with its source day,
     * which is what lets every columns verb ride the list's own [persist] / [deleteItems]
     * plumbing instead of carrying a second copy of it.
     */
    private fun gatherAllTasks(root: File): List<LedgerItem> {
        val calendarRoot = File(root, "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val out = mutableListOf<LedgerItem>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val d = fileDate(file.name) ?: return@forEach
                val items = com.toolsboox.plugin.calendar.ot.TaskWalkCache.items(file) { f ->
                    runCatching { calendarDayService.loadLedgerItems(f) }.getOrNull() ?: emptyList()
                }
                items.filter { it.kind == LedgerItem.Kind.TASK }
                    .forEach { out.add(it); itemSourceDay[it.id] = d }
            }
        return out.sortedByDescending { it.date.time }
    }

    /** Which lane an item stands in. Unknown stages fold into To do — the same honest degradation
     *  an older reader, which only knows todo/doing/done, gives a "waiting" written by this one. */
    private fun colOf(i: LedgerItem): String = when {
        i.done -> "done"
        i.stage == "doing" -> "doing"
        i.stage == "waiting" -> "waiting"
        else -> "todo"
    }

    private fun nextStage(col: String) = stageOrder[(stageOrder.indexOf(col) + 1).coerceAtMost(stageOrder.size - 1)]
    private fun prevStage(col: String) = stageOrder[(stageOrder.indexOf(col) - 1).coerceAtLeast(0)]

    /** ‹ › — move a card one lane. Done stays the only stage that checks the box, so a card
     *  walking through Waiting is still open everywhere the ledger asks "what is open". */
    private fun setStage(item: LedgerItem, stage: String) {
        item.stage = stage
        item.done = (stage == "done")
        persist(item)
        load()
    }

    private fun updateBoardLabel() {
        val name = selectedBoard?.let { id -> boards.firstOrNull { it.id == id }?.name } ?: "All boards"
        binding.itemsBoard.text = "$name  ▾"
    }

    /**
     * The board switcher — boards, and only boards, plus the two doors out.
     *
     * Its ancestor bundled eight unrelated things (source switch, a pref toggle, web push,
     * credentials, delete) behind hand-computed base indices, so adding a row meant re-counting
     * everyone else's. Now: "All", the boards, "＋ New board…", and "⚙ Board settings…" — the
     * settings menu holds everything that is ABOUT the machinery rather than a place to stand.
     * Both are (label, action) pairs the way every showIconMenu caller builds them, so no row can
     * shift another's meaning.
     */
    private fun showBoardPicker() {
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        rows += "▦  All boards" to { selectedBoard = null; load() }
        for (b in boards) rows += "▤  ${b.name.ifBlank { "Untitled" }}" to { selectedBoard = b.id; load() }
        rows += "🗓  Prep from schedule…" to { showSchedulePrep() }
        rows += "＋  New board…" to { promptNewBoard() }
        rows += "⚙  Board settings…" to { showBoardSettings() }
        showIconMenu("Boards · Local", rows)
    }

    /**
     * Schedule-occasioned prep — the first pass of the direction that retired the guessing
     * surfaces: the occasion is the day, the trigger is the schedule, and the assembly is
     * gathers over his own material ([com.toolsboox.plugin.calendar.ot.SchedulePrep]). Nothing
     * lands without an accept: the machine PROPOSES cards with their pulled assets in view,
     * in the same preview-then-commit grammar the lasso flow blessed.
     */
    private fun showSchedulePrep() {
        showIconMenu("Prep from schedule", listOf(
            "☀  Today" to { runSchedulePrep(java.time.LocalDate.now()) },
            "→  Tomorrow" to { runSchedulePrep(java.time.LocalDate.now().plusDays(1)) },
        ))
    }

    private fun runSchedulePrep(day: java.time.LocalDate) {
        val appCtx = requireContext().applicationContext
        val root = documentsRoot()
        viewLifecycleOwner.lifecycleScope.launch {
            val proposals = withContext(Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.SchedulePrep.assemble(
                        appCtx, calendarDayService, corpusService, root, day)
                }.getOrNull().orEmpty()
            }
            if (!isAdded) return@launch
            if (proposals.isEmpty()) {
                showMessage(
                    "No appointment on ${day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} " +
                        "names someone in your rolodex — nothing to prep.", binding.root)
                return@launch
            }
            showPrepProposals(day, proposals)
        }
    }

    /** The proposals, assets in view, each with its own checkbox — accept mints ONLY the checked. */
    private fun showPrepProposals(
        day: java.time.LocalDate,
        proposals: List<com.toolsboox.plugin.calendar.ot.SchedulePrep.Proposal>
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(18), px(8), px(18), px(8))
        }
        val checks = mutableListOf<Pair<android.widget.CheckBox, com.toolsboox.plugin.calendar.ot.SchedulePrep.Proposal>>()
        for (p in proposals) {
            val cb = android.widget.CheckBox(ctx).apply {
                isChecked = true
                text = p.cardText
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, px(10), 0, px(2))
            }
            col.addView(cb)
            checks += cb to p
            for (a in p.assets) {
                col.addView(android.widget.TextView(ctx).apply {
                    text = a
                    textSize = 13f
                    setTextColor(0xFF555555.toInt())
                    setPadding(px(28), px(1), 0, px(1))
                })
            }
        }
        // Guarded: an unchecked-through stack of proposals is a decision half-made — a stray
        // touch outside must not throw it away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("🗓  Prep for " + day.format(java.time.format.DateTimeFormatter.ofPattern("EEE · MMM d")))
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Accept") { _, _ ->
                acceptPrep(day, checks.filter { it.first.isChecked }.map { it.second })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** Mint the accepted proposals as cards on the prep day — stage todo, the open board, the
     *  contact attached, source="prep" so their lineage stays legible. */
    private fun acceptPrep(
        day: java.time.LocalDate,
        accepted: List<com.toolsboox.plugin.calendar.ot.SchedulePrep.Proposal>
    ) {
        if (accepted.isEmpty()) return
        val appCtx = requireContext().applicationContext
        val root = documentsRoot()
        val boardNow = if (columnsMode) selectedBoard ?: "" else ""
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val loc = java.util.Locale.getDefault()
                    val cd = calendarDayService.load(root, day, null, loc)
                    val due = java.util.Date(
                        day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
                    val minted = accepted.map { p ->
                        LedgerItem(
                            id = "li-${java.util.UUID.randomUUID()}", kind = LedgerItem.Kind.TASK,
                            text = p.cardText, date = due, source = "prep",
                            stage = "todo", board = boardNow, contactId = p.contactId
                        )
                    }
                    cd.ledgerItems.addAll(minted)
                    calendarDayService.save(root, day, cd)
                    minted.forEach {
                        runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(appCtx, it) }
                    }
                }
            }
            if (!isAdded) return@launch
            showMessage("${accepted.size} prep card${if (accepted.size == 1) "" else "s"} on the board.", binding.root)
            load()
        }
    }

    /** The machinery behind the boards: the Local ⇄ Site switch, the include-site fold, the web
     *  bridge, and the one destructive verb — everything the switcher used to bury. */
    private fun showBoardSettings() {
        val ctx = requireContext()
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        rows += "🌐  Switch to Site boards" to {
            findNavController().navigate(R.id.action_to_site_boards)
        }
        rows += ((if (includeSite) "☑" else "☐") + "  Include dated Site cards") to {
            includeSite = !includeSite
            kanbanPrefs().edit().putBoolean("include_site", includeSite).apply()
            load()
        }
        rows += "⬆  Send board to web" to { sendBoardToWeb() }
        rows += "🌐  Web bridge…" to { promptBridgeSettings() }
        if (selectedBoard != null) rows += "🗑  Delete this board" to {
            selectedBoard?.let { com.toolsboox.plugin.calendar.ot.BoardsStore.delete(ctx, it) }
            selectedBoard = null
            load()
        }
        showIconMenu("Board settings", rows)
    }

    private fun promptNewBoard() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Board name"; setSingleLine() }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input) }
        // Guarded: a name being typed is work — a stray touch outside must not throw it away.
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("New board")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    selectedBoard = com.toolsboox.plugin.calendar.ot.BoardsStore.add(ctx, name).id
                    load()
                }
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    /** Boards-site + Community-site credentials for the web bridge (mirrors iOS BridgeSettingsView). */
    private fun promptBridgeSettings() {
        val ctx = requireContext()
        val c = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx)
        val cc = com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx)
        fun field(hint: String, value: String, password: Boolean = false) = android.widget.EditText(ctx).apply {
            this.hint = hint; setText(value); setSingleLine()
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun header(text: String) = TextView(ctx).apply {
            this.text = text; textSize = 13f; setTextColor(0xFF666666.toInt())
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val site = field("https://theyoga.club", c.site)
        val user = field("WP username", c.user)
        val pass = field("Application password", c.pass, password = true)
        val board = field("FluentBoards board id", if (c.boardId > 0) c.boardId.toString() else "")
        val cSite = field("https://ashtanga.tech", cc.site)
        val cUser = field("WP username", cc.user)
        val cPass = field("Application password", cc.pass, password = true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(header("BOARDS SITE (FluentBoards)"))
            addView(site); addView(user); addView(pass); addView(board)
            addView(header("COMMUNITY SITE (FluentCommunity)"))
            addView(cSite); addView(cUser); addView(cPass)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }
        // Guarded: typed credentials are work — a stray touch outside must not throw them away.
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Web bridge")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                com.toolsboox.plugin.calendar.nw.LedgerWebBridge.saveConfig(
                    ctx,
                    com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(
                        site.text.toString(), user.text.toString(), pass.text.toString(),
                        board.text.toString().trim().toIntOrNull() ?: 0
                    )
                )
                com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.saveConfig(
                    ctx,
                    com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.Config(
                        cSite.text.toString(), cUser.text.toString(), cPass.text.toString()
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    /** Push one card to the web board; toast the result. */
    private fun sendToWeb(item: LedgerItem) {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerWebBridge.pushCard(requireContext(), item)
            }
            android.widget.Toast.makeText(requireContext(), status, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Push every open card on the visible board (selected board, or all). */
    private fun sendBoardToWeb() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { gatherAllTasks(documentsRoot()) }
            val toSend = (selectedBoard?.let { b -> all.filter { it.board == b } } ?: all).filter { !it.done }
            if (toSend.isEmpty()) {
                android.widget.Toast.makeText(ctx, "Nothing to send", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val status = withContext(Dispatchers.IO) {
                var sent = 0; var dup = 0; var failed = 0; var firstError = ""
                for (item in toSend) {
                    when (val s = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.pushCard(ctx, item)) {
                        "→ web board" -> sent++
                        "Already on the web board" -> dup++
                        else -> { failed++; if (firstError.isEmpty()) firstError = s }
                    }
                }
                if (failed > 0) firstError else "$sent sent" + if (dup > 0) ", $dup already there" else ""
            }
            android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** A pinned gram's face when display == INK — through the shared crop resolution
     *  ([com.toolsboox.ot.LedgerMedia.resolveCropBitmap]): `cropRef` first, then base64 in
     *  `crop`, then `crop` as an OCR filename. Null when the item has no such face. */
    private fun cropBitmap(item: LedgerItem): Bitmap? {
        if (item.display != LedgerItem.Display.INK) return null
        val ctx = context ?: return null
        return com.toolsboox.ot.LedgerMedia.resolveCropBitmap(ctx, item.crop, item.cropRef)
    }

    private fun renderColumn(container: LinearLayout, head: TextView, title: String, items: List<LedgerItem>, col: String) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        head.text = "$title · ${items.size}"
        container.removeAllViews()
        for (item in items) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(8), px(8), px(8), px(8))
                setBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, px(8)) }
            }
            // A gram pinned from Pickings rides its PNG as base64 in `crop` (display == INK). Show it.
            cropBitmap(item)?.let { bmp ->
                card.addView(android.widget.ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    maxHeight = px(120)
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, 0, 0, px(6)) }
                })
            }
            card.addView(TextView(ctx).apply {
                text = item.text; textSize = 13f; setTextColor(0xFF000000.toInt())
                if (item.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            // Contact + time chip (iOS parity): who it's for, when it's due.
            val chipContact = item.contactId?.let { ContactStore.get(ctx, it) }
            val chip = listOfNotNull(
                chipContact?.name?.ifBlank { "Unnamed" }?.let { "👤 $it" },
                item.time?.takeIf { it.isNotBlank() }?.let { "· $it" }
            ).joinToString("  ")
            if (chip.isNotBlank()) card.addView(TextView(ctx).apply {
                text = chip; textSize = 11f; setTextColor(0xFF666666.toInt()); setPadding(0, px(3), 0, 0)
            })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, 0)
            }
            fun glyph(g: String, size: Float, onTap: () -> Unit) = TextView(ctx).apply {
                text = g; textSize = size; setTextColor(0xFF2F6F96.toInt()); setPadding(px(8), 0, px(8), 0)
                setOnClickListener { onTap() }
            }
            fun spacer() = android.widget.Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
            if (col != "todo") row.addView(glyph("‹", 20f) { setStage(item, prevStage(col)) })
            row.addView(spacer())
            row.addView(glyph("↗", 15f) { openItemDay(item) })
            row.addView(spacer())
            if (col != "done") row.addView(glyph("›", 20f) { setStage(item, nextStage(col)) })
            card.addView(row)
            // Long-press a card for everything the list could do to it — the SAME verbs, through
            // the same functions, which is the point of the merge: a board you cannot correct or
            // delete from is a board that only fills up.
            card.setOnLongClickListener {
                showIconMenu(item.text.ifBlank { "Card" }.take(80), listOf(
                    "✎  Edit the words…" to { editWords(item) },
                    "⁂  Its rhizome" to { showRhizome(item) },
                    "🔎  Ask about this" to { askAboutItem(item) },
                    "🎓  Educate me" to { educateFromItem(item) },
                    "⬆  Send to web board" to { sendToWeb(item) },
                    "🗑  Delete" to { confirmDeleteCard(item) }
                ))
                true
            }
            container.addView(card)
        }
    }

    /** Deleting a card is not undoable, so it asks — and says which one it means. The delete
     *  itself is the list's own ([deleteItems]): tombstone + on-page face + remote, one path. */
    private fun confirmDeleteCard(item: LedgerItem) {
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Delete this card?")
            .setMessage(item.text.ifBlank { "(handwritten)" })
            .setPositiveButton("Delete") { _, _ -> deleteItems(listOf(item)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** ↗ — the card's own day page. */
    private fun openItemDay(item: LedgerItem) {
        val ld = itemSourceDay[item.id]
            ?: item.date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        com.toolsboox.plugin.calendar.CalendarNavigator.toDayPage(this, ld, CalendarDay.DEFAULT_STYLE)
    }

    /** Fetch DUE-DATED Site board cards and append them (read-only) into the same columns, so the
     *  Local board reads as one board over both sources. Local always renders first; this never
     *  blocks it and fails silently (empty) when the bridge is off or unreachable. */
    private fun mergeSiteColumnCards() {
        val ctx = requireContext()
        lifecycleScope.launch {
            // The boards surface's own site (its affinity: remembered → ashtanga.tech → active),
            // NOT the bare active site — so the cards merged here are the same cards the Site
            // Boards deep-link will find when one is tapped.
            val boardsCfg = com.toolsboox.plugin.calendar.nw.SiteAffinity.boardsConfigFor(
                ctx, com.toolsboox.plugin.calendar.nw.SiteRouting.SITE_BOARDS
            )
            val cards = withContext(Dispatchers.IO) {
                val eff = boardsCfg ?: com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx)
                if (!eff.ready) emptyList()
                else com.toolsboox.plugin.calendar.nw.LedgerBoards.dueCards(ctx, cfg = boardsCfg)   // server-filtered + bucketed
            }
            if (!isAdded || cards.isEmpty()) return@launch
            fun place(bucket: String, container: LinearLayout, head: TextView, title: String) {
                val mine = cards.filter { it.bucket == bucket }
                if (mine.isEmpty()) return
                for (c in mine) container.addView(siteCardView(c))
                head.text = "$title · ${(head.text.toString().substringAfterLast("· ").trim().toIntOrNull() ?: 0) + mine.size}"
            }
            place("todo", binding.colTodoCards, binding.colTodoHead, "TO DO")
            place("doing", binding.colDoingCards, binding.colDoingHead, "DOING")
            place("done", binding.colDoneCards, binding.colDoneHead, "DONE")
        }
    }

    /** Fetch upcoming site bookings and drop them into the same columns. A booking already
     *  arrives bucketed (ahead of you → todo, under way → doing, settled → done), so it needs no
     *  translation to sit beside a card: both are dated objects with a state. Read-only here —
     *  the writes live in the booking sheet a tap away. */
    private fun mergeSiteColumnBookings() {
        val ctx = requireContext()
        lifecycleScope.launch {
            // Bookings ALWAYS come from the bookings surface's site — theyoga.club by default,
            // where FluentBooking actually runs — regardless of the app's active site. This is
            // Michael's "pull up Yoga Club events without switching a dominant global site" made
            // literal: the studio's day is visible while the rest of the app points anywhere.
            val bookingCfg = com.toolsboox.plugin.calendar.nw.SiteAffinity.boardsConfigFor(
                ctx, com.toolsboox.plugin.calendar.nw.SiteRouting.BOOKINGS
            )
            val bookings = withContext(Dispatchers.IO) {
                val eff = bookingCfg ?: com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx)
                if (!eff.ready) emptyList()
                else com.toolsboox.plugin.calendar.nw.LedgerBooking.bookings(ctx, limit = 60, cfg = bookingCfg)
            }
            if (!isAdded || bookings.isEmpty()) return@launch
            fun place(bucket: String, container: LinearLayout, head: TextView, title: String) {
                val mine = bookings.filter { it.bucket == bucket }
                if (mine.isEmpty()) return
                for (b in mine) container.addView(siteBookingView(b))
                head.text = "$title · ${(head.text.toString().substringAfterLast("· ").trim().toIntOrNull() ?: 0) + mine.size}"
            }
            place("todo", binding.colTodoCards, binding.colTodoHead, "TO DO")
            place("doing", binding.colDoingCards, binding.colDoingHead, "DOING")
            place("done", binding.colDoneCards, binding.colDoneHead, "DONE")
        }
    }

    /** A read-only booking in a Local column: 🕘 tag + when/who; tap opens the booking sheet. */
    private fun siteBookingView(b: com.toolsboox.plugin.calendar.nw.SiteBooking): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val at = bookingLocalTime(b.startTime)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(8), px(8), px(8), px(8))
            setBackgroundColor(0xFFF3F0EA.toInt())   // faint warm tint = "a booking, read-only"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, px(8)) }
            addView(TextView(ctx).apply {
                text = "🕘  ${b.title}"; textSize = 13f; setTextColor(0xFF000000.toInt())
            })
            addView(TextView(ctx).apply {
                text = listOfNotNull(
                    at?.let { (d, hm) ->
                        java.time.format.DateTimeFormatter.ofPattern("EEE d MMM").format(d) + "  " + hm
                    },
                    b.person.ifBlank { null },
                    when (b.ongoing) {
                        "happening_now" -> "● now"
                        "starting_soon" -> "● soon"
                        else -> null
                    },
                    if (b.status == "cancelled" || b.status == "rejected") "cancelled" else null,
                ).joinToString("   ·   ")
                textSize = 11f; setTextColor(0xFF8A6D3B.toInt()); setPadding(0, px(3), 0, 0)
            })
            setOnClickListener { BookingSheet.open(this@LedgerItemsFragment, b.id) }
        }
    }

    /** A read-only Site card in a Local column: 🌐 tag + due chip; tap opens Site Boards. */
    private fun siteCardView(c: com.toolsboox.plugin.calendar.nw.DueCard): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(8), px(8), px(8), px(8))
            setBackgroundColor(0xFFEFF4F7.toInt())   // faint blue tint = "from the site, read-only"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, px(8)) }
            addView(TextView(ctx).apply {
                text = "🌐  ${c.title}"; textSize = 13f; setTextColor(0xFF000000.toInt())
            })
            addView(TextView(ctx).apply {
                text = listOfNotNull(
                    c.dueAt?.take(10)?.let { "📅 $it" },
                    c.board.ifBlank { null },
                    c.commentCount.takeIf { it > 0 }?.let { "💬 $it" }
                ).joinToString("   ·   ")
                textSize = 11f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(3), 0, 0)
            })
            setOnClickListener {
                findNavController().navigate(
                    R.id.action_to_site_boards,
                    androidx.core.os.bundleOf("site_board_id" to c.boardId)
                )
            }
        }
    }

    /** Fold DUE-DATED Site cards AND site bookings whose date lands in the viewed period into the
     *  list, read-only (same opt-in as the kanban: pref ledger_kanban/include_site). Local shows
     *  first; these stream in and fail silently when the bridge is off.
     *
     *  Both are dated objects, so both become synthetic rows on the one rail — a card carries its
     *  board for the deep-link, a booking carries [BOOKING_PREFIX] in its id for the sheet. Adding
     *  a third origin later (a Google Calendar event, say) means another block here, not another
     *  surface. */
    private fun mergeSiteDueCards(localItems: List<LedgerItem>) {
        val ctx = requireContext()
        if (!ctx.getSharedPreferences("ledger_kanban", android.content.Context.MODE_PRIVATE)
                .getBoolean("include_site", false)) return
        val (start, end) = periodRange(navPeriod, anchor)
        lifecycleScope.launch {
            // Cards ride the boards surface's site, bookings the bookings surface's (theyoga.club
            // by default) — two different sites, two configs, one timeline. Ready-ness is judged
            // per source: a device that only knows theyoga.club still gets its bookings here.
            val boardsCfg = com.toolsboox.plugin.calendar.nw.SiteAffinity.boardsConfigFor(
                ctx, com.toolsboox.plugin.calendar.nw.SiteRouting.SITE_BOARDS
            )
            val bookingCfg = com.toolsboox.plugin.calendar.nw.SiteAffinity.boardsConfigFor(
                ctx, com.toolsboox.plugin.calendar.nw.SiteRouting.BOOKINGS
            )
            val legacy = withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx) }
            val boardsReady = (boardsCfg ?: legacy).ready
            val bookingReady = (bookingCfg ?: legacy).ready
            if (!boardsReady && !bookingReady) return@launch

            val cards = (if (!boardsReady) emptyList() else withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerBoards.dueCards(ctx, cfg = boardsCfg)
            }).filter { c ->
                val d = c.dueAt?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                d != null && !d.isBefore(start) && !d.isAfter(end)
            }

            // The server windows by UTC, the timeline thinks in local days — so ask a day wide on
            // either side and settle the boundary here. Without the slack, an early-morning or
            // late-evening booking lands on the wrong day for anyone far enough from UTC.
            val bookings = (if (!bookingReady) emptyList() else withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerBooking.bookings(
                    ctx, from = start.minusDays(1).toString(), to = end.plusDays(1).toString(),
                    cfg = bookingCfg
                )
            }).mapNotNull { b -> bookingLocalTime(b.startTime)?.let { b to it } }
                .filter { (_, at) -> !at.first.isBefore(start) && !at.first.isAfter(end) }

            if (!isAdded || (cards.isEmpty() && bookings.isEmpty())) return@launch

            val syntheticCards = cards.map { c ->
                LedgerItem(
                    id = "sitecard-${c.id}", kind = LedgerItem.Kind.TASK,
                    text = "${c.title}   ·   ${c.board}" + (c.dueAt?.take(10)?.let { "   ·   📅 $it" } ?: ""),
                    date = java.util.Date(), stage = c.bucket, top = Float.MAX_VALUE,
                    board = c.boardId.toString()   // carried so the tap can deep-link to that board
                )
            }

            val syntheticBookings = bookings.map { (b, at) ->
                LedgerItem(
                    id = "$BOOKING_PREFIX${b.id}", kind = LedgerItem.Kind.EVENT,
                    text = "${b.title}   ·   ${b.person}   ·   🕘 ${at.second}" +
                        (if (b.status == "cancelled" || b.status == "rejected") "   ·   cancelled" else ""),
                    date = java.util.Date(), stage = b.bucket, top = Float.MAX_VALUE,
                    time = at.second
                )
            }

            val synthetic = syntheticCards + syntheticBookings
            adapter.readOnlyIds = synthetic.map { it.id }.toSet()
            adapter.submit(localItems + synthetic)
            binding.emptyText.visibility = View.GONE
        }
    }

    /** UTC wire time → (local date, "HH:mm"). Null when the server sent nothing usable. */
    private fun bookingLocalTime(utc: String?): Pair<LocalDate, String>? = utc?.let {
        runCatching {
            val local = java.time.LocalDateTime
                .parse(it.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atOffset(java.time.ZoneOffset.UTC)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            local.toLocalDate() to java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(local)
        }.getOrNull()
    }

    /** Inclusive day range for a filter period around [date]. */
    private fun periodRange(period: String, date: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        "week" -> {
            val s = date.with(java.time.temporal.WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
            s to s.plusDays(6)
        }
        "month" -> date.withDayOfMonth(1) to date.withDayOfMonth(date.lengthOfMonth())
        "quarter" -> {
            val s = date.withMonth((date.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
            val e = s.plusMonths(2)
            s to e.withDayOfMonth(e.lengthOfMonth())
        }
        "year" -> LocalDate.of(date.year, 1, 1) to LocalDate.of(date.year, 12, 31)
        else -> date to date
    }

    /** Walk the day files in [start, end] and collect their ledger items (+ strokes), tagging sources.
     *  Two passes: a SLIM decode collects the items (skipping the heavy stroke arrays entirely),
     *  then only the days whose items actually reference ink get the full decode. This is what
     *  keeps week/month/quarter scopes fast — most items are text-faced and never need strokes.
     *  The slim pass rides [com.toolsboox.plugin.calendar.ot.TaskWalkCache] besides, so a file
     *  unchanged since the last walk (this lens's or the board's) isn't even re-read. */
    private fun gatherRange(root: File, start: LocalDate, end: LocalDate, strokes: HashMap<String, Stroke>): List<LedgerItem> {
        val cal = File(root, "calendar")
        if (!cal.exists()) return emptyList()
        val out = mutableListOf<LedgerItem>()
        val needInk = mutableListOf<File>()
        cal.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val d = fileDate(file.name) ?: return@forEach
                if (d.isBefore(start) || d.isAfter(end)) return@forEach
                val items = com.toolsboox.plugin.calendar.ot.TaskWalkCache.items(file) { f ->
                    runCatching { calendarDayService.loadLedgerItems(f) }.getOrNull() ?: emptyList()
                }
                if (items.isEmpty()) return@forEach
                items.forEach { out.add(it); itemSourceDay[it.id] = d }
                if (items.any { it.strokeIds.isNotEmpty() }) needInk.add(file)
            }
        needInk.forEach { file ->
            val cd = runCatching { calendarDayService.load(file) }.getOrNull() ?: return@forEach
            cd.calendarStrokes.values.forEach { l -> l.forEach { strokes[it.strokeId.toString()] = it } }
            cd.noteStrokes.values.forEach { l -> l.forEach { strokes[it.strokeId.toString()] = it } }
        }
        return out
    }

    private fun fileDate(name: String): LocalDate? = runCatching {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    /** Finger / no-pen capture: type a task or event, set date (and time for events), add + push. */
    private fun showItemEntry(kind: LedgerItem.Kind) {
        val isEvent = kind == LedgerItem.Kind.EVENT
        val input = android.widget.EditText(requireContext()).apply {
            hint = if (isEvent) "New event" else "New task"
            setSingleLine(false); maxLines = 3
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        var chosenTime: String? = null
        val timeButton = android.widget.Button(requireContext()).apply {
            text = "＋ Time"; isAllCaps = false; textSize = 15f
            setBackgroundResource(R.drawable.dialog_rounded_bg)
        }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            // Events carry a time; tasks don't.
            if (isEvent) addView(timeButton, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad })
        }
        timeButton.setOnClickListener {
            val now = java.time.LocalTime.now()
            android.app.TimePickerDialog(requireContext(), { _, h, m ->
                chosenTime = "%02d:%02d".format(h, m); timeButton.text = "🕓  $chosenTime"
            }, now.hour, now.minute, true).show()
        }
        // The list adds to the day it is looking at; the board has no day on screen, so it adds to
        // today — the same promise its old ＋New made.
        var due = if (columnsMode) LocalDate.now() else anchor
        val baseTitle = if (isEvent) "New event" else "New task"
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(baseTitle)
            .setView(container)
            .setPositiveButton("Add") { _, _ -> createManual(kind, input.text.toString(), due, chosenTime) }
            .setNeutralButton("Date", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            input.requestFocus()
            // Keep the dialog open when picking a date (default click would dismiss it).
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                android.app.DatePickerDialog(
                    requireContext(),
                    { _, y, m, d ->
                        due = LocalDate.of(y, m + 1, d)
                        dialog.setTitle("$baseTitle · ${due.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}")
                    },
                    due.year, due.monthValue - 1, due.dayOfMonth
                ).show()
            }
        }
        // A task being typed is work — a stray touch outside must not throw it away. Cancel and
        // the back gesture remain the ways out. (Flag set directly: this dialog wires its own
        // setOnShowListener for focus + the Date button, which showGuardedModal would replace.)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun createManual(kind: LedgerItem.Kind, text: String, due: LocalDate, time: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        // In list mode, nothing read yet means there is no page to add to. The columns lens reads
        // the whole ledger and never sets `day`, so it skips the guard and files by [due].
        if (!columnsMode && day == null) return
        val dueDate = Date(due.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        val item = LedgerItem(
            id = "li-${java.util.UUID.randomUUID()}", kind = kind, text = t, date = dueDate,
            time = if (kind == LedgerItem.Kind.EVENT) time else null, source = "manual",
            // A card born while a board is open belongs to that board — the columns lens's old
            // ＋New made this promise and the one add door keeps it. The list has no board on
            // screen, so a list add stays unfiled, as it always was.
            stage = if (kind == LedgerItem.Kind.TASK) "todo" else "",
            board = if (columnsMode) selectedBoard ?: "" else ""
        )
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Read the ANCHOR's own file and write the item into that, rather than saving
                    // back whatever `day` is holding. With a period on the strip `day` is a
                    // SYNTHETIC CalendarDay carrying every item the window gathered, so saving it
                    // to the anchor would copy a whole month of other days' tasks into one file —
                    // the same family of defect as looking a row's file up from the anchor
                    // (see `persist` / `deleteItems`): once a window is on screen, "the day" the
                    // surface is showing and "the day file" a write belongs in are two things.
                    // The columns lens has no anchor on screen at all, so it files by [due] —
                    // today unless the Date button said otherwise.
                    val root = documentsRoot()
                    val fileDay = if (columnsMode) due else anchor
                    val cd = calendarDayService.load(root, fileDay, null, Locale.getDefault())
                    cd.ledgerItems.add(item)
                    // Tasks also land on the day page (a text box in a free Tasks row); the
                    // scrollable list holds every task regardless, so overflow past the 16 rows
                    // still shows there.
                    if (kind == LedgerItem.Kind.TASK)
                        com.toolsboox.plugin.calendar.ot.LedgerTaskCarryOver.placeTypedTask(cd, t, appCtx)
                    calendarDayService.save(root, fileDay, cd)
                }.onFailure { Timber.w(it, "ledger items: manual save failed") }
                com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(appCtx, item)
                com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(appCtx, item)
            }
            load()
        }
    }

    /** The item is a reference into [day].ledgerItems, so just re-save the day. */
    /** Populate the birthdays strip with the viewed day's rolodex birthdays (tappable → the contact). */
    private fun renderBirthdays() {
        val container = binding.birthdaysContainer
        container.removeAllViews()
        val bdays = contactsById.values
            .filter { birthdayMatches(it.birthday, anchor.monthValue, anchor.dayOfMonth) }
            .sortedBy { it.name.lowercase() }
        container.visibility = if (bdays.isEmpty()) View.GONE else View.VISIBLE
        for (c in bdays) {
            container.addView(TextView(requireContext()).apply {
                text = "🎂  ${c.name.ifBlank { "Unnamed" }}"
                textSize = 16f
                setTextColor(0xFF000000.toInt())
                setPadding(0, 12, 0, 12)
                setOnClickListener { showContact(c) }
            })
        }
    }

    /** Match a freeform birthday string ("Mar 4" / "3-4" / "03/04") to a month/day. */
    private fun birthdayMatches(bday: String, month: Int, day: Int): Boolean {
        val s = bday.lowercase().trim()
        if (s.isEmpty()) return false
        val months = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )
        val parts = s.split(' ', '-', '/', ',', '.').filter { it.isNotBlank() }
        if (parts.size < 2) return false
        val m = months[parts[0].take(3)] ?: parts[0].toIntOrNull()
        val d = parts[1].toIntOrNull()
        return m == month && d == day
    }

    /** Show a contact's details with a jump to the Rolodex — the birthday's "link back". */
    private fun showContact(c: Contact) {
        val info = listOfNotNull(
            c.phone.takeIf { it.isNotBlank() }?.let { "📞  $it" },
            c.email.takeIf { it.isNotBlank() }?.let { "✉  $it" },
            c.org.takeIf { it.isNotBlank() },
            c.birthday.takeIf { it.isNotBlank() }?.let { "🎂  $it" },
            c.bio.takeIf { it.isNotBlank() }
        ).joinToString("\n")
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(c.name.ifBlank { "Contact" })
            .setMessage(info.ifBlank { "No details yet." })
            .setPositiveButton("Open Rolodex") { _, _ -> findNavController().navigate(R.id.action_to_rolodex) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Open a contact picker and link (or clear) the item's rolodex contact, then persist. */
    // --- The card behind a row -----------------------------------------------------------------
    // (The Spiral learner card that lived here retired 2026-08-10 — it guessed, and Michael's
    // screenshot showed it swallowing the surface: full article text, twice over.)

    /**
     * The card behind a row.
     *
     * A task in a list is a line of text, which is a dead end — you can tick it and nothing else.
     * This is the thing the line stands for: both its faces, who it's for, where it sits, and the
     * way to give it a hand-written face it never had.
     */
    private fun openCard(item: LedgerItem) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(10), px(18), px(4))
        }

        col.addView(TextView(ctx).apply {
            text = item.text.ifBlank { "(no words — this one is in ink)" }
            textSize = 17f
            setTextColor(0xFF000000.toInt())
            setTextIsSelectable(true)
        })

        // The words have to stay changeable. Until now the only place a task's text could be
        // edited was the confirm dialog at creation — once that closed, an OCR mistake was
        // permanent, and tapping the task did nothing at all. This is the way back in.
        val editRow = TextView(ctx).apply {
            text = "✎  Edit the words…"
            textSize = 15f
            setTextColor(0xFF3A3A3A.toInt())
            setPadding(0, px(12), 0, px(2))
            isClickable = true
        }
        col.addView(editRow)

        // Its hand: the drawn or pinned face, mounted like anything else shown rather than drawn.
        inkFaceOf(item)?.let { bmp ->
            col.addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx,
                android.widget.ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    maxHeight = px(260)
                    com.toolsboox.ot.ImageZoom.makeTappable(this, item.text)
                }, taped = false).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = px(10)
            })
        }

        val facts = buildString {
            append(if (item.kind == LedgerItem.Kind.TASK) "Task" else "Event")
            item.time?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
            append("  ·  ").append(item.stage.ifBlank { "todo" })
            if (item.kind == LedgerItem.Kind.TASK && item.done) append("  ·  done")
            item.source?.takeIf { it.isNotBlank() }?.let { append("  ·  from ").append(it) }
        }
        col.addView(TextView(ctx).apply {
            text = facts; textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, px(10), 0, 0)
        })

        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        com.toolsboox.ot.ReadingSize.apply(scroll)

        val penLabel = if (inkFaceOf(item) != null) "✍  Redraw in pen…" else "✍  Write it in pen…"

        val card = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (item.kind == LedgerItem.Kind.TASK) "Task card" else "Event card")
            .setView(scroll)
            .setPositiveButton(penLabel) { _, _ -> writeInPen(item) }
            .setNeutralButton("Assign…") { _, _ -> assign(item) }
            .setNegativeButton("Close", null)
            .create()
        editRow.setOnClickListener { card.dismiss(); editWords(item) }
        card.show()
    }

    /**
     * Change a task or event's words after the fact.
     *
     * Taken literally: what you type is what it says. The trailing "friday 9am" parsing belongs
     * to creation, where you're writing the thing for the first time; applying it again here
     * would silently eat words out of a correction, which is the opposite of what a fix is for.
     */
    private fun editWords(item: LedgerItem) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val input = android.widget.EditText(ctx).apply {
            setText(item.text)
            setSelection(item.text.length)
            hint = "What it says"
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(8), px(18), 0)
            addView(input)
        }
        // A correction being typed is work — a stray touch outside must not throw it away.
        // Cancel and the back gesture remain the ways out.
        showGuardedModal(AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (item.kind == LedgerItem.Kind.TASK) "Edit task" else "Edit event")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val next = input.text.toString().trim()
                if (next.isNotBlank() && next != item.text) {
                    item.text = next
                    // An item whose ink face no longer matches its words is a lie. The words are
                    // what was just corrected, so the text face is the truthful one to show.
                    // A face can arrive by ref now (`cropRef`) — an item carrying one HAS ink.
                    if (item.display == LedgerItem.Display.INK &&
                        item.crop.isNullOrBlank() && item.cropRef.isNullOrBlank()) {
                        item.display = LedgerItem.Display.TEXT
                    }
                    persist(item)
                    load()
                }
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    /**
     * The item's ink face: its own carried PNG.
     *
     * Re-rendering from strokeIds is the list's job (it holds the stroke map); the card only
     * needs the face that travels WITH the item, which is what a drawn or pinned one has.
     */
    private fun inkFaceOf(item: LedgerItem): Bitmap? {
        val ctx = context ?: return null
        // `cropRef` first, then base64 `crop`, then filename — the shared crop resolution.
        return com.toolsboox.ot.LedgerMedia.resolveCropBitmap(ctx, item.crop, item.cropRef)
    }

    /**
     * Give a typed task a hand.
     *
     * A task caught by OCR or typed into a box has no ink of its own, so its ink face is blank
     * and the text/ink toggle does nothing. Writing one here fills that face — the same shared
     * pad the reply composer uses, so it has undo and colours without asking for them.
     */
    private fun writeInPen(item: LedgerItem) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val pad = com.toolsboox.ot.InkPadView(ctx)
        val frame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            setPadding(px(2), px(2), px(2), px(2))
            addView(pad, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, px(300)
            ))
        }
        // Cancel / Save ride ABOVE the pad — the handwriting-panel rule (see LedgerTitlePad):
        // platform dialog buttons render at the bottom, exactly where the writing hand rests.
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(6), px(12), 0)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, px(6))
                addView(TextView(ctx).apply {
                    text = "CANCEL"; textSize = 15f; setTextColor(0xFF555555.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(4), px(28), px(6))
                    setOnClickListener { dialog.dismiss() }
                })
                addView(android.view.View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(TextView(ctx).apply {
                    text = "SAVE"; textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(px(28), px(4), 0, px(6))
                    setOnClickListener {
                        val bmp = pad.render()
                        if (bmp == null) {
                            android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val baos = java.io.ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        bmp.recycle()
                        // `crop` carries the PNG inline, the same way a pinned gram does.
                        val written = item.copy(
                            crop = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        ).also { it.display = LedgerItem.Display.INK; it.done = item.done; it.stage = item.stage }
                        persist(written)
                        load()
                        dialog.dismiss()
                    }
                })
            })
            addView(TextView(ctx).apply {
                text = item.text.ifBlank { "Write this one out" }
                textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, px(6))
            })
            addView(com.toolsboox.ot.InkPadView.penBar(ctx, pad))
            addView(frame)
        }

        dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Write it in pen")
            .setView(box)
            .create()
        // The pad holds the task's face mid-write — a palm outside the dialog must not cost the
        // ink. CANCEL / SAVE above the pad and the back gesture remain the ways out.
        showGuardedModal(dialog)
    }

    /**
     * Everything this task joins.
     *
     * The pointers it already carries — who it's for, the page it came off, the board it sits on
     * — are said as edges on the way in. Idempotent, and it leaves the original fields untouched,
     * so this is the compatibility bridge rather than a migration.
     */
    private fun showRhizome(item: LedgerItem) {
        val uri = com.toolsboox.plugin.calendar.ot.LegacyEdges.adopt(
            requireContext(), item, itemSourceDay[item.id]
        )
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to uri,
                LedgerRhizomeFragment.ARG_LABEL to item.text.ifBlank { "(handwritten)" }
            )
        )
    }

    /** 🔎 The universal menu's Ask, from a task's own end: the task's words go to Ask my Ledger
     *  with its `task://` address riding as provenance, so every edge already touching the task
     *  (who it's for, the page it came off) joins the grounding. This list holds events too —
     *  they share the row menu and the `task://` scheme, so only the label differs. */
    private fun askAboutItem(item: LedgerItem) {
        val text = item.text.ifBlank { "(handwritten)" }
        com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
            this, selection = text, title = text,
            link = com.toolsboox.ot.LedgerUri.task(item.id),
            sourceLabel = if (item.kind == LedgerItem.Kind.EVENT) "an event" else "a task"
        )
    }

    /** 🎓 The universal menu's Educate me: the task becomes a question gram on the intake sheet's
     *  Educate Me panel, carrying the same `task://` provenance. */
    private fun educateFromItem(item: LedgerItem) {
        val text = item.text.ifBlank { "(handwritten)" }
        com.toolsboox.plugin.calendar.ot.AskBridge.gramToEducateMe(
            this, text = text, title = text,
            link = com.toolsboox.ot.LedgerUri.task(item.id),
            sourceLabel = if (item.kind == LedgerItem.Kind.EVENT) "an event" else "a task"
        )
    }

    private fun assign(item: LedgerItem) {
        val contacts = contactsById.values.sortedBy { it.name.lowercase() }
        val labels = (listOf("— None —") + contacts.map { it.name.ifBlank { "Unnamed" } }).toTypedArray()
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Assign to…")
            .setItems(labels) { _, which ->
                item.contactId = if (which == 0) null else contacts[which - 1].id
                persist(item)
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun persist(item: LedgerItem) {
        val srcDay = itemSourceDay[item.id] ?: anchor
        lifecycleScope.launch(Dispatchers.IO) {
            val root = documentsRoot()
            runCatching {
                // Load the item's own day (may differ from the anchor when filtering a period),
                // replace it, and save there.
                val cd = calendarDayService.load(root, srcDay, null, Locale.getDefault())
                val idx = cd.ledgerItems.indexOfFirst { it.id == item.id }
                if (idx >= 0) cd.ledgerItems[idx] = item else cd.ledgerItems.add(item)
                calendarDayService.save(root, srcDay, cd)
            }.onFailure { Timber.w(it, "ledger items: save failed") }
            // Re-push so an edit/done-toggle reflects in its destination (idempotent by id): tasks to
            // CalDAV, events to Google Calendar. Each no-ops for the other kind.
            com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item)
            com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(requireContext(), item)
        }
    }


    override fun showLoading() {}
    override fun hideLoading() {}
}
