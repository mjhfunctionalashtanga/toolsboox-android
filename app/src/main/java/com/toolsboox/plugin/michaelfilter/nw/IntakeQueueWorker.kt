package com.toolsboox.plugin.michaelfilter.nw

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * WorkManager one-shot worker that drains the MichaelFilter intake queue.
 *
 * Follows the UltrabridgeSyncWorker house style: plain CoroutineWorker,
 * Result.retry() on network trouble so WorkManager backs off and re-runs.
 * Duplicate submissions are harmless — the server dedups by URL.
 */
class IntakeQueueWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "IntakeQueueWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pending = IntakeQueue.pendingFiles(applicationContext)
        if (pending.isEmpty()) {
            Timber.i("$TAG: Queue empty, nothing to do")
            return@withContext Result.success()
        }

        Timber.i("$TAG: Draining ${pending.size} queued submission(s)")
        var networkFailures = 0

        for (file in pending) {
            val submission = IntakeQueue.load(file)
            if (submission == null) {
                // Corrupt file — drop it so it can't wedge the queue forever.
                file.delete()
                continue
            }

            when (val result = MichaelFilterIntakeClient.submit(submission)) {
                is MichaelFilterIntakeClient.SubmitResult.Saved,
                is MichaelFilterIntakeClient.SubmitResult.Duplicate -> {
                    Timber.i("$TAG: Delivered ${submission.linkUrl}")
                    file.delete()
                }

                is MichaelFilterIntakeClient.SubmitResult.Rejected -> {
                    // The server answered and said no; retrying the same payload
                    // will never succeed, so drop it rather than loop forever.
                    Timber.w("$TAG: Dropping rejected submission ${submission.linkUrl} (HTTP ${result.httpCode})")
                    file.delete()
                }

                is MichaelFilterIntakeClient.SubmitResult.NetworkFailure -> {
                    networkFailures++
                }
            }
        }

        if (networkFailures > 0) {
            Timber.w("$TAG: $networkFailures submission(s) still pending; requesting retry")
            Result.retry()
        } else {
            Result.success()
        }
    }
}
