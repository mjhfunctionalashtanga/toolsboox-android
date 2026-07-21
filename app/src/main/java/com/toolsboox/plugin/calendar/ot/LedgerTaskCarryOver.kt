package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/**
 * Granular task roll-over. Unfinished TASK [LedgerItem]s from the previous day repopulate onto the
 * next day, EACH as its own strokes (display=INK, the handwriting redrawn in a free Tasks row) or as
 * typed text (display=TEXT, a text box in the row) — per the item's own choice. This replaces the
 * blunt whole-section stroke reproducer ([CalendarTaskCarryOver]); the representation is now
 * controlled item by item.
 *
 * Idempotent: a carried copy keeps the source item's `id`, so re-loading the day finds it already
 * present and skips it. Yesterday is left intact (a copy, not a move) so it stays part of history.
 */
object LedgerTaskCarryOver {

    // Tasks-section geometry of the Default day page (mirrors CalendarTaskCarryOver / CalendarDayPage).
    private const val CEW = 600f
    private const val CEH = 50f
    private const val LO = 20f
    private val TO = (1872f - 35 * CEH) / 2f
    private val TASKS_TOP = TO + CEH
    private val TASKS_LEFT = LO + CEW + 50f
    private val TASKS_RIGHT = LO + 2 * CEW + 50f
    private val TASKS_TEXT_LEFT = LO + CEW + 110f   // right of the checkbox column
    // Ten: four rows to the Roots band, two to the write-in strip to the Roots band (see CalendarDayPage). Carrying
    // a task into a row that is no longer drawn would put it under the Roots title.
    private const val ROWS = TaskEntry.GRID_ROWS

    /**
     * Carry [yesterday]'s unfinished tasks onto [today] (mutated in place). Returns true if anything
     * was added (so [today] needs persisting).
     */
    fun carryOver(yesterday: CalendarDay, today: CalendarDay): Boolean {
        val open = yesterday.ledgerItems.filter {
            it.kind == LedgerItem.Kind.TASK && !it.done && it.text.isNotBlank()
        }
        if (open.isEmpty()) return false
        val existing = today.ledgerItems.map { it.id }.toSet()
        // A task the user deleted on `today` is tombstoned by its id. Carry-over MUST honour that
        // or it silently re-adds the deleted task every time the day reloads — the "won't delete /
        // keeps reappearing" bug, since a deleted id is no longer in `existing`.
        val tombstoned = today.deletedElementIds.toSet()
        // …and by its WORDS, because the id is exactly what two copies of one task don't share.
        // A task typed on the day page and the same task made from the reading log have different
        // ids, so the id check above waves both through — then carries both forward every day and
        // paints each its own text box. Matching the words stops the pair at the first carry.
        val saidAlready = HashSet<String>()
        today.ledgerItems.filter { it.kind == LedgerItem.Kind.TASK }
            .forEach { saidAlready.add(LedgerTaskDedupe.key(it.text)) }
        val toCarry = open
            .filter { it.id !in existing && it.id !in tombstoned }
            .filter { saidAlready.add(LedgerTaskDedupe.key(it.text)) }
        if (toCarry.isEmpty()) return false

        val used = occupiedRows(today)
        val freeRows = (0 until ROWS).filter { it !in used }.toMutableList()
        if (freeRows.isEmpty()) return false

        val srcById = (yesterday.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList())
            .associateBy { it.strokeId.toString() }
        val dueDate = Date(
            LocalDate.of(today.year, today.month, today.day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        )

        val addStrokes = mutableListOf<Stroke>()
        val addTexts = mutableListOf<TextElement>()
        val carried = mutableListOf<LedgerItem>()

        for (item in toCarry) {
            if (freeRows.isEmpty()) break
            val row = freeRows.removeAt(0)
            val rowTopY = TASKS_TOP + row * CEH
            val strokes = if (item.display == LedgerItem.Display.INK)
                item.strokeIds.mapNotNull { srcById[it] } else emptyList()

            if (strokes.isNotEmpty()) {
                // INK: redraw the handwriting, shifted so its top sits in the free row.
                val minY = strokes.flatMap { it.strokePoints }.minOf { it.y }
                val deltaY = rowTopY + 8f - minY
                val copied = strokes.map { s ->
                    s.copy(
                        strokeId = UUID.randomUUID(),
                        strokePoints = s.strokePoints.map { it.copy(y = it.y + deltaY) }
                    )
                }
                addStrokes.addAll(copied)
                carried.add(item.copy(
                    date = dueDate,
                    display = LedgerItem.Display.INK,
                    strokeIds = copied.map { it.strokeId.toString() }.toMutableList()
                ))
            } else {
                // TEXT (or INK whose strokes are gone): a typed text box in the row.
                addTexts.add(TextElement(
                    x = TASKS_TEXT_LEFT, y = rowTopY + 6f, width = CEW - 130f,
                    text = item.text, fontSize = 28f, pageKey = "default"
                ))
                carried.add(item.copy(date = dueDate, display = LedgerItem.Display.TEXT, strokeIds = mutableListOf()))
            }
        }
        if (carried.isEmpty()) return false

        val tgt = today.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList()
        today.calendarStrokes[CalendarDay.DEFAULT_STYLE] = tgt + addStrokes
        today.textElements.addAll(addTexts)
        today.ledgerItems.addAll(carried)
        return true
    }

    /**
     * Place a typed (no-ink) task as a text box in the first free Tasks row of [day], so a
     * finger/keyboard task shows on the day page alongside handwriting (not just in the list).
     * Returns false if the Tasks section is full.
     */
    fun placeTypedTask(day: CalendarDay, text: String): Boolean {
        val used = occupiedRows(day)
        val row = (0 until ROWS).firstOrNull { it !in used } ?: return false
        day.textElements.add(TextElement(
            x = TASKS_TEXT_LEFT, y = TASKS_TOP + row * CEH + 6f, width = CEW - 130f,
            text = text, fontSize = 28f, pageKey = "default"
        ))
        return true
    }

    /** Tasks-section rows (0..15) already occupied on [day] by strokes or text boxes. */
    private fun occupiedRows(day: CalendarDay): Set<Int> {
        val rows = mutableSetOf<Int>()
        (day.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList()).forEach { s ->
            if (s.strokePoints.isNotEmpty()) rowOf(
                s.strokePoints.map { it.x }.average().toFloat(),
                s.strokePoints.map { it.y }.average().toFloat()
            )?.let { rows.add(it) }
        }
        day.textElements.filter { it.pageKey == "default" }.forEach { rowOf(it.x, it.y)?.let { r -> rows.add(r) } }
        return rows
    }

    private fun rowOf(centerX: Float, centerY: Float): Int? {
        if (centerX < TASKS_LEFT || centerX > TASKS_RIGHT) return null
        if (centerY < TASKS_TOP || centerY >= TASKS_TOP + ROWS * CEH) return null
        return ((centerY - TASKS_TOP) / CEH).toInt()
    }
}
