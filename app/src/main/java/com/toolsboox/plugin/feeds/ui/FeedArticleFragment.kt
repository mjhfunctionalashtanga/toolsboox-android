package com.toolsboox.plugin.feeds.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import com.toolsboox.databinding.FragmentFeedArticleBinding
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.plugin.feeds.nw.MinifluxClient
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Reads one feed entry (Miniflux content) in a WebView, with Star / Note / open-in-browser.
 * Star and Note both write a ReadingEvent(article) into today's CalendarDay so the article
 * (and any note) joins the shared corpus + sync — mirroring the book reader's highlights.
 */
@AndroidEntryPoint
class FeedArticleFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var miniflux: MinifluxClient

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_feed_article

    private lateinit var binding: FragmentFeedArticleBinding
    private var entry: FeedEntry? = null
    private var parsed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedArticleBinding.bind(view)

        val e = FeedSelection.entry
        if (e == null) {
            showMessage(R.string.feeds_article_missing)
            return
        }

        // JS on so we can read the text selection for highlight-to-annotation.
        binding.articleWeb.settings.javaScriptEnabled = true

        // Floating nav pill: grip drags/collapses; ‹ › page, ⌃ ⌄ step articles, ✎ annotate.
        makeDraggable(binding.artGrip, binding.artPill, "article")
        binding.artMenu.setOnClickListener { openRssDirectory() }
        binding.artMenu.setOnLongClickListener { showArticleMenu(); true }
        binding.artStar.setOnClickListener { entry?.let { star(it) } }
        binding.artParse.setOnClickListener { toggleParse() }
        binding.artPageUp.setOnClickListener { pageWeb(false) }
        binding.artPageDown.setOnClickListener { pageWeb(true) }
        binding.artPrev.setOnClickListener { goToNeighbor(-1) }
        binding.artNext.setOnClickListener { goToNeighbor(1) }
        binding.artAnnotate.setOnClickListener { entry?.let { annotate(it) } }
        setupTapZones()

        showEntry(e)
    }

    // --- Capy-style navigation: tap zones + volume keys, both toggle-able ---

    private fun navPrefs() = requireContext().getSharedPreferences("ledger_reader_nav", 0)
    private fun tapZonesOn() = navPrefs().getBoolean("tap_zones", false)
    private fun volumeTurnOn() = navPrefs().getBoolean("volume_turn", false)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapZones() {
        binding.tapZones.visibility = if (tapZonesOn()) View.VISIBLE else View.GONE
        binding.tapLeft.setOnTouchListener(MultiTap { onTapZone(right = false, count = it) })
        binding.tapRight.setOnTouchListener(MultiTap { onTapZone(right = true, count = it) })
    }

    /** 1 tap = page, 2 = prev/next article, 3 = feed-list drawer (L) / next feed (R). */
    private fun onTapZone(right: Boolean, count: Int) {
        when (count) {
            1 -> pageWeb(right)
            2 -> goToNeighbor(if (right) 1 else -1)
            else -> if (right) nextFeed() else findNavController().popBackStack()
        }
    }

    /** Page the article a screenful — instantly (e-ink: the next part just appears, no scroll). */
    private fun pageWeb(down: Boolean) {
        val step = (binding.articleWeb.height * 9 / 10).coerceAtLeast(1)
        binding.articleWeb.scrollBy(0, if (down) step else -step)
    }

    /** ☰ pulls up the RSS directory — pop back to the feed list and open it there. */
    private fun openRssDirectory() {
        FeedSelection.openDirectory = true
        findNavController().popBackStack()
    }

    /** Jump to the first article of the next feed source in the list. */
    private fun nextFeed() {
        val cur = entry ?: return
        val list = FeedSelection.list
        val i = list.indexOfFirst { it.id == cur.id }
        val next = if (i >= 0) list.drop(i + 1).firstOrNull { it.feedTitle != cur.feedTitle } else null
        if (next != null) showEntry(next) else showMessage(R.string.feeds_article_last)
    }

    /** Counts quick taps (ignoring drags) and fires the total after a short window. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class MultiTap(val onCount: (Int) -> Unit) : View.OnTouchListener {
        private var count = 0
        private var downX = 0f; private var downY = 0f
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val fire = Runnable { if (count > 0) onCount(count); count = 0 }
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
                android.view.MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(e.x - downX) < 40 && kotlin.math.abs(e.y - downY) < 40) {
                        count++; handler.removeCallbacks(fire); handler.postDelayed(fire, 320)
                    }
                    return true
                }
            }
            return false
        }
    }

    /** The top ☰ hamburger: browser + reader settings (tap-zone / volume page-turn). */
    private fun showArticleMenu() {
        val tapOn = tapZonesOn(); val volOn = volumeTurnOn()
        val items = listOf(
            "🌐  Open in browser",
            (if (tapOn) "☑" else "☐") + "  Tap-zone paging",
            (if (volOn) "☑" else "☐") + "  Volume page-turn"
        )
        AlertDialog.Builder(requireContext())
            .setItems(items.toTypedArray()) { d, which ->
                when (which) {
                    0 -> entry?.url?.takeIf { it.isNotBlank() }?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    1 -> { navPrefs().edit().putBoolean("tap_zones", !tapOn).apply(); setupTapZones() }
                    2 -> navPrefs().edit().putBoolean("volume_turn", !volOn).apply()
                }
                d.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Volume-key page turn (opt-in), routed through the host activity.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = handler@{ up ->
            if (!volumeTurnOn()) return@handler false
            pageWeb(!up)
            true
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null
    }

    /** ★ filled when starred, ☆ outline when not. */
    private fun updateStar() {
        binding.artStar.text = if (entry?.starred == true) "★" else "☆"
    }

    /** Make the current view obvious: the parse button is highlighted while in reader (parsed)
     *  view; its glyph flips ⛶ (feed content) ↔ ▤ (parsed / full article). */
    private fun updateParse() {
        binding.artParse.text = if (parsed) "▤" else "⛶"
        binding.artParse.setBackgroundResource(if (parsed) R.drawable.tool_active_bg else 0)
    }

    /** Render an entry (feed content), refresh the star glyph, mark it read on the server. */
    private fun showEntry(e: FeedEntry) {
        entry = e
        parsed = false
        binding.articleWeb.loadDataWithBaseURL(null, buildHtml(e, e.content), "text/html", "UTF-8", null)
        updateStar()
        updateParse()
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        if (url.isNotBlank() && token.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) { miniflux.markRead(url, token, e.id) }
        }
    }

    /** Step to the previous/next article in the list the reader was opened from. */
    private fun goToNeighbor(delta: Int) {
        val cur = entry ?: return
        val list = FeedSelection.list
        val i = list.indexOfFirst { it.id == cur.id }
        val ni = i + delta
        if (i >= 0 && ni in list.indices) showEntry(list[ni])
        else showMessage(if (delta > 0) R.string.feeds_article_last else R.string.feeds_article_first)
    }

    /** Toggle Miniflux's readability parse (full original content) vs the feed's content. */
    private fun toggleParse() {
        val e = entry ?: return
        if (parsed) {
            parsed = false
            binding.articleWeb.loadDataWithBaseURL(null, buildHtml(e, e.content), "text/html", "UTF-8", null)
            updateParse()
            return
        }
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        showMessage(R.string.feeds_article_parsing)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { miniflux.fetchContent(url, token, e.id) }
            when (res) {
                is MinifluxClient.Result.Ok -> {
                    parsed = true
                    binding.articleWeb.loadDataWithBaseURL(null, buildHtml(e, res.value.ifBlank { e.content }), "text/html", "UTF-8", null)
                    updateParse()
                }
                is MinifluxClient.Result.Err -> showMessage("⚠️ " + res.message)
            }
        }
    }

    private fun buildHtml(e: FeedEntry, content: String): String {
        val meta = listOf(e.feedTitle, e.author ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html { padding: 0 44px; }
              body { margin: 40px auto; color: #000; background: #fff;
                     font-family: serif; font-size: 19px; line-height: 1.65;
                     max-width: 34em; }
              h1 { font-size: 26px; line-height: 1.25; }
              .meta { color: #666; font-size: 14px; margin-bottom: 18px; }
              img { max-width: 100%; height: auto; }
              a { color: #000; }
              pre, code { white-space: pre-wrap; }
            </style></head><body>
            <h1>${escape(e.title)}</h1>
            <div class="meta">${escape(meta)}</div>
            $content
            </body></html>
        """.trimIndent()
    }

    private fun star(e: FeedEntry) {
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        val wasStarred = e.starred
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                miniflux.toggleStar(url, token, e.id)
                if (!wasStarred) logEvent(e, note = e.blurb.ifBlank { null })
            }
            entry = e.copy(starred = !wasStarred)
            updateStar()
            showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
        }
    }

    /** Highlight → annotate: read the current selection, then save it (+ an optional note)
     *  as an article annotation, mirroring the book reader. Works with no selection too
     *  (a plain note on the article). */
    private fun annotate(e: FeedEntry) {
        binding.articleWeb.evaluateJavascript(
            "(function(){var s=window.getSelection&&window.getSelection();return s?s.toString():'';})()"
        ) { raw -> showAnnotateDialog(e, unquoteJs(raw).trim()) }
    }

    private fun showAnnotateDialog(e: FeedEntry, selection: String) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.feeds_article_note_hint); setLines(3); gravity = android.view.Gravity.TOP
        }
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(if (selection.isNotBlank()) R.string.feeds_article_highlight else R.string.feeds_article_note)
            .setView(input)
            .setPositiveButton(R.string.feeds_save) { _, _ ->
                val text = input.text.toString().trim()
                if (selection.isBlank() && text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        logEvent(e, excerpt = selection.ifBlank { null }, note = text.ifBlank { null })
                    }
                    showMessage(if (selection.isNotBlank()) R.string.reader_highlight_saved else R.string.feeds_note_saved)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (selection.isNotBlank()) builder.setMessage("“${selection.take(400)}”")
        builder.show()
    }

    /** Decode the JSON string evaluateJavascript hands back (quoted + escaped). */
    private fun unquoteJs(raw: String): String {
        if (raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?: raw.removeSurrounding("\"")
    }

    /** Append an article ReadingEvent (star marker, highlight passage, and/or a note). */
    private fun logEvent(e: FeedEntry, excerpt: String? = null, note: String? = null) {
        try {
            val root = documentsRoot()
            val today = LocalDate.now()
            val day = calendarDayService.load(root, today, null, Locale.getDefault())
            day.readingEvents.add(
                ReadingEvent(
                    id = "art-${e.id}-${UUID.randomUUID().toString().take(8)}",
                    kind = ReadingEvent.Kind.ARTICLE,
                    date = Date(),
                    title = e.title,
                    source = e.feedTitle.ifBlank { null },
                    url = e.url.ifBlank { null },
                    excerpt = excerpt,
                    note = note
                )
            )
            calendarDayService.save(root, today, day)
        } catch (ex: Exception) {
            Timber.w(ex, "failed to log article event")
        }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    private fun prefs() = EncryptedSharedPreferences.create(
        requireContext(), FeedsFragment.PREFS,
        MasterKey.Builder(requireContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun showLoading() {}
    override fun hideLoading() {}

    override fun onDestroyView() {
        if (::binding.isInitialized) binding.articleWeb.destroy()
        super.onDestroyView()
    }
}
