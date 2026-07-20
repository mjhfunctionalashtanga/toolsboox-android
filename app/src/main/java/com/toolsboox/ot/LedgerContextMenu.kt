package com.toolsboox.ot

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.toolsboox.R
import kotlin.math.roundToInt

/**
 * The field-ledger context menu: the one popup component behind every finger
 * long-press menu on ink pages ("pen writes, finger manages").
 *
 * Styled for e-ink paper, matching the intake/pickings page language:
 * white card with a crisp 2dp black border (no fake elevation), a mono-caps
 * header over a solid rule (like the panel titles), items set in Atkinson
 * Hyperlegible separated by dashed hairlines (like the tap-to-type strips).
 * Pressed state is a plain inversion; no animations, no ripples. The card is
 * anchored at the press point and clamped to the window.
 */
object LedgerContextMenu {

    /** One tappable row of the menu. */
    data class Item(val label: String, val action: () -> Unit)

    /** The popup currently on screen, so a new press replaces it instead of stacking. */
    private var current: PopupWindow? = null

    /** Dismiss any open menu. Safe to call when none is showing. */
    fun dismissCurrent() {
        current?.let { runCatching { it.dismiss() } }
        current = null
    }

    /**
     * Show the menu anchored at a press point.
     *
     * Rows come in groups: a fine dashed rule separates rows within a group
     * (the intake-strip echo), a solid hairline separates the groups — the
     * page grammar of solid = structure, dashed = strips.
     *
     * @param anchor the view the press happened on (used for window placement)
     * @param pressX press x in the anchor view's coordinates
     * @param pressY press y in the anchor view's coordinates
     * @param title the mono-caps header label
     * @param groups the tappable rows, grouped
     */
    fun show(
        anchor: View, pressX: Float, pressY: Float, title: String, groups: List<List<Item>>,
        onShow: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null
    ) {
        val ctx = anchor.context ?: return
        dismissCurrent()   // never stack menus — a fresh press replaces the last one
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).roundToInt()

        val hyperlegible = ResourcesCompat.getFont(ctx, R.font.atkinson_hyperlegible)
        val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(236f)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2f), Color.BLACK)
            }
        }

        val textScale = com.toolsboox.ui.plugin.ScreenFragment.modalTextScale(ctx)

        // Header: mono caps over a solid rule, echoing the page panel titles.
        card.addView(TextView(ctx).apply {
            text = title
            typeface = monoBold
            textSize = 13f * textScale
            letterSpacing = 0.18f
            setTextColor(Color.BLACK)
            setPadding(dp(18f), dp(13f), dp(18f), dp(9f))
        })
        card.addView(
            View(ctx).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2f))
        )

        lateinit var popup: PopupWindow

        groups.filter { it.isNotEmpty() }.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                // Solid hairline between groups.
                card.addView(
                    View(ctx).apply { setBackgroundColor(Color.argb(210, 0, 0, 0)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1.5f))
                )
            }
            group.forEachIndexed { index, item ->
                if (index > 0) {
                    // Fine dashed rule within a group.
                    card.addView(
                        DashRule(ctx),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2f)).apply {
                            marginStart = dp(16f)
                            marginEnd = dp(16f)
                        }
                    )
                }
                card.addView(TextView(ctx).apply {
                    text = item.label
                    typeface = hyperlegible
                    textSize = 17f * textScale
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(54f)
                    setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
                    // Pressed = plain inversion (white on black); no ripple.
                    setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                            intArrayOf(Color.WHITE, Color.BLACK)
                        )
                    )
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.BLACK))
                        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                    }
                    setOnClickListener {
                        popup.dismiss()
                        item.action()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        // Measure unconstrained so MATCH_PARENT rows don't balloon the popup to
        // the window width, then hand the popup the exact card size.
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        // NON-focusable on purpose: a focusable popup grabs a focus window, so the *next*
        // finger-down (a second long-press) is swallowed to dismiss it and never reaches the
        // surface — the menu "worked once, then died." Non-focusable lets that press dismiss
        // this menu (via ACTION_OUTSIDE) AND still land on the surface to start a fresh press.
        popup = PopupWindow(card, card.measuredWidth, card.measuredHeight, false).apply {
            isOutsideTouchable = true
            elevation = 0f
            animationStyle = 0
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { if (current === this) current = null; onDismiss?.invoke() }
        }
        current = popup

        // Anchor the card's top-left just off the fingertip, clamped on-window.
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val root = anchor.rootView
        val margin = dp(8f)
        val x = (location[0] + pressX.roundToInt() + dp(6f))
            .coerceIn(margin, (root.width - card.measuredWidth - margin).coerceAtLeast(margin))
        val y = (location[1] + pressY.roundToInt() + dp(6f))
            .coerceIn(margin, (root.height - card.measuredHeight - margin).coerceAtLeast(margin))
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        onShow?.invoke()   // pause the Onyx raw-drawing pipeline, or the pen freezes on the menu
    }

    /**
     * A dashed hairline between menu items, echoing the intake/pickings
     * dashed strips. Software layer so the dash renders under acceleration.
     */
    private class DashRule(context: Context) : View(context) {

        private val density = context.resources.displayMetrics.density

        private val paint = Paint().apply {
            color = Color.argb(120, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            val middle = height / 2f
            canvas.drawLine(0f, middle, width.toFloat(), middle, paint)
        }
    }
}
