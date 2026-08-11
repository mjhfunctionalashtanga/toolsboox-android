package com.toolsboox.plugin.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.toolsboox.R
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.plugin.feeds.nw.FeedCache
import com.toolsboox.plugin.feeds.ui.FeedThumbCache
import com.toolsboox.ui.main.MainActivity

/**
 * "Ledger Feed List" — the first SCROLLABLE widget (Michael, the 1.06.51-00 walkthrough: "the
 * ability to scroll, the small featured images and info for feeds etc, but small small").
 *
 * The bitmap widgets ([FeedWidgetProvider] and family) are one picture: crisp, but frozen at
 * fourteen rows and tappable only as a whole. This one is a real ListView over a
 * [RemoteViewsService] — the launcher scrolls it natively, each row is small (thumbnail ·
 * title · source · age), and each row deep-links to ITS entry: the row's fill-in intent names
 * the entry id, MainActivity's widgetDest router looks it up in the same cached list and opens
 * it straight in the feed's in-pane reader.
 *
 * A SEPARATE provider on purpose — the existing bitmap Feed widget stays untouched, so nothing
 * already placed on a home screen changes shape or loses its face. This is the pattern-proof;
 * the Mail widget gets the same treatment as a follow-on once this one has survived a
 * launcher's opinion of it.
 *
 * Deliberately NOT a [CalendarWidgetProvider] subclass: that family's whole inheritance is the
 * single-ImageView layout and the render-a-bitmap plumbing, and this widget has neither. It
 * still joins [CalendarWidgetProvider.refreshAll]'s broadcast list, so every poke that redraws
 * the bitmaps also re-queries this list ([onUpdate] ends in notifyAppWidgetViewDataChanged —
 * without that, ACTION_APPWIDGET_UPDATE would re-bind the adapter but never reload its rows).
 */
class FeedListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.feed_list_widget_layout)

            // The remote adapter. The widget id rides in the intent AND in its data URI:
            // RemoteViewsService caches factories per-Intent, and two widgets whose intents
            // differ only by extras look identical to Intent.filterEquals — the URI is what
            // keeps each placed widget its own factory.
            val adapterIntent = Intent(context, FeedListWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.feed_list_widget_list, adapterIntent)
            views.setEmptyView(R.id.feed_list_widget_list, R.id.feed_list_widget_empty)

            // Header tap: the surface itself, same door as the bitmap Feed widget.
            views.setOnClickPendingIntent(
                R.id.feed_list_widget_header,
                CalendarWidgetProvider.openSurfaceIntent(context, "feeds")
            )

            // Row-tap TEMPLATE. Collection rows can't each carry a PendingIntent; they carry a
            // fill-in over this one. MUTABLE is load-bearing: on Android 12+ the system silently
            // drops fill-in extras into an immutable PendingIntent, which would turn every row
            // back into a plain "open feeds" — the one thing this widget exists to improve on.
            // The base intent carries NO extras of its own; the fill-in supplies widgetDest and
            // the entry id together, so nothing merges and nothing collides.
            val rowTemplate = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setPendingIntentTemplate(
                R.id.feed_list_widget_list,
                PendingIntent.getActivity(
                    context, "feedListRow".hashCode(), rowTemplate,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            appWidgetManager.updateAppWidget(id, views)
        }
        // Re-binding the adapter does not reload its data — this is the call that makes a
        // refreshAll poke (or the 30-minute tick) actually re-read the cached list.
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.feed_list_widget_list)
    }
}

/** The service the launcher binds to for rows. All the work is in the factory. */
class FeedListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        FeedListRemoteViewsFactory(applicationContext)
}

/**
 * Rows for the Feed List widget. Same data contract as the bitmap feed widget: the freshest
 * cached list off disk ([FeedCache.loadFreshestEntries]), unread first, newest within — and
 * NOTHING fetches. Thumbnails come only from the JPEGs [FeedThumbCache] spilled while the feed
 * screen was open ([FeedThumbCache.diskGet]); an entry the screen never showed simply has no
 * picture, which is the truthful state for a process that must not touch the network.
 *
 * [onDataSetChanged] and [getViewAt] both run on a binder thread, so the disk reads and small
 * decodes here are exactly where the platform wants them.
 */
class FeedListRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    companion object {
        /** A scroll's worth, not an archive: the launcher marshals every row's RemoteViews
         *  (thumbnail bitmaps included) across the binder, and the cached list can hold
         *  hundreds. Fifty compact rows is more than the glance this surface is. */
        private const val MAX_ROWS = 50
    }

    private var entries: List<FeedEntry> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        entries = runCatching { FeedCache.loadFreshestEntries(context) }
            .getOrDefault(emptyList())
            // The bitmap widget's ordering, kept identical on purpose: unread first (the point
            // of the glance), newest within each band — two feed widgets disagreeing about
            // order would read as one of them being wrong.
            .sortedWith(compareBy({ it.read }, { -ListWidgetRenderer.parseEpoch(it.publishedAt) }))
            .take(MAX_ROWS)
    }

    override fun onDestroy() {
        entries = emptyList()
    }

    override fun getCount(): Int = entries.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.feed_list_widget_row)
        val e = entries.getOrNull(position) ?: return views

        views.setTextViewText(R.id.feed_row_title, e.title.ifBlank { "(untitled)" })
        // Unread carries the ● the bitmap rows use; read rows keep a quiet interpunct, so the
        // eye can still sweep the margin for what's new.
        views.setTextViewText(R.id.feed_row_marker, if (e.read) "·" else "●")
        val source = e.feedTitle.ifBlank { e.categoryLabel ?: "Feed" }
        views.setTextViewText(
            R.id.feed_row_meta,
            "$source · ${ListWidgetRenderer.relativeAgeIso(e.publishedAt)}"
        )

        val thumb: Bitmap? = e.imageUrl?.let { FeedThumbCache.diskGet(context, it) }
        if (thumb != null) {
            views.setImageViewBitmap(R.id.feed_row_thumb, thumb)
            views.setViewVisibility(R.id.feed_row_thumb, android.view.View.VISIBLE)
        } else {
            // GONE, not INVISIBLE: a picture-less row gives its dead air back to the title.
            views.setViewVisibility(R.id.feed_row_thumb, android.view.View.GONE)
        }

        // The per-row deep link: everything the template lacks. The router in MainActivity
        // reads feedEntryId, finds the entry in the SAME cached list this factory read, and
        // hands it to the feed pane as a pending in-pane open.
        views.setOnClickFillInIntent(R.id.feed_row_root, Intent().apply {
            putExtra("widgetDest", "feeds")
            putExtra("feedEntryId", e.id)
        })

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = entries.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
