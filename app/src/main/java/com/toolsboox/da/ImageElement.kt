package com.toolsboox.da

import com.squareup.moshi.JsonClass
import java.util.UUID

/**
 * Image element data class for on-canvas pasted/inserted images.
 *
 * Bytes are stored inline as a base64-encoded PNG (downscaled on import) so the element
 * round-trips through exactly the same JSON save + Google Drive sync as strokes and text —
 * no separate files, no codec/sync changes. Geometry is canvas-space (1404x1872), matching
 * [Stroke] and [TextElement].
 *
 * @author MJH
 */
@JsonClass(generateAdapter = true)
data class ImageElement(
    var elementId: UUID = UUID.randomUUID(),
    var timestamp: Long = System.currentTimeMillis(),
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var data: String,
    // Which note page this image belongs to (notePage key, e.g. "pickings"/"0"/"default").
    // Kept as a flat list on CalendarDay; the fragment filters by page so images don't leak
    // across pages. New field with a default → backward-compatible with existing JSON.
    var page: String = "",
    // Where this gram came from, so tapping it can jump back to the origin. Either an http(s) URL
    // (a feed article / book web source) or a ledger ref "ledger://<yyyy-MM-dd>/<pageKey>" for a card
    // grammed off another ledger page. [sourceLabel] is the human name shown in the jump prompt.
    // New fields with defaults → backward-compatible with existing JSON.
    var sourceLink: String = "",
    var sourceLabel: String = "",
    // Rotation in degrees, clockwise, about the element's centre. Applied at render and
    // carried through the id-keyed merge like every other field. New field with a default
    // → backward-compatible with existing JSON (old images decode as un-rotated).
    var rotation: Float = 0f,
    // Layer order within a page: higher draws on top. New field with a default →
    // backward-compatible; existing images (all z=0) keep their insertion order.
    var z: Int = 0,
    // Contact this gram/card is linked to (null = none) — bidirectional CRM linking, so the contact's
    // page can surface it. New field with a default → backward-compatible. Mirrors iOS.
    var contactId: String? = null,
    // Content-addressed lineage id for "where used" (null/"" = derive from data). Seeded from the
    // pre-edit content hash on the first crop/transform and preserved after, so an edited variant
    // still groups with its original. Match key = gramId if set, else md5(data). Mirrors iOS.
    var gramId: String? = null,
    // ---- A/V grams -----------------------------------------------------------------------------
    // An audio or video gram is an ImageElement whose [data] is its POSTER FRAME (video still /
    // audio waveform), plus a pointer to the sounding part below. That way every surface that
    // already draws grams — pickings, timeline, kanban, PDF export — keeps working unchanged, and a
    // client that predates these fields renders the still instead of breaking. Nothing to migrate.
    //
    // "" or "image" = a plain image gram (the default). "audio" / "video" otherwise. Deliberately a
    // String and not an enum: an older build reading a newer day file gets a value it can ignore
    // rather than a decode failure, which is what keeps the two platforms free to land out of step.
    var mediaKind: String = "",
    // Id of the [Attachment] in CalendarDay.avGrams holding the local blob ("" = none on this device).
    var attachmentId: String = "",
    // Remote copy (R2), so a device that never held the blob can still play it ("" = local only).
    var mediaUrl: String = "",
    // Length in milliseconds, so the player chrome can show 0:23 without opening the file (0 = unknown).
    var durationMs: Int = 0,
    // Creator's choice, both optional: a title, and a date to show instead of the page's own day
    // (yyyy-MM-dd; "" = just use the day it sits on).
    var mediaTitle: String = "",
    var mediaDate: String = "",
    // The words a rendered TEXT card was drawn from. A card is a baked PNG, so its text is pixels
    // and can't be edited — unless it remembers what it said. When this is set, the card offers
    // "Edit words…", which re-renders the face from the new text. Empty for photos and grams that
    // have no text of their own. New field with a default → older readers and iOS ignore it.
    var cardText: String = "",
    // The feed a gram was clipped from — the publication, not the article (which is sourceLink).
    // Lets a feed gram offer "Go to feed", opening Feed Ledger filtered to that feed, so a clipping
    // sits one tap from the others from the same place. New field with a default.
    var sourceFeed: String = ""
)
