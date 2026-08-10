package com.toolsboox.ot

import android.content.Context
import android.graphics.Color

/**
 * The annotation pad's pen, REMEMBERED.
 *
 * Michael's punchlist, 2026-08-08 and 08-11 — three complaints with one cause:
 *
 *   "persistent pen selection in handwritten annotation"
 *   "pen selection in notes won't save line thickness"
 *   "in annotation, calligraphy pen (or really any pen) should have persistence / remember selection"
 *
 * The annotation bar was constructed fresh at every open with its width index hard-started at
 * medium and its colour at black. So the pad had no memory at all: every annotation began with the
 * pen someone chose in the source code rather than the one you were using a minute ago.
 *
 * Why this matters more than it sounds: a pen is not a preference, it is a HABIT. Somebody who
 * writes in fine black and corrects in red is not choosing twice per page; they are reaching for
 * the tool they always reach for, and an app that resets it is asking them to make a decision they
 * had already made. That is the same rule that put the last template on ＋ New note.
 *
 * Deliberately its own tiny store rather than a field on the fragment: the annotation pad is
 * summoned from several surfaces (the reader, the intake panel, the reply pads), and a pen that
 * only persisted within one of them would be a stranger sort of broken than no memory at all.
 */
object AnnotationPen {

    private const val PREFS = "ledger_annotation_pen"
    private const val KEY_WIDTH = "width_index"
    private const val KEY_COLOR = "color"

    /** Medium — the middle of the fine/medium/bold ladder, and the right first guess. */
    private const val DEFAULT_WIDTH_INDEX = 1

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun widthIndex(context: Context): Int =
        prefs(context).getInt(KEY_WIDTH, DEFAULT_WIDTH_INDEX)

    fun setWidthIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_WIDTH, index).apply()
    }

    fun color(context: Context): Int = prefs(context).getInt(KEY_COLOR, Color.BLACK)

    fun setColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR, color).apply()
    }
}
