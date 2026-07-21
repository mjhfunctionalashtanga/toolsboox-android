package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.databinding.FragmentLedgerMapBinding
import com.toolsboox.ot.LedgerUri
import com.toolsboox.ot.MindMap
import com.toolsboox.ot.MindMapView
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.ot.ConnectionStore
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
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
    }

    override val view = R.layout.fragment_ledger_map

    private lateinit var binding: FragmentLedgerMapBinding
    private lateinit var map: MindMapView

    private var edges: List<Connection> = emptyList()
    private var adjacency: Map<String, List<String>> = emptyMap()
    private var labels: Map<String, String> = emptyMap()
    private var focus: String = ""
    /** Where we have been, so Back walks the map rather than leaving it. */
    private val trail = ArrayDeque<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerMapBinding.bind(view)
        binding.mapTitle.text = getString(R.string.map_title)
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

        loadGraph()
        focus = arguments?.getString(ARG_URI)?.takeIf { it.isNotBlank() } ?: busiest()
        render()
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
