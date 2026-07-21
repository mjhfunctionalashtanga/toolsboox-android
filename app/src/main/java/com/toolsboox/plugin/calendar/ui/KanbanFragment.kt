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

    // Opt-in: fold DUE-DATED Site board cards into these same columns (read-only), so the Local
    // kanban becomes one board over both sources. Default off — zero change until turned on.
    private fun kanbanPrefs() = requireContext().getSharedPreferences("ledger_kanban", android.content.Context.MODE_PRIVATE)
    private var includeSite: Boolean = false

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

    /** All boards / each board / ＋ New board / web bridge / Delete — the board switcher. */
    private fun showBoardPicker() {
        val ctx = requireContext()
        val labels = mutableListOf("🌐  Switch to Site boards")   // Local ⇄ Site, like the RSS split
        labels += (if (includeSite) "☑" else "☐") + "  Include dated Site cards"
        labels += "▦  All boards"
        labels += boards.map { "▤  ${it.name.ifBlank { "Untitled" }}" }
        labels += "＋  New board…"
        labels += "⬆  Send board to web"
        labels += "🌐  Web bridge…"
        val hasSel = selectedBoard != null
        if (hasSel) labels += "🗑  Delete this board"
        val base = 2   // rows after the Site-switch entry + the include-site toggle
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Boards · Local")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> NavHostFragment.findNavController(this).navigate(com.toolsboox.R.id.action_to_site_boards)
                    which == 1 -> { includeSite = !includeSite; kanbanPrefs().edit().putBoolean("include_site", includeSite).apply(); load() }
                    which == base -> { selectedBoard = null; load() }
                    which <= base + boards.size -> { selectedBoard = boards[which - base - 1].id; load() }
                    which == base + boards.size + 1 -> promptNewBoard()
                    which == base + boards.size + 2 -> sendBoardToWeb()
                    which == base + boards.size + 3 -> promptBridgeSettings()
                    else -> { selectedBoard?.let { BoardsStore.delete(ctx, it) }; selectedBoard = null; load() }
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
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
            .show()
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
            val all = withContext(Dispatchers.IO) { gatherAllTasks() }
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

    /** Decode a pinned gram's base64 PNG (carried in `crop` when display == INK). Null if not one /
     *  if `crop` is instead an OCR filename (which won't base64-decode to a bitmap). */
    private fun cropBitmap(item: LedgerItem): android.graphics.Bitmap? {
        if (item.display != LedgerItem.Display.INK) return null
        val b64 = item.crop ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

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
        includeSite = kanbanPrefs().getBoolean("include_site", false)
        lifecycleScope.launch {
            boards = withContext(Dispatchers.IO) { BoardsStore.list(requireContext()) }
            updateBoardLabel()
            val all = withContext(Dispatchers.IO) { gatherAllTasks() }
            val tasks = selectedBoard?.let { b -> all.filter { it.board == b } } ?: all
            renderColumn(binding.colTodoCards, binding.colTodoHead, "TO DO", tasks.filter { colOf(it) == "todo" }, "todo")
            renderColumn(binding.colDoingCards, binding.colDoingHead, "DOING", tasks.filter { colOf(it) == "doing" }, "doing")
            renderColumn(binding.colDoneCards, binding.colDoneHead, "DONE", tasks.filter { colOf(it) == "done" }.take(40), "done")
            // Local is on screen; now fold in dated Site cards and site bookings
            // (background, read-only, opt-in).
            if (includeSite && selectedBoard == null) {
                mergeSiteCards()
                mergeSiteBookings()
            }
        }
    }

    /** Fetch DUE-DATED Site board cards and append them (read-only) into the same columns, so the
     *  Local kanban reads as one board over both sources. Local always renders first; this never
     *  blocks it and fails silently (empty) when the bridge is off or unreachable. */
    private fun mergeSiteCards() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                if (!com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx).ready) emptyList()
                else com.toolsboox.plugin.calendar.nw.LedgerBoards.dueCards(ctx)   // server-filtered + bucketed
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

    /** Fetch upcoming site bookings and drop them into the same three columns. A booking already
     *  arrives bucketed (ahead of you → todo, under way → doing, settled → done), so it needs no
     *  translation to sit beside a card: both are dated objects with a state. Read-only here —
     *  the writes live in the booking sheet a tap away. */
    private fun mergeSiteBookings() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val bookings = withContext(Dispatchers.IO) {
                if (!com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx).ready) emptyList()
                else com.toolsboox.plugin.calendar.nw.LedgerBooking.bookings(ctx, limit = 60)
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
        val at = runCatching {
            java.time.LocalDateTime
                .parse((b.startTime ?: "").trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atOffset(java.time.ZoneOffset.UTC)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        }.getOrNull()
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
                    at?.let { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM  HH:mm").format(it) },
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
            setOnClickListener { BookingSheet.open(this@KanbanFragment, b.id) }
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
                NavHostFragment.findNavController(this@KanbanFragment).navigate(
                    com.toolsboox.R.id.action_to_site_boards,
                    androidx.core.os.bundleOf("site_board_id" to c.boardId)
                )
            }
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
                if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            })
            // Contact + time chip (iOS parity): who it's for, when it's due.
            val chipContact = item.contactId?.let { com.toolsboox.plugin.calendar.ot.ContactStore.get(ctx, it) }
            val chip = listOfNotNull(
                chipContact?.name?.ifBlank { "Unnamed" }?.let { "👤 $it" },
                item.time?.takeIf { it.isNotBlank() }?.let { "· $it" }
            ).joinToString("  ")
            if (chip.isNotBlank()) card.addView(TextView(ctx).apply {
                text = chip; textSize = 11f; setTextColor(0xFF666666.toInt()); setPadding(0, px(3), 0, 0)
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
            // Long-press a card for the extras (web push).
            card.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(item.text.ifBlank { "Card" })
                    .setItems(arrayOf("⬆  Send to web board")) { _, _ -> sendToWeb(item) }
                    .setNegativeButton("Close", null)
                    .show()
                true
            }
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


    override fun showLoading() {}
    override fun hideLoading() {}
}
