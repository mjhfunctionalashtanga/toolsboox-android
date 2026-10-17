package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * A HIGHLIGHT'S ONE CARD — found, named, and taken off with it. Shared by the article's highlights
 * (ArticleHighlights) and the book reader, so both answer Michael's rule the same way (2026-10-12):
 * "removing the highlight removes its pick (or asks, if the pick was moved onto a board — say
 * which)". The iOS twin is `MarkPick` (locate / remove / reface).
 *
 * A card is found by its element id on the day it was placed, ± a day for a clock that crossed
 * midnight in another time zone ([ReadingEventEdit.candidateDays] — the same neighbourhood the Log
 * line is looked for in).
 */
object MarkCard {

    /** Where a card is: its day and its page key ("…#board" on a board, [CalendarDayPageNotes.GRAM_PICKS] in All Picks). [id]: the card's element id. */
    data class Located(val day: LocalDate, val page: String) {
        /** Still where Highlight put it — All Picks — so removing needs no question. */
        val inAllPicks: Boolean get() = page.substringBefore('#') == CalendarDayPageNotes.GRAM_PICKS
    }

    fun locate(service: CalendarDayService, root: File, day: LocalDate, pickId: String): Located? {
        if (pickId.isBlank()) return null
        for (d in ReadingEventEdit.candidateDays(day)) {
            val cal = service.loadOrNull(root, d, Locale.getDefault()) ?: continue
            cal.imageElements.firstOrNull { it.elementId.toString().equals(pickId, ignoreCase = true) }
                ?.let { return Located(d, it.page) }
        }
        return null
    }

    /** Take [pickId] off [cal] and tombstone it there — whether or not this copy still held it, so
     *  the other device's copy of the day can't hand it back. True when the card was in [cal]. */
    fun dropFrom(cal: CalendarDay, pickId: String): Boolean {
        if (pickId.isBlank()) return false
        val held = cal.imageElements.removeAll { it.elementId.toString().equals(pickId, ignoreCase = true) }
        if (cal.deletedElementIds.none { it.equals(pickId, ignoreCase = true) }) cal.deletedElementIds.add(pickId.lowercase())
        return held
    }

    /** Take the card off the day it is on (found from [day]; tombstoned on [day] when it is gone —
     *  even when this device has never held that day: a removal carrier takes the marker there, so
     *  the card made on the other device can't stay in All Picks (review 2026-10-12 #1). THROWS
     *  [CalendarDayService.UnreadableDayException] when that day is there but won't read
     *  ([ReadingEventEdit.recordRemoval]) — nothing is written over it. */
    fun remove(service: CalendarDayService, root: File, day: LocalDate, pickId: String) {
        if (pickId.isBlank()) return
        ReadingEventEdit.recordRemoval(service, root, removalDay(service, root, day, pickId), listOf(pickId.lowercase()))
    }

    /** The day [remove] writes for [pickId]: the day it is found on from [day] (± a day), else
     *  [day] itself — so a caller can read that day FIRST, before it writes anything else. */
    fun removalDay(service: CalendarDayService, root: File, day: LocalDate, pickId: String): LocalDate =
        locate(service, root, day, pickId)?.day ?: day

    /** The board a moved card sits on, as the question names it. */
    fun boardName(context: Context, day: LocalDate, page: String): String {
        val base = page.substringBefore('#')
        return when {
            base == GramDestinations.NOTES_PAGE -> "that day's note"
            PickingsStore.isPickings(base) -> "“${PickingsStore.nameOf(context, day, base)}”"
            else -> "another board"
        }
    }
}
