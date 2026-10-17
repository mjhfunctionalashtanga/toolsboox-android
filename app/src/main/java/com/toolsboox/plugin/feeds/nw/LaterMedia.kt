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
    fun download(context: Context, url: String, entry: FeedEntry? = null, reason: String = "",
                 onDone: ((File?) -> Unit)? = null) {
        val app = context.applicationContext
        // Remember the metadata BEFORE the bytes: a re-request for an episode already on disk
        // short-circuits here, and that is the common path once the auto-keep sweep has run —
        // if the row were only written after a fresh download, an episode downloaded before this
        // index existed would never acquire one.
        if (entry != null) remember(app, url, entry, reason)
        else if (reason.isNotBlank()) rememberReason(app, url, reason)
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
        val publishedAt: String,
        /** WHY the file is here — "asked" (you tapped ⬇, on any device) or "recent" (the silent
         *  newest-N sweep) — so a row can say so (Michael, 09-19: "hard to tell what ends up there
         *  and why between devices"). Asked files are exempt from the count cap and the age cap. */
        val reason: String = ""
    )

    const val REASON_ASKED = "asked"
    const val REASON_RECENT = "recent"

    /** Why this URL's file is on the device, or null when it isn't. */
    fun reasonFor(context: Context, audioUrl: String): String? =
        if (!isDownloaded(context, audioUrl)) null
        else downloaded(context).firstOrNull { it.audioUrl == audioUrl }?.reason?.ifBlank { REASON_RECENT }

    /** In words, for a row: "you asked" / "newest 8". */
    fun reasonLabel(context: Context, audioUrl: String): String? = when (reasonFor(context, audioUrl)) {
        REASON_ASKED -> "you asked"
        REASON_RECENT -> "newest ${keepLimit(context)}"
        else -> null
    }

    // ---- Dismissed: "I removed this one, don't bring it back" ---------------------------------

    private fun dismissedFile(context: Context) = File(dir(context), "dismissed.json")

    private fun dismissed(context: Context): MutableSet<String> = runCatching {
        val f = dismissedFile(context)
        if (!f.exists()) mutableSetOf() else JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
    }.getOrDefault(mutableSetOf())

    private fun writeDismissed(context: Context, set: Set<String>) = runCatching {
        dismissedFile(context).writeText(JSONArray(set.toList()).toString())
    }.let {}

    /** An explicit ask outranks an old removal — clear the mark when you ask again. */
    fun undismiss(context: Context, audioUrl: String) {
        val d = dismissed(context); if (d.remove(audioUrl)) writeDismissed(context, d)
    }

    private fun indexFile(context: Context) = File(dir(context), "index.json")

    /** Every indexed episode whose file is still on disk, newest download first. */
    /** Let one episode's file go (its place is kept; it streams again when played). */
    fun remove(context: Context, audioUrl: String, dismiss: Boolean = true): Boolean {
        val gone = localFile(context, audioUrl).let { !it.exists() || it.delete() }
        runCatching { writeIndex(context, downloaded(context).filter { it.audioUrl != audioUrl }) }
        // Removed by hand = do not bring it back on the next newest-N sweep.
        if (dismiss) { val d = dismissed(context); if (d.add(audioUrl)) writeDismissed(context, d) }
        return gone
    }

    /** Every kept episode's file, gone. */
    fun removeAll(context: Context) {
        downloaded(context).forEach { localFile(context, it.audioUrl).delete() }
        runCatching { writeIndex(context, emptyList()) }
    }

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
                publishedAt = o.optString("publishedAt"),
                reason = o.optString("reason")
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
            put("publishedAt", it.publishedAt); put("reason", it.reason)
        })
        indexFile(context).writeText(arr.toString())
    }.onFailure { Timber.w(it, "later media index write failed") }.let {}

    /** File this episode's metadata under [url], replacing any older row for the same URL. */
    @Synchronized
    fun remember(context: Context, url: String, e: FeedEntry, reason: String = "") {
        val existing = rawIndex(context)
        // An ask outranks the sweep: once "asked", a later sweep pass never demotes it to "recent".
        val prior = existing.firstOrNull { it.audioUrl == url }
        val why = if (prior?.reason == REASON_ASKED) REASON_ASKED else reason.ifBlank { prior?.reason.orEmpty() }
        val row = Item(
            audioUrl = url, entryId = e.id, title = e.title, feedTitle = e.feedTitle,
            // The episode's picture, else its show's cover (10-12) — a download outlives its feed
            // row, and this is the only picture its Downloaded row will ever have.
            sourceUrl = e.url, imageUrl = ShowCovers.artFor(context, e), publishedAt = e.publishedAt, reason = why
        )
        writeIndex(context, listOf(row) + existing.filter { it.audioUrl != url })
    }

    /** The ask's own row when no feed entry is at hand (an ask made on another device names an
     *  episode this one has never listed): what the synced ListenState knows — title, show, art. */
    @Synchronized
    fun rememberAsk(context: Context, audioUrl: String, sourceUrl: String, title: String, show: String, artworkUrl: String) {
        val existing = rawIndex(context)
        val prior = existing.firstOrNull { it.audioUrl == audioUrl }
        val row = Item(
            audioUrl = audioUrl, entryId = prior?.entryId ?: 0L,
            title = title.ifBlank { prior?.title ?: humanize(audioUrl) },
            feedTitle = show.ifBlank { prior?.feedTitle.orEmpty() },
            sourceUrl = sourceUrl.ifBlank { prior?.sourceUrl.orEmpty() },
            imageUrl = artworkUrl.ifBlank { prior?.imageUrl }, publishedAt = prior?.publishedAt.orEmpty(),
            reason = REASON_ASKED
        )
        writeIndex(context, listOf(row) + existing.filter { it.audioUrl != audioUrl })
    }

    @Synchronized
    private fun rememberReason(context: Context, url: String, reason: String) {
        val existing = rawIndex(context)
        val prior = existing.firstOrNull { it.audioUrl == url } ?: return
        if (prior.reason == REASON_ASKED || prior.reason == reason) return
        writeIndex(context, listOf(prior.copy(reason = reason)) + existing.filter { it.audioUrl != url })
    }

    private fun humanize(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').replace(Regex("[-_]+"), " ").trim()

    /** The raw index, rows whose bytes have not landed yet included — `downloaded()` drops those,
     *  and the writers here run the instant a download STARTS. */
    private fun rawIndex(context: Context): List<Item> = runCatching {
        val f = indexFile(context)
        if (!f.exists()) emptyList() else JSONArray(f.readText()).let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                .mapNotNull { o ->
                    val u = o.optString("audioUrl").ifBlank { null } ?: return@mapNotNull null
                    Item(u, o.optLong("entryId"), o.optString("title"), o.optString("feedTitle"),
                        o.optString("sourceUrl"), o.optString("imageUrl").ifBlank { null },
                        o.optString("publishedAt"), o.optString("reason"))
                }
        }
    }.getOrDefault(emptyList())

    /** The store's size cap when no choice has been made — the iOS default, so both sides hold
     *  the same amount (09-19: "same cap and age on both"). Chosen in Settings › Storage. */
    private const val DEFAULT_CAP_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_AGE_DAYS = 60L
    private const val KEEP_LATEST_DEFAULT = 8

    /** The size choices Settings › Storage offers — the iOS picker's list. */
    val CAP_CHOICES: List<Pair<String, Long>> = listOf(
        "500 MB" to 500L * 1024 * 1024, "1 GB" to 1024L * 1024 * 1024, "2 GB" to 2L * 1024 * 1024 * 1024,
        "5 GB" to 5L * 1024 * 1024 * 1024, "10 GB" to 10L * 1024 * 1024 * 1024)

    fun capBytes(context: Context): Long =
        context.getSharedPreferences("feeds", 0).getLong("offline_cap_bytes", DEFAULT_CAP_BYTES).coerceAtLeast(100L * 1024 * 1024)

    fun setCapBytes(context: Context, bytes: Long) =
        context.getSharedPreferences("feeds", 0).edit().putLong("offline_cap_bytes", bytes).apply()

    /** Most-recent audio episodes to keep offline by the silent sweep — 0 turns the sweep OFF, so
     *  every download is one you asked for (09-19: "make downloads purely deliberate"). */
    fun keepLimit(context: Context): Int =
        context.getSharedPreferences("feeds", 0).getInt("offline_keep_latest", KEEP_LATEST_DEFAULT).coerceIn(0, 20)

    fun setKeepLimit(context: Context, n: Int) =
        context.getSharedPreferences("feeds", 0).edit().putInt("offline_keep_latest", n.coerceIn(0, 20)).apply()

    /**
     * What the FLEET asked for and this device does not hold yet — every synced ListenState with
     * the ask on it, fetched here, headless (the fetch worker and the feeds surface both call
     * this). An episode you removed by hand stays removed until you ask again.
     */
    private val MEDIA_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac", "mp4", "m4v", "mov", "webm")

    /** Is this URL itself a media file (not an episode page or a watch page)? */
    fun isDirectMedia(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase() in MEDIA_EXT

    /**
     * A Later filing of an audio/video item becomes an ASK (the one door `IntakePageStore.fileLink`
     * opens for the listen and watch lanes): the synced `wantOffline` on the URL, this device's
     * download when the URL is a media file, and the "removed by hand" mark lifted — asking again
     * outranks it. A page URL waits for its enclosure: [fulfilWanted] back-fills it from the feed.
     */
    fun askForLater(context: Context, url: String, title: String, image: String?) {
        val app = context.applicationContext
        if (!url.startsWith("http")) return
        val direct = isDirectMedia(url)
        undismiss(app, url)
        com.toolsboox.plugin.calendar.ot.ListenStateStore.setWantOffline(app, url, true, audioUrl = if (direct) url else "",
            title = title, artworkUrl = image?.takeIf { it.startsWith("http") }.orEmpty())
        if (direct) {
            rememberAsk(app, url, url, title, "", image.orEmpty())
            download(app, url, null, REASON_ASKED)
        }
    }

    fun fulfilWanted(context: Context) {
        val app = context.applicationContext
        val skip = dismissed(app)
        // Asks recorded off a page URL (a share, a Later row) have no enclosure yet: the feed's
        // own entry for that page supplies it, once, and the ask becomes fetchable everywhere.
        val blank = com.toolsboox.plugin.calendar.ot.ListenStateStore.wanted(app).filter { it.audioUrl.isBlank() }
        if (blank.isNotEmpty()) {
            val known = runCatching { FeedCache.loadFreshestEntries(app) }.getOrDefault(emptyList())
            for (s in blank) {
                val e = known.firstOrNull { it.url == s.id && !it.audioUrl.isNullOrBlank() } ?: continue
                com.toolsboox.plugin.calendar.ot.ListenStateStore.setWantOffline(app, s.id, true, audioUrl = e.audioUrl!!,
                    title = e.title, show = e.feedTitle, artworkUrl = ShowCovers.artFor(app, e).orEmpty())
                remember(app, e.audioUrl!!, e, REASON_ASKED)
            }
        }
        for (s in com.toolsboox.plugin.calendar.ot.ListenStateStore.wanted(app)) {
            val url = s.audioUrl
            if (url.isBlank() || url in skip || isDownloaded(app, url)) {
                if (url.isNotBlank() && isDownloaded(app, url)) rememberReason(app, url, REASON_ASKED)
                continue
            }
            rememberAsk(app, url, s.id, s.title, s.show, s.artworkUrl)
            download(app, url, null, REASON_ASKED)
        }
    }

    /** Auto-keep the latest episodes offline (podcast-app behaviour, no star): download the newest
     *  [max] audio entries not already cached, then prune older ones back to the cap. `entries`
     *  newest-first; non-audio entries are skipped. Takes ENTRIES rather than bare URLs so each
     *  kept episode carries its title and feed into the index — a hashed file with no row is a
     *  file The Listen cannot show. */
    fun keepRecent(context: Context, entries: List<FeedEntry>, max: Int = keepLimit(context)) {
        if (max <= 0) { prune(context); return }   // the sweep is OFF: only asks download
        val skip = dismissed(context)
        var taken = 0
        for (e in entries) {
            if (taken >= max) break
            val u = e.audioUrl ?: continue
            if (u.isBlank() || u in skip) continue
            taken++
            download(context, u, e, REASON_RECENT)
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
        // What you ASKED for is yours until you remove it: exempt from the age cap and the
        // count cap; only the size cap can evict it, and last (oldest recent files go first).
        val askedNames = rawIndex(context).filter { it.reason == REASON_ASKED }
            .map { localFile(context, it.audioUrl).name }.toSet()
        val live = files.filter { !it.name.endsWith(".part") }
            .sortedWith(compareBy<File> { it.name in askedNames }.thenBy { it.lastModified() })
        var total = live.sumOf { it.length() }
        val cap = capBytes(context)
        for (f in live) {
            val asked = f.name in askedNames
            if ((!asked && f.lastModified() < cutoff) || total > cap) {
                total -= f.length()
                f.delete()
            } else if (!asked) continue else break
        }
        // Count cap on the SWEEP's files only: keep the most-recent N it brought in.
        dir(context).listFiles()?.filter { media(it) && !it.name.endsWith(".part") && it.name !in askedNames }
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
