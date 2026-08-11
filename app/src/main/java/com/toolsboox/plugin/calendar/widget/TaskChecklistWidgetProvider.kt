package com.toolsboox.plugin.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.toolsboox.R
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe
import com.toolsboox.ui.main.MainActivity
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Ledger Task Checklist" — the scrollable task widget, and the first widget with INDIVIDUAL
 * SELECTION: every row carries a real ✓ checkbox that marks THAT task done from the home
 * screen, no app open needed.
 *
 * The bitmap Task List widget ([TaskListWidgetProvider]) stays untouched — glance-only, six
 * rows, tappable as a whole. This one is a ListView over a RemoteViewsService (the Feed List
 * pattern): the launcher scrolls it natively, rows are today's open tasks followed by the
 * 30-day still-open lookback (the same slim walk [ListWidgetRenderer.renderTasks] does, muted,
 * with the day they were written), and each row has two doors:
 *
 *  • the ☐ checkbox → a broadcast to [TaskDoneReceiver], which writes done=true through
 *    [com.toolsboox.plugin.calendar.fi.CalendarDayService] (done is monotonic — the widget
 *    only ever marks done, never un-done) and then refreshes the widget family, debounced;
 *  • the row text → the task's OWN day page (a lookback task opens the day it was written on,
 *    not today), via the router's "day" arm.
 *
 * A collection has ONE PendingIntent template, so both doors ride a broadcast template and the
 * receiver forks on a taskAction extra — the open arm starts MainActivity from the receiver.
 * (On Android 14+ a launcher that doesn't opt into background-activity grants for broadcast
 * PendingIntents can block that trampoline; the fleet's Boox panels are Android 11–13, where
 * the foreground sender's grant is the default. The checkbox — this widget's reason to exist —
 * is a pure broadcast and works everywhere.)
 */
class TaskChecklistWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.task_check_widget_layout)

            // The remote adapter — widget id in the data URI so each placed widget keeps its
            // own factory (Intent.filterEquals ignores extras; see FeedListWidgetProvider).
            val adapterIntent = Intent(context, TaskChecklistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.task_check_widget_list, adapterIntent)
            views.setEmptyView(R.id.task_check_widget_list, R.id.task_check_widget_empty)

            // Header: today's date, and a tap through to today's day page.
            views.setTextViewText(
                R.id.task_check_widget_count,
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEE · MMM d"))
            )
            views.setOnClickPendingIntent(
                R.id.task_check_widget_header,
                CalendarWidgetProvider.openAppIntent(context)
            )

            // The one template both doors share: a broadcast to the receiver. MUTABLE is
            // load-bearing — Android 12+ drops fill-in extras into an immutable PendingIntent,
            // which would strip every row of its taskAction/taskDate/taskId.
            val template = Intent(context, TaskDoneReceiver::class.java)
            views.setPendingIntentTemplate(
                R.id.task_check_widget_list,
                PendingIntent.getBroadcast(
                    context, "taskChecklistRow".hashCode(), template,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            appWidgetManager.updateAppWidget(id, views)
        }
        // Re-binding the adapter does not reload its rows — this is what makes a refreshAll
        // poke (a day save, a checked box, the 30-minute tick) re-read the day files.
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.task_check_widget_list)
    }
}

/** The service the launcher binds to for rows. All the work is in the factory. */
class TaskChecklistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TaskChecklistRemoteViewsFactory(applicationContext)
}

/**
 * Rows for the Task Checklist. Same data walk as [ListWidgetRenderer.renderTasks], without its
 * six-row glance cap: today's open tasks off today's day JSON, then still-open tasks off the
 * 30-day lookback window's day files — slim-decoded ([CalendarDayService.loadLedgerItems]),
 * word-deduped against everything already said ([LedgerTaskDedupe]), each carrying the day it
 * was written. Binder thread, disk only, nothing fetches.
 */
class TaskChecklistRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    companion object {
        /** A scroll's worth — same budget as the other list widgets. */
        private const val MAX_ROWS = 50
        internal const val LOOKBACK_DAYS = 30L
    }

    /** One open task: the day whose file owns it, its id there, its text, and — for lookback
     *  rows — the "since Aug 3" label the page's ghost rows wear. */
    private data class TaskRow(val date: LocalDate, val id: String, val text: String, val since: String?)

    private var rows: List<TaskRow> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = runCatching { gather() }.getOrDefault(emptyList())
    }

    private fun gather(): List<TaskRow> {
        val today = LocalDate.now()
        val out = mutableListOf<TaskRow>()

        val day = WidgetRenderer.loadCalendarDay(context, today)
        val dead = (day?.deletedItemIds.orEmpty() + day?.deletedElementIds.orEmpty()).toSet()
        val tasks = day?.ledgerItems.orEmpty()
            .filter { it.kind == LedgerItem.Kind.TASK && it.text.isNotBlank() && it.id !in dead }
        tasks.filter { !it.done && it.stage != "done" }
            .forEach { out.add(TaskRow(today, it.id, it.text, null)) }

        // Dedupe against EVERYTHING today's file says — done included, so a task checked off
        // today doesn't resurface from yesterday's file (renderTasks' rule, kept).
        val said = HashSet<String>()
        tasks.forEach { said.add(LedgerTaskDedupe.key(it.text)) }

        // The lookback: the widget-process wiring for the slim decode, exactly as
        // ListWidgetRenderer.renderTasks builds it.
        val svc = com.toolsboox.plugin.calendar.fi.CalendarDayService().apply {
            moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.toolsboox.ot.LocaleJsonAdapter())
                .add(com.toolsboox.ot.DateJsonAdapter())
                .add(com.toolsboox.ot.UUIDJsonAdapter())
                .build()
        }
        val root = com.toolsboox.ot.LedgerPaths.documentsRoot(context)
        val sinceFmt = DateTimeFormatter.ofPattern("MMM d")
        var d = today.minusDays(1)
        val floor = today.minusDays(LOOKBACK_DAYS)
        while (!d.isBefore(floor) && out.size < MAX_ROWS) {
            val y = "%04d".format(d.year); val m = "%02d".format(d.monthValue); val dd = "%02d".format(d.dayOfMonth)
            val f = File(File(root, "calendar/$y/$m"), "day-$y-$m-$dd-v2.json")
            if (f.exists()) {
                for (it in runCatching { svc.loadLedgerItems(f) }.getOrNull().orEmpty()) {
                    if (it.kind != LedgerItem.Kind.TASK || it.done || it.stage == "done" || it.text.isBlank()) continue
                    if (!said.add(LedgerTaskDedupe.key(it.text))) continue
                    out.add(TaskRow(d, it.id, it.text, "since ${d.format(sinceFmt)}"))
                    if (out.size >= MAX_ROWS) break
                }
            }
            d = d.minusDays(1)
        }
        return out
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.task_check_widget_row)
        val row = rows.getOrNull(position) ?: return views

        views.setTextViewText(R.id.task_row_text, row.text)
        if (row.since != null) {
            // Lookback rows read muted with their provenance — the page's ghost-row grammar.
            views.setTextViewText(R.id.task_row_since, row.since)
            views.setViewVisibility(R.id.task_row_since, android.view.View.VISIBLE)
            views.setTextColor(R.id.task_row_text, 0x99000000.toInt())
        } else {
            views.setViewVisibility(R.id.task_row_since, android.view.View.GONE)
            views.setTextColor(R.id.task_row_text, android.graphics.Color.BLACK)
        }

        val dateIso = row.date.toString()
        // The ✓ door: mark THIS task done, in the file that owns it.
        views.setOnClickFillInIntent(R.id.task_row_check, Intent().apply {
            putExtra("taskAction", "done")
            putExtra("taskDate", dateIso)
            putExtra("taskId", row.id)
        })
        // The row door: the task's OWN day page.
        views.setOnClickFillInIntent(R.id.task_row_root, Intent().apply {
            putExtra("taskAction", "open")
            putExtra("taskDate", dateIso)
        })

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.let { (it.date.toString() + it.id).hashCode().toLong() } ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}

/**
 * The small receiver behind the Task Checklist's rows. Two arms, forked on taskAction:
 *
 *  • "done" — load the named day through CalendarDayService (moshi wired by hand, the widget-
 *    process rule), set done=true on the named task AND its same-day dedupe twins (QuickWins'
 *    rule: a typed copy and a reading-log copy of one task check off together or the unchecked
 *    twin resurfaces), save back through the same service — the one-throat save, so the card
 *    index, media externalization and (for today's file) the debounced widget poke all ride —
 *    then refresh this family debounced. Done is MONOTONIC: this receiver only ever sets it,
 *    never clears it, so a stale widget tap can never un-do a task.
 *
 *  • "open" — start MainActivity on the task's own day page (widgetDest="day"). See the
 *    provider's comment for the Android 14+ background-activity caveat.
 */
class TaskDoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val date = runCatching { LocalDate.parse(intent.getStringExtra("taskDate") ?: "") }.getOrNull() ?: return
        when (intent.getStringExtra("taskAction")) {
            "open" -> runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    putExtra("widgetDest", "day")
                    putExtra("widgetDay", date.toString())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }
            "done" -> {
                val itemId = intent.getStringExtra("taskId") ?: return
                // File I/O off the main thread, with the receiver kept alive for it — the same
                // goAsync + Thread plumbing CalendarWidgetProvider.onReceive uses for renders.
                val pending = goAsync()
                Thread {
                    try {
                        runCatching { markDone(context, date, itemId) }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun markDone(context: Context, date: LocalDate, itemId: String) {
        val svc = com.toolsboox.plugin.calendar.fi.CalendarDayService().apply {
            moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.toolsboox.ot.LocaleJsonAdapter())
                .add(com.toolsboox.ot.DateJsonAdapter())
                .add(com.toolsboox.ot.UUIDJsonAdapter())
                .build()
            // With the app context wired, the save behaves exactly like an in-app save —
            // media externalization runs and today's file pokes the widget family itself.
            appContext = context.applicationContext
        }
        val root = com.toolsboox.ot.LedgerPaths.documentsRoot(context)
        val day = svc.load(root, date, null, Locale.getDefault())

        val target = day.ledgerItems.firstOrNull { it.id == itemId } ?: return
        val key = LedgerTaskDedupe.key(target.text)
        var changed = false
        for (li in day.ledgerItems) {
            if (li.kind == LedgerItem.Kind.TASK && !li.done &&
                (li.id == itemId || (key.isNotEmpty() && LedgerTaskDedupe.key(li.text) == key))
            ) {
                li.done = true; changed = true
            }
        }
        if (!changed) return
        svc.save(root, date, day)

        // The checked row must leave the list promptly — the save's own poke covers today's
        // file only (and is debounced 5s); a lookback mark would otherwise sit stale for half
        // an hour. Data-changed now for this list, the debounced family refresh for the rest.
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, TaskChecklistWidgetProvider::class.java))
        if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.task_check_widget_list)
        CalendarWidgetProvider.refreshAllDebounced(context)
    }
}
