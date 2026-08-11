package com.toolsboox.plugin.calendar.widget

import android.content.Context
import kotlin.math.sqrt

/**
 * The one RemoteViews bitmap ceiling, computed instead of guessed.
 *
 * Both widget renderers used to cap their pushed bitmap at a fixed 768px longest side. That
 * number was a superstition standing in for the real rule, and on a modern launcher it is the
 * whole reason the widgets look soft: a 4-cell widget on a density-3 screen is ~1200px tall, so
 * the renderer shrank a crisp bitmap to 768 and the launcher's fitCenter ImageView stretched it
 * right back up — a round trip through blur that nothing required.
 *
 * The real rule (AppWidgetServiceImpl, unchanged since KitKat): one RemoteViews update may carry
 * at most 1.5 × the device screen's pixel AREA in bitmap memory — i.e. bytes ≤ 1.5 × screenW ×
 * screenH × 4, and ARGB_8888 is 4 bytes/px, so in PIXELS the ceiling is 1.5 × screenW × screenH.
 * The budget here is 1.0 × screen area: a third of headroom held back on purpose, because the
 * layout's other drawables count against the same transaction, a launcher can report its own
 * display metrics, and an overshoot doesn't degrade — it THROWS, and a widget that crashes its
 * update is worse than one drawn a shade softer.
 *
 * In practice the cap almost never binds now: the fit-scale upstream already bounds the raw
 * bitmap to the widget's own dp × density box, and a widget is smaller than the screen it sits
 * on — so the common case ships at the widget's true pixel size, which is the fix. The cap is
 * the seatbelt for the uncommon case (a launcher handing back outsized OPTION_APPWIDGET bounds),
 * and it scales by AREA — sqrt on each side — because area is what the ceiling is written in;
 * capping the longest side alone over-shrinks wide-and-short widgets and under-protects square
 * ones.
 */
object WidgetBitmapBudget {

    /** Pure arithmetic, so the test can pin it without a Context: the scale (≤ 1) that brings
     *  rawW×rawH inside one screen's area. 1f when it already fits (never upscales). */
    fun capScale(screenW: Int, screenH: Int, rawW: Int, rawH: Int): Float {
        if (rawW <= 0 || rawH <= 0 || screenW <= 0 || screenH <= 0) return 1f
        val budget = screenW.toLong() * screenH.toLong()
        val raw = rawW.toLong() * rawH.toLong()
        if (raw <= budget) return 1f
        return sqrt(budget.toDouble() / raw.toDouble()).toFloat()
    }

    /** The same scale against this device's real screen. */
    fun capScale(context: Context, rawW: Int, rawH: Int): Float {
        val dm = context.resources.displayMetrics
        return capScale(dm.widthPixels, dm.heightPixels, rawW, rawH)
    }
}
