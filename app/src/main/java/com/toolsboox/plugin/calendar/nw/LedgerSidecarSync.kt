package com.toolsboox.plugin.calendar.nw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

/**
 * One shared WebDAV entry point for the small "sidecar" stores (Text Notes, Intake pages, Pickings
 * board names, Synthesis ideas, reader position) that live outside the main day JSON. Centralises the
 * Ultrabridge credential read + service construction so each store doesn't re-implement it, and gives
 * them a pull/push that round-trips instead of the old push-only-clobber pattern.
 *
 * All calls block on the network — invoke off the main thread.
 */
object LedgerSidecarSync {

    /** A configured WebDAV service, or null when Ultrabridge isn't set up (no URL). */
    fun service(context: Context): UltrabridgeWebDavService? {
        return runCatching {
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext, "ultrabridge_encrypted_prefs",
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val url = prefs.getString("ultrabridge_webdav_url", "").orEmpty()
            if (url.isBlank()) return null
            UltrabridgeWebDavService(
                url,
                prefs.getString("ultrabridge_webdav_user", "").orEmpty(),
                prefs.getString("ultrabridge_webdav_pass", "").orEmpty()
            )
        }.onFailure { Timber.w(it, "LedgerSidecarSync: service init failed") }.getOrNull()
    }

    /** Download [remotePath] as UTF-8 text, or null if absent / no creds / offline. */
    fun pull(context: Context, remotePath: String): String? =
        service(context)?.let { svc ->
            runCatching { svc.download(remotePath)?.toString(Charsets.UTF_8) }
                .onFailure { Timber.w(it, "LedgerSidecarSync: pull failed for $remotePath") }.getOrNull()
        }

    /** Upload [text] to [remotePath] (overwrite). No-op if Ultrabridge isn't configured. */
    fun push(context: Context, remotePath: String, text: String) {
        val svc = service(context) ?: return
        runCatching { svc.uploadBytes(text.toByteArray(Charsets.UTF_8), remotePath) }
            .onFailure { Timber.w(it, "LedgerSidecarSync: push failed for $remotePath") }
    }

    /** Run [block] on a daemon thread (the stores' sync is fire-and-forget from UI callers). */
    fun background(block: () -> Unit) {
        Thread { runCatching { block() }.onFailure { Timber.w(it, "LedgerSidecarSync: bg task failed") } }
            .apply { isDaemon = true }.start()
    }
}
