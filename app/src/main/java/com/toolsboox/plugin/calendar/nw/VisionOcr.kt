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

    // ---------------------------------------------------------------------------------
    // Structured recognition — the confabulation brake (shared contract, BOTH platforms).
    // The model must return { text, legible, confidence, kind }; we DISCARD illegible /
    // low-confidence output, and a client-side brake rejects refusal-shaped text (a
    // confabulator's self-report is a weak witness on its own). Same prompt + threshold
    // on iOS — change them together or not at all.
    // ---------------------------------------------------------------------------------

    /** What a photo's handwriting turned out to be — routing decided by [kind]. */
    data class OcrResult(val text: String, val kind: String)   // kind: task | event | note | prose

    const val CONFIDENCE_THRESHOLD = 0.6

    // Keep this prompt IDENTICAL to the iOS vision-LLM fallback (see the parity handoff).
    private const val STRUCTURED_PROMPT =
        "This image is a photograph of handwritten content. Transcribe EXACTLY what is written — " +
            "verbatim, no guessing, no filling in. If you cannot read it, do not invent text. " +
            "Reply with ONLY this JSON, nothing else:\n" +
            "{\"text\": \"the exact transcription\", \"legible\": true|false, " +
            "\"confidence\": 0.0-1.0, \"kind\": \"task\"|\"event\"|\"note\"|\"prose\"}\n" +
            "kind: task = a to-do/action item; event = a dated/timed appointment; " +
            "note = a short note or list; prose = longer writing (a letter, recipe, passage). " +
            "If the image has no legible handwriting, reply {\"text\": \"\", \"legible\": false, " +
            "\"confidence\": 0.0, \"kind\": \"note\"}."

    /** Refusal/apology-shaped output — the model NARRATING instead of transcribing. Only
     *  unambiguous AI-refusal openers: real handwriting can legitimately start with
     *  "unable to…" / "appears to be…" (the reviewer's catch), so those stay OUT. */
    private val REFUSAL_MARKERS = listOf(
        "as an ai", "i'm sorry", "i am sorry", "i cannot read", "i can't read",
        "unable to read", "no legible", "cannot make out", "the image shows")

    /**
     * Recognize with the quality gate: null when there's no key, the request fails, the model
     * says illegible, confidence is under [CONFIDENCE_THRESHOLD], or the text reads like a
     * refusal. Falls back to treating a bare (non-JSON) reply as legacy plain text, gated by
     * the refusal brake only.
     */
    fun recognizeStructured(bitmap: Bitmap, provider: String, apiKey: String, model: String): OcrResult? {
        val raw = recognizeWithPrompt(bitmap, provider, apiKey, model, STRUCTURED_PROMPT) ?: return null
        // Strip a ```json fence if the model added one anyway.
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { JSONObject(cleaned) }.getOrNull()
        val text: String
        if (obj != null) {
            // Structured mode: a refusal arrives as legible:false — TRUST the structure and
            // do NOT head-scan the transcription (a real note reading "Unable to make it
            // Tuesday" must survive; the refusal brake is for the legacy prose path only).
            if (!obj.optBoolean("legible", false)) return null
            if (obj.optDouble("confidence", 0.0) < CONFIDENCE_THRESHOLD) return null
            text = obj.optString("text", "").trim()
            if (text.isBlank()) return null
            return OcrResult(text, obj.optString("kind", "note").lowercase().ifBlank { "note" })
        }
        // Legacy-shaped reply (plain text): keep it, but the refusal brake still applies.
        text = cleaned
        if (text.isBlank() || looksLikeRefusal(text)) return null
        return OcrResult(text, if (text.length > 200 || text.lines().size > 4) "prose" else "note")
    }

    private fun looksLikeRefusal(text: String): Boolean {
        val head = text.take(120).lowercase()
        return REFUSAL_MARKERS.any { head.contains(it) }
    }

    private fun recognizeWithPrompt(bitmap: Bitmap, provider: String, apiKey: String, model: String, prompt: String): String? {
        if (apiKey.isBlank()) return null
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
        val req = if (provider == OPENAI) openAi(b64, model, apiKey, prompt) else anthropic(b64, model, apiKey, prompt)
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

    private fun anthropic(b64: String, model: String, apiKey: String, prompt: String = PROMPT): Request {
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/png").put("data", b64)))
            .put(JSONObject().put("type", "text").put("text", prompt))
        val payload = JSONObject().put("model", model).put("max_tokens", 400)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return Request.Builder().url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(json)).build()
    }

    private fun openAi(b64: String, model: String, apiKey: String, prompt: String = PROMPT): Request {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
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
