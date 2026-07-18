package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass
import java.util.UUID

/**
 * A single piece of correspondence exchanged with a rolodex [Contact] — a letter, email, call,
 * meeting, gift or note. The CRM's interaction log: contacts live in `contacts/contacts.json`,
 * their correspondence in one global synced sidecar (`correspondence/correspondence.json`),
 * id-union merged over the same Ultrabridge WebDAV credential as the day tree. Wire-compatible 1:1
 * with the iOS CorrespondenceEntry (Sources/LedgerCore).
 *
 * Flat, string-and-millis fields with defaults so the Moshi codec tolerates missing keys and old /
 * cross-platform JSON still decodes — same convention as [Contact] and [LedgerItem].
 *
 * @author MJH
 */
@JsonClass(generateAdapter = true)
data class CorrespondenceEntry(
    var id: String = UUID.randomUUID().toString(),
    // The rolodex [Contact.id] this belongs to. Never blank in practice — set at creation.
    var contactId: String = "",
    // When the exchange happened (millis). Defaults to now; user-editable via a date picker.
    var at: Long = System.currentTimeMillis(),
    // One of [Kind.wire]: "letter" | "email" | "call" | "meeting" | "gift" | "note".
    var kind: String = Kind.NOTE.wire,
    // "sent" (outbound, from me) | "received" (inbound) | "" (n/a — e.g. a meeting or a note).
    var direction: String = "",
    var subject: String = "",
    var body: String = "",
    var updated: Long = System.currentTimeMillis(),
    // Delete tombstone (0 = live; millis when deleted) so an id-union merge keeps deletes across devices.
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L

    /** Type of exchange. Persisted by [wire] (stable string) so JSON stays language-neutral. */
    enum class Kind(val wire: String, val glyph: String, val label: String) {
        LETTER("letter", "✉️", "Letter"),
        EMAIL("email", "📧", "Email"),
        CALL("call", "📞", "Call"),
        MEETING("meeting", "🤝", "Meeting"),
        GIFT("gift", "🎁", "Gift"),
        NOTE("note", "📝", "Note");

        companion object {
            fun of(wire: String): Kind = values().firstOrNull { it.wire == wire } ?: NOTE
        }
    }

    /** Directionality of a [kind]. "" means not applicable (meetings, standalone notes). */
    enum class Direction(val wire: String, val label: String) {
        NONE("", "—"),
        SENT("sent", "Sent"),
        RECEIVED("received", "Received");

        companion object {
            fun of(wire: String): Direction = values().firstOrNull { it.wire == wire } ?: NONE
        }
    }
}
