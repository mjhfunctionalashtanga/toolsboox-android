package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What comes back around, and when.
 *
 * Spiral learning: a thing you wrote once returns later, and again later still, so understanding
 * layers instead of being filed and forgotten. Spaced repetition supplies the "when".
 *
 * Deliberately NOT a study app. There is no score, no streak, no ease factor, nothing that
 * punishes a missed day — those mechanics belong to the attention economy this is meant to be an
 * escape from. The whole contract is: the right old thing, at a reasonable interval, offered
 * once. Ignore it for three weeks and nothing is broken or lost; the card simply waits.
 *
 * State is one small JSON of `id -> {seen, step}` in plain prefs. If it is ever deleted, the
 * worst outcome is that everything looks new again, which is a fine failure.
 */
object Spiral {

    private const val PREFS = "ledger_spiral"
    private const val KEY = "state"

    /**
     * Days between one showing and the next. Widening, and it stops at five weeks — past that a
     * thing has either stuck or stopped mattering, and pretending otherwise is bookkeeping for
     * its own sake.
     */
    private val STEPS = intArrayOf(1, 3, 7, 16, 35)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun state(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun save(context: Context, o: JSONObject) {
        prefs(context).edit().putString(KEY, o.toString()).apply()
    }

    /** A stable key for a snippet — its citation plus a prefix of its text. */
    fun keyOf(citation: String, text: String): String =
        (citation + "|" + text.take(64)).hashCode().toString()

    /** True when this one is ready to come back (or has never been shown). */
    fun isDue(context: Context, key: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = state(context).optJSONObject(key) ?: return true
        val seen = entry.optLong("seen", 0L)
        val step = entry.optInt("step", 0).coerceIn(0, STEPS.size - 1)
        return now - seen >= TimeUnit.DAYS.toMillis(STEPS[step].toLong())
    }

    /**
     * Record that it was shown and push it one step out.
     *
     * [closer] pulls it back in a step instead — for "this one hasn't landed yet". That is the
     * only knob, and it is a preference rather than a grade.
     */
    fun mark(context: Context, key: String, closer: Boolean = false) {
        val o = state(context)
        val entry = o.optJSONObject(key) ?: JSONObject()
        val step = entry.optInt("step", 0)
        entry.put("seen", System.currentTimeMillis())
        entry.put("step", (if (closer) step - 1 else step + 1).coerceIn(0, STEPS.size - 1))
        o.put(key, entry)

        // Keep the ledger of what-was-seen from outliving its usefulness: past a few thousand
        // entries, drop the oldest. This is a cache of attention, not a record worth keeping.
        if (o.length() > 4000) {
            val byAge = o.keys().asSequence()
                .map { it to (o.optJSONObject(it)?.optLong("seen", 0L) ?: 0L) }
                .sortedBy { it.second }
                .take(o.length() - 3000)
            for ((k, _) in byAge) o.remove(k)
        }

        save(context, o)
    }

    /** Put it away for good — some things resurface once and that's plainly enough. */
    fun retire(context: Context, key: String) {
        val o = state(context)
        o.put(key, JSONObject().put("seen", System.currentTimeMillis()).put("step", STEPS.size - 1))
        save(context, o)
    }

    // --- Choosing what comes back ----------------------------------------------------------------

    /**
     * What surfaced, and the recent thing it rhymes with.
     *
     * [echo] is the point. A queue hands you an old note; a spiral hands you an old note *because
     * of what you have been circling lately*, and says which. That connection is where the
     * layering happens — without it this is just a list in a slow order.
     */
    data class Pick<T>(val item: T, val echo: T?, val shared: List<String>)

    private const val RECENT_DAYS = 21L
    private const val MIN_TERM = 4

    /**
     * Words too common to mean anything. Short list on purpose — an aggressive stop-list starts
     * throwing away the vocabulary a person actually thinks in.
     */
    private val STOP = setOf(
        "this", "that", "with", "from", "have", "what", "when", "were", "they", "them", "then",
        "there", "here", "your", "yours", "about", "would", "could", "should", "been", "being",
        "into", "over", "under", "just", "like", "than", "some", "more", "most", "much", "very",
        "also", "only", "even", "still", "make", "made", "made", "does", "doing", "done", "each",
        "which", "while", "after", "before", "because", "these", "those", "their", "will", "wont",
        "cant", "dont", "didnt", "thing", "things", "really", "something", "anything", "everything"
    )

    private fun terms(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= MIN_TERM && it !in STOP }

    /**
     * Choose what comes back around.
     *
     * Builds a picture of what you have been circling in the last few weeks, then prefers a DUE
     * item that shares vocabulary with it — so the thing that returns is on a subject already
     * warm, rather than merely old. Falls back to plain oldest-due when nothing rhymes, which is
     * the honest answer for a ledger that hasn't found its themes yet.
     *
     * Deliberately arithmetic rather than a model call: it must work on a plane, on a Boox, with
     * no key and no network. A persona can make it eloquent later; it should not need one to be
     * useful.
     */
    fun <T> choose(
        context: Context,
        items: List<T>,
        textOf: (T) -> String,
        dateOf: (T) -> Long,
        keyOf: (T) -> String,
        now: Long = System.currentTimeMillis()
    ): Pick<T>? {
        if (items.isEmpty()) return null

        val recentCut = now - TimeUnit.DAYS.toMillis(RECENT_DAYS)
        val recent = items.filter { dateOf(it) >= recentCut }

        // What you've been circling: how many distinct recent items each word appears in. Counting
        // items rather than occurrences stops one long note from deciding the whole theme.
        val warmth = HashMap<String, Int>()
        for (r in recent) {
            for (t in terms(textOf(r)).toSet()) warmth[t] = (warmth[t] ?: 0) + 1
        }

        val due = items.filter { dateOf(it) < recentCut && isDue(context, keyOf(it), now) }
            .ifEmpty { items.filter { isDue(context, keyOf(it), now) } }
        if (due.isEmpty()) return null

        var best: T? = null
        var bestScore = 0.0
        var bestShared = emptyList<String>()

        for (candidate in due) {
            val ts = terms(textOf(candidate)).toSet()
            if (ts.isEmpty()) continue
            val shared = ts.filter { warmth.containsKey(it) }
            if (shared.isEmpty()) continue
            // Rarity matters more than volume: a word in ONE recent note is a thread, a word in
            // twenty is just how you write.
            val score = shared.sumOf { 1.0 / (1.0 + (warmth[it] ?: 1)) } * shared.size
            if (score > bestScore) {
                best = candidate
                bestScore = score
                bestShared = shared.sortedByDescending { warmth[it] ?: 0 }.take(3)
            }
        }

        // Nothing rhymes — hand back the oldest thing that's due and say so by leaving echo null.
        if (best == null) {
            val oldest = due.minByOrNull { dateOf(it) } ?: return null
            return Pick(oldest, null, emptyList())
        }

        // Which recent item it rhymes WITH — the one sharing most of those words.
        val bestTerms = terms(textOf(best)).toSet()
        val echo = recent.maxByOrNull { r -> terms(textOf(r)).toSet().count { it in bestTerms } }
            ?.takeIf { r -> terms(textOf(r)).toSet().any { it in bestTerms } }

        return Pick(best, echo, bestShared)
    }
}
