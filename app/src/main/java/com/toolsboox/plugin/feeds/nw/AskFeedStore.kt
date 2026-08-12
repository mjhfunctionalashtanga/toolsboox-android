package com.toolsboox.plugin.feeds.nw

import com.toolsboox.plugin.feeds.da.FeedEntry
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * A local "Ask my Ledger" feed — answers you chose to keep, surfaced as entries in the Feed Ledger
 * (question = title, answer = body). Local-only, newest first.
 */
object AskFeedStore {
    private fun file(context: Context) =
        File(context.filesDir, "ask-feed").apply { mkdirs() }.let { File(it, "entries.json") }

    private fun read(context: Context): JSONArray =
        runCatching { JSONArray(file(context).takeIf { it.exists() }?.readText() ?: "[]") }.getOrDefault(JSONArray())

    /** Save a Q&A. Returns true on success. */
    fun add(context: Context, question: String, answer: String): Boolean {
        val arr = read(context)
        arr.put(JSONObject()
            .put("id", System.currentTimeMillis())
            .put("q", question.trim())
            .put("a", answer.trim())
            .put("at", java.time.LocalDateTime.now().toString()))
        return runCatching { file(context).writeText(arr.toString()); true }
            .onFailure { Timber.w(it, "ask-feed save failed") }.getOrDefault(false)
    }

    /** Saved answers as feed entries (newest first) for the Feed Ledger. */
    fun list(context: Context): List<FeedEntry> = runCatching {
        val arr = read(context)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val body = o.optString("a")
            FeedEntry(
                id = o.optLong("id"),
                title = o.optString("q").ifBlank { "Answer" },
                feedTitle = "Ask my Ledger",
                url = "",
                author = null,
                // The answer is markdown (the model's native register) — render it to HTML for
                // the article view instead of escaping it raw, or the saved copy shows the same
                // `**`/`##` litter the chat pane used to. MarkdownHtml escapes on its way through.
                content = com.toolsboox.plugin.calendar.ot.MarkdownHtml.html(body),
                publishedAt = o.optString("at"),
                starred = false,
                category = null
            )
        }.reversed()
    }.getOrDefault(emptyList())
}
