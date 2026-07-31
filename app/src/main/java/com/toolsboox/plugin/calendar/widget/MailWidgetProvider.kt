package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import java.time.LocalDate

/**
 * Action-mail widget: the inbox's kept pile at a glance — STARRED messages first, then the most
 * recent, as "sender · subject" rows with a ★ on the starred ones. Draws from the persisted
 * starred store / seeded samples on disk; a widget never triggers an IMAP fetch. Inherits all the
 * plumbing from [CalendarWidgetProvider]; only the bitmap differs.
 */
class MailWidgetProvider : CalendarWidgetProvider() {
    override val tapDest: String = "mail"

    override fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        ListWidgetRenderer.renderMail(context, widthDp, heightDp)
}
