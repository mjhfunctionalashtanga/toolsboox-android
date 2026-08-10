package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * Blog widget: direct access to the publish desk — write, schedule, or browse posts. A door
 * rather than a window (see [ListWidgetRenderer.renderPublish] for why there is nothing to
 * glance at); the tap lands on the WordPress compose surface.
 */
class BlogWidgetProvider : CalendarWidgetProvider() {
    override val tapDest: String = "publish"

    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderPublish(context, widthDp, heightDp)
}
