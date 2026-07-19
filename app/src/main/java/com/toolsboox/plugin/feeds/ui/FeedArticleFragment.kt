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

        // Tapping a link opens a menu (Open / Later / Copy); HOLDING now falls through to native
        // text selection, so you can highlight & copy passages — including a link's text.
        binding.articleWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView, request: android.webkit.WebResourceRequest
            ): Boolean {
                val url = request.url?.toString().orEmpty()
                if (url.startsWith("http")) { showLinkMenu(url); return true }
                return false
            }
        }

        // Floating nav pill: grip drags/collapses; ‹ › page, ⌃ ⌄ step articles, ✎ annotate.
        makeDraggable(binding.artGrip, binding.artPill, "article")
        applyArticlePillOrientation()
        binding.artMenu.setOnClickListener { openRssDirectory() }
        binding.artToday.setOnClickListener {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_calendar_day)
        }
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

    /** Horizontal ⇄ vertical pill, per the wrench toggle (persisted). */
    private fun applyArticlePillOrientation() {
        (binding.artPill as? android.widget.LinearLayout)?.orientation =
            if (navPrefs().getBoolean("article_pill_vertical", false)) android.widget.LinearLayout.VERTICAL
            else android.widget.LinearLayout.HORIZONTAL
    }
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

    /**
     * ☰ pulls up the directory as an overlay — WITHOUT leaving the article. Picking a feed view
     * returns to the list in that mode; the Ledger sections (almanac/history/…) navigate away.
     */
    /** Link tapped in the article → Open / Add to Later / Copy (moved off long-press so holding can select). */
    private fun showLinkMenu(url: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(url)
            .setItems(arrayOf("🌐  Open", "🔖  Add to Later List", "📋  Copy link")) { _, which ->
                when (which) {
                    0 -> startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    1 -> {
                        com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                            requireContext(), java.time.LocalDate.now(), "read", url, null)
                        showMessage("Saved to Later List", binding.root)
                    }
                    2 -> {
                        val cb = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                        showMessage("Link copied", binding.root)
                    }
                }
            }.show()
    }

    /** ☰ from the article: land back on the feeds list with THE feeds directory open — one
     *  directory implementation (FeedsFragment.showFeedDirectory), one look, everywhere. */
    private fun openRssDirectory() {
        FeedSelection.openDirectoryOnArrival = true
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

    /** Long-press ☰: browser, read-aloud, and reader settings (tap-zone / volume page-turn). */
    private fun showArticleMenu() {
        val tapOn = tapZonesOn(); val volOn = volumeTurnOn()
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        // Read-aloud is a small state machine: idle → Read; speaking → Pause + Player + Stop; paused → Resume + Stop.
        val readItems: List<Pair<String, () -> Unit>> = when {
            player.isSpeaking -> listOf(
                "⏸  Pause reading" to { player.toggle() },
                "🎛  Player…" to { player.showModal(requireContext()) },
                "⏹  Stop reading" to { player.stop() })
            player.isPaused -> listOf(
                "▶  Resume reading" to { player.toggle() },
                "⏹  Stop reading" to { player.stop() })
            else -> listOf("🔊  Read aloud" to { readAloud() })
        }
        val fixed: List<Pair<String, () -> Unit>> = listOf(
            "🌐  Open in browser" to {
                entry?.url?.takeIf { it.isNotBlank() }?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }; Unit
            },
            ((if (tapOn) "☑" else "☐") + "  Tap-zone paging") to { navPrefs().edit().putBoolean("tap_zones", !tapOn).apply(); setupTapZones() },
            ((if (volOn) "☑" else "☐") + "  Volume page-turn") to { navPrefs().edit().putBoolean("volume_turn", !volOn).apply(); Unit },
            "🔄  Rotate screen" to {
                val a = requireActivity()
                a.requestedOrientation =
                    if (a.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            },
            "↕  Flip pill layout" to {
                val v = !navPrefs().getBoolean("article_pill_vertical", false)
                navPrefs().edit().putBoolean("article_pill_vertical", v).apply()
                applyArticlePillOrientation()
            }
        )
        val all = fixed.take(1) + readItems + fixed.drop(1)
        showIconMenu(null, all)
    }

    /** Read the article aloud via the process-wide player (keeps playing after you leave). */
    private fun readAloud() {
        val e = entry
        binding.articleWeb.evaluateJavascript(
            "(document.body && document.body.innerText) || ''"
        ) { raw ->
            val player = com.toolsboox.ui.plugin.LedgerPlayer
            player.start(requireContext(), e?.title, e?.feedTitle?.ifBlank { null }, e?.imageUrl, unquoteJs(raw))
            player.showModal(requireContext())
        }
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

    /** Filled star when starred, outline when not. */
    private fun updateStar() {
        binding.artStar.setImageResource(if (entry?.starred == true) R.drawable.ic_starred else R.drawable.ic_star)
    }

    /** Make the current view obvious: the reader-view icon shows the active-tool chip while in
     *  the parsed / full-article view, and no chip on the feed content. */
    private fun updateParse() {
        binding.artParse.setBackgroundResource(if (parsed) R.drawable.tool_active_bg else android.R.color.transparent)
    }

    /** Render an entry (feed content), refresh the star glyph, mark it read on the server. */
    private fun showEntry(e: FeedEntry) {
        entry = e
        parsed = false
        binding.articleWeb.loadDataWithBaseURL(articleBaseUrl(e), buildHtml(e, e.content), "text/html", "UTF-8", null)
        updateStar()
        updateParse()
        // A content-less entry (opened from a Stars & Events row, where only the URL rides along)
        // fetches its readable text immediately — no blank page, no manual ¶ tap.
        if (e.content.isBlank() && e.url.startsWith("http")) toggleParse()
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        // Synthetic entries (id=0) aren't on the server — nothing to mark read.
        if (url.isNotBlank() && token.isNotBlank() && e.id != 0L) {
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
            binding.articleWeb.loadDataWithBaseURL(articleBaseUrl(e), buildHtml(e, e.content), "text/html", "UTF-8", null)
            updateParse()
            return
        }
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        showMessage(R.string.feeds_article_parsing)
        lifecycleScope.launch {
            // A synthetic entry (id=0, opened from a Stars & Events row) isn't on the server —
            // Miniflux would 400 on its id. Fetch + readability-strip the page directly instead.
            val res = withContext(Dispatchers.IO) {
                if (e.id == 0L) miniflux.fetchPageReadable(e.url)
                else miniflux.fetchContent(url, token, e.id)
            }
            when (res) {
                is MinifluxClient.Result.Ok -> {
                    parsed = true
                    binding.articleWeb.loadDataWithBaseURL(articleBaseUrl(e), buildHtml(e, res.value.ifBlank { e.content }), "text/html", "UTF-8", null)
                    updateParse()
                }
                is MinifluxClient.Result.Err -> showMessage("⚠️ " + res.message)
            }
        }
    }

    /** A real https baseUrl gives the WebView document a valid origin/referer. A null baseUrl leaves
     *  embeds on an opaque origin, which makes YouTube fail with "error 150" (embedding not allowed). */
    private fun articleBaseUrl(e: FeedEntry): String =
        e.url.takeIf { it.startsWith("http", ignoreCase = true) } ?: "https://www.youtube.com"

    /**
     * YouTube refuses many embeds in a WebView (error 150/152 — embedding disabled), leaving a
     * dead player you can't even tap. Rewrite every YouTube iframe into a tappable thumbnail card
     * that links to the watch page — the link menu / external open handles it from there.
     */
    private fun rewriteYouTubeEmbeds(content: String): String {
        val iframe = Regex(
            """<iframe[^>]*src=["'][^"']*(?:youtube(?:-nocookie)?\.com/embed/|youtu\.be/)([A-Za-z0-9_\-]{6,})[^"']*["'][^>]*>\s*</iframe>""",
            RegexOption.IGNORE_CASE
        )
        return iframe.replace(content) { m ->
            val id = m.groupValues[1]
            """<a href="https://www.youtube.com/watch?v=$id" style="display:block;text-decoration:none">
               <img src="https://img.youtube.com/vi/$id/hqdefault.jpg" style="width:100%;display:block"/>
               <span style="display:block;padding:8px 0;font-weight:bold;color:#000">▶ Watch on YouTube</span>
               </a>"""
        }
    }

    private fun buildHtml(e: FeedEntry, rawContent: String): String {
        val content = rewriteYouTubeEmbeds(rawContent)
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
              iframe { max-width: 100%; border: 0; }
              @supports (aspect-ratio: 16 / 9) { iframe { width: 100%; height: auto; aspect-ratio: 16 / 9; } }
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
                if (!wasStarred) logEvent(e, note = e.blurb.ifBlank { null }, starred = true)
            }
            entry = e.copy(starred = !wasStarred)
            updateStar()
            showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
        }
    }

    /** Highlight → annotate: read the current selection, then open the shared capture menu
     *  (note / photo / upload / voice), mirroring the book reader. Works with no selection. */
    private fun annotate(e: FeedEntry) {
        binding.articleWeb.evaluateJavascript(
            "(function(){var s=window.getSelection&&window.getSelection();return s?s.toString():'';})()"
        ) { raw ->
            captureAnnotation(unquoteJs(raw).trim(), e.title) { selection, note, attachment ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { logEvent(e, excerpt = selection, note = note, attachment = attachment) }
                }
            }
        }
    }

    /** Decode the JSON string evaluateJavascript hands back (quoted + escaped). */
    private fun unquoteJs(raw: String): String {
        if (raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?: raw.removeSurrounding("\"")
    }

    /** Append an article ReadingEvent (star marker, highlight passage, note, and/or media). */
    private fun logEvent(
        e: FeedEntry, excerpt: String? = null, note: String? = null,
        attachment: com.toolsboox.da.Attachment? = null, starred: Boolean = false
    ) {
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
                    note = note,
                    attachments = attachment?.let { mutableListOf(it) },
                    starred = starred
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
