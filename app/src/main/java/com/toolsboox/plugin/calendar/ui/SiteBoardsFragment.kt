package com.toolsboox.plugin.calendar.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
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
import com.toolsboox.ot.InkPadView

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
    private var pendingBoardId: Int = 0   // deep-link target from the timeline; opened once on load
    private var compact: SiteBoardCompact? = null

    /** The card being dragged and its view, so a drop can reparent + move it. */
    private var dragging: Pair<SiteTask, View>? = null

    /** Show the card's featured image on the card face (toggle, persisted). */
    private var showCovers: Boolean = true
    private fun boardPrefs() = requireContext().getSharedPreferences("ledger_site_boards", android.content.Context.MODE_PRIVATE)

    /** Which card an image-pick will attach to (boardId, taskId), set when Upload… is tapped. */
    private var pendingUpload: Pair<Int, Long>? = null
    private val imagePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingUpload; pendingUpload = null
        if (uri == null || target == null) return@registerForActivityResult
        lifecycleScope.launch {
            val png = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    }
                }.getOrNull()
            }
            if (png == null) { toast("Couldn't read image"); return@launch }
            val status = withContext(Dispatchers.IO) {
                LedgerBoards.commentTask(requireContext(), target.first, target.second, null, png)
            }
            toast(if (status == "Reply posted") "Uploaded to card" else status)
        }
    }

    /** Fetch a public image (card cover / attachment) to a bitmap. Null on failure. */
    private fun loadRemoteBitmap(url: String): Bitmap? = try {
        java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

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
        showCovers = boardPrefs().getBoolean("showCovers", true)
        view.findViewById<Button>(R.id.site_boards_covers).setOnClickListener {
            showCovers = !showCovers
            boardPrefs().edit().putBoolean("showCovers", showCovers).apply()
            toast(if (showCovers) "Card images on" else "Card images off")
            openBoard?.let { loadBoard(it) }
        }
        upButton.setOnClickListener { showList() }
        // Tap the title at the board-list level to flip to the Local board (Local ⇄ Site).
        titleView.setOnClickListener {
            if (openBoard == null) NavHostFragment.findNavController(this).navigate(com.toolsboox.R.id.action_to_kanban)
        }
        // Deep link: a dated Site card tapped in the timeline (kanban / Tasks) passes its board id.
        pendingBoardId = arguments?.getInt("site_board_id", 0) ?: 0
        loadBoards()
    }

    /* ---------------------------------------------------------------
     * Board list
     * ------------------------------------------------------------- */

    private fun loadBoards() {
        titleView.text = "Boards · Site  ⇄"
        upButton.visibility = View.GONE
        openBoard = null
        renderMessage("Loading boards…")
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { LedgerBoards.boards(requireContext()) }
            boards = list
            val target = pendingBoardId
            if (target > 0) {
                pendingBoardId = 0   // consume — a later "up" returns to the list, not back here
                val b = boards.firstOrNull { it.id == target }
                if (b != null) { loadBoard(b); return@launch }
            }
            if (openBoard == null) showList()
        }
    }

    private fun showList() {
        titleView.text = "Boards · Site  ⇄"
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
        // Big boards (the Support board carries 600+ cards) froze here: every card view was built
        // synchronously on the main thread. Render a capped first batch; the rest load on tap. A
        // "＋ N more" footer at the very bottom is not a card (no tag), so drops still land among cards.
        val maxCards = 45
        tasks.take(maxCards).forEach { cardsContainer.addView(cardView(board, it)) }
        if (tasks.size > maxCards) {
            cardsContainer.addView(TextView(ctx).apply {
                text = "＋ ${tasks.size - maxCards} more"
                setTextColor(Color.parseColor("#2F6F96")); textSize = 13f
                setPadding(px(8), px(12), px(8), px(12))
                setOnClickListener {
                    (parent as? ViewGroup)?.removeView(this)   // drop the footer, append the rest
                    tasks.drop(maxCards).forEach { t -> cardsContainer.addView(cardView(board, t)) }
                }
            })
        }
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
        // The card's featured image on its face (toggleable via the 🖼 header button).
        if (showCovers && task.coverUrl != null) {
            val img = android.widget.ImageView(ctx).apply {
                adjustViewBounds = true; setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(6) }
            }
            card.addView(img)
            val url = task.coverUrl
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { loadRemoteBitmap(url) }
                if (bmp != null && isAdded) img.setImageBitmap(bmp)
            }
        }
        val meta = buildList {
            task.priority?.takeIf { it.isNotBlank() && it != "normal" }?.let { add(it) }
            task.dueAt?.take(10)?.let { add("due $it") }
            if (task.commentCount > 0) add("${task.commentCount}💬")
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
        // A container we swap the content INTO — AlertDialog.setView() after show() doesn't reliably
        // replace the view (the card "stuck on Loading…"), but updating this container's children does.
        val container = FrameLayout(ctx)
        container.addView(TextView(ctx).apply { text = "Loading…"; setPadding(px(24), px(24), px(24), px(24)) })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setView(container).create()
        dialog.show()
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) { LedgerBoards.taskDetail(requireContext(), board.id, task.id) }
            if (!isAdded) return@launch
            container.removeAllViews()
            if (detail == null) {
                container.addView(TextView(ctx).apply {
                    text = "Couldn't load the card."; setPadding(px(24), px(24), px(24), px(24))
                })
            } else {
                container.addView(detailView(board, detail, dialog))
            }
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

        // ▸ RELATED (rhizome): where this card came from + its neighbours (same contact). Async so
        // it never blocks the card opening; the section appears only if there's an edge to show.
        val relatedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(relatedBox)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { LedgerBoards.related(requireContext(), board.id, d.id) }
            if (r == null || !isAdded) return@launch
            if (r.provenance == null && r.related.isEmpty()) return@launch
            relatedBox.addView(label("▸ RELATED"))
            r.provenance?.let { p ->
                relatedBox.addView(TextView(ctx).apply {
                    text = p.label + (if (p.url != null) "   ↗" else "")
                    setTextColor(if (p.url != null) Color.parseColor("#2F6F96") else Color.parseColor("#333333"))
                    textSize = 14f; setPadding(0, px(2), 0, px(2))
                    if (p.url != null) setOnClickListener {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.url)))
                    }
                })
            }
            r.related.forEach { rc ->
                relatedBox.addView(TextView(ctx).apply {
                    text = "•  ${rc.title}   ·   ${rc.board}"
                    setTextColor(Color.parseColor("#2F6F96")); textSize = 14f; setPadding(0, px(3), 0, px(1))
                    setOnClickListener {
                        dialog.dismiss()
                        openDetail(
                            SiteBoard(rc.boardId, rc.board, null, 0, 0),
                            SiteTask(rc.taskId, rc.title, 0, 0.0, null, null, null, null, false)
                        )
                    }
                })
            }
        }

        // The card's uploaded image (the handwriting face / cover), if any.
        d.coverUrl?.let { url ->
            val img = android.widget.ImageView(ctx).apply {
                adjustViewBounds = true; setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(12) }
            }
            col.addView(img)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { loadRemoteBitmap(url) }
                if (bmp != null && isAdded) img.setImageBitmap(bmp)
            }
        }

        if (d.description.isNotBlank()) {
            col.addView(label("NOTES"))
            // Keep links LIVE (HTML spans + movement method), but strip the old inbox "Open the …
            // thread ↗" anchor — the ▸ RELATED provenance now owns that link, so it's not doubled.
            val cleanDesc = d.description.replace(
                Regex("<p>\\s*<a\\b[^>]*>\\s*Open the[^<]*thread[^<]*</a>\\s*</p>", RegexOption.IGNORE_CASE), ""
            )
            col.addView(TextView(ctx).apply {
                text = android.text.Html.fromHtml(cleanDesc, android.text.Html.FROM_HTML_MODE_COMPACT).trim()
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setTextColor(Color.BLACK); textSize = 15f
            })
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
        edits.addView(editButton("Upload…") { pendingUpload = board.id to d.id; imagePicker.launch("image/*") })
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

    /** Reply to a card in handwriting and/or typed text — both ride to the same comment. */
    private fun promptReply(board: SiteBoard, d: SiteTaskDetail, parent: androidx.appcompat.app.AlertDialog) {
        val ctx = requireContext()
        val ink = InkPadView(ctx)
        val input = android.widget.EditText(ctx).apply {
            hint = "…or type a reply"; setPadding(px(14), px(12), px(14), px(12)); minLines = 1
        }
        var shareAsGram: (() -> Unit)? = null
        // Actions at the TOP (a writing hand covers bottom buttons) + a bold frame around
        // the pen area so it reads clearly against the white dialog on e-ink.
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, 0, 0, px(6))
        }
        val inkFrame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(px(2), px(2), px(2), px(2))
            addView(ink, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, px(300)
            ))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(6), px(12), 0)
            addView(actionRow)
            // The same pen toolbar the reply pad has — undo, three inks, fine↔bold.
            addView(InkPadView.penBar(ctx, ink))
            addView(inkFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) })
            // Share this handwriting out as a gram (into a community space), citing the card.
            addView(TextView(ctx).apply {
                text = "↗  Share as gram instead…"
                setTextColor(Color.parseColor("#2F6F96")); textSize = 15f
                setPadding(px(2), px(10), 0, px(2))
                setOnClickListener { shareAsGram?.invoke() }
            })
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Reply · ${d.title.take(36)}")
            .setView(box)
            .create()
        fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(Color.parseColor("#2F6F96"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(16), px(4), px(16), px(4)); setOnClickListener { onTap() }
        }
        actionRow.addView(actionBtn("Clear") { ink.clear(); input.text?.clear() })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        actionRow.addView(actionBtn("Send") {
            val text = input.text.toString().trim().ifBlank { null }
            val png = ink.render()?.let { bmp ->
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
                baos.toByteArray()
            }
            if (text == null && png == null) { toast("Nothing to send"); return@actionBtn }
            lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) {
                    LedgerBoards.commentTask(requireContext(), board.id, d.id, text, png)
                }
                toast(status)
                if (status == "Reply posted") { dialog.dismiss(); parent.dismiss(); openDetail(board, taskStub(d)) }
            }
        })
        shareAsGram = {
            val bmp = ink.render()
            if (bmp == null) toast("Nothing written")
            else { dialog.dismiss(); shareCardAsGram(bmp, board, d) }
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Share a card's handwritten reply as a gram: pick the community space, review + edit the
     * provenance line (which cites the card), then post. Outward-facing, so nothing goes until Share.
     */
    private fun shareCardAsGram(bmp: Bitmap, board: SiteBoard, d: SiteTaskDetail) {
        val ctx = requireContext()
        val provDefault = "↩ On board card “${d.title}” · ${board.title}"
        lifecycleScope.launch {
            val spaces = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.spaces(requireContext())
            }
            if (!isAdded) return@launch
            if (spaces.isEmpty()) { toast("No community spaces to share into"); bmp.recycle(); return@launch }
            var chosen = spaces.first()

            val preview = android.widget.ImageView(ctx).apply {
                setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(150))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            val spaceBtn = Button(ctx).apply {
                text = "Space: ${chosen.title}  ▾"; isAllCaps = false
                setOnClickListener {
                    val names = spaces.map { it.title }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("Share to…")
                        .setItems(names) { _, which -> chosen = spaces[which]; text = "Space: ${chosen.title}  ▾" }
                        .show()
                }
            }
            val provInput = android.widget.EditText(ctx).apply {
                setText(provDefault); setSelection(text.length); textSize = 14f
                setPadding(px(12), px(10), px(12), px(10)); minLines = 2
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), 0)
                addView(preview)
                addView(spaceBtn)
                addView(TextView(ctx).apply {
                    text = "PROVENANCE (edit before sharing)"
                    textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(0, px(12), 0, px(3))
                })
                addView(provInput)
            }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Share as gram")
                .setView(box)
                .setPositiveButton("Share ↗") { _, _ ->
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
                    val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    val provenance = provInput.text.toString().trim()
                    val uuid = "gram-" + java.util.UUID.randomUUID().toString().lowercase()
                    val spaceId = chosen.id
                    lifecycleScope.launch {
                        val status = withContext(Dispatchers.IO) {
                            com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.postGram(
                                requireContext(), b64, "", uuid, spaceId, provenance.ifBlank { null }, null
                            )
                        }
                        toast(status)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> bmp.recycle() }
                .show()
        }
    }

    /** Minimal stylus pad — plain touch, no Onyx pipeline (fine for a short handwritten reply). */

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
