package com.toolsboox.plugin.calendar.nw

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The day's booking roster: everyone signed up (FluentBooking), each a touchable card — tap to see
 * them, jump to their FluentCRM profile, and scribble a note that goes straight onto their CRM
 * timeline (handwriting → OCR → /crm/note). The bridge's roster endpoint carries name, photo, time,
 * status and the CRM contact id, so the day's people are one tap from their record.
 *
 * Reuses [LedgerWebBridge]'s stored site/user/pass creds (the same "Community & Boards" settings),
 * exactly as [LedgerBooking] and [LedgerBoards] do — and, like them, every call takes an optional
 * per-site [LedgerWebBridge.Config] so the roster (which lives on theyoga.club, where FluentBooking
 * runs) can be read without making that site globally active. All calls run on Dispatchers.IO and fail quietly
 * to an empty/null result, e-ink-quietly. Mirrors iOS `App/RosterView.swift` `RosterBridge`.
 */
object RosterBridge {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun auth(c: LedgerWebBridge.Config) = Credentials.basic(c.user, c.pass)

    /** One attendee on the roster. [time] is 'yyyy-MM-dd HH:mm:ss' in the device's own zone. */
    data class Attendee(
        val id: Int,
        val name: String,
        val email: String,
        val time: String,
        val status: String,
        val crmContactId: Int,
        val photo: String,
        val eventId: Int,
        val eventTitle: String,
        val announcement: String,
    ) {
        /** "HH:mm" carved out of "yyyy-MM-dd HH:mm:ss" — the slot label, matching iOS `clock`. */
        val clock: String get() = if (time.length >= 16) time.substring(11, 16) else time

        /** The day part ("yyyy-MM-dd") — the session day a connection joins the person to. */
        val day: String get() = if (time.length >= 10) time.substring(0, 10) else time

        /** Start as epoch millis in the device zone, or null when unparseable. */
        val startEpochMillis: Long?
            get() = runCatching {
                LocalDateTime.parse(time.trim(), WIRE)
                    .atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull()
    }

    private val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * The day's attendees ([date] = "yyyy-MM-dd"). Empty on any failure or when the bridge isn't
     * configured. Call from Dispatchers.IO.
     */
    fun roster(context: Context, date: String, cfg: LedgerWebBridge.Config? = null): List<Attendee> {
        val c = cfg ?: LedgerWebBridge.config(context)
        if (c.site.isBlank() || c.user.isBlank() || c.pass.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/booking/roster?date=$date")
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("roster")
                    ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { r ->
                    val id = r.optInt("id", 0)
                    if (id == 0) return@mapNotNull null
                    Attendee(
                        id = id,
                        name = r.optString("name", ""),
                        email = r.optString("email", ""),
                        time = r.optString("time", ""),
                        status = r.optString("status", ""),
                        crmContactId = r.optInt("crmContactId", 0),
                        photo = r.optString("photo", "").takeIf { it != "null" } ?: "",
                        eventId = r.optInt("eventId", 0),
                        eventTitle = r.optString("eventTitle", ""),
                        announcement = r.optString("announcement", ""),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "roster fetch failed")
            emptyList()
        }
    }

    /** One FluentCRM contact, as surfaced in the rolodex's CRM view (id, display name, email, avatar). */
    data class CrmContact(val id: Int, val name: String, val email: String, val photo: String)

    /**
     * The active site's FluentCRM contacts (name/email LIKE [search]), capped at 200. Empty on any
     * failure or when the bridge isn't configured — never throws. Blocking; call from Dispatchers.IO.
     * Mirrors [roster]'s client/timeouts/auth/`.use{}`/guarded-JSON idiom, but the endpoint returns a
     * bare JSON array of {id,name,email,photo}.
     */
    fun crmContacts(context: Context, search: String = "", cfg: LedgerWebBridge.Config? = null): List<CrmContact> {
        val c = cfg ?: LedgerWebBridge.config(context)
        if (c.site.isBlank() || c.user.isBlank() || c.pass.isBlank()) return emptyList()
        return try {
            val q = URLEncoder.encode(search, "UTF-8")
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/crm/contacts?search=$q&limit=200")
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: return emptyList())
                (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { r ->
                    val id = r.optInt("id", 0)
                    if (id == 0) return@mapNotNull null
                    CrmContact(
                        id = id,
                        name = r.optString("name", ""),
                        email = r.optString("email", ""),
                        photo = r.optString("photo", "").takeIf { it != "null" } ?: "",
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "crm contacts fetch failed")
            emptyList()
        }
    }

    /**
     * The next upcoming (scheduled, still in the future) attendee-slot today — for the appointment
     * nudge. Null when nothing is coming. Call from Dispatchers.IO.
     */
    fun nextToday(context: Context, cfg: LedgerWebBridge.Config? = null): Attendee? {
        val ds = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val now = System.currentTimeMillis()
        return roster(context, ds, cfg)
            .filter { it.status == "scheduled" && (it.startEpochMillis ?: Long.MIN_VALUE) >= now - 600_000L }
            .minByOrNull { it.startEpochMillis ?: Long.MAX_VALUE }
    }

    /**
     * Post a note onto a contact's CRM timeline (the endpoint matches on email too when the contact
     * id is unknown). Returns true on a 200. Call from Dispatchers.IO.
     */
    fun saveNote(context: Context, contactId: Int, email: String, text: String, cfg: LedgerWebBridge.Config? = null): Boolean {
        val c = cfg ?: LedgerWebBridge.config(context)
        if (c.site.isBlank() || text.trim().isEmpty()) return false
        return try {
            val payload = JSONObject()
                .put("note_uuid", UUID.randomUUID().toString().lowercase())
                .put("text", text)
                .put("contact_id", contactId)
                .put("email", email)
                .toString()
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/crm/note")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.w(e, "crm note save failed")
            false
        }
    }
}

/**
 * Which class types (FluentBooking event ids) should prompt you — before a session — to record and
 * pull up its notes. Opt-in per class, so a personal appointment never nags. Plus a once-a-day
 * dismissal so an opted-in class asks at most once. Mirrors iOS `RecordPrefs`.
 */
object RecordPrefs {
    private const val PREFS = "roster_record_prefs"
    private const val KEY_ENABLED = "record_prompt_events"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayStr(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    fun enabled(context: Context, eventId: Int): Boolean =
        ids(context).contains(eventId.toString())

    fun setEnabled(context: Context, eventId: Int, on: Boolean) {
        val s = ids(context).toMutableSet()
        if (on) s.add(eventId.toString()) else s.remove(eventId.toString())
        prefs(context).edit().putStringSet(KEY_ENABLED, s).apply()
    }

    private fun ids(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()

    fun dismissedToday(context: Context, eventId: Int): Boolean =
        prefs(context).getString(dismissKey(eventId), "") == todayStr()

    fun dismissToday(context: Context, eventId: Int) {
        prefs(context).edit().putString(dismissKey(eventId), todayStr()).apply()
    }

    private fun dismissKey(eventId: Int) = "record_dismissed_$eventId"
}
