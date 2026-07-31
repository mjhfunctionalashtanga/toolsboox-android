package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * Task-list widget: today's OPEN tasks as checkbox rows — ☐ + the ledger item's text, first six
 * undone with a "+N more" beneath, the date in the header, and the next timed calendar event on
 * top when one is still ahead. Reads text + done straight off today's day JSON (the same file the
 * page renderer loads), so it stays cheap; the render already runs off the main thread via
 * [CalendarWidgetProvider]'s goAsync plumbing. Display only — a tap opens today's day page,
 * where the checkboxes are real.
 */
class TaskListWidgetProvider : CalendarWidgetProvider() {
    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderTasks(context, date, widthDp, heightDp)
}
