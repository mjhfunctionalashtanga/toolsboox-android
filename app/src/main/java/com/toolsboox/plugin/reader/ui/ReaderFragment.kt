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
        binding.gotoButton.setOnClickListener { showSurfacesMenu() }

        // Resume the last book, else land on the reader's "waiting for a book" screen.
        restoreLastBook()
        web.loadUrl("$ORIGIN/reader-embed.html")
    }

    /** Books live here so a shelf of several can be kept (not just one "current"). */
    private fun booksDir() = File(requireContext().filesDir, "reader/books").apply { mkdirs() }

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
            }
            "highlight" -> {
                val text = msg.optString("text").trim()
                if (text.isEmpty()) { showMessage(R.string.reader_select_first); return }
                logHighlight(text, msg.optString("cfi"))
                showMessage(R.string.reader_highlight_saved)
            }
            "openExternal" -> {
                val href = msg.optString("href")
                if (href.isNotBlank()) startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(href)))
            }
            "error" -> Timber.w("reader error: ${msg.optString("message")}")
        }
    }

    /** Append a book highlight to today's CalendarDay so it joins the corpus + syncs. */
    private fun logHighlight(text: String, cfi: String) {
        val ctx = requireContext()
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
                        excerpt = text,
                        location = cfi.ifBlank { null }
                    )
                )
                calendarDayService.save(root, today, day)
            } catch (e: Exception) {
                Timber.w(e, "failed to log book highlight")
            }
        }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

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
