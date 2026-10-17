package com.toolsboox.plugin.calendar.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.toolsboox.R
import com.toolsboox.plugin.calendar.ot.LedgerYear
import com.toolsboox.plugin.calendar.ot.YearInLedgerLoader
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 📊 **Your year in the Ledger** — the review page. Reads what the owner has already logged and
 * shows it as a scrolling column of "big number + one line of plain words", a Top section
 * (authors · feeds · correspondents), and a Share… that hands the same content to the share
 * sheet as plain text. Numbers come from [LedgerYear], the engine both forks share word for word;
 * this fragment is only the gathering ([YearInLedgerLoader], off-main) and the page.
 *
 * Quiet e-ink voice: black on white, no colour carries meaning, no chart junk. The one picture
 * is the six text-sized block bars under the busiest hour, set in the monospace face so they line
 * up. Rides the shared semantic-surface frame (Almanac strip · title · scrolling column).
 */
@AndroidEntryPoint
class YearInLedgerFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    override val view = R.layout.fragment_semantic_surface

    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private var navBar: SemanticNavBar? = null
    private var span = LedgerYear.Span.YEAR
    private var review: LedgerYear.Review? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.semantic_title).text = "📊 ${LedgerYear.TITLE}"
        column = view.findViewById(R.id.semantic_column)
        scroll = view.findViewById(R.id.semantic_scroll)
        navBar = SemanticNavBar(this, view.findViewById(R.id.semantic_navigator),
            calendarDayService, calendarPatternService) { documentsRoot() }
        // The header ☰ and Close retire into the rail (☰ Hub is the same door; Close only
        // repeated the system back gesture). Share… stays in the header as the page's one verb.
        view.findViewById<android.widget.ImageButton>(R.id.semantic_hub_button).visibility = View.GONE
        view.findViewById<TextView>(R.id.semantic_close).visibility = View.GONE
        view.findViewById<TextView>(R.id.semantic_action).apply {
            text = "Share…"
            visibility = View.VISIBLE
            setOnClickListener { share() }
        }
        setupActionRail(
            view.findViewById(R.id.semantic_rail), "year_in_ledger",
            actions = { listOf(
                com.toolsboox.ot.TuckPanel.Item(0, "Share", glyph = "⬆") { share() }
            ) }
        )
        load()
    }

    private fun load() {
        val ctx = context?.applicationContext ?: return
        val s = span
        YearInLedgerLoader.cached(s)?.let { review = it; render(); return }
        review = null
        render(loading = true)
        // The view's scope: the count exists only to fill this column, so back-navigation cancels
        // it instead of ghost-rendering into a dead view. A year is a few hundred day files — IO.
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { YearInLedgerLoader.review(ctx, calendarDayService, documentsRoot(), s) }.getOrNull()
            }
            if (!isAdded || span != s) return@launch
            review = r
            render()
        }
    }

    private fun render(loading: Boolean = false) {
        val ctx = context ?: return
        column.removeAllViews()
        column.addView(spanRow())
        val r = review
        if (r == null) {
            if (loading) column.addView(hint("Reading the ledger…"))
            com.toolsboox.ot.ReadingSize.apply(scroll)
            return
        }
        column.addView(TextView(ctx).apply {
            text = "${r.span.label} · ${LedgerYear.rangeText(r)}"
            textSize = 13f; setTextColor(0xFF666666.toInt()); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })
        if (r.isEmpty) {
            column.addView(hint(LedgerYear.EMPTY_WORDS))
            com.toolsboox.ot.ReadingSize.apply(scroll)
            return
        }
        for (s in r.stats) column.addView(statRow(s))
        topSection(LedgerYear.TOP_AUTHORS_TITLE, r.topAuthors) { LedgerYear.plural(it, "highlight", "highlights") }
        topSection(LedgerYear.TOP_FEEDS_TITLE, r.topFeeds) { LedgerYear.plural(it, "mark", "marks") }
        topSection(LedgerYear.TOP_CORRESPONDENTS_TITLE, r.topCorrespondents) { LedgerYear.plural(it, "letter", "letters") }
        column.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(24)) })
        com.toolsboox.ot.ReadingSize.apply(scroll)
    }

    /** Week · Month · Year · All — the chosen one solid black, the rest outlined (no colour). */
    private fun spanRow(): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        }
        for (s in LedgerYear.Span.values()) {
            val chip = com.toolsboox.ot.SemanticCards.actionChip(ctx, s.title) {
                if (span != s) { span = s; load() }
            }
            if (s == span) {
                chip.setTextColor(Color.WHITE)
                chip.setBackgroundColor(Color.BLACK)
                chip.setTypeface(null, Typeface.BOLD)
            }
            row.addView(chip)
        }
        return row
    }

    private fun statRow(s: LedgerYear.Stat): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(4))
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        }
        line.addView(TextView(ctx).apply {
            text = s.number
            textSize = 34f; setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        line.addView(TextView(ctx).apply {
            text = s.words
            textSize = 15f; setTextColor(0xFF555555.toInt())
            setPadding(dp(12), 0, 0, dp(5))
        })
        box.addView(line)
        s.detail?.let { d ->
            box.addView(TextView(ctx).apply {
                text = d
                textSize = 12f; setTextColor(0xFF666666.toInt())
                if (s.id == "busiestHour") { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.15f) }
                setPadding(0, dp(if (s.id == "busiestHour") 8 else 2), 0, 0)
            })
        }
        return box
    }

    private fun topSection(title: String, rows: List<LedgerYear.Ranked>, unit: (Int) -> String) {
        if (rows.isEmpty()) return
        val ctx = requireContext()
        column.addView(TextView(ctx).apply {
            text = title.uppercase()
            textSize = 12f; setTextColor(0xFF777777.toInt()); letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(28), 0, dp(6))
        })
        rows.forEachIndexed { i, row ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(TextView(ctx).apply {
                text = "${i + 1}."
                textSize = 15f; setTextColor(0xFF777777.toInt())
                minWidth = dp(26)
            })
            line.addView(TextView(ctx).apply {
                text = row.name
                textSize = 15f; setTextColor(Color.BLACK); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(ctx).apply {
                text = unit(row.count)
                textSize = 13f; setTextColor(0xFF666666.toInt())
                setPadding(dp(8), 0, 0, 0)
            })
            column.addView(line)
        }
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, dp(12), 0, 0)
    }

    /** The same content as plain text, to whatever wants it — a note, a message, the clipboard. */
    private fun share() {
        val r = review ?: return
        val text = LedgerYear.shareText(r)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, LedgerYear.TITLE)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share your year"))
    }

    // The page says "Reading the ledger…" in its own column while it counts; the frame's
    // spinner hooks have nothing to add.
    override fun showLoading() {}
    override fun hideLoading() {}
}
