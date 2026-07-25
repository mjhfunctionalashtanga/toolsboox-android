package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * Daily Pile widget: everything today gathered — tasks and events from the day's ledger, birthdays
 * from the rolodex, and the grams dropped on the day's pages (starred-mail grams among them), each
 * with its kind glyph and grams with a thumbnail. Reproduces the Daily Pile screen's read-side
 * gather off the same day files and honours its pick/dismiss/order state. All disk; no network.
 * Rolls over at midnight through [CalendarWidgetProvider]'s inherited alarm.
 */
class DailyPileWidgetProvider : CalendarWidgetProvider() {
    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderPile(context, date, widthDp, heightDp)
}
