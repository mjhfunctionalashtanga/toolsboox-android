package com.toolsboox.plugin.mail

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * One email place the unified inbox can draw from: an address plus its IMAP (receive) and SMTP
 * (send) coordinates. Everything but the password is plain config; the password NEVER lives in this
 * object -- it sits in EncryptedSharedPreferences under mailpass_<id>, exactly the way
 * [com.toolsboox.plugin.calendar.nw.SiteStore] keeps a site's application password. Mirrors iOS
 * App/MailAccounts.swift MailAccount.
 */
data class MailAccount(
    val id: String = UUID.randomUUID().toString(),
    var displayName: String = "",
    var email: String = "",
    var username: String = "",       // login -- usually the same as the address
    var imapHost: String = "",
    var imapPort: Int = 993,
    var imapSSL: Boolean = true,
    var smtpHost: String = "",
    var smtpPort: Int = 465,         // implicit TLS (STARTTLS on 587 isn't supported by the client)
    var smtpSSL: Boolean = true,
) {
    /** The login used for both servers -- the address unless a separate username was given. */
    val loginName: String get() = username.ifBlank { email }
    val display: String
        get() = if (displayName.isNotBlank()) displayName else if (email.isNotBlank()) email else "Untitled account"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("displayName", displayName).put("email", email).put("username", username)
        .put("imapHost", imapHost).put("imapPort", imapPort).put("imapSSL", imapSSL)
        .put("smtpHost", smtpHost).put("smtpPort", smtpPort).put("smtpSSL", smtpSSL)

    companion object {
        fun fromJson(o: JSONObject) = MailAccount(
            o.optString("id", UUID.randomUUID().toString()),
            o.optString("displayName", ""),
            o.optString("email", ""),
            o.optString("username", ""),
            o.optString("imapHost", ""),
            o.optInt("imapPort", 993),
            o.optBoolean("imapSSL", true),
            o.optString("smtpHost", ""),
            o.optInt("smtpPort", 465),
            o.optBoolean("smtpSSL", true),
        )

        /** Common providers, so a new account is a name + password rather than four hostnames. */
        val presets: List<Triple<String, String, String>> = listOf(
            Triple("Gmail", "imap.gmail.com", "smtp.gmail.com"),
            Triple("iCloud", "imap.mail.me.com", "smtp.mail.me.com"),
            Triple("Fastmail", "imap.fastmail.com", "smtp.fastmail.com"),
            Triple("Outlook / Microsoft 365", "outlook.office365.com", "smtp.office365.com"),
            Triple("Yahoo", "imap.mail.yahoo.com", "smtp.mail.yahoo.com"),
        )
    }
}

/**
 * The configured email places. Accounts persist as a JSON array in EncryptedSharedPreferences
 * (ledgr_mail_prefs); each account's password lives in the SAME encrypted store under
 * mailpass_<id>. Deleting an account clears its password too. Mirrors iOS MailAccountStore, and
 * follows the storage shape of [com.toolsboox.plugin.calendar.nw.SiteStore].
 */
object MailAccountStore {
    private const val PREFS = "ledgr_mail_prefs"
    private const val KEY_ACCOUNTS = "mail_accounts"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences = cached ?: EncryptedSharedPreferences.create(
        context, PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).also { cached = it }

    fun all(context: Context): List<MailAccount> = try {
        val raw = prefs(context).getString(KEY_ACCOUNTS, "") ?: ""
        if (raw.isBlank()) emptyList()
        else JSONArray(raw).let { arr -> (0 until arr.length()).map { MailAccount.fromJson(arr.getJSONObject(it)) } }
    } catch (e: Exception) {
        Timber.w(e, "mail accounts read failed")
        emptyList()
    }

    fun save(context: Context, accounts: List<MailAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun upsert(context: Context, a: MailAccount) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list.add(a)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
        setPassword(context, id, "")     // drop the secret with the account
    }

    private fun passKey(id: String) = "mailpass_$id"
    fun password(context: Context, id: String): String = prefs(context).getString(passKey(id), "") ?: ""
    fun setPassword(context: Context, id: String, p: String) {
        prefs(context).edit().putString(passKey(id), p).apply()
    }
}
