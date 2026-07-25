package com.toolsboox.plugin.calendar.ot

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import java.text.Normalizer

/**
 * The text half of Ledger search — the matching, windowing and highlighting that make typing a
 * few letters find the thing regardless of case, accents or where in the passage it hides.
 *
 * Matching is token-AND substring over a FOLDED haystack (lowercased, diacritics stripped), so
 * "medit sut" finds "Meditación on the Sutras": every token must appear somewhere, and a token is
 * satisfied by any word it prefixes or sits inside. The folding keeps an index map back to the
 * original string, so highlights land on the real characters even when accents changed lengths.
 * Pure functions, no Android state — unit-testable like the corpus service it searches beside.
 */
object LedgerSearch {

    /** Split a query into folded tokens; the unit every match/highlight works in. */
    fun tokens(query: String): List<String> =
        fold(query).folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** True when EVERY token appears somewhere in [haystack] (case/diacritic-insensitive). */
    fun matches(tokens: List<String>, haystack: String): Boolean {
        if (tokens.isEmpty()) return false
        val h = fold(haystack).folded
        return tokens.all { h.contains(it) }
    }

    /**
     * A snippet windowed around the FIRST token hit — the row shows the match, not the first
     * 240 characters of an article that matched on page three. Whole text returned when it
     * already fits or nothing matched (the semantic layer surfaces things words didn't).
     */
    fun window(text: String, tokens: List<String>, radius: Int = 120): String {
        val t = text.trim()
        if (t.length <= radius * 2) return t
        val f = fold(t)
        val hit = tokens.asSequence().map { f.folded.indexOf(it) }.filter { it >= 0 }.minOrNull()
            ?: return t.take(radius * 2).trimEnd() + "…"
        val orig = f.map.getOrElse(hit) { 0 }
        val from = (orig - radius).coerceAtLeast(0)
        val to = (orig + radius).coerceAtMost(t.length)
        return (if (from > 0) "…" else "") + t.substring(from, to).trim() + (if (to < t.length) "…" else "")
    }

    /** [text] with every token occurrence bolded — the match made visible in the row. */
    fun emphasize(query: String, text: CharSequence): CharSequence {
        val toks = tokens(query)
        if (toks.isEmpty() || text.isBlank()) return text
        val s = text.toString()
        val f = fold(s)
        val out = SpannableString(s)
        for (tok in toks) {
            var at = f.folded.indexOf(tok)
            var guard = 0
            while (at >= 0 && guard++ < 40) {
                val from = f.map.getOrElse(at) { break }
                val to = f.map.getOrElse(at + tok.length - 1) { s.length - 1 } + 1
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), from,
                    to.coerceAtMost(s.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                at = f.folded.indexOf(tok, at + 1)
            }
        }
        return out
    }

    /** A folded string plus, per folded char, the index of the original char it came from. */
    class Folded(val folded: String, val map: IntArray)

    /** Lowercase + strip combining marks, char by char so highlight offsets survive the fold. */
    fun fold(s: String): Folded {
        val sb = StringBuilder(s.length)
        val map = ArrayList<Int>(s.length)
        for (i in s.indices) {
            val d = Normalizer.normalize(s[i].lowercaseChar().toString(), Normalizer.Form.NFD)
            for (c in d) {
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(c); map.add(i)
            }
        }
        return Folded(sb.toString(), map.toIntArray())
    }
}
