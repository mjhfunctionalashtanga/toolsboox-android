package com.toolsboox.plugin.feeds.da

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
    val starred: Boolean
) {
    /** Plain-text blurb for the list row. */
    val blurb: String
        get() = content
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z#0-9]+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
}
