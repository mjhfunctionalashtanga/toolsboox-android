package com.toolsboox.plugin.calendar.nw

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * POSTs one panel card to its webhook as multipart/form-data — the PNG under `image` plus
 * the fields (panel id/title/text/page/date) a niche template's endpoint consumes. Same
 * house style as MichaelFilterIntakeClient: synchronous, call from a background dispatcher.
 */
object PanelWebhookClient {

    private const val TAG = "PanelWebhook"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15_000, TimeUnit.MILLISECONDS)
        .writeTimeout(60_000, TimeUnit.MILLISECONDS)
        .readTimeout(30_000, TimeUnit.MILLISECONDS)
        .build()

    sealed class Result {
        object Ok : Result()
        /** Server answered but rejected — retrying won't help. */
        data class Rejected(val code: Int) : Result()
        /** Network / 5xx — worth retrying later. */
        object NetworkFailure : Result()
    }

    fun post(job: PanelWebhookJob): Result {
        val image = File(job.imageFile)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            job.key?.takeIf { it.isNotBlank() }?.let { addFormDataPart("key", it) }
            addFormDataPart("panel", job.panelId)
            addFormDataPart("title", job.title)
            addFormDataPart("text", job.text)
            addFormDataPart("page", job.page)
            addFormDataPart("date", job.dateMs.toString())
            if (image.exists()) {
                addFormDataPart(
                    "image", image.name,
                    image.asRequestBody("image/png".toMediaTypeOrNull())
                )
            }
        }.build()

        val request = Request.Builder().url(job.url).post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.Ok
                    response.code in 500..599 -> {
                        Timber.w("$TAG: ${response.code} from ${job.url} (retry)")
                        Result.NetworkFailure
                    }
                    else -> {
                        Timber.w("$TAG: ${response.code} from ${job.url} (rejected)")
                        Result.Rejected(response.code)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.i("$TAG: network failure to ${job.url}: ${e.message}")
            Result.NetworkFailure
        }
    }
}
