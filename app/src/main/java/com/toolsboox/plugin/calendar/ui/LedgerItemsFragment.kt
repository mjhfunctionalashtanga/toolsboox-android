package com.toolsboox.plugin.calendar.ui

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.DateFormat
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.databinding.FragmentLedgerItemsBinding
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
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
    private var navBar: CalendarNavBarHost? = null

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
            onStepDay = { d -> anchor = d; load() })
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
                                      vh: androidx.recyclerview.widget.RecyclerView.ViewHolder): Int =
                if (adapter.selecting) 0 else super.getSwipeDirs(rv, vh)
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
        binding.selectionCount.text = "${adapter.selectedIds.size} selected"
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
        val d = day ?: return
        val ids = toDelete.map { it.id }.toSet()
        d.ledgerItems.removeAll { it.id in ids }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { calendarDayService.save(documentsRoot(), anchor, d) }
                    .onFailure { Timber.w(it, "ledger items: delete save failed") }
                toDelete.forEach { runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.deleteTask(requireContext(), it) } }
                toDelete.forEach { runCatching { com.toolsboox.plugin.calendar.nw.LedgerEventSync.deleteEvent(requireContext(), it) } }
            }
            load()
        }
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            val root = documentsRoot()
            val (d, strokes, pattern) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, anchor, null, Locale.getDefault()) }
                    .onFailure { Timber.w(it, "ledger items: load failed") }.getOrNull()
                val map = HashMap<String, Stroke>()
                cd?.calendarStrokes?.values?.forEach { list -> list.forEach { map[it.strokeId.toString()] = it } }
                cd?.noteStrokes?.values?.forEach { list -> list.forEach { map[it.strokeId.toString()] = it } }
                val pat = runCatching { calendarPatternService.load(root, anchor, Locale.getDefault()) }
                    .onFailure { Timber.w(it, "ledger items: pattern load failed") }.getOrNull()
                Triple(cd, map, pat)
            }
            day = d
            // Redraw the reused Almanac navigator for this date.
            val navDay = d ?: CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
            pattern?.let { navBar?.render(navDay, it) }
            // Tasks first, then events; done tasks sink to the bottom of the task group.
            val items = (d?.ledgerItems ?: emptyList()).sortedWith(
                compareBy({ it.kind != LedgerItem.Kind.TASK }, { it.kind == LedgerItem.Kind.TASK && it.done }, { it.top })
            )
            adapter = LedgerItemAdapter(items, strokes, ::persist, ::onEnterSelection, ::updateSelectionBar)
            binding.itemsRecycler.adapter = adapter
            attachSwipeToDelete()
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

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
    private fun persist(item: LedgerItem) {
        val d = day ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { calendarDayService.save(documentsRoot(), anchor, d) }
                .onFailure { Timber.w(it, "ledger items: save failed") }
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
