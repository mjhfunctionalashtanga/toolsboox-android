package com.toolsboox.plugin.chat.nw

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Notebot tool-use loop — the Kotlin port of `LedgerLLM.converse` from the iOS fork
 * (ledger-ipad-correspondence/App/AskTools.swift), against the wire format PINNED in
 * DESIGN-NOTEBOT-VOICE.md §6: send the question with the registry's tools, execute whatever
 * the model calls, feed results back as `tool_result` / `role:"tool"` blocks, repeat — six
 * rounds max, the cap round forced to words with `tool_choice: none`, `max_tokens` 1024 per
 * call (a cost guardrail that is structural, not accounting).
 *
 * This SUPERSEDES the front-loaded-retrieval + ```ledger-create path for conversation; that
 * fenced-block path stays alive in [LedgerChatService] for the grounded one-shot callers
 * (educate, zone prompts) that never converse.
 */
object NotebotLoop {

    /** How many tool rounds a single question may spend before it must answer in words. */
    const val MAX_ROUNDS = 6

    sealed class Outcome {
        /** The words, and which tools the turn used along the way (call order, deduped) —
         *  the "· used search_ledger, notes_create" receipt caption. */
        data class Ok(val text: String, val toolsUsed: List<String>) : Outcome()
        data class Err(val message: String) : Outcome()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    /**
     * One conversational turn. Blocking (network + tool executors) — call from an IO
     * dispatcher. [history] is the last few ANSWERED turns of this session (question, answer)
     * — the model sees at most 8 of them (context is a cost surface; a conversation that
     * needs turn 40 needed a note at turn 39). [grounding] is the send-to-Ask provenance
     * preamble; it rides the system context, never the visible question.
     */
    fun converse(
        provider: String, apiKey: String, model: String,
        question: String, history: List<Pair<String, String>>,
        registry: ToolRegistry, persona: String? = null, grounding: String? = null,
    ): Outcome {
        val label = if (provider == LedgerChatService.OPENAI) "OpenAI" else "Claude"
        if (apiKey.isBlank()) return Outcome.Err("Add your $label API key in Settings first.")
        val system = toolSystem(persona, grounding)
        val past = history.takeLast(8)
        return if (provider == LedgerChatService.OPENAI)
            openAIToolLoop(apiKey, model, system, question, past, registry)
        else anthropicToolLoop(apiKey, model, system, question, past, registry)
    }

    /**
     * The tool-mode system prompt: no inlined corpus and no ```ledger-create block — retrieval
     * and creation are both tools now — and the confirmation discipline lives here: act, then
     * say plainly what was made.
     */
    private fun toolSystem(persona: String?, grounding: String?): String {
        val personaLine = persona?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it\n\n" } ?: ""
        val groundingBlock = grounding?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: ""
        return personaLine +
            "You are Notebot — the reader's own Ledger, a warm, precise assistant with real hands " +
            "and two reaches. The local tools read and write THIS device's Ledger (book and article " +
            "highlights, planner days, tasks, site boards, notes, grams); the notes_* tools reach the " +
            "reader's mjh.yoga /notes garden (their diary, shala-daily, pickings, seed notes, essays). " +
            "Today is ${java.time.LocalDate.now()}.\n\n" +
            "Ground every claim: call search_ledger before answering anything about what the reader " +
            "wrote, read, planned or said on this device; call notes_search or notes_search_semantic " +
            "for the /notes garden; call todays_plan for today's shape; call due_cards for the site " +
            "boards. Cite the bracketed source tags the results carry, e.g. " +
            "[2026-07-13 · book · Light on Yoga]. If the tools come back empty, say so plainly rather " +
            "than inventing.\n\n" +
            "When the reader asks you to create something, DO it — add_task, create_note, save_gram, " +
            "notes_create, notes_annotate_append, note_pin — resolving relative dates against today " +
            "first, then tell them plainly what you made (\"Added task: …\"). Never claim an action a " +
            "tool result doesn't confirm; if a tool reports a failure, say the failure plainly and do " +
            "not retry it on your own. Nothing here deletes, so act without asking permission — but " +
            "only create what was actually asked for. Keep answers grounded, human, and short enough " +
            "to be spoken." + groundingBlock
    }

    // MARK: - Anthropic wire

    private fun anthropicToolLoop(
        apiKey: String, model: String, system: String, question: String,
        history: List<Pair<String, String>>, registry: ToolRegistry,
    ): Outcome {
        val messages = JSONArray()
        for ((q, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        val used = mutableListOf<String>()

        for (round in 0 until MAX_ROUNDS) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1024)
                .put("system", system)
                .put("messages", messages)
                .put("tools", registry.anthropicTools())
            // The cap round forbids further calls, so the loop always ends in words (§6).
            if (round == MAX_ROUNDS - 1) body.put("tool_choice", JSONObject().put("type", "none"))

            val obj = when (val r = postJson(
                "https://api.anthropic.com/v1/messages", body,
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), "Claude")) {
                is Wire.Ok -> r.obj
                is Wire.Fail -> return Outcome.Err(r.message)
            }

            val content = obj.optJSONArray("content") ?: JSONArray()
            val toolUses = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                .filter { it.optString("type") == "tool_use" }
            if (obj.optString("stop_reason") == "tool_use" && toolUses.isNotEmpty()) {
                // Echo the assistant turn verbatim (text + tool_use blocks), then answer each
                // call with a tool_result keyed by its id — the Messages API contract.
                messages.put(JSONObject().put("role", "assistant").put("content", content))
                val results = JSONArray()
                for (block in toolUses) {
                    val name = block.optString("name")
                    if (name !in used) used += name
                    val out = registry.execute(name, block.optJSONObject("input") ?: JSONObject())
                    results.put(JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.optString("id"))
                        .put("content", out))
                }
                messages.put(JSONObject().put("role", "user").put("content", results))
                continue
            }
            val text = buildString {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
            return Outcome.Ok(text.ifBlank { "(empty reply)" }, used)
        }
        return Outcome.Ok("(stopped at the tool-round cap)", used)
    }

    // MARK: - OpenAI wire

    private fun openAIToolLoop(
        apiKey: String, model: String, system: String, question: String,
        history: List<Pair<String, String>>, registry: ToolRegistry,
    ): Outcome {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for ((q, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        val used = mutableListOf<String>()

        for (round in 0 until MAX_ROUNDS) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1024)
                .put("messages", messages)
                .put("tools", registry.openAITools())
            if (round == MAX_ROUNDS - 1) body.put("tool_choice", "none")

            val obj = when (val r = postJson(
                "https://api.openai.com/v1/chat/completions", body,
                mapOf("Authorization" to "Bearer $apiKey"), "OpenAI")) {
                is Wire.Ok -> r.obj
                is Wire.Fail -> return Outcome.Err(r.message)
            }

            val msg = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return Outcome.Ok("(empty reply)", used)
            val calls = msg.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                // Echo the assistant message with its tool_calls, then answer each with a
                // role:"tool" message keyed by tool_call_id — the function-calling contract.
                messages.put(msg)
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val fn = call.optJSONObject("function") ?: JSONObject()
                    val name = fn.optString("name")
                    if (name !in used) used += name
                    val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                        .getOrDefault(JSONObject())
                    val out = registry.execute(name, args)
                    messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("content", out))
                }
                continue
            }
            // optString renders a JSON null as the string "null" — treat it as empty.
            val text = msg.optString("content").trim().takeIf { it != "null" }.orEmpty()
            return Outcome.Ok(text.ifBlank { "(empty reply)" }, used)
        }
        return Outcome.Ok("(stopped at the tool-round cap)", used)
    }

    // MARK: - Shared transport

    private sealed class Wire {
        data class Ok(val obj: JSONObject) : Wire()
        data class Fail(val message: String) : Wire()
    }

    private fun postJson(url: String, body: JSONObject, headers: Map<String, String>, label: String): Wire {
        val builder = Request.Builder().url(url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(json))
        for ((k, v) in headers) builder.addHeader(k, v)
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    return Wire.Fail("$label API error ${resp.code}" +
                        (if (msg.isNullOrBlank()) "" else ": $msg"))
                }
                Wire.Ok(runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
            }
        } catch (e: Exception) {
            Wire.Fail("Network error: ${e.message}")
        }
    }
}
