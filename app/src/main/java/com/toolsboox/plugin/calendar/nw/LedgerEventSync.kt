package com.toolsboox.plugin.calendar.nw

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pushes structured EVENT items to the user's Google Calendar via the Calendar API v3 — the events
 * counterpart to [LedgerTaskSync] (which handles tasks over CalDAV). The item's stable `id` maps to a
 * deterministic Google event id (`SHA-256(id)` as lowercase hex — valid base32hex, and identical on
 * the iPad), so re-pushing the same item (edit, another device) updates in place rather than
 * duplicating. Off until the user connects Google Calendar in calendar settings.
 *
 * Google Calendar is reached through the Calendar API (reusing the Google sign-in the app already does
 * for Drive, plus the `calendar.events` scope) rather than a device sync account — so it works on a
 * bare Boox with no system Google account, and the target calendar is the user's own.
 */
object LedgerEventSync {

    const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events"
    const val ENABLED_KEY = "googleCalendarEnabled"
    const val CALENDAR_ID_KEY = "googleCalendarId"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Add or update the event. No-op for tasks / when Calendar isn't connected. */
    suspend fun pushEvent(context: Context, item: LedgerItem) = withContext(Dispatchers.IO) {
        if (item.kind != LedgerItem.Kind.EVENT) return@withContext
        if (item.text.isBlank()) return@withContext
        val token = accessToken(context) ?: return@withContext
        val gid = eventId(item.id)
        val body = eventJson(item, gid)
        try {
            // Update in place first; if it doesn't exist yet (404) or was previously deleted (410),
            // insert it with the same id.
            val putCode = send("PUT", eventsUrl(context) + "/" + gid, token, body)
            val finalCode = if (putCode == 404 || putCode == 410)
                send("POST", eventsUrl(context), token, body) else putCode
            // Every other failure used to vanish silently — an expired token meant events
            // never reached Google Calendar with zero trace. Record it so the UI can tell.
            if (finalCode in 200..299) {
                prefs(context).edit().remove(LAST_ERROR_KEY).apply()
            } else {
                recordFailure(context, finalCode, item.id)
            }
        } catch (e: Exception) {
            Timber.w(e, "Calendar event push failed for ${item.id}")
        }
    }

    /** Human-readable status of the last push ("" = healthy) — for settings/status surfaces. */
    fun lastError(context: Context): String =
        prefs(context).getString(LAST_ERROR_KEY, "") ?: ""

    private const val LAST_ERROR_KEY = "googleCalendarLastError"

    private fun recordFailure(context: Context, code: Int, itemId: String) {
        val hint = if (code == 401 || code == 403)
            "Google sign-in expired — reconnect in Calendar settings" else "HTTP $code"
        Timber.w("Calendar push failed for $itemId: $hint")
        prefs(context).edit().putString(LAST_ERROR_KEY, hint).apply()
    }

    /** Remove the event's Google Calendar entry (item deleted). Best-effort. */
    suspend fun deleteEvent(context: Context, item: LedgerItem) = withContext(Dispatchers.IO) {
        if (item.kind != LedgerItem.Kind.EVENT) return@withContext
        val token = accessToken(context) ?: return@withContext
        try {
            send("DELETE", eventsUrl(context) + "/" + eventId(item.id), token, null)
        } catch (e: Exception) {
            Timber.w(e, "Calendar event delete failed for ${item.id}")
        }
    }

    // MARK: - Config

    private fun prefs(context: Context) = context.getSharedPreferences("MAIN", Context.MODE_PRIVATE)

    private fun isEnabled(context: Context) = prefs(context).getBoolean(ENABLED_KEY, false)

    private fun calendarId(context: Context): String {
        val id = prefs(context).getString(CALENDAR_ID_KEY, "") ?: ""
        return if (id.isBlank()) "primary" else id
    }

    private fun eventsUrl(context: Context): String {
        val calId = java.net.URLEncoder.encode(calendarId(context), "UTF-8")
        return "https://www.googleapis.com/calendar/v3/calendars/$calId/events"
    }

    /** Calendar-scoped OAuth token for the last-signed-in Google account, or null if unavailable. */
    private fun accessToken(context: Context): String? {
        if (!isEnabled(context)) return null
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(CALENDAR_SCOPE))
            credential.selectedAccount = account
            credential.token   // blocking; throws UserRecoverableAuthException until scope is granted
        } catch (e: Exception) {
            Timber.w(e, "Calendar access token unavailable (connect Google Calendar in settings)")
            null
        }
    }

    // MARK: - HTTP

    /** Returns the HTTP status code so [pushEvent] can fall back to insert on 404. */
    private fun send(method: String, url: String, token: String, body: String?): Int {
        val reqBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url)
            .method(method, reqBody)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp -> return resp.code }
    }

    // MARK: - Event id

    /**
     * Deterministic Google event id: lowercase hex of SHA-256(item.id). Hex is a subset of the
     * base32hex alphabet Google requires (`[a-v0-9]`), and the same bytes hash identically on iOS,
     * so both apps address the one event.
     */
    fun eventId(itemId: String): String =
        MessageDigest.getInstance("SHA-256").digest(itemId.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // MARK: - Body

    private fun eventJson(item: LedgerItem, id: String): String {
        val event = JSONObject()
        event.put("id", id)
        event.put("summary", item.text)
        val tz = TimeZone.getDefault().id

        val range = timedRange(item)
        if (range != null) {
            event.put("start", JSONObject().put("dateTime", isoFmt.format(range.first)).put("timeZone", tz))
            event.put("end", JSONObject().put("dateTime", isoFmt.format(range.second)).put("timeZone", tz))
        } else {
            // All-day: end date is exclusive, so it's the day after.
            val cal = Calendar.getInstance().apply { time = item.date }
            val startDate = ymdFmt.format(item.date)
            cal.add(Calendar.DAY_OF_MONTH, 1)
            event.put("start", JSONObject().put("date", startDate))
            event.put("end", JSONObject().put("date", ymdFmt.format(cal.time)))
        }
        return event.toString()
    }

    /**
     * If the item carries a written time (e.g. "09:00"), a 60-minute block starting there; else null
     * (all-day). Parsed in the device timezone so it matches what the user wrote.
     */
    private fun timedRange(item: LedgerItem): Pair<Date, Date>? {
        val time = item.time?.trim() ?: return null
        if (time.isEmpty()) return null
        val parts = time.split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        val cal = Calendar.getInstance()
        cal.time = item.date
        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.time
        cal.add(Calendar.MINUTE, 60)
        return Pair(start, cal.time)
    }

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val ymdFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
}
