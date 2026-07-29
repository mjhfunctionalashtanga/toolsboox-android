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

    // Open the hub with the folder CONTAINING the current surface already expanded — on Roots the
    // Garden folder is open, on Mail the Desk, and so on. Inferred from the calling fragment's type
    // (every surface passes `this`), so all ~15 call sites get it for free; the explicit
    // [expandFeedLedger] wins over inference (the feeds back-ladder wants Feed open from the day
    // page). One folder at most; surfaces that aren't inside any folder (the day page is "Today",
    // the week/month almanacs) expand nothing. The accordion renders expanded folders in its single
    // build pass, so there is no post-open toggle to flash the e-ink.
    val home: String? = if (expandFeedLedger) "Incoming" else when (fragment) {
        is FeedsFragment, is FeedArticleFragment,
        is com.toolsboox.plugin.mail.ui.MailInboxFragment,
        is com.toolsboox.plugin.mail.ui.MailComposeFragment -> "Incoming"
        is com.toolsboox.plugin.calendar.ui.QuickWinsFragment,
        is com.toolsboox.plugin.calendar.ui.RolodexFragment,
        is com.toolsboox.plugin.calendar.ui.LedgerItemsFragment,
        is com.toolsboox.plugin.calendar.ui.KanbanFragment,
        is com.toolsboox.plugin.calendar.ui.PublishFragment,
        is com.toolsboox.plugin.calendar.ui.PostsBrowserFragment -> "Desk"
        is com.toolsboox.plugin.calendar.ui.LedgerRootsFragment,
        is com.toolsboox.plugin.calendar.ui.LedgerMapFragment,
        is com.toolsboox.plugin.calendar.ui.SproutsFragment,
        is com.toolsboox.plugin.calendar.ui.SeedsFragment,
        is com.toolsboox.plugin.calendar.ui.MissedRhizomesFragment,
        is com.toolsboox.plugin.calendar.ui.LedgerRhizomeFragment -> "Garden"
        is com.toolsboox.plugin.textnotes.ui.TextNotesFragment,
        is com.toolsboox.plugin.calendar.ui.NotesTagsFragment -> "Notes"
        is com.toolsboox.plugin.reader.ui.ReaderFragment -> "Bookshelf"
        // ReadingLogFragment serves both Search and History; either way its folder home is Log.
        is com.toolsboox.plugin.calendar.ui.ReadingLogFragment,
        is com.toolsboox.plugin.chat.ui.LedgerChatFragment -> "Log"
        is com.toolsboox.plugin.calendar.ui.SiteBoardsFragment,
        is com.toolsboox.plugin.calendar.ui.CorrespondenceFragment,
        is com.toolsboox.plugin.calendar.ui.MessagesFragment,
        is com.toolsboox.plugin.calendar.ui.RosterFragment -> "Community"
        is com.toolsboox.plugin.calendar.ui.SiteWebFragment -> "Sites"
        is com.toolsboox.plugin.calendar.ui.CalendarSettingsFragment,
        is com.toolsboox.plugin.cloud.ui.CloudFragment -> "Settings"
        // The day surface hosts many pages: the day grid itself is "Today" (no folder), the
        // ritual pages live under Daily, and the freeform note pages under Notes. Same key
        // vocabulary as the fragment's own sectionEmoji()/sectionIcon().
        is com.toolsboox.plugin.calendar.ui.CalendarDayFragment ->
            // Fold a "#n" sub-page (write#1, grid#2) onto its base so it calls the same folder home
            // as its base page — the sub-page tail only matters to the pager, never to the hub.
            when (val page = fragment.currentNotePage()?.substringBefore('#')) {
                null, "default", com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE -> null
                "intake", "write" -> "Flow"
                "gratitude", "selfexec" -> "Garden"
                "grid", "sketch" -> "Notes"
                // A NAMED document ("pickings-…", "synthesize-…", "write-…") calls the same folder
                // home as the surface it belongs to — the "-<millis>" tail is storage, never a
                // different kind of page.
                else -> if (com.toolsboox.plugin.calendar.ot.LedgerDocuments.surfaceOf(page) != null) "Flow"
                else "Notes"   // lined pages ("0", "1", …) and named notebooks
            }
        else -> null   // almanac pages (week/month/…), dashboard — no folder to call home
    }

    // Jump to a Feed Ledger view (feed / stars / read / later, optionally by kind) by setting the
    // selection and navigating — the same wiring across every surface.
    fun openFeed(mode: String, kind: String?) {
        FeedSelection.filterFeedTitle = null
        FeedSelection.mode = mode; FeedSelection.kind = kind
        // Open the feed panel (directory) on arrival too — reaching a feed view from the hub should
        // show the same left panel you'd have coming from within the feed list (Later especially).
        FeedSelection.openDirectory = true
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
    val bookshelf = ScreenFragment.Folder("📚", "Bookshelf", bookRows, expanded = home == "Bookshelf")

    // Read-aloud transport, only while something is playing — reachable from every surface.
    val nowPlaying = if (com.toolsboox.ui.plugin.LedgerPlayer.isActive)
        ScreenFragment.Folder("▶️", "Now Playing",
            action = { com.toolsboox.ui.plugin.LedgerPlayer.showModal(fragment.requireContext()) })
    else null

    // Order per Michael: Search · Today (straight to the page, no submenu) · Feed · Flow ·
    // Desk · Garden · Notes · Bookshelf · Ledger Log · Community · Sites · Settings.
    // Flow sits directly under Feed: the world comes in through Feed, then Flow is where you
    // catch and make from it (Intake → Pickings → Synthesize → Write).
    return listOfNotNull(
        nowPlaying,
        // Search — one tap onto the Log/history surface, arriving ready to type: the one field
        // that searches the whole Ledger, OCR/text layer and semantic layer both.
        ScreenFragment.Folder("🔍", "Search", action = {
            ReadingLogSelection.focusSearch = true
            openHistory(null)
        }),
        // One-tap jump to today's Day page — no submenu. If we're leaving an open article/book,
        // drop a return anchor so the Day page can jump straight back.
        ScreenFragment.Folder("☀️", "Today", action = {
            (fragment as? com.toolsboox.ui.plugin.ReturnAnchorProvider)?.prepareReturnAnchor()
            nav.navigate(R.id.action_to_calendar_day)
        }),
        // Feed Ledger — the RSS reader lenses. Starred (your RSS stars) and Later (the read-later
        // intake) are DIFFERENT stores — both here, as on iPad, not one standing in for the other.
        ScreenFragment.Folder("📰", "Incoming", listOf(
            "📧  Mail" to { nav.navigate(R.id.action_to_mail_inbox) },
            "📰  All" to { openFeed("feed", null) },
            "📖  The Read" to { openFeed("feed", "read") },
            "📺  The Watch" to { openFeed("feed", "watch") },
            "🎧  The Listen" to { openFeed("feed", "listen") },
            "⭐  Starred" to { openFeed("stars", null) },
            "🔖  Later" to { openFeed("later", null) },
            // The rest of the feed drawer's views, here too so the hub and the drawer agree
            // (they're sidebar rows on iPad). "pickings" and "local" ride the same one-shot
            // FeedSelection mode the lens rows use; Smart Feeds are each their own saved
            // search, so that row lands on the list with the drawer open — the smart rows
            // live there, one tap away, without duplicating the fragment's private wiring.
            "❝  Feed Pickings" to { openFeed("pickings", null) },
            "#  Smart Feeds" to {
                FeedSelection.filterFeedTitle = null
                FeedSelection.openDirectory = true
                nav.navigate(R.id.action_to_feeds)
            },
            "📡  Local Feeds" to { openFeed("local", null) }
        ), expanded = home == "Incoming"),
        // Flow — the daily catch→make spine, right under Feed. Intake is the day's catch
        // (Email/Read/Watch/Listen grams, one per starred item); moving a gram into its Pickings
        // is where it becomes something; Synthesize works the gathered pieces and Write closes it
        // out. (Was "Daily"; Daily Pile is retired — Intake takes its place. Gratitude and Self
        // Executive moved to the Garden.)
        ScreenFragment.Folder("⤳", "Flow", listOf(
            "📥  Star Sort" to { CalendarNavigator.toDayNote(fragment, today, "intake") },
            "❝  Pickings" to { showPickingsPicker(fragment) },
            "🔬  Synthesize" to { showSynthPicker(fragment) },
            // Write opens its directory rather than jumping straight at today's page, now that it
            // HAS documents to choose between. Its two neighbours in Flow already worked this way;
            // the odd one out was Write, the surface with the most reason to ask which piece.
            "✍  Write" to { showWritePicker(fragment) }
        ), expanded = home == "Flow"),
        // Desk Ledger — the working surfaces: mail, people, tasks, boards, publishing, notes.
        ScreenFragment.Folder("🗒", "Desk", listOf(
            // Desk order (Michael, 07-24): Quick Wins leads — the quickest action-surface at the
            // top — then the internet-backed working surfaces. The note surfaces moved to their
            // own Notes folder below.
            "⚡  Quick Wins" to { nav.navigate(R.id.action_to_quick_wins) },
            "👤  Contacts" to { nav.navigate(R.id.action_to_rolodex) },
            // Boards = one system, two sources (Local on-device tasks · Site FluentBoards),
            // framed like the RSS Local/Site split. Tasks & Events is the list view of Local.
            "☑  Tasks & Events" to { nav.navigate(R.id.action_to_ledger_items) },
            "📋  Boards · Local" to { nav.navigate(R.id.action_to_kanban) },
            // WordPress publishing on the active site — compose (post/schedule/draft, CPTs, grams as
            // the featured image) and browse/edit/trash posts.
            "🖋  Publish" to { nav.navigate(R.id.action_to_publish) },
            "🗎  Posts" to { nav.navigate(R.id.action_to_posts_browser) }
        ), expanded = home == "Desk"),
        // The Garden: the surfaces about what you've already written rather than capturing more of
        // it. Gratitude and Self Executive settle here — reflective, not capture — moved out of Flow.
        // Roots is what keeps coming back, Map is the same material as a picture, and Sprouts and
        // Missed Rhizomes are what sprouted or what you skipped that speaks to it.
        ScreenFragment.Folder("🪴", "Garden", listOf(
            "🙏  Gratitude" to { CalendarNavigator.toDayNote(fragment, today, "gratitude") },
            "🐘  Self Executive" to { CalendarNavigator.toDayNote(fragment, today, "selfexec") },
            "🌿  Roots" to { nav.navigate(R.id.action_to_ledger_roots) },
            // Seeds sits just before Sprouts — the lifecycle is Seed → (roots form) → Sprout: a
            // #tag still finding its roots network lives here until it recurs enough to sprout.
            "🌰  Seeds" to { nav.navigate(R.id.action_to_seeds) },
            "🌱  Sprouts" to { nav.navigate(R.id.action_to_sprouts) },
            "✧  Missed Rhizomes" to { nav.navigate(R.id.action_to_missed_rhizomes) },
            "🗺  Map" to { nav.navigate(R.id.action_to_ledger_map) }
        ), expanded = home == "Garden"),
        // Notes as their own door (Michael, 07-24): the four note surfaces out of the Desk into a
        // folder of their own — the named-notes work will grow from here. Notes reopens where you
        // last were.
        ScreenFragment.Folder("✒", "Notes", listOf(
            "✒  Notes" to { CalendarNavigator.toLastDayNote(fragment) },
            "📈  Grid Notes" to { CalendarNavigator.toDayNote(fragment, LocalDate.now(), "grid") },
            "⌱  Jot Notes" to { CalendarNavigator.toDayNote(fragment, LocalDate.now(), "sketch") },
            "⌗  Text Notes" to { nav.navigate(R.id.action_to_text_notes) },
            // #hashtags harvested off note pages → jump to any page a tag appears on. No naming.
            "#  Tags" to { showTagIndex(fragment) },
            // Notes & Tags — a period-filtered list ("it's like Feeds") of the days that hold note
            // content and/or #tags, the Almanac nav as its filter. Opens at this week around today.
            "🗓  Notes & Tags" to {
                com.toolsboox.plugin.calendar.ui.NotesTagsFragment.open(fragment, today, "week")
            }
        ), expanded = home == "Notes"),
        bookshelf,
        // Log — the zettelkasten: one screen with range/origin/search inside; Ask lives with it
        // (asking IS querying the log).
        ScreenFragment.Folder("🕘", "Log", listOf(
            // "History", not "Log" — the child shares the folder's name and Search's destination,
            // and a distinct name + glyph is what tells you it's the browse-the-past door.
            "🕰  History" to { openHistory(null) },
            "🔎  Ask" to { nav.navigate(R.id.action_to_ledger_chat) }
        ), expanded = home == "Log"),
        // Community: the NATIVE people-facing surfaces — your desk's connection to others. The active
        // site's member-facing WEB portals live in their own "Sites" folder below, so it's clear at a
        // glance which rows are native tools and which are the website rendered in a WebView.
        ScreenFragment.Folder("👥", "Community", listOf(
            // 🗃 (not 📋) so the two Boards doors don't wear the same icon when the Desk and
            // Community folders are open together — Local keeps the card, Site gets the file box.
            "🗃  Boards · Site" to { nav.navigate(R.id.action_to_site_boards) },
            "@  Correspondence" to { nav.navigate(R.id.action_to_correspondence) },
            "💬  Messages" to { nav.navigate(R.id.action_to_messages) },
            // Bluesky sits beside Correspondence rather than inside it: Correspondence is the
            // community/boards exchange on his OWN sites, where the bridge creds get him in;
            // this is the POSSE loop back out to a public timeline, on the syndication secret and
            // a queue on the VPS. Same act, different ground and different auth — one door each.
            "🦋  Bluesky" to { nav.navigate(R.id.action_to_bluesky) },
            // The day's booking roster (tap a person → their CRM, scribble a note that OCRs onto their
            // CRM timeline). Mail moved to the Desk — email is a working surface, not a person-surface.
            "🎟  Roster" to { nav.navigate(R.id.action_to_roster) }
        ), expanded = home == "Community"),
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
        ), expanded = home == "Sites"),
        ScreenFragment.Folder("⚙", "Settings", listOf(
            // "All settings", not "⚙ Settings" — the folder is already called Settings and wears
            // the gear; a child repeating both read as the same door twice.
            "⚙  All settings" to { nav.navigate(R.id.action_to_settings) },
            // Site accounts is configuration, so it belongs here — not buried behind a button deep
            // inside the calendar-settings scroll. Same manager the settings screen opens; one source
            // of truth. ("Site accounts", not "Sites" — that label is the web-portals folder above.)
            "🖥  Site accounts" to { com.toolsboox.plugin.calendar.ui.SitesSettingsDialog.show(fragment.requireContext()) },
            "🔤  OCR model" to { com.toolsboox.ui.plugin.OcrModel.showPicker(fragment.requireContext()) },
            "☁  Cloud sync" to { nav.navigate(R.id.action_to_cloud) }
        ), expanded = home == "Settings")
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

/**
 * The document directory — ONE listing for every making surface that accumulates pages.
 *
 * This grew out of the Pickings picker, which had two problems. The small one: it read
 * `LocalDate.now()` and nothing else, so opening it from a page dated the 21st listed the 21st's
 * boards nowhere and today's boards always, and every pick navigated you off the day you were
 * working on. A directory that can only ever describe one day isn't a directory; it's a shortcut
 * wearing one's clothes. The larger one: it was the ONLY surface with a directory at all, so Write
 * and Synthesize — the two that most need to say which piece you're in — had none, and each would
 * have grown its own had this stayed Pickings-shaped.
 *
 * So the listing is driven by [com.toolsboox.plugin.calendar.ot.LedgerDocuments], which knows all
 * four surfaces as one kind of thing: a titled document with a page count. Multi-page documents
 * (Write, Synthesize) expand into their sub-pages rather than jumping you at the base; single-page
 * ones (Pickings) are flat rows, because there is no drill-down to build.
 *
 * [knownPageKeys] lets a caller that already holds the day (the day fragment does) hand over its
 * page keys so the counts don't cost a second read of the day file.
 */
fun showDocumentDirectory(
    fragment: ScreenFragment,
    surface: String,
    date: LocalDate = LocalDate.now(),
    currentKey: String? = null,
    knownPageKeys: Set<String> = emptySet(),
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    docs0.sync(ctx, surface, date)   // pull the names given on other devices
    val docs = docs0.forSurface(ctx, surface, date, knownPageKeys)
    val glyph = docs0.glyph(surface)
    val label = docs0.label(surface)
    // The document you're standing in is marked, so the list answers "where am I" as well as "what
    // else is there" — the same "· here" the iPad's directory uses. One shape on both platforms.
    val hereBase = currentKey?.substringBefore('#')
    val rows = docs.map {
        val pages = if (it.subPageCount > 1) "  ·  ${it.subPageCount} pages" else ""
        // A named document off today shows the day it was started; without it a list of essay
        // titles says nothing about when any of them happened.
        val when_ = if (it.date != date) "  ·  ${it.date}" else ""
        "$glyph  ${it.title}$pages$when_" + if (it.key == hereBase) "   ·  here" else ""
    }
    val renameable = docs.any { docs0.canRename(surface, it.key) }
    val extras = listOfNotNull(
        "＋  New ${docs0.noun(surface)}…",
        if (renameable) "✎  Rename…" else null,
        "📅  Go to a date…"
    )
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("$label · $date")
        .setItems((rows + extras).toTypedArray()) { _, which ->
            val extra = which - rows.size
            when {
                which < rows.size -> {
                    val doc = docs[which]
                    // Expand rather than jump: a document with pages should show you its pages, so
                    // "which page of the essay" is answerable from the same place as "which essay".
                    if (doc.subPageCount > 1) showDocumentPages(fragment, surface, doc, currentKey)
                    else CalendarNavigator.toDayNote(fragment, doc.date, doc.key)
                }
                extra == 0 -> promptNewDocument(fragment, surface, date)
                extras.size == 3 && extra == 1 -> promptRenameDocument(fragment, surface, date, docs)
                // The same surface on another day — a directory should walk time as well as pages,
                // which is the move the iPad's directory ends on too.
                else -> showDocumentDatePicker(fragment, surface, date, currentKey)
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

/**
 * One multi-page document's pages. The same "Page N · here / ＋ New page" shape the day fragment's
 * own sub-page jump uses, so drilling in from the directory and tapping the ‹ N › pager land you in
 * the same list — a surface should not have two different answers to "what pages are in this".
 */
private fun showDocumentPages(
    fragment: ScreenFragment,
    surface: String,
    doc: com.toolsboox.plugin.calendar.ot.LedgerDocument,
    currentKey: String?,
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val here = if (currentKey?.substringBefore('#') == doc.key)
        currentKey.substringAfter('#', "").toIntOrNull() ?: 0 else -1
    val rows = (0 until doc.subPageCount).map { "Page ${it + 1}" + if (it == here) "   ·  here" else "" }
    // An unnamed document's title IS its date, so titling this dialog "<title> · <date>" would
    // print the date twice; it takes the surface's name instead.
    val heading = if (doc.named) "${doc.title} · ${doc.date}"
    else "${docs0.label(surface)} · ${doc.date}"
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle(heading)
        .setItems((rows + listOf("＋  New page", "‹  All ${docs0.label(surface)}")).toTypedArray()) { _, which ->
            when (which) {
                in rows.indices -> CalendarNavigator.toDayNote(fragment, doc.date, docs0.subPageKey(doc.key, which))
                rows.size -> CalendarNavigator.toDayNote(
                    fragment, doc.date, docs0.subPageKey(doc.key, doc.subPageCount))
                // Back up a level rather than closing: the drill-down was one tap, and having to
                // reopen the whole directory to look at a different document is what makes a nested
                // menu feel like a trap.
                else -> showDocumentDirectory(fragment, surface, doc.date, currentKey)
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

/**
 * "Go to a date…": pick a day, then land back in the directory FOR that day rather than jumping
 * blind. Re-entering the list is what makes it a walk rather than a leap — you see what's over
 * there before committing to a page.
 */
private fun showDocumentDatePicker(
    fragment: ScreenFragment,
    surface: String,
    from: LocalDate,
    currentKey: String?,
) {
    val ctx = fragment.requireContext()
    android.app.DatePickerDialog(
        com.toolsboox.ot.ModalScale.wrap(ctx),
        { _, y, m, d -> showDocumentDirectory(fragment, surface, LocalDate.of(y, m + 1, d), currentKey) },
        from.year, from.monthValue - 1, from.dayOfMonth
    ).show()
}

/** Name a new document and open it. Blank is allowed — the store falls back to the date. */
private fun promptNewDocument(fragment: ScreenFragment, surface: String, date: LocalDate) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val input = android.widget.EditText(ctx).apply {
        hint = "What's this ${docs0.noun(surface)} about?"; setSingleLine()
    }
    val pad = (16 * ctx.resources.displayMetrics.density).toInt()
    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
    }
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("New ${docs0.noun(surface)}")
        .setView(box)
        .setPositiveButton("Create") { _, _ ->
            val doc = docs0.create(ctx, surface, input.text.toString().trim(), date) ?: return@setPositiveButton
            CalendarNavigator.toDayNote(fragment, doc.date, doc.key)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * Rename one of the listed documents. The list is the directory's own, so what you can rename is
 * exactly what you were just looking at — including, on Write, the daily page itself (its index
 * scopes the shared "write" key by date, so naming the 21st's leaves the 22nd's alone).
 */
private fun promptRenameDocument(
    fragment: ScreenFragment,
    surface: String,
    date: LocalDate,
    docs: List<com.toolsboox.plugin.calendar.ot.LedgerDocument>,
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val target = docs.filter { docs0.canRename(surface, it.key) }
    if (target.isEmpty()) return
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Rename which ${docs0.noun(surface)}?")
        .setItems(target.map { it.title }.toTypedArray()) { _, which ->
            val doc = target[which]
            // Seed the field with the EXPLICIT name only. Pre-filling an unnamed document with its
            // date would make "rename" mean "confirm the date as the name", which is the one answer
            // the date default already gives for free.
            val input = android.widget.EditText(ctx).apply {
                if (doc.named) setText(doc.title) else hint = doc.date.toString()
                setSingleLine()
            }
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            val box = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
            }
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Rename ${docs0.noun(surface)}")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    docs0.rename(ctx, surface, doc.key, input.text.toString().trim(), doc.date)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** Pickings' door into the shared directory. Kept as its own name because the surface is called
 *  Pickings everywhere it's reached from — the hub row, the pager chip, the day chip. */
fun showPickingsPicker(
    fragment: ScreenFragment,
    date: LocalDate = LocalDate.now(),
    currentKey: String? = null,
) = showDocumentDirectory(
    fragment, com.toolsboox.plugin.calendar.ot.LedgerDocuments.PICKINGS, date, currentKey)

/** The tag index: every #tag harvested off note pages → the pages it appears on, for jump-nav. */
fun showTagIndex(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val tags = com.toolsboox.plugin.calendar.ot.LedgerTags.list(ctx)
    if (tags.isEmpty()) {
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Tags")
            .setMessage("No #tags yet. Write #something on a page — it's harvested when the page's ink is captured.")
            .setPositiveButton("Close", null)
            .show()
        return
    }
    // SEARCHABLE, not a flat list. Past a few dozen tags a scroll stops being an index and becomes
    // a haystack — and this is built for hundreds. A filter field over the same data, rebuilt in
    // place as you type: on e-ink a full redraw per keystroke is still cheaper than any incremental
    // scheme, and there is no diffing to get wrong. Matches the iPad's `.searchable` index.
    val dp = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val rows = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
    val field = android.widget.EditText(ctx).apply {
        hint = "Find a tag"; isSingleLine = true; textSize = 15f
    }
    val col = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(px(16), px(8), px(16), px(8))
        addView(field)
        addView(rows)
    }
    lateinit var dialog: androidx.appcompat.app.AlertDialog

    fun render(query: String) {
        rows.removeAllViews()
        val q = query.trim().lowercase()
        val shown = if (q.isEmpty()) tags else tags.filter { it.tag.contains(q) }
        if (shown.isEmpty()) {
            rows.addView(android.widget.TextView(ctx).apply {
                text = "Nothing matches “$query”."
                textSize = 14f; setTextColor(0xFF888888.toInt()); setPadding(px(4), px(12), px(4), px(4))
            })
            return
        }
        for (t in shown) {
            rows.addView(android.widget.TextView(ctx).apply {
                text = "#${t.tag}  ·  ${t.occurrences.size}"
                textSize = 16f; setTextColor(0xFF000000.toInt())
                setPadding(px(4), px(10), px(4), px(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { dialog.dismiss(); showTagPages(fragment, t) }
            })
        }
        com.toolsboox.ot.LedgerFonts.applyTree(rows)
    }

    field.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString().orEmpty()) }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    render("")

    dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Tags")
        .setView(android.widget.ScrollView(ctx).apply { addView(col) })
        .setNegativeButton("Close", null)
        .create()
    fragment.showModal(dialog)
}

/** One tag's pages, newest first — tap to jump straight to that day's page, landing on the tag's
 *  mark (the capture zone it was written in) when the occurrence recorded one. A ✎ marks the ones
 *  that will zoom to the mark; legacy occurrences with no rect just open the page. */
private fun showTagPages(fragment: ScreenFragment, tag: com.toolsboox.plugin.calendar.ot.LedgerTags.TagInfo) {
    val ctx = fragment.requireContext()
    val occ = tag.occurrences.sortedByDescending { it.first }
    val labels = occ.map { "${if (it.third != null) "✎  " else ""}${it.first}  ·  ${it.second}" }.toTypedArray()
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("#${tag.tag}")
        .setItems(labels) { _, which ->
            val (date, page, rect) = occ[which]
            CalendarNavigator.toDayNote(fragment, date, page, rect)
        }
        .setNegativeButton("Close", null)
        .show()
}

/**
 * Synthesize's door into the shared directory: the day's own synthesis, plus the named TOPIC pages
 * that persist over time, each expandable into its sub-pages.
 *
 * The topic pages are the coarse boundary — one per subject, each holding only what you carried
 * onto it — so a surrogacy synthesis and an Android one never bleed into each other's outlines.
 */
fun showSynthPicker(
    fragment: ScreenFragment,
    date: LocalDate = LocalDate.now(),
    currentKey: String? = null,
) = showDocumentDirectory(
    fragment, com.toolsboox.plugin.calendar.ot.LedgerDocuments.SYNTHESIZE, date, currentKey)

/** Write's door into the shared directory: the day's writing plus the named pieces you return to. */
fun showWritePicker(
    fragment: ScreenFragment,
    date: LocalDate = LocalDate.now(),
    currentKey: String? = null,
) = showDocumentDirectory(
    fragment, com.toolsboox.plugin.calendar.ot.LedgerDocuments.WRITE, date, currentKey)
