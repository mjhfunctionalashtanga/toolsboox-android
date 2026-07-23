package com.toolsboox.plugin.chat.nw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Generate an image from a prompt, IN the app, using the reader's own OpenAI key (the same one Ask
 * my Ledger uses when its provider is OpenAI) — so image→gram works for anyone with a key, not only
 * on sites that run AI Engine. Mirrors iOS `App/ImageGen.swift`: a POST to OpenAI
 * `v1/images/generations` (model gpt-image-1, 1024×1024), decoding `b64_json` (or downloading `url`).
 *
 * Blocking on purpose — call it off the main thread (Dispatchers.IO). Returns a sealed [Outcome] so
 * the caller can show the API's own message rather than catching exceptions across threads.
 */
object ImageGen {

    sealed class Outcome {
        data class Ok(val bitmap: Bitmap) : Outcome()
        data class Err(val message: String) : Outcome()
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** The OpenAI key from the shared chat prefs ("" = none). Mirrors iOS `ai_key_openai`. */
    fun apiKey(context: Context): String = runCatching {
        val prefs = EncryptedSharedPreferences.create(
            context, "ledger_chat_encrypted_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString("ledger_chat_api_key_openai", "")?.trim().orEmpty()
    }.getOrDefault("")

    fun configured(context: Context): Boolean = apiKey(context).isNotBlank()

    /** A prompt → an image. [size] is one of 1024x1024 / 1024x1536 / 1536x1024. */
    fun generate(context: Context, prompt: String, size: String = "1024x1024"): Outcome {
        val key = apiKey(context)
        if (key.isBlank()) return Outcome.Err("Add your OpenAI key in Settings to generate images.")

        val body = JSONObject()
            .put("model", "gpt-image-1")
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", size)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (!resp.isSuccessful) {
                    val msg = obj?.optJSONObject("error")?.optString("message").orEmpty()
                    return Outcome.Err(msg.ifBlank { "Image request failed (${resp.code})." })
                }
                val first = obj?.optJSONArray("data")?.optJSONObject(0)
                    ?: return Outcome.Err("No image returned.")
                // gpt-image-1 returns base64; some models/settings return a URL.
                val b64 = first.optString("b64_json", "")
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    return if (bmp != null) Outcome.Ok(bmp) else Outcome.Err("Couldn't decode the image.")
                }
                val url = first.optString("url", "")
                if (url.isNotBlank()) {
                    client.newCall(Request.Builder().url(url).build()).execute().use { imgResp ->
                        val imgBytes = imgResp.body?.bytes()
                        val bmp = imgBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        return if (bmp != null) Outcome.Ok(bmp) else Outcome.Err("Couldn't decode the image.")
                    }
                }
                Outcome.Err("Couldn't decode the image.")
            }
        } catch (e: Exception) {
            Timber.w(e, "image generation failed")
            Outcome.Err(e.localizedMessage ?: "Image request failed.")
        }
    }
}
