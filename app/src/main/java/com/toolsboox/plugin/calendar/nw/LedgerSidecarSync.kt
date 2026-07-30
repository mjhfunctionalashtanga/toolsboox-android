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
 * Every call that touches the network blocks on it — invoke those off the main thread. The
 * "why is this list empty" reads at the foot of this file deliberately do not: they answer from a
 * setting and two timestamps precisely so an empty state can ask them while it is drawing.
 */
object LedgerSidecarSync {

    /** A configured WebDAV service, or null when Ultrabridge isn't set up (no URL). */
    fun service(context: Context): UltrabridgeWebDavService? {
        return runCatching {
            // One prefs handle, not three: [prefs] builds a master key and opens the keystore, so
            // reading the three fields through three calls would pay for that three times on a path
            // every sidecar write goes through.
            val p = prefs(context)
            val url = p.getString("ultrabridge_webdav_url", "").orEmpty()
            if (url.isBlank()) return null
            UltrabridgeWebDavService(
                url,
                p.getString("ultrabridge_webdav_user", "").orEmpty(),
                p.getString("ultrabridge_webdav_pass", "").orEmpty()
            )
        }.onFailure { Timber.w(it, "LedgerSidecarSync: service init failed") }.getOrNull()
    }

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext, "ultrabridge_encrypted_prefs",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Download [remotePath] as UTF-8 text, or null if absent / no creds / offline. */
    fun pull(context: Context, remotePath: String): String? =
        service(context)?.let { svc ->
            runCatching { svc.download(remotePath)?.toString(Charsets.UTF_8) }
                .onFailure { Timber.w(it, "LedgerSidecarSync: pull failed for $remotePath") }.getOrNull()
                // Bytes came back, so the server is there and the credentials are good. Null proves
                // nothing either way — see [rememberReached].
                ?.also { rememberReached() }
        }

    /** Upload [text] to [remotePath] (overwrite). No-op if Ultrabridge isn't configured. */
    fun push(context: Context, remotePath: String, text: String) {
        val svc = service(context) ?: return
        runCatching { svc.uploadBytes(text.toByteArray(Charsets.UTF_8), remotePath) }
            .onFailure { Timber.w(it, "LedgerSidecarSync: push failed for $remotePath") }
            .getOrNull()?.let { if (it) rememberReached() }
    }

    // ------------------------------------------------------------------
    // WHY AN EMPTY LIST IS EMPTY
    // ------------------------------------------------------------------

    /**
     * EVERY CALL ABOVE SWALLOWS ITS OWN FAILURE, AND THAT IS THE RIGHT BEHAVIOUR FOR A PLANNER —
     * a sidecar that cannot reach the server must not interrupt what you are writing. But the
     * surfaces downstream inherited the silence along with the resilience, and one of them was
     * lying because of it.
     *
     * Michael's Boox Go 6 showed an empty Later List. Two causes were real and fixed (a media lens
     * intersecting the list; a 120-day read window against a 15-day cross-device pull). The third
     * could not be settled without the device and is the one that matters most: [service] returns
     * NULL when no WebDAV URL has ever been entered, every caller is wrapped in `runCatching`, and
     * so on a device that has never had Ultrabridge set up the Later List is permanently empty,
     * always was, and says nothing about why. "Nothing filed yet" is a true sentence about the
     * device and a false one about the man, who had filed four hundred links on three other
     * devices.
     *
     * So the stores keep swallowing, and this is where the swallowed fact is kept for anyone who
     * needs to explain an empty screen.
     *
     * THE CARE THIS NEEDS IS NOT TO CRY WOLF. A device that IS configured and simply has nothing
     * filed must still be told "nothing filed yet"; an apology on a working device is worse than
     * no apology, because it teaches you to ignore the line that will one day be true. So the two
     * facts are gathered by different means and held to different standards:
     *
     *  • NOT CONFIGURED is a local, instant, definitive fact — a blank URL in the settings. No
     *    network, no doubt, and it is the permanent silent failure this whole section exists for.
     *  • UNREACHABLE is only ever recorded by something that actually asked and was actually
     *    refused: [probe]'s Depth:0 PROPFIND, or the intake listing in
     *    [com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.pullMissingDays]. A failed [push]
     *    or an empty [pull] is NOT taken as evidence — a 403 on one path, or a file that simply
     *    isn't there, would otherwise convict a healthy server. This is the same distinction
     *    [CalendarWebDavSyncService.sync] draws when it refuses to read a failed listing as an
     *    empty server, and it is drawn here for the same reason: the cost of being wrong is
     *    telling someone their ledger is broken when it isn't.
     *
     * In memory rather than persisted, deliberately. A remembered "unreachable" from three days
     * ago would reappear on a fresh launch and speak about a network it knows nothing about;
     * forgetting on process death means the app starts each life with no opinion, which is the
     * honest starting point. Nothing here is a cache to be kept warm — it is a record of what was
     * actually observed this session.
     */
    @Volatile private var lastReachedAt = 0L

    @Volatile private var lastUnreachableAt = 0L

    /** How long an observation speaks for. Ten minutes: long enough that a list opened twice in a
     *  row doesn't pay for two probes, short enough that walking out of wifi is noticed. */
    private const val OBSERVATION_TTL_MS = 10 * 60_000L

    private fun rememberReached() {
        lastReachedAt = System.currentTimeMillis()
        lastUnreachableAt = 0L
    }

    private fun rememberUnreachable() {
        lastUnreachableAt = System.currentTimeMillis()
        lastReachedAt = 0L
    }

    /** True when a WebDAV URL has been entered on this device. Instant, local, main-thread-safe. */
    fun isConfigured(context: Context): Boolean =
        runCatching { prefs(context).getString("ultrabridge_webdav_url", "").orEmpty() }
            .getOrDefault("").isNotBlank()

    /**
     * Ask the server whether it is there — ONE round trip, blocking, off the main thread.
     *
     * Meant to be paid by the moment that needs the answer: a list that has just come back empty.
     * Calling it on every load would put a request in front of every screen for a fact that only
     * matters when there is nothing to show.
     *
     * @return true when the server answered (an unconfigured device answers false without asking)
     */
    fun probe(context: Context): Boolean {
        val svc = service(context) ?: return false
        val ok = runCatching { svc.reachable() }
            .onFailure { Timber.w(it, "LedgerSidecarSync: probe failed") }.getOrDefault(false)
        if (ok) rememberReached() else rememberUnreachable()
        return ok
    }

    /**
     * The one calm line an empty cross-device list should carry, or null when the emptiness is the
     * honest kind and the surface's own words are the whole truth.
     *
     * Instant and main-thread-safe: it reads a setting and two timestamps, never the network.
     *
     * The wording names the setting and where it lives, because "sync is not configured" leaves you
     * exactly as stuck as saying nothing — and stops there. This is a planner, not an error
     * console: no host, no status code, no stack. If the sentence isn't enough, Settings has the
     * WEBDAV section's own last-mirror line, which is where a diagnosis belongs.
     */
    fun emptyStateNote(context: Context): String? {
        if (!isConfigured(context)) {
            return "Cross-device sync isn't set up on this device, so anything filed elsewhere " +
                "can't reach it. Settings → WEBDAV → WebDAV URL."
        }
        val now = System.currentTimeMillis()
        if (lastUnreachableAt > 0L && now - lastUnreachableAt < OBSERVATION_TTL_MS) {
            return "Couldn't reach your WebDAV server just now, so anything filed on another " +
                "device isn't here yet."
        }
        // Reached, or nothing observed yet. Either way there is nothing honest to add.
        return null
    }

    /** [own] — what the surface would say by itself — followed by the sync's reason when there is
     *  one. The shape every empty state uses, so the two sentences never drift apart in tone. */
    fun explainEmpty(context: Context, own: String): String =
        emptyStateNote(context)?.let { "$own\n\n$it" } ?: own

    /** Record the outcome of a listing that already happened, so a caller doing its own PROPFIND
     *  doesn't have to pay for [probe] as well. Null means the listing FAILED — a 404 collection
     *  reports true, having proved the server answered. */
    fun noteListing(reached: Boolean) {
        if (reached) rememberReached() else rememberUnreachable()
    }

    /** Run [block] on a daemon thread (the stores' sync is fire-and-forget from UI callers). */
    fun background(block: () -> Unit) {
        Thread { runCatching { block() }.onFailure { Timber.w(it, "LedgerSidecarSync: bg task failed") } }
            .apply { isDaemon = true }.start()
    }
}
