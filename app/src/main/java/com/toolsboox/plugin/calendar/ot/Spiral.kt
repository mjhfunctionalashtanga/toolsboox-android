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
        // Absent means never shown, so the FIRST showing must store step 0 — the one-day wait.
        // Defaulting to 0 here and storing step+1 skipped STEPS[0] entirely: a thing shown once
        // waited three days, and the documented 1/3/7/16/35 was really 3/7/16/35/35.
        val step = entry.optInt("step", -1)
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
     * How much more a word in your own annotation counts than one in the passage you marked.
     *
     * Four, so that ONE word of yours outweighs three of the source's. That ratio is the whole
     * claim: when you highlight a long passage and write two words about it, the passage's
     * vocabulary is the author's — incidental, what the book happened to be about — while the two
     * words are what you were thinking. At 3.0 a six-word match on the author's prose tied with a
     * two-word match on yours, and a tie goes to whichever was scanned first, which is no rule at
     * all.
     */
    private const val OWN_WEIGHT = 4.0

    /**
     * What the spiral is allowed to draw on: things you MADE or MARKED.
     *
     * Notably excludes [Section.FEED] — the offline article cache holds everything that synced,
     * read or not. Letting that in would be quietly disastrous here: "what you've been circling"
     * would be computed from other people's writing, and the card would start resurfacing articles
     * you never chose to look at. That is the walled-garden dynamic wearing a different hat, which
     * is the one thing this must not become.
     *
     * [Section.ARTICLES] stays, because those are your annotations ON articles — your words about
     * someone else's, which is exactly the kind of thing worth having come back.
     *
     * Ask itself may still read the whole cache; being able to answer a question you asked from
     * what you read is fine. Pushing it at you unbidden is not the same act.
     */
    val SCOPE: Set<com.toolsboox.plugin.chat.da.Section> = setOf(
        com.toolsboox.plugin.chat.da.Section.BOOKS,       // book highlights
        com.toolsboox.plugin.chat.da.Section.ARTICLES,    // your annotations on articles
        com.toolsboox.plugin.chat.da.Section.PLANNER,     // what you wrote on the pages
        com.toolsboox.plugin.chat.da.Section.MEDIA,       // A/V grams
        com.toolsboox.plugin.chat.da.Section.SECTIONS,    // OCR'd handwriting
        com.toolsboox.plugin.chat.da.Section.NOTES,       // text notes
        com.toolsboox.plugin.chat.da.Section.ANNOTATIONS  // roster/margin notes in your own hand
        // NOT Section.TASKS. Tasks and events are logistics — "dentist at three", "email Bob".
        // They are things to DO, not things to reconsider: a finished errand coming back around
        // teaches nothing, and their vocabulary swamps the roots with scheduling words, which
        // recur constantly and mean nothing about what you are actually thinking about.
    )

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
     * Drop repeats before anything looks at the ledger.
     *
     * The same words can reach the corpus by more than one road — a picking that was also OCR'd
     * into page sections, a note that is also a text box on the day — and the gatherer has no way
     * to know they are the same thing. Left alone, one item shows up as three: three times in the
     * roots, three chances of being picked, and a thread whose "size" is really one thought
     * counted repeatedly.
     *
     * Matched on the words themselves, normalised, because that is the only thing the copies
     * reliably share. The first of each set wins, so whichever section got there first keeps it.
     */
    fun <T> dedupe(items: List<T>, textOf: (T) -> String): List<T> {
        val seen = HashSet<String>()
        return items.filter { seen.add(fingerprint(textOf(it))) }
    }

    /**
     * The significant words, sorted and deduplicated.
     *
     * Not the text itself: handwriting OCR reads the same line differently on different passes, so
     * "se be explore mile minded" and "ge Explore mile minded" are one thought that arrived twice.
     * Comparing exact strings keeps both; comparing the set of real words collapses them, because
     * what the two readings agree on IS the thought.
     */
    private fun fingerprint(text: String): String =
        significantWords(text).sorted().take(12).joinToString(" ")

    private fun significantWords(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 && it !in STOP }
            .toSet()

    /**
     * Is there enough here to be worth handing back?
     *
     * A day page's OCR leaves fragments — a row of dots, half a word, a stray "tore....." — and
     * they are not thoughts. Requiring a few real words is a cheap, honest filter: it costs the
     * occasional terse note and removes an enormous amount of noise that would otherwise be
     * resurfaced at you as if it meant something.
     */
    fun isSubstantial(text: String): Boolean = significantWords(text).size >= 3

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
        /** The reader's own words about the item, if any — weighted above the source's. */
        ownOf: (T) -> String = { "" },
        now: Long = System.currentTimeMillis(),
        /**
         * Extra weight from the roots — see [Rhizome.bridgeScore]. An object that JOINS two
         * threads teaches more than another member of a thread you're already inside, so the
         * spiral prefers a connector when it can find one.
         */
        bonusOf: (T) -> Double = { 0.0 }
    ): Pick<T>? {
        if (items.isEmpty()) return null

        val recentCut = now - TimeUnit.DAYS.toMillis(RECENT_DAYS)
        val recent = items.filter { dateOf(it) >= recentCut }

        // What you've been circling: how many distinct recent items each word appears in. Counting
        // items rather than occurrences stops one long note from deciding the whole theme.
        //
        // Your OWN words count for more. A marked passage is someone else's writing that happened
        // to be worth marking; the note you attached is why. The star pipeline on mjh.yoga makes
        // the same call — it treats the reason you saved a thing as the lens for everything it
        // then does with it — and the Notes Bot is told the same: the annotation is the
        // commentary, the body is only source material.
        val warmth = HashMap<String, Int>()
        val ownTerms = HashSet<String>()
        for (r in recent) {
            val own = terms(ownOf(r)).toSet()
            ownTerms += own
            for (t in terms(textOf(r)).toSet() + own) warmth[t] = (warmth[t] ?: 0) + 1
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
            // Rare warm words are worth more than common ones, and a word of YOUR OWN is worth
            // more than one from a passage you merely marked. The sum already grows with how many
            // words match, so multiplying by the count again would let one long quotation of
            // someone else's prose outvote the two words that said why it mattered.
            val score = shared.sumOf {
                (1.0 / (1.0 + (warmth[it] ?: 1))) * (if (it in ownTerms) OWN_WEIGHT else 1.0)
            } * (1.0 + bonusOf(candidate))
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
        // …and it must not be the pick itself. On a young ledger almost everything falls inside the
        // recent window, so the fallback puts recent items in the running — and without this the
        // card cheerfully told you a thing rhymed with itself.
        val bestFingerprint = fingerprint(textOf(best))
        val echo = recent
            .filter { fingerprint(textOf(it)) != bestFingerprint }
            .maxByOrNull { r -> terms(textOf(r)).toSet().count { it in bestTerms } }
            ?.takeIf { r -> terms(textOf(r)).toSet().any { it in bestTerms } }

        return Pick(best, echo, bestShared)
    }
}
