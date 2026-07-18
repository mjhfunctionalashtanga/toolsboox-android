package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.Date

/**
 * A structured item extracted from a day page's handwriting — a **task** (from the Tasks
 * section) or a calendar **event** (from the Schedule section). Each keeps BOTH faces of the
 * source: the OCR'd [text] AND a pointer back to the ink ([left]/[top]/[right]/[bottom] region
 * in the 1404×1872 template space + the [strokeIds] that produced it, and an optional cropped
 * ink PNG [crop]). [display] chooses which face a list shows — **per item** — defaulting by OCR
 * [confidence] (high → text, shaky → ink) with a tap override.
 *
 * Wire-shape is flat + string-enum + millis-date so it round-trips 1:1 with a future iOS Codable
 * mirror, and rides the shared CalendarDay JSON like [ReadingEvent] / avGrams.
 */
@JsonClass(generateAdapter = true)
data class LedgerItem(
    val id: String,
    val kind: Kind,
    /** OCR'd text (Layer-1 ink OCR, optionally upgraded by the Layer-2 vision pass). */
    var text: String,
    /** When it was extracted. */
    var date: Date,
    /** Source region in the 1404×1872 template space (links the item back to its ink). */
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    /** Ids of the strokes that make up this item, so its ink can be re-rendered. */
    val strokeIds: MutableList<String> = mutableListOf(),
    /** Optional cropped-ink PNG filename in the attachments dir (rendered lazily). */
    val crop: String? = null,
    /** Which face to show in a list — per item. */
    var display: Display = Display.TEXT,
    /** Tasks: checked/done. */
    var done: Boolean = false,
    /** Events: a time label if one was written/parsed (e.g. "09:00"). */
    val time: String? = null,
    /** 0..1 OCR confidence; drives the default [display]. */
    val confidence: Float = 1f,
    /** Provenance: the page key, or "auto" / "lasso". */
    val source: String? = null,
    /** Optional rolodex cross-reference: the [Contact.id] this task/event is assigned to / about.
     *  New field with a default → backward-compatible; the id-union merge carries it in the value. */
    var contactId: String? = null
) {
    enum class Kind {
        @Json(name = "task") TASK,
        @Json(name = "event") EVENT
    }

    enum class Display {
        @Json(name = "ink") INK,
        @Json(name = "text") TEXT
    }
}
