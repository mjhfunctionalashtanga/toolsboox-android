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
    var page: String = ""
)
