package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.ot.LedgerTags
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * 🌰 **Seeds** — the Garden bed where an idea lives while it's still just an idea.
 *
 * A note or a jot becomes a seed the moment you give it a `#hashtag`: a new topic you're beginning
 * to think about, or an old one you're picking back up. Seeds is where those incubating ideas are
 * surfaced before they've grown roots — so the two that stand out are the **new** ones (a `#tag`
 * created in the last couple of weeks, whose roots network you're only starting to discover) and the
 * **dormant** ones (an old `#tag` you haven't touched in a while, worth watering again). The rest are
 * **growing** — already recurring across your pages, on their way to sprouting.
 *
 * Every seed carries the date it was created (see [LedgerTags.TagInfo.created]) and, on demand, a
 * short Wikipedia-style **provenance** — an encyclopedic overview of the topic, generated once by the
 * Ledger's LLM and cached in [SeedProvenanceStore] so it teaches you the subject without regenerating
 * each open. (This is where the old "Educate me" role settles: the provenance IS the lesson.) Tapping
 * a seed opens its detail — the pages it appears on to jump to, and its provenance.
 *
 * Modelled on the sibling Garden surfaces (Sprouts, Quick Wins): the shared semantic frame, the
 * Almanac strip as a place to leave from, a scrolling column of cards read at [com.toolsboox.ot.ReadingSize].
 *
 * Lifecycle: **Seed → (roots form) → Sprout.** New/dormant surfacing is automatic from the tags'
 * created-dates and occurrences; an explicit "mark as seed" (long-press) pins one regardless, so an
 * idea you want to keep incubating stays in the bed even once it's recurring.
 */
@AndroidEntryPoint
class SeedsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    @Inject
    lateinit var chatService: com.toolsboox.plugin.chat.nw.LedgerChatService

    override val view = R.layout.fragment_semantic_surface

    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private var navBar: SemanticNavBar? = null
    private var seeds: List<Seed> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** When a `#tag` is new enough to be an idea still finding its roots. */
    private val NEW_DAYS = 14L

    /** When a `#tag`'s created-date AND last sighting are both old enough that it's gone quiet. */
    private val DORMANT_DAYS = 30L

    // SPROUTED leads: a seed whose roots network has formed (see the promotion threshold in the
    // companion) has graduated, and the graduation is the top of the bed. The rest follow as before.
    private enum class Bed { SPROUTED, SEEDED, NEW, DORMANT, GROWING }

    /** What one pass over the tags produced: the beds, plus any seed that graduated on THIS pass —
     *  carried out so the once-only "took root → Sprout" notice fires exactly when it happens. */
    private data class GatherResult(val seeds: List<Seed>, val promoted: List<String>)

    private data class Seed(
        val tag: String,
        val created: Long,
        val occurrences: List<Triple<LocalDate, String, RectF?>>,
        val bed: Bed,
        val lastSeen: LocalDate?
    ) {
        val count: Int get() = occurrences.size
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "🌰 Seeds"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        // Leave-from mode, like Quick Wins: seeds are the whole tag bed at once, not one day — the
        // strip is a place to step out to a day, never a filter that could empty the garden.
        navBar = SemanticNavBar(this, view.findViewById(R.id.semantic_navigator),
            calendarDayService, calendarPatternService) { documentsRoot() }
        // The header ☰ and Close retire into the rail — ☰ Hub is the same door, and Close only
        // repeated the system back gesture. No other chrome here: Hub · ⇄ · ✕.
        view.findViewById<android.widget.ImageButton>(R.id.semantic_hub_button).visibility = View.GONE
        view.findViewById<TextView>(R.id.semantic_close).visibility = View.GONE
        setupActionRail(view.findViewById(R.id.semantic_rail), "seeds", actions = { emptyList() })
        load()
    }

    private fun load() {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(hint("Turning the soil…"))
        // The view's scope: the read exists only to fill this column, so back-navigation cancels it
        // rather than ghost-rendering into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { gather(ctx) }.getOrNull() ?: GatherResult(emptyList(), emptyList())
            }
            if (!isAdded) return@launch
            seeds = result.seeds
            render()
            // The graduation is recorded once (SeedPromotionStore), so a promoted seed never
            // promotes again — this notice therefore fires exactly once per seed that takes root.
            if (result.promoted.isNotEmpty()) announcePromotions(result.promoted)
        }
    }

    /** The quiet, once-only graduation notice: "#ashtanga has taken root → Sprout". */
    private fun announcePromotions(tags: List<String>) {
        if (!isAdded) return
        val msg = if (tags.size == 1)
            "🌱 #${tags.first()} has taken root → Sprout"
        else
            "🌱 ${tags.take(3).joinToString(", ") { "#$it" }} took root → Sprouts"
        showMessage(msg, requireView())
    }

    /**
     * Read every `#tag`, date it, sort it into a bed by freshness — and close the lifecycle loop:
     * evaluate each seed against the roots-network [promotion threshold][ROOT_MIN_PAGES] and graduate
     * the newly-qualifying ones to a Sprout ([SeedPromotionStore], recorded once). A promoted seed
     * sits in the SPROUTED bed and is never re-promoted; a graduation on this pass is carried out so
     * the notice fires once.
     */
    private fun gather(ctx: Context): GatherResult {
        val now = System.currentTimeMillis()
        val marked = SeedMarkStore.all(ctx)
        val promoted = SeedPromotionStore.all(ctx)
        // Co-occurrence heft in ONE pass over the whole ledger (not relatedTags() per tag, which is
        // O(tags²)): how many distinct OTHER tags each tag shares a page with. Each unordered pair is
        // counted once, so a tag's tally is its number of distinct co-occurring tags — its rhizome web.
        val relatedCounts = HashMap<String, Int>()
        for ((a, b, _) in LedgerTags.coOccurrences(ctx)) {
            relatedCounts[a] = (relatedCounts[a] ?: 0) + 1
            relatedCounts[b] = (relatedCounts[b] ?: 0) + 1
        }
        val newlyPromoted = mutableListOf<String>()
        val seeds = LedgerTags.list(ctx).map { info ->
            val createdDaysAgo = if (info.created > 0L) (now - info.created) / 86_400_000L else Long.MAX_VALUE
            val lastSeen = info.occurrences.maxByOrNull { it.first }?.first
            val seenDaysAgo = lastSeen?.let { java.time.temporal.ChronoUnit.DAYS.between(it, LocalDate.now()) } ?: Long.MAX_VALUE

            // Roots-network measures. distinctPages is the tag's graph degree: every occurrence page
            // becomes a tag↔page edge in the rhizome (LedgerTags.putOccurrence → ConnectionStore),
            // so this equals ConnectionStore.neighbours(tagUri).size — read here for free off the
            // occurrences already in hand rather than re-walking the edge graph per tag.
            val distinctPages = info.occurrences.map { "${it.first}|${it.second}" }.toSet().size
            val distinctDays = info.occurrences.map { it.first }.toSet().size
            val related = relatedCounts[info.tag] ?: 0
            val rooted = distinctPages >= ROOT_MIN_PAGES &&
                related >= ROOT_MIN_RELATED &&
                distinctDays >= ROOT_MIN_DAYS

            val alreadyPromoted = promoted.contains(info.tag)
            if (!alreadyPromoted && rooted && SeedPromotionStore.promote(ctx, info.tag)) {
                newlyPromoted.add(info.tag)
            }
            val bed = when {
                // Graduation is terminal and wins over every other bed — a rooted idea has sprouted.
                alreadyPromoted || rooted -> Bed.SPROUTED
                marked.contains(info.tag) -> Bed.SEEDED
                info.created > 0L && createdDaysAgo <= NEW_DAYS -> Bed.NEW
                createdDaysAgo >= DORMANT_DAYS && seenDaysAgo >= DORMANT_DAYS -> Bed.DORMANT
                else -> Bed.GROWING
            }
            Seed(info.tag, info.created, info.occurrences, bed, lastSeen)
        }.sortedWith(
            // Sprouted first, then new (newest created leading), then the most-dormant, then growing by heft.
            compareBy<Seed> { it.bed.ordinal }
                .thenByDescending { if (it.bed == Bed.NEW) it.created else 0L }
                .thenBy { if (it.bed == Bed.DORMANT) (it.lastSeen ?: LocalDate.MIN) else LocalDate.MAX }
                .thenByDescending { it.count }
        )
        return GatherResult(seeds, newlyPromoted)
    }

    private fun render() {
        column.removeAllViews()
        if (seeds.isEmpty()) {
            column.addView(hint(
                "No seeds yet. Write #something on a note or a jot — the moment a page's ink is " +
                "captured, the tag is planted here with the date you first used it. A brand-new " +
                "hashtag, or one you haven't touched in a while, is a seed worth watching."))
            com.toolsboox.ot.ReadingSize.apply(scroll)
            return
        }
        val byBed = seeds.groupBy { it.bed }
        section(Bed.SPROUTED, "🌱 Sprouted", "took root — graduated to Sprouts", byBed)
        // Link straight to the harvest surface where these now live and can be acted on.
        if (byBed[Bed.SPROUTED].orEmpty().isNotEmpty())
            column.addView(linkText("→  View in Sprouts") { findNavController().navigate(R.id.action_to_sprouts) })
        section(Bed.SEEDED, "🌰 Seeded", "ideas you've marked to keep incubating", byBed)
        section(Bed.NEW, "🌱 New seeds", "planted in the last two weeks — roots still forming", byBed)
        section(Bed.DORMANT, "🍂 Dormant", "old ideas gone quiet — worth watering again", byBed)
        section(Bed.GROWING, "🌿 Growing", "already recurring, on their way to sprouting", byBed)
        com.toolsboox.ot.ReadingSize.apply(scroll)
    }

    private fun section(bed: Bed, title: String, blurb: String, byBed: Map<Bed, List<Seed>>) {
        val rows = byBed[bed].orEmpty()
        if (rows.isEmpty()) return
        column.addView(header(title, blurb))
        for (s in rows) column.addView(card(s))
    }

    private fun header(title: String, blurb: String) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(6))
        addView(TextView(context).apply {
            text = title
            textSize = 13f; setTextColor(0xFF555555.toInt()); letterSpacing = 0.06f
        })
        addView(TextView(context).apply {
            text = blurb
            textSize = 12f; setTextColor(0xFF999999.toInt()); setPadding(0, dp(1), 0, 0)
        })
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(12), 0, 0)
    }

    private fun card(s: Seed): View {
        val ctx = requireContext()
        val box = com.toolsboox.ot.SemanticCards.card(ctx)
        box.addView(TextView(ctx).apply {
            text = "#${s.tag}"
            textSize = 19f; setTextColor(0xFF000000.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        box.addView(TextView(ctx).apply {
            text = subtitle(s)
            textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(3), 0, 0)
        })
        box.setOnClickListener { openSeed(s) }
        box.setOnLongClickListener { holdMenu(s); true }
        return box
    }

    /** The provenance line: created-date, freshness and heft — the facts that make it a seed. */
    private fun subtitle(s: Seed): String {
        val created = if (s.created > 0L)
            "planted " + DATE_FMT.format(java.time.Instant.ofEpochMilli(s.created).atZone(ZoneId.systemDefault()).toLocalDate())
        else "planted (undated)"
        val freshness = when (s.bed) {
            Bed.SPROUTED -> "🌱 sprouted"
            Bed.NEW -> "new"
            Bed.DORMANT -> s.lastSeen?.let { "last seen " + DATE_FMT.format(it) } ?: "dormant"
            Bed.SEEDED -> "seeded"
            Bed.GROWING -> "growing"
        }
        val heft = "${s.count}×"
        val prov = if (SeedProvenanceStore.has(requireContext(), s.tag)) " · 📖 provenance" else ""
        return "$created · $freshness · $heft$prov"
    }

    private fun holdMenu(s: Seed) {
        val ctx = context ?: return
        val isSeeded = SeedMarkStore.all(ctx).contains(s.tag)
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "📖  Open provenance" to { openSeed(s) },
            (if (isSeeded) "🌿  Unmark as seed" else "🌰  Mark as seed") to {
                SeedMarkStore.toggle(ctx, s.tag)
                load()
            }
        )
        // A graduated seed offers the jump to where it now lives.
        if (s.bed == Bed.SPROUTED)
            items.add(0, "🌱  View in Sprouts" to { findNavController().navigate(R.id.action_to_sprouts) })
        showIconMenu("🌰  #${s.tag}", items)
    }

    // MARK: - Seed detail (pages + provenance)

    /**
     * A seed's detail: the pages it lives on (tap to jump), and its provenance — cached if it's been
     * generated, or a one-tap generate. The content column is rebuilt in place when provenance
     * arrives, so the dialog updates without being dismissed and reopened.
     */
    private fun openSeed(s: Seed) {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        val scrollView = ScrollView(ctx).apply { addView(content) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("🌰  #${s.tag}")
            .setView(scrollView)
            .setNegativeButton(getString(R.string.roots_close), null)
            .create()
        rebuildDetail(content, s, dialog)
        com.toolsboox.ot.ReadingSize.apply(scrollView)
        dialog.show()
    }

    private var generating = false

    private fun rebuildDetail(content: LinearLayout, s: Seed, dialog: androidx.appcompat.app.AlertDialog) {
        val ctx = content.context
        content.removeAllViews()

        // Provenance first — the Wikipedia-style overview that teaches the topic.
        content.addView(TextView(ctx).apply {
            text = "Provenance"
            textSize = 12f; setTextColor(0xFF777777.toInt()); letterSpacing = 0.06f
        })
        val cached = SeedProvenanceStore.get(ctx, s.tag)
        when {
            cached != null -> {
                content.addView(TextView(ctx).apply {
                    text = cached
                    textSize = 16f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.25f)
                    setPadding(0, dp(4), 0, dp(6))
                })
                content.addView(linkText("↻  Regenerate provenance") { generateProvenance(content, s, dialog) })
            }
            generating -> content.addView(TextView(ctx).apply {
                text = "Growing the provenance…"
                textSize = 14f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(4), 0, dp(6))
            })
            com.toolsboox.plugin.chat.nw.AiCreds.get(ctx) != null ->
                content.addView(linkText("✨  Generate provenance") { generateProvenance(content, s, dialog) })
            else -> content.addView(TextView(ctx).apply {
                text = "Add an AI key in Settings to grow a short encyclopedic overview of #${s.tag}."
                textSize = 14f; setTextColor(0xFF888888.toInt()); setLineSpacing(0f, 1.15f)
                setPadding(0, dp(4), 0, dp(6))
            })
        }

        // Then the pages — where the seed already lives, newest first, tap to jump.
        content.addView(TextView(ctx).apply {
            text = "Where it lives"
            textSize = 12f; setTextColor(0xFF777777.toInt()); letterSpacing = 0.06f
            setPadding(0, dp(14), 0, dp(2))
        })
        val occ = s.occurrences.sortedByDescending { it.first }
        for ((date, page, rect) in occ) {
            content.addView(linkText("→  ${if (rect != null) "✎  " else ""}$date  ·  $page") {
                dialog.dismiss()
                // Land on the tag's mark (the capture zone) when the occurrence recorded one.
                CalendarNavigator.toDayNote(this, date, page, rect)
            })
        }
    }

    private fun linkText(label: String, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = label
        textSize = 16f; setTextColor(0xFF000000.toInt())
        setPadding(0, dp(8), 0, dp(8))
        background = com.toolsboox.ot.SemanticCards.cardBackground(context)
        val pad = dp(10)
        setPadding(pad, dp(8), pad, dp(8))
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)).also {
            it.topMargin = dp(4); layoutParams = it
        }
        setOnClickListener { onClick() }
    }

    /**
     * Grow the seed's provenance: one LLM call for a concise encyclopedic overview of the topic,
     * cached in [SeedProvenanceStore] keyed by tag so it isn't regenerated on the next open. The
     * detail rebuilds in place — the "generating…" line, then the answer — without closing the dialog.
     */
    private fun generateProvenance(content: LinearLayout, s: Seed, dialog: androidx.appcompat.app.AlertDialog) {
        val ctx = context ?: return
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
        if (creds == null) {
            showMessage("Add an AI key in Settings to generate provenance", requireView()); return
        }
        if (generating) return
        generating = true
        rebuildDetail(content, s, dialog)
        val (provider, key, model) = creds
        val prompt = """
            The reader keeps a handwritten planner and tags their notes with #hashtags. One of those
            tags is a topic they are thinking about. Write a concise, Wikipedia-style overview of the
            topic below — what it is, where it comes from, and why it matters — in 2 to 4 plain
            sentences of encyclopedic prose. No markdown, no headings, no preamble, no first person.
            If the topic is ambiguous, take its most common, general meaning.
        """.trimIndent()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { chatService.run(provider, key, model, prompt, "Topic: #${s.tag}") }
            } finally {
                generating = false
            }
            if (!isAdded) return@launch
            when (result) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> {
                    val text = result.answer.trim()
                    if (text.isBlank()) {
                        showMessage("The Ledger returned nothing to plant", requireView())
                    } else {
                        withContext(Dispatchers.IO) { SeedProvenanceStore.put(ctx, s.tag, text) }
                    }
                    if (dialog.isShowing) rebuildDetail(content, s, dialog)
                    render()   // the card's subtitle now shows the 📖 provenance marker
                }
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err -> {
                    showMessage(result.message, requireView())
                    if (dialog.isShowing) rebuildDetail(content, s, dialog)
                }
            }
        }
    }

    override fun showLoading() {}
    override fun hideLoading() {}

    companion object {
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

        // MARK: - Promotion threshold (Seed → Sprout)
        //
        // A seed graduates to a Sprout when its ROOTS NETWORK has formed — not a brand-new one-off
        // tag, but one woven into the graph. The rule is a composite AND of three cheap, tunable
        // signals read from the tag's occurrences (the same data the rhizome is built from):
        //
        //   1. touches ≥ ROOT_MIN_PAGES distinct pages/objects — its graph degree. Every occurrence
        //      page is a tag↔page edge (LedgerTags.putOccurrence → ConnectionStore), so distinct
        //      pages == ConnectionStore.neighbours(tagUri(tag)).size.
        //   2. co-occurs with ≥ ROOT_MIN_RELATED other tags — the co-occurrence heft from
        //      LedgerTags.coOccurrences / relatedTags: the tag is entangled with the wider web.
        //   3. recurred across ≥ ROOT_MIN_DAYS distinct days — it came back over time, not all at once.
        //
        // All three must hold. A tag on five pages, crossing two other tags, over three days has
        // rooted; a tag scribbled five times on one afternoon page has not. Tune these to move the
        // graduation line.
        private const val ROOT_MIN_PAGES = 5
        private const val ROOT_MIN_RELATED = 2
        private const val ROOT_MIN_DAYS = 3
    }
}

/**
 * A seed's provenance — the short Wikipedia-style overview generated on demand — cached per tag so it
 * teaches the topic without regenerating each open. A plain local SharedPreferences map, the same
 * shape as [LedgerTags]' occurrence store; keyed by the case-folded tag, with the generation time
 * kept alongside so a future "regenerate if stale" can lean on it.
 */
object SeedProvenanceStore {
    private const val PREFS = "seed_provenance"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(tag: String) = "prov:${tag.lowercase()}"

    fun get(context: Context, tag: String): String? =
        prefs(context).getString(key(tag), null)?.takeIf { it.isNotBlank() }

    fun has(context: Context, tag: String): Boolean = get(context, tag) != null

    fun put(context: Context, tag: String, text: String) {
        prefs(context).edit()
            .putString(key(tag), text)
            .putLong("${key(tag)}:at", System.currentTimeMillis())
            .apply()
    }

    /** When the provenance was generated (epoch millis), or 0 if never. */
    fun generatedAt(context: Context, tag: String): Long = prefs(context).getLong("${key(tag)}:at", 0L)
}

/**
 * Ideas explicitly **marked** as seeds — pinned into the bed regardless of freshness, so a topic you
 * want to keep incubating stays surfaced even once it's recurring. A minimal StringSet of case-folded
 * tags; the automatic new/dormant surfacing is the core, this is the deliberate override.
 */
object SeedMarkStore {
    private const val PREFS = "seed_marks"
    private const val KEY = "seeded_tags"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    fun toggle(context: Context, tag: String) {
        val t = tag.lowercase()
        val cur = all(context).toMutableSet()
        if (!cur.add(t)) cur.remove(t)
        prefs(context).edit().putStringSet(KEY, cur).apply()
    }
}

/**
 * Seeds that have **graduated to a Sprout** — recorded once, so the promotion happens a single time
 * and a promoted seed shows as Sprouted rather than being re-promoted (and its "took root → Sprout"
 * notice therefore fires exactly once). The endpoint of the Garden lifecycle: Seed → (roots form) →
 * Sprout. A minimal StringSet of case-folded tags — the same idiom as [SeedMarkStore] — with each
 * tag's promotion time kept alongside so a future surface can order or age the graduations.
 */
object SeedPromotionStore {
    private const val PREFS = "seed_promotions"
    private const val KEY = "promoted_tags"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    fun isPromoted(context: Context, tag: String): Boolean = all(context).contains(tag.lowercase())

    /** When the seed graduated (epoch millis), or 0 if it never has. */
    fun promotedAt(context: Context, tag: String): Long = prefs(context).getLong("at:${tag.lowercase()}", 0L)

    /**
     * Record a graduation. Idempotent and once-only: returns true only the FIRST time a tag is
     * promoted (and stamps the time), false on every call thereafter — so the caller can fire the
     * one-time notice precisely when a seed newly takes root.
     */
    fun promote(context: Context, tag: String): Boolean {
        val t = tag.lowercase()
        val cur = all(context).toMutableSet()
        if (!cur.add(t)) return false
        prefs(context).edit()
            .putStringSet(KEY, cur)
            .putLong("at:$t", System.currentTimeMillis())
            .apply()
        return true
    }
}
