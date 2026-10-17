package com.toolsboox.plugin.calendar.ot

/**
 * THE WORDS A REFUSED MARK WRITE SAYS (the day-file guard, 2026-10-16 review, item 6) — the verb
 * phrases that fill "Couldn't <verb> — <page> won't read"
 * ([com.toolsboox.plugin.calendar.fi.CalendarDayService.refusal]; iOS `DayFileWords.couldnt`), ONE
 * table for both apps, pinned by `convergence-fixtures/day-guard-words.json`.
 *
 * iOS's verbs where iOS had them first (DayReadingLog's line writes, the placement's whole line),
 * with Michael's noun where iOS said "card": a highlight's card is its PICK on both apps ("Remove,
 * pick too", "its pick in All Picks" — review 2026-10-12 R2 #5).
 */
object MarkWords {
    /** A new highlight — its pick and its Log line on today, in one write — refused: the
     *  placement's line, whole (iOS `DayFileWords.couldntWriteOnToday`). */
    const val HIGHLIGHT_REFUSED = "Couldn't write on today — its page won't read"

    /** Anything else written onto today refused — the same sentence ([HIGHLIGHT_REFUSED]; the Log
     *  basket's too; iOS's placement line): an untied Pick sheet's adds kept on today's A/V list,
     *  and its card placed Here on a today page that opened read-only (add-to-pick). */
    const val TODAY_REFUSED = HIGHLIGHT_REFUSED

    /** A highlight's (or a whole-piece note's) Log line made on its day (iOS `DayReadingLog.put`). */
    const val LOG_IT = "log it"

    /** What is said about a highlight — a note, handwriting, a photo, a recording — onto its one Log
     *  line, or the note cleared (iOS `DayReadingLog.edit`). */
    const val CHANGE_THE_LINE = "change the line"

    /** Its one pick re-drawn with what was said (iOS `ArticleMarks.reface`). */
    const val CHANGE_THE_PICK = "change the pick"

    /** A highlight's Log line taken off its day (iOS `DayReadingLog.remove`). */
    const val TAKE_THE_LINE_OFF = "take the line off"

    /** A highlight's pick taken off its day (iOS `ArticleMarks.remove`). */
    const val TAKE_THE_PICK_OFF = "take the pick off"

    /** 📝 on a kept highlight: a copy of the passage onto today's note (Android's own row). */
    const val PUT_ON_TODAYS_NOTE = "put it on today's note"

    /** A Pick sheet's card placed on a day other than today that won't read ("Couldn't place it —
     *  the Oct 3 page won't read"; merge-addpick review #10). NOT YET A ROW of
     *  convergence-fixtures/day-guard-words.json: that table is byte-identical in both repos, so its
     *  `placeIt` row lands in both at once (iOS says `DayFileWords.couldntWrite` at these sites today). */
    const val PLACE_IT = "place it"
}
