package com.toolsboox.plugin.feeds.nw

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Offline media cache for Later-list audio (podcasts). Files are keyed by a hash of the source URL
 * so playback can prefer a local copy when one exists. Downloads are fire-and-forget and safe to
 * call repeatedly (a present file short-circuits).
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
     *  the finished file (or null on failure), posted on that thread. */
    fun download(context: Context, url: String, onDone: ((File?) -> Unit)? = null) {
        val app = context.applicationContext
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

    private const val MAX_BYTES = 1_500L * 1024 * 1024   // ~1.5 GB of podcasts
    private const val MAX_AGE_DAYS = 45L

    /** Janitor: failed downloads leaked .part files forever and finished episodes never
     *  aged out — cap by age, then by total size (oldest first). */
    fun prune(context: Context) = runCatching {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return@runCatching
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
    }.let {}
}
