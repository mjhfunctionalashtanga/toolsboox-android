package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import org.json.JSONObject
import timber.log.Timber

/**
 * The one synced answer to "what hour does my day start at?" — and nothing else.
 *
 * THE DAY OWNS ITS OWN START HOUR. A day file's [CalendarDay.startHour] is data: once written it is
 * the hour that day's schedule grid was drawn from, and the ink sitting on that grid was placed
 * against it. Strokes are stored in absolute page coordinates, so re-stamping a written day with a
 * different hour does not move the handwriting — it slides the printed rows out from underneath it.
 *
 * That is exactly what happened. Michael set his day to start at 7am. Of his 124 synced days, 62
 * carried 5 (this fork's built-in default), 54 carried 6 (the iPad's hardcoded literal), and six
 * carried the 7 he had chosen: his preference survived on five percent of his own archive. Three
 * places each felt entitled to decide, and the day's own record lost every argument. The loudest was
 * right here on this fork — [com.toolsboox.plugin.calendar.fi.CalendarDayService.loadOrNull] used to
 * overwrite every loaded day's value with the local setting on the way past, so simply OPENING a day
 * on a device whose spinner said 5 rewrote it to 5 the next time anything saved.
 *
 * So this object is deliberately not a decision-maker. It is the seed a fork uses when minting a day
 * that does not exist yet, and the fallback a renderer uses when the day it was handed has no
 * opinion (`startHour == null`). A day that already carries a value is never touched.
 *
 * THE WIRE, pinned here and mirrored byte-for-byte by iOS's `PagePrefs` in LedgerCore:
 *
 *     page-prefs/prefs.json
 *     {"startHour":7,"updatedAt":1754006400000}
 *
 * A JSON OBJECT (not an array — the mail-reply sidecar next door is a list of rows; this is one row,
 * and pretending otherwise would invite a second entry nothing knows how to reconcile). `startHour`
 * is 0..23, `updatedAt` is epoch milliseconds, and the newest one wins — the same newer-side-wins
 * rule the calendar merge and [com.toolsboox.plugin.mail.MailReplyDrafts] already follow. Anything
 * else the server hands back — absent file, truncated write, an hour of 99 — is treated as no
 * opinion at all, and the built-in default stands.
 *
 * Round-tripped through [LedgerSidecarSync] on its single "sidecar-sync" thread, exactly like every
 * other small registry.
 */
object PagePrefs {

    /**
     * The hour a day starts at when nobody has said otherwise — no day value, no stored setting.
     *
     * FIVE, and iOS now agrees. The two forks disagreed (here 5, iPad 6) and one of them had to
     * give; 5 is what this fork's spinner, widget renderer and tap hit-testing have all defaulted to
     * for the life of the fork, and it is the hour on the plurality of Michael's existing days.
     */
    const val DEFAULT_START_HOUR = 5

    /**
     * "Leave empty" — the spinner's first entry, and a day page with no schedule grid at all. A
     * LOCAL choice only: the pinned wire is 0..23, so a device set to this simply doesn't speak on
     * the subject rather than publishing a value the other fork's decoder is required to reject.
     */
    const val NO_GRID = -1

    /** Where the sidecar lands, relative to the WebDAV root the day tree already syncs against. */
    const val PATH = "page-prefs/prefs.json"

    // The existing key, kept: it is what every device already has stored, and renaming it would
    // silently reset the setting to 5 on the exact devices this fix is for.
    private const val KEY_HOUR = "calendarStartHour"
    private const val KEY_STAMP = "calendarStartHourUpdatedAt"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("MAIN", Context.MODE_PRIVATE)

    fun isValidHour(hour: Int) = hour in 0..23

    /** An hour to actually draw with — the caller's value when it is one, the built-in otherwise. */
    fun sanitized(hour: Int?): Int = if (hour != null && isValidHour(hour)) hour else DEFAULT_START_HOUR

    /**
     * The device's setting: what a NEW day is stamped with, and what a day with no opinion is drawn
     * at. May be [NO_GRID]; anything else out of range reads as the built-in.
     */
    fun settingHour(context: Context): Int {
        val raw = runCatching { prefs(context).getInt(KEY_HOUR, DEFAULT_START_HOUR) }
            .getOrDefault(DEFAULT_START_HOUR)
        return if (raw == NO_GRID || isValidHour(raw)) raw else DEFAULT_START_HOUR
    }

    /**
     * THE HOUR TO DRAW A DAY AT: the day first, the setting second, never a literal.
     *
     * Null is not an error — a day that was written without an opinion is exactly when the setting
     * should speak. [NO_GRID] on the day IS an opinion and is kept.
     */
    fun startHourOf(context: Context, day: CalendarDay?): Int = day?.startHour ?: settingHour(context)

    /**
     * Michael changed the setting. Stamp it now so it beats every other device's older copy, and
     * push — the whole point of the sidecar is that the Boox and the iPad stop disagreeing.
     *
     * Writes the local pref whatever the value; publishes only a real 0..23 hour (see [NO_GRID]).
     */
    fun setStartHour(context: Context, hour: Int) {
        val now = System.currentTimeMillis()
        runCatching {
            prefs(context).edit().putInt(KEY_HOUR, hour).putLong(KEY_STAMP, now).apply()
        }.onFailure { Timber.w(it, "page prefs save failed") }
        sync(context)
    }

    /** One decoded record of the sidecar. */
    data class Record(val startHour: Int, val updatedAt: Long) {
        /** The exact bytes both forks write — fixed key order, so a round trip doesn't churn. */
        fun json(): String = """{"startHour":$startHour,"updatedAt":$updatedAt}"""
    }

    /**
     * Read a sidecar payload, or null when there is nothing trustworthy in it.
     *
     * Null rather than a defaulted record on purpose: a garbage file must not be laundered into a
     * well-formed preference carrying a fresh [Record.updatedAt], because that record would then WIN
     * the merge against the real setting on every other device.
     */
    fun parse(text: String?): Record? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(text)
            if (!o.has("startHour")) return null
            val hour = o.optInt("startHour", Int.MIN_VALUE)
            if (!isValidHour(hour)) return null
            Record(hour, o.optLong("updatedAt", 0L))
        }.getOrNull()
    }

    /** Newest [Record.updatedAt] wins; a tie keeps the local side, so a merge is stable run twice. */
    fun merge(local: Record?, remote: Record?): Record? {
        if (local == null) return remote
        if (remote == null) return local
        return if (remote.updatedAt > local.updatedAt) remote else local
    }

    private fun localRecord(context: Context): Record? {
        val hour = settingHour(context)
        if (!isValidHour(hour)) return null   // "leave empty" doesn't speak on the wire
        val stamp = runCatching { prefs(context).getLong(KEY_STAMP, 0L) }.getOrDefault(0L)
        return Record(hour, stamp)
    }

    /**
     * Round-trip the setting so the hour chosen here is the hour the iPad seeds its next day with.
     *
     * A remote file that is missing or unreadable leaves the local value untouched and re-pushes it,
     * which is how a first run seeds the server rather than being overwritten by nothing. A remote
     * that WINS is written straight into the same pref the spinner reads — no separate mirror, so
     * the settings screen shows the truth the moment it opens.
     */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        LedgerSidecarSync.background {
            val local = localRecord(appContext)
            val remote = parse(LedgerSidecarSync.pull(appContext, PATH))
            val winner = merge(local, remote) ?: return@background
            if (winner != local) {
                runCatching {
                    prefs(appContext).edit()
                        .putInt(KEY_HOUR, winner.startHour)
                        .putLong(KEY_STAMP, winner.updatedAt).apply()
                }.onFailure { Timber.w(it, "page prefs merge save failed") }
            }
            LedgerSidecarSync.push(appContext, PATH, winner.json())
        }
    }
}
