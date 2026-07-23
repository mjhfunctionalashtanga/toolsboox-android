package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.FeedEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A local (no-server) feed subscription. */
data class LocalSub(val id: Long, val url: String, val title: String)

/**
 * Local Feeds (Android parity of the iOS LocalFeedStore) — subscribe to a feed URL with NO
 * Miniflux server. Subscriptions + star state live in SharedPreferences; each fetch parses the
 * feed (RssParser) and synthesises FeedEntry rows (NEGATIVE id marks "local") so they reuse the
 * whole feed UI. Blocking fetches — call off the main thread.
 */
object LocalFeedStore {
    private const val PREFS = "ledger_local_feeds"
    private val http = OkHttpClient()

    // Precise range, matching idFor()/entries() (= -(hash % 1e9) - 1000): the feed UI's other
    // synthetic rows (Later, Pickings) live in bands BELOW this and must not classify as local.
    fun isLocal(id: Long): Boolean = id in -1_000_000_999L..-1000L
    private fun idFor(url: String): Long = -(abs(url.hashCode().toLong()) % 1_000_000_000L) - 1000L
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun subscriptions(ctx: Context): List<LocalSub> {
        val arr = JSONArray(prefs(ctx).getString("subs", "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { LocalSub(it.getLong("id"), it.getString("url"), it.getString("title")) }
    }

    private fun saveSubs(ctx: Context, subs: List<LocalSub>) {
        val arr = JSONArray()
        subs.forEach { arr.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) }
        prefs(ctx).edit().putString("subs", arr.toString()).apply()
    }

    /** Fetch + parse (validating), store with title. null = not a feed. */
    fun add(ctx: Context, url: String): LocalSub? {
        val clean = url.trim()
        val feed = fetch(clean) ?: return null
        val id = idFor(clean)
        val subs = subscriptions(ctx).toMutableList()
        subs.firstOrNull { it.id == id }?.let { return it }
        val sub = LocalSub(id, clean, feed.title.ifBlank { clean })
        subs.add(sub); saveSubs(ctx, subs)
        return sub
    }

    fun remove(ctx: Context, id: Long) = saveSubs(ctx, subscriptions(ctx).filter { it.id != id })

    fun importOpml(ctx: Context, urls: List<String>): Int {
        var n = 0
        urls.forEach { if (add(ctx, it) != null) n++ }
        return n
    }

    fun exportOpml(ctx: Context): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val outlines = subscriptions(ctx).joinToString("\n") {
            "    <outline type=\"rss\" text=\"${esc(it.title)}\" title=\"${esc(it.title)}\" xmlUrl=\"${esc(it.url)}\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\"><head><title>Ledger Feeds</title></head><body>\n$outlines\n</body></opml>"
    }

    // Star state (on-device), keyed by item URL.
    private fun starSet(ctx: Context) = prefs(ctx).getStringSet("star", emptySet())!!.toMutableSet()
    fun setStar(ctx: Context, url: String, starred: Boolean) {
        val s = starSet(ctx); if (starred) s.add(url) else s.remove(url)
        prefs(ctx).edit().putStringSet("star", s).apply()
    }

    private fun fetch(url: String): RssFeed? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.let { RssParser.parse(it) }
        }
    }.getOrNull()

    /** Entries for one subscription, or all local subs when [sub] is null. */
    fun entries(ctx: Context, sub: LocalSub?): List<FeedEntry> {
        val subs = if (sub != null) listOf(sub) else subscriptions(ctx)
        val star = prefs(ctx).getStringSet("star", emptySet()) ?: emptySet()
        val out = mutableListOf<FeedEntry>()
        for (s in subs) {
            val feed = fetch(s.url) ?: continue
            for (it in feed.items) {
                val link = it.link.ifBlank { it.guid }
                if (link.isBlank()) continue
                out.add(FeedEntry(
                    id = -(abs((s.url + link).hashCode().toLong()) % 1_000_000_000L) - 1000L,
                    title = it.title.ifBlank { link }, feedTitle = s.title, url = link,
                    author = it.author.ifBlank { null }, content = it.content,
                    publishedAt = it.date, starred = star.contains(link), category = "local"
                ))
            }
        }
        return out
    }
}
