package com.toolsboox.plugin.calendar.nw

import android.graphics.Bitmap
import android.graphics.RectF
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

    // ---------------------------------------------------------------------------------
    // Tag word-boxes — approximate location of each #hashtag, FROM the vision LLM.
    // The model already reads the handwriting; a vision model can also point at WHERE it
    // read a word. We ask only for the boxes of #hashtags (cheap, small reply) and use them
    // to record a tag at its WORD rect instead of the whole capture zone, for a tighter Tags
    // jump. Approximate on purpose (no OCR dependency) — the harvest falls back to the zone
    // rect for any tag the model can't place, so this never lands worse than zone-level.
    // ---------------------------------------------------------------------------------

    private const val TAGBOX_PROMPT =
        "This image is a handwritten note that may contain #hashtags (a # written before a word). " +
            "For EACH #hashtag you can read, give its APPROXIMATE bounding box, normalized to THIS " +
            "image with the TOP-LEFT corner as origin and every value between 0.0 and 1.0. " +
            "Reply with ONLY this JSON array, nothing else:\n" +
            "[{\"tag\": \"#example\", \"x\": 0.0, \"y\": 0.0, \"w\": 0.0, \"h\": 0.0}]\n" +
            "x,y = the word's top-left corner; w,h = its width and height (all 0.0-1.0). " +
            "Include only #hashtags. If there are none, reply with []."

    /** A valid tag word (letter start, then word chars) — mirrors the body of LedgerTags.HASHTAG. */
    private val TAG_WORD = Regex("""[\p{L}][\p{L}\p{N}_-]{1,40}""")

    /**
     * Ask the vision model for each `#hashtag`'s approximate NORMALIZED box (0..1, top-left origin,
     * relative to [bitmap]). Returns a map of tag (case-folded, no leading '#') → RectF(l,t,r,b) in
     * 0..1. Tolerant JSON like the other structured calls: an empty map on no key, request failure,
     * a non-array reply, or any per-entry parse problem — the caller then falls back to the zone rect.
     */
    fun recognizeTagBoxes(bitmap: Bitmap, provider: String, apiKey: String, model: String): Map<String, RectF> {
        val raw = recognizeWithPrompt(bitmap, provider, apiKey, model, TAGBOX_PROMPT) ?: return emptyMap()
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = runCatching { JSONArray(cleaned) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, RectF>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tag = o.optString("tag", "").trim().removePrefix("#").lowercase()
            if (!TAG_WORD.matches(tag)) continue
            val rect = boxFrom(o) ?: continue
            // If the model lists a tag twice, keep the larger box (more likely the whole word).
            val prev = out[tag]
            if (prev == null || rect.width() * rect.height() > prev.width() * prev.height()) out[tag] = rect
        }
        return out
    }

    // ---------------------------------------------------------------------------------
    // URL word-boxes — where each link SITS on the page, for the linked-PDF export.
    // Same bargain as the tag boxes above: the vision model is already reading the page, and
    // it can also point at where it read something. A PDF link annotation needs a rectangle,
    // and there is no other source of one for handwriting — the on-device OCR gives text and
    // not geometry. Approximate on purpose: every link is ALSO listed on the exported PDF's
    // index page as exact, selectable text, so a rectangle that sits a little off costs a
    // slightly-misplaced tap target and never costs the link itself.
    // ---------------------------------------------------------------------------------

    private const val URLBOX_PROMPT =
        "This image is a page that may contain web addresses (URLs), handwritten or typed. " +
            "For EACH URL you can read, give the address and its APPROXIMATE bounding box, " +
            "normalized to THIS image with the TOP-LEFT corner as origin and every value between " +
            "0.0 and 1.0. Reply with ONLY this JSON array, nothing else:\n" +
            "[{\"url\": \"https://example.com/page\", \"x\": 0.0, \"y\": 0.0, \"w\": 0.0, \"h\": 0.0}]\n" +
            "x,y = the address's top-left corner; w,h = its width and height (all 0.0-1.0). " +
            "Transcribe the address exactly; do not invent or complete one you cannot read. " +
            "If there are none, reply with []."

    /**
     * Ask the vision model for each URL on [bitmap] and its NORMALIZED box (0..1, top-left origin).
     * Returns url → RectF(l,t,r,b) in 0..1, empty on no key / request failure / unparseable reply —
     * the export then falls back to the index page alone, which is the honest outcome rather than a
     * guessed rectangle.
     *
     * Only http/https survive: a `mailto:` or a bare `example.com` in the middle of a sentence is
     * more likely to be a misread than a link the reader wants to follow.
     */
    fun recognizeUrlBoxes(bitmap: Bitmap, provider: String, apiKey: String, model: String): Map<String, RectF> {
        val raw = recognizeWithPrompt(bitmap, provider, apiKey, model, URLBOX_PROMPT) ?: return emptyMap()
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = runCatching { JSONArray(cleaned) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, RectF>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url", "").trim().trimEnd('.', ',', ')', ']', '>', ';')
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (url.length < 12 || url.contains(' ')) continue
            val rect = boxFrom(o) ?: continue
            val prev = out[url]
            if (prev == null || rect.width() * rect.height() > prev.width() * prev.height()) out[url] = rect
        }
        return out
    }

    /** Normalized RectF from either {x,y,w,h} or {l,t,r,b}; clamped to 0..1, null if not positive-area. */
    private fun boxFrom(o: JSONObject): RectF? {
        val l: Float; val t: Float; val r: Float; val b: Float
        if (o.has("w") || o.has("h")) {
            val x = o.optDouble("x", Double.NaN); val y = o.optDouble("y", Double.NaN)
            val w = o.optDouble("w", Double.NaN); val h = o.optDouble("h", Double.NaN)
            if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN()) return null
            l = x.toFloat(); t = y.toFloat(); r = (x + w).toFloat(); b = (y + h).toFloat()
        } else {
            val ll = o.optDouble("l", Double.NaN); val tt = o.optDouble("t", Double.NaN)
            val rr = o.optDouble("r", Double.NaN); val bb = o.optDouble("b", Double.NaN)
            if (ll.isNaN() || tt.isNaN() || rr.isNaN() || bb.isNaN()) return null
            l = ll.toFloat(); t = tt.toFloat(); r = rr.toFloat(); b = bb.toFloat()
        }
        val cl = l.coerceIn(0f, 1f); val ct = t.coerceIn(0f, 1f)
        val cr = r.coerceIn(0f, 1f); val cb = b.coerceIn(0f, 1f)
        if (cr <= cl || cb <= ct) return null
        return RectF(cl, ct, cr, cb)
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
