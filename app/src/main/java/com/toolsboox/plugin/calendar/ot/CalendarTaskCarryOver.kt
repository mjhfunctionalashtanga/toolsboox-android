package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.Stroke
import com.toolsboox.plugin.calendar.da.v2.CalendarDay

/**
 * Carries over open (unchecked) rows of the Tasks section of the Default day page
 * to the next day's page.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
object CalendarTaskCarryOver {

    // Layout constants of the Tasks section on the Default day page, see CalendarDayPage
    private const val CEW = 600.0f
    private const val CEH = 50.0f
    private const val LO = 20.0f
    private const val TO = (1872.0f - 35 * CEH) / 2.0f

    private const val TASKS_LEFT = LO + CEW + 50.0f
    private const val TASKS_RIGHT = LO + 2 * CEW + 50.0f
    private const val TASKS_DIVIDER = LO + CEW + 100.0f
    private const val TASKS_TOP = TO + CEH
    private const val ROWS = 16

    /**
     * Moves open (unchecked) task rows of [yesterday] to the first free rows of [today].
     *
     * @param yesterday the previous day's calendar day, mutated in place
     * @param today the current day's calendar day, mutated in place
     * @return true if any strokes were moved, meaning both days need to be persisted
     */
    fun carryOver(yesterday: CalendarDay, today: CalendarDay): Boolean {
        val sourceStrokes = yesterday.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: return false
        val targetStrokes = today.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: listOf()

        val sourceRows = groupByRow(sourceStrokes)
        val targetRows = groupByRow(targetStrokes)

        val openRows = (1..ROWS).filter { row ->
            val strokes = sourceRows[row] ?: return@filter false
            strokes.any { !it.isCheckboxStroke() } && strokes.none { it.isCheckboxStroke() }
        }
        if (openRows.isEmpty()) return false

        val freeRows = (1..ROWS).filter { targetRows[it].isNullOrEmpty() }.toMutableList()
        if (freeRows.isEmpty()) return false

        val movedStrokes = mutableListOf<Stroke>()
        val remainingSourceStrokes = sourceStrokes.toMutableList()

        for (sourceRow in openRows) {
            if (freeRows.isEmpty()) break
            val targetRow = freeRows.removeAt(0)
            val deltaY = (targetRow - sourceRow) * CEH

            val rowStrokes = sourceRows[sourceRow] ?: continue
            rowStrokes.forEach { stroke ->
                movedStrokes.add(stroke.copy(strokePoints = stroke.strokePoints.map { it.copy(y = it.y + deltaY) }))
            }
            remainingSourceStrokes.removeAll(rowStrokes)
        }

        if (movedStrokes.isEmpty()) return false

        yesterday.calendarStrokes[CalendarDay.DEFAULT_STYLE] = remainingSourceStrokes
        today.calendarStrokes[CalendarDay.DEFAULT_STYLE] = targetStrokes + movedStrokes
        return true
    }

    /**
     * Groups the strokes of the Tasks section by their row number (1..16).
     */
    private fun groupByRow(strokes: List<Stroke>): Map<Int, List<Stroke>> {
        return strokes.mapNotNull { stroke -> rowOf(stroke)?.let { it to stroke } }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Returns the Tasks section row number (1..16) of the stroke, or null if it's outside of it.
     */
    private fun rowOf(stroke: Stroke): Int? {
        if (stroke.strokePoints.isEmpty()) return null

        val centerX = stroke.strokePoints.map { it.x }.average()
        val centerY = stroke.strokePoints.map { it.y }.average()

        if (centerX < TASKS_LEFT || centerX > TASKS_RIGHT) return null
        if (centerY < TASKS_TOP || centerY >= TASKS_TOP + ROWS * CEH) return null

        return ((centerY - TASKS_TOP) / CEH).toInt() + 1
    }

    /**
     * A stroke counts as a checkbox mark if its center is left of the column divider.
     */
    private fun Stroke.isCheckboxStroke(): Boolean {
        val centerX = strokePoints.map { it.x }.average()
        return centerX < TASKS_DIVIDER
    }
}
