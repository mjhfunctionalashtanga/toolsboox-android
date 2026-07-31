package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * Feed widget: the RSS headlines the app already surfaced — unread first, "source · age" beneath
 * each — with the unread count in the header. Reads the freshest cached entry list off disk (what
 * the Feed screen last wrote); never fetches from Miniflux. Inherits [CalendarWidgetProvider]'s
 * plumbing; only the bitmap differs.
 */
class FeedWidgetProvider : CalendarWidgetProvider() {
    override val tapDest: String = "feeds"

    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderFeed(context, widthDp, heightDp)
}
