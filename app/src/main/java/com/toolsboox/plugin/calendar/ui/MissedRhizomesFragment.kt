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
import com.toolsboox.ot.HtmlText
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.ot.ConnectionStore
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.PickingsStore
import com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
import com.toolsboox.plugin.calendar.ot.SemanticRoots
import com.toolsboox.plugin.calendar.ot.Spiral
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.plugin.feeds.nw.FeedCache
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * ✧ **Missed Connections** — the Android mirror of the iPad's MissedRhizomesView; a serendipity
 * engine over the feed.
 *
 * The name on screen is "Missed Connections"; the class, the file, the nav id and every stored key
 * still say "rhizome", and that disagreement is deliberate. This surface is wired through the
 * rhizome graph — [com.toolsboox.plugin.calendar.ot.ConnectionStore], the `tag://` and `ledger://`
 * addresses, the corpus gather — and renaming that vocabulary to follow a label would be a wide
 * change with a live wire running through it, for no gain a reader would ever see. So the label
 * moves and the plumbing doesn't. Michael reads "Missed Connections"; the compiler reads
 * MissedRhizomesFragment.
 *
 * It reads the items you walked past (cached, still unread, never starred) and, for each, asks the on-device
 * meaning model how deeply it rhymes with your ROOTS — the surfaced material you've actually engaged
 * with (see [SemanticRoots]). The few that rhyme deepest are connections you'd otherwise have lost:
 * something you skipped that speaks straight to what you're already thinking about. Each can become a
 * picking — a quote card dropped onto today's board next to the root it echoes — so the missed thing
 * rejoins the woven corpus instead of scrolling away.
 */
@AndroidEntryPoint
class MissedRhizomesFragment @Inject constructor() : ScreenFragment() {

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
    private var finds: List<MissedFind> = emptyList()
    private val picked = HashSet<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class MissedFind(
        val id: String, val entryTitle: String, val entryUrl: String, val feedName: String,
        val quote: String, val rootTag: String, val rootText: String, val score: Double,
        // The day the skipped thing was published — what a date filter on this surface means.
        val published: LocalDate?
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "✧ Missed Connections"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        // The strip FILTERS the finds in place — a period tap scopes to what was published then,
        // the carets step it, and nothing about a date ever navigates away from here.
        navBar = SemanticNavBar(this, view.findViewById(R.id.semantic_navigator),
            calendarDayService, calendarPatternService,
            onFilter = { _, _ -> if (isAdded) render() }) { documentsRoot() }
        // The header ☰ and Close retire into the rail — ☰ Hub is the same door, and Close only
        // repeated the system back gesture. No other chrome here: Hub · ⇄ · ✕.
        view.findViewById<android.widget.ImageButton>(R.id.semantic_hub_button).visibility = View.GONE
        view.findViewById<TextView>(R.id.semantic_close).visibility = View.GONE
        setupActionRail(view.findViewById(R.id.semantic_rail), "missed_rhizomes", actions = { emptyList() })
        load()
    }

    private fun load() {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(hint("Listening for what you missed…"))
        // The view's scope: the discovery exists only to fill this column, so back-navigation
        // cancels it instead of ghost-rendering into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { discover(ctx) }.getOrNull() ?: emptyList() }
            if (!isAdded) return@launch
            finds = found
            render()
        }
    }

    private fun render() {
        column.removeAllViews()
        val win = navBar?.window()
        // A find whose feed never said WHEN stays in every window: an unknown date is not a date
        // outside the period, and filtering to a year shouldn't silently disappear the undated.
        val shown = if (win == null) finds else finds.filter {
            it.published == null ||
                (!it.published.isBefore(win.first) && it.published.isBefore(win.second))
        }
        if (shown.isEmpty()) {
            // Two different kinds of nothing. A filtered period with no finds is normal and should
            // say WHICH period came up empty — a bare blank here reads as broken — while a feed
            // that hasn't rhymed with anything yet needs the longer explanation.
            column.addView(hint(
                if (win != null)
                    "No missed connections for ${navBar?.periodLabel()}. The carets step to the next " +
                    "period; tapping the period again brings back everything."
                else
                    "Nothing you skipped in the feed rhymes deeply with your own material yet. Let more feed " +
                    "collect, or add an embeddings key in Settings so the meaning model can compare them."))
            com.toolsboox.ot.ReadingSize.apply(scroll)
            return
        }
        for (f in shown) column.addView(card(f))
        com.toolsboox.ot.ReadingSize.apply(scroll)
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(12), 0, 0)
    }

    private fun card(f: MissedFind): View {
        val ctx = requireContext()
        // The shared semantic-card ground — one drawer for all three surfaces (SemanticCards).
        val box = com.toolsboox.ot.SemanticCards.card(ctx)
        box.addView(TextView(ctx).apply {
            text = "YOU SKIPPED"
            textSize = 11f; setTextColor(0xFF999999.toInt()); letterSpacing = 0.08f
        })
        box.addView(TextView(ctx).apply {
            text = f.entryTitle
            textSize = 17f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.15f)
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        if (f.feedName.isNotBlank()) box.addView(TextView(ctx).apply {
            text = f.feedName
            textSize = 12f; setTextColor(0xFF999999.toInt())
        })
        box.addView(TextView(ctx).apply {
            text = "“${f.quote}”"
            textSize = 15f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.2f)
            setTypeface(typeface, android.graphics.Typeface.ITALIC)
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(8))
        })
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = "⁂  rhymes with your ${f.rootTag}"
                textSize = 12f; setTextColor(0xFF555555.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                text = "${(f.score * 100).toInt()}%"
                textSize = 12f; setTextColor(0xFF777777.toInt())
            })
        })
        if (f.rootText.isNotBlank()) box.addView(TextView(ctx).apply {
            text = f.rootText
            textSize = 13f; setTextColor(0xFF777777.toInt()); setLineSpacing(0f, 1.15f)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), 0, 0)
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        }
        actions.addView(actionButton("⁂ Rhizome") { openRhizome(f) })
        actions.addView(actionButton("📖 Read") { openEntry(f) })
        actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        actions.addView(actionButton(if (f.id in picked) "✓ Picked" else "⚗ Make picking") { makePicking(f) })
        box.addView(actions)
        // Hold the card for the fuller menu: pick it as a gram in your medium of choice, or
        // throw it out of the corpus for good.
        box.setOnLongClickListener { holdMenu(f); true }
        return box
    }

    /** The hold menu — the card's verbs plus the two that don't fit on a chip strip: the pick
     *  that asks its medium, and the permanent removal. */
    private fun holdMenu(f: MissedFind) {
        showIconMenu(f.entryTitle.take(80), listOf(
            "⁂  Pick — make it a gram" to { pickAsGram(f) },
            "⚗  Make picking" to { makePicking(f) },
            "⁂  Rhizome" to { openRhizome(f) },
            "📖  Read" to { openEntry(f) },
            "🗑  Remove from corpus" to { removeFromCorpus(f) }
        ))
    }

    /** The pick, the way it works everywhere now: the skipped passage becomes the quote in hand
     *  and [com.toolsboox.plugin.feeds.ot.FeedNoteGram] asks which medium carries your note —
     *  handwriting, text, audio or video — before the card lands on today's Notes page. Saving
     *  with nothing added still makes the plain quote gram; the pick itself is enough. */
    private fun pickAsGram(f: MissedFind) {
        com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
            this, calendarDayService, documentsRoot(),
            itemText = f.quote.take(600),
            originLabel = listOf(f.entryTitle, f.feedName).filter { it.isNotBlank() }
                .joinToString(" · ").take(80),
            sourceUrl = f.entryUrl
        ) { kind, sink -> captureAvGramDirect(kind, sink) }
    }

    /** Stronger than skipping past it again: tombstone the entry so no future discovery run can
     *  re-offer it. The softer dismissals stay where they were; this is for actual junk. */
    private fun removeFromCorpus(f: MissedFind) {
        lifecycleScope.launch(Dispatchers.IO) { runCatching { corpusService.exclude(f.entryUrl) } }
        finds = finds.filterNot { it.id == f.id }
        render()
        showMessage("Removed — it won't be offered again", requireView())
    }

    // The shared chip: solid 1dp black stroke so the verb reads on e-ink (SemanticCards).
    private fun actionButton(label: String, onClick: () -> Unit) =
        com.toolsboox.ot.SemanticCards.actionChip(requireContext(), label, onClick)

    // MARK: - Actions

    private fun openRhizome(f: MissedFind) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to f.entryUrl,
                LedgerRhizomeFragment.ARG_LABEL to f.entryTitle.take(60)
            )
        )
    }

    /** Open the skipped article. The in-app reader is the ideal home (TODO: route via FeedSelection);
     *  for now open the source so the missed thing can still be read. */
    private fun openEntry(f: MissedFind) {
        runCatching {
            startActivity(android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(f.entryUrl)))
        }.onFailure { showMessage("Couldn't open that article", requireView()) }
    }

    private fun makePicking(f: MissedFind) {
        if (f.id in picked) return
        val ctx = context ?: return
        val today = LocalDate.now()
        picked.add(f.id)
        render()
        // One-tap verb on a list row — no chooser, the shared gram memory routes the card (Gram
        // Picks when the remembered place no longer exists today) and the message names the landing.
        val dest = com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(ctx, today)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val card = QuoteCardRenderer.render(
                    f.quote.take(600), f.entryTitle.take(80), "✧ rhymes with your ${f.rootTag}".take(80),
                    1080, 0)
                PickingsPlacement.place(
                    calendarDayService, documentsRoot(), card, today, dest.key,
                    sourceLink = f.entryUrl, sourceLabel = f.entryTitle.take(60), cardText = f.quote.take(600),
                    intakeKind = dest.kind)
                // Provenance: the skipped source now belongs to the page the card landed on.
                if (f.entryUrl.isNotBlank()) ConnectionStore.connect(
                    ctx, f.entryUrl, LedgerUri.page(today.toString(), dest.key),
                    kind = Connection.SOURCE, fromLabel = f.entryTitle.take(60), toLabel = "${dest.name} · $today")
            }
        }
        showMessage("Picking added to ${dest.name}", requireView())
    }

    // MARK: - Discovery

    private fun tag(s: CorpusSnippet): String = s.title.ifBlank { s.citation }

    private fun discover(ctx: Context, minScore: Double = 0.60, topN: Int = 30): List<MissedFind> {
        // What you genuinely walked past: cached, still UNREAD, never starred — deduped across views.
        // The Miniflux read flag now rides along in the offline cache ([FeedCache] persists `read`), so
        // "skipped" is true unread here, not merely "unstarred"; the session read-tracker
        // ([FeedReadState]) folds in anything opened this run. Read or starred = you engaged with it.
        val cacheDir = File(ctx.filesDir, "feed-cache")
        val lists = cacheDir.listFiles { f -> f.isFile && f.name.startsWith("list-") && f.name.endsWith(".json") }
            ?: return emptyList()
        val seen = HashSet<String>()
        data class Entry(
            val title: String, val url: String, val feed: String, val body: String,
            val published: LocalDate?
        )
        // The cache stamps entries with whatever the feed said: Miniflux hands over RFC 3339,
        // Later rows a bare date — but a LOCAL subscription passes the raw RSS pubDate through
        // untouched, which is usually RFC 1123 ("Wed, 23 Jul 2026 …"). That last shape parsed to
        // null here, and a null date vanished under every filter — the whole of "no missed
        // rhizomes in 2026". So: try each shape the cache actually holds, then the first ten
        // characters as a date of last resort.
        fun published(raw: String): LocalDate? =
            runCatching {
                java.time.OffsetDateTime.parse(raw)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
            }.getOrNull()
                ?: runCatching {
                    java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
                }.getOrNull()
                ?: runCatching { LocalDate.parse(raw) }.getOrNull()
                ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        val unsurfaced = ArrayList<Entry>()
        for (lf in lists) {
            val key = lf.name.removePrefix("list-").removeSuffix(".json")
            for (e in FeedCache.loadEntries(ctx, key)) {
                val engaged = e.starred || e.read ||
                    com.toolsboox.plugin.feeds.ui.FeedReadState.isRead(e.id)
                if (engaged || e.url.isBlank() || !seen.add(e.url)) continue
                // Thrown out by hand ("Remove from corpus") — tombstoned, never re-offered.
                if (corpusService.isExcluded(e.url)) continue
                val body = strip(e.content)
                unsurfaced.add(Entry(e.title, e.url, e.feedTitle, body, published(e.publishedAt)))
            }
        }
        if (unsurfaced.isEmpty()) return emptyList()
        val entries = unsurfaced.take(150)

        // Everything you've kept: the roots corpus (books/feeds/planner/annotations engaged with).
        val roots = corpusService.gather(documentsRoot(), Spiral.SCOPE)
            .filter { Spiral.isSubstantial(it.text) }
            .let { Spiral.dedupe(it) { s -> s.text } }
            .takeLast(300)
        if (roots.isEmpty()) return emptyList()

        // One round of embedding for both sides, then compare in memory.
        fun entryText(e: Entry) = (e.title + ". " + e.body).trim()
        val vecMap = SemanticRoots.vectorsFor(ctx, entries.map { entryText(it) } + roots.map { it.text })
        if (vecMap.isEmpty()) return emptyList()
        val rootVecs = roots.mapNotNull { s -> vecMap[s.text]?.let { s to it } }
        if (rootVecs.isEmpty()) return emptyList()

        val out = ArrayList<MissedFind>()
        for (e in entries) {
            if (e.body.length <= 40) continue
            val ev = vecMap[entryText(e)] ?: continue
            var best: Pair<CorpusSnippet, Double>? = null
            for ((s, sv) in rootVecs) {
                val c = SemanticRoots.cosine(ev, sv)
                if (c > (best?.second ?: -1.0)) best = s to c
            }
            val b = best ?: continue
            if (b.second < minScore || b.second >= 0.97) continue
            out.add(MissedFind(
                id = e.url, entryTitle = e.title, entryUrl = e.url, feedName = e.feed,
                quote = bestSentence(e.body, b.first.text) ?: e.body.take(220),
                rootTag = tag(b.first), rootText = b.first.text.take(200), score = b.second,
                published = e.published))
        }
        return out.sortedByDescending { it.score }.take(topN)
    }

    /** The entry sentence that shares the most vocabulary with the matched root — the line to quote.
     *  (The iPad picks this by embedding each sentence; word-overlap is the bounded stand-in here.) */
    private fun bestSentence(body: String, rootText: String): String? {
        val rootWords = tokenize(rootText)
        if (rootWords.isEmpty()) return null
        val sentences = body.split(Regex("[.!?]"))
            .map { it.trim() }.filter { it.length >= 30 }.take(12)
        var best: Pair<String, Int>? = null
        for (s in sentences) {
            val overlap = tokenize(s).count { it in rootWords }
            if (overlap > (best?.second ?: 0)) best = s.take(240) to overlap
        }
        return best?.first
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.toSet()

    /** Lightweight HTML→text — scripts/styles out, tags out, a handful of entities, whitespace tidy. */
    private fun strip(html: String): String = HtmlText.toPlain(html)

    override fun showLoading() {}
    override fun hideLoading() {}
}
