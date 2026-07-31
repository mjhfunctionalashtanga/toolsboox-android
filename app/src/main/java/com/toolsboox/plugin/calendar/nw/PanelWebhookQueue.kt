package com.toolsboox.plugin.calendar.nw

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * On-disk queue of panel-card deliveries — each job is a JSON sidecar plus the card PNG,
 * held under filesDir until its webhook accepts it. Mirrors the MichaelFilter intake queue.
 */
object PanelWebhookQueue {

    private const val TAG = "PanelWebhookQueue"
    private const val QUEUE_DIR = "panel-webhook-queue"
    private const val WORK_NAME = "panel-webhook-drain"

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(PanelWebhookJob::class.java)

    fun queueDir(context: Context): File =
        File(context.filesDir, QUEUE_DIR).apply { mkdirs() }

    /** Persist [pngBytes] + a job JSON that points at it. Returns the job's id (or null).
     *  The webhook's `key` auth field is deliberately NOT persisted: the job JSON is a plain
     *  file, and the key already lives in the encrypted [PanelWebhookStore] — the drain worker
     *  looks it up by destination name at send time (which also means a rotated key applies to
     *  jobs queued before the rotation). */
    fun enqueue(context: Context, job: PanelWebhookJob, pngBytes: ByteArray): String? = try {
        val id = UUID.randomUUID().toString()
        val png = File(queueDir(context), "$id.png")
        png.writeBytes(pngBytes)
        val stored = job.copy(imageFile = png.absolutePath, key = null)
        File(queueDir(context), "$id.json").writeText(adapter.toJson(stored))
        id
    } catch (e: Exception) {
        Timber.w(e, "$TAG: enqueue failed")
        null
    }

    fun pendingJobFiles(context: Context): List<File> =
        queueDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()

    fun load(file: File): PanelWebhookJob? =
        runCatching { adapter.fromJson(file.readText()) }.getOrNull()

    /** Delete a job and its PNG once delivered. */
    fun delete(jobFile: File) {
        runCatching {
            load(jobFile)?.let { File(it.imageFile).delete() }
            jobFile.delete()
        }
    }

    fun scheduleDrain(context: Context) {
        val request = OneTimeWorkRequestBuilder<PanelWebhookWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
