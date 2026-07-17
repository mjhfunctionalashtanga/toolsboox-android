package com.toolsboox.plugin.calendar.ui

import android.content.SharedPreferences
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.toolsboox.da.Attachment
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import com.toolsboox.databinding.FragmentCalendarGoogleDriveSyncBinding
import com.toolsboox.di.GoogleDriveModule
import com.toolsboox.fi.GoogleDriveService
import com.toolsboox.plugin.calendar.da.v1.CalendarSyncItem
import com.toolsboox.plugin.calendar.da.v1.CalendarSyncViewItem
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.*
import com.toolsboox.ui.plugin.FragmentPresenter
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDate
import java.util.*
import javax.inject.Inject


/**
 * Calendar Google Drive sync presenter.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarGoogleDriveSyncPresenter @Inject constructor() : FragmentPresenter() {
    /**
     * The shared preferences.
     */
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    /**
     * The calendar day service.
     */
    @Inject
    lateinit var calendarDayService: CalendarDayService

    /**
     * The calendar month service.
     */
    @Inject
    lateinit var calendarMonthService: CalendarMonthService

    /**
     * The calendar pattern service.
     */
    @Inject
    lateinit var calendarPatternService: CalendarPatternService

    /**
     * The calendar quarter service.
     */
    @Inject
    lateinit var calendarQuarterService: CalendarQuarterService

    /**
     * The calendar week service.
     */
    @Inject
    lateinit var calendarWeekService: CalendarWeekService

    /**
     * The calendar year service.
     */
    @Inject
    lateinit var calendarYearService: CalendarYearService

    // Google sign-in client.
    @Inject
    lateinit var googleSignInClient: GoogleSignInClient

    /**
     * Load the .json files of the Calendar plugin.
     *
     * @param fragment the fragment
     * @param userId the user ID
     * @param binding the binding on the fragment
     * @return the list of files
     */
    fun fileList(fragment: CalendarGoogleDriveSyncFragment, userId: UUID, binding: FragmentCalendarGoogleDriveSyncBinding) {
        if (!checkPermissions(fragment, binding.root)) return

        fragment.showLoading()
        val calendarSyncItems: MutableList<CalendarSyncItem> = mutableListOf()
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)
                calendarSyncItems.addAll(fileList(rootPath, userId))
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    fragment.somethingHappened(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    fragment.hideLoading()
                    fragment.fileListResult(calendarSyncItems)
                }
            }
        }
    }

    /**
     * Load the list of files from Google Drive.
     *
     * @param fragment the fragment
     * @param driveService the Google Drive service
     */
    fun cloudList(fragment: CalendarGoogleDriveSyncFragment, driveService: Drive) {
        fragment.showLoading()
        val calendarSyncItems = mutableListOf<CalendarSyncItem>()
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                calendarSyncItems.addAll(cloudList(driveService))
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    fragment.somethingHappened(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    fragment.hideLoading()
                    fragment.cloudListResult(calendarSyncItems)
                }
            }
        }
    }

    /**
     * Load the specified item from file.
     *
     * @param fragment the fragment
     * @param calendarSyncItem the calendar sync item
     * @param binding the binding on the fragment
     * @return the loaded content
     */
    fun fileLoad(fragment: CalendarGoogleDriveSyncFragment, calendarSyncItem: CalendarSyncItem, binding: FragmentCalendarGoogleDriveSyncBinding) {
        if (!checkPermissions(fragment, binding.root)) return

        fragment.showLoading()
        val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = fileLoad(rootPath, calendarSyncItem)
                fragment.lifecycleScope.launch(Dispatchers.Main) {
                    fragment.hideLoading()
                    fragment.fileLoadResult(result)
                }

            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    fragment.somethingHappened(e)
                }
            }
        }
    }

    /**
     * Load the specified item from Google Drive.
     *
     * @param fragment the fragment
     * @param driveService the Google Drive service
     * @param calendarSyncItem the calendar sync item
     */
    fun cloudLoad(fragment: CalendarGoogleDriveSyncFragment, driveService: Drive, calendarSyncItem: CalendarSyncItem) {
        fragment.showLoading()
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loadedItem = cloudLoad(driveService, calendarSyncItem)
                withContext(Dispatchers.Main) {
                    fragment.hideLoading()
                    fragment.cloudLoadResult(loadedItem)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    fragment.somethingHappened(e)
                }
            }
        }
    }

    /**
     * Update the cloudUpdate field of the specified item.
     *
     * @param fragment the fragment
     * @param calendarSyncItem the calendar sync item
     * @param binding the binding on the fragment
     * @return the loaded JSON
     */
    fun fileUpdate(fragment: CalendarGoogleDriveSyncFragment, calendarSyncItem: CalendarSyncItem, binding: FragmentCalendarGoogleDriveSyncBinding) {
        if (!checkPermissions(fragment, binding.root)) return

        fragment.showLoading()
        val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = fileUpdate(rootPath, calendarSyncItem)
                fragment.lifecycleScope.launch(Dispatchers.Main) {
                    fragment.hideLoading()
                    fragment.fileUpdateResult(result)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    fragment.somethingHappened(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    fragment.hideLoading()
                }
            }
        }
    }

    /**
     * Update the specified calendar item on Google Drive.
     *
     * @param fragment the fragment
     * @param driveService the Google Drive service
     * @param calendarSyncItem the calendar sync item
     */
    fun cloudUpdate(fragment: CalendarGoogleDriveSyncFragment, driveService: Drive, calendarSyncItem: CalendarSyncItem) {
        fragment.showLoading()
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            try {
                cloudUpdate(driveService, calendarSyncItem)
                withContext(Dispatchers.Main) {
                    fragment.hideLoading()
                    fragment.cloudUpdateResult(calendarSyncItem)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    fragment.somethingHappened(e)
                }
            }
        }
    }

    /**
     * Synchronize the local files with the Google Drive.
     *
     * @param fragment the fragment
     * @param userId the user ID
     * @param driveService the Google Drive service
     */
    fun backgroundSync(fragment: ScreenFragment, userId: UUID) {
        // Prevent sync if the auto-sync is manually disabled.
        if (!sharedPreferences.getString("googleDriveAutoSyncOptIn", "true").toBoolean()) return

        // Prevent multiple syncs in a short time.
        val lastCalendarGoogleDriveBackgroundSync = Instant.ofEpochMilli(sharedPreferences.getLong("lastCalendarGoogleDriveBackgroundSync", 0L))
        if (Instant.now().minusSeconds(30).isBefore(lastCalendarGoogleDriveBackgroundSync)) return
        sharedPreferences.edit().putLong("lastCalendarGoogleDriveBackgroundSync", Instant.now().toEpochMilli()).apply()

        // Start silent sign-in to acquire the drive API.
        googleSignInClient.silentSignIn()
            .addOnSuccessListener { result ->
                Timber.i("Silent-signed into a GoogleAccount: ${result.id}")
                GoogleDriveModule.provideCredential(fragment.requireContext(), result)
                    .let { credential ->
                        val driveService = GoogleDriveModule.provideDrive(credential)
                        fragment.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val rootPath = rootPath(fragment, Environment.DIRECTORY_DOCUMENTS)
                                val fileList = fileList(rootPath, userId)
                                val cloudList = cloudList(driveService)
                                val syncList = calculateSyncList(fileList, cloudList)

                                // Carry annotation / A-V-gram media blobs too (immutable, UUID-named).
                                runCatching { syncDriveAttachments(driveService, rootPath) }
                                    .onFailure { Timber.w(it, "Drive attachment sync failed") }

                                if (syncList.isEmpty()) return@launch

                                Timber.i("Background sync items: ${syncList}")
                                syncList.forEach { item ->
                                    val fileItem = item.file
                                    val cloudItem = item.cloud
                                    when {
                                        // New on cloud only → pull it down as-is.
                                        fileItem == null && cloudItem != null -> {
                                            Timber.i("File create (cloud-only): ${cloudItem}")
                                            fileUpdate(rootPath, cloudLoad(driveService, cloudItem))
                                        }
                                        // New on local only → push it up as-is.
                                        cloudItem == null && fileItem != null -> {
                                            Timber.i("Cloud create (local-only): ${fileItem}")
                                            cloudUpdate(driveService, fileLoad(rootPath, fileItem))
                                        }
                                        // Exists on both and diverged → stroke-level MERGE (union
                                        // by id) instead of last-write-wins clobber, then write the
                                        // merged result to BOTH sides so the two devices converge.
                                        fileItem != null && cloudItem != null -> {
                                            val localLoaded = fileLoad(rootPath, fileItem)
                                            val cloudLoaded = cloudLoad(driveService, cloudItem)
                                            val merged = mergeDaySyncItem(localLoaded, cloudLoaded)
                                            if (merged != null) {
                                                Timber.i("Merge (both sides): ${fileItem.baseName}")
                                                fileUpdate(rootPath, merged)
                                                cloudUpdate(driveService, merged)
                                            } else {
                                                // Non-day / legacy file → last-write-wins fallback.
                                                if ((fileItem.updated?.time ?: 0L) < (cloudItem.updated?.time ?: 0L)) {
                                                    Timber.i("File update (LWW): ${cloudItem}")
                                                    fileUpdate(rootPath, cloudLoaded)
                                                } else {
                                                    Timber.i("Cloud update (LWW): ${fileItem}")
                                                    cloudUpdate(driveService, localLoaded)
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: IOException) {
                                Timber.w(e, "Background Drive sync network failure")
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        fragment.requireContext(),
                                        "⚠ Drive sync failed — check your connection. Changes will sync when you're back online.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
            }
            .addOnFailureListener { e ->
                Timber.i("Silent-sign-in failed: ${e.message}")
                android.widget.Toast.makeText(
                    fragment.requireContext(),
                    "⚠ Couldn't reach Google Drive to sync — check your connection.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
    }

    /**
     * Sync the annotation / A-V-gram media blobs to Drive. Immutable, UUID-named files, so a
     * simple push-new / pull-missing pass with no conflicts. Each is tagged with a
     * {type: attachment} property so it can be enumerated regardless of folder. Mirrors the
     * WebDAV attachment sync so both cloud back-ends carry the media the day JSON references.
     */
    private fun syncDriveAttachments(driveService: Drive, rootPath: File) {
        val dir = File(rootPath, "attachments")
        val local = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val remote = GoogleDriveService.walkByProperty(driveService, Pair("type", "attachment"))
        val remoteNames = remote.mapNotNull { it.name }.toSet()
        val localNames = local.map { it.name }.toSet()

        val toPush = local.filter { it.name !in remoteNames }
        if (toPush.isNotEmpty()) {
            val attachRoot = GoogleDriveService.getOrCreateRootFolder(driveService, "attachments") ?: return
            toPush.forEach { f ->
                runCatching {
                    GoogleDriveService.uploadFile(
                        driveService, attachRoot, f.name, FileContent(mimeFor(f.name), f), mapOf("type" to "attachment")
                    )
                }.onFailure { Timber.w(it, "Drive attachment push failed: ${f.name}") }
            }
        }

        if (!dir.exists()) dir.mkdirs()
        remote.forEach { rf ->
            val name = rf.name ?: return@forEach
            if (name in localNames) return@forEach
            runCatching {
                File(dir, name).outputStream().use { GoogleDriveService.downloadFile(driveService, rf, it) }
            }.onFailure { Timber.w(it, "Drive attachment pull failed: $name") }
        }
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }

    /**
     * Returns with the list of files from the filesystem.
     *
     * @param rootPath the root path
     * @param userId the user ID
     * @return the list of files
     */
    private fun fileList(rootPath: File, userId: UUID): MutableList<CalendarSyncItem> {
        val calendarSyncItems: MutableList<CalendarSyncItem> = mutableListOf()

        val path = File(rootPath, "calendar/")
        if (!path.exists()) return calendarSyncItems

        // Collect the .json files with a fault-tolerant walk. Day pages are saved
        // atomically (write day-*.json.tmp, then rename over day-*.json), so a *.tmp
        // file can vanish mid-traversal. Files.walk reads attributes eagerly while
        // walking and would throw NoSuchFileException (→ UncheckedIOException) on that
        // race, killing the background sync coroutine. walkFileTree + visitFileFailed
        // lets us skip a transient/vanished entry and keep going.
        val jsonFiles = mutableListOf<File>()
        try {
            Files.walkFileTree(Paths.get(path.toURI()), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val f = file.toFile()
                    if (f.isFile && f.name.endsWith(".json") && !f.name.startsWith("pattern-")) {
                        jsonFiles.add(f)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Timber.w(exc, "fileList: skipping unreadable/vanished path $file")
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            Timber.w(e, "fileList: walk failed for $path")
        }

        jsonFiles.forEach { item ->
            try {
                calendarYearService.load(item)?.let { calendarYear ->
                    calendarSyncItems.add(calendarYearService.getItem(userId, calendarYear))
                }
                calendarQuarterService.load(item)?.let { calendarQuarter ->
                    calendarSyncItems.add(calendarQuarterService.getItem(userId, calendarQuarter))
                }
                calendarMonthService.load(item)?.let { calendarMonth ->
                    calendarSyncItems.add(calendarMonthService.getItem(userId, calendarMonth))
                }
                calendarWeekService.load(item)?.let { calendarWeek ->
                    calendarSyncItems.add(calendarWeekService.getItem(userId, calendarWeek))
                }
                calendarDayService.load(item)?.let { calendarDay ->
                    calendarSyncItems.add(calendarDayService.getItem(userId, calendarDay))
                }
            } catch (e: Exception) {
                // A file can still disappear between the walk and load() (same save race);
                // don't let one transient item abort the whole sync scan.
                Timber.w(e, "fileList: skipping item ${item.name}")
            }
        }

        return calendarSyncItems
    }

    /**
     * Returns with the list of files from Google Drive.
     *
     * @param driveService the Google Drive service
     * @return the list of files
     */
    private fun cloudList(driveService: Drive): MutableList<CalendarSyncItem> {
        val calendarSyncItems: MutableList<CalendarSyncItem> = mutableListOf()

        val files = GoogleDriveService.walkByProperty(driveService, Pair("type", "calendar"))
        files.forEach {
            if (it.properties == null) return@forEach
            val path = it.properties["path"] ?: return@forEach
            val baseName = it.properties["baseName"] ?: return@forEach
            val version = it.properties["version"] ?: return@forEach
            val created = it.properties["created"]?.toLong() ?: return@forEach
            val modified = it.properties["modified"]?.toLong() ?: return@forEach
            calendarSyncItems.add(CalendarSyncItem(UUID.randomUUID(), path, baseName, version, Date(created), Date(modified), null))
        }

        return calendarSyncItems
    }

    /**
     * Load the specified item from file.
     *
     * @param rootPath the root path
     * @param calendarSyncItem the calendar sync item
     * @return the loaded content
     */
    private fun fileLoad(rootPath: File, calendarSyncItem: CalendarSyncItem): CalendarSyncItem {
        calendarYearService.load(rootPath, calendarSyncItem.path, calendarSyncItem.baseName)?.let {
            calendarSyncItem.json = calendarYearService.json(it)
        }
        calendarQuarterService.load(rootPath, calendarSyncItem.path, calendarSyncItem.baseName)?.let {
            calendarSyncItem.json = calendarQuarterService.json(it)
        }
        calendarMonthService.load(rootPath, calendarSyncItem.path, calendarSyncItem.baseName)?.let {
            calendarSyncItem.json = calendarMonthService.json(it)
        }
        calendarWeekService.load(rootPath, calendarSyncItem.path, calendarSyncItem.baseName)?.let {
            calendarSyncItem.json = calendarWeekService.json(it)
        }
        calendarDayService.load(rootPath, calendarSyncItem.path, calendarSyncItem.baseName)?.let {
            calendarSyncItem.json = calendarDayService.json(it)
        }

        return calendarSyncItem
    }

    /**
     * Load the specified item from Google Drive.
     *
     * @param driveService the Google Drive service
     * @param fileName the file name
     * @param calendarSyncItem the calendar sync item
     * @return the loaded content
     */
    private fun cloudLoad(driveService: Drive, calendarSyncItem: CalendarSyncItem): CalendarSyncItem {
        val fileName = "${calendarSyncItem.baseName}-${calendarSyncItem.version}.json"

        val appRoot = GoogleDriveService.getOrCreateRootFolder(driveService, "calendar")
        val folder = GoogleDriveService.getOrCreatePath(driveService, appRoot!!, calendarSyncItem.path)
        val file = GoogleDriveService.getFile(driveService, folder!!, fileName)

        val byteArrayOutputStream = ByteArrayOutputStream()
        GoogleDriveService.downloadFile(driveService, file!!, byteArrayOutputStream)
        calendarSyncItem.json = byteArrayOutputStream.toString("UTF-8")

        return calendarSyncItem
    }

    /**
     * Update the specified item on the filesystem.
     *
     * @param rootPath the root path
     * @param calendarSyncItem the calendar sync item
     * @return the updated item
     */
    private fun fileUpdate(rootPath: File, calendarSyncItem: CalendarSyncItem): CalendarSyncItem {
        calendarYearService.fromSyncItem(calendarSyncItem)?.let {
            calendarYearService.save(rootPath, calendarSyncItem.path, calendarSyncItem.baseName, it)

            val currentDate = LocalDate.ofYearDay(it.year, 1)
            val calendarPattern = calendarPatternService.load(rootPath, currentDate, it.locale)
            calendarPattern.updateYear(it)
            calendarPatternService.save(rootPath, currentDate, calendarPattern)
        }
        calendarQuarterService.fromSyncItem(calendarSyncItem)?.let {
            calendarQuarterService.save(rootPath, calendarSyncItem.path, calendarSyncItem.baseName, it)

            val currentDate = LocalDate.ofYearDay(it.year, 1)
            val calendarPattern = calendarPatternService.load(rootPath, currentDate, it.locale)
            calendarPattern.updateQuarter(it)
            calendarPatternService.save(rootPath, currentDate, calendarPattern)
        }
        calendarMonthService.fromSyncItem(calendarSyncItem)?.let {
            calendarMonthService.save(rootPath, calendarSyncItem.path, calendarSyncItem.baseName, it)

            val currentDate = LocalDate.ofYearDay(it.year, 1)
            val calendarPattern = calendarPatternService.load(rootPath, currentDate, it.locale)
            calendarPattern.updateMonth(it)
            calendarPatternService.save(rootPath, currentDate, calendarPattern)
        }
        calendarWeekService.fromSyncItem(calendarSyncItem)?.let {
            calendarWeekService.save(rootPath, calendarSyncItem.path, calendarSyncItem.baseName, it)

            val currentDate = LocalDate.ofYearDay(it.year, 1)
            val calendarPattern = calendarPatternService.load(rootPath, currentDate, it.locale)
            calendarPattern.updateWeek(it)
            calendarPatternService.save(rootPath, currentDate, calendarPattern)
        }
        calendarDayService.fromSyncItem(calendarSyncItem)?.let {
            calendarDayService.save(rootPath, calendarSyncItem.path, calendarSyncItem.baseName, it)

            val currentDate = LocalDate.ofYearDay(it.year, 1)
            val calendarPattern = calendarPatternService.load(rootPath, currentDate, it.locale)
            calendarPattern.updateDay(it)
            calendarPatternService.save(rootPath, currentDate, calendarPattern)
        }

        return calendarSyncItem
    }

    /**
     * Update the specified item on Google Drive.
     *
     * @param driveService the Google Drive service
     * @param calendarSyncItem the calendar sync item
     * @return the updated item
     */
    private fun cloudUpdate(driveService: Drive, calendarSyncItem: CalendarSyncItem) {
        val appRoot = GoogleDriveService.getOrCreateRootFolder(driveService, "calendar")
        val folder = GoogleDriveService.getOrCreatePath(driveService, appRoot!!, calendarSyncItem.path)

        val properties = mutableMapOf<String, String>()
        properties["type"] = "calendar"
        properties["path"] = calendarSyncItem.path
        properties["baseName"] = calendarSyncItem.baseName
        properties["version"] = calendarSyncItem.version
        properties["created"] = (calendarSyncItem.created?.time ?: Instant.now().toEpochMilli()).toString()
        properties["modified"] = (calendarSyncItem.updated?.time ?: Instant.now().toEpochMilli()).toString()

        val fileName = "${calendarSyncItem.baseName}-${calendarSyncItem.version}.json"
        val content = ByteArrayContent.fromString("application/json", calendarSyncItem.json!!)

        GoogleDriveService.uploadFile(driveService, folder!!, fileName, content, properties)
    }

    /**
     * Calculate the synchronization list.
     *
     * @param fileList the list of files
     * @param cloudList the list of files on Google Drive
     * @return the list of items to synchronize
     */
    fun calculateSyncList(fileList: List<CalendarSyncItem>, cloudList: List<CalendarSyncItem>): List<CalendarSyncViewItem> {
        val syncList = mutableListOf<CalendarSyncViewItem>()

        fileList.forEach { fci ->
            // If the file is not in the cloud, add it.
            if (fci.updated == null) {
                syncList.add(CalendarSyncViewItem(fci, fci, null))
                return@forEach
            }

            // If the file is in the cloud, checks the path, the baseName and the version.
            val cloudItem = cloudList
                .filter { it.path == fci.path }
                .filter { it.baseName == fci.baseName }
                .firstOrNull { it.version == fci.version }

            // If the file is not in the cloud, add it.
            if (cloudItem == null) {
                syncList.add(CalendarSyncViewItem(fci, fci, null))
                return@forEach
            }

            if ((cloudItem.updated != null) and (cloudItem.updated!!.time < fci.updated.time)) {
                syncList.add(CalendarSyncViewItem(fci, fci, cloudItem))
                return@forEach
            }
        }

        cloudList.forEach { cci ->
            // It's a bug?!
            if (cci.updated == null) {
                return@forEach
            }

            val fileItem = fileList
                .filter { it.path == cci.path }
                .filter { it.baseName == cci.baseName }
                .firstOrNull { it.version == cci.version }

            if (fileItem == null) {
                syncList.add(CalendarSyncViewItem(cci, null, cci))
            } else if (fileItem.updated != null) {
                if (fileItem.updated.time < cci.updated.time) {
                    syncList.add(CalendarSyncViewItem(fileItem, fileItem, cci))
                }
            }
        }

        return syncList
    }

    /**
     * Stroke-level merge of a day page that diverged on two devices.
     *
     * Both sides are parsed to [CalendarDay] and their strokes, text, and images are
     * UNIONED by their stable IDs, so two devices that wrote on the same day COMBINE
     * instead of last-write-wins clobbering one. Only v2 day files merge; anything else
     * (legacy v1, week/month/year) returns null so the caller falls back to LWW.
     *
     * @param local the local side sync item (json loaded)
     * @param cloud the cloud side sync item (json loaded)
     * @return a sync item carrying the merged json, or null if not mergeable
     */
    private fun mergeDaySyncItem(local: CalendarSyncItem, cloud: CalendarSyncItem): CalendarSyncItem? {
        if (local.version != "v2" || cloud.version != "v2") return null
        val localDay = calendarDayService.fromSyncItem(local) ?: return null
        val cloudDay = calendarDayService.fromSyncItem(cloud) ?: return null
        val merged = mergeCalendarDay(localDay, cloudDay)
        return local.copy(json = calendarDayService.json(merged), created = merged.created, updated = merged.updated)
    }

    /**
     * Merge two versions of the same calendar day — now the shared [CalendarDayMerger] used by both
     * the Drive and WebDAV sync paths, so they can never drift apart.
     */
    private fun mergeCalendarDay(a: CalendarDay, b: CalendarDay): CalendarDay =
        com.toolsboox.plugin.calendar.fi.CalendarDayMerger.merge(a, b)
}