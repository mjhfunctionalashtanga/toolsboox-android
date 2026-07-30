package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.os.Environment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Two-way sync of the calendar DAY JSON files against a WebDAV server, mirroring the exact
 * on-disk layout the iPad app uses so the two clients interoperate 1:1:
 *
 *   <webdavRoot>/calendar/YYYY/MM/{baseName}-{version}.json
 *   e.g. calendar/2026/05/day-2026-05-28-v2.json
 *
 * The local tree under <externalDocs>/calendar/YYYY/MM/ maps directly onto that remote tree,
 * so a file's relative path IS its remote path. This complements [UltrabridgeSyncWorker] (which
 * renders PDFs and pushes a flat json/ mirror for the OCR pipeline) by keeping the versioned day
 * files themselves converged between devices.
 *
 * Conflict resolution is a conflict-free MERGE (shared [com.toolsboox.plugin.calendar.fi.CalendarDayMerger]
 * — the same union+tombstone logic the Google Drive sync uses), so two devices editing the same day
 * both keep their edits instead of the older `updated` losing everything. The merge is made
 * deterministic (pair ordered by updated then json; timestamp = max, not "now") so devices converge to
 * a byte-identical result and stop syncing rather than ping-ponging. A server-clock watermark + PROPFIND
 * getlastmodified pre-filter skip files that haven't changed since the last converged pass.
 *
 * @param webdav the shared WebDAV HTTP/auth client
 * @param rootDir the app's external documents directory (parent of the "calendar/" tree)
 * @param moshi a Moshi configured with the app's Date/Locale/UUID adapters
 */
class CalendarWebDavSyncService(
    private val webdav: UltrabridgeWebDavService,
    private val rootDir: File,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "CalendarWebDavSync"
        private const val ENCRYPTED_PREFS_NAME = "ultrabridge_encrypted_prefs"

        // One sync pass at a time, PROCESS-wide: the periodic worker and the on-open/on-pause
        // one-shot use different unique work names, so WorkManager alone lets them overlap —
        // two passes interleaving over the same tree and watermark file corrupt each other.
        private val syncMutex = Mutex()

        // Matches the versioned day files both clients write: day-YYYY-MM-DD-v#.json
        private val DAY_FILE_REGEX = Regex("^day-.*-v\\d+\\.json$", RegexOption.IGNORE_CASE)

        // getlastmodified is only 1-second resolution, so a change in the same wall-second as the last
        // converged pass could share its mtime. Require the remote mtime to be this far BELOW the
        // watermark before trusting "unchanged"; anything within the margin is re-fetched and merged.
        private const val MTIME_MARGIN_MS = 2_000L

        /**
         * Build a Moshi instance matching the app's NetworkModule.provideMoshi().
         */
        private fun buildMoshi(): Moshi {
            return Moshi.Builder()
                .add(LocaleJsonAdapter())
                .add(DateJsonAdapter())
                .add(UUIDJsonAdapter())
                .build()
        }

        /**
         * Build a sync service from application state, reading the same EncryptedSharedPreferences
         * WebDAV credentials [UltrabridgeSyncWorker] uses. Returns null when WebDAV is not
         * configured, so callers can no-op silently.
         *
         * @param context an application context
         * @return a configured service, or null if credentials are missing
         */
        fun fromContext(context: Context): CalendarWebDavSyncService? {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val url = prefs.getString("ultrabridge_webdav_url", null)
            val user = prefs.getString("ultrabridge_webdav_user", null)
            val pass = prefs.getString("ultrabridge_webdav_pass", null)
            if (url.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) {
                Timber.w("$TAG: WebDAV credentials not configured, skipping day-JSON sync")
                return null
            }

            val rootDir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }

            return CalendarWebDavSyncService(
                UltrabridgeWebDavService(url, user, pass),
                rootDir,
                buildMoshi()
            )
        }
    }

    /**
     * Outcome counters for a single [sync] pass, handy for logging/tests.
     */
    data class SyncStats(val pushed: Int, val pulled: Int, val skipped: Int, val failed: Int)

    private data class LocalEntry(val remotePath: String, val file: File, val updated: Long)

    /**
     * Run one full bidirectional sync pass of the calendar day JSON files.
     *
     * Local-only files are PUT to WebDAV (parent collections MKCOL'd first); remote-only files are
     * GET and written locally; files on both sides resolve by last-write-wins on [CalendarDay.updated].
     * All network I/O runs on [Dispatchers.IO].
     *
     * @return the pushed/pulled/skipped/failed counts
     */
    suspend fun sync(): SyncStats = syncMutex.withLock { withContext(Dispatchers.IO) {
        var pushed = 0
        var pulled = 0
        var skipped = 0
        var failed = 0

        val localByPath = inventoryLocal().associateBy { it.remotePath }
        // A failed listing is NOT an empty server: pushing "local-only" files against an
        // unknown remote would overwrite peer edits. Abort the pass; watermarks stay put.
        val remoteListing = webdav.propfind("calendar/") ?: run {
            Timber.w("$TAG: remote listing failed; aborting sync pass")
            return@withContext SyncStats(0, 0, 0, 1)
        }
        val remoteByPath = remoteListing
            .filter { DAY_FILE_REGEX.matches(File(it.remotePath).name) }
            .associateBy { it.remotePath }

        // NEWEST DAY FIRST, deliberately. A first converge over a few years of days means downloading
        // and merging a hundred multi-megabyte files, which takes long enough that the pass often
        // does not survive to the end — the Boox kills a backgrounded process, and the on-exit
        // trigger is exactly when this runs. Since the watermark only advances on a fully clean pass,
        // a truncated run still commits every file it got through; so the order it gets through them
        // in decides what converges. Descending path order puts today, then yesterday, at the front:
        // the pages just written are the ones a person is waiting to see on their other device, and
        // 2025 can wait for a pass that runs to completion.
        val allPaths = (localByPath.keys + remoteByPath.keys).sortedDescending()
        Timber.i("$TAG: local=${localByPath.size} remote=${remoteByPath.size} union=${allPaths.size}")

        // Two watermarks from the last fully-converged pass, each compared only against its OWN clock:
        //  - [watermark]: max remote getlastmodified (SERVER clock) → detect remote changes.
        //  - [localWatermark]: max local CalendarDay.updated (DEVICE clock) → detect local changes.
        // A both-sides file provably unchanged on both is skipped without a GET; mixing the two clocks
        // (the old bug) is avoided entirely.
        val (watermark, localWatermark) = readWatermarks()
        var maxRemoteSeen = watermark
        var maxLocalSeen = localWatermark

        for (remotePath in allPaths) {
            val local = localByPath[remotePath]
            val remote = remoteByPath[remotePath]
            if (remote != null && remote.lastModified > maxRemoteSeen) maxRemoteSeen = remote.lastModified
            try {
                when {
                    // Local-only → push it up.
                    local != null && remote == null -> {
                        if (push(local)) pushed++ else failed++
                    }
                    // Remote-only → pull it down.
                    local == null && remote != null -> {
                        if (pull(remotePath)) pulled++ else failed++
                    }
                    // On both sides → MERGE (union strokes/text/images/events by id, honouring erase
                    // tombstones), not last-write-wins, so neither device's concurrent edits are lost.
                    local != null && remote != null -> {
                        val rMtime = remote.lastModified
                        // The local "changed?" signal is the FILE's own mtime (set when THIS device last
                        // wrote it) — a pure device-clock value. We deliberately do NOT use
                        // CalendarDay.updated here: the merge stamps updated = max(local, peer), so a
                        // faster peer's clock would poison it and could persistently skip a real local edit.
                        val lMtime = local.file.lastModified()
                        if (lMtime > maxLocalSeen) maxLocalSeen = lMtime
                        // Skip decisions use SAME-CLOCK comparisons only (server rMtime vs server
                        // watermark; device lMtime vs device localWatermark). An unknown/0 mtime is never
                        // "unchanged", so we never blind-push over a copy we didn't fetch. The MARGIN
                        // covers the 1-second resolution of getlastmodified.
                        val remoteUnchanged = rMtime > 0 && watermark > 0 && rMtime < watermark - MTIME_MARGIN_MS
                        val localUnchanged = localWatermark > 0 && lMtime <= localWatermark
                        // Both provably unchanged since the last converged pass → nothing to do.
                        if (remoteUnchanged && localUnchanged) { skipped++; continue }
                        // Remote provably unchanged (so local already ⊇ remote from the last converge) but
                        // local changed → a plain push carries the new local edits without losing anything.
                        if (remoteUnchanged) { if (push(local)) pushed++ else failed++; continue }
                        // Remote changed or its mtime is unknown → fetch and merge (never blind-push).
                        val remoteBytes = webdav.download(remotePath) ?: run { failed++; continue }
                        val remoteDay = parseDay(remoteBytes)
                        val localDay = parseDay(local.file.readBytes())
                        // If EITHER side isn't a parseable v2 day (e.g. a forward-schema file from a newer
                        // build), do nothing destructive: leave both copies as-is and log. Converges once
                        // both builds understand the schema; never clobbers the side we can't read.
                        if (remoteDay == null || localDay == null) {
                            Timber.w("$TAG: unparseable day on one side, leaving both untouched: $remotePath")
                            skipped++; continue
                        }
                        // Deterministic merge: order the pair by (updated, then json) so every device
                        // computes byte-identical output from the same two versions → convergence, not
                        // ping-pong. Stamp updated = max(existing) rather than "now" for the same reason.
                        val lu = localDay.updated?.time ?: 0L; val ru = remoteDay.updated?.time ?: 0L
                        val (first, second) = when {
                            lu < ru -> localDay to remoteDay
                            lu > ru -> remoteDay to localDay
                            dayJson(localDay) <= dayJson(remoteDay) -> localDay to remoteDay
                            else -> remoteDay to localDay
                        }
                        val merged = com.toolsboox.plugin.calendar.fi.CalendarDayMerger.merge(first, second)
                        merged.updated = java.util.Date(maxOf(lu, ru))
                        merged.created = localDay.created ?: remoteDay.created
                        val mergedJson = dayJson(merged)
                        if (!sameContent(localDay, merged, mergedJson)) {
                            // Pen-up guard: if the local file changed since we read it (a save
                            // landed during the merge), our merge is stale — writing it would
                            // discard that save (including erase tombstones, which then resurrect
                            // permanently). Leave local alone; the next pass merges the fresh copy.
                            if (local.file.lastModified() != lMtime) {
                                Timber.i("$TAG: local $remotePath changed mid-merge; deferring local write")
                                failed++    // hold the watermark back so the next pass re-merges
                            } else if (writeLocal(remotePath, mergedJson.toByteArray(Charsets.UTF_8))) pulled++ else failed++
                        }
                        if (!sameContent(remoteDay, merged, mergedJson)) {
                            ensureRemoteParents(remotePath)
                            if (webdav.uploadBytes(mergedJson.toByteArray(Charsets.UTF_8), remotePath)) pushed++ else failed++
                        }
                    }
                }
            } catch (e: Throwable) {
                // Throwable so that one unreadable/oversized day costs that day, not the pass — and
                // certainly not the process. An OutOfMemoryError here used to escape every handler
                // up to the worker and take the app down mid-sync.
                failed++
                Timber.e(e, "$TAG: Error syncing $remotePath")
            }
        }

        // Only advance the watermarks when every file converged this pass; otherwise a file that failed
        // to download/merge could be wrongly skipped (and its remote edits lost) next time.
        if (failed == 0) writeWatermarks(maxRemoteSeen, maxLocalSeen)

        runCatching { syncAttachments() }.onFailure { Timber.w(it, "$TAG: attachment sync failed") }

        val stats = SyncStats(pushed, pulled, skipped, failed)
        Timber.i("$TAG: Day-JSON sync done: $stats")
        stats
    } }

    /**
     * Sync the annotation / A/V-gram media blobs. These are immutable and UUID-named, so there
     * are no conflicts: push any local file the server lacks, pull any remote file we lack. The
     * lightweight Attachment references already ride in the day JSON; this carries the bytes so
     * photos and voice memos round-trip across devices.
     */
    private fun syncAttachments() {
        val dir = File(rootDir, "attachments/")
        val local = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        // On a failed listing, skip the pass — attachments are immutable so pushing is safe,
        // but "remote lacks everything" would re-upload the entire media library.
        val remote = runCatching { webdav.propfind("attachments/") }.getOrNull() ?: run {
            Timber.w("$TAG: attachments listing failed; skipping attachment pass")
            return
        }
        val remoteNames = remote.map { File(it.remotePath).name }.toSet()
        val localNames = local.map { it.name }.toSet()

        val toPush = local.filter { it.name !in remoteNames }
        if (toPush.isNotEmpty()) webdav.ensureDirectory("attachments/")
        for (f in toPush) runCatching { webdav.upload(f, "attachments/${f.name}") }

        if (!dir.exists()) dir.mkdirs()
        for (r in remote) {
            val name = File(r.remotePath).name
            if (name.isBlank() || name in localNames) continue
            val bytes = runCatching { webdav.download(r.remotePath) }.getOrNull() ?: continue
            // Same temp-then-move as everything else. This wrote straight to the final name, so a
            // kill or a short read left a half-attachment under a name that says it's whole — and
            // the `name in localNames` skip above then treats it as already downloaded, for good.
            runCatching {
                val temp = File(dir, "$name.tmp")
                temp.writeBytes(bytes)
                try {
                    Files.move(temp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    Files.move(temp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /**
     * Does this look like a whole JSON document?
     *
     * Only checks that it starts and ends with matching brackets, ignoring trailing whitespace.
     * That is deliberately shallow — it catches truncation, which is the failure that actually
     * happens, and costs microseconds on a file too big to parse twice.
     */
    private fun closesCleanly(bytes: ByteArray): Boolean {
        var end = bytes.size - 1
        while (end >= 0 && bytes[end].toInt().toChar().isWhitespace()) end--
        var start = 0
        while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
        if (start >= end) return false
        val first = bytes[start].toInt().toChar()
        val last = bytes[end].toInt().toChar()
        return (first == '{' && last == '}') || (first == '[' && last == ']')
    }

    /**
     * Walk <rootDir>/calendar for versioned day files and record each one's remote path and
     * parsed `updated` millis. Uses a fault-tolerant walk because day files are saved atomically
     * (write .tmp, rename), so a transient file can vanish mid-traversal.
     */
    private fun inventoryLocal(): List<LocalEntry> {
        val calendarDir = File(rootDir, "calendar/")
        if (!calendarDir.exists()) return emptyList()

        val entries = mutableListOf<LocalEntry>()
        try {
            Files.walkFileTree(Paths.get(calendarDir.toURI()), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val f = file.toFile()
                    if (f.isFile && DAY_FILE_REGEX.matches(f.name)) {
                        val relative = f.absolutePath.substringAfter(rootDir.absolutePath.trimEnd('/') + "/")
                        val updated = try {
                            parseUpdated(f.readText(Charsets.UTF_8)) ?: f.lastModified()
                        } catch (e: Exception) {
                            f.lastModified()
                        }
                        entries.add(LocalEntry(relative, f, updated))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    Timber.w(exc, "$TAG: skipping unreadable/vanished path $file")
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: java.io.IOException) {
            Timber.w(e, "$TAG: local walk failed for $calendarDir")
        }
        return entries
    }

    /**
     * Parse the `updated` epoch-millis out of a day JSON, or null if it is not a parseable v2 day.
     */
    private fun parseUpdated(json: String): Long? {
        return try {
            moshi.adapter(CalendarDay::class.java).fromJson(json)?.updated?.time
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Parse a day JSON to a [CalendarDay], or null if it isn't parseable.
     *
     * Throwable, not Exception: a day carrying tens of megabytes of inline base64 throws
     * OutOfMemoryError, which is an Error and therefore sailed past every `catch (e: Exception)`
     * between here and the top of the worker — one oversized file killed the whole process rather
     * than being skipped as unparseable. Treating "too big to parse" the same as "not a v2 day" is
     * exactly right for this caller: [sync] already leaves both copies untouched when either side
     * won't parse, so the file is left alone instead of being clobbered or crashing the app.
     */
    private fun parseDay(bytes: ByteArray): CalendarDay? =
        try { moshi.adapter(CalendarDay::class.java).fromJson(String(bytes, Charsets.UTF_8)) } catch (e: Throwable) { null }

    /** Serialize a day the same way it's stored, so equal content produces equal strings. */
    private fun dayJson(day: CalendarDay): String = moshi.adapter(CalendarDay::class.java).toJson(day)

    /**
     * True when [day] already holds exactly the merged content — comparing with the merge's
     * timestamps normalised in, so a byte-equal check reflects content only. Used to avoid a needless
     * local write / remote push (and the cross-device ping-pong that would cause).
     */
    private fun sameContent(day: CalendarDay, merged: CalendarDay, mergedJson: String): Boolean {
        val n = day.deepCopy()
        n.updated = merged.updated
        n.created = merged.created
        return dayJson(n) == mergedJson
    }

    private fun watermarkFile() = File(rootDir, ".calendar_webdav_sync_state")

    /** (remoteWatermark, localWatermark) from the last converged pass; (0,0) if none. */
    private fun readWatermarks(): Pair<Long, Long> = runCatching {
        val parts = watermarkFile().readText().trim().split(",")
        (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault(0L to 0L)

    private fun writeWatermarks(remote: Long, local: Long) {
        // Atomic: a truncated watermark parses as (0,0) which is safe (just re-merges), but
        // temp+rename costs nothing and keeps the state.json lesson applied everywhere.
        runCatching {
            val f = watermarkFile()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText("$remote,$local")
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Timber.w(it, "$TAG: failed to store sync watermarks") }
    }

    /**
     * MKCOL the parent collections of [remotePath] then PUT the local file's bytes.
     */
    private fun push(local: LocalEntry): Boolean {
        ensureRemoteParents(local.remotePath)
        return webdav.uploadBytes(local.file.readBytes(), local.remotePath)
    }

    /**
     * GET [remotePath] and write it into the mirrored local location.
     */
    private fun pull(remotePath: String): Boolean {
        val bytes = webdav.download(remotePath) ?: return false
        return writeLocal(remotePath, bytes)
    }

    /**
     * MKCOL every ancestor collection of a remote file so a PUT does not 409 on a missing parent.
     * WebDAV MKCOL is not recursive, so create each prefix in order.
     */
    private fun ensureRemoteParents(remotePath: String) {
        val segments = remotePath.trim('/').split("/").dropLast(1)
        var prefix = ""
        for (segment in segments) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            webdav.ensureDirectory(prefix)
        }
    }

    /**
     * Write [bytes] to <rootDir>/<remotePath> using the same temp-then-atomic-move strategy as
     * CalendarDayService.save, so a kill mid-write never leaves a truncated day file on disk.
     */
    private fun writeLocal(remotePath: String, bytes: ByteArray): Boolean {
        return try {
            // Refuse to install a JSON file that doesn't close.
            //
            // The atomic move is what makes this necessary rather than sufficient: it guarantees
            // the file on disk is a COMPLETE copy of the bytes we were handed, which is no help at
            // all when the bytes themselves are half a day. That is how
            // `day-2026-09-11-v2.json` came to be 39 MB of unterminated string — one inline
            // base64 image, cut off, written perfectly.
            //
            // Structural rather than a full parse: these files reach tens of megabytes and
            // parsing one on a Boox to decide whether to save it would cost more than the sync.
            // Truncation always shows up at the end, so the end is where to look.
            if (remotePath.endsWith(".json") && !closesCleanly(bytes)) {
                Timber.w("$TAG: Refusing truncated $remotePath (${bytes.size} bytes) — keeping the local copy")
                return false
            }
            val target = File(rootDir, remotePath)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Atomic move unavailable for ${target.name}; falling back to replace")
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Timber.i("$TAG: Pulled $remotePath")
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to write local $remotePath")
            false
        }
    }
}
