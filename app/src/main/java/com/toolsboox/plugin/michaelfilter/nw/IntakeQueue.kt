package com.toolsboox.plugin.michaelfilter.nw

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * On-disk queue of unsent MichaelFilter intake submissions.
 *
 * One JSON file per submission in filesDir/michaelfilter-intake-queue/.
 * The fragment writes and (on immediate success) deletes entries;
 * [IntakeQueueWorker] drains whatever is left when the network allows.
 */
object IntakeQueue {

    private const val TAG = "IntakeQueue"
    private const val QUEUE_DIR = "michaelfilter-intake-queue"
    private const val WORK_NAME = "michaelfilter-intake-submit"

    private val moshi: Moshi = Moshi.Builder().build()

    /**
     * The queue directory, created on demand.
     */
    fun queueDir(context: Context): File =
        File(context.filesDir, QUEUE_DIR).apply { mkdirs() }

    /**
     * Persist a submission to the queue.
     *
     * @return the queue file, or null if the write failed
     */
    fun enqueue(context: Context, submission: IntakeSubmission): File? {
        return try {
            val file = File(queueDir(context), "${UUID.randomUUID()}.json")
            val json = moshi.adapter(IntakeSubmission::class.java).toJson(submission)
            file.writeText(json, Charsets.UTF_8)
            Timber.i("$TAG: Queued ${submission.linkUrl} as ${file.name}")
            file
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to queue submission")
            null
        }
    }

    /**
     * List pending queue files, oldest first.
     */
    fun pendingFiles(context: Context): List<File> =
        queueDir(context).listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    /**
     * Load a queued submission, or null if the file is unreadable.
     */
    fun load(file: File): IntakeSubmission? {
        return try {
            moshi.adapter(IntakeSubmission::class.java).fromJson(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Corrupt queue file ${file.name}")
            null
        }
    }

    /**
     * Schedule the drain worker (network-gated, unique, kept if already pending).
     */
    fun scheduleDrain(context: Context) {
        val request = OneTimeWorkRequestBuilder<IntakeQueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
