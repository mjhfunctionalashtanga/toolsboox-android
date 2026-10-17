package com.toolsboox

import com.toolsboox.plugin.feeds.nw.ShowCovers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The show-cover index's pure half (Listen artwork, 10-12): how a show is keyed, when an iTunes
 * result may vouch for a feed, and the bound that keeps the index small.
 */
class ShowCoversKeysTest {

    private val k = ShowCovers.Keys

    @Test
    fun `a feed is the same feed over http or https, with or without its trailing slash`() {
        assertEquals("u:feeds.example.com/show", k.url("https://feeds.example.com/show/"))
        assertEquals(k.url("http://Feeds.Example.com/show"), k.url("https://feeds.example.com/show/"))
        assertTrue(k.sameFeed("https://feeds.example.com/show", "http://feeds.example.com/show/"))
    }

    @Test
    fun `only a web address keys a feed`() {
        assertNull(k.url(null))
        assertNull(k.url(""))
        assertNull(k.url("/api/items/abc/cover"))
        assertNull(k.url("file:///sdcard/x.xml"))
        assertFalse(k.sameFeed(null, null))
        assertFalse(k.sameFeed("", ""))
    }

    @Test
    fun `a directory result for another feed never vouches for this one`() {
        assertFalse(k.sameFeed("https://feeds.example.com/other", "https://feeds.example.com/show"))
    }

    @Test
    fun `a show's name keys case- and spacing-blind`() {
        assertEquals("t:the daily", k.title("  The   Daily "))
        assertEquals(k.title("THE DAILY"), k.title("the daily"))
        assertNull(k.title("   "))
        assertNull(k.title(null))
    }

    @Test
    fun `iTunes art is asked for at 600px`() {
        assertEquals("https://is1.mzstatic.com/a/600x600bb.jpg", k.upsize("https://is1.mzstatic.com/a/100x100bb.jpg"))
        assertEquals("", k.upsize(null))
    }

    private val itunes = """
        {"resultCount":2,"results":[
          {"collectionName":"The Show","feedUrl":"https://other.com/feed","artworkUrl600":"https://img/other600.jpg"},
          {"collectionName":"The Show (Clips)","feedUrl":"https://feeds.ex.com/show/","artworkUrl100":"https://img/x/100x100bb.jpg"}
        ]}
    """.trimIndent()

    @Test
    fun `an iTunes answer is taken for the same feed, and only that feed`() {
        assertEquals("https://img/x/600x600bb.jpg", k.itunesArtwork(itunes, "http://feeds.ex.com/show", "The Show"))
        // A known feed never falls back to a name match — the same-named show is someone else's.
        assertNull(k.itunesArtwork(itunes, "https://nowhere/feed", "The Show"))
    }

    @Test
    fun `a show known only by name takes the exact name, never a near miss (the iPhone's rule)`() {
        assertEquals("https://img/other600.jpg", k.itunesArtwork(itunes, null, "  the   show "))
        assertNull(k.itunesArtwork(itunes, null, "The Shows"))
        assertNull(k.itunesArtwork(itunes, null, null))
        assertNull(k.itunesArtwork("not json", null, "The Show"))
    }

    @Test
    fun `a listen row tries the episode's art, then the show's cover, then a notes picture`() {
        assertEquals(listOf("https://ep.jpg", "https://cover.jpg", "https://notes.jpg"),
            k.listenOrder("https://ep.jpg", "https://cover.jpg", "https://notes.jpg"))
        // No art of its own: the cover leads, the notes picture (a sponsor banner as often as not) last.
        assertEquals(listOf("https://cover.jpg", "https://notes.jpg"),
            k.listenOrder(null, "https://cover.jpg", "https://notes.jpg"))
        // An audiobook's cover PATH is its own art; repeats and non-web addresses drop out.
        assertEquals(listOf("/api/items/1/cover", "https://cover.jpg"),
            k.listenOrder("/api/items/1/cover", "https://cover.jpg", "https://cover.jpg"))
        assertEquals(emptyList<String>(), k.listenOrder("", null, "data:image/png;base64,AA"))
    }

    @Test
    fun `an unsigned Audiobookshelf address on this server is keyed by its path`() {
        val abs = com.toolsboox.plugin.reader.ui.Audiobookshelf
        // The iPhone may know the server as http where this device knows https, or in other case.
        assertEquals("/api/items/li_1/cover", abs.sameServerPath("http://ABS.example.com/api/items/li_1/cover", "https://abs.example.com"))
        assertEquals("/api/items/li_1/cover", abs.sameServerPath("https://ex.com/abs/api/items/li_1/cover", "https://ex.com/abs/"))
        // Another server, a signed address, or a path that is not an item's: left alone.
        assertNull(abs.sameServerPath("https://elsewhere.com/api/items/li_1/cover", "https://abs.example.com"))
        assertNull(abs.sameServerPath("https://abs.example.com/api/items/li_1/cover?token=x", "https://abs.example.com"))
        assertNull(abs.sameServerPath("https://abs.example.com/feed/cover.jpg", "https://abs.example.com"))
        assertNull(abs.sameServerPath("https://abs.example.com.evil.net/api/items/li_1/cover", "https://abs.example.com"))
    }

    @Test
    fun `the index keeps the newest and drops the oldest past its bound`() {
        val m = LinkedHashMap<String, String>()
        k.putBounded(m, "a", "1", 2)
        k.putBounded(m, "b", "2", 2)
        k.putBounded(m, "a", "1b", 2)   // a re-learned show moves to the newest end
        k.putBounded(m, "c", "3", 2)
        assertEquals(listOf("a", "c"), m.keys.toList())
        assertEquals("1b", m["a"])
    }
}
