package com.toolsboox.plugin.chat.nw

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * "Ask my Ledger" over the Anthropic Messages API. Given the retrieved corpus context
 * and a question, it returns a grounded, citation-friendly answer. The API key lives in
 * EncryptedSharedPreferences (never committed) and is passed in by the caller.
 */
class LedgerChatService @Inject constructor() {

    sealed class Result {
        data class Ok(val answer: String) : Result()
        data class Err(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    /**
     * Blocking call — invoke off the main thread. [context] is the cite-prefixed corpus
     * block from [com.toolsboox.plugin.chat.fi.LedgerCorpusService.buildContext].
     */
    fun ask(apiKey: String, model: String, question: String, context: String): Result {
        if (apiKey.isBlank()) return Result.Err("Add your Claude API key in Settings first.")

        val system = buildString {
            append("You are the reader's own Ledger — a warm, precise assistant that answers ")
            append("ONLY from the excerpts below (their book highlights, feed annotations, planner ")
            append("notes and A/V grams). Cite the bracketed source tags you draw from, e.g. ")
            append("[2026-07-13 · book · Light on Yoga]. If the excerpts don't cover it, say so ")
            append("plainly rather than inventing. Keep it grounded and human.\n\n")
            append("=== LEDGER EXCERPTS ===\n")
            append(if (context.isBlank()) "(nothing found in the chosen scope)" else context)
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
            .toString()

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(json))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.Err(errorMessage(resp.code, text))
                Result.Ok(parseAnswer(text))
            }
        } catch (e: Exception) {
            Result.Err("Network error: ${e.message}")
        }
    }

    /** Pull the concatenated text blocks out of the Messages API response. */
    private fun parseAnswer(payload: String): String {
        val content = JSONObject(payload).optJSONArray("content") ?: return "(empty reply)"
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().ifBlank { "(empty reply)" }
    }

    private fun errorMessage(code: Int, payload: String): String {
        val msg = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return "Claude API error $code${if (!msg.isNullOrBlank()) ": $msg" else ""}"
    }

    companion object {
        /** Balanced default; overridable in Settings. */
        const val DEFAULT_MODEL = "claude-sonnet-5"
    }
}
