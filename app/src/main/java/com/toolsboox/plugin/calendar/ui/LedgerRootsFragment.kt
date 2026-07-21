package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
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

    override val view = R.layout.fragment_ledger_roots

    private lateinit var binding: FragmentLedgerRootsBinding

    /** Held so a thread row can list its members without re-reading the whole ledger. */
    private var corpus: List<CorpusSnippet> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLedgerRootsBinding.bind(view)
        binding.rootsClose.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        val ctx = context ?: return
        val col = binding.rootsColumn
        col.removeAllViews()
        col.addView(TextView(ctx).apply {
            text = getString(R.string.roots_loading)
            textSize = 14f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(10), 0, 0)
        })

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = corpusService.gather(documentsRoot(), Spiral.SCOPE)
                        .filter { Spiral.isSubstantial(it.text) }
                        .let { Spiral.dedupe(it) { s -> s.text } }
                    val threads = Rhizome.threads(
                        all.map { it.text + " " + it.title }, all.map { it.date.time })
                    Triple(all, threads, Rhizome.crossings(threads))
                }.getOrNull()
            }
            if (!isAdded || result == null) return@launch
            val (all, threads, crossings) = result
            corpus = all
            col.removeAllViews()

            if (threads.isEmpty()) {
                col.addView(TextView(ctx).apply {
                    text = getString(R.string.roots_empty)
                    textSize = 14f; setTextColor(0xFF666666.toInt()); setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(10), 0, 0)
                })
                com.toolsboox.ot.ReadingSize.apply(col)
                return@launch
            }

            val now = System.currentTimeMillis()
            col.addView(header(getString(R.string.roots_what_comes_back), top = 4))

            for (t in threads.take(18)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(11), 0, dp(11))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(TextView(ctx).apply {
                    // A quiet thread is one worth picking back up, so it says so rather than
                    // merely sorting lower.
                    text = t.term + (if (t.isQuiet(now)) "   · gone quiet" else "")
                    textSize = 22f; setTextColor(0xFF000000.toInt())
                })
                row.addView(TextView(ctx).apply {
                    text = "${t.size} times" + (if (t.spanDays > 0) "  ·  across ${t.spanDays} days" else "")
                    textSize = 13f; setTextColor(0xFF777777.toInt())
                })
                row.setOnClickListener { showThread(t) }
                col.addView(row)
            }

            if (crossings.isNotEmpty()) {
                col.addView(header(getString(R.string.roots_where_they_touch), top = 18))
                // Most-connected first: the things holding the most threads together.
                for ((idx, terms) in crossings.entries.sortedByDescending { it.value.size }.take(10)) {
                    val snip = all.getOrNull(idx) ?: continue
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(13), 0, dp(13))
                        setBackgroundResource(android.R.drawable.list_selector_background)
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
                    col.addView(row)
                }
            }

            com.toolsboox.ot.ReadingSize.apply(col)
        }
    }

    private fun header(text: String, top: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f; setTextColor(0xFF777777.toInt()); letterSpacing = 0.08f
        setPadding(0, dp(top), 0, dp(8))
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
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🌿  " + thread.term)
            .setView(scroll)
            .setNegativeButton(getString(R.string.roots_close), null)
            .show()
    }

    // Reading the ledger is fast enough that a spinner would only flash; the column says
    // "Reading the roots…" until it has something, which is the same information without the churn.
    override fun showLoading() {}
    override fun hideLoading() {}
}
