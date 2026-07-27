package com.toolsboox.plugin.feeds.nw

import java.net.HttpURLConnection
import java.net.URL

/**
 * Personal-use paywall bypass (Bypass Paywall Clean spirit) — the Kotlin twin of the iOS
 * `PaywallBypass`. When Miniflux's readability parse comes back a paywall stub, refetch the article
 * the way a search crawler or an archive sees it: full text, no overlay. Per-site rules pick the
 * strategy chain; everything else gets the default (crawler user-agent, then archive.today). It runs
 * only in the readability prefetch / reader path (never bulk page loads), a fallback for articles
 * you'd read anyway — it routes around publisher terms, so it's a quiet personal tool.
 *
 * STARTER ruleset (mirrors iOS). The full upstream BPC ruleset (hundreds of sites) ingests here
 * later as JSON rather than hand-copied.
 */
object PaywallBypass {
    private const val CRAWLER_UA =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

    enum class Strategy { CRAWLER_UA, ARCHIVE_TODAY }
    private data class Rule(val domainSuffix: String, val strategies: List<Strategy>)

    private val rules = listOf(
        Rule("nytimes.com", listOf(Strategy.CRAWLER_UA, Strategy.ARCHIVE_TODAY)),
        Rule("wsj.com", listOf(Strategy.ARCHIVE_TODAY)),
        Rule("washingtonpost.com", listOf(Strategy.CRAWLER_UA, Strategy.ARCHIVE_TODAY)),
        Rule("economist.com", listOf(Strategy.ARCHIVE_TODAY)),
        Rule("ft.com", listOf(Strategy.ARCHIVE_TODAY)),
        Rule("theatlantic.com", listOf(Strategy.CRAWLER_UA, Strategy.ARCHIVE_TODAY)),
        Rule("newyorker.com", listOf(Strategy.CRAWLER_UA, Strategy.ARCHIVE_TODAY)),
        Rule("wired.com", listOf(Strategy.CRAWLER_UA, Strategy.ARCHIVE_TODAY)),
        Rule("bloomberg.com", listOf(Strategy.ARCHIVE_TODAY)),
        Rule("medium.com", listOf(Strategy.CRAWLER_UA)),
    )
    private val defaultChain = listOf(Strategy.CRAWLER_UA, Strategy.ARCHIVE_TODAY)

    /** A parse that's null or shorter than a real article's prose reads as a paywall stub. */
    fun looksTruncated(html: String?): Boolean = html == null || plainLength(html) < 1200

    /** Best readable HTML for [urlString] via the site's strategy chain, or null. Blocking — call
     *  off the main thread (it's used inside the background prefetch loop). */
    fun readable(urlString: String): String? {
        val host = runCatching { URL(urlString).host?.lowercase() }.getOrNull() ?: return null
        val strategies = rules.firstOrNull { host.endsWith(it.domainSuffix) }?.strategies ?: defaultChain
        for (s in strategies) {
            val html = fetch(urlString, s) ?: continue
            val body = extractArticle(html)
            if (body != null && plainLength(body) > 500) return body
        }
        return null
    }

    private fun fetch(urlString: String, s: Strategy): String? = runCatching {
        val target = when (s) {
            Strategy.CRAWLER_UA -> urlString
            Strategy.ARCHIVE_TODAY -> "https://archive.ph/newest/$urlString"
        }
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
            when (s) {
                Strategy.CRAWLER_UA -> {
                    setRequestProperty("User-Agent", CRAWLER_UA)          // Googlebot sees full text
                    setRequestProperty("Referer", "https://www.google.com/")
                }
                Strategy.ARCHIVE_TODAY ->
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
            }
        }
        try {
            if (conn.responseCode !in 200..399) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Light readability heuristic: biggest <article>, then <main>, else the joined <p> blocks. */
    fun extractArticle(html: String): String? {
        firstBlock(html, "article")?.let { return it }
        firstBlock(html, "main")?.let { return it }
        val ps = Regex("(?is)<p[^>]*>.*?</p>").findAll(html).map { it.value }.filter { it.length > 40 }.toList()
        val joined = ps.joinToString("\n")
        return if (plainLength(joined) > 500) joined else null
    }

    private fun firstBlock(html: String, tag: String): String? =
        Regex("(?is)<$tag[^>]*>.*?</$tag>").findAll(html).map { it.value }.maxByOrNull { it.length }

    private fun plainLength(html: String): Int =
        html.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ").trim().length
}
