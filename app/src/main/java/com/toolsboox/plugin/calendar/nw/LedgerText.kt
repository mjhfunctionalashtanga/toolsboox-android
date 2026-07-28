package com.toolsboox.plugin.calendar.nw

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A plain text completion — system prompt in, text out.
 *
 * [VisionOcr] already talks to both providers, but only ever about a BITMAP: every request it
 * builds carries an image block. Reading words that are already words needed a different shape,
 * and copying VisionOcr's request builders to strip the image out would have left two nearly
 * identical transports to keep in step. This is the text one, and it is deliberately the whole
 * surface: one function, no streaming, no tools.
 *
 * Mirrors the iPad's `LedgerLLM.run(prompt:on:)` — same two providers, same shape of answer — so a
 * prompt written once behaves the same on both devices.
 */
object LedgerText {

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Run [prompt] as the system instruction over [text]. Returns null on any failure — a missing
     * key, a refused request, a body that won't parse. Callers treat a null as "no answer", never
     * as an error to surface: this is always an enhancement to something that already worked.
     *
     * Blocking; call it off the main thread (the callers here use Dispatchers.IO).
     */
    fun complete(prompt: String, text: String, provider: String, apiKey: String, model: String): String? {
        if (apiKey.isBlank() || text.isBlank()) return null
        val request = if (provider == "openai") openAi(prompt, text, model, apiKey)
                      else anthropic(prompt, text, model, apiKey)
        return runCatching {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return null
                if (provider == "openai") parseOpenAi(body) else parseAnthropic(body)
            }
        }.getOrNull()
    }

    private fun anthropic(prompt: String, text: String, model: String, apiKey: String): Request {
        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", prompt)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", text)))
        return Request.Builder().url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    private fun openAi(prompt: String, text: String, model: String, apiKey: String): Request {
        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", text)))
        return Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    private fun parseAnthropic(payload: String): String? = runCatching {
        val blocks = JSONObject(payload).getJSONArray("content")
        (0 until blocks.length())
            .map { blocks.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "text" }
            ?.optString("text")
            ?.trim()
    }.getOrNull()

    private fun parseOpenAi(payload: String): String? = runCatching {
        JSONObject(payload).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
    }.getOrNull()
}
