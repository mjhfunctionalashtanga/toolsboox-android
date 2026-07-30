package com.toolsboox

import com.toolsboox.plugin.calendar.nw.UltrabridgeWebDavService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of WebDAV PROPFIND multistatus bodies, which is what the Depth-1 tree walk stands on.
 *
 * These exist because the day-JSON mirror spent three days doing nothing: it asked for
 * `Depth: infinity`, stock Apache (`DavDepthInfinity Off`) answered 403, the sync correctly refused
 * to read "listing failed" as "server is empty", and aborted every pass. The fallback that replaced
 * it has to get two things right that the old single-shot parse never had to think about — telling a
 * sub-collection apart from a file, and never handing back the collection it was just asked about
 * (which would recurse forever). Both are asserted here against real bytes from dav.mjh.yoga.
 */
class PropfindListingTest {

    private val svc = UltrabridgeWebDavService("https://dav.mjh.yoga", "u", "p")

    /** A Depth-1 body as Apache actually emits it: lp1-prefixed props, D-prefixed hrefs. */
    private fun response(href: String, lastMod: String = "Sun, 26 Jul 2026 03:50:33 GMT") = """
        <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
        <D:href>$href</D:href>
        <D:propstat><D:prop>
        <lp1:getlastmodified>$lastMod</lp1:getlastmodified>
        </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response>
    """.trimIndent()

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">""" + responses.joinToString("") + "</D:multistatus>"

    @Test
    fun `depth-1 listing separates child collections from files and drops the collection itself`() {
        val xml = multistatus(
            response("/calendar/"),            // the collection we asked about
            response("/calendar/2026/"),
            response("/calendar/2025/"),
            response("/calendar/stray.json")
        )
        val listing = svc.parseListing(xml, "calendar")

        // The self-entry must NOT come back as a child, or the walk never terminates.
        assertEquals(listOf("calendar/2026", "calendar/2025"), listing.collections)
        assertEquals(listOf("calendar/stray.json"), listing.files.map { it.remotePath })
    }

    @Test
    fun `file paths stay relative to the base url and carry the server mtime`() {
        val xml = multistatus(response("/calendar/2026/07/day-2026-07-29-v2.json"))
        val listing = svc.parseListing(xml, "calendar/2026/07")

        val entry = listing.files.single()
        assertEquals("calendar/2026/07/day-2026-07-29-v2.json", entry.remotePath)
        // Sun, 26 Jul 2026 03:50:33 GMT. The format string names the zone, so this is the same
        // number wherever the test runs — the mtime is compared against a server-clock watermark.
        assertEquals(1785037833000L, entry.lastModified)
    }

    @Test
    fun `percent-encoded hrefs decode to plain paths`() {
        val xml = multistatus(response("/calendar/2026/07/day%2D2026%2D07%2D29%2Dv2.json"))
        val listing = svc.parseListing(xml, "calendar/2026/07")
        assertEquals("calendar/2026/07/day-2026-07-29-v2.json", listing.files.single().remotePath)
    }

    /**
     * If a server DOES allow Depth: infinity, the same parse runs over a deep body. Files at any
     * depth must be collected; grandchild collections must not be offered as children to recurse
     * into (the walk would revisit them, and the fast path does not use them at all).
     */
    @Test
    fun `deep listing collects nested files and offers only direct child collections`() {
        val xml = multistatus(
            response("/calendar/"),
            response("/calendar/2026/"),
            response("/calendar/2026/07/"),
            response("/calendar/2026/07/day-2026-07-29-v2.json")
        )
        val listing = svc.parseListing(xml, "calendar")

        assertEquals(listOf("calendar/2026"), listing.collections)
        assertEquals(listOf("calendar/2026/07/day-2026-07-29-v2.json"), listing.files.map { it.remotePath })
    }

    @Test
    fun `hrefs outside the requested collection are ignored`() {
        val xml = multistatus(response("/attachments/blob.mov"), response("/calendar/keep.json"))
        val listing = svc.parseListing(xml, "calendar")
        assertEquals(listOf("calendar/keep.json"), listing.files.map { it.remotePath })
    }

    @Test
    fun `a missing getlastmodified yields an unknown mtime rather than a wrong one`() {
        // Zero matters: sync treats an unknown remote mtime as "changed" and merges instead of
        // trusting a skip, so a parse miss can never silently drop a peer's edits.
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""" +
            "<D:response><D:href>/calendar/x.json</D:href></D:response></D:multistatus>"
        val listing = svc.parseListing(xml, "calendar")
        assertEquals(0L, listing.files.single().lastModified)
        assertTrue(listing.collections.isEmpty())
    }
}
