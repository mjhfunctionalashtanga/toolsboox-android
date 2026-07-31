package com.toolsboox.plugin.chat.nw

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Notebot's remote reach: one POST per tool to `https://mjh.yoga/wp-json/mjh/v1/notebot/{tool}`
 * with the `X-MJH-Ingest-Secret` header — the same credential the /notes ingest path uses
 * (DESIGN-NOTEBOT-VOICE.md §6). Responses come back `{status, message, ...}` — the
 * `{status, id, title, view_url, message}` shape the bot's functions already return.
 *
 * Contract for failures: timeouts are SHORT (a conversation is waiting), every failure is
 * returned as a plain English sentence the transcript says out loud, and nothing is EVER
 * retried silently — a spoken answer that quietly retried would be narrating a different
 * reality than the one that happened.
 *
 * The secret lives in the same `EncryptedSharedPreferences` file as the chat keys
 * (`ledger_chat_encrypted_prefs`, the standing Android equivalent of the iOS Keychain
 * `SecureStore`), under the `notes_ingest_secret` key the chat settings panel writes.
 */
object NotebotRemote {

    private const val BASE = "https://mjh.yoga/wp-json/mjh/v1/notebot/"
    private const val INGEST_URL = "https://mjh.yoga/wp-json/mjh/v1/notes/create-tagged"
    const val KEY_SECRET = "notes_ingest_secret"

    // 10-second ceiling per the brief: a remote tool that hasn't answered in 10s gets its
    // failure said, not waited on. No retries anywhere in this client.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = "application/json".toMediaType()

    /** The ingest secret, or null when the remote reach isn't configured. */
    fun secret(context: Context): String? = runCatching {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context, "ledger_chat_encrypted_prefs",
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString(KEY_SECRET, "")?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Call one notebot tool. [args] is posted verbatim as the JSON body. Blocking — the tool
     * loop calls this from an IO dispatcher. Always returns a sentence; never throws, never
     * retries.
     */
    fun call(context: Context, tool: String, args: JSONObject): String {
        val secret = secret(context)
            ?: return "The mjh.yoga notes reach isn't configured — add the notes ingest secret " +
                "in Ask settings. Nothing was done."
        val req = Request.Builder()
            .url(BASE + tool)
            .addHeader("X-MJH-Ingest-Secret", secret)
            .addHeader("Content-Type", "application/json")
            .post(args.toString().toRequestBody(json))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    return "mjh.yoga notes error ${resp.code} for $tool" +
                        (if (msg.isNullOrBlank()) "" else ": $msg") + " — not retried."
                }
                val obj = runCatching { JSONObject(body) }.getOrNull()
                    ?: return body.trim().take(4000).ifBlank { "$tool returned nothing." }
                val message = obj.optString("message").trim()
                if (obj.optString("status") == "error") {
                    return "mjh.yoga notes error for $tool" +
                        (if (message.isBlank()) "" else ": $message") + " — not retried."
                }
                // The message field carries the tool's cite-tagged text or its past-tense
                // confirmation; the raw body is the fallback so a read never comes back mute.
                message.ifBlank { body.trim().take(4000).ifBlank { "$tool returned nothing." } }
            }
        } catch (e: Exception) {
            Timber.w(e, "notebot remote %s failed", tool)
            "Couldn't reach mjh.yoga for $tool: ${e.message ?: "network error"} — not retried."
        }
    }

    /**
     * The create_note double-write: post a note to mjh.yoga /notes (`create-tagged`, the same
     * route the iOS create_note rides), tagged for this surface. Quietly skipped (returns
     * false) until the secret is configured; fire-and-forget when it is — the local note is
     * the copy the answer vouches for.
     */
    fun postNoteIngest(context: Context, title: String, markdown: String): Boolean {
        val secret = secret(context) ?: return false
        val payload = JSONObject()
            .put("title", title)
            .put("body", markdown)
            .put("tags", org.json.JSONArray(listOf("ask-chat", "Ask")))
            .put("idem_key", "ask-chat-" + (System.currentTimeMillis() / 1000))
        val req = Request.Builder()
            .url(INGEST_URL)
            .addHeader("X-MJH-Ingest-Secret", secret)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(json))
            .build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Timber.w(e, "notes ingest double-write failed")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
        return true
    }
}
