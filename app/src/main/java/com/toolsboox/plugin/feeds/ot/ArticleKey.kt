package com.toolsboox.plugin.feeds.ot

import java.security.MessageDigest

/**
 * ONE NAME FOR AN ARTICLE, on every device and in both apps — the key its highlights are filed
 * under ([ArticleNoteStore]) and the key its gram picks are found by ([com.toolsboox.plugin.calendar.ot.ArticlePickIndex]).
 * Michael 2026-10-12: "gram picks should highlight back too" — a pick made from the share card on
 * the iPhone and a highlight made in the B6's Reader view must name the same article.
 *
 * [clean] is a plain string walk, written so the iOS twin (`ArticleKey.swift`) can follow it line
 * for line — never java.net.URI or URLComponents, whose percent-encoding rules differ (design
 * critique #12; the near-miss BookMarksStore.swift:91-95 warns about). Both test suites read ONE
 * fixture, `article-key-cases.json`, so a drift fails a test instead of silently splitting marks.
 *
 * The rules: https; no user info; host lowercased, IDN to punycode, no "www.", no default port,
 * no trailing dot; percent escapes of unreserved characters decoded (%7E → ~) and the rest
 * upper-cased; no trailing slash; `+` in the query is a space (%20); tracker parameters gone
 * (utm_*, fbclid, gclid, dclid, msclkid, mc_cid, mc_eid, _hsenc, _hsmi, ref, ref_src, igshid,
 * yclid, mkt_tok); the rest sorted; an empty query and the fragment dropped. Anything that is not
 * http(s) comes back trimmed and otherwise untouched.
 *
 * [of] is `"a-"` + the first 16 hex characters of SHA-256 of the cleaned URL; [bucket] its first
 * hex character, which splits the mark file sixteen ways (critique #3).
 */
object ArticleKey {

    private val TRACKERS = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "_hsenc", "_hsmi",
        "ref", "ref_src", "igshid", "yclid", "mkt_tok"
    )

    fun of(url: String): String = "a-" + sha256(clean(url)).take(16)

    /** The bucket a key's marks live in — one hex character. */
    fun bucket(key: String): Char = key.removePrefix("a-").firstOrNull()?.lowercaseChar()
        ?.takeIf { it in '0'..'9' || it in 'a'..'f' } ?: '0'

    fun clean(raw: String): String {
        val s = raw.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return s
        val scheme = s.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return s
        var rest = s.substring(schemeEnd + 3)
        rest = rest.substringBefore('#')
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        var host = rest.substring(0, authEnd).substringAfterLast('@')
        val pathAndQuery = rest.substring(authEnd)
        var path = pathAndQuery.substringBefore('?')
        val query = if ('?' in pathAndQuery) pathAndQuery.substringAfter('?') else ""

        host = host.lowercase()
        if (host.endsWith(":443") || host.endsWith(":80")) host = host.substringBeforeLast(':')
        host = host.trimEnd('.')
        host = runCatching { java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()
        if (host.startsWith("www.")) host = host.removePrefix("www.")

        path = normalizeEscapes(path)
        while (path.endsWith("/")) path = path.dropLast(1)

        val params = query.split('&')
            .filter { it.isNotEmpty() }
            .map { normalizeEscapes(it.replace("+", "%20")) }
            .filter { p ->
                val name = p.substringBefore('=').lowercase()
                !(name.startsWith("utm_") || name in TRACKERS)
            }
            .sorted()
        return "https://" + host + path + (if (params.isEmpty()) "" else "?" + params.joinToString("&"))
    }

    /** %7E → ~ (any escape of an RFC 3986 unreserved character is decoded); every other escape's
     *  hex upper-cased. A malformed % is left as it stands. */
    internal fun normalizeEscapes(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                val v = s.substring(i + 1, i + 3).toInt(16)
                val ch = v.toChar()
                if (v < 0x80 && (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~')) out.append(ch)
                else out.append('%').append(s.substring(i + 1, i + 3).uppercase())
                i += 3
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * The looser comparison the store falls back to when a key finds nothing (critique #12): the
     * cleaned URLs equal once both are decoded and the scheme is ignored. Never a key — only the
     * last chance before a mark is shown as missing.
     */
    fun looseEquals(a: String, b: String): Boolean {
        fun loose(u: String) = runCatching { java.net.URLDecoder.decode(clean(u).removePrefix("https://"), "UTF-8") }
            .getOrDefault(clean(u)).lowercase().trimEnd('/')
        return a.isNotBlank() && b.isNotBlank() && loose(a) == loose(b)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
