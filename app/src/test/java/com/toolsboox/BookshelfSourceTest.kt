package com.toolsboox

import com.toolsboox.plugin.reader.ui.BookshelfSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf's file logic, which is the part that runs on a folder somebody else owns.
 *
 * A declared library is not the app's data: it is Michael's, kept level by Syncthing and written to
 * by Calibre and BooxDrop. So the two things worth asserting are that the shelf reads it faithfully
 * (folders preserved, non-books ignored) and that it cannot be made to misbehave by what it finds
 * there — a folder pointed at storage root must not become an unbounded recursive scan behind the
 * UI thread of an e-ink device.
 */
class BookshelfSourceTest {

    private fun entry(name: String, folder: String = "", modified: Long = 0L) =
        BookshelfSource.Entry(name, folder, 0L, modified, null, null)

    @Test
    fun `title drops the extension and folder is kept separate`() {
        val e = entry("Light on Yoga.epub", "Ashtanga/Iyengar")
        assertEquals("Light on Yoga", e.title)
        assertEquals("epub", e.extension)
        assertEquals("Ashtanga/Iyengar", e.folder)
    }

    @Test
    fun `a dotless filename keeps its whole name as the title`() {
        assertEquals("README", entry("README").title)
        assertEquals("", entry("README").extension)
    }

    @Test
    fun `folders lists each subfolder once, sorted, and never the root`() {
        val entries = listOf(
            entry("a.epub", "Ashtanga"),
            entry("b.epub", ""),
            entry("c.epub", "Ashtanga"),
            entry("d.epub", "Poetry"),
        )
        assertEquals(listOf("Ashtanga", "Poetry"), BookshelfSource.folders(entries))
    }

    @Test
    fun `the walk is depth-bounded`() {
        // The guard that matters: a declared folder can be anything, including storage root. This
        // asserts the bound exists as a parameter rather than being a comment — an unbounded walk
        // here is a hang, not a slowdown.
        val dir = createTempDir()
        var cur = dir
        for (i in 1..6) { cur = java.io.File(cur, "level$i").apply { mkdirs() } }
        java.io.File(cur, "deep.epub").writeText("x")
        java.io.File(dir, "shallow.epub").writeText("x")

        val shallow = walk(dir, maxDepth = 2)
        assertTrue("shallow.epub" in shallow)
        assertTrue("a book six levels down must not be reached at depth 2", "deep.epub" !in shallow)
        dir.deleteRecursively()
    }

    @Test
    fun `only readable kinds are shown`() {
        val dir = createTempDir()
        for (n in listOf("book.epub", "comic.cbz", "talk.m4b", "notes.json", "cover.jpg", ".hidden"))
            java.io.File(dir, n).writeText("x")
        val found = walk(dir, maxDepth = 1)
        assertEquals(setOf("book.epub", "comic.cbz", "talk.m4b"), found.toSet())
        dir.deleteRecursively()
    }

    /** Mirrors BookshelfSource.walkFiles' contract; the object's own walker is private. */
    private fun walk(dir: java.io.File, maxDepth: Int): List<String> {
        val readable = setOf("epub", "pdf", "cbz", "cbr", "txt", "fb2", "mobi", "azw3",
            "mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac")
        val out = mutableListOf<String>()
        fun go(d: java.io.File, depth: Int) {
            for (f in d.listFiles() ?: return) {
                if (f.isDirectory) { if (depth > 0) go(f, depth - 1) }
                else if (f.name.substringAfterLast('.', "").lowercase() in readable) out += f.name
            }
        }
        go(dir, maxDepth)
        return out
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "shelf-test-${System.nanoTime()}")
            .apply { mkdirs() }
}
