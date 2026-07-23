package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.calendar.da.v2.LedgerItem

/**
 * One task, once — however many roads it arrived by.
 *
 * The same task can be born twice with two different ids. Typing "get bent" on the day page makes
 * an `li-…` item; making a task from the same thing in the reading log makes a `log-…` item. Both
 * are TASK, both say the same words, and every dedupe in the app keyed on `id`, so nothing caught
 * it. Worse, carry-over then honoured both faithfully — copying each forward every day and painting
 * each its own text box, so the day page grew a second "get bent" one row under the first.
 *
 * Matching on the words rather than the id is the only thing that works here, because the id is
 * precisely what the two copies don't share. Normalised the way a person would read it: case and
 * punctuation are not a difference, and neither is the trailing " (done)" a status line adds.
 *
 * Deliberately NOT fuzzy. A near-match rule would eventually collapse two genuinely different
 * tasks that happen to start alike ("call Dad" / "call Dad's landlord"), and quietly losing a task
 * is far worse than showing one twice.
 */
object LedgerTaskDedupe {

    /**
     * The comparable form of a task's words: lowercase, punctuation dropped, runs of space
     * collapsed. Two items with the same key are the same task no matter what their ids say.
     */
    fun key(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()

    /** True when [items] already holds a task saying the same words as [text]. */
    fun containsTask(items: List<LedgerItem>, text: String): Boolean {
        val k = key(text)
        if (k.isEmpty()) return false
        return items.any { it.kind == LedgerItem.Kind.TASK && key(it.text) == k }
    }

    /**
     * Drop later copies of a task that says what an earlier one already said.
     *
     * First in wins, so the original keeps its place in the list and its id — anything already
     * pointing at that id (a sync record, a board card) stays pointing at something real.
     *
     * Events are left entirely alone: two events with the same title at different times are a
     * normal week, not a mistake.
     */
    fun dedupe(items: List<LedgerItem>): List<LedgerItem> {
        val seen = HashSet<String>()
        return items.filter { item ->
            if (item.kind != LedgerItem.Kind.TASK) return@filter true
            val k = key(item.text)
            if (k.isEmpty()) true else seen.add(k)
        }
    }
}
