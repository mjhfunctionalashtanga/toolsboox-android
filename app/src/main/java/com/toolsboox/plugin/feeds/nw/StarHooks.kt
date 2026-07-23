package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.FeedEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Two generic, opt-in, off-by-default reactions to STARRING a feed item — the building
 * blocks of "the Ledger writes back," with zero personal/bespoke code:
 *
 *  1. **Webhook-out** — POST the starred item to a user-set URL. The user points it at
 *     whatever automation they run (Michael → his VPS, which enriches and writes a
 *     correspondence ReadingEvent back into the synced day tree). Empty URL ⇒ no-op.
 *  2. **In-app synthesis** — run three user-editable questions through the user's own
 *     Ask AI key and write the combined answer straight into today's Ledger as a
 *     correspondence ReadingEvent (source starts with "↩", so the Log renders it as a
 *     reply and Ask/synthesis pick it up). Toggle off by default; needs an AI key.
 *
 * These are independent toggles — run one OR the other for a given star, not both, or you
 * get two write-backs. Both reuse existing machinery (a plain POST; LedgerChatService).
 */
object StarHooks {

    // Stored in the feeds plugin's EncryptedSharedPreferences (same store as URL/token).
    const val KEY_WEBHOOK_URL = "star_webhook_url"
    const val KEY_WEBHOOK_SECRET = "star_webhook_secret"
    const val KEY_AUTOSYNTH = "star_autosynth"
    const val KEY_Q1 = "star_synth_q1"
    const val KEY_Q2 = "star_synth_q2"
    const val KEY_Q3 = "star_synth_q3"

    val DEFAULT_QUESTIONS = listOf(
        "In two sentences, what is the core claim or idea here?",
        "What's the strongest objection or blind spot?",
        "What should I do, make, or think about next because of this?"
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    /** Fire whichever hooks are configured for a freshly-starred [entry]. Call from IO. */
    fun onNewStar(context: Context, prefs: android.content.SharedPreferences, entry: FeedEntry, writeReply: (title: String, body: String, url: String?) -> Unit) {
        val webhook = prefs.getString(KEY_WEBHOOK_URL, "").orEmpty().trim()
        if (webhook.isNotBlank()) postWebhook(webhook, prefs.getString(KEY_WEBHOOK_SECRET, "").orEmpty(), entry)

        if (prefs.getBoolean(KEY_AUTOSYNTH, false)) runSynthesis(context, prefs, entry, writeReply)
    }

    private fun postWebhook(url: String, secret: String, entry: FeedEntry) {
        try {
            val payload = JSONObject()
                .put("title", entry.title)
                .put("url", entry.url)
                .put("excerpt", entry.blurb)
                .put("feedTitle", entry.feedTitle)
                .put("starredAt", System.currentTimeMillis())
                .toString()
            val req = Request.Builder().url(url)
                .apply { if (secret.isNotBlank()) addHeader("X-Ledger-Secret", secret) }
                .post(payload.toRequestBody(jsonType)).build()
            http.newCall(req).execute().use { /* fire-and-forget */ }
        } catch (e: Exception) {
            Timber.w(e, "star webhook POST failed")
        }
    }

    private fun runSynthesis(
        context: Context, prefs: android.content.SharedPreferences, entry: FeedEntry,
        writeReply: (title: String, body: String, url: String?) -> Unit
    ) {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(context) ?: return
        val (provider, key, model) = creds
        val questions = listOf(
            prefs.getString(KEY_Q1, DEFAULT_QUESTIONS[0]).orEmpty().ifBlank { DEFAULT_QUESTIONS[0] },
            prefs.getString(KEY_Q2, DEFAULT_QUESTIONS[1]).orEmpty().ifBlank { DEFAULT_QUESTIONS[1] },
            prefs.getString(KEY_Q3, DEFAULT_QUESTIONS[2]).orEmpty().ifBlank { DEFAULT_QUESTIONS[2] }
        )
        // Feed the model the article's own text (blurb/content, tags stripped) as the source.
        val source = android.text.Html.fromHtml(
            entry.content.ifBlank { entry.blurb }, android.text.Html.FROM_HTML_MODE_LEGACY
        ).toString().replace(Regex("\\s+"), " ").trim().take(4000)
        val prompt = "Answer each question about the article below in 2–4 sentences, " +
            "labeling each answer with its question. Be concrete.\n\n" +
            questions.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")
        val chat = com.toolsboox.plugin.chat.nw.LedgerChatService()
        val res = chat.run(provider, key, model, prompt, "ARTICLE: ${entry.title}\n\n$source")
        if (res is com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok) {
            writeReply(entry.title, res.answer.trim(), entry.url)
        }
    }
}
