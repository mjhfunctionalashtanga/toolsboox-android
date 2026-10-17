package com.toolsboox

import com.toolsboox.plugin.feeds.ot.ArticleKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE NAME PER ARTICLE on both apps (Michael 2026-10-12: "gram picks should highlight back too";
 * design critique #12). The cases live in `article-key-cases.json`, which the iOS suite reads
 * byte-for-byte too — a drift between the two cleaners fails here instead of silently splitting
 * an article's marks in two.
 */
class ArticleKeyTest {

    private val cases = JSONObject(javaClass.getResourceAsStream("/article-key-cases.json")!!.bufferedReader().readText())

    @Test
    fun `every shared cleaning case`() {
        val arr = cases.getJSONArray("clean")
        assertTrue(arr.length() >= 30)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            assertEquals("case $i: ${c.getString("in")}", c.getString("out"), ArticleKey.clean(c.getString("in")))
        }
    }

    @Test
    fun `the key is the first 16 hex of SHA-256 of the cleaned URL - and cleaning is idempotent`() {
        val arr = cases.getJSONArray("keys")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            assertEquals(c.getString("key"), ArticleKey.of(c.getString("in")))
        }
        val clean = cases.getJSONArray("clean")
        for (i in 0 until clean.length()) {
            val once = ArticleKey.clean(clean.getJSONObject(i).getString("in"))
            assertEquals(once, ArticleKey.clean(once))
        }
        // The same article under its feed's tracking link and its share link is one key.
        assertEquals(ArticleKey.of("http://www.Example.com/a/b/?utm_source=rss#top"), ArticleKey.of("https://example.com/a/b"))
        // No dot in a key (the book store's key(forFilename:) strips extensions) and a hex bucket.
        val k = ArticleKey.of("https://example.com/a.html")
        assertFalse(k.contains('.'))
        assertTrue(ArticleKey.bucket(k) in "0123456789abcdef")
    }

    @Test
    fun `the loose fallback forgives encoding and scheme, never a different page`() {
        assertTrue(ArticleKey.looseEquals("https://example.com/caf%C3%A9", "http://example.com/café"))
        assertFalse(ArticleKey.looseEquals("https://example.com/a", "https://example.com/b"))
        assertFalse(ArticleKey.looseEquals("", "https://example.com/a"))
    }
}
