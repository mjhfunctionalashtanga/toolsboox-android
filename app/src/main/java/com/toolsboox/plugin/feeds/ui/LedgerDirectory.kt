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
                "grid", "sketch",
                com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS -> "Notes"
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
    fun openFeed(mode: String, kind: String?, laterLane: String? = null) {
        FeedSelection.filterFeedTitle = null
        FeedSelection.mode = mode; FeedSelection.kind = kind
        FeedSelection.laterLane = laterLane
        // Open the feed panel (directory) on arrival too — reaching a feed view from the hub should
        // show the same left panel you'd have coming from within the feed list (Later especially).
        FeedSelection.openDirectory = true
        nav.navigate(R.id.action_to_feeds)
    }
    /**
     * Open the inbox, unified or narrowed to one account.
     *
     * The narrowing travels through the same `account_filter` preference the inbox already reads on
     * arrival, rather than a nav argument: the inbox persists that choice so it reopens the way it
     * was left, and a second channel saying the same thing would be one more way for the two to
     * disagree about which mailbox you asked for.
     */
    fun openMail(accountId: String?) {
        fragment.requireContext().getSharedPreferences("ledger_mail_inbox", 0).edit()
            .putString("account_filter", accountId ?: "").apply()
        nav.navigate(R.id.action_to_mail_inbox)
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
        // The DIRECTORY, directly under Search, because they are the two halves of the same
        // question: Search reads what you wrote, the Directory shows what you have. Michael asked
        // for "the directory folder system for things like picking synthesize, write notes, etc." —
        // one findable, sortable place across every kind. Until this row existed the per-kind
        // directories could only be reached from INSIDE a kind, so "what do I have?" was a question
        // you could only ask once you had already gone somewhere and stopped needing to ask it.
        ScreenFragment.Folder("🗂", "Directory", action = { showLedgerRootDirectory(fragment) }),
        // One-tap jump to today's Day page — no submenu. If we're leaving an open article/book,
        // drop a return anchor so the Day page can jump straight back.
        ScreenFragment.Folder("☀️", "Today", action = {
            (fragment as? com.toolsboox.ui.plugin.ReturnAnchorProvider)?.prepareReturnAnchor()
            nav.navigate(R.id.action_to_calendar_day)
        }),
        // Feed Ledger — the RSS reader lenses. Starred (your RSS stars) and Later (the read-later
        // intake) are DIFFERENT stores — both here, as on iPad, not one standing in for the other.
        ScreenFragment.Folder("📰", "Incoming", listOf(
            "📧  Mail" to { openMail(null) },
        ) + mailAccountRows(fragment, ::openMail) + listOf(
            "📰  All" to { openFeed("feed", null) },
            "📖  The Read" to { openFeed("feed", "read") },
            "📺  The Watch" to { openFeed("feed", "watch") },
            "🎧  The Listen" to { openFeed("feed", "listen") },
            // 🔖 Later List sits directly under 🎧 The Listen — Michael: "I'd love later list to
            // appear in feeds like a feed instead of this, instead under '🎧 The Listen' like
            // '🔖 Later List'." It's the end of the media block, above Starred and the rest,
            // because it's the one list here you KEEP rather than a lens over what arrived.
            // Its four lanes ride under it, indented like the mail accounts under Mail: this hub is
            // a flat list of doors, so an accordion isn't available, and a door per lane costs one
            // row each and saves a navigation plus a fold on the other side.
            "🔖  Later List" to { openFeed("later", null) },
        ) + com.toolsboox.plugin.feeds.nw.LaterFeed.LANES.map { (lane, label, _) ->
            "    $label" to { openFeed("later", null, lane) }
        } + listOf(
            "⭐  Starred" to { openFeed("stars", null) },
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
            "★  All Stars" to { CalendarNavigator.toDayNote(fragment, today, "intake") },
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
            // Gram Picks: where every grabbed gram lands, so sorting happens once, later, in one
            // place — instead of choosing between a Pickings board, Star Sort, Synthesize and a
            // board of its own at the moment you grab the thing.
            "◈  Gram Picks" to {
                CalendarNavigator.toDayNote(
                    fragment, LocalDate.now(),
                    com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
                )
            },
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

// ─────────────────────────────────────────────────────────────────────────────
//  The ROOT directory — one findable, sortable place across everything you have
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The two things the root lists that are NOT [com.toolsboox.plugin.calendar.ot.LedgerDocuments]
 * surfaces, given ids in the same namespace so one list can hold all six kinds.
 *
 * Notes are the handwritten day pages: they have no store, no names and no registry — a day either
 * has a page or it doesn't — so there is nothing for [LedgerDocuments] to index and the directory
 * lists the DAYS instead. Tags are the opposite shape: hundreds of them, no dates of their own
 * except the days they were written on. Neither is a document, and pretending either was one (a
 * fake "document" per note page, a fake date per tag) would put rows in the by-date spine that
 * nothing could open. So they are kinds here and only here.
 */
private const val KIND_NOTES = "notes"
private const val KIND_TAGS = "tags"

/** Michael's order, and the order every view here uses: what you catch, what you work it into,
 *  what you write from it, then the two indexes over the whole thing. */
private val DIRECTORY_KINDS = listOf(
    com.toolsboox.plugin.calendar.ot.LedgerDocuments.PICKINGS,
    com.toolsboox.plugin.calendar.ot.LedgerDocuments.SYNTHESIZE,
    com.toolsboox.plugin.calendar.ot.LedgerDocuments.WRITE,
    KIND_NOTES,
    com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEXT_NOTES,
    KIND_TAGS,
)

/**
 * A kind's glyph. The four document surfaces take theirs from [LedgerDocuments] rather than
 * carrying a second copy here — that object's glyph/label/noun trio is the one vocabulary the hub,
 * the day chip and the per-kind directories all speak, and a root directory that renamed Text Notes
 * from ⌗ to 📝 would have you looking for a door you'd never seen. Notes and Tags borrow the hub's
 * own row glyphs for the same reason.
 */
private fun kindGlyph(kind: String): String = when (kind) {
    KIND_NOTES -> "✒"
    KIND_TAGS -> "#"
    else -> com.toolsboox.plugin.calendar.ot.LedgerDocuments.glyph(kind)
}

private fun kindLabel(kind: String): String = when (kind) {
    KIND_NOTES -> "Notes"
    KIND_TAGS -> "Tags"
    else -> com.toolsboox.plugin.calendar.ot.LedgerDocuments.label(kind)
}

/** What a kind's count is counting. "412" alone is a number; "412 days" is an answer. */
private fun kindCountLabel(kind: String, n: Int): String = when (kind) {
    KIND_NOTES -> if (n == 1) "1 day" else "$n days"
    KIND_TAGS -> if (n == 1) "1 tag" else "$n tags"
    else -> if (n == 1) "1 document" else "$n documents"
}

private const val DIRECTORY_PREFS = "ledger_directory"

/** How many rows a folder opens with before it offers to show more. A folder that renders four
 *  hundred rows into a scroll view is a full-page e-ink repaint you then have to scroll past. */
private const val DIRECTORY_PAGE = 40

/**
 * How the directory orders what it lists — at the root AND inside a kind, from one setting.
 *
 * Persisted, because "a directory that forgets how you like it sorted is a directory you re-sort
 * every time". One setting rather than one per surface: the whole point of the root is that the six
 * kinds are one thing seen six ways, and six independent sort settings would be six ways for them
 * to disagree about what "newest" means.
 */
private enum class DirectorySort(val id: String, val label: String) {
    DATE("date", "Date"), NAME("name", "Name"), KIND("kind", "Kind");

    fun next(): DirectorySort = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String?): DirectorySort = entries.firstOrNull { it.id == id } ?: DATE
    }
}

/**
 * Which SPINE the root is showing. Michael's second refinement, in his words: reimagine the date
 * navigation "as a FOLDER — it should feel like opening a drawer or a folder tree (by date / name /
 * kind), not a flat scroll". So the date axis is not a jump you take and a list you land in; it is
 * the same directory hung on a different spine, and this is the switch between them.
 */
private enum class DirectorySpine(val id: String, val label: String, val glyph: String) {
    KIND("kind", "By kind", "▦"), DATE("date", "By date", "📅");

    fun next(): DirectorySpine = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String?): DirectorySpine = entries.firstOrNull { it.id == id } ?: KIND
    }
}

private fun directoryPrefs(context: android.content.Context) =
    context.getSharedPreferences(DIRECTORY_PREFS, 0)

private fun directorySort(context: android.content.Context): DirectorySort =
    DirectorySort.of(directoryPrefs(context).getString("sort", null))

private fun setDirectorySort(context: android.content.Context, sort: DirectorySort) {
    directoryPrefs(context).edit().putString("sort", sort.id).apply()
}

private fun directorySpine(context: android.content.Context): DirectorySpine =
    DirectorySpine.of(directoryPrefs(context).getString("spine", null))

private fun setDirectorySpine(context: android.content.Context, spine: DirectorySpine) {
    directoryPrefs(context).edit().putString("spine", spine.id).apply()
}

/**
 * Which folders are open, persisted so the drawer reopens the way you left it — the same argument
 * as persisting the sort. Copied out of the preference: [android.content.SharedPreferences] hands
 * back the live set and mutating it is documented as undefined.
 */
private fun directoryOpenFolders(context: android.content.Context): MutableSet<String> =
    HashSet(directoryPrefs(context).getStringSet("open", emptySet()).orEmpty())

private fun setDirectoryOpenFolders(context: android.content.Context, open: Set<String>) {
    directoryPrefs(context).edit().putStringSet("open", HashSet(open)).apply()
}

/** One listable thing, whatever kind it came from — the root's common currency. */
private class DirItem(
    val kind: String,
    val key: String,
    val title: String,
    val date: LocalDate,
)

/**
 * Everything of one kind, across all of time.
 *
 * The costs here are deliberately unequal, because the kinds are. Write and Synthesize keep ONE
 * global index, so their whole history is one small file. Pickings and Text Notes keep one small
 * file per day, so this asks the store which days exist ([LedgerDocuments.dates], filenames only)
 * and reads just those. Notes have no index at all, so the list is the day files that exist
 * ([LedgerDocuments.dayDates], filenames again). Tags come off the memoized occurrence store.
 *
 * Nothing here decodes a day file. That is the whole design constraint: a day file is megabytes of
 * inline base64 and this list is built while a menu is opening, on e-ink. It is why the by-kind
 * view lists what the STORES know — the documents you named and kept — and why the implicit daily
 * pages (a day's bare "write", its default board) show up in the by-date spine instead, where
 * exactly one day's worth is read at a time.
 */
private fun directoryItems(context: android.content.Context, kind: String): List<DirItem> {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val today = LocalDate.now()
    return when (kind) {
        // Global index + today's implicit daily document, which is what forSurface already returns.
        docs.WRITE, docs.SYNTHESIZE ->
            docs.forSurface(context, kind, today).map { DirItem(kind, it.key, it.title, it.date) }

        // Per-day stores: the days the store has files for, plus today (which always has a page in
        // principle even before anything is written to it).
        docs.PICKINGS, docs.TEXT_NOTES ->
            (docs.dates(context, kind) + today).distinct().sortedDescending().flatMap { d ->
                docs.forSurface(context, kind, d).map { DirItem(kind, it.key, it.title, d) }
            }

        // A day, titled by its date, opening on note page "0" — the page the Notes door lands on.
        KIND_NOTES -> docs.dayDates(context).map { DirItem(KIND_NOTES, "0", it.toString(), it) }

        // A tag's "date" is the last day it was written on, so sorting by date puts the vocabulary
        // you are currently using at the top rather than the vocabulary you started with.
        KIND_TAGS -> com.toolsboox.plugin.calendar.ot.LedgerTags.list(context).map { t ->
            DirItem(KIND_TAGS, t.tag, "#${t.tag}", t.occurrences.maxOf { it.first })
        }

        else -> emptyList()
    }
}

/**
 * Everything ONE day holds, across every kind — the leaf of the by-date spine.
 *
 * This is where the implicit documents the by-kind view can't afford to hunt for appear: asking
 * [LedgerDocuments.forSurface] for a single date supplies that day's own bare Write page, its
 * default Pickings board and so on, whether or not any store ever recorded them. The filter on
 * `it.date == date` drops the globally-indexed documents forSurface folds in, which belong to
 * their own days elsewhere in this same tree.
 *
 * The Notes row is offered unconditionally rather than checked against the day file. Opening a
 * never-written page shows a blank page, which is that surface's normal answer to a page never
 * written; confirming it first would cost a multi-megabyte read per day row.
 */
private fun dayDocuments(context: android.content.Context, date: LocalDate): List<DirItem> {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val out = mutableListOf<DirItem>()
    for (surface in listOf(docs.PICKINGS, docs.SYNTHESIZE, docs.WRITE, docs.TEXT_NOTES)) {
        docs.forSurface(context, surface, date)
            .filter { it.date == date }
            .forEach { out.add(DirItem(surface, it.key, it.title, date)) }
    }
    out.add(DirItem(KIND_NOTES, "0", "Notes", date))
    return out
}

/**
 * How many #tags each (day, document) carries, in one pass — the badge that tells you a row has
 * items inside it worth landing on.
 *
 * Built once per opening of a directory and handed to every row. The obvious alternative, asking
 * [com.toolsboox.plugin.calendar.ot.LedgerTags] per row, is a scan of every tag's occurrences per
 * row per keystroke of the filter; at the hundreds of tags this vocabulary is aimed at that is the
 * kind of cost that shows up as a sluggish redraw and gets blamed on e-ink.
 *
 * Keyed on the document's BASE key, so a tag written on page three of an essay still marks the
 * essay.
 */
private fun directoryMarkCounts(context: android.content.Context): Map<String, Int> {
    val out = HashMap<String, Int>()
    for (info in com.toolsboox.plugin.calendar.ot.LedgerTags.list(context)) {
        for ((day, page, _) in info.occurrences) {
            val k = "$day|${page.substringBefore('#')}"
            out[k] = (out[k] ?: 0) + 1
        }
    }
    return out
}

/** The #tags written anywhere inside one document — base page and sub-pages — each with the page
 *  it is on and the mark it was written at. */
private fun documentMarks(
    context: android.content.Context,
    date: LocalDate,
    base: String,
): List<Triple<String, String, android.graphics.RectF?>> =
    com.toolsboox.plugin.calendar.ot.LedgerTags.list(context).mapNotNull { info ->
        val occ = info.occurrences.firstOrNull { it.first == date && it.second.substringBefore('#') == base }
        if (occ == null) null else Triple(info.tag, occ.second, occ.third)
    }.sortedBy { it.first }

/** "  ·  page 3" for a sub-page key, nothing for a base page. Shared by the tag rows and the card
 *  rows so a #tag and a card on the same sheet say where they are the same way. */
private fun subPageSuffix(page: String): String {
    val sub = page.substringAfter('#', "").toIntOrNull() ?: return ""
    return "  ·  page ${sub + 1}"
}

/**
 * A CalendarDayService for the directory's one bounded repair.
 *
 * The directory is a file of top-level functions with no injection point of its own, and the repair
 * needs the SAME day reader everything else uses — not a private one. Moshi is provided in the
 * activity component, so the entry point is an activity one; every screen that opens a directory
 * lives in MainActivity, which is an `@AndroidEntryPoint`.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.android.components.ActivityComponent::class)
internal interface DirectoryDayServiceEntryPoint {
    fun calendarDayService(): com.toolsboox.plugin.calendar.fi.CalendarDayService
}

/**
 * Michael's first refinement: "Items must be findable INDIVIDUALLY on the page too — a jump-to-a-
 * specific-item affordance, not only a flat list." So a document is not only a page you open; it is
 * a page you can open AT something.
 *
 * The items are the document's #tags and its CARDS: the two things on a page that have a name and a
 * place. Both are read from an occurrence store — [com.toolsboox.plugin.calendar.ot.LedgerTags] and
 * [com.toolsboox.plugin.calendar.ot.PickingsCards] — and both land through the same
 * `toDayNote(date, page, rect)` with a rect in the same design space, so the two kinds of row are
 * genuinely one kind of row with two sources. That was the condition this list was left waiting on:
 * "When cards get an index of their own they can join this list unchanged." They have, and they did.
 *
 * The card index is still never DECODED from here. Cards arrive from a small per-day sidecar; the
 * only thing that opens a day file is the ⟳ row, which appears only when that day's index is behind
 * its day file (a board made before the index existed, or a day pulled down by WebDAV behind the
 * app's back), does exactly one day, and does it on a background thread. So the menu still opens at
 * the cost of a directory listing, and the repair is a thing you choose rather than a thing that
 * happens to you while you are trying to get somewhere.
 */
private fun showDocumentItems(
    fragment: ScreenFragment,
    title: String,
    date: LocalDate,
    base: String,
) {
    val ctx = fragment.requireContext()
    val store = com.toolsboox.plugin.calendar.ot.PickingsCards
    val marks = documentMarks(ctx, date, base)
    val cards = store.cards(ctx, date, base)
    // Two stats, no decode — safe on the path of opening a menu, which is the whole point.
    val stale = !store.isFresh(ctx, date)
    // Nothing to aim at and nothing to repair: behave exactly as a tap would rather than showing an
    // empty chooser.
    if (marks.isEmpty() && cards.isEmpty() && !stale) {
        CalendarNavigator.toDayNote(fragment, date, base)
        return
    }
    // "Top of the page" leads, so the item list is never a detour on the way to the ordinary thing.
    // Tags then cards, each in its own order: tags alphabetically (they are a vocabulary), cards in
    // reading order down the board (they are a composition). Interleaving them by position was the
    // alternative and reads as a jumble — you come here knowing WHICH KIND of thing you are after.
    val labels = (
        listOf("⌂  Top of the page") +
            marks.map { (tag, page, rect) ->
                // The ✎ is showTagPages' own mark for "this one zooms to the word"; a legacy
                // occurrence with no rect just opens the page, and saying so beats a jump that
                // silently doesn't.
                "${if (rect != null) "✎  " else ""}#$tag${subPageSuffix(page)}"
            } +
            cards.map { c -> "${store.glyphFor(c)}  ${store.labelFor(c)}${subPageSuffix(c.page)}" } +
            (if (stale) listOf("⟳  Read this page's cards…") else emptyList())
        ).toTypedArray()
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle(title)
        .setItems(labels) { _, which ->
            when {
                which == 0 -> CalendarNavigator.toDayNote(fragment, date, base)
                which <= marks.size -> {
                    val (_, page, rect) = marks[which - 1]
                    CalendarNavigator.toDayNote(fragment, date, page, rect)
                }

                which <= marks.size + cards.size -> {
                    val card = cards[which - 1 - marks.size]
                    CalendarNavigator.toDayNote(fragment, date, card.page, card.rect)
                }

                else -> repairDocumentCards(fragment, title, date, base)
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

/**
 * Read ONE day file and rebuild its card index, then re-open the item list with the cards in it.
 *
 * Everything about this is deliberately narrow. One day, chosen by you, off the main thread, through
 * the same [com.toolsboox.plugin.calendar.fi.CalendarDayService] load that carries this fork's
 * defences against a day file too large to read — the 86 MB day that killed the process on launch is
 * the reason a private decoder was not written for this. Idempotent: run it twice and the second run
 * writes the same bytes.
 *
 * It re-opens the list rather than reporting success, because "it worked" is not what you were after
 * — the card you were looking for is.
 */
private fun repairDocumentCards(
    fragment: ScreenFragment,
    title: String,
    date: LocalDate,
    base: String,
) {
    val app = fragment.requireContext().applicationContext
    val service = runCatching {
        dagger.hilt.android.EntryPointAccessors
            .fromActivity(fragment.requireActivity(), DirectoryDayServiceEntryPoint::class.java)
            .calendarDayService()
    }.getOrNull()
    if (service == null) {
        fragment.showMessage("Couldn't open the day reader")
        return
    }
    fragment.showMessage("Reading $date…")
    Thread {
        val n = runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards.backfill(app, service, date)
        }.getOrDefault(-1)
        runCatching {
            fragment.requireActivity().runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                if (n < 0) fragment.showMessage("That day couldn't be read")
                else showDocumentItems(fragment, title, date, base)
            }
        }
    }.apply { isDaemon = true }.start()
}

/** Open a document the way the directory always has: expand it if it has pages, else go to it.
 *  Factored out so the root and the per-kind directory can't drift on what a tap means. */
private fun openDocument(
    fragment: ScreenFragment,
    surface: String,
    doc: com.toolsboox.plugin.calendar.ot.LedgerDocument,
    currentKey: String?,
) {
    if (doc.subPageCount > 1) showDocumentPages(fragment, surface, doc, currentKey)
    else CalendarNavigator.toDayNote(fragment, doc.date, doc.key)
}

/** Open one listed item, whatever kind it is. Only the tapped document pays for its page count —
 *  the count is lazy, and the rows deliberately never ask for it. */
private fun openDirectoryItem(fragment: ScreenFragment, item: DirItem) {
    val ctx = fragment.requireContext()
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    when (item.kind) {
        KIND_TAGS -> com.toolsboox.plugin.calendar.ot.LedgerTags.list(ctx)
            .firstOrNull { it.tag == item.key }
            ?.let { showTagPages(fragment, it) }

        KIND_NOTES -> CalendarNavigator.toDayNote(fragment, item.date, item.key)

        // Text Notes lives in its own fragment rather than on a day-page surface, so it is reached
        // through its own door rather than through CalendarNavigator.
        docs.TEXT_NOTES ->
            com.toolsboox.plugin.textnotes.ui.TextNotesFragment.open(fragment, item.date, item.key)

        else -> {
            val doc = docs.forSurface(ctx, item.kind, item.date).firstOrNull { it.key == item.key }
            if (doc == null) CalendarNavigator.toDayNote(fragment, item.date, item.key)
            else openDocument(fragment, item.kind, doc, null)
        }
    }
}

/** The row that opens a kind's OWN directory — the per-kind doors this root sits above rather than
 *  replaces, reachable from inside the folder that lists the same things. */
private fun kindDoorRow(fragment: ScreenFragment, kind: String, date: LocalDate): DirRow {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    return when (kind) {
        KIND_TAGS -> DirRow("↳", "Open the Tags index…", null, 1) { showTagIndex(fragment) }
        KIND_NOTES -> DirRow("↳", "Open Notes & Tags…", null, 1) {
            com.toolsboox.plugin.calendar.ui.NotesTagsFragment.open(fragment, date, "week")
        }
        docs.TEXT_NOTES -> DirRow("↳", "Open Text Notes…", null, 1) {
            com.toolsboox.plugin.textnotes.ui.TextNotesFragment.open(fragment, date, null)
        }
        else -> DirRow("↳", "Open the ${docs.label(kind)} directory…", null, 1) {
            showDocumentDirectory(fragment, kind, date)
        }
    }
}

private fun sortItems(items: List<DirItem>, sort: DirectorySort): List<DirItem> = when (sort) {
    DirectorySort.DATE ->
        items.sortedWith(compareByDescending<DirItem> { it.date }.thenBy { it.title.lowercase() })
    DirectorySort.NAME ->
        items.sortedWith(compareBy<DirItem> { it.title.lowercase() }.thenByDescending { it.date })
    DirectorySort.KIND ->
        items.sortedWith(
            compareBy<DirItem> { DIRECTORY_KINDS.indexOf(it.kind) }
                .thenByDescending { it.date }.thenBy { it.title.lowercase() }
        )
}

/**
 * One item as a row: its own glyph, its title, and — the part that makes it findable — the date it
 * belongs to and what is addressable inside it. Holding the row lands on one of those things.
 *
 * TWO badges, not one widened badge. 🏷 counts #tags and ❝ counts cards, because they answer
 * different questions about a page — "what is this filed under" and "what is on it" — and a page can
 * easily be heavy in one and empty in the other. A single number summing them would be the one
 * figure that tells you neither.
 *
 * The hold is now offered on every row that has a page behind it, badge or no badge. It used to be
 * withheld when the tag count was zero, which was honest while tags were the only item; with a card
 * index that can be BEHIND its day file, a zero is sometimes "nothing there" and sometimes "not
 * looked yet", and a gesture that silently does nothing is the worst way to tell those apart.
 * [showDocumentItems] still falls straight through to opening the page when there really is nothing
 * to aim at, so a hold on an empty document costs exactly what a tap does.
 */
private fun directoryRowFor(
    fragment: ScreenFragment,
    item: DirItem,
    depth: Int,
    markCounts: Map<String, Int>,
    cardCounts: Map<String, Int>,
    showKind: Boolean,
): DirRow {
    // Two kinds have no page behind them and so nothing to land ON: a tag row IS the index of
    // places, and a Text Note's "key" is a note id its own fragment owns — holding either used to
    // be impossible only because their tag count was always zero, which stopped being a guard the
    // moment the hold became unconditional.
    val isTag = item.kind == KIND_TAGS ||
        item.kind == com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEXT_NOTES
    val n = if (isTag) 0 else markCounts["${item.date}|${item.key}"] ?: 0
    val cards = if (isTag) 0 else cardCounts["${item.date}|${item.key}"] ?: 0
    val detail = buildString {
        if (showKind) append(kindLabel(item.kind)).append("  ·  ")
        append(item.date.toString())
        if (n > 0) append("  ·  🏷 ").append(n)
        if (cards > 0) append("  ·  ❝ ").append(cards)
    }
    return DirRow(
        glyph = kindGlyph(item.kind),
        label = item.title,
        detail = detail,
        depth = depth,
        onHold = if (isTag) null else ({ showDocumentItems(fragment, item.title, item.date, item.key) }),
    ) { openDirectoryItem(fragment, item) }
}

/** One row of a directory list. [closes] is false for the rows that are CONTROLS — a folder header,
 *  a sort chip — because dismissing the dialog you are steering is not steering it. */
private class DirRow(
    val glyph: String,
    val label: String,
    val detail: String? = null,
    val depth: Int = 0,
    val closes: Boolean = true,
    val onHold: (() -> Unit)? = null,
    val onTap: () -> Unit,
)

/**
 * The directory list, and the reason there is only one of it.
 *
 * This is [showTagIndex]'s pattern generalised: a filter field over a column that is REBUILT IN
 * PLACE on every change. On e-ink a full redraw per keystroke is still cheaper than any incremental
 * scheme, and there is no diffing to get wrong — which is also why the folder tree expands by
 * rebuilding rather than by animating a reveal, per the fork's standing rule that a constantly
 * repainting view ghosts on this hardware.
 *
 * [build] is handed the current query and a redraw hook, so a row can change the state the next
 * build reads (a folder's open-ness, the sort) and ask for the list again — an accordion without a
 * second mechanism for accordions.
 */
private fun showDirectoryList(
    fragment: ScreenFragment,
    title: String,
    searchHint: String?,
    empty: String,
    build: (query: String, redraw: () -> Unit) -> List<DirRow>,
) {
    val ctx = fragment.requireContext()
    val density = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

    val body = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }
    val field = searchHint?.let {
        android.widget.EditText(ctx).apply { hint = it; isSingleLine = true; textSize = 15f }
    }
    val col = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(px(16), px(8), px(16), px(8))
        field?.let { addView(it) }
        addView(body)
    }
    lateinit var dialog: androidx.appcompat.app.AlertDialog
    var draw: (() -> Unit)? = null
    val redraw = { draw?.invoke(); Unit }

    draw = {
        body.removeAllViews()
        val rows = build(field?.text?.toString()?.trim().orEmpty(), redraw)
        if (rows.isEmpty()) {
            body.addView(android.widget.TextView(ctx).apply {
                text = empty
                textSize = 14f; setTextColor(0xFF888888.toInt())
                setPadding(px(4), px(12), px(4), px(4))
            })
        }
        for (row in rows) {
            val hold = row.onHold
            body.addView(android.widget.TextView(ctx).apply {
                text = buildString {
                    if (row.glyph.isNotBlank()) { append(row.glyph); append("  ") }
                    append(row.label)
                    if (!row.detail.isNullOrBlank()) { append("  ·  "); append(row.detail) }
                }
                textSize = if (row.depth == 0) 16f else 15f
                setTextColor(if (row.depth == 0) 0xFF000000.toInt() else 0xFF333333.toInt())
                setPadding(px(4 + row.depth * 16), px(10), px(4), px(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { if (row.closes) dialog.dismiss(); row.onTap() }
                if (hold != null) setOnLongClickListener { dialog.dismiss(); hold(); true }
            })
        }
        com.toolsboox.ot.LedgerFonts.applyTree(body)
    }

    field?.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { redraw() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    redraw()

    dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle(title)
        .setView(android.widget.ScrollView(ctx).apply { addView(col) })
        .setNegativeButton("Close", null)
        .create()
    fragment.showModal(dialog)
}

/**
 * THE root directory: one place that answers "what do I have?" across every kind at once.
 *
 * Michael, on the punchlist: one findable, sortable place across ALL #tags AND all Pickings /
 * Synthesis / Text Notes / Writes. Until this existed each kind had a good directory and no way in
 * except from inside that kind — so the question could only be asked by someone who had already
 * gone somewhere and, in going, answered it.
 *
 * Three things ride on the same rebuilt-in-place column:
 *
 *  • **By kind** — the six kinds as folders, each with its count, each opening to its documents and
 *    carrying a row into its OWN directory (which keeps New / Rename / the date walk). The per-kind
 *    doors are untouched; this is a root above them.
 *  • **By date** — the same material hung on the other spine: year → month → day → what you made
 *    that day. Michael's "opening a drawer", not a date picker that fires you somewhere.
 *  • **Find** — type, and both spines collapse into one flat result list across every kind. A folder
 *    tree stops being findable past a few hundred documents; the field is what carries it past that.
 *
 * The sort and the open folders persist, so the drawer reopens the way you left it.
 */
fun showLedgerRootDirectory(fragment: ScreenFragment, date: LocalDate = LocalDate.now()) {
    val ctx = fragment.requireContext()
    // Read each kind ONCE per opening, not once per keystroke: the by-kind counts walk index files,
    // and re-walking them behind the keyboard is the difference between a filter that keeps up and
    // one that stutters. The tag marks are gathered once for the same reason.
    val cache = HashMap<String, List<DirItem>>()
    fun items(kind: String): List<DirItem> = cache.getOrPut(kind) { directoryItems(ctx, kind) }
    val marks = directoryMarkCounts(ctx)
    // The card tally is ONE small file for the whole ledger, read here for the same reason the tag
    // marks are: a badge is wanted on every row, and asking per row — or opening a per-day sidecar
    // per row — is the cost that shows up as a sluggish redraw and gets blamed on e-ink.
    val cardCounts = com.toolsboox.plugin.calendar.ot.PickingsCards.counts(ctx)
    // How far each folder has been unrolled — the "…and N more" row raises its own cap and redraws,
    // which is the same in-place move as opening a folder rather than a second kind of paging.
    val unrolled = HashMap<String, Int>()

    showDirectoryList(
        fragment,
        title = "Directory",
        searchHint = "Find anything",
        empty = "Nothing in the ledger yet.",
    ) { query, redraw ->
        val sort = directorySort(ctx)
        val spine = directorySpine(ctx)
        val open = directoryOpenFolders(ctx)
        val rows = mutableListOf<DirRow>()

        fun toggle(id: String) {
            if (id in open) open.remove(id) else open.add(id)
            setDirectoryOpenFolders(ctx, open)
        }

        fun caret(id: String) = if (id in open) "▾  " else "▸  "

        // The two dials, at the top where a dial belongs. Both cycle in place.
        rows += DirRow(spine.glyph, spine.label, "tap for ${spine.next().label}", closes = false) {
            setDirectorySpine(ctx, spine.next()); redraw()
        }
        rows += DirRow("⇅", "Sort · ${sort.label}", "date → name → kind", closes = false) {
            setDirectorySort(ctx, sort.next()); redraw()
        }

        if (query.isNotEmpty()) {
            // Searching flattens both spines: when you are looking for a named thing, which folder
            // it happens to live in is the one fact you don't have.
            val q = query.lowercase()
            val hits = sortItems(
                DIRECTORY_KINDS.flatMap { items(it) }.filter { it.title.lowercase().contains(q) },
                sort
            )
            val cap = unrolled["find"] ?: DIRECTORY_PAGE
            if (hits.isEmpty()) {
                rows += DirRow("", "Nothing matches “$query”.", null, 1, closes = false) {}
            }
            for (item in hits.take(cap)) rows += directoryRowFor(fragment, item, 1, marks, cardCounts, true)
            if (hits.size > cap) {
                rows += DirRow("", "…and ${hits.size - cap} more", "tap to show more", 1, closes = false) {
                    unrolled["find"] = cap + DIRECTORY_PAGE; redraw()
                }
            }
            return@showDirectoryList rows
        }

        when (spine) {
            DirectorySpine.KIND -> for (kind in DIRECTORY_KINDS) {
                val list = items(kind)
                val id = "kind:$kind"
                rows += DirRow(
                    kindGlyph(kind), caret(id) + kindLabel(kind), kindCountLabel(kind, list.size),
                    closes = false
                ) { toggle(id); redraw() }
                if (id !in open) continue
                rows += kindDoorRow(fragment, kind, date)
                val ordered = sortItems(list, sort)
                val cap = unrolled[id] ?: DIRECTORY_PAGE
                for (item in ordered.take(cap)) rows += directoryRowFor(fragment, item, 1, marks, cardCounts, false)
                if (ordered.size > cap) {
                    rows += DirRow("", "…and ${ordered.size - cap} more", "tap to show more", 1, closes = false) {
                        unrolled[id] = cap + DIRECTORY_PAGE; redraw()
                    }
                }
            }

            DirectorySpine.DATE -> {
                // The spine's skeleton is every date ANY kind knows about — cheap, because each
                // kind's dates came off filenames and indexes. A day's contents are read only when
                // that day's folder is opened, so walking the tree costs what you look at.
                val days = DIRECTORY_KINDS.flatMap { items(it) }.map { it.date }
                    .distinct().sortedDescending()
                val locale = Locale.getDefault()
                val byYear = days.groupBy { it.year }
                for (year in byYear.keys.sortedDescending()) {
                    val yearDays = byYear.getValue(year)
                    val yid = "y:$year"
                    rows += DirRow(
                        "🗂", caret(yid) + year, kindCountLabel(KIND_NOTES, yearDays.size), closes = false
                    ) { toggle(yid); redraw() }
                    if (yid !in open) continue
                    val byMonth = yearDays.groupBy { it.monthValue }
                    for (month in byMonth.keys.sortedDescending()) {
                        val monthDays = byMonth.getValue(month)
                        val mid = "$yid/m:$month"
                        val name = java.time.Month.of(month)
                            .getDisplayName(java.time.format.TextStyle.FULL, locale)
                        rows += DirRow(
                            "📁", caret(mid) + name, kindCountLabel(KIND_NOTES, monthDays.size), 1,
                            closes = false
                        ) { toggle(mid); redraw() }
                        if (mid !in open) continue
                        for (day in monthDays) {
                            val did = "$mid/d:$day"
                            val dow = day.dayOfWeek
                                .getDisplayName(java.time.format.TextStyle.SHORT, locale)
                            rows += DirRow(
                                "🗓", caret(did) + "$dow ${day.dayOfMonth}", day.toString(), 2,
                                closes = false
                            ) { toggle(did); redraw() }
                            if (did !in open) continue
                            for (item in sortItems(dayDocuments(ctx, day), sort)) {
                                rows += directoryRowFor(fragment, item, 3, marks, cardCounts, true)
                            }
                        }
                    }
                }
            }
        }

        // Holding a row is not a thing anyone guesses, and an undiscoverable affordance is not one.
        // This row says what hold does AND is a working door to the index it lands you in.
        rows += DirRow("🏷", "Hold a document to land on a #tag inside it", "Tags index") {
            showTagIndex(fragment)
        }
        rows
    }
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
 *
 * It now rides the same rebuilt-in-place column as the root ([showDirectoryList]), which is what
 * gives it the three things a list of documents wants and a fixed `setItems` array can't have: the
 * persisted sort, a filter field once there are enough documents to need one, and a HOLD on a
 * document that lands you on an item inside it rather than at the top of it. The rows, the extras
 * and what each of them does are otherwise exactly what they were.
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
    // Built ONCE, outside the redraw: a document's page count is lazy and a miss costs a streamed
    // read of the day file, so rebuilding this list per keystroke would put a disk scan per
    // document behind the keyboard. Held across redraws, each count is paid for at most once.
    val docs = docs0.forSurface(ctx, surface, date, knownPageKeys)
    val marks = directoryMarkCounts(ctx)
    val cardCounts = com.toolsboox.plugin.calendar.ot.PickingsCards.counts(ctx)
    val glyph = docs0.glyph(surface)
    val label = docs0.label(surface)
    // The document you're standing in is marked, so the list answers "where am I" as well as "what
    // else is there" — the same "· here" the iPad's directory uses. One shape on both platforms.
    val hereBase = currentKey?.substringBefore('#')
    val renameable = docs.any { docs0.canRename(surface, it.key) }
    // Which of the two deletion verbs this listing can honestly offer. Untitling needs something
    // with a title on it; deleting is a property of the SURFACE (see LedgerDocuments.canDelete —
    // boards and text notes answer the question elsewhere).
    val titled = docs.filter { it.named && docs0.canUntitle(surface, it.key) }
    val deletable = docs0.canDelete(surface) && docs.isNotEmpty()

    showDirectoryList(
        fragment,
        title = "$label · $date",
        // The field appears only once the list is long enough to hide something. On a surface with
        // three documents it would be a box asking you to type what you can already see.
        searchHint = if (docs.size > 6) "Find a ${docs0.noun(surface)}" else null,
        empty = "Nothing here yet.",
    ) { query, redraw ->
        val sort = directorySort(ctx)
        val rows = mutableListOf<DirRow>()
        rows += DirRow("⇅", "Sort · ${sort.label}", "date → name → kind", closes = false) {
            setDirectorySort(ctx, sort.next()); redraw()
        }
        val q = query.lowercase()
        val shown = sortDocuments(docs.filter { q.isEmpty() || it.title.lowercase().contains(q) }, sort)
        if (shown.isEmpty()) rows += DirRow("", "Nothing matches “$query”.", null, 1, closes = false) {}
        for (doc in shown) {
            val n = marks["${doc.date}|${doc.key}"] ?: 0
            val cards = cardCounts["${doc.date}|${doc.key}"] ?: 0
            val detail = buildString {
                if (doc.subPageCount > 1) append("${doc.subPageCount} pages")
                // A named document off today shows the day it was started; without it a list of
                // essay titles says nothing about when any of them happened.
                if (doc.date != date) { if (isNotEmpty()) append("  ·  "); append(doc.date.toString()) }
                if (n > 0) { if (isNotEmpty()) append("  ·  "); append("🏷 $n") }
                // On a Pickings board this is the badge that matters — a board's whole content is
                // its cards, and until now the row could only say how many WORDS were on it.
                if (cards > 0) { if (isNotEmpty()) append("  ·  "); append("❝ $cards") }
                if (doc.key == hereBase) { if (isNotEmpty()) append("  ·  "); append("here") }
            }
            rows += DirRow(
                glyph, doc.title, detail,
                // Text Notes are listable here but not landable: their key is a note id, not a page
                // key, so there is no page for a hold to open at anything.
                onHold = if (surface == docs0.TEXT_NOTES) null
                else ({ showDocumentItems(fragment, doc.title, doc.date, doc.key) }),
            ) {
                // Expand rather than jump: a document with pages should show you its pages, so
                // "which page of the essay" is answerable from the same place as "which essay".
                openDocument(fragment, surface, doc, currentKey)
            }
        }
        rows += DirRow("＋", "New ${docs0.noun(surface)}…") { promptNewDocument(fragment, surface, date) }
        if (renameable) rows += DirRow("✎", "Rename…") { promptRenameDocument(fragment, surface, date, docs) }
        // The two deletions, in that order and never merged into one row. The untitle row appears
        // only when something on this surface actually has a title to remove, so it isn't a control
        // that does nothing on a shelf of date-titled documents.
        if (titled.isNotEmpty()) {
            rows += DirRow("⌫", "Remove a title…", "keeps the pages") {
                promptUntitleDocument(fragment, surface, date, docs)
            }
        }
        if (deletable) {
            rows += DirRow("🗑", "Delete a ${docs0.noun(surface)}…", "the ink goes too") {
                promptDeleteDocument(fragment, surface, date, docs, currentKey)
            }
        }
        // The one place a SWEEP is offered, and it is offered rather than run.
        //
        // Every board made before the card index existed has a day file the index has never read,
        // and repairing them one hold at a time is a poor answer for a year of boards. So the row
        // appears only when this listing actually contains days the index is behind on, says how
        // many, and reads them in the background — bounded by COUNT rather than by time, because
        // one 40 MB day is worth thirty ordinary ones and a deadline would stop in the middle of
        // whichever one it was on. Everything about it is idempotent: run it again and the days it
        // already read are fresh and skipped.
        val staleDates = docs.map { it.date }.distinct()
            .filter { !com.toolsboox.plugin.calendar.ot.PickingsCards.isFresh(ctx, it) }
        if (staleDates.isNotEmpty()) {
            val what = if (staleDates.size == 1) "1 day" else "${staleDates.size} days"
            rows += DirRow("⟳", "Read the cards on $what…", "not indexed yet", closes = false) {
                sweepDocumentCards(fragment, staleDates, redraw)
            }
        }
        // The same surface on another day — a directory should walk time as well as pages,
        // which is the move the iPad's directory ends on too.
        rows += DirRow("📅", "Go to a date…") { showDocumentDatePicker(fragment, surface, date, currentKey) }
        // Up, not out: this kind is one folder of a larger drawer, and the way back to the rest of
        // it should not be "close this and find the hub".
        rows += DirRow("‹", "All of the Ledger…") { showLedgerRootDirectory(fragment, date) }
        rows
    }
}

/** How many days one tap of the sweep will read. Thirty ordinary days is a second or two of IO and
 *  a handful of small writes; it is also more days than any one directory listing usually shows, so
 *  in practice one tap finishes the job and the row stops appearing. A bigger number would only buy
 *  a longer wait for the same outcome. */
private const val CARD_SWEEP_LIMIT = 30

/** Read up to [CARD_SWEEP_LIMIT] of [dates] into the card index, newest first, off the main thread,
 *  then rebuild the list in place so the badges appear where they were missing. */
private fun sweepDocumentCards(
    fragment: ScreenFragment,
    dates: List<LocalDate>,
    redraw: () -> Unit,
) {
    val app = fragment.requireContext().applicationContext
    val service = runCatching {
        dagger.hilt.android.EntryPointAccessors
            .fromActivity(fragment.requireActivity(), DirectoryDayServiceEntryPoint::class.java)
            .calendarDayService()
    }.getOrNull()
    if (service == null) {
        fragment.showMessage("Couldn't open the day reader")
        return
    }
    fragment.showMessage("Reading…")
    Thread {
        val done = runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsCards
                .backfillMissing(app, service, dates, CARD_SWEEP_LIMIT)
        }.getOrDefault(0)
        runCatching {
            fragment.requireActivity().runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                fragment.showMessage(if (done == 1) "Read 1 day." else "Read $done days.")
                redraw()
            }
        }
    }.apply { isDaemon = true }.start()
}

/**
 * The persisted sort, applied inside one kind.
 *
 * "By kind" has nothing left to group by once you are already inside a kind, so it falls back to
 * the store's own order — the daily document first, then the named ones as the index holds them,
 * which is exactly what this list looked like before it could be sorted at all. Reordering it to
 * something arbitrary just so all three settings visibly "do something" would be worse than a
 * setting that honestly does nothing here.
 */
private fun sortDocuments(
    docs: List<com.toolsboox.plugin.calendar.ot.LedgerDocument>,
    sort: DirectorySort,
): List<com.toolsboox.plugin.calendar.ot.LedgerDocument> = when (sort) {
    DirectorySort.DATE -> docs.sortedWith(
        compareByDescending<com.toolsboox.plugin.calendar.ot.LedgerDocument> { it.date }
            .thenBy { it.title.lowercase() }
    )
    DirectorySort.NAME -> docs.sortedWith(
        compareBy<com.toolsboox.plugin.calendar.ot.LedgerDocument> { it.title.lowercase() }
            .thenByDescending { it.date }
    )
    DirectorySort.KIND -> docs
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

/**
 * Name a new document and open it. Blank is allowed — the store falls back to the date.
 *
 * `internal` rather than private because the day page's ‹ N › menu offers the same "＋ New
 * writing…" row the directory does, and a second copy of a naming dialog is a second thing to keep
 * in step — the wording, the hint, and the "blank is allowed" rule all have to agree, or naming a
 * piece from the surface would mean something subtly different from naming it from the directory.
 */
internal fun promptNewDocument(fragment: ScreenFragment, surface: String, date: LocalDate) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    promptTitle(
        fragment, surface,
        dialogTitle = "New ${docs0.noun(surface)}",
        fieldHint = "What's this ${docs0.noun(surface)} about?",
        seed = null,
        saveLabel = "Create",
    ) { name, face ->
        val doc = docs0.create(ctx, surface, name, date) ?: return@promptTitle
        // The face is filed AFTER the document exists, because until [create] returns there is no
        // key to file it under — a minted key is the store's to choose, not this dialog's.
        face?.let { com.toolsboox.plugin.calendar.ot.LedgerTitleInk.put(ctx, surface, doc.key, doc.date, it) }
        CalendarNavigator.toDayNote(fragment, doc.date, doc.key)
    }
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
            promptTitle(
                fragment, surface,
                dialogTitle = "Rename ${docs0.noun(surface)}",
                fieldHint = doc.date.toString(),
                seed = if (doc.named) doc.title else null,
                saveLabel = "Save",
            ) { name, face ->
                docs0.rename(ctx, surface, doc.key, name, doc.date)
                // A rename with a blank pad leaves the stored face alone: he came here to change the
                // words, and silently dropping the handwriting because he didn't write it out again
                // would make every rename a destructive act.
                face?.let {
                    com.toolsboox.plugin.calendar.ot.LedgerTitleInk.put(ctx, surface, doc.key, doc.date, it)
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * Title THE DOCUMENT YOU ARE STANDING IN — the surface's own naming, as against the directory's.
 *
 * [promptRenameDocument] asks "rename which one?" first, and that is right where it lives: the
 * directory lists documents from many days and may have been opened from the hub with no current
 * page at all, so it has to be told which. From the page itself the question is already answered —
 * you are IN the thing — and asking again is the difference between titling your work and
 * administering a list of titles. Text Notes never asks: the title field belongs to the note on
 * screen. This is that, for a surface whose title has nowhere to live but a menu.
 *
 * Seeded with the EXPLICIT name only, [promptRenameDocument]'s rule and iOS's
 * (`PlannerShell.documentNamingButtons`): pre-filling an unnamed document with its date would make
 * "rename" mean "confirm the date as the name", which is the one answer the date default already
 * gives for free.
 *
 * [onRenamed] lets the caller redraw — the page header carries the name, so a rename that didn't
 * repaint would leave the old title on screen until the next navigation.
 */
internal fun promptRenameCurrentDocument(
    fragment: ScreenFragment,
    surface: String,
    key: String,
    date: LocalDate,
    onRenamed: () -> Unit = {},
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val doc = docs0.forSurface(ctx, surface, date).firstOrNull { it.key == key }
    promptTitle(
        fragment, surface,
        dialogTitle = "Name this ${docs0.noun(surface)}",
        fieldHint = date.toString(),
        seed = if (doc != null && doc.named) doc.title else null,
        saveLabel = "Save",
    ) { name, face ->
        docs0.rename(ctx, surface, key, name, date)
        face?.let { com.toolsboox.plugin.calendar.ot.LedgerTitleInk.put(ctx, surface, key, date, it) }
        // The redraw is what puts BOTH halves on the page — the typed name in the header and the
        // written one top right — without waiting for a navigation.
        onRenamed()
    }
}

// ── DELETION: two verbs, kept apart ───────────────────────────────────────────────────────────
//
// Michael's instruction, after the naming work deliberately left this open with its reasons written
// down: do NOT ship one ambiguous "Delete". A Write document is two things in two places — a TITLE
// in the index and INK in the day JSON under the document's key — so "delete it" has two honest
// meanings and one button would have to choose one silently.
//
//  ⌫ Remove the title  — non-destructive, no confirmation, undone by naming it again.
//  🗑 Delete …         — destructive, says how many pages and that the ink goes with them.
//
// They live here, beside [promptRenameDocument] and [promptRenameCurrentDocument], because a
// document's verbs should sit together: naming, renaming, untitling and deleting are one small
// vocabulary and splitting them across files is how they drift out of agreement.

/**
 * Take the title off the document you name, and say so.
 *
 * No confirmation, on purpose. Nothing is lost — the pages stay exactly where they are and the
 * document goes back to wearing its date — and the undo is the ✎ row two lines up. A confirmation
 * on a reversible act only teaches you to dismiss confirmations.
 *
 * See [com.toolsboox.plugin.calendar.ot.LedgerDocuments.untitle] for why this blanks the name rather
 * than dropping the index row: for a minted document that row is the only thing that makes its pages
 * reachable.
 */
internal fun untitleDocument(
    fragment: ScreenFragment,
    surface: String,
    key: String,
    date: LocalDate,
    title: String,
    onDone: () -> Unit = {},
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    docs0.untitle(ctx, surface, key, date)
    fragment.showMessage("“$title” is untitled again — the pages are still there.")
    onDone()
}

/** "Remove which title?" — the directory's own list, filtered to the ones that actually have one. */
private fun promptUntitleDocument(
    fragment: ScreenFragment,
    surface: String,
    date: LocalDate,
    docs: List<com.toolsboox.plugin.calendar.ot.LedgerDocument>,
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val target = docs.filter { it.named && docs0.canUntitle(surface, it.key) }
    if (target.isEmpty()) return
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Remove which title?")
        .setItems(target.map { it.title }.toTypedArray()) { _, which ->
            val doc = target[which]
            untitleDocument(fragment, surface, doc.key, doc.date, doc.title)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** "Delete which ‹noun›?" — then the count, then the question. */
private fun promptDeleteDocument(
    fragment: ScreenFragment,
    surface: String,
    date: LocalDate,
    docs: List<com.toolsboox.plugin.calendar.ot.LedgerDocument>,
    currentKey: String?,
) {
    val ctx = fragment.requireContext()
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    if (docs.isEmpty()) return
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Delete which ${docs0.noun(surface)}?")
        .setItems(docs.map { it.title }.toTypedArray()) { _, which ->
            val doc = docs[which]
            confirmDeleteDocument(fragment, surface, doc.key, doc.title, doc.date) {
                // Only if you were standing in it. Landing you on the surface's own daily page for
                // that day is the nearest thing to "where you were" that still exists.
                if (currentKey?.substringBefore('#') == doc.key) {
                    CalendarNavigator.toDayNote(fragment, doc.date, docs0.defaultKey(surface))
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * Find the pages, ask the real question, then clear them.
 *
 * THE COUNT COMES OFF THE DISK. [com.toolsboox.plugin.calendar.ot.LedgerDocumentPages.locate] streams
 * the day files looking for the document's page keys; it does not ask the index, which holds a title
 * and nothing else, and it does not ask [LedgerDocument.subPageCount], which only ever looks at the
 * document's home date. That difference is the point: a document's pages can have been carried to
 * other days by "📅 Go to a date…" or by the centre pill's tap-to-today, and a confirmation that
 * under-counted them would be promising to delete less than it deletes.
 *
 * The scan is a disk walk, so it runs on a background thread and the dialog waits for it. On e-ink
 * that is a real pause and it gets a "Looking…" rather than a frozen menu.
 */
internal fun confirmDeleteDocument(
    fragment: ScreenFragment,
    surface: String,
    key: String,
    title: String,
    date: LocalDate,
    onDeleted: () -> Unit = {},
) {
    val app = fragment.requireContext().applicationContext
    val service = runCatching {
        dagger.hilt.android.EntryPointAccessors
            .fromActivity(fragment.requireActivity(), DirectoryDayServiceEntryPoint::class.java)
            .calendarDayService()
    }.getOrNull()
    if (service == null) {
        fragment.showMessage("Couldn't open the day reader")
        return
    }
    fragment.showMessage("Looking for its pages…")
    Thread {
        val pages = runCatching {
            com.toolsboox.plugin.calendar.ot.LedgerDocumentPages.locate(app, key, date)
        }.getOrNull()
        runCatching {
            fragment.requireActivity().runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                if (pages == null) {
                    fragment.showMessage("Couldn't read the days this one is on — nothing deleted.")
                    return@runOnUiThread
                }
                askAndErase(fragment, service, surface, key, title, date, pages, onDeleted)
            }
        }
    }.apply { isDaemon = true }.start()
}

/** The question itself. Every number in it was counted off a day file a moment ago. */
private fun askAndErase(
    fragment: ScreenFragment,
    service: com.toolsboox.plugin.calendar.fi.CalendarDayService,
    surface: String,
    key: String,
    title: String,
    date: LocalDate,
    pages: com.toolsboox.plugin.calendar.ot.DocumentPages,
    onDeleted: () -> Unit,
) {
    val ctx = fragment.requireContext()
    val app = ctx.applicationContext
    val docs0 = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val noun = docs0.noun(surface)

    // Nothing on disk: there is no ink to warn about, so don't invent a warning. This is a title
    // over an empty document and saying so plainly is the whole message.
    if (pages.isEmpty) {
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Delete “$title”?")
            .setMessage("Nothing has been written in this $noun — there are no pages to lose. The title goes.")
            .setPositiveButton("Delete the title") { _, _ ->
                docs0.forget(app, surface, key, date)
                fragment.showMessage("Deleted “$title”.")
                runCatching { fragment.onExternalGramPlaced(key) }
                onDeleted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return
    }

    val n = pages.pageCount
    val pageWord = if (n == 1) "1 page" else "$n pages"
    val body = buildString {
        append("Deleting this $noun deletes its $pageWord and all the ink on them. ")
        // A document whose pages ended up on more than one day is unusual enough that the
        // confirmation has to say so — otherwise the number looks wrong and you cancel a correct
        // delete, or worse, accept a bigger one than you thought.
        if (pages.dates.size > 1) {
            append("They are on ${pages.dates.size} days (")
            append(pages.dates.joinToString(", ") { it.toString() })
            append("). ")
        }
        append("This cannot be undone.")
    }
    androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle("Delete “$title”?")
        .setMessage(body)
        // The last thing you touch says what it does. "OK" against a message you have already
        // stopped reading is how a destructive button gets pressed by accident.
        .setPositiveButton("Delete $pageWord") { _, _ ->
            fragment.showMessage("Deleting…")
            Thread {
                val result = runCatching {
                    com.toolsboox.plugin.calendar.ot.LedgerDocumentPages.erase(app, service, pages)
                }.getOrNull()
                // The index entry goes LAST, and only for the days that were actually cleared. A
                // day we could not read still holds its pages, and dropping the title while they
                // survive is the one outcome this whole design is arranged to prevent.
                val stranded = result?.unreadable.orEmpty()
                if (result != null && stranded.isEmpty()) docs0.forget(app, surface, key, date)
                runCatching {
                    fragment.requireActivity().runOnUiThread {
                        if (!fragment.isAdded) return@runOnUiThread
                        when {
                            result == null ->
                                fragment.showMessage("Couldn't delete “$title” — nothing was changed.")
                            stranded.isNotEmpty() -> fragment.showMessage(
                                "Kept “$title”: ${stranded.size} day(s) couldn't be read, " +
                                    "so its pages are still there."
                            )
                            else -> fragment.showMessage("Deleted “$title” and its $pageWord.")
                        }
                        // RE-READ, NEVER BLIND-SAVE. If the day page below is showing one of the
                        // days just rewritten, its in-memory copy still holds the ink we removed and
                        // its next pen-up save would write every stroke back. This is the same
                        // telling a background placement gives, for the same reason.
                        runCatching { fragment.onExternalGramPlaced(key) }
                        if (stranded.isEmpty()) onDeleted()
                    }
                }
            }.apply { isDaemon = true }.start()
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
    //
    // That pattern is now [showDirectoryList] and every directory in this file shares it — this one
    // is where it came from, so it uses it rather than keeping the original hand-rolled copy around
    // to drift from its own children.
    showDirectoryList(
        fragment,
        title = "Tags",
        searchHint = "Find a tag",
        empty = "No #tags yet.",
    ) { query, redraw ->
        val sort = directorySort(ctx)
        val rows = mutableListOf<DirRow>()
        rows += DirRow("⇅", "Sort · ${sort.label}", "date → name → kind", closes = false) {
            setDirectorySort(ctx, sort.next()); redraw()
        }
        val q = query.lowercase()
        val shown = tags.filter { q.isEmpty() || it.tag.contains(q) }
        // Tags are one kind, so "by kind" has nothing to group — it keeps the store's own order,
        // which is most-used first, and that is the ordering this index has always opened with.
        val ordered = when (sort) {
            DirectorySort.NAME -> shown.sortedBy { it.tag }
            DirectorySort.DATE -> shown.sortedByDescending { t -> t.occurrences.maxOf { it.first } }
            DirectorySort.KIND -> shown
        }
        if (ordered.isEmpty()) rows += DirRow("", "Nothing matches “$query”.", null, 1, closes = false) {}
        for (t in ordered) {
            rows += DirRow("#", t.tag, "${t.occurrences.size} pages") { showTagPages(fragment, t) }
        }
        rows += DirRow("‹", "All of the Ledger…") { showLedgerRootDirectory(fragment) }
        rows
    }
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

/**
 * The configured mailboxes, as directory rows under Mail — the Boox half of the iPad's accounts
 * rail, where each account is listed beside the feeds rather than hidden behind a chip inside the
 * inbox. Reaching one mailbox should cost the same as reaching one feed; it was costing a
 * navigation plus a chip plus an accordion.
 *
 * Nothing is listed when there is only one account: a lone row under "Mail" says nothing "Mail"
 * didn't already say, and a directory earns its length by every row being a real choice.
 */
private fun mailAccountRows(
    fragment: ScreenFragment,
    open: (String?) -> Unit,
): List<Pair<String, () -> Unit>> {
    val accounts = com.toolsboox.plugin.mail.MailAccountStore.all(fragment.requireContext())
    if (accounts.size < 2) return emptyList()
    return accounts.map { a -> "    @  ${a.display}" to { open(a.id) } }
}
