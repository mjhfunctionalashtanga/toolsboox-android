package com.toolsboox.plugin.feeds.ui

import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.ui.LogOrigin
import com.toolsboox.plugin.calendar.ui.ReadingLogSelection
import com.toolsboox.ui.plugin.ScreenFragment
import java.time.LocalDate
import java.util.Locale

/**
 * The broader Ledger directory shared by the feed list's second button and the feed article's
 * ☰ overlay — Almanac, History, Later, and Personal, plus the surfaces to jump to. Kept out of
 * the RSS hamburger (which is feeds-only) so navigation to the rest of the Ledger has its own
 * home. Each entry navigates via [CalendarNavigator] / the global nav actions.
 */
fun ledgerDirectoryFolders(fragment: ScreenFragment): List<ScreenFragment.Folder> {
    val nav = NavHostFragment.findNavController(fragment)
    val today = LocalDate.now()
    val locale = Locale.getDefault()
    return listOf(
        ScreenFragment.Folder("📆", "Almanac", listOf(
            "📆  Week" to { CalendarNavigator.toWeekPage(fragment, today, locale) },
            "📅  Month" to { CalendarNavigator.toMonthPage(fragment, today) },
            "📊  Quarter" to { CalendarNavigator.toQuarterPage(fragment, today) },
            "🗓️  Year" to { CalendarNavigator.toYearPage(fragment, today) }
        )),
        ScreenFragment.Folder("🕓", "History", action = { nav.navigate(R.id.action_to_reading_log) }),
        ScreenFragment.Folder("🔖", "Later", action = {
            FeedSelection.mode = "later"; FeedSelection.kind = null
            nav.navigate(R.id.action_to_feeds)
        }),
        ScreenFragment.Folder("👤", "Personal", listOf(
            "❝  Pickings" to { CalendarNavigator.toDayNote(fragment, today, "pickings") },
            "🙏  Gratitude" to { CalendarNavigator.toDayNote(fragment, today, "gratitude") },
            "🎬  A/V Grams" to { ReadingLogSelection.origin = LogOrigin.AV; nav.navigate(R.id.action_to_reading_log) }
        )),
        // Top-level so the Bookshelf is never buried under "Go to".
        ScreenFragment.Folder("📚", "Bookshelf", action = { nav.navigate(R.id.action_to_reader) }),
        ScreenFragment.Folder("↪", "Go to", listOf(
            "📅  Day" to { nav.navigate(R.id.action_to_calendar_day) },
            "📰  Feed Ledger" to { nav.navigate(R.id.action_to_feeds) },
            "💬  Ask my Ledger" to { nav.navigate(R.id.action_to_ledger_chat) }
        ))
    )
}
