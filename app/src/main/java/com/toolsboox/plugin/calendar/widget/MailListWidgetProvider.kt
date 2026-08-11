package com.toolsboox.plugin.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.toolsboox.R
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.ui.main.MainActivity

/**
 * "Ledger Mail List" — the Mail follow-on the Feed List widget's comment promised: the same
 * scrollable RemoteViews ListView, over the inbox instead of the feed cache.
 *
 * The bitmap Mail widget ([MailWidgetProvider]) stays untouched — one picture, tappable only as
 * a whole. This one scrolls natively and each row deep-links to ITS letter: the fill-in intent
 * carries the message id, MainActivity's widgetDest router drops it into
 * [com.toolsboox.plugin.feeds.ui.FeedSelection.pendingMailOpenId] — the same one-shot handoff
 * the gram router's mail:// links ride — and the feeds pane (wearing The Mail lens) opens the
 * letter after its warm load.
 *
 * Data contract is the bitmap widget's, kept identical on purpose: [InboxStore.warm] first (the
 * widget-process rule — an un-warmed store shows only the keep piles), then [InboxStore.messages]
 * minus Sent, starred first, newest within. Two mail widgets disagreeing about order would read
 * as one of them being wrong. Nothing here fetches — no IMAP, ever.
 */
class MailListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.mail_list_widget_layout)

            // The remote adapter. The widget id rides in the data URI as well as the extras —
            // RemoteViewsService caches factories per-Intent and Intent.filterEquals ignores
            // extras, so the URI is what keeps each placed widget its own factory.
            val adapterIntent = Intent(context, MailListWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.mail_list_widget_list, adapterIntent)
            views.setEmptyView(R.id.mail_list_widget_list, R.id.mail_list_widget_empty)

            // Header tap: the mail surface itself, same door as the bitmap Mail widget.
            views.setOnClickPendingIntent(
                R.id.mail_list_widget_header,
                CalendarWidgetProvider.openSurfaceIntent(context, "mail")
            )

            // Row-tap TEMPLATE — MUTABLE is load-bearing (Android 12+ silently drops fill-in
            // extras into an immutable PendingIntent, turning every row back into a plain
            // "open mail"). The base intent carries no extras; the fill-in supplies widgetDest
            // and the message id together.
            val rowTemplate = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setPendingIntentTemplate(
                R.id.mail_list_widget_list,
                PendingIntent.getActivity(
                    context, "mailListRow".hashCode(), rowTemplate,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            appWidgetManager.updateAppWidget(id, views)
        }
        // Re-binding the adapter does not reload its data — this makes a refreshAll poke
        // (or the 30-minute tick) actually re-read the store.
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.mail_list_widget_list)
    }
}

/** The service the launcher binds to for rows. All the work is in the factory. */
class MailListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MailListRemoteViewsFactory(applicationContext)
}

/**
 * Rows for the Mail List widget. [onDataSetChanged] and [getViewAt] run on a binder thread,
 * which satisfies [InboxStore.warm]'s one precondition (never the main thread) — the same
 * warm-first rule the bitmap renderer follows in [ListWidgetRenderer.renderMail].
 */
class MailListRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    companion object {
        /** A scroll's worth, not an archive — same budget as the Feed List rows: the launcher
         *  marshals every row across the binder. */
        private const val MAX_ROWS = 50
    }

    private var messages: List<InboxMessage> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        // Warm first — the widget process has its own memory; without this the list would show
        // only the keep piles while the app itself shows a year of mail.
        runCatching { InboxStore.warm(context) }
        val all = runCatching { InboxStore.messages(context) }
            .getOrDefault(emptyList())
            // Sent mail is keep-forever so messages() returns it — on a widget headed "Mail" it
            // would read as arriving mail from yourself (the bitmap widget's rule, kept).
            .filter { !InboxStore.isSent(it.id) }
        val (starred, rest) = all.partition {
            runCatching { InboxStore.isStarred(context, it.id) }.getOrDefault(false)
        }
        messages = (starred + rest).take(MAX_ROWS)
    }

    override fun onDestroy() {
        messages = emptyList()
    }

    override fun getCount(): Int = messages.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.mail_list_widget_row)
        val m = messages.getOrNull(position) ?: return views

        // The bitmap widget's marker grammar, kept: ★ starred, ● unread, · read.
        views.setTextViewText(
            R.id.mail_row_marker, when {
                runCatching { InboxStore.isStarred(context, m.id) }.getOrDefault(false) -> "★"
                !runCatching { InboxStore.isRead(context, m.id) }.getOrDefault(true) -> "●"
                else -> "·"
            }
        )
        views.setTextViewText(R.id.mail_row_title, m.subject.ifBlank { "(no subject)" })
        val sender = m.fromName.ifBlank { m.fromEmail.substringBefore('@') }.ifBlank { "Unknown" }
        views.setTextViewText(R.id.mail_row_meta, "$sender · ${ListWidgetRenderer.relativeAge(m.date)}")

        // The per-row deep link: the router sets FeedSelection.pendingMailOpenId and the mail
        // lens opens this letter after its warm load — the same pending-open idiom everywhere.
        views.setOnClickFillInIntent(R.id.mail_row_root, Intent().apply {
            putExtra("widgetDest", "mail")
            putExtra("mailOpenId", m.id)
        })

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        messages.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
