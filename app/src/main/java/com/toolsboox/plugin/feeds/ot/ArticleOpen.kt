package com.toolsboox.plugin.feeds.ot

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * ONE WAY TO OPEN AN ARTICLE A PAGE POINTS AT — inside Ledgable, never the browser.
 *
 * Michael, 2026-10-12: "the response should come back with links to those articles within
 * ledgable itself so you can follow up on it from within". A ❖ Synthesize page's source card, its
 * reply's ↗ rows and its Sources card all open here (design §3); so does any http card's
 * "↩ Go to source". The ladder:
 *  1. ON DEVICE — the feed cache by the server id when it is the same address, then by the
 *     address ([same]; openLogRow's order);
 *  2. BY ID — a server id (> 0, Miniflux) asked of the server, used only if it is the same article;
 *  3. BY ADDRESS — a stand-in entry (id 0: never sent to Miniflux, so nothing unrelated is marked
 *     read; blank content: the reader loads the live page readable, in its own pane).
 * Only the reader's Original control ever leaves for the web; Back comes home to the page.
 * A `mail://` link opens the letter in The Mail, as every mail gram does.
 */
object ArticleOpen {

    /** How long step 2 may hold the tap before the reader opens by address instead. */
    private const val FETCH_MS = 4_000L

    /** The same article — by key, so two spellings of one address agree (iOS `ArticleKey.same`). */
    fun same(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() && (ArticleKey.of(a) == ArticleKey.of(b))

    /**
     * Open [link] in the reader (or The Mail). [fragment] is the screen that raised the tap — but
     * the rows dialog and the hold menu can outlive it (the day page rebuilds often: three times in
     * 75 s in the -322 logcat), leaving it detached (no context: the tap did nothing) or viewless
     * (`viewLifecycleOwner` threw). So the work runs on the ACTIVITY's scope, and the navigator is
     * found when it is needed — the screen in front then; with none, he is told (review 2026-10-12).
     */
    fun open(fragment: ScreenFragment, link: String, entryId: Long, title: String, feed: String = "") {
        val url = link.trim()
        if (url.isEmpty()) return
        val act = (fragment.activity ?: com.toolsboox.plugin.calendar.ui.SynthListRun.activity()) as? FragmentActivity
        if (act == null || act.isFinishing || act.isDestroyed) {
            Timber.w("article-open %s: no activity to open it in", url)
            return
        }
        val ctx = act.applicationContext
        fun front(): ScreenFragment? {
            val f = com.toolsboox.plugin.calendar.ui.SynthListRun.screenInFront(fragment, act)
            if (f == null) say(ctx)
            return f
        }
        if (url.startsWith("mail://") || url.startsWith("acct:")) {
            val f = front() ?: return
            runCatching {
                com.toolsboox.plugin.feeds.ui.openMailRoom(f.findNavController(), mode = "both",
                    letterId = url.removePrefix("mail://"))
            }.onFailure { Timber.w(it, "article-open: mail navigation refused"); say(ctx) }
            return
        }
        act.lifecycleScope.launch {
            val entry = resolve(ctx, url, entryId) ?: standIn(url, entryId, title, feed)
            Timber.i("article-open %s → id=%d (%s)", url, entry.id, if (entry.id == 0L) "by address" else "entry")
            val f = front() ?: return@launch
            com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry = entry
            runCatching { f.findNavController().navigate(com.toolsboox.R.id.action_to_feeds) }
                .onFailure { Timber.w(it, "article-open: navigation refused"); say(ctx) }
        }
    }

    /** What a tap that can't reach the reader says — never a silent nothing. */
    private const val CANT_OPEN = "Couldn't open that article — try again."

    private fun say(ctx: Context) =
        android.widget.Toast.makeText(ctx, CANT_OPEN, android.widget.Toast.LENGTH_LONG).show()

    /** Steps 1 and 2 — the real entry, or null for the stand-in. The server ask is bounded by
     *  [FETCH_MS] on the AWAIT (a blocking call inside withContext can't be timed out). */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun resolve(ctx: Context, url: String, entryId: Long): FeedEntry? {
        val known = withContext(Dispatchers.IO) {
            runCatching { com.toolsboox.plugin.feeds.nw.FeedCache.loadFreshestEntries(ctx) }.getOrDefault(emptyList())
        }
        // By id only when it is the SAME article: an iPhone's `#id` can be Feedbin's, GReader's,
        // Fever's or another Miniflux's, and the cached entry sharing that number here is some other
        // piece — which the reader would then mark read (review 2026-10-12; iOS ArticleOpen's rule).
        if (entryId > 0) known.firstOrNull { it.id == entryId && same(it.url, url) }?.let { return it }
        known.firstOrNull { it.url.isNotBlank() && same(it.url, url) }?.let { return it }
        if (entryId <= 0) return null
        val (base, token) = withContext(Dispatchers.IO) { creds(ctx) } ?: return null
        val call = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
            com.toolsboox.plugin.feeds.nw.MinifluxClient().getEntry(base, token, entryId)
        }
        val got = runCatching { withTimeoutOrNull(FETCH_MS) { call.await() } }.getOrNull()
        if (got == null) call.cancel()
        val e = (got as? com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok)?.value ?: return null
        return e.takeIf { same(it.url, url) }
    }

    /** Step 3: the reader by address — id 0 and blank content (the in-pane reader's live-page sentinel). */
    fun standIn(url: String, entryId: Long, title: String, feed: String): FeedEntry = FeedEntry(
        id = 0L, title = title.ifBlank { url }, feedTitle = feed, url = url, author = null, content = "",
        publishedAt = java.time.Instant.now().toString(), starred = false, read = true,
    )

    private fun creds(ctx: Context): Pair<String, String>? = runCatching {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        val p = androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx, com.toolsboox.plugin.feeds.ui.FeedsFragment.PREFS, masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val u = p.getString(com.toolsboox.plugin.feeds.ui.FeedsFragment.KEY_URL, "").orEmpty()
        val t = p.getString(com.toolsboox.plugin.feeds.ui.FeedsFragment.KEY_TOKEN, "").orEmpty()
        if (u.isBlank() || t.isBlank()) null else u to t
    }.getOrNull()
}
