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
import com.toolsboox.ot.ReadingFonts
import com.toolsboox.ot.ReadingType
import com.toolsboox.ot.ReadingTypes
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

    /** The same corpus the Log searches — day pages, OCR'd handwriting sections, text notes,
     *  annotations, cached feed articles — so "all of Ledger" from inside a book means the same
     *  thing it means anywhere else. */
    @Inject
    lateinit var corpusService: com.toolsboox.plugin.chat.fi.LedgerCorpusService

    /** If a book is open, stash a return anchor so the Day page can jump straight back to it. The
     *  reader restores the last book on its own (KEY_BOOK), so only the nav action + label are needed. */
    override fun prepareReturnAnchor() {
        // By NAME, not File — a streamed book (a comic off the declared tree) has no File at all.
        val name = currentBookName ?: currentBookFile?.name
        if (name != null) {
            com.toolsboox.ui.plugin.LedgerReturn.set(
                R.id.action_to_reader, bookTitle.ifBlank { name.substringBeforeLast('.') })
        } else {
            com.toolsboox.ui.plugin.LedgerReturn.clear()
        }
    }

    override val view = R.layout.fragment_reader

    private lateinit var binding: FragmentReaderBinding

    /** The book currently served at https://ledger.reader/book/current. */
    @Volatile
    private var currentBookFile: File? = null

    /**
     * The book's ORIGINAL location in the declared tree, when it has one — and the reason a comic
     * opens at all rather than after a minute.
     *
     * Opening used to require materialising the book into cache first: a byte-for-byte copy, so a
     * 280 MB comic cost a full copy of itself before the first page could be drawn. But nothing in
     * the reader needs a FILE — `serve` hands the WebView an InputStream, and the content resolver
     * gives one straight off the tree. So the copy is skipped and the bytes are streamed from where
     * they already live.
     *
     * The materialised file is still made for the paths that genuinely need a seekable descriptor
     * (the PDF renderer), and for those it is made LAZILY, when they ask.
     */
    @Volatile
    private var currentBookUri: android.net.Uri? = null

    /** The book's real filename — the extension the engine routes on. Held separately because a
     *  streamed book has no File to ask. */
    @Volatile
    private var currentBookName: String? = null

    /**
     * The open book's fleet-wide name in `reading-state.json` — [ReadingState.keyFor]'s
     * folder-qualified name + size, NOT the device-local path (which differs per device) and NOT
     * the bare filename (which collides across folders and survives a replaced file it shouldn't).
     * Set by whoever opens the book: the shelf hands the exact key from its [BookshelfSource.Entry];
     * imports and the last-book restore derive/recall it — see [loadBookFile]/[restoreLastBook].
     */
    private var currentReadingId: String? = null
    /** One end-of-book offer per open — re-arms when a book loads (see [offerNextBook]). */
    private var nextBookOffered = false

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
    /** The same spot's pre-slice-2 CFI, for a comic whose page order moved (relocate's
     *  `legacyCfi`; blank otherwise) — a bookmark saved before the update matches this page. */
    private var currentLegacyCfi = ""
    private var currentChapter = ""
    private var currentFraction = 0.0
    private var bookCover: android.graphics.Bitmap? = null

    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importAndOpen(it) }
    }

    /** Aa Type's "Import font…" (Michael, 2026-10-11) — registered at construction like every
     *  launcher; the result goes to the open panel, which chooses the new face at once. */
    private val importFont = ReadingFonts.importLauncher(this) { r ->
        aaPanel?.takeIf { it.isShowing }?.onImported(r)
            ?: r.onSuccess { showMessage("Font added · ${it.label}") }
                .onFailure { showMessage(it.message ?: "Couldn't add that font") }
    }
    private var aaPanel: com.toolsboox.ui.plugin.AaTypePanel? = null

    /** The Aa Type payload handed to the engine BEFORE the book opened ([openWhenReady]); 'loaded'
     *  re-lays only if the type changed in between — no second render (Michael, 2026-10-11). */
    private var primedType: String? = null

    /**
     * Declaring the books folder. Michael, 2026-08-05: "We can declare a folder to use in app as
     * the books folder" — so the shelf points at the library he already keeps rather than becoming
     * a second copy of it.
     */
    private val pickBooksFolder = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        BookshelfSource.declare(requireContext(), uri)
        val n = BookshelfSource.list(requireContext()).size
        showMessage(
            "Shelf: ${BookshelfSource.declaredName(requireContext()) ?: "folder"} · $n book" +
                (if (n == 1) "" else "s")
        )
    }

    /** Open the catalog if the shelf asked us to on the way in — see BookshelfFragment.openCatalog. */
    private fun consumeCatalogRequest() {
        val prefs = requireContext().getSharedPreferences("ledger_reader_prefs", 0)
        // Now Playing's 📖: open THIS shelf entry on arrival (the paired book behind the audiobook).
        prefs.getString("open_shelf_entry_on_arrival", null)?.let { name ->
            prefs.edit().remove("open_shelf_entry_on_arrival").apply()
            binding.root.post {
                if (!isAdded) return@post
                shelfEntries().firstOrNull { it.name == name }?.let { openShelfEntry(it) }
                    ?: showMessage("That book isn't on this shelf.")
            }
            return
        }
        if (!prefs.getBoolean("open_catalog_on_arrival", false)) return
        prefs.edit().putBoolean("open_catalog_on_arrival", false).apply()
        binding.root.post { if (isAdded) showOpdsBrowser(null) }
    }

    /**
     * Browse the OPDS catalog. [href] null is the root; a sub-catalog passes its own.
     *
     * One list, two kinds of row, distinguished by the glyph: 🗂 drills further in, 📖 pulls the
     * book down onto the shelf. That is the whole of OPDS as far as a reader is concerned, and
     * modelling it as anything richer would be modelling the format rather than the task.
     */
    private fun showOpdsBrowser(href: String?) {
        val ctx = requireContext()
        if (OpdsCatalog.config(ctx) == null) { showOpdsSettings(); return }
        showMessage("Fetching the catalog…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OpdsCatalog.feed(ctx, href) }
            }
            if (!isAdded) return@launch
            val feed = result.getOrElse { e ->
                // The message is the point: a catalog failure is almost always a sign-in, and
                // "Sign in to the catalog" is a fixable instruction where "HTTP 401" is not.
                // And the fix needs its DOOR: the settings row used to live only on the menu a
                // SUCCESSFUL fetch built, so a mistyped address locked the settings behind the
                // very failure they would repair ("no way to access OPDS address after first
                // setting it — I accidentally mistyped it and cannot change it", 08-17 page).
                showIconMenu("Catalog", listOf(
                    "⚠  ${e.message ?: "The catalog didn't answer."}" to { showOpdsBrowser(href) },
                    "⚙  Catalog settings…" to { showOpdsSettings() }
                ))
                return@launch
            }
            if (feed.entries.isEmpty()) {
                showIconMenu(feed.title.ifBlank { "Catalog" }, listOf(
                    "Nothing here." to {},
                    "⚙  Catalog settings…" to { showOpdsSettings() }
                ))
                return@launch
            }
            val rows = feed.entries.map { e ->
                val glyph = if (e.isBook) "📖" else "🗂"
                val line = buildString {
                    append(glyph).append("  ").append(e.title)
                    if (e.author.isNotBlank()) append(" · ").append(e.author)
                }
                line to {
                    if (e.isBook) downloadFromCatalog(e)
                    else e.navHref?.let { showOpdsBrowser(it) } ?: showMessage("Nothing to open there.")
                }
            }
            feed.searchTemplate?.let { lastOpdsSearchTemplate = it }
            showIconMenu(
                feed.title.ifBlank { "Catalog" },
                listOf("🔎  Search the catalog…" to { searchOpds(feed) }) + rows + listOf("⚙  Catalog settings…" to { showOpdsSettings() })
            )
        }
    }

    /** The root feed's search template, remembered so a sub-catalog can search too. */
    private var lastOpdsSearchTemplate: String? = null

    /** Ask the catalog (its OpenSearch template) or, without one, filter what is on screen. */
    private fun searchOpds(feed: OpdsCatalog.Feed) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Title or author"; setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        fun run() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            val template = feed.searchTemplate ?: lastOpdsSearchTemplate
            if (template != null) {
                showOpdsBrowser(template.replace("{searchTerms}", java.net.URLEncoder.encode(q, "UTF-8")))
            } else {
                val hits = feed.entries.filter { it.title.contains(q, true) || it.author.contains(q, true) }
                if (hits.isEmpty()) { showMessage("Nothing here matches “$q”."); return }
                showIconMenu("“$q” · ${hits.size}", hits.map { e ->
                    ((if (e.isBook) "📖" else "🗂") + "  " + e.title + (if (e.author.isNotBlank()) " · ${e.author}" else "")) to {
                        if (e.isBook) downloadFromCatalog(e) else e.navHref?.let { showOpdsBrowser(it) } ?: Unit
                    }
                })
            }
        }
        val d = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Search the catalog")
            .setView(android.widget.FrameLayout(ctx).apply { setPadding(48, 8, 48, 0); addView(input) })
            .setPositiveButton("Search") { _, _ -> run() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> d.dismiss(); run(); true }
        showModal(d)
    }

    /** Pull one entry down onto the shelf — wherever the shelf currently is — and offer to read it. */
    private fun downloadFromCatalog(entry: OpdsCatalog.Entry) {
        val ctx = requireContext()
        val name = entry.filename()
        showMessage("Fetching ${entry.title}…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val cfg = OpdsCatalog.config(ctx) ?: return@runCatching false
                    val link = entry.download ?: return@runCatching false
                    val url = OpdsCatalog.resolve(cfg.url, link.href)
                    var wrote = false
                    OpdsCatalog.stream(ctx, url) { input ->
                        wrote = BookshelfSource.writeInto(ctx, name) { out -> input.copyTo(out) }
                    }
                    wrote
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            if (!ok) { showMessage("Couldn't fetch ${entry.title}."); return@launch }
            showMessage("On the shelf · ${entry.title}")
            // Push on add: one OPDS pull feeds the whole fleet — the book rides up to the
            // library hub so every other shelf sees it (and fetches or offers it by its own
            // policy) without anyone pulling from the catalog twice. Quiet on failure; the
            // sync pass retries.
            LibraryHub.pushAdded(ctx.applicationContext, "", name)
            // Offer the read rather than taking it: you may be filling the shelf, not starting a
            // book, and being yanked out of the catalog mid-browse is the wrong default.
            showIconMenu(entry.title, listOf(
                "📖  Read it now" to {
                    shelfEntries().firstOrNull { it.name == name }?.let { openShelfEntry(it) }
                    Unit
                },
                "🗂  Back to the catalog" to { showOpdsBrowser(null) },
            ))
        }
    }

    /** Where the catalog is, and who you are to it. */
    private fun showOpdsSettings() {
        val ctx = requireContext()
        val cfg = OpdsCatalog.config(ctx)
        val dp = resources.displayMetrics.density
        val urlIn = android.widget.EditText(ctx).apply {
            hint = "Catalog URL (…/opds)"; setSingleLine(); setText(cfg?.url.orEmpty())
        }
        val userIn = android.widget.EditText(ctx).apply {
            hint = "Username"; setSingleLine(); setText(cfg?.user.orEmpty())
        }
        val passIn = android.widget.EditText(ctx).apply {
            hint = "Password"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(cfg?.pass.orEmpty())
        }
        // FREE CATALOGS (09-13, "people able to use the app without their own VPS"): a person
        // with no server of their own still has a shelf to fill — the public-domain catalogs
        // speak OPDS too. One tap fills the address; no sign-in needed.
        val presets = listOf(
            "Standard Ebooks" to "https://standardebooks.org/feeds/opds",
            "Project Gutenberg" to "https://m.gutenberg.org/ebooks.opds/",
            "Feedbooks · public domain" to "https://catalog.feedbooks.com/publicdomain/browse/en/top.atom",
        )
        val presetRow = android.widget.TextView(ctx).apply {
            text = "📚  Free catalogs…"; textSize = 15f; setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                    .setTitle("Free catalogs")
                    .setItems(presets.map { it.first }.toTypedArray()) { _, i -> urlIn.setText(presets[i].second); userIn.setText(""); passIn.setText("") }
                    .show()
            }
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
            addView(urlIn); addView(userIn); addView(passIn); addView(presetRow)
        }
        // Through the modal door — the reader runs raw ink for annotations, and a raw-shown
        // dialog under it is the stylus-freeze class (Synthesize picker, Sites popup).
        showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Book catalog")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                OpdsCatalog.save(ctx, urlIn.text.toString(), userIn.text.toString(), passIn.text.toString())
                showOpdsBrowser(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** The shelf, wherever it has been declared to be. */
    private fun shelfEntries(): List<BookshelfSource.Entry> =
        runCatching { BookshelfSource.list(requireContext()) }.getOrDefault(emptyList())

    /**
     * End of the book → OFFER the folder's next one, mihon-style (Michael, 08-30) — an OFFER,
     * never an auto-advance: e-ink quiet, Back stays put, tapping opens it through the one
     * open path ([openShelfEntry] — streamed, wanted, session started, position recorded).
     * "Next" is the shelf's own order for the folder (title, case-folded — mihon chapters are
     * lexicographic within a series, so this is chapter order). The finished book's done flag
     * latches in the media log; its 100% fraction is already on the reading-state wire.
     */
    private fun offerNextBook() {
        val ctx = context ?: return
        val readingId = currentReadingId ?: return
        Thread {
            val entries = runCatching { BookshelfSource.list(ctx) }.getOrDefault(emptyList())
            val cur = entries.firstOrNull {
                com.toolsboox.plugin.calendar.da.v2.ReadingState.keyFor(it.folder, it.name, it.sizeBytes) == readingId
            } ?: return@Thread
            runCatching {
                com.toolsboox.plugin.calendar.ot.MediaLogStore.record(
                    ctx, id = "book:$readingId",
                    kind = com.toolsboox.plugin.calendar.da.v2.MediaLogEntry.READ,
                    title = cur.title, source = "bookshelf", done = true)
            }
            val siblings = entries.filter { it.folder == cur.folder }
                .sortedBy { it.title.lowercase() }
            val i = siblings.indexOfFirst { it.name == cur.name && it.sizeBytes == cur.sizeBytes }
            val next = siblings.getOrNull(i + 1)
            // 📈 THE TRACKER HEARS THE CHAPTER (09-13): a series bound to AniList / MAL / … gets
            // this chapter's number pushed — never lowered — through Suwayomi. Quiet on failure.
            com.toolsboox.plugin.reader.nw.TrackerStore.bound(ctx, cur.folder)?.let { b ->
                runCatching {
                    val ch = com.toolsboox.plugin.reader.nw.TrackerBridge.chapterNumber(cur.name, i.coerceAtLeast(0))
                    val line = if (b.provider == com.toolsboox.plugin.reader.nw.ComicTrackers.SUWAYOMI) {
                        val srv = com.toolsboox.plugin.reader.nw.TrackerBridge.server(ctx) ?: return@runCatching
                        val now = com.toolsboox.plugin.reader.nw.TrackerBridge.progress(srv, b.recordId, ch)
                        com.toolsboox.plugin.reader.nw.TrackerStore.recordProgress(ctx, cur.folder, now)
                        "📈 ${b.trackerName} · ${b.title} · ch ${if (now % 1f == 0f) now.toInt().toString() else now.toString()}"
                    } else com.toolsboox.plugin.reader.nw.ComicTrackers.progress(ctx, cur.folder, b, ch, cur.name)
                    activity?.runOnUiThread { if (isAdded) showMessage(line, binding.root) }
                }.onFailure { Timber.w(it, "tracker progress push failed") }
            }
            activity?.runOnUiThread {
                if (!isAdded || currentReadingId != readingId) return@runOnUiThread
                // The finished book's way out, right here (Michael, 09-13: "when I finish a comic
                // book, I don't need it on there anymore unless I wanna keep it… same with
                // finished books"): next in the folder, delete everywhere (hub + the fleet's
                // question), delete from this device, or keep it. Deleting leaves the reader.
                val rows = mutableListOf<Pair<String, () -> Unit>>()
                if (next != null) rows += "→  Next: ${next.title}" to { openShelfEntry(next) }
                rows += "✓  Finished — delete everywhere" to {
                    val app = requireContext().applicationContext
                    val gone = LibraryHub.deleteEverywhere(app, cur)
                    showMessage(if (gone) "Deleted everywhere · ${cur.title}" else "Couldn't delete ${cur.title}.", binding.root)
                    if (gone) requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                rows += "🗑  Delete from this device" to {
                    val gone = LibraryHub.deleteHere(requireContext().applicationContext, cur)
                    showMessage(if (gone) "Deleted here · ${cur.title}" else "Couldn't delete ${cur.title}.", binding.root)
                    if (gone) requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                rows += "📚  Keep it" to { }
                showIconMenu("Finished · ${cur.title}" + (if (next == null) " — the last one in ${cur.folder.ifBlank { "this folder" }}" else ""), rows)
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Open a shelf entry. A book behind a declared tree is materialised into the cache first, so
     * every reader, annotation and position path downstream still works on a real file.
     */
    private fun openShelfEntry(entry: BookshelfSource.Entry) {
        // The read history the shelf's period filter reads. Recorded here rather than by stamping
        // the file: you cannot touch a book inside a declared tree, and touching Michael's own
        // Calibre library to note that Ledger looked at something would make every book appear
        // freshly changed to Syncthing and push a library across the mesh for a read.
        BookOpens.record(requireContext(), entry.name)
        // The entry is the ONLY place the folder-qualified identity is known for a book behind a
        // declared tree, so the sync key travels in here rather than being derived downstream.
        val id = com.toolsboox.plugin.calendar.da.v2.ReadingState
            .keyFor(entry.folder, entry.name, entry.sizeBytes)
        // Reading it is wanting it everywhere (the 08-27 ruling); the re-ask keeps the want young.
        BookWantsStore.want(requireContext().applicationContext, id, LibraryHub.deviceName(requireContext()))
        // The attention clock starts — book or comic, the reader is the one caller that knows
        // which (his stats ruling: "how much time book, how much time comic").
        val medium = if (entry.name.lowercase().endsWith(".cbz") || entry.name.lowercase().endsWith(".cbr"))
            com.toolsboox.plugin.calendar.da.v2.ReadingSession.COMIC
        else com.toolsboox.plugin.calendar.da.v2.ReadingSession.BOOK
        com.toolsboox.plugin.calendar.ot.ReadingSessionStore.begin(requireContext(), medium, id)
        // Books join the read/watch/listen history — the media log never heard of them before,
        // so "the Log" had every article and no book. Same call the feed's two recorders make.
        com.toolsboox.plugin.calendar.ot.MediaLogStore.record(
            requireContext(), id = "book:$id",
            kind = com.toolsboox.plugin.calendar.da.v2.MediaLogEntry.READ,
            title = entry.title, source = "bookshelf")
        // NO COPY for a tree entry — stream it (see loadBookUri). A plain file opens as it always did.
        val uri = entry.uri
        if (uri != null) { loadBookUri(uri, entry.name, id); return }
        val f = entry.file ?: BookshelfSource.materialise(requireContext(), entry)
        if (f == null) {
            showMessage("Couldn't open ${entry.title}")
            return
        }
        loadBookFile(f, id)
    }

    /** The rotation belt for the comic refit. fixed-layout's ResizeObserver doesn't deliver in
     *  this WebView (the -14 observer lesson), so the embed refits on the window resize event —
     *  and this covers the WebView that relaid out without firing one: after the flip's layout
     *  settles, poke the embed's deterministic refit door. Harmless when the JS listener already
     *  ran (a same-value re-render), harmless on reflowable books (no 'zoom' attribute, no-op). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        pokeComicRefit()
    }

    /** Poke the fixed-layout embed's deterministic refit door — RETRIED across the window the
     *  e-ink relayout actually settles in (a single 250ms poke missed it, and the two-page
     *  canvas stuck mis-rotated — Michael, 09-03: "it constrains the canvas strangely and can
     *  get misrotated and stuck"). Idempotent: a same-value re-render is harmless, and on a
     *  reflowable book there's no 'zoom' attribute so refitViewport is a no-op. */
    private fun pokeComicRefit() {
        if (!::binding.isInitialized) return
        for (delay in longArrayOf(80, 300, 700, 1200)) {
            binding.readerWeb.postDelayed({
                if (isAdded) runCatching {
                    binding.readerWeb.evaluateJavascript("window.refitViewport && window.refitViewport()", null)
                }
            }, delay)
        }
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

            // The reader WebView never leaves its own origin. The bridge is exposed to whatever
            // page is loaded ([addJavascriptInterface] above), so an EPUB link that navigated the
            // main frame to an outside page would hand AndroidReaderBridge to that page's scripts.
            // Anything off-origin goes to a real browser instead.
            //
            // BUT A COMIC'S PAGES ARE blob: URLS, AND THIS WAS EATING THEM — the last thing keeping
            // CBZ files from opening. `comic-book.js` hands each page to the renderer as a Blob it
            // made from the archive, and `blob:https://ledger.reader/<uuid>` has NO PARSEABLE HOST,
            // so `url.host == HOST` was false and every page turn was politely offered to an
            // external browser ("No Activity found to handle Intent { act=VIEW dat=blob: }"). The
            // comic opened, routed correctly, and then rendered nothing.
            //
            // A blob is content THIS PAGE created; there is no origin to leave. Same for data: and
            // about:. And a SUB-FRAME never receives the bridge — only the main frame does — so a
            // sandboxed iframe navigating is not the risk this guard exists for. The guard now
            // means what it always intended: the MAIN FRAME may not walk off our own origin.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                when (url.scheme?.lowercase()) {
                    "blob", "data", "about", "javascript" -> return false
                }
                if (!request.isForMainFrame) return false
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url))
                }.onFailure { Timber.w(it, "No browser for $url") }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // ARRIVING ON THE BOOK BEING READ (the transport title's door, 09-13): the voice
                // is already going — re-take the follow relay so the sentence lights up here.
                run {
                    val p = com.toolsboox.ui.plugin.LedgerPlayer
                    if (p.isActive && p.readingBook != null && p.readingBook == currentBookName) {
                        view.evaluateJavascript(FOLLOW_JS, null)
                        p.onFollowSentence = { sentence ->
                            if (isAdded && requireContext().getSharedPreferences("MAIN", 0).getBoolean("tts_follow", true)) {
                                val q = org.json.JSONObject.quote(sentence)
                                view.post { runCatching { view.evaluateJavascript("window.__ledgerFollow && window.__ledgerFollow($q)", null) } }
                            }
                        }
                    }
                }
                // Belt-and-suspenders shim if document-start isn't supported.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(SHIM_JS, null)
                }
            }
        }

        // The floating pill retires: the tucked action rail (an in-flow gutter beside the book —
        // the reader pane narrows rather than being covered, so the foliate margin stays prose)
        // carries everything the pill carried, unpacked into icons. The pill's views stay in the
        // layout, hidden, so nothing else referencing them breaks; the buttons keep answering
        // performClick for anything that drives them programmatically.
        binding.readerBar.visibility = View.GONE
        // THE ONE PANEL LANGUAGE (Michael 2026-10-11: one design language for every tuck panel,
        // both apps): ⛶ | Today · Nib · More | Page ‹ · Page › |
        // Book · Notes · Find · Type · Listen · Mark | Side. Monochrome faces (📖 → the book
        // drawable, ▶/🎧 → one Listen speaker, the pencil → Mark's underline), More is the wrench
        // as it was, Notes and Find are one tap now (they were Book › Notes and Book › Search),
        // and ⛶ replaces ✕ Tuck — a remembered tuck comes back as full screen, the [⛶] pill in the
        // page's top-left corner. Today and Nib are the shared doors ([setupPanel]); ☰ Hub left
        // the column 2026-10-12 (Michael: "Those too.") — the wrench's first row, and the left
        // edge's Back once the book is closed ([edgeReader]).
        readerRail = setupPanel(
            binding.readerRail, "reader", com.toolsboox.ot.PanelSeats.Dress.BOOK,
            fullPillHost = binding.readerPage,
        ) { seat -> readerSeat(seat, web) }
        // FULL SCREEN IN A READER LEAVES NOTHING ON THE PAGE (Michael 2026-10-12: "I need the full
        // screen button to be able to be fully hidden within a reader" + "IT cant block the comics
        // :P"): in full screen the [⛶] pill and the notes pill go too; a tap in the middle of the
        // page brings them back, a page turn puts them away ([com.toolsboox.ot.ReaderFullScreen];
        // the taps: [readerCentreTap], the turns: [pageTurn], "turned", "panelStep"). The comic's
        // "12 / 48" and the read-aloud chip go and come with them (review 2026-10-12; the chip
        // through the activity's readerAway, beside the notes pill). In NORMAL reading the same
        // middle tap goes into full screen, as on the iPhone (Michael 2026-10-12: "yes") — the ⛶
        // seat's own transition ([com.toolsboox.ot.TuckPanel.readerMiddleTap]).
        readerRail?.onReaderChrome = { shown -> pushPageReadShown(shown) }
        readerRail?.reader = true
        // TalkBack has no centre of the page to aim at: a named action is the same tap.
        binding.readerWeb.setControlsAction { readerRail?.readerMiddleTap() }
        // The retired pill's buttons keep their handlers for performClick-driven callers.
        binding.prevButton.setOnClickListener { web.evaluateJavascript("window.pageLeft && window.pageLeft()", null) }
        binding.nextButton.setOnClickListener { web.evaluateJavascript("window.pageRight && window.pageRight()", null) }
        binding.highlightButton.setOnClickListener {
            web.evaluateJavascript("window.highlightSelection && window.highlightSelection()", null)
        }
        binding.openButton.setOnClickListener { showReaderControls() }
        binding.todayButton.setOnClickListener { showLedgerDirectory() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.gotoButton.setOnClickListener { showBookDirectory() }
        setupTapZones()

        // Empty-state add-book affordance (visible until a book is open).
        binding.readerAddBook.setOnClickListener {
            openBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "*/*"))
        }
        // Resume the last book, else land on the reader's "waiting for a book" screen.
        restoreLastBook()
        // "No book open yet" asks whether a book is OPEN, and since 1.07.44 a book can be open
        // without being a File — a comic behind the declared tree is streamed, not copied
        // ([loadBookUri]), so `currentBookFile` is null for one. Testing only the file left the
        // add-a-book pane sitting on top of a comic that was loading and paging perfectly well
        // underneath it.
        binding.readerEmpty.visibility =
            if (currentBookFile == null && currentBookUri == null) View.VISIBLE else View.GONE
        web.loadUrl("$ORIGIN/reader-embed.html")
    }

    /** Books live here so a shelf of several can be kept (not just one "current"). */
    private fun booksDir() = File(requireContext().filesDir, "reader/books").apply { mkdirs() }

    // --- In-book table of contents (posted by the reader JS on load) ---
    private data class TocEntry(val label: String, val href: String, val depth: Int, val index: Int = -1)

    // --- READ-ALONG: this book's Audiobookshelf pairing, and where the page stands in its section ---
    private var readAlongPairing: ReadAlongPairing.Pairing? = null
    private var currentSectionIndex = -1
    private var currentSectionFraction = 0.0
    /** One in-book search hit: where it is, and enough words to recognise it. */
    private data class SearchHit(val cfi: String, val excerpt: String)
    private var searchHits: List<SearchHit> = emptyList()
    /** Set while a search dialog is open, so results can land in it when the engine answers. */
    private var onSearchHits: ((List<SearchHit>) -> Unit)? = null
    private var tocItems: List<TocEntry> = emptyList()

    /**
     * ☰ book directory — navigating *inside* and around the book: the shelf, import, the
     * table of contents (jump to any chapter), read-aloud, and the page-turn controls.
     * Cross-surface "get out" jumps live on the ▦ Ledger directory next to it.
     */
    private fun showBookDirectory() {
        // In-book navigation + the AI pipeline. Shelf/import/read-aloud/screen controls moved to the
        // wrench (🔧) in the bottom bar; this ☰ stays about moving *within* the open book.
        //
        // The table of contents used to fake its depth with leading spaces — every section of
        // every chapter stood in one flat run, and an eighty-row scroll is the slowest thing
        // there is on e-ink. The depth is real now: the top-level chapters are the rows (the
        // label still navigates, exactly as before), and a chapter's sections tuck into a
        // genuine sub-fold behind a caret on its row — the hub's Mail / Later List shape,
        // menu → Chapters → chapter → its sections. TOCs nested deeper than two levels keep
        // the two-level shape by folding everything under its nearest top-level chapter, with
        // a residual indent on those labels so the deeper nesting still reads.
        val chapterItems = mutableListOf<Pair<String, () -> Unit>>()
        val chapterSubFolds = mutableMapOf<String, List<Pair<String, () -> Unit>>>()
        for (e in tocItems) {
            if (e.depth == 0 || chapterItems.isEmpty()) {
                chapterItems += "◦ ${e.label}" to { goToHref(e.href) }
            } else {
                val parent = chapterItems.last().first
                chapterSubFolds[parent] = chapterSubFolds[parent].orEmpty() +
                    (("${"  ".repeat(e.depth - 1)}· ${e.label}") to { goToHref(e.href) })
            }
        }
        // Marks belong on ☰ rather than the pill: they are ways of moving *within* the book,
        // which is what this menu is for, and the pill is already eight items wide on a panel
        // that cannot afford a ninth. The bookmark row states what it will do, so the toggle
        // reads as a toggle without needing a lit icon.
        val book = bookNoteKey()
        val bookmarked = book.isNotBlank() && currentCfi.isNotBlank() && bookmarkHere(book) != null
        val groups = mutableListOf(
            "Find" to listOf(
                "🔎  Search this book / all Ledger…" to { showBookSearch() }
            ),
        )
        // A comic's own controls, beside the marks (design §4: the toggle belongs in the
        // reader's own menu, and it re-lays the current page immediately so the effect is
        // visible where the choice is made). Per BOOK, stored — he reads both directions.
        if (isComicOpen()) {
            // Settings ride the fleet key (Michael 2026-10-11); `book` stays the marks' key.
            val ck = comicKey()
            val panelOn = comicPrefs().getBoolean("comic_panels_$ck", false)
            val balloonOn = comicPrefs().getBoolean("comic_balloons_$ck", false)
            val rtl = comicPrefs().getBoolean("comic_rtl_$ck", false)
            val scrolled = comicPrefs().getString("comic_flow_$ck", "paginated") == "scrolled"
            val twoPage = comicPrefs().getString("comic_spread_$ck", "none") == "both"
            val zoom = comicPrefs().getString("comic_zoom_$ck", "fit-page")!!
            groups += "Comic" to listOf(
                // Panel walk and vertical scroll are two WAYS TO WALK the same book — turning
                // one on stands the other down, so the reader is never in both at once.
                ((if (panelOn) "☑" else "☐") + "  Panel-by-panel") to {
                    // …and one page per screen: a walk that owns one iframe of a multi-page
                    // layout crops a third of the view and reads as doing nothing (his B6,
                    // landscape). The spread stands down; last choice wins.
                    comicPrefs().edit().putBoolean("comic_panels_$ck", !panelOn)
                        .putBoolean("comic_balloons_$ck", false)
                        .putString("comic_flow_$ck", "paginated")
                        .putString("comic_spread_$ck", "none").apply()
                    pushComicPrefs()
                },
                // 💬 The BALLOON walk (Seeneva's detector, lifted; indexed in the background on
                // open, never at page-turn). The same walk as panels wearing different rects —
                // steps, taps, capture, fallback all identical — so the two are one-at-a-time.
                // THE ROW SAYS WHERE THE INDEX STANDS (field, 09-10: "the balloons aren't really
                // working" — a bare checkbox over a page that showed whole was the whole story
                // the reader got). One of: "· 12 on this page" · "· indexing… (3/48 pages)" ·
                // "· none found on this page" · "· not available for CBR" · "· not indexed yet —
                // index now". The last is a DOOR: it starts the pass at high priority, on this
                // page, and arms the walk so the balloons land where the reader is standing.
                ((if (balloonOn) "☑" else "☐") + "  💬 Balloon by balloon · " + balloonRowState()) to {
                    when (balloonRowKind()) {
                        BalloonRow.CBR -> showMessage(
                            "💬 Balloons need a CBZ — this book is a CBR (RAR), which can't be indexed yet",
                            binding.root)
                        BalloonRow.NOT_INDEXED -> {
                            comicPrefs().edit().putBoolean("comic_balloons_$ck", true)
                                .putBoolean("comic_panels_$ck", false)
                                .putString("comic_flow_$ck", "paginated")
                                .putString("comic_spread_$ck", "none").apply()
                            pushComicPrefs()
                            maybeIndexBalloons(urgent = true)
                            showMessage("💬 Indexing balloons for this book — starting at this page", binding.root)
                        }
                        else -> {
                            comicPrefs().edit().putBoolean("comic_balloons_$ck", !balloonOn)
                                .putBoolean("comic_panels_$ck", false)
                                .putString("comic_flow_$ck", "paginated")
                                .putString("comic_spread_$ck", "none").apply()
                            pushComicPrefs()
                            // Turning the walk on while the pass still cooks: hurry it up.
                            if (!balloonOn) com.toolsboox.plugin.reader.ot.BalloonIndex.boost(balloonKey())
                        }
                    }
                },
                ((if (twoPage) "☑" else "☐") + "  Two-page spread") to {
                    comicPrefs().edit()
                        .putString("comic_spread_$ck", if (twoPage) "none" else "both")
                        .putBoolean("comic_panels_$ck", false)
                        .putBoolean("comic_balloons_$ck", false).apply()
                    pushComicPrefs()
                },
                ((if (scrolled) "☑" else "☐") + "  Vertical scroll") to {
                    comicPrefs().edit()
                        .putString("comic_flow_$ck", if (scrolled) "paginated" else "scrolled")
                        .putBoolean("comic_panels_$ck", false)
                        .putBoolean("comic_balloons_$ck", false).apply()
                    pushComicPrefs()
                },
                // ONE fit choice, not a zoom ladder ("the zoom button… just jumps the zoom.
                // That's not what anyone would really want"). Page and width are the two honest
                // fits; MAGNIFICATION is the hold-drag box — draw what you want to see.
                // THREE fits, by user selection (09-03: "no default should be page… fit width
                // in landscape… but by user selection"): Page · Width · Auto (page in portrait,
                // width in landscape — the embed resolves 'auto' by the live orientation).
                ("🔍  Fit: " + when (zoom) { "fit-width" -> "width"; "auto" -> "auto (page/width)"; else -> "page" }) to {
                    comicPrefs().edit().putString("comic_zoom_$ck",
                        when (zoom) { "fit-page" -> "fit-width"; "fit-width" -> "auto"; else -> "fit-page" }).apply()
                    pushComicPrefs()
                },
                ("🔁  Reading direction: " + (if (rtl) "right-to-left (manga)" else "left-to-right")) to {
                    comicPrefs().edit().putBoolean("comic_rtl_$ck", !rtl).apply()
                    pushComicPrefs()
                },
                // The menu door to the long-hold's capture — a gesture is never the only way in.
                "🖼  This page → save / pick / annotate…" to {
                    binding.readerWeb.evaluateJavascript(
                        "window.comicCaptureNow && window.comicCaptureNow()", null)
                },
            )
        }
        groups += mutableListOf(
            "Marks" to listOf(
                (if (bookmarked) "🔖  Bookmarked — remove it" else "🔖  Bookmark this page") to { toggleBookmark() },
                "🖍  Annotations…" to { showBookNotes(BookNote.ANNOTATION) },
                "🔖  Bookmarks…" to { showBookNotes(BookNote.BOOKMARK) },
                "🗒  Notes on this book…" to { showBookNotes(BookNote.NOTE) }
            ),
            // "Write", not "Synthesize" (08-12 sweep). The Synthesize surface retired — no door
            // of its own anywhere else in the app (the day page stopped offering it as a move
            // target, the hub's Ask group ruled synthesis an Ask OUTPUT, not a place) — but this
            // group still carried its name and its lead row, "🔬 3 questions → Synthesize",
            // which built fresh content onto that doorless page. The row and its private
            // placement fork (readerSynthesize + placeSourcedQuestionsToDay, a byte-for-byte
            // copy of the day page's retired pipeline) are gone. The two verbs that remain were
            // never synthesis: both land on the LIVE Write page — and the prompt row now says
            // so ("→ Lines" was label drift; the code always wrote pageKey "write").
            "Write" to listOf(
                "✍  Writing prompt → Notes" to { readerWritingPrompt() },
                "🗒  Essay outline → Notes" to { readerOutline() }
            ),
        )
        // A book with eighty chapters turns this accordion into a scroll even with its sections
        // folded away. Past a couple of screens' worth (measured against the WHOLE tree, folds
        // included — that's what the finder searches), offer a filter above the list — the list
        // is still there under it.
        if (chapterItems.isNotEmpty()) {
            groups += "Chapters" to
                (if (tocItems.size > 18)
                    listOf("🔎  Find a chapter…" to { showChapterFinder() }) + chapterItems
                 else chapterItems)
        }
        showDirectory(groups, chapterSubFolds, contents = true)
    }

    /** ▦ Ledger directory — the shared cross-surface "get in/out" menu (Almanac/History/…). */
    private fun showLedgerDirectory() =
        showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))

    /** A book is read: the left edge's first Back opens its CONTENTS, the second goes back to the
     *  shelf, and the shelf's own Back opens the Hub (Michael 2026-10-12: "Content then shelf";
     *  the 3 s window rules as everywhere — [com.toolsboox.ot.EdgeLadder]). */
    override val edgeReader: Boolean get() = true

    /** The book's contents are the reader's pane — shut whenever a book is open (they are a
     *  dialog; while it shows, its own Back takes the next rung, [showBookDirectory]). With no
     *  book open there is nothing to list, so the Back is the plain one. */
    override fun edgePane(): com.toolsboox.ot.EdgeLadder.Pane =
        if (currentBookFile != null || currentBookUri != null || tocItems.isNotEmpty()) com.toolsboox.ot.EdgeLadder.Pane.SHUT
        else com.toolsboox.ot.EdgeLadder.Pane.NONE

    override fun openEdgePane() = showBookDirectory()

    /**
     * The wrench (🔧 in the bottom bar): everything *about* the book rather than moving within it —
     * the shelf + import, read-aloud transport, screen rotate, and the finger/tap page-turn toggles.
     */
    private fun showReaderControls() {
        val shelfName = BookshelfSource.declaredName(requireContext())
        // WRENCH HOLDS SETTINGS, HOLDS HOLD ACTIONS (Michael, field, 08-27: "the wrench and the
        // long hold need not present redundant options"). Gone from here: the recents fold (the
        // SHELF is that list, one tap away) and "Note on this book" (the shelf hold's action and
        // the capture chrome's — it lived in three places). What stays is what CONFIGURES the
        // surface: the folder, import/catalog (declaring what the shelf holds), the spread.
        val bookRows: List<Pair<String, () -> Unit>> =
            listOf<Pair<String, () -> Unit>>("🗂  Books folder · ${shelfName ?: "app storage"}" to {
                runCatching { pickBooksFolder.launch(BookshelfSource.pickIntent()) }
                Unit
            }) +
            ("＋  Import a book…" to {
                openBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "*/*"))
            }) +
            // The catalog — his punchlist's "Bookshelf still missing OPDS in import". Sits with
            // Import rather than in Settings, because browsing a catalog IS importing: it is the
            // same act with the shelf on someone else's machine.
            ("🌐  Catalog…" to { showOpdsBrowser(null) }) +
            ("📖  " + (if (bookType().columns == 2) "One page" else "Two pages") to { toggleSpread() }) +
            // Aa Type from the wrench too (Michael, 2026-10-11) — the rail's Aa seat stays.
            ("Aa  Type…" to { openSettings() })
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        val readingRows: List<Pair<String, () -> Unit>> = when {
            player.isSpeaking -> listOf(
                "⏸  Pause reading" to { player.toggle() },
                // Inline, not modal — this surface is where the playback lives (08-12 rule),
                // so "Player" seats the persistent transport card on the page.
                "🎛  Player" to { seatTransport() },
                "⏹  Stop reading" to { player.stop(); seatTransport() })
            player.isPaused -> listOf(
                "▶  Resume reading" to { player.toggle() },
                "⏹  Stop reading" to { player.stop(); seatTransport() })
            // Idle: NO "Read aloud" row — that verb lives on the tuck panel (an ACTION, the
            // dedupe rule again); the wrench keeps only the live TRANSPORT when one is playing.
            else -> emptyList()
        }
        val tapOn = readerNavPrefs().getBoolean("tap_zones", true)
        val volOn = readerNavPrefs().getBoolean("volume_turn", true)
        // The floating pills' size, adjustable from INSIDE the reader. Not a reader-private
        // setting: this reads and writes the one shared PILL dial (ModalScale.PILL_SIZE_KEY —
        // the same "Pill size" row Settings → Legibility shows), because the pills are one
        // system and their size is one number. It appears here too because the reader is where
        // the pill most needs adjusting — it parks in the book margin, and at this panel's
        // dimens it can come up genuinely tiny — while the settings row sits three surfaces
        // away behind the "size everything together" switch. Touching it from here detaches
        // the pill dial from that switch exactly as touching the row in Settings does.
        // Applied on the spot through the same pass every surface's pills get on open
        // (reapplyPillScale); the pen button follows by itself — MainActivity listens on the key.
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        val pillTier = a11y.getString(com.toolsboox.ot.ModalScale.PILL_SIZE_KEY,
            a11y.getString(com.toolsboox.ot.ModalScale.SIZE_KEY, "standard"))
        fun pt(m: String) = if (pillTier == m) "◉" else "○"
        fun setPillTier(m: String) {
            a11y.edit().putString(com.toolsboox.ot.ModalScale.PILL_SIZE_KEY, m).apply()
            reapplyPillScale(binding.readerGrip, binding.readerBar, "reader")
        }
        // The shelf's spacing ladder, from inside the reader — Michael's 2026-08-11 punchlist:
        // "Bookshelf needs clear spacing adjustments available in wrench — probably unto
        // 'reading' in the wrench in the book reader." Its own section beside Books/Reading
        // rather than a row hidden inside either, because the wrench's grammar is named
        // sections and "how the SHELF draws" is neither a book nor the read-aloud transport.
        // Radio rows, current rung marked; picking one persists and the shelf relays out once
        // on its next load — nothing here repaints the reader, which is showing a book.
        val shelfCur = ShelfDensity.current(requireContext())
        val shelfRows: List<Pair<String, () -> Unit>> = ShelfDensity.entries.map { d ->
            val mark = if (d == shelfCur) "◉" else "○"
            "$mark  ${d.label} · ${d.columns} across" to {
                ShelfDensity.set(requireContext(), d)
                showMessage("Bookshelf · ${d.label}")
            }
        }
        // READ-ALONG: pair this book with its Audiobookshelf recording; once paired, the doors to
        // listen, to fix a chapter the matcher got wrong, and to unpair. Configures the surface,
        // so it lives on the wrench; the ACT of listening is the rail's 🎧 (the dedupe rule).
        val pairing = readAlongPairing
        val listenRows: List<Pair<String, () -> Unit>> = when {
            isComicOpen() || !bookReady -> emptyList()
            pairing == null -> listOf("🎧  Listen along… (Audiobookshelf)" to { showReadAlongPicker() })
            else -> listOf(
                "🎧  Listen along · ${pairing.audioTitle}" to { listenAlong() },
                "⏮  From this chapter's start" to { listenAlong(ReadAlong.StartAt.CHAPTER_START) },
                "⏩  From where the audiobook left off" to { listenAlong(ReadAlong.StartAt.AUDIO_RESUME) },
                "🔁  Re-pair… · ${pairing.summary}" to { showReadAlongRepair() },
                "✂  Unpair" to { unpairReadAlong() },
            )
        }
        // ☰ Hub, the first row (Michael 2026-10-12: the touch route to the main menu, since the
        // column's Hub seat went — the left edge goes back to the shelf first, then the Hub).
        showDirectory(withHubRows(listOfNotNull(
            "Books" to bookRows,
            "Bookshelf" to shelfRows,
            ("Reading" to readingRows).takeIf { readingRows.isNotEmpty() },
            ("Listen along" to listenRows).takeIf { listenRows.isNotEmpty() },
            "Screen" to listOf(
                "🔄  Rotate screen" to { cycleScreenOrientation(); pokeComicRefit() },
                // A book wants a clean page; not everyone wants the floating pen on top of it.
                ((if (quickNoteShown()) "🖉  Hide the quick-note button" else "🖉  Show the quick-note button")
                    to { setQuickNoteShown(!quickNoteShown()) }),
                // Every panel's More carries ☐ Tool labels (the one panel language, 2026-10-11).
                toolLabelsRow(readerRail).let { "${it.emoji}  ${it.label}" to it.action }
            ),
            "Pill size" to listOf(
                "${pt("compact")}  Compact" to { setPillTier("compact") },
                "${pt("standard")}  Standard" to { setPillTier("standard") },
                "${pt("expanded")}  Expanded" to { setPillTier("expanded") }
            ),
            "Page turn (finger)" to listOf(
                ("👆  Tap to turn: " + when (tapZoneStyle()) {
                    "sides" -> "side halves"; "off" -> "off"; else -> "corners"
                }) to {
                    val next = when (tapZoneStyle()) { "off" -> "corners"; "corners" -> "sides"; else -> "off" }
                    readerNavPrefs().edit().putString("tap_zones_style", next)
                        .putBoolean("tap_zones", next != "off").apply()
                    setupTapZones()
                    showMessage(when (next) {
                        "sides" -> "Tap either half of the page to turn"
                        "corners" -> "Tap the bottom corners to turn"
                        else -> "Tap-to-turn off"
                    })
                },
                ((if (volOn) "☑" else "☐") + "  Volume keys turn") to { toggleReaderNav("volume_turn", !volOn) }
            )
        )))
    }

    private fun quickNoteShown(): Boolean =
        (activity as? com.toolsboox.ui.main.MainActivity)?.quickNoteVisible() ?: true

    private fun setQuickNoteShown(visible: Boolean) {
        (activity as? com.toolsboox.ui.main.MainActivity)?.setQuickNoteVisible(visible)
    }

    /** Run the book's own full-text search. The JS has had `searchBook` all along — the iPad has
     *  called it since the reader shipped; Android simply never did. Results come back through the
     *  "searchResults" message. */
    private fun searchBook(query: String) {
        val esc = query.replace("\\", "\\\\").replace("'", "\\'")
        binding.readerWeb.evaluateJavascript("window.searchBook && window.searchBook('$esc')", null)
    }

    private fun goToCfi(cfi: String) {
        if (cfi.isBlank()) return
        val esc = cfi.replace("\\", "\\\\").replace("'", "\\'")
        binding.readerWeb.evaluateJavascript("window.goToHref && window.goToHref('$esc')", null)
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
    /** The finger page-turn zones, in three sizes: off · bottom CORNERS (the small defensible
     *  patch) · full SIDE HALVES ("touching the screen can take them forward or back just like
     *  the arrows"). Sides trade edge-of-page touches for never reaching for anything. */
    private fun tapZoneStyle(): String = readerNavPrefs().getString(
        "tap_zones_style",
        if (readerNavPrefs().getBoolean("tap_zones", true)) "corners" else "off")!!

    private fun setupTapZones() {
        val style = tapZoneStyle()
        // A COMIC TAKES ITS ZONES IN THE PAGE (Michael, 2026-10-11: "I like a single finger slide
        // in a comic"). These strips sit ON TOP of the WebView, so on a comic they swallowed every
        // swipe, pan, pinch and hold that began in them — in "sides" that was half the screen.
        // While a comic is open they stand down and the comic's own touch layer (reader-embed.js,
        // the COMIC-GESTURES block) routes the same taps by the same setting, posting "tap"
        // back here (see handle). Every other book keeps these native strips, unchanged.
        binding.tapZones.visibility =
            if (style == "off" || isComicOpen()) View.GONE else View.VISIBLE
        binding.readerWeb.evaluateJavascript(
            "window.setComicTapZones && window.setComicTapZones('$style')", null)
        (binding.tapZones.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            height = if (style == "sides") android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                else (120 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.BOTTOM
        }
        binding.tapZones.requestLayout()
        binding.readerTapLeft.setOnClickListener { pageTurn(next = false) }
        binding.readerTapRight.setOnClickListener { pageTurn(next = true) }
    }

    private fun pageTurn(next: Boolean) {
        // Full screen's peek goes BEFORE the page moves, so the new page covers the pill's patch in
        // the same e-ink update (ReaderFullScreen) — and a comic's centre tap still waiting out its
        // double-tap window never lands on the new page (review 2026-10-12).
        cancelReaderCentre()
        readerRail?.readerPageTurn()
        // A manga's next page is to the LEFT. The PHYSICAL turns — tap zones, a comic's swipes
        // (its "tap" message) and volume keys, which are spatial gestures — follow the book's
        // direction; the rail's arrows keep their labelled meaning (‹ always back, › forward).
        val n = if (isComicOpen() &&
            comicPrefs().getBoolean("comic_rtl_${comicKey()}", false)) !next else next
        binding.readerWeb.evaluateJavascript(
            if (n) "window.pageRight && window.pageRight()" else "window.pageLeft && window.pageLeft()", null
        )
    }

    override fun onResume() {
        super.onResume()
        com.toolsboox.ui.plugin.LedgerPlayer.addListener(transportListener)
        ReadAlong.attach(readAlongSink)   // the narration may turn THIS reader's pages
        consumeCatalogRequest()
        // Audio survives navigation (LedgerPlayer is process-wide) — so a reader you come BACK
        // to mid-listen re-seats the inline transport it left; nothing seats when idle.
        seatTransport()
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = handler@{ up ->
            if (!readerNavPrefs().getBoolean("volume_turn", true)) return@handler false
            pageTurn(next = !up)   // volume-up = back a page, volume-down = forward
            true
        }
    }

    override fun onPause() {
        super.onPause()
        com.toolsboox.ui.plugin.LedgerPlayer.removeListener(transportListener)
        ReadAlong.detach(readAlongSink)   // off screen: the narration plays on, the pages stay
        runCatching { com.toolsboox.plugin.feeds.ui.NowPlayingCard.dismissSummon(requireActivity()) }
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null
        // Closing the book (or the app going behind something) is the moment the debounced
        // reading-state push must not wait out its quiet timer — "after five still seconds" may
        // be after the process is gone, and the page you closed on is the page the next device
        // should open to.
        context?.let { com.toolsboox.plugin.calendar.ot.ReadingStateStore.flush(it) }
        // The attention clock stops with the page — a paused reader is a closed session; a
        // return mints a fresh one at the next open. Sub-10s blinks write nothing.
        context?.let { com.toolsboox.plugin.calendar.ot.ReadingSessionStore.end(it) }
    }

    /** Pick from the imported books, or import a new one. */
    /**
     * The shelf, searchable.
     *
     * A shelf you can only scroll is a shelf you can only browse, and this is the surface you most
     * often arrive at already knowing which book you want. A filter field over the same list,
     * rebuilt in place as you type — on e-ink a full redraw per keystroke is still cheaper than any
     * incremental scheme, and there's no diffing to get wrong. Same shape as the tag index.
     */
    /** Filter a long table of contents rather than scrolling it. Same shape as the shelf. */
    /**
     * Search from inside a book — THIS book, or the whole ledger.
     *
     * The scope switch is the point. Reading, you want both questions and you don't want to decide
     * which surface to be on first: "where does she say that" is this book, and "what else have I
     * got on this" is everything. One field, one toggle, no trip out to another screen to change
     * your mind. Michael: "make sure you can search all of Ledger or just the current book from
     * inside a book."
     *
     * In-book hits come from the reader's own engine (foliate's `search`, via `window.searchBook`)
     * and land asynchronously; ledger hits are the same corpus the Log searches, which already
     * includes OCR'd handwriting, so a passage you wrote by hand is findable from here too.
     */
    private fun showBookSearch() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        var wholeLedger = false
        val rows = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val field = android.widget.EditText(ctx).apply {
            hint = "Search"; isSingleLine = true; textSize = 15f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val scope = android.widget.TextView(ctx).apply {
            textSize = 14f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(4), px(8), px(4), px(8))
        }
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
            addView(field); addView(scope); addView(rows)
        }
        lateinit var dialog: AlertDialog

        fun note(text: String) {
            rows.removeAllViews()
            rows.addView(android.widget.TextView(ctx).apply {
                this.text = text
                textSize = 14f; setTextColor(0xFF888888.toInt())
                setPadding(px(4), px(12), px(4), px(4))
            })
        }

        fun showBookHits(hits: List<SearchHit>) {
            rows.removeAllViews()
            if (hits.isEmpty()) { note("Nothing in this book."); return }
            for (h in hits.take(200)) {
                rows.addView(android.widget.TextView(ctx).apply {
                    text = h.excerpt.ifBlank { "…" }
                    textSize = 15f; setTextColor(0xFF000000.toInt())
                    setPadding(px(4), px(10), px(4), px(10))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { dialog.dismiss(); goToCfi(h.cfi) }
                })
            }
            com.toolsboox.ot.LedgerFonts.applyTree(rows)
        }

        fun runLedger(q: String) {
            note("Searching the ledger…")
            lifecycleScope.launch {
                val toks = com.toolsboox.plugin.calendar.ot.LedgerSearch.tokens(q)
                val hits = withContext(Dispatchers.IO) {
                    runCatching {
                        corpusService.gather(com.toolsboox.ot.LedgerPaths.documentsRoot(ctx))
                            .filter {
                                com.toolsboox.plugin.calendar.ot.LedgerSearch.matches(
                                    toks, it.title + " " + it.source + " " + it.text)
                            }.take(60)
                    }.getOrDefault(emptyList())
                }
                if (!isAdded) return@launch
                rows.removeAllViews()
                if (hits.isEmpty()) { note("Nothing in the ledger."); return@launch }
                for (h in hits) {
                    rows.addView(android.widget.TextView(ctx).apply {
                        text = (h.title.ifBlank { h.source }) + "\n" +
                            com.toolsboox.plugin.calendar.ot.LedgerSearch.window(h.text, toks)
                        textSize = 14f; setTextColor(0xFF000000.toInt())
                        setPadding(px(4), px(10), px(4), px(10))
                    })
                }
                com.toolsboox.ot.LedgerFonts.applyTree(rows)
            }
        }

        fun run() {
            val q = field.text.toString().trim()
            if (q.isBlank()) { note("Type something to look for."); return }
            if (wholeLedger) runLedger(q) else { note("Searching this book…"); searchBook(q) }
        }

        fun paintScope() {
            scope.text = if (wholeLedger) "Searching ALL of Ledger — tap for this book only"
                         else "Searching THIS BOOK — tap to search all of Ledger"
        }
        scope.setOnClickListener { wholeLedger = !wholeLedger; paintScope(); run() }
        paintScope()

        onSearchHits = { hits -> if (!wholeLedger) showBookHits(hits) }
        field.setOnEditorActionListener { _, _, _ -> run(); true }
        note("Type something to look for.")

        dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Search")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel) { _, _ -> onSearchHits = null }
            .create()
        dialog.setOnDismissListener { onSearchHits = null }
        showModal(dialog)
    }

    private fun showChapterFinder() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val rows = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val field = android.widget.EditText(ctx).apply {
            hint = "Find a chapter"; isSingleLine = true; textSize = 15f
        }
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
            addView(field); addView(rows)
        }
        lateinit var dialog: AlertDialog

        fun render(query: String) {
            rows.removeAllViews()
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) tocItems else tocItems.filter { it.label.lowercase().contains(q) }
            if (shown.isEmpty()) {
                rows.addView(android.widget.TextView(ctx).apply {
                    text = "No chapter matches “$query”."
                    textSize = 14f; setTextColor(0xFF888888.toInt())
                    setPadding(px(4), px(12), px(4), px(4))
                })
            }
            for (e in shown) {
                rows.addView(android.widget.TextView(ctx).apply {
                    text = "${"  ".repeat(e.depth)}${if (e.depth == 0) "◦ " else "· "}${e.label}"
                    textSize = 16f; setTextColor(0xFF000000.toInt())
                    setPadding(px(4), px(10), px(4), px(10))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { dialog.dismiss(); goToHref(e.href) }
                })
            }
            com.toolsboox.ot.LedgerFonts.applyTree(rows)
        }

        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        render("")

        dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Chapters")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)
    }

    private fun openShelf() {
        val ctx = requireContext()
        val all = shelfEntries().sortedBy { (it.folder + "/" + it.name).lowercase() }
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val rows = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val field = android.widget.EditText(ctx).apply {
            hint = "Find a book"; isSingleLine = true; textSize = 15f
        }
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
            addView(field); addView(rows)
        }
        lateinit var dialog: AlertDialog

        fun render(query: String) {
            rows.removeAllViews()
            val q = query.trim().lowercase()
            // The folder is searchable too: in a declared library it is how you remember where a
            // book was, and often the only thing you remember.
            val shown = if (q.isEmpty()) all
                        else all.filter {
                            it.title.lowercase().contains(q) || it.folder.lowercase().contains(q)
                        }
            var lastFolder: String? = null
            for (f in shown) {
                if (f.folder != lastFolder) {
                    lastFolder = f.folder
                    if (f.folder.isNotEmpty()) rows.addView(android.widget.TextView(ctx).apply {
                        text = "🗂  ${f.folder}"
                        textSize = 13f; setTextColor(0xFF888888.toInt())
                        setPadding(px(4), px(12), px(4), px(2))
                    })
                }
                rows.addView(android.widget.TextView(ctx).apply {
                    text = f.title
                    textSize = 16f; setTextColor(0xFF000000.toInt())
                    setPadding(px(4), px(10), px(4), px(10))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { dialog.dismiss(); openShelfEntry(f) }
                })
            }
            if (shown.isEmpty()) {
                rows.addView(android.widget.TextView(ctx).apply {
                    text = if (all.isEmpty()) "No books yet." else "Nothing matches “$query”."
                    textSize = 14f; setTextColor(0xFF888888.toInt())
                    setPadding(px(4), px(12), px(4), px(4))
                })
            }
            // Import stays at the bottom of the list, where it doesn't compete with the books.
            rows.addView(android.widget.TextView(ctx).apply {
                text = "＋  " + getString(R.string.reader_import_new)
                textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                setPadding(px(4), px(14), px(4), px(6))
                setOnClickListener {
                    dialog.dismiss()
                    openBook.launch(arrayOf("application/epub+zip", "application/pdf",
                                            "application/x-mobipocket-ebook", "*/*"))
                }
            })
            com.toolsboox.ot.LedgerFonts.applyTree(rows)
        }

        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        render("")

        dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(R.string.reader_shelf_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showModal(dialog)
    }

    /**
     * Open a book STRAIGHT FROM THE DECLARED TREE — no copy.
     *
     * The shelf's own entries live behind a tree URI, and materialising one meant copying the whole
     * file into cache before the first page could be drawn. For an epub that is a blink; for a
     * 280 MB comic it is the reason "Opening a book…" sat there for a minute. Nothing about the
     * reader needed the copy: `serve` hands the WebView a stream, and the resolver gives one.
     */
    fun loadBookUri(uri: android.net.Uri, name: String, readingId: String?) {
        // Another book: a full screen the last one's middle tap brought goes with it (iOS opens
        // every book in normal reading; the ⛶'s full screen stays, remembered as ever).
        readerRail?.readerPieceOver()
        nextBookOffered = false
        currentBookUri = uri
        currentBookName = name
        currentBookFile = null
        currentReadingId = readingId
        requireContext().getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_BOOK_URI, uri.toString())
            .putString(KEY_BOOK_NAME, name)
            .remove(KEY_BOOK)
            .apply()
        readingId?.let {
            requireContext().getSharedPreferences(PREFS, 0).edit().putString(KEY_READING_ID, it).apply()
        }
        binding.readerEmpty.visibility = View.GONE
        openWhenReady()
    }

    private fun loadBookFile(file: File, readingId: String? = null) {
        file.setLastModified(System.currentTimeMillis())   // track recency (last opened)
        // Audiobooks aren't e-books — hand them to the shared player instead of the foliate WebView.
        if (com.toolsboox.ui.plugin.LedgerPlayer.isAudioFile(file.name)) {
            com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
                requireContext(), file.nameWithoutExtension, "Audiobook", null, file.absolutePath)
            // Inline transport, not the modal: the reader is where this playback lives, so the
            // controls seat themselves ON the surface (08-12) — seek bar, times, speed, ⏹ —
            // and stay there across page turns until ⏹ or the file ends.
            seatTransport()
            return
        }
        readerRail?.readerPieceOver()   // another book: see loadBookUri
        nextBookOffered = false
        currentBookFile = file
        currentBookUri = null      // a file-backed book supersedes any streamed one
        currentBookName = file.name
        // The sync key: handed in by the shelf (which alone knows a tree entry's folder), derived
        // for plain files under the default shelf (imports, directory rows). Persisted BESIDE the
        // path in the same edit so the last-book restore gets the identity back with the file —
        // deriving it at restore time would mis-key a materialised cache copy ("<size>-<name>").
        val id = readingId ?: com.toolsboox.plugin.calendar.ot.ReadingStateStore
            .idForShelfFile(booksDir(), file)
        currentReadingId = id
        requireContext().getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_BOOK, file.absolutePath)
            .remove(KEY_BOOK_URI).remove(KEY_BOOK_NAME)
            .putString(KEY_READING_ID, id)
            .apply()
        bookReady = false
        binding.readerEmpty.visibility = View.GONE
        openWhenReady()
    }

    /**
     * Aa — THE AA TYPE PANEL, which STAYS OPEN.
     *
     * Michael, 2026-08-10: adjusting text size "drops you out of text size adjustment after one
     * button push." It was an `setItems` list, and a list dismisses when you pick from it — right
     * for a menu of destinations, wrong for a DIAL. So the steppers live in a view, they repeat,
     * and the value is on screen between them. Only the star closes, because starring is done
     * when it is done.
     *
     * Michael, 2026-10-11 ("Aa type panel is good for that"): the same panel, grown into Aa Type
     * (com.toolsboox.ui.plugin.AaTypePanel) — face, size, weight, every spacing, margins, width,
     * spread, paged/scrolled, themes and colours, the override switches and custom CSS, for books
     * and for articles. Size, line spacing, theme, spread and ★ are all still on it, still one tap
     * from the reader (the Aa seat, the hidden settings button, and now the wrench's Books rows).
     */
    private fun openSettings() {
        aaPanel = com.toolsboox.ui.plugin.AaTypePanel(
            this, ReadingType.Profile.BOOK, importFont,
            onChange = { p -> if (p == ReadingType.Profile.BOOK) scheduleReaderSettings() },
            toggleSpread = { toggleSpread() },
            closing = "★  Star this book" to { starThisBook() },
            note = fixedPageNote(),
        ).also { it.show() }
    }

    /** What the open book won't take from Aa Type, said on the panel instead of left as dials
     *  that do nothing (review, Michael 2026-10-11): a comic's and a PDF's pages are pictures. */
    private fun fixedPageNote(): String? =
        when ((currentBookName ?: currentBookFile?.name).orEmpty().substringAfterLast('.', "").lowercase()) {
            "cbz", "cbr" -> "A comic keeps its own pages — its wrench has Vertical scroll. " +
                "These settings are for books with flowing text."
            "pdf" -> "A PDF keeps its own pages. These settings are for books with flowing text."
            else -> null
        }

    /** The book profile of Aa Type — size, line, theme and spread live there now (Michael,
     *  2026-10-11); the old reader_* keys were migrated into it on first read. */
    private fun bookType(): ReadingType = ReadingTypes.load(requireContext(), ReadingType.Profile.BOOK)

    /**
     * Star the whole book onto today's Stars register, into THE BOOKS band.
     *
     * Michael, on Stars: "the only Star type missing is books on the page". Feeds could star a read,
     * a watch or a listen, and mail could file itself, but a book had no way to appear at all — you
     * could star a *passage* (which goes to the Ledger Log, a different thing entirely) and that was
     * the closest available. So the register was silent about the one kind of intake that takes the
     * longest.
     *
     * Deduped on `book://<path>` the same way [com.toolsboox.plugin.feeds.ot.FeedNoteGram.placeStarGram]
     * dedupes a feed star, so re-starring a book you are part-way through doesn't stack twins.
     */
    private fun starThisBook() {
        // By NAME first — a streamed book (a comic off the declared tree) has no File, and the
        // File-only test made ★ answer "No book open" with a comic open on screen. The dedupe
        // key prefers the path where one exists (stable for app-directory books, matching every
        // star already placed) and falls back to the name, which is the fleet-portable identity.
        val name = currentBookName ?: currentBookFile?.name
        if (name == null) { showMessage("No book open"); return }
        val title = bookTitle.ifBlank { name.substringBeforeLast('.') }
        val author = bookAuthor.ifBlank { null }
        val src = currentBookFile?.let { "book://${it.absolutePath}" } ?: "book://$name"
        lifecycleScope.launch {
            // null = the placement failed (a today-file that won't read throws in place()).
            val placed: Boolean? = withContext(Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    val today = LocalDate.now()
                    // Read-only dedupe peek; place() below is the writer.
                    val day = calendarDayService.loadForDisplay(root, today, null, Locale.getDefault())
                    if (day.imageElements.any {
                            it.page == com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.INTAKE_PAGE &&
                                it.sourceLink == src
                        }) return@runCatching false
                    val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                        src, title, "books", thumb = bookCover, sourceName = author.orEmpty()
                    )
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, root, face, today,
                        com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.INTAKE_PAGE,
                        sourceLink = src, sourceLabel = listOfNotNull(title, author).joinToString(" · "),
                        cardText = title, intakeKind = "books"
                    )
                    true
                }.onFailure { Timber.w(it, "book star: gram not placed") }.getOrNull()
            }
            showMessage(when (placed) {
                true -> "★ → All Stars · THE BOOKS"
                false -> "Already starred today"
                null -> "Couldn't star that onto today's page."
            })
        }
    }

    /** E-ink: a run of stepper taps costs ONE re-lay, not one per tap (Michael, 2026-10-11). */
    private val applyTypeLater = Runnable { if (isAdded && getView() != null && ::binding.isInitialized) applyReaderSettings() }

    private fun scheduleReaderSettings() {
        binding.readerWeb.removeCallbacks(applyTypeLater)
        binding.readerWeb.postDelayed(applyTypeLater, com.toolsboox.ot.AaType.LIVE_DEBOUNCE_MS)
    }

    /**
     * The whole book look in one pass: every Aa Type setting, the @font-face block (bundled and
     * imported faces, served by [ReadingFonts.intercept]) and the chosen family land together and
     * the engine lays out ONCE (window.applyReaderType). The old four-key merge left the rest to
     * the engine's defaults; the full object states everything, and its defaults ARE those
     * defaults, so an untouched profile draws exactly as before. A comic's flow is its own
     * (comic_flow_<book>), so the payload leaves flow out while one is open.
     */
    private fun applyReaderSettings() {
        binding.readerWeb.removeCallbacks(applyTypeLater)   // this pass covers any pending one
        val t = bookType()
        val json = t.toFoliateJson(includeFlow = !isComicOpen())
        binding.readerWeb.evaluateJavascript(
            "window.applyReaderType ? window.applyReaderType(${readerTypeArgs(t)})" +
                " : (window.applyReaderSettings && window.applyReaderSettings($json))", null
        )
    }

    /** The engine's three Aa Type arguments as JS: the settings object, the @font-face block and
     *  the family. One string, so the pre-open push and 'loaded' can compare what each would send. */
    private fun readerTypeArgs(t: ReadingType = bookType()): String {
        val ctx = requireContext()
        val json = t.toFoliateJson(includeFlow = !isComicOpen())
        val faces = JSONObject.quote(ReadingFonts.fontFacesCss(ctx))
        val family = JSONObject.quote(ReadingFonts.familyCss(ctx, t))
        return "$json, $faces, $family"
    }

    /**
     * Two pages side by side, like an open book. Michael, 2026-08-10.
     *
     * The engine could always do it — foliate takes a `max-column-count` and the paginator has the
     * spread arithmetic — so this is a toggle rather than a feature: the setting simply had no way
     * to be reached from Android.
     *
     * Not automatic on rotation, deliberately. A spread is the right shape for a wide screen most
     * of the time, but "most of the time" is not a claim worth making on someone else's behalf
     * about the thing they read in bed. It is a choice, it persists, and it is one tap.
     */
    private fun toggleSpread() {
        val on = ReadingTypes.edit(requireContext(), ReadingType.Profile.BOOK) {
            it.copy(columns = if (it.columns == 2) 1 else 2)
        }.columns == 2
        // At once, not debounced: one tap, and the comic push below has always followed it.
        applyReaderSettings()
        // The mirror of the panel toggle's one-page claim: a spread and the panel walk cannot
        // both own the screen, and whichever was chosen LAST wins.
        if (on && isComicOpen()) {
            val book = comicKey()
            if (comicPrefs().getBoolean("comic_panels_$book", false)) {
                comicPrefs().edit().putBoolean("comic_panels_$book", false).apply()
                pushComicPrefs()
            }
        }
        showMessage(if (on) "Two pages" else "One page")
    }

    /** Serve foliate assets and the current book over the same-origin host. */
    private fun serve(url: Uri): WebResourceResponse? {
        // The reading faces (Aa Type) answer at their own address, for every reading WebView.
        if (url.host == ReadingFonts.HOST) return context?.let { ReadingFonts.intercept(it, url) }
        if (url.host != HOST) return null
        val path = url.path ?: return null
        return try {
            // "/book/current" AND "/book/current.cbz" — the extension is carried in the URL on
            // purpose; see [openCurrentBookInEngine] for why foliate needs it there.
            if (path.startsWith("/book/current")) {
                // The tree URI wins when there is one: streaming from where the book already lives
                // is what makes a large comic open now rather than after its own size in copying.
                val uri = currentBookUri
                val name = currentBookName ?: currentBookFile?.name
                if (uri != null && name != null) {
                    // A tree book whose folder grant has lapsed (a reboot, a re-added folder)
                    // throws here; the engine then loads nothing and the WebView shows its own
                    // "webpage" error — meaningless to a reader (Michael, 09-13, on the B6). A
                    // shelf copy of the same name stands in when there is one; otherwise say it.
                    val stream = runCatching { requireContext().contentResolver.openInputStream(uri) }.getOrNull()
                    if (stream != null) WebResourceResponse(mimeFor(name), null, stream)
                    else {
                        val copy = File(booksDir(), name).takeIf { it.isFile && it.length() > 0 }
                        if (copy != null) WebResourceResponse(mimeFor(name), null, copy.inputStream())
                        else {
                            activity?.runOnUiThread { showMessage("Couldn't reach ${name.substringBeforeLast('.')} in its folder — open it from the Bookshelf again.") }
                            notFound()
                        }
                    }
                } else {
                    val f = currentBookFile ?: return notFound()
                    WebResourceResponse(mimeFor(f.name), null, f.inputStream())
                }
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
            // Push on add: a share-in / open-with import is an add like any other — it lands in
            // the app's own books directory, so it belongs to the fleet from this moment.
            LibraryHub.pushAdded(requireContext().applicationContext, "", file.name)
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
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        // A STREAMED book first: it is the newer shape, and a stale KEY_BOOK from an earlier
        // session must not win over the book actually last opened.
        val savedUri = prefs.getString(KEY_BOOK_URI, null)
        val savedName = prefs.getString(KEY_BOOK_NAME, null)
        if (savedUri != null && savedName != null) {
            currentBookUri = android.net.Uri.parse(savedUri)
            currentBookName = savedName
            currentBookFile = null
            currentReadingId = prefs.getString(KEY_READING_ID, null)
            pendingOpen = true
            return
        }
        val path = prefs.getString(KEY_BOOK, null) ?: return
        val f = File(path)
        if (f.exists()) {
            currentBookFile = f
            // The identity saved beside the path (see [loadBookFile]); a pre-upgrade pref has no
            // saved id, so derive one — right for shelf files, and for a stale cache copy it only
            // means this session keys under the cache name until the book is next opened properly.
            currentReadingId = prefs.getString(KEY_READING_ID, null)
                ?: com.toolsboox.plugin.calendar.ot.ReadingStateStore.idForShelfFile(booksDir(), f)
            pendingOpen = true
        }
    }

    /** Jump back to where we left off in this book (the CFI stored on the last relocate). */
    private fun restoreReadingPosition() {
        val book = (currentBookName ?: currentBookFile?.name)?.substringBeforeLast('.')
            ?: return
        val ctx = requireContext().applicationContext
        // Resume instantly from the LOCAL spot, then re-jump once the pull merges if another
        // device's position is newer. The old fire-and-forget sync raced this restore: the
        // reader jumped to the stale local spot, the first page turn re-stamped it as newest,
        // and the push reverted the other device's progress — silently, on every open.
        fun jump(cfi: String) {
            val esc = cfi.replace("\\", "\\\\").replace("'", "\\'")
            binding.readerWeb.evaluateJavascript("window.goToCfi && window.goToCfi('$esc')", null)
        }
        // Two stores answer here while the fleet crosses over: `reading-state.json`
        // (folder-qualified name+size key, the 2026-08-11 Listen/Library sidecar) is
        // authoritative when it knows the book; `reader/positions.json` (bare-name key) is the
        // fallback for books whose progress predates the new file. Only ONE of them may drive
        // the re-jump — two merge callbacks each entitled to move the page would race each
        // other's landing — so the new store drives whenever the book has a sync key, and the
        // legacy file is still round-tripped (no callback) to keep devices on older builds fed.
        val id = currentReadingId
        val localState = id?.let { com.toolsboox.plugin.calendar.ot.ReadingStateStore.get(ctx, it) }
        val localCfi = localState?.locator ?: ReaderPositionStore.get(ctx, book)
        localCfi?.let { jump(it) }
        if (id != null) {
            com.toolsboox.plugin.calendar.ot.ReadingStateStore.sync(ctx) { merged ->
                // The merge already decided whose read is newest: if the locator it kept is not
                // the one we resumed to, another device is ahead — go there.
                val remote = merged.firstOrNull { it.id == id && !it.isDeleted }
                if (remote != null && remote.locator.isNotBlank() && remote.locator != localCfi) {
                    binding.readerWeb.post {
                        if (!isAdded) return@post
                        skipRelocates++    // the re-jump's own relocate must not re-stamp
                        jump(remote.locator)
                    }
                }
            }
            ReaderPositionStore.sync(ctx)
        } else {
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
    }

    /** Ask foliate to fetch the book from our host once the engine has signalled ready. Before the
     *  engine is ready, defer (pendingOpen) — `handle("ready")` replays this so the book still opens. */
    private fun openWhenReady() {
        // Either shape counts: a materialised File, or a book streamed from the tree (loadBookUri).
        if (currentBookFile == null && currentBookUri == null) return
        if (!engineReady) { pendingOpen = true; return }
        // THE EXTENSION RIDES IN THE URL, and it is the whole reason a CBZ opens at all.
        //
        // Michael's comic failed with "Failed to load container file at EPUB.init" — foliate was
        // opening it as an EPUB and looking for META-INF/container.xml. The reason is in foliate's
        // own `fetchFile`: given a URL it builds `new File([blob], new URL(res.url).pathname)` —
        // the file's NAME becomes the path and its TYPE IS NEVER SET. `isCBZ` tests exactly those
        // two things (`type === 'application/vnd.comicbook+zip' || name.endsWith('.cbz')`), so a
        // book served at "/book/current" can only ever look like an epub, whatever it actually is.
        //
        // Putting the real extension on the URL is the fix that needs no patch to the vendored
        // engine: the name ends in ".cbz" and foliate's own routing does the rest. The MIME is set
        // correctly too ([mimeFor]) so the other half of that test also passes.
        val ext = (currentBookName ?: currentBookFile?.name).orEmpty()
            .substringAfterLast('.', "").lowercase()
        val bookUrl = if (ext.isBlank()) "$ORIGIN/book/current" else "$ORIGIN/book/current.$ext"
        // COMIC PREFS BEFORE THE BOOK — the embed applies spread/zoom between open() and the
        // first paint, so the first render is the right render (the blank-page-on-open was the
        // prefs landing mid-render and racing it). The 'loaded' push still follows, idempotent.
        if (isComicOpen()) pushComicPrefs(restorePanel = true)
        // The tap strips follow the book: down for a comic (its page routes the taps), back up
        // for anything else — and the comic layer learns the tap-zone setting before the first tap.
        setupTapZones()
        // AA TYPE BEFORE THE BOOK, for the same reason (Michael, 2026-10-11): the whole look goes
        // down ahead of the open, the embed's own lay-out between open() and the first page picks
        // it up, and the first paint is the right paint. 'loaded' used to push it AFTER the first
        // page — every book drew twice (engine defaults, then the prefs), two e-ink refreshes.
        // evaluateJavascript runs in order, so this lands before openBookURL starts.
        val args = readerTypeArgs()
        primedType = args
        binding.readerWeb.evaluateJavascript("window.primeReaderType ? window.primeReaderType($args) : false") { r ->
            if (r != "true") primedType = null   // an engine without it: 'loaded' pushes as before
        }
        binding.readerWeb.evaluateJavascript("window.openBookURL && window.openBookURL('$bookUrl')", null)
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
                // Either shape counts — a File, or a book STREAMED from the tree; testing only
                // the File made a slow engine start drop a streamed book's open on the floor.
                if (pendingOpen || currentBookFile != null || currentBookUri != null) {
                    pendingOpen = false; openWhenReady()
                }
            }
            "loaded" -> {
                bookReady = true
                // A new book: no walk step has stood anywhere yet, and nothing peeks. The page is
                // new too: it hears where the controls are (the comic's count follows them).
                walkAt = null
                cancelReaderCentre()
                readerRail?.readerPageTurn()
                pushPageReadShown(readerRail?.readerChromeShown ?: true)
                bookTitle = msg.optString("title").ifBlank { "Untitled" }
                bookAuthor = msg.optString("author")
                bookCover = null
                // Already drawn with the primed type: push only what changed since (a tap in the
                // panel mid-open), never the same look a second time.
                if (primedType == null || primedType != readerTypeArgs()) applyReaderSettings()
                primedType = null
                restoreHighlights()
                // Skip the first relocate (foliate's start-of-book report on open) so it can't clobber
                // the saved spot before we jump back to it.
                skipRelocates = 1    // foliate's start-of-book relocate
                restoreReadingPosition()
                // READ-ALONG: the pairing this book has (local copy first, then the hub's — a
                // pairing made on the other device relabels the ▶ the moment it lands).
                currentSectionIndex = -1
                readAlongPairing = readAlongKey()?.let { ReadAlong.load(requireContext(), it) }
                // A recording chosen on the Audiobookshelf side for THIS book (the shelf's "Read
                // along with a book…"): pair it now, the contents arriving as they do.
                requireContext().getSharedPreferences("ledger_reader_prefs", 0).let { prefs ->
                    prefs.getString("pending_pair_abs_item", null)?.let { pid ->
                        prefs.edit().remove("pending_pair_abs_item").apply()
                        pairReadAlong(Audiobookshelf.Item(id = pid, mediaType = "book", title = "the recording", author = "", narrator = "", duration = 0.0, hasAudio = true, ebookFormat = "", numEpisodes = 0))
                    }
                }
                // Opened onto a book whose audiobook is already playing (from the shelf, from Now
                // Playing's 📖): follow it, don't restart it.
                readAlongKey()?.let { key -> readAlongPairing?.let { p ->
                    if (AbsPlayback.playingItemId() == p.absItemId) {
                        val app = requireContext().applicationContext
                        Thread { runCatching { ReadAlong.adopt(app, key, p) } }.start()
                    }
                } }
                readAlongKey()?.let { key ->
                    ReadAlong.pull(requireContext(), key) { merged ->
                        if (isAdded && readAlongKey() == key && merged != readAlongPairing) {
                            readAlongPairing = merged; rebuildActionRail("reader")
                        }
                    }
                }
                // A fresh open: the why-whole sentences may speak again (once per open, per
                // mode — never persisted, so a book is never silent for life), and the index
                // in hand is whatever this open gets pushed.
                fallbackSaid.clear()
                balloonJson = null
                // A comic gets its per-book settings the moment it is open — panel mode, reading
                // direction, and the panel the walk stood on last time.
                if (isComicOpen()) {
                    pushComicPrefs(restorePanel = true)
                    // After the position restore has had its say: the pass starts AT THE PAGE
                    // ON SCREEN, and the page is known only once foliate has relocated to it.
                    binding.readerWeb.postDelayed({ if (isAdded && bookReady) maybeIndexBalloons() }, 900)
                }
                // The rail follows the book.
                readerRail?.markActive(null)
                rebuildActionRail("reader")
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
            "turned" -> {
                // A HAND'S TURN in the page (the embed's 'turned', iOS's twin): foliate's own swipe
                // in a text book or a PDF, a scroll in scrolled flow — only when the place moved,
                // never a re-lay (Aa, a rotation, the snap-back of a short swipe or of a tap that
                // moved a pixel) or a jump. It puts full screen's peek away (ReaderFullScreen;
                // review 2026-10-12: every relocate used to, so the snap after a centre tap hid
                // the ⛶ it had just brought).
                cancelReaderCentre()
                readerRail?.readerPageTurn()
            }
            "relocate" -> {
                // Note the spot BEFORE the skip: a skipped relocate is still a true report of
                // where the reader now stands, and a bookmark made right after a jump needs it.
                currentCfi = msg.optString("cfi")
                currentLegacyCfi = msg.optString("legacyCfi")
                currentChapter = msg.optString("chapter")
                currentFraction = msg.optDouble("fraction", 0.0).let { if (it.isNaN()) 0.0 else it }
                // The spine index on screen — where a balloon pass starts, and the page the
                // Comic menu's balloon row counts for.
                msg.optInt("index", -1).takeIf { it >= 0 }?.let { comicPageIndex = it; currentSectionIndex = it }
                currentSectionFraction = msg.optDouble("sectionFraction", 0.0).let { if (it.isNaN()) 0.0 else it }
                // READ-ALONG's reverse direction: a landing in a new section moves the narration
                // there — only a hand's landing (a swallowed relocate is a restore's), never a
                // scroll within the section (ReadAlong keeps that rule).
                ReadAlong.onRelocate(requireContext(), readAlongKey(), currentSectionIndex, manual = skipRelocates == 0)
                // Foliate reports the current spot on every page turn — remember it per book (by NAME,
                // so it's portable across devices) and round-trip it through WebDAV.
                if (skipRelocates > 0) { skipRelocates--; return }
                val cfi = msg.optString("cfi")
                if (cfi.isNotBlank()) bookNoteKey().takeIf { it.isNotBlank() }?.let {
                    ReaderPositionStore.set(requireContext(), it, cfi)
                }
                // The new sidecar too, under the folder-qualified key — locator plus foliate's
                // own fraction, which is what the shelf/almanac can show as "42% read". The
                // store's push is debounced (five quiet seconds), so a fast page-turner costs
                // local writes only.
                if (cfi.isNotBlank()) currentReadingId?.let {
                    com.toolsboox.plugin.calendar.ot.ReadingStateStore
                        .record(requireContext(), it, cfi, currentFraction)
                }
                // THE LAST PAGE OFFERS THE NEXT BOOK (Michael, 08-30: "like mihon, when I
                // finish a comic in a folder I want to be able to go to the next one").
                if (currentFraction >= 0.995 && !nextBookOffered) {
                    nextBookOffered = true
                    offerNextBook()
                }
            }
            "searchResults" -> {
                val arr = msg.optJSONArray("items") ?: org.json.JSONArray()
                searchHits = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val cfi = o.optString("cfi"); if (cfi.isBlank()) null
                    else SearchHit(cfi, o.optString("excerpt"))
                }
                onSearchHits?.invoke(searchHits)
            }
            "toc" -> {
                val arr = msg.optJSONArray("items") ?: return
                tocItems = (0 until arr.length()).mapNotNull { i ->
                    val it = arr.optJSONObject(i) ?: return@mapNotNull null
                    val label = it.optString("label").trim()
                    if (label.isEmpty()) null else TocEntry(label, it.optString("href"), it.optInt("depth", 0), it.optInt("index", -1))
                }
            }
            "highlight" -> {
                val text = msg.optString("text").trim()
                val cfi = msg.optString("cfi")
                // The JS has already drawn the mark; persist its CFI so it survives a reopen.
                // The JS says which style it drew, so the stored mark can be restored as itself.
                val style = msg.optString("style").takeIf { it.isNotBlank() } ?: BookNote.STYLE_HIGHLIGHT
                val mark = if (text.isNotEmpty() && cfi.isNotBlank()) rememberHighlight(cfi, text, style) else null
                // ONE PANEL, then done ("This could all be one panel, honestly"): the mark panel
                // already asked what to do — a bare style row means a quiet mark and nothing
                // else opens; a verb row runs its one verb on the marked passage, no menu between.
                val verb = pendingMarkVerb
                pendingMarkVerb = null
                when {
                    text.isEmpty() -> showMessage("Select some text first.")
                    // 🖍 HIGHLIGHT — the style row — IS a highlight, and a highlight makes its gram
                    // pick (Michael 2026-10-12: "Highlight should make a gram pick anyway"; review
                    // 2026-10-12 R2 #1: the iPad's 🖍 placed one and the Boox's didn't). Underline,
                    // squiggle and strike stay quiet marks on both apps — they say something about
                    // the passage, they don't keep it.
                    verb == null && style == BookNote.STYLE_HIGHLIGHT && mark != null -> {
                        logHighlight(text, cfi = cfi)
                        pickOnce(mark)
                    }
                    verb == null -> logHighlight(text, cfi = cfi)   // the quiet mark still joins the Log
                    // ❝ — the same keep, named: the mark is drawn, the Log line is written, and —
                    // PARITY with the article — its ONE gram pick lands on All Picks. 🃏 Create
                    // pick left this panel: ❝ does both.
                    verb == "keep" -> {
                        logHighlight(text, cfi = cfi)
                        if (mark != null) pickOnce(mark) else showMessage("Highlight saved")
                    }
                    verb == "star" -> {
                        logHighlight(text, cfi = cfi, starred = true)
                        showMessage("Starred to your Ledger Log")
                    }
                    verb == "gramnote" -> { logHighlight(text, cfi = cfi); highlightOntoTodaysNote(text) }
                    verb == "handwrite" && mark != null -> {
                        // Writing lands ON this passage — the mark's own note editor.
                        logHighlight(text, cfi = cfi)
                        BookNotesPanel.bind(bookNoteKey())
                        BookNotesPanel.editNote(requireContext(), mark, showModal = { showModal(it) }) {
                            showMessage("Handwriting saved")
                            refaceBookCard(cfi)
                        }
                    }
                    else -> captureAnnotationVerb(verb, text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                        logHighlight(selection, note, cfi, attachment)
                        // The passage is already in the book's own list; the reader's words join
                        // it there too, so the panel shows the pair the way the day timeline does.
                        if (mark != null && !note.isNullOrBlank()) BookNoteStore.put(
                            requireContext(), bookNoteKey(),
                            mark.copy(note = note, updatedAt = System.currentTimeMillis()))
                    }
                }
            }
            "tap" -> {
                // A comic's side-zone tap or swipe (the COMIC-GESTURES block in reader-embed.js —
                // Michael, 2026-10-11). The zone is SPATIAL; pageTurn applies the manga flip, so a
                // tap, a swipe and the native strips all turn the same way. A CENTRE tap (a comic's,
                // or a prose book's or a PDF's from the click router) is the reader's middle tap: in
                // normal reading it goes into full screen, in full screen it brings the [⛶] back
                // (Michael 2026-10-12, [readerCentreTap]).
                when (msg.optString("zone")) {
                    "left" -> pageTurn(next = false)
                    "right" -> pageTurn(next = true)
                    // fx: where across the screen (0…1) — the centre here is the tap-zone
                    // setting's, so only iOS's middle 44% goes INTO full screen.
                    "center" -> readerCentreTap(msg.optDouble("fx", 0.5).toFloat())
                }
            }
            "tapAnnotation" -> {
                val cfi = msg.optString("cfi")
                val text = msg.optString("text").trim()
                if (cfi.isNotBlank()) showHighlightMenu(cfi, text)
            }
            "panelStep" -> {
                // Full screen's peek (ReaderFullScreen.walkMove): a walk's centre tap shows the
                // WHOLE page — that is its controls tap, so the [⛶] comes with it, and the tap back
                // to the panel toggles it; every other step (a panel, a page, a zoom) is a turn, and
                // the same place posted again (a re-lay, a balloon push) is neither. A comic's centre
                // tap still waiting out its double-tap window was the first half of a zoom: any
                // step that moved something cancels it. The walk's tap is never the middle tap: it
                // acts only in full screen and never goes into it (iOS's guard — its whole page acts
                // only in full screen, nothing peeking).
                val at = com.toolsboox.ot.ReaderFullScreen.WalkAt(
                    page = msg.optInt("page", -1), panel = msg.optInt("panel", -1),
                    whole = msg.optBoolean("whole", false), zoomed = msg.optBoolean("zoomed", false))
                val move = com.toolsboox.ot.ReaderFullScreen.walkMove(walkAt, at)
                walkAt = at
                if (move != com.toolsboox.ot.ReaderFullScreen.WalkMove.NONE) cancelReaderCentre()
                // Full screen only: in normal reading the walk changes nothing (ReaderFullScreen.walkStep).
                readerRail?.readerWalkStep(move)
                // Remember where the walk stands, per book — reopening resumes at this panel.
                // (Under the fleet key since slice 2, beside the settings; `book` below is the
                // balloon pass's reader key.)
                val book = balloonKey()
                val ck = comicKey()
                if (ck.isNotBlank()) comicPrefs().edit()
                    .putInt("comic_panel_at_$ck", msg.optInt("panel", -1)).apply()
                msg.optInt("page", -1).takeIf { it >= 0 }?.let { comicPageIndex = it }
                // THE FALLBACK SPEAKS ONCE (field, 08-30: "Panel by panel doesn't zoom nor
                // does balloon" — every layer was healthy in the harness; on device the walks
                // were falling back to whole pages in silence, which reads as broken). When a
                // walk is ON and this page yielded nothing, say so — once per OPEN per mode,
                // not per page: a sentence is honesty, a toast-per-turn is nagging. (Once per
                // book FOR LIFE, as it was, is how the reader ended up with a checkbox that did
                // nothing and never said why again — field, 09-10.)
                val mode = msg.optString("mode")
                if (msg.optInt("count", 0) == 0 && (mode == "panel" || mode == "balloon")) {
                    if (book.isNotBlank() && fallbackSaid.add(mode)) {
                        showMessage(
                            if (mode == "balloon") {
                                val p = com.toolsboox.plugin.reader.ot.BalloonIndex.progress(book)
                                val thisPage = com.toolsboox.plugin.reader.ot.BalloonIndex
                                    .pageBalloons(balloonJson, comicPageIndex)
                                when {
                                    thisPage != null && thisPage == 0 ->
                                        "💬 No balloons found on this page — showing it whole"
                                    // A failure is named as one (slice 2), never "none found".
                                    com.toolsboox.plugin.reader.ot.BalloonIndex
                                        .pageFailed(balloonJson, comicPageIndex) ->
                                        "💬 Balloon detection failed on this page — showing it whole" +
                                            (if (com.toolsboox.plugin.reader.ot.BalloonIndex
                                                    .pageRetryable(balloonJson, comicPageIndex))
                                                " (it's tried again next time the book opens)" else "")
                                    p != null ->
                                        "💬 Balloons are still indexing (${p.done}/${p.total} pages) — this page shows whole until its turn"
                                    !msg.optBoolean("indexed", false) ->
                                        "💬 Balloons are still indexing — pages show whole until it finishes"
                                    else -> "💬 No balloons found on this page — showing it whole"
                                }
                            } else if (msg.optString("reason") == "incomplete")
                                "🗐 Panel detection couldn't box the whole page — showing it whole so nothing is missed"
                            else "🗐 No panels found on this page — showing it whole",
                            binding.root)
                    }
                }
            }
            "comicCapture" -> {
                // Long-press on a comic page: the JS sends what is on screen (the panel when
                // cropped, the page when whole) as a JPEG data URL.
                val dataUrl = msg.optString("dataUrl")
                val comma = dataUrl.indexOf(',')
                if (comma > 0) {
                    val bmp = runCatching {
                        val bytes = android.util.Base64.decode(
                            dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    if (bmp != null) showComicCaptureMenu(
                        bmp, msg.optInt("page"), msg.optInt("pages"), msg.optInt("panel"))
                }
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
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    // Throws (caught below) rather than save over a today-file that won't read.
                    val day: CalendarDay = calendarDayService.loadForWriteOrThrow(root, today, null, locale)
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
                }
            } catch (e: Exception) {
                Timber.w(e, "failed to log book annotation")
            }
        }
    }

    // --- COMICS ---------------------------------------------------------------------------

    /** The open book's extension — a streamed book has no File, so ask the NAME. */
    private fun isComicOpen(): Boolean =
        (currentBookName ?: currentBookFile?.name).orEmpty()
            .substringAfterLast('.', "").lowercase() in setOf("cbz", "cbr")

    private fun comicPrefs() = requireContext().getSharedPreferences("ledger_reader_prefs", 0)

    /**
     * The key a comic's SETTINGS (direction, walks, flow, spread, fit) and its saved panel live
     * under: the fleet key — `currentReadingId`, folder/name#size — falling back to the file name
     * as `readAlongKey` does (Michael 2026-10-11, comics slice 2). The file name alone made every
     * series' "Chapter 1.cbz" share one set: a manga's right-to-left leaked into a Western comic.
     * Notes and bookmarks stay on [bookNoteKey] on purpose. NOTHING VANISHES: the first time a
     * book is opened under its fleet key, its settings are copied across (never moved — an older
     * build reading the old key still finds them) from the same file in another folder, else from
     * the file-name key ([com.toolsboox.plugin.reader.ot.ComicSettingsCarry], review 2026-10-11:
     * re-pointing the Palma's folder must not revert every book). The file-name key is never
     * written again, so a NEW series' "Chapter 1.cbz" doesn't inherit the last one's settings.
     */
    private fun comicKey(): String {
        val legacy = bookNoteKey()
        val key = currentReadingId ?: legacy
        if (key != legacy && legacy.isNotBlank() && comicKeysCarried.add(key)) {
            val p = comicPrefs()
            val all = p.all
            val source = com.toolsboox.plugin.reader.ot.ComicSettingsCarry.source(key, legacy, all.keys, COMIC_PREF_NAMES)
            val e = p.edit()
            var any = false
            for (name in COMIC_PREF_NAMES) {
                val from = "comic_${name}_$source"
                val to = "comic_${name}_$key"
                if (p.contains(to) || !p.contains(from)) continue
                when (val v = all[from]) {
                    is Boolean -> e.putBoolean(to, v)
                    is String -> e.putString(to, v)
                    is Int -> e.putInt(to, v)
                    else -> continue
                }
                any = true
            }
            if (any) e.apply()
        }
        return key
    }

    /** Fleet keys whose file-name settings were already carried across this session. */
    private val comicKeysCarried = mutableSetOf<String>()

    /**
     * Hand the reader JS this book's comic settings — panel mode, reading direction, and (on
     * open) the panel the walk stood on last time. The JS re-lays the current page immediately,
     * so a toggle shows its effect where the choice was made.
     */
    private fun pushComicPrefs(restorePanel: Boolean = false) {
        val book = comicKey()
        val rtl = comicPrefs().getBoolean("comic_rtl_$book", false)
        val panelMode = comicPrefs().getBoolean("comic_panels_$book", false)
        val balloonMode = comicPrefs().getBoolean("comic_balloons_$book", false)
        val restore = if (restorePanel) comicPrefs().getInt("comic_panel_at_$book", -1) else -1
        val flow = comicPrefs().getString("comic_flow_$book", "paginated")
        val zoom = comicPrefs().getString("comic_zoom_$book", "fit-page")
        val spread = comicPrefs().getString("comic_spread_$book", "none")
        binding.readerWeb.evaluateJavascript(
            "window.setComicPrefs && window.setComicPrefs(" +
                "{isComic:${isComicOpen()},rtl:$rtl,panelMode:$panelMode,balloonMode:$balloonMode," +
                "restorePanel:$restore," +
                "flow:'$flow',zoom:'$zoom',spread:'$spread'})",
            null
        )
    }

    /**
     * The balloon index's READER key: the fleet key (folder/name#size), else the file name — the
     * running pass, its progress, its boost and its guards all ride it, so two series' "Chapter
     * 1.cbz" never adopt each other's pass (review 2026-10-11). A plain string: no disk.
     */
    private fun balloonKey(): String = currentReadingId ?: bookNoteKey()

    /** The spine index on screen (relocate/panelStep keep it current) — where a balloon pass
     *  starts and the page the Comic menu's balloon row counts for. */
    @Volatile private var comicPageIndex = 0       // written on main, read by the indexing thread
    /** The balloon index as last pushed to the reader JS for the open book (partial or done). */
    private var balloonJson: String? = null
    /** Which why-whole sentences this OPEN has already said ("panel" / "balloon" / "fail"). */
    private val fallbackSaid = mutableSetOf<String>()

    private enum class BalloonRow { COUNT, INDEXING, NONE, CBR, NOT_INDEXED }

    private fun balloonRowKind(): BalloonRow {
        val book = balloonKey()
        val ext = (currentBookName ?: currentBookFile?.name).orEmpty()
            .substringAfterLast('.', "").lowercase()
        if (ext == "cbr") return BalloonRow.CBR
        val bi = com.toolsboox.plugin.reader.ot.BalloonIndex
        val json = balloonJson ?: bi.partial(requireContext().applicationContext, book)
        val here = bi.pageBalloons(json, comicPageIndex)
        return when {
            here != null && here > 0 -> BalloonRow.COUNT
            here != null -> BalloonRow.NONE
            bi.isIndexing(book) -> BalloonRow.INDEXING
            // Detection failed here but has tries left: the "index now" door retries it.
            bi.pageRetryable(json, comicPageIndex) -> BalloonRow.NOT_INDEXED
            json != null && runCatching { org.json.JSONObject(json).optBoolean("done") }.getOrDefault(false) ->
                BalloonRow.NONE     // finished, and this page simply has none
            else -> BalloonRow.NOT_INDEXED
        }
    }

    /** The words after "💬 Balloon by balloon · " — the same five on both platforms. */
    private fun balloonRowState(): String {
        val book = balloonKey()
        val bi = com.toolsboox.plugin.reader.ot.BalloonIndex
        return when (balloonRowKind()) {
            BalloonRow.CBR -> "not available for CBR"
            BalloonRow.NOT_INDEXED -> "not indexed yet — index now"
            BalloonRow.INDEXING -> bi.progress(book)
                ?.let { "indexing… (${it.done}/${it.total} pages)" } ?: "indexing…"
            BalloonRow.NONE -> "none found on this page"
            BalloonRow.COUNT -> {
                val json = balloonJson ?: bi.partial(requireContext().applicationContext, book)
                "${bi.pageBalloons(json, comicPageIndex) ?: 0} on this page"
            }
        }
    }

    /**
     * 💬 The balloon index rides the book open: cached → pushed at once; unindexed → a
     * background pass over the archive (Seeneva's own architecture: inference off the page-turn
     * path, never at page-turn) that starts AT THE PAGE ON SCREEN, pushes the first pages one by
     * one and writes every partial down, so a close mid-pass loses nothing and the next open
     * resumes. [urgent] is the menu door: the pass runs above normal priority. CBR (RAR) is
     * honestly skipped — see [com.toolsboox.plugin.reader.ot.BalloonIndex].
     */
    private fun maybeIndexBalloons(urgent: Boolean = false) {
        val book = balloonKey()
        val ext = (currentBookName ?: currentBookFile?.name).orEmpty()
            .substringAfterLast('.', "").lowercase()
        val file = currentBookFile
        val uri = currentBookUri
        val ctx = requireContext().applicationContext
        val fleetKey = currentReadingId
        com.toolsboox.plugin.reader.ot.BalloonIndex.ensure(
            ctx, book, isCbz = ext == "cbz",
            // The central directory, not ZipInputStream (slice 2): Mihon/Suwayomi CBZs open.
            open = { com.toolsboox.plugin.reader.ot.CbzDirectory.open(ctx, file, uri, spool = true) },
            alias = fleetKey,
            legacyKey = bookNoteKey().takeIf { it.isNotBlank() },
            startAt = { comicPageIndex },
            urgent = urgent,
            onFail = { reason ->
                // Once per open: a balloon walk that can never arm must say why (field, 08-30).
                activity?.runOnUiThread {
                    if (!isAdded || balloonKey() != book) return@runOnUiThread
                    if (fallbackSaid.add("fail")) {
                        showMessage("💬 Balloon indexing unavailable — $reason", binding.root)
                    }
                }
            },
        ) { json, pages, balloons, fresh ->
            activity?.runOnUiThread {
                if (!isAdded || balloonKey() != book) return@runOnUiThread
                balloonJson = json
                binding.readerWeb.evaluateJavascript(
                    "window.setBalloonIndex && window.setBalloonIndex($json)", null)
                if (fresh) {
                    // HONEST COUNT (field, 08-30): the toast reports what actually happened —
                    // pages that failed detection are named, never folded into "indexed".
                    val failed = runCatching { org.json.JSONObject(json).optInt("failed", 0) }.getOrDefault(0)
                    showMessage(
                        "💬 Balloons indexed · $balloons across $pages pages" +
                            (if (failed > 0) " · $failed pages failed" else ""),
                        binding.root)
                }
            }
        }
    }

    /**
     * Long-press on a comic page — the merger of Ledgable's flow and the joy of reading comics:
     * what is on screen (the panel when cropped, the page when whole) becomes a thing you can
     * KEEP. Save it, gram it onto today's page, or annotate it in the reader's own words.
     */
    private fun showComicCaptureMenu(bmp: android.graphics.Bitmap, page: Int, pages: Int, panel: Int) {
        val where = buildString {
            append("Page ").append(page)
            if (pages > 0) append(" of ").append(pages)
            if (panel > 0) append(" · panel ").append(panel)
        }
        val title = bookTitle.ifBlank { bookNoteKey() }
        showIconMenu("$title — $where", listOf(
            "💾  Save image" to { saveComicImage(bmp) },
            "🃏  Create pick" to { comicGram(bmp) },
            "🖍  Annotate" to { annotateComicCapture(bmp, where) },
            // 🔊 The balloon's whole point (Seeneva's headline, ours now): what is on screen —
            // a balloon in the balloon walk, a panel, the page — is read ALOUD: printed-text
            // OCR (the house ladder's ML Kit rung; comics are print, not handwriting) into the
            // same transport every read-aloud wears.
            "🔊  Read this aloud" to { readComicCaptureAloud(bmp, where) },
        ))
    }

    /** OCR the capture and speak it — [showComicCaptureMenu]'s 🔊 verb. */
    private fun readComicCaptureAloud(bmp: android.graphics.Bitmap, where: String) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { com.toolsboox.plugin.calendar.ot.PanelOcr.recognizeImage(bmp) }
                    .getOrDefault("")
            }
            if (!isAdded) return@launch
            if (text.isBlank()) { showMessage("No words found in this crop", binding.root); return@launch }
            com.toolsboox.ui.plugin.LedgerPlayer.start(
                requireContext(), bookTitle.ifBlank { bookNoteKey() }, where, null, text)
            seatTransport()
        }
    }

    /** The panel/page into the device's Pictures — for use OUTSIDE Ledger (a wallpaper, a chat). */
    private fun saveComicImage(bmp: android.graphics.Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "ledger-comic-${System.currentTimeMillis()}.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29)
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ledger")
                }
                val uri = requireContext().contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                requireContext().contentResolver.openOutputStream(uri)!!.use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                }
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (isAdded) showMessage(if (ok) "Saved to Pictures/Ledger" else "Couldn't save the image.")
            }
        }
    }

    /** The panel/page as a photo gram on TODAY'S page — the same avGrams every capture uses,
     *  so it syncs, appears on the day timeline, and can be placed like any other gram. */
    private fun comicGram(bmp: android.graphics.Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val dest = File(attachmentsDir(), "photo-${UUID.randomUUID()}.jpg")
                dest.outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                }
                val att = com.toolsboox.da.Attachment(
                    UUID.randomUUID().toString(), com.toolsboox.da.Attachment.Kind.PHOTO,
                    dest.name, null, Date())
                val today = LocalDate.now()
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    val day = calendarDayService.loadForWriteOrThrow(documentsRoot(), today, null, Locale.getDefault())
                    day.avGrams.add(att)
                    calendarDayService.save(documentsRoot(), today, day)
                }
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (isAdded) showMessage(if (ok) "Pick on today's page" else "Couldn't make the pick.")
            }
        }
    }

    /**
     * Annotate the captured panel: the image is the passage, and the shared capture verbs add
     * the reader's words — typed, handwritten, or spoken. Both ride ONE ReadingEvent onto
     * today's page, so the pair shows together on the timeline and in the Log.
     */
    private fun annotateComicCapture(bmp: android.graphics.Bitmap, where: String) {
        val imageAtt = runCatching {
            val dest = File(attachmentsDir(), "photo-${UUID.randomUUID()}.jpg")
            dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            com.toolsboox.da.Attachment(
                UUID.randomUUID().toString(), com.toolsboox.da.Attachment.Kind.PHOTO,
                dest.name, null, Date())
        }.getOrNull()
        // The canon's ★ and 📝 for a panel: star = the panel's ReadingEvent starred; onto today's
        // note = the panel image itself as a photo gram on today's page (comicGram).
        captureAnnotation(
            where, bookTitle.ifBlank { null },
            onStar = {
                logComicAnnotation(where, null, imageAtt, null, starred = true)
                showMessage("Starred to your Ledger Log")
            },
            onPlaceGram = { comicGram(bmp) }
        ) { _, note, extra ->
            logComicAnnotation(where, note, imageAtt, extra)
        }
    }

    /** [logHighlight]'s comic twin: the panel image plus whatever the capture added. */
    private fun logComicAnnotation(
        where: String, note: String?, image: com.toolsboox.da.Attachment?,
        extra: com.toolsboox.da.Attachment?, starred: Boolean = false
    ) {
        val title = bookTitle
        val author = bookAuthor
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val root = documentsRoot()
                val today = LocalDate.now()
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    // Throws (caught below) rather than save over a today-file that won't read.
                    val day: CalendarDay = calendarDayService.loadForWriteOrThrow(root, today, null, Locale.getDefault())
                    day.readingEvents.add(
                        ReadingEvent(
                            id = "bookhl-${UUID.randomUUID()}",
                            kind = ReadingEvent.Kind.BOOK,
                            date = Date(),
                            title = title,
                            source = author.ifBlank { null },
                            excerpt = where,
                            note = note?.ifBlank { null },
                            attachments = listOfNotNull(image, extra).toMutableList().takeIf { it.isNotEmpty() },
                            starred = starred
                        )
                    )
                    calendarDayService.save(root, today, day)
                }
            } catch (e: Exception) {
                Timber.w(e, "failed to log comic annotation")
            }
        }
    }

    /** The legacy CFI-only highlight store — read once per book, to be adopted by [BookNoteStore]. */
    private fun highlightPrefs() = requireContext().getSharedPreferences(HL_PREFS, 0)

    private fun bookKey(): String? = currentBookName ?: currentBookFile?.name

    /** Marks are keyed by book NAME, like the reading position — the file path differs per device.
     *  currentBookName first: a STREAMED book (a comic off the declared tree) has no File, and
     *  keying off the File alone left every such book with a blank key — no marks, no bookmarks,
     *  no notes, silently. */
    private fun bookNoteKey(): String =
        (currentBookName ?: currentBookFile?.name).orEmpty().substringBeforeLast('.')

    /** Record a highlight as a [BookNote] so it survives a reopen AND can be listed per book —
     *  foliate only draws annotations that are (re-)added to the current view. */
    private fun rememberHighlight(
        cfi: String, text: String, style: String = BookNote.STYLE_HIGHLIGHT
    ): BookNote? {
        if (cfi.isBlank()) return null
        val book = bookNoteKey().ifBlank { return null }
        val now = System.currentTimeMillis()
        val mark = BookNote(
            id = BookNote.newId(), type = BookNote.ANNOTATION, cfi = cfi,
            chapter = currentChapter, fraction = currentFraction, text = text,
            style = style, createdAt = now, updatedAt = now
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
            // …AS THE STYLE IT WAS MADE IN. Restoring every mark as a highlight would quietly
            // rewrite the reader's own distinctions on the next open — an underline he meant as
            // "true" and a strike he meant as "wrong" would come back saying the same thing.
            if (mark.cfi.isNotBlank()) binding.readerWeb.evaluateJavascript(
                "window.addStoredHighlight && window.addStoredHighlight('$escaped', '${mark.style}')", null)
        }
    }

    /** A bookmark for where the reader is standing — Readest's toggle: one per location, and
     *  tapping it again takes it back. Labelled with the chapter, or the percentage when the
     *  book has no table of contents to name it by. */
    private fun toggleBookmark() {
        val book = bookNoteKey().ifBlank { showMessage("Open a book first."); return }
        if (currentCfi.isBlank()) { showMessage("Turn a page first — no location yet."); return }
        val existing = bookmarkHere(book)
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

    /** The bookmark on the page on screen — by its CFI, or (a comic whose page order moved in
     *  slice 2) by the CFI the old order gave this page, so an older bookmark still counts. */
    private fun bookmarkHere(book: String): BookNote? =
        BookNoteStore.bookmarkAt(requireContext(), book, currentCfi)
            ?: currentLegacyCfi.takeIf { it.isNotBlank() }?.let { BookNoteStore.bookmarkAt(requireContext(), book, it) }

    /** The book's marks — annotations, bookmarks, and notes about the book itself. */
    private fun showBookNotes(startType: String) {
        val book = bookNoteKey().ifBlank { showMessage("Open a book first."); return }
        BookNotesPanel.bind(book)
        BookNotesPanel.show(
            requireContext(), book, tocItems.map { it.label }, startType,
            showModal = { showModal(it) },
            onJump = { mark ->
                skipRelocates++   // the jump's own relocate must not re-stamp the saved spot
                val esc = mark.cfi.replace("\\", "\\\\").replace("'", "\\'")
                binding.readerWeb.evaluateJavascript("window.goToCfi && window.goToCfi('$esc')", null)
            },
            // A note written from the list reaches the mark's ONE card too (step 3 left it open).
            onEdited = { mark -> if (mark.type == BookNote.ANNOTATION && mark.cfi.isNotBlank()) refaceBookCard(mark.cfi) },
            onUnmark = { mark ->
                if (mark.type == BookNote.ANNOTATION && mark.cfi.isNotBlank()) {
                    val esc = mark.cfi.replace("\\", "\\\\").replace("'", "\\'")
                    binding.readerWeb.evaluateJavascript("window.deleteHighlight && window.deleteHighlight('$esc')", null)
                }
                // Its card goes with it (or asks, naming the board — Michael 2026-10-12).
                dropCardsOfDeleted(mark)
            }
        )
    }

    /**
     * A note about the book rather than a passage in it — typed, or written by hand.
     *
     * It used to answer a saved note with "Noted", which says that something happened and nothing
     * about what. Michael: "the pen on the pill lets me write on the book which I love, but what
     * exactly does it do after that." It goes to this book's marks, alongside its highlights and
     * bookmarks — so the confirmation names that, and offers the trip rather than making you go
     * looking for a thing you cannot be sure exists.
     */
    private fun composeBookNote() {
        val book = bookNoteKey().ifBlank { showMessage("Open a book first."); return }
        BookNotesPanel.bind(book)
        BookNotesPanel.editNote(requireContext(), null, showModal = { showModal(it) }) {
            // getView(), not `view` — on ScreenFragment that name is the layout RESOURCE ID.
            val root = getView() ?: return@editNote
            com.google.android.material.snackbar.Snackbar
                .make(root, "Saved to this book's notes", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Show") { showBookNotes(BookNote.NOTE) }
                .show()
        }
    }

    /** Tapping an existing highlight opens its menu: copy the passage, add a note, or remove it —
     *  not just Delete. (Selecting fresh text still uses the WebView's own Copy/Share popup + the
     *  ✎ pill to highlight.) */
    /**
     * HOW TO MARK THIS PASSAGE — Readest's four, offered before the mark is made.
     *
     * Michael, 2026-08-23: the reader "needs more highlight and underline options like in the
     * readest that it forks". One yellow band was the whole vocabulary, which on a grayscale
     * screen is worse than it sounds — the band is nearly invisible there (see the einkHighlight
     * note in reader-embed.js), so the underline was already doing the visible work while
     * pretending to be a highlight's trim.
     *
     * The four are DISTINCTIONS, not decoration: a highlight says "this", an underline says
     * "true", a squiggle says "unsure", a strike says "wrong". That is why the style is stored
     * with the mark and restored with it — coming back as a highlight would erase the reader's
     * own judgment about the passage.
     */
    private fun showMarkStylePicker() {
        // ONE PANEL, HONESTLY (his ruling, 2026-08-24: "This could all be one panel"). It used
        // to be two — pick a style, then a SECOND panel of annotation verbs with no handwriting
        // anywhere in it, while the handwriting door sat on the tap-an-EXISTING-mark menu where
        // you never are at the moment of marking. Now: the four styles are quick marks (tap one,
        // the passage is marked, nothing else opens), and the verbs below mark-then-act — one
        // decision, one panel, handwriting a first-class row.
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        for (style in BookNote.STYLES) rows += BookNote.styleLabel(style) to {
            pendingMarkVerb = null
            binding.readerWeb.evaluateJavascript(
                "window.markSelection && window.markSelection('$style')", null)
        }
        // THE CANON (09-10): the same seven verbs, in the same order, as every other annotate
        // surface — ❝ Save highlight leads (a bare highlight is a complete annotation; it is the
        // plain highlight style with its name said out loud), then the mediums, ★, and 📝. The
        // extras (upload, pick, Ask, Educate) follow the seven.
        val verbs = listOf(
            "keep" to "❝  Save highlight",
            "handwrite" to "✍  Handwrite a note",
            "voice" to "🎤  Record a voice note",
            "photo" to "📷  Photo",
            "note" to "⌨  Type a note",
            "star" to "★  Star it",
            "gramnote" to "📝  Onto today's note",
            "upload" to "🖼  Upload a picture",
            // 🃏 Create pick folded into ❝ (Michael 2026-10-12, the article's ruling, applied here
            // for parity: "Highlight should make a gram pick anyway").
            "ask" to "🔎  Ask about this",
            "educate" to "🎓  Educate me",
        )
        for ((key, label) in verbs) rows += label to {
            pendingMarkVerb = key
            binding.readerWeb.evaluateJavascript(
                "window.markSelection && window.markSelection('highlight')", null)
        }
        showIconMenu("Mark this passage", rows)
    }

    /** Which verb the mark panel chose, run when the JS reports the mark made ("highlight"). */
    private var pendingMarkVerb: String? = null

    /** The reader's rail, retained so the lasso mode can box its icon while live. */
    private var readerRail: com.toolsboox.ot.TuckPanel? = null

    /** Where the comic's last "panelStep" stood — [handle]'s "panelStep" reads the next against it. */
    private var walkAt: com.toolsboox.ot.ReaderFullScreen.WalkAt? = null

    /** The comic's "12 / 48" shows only with the reader's controls (review 2026-10-12, iOS's
     *  setPageReadShown): away in full screen, back with the peek. */
    private fun pushPageReadShown(shown: Boolean) {
        if (!::binding.isInitialized) return
        runCatching {
            binding.readerWeb.evaluateJavascript("window.setPageReadShown && window.setPageReadShown($shown)", null)
        }
    }

    /** A comic's centre tap waiting out the comic's double-tap window ([readerCentreTap]). */
    private var pendingReaderCentre: Runnable? = null

    /**
     * The reader's middle tap — the centre of the page (Michael 2026-10-12), iOS's `middleTap`
     * ([com.toolsboox.ot.TuckPanel.readerMiddleTap]): in normal reading INTO full screen, the ⛶
     * seat's own transition; in full screen the [⛶] pill and the notes pill come back, or go again.
     * The page's guards ran before it got here (reader-embed.js: links, a selection and the tap
     * that puts one down, a mark, the side zones; a comic's own layer: the walk, a hot zoom, a
     * zoom). A comic's first centre tap reaches here BEFORE its second one zooms (comic-gestures.js
     * pairs centre taps into a double-tap zoom), so a comic's waits the double-tap window (and some
     * slack, [com.toolsboox.ot.ReaderFullScreen.COMIC_CENTRE_WAIT_MS]) and a zoom's "panelStep"
     * cancels it — no flash of the pill, and no trip into full screen, on a double-tap zoom.
     * The walk's whole page never comes here: it is the walk's step alone ("panelStep").
     * [fx] is where across the screen the tap landed: the page calls "centre" whatever its tap-zone
     * setting does (by default everything above the bottom band), so only iOS's middle 44% goes
     * INTO full screen ([com.toolsboox.ot.ReaderFullScreen.inCentre]); in full screen any of it
     * brings the controls, as before.
     */
    private fun readerCentreTap(fx: Float = 0.5f) {
        cancelReaderCentre()
        val inMiddle = com.toolsboox.ot.ReaderFullScreen.inCentre(fx)
        if (!isComicOpen()) { readerRail?.readerMiddleTap(inMiddle); return }
        val r = Runnable { pendingReaderCentre = null; if (isAdded) readerRail?.readerMiddleTap(inMiddle) }
        pendingReaderCentre = r
        binding.readerWeb.postDelayed(r, com.toolsboox.ot.ReaderFullScreen.COMIC_CENTRE_WAIT_MS)
    }

    private fun cancelReaderCentre() {
        pendingReaderCentre?.let { binding.readerWeb.removeCallbacks(it) }
        pendingReaderCentre = null
    }

    /**
     * The book reader's own seats on the one panel language (spec §4.11, Michael 2026-10-11).
     * The frame's Hub, Today and Nib and the foot's Side are the shared doors ([setupPanel]).
     */
    private fun readerSeat(s: com.toolsboox.ot.PanelSeats.Seat, web: WebView): com.toolsboox.ot.TuckPanel.Item? =
        when (s) {
            // The wrench keeps its whole controls modal — shelf/import/read-along/rotate/page-turn,
            // and ☐ Tool labels with them.
            com.toolsboox.ot.PanelSeats.Seat.MORE ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.reader_controls)) { showReaderControls() }
            // Named by the seat ("Previous page" / "Next page"), never the bare chevron the
            // old strings carry — with captions off the hold must say a word (review 2026-10-11, #8).
            com.toolsboox.ot.PanelSeats.Seat.PAGE_BACK ->
                com.toolsboox.ot.PanelFaces.item(s) {
                    web.evaluateJavascript("window.pageLeft && window.pageLeft()", null)
                }
            com.toolsboox.ot.PanelSeats.Seat.PAGE_FORWARD ->
                com.toolsboox.ot.PanelFaces.item(s) {
                    web.evaluateJavascript("window.pageRight && window.pageRight()", null)
                }
            // The book's own contents — moving WITHIN the open book (TOC, marks, search). A book,
            // never a second hamburger (Michael 2026-08-24: "The second hamburger does not").
            com.toolsboox.ot.PanelSeats.Seat.BOOK ->
                com.toolsboox.ot.PanelFaces.item(s, "This book") { showBookDirectory() }
            // One tap to the book's notes and to its search (they were Book › Notes on this book…
            // and Book › Search, two taps each).
            // The seat is "Annotations" (Michael 2026-10-12: "Instead of margin, those can be
            // annotations"), so it opens the Annotations tab — it opened Notes (review #12).
            com.toolsboox.ot.PanelSeats.Seat.NOTES ->
                com.toolsboox.ot.PanelFaces.item(s) { showBookNotes(BookNote.ANNOTATION) }
            com.toolsboox.ot.PanelSeats.Seat.FIND ->
                com.toolsboox.ot.PanelFaces.item(s, "Search this book") { showBookSearch() }
            com.toolsboox.ot.PanelSeats.Seat.TYPE ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.reader_aa)) { openSettings() }
            // ONE Listen seat, one speaker face: it reads aloud, or — once the book is paired with
            // its Audiobookshelf recording — plays the narration from this page (read-along). Tap
            // starts it; the transport card seats itself and carries pause/stop/speed/🌙 from there.
            // Boxed while this book is being read aloud or narrated (a box marks what's on — iOS
            // boxes its Listen the same way; review 2026-10-11, F11).
            com.toolsboox.ot.PanelSeats.Seat.LISTEN ->
                if (readAlongPairing != null) com.toolsboox.ot.PanelFaces.item(s, "Listen along",
                    on = readAlongKey()?.let { ReadAlong.isActive(it) } == true) { listenAlong() }
                else com.toolsboox.ot.PanelFaces.item(s, "Read aloud", on = readingThisAloud()) { readAloud() }
            com.toolsboox.ot.PanelSeats.Seat.MARK ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.reader_highlight)) { showMarkStylePicker() }
            else -> null
        }

    // The ⬚ lasso MODE is gone (Michael, 09-13: "why is there a zoom lasso button on the rail…
    // get rid — in the wrench setting too"): hold to act, no modes. The box is still drawn by
    // hold-then-drag; double-tap zooms back out.

    private fun showHighlightMenu(cfi: String, text: String) {
        val ctx = requireContext()
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        // THE NOTE IS THE FIRST THING A TAPPED MARK SHOWS (field, 09-10, "the book reader too"):
        // a passage with the reader's own words on it opened a menu that never mentioned them.
        // The row reads the note and opens its editor; a mark with no note offers to add one below.
        val existing = BookNoteStore.list(ctx, bookNoteKey(), BookNote.ANNOTATION).firstOrNull { it.cfi == cfi }
        if (existing != null && (existing.note.isNotBlank() || existing.image != null)) {
            val preview = existing.note.trim().replace('\n', ' ').let { if (it.length > 80) it.take(79) + "…" else it }
            rows += ("🗒  " + (preview.ifBlank { "Handwritten note" }) + " — open") to {
                BookNotesPanel.bind(bookNoteKey())
                BookNotesPanel.editNote(ctx, existing, showModal = { showModal(it) }) {
                    showMessage("Note saved")
                    refaceBookCard(cfi)
                }
            }
        }
        if (text.isNotBlank()) rows += "📋  Copy text" to {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger", text))
            showMessage("Copied to clipboard")
        }
        // THE CANON (09-10), on an existing mark too: ❝ · ✍ · 🎤 · 📷 · ⌨ · ★ · 📝, then the
        // extras. ❝ re-files the passage into TODAY's Log — a mark made last week that you come
        // back to is a keep today as much as it was then.
        if (text.isNotBlank()) rows += "❝  Save highlight" to {
            logHighlight(text, cfi = cfi)
            // Its ONE gram pick too — once per mark (parity with the article, Michael 2026-10-12).
            val mark = existing ?: BookNoteStore.list(ctx, bookNoteKey(), BookNote.ANNOTATION).firstOrNull { it.cfi == cfi }
            if (mark != null) pickOnce(mark) else showMessage("Highlight saved")
        }
        // ✍ HANDWRITE — the pad was already built (BookNotesPanel writes a base64 PNG into
        // BookNote.image) and simply had no door at the moment you mark a passage, which is the
        // moment you want it: "the bookshelf reader annotation doesn't have a handwriting option."
        // It opens the mark's own note editor, so the writing lands ON this passage rather than
        // as a loose note about the book.
        rows += "✍  Handwrite a note" to {
            val book = bookNoteKey()
            val mark = BookNoteStore.list(ctx, book, BookNote.ANNOTATION).firstOrNull { it.cfi == cfi }
                ?: rememberHighlight(cfi, text)
            if (mark == null) {
                showMessage("Couldn't find that mark to write on.")
            } else {
                // bind() first: the panel saves under `currentBook`, and an editor opened without
                // it would file the writing against whatever book was last listed.
                BookNotesPanel.bind(book)
                BookNotesPanel.editNote(ctx, mark, showModal = { showModal(it) }) {
                    showMessage("Handwriting saved")
                    // The handwriting reaches its ONE card too (review 2026-10-12 #12).
                    refaceBookCard(cfi)
                }
            }
        }
        // The mediums ride the shared capture verbs straight (no second chooser), each filing
        // ONE ReadingEvent with the passage — the same sink the mark panel's verbs use.
        rows += "🎤  Record a voice note" to {
            captureAnnotationVerb("voice", text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                logHighlight(selection, note, cfi, attachment)
            }
        }
        rows += "📷  Photo" to {
            captureAnnotationVerb("photo", text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                logHighlight(selection, note, cfi, attachment)
            }
        }
        rows += "⌨  Type a note" to {
            captureAnnotationVerb("note", text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                logHighlight(selection, note, cfi, attachment)
                // The words land ON THE MARK too (iOS parity: the tapped mark's note shows them), and
                // its ONE card takes them — never a second card (Michael 2026-10-12: "A note added to
                // that highlight goes onto the SAME pick").
                if (!note.isNullOrBlank()) {
                    val book = bookNoteKey()
                    BookNoteStore.list(ctx, book, BookNote.ANNOTATION).firstOrNull { it.cfi == cfi }?.let { m ->
                        BookNoteStore.update(ctx, book, m.id) { it.copy(note = note) }
                    }
                    refaceBookCard(cfi)
                }
            }
        }
        if (text.isNotBlank()) rows += "★  Star it" to {
            logHighlight(text, cfi = cfi, starred = true)
            showMessage("Starred to your Ledger Log")
        }
        if (text.isNotBlank()) rows += "📝  Onto today's note" to {
            logHighlight(text, cfi = cfi); highlightOntoTodaysNote(text)
        }
        rows += "🖼  Upload a picture" to {
            captureAnnotationVerb("upload", text, bookTitle.ifBlank { null }) { selection, note, attachment ->
                logHighlight(selection, note, cfi, attachment)
            }
        }
        // 🃏 Create pick folded into ❝ above (Michael 2026-10-12: "Highlight should make a gram
        // pick anyway") — one card per highlight; moving it to a board is All Picks' own hold.
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

    // readerSynthesize() and placeSourcedQuestionsToDay() lived here until the 08-12 sweep —
    // the reader's private fork of the day page's Synthesize pipeline, still placing question
    // groups onto the retired Synthesize page (a page with no door of its own anywhere in the
    // app). Retired with the "🔬 3 questions → Synthesize" row in the book directory above;
    // synthesis is an Ask capability now (the hub's Ask group), not a reader shortcut.

    /** Offer 3 writing prompts from the selected passage / current section; the chosen one seeds Write. */
    /**
     * Put typed material on a FRESH page of today's Notes — ONE wide box, the way the feed's
     * engines write. The reader's Writing prompt and Essay outline used to drop boxes onto the
     * Write (Lines) page, a line per 120px box: over whatever was already there, and long lines
     * wrapped inside their box into the next one ("it clobbered and created a bug… synthesize/
     * lines overlapping" — Michael, 09-04, and his ruling: "should be sent to a fresh page on
     * today's notes"). Fresh = the first numeric page after the last that holds anything
     * (strokes, a card, or text); an empty day starts at 0. Returns the page key it went to.
     */
    private fun placeOnFreshNotesPage(day: com.toolsboox.plugin.calendar.da.v2.CalendarDay, title: String, lines: List<String>): String {
        val used = HashSet<Int>()
        day.noteStrokes.forEach { (k, v) -> if (v.isNotEmpty()) k.toIntOrNull()?.let { used.add(it) } }
        day.imageElements.forEach { it.page.toIntOrNull()?.let { i -> used.add(i) } }
        day.textElements.forEach { if (it.text.isNotBlank()) it.pageKey.toIntOrNull()?.let { i -> used.add(i) } }
        val key = ((used.maxOrNull() ?: -1) + 1).toString()
        val body = lines.joinToString("\n\n").trim()
        day.textElements.add(com.toolsboox.da.TextElement(
            x = 90f, y = 140f, width = 1220f, height = (120f + 44f * (lines.size + body.count { it == '\n' })),
            text = "$title\n\n$body", fontSize = 26f, pageKey = key))
        return key
    }

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
                            var landed: String? = null
                            withContext(Dispatchers.IO) {
                                val root = documentsRoot(); val today = LocalDate.now()
                                // Under the day lock — every load→mutate→save of a day file
                                // serializes there (the widget-checkbox tear made the rule
                                // non-negotiable; see TaskDoneQueue).
                                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                                    // A today-file that won't read is never saved over.
                                    val day = calendarDayService.loadForWrite(root, today, null, Locale.getDefault())
                                        ?: return@withDay
                                    landed = placeOnFreshNotesPage(day, "✍ Writing prompt · " + bookTitle.ifBlank { "book" },
                                        listOf(prompts[which]))
                                    calendarDayService.save(root, today, day)
                                }
                            }
                            val page = landed ?: run {
                                showMessage("Couldn't write on today — its page won't read")
                                return@launch
                            }
                            showMessage("Prompt → today's Notes, page ${page.toInt() + 1}")
                            CalendarNavigator.toDayNote(this@ReaderFragment, LocalDate.now(), page)
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
                var landed: String? = null
                withContext(Dispatchers.IO) {
                    val root = documentsRoot(); val today = LocalDate.now()
                    // Under the day lock — same rule as the prompt writer above.
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                        // A today-file that won't read is never saved over.
                        val day = calendarDayService.loadForWrite(root, today, null, Locale.getDefault())
                            ?: return@withDay
                        landed = placeOnFreshNotesPage(day, "🗒 Essay outline · " + bookTitle.ifBlank { "book" }, lines)
                        calendarDayService.save(root, today, day)
                    }
                }
                val page = landed ?: run {
                    showMessage("Couldn't write on today — its page won't read")
                    return@launch
                }
                showMessage("Outline → today's Notes, page ${page.toInt() + 1}")
                CalendarNavigator.toDayNote(this@ReaderFragment, LocalDate.now(), page)
            }
        }
    }

    /** Render a card from a book highlight and place it on a pickings board (chooser). The card now
     *  carries the book title, author, and cover thumbnail. */
    /**
     * ❝'s card: the passage's ONE gram pick on today's All Picks, remembered on the mark
     * ([BookNote.pickId]) so a second ❝ never stacks a twin (Michael 2026-10-12, the article's
     * "Highlight should make a gram pick anyway", applied to the book for parity). The book's card
     * face — the chapter, the author, the cover — as 🃏 Create pick drew it before it folded in here.
     */
    private fun pickOnce(mark: BookNote) {
        val book = bookNoteKey().ifBlank { return }
        val current = BookNoteStore.list(requireContext(), book, BookNote.ANNOTATION).firstOrNull { it.id == mark.id } ?: mark
        if (current.pickId.isNotBlank()) { showMessage(PICKED); return }
        val title = bookTitle.ifBlank { book }
        val author = bookAuthor.ifBlank { null }
        val cover = bookCover
        val footer = listOfNotNull(title, currentChapter.ifBlank { null }).joinToString(" · ")
        val label = listOfNotNull(title, currentChapter.ifBlank { null }, author).joinToString(" · ")
        val src = currentBookFile?.let { "book://${it.absolutePath}" } ?: (currentBookName?.let { "book://$it" } ?: "")
        val ctx = requireContext().applicationContext
        val root = documentsRoot()
        lifecycleScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                val pickId = java.util.UUID.randomUUID().toString()
                val today = java.time.LocalDate.now()
                runCatching {
                    val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                        current.text, source = footer, note = current.note.ifBlank { null }, W = 1080, H = 0,
                        author = author, cover = cover)
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, root, bmp, today,
                        com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS,
                        sourceLink = src, sourceLabel = label, cardText = current.text,
                        elementId = java.util.UUID.fromString(pickId))
                    // CHECK-AND-SET on the mark AS STORED NOW (review 2026-10-12 #11): it used to
                    // write back the copy read before the card render, losing anything said to the
                    // mark meanwhile — and two ❝ at once both placed a card. The first card recorded
                    // wins; a twin comes straight back off. The card's day rides with its id (iOS
                    // reads `pickDay` — Bookshelf.swift removeHighlight / MarkPick).
                    BookNoteStore.update(ctx, book, mark.id) { held ->
                        if (held.pickId.isBlank()) held.copy(pickId = pickId, pickDay = today.toString()) else null
                    }
                    val held = BookNoteStore.list(ctx, book, BookNote.ANNOTATION).firstOrNull { it.id == mark.id }
                    if (held != null && held.pickId != pickId) {
                        com.toolsboox.plugin.calendar.ot.MarkCard.remove(calendarDayService, root, today, pickId)
                    }
                }.onFailure { Timber.w(it, "book highlight: its pick wasn't placed") }
            }
            // The highlight is saved either way; today's page that won't read says so (day-file
            // guard — nothing was written over it), as iOS's "Couldn't make the pick" does.
            val refused = com.toolsboox.plugin.calendar.fi.CalendarDayService.refusal(attempt.exceptionOrNull(), "make the pick")
            if (isAdded) showMessage(when {
                attempt.isSuccess -> PICKED
                refused != null -> "Highlight saved · $refused"
                else -> "Highlight saved"
            })
        }
    }

    /** 📝 Onto today's note — the passage as a quote gram on TODAY'S NOTES PAGE (page "0"), the
     *  canon's seventh row: ❝ files to All Picks; this one knows — the page you are
     *  writing on. Same face (title · chapter footer, author, cover), same provenance link. */
    private fun highlightOntoTodaysNote(text: String) {
        val title = bookTitle.ifBlank { bookNoteKey() }
        val author = bookAuthor.ifBlank { null }
        val cover = bookCover
        val footer = listOfNotNull(title, currentChapter.ifBlank { null }).joinToString(" · ")
        val label = listOfNotNull(title, currentChapter.ifBlank { null }, author).joinToString(" · ")
        val src = currentBookFile?.let { "book://${it.absolutePath}" }
            ?: (currentBookName?.let { "book://$it" } ?: "")
        val page = com.toolsboox.plugin.calendar.ot.GramDestinations.NOTES_PAGE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                        text, source = footer, note = null, W = 1080, H = 0,
                        author = author, cover = cover)
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bmp, LocalDate.now(), page,
                        sourceLink = src, sourceLabel = label, cardText = text)
                }.isSuccess
            }
            if (!isAdded) return@launch
            if (!ok) { showMessage("Couldn't place it on today's note."); return@launch }
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, "Gram on today's notes.",
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction("Go to it") {
                com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(this@ReaderFragment, LocalDate.now(), page)
            }.show()
        }
    }

    /**
     * Tapping an existing highlight offers to remove it (mark + stored CFI) — AT ONCE (review
     * 2026-10-12 R2 #8: it waited on a day-file load before it would open). Its card goes with it;
     * only when the card was moved onto a board does a second question follow, naming the board —
     * iOS's two steps and iOS's words (Michael 2026-10-12: "removing the highlight removes its pick
     * (or asks, if the pick was moved onto a board — say which)").
     */
    private fun confirmDeleteHighlight(cfi: String) {
        AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(R.string.reader_highlight)
            .setMessage(R.string.reader_highlight_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reader_highlight_delete) { d, _ -> d.dismiss(); deleteHighlightAndPick(cfi) }
            .show()
    }

    private fun deleteHighlightAndPick(cfi: String) {
        val ctx = requireContext().applicationContext
        val book = bookNoteKey()
        val root = documentsRoot()
        fun go(cards: List<Pair<String, com.toolsboox.plugin.calendar.ot.MarkCard.Located>>, removeCards: Boolean) {
            val escaped = cfi.replace("\\", "\\\\").replace("'", "\\'")
            binding.readerWeb.evaluateJavascript("window.deleteHighlight && window.deleteHighlight('$escaped')", null)
            forgetHighlight(cfi)
            if (removeCards) dropCards(root, cards)
            showMessage(if (removeCards || cards.isEmpty()) "Highlight removed" else "Highlight removed · its pick stays")
        }
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) { cardsOf(ctx, book, root, cfi) }
            if (!isAdded) return@launch
            val moved = cards.firstOrNull { !it.second.inAllPicks }
            if (moved == null) { go(cards, true); return@launch }
            val board = com.toolsboox.plugin.calendar.ot.MarkCard.boardName(ctx, moved.second.day, moved.second.page)
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                .setTitle("Its pick is on $board now.")
                .setPositiveButton("Delete the highlight and its pick") { d, _ -> go(cards, true); d.dismiss() }
                .setNeutralButton("Delete the highlight, keep the pick") { d, _ -> go(cards, false); d.dismiss() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** The cards of the marks at [cfi] (live or just forgotten), each with where it is now. */
    private fun cardsOf(
        ctx: android.content.Context, book: String, root: java.io.File, cfi: String
    ): List<Pair<String, com.toolsboox.plugin.calendar.ot.MarkCard.Located>> =
        BookNoteStore.list(ctx, book, BookNote.ANNOTATION)
            .filter { it.cfi == cfi && it.pickId.isNotBlank() }
            .mapNotNull { m ->
                com.toolsboox.plugin.calendar.ot.MarkCard.locate(calendarDayService, root,
                    m.cardDay ?: java.time.LocalDate.now(), m.pickId)?.let { m.pickId to it }
            }

    /** Take [cards] off their days (tombstoned, so the other device's copy can't return them). */
    private fun dropCards(root: java.io.File, cards: List<Pair<String, com.toolsboox.plugin.calendar.ot.MarkCard.Located>>) {
        if (cards.isEmpty()) return
        val svc = calendarDayService
        val act = activity
        Thread {
            var refused: String? = null
            for ((id, at) in cards) runCatching { com.toolsboox.plugin.calendar.ot.MarkCard.remove(svc, root, at.day, id) }
                .onFailure {
                    Timber.w(it, "book highlight: its pick didn't come off")
                    // A pick on a page that won't read stays where it is (day-file guard) — said.
                    if (refused == null) refused = com.toolsboox.plugin.calendar.fi.CalendarDayService.refusal(it, com.toolsboox.plugin.calendar.ot.MarkWords.TAKE_THE_PICK_OFF)
                }
            refused?.let { words -> act?.runOnUiThread { if (isAdded) showMessage(words) } }
        }.apply { isDaemon = true }.start()
    }

    /** A book mark deleted from the Annotations list: its card goes too when it is still in All
     *  Picks; moved onto a board, it asks first, naming the board. */
    private fun dropCardsOfDeleted(mark: BookNote) {
        if (mark.pickId.isBlank()) return
        val ctx = requireContext().applicationContext
        val root = documentsRoot()
        lifecycleScope.launch {
            val at = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.ot.MarkCard.locate(calendarDayService, root,
                    mark.cardDay ?: java.time.LocalDate.now(), mark.pickId)
            } ?: return@launch
            if (at.inAllPicks) { dropCards(root, listOf(mark.pickId to at)); return@launch }
            if (!isAdded) return@launch
            AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
                .setTitle("Remove its pick too?")
                .setMessage("Its pick is on " +
                    com.toolsboox.plugin.calendar.ot.MarkCard.boardName(ctx, at.day, at.page) + " now.")
                .setPositiveButton("Remove the pick") { _, _ -> dropCards(root, listOf(mark.pickId to at)) }
                .setNegativeButton("Keep the pick", null)
                .show()
        }
    }

    /**
     * Re-draw a book highlight's ONE card with the words now on its mark — the same card, in place
     * (Michael 2026-10-12: "A note added to that highlight goes onto the SAME pick/card — never a
     * second card; edits never add cards"). A card taken off stays off.
     */
    private fun refaceBookCard(cfi: String) {
        val ctx = requireContext().applicationContext
        val book = bookNoteKey().ifBlank { return }
        val title = bookTitle.ifBlank { book }
        val author = bookAuthor.ifBlank { null }
        val cover = bookCover
        val here = currentChapter
        val root = documentsRoot()
        lifecycleScope.launch(Dispatchers.IO) {
            val m = BookNoteStore.list(ctx, book, BookNote.ANNOTATION)
                .firstOrNull { it.cfi == cfi && it.pickId.isNotBlank() } ?: return@launch
            // The face pickOnce drew: the book · the passage's own chapter (not wherever the reader
            // stands now), the author, the cover — now with the words.
            val footer = listOfNotNull(title, m.chapter.ifBlank { here }.ifBlank { null }).joinToString(" · ")
            runCatching {
                // A handwritten note rides the card too (review 2026-10-12 #12 — only the typed
                // words reached it): with ink, the note card's face (the passage, the words, the
                // handwriting under them); without, the book's own face.
                val ink = m.image?.let { b64 ->
                    runCatching {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                val face = if (ink != null) com.toolsboox.plugin.feeds.ot.FeedNoteGram.noteCard(
                    m.text, m.note.ifBlank { null }, listOf(ink), null, listOfNotNull(footer, author).joinToString(" · "))
                else com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(
                    m.text, source = footer, note = m.note.ifBlank { null }, W = 1080, H = 0, author = author, cover = cover)
                val near = com.toolsboox.plugin.calendar.ot.ReadingEventEdit.candidateDays(m.cardDay ?: java.time.LocalDate.now())
                // Its own day that won't read throws (day-file guard): the card is left as it is, and said.
                com.toolsboox.plugin.calendar.ot.PickingsPlacement.refaceNear(calendarDayService, root, near, m.pickId, face)
            }.onFailure { t ->
                Timber.w(t, "book highlight card re-face failed")
                com.toolsboox.plugin.calendar.fi.CalendarDayService.refusal(t, com.toolsboox.plugin.calendar.ot.MarkWords.CHANGE_THE_PICK)?.let { words ->
                    withContext(Dispatchers.Main) { if (isAdded) showMessage(words) }
                }
            }
        }
    }


    override fun showLoading() {}
    override fun hideLoading() {}

    /** Read the book aloud FROM HERE via the process-wide player — from the highlight when one is
     *  in hand, else from the first visible line of the page, to the section's end (foliate's
     *  getReaderTextFromHere; 09-06: "it starts playing the beginning of the book instead of the
     *  highlight or page"). Keeps playing after you leave the book; drive it from the transport. */
    private fun readAloud() {
        binding.readerWeb.evaluateJavascript(
            "window.getReaderTextFromHere ? window.getReaderTextFromHere() : (window.getReaderText && window.getReaderText())"
        ) { raw ->
            val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                ?: raw.removeSurrounding("\"")
            if (text.isBlank()) { showMessage(R.string.reader_capture_failed); return@evaluateJavascript }
            val player = com.toolsboox.ui.plugin.LedgerPlayer
            player.start(requireContext(), bookTitle.ifBlank { "Reading" }, bookAuthor.ifBlank { null }, null, text)
            player.readingBook = currentBookName   // the transport's title finds its way back here
            // TTS through the same transport as audio (08-12): the inline card renders ¶ chunk
            // progress for the speech backend, so read-aloud wears the identical controls.
            seatTransport()
            // 🔦 FOLLOW-ALONG (Michael, 08-30, optional via the transport's toggle): the spoken
            // SENTENCE highlights on the page as the voice reaches it — a temporary outline,
            // never a user mark, replaced as it advances. One quiet DOM swap per sentence.
            binding.readerWeb.evaluateJavascript(FOLLOW_JS, null)
            player.onFollowSentence = { sentence ->
                if (isAdded && requireContext().getSharedPreferences("MAIN", 0)
                        .getBoolean("tts_follow", true)) {
                    val q = org.json.JSONObject.quote(sentence)
                    binding.readerWeb.post {
                        runCatching { binding.readerWeb.evaluateJavascript("window.__ledgerFollow && window.__ledgerFollow($q)", null) }
                    }
                }
            }
        }
    }

    // ── READ-ALONG (the Audiobookshelf recording, paired with this book) ─────────────────────

    /** The pairing file's key: the fleet-wide reading-state id when the book has one, else its name. */
    private fun readAlongKey(): String? = currentReadingId ?: bookNoteKey().takeIf { it.isNotBlank() }

    /** How [ReadAlong] reaches this reader: which book is up, which section, and the page turn. */
    private val readAlongSink = object : ReadAlong.Reader {
        override val bookKey: String? get() = if (isAdded && bookReady) readAlongKey() else null
        override val sectionIndex: Int get() = currentSectionIndex
        // The narration's own turns into another section are the app's turns: full screen's peek
        // goes before the page moves (ReaderFullScreen; the embed's 'turned' counts a hand's only).
        override fun goToHref(href: String) {
            if (!isAdded) return
            readerRail?.readerPageTurn()
            this@ReaderFragment.goToHref(href)
        }
        override fun goToSectionFraction(index: Int, fraction: Double) {
            if (!isAdded) return
            readerRail?.readerPageTurn()
            binding.readerWeb.post {
                runCatching { binding.readerWeb.evaluateJavascript("window.goToSectionFraction && window.goToSectionFraction($index, $fraction)", null) }
            }
        }
    }

    private fun tocChapters(): List<ReadAlongPairing.TextChapter> =
        tocItems.filter { it.href.isNotBlank() }.distinctBy { it.href }
            .map { ReadAlongPairing.TextChapter(it.href, it.label, it.index) }

    /**
     * ▶ Listen along: the narration from THIS page — the audio chapter this section is paired with,
     * as far in as the page is (Readest's approximate timing). Already narrating this book: a
     * pause/resume, like the ▶ on any transport.
     */
    private fun listenAlong(at: ReadAlong.StartAt = ReadAlong.StartAt.HERE) {
        val key = readAlongKey() ?: return
        val pairing = readAlongPairing ?: run { showReadAlongPicker(); return }
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        if (at == ReadAlong.StartAt.HERE && ReadAlong.isActive(key)) { player.toggle(); seatTransport(); return }
        if (ReadAlong.isActive(key)) ReadAlong.stop()
        val ctx = requireContext().applicationContext
        val section = currentSectionIndex; val fraction = currentSectionFraction
        showMessage("Starting · ${pairing.audioTitle}")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ReadAlong.start(ctx, key, pairing, section, fraction, at) } }
            if (!isAdded) return@launch
            // The landing is SAID — a wrong place names its mapping instead of just feeling wrong.
            r.onSuccess { seatTransport(); showMessage("🎧 ${ReadAlong.lastStartReport}") }
                .onFailure { showMessage(it.message ?: "Couldn't start the audiobook.") }
        }
    }

    /**
     * 🎧 Listen along… — pick the recording. The server's audiobooks, best match for this book's
     * title and author first; a 🔎 row to type a different search over the same list.
     */
    private fun showReadAlongPicker(query: String? = null, cached: List<Audiobookshelf.Item>? = null) {
        val ctx = requireContext()
        if (Audiobookshelf.config(ctx) == null) {
            showMessage("Add your Audiobookshelf server first — Bookshelf › 🎧 From Audiobookshelf…")
            return
        }
        // No early refusal on an empty table of contents — it is usually still LOADING when the
        // picker opens; pairReadAlong waits for it (Michael, 09-11: "doesn't quite make sense").
        val q = query ?: bookTitle
        if (cached == null) showMessage("Asking Audiobookshelf for “$q”…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { cached ?: ReadAlong.candidates(ctx, bookTitle, bookAuthor) }
            }
            if (!isAdded) return@launch
            val all = result.getOrElse { e -> showMessage(e.message ?: "The server didn't answer."); return@launch }
            // The re-rank for a typed query runs OFF the main thread (a library is thousands).
            val ranked = if (query == null) all else withContext(Dispatchers.IO) { ReadAlong.rank(all, query, "") }
            val shown = ranked.take(20)
            val rows = shown.map { it ->
                val line = buildString {
                    append("🎧  ").append(it.title)
                    if (it.author.isNotBlank()) append(" · ").append(it.author)
                    if (it.duration > 0) append(" · ").append(Audiobookshelf.clock(it.duration))
                    if (it.chapters.isNotEmpty()) append(" · ").append(it.chapters.size).append(" ch")
                }
                line to { pairReadAlong(it) }
            }
            val search = "🔎  Search the library…" to {
                val field = android.widget.EditText(com.toolsboox.ot.ModalScale.wrap(ctx)).apply { setText(q); setSelection(text.length) }
                AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("Which audiobook?").setView(field)
                    .setPositiveButton("Search") { d, _ -> d.dismiss(); showReadAlongPicker(field.text.toString(), all) }
                    .setNegativeButton(R.string.cancel, null).show()
                Unit
            }
            val empty = if (shown.isEmpty()) listOf("Nothing on the server matches “$q”." to {}) else emptyList()
            showIconMenu("Listen along · which recording is “$bookTitle”?", empty + rows + listOf(search))
        }
    }

    /** The pick made: fetch the whole item, map its chapters onto the contents, save, say how it went. */
    private fun pairReadAlong(minified: Audiobookshelf.Item) {
        val key = readAlongKey() ?: return
        val ctx = requireContext()
        showMessage("Pairing · ${minified.title}…")
        lifecycleScope.launch {
            // The contents as they ARE at pair time — waited for, briefly, if the book is still opening.
            var toc = tocChapters(); var tries = 0
            while (toc.isEmpty() && tries < 40) { kotlinx.coroutines.delay(250); if (!isAdded) return@launch; toc = tocChapters(); tries++ }
            if (toc.isEmpty()) { showMessage("The book's contents never loaded — open it again and retry."); return@launch }
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val item = Audiobookshelf.item(ctx, minified.id)
                    if (!item.hasAudio && item.tracks.isEmpty()) error("That item has no audio on the server.")
                    ReadAlong.pair(ctx, bookTitle, toc, item).also { ReadAlong.save(ctx, key, it) }
                }
            }
            if (!isAdded) return@launch
            val pairing = r.getOrElse { e -> showMessage(e.message ?: "Couldn't pair it."); return@launch }
            readAlongPairing = pairing
            rebuildActionRail("reader")
            showReadAlongSummary(pairing)
        }
    }

    /** "42 of 44 chapters matched" — shown once, with the doors to listen now or fix a chapter. */
    private fun showReadAlongSummary(pairing: ReadAlongPairing.Pairing) {
        showIconMenu("${pairing.audioTitle}\n${pairing.summary}", listOf(
            "▶  Listen along from this page" to { listenAlong() },
            "🔁  Fix a chapter…" to { showReadAlongRepair() },
            "✓  Done" to {},
        ))
    }

    /**
     * 🔁 Re-pair… — every audio chapter with the text it is paired to (or "absorbed"); tap one to
     * point it at a different contents entry, or at no text. One edit, saved, synced.
     */
    private fun showReadAlongRepair() {
        val key = readAlongKey() ?: return
        val pairing = readAlongPairing ?: return
        val ctx = requireContext()
        showMessage("Fetching chapters…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Audiobookshelf.item(ctx, pairing.absItemId) } }
            if (!isAdded) return@launch
            val item = r.getOrElse { e -> showMessage(e.message ?: "Couldn't fetch the chapters."); return@launch }
            val audio = ReadAlong.audioChapters(item)
            val toc = tocChapters()
            val labelFor = toc.associate { it.href to it.label }
            val byAudio = pairing.chapterMap.associateBy { it.audioChapterIndex }
            val rows = audio.map { ch ->
                val m = byAudio[ch.index]
                val to = when {
                    m != null -> "→ " + (labelFor[m.tocHref] ?: m.tocHref) + if (m.how == ReadAlongPairing.HOW_MANUAL) " ✎" else ""
                    else -> "→ (absorbed by the chapter before)"
                }
                "${ch.index + 1}. ${Audiobookshelf.clock(ch.start)} · ${ch.title.ifBlank { "Chapter ${ch.index + 1}" }}\n      $to" to {
                    val choices = toc.map { t -> "📖  ${t.label}" to { saveRepair(key, pairing, ch.index, t) } } +
                        ("∅  No text — absorb into the chapter before" to { saveRepair(key, pairing, ch.index, null) })
                    showIconMenu("Which text is “${ch.title.ifBlank { "chapter ${ch.index + 1}" }}”?", choices)
                }
            }
            showIconMenu("Re-pair · ${pairing.summary}", rows + listOf(
                "🎧  Choose a different recording…" to { showReadAlongPicker() },
                "✂  Unpair" to { unpairReadAlong() },
            ))
        }
    }

    private fun saveRepair(key: String, pairing: ReadAlongPairing.Pairing, audioChapterIndex: Int, toc: ReadAlongPairing.TextChapter?) {
        val fixed = ReadAlongPairing.repair(pairing, audioChapterIndex, toc, System.currentTimeMillis())
        ReadAlong.save(requireContext(), key, fixed)
        readAlongPairing = fixed
        if (ReadAlong.isActive(key)) ReadAlong.stop()   // the live session's spans are stale; ▶ re-seats it
        showMessage("Re-paired · ${fixed.summary}")
    }

    private fun unpairReadAlong() {
        val key = readAlongKey() ?: return
        ReadAlong.forget(requireContext(), key)
        readAlongPairing = null
        rebuildActionRail("reader")
        showMessage("Unpaired — ▶ reads aloud again")
    }

    /** Seat (or clear) the inline Now Playing transport over the page — see NowPlayingCard.seat. */
    private fun seatTransport() {
        // The summon grammar (-59): on the reading surface the transport is an edge CHIP by
        // default — tap expands the full card in place, ⌄ tucks it back; never a card squatting
        // over the page unbidden, never zero affordance while something plays.
        runCatching { com.toolsboox.plugin.feeds.ui.NowPlayingCard.summon(requireActivity()) }
    }

    private val transportListener: () -> Unit = {
        if (isAdded) {
            seatTransport()
            // The Listen seat's box follows the voice; the redress rule flips that one cell, and
            // the same seats again move no pixel. Posted: the player calls from its own thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (isAdded) rebuildActionRail("reader") }
        }
    }

    /** The process-wide voice is reading THIS book aloud (the synth, not a narration). */
    private fun readingThisAloud(): Boolean {
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        return player.isActive && player.capture == null && player.readingBook != null &&
            player.readingBook == currentBookName
    }

    override fun onDestroyView() {
        runCatching { com.toolsboox.plugin.reader.ot.BalloonIndex.detach(balloonKey()) }
        runCatching { cancelReaderCentre() }
        // The player is process-wide; a relay that still captured this fragment kept the whole
        // view tree (WebView included) alive after leaving the book, and fired into a destroyed
        // WebView while read-aloud carried on (audit 09-10). The refit belt's posted runnables go
        // with the view for the same reason.
        com.toolsboox.ui.plugin.LedgerPlayer.onFollowSentence = null
        if (::binding.isInitialized) {
            binding.readerWeb.handler?.removeCallbacksAndMessages(null)
            binding.readerWeb.destroy()
        }
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
        // The type half of foliate's isCBZ test — see the note where the book URL is built.
        "cbz" -> "application/vnd.comicbook+zip"
        "cbr" -> "application/vnd.comicbook-rar"
        "pdf" -> "application/pdf"
        "mobi", "azw3" -> "application/x-mobipocket-ebook"
        "cbz" -> "application/vnd.comicbook+zip"
        "fb2" -> "application/x-fictionbook+xml"
        else -> "application/octet-stream"
    }

    companion object {
        /** A book highlight kept with its pick — iOS's words (Bookshelf.swift), Michael's noun
         *  (review 2026-10-12 R2 #5: "card" on the Boox, "pick" on the iPad). */
        private const val PICKED = "❝ Highlighted · its pick in All Picks"
        /** Every per-book comic setting [comicKey] carries from the file-name key. */
        private val COMIC_PREF_NAMES = listOf("panels", "balloons", "rtl", "flow", "spread", "zoom", "panel_at")
        /** Sentence follow-highlight: find the sentence's leading words in the reader's document
         *  (the foliate iframe when present), wrap the first hit in a temporary outlined span,
         *  scroll it centred, and unwrap the previous one — one swap per sentence, e-ink quiet. */
        /** The shared follow-along (TtsFollowJs): cross-node matching, e-ink-quiet marks. */
        const val FOLLOW_JS = com.toolsboox.plugin.feeds.ot.TtsFollowJs.SCRIPT

        private const val HOST = "ledger.reader"
        private const val ORIGIN = "https://ledger.reader"
        private const val PREFS = "ledger_reader_prefs"
        private const val HL_PREFS = "ledger_reader_highlights"
        private const val KEY_BOOK = "current_book_path"
        /** A streamed book's tree URI + name — the no-copy open (see [loadBookUri]). */
        private const val KEY_BOOK_URI = "current_book_uri"
        private const val KEY_BOOK_NAME = "current_book_name"
        /** The open book's `reading-state.json` key, saved beside [KEY_BOOK] (same literal is
         *  written by BookshelfFragment.open and LedgerDirectory.openBook, which set the path
         *  from outside and navigate here). */
        private const val KEY_READING_ID = "current_reading_id"
        // Size, theme, the two-page spread and line spacing moved into Aa Type's book profile
        // (com.toolsboox.ot.ReadingType, Michael 2026-10-11). Their old keys — reader_font_pct,
        // reader_theme, reader_spread, reader_line_height_tenths — are read once there, by the
        // migration, and never written again.

        // The Theme row's four-theme cycle became Aa Type's theme strip (ReadingType.THEMES: the
        // same four plus high contrast), one tap each instead of a walk (Michael 2026-10-11). A
        // run of stepper taps waits AaType.LIVE_DEBOUNCE_MS before its one re-lay (e-ink).
        private val SHIM_JS = """
            window.webkit = window.webkit || {};
            window.webkit.messageHandlers = window.webkit.messageHandlers || {};
            window.webkit.messageHandlers.reader = {
              postMessage: function(m) { try { AndroidReaderBridge.postMessage(JSON.stringify(m)); } catch (e) {} }
            };
        """.trimIndent()
    }
}
