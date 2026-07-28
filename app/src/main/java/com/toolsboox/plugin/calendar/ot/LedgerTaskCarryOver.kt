package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.math.ceil

/**
 * Granular task roll-over. Unfinished TASK [LedgerItem]s from the previous day repopulate onto the
 * next day, EACH as its own strokes (display=INK, the handwriting redrawn in a free Tasks row) or as
 * typed text (display=TEXT, a text box in the row) — per the item's own choice. This replaces the
 * blunt whole-section stroke reproducer ([CalendarTaskCarryOver]); the representation is now
 * controlled item by item.
 *
 * Idempotent: a carried copy keeps the source item's `id`, so re-loading the day finds it already
 * present and skips it. Yesterday is left intact (a copy, not a move) so it stays part of history.
 *
 * **Rows are measured, never assumed.** A task is a text box that word-wraps to its own width, so
 * "one task, one 50px row" was a guess — and a task whose words ran to two lines was drawn straight
 * through the row below it ("layers on top … it should not overwrite/stack that way"). Every
 * placement here measures the wrapped layout with [StaticLayout] and claims the rows it actually
 * needs; a task that cannot fit the free rows at full size has its FONT shrunk to fit rather than
 * its words cut, so the text a delete matches on stays intact.
 */
object LedgerTaskCarryOver {

    // Tasks-section geometry of the Default day page (mirrors CalendarTaskCarryOver / CalendarDayPage).
    private const val CEW = 645f  // in lockstep with CalendarDayPage.cew (widened 07-24)
    private const val CEH = 50f
    private const val LO = 20f
    private val TO = (1872f - 35 * CEH) / 2f
    private val TASKS_TOP = TO + CEH
    private val TASKS_LEFT = LO + CEW + 50f
    private val TASKS_RIGHT = LO + 2 * CEW + 50f
    private val TASKS_TEXT_LEFT = LO + CEW + 110f   // right of the checkbox column
    // Twelve: four rows go to the Roots band (see CalendarDayPage). Carrying a task into a
    // row that is no longer drawn would put it under the Roots title.
    private const val ROWS = 12

    /** Where a task box sits inside its row, and how wide it may run before wrapping. */
    private const val ROW_INSET = 6f
    private val BOX_WIDTH = CEW - 130f

    /** Full size, and the smallest the font may be shrunk to before a task is simply not placed. */
    private const val FONT_SIZE = 28f
    private const val FONT_FLOOR = 17f

    /** The page face, resolved once — measuring must agree with [com.toolsboox.ui.plugin.SurfaceFragment]'s draw. */
    @Volatile
    private var face: Typeface? = null

    private fun typeface(context: Context?): Typeface {
        face?.let { return it }
        val t = context?.let { runCatching { ResourcesCompat.getFont(it, R.font.atkinson_hyperlegible) }.getOrNull() }
            ?: Typeface.DEFAULT
        face = t
        return t
    }

    private fun paint(context: Context?, fontSize: Float): TextPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = fontSize
        typeface = typeface(context)
    }

    /**
     * How many 50px rows a task's WRAPPED text really occupies — the measurement the draw cursor
     * advances by, so the next task starts below this one instead of on top of it.
     */
    fun rowsNeeded(context: Context?, text: String, width: Float = BOX_WIDTH, fontSize: Float = FONT_SIZE): Int {
        if (text.isBlank()) return 1
        val w = width.coerceAtLeast(80f).toInt()
        val height = runCatching {
            StaticLayout.Builder.obtain(text, 0, text.length, paint(context, fontSize), w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build().height.toFloat()
        }.getOrDefault(fontSize * 1.3f)
        // The box is inset into its row, so a layout that only just fits the bare row height would
        // still spill into the next one — count the inset as part of what has to fit.
        return ceil((height + ROW_INSET) / CEH).toInt().coerceIn(1, ROWS)
    }

    /** The largest font (down to [FONT_FLOOR]) at which [text] fits [rows] rows, or null. */
    private fun fontThatFits(context: Context?, text: String, width: Float, rows: Int): Float? {
        var size = FONT_SIZE
        while (size >= FONT_FLOOR) {
            if (rowsNeeded(context, text, width, size) <= rows) return size
            size -= 2f
        }
        return null
    }

    /**
     * Carry [yesterday]'s unfinished tasks onto [today] (mutated in place). Returns true if anything
     * was added (so [today] needs persisting).
     */
    fun carryOver(yesterday: CalendarDay, today: CalendarDay, context: Context? = null): Boolean {
        val open = yesterday.ledgerItems.filter {
            it.kind == LedgerItem.Kind.TASK && !it.done && it.text.isNotBlank()
        }
        if (open.isEmpty()) return false
        val existing = today.ledgerItems.map { it.id }.toSet()
        // A task the user deleted on `today` is tombstoned by its id — in `deletedItemIds`
        // (the dedicated list, shared wire name with iOS) and, for deletions recorded by
        // pre-split builds, `deletedElementIds`. Carry-over MUST honour both or it silently
        // re-adds the deleted task every time the day reloads — the "won't delete / keeps
        // reappearing" bug, since a deleted id is no longer in `existing`.
        val tombstoned = buildSet {
            addAll(today.deletedItemIds)
            addAll(today.deletedElementIds)
        }
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

        val used = occupiedRows(context, today).toMutableSet()
        if (used.size >= ROWS) return false

        val srcById = (yesterday.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList())
            .associateBy { it.strokeId.toString() }
        val dueDate = Date(
            LocalDate.of(today.year, today.month, today.day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        )

        val addStrokes = mutableListOf<Stroke>()
        val addTexts = mutableListOf<TextElement>()
        val carried = mutableListOf<LedgerItem>()

        for (item in toCarry) {
            val strokes = if (item.display == LedgerItem.Display.INK)
                item.strokeIds.mapNotNull { srcById[it] } else emptyList()

            if (strokes.isNotEmpty()) {
                // INK: redraw the handwriting, shifted so its top sits in a free run of rows tall
                // enough to hold the whole thing — tall handwriting used to be dropped into one
                // row and run over whatever was under it, exactly as wrapped text did.
                val minY = strokes.flatMap { it.strokePoints }.minOf { it.y }
                val maxY = strokes.flatMap { it.strokePoints }.maxOf { it.y }
                val span = ceil((maxY - minY + ROW_INSET) / CEH).toInt().coerceIn(1, ROWS)
                val row = freeRun(used, span) ?: continue
                claim(used, row, span)
                val deltaY = TASKS_TOP + row * CEH + 8f - minY
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
                // TEXT (or INK whose strokes are gone): a typed text box in as many rows as its
                // wrapped words actually need.
                val placed = placeBox(context, used, item.text) ?: continue
                addTexts.add(placed)
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
     * Place a typed (no-ink) task as a text box in the first free Tasks rows of [day], so a
     * finger/keyboard task shows on the day page alongside handwriting (not just in the list).
     * Returns false if the Tasks section has no run of free rows big enough for it.
     */
    fun placeTypedTask(day: CalendarDay, text: String, context: Context? = null): Boolean {
        val used = occupiedRows(context, day).toMutableSet()
        val box = placeBox(context, used, text) ?: return false
        day.textElements.add(box)
        return true
    }

    /**
     * Re-lay the Tasks section so **no two rows are drawn on top of each other** — the repair pass
     * for days whose boxes were placed by the old fixed-pitch rule (and for anything a sync merge
     * lands mid-column). Ink is never moved: handwriting is where the hand put it, so its rows are
     * claimed first and the typed boxes fill the gaps in their existing top-to-bottom order.
     *
     * Only y (and, when a task will not otherwise fit, font size) changes — never the words, which
     * are what a delete matches a box to its item by. Returns true when something moved.
     */
    fun reflow(context: Context?, day: CalendarDay): Boolean {
        val boxes = day.textElements
            .filter { it.pageKey == "default" && inTasks(it.x, it.y) }
            .sortedWith(compareBy({ it.y }, { it.x }))
        if (boxes.isEmpty()) return false

        val used = inkRows(day).toMutableSet()
        var changed = false
        for (box in boxes) {
            val want = rowsNeeded(context, box.text, box.width, FONT_SIZE)
            var size = FONT_SIZE
            var row = freeRun(used, want)
            var span = want
            if (row == null) {
                // No run that tall — shrink the type until the words fit whatever run is left.
                val biggest = largestFreeRun(used)
                val fitted = if (biggest > 0) fontThatFits(context, box.text, box.width, biggest) else null
                if (fitted != null) {
                    size = fitted
                    span = rowsNeeded(context, box.text, box.width, size)
                    row = freeRun(used, span)
                }
            }
            if (row == null) {
                // Genuinely nowhere to go: leave it exactly where it is and claim its rows so the
                // next box still routes around it. Losing a task is worse than a crowded section.
                claim(used, rowOf(box.x, box.y) ?: 0, want)
                continue
            }
            claim(used, row, span)
            val y = TASKS_TOP + row * CEH + ROW_INSET
            if (box.y != y || box.fontSize != size) {
                box.y = y
                box.fontSize = size
                changed = true
            }
        }
        return changed
    }

    /** A text box for [text] in the first free run of rows, claiming them. Null when none fits. */
    private fun placeBox(context: Context?, used: MutableSet<Int>, text: String): TextElement? {
        val want = rowsNeeded(context, text, BOX_WIDTH, FONT_SIZE)
        var size = FONT_SIZE
        var span = want
        var row = freeRun(used, want)
        if (row == null) {
            val biggest = largestFreeRun(used)
            val fitted = if (biggest > 0) fontThatFits(context, text, BOX_WIDTH, biggest) else null
            if (fitted == null) return null
            size = fitted
            span = rowsNeeded(context, text, BOX_WIDTH, size)
            row = freeRun(used, span) ?: return null
        }
        claim(used, row, span)
        return TextElement(
            x = TASKS_TEXT_LEFT, y = TASKS_TOP + row * CEH + ROW_INSET, width = BOX_WIDTH,
            text = text, fontSize = size, pageKey = "default"
        )
    }

    /** The first row starting a run of [span] free rows inside the section, or null. */
    private fun freeRun(used: Set<Int>, span: Int): Int? =
        (0..ROWS - span).firstOrNull { start -> (start until start + span).none { it in used } }

    /** The tallest run of free rows left in the section (0 when the section is full). */
    private fun largestFreeRun(used: Set<Int>): Int {
        var best = 0
        var run = 0
        for (r in 0 until ROWS) {
            if (r in used) run = 0 else { run++; if (run > best) best = run }
        }
        return best
    }

    private fun claim(used: MutableSet<Int>, row: Int, span: Int) {
        for (r in row until (row + span).coerceAtMost(ROWS)) used.add(r)
    }

    /** Tasks-section rows already occupied on [day] — by ink, and by every row a text box spans. */
    private fun occupiedRows(context: Context?, day: CalendarDay): Set<Int> {
        val rows = inkRows(day).toMutableSet()
        day.textElements.filter { it.pageKey == "default" && inTasks(it.x, it.y) }.forEach { t ->
            val row = rowOf(t.x, t.y) ?: return@forEach
            claim(rows, row, rowsNeeded(context, t.text, t.width, t.fontSize))
        }
        return rows
    }

    /** Rows the handwriting covers — its whole vertical extent, not just where its middle landed. */
    private fun inkRows(day: CalendarDay): Set<Int> {
        val rows = mutableSetOf<Int>()
        (day.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList()).forEach { s ->
            if (s.strokePoints.isEmpty()) return@forEach
            val cx = s.strokePoints.map { it.x }.average().toFloat()
            if (cx < TASKS_LEFT || cx > TASKS_RIGHT) return@forEach
            val top = s.strokePoints.minOf { it.y }
            val bottom = s.strokePoints.maxOf { it.y }
            val first = rowOf(cx, top) ?: rowOf(cx, bottom) ?: return@forEach
            val last = rowOf(cx, bottom) ?: first
            for (r in first..last) rows.add(r)
        }
        return rows
    }

    /** True when a point falls inside the drawn Tasks section. */
    private fun inTasks(x: Float, y: Float): Boolean =
        x >= TASKS_LEFT && x <= TASKS_RIGHT && y >= TASKS_TOP && y < TASKS_TOP + ROWS * CEH

    private fun rowOf(centerX: Float, centerY: Float): Int? {
        if (!inTasks(centerX, centerY)) return null
        return ((centerY - TASKS_TOP) / CEH).toInt().coerceIn(0, ROWS - 1)
    }
}
