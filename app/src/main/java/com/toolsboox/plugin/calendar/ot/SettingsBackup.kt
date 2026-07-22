package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Export/import of connection settings so a device is set up once and copied to the others, instead
 * of re-typing every WebDAV / RSS address + password. Uses the SAME cross-platform common schema as
 * the iPad ([webdav]/[miniflux]/[ai] here; the iPad adds opds/books/ttsOpenAI it owns), so a backup
 * made on either device restores on the other. Encrypted stores are read decrypted and re-encrypted
 * on the target.
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

    private fun JSONObject.putIfNotBlank(key: String, value: String?) {
        if (!value.isNullOrBlank()) put(key, value)
    }

    fun exportJson(context: Context): String {
        val root = JSONObject().put("app", "ledger-settings").put("version", 1).put("platform", "android")

        val ub = enc(context, "ultrabridge_encrypted_prefs")
        val webdav = JSONObject()
        webdav.putIfNotBlank("url", ub.getString("ultrabridge_webdav_url", ""))
        webdav.putIfNotBlank("user", ub.getString("ultrabridge_webdav_user", ""))
        webdav.putIfNotBlank("pass", ub.getString("ultrabridge_webdav_pass", ""))
        if (webdav.length() > 0) root.put("webdav", webdav)

        val feeds = enc(context, "ledger_feeds_prefs")
        val mf = JSONObject()
        mf.putIfNotBlank("url", feeds.getString("miniflux_url", ""))
        mf.putIfNotBlank("token", feeds.getString("miniflux_token", ""))
        if (mf.length() > 0) root.put("miniflux", mf)

        val chat = enc(context, "ledger_chat_encrypted_prefs")
        val provider = chat.getString("ledger_chat_provider", "") ?: ""
        if (provider.isNotBlank()) {
            val ai = JSONObject().put("provider", provider)
            ai.putIfNotBlank("key", chat.getString("ledger_chat_api_key_$provider", ""))
            ai.putIfNotBlank("model", chat.getString("ledger_chat_model_$provider", ""))
            root.put("ai", ai)
        }

        val main = plain(context, "MAIN")
        val gcal = JSONObject()
        gcal.putIfNotBlank("calendarId", main.getString("googleCalendarId", ""))
        if (gcal.length() > 0) root.put("googleCalendar", gcal)

        return root.toString(2)
    }

    /** Apply a backup; returns the sections applied. Only non-blank values overwrite. */
    fun importJson(context: Context, json: String): List<String> {
        val root = JSONObject(json)
        val applied = mutableListOf<String>()

        root.optJSONObject("webdav")?.let { w ->
            val e = enc(context, "ultrabridge_encrypted_prefs").edit()
            w.optString("url").takeIf { it.isNotBlank() }?.let { e.putString("ultrabridge_webdav_url", it) }
            w.optString("user").takeIf { it.isNotBlank() }?.let { e.putString("ultrabridge_webdav_user", it) }
            w.optString("pass").takeIf { it.isNotBlank() }?.let { e.putString("ultrabridge_webdav_pass", it) }
            e.apply()
            applied.add("webdav")
        }

        root.optJSONObject("miniflux")?.let { m ->
            val e = enc(context, "ledger_feeds_prefs").edit()
            m.optString("url").takeIf { it.isNotBlank() }?.let { e.putString("miniflux_url", it) }
            m.optString("token").takeIf { it.isNotBlank() }?.let { e.putString("miniflux_token", it) }
            e.apply()
            applied.add("miniflux")
        }

        root.optJSONObject("ai")?.let { a ->
            val provider = a.optString("provider")
            if (provider.isNotBlank()) {
                val e = enc(context, "ledger_chat_encrypted_prefs").edit()
                e.putString("ledger_chat_provider", provider)
                a.optString("key").takeIf { it.isNotBlank() }?.let { e.putString("ledger_chat_api_key_$provider", it) }
                a.optString("model").takeIf { it.isNotBlank() }?.let { e.putString("ledger_chat_model_$provider", it) }
                e.apply()
                applied.add("ai")
            }
        }

        root.optJSONObject("googleCalendar")?.let { g ->
            val e = plain(context, "MAIN").edit()
            g.optString("calendarId").takeIf { it.isNotBlank() }?.let { e.putString("googleCalendarId", it) }
            e.apply()
            applied.add("googleCalendar")
        }
        return applied
    }
}
