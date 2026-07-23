package com.toolsboox.plugin.feeds.da

import com.toolsboox.ot.HtmlText

/**
 * One feed entry from Miniflux, flattened to what the Ledger's feed list + reader need.
 * Deliberately lightweight (no persistence) — the source of truth stays on the Miniflux
 * server; starring/annotating writes a [com.toolsboox.plugin.calendar.da.v2.ReadingEvent]
 * into the day JSON so it joins the shared corpus + sync.
 */
data class FeedEntry(
    val id: Long,
    val title: String,
    val feedTitle: String,
    val url: String,
    val author: String?,
    val content: String,
    val publishedAt: String,
    val starred: Boolean,
    /** Miniflux read/unread status for this entry (true = read). */
    val read: Boolean = false,
    /** Miniflux category/folder title (the feed's folder), null if uncategorised. */
    val category: String? = null,
    /** First image enclosure URL (podcast/video art), used when the content has no inline image. */
    val enclosureImage: String? = null,
    /** First audio enclosure URL (the podcast episode's .mp3/.m4a), used to play the real audio. */
    val enclosureAudio: String? = null
) {
    /** The playable audio URL for this entry: the audio enclosure, else the entry URL if it looks
     *  like a direct audio file. Null when there's nothing to play as audio. */
    val audioUrl: String?
        get() = enclosureAudio?.takeIf { it.startsWith("http") }
            ?: url.takeIf { u -> listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus").any { u.lowercase().substringBefore('?').endsWith(it) } }

    /** Read / Watch / Listen lens. Primary signal is the Miniflux category's leading emoji
     *  (📖 / 📺 / 🎧, per the shared grammar with the iPad); falls back to a media heuristic. */
    val kind: String
        get() {
            val c = category?.trim().orEmpty()
            val cl = c.lowercase()
            val u = url.lowercase()
            return when {
                c.startsWith("📺") -> "watch"
                c.startsWith("🎧") -> "listen"
                c.startsWith("📖") -> "read"
                "watch" in cl || "video" in cl || "youtube" in u || "youtu.be" in u || "vimeo" in u -> "watch"
                "listen" in cl || "podcast" in cl || "audio" in cl || u.endsWith(".mp3") -> "listen"
                else -> "read"
            }
        }

    /** Category title with the leading media emoji stripped (for folder labels). */
    val categoryLabel: String?
        get() = category?.trim()?.let { c ->
            for (e in listOf("📖", "📺", "🎧")) if (c.startsWith(e)) return c.removePrefix(e).trim()
            c
        }

    /** Featured image for the list row: the first inline <img>, else the image enclosure. */
    val imageUrl: String?
        get() = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(content)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
            ?: enclosureImage?.takeIf { it.startsWith("http") }

    /** Plain-text blurb for the list row. */
    val blurb: String
        get() = HtmlText.toPlain(content).take(180)
}
