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
    recentBooks: List<Pair<String, () -> Unit>> = emptyList(),
    expandFeedLedger: Boolean = false
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

    // Order per Michael: Search · Today (straight to the page, no submenu) · Daily Ledger ·
    // Desk Ledger · Garden · Feed Ledger · Bookshelf · Ledger Log · Community · Sites · Settings.
    return listOfNotNull(
        nowPlaying,
        // Search — one tap onto the Log/history surface, which carries range/origin/search inside.
        ScreenFragment.Folder("🔍", "Search", action = { openHistory(null) }),
        // One-tap jump to today's Day page — no submenu. If we're leaving an open article/book,
        // drop a return anchor so the Day page can jump straight back.
        ScreenFragment.Folder("☀️", "Today", action = {
            (fragment as? com.toolsboox.ui.plugin.ReturnAnchorProvider)?.prepareReturnAnchor()
            nav.navigate(R.id.action_to_calendar_day)
        }),
        // The daily ritual: Intake → Pickings → Gratitude → Self Executive → Synthesize → Write.
        // Synthesize works the day's gathered pieces and Write closes the ritual out — both live here,
        // matching iPad, rather than in the Garden.
        ScreenFragment.Folder("❤️", "Daily", listOf(
            // Daily Pile — everything the day collected on one grid, to pick or rhizome outward from.
            "🗂  Daily Pile" to { nav.navigate(R.id.action_to_daily_pile) },
            "📥  Intake" to { CalendarNavigator.toDayNote(fragment, today, "intake") },
            "❝  Pickings" to { showPickingsPicker(fragment) },
            "🙏  Gratitude" to { CalendarNavigator.toDayNote(fragment, today, "gratitude") },
            "🐘  Self Executive" to { CalendarNavigator.toDayNote(fragment, today, "selfexec") },
            "🔬  Synthesize" to { showSynthPicker(fragment) },
            "✍  Write" to { CalendarNavigator.toDayNote(fragment, LocalDate.now(), "write") }
        )),
        // Desk Ledger — the working surfaces: mail, people, tasks, boards, publishing, notes.
        ScreenFragment.Folder("🗒", "Desk", listOf(
            // Desk order (Michael): the internet-backed working surfaces first — mail, people, tasks,
            // boards, publishing, quick wins — then the four note surfaces together at the end.
            // Native unified mail (IMAP/SMTP) — email is a working surface, so it lives on the Desk.
            "✉  Mail" to { nav.navigate(R.id.action_to_mail_inbox) },
            "👤  Contacts" to { nav.navigate(R.id.action_to_rolodex) },
            // Boards = one system, two sources (Local on-device tasks · Site FluentBoards),
            // framed like the RSS Local/Site split. Tasks & Events is the list view of Local.
            "☑  Tasks & Events" to { nav.navigate(R.id.action_to_ledger_items) },
            "📋  Boards · Local" to { nav.navigate(R.id.action_to_kanban) },
            // WordPress publishing on the active site — compose (post/schedule/draft, CPTs, grams as
            // the featured image) and browse/edit/trash posts.
            "🖋  Publish" to { nav.navigate(R.id.action_to_publish) },
            "🗎  Posts" to { nav.navigate(R.id.action_to_posts_browser) },
            // Quick Wins — the quickest semantic action-surface, now a working-desk row.
            "⚡  Quick Wins" to { nav.navigate(R.id.action_to_quick_wins) },
            // The four note surfaces belong together, at the end of the desk. Notes reopens where you
            // last were.
            "✒  Notes" to { CalendarNavigator.toLastDayNote(fragment) },
            "📈  Grid Notes" to { CalendarNavigator.toDayNote(fragment, LocalDate.now(), "grid") },
            "⌱  Sketch Notes" to { CalendarNavigator.toDayNote(fragment, LocalDate.now(), "sketch") },
            "⌗  Text Notes" to { nav.navigate(R.id.action_to_text_notes) }
        )),
        // The Garden: the surfaces that are about what you have already written rather than about
        // capturing more of it. Roots is what keeps coming back, Map is the same material as a picture,
        // and Sprouts and Missed Rhizomes are what sprouted or what you skipped that speaks to it.
        // (Write moved to Daily; Quick Wins moved to the Desk.)
        ScreenFragment.Folder("🪴", "Garden", listOf(
            "🌿  Roots" to { nav.navigate(R.id.action_to_ledger_roots) },
            "🗺  Map" to { nav.navigate(R.id.action_to_ledger_map) },
            "🌱  Sprouts" to { nav.navigate(R.id.action_to_sprouts) },
            "✧  Missed Rhizomes" to { nav.navigate(R.id.action_to_missed_rhizomes) }
        )),
        // Feed Ledger — the RSS reader lenses. Starred (your RSS stars) and Later (the read-later
        // intake) are DIFFERENT stores — both here, as on iPad, not one standing in for the other.
        ScreenFragment.Folder("📰", "Feed", listOf(
            "📰  All" to { openFeed("feed", null) },
            "📖  The Read" to { openFeed("feed", "read") },
            "📺  The Watch" to { openFeed("feed", "watch") },
            "🎧  The Listen" to { openFeed("feed", "listen") },
            "⭐  Starred" to { openFeed("stars", null) },
            "🔖  Later" to { openFeed("later", null) }
        ), expanded = expandFeedLedger),
        bookshelf,
        // Log — the zettelkasten: one screen with range/origin/search inside; Ask lives with it
        // (asking IS querying the log).
        ScreenFragment.Folder("🕘", "Log", listOf(
            "🕘  Log" to { openHistory(null) },
            "🔎  Ask" to { nav.navigate(R.id.action_to_ledger_chat) }
        )),
        // Community: the NATIVE people-facing surfaces — your desk's connection to others. The active
        // site's member-facing WEB portals live in their own "Sites" folder below, so it's clear at a
        // glance which rows are native tools and which are the website rendered in a WebView.
        ScreenFragment.Folder("👥", "Community", listOf(
            "📋  Boards · Site" to { nav.navigate(R.id.action_to_site_boards) },
            "@  Correspondence" to { nav.navigate(R.id.action_to_correspondence) },
            "💬  Messages" to { nav.navigate(R.id.action_to_messages) },
            // The day's booking roster (tap a person → their CRM, scribble a note that OCRs onto their
            // CRM timeline). Mail moved to the Desk — email is a working surface, not a person-surface.
            "🎟  Roster" to { nav.navigate(R.id.action_to_roster) }
        )),
        // Sites — the active site's own forward-facing Vue apps in a persistent-session WebView (sign in
        // once, cookies stick). This is the WEBSITE as members/customers see it, kept apart from the
        // native tools above. Portals that ARE a native surface's web face live inside that surface
        // instead of here: the FluentBoards front → "Boards · Site", the booking page → Roster's
        // "Booking page", the FluentCRM admin → Contacts' "CRM". Only genuinely portal-only pages remain.
        ScreenFragment.Folder("🌐", "Sites", listOf(
            "🏛  Community portal" to { openSiteWeb(nav, "community") },
            "🎓  Courses" to { openSiteWeb(nav, "courses") },
            "🛟  Support" to { openSiteWeb(nav, "support") },
            "🛍  Shop" to { openSiteWeb(nav, "shop") }
        )),
        ScreenFragment.Folder("⚙", "Settings", listOf(
            "⚙  Settings" to { nav.navigate(R.id.action_to_settings) },
            // Sites is configuration, so it belongs here — not buried behind a button deep inside the
            // calendar-settings scroll. Same manager the settings screen opens; one source of truth.
            "🖥  Sites" to { com.toolsboox.plugin.calendar.ui.SitesSettingsDialog.show(fragment.requireContext()) },
            "🔤  OCR model" to { com.toolsboox.ui.plugin.OcrModel.showPicker(fragment.requireContext()) },
            "☁  Cloud sync" to { nav.navigate(R.id.action_to_cloud) }
        ))
    )
}

/** Open one of the active site's forward-facing Vue apps in the persistent-session WebView. */
private fun openSiteWeb(nav: androidx.navigation.NavController, target: String) {
    nav.navigate(
        R.id.action_to_site_web,
        androidx.core.os.bundleOf(
            com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_TARGET to target
        )
    )
}

/** Pickings can be multiple, named boards per day — choose one, add a new one, or rename one. */
fun showPickingsPicker(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val today = LocalDate.now()
    com.toolsboox.plugin.calendar.ot.PickingsStore.sync(ctx, today)   // pull other devices' board names
    val pages = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, today)
    val labels = (pages.map { "❝  ${it.name}" } + listOf("＋  New pickings…", "✎  Rename a pickings…")).toTypedArray()
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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

/**
 * Synthesize pages: today's daily page, plus the named TOPIC pages that persist over time.
 *
 * The topic pages are the coarse boundary — one per subject, each holding only what you carried
 * onto it — so a surrogacy synthesis and an Android one never bleed into each other's outlines.
 */
fun showSynthPicker(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val today = LocalDate.now()
    val store = com.toolsboox.plugin.calendar.ot.SynthPageStore
    store.sync(ctx)   // pull topic pages made on other devices
    val topics = store.list(ctx)
    val labels = (listOf("🔬  Today's synthesis") +
        topics.map { "🔬  ${it.name}  ·  ${it.date}" } +
        listOf("＋  New synthesis…", "✎  Rename a synthesis…")).toTypedArray()
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Synthesize")
        .setItems(labels) { _, which ->
            when {
                which == 0 -> CalendarNavigator.toDayNote(fragment, today, store.DEFAULT_KEY)
                which <= topics.size -> {
                    val p = topics[which - 1]
                    CalendarNavigator.toDayNote(fragment, p.date, p.key)
                }
                which == topics.size + 1 -> promptNewSynth(fragment)
                else -> promptRenameSynth(fragment)
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

private fun promptNewSynth(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val input = android.widget.EditText(ctx).apply { hint = "What's this synthesis about?"; setSingleLine() }
    val pad = (16 * ctx.resources.displayMetrics.density).toInt()
    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
    }
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("New synthesis")
        .setView(box)
        .setPositiveButton("Create") { _, _ ->
            val page = com.toolsboox.plugin.calendar.ot.SynthPageStore.add(ctx, input.text.toString().trim())
            CalendarNavigator.toDayNote(fragment, page.date, page.key)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun promptRenameSynth(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val pages = com.toolsboox.plugin.calendar.ot.SynthPageStore.list(ctx)
    if (pages.isEmpty()) return
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Rename which synthesis?")
        .setItems(pages.map { it.name }.toTypedArray()) { _, which ->
            val page = pages[which]
            val input = android.widget.EditText(ctx).apply { setText(page.name); setSingleLine() }
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
            }
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Rename synthesis")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    com.toolsboox.plugin.calendar.ot.SynthPageStore.rename(ctx, page.key, input.text.toString().trim())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        .setNegativeButton(android.R.string.cancel, null)
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
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Rename which pickings?")
        .setItems(pages.map { it.name }.toTypedArray()) { _, which ->
            val page = pages[which]
            val input = android.widget.EditText(ctx).apply { setText(page.name); setSingleLine() }
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
            }
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
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
