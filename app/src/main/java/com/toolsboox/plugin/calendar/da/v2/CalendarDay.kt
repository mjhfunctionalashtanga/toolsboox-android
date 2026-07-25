package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass
import com.toolsboox.da.Attachment
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v1.ReadingProgress
import java.util.*

/**
 * Calendar day data class.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@JsonClass(generateAdapter = true)
data class CalendarDay(
    val year: Int,
    val month: Int,
    val day: Int,
    val locale: Locale = Locale.getDefault(),
    val events: MutableList<CalendarEvent> = mutableListOf(),
    val readingProgress: MutableList<ReadingProgress> = mutableListOf(),

    var hasLanes: Boolean = false,
    var startHour: Int?,

    override var calendarStrokes: MutableMap<String, List<Stroke>> = mutableMapOf(),
    override var calendarValues: MutableMap<String, Map<String, Float?>> = mutableMapOf(),
    override var noteStrokes: MutableMap<String, List<Stroke>> = mutableMapOf(),
    override var textElements: MutableList<TextElement> = mutableListOf(),
    var imageElements: MutableList<ImageElement> = mutableListOf(),

    /**
     * Per-day reading timeline written by the iOS/cross-device Ledger — starred
     * articles (Feed Ledger) and book highlights (Book Ledger). This app doesn't
     * yet author these, but it MUST carry them through a load→save round-trip
     * (and the sync merge) or an Android save would silently strip the reading
     * data the iPad wrote. Defaulted so older/foreign day JSON still loads.
     */
    var readingEvents: MutableList<ReadingEvent> = mutableListOf(),

    /**
     * A/V Grams — the day's voice/video recordings, written by the iOS Ledger.
     * Same round-trip-preservation contract as [readingEvents].
     */
    var avGrams: MutableList<Attachment> = mutableListOf(),

    /**
     * Structured tasks + calendar events extracted from this day's handwriting (per-section
     * OCR). Each [LedgerItem] keeps both its OCR text and a link back to its ink. Same
     * round-trip-preservation + sync-merge contract as [readingEvents] / [avGrams].
     */
    var ledgerItems: MutableList<LedgerItem> = mutableListOf(),

    override var created: Date? = null,
    override var updated: Date? = null,

    /**
     * Tombstones for strokes the user has erased, keyed by [Stroke.strokeId] (as string).
     * A plain growing set is enough because strokeIds are globally-unique UUIDs and an
     * erase is permanent for that id. The sync merge unions these across devices and
     * subtracts them from the unioned strokes so a deletion on one device wins over the
     * surviving copy on another — instead of the union silently resurrecting erased ink.
     */
    var deletedStrokeIds: MutableList<String> = mutableListOf(),

    /**
     * Tombstones for erased/cut text and image elements, keyed by their elementId (as
     * string). Same purpose as [deletedStrokeIds] but for the element union merges — a
     * cut image resurrects on the next Drive-sync merge without this (the union sees the
     * other device's surviving copy and re-adds it → "echo image after open/shut").
     */
    var deletedElementIds: MutableList<String> = mutableListOf(),

    /**
     * Tombstones for tasks/events the user has deleted, keyed by [LedgerItem.id]. Same purpose
     * as [deletedStrokeIds] but for the [ledgerItems] id-union merge — without it the other
     * device's surviving copy re-adds a deleted task on the next sync ("cleared todos keep
     * coming back"). The wire name `deletedItemIds` is shared with the iOS Ledger, which is
     * adding the identical field in parallel — do not rename.
     */
    var deletedItemIds: MutableList<String> = mutableListOf()
) : Calendar {

    /**
     * Record a user-intent deletion of a ledger item (marking done is NOT deletion). Writes BOTH
     * tombstone lists: [deletedItemIds] is the canonical field (wire name shared with iOS), and
     * [deletedElementIds] is kept in lockstep because pre-split builds' merges and carry-over
     * only honour the element list for ledgerItems — a mixed-version fleet would resurrect the
     * item otherwise.
     */
    fun tombstoneLedgerItem(id: String) {
        if (id !in deletedItemIds) deletedItemIds.add(id)
        if (id !in deletedElementIds) deletedElementIds.add(id)
    }

    companion object {
        /**
         * Name of the default calendar page style.
         */
        const val DEFAULT_STYLE = "Default"

        /**
         * Covert calendar day data class from v1 format to v2 format.
         *
         * @param v1 the v1 data class
         * @return the v2 data class
         */
        fun convert(v1: com.toolsboox.plugin.calendar.da.v1.CalendarDay): CalendarDay {
            val strokes = com.toolsboox.plugin.teamdrawer.nw.domain.Stroke.convertTo(v1.strokes)
            val notesStrokes = com.toolsboox.plugin.teamdrawer.nw.domain.Stroke.convertTo(v1.notesStrokes)

            val calendarStrokes = mutableMapOf(DEFAULT_STYLE to strokes)
            val calendarValues = mutableMapOf(DEFAULT_STYLE to mapOf<String, Float?>())
            val noteStrokes = mutableMapOf("0" to notesStrokes)

            return CalendarDay(
                v1.year, v1.month, v1.day, v1.locale, mutableListOf(), mutableListOf(),
                false, null, calendarStrokes, calendarValues, noteStrokes
            )
        }
    }

    /**
     * Deep copy of the calendar day data class
     */
    fun deepCopy(): CalendarDay {
        return CalendarDay(
            this.year, this.month, this.day, this.locale, this.events.toMutableList(), this.readingProgress.toMutableList(), this.hasLanes, this.startHour,
            Calendar.strokesDeepCopy(calendarStrokes), Calendar.valuesDeepCopy(calendarValues), Calendar.strokesDeepCopy(noteStrokes),
            Calendar.textElementsDeepCopy(textElements),
            imageElements.map { it.copy() }.toMutableList(),
            readingEvents = this.readingEvents.map { it.copy() }.toMutableList(),
            avGrams = this.avGrams.map { it.copy() }.toMutableList(),
            ledgerItems = this.ledgerItems.map { it.copy(strokeIds = it.strokeIds.toMutableList()) }.toMutableList(),
            deletedStrokeIds = this.deletedStrokeIds.toMutableList(),
            deletedElementIds = this.deletedElementIds.toMutableList(),
            deletedItemIds = this.deletedItemIds.toMutableList()
        )
    }
}
