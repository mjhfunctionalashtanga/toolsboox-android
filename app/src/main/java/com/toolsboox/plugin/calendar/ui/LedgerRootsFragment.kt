package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.databinding.FragmentLedgerRootsBinding
import com.toolsboox.plugin.calendar.ot.Rhizome
import com.toolsboox.plugin.calendar.ot.Spiral
import com.toolsboox.plugin.chat.da.CorpusSnippet
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The roots: what you keep coming back to, and where two of them touch.
 *
 * A **thread** is a word that recurs across things you made at different times — a deliberately
 * modest claim, because it is one you can check by eye. The **crossings** are the interesting
 * part: an object sitting in two threads at once is where two preoccupations meet, and nothing
 * about it looks special in a list, so it is exactly what scrolling never finds.
 *
 * This was an AlertDialog over the tasks list. A dialog is the wrong container for something
 * meant to be read and wandered around in — Back dismisses it rather than stepping through it, it
 * scrolls badly on e-ink, and it dies with whatever is underneath. A page can be arrived at.
 */
@AndroidEntryPoint
class LedgerRootsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    override val view = R.layout.fragment_ledger_roots

    private lateinit var binding: FragmentLedgerRootsBinding
    private var navBar: CalendarNavBarHost? = null
    private var anchor: java.time.LocalDate = java.time.LocalDate.now()

    /** Held so a thread row can list its members without re-reading the whole ledger. */
    private var corpus: List<CorpusSnippet> = emptyList()

    /** Held with [corpus] so muting and dismissing re-render from memory instead of re-reading. */
    private var threads: List<Rhizome.Thread> = emptyList()

    /** Terms unmuted on THIS page, this session. An unmuted thread might not win a seat on
     *  today's board, and a card that vanishes under the finger that just brought it back
     *  reads as deletion — so these keep their tile until the page is left. */
    private val unmutedThisSession = mutableSetOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerRootsBinding.bind(view)
        binding.rootsClose.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.gotoButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }

        // The almanac strip, as on Write and Synthesize. The roots are the whole ledger at once,
        // not one day — so here the date is a place to LEAVE from: step to a day and open it, or
        // tap a period to jump into the calendar. What comes back and where it touches stays put.
        navBar = CalendarNavBarHost(requireContext(), binding.navigatorImageView, this,
            onStepDay = { d -> com.toolsboox.plugin.calendar.CalendarNavigator.toDayPage(
                this, d, com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE) })
        renderNav()

        load()
    }

    /** Draw the strip for the anchor day, dots and all. */
    private fun renderNav() {
        lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, anchor, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                cd to runCatching { calendarPatternService.load(root, anchor, loc) }.getOrNull()
            }
            if (isAdded) pat?.let { navBar?.render(day, it) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Rebuild the page.
     *
     * Reading the roots means walking every day JSON in the ledger plus the cached feed —
     * that is the whole of why this page was slow, and it is IO the page was re-doing on
     * every single open. So: the last session's answer (held in [RootsCache], per day) goes
     * on screen IMMEDIATELY, and the full re-read runs behind it, re-rendering only if the
     * ledger actually changed since. First open of a day still reads cold — there is nothing
     * to show yet — but every open after that paints before the disk is touched.
     */
    private fun load() {
        val ctx = context ?: return
        val col = binding.rootsColumn
        val today = java.time.LocalDate.now()
        val cached = RootsCache.day == today && RootsCache.threads.isNotEmpty()
        if (cached) {
            corpus = RootsCache.corpus
            threads = RootsCache.threads
            renderContent()
        } else {
            col.removeAllViews()
            col.addView(TextView(ctx).apply {
                text = getString(R.string.roots_loading)
                textSize = 14f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(10), 0, 0)
            })
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = corpusService.gather(documentsRoot(), Spiral.SCOPE)
                        .filter { Spiral.isSubstantial(it.text) }
                        .let { Spiral.dedupe(it) { s -> s.text } }
                    // The all-threads crossings that used to be computed here went straight in
                    // the bin — the page only ever shows crossings of the UNMUTED threads,
                    // recomputed in renderContent(). Threads are enough to carry out.
                    all to Rhizome.threads(
                        all.map { it.text + " " + it.title }, all.map { it.date.time })
                }.getOrNull()
            }
            if (!isAdded || result == null) return@launch
            val (all, fresh) = result
            val changed = all.size != corpus.size || fresh != threads
            RootsCache.day = today
            RootsCache.corpus = all
            RootsCache.threads = fresh
            // A cached paint that the re-read agrees with stays put — no flash for nothing.
            if (cached && !changed) return@launch
            corpus = all
            threads = fresh
            renderContent()
        }
    }

    /**
     * Draw the page from the held [corpus] and [threads] — pure memory, no disk. Muting a thread
     * and hiding a crossing land here, because everything they change (which threads are live,
     * which crossings exist between them, which are dismissed) is derivable from what's already
     * in hand; re-reading the whole ledger for a preference flip was most of why a mute felt slow.
     */
    private fun renderContent() {
        val ctx = context ?: return
        val col = binding.rootsColumn
        val threads = this.threads
        val all = this.corpus
        col.removeAllViews()

        if (threads.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = getString(R.string.roots_empty)
                textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
                setPadding(0, dp(10), 0, 0)
            })
            com.toolsboox.ot.ReadingSize.apply(col)
            return
        }

        val now = System.currentTimeMillis()
        col.addView(header(getString(R.string.roots_what_comes_back), top = 4))

        // Threads as CARDS across the width, not a column of one-word rows.
        //
        // A thread is a short word — "internet", "july", "said" — so a vertical list of them
        // is a thin ribbon down the left with the whole page empty beside it. That is wasteful
        // on any screen and absurd on a Tab X. Laid out as tiles, the same eighteen threads
        // occupy a few rows instead of eighteen, the crossings below get the space they
        // actually need for sentences, and the whole thing reads as a board of subjects rather
        // than a list of leftovers.
        //
        // WHICH eighteen is the day's board, not the all-time top: heat is a static ranking,
        // so take(18) showed the same biggest threads every day forever. composeBoard() below
        // mixes hot, mid and quiet bands, rotates within them on a date seed, and demotes what
        // the last few days already showed. Muted threads no longer eat seats — they sit at the
        // END of the grid, dimmed, so the unmute tap stays one tap away. A thread unmuted this
        // session keeps its tile even if it doesn't win a seat, so the card doesn't vanish
        // under the finger that just brought it back.
        val epochToday = java.time.LocalDate.now().toEpochDay()
        val unmuted = threads.filterNot {
            com.toolsboox.plugin.calendar.ot.RootsMute.isMuted(ctx, it.term)
        }
        val board = composeBoard(ctx, unmuted, now, epochToday)
        val live = board + unmuted.filter { it.term in unmutedThisSession && it !in board }
        com.toolsboox.plugin.calendar.ot.RootsMute.recordShown(ctx, epochToday, live.map { it.term })
        val tiles = live + threads.filter {
            com.toolsboox.plugin.calendar.ot.RootsMute.isMuted(ctx, it.term)
        }

        // Column count comes from the screen, so it stays sensible from a Palma to a Tab X.
        val columns = (resources.configuration.screenWidthDp / 190).coerceIn(2, 6)
        var rowBox: LinearLayout? = null
        for ((i, t) in tiles.withIndex()) {
            if (i % columns == 0) {
                rowBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(rowBox)
            }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = com.toolsboox.ot.SemanticCards.cardBackground(ctx)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            }
            card.addView(TextView(ctx).apply {
                // A quiet thread is one worth picking back up, so it says so rather than
                // merely sorting lower.
                text = t.term
                textSize = 19f; setTextColor(0xFF000000.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(TextView(ctx).apply {
                text = "${t.size}×" + (if (t.spanDays > 0) " · ${t.spanDays}d" else "") +
                    (if (t.isQuiet(now)) " · quiet" else "")
                textSize = 12f; setTextColor(0xFF777777.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // Tap MUTES. That is the action worth putting first: the top of this list is
            // mostly datelines and clipping plumbing, and the person reading it is the only
            // one who can tell "practice" from "units" — so saying so should cost one tap.
            // Hold to see where a thread actually runs.
            val muted = com.toolsboox.plugin.calendar.ot.RootsMute.isMuted(ctx, t.term)
            if (muted) {
                card.alpha = 0.45f
                (card.getChildAt(0) as? TextView)?.paintFlags =
                    android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
            card.setOnClickListener {
                // A mute changes nothing the disk knows about — which threads are live and
                // what crosses between them is all derivable from what's already in hand — so
                // this used to re-read the whole ledger for a preference flip, which is why a
                // mute felt slow. Toggle, then redraw from memory.
                toggleMute(t.term)
            }
            card.setOnLongClickListener { threadHoldMenu(t); true }
            rowBox?.addView(card)
        }
        // Pad the last row so three cards among four columns don't stretch to fill it.
        val remainder = tiles.size % columns
        if (remainder != 0) repeat(columns - remainder) {
            rowBox?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }

        // Crossings come from the DAY'S BOARD, not the all-time thread pool. Two reasons: a
        // mute changes what counts as a meeting-point (the junk threads were manufacturing
        // crossings between newspapers), and the board rotating daily means the meeting-points
        // rotate with it — "where they touch" stays a claim about the threads actually on the
        // page above. Ties in connectedness are broken by the same date seed, so equally-joined
        // crossings take turns leading across days instead of the corpus order deciding forever.
        val liveCrossings = Rhizome.crossings(live)
        if (liveCrossings.isNotEmpty()) {
            col.addView(header(getString(R.string.roots_where_they_touch), top = 18))
            // Most-connected first, minus the ones you've thrown away as junk meeting-points.
            val dismissed = com.toolsboox.plugin.calendar.ot.RootsMute.dismissedCrossings(ctx)
            val rng = kotlin.random.Random(epochToday)
            val ranked = liveCrossings.entries.toList().shuffled(rng)
                .sortedByDescending { it.value.size }
            for ((idx, terms) in ranked.take(12)) {
                val snip = all.getOrNull(idx) ?: continue
                if (snip.citation in dismissed) continue
                // Tombstoned since this corpus was gathered — gone from the next gather, but it
                // must not linger on screen until then either.
                if (corpusService.isExcluded(snip.citation)) continue
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(12), dp(13), dp(12))
                    background = com.toolsboox.ot.SemanticCards.cardBackground(ctx)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(3), dp(3), dp(3), dp(5)) }
                }
                row.addView(TextView(ctx).apply {
                    // Black, not the old blue: on an e-ink panel a mid-blue renders as a grey
                    // smudge, which is less legible than the body text it is meant to lead.
                    text = terms.joinToString("  ✕  ")
                    textSize = 17f; setTextColor(0xFF000000.toInt())
                })
                // The WORDS first, the provenance under them and small. The date used to lead
                // every crossing, which put the least interesting fact — when — above the thing
                // the crossing exists to show you, which is what.
                row.addView(TextView(ctx).apply {
                    text = snip.text.take(240).trim() + if (snip.text.length > 240) "…" else ""
                    textSize = 17f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.25f)
                    setPadding(0, dp(2), 0, dp(3))
                })
                row.addView(TextView(ctx).apply {
                    text = snip.citation
                    textSize = 12f; setTextColor(0xFF999999.toInt())
                })
                // Tap a meeting-point to read the whole of it and step to where it lives — it
                // was inert, which is a strange thing for the one card on the page whose entire
                // job is to say "there is more here than fits". Hold for the fuller menu: pick
                // it as a gram in your medium of choice, open its rhizome, or throw it out.
                row.setOnClickListener { openCrossing(snip, terms) }
                row.setOnLongClickListener { crossingHoldMenu(snip, terms); true }
                col.addView(row)
            }
        }

        com.toolsboox.ot.ReadingSize.apply(col)
    }

    /**
     * The day's board: which threads get the [seats].
     *
     * Heat is a static ranking — the biggest thread wins every day forever, which is how one
     * word held the page for a week. So the board is COMPOSED, not ranked:
     *
     *  - **Bands.** The pool (already heat-sorted by [Rhizome.threads], muted excluded by the
     *    caller) splits into hot (top half of the still-active threads), mid (bottom half),
     *    and quiet (nothing joined in 45 days — [Rhizome.Thread.isQuiet]). Quiet gets ~3 seats,
     *    the rest split evenly hot/mid, so the load-bearing threads stay present while the
     *    middle of the ledger — which the old ranking never reached — gets half the page.
     *  - **Anchors.** The two hottest active threads always sit. When the biggest thread is
     *    genuinely the day's material, it appears — the goal is variety, not censorship.
     *  - **Rotation.** Within each band, order is shuffled on [epochToday] as the seed:
     *    deterministic per day, so the page holds still across reopens, and different across
     *    days, so the bands' seats actually rotate.
     *  - **Demotion.** Threads the last few days' boards showed ([RootsMute.shownBefore])
     *    sort to the back of their band — more recently shown, further back. A demoted thread
     *    is never excluded: short bands backfill from the whole pool, and a pool that fits in
     *    the seats shows whole, so suppression can never empty the surface.
     *
     * Pure function of held state + prefs — no disk, no second corpus walk.
     */
    private fun composeBoard(
        ctx: android.content.Context,
        pool: List<Rhizome.Thread>,
        now: Long,
        epochToday: Long,
        seats: Int = 18
    ): List<Rhizome.Thread> {
        if (pool.size <= seats) return pool
        val lastShown = com.toolsboox.plugin.calendar.ot.RootsMute.shownBefore(ctx, epochToday)
        val historyDays = com.toolsboox.plugin.calendar.ot.RootsMute.SHOWN_HISTORY_DAYS
        // Yesterday weighs 5, five days ago weighs 1, never-shown weighs 0.
        fun penalty(t: Rhizome.Thread): Long {
            val last = lastShown[t.term.lowercase()] ?: return 0L
            return (historyDays + 1 - (epochToday - last)).coerceAtLeast(0L)
        }
        val rng = kotlin.random.Random(epochToday)
        // shuffled-then-stable-sorted: penalty groups keep their shuffled order inside.
        fun rotate(band: List<Rhizome.Thread>) = band.shuffled(rng).sortedBy { penalty(it) }

        val quiet = pool.filter { it.isQuiet(now) }
        val loud = pool.filterNot { it.isQuiet(now) }
        val quietSeats = minOf(3, quiet.size)
        val hotSeats = (seats - quietSeats + 1) / 2
        val hot = loud.take((loud.size + 1) / 2)
        val mid = loud.drop(hot.size)

        val board = LinkedHashSet<Rhizome.Thread>()
        board += (hot.take(2) + rotate(hot.drop(2))).take(hotSeats)
        board += rotate(mid).take((seats - quietSeats - board.size).coerceAtLeast(0))
        board += rotate(quiet).take(quietSeats)
        if (board.size < seats) for (t in rotate(pool)) {
            if (board.size >= seats) break
            board += t
        }
        return board.toList()
    }

    /** Flip a term's mute and redraw from memory. Unmutes are remembered for the session so
     *  the tile survives even when it doesn't win a seat on today's board. */
    private fun toggleMute(term: String) {
        val ctx = context ?: return
        val nowMuted = com.toolsboox.plugin.calendar.ot.RootsMute.toggle(ctx, term)
        if (nowMuted) unmutedThisSession.remove(term) else unmutedThisSession.add(term)
        renderContent()
    }

    /** Hold on a meeting-point: the card's verbs in one place, led by the pick. The pick asks
     *  its medium ([com.toolsboox.plugin.feeds.ot.FeedNoteGram] — handwriting, text, audio,
     *  video) with the crossing's words as the quote in hand, and lands on today's Notes page;
     *  saving with nothing added still makes the plain quote gram. "Hide" stays the soft local
     *  no; "Remove from corpus" is the hard one — tombstoned, never indexed again. */
    private fun crossingHoldMenu(snip: CorpusSnippet, terms: List<String>) {
        showIconMenu(snip.text.take(80), listOf(
            "⁂  Pick — make it a gram" to { pickAsGram(snip.text, terms.joinToString("  ✕  ")) },
            "📖  Read it whole" to { openCrossing(snip, terms) },
            "🕸  Open rhizome" to { openCrossingRhizome(snip) },
            "→  Synthesize" to { sendCrossingToSynth(snip, terms) },
            "🗑  Hide here" to {
                com.toolsboox.plugin.calendar.ot.RootsMute.dismissCrossing(requireContext(), snip.citation)
                renderContent()
            },
            "🗑  Remove from corpus" to { removeFromCorpus(snip.citation) }
        ))
    }

    /** Hold on a thread tile: what the tap can't offer — the term as a pickable object, the
     *  places it runs, and the mute spelled out. */
    private fun threadHoldMenu(t: Rhizome.Thread) {
        val ctx = context ?: return
        val muted = com.toolsboox.plugin.calendar.ot.RootsMute.isMuted(ctx, t.term)
        showIconMenu("🌿  " + t.term, listOf(
            "⁂  Pick — make it a gram" to {
                pickAsGram(t.term, "a thread through your ledger · ${t.size}×")
            },
            "🌿  Where it runs" to { showThread(t) },
            (if (muted) "🔊  Unmute" else "🔇  Mute") to { toggleMute(t.term) }
        ))
    }

    /** The shared pick seam for this page — item text in, medium chooser up, gram on today. */
    private fun pickAsGram(itemText: String, originLabel: String) {
        com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
            this, calendarDayService, documentsRoot(),
            itemText = itemText.take(600), originLabel = originLabel.take(80)
        ) { kind, sink -> captureAvGramDirect(kind, sink) }
    }

    /** Tombstone a crossing's snippet: it leaves this page now and the corpus for good — no
     *  re-gather, on any surface, brings it back. */
    private fun removeFromCorpus(citation: String) {
        lifecycleScope.launch {
            // Tombstone first, THEN redraw — renderContent asks isExcluded, so drawing before
            // the exclusion lands would show the thing being removed one last time.
            withContext(Dispatchers.IO) { runCatching { corpusService.exclude(citation) } }
            if (!isAdded) return@launch
            renderContent()
            showMessage("Removed from the corpus — it won't be indexed again", requireView())
        }
    }

    private fun header(text: String, top: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f; setTextColor(0xFF777777.toInt()); letterSpacing = 0.08f
        setPadding(0, dp(top), 0, dp(8))
    }

    /**
     * Read a crossing whole, and go where it lives.
     *
     * The card shows 240 characters; the thing that made it worth surfacing is often past that.
     * So the tap opens the full text with its own note beneath it, and — when it is a planner
     * snippet — a way to step onto the day it sits on.
     */
    private fun openCrossing(snip: CorpusSnippet, terms: List<String>) {
        val ctx = context ?: return
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        col.addView(TextView(ctx).apply {
            text = snip.text.trim()
            textSize = 17f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.25f)
        })
        // The reader's own note is different evidence from the passage — why it was marked, not
        // what was marked — so it gets its own line rather than being run together with the text.
        if (snip.own.isNotBlank()) col.addView(TextView(ctx).apply {
            text = "— " + snip.own.trim()
            textSize = 15f; setTextColor(0xFF444444.toInt()); setLineSpacing(0f, 1.2f)
            setPadding(0, dp(10), 0, 0)
        })
        col.addView(TextView(ctx).apply {
            text = snip.citation
            textSize = 12f; setTextColor(0xFF999999.toInt()); setPadding(0, dp(10), 0, 0)
        })
        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        com.toolsboox.ot.ReadingSize.apply(scroll)

        val day = snip.date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(terms.joinToString("  ✕  "))
            .setView(scroll)
            .setPositiveButton("→  Synthesize") { _, _ -> sendCrossingToSynth(snip, terms) }
            .setNeutralButton("Go to the day") { _, _ ->
                com.toolsboox.plugin.calendar.CalendarNavigator.toDayPage(this, day,
                    com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE)
            }
            // Throw a junk meeting-point away — two snippets that share a stray word and mean
            // nothing together. It stays in the corpus; it just stops being offered here.
            .setNegativeButton("🗑 Hide") { _, _ ->
                com.toolsboox.plugin.calendar.ot.RootsMute.dismissCrossing(requireContext(), snip.citation)
                // A dismissal is a preference, not new material — redraw from memory.
                renderContent()
            }
            .show()
    }

    /**
     * Move a root onto a Synthesize page as an object.
     *
     * A crossing is already a thing worth keeping — the moment two preoccupations met — so this
     * carries it whole onto a synthesis: a card with its words and terms, and the provenance to
     * jump back to the day it came from. On the synth page it is a real gram, so it can be moved,
     * arranged next to the other pieces, mapped, and trimmed like anything else placed there.
     */
    private fun sendCrossingToSynth(snip: CorpusSnippet, terms: List<String>) {
        val day = snip.date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val back = com.toolsboox.ot.LedgerUri.page(day.toString())
        chooseSynthPage { page ->
            // Ask which of that day's pieces to bring, rather than dumping them and making you
            // delete the strays. The root always comes; the grams, cards and pictures that shared
            // the day are ticked by default but yours to untick — the boundary trim, applied at
            // the moment you move the object rather than after.
            lifecycleScope.launch {
                val pieces = withContext(Dispatchers.IO) {
                    runCatching {
                        calendarDayService.load(documentsRoot(), day, null, java.util.Locale.getDefault())
                            .imageElements
                            // Real static cards only. An A/V gram is a poster for a recording that
                            // isn't coming with it, and a faceless element is nothing to place —
                            // either would land as a stray image on the synthesis. A face is
                            // inline `data` OR a media `dataRef` (WIRE-MEDIA-BY-REFERENCE.md).
                            .filter { (it.data.isNotBlank() || it.dataRef.isNotBlank()) && it.mediaKind.isBlank() }
                            .toList()
                    }.getOrNull().orEmpty()
                }
                if (pieces.isEmpty()) { placeCrossing(snip, terms, page, back, emptyList()); return@launch }
                val ctx = context ?: return@launch
                val labels = pieces.mapIndexed { i, el ->
                    "🖼  " + el.sourceLabel.ifBlank { el.mediaTitle.ifBlank { "Piece ${i + 1}" } }.take(60)
                }.toTypedArray()
                val checked = BooleanArray(pieces.size) { true }
                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                    .setTitle("Bring which pieces with it?")
                    .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                    .setPositiveButton("Send") { _, _ ->
                        placeCrossing(snip, terms, page, back, pieces.filterIndexed { i, _ -> checked[i] })
                    }
                    .setNeutralButton("Root only") { _, _ -> placeCrossing(snip, terms, page, back, emptyList()) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Drop the root's quote card plus [pieces] onto [page], each with a link back to its day. */
    private fun placeCrossing(
        snip: CorpusSnippet, terms: List<String>,
        page: com.toolsboox.plugin.calendar.ot.SynthPage, back: String,
        pieces: List<com.toolsboox.da.ImageElement>
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val place = com.toolsboox.plugin.calendar.ot.PickingsPlacement
                // The root as a fresh quote card — it gets the tape.
                val card = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                    snip.text.take(600), terms.joinToString("  ✕  "),
                    snip.own.take(160).ifBlank { null }, 1080, 0)
                runCatching {
                    place.place(calendarDayService, root, card, page.date, page.key,
                        sourceLink = back, sourceLabel = terms.joinToString(" · "),
                        cardText = snip.text.take(600))
                }
                // The chosen pieces, brought over AS THEY LOOK — they keep their own faces
                // (treatment = false, or a taped card would be taped twice) and a link home.
                for (el in pieces) {
                    val bmp = context?.let {
                        com.toolsboox.ot.LedgerMedia.resolveBitmap(it, el.data, el.dataRef)
                    }
                    if (bmp != null) runCatching {
                        place.place(calendarDayService, root, bmp, page.date, page.key,
                            sourceLink = el.sourceLink.ifBlank { back },
                            sourceLabel = el.sourceLabel.ifBlank { terms.joinToString(" · ") },
                            treatment = false)
                    }
                }
            }
            val what = if (pieces.isEmpty()) "Sent to ${page.name}."
                else "Sent to ${page.name} — the root and ${pieces.size} pieces."
            // Offer the trip rather than taking it — you may be sending several roots to the same
            // page before you go look at it — but make going one tap.
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, what, com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction("Go to it") {
                com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(this@LedgerRootsFragment, page.date, page.key)
            }.show()
        }
    }

    /** Pick where a root lands: today's synthesis, or one of the named topic pages. */
    private fun chooseSynthPage(onPick: (com.toolsboox.plugin.calendar.ot.SynthPage) -> Unit) {
        val ctx = context ?: return
        val store = com.toolsboox.plugin.calendar.ot.SynthPageStore
        val topics = store.list(ctx)
        val today = com.toolsboox.plugin.calendar.ot.SynthPage(store.DEFAULT_KEY, "Today's synthesis", java.time.LocalDate.now())
        val pages = listOf(today) + topics
        val labels = (pages.map { "🔬  ${it.name}" } + listOf("＋  New synthesis…")).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Send to which synthesis?")
            .setItems(labels) { _, which ->
                if (which < pages.size) onPick(pages[which])
                else {
                    val input = android.widget.EditText(ctx).apply { hint = "What's this synthesis about?"; setSingleLine() }
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    val box = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
                    }
                    // Guarded: a name being typed is work — a stray touch outside must not
                    // throw it away.
                    showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                        .setTitle("New synthesis").setView(box)
                        .setPositiveButton("Create") { _, _ ->
                            onPick(store.add(ctx, input.text.toString().trim()))
                        }
                        .setNegativeButton(android.R.string.cancel, null).create())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Open the day this crossing sits on as an object, and everything it joins. */
    private fun openCrossingRhizome(snip: CorpusSnippet) {
        val day = snip.date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to com.toolsboox.ot.LedgerUri.page(day.toString()),
                LedgerRhizomeFragment.ARG_LABEL to snip.title.ifBlank { snip.citation }
            )
        )
    }

    /** Everywhere one thread runs, oldest first — the shape of a preoccupation over time. */
    private fun showThread(thread: Rhizome.Thread) {
        val ctx = context ?: return
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        for (i in thread.members.sortedBy { corpus.getOrNull(it)?.date?.time ?: 0L }) {
            val snip = corpus.getOrNull(i) ?: continue
            col.addView(TextView(ctx).apply {
                text = snip.text.take(300).trim() + if (snip.text.length > 300) "…" else ""
                textSize = 17f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.25f)
                setPadding(0, dp(14), 0, dp(2))
            })
            col.addView(TextView(ctx).apply {
                text = snip.citation
                textSize = 12f; setTextColor(0xFF999999.toInt())
            })
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        com.toolsboox.ot.ReadingSize.apply(scroll)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("🌿  " + thread.term)
            .setView(scroll)
            .setNegativeButton(getString(R.string.roots_close), null)
            .show()
    }

    // The column says "Reading the roots…" until it has something (and, after the first read of
    // the day, opens straight onto the cached roots) — a spinner would only add churn.
    override fun showLoading() {}
    override fun hideLoading() {}
}

/**
 * The last full read of the roots, kept for the session so reopening the page paints before the
 * disk is touched. Keyed by day — a new day is new material by definition — and kept honest
 * within the day by the background re-read in [LedgerRootsFragment.load], which replaces it (and
 * the screen) only when the ledger actually changed. Deliberately NOT persisted: the corpus walk
 * is the expensive thing, and it happens exactly once per process per open-day either way.
 */
private object RootsCache {
    var day: java.time.LocalDate? = null
    var corpus: List<CorpusSnippet> = emptyList()
    var threads: List<Rhizome.Thread> = emptyList()
}
