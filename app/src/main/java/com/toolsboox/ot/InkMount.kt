package com.toolsboox.ot

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The field-ledger mount for SHARED ink: anywhere a gram or a handwritten reply is
 * SHOWN rather than drawn — a reply's attachment, an inbound handwritten comment, a
 * picker thumbnail, a post's image — it gets mounted like a photo taped into a
 * scrapbook: a white mat, a hard black rule, and two bits of tape.
 *
 * Built for e-ink: solid black strokes on white only. No shadows, no gradients, no
 * alpha — those dither into mud on the panel. Skeuomorphic, but one line thick.
 */
object InkMount {

    /**
     * Wrap [child] in the mount. [taped] adds the two corner tape strips (off for
     * small thumbnails, where they'd crowd the image).
     */
    fun wrap(context: Context, child: View, taped: Boolean = true): View {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val mat = FrameLayout(context).apply {
            // A generous mat so the tape never sits on the words underneath it.
            setPadding(px(12), px(14), px(12), px(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(px(2), Color.BLACK)
            }
            addView(child)
        }
        if (!taped) return mat

        // The tape needs room to overhang the mat's top corners.
        val holder = FrameLayout(context).apply {
            setPadding(px(12), px(11), px(12), 0)
            addView(mat)
        }
        holder.addView(tape(context, -20f), tapeParams(context, start = true))
        holder.addView(tape(context, 20f), tapeParams(context, start = false))
        return holder
    }

    /** One strip of "tape": a white rectangle with a black rule, tilted. */
    private fun tape(context: Context, angle: Float): View {
        val dp = context.resources.displayMetrics.density
        return View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((1 * dp).toInt(), Color.BLACK)
            }
            rotation = angle
        }
    }

    private fun tapeParams(context: Context, start: Boolean): FrameLayout.LayoutParams {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        return FrameLayout.LayoutParams(px(40), px(14)).apply {
            gravity = (if (start) android.view.Gravity.START else android.view.Gravity.END) or
                android.view.Gravity.TOP
            if (start) marginStart = px(2) else marginEnd = px(2)
        }
    }

    /** Convenience for LinearLayout children: wrap and hand back with match-width params. */
    fun wrapInColumn(context: Context, child: View, taped: Boolean = true): View =
        wrap(context, child, taped).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
}
