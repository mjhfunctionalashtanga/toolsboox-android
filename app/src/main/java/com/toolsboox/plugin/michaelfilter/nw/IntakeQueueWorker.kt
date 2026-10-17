package com.toolsboox.plugin.michaelfilter.nw

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
 *
 * The key comes from [MichaelFilterConnector] (Settings), and the loop is [IntakeDrain]:
 *  - no key → nothing is sent, the queue is kept, success (a saved key schedules the next pass);
 *  - the saved key was turned down less than a day ago → nothing is sent until a key is saved, or
 *    the day is up (then one request re-checks it: [MichaelFilterConnector.RECHECK_AFTER_MS]);
 *  - the server turns the key down now → the queue is kept, [MichaelFilterConnector.KEY_TURNED_DOWN]
 *    is shown ONCE, and the worker stops — no retry loop against a key that will keep failing.
 *    The refusal is pinned to the key this pass SENT, so a pass still running with an old key when
 *    a new one is saved can't mark the new one turned down.
 */
class IntakeQueueWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "IntakeQueueWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val pending = IntakeQueue.pendingFiles(ctx)
        if (pending.isEmpty()) {
            Timber.i("$TAG: Queue empty, nothing to do")
            return@withContext Result.success()
        }
        // The key this pass sends — read once, and refresh() re-pins the saved key's fingerprint.
        val key = MichaelFilterConnector.refresh(ctx)
        if (key.isNotEmpty() && MichaelFilterConnector.holdsRefusedKey(ctx, key)) {
            Timber.i("$TAG: ${pending.size} item(s) held — the saved key was turned down; waiting for a new one")
            return@withContext Result.success()
        }

        Timber.i("$TAG: Draining ${pending.size} queued submission(s)")

        var keyTaken = false
        val outcome = IntakeDrain.run(
            key = key,
            pending = pending,
            load = { file -> IntakeQueue.load(file) },
            submit = { submission, k ->
                MichaelFilterIntakeClient.submit(submission, k).also {
                    if (it is MichaelFilterIntakeClient.SubmitResult.Saved ||
                        it is MichaelFilterIntakeClient.SubmitResult.Duplicate) keyTaken = true
                }
            },
            remove = { file -> file.delete() }
        )
        // A save or a duplicate is proof the key works (a 400 isn't — WordPress can refuse the params
        // before it ever looks at the key): a refusal this re-check got past is over.
        if (keyTaken) MichaelFilterConnector.clearTurnedDown(ctx, key)

        when (outcome) {
            IntakeDrain.Outcome.DONE -> Result.success()
            IntakeDrain.Outcome.RETRY_LATER -> {
                Timber.w("$TAG: some submission(s) still pending; requesting retry")
                Result.retry()
            }
            IntakeDrain.Outcome.NOT_CONNECTED -> {
                Timber.i("$TAG: ${pending.size} item(s) held — MichaelFilter isn't connected")
                Result.success()
            }
            IntakeDrain.Outcome.KEY_TURNED_DOWN -> {
                if (MichaelFilterConnector.markTurnedDown(ctx, key)) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(ctx, MichaelFilterConnector.KEY_TURNED_DOWN, Toast.LENGTH_LONG).show()
                    }
                }
                Timber.w("$TAG: key turned down (fingerprint ${MichaelFilterConnector.fingerprint(key)}); queue kept")
                Result.success()
            }
        }
    }
}
