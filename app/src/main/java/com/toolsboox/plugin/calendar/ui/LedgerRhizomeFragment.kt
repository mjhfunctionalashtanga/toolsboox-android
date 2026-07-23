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
 * A card the size of what it holds, floating on a dimmed page. Four connections filling a 13"
 * panel read as an error; the same four in a card the width of a paragraph read as a list. The
 * width is capped rather than fixed, so one layout is a card on a Tab X and simply the screen on
 * a Palma.
 *
 * One line per connection, ordered by how specific the relation is, with the relation itself in
 * faint grey at the end of its own row. No section headings and no grouping: these are
 * heterogeneous things — a contact beside a page beside a link — and nothing here is primary,
 * which is what keeps the page flat rather than a tree with the subject sitting at the root.
 * Direction is kept, since "came from this" and "this came from me" are different claims.
 *
 * Tap a row to go to the thing; hold it to walk to ITS rhizome, which is what holding means
 * everywhere else in the app. A walk REPLACES this page rather than stacking on it — there is one
 * rhizome page and it moves, so leaving is always one Close away.
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
        // Tapping the dimmed surround is the other way out, the way any card behaves.
        (view as? android.view.ViewGroup)?.setOnClickListener { findNavController().popBackStack() }

        sizeCard()
        render()
    }

    /**
     * A card the size of a paragraph, not the size of the panel.
     *
     * Capped rather than fixed, so the same layout is a card on a 13" Tab X and simply the width
     * of the screen on a Palma. Height stays wrap-content and is bounded only so a long list
     * scrolls inside the card instead of growing past the edges.
     */
    private fun sizeCard() {
        val dm = resources.displayMetrics
        val dp = dm.density
        val w = minOf((560 * dp).toInt(), dm.widthPixels - (32 * dp).toInt())
        binding.rhizomeCard.layoutParams =
            (binding.rhizomeCard.layoutParams as android.widget.FrameLayout.LayoutParams)
                .apply { width = w }
    }

    /**
     * Keep the card inside the panel once it knows how tall it wants to be.
     *
     * A ScrollView has no max height, so this waits for a real measurement and then gives the
     * list back only the room the card can spare — a long rhizome scrolls inside the card rather
     * than growing off the top and bottom of the screen.
     */
    private fun capHeight() {
        val card = binding.rhizomeCard
        card.post {
            if (!::binding.isInitialized || !isAdded) return@post
            val max = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            val excess = card.height - max
            if (excess > 0) {
                binding.rhizomeScroll.layoutParams = binding.rhizomeScroll.layoutParams.apply {
                    height = (binding.rhizomeScroll.height - excess).coerceAtLeast(1)
                }
                binding.rhizomeScroll.requestLayout()
            }
        }
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
            capHeight()
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
        capHeight()
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
        // The live object first, then whatever the edge recorded it was called, then the generic
        // description. "a task" is true and useless; the words someone actually wrote are the
        // whole reason to walk here.
        val name = when (ref?.scheme) {
            LedgerUri.SCHEME_CONTACT -> contacts[ref.body]?.name?.ifBlank { null }
            else -> null
        } ?: edge.otherLabel(uri).takeIf { it.isNotBlank() } ?: LedgerUri.describe(other)
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
