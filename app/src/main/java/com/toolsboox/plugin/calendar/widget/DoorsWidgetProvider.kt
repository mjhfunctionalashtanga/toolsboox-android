package com.toolsboox.plugin.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.toolsboox.R
import com.toolsboox.ui.main.MainActivity

/**
 * THE DOORS WIDGET — seven rooms, one tap each (Michael, 09-13: "(continue)(e-mail)(read)(listen)
 * (watch)(all notes)(new note) for quick access to what I use"). No data to keep fresh: each button
 * is a `widgetDest` the activity's own widget router already answers (MainActivity), so this is a
 * row of doors and nothing else. Every intent carries its own action so the launcher keeps seven
 * distinct PendingIntents (Intent.filterEquals ignores extras).
 *
 * The Incoming tiles wear the shared door order and names ([com.toolsboox.plugin.feeds.ui.IncomingDoors],
 * Michael 2026-10-11): 📧 The Mail · Read · Watch · Listen — Watch before Listen, as everywhere else.
 * The layout holds the order; IncomingDoorsTest reads it.
 */
class DoorsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.doors_widget_layout)
            DOORS.forEachIndexed { i, (viewId, dest) ->
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = "com.toolsboox.widget.DOOR_$dest"
                    putExtra("widgetDest", dest)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pi = PendingIntent.getActivity(context, 7100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(viewId, pi)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        val DOORS = listOf(
            R.id.door_continue to "door_continue",
            R.id.door_mail to "door_mail",
            R.id.door_read to "door_read",
            R.id.door_watch to "door_watch",
            R.id.door_listen to "door_listen",
            R.id.door_notes to "door_notes",
            R.id.door_newnote to "door_newnote",
        )
    }
}
