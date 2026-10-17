package com.toolsboox.plugin.michaelfilter.nw

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A Reddit post as a CARD — title, excerpt, picture, body — read from the post's own Atom feed
 * (`<post url>.rss`), because the HTML page is a wall to anything that isn't a signed-in browser
 * (Michael, 09-13: a saved Reddit link showed "nothing except a message bubble image" — reddit's
 * generic snoo balloon, the only og:image its block page offers). Share links (`/r/x/s/…`) and
 * `redd.it` shorts are followed to the post first. Null for any other site, and for a Reddit
 * page that isn't a post (a subreddit front page has a feed too, but no single body).
 */
object RedditCard {
    class Card(val title: String?, val excerpt: String?, val image: String?, val bodyHtml: String)

    private const val UA = "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0"

    fun isReddit(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it"
    }

    /** Reddit's own logo / snoo balloon as og:image — a site badge, never the post's picture. */
    fun isGenericImage(url: String): Boolean =
        url.contains("redditstatic.com") || url.contains("reddit-logo") || url.contains("snoo")

    fun fetch(client: OkHttpClient, url: String): Card? {
        if (!isReddit(url)) return null
        return runCatching {
            // Resolve the share link / short link to the post's canonical URL first.
            var post = url.substringBefore("?").substringBefore("#").trimEnd('/')
            if (!post.contains("/comments/")) {
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).head().build())
                    .execute().use { post = it.request.url.toString().substringBefore("?").trimEnd('/') }
                if (!post.contains("/comments/")) return null
            }
            val feed = client.newCall(Request.Builder().url("$post/.rss").header("User-Agent", UA).get().build())
                .execute().use { resp -> if (!resp.isSuccessful) return null; resp.body?.string().orEmpty() }
            val entry = Regex("(?s)<entry>(.*?)</entry>").find(feed)?.groupValues?.get(1) ?: return null
            fun tag(name: String): String? =
                Regex("(?s)<$name(?:\\s[^>]*)?>(.*?)</$name>").find(entry)?.groupValues?.get(1)
            val title = tag("title")?.let { unescape(it) }?.trim()
            val thumb = Regex("<media:thumbnail[^>]+url=\"([^\"]+)\"").find(entry)?.groupValues?.get(1)?.let { unescape(it) }
            var body = tag("content")?.let { unescape(it) }.orEmpty()
            // The feed wraps the body in <!-- SC_OFF --><div class="md">…</div><!-- SC_ON --> and
            // trails "submitted by /u/… [link] [comments]" — keep the body, drop the trailer.
            body = Regex("(?s)<!--\\s*SC_OFF\\s*-->(.*?)<!--\\s*SC_ON\\s*-->").find(body)?.groupValues?.get(1) ?: body
            body = body.replace(Regex("(?is)&nbsp;submitted by.*$"), "").replace(Regex("(?is)submitted by\\s+<a.*$"), "").trim()
            val image = thumb
                ?: Regex("<img[^>]+src=\"(https?://[^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: Regex("href=\"(https?://(?:i\\.redd\\.it|preview\\.redd\\.it|i\\.imgur\\.com)/[^\"]+)\"").find(body)?.groupValues?.get(1)
            val text = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("\\s+"), " ").trim()
            val author = tag("name")?.trim()
            val excerpt = when {
                text.isNotBlank() -> text.take(200)
                author != null -> "$author on Reddit"
                else -> null
            }
            Card(title, excerpt, image?.takeUnless { isGenericImage(it) }, body)
        }.getOrNull()
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&amp;", "&")
}
