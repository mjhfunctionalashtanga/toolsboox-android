package com.toolsboox

import com.toolsboox.ot.AaType
import com.toolsboox.ot.ReadingFonts
import com.toolsboox.ot.ReadingType
import com.toolsboox.ot.ReadingType.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Aa Type panel's dials (Michael, 2026-10-11): every step lands in range and never drifts,
 * the old panel's size and line steps are unchanged, the face list walks without wrapping, and
 * the live article script only ever touches the app's own type block.
 */
class AaTypeTest {

    private fun book() = ReadingType.builtin(Profile.BOOK)
    private fun article() = ReadingType.builtin(Profile.ARTICLE)

    // ---- the old panel's dials, unchanged ------------------------------------------------

    @Test
    fun `size moves in tens and stops at both ends`() {
        assertEquals(110, AaType.stepSize(book(), +1).sizePct)
        assertEquals(90, AaType.stepSize(book(), -1).sizePct)
        assertEquals(ReadingType.SIZE_MAX, AaType.stepSize(book().copy(sizePct = 240), +1).sizePct)
        assertEquals(ReadingType.SIZE_MIN, AaType.stepSize(book().copy(sizePct = 60), -1).sizePct)
    }

    @Test
    fun `line moves in tenths without drift`() {
        var t = book()
        repeat(7) { t = AaType.stepLine(t, +1) }
        assertEquals(2.2, t.line, 0.0)            // exact: counted in tenths, not summed floats
        repeat(30) { t = AaType.stepLine(t, -1) }
        assertEquals(1.0, t.line, 0.0)
        repeat(40) { t = AaType.stepLine(t, +1) }
        assertEquals(3.0, t.line, 0.0)
    }

    // ---- the new dials -------------------------------------------------------------------

    @Test
    fun `weight is a named step and snaps an odd value first`() {
        assertEquals(500, AaType.stepWeight(book(), +1).weight)
        assertEquals(900, AaType.stepWeight(book().copy(weight = 900), +1).weight)
        assertEquals(100, AaType.stepWeight(book().copy(weight = 100), -1).weight)
        assertEquals(500, AaType.stepWeight(book().copy(weight = 430), +1).weight)
        // The dial moves in hundreds, so the name is the number — short enough for a Palma at Large.
        assertEquals("Bold", AaType.weightLabel(book().copy(weight = 700)))
        assertEquals("Regular", AaType.weightLabel(book()))
        assertEquals("430", AaType.weightLabel(book().copy(weight = 430)))
    }

    @Test
    fun `spacing steps stay in range and print locale-proof`() {
        var t = book()
        repeat(40) { t = AaType.stepParagraph(t, +1) }
        assertEquals(4.0, t.paragraph, 0.0)
        repeat(3) { t = AaType.stepIndent(t, +1) }
        assertEquals(1.5, t.indent, 0.0)
        repeat(20) { t = AaType.stepWord(t, -1) }
        assertEquals(-4.0, t.word, 0.0)
        t = AaType.stepLetter(t, +1)
        assertEquals(0.25, t.letter, 0.0)
        assertEquals("1.5 em", AaType.emLabel(1.5))
        assertEquals("0.25 px", AaType.pxLabel(0.25))
        assertEquals("0 px", AaType.pxLabel(-0.0))
    }

    @Test
    fun `width walks a ladder with no limit at the top`() {
        assertEquals(800, AaType.stepWidth(book(), +1).maxWidthPx)          // 720 → 800
        assertEquals(640, AaType.stepWidth(book(), -1).maxWidthPx)
        // An article starts uncapped: down is the widest cap, up stays uncapped.
        assertEquals(1600, AaType.stepWidth(article(), -1).maxWidthPx)
        assertEquals(0, AaType.stepWidth(article(), +1).maxWidthPx)
        // A width set elsewhere snaps to its nearest rung before moving.
        assertEquals(800, AaType.stepWidth(book().copy(maxWidthPx = 730), +1).maxWidthPx)
        assertEquals(400, AaType.stepWidth(book().copy(maxWidthPx = 400), -1).maxWidthPx)
        assertEquals("No limit", AaType.widthLabel(article()))
        assertEquals("720 px", AaType.widthLabel(book()))
    }

    @Test
    fun `margin moves in fours within range`() {
        assertEquals(52, AaType.stepMargin(book(), +1).marginPx)
        assertEquals(0, AaType.stepMargin(book().copy(marginPx = 2), -1).marginPx)
        assertEquals(200, AaType.stepMargin(book().copy(marginPx = 200), +1).marginPx)
    }

    // ---- faces ---------------------------------------------------------------------------

    @Test
    fun `the face list is own, generics, bundled, then imported`() {
        val fs = AaType.faces(Profile.BOOK, listOf("u:Mine.ttf" to "Mine"))
        assertEquals("The book's own", fs.first().label)
        assertEquals(listOf("serif", "sans-serif", "monospace"), fs.subList(1, 4).map { it.family })
        assertEquals(ReadingFonts.BUNDLED.map { it.id }, fs.subList(4, 4 + ReadingFonts.BUNDLED.size).map { it.family })
        assertEquals("u:Mine.ttf", fs.last().customFontId)
        assertEquals("The page's own", AaType.faces(Profile.ARTICLE, emptyList()).first().label)
    }

    @Test
    fun `choosing an imported face keeps the family as its fallback`() {
        val fs = AaType.faces(Profile.ARTICLE, listOf("u:Mine.ttf" to "Mine"))
        val t = fs.last().applyTo(article())
        assertEquals("u:Mine.ttf", t.customFontId)
        assertEquals("serif", t.family)
        assertEquals(fs.lastIndex, AaType.faceIndex(t, fs))
        // …and choosing a bundled one lets the imported face go.
        val back = fs.first { it.family == "literata" }.applyTo(t)
        assertEquals("", back.customFontId)
        assertEquals("literata", back.family)
    }

    @Test
    fun `stepping faces stops at the ends rather than wrapping`() {
        val fs = AaType.faces(Profile.BOOK, emptyList())
        assertEquals(0, AaType.faceIndex(book(), fs))
        assertEquals(book(), AaType.stepFace(book(), -1, fs))
        assertEquals("serif", AaType.stepFace(book(), +1, fs).family)
        val last = fs.last().applyTo(book())
        assertEquals(last, AaType.stepFace(last, +1, fs))
    }

    @Test
    fun `a removed imported face falls back to its family in the list`() {
        val fs = AaType.faces(Profile.ARTICLE, emptyList())
        val t = article().copy(customFontId = "u:Gone.ttf")
        assertEquals(fs.indexOfFirst { it.family == "serif" }, AaType.faceIndex(t, fs))
    }

    // ---- colours -------------------------------------------------------------------------

    @Test
    fun `typed colours are read generously and refused when they aren't colours`() {
        assertEquals("#333333", AaType.parseHex("333"))
        assertEquals("#1a1a1a", AaType.parseHex(" #1A1A1A "))
        assertNull(AaType.parseHex("red"))
        assertNull(AaType.parseHex("#12345"))
        assertTrue((AaType.TEXT_COLOURS + AaType.PAGE_COLOURS).all { AaType.parseHex(it.first) == it.first })
    }

    @Test
    fun `every theme has words for both profiles`() {
        for (th in ReadingType.THEMES) for (p in Profile.values()) {
            assertFalse(AaType.themeLabel(th, p) == th)
        }
    }

    // ---- articles, live ------------------------------------------------------------------

    @Test
    fun `the live script only fills the app's own block and can't be broken out of`() {
        val js = AaType.liveArticleJs("p { content: \"</style><script>x()</script>\" }\n")
        assertTrue(js.contains("getElementById('ledger-type')"))
        assertTrue(js.contains("if(!s||s.textContent===c)return"))   // no block → nothing; same text → no re-lay
        assertFalse(js.contains("</script>"))
        assertFalse(js.contains("\n"))
    }

    @Test
    fun `the live swap waits for the face, and only the newest swap lands`() {
        val js = AaType.liveArticleJs("p{}", "'LedgerLiterata', serif")
        assertTrue(js, js.contains("document.fonts.load('1em '+f)"))
        assertTrue(js, js.contains("window.__ledgerTypeWant===c"))
        assertTrue(js, js.endsWith("(\"p{}\",\"'LedgerLiterata', serif\")"))
        // No face to wait for: the swap goes at once.
        assertTrue(AaType.liveArticleJs("p{}").contains(",\"\")"))
    }

    @Test
    fun `the theme strip has a short word for every theme`() {
        for (th in ReadingType.THEMES) {
            val w = AaType.themeChip(th)
            assertFalse(th, w == th)
            assertTrue(w, w.length <= 8)
        }
    }

    @Test
    fun `the width list can pick every rung, no limit last`() {
        assertEquals(0, AaType.WIDTHS.last())
        assertEquals("No limit", AaType.widthLabel(book().copy(maxWidthPx = 0)))
        assertTrue(640 in AaType.WIDTHS)
    }

    @Test
    fun `high contrast forces black on white in an article, as the colour switch does`() {
        val css = article().copy(theme = "contrast").toArticleCss("serif")
        assertTrue(css.contains("body * { color: #000000 !important;"))
        assertTrue(css.contains("background: #ffffff"))
        assertFalse(article().toArticleCss("serif").contains("!important"))   // untouched: nothing forced
    }

    @Test
    fun `the face override sets the weight but bold stays bold`() {
        val css = article().copy(overrideFont = true, weight = 500).toArticleCss("serif")
        assertTrue(css.contains("body { font-weight: 500 !important; }"))
        assertTrue(css.contains(":not(strong):not(b)"))
        assertTrue(css.contains("font-weight: inherit !important"))
    }

    @Test
    fun `the article block is the element the live script finds`() {
        assertEquals("<style id=\"ledger-type\">p{}</style>", AaType.articleStyleTag("p{}"))
        assertTrue(AaType.liveArticleJs("p{}").contains("getElementById('ledger-type')"))
    }

    @Test
    fun `the article frame sets no type, so both article views read the same`() {
        // Both builders carry this frame and the type block, nothing else (Michael 2026-10-11).
        val frame = AaType.ARTICLE_FRAME_CSS
        for (typeProp in listOf("font-family", "font-size", "line-height", "color", "background", "margin: ", "padding")) {
            assertFalse(typeProp, frame.contains(typeProp))
        }
        assertTrue(frame.contains("img { max-width: 100%"))
        assertTrue(frame.contains("pre, code { white-space: pre-wrap; }"))
    }
}
