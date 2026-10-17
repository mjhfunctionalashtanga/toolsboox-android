package com.toolsboox.plugin.feeds.nw

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * THE FETCH CYCLE — mail and RSS arrive on a clock, not only on an open (Michael, 08-23: "make
 * sure that the app fetches new mail and rss on a regular cycle, and keeps mail and rss feeds so
 * that they can be read offline no problem — the user can set the fetch timings if there isn't a
 * push mechanic for the mail").
 *
 * There is no push mechanic on either lane — Miniflux is a poll API and the mail is plain IMAP —
 * so the cycle is a WorkManager periodic, user-timed from Settings ([schedule]; the
 * WidgetRefreshWorker discipline: idempotent, non-Hilt, alarm survives launcher battery managers).
 *
 * Each tick does exactly what OPENING the surfaces does, headless:
 *  - RSS: the unread page into [FeedCache.saveEntries] under the default view's key, then the
 *    readable copy of each new entry into [FeedCache.saveContent] — the same files the feed list
 *    falls back to offline — then the cache janitor. (No paywall-bypass in the background: that
 *    budgeted, throttled pass stays with the foreground sweep where its pacing was designed.)
 *  - Mail: [com.toolsboox.plugin.mail.MailSync.refresh] — the inbox's own merge-only refresh,
 *    durable through InboxStore, so letters fetched at 6am read on the train at 8 with no signal.
 *
 * Battery honesty on e-ink: the default is 30 minutes, the floor WorkManager enforces is 15, and
 * "Off" is a real choice — the surfaces still fetch on open exactly as before.
 */
class FeedFetchWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // ── RSS ──────────────────────────────────────────────────────────────────────────
        // Through FeedPrefs — the file is ENCRYPTED, and this worker's plain open had been
        // reading blanks (no server refresh in the background, ever) until 09-03.
        val (url, token) = FeedPrefs.miniflux(ctx)
        if (url.isNotBlank() && token.isNotBlank()) {
            val client = MinifluxClient()
            when (val res = runCatching { client.fetchUnread(url, token) }.getOrNull()) {
                is MinifluxClient.Result.Ok -> {
                    // The default view's cache key ("unread_all") — the offline fallback the
                    // list reads when the network is gone.
                    FeedCache.saveEntries(ctx, "unread_all", res.value)
                    var saved = 0
                    for (e in res.value.take(200)) {
                        if (FeedCache.loadContent(ctx, e.id) != null) continue
                        val c = runCatching { client.fetchContent(url, token, e.id) }.getOrNull()
                        if (c is MinifluxClient.Result.Ok && c.value.isNotBlank()) {
                            FeedCache.saveContent(ctx, e.id, c.value)
                            // The pictures come along (the Handy News Reader lift): an offline
                            // article with network-only images is not offline on a device that
                            // leaves the wifi behind.
                            runCatching { FeedCache.cacheArticleImages(ctx, c.value) }
                            saved++
                        }
                    }
                    FeedCache.prune(ctx)
                    // Podcast-app behaviour, finally headless (the AntennaPod lift, 09-02):
                    // keep the newest episodes on the device from the background tick, not
                    // only when The Listen happens to be opened.
                    runCatching { LaterMedia.keepRecent(ctx, res.value) }
                        .onFailure { Timber.w(it, "feed fetch: keepRecent failed") }
                    // …and what the fleet ASKED for, headless too (09-19): an ask made on the
                    // iPhone becomes a file on this Boox from the background tick, not only when
                    // The Listen happens to be opened here.
                    runCatching { LaterMedia.fulfilWanted(ctx) }
                        .onFailure { Timber.w(it, "feed fetch: fulfilWanted failed") }
                    Timber.i("feed fetch: ${res.value.size} entries, $saved new readable copies")
                }
                else -> Timber.w("feed fetch: miniflux unreachable, cache stands")
            }
        }

        // ── Local Feeds subscriptions: the synced sidecar's background round trip ─────────
        runCatching { LocalFeedStore.sync(ctx) }

        // ── THE FLEET HEARTBEAT (09-03: "make sure media played and watched and podcasted
        // really is synced… it feels like it's not"). The stores DID sync, but only at sparse
        // arrival moments — a device you hadn't opened the Log on sat stale for hours. The
        // background tick now round-trips the playheads and the media log too, so the fleet
        // converges every cycle with the app closed.
        runCatching { com.toolsboox.plugin.calendar.ot.ListenStateStore.sync(ctx) }
        runCatching { com.toolsboox.plugin.calendar.ot.MediaLogStore.sync(ctx) }

        // ── Mail ─────────────────────────────────────────────────────────────────────────
        runCatching { com.toolsboox.plugin.mail.MailSync.refresh(ctx) }
            .onFailure { Timber.w(it, "feed fetch: mail refresh failed") }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "ledger-feed-fetch"

        /** The user's chosen cadence, minutes; 0 = off. Read by Settings and [schedule] alike. */
        const val PREF_MINUTES = "feed_fetch_minutes"
        const val DEFAULT_MINUTES = 30

        /** The cadences on offer, in minutes (0 = off), and their words — one list, two dialogs
         *  (the feed settings and the mail accounts), so the rows cannot drift apart. */
        val CADENCE_MINUTES = listOf(0, 15, 30, 60, 120, 240)
        val CADENCE_LABELS = listOf("Off", "Every 15 min", "Every 30 min", "Every hour", "Every 2 hours", "Every 4 hours")

        /** The saved cadence, through [FeedPrefs] — the store the settings dialog writes. */
        fun minutes(context: Context): Int =
            FeedPrefs.open(context).getInt(PREF_MINUTES, DEFAULT_MINUTES)

        /** Save a cadence and re-arm (or cancel) the alarm now, not at the next cold start. */
        fun setMinutes(context: Context, minutes: Int) {
            FeedPrefs.open(context).edit().putInt(PREF_MINUTES, minutes).apply()
            schedule(context)
        }

        /** (Re)apply the schedule from the pref. Idempotent (UPDATE policy — a changed interval
         *  replaces the old alarm); 0 cancels outright. Call at app start and from Settings.
         *
         *  Through [FeedPrefs], the ENCRYPTED store — the settings dialog has always written the
         *  cadence there, and this read used a plain `getSharedPreferences` on the same file
         *  name, where the encrypted key is gibberish: every read came back DEFAULT_MINUTES, so
         *  "Off" never cancelled and "Every hour" never changed anything. The same plain-open bug
         *  FeedPrefs' own note describes for the Miniflux URL, one key over. */
        fun schedule(context: Context) {
            val minutes = minutes(context)
            val wm = WorkManager.getInstance(context)
            if (minutes <= 0) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            // WorkManager's floor is 15 minutes; a smaller ask silently becomes 15, so clamp
            // loudly here rather than let the pref lie about what it does. Same constraints as
            // the calendar sync: connected + battery-not-low, no charging requirement (this
            // device runs for days unplugged; Doze already throttles into maintenance windows).
            val request = PeriodicWorkRequestBuilder<FeedFetchWorker>(
                minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
            )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
