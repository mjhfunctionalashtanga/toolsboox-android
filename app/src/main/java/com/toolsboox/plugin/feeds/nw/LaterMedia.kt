package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.FeedEntry
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Offline media cache for Later-list audio (podcasts). Files are keyed by a hash of the source URL
 * so playback can prefer a local copy when one exists. Downloads are fire-and-forget and safe to
 * call repeatedly (a present file short-circuits).
 *
 * Beside the files sits an **index.json** — url-hash → title / feed / entry id / artwork — because
 * a hashed filename is not an episode. Without it a download that had aged out of the server's
 * recent window was unreachable: the file was on the device, The Listen could not name it, and it
 * simply vanished from the lens. (The iPad has carried this index from the start —
 * `OfflineMediaStore.Item` — and unions its rows into The Listen; this is that parity.) The index
 * is a CACHE SIDECAR, never a source of truth: every row is re-derivable from a fresh fetch, and a
 * row whose file has gone is dropped by the janitor rather than trusted.
 */
object LaterMedia {
    private fun dir(context: Context) = File(context.filesDir, "later-media").apply { mkdirs() }

    private fun key(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3").take(5).ifBlank { "mp3" }
        return "${url.hashCode().toUInt()}.$ext"
    }

    fun localFile(context: Context, url: String): File = File(dir(context), key(url))

    fun isDownloaded(context: Context, url: String): Boolean =
        localFile(context, url).let { it.exists() && it.length() > 0 }

    /** Local path if cached, else the original URL — what to hand the player. */
    fun playableSource(context: Context, url: String): String =
        if (isDownloaded(context, url)) localFile(context, url).absolutePath else url

    /** Download [url] into the cache if not already present. Runs on a daemon thread; [onDone] gets
     *  the finished file (or null on failure), posted on that thread. [entry] is the episode the
     *  URL came off — pass it and the download files itself in the index, so The Listen can still
     *  name the thing months later. */
    fun download(context: Context, url: String, entry: FeedEntry? = null, onDone: ((File?) -> Unit)? = null) {
        val app = context.applicationContext
        // Remember the metadata BEFORE the bytes: a re-request for an episode already on disk
        // short-circuits here, and that is the common path once the auto-keep sweep has run —
        // if the row were only written after a fresh download, an episode downloaded before this
        // index existed would never acquire one.
        if (entry != null) remember(app, url, entry)
        if (isDownloaded(app, url)) { onDone?.invoke(localFile(app, url)); return }
        Thread {
            val dest = localFile(app, url)
            val ok = runCatching {
                val tmp = File(dest.absolutePath + ".part")
                java.net.URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(dest)
            }.onFailure { Timber.w(it, "later media download failed: $url") }.getOrDefault(false)
            onDone?.invoke(if (ok && isDownloaded(app, url)) dest else null)
        }.apply { isDaemon = true }.start()
    }

    // ---- The metadata index --------------------------------------------------------------

    /** One downloaded episode as The Listen needs to draw it — the iPad's `OfflineMediaStore.Item`. */
    data class Item(
        /** The audio enclosure URL: the file key, and what [playableSource] is asked about. */
        val audioUrl: String,
        /** The entry's REAL id, so a rebuilt row dedupes against the live list and its star /
         *  mark-read still reach Miniflux instead of a made-up synthetic id. */
        val entryId: Long,
        val title: String,
        val feedTitle: String,
        /** The episode's own page (show notes) — keeps "go to source" working on a rebuilt row. */
        val sourceUrl: String,
        val imageUrl: String?,
        val publishedAt: String
    )

    private fun indexFile(context: Context) = File(dir(context), "index.json")

    /** Every indexed episode whose file is still on disk, newest download first. */
    fun downloaded(context: Context): List<Item> = runCatching {
        val f = indexFile(context)
        if (!f.exists()) return@runCatching emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("audioUrl").ifBlank { return@mapNotNull null }
            Item(
                audioUrl = u,
                entryId = o.optLong("entryId"),
                title = o.optString("title"),
                feedTitle = o.optString("feedTitle"),
                sourceUrl = o.optString("sourceUrl"),
                imageUrl = o.optString("imageUrl").ifBlank { null },
                publishedAt = o.optString("publishedAt")
            )
        }.filter { isDownloaded(context, it.audioUrl) }
            .sortedByDescending { localFile(context, it.audioUrl).lastModified() }
    }.getOrDefault(emptyList())

    private fun writeIndex(context: Context, items: List<Item>) = runCatching {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().apply {
            put("audioUrl", it.audioUrl); put("entryId", it.entryId)
            put("title", it.title); put("feedTitle", it.feedTitle)
            put("sourceUrl", it.sourceUrl); put("imageUrl", it.imageUrl ?: "")
            put("publishedAt", it.publishedAt)
        })
        indexFile(context).writeText(arr.toString())
    }.onFailure { Timber.w(it, "later media index write failed") }.let {}

    /** File this episode's metadata under [url], replacing any older row for the same URL. */
    @Synchronized
    fun remember(context: Context, url: String, e: FeedEntry) {
        val row = Item(
            audioUrl = url, entryId = e.id, title = e.title, feedTitle = e.feedTitle,
            sourceUrl = e.url, imageUrl = e.imageUrl, publishedAt = e.publishedAt
        )
        // Read the raw file rather than `downloaded()`: that one drops rows whose bytes have not
        // landed yet, and this is called the instant a download STARTS.
        val existing = runCatching {
            val f = indexFile(context)
            if (!f.exists()) emptyList() else JSONArray(f.readText()).let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                    .mapNotNull { o ->
                        val u = o.optString("audioUrl").ifBlank { null } ?: return@mapNotNull null
                        Item(u, o.optLong("entryId"), o.optString("title"), o.optString("feedTitle"),
                            o.optString("sourceUrl"), o.optString("imageUrl").ifBlank { null },
                            o.optString("publishedAt"))
                    }
            }
        }.getOrDefault(emptyList())
        writeIndex(context, listOf(row) + existing.filter { it.audioUrl != url })
    }

    private const val MAX_BYTES = 1_500L * 1024 * 1024   // ~1.5 GB of podcasts
    private const val MAX_AGE_DAYS = 45L
    private const val KEEP_LATEST_DEFAULT = 8

    /** Most-recent audio episodes to keep offline (parity with the iPad's per-surface cap). */
    fun keepLimit(context: Context): Int =
        context.getSharedPreferences("feeds", 0).getInt("offline_keep_latest", KEEP_LATEST_DEFAULT).coerceIn(3, 20)

    /** Auto-keep the latest episodes offline (podcast-app behaviour, no star): download the newest
     *  [max] audio entries not already cached, then prune older ones back to the cap. `entries`
     *  newest-first; non-audio entries are skipped. Takes ENTRIES rather than bare URLs so each
     *  kept episode carries its title and feed into the index — a hashed file with no row is a
     *  file The Listen cannot show. */
    fun keepRecent(context: Context, entries: List<FeedEntry>, max: Int = keepLimit(context)) {
        var taken = 0
        for (e in entries) {
            if (taken >= max) break
            val u = e.audioUrl ?: continue
            if (u.isBlank()) continue
            taken++
            download(context, u, e)
        }
        prune(context)
    }

    /** Janitor: failed downloads leaked .part files forever and finished episodes never
     *  aged out — cap by age, then by total size (oldest first). */
    fun prune(context: Context) = runCatching {
        // index.json lives in this directory but is NOT an episode: without this guard the size
        // and count caps below would happily evict the very file that says what the episodes are.
        fun media(f: File) = f.isFile && f.name != "index.json"
        val files = dir(context).listFiles()?.filter { media(it) } ?: return@runCatching
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_AGE_DAYS * 24 * 3600 * 1000
        // Stale .part = an interrupted download (anything older than a day is dead).
        files.filter { it.name.endsWith(".part") && it.lastModified() < now - 24 * 3600 * 1000 }
            .forEach { it.delete() }
        val live = files.filter { !it.name.endsWith(".part") }.sortedBy { it.lastModified() }
        var total = live.sumOf { it.length() }
        for (f in live) {
            if (f.lastModified() < cutoff || total > MAX_BYTES) {
                total -= f.length()
                f.delete()
            } else break
        }
        // Count cap: keep only the most-recent N episodes (parity with the iPad's per-surface cap).
        dir(context).listFiles()?.filter { media(it) && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keepLimit(context))
            ?.forEach { it.delete() }
        // Drop index rows whose bytes are gone. Left alone, the index would grow forever and
        // The Listen would rebuild rows for episodes that no longer play (the .versions lesson:
        // the sidecar needs a janitor as much as the payload does).
        val surviving = downloaded(context)
        writeIndex(context, surviving)
    }.let {}
}
