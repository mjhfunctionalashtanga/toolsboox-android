package com.toolsboox.plugin.michaelfilter.nw

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.security.MessageDigest

/**
 * MICHAELFILTER IS A CONNECTOR NOW — a key you paste in Settings, not a secret baked into the APK.
 *
 * Michael's reading pipeline lives on his own server. Until 2026-10-17 its key was compiled into
 * every build from local.properties, so every install — his, a tester's, a stranger's — carried
 * his key and quietly sent filed links to his server; and the copy compiled in had been dead since
 * the July rotation, so the B6's sends were all turned away (401) and the queue threw each one out
 * without a word. Now (Michael, 2026-10-16: "I want the app to be valuable to others" — his
 * servers are optional connectors, never requirements): no key saved → nothing is
 * sent, the Later List and everything local work exactly as before, and the doors that only exist
 * to send say [NOT_CONNECTED]. A key the server refuses shows [KEY_TURNED_DOWN] once, and the
 * queue keeps every item until a key is saved, then sends them.
 *
 * Storage: the key sits in EncryptedSharedPreferences ([KEY_PREFS], the same scheme as the
 * Ultrabridge credentials) and rides the settings backup as the `michaelFilter.key` secret (sealed
 * in the passphrase envelope, absent from a no-passphrase export). NON-secret state sits in plain
 * private prefs ([STATE_PREFS]) so a door can ask "connected?" on the main thread without paying
 * for the encrypted store (200 ms–2 s to open on e-ink flash): whether a key is saved, the saved
 * key's [fingerprint], and — when the server refused one — the fingerprint of the key it refused
 * and when. "Turned down" is true only while the refused fingerprint IS the saved key's, so a pass
 * that was still running with an old key when a new one was saved can't brand the new one. The
 * state is rewritten from the key on every save, import, Settings visit and worker pass
 * ([refresh]), so it cannot drift far — and if it ever did, the worker reads the key itself and
 * sends nothing without one.
 *
 * Never logged, never shown: the key. What may be logged is [fingerprint] — four hex characters
 * of its SHA-256, enough to tell two keys apart, useless for rebuilding one.
 */
object MichaelFilterConnector {

    private const val TAG = "MichaelFilterConnector"

    /** Encrypted store holding the key. Also the store [com.toolsboox.plugin.calendar.ot.SettingsBackup] maps. */
    const val KEY_PREFS = "michaelfilter_connector_prefs"

    /** The key's pref name inside [KEY_PREFS]. */
    const val KEY_PREF = "intake_key"

    /** Plain, non-secret state: connected, the saved key's fingerprint, the refused key's and when. */
    private const val STATE_PREFS = "michaelfilter_connector_state"
    private const val STATE_CONNECTED = "connected"
    private const val STATE_KEY_FP = "key_fingerprint"
    private const val STATE_REFUSED_FP = "refused_fingerprint"
    private const val STATE_REFUSED_AT = "refused_at"

    /**
     * A refused key is held — nothing sent with it — for this long, then the next pass tries it once
     * more (one request: the pass stops at the first refusal). A refusal the server didn't mean (a
     * firewall that answered JSON, a plugin briefly off) heals within a day without a re-save.
     */
    const val RECHECK_AFTER_MS = 24L * 60 * 60 * 1000

    /** What a MichaelFilter-only door says when there is no key. */
    const val NOT_CONNECTED = "MichaelFilter isn't connected — add your key in Settings"

    /** The one message a refused key earns (the worker shows it once; Settings shows it while true). */
    const val KEY_TURNED_DOWN = "MichaelFilter turned the key down — check it in Settings"

    @Volatile private var cachedKeyPrefs: SharedPreferences? = null

    private fun keyPrefs(context: Context): SharedPreferences? {
        cachedKeyPrefs?.let { return it }
        return synchronized(this) {
            cachedKeyPrefs ?: runCatching {
                val app = context.applicationContext
                EncryptedSharedPreferences.create(
                    app, KEY_PREFS,
                    MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.onFailure { Timber.w("$TAG: key store unavailable (${it.javaClass.simpleName})") }
                .getOrNull()
                ?.also { cachedKeyPrefs = it }
        }
    }

    private fun state(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    /** Trimmed — a pasted key often drags a newline or a space along with it. */
    fun normalize(raw: String?): String = raw?.trim().orEmpty()

    /** Four hex characters of the key's SHA-256: safe to log, tells two keys apart. "none" when blank. */
    fun fingerprint(key: String): String {
        val k = normalize(key)
        if (k.isEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(k.toByteArray(Charsets.UTF_8))
        return digest.take(2).joinToString("") { "%02x".format(it) }
    }

    /** The saved key, "" when none. Opens the encrypted store — call off the main thread when you can. */
    fun key(context: Context): String =
        normalize(runCatching { keyPrefs(context)?.getString(KEY_PREF, "") }.getOrNull())

    /** Cheap: is a key saved? (Plain prefs — fine on the main thread.) */
    fun isConnected(context: Context): Boolean = state(context).getBoolean(STATE_CONNECTED, false)

    /** Cheap: did the server turn the SAVED key down? (A refusal of any other key doesn't count.) */
    fun isTurnedDown(context: Context): Boolean {
        val s = state(context)
        return isConnected(context) &&
            refusalCounts(s.getString(STATE_KEY_FP, null), s.getString(STATE_REFUSED_FP, null))
    }

    // ── the turned-down rules, pure so the unit tests pin them ─────────────────────────────────

    /** A refusal counts only for the key it was earned by. */
    fun refusalCounts(savedFp: String?, refusedFp: String?): Boolean =
        refusedFp != null && refusedFp == savedFp

    /**
     * Should a pass with [passFp] send nothing? Only while that very key was refused less than
     * [RECHECK_AFTER_MS] ago. A clock that went backwards counts as "due" rather than "held forever".
     */
    fun holdsRefusedKey(passFp: String, refusedFp: String?, refusedAt: Long, nowMs: Long): Boolean =
        refusedFp != null && refusedFp == passFp && (nowMs - refusedAt) in 0 until RECHECK_AFTER_MS

    /** A refusal from a pass that started with [passFp] is recorded only if that is still the saved key. */
    fun refusalApplies(passFp: String, savedFp: String?): Boolean = passFp == savedFp

    /** Write the non-secret state for a key just saved / found: connected, its fingerprint, no refusal. */
    private fun writeFreshState(context: Context, k: String) {
        state(context).edit()
            .putBoolean(STATE_CONNECTED, k.isNotEmpty())
            .apply { if (k.isEmpty()) remove(STATE_KEY_FP) else putString(STATE_KEY_FP, fingerprint(k)) }
            .remove(STATE_REFUSED_FP)
            .remove(STATE_REFUSED_AT)
            .apply()
    }

    /**
     * Save (or, with a blank [raw], remove) the key. Every save clears "turned down" — saving is
     * how you say "try this one" — and, when a key is now present, schedules the queue to send
     * what it has been holding. Returns the saved key's [fingerprint] ("none" for a removal), or
     * null when the encrypted store could not be opened and nothing was saved.
     */
    fun setKey(context: Context, raw: String?): String? {
        val k = normalize(raw)
        val prefs = keyPrefs(context)
        if (prefs == null) {
            Timber.w("$TAG: key not saved — key store unavailable")
            return null
        }
        synchronized(this) {
            prefs.edit().apply { if (k.isEmpty()) remove(KEY_PREF) else putString(KEY_PREF, k) }.apply()
            writeFreshState(context, k)
        }
        if (k.isNotEmpty()) runCatching { IntakeQueue.scheduleDrainAfterKeyChange(context) }
        return fingerprint(k)
    }

    /**
     * The key arrived some other way (a settings import wrote [KEY_PREFS] directly): re-derive the
     * connected flag, clear "turned down", and send what is queued.
     */
    fun keyChanged(context: Context) {
        val k = synchronized(this) { key(context).also { writeFreshState(context, it) } }
        if (k.isNotEmpty()) runCatching { IntakeQueue.scheduleDrainAfterKeyChange(context) }
    }

    /**
     * Re-derive the connected flag and the saved key's fingerprint from the key itself (Settings
     * does this on every visit, the worker on every pass) and return the key. A refusal is left
     * alone — it still counts only if it matches the key read here. Opens the encrypted store.
     */
    fun refresh(context: Context): String = synchronized(this) {
        val k = key(context)
        val s = state(context)
        val fp = if (k.isEmpty()) null else fingerprint(k)
        if (k.isNotEmpty() != isConnected(context) || fp != s.getString(STATE_KEY_FP, null)) {
            s.edit()
                .putBoolean(STATE_CONNECTED, k.isNotEmpty())
                .apply { if (fp == null) remove(STATE_KEY_FP) else putString(STATE_KEY_FP, fp) }
                .apply()
        }
        k
    }

    /** Should the worker hold the queue for [key]? True while that key's refusal is under a day old. */
    fun holdsRefusedKey(context: Context, key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = state(context)
        return holdsRefusedKey(fingerprint(key), s.getString(STATE_REFUSED_FP, null), s.getLong(STATE_REFUSED_AT, 0L), nowMs)
    }

    /**
     * The server refused [key] — the key the pass was SENT with. Recorded only when that is still
     * the saved key: a pass that began before a new key was saved keeps running (the HTTP call
     * blocks; WorkManager's REPLACE can't stop it), and its 401 says nothing about the new key.
     * Returns true only the FIRST time for this key, so the message is shown once, not every pass
     * (a daily re-check that is refused again just moves the clock).
     */
    fun markTurnedDown(context: Context, key: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(this) {
            val s = state(context)
            val fp = fingerprint(key)
            if (!refusalApplies(fp, s.getString(STATE_KEY_FP, null))) return@synchronized false
            val first = s.getString(STATE_REFUSED_FP, null) != fp
            s.edit().putString(STATE_REFUSED_FP, fp).putLong(STATE_REFUSED_AT, nowMs).apply()
            first
        }

    /** [key] was just accepted (a save or a duplicate came back): any refusal of it is over. */
    fun clearTurnedDown(context: Context, key: String) {
        synchronized(this) {
            val s = state(context)
            if (s.getString(STATE_REFUSED_FP, null) == fingerprint(key)) {
                s.edit().remove(STATE_REFUSED_FP).remove(STATE_REFUSED_AT).apply()
            }
        }
    }

    /** One plain line for Settings: not connected / connected / turned down, plus what is waiting. */
    fun statusLine(context: Context): String {
        val waiting = runCatching { IntakeQueue.pendingFiles(context).size }.getOrDefault(0)
        val tail = when (waiting) {
            0 -> ""
            1 -> " · 1 link waiting to send"
            else -> " · $waiting links waiting to send"
        }
        return when {
            !isConnected(context) -> "Not connected — nothing is sent to MichaelFilter.$tail"
            isTurnedDown(context) -> "$KEY_TURNED_DOWN$tail"
            else -> "Connected — links you file are sent to MichaelFilter.$tail"
        }
    }
}
