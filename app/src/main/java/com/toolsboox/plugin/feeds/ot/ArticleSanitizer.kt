package com.toolsboox.plugin.feeds.ot

import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Feed HTML is somebody else's HTML. Both article readers used to interpolate an entry's
 * content raw into a JS-enabled document (JS is on for the highlight-to-annotation selection
 * bridge), with the document's baseUrl set to the entry's OWN url — and the app's shared
 * WebView cookie jar holds real logged-in sessions (see SiteWebFragment). A crafted feed item
 * carrying a <script> plus a `url` pointing at a session origin was therefore a working
 * cookie-theft kit. Two rules close it, and both live here:
 *
 *  1. Everything an entry ships renders only after [clean] — no scripts, no on* handlers,
 *     no javascript: URLs, iframes only for YouTube's own embed player.
 *  2. Article documents load on [BASE_URL], never on the entry's url — see that constant.
 */
object ArticleSanitizer {

    /**
     * The one origin article documents load under. It has to be a REAL https URL — a null
     * baseUrl leaves the document on an opaque origin, which makes YouTube embeds fail with
     * "error 150" (embedding not allowed) — but it must NOT be the entry's own url: that field
     * is feed-supplied, and rendering feed HTML on an origin the cookie jar has a session for
     * is exactly the hole this closes. Any real https origin satisfies YouTube, so all articles
     * share this sentinel; `.invalid` is reserved (RFC 2606) and can never be a site the user
     * is logged into.
     */
    const val BASE_URL = "https://article.ledger.invalid/"

    /** The only iframes allowed through: YouTube's own embed player (kept alive so the
     *  readers' rewriteYouTubeEmbeds can turn them into tappable thumbnail cards). */
    private val YOUTUBE_EMBED =
        Regex("""^https://(www\.)?(youtube\.com|youtube-nocookie\.com)/embed/""")

    /** Relaxed base (headings, tables, blockquote, pre/code, lists, img, a) plus figures and —
     *  provisionally — iframes; the YouTube-only src restriction is enforced in [clean], since
     *  a Safelist can say "https only" but not "this host only". */
    private val SAFELIST: Safelist = Safelist.relaxed()
        .addTags("figure", "figcaption", "iframe")
        .addAttributes("iframe", "src", "width", "height", "allow", "allowfullscreen", "frameborder")
        .addProtocols("iframe", "src", "https")

    /**
     * Allowlist-clean [html]. Text structure and media stay; anything executable goes.
     * Relative src/href are absolutised against [entryUrl] FIRST — the document no longer
     * loads from the entry's origin, so a relative image would otherwise resolve against
     * the [BASE_URL] sentinel and 404.
     */
    fun clean(html: String, entryUrl: String): String {
        val base = entryUrl.takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty()
        val doc = Jsoup.parse(html, base)
        if (base.isNotBlank()) {
            for (el in doc.select("[src]")) el.absUrl("src").takeIf { it.isNotBlank() }?.let { u -> el.attr("src", u) }
            for (el in doc.select("[href]")) el.absUrl("href").takeIf { it.isNotBlank() }?.let { u -> el.attr("href", u) }
        }
        val cleaned = Cleaner(SAFELIST).clean(doc)
        for (frame in cleaned.select("iframe")) {
            if (!YOUTUBE_EMBED.containsMatchIn(frame.attr("src"))) frame.remove()
        }
        // No pretty-printing: reformatting would inject whitespace inside <pre> blocks.
        cleaned.outputSettings().prettyPrint(false)
        return cleaned.body().html()
    }
}
