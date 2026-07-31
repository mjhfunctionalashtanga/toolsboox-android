package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

/**
 * Persist the user's list of webhook destinations. Encrypted, not plain: this shipped as plain
 * prefs under a "URLs aren't secret" comment, but the record carries the destination's `key`
 * auth field, and keys ARE secret — same store idiom as [AssetWebhook].
 */
object PanelWebhookStore {
    private const val PREFS = "ledger_panel_webhooks_secure"
    /** The pre-encryption plain file; drained once by [migrate], then never written again. */
    private const val LEGACY_PREFS = "ledger_panel_webhooks"
    private const val KEY = "list"

    private val moshi = Moshi.Builder().build()
    private val listType = Types.newParameterizedType(List::class.java, PanelWebhook::class.java)
    private val adapter = moshi.adapter<List<PanelWebhook>>(listType)

    /** Exact builder as [AssetWebhook] — one idiom for where the Ledger keeps its send secrets. */
    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { migrate(context, it) }
    }

    /** One-time, silent: whatever the plain-prefs store still holds moves into the encrypted one
     *  (only when the encrypted side has nothing yet — an already-migrated store wins), and the
     *  plain file is cleared either way so the keys stop existing in cleartext. */
    private fun migrate(context: Context, secure: SharedPreferences) {
        val plain = context.getSharedPreferences(LEGACY_PREFS, 0)
        val json = plain.getString(KEY, null) ?: return
        if (secure.getString(KEY, null) == null) secure.edit().putString(KEY, json).apply()
        plain.edit().clear().apply()
    }

    fun list(context: Context): List<PanelWebhook> {
        val json = runCatching { prefs(context).getString(KEY, null) }
            .onFailure { Timber.w(it, "PanelWebhookStore: read failed") }
            .getOrNull() ?: return emptyList()
        return runCatching { adapter.fromJson(json) }.getOrNull() ?: emptyList()
    }

    fun save(context: Context, hooks: List<PanelWebhook>) {
        runCatching {
            prefs(context).edit().putString(KEY, adapter.toJson(hooks)).apply()
        }.onFailure { Timber.w(it, "PanelWebhookStore: save failed") }
    }

    fun add(context: Context, hook: PanelWebhook) {
        save(context, list(context).filterNot { it.name == hook.name } + hook)
    }

    fun remove(context: Context, name: String) {
        save(context, list(context).filterNot { it.name == name })
    }
}
