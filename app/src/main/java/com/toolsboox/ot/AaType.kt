package com.toolsboox.ot

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * The Aa Type panel's arithmetic — every dial's step, range and words — kept apart from the views
 * so it can be tested and so the book reader and both article readers move by the same amounts
 * (Michael, 2026-10-11: "Aa type panel is good for that"). Each step takes a [ReadingType] and a
 * direction (−1 / +1) and returns the next one; the store sanitises again on save.
 */
object AaType {

    /** How long the page waits after the last tap before it re-lays — one e-ink refresh per run
     *  of taps (the spec's ~300 ms; book reader and both article readers share it, so they can be
     *  tuned together on the device). */
    const val LIVE_DEBOUNCE_MS = 300L

    // ---- the dials -----------------------------------------------------------------------

    /** Size moves in tens, as the reader's A−/A+ always did. */
    fun stepSize(t: ReadingType, dir: Int) =
        t.copy(sizePct = (t.sizePct + 10 * dir).coerceIn(ReadingType.SIZE_MIN, ReadingType.SIZE_MAX))

    /** EinkBro's bold boost is the weight going up a named step. */
    fun stepWeight(t: ReadingType, dir: Int) = t.copy(weight = (snap(t.weight, 100) + 100 * dir).coerceIn(100, 900))

    /** Counted in tenths, so a run of taps never drifts (1.0×–3.0×). */
    fun stepLine(t: ReadingType, dir: Int): ReadingType {
        val tenths = (Math.round(t.line * 10).toInt() + dir).coerceIn(10, 30)
        return t.copy(line = tenths / 10.0)
    }

    fun stepParagraph(t: ReadingType, dir: Int) = t.copy(paragraph = step(t.paragraph, 0.25, dir, 0.0, 4.0))
    fun stepIndent(t: ReadingType, dir: Int) = t.copy(indent = step(t.indent, 0.5, dir, 0.0, 6.0))
    fun stepWord(t: ReadingType, dir: Int) = t.copy(word = step(t.word, 0.5, dir, -4.0, 16.0))
    fun stepLetter(t: ReadingType, dir: Int) = t.copy(letter = step(t.letter, 0.25, dir, -2.0, 8.0))
    fun stepMargin(t: ReadingType, dir: Int) = t.copy(marginPx = (snap(t.marginPx, 4) + 4 * dir).coerceIn(0, 200))

    /** Column widths worth stopping at, narrow to wide, then "no cap" (0) at the top. A width
     *  set elsewhere snaps to its nearest rung before it moves. */
    val WIDTHS = listOf(400, 480, 560, 640, 720, 800, 900, 1000, 1200, 1400, 1600, 0)

    fun stepWidth(t: ReadingType, dir: Int): ReadingType {
        val i = widthRung(t.maxWidthPx)
        return t.copy(maxWidthPx = WIDTHS[(i + dir).coerceIn(0, WIDTHS.lastIndex)])
    }

    private fun widthRung(px: Int): Int {
        if (px <= 0) return WIDTHS.lastIndex
        return WIDTHS.dropLast(1).withIndex().minByOrNull { abs(it.value - px) }!!.index
    }

    // ---- faces ---------------------------------------------------------------------------

    /** One face in the panel's list. Exactly one of [family] / [customFontId] says what it is:
     *  choosing a bundled face or a generic clears the imported one; choosing an imported face
     *  keeps [ReadingType.family] as its fallback. */
    data class FaceChoice(val family: String, val customFontId: String, val label: String) {
        val isImported get() = customFontId.isNotBlank()
        fun applyTo(t: ReadingType) =
            if (isImported) t.copy(customFontId = customFontId) else t.copy(family = family, customFontId = "")
    }

    /** The document's own face, the device's three generics, the bundled faces, then the
     *  imported ones ([imported] = id to label). */
    fun faces(profile: ReadingType.Profile, imported: List<Pair<String, String>>): List<FaceChoice> =
        listOf(FaceChoice("", "", if (profile == ReadingType.Profile.BOOK) "The book's own" else "The page's own")) +
            listOf(
                FaceChoice("serif", "", "Serif (device)"),
                FaceChoice("sans-serif", "", "Sans (device)"),
                FaceChoice("monospace", "", "Mono (device)"),
            ) +
            ReadingFonts.BUNDLED.map { FaceChoice(it.id, "", it.label) } +
            imported.map { (id, label) -> FaceChoice("", id, label) }

    /** Where [t] stands in [choices]: its imported face while that is still listed, else its family. */
    fun faceIndex(t: ReadingType, choices: List<FaceChoice>): Int {
        if (t.customFontId.isNotBlank()) {
            val i = choices.indexOfFirst { it.customFontId == t.customFontId }
            if (i >= 0) return i
        }
        return choices.indexOfFirst { !it.isImported && it.family == t.family }.coerceAtLeast(0)
    }

    /** ‹ / › walk the list and stop at its ends (no wrap — a wrap is a surprise on e-ink). */
    fun stepFace(t: ReadingType, dir: Int, choices: List<FaceChoice>): ReadingType {
        if (choices.isEmpty()) return t
        return choices[(faceIndex(t, choices) + dir).coerceIn(0, choices.lastIndex)].applyTo(t)
    }

    // ---- words ---------------------------------------------------------------------------

    fun sizeLabel(t: ReadingType) = "${t.sizePct}%"

    /** The weight's name — the dial moves in hundreds, so the name IS the number ("700 · Bold"
     *  squeezed the row's own label to nothing at Large text on a Palma). A weight set off the
     *  hundreds elsewhere shows as its number. */
    fun weightLabel(t: ReadingType) = if (t.weight % 100 != 0) "${t.weight}" else when (t.weight) {
        100 -> "Thin"; 200 -> "Extra light"; 300 -> "Light"; 400 -> "Regular"; 500 -> "Medium"
        600 -> "Semibold"; 700 -> "Bold"; 800 -> "Extra bold"; else -> "Black"
    }

    fun lineLabel(t: ReadingType) = String.format(Locale.US, "%.1f×", t.line)
    fun emLabel(v: Double) = "${num(v)} em"
    fun pxLabel(v: Double) = "${num(v)} px"
    fun widthLabel(t: ReadingType) = if (t.maxWidthPx <= 0) "No limit" else "${t.maxWidthPx} px"

    /** A theme's word on the one-row strip at the top of the panel — short enough that all five
     *  sit in one row on a Palma; [themeLabel] says the rest under Colour. */
    fun themeChip(theme: String) = when (theme) {
        "default" -> "Default"; "sepia" -> "Sepia"; "gray" -> "Grey"; "black" -> "Black"
        "contrast" -> "Contrast"; else -> theme
    }

    /** The Colour section's themes, in the panel's words. */
    fun themeLabel(theme: String, profile: ReadingType.Profile) = when (theme) {
        "default" -> if (profile == ReadingType.Profile.BOOK) "Default · the book's colours" else "Default · black on white"
        "sepia" -> "Sepia"
        "gray" -> "Grey"
        "black" -> "Black · light text"
        "contrast" -> "High contrast · black text, white page, always"
        else -> theme
    }

    /** Text colours that read on e-ink and on glass, darkest first. */
    val TEXT_COLOURS = listOf(
        "#000000" to "Black", "#1a1a1a" to "Near black", "#333333" to "Dark grey",
        "#5b4636" to "Sepia ink", "#cfcfcf" to "Light grey", "#ffffff" to "White",
    )

    /** Page colours, lightest first. */
    val PAGE_COLOURS = listOf(
        "#ffffff" to "White", "#f8f5ec" to "Paper", "#f4ecd8" to "Sepia",
        "#c9c9c9" to "Grey", "#1e1e1e" to "Charcoal", "#000000" to "Black",
    )

    /** "#rrggbb" from what someone typed ("333", "#333333", " 1A1A1A "), or null. */
    fun parseHex(typed: String): String? {
        val s = typed.trim().removePrefix("#")
        val six = when {
            Regex("^[0-9a-fA-F]{6}$").matches(s) -> s
            Regex("^[0-9a-fA-F]{3}$").matches(s) -> s.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return "#" + six.lowercase()
    }

    // ---- articles, live ------------------------------------------------------------------

    /** The id of the article views' replaceable type block. */
    const val ARTICLE_STYLE_ID = "ledger-type"

    /** The block as an element, for an article builder's <head>, after [ARTICLE_FRAME_CSS]. The
     *  live script swaps its text in place ([liveArticleJs]). */
    fun articleStyleTag(css: String) = "<style id=\"$ARTICLE_STYLE_ID\">$css</style>"

    /**
     * What both article builders own: pictures and players fit the column, code wraps, the byline
     * keeps its gap. No face, size, spacing, width or colour — every word is set by the type
     * block, so the in-pane reader and the standalone one read the same (Michael 2026-10-11).
     */
    const val ARTICLE_FRAME_CSS = """
      .meta { margin-bottom: 16px; }
      img { max-width: 100%; height: auto; }
      iframe { max-width: 100%; border: 0; }
      @supports (aspect-ratio: 16 / 9) { iframe { width: 100%; height: auto; aspect-ratio: 16 / 9; } }
      pre, code { white-space: pre-wrap; }
    """

    /**
     * The script that swaps the article's type block for [css] — no reload. Only a page that
     * carries the block (an article the app built) is touched; a live web page or a letter has
     * none and is left alone. Unchanged text is not rewritten, so a repeat costs no re-lay
     * (e-ink: no refresh for nothing).
     */
    fun liveArticleJs(css: String, family: String = ""): String =
        "(function(c,f){var s=document.getElementById('$ARTICLE_STYLE_ID');" +
            "if(!s||s.textContent===c)return;window.__ledgerTypeWant=c;" +
            // The face is fetched BEFORE the swap (its @font-face is already on the page), so the
            // text is laid once in it — swapping first drew blank text (font-display: block),
            // then the text: two refreshes (review, Michael 2026-10-11). Only the newest wins.
            "var go=function(){if(window.__ledgerTypeWant===c&&s.textContent!==c)s.textContent=c;};" +
            "if(f&&document.fonts&&document.fonts.load){document.fonts.load('1em '+f).then(go,go);}else go();" +
            "})(${JSONObject.quote(css)},${JSONObject.quote(family)})"

    // ---- helpers -------------------------------------------------------------------------

    private fun step(v: Double, by: Double, dir: Int, min: Double, max: Double): Double {
        val rungs = Math.round(v / by)
        return ((rungs + dir) * by).coerceIn(min, max).let { Math.round(it * 100) / 100.0 }
    }

    private fun snap(v: Int, by: Int) = Math.round(v / by.toDouble()).toInt() * by

    private fun num(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}
