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
}
