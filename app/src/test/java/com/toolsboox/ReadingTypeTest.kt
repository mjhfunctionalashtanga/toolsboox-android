package com.toolsboox

import com.toolsboox.ot.ReadingType
import com.toolsboox.ot.ReadingType.Profile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Aa Type's model (Michael, 2026-10-11): the migration of the reader's old keys (nothing lost),
 * the sparse layering, and the two outputs — the book engine's settings object and the article
 * style block. The builtins are pinned to what the readers drew BEFORE Aa Type, so an untouched
 * profile changes nobody's page.
 */
class ReadingTypeTest {

    private val savedLocale: Locale = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun book() = ReadingType.builtin(Profile.BOOK)
    private fun article() = ReadingType.builtin(Profile.ARTICLE)

    // ---- migration -----------------------------------------------------------------------

    @Test
    fun `every old reader pref lands on its Aa Type field`() {
        val old = mapOf<String, Any>(
            "reader_font_pct" to 130, "reader_line_height_tenths" to 18,
            "reader_theme" to "sepia", "reader_spread" to true,
            "current_book_path" to "/x/y.epub",   // unrelated reader keys are ignored
        )
        val t = ReadingType.resolve(Profile.BOOK, null, ReadingType.migrateReaderPrefs(old))
        assertEquals(130, t.sizePct)
        assertEquals(1.8, t.line, 1e-9)
        assertEquals("sepia", t.theme)
        assertEquals(2, t.columns)
        // …and nothing else moved.
        assertEquals(book().copy(sizePct = 130, line = 1.8, theme = "sepia", columns = 2), t)
    }

    @Test
    fun `old values that were already the defaults store nothing`() {
        val old = mapOf<String, Any>(
            "reader_font_pct" to 100, "reader_line_height_tenths" to 15,
            "reader_theme" to "default", "reader_spread" to false,
        )
        assertEquals(0, ReadingType.migrateReaderPrefs(old).length())
    }

    @Test
    fun `a reader who never touched the settings migrates to an empty override`() {
        val m = ReadingType.migrateReaderPrefs(emptyMap<String, Any>())
        assertEquals(0, m.length())
        assertEquals(book(), ReadingType.resolve(Profile.BOOK, null, m))
    }

    @Test
    fun `only the keys that exist migrate`() {
        val m = ReadingType.migrateReaderPrefs(mapOf("reader_spread" to true))
        assertEquals(setOf("columns"), m.keys().asSequence().toSet())
        assertEquals(2, m.getInt("columns"))
    }

    @Test
    fun `the migration goes to books only - articles are untouched`() {
        val m = ReadingType.migrateReaderPrefs(mapOf("reader_font_pct" to 150, "reader_theme" to "black"))
        assertEquals(article(), ReadingType.resolve(Profile.ARTICLE, null, null))
        assertEquals(150, ReadingType.resolve(Profile.BOOK, null, m).sizePct)
    }

    @Test
    fun `out of range and unknown old values are made safe`() {
        val m = ReadingType.migrateReaderPrefs(mapOf(
            "reader_font_pct" to 999, "reader_line_height_tenths" to 2, "reader_theme" to "neon"))
        val t = ReadingType.resolve(Profile.BOOK, null, m)
        assertEquals(ReadingType.SIZE_MAX, t.sizePct)
        assertEquals(ReadingType.LINE_MIN, t.line, 1e-9)
        assertEquals("default", t.theme)
        assertFalse(m.has("theme"))
    }

    @Test
    fun `wrongly typed old values are skipped, not crashed on`() {
        val m = ReadingType.migrateReaderPrefs(mapOf("reader_font_pct" to "big", "reader_spread" to "yes"))
        assertEquals(0, m.length())
    }

    // ---- layering ------------------------------------------------------------------------

    @Test
    fun `diff then resolve round-trips any value`() {
        val v = book().copy(sizePct = 140, weight = 600, line = 1.9, family = "literata",
            justify = false, marginPx = 20, theme = "contrast", fg = "#112233", customCss = "p{color:red}")
        val d = ReadingType.diff(Profile.BOOK, null, v)
        assertEquals(v, ReadingType.resolve(Profile.BOOK, null, d))
        assertFalse("unchanged fields aren't stored", d.has("hyphenate"))
    }

    @Test
    fun `putting a value back to its base removes it from storage`() {
        assertEquals(0, ReadingType.diff(Profile.BOOK, null, book()).length())
    }

    @Test
    fun `the shared layer reaches both profiles until one overrides it`() {
        val shared = JSONObject().put("line", 1.8).put("family", "inter")
        val own = JSONObject().put("family", "vollkorn")
        assertEquals(1.8, ReadingType.resolve(Profile.ARTICLE, shared, null).line, 1e-9)
        assertEquals(1.8, ReadingType.resolve(Profile.BOOK, shared, own).line, 1e-9)
        assertEquals("inter", ReadingType.resolve(Profile.ARTICLE, shared, null).family)
        assertEquals("vollkorn", ReadingType.resolve(Profile.BOOK, shared, own).family)
    }

    @Test
    fun `garbage in storage falls through to the layer below`() {
        val own = JSONObject().put("sizePct", "huge").put("fg", "red").put("theme", 7).put("nope", 1)
        assertEquals(book(), ReadingType.resolve(Profile.BOOK, null, own))
    }

    // ---- toFoliateJson -------------------------------------------------------------------

    @Test
    fun `an untouched book profile states exactly the engine's own defaults`() {
        // reader-embed.js `settings` — the values every book drew with before Aa Type.
        val j = JSONObject(book().toFoliateJson())
        assertEquals(100, j.getInt("fontSize"))
        assertEquals(400, j.getInt("fontWeight"))
        assertEquals(1.5, j.getDouble("lineHeight"), 1e-9)
        assertEquals(1.0, j.getDouble("paragraphMargin"), 1e-9)
        assertEquals(0.0, j.getDouble("textIndent"), 1e-9)
        assertEquals(0.0, j.getDouble("wordSpacing"), 1e-9)
        assertEquals(0.0, j.getDouble("letterSpacing"), 1e-9)
        assertTrue(j.getBoolean("justify"))
        assertTrue(j.getBoolean("hyphenate"))
        assertEquals(48, j.getInt("margin"))
        assertEquals(720, j.getInt("maxInlineSize"))
        assertEquals(1, j.getInt("columns"))
        assertEquals("paginated", j.getString("flow"))
        assertEquals("default", j.getString("theme"))
        assertEquals("", j.getString("fg"))
        assertFalse(j.getBoolean("overrideFont"))
    }

    @Test
    fun `a comic's payload leaves its flow alone`() {
        assertFalse(JSONObject(book().copy(flow = "scrolled").toFoliateJson(includeFlow = false)).has("flow"))
        assertEquals("scrolled", JSONObject(book().copy(flow = "scrolled").toFoliateJson()).getString("flow"))
    }

    @Test
    fun `no width cap becomes a column wider than any screen`() {
        assertTrue(JSONObject(book().copy(maxWidthPx = 0).toFoliateJson()).getInt("maxInlineSize") >= 4000)
    }

    @Test
    fun `the payload is locale-proof`() {
        Locale.setDefault(Locale.GERMANY)
        val s = book().copy(line = 1.7, word = 0.5).toFoliateJson()
        assertTrue(s, s.contains("\"lineHeight\":1.7"))
        assertFalse(s, s.contains("1,7"))
    }

    // ---- toArticleCss --------------------------------------------------------------------

    @Test
    fun `an untouched article profile is the in-pane reader's column`() {
        // FeedsFragment.buildArticleHtml: serif 18px / 1.6, 44px gutters, black on white.
        val css = article().toArticleCss(familyCss = "serif")
        assertTrue(css, css.contains("padding: 0 44px"))
        assertTrue(css, css.contains("font-family: serif;"))
        assertTrue(css, css.contains("font-size: 18px;"))
        assertTrue(css, css.contains("line-height: 1.6;"))
        assertTrue(css, css.contains("color: #000000;"))
        assertTrue(css, css.contains("background: #ffffff;"))
        assertTrue(css, css.contains("text-align: start"))
        assertTrue(css, css.contains("hyphens: manual"))
        assertFalse("no width cap by default", css.contains("max-width"))
        assertFalse("nothing forced by default", css.contains("!important"))
        // The two builders' old 24px heading and 13px #666 byline, now from the block.
        assertTrue(css, css.contains("h1 { font-size: 1.33em;"))
        assertTrue(css, css.contains(".meta { font-size: 0.72em; color: #666666; }"))
    }

    @Test
    fun `paragraph spacing leaves lists as tight as they were`() {
        val css = article().copy(paragraph = 0.5).toArticleCss("serif")
        assertTrue(css, css.contains("p, blockquote { margin-block: 0.5em; }"))
        val spacingRule = css.lines().first { it.startsWith("p, li, blockquote, dd {") }
        assertFalse(spacingRule, spacingRule.contains("margin"))
    }

    @Test
    fun `the byline mixes the theme's own two colours`() {
        assertTrue(article().copy(theme = "black").toArticleCss("serif").contains(".meta { font-size: 0.72em; color: #7c7c7c; }"))
        assertTrue(article().copy(fg = "#ffffff", bg = "#000000").sanitized().toArticleCss("serif")
            .contains("color: #999999; }"))
    }

    @Test
    fun `the byline reads on every theme and stays #666 where it already did`() {
        for (th in ReadingType.THEMES) {
            val t = article().copy(theme = th)
            val css = t.toArticleCss("serif")
            val ink = Regex("\\.meta \\{ font-size: 0.72em; color: (#[0-9a-f]{6}); \\}").find(css)!!.groupValues[1]
            assertTrue("$th $ink", ReadingType.contrastRatio(ink, t.resolvedBg()) >= ReadingType.READABLE)
        }
        assertTrue(article().toArticleCss("serif").contains("color: #666666; }"))
    }

    @Test
    fun `high contrast is black on white always - custom colours give way`() {
        val t = article().copy(theme = "contrast", fg = "#ffffff", bg = "#1e1e1e").sanitized()
        assertEquals("#000000", t.resolvedFg())
        assertEquals("#ffffff", t.resolvedBg())
        val css = t.toArticleCss("serif")
        assertTrue(css, css.contains("background: #ffffff;") && css.contains("color: #000000 !important"))
        assertFalse(css, css.contains("#1e1e1e"))
    }

    @Test
    fun `a custom page with no text colour gets the ink that reads on it`() {
        // Charcoal page on the default theme: light ink, not the theme's black.
        assertEquals("#cfcfcf", article().copy(bg = "#1e1e1e").resolvedFg())
        // Paper page on Black: dark ink, not Black's light grey.
        assertEquals("#000000", article().copy(theme = "black", bg = "#f8f5ec").resolvedFg())
        // A page the theme's ink already reads on keeps it.
        assertEquals("#5b4636", article().copy(theme = "sepia", bg = "#f8f5ec").resolvedFg())
        // A custom text colour is the reader's own choice and stays, readable or not.
        assertEquals("#ffffff", article().copy(fg = "#ffffff").resolvedFg())
    }

    @Test
    fun `contrast arithmetic`() {
        assertEquals(21.0, ReadingType.contrastRatio("#000000", "#ffffff"), 0.01)
        assertEquals(1.0, ReadingType.contrastRatio("#777777", "#777777"), 0.0)
        assertTrue(ReadingType.isDark("#1e1e1e"))
        assertTrue(ReadingType.isDark("#000000"))
        assertFalse(ReadingType.isDark("#c9c9c9"))
        assertFalse(ReadingType.isDark("#f4ecd8"))
        assertEquals(21.0, ReadingType.contrastRatio("nope", "#ffffff"), 0.0)   // never a false warning
    }

    @Test
    fun `under my face code keeps its own, inside too`() {
        val css = article().copy(overrideFont = true).toArticleCss("'LedgerInter', sans-serif")
        assertTrue(css, css.contains("body :not(pre):not(code):not(kbd):not(samp):not(tt) { font-family: 'LedgerInter', sans-serif !important; }"))
        // Same specificity (type + five :not), written after: wins inside a highlighter's spans.
        val forced = css.indexOf("font-family: 'LedgerInter'")
        val kept = css.indexOf(ReadingType.CODE_KEEPS_ITS_FACE)
        assertTrue(css, kept > forced)
        assertTrue(ReadingType.CODE_KEEPS_ITS_FACE.startsWith("pre :not(pre):not(code):not(kbd):not(samp):not(tt), code "))
        // No face chosen: nothing forced, nothing kept.
        assertFalse(article().copy(overrideFont = true).toArticleCss("").contains("font-family: inherit"))
    }

    @Test
    fun `size scales from the article base and stays locale-proof`() {
        Locale.setDefault(Locale.FRANCE)
        val css = article().copy(sizePct = 120, line = 1.75).toArticleCss("serif")
        assertTrue(css, css.contains("font-size: 21.6px;"))
        assertTrue(css, css.contains("line-height: 1.75;"))
    }

    @Test
    fun `layout and family reach the block`() {
        val css = article().copy(maxWidthPx = 640, justify = true, hyphenate = true, indent = 1.5,
            paragraph = 0.5, word = 2.0, letter = 0.5, weight = 500)
            .toArticleCss("'LedgerLiterata', serif")
        assertTrue(css, css.contains("max-width: 640px"))
        assertTrue(css, css.contains("text-align: justify"))
        assertTrue(css, css.contains("hyphens: auto"))
        assertTrue(css, css.contains("text-indent: 1.5em"))
        assertTrue(css, css.contains("margin-block: 0.5em"))
        assertTrue(css, css.contains("word-spacing: 2px"))
        assertTrue(css, css.contains("letter-spacing: 0.5px"))
        assertTrue(css, css.contains("font-weight: 500"))
        assertTrue(css, css.contains("font-family: 'LedgerLiterata', serif;"))
    }

    @Test
    fun `a blank family leaves the document's own face`() {
        assertFalse(article().copy(family = "").toArticleCss("").contains("font-family"))
    }

    @Test
    fun `themes and custom colours`() {
        val sepia = article().copy(theme = "sepia").toArticleCss("serif")
        assertTrue(sepia, sepia.contains("color: #5b4636;") && sepia.contains("background: #f4ecd8;"))
        val custom = article().copy(theme = "sepia", fg = "#101010", bg = "#fafafa").sanitized().toArticleCss("serif")
        assertTrue(custom, custom.contains("color: #101010;") && custom.contains("background: #fafafa;"))
        val bad = article().copy(fg = "red; } body { display:none").sanitized()
        assertEquals("", bad.fg)
    }

    @Test
    fun `the override switches force with important, code keeps its face`() {
        val css = article().copy(overrideFont = true, overrideLayout = true, overrideColor = true)
            .toArticleCss("'LedgerInter', sans-serif")
        assertTrue(css, css.contains(":not(pre):not(code)"))
        assertTrue(css, css.contains("font-family: 'LedgerInter', sans-serif !important"))
        assertTrue(css, css.contains("text-align: start !important"))
        assertTrue(css, css.contains("color: #000000 !important"))
    }

    @Test
    fun `custom css comes last and cannot close the style element`() {
        val css = article().copy(customCss = "p { color: red } </style><script>x()</script>")
            .sanitized().toArticleCss("serif")
        assertTrue(css.trimEnd().endsWith("<\\/script>"))
        assertFalse(css.contains("</style"))
    }
}
