package com.toolsboox.plugin.calendar.nw

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Layer-2 high-quality OCR: send the ink image to a vision model (Claude or OpenAI, reusing the
 * Ask-my-Ledger per-provider key) and get back a clean transcription — far better on real
 * handwriting than the on-device ink model. Blocking; call off the main thread.
 */
object VisionOcr {

    const val ANTHROPIC = "anthropic"
    const val OPENAI = "openai"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private const val PROMPT =
        "This image is a handwritten note. Transcribe the handwriting exactly as written. " +
            "Reply with ONLY the transcription text — no quotation marks, no commentary, no labels. " +
            "If nothing is legible, reply with an empty line."

    /** Returns the transcription, or null on error / no key. */
    fun recognize(bitmap: Bitmap, provider: String, apiKey: String, model: String): String? {
        if (apiKey.isBlank()) return null
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
        val req = if (provider == OPENAI) openAi(b64, model, apiKey) else anthropic(b64, model, apiKey)
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Timber.w("VisionOcr %s error %d: %s", provider, resp.code, body.take(300)); return null }
                (if (provider == OPENAI) parseOpenAi(body) else parseAnthropic(body))?.trim()?.ifBlank { null }
            }
        } catch (e: Exception) {
            Timber.w(e, "VisionOcr request failed"); null
        }
    }

    private fun anthropic(b64: String, model: String, apiKey: String): Request {
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/png").put("data", b64)))
            .put(JSONObject().put("type", "text").put("text", PROMPT))
        val payload = JSONObject().put("model", model).put("max_tokens", 400)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return Request.Builder().url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(json)).build()
    }

    private fun openAi(b64: String, model: String, apiKey: String): Request {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PROMPT))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/png;base64,$b64")))
        val payload = JSONObject().put("model", model).put("max_tokens", 400)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey").addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(json)).build()
    }

    private fun parseAnthropic(payload: String): String? {
        val c = JSONObject(payload).optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until c.length()) c.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.let { sb.append(it.optString("text")) }
        return sb.toString()
    }

    private fun parseOpenAi(payload: String): String? =
        JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
}
