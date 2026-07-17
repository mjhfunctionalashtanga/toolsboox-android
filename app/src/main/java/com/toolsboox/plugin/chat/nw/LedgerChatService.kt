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
     * block from [com.toolsboox.plugin.chat.fi.LedgerCorpusService.buildContext]. [provider]
     * selects Anthropic Claude ("anthropic") or OpenAI ("openai"); each uses its own key.
     */
    fun ask(provider: String, apiKey: String, model: String, question: String, context: String,
            persona: String? = null): Result {
        val label = if (provider == OPENAI) "OpenAI" else "Claude"
        if (apiKey.isBlank()) return Result.Err("Add your $label API key in Settings first.")

        val system = buildString {
            // A saved persona (optional) sets the voice/role; the grounding rules below still apply.
            persona?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it); append("\n\n") }
            append("You are the reader's own Ledger — a warm, precise assistant that answers ")
            append("ONLY from the excerpts below (their book highlights, feed annotations, planner ")
            append("notes and A/V grams). Cite the bracketed source tags you draw from, e.g. ")
            append("[2026-07-13 · book · Light on Yoga]. If the excerpts don't cover it, say so ")
            append("plainly rather than inventing. Keep it grounded and human.\n\n")
            // Create capability: the reader can ask to add a task, schedule an event, or make a note.
            append("Today is ${java.time.LocalDate.now()}. When (and ONLY when) the reader asks you to ")
            append("create something — \"add a task\", \"schedule…\", \"make a note\", \"remind me\" — do it by ")
            append("ending your reply with exactly one fenced block and nothing after it:\n")
            append("```ledger-create\n")
            append("[{\"kind\":\"task|event|note\",\"text\":\"…\",\"title\":\"…\",\"date\":\"YYYY-MM-DD\",\"time\":\"HH:MM\"}]\n")
            append("```\n")
            append("Resolve relative dates (\"tomorrow\") against today; omit \"date\" to mean today; omit ")
            append("\"time\" unless a time was given. \"note\" creates a new titled Text Note (give a short ")
            append("\"title\"); \"title\" is ignored for tasks/events. Give a brief natural confirmation BEFORE ")
            append("the block. Never emit the block unless asked to create.\n\n")
            append("=== LEDGER EXCERPTS ===\n")
            append(if (context.isBlank()) "(nothing found in the chosen scope)" else context)
        }

        val req = if (provider == OPENAI) openAiRequest(apiKey, model, system, question)
        else anthropicRequest(apiKey, model, system, question)

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.Err(errorMessage(label, resp.code, text))
                Result.Ok(if (provider == OPENAI) parseOpenAi(text) else parseAnthropic(text))
            }
        } catch (e: Exception) {
            Result.Err("Network error: ${e.message}")
        }
    }

    /**
     * "Educate me" — a general-knowledge lookup of a circled term (usually a BAND or a
     * BOOK). Unlike [ask], this is NOT corpus-restricted: it identifies the term and
     * returns a short description plus one canonical URL on its own final line, so the
     * caller can file it into the Later List's Educate section (→ feed with a link).
     */
    fun educate(provider: String, apiKey: String, model: String, term: String): Result {
        val label = if (provider == OPENAI) "OpenAI" else "Claude"
        if (apiKey.isBlank()) return Result.Err("Add your $label API key in Settings first.")

        val system = buildString {
            append("The reader circled a term in their notes — most often a BAND or a BOOK ")
            append("(sometimes an author, place, film, or idea). Identify it and give a warm, ")
            append("precise 2–3 sentence description of what it is and why it's worth knowing. ")
            append("Then, on its OWN FINAL LINE, give exactly one canonical URL — the official ")
            append("site, its Wikipedia page, or a Google search link — plain text, no markdown.")
        }
        val question = "Circled term: \"$term\""
        val req = if (provider == OPENAI) openAiRequest(apiKey, model, system, question)
        else anthropicRequest(apiKey, model, system, question)

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.Err(errorMessage(label, resp.code, text))
                Result.Ok(if (provider == OPENAI) parseOpenAi(text) else parseAnthropic(text))
            }
        } catch (e: Exception) {
            Result.Err("Network error: ${e.message}")
        }
    }

    /**
     * Run an arbitrary prompt over some text — the zone engine's per-zone AI prompts
     * (summarise a Notes zone, extract a quote, tidy OCR, …). Blocking; call off the main thread.
     */
    fun run(provider: String, apiKey: String, model: String, prompt: String, text: String): Result {
        val label = if (provider == OPENAI) "OpenAI" else "Claude"
        if (apiKey.isBlank()) return Result.Err("Add your $label API key in Settings first.")
        val req = if (provider == OPENAI) openAiRequest(apiKey, model, prompt, text)
        else anthropicRequest(apiKey, model, prompt, text)
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.Err(errorMessage(label, resp.code, body))
                Result.Ok(if (provider == OPENAI) parseOpenAi(body) else parseAnthropic(body))
            }
        } catch (e: Exception) {
            Result.Err("Network error: ${e.message}")
        }
    }

    private fun anthropicRequest(apiKey: String, model: String, system: String, question: String): Request {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
            .toString()
        return Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(json))
            .build()
    }

    private fun openAiRequest(apiKey: String, model: String, system: String, question: String): Request {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", question))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("messages", messages)
            .toString()
        return Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(json))
            .build()
    }

    /** Pull the concatenated text blocks out of the Anthropic Messages API response. */
    private fun parseAnthropic(payload: String): String {
        val content = JSONObject(payload).optJSONArray("content") ?: return "(empty reply)"
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().ifBlank { "(empty reply)" }
    }

    /** Pull the assistant message out of the OpenAI chat-completions response. */
    private fun parseOpenAi(payload: String): String {
        val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0) ?: return "(empty reply)"
        return choice.optJSONObject("message")?.optString("content")?.ifBlank { null } ?: "(empty reply)"
    }

    private fun errorMessage(label: String, code: Int, payload: String): String {
        val msg = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return "$label API error $code${if (!msg.isNullOrBlank()) ": $msg" else ""}"
    }

    companion object {
        const val ANTHROPIC = "anthropic"
        const val OPENAI = "openai"
        /** Balanced defaults per provider; overridable in Settings. */
        const val DEFAULT_MODEL = "claude-sonnet-5"
        const val DEFAULT_MODEL_OPENAI = "gpt-4o"
        fun defaultModel(provider: String) = if (provider == OPENAI) DEFAULT_MODEL_OPENAI else DEFAULT_MODEL
    }
}
