package com.toolsboox.ot

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.toolsboox.R

/**
 * Reading size for the screens built out of plain views — correspondence, threads, boards.
 *
 * On e-ink this is what "zoom in" should actually mean. Scaling a rasterised view makes text
 * fuzzy and pushes it off the side; re-laying it out at a larger size keeps every glyph as sharp
 * as the panel can draw it and keeps the lines inside the margins. Pinch-zoom is left to images,
 * where there really is a picture to magnify.
 */
object ReadingSize {

    // The scale predates the shared appearance store and grew up in "MAIN" — alone among the
    // legibility keys, which all live in "ledger_a11y". That split was harmless day to day and a
    // trap for anything that migrates or exports the store. Home is now ledger_a11y; the first
    // read copies an old MAIN value forward once, and writes only ever land in the new store.
    private const val PREFS = "ledger_a11y"
    private const val LEGACY_PREFS = "MAIN"
    private const val KEY = "reading_scale"

    /** The steps offered, in order. 1.0 is whatever the screen was designed at. */
    val STEPS = floatArrayOf(1.0f, 1.15f, 1.35f, 1.6f, 1.9f)

    fun scale(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return prefs.getFloat(KEY, 1.0f)
        // One-time copy-forward from the legacy MAIN store, so a reader who had sized their
        // text up keeps that size across the move without ever noticing it happened.
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val value = legacy.getFloat(KEY, 1.0f)
        if (legacy.contains(KEY)) prefs.edit().putFloat(KEY, value).apply()
        return value
    }

    fun setScale(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, value).apply()
    }

    /** The next step up, wrapping back to the smallest once past the largest. */
    fun cycle(context: Context): Float {
        val current = scale(context)
        val idx = STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.001f }
        val next = STEPS[(if (idx < 0) 0 else idx + 1) % STEPS.size]
        setScale(context, next)
        return next
    }

    /** A short label for the control, e.g. "A 135%". */
    fun label(context: Context): String = "A  " + (scale(context) * 100).toInt() + "%"

    /**
     * Re-size every TextView under [root].
     *
     * Each view's original size is remembered the first time it's seen, so applying this twice
     * (a re-render, a step change) sets an absolute size rather than multiplying what's already
     * there — otherwise text would grow without bound.
     */
    fun apply(root: View, scale: Float) {
        if (root is TextView) {
            val base = (root.getTag(R.id.tag_base_text_size) as? Float)
                ?: root.textSize.also { root.setTag(R.id.tag_base_text_size, it) }
            root.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale)
            // Honour the chosen reading font here too, so every reading surface picks it up along
            // with the size. SYSTEM returns null and leaves the view's designed typeface untouched.
            LedgerFonts.typeface(root.context)?.let {
                root.setTypeface(it, root.typeface?.style ?: android.graphics.Typeface.NORMAL)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) apply(root.getChildAt(i), scale)
        }
    }

    /** Apply the stored size. */
    fun apply(root: View) = apply(root, scale(root.context))
}
