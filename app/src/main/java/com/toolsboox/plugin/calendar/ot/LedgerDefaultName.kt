package com.toolsboox.plugin.calendar.ot

/**
 * THE DEFAULT NAME OF A NEW NOTE IS ITS DATE (Michael, 2026-08-23: "Everything gets a name,
 * default name being the date").
 *
 * It replaces four different counters — "Writing 7", "Jot 3", "Pickings 2", "Synthesis 5" —
 * which named a note after HOW MANY there were, a fact about the shelf rather than about the
 * note. The date is what you actually know about a page at the moment you start it, and it is
 * the thing you reach for when you go looking later.
 *
 * ISO, deliberately, and identically on iOS: this string goes on the WIRE (the `name` field of
 * every `pages.json`), it is already the fallback the iPad displays for an unnamed document, it
 * sorts, and it cannot be read as a different day in another locale.
 *
 * A second note on the same day takes "(2)", a third "(3)". The date stays the name — the suffix
 * only says which one, and it is computed against the names ALREADY IN THE SHELF so it never
 * duplicates one that is there. Renaming remains the point: this is what a note is called until
 * you have something better to call it.
 */
object LedgerDefaultName {
    fun forDate(date: java.time.LocalDate, taken: Collection<String>): String {
        val base = LedgerDocuments.dateTitle(date)   // ONE spelling of date-as-title
        if (taken.none { it == base }) return base
        // "-1, -2, -3" (Michael, 2026-08-27: a day's later notes are "named for the day with a
        // -1 or similar such -2, -3"). Display name only; keys never carry it. Names minted
        // under the old " (2)" spelling keep it — a name is never rewritten.
        var n = 1
        while (taken.any { it == "$base -$n" }) n++
        return "$base -$n"
    }
}
