package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Export/import of connection settings so a device is set up once and copied to the others, instead
 * of re-typing every WebDAV / RSS / bridge address + password. Uses the SAME cross-platform common
 * schema as the iPad (see App/LedgerSettingsBackup.swift), so a backup made on either device
 * restores on the other. Encrypted stores are read decrypted and re-encrypted on the target.
 *
 * Two guarantees that make round-tripping safe between an iPad and a Boox:
 *  - The AI section uses the iPad's per-provider shape (ai.provider / ai.anthropicKey / ai.openaiKey
 *    / ai.anthropicModel / ai.openaiModel), mapped onto Android's `ledger_chat_*` prefs. The old
 *    single-provider {key, model} shape silently carried nothing to the iPad.
 *  - Any section (opds, support, books, ttsOpenAI, prefs, mail, …) or any field inside a section
 *    Android does not have a home for is captured verbatim on import into an encrypted passthrough
 *    store and re-emitted on the next export, so an Android re-export NEVER destroys iOS-only
 *    settings.
 */
object SettingsBackup {

    private fun enc(context: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context, name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun plain(context: Context, name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun store(context: Context, name: String, encrypted: Boolean) =
        if (encrypted) enc(context, name) else plain(context, name)

    /** One backup field: which JSON section/key it lives under, and the SharedPreferences it maps to. */
    private data class Field(
        val section: String,
        val json: String,
        val storeName: String,
        val encrypted: Boolean,
        val prefKey: String,
        val isInt: Boolean = false
    )

    /** Encrypted store that carries unmapped iOS-only sections/fields across an Android round-trip. */
    private const val PASSTHROUGH_STORE = "ledger_settings_passthrough"

    /**
     * Every field iOS reads/writes that Android has a home for. Sections iOS owns but Android does
     * not (opds, support, books, ttsOpenAI, prefs, mail, and anything future) are NOT listed here —
     * they survive via the passthrough store instead.
     */
    private val fields = listOf(
        // WebDAV (Ultrabridge sync)
        Field("webdav", "url", "ultrabridge_encrypted_prefs", true, "ultrabridge_webdav_url"),
        Field("webdav", "user", "ultrabridge_encrypted_prefs", true, "ultrabridge_webdav_user"),
        Field("webdav", "pass", "ultrabridge_encrypted_prefs", true, "ultrabridge_webdav_pass"),
        // Miniflux RSS account
        Field("miniflux", "url", "ledger_feeds_prefs", true, "miniflux_url"),
        Field("miniflux", "token", "ledger_feeds_prefs", true, "miniflux_token"),
        // The star -> synthesize webhook (the rest of iOS's `feeds` section round-trips via passthrough)
        Field("feeds", "starWebhook", "ledger_feeds_prefs", true, "star_webhook_url"),
        // AI / chat — iPad's per-provider shape mapped onto Android's ledger_chat_* prefs
        Field("ai", "provider", "ledger_chat_encrypted_prefs", true, "ledger_chat_provider"),
        Field("ai", "anthropicKey", "ledger_chat_encrypted_prefs", true, "ledger_chat_api_key_anthropic"),
        Field("ai", "openaiKey", "ledger_chat_encrypted_prefs", true, "ledger_chat_api_key_openai"),
        Field("ai", "anthropicModel", "ledger_chat_encrypted_prefs", true, "ledger_chat_model_anthropic"),
        Field("ai", "openaiModel", "ledger_chat_encrypted_prefs", true, "ledger_chat_model_openai"),
        // Per-user Later List feed token
        Field("laterFeed", "token", "ledger_later_prefs", false, "later_feed_token"),
        // Google Calendar target
        Field("googleCalendar", "calendarId", "MAIN", false, "googleCalendarId"),
        // ledgr-fb-bridge — boards site (FluentBoards)
        Field("bridgeBoards", "url", "ledgr_bridge_prefs", true, "site"),
        Field("bridgeBoards", "user", "ledgr_bridge_prefs", true, "user"),
        Field("bridgeBoards", "pass", "ledgr_bridge_prefs", true, "pass"),
        Field("bridgeBoards", "boardId", "ledgr_bridge_prefs", true, "boardId", isInt = true),
        // ledgr-fb-bridge — community site (FluentCommunity)
        Field("bridgeCommunity", "url", "ledgr_bridge_prefs", true, "communitySite"),
        Field("bridgeCommunity", "user", "ledgr_bridge_prefs", true, "communityUser"),
        Field("bridgeCommunity", "pass", "ledgr_bridge_prefs", true, "communityPass")
    )

    private fun isHandled(section: String, field: String) =
        fields.any { it.section == section && it.json == field }

    fun exportJson(context: Context): String {
        val root = JSONObject().put("app", "ledger-settings").put("version", 1).put("platform", "android")
        val sections = LinkedHashMap<String, JSONObject>()

        for (f in fields) {
            val sp = store(context, f.storeName, f.encrypted)
            val sec = sections.getOrPut(f.section) { JSONObject() }
            if (f.isInt) {
                val n = sp.getInt(f.prefKey, 0)
                if (n != 0) sec.put(f.json, n.toString())
            } else {
                val v = sp.getString(f.prefKey, "") ?: ""
                if (v.isNotBlank()) sec.put(f.json, v)
            }
        }

        // Merge round-tripped iOS-only sections/fields captured on the last import. Live device
        // values set above win; passthrough only fills gaps and never overwrites a real cred.
        val passthrough = readPassthrough(context)
        val pKeys = passthrough.keys()
        while (pKeys.hasNext()) {
            val section = pKeys.next()
            val pv = passthrough.get(section)
            if (pv is JSONObject) {
                val target = sections.getOrPut(section) { JSONObject() }
                val fKeys = pv.keys()
                while (fKeys.hasNext()) {
                    val fk = fKeys.next()
                    if (!target.has(fk)) target.put(fk, pv.get(fk))
                }
            } else if (!sections.containsKey(section)) {
                // Non-object section (e.g. the mail array) Android doesn't model — emit verbatim.
                root.put(section, pv)
            }
        }

        for ((name, obj) in sections) if (obj.length() > 0) root.put(name, obj)
        return root.toString(2)
    }

    /** Apply a backup; returns the sections applied. Only non-blank values overwrite. */
    fun importJson(context: Context, json: String): List<String> {
        val root = JSONObject(json)
        val applied = mutableListOf<String>()
        val editors = HashMap<String, SharedPreferences.Editor>()
        fun editor(f: Field) = editors.getOrPut(f.storeName) { store(context, f.storeName, f.encrypted).edit() }

        val appliedSections = LinkedHashSet<String>()
        for (f in fields) {
            val sec = root.optJSONObject(f.section) ?: continue
            if (f.isInt) {
                val n = sec.optString(f.json).toIntOrNull()
                if (n != null && n != 0) {
                    editor(f).putInt(f.prefKey, n)
                    appliedSections.add(f.section)
                }
            } else {
                val v = sec.optString(f.json)
                if (v.isNotBlank()) {
                    editor(f).putString(f.prefKey, v)
                    appliedSections.add(f.section)
                }
            }
        }
        editors.values.forEach { it.apply() }
        applied.addAll(appliedSections)

        // Capture everything Android didn't map — whole iOS-only sections and any unmapped fields
        // inside sections we only partially handle — so a later Android export re-emits them intact.
        val passthrough = JSONObject()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "app" || key == "version" || key == "platform") continue
            val value = root.get(key)
            if (value is JSONObject) {
                val leftover = JSONObject()
                val fk = value.keys()
                while (fk.hasNext()) {
                    val field = fk.next()
                    if (!isHandled(key, field)) leftover.put(field, value.get(field))
                }
                if (leftover.length() > 0) passthrough.put(key, leftover)
            } else {
                // Non-object section (e.g. the mail array). Android has no model for it; keep it.
                passthrough.put(key, value)
                if (!applied.contains(key)) applied.add(key)
            }
        }
        writePassthrough(context, passthrough)
        return applied
    }

    private fun readPassthrough(context: Context): JSONObject =
        try {
            JSONObject(enc(context, PASSTHROUGH_STORE).getString("json", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun writePassthrough(context: Context, obj: JSONObject) {
        enc(context, PASSTHROUGH_STORE).edit().putString("json", obj.toString()).apply()
    }
}
