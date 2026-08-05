package com.toolsboox.plugin.calendar.ot

import androidx.navigation.fragment.findNavController
import com.toolsboox.R
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.ui.plugin.ScreenFragment
import java.time.LocalDate

/**
 * THE WALK — one list, read by everything that steps: the ⚡ fast-lane, the stepper's ↑/↓, the
 * pager arrows and the finger swipes.
 *
 * It used to live inside `CalendarDayFragment` as a list of note-page keys, which was fine while
 * every station was a page in the day file. Michael's punchlist of 2026-08-04 ended that: "Day
 * Skipper doesn't include Self Executive, Quick Wins, Missed Connections, then Gratitude." Two of
 * those four are not day pages at all — Quick Wins and Missed Connections are their own fragments —
 * so a walk that can only name pages could never contain them.
 *
 * Hence [Station.open]. A station knows how to be arrived at, and the stepper does not care whether
 * that means a page key or a nav action. The walk is now exactly the Daily folder, in the order
 * Daily lists it, which is the point: **Daily IS the walk**, and two ways of saying the same order
 * would eventually disagree — they already had, which is what put this on the punchlist.
 *
 * Off-walk pages (the numbered notes tail, named boards, every Synthesize page) fold onto the walk
 * the same way they always did; leaving the walk is not leaving the ledger.
 */
object RitualWalk {

    /**
     * One stop. [key] is the identity used to ask "where am I" — for a day page it is the note-page
     * key, for a surface it is a name the surface's own fragment declares.
     */
    data class Station(
        val key: String,
        val glyph: String,
        val label: String,
        /** True when this station is a page in the day file rather than a surface of its own. */
        val isDayPage: Boolean,
    ) {
        /** Arrive here from anywhere. */
        fun open(fragment: ScreenFragment, date: LocalDate = LocalDate.now()) {
            if (isDayPage) {
                CalendarNavigator.toDayNote(fragment, date, key)
                return
            }
            // A surface station. A dead nav action must never be a silent flash — the walk is a
            // thing you tap repeatedly, and one stop that does nothing reads as the whole stepper
            // being broken.
            val action = when (key) {
                QUICK_WINS -> R.id.action_to_quick_wins
                MISSED -> R.id.action_to_missed_rhizomes
                else -> return
            }
            runCatching { fragment.findNavController().navigate(action) }
        }
    }

    const val QUICK_WINS = "quickwins"
    const val MISSED = "missed"

    /**
     * The walk, in order — the same order, and the same membership, as the Daily folder.
     *
     * Self Executive earns its place back here on Michael's instruction rather than on its usage
     * count (4 days in 124). That is the right call and worth saying plainly: the measurement that
     * trimmed this walk to four stations could see how often a page was WRITTEN and could not see
     * what it was for. Self Executive is a page he keeps deliberately.
     */
    val ALL: List<Station> = listOf(
        Station("intake", "★", "All Stars", isDayPage = true),
        Station(CalendarDayPageNotes.GRAM_PICKS, "◈", "Gram Picks", isDayPage = true),
        Station("pickings", "❝", "Pickings", isDayPage = true),
        Station("selfexec", "🐘", "Self Executive", isDayPage = true),
        Station(QUICK_WINS, "⚡", "Quick Wins", isDayPage = false),
        Station(MISSED, "✧", "Missed Connections", isDayPage = false),
        Station("gratitude", "🙏", "Gratitude", isDayPage = true),
    )

    /** The day-page keys on the walk — what "is this page a station" asks. */
    val pageKeys: List<String> = ALL.filter { it.isDayPage }.map { it.key }

    fun station(key: String?): Station? = ALL.firstOrNull { it.key == key }

    fun indexOf(key: String?): Int = ALL.indexOfFirst { it.key == key }

    /** The station after [key], or null at the end of the walk. */
    fun next(key: String?): Station? {
        val i = indexOf(key)
        return if (i < 0) null else ALL.getOrNull(i + 1)
    }

    /** The station before [key], or null at the head of the walk. */
    fun prev(key: String?): Station? {
        val i = indexOf(key)
        return if (i <= 0) null else ALL.getOrNull(i - 1)
    }

    val first: Station get() = ALL.first()
    val last: Station get() = ALL.last()

    /**
     * The ↑/↓ pair a SURFACE station wears on its rail, so the walk continues through it.
     *
     * The day pages get their stepping from the day fragment's own stepper, pager and swipes. A
     * surface station has none of that machinery, and without these two buttons the walk would
     * dead-end on it — you could step onto Quick Wins and not step off, which is worse than
     * leaving it off the walk altogether.
     *
     * Stepping back off the FIRST station and forward off the LAST are both offered rather than
     * hidden: the walk's ends lead somewhere (the day page behind it, the notes tail ahead of it),
     * and a button that vanishes at the edges makes the rail flicker as you walk.
     */
    fun stepItems(
        fragment: ScreenFragment,
        stationKey: String,
        date: LocalDate = LocalDate.now(),
    ): List<com.toolsboox.ot.TuckPanel.Item> {
        val back = prev(stationKey)
        val fwd = next(stationKey)
        val items = mutableListOf<com.toolsboox.ot.TuckPanel.Item>()
        items += com.toolsboox.ot.TuckPanel.Item(
            0, back?.label ?: "Back", glyph = "↑",
        ) {
            if (back != null) back.open(fragment, date)
            else CalendarNavigator.toDayPage(fragment, date, CalendarDay.DEFAULT_STYLE)
        }
        items += com.toolsboox.ot.TuckPanel.Item(
            0, fwd?.label ?: "Notes", glyph = "↓",
        ) {
            if (fwd != null) fwd.open(fragment, date)
            else CalendarNavigator.toDayNote(fragment, date, "0")
        }
        return items
    }
}
