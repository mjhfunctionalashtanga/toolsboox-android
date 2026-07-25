package com.toolsboox.ot

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The semantic surfaces' shared card kit — the white card and the action chip that Sprouts,
 * Missed Rhizomes and Quick Wins each grew a verbatim copy of. One drawer, one look: a card
 * is white with a 1dp #BBBBBB hairline at r10; a chip carries a SOLID 1dp black stroke so it
 * reads as a button on a Boox panel (the old 7%-black fill alone vanished in monochrome —
 * the audit's e-ink-invisible chips).
 *
 * Lives in ot/ next to [InkMount] because it is the same kind of thing: a small, named piece
 * of the design language that fragments consume rather than re-invent.
 */
object SemanticCards {

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** The card ground alone — for a caller that builds its own layout on top of it. */
    fun cardBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        setStroke(dp(context, 1), 0xFFBBBBBB.toInt())
        cornerRadius = dp(context, 10).toFloat()
    }

    /**
     * One semantic card: a vertical column on the white/#BBBBBB/r10 ground with the surfaces'
     * shared padding and margins. The caller adds its rows and its action strip.
     */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        background = cardBackground(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(context, 3), dp(context, 4), dp(context, 3), dp(context, 5)) }
    }

    /**
     * One action chip: the tappable verb on a card's bottom strip ("⁂ Rhizome", "🌿 Pick").
     * Keeps the soft 7%-black fill for LCD warmth, but the 1dp solid black stroke is what
     * makes it survive monochrome.
     */
    fun actionChip(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f; setTextColor(0xFF000000.toInt())
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            background = GradientDrawable().apply {
                setColor(0x11000000)
                setStroke(dp(context, 1), 0xFF000000.toInt())
                cornerRadius = dp(context, 8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(context, 8), 0) }
            setOnClickListener { onClick() }
        }
}
