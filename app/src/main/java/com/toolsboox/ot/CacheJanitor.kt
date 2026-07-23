package com.toolsboox.ot

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Keeps the unbounded caches bounded (data-architecture step 1): the app accretes
 * re-fetchable copies — readable article caches, downloaded podcast audio, camera
 * temp shots, rendered share cards — that grow forever if nobody sweeps. This
 * janitor runs at most once a day, off the main thread, and only ever touches
 * RE-CREATABLE data. User media (attachments — A/V grams, annotation photos) and
 * day JSONs are deliberately NOT its business.
 */
object CacheJanitor {

    private const val DAY_MS = 24L * 3600 * 1000

    /** Call from app start — debounced to one sweep per day, always off-main. */
    fun runDaily(context: Context) {
        val prefs = context.getSharedPreferences("cache_janitor", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_run", 0L) < DAY_MS) return
        prefs.edit().putLong("last_run", now).apply()
        Thread {
            runCatching { sweep(context) }.onFailure { Timber.w(it, "cache janitor sweep failed") }
        }.start()
    }

    private fun sweep(context: Context) {
        // Short-lived render/temp caches: gone after a week.
        for (name in listOf("camera", "cards", "exports")) {
            ageSweep(File(context.cacheDir, name), days = 7)
        }
        // Readable article copies (Later List offline cache): re-fetchable on open.
        val articles = File(context.filesDir, "later-article-cache")
        ageSweep(articles, days = 90)
        sizeCap(articles, capBytes = 200L * 1024 * 1024)
        // Downloaded Later media (podcast audio): playback falls back to streaming.
        val media = File(context.filesDir, "later-media")
        ageSweep(media, days = 60)
        sizeCap(media, capBytes = 500L * 1024 * 1024)
    }

    /** Delete files not touched in [days]. */
    private fun ageSweep(dir: File, days: Int) {
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }

    /** Keep the newest files up to [capBytes]; delete the rest (oldest first). */
    private fun sizeCap(dir: File, capBytes: Long) {
        if (!dir.isDirectory) return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var used = 0L
        for (f in files) {
            used += f.length()
            if (used > capBytes) f.delete()
        }
    }
}
