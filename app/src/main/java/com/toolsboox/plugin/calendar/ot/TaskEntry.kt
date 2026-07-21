package com.toolsboox.plugin.calendar.ot

import android.graphics.RectF
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Write a task at the foot of the Tasks panel, and say when it's due.
 *
 * The Tasks grid is a list of rows, which is a fine way to READ tasks and a poor way to add one:
 * you have to find a free row, and the row you find is wherever there happens to be space rather
 * than where the task belongs. So the last two rows of the panel stop being grid and become a
 * place to write — wide box for the words, narrow box for when — and what lands there is lifted
 * off, filed as a real item with a due date, and the ink cleared for the next one.
 *
 * Two lines rather than one because handwriting is bigger than type, and a single 50px row is
 * cramped enough that you write small and the recogniser suffers for it.
 *
 * Geometry mirrors [CalendarDayPage]. If the page moves these, this must move with it — they are
 * the same rectangles seen from two sides, and nothing in the type system says so.
 */
object TaskEntry {

    private const val CEW = 600f
    private const val CEH = 50f
    private const val LO = 20f
    private val TO = (1872f - 35 * CEH) / 2f

    /** Rows 1..[GRID_ROWS] are the readable grid; the two below are where you write. */
    const val GRID_ROWS = 10

    private val LEFT = LO + CEW + 50f
    private val RIGHT = LO + 2 * CEW + 50f

    /** Top of the write-in strip: straight after the last grid row. */
    private val STRIP_TOP = TO + (GRID_ROWS + 1) * CEH
    private val STRIP_BOTTOM = STRIP_TOP + 2 * CEH

    /** Where the "due by" cell starts — the right quarter of the strip. */
    private val DUE_LEFT = RIGHT - 190f

    /** The whole write-in strip (words + due), for drawing its border. */
    val strip: RectF get() = RectF(LEFT, STRIP_TOP, RIGHT, STRIP_BOTTOM)

    /** Where the task's words go. */
    val words: RectF get() = RectF(LEFT, STRIP_TOP, DUE_LEFT, STRIP_BOTTOM)

    /** Where "tue", "3/8", "fri" goes. Optional — a task with no date is due today. */
    val due: RectF get() = RectF(DUE_LEFT, STRIP_TOP, RIGHT, STRIP_BOTTOM)

    /** True when a point in page design space falls inside the write-in strip. */
    fun contains(x: Float, y: Float): Boolean = strip.contains(x, y)

    /**
     * Read a hand-written due date.
     *
     * Deliberately forgiving and deliberately small: the things a person actually writes in a box
     * this size are a weekday, a day-and-month, or a word like "tomorrow". Anything it can't read
     * means today, because a task with a wrong date is worse than a task with an obvious one — you
     * will see it on today's list and can move it, whereas a task silently filed three weeks out
     * is a task you have lost.
     *
     * [today] is passed in rather than read from the clock so this can be tested at all.
     */
    fun parseDue(text: String?, today: LocalDate): LocalDate {
        val t = text?.lowercase()?.trim()?.trim('.', ',', ':', ';') ?: return today
        if (t.isEmpty()) return today

        if (t.startsWith("today") || t == "tod") return today
        if (t.startsWith("tomorrow") || t == "tom" || t == "tmrw") return today.plusDays(1)

        // "3d", "in 3 days", "2w"
        Regex("^(?:in\\s+)?(\\d{1,2})\\s*(d|day|days|w|wk|week|weeks)$").find(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            return if (m.groupValues[2].startsWith("w")) today.plusWeeks(n) else today.plusDays(n)
        }

        // A weekday name or its prefix → the NEXT one of those, never today's date in the past.
        val day = java.time.DayOfWeek.entries.firstOrNull { d ->
            val full = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
            val short = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
            t == full || t == short || (t.length >= 3 && full.startsWith(t))
        }
        if (day != null) {
            var d = today.plusDays(1)
            while (d.dayOfWeek != day) d = d.plusDays(1)
            return d
        }

        // "3/8", "3-8", "12/25" — day/month in the order the rest of the app writes dates.
        Regex("^(\\d{1,2})\\s*[/-]\\s*(\\d{1,2})$").find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // Day-first, as the rest of the app writes dates — "3/8" is the third of August.
            // Unless the SECOND number can't be a month, in which case it must be the day.
            val (dd, mm) = if (b > 12) b to a else a to b
            if (mm in 1..12 && dd in 1..31) {
                val candidate = runCatching { LocalDate.of(today.year, mm, dd) }.getOrNull()
                if (candidate != null) {
                    // A date already gone means next year — you don't set a task for last March.
                    return if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
                }
            }
        }

        // Bare day of the month: "24" → this month if still ahead, else next.
        t.toIntOrNull()?.let { n ->
            if (n in 1..31) {
                val candidate = runCatching { today.withDayOfMonth(n) }.getOrNull()
                if (candidate != null) return if (candidate.isBefore(today)) candidate.plusMonths(1) else candidate
            }
        }

        return today
    }
}
