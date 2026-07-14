package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.toolsboox.da.Attachment
import java.util.Date

/**
 * One thing you read / marked on a given day, bucketed onto a [CalendarDay] so the
 * planner can show a per-day reading timeline. Wire-compatible with the iOS
 * `LedgerCore.ReadingEvent`.
 *
 * Two producers write these into the shared day JSON: the **Feed Ledger** emits one
 * when you star an article ([kind] = [Kind.ARTICLE]); the **Book Ledger** emits one
 * per highlight ([kind] = [Kind.BOOK]). A single flat shape covers both — [kind]
 * discriminates, and the book-only fields ([excerpt]/[note]/[location]) stay null
 * for articles.
 */
@JsonClass(generateAdapter = true)
data class ReadingEvent(
    /** Stable dedupe/identity key — the article's id or the book note's id. */
    val id: String,
    val kind: Kind,
    /** When it was starred / highlighted — the timestamp we bucket by day. */
    val date: Date,
    /** Article title, or book title. */
    val title: String,
    /** Feed title (article) or author (book). */
    val source: String? = null,
    /** Article link (articles only). */
    val url: String? = null,
    /** The highlighted passage (books only). */
    val excerpt: String? = null,
    /** The reader's own note (books only). */
    val note: String? = null,
    /** Chapter label or CFI location (books only). */
    val location: String? = null,
    /** Photos / voice memos attached to this annotation. */
    val attachments: MutableList<Attachment>? = null,
    /** A featured/lead image URL for the source (articles), shown on the card. */
    val image: String? = null
) {
    enum class Kind {
        @Json(name = "article") ARTICLE,
        @Json(name = "book") BOOK
    }
}
