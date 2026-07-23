package com.toolsboox.ot

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * A small markdown renderer — headings, emphasis, lists, quotes, code, rules — to styled text.
 *
 * Not a full CommonMark engine and not trying to be: notes on a Boox are read, not compiled, so
 * this covers the marks people actually write and leaves the rest as plain text rather than
 * pulling in a library. Block structure line by line; inline emphasis within each line.
 *
 * Pure production of a [Spanned] — no view, no IO — so the notes screen just drops the result into
 * a TextView.
 */
object MarkdownRender {

    private const val H1 = 1.6f
    private const val H2 = 1.35f
    private const val H3 = 1.15f
    private val CODE_BG = Color.rgb(0xEE, 0xEE, 0xEE)
    private val MUTED = Color.rgb(0x66, 0x66, 0x66)
    private val RULE = Color.rgb(0xBB, 0xBB, 0xBB)

    fun render(markdown: String): Spanned {
        val out = SpannableStringBuilder()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var inFence = false

        for ((i, raw) in lines.withIndex()) {
            val line = raw
            if (i > 0) out.append("\n")

            // Fenced code block: everything between ``` lines is verbatim monospace.
            if (line.trimStart().startsWith("```")) { inFence = !inFence; continue }
            if (inFence) { appendSpan(out, line, TypefaceSpan("monospace")); continue }

            val trimmed = line.trim()

            // Horizontal rule.
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                appendSpan(out, "─".repeat(24), ForegroundColorSpan(RULE)); continue
            }

            // Headings.
            val h = Regex("""^(#{1,6})\s+(.*)$""").find(line)
            if (h != null) {
                val level = h.groupValues[1].length
                val size = when (level) { 1 -> H1; 2 -> H2; 3 -> H3; else -> 1.05f }
                val start = out.length
                inline(out, h.groupValues[2])
                out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            // Block quote.
            if (trimmed.startsWith("> ")) {
                val start = out.length
                inline(out, trimmed.removePrefix("> "))
                out.setSpan(QuoteSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(MUTED), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            // Bullet list — any indent, -, *, or +.
            val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""").find(line)
            if (bullet != null) {
                val indent = bullet.groupValues[1].replace("\t", "  ").length / 2
                val start = out.length
                inline(out, bullet.groupValues[2])
                out.setSpan(BulletSpan(24 + indent * 24), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            // Numbered list — keep the number, indent the wrap.
            val num = Regex("""^(\s*)(\d+)\.\s+(.*)$""").find(line)
            if (num != null) {
                val start = out.length
                out.append("${num.groupValues[2]}.  ")
                inline(out, num.groupValues[3])
                out.setSpan(LeadingMarginSpan.Standard(0, 40), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            inline(out, line)
        }
        return out
    }

    /** Inline emphasis within one line: **bold**, *italic* / _italic_, `code`. */
    private fun inline(out: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val bold = matchDelim(rest, "**") ?: matchDelim(rest, "__")
            if (bold != null) { appendSpan(out, bold.first, StyleSpan(Typeface.BOLD)); i += bold.second; continue }
            val code = matchDelim(rest, "`")
            if (code != null) {
                val start = out.length
                out.append(code.first)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(Color.rgb(0x33, 0x33, 0x33)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i += code.second; continue
            }
            val ital = matchDelim(rest, "*") ?: matchDelim(rest, "_")
            if (ital != null) { appendSpan(out, ital.first, StyleSpan(Typeface.ITALIC)); i += ital.second; continue }
            out.append(text[i]); i++
        }
    }

    /**
     * If [rest] opens with [delim] and closes it again, return the inner text and how many chars
     * to advance. Null when there's no matching close — a lone `*` stays literal.
     */
    private fun matchDelim(rest: String, delim: String): Pair<String, Int>? {
        if (!rest.startsWith(delim)) return null
        val close = rest.indexOf(delim, delim.length)
        if (close <= delim.length - 1 || close == delim.length) return null
        val inner = rest.substring(delim.length, close)
        if (inner.isBlank()) return null
        return inner to (close + delim.length)
    }

    private fun appendSpan(out: SpannableStringBuilder, text: String, span: Any) {
        val start = out.length
        out.append(text)
        out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
