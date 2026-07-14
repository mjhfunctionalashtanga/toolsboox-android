package com.toolsboox.da

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.Date

/**
 * A media file attached to an annotation or A/V Gram. Wire-compatible with the
 * iOS `LedgerCore.Attachment` (same field names + `kind` raw values), so the
 * shared day JSON round-trips 1:1 between the Boox Ledger and the iPad Ledger.
 *
 * The bytes live in the app's attachments directory (blob sync lands later);
 * only this lightweight reference rides in the day JSON.
 */
@JsonClass(generateAdapter = true)
data class Attachment(
    val id: String,
    val kind: Kind,
    /** Filename within the app's attachments directory. */
    val filename: String,
    /** Duration in seconds (audio/video only). */
    val duration: Double? = null,
    /** When it was captured. */
    val date: Date? = null
) {
    enum class Kind {
        @Json(name = "photo") PHOTO,
        @Json(name = "audio") AUDIO,
        @Json(name = "video") VIDEO
    }
}
