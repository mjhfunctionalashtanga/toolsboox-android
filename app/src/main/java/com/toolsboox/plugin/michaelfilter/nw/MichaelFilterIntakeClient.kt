package com.toolsboox.plugin.michaelfilter.nw

import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the mjh.yoga MichaelFilter intake endpoint.
 *
 * POST https://mjh.yoga/wp-json/mjh-rss/v1/intake
 *   form params: key, url, kind (read|listen|watch), title, text, why
 *   200 {"ok":true,...} on save; {"ok":true,"dup":true} on duplicate URL.
 */
object MichaelFilterIntakeClient {

    private const val TAG = "MichaelFilterIntake"

    private const val INTAKE_URL = "https://mjh.yoga/wp-json/mjh-rss/v1/intake"
    // Out of source: set INTAKE_KEY in local.properties (git-ignored). See app/build.gradle.
    private val INTAKE_KEY = com.toolsboox.BuildConfig.INTAKE_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(15_000, TimeUnit.MILLISECONDS)
        .writeTimeout(30_000, TimeUnit.MILLISECONDS)
        .readTimeout(30_000, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Result of a submit attempt.
     */
    sealed class SubmitResult {
        /** Accepted; in tomorrow's edition. */
        object Saved : SubmitResult()

        /** Already in the pipeline (duplicate URL). */
        object Duplicate : SubmitResult()

        /** The server answered but rejected the submission; retrying won't help. */
        data class Rejected(val httpCode: Int) : SubmitResult()

        /** Network-level failure; worth retrying later. */
        object NetworkFailure : SubmitResult()
    }

    /**
     * Submit one intake item synchronously. Call from a background dispatcher.
     *
     * @param submission the submission
     * @return the submit result
     */
    fun submit(submission: IntakeSubmission): SubmitResult {
        val formBuilder = FormBody.Builder()
            .add("key", INTAKE_KEY)
            .add("kind", submission.linkKind)

        // Text-only submissions (Educate Me panel) carry no URL at all.
        if (submission.linkUrl.isNotBlank()) formBuilder.add("url", submission.linkUrl)

        submission.linkTitle?.takeIf { it.isNotBlank() }?.let { formBuilder.add("title", it) }
        submission.pastedBody?.takeIf { it.isNotBlank() }?.let { formBuilder.add("text", it) }
        submission.whyNote?.takeIf { it.isNotBlank() }?.let { formBuilder.add("why", it) }

        val request = Request.Builder()
            .url(INTAKE_URL)
            .post(formBuilder.build())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Timber.w("$TAG: HTTP ${response.code} for ${submission.linkUrl}: $body")
                    return if (response.code in 500..599) {
                        SubmitResult.NetworkFailure
                    } else {
                        SubmitResult.Rejected(response.code)
                    }
                }

                val json = runCatching { JSONObject(body) }.getOrNull()
                val ok = json?.optBoolean("ok", false) ?: false
                val dup = json?.optBoolean("dup", false) ?: false

                when {
                    ok && dup -> {
                        Timber.i("$TAG: Duplicate URL ${submission.linkUrl}")
                        SubmitResult.Duplicate
                    }

                    ok -> {
                        Timber.i("$TAG: Saved ${submission.linkUrl} (note_id=${json?.opt("note_id")})")
                        SubmitResult.Saved
                    }

                    else -> {
                        Timber.w("$TAG: Unexpected 200 body for ${submission.linkUrl}: $body")
                        SubmitResult.Rejected(response.code)
                    }
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "$TAG: Network error submitting ${submission.linkUrl}")
            SubmitResult.NetworkFailure
        }
    }
}
