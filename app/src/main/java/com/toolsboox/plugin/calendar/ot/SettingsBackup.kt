package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.plugin.calendar.nw.LedgerSite
import com.toolsboox.plugin.calendar.nw.SiteStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailAccountStore
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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

    // ── The passphrase envelope (pinned wire format, shared with the iPad) ────────────────────
    //
    //   "encryptedSecrets": { "v": 1, "kdf": "pbkdf2-hmac-sha256", "iter": 210000,
    //                         "salt": "<b64 16B>", "nonce": "<b64 12B>",
    //                         "ct": "<b64 AES-256-GCM ciphertext+tag>" }
    //
    // `ct` decrypts to a UTF-8 JSON object mapping secret field names ("webdav.pass",
    // "mail.<id>.password", …) to their values. Key = PBKDF2-HMAC-SHA256(passphrase, salt, iter,
    // 32 bytes) — SecretKeyFactory("PBKDF2WithHmacSHA256") UTF-8-encodes the char[] passphrase,
    // matching the iPad's `passphrase.utf8` — and AES/GCM/NoPadding appends the 16-byte tag to
    // the ciphertext, which is exactly the byte layout CryptoKit's AES.GCM emits and expects.
    // In the settings JSON itself every enveloped field is replaced by null, so the file keeps
    // its shape; a file with plaintext secrets and no envelope is a LEGACY backup and still
    // imports as before.

    private const val ENVELOPE_KEY = "encryptedSecrets"
    private const val PBKDF2_ITER = 210_000

    /**
     * Every secret-valued field of the cross-platform schema, as "section.field". Pinned by NAME
     * (not derived from [fields]) because Android must also protect fields it only carries via
     * passthrough (opds.pass, books.pass, support.pass, ttsOpenAI.key) — a re-export of an
     * iOS-made backup must never surface those in cleartext. "ai.key" is the legacy
     * single-provider shape, Android-only (iOS neither emits nor reads it). The array passwords
     * (mail/sites) are handled positionally, keyed "mail.<id>.password" / "sites.<id>.password"
     * — ids are UUIDs and carry no dots. The iPad derives the same scalar list from its
     * Keychain-backed keys; the two MUST stay in step or a backup made on one device silently
     * drops secrets on the other.
     */
    private val secretFields = setOf(
        "webdav.pass", "opds.pass", "miniflux.token", "feeds.secret", "support.pass",
        "books.pass", "ttsOpenAI.key", "ai.key", "ai.anthropicKey", "ai.openaiKey",
        "laterFeed.token", "bridgeBoards.pass", "bridgeCommunity.pass", "bridgeBluesky.secret"
    )

    /** True when a backup carries the envelope — ask BEFORE importing, so the UI can prompt. */
    fun hasEncryptedSecrets(json: String): Boolean =
        try { JSONObject(json).has(ENVELOPE_KEY) } catch (e: Exception) { false }

    /**
     * What an import did: the applied sections, plus how the envelope fared — so the UI can say
     * "wrong passphrase" as a clear, separate fact rather than a silent partial import.
     */
    data class ImportResult(
        val applied: List<String>,
        val hadEncryptedSecrets: Boolean = false,
        val secretsUnlocked: Boolean = false,
        val wrongPassphrase: Boolean = false
    )

    private fun deriveKey(passphrase: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iter, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun sealEnvelope(secrets: JSONObject, passphrase: String): JSONObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, PBKDF2_ITER), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(secrets.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", 1).put("kdf", "pbkdf2-hmac-sha256").put("iter", PBKDF2_ITER)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
    }

    /** null on ANY failure — a GCM tag mismatch (wrong passphrase) looks like corruption by design. */
    private fun openEnvelope(env: JSONObject, passphrase: String): JSONObject? = try {
        val salt = Base64.decode(env.getString("salt"), Base64.NO_WRAP)
        val nonce = Base64.decode(env.getString("nonce"), Base64.NO_WRAP)
        val ct = Base64.decode(env.getString("ct"), Base64.NO_WRAP)
        val iter = env.optInt("iter", PBKDF2_ITER)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iter), GCMParameterSpec(128, nonce))
        JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
    } catch (e: Exception) {
        Timber.w("settings secrets decrypt failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Pull every secret-valued field out of the finished export. Non-empty [passphrase]: the
     * values move into the envelope and `null` is written where each sat. Null/empty passphrase:
     * the fields are REMOVED outright — the export carries no secrets at all, and no envelope.
     */
    private fun sealSecrets(root: JSONObject, passphrase: String?) {
        val include = !passphrase.isNullOrEmpty()
        val secrets = JSONObject()
        for (path in secretFields) {
            val section = path.substringBefore('.')
            val field = path.substringAfter('.')
            val sec = root.optJSONObject(section) ?: continue
            if (sec.isNull(field)) continue                    // absent, or already null
            val v = sec.optString(field, "")
            if (v.isBlank()) { sec.remove(field); continue }
            if (include) { secrets.put(path, v); sec.put(field, JSONObject.NULL) } else sec.remove(field)
            if (sec.length() == 0) root.remove(section)
        }
        for (arrKey in listOf("mail", "sites")) {
            val arr = root.optJSONArray(arrKey) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.isNull("password")) { o.remove("password"); continue }
                val p = o.optString("password", "")
                if (p.isBlank()) { o.remove("password"); continue }
                val id = o.optString("id", "")
                if (include && id.isNotBlank()) {
                    secrets.put("$arrKey.$id.password", p)
                    o.put("password", JSONObject.NULL)
                } else {
                    o.remove("password")
                }
            }
        }
        // An encrypt failure throws out of exportJson (caller shows "Export failed") rather than
        // returning a file that silently lost every credential.
        if (include && secrets.length() > 0) root.put(ENVELOPE_KEY, sealEnvelope(secrets, passphrase!!))
    }

    /**
     * Fold decrypted secrets back into the parsed backup, at the exact spots the export nulled.
     * Unknown paths are ignored — a secret this build has no home for must not abort the rest.
     */
    private fun injectSecrets(root: JSONObject, secrets: JSONObject) {
        val keys = secrets.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val value = secrets.optString(path, "")
            if (value.isBlank()) continue
            val head = path.substringBefore('.')
            if ((head == "mail" || head == "sites") && path.endsWith(".password")) {
                val id = path.removePrefix("$head.").removeSuffix(".password")
                val arr = root.optJSONArray(head) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id") == id) o.put("password", value)
                }
            } else if (path.contains('.')) {
                val sec = root.optJSONObject(head) ?: JSONObject().also { root.put(head, it) }
                sec.put(path.removePrefix("$head."), value)
            }
        }
    }

    /**
     * Remove every JSONObject.NULL left after (or instead of) decryption, everywhere in the
     * tree. Two reasons, both load-bearing: Android's `optString` renders NULL as the literal
     * string "null" — an unstripped null would store "null" AS a password — and the passthrough
     * capture below must never hold a null where a real value (from an earlier, unlocked import)
     * already sits.
     */
    private fun stripNulls(node: JSONObject) {
        val names = node.names() ?: return
        for (i in 0 until names.length()) {
            val key = names.optString(i)
            when (val v = node.opt(key)) {
                JSONObject.NULL -> node.remove(key)
                is JSONObject -> stripNulls(v)
                is JSONArray -> for (j in 0 until v.length()) v.optJSONObject(j)?.let { stripNulls(it) }
            }
        }
    }

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

    /**
     * Build the settings JSON. A non-empty [passphrase] moves every secret-valued field into the
     * `encryptedSecrets` envelope (null left in its place); a null/empty passphrase exports
     * WITHOUT secrets at all. Cleartext secrets never leave this function either way.
     */
    fun exportJson(context: Context, passphrase: String?): String {
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

        // LAST, over the finished tree, so it covers the live fields, the passthrough-carried
        // iOS-only sections (opds/books/support/ttsOpenAI) AND the sites/mail arrays alike.
        sealSecrets(root, passphrase)

        return root.toString(2)
    }

    /**
     * Apply a backup. Only non-blank values overwrite. Legacy backups (plaintext secrets, no
     * envelope) import exactly as before. With an `encryptedSecrets` envelope: a non-empty
     * [passphrase] decrypts it and folds the values back in before the normal pass; a wrong
     * passphrase (GCM auth failure) or a null passphrase imports every non-secret field and
     * skips the secrets.
     */
    fun importJson(context: Context, json: String, passphrase: String? = null): ImportResult {
        val root = JSONObject(json)
        var hadSecrets = false
        var unlocked = false
        var wrong = false
        root.optJSONObject(ENVELOPE_KEY)?.let { env ->
            hadSecrets = true
            // Off the tree BEFORE the passthrough capture below — a stale envelope re-emitted
            // from passthrough would advertise secrets a later export doesn't actually carry.
            root.remove(ENVELOPE_KEY)
            if (!passphrase.isNullOrEmpty()) {
                val secrets = openEnvelope(env, passphrase)
                if (secrets != null) { unlocked = true; injectSecrets(root, secrets) } else wrong = true
            }
        }
        // With the secrets either restored or skipped, drop the placeholder nulls so neither the
        // pref writes nor the passthrough capture ever see JSONObject.NULL.
        stripNulls(root)
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
            // Defensive: a well-formed envelope was already lifted off the tree above, but a
            // malformed one (non-object) would land here and be re-emitted forever.
            if (key == ENVELOPE_KEY) continue
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
        return ImportResult(applied, hadSecrets, unlocked, wrong)
    }

    /**
     * Read one carried section by name — the door out of passthrough.
     *
     * Passthrough was built as a courtesy: hold iOS-only settings verbatim so an Android re-export
     * cannot destroy them. But "Android has no home for this" is a statement about a moment, not
     * forever — the OPDS catalog was carried across for months while nothing here could open it,
     * and the day Android grew a client the config was already on the device. This lets a new
     * feature claim what the sync was keeping for it, so Michael configures a thing once.
     */
    fun carriedSection(context: Context, name: String): JSONObject? =
        readPassthrough(context).optJSONObject(name)

    /** Write a carried section back, so a value set on Android survives the next export to iOS. */
    fun putCarriedSection(context: Context, name: String, section: JSONObject) {
        val root = readPassthrough(context)
        root.put(name, section)
        writePassthrough(context, root)
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
