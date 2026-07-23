package com.toolsboox.plugin.calendar.nw

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import timber.log.Timber

/**
 * A configured destination for panel cards — one per niche-industry template. The panel's
 * card PNG + OCR text is POSTed here (see [PanelWebhookClient]); offline-safe via
 * [PanelWebhookQueue]. Mirrors how Ultrabridge/intake push to their endpoints.
 */
@JsonClass(generateAdapter = true)
data class PanelWebhook(
    val name: String,
    val url: String,
    val key: String? = null
)

/** One queued panel-card delivery (persisted until the endpoint accepts it). */
@JsonClass(generateAdapter = true)
data class PanelWebhookJob(
    val webhookName: String,
    val url: String,
    val key: String?,
    val panelId: String,
    val title: String,
    val text: String,
    val page: String,
    val dateMs: Long,
    /** Absolute path to the card PNG sitting in the queue dir. */
    val imageFile: String
)

/** Persist the user's list of webhook destinations (plain prefs — URLs aren't secret). */
object PanelWebhookStore {
    private const val PREFS = "ledger_panel_webhooks"
    private const val KEY = "list"

    private val moshi = Moshi.Builder().build()
    private val listType = Types.newParameterizedType(List::class.java, PanelWebhook::class.java)
    private val adapter = moshi.adapter<List<PanelWebhook>>(listType)

    fun list(context: Context): List<PanelWebhook> {
        val json = context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: return emptyList()
        return runCatching { adapter.fromJson(json) }.getOrNull() ?: emptyList()
    }

    fun save(context: Context, hooks: List<PanelWebhook>) {
        runCatching {
            context.getSharedPreferences(PREFS, 0).edit().putString(KEY, adapter.toJson(hooks)).apply()
        }.onFailure { Timber.w(it, "PanelWebhookStore: save failed") }
    }

    fun add(context: Context, hook: PanelWebhook) {
        save(context, list(context).filterNot { it.name == hook.name } + hook)
    }

    fun remove(context: Context, name: String) {
        save(context, list(context).filterNot { it.name == name })
    }
}
