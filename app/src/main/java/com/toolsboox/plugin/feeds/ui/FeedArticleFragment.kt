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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedArticleBinding.bind(view)

        val e = FeedSelection.entry
        entry = e
        if (e == null) {
            showMessage(R.string.feeds_article_missing)
            return
        }

        binding.articleWeb.settings.javaScriptEnabled = false
        binding.articleWeb.loadDataWithBaseURL(null, buildHtml(e), "text/html", "UTF-8", null)

        binding.starButton.text = getString(if (e.starred) R.string.feeds_article_unstar else R.string.feeds_article_star)
        binding.starButton.setOnClickListener { star(e) }
        binding.noteButton.setOnClickListener { note(e) }
        binding.browserButton.setOnClickListener {
            if (e.url.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)))
        }

        // Opening an entry marks it read on the server (shrinks the unread list).
        val p = prefs()
        val url = p.getString(FeedsFragment.KEY_URL, "").orEmpty()
        val token = p.getString(FeedsFragment.KEY_TOKEN, "").orEmpty()
        if (url.isNotBlank() && token.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) { miniflux.markRead(url, token, e.id) }
        }
    }

    private fun buildHtml(e: FeedEntry): String {
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
            ${e.content}
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
            binding.starButton.text =
                getString(if (!wasStarred) R.string.feeds_article_unstar else R.string.feeds_article_star)
            showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
        }
    }

    private fun note(e: FeedEntry) {
        val input = EditText(requireContext()).apply { hint = getString(R.string.feeds_article_note_hint) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.feeds_article_note)
            .setView(input)
            .setPositiveButton(R.string.feeds_save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { logEvent(e, note = text) }
                    showMessage(R.string.feeds_note_saved)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Append an article ReadingEvent (star marker, or a note) to today's CalendarDay. */
    private fun logEvent(e: FeedEntry, note: String?) {
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
