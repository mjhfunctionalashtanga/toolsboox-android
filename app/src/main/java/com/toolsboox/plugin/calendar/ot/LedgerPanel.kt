package com.toolsboox.plugin.calendar.ot

import android.graphics.RectF

/**
 * A shared, addressable region of any Ledger page — the keystone that lets every panel be
 * treated the same way: rendered to a PNG card, OCR'd, saved into Notes & Annotations, and
 * (later) pushed through a template's webhook/API. Generalises the intake page's
 * `IntakePanel` to the whole planner.
 *
 * Coordinates are in the shared 1404×1872 page space that the Kotlin templates draw into
 * (the same space the iPad templates use), so a panel maps 1:1 to the rendered page bitmap.
 */
data class LedgerPanel(
    /** Stable key, e.g. "gratitude", "best_thing", "doodle", "notes", "quotes". */
    val id: String,
    /** Human title, matching the on-page header. */
    val title: String,
    /** What the panel holds — drives OCR/card treatment. */
    val kind: Kind,
    /** Region in 1404×1872 page space. */
    val rect: RectF
) {
    enum class Kind { TEXT, DOODLE, IMAGE }

    companion object {
        /**
         * The panels of a given day page (by note-page key; null = the default Schedule/
         * Tasks/Notes day page). Geometry mirrors the drawn templates.
         */
        fun forPage(notePage: String?): List<LedgerPanel> = when (notePage) {
            "gratitude" -> listOf(
                LedgerPanel("gratitude", "3 Things I'm Grateful For", Kind.TEXT, RectF(60f, 40f, 672f, 910f)),
                LedgerPanel("best_thing", "The Best Thing Today", Kind.TEXT, RectF(732f, 40f, 1344f, 910f)),
                LedgerPanel("doodle", "Doodle", Kind.DOODLE, RectF(60f, 955f, 1344f, 1820f))
            )
            "pickings" -> listOf(
                LedgerPanel("quotes", "Pickings", Kind.TEXT, RectF(60f, 40f, 1344f, 1820f))
            )
            "intake" -> CalendarDayPageIntake.panels.map {
                LedgerPanel(it.kindKey, it.title, Kind.TEXT, RectF(it.rect))
            }
            null, "default", "0" -> listOf(
                LedgerPanel("schedule", "Schedules", Kind.TEXT, RectF(20f, 60f, 620f, 1810f)),
                LedgerPanel("tasks", "Tasks", Kind.TEXT, RectF(684f, 60f, 1384f, 1000f)),
                LedgerPanel("notes", "Notes", Kind.TEXT, RectF(684f, 1040f, 1384f, 1810f))
            )
            else -> listOf(
                // Any other named note page is a single full-page writing panel.
                LedgerPanel("notes", "Notes", Kind.TEXT, RectF(60f, 40f, 1344f, 1820f))
            )
        }
    }
}
