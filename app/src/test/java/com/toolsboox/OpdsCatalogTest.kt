package com.toolsboox

import com.toolsboox.plugin.reader.ui.OpdsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OPDS parser, against the shapes catalogs actually emit.
 *
 * Worth testing properly because the input is someone else's XML. Calibre-Web, Standard Ebooks and
 * Project Gutenberg all describe the same two ideas — "browse here" and "download this" — with
 * different namespaces, different rel spellings and different opinions about whether a summary is
 * plain text. A parser that only handles the one server Michael happens to run would break the day
 * he pointed it at another, silently, by showing an empty catalog.
 */
class OpdsCatalogTest {

    private fun parse(xml: String) = OpdsCatalog.parse(xml.byteInputStream())

    private val calibreWeb = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <title>Calibre-Web</title>
          <entry>
            <title>Hot Books</title>
            <id>urn:uuid:hot</id>
            <link rel="subsection" href="/opds/hot" type="application/atom+xml;profile=opds-catalog"/>
          </entry>
          <entry>
            <title>Light on Yoga</title>
            <id>urn:uuid:1</id>
            <author><name>B.K.S. Iyengar</name></author>
            <summary>&lt;p&gt;The &lt;b&gt;standard&lt;/b&gt; reference.&lt;/p&gt;</summary>
            <link rel="http://opds-spec.org/image/thumbnail" href="/opds/cover/1" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/1/epub" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/1/pdf" type="application/pdf"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `the feed title is the outer one, not an entry's`() {
        assertEquals("Calibre-Web", parse(calibreWeb).title)
    }

    @Test
    fun `a navigation entry is not a book and offers a href to drill into`() {
        val nav = parse(calibreWeb).entries.first { it.title == "Hot Books" }
        assertFalse(nav.isBook)
        assertEquals("/opds/hot", nav.navHref)
        assertNull(nav.download)
    }

    @Test
    fun `a book entry prefers EPUB over the other acquisition links`() {
        val book = parse(calibreWeb).entries.first { it.title == "Light on Yoga" }
        assertTrue(book.isBook)
        assertEquals("/opds/download/1/epub", book.download?.href)
        assertEquals("B.K.S. Iyengar", book.author)
    }

    @Test
    fun `HTML in the summary is reduced to one plain line`() {
        val book = parse(calibreWeb).entries.first { it.title == "Light on Yoga" }
        assertEquals("The standard reference.", book.summary)
    }

    @Test
    fun `the filename carries author and title and a real extension`() {
        val book = parse(calibreWeb).entries.first { it.title == "Light on Yoga" }
        assertEquals("B.K.S. Iyengar - Light on Yoga.epub", book.filename())
    }

    @Test
    fun `filenames drop characters no filesystem in the mesh will take`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>x</title>
              <entry><title>What/Now: a book?</title><id>2</id>
                <link rel="http://opds-spec.org/acquisition" href="/d/2" type="application/epub+zip"/>
              </entry>
            </feed>
        """.trimIndent()
        val name = parse(xml).entries.single().filename()
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('?'))
        assertTrue(name.endsWith(".epub"))
    }

    @Test
    fun `an acquisition link with no usable type still yields an extension from the href`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>x</title>
              <entry><title>Comic</title><id>3</id>
                <link rel="http://opds-spec.org/acquisition" href="/files/comic.cbz"/>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals("Comic.cbz", parse(xml).entries.single().filename())
    }

    @Test
    fun `an entry with no title is dropped rather than shown as blank`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>x</title>
              <entry><id>4</id></entry>
              <entry><title>Real</title><id>5</id></entry>
            </feed>
        """.trimIndent()
        assertEquals(listOf("Real"), parse(xml).entries.map { it.title })
    }

    @Test
    fun `relative hrefs resolve against the catalog root`() {
        assertEquals(
            "https://books.example.com/opds/hot",
            OpdsCatalog.resolve("https://books.example.com/opds/", "hot")
        )
        assertEquals(
            "https://books.example.com/opds/hot",
            OpdsCatalog.resolve("https://books.example.com/opds/root.xml", "/opds/hot")
        )
        // An absolute href is already an answer and must survive untouched.
        assertEquals(
            "https://other.example.com/f.epub",
            OpdsCatalog.resolve("https://books.example.com/opds/", "https://other.example.com/f.epub")
        )
    }

    @Test
    fun `an empty feed parses to an empty list rather than throwing`() {
        // A catalog with nothing in it is a normal answer — an empty shelf, not a failure — and
        // must not surface as an error the user has to interpret.
        val feed = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Empty</title></feed>""")
        assertEquals("Empty", feed.title)
        assertTrue(feed.entries.isEmpty())
    }
}
