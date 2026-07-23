package com.toolsboox.ot

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.toolsboox.R

/**
 * Reading font for the screens built out of plain views — menus, correspondence, threads, boards.
 *
 * On e-ink the shape of the glyph matters as much as its size. Atkinson Hyperlegible was the only
 * face bundled; this widens that to a small set of purpose-built "Fast" e-ink faces (mirrored from
 * the iOS ReadingFont ids) plus a true "System" no-op that leaves every view exactly as designed.
 *
 * The choice is stored once and read back wherever text is laid out, so a single setting drives
 * both the menus (via [applyTree]) and the reading surfaces (via ReadingSize.apply).
 */
object LedgerFonts {

    private const val PREFS = "ledger_a11y"
    private const val KEY = "reading_font"

    /**
     * The faces on offer. [fontRes] is null for [SYSTEM] — the platform default, a deliberate
     * no-op so the app looks untouched unless the reader picks something. The [id]s mirror the
     * iOS ReadingFont rawValues where a matching face exists.
     */
    enum class Choice(val id: String, val label: String, val fontRes: Int?) {
        SYSTEM("system", "System", null),
        ATKINSON("atkinson", "Atkinson Hyperlegible", R.font.atkinson_hyperlegible),
        FAST_SANS("fastSans", "Fast Sans", R.font.fast_sans),
        FAST_SERIF("fastSerif", "Fast Serif", R.font.fast_serif),
        FAST_MONO("fastMono", "Fast Mono", R.font.fast_mono);
    }

    /** The stored choice, defaulting to [Choice.SYSTEM]. */
    fun current(context: Context): Choice {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Choice.SYSTEM.id)
        return choiceById(id)
    }

    /** Store the choice by its [Choice.id]. Unknown ids fall back to [Choice.SYSTEM]. */
    fun set(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choiceById(id).id).apply()
    }

    /** Resolve an [id] to a [Choice], defaulting to [Choice.SYSTEM]. */
    fun choiceById(id: String?): Choice =
        Choice.values().firstOrNull { it.id == id } ?: Choice.SYSTEM

    /**
     * The [Typeface] for the stored choice, or null for [Choice.SYSTEM] (and on any load failure).
     * A null return means "leave the view's typeface alone".
     */
    fun typeface(context: Context): Typeface? = typefaceFor(context, current(context))

    /** As [typeface] but for a specific [choice] — used when previewing a face the reader hasn't kept. */
    fun typefaceFor(context: Context, choice: Choice): Typeface? {
        val res = choice.fontRes ?: return null
        return runCatching { ResourcesCompat.getFont(context, res) }.getOrNull()
    }

    /**
     * Set the chosen face on every TextView under [root], preserving each view's existing style
     * (bold/italic) so weight is kept.
     *
     * When the choice is [Choice.SYSTEM] the typeface is null and this does NOTHING — each view is
     * left with the typeface it was designed with, never nulled.
     */
    fun applyTree(root: View) {
        val tf = typeface(root.context) ?: return
        fun walk(view: View) {
            if (view is TextView) {
                runCatching { view.setTypeface(tf, view.typeface?.style ?: Typeface.NORMAL) }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        runCatching { walk(root) }
    }
}
