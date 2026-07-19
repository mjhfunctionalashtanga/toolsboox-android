package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass
import java.util.UUID

/**
 * One saved clip-art asset in the personal Clippings library — a monochrome sticker / line-art the
 * user altered once and can reuse on any page or gram. Global (not per-day): clippings live in one
 * synced sidecar file (`clippings/clippings.json`), id-union merged. Wire-compatible with iOS `Clipping`.
 */
@JsonClass(generateAdapter = true)
data class Clipping(
    var id: String = UUID.randomUUID().toString(),
    // Inline base64 PNG.
    var data: String = "",
    var label: String = "",
    var created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
    // Delete tombstone (0 = live) so an id-union merge keeps deletes across devices.
    var deletedAt: Long = 0L,
    // LINEAGE — a clipping keeps its identity through the library and back out. The gram's
    // content-address (gramId or md5 at save time) so "Where used" still groups placements made
    // FROM the library; the source link/label so "Go to source" still works after placing.
    // New fields with defaults → wire-compatible with older files and the iOS mirror.
    var gramId: String = "",
    var sourceLink: String = "",
    var sourceLabel: String = ""
) {
    val isDeleted: Boolean get() = deletedAt > 0L
}
