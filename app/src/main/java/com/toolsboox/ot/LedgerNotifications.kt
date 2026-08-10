package com.toolsboox.ot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber

/**
 * The one door every system notification walks through — and the per-category mute switches
 * Michael asked for (2026-08-10: "more granularity around the notifications coming from
 * Ledgable — schedule, tasks, RSS, email etc.").
 *
 * The 2026-08-10 audit found the app posts NO system notification anywhere: the class nudge is
 * an in-app dialog ([com.toolsboox.plugin.calendar.ui.AppointmentNudge]), the sync workers are
 * silent, and mail/feeds fetch only feeds widgets. Which means the coarseness problem was one
 * bad launch away: with minSdk 26, the FIRST feature to call notify() without a channel would
 * be silently dropped by the OS, and the first one to invent its own channel ad hoc would start
 * the usual drift — five features, five naming schemes, no way to silence one without the rest.
 * So the channels are laid down BEFORE the first poster exists, the way the day owns its
 * startHour: the structure precedes the content, and the content has nowhere wrong to land.
 *
 * WHY CHANNELS AND NOT A SETTINGS SCREEN OF OUR OWN: Android notification channels ARE the
 * per-category granularity — the system settings page lists them with a switch each, honors the
 * choice at the OS level (a muted channel stays muted even if our code has a bug), and survives
 * reinstalls of the toggle UI we would otherwise have to build and maintain. Building our own
 * mute matrix would be a second source of truth for the same position — the exact sin the
 * Ledger canon forbids. Settings gets one row that jumps to the system page; the system does
 * the rest.
 *
 * THE CATEGORY SET is derived from what the app actually is, not from what a notifier might
 * someday want: schedule (the class/appointment nudge and anything else time-critical — HIGH,
 * because a nudge that arrives after the class started is landfill), tasks and mail (DEFAULT —
 * worth a statusbar icon, not worth a heads-up over live ink), feeds (LOW — RSS is a river,
 * not a doorbell), and sync (MIN — a worker grumbling about WebDAV should be findable, never
 * audible). There is no community/replies channel because no code path exists that would post
 * one; a channel with no possible poster is chrome.
 *
 * createNotificationChannel is idempotent (re-registering is a cheap no-op that also lets us
 * revise names/descriptions in an update), so [ensureChannels] runs unconditionally at app
 * start and again inside [post] — a poster can never race the registry.
 */
object LedgerNotifications {

    /** Class and appointment nudges — the "you asked to be told" moments. */
    const val CHANNEL_SCHEDULE = "ledger_schedule"

    /** Task deadlines and board movement. */
    const val CHANNEL_TASKS = "ledger_tasks"

    /** New mail in the connected inbox. */
    const val CHANNEL_MAIL = "ledger_mail"

    /** Fresh items in subscribed feeds. */
    const val CHANNEL_FEEDS = "ledger_feeds"

    /** Background sync status — failures worth knowing about, never worth a sound. */
    const val CHANNEL_SYNC = "ledger_sync"

    /** Register every category. Idempotent — call as often as convenient. */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        fun channel(id: String, name: String, importance: Int, description: String) =
            NotificationChannel(id, name, importance).also { it.description = description }
        listOf(
            channel(
                CHANNEL_SCHEDULE, "Schedule", NotificationManager.IMPORTANCE_HIGH,
                "Upcoming classes and appointments — nudges you opted into."
            ),
            channel(
                CHANNEL_TASKS, "Tasks", NotificationManager.IMPORTANCE_DEFAULT,
                "Task due dates and board activity."
            ),
            channel(
                CHANNEL_MAIL, "Mail", NotificationManager.IMPORTANCE_DEFAULT,
                "New mail in the connected inbox."
            ),
            channel(
                CHANNEL_FEEDS, "Feeds", NotificationManager.IMPORTANCE_LOW,
                "New items in subscribed RSS feeds."
            ),
            channel(
                CHANNEL_SYNC, "Sync & system", NotificationManager.IMPORTANCE_MIN,
                "Background sync status and problems."
            )
        ).forEach { nm.createNotificationChannel(it) }
    }

    /**
     * Post through a category — the only sanctioned way to notify. Ensures the channel exists
     * first (so nothing can ever post channel-less and vanish on API 26+) and respects the
     * 33+ runtime permission by staying silent rather than crashing when it isn't held.
     */
    fun post(context: Context, channelId: String, notificationId: Int, build: (NotificationCompat.Builder) -> Unit) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.i("notification on $channelId suppressed — POST_NOTIFICATIONS not granted")
            return
        }
        val builder = NotificationCompat.Builder(context, channelId)
        build(builder)
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, builder.build()) }
            .onFailure { Timber.w(it, "notification on $channelId failed") }
    }

    /**
     * Jump to the system's per-app notification page, where each channel above has its own
     * switch. minSdk 26 means ACTION_APP_NOTIFICATION_SETTINGS always exists — no legacy
     * fallback path to carry.
     */
    fun openCategorySettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "could not open system notification settings") }
    }
}
