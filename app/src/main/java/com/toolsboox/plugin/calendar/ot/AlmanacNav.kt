package com.toolsboox.plugin.calendar.ot

import android.content.Context

/**
 * Shared state for the "hide the almanac navigator" toggle.
 *
 * The almanac strip — the day/week/month/quarter/year navigator drawn by
 * [com.toolsboox.plugin.calendar.ui.CalendarNavBarHost] and the day page's own navigator — costs
 * a band of vertical space at the top of every surface it rides on. A chevron collapses it so the
 * page (or the mail list) reclaims that band, and the choice is remembered across sessions in the
 * app-wide "ledger_ui" prefs so it holds wherever the strip appears (the day page and Mail today).
 */
object AlmanacNav {
    private const val PREFS = "ledger_ui"
    private const val KEY_HIDDEN = "almanac_nav_hidden"

    /** True when the user has collapsed the almanac navigator strip. */
    fun isHidden(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false)

    /** Persist the collapsed/expanded choice. */
    fun setHidden(context: Context, hidden: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HIDDEN, hidden).apply()
    }
}
