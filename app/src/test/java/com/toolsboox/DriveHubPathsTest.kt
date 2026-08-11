package com.toolsboox

import com.toolsboox.plugin.calendar.nw.DriveHubPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Drive backend — the only half a unit test can honestly hold (everything
 * else in [com.toolsboox.plugin.calendar.nw.DriveHubTransport] is a network call wearing a method).
 *
 * Two translations happen here and both have failure modes that would be silent in production:
 * a slash-path split wrongly puts a book in the wrong folder forever, and a name dropped unescaped
 * into Drive's query language makes the lookup miss (or match something else) precisely for the
 * books with apostrophes in their titles — which, for a yoga library, is not a rare case
 * (Iyengar's, Patañjali's, "Krishnamacharya's legacy…").
 */
class DriveHubPathsTest {

    @Test
    fun `a nested path splits into folder segments and a file name`() {
        val s = DriveHubPaths.split("books/Ashtanga/Iyengar/Light on Yoga.epub")
        assertEquals(listOf("books", "Ashtanga", "Iyengar"), s.dirSegments)
        assertEquals("Light on Yoga.epub", s.name)
    }

    @Test
    fun `a root-level sidecar has no folders to walk`() {
        val s = DriveHubPaths.split("listen-state.json")
        assertTrue(s.dirSegments.isEmpty())
        assertEquals("listen-state.json", s.name)
    }

    @Test
    fun `leading and doubled slashes normalize away, matching the WebDAV side`() {
        // The WebDAV service trims and joins; a path that resolved differently per backend would
        // be a seam leak — the same string must mean the same file everywhere.
        val s = DriveHubPaths.split("/books//Ashtanga/book.pdf")
        assertEquals(listOf("books", "Ashtanga"), s.dirSegments)
        assertEquals("book.pdf", s.name)
    }

    @Test
    fun `an empty path is an empty name, not a crash`() {
        val s = DriveHubPaths.split("")
        assertTrue(s.dirSegments.isEmpty())
        assertEquals("", s.name)
    }

    @Test
    fun `apostrophes are escaped for the query language`() {
        assertEquals("O\\'Reilly", DriveHubPaths.escapeQuery("O'Reilly"))
    }

    @Test
    fun `backslash escapes before quote, or the escape itself gets mangled`() {
        // Escaping ' first would turn a literal backslash-quote in a name into a broken sequence:
        // the backslash pass would then double the escape we just wrote.
        assertEquals("a\\\\b\\'c", DriveHubPaths.escapeQuery("a\\b'c"))
    }

    @Test
    fun `the file query binds name and parent and always excludes trash and folders`() {
        val q = DriveHubPaths.fileQuery("Light on Yoga.epub", "folder123")
        assertTrue(q.contains("name='Light on Yoga.epub'"))
        assertTrue(q.contains("'folder123' in parents"))
        assertTrue("trashed files are dead to sync — a user who trashed a book said something",
            q.contains("trashed=false"))
        assertTrue(q.contains("mimeType!='application/vnd.google-apps.folder'"))
    }

    @Test
    fun `the folder query is the same shape with the mimeType flipped`() {
        val q = DriveHubPaths.folderQuery("books", "root")
        assertTrue(q.contains("name='books'"))
        assertTrue(q.contains("'root' in parents"))
        assertTrue(q.contains("mimeType='application/vnd.google-apps.folder'"))
        assertTrue(q.contains("trashed=false"))
    }

    @Test
    fun `a name with an apostrophe rides escaped inside the query`() {
        val q = DriveHubPaths.fileQuery("Patañjali's sutras.pdf", "f1")
        assertTrue(q.contains("name='Patañjali\\'s sutras.pdf'"))
    }
}
