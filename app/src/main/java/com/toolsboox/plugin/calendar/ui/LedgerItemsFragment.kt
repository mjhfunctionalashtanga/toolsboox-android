package com.toolsboox.plugin.calendar.ui

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.DateFormat
import android.view.View
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

        adapter = LedgerItemAdapter(emptyList(), emptyMap(), ::persist, ::onEnterSelection, ::updateSelectionBar)
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
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
            val items = (d?.ledgerItems ?: emptyList()).sortedWith(
                compareBy({ it.kind != LedgerItem.Kind.TASK }, { it.kind == LedgerItem.Kind.TASK && it.done }, { it.top })
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
                }
            )
            binding.itemsRecycler.adapter = adapter
            attachSwipeToDelete()
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            mergeSiteDueCards(items)
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle(c.name.ifBlank { "Contact" })
            .setMessage(info.ifBlank { "No details yet." })
            .setPositiveButton("Open Rolodex") { _, _ -> findNavController().navigate(R.id.action_to_rolodex) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Open a contact picker and link (or clear) the item's rolodex contact, then persist. */
    private fun assign(item: LedgerItem) {
        val contacts = contactsById.values.sortedBy { it.name.lowercase() }
        val labels = (listOf("— None —") + contacts.map { it.name.ifBlank { "Unnamed" } }).toTypedArray()
        AlertDialog.Builder(requireContext())
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

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    override fun showLoading() {}
    override fun hideLoading() {}
}
