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
        /** Arrive here on [date]. No default: a step of the walk keeps the page's own day (Michael
         *  2026-10-11: after turning to Wednesday and walking up to All Stars and back down, "the
         *  day page shows that day again, not today"), so every caller must say which day. */
        fun open(fragment: ScreenFragment, date: LocalDate) {
            if (isDayPage) {
                CalendarNavigator.toDayNote(fragment, date, key)
                return
            }
            // TODAY — the schedule face itself (notePage == null), which is a page of the day but
            // not a NOTE page, so it cannot ride toDayNote. It leads the walk (Michael, 2026-08-23:
            // "the page skipper can go today > all stars > gram picks > todays note") because it is
            // the surface everything else is a departure FROM: the day's own dashboard.
            if (key == TODAY) {
                CalendarNavigator.toDayPage(fragment, date)
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
     * The Today station's key — the SCHEDULE FACE, which is `notePage == null`.
     *
     * A sentinel rather than a page key, because null is not a map key and "" is a value the note
     * surfaces could plausibly hand in. Nothing stores strokes under it; [Station.open] routes it
     * to `toDayPage` and [pageKeys] deliberately excludes it, so "am I standing on a station" — a
     * question asked with a note page in hand — can never answer yes for the face that has none.
     */
    const val TODAY = "\u0000today"

    /**
     * The walk, in order — the same order, and the same membership, as the Daily folder.
     *
     * Michael's 08-23 ruling collapsed it to three: "the Daily category can be All Stars, Gram
     * Picks, Notes." Pickings and Gratitude are TEMPLATES a Notes page wears (their pages ride
     * inside the dated note's own ‹ N › walk, so stepping into Notes steps through them); Self
     * Executive is ELIMINATED as a destination — its history still renders wherever it exists,
     * but nothing offers it; Quick Wins and Missed Connections were built into Ask, where a
     * computed answer belongs. The 08-04 note about Self Executive earning its place back is
     * superseded by this ruling.
     */
    val ALL: List<Station> = listOf(
        Station(TODAY, "📅", "Today", isDayPage = false),
        Station("intake", "★", "All Stars", isDayPage = true),
        Station(CalendarDayPageNotes.GRAM_PICKS, "◈", "All Picks", isDayPage = true),
        // "Notes", as iOS names the stop: the walk keeps the page's day now (Michael 2026-10-11),
        // so on a Wednesday "Next · Today's Note" opened Wednesday's note (review 2026-10-11).
        Station("0", "✒", "Notes", isDayPage = true),
    )

    /** The day-page keys on the walk — what "is this page a station" asks. Today is absent by
     *  construction: it is the face, and this question always arrives holding a note page. */
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
        /** The day the steps land on — no default, as [Station.open] has none (Michael 2026-10-11:
         *  a step keeps its day). */
        date: LocalDate,
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
