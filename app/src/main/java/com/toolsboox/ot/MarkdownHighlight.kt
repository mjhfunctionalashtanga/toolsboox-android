package com.toolsboox.ot

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Live markdown styling, applied to the editor's own text as you type.
 *
 * The difference from [MarkdownRender] is that this changes NOTHING about the text — it only
 * styles the characters already there, markdown marks and all, so a heading grows and **bold**
 * goes bold while the `#` and `**` stay visible and editable. That is what makes it a live
 * highlighter rather than a preview: the note you are writing simply looks like what it will be.
 *
 * Idempotent: every pass clears the spans it added last time (tagged so nothing else is touched)
 * and lays them down fresh, so it can run on every keystroke.
 */
object MarkdownHighlight {

    private val HEADING = Regex("""(?m)^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""(?m)^(\s*)([-*+])\s+""")
    private val QUOTE = Regex("""(?m)^>\s+.*$""")
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val ITALIC = Regex("""(?<![*_\w])([*_])(?=\S)([^*_]+?)(?<=\S)\1(?![*_\w])""")
    private val CODE = Regex("""`([^`\n]+)`""")

    fun apply(text: Editable) {
        // Remove only the span TYPES we apply — selection and IME composing use different classes,
        // so this never disturbs the cursor or in-flight input.
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            if (span is RelativeSizeSpan || span is StyleSpan || span is ForegroundColorSpan ||
                span is LeadingMarginSpan.Standard || span is TypefaceSpan
            ) text.removeSpan(span)
        }

        HEADING.findAll(text).forEach { m ->
            val level = m.groupValues[1].length
            val size = when (level) { 1 -> 1.6f; 2 -> 1.35f; 3 -> 1.15f; else -> 1.05f }
            set(text, RelativeSizeSpan(size), m.range.first, m.range.last + 1)
            set(text, StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1)
        }
        QUOTE.findAll(text).forEach { m ->
            set(text, ForegroundColorSpan(MUTED), m.range.first, m.range.last + 1)
            set(text, LeadingMarginSpan.Standard(24), m.range.first, m.range.last + 1)
        }
        BULLET.findAll(text).forEach { m ->
            // Colour the marker so a list reads as a list; the text stays plain.
            set(text, ForegroundColorSpan(ACCENT), m.range.first, m.range.last + 1)
        }
        BOLD.findAll(text).forEach { m -> set(text, StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1) }
        ITALIC.findAll(text).forEach { m -> set(text, StyleSpan(Typeface.ITALIC), m.range.first, m.range.last + 1) }
        CODE.findAll(text).forEach { m ->
            set(text, TypefaceSpan("monospace"), m.range.first, m.range.last + 1)
            set(text, ForegroundColorSpan(CODE_INK), m.range.first, m.range.last + 1)
        }
    }

    private val MUTED = Color.rgb(0x66, 0x66, 0x66)
    private val ACCENT = Color.rgb(0x44, 0x44, 0x44)
    private val CODE_INK = Color.rgb(0x33, 0x33, 0x33)

    private fun set(text: Editable, span: Any, start: Int, end: Int) {
        if (start in 0..end && end <= text.length)
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
