package com.toolsboox

import com.toolsboox.plugin.calendar.ot.Rhizome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The roots claim something specific — "you keep coming back to this word, and here is everywhere
 * you did" — and the whole point of keeping the claim modest is that it can be checked. These
 * check it.
 */
class RhizomeTest {

    private fun daysAgo(n: Long, now: Long = 1_753_000_000_000L) = now - TimeUnit.DAYS.toMillis(n)

    @Test
    fun a_word_in_enough_items_becomes_a_thread() {
        val texts = listOf(
            "the mysore room at dawn, counting breath",
            "counting breath again, this time alone",
            "breath is the only instrument here",
            "nothing whatsoever in common"
        )
        val dates = listOf(daysAgo(90), daysAgo(60), daysAgo(30), daysAgo(10))

        val threads = Rhizome.threads(texts, dates, minMembers = 3)
        val breath = threads.firstOrNull { it.term == "breath" }

        assertTrue("breath recurs three times and should be a thread", breath != null)
        assertEquals(3, breath!!.size)
        assertEquals(listOf(0, 1, 2), breath.members)
    }

    @Test
    fun two_mentions_is_a_coincidence_not_a_thread() {
        val texts = listOf("kapotasana today", "kapotasana again", "something else entirely")
        val dates = listOf(daysAgo(30), daysAgo(20), daysAgo(10))

        val threads = Rhizome.threads(texts, dates, minMembers = 3)

        assertNull("two mentions must not qualify", threads.firstOrNull { it.term == "kapotasana" })
    }

    @Test
    fun a_word_in_everything_is_how_you_write_not_what_youre_on_about() {
        // "practice" in every item; "grief" in a third of them. The ubiquitous one must be dropped
        // by the ceiling, or the roots would be topped by a word that distinguishes nothing.
        val texts = (1..12).map { i ->
            if (i % 4 == 0) "practice today and grief underneath" else "practice today, ordinary"
        }
        val dates = (1..12).map { daysAgo(it.toLong() * 5) }

        val threads = Rhizome.threads(texts, dates, minMembers = 3, ceilingFraction = 0.25)

        assertNull("a word in all 12 must be dropped", threads.firstOrNull { it.term == "practice" })
        assertTrue("a word in 3 of 12 survives", threads.any { it.term == "grief" })
    }

    @Test
    fun longevity_outranks_volume() {
        // A word used 4 times across six months should beat one used 5 times inside a fortnight:
        // the fortnight one is a project you already remember, the long one is a preoccupation.
        val texts = mutableListOf<String>()
        val dates = mutableListOf<Long>()
        repeat(4) { i ->
            texts += "returning to shoulder over and over"
            dates += daysAgo(180L - i * 55)
        }
        repeat(5) {
            texts += "sprint deadline burndown scheduling"
            dates += daysAgo(10L - it)
        }

        val threads = Rhizome.threads(texts, dates, minMembers = 3, ceilingFraction = 0.9)
        val shoulder = threads.first { it.term == "shoulder" }
        val sprint = threads.first { it.term == "sprint" }

        assertTrue(
            "the long-running thread should rank above the burst (${shoulder.heat} vs ${sprint.heat})",
            shoulder.heat > sprint.heat
        )
    }

    @Test
    fun a_thread_nothing_has_joined_lately_reads_as_quiet() {
        val now = 1_753_000_000_000L
        val texts = List(3) { "samyama attention gathering" }
        val dates = listOf(daysAgo(200, now), daysAgo(150, now), daysAgo(120, now))

        val t = Rhizome.threads(texts, dates, minMembers = 3).first { it.term == "samyama" }

        assertTrue("last touched 120 days ago — quiet", t.isQuiet(now))
        assertFalse("not quiet as of its own last day", t.isQuiet(daysAgo(119, now)))
    }

    @Test
    fun crossings_find_the_item_sitting_in_two_threads() {
        // Item 2 is the only one carrying BOTH words — the join, and the thing a list never shows.
        val texts = listOf(
            "grief in the morning", "grief again", "grief and the breath together",
            "breath counting", "breath and nothing else"
        )
        val dates = List(5) { daysAgo((it + 1).toLong() * 20) }

        val threads = Rhizome.threads(texts, dates, minMembers = 3, ceilingFraction = 0.9)
        val crossings = Rhizome.crossings(threads, minThreads = 2)

        assertTrue("item 2 joins grief and breath", crossings.containsKey(2))
        assertEquals(setOf("grief", "breath"), crossings[2]!!.toSet())
        assertFalse("item 0 is only in one thread", crossings.containsKey(0))
    }

    @Test
    fun a_bridge_scores_only_when_it_reaches_somewhere_youre_not() {
        val warm = setOf("grief", "practice")

        // Touches a warm thread AND a cold one: the case worth surfacing.
        assertTrue(Rhizome.bridgeScore(listOf("grief", "shoulder"), warm) > 0.0)
        // Both warm — more of what you're already inside, no bonus.
        assertEquals(0.0, Rhizome.bridgeScore(listOf("grief", "practice"), warm), 0.0001)
        // Both cold — nothing to bridge FROM.
        assertEquals(0.0, Rhizome.bridgeScore(listOf("shoulder", "elbow"), warm), 0.0001)
        // A single thread cannot be a bridge.
        assertEquals(0.0, Rhizome.bridgeScore(listOf("grief"), warm), 0.0001)
        // Reaching two cold threads beats reaching one.
        assertTrue(
            Rhizome.bridgeScore(listOf("grief", "shoulder", "elbow"), warm) >
                Rhizome.bridgeScore(listOf("grief", "shoulder"), warm)
        )
    }

    @Test
    fun empty_and_mismatched_input_are_survivable() {
        assertTrue(Rhizome.threads(emptyList(), emptyList()).isEmpty())
        assertTrue("length mismatch must not throw", Rhizome.threads(listOf("a"), emptyList()).isEmpty())
    }
}
