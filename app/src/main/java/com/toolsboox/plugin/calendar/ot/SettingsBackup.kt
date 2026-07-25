package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.plugin.calendar.nw.LedgerSite
import com.toolsboox.plugin.calendar.nw.SiteStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailAccountStore
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

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
 *  - The multi-site `sites` array (each site + its password + `activeSite`) and the `mail` accounts
 *    array (each account + its password) round-trip through SiteStore / MailAccountStore — the same
 *    stores the app actually reads — so a site or account configured on the iPad appears on the Boox
 *    and vice-versa. (They used to fall into passthrough: re-emitted but never ingested.)
 *  - Any OTHER section (opds, support, books, ttsOpenAI, prefs, …) or any field inside a section
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

        // Multi-site "Sites" system + mail accounts. These are ARRAYS (each entry carries its own
        // encrypted password), not scalar prefs, so they can't ride the field map above. They used to
        // fall into the opaque passthrough store — re-emitted but never actually ingested — which is
        // the data-loss bug: an export emitted zero mail accounts and only the single active site's
        // write-through creds. We now read them straight from SiteStore / MailAccountStore.
        //
        // Ports and SSL flags are written as STRINGS ("993", "1"/"0") EXACTLY as the iPad writes them
        // (see App/LedgerSettingsBackup.swift ~:73-93): iOS parses these fields with `as? String`, so
        // emitting raw ints/bools would make an iPad import silently drop the port and SSL flags.
        // Emitted last so live values override any stale copy the passthrough merge may have surfaced.
        runCatching {
            val sites = SiteStore.all(context)
            if (sites.isNotEmpty()) {
                val arr = JSONArray()
                for (s in sites) {
                    // LedgerSite.toJson() is already all-string (id/name/url/username/boardId/*Path),
                    // matching the iPad's site shape; we only add the per-site password.
                    arr.put(s.toJson().put("password", SiteStore.password(context, s.id)))
                }
                root.put("sites", arr)
                root.put("activeSite", SiteStore.activeId(context))
            }
        }.onFailure { Timber.w(it, "sites export failed") }

        runCatching {
            val accounts = MailAccountStore.all(context)
            if (accounts.isNotEmpty()) {
                val arr = JSONArray()
                for (a in accounts) {
                    arr.put(
                        JSONObject()
                            .put("id", a.id)
                            .put("displayName", a.displayName)
                            .put("email", a.email)
                            .put("username", a.username)
                            .put("imapHost", a.imapHost)
                            .put("imapPort", a.imapPort.toString())
                            .put("imapSSL", if (a.imapSSL) "1" else "0")
                            .put("smtpHost", a.smtpHost)
                            .put("smtpPort", a.smtpPort.toString())
                            .put("smtpSSL", if (a.smtpSSL) "1" else "0")
                            .put("password", MailAccountStore.password(context, a.id))
                    )
                }
                root.put("mail", arr)
            }
        }.onFailure { Timber.w(it, "mail export failed") }

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

        // Legacy single-provider AI shape ({provider, key, model}, pre per-provider split): the
        // provider names which per-provider prefs `key`/`model` belong to, so an old backup still
        // restores a working chat instead of silently dropping the credential.
        root.optJSONObject("ai")?.let { sec ->
            val suffix = if (sec.optString("provider") == "openai") "openai" else "anthropic"
            val chat = fields.first { it.section == "ai" }
            val key = sec.optString("key")
            if (key.isNotBlank()) {
                editor(chat).putString("ledger_chat_api_key_$suffix", key)
                appliedSections.add("ai")
            }
            val model = sec.optString("model")
            if (model.isNotBlank()) {
                editor(chat).putString("ledger_chat_model_$suffix", model)
                appliedSections.add("ai")
            }
        }

        editors.values.forEach { it.apply() }
        applied.addAll(appliedSections)

        // Multi-site "Sites" system: ACTUALLY ingest each site into SiteStore (create-or-update by id,
        // its password into the encrypted per-site key, then honour `activeSite`) instead of dropping
        // the array into passthrough where it was re-emitted but never applied. Non-destructive, exactly
        // like the scalar import above: upsert only creates/updates by id and never deletes sites that
        // aren't in the file. Every entry is guarded so one malformed record can't abort the import.
        root.optJSONArray("sites")?.let { arr ->
            var any = false
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.optJSONObject(i) ?: return@runCatching
                    // iPad site keys match LedgerSite.fromJson one-for-one (all string-valued).
                    val site = LedgerSite.fromJson(o)
                    if (site.url.isBlank()) return@runCatching   // mirror iOS: skip creds-less rows
                    SiteStore.upsert(context, site)
                    val p = o.optString("password", "")
                    if (p.isNotBlank()) SiteStore.setPassword(context, site.id, p)
                    any = true
                }.onFailure { Timber.w(it, "site import entry failed") }
            }
            // Re-point the live/write-through creds at whichever site the backup marks active.
            val active = root.optString("activeSite", "")
            if (active.isNotBlank()) runCatching { SiteStore.activate(context, active) }
                .onFailure { Timber.w(it, "activate site failed") }
            if (any) applied.add("sites")
        }

        // Mail accounts: ingest into MailAccountStore (create-or-update by id; password to the encrypted
        // per-account key). Ports arrive as strings and SSL as "1"/"0" (the iPad's shape); parse them to
        // match iOS semantics — MailAccount.fromJson's optBoolean would NOT coerce "1"/"0", so we build
        // the account by hand. Guarded per-entry; upsert never deletes accounts absent from the file.
        root.optJSONArray("mail")?.let { arr ->
            var any = false
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.optJSONObject(i) ?: return@runCatching
                    val a = MailAccount(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        displayName = o.optString("displayName", ""),
                        email = o.optString("email", ""),
                        username = o.optString("username", ""),
                        imapHost = o.optString("imapHost", ""),
                        imapPort = o.optString("imapPort", "").toIntOrNull() ?: 993,
                        imapSSL = o.optString("imapSSL", "1") != "0",
                        smtpHost = o.optString("smtpHost", ""),
                        smtpPort = o.optString("smtpPort", "").toIntOrNull() ?: 465,
                        smtpSSL = o.optString("smtpSSL", "1") != "0",
                    )
                    if (a.email.isBlank()) return@runCatching   // mirror iOS: skip address-less rows
                    MailAccountStore.upsert(context, a)
                    val p = o.optString("password", "")
                    if (p.isNotBlank()) MailAccountStore.setPassword(context, a.id, p)
                    any = true
                }.onFailure { Timber.w(it, "mail import entry failed") }
            }
            if (any) applied.add("mail")
        }

        // Capture everything Android didn't map — whole iOS-only sections and any unmapped fields
        // inside sections we only partially handle — so a later Android export re-emits them intact.
        val passthrough = JSONObject()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "app" || key == "version" || key == "platform") continue
            // Now natively modelled (ingested into SiteStore / MailAccountStore above) — must NOT fall
            // into passthrough, or they'd be re-emitted from there as a stale, un-ingested shadow copy.
            if (key == "sites" || key == "mail" || key == "activeSite") continue
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
        // Merge over the existing store, imported keys winning: a partial settings file must not
        // erase foreign sections (opds, books, ttsOpenAI, …) captured by an earlier import.
        val carried = readPassthrough(context)
        val newKeys = passthrough.keys()
        while (newKeys.hasNext()) {
            val key = newKeys.next()
            val nv = passthrough.get(key)
            val ov = carried.opt(key)
            if (nv is JSONObject && ov is JSONObject) {
                val fk = nv.keys()
                while (fk.hasNext()) {
                    val field = fk.next()
                    ov.put(field, nv.get(field))
                }
            } else carried.put(key, nv)
        }
        writePassthrough(context, carried)
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
