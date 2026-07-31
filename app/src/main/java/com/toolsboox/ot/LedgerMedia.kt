package com.toolsboox.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * The content-addressed media store and the one resolver every reader of a picture goes through
 * (WIRE-MEDIA-BY-REFERENCE.md).
 *
 * Two fields carry base64 image payloads inside the day JSON — `imageElements[].data` and
 * `ledgerItems[].crop` — and they are the whole bloat problem: the 39 MB day that truncated
 * mid-write, the 86 MB one that OOM-killed every launch, the picker whose open menu held ~80 MB
 * of UTF-16 base64. The cure is the pattern `avGrams` was born with: bytes in a file, a name in
 * the JSON. Files live in `media/` (a sibling of `attachments/`), named
 * `<sha256-of-decoded-bytes>.<png|jpg>` — content-addressed, so the same gram on ten boards is
 * ONE file, a truncated download can never be mistaken for the real thing, and sync is pure
 * union with nothing to merge.
 *
 * This object is the READ half (Phase R): nothing here writes a ref, clears a payload, or
 * externalizes anything — it only answers "give me the pixels this element means", wherever
 * they happen to live. Resolution order is pinned by the wire brief:
 *
 *  - [ImageElement.data]: inline `data` first (a build that wrote inline meant it, and every
 *    pre-migration file is inline), then `dataRef` against the media store, then nothing.
 *  - [LedgerItem.crop]: `cropRef` FIRST, then try-base64-decode(`crop`), then `crop` as an
 *    attachments filename (the OCR legacy). The dual-typed `crop` fallback is permanent.
 *
 * A missing blob resolves to null and the caller renders a placeholder or skips — it is NEVER
 * a reason to drop an element, clear a ref, or fail a save or sync. Nothing here throws.
 */
object LedgerMedia {

    /**
     * The one legal shape of a media-store name: 64 lowercase hex chars (the SHA-256 of the
     * DECODED bytes, not of the base64 string), a dot, and the extension `LedgerImageCodec`
     * actually encoded. Validated before any path is built from it — a ref is wire data, and
     * wire data does not get to name arbitrary files.
     */
    private val REF_SHAPE = Regex("^[0-9a-f]{64}\\.(png|jpg)$")

    /** Whether [ref] is even shaped like a media-store name. */
    fun isValidRef(ref: String): Boolean = REF_SHAPE.matches(ref)

    /** SHA-256 of [bytes] as lowercase hex — the media name's stem, and the download verifier. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The local media store: `<documentsRoot>/media/`, a sibling of `attachments/`, created on demand. */
    fun mediaDir(context: Context): File =
        File(LedgerPaths.documentsRoot(context), "media").apply { mkdirs() }

    /** The file [ref] names, or null when the ref fails [isValidRef] — shape first, filesystem second. */
    fun fileFor(context: Context, ref: String): File? =
        if (isValidRef(ref)) File(mediaDir(context), ref) else null

    /**
     * The bytes an element's `data`/`dataRef` pair means: inline [data] if non-empty (decoded),
     * else the media file [ref] names, else null. Null too for a bad ref, a missing file, or a
     * payload that won't decode — the caller's placeholder path, never an exception.
     */
    fun resolveBytes(context: Context, data: String, ref: String): ByteArray? =
        resolveBytes(data, ref) { fileFor(context, it) }

    /**
     * The Context-free core of [resolveBytes], so the resolution order is a plain JVM unit test:
     * [fileFor] maps a shape-valid ref to its file (or null). runCatching is Throwable-wide on
     * purpose — a decode that OOMs is a missing face, not a dead app.
     */
    internal fun resolveBytes(data: String, ref: String, fileFor: (String) -> File?): ByteArray? {
        if (data.isNotBlank()) {
            // The MIME decoder tolerates the line-wrapped base64 older writers produced, and
            // skips (rather than throws on) stray characters — so garbage can "decode" to
            // nothing. Zero bytes are no face either way; normalise them to null. Inline data
            // that fails NEVER falls through to the ref: writers never emit both, so a ref
            // beside non-empty data means nothing.
            return runCatching { java.util.Base64.getMimeDecoder().decode(data) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        if (ref.isNotBlank()) {
            val file = fileFor(ref) ?: return null
            return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * [resolveBytes] re-encoded as base64 for the network seams that speak it today
     * (LedgerWebBridge, the vision-OCR callers): inline [data] is passed through untouched —
     * it already IS the wire form — and a ref's bytes are encoded at the seam. Null when
     * there is nothing to send.
     */
    fun resolveBase64(context: Context, data: String, ref: String): String? {
        if (data.isNotBlank()) return data
        val bytes = resolveBytes(context, "", ref) ?: return null
        return runCatching { java.util.Base64.getEncoder().encodeToString(bytes) }.getOrNull()
    }

    /** [resolveBytes] decoded to a full-resolution bitmap — for the paths that keep the pixels. */
    fun resolveBitmap(context: Context, data: String, ref: String): Bitmap? {
        val bytes = resolveBytes(context, data, ref) ?: return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    /**
     * [resolveBytes] decoded at no more than [maxPx] on its long edge — the bounded decode the
     * memory-floor fixes standardized (see CalendarDayFragment.decodeGramThumb): bounds first,
     * then a power-of-two inSampleSize, so the cost of a face is set by the size it will be
     * SHOWN at, not by whatever the source happened to be. Render paths resolving a ref must
     * come through here rather than regrow the full-resolution allocation the fixes took out.
     */
    fun resolveThumb(
        context: Context, data: String, ref: String, maxPx: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap? = runCatching {
        val bytes = resolveBytes(context, data, ref) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config })
    }.getOrNull()

    /**
     * A ledger item's ink face, through the permanent crop resolution order: [cropRef] first,
     * then try-base64-decode([crop]), then [crop] as an attachments filename. Every arm is
     * validated by actually decoding a bitmap — that is what has always separated "base64 face"
     * from "OCR filename" in the dual-typed [crop], and the ref arm gets the same treatment so
     * a missing or corrupt blob falls through to whatever inline face the item still carries.
     */
    fun resolveCropBitmap(context: Context, crop: String?, cropRef: String?): Bitmap? {
        cropRef?.takeIf { it.isNotBlank() }?.let { ref ->
            runCatching {
                fileFor(context, ref)?.takeIf { it.isFile }?.readBytes()?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }
            }.getOrNull()?.let { return it }
        }
        val inline = crop?.takeIf { it.isNotBlank() } ?: return null
        runCatching {
            val bytes = android.util.Base64.decode(inline, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { return it }
        return runCatching {
            File(LedgerPaths.attachmentsDir(context), inline).takeIf { it.isFile }?.readBytes()?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        }.getOrNull()
    }

    /**
     * [resolveCropBitmap]'s byte-level twin for seams that ship the encoded file (the web
     * bridge's card push): same order, and each arm's bytes are returned only if they decode
     * to a bitmap — bytes that don't render are not a face.
     */
    fun resolveCropBytes(context: Context, crop: String?, cropRef: String?): ByteArray? {
        cropRef?.takeIf { it.isNotBlank() }?.let { ref ->
            runCatching {
                fileFor(context, ref)?.takeIf { it.isFile }?.readBytes()
                    ?.takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }
            }.getOrNull()?.let { return it }
        }
        val inline = crop?.takeIf { it.isNotBlank() } ?: return null
        runCatching {
            android.util.Base64.decode(inline, android.util.Base64.DEFAULT)
                .takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }
        }.getOrNull()?.let { return it }
        return runCatching {
            File(LedgerPaths.attachmentsDir(context), inline).takeIf { it.isFile }?.readBytes()
                ?.takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }
        }.getOrNull()
    }
}
