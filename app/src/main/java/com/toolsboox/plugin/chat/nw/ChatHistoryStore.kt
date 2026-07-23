package com.toolsboox.plugin.chat.nw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** One recorded Ask-my-Ledger exchange. */
data class ChatTurn(val question: String, val answer: String, val at: String)

/** Rolling history of Ask-my-Ledger exchanges (most recent kept), so past answers stay reviewable. */
object ChatHistoryStore {
    private const val MAX = 200
    private fun file(context: Context) = File(context.filesDir, "ledger-chat-history.json")

    private fun read(context: Context): JSONArray =
        runCatching { JSONArray(file(context).takeIf { it.exists() }?.readText() ?: "[]") }.getOrDefault(JSONArray())

    fun add(context: Context, question: String, answer: String) {
        val arr = read(context)
        arr.put(JSONObject().put("q", question.trim()).put("a", answer.trim())
            .put("at", java.time.LocalDateTime.now().toString()))
        val start = maxOf(0, arr.length() - MAX)
        val trimmed = JSONArray()
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        runCatching { file(context).writeText(trimmed.toString()) }
            .onFailure { Timber.w(it, "chat history save failed") }
    }

    /** Newest first. */
    fun all(context: Context): List<ChatTurn> = runCatching {
        val arr = read(context)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it); ChatTurn(o.optString("q"), o.optString("a"), o.optString("at"))
        }.reversed()
    }.getOrDefault(emptyList())
}
