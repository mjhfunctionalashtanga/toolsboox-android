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
 *
 * THE KEY IS NOT IN THE BUILD. It used to be compiled in from local.properties as
 * BuildConfig.INTAKE_KEY, which put the same secret into every APK (debug, release, the R2 fleet
 * build) and, through the public fork, into the world's hands — and the copy it compiled had been
 * dead since the July rotation, so every B6 send got a 401 that the queue then quietly threw away.
 * Now the key is a Settings connector ([MichaelFilterConnector]) and the caller passes it in. No
 * key, no request: [submit] answers [SubmitResult.NotConnected] without touching the network.
 */
object MichaelFilterIntakeClient {

    private const val TAG = "MichaelFilterIntake"

    private const val INTAKE_URL = "https://mjh.yoga/wp-json/mjh-rss/v1/intake"

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

        /**
         * The server answered and refused THIS submission (a bad request, too big, gone for good):
         * retrying the same payload won't help, so the queue drops it. Only the codes in
         * [ITEM_REFUSALS] and an explicit `"ok": false` land here.
         */
        data class Rejected(val httpCode: Int) : SubmitResult()

        /**
         * The server turned the KEY down (a 401, or a 403 that WordPress itself wrote). Not the
         * item's fault: every queued item would get the same answer, so the queue keeps them all and
         * waits for a key to be saved.
         */
        object KeyRejected : SubmitResult()

        /** No key saved — nothing was sent. */
        object NotConnected : SubmitResult()

        /**
         * Nothing this item did: no network, the server having a bad moment (5xx), the route or
         * plugin missing (404), a timeout or rate limit (408/425/429), a firewall page, or a 2xx we
         * can't read. Kept and retried later — never dropped.
         */
        object NetworkFailure : SubmitResult()
    }

    /**
     * The refusals that are the ITEM's fault, and so the only ones that drop it. Everything else
     * outside 2xx is kept: the old rule ("any other 4xx drops the item") threw away the whole queue
     * on a 404 (route gone, plugin off) or a 429 exactly the way the dead key's 401 used to.
     */
    private val ITEM_REFUSALS = setOf(400, 409, 410, 413, 415, 422)

    /**
     * What an HTTP answer means — split out of [submit] so it is pinned by a unit test without a
     * server.
     *  - 2xx counts only when its JSON says `ok`; a 2xx we can't read (a maintenance or firewall
     *    page) is NOT a refusal of the item — it is kept for a retry.
     *  - 401 is the key. 403 is the key only when WordPress wrote it (a JSON body): Hostinger's
     *    firewall / bot challenge also answers 403, with an HTML page, and that is the network's
     *    problem, not the key's — the July 4G sends hit exactly that.
     *  - [ITEM_REFUSALS] are this one item; every other code is kept for a retry.
     */
    fun classify(httpCode: Int, body: String): SubmitResult {
        if (httpCode in 200..299) {
            val json = readJson(body, lenient = true)
            if (json == null || !json.has("ok")) return SubmitResult.NetworkFailure
            val ok = json.optBoolean("ok", false)
            val dup = json.optBoolean("dup", false)
            return when {
                ok && dup -> SubmitResult.Duplicate
                ok -> SubmitResult.Saved
                else -> SubmitResult.Rejected(httpCode)
            }
        }
        return when {
            httpCode == 401 -> SubmitResult.KeyRejected
            httpCode == 403 ->
                if (readJson(body, lenient = false) != null) SubmitResult.KeyRejected else SubmitResult.NetworkFailure
            httpCode in ITEM_REFUSALS -> SubmitResult.Rejected(httpCode)
            else -> SubmitResult.NetworkFailure
        }
    }

    /**
     * The body as a JSON object, or null. Strict: the whole (trimmed) body is one object — an HTML
     * page never passes, even one whose inline CSS org.json would happily half-parse. Lenient (2xx
     * only): also finds the object after a PHP notice printed ahead of it, so a save that worked
     * still reads as saved instead of being re-sent forever.
     */
    private fun readJson(body: String, lenient: Boolean): JSONObject? {
        val t = body.trim()
        if (t.startsWith("{") && t.endsWith("}")) {
            runCatching { JSONObject(t) }.getOrNull()?.let { return it }
        }
        if (!lenient) return null
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(t.substring(start, end + 1)) }.getOrNull()
    }

    /**
     * Submit one intake item synchronously. Call from a background dispatcher.
     *
     * @param submission the submission
     * @param key the MichaelFilter key from Settings; blank sends nothing
     * @return the submit result
     */
    fun submit(submission: IntakeSubmission, key: String): SubmitResult {
        if (key.isBlank()) return SubmitResult.NotConnected

        val formBuilder = FormBody.Builder()
            .add("key", key)
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
                val result = classify(response.code, body)
                // The log names the code and the link, never the key, and never the body of a
                // refusal (a server error page has no business in logcat either).
                when (result) {
                    is SubmitResult.Saved -> Timber.i("$TAG: Saved ${submission.linkUrl}")
                    is SubmitResult.Duplicate -> Timber.i("$TAG: Duplicate URL ${submission.linkUrl}")
                    is SubmitResult.KeyRejected -> Timber.w("$TAG: HTTP ${response.code} — the key was turned down")
                    else -> Timber.w("$TAG: HTTP ${response.code} for ${submission.linkUrl}")
                }
                result
            }
        } catch (e: IOException) {
            Timber.w(e, "$TAG: Network error submitting ${submission.linkUrl}")
            SubmitResult.NetworkFailure
        }
    }
}
