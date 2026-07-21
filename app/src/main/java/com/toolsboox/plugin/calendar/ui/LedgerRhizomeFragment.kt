package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.databinding.FragmentLedgerRhizomeBinding
import com.toolsboox.ot.LedgerUri
import com.toolsboox.ot.ReadingSize
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.ot.ConnectionStore
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * One object's rhizome: everything it joins, entered from the object itself.
 *
 * Columns are KINDS OF CONNECTION rather than stages. The card look is borrowed from the boards
 * because it reads well on e-ink, but the kanban grammar is not: these are heterogeneous things —
 * a contact next to a page next to a link — and they are not moving through anything. No group is
 * primary, which is what keeps this flat instead of a tree with the subject at the root.
 *
 * Tapping a card walks to THAT object's rhizome rather than opening it. Every entry point leads
 * to every other; the walk is the point, and Back is the way home.
 */
@AndroidEntryPoint
class LedgerRhizomeFragment @Inject constructor() : ScreenFragment() {

    companion object {
        const val ARG_URI = "rhizome_uri"
        const val ARG_LABEL = "rhizome_label"

        /** The order groups are shown in — most specific relation first, loosest last. */
        private val KIND_ORDER = listOf(
            Connection.SOURCE, Connection.ASSIGNED, Connection.PLACED,
            Connection.MENTIONS, Connection.ABOUT
        )

        private fun heading(kind: String): String = when (kind) {
            Connection.SOURCE -> "Came from"
            Connection.ASSIGNED -> "Whose it is"
            Connection.PLACED -> "Placed on"
            Connection.MENTIONS -> "Mentions"
            Connection.ABOUT -> "About"
            else -> kind.replaceFirstChar { it.uppercase() }
        }
    }

    override val view = R.layout.fragment_ledger_rhizome

    private lateinit var binding: FragmentLedgerRhizomeBinding
    private var uri: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerRhizomeBinding.bind(view)

        uri = arguments?.getString(ARG_URI).orEmpty()
        val label = arguments?.getString(ARG_LABEL).orEmpty()

        binding.rhizomeTitle.text = getString(R.string.rhizome_title)
        binding.rhizomeSubject.text = label.ifBlank { LedgerUri.describe(uri) }
        binding.rhizomeClose.setOnClickListener { findNavController().popBackStack() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // An edge may have been made from somewhere else while this page sat in the back stack.
        if (::binding.isInitialized) render()
    }

    private fun render() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val column = binding.rhizomeColumn
        column.removeAllViews()

        val edges = if (uri.isBlank()) emptyList() else ConnectionStore.touching(ctx, uri)
        if (edges.isEmpty()) {
            column.addView(TextView(ctx).apply {
                // Say what would make one rather than just reporting a zero.
                text = getString(R.string.rhizome_empty)
                textSize = 14f; setTextColor(0xFF666666.toInt()); setPadding(0, px(18), 0, 0)
            })
            ReadingSize.apply(binding.rhizomeScroll)
            return
        }

        val contacts = runCatching { ContactStore.loadAll(ctx).associateBy { it.id } }.getOrNull().orEmpty()
        val grouped = edges.groupBy { it.kind }
        val kinds = KIND_ORDER.filter { grouped.containsKey(it) } + grouped.keys.filterNot { it in KIND_ORDER }

        for (kind in kinds) {
            val group = grouped[kind].orEmpty()
            column.addView(TextView(ctx).apply {
                text = "${heading(kind).uppercase()}  ·  ${group.size}"
                textSize = 11f; letterSpacing = 0.08f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, px(16), 0, px(6))
            })
            for (edge in group) {
                val other = edge.otherEnd(uri) ?: continue
                column.addView(cardView(other, edge, contacts.keys, contacts, px = ::px))
            }
        }
        ReadingSize.apply(binding.rhizomeScroll)
    }

    /** A board card, borrowed for its look: compact, high contrast, one line of meaning below. */
    private fun cardView(
        other: String,
        edge: Connection,
        @Suppress("UNUSED_PARAMETER") contactIds: Set<String>,
        contacts: Map<String, com.toolsboox.plugin.calendar.da.v2.Contact>,
        px: (Int) -> Int
    ): View {
        val ctx = requireContext()
        val ref = LedgerUri.parse(other)
        val name = when (ref?.scheme) {
            LedgerUri.SCHEME_CONTACT -> contacts[ref.body]?.name?.ifBlank { null } ?: "Unnamed"
            else -> LedgerUri.describe(other)
        }
        val glyph = when (ref?.scheme) {
            LedgerUri.SCHEME_LEDGER -> if (ref.isElement) "✒" else "📆"
            LedgerUri.SCHEME_TASK -> "🃏"
            LedgerUri.SCHEME_CONTACT -> "👤"
            LedgerUri.SCHEME_CLIPPING -> "🖼"
            LedgerUri.SCHEME_BOOK -> "📖"
            else -> if (ref?.isWeb == true) "🌐" else "↪"
        }
        // Which way the edge points is a fact about the relation, so it is shown rather than
        // flattened away — "came from this" and "this came from me" are different things to know.
        val direction = if (edge.from == uri) "→" else "←"

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, px(8)) }
            addView(TextView(ctx).apply {
                text = "$glyph  $name"
                textSize = 13f; setTextColor(0xFF000000.toInt())
            })
            addView(TextView(ctx).apply {
                text = listOfNotNull(
                    "$direction ${edge.kind}",
                    edge.note.takeIf { it.isNotBlank() }
                ).joinToString("   ·   ")
                textSize = 11f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(3), 0, 0)
            })
            setOnClickListener { walkTo(other, name) }
        }
    }

    /** Walk to the neighbour's own rhizome — the same page, one step along. */
    private fun walkTo(other: String, label: String) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(ARG_URI to other, ARG_LABEL to label)
        )
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
