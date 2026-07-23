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
     * @return true if the upload succeeded (HTTP 2xx), false otherwise
     */
    fun upload(file: File, remotePath: String): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = remotePath.trimStart('/')
        val url = "$normalizedBase/$normalizedPath"

        val credential = Credentials.basic(username, password)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("Overwrite", "T")
            .put(file.asRequestBody(PDF_MEDIA_TYPE))
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
     * List every file under a remote collection via a WebDAV PROPFIND (Depth: infinity).
     *
     * Hrefs are resolved back to paths relative to [baseUrl]. Entries whose href cannot be
     * anchored under the requested collection are skipped. Collections (hrefs ending in "/")
     * are omitted — only concrete files are returned.
     *
     * @param remoteDirPath the collection path relative to [baseUrl] (e.g. "calendar/")
     * @return the discovered file entries, or null on error — callers MUST distinguish
     *   "listing failed" from "genuinely empty": treating a failed PROPFIND as an empty
     *   server made sync classify every local file as local-only and blind-push over
     *   remote edits (then advance the watermark past them).
     */
    fun propfind(remoteDirPath: String): List<RemoteEntry>? {
        val normalizedBase = baseUrl.trimEnd('/')
        val anchor = remoteDirPath.trim('/')
        val url = "$normalizedBase/$anchor/"

        val credential = Credentials.basic(username, password)
        val body = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/></d:prop></d:propfind>"
            ).toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("Depth", "infinity")
            .method("PROPFIND", body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: PROPFIND failed for $remoteDirPath: ${response.code} ${response.message}")
                    return null
                }
                parsePropfind(response.body?.string() ?: "", anchor)
            }
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Network error listing $remoteDirPath")
            null
        }
    }

    /**
     * Parse a WebDAV multistatus XML body into [RemoteEntry] items, anchoring each href to a
     * path relative to [baseUrl]. The href is matched from the first occurrence of "<anchor>/"
     * so it works whether the server returns absolute paths, full URLs, or (percent) encoded ones.
     */
    private fun parsePropfind(xml: String, anchor: String): List<RemoteEntry> {
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val entries = mutableListOf<RemoteEntry>()
        val marker = "$anchor/"

        for (block in RESPONSE_REGEX.findAll(xml)) {
            val chunk = block.value
            val rawHref = HREF_REGEX.find(chunk)?.groupValues?.get(1)?.trim() ?: continue
            if (rawHref.endsWith("/")) continue // a collection, not a file

            val href = try {
                URLDecoder.decode(rawHref, "UTF-8")
            } catch (e: Exception) {
                rawHref
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

            entries.add(RemoteEntry(remotePath, lastModified))
        }

        return entries
    }
}
