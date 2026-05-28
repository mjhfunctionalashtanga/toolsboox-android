package com.toolsboox.plugin.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.toolsboox.R
import com.toolsboox.ui.main.MainActivity
import java.time.LocalDate

/**
 * Full day-page widget. Renders the entire 1404x1872 planner canvas.
 */
open class CalendarWidgetProvider : AppWidgetProvider() {

    companion object {
        fun refreshAll(context: Context) {
            broadcastUpdate(context, CalendarWidgetProvider::class.java)
            broadcastUpdate(context, ScheduleWidgetProvider::class.java)
            broadcastUpdate(context, TasksNotesWidgetProvider::class.java)
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
    }

    protected open val mode: WidgetRenderer.Mode = WidgetRenderer.Mode.FULL

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    protected fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.calendar_widget_layout)
        val today = LocalDate.now()

        val options = manager.getAppWidgetOptions(widgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)

        val bitmap = WidgetRenderer.render(context, today, widthDp, heightDp, mode)
        views.setImageViewBitmap(R.id.widget_page_image, bitmap)
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

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
