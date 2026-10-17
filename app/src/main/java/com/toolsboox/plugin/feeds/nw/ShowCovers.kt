package com.toolsboox.plugin.feeds.nw

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.toolsboox.plugin.feeds.da.FeedEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * THE SHOW'S COVER, learned once per show and kept (Michael 2026-10-12, The Listen: "I wonder why
 * these aren't getting their podcasts cover pic or article featured image etc").
 *
 * Most podcast episodes carry no picture of their own — on his server 69% of the last 60 days'
 * 🎧 entries had no image enclosure and none had an inline `<img>` — so a row that only drew the
 * episode's own art was blank for most of The Listen. Every one of those shows HAS a cover: the
 * channel-level `itunes:image` (Apple requires it) at the head of its feed. This is where that
 * cover is remembered, so a row, the player and the synced ListenState can all name it.
 *
 * The ladder (one network trip per show, ever, then disk):
 *  1. what we already know — [lookup], memory then the small JSON index on disk; never network;
 *  2. the feed's own HEAD — `RssParser.channelImage`, the parse stops at the first item so a
 *     megabyte back catalogue costs a few kilobytes; also fed for free by FeedChapters' full
 *     parse on play, and by Local Feeds' own parse;
 *  3. the iTunes directory, last resort — searched by the show's name and accepted ONLY when the
 *     result names this exact feed URL (the same open index the 🔍 Find-a-show door uses); for a
 *     show known only by its name (an In-progress row this device has no feed for), only the
 *     result whose name is exactly this show's — the iPhone's rule (PodcastArt.itunesArtwork).
 *  (Not Miniflux's feed icon: on his server every 🎧 feed's icon is a ~2KB 32×32 favicon.)
 *
 * Keyed by the feed's XML URL — Miniflux `feed_url`, a Local Feed's own URL — and ALSO by the
 * show's name, because a row known only from the synced ListenState (the In-progress shelf, a
 * download that aged off the server) has a show and no feed URL.
 *
 * Bounded: [MAX_COVERS] shows and [MAX_MISSES] remembered misses, oldest out. The pictures
 * themselves ride [com.toolsboox.plugin.feeds.ui.FeedThumbCache] (memory + the pruned disk
 * spill), so covers show offline once seen. Nothing here touches the network on the main thread.
 */
object ShowCovers {

    /** A show we looked for and found nothing for — not asked again for this long (3 days, the
     *  iPhone's `missRetry`, so the two devices give up on a show for the same while). */
    private const val MISS_TTL_MS = 3L * 24 * 3600 * 1000

    /** A show asked for this process that came back with nothing and no miss (offline, a 500):
     *  asked again after this long, not only on the next launch (review 10-12 — an offline first
     *  visit used to leave every row waiting until the app restarted). */
    private const val ASK_RETRY_MS = 10L * 60 * 1000

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** One worker: a first visit to The Listen asks for each show once, in turn — never a burst
     *  of forty feed fetches at the same moment the list itself is loading. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "show-covers").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var loaded = false
    private val covers = LinkedHashMap<String, String>()
    private val misses = LinkedHashMap<String, Long>()
    /** Keys asked for this process, and when — a feed that failed (offline, 500) is not re-asked
     *  on every bind, only once [ASK_RETRY_MS] has passed. */
    private val asked = LinkedHashMap<String, Long>()
    /** Feeds whose head was read this process (FeedChapters' play-time parse) and named no cover:
     *  [find] goes straight to the directory instead of fetching the same feed again. */
    private val headNamedNone = HashSet<String>()

    private val learned = CopyOnWriteArrayList<() -> Unit>()

    /** Called on the MAIN thread whenever a new cover is learned — the list repaints the rows
     *  still waiting for one (and only those). */
    fun addListener(l: () -> Unit) { learned += l }
    fun removeListener(l: () -> Unit) { learned -= l }

    // ---- Read (never network) ----------------------------------------------------------------

    /** The cover for this show if we know it — by feed URL first, then by name. Disk at most
     *  (the first call of a process reads the small index), never the network. */
    fun lookup(context: Context, feedUrl: String?, show: String?): String? {
        ensureLoaded(context)
        synchronized(lock) {
            Keys.url(feedUrl)?.let { k -> covers[k]?.let { return it } }
            Keys.title(show)?.let { k -> covers[k]?.let { return it } }
        }
        return null
    }

    /** An entry's picture — what a play hands the player, and so what the synced ListenState
     *  carries. A listen entry: its OWN episode image, else its show's cover, else an image from
     *  its notes (the approved order, the iPhone's too — a notes `<img>` is as often a sponsor
     *  banner, and it must not travel to the other devices ahead of the cover). Anything else:
     *  its featured image, else its show's cover. */
    fun artFor(context: Context, e: FeedEntry): String? {
        val show = e.feedTitle.ifBlank { null }
        if (e.kind != "listen") return e.imageUrl ?: lookup(context, e.feedUrl, show)
        e.enclosureImage?.takeIf { it.startsWith("http") }?.let { return it }
        return lookup(context, e.feedUrl, show) ?: e.inlineImage
    }

    // ---- Learn --------------------------------------------------------------------------------

    /** Record [cover] for the show (both keys). Persisted off the main thread; listeners hear of
     *  it only when it is news. */
    fun remember(context: Context, feedUrl: String?, show: String?, cover: String?) {
        val c = cover?.trim()?.takeIf { it.startsWith("http") } ?: return
        ensureLoaded(context)
        var changed = false
        synchronized(lock) {
            for (k in listOfNotNull(Keys.url(feedUrl), Keys.title(show))) {
                if (covers[k] != c) { Keys.putBounded(covers, k, c, MAX_COVERS); changed = true }
                misses.remove(k)
            }
        }
        if (!changed) return
        val app = context.applicationContext
        worker.execute { persist(app) }
        main.post { learned.forEach { runCatching { it() } } }
    }

    /**
     * Ask for the show's cover in the background, at most once per show every [ASK_RETRY_MS]:
     * the feed head, then the iTunes directory. A title-only show (no feed URL — an In-progress
     * row this device has no feed for) goes to the directory alone, taken only on an exact name.
     */
    fun ensure(context: Context, feedUrl: String?, show: String?) {
        val key = Keys.url(feedUrl) ?: Keys.title(show) ?: return
        if (lookup(context, feedUrl, show) != null) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val at = asked[key]
            if (at != null && now - at < ASK_RETRY_MS) return
            Keys.putBounded(asked, key, now, MAX_COVERS)
        }
        val app = context.applicationContext
        worker.execute { runCatching { find(app, feedUrl, show) }.onFailure { Timber.d(it, "cover: %s", feedUrl ?: show) } }
    }

    /** FeedChapters read this feed whole and its head named no cover — [find] need not read it
     *  again this process (review 10-12: the play-time art used to re-fetch it, then ask iTunes,
     *  before the chapters could post). */
    fun feedNamedNone(feedUrl: String?) {
        val uk = Keys.url(feedUrl) ?: return
        synchronized(lock) { headNamedNone.add(uk) }
    }

    /**
     * The whole ladder, BLOCKING — call off the main thread (FeedChapters' play-time resolve
     * does). Returns the cover or null; learns what it finds, and remembers a miss only when the
     * feed was actually reached (a dead network tonight is not "this show has no cover").
     */
    fun find(context: Context, feedUrl: String?, show: String?): String? {
        lookup(context, feedUrl, show)?.let { return it }
        val fu = feedUrl?.trim()?.takeIf { it.startsWith("http") }
        // The miss is kept under the feed's key, or — a title-only show — its name's.
        val mk = Keys.url(fu) ?: Keys.title(show) ?: return null
        val skipHead = synchronized(lock) {
            val at = misses[mk]
            if (at != null && System.currentTimeMillis() - at < MISS_TTL_MS) return null
            mk in headNamedNone
        }
        // A head already read and found bare counts as reached: the directory's answer decides.
        var reached = skipHead
        var cover: String? = if (fu == null || skipHead) null else runCatching {
            http.newCall(Request.Builder().url(fu).get().build()).execute().use { r ->
                reached = true
                if (!r.isSuccessful) null else r.body?.byteStream()?.let { RssParser.channelImage(it) }
            }
        }.getOrNull()
        if (cover == null && !show.isNullOrBlank()) {
            val viaDirectory = runCatching { itunes(fu, show) }
            if (viaDirectory.isSuccess) reached = true
            cover = viaDirectory.getOrNull()
        }
        if (cover != null) remember(context, fu, show, cover)
        else if (reached) {
            synchronized(lock) { Keys.putBounded(misses, mk, System.currentTimeMillis(), MAX_MISSES) }
            worker.execute { persist(context.applicationContext) }
        }
        return cover
    }

    /** The iTunes search, by name — see [Keys.itunesArtwork] for which answer is taken. Throws
     *  when unreachable. */
    private fun itunes(feedUrl: String?, show: String): String? {
        val term = java.net.URLEncoder.encode(show.trim(), "UTF-8")
        val body = http.newCall(Request.Builder()
            .url("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=15&term=$term").get().build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        return Keys.itunesArtwork(body, feedUrl, show)
    }

    // ---- Disk ---------------------------------------------------------------------------------

    private const val MAX_COVERS = 600
    private const val MAX_MISSES = 600

    private fun file(context: Context) =
        File(File(context.filesDir, "feed-cache").apply { mkdirs() }, "show-covers.json")

    private fun ensureLoaded(context: Context) {
        synchronized(lock) { if (loaded) return }
        val parsed = runCatching {
            val f = file(context)
            if (f.exists()) JSONObject(f.readText()) else null
        }.getOrNull()
        synchronized(lock) {
            if (loaded) return
            loaded = true
            parsed?.optJSONObject("covers")?.let { o ->
                o.keys().forEach { k -> o.optString(k).takeIf { it.startsWith("http") }?.let { covers[k] = it } }
            }
            parsed?.optJSONObject("misses")?.let { o -> o.keys().forEach { k -> misses[k] = o.optLong(k) } }
        }
    }

    /** Temp-then-rename, the house rule — a half-written index would forget every show. */
    private fun persist(context: Context) {
        val json = synchronized(lock) {
            JSONObject()
                .put("v", 1)
                .put("covers", JSONObject().also { o -> covers.forEach { (k, v) -> o.put(k, v) } })
                .put("misses", JSONObject().also { o -> misses.forEach { (k, v) -> o.put(k, v) } })
                .toString()
        }
        runCatching {
            val target = file(context)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }.onFailure { Timber.w(it, "show-covers save failed") }
    }

    /** The pure half — keys and judgments, unit-tested without Android. */
    object Keys {
        /** "u:" + the feed URL without its scheme or trailing slash, lower-cased — http/https and
         *  a stray slash are the same feed. Null for anything that is not a web address. */
        fun url(feedUrl: String?): String? {
            val u = feedUrl?.trim().orEmpty()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return null
            val bare = u.substringAfter("://").trimEnd('/').lowercase()
            return if (bare.isBlank()) null else "u:$bare"
        }

        /** "t:" + the show's name, case and spacing folded. Null when blank. */
        fun title(show: String?): String? {
            val t = show?.trim()?.lowercase()?.replace(SPACES, " ").orEmpty()
            return if (t.isBlank()) null else "t:$t"
        }

        /** Compiled once: every Listen row's bind folds its show's name through [title]. */
        private val SPACES = Regex("\\s+")

        fun sameFeed(a: String?, b: String?): Boolean {
            val x = url(a) ?: return false
            return x == url(b)
        }

        /**
         * The cover out of an iTunes search answer, accepted only for the SAME show: the result
         * whose `feedUrl` is this feed when the feed is known, else the one whose name is exactly
         * this show's (case and spacing folded). A near miss is someone else's cover — no picture
         * beats the wrong one. The iPhone's PodcastArt.itunesArtwork, rule for rule.
         */
        fun itunesArtwork(body: String, feedUrl: String?, show: String?): String? {
            val wantFeed = url(feedUrl)
            val wantShow = title(show)
            if (wantFeed == null && wantShow == null) return null
            val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val same = if (wantFeed != null) url(o.optString("feedUrl")) == wantFeed
                    else title(o.optString("collectionName")) == wantShow
                if (!same) continue
                val art = o.optString("artworkUrl600").ifBlank { upsize(o.optString("artworkUrl100")) }
                return art.takeIf { it.startsWith("http") }
            }
            return null
        }

        /**
         * A LISTEN row's pictures, best first (Michael's order, 10-12, the iPhone's too): the
         * episode's own image → its Audiobookshelf cover path → the show's cover → an image from
         * the notes. Each is tried in turn when the one before fails to load. Blanks and repeats
         * drop out.
         */
        fun listenOrder(own: String?, cover: String?, inline: String?): List<String> {
            val o = own?.trim().orEmpty()
            return listOfNotNull(
                o.takeIf { it.startsWith("http") }, o.takeIf { it.startsWith("/api/") },
                cover?.takeIf { it.startsWith("http") }, inline?.takeIf { it.startsWith("http") },
            ).distinct()
        }

        /** iTunes' 100px art URL asked for at 600px — the directory serves any square size. */
        fun upsize(art100: String?): String =
            art100.orEmpty().replace("100x100bb", "600x600bb").replace("100x100", "600x600")

        /** Put, moving [key] to the newest end, dropping the oldest past [max]. */
        fun <V> putBounded(map: LinkedHashMap<String, V>, key: String, value: V, max: Int) {
            map.remove(key)
            map[key] = value
            while (map.size > max) map.remove(map.keys.first())
        }
    }
}
