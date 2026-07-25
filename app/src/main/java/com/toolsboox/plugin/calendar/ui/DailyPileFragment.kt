package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.databinding.FragmentDailyPileBinding
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.PickingsStore
import com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/**
 * Daily Pile — everything the day collected, on one grid: tasks and events, birthdays, the grams
 * you dropped. The concrete pile, not the emergent stuff (that's Roots). A pile you work OUTWARD
 * from — PICKING the ones worth keeping into today's pickings (where they refine into synthesis),
 * or rhizoming the rest. The on-ramp between "the day happened" and "here's what I'll make of it".
 *
 * Mirrors iOS `App/DailyPileView.swift`. Android has no unified Mail, so mail pieces are skipped.
 */
@AndroidEntryPoint
class DailyPileFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_daily_pile

    private lateinit var binding: FragmentDailyPileBinding
    private val date: LocalDate = LocalDate.now()
    private val picked = mutableSetOf<String>()

    // The gathered pile lives on the fragment (not just inside load) so a move/dismiss/delete can
    // re-render the grid without walking the day files again.
    private var pieces: List<Piece> = emptyList()

    // The id of the lifted card while a move is in flight. Pick-up/put-down instead of drag:
    // a drag redraws continuously, which smears on e-ink; two taps redraw exactly twice.
    private var movingId: String? = null

    /** One piece on the day's pile — a movable thing that can be picked or rhizomed. */
    private enum class Kind(val glyph: String, val word: String, val tint: Int) {
        TASK("🃏", "task", 0xFF3B6EA5.toInt()),
        EVENT("📆", "event", 0xFF7A5EA8.toInt()),
        BIRTHDAY("🎂", "birthday", 0xFFB5537F.toInt()),
        GRAM("🖼", "gram", 0xFFB5793B.toInt())
    }

    private data class Piece(
        val id: String, val kind: Kind, val title: String, val uri: String, val image: Bitmap? = null
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentDailyPileBinding.bind(view)
        binding.dailyPileSubtitle.text = date.toString()
        binding.dailyPileClose.setOnClickListener { findNavController().popBackStack() }
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        val ctx = context ?: return
        val col = binding.dailyPileColumn
        col.removeAllViews()
        col.addView(TextView(ctx).apply {
            text = "Gathering the day…"
            textSize = 14f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(12), 0, 0)
        })
        // The view's scope: the gather exists only to fill this column, so back-navigation
        // cancels it instead of ghost-rendering into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            val gathered = withContext(Dispatchers.IO) { gather(ctx) }
            if (!isAdded) return@launch
            pieces = gathered
            rerender()
        }
    }

    /** Redraw the grid from [pieces] as they stand — no re-gather, so moves and removals are instant. */
    private fun rerender() {
        val ctx = context ?: return
        val col = binding.dailyPileColumn
        col.removeAllViews()
        if (pieces.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Nothing's collected on $date yet. Capture through the day and it lands here to refine."
                textSize = 15f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.2f)
                setPadding(0, dp(14), 0, 0)
            })
            com.toolsboox.ot.ReadingSize.apply(binding.dailyPileScroll)
            return
        }
        renderGrid(ctx, col, pieces)
        com.toolsboox.ot.ReadingSize.apply(binding.dailyPileScroll)
    }

    /** Tasks/events + birthdays + grams for the day, read straight from the ledger and contacts. */
    private fun gather(ctx: Context): List<Piece> {
        val out = mutableListOf<Piece>()
        val dayStr = date.toString()
        val day = runCatching {
            calendarDayService.load(documentsRoot(), date, null, Locale.getDefault())
        }.getOrNull()

        // Defensive: a tombstoned item shouldn't be in the file at all, but if a pre-tombstone
        // merge left a copy alongside its tombstone, the pile must not resurrect it.
        val dead = (day?.deletedItemIds.orEmpty() + day?.deletedElementIds.orEmpty()).toSet()
        for (item in day?.ledgerItems.orEmpty()) {
            if (item.text.isBlank() || item.id in dead) continue
            out.add(Piece("task-${item.id}",
                if (item.kind == LedgerItem.Kind.EVENT) Kind.EVENT else Kind.TASK,
                item.text, LedgerUri.task(item.id)))
        }
        for (c in runCatching { ContactStore.list(ctx) }.getOrNull().orEmpty()) {
            if (birthdayMatches(c.birthday, date.monthValue, date.dayOfMonth)) {
                out.add(Piece("bday-${date.monthValue}-${date.dayOfMonth}-${c.id}", Kind.BIRTHDAY,
                    "${c.name} · ${c.birthday}", LedgerUri.contact(c.id)))
            }
        }
        for (img in day?.imageElements.orEmpty()) {
            if (img.data.isBlank()) continue
            val label = img.sourceLabel.ifBlank { img.cardText.ifBlank { "Gram" } }
            val bmp = runCatching {
                val bytes = android.util.Base64.decode(img.data, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            val pageKey = img.page.ifBlank { "default" }
            out.add(Piece("gram-${img.elementId}", Kind.GRAM, label.take(40),
                LedgerUri.element(dayStr, pageKey, img.elementId.toString().lowercase()), bmp))
        }
        // A picked piece rests for a few days before it can surface again; a removed one stays
        // off THIS day's pile for good. Then the user's hand-set order, where one exists.
        val alive = out.filter {
            !PilePickStore.isCooling(ctx, it.id) && !PileDismissStore.isDismissed(ctx, date, it.id)
        }
        return PileOrderStore.arrange(ctx, date, alive)
    }

    private fun renderGrid(ctx: Context, col: LinearLayout, pieces: List<Piece>) {
        val columns = (resources.configuration.screenWidthDp / 190).coerceIn(2, 5)
        var rowBox: LinearLayout? = null
        for ((i, p) in pieces.withIndex()) {
            if (i % columns == 0) {
                rowBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(rowBox)
            }
            rowBox?.addView(card(ctx, p))
        }
        val remainder = pieces.size % columns
        if (remainder != 0) repeat(columns - remainder) {
            rowBox?.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    private fun card(ctx: Context, p: Piece): View {
        val isPicked = picked.contains(p.id)
        val isLifted = movingId == p.id
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFF6F6F6.toInt())
                // The lift is a heavier black stroke, not elevation or animation — a border-width
                // change is one clean redraw, which is all e-ink does well.
                if (isLifted) setStroke(dp(3), 0xFF000000.toInt())
                else setStroke(dp(1), (p.kind.tint and 0x00FFFFFF) or (if (isPicked) 0xB0000000.toInt() else 0x59000000))
                cornerRadius = dp(12).toFloat()
            }
            alpha = if (isPicked) 0.55f else 1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }
        // Long-press holds the piece's menu: move it, remove it, delete it at the source.
        card.setOnLongClickListener { pieceMenu(p); true }
        // While a card is lifted, every card is a landing spot; tapping the lifted one sets it down.
        card.setOnClickListener {
            val lifted = movingId ?: return@setOnClickListener
            if (lifted == p.id) { movingId = null; rerender() } else placeLifted(lifted, p.id)
        }

        if (p.image != null) {
            card.addView(ImageView(ctx).apply {
                setImageBitmap(p.image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(80)
                ).apply { bottomMargin = dp(6) }
            })
        }

        card.addView(TextView(ctx).apply {
            text = "${p.kind.glyph}  ${p.title}"
            textSize = 14f; setTextColor(0xFF000000.toInt())
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // Rhizome — join it to something. The same gesture as everywhere: opens its rhizome page.
        // ⁂ is THE connect glyph on Android (Missed Rhizomes and Quick Wins already wear it).
        actions.addView(TextView(ctx).apply {
            text = "⁂"
            textSize = 20f; setTextColor(p.kind.tint)
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { rhizome(p) }
        })
        actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        // Pick — into today's pickings, where it refines into synthesis.
        actions.addView(TextView(ctx).apply {
            text = if (isPicked) "✓" else "❝"
            textSize = 18f; setTextColor(p.kind.tint)
            if (!isPicked) setOnClickListener { pick(p, card) }
        })
        card.addView(actions)
        return card
    }

    /** Pick a piece into today's pickings basket — a gram of it, where it refines into synthesis. */
    private fun pick(p: Piece, cardView: View) {
        val ctx = context ?: return
        picked.add(p.id)
        PilePickStore.pick(ctx, p.id)
        cardView.alpha = 0.55f
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    val caption = "${p.kind.word} · picked $date"
                    if (p.image != null) {
                        // A gram is already a card — place it as-is (no second tape).
                        PickingsPlacement.place(
                            calendarDayService, root, p.image, date, PickingsStore.DEFAULT_KEY,
                            sourceLink = p.uri, sourceLabel = p.title, treatment = false)
                    } else {
                        // A task/event/birthday becomes a fresh quote card — it gets the tape.
                        val bmp = QuoteCardRenderer.render(p.title.take(600), caption, null, 1080, 0)
                        PickingsPlacement.place(
                            calendarDayService, root, bmp, date, PickingsStore.DEFAULT_KEY,
                            sourceLink = p.uri, sourceLabel = p.title, cardText = p.title.take(600))
                    }
                    true
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            showMessage(
                if (ok) "Picked into today's pickings" else "Couldn't pick that one",
                binding.root)
        }
    }

    /** Open the piece's rhizome — everything it joins, and a place to make a new edge. */
    private fun rhizome(p: Piece) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to p.uri,
                LedgerRhizomeFragment.ARG_LABEL to p.title
            )
        )
    }

    // MARK: - Long-press menu: move / remove / delete

    /**
     * The piece's held menu. Birthdays come from the rolodex, not the day file, so they can be
     * moved or removed from the pile but there's nothing at source to delete.
     */
    private fun pieceMenu(p: Piece) {
        val labels = mutableListOf("⇄ Move — tap where it goes", "🕳 Remove from pile")
        if (p.kind != Kind.BIRTHDAY) labels.add("🗑 Delete ${p.kind.word}")
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(p.title.take(60))
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startMove(p)
                    1 -> removeFromPile(p)
                    2 -> confirmDelete(p)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lift the piece; the next tap on any card puts it down there. */
    private fun startMove(p: Piece) {
        movingId = p.id
        rerender()
        showMessage("Lifted — tap the card whose spot it should take (tap it again to set it down)", binding.root)
    }

    /** Put the lifted piece down in the tapped card's spot, and remember the hand-set order. */
    private fun placeLifted(liftedId: String, targetId: String) {
        val ctx = context ?: return
        val list = pieces.toMutableList()
        val from = list.indexOfFirst { it.id == liftedId }
        if (from >= 0) {
            val piece = list.removeAt(from)
            // Re-find the target AFTER the removal so the insert index is right on both sides.
            val at = list.indexOfFirst { it.id == targetId }
            if (at >= 0) list.add(at, piece) else list.add(from, piece)
        }
        movingId = null
        pieces = list
        PileOrderStore.save(ctx, date, list.map { it.id })
        rerender()
    }

    /** Per-day dismissal — the piece leaves THIS day's pile but its source object stays untouched. */
    private fun removeFromPile(p: Piece) {
        val ctx = context ?: return
        PileDismissStore.dismiss(ctx, date, p.id)
        pieces = pieces.filter { it.id != p.id }
        rerender()
        showMessage("Removed from the pile — the ${p.kind.word} itself is untouched", binding.root)
    }

    /** Deleting is destructive at the source, so it gets the same confirm the ledger list uses. */
    private fun confirmDelete(p: Piece) {
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Delete this ${p.kind.word}?")
            .setMessage(p.title)
            .setPositiveButton("Delete") { _, _ -> delete(p) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Delete the piece's underlying object out of the day file — the grid updates right away. */
    private fun delete(p: Piece) {
        pieces = pieces.filter { it.id != p.id }
        rerender()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when (p.kind) {
                        Kind.TASK, Kind.EVENT -> deleteLedgerItemAtSource(p.id.removePrefix("task-"))
                        Kind.GRAM -> deleteGramAtSource(p.id.removePrefix("gram-"))
                        else -> false
                    }
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            showMessage(
                if (ok) "Deleted the ${p.kind.word}" else "Couldn't delete that ${p.kind.word}",
                binding.root)
        }
    }

    /**
     * Remove a task/event from today's day JSON — the same steps as the ledger list's delete:
     * drop the item, tombstone its id so a union merge can't resurrect it, and take its ON-PAGE
     * face with it (ink strokes by strokeIds, typed tasks by matching text box), or a deleted
     * task keeps showing on the day page. Remote VTODO/event deletes fire after, best-effort.
     */
    private fun deleteLedgerItemAtSource(itemId: String): Boolean {
        val root = documentsRoot()
        val cd = calendarDayService.load(root, date, null, Locale.getDefault())
        val item = cd.ledgerItems.firstOrNull { it.id == itemId } ?: return false
        cd.ledgerItems.removeAll { it.id == itemId }
        cd.tombstoneLedgerItem(itemId)

        val strokeIds = item.strokeIds.toSet()
        if (strokeIds.isNotEmpty()) {
            cd.calendarStrokes[CalendarDay.DEFAULT_STYLE] =
                (cd.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList())
                    .filterNot { it.strokeId.toString() in strokeIds }
            strokeIds.forEach { if (it !in cd.deletedStrokeIds) cd.deletedStrokeIds.add(it) }
        }
        if (item.text.isNotBlank()) {
            val boxes = cd.textElements.filter { it.pageKey == "default" && it.text == item.text }
            cd.textElements.removeAll(boxes)
            boxes.forEach {
                val eid = it.elementId.toString()
                if (eid !in cd.deletedElementIds) cd.deletedElementIds.add(eid)
            }
        }
        calendarDayService.save(root, date, cd)

        // The remote deletes ride their own launch so a slow network can't hold the result.
        val app = context?.applicationContext
        if (app != null) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.deleteTask(app, item) }
            runCatching { com.toolsboox.plugin.calendar.nw.LedgerEventSync.deleteEvent(app, item) }
        }
        return true
    }

    /**
     * Remove a gram from today's day JSON — drop the image element and tombstone its id, the
     * same pair the day surface writes when a card is deleted in place (see CalendarDayFragment's
     * onImageElementsChanged), so sync sees an intentional delete rather than a missing element.
     */
    private fun deleteGramAtSource(elementId: String): Boolean {
        val root = documentsRoot()
        val cd = calendarDayService.load(root, date, null, Locale.getDefault())
        val doomed = cd.imageElements.filter { it.elementId.toString().equals(elementId, ignoreCase = true) }
        if (doomed.isEmpty()) return false
        cd.imageElements.removeAll(doomed)
        doomed.forEach {
            val eid = it.elementId.toString()
            if (eid !in cd.deletedElementIds) cd.deletedElementIds.add(eid)
        }
        calendarDayService.save(root, date, cd)
        return true
    }

    /** Freeform birthday string ("Mar 4", "3-4", "03/04") matched to a month/day. */
    private fun birthdayMatches(bday: String, month: Int, day: Int): Boolean {
        val s = bday.lowercase().trim()
        if (s.isEmpty()) return false
        val months = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
        val nums = s.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
        val mk = months.entries.firstOrNull { s.contains(it.key) }?.value
        if (mk != null) return mk == month && nums.contains(day)
        if (nums.size >= 2) return nums[0] == month && nums[1] == day
        return false
    }

    override fun showLoading() {}
    override fun hideLoading() {}

    /**
     * Picked pieces rest for a few days before they can surface again — retired for a bit, not
     * forever. Keyed by piece id; entries past the cooldown are pruned on write. Mirrors iOS
     * `PilePickStore`.
     */
    private object PilePickStore {
        private const val PREFS = "daily_pile_picks"
        private const val COOLDOWN_MS = 3L * 86_400_000L

        fun pick(ctx: Context, id: String) {
            val p = ctx.getSharedPreferences(PREFS, 0)
            val now = System.currentTimeMillis()
            val e = p.edit().putLong(id, now)
            // Prune anything already past its cooldown so the store stays small.
            for ((k, v) in p.all) if (v is Long && now - v >= COOLDOWN_MS) e.remove(k)
            e.apply()
        }

        fun isCooling(ctx: Context, id: String): Boolean {
            val t = ctx.getSharedPreferences(PREFS, 0).getLong(id, 0L)
            return t > 0L && System.currentTimeMillis() - t < COOLDOWN_MS
        }
    }

    /**
     * The user's hand-set order of the pile — one newline-joined id list per day, keyed by the ISO
     * date. Newline is safe as a joiner because every piece id is built from UUIDs/numbers.
     * Days age out on write (ISO dates compare lexicographically), so the store stays small.
     */
    private object PileOrderStore {
        private const val PREFS = "daily_pile_order"
        private const val KEEP_DAYS = 7L

        fun save(ctx: Context, date: LocalDate, ids: List<String>) {
            val p = ctx.getSharedPreferences(PREFS, 0)
            val e = p.edit().putString(date.toString(), ids.joinToString("\n"))
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
            for (k in p.all.keys) if (k < cutoff) e.remove(k)
            e.apply()
        }

        /** [pieces] in the saved order; anything gathered since the last save lands at the end. */
        fun arrange(ctx: Context, date: LocalDate, pieces: List<Piece>): List<Piece> {
            val saved = ctx.getSharedPreferences(PREFS, 0).getString(date.toString(), null)
                ?: return pieces
            val rank = saved.split("\n").withIndex().associate { (i, id) -> id to i }
            val (known, fresh) = pieces.partition { it.id in rank }
            return known.sortedBy { rank[it.id]!! } + fresh
        }
    }

    /**
     * Pieces removed from the pile by hand — a per-day dismissal list, so "remove" hides the piece
     * from THIS day's pile forever without touching the task/gram/contact it points at. Same
     * newline-joined-per-date shape and lexicographic age-out as [PileOrderStore].
     */
    private object PileDismissStore {
        private const val PREFS = "daily_pile_dismissals"
        private const val KEEP_DAYS = 7L

        fun dismiss(ctx: Context, date: LocalDate, id: String) {
            val p = ctx.getSharedPreferences(PREFS, 0)
            val key = date.toString()
            val ids = p.getString(key, null)?.split("\n")?.toMutableList() ?: mutableListOf()
            if (id !in ids) ids.add(id)
            val e = p.edit().putString(key, ids.joinToString("\n"))
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
            for (k in p.all.keys) if (k < cutoff) e.remove(k)
            e.apply()
        }

        fun isDismissed(ctx: Context, date: LocalDate, id: String): Boolean =
            ctx.getSharedPreferences(PREFS, 0).getString(date.toString(), null)
                ?.split("\n")?.contains(id) == true
    }
}
