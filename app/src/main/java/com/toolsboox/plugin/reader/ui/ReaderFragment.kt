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
class ReaderFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_reader

    private lateinit var binding: FragmentReaderBinding

    /** The book currently served at https://ledger.reader/book/current. */
    @Volatile
    private var currentBookFile: File? = null

    private var bookReady = false
    private var pendingOpen = false
    private var bookTitle = ""
    private var bookAuthor = ""

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
        binding.openButton.setOnClickListener { openShelf() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.gotoButton.setOnClickListener { showReaderDirectory() }
        // Drag to move; tap the grip to hide/show the bar (books want a clean page).
        makeDraggable(binding.readerGrip, binding.readerBar, "reader") { toggleReaderBar() }
        applyReaderBarCollapse()

        // Resume the last book, else land on the reader's "waiting for a book" screen.
        restoreLastBook()
        web.loadUrl("$ORIGIN/reader-embed.html")
    }

    /** Books live here so a shelf of several can be kept (not just one "current"). */
    private fun booksDir() = File(requireContext().filesDir, "reader/books").apply { mkdirs() }

    /** Hamburger directory: the actual books on the shelf + jumps to the other surfaces. */
    private fun showReaderDirectory() {
        val nav = androidx.navigation.fragment.NavHostFragment.findNavController(this)
        // Most-recent first (recency tracked by touching the file on open).
        val books = booksDir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val bookRows: List<Pair<String, () -> Unit>> =
            books.map { f -> ("📖  " + f.nameWithoutExtension) to { loadBookFile(f) } } +
            ("＋  Import a book…" to {
                openBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "*/*"))
            })
        showDirectory(
            listOf(
                "Recent books" to bookRows,
                "Go to" to listOf(
                    "📅  Day" to { CalendarNavigator.toDayPage(this, LocalDate.now(), CalendarDay.DEFAULT_STYLE) },
                    "🖍️  Notes & Annotations" to { CalendarNavigator.toDayNote(this, LocalDate.now(), "0") },
                    "📰  Feed Ledger" to { nav.navigate(R.id.action_to_feeds) },
                    "💬  Ask my Ledger" to { nav.navigate(R.id.action_to_ledger_chat) }
                )
            )
        )
    }

    /** Pick from the imported books, or import a new one. */
    private fun openShelf() {
        val books = booksDir().listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val labels = books.map { it.nameWithoutExtension } + listOf(getString(R.string.reader_import_new))
        AlertDialog.Builder(requireContext())
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
        currentBookFile = file
        file.setLastModified(System.currentTimeMillis())   // track recency (last opened)
        requireContext().getSharedPreferences(PREFS, 0).edit().putString(KEY_BOOK, file.absolutePath).apply()
        bookReady = false
        openWhenReady()
    }

    /** Font size + theme for the reader, applied via foliate's applyReaderSettings. */
    private fun openSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        val builder = AlertDialog.Builder(requireContext()).setTitle(R.string.reader_settings_title)
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

    /** Copy the picked document into the shelf (keyed by its real name) and open it. */
    private fun importAndOpen(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val ext = extensionFor(uri)
                val base = displayName(uri).substringBeforeLast('.', "book")
                    .replace(Regex("[^\\w .-]"), "_").take(80).ifBlank { "book" }
                val dest = File(booksDir(), "$base.$ext")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest
            }
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

    /** Ask foliate to fetch the book from our host once the engine has signalled ready. */
    private fun openWhenReady() {
        if (currentBookFile == null) return
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
            "ready" -> if (pendingOpen || currentBookFile != null) { pendingOpen = false; openWhenReady() }
            "loaded" -> {
                bookReady = true
                bookTitle = msg.optString("title").ifBlank { "Untitled" }
                bookAuthor = msg.optString("author")
                applyReaderSettings()
                restoreHighlights()
            }
            "highlight" -> {
                val text = msg.optString("text").trim()
                val cfi = msg.optString("cfi")
                // The JS has already drawn the visual highlight; persist its CFI so it survives a
                // reopen. Then open the shared capture menu so the reader can add a note, photo,
                // upload, or voice memo — with or without a text selection.
                if (text.isNotEmpty() && cfi.isNotBlank()) rememberHighlight(cfi)
                captureAnnotation(text) { selection, note, attachment ->
                    logHighlight(selection, note, cfi, attachment)
                }
            }
            "tapAnnotation" -> {
                val cfi = msg.optString("cfi")
                if (cfi.isNotBlank()) confirmDeleteHighlight(cfi)
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
        attachment: com.toolsboox.da.Attachment? = null
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
                        attachments = attachment?.let { mutableListOf(it) }
                    )
                )
                calendarDayService.save(root, today, day)
            } catch (e: Exception) {
                Timber.w(e, "failed to log book annotation")
            }
        }
    }

    /** Highlights are stored per-book (keyed by file name) so they survive a reopen —
     *  foliate only draws annotations that are (re-)added to the current view. */
    private fun highlightPrefs() = requireContext().getSharedPreferences(HL_PREFS, 0)

    private fun bookKey(): String? = currentBookFile?.name

    /** Persist a highlight's CFI for the current book. */
    private fun rememberHighlight(cfi: String) {
        if (cfi.isBlank()) return
        val key = bookKey() ?: return
        val set = highlightPrefs().getStringSet(key, emptySet())!!.toMutableSet()
        set.add(cfi)
        highlightPrefs().edit().putStringSet(key, set).apply()
    }

    /** Drop a highlight's CFI from the current book's store. */
    private fun forgetHighlight(cfi: String) {
        val key = bookKey() ?: return
        val set = highlightPrefs().getStringSet(key, emptySet())!!.toMutableSet()
        if (set.remove(cfi)) highlightPrefs().edit().putStringSet(key, set).apply()
    }

    /** On book load, re-apply every stored highlight so saved marks reappear. */
    private fun restoreHighlights() {
        val key = bookKey() ?: return
        val cfis = highlightPrefs().getStringSet(key, emptySet()) ?: return
        for (cfi in cfis) {
            val escaped = cfi.replace("\\", "\\\\").replace("'", "\\'")
            binding.readerWeb.evaluateJavascript("window.addStoredHighlight && window.addStoredHighlight('$escaped')", null)
        }
    }

    /** Tapping an existing highlight offers to remove it (mark + stored CFI). */
    private fun confirmDeleteHighlight(cfi: String) {
        AlertDialog.Builder(requireContext())
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

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    /** Hide/show the reader bar — collapsed leaves just the grip + ✎ highlight. */
    private fun toggleReaderBar() {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        prefs.edit().putBoolean("reader_bar_collapsed", !prefs.getBoolean("reader_bar_collapsed", false)).apply()
        applyReaderBarCollapse()
    }

    private fun applyReaderBarCollapse() {
        val collapsed = requireContext().getSharedPreferences("ledger_widgets", 0)
            .getBoolean("reader_bar_collapsed", false)
        for (v in listOf(binding.gotoButton, binding.prevButton, binding.nextButton, binding.settingsButton, binding.openButton))
            v.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    override fun showLoading() {}
    override fun hideLoading() {}

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
