package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.PickingsStore
import com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
import com.toolsboox.plugin.calendar.ot.SemanticRoots
import com.toolsboox.plugin.calendar.ot.Spiral
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 🌱 **Sprouts** — the Android mirror of the iPad's SproutsView; the Garden's harvest.
 *
 * Where Roots and Map let you *look* at what keeps coming back, Sprouts is where you *act* on it: the
 * emergent ideas the meaning model grew from your own corpus — two things you made at different times
 * that rhyme in meaning though nothing links them (see [SemanticRoots.meaningCrossings]) — each a
 * little sprout you can pick and bring into your day. Bring it to the Daily (a task on today), to the
 * Desk (a text note), or pick it into today's pickings with the crossing captioned on the card.
 *
 * A taken sprout rests a few days before it can surface again — so the harvest keeps moving rather
 * than re-offering what you already carried off.
 */
@AndroidEntryPoint
class SproutsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    override val view = R.layout.fragment_semantic_surface

    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private var navBar: SemanticNavBar? = null
    private var sprouts: List<Sprout> = emptyList()
    private val taken = HashSet<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class Sprout(
        val id: String, val aText: String, val aTag: String,
        val bText: String, val bTag: String, val score: Double
    ) {
        val line: String get() = "$aText  ⇄  $bText"
        val noteBody: String get() = "$aText\n— $aTag\n\n⇄ rhymes with:\n\n$bText\n— $bTag"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "🌱 Sprouts"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        navBar = SemanticNavBar(this, view.findViewById(R.id.semantic_navigator),
            calendarDayService, calendarPatternService) { documentsRoot() }
        view.findViewById<TextView>(R.id.semantic_close)
            .setOnClickListener { findNavController().popBackStack() }
        load()
    }

    private fun load() {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(hint("Letting the day's ideas sprout…"))
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { grow(ctx) }.getOrNull() ?: emptyList() }
            if (!isAdded) return@launch
            sprouts = found
            render()
        }
    }

    private fun render() {
        column.removeAllViews()
        val shown = sprouts.filter { it.id !in taken }
        if (shown.isEmpty()) {
            column.addView(hint(
                "No two things you kept rhyme deeply enough to sprout an idea yet. Let the roots grow, " +
                "or add an embeddings key in Settings so the meaning model can cross them."))
            com.toolsboox.ot.ReadingSize.apply(scroll)
            return
        }
        for (s in shown) column.addView(card(s))
        com.toolsboox.ot.ReadingSize.apply(scroll)
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(12), 0, 0)
    }

    private fun leg(text: String, tag: String): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 16f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.2f)
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                this.text = tag
                textSize = 12f; setTextColor(0xFF999999.toInt()); setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun card(s: Sprout): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt()); setStroke(dp(1), 0xFFBBBBBB.toInt()); cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3), dp(4), dp(3), dp(5)) }
        }
        box.addView(leg(s.aText, s.aTag))
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(ctx).apply {
                text = "⇅  rhymes in meaning"
                textSize = 12f; setTextColor(0xFF777777.toInt())
            })
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            addView(TextView(ctx).apply {
                text = "${(s.score * 100).toInt()}%"
                textSize = 12f; setTextColor(0xFF777777.toInt())
            })
        })
        box.addView(leg(s.bText, s.bTag))

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        }
        actions.addView(actionButton("☀ To Daily") { bringToDaily(s) })
        actions.addView(actionButton("✎ To Desk") { bringToDesk(s) })
        actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        actions.addView(actionButton("🌿 Pick") { pick(s) })
        box.addView(actions)
        return box
    }

    private fun actionButton(label: String, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = label
        textSize = 14f; setTextColor(0xFF000000.toInt())
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x11000000); cornerRadius = dp(8).toFloat()
        }
        (layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { layoutParams = it }).setMargins(0, 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    // MARK: - Take actions

    private fun bringToDaily(s: Sprout) {
        val ctx = context ?: return
        val today = LocalDate.now()
        retire(ctx, s)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = documentsRoot()
                val day = calendarDayService.load(root, today, null, Locale.getDefault())
                day.ledgerItems.add(LedgerItem(
                    id = "sprout-" + java.util.UUID.randomUUID().toString().lowercase(),
                    kind = LedgerItem.Kind.TASK, text = s.line.take(140), date = Date(),
                    source = "sprout", stage = "todo"))
                calendarDayService.save(root, today, day)
            }
        }
        showMessage("Brought to today", requireView())
    }

    private fun bringToDesk(s: Sprout) {
        val ctx = context ?: return
        retire(ctx, s)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                com.toolsboox.plugin.textnotes.TextNotesStore.addNote(
                    ctx, LocalDate.now(), "🌱 ${s.aTag} ⇄ ${s.bTag}", s.noteBody)
            }
        }
        showMessage("Sent to Desk notes", requireView())
    }

    private fun pick(s: Sprout) {
        val ctx = context ?: return
        val today = LocalDate.now()
        retire(ctx, s)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val card = QuoteCardRenderer.render(s.line.take(600), "✧ a crossing", null, 1080, 0)
                PickingsPlacement.place(
                    calendarDayService, documentsRoot(), card, today, PickingsStore.DEFAULT_KEY,
                    sourceLabel = "✧ ${s.aTag} ⇄ ${s.bTag}".take(80), cardText = s.line.take(600))
            }
        }
        showMessage("Picked into today's pickings", requireView())
    }

    private fun retire(ctx: Context, s: Sprout) {
        taken.add(s.id)
        SproutRestStore.rest(ctx, s.id)
        render()
    }

    // MARK: - Grow

    private fun tag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    private fun grow(ctx: Context): List<Sprout> {
        val corpus = corpusService.gather(documentsRoot(), Spiral.SCOPE)
            .filter { Spiral.isSubstantial(it.text) }
            .let { Spiral.dedupe(it) { s -> s.text } }
        if (corpus.isEmpty()) return emptyList()
        val crossings = SemanticRoots.meaningCrossings(ctx, corpus, cap = 260, topN = 40, minScore = 0.60)
        return crossings.mapNotNull { c ->
            val a = c.a.text.take(200).trim()
            val b = c.b.text.take(200).trim()
            val id = "${a.take(24)}|${b.take(24)}"
            if (SproutRestStore.isResting(ctx, id)) return@mapNotNull null
            Sprout(id, a, tag(c.a), b, tag(c.b), c.score)
        }
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}

/**
 * A taken sprout rests for a few days before it can surface again — so the harvest keeps moving on
 * rather than re-offering what you already carried off. Same shape as the Daily Pile's cooldown.
 */
object SproutRestStore {
    private const val PREFS = "sprout_garden_rest"     // id -> epochSeconds
    private const val COOLDOWN_SECONDS = 5L * 86_400L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rest(context: Context, id: String) {
        val now = System.currentTimeMillis() / 1000
        val p = prefs(context)
        val edit = p.edit().putLong(id, now)
        // Sweep the expired so the map can't grow without bound.
        for ((k, v) in p.all) if (v is Long && now - v >= COOLDOWN_SECONDS) edit.remove(k)
        edit.apply()
    }

    fun isResting(context: Context, id: String): Boolean {
        val t = prefs(context).getLong(id, 0L)
        if (t == 0L) return false
        return System.currentTimeMillis() / 1000 - t < COOLDOWN_SECONDS
    }
}
