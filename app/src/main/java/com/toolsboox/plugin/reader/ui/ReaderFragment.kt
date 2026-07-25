package com.toolsboox.plugin.reader.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.toolsboox.R
import com.toolsboox.databinding.FragmentReaderBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * The Ledger reader — foliate-js in a WebView, mirroring the iOS reader: the same
 * `foliate/` bundle is served over a same-origin host via request interception, a small
 * shim maps foliate's `window.webkit.messageHandlers.reader` bridge onto an Android
 * `@JavascriptInterface`, and highlights are logged as `ReadingEvent(kind=book)` into
 * today's `CalendarDay` so they join the shared corpus + sync.
 */
@AndroidEntryPoint
class ReaderFragment @Inject constructor() : ScreenFragment(), com.toolsboox.ui.plugin.ReturnAnchorProvider {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    /** If a book is open, stash a return anchor so the Day page can jump straight back to it. The
     *  reader restores the last book on its own (KEY_BOOK), so only the nav action + label are needed. */
    override fun prepareReturnAnchor() {
        val f = currentBookFile
        if (f != null) {
            com.toolsboox.ui.plugin.LedgerReturn.set(R.id.action_to_reader, bookTitle.ifBlank { f.nameWithoutExtension })
        } else {
            com.toolsboox.ui.plugin.LedgerReturn.clear()
        }
    }

    override val view = R.layout.fragment_reader

    private lateinit var binding: FragmentReaderBinding

    /** The book currently served at https://ledger.reader/book/current. */
    @Volatile
    private var currentBookFile: File? = null

    private var bookReady = false
    private var pendingOpen = false
    /** Relocates to swallow: foliate's start-of-book report + each programmatic goToCfi
     *  jump fires one, and stamping those would overwrite the saved spot with a stale CFI
     *  (fresh timestamp) — which then out-merged genuinely newer cross-device progress. */
    private var skipRelocates = 0
    /** The foliate engine has posted "ready" — `window.openBookURL` exists. Opening a book before
     *  this is what made the FIRST import fail (engine still loading) and work on the retry. */
    private var engineReady = false
    private var bookTitle = ""
    private var bookAuthor = ""
    /** Where the reader is standing, refreshed on every relocate. A mark can only learn its
     *  chapter and its place in the book at the moment it is made — see [BookNote.chapter]. */
    private var currentCfi = ""
    private var currentChapter = ""
    private var currentFraction = 0.0
    private var bookCover: android.graphics.Bitmap? = null

    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importAndOpen(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentReaderBinding.bind(view)

        val web = binding.readerWeb
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidReaderBridge")
        // Route foliate's iOS-style postMessage onto our bridge, before page scripts run.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, SHIM_JS, setOf(ORIGIN))
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                // Belt-and-suspenders shim if document-start isn't supported.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(SHIM_JS, null)
                }
            }
        }

        binding.prevButton.setOnClickListener { web.evaluateJavascript("window.pageLeft && window.pageLeft()", null) }
        binding.nextButton.setOnClickListener { web.evaluateJavascript("window.pageRight && window.pageRight()", null) }
        binding.highlightButton.setOnClickListener {
            web.evaluateJavascript("window.highlightSelection && window.highlightSelection()", null)
        }
        // Wrench in the bottom bar → the book's controls modal (shelf/import/read-aloud/rotate/turns).
        binding.openButton.setOnClickListener { showReaderControls() }
        // Sunshine brings up the section nav (selection) rather than jumping straight back.
        binding.todayButton.setOnClickListener { showLedgerDirectory() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.gotoButton.setOnClickListener { showBookDirectory() }
        // Drag to move; tap the grip to fold/turn — the SHARED four-state pill (wide open →
        // wide folded → tall open → tall folded), replacing the reader's private collapse.
        // The reader was the one surface whose pill could never turn vertical, and vertical is
        // exactly the shape that fits the book margin once the modal-size dial slims it: at
        // Compact a tall pill is ~42dp wide against the reader's ~46dp foliate gutter. The
        // "reader" key keeps the position you'd already dragged it to; the old collapse pref
        // seeds the new shared one so a folded bar stays folded across the change.
        requireContext().getSharedPreferences("ledger_widgets", 0).let { p ->
            if (!p.contains("reader_collapsed") && p.getBoolean("reader_bar_collapsed", false))
                p.edit().putBoolean("reader_collapsed", true).apply()
        }
        // Folding leaves ☀ today and ✎ highlight showing (grip + the two essentials), as before.
        cyclePillOnTap(
            binding.readerGrip, binding.readerBar, "reader",
            collapsible = listOf(
                binding.gotoButton, binding.prevButton, binding.nextButton,
                binding.settingsButton, binding.openButton
            )
        )
        setupTapZones()

        // Empty-state add-book affordance (visible until a book is open).
        binding.readerAddBook.setOnClickListener {
            openBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "*/*"))
        }
        // Resume the last book, else land on the reader's "waiting for a book" screen.
        restoreLastBook()
        binding.readerEmpty.visibility = if (currentBookFile == null) View.VISIBLE else View.GONE
        web.loadUrl("$ORIGIN/reader-embed.html")
    }

    /** Books live here so a shelf of several can be kept (not just one "current"). */
    private fun booksDir() = File(requireContext().filesDir, "reader/books").apply { mkdirs() }

    // --- In-book table of contents (posted by the reader JS on load) ---
    private data class TocEntry(val label: String, val href: String, val depth: Int)
    private var tocItems: List<TocEntry> = emptyList()

    /**
     * ☰ book directory — navigating *inside* and around the book: the shelf, import, the
     * table of contents (jump to any chapter), read-aloud, and the page-turn controls.
     * Cross-surface "get out" jumps live on the ▦ Ledger directory next to it.
     */
    private fun showBookDirectory() {
        // In-book navigation + the AI pipeline. Shelf/import/read-aloud/screen controls moved to the
        // wrench (🔧) in the bottom bar; this ☰ stays about moving *within* the open book.
        val chapterRows: List<Pair<String, () -> Unit>> = tocItems.map { e ->
            ("${"  ".repeat(e.depth)}${if (e.depth == 0) "◦ " else "· "}${e.label}") to { goToHref(e.href) }
        }
        // Marks belong on ☰ rather than the pill: they are ways of moving *within* the book,
        // which is what this menu is for, and the pill is already eight items wide on a panel
        // that cannot afford a ninth. The bookmark row states what it will do, so the toggle
        // reads as a toggle without needing a lit icon.
        val book = bookNoteKey()
        val bookmarked = book.isNotBlank() && currentCfi.isNotBlank() &&
            BookNoteStore.bookmarkAt(requireContext(), book, currentCfi) != null
        val groups = mutableListOf(
            "Marks" to listOf(
                (if (bookmarked) "🔖  Bookmarked — remove it" else "🔖  Bookmark this page") to { toggleBookmark() },
                "🖍  Annotations…" to { showBookNotes(BookNote.ANNOTATION) },
                "🔖  Bookmarks…" to { showBookNotes(BookNote.BOOKMARK) },
                "🗒  Notes on this book…" to { showBookNotes(BookNote.NOTE) }
            ),
            "Synthesize" to listOf(
                "🔬  3 questions → Synthesize" to { readerSynthesize() },
                "✍  Writing prompt → Write" to { readerWritingPrompt() },
                "🗒  Essay outline → Write" to { readerOutline() }
            )
        )
        if (chapterRows.isNotEmpty()) groups += "Chapters" to chapterRows
        showDirectory(groups)
    }

    /** ▦ Ledger directory — the shared cross-surface "get in/out" menu (Almanac/History/…). */
    private fun showLedgerDirectory() =
        showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))

    /**
     * The wrench (🔧 in the bottom bar): everything *about* the book rather than moving within it —
     * the shelf + import, read-aloud transport, screen rotate, and the finger/tap page-turn toggles.
     */
    private fun showReaderControls() {
        val books = booksDir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val bookRows: List<Pair<String, () -> Unit>> =
            books.map { f -> ("📖  " + f.nameWithoutExtension) to { loadBookFile(f) } } +
            ("＋  Import a book…" to {
                openBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "*/*"))
            }) +
            // A note ABOUT the book belongs with the shelf, not with the page you happen to be on.
            ("🖍  Note on this book…" to { composeBookNote() })
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        val readingRows: List<Pair<String, () -> Unit>> = when {
            player.isSpeaking -> listOf(
                "⏸  Pause reading" to { player.toggle() },
                "🎛  Player…" to { player.showModal(requireContext()) },
                "⏹  Stop reading" to { player.stop() })
            player.isPaused -> listOf(
                "▶  Resume reading" to { player.toggle() },
                "⏹  Stop reading" to { player.stop() })
            else -> listOf("🔊  Read aloud" to { readAloud() })
        }
        val tapOn = readerNavPrefs().getBoolean("tap_zones", true)
        val volOn = readerNavPrefs().getBoolean("volume_turn", true)
        showDirectory(listOf(
            "Books" to bookRows,
            "Reading" to readingRows,
            "Screen" to listOf(
                "🔄  Rotate screen" to { cycleScreenOrientation() },
                // A book wants a clean page; not everyone wants the floating pen on top of it.
                ((if (quickNoteShown()) "🖉  Hide the quick-note button" else "🖉  Show the quick-note button")
                    to { setQuickNoteShown(!quickNoteShown()) })
            ),
            "Page turn (finger)" to listOf(
                ((if (tapOn) "☑" else "☐") + "  Tap sides to turn") to { toggleReaderNav("tap_zones", !tapOn); setupTapZones() },
                ((if (volOn) "☑" else "☐") + "  Volume keys turn") to { toggleReaderNav("volume_turn", !volOn) }
            )
        ))
    }

    private fun quickNoteShown(): Boolean =
        (activity as? com.toolsboox.ui.main.MainActivity)?.quickNoteVisible() ?: true

    private fun setQuickNoteShown(visible: Boolean) {
        (activity as? com.toolsboox.ui.main.MainActivity)?.setQuickNoteVisible(visible)
    }

    private fun goToHref(href: String) {
        if (href.isBlank()) return
        val esc = href.replace("\\", "\\\\").replace("'", "\\'")
        binding.readerWeb.evaluateJavascript("window.goToHref && window.goToHref('$esc')", null)
    }

    // --- Touch-screen page turn (no button): lower-band tap zones + volume keys ---

    private fun readerNavPrefs() = requireContext().getSharedPreferences("ledger_book_nav", 0)
    private fun toggleReaderNav(key: String, value: Boolean) =
        readerNavPrefs().edit().putBoolean(key, value).apply()

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTapZones() {
        // On by default in the reader — Michael wants page turns without reaching for the pill.
        binding.tapZones.visibility = if (readerNavPrefs().getBoolean("tap_zones", true)) View.VISIBLE else View.GONE
        binding.readerTapLeft.setOnClickListener { pageTurn(next = false) }
        binding.readerTapRight.setOnClickListener { pageTurn(next = true) }
    }

    private fun pageTurn(next: Boolean) {
        binding.readerWeb.evaluateJavascript(
            if (next) "window.pageRight && window.pageRight()" else "window.pageLeft && window.pageLeft()", null
        )
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = handler@{ up ->
            if (!readerNavPrefs().getBoolean("volume_turn", true)) return@handler false
            pageTurn(next = !up)   // volume-up = back a page, volume-down = forward
            true
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null
    }

    /** Pick from the imported books, or import a new one. */
    private fun openShelf() {
        val books = booksDir().listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val labels = books.map { it.nameWithoutExtension } + listOf(getString(R.string.reader_import_new))
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(R.string.reader_shelf_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == books.size) {
                    openBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "*/*"))
                } else {
                    loadBookFile(books[which])
                }
            }
            .show()
    }

    private fun loadBookFile(file: File) {
        file.setLastModified(System.currentTimeMillis())   // track recency (last opened)
        // Audiobooks aren't e-books — hand them to the shared player instead of the foliate WebView.
        if (com.toolsboox.ui.plugin.LedgerPlayer.isAudioFile(file.name)) {
            com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
                requireContext(), file.nameWithoutExtension, "Audiobook", null, file.absolutePath)
            com.toolsboox.ui.plugin.LedgerPlayer.showModal(requireContext())
            return
        }
        currentBookFile = file
        requireContext().getSharedPreferences(PREFS, 0).edit().putString(KEY_BOOK, file.absolutePath).apply()
        bookReady = false
        binding.readerEmpty.visibility = View.GONE
        openWhenReady()
    }

    /** Font size + theme for the reader, applied via foliate's applyReaderSettings. */
    private fun openSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        val builder = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext())).setTitle(R.string.reader_settings_title)
        val current = prefs.getString(KEY_THEME, "default") ?: "default"
        val items = arrayOf(
            getString(R.string.reader_font_smaller),
            getString(R.string.reader_font_larger),
            getString(R.string.reader_theme, current)
        )
        builder.setItems(items) { _, which ->
            when (which) {
                0 -> changeFont(-10)
                1 -> changeFont(10)
                2 -> cycleTheme()
            }
        }
        builder.show()
    }

    private fun changeFont(delta: Int) {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        val pct = (prefs.getInt(KEY_FONT, 100) + delta).coerceIn(60, 240)
        prefs.edit().putInt(KEY_FONT, pct).apply()
        applyReaderSettings()
    }

    private fun cycleTheme() {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        val idx = THEMES.indexOf(prefs.getString(KEY_THEME, "default") ?: "default").coerceAtLeast(0)
        val next = THEMES[(idx + 1) % THEMES.size]
        prefs.edit().putString(KEY_THEME, next).apply()
        applyReaderSettings()
    }

    private fun applyReaderSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        val pct = prefs.getInt(KEY_FONT, 100)
        val theme = prefs.getString(KEY_THEME, "default") ?: "default"
        binding.readerWeb.evaluateJavascript(
            "window.applyReaderSettings && window.applyReaderSettings({fontSize:$pct,theme:'$theme'})", null
        )
    }

    /** Serve foliate assets and the current book over the same-origin host. */
    private fun serve(url: Uri): WebResourceResponse? {
        if (url.host != HOST) return null
        val path = url.path ?: return null
        return try {
            if (path == "/book/current") {
                val f = currentBookFile ?: return notFound()
                WebResourceResponse(mimeFor(f.name), null, f.inputStream())
            } else {
                val asset = "foliate" + path   // path starts with "/"
                WebResourceResponse(mimeFor(path), if (isText(path)) "UTF-8" else null,
                    requireContext().assets.open(asset))
            }
        } catch (e: Exception) {
            Timber.w(e, "reader serve failed for $path")
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))

    /** Copy the picked document into the shelf (keyed by its real name) and open it. The copy is
     *  guarded: a null/throwing input stream or an empty result surfaces a message instead of
     *  crashing (or handing the engine a zero-byte file) — the old first-import crash path. */
    private fun importAndOpen(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val ext = extensionFor(uri)
                    val base = displayName(uri).substringBeforeLast('.', "book")
                        .replace(Regex("[^\\w .-]"), "_").take(80).ifBlank { "book" }
                    val dest = File(booksDir(), "$base.$ext")
                    val copied = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    if (copied == null || !dest.exists() || dest.length() == 0L) null else dest
                }.onFailure { Timber.w(it, "book import failed") }.getOrNull()
            }
            if (file == null) { showMessage(R.string.reader_import_failed); return@launch }
            loadBookFile(file)
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "book"
        runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
            }
        }
        return name
    }

    private fun restoreLastBook() {
        val path = requireContext().getSharedPreferences(PREFS, 0).getString(KEY_BOOK, null) ?: return
        val f = File(path)
        if (f.exists()) { currentBookFile = f; pendingOpen = true }
    }

    /** Jump back to where we left off in this book (the CFI stored on the last relocate). */
    private fun restoreReadingPosition() {
        val f = currentBookFile ?: return
        val book = f.nameWithoutExtension
        val ctx = requireContext().applicationContext
        // Resume instantly from the LOCAL spot, then re-jump once the pull merges if another
        // device's position is newer. The old fire-and-forget sync raced this restore: the
        // reader jumped to the stale local spot, the first page turn re-stamped it as newest,
        // and the push reverted the other device's progress — silently, on every open.
        fun jump(cfi: String) {
            val esc = cfi.replace("\\", "\\\\").replace("'", "\\'")
            binding.readerWeb.evaluateJavascript("window.goToCfi && window.goToCfi('$esc')", null)
        }
        val localCfi = ReaderPositionStore.get(ctx, book)
        localCfi?.let { jump(it) }
        ReaderPositionStore.sync(ctx) { merged ->
            val remoteCfi = merged.optJSONObject(book)?.optString("cfi")?.takeIf { it.isNotBlank() }
            if (remoteCfi != null && remoteCfi != localCfi) {
                binding.readerWeb.post {
                    if (!isAdded) return@post
                    skipRelocates++    // the re-jump's own relocate must not re-stamp
                    jump(remoteCfi)
                }
            }
        }
    }

    /** Ask foliate to fetch the book from our host once the engine has signalled ready. Before the
     *  engine is ready, defer (pendingOpen) — `handle("ready")` replays this so the book still opens. */
    private fun openWhenReady() {
        if (currentBookFile == null) return
        if (!engineReady) { pendingOpen = true; return }
        binding.readerWeb.evaluateJavascript("window.openBookURL && window.openBookURL('$ORIGIN/book/current')", null)
    }

    /** JS → Kotlin bridge (foliate posts JSON here via the shim). */
    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val msg = runCatching { JSONObject(json) }.getOrNull() ?: return
            val type = msg.optString("type")
            binding.readerWeb.post { handle(type, msg) }
        }
    }

    private fun handle(type: String, msg: JSONObject) {
        when (type) {
            "ready" -> {
                engineReady = true
                if (pendingOpen || currentBookFile != null) { pendingOpen = false; openWhenReady() }
            }
            "loaded" -> {
                bookReady = true
                bookTitle = msg.optString("title").ifBlank { "Untitled" }
                bookAuthor = msg.optString("author")
                bookCover = null
                applyReaderSettings()
                restoreHighlights()
                // Skip the first relocate (foliate's start-of-book report on open) so it can't clobber
                // the saved spot before we jump back to it.
                skipRelocates = 1    // foliate's start-of-book relocate
                restoreReadingPosition()
            }
            "cover" -> {
                // data:image/*;base64,<bytes> → the book cover bitmap for the book-gram card.
                val dataUrl = msg.optString("dataUrl")
                val comma = dataUrl.indexOf(',')
                if (comma > 0) runCatching {
                    val bytes = android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                    bookCover = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            "relocate" -> {
                // Note the spot BEFORE the skip: a skipped relocate is still a true report of
                // where the reader now stands, and a bookmark made right after a jump needs it.
                currentCfi = msg.optString("cfi")
                currentChapter = msg.optString("chapter")
                currentFraction = msg.optDouble("fraction", 0.0).let { if (it.isNaN()) 0.0 else it }
                // Foliate reports the current spot on every page turn — remember it per book (by NAME,
                // so it's portable across devices) and round-trip it through WebDAV.
                if (skipRelocates > 0) { skipRelocates--; return }
                val cfi = msg.optString("cfi")
                if (cfi.isNotBlank()) currentBookFile?.let {
                    ReaderPositionStore.set(requireContext(), it.nameWithoutExtension, cfi)
                }
            }
            "toc" -> {
                val arr = msg.optJSONArray("items") ?: return
                tocItems = (0 until arr.length()).mapNotNull { i ->
                    val it = arr.optJSONObject(i) ?: return@mapNotNull null
                    val label = it.optString("label").trim()
                    if (label.isEmpty()) null else TocEntry(label, it.optString("href"), it.optInt("depth", 0))
                }
            }
            "highlight" -> {
                val text = msg.optString("text").trim()
                val cfi = msg.optString("cfi")
                // The JS has already drawn the visual highlight; persist its CFI so it survives a
                // reopen. Then open the shared capture menu so the reader can add a note, photo,
                // upload, or voice memo — with or without a text selection.
                val mark = if (text.isNotEmpty() && cfi.isNotBlank()) rememberHighlight(cfi, text) else null
                captureAnnotation(text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                    logHighlight(selection, note, cfi, attachment)
                    // The passage is already in the book's own list; the reader's words join it
                    // there too, so the panel shows the pair the way the day timeline does.
                    if (mark != null && !note.isNullOrBlank()) BookNoteStore.put(
                        requireContext(), bookNoteKey(),
                        mark.copy(note = note, updatedAt = System.currentTimeMillis()))
                }
            }
            "tapAnnotation" -> {
                val cfi = msg.optString("cfi")
                val text = msg.optString("text").trim()
                if (cfi.isNotBlank()) showHighlightMenu(cfi, text)
            }
            "openExternal" -> {
                val href = msg.optString("href")
                if (href.isNotBlank()) startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(href)))
            }
            "error" -> Timber.w("reader error: ${msg.optString("message")}")
        }
    }

    /** Append a book annotation (highlight passage, note, and/or media) to today's CalendarDay. */
    private fun logHighlight(
        text: String?, note: String? = null, cfi: String = "",
        attachment: com.toolsboox.da.Attachment? = null, starred: Boolean = false
    ) {
        val title = bookTitle
        val author = bookAuthor
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val root = documentsRoot()
                val today = LocalDate.now()
                val locale = Locale.getDefault()
                val day: CalendarDay = calendarDayService.load(root, today, null, locale)
                day.readingEvents.add(
                    ReadingEvent(
                        id = "bookhl-${UUID.randomUUID()}",
                        kind = ReadingEvent.Kind.BOOK,
                        date = Date(),
                        title = title,
                        source = author.ifBlank { null },
                        excerpt = text?.ifBlank { null },
                        note = note?.ifBlank { null },
                        location = cfi.ifBlank { null },
                        attachments = attachment?.let { mutableListOf(it) },
                        starred = starred
                    )
                )
                calendarDayService.save(root, today, day)
            } catch (e: Exception) {
                Timber.w(e, "failed to log book annotation")
            }
        }
    }

    /** The legacy CFI-only highlight store — read once per book, to be adopted by [BookNoteStore]. */
    private fun highlightPrefs() = requireContext().getSharedPreferences(HL_PREFS, 0)

    private fun bookKey(): String? = currentBookFile?.name

    /** Marks are keyed by book NAME, like the reading position — the file path differs per device. */
    private fun bookNoteKey(): String = currentBookFile?.nameWithoutExtension.orEmpty()

    /** Record a highlight as a [BookNote] so it survives a reopen AND can be listed per book —
     *  foliate only draws annotations that are (re-)added to the current view. */
    private fun rememberHighlight(cfi: String, text: String): BookNote? {
        if (cfi.isBlank()) return null
        val book = bookNoteKey().ifBlank { return null }
        val now = System.currentTimeMillis()
        val mark = BookNote(
            id = BookNote.newId(), type = BookNote.ANNOTATION, cfi = cfi,
            chapter = currentChapter, fraction = currentFraction, text = text,
            createdAt = now, updatedAt = now
        )
        BookNoteStore.put(requireContext(), book, mark)
        return mark
    }

    /** Drop a highlight from the current book's marks (by location — that's what the page knows). */
    private fun forgetHighlight(cfi: String) {
        val book = bookNoteKey().ifBlank { return }
        BookNoteStore.list(requireContext(), book, BookNote.ANNOTATION)
            .filter { it.cfi == cfi }
            .forEach { BookNoteStore.delete(requireContext(), book, it.id) }
        // Keep the legacy set in step too, so an un-adopted book can't resurrect the mark.
        bookKey()?.let { key ->
            val set = highlightPrefs().getStringSet(key, emptySet())!!.toMutableSet()
            if (set.remove(cfi)) highlightPrefs().edit().putStringSet(key, set).apply()
        }
    }

    /** On book load, re-apply every stored highlight so saved marks reappear. */
    private fun restoreHighlights() {
        val book = bookNoteKey().ifBlank { return }
        bookKey()?.let { key ->
            BookNoteStore.adoptLegacyHighlights(requireContext(), book,
                highlightPrefs().getStringSet(key, emptySet()).orEmpty())
        }
        for (mark in BookNoteStore.list(requireContext(), book, BookNote.ANNOTATION)) {
            val escaped = mark.cfi.replace("\\", "\\\\").replace("'", "\\'")
            if (mark.cfi.isNotBlank()) binding.readerWeb.evaluateJavascript(
                "window.addStoredHighlight && window.addStoredHighlight('$escaped')", null)
        }
    }

    /** A bookmark for where the reader is standing — Readest's toggle: one per location, and
     *  tapping it again takes it back. Labelled with the chapter, or the percentage when the
     *  book has no table of contents to name it by. */
    private fun toggleBookmark() {
        val book = bookNoteKey().ifBlank { showMessage("Open a book first."); return }
        if (currentCfi.isBlank()) { showMessage("Turn a page first — no location yet."); return }
        val existing = BookNoteStore.bookmarkAt(requireContext(), book, currentCfi)
        if (existing != null) {
            BookNoteStore.delete(requireContext(), book, existing.id)
            showMessage("Bookmark removed")
            return
        }
        val now = System.currentTimeMillis()
        BookNoteStore.put(requireContext(), book, BookNote(
            id = BookNote.newId(), type = BookNote.BOOKMARK, cfi = currentCfi,
            chapter = currentChapter, fraction = currentFraction,
            text = currentChapter.ifBlank { "${(currentFraction * 100).toInt()}% through the book" },
            createdAt = now, updatedAt = now
        ))
        showMessage("Bookmarked")
    }

    /** The book's marks — annotations, bookmarks, and notes about the book itself. */
    private fun showBookNotes(startType: String) {
        val book = bookNoteKey().ifBlank { showMessage("Open a book first."); return }
        BookNotesPanel.bind(book)
        BookNotesPanel.show(
            requireContext(), book, tocItems.map { it.label }, startType,
            onJump = { mark ->
                skipRelocates++   // the jump's own relocate must not re-stamp the saved spot
                val esc = mark.cfi.replace("\\", "\\\\").replace("'", "\\'")
                binding.readerWeb.evaluateJavascript("window.goToCfi && window.goToCfi('$esc')", null)
            },
            onUnmark = { mark ->
                if (mark.type == BookNote.ANNOTATION && mark.cfi.isNotBlank()) {
                    val esc = mark.cfi.replace("\\", "\\\\").replace("'", "\\'")
                    binding.readerWeb.evaluateJavascript("window.deleteHighlight && window.deleteHighlight('$esc')", null)
                }
            }
        )
    }

    /** A note about the book rather than a passage in it — typed, or written by hand. */
    private fun composeBookNote() {
        val book = bookNoteKey().ifBlank { showMessage("Open a book first."); return }
        BookNotesPanel.bind(book)
        BookNotesPanel.editNote(requireContext(), null) { showMessage("Noted") }
    }

    /** Tapping an existing highlight opens its menu: copy the passage, add a note, or remove it —
     *  not just Delete. (Selecting fresh text still uses the WebView's own Copy/Share popup + the
     *  ✎ pill to highlight.) */
    private fun showHighlightMenu(cfi: String, text: String) {
        val ctx = requireContext()
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        if (text.isNotBlank()) rows += "📋  Copy text" to {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger", text))
            showMessage("Copied to clipboard")
        }
        if (text.isNotBlank()) rows += "⭐  Star this passage" to {
            logHighlight(text, cfi = cfi, starred = true)
            showMessage("Starred to your Ledger Log")
        }
        rows += "🖍️  Add note" to {
            captureAnnotation(text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                logHighlight(selection, note, cfi, attachment)
            }
        }
        if (text.isNotBlank()) rows += "❝  Add to Pickings" to { highlightToPickings(text) }
        rows += "🗑  Remove highlight" to { confirmDeleteHighlight(cfi) }
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(R.string.reader_highlight)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unquoteJs(raw: String): String =
        runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw.removeSurrounding("\"")

    private fun runLlmLines(creds: Triple<String, String, String>, system: String, text: String): List<String> {
        val res = com.toolsboox.plugin.chat.nw.LedgerChatService().run(creds.first, creds.second, creds.third, system, text)
        return if (res is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)
            res.answer.lines().map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim() }.filter { it.isNotEmpty() }
        else emptyList()
    }

    /**
     * The intake boundary for the reader's AI pipeline: a highlighted selection is the explicit
     * boundary; with nothing selected it falls back to the whole current section. Either way it says
     * which scope it used, so the intake is never a mystery.
     */
    private fun readerIntakeText(onText: (text: String, scope: String) -> Unit) {
        binding.readerWeb.evaluateJavascript("window.getReaderSelectionText && window.getReaderSelectionText()") { rawSel ->
            val sel = unquoteJs(rawSel)
            if (sel.isNotBlank()) { showMessage("Using your selected text."); onText(sel, "selection"); return@evaluateJavascript }
            binding.readerWeb.evaluateJavascript("window.getReaderText && window.getReaderText()") { raw ->
                val text = unquoteJs(raw)
                if (text.isBlank()) { showMessage("Select some text first, or open a readable page."); return@evaluateJavascript }
                showMessage("Using this whole section (highlight text first to focus it).")
                onText(text, "section")
            }
        }
    }

    /** The same Synthesize loop as the day pages, from the selected passage / current section: 3 questions. */
    private fun readerSynthesize() {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(requireContext())
            ?: run { showMessage("Add your Ask-my-Ledger key in Settings first."); return }
        readerIntakeText { text, scope ->
            lifecycleScope.launch {
                val qs = withContext(Dispatchers.IO) {
                    runLlmLines(creds, "You are the reader's thinking partner. From the book passage below, propose " +
                        "EXACTLY three focused, generative questions worth answering. Output ONLY the three questions, " +
                        "one per line, no numbering.", text).take(3)
                }
                if (qs.isEmpty()) { showMessage("Couldn't synthesize."); return@launch }
                // Grouped on the Synthesize page: a source gram (back-link to this book) + the questions.
                val srcLink = currentBookFile?.let { "book://${it.absolutePath}" } ?: ""
                val srcLabel = (bookTitle.ifBlank { currentBookFile?.nameWithoutExtension ?: "book" }) + " · " + scope
                com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(
                    requireContext(), LocalDate.now(), qs, "question", bookTitle.ifBlank { "book" })
                withContext(Dispatchers.IO) {
                    placeSourcedQuestionsToDay(qs, "synthesize", srcLink, srcLabel)
                }
                showMessage("3 questions placed on your Synthesize page.")
                CalendarNavigator.toDayNote(this@ReaderFragment, LocalDate.now(), "synthesize")
            }
        }
    }

    /**
     * Reader-side twin of the day page's grouped placement: a source gram card (with the back-link)
     * at the top of the batch, then the question text boxes beneath it, written straight to today.
     */
    private fun placeSourcedQuestionsToDay(lines: List<String>, pageKey: String, sourceLink: String, sourceLabel: String) {
        val root = documentsRoot(); val today = LocalDate.now()
        val day = calendarDayService.load(root, today, null, Locale.getDefault())
        var y = 140f
        if (sourceLink.isNotBlank()) runCatching {
            val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                "↩ Synthesized from\n$sourceLabel", null, null, 1100, 240)
            val baos = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            val w = 620f; val h = w * bmp.height / bmp.width
            day.imageElements.add(com.toolsboox.da.ImageElement(
                x = 90f, y = y, width = w, height = h, data = base64, page = pageKey,
                sourceLink = sourceLink, sourceLabel = sourceLabel))
            y += h + 30f
        }
        for (line in lines) {
            day.textElements.add(com.toolsboox.da.TextElement(
                x = 90f, y = y, width = 1220f, height = 100f, text = line, pageKey = pageKey))
            y += 120f
        }
        calendarDayService.save(root, today, day)
    }

    /** Offer 3 writing prompts from the selected passage / current section; the chosen one seeds Write. */
    private fun readerWritingPrompt() {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(requireContext())
            ?: run { showMessage("Add your Ask-my-Ledger key in Settings first."); return }
        readerIntakeText { text, _ ->
            lifecycleScope.launch {
                val prompts = withContext(Dispatchers.IO) {
                    runLlmLines(creds, "From the book passage below, propose THREE distinct, compelling essay writing " +
                        "prompts that could grow from these ideas. Output ONLY the three prompts, one per line, no numbering.",
                        text).take(3)
                }
                if (prompts.isEmpty()) { showMessage("Couldn't draft prompts."); return@launch }
                com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(
                    requireContext(), LocalDate.now(), prompts, "prompt", bookTitle.ifBlank { "book" })
                AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                    .setTitle("Pick a writing prompt")
                    .setItems(prompts.toTypedArray()) { _, which ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val root = documentsRoot(); val today = LocalDate.now()
                                val day = calendarDayService.load(root, today, null, Locale.getDefault())
                                day.textElements.add(com.toolsboox.da.TextElement(
                                    x = 90f, y = 140f, width = 1220f, height = 100f,
                                    text = "Prompt: " + prompts[which], pageKey = "write"))
                                calendarDayService.save(root, today, day)
                            }
                            CalendarNavigator.toDayNote(this@ReaderFragment, LocalDate.now(), "write")
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Essay outline from the selected passage / current section → movable text boxes in the Write page. */
    private fun readerOutline() {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(requireContext())
            ?: run { showMessage("Add your Ask-my-Ledger key in Settings first."); return }
        readerIntakeText { text, _ ->
            lifecycleScope.launch {
                val lines = withContext(Dispatchers.IO) {
                    runLlmLines(creds, "Sketch a CLASSIC bullet-pointed essay outline from the book passage below: a " +
                        "one-line thesis, then Intro, three Body points each with 1–2 sub-bullets, and a Conclusion. " +
                        "Keep each line short. Output ONLY the outline, one bullet per line.", text)
                }
                if (lines.isEmpty()) { showMessage("Couldn't outline that."); return@launch }
                com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(
                    requireContext(), LocalDate.now(), lines, "outline", bookTitle.ifBlank { "book" })
                withContext(Dispatchers.IO) {
                    val root = documentsRoot(); val today = LocalDate.now()
                    val day = calendarDayService.load(root, today, null, Locale.getDefault())
                    var y = 140f
                    for (line in lines) {
                        day.textElements.add(com.toolsboox.da.TextElement(
                            x = 90f, y = y, width = 1220f, height = 100f, text = line, pageKey = "write"))
                        y += 120f
                    }
                    calendarDayService.save(root, today, day)
                }
                showMessage("Outline placed in Write.")
                CalendarNavigator.toDayNote(this@ReaderFragment, LocalDate.now(), "write")
            }
        }
    }

    /** Render a card from a book highlight and place it on a pickings board (chooser). The card now
     *  carries the book title, author, and cover thumbnail. */
    private fun highlightToPickings(text: String) {
        val title = bookTitle.ifBlank { currentBookFile?.nameWithoutExtension ?: "" }
        val author = bookAuthor.ifBlank { null }
        val cover = bookCover
        // The card names where in the book it came from: the chapter under the title, the author
        // beside it, the cover on the footer. The provenance label carries the same, so a book
        // highlight reads the same on the card, in its rhizome, and on the Map.
        val footer = listOfNotNull(title, currentChapter.ifBlank { null }).joinToString(" · ")
        val label = listOfNotNull(title, currentChapter.ifBlank { null }, author).joinToString(" · ")
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                // Height 0 so a long highlight isn't shorn off by the square format.
                com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                    text, source = footer, note = null, W = 1080, H = 0,
                    author = author, cover = cover)
            }
            val src = currentBookFile?.let { "book://${it.absolutePath}" } ?: ""
            com.toolsboox.plugin.calendar.ot.PickingsPlacement.chooseAndPlace(
                this@ReaderFragment, calendarDayService, documentsRoot(), bmp,
                sourceLink = src, sourceLabel = label, cardText = text)
        }
    }

    /** Tapping an existing highlight offers to remove it (mark + stored CFI). */
    private fun confirmDeleteHighlight(cfi: String) {
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(R.string.reader_highlight)
            .setMessage(R.string.reader_highlight_delete_confirm)
            .setPositiveButton(R.string.reader_highlight_delete) { d, _ ->
                val escaped = cfi.replace("\\", "\\\\").replace("'", "\\'")
                binding.readerWeb.evaluateJavascript("window.deleteHighlight && window.deleteHighlight('$escaped')", null)
                forgetHighlight(cfi)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    override fun showLoading() {}
    override fun hideLoading() {}

    /** Read the current book section aloud via the process-wide player (foliate exposes
     *  window.getReaderText). Keeps playing after you leave the book; drive it from the modal. */
    private fun readAloud() {
        binding.readerWeb.evaluateJavascript("window.getReaderText && window.getReaderText()") { raw ->
            val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                ?: raw.removeSurrounding("\"")
            if (text.isBlank()) { showMessage(R.string.reader_capture_failed); return@evaluateJavascript }
            val player = com.toolsboox.ui.plugin.LedgerPlayer
            player.start(requireContext(), bookTitle.ifBlank { "Reading" }, bookAuthor.ifBlank { null }, null, text)
            player.showModal(requireContext())
        }
    }

    override fun onDestroyView() {
        if (::binding.isInitialized) binding.readerWeb.destroy()
        super.onDestroyView()
    }

    private fun extensionFor(uri: Uri): String {
        val fromType = MimeTypeMap.getSingleton().getExtensionFromMimeType(requireContext().contentResolver.getType(uri))
        if (!fromType.isNullOrBlank()) return fromType
        val name = uri.lastPathSegment ?: return "epub"
        return name.substringAfterLast('.', "epub")
    }

    private fun isText(path: String) = path.endsWith(".js") || path.endsWith(".mjs") ||
        path.endsWith(".css") || path.endsWith(".html") || path.endsWith(".json") || path.endsWith(".svg")

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mobi", "azw3" -> "application/x-mobipocket-ebook"
        "cbz" -> "application/vnd.comicbook+zip"
        "fb2" -> "application/x-fictionbook+xml"
        else -> "application/octet-stream"
    }

    companion object {
        private const val HOST = "ledger.reader"
        private const val ORIGIN = "https://ledger.reader"
        private const val PREFS = "ledger_reader_prefs"
        private const val HL_PREFS = "ledger_reader_highlights"
        private const val KEY_BOOK = "current_book_path"
        private const val KEY_FONT = "reader_font_pct"
        private const val KEY_THEME = "reader_theme"
        private val THEMES = listOf("default", "sepia", "gray", "black")
        private val SHIM_JS = """
            window.webkit = window.webkit || {};
            window.webkit.messageHandlers = window.webkit.messageHandlers || {};
            window.webkit.messageHandlers.reader = {
              postMessage: function(m) { try { AndroidReaderBridge.postMessage(JSON.stringify(m)); } catch (e) {} }
            };
        """.trimIndent()
    }
}
