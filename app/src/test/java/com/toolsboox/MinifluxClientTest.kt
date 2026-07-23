package com.toolsboox

import com.toolsboox.plugin.feeds.nw.MinifluxClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Miniflux entries parsing that drives the in-app Feed Ledger. */
class MinifluxClientTest {

    private val sample = """
        {
          "total": 2,
          "entries": [
            {
              "id": 101, "feed_id": 7, "status": "unread", "title": "On Practice",
              "url": "https://ashtanga.tech/on-practice", "author": "MJH",
              "published_at": "2026-07-13T08:00:00Z",
              "content": "<p>Consistency <b>over</b> intensity.</p>",
              "starred": false, "feed": { "id": 7, "title": "Ashtanga Tech" }
            },
            {
              "id": 102, "feed_id": 9, "status": "unread", "title": "Breath",
              "url": "https://example.com/breath", "author": null,
              "published_at": "2026-07-12T08:00:00Z",
              "content": "<p>Sthira sukham.</p>", "starred": true,
              "feed": { "id": 9, "title": "Notes" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parses_entries_and_derives_blurb() {
        val entries = MinifluxClient().parseEntries(sample)
        assertEquals(2, entries.size)

        val first = entries[0]
        assertEquals(101L, first.id)
        assertEquals("On Practice", first.title)
        assertEquals("Ashtanga Tech", first.feedTitle)
        assertEquals("MJH", first.author)
        assertEquals(false, first.starred)
        // Blurb strips HTML.
        assertTrue(first.blurb.contains("Consistency over intensity"))
        assertTrue(!first.blurb.contains("<"))

        val second = entries[1]
        assertEquals(true, second.starred)
        assertEquals(null, second.author)
    }
}
