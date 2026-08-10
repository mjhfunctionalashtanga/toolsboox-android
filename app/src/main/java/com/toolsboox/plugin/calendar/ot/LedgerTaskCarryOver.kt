package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.toolsboox.R
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import kotlin.math.ceil

/**
 * The Tasks section's row geometry, and the placement of a TYPED task onto the page.
 *
 * This object used to be the carry-over: every open of today it copied yesterday's undone tasks
 * into the day file and stamped typed text boxes into the Tasks column (then a reflow pass
 * re-arranged them), each box linked back to its item BY MATCHING WORDS. That retired on
 * Michael's ruling (2026-08-10, "they aren't making the workflow better"): an undone task no
 * longer gets re-inscribed — it stays on its own day, and the page DRAWS what is still open as
 * render-only ghost rows ([OpenTasks] + [CalendarDayPage]). What remains here is the asked-for
 * half: a task you type by hand gets a text box on the page ([placeTypedTask]), and the row
 * arithmetic both that and the ghost renderer share.
 *
 * **Rows are measured, never assumed.** A task is a text box that word-wraps to its own width, so
 * "one task, one 50px row" was a guess — and a task whose words ran to two lines was drawn straight
 * through the row below it. Every placement measures the wrapped layout with [StaticLayout] and
 * claims the rows it actually needs; a task that cannot fit the free rows at full size has its
 * FONT shrunk to fit rather than its words cut.
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
    // Seventeen, matching the drawn grid — the Roots band that once took four of these rows is
    // retired (see the grid comment in CalendarDayPage) and the rows came home.
    private const val ROWS = 17

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
     * The free rows of the Tasks section, in order — what the ghost renderer may draw into.
     * A row is taken by the hand's ink or by a text box's measured span; ghosts get the rest.
     */
    fun freeRows(context: Context?, day: CalendarDay): List<Int> {
        val used = occupiedRows(context, day)
        return (0 until ROWS).filter { it !in used }
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
