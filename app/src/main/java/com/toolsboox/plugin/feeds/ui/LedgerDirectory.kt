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
 * The ONE Ledger directory, shared by every surface — the day page's ▦ hub, the feed list's
 * ledger button, the feed article's ☰ overlay, the book reader's ▦, and the chat. Keeping a single
 * definition here is what stops the day page and the feed/book screens from drifting apart.
 *
 * [recentBooks] lets the caller (the day hub) fold its recently-opened books into the Bookshelf
 * folder; other callers pass nothing and Bookshelf is a one-tap jump.
 */
fun ledgerDirectoryFolders(
    fragment: ScreenFragment,
    recentBooks: List<Pair<String, () -> Unit>> = emptyList()
): List<ScreenFragment.Folder> {
    val nav = NavHostFragment.findNavController(fragment)
    val today = LocalDate.now()
    val locale = Locale.getDefault()

    // Jump to a Feed Ledger view (feed / stars / read / later, optionally by kind) by setting the
    // selection and navigating — the same wiring across every surface.
    fun openFeed(mode: String, kind: String?) {
        FeedSelection.filterFeedTitle = null
        FeedSelection.mode = mode; FeedSelection.kind = kind
        nav.navigate(R.id.action_to_feeds)
    }
    fun openHistory(origin: LogOrigin?) {
        ReadingLogSelection.origin = origin
        nav.navigate(R.id.action_to_reading_log)
    }

    // Bookshelf is a dropdown on EVERY surface: recent books + All books. Read the shelf directly
    // (filesDir/reader/books) so it doesn't depend on the caller passing recentBooks. Opening a book
    // = point the reader's saved-book pref at it, then navigate (the reader restores it on open).
    fun openBook(f: java.io.File) {
        f.setLastModified(System.currentTimeMillis())
        // Audiobooks play through the shared player; e-books open in the reader.
        if (com.toolsboox.ui.plugin.LedgerPlayer.isAudioFile(f.name)) {
            com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
                fragment.requireContext(), f.nameWithoutExtension, "Audiobook", null, f.absolutePath)
            com.toolsboox.ui.plugin.LedgerPlayer.showModal(fragment.requireContext())
            return
        }
        fragment.requireContext().getSharedPreferences("ledger_reader_prefs", 0).edit()
            .putString("current_book_path", f.absolutePath).apply()
        nav.navigate(R.id.action_to_reader)
    }
    val shelf = java.io.File(fragment.requireContext().filesDir, "reader/books")
        .listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(6) ?: emptyList()
    val bookRows: List<Pair<String, () -> Unit>> =
        recentBooks.ifEmpty { shelf.map { f -> ("📖  " + f.nameWithoutExtension) to { openBook(f) } } } +
        ("📚  All books" to { nav.navigate(R.id.action_to_reader) })
    val bookshelf = ScreenFragment.Folder("📚", "Bookshelf", bookRows)

    // Read-aloud transport, only while something is playing — reachable from every surface.
    val nowPlaying = if (com.toolsboox.ui.plugin.LedgerPlayer.isActive)
        ScreenFragment.Folder("▶️", "Now Playing",
            action = { com.toolsboox.ui.plugin.LedgerPlayer.showModal(fragment.requireContext()) })
    else null

    // Order: Today, Almanac (+Tasks&Events), Daily, Feed, Bookshelf, Ask, Ledger Log (+Tasks&Events), Settings.
    return listOfNotNull(
        nowPlaying,
        // One-tap jump to today's Day page — the "home" the feed/book screens were missing. If we're
        // leaving an open article/book, drop a return anchor so the Day page can jump straight back.
        ScreenFragment.Folder("☀️", "Today", listOf(
            "☀  Today's page" to {
                (fragment as? com.toolsboox.ui.plugin.ReturnAnchorProvider)?.prepareReturnAnchor()
                nav.navigate(R.id.action_to_calendar_day)
            },
            "📝  Text Notes" to { nav.navigate(R.id.action_to_text_notes) },
            // The freeform multi-page handwriting surface (distinct from "Write" under Daily Ledgers).
            "✒  Notes" to { CalendarNavigator.toDayNote(fragment, today, "0") }
        )),
        // Tasks & Events promoted to the top level — a one-tap jump, not buried in Ledger Log.
        ScreenFragment.Folder("🗒", "Tasks & Events", action = { nav.navigate(R.id.action_to_ledger_items) }),
        ScreenFragment.Folder("👤", "Rolodex", action = { nav.navigate(R.id.action_to_rolodex) }),
        ScreenFragment.Folder("📋", "Boards", action = { nav.navigate(R.id.action_to_kanban) }),
        ScreenFragment.Folder("✉️", "Correspondence", action = { nav.navigate(R.id.action_to_correspondence) }),
        ScreenFragment.Folder("📆", "Almanac", listOf(
            "📆  Week" to { CalendarNavigator.toWeekPage(fragment, today, locale) },
            "📅  Month" to { CalendarNavigator.toMonthPage(fragment, today) },
            "📊  Quarter" to { CalendarNavigator.toQuarterPage(fragment, today) },
            "🗓️  Year" to { CalendarNavigator.toYearPage(fragment, today) }
        )),
        ScreenFragment.Folder("❤️", "Daily Ledgers", listOf(
            "❝  Pickings" to { showPickingsPicker(fragment) },
            "🙏  Gratitude" to { CalendarNavigator.toDayNote(fragment, today, "gratitude") },
            "🔬  Synthesize" to { CalendarNavigator.toDayNote(fragment, today, "synthesize") },
            "✍️  Write" to { CalendarNavigator.toDayNote(fragment, today, "write") }
        )),
        // Feed Ledger — the RSS reader lenses.
        ScreenFragment.Folder("📰", "Feed Ledger", listOf(
            "📰  All" to { openFeed("feed", null) },
            "📖  The Read" to { openFeed("feed", "read") },
            "📺  The Watch" to { openFeed("feed", "watch") },
            "🎧  The Listen" to { openFeed("feed", "listen") },
            "🔖  Later" to { openFeed("later", null) }
        )),
        bookshelf,
        ScreenFragment.Folder("💬", "Ask my Ledger", action = { nav.navigate(R.id.action_to_ledger_chat) }),
        // Ledger Log — everything consumed: stars, annotations, read/watched/listened, books.
        ScreenFragment.Folder("🕘", "Ledger Log", listOf(
            "🗂  All" to { openHistory(null) },
            "⭐  Stars" to { openFeed("stars", null) },
            "🖍️  Annotations" to { openHistory(null) },
            "🎧  Listened" to { openFeed("read", "listen") },
            "📺  Watched" to { openFeed("read", "watch") },
            "📰  Feed Read" to { openFeed("read", null) },
            "📚  Books Read" to { openHistory(LogOrigin.BOOK) }
        )),
        ScreenFragment.Folder("⚙", "Settings", listOf(
            "⚙  Settings" to { nav.navigate(R.id.action_to_settings) },
            "🔤  OCR model" to { com.toolsboox.ui.plugin.OcrModel.showPicker(fragment.requireContext()) },
            "☁  Cloud sync" to { nav.navigate(R.id.action_to_cloud) }
        ))
    )
}

/** Pickings can be multiple, named boards per day — choose one, add a new one, or rename one. */
fun showPickingsPicker(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val today = LocalDate.now()
    com.toolsboox.plugin.calendar.ot.PickingsStore.sync(ctx, today)   // pull other devices' board names
    val pages = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, today)
    val labels = (pages.map { "❝  ${it.name}" } + listOf("＋  New pickings…", "✎  Rename a pickings…")).toTypedArray()
    androidx.appcompat.app.AlertDialog.Builder(ctx)
        .setTitle("Pickings · $today")
        .setItems(labels) { _, which ->
            when {
                which < pages.size -> CalendarNavigator.toDayNote(fragment, today, pages[which].key)
                which == pages.size -> promptNewPicking(fragment)
                else -> promptRenamePicking(fragment)
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

private fun promptNewPicking(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val today = LocalDate.now()
    val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
    val pad = (16 * ctx.resources.displayMetrics.density).toInt()
    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
    }
    androidx.appcompat.app.AlertDialog.Builder(ctx)
        .setTitle("New pickings")
        .setView(box)
        .setPositiveButton("Create") { _, _ ->
            val page = com.toolsboox.plugin.calendar.ot.PickingsStore.add(ctx, today, input.text.toString().trim())
            CalendarNavigator.toDayNote(fragment, today, page.key)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun promptRenamePicking(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val today = LocalDate.now()
    val pages = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, today)
    androidx.appcompat.app.AlertDialog.Builder(ctx)
        .setTitle("Rename which pickings?")
        .setItems(pages.map { it.name }.toTypedArray()) { _, which ->
            val page = pages[which]
            val input = android.widget.EditText(ctx).apply { setText(page.name); setSingleLine() }
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
            }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Rename pickings")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    com.toolsboox.plugin.calendar.ot.PickingsStore.rename(ctx, today, page.key, input.text.toString().trim())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        .show()
}
