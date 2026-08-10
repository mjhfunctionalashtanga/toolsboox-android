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
        is com.toolsboox.plugin.mail.ui.MailComposeFragment -> "Incoming"
        is com.toolsboox.plugin.calendar.ui.RolodexFragment,
        is com.toolsboox.plugin.calendar.ui.LedgerItemsFragment -> "Desk"
        // Quick Wins and Missed Connections are the two reflective surfaces that survived the
        // garden; they call Daily home beside Gratitude and Self Executive.
        is com.toolsboox.plugin.calendar.ui.QuickWinsFragment,
        is com.toolsboox.plugin.calendar.ui.MissedRhizomesFragment -> "Daily"
        // The map and the rhizome view it opens are ways of SEEING connections, which is thinking —
        // so they call the Ask group home now rather than the retired Garden.
        is com.toolsboox.plugin.calendar.ui.LedgerMapFragment,
        is com.toolsboox.plugin.calendar.ui.LedgerRhizomeFragment -> "Ask"
        is com.toolsboox.plugin.textnotes.ui.TextNotesFragment,
        is com.toolsboox.plugin.calendar.ui.NotesTagsFragment -> "Notes"
        is com.toolsboox.plugin.reader.ui.ReaderFragment -> "Bookshelf"
        // ReadingLogFragment serves both Search and History; either way its folder home is Log.
        is com.toolsboox.plugin.calendar.ui.ReadingLogFragment -> "Log"
        // The chat surface IS the Ask group's centre — Chat, and the two thinking rows that open it
        // with a question already drafted.
        is com.toolsboox.plugin.chat.ui.LedgerChatFragment -> "Ask"
        // The site-facing native surfaces moved out of the retired Community folder into Sites,
        // beside Publish and Posts — they are all the same act: your sites, worked from here.
        is com.toolsboox.plugin.calendar.ui.SiteBoardsFragment,
        is com.toolsboox.plugin.calendar.ui.CorrespondenceFragment,
        is com.toolsboox.plugin.calendar.ui.MessagesFragment,
        is com.toolsboox.plugin.calendar.ui.BlueskyFragment,
        is com.toolsboox.plugin.calendar.ui.RosterFragment,
        is com.toolsboox.plugin.calendar.ui.PublishFragment,
        is com.toolsboox.plugin.calendar.ui.PostsBrowserFragment -> "Sites"
        // The Fluent portals are the member-facing WEB face of the same sites, so they get their
        // own folder and their own home.
        is com.toolsboox.plugin.calendar.ui.SiteWebFragment -> "Fluent"
        is com.toolsboox.plugin.calendar.ui.CalendarSettingsFragment,
        is com.toolsboox.plugin.cloud.ui.CloudFragment -> "Settings"
        // The day surface hosts many pages: the day grid itself is "Today" (no folder), the
        // catch-and-sort pages live under Filter, the reflective ones under Daily, and the freeform
        // note pages under Notes. Same key vocabulary as the fragment's own sectionEmoji().
        is com.toolsboox.plugin.calendar.ui.CalendarDayFragment ->
            // Fold a "#n" sub-page (write#1, grid#2) onto its base so it calls the same folder home
            // as its base page — the sub-page tail only matters to the pager, never to the hub.
            when (val page = fragment.currentNotePage()?.substringBefore('#')) {
                null, "default", com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE -> null
                // One folder for the whole daily practice — the catching-and-sorting stations and
                // the pages he keeps for himself. Filter was folded into Daily ("yah, daily").
                "intake",
                com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS,
                "gratitude", "selfexec" -> "Daily"
                "grid", "sketch", "write" -> "Notes"
                // A NAMED document ("pickings-…", "write-…") calls the same folder home as the
                // surface it belongs to — the "-<millis>" tail is storage, never a different kind of
                // page. Synthesize documents are the exception worth naming: their surface retired,
                // so a "synthesize-…" page still OPENS (from the Directory, or from any link that
                // addresses it) but belongs to no folder, and the hub honestly expands nothing.
                else -> when (com.toolsboox.plugin.calendar.ot.LedgerDocuments.surfaceOf(page)) {
                    com.toolsboox.plugin.calendar.ot.LedgerDocuments.PICKINGS -> "Daily"
                    com.toolsboox.plugin.calendar.ot.LedgerDocuments.SYNTHESIZE -> null
                    else -> "Notes"   // lined pages ("0", "1", …), named grids, jots and writings
                }
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
     * Open the Feed Ledger wearing 📧 The Mail lens, unified or narrowed to one account.
     *
     * Mail's one home is the lens now — the standalone inbox screen retired once Michael had
     * lived with The Mail ("Retire") — so this door rides the same one-shot FeedSelection
     * channel as every other feed view: the kind picks the lens, the mailbox rides beside it,
     * and the mode lands on Unread, which is the lens's own cold-entry view. (The narrowing
     * used to travel through the `account_filter` preference the screen read on arrival; the
     * lens maintains that preference itself now, for compose's sake.)
     */
    fun openMail(accountId: String?) {
        FeedSelection.mailMailbox = accountId
        openFeed("feed", "mail")
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
        // Michael's punchlist №6: "All Books in Bookshelf does not go to [the books]". It went to
        // the reader holding whatever was last open — a book, not a shelf. Now it goes to the shelf.
        ("📚  All books" to { nav.navigate(R.id.action_to_bookshelf) })
    val bookshelf = ScreenFragment.Folder("📚", "Bookshelf", bookRows, expanded = home == "Bookshelf")

    // Read-aloud transport, only while something is playing — reachable from every surface.
    val nowPlaying = if (com.toolsboox.ui.plugin.LedgerPlayer.isActive)
        ScreenFragment.Folder("▶️", "Now Playing",
            action = { com.toolsboox.ui.plugin.LedgerPlayer.showModal(fragment.requireContext()) })
    else null

    // What to open when the Search row is tapped: the Log/history surface, arriving ready to
    // type — the one field that searches the whole Ledger, OCR/text layer and semantic layer both.
    val openSearch = {
        ReadingLogSelection.focusSearch = true
        openHistory(null)
    }

    // Michael's order, 2026-08: Ask · Today · Incoming · Filter · Daily · Desk · Notes · Bookshelf ·
    // Sites · Fluent · Log · Settings.
    //
    // The shape of it is worth saying plainly, because it is the thing the iOS twin has to copy.
    // The hub used to be arranged by WHERE a thing lived (a Flow of surfaces, a Garden of surfaces);
    // it is arranged by WHAT YOU ARE DOING now. Ask leads because a question is the commonest way
    // in. Filter is what Flow became — his words were "no more flow" — and it holds only the three
    // stations that catch and sort what arrived. Daily holds what you do with yourself rather than
    // with the world's material. Sites is your sites worked from here; Fluent is the same sites
    // seen as a member sees them.
    return listOfNotNull(
        nowPlaying,
        // ASK — one dropdown above Today: Search, Chat, and the Directory.
        //
        // Search reads what you already wrote; Chat asks the same corpus in prose. That is the whole
        // group, and its smallness is the point. Synthesize, Brainstorm, a writing prompt, an essay
        // outline, the map — Michael's ruling is that these are all Ask OUTPUTS, things a
        // conversation hands back, not places you travel to. A menu row for an output would be a
        // door onto a thing that only exists once you've asked for it. So they get no rows: the
        // retiring Synthesize surface leaves a capability behind in Chat rather than a shortcut in
        // the menu, and the group ends up smaller than the one it replaced.
        //
        // The Directory left this group to stand on its own above Settings (Michael's words), which
        // is the right shape for it: Search and Chat are questions, and the Directory is the
        // opposite move — not asking, but going and looking.
        //
        // Michael: "Leave a search field on top for a quick search of Ledgable." So the group opens
        // with a place to TYPE rather than with a row that promises somewhere to type. Press the
        // keyboard's search key and you land in the Search surface with the query already run; the
        // 🔍 Search row below is the same door with nothing typed yet. Nothing runs while you type —
        // see [ScreenFragment.FolderField] for why a live corpus query behind the keyboard is the
        // one thing a drawer on e-ink must not do.
        ScreenFragment.Folder("🔎", "Ask", listOf(
            // Named for WHERE IT GOES, not for what it does. Michael, 2026-08-04: "Hamburger menu
            // 'search' the ledger and find anything… what's the difference?" — a fair question,
            // because the field above and this row were both called searching and landed on two
            // different surfaces. The field runs the query against your written history; this row
            // opens the full search, across everything, with its filters.
            "🔍  Search everything" to openSearch,
            "💬  Chat" to { nav.navigate(R.id.action_to_ledger_chat) },
            // The one Ask output that goes OUTSIDE the ledger for material and brings it back as
            // a working page rather than an answer.
            "🔗  Research a topic…" to { showResearch(fragment) },
            // Pick Harvest: one link, broken into cards you can arrange. Beside Research because
            // both go OUTSIDE and come back with a page rather than an answer.
            "❝  Harvest a link…" to { showPickHarvest(fragment) },
            // The Map is a way of SEEING what connects — thinking, not a daily station. It sits
            // with Search and Chat because all three answer a question you came with.
            "🗺  Map" to { nav.navigate(R.id.action_to_ledger_map) },
        ), expanded = home == "Ask", action = { nav.navigate(R.id.action_to_ledger_chat) },
            field = ScreenFragment.FolderField("Quick find in your writing") { q ->
                com.toolsboox.plugin.calendar.ui.ReadingLogSelection.seedQuery = q.ifBlank { null }
                if (q.isBlank()) openSearch() else openHistory(null)
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
            "📧  Mail" to { openMail(null) },
            // Bluesky, in from Sites (Michael: "Bluesky either feeds or elsewhere in Incoming").
            //
            // A PLAIN ROW, not a lens. The Read / The Watch / The Listen are all one call —
            // openFeed("feed", kind) — because they are three filters over the ONE Miniflux store
            // this folder is built on. Bluesky is a different service on a different secret with its
            // own fragment and its own reply queue on the VPS; making it wear the lens grammar would
            // mean inventing a feed that doesn't exist, and the row would then be the only "lens"
            // that opened a different screen. It sits directly under Mail because those two are the
            // arrivals that are PEOPLE talking; the block below them is publications.
            "🦋  Bluesky" to { nav.navigate(R.id.action_to_bluesky) },
            "📰  All" to { openFeed("feed", null) },
            "📖  The Read" to { openFeed("feed", "read") },
            "📺  The Watch" to { openFeed("feed", "watch") },
            "🎧  The Listen" to { openFeed("feed", "listen") },
            // 🔖 Later List sits directly under 🎧 The Listen — Michael: "I'd love later list to
            // appear in feeds like a feed instead of this, instead under '🎧 The Listen' like
            // '🔖 Later List'." It's the end of the media block, above Starred and the rest,
            // because it's the one list here you KEEP rather than a lens over what arrived.
            "🔖  Later List" to { openFeed("later", null) },
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
        ), expanded = home == "Incoming",
            // The two rows with a family of destinations behind them fold for real — a caret on
            // the row, sub-rows beneath — instead of the old leading-space fake-indent, which cost
            // this folder up to six always-visible rows and read as clutter, not depth ("the
            // submenu got rid of the submenus"). Mail keeps navigating to the unified inbox —
            // The Mail lens, since the standalone screen retired — and its fold holds the
            // per-account doors (built only past one account, as before);
            // Later List keeps navigating to the whole list and its fold holds the four lanes.
            // Just these two: this hub is a launcher, and the Feeds page's own directory pane
            // already carries the deep tree.
            subFolds = mapOf(
                "📧  Mail" to mailAccountRows(fragment, ::openMail),
                "🔖  Later List" to com.toolsboox.plugin.feeds.nw.LaterFeed.LANES.map { (lane, label, _) ->
                    label to { openFeed("later", null, lane) }
                },
            )),
        // FILTER — what arrived, being sorted. Directly under Incoming, because that is the order it
        // happens in: the world comes in through Incoming, and this is the sieve.
        //
        // It was called Flow and held five rows. Michael's words were "no more flow": the name
        // promised a current carrying you from catching to making, and the measured record says the
        // current stops at the third station. All Stars is where a star lands (8 days of ink), Gram
        // Picks is the inbox every grabbed gram falls into, and Pickings is where a gram becomes
        // something — 33 days and 158 grams, his second-most-used surface in the whole ledger.
        //
        // Synthesize left with its surface; its idea is an Ask output now. Write left this group
        // because Filter names a job Write isn't doing — and it has since left the hub altogether:
        // it is the Lines template, reached through the Notes door.
        //
        // PICKINGS STAYS EXACTLY HERE, and now it is a Notes template as well. That is not a
        // contradiction, it is the point: ONE SURFACE REACHED TWO WAYS. The funnel takes you to
        // TODAY'S board, because that is what sorting what arrived means — All Stars, then the
        // inbox, then the board you put things on. The Notes door lets you MAKE A NAMED ONE,
        // because a board you keep is a note you arranged. Same PickingsStore, same keys, same
        // shelf; two questions, two doors, and the day's board is one tap from the sieve where it
        // has always been.
        // DAILY — the whole daily practice, in the order the ritual walks it.
        //
        // Filter used to hold the first three of these as a group of its own. Michael collapsed it
        // in: "yah, daily". He is right twice over. They ARE daily — All Stars and Gram Picks are
        // touched every day, not visited as a category — and once they leave, Filter is a folder
        // with one row in it, which is not a folder. So the funnel keeps its ORDER, which is where
        // the meaning actually lived (arrive → pick → arrange), and loses the box around it.
        //
        // This is also the Garden, renamed and cut to what earns its place. Gratitude is the single
        // most-written page in the ledger (44 days) and Self Executive is his own keep. Quick Wins
        // moves in from the Desk: it is a daily reckoning, not a working surface. Missed
        // Connections — "pretty good" — is the one discovery surface that survived.
        //
        // Roots, Seeds and Sprouts are gone. All three were DERIVED views over the tag index and the
        // connection graph, holding no data of their own, and Michael's reading of Roots and Seeds
        // was "pretty useless". Nothing they showed has been lost: it is all still in the graph, and
        // Missed Connections still reads it.
        ScreenFragment.Folder("🪴", "Daily", listOf(
            "★  All Stars" to { CalendarNavigator.toDayNote(fragment, today, "intake") },
            // Gram Picks between All Stars and Pickings — the iPhone's order. Where every grabbed
            // gram lands, so sorting happens once, later, in one place.
            "◈  Gram Picks" to {
                CalendarNavigator.toDayNote(
                    fragment, today,
                    com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
                )
            },
            "❝  Pickings" to { showPickingsPicker(fragment) },
            "🐘  Self Executive" to { CalendarNavigator.toDayNote(fragment, today, "selfexec") },
            "⚡  Quick Wins" to { nav.navigate(R.id.action_to_quick_wins) },
            // "Missed Connections" is a LABEL change only. The class, the file, the nav id and every
            // stored key still say "rhizome", deliberately: the surface is wired through the rhizome
            // graph, and renaming that vocabulary would be churn with a live wire in it. He reads
            // "Missed Connections"; the compiler reads MissedRhizomesFragment.
            "✧  Missed Connections" to { nav.navigate(R.id.action_to_missed_rhizomes) },
            // Map keeps the door it has always had, in the folder it has always been in. Its future
            // is as a rendering of an Ask output rather than a place you visit — that is a later
            // slice, and until then taking its only top-level door away would strand a working
            // viewer to make a menu tidier.
            // Gratitude last, on Michael's ordering: the day's material is caught, sorted and
            // worked through above, and this is what you write when that is done.
            "🙏  Gratitude" to { CalendarNavigator.toDayNote(fragment, today, "gratitude") }
        ), expanded = home == "Daily"),
        // DESK — people, and the work. Four rows now (Michael: "Roster/Booking goes in Desk").
        //
        // "Boards & Tasks" is ONE row because a board is not a thing beside a task, it is a LENS
        // over tasks — Michael's own reasoning, and the measurement behind it is stark: 263 tasks,
        // 260 of them belonging to no board at all and 239 sitting in stage "todo". A separate
        // Boards door was a second front on a drawer that is almost entirely one pile. So the kanban
        // is a DISPLAY MODE of Tasks now (list <-> by stage) rather than its own hub row, and this
        // row opens whichever mode you last used — the surface itself reads the remembered lens on
        // arrival (see [TASKS_MODE_PREFS]). Nothing of the kanban's rendering was deleted — it was
        // re-homed into the one fragment.
        //
        // ROSTER comes up out of the Contacts fold. It was tucked in there because the IA named no
        // home for it and a sub-fold kept the door alive at the nearest true place; it has a home
        // now, and a row you have to open a fold to reach is a row you forget you have.
        //
        // BOOKING is the other half of the same day and is NEW as a door. FluentBooking has been in
        // the app for a while with no top-level way in: a booking could only be reached by finding
        // the card it happens to sit on in Boards & Tasks, or the attendee it belongs to on the
        // Roster. Roster answers "who is coming today"; Booking answers "what is booked" — the same
        // material, the two questions you actually have, so they are two rows rather than one.
        // See [showBookingPicker] for why the door is a picker and not a new screen.
        //
        // Quick Wins moved to Daily; Publish and Posts moved to Sites, where the rest of the work on
        // his sites now lives.
        ScreenFragment.Folder("🗒", "Desk", listOf(
            "👤  Contacts" to { nav.navigate(R.id.action_to_rolodex) },
            "☑  Boards & Tasks" to { nav.navigate(R.id.action_to_ledger_items) },
            "🎟  Event Attendees" to { nav.navigate(R.id.action_to_roster) },
            "🕘  Booking" to { showBookingPicker(fragment) }
        ), expanded = home == "Desk"),
        // NOTES — ONE DOOR, FIVE TEMPLATES.
        //
        // Michael: "Notes have template options: Pickings, Jots, Lines, Grid, Text. Each can be
        // saved etc hence the directory." And on Write: "write was really just another surface that
        // was exactly just like lined notes."
        //
        // This folder used to be the five surfaces as five rows — Notes, Grid Notes, Jot Notes,
        // Write, Text Notes — which is the shape that sentence retires. A template is not a door,
        // it is a PROPERTY OF THE PAGE, so the door is Notes and the template is chosen at the
        // moment you make one. The stores, the indexes and every key underneath are untouched:
        // see [com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEMPLATES] for the whole mapping.
        //
        // ✍ WRITE RETIRES AS A ROW HERE, in the same change that makes Lines real — never before.
        // Its data is not retired and cannot be: "write" and every "write-<millis>" still resolve,
        // still list, still open from the Directory, from the day chip and from any
        // `ledger://<date>/write-<millis>` a gram or link already carries. Pinned in
        // RetiredSurfaceReachabilityTest so a later tidy-up cannot quietly break it.
        //
        // WHY THE FIRST ROW STILL ASKS NOTHING. Capture must stay fast: "✒ Notes" reopens the note
        // you were last on, one tap, no dialog, exactly as before. The chooser only ever appears
        // behind "＋ New note…", where you have already said you are making a new thing and the one
        // question left is which kind. The floating pen's quick-note menu (MainActivity) keeps its
        // own one-tap rows too, so nothing on the capture path grew a step.
        ScreenFragment.Folder("✒", "Notes", listOf(
            // THE FOLDERS LEAD. Michael, 2026-08-04: "The Folder Glyph/directory is meant to be
            // top of the Notes directory." The filing system is the first thing you should see
            // when you open Notes — going to the last page you wrote is what you do when you
            // already know where you were going, and that is the shorter journey, not the
            // primary one.
            "🗂  All notes…" to { showNotesTemplatePicker(fragment, today) },
            "✒  Notes" to { CalendarNavigator.toLastDayNote(fragment) },
            // Named for what it makes. The ellipsis went when the tap stopped asking — a label
            // that promises a question and then acts is the small lie that makes a hub feel
            // untrustworthy. The fold beside it still holds all five.
            newNoteLabel(fragment) to { showNewNotePicker(fragment, today) },
            // #hashtags harvested off note pages → jump to any page a tag appears on. No naming.
            "#  Tags" to { showTagIndex(fragment) },
            // Its sibling: tags are what you MARKED, links are what you CONNECTED.
            "🔗  Links" to { showLinkIndex(fragment) },
            // Notes & Tags — a period-filtered list ("it's like Feeds") of the days that hold note
            // content and/or #tags, the Almanac nav as its filter. Opens at this week around today.
            "🗓  Notes & Tags" to {
                com.toolsboox.plugin.calendar.ui.NotesTagsFragment.open(fragment, today, "week")
            }
        ), expanded = home == "Notes",
            // The five templates ride under the two doors that take one, as a real fold rather than
            // as five more top-level rows: the folder says Notes, and opening the fold says which
            // kinds of note there are. It is also what keeps the old one-tap habits alive — "today's
            // grid" is now Notes › All notes › Grid, and the fold makes that two taps rather than a
            // dialog. Built from LedgerDocuments.TEMPLATES so the order is his, in one place.
            subFolds = mapOf(
                newNoteLabel(fragment) to noteTemplateRows(fragment) { surface ->
                    // Picking from the fold also SETS the default, so the row you tap next time
                    // makes the kind you last chose — which is the only reading of "last template"
                    // that matches what you did.
                    setLastNoteTemplate(fragment.requireContext(), surface)
                    createNoteOfTemplate(fragment, surface, today)
                },
                "🗂  All notes…" to noteTemplateRows(fragment) { surface ->
                    openNoteTemplateDirectory(fragment, surface, today)
                }
            )),
        bookshelf,
        // SITES — YOUR sites, worked from here.
        //
        // This folder and the Fluent one below split what used to be "Community" and "Sites" along a
        // clearer line than native-versus-WebView: this is the OPERATOR's side (write a post, answer
        // a comment, move a card), Fluent is the same sites as a MEMBER sees them. Publish and Posts
        // came in from the Desk on that reasoning — publishing to a site is site work, not desk work.
        //
        // "Site Boards", not "Boards". Three different things in this app were once one bare word in
        // the menu — the local kanban, the Pickings boards, and FluentBoards on his sites — and a
        // menu where the same word means three things is a menu you have to remember rather than
        // read. The local kanban is a mode of Tasks now, Pickings is called Pickings, and this one
        // says whose boards it means.
        ScreenFragment.Folder("🌐", "Sites", listOf(
            // WordPress publishing on the active site — compose (post/schedule/draft, CPTs, grams as
            // the featured image) and browse/edit/trash posts.
            "🖋  Publish" to { nav.navigate(R.id.action_to_publish) },
            "🗎  Posts" to { nav.navigate(R.id.action_to_posts_browser) },
            "@  Correspondence" to { nav.navigate(R.id.action_to_correspondence) },
            "💬  Messages" to { nav.navigate(R.id.action_to_messages) },
            "🗃  Site Boards" to { nav.navigate(R.id.action_to_site_boards) },
            // Site accounts is configuration OF THESE SITES, so it sits with them rather than in the
            // gear where it was buried. Same manager the settings screen opens; one source of truth.
            "🖥  Site Settings" to { com.toolsboox.plugin.calendar.ui.SitesSettingsDialog.show(fragment.requireContext()) }
        ), expanded = home == "Sites"),
        // Bluesky LEFT this folder for Incoming (Michael: "Bluesky either feeds or elsewhere in
        // Incoming"). It was folded under Correspondence on the reasoning that both are the exchange
        // with people about what you made — true, but it put the one PUBLIC TIMELINE in the app
        // behind a fold in the operator's folder, two taps from the things it actually resembles.
        // A timeline is something that arrives; Incoming is where the things that arrive live.
        // FLUENT — the same sites as a member sees them: the four Fluent front ends in a
        // persistent-session WebView (sign in once, cookies stick).
        //
        // It was called "Sites" and that name now belongs to the operator's folder above, which is
        // the honest way round: these pages are not his sites, they are his sites' front doors, and
        // the plugin family is the thing they actually have in common.
        //
        // Michael's IA listed "Support" in BOTH this group and Sites. There is exactly one support
        // destination in the app — the FluentSupport portal — so it is here, with its family, and
        // not duplicated above under a second name. Flagged rather than guessed at.
        ScreenFragment.Folder("🚪", "Fluent", listOf(
            "🏛  Portal" to { openSiteWeb(nav, "community") },
            "🎓  Courses" to { openSiteWeb(nav, "courses") },
            "🛟  Support" to { openSiteWeb(nav, "support") },
            "🛍  Shop" to { openSiteWeb(nav, "shop") }
        ), expanded = home == "Fluent"),
        // Log — the zettelkasten: one screen with range/origin/search inside. Ask lived here
        // (asking IS querying the log) before it went to the top of the hub; the door up there
        // reaches the same chat surface.
        ScreenFragment.Folder("🕘", "Log", listOf(
            // "History", not "Log" — the child shares the folder's name and Search's destination,
            // and a distinct name + glyph is what tells you it's the browse-the-past door.
            "🕰  History" to { openHistory(null) }
        ), expanded = home == "Log"),
        // DIRECTORY — standing alone above Settings, exactly as Michael put it.
        //
        // No submenu and no folder: it is one door onto one place, the way Today is. It used to be
        // the third row of the Search/Ask dropdown, which read as a third KIND of question; down
        // here it reads as what it is — the opposite of asking. You go and look.
        ScreenFragment.Folder("🗂", "Directory", action = { showLedgerRootDirectory(fragment) }),
        ScreenFragment.Folder("⚙", "Settings", listOf(
            // "All settings", not "⚙ Settings" — the folder is already called Settings and wears
            // the gear; a child repeating both read as the same door twice.
            "⚙  All settings" to { nav.navigate(R.id.action_to_settings) },
            // Site accounts left this folder for Sites, as "Site Settings" — it is configuration OF
            // those sites, and it now sits with them instead of two folders away from everything it
            // configures. Same dialog, one door.
            "🔤  OCR model" to { com.toolsboox.ui.plugin.OcrModel.showPicker(fragment.requireContext()) },
            // The mirror: notes as real Markdown in a folder you declare. Under Settings rather
            // than Notes because it is a property of where the ledger LIVES, not a thing you do
            // to a note — the same shelf of decisions as cloud sync, which it sits beside.
            "🪞  Notes → Markdown folder" to { showNotesMirrorSettings(fragment) },
            "☁  Cloud sync" to { nav.navigate(R.id.action_to_cloud) }
        ), expanded = home == "Settings")
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  NOTES — one door, five templates
// ─────────────────────────────────────────────────────────────────────────────
//
// Michael: "Notes have template options: Pickings, Jots, Lines, Grid, Text. Each can be saved etc
// hence the directory."
//
// Everything below is PRESENTATION over storage that already exists. A template is a surface id,
// which is a page-key family, which is a store — the table is in
// [com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEMPLATES] and nothing here mints a key, moves
// a key or writes a byte the old five doors didn't already write. What is new is that the five are
// enumerated from ONE list in ONE order, so the chooser, the directory door and the Directory's own
// kind ordering cannot drift into three orders.

/**
 * The five templates as menu rows, in Michael's order, each carrying its own glyph and name.
 *
 * Built from [LedgerDocuments.TEMPLATES] rather than written out, because the moment they are
 * written out twice the second copy is where a sixth template fails to appear. [onPick] is what the
 * caller means by choosing one — making a note, or opening that template's shelf.
 */
private fun noteTemplateRows(
    fragment: ScreenFragment,
    onPick: (String) -> Unit,
): List<Pair<String, () -> Unit>> {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    return docs.TEMPLATES.map { surface ->
        (docs.glyph(surface) + "  " + docs.label(surface)) to { onPick(surface) }
    }
}

/**
 * Make a new note on [surface] and go and stand on it.
 *
 * NO NAME IS ASKED FOR, and that is the capture-stays-fast rule doing its job. Every store already
 * mints a usable title ("Jot 3", "Pickings 2", the date for a daily page) and the page you land on
 * carries "✎ Name this note…" in its own ‹ N › menu, so the name can be given once there is
 * something to name it after. Typing a title into a dialog before a blank page is the friction the
 * whole Notes door exists to remove — and [promptNewDocument] is still there, on the per-template
 * directory, for the times you are deliberately starting a piece and know what it is.
 *
 * THE MINTING IS NOT DONE HERE. It goes through [LedgerDocuments.startNote], the ot-layer seam that
 * knows all five stores — because Ask is about to make notes too ("text output in text notes …
 * those can bring in grams and outside links, so would go on a grid"), and a picker that owned the
 * only way to mint one would have to be driven headless by whatever wanted a note. This function is
 * the picker's half: ask for the note, then go and stand on it.
 */
private fun createNoteOfTemplate(fragment: ScreenFragment, surface: String, date: LocalDate) {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val made = docs.startNote(fragment.requireContext(), surface, "", date)
    if (made == null) {
        fragment.showMessage("Couldn't start a new ${docs.label(surface)} note.")
        return
    }
    openNewNote(fragment, made)
}

/**
 * Go and stand on a note that was just made — the ONE place the Text branch is taken.
 *
 * [LedgerDocuments.NewNote.isTextNote] says which kind of address the key is; everything that mints
 * a note routes its navigation through here rather than re-deciding, so a caller that forgets can
 * only forget in one place. Public because the Ask slice will mint notes from outside this file and
 * will want to open the one it just wrote.
 */
fun openNewNote(fragment: ScreenFragment, note: com.toolsboox.plugin.calendar.ot.LedgerDocuments.NewNote) {
    if (note.isTextNote)
        com.toolsboox.plugin.textnotes.ui.TextNotesFragment.open(fragment, note.date, note.key)
    else CalendarNavigator.toDayNote(fragment, note.date, note.key)
}

/** One template's shelf — every note ever made on it. Text again goes to its own fragment, which
 *  IS its directory; the other four share [showDocumentDirectory]. */
private fun openNoteTemplateDirectory(fragment: ScreenFragment, surface: String, date: LocalDate) {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    if (surface == docs.TEXT_NOTES)
        com.toolsboox.plugin.textnotes.ui.TextNotesFragment.open(fragment, date, null)
    else showDocumentDirectory(fragment, surface, date)
}

/**
 * "＋ New note…" — which template?
 *
 * The row has a fold carrying the same five, so this dialog is the answer for the tap rather than
 * the caret. It exists because a fold is a thing you have to notice: the row must do something
 * honest when pressed, and "ask which kind" is the only honest thing a row called "New note…" can
 * do. The template you used last leads the list and is marked, so the commonest answer is the first
 * one under your thumb — but it is never taken silently, because which template a note is cannot be
 * changed afterwards once there is ink on it (see the page's own Template row), and a decision that
 * permanent should not be made by a default you didn't see.
 */
/**
 * "＋ New note…" — TAKES YOUR LAST TEMPLATE. It does not ask.
 *
 * Michael left this one open ("whether ＋ New note… should ask or take last template") and the row
 * itself settles it: it already carries a FOLD holding the same five templates, so the choice is
 * one gesture away whether or not the tap asks. A dialog on tap is therefore a second way to answer
 * a question that is already answerable — the "eliminate decisions" rule, applied to a decision the
 * interface was asking twice.
 *
 * So tap makes the note you most recently made. Open the fold when you want a different kind.
 * First ever tap has no last template and falls back to the chooser, which is the one moment the
 * question is genuinely unanswered.
 */
fun showNewNotePicker(fragment: ScreenFragment, date: LocalDate = LocalDate.now()) {
    val last = lastNoteTemplate(fragment.requireContext())
        ?.takeIf { com.toolsboox.plugin.calendar.ot.LedgerDocuments.isTemplate(it) }
    if (last != null) {
        createNoteOfTemplate(fragment, last, date)
        return
    }
    showTemplateChooser(fragment, "New note") { surface ->
        setLastNoteTemplate(fragment.requireContext(), surface)
        createNoteOfTemplate(fragment, surface, date)
    }
}

/** "🗂 All notes…" — which template's shelf? Same five, same order, no last-used mark: you are
 *  looking for something, and where you last MADE a note says nothing about where it is. */
fun showNotesTemplatePicker(fragment: ScreenFragment, date: LocalDate = LocalDate.now()) =
    showTemplateChooser(fragment, "All notes", markLastUsed = false) { surface ->
        openNoteTemplateDirectory(fragment, surface, date)
    }

/** The shared five-row chooser. One renderer so the two doors can never offer different fives. */
private fun showTemplateChooser(
    fragment: ScreenFragment,
    title: String,
    markLastUsed: Boolean = true,
    onPick: (String) -> Unit,
) {
    val ctx = fragment.requireContext()
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val last = if (markLastUsed) lastNoteTemplate(ctx) else null
    // His order, with the last-used lifted to the front when there is one — the order is still his
    // for everything below it, so the list never reshuffles into an order he has to re-read.
    val ordered = if (last != null && last in docs.TEMPLATES)
        listOf(last) + docs.TEMPLATES.filterNot { it == last } else docs.TEMPLATES
    val labels = ordered.map { surface ->
        docs.glyph(surface) + "  " + docs.label(surface) +
            if (surface == last) "  ·  last" else ""
    }
    val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle(title)
        .setItems(labels.toTypedArray()) { _, which -> onPick(ordered[which]) }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    fragment.showModal(dialog)
    dialog.window?.decorView?.let { root -> root.post { com.toolsboox.ot.LedgerFonts.applyTree(root) } }
}

private const val NOTE_TEMPLATE_PREFS = "ledger_notes"
private const val NOTE_TEMPLATE_KEY = "last_template"

/** The template you last STARTED a note on — a hint for the chooser, never a decision it makes.
 *  Shares the `ledger_notes` prefs file with last_note_date/last_note_page, which is the same
 *  question about the same door. */
/** "＋ New Jots note", or plain "＋ New note…" until there is a last one to name. */
private fun newNoteLabel(fragment: ScreenFragment): String {
    val last = lastNoteTemplate(fragment.requireContext())
        ?.takeIf { com.toolsboox.plugin.calendar.ot.LedgerDocuments.isTemplate(it) } ?: return "＋  New note…"
    return "＋  New ${com.toolsboox.plugin.calendar.ot.LedgerDocuments.label(last)} note"
}

fun lastNoteTemplate(context: android.content.Context): String? =
    context.getSharedPreferences(NOTE_TEMPLATE_PREFS, 0).getString(NOTE_TEMPLATE_KEY, null)

fun setLastNoteTemplate(context: android.content.Context, surface: String) {
    context.getSharedPreferences(NOTE_TEMPLATE_PREFS, 0).edit()
        .putString(NOTE_TEMPLATE_KEY, surface).apply()
}

// ─────────────────────────────────────────────────────────────────────────────
//  Booking — the Desk's other half of the day
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "🕘 Booking" — the upcoming bookings, one tap from the sheet that acts on them.
 *
 * A PICKER, NOT A SCREEN, and deliberately so. [BookingSheet]'s own note says why the app refuses
 * to become a booking admin console: the four things you would do to a booking with a pen in your
 * hand are keep it, cancel it, move it and write on it, and all four already live in that sheet.
 * What was missing was never a screen — it was a way to REACH a booking without first finding the
 * card it sits on. So this is the list and nothing else: the fetch the kanban already makes, the
 * sheet that already exists, and no third rendering of a booking to keep in step with the other two.
 *
 * Network on a background thread, failures land as a line rather than a crash, and no creds means an
 * honest empty — the same read-only-safe contract the Roster keeps.
 */
fun showBookingPicker(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    fragment.showMessage("Loading bookings…")
    Thread {
        val bookings = runCatching {
            if (!com.toolsboox.plugin.calendar.nw.LedgerWebBridge.config(ctx).ready) emptyList()
            else com.toolsboox.plugin.calendar.nw.LedgerBooking.bookings(ctx, limit = 60)
        }.getOrDefault(emptyList())
        runCatching {
            fragment.requireActivity().runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                if (bookings.isEmpty()) {
                    fragment.showMessage("No upcoming bookings.")
                    return@runOnUiThread
                }
                showDirectoryList(
                    fragment,
                    title = "Booking",
                    // Same threshold as everywhere else: below a handful, a filter field asks you
                    // to type what you can already see.
                    searchHint = if (bookings.size > 6) "Find a booking" else null,
                    empty = "No upcoming bookings.",
                ) { query, _ ->
                    val q = query.lowercase()
                    val rows = mutableListOf<DirRow>()
                    val shown = bookings.filter {
                        q.isEmpty() || it.title.lowercase().contains(q) ||
                            it.person.lowercase().contains(q) ||
                            (it.startTime ?: "").contains(q)
                    }
                    if (shown.isEmpty())
                        rows += DirRow("", "Nothing matches “$query”.", null, 1, closes = false) {}
                    for (b in shown) {
                        rows += DirRow("🕘", b.title, bookingDetail(b)) {
                            com.toolsboox.plugin.calendar.ui.BookingSheet.open(fragment, b.id)
                        }
                    }
                    rows += DirRow("🎟", "Today's roster…") {
                        NavHostFragment.findNavController(fragment).navigate(R.id.action_to_roster)
                    }
                    rows
                }
            }
        }
    }.apply { isDaemon = true }.start()
}

/** "Tue 5 Aug  14:00  ·  Jane  ·  ● soon" — when, who, and whether it is imminent. The same three
 *  facts the kanban's booking card shows, formatted the same way, so a booking reads identically
 *  wherever you meet it. */
private fun bookingDetail(b: com.toolsboox.plugin.calendar.nw.SiteBooking): String {
    val at = runCatching {
        java.time.LocalDateTime
            .parse((b.startTime ?: "").trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atOffset(java.time.ZoneOffset.UTC)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
    }.getOrNull()
    return listOfNotNull(
        at?.let { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM  HH:mm").format(it) },
        b.person.ifBlank { null },
        when (b.ongoing) {
            "happening_now" -> "● now"
            "starting_soon" -> "● soon"
            else -> null
        },
        if (b.status == "cancelled" || b.status == "rejected") "cancelled" else null,
    ).joinToString("   ·   ")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Boards & Tasks — ONE door, two ways of looking
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Which lens "Boards & Tasks" opens on: the list, or the by-stage board, whichever you were last
 * looking at.
 *
 * Michael's reasoning is the whole design: *a note is a place, a board is a lens*. A board is not a
 * sibling of the task list, it is the task list seen through stage — so it cannot honestly be a
 * second door, and the hub no longer offers one. What used to be two rows ("Tasks & Events" and
 * "Boards · Local") is one row whose destination is a REMEMBERED VIEW.
 *
 * The two renderings used to live in two fragments, and a router here sent the hub's one row to
 * whichever you'd used last — a seam, its comment said, that whoever finally merged them would
 * delete. Merged: both lenses are [com.toolsboox.plugin.calendar.ui.LedgerItemsFragment] now, the
 * router is gone, and the pref that used to pick a FRAGMENT names a RENDER MODE the surface reads
 * on arrival and writes on every ▤/☰ flip. Same key, same values, so the choice a device
 * remembered from before the merge still lands where it always did.
 */
const val TASKS_MODE_PREFS = "ledger_tasks_view"
const val TASKS_MODE_KEY = "mode"
const val TASKS_MODE_STAGE = "stage"

fun tasksModeIsStage(context: android.content.Context): Boolean =
    context.getSharedPreferences(TASKS_MODE_PREFS, 0).getString(TASKS_MODE_KEY, null) == TASKS_MODE_STAGE

fun setTasksModeStage(context: android.content.Context, stage: Boolean) {
    context.getSharedPreferences(TASKS_MODE_PREFS, 0).edit()
        .putString(TASKS_MODE_KEY, if (stage) TASKS_MODE_STAGE else "list").apply()
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

/**
 * Gram Picks is the THIRD kind that lives here and only here. [LedgerDocuments] deliberately
 * declines it a surface (one implicit page run per day — a chip over it would promise siblings
 * that cannot exist, see `surfaceOf`), so like Notes it has no store to list documents from. What
 * it does have is cards, and the card index already knows which days hold them: the directory
 * lists THOSE days, off [com.toolsboox.plugin.calendar.ot.PickingsCards.counts] — one small
 * memoized file, no day decoded — each row opening that day's inbox.
 */
private const val KIND_GRAMPICKS = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS

/**
 * Michael's order: the inbox first, then THE FIVE TEMPLATES AS ONE BLOCK in his order, then the
 * plain day pages, the retired surface, and the two indexes over the whole thing.
 *
 * The five come straight off [LedgerDocuments.TEMPLATES] rather than being spelled out again. That
 * list is the one place their order is decided (Pickings · Jots · Lines · Grid · Text), and the
 * whole point of the Notes door is that the chooser and the Directory agree about what the five are
 * and what order they come in — two hand-written copies is exactly how they would stop agreeing.
 *
 * Synthesize has moved BELOW the days. It used to sit third, among the making surfaces, which was
 * right while it was one; it is a retired surface now, listed so its pages still open, and a
 * retired kind above the live ones would be the directory disagreeing with the hub about what the
 * app is for.
 */
private val DIRECTORY_KINDS = listOf(
    // Gram Picks leads, before Pickings, because that is its place in the flow the order mirrors
    // (All Stars → Gram Picks → Pickings): the inbox first, then what it is sorted into.
    KIND_GRAMPICKS,
) + com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEMPLATES + listOf(
    KIND_NOTES,
    com.toolsboox.plugin.calendar.ot.LedgerDocuments.SYNTHESIZE,
    KIND_TAGS,
)

/**
 * A kind's glyph. The four document surfaces take theirs from [LedgerDocuments] rather than
 * carrying a second copy here — that object's glyph/label/noun trio is the one vocabulary the hub,
 * the day chip and the per-kind directories all speak, and a root directory that renamed Text Notes
 * from Ⓣ to 📝 would have you looking for a door you'd never seen. Notes and Tags borrow the hub's
 * own row glyphs for the same reason.
 */
private fun kindGlyph(kind: String): String = when (kind) {
    KIND_NOTES -> "✒"
    KIND_TAGS -> "#"
    KIND_GRAMPICKS -> "◈"
    else -> com.toolsboox.plugin.calendar.ot.LedgerDocuments.glyph(kind)
}

private fun kindLabel(kind: String): String = when (kind) {
    KIND_NOTES -> "Notes"
    KIND_TAGS -> "Tags"
    KIND_GRAMPICKS -> "Gram Picks"
    else -> com.toolsboox.plugin.calendar.ot.LedgerDocuments.label(kind)
}

/**
 * What a kind's count is counting. "412" alone is a number; "412 days" is an answer.
 *
 * THE FIVE TEMPLATES ALL COUNT IN NOTES, because that is what they are: the header already says
 * which template ("Jots · 14 notes"), so a second word for the same thing on the same row would be
 * the row saying "Jots · 14 jots". The old per-surface count nouns — boards, writings, grids, jots
 * — were right while they were five separate kinds of thing; under one Notes door they are one kind
 * of thing made five ways, and this follows
 * [com.toolsboox.plugin.calendar.ot.LedgerDocuments.noun] rather than keeping a private list to
 * drift from it. Pickings loses "boards" here, which is the one real casualty and worth naming: a
 * board IS a note you arrange on, and calling it a note in the count is the model, not a loss.
 *
 * The three that are NOT templates keep their own nouns, because none of them counts documents:
 * Notes and Gram Picks count DAYS (their rows ARE days — [directoryItems] lists dayDates — and the
 * by-date spine reuses this same label for its year/month counts, where only "days" is true), and
 * Tags counts tags.
 */
private fun kindCountLabel(kind: String, n: Int): String {
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    val noun = when (kind) {
        KIND_NOTES -> if (n == 1) "day" else "days"
        KIND_TAGS -> if (n == 1) "tag" else "tags"
        // Gram Picks rows ARE days, like Notes' — the inbox is one page run per day.
        KIND_GRAMPICKS -> if (n == 1) "day" else "days"
        docs.SYNTHESIZE -> if (n == 1) "synthesis" else "syntheses"
        docs.PICKINGS, docs.JOT, docs.WRITE, docs.GRID, docs.TEXT_NOTES ->
            if (n == 1) "note" else "notes"
        else -> if (n == 1) "document" else "documents"
    }
    return "$n $noun"
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
 * The directory's own two settings, as the ids the backup file carries — `sort` and `spine`.
 *
 * These exist so [com.toolsboox.plugin.calendar.ot.NamesBackup] can carry "how this drawer is
 * arranged" without a second copy of the preference name or the two enums' id strings living over
 * in the calendar package. They are the whole of the directory's persistent state that is worth
 * moving between devices: the open-folder set is where you happened to leave a drawer three minutes
 * ago, not a shape of the ledger, and restoring one device's half-open drawers onto another would
 * be noise dressed as a restore.
 */
fun ledgerDirectoryState(context: android.content.Context): Pair<String, String> =
    directorySort(context).id to directorySpine(context).id

/**
 * Apply a backed-up arrangement. Blank or UNRECOGNISED ids are left alone rather than coerced:
 * [DirectorySort.of] and [DirectorySpine.of] both fall back to a default for anything they don't
 * know, so passing an iPad-only spine straight through would silently reset Michael's spine to
 * "By kind" and call it a restore. Ignoring it leaves the local setting standing, which is the
 * failure this can afford — a preference is the one thing in a names backup that costs nothing to
 * set again by hand.
 */
fun applyLedgerDirectoryState(context: android.content.Context, sort: String?, spine: String?) {
    if (!sort.isNullOrBlank() && DirectorySort.entries.any { it.id == sort })
        setDirectorySort(context, DirectorySort.of(sort))
    if (!spine.isNullOrBlank() && DirectorySpine.entries.any { it.id == spine })
        setDirectorySpine(context, DirectorySpine.of(spine))
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
        // Grid and Jot are here rather than with the per-day stores below because their index is
        // global too: a grid you named is a thing you return to, filed by the day it was started on.
        docs.WRITE, docs.SYNTHESIZE, docs.GRID, docs.JOT ->
            docs.forSurface(context, kind, today).map { DirItem(kind, it.key, it.title, it.date) }

        // Per-day stores: the days the store has files for, plus today (which always has a page in
        // principle even before anything is written to it).
        docs.PICKINGS, docs.TEXT_NOTES ->
            (docs.dates(context, kind) + today).distinct().sortedDescending().flatMap { d ->
                docs.forSurface(context, kind, d).map { DirItem(kind, it.key, it.title, d) }
            }

        // A day, titled by its date, opening on note page "0" — the page the Notes door lands on.
        KIND_NOTES -> docs.dayDates(context).map { DirItem(KIND_NOTES, "0", it.toString(), it) }

        // The days that hold picked grams, read off the card tally — one small memoized file,
        // keyed "date|basePage", so no day file is opened to build this list. Today is always
        // offered: the inbox exists in principle before anything lands on it.
        KIND_GRAMPICKS -> (
            com.toolsboox.plugin.calendar.ot.PickingsCards.counts(context).keys
                .filter { it.substringAfter('|', "") == KIND_GRAMPICKS }
                .mapNotNull { k -> runCatching { LocalDate.parse(k.substringBefore('|')) }.getOrNull() }
                + today
            ).distinct().sortedDescending()
            .map { DirItem(KIND_GRAMPICKS, KIND_GRAMPICKS, it.toString(), it) }

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
    // Grid and Jot supply their day's implicit pad here for the same reason Write does: the by-kind
    // view can only afford to list what the STORES know, so a grid page written on and never named
    // exists in this tree only at its own day — which is where you would look for it.
    for (surface in listOf(
        docs.PICKINGS, docs.SYNTHESIZE, docs.WRITE, docs.GRID, docs.JOT, docs.TEXT_NOTES
    )) {
        docs.forSurface(context, surface, date)
            .filter { it.date == date }
            .forEach { out.add(DirItem(surface, it.key, it.title, date)) }
    }
    // The day's inbox, offered only when the card tally says it holds something — unlike the Notes
    // row (whose blank page is that surface's normal answer), an empty Gram Picks row on every day
    // of history would be four hundred doors to the same nothing. The tally is the memoized
    // one-file read the badges already paid for.
    if ((com.toolsboox.plugin.calendar.ot.PickingsCards.counts(context)["$date|$KIND_GRAMPICKS"] ?: 0) > 0) {
        out.add(DirItem(KIND_GRAMPICKS, KIND_GRAMPICKS, "Gram Picks", date))
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

        // Both are day rows opening a day page — Gram Picks' key IS its page key ("grampicks"),
        // so the one navigation serves the two kinds.
        KIND_NOTES, KIND_GRAMPICKS -> CalendarNavigator.toDayNote(fragment, item.date, item.key)

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
        // The kind's own door is the page itself — today's inbox; the day rows below reach the past.
        KIND_GRAMPICKS -> DirRow("↳", "Open Gram Picks…", null, 1) {
            CalendarNavigator.toDayNote(fragment, LocalDate.now(), KIND_GRAMPICKS)
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
            // Title AND date-iso: "2026-07" finds a month's work by name. iOS also matches blurbs;
            // Android's index carries none — same query, widest scope each side owns.
            val hits = sortItems(
                DIRECTORY_KINDS.flatMap { items(it) }.filter {
                    it.title.lowercase().contains(q) || it.date.toString().contains(q)
                },
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

/**
 * Declare (or stop) the Markdown mirror.
 *
 * The dialog says the RULE, not the mechanism, because the rule is the thing that will save him
 * an hour of confusion: text comes back, ink does not. Everything else about this feature is
 * discoverable by looking in the folder.
 */
fun showNotesMirrorSettings(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val mirror = com.toolsboox.plugin.calendar.ot.NotesMirror
    val here = mirror.declaredName(ctx)
    val rows = mutableListOf<Pair<String, () -> Unit>>()
    rows += ("🗂  " + (here?.let { "Folder · $it" } ?: "Choose a folder…")) to {
        fragment.pickTree(mirror.pickIntent()) { uri ->
            mirror.declare(ctx, uri)
            fragment.showMessage("Mirroring to ${mirror.declaredName(ctx) ?: "the folder"}.")
        }
    }
    if (here != null) rows += ("✕  Stop mirroring" to {
        mirror.stop(ctx)
        fragment.showMessage("Mirror off. The files already written stay where they are.")
    })
    fragment.showIconMenuWithNote(
        "Notes → Markdown",
        "Typed notes become .md files here and read back when you edit them elsewhere. " +
            "Handwritten pages are copied out as a picture plus their recognised text — " +
            "those are one-way.",
        rows
    )
}

/**
 * Pick Harvest — a link in, a Pickings board out.
 *
 * Michael: "the ai grab three notes and up to three quotes and two images filling up the picking
 * template from user given link." The board is the point: a summary is read once, but cards can be
 * arranged, connected, quoted from and replied to like anything he gathered by hand.
 */
fun showPickHarvest(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val dp = fragment.resources.displayMetrics.density
    val urlIn = android.widget.EditText(ctx).apply {
        hint = "Paste a link to harvest"; setSingleLine()
    }
    val titleIn = android.widget.EditText(ctx).apply {
        hint = "Name the board (optional)"; setSingleLine()
    }
    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
        addView(android.widget.TextView(ctx).apply {
            text = "Three notes and up to three quotes — verbatim — as cards on a Pickings board."
            textSize = 13f; setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        addView(urlIn); addView(titleIn)
    }
    fragment.showGuardedModal(
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Harvest a link")
            .setView(box)
            .setPositiveButton("Harvest") { _, _ ->
                val url = urlIn.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                val title = titleIn.text.toString().trim()
                fragment.showMessage("Harvesting\u2026", fragment.requireView())
                Thread {
                    val note = runCatching {
                        com.toolsboox.plugin.calendar.ot.PickHarvest.run(
                            ctx,
                            com.toolsboox.plugin.calendar.fi.CalendarDayService(),
                            com.toolsboox.ot.LedgerPaths.documentsRoot(ctx),
                            if (url.startsWith("http")) url else "https://$url",
                            title,
                        )
                    }.getOrNull()
                    runCatching {
                        fragment.requireActivity().runOnUiThread {
                            fragment.showMessage(
                                if (note == null) "Nothing worth keeping came back."
                                else "Pickings \u00B7 ${note.name}",
                                fragment.requireView(),
                            )
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    )
}

/**
 * Ask the research assistant for a topic, and land what comes back as a Jots page.
 *
 * The waiting is the whole design problem here: this is a network round-trip on a device that
 * repaints in full, so there is no spinner worth drawing. Instead the dialog closes immediately,
 * a line says it has gone, and the finished note announces itself — you are free to go do
 * something else in the ledger while it works, which is the honest shape of a slow thing.
 */
fun showResearch(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val dp = fragment.resources.displayMetrics.density
    val input = android.widget.EditText(ctx).apply {
        hint = "A topic, or a question you want the material for."
        setLines(3)
        gravity = android.view.Gravity.TOP
    }
    val box = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
        addView(android.widget.TextView(ctx).apply {
            text = "Curated sources, clustered and cited, as a jots page of connected grams."
            textSize = 13f; setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        addView(input)
    }
    // Guarded: a typed topic is work — a stray touch outside must not throw it away.
    fragment.showGuardedModal(
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Research a topic")
            .setView(box)
            .setPositiveButton("Go and find it") { _, _ ->
                val topic = input.text.toString().trim()
                if (topic.isBlank()) return@setPositiveButton
                fragment.showMessage("Researching \u201C$topic\u201D\u2026", fragment.requireView())
                Thread {
                    val note = runCatching {
                        com.toolsboox.plugin.calendar.ot.ResearchAssistant.run(
                            ctx,
                            com.toolsboox.plugin.calendar.fi.CalendarDayService(),
                            com.toolsboox.ot.LedgerPaths.documentsRoot(ctx),
                            topic,
                        )
                    }.getOrNull()
                    runCatching {
                        fragment.requireActivity().runOnUiThread {
                            fragment.showMessage(
                                if (note == null) "Nothing solid came back \u2014 no page made."
                                else "Jots \u00B7 ${note.name}",
                                fragment.requireView(),
                            )
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    )
}

/**
 * THE LINK INDEX — every `[[link]]` you have written, and what points at what.
 *
 * The twin of the tag index, and the difference between them is the whole reason both exist. A tag
 * is a LABEL: many pages wear it, it names no page, and its index answers "what did I mark this
 * way". A link has a TARGET, so its index answers a different and better question — "what have I
 * connected, and what points AT this?"
 *
 * Backlinks are the payoff. You handwrite [[Collider]] on four pages across three weeks; this is
 * where those four pages show up together, without you having filed anything.
 *
 * A target that nothing in the ledger is named after is shown as OWED (◇) rather than broken (⚠).
 * In a handwritten ledger, linking forward to something unwritten is an ordinary act — it is a note
 * to yourself that the thing should exist — and an index that scolded you for it would be
 * misreading the gesture. Tapping one offers to write it.
 */
fun showLinkIndex(fragment: ScreenFragment) {
    val ctx = fragment.requireContext()
    val links = com.toolsboox.plugin.calendar.ot.LedgerLinks
    val targets = links.all(ctx)
    if (targets.isEmpty()) {
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Links")
            .setMessage(
                "No [[links]] yet.\n\nWrite [[the name of a note]] on a page — by hand is fine, " +
                    "it's read off the same pass that finds #tags — and it becomes a link here " +
                    "and an edge on the Map."
            )
            .setPositiveButton("Close", null)
            .show()
        return
    }
    // The names anything is actually called, so a target can be told from an owed one. Read once,
    // outside the redraw: it is a directory scan, and doing it per keystroke would put a disk walk
    // behind the keyboard.
    val known = com.toolsboox.plugin.calendar.ot.LedgerDocuments.TEMPLATES.flatMap { t ->
        runCatching {
            com.toolsboox.plugin.calendar.ot.LedgerDocuments
                .forSurface(ctx, t, LocalDate.now()).map { it.title }
        }.getOrDefault(emptyList())
    }

    showDirectoryList(
        fragment,
        title = "Links",
        searchHint = "Find a link",
        empty = "No links yet.",
    ) { query, redraw ->
        val rows = mutableListOf<DirRow>()
        val q = query.trim().lowercase()
        val shown = targets.filter { q.isEmpty() || it.lowercase().contains(q) }
        if (shown.isEmpty()) rows += DirRow("", "Nothing matches “$query”.", null, 1, closes = false) {}
        for (target in shown) {
            val from = links.backlinks(ctx, target)
            val lands = links.resolves(target, known)
            rows += DirRow(
                if (lands) "🔗" else "◇",
                target,
                // The count is the useful fact about a link — one mention is a thought, six is a
                // theme — and "owed" says the other thing worth knowing in one word.
                (if (lands) "" else "owed · ") + "${from.size} page" + (if (from.size == 1) "" else "s"),
                closes = false,
            ) {
                // An owed link is a note you told yourself to write. Tapping it offers to write
                // it — the gesture completed rather than merely reported. A link that already
                // lands has nothing to offer here; its backlinks are the point, and they are
                // already listed underneath.
                if (!lands) offerToWrite(fragment, target)
            }
            // The pages that point here, indented under it. This IS the backlink list; a separate
            // screen for it would be a second tap to see the only thing the row is about.
            for (occ in from.take(12)) {
                rows += DirRow("↳", occ.pageKey, occ.date.toString(), 1) {
                    com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(
                        fragment, occ.date, occ.pageKey)
                }
            }
            if (from.size > 12) {
                rows += DirRow("", "…and ${from.size - 12} more", null, 1, closes = false) {}
            }
        }
        rows
    }
}

/**
 * Offer to write the note an owed `[[link]]` is asking for.
 *
 * Picks the template, then mints the note ALREADY NAMED for the link — which is what makes the
 * link land: resolution is by name, so naming it is the whole act. Landing on the new page rather
 * than returning to the index, because you tapped this to write something.
 */
private fun offerToWrite(fragment: ScreenFragment, target: String) {
    val ctx = fragment.requireContext()
    val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
    // The same picker "＋ New note…" uses, and the same landing — one way to start a note, so a
    // note begun from a link is indistinguishable from any other.
    showTemplateChooser(fragment, "Write “$target”?") { t ->
        val note = docs.startNote(ctx, t, target)
        if (note == null) fragment.showMessage("Couldn't start a new ${docs.label(t)} note.")
        else openNewNote(fragment, note)
    }
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

/** One tag's pages — tap to jump straight to that day's page, landing on the tag's mark (the
 *  capture zone it was written in) when the occurrence recorded one. A ✎ marks the ones that will
 *  zoom to the mark; legacy occurrences with no rect just open the page.
 *
 *  On the shared [showDirectoryList] chassis, like every other hop in the drawer — this was the
 *  ONE directory still shipping as a bare setItems alert, so the last step of tag navigation
 *  dropped the idiom (no filter, no sort, no way back) exactly where a hot tag has the most rows. */
private fun showTagPages(fragment: ScreenFragment, tag: com.toolsboox.plugin.calendar.ot.LedgerTags.TagInfo) {
    val ctx = fragment.requireContext()
    val occ = tag.occurrences
    showDirectoryList(
        fragment,
        title = "#${tag.tag}",
        // Same threshold as the document directory: on a tag written six times the field would be
        // a box asking you to type what you can already see.
        searchHint = if (occ.size > 6) "Find a page" else null,
        empty = "No pages yet.",
    ) { query, redraw ->
        val sort = directorySort(ctx)
        val rows = mutableListOf<DirRow>()
        rows += DirRow("⇅", "Sort · ${sort.label}", "date → name → kind", closes = false) {
            setDirectorySort(ctx, sort.next()); redraw()
        }
        val q = query.lowercase()
        // Match date-iso and page key both — the two things a row says are the two things you'd
        // type to find it.
        val shown = occ.filter {
            q.isEmpty() || it.first.toString().contains(q) || it.second.lowercase().contains(q)
        }
        // One tag's pages are one kind, so "by kind" has nothing to group — it keeps newest-first,
        // the order this list has always opened with.
        val ordered = when (sort) {
            DirectorySort.NAME -> shown.sortedWith(
                compareBy<Triple<LocalDate, String, android.graphics.RectF?>> { it.second.lowercase() }
                    .thenByDescending { it.first })
            else -> shown.sortedByDescending { it.first }
        }
        if (ordered.isEmpty()) rows += DirRow("", "Nothing matches “$query”.", null, 1, closes = false) {}
        for ((date, page, rect) in ordered) {
            rows += DirRow(if (rect != null) "✎" else "", date.toString(), page) {
                CalendarNavigator.toDayNote(fragment, date, page, rect)
            }
        }
        // Up, not out — the drawer's terminal row: back to the index this tag was picked from.
        rows += DirRow("‹", "All tags…") { showTagIndex(fragment) }
        rows
    }
}

// Synthesize's "New synthesis…" picker is GONE with its surface — it was the door that created a
// synthesis you would then go and stand on, and there is nowhere to stand now. What it wrapped is
// not gone: [showDocumentDirectory] over LedgerDocuments.SYNTHESIZE is exactly what the day chip
// opens when you ARE on a synthesis page, and the root Directory still lists the kind, so every
// synthesis Michael has ever written is two taps away and opens, pages and renames as it always did.

// `showWritePicker` is GONE, and this is the only thing about Write that went.
//
// It was a one-line wrapper — showDocumentDirectory over LedgerDocuments.WRITE — whose only caller
// was the hub's "✍ Write" row. Write is the LINES TEMPLATE now, and a template's shelf is reached
// the way every other template's is: Notes › All notes › Lines, the day chip on any Lines page, or
// the root Directory's Lines folder. A private door to one of five identical shelves is the
// five-separate-surfaces shape the Notes door exists to remove.
//
// NOT ONE BYTE OF WRITE'S DATA IS AFFECTED. "write", "write#1", "write-<millis>" and their
// sub-pages resolve, list, page, name and open exactly as before, from the Directory, from the day
// chip, and from any `ledger://<date>/write-<millis>` a gram or a link already carries — the
// routing has no allowlist and never had one. RetiredSurfaceReachabilityTest pins all of it.
//
// [showPickingsPicker] survives the same cut on purpose: Pickings keeps a door of its own at the
// end of the Filter funnel (All Stars → Gram Picks → Pickings), which is a different question from
// "which template is this note" and reaches the same surface a different way.

/**
 * The configured mailboxes, as the Mail row's sub-fold — the Boox half of the iPad's accounts
 * rail, where each account is listed beside the feeds rather than hidden behind a chip inside the
 * inbox. Reaching one mailbox should cost the same as reaching one feed; it was costing a
 * navigation plus a chip plus an accordion. (These were leading-space fake-indented rows once;
 * now the fold is real, they carry plain labels and the renderer owns the depth.)
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
    return accounts.map { a -> "@  ${a.display}" to { open(a.id) } }
}
