package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.databinding.FragmentLedgerMapBinding
import com.toolsboox.ot.LedgerUri
import com.toolsboox.ot.MindMap
import com.toolsboox.ot.MapPersona
import com.toolsboox.ot.Markmap
import com.toolsboox.ot.MindMapView
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.ot.ConnectionStore
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.calendar.ot.LedgerTags
import timber.log.Timber
import com.toolsboox.ui.plugin.ScreenFragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Map: your connections as a picture rather than a list.
 *
 * The rhizome card answers "what does this one thing touch"; this answers "what does any of it
 * look like". Same edges underneath — a map and a list of the same graph, and you want both for
 * the same reason you want a map and a route.
 *
 * It opens on the busiest node rather than on today, because a map that opens on an empty corner
 * teaches you nothing about your own material.
 *
 * Tap to walk (that node becomes the middle); hold to open the thing itself.
 */
@AndroidEntryPoint
class LedgerMapFragment @Inject constructor() : ScreenFragment() {

    companion object {
        const val ARG_URI = "map_uri"
        // Map ONE page's gathered pieces instead of the whole connection graph — a synthesis laid
        // out as where its material came from and how it clusters.
        const val ARG_PAGE_DATE = "map_page_date"
        const val ARG_PAGE_KEY = "map_page_key"

        /** Fewer distinct nodes than this and the connection graph isn't a picture yet — the
         *  word-rhizome weave carries the surface instead. */
        const val MIN_CONNECTION_NODES = 3

        /** How many tag↔tag co-occurrence edges the map draws, hottest (most shared pages) first.
         *  The tag↔page edges ride the connection store unbounded; only the derived tag web is
         *  capped, so a ledger with a dense tag vocabulary stays a picture rather than a hairball. */
        const val MAX_TAG_COOCCURRENCE_EDGES = 48

        /** The seed bed's own middle. Deliberately NOT a ledger address: the bed is a place to
         *  stand, not an object, so [nodeHoldMenu] stands down on it rather than offering a
         *  rhizome that could never resolve — the same guard the weave's centre gets. */
        const val SEED_BED = "seeds:root"

        /** How many seeds the bed hands the layout: twice what a ring can seat, so the rest are a
         *  walk away rather than thrown away. */
        private const val MAX_SEEDS = MindMap.MAX_INNER * 2

        /** The immediate state while the ledger is read — the surface must never look dead. */
        private const val WEAVING = "Weaving the map…"
    }

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    override val view = R.layout.fragment_ledger_map

    private lateinit var binding: FragmentLedgerMapBinding
    private lateinit var map: MindMapView
    private var navBar: CalendarNavBarHost? = null
    private var anchor: java.time.LocalDate = java.time.LocalDate.now()

    private var edges: List<Connection> = emptyList()
    private var adjacency: Map<String, List<String>> = emptyMap()
    private var labels: Map<String, String> = emptyMap()
    /** Non-null when the picture is the word-rhizome weave rather than drawn edges. */
    private var weave: com.toolsboox.plugin.calendar.ot.LedgerMapWeave.Weave? = null
    private var focus: String = ""
    /** Where we have been, so Back walks the map rather than leaving it. */
    private val trail = ArrayDeque<String>()
    /** The markdown behind the current picture, when it came from an outline rather than edges. */
    private var outline: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerMapBinding.bind(view)
        binding.mapTitle.text = getString(R.string.map_title)
        binding.mapMenu.setOnClickListener { showDrawMenu() }
        binding.gotoButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }

        // The almanac strip, as on Write and Synthesize. The map is the whole graph, not one day,
        // so the date is a place to leave from: step to a day and open it, or tap a period to
        // jump into the calendar. The picture itself does not change with the anchor.
        navBar = CalendarNavBarHost(requireContext(), binding.navigatorImageView, this,
            onStepDay = { d -> CalendarNavigator.toDayPage(
                this, d, com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE) })
        renderNav()
        binding.mapClose.setOnClickListener {
            if (trail.isEmpty()) findNavController().popBackStack()
            else { focus = trail.removeLast(); render() }
        }

        map = MindMapView(requireContext()).apply {
            onNodeTap = { uri -> if (uri != focus) { trail.addLast(focus); focus = uri; render() } }
            onNodeHold = { uri -> nodeHoldMenu(uri) }
        }
        binding.mapCanvas.addView(
            map,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // The contract in one line, before anything is deduced from a diagram.
        binding.mapHint.text = getString(R.string.map_hint)

        val pageDate = arguments?.getString(ARG_PAGE_DATE)?.takeIf { it.isNotBlank() }
        if (pageDate != null) {
            loadPageGraph(pageDate, arguments?.getString(ARG_PAGE_KEY).orEmpty())
            render()
        } else {
            openMap(arguments?.getString(ARG_URI)?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * First paint of the whole-ledger map, off the main thread.
     *
     * The drawn connection graph stays primary when it is a picture worth drawing; when it is
     * empty or too thin (< [MIN_CONNECTION_NODES] nodes — an imported archive has decades of
     * material and essentially no edges) the surface weaves the word-rhizome fallback instead of
     * claiming nothing is connected. Either way the subject line says "Weaving the map…"
     * immediately, so the screen is never silently dead while a big ledger is read.
     */
    private fun openMap(wanted: String?) {
        binding.mapSubject.text = WEAVING
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val conn = withContext(Dispatchers.IO) { buildConnectionGraph(appCtx) }
            if (!isAdded) return@launch
            if (conn.adjacency.keys.size >= MIN_CONNECTION_NODES) {
                applyConnections(conn)
                focus = wanted?.takeIf { it in adjacency } ?: openingFocus()
                render()
            } else {
                showWeave(initialFocus = wanted)
            }
        }
    }

    /** Pan the picture with the hardware keys, by most of the panel like every other surface —
     *  the map canvas is a custom View, so the ScreenFragment default finds nothing to scroll. */
    override fun onVolumeKey(up: Boolean): Boolean {
        if (!volumeKeysPage()) return false
        if (!::map.isInitialized) return false
        val step = (map.height * 9 / 10).coerceAtLeast(1)
        map.panBy(if (up) step.toFloat() else -step.toFloat())
        return true
    }

    /**
     * Map one page's gathered pieces: where the material came from, and how it clusters.
     *
     * The page sits in the middle. Each distinct SOURCE the pieces carry (a root's terms, a day)
     * rings it, and the pieces themselves hang off the source they came from — so a synthesis
     * built from three roots reads as three clusters, and a stray piece with no provenance sits on
     * its own spoke. This is the picture you arrange before you skeleton: what belongs together is
     * suddenly visible, and the odd one out is suddenly obvious.
     */
    private fun loadPageGraph(dateStr: String, pageKey: String) {
        val date = runCatching { java.time.LocalDate.parse(dateStr) }.getOrNull() ?: return
        val ctx = requireContext()
        val day = runCatching {
            calendarDayService.load(documentsRoot(), date, null, java.util.Locale.getDefault())
        }.getOrNull() ?: return

        val adj = HashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()
        val center = com.toolsboox.ot.LedgerUri.page(dateStr, pageKey)
        names[center] = com.toolsboox.plugin.calendar.ot.SynthPageStore.nameOf(ctx, pageKey)

        fun link(a: String, b: String) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }

        // A face is inline `data` OR a media `dataRef` — an externalized card still maps.
        val pieces = day.imageElements.filter {
            it.page == pageKey && (it.data.isNotBlank() || it.dataRef.isNotBlank()) && it.mediaKind.isBlank()
        }
        val textPieces = day.textElements.filter { it.pageKey == pageKey && it.text.isNotBlank() }

        for (el in pieces) {
            val id = com.toolsboox.ot.LedgerUri.element(dateStr, pageKey, el.elementId.toString())
            names[id] = el.sourceLabel.ifBlank { "Card" }.take(40)
            // Group under the source it came from, when it named one; else straight to the centre.
            val src = el.sourceLabel.takeIf { it.isNotBlank() }
            if (src != null) {
                val srcId = "src:$src"
                names[srcId] = src.take(40)
                if (srcId !in adj) link(center, srcId)
                link(srcId, id)
            } else link(center, id)
        }
        for (el in textPieces) {
            val id = com.toolsboox.ot.LedgerUri.element(dateStr, pageKey, el.elementId.toString())
            names[id] = el.text.take(40)
            link(center, id)
        }

        adjacency = adj
        labels = names
        weave = null
        focus = center
    }

    /** Draw the strip for the anchor day. */
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

    /** One built connection graph — pure data, so the build can run on any dispatcher. */
    private data class ConnGraph(
        val edges: List<Connection>,
        val adjacency: Map<String, List<String>>,
        val labels: Map<String, String>
    )

    private fun buildConnectionGraph(ctx: android.content.Context): ConnGraph {
        val edges = ConnectionStore.loadAll(ctx).filter { !it.isDeleted }

        val adj = HashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()
        val contacts = runCatching { ContactStore.loadAll(ctx).associateBy { it.id } }.getOrNull().orEmpty()

        fun name(uri: String, recorded: String): String {
            val ref = LedgerUri.parse(uri)
            if (ref?.scheme == LedgerUri.SCHEME_CONTACT) {
                contacts[ref.body]?.name?.ifBlank { null }?.let { return it }
            }
            return recorded.ifBlank { LedgerUri.describe(uri) }
        }

        for (e in edges) {
            // Undirected on the map. Which way an edge points matters when you are reading one
            // relationship; when you are looking at the shape of the whole thing, it is noise.
            adj.getOrPut(e.from) { mutableListOf() }.add(e.to)
            adj.getOrPut(e.to) { mutableListOf() }.add(e.from)
            names[e.from] = name(e.from, e.fromLabel)
            names[e.to] = name(e.to, e.toLabel)
        }

        // Tags are already nodes here — LedgerTags writes a tag↔page edge for every occurrence, so
        // a `#tag` arrives in the loop above like any other end and the radial layout sizes it by
        // heft (its degree = the pages it tags). The ONE relation the connection store does not hold
        // is tag↔tag: two tags that share a page. We derive it cheaply and add it so the tag web is
        // visible — a light edge between #ashtanga and #backbends when they keep landing together.
        val cooc = runCatching { LedgerTags.coOccurrences(ctx) }.getOrNull().orEmpty()
        val shownCooc = cooc.take(MAX_TAG_COOCCURRENCE_EDGES)
        if (cooc.size > shownCooc.size) {
            Timber.i("Map: %d tag co-occurrence edges, drawing hottest %d", cooc.size, shownCooc.size)
        }
        for ((a, b, _) in shownCooc) {
            val ua = LedgerTags.tagUri(a); val ub = LedgerTags.tagUri(b)
            adj.getOrPut(ua) { mutableListOf() }.add(ub)
            adj.getOrPut(ub) { mutableListOf() }.add(ua)
            names.putIfAbsent(ua, "#$a")
            names.putIfAbsent(ub, "#$b")
        }

        // Every neighbour list heaviest-first. The layout can only seat MAX_INNER around the
        // focus, and it seats them in list order — unsorted, the first paint was whichever ten
        // edges happened to be recorded first, which is how a map of your own material manages
        // to open looking arbitrary. Sorted, the ring IS the top-N: the most-connected things
        // you have, named and tappable, and the long tail stays a walk away instead of noise.
        val deg = adj.mapValues { it.value.distinct().size }
        for (list in adj.values) list.sortByDescending { deg[it] ?: 0 }
        return ConnGraph(edges, adj, names)
    }

    private fun applyConnections(g: ConnGraph) {
        edges = g.edges
        adjacency = g.adjacency
        labels = g.labels
        weave = null
    }

    /** The "My connections" road: load off-main, then draw from the busiest corner. */
    private fun showConnections() {
        binding.mapSubject.text = WEAVING
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val g = withContext(Dispatchers.IO) { buildConnectionGraph(appCtx) }
            if (!isAdded) return@launch
            applyConnections(g)
            trail.clear()
            focus = openingFocus()
            render()
        }
    }

    /**
     * The word-rhizome weave: threads ring the centre sized by heat, crossings hang off the
     * threads they join — [com.toolsboox.plugin.calendar.ot.LedgerMapWeave], the iOS Map's
     * fallback ported onto the same layout pipeline as the connection graph.
     *
     * Cache shape mirrors Roots: a weave already built today paints instantly, then a background
     * re-build (cheap — the corpus walk underneath is mtime-cached on disk) replaces the picture
     * only when the ledger actually changed, so reopening is instant and never re-scans decades.
     */
    private fun showWeave(initialFocus: String? = null) {
        val weaveCache = com.toolsboox.plugin.calendar.ot.LedgerMapWeave.Cache
        val today = java.time.LocalDate.now()
        val held = weaveCache.weave?.takeIf { weaveCache.day == today && !it.isEmpty }
        if (held != null) {
            applyWeave(held, initialFocus)
            render()
        } else {
            binding.mapSubject.text = WEAVING
        }

        val root = documentsRoot()
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { com.toolsboox.plugin.calendar.ot.LedgerMapWeave.build(corpusService, root) }
                    .getOrNull()
            } ?: com.toolsboox.plugin.calendar.ot.LedgerMapWeave.EMPTY
            if (!isAdded) return@launch
            weaveCache.day = today
            weaveCache.weave = fresh
            // Only repaint if the surface is still showing (or waiting for) THIS weave — the
            // person may have walked to My connections or an outline while the ledger was read.
            val stillOnWeave = weave === held || (weave == null && adjacency.isEmpty())
            if (!stillOnWeave) return@launch
            // A cached paint the re-build agrees with stays put — no flash for nothing.
            if (held != null && fresh.adjacency == held.adjacency) return@launch
            applyWeave(fresh, initialFocus)
            render()
        }
    }

    /**
     * 🌰 The "Seeds" road: the seed bed in the middle, each incubating `#tag` ringing it, and the
     * pages that tag was planted on hanging off it.
     *
     * The Map's other two pictures both need the ledger to have done something first — the
     * connection graph needs edges you drew, the weave needs words that have come back often
     * enough to be threads — which is the honest reason the surface can open on nothing. A seed
     * needs neither: the moment you write a `#hashtag` it is planted ([LedgerTags.record]), so
     * this is the map most ledgers can draw on the day they are opened, and it answers a question
     * you actually have — what am I currently incubating, and where does it live?
     *
     * Newest-planted first, because that is what "still finding its roots" means and it is the bed
     * [SeedsFragment] leads with. Every node here is a REAL address: a tag is `tag://…`, the same
     * node the connection graph draws, so holding a seed opens its own rhizome; an occurrence is
     * `ledger://<date>/<page>`, so holding one opens the day it was planted on. Nothing synthetic
     * but the bed itself, which is why it is the only thing [nodeHoldMenu] has to guard.
     */
    private fun showSeeds() {
        binding.mapSubject.text = WEAVING
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val bed = withContext(Dispatchers.IO) { buildSeedBed(appCtx) }
            if (!isAdded) return@launch
            if (bed.adjacency.isEmpty()) {
                // The picture you had stays on screen; only the refusal is new information. A road
                // asked for BY NAME is the only one that can come back empty and mean something —
                // the ordinary empty-ledger case is already worded properly by render(), but asking
                // for Seeds on a ledger with no hashtags deserves to be told why.
                render()
                showMessage("No seeds yet — write a #hashtag on a page and it plants here.", binding.root)
                return@launch
            }
            applyConnections(bed)
            trail.clear()
            focus = SEED_BED
            render()
        }
    }

    /**
     * Build the bed off-main, as a plain [ConnGraph] — the same shape [buildConnectionGraph]
     * returns, so this is one more producer of the picture rather than a second kind of picture.
     *
     * Node weight rides the degree fallback in [render] rather than a weights map of its own, and
     * that lands where it should by construction: a seed's neighbours ARE its occurrences (plus
     * the bed), so a tag written on nine pages draws heavier than one written on two — sized by
     * occurrence count, which is what the ring is supposed to say.
     */
    private fun buildSeedBed(ctx: android.content.Context): ConnGraph {
        val infos = runCatching { LedgerTags.list(ctx) }.getOrNull().orEmpty()
            .filter { it.occurrences.isNotEmpty() }
        if (infos.isEmpty()) return ConnGraph(emptyList(), emptyMap(), emptyMap())

        val adj = HashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()
        names[SEED_BED] = "🌰 Seeds"
        val bed = adj.getOrPut(SEED_BED) { mutableListOf() }

        for (info in infos.sortedByDescending { it.created }.take(MAX_SEEDS)) {
            val tid = LedgerTags.tagUri(info.tag)
            bed.add(tid)
            names[tid] = "#${info.tag}"
            // The bed first, then this seed's pages — so standing in a seed you see where it has
            // landed with the way back one step away rather than buried at the end of the ring.
            val pages = mutableListOf(SEED_BED)
            // Newest sighting first: where a seed has been landing lately is the useful end of its
            // history, and the ring only has room for the head of the list.
            for (occ in info.occurrences.sortedByDescending { it.first }) {
                val pid = LedgerUri.page(occ.first.toString(), occ.second)
                names.putIfAbsent(pid, LedgerUri.describe(pid))
                pages.add(pid)
                // Undirected, like every other picture here: focus a page and every seed planted
                // on it rings IT, which is the crossing made visible.
                adj.getOrPut(pid) { mutableListOf() }.add(tid)
            }
            adj[tid] = pages
        }
        return ConnGraph(emptyList(), adj, names)
    }

    private fun applyWeave(w: com.toolsboox.plugin.calendar.ot.LedgerMapWeave.Weave, initialFocus: String?) {
        weave = w
        adjacency = w.adjacency
        labels = w.labels
        edges = emptyList()
        trail.clear()
        focus = initialFocus?.takeIf { it in w.adjacency } ?: com.toolsboox.plugin.calendar.ot.LedgerMapWeave.ROOT
    }

    /** The most-connected node — the part of the map with something to show. */
    private fun busiest(): String =
        adjacency.maxByOrNull { it.value.distinct().size }?.key.orEmpty()

    /**
     * Where the map opens: TODAY's corner when today has connections worth seeing, else the
     * busiest node. A map that opens on today answers "what is my current material touching?"
     * before you have touched anything; only when today hasn't woven yet does it fall back to
     * the strongest cluster overall — and either way the first paint is a named, tappable
     * middle, not an arbitrary corner.
     */
    private fun openingFocus(): String {
        val today = java.time.LocalDate.now().toString()
        val todays = adjacency.entries
            .filter { it.key.contains(today) }
            .maxByOrNull { it.value.distinct().size }
        if (todays != null && todays.value.distinct().size >= 2) return todays.key
        return busiest()
    }

    private fun render() {
        val w = weave
        if (adjacency.isEmpty() || focus.isBlank()) {
            map.setGraph(emptyList())
            // "Nothing is connected yet" is only honest when there is genuinely nothing — no
            // edges AND an empty corpus. A ledger with material but no recurring words yet gets
            // the truthful version instead.
            binding.mapSubject.text =
                if (w != null && !w.corpusIsEmpty)
                    "Nothing has come back often enough to weave yet — this fills in as the ledger does."
                else getString(R.string.map_empty)
            binding.mapHint.text = getString(R.string.map_hint)
            return
        }
        binding.mapSubject.text = labels[focus] ?: LedgerUri.describe(focus)
        // The weave announces its window on the hint line — a capped map says what it was woven
        // from rather than silently truncating 24 years to a picture.
        binding.mapHint.text = w?.subtitle()?.takeIf { it.isNotBlank() } ?: getString(R.string.map_hint)
        // Each node's weight rides along so the view can size boxes by it, the way the iOS Map
        // sizes its discs — connection count for drawn edges, thread heat for the weave. A busy
        // node should LOOK load-bearing.
        map.setGraph(
            MindMap.layout(focus, adjacency) { labels[it] ?: LedgerUri.describe(it) },
            w?.weights ?: adjacency.mapValues { it.value.distinct().size })
    }

    /**
     * Where the map comes from.
     *
     * Your own connections are the true map and the least surprising one. An outline is the map
     * you already have in your head and just want to see. And Ask is for the map you cannot draw
     * yet, which is the only one worth asking a machine for.
     */
    private fun showDrawMenu() {
        showIconMenu(getString(R.string.map_title), listOf(
            // ⁂ is THE connect glyph on Android — the same mark the semantic surfaces wear.
            "⁂  My connections" to { showConnections() },
            // The weave stays reachable even when drawn edges exist — the words are a different
            // map of the same ledger, not just the fallback for an unwoven one.
            "🌿  Word rhizomes" to { showWeave() },
            // The third road, and the one that answers Michael's standing complaint that the Map
            // is "not obviously useful on load" with material rather than with copy: the two above
            // both need the ledger to have DONE something first, and a seed needs nothing but a
            // hashtag. It sits here rather than under Ask because it is not a map you have to ask
            // anyone for — the bed is already in the ledger.
            "🌰  Seeds — what's incubating" to { showSeeds() },
            "✎  Type an outline…" to { showOutlineDialog("") },
            "🧠  Ask for a map…" to { showPersonaMenu() }
        ))
    }

    private fun showPersonaMenu() {
        showIconMenu("Draw it as…", MapPersona.ALL.map { p ->
            p.label to { askFor(p) }
        })
    }

    /** What the model should chew on: whatever you type, or your own material if you type nothing. */
    private fun askFor(persona: MapPersona.Persona) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val input = android.widget.EditText(ctx).apply {
            hint = "What should it map? A topic, a question, or paste something in."
            setLines(4)
            gravity = android.view.Gravity.TOP
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
            addView(android.widget.TextView(ctx).apply {
                text = persona.blurb
                textSize = 13f; setTextColor(0xFF666666.toInt())
                setPadding(0, 0, 0, (10 * dp).toInt())
            })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(persona.label)
            .setView(box)
            .setPositiveButton("Draw it") { _, _ -> runPersona(persona, input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runPersona(persona: MapPersona.Persona, subject: String) {
        val ctx = requireContext()
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
        if (creds == null) {
            showMessage(getString(R.string.map_needs_key), binding.root); return
        }
        val material = subject.ifBlank {
            // Nothing typed → map what is already connected, named rather than addressed.
            labels.values.distinct().take(60).joinToString("\n")
        }
        if (material.isBlank()) { showMessage(getString(R.string.map_nothing_to_map), binding.root); return }

        binding.mapSubject.text = getString(R.string.map_drawing)
        val (provider, key, model) = creds
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.chat.nw.LedgerChatService().run(provider, key, model, persona.prompt, material)
            }
            when (result) {
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok -> showOutline(result.answer)
                is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Err -> {
                    render()
                    showMessage(result.message, binding.root)
                }
            }
        }
    }

    /** Show the markdown before drawing it, so a map you did not mean can be edited, not just rejected. */
    private fun showOutlineDialog(initial: String) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val input = android.widget.EditText(ctx).apply {
            setText(initial.ifBlank { "# My map\n## First branch\n- a thought\n- another\n## Second branch\n" })
            setLines(10)
            gravity = android.view.Gravity.TOP
            textSize = 13f
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Outline")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Draw it") { _, _ -> showOutline(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Draw a markdown outline instead of the connection graph. */
    private fun showOutline(markdown: String) {
        val nodes = Markmap.parse(markdown)
        val root = Markmap.root(nodes)
        if (root == null) { showMessage(getString(R.string.map_nothing_to_map), binding.root); return }
        adjacency = Markmap.adjacency(nodes)
        labels = Markmap.labels(nodes)
        weave = null
        trail.clear()
        focus = root
        outline = markdown
        render()
    }

    /** Hold on a node: the node's name up top, then pick it as a gram (the medium chooser, as
     *  everywhere) or open the thing itself — holding used to jump straight to the rhizome,
     *  which was the right single verb until picking existed. */
    private fun nodeHoldMenu(uri: String) {
        // Weave nodes are synthetic addresses ("weave:…"), not ledger uris — holding one gets
        // the weave's own menu instead of a rhizome jump that could never resolve.
        val w = weave
        if (w != null && com.toolsboox.plugin.calendar.ot.LedgerMapWeave.isWeaveNode(uri)) {
            weaveHoldMenu(w, uri)
            return
        }
        // The seed bed is the one node on that map with nothing behind it — the same reason the
        // weave's centre has no menu. Everything else the bed places is a real address.
        if (uri == SEED_BED) return
        val label = labels[uri] ?: LedgerUri.describe(uri)
        showIconMenu(label.take(80), listOf(
            "⁂  Pick — make it a gram" to {
                com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
                    this, calendarDayService, documentsRoot(),
                    itemText = label.take(600), originLabel = "from your map",
                    sourceUrl = uri
                ) { kind, sink -> captureAvGramDirect(kind, sink) }
            },
            "⁂  Open rhizome" to { openRhizome(uri) }
        ))
    }

    private fun openRhizome(uri: String) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                LedgerRhizomeFragment.ARG_URI to uri,
                LedgerRhizomeFragment.ARG_LABEL to (labels[uri] ?: LedgerUri.describe(uri))
            )
        )
    }

    /**
     * Hold on a weave node. A thread opens everywhere it runs (the same reading Roots gives it);
     * a crossing gets the usual pair of verbs — pick it as a gram, or open the day it was made on
     * as an object (the road Roots takes for a crossing). The centre has nothing to hold.
     */
    private fun weaveHoldMenu(w: com.toolsboox.plugin.calendar.ot.LedgerMapWeave.Weave, uri: String) {
        val weaveOt = com.toolsboox.plugin.calendar.ot.LedgerMapWeave
        when {
            weaveOt.isThread(uri) -> {
                val t = w.threadsByTerm[weaveOt.termOf(uri)] ?: return
                showThreadRun(w, t)
            }
            weaveOt.isItem(uri) -> {
                val snip = weaveOt.itemIndexOf(uri)?.let { w.snippets.getOrNull(it) } ?: return
                showIconMenu(snip.text.take(80), listOf(
                    "⁂  Pick — make it a gram" to {
                        com.toolsboox.plugin.feeds.ot.FeedNoteGram.showForItem(
                            this, calendarDayService, documentsRoot(),
                            itemText = snip.text.take(600), originLabel = "from your map"
                        ) { kind, sink -> captureAvGramDirect(kind, sink) }
                    },
                    "⁂  Open rhizome" to {
                        val day = snip.date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        findNavController().navigate(
                            R.id.action_to_ledger_rhizome,
                            androidx.core.os.bundleOf(
                                LedgerRhizomeFragment.ARG_URI to LedgerUri.page(day.toString()),
                                LedgerRhizomeFragment.ARG_LABEL to snip.title.ifBlank { snip.citation }
                            )
                        )
                    }
                ))
            }
        }
    }

    /** Everywhere one thread runs, oldest first — the same reading Roots gives a thread. */
    private fun showThreadRun(
        w: com.toolsboox.plugin.calendar.ot.LedgerMapWeave.Weave,
        thread: com.toolsboox.plugin.calendar.ot.Rhizome.Thread
    ) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt())
        }
        for (i in thread.members.sortedBy { w.snippets.getOrNull(it)?.date?.time ?: 0L }) {
            val snip = w.snippets.getOrNull(i) ?: continue
            col.addView(TextView(ctx).apply {
                text = snip.text.take(300).trim() + if (snip.text.length > 300) "…" else ""
                textSize = 17f; setTextColor(0xFF000000.toInt()); setLineSpacing(0f, 1.25f)
                setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
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

    override fun showLoading() {}
    override fun hideLoading() {}
}
