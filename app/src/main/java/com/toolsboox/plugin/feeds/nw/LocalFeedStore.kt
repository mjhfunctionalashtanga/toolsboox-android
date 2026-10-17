package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.FeedEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A local (no-server) feed subscription. [updated]/[deletedAt] are the merge clock — the
 *  subscription list is a SYNCED SIDECAR now (09-03: "what about syncing between devices when
 *  it doesn't get added to miniflux — not everyone will have miniflux"): `local-feeds.json` at
 *  the hub root, id-union, newest-updated wins, tombstones survive, both platforms one file. */
data class LocalSub(
    val id: Long, val url: String, val title: String,
    val updated: Long = 0L, val deletedAt: Long = 0L,
) {
    val isDeleted: Boolean get() = deletedAt > 0L
}

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

    private const val SYNC_PATH = "local-feeds.json"

    /** Live subscriptions — tombstones filtered here so every existing caller stays honest. */
    fun subscriptions(ctx: Context): List<LocalSub> = allSubs(ctx).filter { !it.isDeleted }

    private fun allSubs(ctx: Context): List<LocalSub> {
        val arr = JSONArray(prefs(ctx).getString("subs", "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map {
                LocalSub(it.getLong("id"), it.getString("url"), it.getString("title"),
                    it.optLong("updated", 0L), it.optLong("deletedAt", 0L))
            }
    }

    private fun saveSubs(ctx: Context, subs: List<LocalSub>) {
        val arr = JSONArray()
        subs.forEach {
            arr.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)
                .put("updated", it.updated).put("deletedAt", it.deletedAt))
        }
        prefs(ctx).edit().putString("subs", arr.toString()).apply()
    }

    /** The round trip: pull `local-feeds.json`, id-union (newest [LocalSub.updated] wins,
     *  tombstones survive), save, push — the ListenStateStore pattern on the same one-thread
     *  sidecar executor. Rides the feed screen's load and the background fetch tick. */
    fun sync(ctx: Context, onMerged: ((List<LocalSub>) -> Unit)? = null) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            val local = allSubs(ctx)
            val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(ctx, SYNC_PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val rArr = runCatching { JSONArray(remoteText) }.getOrDefault(JSONArray())
                val remote = (0 until rArr.length()).map { rArr.getJSONObject(it) }.map {
                    LocalSub(it.getLong("id"), it.getString("url"), it.getString("title"),
                        it.optLong("updated", 0L), it.optLong("deletedAt", 0L))
                }
                // BY URL, not id: the two platforms derive different local ids from the same
                // URL (Java hashCode vs FNV) — the address is the identity on the wire, and
                // each platform re-derives its own id on read.
                (local + remote).groupBy { it.url }
                    .map { (_, versions) -> versions.maxByOrNull { it.updated }!! }
                    .map { it.copy(id = idFor(it.url)) }
            }
            saveSubs(ctx, merged)
            val out = JSONArray()
            merged.forEach {
                out.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)
                    .put("updated", it.updated).put("deletedAt", it.deletedAt))
            }
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(ctx, SYNC_PATH, out.toString())
            onMerged?.invoke(merged.filter { !it.isDeleted })
        }
    }

    /** Fetch + parse (validating), store with title. null = not a feed. */
    fun add(ctx: Context, url: String): LocalSub? {
        val clean = url.trim()
        val feed = fetch(clean) ?: return null
        val id = idFor(clean)
        val subs = subscriptions(ctx).toMutableList()
        subs.firstOrNull { it.id == id }?.let { return it }
        val sub = LocalSub(id, clean, feed.title.ifBlank { clean },
            updated = System.currentTimeMillis())
        subs.add(sub); saveSubs(ctx, allSubs(ctx).filter { it.id != id } + sub)
        sync(ctx)   // a subscription made anywhere appears everywhere
        return sub
    }

    /** Remove = TOMBSTONE, so the delete syncs instead of the other device resurrecting it. */
    fun remove(ctx: Context, id: Long) {
        val now = System.currentTimeMillis()
        saveSubs(ctx, allSubs(ctx).map {
            if (it.id == id) it.copy(deletedAt = now, updated = now) else it
        })
        sync(ctx)
    }

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
            // The show's cover, from this same parse (10-12): remembered for every surface that
            // names the show, and the picture of each EPISODE that carries none of its own. Only
            // a feed with audio in it is a show (review 10-12): a WordPress blog ships its 32px
            // site icon as the channel <image>, and every text post wore it as a blurry thumbnail.
            val isShow = feed.items.any { it.enclosureAudio.isNotBlank() }
            if (isShow) ShowCovers.remember(ctx, s.url, s.title, feed.imageUrl)
            val cover = feed.imageUrl.takeIf { isShow && it.startsWith("http") }
            for (it in feed.items) {
                val link = it.link.ifBlank { it.guid }
                if (link.isBlank()) continue
                out.add(FeedEntry(
                    id = -(abs((s.url + link).hashCode().toLong()) % 1_000_000_000L) - 1000L,
                    title = it.title.ifBlank { link }, feedTitle = s.title, url = link,
                    author = it.author.ifBlank { null }, content = it.content,
                    publishedAt = it.date, starred = star.contains(link), category = "local",
                    // The podcast layer: episode file + art straight from the parse, and the
                    // sub's own URL so FeedChapters can walk back to the 2.0 tags.
                    enclosureImage = it.enclosureImage.ifBlank { null }
                        ?: (if (it.enclosureAudio.isNotBlank()) cover else null),
                    enclosureAudio = it.enclosureAudio.ifBlank { null },
                    feedUrl = s.url
                ))
            }
        }
        return out
    }
}
