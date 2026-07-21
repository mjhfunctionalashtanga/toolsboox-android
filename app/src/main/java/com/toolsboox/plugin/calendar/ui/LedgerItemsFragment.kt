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
 * Tasks & Events — the structured items extracted from a day's handwriting, listed per day.
 * Each row shows text or ink per the item's own toggle; tasks check off, events show their time.
 * Edits (done / display) persist back into the day JSON so they sync.
 */
@AndroidEntryPoint
class LedgerItemsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    /** Reads the whole ledger as citable snippets — what the learner card draws from. */
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerItemsBinding.bind(view)

        adapter = LedgerItemAdapter(emptyList(), emptyMap(), ::persist, ::onEnterSelection, ::updateSelectionBar,
            onOpenCard = ::openCard)
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
        binding.itemsPill.bringToFront()
        makeDraggable(binding.itemsGrip, binding.itemsPill, "items_pill")

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
                        val ids = group.map { it.id }.toSet()
                        cd.ledgerItems.removeAll { it.id in ids }
                        // Tombstone so the union merge can't resurrect a deleted item from a synced copy.
                        ids.forEach { if (it !in cd.deletedElementIds) cd.deletedElementIds.add(it) }

                        // Also remove the item's ON-PAGE face, or a deleted task keeps showing on the
                        // day page: ink tasks are strokes (by strokeIds), typed tasks are text boxes
                        // (matched by text on the default page). Tombstone both so they stay gone.
                        val strokeIds = group.flatMap { it.strokeIds }.toSet()
                        if (strokeIds.isNotEmpty()) {
                            cd.calendarStrokes[CalendarDay.DEFAULT_STYLE] =
                                (cd.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList())
                                    .filterNot { it.strokeId.toString() in strokeIds }
                            strokeIds.forEach { if (it !in cd.deletedStrokeIds) cd.deletedStrokeIds.add(it) }
                        }
                        val texts = group.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }.toSet()
                        if (texts.isNotEmpty()) {
                            val boxes = cd.textElements.filter { it.pageKey == "default" && it.text in texts }
                            cd.textElements.removeAll(boxes)
                            boxes.forEach {
                                val eid = it.elementId.toString()
                                if (eid !in cd.deletedElementIds) cd.deletedElementIds.add(eid)
                            }
                        }
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

    private fun load() {
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
            val items = com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe
                .dedupe(d?.ledgerItems ?: emptyList())
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
                onOpenCard = ::openCard
            )
            binding.itemsRecycler.adapter = adapter
            attachSwipeToDelete()
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            mergeSiteDueCards(items)
            showLearnerCard()
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
            val ready = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx).ready
            }
            if (!ready) return@launch

            val cards = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerBoards.dueCards(ctx)
            }.filter { c ->
                val d = c.dueAt?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                d != null && !d.isBefore(start) && !d.isAfter(end)
            }

            // The server windows by UTC, the timeline thinks in local days — so ask a day wide on
            // either side and settle the boundary here. Without the slack, an early-morning or
            // late-evening booking lands on the wrong day for anyone far enough from UTC.
            val bookings = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerBooking.bookings(
                    ctx, from = start.minusDays(1).toString(), to = end.plusDays(1).toString()
                )
            }.mapNotNull { b -> bookingLocalTime(b.startTime)?.let { b to it } }
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
     *  keeps week/month/quarter scopes fast — most items are text-faced and never need strokes. */
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
                val items = runCatching { calendarDayService.loadLedgerItems(file) }.getOrNull() ?: return@forEach
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
        var due = anchor
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
        dialog.show()
    }

    private fun createManual(kind: LedgerItem.Kind, text: String, due: LocalDate, time: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        val d = day ?: return
        val dueDate = Date(due.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        val item = LedgerItem(
            id = "li-${java.util.UUID.randomUUID()}", kind = kind, text = t, date = dueDate,
            time = if (kind == LedgerItem.Kind.EVENT) time else null, source = "manual"
        )
        d.ledgerItems.add(item)
        // Tasks also land on the day page (a text box in a free Tasks row); the scrollable list
        // holds every task regardless, so overflow past the 16 rows still shows there.
        if (kind == LedgerItem.Kind.TASK) com.toolsboox.plugin.calendar.ot.LedgerTaskCarryOver.placeTypedTask(d, t)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { calendarDayService.save(documentsRoot(), anchor, d) }
                    .onFailure { Timber.w(it, "ledger items: manual save failed") }
                com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item)
                com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(requireContext(), item)
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
    // --- Writing a task by hand, and the learner card -----------------------------------------


    /**
     * The learner card: one thing out of your own ledger, come back around.
     *
     * Spiral learning — a note, a highlight, a recording you made once returns later so it can
     * layer rather than be filed and forgotten. It sits at the foot of the tasks because that is
     * where you already look, and it asks for nothing: no score, no streak, no penalty for
     * ignoring it. "Again" pulls it closer, "Later" pushes it out, "Make a task" turns it into
     * something to do. Then it's gone until its turn comes round.
     */
    private fun showLearnerCard() {
        val ctx = context ?: return
        val card = binding.learnerCard
        card.removeAllViews()
        card.visibility = View.GONE

        lifecycleScope.launch {
            val chosen = withContext(Dispatchers.IO) {
                runCatching {
                    val all = corpusService.gather(documentsRoot(), com.toolsboox.plugin.calendar.ot.Spiral.SCOPE)
                        .filter { com.toolsboox.plugin.calendar.ot.Spiral.isSubstantial(it.text) }
                        .let { com.toolsboox.plugin.calendar.ot.Spiral.dedupe(it) { s -> s.text } }

                    // The roots feed the spiral: an object that joins two threads — one you're in
                    // now and one you aren't — beats another member of a thread you're already
                    // inside. That's the difference between being reminded and being connected.
                    val threads = com.toolsboox.plugin.calendar.ot.Rhizome.threads(
                        all.map { it.text + " " + it.title }, all.map { it.date.time })
                    val crossings = com.toolsboox.plugin.calendar.ot.Rhizome.crossings(threads)
                    val warmCut = System.currentTimeMillis() -
                        java.util.concurrent.TimeUnit.DAYS.toMillis(21)
                    val warmTerms = threads
                        .filter { t -> t.members.any { (all.getOrNull(it)?.date?.time ?: 0L) >= warmCut } }
                        .map { it.term }.toSet()
                    val bonusByIndex = crossings.mapValues { (_, terms) ->
                        com.toolsboox.plugin.calendar.ot.Rhizome.bridgeScore(terms, warmTerms)
                    }
                    val indexOf = all.withIndex().associate { (i, v) -> v to i }

                    com.toolsboox.plugin.calendar.ot.Spiral.choose(
                        ctx, all,
                        textOf = { it.text + " " + it.title },
                        dateOf = { it.date.time },
                        keyOf = { com.toolsboox.plugin.calendar.ot.Spiral.keyOf(it.citation, it.text) },
                        ownOf = { it.own },
                        bonusOf = { indexOf[it]?.let { i -> bonusByIndex[i] } ?: 0.0 }
                    )
                }.getOrNull()
            }

            if (!isAdded || chosen == null) return@launch
            val pick = chosen.item
            val key = com.toolsboox.plugin.calendar.ot.Spiral.keyOf(pick.citation, pick.text)
            val dp = resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()

            card.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt()); setStroke(px(2), 0xFF111111.toInt()); cornerRadius = px(12).toFloat()
            }
            // The header says WHY this one, which is the whole difference between a spiral and a
            // queue. "Come back around" alone is just a slow list. Tapping it opens the roots.
            card.addView(TextView(ctx).apply {
                text = (if (chosen.shared.isEmpty()) "🌀  come back around"
                    else "🌀  you've been circling " + chosen.shared.joinToString(" · ")) + "   ›"
                textSize = 12f; setTextColor(0xFF666666.toInt())
                setOnClickListener { findNavController().navigate(R.id.action_to_ledger_roots) }
            })
            card.addView(TextView(ctx).apply {
                text = pick.text.take(320).trim() + if (pick.text.length > 320) "…" else ""
                textSize = 15f; setTextColor(0xFF000000.toInt()); setPadding(0, px(6), 0, px(6))
                setLineSpacing(0f, 1.15f)
            })
            card.addView(TextView(ctx).apply {
                text = pick.citation + (if (pick.title.isNotBlank()) "  ·  " + pick.title else "")
                textSize = 11f; setTextColor(0xFF888888.toInt())
            })

            // The rhyme: the recent thing that pulled this one back up. Showing both halves is
            // what lets a subject LAYER instead of just recurring.
            chosen.echo?.let { echo ->
                card.addView(TextView(ctx).apply {
                    text = "↳ rhymes with: " + echo.text.take(120).trim() +
                        (if (echo.text.length > 120) "…" else "")
                    textSize = 12f; setTextColor(0xFF444444.toInt())
                    setPadding(px(10), px(8), 0, 0)
                })
            }

            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(8), 0, 0)
            }
            fun act(label: String, onTap: () -> Unit) = TextView(ctx).apply {
                text = label; textSize = 14f; setTextColor(0xFF2F6F96.toInt())
                setPadding(0, px(4), px(18), px(4))
                setOnClickListener { onTap() }
            }
            actions.addView(act("↺ Again") {
                com.toolsboox.plugin.calendar.ot.Spiral.mark(ctx, key, closer = true); showLearnerCard()
            })
            actions.addView(act("→ Later") {
                com.toolsboox.plugin.calendar.ot.Spiral.mark(ctx, key); showLearnerCard()
            })
            actions.addView(act("🗒 Make a task") {
                com.toolsboox.plugin.calendar.ot.Spiral.mark(ctx, key)
                makeTaskFromSnippet(pick)
            })
            actions.addView(act("✕") {
                com.toolsboox.plugin.calendar.ot.Spiral.retire(ctx, key); showLearnerCard()
            })
            card.addView(actions)

            com.toolsboox.ot.ReadingSize.apply(card)
            card.visibility = View.VISIBLE
        }
    }


    /** Turn what came back around into something to do, keeping the words and where they're from. */
    private fun makeTaskFromSnippet(pick: com.toolsboox.plugin.chat.da.CorpusSnippet) {
        val task = LedgerItem(
            id = "spiral-" + java.util.UUID.randomUUID().toString().lowercase(),
            kind = LedgerItem.Kind.TASK,
            text = pick.text.take(140).trim(),
            date = java.util.Date(),
            source = "spiral",
            stage = "todo"
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    val day = calendarDayService.load(root, anchor, null, Locale.getDefault())
                    day.ledgerItems.add(task)
                    calendarDayService.save(root, anchor, day)
                }
            }
            load()
        }
    }

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
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (item.kind == LedgerItem.Kind.TASK) "Edit task" else "Edit event")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val next = input.text.toString().trim()
                if (next.isNotBlank() && next != item.text) {
                    item.text = next
                    // An item whose ink face no longer matches its words is a lie. The words are
                    // what was just corrected, so the text face is the truthful one to show.
                    if (item.display == LedgerItem.Display.INK && item.crop.isNullOrBlank()) {
                        item.display = LedgerItem.Display.TEXT
                    }
                    persist(item)
                    load()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The item's ink face: its own carried PNG.
     *
     * Re-rendering from strokeIds is the list's job (it holds the stroke map); the card only
     * needs the face that travels WITH the item, which is what a drawn or pinned one has.
     */
    private fun inkFaceOf(item: LedgerItem): Bitmap? {
        return item.crop?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
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
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(6), px(12), 0)
            addView(TextView(ctx).apply {
                text = item.text.ifBlank { "Write this one out" }
                textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, px(6))
            })
            addView(com.toolsboox.ot.InkPadView.penBar(ctx, pad))
            addView(frame)
        }

        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Write it in pen")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val bmp = pad.render()
                if (bmp == null) {
                    android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
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
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
