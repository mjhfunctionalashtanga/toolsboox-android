package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * All Stars widget: today's starred count per band — READ / WATCH / LISTEN / BOOKS / EMAIL — as
 * one compact row of five cells, counted off today's day JSON (imageElements on the "intake"
 * page, by intakeKind; EMAIL keeps its legacy "educate" storage key). Counts rather than gram
 * thumbnails on purpose: base64-decoding a register's worth of cards in the widget process is
 * exactly the weight a glance surface must not carry. A tap opens the All Stars page itself.
 */
class AllStarsWidgetProvider : CalendarWidgetProvider() {
    override val tapDest: String = "allstars"

    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderStars(context, date, widthDp, heightDp)
}
