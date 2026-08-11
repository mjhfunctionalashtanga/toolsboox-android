package com.toolsboox.plugin.calendar.nw

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * PURE Drive path/query logic, split from the transport so the JVM tests can hold it without a
 * network or an Android runtime. Drive is not a filesystem — it is a flat id-space wearing a
 * folder costume — so the seam's slash-paths have to be translated: segments become a chain of
 * folder lookups, and names go into Drive's query language, which has quoting rules of its own.
 */
object DriveHubPaths {

    /** A seam path split into what Drive thinks in: folder segments to walk, then a file name. */
    data class Split(val dirSegments: List<String>, val name: String)

    /**
     * "books/Ashtanga/Light on Yoga.epub" → segments [books, Ashtanga] + name "Light on Yoga.epub".
     * Blank segments (doubled or leading slashes) are dropped — the WebDAV side normalizes the
     * same way, and a path that means something different per backend would be a seam leak.
     */
    fun split(remotePath: String): Split {
        val parts = remotePath.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return Split(emptyList(), "")
        return Split(parts.dropLast(1), parts.last())
    }

    /**
     * A file name made safe inside a Drive `q` string literal. Drive's query language delimits
     * with single quotes and escapes with backslash — so both characters in a book's actual name
     * ("O'Reilly's 100% guide.pdf") must be escaped, or the query is a syntax error at best and a
     * different query at worst. Backslash first, then quote, or the quote's escape gets escaped.
     */
    fun escapeQuery(name: String): String =
        name.replace("\\", "\\\\").replace("'", "\\'")

    /** The query that finds a FILE by name inside a folder. Trashed files are dead to us: a user
     *  who trashed a book in Drive said something, and resurrecting it via sync would unsay it. */
    fun fileQuery(name: String, parentId: String): String =
        "name='${escapeQuery(name)}' and '${escapeQuery(parentId)}' in parents" +
            " and mimeType!='application/vnd.google-apps.folder' and trashed=false"

    /** The query that finds a FOLDER by name inside a parent. */
    fun folderQuery(name: String, parentId: String): String =
        "name='${escapeQuery(name)}' and '${escapeQuery(parentId)}' in parents" +
            " and mimeType='application/vnd.google-apps.folder' and trashed=false"
}

/**
 * THE SECOND BACKEND — Google Drive behind the [HubTransport] seam (design step D, 2026-08-11).
 *
 * WHY A VISIBLE "Ledger" FOLDER AND NOT appDataFolder: the old day-file Drive sync
 * ([com.toolsboox.fi.GoogleDriveService]) lives in appDataFolder — invisible, unbrowsable, deleted
 * wholesale when the app is uninstalled. That is the right place for an app's private mirror and
 * the WRONG place for a person's library: the design's spirit is user-ownable data ("the hub is
 * the truth"), and a truth you cannot open in drive.google.com, share, or take with you when you
 * leave the app is not owned, it is hostage. So this transport keeps everything under a plain
 * "Ledger" folder in My Drive — books/ inside it exactly mirrors the WebDAV tree, and a person
 * can watch their library arrive.
 *
 * AUTH: the sign-in the app already holds. [com.toolsboox.di.GoogleDriveModule] has requested
 * `DRIVE_FILE` scope since the day-sync era, and `DRIVE_FILE` is precisely enough for a visible
 * folder THIS app created — it grants nothing over the rest of the person's Drive. No new consent
 * screen, no new flow: [create] returns null when no account is signed in (Settings guides to the
 * existing Connect button), and the seam's callers already treat a null transport as "not
 * configured", the same quiet posture as a blank WebDAV URL.
 *
 * UPLOADS ARE RESUMABLE AND STREAMED: [putFile] hands the google-api-client a [FileContent],
 * whose media uploader defaults to the resumable protocol and reads the file in chunks — a 100MB
 * book never exists whole in RAM, matching the WebDAV side's streaming PUT. The `.part`-then-
 * rename civility maps as upload-under-temp-name then a files.update rename ([move]); Drive's
 * rename cannot be made atomic-with-overwrite the way WebDAV's MOVE can, so [move] deletes the
 * old holder of the name after the rename lands — the gap is a moment of two files with one name,
 * and [get] resolves that by newest-modified, which is always the finished upload.
 *
 * Blocking, quiet on failure, never on the main thread — the seam's contract. An expired or
 * scope-less token surfaces as UserRecoverableAuthIOException; it is caught like any other
 * failure, because a background sync pass has no business launching consent UI. The person
 * reconnects in Settings; until then the transport answers null/false, honestly.
 */
class DriveHubTransport private constructor(private val drive: Drive) : HubTransport {

    companion object {
        private const val TAG = "DriveHubTransport"

        /** The one visible root. A short, human name on purpose — this folder is the product's
         *  face inside the person's own Drive. */
        const val ROOT_FOLDER = "Ledger"

        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FIELDS = "files(id, name, mimeType, modifiedTime)"

        /**
         * Folder path → Drive folder id, process-lifetime. Folder ids are stable in Drive (a
         * rename keeps the id), so the cache only ever saves round trips; it is dropped wholesale
         * on any resolution failure rather than repaired, because a stale id that 404s once will
         * 404 forever and wholesale forgetting is the version of "invalidate" that cannot be
         * subtly wrong. Keyed "" for the Ledger root itself.
         */
        private val folderIds = ConcurrentHashMap<String, String>()

        /**
         * The transport for the signed-in account, or null when there isn't one — which callers
         * read exactly as they read a blank WebDAV URL: not configured, stay quiet.
         */
        fun create(context: Context): DriveHubTransport? = runCatching {
            val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
            val credential = GoogleAccountCredential
                .usingOAuth2(context.applicationContext, listOf(DriveScopes.DRIVE_FILE))
            credential.selectedAccount = account
            val drive = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
                .setApplicationName("Ledger")
                .build()
            DriveHubTransport(drive)
        }.onFailure { Timber.w(it, "$TAG: create failed") }.getOrNull()
    }

    // ── Folder resolution ─────────────────────────────────────────────────────────────────────

    /** The Ledger root's id, creating the folder on first touch when [create] is true. */
    private fun rootId(createMissing: Boolean): String? {
        folderIds[""]?.let { return it }
        val found = runCatching {
            drive.files().list()
                .setQ(DriveHubPaths.folderQuery(ROOT_FOLDER, "root"))
                .setFields(FIELDS)
                .execute().files.firstOrNull()?.id
        }.onFailure { Timber.w(it, "$TAG: root lookup failed") }.getOrNull()
        val id = found ?: if (createMissing) createFolder(ROOT_FOLDER, "root") else null
        if (id != null) folderIds[""] = id
        return id
    }

    /**
     * Resolve [dirSegments] to a folder id, walking (and caching) prefix by prefix.
     * [createMissing] mirrors the seam's split personality: reads must not scribble folders into
     * a person's Drive just for asking, writes need the ancestry to exist.
     */
    private fun folderIdFor(dirSegments: List<String>, createMissing: Boolean): String? {
        var parentId = rootId(createMissing) ?: return null
        var prefix = ""
        for (segment in dirSegments) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            val cached = folderIds[prefix]
            if (cached != null) {
                parentId = cached
                continue
            }
            val found = runCatching {
                drive.files().list()
                    .setQ(DriveHubPaths.folderQuery(segment, parentId))
                    .setFields(FIELDS)
                    .execute().files.firstOrNull()?.id
            }.onFailure {
                Timber.w(it, "$TAG: folder lookup failed for $prefix")
                folderIds.clear()
            }.getOrNull()
            val id = found ?: if (createMissing) createFolder(segment, parentId) else null
            if (id == null) return null
            folderIds[prefix] = id
            parentId = id
        }
        return parentId
    }

    private fun createFolder(name: String, parentId: String): String? = runCatching {
        val metadata = com.google.api.services.drive.model.File()
            .setName(name)
            .setMimeType(FOLDER_MIME)
            .setParents(listOf(parentId))
        drive.files().create(metadata).setFields("id").execute().id
    }.onFailure { Timber.w(it, "$TAG: createFolder failed for $name") }.getOrNull()

    /**
     * The newest file wearing [path]'s name, or null. Newest-modified-first is load-bearing, not
     * tidiness: [move]'s rename-then-delete-old leaves a brief window of two same-named files,
     * and the newer one is by construction the finished upload.
     */
    private fun findFile(path: String, createFolders: Boolean = false): com.google.api.services.drive.model.File? {
        val (dirSegments, name) = DriveHubPaths.split(path)
        if (name.isEmpty()) return null
        val folderId = folderIdFor(dirSegments, createFolders) ?: return null
        return runCatching {
            drive.files().list()
                .setQ(DriveHubPaths.fileQuery(name, folderId))
                .setOrderBy("modifiedTime desc")
                .setFields(FIELDS)
                .execute().files.firstOrNull()
        }.onFailure { Timber.w(it, "$TAG: file lookup failed for $path") }.getOrNull()
    }

    // ── The seam ──────────────────────────────────────────────────────────────────────────────

    override fun get(path: String): ByteArray? {
        val file = findFile(path) ?: return null
        return runCatching {
            val out = ByteArrayOutputStream()
            drive.files().get(file.id).executeMediaAndDownloadTo(out)
            out.toByteArray()
        }.onFailure { Timber.w(it, "$TAG: get failed for $path") }.getOrNull()
    }

    override fun getStream(path: String, sink: (InputStream) -> Unit): Boolean {
        val file = findFile(path) ?: return false
        return runCatching {
            drive.files().get(file.id).executeMedia().content.use(sink)
            true
        }.onFailure {
            // Broader than IO on purpose, exactly as the WebDAV side: the sink may throw its own
            // verification failure (wrong hash, short landing) to abort, and that must read as
            // "download failed", never crash the sync pass.
            Timber.w(it, "$TAG: getStream failed for $path")
        }.getOrDefault(false)
    }

    override fun putBytes(path: String, bytes: ByteArray, contentType: String): Boolean =
        putContent(path, ByteArrayContent(contentType, bytes))

    override fun putFile(path: String, file: File): Boolean =
        // FileContent + the default media uploader = the resumable protocol, chunked from disk —
        // never the whole book in RAM. See the class doc.
        putContent(path, FileContent("application/octet-stream", file))

    private fun putContent(path: String, content: com.google.api.client.http.AbstractInputStreamContent): Boolean {
        val (dirSegments, name) = DriveHubPaths.split(path)
        if (name.isEmpty()) return false
        val folderId = folderIdFor(dirSegments, createMissing = true) ?: return false
        return runCatching {
            val existing = findFile(path)
            if (existing == null) {
                val metadata = com.google.api.services.drive.model.File()
                    .setName(name)
                    .setParents(listOf(folderId))
                drive.files().create(metadata, content).setFields("id").execute()
            } else {
                // Overwrite-in-place keeps the file's id (and any shares on it) stable — Drive's
                // content swap commits atomically server-side, so a concurrent reader gets old
                // bytes or new bytes, never a splice.
                drive.files().update(existing.id, null, content).execute()
            }
            true
        }.onFailure { Timber.w(it, "$TAG: put failed for $path") }.getOrDefault(false)
    }

    override fun ensureFolder(dirPath: String): Boolean {
        val segments = dirPath.split('/').filter { it.isNotBlank() }
        return folderIdFor(segments, createMissing = true) != null
    }

    override fun move(fromPath: String, toPath: String): Boolean {
        val from = findFile(fromPath) ?: return false
        val (toSegments, toName) = DriveHubPaths.split(toPath)
        if (toName.isEmpty()) return false
        val toFolderId = folderIdFor(toSegments, createMissing = true) ?: return false
        val old = findFile(toPath)
        return runCatching {
            val (fromSegments, _) = DriveHubPaths.split(fromPath)
            val request = drive.files()
                .update(from.id, com.google.api.services.drive.model.File().setName(toName))
            if (fromSegments != toSegments) {
                val fromFolderId = folderIdFor(fromSegments, createMissing = false)
                request.addParents = toFolderId
                if (fromFolderId != null) request.removeParents = fromFolderId
            }
            request.setFields("id").execute()
            // Overwrite: T, Drive-style — the rename IS the commit, and the stale earlier copy
            // under the final name is exactly what it should replace. Deleted AFTER the rename so
            // the name always resolves to something whole; the brief two-files window resolves
            // newest-first in [findFile].
            if (old != null && old.id != from.id) {
                runCatching { drive.files().delete(old.id).execute() }
            }
            true
        }.onFailure { Timber.w(it, "$TAG: move failed $fromPath → $toPath") }.getOrDefault(false)
    }

    override fun delete(path: String): Boolean {
        val file = findFile(path) ?: return true   // already gone is done, as on the WebDAV side
        return runCatching {
            drive.files().delete(file.id).execute()
            true
        }.onFailure { Timber.w(it, "$TAG: delete failed for $path") }.getOrDefault(false)
    }

    override fun reachable(): Boolean = runCatching {
        // The Depth:0 PROPFIND of the Drive world: one cheap authenticated round trip that lists
        // nothing and proves reach + auth. about.get with a single field is the smallest ask the
        // API has.
        drive.about().get().setFields("user").execute()
        true
    }.onFailure { Timber.w(it, "$TAG: reachability probe failed") }.getOrDefault(false)
}
