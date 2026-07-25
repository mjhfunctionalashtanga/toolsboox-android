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
        val bText: String, val bTag: String, val score: Double,
        // When each leg was made — a crossing belongs to a filtered period if EITHER of the
        // things that crossed was made inside it.
        val aDate: LocalDate?, val bDate: LocalDate?,
        // Each leg's citation — the corpus key a "remove from corpus" tombstones, so junk can be
        // thrown out by the leg that is junk rather than both at once.
        val aCite: String = "", val bCite: String = ""
    ) {
        val line: String get() = "$aText  ⇄  $bText"
        val noteBody: String get() = "$aText\n— $aTag\n\n⇄ rhymes with:\n\n$bText\n— $bTag"

        fun inWindow(start: LocalDate, end: LocalDate): Boolean {
            // A crossing with no dated leg stays in every window — an unknown date is not a date
            // outside the period, so a broad filter must not silently disappear the undated.
            val known = listOfNotNull(aDate, bDate)
            if (known.isEmpty()) return true
            return known.any { !it.isBefore(start) && it.isBefore(end) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "🌱 Sprouts"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        // The strip FILTERS the harvest in place — a period tap scopes to that period, the carets
        // step it, and nothing about a date ever navigates away from Sprouts.
        navBar = SemanticNavBar(this, view.findViewById(R.id.semantic_navigator),
            calendarDayService, calendarPatternService,
            onFilter = { _, _ -> if (isAdded) render() }) { documentsRoot() }
        // The ▦ hub, top-left as everywhere — the same directory accordion the feed carries.
        view.findViewById<android.widget.ImageButton>(R.id.semantic_hub_button)
            .setOnClickListener { showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this)) }
        view.findViewById<TextView>(R.id.semantic_close)
            .setOnClickListener { findNavController().popBackStack() }
        load()
    }

    private fun load() {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(hint("Letting the day's ideas sprout…"))
        // The view's scope: the grow exists only to fill this column, so back-navigation
        // cancels it instead of ghost-rendering into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { grow(ctx) }.getOrNull() ?: emptyList() }
            if (!isAdded) return@launch
            sprouts = found
            render()
        }
    }

    private fun render() {
        column.removeAllViews()
        val alive = sprouts.filter { it.id !in taken }
        val win = navBar?.window()
        val shown = if (win == null) alive else alive.filter { it.inWindow(win.first, win.second) }
        if (shown.isEmpty()) {
            // Two different kinds of nothing. A filtered period with no sprouts is normal and
            // should say WHICH period came up empty — a bare blank here reads as broken — while
            // a corpus that hasn't crossed anything yet needs the longer explanation.
            column.addView(hint(
                if (win != null)
                    "No sprouts for ${navBar?.periodLabel()}. The carets step to the next " +
                    "period; tapping the period again brings back everything."
                else
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
        // The shared semantic-card ground — one drawer for all three surfaces (SemanticCards).
        val box = com.toolsboox.ot.SemanticCards.card(ctx)
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
        // Hold the card for the fuller menu: pick it as a gram in your medium of choice, or
        // throw a junk leg out of the corpus for good.
        box.setOnLongClickListener { holdMenu(s); true }
        return box
    }

    /** The hold menu — the chips' verbs plus the two that don't fit on a strip: the pick that
     *  asks its medium, and per-leg removal from the corpus. Removal is BY LEG on purpose: a
     *  crossing is usually junk because ONE of its legs is boilerplate, and the other may be a
     *  perfectly good note that deserves better company. */
    private fun holdMenu(s: Sprout) {
        val items = mutableListOf(
            "⁂  Pick — make it a gram" to { pickAsGram(s) },
            "☀  To Daily" to { bringToDaily(s) },
            "✎  To Desk" to { bringToDesk(s) },
            "🌿  Pick to Pickings" to { pick(s) },
            "🗑  Remove “${s.aText.take(28)}…” from corpus" to { removeLeg(s.aCite) }
        )
        if (s.bCite != s.aCite)
            items += "🗑  Remove “${s.bText.take(28)}…” from corpus" to { removeLeg(s.bCite) }
        showIconMenu(s.line.take(80), items)
    }

    /** The pick, the way it works everywhere now: the crossing becomes the quote in hand and
     *  [com.toolsboox.plugin.feeds.ot.FeedNoteGram] asks which medium carries your note —
     *  handwriting, text, audio or video — before the card lands on today's Notes page. Saving
     *  with nothing added still makes the plain quote gram; the pick itself is enough. */
    private fun pickAsGram(s: Sprout) {
        val ctx = context ?: return
        retire(ctx, s)
        com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
            this, calendarDayService, documentsRoot(),
            itemText = s.line.take(600),
            originLabel = "✧ ${s.aTag} ⇄ ${s.bTag}".take(80)
        ) { kind, sink -> captureAvGramDirect(kind, sink) }
    }

    /** Tombstone one leg: every sprout standing on it comes down now, and the corpus never
     *  indexes it again — stronger than the rest cooldown, which only postpones. */
    private fun removeLeg(cite: String) {
        if (cite.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { corpusService.exclude(cite) } }
        sprouts = sprouts.filterNot { it.aCite == cite || it.bCite == cite }
        render()
        showMessage("Removed from the corpus — it won't be crossed again", requireView())
    }

    // The shared chip: solid 1dp black stroke so the verb reads on e-ink (SemanticCards).
    private fun actionButton(label: String, onClick: () -> Unit) =
        com.toolsboox.ot.SemanticCards.actionChip(requireContext(), label, onClick)

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
        fun day(d: Date?): LocalDate? = d?.toInstant()
            ?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
        return crossings.mapNotNull { c ->
            val a = c.a.text.take(200).trim()
            val b = c.b.text.take(200).trim()
            val id = "${a.take(24)}|${b.take(24)}"
            if (SproutRestStore.isResting(ctx, id)) return@mapNotNull null
            Sprout(id, a, tag(c.a), b, tag(c.b), c.score, day(c.a.date), day(c.b.date),
                c.a.citation, c.b.citation)
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
