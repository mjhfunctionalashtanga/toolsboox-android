package com.toolsboox.plugin.calendar.nw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pushes structured TASK items to the Ultrabridge CalDAV `tasks` calendar as VTODOs, so the app —
 * not the server's OCR — becomes the source of tasks. The item's own stable `id` is the CalDAV UID,
 * so re-pushing the same item (edit, done-toggle, another device) overwrites rather than duplicates.
 *
 * Events are NOT pushed: this CalDAV only holds the `tasks` collection (VEVENT PUTs are rejected),
 * so events stay in the Ledger JSON + the WP render, exactly as before.
 *
 * Reuses the Ultrabridge WebDAV credentials the user already entered (same host, same admin auth).
 */
object LedgerTaskSync {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun creds(context: Context): Triple<String, String, String>? {
        return try {
            val prefs = EncryptedSharedPreferences.create(
                context, "ultrabridge_encrypted_prefs",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val url = prefs.getString("ultrabridge_webdav_url", "") ?: ""
            val user = prefs.getString("ultrabridge_webdav_user", "") ?: ""
            val pass = prefs.getString("ultrabridge_webdav_pass", "") ?: ""
            if (url.isBlank() || user.isBlank() || pass.isBlank()) null else Triple(url, user, pass)
        } catch (e: Exception) {
            Timber.w(e, "ultrabridge creds unavailable for task sync")
            null
        }
    }

    /** CalDAV tasks-collection base derived from the WebDAV URL (same host). */
    private fun tasksBase(webdavUrl: String): String {
        val u = URL(webdavUrl)
        val port = if (u.port != -1) ":${u.port}" else ""
        return "${u.protocol}://${u.host}$port/caldav/user/calendars/tasks/"
    }

    /** Push a single TASK item as a VTODO. No-op for events / when Ultrabridge isn't configured. */
    suspend fun pushTask(context: Context, item: LedgerItem) {
        if (item.kind != LedgerItem.Kind.TASK) return
        if (item.text.isBlank()) return
        val (url, user, pass) = creds(context) ?: return
        try {
            val target = tasksBase(url) + item.id + ".ics"
            val body = buildVTodo(item).toRequestBody("text/calendar; charset=utf-8".toMediaType())
            val req = Request.Builder().url(target).put(body)
                .header("Authorization", Credentials.basic(user, pass))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) Timber.w("CalDAV task PUT ${resp.code} for ${item.id}")
            }
        } catch (e: Exception) {
            Timber.w(e, "CalDAV task push failed for ${item.id}")
        }
    }

    /** Remove a task's VTODO (item deleted). Best-effort. */
    suspend fun deleteTask(context: Context, item: LedgerItem) {
        if (item.kind != LedgerItem.Kind.TASK) return
        val (url, user, pass) = creds(context) ?: return
        try {
            val req = Request.Builder().url(tasksBase(url) + item.id + ".ics").delete()
                .header("Authorization", Credentials.basic(user, pass)).build()
            client.newCall(req).execute().use { }
        } catch (e: Exception) {
            Timber.w(e, "CalDAV task delete failed for ${item.id}")
        }
    }

    private fun buildVTodo(item: LedgerItem): String {
        val stampFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = stampFmt.format(Date())
        val due = dateFmt.format(item.date)
        val status = if (item.done) "COMPLETED" else "NEEDS-ACTION"
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//toolsboox//ledger//EN\r\n")
        sb.append("BEGIN:VTODO\r\n")
        sb.append("UID:").append(item.id).append("\r\n")
        sb.append("DTSTAMP:").append(now).append("\r\n")
        sb.append("SUMMARY:").append(escape(item.text)).append("\r\n")
        sb.append("DUE;VALUE=DATE:").append(due).append("\r\n")
        sb.append("STATUS:").append(status).append("\r\n")
        if (item.done) {
            sb.append("PERCENT-COMPLETE:100\r\n")
            sb.append("COMPLETED:").append(now).append("\r\n")
        }
        sb.append("END:VTODO\r\n")
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /** iCal text escaping (RFC 5545): backslash, semicolon, comma, newline. */
    private fun escape(text: String): String = text
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\n", "\\n").replace("\r", "")
}
