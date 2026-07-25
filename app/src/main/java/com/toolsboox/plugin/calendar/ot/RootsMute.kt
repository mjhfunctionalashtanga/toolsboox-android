package com.toolsboox.plugin.calendar.ot

import android.content.Context

/**
 * Words you have decided are not subjects.
 *
 * A thread is a word that recurs, which is a modest enough claim to be checkable — and checking it
 * against a real ledger showed the top of the list reading `said · 2026 · first · july · internet ·
 * pressreader · article · http`. Most of that is newspaper datelines and the plumbing of the
 * clipping pipeline, not anything anyone is thinking about.
 *
 * The instinct is to lengthen the built-in stop-list. That is the wrong instinct: an aggressive
 * stop-list starts throwing away the vocabulary a person actually thinks in, and no list written
 * by someone else can know that "practice" is a subject here and "units" is admin. The person
 * reading the roots is the only one who can tell, and they can tell instantly — so let them say
 * so, once, per word.
 *
 * Muting is not deleting. The item stays in the corpus and still surfaces on its own merits; the
 * WORD just stops being offered as a thing you keep coming back to.
 */
object RootsMute {

    private const val PREFS = "ledger_roots"
    private const val KEY = "muted"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun muted(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.map { it.lowercase() }?.toSet() ?: emptySet()

    fun isMuted(context: Context, term: String): Boolean = term.lowercase() in muted(context)

    /** Flip a word's state. Returns true if it is now muted. */
    fun toggle(context: Context, term: String): Boolean {
        val t = term.lowercase()
        val next = muted(context).toMutableSet()
        val nowMuted = if (t in next) { next.remove(t); false } else { next.add(t); true }
        prefs(context).edit().putStringSet(KEY, next).apply()
        return nowMuted
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    // ─── Dismissed crossings ──────────────────────────────────────────────────
    //
    // Muting a WORD tidies the threads; sometimes a whole meeting-point is just junk — two
    // newspaper snippets that share a stray term and mean nothing together. You can't un-say the
    // text, but you can tell the roots to stop offering this particular crossing. Keyed by the
    // snippet's own citation, which is stable across reloads.

    private const val KEY_DISMISSED = "dismissed_crossings"

    fun dismissedCrossings(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISMISSED, emptySet())?.toSet() ?: emptySet()

    fun dismissCrossing(context: Context, id: String) {
        val next = dismissedCrossings(context).toMutableSet()
        next.add(id)
        prefs(context).edit().putStringSet(KEY_DISMISSED, next).apply()
    }

    // ─── Shown history ────────────────────────────────────────────────────────
    //
    // What the Roots board actually put on screen, for the last few days. Not a preference —
    // bookkeeping, so today's board can DEMOTE what yesterday's already showed instead of
    // re-offering the same eighteen words forever. Demote, never exclude: a thread shown
    // yesterday sorts to the back of its band, but when it is genuinely the day's material
    // (hot enough, or the pool is thin) it still appears. Entries are "epochDay:term".

    private const val KEY_SHOWN = "shown_history"

    /** How many days back a showing still counts against a thread. */
    const val SHOWN_HISTORY_DAYS = 5

    /**
     * term (lowercase) → the most recent epoch-day it was on the board, drawn only from days
     * strictly before [beforeEpochDay]. Today's own showings must not demote today — the page
     * has to stay stable across reopens within the day.
     */
    fun shownBefore(context: Context, beforeEpochDay: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (e in prefs(context).getStringSet(KEY_SHOWN, emptySet()).orEmpty()) {
            val day = e.substringBefore(':').toLongOrNull() ?: continue
            val term = e.substringAfter(':', "")
            if (term.isEmpty() || day >= beforeEpochDay) continue
            val prev = out[term]
            if (prev == null || day > prev) out[term] = day
        }
        return out
    }

    /**
     * Record the board for [epochDay]. Replaces any earlier record for the same day — a mute
     * flip mid-day recomposes the board, and the last composition is the true one — and drops
     * anything older than [SHOWN_HISTORY_DAYS], so the store stays a few dozen short strings.
     */
    fun recordShown(context: Context, epochDay: Long, terms: Collection<String>) {
        val kept = prefs(context).getStringSet(KEY_SHOWN, emptySet()).orEmpty()
            .filter {
                val d = it.substringBefore(':').toLongOrNull()
                d != null && d != epochDay && epochDay - d in 0..SHOWN_HISTORY_DAYS.toLong()
            }
        val next = (kept + terms.map { "$epochDay:${it.lowercase()}" }).toSet()
        prefs(context).edit().putStringSet(KEY_SHOWN, next).apply()
    }
}
