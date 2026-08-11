package com.toolsboox.plugin.calendar.nw

import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.InputStream

/**
 * THE BACKEND SEAM — one sync logic, two transports (design step D, 2026-08-11).
 *
 * The ruling: "Google Drive is the SECOND BACKEND for users without WebDAV, behind the same seam —
 * one sync logic, two transports, chosen in Settings. Michael's own fleet rides WebDAV." This
 * interface IS that seam. It is cut to exactly what the hub ([com.toolsboox.plugin.reader.ui.LibraryHub])
 * and the sidecar stores (through [LedgerSidecarSync]) actually ask of a remote today — no more:
 *
 *  • GET a file's bytes, whole ([get]) or streamed ([getStream] — books must never be whole in RAM).
 *  • PUT bytes ([putBytes]) or a streamed file ([putFile]).
 *  • [ensureFolder], [move], [delete] — the mechanics of the `.part`-then-rename civility.
 *  • [reachable] — the honest "are you there" for empty states.
 *
 * DELIBERATELY ABSENT: any listing operation. The WebDAV sidecar path does no PROPFIND — every
 * store round-trips a file it already knows the name of — and the interface keeps that shape so a
 * transport never has to promise a directory walk it can't cheaply give. The surfaces that DO list
 * (the day-tree mirror, intake's pull-missing-days) are WebDAV services with their own Drive
 * counterparts already, and they stay on their own machinery.
 *
 * Every method is BLOCKING and quiet on failure (false/null, logged by the implementation) — the
 * contract the WebDAV service has always kept, and the reason a sidecar that can't reach the
 * server never interrupts what you are writing. Callers already run these on the sidecar-sync /
 * library-hub single threads; nothing here may be called on the main thread.
 */
interface HubTransport {

    /** The file's bytes, or null when absent / unreachable / unauthorized — the caller cannot and
     *  must not distinguish (see [LedgerSidecarSync]'s "why an empty list is empty" ledger). */
    fun get(path: String): ByteArray?

    /**
     * Stream the file to [sink] — socket to disk, never whole in RAM. Returns true only when the
     * remote answered AND [sink] returned without throwing; the sink's own verification failure
     * (wrong hash, short landing) reads as "download failed", never as a crash.
     */
    fun getStream(path: String, sink: (InputStream) -> Unit): Boolean

    /** Write [bytes] at [path], overwriting. [contentType] defaults to JSON because every sidecar
     *  is JSON; the title-ink faces pass image/png. */
    fun putBytes(path: String, bytes: ByteArray, contentType: String = "application/json"): Boolean

    /** Stream [file] up to [path] as octet-stream — a book is whatever it is, and the remote
     *  stores bytes either way. Implementations must stream (resumable/chunked where the protocol
     *  offers it), never buffer the file whole. */
    fun putFile(path: String, file: File): Boolean

    /** Make [dirPath] exist. True when it exists or was created; idempotent and cheap to repeat. */
    fun ensureFolder(dirPath: String): Boolean

    /**
     * Server-side rename, overwriting any file already at [toPath] — the commit step of
     * `.part`-then-rename. A transport that cannot rename returns false and the caller falls back
     * to a direct put (same landing, briefly less atomic) — see [putFileCommitted].
     */
    fun move(fromPath: String, toPath: String): Boolean

    /** Remove the file at [path]. "Already gone" counts as done. Used only to sweep our own
     *  orphaned `.part` temps — tombstones, not deletes, are how a book leaves the library. */
    fun delete(path: String): Boolean

    /** ONE cheap round trip answering only "can this device reach and authenticate". */
    fun reachable(): Boolean
}

/**
 * Upload [file] under [dirPath]/[name] so that the FINAL name either doesn't exist yet or is
 * whole — the `.part`-then-rename civility, lifted out of LibraryHub so it is written once and
 * proven transport-blind by test rather than by hope. On a remote without rename the direct put
 * is the fallback (same landing, briefly less atomic) and our temp is swept.
 *
 * Lives on the seam rather than in the hub because the sequence is a property of "how you put a
 * big file somewhere other devices are simultaneously reading from", not of books specifically —
 * the next big-file feature (A/V grams) inherits it by calling it.
 */
fun HubTransport.putFileCommitted(dirPath: String, name: String, file: File): Boolean {
    val prefix = if (dirPath.isEmpty()) "" else "$dirPath/"
    val finalPath = "$prefix$name"
    val partPath = "$prefix.$name.part"
    if (!putFile(partPath, file)) return false
    if (move(partPath, finalPath)) return true
    // No rename on this remote: put the real name directly, sweep our temp.
    val direct = putFile(finalPath, file)
    runCatching { delete(partPath) }
    return direct
}

/**
 * Put [bytes] at [remotePath], creating missing ancestor folders only on failure — the sidecar
 * push shape. Upload first so the steady state stays one round trip; the retry exists because
 * stock Apache dav 409s a PUT whose parent collection was never MKCOLed (the lesson that once
 * cost grid-index/ and friends three silent days — see [LedgerSidecarSync.push]'s history).
 * Extracted here so the fake-transport test can prove the sequence without a server.
 */
internal fun HubTransport.putBytesEnsuringFolders(remotePath: String, bytes: ByteArray): Boolean {
    return putBytes(remotePath, bytes) || run {
        val segments = remotePath.trim('/').split("/").dropLast(1)
        var prefix = ""
        for (segment in segments) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            ensureFolder(prefix)
        }
        segments.isNotEmpty() && putBytes(remotePath, bytes)
    }
}

/**
 * The WebDAV side of the seam: a 1:1 delegation onto [UltrabridgeWebDavService], each method the
 * exact call the hub and the sidecars made before the seam existed — same media types, same
 * defaults, same failure shape. This class must stay boring: any behavior it adds is behavior
 * Michael's fleet didn't have yesterday, and the refactor's whole proof is the 332 tests not
 * noticing it happened.
 */
class WebDavHubTransport(private val svc: UltrabridgeWebDavService) : HubTransport {

    override fun get(path: String): ByteArray? = svc.download(path)

    override fun getStream(path: String, sink: (InputStream) -> Unit): Boolean =
        svc.downloadTo(path, sink)

    override fun putBytes(path: String, bytes: ByteArray, contentType: String): Boolean =
        svc.uploadBytes(bytes, path, contentType.toMediaType())

    override fun putFile(path: String, file: File): Boolean =
        svc.upload(file, path, OCTET)

    override fun ensureFolder(dirPath: String): Boolean = svc.ensureDirectory(dirPath)

    override fun move(fromPath: String, toPath: String): Boolean = svc.move(fromPath, toPath)

    override fun delete(path: String): Boolean = svc.delete(path)

    override fun reachable(): Boolean = svc.reachable()

    companion object {
        /** Octet-stream, as LibraryHub always passed for book bytes — the server stores bytes
         *  either way, and a book is whatever it is. */
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
