package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray

/**
 * Feeds that carry your OWN material back to you.
 *
 * Most of a reader is other people's writing arriving unbidden, which is why [Spiral] refuses to
 * draw on the feed cache. But one feed can be different: mjh.yoga publishes a secret-gated RSS of
 * the `note` CPT, so the things already starred, annotated and angled come back down the same pipe
 * as everything else. Those are yours — they were admitted by a deliberate act — and shutting them
 * out would be the rule defeating its own purpose.
 *
 * So the distinction the spiral actually needs isn't "feed vs. not-feed", it's **whether a human
 * chose it**. Marking a feed as your own is that declaration, made once, by hand.
 */
object OwnFeeds {

    private const val PREFS = "ledger_own_feeds"
    private const val KEY = "titles"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Feed titles whose items count as your own material. */
    fun all(context: Context): Set<String> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun isOwn(context: Context, feedTitle: String): Boolean =
        feedTitle.isNotBlank() && all(context).any { it.equals(feedTitle, ignoreCase = true) }

    fun toggle(context: Context, feedTitle: String): Boolean {
        if (feedTitle.isBlank()) return false
        val current = all(context).toMutableSet()
        val nowOwn = if (current.any { it.equals(feedTitle, ignoreCase = true) }) {
            current.removeAll { it.equals(feedTitle, ignoreCase = true) }; false
        } else {
            current.add(feedTitle); true
        }
        val arr = JSONArray()
        for (t in current) arr.put(t)
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        return nowOwn
    }
}
