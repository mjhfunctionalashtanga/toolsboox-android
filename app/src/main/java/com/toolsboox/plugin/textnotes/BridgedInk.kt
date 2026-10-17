package com.toolsboox.plugin.textnotes

import android.content.Context
import android.graphics.Bitmap
import com.toolsboox.ot.LedgerMedia
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync

/**
 * Resolve a bridged note's ink ref ("media/<sha256>.<png|jpg>") to pixels.
 *
 * The refs the VPS bridge writes name blobs on the dav media lane — the SAME content-addressed
 * store `imageElements[].dataRef` lives in (WIRE-MEDIA-BY-REFERENCE.md), so the first stop is the
 * local media store, which the calendar sync's media pull keeps unioned with the server. But the
 * text-notes sync pulls ONLY the day's JSON, and a bridged note can arrive on a device whose next
 * media pull hasn't run yet — so a miss falls through to ONE lazy fetch over the sidecar transport
 * (the very server the note itself synced from), verified against the name's own hash and
 * installed into the local media store. That install IS the disk cache: content-addressed, shared
 * with every other reader, and exactly the file the next media pull would have written anyway.
 *
 * Null for a mis-shaped ref, an unconfigured transport, a failed fetch, or bytes that don't hash
 * to their name — the caller skips the picture; a missing blob is never a reason to disturb the
 * note. Blocking on a miss: call on a background thread.
 */
object BridgedInk {

    /** [rawRef] decoded at no more than [maxPx] on its long edge (LedgerMedia's bounded decode —
     *  the size it will be SHOWN at, not whatever the stroke PNG happened to be). */
    fun bitmap(context: Context, rawRef: String, maxPx: Int): Bitmap? {
        val name = rawRef.removePrefix("media/")
        if (!LedgerMedia.isValidRef(name)) return null
        LedgerMedia.resolveThumb(context, "", name, maxPx)?.let { return it }
        val bytes = runCatching { LedgerSidecarSync.transport(context)?.get("media/$name") }
            .getOrNull() ?: return null
        // The name is its own checksum (pullMedia's rule): bytes that don't hash to it are not
        // the file, and must not be installed under a name that vouches for them.
        if (LedgerMedia.sha256Hex(bytes) != name.substringBeforeLast('.')) return null
        LedgerMedia.storeBlob(LedgerMedia.mediaDir(context), bytes, name.substringAfterLast('.'))
        return LedgerMedia.resolveThumb(context, "", name, maxPx)
    }
}
