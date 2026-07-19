package com.toolsboox.plugin.calendar.ui

import android.content.ClipData
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.plugin.calendar.nw.LedgerBoards
import com.toolsboox.plugin.calendar.nw.SiteBoard
import com.toolsboox.plugin.calendar.nw.SiteBoardCompact
import com.toolsboox.plugin.calendar.nw.SiteStage
import com.toolsboox.plugin.calendar.nw.SiteTask
import com.toolsboox.plugin.calendar.nw.SiteTaskDetail
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Site Boards — a tactile browser for every FluentBoards board on the site. The board list opens a
 * board into real columns of real cards; long-press lifts a card and dropping it on a column moves
 * the task on the server (the move fires FluentBoards' own stage-changed hook — notifications,
 * default-assignees, email). Tapping a card opens the whole task: description, people, labels, the
 * comment thread — repliable in typed text. Reuses the "Community & Boards" bridge creds.
 *
 * Read-only-safe: if the bridge isn't configured, the list is simply empty with a hint. Every
 * network call runs off the main thread and fails quietly to an empty/null result.
 */
@AndroidEntryPoint
class SiteBoardsFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_site_boards

    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var upButton: Button

    private var boards: List<SiteBoard> = emptyList()
    private var openBoard: SiteBoard? = null
    private var compact: SiteBoardCompact? = null

    /** The card being dragged and its view, so a drop can reparent + move it. */
    private var dragging: Pair<SiteTask, View>? = null

    private val density get() = resources.displayMetrics.density
    private fun px(v: Int): Int = (v * density).toInt()
    private fun toast(msg: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.site_boards_content)
        titleView = view.findViewById(R.id.site_boards_title)
        upButton = view.findViewById(R.id.site_boards_up)
        view.findViewById<Button>(R.id.site_boards_close).setOnClickListener {
            NavHostFragment.findNavController(this).popBackStack()
        }
        view.findViewById<Button>(R.id.site_boards_refresh).setOnClickListener {
            val b = openBoard
            if (b == null) loadBoards() else loadBoard(b)
        }
        upButton.setOnClickListener { showList() }
        loadBoards()
    }

    /* ---------------------------------------------------------------
     * Board list
     * ------------------------------------------------------------- */

    private fun loadBoards() {
        titleView.text = "Site Boards"
        upButton.visibility = View.GONE
        openBoard = null
        renderMessage("Loading boards…")
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { LedgerBoards.boards(requireContext()) }
            boards = list
            if (openBoard == null) showList()
        }
    }

    private fun showList() {
        titleView.text = "Site Boards"
        upButton.visibility = View.GONE
        openBoard = null
        compact = null
        if (boards.isEmpty()) {
            renderMessage("No boards.\n\nSet the site, user and app password under Settings → Community & Boards (Fluent), then refresh with ↻.")
            return
        }
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(6), px(10), px(24))
        }
        // Correspondence Inbox — replies to your shared items become cards on the configured board.
        val inboxBoardId = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx).boardId
        if (inboxBoardId > 0) col.addView(inboxRow(inboxBoardId))
        for (b in boards) {
            col.addView(boardRow(b))
        }
        scroll.addView(col)
        setContent(scroll)
    }

    /** The inbox pull: sync correspondence to the configured board, then open it. */
    private fun inboxRow(boardId: Int): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5")); setStroke(px(2), Color.BLACK); cornerRadius = px(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8); bottomMargin = px(6) }
            isClickable = true
            setOnClickListener { syncInboxThenOpen(boardId) }
        }
        row.addView(TextView(ctx).apply {
            text = "↩  Correspondence Inbox"
            setTextColor(Color.BLACK); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(TextView(ctx).apply {
            text = "Replies to what you've shared → land here as cards. Tap to sync & open."
            setTextColor(Color.parseColor("#666666")); textSize = 13f
            setPadding(0, px(3), 0, 0)
        })
        return row
    }

    private fun syncInboxThenOpen(boardId: Int) {
        toast("Gathering replies…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { LedgerBoards.syncInbox(requireContext(), boardId) }
            if (!isAdded) return@launch
            if (result == null) { toast("Couldn't reach the inbox"); return@launch }
            val (created, _) = result
            toast(if (created > 0) "$created new" else "No new replies")
            val board = boards.firstOrNull { it.id == boardId }
                ?: SiteBoard(boardId, "Correspondence Inbox", null, 0, 0)
            loadBoard(board)
        }
    }

    private fun boardRow(board: SiteBoard): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(2), Color.BLACK); cornerRadius = px(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
            isClickable = true
            setOnClickListener { loadBoard(board) }
        }
        row.addView(TextView(ctx).apply {
            text = board.title
            setTextColor(Color.BLACK); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(TextView(ctx).apply {
            val cards = if (board.taskCount == 1) "1 card" else "${board.taskCount} cards"
            val cols = if (board.stageCount == 1) "1 column" else "${board.stageCount} columns"
            text = "$cards · $cols"
            setTextColor(Color.parseColor("#666666")); textSize = 13f
            setPadding(0, px(3), 0, 0)
        })
        return row
    }

    /* ---------------------------------------------------------------
     * One board — the tactile columns
     * ------------------------------------------------------------- */

    private fun loadBoard(board: SiteBoard) {
        openBoard = board
        titleView.text = board.title
        upButton.visibility = View.VISIBLE
        renderMessage("Loading ${board.title}…")
        lifecycleScope.launch {
            val c = withContext(Dispatchers.IO) { LedgerBoards.compactBoard(requireContext(), board.id) }
            if (openBoard?.id != board.id) return@launch
            compact = c
            if (c == null) renderMessage("Couldn't reach ${board.title}.") else renderColumns(board, c)
        }
    }

    private fun renderColumns(board: SiteBoard, c: SiteBoardCompact) {
        val ctx = requireContext()
        val hScroll = HorizontalScrollView(ctx).apply { isFillViewport = true }
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(6), px(6), px(6))
        }
        val byStage = c.tasks.groupBy { it.stageId }
        for (stage in c.stages) {
            strip.addView(columnView(board, stage, byStage[stage.id].orEmpty()))
        }
        hScroll.addView(strip)
        setContent(hScroll)
    }

    private fun columnView(board: SiteBoard, stage: SiteStage, tasks: List<SiteTask>): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(px(268), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginStart = px(4); marginEnd = px(4) }
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(px(6), px(6), px(6), px(6))
        }
        column.addView(TextView(ctx).apply {
            text = "${stage.title.uppercase()}  ${tasks.size}"
            setTextColor(Color.parseColor("#666666")); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(px(2), 0, 0, px(4))
        })

        val cardsScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val cardsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = px(120)   // a droppable target even when empty
        }
        for (t in tasks) cardsContainer.addView(cardView(board, t))
        cardsContainer.setOnDragListener(columnDropListener(board, stage, cardsContainer))
        cardsScroll.addView(cardsContainer)
        column.addView(cardsScroll)
        return column
    }

    private fun cardView(board: SiteBoard, task: SiteTask): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(2), Color.BLACK); cornerRadius = px(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(8) }
            tag = task
        }
        card.addView(TextView(ctx).apply {
            text = (if (task.isLedgr) "✦ " else "") + task.title
            setTextColor(Color.BLACK); textSize = 15f
        })
        val meta = buildList {
            task.priority?.takeIf { it.isNotBlank() && it != "normal" }?.let { add(it) }
            task.dueAt?.take(10)?.let { add("due $it") }
            if (task.status == "closed") add("✓ done")
        }
        if (meta.isNotEmpty()) card.addView(TextView(ctx).apply {
            text = meta.joinToString("  ·  ")
            setTextColor(Color.parseColor("#777777")); textSize = 12f
            setPadding(0, px(4), 0, 0)
        })

        card.setOnClickListener { openDetail(board, task) }
        card.setOnLongClickListener {
            dragging = task to card
            val clip = ClipData.newPlainText("ledgr_task", task.id.toString())
            val shadow = View.DragShadowBuilder(card)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                card.startDragAndDrop(clip, shadow, null, 0) else card.startDrag(clip, shadow, null, 0)
            card.visibility = View.INVISIBLE
            true
        }
        return card
    }

    /** A column accepts a dropped card and moves the task there on the server. */
    private fun columnDropListener(board: SiteBoard, stage: SiteStage, container: LinearLayout) =
        View.OnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> { container.setBackgroundColor(Color.parseColor("#E0E0E0")); true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_LOCATION -> {
                    if (event.action == DragEvent.ACTION_DRAG_EXITED) container.setBackgroundColor(Color.TRANSPARENT)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    container.setBackgroundColor(Color.TRANSPARENT)
                    val (task, view) = dragging ?: return@OnDragListener true
                    val insertAt = reparentInto(container, view, event.y)
                    view.visibility = View.VISIBLE
                    moveOnServer(board, task, stage, view, insertAt + 1)   // server index is 1-based
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    container.setBackgroundColor(Color.TRANSPARENT)
                    // If nothing accepted the drop, un-hide the original.
                    if (!event.result) dragging?.second?.visibility = View.VISIBLE
                    true
                }
                else -> true
            }
        }

    /**
     * Drop [view] into [container] at the position the finger released, and return the 0-based
     * index it landed at. The dragged view is removed first so the index math is against the
     * settled child list — cross-column or reorder-in-place both work.
     */
    private fun reparentInto(container: LinearLayout, view: View, dropY: Float): Int {
        var refChild: View? = null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === view) continue
            if (dropY <= child.top + child.height / 2f) { refChild = child; break }
        }
        (view.parent as? ViewGroup)?.removeView(view)
        val insertAt = if (refChild != null) container.indexOfChild(refChild) else container.childCount
        container.addView(view, insertAt)
        return insertAt
    }

    private fun moveOnServer(board: SiteBoard, task: SiteTask, stage: SiteStage, view: View, index: Int) {
        // Reflect the new stage locally so a re-drop from here reads correctly.
        view.tag = task.copy(stageId = stage.id)
        val samePlace = task.stageId == stage.id
        dragging = null
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                LedgerBoards.moveTaskAt(requireContext(), board.id, task.id, stage.id, index)
            }
            if (!samePlace) toast(if (status == "Moved") "→ ${stage.title}" else status)
            if (status != "Moved") openBoard?.let { loadBoard(it) }   // reconcile on failure
        }
    }

    /* ---------------------------------------------------------------
     * One card — the deep detail
     * ------------------------------------------------------------- */

    private fun openDetail(board: SiteBoard, task: SiteTask) {
        val ctx = requireContext()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(TextView(ctx).apply { text = "Loading…"; setPadding(px(24), px(24), px(24), px(24)) })
            .create()
        dialog.show()
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) { LedgerBoards.taskDetail(requireContext(), board.id, task.id) }
            if (!isAdded) return@launch
            if (detail == null) { dialog.dismiss(); toast("Couldn't load the card"); return@launch }
            dialog.setView(detailView(board, detail, dialog))
        }
    }

    private fun detailView(board: SiteBoard, d: SiteTaskDetail, dialog: androidx.appcompat.app.AlertDialog): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(20), px(22), px(18))
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t; setTextColor(Color.parseColor("#888888")); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
            setPadding(0, px(14), 0, px(3))
        }
        fun body(t: String) = TextView(ctx).apply { text = t; setTextColor(Color.BLACK); textSize = 15f }

        col.addView(TextView(ctx).apply {
            text = d.title; setTextColor(Color.BLACK); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        })
        val meta = buildList {
            d.status?.let { add(if (it == "closed") "✓ done" else it) }
            d.priority?.takeIf { it.isNotBlank() && it != "normal" }?.let { add(it) }
            d.dueAt?.take(10)?.let { add("due $it") }
        }
        if (meta.isNotEmpty()) col.addView(TextView(ctx).apply {
            text = meta.joinToString("   ·   "); setTextColor(Color.parseColor("#666666")); textSize = 13f
            setPadding(0, px(4), 0, 0)
        })

        if (d.description.isNotBlank()) {
            col.addView(label("NOTES"))
            col.addView(body(android.text.Html.fromHtml(d.description, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()))
        }
        if (d.assignees.isNotEmpty()) {
            col.addView(label("PEOPLE"))
            col.addView(body(d.assignees.joinToString("\n") { "• ${it.name}" + (if (it.email.isNotBlank()) "  <${it.email}>" else "") }))
        }
        if (d.labels.isNotEmpty()) {
            col.addView(label("LABELS"))
            col.addView(body(d.labels.joinToString("   ") { "◆ ${it.title}" }))
        }
        if (d.crmName != null) {
            col.addView(label("CONTACT"))
            col.addView(body(d.crmName + (if (d.crmEmail != null) "  <${d.crmEmail}>" else "")))
        }
        col.addView(label(if (d.comments.isEmpty()) "COMMENTS  (none yet)" else "COMMENTS"))
        for (cm in d.comments.reversed()) {
            col.addView(TextView(ctx).apply {
                text = "${cm.author}: ${cm.excerpt}"
                setTextColor(Color.parseColor("#333333")); textSize = 14f
                setPadding(0, px(6), 0, px(2))
            })
        }

        // Edit row — assign people, set a due date, set priority. Each saves through the
        // FluentBoards update path (email + hooks fire), then reopens the card fresh.
        val edits = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, px(16), 0, 0)
        }
        fun editButton(label: String, onTap: () -> Unit) = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 13f
            setPadding(px(14), 0, px(14), 0); minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(6) }
            setOnClickListener { onTap() }
        }
        edits.addView(editButton("Assign…") { promptAssign(board, d, dialog) })
        edits.addView(editButton("Due…") { promptDue(board, d, dialog) })
        edits.addView(editButton("Priority…") { promptPriority(board, d, dialog) })
        col.addView(edits)

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, px(14), 0, 0)
        }
        actions.addView(Button(ctx).apply {
            text = "Reply"; isAllCaps = false
            setOnClickListener { promptReply(board, d, dialog) }
        })
        actions.addView(Button(ctx).apply {
            text = "Close"; isAllCaps = false
            setPadding(px(20), 0, 0, 0)
            setOnClickListener { dialog.dismiss() }
        })
        col.addView(actions)
        scroll.addView(col)
        return scroll
    }

    private fun promptReply(board: SiteBoard, d: SiteTaskDetail, parent: androidx.appcompat.app.AlertDialog) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "Your reply…"; setPadding(px(20), px(16), px(20), px(16)); minLines = 2
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Comment on “${d.title.take(40)}”")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        LedgerBoards.commentTask(requireContext(), board.id, d.id, text, null)
                    }
                    toast(status)
                    if (status == "Reply posted") { parent.dismiss(); openDetail(board, taskStub(d)) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Save an edit through updateTask, then reopen the card so it shows the new state. */
    private fun saveEdit(
        board: SiteBoard, d: SiteTaskDetail, parent: androidx.appcompat.app.AlertDialog,
        assignees: List<Int>? = null, dueAt: String? = null, priority: String? = null,
    ) {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                LedgerBoards.updateTask(requireContext(), board.id, d.id, assignees, dueAt, priority)
            }
            toast(status)
            if (status == "Saved") { parent.dismiss(); openDetail(board, taskStub(d)) }
        }
    }

    private fun promptAssign(board: SiteBoard, d: SiteTaskDetail, parent: androidx.appcompat.app.AlertDialog) {
        lifecycleScope.launch {
            val roster = withContext(Dispatchers.IO) { LedgerBoards.members(requireContext(), board.id) }
            if (!isAdded) return@launch
            if (roster.isEmpty()) { toast("No people on this board to assign"); return@launch }
            val names = roster.map { it.name }.toTypedArray()
            val current = d.assignees.map { it.id }.toSet()
            val checked = BooleanArray(roster.size) { roster[it].id in current }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Assign people")
                .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Save") { _, _ ->
                    val ids = roster.filterIndexed { i, _ -> checked[i] }.map { it.id }
                    saveEdit(board, d, parent, assignees = ids)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun promptDue(board: SiteBoard, d: SiteTaskDetail, parent: androidx.appcompat.app.AlertDialog) {
        val cal = java.util.Calendar.getInstance()
        d.dueAt?.take(10)?.let { s ->
            runCatching {
                val p = s.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            }
        }
        val picker = android.app.DatePickerDialog(requireContext(), { _, y, m, day ->
            saveEdit(board, d, parent, dueAt = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day))
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
        if (d.dueAt != null) picker.setButton(
            android.app.DatePickerDialog.BUTTON_NEUTRAL, "Clear"
        ) { _, _ -> saveEdit(board, d, parent, dueAt = "") }
        picker.show()
    }

    private fun promptPriority(board: SiteBoard, d: SiteTaskDetail, parent: androidx.appcompat.app.AlertDialog) {
        val options = arrayOf("low", "normal", "high")
        val cur = options.indexOf(d.priority ?: "normal").coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Priority")
            .setSingleChoiceItems(options, cur) { dlg, which ->
                saveEdit(board, d, parent, priority = options[which])
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun taskStub(d: SiteTaskDetail) =
        SiteTask(d.id, d.title, d.stageId, 0.0, d.priority, d.status, d.dueAt, d.coverUrl, d.isLedgr)

    /* ---------------------------------------------------------------
     * Content helpers
     * ------------------------------------------------------------- */

    private fun setContent(v: View) {
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderMessage(msg: String) {
        setContent(TextView(requireContext()).apply {
            text = msg; setTextColor(Color.parseColor("#666666")); textSize = 15f
            gravity = Gravity.CENTER; setPadding(px(32), px(48), px(32), px(32))
        })
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
