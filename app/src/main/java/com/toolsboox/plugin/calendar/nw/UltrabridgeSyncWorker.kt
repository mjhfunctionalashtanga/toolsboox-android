package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.*
import com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * WorkManager-based periodic worker that renders calendar pages to PDF
 * and uploads them to a WebDAV server (Ultrabridge backup).
 *
 * Reads WebDAV credentials from EncryptedSharedPreferences:
 *   - ultrabridge_webdav_url
 *   - ultrabridge_webdav_user
 *   - ultrabridge_webdav_pass
 *
 * Tracks sync progress via ultrabridgeLastSyncMs in the main SharedPreferences.
 *
 * PDF naming convention:
 *   ToolsForBoox-Day-2026-05.pdf     (all days in a month, multi-page)
 *   ToolsForBoox-Week-2026-Q2.pdf    (all weeks in a quarter, multi-page)
 *   ToolsForBoox-Month-2026.pdf      (all months in a year, multi-page)
 *   ToolsForBoox-Quarter-2026.pdf    (all quarters in a year, multi-page)
 *   ToolsForBoox-Year-2026.pdf       (single year page)
 */
class UltrabridgeSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "UltrabridgeSyncWorker"
        const val WORK_NAME = "ultrabridge-sync"
        private const val ONE_SHOT_WORK_NAME = "ultrabridge-sync-now"
        private const val ENCRYPTED_PREFS_NAME = "ultrabridge_encrypted_prefs"
        private const val MAIN_PREFS_NAME = "MAIN"
        private const val PREF_LAST_SYNC_MS = "ultrabridgeLastSyncMs"

        /** Human-readable outcome of the last day-JSON mirror pass, and when it ran. */
        const val PREF_LAST_MIRROR_RESULT = "ultrabridgeLastMirrorResult"
        const val PREF_LAST_MIRROR_AT = "ultrabridgeLastMirrorAt"

        /**
         * True while an ink surface is in the foreground. The sync renders every calendar group to
         * PDF (17+ documents) and uploads them — CPU-heavy work that, run alongside active drawing,
         * pegged the SoC and starved the UI thread into a 5s input-timeout ANR ("crashes on rotation
         * while drawing" — really the sync colliding with the pen). While this is set, doWork() bows
         * out with Result.retry(), so the heavy pass only runs once the pen is put down.
         */
        @Volatile
        var inkSurfaceActive: Boolean = false

        fun syncNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<UltrabridgeSyncWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                // On wake/reconnect the CONNECTED constraint is met before WiFi is actually
                // validated, so the first attempt often beats the network. Retry on a short
                // linear backoff (15s, 30s, 45s…) so it recovers in seconds once WiFi settles,
                // instead of the default 30s→60s→120s exponential.
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    15, java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
            // KEEP, not REPLACE. A pass takes minutes (17+ PDF renders, then the day mirror), and
            // both on-open and on-pause call this — so REPLACE meant every trigger CANCELLED the
            // running pass, and whatever sat at its tail never completed. Observed live as
            // "Day-JSON WebDAV mirror failed (non-fatal): JobCancellationException" on a loop: the
            // one pass that actually converges devices was the one reliably killed. The worker is
            // idempotent and re-reads everything each run, so letting a running pass finish is
            // strictly better than restarting it from the top.
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request)
        }

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
    }

    /**
     * Record how the last day-mirror pass went, so Settings can say so.
     *
     * The outage this exists for was invisible for three days: the mirror aborted on every pass,
     * logged one warn line, and the worker returned success. Nothing a person could see said
     * anything was wrong — the surfaces that DON'T use PROPFIND kept syncing, so the app looked
     * healthy. A timestamped one-liner in Settings is the cheapest thing that would have caught it
     * on day one.
     */
    private fun recordSyncOutcome(prefs: SharedPreferences, outcome: String) {
        prefs.edit()
            .putString(PREF_LAST_MIRROR_RESULT, outcome)
            .putLong(PREF_LAST_MIRROR_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get or create EncryptedSharedPreferences for Ultrabridge credentials.
     */
    private fun getEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            applicationContext,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun doWork(): Result {
        // Never run the heavy PDF pass while the pen is live — defer until the surface is idle.
        //
        // Bow out with SUCCESS, not retry. Retry increments runAttemptCount, and the backoff grows
        // with it (15s, 30s, 45s…) — so on a device where the ink surface is the home screen, the
        // deferral fires on nearly every trigger and inflates the backoff for the runs that DO have
        // work to do. There is nothing to retry here anyway: SurfaceFragment.onPause clears this
        // flag and calls syncNow() immediately after, and the periodic worker backs that up. Saying
        // "nothing to do right now" is the honest answer and keeps the backoff meaningful for real
        // failures.
        if (inkSurfaceActive) {
            Timber.i("$TAG: Ink surface active — deferring sync so drawing stays smooth")
            return Result.success()
        }
        Timber.i("$TAG: Starting Ultrabridge PDF sync")

        try {
            val mainPrefs = applicationContext.getSharedPreferences(MAIN_PREFS_NAME, Context.MODE_PRIVATE)
            val encryptedPrefs = getEncryptedPrefs()

            // Read WebDAV credentials
            val webdavUrl = encryptedPrefs.getString("ultrabridge_webdav_url", null)
            val webdavUser = encryptedPrefs.getString("ultrabridge_webdav_user", null)
            val webdavPass = encryptedPrefs.getString("ultrabridge_webdav_pass", null)

            if (webdavUrl.isNullOrBlank() || webdavUser.isNullOrBlank() || webdavPass.isNullOrBlank()) {
                Timber.w("$TAG: WebDAV credentials not configured, skipping sync")
                return Result.success()
            }

            // Host only (no path/creds) so connection failures are diagnosable from logcat.
            val targetHost = runCatching {
                java.net.URI(webdavUrl).let { "${it.scheme}://${it.host}:${it.port}" }
            }.getOrDefault("unparseable")
            Timber.i("$TAG: Sync target: $targetHost")

            // Pre-flight reachability. WorkManager's CONNECTED constraint fires the instant a
            // network attaches — before it's validated — so on wake/reconnect the sync often
            // beats WiFi actually being usable (DNS resolves to nothing / connect hangs). Probe
            // the host cheaply and Result.retry() FAST, rather than rendering every PDF only to
            // fail on upload. The short linear backoff (see syncNow) then re-fires in ~15s, by
            // which point WiFi has usually settled.
            val reachable = withContext(Dispatchers.IO) {
                try {
                    val uri = java.net.URI(webdavUrl)
                    val port = when {
                        uri.port > 0 -> uri.port
                        uri.scheme == "https" -> 443
                        else -> 80
                    }
                    java.net.Socket().use { sock ->
                        sock.connect(java.net.InetSocketAddress(uri.host, port), 4000)
                    }
                    true
                } catch (e: Exception) {
                    Timber.i("$TAG: Host not reachable yet (${e.javaClass.simpleName}); retry when network settles")
                    false
                }
            }
            if (!reachable) return Result.retry()

            val lastSyncMs = mainPrefs.getLong(PREF_LAST_SYNC_MS, 0L)

            val rootDir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }
            val calendarDir = File(rootDir, "calendar/")
            if (!calendarDir.exists()) {
                Timber.i("$TAG: No local calendar directory, nothing to sync")
                return Result.success()
            }

            val allFiles = mutableListOf<File>()
            Files.walk(Paths.get(calendarDir.toURI())).use { stream ->
                stream.map(Path::toFile)
                    .filter(File::isFile)
                    .filter { it.name.endsWith(".json") }
                    .filter { !it.name.startsWith("pattern-") }
                    .forEach { allFiles.add(it) }
            }

            if (allFiles.isEmpty()) {
                Timber.i("$TAG: No calendar files found")
                return Result.success()
            }

            Timber.i("$TAG: Found ${allFiles.size} calendar files to process")

            val groupedFiles = groupFilesByTypeAndPeriod(allFiles, calendarDir)

            // Set up services
            val moshi = buildMoshi()
            val webdavService = UltrabridgeWebDavService(webdavUrl, webdavUser, webdavPass)

            // Create the remote base directory
            webdavService.ensureDirectory("ToolsForBoox")

            var uploadCount = 0
            // Count uploads that were attempted but did not succeed. A single swallowed
            // failure used to still return Result.success(), so WorkManager never retried
            // and the on-exit push silently died on one flaky request — leaving the cloud
            // (and the downstream OCR pipeline) stale until the next periodic window, which
            // Boox battery management often suppresses for days. We now retry instead.
            var failureCount = 0
            val tempDir = File(applicationContext.cacheDir, "ultrabridge-pdf")
            tempDir.mkdirs()

            try {
                // FIRST, before anything expensive: the two-way mirror of the versioned day JSON
                // against the SAME WebDAV tree the iPad uses (calendar/YYYY/MM/day-...-vN.json), so
                // the clients converge. This used to run last, after every PDF had been rendered and
                // pushed — several minutes in — which made it the part of the pass that got dropped
                // whenever anything cut the run short. It is also by far the cheapest and the only
                // part that carries the user's pages between devices, so it goes first: if only one
                // thing survives a truncated pass, this is the thing that should.
                //
                // Failures here don't fail the whole worker — the pass is self-healing and re-runs.
                var mirrorFailed = false
                try {
                    val daySync = CalendarWebDavSyncService(
                        UltrabridgeWebDavService(webdavUrl, webdavUser, webdavPass),
                        rootDir,
                        moshi,
                        applicationContext
                    )
                    val stats = daySync.sync()
                    mirrorFailed = stats.failed > 0
                    Timber.i("$TAG: Day-JSON WebDAV mirror: $stats")
                    recordSyncOutcome(mainPrefs, if (mirrorFailed) "partial: $stats" else "ok: $stats")
                } catch (e: Exception) {
                    mirrorFailed = true
                    Timber.w(e, "$TAG: Day-JSON WebDAV mirror failed (non-fatal)")
                    recordSyncOutcome(mainPrefs, "failed: ${e.javaClass.simpleName}")
                }

                // THE LIBRARY HUB rides the same cadence — this is the "sync tick" the
                // Listen/Library design's pull-on-tick names. After the day mirror (pages
                // before books when a pass gets truncated), before the PDF renders (a book a
                // person is waiting on beats a backup nobody is). Quiet failure by design and
                // deliberately NOT counted into failureCount/mirrorFailed: the pass is
                // self-healing (an unpushed add or unfetched book is simply found again next
                // hour), and a flaky 100MB book download must not put the whole worker — day
                // mirror included — into retry backoff.
                try {
                    withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.reader.ui.LibraryHub.syncPassBlocking(applicationContext)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Library hub pass failed (non-fatal)")
                }

                for ((groupKey, files) in groupedFiles) {
                    // If the pen goes live mid-run, abandon and reschedule — a half-done sync is
                    // fine (it's idempotent), a frozen page is not.
                    if (inkSurfaceActive) {
                        Timber.i("$TAG: Ink surface went active mid-render — yielding, will retry")
                        return Result.retry()
                    }
                    try {
                        val pdfFile = renderGroupToPdf(groupKey, files, moshi, tempDir, calendarDir)
                            ?: continue

                        val remotePath = "ToolsForBoox/${pdfFile.name}"
                        val uploaded = withContext(Dispatchers.IO) {
                            webdavService.upload(pdfFile, remotePath)
                        }

                        if (uploaded) {
                            uploadCount++
                        } else {
                            failureCount++
                            Timber.w("$TAG: Failed to upload ${pdfFile.name}")
                        }

                        // Clean up temp PDF
                        pdfFile.delete()
                    } catch (e: Exception) {
                        failureCount++
                        Timber.e(e, "$TAG: Error processing group $groupKey")
                    }
                }

                // Upload raw day JSON files to WebDAV for downstream processing
                val dayJsonFiles = allFiles.filter { it.name.startsWith("day-") }
                var jsonUploadCount = 0
                webdavService.ensureDirectory("ToolsForBoox/json")
                // Blobs before the day JSONs that reference them — the pinned wire ordering,
                // applied to the processor's copy of the tree. The VPS processor resolves
                // `dataRef`/`cropRef` against LEDGER_MEDIA_DIR, which defaults to the `media/`
                // sibling of the day tree (json-dir/../../media = the same `media/` collection
                // at this sync root that the day mirror pushes to). The mirror above normally
                // pushed everything already, so this is a zero-round-trip no-op via the shared
                // pushed-set; it exists for the pass where the mirror failed but the raw upload
                // still runs — the processor must never compose a page whose refs we could have
                // carried. A blob failure logs inside and never blocks the JSON upload: the
                // processor skips a missing image rather than erroring the day.
                runCatching { CalendarWebDavSyncService.pushMediaBlobs(webdavService, rootDir) }
                    .onFailure { Timber.w(it, "$TAG: media blob push before json/ failed (non-fatal)") }
                for (file in dayJsonFiles) {
                    try {
                        val remotePath = "ToolsForBoox/json/${file.name}"
                        val uploaded = withContext(Dispatchers.IO) {
                            webdavService.upload(file, remotePath)
                        }
                        if (uploaded) jsonUploadCount++ else failureCount++
                    } catch (e: Exception) {
                        failureCount++
                        Timber.e(e, "$TAG: JSON upload error for ${file.name}")
                    }
                }
                Timber.i("$TAG: Uploaded $jsonUploadCount of ${dayJsonFiles.size} day JSON files")

                // If anything failed, ask WorkManager to retry with backoff rather than
                // reporting a clean success. The worker re-uploads everything each run
                // (self-healing), so a retry simply re-attempts the failed files.
                //
                // A failed MIRROR counts too. It used to be swallowed at warn level and the worker
                // still returned success, so the day tree could stop converging entirely — as it did
                // for three days — while every pass reported itself clean.
                if (failureCount > 0 || mirrorFailed) {
                    Timber.w("$TAG: $failureCount upload(s) failed, mirrorFailed=$mirrorFailed; requesting retry")
                    return Result.retry()
                }

                // Update sync cursor (commit, not apply: the worker may be killed
                // immediately after returning, before an async apply() flushes).
                mainPrefs.edit().putLong(PREF_LAST_SYNC_MS, System.currentTimeMillis()).commit()
                Timber.i("$TAG: Sync complete. Uploaded $uploadCount PDFs.")
                return Result.success()

            } finally {
                // Clean up temp directory
                tempDir.listFiles()?.forEach { it.delete() }
            }

        } catch (e: java.net.UnknownHostException) {
            Timber.w(e, "$TAG: Network unavailable, will retry")
            return Result.retry()
        } catch (e: java.net.SocketTimeoutException) {
            Timber.w(e, "$TAG: Network timeout, will retry")
            return Result.retry()
        } catch (e: java.io.IOException) {
            Timber.w(e, "$TAG: IO error, will retry")
            return Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Unexpected error during sync")
            return Result.failure()
        }
    }

    /**
     * Grouping key that identifies a PDF file to produce.
     */
    private data class GroupKey(
        val pageType: String,  // "day", "week", "month", "quarter", "year"
        val period: String     // e.g. "2026-05" for days, "2026-Q2" for weeks, "2026" for months
    )

    /**
     * Group calendar JSON files by page type and time period for PDF batching.
     *
     * Grouping strategy:
     *   - day files    -> grouped by year-month   (ToolsForBoox-Day-2026-05.pdf)
     *   - week files   -> grouped by year-quarter (ToolsForBoox-Week-2026-Q2.pdf)
     *   - month files  -> grouped by year         (ToolsForBoox-Month-2026.pdf)
     *   - quarter files -> grouped by year        (ToolsForBoox-Quarter-2026.pdf)
     *   - year files   -> one per year            (ToolsForBoox-Year-2026.pdf)
     */
    private fun groupFilesByTypeAndPeriod(
        files: List<File>,
        calendarDir: File
    ): Map<GroupKey, List<File>> {
        val groups = mutableMapOf<GroupKey, MutableList<File>>()

        for (file in files) {
            val baseName = file.name.removeSuffix("-v2.json").removeSuffix(".json")
            val groupKey = inferGroupKey(baseName, file, calendarDir) ?: continue
            groups.getOrPut(groupKey) { mutableListOf() }.add(file)
        }

        return groups
    }

    /**
     * Infer the GroupKey from a calendar file's base name.
     */
    private fun inferGroupKey(baseName: String, file: File, calendarDir: File): GroupKey? {
        return when {
            // day-YYYY-MM-DD -> group by YYYY-MM
            baseName.startsWith("day-") -> {
                val parts = baseName.removePrefix("day-").split("-")
                if (parts.size >= 2) {
                    GroupKey("Day", "${parts[0]}-${parts[1]}")
                } else null
            }

            // week-YYYY-WNN -> group by YYYY-Q?
            baseName.startsWith("week-") -> {
                val parts = baseName.removePrefix("week-").split("-")
                if (parts.size >= 2) {
                    val year = parts[0]
                    val weekStr = parts[1].removePrefix("W").removePrefix("w")
                    val weekNum = weekStr.toIntOrNull() ?: return null
                    val quarter = ((weekNum - 1) / 13) + 1
                    GroupKey("Week", "$year-Q$quarter")
                } else null
            }

            // month-YYYY-MM -> group by YYYY
            baseName.startsWith("month-") -> {
                val parts = baseName.removePrefix("month-").split("-")
                if (parts.isNotEmpty()) {
                    GroupKey("Month", parts[0])
                } else null
            }

            // quarter-YYYY-QN -> group by YYYY
            baseName.startsWith("quarter-") -> {
                val parts = baseName.removePrefix("quarter-").split("-")
                if (parts.isNotEmpty()) {
                    GroupKey("Quarter", parts[0])
                } else null
            }

            // year-YYYY -> group by YYYY
            baseName.startsWith("year-") -> {
                val year = baseName.removePrefix("year-").split("-").firstOrNull()
                if (year != null) {
                    GroupKey("Year", year)
                } else null
            }

            else -> null
        }
    }

    /**
     * Render a group of calendar files to a single multi-page PDF.
     * Returns the temp PDF file, or null if no pages could be loaded.
     */
    private suspend fun renderGroupToPdf(
        groupKey: GroupKey,
        files: List<File>,
        moshi: Moshi,
        tempDir: File,
        calendarDir: File
    ): File? = withContext(Dispatchers.Default) {
        // For a modified group, load ALL files in that group's scope (not just modified ones)
        // so the PDF always contains the complete set.
        val allFilesInGroup = findAllFilesForGroup(groupKey, calendarDir)

        val pages = mutableListOf<Pair<String, com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.PageContent>>()

        // Sort files for consistent page ordering
        val sortedFiles = allFilesInGroup.sortedBy { it.name }

        for (file in sortedFiles) {
            val calendarData = loadCalendarData(file, moshi) ?: continue
            pages.add(file.name to calendarData)
        }

        if (pages.isEmpty()) return@withContext null

        val pdfFileName = "ToolsForBoox-${groupKey.pageType}-${groupKey.period}.pdf"
        val pdfFile = File(tempDir, pdfFileName)

        CalendarPdfRenderer.renderMonthToPdf(pages, pdfFile, context = applicationContext)

        Timber.i("$TAG: Rendered ${pages.size} pages to $pdfFileName")
        pdfFile
    }

    /**
     * Find ALL calendar files belonging to a group, not just the modified ones.
     * This ensures the PDF always contains the complete set for its scope.
     */
    private fun findAllFilesForGroup(groupKey: GroupKey, calendarDir: File): List<File> {
        val allFiles = mutableListOf<File>()

        Files.walk(Paths.get(calendarDir.toURI())).use { stream ->
            stream.map(Path::toFile)
                .filter(File::isFile)
                .filter { it.name.endsWith(".json") }
                .filter { !it.name.startsWith("pattern-") }
                .forEach { file ->
                    val baseName = file.name.removeSuffix("-v2.json").removeSuffix(".json")
                    val fileGroupKey = inferGroupKey(baseName, file, calendarDir)
                    if (fileGroupKey == groupKey) {
                        allFiles.add(file)
                    }
                }
        }

        // Prefer v2 files over v1 when both exist
        val byBaseName = allFiles.groupBy { it.name.removeSuffix("-v2.json").removeSuffix(".json") }
        return byBaseName.map { (_, versions) ->
            versions.firstOrNull { it.name.contains("-v2.json") } ?: versions.first()
        }
    }

    /**
     * Load a calendar JSON file and extract its stroke maps.
     * Handles all calendar types (day, week, month, quarter, year) in both v1 and v2 formats.
     */
    /**
     * Build a PageContent from any calendar type: strokes always, plus text boxes (all
     * types) and images (day pages only). Keeps typed/pasted text + images in the exported
     * PDF so they reach the OCR/notes pipeline instead of being silently dropped.
     */
    private fun pageContentOf(cal: com.toolsboox.plugin.calendar.da.v2.Calendar): com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.PageContent {
        val images = (cal as? CalendarDay)?.imageElements ?: emptyList()
        return com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.PageContent(
            cal.calendarStrokes, cal.noteStrokes, cal.textElements, images
        )
    }

    private fun loadCalendarData(
        file: File,
        moshi: Moshi
    ): com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.PageContent? {
        try {
            val json = file.readText(Charsets.UTF_8)
            // Empty/half-synced day files are expected (unwritten days, interrupted
            // downloads). Skip them quietly instead of throwing EOFException and logging
            // a stack trace per file — they simply contribute no page to the PDF.
            if (json.isBlank()) return null
            val name = file.name
            val isV2 = name.endsWith("-v2.json")

            return when {
                name.startsWith("day-") -> {
                    if (isV2) {
                        moshi.adapter(CalendarDay::class.java).fromJson(json)?.let {
                            pageContentOf(it)
                        }
                    } else {
                        moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarDay::class.java)
                            .fromJson(json)?.let { CalendarDay.convert(it) }?.let {
                                pageContentOf(it)
                            }
                    }
                }

                name.startsWith("week-") -> {
                    if (isV2) {
                        moshi.adapter(CalendarWeek::class.java).fromJson(json)?.let {
                            pageContentOf(it)
                        }
                    } else {
                        moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarWeek::class.java)
                            .fromJson(json)?.let { CalendarWeek.convert(it) }?.let {
                                pageContentOf(it)
                            }
                    }
                }

                name.startsWith("month-") -> {
                    if (isV2) {
                        moshi.adapter(CalendarMonth::class.java).fromJson(json)?.let {
                            pageContentOf(it)
                        }
                    } else {
                        moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarMonth::class.java)
                            .fromJson(json)?.let { CalendarMonth.convert(it) }?.let {
                                pageContentOf(it)
                            }
                    }
                }

                name.startsWith("quarter-") -> {
                    if (isV2) {
                        moshi.adapter(CalendarQuarter::class.java).fromJson(json)?.let {
                            pageContentOf(it)
                        }
                    } else {
                        moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarQuarter::class.java)
                            .fromJson(json)?.let { CalendarQuarter.convert(it) }?.let {
                                pageContentOf(it)
                            }
                    }
                }

                name.startsWith("year-") -> {
                    if (isV2) {
                        moshi.adapter(CalendarYear::class.java).fromJson(json)?.let {
                            pageContentOf(it)
                        }
                    } else {
                        moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarYear::class.java)
                            .fromJson(json)?.let { CalendarYear.convert(it) }?.let {
                                pageContentOf(it)
                            }
                    }
                }

                else -> null
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error loading calendar data from ${file.name}")
            return null
        }
    }
}
