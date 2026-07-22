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
    }

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    override val view = R.layout.fragment_ledger_map

    private lateinit var binding: FragmentLedgerMapBinding
    private lateinit var map: MindMapView
    private var navBar: CalendarNavBarHost? = null
    private var anchor: java.time.LocalDate = java.time.LocalDate.now()

    private var edges: List<Connection> = emptyList()
    private var adjacency: Map<String, List<String>> = emptyMap()
    private var labels: Map<String, String> = emptyMap()
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
            onNodeHold = { uri -> openRhizome(uri) }
        }
        binding.mapCanvas.addView(
            map,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val pageDate = arguments?.getString(ARG_PAGE_DATE)?.takeIf { it.isNotBlank() }
        if (pageDate != null) {
            loadPageGraph(pageDate, arguments?.getString(ARG_PAGE_KEY).orEmpty())
        } else {
            loadGraph()
            focus = arguments?.getString(ARG_URI)?.takeIf { it.isNotBlank() } ?: busiest()
        }
        render()
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

        val pieces = day.imageElements.filter { it.page == pageKey && it.data.isNotBlank() && it.mediaKind.isBlank() }
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

    private fun loadGraph() {
        val ctx = requireContext()
        edges = ConnectionStore.loadAll(ctx).filter { !it.isDeleted }

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
        adjacency = adj
        labels = names
    }

    /** The most-connected node — the part of the map with something to show. */
    private fun busiest(): String =
        adjacency.maxByOrNull { it.value.distinct().size }?.key.orEmpty()

    private fun render() {
        if (adjacency.isEmpty() || focus.isBlank()) {
            map.setGraph(emptyList())
            binding.mapSubject.text = getString(R.string.map_empty)
            return
        }
        binding.mapSubject.text = labels[focus] ?: LedgerUri.describe(focus)
        map.setGraph(MindMap.layout(focus, adjacency) { labels[it] ?: LedgerUri.describe(it) })
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
            "🕸  My connections" to { loadGraph(); focus = busiest(); trail.clear(); render() },
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
        trail.clear()
        focus = root
        outline = markdown
        render()
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

    override fun showLoading() {}
    override fun hideLoading() {}
}
