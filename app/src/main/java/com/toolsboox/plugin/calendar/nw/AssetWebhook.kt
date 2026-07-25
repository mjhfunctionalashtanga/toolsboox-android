package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * "⚡ Webhook" — the third destination of a gram's asset export: POST the rendered PNG to a
 * user-configured URL, the exact idiom of the feed star webhook (StarHooks.star_webhook_url +
 * optional shared secret), with `asset_webhook_url` / `asset_webhook_secret` living in the SAME
 * EncryptedSharedPreferences store. The user points it at whatever automation they run; empty
 * URL ⇒ the destination doesn't exist.
 *
 * Payload is multipart/form-data: the PNG as `file`, plus {title, date, sourceLabel, sourceLink}
 * fields — and `mediaUrl` when the same export also completed a WP media upload. The secret rides
 * as `X-MJH-Secret` when configured. Fire-and-forget.
 *
 * This is the first brick of the design doc's templates-in/out routing: an asset leaving the
 * Ledger addressed to an endpoint, rather than to a person via the share sheet.
 */
object AssetWebhook {

    /** Same store as the star webhook — one place to look for "where the Ledger writes out". */
    private const val PREFS = "ledger_feeds_prefs"
    const val KEY_URL = "asset_webhook_url"
    const val KEY_SECRET = "asset_webhook_secret"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun url(context: Context): String =
        try { prefs(context).getString(KEY_URL, "").orEmpty().trim() } catch (e: Exception) { "" }

    fun secret(context: Context): String =
        try { prefs(context).getString(KEY_SECRET, "").orEmpty().trim() } catch (e: Exception) { "" }

    fun configured(context: Context): Boolean = url(context).isNotBlank()

    fun save(context: Context, url: String, secret: String) {
        try {
            prefs(context).edit()
                .putString(KEY_URL, url.trim())
                .putString(KEY_SECRET, secret.trim())
                .apply()
        } catch (e: Exception) {
            Timber.w(e, "asset webhook: prefs save failed")
        }
    }

    /**
     * POST the asset. Call from Dispatchers.IO. True on any 2xx; false otherwise (the caller
     * toasts, nothing retries — fire-and-forget, like the star webhook).
     */
    fun post(
        context: Context, png: ByteArray, filename: String,
        title: String, date: String, sourceLabel: String, sourceLink: String,
        mediaUrl: String? = null,
    ): Boolean {
        val target = url(context)
        if (target.isBlank()) return false
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, png.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("title", title)
                .addFormDataPart("date", date)
                .addFormDataPart("sourceLabel", sourceLabel)
                .addFormDataPart("sourceLink", sourceLink)
                .apply { if (!mediaUrl.isNullOrBlank()) addFormDataPart("mediaUrl", mediaUrl) }
                .build()
            val req = Request.Builder().url(target)
                .apply { val s = secret(context); if (s.isNotBlank()) addHeader("X-MJH-Secret", s) }
                .post(body).build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.w(e, "asset webhook POST failed"); false
        }
    }
}
