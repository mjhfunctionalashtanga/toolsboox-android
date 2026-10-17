package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.ReadingSession
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * The SESSIONS — `reading-sessions.json` at the hub root; wire and vocabulary on
 * [ReadingSession]. One open session at a time per process (a person reads one thing), begun
 * where a medium opens and closed where it closes; a session shorter than [MIN_SESSION_MS] is a
 * misfire, not attention, and writes nothing. A session the process dies inside is lost —
 * honest, and cheaper than a heartbeat.
 *
 * Callers today: the reader (book/comic — it knows CBZ from epub). Feeds, mail and listen join
 * as call sites, never as wire changes; the handoff carries their worklist.
 */
object ReadingSessionStore {
    private const val PATH = "reading-sessions.json"
    private const val KEEP_DAYS = 400L
    private const val MIN_SESSION_MS = 10_000L

    private val adapter = Moshi.Builder().build()
        .adapter<List<ReadingSession>>(
            Types.newParameterizedType(List::class.java, ReadingSession::class.java))

    private fun file(context: Context) = File(context.filesDir, PATH)

    fun loadAll(context: Context): MutableList<ReadingSession> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    private fun saveAll(context: Context, all: List<ReadingSession>) {
        runCatching { file(context).writeText(adapter.toJson(all)) }
            .onFailure { Timber.w(it, "reading-sessions save failed") }
    }

    // ── The open session (one per process, in memory) ─────────────────────────────────────────

    private var openMedium: String? = null
    private var openRef: String = ""
    private var openStart: Long = 0L

    /** A medium opened. Re-beginning (a new book without closing the last) closes the old first. */
    fun begin(context: Context, medium: String, ref: String) {
        end(context)
        openMedium = medium
        openRef = ref
        openStart = System.currentTimeMillis()
    }

    /** The medium closed (or the surface paused). Writes the row and queues the push; a blink
     *  under [MIN_SESSION_MS] writes nothing. Safe to call with nothing open. */
    fun end(context: Context) {
        val medium = openMedium ?: return
        val start = openStart
        val ref = openRef
        openMedium = null
        val duration = System.currentTimeMillis() - start
        if (duration < MIN_SESSION_MS) return
        val app = context.applicationContext
        val row = ReadingSession(
            id = "rs-" + UUID.randomUUID().toString().lowercase(),
            medium = medium, ref = ref,
            device = runCatching {
                android.provider.Settings.Global.getString(app.contentResolver, "device_name")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: (android.os.Build.MODEL ?: "unknown"),
            start = start, durationMs = duration, deletedAt = 0L
        )
        // Row captured on the caller; the file rewrite rides the sidecar thread — onPause is a
        // moment the UI cannot afford a whole-file load-append-save.
        LedgerSidecarSync.background {
            runCatching {
                val all = loadAll(app)
                all.add(row)
                saveAll(app, all)
                sync(app)
            }
        }
    }

    /** Pull → union-merge → prune → save → push-if-changed; also rides the library hub's tick. */
    fun sync(context: Context) {
        val remoteText = LedgerSidecarSync.pull(context, PATH)
        val remote: List<ReadingSession> =
            if (remoteText.isNullOrBlank()) emptyList()
            else runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
        val merged = ReadingSession.merge(loadAll(context), remote)
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val kept = merged.filter { it.stamp >= cutoff }
        saveAll(context, kept)
        if (kept != remote) {
            LedgerSidecarSync.push(context, PATH, adapter.toJson(kept))
        }
    }

    /** Total attention per medium between [from]..[to] (epoch ms) — the aggregation Michael's
     *  ruling names ("how much time book, how much time comic…"); the 🕘 Log's future surface
     *  reads this, no other bookkeeping required. */
    fun timeByMedium(context: Context, from: Long, to: Long): Map<String, Long> =
        loadAll(context)
            .filter { !it.isDeleted && it.start >= from && it.start < to }
            .groupBy { it.medium }
            .mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
}
