package com.toolsboox.plugin.calendar.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import com.toolsboox.R
import com.toolsboox.ui.main.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Full day-page widget. Renders the entire 1404x1872 planner canvas.
 */
open class CalendarWidgetProvider : AppWidgetProvider() {

    companion object {
        fun refreshAll(context: Context) {
            broadcastUpdate(context, CalendarWidgetProvider::class.java)
            broadcastUpdate(context, ScheduleWidgetProvider::class.java)
            broadcastUpdate(context, TasksNotesWidgetProvider::class.java)
            broadcastUpdate(context, MailWidgetProvider::class.java)
            broadcastUpdate(context, FeedWidgetProvider::class.java)
            broadcastUpdate(context, DailyPileWidgetProvider::class.java)
            broadcastUpdate(context, TaskListWidgetProvider::class.java)
            broadcastUpdate(context, AllStarsWidgetProvider::class.java)
            broadcastUpdate(context, BlogWidgetProvider::class.java)
            broadcastUpdate(context, RosterWidgetProvider::class.java)
        }

        private fun broadcastUpdate(context: Context, cls: Class<*>) {
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) return
            val intent = Intent(context, cls).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }

        fun openAppIntent(context: Context): PendingIntent {
            val today = LocalDate.now()
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("year", today.year.toString())
                putExtra("month", today.monthValue.toString())
                putExtra("day", today.dayOfMonth.toString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * A tap-through to a NAMED surface rather than the day page — "mail" / "feeds" /
         * "allstars", consumed by MainActivity.onResume the way the share intents are. The
         * request code is the destination's hash so each surface keeps its own PendingIntent;
         * on a shared request code, FLAG_UPDATE_CURRENT would rewrite every widget's tap to
         * whichever destination was filled in last.
         */
        fun openSurfaceIntent(context: Context, dest: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra("widgetDest", dest)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, dest.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    protected open val mode: WidgetRenderer.Mode = WidgetRenderer.Mode.FULL

    /** Where a tap on this widget lands: a surface name for [openSurfaceIntent], or null for the
     *  default — today's day page. Every widget taps through to the surface it is a window onto. */
    protected open val tapDest: String? = null

    /**
     * Run widget rendering on a background thread — file I/O, calendar queries,
     * and bitmap drawing should never block the main thread.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val pendingResult = goAsync()
            Thread {
                try { super.onReceive(context, intent) }
                finally { pendingResult.finish() }
            }.start()
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
        scheduleMidnightUpdate(context)
        // Belt to updatePeriodMillis's braces: a WorkManager periodic tick at the same 30 minutes,
        // for launchers/battery managers that starve the platform's own widget alarm. KEEP-policy
        // unique work, so re-landing here on every update never reschedules anything.
        runCatching { WidgetRefreshWorker.schedule(context) }
    }

    /**
     * Schedule an alarm shortly after midnight so the widget rolls over to the new day.
     * Without this, the widget waits up to 30 minutes (the updatePeriodMillis interval).
     */
    private fun scheduleMidnightUpdate(context: Context) {
        val cls = this::class.java
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))
        if (ids.isEmpty()) return

        val intent = Intent(context, cls).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, cls.name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextMidnight = LocalDate.now().plusDays(1)
            .atTime(LocalTime.of(0, 0, 5))
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC, nextMidnight, pendingIntent)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    /**
     * The bitmap this widget shows. The base widgets crop regions of the real day page
     * ([WidgetRenderer]); the list widgets (Mail / Feed / Daily Pile) override this to draw
     * their own e-ink list instead, while inheriting all of the plumbing above — the background
     * thread, the midnight roll-over, the tap-to-open, and the shared single-ImageView layout.
     */
    protected open fun renderBitmap(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap =
        WidgetRenderer.render(context, date, widthDp, heightDp, mode)

    protected fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.calendar_widget_layout)
        val today = LocalDate.now()

        val options = manager.getAppWidgetOptions(widgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)

        val bitmap = renderBitmap(context, today, widthDp, heightDp)
        views.setImageViewBitmap(R.id.widget_page_image, bitmap)
        views.setOnClickPendingIntent(R.id.widget_root,
            tapDest?.let { openSurfaceIntent(context, it) } ?: openAppIntent(context))

        manager.updateAppWidget(widgetId, views)
    }
}

/**
 * Schedule-only widget. Renders the left column (hours grid + event lanes).
 */
class ScheduleWidgetProvider : CalendarWidgetProvider() {
    override val mode: WidgetRenderer.Mode = WidgetRenderer.Mode.SCHEDULE
}

/**
 * Tasks + Notes widget. Renders the right column (tasks on top, notes on bottom).
 */
class TasksNotesWidgetProvider : CalendarWidgetProvider() {
    override val mode: WidgetRenderer.Mode = WidgetRenderer.Mode.TASKS_NOTES
}
