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
 * This object holds both halves now. The READ half (Phase R) answers "give me the pixels this
 * element means", wherever they happen to live. The WRITE half (Phase W, [externalizeDay]) runs
 * at exactly one chokepoint — the day-save seam in CalendarDayService.save — and moves
 * over-threshold inline payloads into the store. Resolution order is pinned by the wire brief:
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
     * THIS CONSTANT IS THE PHASE W SWITCH (WIRE-MEDIA-BY-REFERENCE.md, "Phasing — and why it
     * is strict"). While true, the day-save seam externalizes over-threshold inline payloads
     * into refs; flipping it to false and rebuilding is the rollback — writers go back to
     * leaving everything inline, and the permanent read-both rule means nothing already
     * externalized breaks. It must NEVER ship true to a fleet with pre-Phase-R readers: both
     * forks drop unknown JSON fields on re-save, so an old build that opens a ref-bearing day
     * silently deletes the `dataRef` — and the image with it, fleet-wide, via sync. Every
     * syncing device (both forks + the VPS processor) runs Phase R as of 1.06.11-00, which is
     * the only reason this is allowed to be true.
     */
    const val EMIT_MEDIA_REFS = true

    /**
     * The pinned inline threshold: DECODED payloads at or under this many bytes stay inline.
     * Small stickers and shapes cost nothing inline and skip a fetch round-trip on e-ink;
     * photographs and card faces are what bloat. One number, both forks, pinned by the wire.
     */
    const val INLINE_MAX_BYTES = 65536

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

    // ── The WRITE half (Phase W): externalize-on-save ─────────────────────────────────────────
    //
    // No janitor lives here yet, on purpose: local mark-sweep GC (a media file referenced by no
    // local day/board and untouched for ≥30 days may be deleted LOCALLY) is deferred —
    // content-addressing means nothing is deletable for 30 days from first ship anyway, so
    // deferring it costs zero storage today. Remote deletion is out of scope permanently per the
    // wire: the remote is the archive (and the future cold tier).

    /**
     * The extension the bytes honestly are, by magic number: PNG (`\x89PNG`) → "png", JPEG
     * (`\xFF\xD8`) → "jpg", anything else → null. Null means "leave it inline": the media store
     * never externalizes bytes it can't name honestly, because the ref's extension is a promise
     * to every reader (including the VPS processor's MIME handling) about what the file is.
     */
    internal fun extensionByMagic(bytes: ByteArray): String? = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
                && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        else -> null
    }

    /**
     * Write [bytes] into [mediaDir] under their content-addressed name, temp-then-atomic-move
     * like every hardened store, and return the name ONLY when the file provably exists
     * afterwards. Content-addressing makes this idempotent: already-present means done, no
     * write at all. Null on any failure — and the caller keeps the payload inline, because
     * losing pixels to a full disk is not an option; the day file stays the fallback of record
     * until the blob is safely on disk.
     */
    internal fun storeBlob(mediaDir: File, bytes: ByteArray, ext: String): String? {
        val name = sha256Hex(bytes) + "." + ext
        val target = File(mediaDir, name)
        if (target.isFile) return name
        return runCatching {
            mediaDir.mkdirs()
            // Unique temp name: two saves racing on the same content must not truncate each
            // other's temp mid-move. Whoever moves first wins; the loser's move fails and the
            // target check below settles it.
            val temp = File(mediaDir, "$name.${System.nanoTime()}.tmp")
            try {
                temp.writeBytes(bytes)
                try {
                    java.nio.file.Files.move(
                        temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (e: Exception) {
                    java.nio.file.Files.move(
                        temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                temp.delete()
            }
            if (target.isFile) name else null
        }.getOrNull()
    }

    /**
     * The Phase W chokepoint: externalize [day]'s over-threshold inline payloads into
     * [mediaDir], in place, just before serialization. This runs ONLY from the day-save seam
     * (CalendarDayService.save) — writers keep producing inline elements exactly as before,
     * and the save externalizes. That one placement covers new elements (externalized on their
     * first save, which follows placement immediately), transforms (Phase R hygiene re-encodes
     * inline and clears the ref; the next save re-externalizes under the new hash), and the
     * lazy migration of old days (any day being saved anyway sheds its inline payloads — no
     * sweep, no bulk pass; a day untouched forever stays inline forever, and read-both is
     * permanent).
     *
     * Per element: decode; ≤ [INLINE_MAX_BYTES] stays inline; unknown magic stays inline; a
     * failed blob write stays inline (no ref is ever set for bytes that aren't on disk).
     * `elementId` and `timestamp` are never touched — the pixels are identical, and a minted
     * timestamp would make the migrated copy win merges it has no business winning. Before
     * `data` is cleared, an empty `gramId` is backfilled with md5-of-the-base64-STRING via
     * [CryptoUtils.md5Hash] — exactly the match key the contentKey / "where used" derivation
     * computes, so the lineage survives the base64 leaving the file. (gramId stays
     * md5-of-base64; the media NAME is sha256-of-bytes. Two different jobs; never unified.)
     *
     * A `ledgerItems[].crop` externalizes only when it base64-decodes to bytes wearing a
     * known image magic — the dual-typed crop's other meaning is an attachments FILENAME,
     * which must never be externalized or altered; a filename "decodes" to a handful of
     * magic-less bytes and falls out at the size and magic gates. The pass is idempotent:
     * an already-externalized element (empty payload, ref set) is skipped outright, and
     * re-storing existing content writes no new files.
     *
     * Context-free (a plain [File] media dir) so the whole contract is a JVM unit test.
     * Never throws: any per-element surprise leaves that element inline, which is always safe.
     */
    fun externalizeDay(day: com.toolsboox.plugin.calendar.da.v2.CalendarDay, mediaDir: File) {
        if (!EMIT_MEDIA_REFS) return

        for (element in day.imageElements) runCatching {
            // Empty data = already externalized (or faceless) — untouched, which is what makes
            // re-saving a migrated day a no-op instead of field churn.
            if (element.data.isBlank()) return@runCatching
            val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(element.data) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: return@runCatching
            if (bytes.size <= INLINE_MAX_BYTES) return@runCatching
            val ext = extensionByMagic(bytes) ?: return@runCatching
            val name = storeBlob(mediaDir, bytes, ext) ?: return@runCatching
            // Only now — the blob is provably on disk. Backfill the lineage BEFORE the base64
            // leaves the file, then flip the faces: ref set, data emptied ("" stays present as
            // a key on the wire; both decoders hard-require it).
            if (element.gramId.isNullOrBlank()) {
                element.gramId = CryptoUtils.md5Hash(element.data.toByteArray())
            }
            element.dataRef = name
            element.data = ""
        }

        for (index in day.ledgerItems.indices) runCatching {
            val item = day.ledgerItems[index]
            val crop = item.crop?.takeIf { it.isNotBlank() } ?: return@runCatching
            // The try-decode discriminator, same as the read side: only a crop that decodes to
            // actual image bytes is a base64 face. The MIME decoder skips a filename's dots and
            // letters into a few stray bytes, so the magic and size gates are what keep a
            // filename crop untouchable here.
            val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(crop) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: return@runCatching
            if (bytes.size <= INLINE_MAX_BYTES) return@runCatching
            val ext = extensionByMagic(bytes) ?: return@runCatching
            val name = storeBlob(mediaDir, bytes, ext) ?: return@runCatching
            // crop is a val (its dual typing predates mutability), so the externalized item is
            // a copy — same id, same done/stage/board, cropRef set and the payload gone.
            day.ledgerItems[index] = item.copy(crop = null, cropRef = name)
        }
    }
}
