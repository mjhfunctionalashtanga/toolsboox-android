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
    val enclosureAudio: String? = null,
    /**
     * First VIDEO enclosure URL — the downloadable file for a Watch entry.
     *
     * Michael's YouTube pipeline puts the pulled video on R2 and the feed carries it "like what you
     * do for the podcasts": as an enclosure. Nothing read video enclosures before, so those files
     * were invisible to the app — a Watch row looked un-downloadable even when its file was sitting
     * there, which is what made "keep offline" on a video only ever able to say "queued".
     */
    val enclosureVideo: String? = null,
    /** Transcript enclosure URL (RSS podcast:transcript) when the feed surfaces one — best-effort. */
    val enclosureTranscript: String? = null,
    /** The feed's own XML URL (Miniflux `feed.feed_url` / the local sub's URL). The Podcasting 2.0
     *  layer (chapters/transcripts) lives in that XML and nowhere in the Miniflux API — this is
     *  the address [com.toolsboox.plugin.feeds.nw.FeedChapters] goes back to for it. */
    val feedUrl: String? = null
) {
    /** The playable audio URL for this entry: the audio enclosure, else the entry URL if it looks
     *  like a direct audio file. Null when there's nothing to play as audio. */
    val audioUrl: String?
        get() = enclosureAudio?.takeIf { it.startsWith("http") }
            ?: url.takeIf { u -> listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus").any { u.lowercase().substringBefore('?').endsWith(it) } }

    /**
     * THE FETCHABLE MEDIA FILE for this entry, audio or video, or null when the feed named only a
     * page. One question — "is there a file?" — asked in one place, because the bar, the keep verb
     * and the fulfil pass must all agree about which entries can actually be downloaded today.
     *
     * A YouTube WATCH LINK is not a file: fetching one stores an HTML page under a name claiming to
     * be a video, and the failure only shows up later as silence with no signal.
     */
    val mediaUrl: String?
        get() = audioUrl ?: enclosureVideo?.takeIf { it.startsWith("http") }
            ?: url.takeIf { u -> listOf(".mp4", ".m4v", ".webm", ".mkv").any { u.lowercase().substringBefore('?').endsWith(it) } }

    /** The episode's transcript URL, when the feed shipped one. */
    val transcriptUrl: String?
        get() = enclosureTranscript?.takeIf { it.startsWith("http") }

    /** Read / Watch / Listen lens. Primary signal is the Miniflux category's leading emoji
     *  (📖 / 📺 / 🎧, per the shared grammar with the iPad); falls back to a media heuristic. */
    // `by lazy` (audit 09-10): `kind`, `imageUrl` and `blurb` were GETTERS, so every bind, every
    // search keystroke and every directory count re-ran an HTML strip and a fresh Regex per row —
    // on a 5,000-row mail list that was the whole scroll budget. The inputs are immutable ctor
    // fields, so computing once per instance is exact; `copy()` simply recomputes on first touch.
    val kind: String by lazy {
            val c = category?.trim().orEmpty()
            val byName = categoryKind(c)
            val u = url.lowercase()
            when {
                c.startsWith("📺") -> "watch"
                c.startsWith("🎧") -> "listen"
                c.startsWith("📖") -> "read"
                byName == "watch" || "youtube" in u || "youtu.be" in u || "vimeo" in u -> "watch"
                // Any entry carrying a playable audio enclosure IS a listen item, whatever its
                // category is named: a podcast episode's `url` is the show-notes webpage (not a
                // `.mp3`), and its Miniflux folder is often a plain name ("The Daily") with no 🎧
                // and no "podcast"/"audio" in it — so keying off category text alone dropped real
                // audio out of The Listen. `audioUrl` covers both the audio enclosure and a direct
                // audio-file URL.
                byName == "listen" || audioUrl != null -> "listen"
                else -> "read"
            }
    }

    /** Category title with the leading media emoji stripped (for folder labels). */
    val categoryLabel: String?
        get() = category?.trim()?.let { c ->
            for (e in listOf("📖", "📺", "🎧")) if (c.startsWith(e)) return c.removePrefix(e).trim()
            c
        }

    /** The first inline `<img>` of the content (an article's lead picture; in show notes it can
     *  just as well be a sponsor banner or a tracking pixel — which is why a LISTEN row ranks it
     *  below the episode's own art and the show's cover, see ShowCovers.Keys.listenOrder). */
    val inlineImage: String? by lazy {
        IMG_SRC.find(content)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
    }

    /** Featured image for the list row: the first inline <img>, else the image enclosure. */
    val imageUrl: String? by lazy {
        inlineImage ?: enclosureImage?.takeIf { it.startsWith("http") }
    }

    /** Plain-text blurb for the list row. */
    val blurb: String by lazy { HtmlText.toPlain(content).take(180) }

    /** The YouTube video id when this entry IS a YouTube video (watch/shorts/live/youtu.be/embed
     *  URL, or a yt:video guid via an embed in the content) — what The Watch's skinned article
     *  view builds its thumbnail + tap-to-play embed around. Null for everything else. */
    val youTubeId: String?
        get() {
            val u = url
            Regex("""(?:youtube(?:-nocookie)?\.com/(?:watch\?[^#]*\bv=|shorts/|live/|embed/)|youtu\.be/)([A-Za-z0-9_\-]{6,})""")
                .find(u)?.let { return it.groupValues[1].take(16) }
            return Regex("""youtube(?:-nocookie)?\.com/embed/([A-Za-z0-9_\-]{6,})""")
                .find(content)?.groupValues?.get(1)?.take(16)
        }

    companion object {
        /** The row-thumbnail `<img src>` matcher, compiled once (it used to be built per access). */
        private val IMG_SRC = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        /**
         * The lens a Miniflux CATEGORY TITLE declares on its own — the leading media emoji of the
         * grammar shared with the iPad (📖 / 📺 / 🎧) first, then the folder's plain name. Null
         * when the name says nothing either way; entries in such a folder are still sorted by
         * [kind]'s media heuristics (an audio enclosure makes a listen item whatever the folder
         * is called).
         *
         * It lives here, beside [kind], because the drawer now asks the SERVER for a lens's
         * folders rather than sifting the loaded page — and two places deciding what counts as
         * "The Listen" is exactly how they drift apart and the lens goes quietly empty again.
         */
        fun categoryKind(title: String?): String? {
            val c = title?.trim().orEmpty()
            if (c.isBlank()) return null
            val cl = c.lowercase()
            return when {
                c.startsWith("📺") -> "watch"
                c.startsWith("🎧") -> "listen"
                c.startsWith("📖") -> "read"
                "watch" in cl || "video" in cl -> "watch"
                "listen" in cl || "podcast" in cl || "audio" in cl -> "listen"
                else -> null
            }
        }
    }
}
