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
    /** Media-store name of the card face (`<sha256-of-decoded-bytes>.<png|jpg>`,
     *  WIRE-MEDIA-BY-REFERENCE.md). Goes FIRST in crop resolution, then try-base64([crop]), then
     *  [crop]-as-attachments-filename — the dual-typed [crop] fallback is permanent; this just adds
     *  a head to it. New field with a default → backward-compatible. */
    var cropRef: String? = null,
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
    var contactId: String? = null,
    /** Kanban stage for the Boards view: "todo" | "doing" | "waiting" | "done" ("" = todo). A
     *  stage is a VALUE on this existing key, never a new wire key — "waiting" (Aug 2026, the
     *  lane for work sitting on someone else) rides the same string, and any reader that doesn't
     *  know a value folds it into To do, which is honest degradation. New field with a default →
     *  backward-compatible. Wire-compatible with iOS `stage`. */
    var stage: String = "",
    /** Which named board this card belongs to ([Board.id]; "" = unfiled / on "All"). Wire-compatible
     *  with iOS `board`. New field with a default → backward-compatible. */
    var board: String = "",
    /** OFFER-NEVER-MINT (Michael's ruling, Aug 2026). An AUTO-created candidate — from the EMAIL
     *  intake faucet ([source] "email", id "li-…") or the PATHWAY/PLAN expansion faucet ([source]
     *  "path", id "pv-…") — is PROPOSED, not a live task. A proposed item is excluded from every
     *  active-task read (the Tasks list, its count, the Kanban, the widgets, the day-page ghost
     *  rows) and shown instead on the Proposed shelf, where one tap PROMOTES it (proposed=false →
     *  a live task) or DISMISSES it (tombstone). Nothing becomes a task without that tap.
     *
     *  Handwritten and manually-typed tasks are NEVER proposed — the default is `false`, the CURRENT
     *  behavior, so every existing item on disk and every hand-made task stays a live task unchanged;
     *  only the two faucets set it true. New key + safe default (the Moshi legacy-key rule, same as
     *  [contactId]/[stage]/[board]); the id-union merge carries it per-item, and promotion is
     *  monotonic there (parity with [done], see CalendarDayMerger). Wire-compatible with a future
     *  iOS `proposed`. */
    var proposed: Boolean = false
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
