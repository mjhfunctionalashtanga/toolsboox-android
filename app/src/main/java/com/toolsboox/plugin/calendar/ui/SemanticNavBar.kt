package com.toolsboox.plugin.calendar.ui

import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.fi.CalendarPatternService
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Wires the shared Almanac strip into a read-and-wander surface (Quick Wins, Sprouts, Missed
 * Rhizomes).
 *
 * Two modes, chosen by whether the surface passes [onFilter]:
 *
 * Without it (the old shape), the date is a place to LEAVE from — the arrows step the anchor and
 * open that day; a period tap jumps into the calendar. With it, the bar is a FILTER and never
 * navigates away: a period tap scopes the surface to that period (the tapped slot goes focal with
 * the accent bar under it), the carets step the SELECTED period — a month back, not a day, and
 * never a reset to today — and tapping the focal period again clears the filter back to
 * everything. The surface re-renders in place each time; nobody gets thrown out of the garden
 * for touching a date.
 *
 * One helper so the three fragments don't triplicate it.
 */
class SemanticNavBar(
    private val fragment: ScreenFragment,
    imageView: ImageView,
    private val dayService: CalendarDayService,
    private val patternService: CalendarPatternService,
    // Filtering surfaces receive (granularity-or-null, anchor) after every change; null
    // granularity means the filter is off and the surface should show everything.
    private val onFilter: ((String?, LocalDate) -> Unit)? = null,
    private val documentsRoot: () -> File,
) {
    private var anchor: LocalDate = LocalDate.now()

    /** The active filter level, or null for "everything". Only meaningful with [onFilter]. */
    var granularity: String? = null
        private set

    private val host: CalendarNavBarHost = CalendarNavBarHost(
        fragment.requireContext(), imageView, fragment,
        onStepDay = { d ->
            anchor = d
            if (onFilter == null) {
                // Leave-from mode: step the strip's anchor and open that day.
                render()
                com.toolsboox.plugin.calendar.CalendarNavigator.toDayPage(
                    fragment, d, CalendarDay.DEFAULT_STYLE)
            } else {
                // Filter mode: a caret with no filter yet quietly starts one at day level —
                // "show me yesterday's" is what the tap means — and then steps stay in place.
                if (granularity == null) {
                    granularity = "day"
                    host.setGranularity("day")
                }
                render()
                onFilter?.invoke(granularity, anchor)
            }
        },
        onSelectPeriod = if (onFilter == null) null else { g, d ->
            if (g == granularity) {
                // The focal period tapped again: the filter comes off, everything comes back.
                granularity = null
                host.setGranularity(null)
                onFilter?.invoke(null, anchor)
            } else {
                granularity = g
                anchor = d
                onFilter?.invoke(g, d)
            }
        }
    )

    init {
        // Filter mode opens unfiltered — the surface shows its whole harvest until a period is
        // chosen — so the strip must open quiet too, not focal on today.
        if (onFilter != null) host.setGranularity(null)
        render()
    }

    /** [start, end) of the filtered window, or null when the filter is off. */
    fun window(): Pair<LocalDate, LocalDate>? {
        val a = anchor
        return when (granularity) {
            "day" -> a to a.plusDays(1)
            "week" -> {
                val s = a.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
                s to s.plusWeeks(1)
            }
            "month" -> { val s = a.withDayOfMonth(1); s to s.plusMonths(1) }
            "quarter" -> {
                val s = a.withDayOfMonth(1).withMonth((a.monthValue - 1) / 3 * 3 + 1)
                s to s.plusMonths(3)
            }
            "year" -> { val s = a.withDayOfYear(1); s to s.plusYears(1) }
            else -> null
        }
    }

    /** The filtered period the way a person would say it — "July 23", "Week 30 of 2026", "July
     *  2026", "Q3 2026", "2026" — for empty states that name what came up empty. Null = no filter. */
    fun periodLabel(): String? {
        val a = anchor
        return when (granularity) {
            "day" -> a.format(DateTimeFormatter.ofPattern("MMMM d"))
            "week" -> "Week " + a.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear()) +
                " of " + a.year
            "month" -> a.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + a.year
            "quarter" -> "Q" + ((a.monthValue - 1) / 3 + 1) + " " + a.year
            "year" -> "${a.year}"
            else -> null
        }
    }

    /** Draw the strip for the anchor day, dots and all. Off the main thread, quiet on failure. */
    private fun render() {
        fragment.lifecycleScope.launch {
            val root = documentsRoot()
            val loc = Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { dayService.load(root, anchor, null, loc) }.getOrNull()
                    ?: CalendarDay(anchor.year, anchor.monthValue, anchor.dayOfMonth, startHour = null)
                cd to runCatching { patternService.load(root, anchor, loc) }.getOrNull()
            }
            // A missing pattern must not kill the strip: an unrendered host has no currentDay,
            // so its touch listener returns false forever — a dead nav that looks alive.
            // Same fallback the mail/feeds hosts use: an empty filled pattern, no dots.
            val safePat = pat ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(anchor.year, loc).fill()
            if (fragment.isAdded) host.render(day, safePat)
        }
    }
}
