package com.toolsboox.plugin.calendar.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic widget tick: every 30 minutes, poke every home-screen widget to re-render
 * ([CalendarWidgetProvider.refreshAll] — a broadcast; each provider then redraws off its own
 * cached data on a background thread). This backs up updatePeriodMillis (also 30 minutes),
 * which some launchers and battery managers quietly starve; WorkManager's alarm survives them.
 * Not a Hilt worker on purpose — it needs no dependencies, and the default factory builds it.
 */
class WidgetRefreshWorker(
    context: Context, params: WorkerParameters
) : androidx.work.Worker(context, params) {

    override fun doWork(): Result {
        runCatching { CalendarWidgetProvider.refreshAll(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "ledger-widget-refresh"

        /** Idempotent — KEEP policy, so the schedule lands once no matter how often this is hit
         *  (every widget onUpdate calls it). refreshAll no-ops per class with no placed widgets,
         *  so an orphaned schedule costs one skipped broadcast per half hour. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
