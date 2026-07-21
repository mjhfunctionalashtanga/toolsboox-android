package com.toolsboox

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.toolsboox.plugin.calendar.ot.Spiral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The spiral's promises are all about WHEN and WHY, which is exactly the kind of thing that looks
 * fine on the page and is wrong in practice. These pin them down:
 *
 *  - a thing comes back at a widening interval, and never faster than its step allows
 *  - what comes back is chosen by what you've been circling, not by age alone
 *  - your own words about a thing count for more than the thing itself
 *  - nothing about it punishes you for ignoring it
 */
class SpiralTest {

    /** An item as the learner card sees one. */
    private data class Snip(val text: String, val own: String, val ageDays: Long, val id: String)

    // mark() stamps System.currentTimeMillis(), so the fake clock has to start from the real one
    // or every interval comparison is a year out.
    private val now = System.currentTimeMillis()
    private fun daysAgo(n: Long) = now - TimeUnit.DAYS.toMillis(n)

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = FakePrefsContext()
    }

    private fun choose(items: List<Snip>, bonus: (Snip) -> Double = { 0.0 }) =
        Spiral.choose(
            context, items,
            textOf = { it.text },
            dateOf = { daysAgo(it.ageDays) },
            keyOf = { it.id },
            ownOf = { it.own },
            now = now,
            bonusOf = bonus
        )

    // --- the schedule ---------------------------------------------------------------------------

    @Test
    fun something_never_shown_is_due() {
        assertTrue(Spiral.isDue(context, "fresh", now))
    }

    @Test
    fun once_shown_it_waits_and_then_widens() {
        Spiral.mark(context, "k")
        assertFalse("not due the same day", Spiral.isDue(context, "k", now))
        // step 0 → 1 day
        assertTrue("due a day later", Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(1) + 1_000))

        // Show it again: the gap widens to 3 days, so one day is no longer enough.
        Spiral.mark(context, "k")
        assertFalse("interval widened past a day", Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(1)))
        assertTrue("due after three", Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(3) + 1_000))
    }

    @Test
    fun again_pulls_it_back_in() {
        // Four showings walk 1 → 3 → 7 → 16. (Three would only reach 7; this assertion was
        // written against the off-by-one, when the first showing wrongly consumed step 1.)
        repeat(4) { Spiral.mark(context, "k") }
        assertFalse(Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(7)))

        Spiral.mark(context, "k", closer = true)          // back to 7
        assertTrue("Again should shorten the wait", Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(7) + 1_000))
    }

    @Test
    fun the_interval_stops_widening_at_five_weeks() {
        repeat(20) { Spiral.mark(context, "k") }
        assertFalse(Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(34)))
        assertTrue(
            "caps at 35 days however many times it's seen",
            Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(35) + 1_000)
        )
    }

    @Test
    fun retiring_puts_it_away_for_the_longest_interval() {
        Spiral.retire(context, "k")
        assertFalse(Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(34)))
    }

    @Test
    fun ignoring_it_costs_nothing() {
        // Nothing accumulates against you: after a long silence the same item is simply due,
        // exactly as it would have been the day after its interval elapsed.
        Spiral.mark(context, "k")
        assertTrue(Spiral.isDue(context, "k", now + TimeUnit.DAYS.toMillis(400)))
    }

    // --- what comes back ------------------------------------------------------------------------

    @Test
    fun it_prefers_what_you_have_been_circling_over_what_is_merely_old() {
        val items = listOf(
            // Oldest, but on a subject nothing recent touches.
            Snip("bookkeeping ledgers invoices spreadsheets", "", 300, "old-unrelated"),
            // Newer than that, but it rhymes with the recent work.
            Snip("kapotasana and the shoulder opening slowly", "", 120, "old-related"),
            // Recent — the warmth source. Cannot itself be picked.
            Snip("shoulder again today, kapotasana felt closer", "", 3, "recent")
        )

        val pick = choose(items)

        assertNotNull(pick)
        assertEquals("the related one should win over the merely older", "old-related", pick!!.item.id)
        assertEquals("and it should name the recent thing it rhymes with", "recent", pick.echo?.id)
        assertTrue("and say which words did it", pick.shared.isNotEmpty())
    }

    @Test
    fun your_own_words_outweigh_the_passage_they_are_on() {
        // The recent item is a long quotation of someone else's prose, with two words of your own.
        // Those two words should steer what comes back — the passage is what you marked, the note
        // is why.
        val quotation = "the author writes at length about supply chains logistics warehousing " +
            "distribution procurement inventory throughput and fulfilment across many pages"
        val items = listOf(
            Snip("supply chains logistics warehousing distribution procurement", "", 200, "matches-passage"),
            Snip("grief in august, the anniversary again", "", 200, "matches-note"),
            Snip(quotation, "grief august", 2, "recent")
        )

        val pick = choose(items)

        assertNotNull(pick)
        assertEquals(
            "the note's subject should outweigh the quoted passage's",
            "matches-note", pick!!.item.id
        )
    }

    @Test
    fun when_nothing_rhymes_it_falls_back_to_the_oldest_and_says_so() {
        val items = listOf(
            Snip("entirely unrelated alpha material", "", 100, "newer"),
            Snip("entirely unrelated beta material", "", 300, "oldest"),
            Snip("recent words sharing nothing", "", 1, "recent")
        )

        val pick = choose(items)

        assertNotNull(pick)
        assertEquals("oldest due wins the fallback", "oldest", pick!!.item.id)
        assertNull("and no rhyme is claimed", pick.echo)
        assertTrue(pick.shared.isEmpty())
    }

    @Test
    fun a_bridge_is_preferred_over_more_of_the_same() {
        val items = listOf(
            Snip("shoulder practice morning", "", 150, "plain"),
            Snip("shoulder practice morning", "", 150, "bridge"),
            Snip("shoulder practice morning again", "", 2, "recent")
        )

        // Identical candidates: only the roots bonus can separate them.
        val pick = choose(items) { if (it.id == "bridge") 2.0 else 0.0 }

        assertEquals("the connector should win the tie", "bridge", pick!!.item.id)
    }

    @Test
    fun something_already_shown_does_not_come_straight_back() {
        val items = listOf(
            Snip("kapotasana shoulder opening", "", 120, "a"),
            Snip("kapotasana shoulder slowly", "", 200, "b"),
            Snip("kapotasana shoulder today", "", 2, "recent")
        )

        val first = choose(items)!!.item.id
        Spiral.mark(context, first)
        val second = choose(items)?.item?.id

        assertTrue("the second pick must not repeat the first", second != first)
    }

    @Test
    fun the_scope_is_what_you_made_or_marked_and_not_your_diary_admin() {
        val scope = Spiral.SCOPE
        assertFalse(
            "the reader is other people's writing arriving unbidden",
            com.toolsboox.plugin.chat.da.Section.FEED in scope
        )
        assertFalse(
            "tasks and events are logistics, not thinking — a finished errand teaches nothing",
            com.toolsboox.plugin.chat.da.Section.TASKS in scope
        )
        assertTrue("your annotations on articles are yours", com.toolsboox.plugin.chat.da.Section.ARTICLES in scope)
        assertTrue("book highlights are yours", com.toolsboox.plugin.chat.da.Section.BOOKS in scope)
    }

    @Test
    fun an_empty_ledger_returns_nothing_rather_than_throwing() {
        assertNull(choose(emptyList()))
    }

    // --- fakes ----------------------------------------------------------------------------------

    /** In-memory prefs: the schedule is the thing under test, not Android's storage. */
    private class FakePrefsContext : ContextWrapper(null) {
        private val stores = HashMap<String, FakePrefs>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            stores.getOrPut(name) { FakePrefs() }
    }

    private class FakePrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getStringSet(k: String?, d: MutableSet<String>?) = values[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = values[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = values[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = values[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = values[k] as? Boolean ?: d
        override fun contains(k: String?) = values.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            override fun putString(k: String?, v: String?) = apply { staged[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { staged[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { staged[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { staged[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { staged[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { staged[k!!] = v }
            override fun remove(k: String?) = apply { removed.add(k!!) }
            override fun clear() = apply { removed.addAll(values.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                for (k in removed) values.remove(k)
                values.putAll(staged)
            }
        }
    }
}
