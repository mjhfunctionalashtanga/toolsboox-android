package com.toolsboox

import com.toolsboox.plugin.reader.ui.BookshelfSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the shelf CALLS a book, pinned against real filenames off Michael's Palma.
 *
 * A shelf built by pointing at folders shows whatever the downloader named the file, and
 * downloaders name files for themselves. These cases are the actual strings on his device — the
 * mihon hash, the exporter id, the colon-as-underscore — plus the ones that must NOT be touched,
 * which is the half a cleanup like this usually gets wrong.
 */
class ShelfTitleTest {

    private fun title(name: String) =
        BookshelfSource.Entry(name, "", 0, 0, null, null).title

    @Test
    fun `mihon's hash suffix comes off`() {
        assertEquals("(2024 – Part 1)", title("(2024 – Part 1)_58d1c1.cbz"))
        assertEquals("(2024 – Part 7)", title("(2024 – Part 7)_54b37f.cbz"))
    }

    @Test
    fun `an exporter's numeric id comes off, and a colon comes back`() {
        assertEquals(
            "Fluke: Chance, Chaos, and Why - Brian Klaas",
            title("Fluke_ Chance, Chaos, and Why  - Brian Klaas_1335.epub")
        )
    }

    @Test
    fun `a plain title is left exactly alone`() {
        assertEquals("Uncanny X-Force by Rick Remender Omnibus",
            title("Uncanny X-Force by Rick Remender Omnibus.cbz"))
    }

    @Test
    fun `a title that genuinely ends in a number keeps it`() {
        // The trailing-number strip fires only after an UNDERSCORE, so these are safe — this is
        // the case a careless cleanup destroys.
        assertEquals("1984", title("1984.epub"))
        assertEquals("Volume 3", title("Volume 3.cbz"))
        assertEquals("Slaughterhouse-Five", title("Slaughterhouse-Five.epub"))
    }

    @Test
    fun `underscores that are word separators survive`() {
        // Only "_ " (underscore then SPACE) is read as a colon; a bare underscore is left as it is.
        assertEquals("some_file_name", title("some_file_name.epub"))
    }

    @Test
    fun `a name that is nothing but a hash keeps something to show`() {
        // Stripping everything would leave a blank row, which is worse than an ugly one.
        assertEquals("_58d1c1", title("_58d1c1.cbz"))
    }
}

/** The catalog address, normalised — see `OpdsCatalog.withScheme`. */
class OpdsSchemeTest {
    private fun s(u: String) = com.toolsboox.plugin.reader.ui.OpdsCatalog.withScheme(u)

    @Test
    fun `a bare host gets https`() {
        // The exact string that produced "no protocol for books.mjh.yoga".
        assertEquals("https://books.mjh.yoga", s("books.mjh.yoga"))
        assertEquals("https://books.mjh.yoga/opds", s("  books.mjh.yoga/opds  "))
    }

    @Test
    fun `an address that already says its scheme is left alone`() {
        assertEquals("https://books.mjh.yoga", s("https://books.mjh.yoga"))
        // http stays http: a catalog that genuinely needs cleartext said so on purpose.
        assertEquals("http://192.168.1.20:8080/opds", s("http://192.168.1.20:8080/opds"))
    }

    @Test
    fun `blank stays blank, so an unset catalog is still unset`() {
        assertEquals("", s(""))
        assertEquals("", s("   "))
    }

    // THE SERIES JOINS A NAME THAT SAYS NOTHING (2026-08-24: "the comics still show their
    // title as a hashtag and a numeral"). Chapter files carry the series in the FOLDER.
    @Test fun chapterNumberGetsItsSeries() {
        org.junit.Assert.assertEquals(
            "Uncanny X-Force · #102",
            BookshelfSource.displayTitle("#102_58d1c1.cbz", "ReadAllComics (EN)/Uncanny X-Force"))
    }

    @Test fun realTitleStandsAlone() {
        org.junit.Assert.assertEquals(
            "Fluke: Chance, Chaos, and Why",
            BookshelfSource.displayTitle("Fluke_ Chance, Chaos, and Why_1335.epub", "ebooks/Science"))
    }

    @Test fun noFolderMeansTheNameStands() {
        org.junit.Assert.assertEquals("#102", BookshelfSource.displayTitle("#102_58d1c1.cbz", ""))
    }
}
