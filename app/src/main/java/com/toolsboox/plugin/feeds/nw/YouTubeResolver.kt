package com.toolsboox.plugin.feeds.nw

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Any pasted YouTube link → the channel behind it. YouTube still publishes a real RSS feed per
 * channel (feeds/videos.xml?channel_id=UC…), it just never shows anyone the channel id — so
 * "Add feed by URL" accepts the links people actually copy (a video, a @handle, /channel/, the
 * old /user/ and /c/ forms) and digs the id out of the page HTML. Blocking; call on IO.
 */
object YouTubeResolver {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Channel(val channelId: String, val title: String) {
        /** The channel's honest-to-goodness RSS feed. */
        val rssUrl: String get() = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
    }

    fun isYouTube(url: String): Boolean {
        val u = url.lowercase()
        return "youtube.com/" in u || "youtu.be/" in u
    }

    /** A link that IS already the feed (someone pasted the videos.xml) needs no resolving. */
    fun isYouTubeFeed(url: String): Boolean = "youtube.com/feeds/videos.xml" in url.lowercase()

    /**
     * Resolve the channel id + title for any YouTube URL. A /channel/UC… link carries its id in
     * plain sight; everything else (video, @handle, /user/, /c/, shorts) means fetching the page
     * and reading the id out of the embedded metadata. Null when nothing channel-shaped emerges
     * — bad link, consent wall, dead network — never a crash.
     */
    fun resolve(url: String): Channel? = runCatching {
        val clean = url.trim()
        // The id might already be in the URL — but the TITLE still comes from the page, so we
        // fetch either way and just have a guaranteed fallback id in hand.
        val directId = Regex("""youtube\.com/channel/(UC[0-9A-Za-z_\-]{22})""").find(clean)?.groupValues?.get(1)
        val html = fetchPage(clean)
        val id = directId ?: html?.let { channelIdFrom(it) } ?: return null
        val title = html?.let { titleFrom(it) } ?: "YouTube channel"
        Channel(id, title)
    }.getOrNull()

    private fun fetchPage(url: String): String? = runCatching {
        val req = Request.Builder().url(url)
            // A desktop UA + the pre-consent cookie keeps YouTube serving the plain HTML with
            // the channelId metadata instead of a consent interstitial.
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .header("Cookie", "CONSENT=YES+1")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            // The metadata sits in the first slice of the page; don't hold 2MB of watch page.
            else r.body?.byteStream()?.readBytes()?.decodeToString()?.take(1_500_000)
        }
    }.getOrNull()

    /** The channel id, wherever this page flavor hides it. */
    private fun channelIdFrom(html: String): String? =
        Regex("""itemprop="(?:channelId|identifier)"\s+content="(UC[0-9A-Za-z_\-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""channelId"\s*:\s*"(UC[0-9A-Za-z_\-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""externalId"\s*:\s*"(UC[0-9A-Za-z_\-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""youtube\.com/channel/(UC[0-9A-Za-z_\-]{22})""").find(html)?.groupValues?.get(1)

    /** The channel/author name — og-tag first, then the player metadata, then the <title>. */
    private fun titleFrom(html: String): String? = (
        Regex(""""author"\s*:\s*"([^"]{1,120})"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""ownerChannelName"\s*:\s*"([^"]{1,120})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta property="og:title" content="([^"]{1,160})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]{1,160})</title>""").find(html)?.groupValues?.get(1)
                ?.removeSuffix(" - YouTube")
        )?.replace("\\u0026", "&")?.replace("&amp;", "&")?.trim()?.ifBlank { null }
}
