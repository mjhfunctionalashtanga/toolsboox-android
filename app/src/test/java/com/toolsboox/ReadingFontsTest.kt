package com.toolsboox

import com.toolsboox.ot.ReadingFonts
import com.toolsboox.ot.ReadingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Aa Type's faces (Michael, 2026-10-11): every bundled face is really in the APK at the path it
 * is served from, the @font-face block names them all, a stored family resolves to the same CSS
 * on both platforms (iOS faceName "Ledger<Id>"), and an imported file is judged by its bytes.
 */
class ReadingFontsTest {

    /** Unit tests run with the module directory (app/) as the working directory. */
    private val assets = File("src/main/assets/fonts")
    private val resFont = File("src/main/res/font")

    @Test
    fun `every bundled face file exists where it is served from`() {
        for (face in ReadingFonts.BUNDLED) for (f in face.files) {
            val file = when {
                f.path.startsWith("a/") -> File(assets, f.path.removePrefix("a/"))
                f.path.startsWith("r/") -> File(resFont, f.path.removePrefix("r/"))
                else -> error("unknown source ${f.path}")
            }
            assertTrue("${face.id}: ${file.path} missing", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun `the license travels with the bundled fonts`() {
        assertTrue(File(assets, "OFL.txt").readText().contains("SIL OPEN FONT LICENSE"))
    }

    @Test
    fun `fast serif is not offered - its license is not OFL`() {
        assertTrue(ReadingFonts.BUNDLED.none { it.id == "fastSerif" })
    }

    @Test
    fun `the face block declares every face at the font origin`() {
        val css = ReadingFonts.bundledFacesCss()
        for (face in ReadingFonts.BUNDLED) {
            assertTrue(face.id, css.contains("font-family: '${face.cssName}'"))
            for (f in face.files) assertTrue(f.path, css.contains("url('${ReadingFonts.ORIGIN}/${f.path}')"))
        }
        assertTrue(css.contains("font-weight: 200 900"))   // Literata's variable axis
        assertTrue("no swap repaint on e-ink", css.contains("font-display: block") && !css.contains("swap"))
    }

    @Test
    fun `families resolve like iOS`() {
        assertEquals("'LedgerLiterata', serif", ReadingFonts.familyCssFor("literata"))
        assertEquals("'LedgerFastMono', monospace", ReadingFonts.familyCssFor("fastMono"))
        assertEquals("sans-serif", ReadingFonts.familyCssFor("sans-serif"))
        assertEquals("", ReadingFonts.familyCssFor(""))
        assertEquals("", ReadingFonts.familyCssFor("comic-sans"))
        val t = ReadingType.builtin(ReadingType.Profile.BOOK).copy(family = "inter", customFontId = "u:x.ttf")
        // Without a context an imported face can't be looked up — the family stands in.
        assertEquals("'LedgerInter', sans-serif", ReadingFonts.familyCss(null, t))
    }

    @Test
    fun `the bytes decide what a picked file is`() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        assertEquals("ttf", ReadingFonts.sniffFormat(b(0, 1, 0, 0)))
        assertEquals("ttf", ReadingFonts.sniffFormat("true".toByteArray()))
        assertEquals("otf", ReadingFonts.sniffFormat("OTTO".toByteArray()))
        assertEquals("woff", ReadingFonts.sniffFormat("wOFF".toByteArray()))
        assertEquals("woff2", ReadingFonts.sniffFormat("wOF2".toByteArray()))
        assertNull("a collection can't be chosen from in CSS", ReadingFonts.sniffFormat("ttcf".toByteArray()))
        assertNull(ReadingFonts.sniffFormat("%PDF".toByteArray()))
        assertNull(ReadingFonts.sniffFormat(b(0, 1)))
        assertEquals("ttf", ReadingFonts.sniffFormat(File(assets, "Jost.ttf").readBytes().copyOf(4)))
    }

    @Test
    fun `picked names become safe file stems`() {
        assertEquals("Bookerly-Regular", ReadingFonts.safeStem("Bookerly-Regular.ttf"))
        assertEquals("My_Font_v2", ReadingFonts.safeStem("My Font (v2).otf"))
        assertEquals("evil", ReadingFonts.safeStem("../../evil.ttf"))
        assertEquals("font", ReadingFonts.safeStem(".ttf"))
        assertEquals("font", ReadingFonts.safeStem("フォント.ttf"))
    }

    @Test
    fun `an imported font is listed by the family name inside it`() {
        assertEquals("Jost", ReadingFonts.sfntFamilyName(File(assets, "Jost.ttf")))
        assertEquals("Literata", ReadingFonts.sfntFamilyName(File(assets, "Literata.ttf")))
        assertEquals("Inter Variable", ReadingFonts.sfntFamilyName(File(assets, "Inter.ttf")))
        // The typographic family (name id 16) beats the style-linked one ("… Next Medium").
        assertEquals("Atkinson Hyperlegible Next",
            ReadingFonts.sfntFamilyName(File(resFont, "atkinson_hyperlegible_medium.ttf")))
    }

    @Test
    fun `a file that isn't a font has no name`() {
        val f = File.createTempFile("notafont", ".ttf").apply { writeText("hello, this is not a font"); deleteOnExit() }
        assertNull(ReadingFonts.sfntFamilyName(f))
    }

    @Test
    fun `two imported files never share a CSS name`() {
        val a = ReadingFonts.UserFont("u:My.Font.ttf", File("/x/My.Font.ttf"), "My Font")
        val b = ReadingFonts.UserFont("u:My_Font.ttf", File("/x/My_Font.ttf"), "My Font")
        assertTrue(a.cssName != b.cssName)
        assertTrue(a.cssName, Regex("^LedgerU_[A-Za-z0-9_]+$").matches(a.cssName))
        // Stable: the same file is the same name every time.
        assertEquals(a.cssName, ReadingFonts.UserFont("u:My.Font.ttf", File("/y/My.Font.ttf"), "Other").cssName)
    }
}
