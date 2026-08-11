package com.toolsboox.plugin.calendar.widget

/**
 * WHERE THE SCHEDULE WIDGET FOLDS ITS COLUMN — computed from the day's own start hour, not
 * assumed from a literal.
 *
 * The schedule column is 35 rows of `ceh`: one header row, then 34 half-hour cells (17 hours,
 * two cells per hour — hour `h` occupies rows `2*(h - startHour) + 1` and the one below it).
 * The widget shows that column folded in two, side by side. The fold used to be hardcoded at
 * row 17, with a comment calling it "1:00pm" — true only for startHour = 5, the literal the
 * whole day surface has since stopped assuming (`PagePrefs.startHourOf`: THE DAY, THEN THE
 * SETTING, NEVER A LITERAL). On a day that starts at 7, row 17 is 3:30pm's border: the fold
 * lands mid-afternoon, the "morning" pane runs deep past lunch, and the two panes stop meaning
 * what their shapes say they mean.
 *
 * The fold belongs on the 1:00pm gridline of THIS day's grid, which is row
 * `2 * (13 - startHour) + 1`. When that line isn't usefully inside the drawn range — a day
 * with no grid at all ([com.toolsboox.plugin.calendar.ot.PagePrefs.NO_GRID]), or a start hour
 * so late the whole column is afternoon — the honest fold is the middle of the drawn range,
 * row 17, which is what the old constant silently was.
 *
 * Pure arithmetic in its own object (no android imports) for the same reason
 * [WidgetBitmapBudget.capScale] has a Context-free overload: so a JVM test can pin the row
 * math without Robolectric.
 */
object ScheduleSplit {

    /** One header row + 34 half-hour cells — the 35 `ceh` rows both renderers draw. */
    const val TOTAL_ROWS = 35

    /** The wall-clock hour the fold aims for: 1:00pm, morning | afternoon. */
    const val FOLD_HOUR = 13

    /** The middle fold — what the drawn range halves to when 1pm isn't inside it. */
    const val MIDDLE_ROW = 17

    /**
     * The row index (in `ceh` units below the column top) where the schedule composite splits.
     *
     * Rows 0..splitRow-1 become the left pane (header + morning), rows splitRow..34 the right
     * (afternoon). The 1pm gridline is used only when it leaves at least one full hour (two
     * cells) on each side of the fold — i.e. row 3..33; outside that, or when [startHour] is
     * not a real hour (NO_GRID = -1, garbage), the fold is [MIDDLE_ROW].
     */
    fun splitRow(startHour: Int): Int {
        if (startHour !in 0..23) return MIDDLE_ROW
        val foldRow = 2 * (FOLD_HOUR - startHour) + 1
        return if (foldRow in 3..TOTAL_ROWS - 2) foldRow else MIDDLE_ROW
    }
}
