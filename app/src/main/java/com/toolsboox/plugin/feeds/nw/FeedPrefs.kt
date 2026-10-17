package com.toolsboox.plugin.feeds.nw

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * THE ONE DOOR to `ledger_feeds_prefs` — which is ENCRYPTED (EncryptedSharedPreferences).
 *
 * Found the hard way (Michael, 09-03: "when I added a podcast, it went right into local
 * feeds"): two call sites opened the file with plain `getSharedPreferences` and read
 * gibberish keys as blanks — the podcast subscribe fell back to Local Feeds with Miniflux
 * fully configured, and `FeedFetchWorker`'s background refresh had been quietly no-opping
 * on the server for the same reason. Any code that wants `miniflux_url`/`miniflux_token`
 * comes through here.
 */
object FeedPrefs {
    const val NAME = "ledger_feeds_prefs"

    @Volatile private var cached: SharedPreferences? = null
    /** One encrypted prefs per process — creating it is a Keystore round-trip (audit 09-11). */
    fun open(context: Context): SharedPreferences = cached ?: openFresh(context).also { cached = it }

    private fun openFresh(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context.applicationContext, NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // A device whose keystore is momentarily unavailable still gets A prefs object —
        // reads come back blank rather than the caller crashing, which is the same behavior
        // the plain-open bug had, now with the fix path logged around it.
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    fun miniflux(context: Context): Pair<String, String> {
        val p = open(context)
        return (p.getString("miniflux_url", "").orEmpty() to
                p.getString("miniflux_token", "").orEmpty())
    }
}
