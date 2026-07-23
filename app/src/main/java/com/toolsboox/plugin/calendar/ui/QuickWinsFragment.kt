package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.ot.ConnectionStore
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
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/**
 * ⚡ **Quick Wins** — the Android mirror of the iPad's QuickWinsView.
 *
 * The system's understanding of your own graph, turned into a path to victory. It reads the
 * CONNECTION graph and the semantic roots to find the undone tasks that are quickest to finish and
 * highest in leverage: the ones you've ALREADY committed to a synthesis / pickings / write basket
 * (so finishing them completes something you started), the ones that connect many things (so one
 * action unblocks a cluster), and the small standalone ones. Each win carries the companions worth
 * grabbing with it — the objects it's connected to, and a thing it rhymes with — so you work a whole
 * neighbourhood in one pass. Optionally, the Ledger narrates the order.
 *
 * A page, not a dialog — the same call the roots and rhizome surfaces made, and for the same reason:
 * something meant to be read and acted on wants to be arrived at, not dismissed.
 */
@AndroidEntryPoint
class QuickWinsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    override val view = R.layout.fragment_semantic_surface

    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private var wins: List<QuickWin> = emptyList()
    private val done = HashSet<String>()
    private var path: List<String> = emptyList()
    private var thinking = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class QuickWin(
        val id: String, val text: String, val uri: String, val sourceDay: LocalDate,
        val committed: Boolean, val degree: Int,
        val reasons: List<String>, val companions: List<String>
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "⚡ Quick Wins"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        view.findViewById<TextView>(R.id.semantic_close)
            .setOnClickListener { findNavController().popBackStack() }
        view.findViewById<TextView>(R.id.semantic_action).apply {
            text = "✧ Path to victory"
            visibility = View.VISIBLE
            setOnClickListener { narratePath() }
        }
        load()
    }

    private fun live(): List<QuickWin> = wins.filter { it.id !in done }

    private fun load() {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(hint("Reading the connections…"))
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { compute(ctx) }.getOrNull() ?: emptyList() }
            if (!isAdded) return@launch
            wins = found
            render()
        }
    }

    private fun render() {
        val ctx = context ?: return
        column.removeAllViews()
        val items = live()
        if (path.isNotEmpty()) column.addView(pathCard(path))
        if (items.isEmpty()) {
            column.addView(hint(
                "Nothing's close enough to finish or connected enough to leverage yet. As you rhizome " +
                "tasks into your syntheses and pickings, the shortest paths to victory surface here."))
            com.toolsboox.ot.ReadingSize.apply(scroll)
            return
        }
        for (w in items) column.addView(winCard(w))
        com.toolsboox.ot.ReadingSize.apply(scroll)
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(12), 0, 0)
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt()); setStroke(dp(1), 0xFFBBBBBB.toInt()); cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(3), dp(4), dp(3), dp(5)) }
    }

    private fun winCard(w: QuickWin): View {
        val ctx = requireContext()
        val box = card()
        // The committed ones — the ones you already started — get a heavier edge.
        if (w.committed) (box.background as? android.graphics.drawable.GradientDrawable)
            ?.setStroke(dp(2), 0xFF000000.toInt())

        box.addView(TextView(ctx).apply {
            text = w.text
            textSize = 17f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.2f); maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (w.reasons.isNotEmpty()) box.addView(TextView(ctx).apply {
            text = w.reasons.joinToString("  ·  ")
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(5), 0, 0)
        })
        if (w.companions.isNotEmpty()) box.addView(TextView(ctx).apply {
            text = "comes with  " + w.companions.take(4).joinToString("  ·  ")
            textSize = 13f; setTextColor(0xFF333333.toInt()); setPadding(0, dp(6), 0, 0)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        }
        actions.addView(actionButton("✓ Done") { markDone(w) })
        actions.addView(actionButton("⁂ Rhizome") { openRhizome(w) })
        actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        actions.addView(actionButton("✋ Grab") { grab(w) })
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

    private fun pathCard(steps: List<String>): View {
        val ctx = requireContext()
        val box = card()
        box.addView(TextView(ctx).apply {
            text = "🏁  Path to victory"
            textSize = 13f; setTextColor(0xFF777777.toInt()); letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(6))
        })
        steps.forEachIndexed { i, s ->
            box.addView(TextView(ctx).apply {
                text = "${i + 1}.  $s"
                textSize = 15f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.2f)
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        box.addView(TextView(ctx).apply {
            text = "Clear"
            textSize = 13f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(6), 0, 0)
            setOnClickListener { path = emptyList(); render() }
        })
        return box
    }

    // MARK: - Actions

    private fun markDone(w: QuickWin) {
        val ctx = context ?: return
        done.add(w.id)
        render()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = documentsRoot()
                val cd = calendarDayService.load(root, w.sourceDay, null, Locale.getDefault())
                val idx = cd.ledgerItems.indexOfFirst { it.id == w.id }
                if (idx >= 0) { cd.ledgerItems[idx].done = true; calendarDayService.save(root, w.sourceDay, cd) }
            }
        }
        showMessage("Done — one off the pile", requireView())
    }

    private fun openRhizome(w: QuickWin) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to w.uri,
                LedgerRhizomeFragment.ARG_LABEL to w.text.take(60)
            )
        )
    }

    private fun grab(w: QuickWin) {
        val ctx = context ?: return
        val today = LocalDate.now()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val card = QuoteCardRenderer.render(w.text.take(600), "⚡ quick win", null, 1080, 0)
                PickingsPlacement.place(
                    calendarDayService, documentsRoot(), card, today, PickingsStore.DEFAULT_KEY,
                    sourceLink = w.uri, sourceLabel = w.text.take(60), cardText = w.text.take(600))
                // Provenance: the task now belongs to today's pickings basket.
                ConnectionStore.connect(
                    ctx, w.uri, LedgerUri.page(today.toString(), PickingsStore.DEFAULT_KEY),
                    kind = Connection.PLACED, fromLabel = w.text.take(60), toLabel = "Pickings · $today")
            }
        }
        showMessage("Grabbed into today's pickings", requireView())
    }

    private fun narratePath() {
        if (thinking) return
        val ctx = context ?: return
        val items = live()
        if (items.isEmpty()) return
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
        if (creds == null) { showMessage("Add an AI key in Settings for a narrated path", requireView()); return }
        thinking = true
        showMessage("Reading your path…", requireView())
        val (provider, key, model) = creds
        val listing = items.mapIndexed { i, w ->
            "${i + 1}. ${w.text}" + (if (w.reasons.isEmpty()) "" else "  [${w.reasons.joinToString(", ")}]")
        }.joinToString("\n")
        val prompt = """
            You are the reader's own Ledger. Below are their quickest wins — tasks ranked by how close
            they are to finishing and how much they unblock (the bracketed notes say which are already
            committed to a synthesis/pickings basket and how many things each connects). Give a SHORT
            ordered path to victory: which to do first and why, in their warm, plain voice, favouring
            the committed and high-leverage ones early so momentum compounds. 3-5 steps. Return ONE step
            per line, each line a plain sentence, no numbering, no preamble.
        """.trimIndent()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.chat.nw.LedgerChatService().run(provider, key, model, prompt, listing)
            }
            thinking = false
            if (!isAdded) return@launch
            when (result) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                    val steps = result.answer.split("\n")
                        .map { it.trim().removePrefix("-").trim().replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim() }
                        .filter { it.isNotBlank() }.take(6)
                    if (steps.isEmpty()) showMessage("Ledger didn't return a path", requireView())
                    else { path = steps; render() }
                }
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err ->
                    showMessage(result.message, requireView())
            }
        }
    }

    // MARK: - Compute

    /** A basket page — finishing something connected to one completes work you already started. */
    private fun isBasket(uri: String): Boolean =
        uri.contains("/synthesize") || uri.contains("/pickings") || uri.contains("/write")

    private fun fileDate(name: String): LocalDate? = runCatching {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    private fun compute(ctx: android.content.Context): List<QuickWin> {
        val root = documentsRoot()
        val calendarRoot = File(root, "calendar")
        if (!calendarRoot.isDirectory) return emptyList()

        // Every undone, non-blank task — with the day it lives on, so Done saves to the right file.
        data class Task(val item: LedgerItem, val day: LocalDate)
        val tasks = ArrayList<Task>()
        calendarRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .forEach { file ->
                val day = fileDate(file.name) ?: return@forEach
                val items = runCatching { calendarDayService.loadLedgerItems(file) }.getOrNull() ?: return@forEach
                for (li in items) {
                    if (li.kind == LedgerItem.Kind.TASK && !li.done && li.text.trim().isNotEmpty())
                        tasks.add(Task(li, day))
                }
            }
        if (tasks.isEmpty()) return emptyList()

        val edges = ConnectionStore.loadAll(ctx).filter { it.deletedAt == 0L }

        // First pass — leverage (graph) + ease (short text). Cheap, no semantics.
        data class Scored(val t: Task, val es: List<Connection>, val committed: Boolean, val score: Double)
        val scored = ArrayList<Scored>()
        for (t in tasks) {
            val uri = LedgerUri.task(t.item.id)
            val es = edges.filter { it.from == uri || it.to == uri }
            val committed = es.any { isBasket(it.from) || isBasket(it.to) }
            val len = t.item.text.length
            val ease = if (len < 60) 1.0 else if (len < 120) 0.5 else 0.0
            val score = (if (committed) 3.0 else 0.0) + minOf(es.size.toDouble(), 5.0) * 0.6 + ease
            if (score >= 0.5) scored.add(Scored(t, es, committed, score))
        }
        val top = scored.sortedByDescending { it.score }.take(8)
        if (top.isEmpty()) return emptyList()

        // Second pass — companions + a rhyme, bounded to the finalists (semantics on <= 8).
        val corpus = corpusService.gather(root, Spiral.SCOPE)
            .filter { Spiral.isSubstantial(it.text) }
            .let { Spiral.dedupe(it) { s -> s.text } }

        return top.map { s ->
            val uri = LedgerUri.task(s.t.item.id)
            val reasons = ArrayList<String>()
            if (s.committed) reasons.add("in a basket")
            if (s.es.isNotEmpty()) reasons.add("connects ${s.es.size}")

            val companions = ArrayList<String>()
            for (e in s.es.take(4)) {
                val other = e.otherEnd(uri) ?: continue
                val label = e.otherLabel(uri).ifBlank { LedgerUri.describe(other) }
                if (label.isNotBlank()) companions.add(label.take(28))
            }
            val rhymes = SemanticRoots.neighbors(ctx, s.t.item.text, corpus, topN = 2, minScore = 0.55)
            for (r in rhymes) companions.add(rhymeTag(r.snippet).take(28))
            rhymes.firstOrNull()?.let { reasons.add("rhymes with ${rhymeTag(it.snippet).take(20)}") }

            QuickWin(
                id = s.t.item.id, text = s.t.item.text, uri = uri, sourceDay = s.t.day,
                committed = s.committed, degree = s.es.size,
                reasons = reasons, companions = companions.distinct().sorted())
        }
    }

    /** A short human tag for a rhymed snippet — its title, else its citation. */
    private fun rhymeTag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    override fun showLoading() {}
    override fun hideLoading() {}
}
