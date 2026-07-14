package com.toolsboox.plugin.feeds.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import android.os.Build
import android.os.Environment
import com.toolsboox.databinding.FragmentFeedsBinding
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
 * The in-app Feed Ledger — a native Miniflux list. Tapping an entry opens it in the
 * article reader; the star glyph saves it into the day JSON (shared corpus + sync).
 * Credentials (URL + token) live in EncryptedSharedPreferences.
 */
@AndroidEntryPoint
class FeedsFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var miniflux: MinifluxClient

    @Inject
    lateinit var calendarDayService: CalendarDayService

    override val view = R.layout.fragment_feeds

    private lateinit var binding: FragmentFeedsBinding
    private lateinit var adapter: FeedEntryAdapter
    private var loading = false
    private var allEntries: List<FeedEntry> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedsBinding.bind(view)

        adapter = FeedEntryAdapter(emptyList(), onOpen = ::openEntry, onStar = ::toggleStar)
        binding.feedsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.feedsRecycler.adapter = adapter
        binding.feedsRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        val prefs = prefs()
        binding.urlEdit.setText(prefs.getString(KEY_URL, ""))
        binding.tokenEdit.setText(prefs.getString(KEY_TOKEN, ""))

        binding.settingsButton.setOnClickListener {
            binding.settingsPanel.visibility =
                if (binding.settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        binding.saveSettingsButton.setOnClickListener {
            prefs().edit()
                .putString(KEY_URL, binding.urlEdit.text.toString().trim())
                .putString(KEY_TOKEN, binding.tokenEdit.text.toString().trim())
                .apply()
            binding.settingsPanel.visibility = View.GONE
            showMessage(R.string.feeds_saved)
            refresh()
        }
        binding.refreshButton.setOnClickListener { refresh() }
        binding.gotoButton.setOnClickListener { showFeedDirectory() }
        binding.viewToggleButton.setOnClickListener {
            mode = if (mode == "stars") "feed" else "stars"
            refresh()
        }

        // Honour the view/kind chosen from the hub (feed / stars / later, + read/watch/listen).
        val (m, k) = FeedSelection.consume()
        mode = m; kindFilter = k
        refresh()
    }

    /** Current view: "feed" (unread RSS) · "stars" · "later" (read-later intake). */
    private var mode: String = "feed"
    /** Optional read/watch/listen lens. */
    private var kindFilter: String? = null

    private fun applyKind(entries: List<FeedEntry>): List<FeedEntry> {
        val k = kindFilter ?: return entries
        return entries.filter { it.kind == k }
    }

    private fun refresh() {
        if (loading) return
        binding.viewToggleButton.setText(if (mode == "stars") R.string.feeds_view_unread else R.string.feeds_view_starred)
        if (mode == "later") { loadLaterList(); return }

        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) {
            binding.settingsPanel.visibility = View.VISIBLE
            showEmpty(getString(R.string.feeds_need_creds))
            return
        }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (mode == "stars") miniflux.fetchStarred(url, token) else miniflux.fetchUnread(url, token)
            }
            loading = false
            binding.progress.visibility = View.INVISIBLE
            when (result) {
                is MinifluxClient.Result.Ok -> {
                    allEntries = result.value
                    val filter = FeedSelection.filterFeedTitle
                    FeedSelection.filterFeedTitle = null
                    var shown = applyKind(result.value)
                    if (filter != null) shown = shown.filter { it.feedTitle == filter }
                    adapter.submit(shown)
                    if (shown.isEmpty()) showEmpty(getString(R.string.feeds_empty))
                    else binding.emptyText.visibility = View.GONE
                }
                is MinifluxClient.Result.Err -> showEmpty("⚠️ " + result.message)
            }
        }
    }

    /** Later List: the read-later links intaked across recent days (MichaelFilter intake
     *  sidecar), as feed rows — read/watch/listen by the intake kind. */
    private fun loadLaterList() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { gatherLaterList() }
            binding.progress.visibility = View.INVISIBLE
            allEntries = entries
            val shown = applyKind(entries)
            adapter.submit(shown)
            if (shown.isEmpty()) showEmpty(getString(R.string.feeds_later_empty))
            else binding.emptyText.visibility = View.GONE
        }
    }

    private fun gatherLaterList(): List<FeedEntry> {
        val ctx = requireContext().applicationContext
        val out = mutableListOf<FeedEntry>()
        val kinds = listOf("read", "watch", "listen", "educate")
        var idSeed = 0L
        for (d in 0L..120L) {
            val date = java.time.LocalDate.now().minusDays(d)
            val data = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.load(ctx, date)
            for (kind in kinds) {
                data.typedFor(kind).lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(line).firstOrNull() ?: line
                    val title = line.replace(url, "").trim().ifBlank { url }
                    // Reuse the RSS kind field via category so applyKind() sees read/watch/listen.
                    out += FeedEntry(
                        id = idSeed++, title = title, feedTitle = "Later · $kind",
                        url = url, author = null, content = "", publishedAt = date.toString(),
                        starred = false, category = if (kind == "educate") "read" else kind
                    )
                }
            }
        }
        return out
    }

    private fun showEmpty(text: String) {
        binding.emptyText.text = text
        binding.emptyText.visibility = View.VISIBLE
    }

    private fun openEntry(entry: FeedEntry) {
        FeedSelection.entry = entry
        findNavController().navigate(R.id.action_to_feed_article)
    }

    /** Set the current view/kind and reload (used by the dropdown rows). */
    private fun switchTo(newMode: String, kind: String?) {
        mode = newMode; kindFilter = kind; refresh()
    }

    /** Filter the shown list to one folder (Miniflux category) within the current view. */
    private fun showFolder(name: String) {
        adapter.submit(applyKind(allEntries).filter { (it.category ?: it.feedTitle) == name })
    }

    /**
     * The Feed Ledger dropdown: two homes — Feed (RSS) and Later List — each sliced by
     * Read / Watch / Listen. Feed also lists its folders (Miniflux categories); Stars is
     * the starred-articles view.
     */
    private fun showFeedDirectory() {
        val nav = androidx.navigation.fragment.NavHostFragment.findNavController(this)
        val folders = allEntries.mapNotNull { it.category }.distinct().sortedBy { it.lowercase() }
        val feedRows = listOf<Pair<String, () -> Unit>>(
            "📰  All" to { switchTo("feed", null) },
            "📖  Read" to { switchTo("feed", "read") },
            "📺  Watch" to { switchTo("feed", "watch") },
            "🎧  Listen" to { switchTo("feed", "listen") }
        ) + folders.map { f -> ("🗂  $f" to { showFolder(f) }) }
        val laterRows = listOf<Pair<String, () -> Unit>>(
            "🔖  All" to { switchTo("later", null) },
            "📖  Read" to { switchTo("later", "read") },
            "📺  Watch" to { switchTo("later", "watch") },
            "🎧  Listen" to { switchTo("later", "listen") }
        )
        showDirectory(
            listOf(
                "Feed (RSS)" to feedRows,
                "Later List" to laterRows,
                "" to listOf(
                    "⭐  Stars" to { switchTo("stars", null) },
                    "📅  Day" to { nav.navigate(R.id.action_to_calendar_day) },
                    "📖  Bookshelf" to { nav.navigate(R.id.action_to_reader) },
                    "💬  Ask my Ledger" to { nav.navigate(R.id.action_to_ledger_chat) }
                )
            )
        )
    }

    private fun toggleStar(entry: FeedEntry) {
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val wasStarred = entry.starred
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                miniflux.toggleStar(url, token, entry.id)
                // Newly starred → log to today's Ledger so it joins the corpus + sync.
                if (!wasStarred) logStar(entry)
            }
            showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
            refresh()
        }
    }

    /** Append a starred article to today's CalendarDay as a ReadingEvent(article). */
    private fun logStar(entry: FeedEntry) {
        try {
            val root = documentsRoot()
            val today = LocalDate.now()
            val day = calendarDayService.load(root, today, null, Locale.getDefault())
            day.readingEvents.add(
                ReadingEvent(
                    id = "art-${entry.id}",
                    kind = ReadingEvent.Kind.ARTICLE,
                    date = Date(),
                    title = entry.title,
                    source = entry.feedTitle.ifBlank { null },
                    url = entry.url.ifBlank { null },
                    note = entry.blurb.ifBlank { null }
                )
            )
            calendarDayService.save(root, today, day)
        } catch (e: Exception) {
            Timber.w(e, "failed to log starred article")
        }
    }

    private fun documentsRoot(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    private fun prefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            requireContext(), PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun showLoading() {}
    override fun hideLoading() {}

    companion object {
        const val PREFS = "ledger_feeds_prefs"
        const val KEY_URL = "miniflux_url"
        const val KEY_TOKEN = "miniflux_token"
    }
}

/** Hand the tapped entry to the article fragment without stuffing it through nav args. */
object FeedSelection {
    var entry: FeedEntry? = null

    /** A feed title the Feed Ledger should filter to on next open (set from the day-page hub). */
    var filterFeedTitle: String? = null

    /** Which view to open: "feed" (RSS unread), "stars", or "later" (read-later intake). */
    var mode: String? = null
    /** Optional read/watch/listen lens to filter to. */
    var kind: String? = null

    /** Consume the pending mode/kind (one-shot) after the fragment applies it. */
    fun consume(): Pair<String, String?> {
        val m = mode ?: "feed"; val k = kind
        mode = null; kind = null
        return m to k
    }
}
