package com.toolsboox

import com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lane table behind ⏳ Later — the one save that never asks a question.
 *
 * A Later save derives read/watch/listen from the URL alone, so this table IS the answer the
 * user would have given, and a wrong row here is a YouTube link wearing 🔖 READ in the wrong
 * Intake quarter (the exact bug the share arm had before inferKind was wired in). Every kind of
 * link the design names — article, video, audio, podcast episode — gets a row, and the fallback
 * is pinned too: when the URL says nothing, it reads.
 */
class ShareInferKindTest {

    @Test
    fun `video hosts watch`() {
        assertEquals("watch", ShareTextParser.inferKind("https://www.youtube.com/watch?v=abc123"))
        assertEquals("watch", ShareTextParser.inferKind("https://youtu.be/abc123"))
        assertEquals("watch", ShareTextParser.inferKind("https://vimeo.com/12345"))
        assertEquals("watch", ShareTextParser.inferKind("https://www.twitch.tv/somechannel"))
    }

    @Test
    fun `podcast hosts listen`() {
        assertEquals("listen", ShareTextParser.inferKind("https://open.spotify.com/episode/xyz"))
        assertEquals("listen", ShareTextParser.inferKind("https://podcasts.apple.com/us/podcast/id123"))
        assertEquals("listen", ShareTextParser.inferKind("https://overcast.fm/+abcdef"))
        assertEquals("listen", ShareTextParser.inferKind("https://pca.st/episode/xyz"))
        assertEquals("listen", ShareTextParser.inferKind("https://soundcloud.com/artist/track"))
    }

    @Test
    fun `a direct media file says what it is louder than its host does`() {
        assertEquals("listen", ShareTextParser.inferKind("https://cdn.example.com/episodes/42.mp3"))
        assertEquals("listen", ShareTextParser.inferKind("https://example.com/audio/take.m4a"))
        assertEquals("watch", ShareTextParser.inferKind("https://example.com/clips/demo.mp4"))
    }

    @Test
    fun `an episode page on an ordinary site listens by its path`() {
        assertEquals("listen", ShareTextParser.inferKind("https://example.com/podcast/episode-5"))
        assertEquals("listen", ShareTextParser.inferKind("https://example.com/podcasts/season-2/ep-9"))
    }

    @Test
    fun `everything else reads`() {
        assertEquals("read", ShareTextParser.inferKind("https://www.nytimes.com/2026/08/22/opinion/piece.html"))
        assertEquals("read", ShareTextParser.inferKind("https://example.com/blog/post"))
        assertEquals("read", ShareTextParser.inferKind(null))
        assertEquals("read", ShareTextParser.inferKind("not a url at all"))
    }

    @Test
    fun `the query string never fools the extension check`() {
        // URI.path excludes the query — a tracking param naming an mp4 must not make an article watch.
        assertEquals("read", ShareTextParser.inferKind("https://example.com/article?video=clip.mp4"))
    }
}
