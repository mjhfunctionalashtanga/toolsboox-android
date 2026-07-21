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
}
