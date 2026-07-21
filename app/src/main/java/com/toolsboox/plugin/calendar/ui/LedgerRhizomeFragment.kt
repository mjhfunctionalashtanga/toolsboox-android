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
import com.toolsboox.plugin.calendar.CalendarNavigator
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
        // Four connections do not need four section headings and eight lines of card to say so.
        // The relation is a few faint words at the end of the row it belongs to; the row itself
        // is one line, because that is all a connection is.
        val ordered = edges.sortedWith(
            compareBy({ KIND_ORDER.indexOf(it.kind).let { i -> if (i < 0) KIND_ORDER.size else i } },
                { -it.updated })
        )
        for (edge in ordered) {
            val other = edge.otherEnd(uri) ?: continue
            column.addView(rowView(other, edge, contacts, px = ::px))
        }
        ReadingSize.apply(binding.rhizomeScroll)
    }

    /** One connection, one line: what it is, and in faint words how it is joined. */
    private fun rowView(
        other: String,
        edge: Connection,
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
        // Which way the edge points is a fact about the relation, so it is kept rather than
        // flattened away — "came from this" and "this came from me" are different things to know.
        val relation = if (edge.from == uri) heading(edge.kind).lowercase()
        else "${heading(edge.kind).lowercase()} this"

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, px(9), 0, px(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(ctx).apply {
                text = "$glyph  $name"
                textSize = 15f; setTextColor(0xFF000000.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = relation
                textSize = 11f; setTextColor(0xFF8A8A8A.toInt())
                setPadding(px(10), 0, 0, 0)
            })
            // Tap opens the thing; long-press walks to ITS rhizome. The same grammar as the rest
            // of the app, where long-press has always meant "tell me more about this". Tapping
            // "came from" and landing on another list of connections instead of the page itself
            // is the wrong answer to an obvious question.
            setOnClickListener { open(other, name) }
            setOnLongClickListener { walkTo(other, name); true }
        }
    }

    /**
     * Walk to the neighbour's own rhizome — REPLACING this page, not stacking on it.
     *
     * Stacking meant every step deepened the pile and Close had to be pressed once per step to
     * get back out, which turns a walk into a trap. There is one rhizome page and it moves.
     */
    private fun walkTo(other: String, label: String) {
        findNavController().navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(ARG_URI to other, ARG_LABEL to label),
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.LedgerRhizomeFragment, true)
                .build()
        )
    }

    /**
     * Go to the thing itself.
     *
     * An address that cannot be resolved says so and stays put, rather than navigating nowhere —
     * an edge is allowed to point at something this device can't open, or has never heard of.
     */
    private fun open(other: String, label: String) {
        val ref = LedgerUri.parse(other)
        if (ref == null) { showMessage(getString(R.string.rhizome_cannot_open, label), binding.root); return }
        when {
            ref.scheme == LedgerUri.SCHEME_LEDGER -> {
                val date = runCatching { java.time.LocalDate.parse(ref.date) }.getOrNull()
                if (date == null) { showMessage(getString(R.string.rhizome_cannot_open, label), binding.root); return }
                val page = ref.pageKey ?: "default"
                if (page == "default") CalendarNavigator.toDayPage(this, date)
                else CalendarNavigator.toDayNote(this, date, page)
            }
            ref.scheme == LedgerUri.SCHEME_CONTACT ->
                findNavController().navigate(R.id.action_to_rolodex)
            ref.scheme == LedgerUri.SCHEME_TASK ->
                findNavController().navigate(R.id.action_to_ledger_items)
            ref.isWeb -> runCatching {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(other)))
            }.onFailure { showMessage(getString(R.string.rhizome_cannot_open, label), binding.root) }
            else -> showMessage(getString(R.string.rhizome_cannot_open, label), binding.root)
        }
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
