package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass
import java.util.UUID

/**
 * A rolodex contact — the CRM's core record. Global (not per-day): contacts live in a single
 * synced sidecar file (`contacts/contacts.json`), id-union merged, on the same Ultrabridge WebDAV
 * credential as the day tree. Wire-compatible 1:1 with the iOS `Contact` (Sources/LedgerCore).
 *
 * Flat, string-and-millis fields with defaults so the Moshi codec tolerates missing keys and old /
 * cross-platform JSON still decodes — same convention as [LedgerItem] and [ImageElement].
 *
 * @author MJH
 */
@JsonClass(generateAdapter = true)
data class Contact(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var phone: String = "",
    var email: String = "",
    var org: String = "",
    var bio: String = "",
    // Freeform for now (e.g. "Mar 4" / "03-04"); later parsed into a recurring calendar event.
    var birthday: String = "",
    // Inline base64 PNG avatar ("" = none). Resolved by MediaSource.AvatarOf when a face is dropped
    // onto a page/letter, so editing it here updates every placement.
    var avatarData: String = "",
    // Read-through from FluentCRM (status/lists live there); used for the rolodex header + filtering.
    var tags: List<String> = emptyList(),
    var updated: Long = System.currentTimeMillis(),
    // Delete tombstone (0 = live; millis when deleted) so an id-union merge keeps deletes across devices.
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L
}
