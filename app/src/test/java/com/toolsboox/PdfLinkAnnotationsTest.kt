package com.toolsboox

import com.toolsboox.plugin.calendar.ot.PdfLinkAnnotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link injector is byte surgery on a container format, run on a device we cannot attach a
 * debugger to, and its failure mode is a file that looks fine until someone opens it. So the
 * parsing and the emitted structure are pinned here, on a fixture built the way Android's own
 * writer builds one: plain objects, a classic `xref` table, a `trailer` dictionary.
 */
class PdfLinkAnnotationsTest {

    /** A four-object PDF (catalogue, page tree, one page, one content stream) with a correct
     *  classic cross-reference table — offsets computed as the bytes are laid down, so the fixture
     *  cannot drift out of agreement with itself the way a hand-typed one would. */
    private fun fixture(): ByteArray {
        val body = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [ 3 0 R ] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 612 792 ] /Contents 4 0 R >>\nendobj\n",
            "4 0 obj\n<< /Length 44 >>\nstream\nBT /F1 12 Tf 72 720 Td (https://x.test) Tj ET\nendstream\nendobj\n",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(body.size + 1)
        for ((i, obj) in body.withIndex()) {
            offsets[i + 1] = sb.length
            sb.append(obj)
        }
        val xrefAt = sb.length
        sb.append("xref\n0 ").append(body.size + 1).append('\n')
        sb.append("0000000000 65535 f \n")
        for (i in 1..body.size) sb.append(String.format("%010d 00000 n \n", offsets[i]))
        sb.append("trailer\n<< /Size ").append(body.size + 1).append(" /Root 1 0 R >>\n")
        sb.append("startxref\n").append(xrefAt).append("\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `injects a link annotation and re-issues the page with an Annots array`() {
        val src = fixture()
        val out = PdfLinkAnnotations.inject(
            src,
            listOf(PdfLinkAnnotations.Link("https://example.com/a(b)", 0, 72f, 700f, 300f, 720f))
        )
        assertNotNull("classic-xref PDFs must be annotatable", out)
        val s = String(out!!, Charsets.ISO_8859_1)

        // Nothing of the original may be disturbed — an incremental update only appends.
        assertTrue("original bytes must be preserved verbatim", s.startsWith(String(src, Charsets.ISO_8859_1)))

        // The annotation itself, with the parentheses in the URL escaped so the literal string
        // does not terminate early.
        assertTrue(s.contains("/Type /Annot /Subtype /Link"))
        assertTrue(s.contains("/URI (https://example.com/a\\(b\\))"))
        assertTrue(s.contains("/Rect [ 72.00 700.00 300.00 720.00 ]"))

        // The page is re-issued under its ORIGINAL object number, carrying the new array.
        assertTrue(s.contains("3 0 obj\n<< /Type /Page"))
        assertTrue(s.contains("/Annots [ 5 0 R ]"))
    }

    @Test
    fun `the appended cross-reference section points at the objects it lists`() {
        val src = fixture()
        val out = PdfLinkAnnotations.inject(
            src, listOf(PdfLinkAnnotations.Link("https://x.test", 0, 0f, 0f, 10f, 10f))
        )!!
        val s = String(out, Charsets.ISO_8859_1)

        val startXref = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(s)!!.groupValues[1].toInt()
        assertTrue("startxref must address our appended table", s.startsWith("xref", startXref))

        // /Prev must lead back to the original table, or a reader loses every original object.
        val origStart = Regex("""startxref\s+(\d+)""").find(String(src, Charsets.ISO_8859_1))!!
            .groupValues[1].toInt()
        assertTrue(s.contains("/Prev $origStart"))
        // Two new objects (the annotation is 5, the re-issued page is 3), so /Size grows by one.
        assertTrue(s.contains("/Size 6"))

        // Every offset in the appended table must actually land on that object's header.
        val table = s.substring(startXref)
        val sub = Regex("""(\d+) (\d+)\n((?:\d{10} \d{5} [nf] \n)+)""").findAll(table)
        var entries = 0
        for (m in sub) {
            val first = m.groupValues[1].toInt()
            m.groupValues[3].trim('\n').split("\n").forEachIndexed { i, line ->
                val off = line.substring(0, 10).toInt()
                assertTrue(
                    "object ${first + i} must start at $off",
                    s.startsWith("${first + i} 0 obj", off)
                )
                entries++
            }
        }
        assertEquals("one annotation object plus one re-issued page", 2, entries)
    }

    @Test
    fun `refuses a cross-reference stream rather than guessing`() {
        // The compressed form: startxref addresses an object, not the keyword `xref`.
        val s = "%PDF-1.5\n1 0 obj\n<< /Type /XRef /Size 2 /Root 1 0 R >>\nstream\nx\nendstream\nendobj\n" +
            "startxref\n9\n%%EOF\n"
        assertNull(PdfLinkAnnotations.inject(s.toByteArray(Charsets.ISO_8859_1), listOf(
            PdfLinkAnnotations.Link("https://x.test", 0, 0f, 0f, 1f, 1f)
        )))
    }

    @Test
    fun `no links means no rewrite`() {
        assertNull(PdfLinkAnnotations.inject(fixture(), emptyList()))
    }
}
