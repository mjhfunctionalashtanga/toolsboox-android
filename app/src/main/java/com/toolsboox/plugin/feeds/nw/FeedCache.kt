package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.FeedEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device cache so the RSS feed is readable offline: it stores the fetched entry
 * lists (with their UNPARSED content) per view, plus the PARSED (readability) HTML
 * per article. Nothing here needs the network; a failed fetch falls back to this.
 */
object FeedCache {
    private fun dir(context: Context) =
        File(context.filesDir, "feed-cache").apply { mkdirs() }

    private fun listFile(context: Context, key: String) = File(dir(context), "list-$key.json")
    private fun contentFile(context: Context, id: Long) = File(dir(context), "content-$id.html")

    // MARK: - Entry lists (unparsed content rides along inside each entry)

    fun saveEntries(context: Context, key: String, entries: List<FeedEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("id", e.id); put("title", e.title); put("feedTitle", e.feedTitle)
                put("url", e.url); put("author", e.author ?: JSONObject.NULL)
                put("content", e.content); put("publishedAt", e.publishedAt)
                put("starred", e.starred); put("read", e.read)
                put("category", e.category ?: JSONObject.NULL)
                put("enclosureImage", e.enclosureImage ?: JSONObject.NULL)
                put("enclosureAudio", e.enclosureAudio ?: JSONObject.NULL)
                put("feedUrl", e.feedUrl ?: JSONObject.NULL)
            })
        }
        runCatching { listFile(context, key).writeText(arr.toString()) }
    }

    fun loadEntries(context: Context, key: String): List<FeedEntry> = runCatching {
        val f = listFile(context, key)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FeedEntry(
                id = o.optLong("id"), title = o.optString("title"), feedTitle = o.optString("feedTitle"),
                url = o.optString("url"), author = o.opt("author")?.takeIf { it is String } as String?,
                content = o.optString("content"), publishedAt = o.optString("publishedAt"),
                starred = o.optBoolean("starred"), read = o.optBoolean("read"),
                category = o.opt("category")?.takeIf { it is String } as String?,
                enclosureImage = o.opt("enclosureImage")?.takeIf { it is String } as String?,
                enclosureAudio = o.opt("enclosureAudio")?.takeIf { it is String } as String?,
                feedUrl = o.opt("feedUrl")?.takeIf { it is String } as String?
            )
        }
    }.getOrDefault(emptyList())

    // MARK: - Freshest list + disk thumbnails (what the home-screen list widget reads)

    /**
     * The most recently written entry list, whatever view wrote it. The Feed screen persists
     * list-<mode>_<kind>.json per view; a widget (or the widget-tap router in MainActivity)
     * can't know which view was last open, so "freshest file wins" is the honest answer — it is
     * the list the person most recently looked at. Pure disk read; nothing here fetches.
     */
    fun loadFreshestEntries(context: Context): List<FeedEntry> {
        val lists = dir(context).listFiles { f -> f.name.startsWith("list-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val freshest = lists.firstOrNull() ?: return emptyList()
        val key = freshest.name.removePrefix("list-").removeSuffix(".json")
        return loadEntries(context, key)
    }

    /**
     * Where an entry's row thumbnail lives ON DISK, keyed by the image URL's hash. The feed
     * screen's in-memory [com.toolsboox.plugin.feeds.ui.FeedThumbCache] spills every thumbnail
     * it loads into this file, and the widget process reads ONLY these files — a widget never
     * fetches (short-lived process, no session, a fetch would only ever fail), so an entry the
     * screen hasn't shown yet simply has no picture on the home screen, which is the truthful
     * state. SHA-1 of the URL, not the URL: image URLs carry query strings and slashes that
     * have no business in a filename.
     */
    fun thumbFile(context: Context, url: String): File =
        File(dir(context), "thumb-${sha1(url)}.jpg")

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // MARK: - Parsed (readability) article HTML

    fun saveContent(context: Context, id: Long, html: String) {
        runCatching { contentFile(context, id).writeText(html) }
    }

    fun loadContent(context: Context, id: Long): String? = runCatching {
        val f = contentFile(context, id); if (f.exists()) f.readText() else null
    }.getOrNull()

    // MARK: - Podcast sidecars (chapters + transcript pointers, per entry)

    private fun podFile(context: Context, id: Long) = File(dir(context), "pod-$id.json")
    private fun transcriptFile(context: Context, id: Long) = File(dir(context), "transcript-$id.txt")

    /**
     * The per-entry podcast metadata blob [FeedChapters] resolves: {"source", "chapters":
     * [{"start","title"}], "chaptersUrl", "transcriptUrl", "transcriptType"}. Small JSON,
     * absent-tolerant — a missing/garbled file just means "resolve again"; an EXISTING file
     * with an empty chapter list means "we looked, there are none" and stops the re-looking.
     */
    fun savePodMeta(context: Context, id: Long, meta: JSONObject) {
        runCatching { podFile(context, id).writeText(meta.toString()) }
    }

    fun loadPodMeta(context: Context, id: Long): JSONObject? = runCatching {
        val f = podFile(context, id)
        if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()

    /** The fetched transcript, already normalised to plain "[m:ss] line" text. */
    fun saveTranscript(context: Context, id: Long, text: String) {
        runCatching { transcriptFile(context, id).writeText(text) }
    }

    fun loadTranscript(context: Context, id: Long): String? = runCatching {
        val f = transcriptFile(context, id); if (f.exists()) f.readText() else null
    }.getOrNull()

    // MARK: - Janitor

    // Matched to iOS (prefetch 400 / cap 800 / 60-day age) so both devices keep the same
    // offline depth. Parsed articles are small HTML; 800 stays well clear of the disk.
    private const val MAX_CONTENT_FILES = 800
    private const val MAX_AGE_DAYS = 60L

    /**
     * Prune old parsed-article HTML and podcast sidecars. Every refresh can add up to ~400
     * content-<id>.html files (now plus pod-/transcript- sidecars, plus the thumb- JPEGs the
     * list widget reads — tiny, but URL-keyed, so nothing else would ever retire a dead one)
     * and nothing ever removed them (the `.versions` disk-fill lesson): cap by age AND count,
     * oldest first. Cheap (one listFiles) — call opportunistically after a refresh, off the
     * main thread.
     */
    fun prune(context: Context) = runCatching {
        val files = dir(context).listFiles { f ->
            f.name.startsWith("content-") || f.name.startsWith("pod-") ||
                f.name.startsWith("transcript-") || f.name.startsWith("thumb-")
        } ?: return@runCatching
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24 * 3600 * 1000
        val sorted = files.sortedBy { it.lastModified() }
        var live = sorted.size
        for (f in sorted) {
            if (f.lastModified() < cutoff || live > MAX_CONTENT_FILES) {
                if (f.delete()) live--
            } else break
        }
    }.let {}
}
