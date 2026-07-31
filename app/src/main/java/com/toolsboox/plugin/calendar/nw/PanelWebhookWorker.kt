package com.toolsboox.plugin.calendar.nw

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drains the panel-webhook queue: POST each queued card to its endpoint, delete on accept,
 * Result.retry() on network trouble so WorkManager backs off. House style matches
 * IntakeQueueWorker / UltrabridgeSyncWorker.
 */
class PanelWebhookWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PanelWebhookWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobs = PanelWebhookQueue.pendingJobFiles(applicationContext)
        if (jobs.isEmpty()) return@withContext Result.success()

        var retry = false
        for (file in jobs) {
            val job = PanelWebhookQueue.load(file) ?: run { file.delete(); null } ?: continue
            // The job JSON on disk carries no auth key (see PanelWebhookQueue.enqueue): resolve
            // it from the encrypted store by destination name, falling back to whatever a
            // pre-migration job still holds in its own record.
            val key = PanelWebhookStore.list(applicationContext)
                .firstOrNull { it.name == job.webhookName }?.key ?: job.key
            when (PanelWebhookClient.post(job, key)) {
                is PanelWebhookClient.Result.Ok -> {
                    Timber.i("$TAG: delivered ${job.panelId} → ${job.webhookName}")
                    PanelWebhookQueue.delete(file)
                }
                is PanelWebhookClient.Result.Rejected -> {
                    // Won't succeed on retry — drop it so the queue doesn't wedge.
                    Timber.w("$TAG: rejected ${job.panelId} → ${job.webhookName}; dropping")
                    PanelWebhookQueue.delete(file)
                }
                is PanelWebhookClient.Result.NetworkFailure -> retry = true
            }
        }
        if (retry) Result.retry() else Result.success()
    }
}
