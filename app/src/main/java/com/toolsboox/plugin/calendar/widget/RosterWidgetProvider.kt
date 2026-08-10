package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * Event Attendees widget: direct access to the roster. A door rather than a window — the roster
 * is live booking data a widget process must never fetch (see [ListWidgetRenderer.renderRoster]);
 * the tap lands on the Event Attendees surface, which fetches with a real session.
 */
class RosterWidgetProvider : CalendarWidgetProvider() {
    override val tapDest: String = "roster"

    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderRoster(context, widthDp, heightDp)
}
