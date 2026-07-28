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
                else -> if (com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(page) ||
                    com.toolsboox.plugin.calendar.ot.SynthPageStore.isSynth(page)) "Flow"
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
            "✍  Write" to { CalendarNavigator.toDayNote(fragment, LocalDate.now(), "write") }
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
