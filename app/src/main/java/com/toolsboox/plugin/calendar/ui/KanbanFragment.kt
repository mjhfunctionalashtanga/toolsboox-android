package com.toolsboox.plugin.calendar.ui

import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentKanbanBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.BoardsStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Boards — the kanban. The flat Tasks list arranged into To do / Doing / Done swimlanes; ‹ › move a
 * card between stages (moving to Done checks it off + syncs), ↗ opens the task's day. Mirrors iOS
 * KanbanView. Reads every task across day files by `stage`/`done`; the Done column is capped to recent.
 */
@AndroidEntryPoint
class KanbanFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_kanban
    private lateinit var binding: FragmentKanbanBinding

    private val order = listOf("todo", "doing", "done")
    private var selectedBoard: String? = null
    private var boards: List<com.toolsboox.plugin.calendar.da.v2.Board> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentKanbanBinding.bind(view)
        binding.kanbanClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.kanbanNew.setOnClickListener { promptNewTask() }
        binding.kanbanBoard.setOnClickListener { showBoardPicker() }
        load()
    }

    private fun updateBoardLabel() {
        val name = selectedBoard?.let { id -> boards.firstOrNull { it.id == id }?.name } ?: "All boards"
        binding.kanbanBoard.text = "$name  ▾"
    }

    /** All boards / each board / ＋ New board / Delete — the board switcher. */
    private fun showBoardPicker() {
        val ctx = requireContext()
        val labels = mutableListOf("▦  All boards")
        labels += boards.map { "▤  ${it.name.ifBlank { "Untitled" }}" }
        labels += "＋  New board…"
        val hasSel = selectedBoard != null
        if (hasSel) labels += "🗑  Delete this board"
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Boards")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> { selectedBoard = null; load() }
                    which <= boards.size -> { selectedBoard = boards[which - 1].id; load() }
                    which == boards.size + 1 -> promptNewBoard()
                    else -> { selectedBoard?.let { BoardsStore.delete(ctx, it) }; selectedBoard = null; load() }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptNewBoard() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Board name"; setSingleLine() }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("New board")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) { selectedBoard = BoardsStore.add(ctx, name).id; load() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun colOf(i: LedgerItem): String = if (i.done) "done" else if (i.stage == "doing") "doing" else "todo"

    /** Create a new card straight from the board — a task on today's page, in the To do column. */
    private fun promptNewTask() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "New task"; setSingleLine() }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("New card")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) addTask(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTask(text: String) {
        val today = java.time.LocalDate.now()
        val item = LedgerItem(
            id = "li-" + java.util.UUID.randomUUID().toString().lowercase(),
            kind = LedgerItem.Kind.TASK, text = text, date = Date(), stage = "todo",
            board = selectedBoard ?: ""
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val day = calendarDayService.load(root, today, null, Locale.getDefault())
                day.ledgerItems.add(item)
                calendarDayService.save(root, today, day)
            }
            load()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), item) }
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            boards = withContext(Dispatchers.IO) { BoardsStore.list(requireContext()) }
            updateBoardLabel()
            val all = withContext(Dispatchers.IO) { gatherAllTasks() }
            val tasks = selectedBoard?.let { b -> all.filter { it.board == b } } ?: all
            renderColumn(binding.colTodoCards, binding.colTodoHead, "TO DO", tasks.filter { colOf(it) == "todo" }, "todo")
            renderColumn(binding.colDoingCards, binding.colDoingHead, "DOING", tasks.filter { colOf(it) == "doing" }, "doing")
            renderColumn(binding.colDoneCards, binding.colDoneHead, "DONE", tasks.filter { colOf(it) == "done" }.take(40), "done")
        }
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
            card.addView(TextView(ctx).apply {
                text = item.text; textSize = 13f; setTextColor(0xFF000000.toInt())
                if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, 0)
            }
            fun glyph(g: String, size: Float, onTap: () -> Unit) = TextView(ctx).apply {
                text = g; textSize = size; setTextColor(0xFF2F6F96.toInt()); setPadding(px(8), 0, px(8), 0)
                setOnClickListener { onTap() }
            }
            if (col != "todo") row.addView(glyph("‹", 20f) { setStage(item, prev(col)) })
            row.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            row.addView(glyph("↗", 15f) { openDay(item.date) })
            row.addView(Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            if (col != "done") row.addView(glyph("›", 20f) { setStage(item, next(col)) })
            card.addView(row)
            container.addView(card)
        }
    }

    private fun next(col: String) = order[(order.indexOf(col) + 1).coerceAtMost(2)]
    private fun prev(col: String) = order[(order.indexOf(col) - 1).coerceAtLeast(0)]

    private fun setStage(item: LedgerItem, stage: String) {
        val ld = item.date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        lifecycleScope.launch {
            // Save + re-render immediately; push CalDAV in the background so the UI never waits on it.
            val updated = withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val day = calendarDayService.load(root, ld, null, Locale.getDefault())
                val j = day.ledgerItems.indexOfFirst { it.id == item.id }
                if (j < 0) return@withContext null
                day.ledgerItems[j].stage = stage
                day.ledgerItems[j].done = (stage == "done")
                calendarDayService.save(root, ld, day)
                day.ledgerItems[j]
            }
            load()
            if (updated != null) lifecycleScope.launch(Dispatchers.IO) {
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(requireContext(), updated) }
            }
        }
    }

    private fun openDay(date: Date) {
        val ld = date.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        CalendarNavigator.toDayPage(this, ld, CalendarDay.DEFAULT_STYLE)
    }

    private fun gatherAllTasks(): List<LedgerItem> {
        val calendarRoot = File(documentsRoot(), "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val out = mutableListOf<LedgerItem>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                // Slim decode — read only ledgerItems, skip the heavy stroke arrays (mirrors iOS DayLite).
                val items = runCatching { calendarDayService.loadLedgerItems(file) }.getOrNull() ?: return@forEach
                out.addAll(items.filter { it.kind == LedgerItem.Kind.TASK })
            }
        return out.sortedByDescending { it.date.time }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    override fun showLoading() {}
    override fun hideLoading() {}
}
