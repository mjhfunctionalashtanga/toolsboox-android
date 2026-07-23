package com.toolsboox.plugin.calendar.ot

import java.util.concurrent.TimeUnit

/**
 * The roots under the ledger: which things belong with which, and which threads are alive.
 *
 * A **thread** is a word that recurs across several objects you made at different times. That is a
 * deliberately modest definition — it is not a topic model and does not pretend to understand
 * anything. It says "you keep coming back to this word, and here is everywhere you did," which is
 * a claim that can be checked by eye. Anything cleverer would be guessing in a way you couldn't
 * audit, and an unauditable map of your own thinking is worse than none.
 *
 * Built with an inverted index rather than comparing every object to every other: a ledger with a
 * few thousand entries has millions of pairs, and almost all of them share nothing.
 */
object Rhizome {

    /** A word that keeps coming back, and everywhere it came back. */
    data class Thread(
        val term: String,
        val members: List<Int>,
        val firstAt: Long,
        val lastAt: Long
    ) {
        val size: Int get() = members.size

        /** Days from the first appearance to the most recent. */
        val spanDays: Long get() = TimeUnit.MILLISECONDS.toDays((lastAt - firstAt).coerceAtLeast(0))

        /** Quiet when nothing has joined it lately — a thread that may want picking back up. */
        fun isQuiet(now: Long): Boolean = now - lastAt > TimeUnit.DAYS.toMillis(45)

        /**
         * How much a thread is worth showing. Longevity over volume: a word used across six months
         * is a preoccupation, whereas one used ten times in a fortnight is usually a project — and
         * you already know what you did this fortnight.
         */
        val heat: Double get() = size * (1.0 + spanDays / 90.0)
    }

    /** Words too common to carry meaning. Kept short — see [Spiral.STOP] for why. */
    private val STOP = setOf(
        "this", "that", "with", "from", "have", "what", "when", "were", "they", "them", "then",
        "there", "here", "your", "yours", "about", "would", "could", "should", "been", "being",
        "into", "over", "under", "just", "like", "than", "some", "more", "most", "much", "very",
        "also", "only", "even", "still", "make", "made", "does", "doing", "done", "each", "which",
        "while", "after", "before", "because", "these", "those", "their", "will", "wont", "cant",
        "dont", "didnt", "thing", "things", "really", "something", "anything", "everything",
        "going", "know", "think", "want", "need", "good", "well", "back", "down", "time", "today"
    )

    private fun terms(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 && it !in STOP }
            .toSet()

    /**
     * Find the threads.
     *
     * [minMembers] is the floor for calling something a thread at all — two mentions is a
     * coincidence. Words appearing in more than [ceilingFraction] of everything are dropped: they
     * are how you write, not what you are on about.
     */
    fun threads(
        texts: List<String>,
        dates: List<Long>,
        minMembers: Int = 3,
        ceilingFraction: Double = 0.25,
        limit: Int = 40
    ): List<Thread> {
        if (texts.isEmpty() || texts.size != dates.size) return emptyList()

        val index = HashMap<String, MutableList<Int>>()
        for (i in texts.indices) {
            for (t in terms(texts[i])) index.getOrPut(t) { mutableListOf() }.add(i)
        }

        val ceiling = (texts.size * ceilingFraction).coerceAtLeast(3.0)

        return index.asSequence()
            .filter { it.value.size >= minMembers && it.value.size <= ceiling }
            .map { (term, members) ->
                val ds = members.map { dates[it] }
                Thread(term, members, ds.min(), ds.max())
            }
            .sortedByDescending { it.heat }
            .take(limit)
            .toList()
    }

    /**
     * Objects that sit in more than one thread.
     *
     * These are the joins — where two preoccupations touch. They are the most interesting things
     * in a ledger and the hardest to find by scrolling, because nothing about them looks special
     * in a list.
     */
    fun crossings(threads: List<Thread>, minThreads: Int = 2): Map<Int, List<String>> {
        val byItem = HashMap<Int, MutableList<String>>()
        for (t in threads) for (m in t.members) byItem.getOrPut(m) { mutableListOf() }.add(t.term)
        return byItem.filterValues { it.size >= minThreads }
    }

    /**
     * A bonus for an object that would join two threads which have not been brought together
     * recently — the spiral's reason to prefer a bridge over more of the same. Surfacing a
     * connector teaches more than surfacing another member of a thread you're already inside.
     */
    fun bridgeScore(itemThreads: List<String>, warmThreads: Set<String>): Double {
        if (itemThreads.size < 2) return 0.0
        val warm = itemThreads.count { it in warmThreads }
        val cold = itemThreads.size - warm
        // Best case: it touches something you're in now AND something you aren't.
        return if (warm > 0 && cold > 0) 1.0 + cold * 0.25 else 0.0
    }
}
