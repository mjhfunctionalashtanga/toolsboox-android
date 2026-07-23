package com.toolsboox.plugin.calendar.ot

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Reading a due date out of handwriting.
 *
 * This began as a write-in strip at the foot of the Tasks panel — a place to write a task and a
 * date, lifted off and filed on page-leave. That is gone, and the reasoning is worth keeping:
 * lassoing a written task and tapping "→ item" already did the same job better. It KEEPS the ink
 * and links the item to it, rather than deleting handwriting on the strength of an OCR guess; it
 * shows you the text before committing, so a bad read is caught by a person instead of filed
 * silently; and it costs no rows. The strip cost two, and every data-loss risk found in the review
 * lived inside it.
 *
 * What survives is the useful half: the date. Write "call Dad fri" and the "fri" becomes the due
 * date rather than part of the task's name.
 */
object TaskEntry {

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

    /**
     * Split a trailing date off a task's words.
     *
     * "call Dad fri" is one gesture and two facts. Reading the last word or two as a date, when it
     * IS one, means the date can be written where the task is written rather than in a separate
     * box — which was the only thing the write-in strip did better than a lasso.
     *
     * Returns the text with the date removed and the date itself, or the text unchanged and null.
     * Unchanged is the common case and must stay cheap and safe: `parseDue` answers "today" for
     * anything it doesn't recognise, so a word only counts as a date when it resolves to some
     * OTHER day. That keeps ordinary words — "call Dad", "email Bob" — entirely alone.
     */
    fun splitTrailingDue(text: String, today: LocalDate = LocalDate.now()): Pair<String, LocalDate?> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2) return text to null
        // Try the last two words, then the last one: "in 3 days" is a date, "days" alone is not.
        for (take in 2 downTo 1) {
            if (words.size <= take) continue
            val tail = words.takeLast(take).joinToString(" ")
            val parsed = parseDue(tail, today)
            if (parsed != today) {
                var head = words.dropLast(take)
                // Drop a dangling preposition the date was hanging off: "meet Sam on tuesday".
                // "in" too: "book flights in 3 days" reads its date as the last TWO words, which
                // leaves the "in" behind on the task's name.
                if (head.lastOrNull()?.lowercase() in setOf("on", "by", "due", "in")) head = head.dropLast(1)
                if (head.isEmpty()) return text to null      // the whole thing was a date; keep it
                return head.joinToString(" ") to parsed
            }
        }
        return text to null
    }

    /**
     * Split a trailing time off a task's words: "call Dad 3pm", "standup 09:30".
     *
     * Programmatic first, deliberately. The whole point of reading the ink on-device is that it
     * works on a plane with no key and no signal — and a clock face is the most regular thing a
     * person writes, so it needs no model to read. What can't be parsed is simply left in the
     * name, where it is visible and can be corrected, rather than guessed at.
     *
     * Returns the words with the time removed and "HH:mm", or the words unchanged and null.
     */
    fun splitTrailingTime(text: String): Pair<String, String?> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2) return text to null
        val last = words.last().lowercase().trim('.', ',', ';')
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))?(am|pm)?$").find(last) ?: return text to null
        var hour = m.groupValues[1].toIntOrNull() ?: return text to null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val suffix = m.groupValues[3]
        // A bare number is a day of the month far more often than an hour — "call Dad 24" is the
        // 24th. Only an explicit am/pm or a colon makes it a time.
        if (suffix.isEmpty() && m.groupValues[2].isEmpty()) return text to null
        if (suffix == "pm" && hour < 12) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return text to null
        val head = words.dropLast(1)
            .let { if (it.lastOrNull()?.lowercase() == "at") it.dropLast(1) else it }
        if (head.isEmpty()) return text to null
        return head.joinToString(" ") to String.format(Locale.US, "%02d:%02d", hour, minute)
    }
}
