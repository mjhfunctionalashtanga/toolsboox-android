package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.Attachment
import java.io.File

/**
 * WHICH OF A LINE'S ATTACHMENTS A PICK'S FACE WEARS — the rules both apps read a line by (iOS twin:
 * LedgerCore `PickFaceAdds`, in PickFaceLayout.swift). Pure (no android.graphics), so it is tested
 * on the JVM; [PickFace] decodes what this picks.
 */
object PickFaceAdds {

    /** Handwriting rides the line as a PHOTO attachment named ink-… (both apps). Any other picture —
     *  a PNG photo included — is a photo. */
    const val INK_PREFIX = "ink-"

    fun isInk(a: Attachment) = a.kind == Attachment.Kind.PHOTO && a.filename.startsWith(INK_PREFIX)

    /** A photo of yours or a video — what goes in the band (or Landscape's column). */
    fun isVisual(a: Attachment) = (a.kind == Attachment.Kind.PHOTO && !isInk(a)) || a.kind == Attachment.Kind.VIDEO

    /**
     * A line's attachments, NEWEST FIRST: by `date`, a tie (or no date) the later one in the line —
     * iOS `PickFaceAdds.newestFirst`, so both apps pick the same tiles, handwriting, chips and media
     * slot after a merge has pooled the line out of time order (review 2026-10-16 #14: this app read
     * the line's order, iOS the dates).
     */
    fun newestFirst(attachments: List<Attachment>): List<Attachment> =
        attachments.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Attachment>> { it.value.date?.time ?: 0L }.thenByDescending { it.index })
            .map { it.value }

    /** The files a face needs on this device to be drawn whole — the newest handwriting and the two
     *  visuals the band (or Landscape's column) shows. iOS `PickFaceAdds.drawn`. */
    fun drawn(attachments: List<Attachment>): List<Attachment> {
        val n = newestFirst(attachments)
        return listOfNotNull(n.firstOrNull { isInk(it) }) + n.filter { isVisual(it) }.take(2)
    }

    /** How long a re-face waits for a file the other device made before drawing without it — past
     *  this the file is taken as lost, so a pick never waits for good. */
    const val WAIT_FOR_FILES_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * The drawn files ([drawn]) not on this device yet that are worth waiting for (review 2026-10-16
     * #3): a face baked without one wins the element merge on both devices, and nothing re-faces
     * when the file lands — so the re-face waits instead (PickAdds' waiting faces, drained after
     * every sync).
     */
    fun missing(dir: File, attachments: List<Attachment>, now: Long = System.currentTimeMillis()): List<Attachment> =
        drawn(attachments).filter { a -> !File(dir, a.filename).exists() && (a.date?.time ?: 0L) > now - WAIT_FOR_FILES_MS }

    /** The one media slot older builds read: a video if there is any, else a voice note — the
     *  newest of that kind ([newestFirst]); null when there is none. */
    fun mediaClip(attachments: List<Attachment>): Attachment? {
        val n = newestFirst(attachments)
        return n.firstOrNull { it.kind == Attachment.Kind.VIDEO } ?: n.firstOrNull { it.kind == Attachment.Kind.AUDIO }
    }
}
