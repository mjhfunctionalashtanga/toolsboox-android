package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.os.Environment
import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The CHEAP half of the sync, on the moments that matter (Michael, 2026-07-31: "those are the
 * kinds of pushes I want to have happen whenever the device is closed or a page is turned —
 * that notes page should sync immediately with all of the devices").
 *
 * The full [UltrabridgeSyncWorker] pass renders 17+ PDFs before anything else and BOWS OUT
 * entirely while an ink surface is foreground (the ANR lesson) — which meant a day spent writing
 * was a day the day files never left the device: the one pass that carries pages between devices
 * was gated behind the heaviest work and the strictest guard. This runs ONLY the day-JSON mirror
 * ([CalendarWebDavSyncService.sync] — a listing plus the changed files, seconds not minutes), and
 * it does NOT honour [UltrabridgeSyncWorker.inkSurfaceActive]: at a page turn or screen-off the
 * pen is definitionally down.
 *
 * Debounced, not queued: the mirror is idempotent and self-healing, so two triggers close
 * together need one pass, and [CalendarWebDavSyncService]'s own mutex keeps a quick pass from
 * colliding with the worker's full pass. Pen-up is deliberately NOT a trigger — per-stroke
 * uploads are what bloated the server's version history to 22 GB once already.
 *
 * Media ordering (Phase W) rides [CalendarWebDavSyncService.sync] itself: the pass pushes any
 * unconfirmed media blobs BEFORE the day loop, off a pushed-set that makes the steady state
 * zero extra round trips — so a page turn that just placed a photo carries the photo's bytes
 * up ahead of the day JSON naming them, and a page turn that placed nothing pays nothing. A
 * blob push failure logs and skips the blob but the day still mirrors: peers and the processor
 * render a placeholder for a missing blob, and refusing to mirror ink over a picture would
 * invert priorities.
 */
object QuickDayMirror {

    /** Two page turns inside this window share one pass. */
    private const val DEBOUNCE_MS = 20_000L

    private val lastFired = AtomicLong(0L)
    private val running = AtomicBoolean(false)

    fun fire(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        val last = lastFired.get()
        if (now - last < DEBOUNCE_MS) return
        if (!lastFired.compareAndSet(last, now)) return
        if (!running.compareAndSet(false, true)) return

        val app = context.applicationContext
        Thread({
            try {
                val svc = LedgerSidecarSync.service(app) ?: return@Thread
                val rootDir = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return@Thread
                val moshi = Moshi.Builder()
                    .add(LocaleJsonAdapter())
                    .add(DateJsonAdapter())
                    .add(UUIDJsonAdapter())
                    .build()
                val stats = kotlinx.coroutines.runBlocking {
                    CalendarWebDavSyncService(svc, rootDir, moshi, app).sync()
                }
                Timber.i("QuickDayMirror($reason): $stats")
            } catch (e: Throwable) {
                // Best-effort by design: the periodic worker re-runs everything.
                Timber.w(e, "QuickDayMirror($reason) failed (non-fatal)")
            } finally {
                running.set(false)
            }
        }, "quick-day-mirror").apply { isDaemon = true }.start()
    }
}
