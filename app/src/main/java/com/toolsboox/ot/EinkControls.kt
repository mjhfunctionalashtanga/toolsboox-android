package com.toolsboox.ot

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.SwitchCompat

/**
 * The Settings screen's controls, for dialogs built in code.
 *
 * `values/styles.xml` gives the Settings page one toggle shape — `Settings.Toggle`: 17sp dark
 * text, a 52dp minimum height, full width — and every switch and tick-box on that page wears it.
 * The dialogs that grew up around the feeds and the mail room were built in Kotlin with stock
 * widgets instead: a `Spinner` whose popup is a list of 14sp rows, a `CheckBox` at the platform's
 * 48dp with 14sp text. On an e-ink panel with a stylus those are the two hardest things on the
 * screen to hit, and they look like a different app from the page one tap away.
 *
 * A style resource cannot be applied to a widget after construction, so these builders set the
 * same numbers by hand. Text is hard black, not `fgColor`: dialogs stay LIGHT cards in Dark mode
 * (the LedgerAlertDialog note in styles.xml), where `fgColor` is near-white and would vanish —
 * the same reason every dialog label in the app is a literal dark hex.
 */
object EinkControls {

    private const val TEXT_SP = 17f
    private const val ROW_DP = 52
    private const val INK = 0xFF000000.toInt()

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * A switch in the Settings.Toggle shape. [onChange] fires on a user flip only, never on the
     * initial [checked] — so a caller may persist in it without writing the loaded value back.
     */
    fun toggle(ctx: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setTextColor(INK)
            minHeight = dp(ctx, ROW_DP)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    /**
     * One big radio row per choice — the replacement for a `Spinner`, whose popup list is the
     * wrong shape for a stylus. The picked row is the filled dot; nothing here is colour-only.
     * [onPick] fires on a user pick only. Read the current pick back with [picked].
     */
    fun radioRows(ctx: Context, labels: List<String>, selected: Int, onPick: (Int) -> Unit = {}): RadioGroup {
        val group = RadioGroup(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val ids = IntArray(labels.size) { android.view.View.generateViewId() }   // unique per process — two groups in one dialog must not collide
        labels.forEachIndexed { i, label ->
            group.addView(AppCompatRadioButton(ctx).apply {
                id = ids[i]
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                setTextColor(INK)
                minHeight = dp(ctx, ROW_DP)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 8), 0, 0, 0)
            }, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (selected in labels.indices) group.check(ids[selected])
        group.setOnCheckedChangeListener { _, id -> ids.indexOf(id).let { if (it >= 0) onPick(it) } }
        group.tag = ids
        return group
    }

    /** The index picked in a [radioRows] group, or -1 when nothing is. */
    fun picked(group: RadioGroup): Int = (group.tag as? IntArray)?.indexOf(group.checkedRadioButtonId) ?: -1
}
