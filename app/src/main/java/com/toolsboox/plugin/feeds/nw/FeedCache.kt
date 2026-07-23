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
                put("starred", e.starred); put("category", e.category ?: JSONObject.NULL)
                put("enclosureImage", e.enclosureImage ?: JSONObject.NULL)
                put("enclosureAudio", e.enclosureAudio ?: JSONObject.NULL)
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
                starred = o.optBoolean("starred"),
                category = o.opt("category")?.takeIf { it is String } as String?,
                enclosureImage = o.opt("enclosureImage")?.takeIf { it is String } as String?,
                enclosureAudio = o.opt("enclosureAudio")?.takeIf { it is String } as String?
            )
        }
    }.getOrDefault(emptyList())

    // MARK: - Parsed (readability) article HTML

    fun saveContent(context: Context, id: Long, html: String) {
        runCatching { contentFile(context, id).writeText(html) }
    }

    fun loadContent(context: Context, id: Long): String? = runCatching {
        val f = contentFile(context, id); if (f.exists()) f.readText() else null
    }.getOrNull()

    // MARK: - Janitor

    private const val MAX_CONTENT_FILES = 400
    private const val MAX_AGE_DAYS = 60L

    /**
     * Prune old parsed-article HTML. Every refresh can add up to ~60 content-<id>.html
     * files and nothing ever removed them (the `.versions` disk-fill lesson): cap by age
     * AND count, oldest first. Cheap (one listFiles) — call opportunistically after a
     * refresh, off the main thread.
     */
    fun prune(context: Context) = runCatching {
        val files = dir(context).listFiles { f -> f.name.startsWith("content-") } ?: return@runCatching
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
