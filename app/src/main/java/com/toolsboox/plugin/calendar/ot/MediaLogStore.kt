package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.MediaLogEntry
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * THE MEDIA LOG — what you read, watched and heard, in one synced sidecar (`media-log.json` at the
 * hub root), id-union merged like [ListenStateStore]. Wire shape on [MediaLogEntry].
 *
 * EVERYTHING CONSUMED IS RECORDED (Michael's ruling, 2026-08-23). The failure mode of that is a
 * long list, which [prune] handles; the failure mode of the alternative — logging only what you
 * starred — is a history that does not contain the thing you are looking for.
 *
 * WRITES ARE CHEAP AND SYNC IS NOT. [record] touches local disk only, because the recording moments
 * are page turns and playback starts: a WebDAV round trip on each would keep the radio hot for the
 * length of a reading session. [sync] is called on ARRIVAL at the log — the one moment the fleet's
 * answer has to be complete — and it is the same pull-merge-save-push round trip every sidecar here
 * makes, on the shared single-thread executor whose ordering is the correctness mechanism.
 */
object MediaLogStore {
    private const val PATH = "media-log.json"

    /** How long a row stays. Long enough that "what was that thing in the spring" still answers,
     *  short enough that the file stays something a Boox can hold and sync. */
    private const val KEEP_DAYS = 400L

    private val adapter = Moshi.Builder().build()
        .adapter<List<MediaLogEntry>>(
            Types.newParameterizedType(List::class.java, MediaLogEntry::class.java))

    private fun file(context: Context) = File(context.filesDir, PATH)

    fun loadAll(context: Context): MutableList<MediaLogEntry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull()
            ?: mutableListOf()
    }

    fun saveAll(context: Context, rows: List<MediaLogEntry>) {
        runCatching { file(context).writeText(adapter.toJson(rows)) }
            .onFailure { Timber.w(it, "media-log save failed") }
    }

    /**
     * Note that you engaged with something. Local only — see the class note on write cadence.
     *
     * Idempotent per id: reading the same article twice moves [MediaLogEntry.at] forward rather
     * than adding a second row, because the log answers "what have I read", not "how many times
     * did I open this". [done] latches forward for the same reason it does on a task.
     */
    fun record(
        context: Context,
        id: String,
        kind: String,
        title: String = "",
        source: String = "",
        url: String = "",
        feedId: Long = 0L,
        done: Boolean = false,
    ) {
        if (id.isBlank()) return
        val all = loadAll(context)
        val cur = all.firstOrNull { it.id == id }
        val now = System.currentTimeMillis()
        val next = (cur ?: MediaLogEntry(id = id)).copy(
            kind = kind,
            // A later sighting may know less than the first (a synthetic row, a cached stub), so
            // a blank never overwrites something we already knew this thing was called.
            title = title.ifBlank { cur?.title.orEmpty() },
            source = source.ifBlank { cur?.source.orEmpty() },
            url = url.ifBlank { cur?.url.orEmpty() },
            feedId = if (feedId != 0L) feedId else (cur?.feedId ?: 0L),
            at = now,
            done = done || cur?.done == true,
            updated = now,
            deletedAt = 0L,   // engaging with something un-tombstones it: doing it is the louder fact
        )
        if (cur != null) all.remove(cur)
        all.add(next)
        saveAll(context, all)
    }

    /** The log, newest first, optionally one kind only. Tombstones never surface. */
    fun list(context: Context, kind: String? = null): List<MediaLogEntry> =
        loadAll(context)
            .filter { !it.isDeleted && (kind == null || it.kind == kind) }
            .sortedByDescending { it.at }

    /** Forget one row — a tombstone, not a deletion, so the forget survives the next merge. */
    fun forget(context: Context, id: String) {
        val all = loadAll(context)
        val cur = all.firstOrNull { it.id == id } ?: return
        all.remove(cur)
        all.add(cur.copy(deletedAt = System.currentTimeMillis(), updated = System.currentTimeMillis()))
        saveAll(context, all)
        sync(context)
    }

    /** Drop rows older than [KEEP_DAYS] outright — including their tombstones, which have outlived
     *  anything they could still be suppressing. Called on the same arrival that syncs. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
        val all = loadAll(context)
        val kept = all.filter { maxOf(it.at, it.updated) >= cutoff }
        if (kept.size != all.size) saveAll(context, kept)
    }

    /** Pull, [MediaLogEntry.merge], save, push — the sidecar round trip. */
    fun sync(context: Context, onMerged: ((List<MediaLogEntry>) -> Unit)? = null) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val remote = runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
                MediaLogEntry.merge(local, remote)
            }
            saveAll(context, merged)
            LedgerSidecarSync.push(context, PATH, adapter.toJson(merged))
            onMerged?.invoke(merged)
        }
    }
}
