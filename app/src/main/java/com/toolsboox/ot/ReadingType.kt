package com.toolsboox.ot

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Aa Type — how the words are set in the two readers, books and articles.
 *
 * Michael, 2026-10-11: "switching font styles in readers both book and rss isn't as robust as I
 * want. It should be powerful like Readest and powerful like EinkBro." The book engine could
 * always do most of this (reader-embed.js `settings`); the app only ever reached size, line
 * height, theme and spread, and the two article views each hard-coded their own serif column.
 *
 * ONE model, in Readest's vocabulary (their ViewSettings is the idea; this is our own code —
 * Readest is AGPL), and TWO profiles: [Profile.BOOK] and [Profile.ARTICLE]. A profile is a
 * SPARSE override over a shared layer, which is itself sparse over the profile's built-in
 * defaults ([builtin]). So a value set for both is read by both, a value set for books alone stays
 * with books, and a value put back to where it started stops being stored at all ([diff]).
 *
 * Stored as JSON strings in "ledger_a11y" beside the other legibility keys ([ReadingTypes]).
 */
data class ReadingType(
    /** "" = the document's own face; "serif" / "sans-serif" / "monospace"; or a bundled face id
     *  from [ReadingFonts.BUNDLED] (the iOS ReadingFont ids). */
    val family: String,
    /** An imported face ("u:<file>", [ReadingFonts.importFont]). Wins over [family] while its file
     *  is still there; a removed font quietly falls back to [family]. */
    val customFontId: String,
    val sizePct: Int,
    /** 100–900. EinkBro's "bold boost" is this number going up. */
    val weight: Int,
    /** Line height multiplier. */
    val line: Double,
    /** Space between paragraphs, em. */
    val paragraph: Double,
    /** First-line indent, em. */
    val indent: Double,
    /** Word spacing, px. */
    val word: Double,
    /** Letter spacing, px. */
    val letter: Double,
    val justify: Boolean,
    val hyphenate: Boolean,
    /** Page margin (books) / side gutter (articles), px. */
    val marginPx: Int,
    /** Column width cap, px. 0 = no cap. */
    val maxWidthPx: Int,
    /** 1, or 2 = the open-book spread (books). */
    val columns: Int,
    /** "paginated" | "scrolled" (books). */
    val flow: String,
    /** One of [THEMES]. */
    val theme: String,
    /** Custom text / page colour, "#rrggbb"; "" = the theme's. */
    val fg: String,
    val bg: String,
    /** Make the book's own CSS give way (Readest's override switches; applied with !important). */
    val overrideFont: Boolean,
    val overrideLayout: Boolean,
    val overrideColor: Boolean,
    /** Appended last, after everything above. */
    val customCss: String,
) {
    enum class Profile(val key: String) { BOOK("type_book"), ARTICLE("type_article") }

    /** Clamp every field into range — stored JSON is never trusted to be well-formed. */
    fun sanitized(): ReadingType = copy(
        family = family.trim().takeIf { SAFE_ID.matches(it) } ?: "",
        customFontId = customFontId.trim().takeIf { SAFE_ID.matches(it) } ?: "",
        sizePct = sizePct.coerceIn(SIZE_MIN, SIZE_MAX),
        weight = weight.coerceIn(100, 900),
        line = finite(line, 1.5).coerceIn(LINE_MIN, LINE_MAX).round2(),
        paragraph = finite(paragraph, 1.0).coerceIn(0.0, 4.0).round2(),
        indent = finite(indent, 0.0).coerceIn(0.0, 6.0).round2(),
        word = finite(word, 0.0).coerceIn(-4.0, 16.0).round2(),
        letter = finite(letter, 0.0).coerceIn(-2.0, 8.0).round2(),
        marginPx = marginPx.coerceIn(0, 200),
        maxWidthPx = maxWidthPx.coerceIn(0, 2400),
        columns = columns.coerceIn(1, 2),
        flow = if (flow == "scrolled") "scrolled" else "paginated",
        theme = theme.takeIf { it in THEMES } ?: "default",
        fg = fg.trim().takeIf { COLOR.matches(it) } ?: "",
        bg = bg.trim().takeIf { COLOR.matches(it) } ?: "",
        // The CSS rides inside a <style> element in the article views: a literal "</" would end
        // that element and spill the rest into the page. "<\/" is the same CSS, minus the exit.
        customCss = customCss.take(CSS_MAX).replace("</", "<\\/"),
    )

    /** Every field, keyed by its property name — the stored shape and the [diff] currency. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("family", family); put("customFontId", customFontId)
        put("sizePct", sizePct); put("weight", weight)
        put("line", line); put("paragraph", paragraph); put("indent", indent)
        put("word", word); put("letter", letter)
        put("justify", justify); put("hyphenate", hyphenate)
        put("marginPx", marginPx); put("maxWidthPx", maxWidthPx)
        put("columns", columns); put("flow", flow)
        put("theme", theme); put("fg", fg); put("bg", bg)
        put("overrideFont", overrideFont); put("overrideLayout", overrideLayout); put("overrideColor", overrideColor)
        put("customCss", customCss)
    }

    /**
     * The text colour actually drawn. High contrast is black on white ALWAYS — the custom colours
     * give way to it (its label says "always"). Else the custom one; else the theme's — unless a
     * custom PAGE colour leaves the theme's ink unreadable on it (dark page, dark ink), when the
     * ink that reads there is used instead. reader-embed.js buildCSS makes the same choice for
     * books (Aa Type review, Michael 2026-10-11).
     */
    fun resolvedFg(): String {
        if (theme == "contrast") return PALETTE.getValue("contrast").first
        if (fg.isNotBlank()) return fg
        val ink = PALETTE[theme]?.first ?: "#000000"
        if (bg.isBlank() || contrastRatio(ink, bg) >= READABLE) return ink
        return if (isDark(bg)) PALETTE.getValue("black").first else "#000000"
    }

    /** The page colour actually drawn: high contrast's white, else the custom one, else the theme's. */
    fun resolvedBg(): String {
        if (theme == "contrast") return PALETTE.getValue("contrast").second
        return bg.ifBlank { PALETTE[theme]?.second ?: "#ffffff" }
    }

    /**
     * The book engine's settings object (reader-embed.js `settings`), whole — every key, so one
     * applyReaderSettings call states the entire look and nothing is left to an earlier call.
     *
     * [includeFlow] is false for a comic: a comic's flow is per book (comic_flow_<book>, sent by
     * setComicPrefs), and this payload must not walk over it.
     *
     * fg/bg are only the CUSTOM colours ("" when the theme decides) — the engine owns the theme
     * palette for books.
     */
    fun toFoliateJson(includeFlow: Boolean = true): String = JSONObject().apply {
        put("fontSize", sizePct)
        put("fontWeight", weight)
        put("lineHeight", line)
        put("paragraphMargin", paragraph)
        put("textIndent", indent)
        put("wordSpacing", word)
        put("letterSpacing", letter)
        put("justify", justify)
        put("hyphenate", hyphenate)
        put("margin", marginPx)
        // The engine has no "no cap" — a column wider than any screen is the same thing.
        put("maxInlineSize", if (maxWidthPx > 0) maxWidthPx else NO_CAP_PX)
        put("columns", columns)
        if (includeFlow) put("flow", flow)
        put("theme", theme)
        put("fg", fg)
        put("bg", bg)
        put("overrideFont", overrideFont)
        put("overrideLayout", overrideLayout)
        put("overrideColor", overrideColor)
        put("customCss", customCss)
    }.toString()

    /**
     * The article views' type, as the body of a replaceable `<style id="ledger-type">` — both
     * article builders read this one block, so the in-pane reader and the standalone one finally
     * agree. Structure the builders own (images, iframes, code, the listen bar) stays theirs —
     * [AaType.ARTICLE_FRAME_CSS] — and carries no type at all.
     *
     * [familyCss] is the resolved CSS font-family value ([ReadingFonts.familyCss]; "" = leave the
     * document's). [basePx] is what 100% means in an article.
     */
    fun toArticleCss(familyCss: String, basePx: Int = ARTICLE_BASE_PX): String {
        val px = basePx * sizePct / 100.0
        val fgc = resolvedFg()
        val bgc = resolvedBg()
        fun bang(on: Boolean) = if (on) " !important" else ""
        val sb = StringBuilder()
        sb.append("html { padding: 0 ${marginPx}px; background: $bgc; }\n")
        sb.append("body { margin: 24px auto; color: $fgc; background: $bgc;")
        if (familyCss.isNotBlank()) sb.append(" font-family: $familyCss;")
        sb.append(" font-size: ${num(px)}px; font-weight: $weight; line-height: ${num(line)};")
        sb.append(if (maxWidthPx > 0) " max-width: ${maxWidthPx}px; }\n" else " }\n")
        sb.append("h1 { font-size: 1.33em; line-height: 1.25; }\n")
        // The byline's quieter ink is the page's own two colours mixed — #666 on the default
        // page, as both builders drew it — leaning toward the text until it reads (4.5:1) on
        // every theme: at 0.6 it was 3.8:1 on Grey and 2.9:1 on Sepia (Michael 2026-10-11).
        sb.append(".meta { font-size: 0.72em; color: ${bylineInk(fgc, bgc)}; }\n")
        sb.append("a { color: $fgc; }\n")
        val lay = bang(overrideLayout)
        sb.append("p, li, blockquote, dd {")
        sb.append(" text-align: ${if (justify) "justify" else "start"}$lay;")
        sb.append(" word-spacing: ${num(word)}px$lay; letter-spacing: ${num(letter)}px$lay;")
        val hy = if (hyphenate) "auto" else "manual"
        sb.append(" -webkit-hyphens: $hy$lay; hyphens: $hy$lay; }\n")
        // Paragraph spacing is for paragraphs: list items and definitions keep their own (none),
        // so an untouched article's lists sit as tight as they always did.
        sb.append("p, blockquote { margin-block: ${num(paragraph)}em$lay; }\n")
        sb.append("p { text-indent: ${num(indent)}em$lay; }\n")
        if (overrideLayout) sb.append("body * { line-height: ${num(line)} !important; }\n")
        // Code keeps its own face under the override — a forced proportional face breaks it —
        // and so does everything INSIDE code (a highlighter's spans): the second rule is as
        // specific as the first and comes after it, so it wins there (review, Michael 2026-10-11).
        if (overrideFont && familyCss.isNotBlank()) {
            sb.append("body $NOT_CODE { font-family: $familyCss !important; }\n")
            sb.append(CODE_KEEPS_ITS_FACE).append('\n')
        }
        // The weight reaches the text but bold stays bold: everything inherits the body's weight
        // except the elements whose whole point is a different one (the book engine's
        // overrideFont branch is the same rule — Michael 2026-10-11, both readers agree).
        if (overrideFont) {
            sb.append("body { font-weight: $weight !important; }\n")
            sb.append("body *:not(strong):not(b):not(h1):not(h2):not(h3):not(h4):not(h5):not(h6):not(th) ")
            sb.append("{ font-weight: inherit !important; }\n")
        }
        // "contrast" is the forced black-on-white page, so it forces like the switch does.
        if (overrideColor || theme == "contrast") {
            sb.append("body * { color: $fgc !important; background-color: transparent !important; ")
            sb.append("border-color: $fgc !important; }\n")
        }
        if (customCss.isNotBlank()) sb.append(customCss).append('\n')
        return sb.toString()
    }

    companion object {
        /** The book engine's four themes (reader-embed.js THEME_BG/FG) plus a forced
         *  black-text-on-white page — EinkBro's "force black text / white background". */
        val THEMES = listOf("default", "sepia", "gray", "black", "contrast")

        /** text, page — mirrors reader-embed.js THEME_FG/THEME_BG so a book and an article in the
         *  same theme are the same colours. */
        val PALETTE = mapOf(
            "default" to ("#000000" to "#ffffff"),
            "sepia" to ("#5b4636" to "#f4ecd8"),
            "gray" to ("#1a1a1a" to "#c9c9c9"),
            "black" to ("#cfcfcf" to "#000000"),
            "contrast" to ("#000000" to "#ffffff"),
        )

        /** The faces that stay their own under "My face over …" — code, keyboard, sample, teletype. */
        private const val NOT_CODE = ":not(pre):not(code):not(kbd):not(samp):not(tt)"

        /** Inside those, the face is inherited from them, not forced: same specificity as
         *  `body $NOT_CODE` (one type + five :not), written after it. reader-embed.js has the twin. */
        const val CODE_KEEPS_ITS_FACE =
            "pre $NOT_CODE, code $NOT_CODE, kbd $NOT_CODE, samp $NOT_CODE, tt $NOT_CODE " +
                "{ font-family: inherit !important; }"

        /** WCAG AA for body text — the line the panel warns under and the byline is raised to. */
        const val READABLE = 4.5

        const val SIZE_MIN = 60
        const val SIZE_MAX = 240
        const val LINE_MIN = 1.0
        const val LINE_MAX = 3.0
        const val ARTICLE_BASE_PX = 18
        private const val NO_CAP_PX = 4096
        private const val CSS_MAX = 20_000
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
        private val SAFE_ID = Regex("^[A-Za-z0-9:._-]{0,120}$")

        /**
         * Where each profile starts — exactly what the readers drew before Aa Type, so nobody's
         * page changes until they change it. Books: reader-embed.js's own `settings` defaults.
         * Articles: the in-pane reader's old hard-coded column (FeedsFragment.buildArticleHtml —
         * serif 18px / 1.6 / 44px gutters, ragged right, no hyphens, no width cap), the article
         * surface he reads most. Both article builders now take every word from here, so the
         * standalone reader's old 19px / 1.65 / 34em column became this one too.
         */
        fun builtin(profile: Profile): ReadingType = when (profile) {
            Profile.BOOK -> ReadingType(
                family = "", customFontId = "", sizePct = 100, weight = 400, line = 1.5,
                paragraph = 1.0, indent = 0.0, word = 0.0, letter = 0.0,
                justify = true, hyphenate = true, marginPx = 48, maxWidthPx = 720,
                columns = 1, flow = "paginated", theme = "default", fg = "", bg = "",
                overrideFont = false, overrideLayout = false, overrideColor = false, customCss = "",
            )
            Profile.ARTICLE -> ReadingType(
                family = "serif", customFontId = "", sizePct = 100, weight = 400, line = 1.6,
                paragraph = 1.0, indent = 0.0, word = 0.0, letter = 0.0,
                justify = false, hyphenate = false, marginPx = 44, maxWidthPx = 0,
                columns = 1, flow = "scrolled", theme = "default", fg = "", bg = "",
                overrideFont = false, overrideLayout = false, overrideColor = false, customCss = "",
            )
        }

        /** builtin(profile) ← shared ← own, field by field, then [sanitized]. Unknown keys and
         *  wrongly-typed values are ignored (the layer below shows through). */
        fun resolve(profile: Profile, shared: JSONObject?, own: JSONObject?): ReadingType {
            var t = builtin(profile)
            if (shared != null) t = overlay(t, shared)
            if (own != null) t = overlay(t, own)
            return t.sanitized()
        }

        /** The sparse override that turns resolve(profile, shared, null) into [value]: only the
         *  fields that differ. A value put back to its base disappears from storage. */
        fun diff(profile: Profile, shared: JSONObject?, value: ReadingType): JSONObject {
            val base = resolve(profile, shared, null).toJson()
            val v = value.sanitized().toJson()
            val out = JSONObject()
            for (k in v.keys()) if (!same(base.opt(k), v.opt(k))) out.put(k, v.get(k))
            return out
        }

        /**
         * Today's reader prefs ("ledger_reader_prefs") → the BOOK profile's sparse override.
         * Nothing lost: size, line height, theme and the two-page spread each land on their Aa
         * Type field, and a value that was already the default simply isn't stored. They were
         * book-only settings, so they go to the book profile — articles are untouched.
         */
        fun migrateReaderPrefs(old: Map<String, *>): JSONObject {
            var t = builtin(Profile.BOOK)
            (old[OLD_FONT] as? Number)?.let { t = t.copy(sizePct = it.toInt()) }
            (old[OLD_LINE] as? Number)?.let { t = t.copy(line = it.toInt() / 10.0) }
            (old[OLD_THEME] as? String)?.let { t = t.copy(theme = it) }
            (old[OLD_SPREAD] as? Boolean)?.let { t = t.copy(columns = if (it) 2 else 1) }
            return diff(Profile.BOOK, null, t)
        }

        /** The pre-Aa-Type reader keys (ReaderFragment, 08-10…08-12). Read once, never written. */
        const val OLD_PREFS = "ledger_reader_prefs"
        const val OLD_FONT = "reader_font_pct"
        const val OLD_LINE = "reader_line_height_tenths"
        const val OLD_THEME = "reader_theme"
        const val OLD_SPREAD = "reader_spread"

        private fun overlay(t: ReadingType, j: JSONObject): ReadingType {
            fun s(k: String, d: String) = (j.opt(k) as? String) ?: d
            fun i(k: String, d: Int) = (j.opt(k) as? Number)?.toInt() ?: d
            fun f(k: String, d: Double) = (j.opt(k) as? Number)?.toDouble() ?: d
            fun b(k: String, d: Boolean) = (j.opt(k) as? Boolean) ?: d
            return ReadingType(
                family = s("family", t.family), customFontId = s("customFontId", t.customFontId),
                sizePct = i("sizePct", t.sizePct), weight = i("weight", t.weight),
                line = f("line", t.line), paragraph = f("paragraph", t.paragraph),
                indent = f("indent", t.indent), word = f("word", t.word), letter = f("letter", t.letter),
                justify = b("justify", t.justify), hyphenate = b("hyphenate", t.hyphenate),
                marginPx = i("marginPx", t.marginPx), maxWidthPx = i("maxWidthPx", t.maxWidthPx),
                columns = i("columns", t.columns), flow = s("flow", t.flow),
                theme = s("theme", t.theme), fg = s("fg", t.fg), bg = s("bg", t.bg),
                overrideFont = b("overrideFont", t.overrideFont),
                overrideLayout = b("overrideLayout", t.overrideLayout),
                overrideColor = b("overrideColor", t.overrideColor),
                customCss = s("customCss", t.customCss),
            )
        }

        private fun same(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) kotlin.math.abs(a.toDouble() - b.toDouble()) < 1e-6 else a == b

        /** [a] laid over [b] at strength [t] (0–1), "#rrggbb"; [a] itself if either isn't a colour. */
        private fun mix(a: String, b: String, t: Double): String {
            if (!COLOR.matches(a) || !COLOR.matches(b)) return a
            fun ch(s: String, i: Int) = s.substring(1 + 2 * i, 3 + 2 * i).toInt(16)
            return "#" + (0..2).joinToString("") { i ->
                val v = Math.round(ch(a, i) * t + ch(b, i) * (1 - t)).toInt().coerceIn(0, 255)
                String.format(Locale.US, "%02x", v)
            }
        }

        /** The byline's ink: [fg] mixed into [bg] at 0.6, raised toward [fg] until it reads. */
        internal fun bylineInk(fg: String, bg: String): String {
            var t = 0.6
            var ink = mix(fg, bg, t)
            while (t < 1.0 && contrastRatio(ink, bg) < READABLE) { t = minOf(1.0, t + 0.05); ink = mix(fg, bg, t) }
            return ink
        }

        /** WCAG relative luminance of "#rrggbb", 0–1; null if it isn't one. */
        fun luminance(hex: String): Double? {
            if (!COLOR.matches(hex)) return null
            fun lin(i: Int): Double {
                val c = hex.substring(1 + 2 * i, 3 + 2 * i).toInt(16) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * lin(0) + 0.7152 * lin(1) + 0.0722 * lin(2)
        }

        /** WCAG contrast ratio of two "#rrggbb" colours, 1–21 (21 = black on white); 21 when
         *  either isn't a colour, so a bad value never reads as a warning. */
        fun contrastRatio(a: String, b: String): Double {
            val la = luminance(a) ?: return 21.0
            val lb = luminance(b) ?: return 21.0
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        /** A page dark enough that light ink reads better on it than black does. */
        fun isDark(hex: String): Boolean = (luminance(hex) ?: 1.0) < 0.18

        private fun finite(v: Double, d: Double) = if (v.isNaN() || v.isInfinite()) d else v
        private fun Double.round2() = Math.round(this * 100) / 100.0

        /** Locale-proof CSS number: "1.5", "18", never "1,5". */
        private fun num(v: Double): String =
            if (v == Math.floor(v)) v.toLong().toString()
            else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

/**
 * The Aa Type store: two profiles and a shared layer as JSON strings in "ledger_a11y", and the
 * one-time migration of the reader's old keys (first read wins; the old keys are left where they
 * were, so an older build still reads them).
 */
object ReadingTypes {
    private const val PREFS = "ledger_a11y"
    private const val KEY_SHARED = "type_shared"
    private const val KEY_MIGRATED = "type_migrated_v1"

    fun load(context: Context, profile: ReadingType.Profile): ReadingType {
        val p = prefs(context)
        migrateOnce(context)
        return ReadingType.resolve(profile, json(p.getString(KEY_SHARED, null)), json(p.getString(profile.key, null)))
    }

    /** Store [value] for [profile] — only what differs from the shared layer and the builtins. */
    fun save(context: Context, profile: ReadingType.Profile, value: ReadingType) {
        val p = prefs(context)
        migrateOnce(context)
        val d = ReadingType.diff(profile, json(p.getString(KEY_SHARED, null)), value)
        p.edit().putString(profile.key, d.toString()).apply()
    }

    /** Read, change, store, return — the one call a control makes. */
    fun edit(context: Context, profile: ReadingType.Profile, change: (ReadingType) -> ReadingType): ReadingType {
        val next = change(load(context, profile)).sanitized()
        save(context, profile, next)
        return next
    }

    /**
     * Change something for BOTH readers at once: the fields [change] touched (measured against
     * [from]'s current value) go into the shared layer and leave both profiles' overrides, so
     * books and articles both read the new value.
     */
    fun editBoth(context: Context, from: ReadingType.Profile, change: (ReadingType) -> ReadingType) {
        val p = prefs(context)
        migrateOnce(context)
        val before = load(context, from).toJson()
        val after = change(load(context, from)).sanitized().toJson()
        val shared = json(p.getString(KEY_SHARED, null)) ?: JSONObject()
        val touched = after.keys().asSequence().filter { before.opt(it) != after.opt(it) }.toList()
        if (touched.isEmpty()) return
        touched.forEach { shared.put(it, after.get(it)) }
        val e = p.edit().putString(KEY_SHARED, shared.toString())
        for (prof in ReadingType.Profile.values()) {
            val own = json(p.getString(prof.key, null)) ?: continue
            touched.forEach { own.remove(it) }
            e.putString(prof.key, own.toString())
        }
        e.apply()
    }

    /**
     * The article views' whole type block — the faces + the article profile. Both article builders
     * set their words with this and nothing else (Michael 2026-10-11: both article views agree),
     * so it is never empty: an untouched profile is the in-pane reader's old column exactly.
     */
    fun articleCss(context: Context): String {
        val t = load(context, ReadingType.Profile.ARTICLE)
        return ReadingFonts.fontFacesCss(context) + t.toArticleCss(ReadingFonts.familyCss(context, t))
    }

    /** The live swap of that block ([AaType.liveArticleJs]) — the face to fetch first rides along. */
    fun articleLiveJs(context: Context): String {
        val t = load(context, ReadingType.Profile.ARTICLE)
        val family = ReadingFonts.familyCss(context, t)
        return AaType.liveArticleJs(ReadingFonts.fontFacesCss(context) + t.toArticleCss(family), family)
    }

    /**
     * The article page's colour as an Android colour, for the article WebView's own ground: a
     * WebView paints white between documents, so every article opened on a Black page flashed
     * white, then black — two e-ink refreshes (review, Michael 2026-10-11).
     */
    fun articleGround(context: Context): Int =
        runCatching { android.graphics.Color.parseColor(load(context, ReadingType.Profile.ARTICLE).resolvedBg()) }
            .getOrDefault(android.graphics.Color.WHITE)

    /** Start [profile] over: its own layer goes, so it reads the shared layer and the builtins. */
    fun reset(context: Context, profile: ReadingType.Profile) {
        migrateOnce(context)
        prefs(context).edit().remove(profile.key).apply()
    }

    /** An imported font is gone — every layer that named it lets go, so the type falls back. */
    fun forgetFont(context: Context, fontId: String) {
        val p = prefs(context)
        val e = p.edit()
        for (key in listOf(KEY_SHARED) + ReadingType.Profile.values().map { it.key }) {
            val j = json(p.getString(key, null)) ?: continue
            if (j.optString("customFontId") == fontId) { j.remove("customFontId"); e.putString(key, j.toString()) }
        }
        e.apply()
    }

    private fun migrateOnce(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val old = context.getSharedPreferences(ReadingType.OLD_PREFS, Context.MODE_PRIVATE).all
        val migrated = ReadingType.migrateReaderPrefs(old)
        // Anything already in the book profile (there shouldn't be) wins over the migration.
        val book = json(p.getString(ReadingType.Profile.BOOK.key, null)) ?: JSONObject()
        for (k in migrated.keys()) if (!book.has(k)) book.put(k, migrated.get(k))
        p.edit()
            .putString(ReadingType.Profile.BOOK.key, book.toString())
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun json(s: String?): JSONObject? = s?.let { runCatching { JSONObject(it) }.getOrNull() }
}
