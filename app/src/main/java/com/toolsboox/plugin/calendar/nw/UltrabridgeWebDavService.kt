package com.toolsboox.plugin.calendar.nw

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV client for uploading PDF backups to an Ultrabridge-compatible server.
 *
 * Uses OkHttp HTTP PUT with Basic auth to write files. The server is expected
 * to accept standard WebDAV PUT requests (e.g. Nextcloud, ownCloud, any
 * WebDAV-capable endpoint).
 *
 * @param baseUrl the WebDAV base URL (e.g. "https://cloud.example.com/remote.php/dav/files/user")
 * @param username the WebDAV username
 * @param password the WebDAV password
 */
class UltrabridgeWebDavService(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    companion object {
        private const val TAG = "UltrabridgeWebDav"
        private val PDF_MEDIA_TYPE = "application/pdf".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // <d:href> and <d:getlastmodified> localnames, ignoring the (varying) namespace prefix.
        private val HREF_REGEX = Regex("<[a-zA-Z0-9]*:?href>\\s*(.*?)\\s*</[a-zA-Z0-9]*:?href>", RegexOption.IGNORE_CASE)
        private val LASTMOD_REGEX = Regex("<[a-zA-Z0-9]*:?getlastmodified>\\s*(.*?)\\s*</[a-zA-Z0-9]*:?getlastmodified>", RegexOption.IGNORE_CASE)
        private val RESPONSE_REGEX = Regex("<[a-zA-Z0-9]*:?response[ >].*?</[a-zA-Z0-9]*:?response>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    /**
     * A file entry discovered on the WebDAV server via PROPFIND.
     *
     * @param remotePath the path relative to [baseUrl] (e.g. "calendar/2026/05/day-2026-05-28-v2.json")
     * @param lastModified the server's last-modified time in epoch millis (0 if unknown)
     */
    data class RemoteEntry(val remotePath: String, val lastModified: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30_000, TimeUnit.MILLISECONDS)
        .writeTimeout(60_000, TimeUnit.MILLISECONDS)
        .readTimeout(30_000, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Upload a file to the WebDAV server at the given remote path.
     *
     * @param file the local file to upload
     * @param remotePath the path relative to [baseUrl] (e.g. "ToolsForBoox/Day-2026-05.pdf")
     * @param mediaType the content type. Defaults to application/pdf — the original caller here is
     *   the PDF backup and every existing call site relies on the default; the library hub passes
     *   octet-stream because a book is whatever it is and the server stores bytes either way.
     * @return true if the upload succeeded (HTTP 2xx), false otherwise
     */
    fun upload(file: File, remotePath: String, mediaType: MediaType = PDF_MEDIA_TYPE): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = remotePath.trimStart('/')
        val url = "$normalizedBase/$normalizedPath"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("Overwrite", "T")
            .put(file.asRequestBody(mediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Timber.i("$TAG: Uploaded $remotePath (${response.code})")
                    true
                } else {
                    Timber.w("$TAG: Upload failed for $remotePath: ${response.code} ${response.message}")
                    false
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error uploading $remotePath")
            false
        }
    }

    /**
     * Ensure a remote directory exists by issuing a MKCOL request.
     * Ignores 405 (already exists) and 301 (redirect, already exists on some servers).
     *
     * @param remoteDirPath the directory path relative to [baseUrl]
     * @return true if the directory exists or was created
     */
    fun ensureDirectory(remoteDirPath: String): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = remoteDirPath.trimEnd('/') + "/"
        val url = "$normalizedBase/$normalizedPath"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .method("MKCOL", null)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful || response.code == 405 || response.code == 301
                if (!ok) {
                    Timber.w("$TAG: MKCOL failed for $remoteDirPath: ${response.code}")
                }
                ok
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error creating directory $remoteDirPath")
            false
        }
    }

    /**
     * Upload an in-memory byte payload (e.g. a calendar day JSON) to the WebDAV server.
     *
     * @param bytes the payload to write
     * @param remotePath the path relative to [baseUrl] (e.g. "calendar/2026/05/day-2026-05-28-v2.json")
     * @param mediaType the content type (defaults to application/json)
     * @return true if the upload succeeded (HTTP 2xx), false otherwise
     */
    fun uploadBytes(bytes: ByteArray, remotePath: String, mediaType: MediaType = JSON_MEDIA_TYPE): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = remotePath.trimStart('/')
        val url = "$normalizedBase/$normalizedPath"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("Overwrite", "T")
            .put(bytes.toRequestBody(mediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Timber.i("$TAG: Uploaded $remotePath (${response.code})")
                    true
                } else {
                    Timber.w("$TAG: Upload failed for $remotePath: ${response.code} ${response.message}")
                    false
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error uploading $remotePath")
            false
        }
    }

    /**
     * Download a file from the WebDAV server.
     *
     * @param remotePath the path relative to [baseUrl]
     * @return the file bytes, or null on 4xx/5xx or network error
     */
    fun download(remotePath: String): ByteArray? {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = remotePath.trimStart('/')
        val url = "$normalizedBase/$normalizedPath"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.bytes()
                    // A short read is the quiet way a good file becomes a corrupt one. The write
                    // downstream is atomic, so truncated bytes get installed *completely* — a whole
                    // file containing half a day. Content-Length is the only thing that can tell
                    // us, and it costs nothing to check. (-1 means chunked/unknown: nothing to
                    // compare against, so let it through and let the structural check catch it.)
                    val declared = response.body?.contentLength() ?: -1L
                    if (body != null && declared >= 0 && body.size.toLong() != declared) {
                        Timber.w("$TAG: Short read for $remotePath: got ${body.size} of $declared bytes — discarding")
                        return@use null
                    }
                    body
                } else {
                    Timber.w("$TAG: Download failed for $remotePath: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error downloading $remotePath")
            null
        }
    }

    /**
     * Download a file as a stream, handing the open body to [sink].
     *
     * [download] buffers the whole body — right for a day JSON, wrong for a book: the library hub
     * moves files up to (and past) 100MB on devices with little memory to spare, so the bytes must
     * go socket → disk without ever being whole in RAM. The short-read check that [download] does
     * against Content-Length cannot be done here (the bytes are gone by the time we could count
     * them cheaply), so callers verify what landed against the size/hash they already know from
     * the manifest — a strictly stronger check than a length header anyway.
     *
     * @return true when the server answered 2xx and [sink] returned without throwing
     */
    fun downloadTo(remotePath: String, sink: (java.io.InputStream) -> Unit): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = remotePath.trimStart('/')
        val url = "$normalizedBase/$normalizedPath"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: Streaming download failed for $remotePath: ${response.code} ${response.message}")
                    return false
                }
                val body = response.body ?: return false
                body.byteStream().use(sink)
                true
            }
        } catch (e: Exception) {
            // Broader than IOException on purpose: the sink may throw its own verification
            // failure (wrong hash, wrong size) to abort the landing, and that must read as
            // "download failed", not crash the sync pass.
            Timber.w(e, "$TAG: Error streaming $remotePath")
            false
        }
    }

    /**
     * MOVE a file server-side — the WebDAV rename.
     *
     * This is what makes temp-then-rename possible on the REMOTE side: the library hub uploads a
     * book to a `.part` name and MOVEs it into place, so a fetch that races an interrupted upload
     * can never stream down half a book under the real name. Overwrite: T because the rename IS
     * the commit — a stale earlier copy under the final name is exactly what it should replace.
     *
     * The Destination header must be an absolute URL on the same host (RFC 4918 §9.9). Stock
     * Apache mod_dav (dav.mjh.yoga) supports MOVE out of the box; a server that doesn't answers
     * 405/501 and the caller falls back to a plain direct PUT — same result, briefly less atomic.
     */
    fun move(fromPath: String, toPath: String): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val fromUrl = "$normalizedBase/${fromPath.trimStart('/')}"
        val toUrl = "$normalizedBase/${toPath.trimStart('/')}"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(fromUrl)
            .header("Authorization", credential)
            .header("Destination", toUrl)
            .header("Overwrite", "T")
            .method("MOVE", null)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: MOVE failed $fromPath → $toPath: ${response.code} ${response.message}")
                }
                response.isSuccessful
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error moving $fromPath")
            false
        }
    }

    /**
     * DELETE a remote file. The library hub uses it only to sweep its own orphaned `.part`
     * uploads — never a book under its real name; tombstones, not DELETEs, are how a book leaves
     * the library, and even a tombstone never deletes another device's file.
     */
    fun delete(remotePath: String): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val url = "$normalizedBase/${remotePath.trimStart('/')}"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .delete()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // 404 counts as done: the thing we wanted gone is gone.
                response.isSuccessful || response.code == 404
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error deleting $remotePath")
            false
        }
    }

    /**
     * List every file beneath a remote collection, recursively.
     *
     * Tries `Depth: infinity` first, because where it is allowed the whole tree costs one round
     * trip. Where it is not, falls back to walking the tree a collection at a time with `Depth: 1`.
     *
     * The fallback is not a nicety — it is the normal path. Apache's mod_dav ships
     * `DavDepthInfinity Off` and answers an infinite-depth PROPFIND with **403 Forbidden**, and
     * bytemark/webdav (what dav.mjh.yoga runs) is stock Apache. So this method returned null on
     * every call, [CalendarWebDavSyncService.sync] correctly refused to treat "listing failed" as
     * "server is empty" and aborted the pass, and the day-JSON mirror silently did nothing at all
     * for three days while the sidecar surfaces — which use plain GET/PUT and no PROPFIND — kept
     * syncing perfectly. That mix is exactly what "they don't sync together *fully*" looks like
     * from the outside.
     *
     * Walking costs one request per collection (~40 for a three-year day tree, ~0.4s each), which
     * is cheap enough for a periodic pass and, unlike `DavDepthInfinity On`, needs nothing of the
     * server. Fixing it here rather than in Apache also means the app works against any WebDAV
     * endpoint out of the box.
     *
     * Hrefs are resolved back to paths relative to [baseUrl]. Entries whose href cannot be
     * anchored under the requested collection are skipped. Collections are omitted from the
     * result — only concrete files are returned.
     *
     * @param remoteDirPath the collection path relative to [baseUrl] (e.g. "calendar/")
     * @return the discovered file entries, or null on error — callers MUST distinguish
     *   "listing failed" from "genuinely empty": treating a failed PROPFIND as an empty
     *   server made sync classify every local file as local-only and blind-push over
     *   remote edits (then advance the watermark past them).
     */
    fun propfind(remoteDirPath: String): List<RemoteEntry>? {
        (propfindOnce(remoteDirPath, "infinity") as? PropfindResult.Ok)?.let { return it.listing.files }
        Timber.i("$TAG: Depth:infinity unavailable for $remoteDirPath; walking with Depth:1")
        return walk(remoteDirPath)
    }

    /**
     * Depth-1 recursive walk. A missing collection (404) contributes nothing rather than failing
     * the walk — a year folder can exist locally and not yet remotely. Any OTHER failure fails the
     * whole walk (null), because a partial listing read as complete is the blind-push hazard the
     * caller's null-check exists to prevent.
     *
     * @param depthLeft belt-and-braces against a server that reports a collection as its own child
     */
    private fun walk(remoteDirPath: String, depthLeft: Int = 8): List<RemoteEntry>? {
        if (depthLeft <= 0) {
            Timber.w("$TAG: walk depth limit reached at $remoteDirPath")
            return emptyList()
        }
        val listing = when (val r = propfindOnce(remoteDirPath, "1")) {
            is PropfindResult.Ok -> r.listing
            PropfindResult.Missing -> return emptyList()
            PropfindResult.Failed -> return null
        }
        val files = listing.files.toMutableList()
        for (child in listing.collections) {
            files += walk(child, depthLeft - 1) ?: return null
        }
        return files
    }

    internal data class Listing(val files: List<RemoteEntry>, val collections: List<String>)

    /**
     * A PROPFIND outcome. "No such collection" is kept distinct from "failed" so the walk can treat
     * an absent folder as empty while still refusing to mistake a real error for an empty server.
     */
    private sealed interface PropfindResult {
        data class Ok(val listing: Listing) : PropfindResult
        data object Missing : PropfindResult
        data object Failed : PropfindResult
    }

    /**
     * ONE cheap round trip that answers only "can this device reach and authenticate against this
     * server", with no listing and no recursion — `Depth: 0` on the base collection itself.
     *
     * It exists because a surface with an empty list has a question the sidecar stores cannot
     * answer for it. [download] returns null for a missing file and for a dead network alike, and
     * [upload]/[uploadBytes] return false for a 403 on one path as readily as for an unplugged
     * router — so "I got nothing back" is not evidence of anything, and a surface that treated it
     * as evidence would tell a man with a working sync that his sync was broken. A PROPFIND
     * distinguishes: [PropfindResult.Failed] is a genuine failure to reach or authenticate, while
     * [PropfindResult.Missing] (404) means the server answered and the collection simply isn't
     * there, which is a reachable server.
     *
     * `Depth: 0` because nothing here wants the contents. [propfind]'s Depth-1 walk costs one
     * request per collection — forty for a three-year day tree — and asking "are you there" must
     * not cost what asking "what have you got" costs, or empty states will stop asking.
     */
    fun reachable(): Boolean = propfindOnce("", "0") !is PropfindResult.Failed

    /** One PROPFIND at an explicit depth. */
    private fun propfindOnce(remoteDirPath: String, depth: String): PropfindResult {
        val normalizedBase = baseUrl.trimEnd('/')
        val anchor = remoteDirPath.trim('/')
        // An empty anchor means the base collection itself ([reachable]). Left to the general form
        // it would build "…/dav//" — a double slash some servers 404 and others 301, which would
        // read as unreachable on exactly the devices this check exists to reassure.
        val url = if (anchor.isEmpty()) "$normalizedBase/" else "$normalizedBase/$anchor/"

        val credential = Credentials.basic(username, password)
        val body = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/></d:prop></d:propfind>"
            ).toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("Depth", depth)
            .method("PROPFIND", body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: PROPFIND(Depth:$depth) failed for $remoteDirPath: ${response.code} ${response.message}")
                    return if (response.code == 404) PropfindResult.Missing else PropfindResult.Failed
                }
                PropfindResult.Ok(parseListing(response.body?.string() ?: "", anchor))
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error listing $remoteDirPath")
            PropfindResult.Failed
        }
    }

    /**
     * Parse a WebDAV multistatus XML body into files and sub-collections, anchoring each href to a
     * path relative to [baseUrl]. The href is matched from the first occurrence of "<anchor>/"
     * so it works whether the server returns absolute paths, full URLs, or (percent) encoded ones.
     *
     * Sub-collections are returned separately so the Depth-1 walk can recurse into them. The
     * collection's own entry — every PROPFIND reports the requested collection as the first
     * response — is dropped, since recursing into it would never terminate.
     */
    internal fun parseListing(xml: String, anchor: String): Listing {
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val files = mutableListOf<RemoteEntry>()
        val collections = mutableListOf<String>()
        val marker = "$anchor/"

        for (block in RESPONSE_REGEX.findAll(xml)) {
            val chunk = block.value
            val rawHref = HREF_REGEX.find(chunk)?.groupValues?.get(1)?.trim() ?: continue
            val isCollection = rawHref.endsWith("/")

            val href = try {
                URLDecoder.decode(rawHref, "UTF-8")
            } catch (e: Exception) {
                rawHref
            }

            if (isCollection) {
                // Anchor a child collection by its parent marker, then keep only a direct child:
                // "calendar/2026/" under anchor "calendar" yields "2026/", which has one segment.
                val idx = href.indexOf(marker)
                if (idx < 0) continue
                val rest = href.substring(idx + marker.length).trim('/')
                if (rest.isEmpty()) continue                    // the collection itself
                if (rest.contains('/')) continue                // a grandchild (Depth>1 response)
                collections.add("$anchor/$rest")
                continue
            }

            val idx = href.indexOf(marker)
            if (idx < 0) continue
            val remotePath = href.substring(idx)

            val lastModified = LASTMOD_REGEX.find(chunk)?.groupValues?.get(1)?.trim()?.let {
                try {
                    dateFormat.parse(it)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            } ?: 0L

            files.add(RemoteEntry(remotePath, lastModified))
        }

        return Listing(files, collections)
    }
}
