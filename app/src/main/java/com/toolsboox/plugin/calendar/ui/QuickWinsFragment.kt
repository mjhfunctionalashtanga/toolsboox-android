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
import com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.PickingsStore
import com.toolsboox.plugin.calendar.ot.QuickWinsEngine
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

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    override val view = R.layout.fragment_semantic_surface

    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private var navBar: SemanticNavBar? = null
    private var wins: List<QuickWinsEngine.Win> = emptyList()
    private val done = HashSet<String>()
    private var path: List<String> = emptyList()
    private var thinking = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // The QuickWin shape and its compute moved to QuickWinsEngine — the day page's panel reads
    // the same wins now, and two notions of "quickest to finish" would have drifted apart.

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "⚡ Quick Wins"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        navBar = SemanticNavBar(this, view.findViewById(R.id.semantic_navigator),
            calendarDayService, calendarPatternService) { documentsRoot() }
        // The ▦ hub, top-left as everywhere — the shared layout grew it for all three surfaces,
        // and an unwired button here would be a dead door.
        view.findViewById<android.widget.ImageButton>(R.id.semantic_hub_button)
            .setOnClickListener { showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this)) }
        view.findViewById<TextView>(R.id.semantic_close)
            .setOnClickListener { findNavController().popBackStack() }
        view.findViewById<TextView>(R.id.semantic_action).apply {
            text = "✧ Path to victory"
            visibility = View.VISIBLE
            setOnClickListener { narratePath() }
        }
        load()
    }

    private fun live(): List<QuickWinsEngine.Win> = wins.filter { it.id !in done }

    private fun load() {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(hint("Reading the connections…"))
        // The view's scope: the compute exists only to fill this column, so back-navigation
        // cancels it instead of ghost-rendering into a dead view. `fresh` shares the parked
        // answer with the day page's panel — whoever asks first pays; the other reads.
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    QuickWinsEngine.fresh(ctx, corpusService, calendarDayService, documentsRoot(), LocalDate.now())
                }.getOrNull() ?: emptyList()
            }
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

    // The shared semantic-card ground — one drawer for all three surfaces (SemanticCards).
    private fun card(): LinearLayout = com.toolsboox.ot.SemanticCards.card(requireContext())

    private fun winCard(w: QuickWinsEngine.Win): View {
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

    // The shared chip: solid 1dp black stroke so the verb reads on e-ink (SemanticCards).
    private fun actionButton(label: String, onClick: () -> Unit) =
        com.toolsboox.ot.SemanticCards.actionChip(requireContext(), label, onClick)

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

    private fun markDone(w: QuickWinsEngine.Win) {
        val ctx = context ?: return
        done.add(w.id)
        render()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = documentsRoot()
                val cd = calendarDayService.load(root, w.sourceDay, null, Locale.getDefault())
                // Same-day twins (a typed copy and a reading-log copy of one task) check off
                // together, or the unchecked twin resurfaces as a fresh win on the next load.
                val key = LedgerTaskDedupe.key(w.text)
                var changed = false
                for (li in cd.ledgerItems) {
                    if (li.kind == LedgerItem.Kind.TASK && !li.done &&
                        (li.id == w.id || (key.isNotEmpty() && LedgerTaskDedupe.key(li.text) == key))) {
                        li.done = true; changed = true
                    }
                }
                if (changed) calendarDayService.save(root, w.sourceDay, cd)
            }
        }
        showMessage("Done — one off the pile", requireView())
    }

    private fun openRhizome(w: QuickWinsEngine.Win) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to w.uri,
                LedgerRhizomeFragment.ARG_LABEL to w.text.take(60)
            )
        )
    }

    private fun grab(w: QuickWinsEngine.Win) {
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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    com.toolsboox.plugin.chat.nw.LedgerChatService().run(provider, key, model, prompt, listing)
                }
            } finally {
                // Also on cancellation — a wedged flag here would refuse every future narration.
                thinking = false
            }
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

    override fun showLoading() {}
    override fun hideLoading() {}
}
