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
import java.util.Locale

/**
 * Wires the shared Almanac strip into a read-and-wander surface (Quick Wins, Sprouts, Missed
 * Rhizomes) exactly as Roots does. These surfaces aren't one day, so the date is a place to LEAVE
 * from: the arrows step the strip's anchor and open that day; a period tap (day/week/month/quarter/
 * year) jumps into the calendar. What the surface shows doesn't change — the bar is only for getting
 * somewhere from here — so there's no content filtering, matching iOS where these views carry the
 * NavigatorBar for navigation, not scoping. One helper so the three fragments don't triplicate it.
 */
class SemanticNavBar(
    private val fragment: ScreenFragment,
    imageView: ImageView,
    private val dayService: CalendarDayService,
    private val patternService: CalendarPatternService,
    private val documentsRoot: () -> File,
) {
    private var anchor: LocalDate = LocalDate.now()

    private val host = CalendarNavBarHost(
        fragment.requireContext(), imageView, fragment,
        onStepDay = { d ->
            anchor = d
            render()
            com.toolsboox.plugin.calendar.CalendarNavigator.toDayPage(
                fragment, d, CalendarDay.DEFAULT_STYLE)
        }
    )

    init { render() }

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
            if (fragment.isAdded) pat?.let { host.render(day, it) }
        }
    }
}
