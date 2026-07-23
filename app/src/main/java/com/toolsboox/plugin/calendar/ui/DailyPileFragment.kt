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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.databinding.FragmentDailyPileBinding
import com.toolsboox.ot.LedgerUri
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
        lifecycleScope.launch {
            val pieces = withContext(Dispatchers.IO) { gather(ctx) }
            if (!isAdded) return@launch
            col.removeAllViews()
            if (pieces.isEmpty()) {
                col.addView(TextView(ctx).apply {
                    text = "Nothing's collected on $date yet. Capture through the day and it lands here to refine."
                    textSize = 15f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(14), 0, 0)
                })
                com.toolsboox.ot.ReadingSize.apply(binding.dailyPileScroll)
                return@launch
            }
            renderGrid(ctx, col, pieces)
            com.toolsboox.ot.ReadingSize.apply(binding.dailyPileScroll)
        }
    }

    /** Tasks/events + birthdays + grams for the day, read straight from the ledger and contacts. */
    private fun gather(ctx: Context): List<Piece> {
        val out = mutableListOf<Piece>()
        val dayStr = date.toString()
        val day = runCatching {
            calendarDayService.load(documentsRoot(), date, null, Locale.getDefault())
        }.getOrNull()

        for (item in day?.ledgerItems.orEmpty()) {
            if (item.text.isBlank()) continue
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
        // A picked piece rests for a few days before it can surface again.
        return out.filter { !PilePickStore.isCooling(ctx, it.id) }
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
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFF6F6F6.toInt())
                setStroke(dp(1), (p.kind.tint and 0x00FFFFFF) or (if (isPicked) 0xB0000000.toInt() else 0x59000000))
                cornerRadius = dp(12).toFloat()
            }
            alpha = if (isPicked) 0.55f else 1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
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
        actions.addView(TextView(ctx).apply {
            text = "↪"
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
        lifecycleScope.launch {
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
}
