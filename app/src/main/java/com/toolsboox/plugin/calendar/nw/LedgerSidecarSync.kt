package com.toolsboox.plugin.calendar.nw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * One shared sync entry point for the small "sidecar" stores (Text Notes, Intake pages, Pickings
 * board names, Synthesis ideas, reader position) that live outside the main day JSON. Centralises the
 * credential read + transport construction so each store doesn't re-implement it, and gives
 * them a pull/push that round-trips instead of the old push-only-clobber pattern.
 *
 * Since design step D (2026-08-11) the remote underneath is a [HubTransport] — WebDAV or Google
 * Drive, chosen in Settings — and every store that speaks through [pull]/[push] rides whichever
 * backend the person chose without knowing it. The stores were never told which server they were
 * talking to; now that ignorance is the architecture.
 *
 * Every call that touches the network blocks on it — invoke those off the main thread. The
 * "why is this list empty" reads at the foot of this file deliberately do not: they answer from a
 * setting and two timestamps precisely so an empty state can ask them while it is drawing.
 */
object LedgerSidecarSync {

    // ------------------------------------------------------------------
    // THE BACKEND CHOICE (design step D, 2026-08-11)
    // ------------------------------------------------------------------

    /** Where the choice lives: the plain "MAIN" prefs, beside the other sync toggles. The value
     *  is a backend NAME, not a secret, so it does not belong in the encrypted store. */
    const val BACKEND_KEY = "syncBackend"
    const val BACKEND_WEBDAV = "webdav"
    const val BACKEND_DRIVE = "drive"

    /** The chosen backend, defaulting to WebDAV — the default is the absence of a decision, and
     *  the absence of a decision must mean "exactly what every device did yesterday". */
    fun backend(context: Context): String =
        runCatching {
            context.getSharedPreferences("MAIN", Context.MODE_PRIVATE)
                .getString(BACKEND_KEY, BACKEND_WEBDAV)
        }.getOrNull() ?: BACKEND_WEBDAV

    /**
     * The chosen backend as a [HubTransport], or null when that backend isn't set up here — a
     * blank WebDAV URL, or Drive chosen with no Google account signed in. This is the seam every
     * sidecar store and the library hub ride: ONE sync logic, and the transport underneath is the
     * only thing the Settings choice swaps. Switching backends does NOT migrate data — the ledger
     * stays where it is; new syncs go to the chosen backend — and that is said in the Settings
     * copy rather than enforced by cleverness here.
     */
    fun transport(context: Context): HubTransport? =
        if (backend(context) == BACKEND_DRIVE) DriveHubTransport.create(context)
        else service(context)?.let { WebDavHubTransport(it) }

    /** A configured WebDAV service, or null when Ultrabridge isn't set up (no URL). Still public
     *  and still WebDAV-typed on purpose: the callers that genuinely need WebDAV verbs the seam
     *  doesn't carry (intake's PROPFIND listing, the day-file quick mirror — surfaces whose Drive
     *  counterpart is the separate day-sync machinery) come here; everything backend-agnostic
     *  goes through [transport]. */
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
        transport(context)?.let { t ->
            runCatching { t.get(remotePath)?.toString(Charsets.UTF_8) }
                .onFailure { Timber.w(it, "LedgerSidecarSync: pull failed for $remotePath") }.getOrNull()
                // Bytes came back, so the server is there and the credentials are good. Null proves
                // nothing either way — see [rememberReached].
                ?.also { rememberReached() }
        }

    /** Upload [text] to [remotePath] (overwrite). No-op if no sync backend is configured. */
    fun push(context: Context, remotePath: String, text: String) {
        val t = transport(context) ?: return
        runCatching {
            // Stock Apache dav (dav.mjh.yoga) 409s a PUT whose parent collection does not exist,
            // and this path never MKCOLed — which is why grid-index/, sketch-index/, synth-index/
            // and write-index/ silently never appeared at the remote root while the two calendar
            // sync services (which do ensure their dirs) landed fine. Upload first so the steady
            // state stays one round trip; on failure, ensure each ancestor and retry once. The
            // sequence lives on the seam ([putBytesEnsuringFolders]) so the fake-transport test
            // can prove it without a server, and so Drive inherits it unasked.
            t.putBytesEnsuringFolders(remotePath, text.toByteArray(Charsets.UTF_8))
        }
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

    /** True when the CHOSEN backend has what it needs on this device — a WebDAV URL, or (for
     *  Drive) a signed-in Google account. Instant, local, main-thread-safe: both facts are read
     *  from this device without a network. */
    fun isConfigured(context: Context): Boolean =
        if (backend(context) == BACKEND_DRIVE) {
            runCatching {
                com.google.android.gms.auth.api.signin.GoogleSignIn
                    .getLastSignedInAccount(context) != null
            }.getOrDefault(false)
        } else {
            runCatching { prefs(context).getString("ultrabridge_webdav_url", "").orEmpty() }
                .getOrDefault("").isNotBlank()
        }

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
        val t = transport(context) ?: return false
        val ok = runCatching { t.reachable() }
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
        val drive = backend(context) == BACKEND_DRIVE
        if (!isConfigured(context)) {
            // Two backends, two remedies — and the sentence must name the right one, or it sends
            // a Google-backend user hunting for a WebDAV URL they never had.
            return if (drive) {
                "Cross-device sync isn't set up on this device, so anything filed elsewhere " +
                    "can't reach it. Settings → Google Calendar → Connect to sign in to Google."
            } else {
                "Cross-device sync isn't set up on this device, so anything filed elsewhere " +
                    "can't reach it. Settings → WEBDAV → WebDAV URL."
            }
        }
        val now = System.currentTimeMillis()
        if (lastUnreachableAt > 0L && now - lastUnreachableAt < OBSERVATION_TTL_MS) {
            val server = if (drive) "Google Drive" else "your WebDAV server"
            return "Couldn't reach $server just now, so anything filed on another " +
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

    /**
     * The one thread every sidecar round trip runs on, in strict submission order.
     *
     * SERIALIZATION IS THE CORRECTNESS MECHANISM; PARALLEL SIDECAR SYNCS LOSE WRITES. Every
     * sidecar store is a whole-file read-modify-write, and its sync() snapshots the file at
     * thread start, round-trips the network, then writes the merge back to disk and pushes it.
     * With one unbounded Thread per call — the old shape here — two quick edits raced: the sync
     * that snapshotted FIRST could write back LAST, clobbering the second edit on disk and then
     * pushing the loss to the server. A single-threaded executor makes every later sync()
     * snapshot strictly AFTER the earlier one's write-back, which removes that interleaving.
     * Do not widen this pool and do not add a second one: the queue is the fix.
     */
    private val syncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sidecar-sync").apply { isDaemon = true }
    }

    /**
     * Run [block] on the shared "sidecar-sync" daemon thread (the stores' sync is fire-and-forget
     * from UI callers). Strictly FIFO — see [syncExecutor] for why the ordering, not just the
     * off-main-ness, is load-bearing.
     */
    fun background(block: () -> Unit) {
        syncExecutor.execute {
            runCatching { block() }.onFailure { Timber.w(it, "LedgerSidecarSync: bg task failed") }
        }
    }
}
