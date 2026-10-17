package com.toolsboox.plugin.feeds.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import android.os.Build
import android.os.Environment
import com.toolsboox.databinding.FragmentFeedsBinding
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.plugin.feeds.nw.MinifluxClient
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.mail.MailReaderHtml
import com.toolsboox.plugin.mail.MailSync
import com.toolsboox.plugin.mail.MailThreads
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * The in-app Feed Ledger — a native Miniflux list. Tapping an entry opens it in the
 * article reader; the star glyph saves it into the day JSON (shared corpus + sync).
 * Credentials (URL + token) live in EncryptedSharedPreferences.
 */
@AndroidEntryPoint
class FeedsFragment @Inject constructor() : ScreenFragment(), com.toolsboox.ui.plugin.ReturnAnchorProvider {

    /**
     * WHICH ROOM this instance is (Michael 2026-10-11: "You can move mail ... out of incoming" ·
     * "Mail can have its own accordion then"). The MailFragment nav destination is this class with
     * `room = mail`: it stands on the mail lens for good, with its own remembered place
     * ([MailPlace]) and its own drawer (search · 📥 Inbox · ⭐ Starred · each account ▸ its Inbox ·
     * Sent · Junk · Trash · 👤 Contacts). The FeedsFragment destination names no room: 📰 Feeds, RSS only — a mail request
     * that ever lands there is handed on to The Mail. One class, one list, one reader, one rail.
     */
    val isMailRoom: Boolean get() = arguments?.getString(ARG_ROOM) == ROOM_MAIL

    /** If an article is open, stash a return anchor so the Day page can jump straight back to it. */
    override fun prepareReturnAnchor() {
        val e = currentArticle
        val letter = openMail?.takeIf { e != null && it.first == e.id }?.second
        // Armed by the ↩ chip only, when it is tapped (Michael 2026-10-11 review): set here, at
        // leave time, these one-shots were read by whatever way in came next — a gram's Go to
        // feed, Hub › 📧 The Mail, ▶ Continue — and opened yesterday's piece over it. A system-Back
        // return needs none of them: the surviving instance reopens its own [currentArticle].
        if (e != null && letter != null) {
            // ↩ BACK REOPENS A LETTER AS A LETTER (Michael 2026-10-11): the return lands on a
            // fresh instance whose [mailById] is empty, so the article stash opened the letter's
            // row as a bare web page on the RSS lens. A letter rides the mail channel instead —
            // the lens, its mailbox and the mail://<id> open the gram router already uses.
            // It returns to The Mail's own room (Michael 2026-10-11), on the mailbox it was read in.
            val shown = mode; val box = mailMailbox ?: MailPlace.ALL_ACCOUNTS; val id = letter.id
            FeedSelection.list = adapter.current()
            com.toolsboox.ui.plugin.LedgerReturn.set(R.id.action_to_mail, e.title) {
                FeedSelection.mode = shown
                FeedSelection.kind = KIND_MAIL
                FeedSelection.mailMailbox = box
                FeedSelection.pendingMailOpenId = id
            }
        } else if (e != null) {
            FeedSelection.list = adapter.current()
            com.toolsboox.ui.plugin.LedgerReturn.set(R.id.action_to_feeds, e.title) {
                FeedSelection.pendingInPaneEntry = e
            }
        } else {
            com.toolsboox.ui.plugin.LedgerReturn.clear()
        }
    }

    @Inject
    lateinit var miniflux: MinifluxClient

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    private var navBar: com.toolsboox.plugin.calendar.ui.CalendarNavBarHost? = null
    private var navAnchor: java.time.LocalDate = java.time.LocalDate.now()
    /** True after a DELIBERATE almanac step/pick — until then the anchor FOLLOWS today, so a
     *  fragment alive across midnight doesn't quietly window The Listen to yesterday (09-03:
     *  "it's giving me Sept 1 podcasts even though I'm on Sept 2"). */
    private var navPinned = false
    private var navGranularity: String = "day"   // day|week|month|quarter|year (from the nav slots)

    override val view = R.layout.fragment_feeds

    private lateinit var binding: FragmentFeedsBinding
    private lateinit var adapter: FeedEntryAdapter
    private var loading = false
    /** A refresh asked for WHILE one is in flight — queued, never dropped (the field bug: a
     *  lens/surface switch mid-load returned silently, and the old fetch then rendered under the
     *  new filter — often as an empty list or "(nothing in this lens yet)"). */
    private var refreshQueued = false
    /** Which corpus [allEntries] currently holds (the refresh cacheKey, or "mail"). Lets the
     *  warm-first read know whether the rows on screen already answer the current ask. */
    private var corpusKey = ""
    private var allEntries: List<FeedEntry> = emptyList()

    private val feedsTransportListener: () -> Unit =
        { if (isAdded) runCatching { com.toolsboox.plugin.feeds.ui.NowPlayingCard.summon(requireActivity()) } }

    override fun onResume() {
        super.onResume()
        // The midnight roll: an unpinned anchor snaps to the day it always meant ("today").
        if (!navPinned && navGranularity == "day" && navAnchor != java.time.LocalDate.now()) {
            navAnchor = java.time.LocalDate.now()
            if (::binding.isInitialized) { renderNav(); refresh() }
        }
        // The summon grammar (-59): while anything plays, the feeds surface carries at least the
        // edge chip; tap expands the card in place. Live via the player listener below.
        runCatching { com.toolsboox.plugin.feeds.ui.NowPlayingCard.summon(requireActivity()) }
        com.toolsboox.ui.plugin.LedgerPlayer.addListener(feedsTransportListener)
        // Returning from the article with "open the directory" flagged (the ☰ path): entries are
        // already loaded, so fire it here — the refresh success path only covers fresh loads.
        // (The article's ☰ means the RSS drawer — Feeds' to honour, never The Mail's.)
        if (!isMailRoom && FeedSelection.openDirectoryOnArrival && allEntries.isNotEmpty()) {
            FeedSelection.openDirectoryOnArrival = false
            showFeedDirectory()
        }
        // Volume keys move the page here too. The book reader and the full-screen article both
        // had this; the list you actually spend the time in did not, so putting the device down
        // and picking it up meant reaching for the screen again. Whichever is in front gets the
        // keys — the in-pane article when it's open, otherwise the list itself.
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = handler@{ up ->
            if (!volumeTurnOn()) return@handler false
            if (binding.articlePane.visibility == View.VISIBLE) {
                feedsPanel?.readerPageTurn()   // before the page moves (ReaderFullScreen)
                val step = (binding.articleWeb.height * 9 / 10).coerceAtLeast(1)
                binding.articleWeb.scrollBy(0, if (up) -step else step)
            } else {
                val step = (binding.feedsRecycler.height * 9 / 10).coerceAtLeast(1)
                // E-ink: the list must JUMP a page, never smooth-scroll (which ghosts/smears).
                binding.feedsRecycler.scrollBy(0, if (up) -step else step)
            }
            true
        }
    }

    /** Held as a field so the exact instance can be removed again — a lambda written inline at
     *  both ends is two different objects, and `removeListener` on the second one is a no-op that
     *  leaks the first for the life of the process. */
    private val playerRailListener: () -> Unit = {
        binding.root.post { if (isAdded) redressRail() }
        Unit
    }

    override fun onDestroyView() {
        // The reading position, kept for the trip's return leg (see onViewCreated's stash): the
        // view is dying but the instance is not, and where the eyes were is view state nothing
        // else remembers. Captured only while a piece is open — a bare list has no position.
        if (::binding.isInitialized && currentArticle != null) restoreScrollY = binding.articleWeb.scrollY
        com.toolsboox.ui.plugin.LedgerPlayer.removeListener(playerRailListener)
        // The one WebView in the app that was never destroyed with its view (the reader, The Net
        // and the Articles screen all do) — each recreation stranded a renderer (audit 09-10).
        if (::binding.isInitialized) runCatching { binding.articleWeb.destroy() }
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        com.toolsboox.ui.plugin.LedgerPlayer.removeListener(feedsTransportListener)
        runCatching { com.toolsboox.plugin.feeds.ui.NowPlayingCard.dismissSummon(requireActivity()) }
        (activity as? com.toolsboox.ui.main.MainActivity)?.volumeKeyHandler = null
        // Leaving the screen is the other natural flush point for the Bluesky "seen" batch — the
        // size threshold alone would strand a half-batch on the device every time he read six posts
        // and went back to the day page, and those six would be unread again tomorrow.
        flushBlueskySeen()
        // Same rule for the coalesced Miniflux read-marks: the debounce runnable dies with the
        // view, so anything still gathered leaves NOW or those rows come back unread tomorrow.
        flushServerReads()
    }

    /**
     * Server-side read marks, COALESCED. The scroll handler and "Mark above as read" can hand
     * [markEntryRead] a hundred entries in one gesture, and each used to launch its own
     * coroutine + HTTP call — a hundred simultaneous launches on a Palma is what "marking 100
     * posts in 'The Watch' crashes Ledgable" was (08-16 page). Ids gather here and leave in ONE
     * batched `setStatus` after a beat of quiet; a failed flush leaves them queued for the next.
     */
    private val pendingServerReads = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val serverReadFlush = Runnable { flushServerReads() }

    private fun queueServerRead(id: Long) {
        pendingServerReads.add(id)
        binding.feedsRecycler.removeCallbacks(serverReadFlush)
        binding.feedsRecycler.postDelayed(serverReadFlush, 800)
    }

    private fun flushServerReads() {
        if (!isAdded) return
        val p = prefs(); val u = p.getString(KEY_URL, "").orEmpty(); val tk = p.getString(KEY_TOKEN, "").orEmpty()
        val batch = synchronized(pendingServerReads) { pendingServerReads.toList() }
        if (batch.isEmpty() || u.isBlank() || tk.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { miniflux.setStatus(u, tk, batch, "read") }.getOrNull()
            if (ok is MinifluxClient.Result.Ok)
                synchronized(pendingServerReads) { pendingServerReads.removeAll(batch.toSet()) }
            else withContext(Dispatchers.Main) {
                // A blip must not strand the marks in memory until the screen closes (the read
                // state is per-process — an un-flushed mark resurrects unread tomorrow). Re-arm
                // with a longer beat; onPause's flush remains the last resort.
                if (isAdded && ::binding.isInitialized) {
                    binding.feedsRecycler.removeCallbacks(serverReadFlush)
                    binding.feedsRecycler.postDelayed(serverReadFlush, 5_000)
                }
            }
        }
    }

    /** Shared with the full-screen article and the book reader, so one setting covers reading. */
    private fun volumeTurnOn() =
        requireContext().getSharedPreferences("ledger_reader_nav", 0).getBoolean("volume_turn", true)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeedsBinding.bind(view)

        // COMING BACK IS PART OF READING. The note doors — the FeedNoteGram trip, ✍ Write the
        // reply, ⁂ rhizome, a gram placement's "Go to it" — navigate away, which destroys this
        // VIEW but keeps the fragment instance on the back stack, fields intact. The old view's
        // open article therefore survives in [currentArticle] — but the fresh view starts with
        // the pane GONE, so without this stash the return landed on the bare list (Michael:
        // "swiping back from a Note to a feed article should be persistent — not just back to
        // the feed but the ARTICLE being read"), while the stale field quietly mis-dressed the
        // rail. Stash it, zero it (a fresh view holds no pane until one is opened through the
        // front door), and the arrival block at the bottom reopens it through openEntry — the
        // same door the gram router's pending-open uses, so a letter re-resolves through
        // [mailById] (also a surviving field) and the reading grammar comes back whole.
        val cameBackTo = currentArticle
        // …and a detour through the letter's thread cards rides the trip too: ⌨ Type the reply is
        // the composer now, a trip away, and coming back mustn't lose the row ⏫⏬ step from or
        // the "‹ Back" line (2026-10-10 review).
        val hopOrigin = mailThreadOrigin
        val hopBack = mailThreadBack
        // The return anchor's letter coming back through this very stash: the stash reopens it,
        // so a mail://<id> open naming the same letter stands down — one open, not two. (System
        // Back off the Day page arms nothing since the ↩ chip carries its own reopen; this stays
        // as the guard.)
        cameBackTo?.let { e -> openMail?.takeIf { it.first == e.id }?.second }?.let { m ->
            if (FeedSelection.pendingMailOpenId == m.id) FeedSelection.pendingMailOpenId = null
        }
        currentArticle = null

        adapter = FeedEntryAdapter(emptyList(), onOpen = ::openEntry, onStar = ::toggleStar,
                                   onLongPress = ::showEntryHoldMenu)
        adapter.answeredSource = mailAnsweredRows
        // THE LISTEN'S PICTURES (Michael 2026-10-12): every row in The Listen wears its picture —
        // the episode's own, else its show's cover — with the ▶ on it. Tapping the picture plays
        // (or pauses the one playing); tapping the words opens the entry as it always has, so a
        // row tap still never starts sound (08-23). The shelf names its show apart from its
        // "· N min left" line.
        adapter.onPlay = ::playFromRow
        adapter.playable = { e -> kindFilter == "listen" && (e.mediaUrl != null || e.url.startsWith("abs:")) }
        adapter.showOf = { e -> if (isShelfRow(e)) shelfShows[e.id] else e.feedTitle.ifBlank { null } }
        // Text-size tier lives in the shared a11y prefs (set from the wrench). Migrate the
        // short-lived boolean toggle if it was flipped on.
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        if (prefs().getBoolean("feeds_large_rows", false) && a11y.getString("feed_text_size", null) == null) {
            a11y.edit().putString("feed_text_size", "large").apply()
        }
        adapter.textTier = a11y.getString("feed_text_size", "medium") ?: "medium"
        binding.feedsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.feedsRecycler.adapter = adapter
        binding.feedsRecycler.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        // "Mark as read on scroll": as rows scroll off the top, mark them read (Miniflux + the list).
        //
        // The marks land QUIETLY mid-jump (notify=false — yanking rows or repainting during a
        // page-jump smears e-ink), and then ONE rebind fires when the scroll SETTLES: without it,
        // rows scrolled back into view came off the RecyclerView's view cache un-rebound, still
        // wearing their unread face — the field's "on scroll doesn't seem to be working" (08-30;
        // the marks were real, the pixels were stale).
        binding.feedsRecycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            private var markedThisGesture = false
            /** How far down the rows above the fold were marked — the only rows whose bind changed. */
            private var markedUpTo = 0
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                tuckSearchOnScroll(rv, dy)
                if (dy <= 0 || markReadMode() != "scroll") return
                val first = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                val list = adapter.current()
                for (i in 0 until first) list.getOrNull(i)?.let {
                    if (!FeedReadState.isRead(it.id)) {
                        markEntryRead(it, notify = false)
                        markedThisGesture = true
                        markedUpTo = maxOf(markedUpTo, first)
                    }
                }
                // Programmatic jumps (the pill's ↓, volume keys) never leave IDLE state, so the
                // settle hook below can't fire for them — rebind on the next frame instead.
                if (markedThisGesture && rv.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    markedThisGesture = false
                    rv.post { if (isAdded) rebindMarked() }
                }
            }
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE && markedThisGesture) {
                    markedThisGesture = false
                    rv.post { if (isAdded) rebindMarked() }
                }
            }
            /** Only the rows that were marked (all ABOVE the fold) changed — notify that range, not
             *  the whole list (audit 09-10): a full `notifyDataSetChanged` on every settle rebound and
             *  repainted the visible page on e-ink for rows that had not changed at all. */
            private fun rebindMarked() {
                val n = markedUpTo.coerceAtMost(adapter.itemCount); markedUpTo = 0
                if (n > 0) adapter.notifyItemRangeChanged(0, n)
            }
        })

        val prefs = prefs()
        binding.urlEdit.setText(prefs.getString(KEY_URL, ""))
        binding.tokenEdit.setText(prefs.getString(KEY_TOKEN, ""))

        // The pill's wrench now opens the standalone controls modal (mark-read / rotate / layout /
        // feed settings) — one wrench, not two.
        binding.settingsButton.setOnClickListener { showFeedControls() }
        binding.saveSettingsButton.setOnClickListener {
            prefs().edit()
                .putString(KEY_URL, binding.urlEdit.text.toString().trim())
                .putString(KEY_TOKEN, binding.tokenEdit.text.toString().trim())
                .apply()
            binding.settingsPanel.visibility = View.GONE
            showMessage(R.string.feeds_saved)
            refresh()
        }
        binding.refreshButton.setOnClickListener {
            // ⟳ over 🚫 Junk asks the server again even inside [JUNK_FRESH_MS] — the one deliberate
            // re-read; every other refresh() there (👁, a window step, back from a letter) reuses it.
            if (kindFilter == KIND_MAIL && MailPlace.where(mailMailbox).box == MailPlace.Box.JUNK) junkReread = true
            refresh()
        }
        // The header's ▦ hub and RSS drawer buttons retire with the rail: the rail's ☰ Hub is
        // the same door, and the drawer toggle rides the rail with the rest of the surface's
        // actions (one door per action — the navigator strip keeps the whole header width).
        binding.feedsHubButton.visibility = View.GONE
        binding.ledgerButton.visibility = View.GONE
        binding.ledgerButton.setOnClickListener {
            applyDirectoryDrawer(binding.directoryScroll.visibility != View.VISIBLE)
        }
        applyDirectoryDrawer(drawerOpenPref())
        binding.viewToggleButton.setOnClickListener {
            mode = if (mode == "stars") "feed" else "stars"
            refresh()
        }

        // Persistent floating nav pill: ↑↓ page the list, centre ☰ opens the directory.
        // On e-ink the page must *appear* instantly — a plain scrollBy, never a smooth animation.
        binding.feedsPageUp.setOnClickListener { pageBy(-1) }
        binding.feedsPageDown.setOnClickListener { pageBy(1) }
        // Pill centre = the RSS-feed button that pops the directory drawer in/out.
        // Pill centre = today-then-menu (unified across surfaces): first tap returns to
        // today's live feed; a second tap (already on today) opens the section menu.
        binding.feedsGoto.setImageResource(R.drawable.ic_nav_today)
        binding.feedsGoto.setOnClickListener {
            // "Home" means today AND plain — a feed or category pick, Read/All, Starred or a
            // narrowed mailbox all count as being away, so the first tap brings plain Unread back
            // for the lens you're in; only a second tap opens the menu. ☀ TODAY IS THE ONE-TAP
            // RESET (Michael 2026-10-11) now that Incoming remembers where you were.
            // A standing list search and a Later selection are narrowings too (2026-10-11 review):
            // the reset lets go of both, so the second tap is the only one that opens the menu.
            val atHome = if (isMailRoom) MailPlace.isHome(currentMailPlace()) else IncomingPlace.isHome(currentPlace())
            if (atHome && listSearchQuery.isEmpty() &&
                binding.laterSelectBar.visibility != View.VISIBLE &&
                navGranularity == "day" && navAnchor == java.time.LocalDate.now()) {
                // At home, Today's second tap is today's day page in Feeds (spec Q2, Michael
                // 2026-10-11). The directory lost nothing: it is More's first row and the left
                // edge's Back (Michael 2026-10-12 — Hub's seat left the column).
                // The Mail keeps its old second tap until its own panel step.
                if (isMailRoom) showLedgerDirectory() else openTodaysPage()
            } else {
                exitLaterSelection()
                if (listSearchQuery.isNotEmpty() || binding.feedsListSearch.text.isNotEmpty()) {
                    // Cleared ahead of the field so its watcher has nothing left to re-render.
                    listSearchQuery = ""; mailSearchQuery = ""; modeBeforeListSearch = null
                    listSearchDebounce.removeCallbacksAndMessages(null)
                    binding.feedsListSearch.setText("")
                }
                // Each room resets its own place (☀ in The Mail: every account's Unread; ☀ in
                // Feeds: plain Unread for the lens) — neither touches the other's.
                if (isMailRoom) applyMailPlace(MailPlace.today(currentMailPlace()))
                else applyPlace(IncomingPlace.today(currentPlace()))
                navGranularity = "day"; navAnchor = java.time.LocalDate.now(); navPinned = false
                renderNav(); onDateChanged()
            }
        }
        // In-pane article actions (visible only while reading): star · note · reader-toggle · TTS.
        binding.feedsStar.setOnClickListener { currentArticle?.let { starArticle(it) } }
        binding.feedsNote.setOnClickListener { currentArticle?.let { noteArticle(it) } }
        // THE LIST SEARCH FIELD (09-10): the in-feed filter the 🔎 dialog ran, standing at the top
        // of every feed. Live, debounced a beat for e-ink; clearing it brings the whole list back.
        binding.feedsListSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                if (q == listSearchQuery) return
                listSearchQuery = q
                listSearchDebounce.removeCallbacksAndMessages(null)
                listSearchDebounce.postDelayed({
                    if (!isAdded) return@postDelayed
                    // The field cleared after an Enter had sent the list to the whole server:
                    // back to the list you were in (09-14).
                    if (listSearchQuery.isEmpty() && mode == "smart") modeBeforeListSearch?.let { (m, k) ->
                        modeBeforeListSearch = null; switchTo(m, k); return@postDelayed
                    }
                    // An edit retires any server answer still in flight for the old words, so a
                    // late IMAP reply can't stamp itself over the list you're now filtering.
                    if (kindFilter == KIND_MAIL) { mailSearchQuery = listSearchQuery; submitMailRows() }
                    else adapter.submit(if (listSearchQuery.isEmpty()) filterByNavDay(applyPicked(applyKind(allEntries))) else applySearch(allEntries))
                }, 220)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.feedsListSearch.visibility = if (listSearchSummoned()) View.VISIBLE else View.GONE
        binding.feedsListSearch.hint =
            if (isMailRoom) "Search letters · Enter asks the servers" else "Search this list · Enter searches everything"
        binding.feedsListSearch.setOnEditorActionListener { v, _, _ ->
            (v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
            // ENTER ASKS THE WHOLE SERVER (Michael, 09-14: "it doesn't bring up the options that
            // have been read… I won't be able to surface read articles"): typing filters what is
            // loaded — this window's unread; Enter runs the server-wide search, read and all, as
            // the 🔎 dialog does. Clearing the field brings the list you were in back.
            val q = binding.feedsListSearch.text.toString().trim()
            // MAIL SEARCH STAYS IN MAIL (Michael 2026-10-11): in The Mail, Enter asks the mail
            // servers — the 🔎 row's own deep search, read letters and all — never the RSS
            // server, which used to swap the letters for articles. Clearing the field puts the
            // letters back (the debounce above re-gathers).
            if (q.isNotBlank() && kindFilter == KIND_MAIL) {
                mailSearchQuery = q.lowercase()
                // ➤ Sent and 🗑 Trash live on this device: the filter already is the whole search.
                if (mailServerSearchHere()) runMailServerSearch()
            } else if (q.isNotBlank()) {
                if (mode != "smart") modeBeforeListSearch = mode to kindFilter
                switchToSmart(com.toolsboox.plugin.feeds.nw.SmartFeed("", q, q.lowercase()))
            }
            true
        }
        // The long-hold selection menu's verbs (SelectionMenuWebView). On an ARTICLE (Michael
        // 2026-10-12): Highlight keeps the passage in ONE tap — drawn, its Log line, its one gram
        // pick on All Picks ("Highlight should make a gram pick anyway"; Make pick is gone) — and
        // Note opens the chooser with it in hand (ArticleHighlights). In a LETTER both open the
        // chooser as before (marks are the article's; letters are a later step). Search Ledger runs
        // the passage through the Log's search.
        binding.articleWeb.onSelectionAction = { verb ->
            when (verb) {
                com.toolsboox.plugin.feeds.ot.SelectionMenuWebView.Verb.HIGHLIGHT ->
                    currentArticle?.let { e -> if (marksOn(e)) articleHighlights.highlight() else noteArticle(e) }
                com.toolsboox.plugin.feeds.ot.SelectionMenuWebView.Verb.NOTE ->
                    currentArticle?.let { noteArticle(it) }
                com.toolsboox.plugin.feeds.ot.SelectionMenuWebView.Verb.SEARCH ->
                    com.toolsboox.plugin.feeds.ot.WebSelectionBridge.probe(binding.articleWeb) { sel ->
                        val q = sel.lineSequence().firstOrNull()?.trim().orEmpty()
                        if (q.isBlank()) return@probe
                        com.toolsboox.plugin.calendar.ui.ReadingLogSelection.seedQuery = q
                        findNavController().navigate(R.id.action_to_reading_log)
                    }
            }
        }
        binding.feedsParsed.setOnClickListener {
            // A letter flips its own flag (its default is a Mail setting, not the article one).
            if (openMail != null) {
                mailParsed = !mailParsed; renderArticle()
                showMessage(if (mailParsed) "Reader view" else "As sent", binding.root)
            } else {
                showParsed = !showParsed; renderArticle()
                showMessage(if (showParsed) "Reader view" else "Original", binding.root)
                redressRail()   // the Reader seat's box follows (one cell, in place)
            }
        }
        binding.feedsTts.setOnClickListener { toggleArticleTts() }
        // Touch pagination in the in-pane article reader (like the book reader): tap sides to turn.
        // The page-turn corners sit over the text's bottom corners: a tap there on a drawn
        // highlight opens it instead of turning (design critique #15); anywhere else it turns.
        fun cornerTaps(zone: View, dir: Int) {
            var downX = 0f; var downY = 0f
            @Suppress("ClickableViewAccessibility")
            zone.setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { downX = ev.rawX; downY = ev.rawY }
                false
            }
            zone.setOnClickListener {
                val e = currentArticle
                if (e == null || !marksOn(e)) { pageBy(dir); return@setOnClickListener }
                val at = IntArray(2).also { binding.articleWeb.getLocationOnScreen(it) }
                articleHighlights.cornerTap(downX - at[0], downY - at[1]) { pageBy(dir) }
            }
        }
        cornerTaps(binding.articleTapLeft, -1)
        cornerTaps(binding.articleTapRight, 1)
        binding.feedsLater.setOnClickListener { currentArticle?.let { addToLater(it) } }
        // Shrunk article-drawer: ⏫ last article · ‹ page up · › page down · ⏬ next article.
        binding.feedsDwPrev.setOnClickListener { openAdjacentArticle(-1) }
        binding.feedsDwNext.setOnClickListener { openAdjacentArticle(1) }
        // Find within the open piece — one bar over the one pane, so an article and a letter get
        // the same find. See the block at setupArticleFind.
        setupArticleFind()

        // BOTH floating pills retire — the tucked action rail carries the paging trio, the
        // occasional controls AND the article actions, adapting its dress to the mode the way
        // the pill's visibility dance used to (see applyArticlePill, which now re-dresses the
        // rail). The pills' views stay in the layout, hidden, so nothing referencing them
        // breaks; their buttons keep answering performClick.
        binding.feedsPill.visibility = View.GONE
        binding.feedsNavPill.visibility = View.GONE
        // THE ONE PANEL LANGUAGE in Feeds (Michael 2026-10-11: one design language for every tuck
        // panel, both apps — the iOS Feeds list and article wear the same column):
        //   list     ⛶ | Today · Nib · More | Page ↑ · Page ↓ · Previous · Next · Refresh |
        //              Search · Show · Synth | Side
        //   article  ⛶ | Today · Nib · More | Page ↑ · Page ↓ · Previous · Next |
        //              Star · Note · Find · Reader · Type · Listen · Synth · Later | Side
        // Hub and the Feeds drawer's seat left the column (Michael 2026-10-12: "Those too."): the
        // left edge's Back opens the drawer, then the Hub ([edgePane] — the article closes first),
        // and More's first two rows are ☰ Hub and the drawer, for a Back with no edge. The
        // player's transport rides under the frame while something plays. The Mail keeps
        // IncomingRail's column until its own step (one room at a time) — its wrench is its More.
        feedsPanel = if (isMailRoom) setupActionRail(
            binding.feedsRail, "feeds",
            actions = { feedsRailActions().also { railSeatKey = seatKey(it) } },
            hub = { showLedgerDirectory() },
            ownMore = true
        ) else setupPanel(
            binding.feedsRail, "feeds", com.toolsboox.ot.PanelSeats.Dress.FEED_LIST,
            fullPillHost = binding.feedsTopRow,
            flags = { feedsPanelFlags() },
            dressNow = {
                if (currentArticle != null) com.toolsboox.ot.PanelSeats.Dress.ARTICLE
                else com.toolsboox.ot.PanelSeats.Dress.FEED_LIST
            },
            afterFrame = { com.toolsboox.ui.plugin.LedgerPlayerRail.items(requireContext()) { redressRail() } },
        ) { seat -> feedsSeat(seat) }
        // FULL SCREEN IN A READER LEAVES NOTHING ON THE PAGE (Michael 2026-10-12): while a piece is
        // open the panel is a reader — full screen (or the Mail's tucked strip) puts the ⛶ and the
        // notes pill away, a tap in the middle of the piece brings them back, a page turn puts them
        // away again ([com.toolsboox.ot.ReaderFullScreen]). The list keeps its pill. In normal
        // reading that middle tap goes into full screen, as on the iPhone (Michael 2026-10-12:
        // "yes") — the article's ⛶, or the Mail letter's tuck (TuckPanel.readerMiddleTap).
        feedsPanel?.reader = currentArticle != null
        com.toolsboox.plugin.feeds.ot.ArticleFullScreen.wire(binding.articleWeb, { feedsPanel }) {
            binding.articlePane.visibility == View.VISIBLE
        }
        // The transport changes on its own — a track ends, a chapter rolls over, the system pauses
        // for a call — and a rail that only re-dresses when YOU press something would go on showing
        // ▶ over a finished episode. Posted to the main thread because the player's callbacks come
        // off whichever thread the media session used.
        com.toolsboox.ui.plugin.LedgerPlayer.addListener(playerRailListener)

        // System Back closes an open in-pane article (returns to the list) before leaving — but an
        // open find bar is the innermost thing on screen, so Back puts the plain page back first.
        // Losing the whole piece because you were done searching it would be the wrong size of undo.
        articleBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (articleFindOpen()) { hideArticleFind(); return }
                closeArticlePane(restoreDrawer = false)
            }
        }
        // Through addOwnBackCallback: it stands above the left edge's ladder, so the piece closes first.
        addOwnBackCallback(articleBackCallback!!)

        // The real Almanac navigator strip (same as Tasks & Events) — arrows step the
        // date in place; the day/week/month slots jump into the calendar.
        navBar = com.toolsboox.plugin.calendar.ui.CalendarNavBarHost(
            requireContext(), binding.navigatorImageView, this,
            onStepDay = { d ->
                val dir = if (d.isBefore(navAnchor)) -1 else 1
                navAnchor = stepByGranularity(navAnchor, dir)
                navPinned = true
                renderNav(); onDateChanged()
            },
            onSelectPeriod = { g, d ->
                navGranularity = g; navAnchor = d; navPinned = true; renderNav(); onDateChanged()
            }
        )
        renderNav()

        // THE ONE-TIME SPLIT (Michael 2026-10-11: Mail out of Incoming): The Mail's mailbox and view
        // leave the shared Incoming place for MailPlace before EITHER room reads or writes a place
        // (critique 1: run from both arrivals, so whichever room opens first can't save over it).
        MailPlace.migrateOnce(placePrefs(), mailPlacePrefs())
        // A mail request that lands in FEEDS — a caller not yet routed to The Mail's room — is
        // handed on, the instance popped so Back doesn't land on an empty Feeds (critique 5).
        val mailHandoff = if (isMailRoom) null else takeMailHandoff()
        if (isMailRoom) arriveInMail(a11y) else arriveInFeeds(a11y)
        mailHandoff?.let { go -> binding.root.post { if (isAdded) go() } }
        // RSS one-shots never open over letters (and are retired here, so they can't fire on a
        // later Feeds visit either); a letter request never opens over RSS.
        if (isMailRoom) {
            FeedSelection.filterFeedTitle = null
            FeedSelection.pendingInPaneEntry = null
            FeedSelection.pendingResumeListenId = null
        } else FeedSelection.pendingMailOpenId = null
        // A gram's "Go to feed" names a feed: kept as a standing pick, so ⟳ and date steps keep
        // showing that feed rather than dropping back to everything after the first load. With no
        // lens named it lands on everything at your view — the remembered lens could hide the feed.
        FeedSelection.filterFeedTitle?.let { f ->
            FeedSelection.filterFeedTitle = null
            if (!feedsDoorNamed) applyPlace(IncomingPlace.goToFeed(currentPlace(), f)) else pickFeed(null, f)
        }
        if (mailHandoff == null) refresh()

        // The article's ☰ asks the list to pop the RSS directory open on return.
        if (FeedSelection.openDirectory) {
            FeedSelection.openDirectory = false
            binding.root.post { showFeedDirectory() }
        }
        // Open an article straight into the in-pane reader (from a gram's "Go to source") — or
        // REOPEN the one that was open when a trip left this surface (the stash at the top of
        // this method). An explicit pending open wins: it was asked for, the stash was merely
        // left behind. Posted, same as the pending path always was; refresh() has already run
        // synchronously above, so its close-any-open-pane guard fired before the pane exists,
        // and its async completion repaints the LIST without touching the pane.
        val pending = FeedSelection.pendingInPaneEntry
        FeedSelection.pendingInPaneEntry = null
        FeedSelection.pendingResumeListenId?.let { rid ->
            FeedSelection.pendingResumeListenId = null
            // SELF-SUFFICIENT by design (09-03: "it goes to the feeds list but there's no feed
            // it goes to" — the old lookup raced the async list load and lost every time):
            // the ListenState record carries the enclosure URL, so playback needs NOTHING
            // from the list. Enrichment (title/art/chapters) upgrades in place if the row
            // shows up later; the audio starts NOW.
            // OPEN the episode, never autoplay (09-03: "I don't want autoplay that way") — the
            // page carries ▶ Play, and the resume seek is the player's own when he presses it.
            // Prefer the real entry from the loaded pool; synthesize a minimal one if it hasn't
            // arrived (the episode page + enclosure the ListenState already knows), so the pane
            // still opens with its play bar instead of a link-menu web page.
            val st = com.toolsboox.plugin.calendar.ot.ListenStateStore.get(
                requireContext().applicationContext, rid)
            // The stand-in now carries the record's show and picture too (10-12) — pressing ▶ Play
            // on it gave the transport, and the synced record, neither.
            openEntry(listenEntryFor(rid, st))
        }
        // The captured reading position belongs to the article we left. A pending open of a
        // DIFFERENT piece starts at the top like every fresh open. (The ☰ path's return anchor
        // sets pending = the article that was open — same id, so the position survives there.)
        // THE PLAYER'S TITLE LANDS ON THE REAL ROW (Michael, 09-19: "clicking on the title while
        // it's playing should take you to its incoming feed item"). The transport hands over a
        // synthetic entry (id 0, the episode page as its url); resolved here to the loaded pool's
        // own entry — by page URL, or by enclosure for a track started off ▶ Continue — and the
        // list scrolled to it, so the pane that opens is the row's own (its star, its verbs, its
        // read mark), not a nameless web page. Unresolved, the synthetic entry still opens.
        val resolved = pending?.takeIf { it.id == 0L && it.url.isNotBlank() }?.let { p ->
            val pool = adapter.current().ifEmpty { FeedSelection.list }
            pool.firstOrNull { it.url == p.url || it.mediaUrl == p.url || it.enclosureAudio == p.url }
        }
        val pendingEntry = resolved ?: pending
        if (resolved != null) binding.root.post {
            val i = adapter.current().indexOf(resolved)
            if (i >= 0) binding.feedsRecycler.scrollToPosition(i)
        }
        if (pendingEntry != null && pendingEntry.id != cameBackTo?.id) restoreScrollY = 0
        val reopen = pendingEntry ?: cameBackTo
        if (reopen == null) restoreScrollY = 0
        reopen?.let { e ->
            binding.root.post {
                val card = if (e === cameBackTo && hopOrigin != null) mailById[e.id] else null
                if (card != null) { mailThreadOrigin = hopOrigin; openMailEntry(e, card, backTo = hopBack, fromCard = true) }
                else openEntry(e)
                replayArticleScroll()
            }
        }
    }

    /** Whether the Feeds arrival came through a door that named something (set by
     *  [arriveInFeeds]) — a gram's "Go to feed" then narrows that door's lens instead of all. */
    private var feedsDoorNamed = false

    /**
     * 📰 FEEDS' ARRIVAL. Honour the view/kind chosen from the hub (feed / stars / later, +
     * read/watch/listen) — but ONLY when something was actually chosen. The one-shot channel is
     * empty on the return leg of a trip (the note doors set nothing), and consume()'s "feed"/null
     * defaults used to overwrite the surviving mode/kindFilter fields. An arrival that asked for
     * nothing keeps the view it left; a fresh instance asking for nothing lands on the remembered
     * place. (The one-shots are still consumed either way — a stale request must never fire on a
     * later visit that didn't make it.) Mail requests never reach here: [takeMailHandoff] took
     * them first (Michael 2026-10-11, Mail out of Incoming).
     */
    private fun arriveInFeeds(a11y: SharedPreferences) {
        // THE REMEMBERED PLACE (Michael 2026-10-11: "going back to where you were on incoming
        // should be easy"). The plain 📰 Feeds door names nothing it wants — it asks for where
        // you were — so it counts as no door at all, whatever view its one-shot carries.
        val plainDoor = FeedSelection.restorePlaceOnArrival
        FeedSelection.restorePlaceOnArrival = false
        val explicitArrival = !plainDoor && (FeedSelection.mode != null || FeedSelection.kind != null ||
            FeedSelection.laterLane != null)
        val door = if (!explicitArrival) null else IncomingPlace.Door(
            mode = FeedSelection.mode, kind = FeedSelection.kind, laterLane = FeedSelection.laterLane)
        feedsDoorNamed = door != null
        FeedSelection.consume(); FeedSelection.consumeLaterLane(); FeedSelection.consumeMailMailbox()
        // The Mail's fold no longer lives in this drawer: a lens state left at "email" folds.
        if (a11y.getString("feeds_lens_open", "") == "email") a11y.edit().putString("feeds_lens_open", "").apply()
        // A fresh instance — every Hub, Sections, widget or ↩-chip way in, and a restart — starts
        // from the place in ledger_incoming instead of the bare defaults it used to (Q1). A
        // surviving instance (a trip's system-Back return) already IS the place.
        val freshArrival = !placeArrived
        placeArrived = true
        if (!freshArrival && door == null) return
        var place = IncomingPlace.arrive(
            if (freshArrival) IncomingPlace.load(placePrefs()) else currentPlace(), door)
        if (door == null && (FeedSelection.pendingInPaneEntry != null || FeedSelection.pendingResumeListenId != null))
            place = IncomingPlace.forPiece(place)
        applyPlace(place)
        // Arriving on a Later lane from the hub opens the folder it belongs to, so the drawer
        // shows where you are instead of a folded row and a list you can't account for.
        if (laterLane != null) a11y.edit().putBoolean("feeds_later_open", true).apply()
        // Same truth for the media lenses: arriving on The Listen must fold whatever lens the
        // drawer last remembered open (The Read stayed expanded beside a Listen list — "not
        // correct ux"). The single-open rule the pane's own taps keep applies to arrivals too. A
        // place with no lens folds them all (09-03: "it started with mail accordion open for no
        // reason") — the fold follows the place instead of always resetting.
        val k = kindFilter
        if (k != null) a11y.edit().putString("feeds_lens_open", k).apply()
        else if (door == null) a11y.edit().putString("feeds_lens_open", "").apply()
        // Arriving at The Listen is the moment to make good on what the fleet asked to keep:
        // it is the last point before you want those files and the first at which a merge from
        // another device could have told us about them. See [fulfilOfflineAsks].
        if (k == "listen") com.toolsboox.plugin.calendar.ot.ListenStateStore.sync(requireContext()) {
            activity?.runOnUiThread { if (isAdded) fulfilOfflineAsks() }
        }
    }

    /**
     * 📧 THE MAIL'S ARRIVAL (Michael 2026-10-11: Mail is its own room). Always the mail lens; the
     * place is [MailPlace] — the mailbox and view you were in, unless the door named a view, a
     * mailbox, or a letter (whose row must be on screen: every account). The plain door (the Hub's
     * "The Mail", Sections) names nothing and lands where you were; a surviving instance (a trip's
     * system-Back return) already IS the place.
     */
    private fun arriveInMail(@Suppress("UNUSED_PARAMETER") a11y: SharedPreferences) {
        val plainDoor = FeedSelection.restorePlaceOnArrival
        FeedSelection.restorePlaceOnArrival = false
        val letter = FeedSelection.pendingMailOpenId != null
        val explicit = !plainDoor && (FeedSelection.mode != null || FeedSelection.mailMailbox != null || letter)
        val door = if (!explicit) null else MailPlace.Door(
            mode = FeedSelection.mode, mailbox = FeedSelection.mailMailbox, letter = letter)
        FeedSelection.consume(); FeedSelection.consumeLaterLane(); FeedSelection.consumeMailMailbox()
        val freshArrival = !placeArrived
        placeArrived = true
        if (freshArrival || door != null) applyMailPlace(MailPlace.arrive(
            if (freshArrival) MailPlace.load(mailPlacePrefs()) else currentMailPlace(), door))
        // Whatever else happened, this room reads letters.
        kindFilter = KIND_MAIL
    }

    /**
     * A MAIL REQUEST THAT LANDED IN FEEDS — a mail kind, or a letter with no view named — taken
     * out of the one-shot channel (so the Feeds arrival sees no door) and handed back as the move
     * that re-asks it of The Mail's room, popping this Feeds instance so Back never lands on it.
     * Every known caller goes to The Mail directly; this is the net under them (critique 5).
     */
    private fun takeMailHandoff(): (() -> Unit)? {
        val sel = FeedSelection
        val wantsMail = sel.kind == KIND_MAIL ||
            (sel.pendingMailOpenId != null && sel.mode == null && sel.kind == null)
        if (!wantsMail) return null
        val mode = sel.mode; val box = sel.mailMailbox; val letter = sel.pendingMailOpenId
        sel.mode = null; sel.kind = null; sel.mailMailbox = null; sel.pendingMailOpenId = null
        sel.restorePlaceOnArrival = false
        Timber.i("feeds: a mail request landed in Feeds — handing it to The Mail")
        val nav = findNavController()
        return { openMailRoom(nav, mailbox = box, mode = mode, letterId = letter, popFeeds = true) }
    }

    /**
     * Where the reading had got to, across the trip: the article WebView's scrollY, captured in
     * onDestroyView while a piece was open, replayed after the return reopens it. A field, not
     * saved state — it rides the same fragment-instance survival the article itself does.
     */
    private var restoreScrollY = 0

    /**
     * Put the reopened article back where the eyes were. The render is asynchronous
     * (loadDataWithBaseURL lays out on its own time; a letter arrives only after its off-thread
     * hydrate), so this polls: scroll once the document is tall enough to hold the offset, give
     * up gracefully after ~2.5s by scrolling to whatever fits (a shorter render — Reader view
     * flipped, images off — clamps rather than lying). One-shot: the field zeroes on entry so a
     * later open never inherits a dead offset.
     */
    private fun replayArticleScroll() {
        val target = restoreScrollY
        restoreScrollY = 0
        if (target <= 0) return
        var tries = 0
        fun attempt() {
            if (!isAdded || currentArticle == null) return
            val web = binding.articleWeb
            val max = (web.contentHeight * web.scale).toInt() - web.height
            if (max >= target || tries >= 20) web.scrollTo(0, target.coerceAtMost(maxOf(0, max)))
            else { tries++; web.postDelayed({ attempt() }, 120) }
        }
        binding.articleWeb.postDelayed({ attempt() }, 120)
    }

    /** Current view: "feed" (unread RSS) · "stars" · "later" · "local" (no-server RSS). */
    private var mode: String = "feed"

    /** Slim-pane per-feed filter (null = unfiltered) — counts as "away from home" for the goto. */
    private var slimFeedFilter: String? = null
    /** The drawer CATEGORY the list is narrowed to (null = the whole lens) — the folder tap's
     *  half of the pick, beside [slimFeedFilter]'s feed. */
    private var slimCatFilter: String? = null
    /** The lens the feed/category pick was made under. THE FEED STAYS PUT (Michael 2026-10-11):
     *  ⟳, a date step and the midnight roll all re-render through [applyPicked], which honours
     *  the pick while this lens is still showing; a lens switch drops it ([dropStalePick]). */
    private var pickedLens: String? = null
    /** Optional read/watch/listen lens. */
    private var kindFilter: String? = null
    /**
     * Which Later List LANE is showing (null = the whole list), when `mode == "later"`.
     *
     * Its own field rather than reusing [kindFilter], even though three of the four lanes share
     * their names with the media lenses. A lane is where a link was FILED — a fact on disk, one
     * field of one day file — while [kindFilter] is a guess the row makes about itself from its
     * category and its URL. Folding them together would mean the 📧 Email lane (whose links are
     * mostly articles) either vanished from its own lens or dragged half The Read in with it.
     */
    private var laterLane: String? = null
    /** Sidebar search scope: "all" · "feed" (the selected feed) · "category" (the current lens). */
    private var searchScope = "all"
    /**
     * The Mail room's MAILBOX: null = 📥 Inbox (all accounts), an account id = that inbox, or one
     * account's ➤ Sent / 🚫 Junk / 🗑 Trash ([MailPlace.of]; Michael 2026-10-11: "individual sents,
     * junks, trash … a drop down under each account"); the every-account [MailPlace.SENT] / [MailPlace.TRASH]
     * still resolve for a door that hands them over. Only read in
     * The Mail's room ([isMailRoom], where [kindFilter] is always [KIND_MAIL]). Change it through
     * [setMailMailbox].
     * A narrowed inbox and its compose agree on which door mail goes out by HANDING the mailbox
     * to compose (MailComposeFragment.ARG_FROM_ACCOUNT, Michael 2026-10-11) — the old echo into
     * the `account_filter` setting went stale behind compose's back and is no longer written.
     */
    private var mailMailbox: String? = null

    /** Move the mailbox — see [mailMailbox]. */
    private fun setMailMailbox(box: String?) {
        mailMailbox = box
    }

    /** The account the mailbox on screen names, for compose's From and the server search — its
     *  Inbox or one of its piles (an account's ➤ Sent composes from that account); none over
     *  📥 Inbox or the every-account piles. (The server search never runs over a pile.) */
    private fun composeFrom(): String? = MailPlace.accountOf(mailMailbox)

    /** The persisted mail sort — read fresh on every gather, never cached in a field, so the
     *  dial, a settings restore and the pile can't disagree about the standing order. */
    private fun mailSort(): MailSort =
        MailSort.of(requireContext().getSharedPreferences("ledger_mail_inbox", 0).getString("mail_sort", null))

    /** Persist the dial. Its own key ("mail_sort") in the mail lens's prefs file, beside
     *  account_filter — a NEW key, so no older build ever reads it as something else. */
    private fun setMailSort(sort: MailSort) {
        requireContext().getSharedPreferences("ledger_mail_inbox", 0).edit()
            .putString("mail_sort", sort.id).apply()
    }
    /**
     * Synthetic row id → the letter it stands for — the same held-beside-the-list mapping
     * [bskyUriById] is, and for the same reason: [FeedEntry] is shared by every source on this
     * screen and must not widen for one source's key. Rebuilt on every [gatherMailRows].
     */
    private val mailById = HashMap<Long, InboxMessage>()
    /** Single-flight for the background IMAP pull — refresh() re-entry must not stack fetches. */
    private var mailRefreshing = false
    /** The account whose 🚫 Junk is being read right now (null = none) — its own single-flight, so
     *  an Inbox pull in flight never leaves a Junk visit stuck on "Asking…" (and vice versa). */
    private var junkFetching: String? = null
    /** ⟳ was tapped over 🚫 Junk: the next Junk load asks the server even if its last read is fresh. */
    private var junkReread = false
    /** The last mail row opened, so the list-dress ⏫⏬ can step email-to-email from where the
     *  reading left off rather than always from the top. */
    private var lastMailId: Long? = null
    /** The last piece opened in the pane — RSS's list « » step from it, as Mail's do from
     *  [lastMailId] (Michael 2026-10-11, one rail). */
    private var lastArticleId: Long? = null
    /**
     * The letter OPEN in the pane, held beside the map rather than only in it. [gatherMailRows]
     * rebuilds [mailById] from the shown corpus, and a background refresh can re-gather while a
     * letter is open — in the Unread view the just-opened (now read) message drops out of the
     * list, and without this the rebuild would un-map the letter out from under its own reader
     * (the pane fell back to the article renderer mid-read on device). Cleared with the pane.
     */
    private var openMail: Pair<Long, InboxMessage>? = null
    /** The thread cards under the open letter, by link key — a hash of the card's letter id, never
     *  a list position (critique P5) — and the letter a card was opened FROM, for the "‹ Back to …"
     *  line (critique P6: ⏫⏬ and Back still walk the list). Both belong to the open letter. */
    private val mailThreadLinks = HashMap<String, String>()
    private var mailThreadBack: InboxMessage? = null
    /** The LIST row that was open when the first thread card was tapped — ⏫⏬ step from it, not
     *  from the card's letter, so a detour into the conversation never loses your place in the
     *  triage (2026-10-10 review; a Sent card isn't even in the Inbox list). Null when no hop is
     *  under way; any open that isn't a card's clears it. */
    private var mailThreadOrigin: Long? = null
    /** The mail rows you answered, by row id — the adapter draws their ↩ (see
     *  [FeedEntryAdapter.answeredSource]); filled as [mailEntry] mints rows. */
    private val mailAnsweredRows = HashSet<Long>()
    /** A reply went out while the pane covered the list: repaint the rows (their ↩) when it
     *  closes. */
    private var mailRowsStale = false
    /** Whether the OPEN letter loads its remote images. Set per open from the Mail setting
     *  ("Load pictures in letters", ON by default — Michael, 09-11: "I want it to load and render
     *  for me"); the in-body "load images" door flips it on for a letter when the setting is off. */
    @Volatile private var mailImagesOn = false
    /** Whether the OPEN letter is read reflowed (Reader) or as its sender designed it (As sent).
     *  Set per open from the Mail setting ("Open letters as sent", ON by default — the rendered
     *  version first); the rail's Reader toggle flips it for the letter in hand. Its own flag,
     *  not [showParsed]: an article's Reader default is not a letter's. */
    private var mailParsed = false
    private fun mailPrefs(): SharedPreferences =
        requireContext().getSharedPreferences(MAIL_PREFS, android.content.Context.MODE_PRIVATE)
    /** The mail lens's live search text, held for the 🔎 server row — the row names the query it
     *  will run, and a slow answer landing for an OLD query checks this and stands down. */
    private var mailSearchQuery = ""

    /** The Mail pane's unread letters: every account's (📥 Inbox) and each account's (its row and
     *  its Inbox) — iOS MailPane's counts. */
    private data class MailCounts(val total: Int = 0, val byAccount: Map<String, Int> = emptyMap())
    private var mailCounts = MailCounts()
    private var mailRecount: kotlinx.coroutines.Job? = null

    /**
     * Count [mailCounts] afresh, off the main thread (the store can hold 5,000 letters), and redraw
     * the Mail pane only when a number moved. The Unread view's own rule ([gatherMailRows]): a
     * letter you sent, or one in 🚫 Junk, is never unread. Run by every pane draw, when the pane
     * comes back into view, and when a letter is read with the pane showing — so the numbers never
     * lag the letters for long.
     */
    private fun recountMail() {
        if (!isMailRoom) return
        val app = context?.applicationContext ?: return
        mailRecount?.cancel()
        mailRecount = lifecycleScope.launch {
            val next = withContext(Dispatchers.Default) {
                var total = 0
                val by = HashMap<String, Int>()
                val all = runCatching { InboxStore.messages(app) }.getOrDefault(emptyList())
                for (m in all) {
                    if (!isActive) break
                    if (InboxStore.isSent(m.id) || com.toolsboox.plugin.mail.JunkMail.isJunk(m.id) ||
                        InboxStore.isRead(app, m.id)) continue
                    total++
                    MailSync.accountId(m.id)?.let { by[it] = (by[it] ?: 0) + 1 }
                }
                MailCounts(total, by)
            }
            if (next != mailCounts) {
                mailCounts = next
                // Only onto a live view: the job can outlast onDestroyView (lifecycleScope is the
                // fragment's), and a stale binding's drawer is no one's. And never under a search
                // in progress — a redraw builds the pane's search field afresh, empty, while the
                // letters stay filtered (mark-read-on-scroll recounts as he scrolls); the new
                // numbers wait for the pane's next draw instead.
                if (isAdded && view != null && ::binding.isInitialized &&
                    binding.feedsDirectory.findFocus() !is android.widget.EditText &&
                    mailSearchQuery.isEmpty()) renderDirectory()
            }
        }
    }
    /** Single-flight for the server search — one IMAP round-trip at a time, like the refresh. */
    private var mailServerSearching = false
    /** Selected local subscription (null = all local) when mode == "local". */
    private var localSub: com.toolsboox.plugin.feeds.nw.LocalSub? = null
    /** The active smart feed (saved search) when mode == "smart". */
    private var smartFeed: com.toolsboox.plugin.feeds.nw.SmartFeed? = null
    /** Enabled only while an in-pane article is open (so Back returns to the list). */
    private var articleBackCallback: androidx.activity.OnBackPressedCallback? = null
    /** The article currently open in the in-pane reader (for star / note / parsed toggle). */
    private var currentArticle: FeedEntry? = null
    /** The pane is showing "Can't load this article right now." for [currentArticle] — its words are
     *  not the article's, so ❖ Synthesize this article says there is nothing to read yet rather than
     *  synthesizing the error (review 2026-10-12). Set by the pane's onReceivedError, cleared by
     *  every [renderArticle]. */
    private var articleLoadFailed = false
    /** Reader-view (parsed) vs original (unparsed) for the in-pane article. */
    private var showParsed: Boolean = true

    /** [showParsed] as it stood before a Log line reopened a piece in the view it was highlighted
     *  in — put back when that piece closes. */
    private var parsedBeforeLog: Boolean? = null

    /**
     * The open article's highlights and notes (Michael 2026-10-12): Highlight keeps a passage and
     * its one gram pick in one tap; a note joins that pick; the article's picks are drawn back on
     * it. One controller for this pane and the standalone reader ([com.toolsboox.plugin.feeds.ot.ArticleHighlights]).
     */
    private val articleHighlights: com.toolsboox.plugin.feeds.ot.ArticleHighlights by lazy {
        com.toolsboox.plugin.feeds.ot.ArticleHighlights(
            fragment = this, service = calendarDayService, root = { documentsRoot() },
            web = { if (::binding.isInitialized) binding.articleWeb else null },
            captureAv = { kind, sink -> captureAvGramDirect(kind, sink) },
            choosePicture = { sink -> captureGalleryDirect(sink) },
            star = { currentArticle?.let { e -> { toggleStar(e) } } },
            switchView = { view -> switchArticleView(view) },
        )
    }

    /** Marks are an ARTICLE's — a web piece with an address; not a letter, not a skinned YouTube
     *  page (whose text is the app's own). */
    private fun marksOn(e: FeedEntry): Boolean =
        !mailById.containsKey(e.id) && e.url.startsWith("http", ignoreCase = true) &&
            !(e.kind == "watch" && e.youTubeId != null)

    /** "Not on this version" → the view the highlight was made in, one tap (critique #7). */
    private fun switchArticleView(view: String) {
        val e = currentArticle ?: return
        when (view) {
            com.toolsboox.plugin.reader.ui.BookNote.VIEW_READER -> { showParsed = true; renderArticle(); redressRail() }
            com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED -> { showParsed = false; renderArticle(); redressRail() }
            else -> e.url.takeIf { it.startsWith("http", ignoreCase = true) }?.let {
                articleHighlights.setView(com.toolsboox.plugin.reader.ui.BookNote.VIEW_LIVE)
                paintArticleGround(false)
                binding.articleWeb.loadUrl(it)
            }
        }
    }

    /**
     * Synthetic row id → the post's AT URI, for the Bluesky source.
     *
     * The URI does not go on [FeedEntry] and should not: that model is shared by every source in
     * this screen, and widening it for one source's key is how a lean data class becomes a union of
     * everything anyone ever needed. It also could not ride in `url`, which has to stay the
     * bsky.app permalink so "open externally" and the long-press both work. So the mapping is held
     * beside the list, rebuilt whenever the timeline is loaded.
     */
    private val bskyUriById = HashMap<Long, String>()

    /**
     * Posts read but not yet reported to the site.
     *
     * Batched because the server's contract says to batch: one request per row as the list scrolls
     * would be a cellular round trip per post, which on a Boox costs both time and battery. Flushed
     * when the batch is big enough to be worth a request, and again on the way out of the screen —
     * whichever comes first. Kept (not cleared) when a flush fails, because a silently-dropped
     * batch means those posts come back unread on the next refresh and he reads them twice.
     */
    private val pendingBskySeen = LinkedHashSet<String>()

    /** OPML file picker (parity with the iPad's fileImporter). */
    private val opmlPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use {
                    com.toolsboox.plugin.feeds.nw.LocalFeedStore.importOpml(
                        requireContext(), com.toolsboox.plugin.feeds.nw.RssParser.opmlUrls(it))
                } ?: 0
            }
            showMessage("Imported $n feed(s)", binding.root)
            renderDirectory()
        }
    }

    /** Aa Type's "Import font…" (Michael, 2026-10-11) — registered at construction like every
     *  launcher; the result goes to the open panel, which chooses the new face at once. */
    private val importFont = com.toolsboox.ot.ReadingFonts.importLauncher(this) { r ->
        aaPanel?.takeIf { it.isShowing }?.onImported(r)
            ?: r.onSuccess { showMessage("Font added · ${it.label}") }
                .onFailure { showMessage(it.message ?: "Couldn't add that font") }
    }
    private var aaPanel: com.toolsboox.ui.plugin.AaTypePanel? = null

    /**
     * Aa Type, opened on the ARTICLE profile (Michael, 2026-10-11: "switching font styles in
     * readers both book and rss isn't as robust as I want"). Every change reaches the open piece
     * live — no reload, the scroll stays put — debounced to one re-lay per run of taps (e-ink).
     */
    private fun openAaType() {
        aaPanel = com.toolsboox.ui.plugin.AaTypePanel(
            this, com.toolsboox.ot.ReadingType.Profile.ARTICLE, importFont,
            onChange = { p -> if (p == com.toolsboox.ot.ReadingType.Profile.ARTICLE) scheduleArticleType() },
        ).also { it.show() }
    }

    private val applyArticleTypeLater = Runnable {
        if (isAdded && getView() != null && ::binding.isInitialized) {
            if (articleBuilt) paintArticleGround(true)
            binding.articleWeb.evaluateJavascript(com.toolsboox.ot.ReadingTypes.articleLiveJs(requireContext()), null)
        }
    }

    private fun scheduleArticleType() {
        if (!::binding.isInitialized) return
        binding.articleWeb.removeCallbacks(applyArticleTypeLater)
        binding.articleWeb.postDelayed(applyArticleTypeLater, com.toolsboox.ot.AaType.LIVE_DEBOUNCE_MS)
    }

    /** Whether the pane shows an article the app built (it carries the type block) — not a live
     *  web page, a letter or the episode page, which keep the WebView's plain white ground. */
    private var articleBuilt = false
    private var paintedGround: Int? = null

    /**
     * The pane WebView's own ground: the article's page colour for an article the app built, else
     * white. A WebView paints its ground between documents, so every article opened on a Black
     * page flashed white, then black — two e-ink refreshes (review, Michael 2026-10-11).
     */
    private fun paintArticleGround(built: Boolean) {
        articleBuilt = built
        val c = if (built) com.toolsboox.ot.ReadingTypes.articleGround(requireContext()) else android.graphics.Color.WHITE
        if (paintedGround != c) { paintedGround = c; binding.articleWeb.setBackgroundColor(c) }
    }

    private fun applyKind(entries: List<FeedEntry>): List<FeedEntry> {
        val k = kindFilter
        // The Mail is a CORPUS lens, not a sieve: with it active, allEntries already holds only
        // mail rows (gatherMailRows built them), and FeedEntry.kind — a guess made from category
        // and URL — would call every one of them "read" and empty the list. Same pass-through the
        // Later List earns for the same reason: the narrowing happened where the rows were made.
        if (k == KIND_MAIL) return entries
        // The other direction is NOT a pass-through: a letter's home is The Mail lens, full stop.
        // A lens switch can show the standing corpus before its reload lands, and mail rows that
        // survived that beat were "the mail is getting pulled into The Feed" (08-16 page) — with
        // the mail rail's letter verbs then pointing at no open letter. A mail:// row never
        // belongs to any other view, so the sieve says so instead of trusting the corpus.
        val cleaned = entries.filterNot { it.url.startsWith("mail://") }
        val sieved = if (k == null) cleaned else cleaned.filter { it.kind == k }
        // THE SHELF (Michael, 09-19: "Should android" — yes): The Listen opens on what is started
        // and not done across your devices, newest playhead first, read straight off the synced
        // listen state exactly as the iPhone's sheet does. Rows are synthetic (negative ids in
        // their own range) so nothing here ever reaches Miniflux.
        return if (k == "listen") shelfRows() + sieved else sieved
    }

    /** Narrow to the feed or category picked in the drawer — pass-through when nothing is picked
     *  or the pick belongs to another lens. Every re-render of the list goes through here, so the
     *  pick survives ⟳, date steps and midnight instead of being a one-off submit. */
    private fun applyPicked(entries: List<FeedEntry>): List<FeedEntry> {
        if (kindFilter == KIND_MAIL || kindFilter != pickedLens) return entries
        slimFeedFilter?.let { f -> return entries.filter { it.feedTitle == f } }
        slimCatFilter?.let { c ->
            return entries.filter {
                it.categoryLabel == c ||
                    (c == UNCATEGORISED && it.categoryLabel.isNullOrBlank() && it.feedTitle.isNotBlank())
            }
        }
        return entries
    }

    /** Remember a drawer pick against the lens it was made under (see [pickedLens]). */
    private fun pickFeed(category: String?, feed: String?) {
        slimCatFilter = category; slimFeedFilter = feed; pickedLens = kindFilter
        rememberPlace(); updateWindowLabel()
    }

    private fun clearPick() {
        slimCatFilter = null; slimFeedFilter = null; pickedLens = null
        rememberPlace(); updateWindowLabel()
    }

    /** The feed or category narrowing the list right now, by name — null when [applyPicked]
     *  would pass everything through. */
    private fun pickShowing(): String? {
        if (kindFilter == KIND_MAIL || kindFilter != pickedLens) return null
        return slimFeedFilter ?: slimCatFilter
    }

    /**
     * THE MAILBOX ON SCREEN, BY NAME, when it isn't 📥 Inbox — ⭐ Starred, ➤ Sent, 🗑 Trash or one
     * account (2026-10-11 review: a Trash row looks exactly like an Inbox row, and a remembered
     * Sent or account landed from the Hub, Sections or the widget with nothing saying so). The
     * window line wears it with a ✕; the tap goes back to 📥 Inbox ([leaveMailboxForInbox]).
     */
    private fun mailShowing(): String? {
        if (!isMailRoom || kindFilter != KIND_MAIL) return null
        val w = MailPlace.where(mailMailbox)
        val accounts = com.toolsboox.plugin.mail.MailAccountStore.all(requireContext())
        // The drawer's own row name for the account ("@  name", told apart when two share it).
        val label = w.account?.let { id ->
            MailPlace.AccountLabel.all(accounts.map { Triple(it.id, it.display, it.email) })[id]
        }
        return when {
            // One account's pile names both: "➤  Sent · gmail" (its rows live under that account).
            w.box != MailPlace.Box.INBOX && w.account != null ->
                w.box.label() + (label?.let { " · ${it.removePrefix("@").trim()}" } ?: "")
            // Every account's ➤ Sent or 🗑 Trash, as its drawer row names it.
            w.box != MailPlace.Box.INBOX -> w.box.label(accounts.size)
            w.account != null -> label
            mode == "stars" -> MailPlace.Box.STARRED.label()
            else -> null
        }
    }

    /** Back to 📥 Inbox at your Unread / Read / All — the mailbox line's ✕. */
    private fun leaveMailboxForInbox() {
        setMailMailbox(null)
        if (mode !in MailPlace.VIEWS) mode = placeView
        renderDirectory(); refresh()
    }

    // ── THE REMEMBERED PLACE (Michael 2026-10-11, Q1: "going back to where you were on incoming
    // should be easy") — see [IncomingPlace]. The fields above ARE the place while the screen is
    // up; these move it in and out of ledger_incoming.

    /** This instance has taken its arrival — after that its own fields are the place, and a
     *  trip's system-Back return (the same instance) keeps them rather than re-reading prefs. */
    private var placeArrived = false
    /** The standing Unread / Read / All — what a lens door lands on; moved only by 👁, ☀ Today and
     *  an arrival (see [rememberPlace]). */
    private var placeView = IncomingPlace.FEED
    /** The last place written, so a ⟳ that moved nothing doesn't touch the disk. */
    private var placeSaved: IncomingPlace? = null

    private fun placePrefs(): SharedPreferences =
        requireContext().getSharedPreferences(IncomingPlace.PREFS, android.content.Context.MODE_PRIVATE)

    private fun mailPlacePrefs(): SharedPreferences =
        requireContext().getSharedPreferences(MailPlace.PREFS, android.content.Context.MODE_PRIVATE)
    /** The last Mail place written (see [placeSaved]). */
    private var mailPlaceSaved: MailPlace? = null

    private fun currentPlace() = IncomingPlace(
        mode = mode, kind = kindFilter, view = placeView, laterLane = laterLane,
        category = slimCatFilter, feed = slimFeedFilter, pickedLens = pickedLens,
        smartId = smartFeed?.id, localSubId = localSub?.id,
    )

    private fun currentMailPlace() = MailPlace(mode = mode, view = placeView, mailbox = mailMailbox)

    /** Stand The Mail's room in [p] — always the mail lens. A mailbox whose account has since
     *  been removed (its Inbox or one of its piles) falls back to 📥 Inbox (every account) rather
     *  than an empty list ([MailPlace.standing]). (Compose
     *  no longer hears the mailbox through a setting — ✉ hands it over.) */
    private fun applyMailPlace(p: MailPlace) {
        val ctx = requireContext()
        mode = p.mode; kindFilter = KIND_MAIL; placeView = p.view; laterLane = null
        val box = MailPlace.standing(p.mailbox, com.toolsboox.plugin.mail.MailAccountStore.all(ctx).map { it.id })
        if (mailMailbox != box) setMailMailbox(box)
        slimCatFilter = null; slimFeedFilter = null; pickedLens = null; smartFeed = null; localSub = null
        updateWindowLabel()
    }

    /** Stand Feeds in [p]. A smart feed or local subscription that has since been removed falls
     *  back (the view; all local feeds) rather than standing in an empty list. */
    private fun applyPlace(p: IncomingPlace) {
        val ctx = requireContext()
        mode = p.mode; kindFilter = p.kind; laterLane = p.laterLane; placeView = p.view
        slimCatFilter = p.category; slimFeedFilter = p.feed; pickedLens = p.pickedLens
        smartFeed = if (p.mode == "smart")
            com.toolsboox.plugin.feeds.nw.SmartFeedStore.all(ctx).firstOrNull { it.id == p.smartId } else null
        if (p.mode == "smart" && smartFeed == null) mode = p.view
        localSub = if (p.mode == "local") p.localSubId?.let { id ->
            com.toolsboox.plugin.feeds.nw.LocalFeedStore.subscriptions(ctx).firstOrNull { it.id == id }
        } else null
        updateWindowLabel()
    }

    /** Write the place on screen — every lens, view, mailbox, lane and pick change lands here
     *  (refresh() and the pick setters). Moments that aren't places leave the last one standing.
     *  The standing view is NOT learned from the screen (2026-10-11 review): a drawer lens tap's
     *  All, the Later fold's All and a past date's Read are side effects, and one 📖 tap used to
     *  make every later lens door open All — only 👁 and ☀ move it ([placeView]). */
    private fun rememberPlace() {
        if (!placeArrived || !isAdded) return
        if (isMailRoom) {
            // The Mail keeps its own place — the mailbox and view (⭐ Starred is a moment, 🗑 Trash
            // a visit: both leave the last place standing).
            val m = MailPlace.capture(currentMailPlace()) ?: return
            if (m == mailPlaceSaved) return
            MailPlace.save(mailPlacePrefs(), m)
            mailPlaceSaved = m
            return
        }
        // A mail lens never stands in Feeds (a stray one is The Mail's, not a Feeds place).
        if (kindFilter == KIND_MAIL) return
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        // A past window shows Unread as Read (switchTo's coercion); that Read is the date's, not
        // a place — kept like an edition, at the standing view.
        val now = currentPlace().let {
            if (!liveToday && it.mode == "read" && placeView != "read") it.copy(mode = "edition") else it
        }
        val p = IncomingPlace.capture(now) ?: return
        if (p == placeSaved) return
        IncomingPlace.save(placePrefs(), p)
        placeSaved = p
    }

    /** A pick only means something inside the lens and the view family it was made in — the
     *  date-stepped views (feed/read/both/edition/stars) keep it, a lens or corpus switch drops
     *  it, so a feed picked in The Read never empties The Listen. */
    private fun dropStalePick() {
        if (slimFeedFilter == null && slimCatFilter == null) return
        if (kindFilter != pickedLens || kindFilter == KIND_MAIL ||
            mode !in setOf("feed", "read", "both", "edition", "stars")) clearPick()
    }

    /** The in-progress shelf's rows: one synthetic entry per synced playhead, tap = the episode's
     *  page with ▶ Play (never autoplay), hold = the shelf's own doors (see [showShelfMenu]). */
    private fun shelfRows(): List<FeedEntry> {
        val ctx = context ?: return emptyList()
        val states = runCatching { com.toolsboox.plugin.calendar.ot.ListenStateStore.loadAll(ctx) }.getOrDefault(mutableListOf())
            .filter { !it.isDeleted && !it.done && it.positionMs > SHELF_RESUME_MIN_MS }
            .sortedByDescending { it.updated }
        return states.map { st ->
            val mins = if (st.durationMs > 0) maxOf(1L, (st.durationMs - st.positionMs) / 60_000L) else 0L
            val sub = listOf(st.show, if (mins > 0) "$mins min left" else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (st.show.isNotBlank()) shelfShows[shelfId(st.id)] = st.show
            FeedEntry(
                id = shelfId(st.id), title = st.displayTitle, feedTitle = sub, url = st.id,
                author = null, content = "", starred = false, read = true,
                publishedAt = java.time.Instant.ofEpochMilli(st.updated).toString(),
                category = "🎧 $SHELF_LABEL",
                enclosureImage = st.artworkUrl.ifBlank { null },
                enclosureAudio = st.audioUrl.ifBlank { null })
        }
    }

    private fun shelfId(stateId: String): Long = SHELF_ID_BASE - (stateId.hashCode().toLong() and 0x7fffffffL)
    private fun isShelfRow(e: FeedEntry): Boolean = e.id <= SHELF_ID_BASE && e.id > SHELF_ID_BASE - 0x80000000L

    /** Shelf row id → its show (for the row's cover), written as the shelf is built. */
    private val shelfShows = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * The episode a synced playhead names, as an entry that can be opened or played: the loaded
     * list's own row when it is there, else one made from the record itself — its title, its
     * show, its picture, its file. (The stand-in used to drop the show and the art, so playing
     * it left the transport and the synced record without either.)
     */
    private fun listenEntryFor(rid: String, st: com.toolsboox.plugin.calendar.da.v2.ListenState?): FeedEntry {
        val existing = adapter.current().ifEmpty { FeedSelection.list }
            .firstOrNull { !isShelfRow(it) && (it.url == rid || it.mediaUrl == rid) }
            ?: allEntries.firstOrNull { !isShelfRow(it) && (it.url == rid || it.mediaUrl == rid) }
        return existing ?: standInFor(requireContext().applicationContext, rid, st)
    }

    /** [listenEntryFor]'s stand-in, made from the synced record alone — no fragment state, so the
     *  headless up-next chain can build one with the screen long gone. */
    private fun standInFor(ctx: android.content.Context, rid: String,
                           st: com.toolsboox.plugin.calendar.da.v2.ListenState?): FeedEntry {
        val show = st?.show.orEmpty()
        return FeedEntry(
            id = -1L, title = st?.displayTitle
                ?: com.toolsboox.plugin.calendar.da.v2.ListenState.humanize(rid),
            feedTitle = show, url = rid, author = null, content = "",
            publishedAt = java.time.OffsetDateTime.now().toString(), starred = false,
            read = true, category = "🎧 Listen",
            enclosureImage = st?.artworkUrl?.takeIf { it.startsWith("http") }
                ?: com.toolsboox.plugin.feeds.nw.ShowCovers.lookup(ctx, null, show.ifBlank { null }),
            enclosureAudio = st?.audioUrl?.ifBlank { rid } ?: rid)
    }

    /**
     * ▶ on a Listen row's picture (10-12). A feed row plays as the episode page's ▶ Play does. An
     * in-progress shelf row resolves to its episode first — the shelf row's own feed line is
     * "show · N min left", which the transport would otherwise have filed as the show's name —
     * and an Audiobookshelf track resumes through its own player, as ⏯ Continue does.
     */
    private fun playFromRow(e: FeedEntry) {
        if (!isShelfRow(e)) { playEntryAudio(e); return }
        val ctx = requireContext().applicationContext
        if (e.url.startsWith("abs:")) {
            Thread {
                runCatching { com.toolsboox.plugin.reader.ui.AbsPlayback.resume(ctx, e.url) }
                    .onFailure { err -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(ctx, err.message ?: "Couldn't continue it.", android.widget.Toast.LENGTH_LONG).show() } }
            }.apply { isDaemon = true }.start()
            return
        }
        playEntryAudio(listenEntryFor(e.url, com.toolsboox.plugin.calendar.ot.ListenStateStore.get(ctx, e.url)))
    }

    /** The shelf's doors: off the shelf everywhere (tombstoned on the synced state), heard, or
     *  the held file let go — the iPhone's swipe/hold set. */
    private fun showShelfMenu(entry: FeedEntry) {
        val ctx = requireContext().applicationContext
        val store = com.toolsboox.plugin.calendar.ot.ListenStateStore
        val audio = entry.enclosureAudio.orEmpty()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "🗑  Remove from this list — every device" to {
            store.tombstone(ctx, entry.url); adapter.submit(applyKind(allEntries))
            showMessage("Removed · ${entry.title.take(50)}", binding.root)
        }
        items += "✓  Mark as heard" to {
            store.markDone(ctx, entry.url); adapter.submit(applyKind(allEntries))
            showMessage("Heard · ${entry.title.take(50)}", binding.root)
        }
        if (audio.isNotBlank() && com.toolsboox.plugin.feeds.nw.LaterMedia.isDownloaded(ctx, audio)) {
            items += "🗑  Remove download" to {
                com.toolsboox.plugin.feeds.nw.LaterMedia.remove(ctx, audio, dismiss = true)
                if (store.get(ctx, entry.url)?.wantOffline == true) store.setWantOffline(ctx, entry.url, false)
                showMessage("Download removed · ${entry.title.take(50)}", binding.root)
            }
        }
        showIconMenu(entry.title.take(60), items)
    }

    private fun switchToLocal(sub: com.toolsboox.plugin.feeds.nw.LocalSub?) {
        if (isMailRoom) { Timber.w("mail room: local feed refused"); return }
        mode = "local"; localSub = sub; kindFilter = null; refresh()
    }

    private fun loadLocal() {
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.feeds.nw.LocalFeedStore.entries(requireContext(), localSub)
            }
            if (finishLoad()) return@launch
            allEntries = entries
            corpusKey = "local"
            adapter.submit(entries)
            if (entries.isEmpty()) showEmpty("No local feed items yet.") else binding.emptyText.visibility = View.GONE
            renderDirectory()
        }
    }

    /** 🔍 FIND A SHOW (the AntennaPod lift's discovery half): search the open podcast index by
     *  name, tap a result, subscribed — into Miniflux when the server is configured (so the show
     *  reaches every device), else into Local Feeds. One dialog in, one list out, no browse
     *  taxonomy: you know the show you want, the door finds its feed. */
    private fun showFindShow() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Show name…"; isSingleLine = true }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("🔍  Find a show")
            .setView(android.widget.FrameLayout(ctx).apply {
                setPadding(48, 8, 48, 0); addView(input)
            })
            .setNeutralButton("By interest\u2026") { _, _ -> showPodcastInterests() }
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) {
                        runCatching {
                            // The iTunes search API: the same open index AntennaPod leans on —
                            // no key, no account, plain JSON over GET.
                            val enc = java.net.URLEncoder.encode(q, "UTF-8")
                            val body = java.net.URL(
                                "https://itunes.apple.com/search?media=podcast&limit=20&term=$enc"
                            ).readText()
                            val arr = org.json.JSONObject(body).optJSONArray("results")
                                ?: return@runCatching emptyList()
                            (0 until arr.length()).mapNotNull { i ->
                                val o = arr.getJSONObject(i)
                                val feed = o.optString("feedUrl")
                                if (feed.isBlank()) null else PodHit(
                                    o.optString("collectionName").ifBlank { feed },
                                    o.optString("artistName"),
                                    o.optString("artworkUrl100"), feed)
                            }
                        }.getOrDefault(emptyList())
                    }
                    if (!isAdded) return@launch
                    if (found.isEmpty()) { showMessage("No shows found for \u201c$q\u201d", binding.root); return@launch }
                    showPodcastResults("Shows \u00b7 tap to subscribe", found)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One found show: what the covered result rows draw and what a tap subscribes to. */
    private data class PodHit(val name: String, val by: String, val art: String, val feed: String)

    /** Results WITH FACES (09-03: "make the ability to find a podcast include the image and
     *  stuff") — a 56dp cover beside each name, fetched off-main and dropped in as they land. */
    private fun showPodcastResults(title: String, hits: List<PodHit>) {
        val ctx = context ?: return
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val list = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        for (h in hits) {
            val rowIcon = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(56))
                    .apply { marginEnd = dp(10) }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(rowIcon)
                addView(android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(android.widget.TextView(ctx).apply {
                        text = h.name; textSize = 15.5f; setTextColor(0xFF000000.toInt())
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    if (h.by.isNotBlank()) addView(android.widget.TextView(ctx).apply {
                        text = h.by; textSize = 12.5f; setTextColor(0xFF666666.toInt()); maxLines = 1
                    })
                })
                setOnClickListener { dlg.dismiss(); subscribeToShow(h.feed, h.name) }
            }
            list.addView(row)
            if (h.art.isNotBlank()) lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        java.net.URL(h.art).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
                if (isAdded && bmp != null) rowIcon.setImageBitmap(bmp)
            }
        }
        dlg.show()
    }

    /** Browse by interest — Apple's podcast genres, each opening that interest's top shows.
     *  A tap looks the show's feed up (the charts don't carry it) and subscribes. */
    private fun showPodcastInterests() {
        val ctx = context ?: return
        val genres = listOf(
            "Arts" to 1301, "Business" to 1321, "Comedy" to 1303, "Education" to 1304,
            "Fiction" to 1483, "Health & Fitness" to 1512, "History" to 1487,
            "Kids & Family" to 1305, "Music" to 1310, "News" to 1489, "Religion & Spirituality" to 1314,
            "Science" to 1533, "Society & Culture" to 1324, "Sports" to 1545,
            "Technology" to 1318, "True Crime" to 1488, "TV & Film" to 1309)
        // Just the variety, alphabetical (09-03 rulings: "i liked the variety you picked" /
        // "I don't want #mysore etc" — the note-tags aisle came from the wrong drawer and is
        // gone; the genre shelf is the shelf).
        val rows = genres.map { (gname, gid) -> gname to { browseInterest(gname, gid) } }
            .sortedBy { it.first.lowercase() }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("🎙  By interest")
            .setItems(rows.map { it.first }.toTypedArray()) { _, i -> rows[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One subject, searched — the finder's own search path, seeded. */
    private fun searchShows(q: String) {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val enc = java.net.URLEncoder.encode(q, "UTF-8")
                    val body = java.net.URL(
                        "https://itunes.apple.com/search?media=podcast&limit=20&term=$enc").readText()
                    val arr = org.json.JSONObject(body).optJSONArray("results")
                        ?: return@runCatching emptyList()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        val feed = o.optString("feedUrl")
                        if (feed.isBlank()) null else PodHit(
                            o.optString("collectionName").ifBlank { feed },
                            o.optString("artistName"), o.optString("artworkUrl100"), feed)
                    }
                }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            if (found.isEmpty()) showMessage("No shows for #$q", binding.root)
            else showPodcastResults("🎙  #$q", found)
        }
    }

    private fun browseInterest(gname: String, gid: Int) {
        run {
                lifecycleScope.launch {
                    showMessage("$gname \u00b7 top shows\u2026", binding.root)
                    val hits = withContext(Dispatchers.IO) {
                        runCatching {
                            val body = java.net.URL(
                                "https://itunes.apple.com/us/rss/toppodcasts/genre=$gid/limit=25/json").readText()
                            val entries = org.json.JSONObject(body).getJSONObject("feed").optJSONArray("entry")
                                ?: return@runCatching emptyList()
                            (0 until entries.length()).mapNotNull { j ->
                                val e = entries.getJSONObject(j)
                                val id = e.optJSONObject("id")?.optJSONObject("attributes")
                                    ?.optString("im:id").orEmpty()
                                if (id.isBlank()) return@mapNotNull null
                                val imgs = e.optJSONArray("im:image")
                                val art = if (imgs != null && imgs.length() > 0)
                                    imgs.getJSONObject(imgs.length() - 1).optString("label") else ""
                                PodHit(
                                    e.optJSONObject("im:name")?.optString("label").orEmpty(),
                                    e.optJSONObject("im:artist")?.optString("label").orEmpty(),
                                    art, "lookup:$id")
                            }
                        }.getOrDefault(emptyList())
                    }
                    if (!isAdded) return@launch
                    if (hits.isEmpty()) { showMessage("$gname came back empty.", binding.root); return@launch }
                    showPodcastResults("🎙  $gname", hits)
                }
        }
    }

    /** Subscribe to [feedUrl]: through Miniflux when configured (every device sees it), else
     *  Local Feeds. The toast names where it landed, so the two backends never blur. */
    private fun subscribeToShow(feedUrl: String, name: String) {
        val ctx = requireContext()
        if (feedUrl.startsWith("lookup:")) {
            // A charts row: the RSS carries no feed URL — one lookup fetches it, then the
            // ordinary subscribe path takes over.
            val id = feedUrl.removePrefix("lookup:")
            lifecycleScope.launch {
                val real = withContext(Dispatchers.IO) {
                    runCatching {
                        val body = java.net.URL("https://itunes.apple.com/lookup?id=$id").readText()
                        org.json.JSONObject(body).optJSONArray("results")
                            ?.optJSONObject(0)?.optString("feedUrl").orEmpty()
                    }.getOrDefault("")
                }
                if (!isAdded) return@launch
                if (real.isBlank()) showMessage("Couldn't find $name's feed", binding.root)
                else subscribeToShow(real, name)
            }
            return
        }
        val (base, token) = com.toolsboox.plugin.feeds.nw.FeedPrefs.miniflux(ctx)
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) {
                if (base.isNotBlank() && token.isNotBlank()) {
                    val res = runCatching {
                        com.toolsboox.plugin.feeds.nw.MinifluxClient().createFeed(base, token, feedUrl, null)
                    }.getOrNull()
                    if (res is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok) "your feeds \u2014 every device"
                    else null
                } else {
                    if (com.toolsboox.plugin.feeds.nw.LocalFeedStore.add(ctx.applicationContext, feedUrl) != null)
                        "Local Feeds \u2014 this device" else null
                }
            }
            if (!isAdded) return@launch
            showMessage(
                if (where != null) "\u2713 $name \u2192 $where" else "Couldn't subscribe to $name",
                binding.root)
            if (where != null) renderDirectory()
        }
    }

    private fun showAddLocalFeed() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Feed URL — or any YouTube channel/video link"; setSingleLine(); setText("https://")
        }
        // Guarded: a pasted URL is work — a stray touch outside must not throw it away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Add feed")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.length > 8) lifecycleScope.launch { addFeedByUrl(url) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** One paste box, two doors: a YouTube link resolves to the channel's real RSS feed and
     *  subscribes properly (Miniflux when configured, under The Watch); anything else is a
     *  plain local-feed add, exactly as before. */
    private suspend fun addFeedByUrl(url: String) {
        val yt = com.toolsboox.plugin.feeds.nw.YouTubeResolver
        if (yt.isYouTube(url) && !yt.isYouTubeFeed(url)) { addYouTubeFeed(url); return }
        val sub = withContext(Dispatchers.IO) {
            com.toolsboox.plugin.feeds.nw.LocalFeedStore.add(requireContext(), url)
        }
        if (sub == null) showMessage("Not a valid feed", binding.root) else switchToLocal(sub)
    }

    /**
     * Paste a YouTube link — a video, a @handle, a /channel/ page — and it becomes a channel
     * subscription: dig the channel id out of the page, build the channel's RSS URL, and
     * subscribe. With Miniflux configured the feed is created SERVER-SIDE and landed in the
     * 📺 category so its entries wear The Watch lens on every device; with no server it falls
     * back to a local sub, same as any other feed URL.
     */
    private suspend fun addYouTubeFeed(url: String) {
        binding.progress.visibility = View.VISIBLE
        val channel = withContext(Dispatchers.IO) {
            com.toolsboox.plugin.feeds.nw.YouTubeResolver.resolve(url)
        }
        binding.progress.visibility = View.INVISIBLE
        if (channel == null) {
            showMessage("Couldn't find a channel in that YouTube link", binding.root)
            return
        }
        val p = prefs()
        val base = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (base.isBlank() || token.isBlank()) {
            // No server — the channel still arrives, as a local feed.
            val sub = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.feeds.nw.LocalFeedStore.add(requireContext(), channel.rssUrl)
            }
            if (sub == null) showMessage("Couldn't load the channel feed", binding.root)
            else { showMessage("Subscribed to ${channel.title}", binding.root); switchToLocal(sub) }
            return
        }
        val result = withContext(Dispatchers.IO) {
            // Land it under the 📺 lens: the first existing 📺-prefixed category (the shared
            // emoji grammar that makes kind == "watch"), or a fresh "📺 The Watch" when none.
            val watchCat = when (val cats = miniflux.categories(base, token)) {
                is MinifluxClient.Result.Ok ->
                    cats.value.firstOrNull { it.second.trim().startsWith("📺") }?.first
                        ?: (miniflux.createCategory(base, token, "📺 The Watch") as? MinifluxClient.Result.Ok)?.value
                is MinifluxClient.Result.Err -> null
            }
            miniflux.createFeed(base, token, channel.rssUrl, watchCat)
        }
        when (result) {
            is MinifluxClient.Result.Ok -> {
                showMessage("Subscribed to ${channel.title} · The Watch", binding.root)
                refresh()
            }
            is MinifluxClient.Result.Err ->
                showMessage("Couldn't subscribe — ${result.message}", binding.root)
        }
    }

    private fun exportOpml() {
        runCatching {
            val opml = com.toolsboox.plugin.feeds.nw.LocalFeedStore.exportOpml(requireContext())
            val dir = java.io.File(requireContext().cacheDir, "cards").apply { mkdirs() }
            val file = java.io.File(dir, "ledger-feeds.opml")
            file.writeText(opml, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file)
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/xml")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export OPML"))
        }.onFailure { showMessage("Export failed", binding.root) }
    }

    /** The list search's remembered words — they survive a feed/lens switch and a reload now,
     *  instead of silently dying with either ("text input for search in the field gets removed
     *  when changing feeds", 08-17 page). Cleared only by the dialog's own Clear. */
    private var listSearchQuery = ""

    /**
     * The list's search field TUCKS AWAY on a down-scroll and returns on an up-scroll or at the top
     * (Michael, 09-11, iOS: "it occupies too much real estate" — same rule here). A typed or
     * focused search stays put. `searchTuckable` is false while another pane owns the field.
     */
    private var searchTucked = false
    private fun tuckSearchOnScroll(rv: androidx.recyclerview.widget.RecyclerView, dy: Int) {
        if (!listSearchSummoned()) return   // summoned only — never tucks in and out under a page step
        val field = binding.feedsListSearch
        if (field.hasFocus() || field.text.isNotEmpty()) return
        if (!searchAllowedNow) return
        val first = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
        val tuck = when {
            first <= 0 -> false
            dy > 12 -> true
            dy < -12 -> false
            else -> searchTucked
        }
        if (tuck != searchTucked) {
            searchTucked = tuck
            field.visibility = if (tuck) View.GONE else View.VISIBLE
        }
    }
    /** Whether the search field belongs on screen at all right now (the panes that hide it set this). */
    private var searchAllowedNow = true

    /**
     * SUMMONED, NOT STANDING (Michael, 09-14, the Palma: "my up/down pagination buttons get stuck
     * due to the search feed at the top of the feeds… we had a mag glass on the rail to pop it on
     * and off, especially since there is the search-all on the left in the RSS popover"). The
     * field tucked in and out under every page step and the list jumped by its height each time.
     * Now it is off until the rail's 🔍 summons it, and it stays until 🔍 puts it away.
     */
    private fun listSearchSummoned(): Boolean = prefs().getBoolean(KEY_LIST_SEARCH, false)

    /** Where the list stood before Enter sent it to the whole server — restored when the field clears. */
    private var modeBeforeListSearch: Pair<String, String?>? = null

    /** 👁 Unread → Read → Everything, from the rail (09-14) — the scope the list search runs in. */
    private fun cycleReadScope() {
        val next = when (mode) { "feed" -> "read"; "read" -> "both"; else -> "feed" }
        // 👁 is the deliberate Unread / Read / All — the one the lens doors land on (Michael
        // 2026-10-11, the remembered place). From ⭐ Starred it is the way back to Unread.
        placeView = next
        showMessage(when (next) { "read" -> "Showing what you've read"; "both" -> "Showing everything — read and unread"; else -> "Showing unread" }, binding.root)
        switchTo(next, kindFilter)
    }

    /** Show's hold: the view picked outright — Unread · Read · All · Starred (iOS's lens picker). */
    private fun showReadScopePicker() {
        val ctx = context ?: return
        val rows = listOf("Unread" to "feed", "Read" to "read", "All" to "both", "Starred" to "stars")
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Show")
            .setItems(rows.map { (label, m) -> (if (m == mode) "✓  " else "    ") + label }.toTypedArray()) { _, w ->
                val next = rows[w].second
                if (next == mode) return@setItems
                // The deliberate Unread / Read / All is the remembered place (cycleReadScope's rule);
                // Starred is a visit, not a place.
                if (next != "stars") placeView = next
                switchTo(next, kindFilter)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleListSearch() {
        val on = !listSearchSummoned()
        prefs().edit().putBoolean(KEY_LIST_SEARCH, on).apply()
        val field = binding.feedsListSearch
        if (on) {
            if (searchAllowedNow) {
                field.visibility = View.VISIBLE; searchTucked = false
                field.requestFocus()
                (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            if (field.text.isNotEmpty()) field.setText("")   // the watcher brings the whole list back
            field.clearFocus()
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(field.windowToken, 0)
            field.visibility = View.GONE
        }
        redressRail()
    }
    private val listSearchDebounce = android.os.Handler(android.os.Looper.getMainLooper())

    /** The remembered search over [entries] — pass-through when no search is standing. */
    private fun applySearch(entries: List<FeedEntry>): List<FeedEntry> {
        val q = listSearchQuery
        if (q.isEmpty()) return entries
        return entries.filter {
            it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q) ||
                it.feedTitle.lowercase().contains(q)
        }
    }

    /** In-feed search: filter the loaded list by title/blurb (Miniflux + local). */
    private fun showFeedSearch() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Search all feeds"; setSingleLine()
            setText(listSearchQuery); setSelection(listSearchQuery.length)
        }
        // Through the modal door like every dialog near a raw ink session.
        showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                listSearchQuery = input.text.toString().trim().lowercase()
                // Search across ALL loaded feeds/lenses (not just the current view); empty clears back.
                adapter.submit(if (listSearchQuery.isEmpty()) filterByNavDay(applyPicked(applyKind(allEntries))) else applySearch(allEntries))
            }
            .setNeutralButton("Keep as smart feed") { _, _ ->
                // The retired "➕ Add smart feed" row's job, done where the query already is
                // (09-03: the row beside Search read as a second search). Named by its terms;
                // rename later by re-adding under the name you want.
                val q = input.text.toString().trim()
                if (q.isNotBlank()) {
                    com.toolsboox.plugin.feeds.nw.SmartFeedStore.add(
                        requireContext(), com.toolsboox.plugin.feeds.nw.SmartFeed(
                            java.util.UUID.randomUUID().toString(), q, q.lowercase()))
                    renderDirectory()
                    showMessage("#  \u201c$q\u201d kept under SEARCH", binding.root)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    private fun refresh() {
        // Queued behind a load still in flight, the lens has already moved: dress the rail for it
        // now rather than after the old load lands (an open piece re-dresses when its pane closes).
        if (loading) { refreshQueued = true; rememberPlace(); if (currentArticle == null) redressRail(); return }
        // Changing feed/view returns to the list from any open in-pane article.
        if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
        dropStalePick()
        // Every lens, view, mailbox and lane switch ends here, so here is where the place is kept.
        rememberPlace()
        redressRail()
        binding.viewToggleButton.setText(if (mode == "stars") R.string.feeds_view_unread else R.string.feeds_view_starred)
        // The stamp follows the corpus: a lens switch must swap to the arriving corpus's own
        // time (or to silence, before its first load) — not keep wearing the departing one's.
        updateWindowLabel()
        // The Mail lens swaps the corpus, not the pipeline: the view chips still pick the mode,
        // the almanac window and the search still narrow the shown list — but the rows come from
        // the mail store rather than Miniflux. Checked before the mode branches because the lens
        // only ever rides the composable modes (feed/read/both/stars/edition).
        if (kindFilter == KIND_MAIL) { loadMailInbox(); return }
        if (mode == "later") { loadLaterList(); return }
        if (mode == "local") { loadLocal(); return }
        if (mode == "smart") { loadSmart(); return }
        if (mode == "asklog") { loadAskLog(); return }
        if (mode == "pickings") { loadPickingsFeed(); return }
        if (mode == "bsky") { loadBlueskyFeed(); return }

        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) {
            binding.settingsPanel.visibility = View.VISIBLE
            showEmpty(getString(R.string.feeds_need_creds))
            return
        }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        val lens = kindFilter
        val viewMode = mode
        val ck = cacheKey()
        lifecycleScope.launch {
            // WARM FIRST — the mail lens's law, applied to every Miniflux door: if the rows on
            // screen don't already answer this corpus, the offline cache renders BEFORE any
            // network round trip. Every entry path (hub, quick-jump, the notes↔feeds shuttle,
            // back-navigation) shows last-known rows instantly; the fetch below only makes them
            // fresher. Before this, the cache was read only on the ERROR path, so a cold entry
            // sat blank behind the spinner for the whole round trip.
            if (corpusKey != ck) {
                val warm = withContext(Dispatchers.IO) {
                    runCatching {
                        com.toolsboox.plugin.feeds.nw.FeedCache.loadEntries(requireContext(), ck)
                    }.getOrDefault(emptyList())
                }
                if (isAdded && warm.isNotEmpty() && mode == viewMode && kindFilter == lens) {
                    allEntries = withOfflineListen(warm)
                    corpusKey = ck
                    adapter.submit(filterByNavDay(applyPicked(applyKind(allEntries))))
                    binding.emptyText.visibility = View.GONE
                    renderDirectory()
                }
            }
            val result = withContext(Dispatchers.IO) {
                val base = when (viewMode) {
                    "stars" -> miniflux.fetchStarred(url, token)
                    "read" -> miniflux.fetchRead(url, token)
                    "both" -> miniflux.fetchEverything(url, token)
                    "edition" -> {
                        val (s, e) = navWindow()
                        val zone = java.time.ZoneId.systemDefault()
                        miniflux.fetchPublishedWindow(
                            url, token, null,
                            s.atStartOfDay(zone).toEpochSecond(), e.atStartOfDay(zone).toEpochSecond(),
                            limit = 300
                        )
                    }
                    else -> miniflux.fetchUnread(url, token)
                }
                // A lens is a QUESTION PUT TO THE SERVER, not a sieve held over the front page.
                // Unread/Read/All each fetch one flat page (50/100/150 rows) ordered by publish
                // date across every feed at once — so a medium that publishes rarely never makes
                // that page. Michael's podcasts publish an episode a day against text feeds that
                // publish dozens, and The Listen showed "(nothing in this lens yet)" while whole
                // 🎧 folders sat on the server unread. Ask the folders themselves, exactly as the
                // iPad does (FeedStore `.media(categoryIDs:)` merges `entries(categoryID:)` per
                // category), and union the result with the flat page — the page still contributes
                // audio that lives in a plainly-named folder, which is what `kind` catches by
                // enclosure and category ids never would.
                if (lens != null) unionLensCategories(url, token, viewMode, lens, base) else base
            }
            if (finishLoad()) return@launch   // a newer ask superseded this load — render nothing stale
            // Belt for state mutated without a loader (the same stale-render bug by another door):
            // a result for a corpus nobody is looking at any more is cached below, never drawn.
            if (mode != viewMode || kindFilter != lens) return@launch
            when (result) {
                is MinifluxClient.Result.Ok -> {
                    allEntries = withOfflineListen(result.value)
                    corpusKey = ck
                    com.toolsboox.plugin.feeds.nw.FeedCache.saveEntries(requireContext(), cacheKey(), result.value)
                    prefetchParsed(url, token, result.value)   // download readable (parsed) versions offline
                    // Opportunistic janitor — parsed HTML accrued forever (the .versions lesson) —
                    // and the auto-keep sweep rides with it. Auto-keep used to run right here, on
                    // Main: it stats every candidate file, and now that each kept episode also
                    // files a row in the offline index it writes that JSON too. Small work, but
                    // file work, and file work on the UI thread of an e-ink device is a stutter
                    // on every single feed load. Newest-first; the count cap trims the rest.
                    val appCtx = requireContext().applicationContext
                    val loaded = result.value
                    lifecycleScope.launch(Dispatchers.IO) {
                        com.toolsboox.plugin.feeds.nw.FeedCache.prune(appCtx)
                        com.toolsboox.plugin.feeds.nw.LaterMedia.keepRecent(appCtx, loaded)
                    }
                    // The gram's "Go to feed" one-shot became a standing pick on arrival (see
                    // onViewCreated), so the feed survives every later ⟳ — not just this one.
                    val shown = filterByNavDay(applyPicked(applyKind(allEntries)))
                    // A standing search survives the reload — it spans all loaded feeds, exactly
                    // as the dialog's Search does, until its own Clear.
                    adapter.submit(if (listSearchQuery.isEmpty()) shown else applySearch(allEntries))
                    if (shown.isEmpty()) showEmpty(getString(R.string.feeds_empty))
                    else binding.emptyText.visibility = View.GONE
                    stampUpdated()
                    renderDirectory()
                    if (FeedSelection.openDirectoryOnArrival) {
                        FeedSelection.openDirectoryOnArrival = false
                        showFeedDirectory()
                    }
                }
                is MinifluxClient.Result.Err -> {
                    // A failed fetch may only make the list OLDER, never blanker (the mail fix's
                    // rule): rows already answering this corpus stay put with a quiet note.
                    if (corpusKey == ck && allEntries.isNotEmpty()) {
                        showMessage(("⚠️ " + result.message).take(80), binding.root)
                    } else {
                        // Offline: fall back to the cached (offline-readable) copy.
                        val cached = withOfflineListen(
                            com.toolsboox.plugin.feeds.nw.FeedCache.loadEntries(requireContext(), cacheKey()))
                        if (cached.isNotEmpty()) {
                            allEntries = cached
                            corpusKey = ck
                            adapter.submit(filterByNavDay(applyPicked(applyKind(cached))))
                            renderDirectory()
                        } else showEmpty("⚠️ " + result.message)
                    }
                }
            }
        }
    }

    private fun cacheKey(): String =
        if (mode == "edition") "edition_${navGranularity}_${navAnchor}_${kindFilter ?: "all"}"
        else "${mode}_${kindFilter ?: "all"}"

    /**
     * Union DOWNLOADED audio into The Listen — episodes that live on this device but have aged out
     * of whatever the server just handed back.
     *
     * The server's recent window and Michael's downloads are two different sets. He keeps the last
     * eight episodes offline for the subway; the unread page moves on within a day or two. Without
     * this the file is on the Boox, the ⬇ says it's downloaded, and The Listen never shows it —
     * the episode simply stops existing as far as the lens is concerned. The iPad has always
     * unioned these in (`FeedLedgerView.load`: `episodes = live + downloadedOnly`); this is the
     * Android half, now that [LaterMedia] keeps a metadata index to rebuild the rows from.
     *
     * Rebuilt rows carry the episode's REAL entry id, so one that later returns to the live list
     * dedupes against itself and its star / mark-read still reach the server. They're marked read
     * (that's why they aged out) and filed under a 🎧 Downloaded folder so the drawer says plainly
     * where they came from.
     */
    private fun withOfflineListen(entries: List<FeedEntry>): List<FeedEntry> {
        if (kindFilter != "listen") return entries
        val present = entries.mapTo(HashSet()) { it.id }
        val extra = com.toolsboox.plugin.feeds.nw.LaterMedia.downloaded(requireContext())
            .filter { it.entryId !in present }
            .map { i ->
                FeedEntry(
                    id = i.entryId, title = i.title, feedTitle = i.feedTitle, url = i.sourceUrl,
                    author = null, content = "", publishedAt = i.publishedAt,
                    starred = false, read = true, category = "🎧 Downloaded",
                    enclosureImage = i.imageUrl, enclosureAudio = i.audioUrl
                )
            }
        // Aged-out downloads TRAIL the live list — they are older by definition, and pushing them
        // to the top would bury today's episodes under last month's.
        return if (extra.isEmpty()) entries else entries + extra
    }

    /** Miniflux category list, held for the life of the screen. The lens fetch below needs the
     *  (id, title) map on every reload and the folder set changes about as often as Michael adds
     *  a subscription — re-asking for it on each lens tap is a round trip for an answer we
     *  already have. Blank on failure so the union simply degrades to the flat page. */
    private var categoriesCache: List<Pair<Long, String>>? = null

    /**
     * Merge the lens's own folders into [base] — the per-medium server fetch, mirroring the iPad's
     * `.media(categoryIDs:)` source.
     *
     * Only the corpus views compose this way. Starred and the Edition window are DEFINED by
     * something other than the medium (a bookmark; a publish window), so pouring whole 🎧 folders
     * into them would put unstarred episodes under the ⭐ chip and items from outside the window
     * into the Edition — the chip would stay lit over a list that no longer obeyed it.
     *
     * Blocking; call from [Dispatchers.IO].
     */
    private fun unionLensCategories(
        url: String, token: String, viewMode: String, lens: String,
        base: MinifluxClient.Result<List<FeedEntry>>
    ): MinifluxClient.Result<List<FeedEntry>> {
        if (viewMode !in setOf("feed", "read", "both")) return base
        val cats = categoriesCache
            ?: (miniflux.categories(url, token) as? MinifluxClient.Result.Ok)?.value?.also { categoriesCache = it }
            ?: return base
        val ids = cats.filter { (_, title) -> FeedEntry.categoryKind(title) == lens }.map { it.first }
        if (ids.isEmpty()) return base
        val status = when (viewMode) { "feed" -> "unread"; "read" -> "read"; else -> null }
        val extra = ids.flatMap { id ->
            (miniflux.fetchCategory(url, token, id, status) as? MinifluxClient.Result.Ok)?.value.orEmpty()
        }
        if (extra.isEmpty()) return base
        // The flat page still leads: it is what every other view shows, and an entry that appears
        // in both must keep the copy the rest of the screen is holding (same id, same read/star
        // state). distinctBy keeps the first, so `base + extra` is the right order.
        val merged = (((base as? MinifluxClient.Result.Ok)?.value).orEmpty() + extra)
            .distinctBy { it.id }
            // Sorted on the parsed instant, not on the timestamp STRING: feeds hand back offsets
            // as well as Z, and "…T09:00:00-04:00" sorts before "…T08:00:00Z" as text while being
            // the later moment — which is how a merged list ends up shuffled by timezone.
            .sortedByDescending {
                runCatching { java.time.OffsetDateTime.parse(it.publishedAt).toEpochSecond() }.getOrDefault(0L)
            }
        return MinifluxClient.Result.Ok(merged)
    }

    /** Best-effort: fetch + cache the parsed (readability) HTML for each entry so the
     *  in-pane reader has an offline-readable version. Runs in the background. The 400 window
     *  matches iOS (prefetch 400 / cap 800 / 60-day age) so both devices carry the same
     *  offline depth. */
    private fun prefetchParsed(url: String, token: String, entries: List<FeedEntry>) {
        // The app context, captured once: a 400-entry sweep easily outlives this view, and
        // requireContext() mid-loop on a detached fragment is a crash, not a cache miss.
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            var bypassBudget = 30   // pre-unlock at most N paywall stubs per sweep (mirrors iOS)
            for (e in entries.take(400)) {
                if (com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(appCtx, e.id) != null) continue
                val res = runCatching { miniflux.fetchContent(url, token, e.id) }.getOrNull()
                var content = if (res is MinifluxClient.Result.Ok) res.value else ""
                // Pre-unlock a paywalled stub through the bypass ahead of time, so the reader has the
                // full article offline. Bounded + gentle so a big sweep doesn't hammer archive.today.
                if (bypassBudget > 0 && e.url.isNotBlank() &&
                    com.toolsboox.plugin.feeds.nw.PaywallBypass.looksTruncated(content.ifBlank { null })) {
                    bypassBudget--
                    com.toolsboox.plugin.feeds.nw.PaywallBypass.readable(e.url)?.let { content = it }
                    kotlinx.coroutines.delay(400)   // suspend, don't hold an IO worker (audit 09-10)
                }
                if (content.isNotBlank()) {
                    com.toolsboox.plugin.feeds.nw.FeedCache.saveContent(appCtx, e.id, content)
                }
            }
        }
    }

    /** Later List: the read-later links intaked across recent days (MichaelFilter intake
     *  sidecar), as feed rows — narrowed to one lane when the drawer's 🔖 folder is drilled into. */
    private fun loadLaterList() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            // Render what's on THIS device immediately — never block the list on network pulls
            // (14 sequential cross-device fetches left the Later list blank/spinning "not loading").
            val local = withContext(Dispatchers.IO) { gatherLaterList() }
            binding.progress.visibility = View.INVISIBLE
            allEntries = local
            corpusKey = "later"
            // The Later list is the WHOLE backlog ("a finite feed to clear"), NOT filtered by the
            // timeline nav — nav-filtering it hid every item saved on a day other than the one the
            // navigator happened to sit on — and NOT filtered by the media lens either. The lens
            // asks a row to guess its own medium from its category and URL; the Later List's axis
            // is the LANE it was filed into, which is a fact, and which `gatherLaterList` has
            // already applied. Running both meant arriving here with 🎧 The Listen open and being
            // told the whole list was empty.
            adapter.submit(local)
            if (local.isEmpty()) showEmpty(laterEmptyText())
            else binding.emptyText.visibility = View.GONE

            // Then catch up on the other devices' filings in the background, and put them on screen
            // if any arrived. TWO SWEEPS, because they answer different questions: the recent one
            // asks "what changed on days I already have" (a round trip each, so it stays short),
            // and the listing one asks "which days exist at all that I have never seen" — which is
            // the only question that can help a device where he does no filing. See
            // IntakePageStore.pullMissingDays for why a second date window was not the answer.
            withContext(Dispatchers.IO) {
                val ctx = requireContext().applicationContext
                val store = com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
                for (d in 0L..14L) runCatching {
                    store.pullLatest(ctx, java.time.LocalDate.now().minusDays(d))
                }
                // Single-flight with a floor: this method runs again on every reload and every
                // lane tap, and the sweep is a PROPFIND plus a GET per unseen day. A completed
                // sweep buys a few minutes of quiet rather than re-running because he tapped 📧.
                if (System.currentTimeMillis() - lastLaterBackfillMs > 5 * 60_000L) {
                    lastLaterBackfillMs = System.currentTimeMillis()
                    runCatching { store.pullMissingDays(ctx) }
                }
            }
            if (mode == "later" && isAdded) {
                val merged = withContext(Dispatchers.IO) { gatherLaterList() }
                // Content comparison, not a size check — a same-count swap (one added, one
                // deduped) used to leave today's additions invisible until re-entry.
                val changed = merged.map { it.url + "·" + it.title }.toSet() !=
                    local.map { it.url + "·" + it.title }.toSet()
                if (changed) {
                    allEntries = merged
                    adapter.submit(merged)
                }
                if (merged.isEmpty()) {
                    // STILL EMPTY AFTER BOTH SWEEPS — the one moment "can this device even reach
                    // the others" is worth a round trip, and the only moment it is. A probe on
                    // every load would put a request in front of a list that already had its
                    // answer. Re-shown unconditionally rather than only when `changed`, because
                    // an empty list that stays empty is the case where nothing changed and the
                    // explanation is the only thing that did.
                    withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                            .probe(requireContext().applicationContext)
                    }
                    if (isAdded) showEmpty(laterEmptyText())
                } else if (changed) {
                    binding.emptyText.visibility = View.GONE
                }
            }
        }
    }

    /** When the Later List's listing sweep last ran — see [loadLaterList]. On the fragment rather
     *  than in the store because it is a UI-pacing decision, not a fact about the data. */
    private var lastLaterBackfillMs = 0L

    /**
     * The Later List's empty state, which has to say WHICH emptiness it means.
     *
     * "Nothing on your Later List yet" is true of the whole list and a lie about one lane — a man
     * who has filed four hundred links and drilled into 📧 Email would be told he has never filed
     * anything. So a lane names itself and points back at the list it belongs to, which is one tap
     * up in the same drawer.
     *
     * And there is a second emptiness underneath both of those, which was the whole reason the Go 6
     * showed nothing: this list's contents come from the intake sidecars, which are pulled across
     * WebDAV, and a device with no WebDAV configured can never see a link filed anywhere else. That
     * is not "you have not saved anything yet" — it is "this device is not part of the ledger yet",
     * and only one of those two sentences tells you what to do. [LedgerSidecarSync.explainEmpty]
     * appends it when it applies and stays silent when it doesn't, so a configured device with an
     * empty list still reads as an empty list rather than as a fault.
     */
    private fun laterEmptyText(): String {
        val own = laterLane?.let { lane ->
            val label = com.toolsboox.plugin.feeds.nw.LaterFeed.LANES
                .firstOrNull { it.first == lane }?.third ?: lane
            "Nothing filed under $label yet — the rest of your Later List is one row up in 🔖."
        } ?: getString(R.string.feeds_later_empty)
        return com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
            .explainEmpty(requireContext(), own)
    }

    /** Feed Pickings: your pickings boards (that hold grams or ink) surfaced as feed rows. Tapping
     *  a row jumps straight to that board. */
    private fun loadPickingsFeed() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { gatherPickingsBoards() }
            binding.progress.visibility = View.INVISIBLE
            allEntries = entries
            corpusKey = "pickings"
            adapter.submit(entries)
            // Board NAMES ride the same cross-device sidecar the Later List does (PickingsStore's
            // per-day index), so this list inherits the same silence: an unconfigured device shows
            // no boards made anywhere else and blames it on you. Same sentence, same conditions.
            if (entries.isEmpty()) showEmpty(
                com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
                    .explainEmpty(requireContext(), "No pickings boards with content yet.")
            )
            else binding.emptyText.visibility = View.GONE
        }
    }

    private fun gatherPickingsBoards(): List<FeedEntry> {
        val ctx = requireContext().applicationContext
        val root = documentsRoot()
        val locale = java.util.Locale.getDefault()
        val out = mutableListOf<FeedEntry>()
        // Synthetic rows must NEVER collide with real Miniflux entry ids (small positive ints)
        // or LocalFeedStore ids (-1000 .. -1e9): pickings boards live in their own negative band.
        var id = PICKINGS_ID_BASE
        for (d in 0L..60L) {
            val date = java.time.LocalDate.now().minusDays(d)
            val boards = com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, date)
            val day = runCatching { calendarDayService.loadOrNull(root, date, locale) }.getOrNull() ?: continue
            for (b in boards) {
                val grams = day.imageElements.count { it.page == b.key }
                val strokes = day.noteStrokes[b.key]?.size ?: 0
                if (grams == 0 && strokes == 0) continue        // only boards with content
                val bits = listOfNotNull(
                    if (grams > 0) "$grams pick${if (grams == 1) "" else "s"}" else null,
                    if (strokes > 0) "$strokes stroke${if (strokes == 1) "" else "s"}" else null
                ).joinToString(" · ")
                out += FeedEntry(
                    id = id--, title = b.name, feedTitle = "Pickings · $date",
                    url = "ledger://pickings/$date/${b.key}", author = null,
                    content = "<p>$bits</p>", publishedAt = date.toString(),
                    starred = false, category = "pickings-board"
                )
            }
        }
        return out
    }

    /* ---------------------------------------------------------------
     * Bluesky — the following-timeline, as one more source in this list
     * ------------------------------------------------------------- */

    /**
     * WHY THE TIMELINE LIVES HERE AND NOT ON A SCREEN OF ITS OWN.
     *
     * [com.toolsboox.plugin.calendar.ui.BlueskyFragment] is the CORRESPONDENCE surface: the replies
     * awaiting an answer and the ink pad he answers them on. It is a work queue — a short list you
     * empty. A timeline is the opposite: a long list you skim, page through with the volume keys,
     * star out of, search, and stop reading in the middle of. Everything that makes that bearable
     * on e-ink already exists in this fragment and in nothing else the app has — list → in-pane
     * reader, volume paging, tap zones, mark-read-on-scroll, the search field with its scopes, the
     * star that writes a ReadingEvent into the day JSON. Building a second list surface for Bluesky
     * would have been re-implementing all of it, worse, and then having two places where "mark as
     * read" means something slightly different.
     *
     * So Bluesky is a SOURCE, exactly as Pickings, Later and the Ask log are sources: its posts
     * become [FeedEntry] rows in the band of synthetic ids, and every behaviour of this screen
     * applies to them for free. Replying is the one thing this screen cannot do, and that is
     * precisely the thing BlueskyFragment already does — so the reader hands off to it rather than
     * growing a second composer.
     *
     * AVATARS ARE NOT SHOWN, and that is a decision rather than an omission. Every item carries an
     * avatar URL and it would be trivial to put it in the row's thumbnail slot. On a monochrome
     * panel a 76dp avatar is a dithered grey smudge that tells you nothing — faces are exactly the
     * kind of image e-ink renders worst — and it would evict the one image on the row that IS worth
     * its width: the post's own attached picture, which about a sixth of real posts have. Identity
     * is carried as text instead (display name and @handle on the meta line), which is legible at
     * every text tier and costs no network round trip per row.
     */
    private fun loadBlueskyFeed() {
        val ctx = requireContext()
        if (!com.toolsboox.plugin.calendar.nw.BlueskyReply.config(ctx).ready) {
            allEntries = emptyList()
            adapter.submit(emptyList())
            showEmpty(
                "The Bluesky bridge isn't configured yet.\n\n" +
                    "Open 🦋 Bluesky from the Ledger directory and paste the shared POSSE secret " +
                    "there — this list reads the same bridge, so setting it once covers both."
            )
            return
        }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val posts = withContext(Dispatchers.IO) {
                com.toolsboox.plugin.calendar.nw.BlueskyFeed.recent(ctx, 200)
            }
            if (finishLoad()) return@launch
            if (!isAdded) return@launch
            // Rebuilt from scratch: ids are positional, so a shorter timeline would otherwise leave
            // the tail of the previous load's mapping pointing at posts no longer on screen.
            bskyUriById.clear()
            val entries = posts.mapIndexed { i, p -> blueskyEntry(p, BSKY_ID_BASE - i) }
            allEntries = entries
            corpusKey = "bsky"
            adapter.submit(entries)
            if (entries.isEmpty()) showEmpty("Nothing in the timeline mirror yet. ↻ to check again.")
            else binding.emptyText.visibility = View.GONE
            renderDirectory()
        }
    }

    /**
     * One mirrored post as a feed row.
     *
     * The mapping is doing real work in three places. A skeet has NO TITLE — it is a paragraph —
     * so the first line stands in as one and the whole text is the body; without that the row would
     * show a blank headline over a blurb, which is how every "posts as articles" list looks wrong.
     * Its `category` is deliberately left null so the meta line falls back to the feed title and
     * shows the handle rather than the word "bluesky". And the attached images go into the content
     * as real `<img>` tags, which is what makes [FeedEntry.imageUrl] — and therefore the row
     * thumbnail — the post's own picture.
     */
    private fun blueskyEntry(
        post: com.toolsboox.plugin.calendar.nw.BlueskyFeed.Item, id: Long
    ): FeedEntry {
        bskyUriById[id] = post.uri
        val text = post.text.trim()
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        // A post with no words at all is a picture post; saying so beats an empty row.
        val head = firstLine.ifBlank { if (post.images.isNotEmpty()) "(image)" else "(no text)" }
        // Most of a real timeline is replies (about three in five of his), and a reply read out of
        // its thread is confusing unless it says it is one. The glyph is the same ↳ the app uses
        // for nested things elsewhere; the parent itself is one tap away on bsky.app.
        val title = (if (post.isReply) "↳  " else "") + head.take(110)
        return FeedEntry(
            id = id,
            title = title,
            feedTitle = "Bluesky · @" + post.handle.ifBlank { "unknown" },
            url = post.url,
            author = post.author.takeIf { it.isNotBlank() },
            content = blueskyHtml(post),
            publishedAt = blueskyIso(post.createdAt),
            starred = false,
            read = post.seen,
            category = null,
        )
    }

    /**
     * The post as the in-pane reader's body: an answer door, the text, then any pictures.
     *
     * Everything from the post is ESCAPED. The site already runs the text through `wp_kses_post`,
     * so nothing dangerous survives the mirror — but this is a stranger's words being written into
     * a WebView, kses allows a generous tag set, and the text is meant to be read as the plain
     * prose it is. Escaping means a post containing "<3" or "a > b" shows what was typed instead of
     * losing it to a parser, which is the failure the server's own comment worries about from the
     * other side.
     *
     * The answer door is a `ledger://` link rather than a button because the reader IS a WebView:
     * there is no view here to hang a button on, and [openEntry] already intercepts main-frame
     * navigations, so one extra branch there turns a link into an in-app action.
     */
    private fun blueskyHtml(post: com.toolsboox.plugin.calendar.nw.BlueskyFeed.Item): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val sb = StringBuilder()
        // Argument-less on purpose. The obvious shape — the AT URI in the link's path — means
        // percent-encoding `at://did:plc:…/app.bsky.feed.post/…` into a URL and trusting the
        // WebView not to normalise the escaped slashes on the way back out, which is exactly the
        // sort of thing that works on one WebView build and not the next. The reader already knows
        // which post is open, so the link only has to say "this one".
        sb.append("<p><a href=\"").append(BSKY_REPLY_SCHEME)
            .append("\"><b>✍&nbsp;&nbsp;Answer this on Bluesky</b></a></p>")
        if (post.isReply) {
            sb.append("<p style=\"color:#666;font-size:14px\">↳ a reply in a thread — hold the ")
                .append("row, or use ⧉, to open the whole thread on bsky.app</p>")
        }
        for (para in post.text.split(Regex("\n{2,}"))) {
            if (para.isBlank()) continue
            sb.append("<p>").append(esc(para.trim()).replace("\n", "<br />")).append("</p>")
        }
        for (image in post.images) sb.append("<p><img src=\"").append(esc(image)).append("\"/></p>")
        return sb.toString()
    }

    /**
     * The site hands out `created_at` site-local with no offset ("2026-07-29 11:47:03") — it did
     * the timezone maths so no client has to. The row's date formatter wants an offset date-time,
     * so this attaches the DEVICE's zone, which is the same choice
     * [com.toolsboox.plugin.calendar.nw.WPPublish.parseWpDate] makes for WordPress's identically
     * shaped dates: when the zones agree (the normal case, this being his own site) the wall clock
     * is preserved exactly, and when they do not, a timeline is still ordered correctly because the
     * server sorted it before we ever saw it.
     */
    private fun blueskyIso(local: String): String = runCatching {
        java.time.LocalDateTime
            .parse(local.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime()
            .toString()
    }.getOrElse { local }

    /* ---------------------------------------------------------------
     * 📧 The Mail — the unified inbox, as one more source in this list
     * ------------------------------------------------------------- */

    /**
     * WHY MAIL RIDES THE FEEDS PANE. Michael, from the Palma: "this part of the mail screen
     * becomes redundant when we move it to the feeds section 'The Mail' instead of 'Emails' —
     * the feed pane filter works, and we can search the same way, and we can also use those
     * 'clear' etc in the same way on the popout, and the jumps will take you from email to email
     * or pagination within email."
     *
     * So mail is a SOURCE here, the way Bluesky and the Later List are sources — but unlike them
     * it is also a LENS: kindFilter == [KIND_MAIL] swaps the corpus while the whole pane grammar
     * keeps working over it. The VIEWS chips are the mail view axis (Unread/Read/All/Starred map
     * onto InboxStore's read and star state), the drawer search field searches sender, subject
     * and snippet through the same runSearch every article ride[s], the almanac strip windows the
     * pile by date — STACKING with the chips, since mail is all local. Since 2026-10-11 The Mail
     * is its own room ([isMailRoom]) and its drawer holds the mailboxes, in Michael's order
     * (2026-10-11: "Inbox then starred then @gmail"): 📥 Inbox · ⭐ Starred · each account ▸ its
     * Inbox · Sent · Junk · Trash. 🚫 Junk is the one mailbox read from somewhere else — the
     * account's own Junk folder on its server ([MailSync.fetchJunk]), never the store.
     *
     * NOTHING here fetches mail itself. [InboxStore] is the offline pile (warm → messages) and
     * [MailSync.refresh] is the one IMAP pull, exactly as the standalone screen used them while
     * it stood — the lens was a second door onto the same store, never a second sync, and since
     * Michael retired that screen ("Retire", after living with the lens) it is the only door.
     */
    private fun loadMailInbox() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val ctx = requireContext()
            // Fold the download cache in first (off main — it is the one expensive read), so the
            // lens is readable OFFLINE before any IMAP round-trip, as the retired screen was.
            withContext(Dispatchers.IO) { InboxStore.warm(ctx.applicationContext) }
            if (!isAdded) return@launch
            binding.progress.visibility = View.INVISIBLE
            submitMailRows()
            // A mail://<id> arrival (the gram router, an All Stars tap) opens its letter HERE,
            // after the warm load — the store either holds the message now or never will this
            // visit, so this is both the first chance and the honest last one.
            consumePendingMailOpen()
            // 🚫 An account's JUNK reads that account's Junk folder instead (read-only, kept in
            // JunkMail for the visit) — the same single-flight, and the INBOX pull waits for the
            // next Inbox view.
            val junkOf = MailPlace.where(mailMailbox).takeIf { it.box == MailPlace.Box.JUNK }?.account
            if (junkOf != null) {
                // A clean read from the last few minutes stands (review 2026-10-11): every 👁, window
                // step and return from a letter lands here, and each used to log in, LIST, EXAMINE
                // and fetch up to 25 letters again. ⟳ ([junkReread]) always asks.
                val force = junkReread
                junkReread = false
                val last = com.toolsboox.plugin.mail.JunkMail.get(junkOf)
                if (!force && last != null && last.error == null &&
                    System.currentTimeMillis() - last.readAt < JUNK_FRESH_MS) return@launch
                if (junkFetching == junkOf) return@launch
                junkFetching = junkOf
                // The server round-trip is the slow part here — keep the bar up through it (the
                // store's own load above already took it down), not just "Asking…" in the pane.
                binding.progress.visibility = View.VISIBLE
                try {
                    val f = MailSync.fetchJunk(ctx, junkOf)
                    if (isAdded && kindFilter == KIND_MAIL && MailPlace.where(mailMailbox).account == junkOf) {
                        submitMailRows()
                        if (f.error == null) stampUpdated()
                        else if (f.messages.isNotEmpty()) showMessage("Junk: ${f.error}", binding.root)
                    }
                } finally {
                    if (junkFetching == junkOf) junkFetching = null
                    if (isAdded) binding.progress.visibility = View.INVISIBLE
                }
                return@launch
            }
            // Then the background pull through the ONE refresh path, single-flight. The list is
            // already on screen from the store; the pull only makes it fresher.
            if (InboxStore.hasAccounts(ctx) && !mailRefreshing) {
                mailRefreshing = true
                try {
                    val problem = MailSync.refresh(ctx)   // suspends; fetch runs on Dispatchers.IO within
                    if (isAdded && kindFilter == KIND_MAIL) {
                        submitMailRows()
                        if (problem == null) stampUpdated()   // fresh from the server, and it says so
                        if (problem != null) showMessage(problem.split("\n").first(), binding.root)
                    }
                } finally {
                    // Also on cancellation — a wedged flag here would refuse every future refresh.
                    mailRefreshing = false
                }
            }
        }
    }

    /** Put the mail rows on screen through the same seams the articles use: the corpus into
     *  allEntries, the almanac window over it, the drawer's counts refreshed. */
    private fun submitMailRows() {
        val rows = gatherMailRows()
        allEntries = rows
        corpusKey = "mail"
        // The list search filters letters too — sender, subject, first line.
        val shown = filterByNavDay(rows).let { if (listSearchQuery.isEmpty()) it else applySearch(it) }
        adapter.submit(shown)
        if (shown.isEmpty()) showEmpty(mailEmptyText()) else binding.emptyText.visibility = View.GONE
        renderDirectory()
    }

    /**
     * Open the letter a mail://<id> source link asked for, if the store holds it — the one-shot
     * handoff the gram router sets before navigating here ([FeedSelection.pendingMailOpenId], the
     * same shape [FeedSelection.pendingInPaneEntry] rides for articles, and the same pending-open
     * idiom the retired standalone Mail screen consumed). Cleared BEFORE the open, and cleared
     * even when the letter is gone from the store, so a stale request can't fire on some later
     * ordinary visit. Consumed only after the warm load on purpose: asking earlier would miss
     * every letter that lives in the download cache rather than in this session's fetch.
     */
    private fun consumePendingMailOpen() {
        val id = FeedSelection.pendingMailOpenId ?: return
        FeedSelection.pendingMailOpenId = null
        // Resolution through MailOpenResolver, not a bare id equality: an All Stars gram starred
        // on the iPad names this same letter under the iPad's account row id, and the exact
        // match alone is why "select an 'Email'" reached the lens but never the letter (08-12
        // punchlist). The resolver tries exact first, then the letter's own IMAP uid tail —
        // and only an unambiguous answer opens; anything else keeps the old fall-through.
        // The Trash's letters too — a ↩ chip back to a letter read in 🗑 Trash names one of them.
        // And a 🚫 Junk letter read this session (its ↩ chip names it).
        val messages = InboxStore.messages(requireContext()) + InboxStore.cleared(requireContext()) +
            com.toolsboox.plugin.mail.JunkMail.all()
        val openId = com.toolsboox.plugin.mail.MailOpenResolver.resolve(id, messages.map { it.id }) ?: return
        val m = messages.firstOrNull { it.id == openId } ?: return
        // Minting the row maps it into [mailById] even when the current view doesn't list it (a
        // long-read letter under Unread, say) — the pane can open a letter the list isn't showing,
        // exactly as [openMail] keeps an open letter alive across a re-gather.
        val entry = mailEntry(m)
        openMailEntry(entry, m)
    }

    /** Whether asking the mail SERVERS means anything here: not over ➤ Sent or 🗑 Trash (2026-10-11
     *  review, as iOS does) — the servers only ever answer from INBOX, so a search there swapped the
     *  pile for inbox letters, and a Trash "result" wore ⤴ with nothing to move back. */
    private fun mailServerSearchHere(): Boolean = !MailPlace.isPile(mailMailbox)

    /** The door to the deep search layer, as one more row under the local matches — it says which
     *  query it will run, arrives read (nothing here wants attention) and answers to [openEntry]'s
     *  interceptor by its [MAIL_SEARCH_SCHEME] address, the ledger:// idiom the reply and
     *  load-the-rest doors already ride. */
    private fun mailServerSearchRow(q: String): FeedEntry = FeedEntry(
        id = MAIL_SEARCH_ROW_ID,
        title = "🔎  Search the server for \"$q\"",
        feedTitle = "",
        url = MAIL_SEARCH_SCHEME,
        author = null,
        content = "",
        publishedAt = java.time.OffsetDateTime.now().toString(),
        starred = false,
        read = true,
        category = null,
    )

    /**
     * Ask the SERVERS — IMAP `UID SEARCH` per account through [MailSync.searchServer], honoring
     * the account narrowing (a narrowed inbox should search narrowed; Sent stands aside, it is
     * all local and the instant layer already covered it). The matches come back through the
     * size-probed bounded fetch, so they are real, openable, starrable letters, and they REPLACE
     * the local layer on screen until the query changes — the retired standalone screen's
     * two-layer contract, kept. One broken account's error shows briefly; the rest still land.
     */
    private fun runMailServerSearch() {
        val ctx = context ?: return
        val q = mailSearchQuery
        if (q.isBlank() || mailServerSearching || !mailServerSearchHere()) return
        mailServerSearching = true
        showMessage("🔎 Searching the server…", binding.root)
        lifecycleScope.launch {
            try {
                val (results, errors) = withContext(Dispatchers.IO) {
                    MailSync.searchServer(ctx, q, composeFrom())
                }
                // Landed late? The lens moved on, or the query did — stale rows must not stamp
                // themselves over whatever the reader is looking at now.
                if (!isAdded || kindFilter != KIND_MAIL || mailSearchQuery != q) return@launch
                adapter.submit(results.map { mailEntry(it) })
                if (results.isEmpty()) showEmpty("The server found nothing for \"$q\".")
                else binding.emptyText.visibility = View.GONE
                if (errors.isNotEmpty()) showMessage(errors.first(), binding.root)
            } catch (e: Exception) {
                if (isAdded) showMessage("Server search failed: ${e.message ?: "unknown error"}", binding.root)
            } finally {
                // Also on cancellation — a wedged flag would refuse every future search.
                mailServerSearching = false
            }
        }
    }

    /**
     * The inbox as feed rows: the store's pile, narrowed by the mailbox and the view mode. The
     * mode does here what it does for articles — decide which corpus lands in allEntries — and
     * the date window and search compose on top, downstream, through the shared pipeline.
     *
     * Sent is a DIFFERENT PILE, not a lens over this one (the retired screen's rule, kept):
     * the view axis stands aside for it — Unread and Read mean nothing about a letter you wrote,
     * and Starred would empty the mailbox for no reason a reader could work out.
     */
    private fun gatherMailRows(): List<FeedEntry> {
        val ctx = requireContext()
        mailById.clear()
        // Built only where it's read — 🗑 Trash lists the cleared set alone, so it no longer walks
        // the whole held store twice per render on the B6 (2026-10-11 review).
        val all by lazy { InboxStore.messages(ctx) }
        // The mailbox read back: which box, whose — an account's ➤ Sent, 🚫 Junk or 🗑 Trash
        // narrows to that account by ORIGIN (Michael 2026-10-11: "Mail accounts also have
        // individual sents, junks, trash"); a null account is every account's pile.
        val w = MailPlace.where(mailMailbox)
        fun mine(id: String): Boolean = w.account == null ||
            (if (InboxStore.isSent(id)) MailThreads.sentAccount(id) else MailSync.accountId(id)) == w.account
        if (w.box == MailPlace.Box.SENT) refreshCrmStatesOnce()
        // 🗑 TRASH is a pile too (Michael 2026-10-11): the letters cleared on this device, still
        // held here — the view axis stands aside, as for Sent. See InboxStore.cleared.
        // 🚫 JUNK is the account's own Junk folder as last read ([JunkMail], loadMailInbox) —
        // never part of the store, so never in any other view.
        val base = if (w.box == MailPlace.Box.SENT) all.filter { InboxStore.isSent(it.id) && mine(it.id) }
        else if (w.box == MailPlace.Box.TRASH) InboxStore.cleared(ctx).filter { mine(it.id) }
        else if (w.box == MailPlace.Box.JUNK) w.account?.let { com.toolsboox.plugin.mail.JunkMail.get(it)?.messages }.orEmpty()
        else {
            // Sent rows are excluded from every incoming view — they are keep-forever rows in the
            // same store, not incoming mail. (History, from the retired screen: a sent row carries
            // no read id, so Unread once listed every reply ever written as wanting attention.)
            // A junk letter you starred is kept in ⭐ Starred, and only there — the Inbox views
            // never list one.
            var b = all.filter { !InboxStore.isSent(it.id) &&
                (mode == "stars" || !com.toolsboox.plugin.mail.JunkMail.isJunk(it.id)) }
            b = when (mode) {
                "stars" -> b.filter { InboxStore.isStarred(ctx, it.id) }
                "feed" -> b.filter { !InboxStore.isRead(ctx, it.id) }
                "read" -> b.filter { InboxStore.isRead(ctx, it.id) }
                else -> b   // "both" / "edition" — everything; the window narrows downstream
            }
            // Narrow to one account by ORIGIN (the acct:<id> prefix), not by display label —
            // labels get renamed; the id a message arrived through doesn't.
            mailMailbox?.let { id -> b = b.filter { MailSync.accountId(it.id) == id } }
            b
        }
        // The dial's order, applied to whatever pile the narrowing left. Date is the letter's OWN
        // date (the row's stamp), newest first — sorted explicitly rather than trusting store
        // order, so the default is true by construction. Sender groups by the row's "who" line
        // (a Sent row's who is its recipient), newest within; Subject shelves threads together
        // by ignoring the reply/forward dressing the subject line accumulates.
        fun who(m: InboxMessage): String =
            (if (InboxStore.isSent(m.id)) m.toEmail.ifBlank { m.toName } else m.fromEmail.ifBlank { m.fromName })
                .lowercase()
        fun subjectKey(m: InboxMessage): String {
            var s = m.subject.trim().lowercase()
            while (true) {
                val stripped = s.removePrefix("re:").removePrefix("fwd:").removePrefix("fw:").trimStart()
                if (stripped == s) break
                s = stripped
            }
            return s
        }
        val ordered = when (mailSort()) {
            MailSort.DATE -> base.sortedByDescending { it.date }
            MailSort.SENDER -> base.sortedWith(compareBy<InboxMessage> { who(it) }.thenByDescending { it.date })
            MailSort.SUBJECT -> base.sortedWith(compareBy<InboxMessage> { subjectKey(it) }.thenByDescending { it.date })
        }
        val rows = ordered.map { mailEntry(it) }
        // The letter being read survives the rebuild even when the view no longer lists it —
        // see [openMail] for the mid-read failure this prevents.
        openMail?.let { (id, msg) -> mailById.putIfAbsent(id, msg) }
        return rows
    }

    /**
     * One letter as a feed row. The mapping mirrors what the mail list already shows: sender on
     * the feed-title line (a Sent row shows its RECIPIENT — you know who wrote it), subject as
     * the title, snippet as the excerpt (escaped into the content so [FeedEntry.blurb] finds it),
     * unread as the feeds' unread, star as the mail star. The id is minted into mail's own
     * negative band from a hash of the message id, so a reload keeps rows stable and nothing
     * collides with Miniflux, local-feed or the other synthetic bands.
     */
    /** Ask the sites how the CRM letters fared, at most once a minute, then redraw the rows. */
    private var lastCrmRefresh = 0L
    private fun refreshCrmStatesOnce() {
        val now = System.currentTimeMillis()
        if (now - lastCrmRefresh < 60_000L) return
        lastCrmRefresh = now
        val ctx = context?.applicationContext ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { com.toolsboox.plugin.mail.MailSync.refreshCrmStates(ctx) }
            withContext(Dispatchers.Main) { if (isAdded) submitMailRows() }
        }
    }

    private fun mailEntry(m: InboxMessage): FeedEntry {
        val ctx = requireContext()
        val id = MAIL_ID_BASE - (m.id.hashCode().toLong() and 0xFFFFFFFFL)
        mailById[id] = m
        val sent = InboxStore.isSent(m.id)
        // A CRM letter wears what the site last said of it: sending · delivered · opened · clicked.
        val crm = if (sent) InboxStore.crmTag(ctx, m.id) else ""
        // ↩ ANSWERED (2026-10-10, M1: "did I answer this?" in no taps) — replied from this device,
        // or named by one of your sent rows' References. Until Sent comes from the server (M2)
        // that means answered FROM THIS DEVICE.
        // The adapter DRAWS it from this set; the entry's own name stays clean (2026-10-10 review).
        if (!sent && MailThreads.isAnswered(ctx, m)) mailAnsweredRows.add(id) else mailAnsweredRows.remove(id)
        val who = (if (sent) "To " + m.toName.ifBlank { m.toEmail }.ifBlank { m.account }.ifBlank { "—" }
        else m.fromName.ifBlank { m.fromEmail }.ifBlank { "Unknown sender" }) +
            (if (crm.isNotBlank()) " · $crm" else "")
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return FeedEntry(
            id = id,
            title = m.subject.ifBlank { "(no subject)" },
            feedTitle = who,
            url = "mail://${m.id}",
            author = null,
            content = "<p>${esc(m.snippet)}</p>",
            publishedAt = java.time.Instant.ofEpochMilli(m.date)
                .atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString(),
            starred = InboxStore.isStarred(ctx, m.id),
            // A letter you wrote is never "unread" — the one row that has had all your attention.
            read = sent || InboxStore.isRead(ctx, m.id),
            category = null,
        )
    }

    /** The empty pane names the filter that emptied it — a filtered-empty list that just says
     *  "empty" reads as broken (the retired screen's rule, in this pane's shorter voice). */
    private fun mailEmptyText(): String {
        val windowed = !(navGranularity == "day" && navAnchor == java.time.LocalDate.now())
        val w = MailPlace.where(mailMailbox)
        val whose = w.account?.let { id ->
            com.toolsboox.plugin.mail.MailAccountStore.all(requireContext()).firstOrNull { it.id == id }?.display
        }
        return when {
            w.box == MailPlace.Box.JUNK -> junkEmptyText(w.account, windowed)
            w.box == MailPlace.Box.SENT && windowed -> "Nothing sent in ${windowLabel()} — tap today on the strip for everything you've sent."
            w.box == MailPlace.Box.SENT -> "Nothing sent from ${whose ?: "this device"} yet. Replies and letters you compose are kept here."
            w.box == MailPlace.Box.TRASH && windowed -> "Nothing cleared in ${windowLabel()} — tap today on the strip for the whole Trash."
            w.box == MailPlace.Box.TRASH -> "Trash is empty${whose?.let { " for $it" } ?: ""}. Letters you 🧹 sweep or 🗑 delete wait here — ⤴ puts one back in the Inbox."
            windowed -> "No mail in ${windowLabel()} — tap today on the strip for the whole inbox."
            mode == "stars" -> "No starred mail yet. Open a letter and star it to keep it."
            mode == "feed" -> "Nothing unread — you're caught up."
            mode == "read" -> "Nothing read yet."
            mailMailbox != null -> "No mail from this account yet."
            InboxStore.hasAccounts(requireContext()) -> "Inbox empty."
            else -> "No accounts yet — ⚙ on the rail adds one. Until then a few samples show here."
        }
    }

    /** 🚫 Junk's empty pane says which of four things it is: still asking, no such folder on the
     *  server, a failed read, or a Junk folder with nothing in it (in this window). */
    private fun junkEmptyText(accountId: String?, windowed: Boolean): String {
        val acct = accountId?.let { id ->
            com.toolsboox.plugin.mail.MailAccountStore.all(requireContext()).firstOrNull { it.id == id }
        } ?: return "That account is gone — ☰ for your mailboxes."
        val f = com.toolsboox.plugin.mail.JunkMail.get(acct.id)
        return when {
            f == null -> "Asking ${acct.display} for its Junk folder…"
            f.error != null && f.messages.isEmpty() -> "Couldn't read ${acct.display}'s Junk: ${f.error}"
            f.name == null -> "${acct.display} has no Junk or Spam folder on its server."
            windowed -> "Nothing in ${f.name} in ${windowLabel()} — tap today on the strip for the whole folder."
            else -> "${f.name} is empty on ${acct.display}."
        }
    }

    /** The 🧹 sweep, on the list AS SHOWN: everything visible that isn't kept (starred, replied-to
     *  or sent), swept with the same held breath the retired screen held — the snackbar's Undo
     *  puts the whole sweep back until it lapses. */
    private fun sweepMail() {
        val ctx = requireContext()
        val swept = adapter.current().mapNotNull { mailById[it.id] }
            .filter { !InboxStore.isStarred(ctx, it.id) && !InboxStore.isReplied(ctx, it.id) && !InboxStore.isSent(it.id) }
            .map { it.id }
        if (swept.isEmpty()) { showMessage("Nothing to clear — it's all kept", binding.root); return }
        InboxStore.clear(ctx, swept)
        submitMailRows()
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, "Cleared ${swept.size} to 🗑 Trash — starred mail kept",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction("Undo") {
            // The app context outlives the fragment; the sweep must be reversible even if the
            // reader has already wandered off this screen.
            InboxStore.restore(ctx.applicationContext, swept)
            if (isAdded && kindFilter == KIND_MAIL) submitMailRows()
        }.show()
    }

    /** The mail star, both directions — [MailVerbs] owns the ceremony (to-do + All Stars gram on
     *  the way up, the cheap un-keep on the way down); this reflects it into the row in place,
     *  never through refresh() (which would close an open letter). */
    private fun toggleMailStar(entry: FeedEntry, m: InboxMessage) {
        val ctx = requireContext()
        if (entry.starred) {
            com.toolsboox.plugin.mail.ui.MailVerbs.unstar(ctx, m)
            showMessage(R.string.feeds_unstarred)
            reflectStar(entry, false)
        } else {
            com.toolsboox.plugin.mail.ui.MailVerbs.starToTodo(this, calendarDayService, documentsRoot(), m) { placedGram ->
                if (!isAdded) return@starToTodo
                showMessage(
                    when (placedGram) {
                        true -> "★ → All Stars"
                        false -> "★ already on All Stars"
                        null -> "Starred"
                    }, binding.root
                )
                reflectStar(entry, true)
            }
        }
    }

    /** Open a letter: mark it read (per the mark-read setting, like any row), hydrate its body
     *  off disk if the store only holds the envelope, then read it in the pane. */
    private fun openMailEntry(
        entry: FeedEntry, m: InboxMessage, backTo: InboxMessage? = null,
        /** A thread card's open (or its "‹ Back"): the list's place ([lastMailId], the hop's
         *  [mailThreadOrigin]) stays where the triage left it. */
        fromCard: Boolean = false,
    ) {
        // Any open but a thread card's forgets the way back — the list is "back" again.
        mailThreadBack = backTo
        if (markReadMode() != "off") markEntryRead(entry)
        if (!fromCard) { mailThreadOrigin = null; lastMailId = entry.id }
        mailImagesOn = mailPrefs().getBoolean(MAIL_PREF_PICTURES, true)
        mailParsed = !mailPrefs().getBoolean(MAIL_PREF_AS_SENT, true)
        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) { InboxStore.hydrate(requireContext().applicationContext, m) }
            if (!isAdded) return@launch
            mailById[entry.id] = full
            openMail = entry.id to full
            showMailInPane(entry, full)
        }
    }

    /**
     * Dress the pane's WebView for a letter, then enter the pane through the same door articles
     * use. A letter runs NO code and loads NO remote images until asked. The images half is
     * [MailReaderHtml.blockRemote] plus the settings, because a tracking pixel's whole payload is
     * the request. The code half USED to be `javaScriptEnabled = false` — which also switched off
     * the highlight probe, so a letter could never offer "❝ Save highlight" (09-06 field report:
     * "in the email reader, it doesn't give me just the option to leave the highlighted text").
     * Scripts are on now so the [WebSelectionBridge] can read the passage; the no-code promise
     * is kept by [MailReaderHtml.disarm] (every script, handler, frame and code-scheme URL out of
     * the markup before it renders), a null origin, and no file/content access. Link taps get
     * the same menu the article reader gives them; the ledger:// doors below are the letter's
     * own — load images, attachments, and fetch the rest of a truncated message.
     */
    private fun showMailInPane(entry: FeedEntry, m: InboxMessage) {
        // A letter wears no article marks (letters are a later step of the highlights plan).
        articleHighlights.open(null)
        FeedSelection.entry = entry
        FeedSelection.list = adapter.current()
        binding.articleWeb.settings.apply {
            javaScriptEnabled = true      // for the selection bridge only — the letter itself is disarmed
            allowFileAccess = false       // the article path's offline image cache is not a letter's to read
            allowContentAccess = false
            domStorageEnabled = false
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.articleWeb.webChromeClient = android.webkit.WebChromeClient()
        binding.articleWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                // The highlight must survive the pen-nib tap on ✍ — same race, same fix as the
                // article dress (see WebSelectionBridge).
                com.toolsboox.plugin.feeds.ot.WebSelectionBridge.wire(view)
            }
            // PICTURES OFF MEANS NO REQUEST AT ALL (2026-10-10 review). blockNetworkImage stops
            // the images, but a letter's own CSS could still fetch — @import, @font-face
            // url(https://…) — a tracking request the switch never covered. With pictures off,
            // every web request the page makes gets an empty answer; your ink is a data: image
            // and never asks. Off the main thread, hence the @Volatile switch.
            override fun shouldInterceptRequest(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                // The reading faces are the app's own bytes, never a fetch — served pictures on
                // or off (Aa Type, Michael 2026-10-11).
                if (request?.url?.host == com.toolsboox.ot.ReadingFonts.HOST) {
                    return context?.let { com.toolsboox.ot.ReadingFonts.intercept(it, request.url) }
                }
                if (mailImagesOn) return null
                val scheme = request?.url?.scheme?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                if (request?.isForMainFrame == false) return false
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith(MAIL_IMAGES_SCHEME)) { mailImagesOn = true; renderArticle(); return true }
                if (url.startsWith(MAIL_MORE_SCHEME)) { loadRestOfMail(); return true }
                if (url.startsWith(MAIL_ATT_SCHEME)) {
                    url.removePrefix(MAIL_ATT_SCHEME).toIntOrNull()?.let { viewMailAttachment(it) }
                    return true
                }
                if (url.startsWith(MAIL_THREAD_SCHEME)) { openThreadLetter(url.removePrefix(MAIL_THREAD_SCHEME)); return true }
                if (url.startsWith("mailto:", ignoreCase = true)) { composeFromMailto(url); return true }
                if (url.startsWith("http")) { showPaneLinkMenu(url); return true }
                return false
            }
        }
        binding.articleWeb.setOnLongClickListener(null)
        // The row's content is only the snippet; hand the reader actions (TTS above all) the
        // whole letter. Same id, so stars and the prev/next stepping still find the row.
        val esc = m.body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        enterArticlePane(entry.copy(content = "<p>${esc.replace("\n", "<br/>")}</p>"))
    }

    /** The letter in the pane, through the mail reader's own shaping. The parsed toggle (rail's
     *  Reader view) means Reader (reflowed for e-ink) vs As-sent (the sender's design); a plain-
     *  text letter renders as a quiet serif column either way. */
    private fun renderMailArticle(e: FeedEntry, m: InboxMessage) {
        val html = buildMailHtml(e, m)
        val safe = if (mailImagesOn) html else MailReaderHtml.blockRemote(html)
        binding.articleWeb.settings.blockNetworkImage = !mailImagesOn
        // ALWAYS on (2026-10-10 review): off, it also blanks images embedded IN the page — your
        // own ink, on your handwritten letter and its thread card, which make no request. The
        // network half stays shut by blockNetworkImage, blockRemote and the mail client's
        // shouldInterceptRequest (showMailInPane).
        binding.articleWeb.settings.loadsImagesAutomatically = true
        // No baseUrl on purpose (the retired mail reader's choice too): mail markup gets no origin,
        // no referer, and nothing to resolve relative fetches against.
        paintArticleGround(false)
        binding.articleWeb.loadDataWithBaseURL(null, safe, "text/html", "UTF-8", null)
    }

    /** The letter as a document: sender · date (and the images/truncated doors) over the body. */
    private fun buildMailHtml(e: FeedEntry, m: InboxMessage): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sent = InboxStore.isSent(m.id)
        val crm = if (sent) InboxStore.crmTag(requireContext(), m.id) else ""
        val who = (if (sent) "To " + m.toName.ifBlank { m.toEmail }.ifBlank { m.account }
        else listOf(m.fromName, m.fromEmail).filter { it.isNotBlank() }.joinToString("  ·  ")) + (if (crm.isNotBlank()) "  ·  via FluentCRM · $crm" else "")
        val date = java.time.Instant.ofEpochMilli(m.date).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.getDefault()))
        val doors = buildString {
            if (!mailImagesOn && m.html.isNotBlank() && MailReaderHtml.hasRemoteRefs(m.html)) {
                append("<div style=\"margin:4px 0\"><a href=\"$MAIL_IMAGES_SCHEME\" style=\"color:#000\">🖼  Images not loaded — tap to load</a></div>")
            }
            // 📎 THE ATTACHMENTS, finally said (Michael, iOS field, same absence here: the parser
            // used to drop the parts, so the letter view could never know a file existed). A chip
            // per file; the tap fetches THAT message whole and carves the one part — the refresh
            // never pulls attachment bytes.
            m.attachments.forEachIndexed { i, a ->
                val size = when {
                    a.size >= 1_000_000 -> "%.1f MB".format(a.size / 1_000_000f)
                    a.size >= 1_000 -> "${a.size / 1_000} KB"
                    else -> "${a.size} B"
                }
                append("<div style=\"margin:4px 0\"><a href=\"$MAIL_ATT_SCHEME$i\" style=\"color:#000\">📎  ${esc(a.name)} · $size</a></div>")
            }
            if (m.truncated) {
                // The fetch was bounded on purpose (usually attachments); say so, or a cut-off
                // body reads as the parser failing. Tappable only when there's a server id to
                // fetch the rest by — the honest, un-tappable note otherwise.
                if (m.uid != null && MailSync.accountId(m.id) != null) {
                    append("<div style=\"margin:4px 0\"><a href=\"$MAIL_MORE_SCHEME\" style=\"color:#000\">✂  Large message — tap to load the rest</a></div>")
                } else {
                    append("<div style=\"margin:4px 0;color:#666\">✂  Large message — only the beginning was fetched.</div>")
                }
            }
        }
        // THE CONVERSATION (2026-10-10, M1 "see what you sent, and the thread"): did you answer
        // it, and when — on the letter's own header — and the rest of the exchange under it.
        val thread = MailThreads.of(m, InboxStore.messages(requireContext()))
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault())
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(dayFmt)
        // Said only when the letter IS answered — the rule its list row's ↩ follows, so the two
        // never disagree (2026-10-10 review); the date when a sent row names the reply.
        val repliedLine = if (sent || !MailThreads.isAnswered(requireContext(), m)) ""
        else MailThreads.yourReplyAfter(m, thread)?.let { "↩ You replied · ${day(it.date)}" } ?: "↩ Replied"
        val backLine = mailThreadBack?.takeIf { it.id != m.id }?.let { b ->
            val name = b.fromName.ifBlank { b.fromEmail }
            val whose = when { InboxStore.isSent(b.id) -> "your"; name.isBlank() -> "their"; else -> "$name's" }
            "<div style=\"margin:0 0 6px\"><a href=\"${MAIL_THREAD_SCHEME}back\" style=\"color:#000\">‹  Back to ${esc(whose)} letter</a></div>"
        }.orEmpty()
        val header = """
            <div style="font-family:serif;padding:10px 12px;border-bottom:1px solid #000;">
              $backLine
              <div style="font-size:13px;color:#444">${esc(listOf(who, date).filter { it.isNotBlank() }.joinToString("  ·  "))}</div>
              ${if (repliedLine.isNotBlank()) "<div style=\"font-size:14px;color:#000;margin-top:4px\"><b>${esc(repliedLine)}</b></div>" else ""}
              $doors
            </div>
        """.trimIndent()
        val strip = mailThreadStrip(m, thread, ::day)
        val body = if (m.html.isNotBlank()) {
            if (mailParsed) MailReaderHtml.reader(m.html) else MailReaderHtml.asSent(m.html)
        } else {
            // Plain text (or nothing readable): a quiet serif column, never a blank pane.
            val text = m.body.ifBlank {
                m.snippet.ifBlank { "(No readable text in this message — it may be images or attachments only.)" }
            }
            val paras = text.split(Regex("\n{2,}")).filter { it.isNotBlank() }
                .joinToString("") { "<p>${esc(it.trim()).replace("\n", "<br/>")}</p>" }
            """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
               <style>body{margin:16px 22px;color:#000;background:#fff;font-family:serif;font-size:17px;line-height:1.55}</style>
               </head><body>$paras</body></html>"""
        }
        // The header rides INSIDE the shaped document (which owns the <head> and the viewport),
        // right after its body opens — prepending outside would break a full-document letter.
        val bodyOpen = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(body)
        val headed = if (bodyOpen != null) {
            body.substring(0, bodyOpen.range.last + 1) + header + body.substring(bodyOpen.range.last + 1)
        } else header + body
        // The thread rides UNDER the letter, just before the document closes.
        if (strip.isEmpty()) return headed
        val close = headed.lastIndexOf("</body>", ignoreCase = true)
        return if (close >= 0) headed.substring(0, close) + strip + headed.substring(close) else headed + strip
    }

    /**
     * "Earlier in this conversation" — the other letters of [m]'s exchange as cards under it,
     * yours marked ➤ (2026-10-10, M1). Before the 🧵 per-person list this was three taps away and
     * plain text only; now it's under the letter, and a card's tap opens that letter here with
     * its ink. Cards are TEXT (critique P5: earlier letters' markup could carry trackers and
     * megabytes), except your own handwriting — its first page shows small on the two newest of
     * your handwritten letters, since a data: image makes no request.
     */
    private fun mailThreadStrip(m: InboxMessage, thread: List<InboxMessage>, day: (Long) -> String): String {
        mailThreadLinks.clear()
        val others = thread.filter { it.id != m.id }
        if (others.isEmpty()) return ""
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val inkPage = Regex("""<img src="(data:image/(?:png|jpeg);base64,[A-Za-z0-9+/=]+)"""")
        val handPage = Regex("""alt="handwritten""")
        val inkTargets = others.filter { InboxStore.isSent(it.id) && handPage.containsMatchIn(it.html) }
            .sortedByDescending { it.date }.take(2).map { it.id }.toSet()
        return buildString {
            fun card(t: InboxMessage) {
                val key = Integer.toHexString(t.id.hashCode()) + "-" + t.id.length
                mailThreadLinks[key] = t.id
                val mine = InboxStore.isSent(t.id)
                val who = if (mine) "➤ You" else t.fromName.ifBlank { t.fromEmail }.ifBlank { "Them" }
                val pages = if (mine) handPage.findAll(t.html).count() else 0
                val words = t.snippet.takeIf { it.isNotBlank() && !it.startsWith("(handwritten") }
                val raw = when {
                    pages > 0 && words == null -> "✍ handwritten letter" + (if (pages > 1) " · $pages pages" else "")
                    pages > 0 -> "✍ $words"
                    else -> words ?: t.subject
                }
                val line = if (raw.length > 110) raw.take(110).trimEnd() + "…" else raw
                append("<a href=\"$MAIL_THREAD_SCHEME$key\" style=\"display:block;color:#000;text-decoration:none;border:1px solid #000;padding:8px 10px;margin:6px 0\">")
                append("<div style=\"font-size:13px;color:#444\">${esc(who)}  ·  ${esc(day(t.date))}</div>")
                append("<div style=\"font-size:15px;line-height:1.4\">${esc(line)}</div>")
                if (t.id in inkTargets) inkPage.find(t.html)?.groupValues?.get(1)?.takeIf { it.length <= 700_000 }?.let { src ->
                    append("<img src=\"$src\" alt=\"handwritten\" style=\"display:block;max-width:100%;max-height:170px;margin-top:6px;border:1px solid #000\"/>")
                }
                append("</a>")
            }
            fun heading(t: String) =
                append("<div style=\"font-size:13px;color:#444;margin:10px 0 2px\">${esc(t)}</div>")
            val before = others.filter { it.date <= m.date }
            val after = others.filter { it.date > m.date }
            append("<div style=\"font-family:serif;margin:22px 12px 28px;padding-top:6px;border-top:2px solid #000\">")
            if (before.isNotEmpty()) {
                heading("Earlier in this conversation")
                // A long exchange shows its nearest dozen; the rest is a card's tap away.
                if (before.size > 12) heading("… ${before.size - 12} earlier")
                before.takeLast(12).forEach { card(it) }
            }
            if (after.isNotEmpty()) {
                heading("Since this letter")
                after.take(12).forEach { card(it) }
                if (after.size > 12) heading("… ${after.size - 12} more")
            }
            append("</div>")
        }
    }

    /** A thread card's tap (or "‹ Back"): open that letter in this pane, remembering the one it
     *  came from so the way back is one tap. */
    private fun openThreadLetter(key: String) {
        val ctx = context ?: return
        val from = currentArticle?.let { mailById[it.id] }
        val targetId = if (key == "back") mailThreadBack?.id else mailThreadLinks[key]
        val t = targetId?.let { id -> InboxStore.messages(ctx).firstOrNull { it.id == id } }
        if (t == null) { showMessage("That letter isn't on this device any more", binding.root); return }
        // The first hop remembers the list row it left; "‹ Back" onto that row ends the hop.
        if (mailThreadOrigin == null) mailThreadOrigin = currentArticle?.id
        val entry = mailEntry(t)
        openMailEntry(entry, t, backTo = if (key == "back") null else from,
            fromCard = !(key == "back" && entry.id == mailThreadOrigin))
    }

    /** Fetch the whole of a truncated letter through the one on-demand path ([MailSync.fetchFull],
     *  which also lands it in the store), and re-render in place. */
    private fun loadRestOfMail() {
        val entry = currentArticle ?: return
        val m = mailById[entry.id] ?: return
        showMessage("✂ Loading the rest…", binding.root)
        lifecycleScope.launch {
            val full = runCatching { withContext(Dispatchers.IO) { MailSync.fetchFull(requireContext(), m) } }
            if (!isAdded) return@launch
            full.onSuccess {
                mailById[entry.id] = it
                if (openMail?.first == entry.id) openMail = entry.id to it
                if (currentArticle?.id == entry.id) renderArticle()
            }.onFailure {
                showMessage("✂ Couldn't load the rest (${it.message ?: "fetch failed"})", binding.root)
            }
        }
    }

    /**
     * View one attachment: fetch the message whole (the tap IS the consent to the bytes), carve
     * the part, cache it, and hand it to the system viewer through the FileProvider — images and
     * PDFs open in place, anything else falls back to the share sheet. Cached by message+name so
     * a re-tap costs nothing.
     */
    private fun viewMailAttachment(index: Int) {
        val entry = currentArticle ?: return
        val m = mailById[entry.id] ?: return
        val att = m.attachments.getOrNull(index) ?: return
        val ctx = requireContext()
        val safeName = att.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
        val dir = java.io.File(ctx.cacheDir, "mail-att").apply { mkdirs() }
        val cached = java.io.File(dir, "${m.id.hashCode()}-$safeName")
        fun open(file: java.io.File, mime: String) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file)
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifBlank { "*/*" })
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(view)
            } catch (e: android.content.ActivityNotFoundException) {
                // Nothing views this type — offer it onward instead of a dead tap.
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mime.ifBlank { "application/octet-stream" }
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, att.name))
            }
        }
        if (cached.exists() && cached.length() > 0) { open(cached, att.mime); return }
        showMessage("📎 Fetching ${att.name}…", binding.root)
        lifecycleScope.launch {
            val got = runCatching {
                withContext(Dispatchers.IO) {
                    val (bytes, mime) = MailSync.attachmentBytes(requireContext(), m, att.name)
                    cached.writeBytes(bytes)
                    mime
                }
            }
            if (!isAdded) return@launch
            got.onSuccess { mime -> open(cached, mime.ifBlank { att.mime }) }
                .onFailure { showMessage("📎 Couldn't fetch it (${it.message ?: "fetch failed"})", binding.root) }
        }
    }

    /** Reply to the open letter — [MailVerbs.replyDoors] opens the one compose screen straight in
     *  (Michael 2026-10-11), pen and keyboard together, with 🃏 Draft on pickings in its top bar.
     *  Coming back reopens the letter through the trip stash, its thread holding the reply. */
    private fun replyToOpenMail() {
        val m = openLetter() ?: return
        com.toolsboox.plugin.mail.ui.MailVerbs.replyDoors(this, calendarDayService, documentsRoot(), m,
            fromAccount = composeFrom()) { afterMailReply() }
    }

    /**
     * After a reply, the letter says so AT ONCE — "↩ You replied · today" on its header and the
     * reply in its thread strip (2026-10-10 review; iOS reloads the thread on send too) — and
     * the list repaints its ↩: now if it's showing, or the moment the pane closes over it.
     */
    private fun afterMailReply() {
        if (!isAdded) return
        if (binding.articlePane.visibility == View.VISIBLE) {
            if (kindFilter == KIND_MAIL) mailRowsStale = true
            if (currentArticle?.let { mailById[it.id] } != null) renderArticle()
        } else if (kindFilter == KIND_MAIL) submitMailRows()
    }

    /** The accounts editor, through [MailVerbs] — the letter ceremonies' shared home. The Hub's
     *  "@ Mail accounts…" opened over this room comes here too, so deleting the account the room
     *  is narrowed to lets go of it at once (2026-10-11 review). */
    internal fun showMailAccounts() {
        com.toolsboox.plugin.mail.ui.MailVerbs.showAccountsList(
            this,
            onChanged = { if (kindFilter == KIND_MAIL) { renderDirectory(); refresh() } else renderDirectory() },
            onDeleted = { id ->
                // Never leave the lens narrowed to an account that no longer exists — its Inbox or
                // one of its piles.
                if (MailPlace.accountOf(mailMailbox) == id) setMailMailbox(null)
                com.toolsboox.plugin.mail.JunkMail.forget(id)
                if (kindFilter == KIND_MAIL) { renderDirectory(); refresh() } else renderDirectory()
            }
        )
    }

    /** Step FROM THE LIST — the list's « » (09-11: up top with the page turns). The reading rail's
     *  « » continue from the open piece via openAdjacentArticle; these pick the thread back up from
     *  the last piece read (or the top of the pile when nothing has been). The Mail steps email to
     *  email, RSS article to article (Michael 2026-10-11: one rail, so RSS's list gains « » too). */
    private fun stepFromList(dir: Int) {
        val mail = kindFilter == KIND_MAIL
        // Only real letters count as steps — the 🔎 server-search row is a control in the list,
        // and "next email" landing on a search button would read as the jump breaking.
        val list = if (mail) adapter.current().filter { mailById.containsKey(it.id) } else adapter.current()
        if (list.isEmpty()) {
            showMessage(if (dir > 0) R.string.feeds_no_next else R.string.feeds_no_prev, binding.root)
            return
        }
        val i = (if (mail) lastMailId else lastArticleId)?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        val j = if (i < 0) (if (dir > 0) 0 else list.size - 1) else i + dir
        if (j in list.indices) openEntry(list[j])
        else showMessage(if (dir > 0) R.string.feeds_no_next else R.string.feeds_no_prev, binding.root)
    }

    /**
     * One seat of [IncomingRail] as its button. Every action drives the hidden pill button where
     * one exists, so the single wiring the pill had keeps working for anything else that
     * performClicks it (the day-page rule).
     *
     * The Mail's seats are the verbs the retired standalone screen's rail carried — 🧹 sweep (only
     * where sweeping means something), ✉ compose, @ accounts — and, with a letter open, the mail
     * verbs that act on it plus the knowledge-graph moves that screen's letter dialog used to
     * carry: 🏷 tag, 📥 to synth (through [MailVerbs] — an email joins the graph like any other
     * Ledger object), ● Read / Unread in the seat 🕸 Rhizome held (Michael 2026-10-11: Rhizome
     * leaves mail), with 🗑 delete as the deliberate single-message override the sweep never is.
     * ↑↓ page WITHIN a piece and « » cross to the previous/next one, in both lenses.
     *
     * One glyph, one meaning (Michael 2026-10-11): ✎ is Note only (compose wears ✉), ❖ is the
     * engines only (the letter's assign-to-synthesis wears 📥), 🔖 is Later only (the letter's
     * links wear 🔗), the gear is setup only (accounts wear @), and Read / Unread wears the row's
     * own unread dot — ● Unread / ○ Read — never compose's ✉ (review 2026-10-11).
     */
    private fun railItem(seat: IncomingRail.Seat, mail: Boolean): com.toolsboox.ot.TuckPanel.Item = when (seat) {
        // (THE ROOM'S DRAWER seat — "Mail" here, "Feeds" in Feeds — left the column 2026-10-12,
        // Michael: "Those too." The drawer is the left edge's first Back ([openEdgePane]) and
        // More's second row, under ☰ Hub.)
        IncomingRail.Seat.TODAY -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_today, "Today") {
            binding.feedsGoto.performClick()
        }
        IncomingRail.Seat.PAGE_UP -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up, "Page up") {
            binding.feedsPageUp.performClick()
        }
        IncomingRail.Seat.PAGE_DOWN -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down, "Page down") {
            binding.feedsPageDown.performClick()
        }
        // Prev/next WITH the page turns, up top where the thumb rests (Michael, 09-11: "up and down
        // pagination near the top middle and article next / article back with it, for
        // ergonomics") — in The Mail too now, where they used to sit at the foot.
        IncomingRail.Seat.PREV -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_up2,
            if (mail) "Previous email" else getString(R.string.feeds_prev_article)) {
            if (currentArticle != null) binding.feedsDwPrev.performClick() else stepFromList(-1)
        }
        IncomingRail.Seat.NEXT -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_nav_down2,
            if (mail) "Next email" else getString(R.string.feeds_next_article)) {
            if (currentArticle != null) binding.feedsDwNext.performClick() else stepFromList(1)
        }
        IncomingRail.Seat.REFRESH -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_refresh, getString(R.string.feeds_refresh)) {
            binding.refreshButton.performClick()
        }
        IncomingRail.Seat.MORE -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_wrench, getString(R.string.feeds_settings)) {
            binding.settingsButton.performClick()
        }
        // 🔍 SUMMON THE LIST SEARCH (09-14) — on the rail, off by default; see listSearchSummoned.
        IncomingRail.Seat.SEARCH -> com.toolsboox.ot.TuckPanel.Item(0,
            if (listSearchSummoned()) "Put the search away" else "Search this feed",
            glyph = if (listSearchSummoned()) "🔍✓" else "🔍", caption = "Search") { toggleListSearch() }
        // 👁 THE READ SCOPE (09-14: "I don't have an ability to show read/unread from the rail"):
        // Unread → Read → Everything. The list, the search and the page steps all live in it.
        IncomingRail.Seat.SHOW -> com.toolsboox.ot.TuckPanel.Item(0,
            when (mode) { "read" -> "Showing read · tap for everything"; "both" -> "Showing everything · tap for unread"; "stars" -> "Showing starred · tap for unread"; else -> "Showing unread · tap for read" },
            glyph = when (mode) { "read" -> "👁✓"; "both" -> "👁∞"; "stars" -> "👁★"; else -> "👁" },
            // Show's caption is its state (plan v6 §1: "👁 (caption = state)").
            caption = when (mode) { "read" -> "Read"; "both" -> "All"; "stars" -> "Starred"; else -> "Unread" }) { cycleReadScope() }
        IncomingRail.Seat.STAR -> com.toolsboox.ot.TuckPanel.Item(
            if (currentArticle?.starred == true) R.drawable.ic_starred else R.drawable.ic_star,
            "Star") { binding.feedsStar.performClick() }
        IncomingRail.Seat.NOTE -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_pencil, "Note") {
            binding.feedsNote.performClick()
        }
        // 🔎 sits third among the reading verbs, straight after ★ and ✎, whichever the pane holds.
        // High up on purpose: "where does he say that" is a question you ask early in a piece,
        // long before you'd reach past Reader view and read-aloud for it. The find's own ‹ › step
        // matches; « » keep meaning piece to piece.
        IncomingRail.Seat.FIND -> com.toolsboox.ot.TuckPanel.Item(0, getString(R.string.feeds_find), glyph = "🔎") {
            showArticleFind()
        }
        IncomingRail.Seat.READER -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_reader_view, "Reader view") {
            binding.feedsParsed.performClick()
        }
        // Aa Type, one tap — beside Reader view, as the book reader's Aa seat and the iOS
        // article rail's "Aa" (Michael 09-11: "more clearly a reader setting situation"). It
        // was wrench → Aa Type… → the dial (review, Michael 2026-10-11); that row stays too.
        IncomingRail.Seat.TYPE -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_text_size, getString(R.string.aa_type)) { openAaType() }
        IncomingRail.Seat.LISTEN -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_speaker, "Read aloud") {
            binding.feedsTts.performClick()
        }
        // The sweep only exists where sweeping means something — the retired screen's rule
        // (absent on the starred pile and Sent, where every row is keep-forever); see IncomingRail.
        IncomingRail.Seat.SWEEP -> com.toolsboox.ot.TuckPanel.Item(0, "Sweep unstarred", glyph = "🧹") { sweepMail() }
        // ✉ THE ONE COMPOSE (Michael 2026-10-11): ✎ Compose and ✍ Letter folded into one door —
        // the one screen takes the pen on its pad and a finger on its body. It is handed the Mail
        // view's account (the From rule's second step), never the old account_filter setting;
        // the Sent pile names no account, so From falls to the pinned default there.
        IncomingRail.Seat.COMPOSE -> com.toolsboox.ot.TuckPanel.Item(0, "Compose", glyph = "✉") {
            com.toolsboox.plugin.mail.ui.MailComposeFragment.open(
                this, fromAccount = composeFrom())
        }
        IncomingRail.Seat.ACCOUNTS -> com.toolsboox.ot.TuckPanel.Item(0, "Accounts", glyph = "@") { showMailAccounts() }
        IncomingRail.Seat.REPLY -> com.toolsboox.ot.TuckPanel.Item(0, "Reply", glyph = "↩") { replyToOpenMail() }
        IncomingRail.Seat.TAG -> com.toolsboox.ot.TuckPanel.Item(0, "Tag", glyph = "🏷") {
            openLetter()?.let { com.toolsboox.plugin.mail.ui.MailVerbs.tag(this, it) }
        }
        // The letter into the pile you SORT from — star feeds the intake board, this mints it
        // as a pick in All Picks (THE RULING 08-28: saved picks go to All Picks first, and can
        // be sent to a note from there).
        IncomingRail.Seat.PICK -> com.toolsboox.ot.TuckPanel.Item(0, "Send to All Picks", glyph = "◈") {
            openLetter()?.let {
                com.toolsboox.plugin.mail.ui.MailVerbs.intoAllPicks(
                    this, calendarDayService, documentsRoot(), it
                ) { placed ->
                    if (isAdded) showMessage(when (placed) {
                        true -> "In All Picks"
                        false -> "Already in All Picks"
                        else -> "Couldn't place it"
                    }, binding.root)
                }
            }
        }
        IncomingRail.Seat.TO_SYNTH -> com.toolsboox.ot.TuckPanel.Item(0, "Assign to synthesis", glyph = "📥") {
            openLetter()?.let {
                com.toolsboox.plugin.mail.ui.MailVerbs.assignToSynthesis(
                    this, calendarDayService, documentsRoot(), it
                ) { filed ->
                    if (isAdded) showMessage(
                        if (filed) "Filed to today's Synthesize page" else "Nothing to file", binding.root)
                }
            }
        }
        // In and out with this correspondent, one thread (DESIGN-notes-room-2026-08-27 §3.2).
        IncomingRail.Seat.THREAD -> com.toolsboox.ot.TuckPanel.Item(0, "All our letters", glyph = "🧵") {
            openLetter()?.let { com.toolsboox.plugin.mail.ui.MailVerbs.correspondenceWith(this, it, calendarDayService, documentsRoot()) }
        }
        // 🔗 The letter's links, filed (09-03: the third of the three surfaces).
        IncomingRail.Seat.LINKS -> com.toolsboox.ot.TuckPanel.Item(0, "Links → Later", glyph = "🔗") {
            openLetter()?.let { m ->
                val links = Regex("""https?://[^\s"'<>)\]]+""")
                    .findAll(m.body.ifBlank { m.snippet })
                    .map { it.value.trimEnd('.', ',') }.distinct().take(40).toList()
                if (links.isEmpty()) showMessage("No links in this letter", binding.root)
                else {
                    val appCtx = requireContext().applicationContext
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (u in links) runCatching {
                            com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                                appCtx, java.time.LocalDate.now(), "read", u, u)
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isAdded) showMessage("🔗 ${links.size} → Later", binding.root)
                        }
                    }
                }
            }
        }
        // ● READ / UNREAD where 🕸 Rhizome stood (Michael 2026-10-11: "Rhizome still doesn't sound
        // useful" → "Ok" — off mail only; tasks, contacts, the Map and Missed Rhizomes keep it). The
        // iOS mail row's swipe wears the same two words. The glyph and caption are what a tap makes
        // it: ● (the row's unread dot) for Unread, ○ for Read. Not on a letter you sent, in Trash
        // or in Junk ([IncomingRail.seats]).
        IncomingRail.Seat.UNREAD -> {
            val read = openLetterIsRead()
            com.toolsboox.ot.TuckPanel.Item(0, if (read) "Mark unread" else "Mark read", glyph = if (read) "●" else "○",
                caption = if (read) "Unread" else "Read") {
                val e = currentArticle
                val m = openLetter()
                if (e != null && m != null) setMailRead(e, m, read = !openLetterIsRead())
            }
        }
        IncomingRail.Seat.DELETE -> com.toolsboox.ot.TuckPanel.Item(0, "Delete", glyph = "🗑") { deleteOpenMail() }
        // ⤴ on a 🗑 Trash letter, in Delete's seat — the letter back where it was (Michael
        // 2026-10-11). One verb everywhere: "Move back" (the hold menu's and iOS's word).
        IncomingRail.Seat.RESTORE -> com.toolsboox.ot.TuckPanel.Item(0, "Move back", glyph = "⤴") { restoreOpenMail() }
        IncomingRail.Seat.LATER -> com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_go_later, getString(R.string.feeds_add_later)) {
            binding.feedsLater.performClick()
        }
        // ❖ the engines over THIS piece — the list's chip, on the reader too (Michael, 09-11).
        // Its readable copy (the cache's parsed text, else the entry's own content) into the
        // engine picker; the result lands as a new note, as the Log's hold does.
        // and over THE LIST when nothing is open (synth-list-links §7 — iOS's UnifiedInboxView
        // "Synthesize this list"): letters in, a Synthesize page whose cards open them out. Never the
        // article verb with no article open — that returned without a word (review 2026-10-12).
        IncomingRail.Seat.SYNTH -> if (currentArticle == null)
            com.toolsboox.ot.TuckPanel.Item(0, listSynthKind().verb, glyph = "❖") { synthesizeList() }
        else com.toolsboox.ot.TuckPanel.Item(0, com.toolsboox.plugin.calendar.ot.SynthSources.Kind.ARTICLE.verb, glyph = "❖") { synthesizeOpenArticle() }
    }

    /** Whether the open letter reads as read — the row's own rule (sent mail is never unread). */
    private fun openLetterIsRead(): Boolean {
        val e = currentArticle ?: return true
        val m = mailById[e.id] ?: return true
        return InboxStore.isSent(m.id) || InboxStore.isRead(requireContext(), m.id) || FeedReadState.isRead(e.id)
    }

    /**
     * ● Read / Unread for one letter (Michael 2026-10-11) — the letter's rail seat and the row's
     * hold. Read is the one mark-read path every open and scroll already takes ([markEntryRead]);
     * Unread takes the letter out of this device's read set ([InboxStore.markUnread]) and un-greys
     * its row in place. Per device, like every read mark on both apps.
     */
    private fun setMailRead(entry: FeedEntry, m: InboxMessage, read: Boolean) {
        // A letter you sent always reads as read (the row's rule): say so rather than claim a
        // change that the next redraw would undo.
        if (!read && InboxStore.isSent(m.id)) {
            showMessage("A letter you sent is never unread", binding.root)
            return
        }
        if (read) markEntryRead(entry)
        else {
            InboxStore.markUnread(requireContext(), m.id)
            FeedReadState.unmark(entry.id)
            allEntries = allEntries.map { if (it.id == entry.id) it.copy(read = false) else it }
            adapter.setRead(entry.id, false)
            if (currentArticle?.id == entry.id) currentArticle = currentArticle?.copy(read = false)
            redressRail()
        }
        showMessage(if (read) "○ Read" else "● Unread", binding.root)
    }

    /** The letter under the open pane, whole where a load-the-rest already ran — [mailById] holds
     *  the freshest copy of it, and every letter verb should act on that, never a stale slice. */
    /** The open letter, or null WITH the reason said aloud — the rail's letter verbs (Tag,
     *  Read / Unread, Assign, Delete) all gate on this, and a silent null was "buttons in RSS Reader
     *  don't go anywhere" (08-16 page): the verb has to say what it needs, not swallow the tap. */
    private fun openLetter(): InboxMessage? {
        val m = currentArticle?.let { mailById[it.id] }
        if (m == null) showMessage("Open a letter first — this acts on the open email", binding.root)
        return m
    }

    /** Delete the OPEN letter for good — the deliberate single-message override the 🧹 sweep
     *  refuses to be (the sweep spares starred, replied-to and sent mail; delete takes any letter,
     *  kept or not — the retired screen offered it on a row's long-press, and losing the verb with
     *  the screen would have left no way to remove one email). One held breath first, then the
     *  pane closes over the list it came from. */
    private fun deleteOpenMail() {
        val m = openLetter() ?: return
        // Already in 🗑 Trash: say so — never a quiet Move back under a button that says Delete
        // (2026-10-11 review). The rail wears ⤴ for a cleared letter, so only a stale tap lands here.
        if (InboxStore.isCleared(requireContext(), m.id)) {
            showMessage("Already in 🗑 Trash — ⤴ Move back puts it back", binding.root); return
        }
        // 🚫 Junk is read straight off the server, read-only: nothing here can delete it there.
        if (com.toolsboox.plugin.mail.JunkMail.isJunk(m.id)) {
            showMessage("🚫 Junk is read-only here — delete it from the account's own mail app", binding.root); return
        }
        val ctx = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Delete this email?")
            .setMessage(m.subject.ifBlank { m.fromName.ifBlank { m.fromEmail } })
            .setPositiveButton("Delete") { _, _ ->
                InboxStore.clear(ctx, listOf(m.id))
                closeArticlePane()
                if (kindFilter == KIND_MAIL) submitMailRows()
                showMessage("Deleted — in 🗑 Trash, ⤴ puts it back", binding.root)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** ⤴ MOVE BACK — the open 🗑 Trash letter returns where it was (Michael 2026-10-11: "accounts,
     *  starred, inbox, sent, trash"). The same restore the sweep's Undo uses: the letter's cleared
     *  mark comes off, nothing else changes (it was never touched on a server). */
    private fun restoreOpenMail() {
        val m = openLetter() ?: return
        InboxStore.restore(requireContext(), listOf(m.id))
        closeArticlePane()
        if (kindFilter == KIND_MAIL) submitMailRows()
        showMessage("⤴ Back in ${movedBackTo(m)}", binding.root)
    }

    /** Where ⤴ Move back puts [m] — a letter you wrote goes back to ➤ Sent (🗑 Delete takes any
     *  letter, Sent ones too), every other to the Inbox (2026-10-11 review: "Back in the Inbox"
     *  was wrong for a deleted Sent letter). */
    private fun movedBackTo(m: InboxMessage): String = if (InboxStore.isSent(m.id)) "➤ Sent" else "the Inbox"

    /** Saved Ask-my-Ledger answers, surfaced as a local feed. */
    private fun loadAskLog() {
        val entries = com.toolsboox.plugin.feeds.nw.AskFeedStore.list(requireContext())
        allEntries = entries
        corpusKey = "asklog"
        adapter.submit(applyKind(entries))
        if (entries.isEmpty()) showEmpty("No saved answers yet — save one from Ask my Ledger.")
        else binding.emptyText.visibility = View.GONE
    }

    /**
     * The Later List's rows. The synthesis itself moved to
     * [com.toolsboox.plugin.feeds.nw.LaterFeed] when the list grew a star and a delete: those two
     * verbs need a way back from a row to the LINE it was read out of, and a source that has to
     * remember something is a thing with a name rather than a private helper on a screen. This
     * stays as the fragment's one door onto it — the lane narrowing rides along, so every caller
     * gets the same list the drawer is currently pointed at.
     */
    private fun gatherLaterList(): List<FeedEntry> =
        com.toolsboox.plugin.feeds.nw.LaterFeed.rows(requireContext(), laterLane)

    private fun showEmpty(text: String) {
        binding.emptyText.text = text
        binding.emptyText.visibility = View.VISIBLE
    }

    /** Every loader's completion funnels here: clears the in-flight flag, and if a refresh was
     *  asked for mid-load, RUNS it now — the caller must stop rendering its stale result when
     *  this returns true. The old `if (loading) return` guard threw those asks away. */
    private fun finishLoad(): Boolean {
        loading = false
        if (::binding.isInitialized) binding.progress.visibility = View.INVISIBLE
        if (refreshQueued) {
            refreshQueued = false
            if (isAdded) { refresh(); return true }
        }
        return false
    }

    /** Open the article IN the right pane (feed directory + list stay put); Back returns.
     *  A podcast/"listen" entry with real audio plays through the player instead of opening the pane. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openEntry(entry: FeedEntry) {
        // The 🔎 row under the mail lens's local matches is a CONTROL, not a letter — opening it
        // runs the server search it names (see mailServerSearchRow).
        if (entry.url == MAIL_SEARCH_SCHEME) { runMailServerSearch(); return }
        // A Mail row opens the LETTER — in this same pane, so the reading grammar (volume keys,
        // tap zones, the rail's within-vs-across jumps) is the one the articles taught.
        mailById[entry.id]?.let { openMailEntry(entry, it); return }
        // A Feed Pickings row jumps straight to its board (ledger://pickings/<date>/<key>).
        if (entry.url.startsWith("ledger://pickings/")) {
            val parts = entry.url.removePrefix("ledger://pickings/").split("/")
            if (parts.size >= 2) {
                val date = runCatching { java.time.LocalDate.parse(parts[0]) }.getOrNull() ?: java.time.LocalDate.now()
                com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(this, date, parts.drop(1).joinToString("/"))
            }
            return
        }
        // A podcast OPENS. IT DOES NOT PLAY (Michael, 2026-08-23: "when I select an item from The
        // Listen it starts auto playing. I don't want it to autoplay — I want to be able to read
        // the entry and then choose to download from there").
        //
        // The 08-05 build made it play AND open, correcting an older one that played WITHOUT
        // opening; the episode page is where the show notes and chapter links live, and that half
        // stands. What comes off is the playing. Opening something to read it is not consent to
        // sound: a tap on a list row is how you look at a thing, and a row that starts talking has
        // decided for you — the cost lands hardest exactly where you were only browsing.
        //
        // Playback and download are now VERBS ON THE PAGE (see buildListenBar): ▶ Play and the
        // ⬇ that files it for offline, which is a choice that travels — see ListenState.wantOffline.
        if (markReadMode() != "off") markEntryRead(entry)   // per the user's "mark as read" setting
        recordInMediaLog(entry)
        FeedSelection.entry = entry
        FeedSelection.list = adapter.current()
        // The piece's highlights travel with it (ArticleHighlights) — a Log line's tap lands on its
        // passage (FeedSelection.pendingMarkId, consumed once).
        articleHighlights.open(
            if (marksOn(entry)) com.toolsboox.plugin.feeds.ot.ArticleHighlights.Article(
                entry.id, entry.url, entry.title, entry.feedTitle, com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED)
            else null,
            jumpTo = FeedSelection.pendingMarkId.also { FeedSelection.pendingMarkId = null })
        // From the Log: reopen in the text it was highlighted in (critique #7). Reader view is a
        // switch; the feed's text and the live page arrive as the entry's own content.
        // The reader's own Reader-view setting comes back when this piece closes.
        FeedSelection.pendingView.also { FeedSelection.pendingView = null }?.let { v ->
            if (parsedBeforeLog == null) parsedBeforeLog = showParsed
            showParsed = v == com.toolsboox.plugin.reader.ui.BookNote.VIEW_READER
        }
        binding.articleWeb.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false   // the image cache is served by shouldInterceptRequest now, never as file://
            allowContentAccess = true   // the letter dress turns it off; the article dress puts the default back
            // PICTURES: the letter dress sets these from the mail switch and nothing put them back,
            // so a letter read with pictures off left every article after it with broken images
            // (Michael, 09-13). The article has its own switch (Feeds settings › Pictures, on).
            val pics = prefs().getBoolean(KEY_PICTURES, true)
            blockNetworkImage = !pics
            loadsImagesAutomatically = pics
            // YouTube (and other) iframe players need DOM storage + a WebChromeClient, and must be
            // allowed to start without a gesture — otherwise the player errors out (150/152).
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Reliable double-tap + pinch zoom (no on-screen zoom buttons). Needs a wide viewport
            // + overview mode so double-tap actually has something to zoom to.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.articleWeb.webChromeClient = android.webkit.WebChromeClient()
        // Links get a menu, tap or HOLD — same as the standalone Articles reader. Without this
        // the in-pane reader either navigated away inside the pane or ignored the hold entirely
        // ("I can't hold on a link and bring up a menu").
        binding.articleWeb.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                // The selection must survive the pen-nib tap: Onyx collapses the WebView
                // selection the moment a toolbar button is touched — before noteArticle's
                // probe runs. The bridge remembers the last non-empty selection and the
                // probe falls back to it. Field report 09-02: "it also doesn't offer you the
                // ability to just use the highlight."
                com.toolsboox.plugin.feeds.ot.WebSelectionBridge.wire(view)
                // Arriving on the piece the voice is ALREADY reading (the Now Playing title's
                // door, 09-13): the follow-along lights up here too, not only when the read
                // was started from this page.
                val p = com.toolsboox.ui.plugin.LedgerPlayer
                if (p.isActive && p.capture?.url?.isNotBlank() == true && p.capture?.url == entry.url) wireTtsFollow()
                // Aa Type moved while this piece was loading: bring its type block up to date. A
                // no-op when it already is, and on a live web page, which has none (Michael 2026-10-11).
                applyArticleTypeLater.run()
                // The piece's highlights — and its gram picks — drawn back (Michael 2026-10-12).
                if (currentArticle?.let { marksOn(it) } == true) articleHighlights.onPageFinished(view)
            }
            // THE OFFLINE IMAGE CACHE, SERVED IN PLACE. The old swap rewrote a cached picture's
            // src to file://…, and Chromium refuses a local resource inside a document that has
            // a web origin (the sanitizer's https sentinel) — so precisely the pictures we HAD
            // drew as the broken-image glyph, online or off (Michael, 09-13: "the reader shows a
            // broken image… doesn't show the image in original or reader view"). The src stays
            // the real URL; a cached copy answers the request, the network answers the rest.
            override fun shouldInterceptRequest(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (!u.startsWith("http")) return null
                val ctx = context ?: return null
                // The reading faces (Aa Type, Michael 2026-10-11) — served from the app.
                com.toolsboox.ot.ReadingFonts.intercept(ctx, request.url)?.let { return it }
                val f = com.toolsboox.plugin.feeds.nw.FeedCache.imageFile(ctx, u)
                if (!f.isFile || f.length() == 0L) return null
                val mime = when (u.substringBefore('?').substringAfterLast('.').lowercase()) {
                    "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"
                    "avif" -> "image/avif"; else -> "image/jpeg"
                }
                return runCatching { android.webkit.WebResourceResponse(mime, null, f.inputStream()) }.getOrNull()
            }
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                // Sub-frame loads pass through untouched: the watch skin swaps a YouTube iframe
                // in at tap time, and intercepting ITS navigation with the link menu would
                // cancel the very embed the tap asked for. The menu is for main-frame link taps.
                if (request?.isForMainFrame == false) return false
                val url = request?.url?.toString().orEmpty()
                // A tap on a drawn highlight (article-marks.js) — its chooser, never a navigation.
                if (articleHighlights.handleUrl(url)) return true
                // The Bluesky reader's "Answer this" door. Handing off to the surface that already
                // owns replying rather than growing a second composer here — see [loadBlueskyFeed].
                if (url.startsWith(BSKY_REPLY_SCHEME)) {
                    openBlueskyComposer(bskyUriById[currentArticle?.id] ?: "")
                    return true
                }
                // The episode page's own two verbs. currentArticle is the entry the pane is
                // showing, which is the only episode either verb can mean.
                if (url.startsWith(LISTEN_PLAY_SCHEME)) {
                    currentArticle?.let { playEntryAudio(it) }
                    return true
                }
                if (url.startsWith(LISTEN_SAVE_SCHEME)) {
                    currentArticle?.let { toggleListenOffline(it) }
                    return true
                }
                if (url.startsWith("mailto:", ignoreCase = true)) { composeFromMailto(url); return true }
                if (url == ARTICLE_RETRY_SCHEME) { renderArticle(); return true }
                if (url.startsWith("http")) { showPaneLinkMenu(url); return true }
                return false
            }
            // A LIVE PAGE THAT WON'T LOAD says so in the pane, with its way to try again — never the
            // browser, never a blank (synth-list-links §10: a Synthesize page's card opened offline).
            override fun onReceivedError(
                view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                val e = currentArticle ?: return
                if (e.content.isNotBlank() || mailById.containsKey(e.id)) return
                fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                articleLoadFailed = true
                paintArticleGround(true)
                view?.loadDataWithBaseURL(com.toolsboox.plugin.feeds.ot.ArticleSanitizer.BASE_URL,
                    "<html><body style=\"font-family:serif;padding:1.2em\"><h2>${esc(e.title)}</h2>" +
                        "<p>${esc(com.toolsboox.plugin.calendar.ot.SynthSources.ARTICLE_WONT_LOAD)}</p>" +
                        "<p><a href=\"$ARTICLE_RETRY_SCHEME\">${esc(com.toolsboox.plugin.calendar.ot.SynthSources.TRY_AGAIN)}</a></p></body></html>",
                    "text/html", "UTF-8", null)
            }
        }
        binding.articleWeb.setOnLongClickListener {
            val hit = binding.articleWeb.hitTestResult
            val url = hit.extra
            when {
                url.isNullOrBlank() || !url.startsWith("http") -> false
                // A bare picture held: it becomes a photo gram for this article ("a cute photo gram").
                hit.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                    val a = currentArticle
                    com.toolsboox.plugin.feeds.ot.FeedNoteGram.showImageMenu(
                        this, calendarDayService, documentsRoot(), url,
                        a?.title.orEmpty(), a?.feedTitle.orEmpty(), a?.url.orEmpty())
                    true
                }
                // An image that is ALSO a link keeps its link menu, with the photo-gram option added.
                hit.type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> { showPaneLinkMenu(url); true }
                hit.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    showPaneLinkMenu(url, imageUrl = url); true
                }
                else -> false
            }
        }
        enterArticlePane(entry)
    }

    /** The pane's entry dance — title, render, swap the list out, collapse the drawer, arm Back —
     *  shared by the article path above and the mail reader, which differ only in how they dress
     *  the WebView before arriving here. */
    private fun enterArticlePane(entry: FeedEntry) {
        // A new piece is a turn: whatever the centre tap brought back over the last one goes.
        feedsPanel?.readerPageTurn()
        binding.articleTitle.text = entry.title
        currentArticle = entry
        lastArticleId = entry.id
        renderArticle()
        binding.feedsRecycler.visibility = View.GONE
        binding.feedsListSearch.visibility = View.GONE
        searchAllowedNow = false
        binding.emptyText.visibility = View.GONE
        // Give the reader the full pane — collapse the left directory drawer (not persisted).
        setDirectoryDrawerVisible(false)
        binding.articlePane.visibility = View.VISIBLE
        binding.articleTapZones.visibility = if (prefs().getBoolean("feeds_tap_zones", true)) View.VISIBLE else View.GONE
        binding.articleBack.setOnClickListener { closeArticlePane(restoreDrawer = false) }
        // Open-externally fallback: a video whose owner blocks embedding errors (150/152) in the
        // in-pane player — this opens the original URL in the YouTube app / browser instead.
        // (A mail:// address has no browser to open in, so the door hides itself for letters.)
        val ext = entry.url.takeIf { it.startsWith("http", ignoreCase = true) }
        binding.articleBrowser.visibility = if (ext != null) View.VISIBLE else View.GONE
        binding.articleBrowser.setOnClickListener {
            ext?.let {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(it)))
                }.onFailure { showMessage(R.string.feeds_open_external_failed, binding.root) }
            }
        }
        articleBackCallback?.isEnabled = true
        // Keep the shrunk/expanded drawer state across next/last-article jumps (don't reset it here).
        applyArticlePill()
    }

    /**
     * Hand a Bluesky post to the surface that can actually answer it.
     *
     * The composer is not reimplemented here and must not be: [BlueskyFragment] enforces the one
     * rule the whole reply path exists for — handwriting is recognised into an editable field he
     * reads and corrects before anything is sent — and a second composer would be a second place
     * for that rule to be forgotten. The AT URI travels through [BlueskyTarget] the same way a
     * tapped entry travels through [FeedSelection]: a one-shot handoff rather than nav args, which
     * is this app's existing idiom for exactly this.
     *
     * Read-only posts are the honest limit here. The site's reply queue answers COMMENTS on his own
     * syndicated posts — a reply threaded under something of his — and there is no route that posts
     * to an arbitrary skeet. So BlueskyFragment opens the pad when the queue holds this post and
     * says plainly that it cannot when it does not, rather than offering a Send that would 404.
     */
    private fun openBlueskyComposer(uri: String) {
        if (uri.isBlank()) { showMessage("No Bluesky reference on that post", binding.root); return }
        com.toolsboox.plugin.calendar.ui.BlueskyTarget.pendingUri = uri
        runCatching { findNavController().navigate(R.id.action_to_bluesky) }
            .onFailure { showMessage("Couldn't open Bluesky", binding.root) }
    }

    /** Render the open article as parsed (reader view) or unparsed (original). */
    private fun renderArticle() {
        articleLoadFailed = false
        val entry = currentArticle ?: return
        // Every render replaces the document under the pane — a new piece, the Reader-view flip,
        // the load-images door. The renderer's find state does not survive that, so the bar must
        // not either: a count left standing over fresh markup is a lie about a document that was
        // never searched. Find is per-piece, deliberately (see setupArticleFind).
        hideArticleFind()
        // A letter renders through the mail reader's own shaping (MailReaderHtml — the one
        // renderer letters have, so message rendering stays one thing), with
        // the parsed toggle meaning Reader vs As-sent instead of parsed vs raw RSS.
        mailById[entry.id]?.let { renderMailArticle(entry, it); return }
        // A YouTube entry under The Watch gets the skinned episode page — thumbnail with a
        // tap-to-play embed, tappable chapter rows, the description cleaned of timestamp
        // cruft — instead of whatever iframe soup the feed shipped. (Non-YouTube watch
        // entries fall through to the plain article path unchanged.)
        val ytId = if (entry.kind == "watch") entry.youTubeId else null
        if (ytId != null) {
            // The skin escapes everything feed-supplied, but the document still must not adopt the
            // entry's (feed-supplied) url as its origin — the sanitizer's sentinel is a real https
            // origin, which is all the tap-to-play embed needs. See ArticleSanitizer.BASE_URL.
            paintArticleGround(false)
            binding.articleWeb.loadDataWithBaseURL(
                com.toolsboox.plugin.feeds.ot.ArticleSanitizer.BASE_URL,
                buildYouTubeHtml(entry, ytId), "text/html", "UTF-8", null)
            kickChapterResolve(entry)
            return
        }
        val parsed = com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(requireContext(), entry.id)
        // Cached images stand in for their URLs (HNR lift) — offline the pictures still draw,
        // online the swap is free (the file is already here and faster than the network).
        // (The cached-image swap to file:// is gone — the request hook serves the cache in place.)
        val body = if (showParsed && !parsed.isNullOrBlank()) parsed else entry.content
        val httpUrl = entry.url.takeIf { it.startsWith("http", ignoreCase = true) }
        // Which text the marks are made on and drawn over (critique #7): Reader view, the feed's
        // own text, or the live page.
        articleHighlights.setView(when {
            showParsed && !parsed.isNullOrBlank() -> com.toolsboox.plugin.reader.ui.BookNote.VIEW_READER
            body.isBlank() -> com.toolsboox.plugin.reader.ui.BookNote.VIEW_LIVE
            else -> com.toolsboox.plugin.reader.ui.BookNote.VIEW_FEED
        })
        // No stored/parsed article (e.g. a gram's external source, content left blank) → load the
        // live page directly in this pane's WebView rather than showing an empty article.
        // EXCEPT listen/watch entries: a podcast with no show-notes must STILL render its own
        // page so the ▶ Play / ⬇ bar exists — loading the raw web page instead gave no way to
        // play and made every tap a link (09-03: "it is popping up like I'm going to a link
        // offering later links"). The constructed page always carries buildListenBar.
        val isMedia = entry.kind == "listen" || entry.kind == "watch"
        if (body.isBlank() && httpUrl != null && !isMedia) {
            paintArticleGround(false)
            binding.articleWeb.loadUrl(httpUrl)
            return
        }
        // A real https baseUrl gives the document a valid origin/referer; a null baseUrl makes YouTube
        // (and other) embeds fail with "error 150" (embedding-not-allowed for the opaque origin). But
        // NOT the entry's own url: that field is feed-supplied, and this WebView's cookie jar holds
        // real logged-in sessions (see SiteWebFragment). Any real https origin satisfies YouTube, so
        // the article loads on the sanitizer's sentinel — see ArticleSanitizer.BASE_URL.
        paintArticleGround(true)
        binding.articleWeb.loadDataWithBaseURL(
            com.toolsboox.plugin.feeds.ot.ArticleSanitizer.BASE_URL,
            buildArticleHtml(entry, body), "text/html", "UTF-8", null)
    }

    /** Whether the pill is shrunk to the slim article drawer (↑ ↓ · next · back). */
    private var pillShrunk = false

    /**
     * Show the right pill buttons for the current mode: the list, the full article view, or the
     * shrunk article drawer (only ↑/↓ + next + back). The grip toggles shrunk while reading.
     */
    private fun applyArticlePill() {
        val inArticle = currentArticle != null
        // The panel is a reader while a piece is open (ReaderFullScreen); the list keeps its pill.
        feedsPanel?.reader = inArticle
        if (!inArticle) pillShrunk = false
        val shrunk = inArticle && pillShrunk
        val fullArticle = inArticle && !shrunk
        fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE
        // Main-pill controls collapse away in the slim drawer.
        binding.refreshButton.visibility = vis(!shrunk)
        binding.settingsButton.visibility = vis(!shrunk)
        // The side nav pill (↑ ☰ ↓ — the "day picker") is INDEPENDENT of the main pill's shrunk
        // state: minimizing the reader modal must NOT take the day picker with it (Michael). So its
        // three buttons stay put in every mode.
        binding.feedsGoto.visibility = View.VISIBLE
        binding.feedsPageUp.visibility = View.VISIBLE
        binding.feedsPageDown.visibility = View.VISIBLE
        // Article actions only in the full article view.
        binding.feedsStar.visibility = vis(fullArticle); binding.feedsNote.visibility = vis(fullArticle)
        // Filled star when the open article is starred (parity with the standalone reader).
        binding.feedsStar.setImageResource(
            if (currentArticle?.starred == true) R.drawable.ic_starred else R.drawable.ic_star)
        binding.feedsParsed.visibility = vis(fullArticle); binding.feedsTts.visibility = vis(fullArticle)
        binding.feedsLater.visibility = vis(fullArticle)
        // Article jump (⏫ last / ⏬ next article) lives ONLY in the slim shrunk drawer. On the full
        // modal it read as stray double-carrots that "don't go anywhere", so it stays with its drawer.
        binding.feedsDwPrev.visibility = vis(shrunk); binding.feedsDwNext.visibility = vis(shrunk)
        // The rail wears the same mode: list dress or article dress, and the star's fill follows
        // the open article — this call is the rail's whole "visibility dance" (redrawn only when
        // the seats it shows actually changed — see redressRail).
        redressRail()
    }

    /** The seat set the rail was last built with — see [redressRail]. */
    private var railSeatKey: String? = null

    /** A rail's seats as drawn: icon, name, glyph and caption, in order — what the e-ink panel
     *  would repaint. (The actions are read live at tap time, so they never make a seat stale.) */
    private fun seatKey(items: List<com.toolsboox.ot.TuckPanel.Item>): String =
        items.joinToString("\n") { "${it.iconRes}|${it.name}|${it.glyph}|${it.caption}" }

    /**
     * REDRAW ONLY WHEN THE SEATS CHANGE (Michael 2026-10-11). Every lens, mailbox, view or search
     * switch ends in refresh(), a piece opening or closing ends in applyArticlePill, the player
     * reports its own changes — and each asks here. The column is rebuilt only when the seats it
     * would draw differ from the ones on the panel, so an RSS ⟳, a step to the next article with
     * the same star, or a player tick that changed nothing costs the e-ink panel nothing. (This is
     * also what dresses The Mail's rail the moment you land on it — Sections › The Mail used to
     * arrive wearing the RSS verbs.)
     */
    private fun redressRail() {
        // Feeds' panel re-asks every time: the redress rule inside the panel moves no pixel for the
        // same seats again (and flips a box in place), so the key compare lives there.
        if (!isMailRoom) { rebuildActionRail("feeds"); return }
        if (seatKey(feedsRailActions()) != railSeatKey) rebuildActionRail("feeds")
    }

    /** The Feeds room's column — the panel ([setupPanel]) in Feeds, IncomingRail's in The Mail.
     *  Kept for More's ☐ Tool labels. */
    private var feedsPanel: com.toolsboox.ot.TuckPanel? = null

    /** What the Feeds panel is showing right now — the flags that add or drop a seat. */
    private fun feedsPanelFlags() = com.toolsboox.ot.PanelSeats.Flags(
        // ⭐ Starred wears Show too — its way back to Unread from the room (2026-10-11 review).
        showScope = mode in setOf("feed", "read", "both", "stars"),
    )

    /**
     * The Feeds room's own seats on the one panel language (Michael 2026-10-11; spec §4.5/§4.7 with
     * its critique). Nib and Side are the shared doors ([setupPanel]); the Hub is no seat now
     * (Michael 2026-10-12) — More's first row and the left edge's Back ([openHub]), hung from the
     * side the column stands on. Every action drives the hidden pill
     * button where one exists, so the single wiring keeps working for anything that performClicks
     * it (the day-page rule). Monochrome faces only ([com.toolsboox.ot.PanelFaces]): « » for
     * previous/next, the magnifier, the eye, the page-under-a-magnifier for Find, the note page —
     * no 🔍 👁 🔎 emoji, no pencil (a pencil is the Nib's family).
     */
    private fun feedsSeat(s: com.toolsboox.ot.PanelSeats.Seat): com.toolsboox.ot.TuckPanel.Item? {
        val reading = currentArticle != null
        return when (s) {
            // (THE FEEDS PANE'S SEAT — the drawer, boxed while open; hold: The Mail — left the column
            // 2026-10-12. The drawer is the left edge's first Back and More's second row; The
            // Mail is the drawer's own "The Mail ↗" row.)
            // ☀ Today: away from home, the one-tap reset to plain Unread for today; at home, today's
            // day page (spec Q2 — the Hub is More's first row and the left edge's Back, so the
            // second tap no longer opens it).
            // Hold: the day page, from anywhere.
            com.toolsboox.ot.PanelSeats.Seat.TODAY -> com.toolsboox.ot.PanelFaces.item(s, "Today",
                longPress = { openTodaysPage() }) { binding.feedsGoto.performClick() }
            com.toolsboox.ot.PanelSeats.Seat.MORE ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.feeds_settings)) { binding.settingsButton.performClick() }
            com.toolsboox.ot.PanelSeats.Seat.PAGE_UP ->
                com.toolsboox.ot.PanelFaces.item(s) { binding.feedsPageUp.performClick() }
            com.toolsboox.ot.PanelSeats.Seat.PAGE_DOWN ->
                com.toolsboox.ot.PanelFaces.item(s) { binding.feedsPageDown.performClick() }
            // « » WITH the page turns, up top where the thumb rests (Michael, 09-11) — from the
            // list too, where they open the first/next row.
            com.toolsboox.ot.PanelSeats.Seat.PREVIOUS ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.feeds_prev_article)) {
                    if (currentArticle != null) binding.feedsDwPrev.performClick() else stepFromList(-1)
                }
            com.toolsboox.ot.PanelSeats.Seat.NEXT ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.feeds_next_article)) {
                    if (currentArticle != null) binding.feedsDwNext.performClick() else stepFromList(1)
                }
            com.toolsboox.ot.PanelSeats.Seat.REFRESH ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.feeds_refresh)) { binding.refreshButton.performClick() }
            // 🔍 SUMMON THE LIST SEARCH (09-14) — boxed while the field stands.
            com.toolsboox.ot.PanelSeats.Seat.SEARCH -> com.toolsboox.ot.PanelFaces.item(s,
                if (listSearchSummoned()) "Put the search away" else "Search this feed",
                on = listSearchSummoned()) { toggleListSearch() }
            // THE READ SCOPE (09-14): Unread → Read → Everything; the caption is the state, and
            // the box says the list is NOT plain Unread — so the state still shows with Tool labels
            // off (review 2026-10-11, F6). Hold: pick the view outright, Starred included (F11).
            com.toolsboox.ot.PanelSeats.Seat.SHOW -> com.toolsboox.ot.PanelFaces.item(s,
                when (mode) { "read" -> "Showing read · tap for everything"; "both" -> "Showing everything · tap for unread"; "stars" -> "Showing starred · tap for unread"; else -> "Showing unread · tap for read" },
                caption = when (mode) { "read" -> "Read"; "both" -> "All"; "stars" -> "Starred"; else -> "Unread" },
                on = mode != "feed",
                longPress = { showReadScopePicker() }) { cycleReadScope() }
            // ❖ the engines — over the piece while reading, over the list as shown otherwise
            // (every Incoming list has Synth, the 09-06 canon; iOS's "Synthesize this list").
            com.toolsboox.ot.PanelSeats.Seat.SYNTH ->
                if (reading) com.toolsboox.ot.PanelFaces.item(s, com.toolsboox.plugin.calendar.ot.SynthSources.Kind.ARTICLE.verb) { synthesizeOpenArticle() }
                else com.toolsboox.ot.PanelFaces.item(s, listSynthKind().verb) { synthesizeList() }
            com.toolsboox.ot.PanelSeats.Seat.STAR -> com.toolsboox.ot.PanelFaces.item(s, "Star",
                iconRes = if (currentArticle?.starred == true) R.drawable.ic_pf_starred else R.drawable.ic_pf_star) {
                binding.feedsStar.performClick()
            }
            com.toolsboox.ot.PanelSeats.Seat.NOTE ->
                com.toolsboox.ot.PanelFaces.item(s, "Note") { binding.feedsNote.performClick() }
            // Every highlight and note on this piece, one tap; tap a row to land on it (Michael
            // 2026-10-12: "Instead of margin, those can be annotations").
            com.toolsboox.ot.PanelSeats.Seat.NOTES ->
                if (reading && currentArticle?.let { marksOn(it) } == true)
                    com.toolsboox.ot.PanelFaces.item(s, "Annotations") { articleHighlights.showAnnotations() }
                else null
            // Find inside this piece, high up on purpose: "where does he say that" is asked early.
            com.toolsboox.ot.PanelSeats.Seat.FIND ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.feeds_find)) { showArticleFind() }
            // Reader view — boxed while it's on (an on/off seat; a box marks what's on).
            com.toolsboox.ot.PanelSeats.Seat.READER ->
                com.toolsboox.ot.PanelFaces.item(s, "Reader view", on = showParsed) { binding.feedsParsed.performClick() }
            com.toolsboox.ot.PanelSeats.Seat.TYPE ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.aa_type)) { openAaType() }
            // Boxed while this piece is the one being read aloud (iOS boxes its Listen the same
            // way; review 2026-10-11, F11) — the player's own listener redresses it.
            com.toolsboox.ot.PanelSeats.Seat.LISTEN -> com.toolsboox.ot.PanelFaces.item(s, "Read aloud",
                on = com.toolsboox.ui.plugin.LedgerPlayer.isActive && currentArticle != null &&
                    com.toolsboox.ui.plugin.LedgerPlayer.capture?.url == currentArticle?.url) {
                binding.feedsTts.performClick()
            }
            com.toolsboox.ot.PanelSeats.Seat.LATER ->
                com.toolsboox.ot.PanelFaces.item(s, getString(R.string.feeds_add_later)) { binding.feedsLater.performClick() }
            else -> null
        }
    }

    /**
     * ❖ over the list as shown → A NEW SYNTHESIZE PAGE THAT LINKS BACK (Michael 2026-10-12: "the
     * response should come back with links to those articles within ledgable itself" → "create
     * that page as a new synth page, and add a pick to all picks"). Up to forty rows exactly as the
     * list shows them — title, feed, blurb, address and (Miniflux only) the server id — numbered
     * for the engine; the reply lands as a Synthesize page whose cards open these very pieces in
     * the reader here, never the browser, plus one pick in All Picks ([SynthListRun]). The Mail's
     * list rides the same seat (letters, `mail://`), and The Listen's says episodes.
     */
    private fun synthesizeList() {
        val req = listSynthRequest()
        com.toolsboox.plugin.calendar.ui.SynthListRun.start(this, req) {
            isAdded && isResumed && currentArticle == null && listSynthScope() == req.scope
        }
    }

    private fun listSynthKind(): com.toolsboox.plugin.calendar.ot.SynthSources.Kind = when (kindFilter) {
        KIND_MAIL -> com.toolsboox.plugin.calendar.ot.SynthSources.Kind.MAIL
        "listen" -> com.toolsboox.plugin.calendar.ot.SynthSources.Kind.EPISODES
        else -> com.toolsboox.plugin.calendar.ot.SynthSources.Kind.FEEDS
    }

    /**
     * The list's name on the page it makes — iOS's list titles, word for word (review 2026-10-12: the
     * same list made differently named pages, Sources labels and Log rows on the two apps): "All
     * Feeds", "Starred", "Later List" / "Later · The Read", "Bluesky", a picked feed or a lens ("The
     * Listen"), a saved search's name; The Mail's "Mail · Inbox" ("· all accounts" once there are
     * two), "Mail · Sent · <account>", "Mail · <account>".
     */
    private fun listSynthScope(): String = when {
        kindFilter == KIND_MAIL -> "Mail · " + mailSynthName()
        mode == "stars" -> "Starred"
        mode == "later" -> laterLane?.let { lane ->
            com.toolsboox.plugin.feeds.nw.LaterFeed.LANES.firstOrNull { it.first == lane }?.third?.let { "Later · $it" }
        } ?: "Later List"
        mode == "bsky" -> "Bluesky"
        mode == "smart" && smartFeed != null -> smartFeed?.name.orEmpty()
        mode == "local" -> localSub?.title ?: "Local Feeds"
        mode == "pickings" -> "Feed Pickings"
        else -> pickShowing() ?: when (kindFilter) {
            "listen" -> "The Listen"; "watch" -> "The Watch"; "read" -> "The Read"; else -> "All Feeds"
        }
    }

    /** The Mail's list by name, as iOS's `listTitle` says it: an account's own pile "Sent · <account>",
     *  every account's "Sent" / "Trash" ("· all accounts" once there are two), one account's Inbox by
     *  the account's name, "Starred", else "Inbox" ("Inbox · all accounts" once there are two). */
    private fun mailSynthName(): String {
        val w = MailPlace.where(mailMailbox)
        val accounts = com.toolsboox.plugin.mail.MailAccountStore.all(requireContext())
        val label = w.account?.let { id ->
            MailPlace.AccountLabel.all(accounts.map { Triple(it.id, it.display, it.email) })[id]
                ?.let(MailPlace.AccountLabel::plain)
        }
        return when {
            w.box != MailPlace.Box.INBOX && w.account != null -> w.box.title + (label?.let { " · $it" } ?: "")
            w.box != MailPlace.Box.INBOX -> w.box.words(accounts.size)
            w.account != null && label != null -> label
            mode == "stars" -> MailPlace.Box.STARRED.title
            else -> MailPlace.Box.INBOX.words(accounts.size)
        }
    }

    /**
     * A letter as a numbered source, in the words the row uses and the iPhone now uses too (review
     * 2026-10-12): its subject ("(no subject)" when blank), the other party — the sender, or
     * "To <recipient>" for a letter you sent (in Sent every sender is you) — its first line,
     * `mail://<id>`. Not the row's whole face: the CRM status it wears (" · opened") is a moment's
     * state, not the letter, and the iPhone has none — it would put words in the material and on
     * the cards that the other app's lines don't have.
     */
    private fun mailSynthItem(m: com.toolsboox.plugin.mail.InboxMessage): com.toolsboox.plugin.calendar.ot.SynthSources.Item {
        val who = if (InboxStore.isSent(m.id)) "To " + m.toName.ifBlank { m.toEmail }.ifBlank { m.account }.ifBlank { "—" }
            else m.fromName.ifBlank { m.fromEmail }.ifBlank { "Unknown sender" }
        return com.toolsboox.plugin.calendar.ot.SynthSources.Item(
            title = m.subject.ifBlank { "(no subject)" }, feed = who, blurb = m.snippet, link = "mail://${m.id}", entryId = 0)
    }

    private fun listSynthRequest(): com.toolsboox.plugin.calendar.ui.SynthListRun.Request {
        val mail = kindFilter == KIND_MAIL
        // Only rows that ARE pieces: letters in The Mail; never a control row (🔎, the shelf's door).
        val rows = adapter.current()
            .filter { if (mail) mailById.containsKey(it.id) else it.url.isNotBlank() && !it.url.startsWith("ledger://") }
            .take(com.toolsboox.plugin.calendar.ot.SynthSources.MAX_ITEMS)
        val items = rows.map { e ->
            (if (mail) mailById[e.id]?.let { mailSynthItem(it) } else null)
                ?: com.toolsboox.plugin.calendar.ot.SynthSources.Item(
                    title = e.title, feed = e.feedTitle, blurb = e.blurb,
                    link = e.url, entryId = if (e.id > 0) e.id else 0)
        }
        return com.toolsboox.plugin.calendar.ui.SynthListRun.Request(listSynthKind(), listSynthScope(), items)
    }

    /**
     * ONE INCOMING RAIL (Michael 2026-10-11) — [IncomingRail] owns the seats and their order for
     * RSS and The Mail alike; [railItem] makes each seat its button. Nothing either old rail
     * carried is gone: RSS's list gains 🔍 · 👁 (09-14) and « », The Mail gains ⟳ and More on a
     * letter and « » up top, and the letter verbs follow the reading verbs in one place.
     */
    private fun feedsRailActions(): List<com.toolsboox.ot.TuckPanel.Item> {
        val mail = kindFilter == KIND_MAIL
        // THE TRANSPORT LEADS, whenever something is playing. Michael, 2026-08-05: "instead of a
        // pop-up for the player, building the player into the sub menu makes sense." First,
        // because while a podcast is running it is the thing you most often reach for — and now
        // that a Listen row opens its episode page as well as playing, the page underneath is
        // exactly what a modal would have covered. In The Mail too (2026-10-11: a podcast no
        // longer vanishes from the rail when you cross into your letters — plan v6 O11 overridden).
        val transport = com.toolsboox.ui.plugin.LedgerPlayerRail.items(requireContext()) { redressRail() }
        val letter = currentArticle?.let { mailById[it.id] }
        val seats = IncomingRail.seats(
            mail = mail,
            reading = currentArticle != null,
            // ⭐ Starred wears 👁 too — its way back to Unread from the room (2026-10-11 review:
            // with the header toggle gone, Starred had no exit but ☀ or the drawer). Not over ➤ Sent
            // or 🗑 Trash: those piles ignore Unread / Read / All, so 👁 there changed nothing on
            // screen and quietly moved the room's standing view (2026-10-11 review); their way
            // out is the mailbox line's ✕, ☀ or the drawer.
            showScope = mode in setOf("feed", "read", "both", "stars") && !(mail && MailPlace.isPile(mailMailbox)),
            canSweep = mode != "stars" && !MailPlace.isPile(mailMailbox),
            // Decided by THE OPEN LETTER, not the mailbox (2026-10-11 review): a cleared letter
            // opened from a gram or the ↩ chip over 📥 Inbox wore 🗑 Delete, whose tap quietly put
            // it back; a server-search result in Trash wore ⤴ with nothing to move back.
            trash = mail && letter != null && InboxStore.isCleared(requireContext(), letter.id),
            // A 🚫 Junk letter is read-only here: no 🗑 seat (Michael 2026-10-11, per-account Junk).
            junk = mail && letter != null && com.toolsboox.plugin.mail.JunkMail.isJunk(letter.id),
            // A letter you sent is never unread: no ●/○ seat (review 2026-10-11).
            sent = mail && letter != null && InboxStore.isSent(letter.id),
        )
        return transport + seats.map { railItem(it, mail) }
    }

    /**
     * ❖ over the open piece → the same page, with one source [1] (synth-list-links §6): the
     * SELECTION first, as iOS does (FeedLedgerView's synthesizeArticle), else the whole piece —
     * the cache's readable copy, the letter's body, the entry's own text, or, for a live page with
     * none stored, what the pane is showing. He is reading, so it ends in "❖ Ready" with its door
     * rather than a jump.
     */
    private fun synthesizeOpenArticle() {
        val e = currentArticle ?: return
        val ctx = requireContext()
        val letter = mailById[e.id]
        fun go(text: String, label: String) {
            if (!isAdded) return
            val item = letter?.let { mailSynthItem(it) } ?: com.toolsboox.plugin.calendar.ot.SynthSources.Item(
                title = e.title, feed = e.feedTitle, blurb = e.blurb, link = e.url,
                entryId = if (e.id > 0) e.id else 0)
            com.toolsboox.plugin.calendar.ui.SynthListRun.start(this, com.toolsboox.plugin.calendar.ui.SynthListRun.Request(
                com.toolsboox.plugin.calendar.ot.SynthSources.Kind.ARTICLE,
                // A letter by its own subject (the row's face says "(no subject)" for a blank one).
                (letter?.subject ?: e.title).ifBlank { "This article" },
                listOf(item), label, text))
        }
        com.toolsboox.plugin.feeds.ot.WebSelectionBridge.probe(binding.articleWeb) { sel ->
            if (!isAdded) return@probe
            // The pane shows the can't-load page: neither a selection of it nor its text is the article.
            val failed = articleLoadFailed
            if (sel.isNotBlank() && !failed) { go(sel, "Selection"); return@probe }
            val html = com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(ctx, e.id)
                ?: letter?.body?.takeIf { it.isNotBlank() } ?: e.content
            val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
            if (text.isNotBlank()) { go(text, "This article"); return@probe }
            if (failed) { go("", "This article"); return@probe }   // → "Nothing to synthesize yet …"
            binding.articleWeb.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { raw ->
                go(com.toolsboox.plugin.feeds.ot.WebSelectionBridge.unquote(raw), "This article")
            }
        }
    }

    /** Open the previous (dir=-1) or next (dir=+1) article in the displayed list. */
    private fun openAdjacentArticle(dir: Int) {
        val cur = currentArticle ?: return
        // Mail steps through the list AS SHOWN — chips, window and search included — because
        // "next" while triaging unread-in-July must mean the next row of that triage, not the
        // next of the whole pile. Only real letters count: the 🔎 server-search control row is
        // in the list but is not a place the crossing jumps can land.
        // ⏬ WALKS ONLY THE LIST ON SCREEN (Michael 2026-10-11): articles now step the same way —
        // a picked feed, category, window or search is the list you're working, and "next" used
        // to wander off into the whole corpus. A piece opened from outside that list (a gram's
        // "Go to source") still steps the corpus, so it never answers "no next" for no reason.
        val shown = adapter.current()
        val list = if (kindFilter == KIND_MAIL) shown.filter { mailById.containsKey(it.id) }
        else if (shown.any { it.id == cur.id }) shown
        else applyKind(allEntries)
        // After a detour through the thread cards, step from the row the detour left.
        val from = mailThreadOrigin?.takeIf { kindFilter == KIND_MAIL } ?: cur.id
        val i = list.indexOfFirst { it.id == from }
        // Triage flow: an open letter in the Unread view is already read, so it has dropped OUT
        // of the shown list — "next" then means the top of what's left, which is exactly the
        // next unread. Without this the jump answered "no next" mid-triage.
        val j = when {
            i >= 0 -> i + dir
            kindFilter == KIND_MAIL && list.isNotEmpty() -> if (dir > 0) 0 else list.size - 1
            else -> -1
        }
        if (j in list.indices) openEntry(list[j])
        else showMessage(if (dir > 0) R.string.feeds_no_next else R.string.feeds_no_prev, binding.root)
    }

    /** Play an entry's audio through the shared player — the offline copy if we cached it, else the
     *  stream. Used for "listen" feed entries (podcasts). */
    private fun playEntryAudio(e: FeedEntry) {
        // mediaUrl, not audioUrl — A DOWNLOADED VIDEO PLAYS THROUGH THIS SAME TRANSPORT.
        //
        // Michael, 2026-08-23: "I was thinking that the downloaded version is what could be
        // playback matched, not necessarily needing the js api." Exactly right, and it is what
        // makes video progress free: the iframe's playhead is unobservable without the YouTube JS
        // API, but a file on disk goes through MediaPlayer like any episode — so the same heartbeat
        // writes the same synced ListenState, and the same resume lands you where you left off on
        // the other device. Nothing here needed to learn about video; it needed to stop asking
        // specifically for audio.
        // An in-progress shelf row is a pointer, not the episode (its feed line is "show · N min
        // left"): resolved to the episode first, so the transport files the show's real name.
        if (isShelfRow(e)) { playFromRow(e); return }
        val url = e.mediaUrl ?: return
        val src = com.toolsboox.plugin.feeds.nw.LaterMedia.playableSource(requireContext(), url)
        // The episode's picture, else its show's cover (10-12) — what the transport draws and
        // what the synced ListenState carries to the other devices' In-progress rows. Unknown
        // yet, FeedChapters' play-time feed read finds it and hands it over (LedgerPlayer.adoptArt).
        val art = com.toolsboox.plugin.feeds.nw.ShowCovers.artFor(requireContext(), e)
        // The capture identity rides in with the play call: it is what grows the ★ and 📝 on the
        // transport (modal and drawer card both), so a playing podcast can be starred/annotated
        // from the player itself — the Listen flow never opens the article pane where the
        // reader's own star lives. The entry URL is the episode page; a synthetic row (downloaded
        // audio aged off the server) may have none, so the enclosure URL stands in.
        com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
            requireContext(), e.title, e.feedTitle.ifBlank { null }, art, src,
            capture = com.toolsboox.ui.plugin.LedgerPlayer.Capture(
                title = e.title, feedTitle = e.feedTitle,
                url = e.url.ifBlank { url }, imageUrl = art, excerpt = e.blurb,
                enclosureUrl = url
            )
        )
        // The Podcasting 2.0 layer rides in after the transport starts: chapters (2.0 tag or
        // description timestamps) + the transcript hook resolve in the background and attach
        // to THIS session — the Now Playing card grows its chapter row the moment they land.
        com.toolsboox.plugin.feeds.nw.FeedChapters.attachToPlayer(requireContext(), e)
        // The log's PLAY moment — pressing play, not opening the page (see [recordInMediaLog]).
        // A video played through the transport logs as WATCH: it is the same act it would have been
        // in the embed, and the log answers "what did I watch", not "what did the file contain".
        context?.let { ctx ->
            val kinds = com.toolsboox.plugin.calendar.da.v2.MediaLogEntry
            com.toolsboox.plugin.calendar.ot.MediaLogStore.record(
                ctx, id = e.url.ifBlank { url },
                kind = if (e.kind == "watch") kinds.WATCH else kinds.LISTEN,
                title = e.title, source = e.feedTitle, url = e.url.ifBlank { url }, feedId = e.id)
        }
        // No popup on play — the inline Now Playing card seats in the drawer instead
        // (the auto-modal on top of the card read as two competing surfaces).
        armUpNext(requireContext().applicationContext, adapter.current().ifEmpty { FeedSelection.list }, e)
        renderDirectory()   // surface the "▶️ Now Playing" row in the drawer right away
    }

    /** THE LENS IS THE QUEUE (the AntennaPod lift, 09-02): when [after] runs off the end, the
     *  player rolls into the next unheard episode of the same kind from the same list — the way
     *  a finished comic opens the next chapter. Headless on purpose: the chain is built on the
     *  application context and re-arms itself, so episodes keep flowing with the fragment long
     *  gone and the screen dark. */
    private fun armUpNext(appCtx: android.content.Context, list: List<FeedEntry>, after: FeedEntry) {
        val i = list.indexOfFirst { it.id == after.id }
        // Never an In-progress shelf row, and never the same episode again (review 10-12): a
        // stand-in (id -1, not in this list) searched from the TOP of The Listen, where the shelf
        // sits — so "up next" was often this very episode's own shelf row, and finishing it
        // replayed it from 0 (a heard record resumes at the start). The shelf is where you left
        // things, not what comes next.
        val next = list.drop(if (i >= 0) i + 1 else 0).firstOrNull { n ->
            !isShelfRow(n) && (after.url.isBlank() || n.url != after.url) &&
            (after.mediaUrl == null || n.mediaUrl != after.mediaUrl) &&
            n.kind == after.kind && n.mediaUrl != null && n.id != after.id &&
                com.toolsboox.plugin.calendar.ot.ListenStateStore.get(
                    appCtx, n.url.ifBlank { n.mediaUrl ?: "" })?.done != true
        }
        com.toolsboox.ui.plugin.LedgerPlayer.setUpNext(next?.title, next?.let { n ->
            { playHeadless(appCtx, list, n) }
        })
    }

    /** Start [e] on the shared transport without touching fragment state — the up-next chain's
     *  own play path, mirroring [playEntryAudio] minus the UI. */
    private fun playHeadless(appCtx: android.content.Context, list: List<FeedEntry>, e: FeedEntry) {
        // A shelf row is a pointer whose feed line is "show · N min left" — resolved to its
        // episode first, as [playEntryAudio] does, so that line is never filed as the show's name.
        // (armUpNext no longer picks one; this keeps the headless path honest on its own.)
        if (isShelfRow(e)) {
            if (e.url.startsWith("abs:")) return
            val st = com.toolsboox.plugin.calendar.ot.ListenStateStore.get(appCtx, e.url)
            val real = list.firstOrNull { !isShelfRow(it) && (it.url == e.url || it.mediaUrl == e.url) }
                ?: standInFor(appCtx, e.url, st)
            return playHeadless(appCtx, list, real)
        }
        val url = e.mediaUrl ?: return
        val src = com.toolsboox.plugin.feeds.nw.LaterMedia.playableSource(appCtx, url)
        val art = com.toolsboox.plugin.feeds.nw.ShowCovers.artFor(appCtx, e)
        com.toolsboox.ui.plugin.LedgerPlayer.startAudio(
            appCtx, e.title, e.feedTitle.ifBlank { null }, art, src,
            capture = com.toolsboox.ui.plugin.LedgerPlayer.Capture(
                title = e.title, feedTitle = e.feedTitle,
                url = e.url.ifBlank { url }, imageUrl = art, excerpt = e.blurb,
                enclosureUrl = url
            )
        )
        com.toolsboox.plugin.feeds.nw.FeedChapters.attachToPlayer(appCtx, e)
        val kinds = com.toolsboox.plugin.calendar.da.v2.MediaLogEntry
        com.toolsboox.plugin.calendar.ot.MediaLogStore.record(
            appCtx, id = e.url.ifBlank { url },
            kind = if (e.kind == "watch") kinds.WATCH else kinds.LISTEN,
            title = e.title, source = e.feedTitle, url = e.url.ifBlank { url }, feedId = e.id)
        armUpNext(appCtx, list, e)
    }

    /** Add an entry to today's Later List (no star needed). For media (a podcast/video enclosure) we
     *  also kick off a background download so it's available offline.
     *
     *  Through [IntakePageStore.fileLink] — the ONE write path every other filing takes — and not
     *  a hand-rolled append, because the hand-rolled append was how "Later-list saves don't
     *  import" happened: it never cleared the URL's deletion tombstone, so a link he had once
     *  removed was written locally, the toast said "Added", and the very next pull-merge-push
     *  quietly ate the line again (the union-merge consults [LaterRemovals] — that is its job).
     *  fileLink clears the tombstone (an explicit filing outranks an old deletion), writes the
     *  same "title — url" line shape both forks read, dedups the same-day repeat itself, and
     *  brings the rest of the ceremony (og: face, offline cache, personal feed) along. */
    private fun addToLater(e: FeedEntry) {
        val kind = when (e.kind) { "watch" -> "watch"; "listen" -> "listen"; else -> "read" }
        val link = e.audioUrl ?: e.url
        if (link.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                ctx, java.time.LocalDate.now(), kind, link, e.title,
                // The entry knows what it is — carry its blurb and featured image so the
                // Later row shows the thing, not a bare URL.
                excerpt = e.blurb, image = e.imageUrl)
        }
        val media = e.audioUrl
        if (media != null) {
            com.toolsboox.plugin.feeds.nw.LaterMedia.download(requireContext(), media, e)
            showMessage(R.string.feeds_added_later_dl, binding.root)
        } else {
            showMessage(R.string.feeds_added_later, binding.root)
        }
    }

    private fun closeArticlePane(restoreDrawer: Boolean = true) {
        hideArticleFind()
        // Our follow-along hook goes with the pane (a book's, wired by the reader, is left alone).
        ttsFollowHook?.let { if (com.toolsboox.ui.plugin.LedgerPlayer.onFollowSentence === it) com.toolsboox.ui.plugin.LedgerPlayer.onFollowSentence = null }
        ttsFollowHook = null
        binding.articlePane.visibility = View.GONE
        paintArticleGround(false)
        articleHighlights.open(null)
        parsedBeforeLog?.let { showParsed = it; parsedBeforeLog = null }
        binding.articleWeb.loadUrl("about:blank")
        openMail = null
        mailThreadOrigin = null
        binding.feedsRecycler.visibility = View.VISIBLE
        binding.feedsListSearch.visibility = if (listSearchSummoned()) View.VISIBLE else View.GONE
        searchAllowedNow = true; searchTucked = false
        // On an explicit Back, return straight to the article list — don't pop the drawer open as an
        // intermediate step. Only restore it when the article was closed by picking a feed (in the drawer).
        setDirectoryDrawerVisible(restoreDrawer && drawerOpenPref())
        articleBackCallback?.isEnabled = false
        currentArticle = null
        applyArticlePill()
        // A reply sent from the pane: the rows wear their ↩ now the list is back.
        if (mailRowsStale) { mailRowsStale = false; if (kindFilter == KIND_MAIL) submitMailRows() }
        // Intentionally do NOT stop the player — read-aloud keeps going after you leave the article;
        // control it from the "▶ Now Playing" modal.
    }

    // --- Find within the open piece ------------------------------------------------------------
    //
    // The book reader has had this since it shipped, and Michael asked for the same reach in the
    // article pane and the mail reader: "it might also be good to be able to search/find within
    // the current RSS article or email reader — and of course a find is in each book."
    //
    // A VOLUME GETS A HIT LIST; A PAGE GETS A FIND BAR. Say that plainly, because the next reader
    // will notice the two searches don't look alike and should know it was chosen, not drifted
    // into. The book's find (ReaderFragment.showBookSearch, entered from the book ☰ as "🔎 Search
    // this book / all Ledger…") is a RESULTS LIST: foliate's `window.searchBook` answers
    // asynchronously with CFI + excerpt pairs that fill a dialog you tap to jump, and it carries a
    // second scope — the whole Ledger corpus, OCR'd handwriting included. That is right for a
    // volume: three hundred pages you cannot see, so the hits have to be brought to you, and while
    // you are asking a book a question you may well have meant to ask the whole ledger.
    //
    // A piece is one article or one letter, mostly on screen already and never more than a few
    // screens deep. Bringing you a list of excerpts from it would be showing you a copy of what
    // you are looking at. What's wanted is the words lit where they sit and a way to walk them —
    // so: a bar, live count, ‹ ›, and no scope switch, because the only scope a piece has is
    // itself (the whole-ledger question already has its home on the book's search and the Log).
    //
    // What IS borrowed is the vocabulary, so the two read as one house habit: the same 🔎, the
    // same "Find in this piece" phrasing beside "Search this book", a single-line field with the
    // search IME action, and one dismissal that puts the plain page back.
    //
    // ONE IMPLEMENTATION FOR BOTH. An article and a letter are the same pane's WebView dressed two
    // ways, so the find is Android's own native find-in-page — findAllAsync / findNext /
    // clearMatches — which lives in the renderer, NOT in injected JavaScript. Mail was rendered
    // with scripts off when this was built (it runs them now, disarmed, for the highlight bridge
    // — see showMailInPane), and the native find is still the one implementation both dresses
    // share; it never depended on the setting either way.
    //
    // THE RAIL'S ⏫⏬ ARE NOT TOUCHED. They mean "previous / next piece" — article-to-article in
    // the feeds dress, email-to-email in The Mail dress — and they keep meaning exactly that while
    // a find is open. Stepping matches is the find bar's own ‹ › pair. Overloading the crossing
    // jumps would have cost the reader the one move they most want after finding something on
    // page four: carrying on to the next piece.

    /** Debounce for find-as-you-type — the Reading Log's 250ms, for the same reason: a full
     *  find-and-highlight pass per keystroke is wasted work, and on e-ink it is wasted repaint. */
    private val findDebounce = android.os.Handler(android.os.Looper.getMainLooper())

    /** How many matches the renderer last reported, so ‹ › can decline politely at zero. */
    private var findMatchCount = 0

    /** Wire the find bar once, at view creation — the bar itself stays hidden until asked for. */
    private fun setupArticleFind() {
        // The renderer answers asynchronously (it has to walk the document), which is why the
        // count is painted from here rather than returned by findAllAsync. Partial tallies are
        // ignored: a count that ticks 1…4…9 while you type is three extra e-ink repaints saying
        // nothing the final number doesn't.
        binding.articleWeb.setFindListener { activeOrdinal, matches, doneCounting ->
            if (!doneCounting) return@setFindListener
            findMatchCount = matches
            binding.articleFindCount.text = when {
                binding.articleFindField.text.isNullOrBlank() -> ""
                matches == 0 -> getString(R.string.feeds_find_none)
                // activeMatchOrdinal is 0-based; a reader counts from one.
                else -> getString(R.string.feeds_find_count, activeOrdinal + 1, matches)
            }
        }
        binding.articleFindField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                findDebounce.removeCallbacksAndMessages(null)
                findDebounce.postDelayed({ if (isAdded) runArticleFind() }, 250)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // Enter on the keyboard means "the next one" — the same key that submits the reader's
        // search field, doing the only thing left to do once a query is already running.
        binding.articleFindField.setOnEditorActionListener { _, _, _ -> stepArticleFind(forward = true); true }
        binding.articleFindPrev.setOnClickListener { stepArticleFind(forward = false) }
        binding.articleFindNext.setOnClickListener { stepArticleFind(forward = true) }
        binding.articleFindClose.setOnClickListener { hideArticleFind() }
    }

    /** The rail's 🔎 — open the bar over the piece and hand it the keyboard, because nobody asks
     *  to find without something to type. */
    private fun showArticleFind() {
        if (currentArticle == null) { showMessage(R.string.feeds_find_nothing_open, binding.root); return }
        binding.articleFindBar.visibility = View.VISIBLE
        binding.articleFindField.requestFocus()
        binding.articleFindField.post {
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(binding.articleFindField, 0)
        }
        // Asking again with a query still in the field re-finds it: the highlights were cleared
        // on the way out, so the bar would otherwise come back showing a count of nothing.
        if (!binding.articleFindField.text.isNullOrBlank()) runArticleFind()
    }

    /**
     * Put the plain page back: highlights off, bar away, keyboard down. Called by ✕, by Back, and
     * by every path that changes what the pane is showing — a find belongs to the piece it was run
     * against, and carrying a stale query and a stale count across into the next article or letter
     * would be claiming matches in a document that was never searched.
     */
    private fun hideArticleFind() {
        findDebounce.removeCallbacksAndMessages(null)
        binding.articleWeb.clearMatches()
        findMatchCount = 0
        binding.articleFindCount.text = ""
        binding.articleFindField.setText("")
        binding.articleFindBar.visibility = View.GONE
        // Only when the field is really attached — this also runs from renderArticle, which fires
        // once before the pane has ever been shown, and a null window token is not a keyboard.
        binding.articleFindField.windowToken?.let { token ->
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun articleFindOpen() = binding.articleFindBar.visibility == View.VISIBLE

    /** Run (or re-run) the query. An emptied field is a cleared find, not a search for nothing. */
    private fun runArticleFind() {
        val q = binding.articleFindField.text?.toString().orEmpty()
        if (q.isBlank()) {
            binding.articleWeb.clearMatches()
            findMatchCount = 0
            binding.articleFindCount.text = ""
            return
        }
        binding.articleWeb.findAllAsync(q)
    }

    /** ‹ › — the find's OWN stepping, over matches. See the note above about the rail's ⏫⏬. */
    private fun stepArticleFind(forward: Boolean) {
        if (findMatchCount == 0) return
        // findNext wraps at either end by itself, which is what a reader expects of a find: the
        // last match's "next" is the first one again, not a dead button.
        binding.articleWeb.findNext(forward)
    }

    /** Star the open article. `toggleStar` is the single place that logs the star to the Ledger
     *  (local + Miniflux paths), so we don't log a second time here — that used to double-log. */
    private fun starArticle(e: FeedEntry) {
        toggleStar(e)
    }

    /** Read the current selection (if any), then note/annotate the open article. A saved note
     *  IS a gram now — [FeedNoteGram] asks for its medium and places it on today's Notes page. */
    private fun noteArticle(e: FeedEntry) {
        // An ARTICLE: the one chooser of its highlights (ArticleHighlights, Michael 2026-10-12) —
        // the passage in hand when one is selected, that highlight's own when it overlaps one, the
        // whole piece otherwise. Every keeping row keeps the passage first and puts the note on
        // its ONE card.
        if (marksOn(e)) { articleHighlights.note(); return }
        // Letters and articles are the same pane dressed two ways, and since the letter dress
        // runs the selection bridge too (showMailInPane), a highlighted passage in a letter
        // reaches the chooser exactly as an article's does — "❝ Save highlight" first. A
        // letter's provenance rides its mail:// address, the same discriminator every other
        // mail annotation carries.
        com.toolsboox.plugin.feeds.ot.WebSelectionBridge.probe(binding.articleWeb) { sel ->
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.show(
                this, calendarDayService, documentsRoot(),
                selection = sel, articleTitle = e.title, feedTitle = e.feedTitle, articleUrl = e.url,
                captureAv = { kind, sink -> captureAvGramDirect(kind, sink) },
                logEvent = { excerpt, note ->
                    lifecycleScope.launch(Dispatchers.IO) { logArticleEvent(e, excerpt = excerpt, note = note) }
                },
                // ★ on the chooser too — the canon's sixth row. toggleStar knows a letter from
                // an article (the MAIL ceremony vs the Miniflux/ReadingEvent one).
                star = { toggleStar(e) }
            )
        }
    }

    private fun logArticleEvent(e: FeedEntry, excerpt: String? = null, note: String? = null) {
        runCatching {
            val root = documentsRoot()
            val today = java.time.LocalDate.now()
            // Under the day lock (design Phase 0): an unlocked load→save could drop the card or the
            // line another writer was saving to today at that moment.
            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
            // Throws (inside the runCatching) rather than save over a today-file that won't read.
            val day = calendarDayService.loadForWriteOrThrow(root, today, null, java.util.Locale.getDefault())
            day.readingEvents.add(
                com.toolsboox.plugin.calendar.da.v2.ReadingEvent(
                    id = "art-${e.id}-${java.util.UUID.randomUUID().toString().take(8)}",
                    kind = com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.ARTICLE,
                    date = java.util.Date(), title = e.title,
                    source = e.feedTitle.ifBlank { null }, url = e.url.ifBlank { null },
                    excerpt = excerpt, note = note
                )
            )
            calendarDayService.save(root, today, day)
            }
        }
    }

    /** Read the open article aloud via the process-wide player (keeps playing after you leave),
     *  and open the transport modal. If something is already playing, just open the modal. */
    private fun toggleArticleTts() {
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        if (!player.isActive) {
            val e = currentArticle ?: return
            // Prefer the reader-view (readability) full text over the raw RSS content, which is often
            // just the title/byline blurb — that's why TTS "couldn't get past" them. Collapse runaway
            // whitespace so the engine doesn't stall between chunks.
            val parsed = com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(requireContext(), e.id)
            val body = if (!parsed.isNullOrBlank()) parsed else e.content
            val plain = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("\\s+"), " ").trim()
            player.start(
                requireContext(), e.title, e.feedTitle.ifBlank { null }, e.imageUrl,
                listOf(e.title, plain).filter { it.isNotBlank() }.joinToString(". "),
                capture = com.toolsboox.ui.plugin.LedgerPlayer.Capture(
                    e.title, e.feedTitle, e.url, e.imageUrl, excerpt = plain.take(200))
            )
            wireTtsFollow()
        }
        renderDirectory()   // seat the inline Now Playing card; the drawer card is the transport, no auto-popup
    }

    /** The page follows the voice (the book reader's follow-along, on the article and the letter
     *  too — Michael, 09-11: "I love when the text is highlighted while it's being read along").
     *  Ours is remembered so closing the pane clears only OUR hook, never a book's. */
    private var ttsFollowHook: ((String) -> Unit)? = null
    private fun wireTtsFollow() {
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        binding.articleWeb.evaluateJavascript(com.toolsboox.plugin.feeds.ot.TtsFollowJs.SCRIPT, null)
        val hook: (String) -> Unit = { sentence ->
            if (isAdded && requireContext().getSharedPreferences("MAIN", 0).getBoolean("tts_follow", true)) {
                val q = org.json.JSONObject.quote(sentence)
                binding.articleWeb.post {
                    runCatching { binding.articleWeb.evaluateJavascript("window.__ledgerFollow && window.__ledgerFollow($q)", null) }
                }
            }
        }
        ttsFollowHook = hook
        player.onFollowSentence = hook
    }

    private fun buildArticleHtml(e: FeedEntry, content: String = e.content): String {
        val meta = listOf(e.feedTitle, e.author ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // Somebody else's HTML: allowlist-clean it (scripts/on*/javascript: gone, relative media
        // absolutised against the entry url) BEFORE the YouTube rewrite — the sanitizer lets the
        // YouTube embed iframes through, and cleaning first keeps the rewrite's own inline-styled
        // thumbnail card intact. Same pass as the standalone Articles reader.
        val safeBody = rewriteYouTubeEmbeds(com.toolsboox.plugin.feeds.ot.ArticleSanitizer.clean(content, e.url))
        // Face, size, spacing, width and colours are Aa Type's article profile — the same block the
        // standalone reader carries (Michael 2026-10-11). Its Sides start at this page's old 44px
        // gutter: wide enough that the vertical pill at its smallest parks in the margin without
        // covering text. This page keeps only its frame and the listen bar.
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>${com.toolsboox.ot.AaType.ARTICLE_FRAME_CSS}
              /* Square corners and a plain rule, the page's own idiom — see the dashboard note.
                 currentColor: the rules follow the Aa Type colours (black on the default page). */
              .listenbar { display: flex; gap: 12px; margin: 0 0 20px 0;
                           border-top: 1px solid currentColor; border-bottom: 1px solid currentColor; padding: 10px 0; }
              .lb { text-decoration: none; font-family: sans-serif; font-size: 15px;
                    border: 1px solid currentColor; padding: 8px 14px; }
            </style>${com.toolsboox.ot.AaType.articleStyleTag(com.toolsboox.ot.ReadingTypes.articleCss(requireContext()))}${if (marksOn(e)) articleHighlights.headScript() else ""}</head><body>
            <h1>${esc(e.title)}</h1>
            <div class="meta">${esc(meta)}</div>
            ${buildListenBar(e)}
            $safeBody
            </body></html>
        """.trimIndent()
    }

    /**
     * The episode page's two verbs — ▶ Play and the ⬇ that keeps it — or "" for anything that is
     * not an episode.
     *
     * DRAWN INTO THE PAGE rather than added as chrome around it, for the same reason the day
     * dashboard is: the pane is a WebView showing an article, and a floating control over it would
     * have to be positioned against a document it cannot see inside. Two links on a `ledger://`
     * scheme is the idiom this pane already uses (the Bluesky reply door), and the WebView's own
     * navigation interception is what turns them back into Kotlin.
     *
     * The ⬇ says which state it is in, because "downloaded" and "asked for but not here yet" are
     * genuinely different and a person mid-commute needs to know which one they have.
     */
    private fun buildListenBar(e: FeedEntry): String {
        // LISTEN **AND** WATCH. The bar was written for episodes and the machinery under it never
        // cared: ListenState is keyed by entry URL and knows nothing about audio, LaterMedia
        // downloads any http URL, and "keep this" means the same thing about a video. Michael,
        // 2026-08-23: "we can set the same download infrastructure for youtube videos, too."
        if (e.kind != "listen" && e.kind != "watch") return ""
        val ctx = context ?: return ""
        // The FILE, when the feed names one. A YouTube entry usually names only a watch PAGE, which
        // is not a thing that can be fetched — his VPS is what turns one into a file on R2. So the
        // ask is recorded either way and the button says which of those two worlds it is in; when
        // the R2 address is known, the only change is that [directMedia] stops being null.
        val direct = directMedia(e)
        val id = e.url.ifBlank { direct.orEmpty() }
        if (id.isBlank()) return ""
        val here = direct != null && com.toolsboox.plugin.feeds.nw.LaterMedia.isDownloaded(ctx, direct)
        val wanted = com.toolsboox.plugin.calendar.ot.ListenStateStore.get(ctx, id)?.wantOffline == true
        val why = if (here) com.toolsboox.plugin.feeds.nw.LaterMedia.reasonLabel(ctx, direct!!) else null
        val saveLabel = when {
            // SAYS WHY IT IS HERE (09-19): "you asked" travelled from any device; "newest 8" is
            // the silent sweep. Tap = remove it (and, for an ask, withdraw the ask).
            here -> "✓  On this device" + (why?.let { " · $it" } ?: "")
            // "Queued" rather than "Waiting to download" when there is nothing to fetch YET: the
            // ask is real and synced, the file is somebody else's job, and saying "downloading"
            // about a download that cannot start would be the interface lying to a man on a train.
            wanted && direct == null -> "⏳  Queued for download"
            wanted -> "⏳  Waiting to download"
            else -> "⬇  Keep offline"
        }
        return """
            <div class="listenbar">
              <a class="lb" href="$LISTEN_PLAY_SCHEME">▶  Play</a>
              <a class="lb" href="$LISTEN_SAVE_SCHEME">$saveLabel</a>
            </div>
        """.trimIndent()
    }

    /**
     * The FETCHABLE media URL for an entry, or null when the feed named only a page.
     *
     * An enclosure is a file. A YouTube watch link is not — downloading it would store an HTML page
     * under a name that claims to be a video, and the failure would only show up as silence with no
     * signal. Keeping that judgment in one function is what lets the bar, the toggle and the fulfil
     * pass all agree about which entries can actually be fetched today.
     */
    private fun directMedia(e: FeedEntry): String? =
        e.mediaUrl?.takeIf { it.startsWith("http", ignoreCase = true) }

    /**
     * Ask for (or give back) an episode's offline copy — and RECORD THE ASK ON THE SYNCED RECORD,
     * which is the half that makes it worth a verb at all.
     *
     * The download itself is this device doing what it was told; [ListenState.wantOffline] is the
     * telling, and it travels. Another device that opens Ledger sees the merged record, finds it
     * has no file for an episode that was asked for, and fetches it — so "keep this" said on the
     * Boox is true on the iPad without saying it twice, and the playhead was already syncing.
     */
    private fun toggleListenOffline(e: FeedEntry) {
        val ctx = context ?: return
        val direct = directMedia(e)
        val id = e.url.ifBlank { direct.orEmpty() }
        if (id.isBlank()) return
        val store = com.toolsboox.plugin.calendar.ot.ListenStateStore
        val want = store.get(ctx, id)?.wantOffline != true
        // The ask names what it asks for (10-12). A shelf row's feed line is "show · N min left",
        // not a show, so it adds nothing there — the record already knows its show.
        val shelf = isShelfRow(e)
        store.setWantOffline(ctx, id, want, audioUrl = direct.orEmpty(),
            title = if (shelf) "" else e.title, show = if (shelf) "" else e.feedTitle,
            artworkUrl = (if (shelf) e.imageUrl else com.toolsboox.plugin.feeds.nw.ShowCovers.artFor(ctx, e)).orEmpty())
        if (want && direct == null) {
            // Recorded, synced, and honest about it: nothing here can fetch a YouTube watch page,
            // so the ask waits for the side that can. The transcript is still worth gathering now.
            showMessage("Queued — it'll download when the file is ready")
            fetchTranscriptForOffline(e)
            renderArticle()
            return
        }
        val audio = direct ?: return
        if (want) {
            showMessage("Downloading…")
            // THE WORDS COME WITH IT (Michael, 2026-08-23: "if there is a transcript of the watch
            // or the listen available, you can gather those too for offline viewing when
            // downloaded if they aren't already included before download").
            //
            // FeedChapters.transcript already resolves the podcast:transcript pointer, fetches,
            // normalises SRT/VTT/JSON to "[m:ss] line" and caches it — it was simply LAZY, fetched
            // when you opened the transcript. Lazy is the one thing it must not be here: the whole
            // point of keeping an episode is the moment you have no signal, and a transcript that
            // fetches on view is guaranteed absent exactly then. Off the main thread, and quiet —
            // a feed with no transcript is the common case, not a failure worth reporting.
            fetchTranscriptForOffline(e)
            com.toolsboox.plugin.feeds.nw.LaterMedia.undismiss(ctx, audio)   // an ask outranks an old removal
            com.toolsboox.plugin.feeds.nw.LaterMedia.download(ctx, audio, e, com.toolsboox.plugin.feeds.nw.LaterMedia.REASON_ASKED) { f ->
                activity?.runOnUiThread {
                    showMessage(if (f != null) "Saved for offline" else "Couldn't download that episode")
                    // Repaint so the ⬇ says its new state; only if you are still on that episode.
                    if (currentArticle?.id == e.id) renderArticle()
                }
            }
        } else {
            com.toolsboox.plugin.feeds.nw.LaterMedia.remove(ctx, audio, dismiss = true)
            showMessage("Removed the offline copy — it won't come back unless you ask again")
            renderArticle()
        }
    }

    /**
     * Fetch what the FLEET asked for and this device does not have yet.
     *
     * This is the other half of [toggleListenOffline] and the reason the ask is on the synced
     * record rather than beside the file: "keep this" said on the Boox becomes a file on the iPad
     * without being said twice. Run on arrival at The Listen — the moment you are about to want
     * them — and after a merge, which is when a new ask can first be known.
     *
     * Quiet by design: no toast, no progress. These are downloads you asked for on another device
     * and then walked away from; announcing them here would be the app reporting on its chores.
     * The ⬇ on each episode page tells the truth when you go and look.
     */
    private fun fulfilOfflineAsks() {
        val ctx = context ?: return
        val media = com.toolsboox.plugin.feeds.nw.LaterMedia
        // The entries this device knows about, so an ask can be matched back to the episode it
        // came from — which is what makes its transcript fetchable here too.
        val known = runCatching { com.toolsboox.plugin.feeds.nw.FeedCache.loadFreshestEntries(ctx) }
            .getOrDefault(emptyList())
        // The files themselves: LaterMedia's headless fulfil (reason "asked", removed-by-hand
        // honoured). What only this surface can add is the transcript, resolved off the entry.
        media.fulfilWanted(ctx)
        for (s in com.toolsboox.plugin.calendar.ot.ListenStateStore.wanted(ctx)) {
            val url = s.audioUrl
            if (url.isBlank()) continue
            // Best-effort: an ask made on another device may name an episode this one has never
            // listed, and then there is no entry to resolve a transcript pointer from. The audio
            // still arrives; the words arrive the next time the feed brings the episode through.
            known.firstOrNull { it.url == s.id || it.audioUrl == url }?.let { e ->
                media.remember(ctx, url, e, com.toolsboox.plugin.feeds.nw.LaterMedia.REASON_ASKED)
                fetchTranscriptForOffline(e)
            }
        }
    }

    /**
     * Note this entry in the media log — what you read, watched and heard.
     *
     * EVERYTHING CONSUMED IS RECORDED (his ruling): the failure mode is a long list, and
     * [MediaLogStore.prune] handles that, while the failure mode of logging only what you starred
     * is a history that does not contain the thing you went looking for.
     *
     * WHAT COUNTS, honestly:
     *  - **read** — an article opened. This is the same moment the pane marks it read, so the log
     *    and the unread count can never tell different stories about the same tap.
     *  - **watch** — a video's page opened. AN ACKNOWLEDGED APPROXIMATION: the embed is a WebView
     *    iframe and its own play event is not observable without the YouTube JS API, so "you went
     *    to the video" stands in for "you watched it". It is the closest honest signal available
     *    today, and it is written down here rather than quietly assumed.
     *  - **listen** — recorded on PLAY, not on open (see [playEntryAudio]). Opening an episode to
     *    read its show notes is not listening to it, and since autoplay came off those are two
     *    genuinely different acts.
     *
     * MAIL IS NOT MEDIA and never lands here: a letter is correspondence, it has its own store and
     * its own read state, and a reading history that filled up with email would answer the question
     * it exists for less well, not more.
     */
    private fun recordInMediaLog(e: FeedEntry) {
        val ctx = context ?: return
        if (e.kind == KIND_MAIL || mailById.containsKey(e.id)) return
        if (e.url == MAIL_SEARCH_SCHEME) return                       // a control row, not a thing read
        val kind = when (e.kind) {
            "watch" -> com.toolsboox.plugin.calendar.da.v2.MediaLogEntry.WATCH
            // An episode page opened is reading, not listening — playEntryAudio records the other.
            "listen" -> com.toolsboox.plugin.calendar.da.v2.MediaLogEntry.READ
            else -> com.toolsboox.plugin.calendar.da.v2.MediaLogEntry.READ
        }
        val id = e.url.ifBlank { e.audioUrl.orEmpty() }
        if (id.isBlank()) return
        com.toolsboox.plugin.calendar.ot.MediaLogStore.record(
            ctx, id = id, kind = kind, title = e.title, source = e.feedTitle, url = e.url,
            feedId = e.id)
    }

    /** Pull an entry's transcript into the cache now rather than when it is next opened — see the
     *  note in [toggleListenOffline] for why "now" is the whole point. Quiet on every failure. */
    private fun fetchTranscriptForOffline(e: FeedEntry) {
        val ctx = context?.applicationContext ?: return
        Thread {
            runCatching { com.toolsboox.plugin.feeds.nw.FeedChapters.transcript(ctx, e) }
        }.start()
    }

    /**
     * YouTube blocks many embeds in a WebView (error 150/152 — owner disabled embedded playback),
     * leaving a dead player. Rewrite every YouTube iframe into a tappable thumbnail that opens the
     * watch page — same fix the standalone article view already uses, now in the in-pane reader too.
     */
    private fun rewriteYouTubeEmbeds(content: String): String {
        val iframe = Regex(
            """<iframe[^>]*src=["'][^"']*(?:youtube(?:-nocookie)?\.com/embed/|youtu\.be/)([A-Za-z0-9_\-]{6,})[^"']*["'][^>]*>\s*</iframe>""",
            RegexOption.IGNORE_CASE
        )
        return iframe.replace(content) { m ->
            val id = m.groupValues[1]
            """<a href="https://www.youtube.com/watch?v=$id" style="display:block;text-decoration:none">
               <img src="https://img.youtube.com/vi/$id/hqdefault.jpg" style="width:100%;display:block"/>
               <span style="display:block;padding:8px 0;font-weight:bold;color:inherit">▶ Watch on YouTube</span>
               </a>"""
        }
    }

    /**
     * The Watch's skinned episode page for a YouTube entry. The video leads: full-width
     * thumbnail with a solid ▶ badge — tapping swaps in the real embed (autoplay, and
     * `?start=SECONDS` when a chapter row asked for a jump). Under it: title, channel · date
     * in the small mono meta voice, the CHAPTER TABLE as tappable rows (times + titles, the
     * same Chapter shape the player uses), then the description with the timestamp lines
     * removed — they became the table, no need to say them twice. Solid black rules on
     * white throughout; nothing shaded to vanish on e-ink.
     */
    private fun buildYouTubeHtml(e: FeedEntry, videoId: String): String {
        // Quotes escaped too — [thumb] below is feed-supplied and lands inside a src="…" attribute.
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val chaptersLib = com.toolsboox.plugin.feeds.nw.FeedChapters
        val chapters = chaptersLib.quick(requireContext(), e)
        val date = publishedDate(e)?.format(
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.getDefault())
        ).orEmpty()
        val meta = listOf(e.feedTitle, date).filter { it.isNotBlank() }.joinToString("  ·  ")
        // The description as clean prose: line structure kept, chapter lines lifted out
        // (they render as the table above), runs of blank lines collapsed.
        val desc = chaptersLib.htmlToLines(e.content)
            .filterNot { chapters.isNotEmpty() && chaptersLib.isStampLine(it) }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        val thumb = e.enclosureImage?.takeIf { it.startsWith("http") }
            ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        val chapterRows = chapters.joinToString("\n") { c ->
            """<div class="ch" onclick="play(${c.startSec})"><span class="t">${c.clock}</span><span>${esc(c.title)}</span></div>"""
        }
        val chapterBlock = if (chapters.isEmpty()) ""
        else """<div class="chaps">$chapterRows</div>"""
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; color: #000; background: #fff; font-family: serif; }
              .wrap { padding: 18px 22px 28px; }
              #player { position: relative; width: 100%; background: #000; }
              #player img { width: 100%; display: block; }
              #player .cue { position: absolute; top: 0; left: 0; right: 0; bottom: 0;
                             display: flex; align-items: center; justify-content: center; }
              #player .cue span { font-size: 30px; color: #000; background: #fff;
                                  border: 2px solid #000; border-radius: 999px;
                                  padding: 14px 26px; line-height: 1; }
              #player iframe { width: 100%; aspect-ratio: 16 / 9; border: 0; display: block; }
              h1 { font-size: 22px; line-height: 1.3; margin: 16px 0 4px; }
              .meta { font-family: ui-monospace, Menlo, monospace; font-size: 12.5px;
                      color: #444; margin: 0 0 14px; }
              .chaps { border: 1px solid #000; border-radius: 8px; margin: 0 0 16px; overflow: hidden; }
              .ch { display: flex; gap: 12px; padding: 11px 12px; border-top: 1px solid #000; }
              .ch:first-child { border-top: 0; }
              .ch .t { font-family: ui-monospace, Menlo, monospace; font-weight: bold;
                       min-width: 52px; text-align: right; }
              .desc { font-size: 16px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: break-word; }
            </style></head><body>
            <div id="player" onclick="if(!played)play(0)">
              <img src="${esc(thumb)}"/><div class="cue"><span>▶</span></div>
            </div>
            <div class="wrap">
              <h1>${esc(e.title)}</h1>
              <div class="meta">${esc(meta)}</div>
              $chapterBlock
              <div class="desc">${esc(desc)}</div>
            </div>
            <script>
              var played = false;
              function play(s) {
                played = true;
                var p = document.getElementById('player');
                p.innerHTML = '<iframe src="https://www.youtube-nocookie.com/embed/$videoId?start=' + s +
                  '&autoplay=1&rel=0&playsinline=1" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>';
              }
            </script>
            </body></html>
        """.trimIndent()
    }

    /** One entry, one upgrade pass: the skin renders instantly from the quick (cache/description)
     *  chapters, while the full resolve — which may walk back to the feed XML for a real
     *  `podcast:chapters` document — runs behind it and re-renders ONCE if it found more. */
    private var chapterResolveKickedFor: Long? = null
    private fun kickChapterResolve(entry: FeedEntry) {
        if (chapterResolveKickedFor == entry.id) return
        chapterResolveKickedFor = entry.id
        val quick = com.toolsboox.plugin.feeds.nw.FeedChapters.quick(requireContext(), entry)
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val full = runCatching {
                com.toolsboox.plugin.feeds.nw.FeedChapters.forEntry(appCtx, entry)
            }.getOrDefault(quick)
            if (full != quick) withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isAdded && currentArticle?.id == entry.id) renderArticle()
            }
        }
    }

    /** Stepping/selecting keeps the chosen lens (The Watch stays The Watch across dates);
     *  today = the live view, past windows pull the read timeline for filtering. */
    private fun onDateChanged() {
        // The Mail stacks the two axes instead of trading one for the other. An article window
        // becomes the EDITION (a server fetch by publish date, replacing the view) because the
        // corpus lives on Miniflux; mail is all on the device, so the almanac window is a plain
        // local filter that COMPOSES with whatever chip is lit — Starred ∩ July is a real
        // question and it has a real answer. Nothing selected (day-on-today) means no date
        // filter at all: the whole pile, exactly like the feeds' anchored-but-unfiltered state.
        if (kindFilter == KIND_MAIL) {
            updateWindowLabel()
            refresh()
            return
        }
        // Today = the live unread feed. Any other window = that day's EDITION: everything
        // published inside the window (read and unread alike), fetched server-side by
        // published date — stepping the date nav pages through finished daily papers.
        // (The old mode="read" showed only read items that happened to be in the last-100
        // fetch, so past days were incomplete or empty.)
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        mode = if (liveToday) "feed" else "edition"
        updateWindowLabel()
        refresh()
    }


    /** Link menu for the in-pane reader - mirrors the standalone Articles reader's. When the held
     *  thing was an image-anchor, [imageUrl] adds the photo-gram option alongside the link ones.
     *  Through [showModal] like the note chooser \u2014 a stylus hold summons this over the same
     *  ink-capable pane, and an unrouted popover leaves the pen armed under it (the freeze). */
    private fun showPaneLinkMenu(url: String, imageUrl: String? = null) {
        val items = mutableListOf("\ud83c\udf10  Open", "\ud83d\udd16  Save to Later List", "\ud83d\udccb  Copy link")
        if (imageUrl != null) items.add(com.toolsboox.plugin.feeds.ot.FeedNoteGram.photoGramLabel(requireContext()))
        showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(url)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    1 -> {
                        // A link the reader already knows carries its own presentation \u2014 title,
                        // excerpt, featured image \u2014 so the Later row shows the thing, not the URL.
                        val known = (listOfNotNull(currentArticle) + allEntries).firstOrNull { it.url == url }
                        com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                            requireContext(), java.time.LocalDate.now(), "read", url,
                            known?.title, excerpt = known?.blurb, image = known?.imageUrl)
                        showMessage("Saved to Later List", binding.root)
                    }
                    2 -> {
                        val cb = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                        showMessage("Link copied", binding.root)
                    }
                    3 -> {
                        val a = currentArticle
                        com.toolsboox.plugin.feeds.ot.FeedNoteGram.savePhotoGram(
                            this, calendarDayService, documentsRoot(), imageUrl ?: url,
                            a?.title.orEmpty(), a?.feedTitle.orEmpty(), a?.url.orEmpty())
                    }
                }
            }.create())
    }

    /** When the ARTICLE corpus was last actually loaded — 0 until the first real load. */
    private var corpusUpdatedMs = 0L

    /** The MAIL pile's own stamp, held apart from [corpusUpdatedMs]: the two corpora load on
     *  different clocks, and one scalar between them meant The Mail wore the feed load's time —
     *  "mail rows' 'updated at' untrustworthy" (08-17 queue). 0 until an IMAP pull lands. */
    private var mailUpdatedMs = 0L

    /** Stamp the ACTIVE corpus as freshly loaded and say so. "EMAIL feed — needs a 'Last Updated'
     *  note. ALL feeds actually, could use that addition" (08-17 page). */
    private fun stampUpdated() {
        if (kindFilter == KIND_MAIL) mailUpdatedMs = System.currentTimeMillis()
        else corpusUpdatedMs = System.currentTimeMillis()
        updateWindowLabel()
    }

    /** The label under the strip. Its window-words job retired on-device (07-24: the strip's
     *  focal slot already names the filter, and saying it twice read as a bug) — the seat now
     *  carries the freshness stamp instead: one quiet line saying the thing the strip cannot,
     *  when the rows on screen were last true. Reads the stamp of the corpus SHOWING — never
     *  the other one's. GONE until that corpus's first real load, so nothing is promised
     *  that hasn't happened. */
    private fun updateWindowLabel() {
        if (!::binding.isInitialized) return
        val stampMs = if (kindFilter == KIND_MAIL) mailUpdatedMs else corpusUpdatedMs
        // A REMEMBERED NARROWING SAYS SO (2026-10-11 review): a feed or category pick now outlives
        // the visit — a gram's Go to feed, a folded category, a restart — so the line names it,
        // and a tap on it lets go (☀ Today does the same, along with the rest).
        val narrowed = pickShowing()
        // The Mail names its mailbox the same way when it isn't 📥 Inbox (see [mailShowing]).
        val mailbox = mailShowing()
        if (stampMs <= 0L && narrowed == null && mailbox == null) { binding.feedsWindowLabel.visibility = View.GONE; return }
        val t = if (stampMs <= 0L) null else java.time.Instant.ofEpochMilli(stampMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        binding.feedsWindowLabel.apply {
            text = listOfNotNull(
                mailbox?.let { "$it  ✕" }, narrowed?.let { "Only “$it”  ✕" }, t?.let { "Updated $it" }
            ).joinToString("  ·  ")
            if (mailbox != null) setOnClickListener { leaveMailboxForInbox() }
            else if (narrowed != null) setOnClickListener { clearPick(); renderDirectory(); refresh() }
            else { setOnClickListener(null); isClickable = false }
            setTypeface(null, android.graphics.Typeface.NORMAL)
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            visibility = View.VISIBLE
        }
    }

    /** The active window, named plainly: "Week 30 · Jul 20–26" / "July 2026" / "Q3 2026". */
    private fun windowLabel(): String {
        val loc = java.util.Locale.getDefault()
        val md = java.time.format.DateTimeFormatter.ofPattern("MMM d", loc)
        val (start, end) = navWindow()
        return when (navGranularity) {
            "week" -> {
                val wk = navAnchor.get(java.time.temporal.WeekFields.of(loc).weekOfWeekBasedYear())
                "Week $wk · ${start.format(md)}–${end.minusDays(1).format(md)}"
            }
            "month" -> navAnchor.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", loc))
            "quarter" -> "Q${(navAnchor.monthValue - 1) / 3 + 1} ${navAnchor.year}"
            "year" -> "${navAnchor.year}"
            else -> navAnchor.format(java.time.format.DateTimeFormatter.ofPattern("EEE · MMM d", loc))
        }
    }

    private fun stepByGranularity(date: java.time.LocalDate, dir: Int): java.time.LocalDate = when (navGranularity) {
        "week" -> date.plusWeeks(dir.toLong())
        "month" -> date.plusMonths(dir.toLong())
        "quarter" -> date.plusMonths(3L * dir)
        "year" -> date.plusYears(dir.toLong())
        else -> date.plusDays(dir.toLong())
    }

    /** [start, end) of the current window (granularity + anchor). */
    private fun navWindow(): Pair<java.time.LocalDate, java.time.LocalDate> {
        val a = navAnchor
        return when (navGranularity) {
            "week" -> {
                val s = a.with(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).dayOfWeek(), 1)
                s to s.plusWeeks(1)
            }
            "month" -> { val s = a.withDayOfMonth(1); s to s.plusMonths(1) }
            "quarter" -> {
                val s = a.withDayOfMonth(1).withMonth((a.monthValue - 1) / 3 * 3 + 1); s to s.plusMonths(3)
            }
            "year" -> { val s = a.withDayOfYear(1); s to s.plusYears(1) }
            else -> a to a.plusDays(1)
        }
    }

    /** Keep only articles published inside the navigator window (live view when it's today's day). */
    private fun filterByNavDay(entries: List<FeedEntry>): List<FeedEntry> {
        if (navGranularity == "day" && navAnchor == java.time.LocalDate.now()) return entries
        val (start, end) = navWindow()
        return entries.filter { val d = publishedDate(it); d != null && !d.isBefore(start) && d.isBefore(end) }
    }

    private fun publishedDate(e: FeedEntry): java.time.LocalDate? = runCatching {
        java.time.OffsetDateTime.parse(e.publishedAt)
            .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: runCatching {
        // Later-list rows carry a bare date — the day the link was ADDED (that's the day
        // they should file under in the timeline, not their publication date).
        java.time.LocalDate.parse(e.publishedAt)
    }.getOrNull()

    /** Redraw the Almanac navigator strip for the current anchor date. */
    private fun renderNav() {
        lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.loadForDisplay(root, navAnchor, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        navAnchor.year, navAnchor.monthValue, navAnchor.dayOfMonth, startHour = null)
                // A missing/failed pattern must not kill the strip: an unrendered
                // CalendarNavBarHost never sets its currentDay, and a day-less strip swallows
                // every touch — the date filter would render once, then silently no-op.
                val p = runCatching { calendarPatternService.load(root, navAnchor, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(navAnchor.year, loc).fill()
                cd to p
            }
            if (isAdded) {
                // The live feed (day granularity, anchored on today) is UNFILTERED — every
                // unread item, yesterday's included (see filterByNavDay's escape). The strip
                // used to dress today as the focal slot anyway, promising a day filter it
                // wasn't applying — "the almanac says today's date but it's pulling up
                // yesterday's feeds". The strip already has the honest idiom for this:
                // anchored-but-not-filtered draws NO focal slot. Claim a focus only when a
                // filter is really on.
                navBar?.setGranularity(
                    if (navGranularity == "day" && navAnchor == java.time.LocalDate.now()) null
                    else navGranularity
                )
                navBar?.render(day, pat)
            }
        }
    }

    /** Page up (-1) / down (+1): the in-pane article when open, else the article list. */
    private fun pageBy(dir: Int) {
        if (binding.articlePane.visibility == View.VISIBLE) {
            feedsPanel?.readerPageTurn()   // before the page moves (ReaderFullScreen)
            binding.articleWeb.scrollBy(0, dir * binding.articleWeb.height * 9 / 10)
        } else {
            binding.feedsRecycler.scrollBy(0, dir * binding.feedsRecycler.height * 9 / 10)
        }
    }

    /** Pop the left feed-directory drawer in/out (persisted, no animation). */
    private fun applyDirectoryDrawer(open: Boolean) {
        setDirectoryDrawerVisible(open)
        prefs().edit().putBoolean(drawerOpenKey(), open).apply()
    }

    /** Each room remembers its own drawer (the two would fight over one key); The Mail's starts
     *  wherever the shared one stood, so nothing changes on the first visit after the split. */
    private fun drawerOpenKey() = if (isMailRoom) KEY_MAIL_DIR_OPEN else KEY_DIR_OPEN
    private fun drawerOpenPref(): Boolean {
        val p = prefs()
        val shared = p.getBoolean(KEY_DIR_OPEN, true)
        return if (isMailRoom) p.getBoolean(KEY_MAIL_DIR_OPEN, shared) else shared
    }

    /** Show/hide the drawer views WITHOUT touching the persisted preference — used to give the
     *  article reader full width while it's open, then restore the drawer to its saved state. */
    private fun setDirectoryDrawerVisible(open: Boolean) {
        val vis = if (open) View.VISIBLE else View.GONE
        val changed = binding.directoryScroll.visibility != vis
        binding.directoryScroll.visibility = vis
        binding.directoryDivider.visibility = vis
        // (No rail re-ask here: the Feeds seat that was boxed while the drawer stood open left the
        // column 2026-10-12, and no seat follows the drawer now — review 2026-10-12.)
        // The Mail pane's unread counts catch up with the letters read while it was shut.
        if (changed && open) recountMail()
    }

    /** Set the current view/kind and reload (used by the dropdown rows). */
    private fun switchTo(newMode: String, kind: String?) {
        // TWO ROOMS (Michael 2026-10-11): The Mail never wears an RSS lens and Feeds never wears
        // The Mail — a switch across rooms goes to the other room instead.
        if (isMailRoom && kind != KIND_MAIL) { openFeedView(findNavController(), newMode, kind, popMail = true); return }
        if (!isMailRoom && kind == KIND_MAIL) { openMailRoom(findNavController(), mode = newMode, popFeeds = true); return }
        // A view switch ends any Later selection in progress — the checkboxes belong to the list
        // they were ticked on, and a bar promising "Delete (3)" over a different corpus is a bar
        // that deletes rows nobody is looking at. (No-op when not selecting.)
        exitLaterSelection()
        // Picking a lens while browsing a PAST window stays in that window (read timeline),
        // instead of snapping to the live unread view — the date and the lens compose. Mail is
        // exempt from the feed→read coercion: its unread state is local, so "unread in July" is
        // answerable directly and the chip should keep meaning what it says.
        val liveToday = navGranularity == "day" && navAnchor == java.time.LocalDate.now()
        mode = if (newMode == "feed" && !liveToday && kind != KIND_MAIL) "read" else newMode
        kindFilter = kind
        // Leaving the Later List drops its lane with it: a lane is a narrowing of THAT corpus and
        // means nothing over any other, and a stale one would silently narrow the list the next
        // time he came back — the drawer saying "Later List" while showing only 📧 Email.
        if (newMode != "later") laterLane = null
        refresh()
    }

    private fun switchToSmart(feed: com.toolsboox.plugin.feeds.nw.SmartFeed) {
        // A smart feed is an RSS search — never over letters (MAIL SEARCH STAYS IN MAIL).
        if (isMailRoom) { Timber.w("mail room: smart feed refused"); return }
        mode = "smart"; smartFeed = feed; kindFilter = null; refresh()
    }

    /** Smart feed: server-wide search when online, filter the last-loaded entries when offline. */
    private fun loadSmart() {
        val feed = smartFeed ?: return
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) { showEmpty(getString(R.string.feeds_need_creds)); return }
        loading = true
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { miniflux.search(url, token, feed.query) }
            if (finishLoad()) return@launch
            corpusKey = "smart"
            when (result) {
                is MinifluxClient.Result.Ok -> {
                    // (Miniflux search spans all statuses; this list has no per-entry read flag.)
                    val shown = result.value
                    allEntries = shown
                    adapter.submit(shown)
                    if (shown.isEmpty()) showEmpty(getString(R.string.feeds_empty))
                    else binding.emptyText.visibility = View.GONE
                }
                is MinifluxClient.Result.Err -> {
                    // Offline: filter whatever's already loaded by the saved query.
                    val q = feed.query.lowercase()
                    val cached = allEntries.filter {
                        it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q)
                    }
                    if (cached.isNotEmpty()) adapter.submit(cached)
                    else showEmpty("⚠️ " + result.message)
                }
            }
        }
    }

    /** Prompt for a new smart feed (name + search terms) and save it. */
    private fun promptAddSmartFeed() {
        val ctx = requireContext()
        val name = android.widget.EditText(ctx).apply { hint = "Name (e.g. Ashtanga)" }
        val query = android.widget.EditText(ctx).apply { hint = "Search terms" }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(8), dpPx(20), 0)
            addView(name); addView(query)
        }
        // Guarded: a name and search terms being typed are work — a stray touch outside must
        // not throw them away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("New smart feed")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val q = query.text.toString().trim()
                if (q.isNotBlank()) {
                    val n = name.text.toString().trim().ifBlank { q }
                    com.toolsboox.plugin.feeds.nw.SmartFeedStore.add(
                        ctx, com.toolsboox.plugin.feeds.nw.SmartFeed(java.util.UUID.randomUUID().toString(), n, q))
                    showFeedDirectory()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** Tapping a category drills one level down: a sub-menu of the specific feeds inside it
     *  (plus "All"). Tapping a feed filters to just that feed. */
    private fun showCategory(label: String) {
        val inCat = allEntries.filter { it.categoryLabel == label }
        val feeds = inCat.map { it.feedTitle }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        if (feeds.size <= 1) { adapter.submit(inCat); return }
        val rows = listOf<Pair<String, () -> Unit>>("📰  All — $label" to { adapter.submit(inCat) }) +
            feeds.map { f -> ("📰  $f" to { adapter.submit(inCat.filter { it.feedTitle == f }) }) }
        showDirectory(listOf(label to rows))
    }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Feed-specific settings (shown from the wrench) — as a dialog so it works while reading. */
    /** The wrench modal: mark-all-read / refresh, rotate the screen, flip the pill's orientation. */
    private fun showFeedControls() {
        val vertical = prefs().getBoolean("feeds_pill_vertical", false)
        val mode = markReadMode()
        fun tick(m: String) = if (mode == m) "◉" else "○"
        // Accessibility: Small / Medium / Large for the feed rows and for every modal.
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        val feedTier = a11y.getString("feed_text_size", "medium")
        fun ft(m: String) = if (feedTier == m) "◉" else "○"
        fun setFeedTier(m: String) {
            a11y.edit().putString("feed_text_size", m).apply()
            adapter.textTier = m
        }
        // ☰ Hub, then the room's drawer, head the wrench (Michael 2026-10-12: the seats left the
        // column — the left edge's Back opens the drawer, then the Hub; these two rows are the
        // touch route, and the only one where Back carries no edge: 3-button navigation, Android
        // 11–13). The drawer row names what a tap does.
        val drawerOpen = binding.directoryScroll.visibility == View.VISIBLE
        val drawerRow = (if (isMailRoom) "📧  " else "📰  ") +
            (if (drawerOpen) "Close the " else "Open the ") + (if (isMailRoom) "Mail drawer" else "Feeds drawer")
        showDirectory(withHubRows(listOf(
            // ✓ Mark all read is Miniflux's: over letters it found nothing to mark, so The Mail's
            // More (a seat since 2026-10-11) leaves it out rather than offer a door to nothing.
            "Feed" to listOfNotNull(
                ("✓  Mark all read" to { markAllRead() }).takeIf { kindFilter != KIND_MAIL },
                "🔄  Refresh" to { refresh() }
            ),
            "Mark as read" to listOf(
                "${tick("open")}  On open" to { prefs().edit().putString("feeds_mark_read", "open").apply() },
                "${tick("scroll")}  On scroll past" to { prefs().edit().putString("feeds_mark_read", "scroll").apply() },
                "${tick("off")}  Off (manual only)" to { prefs().edit().putString("feeds_mark_read", "off").apply() }
            ),
            "Reading" to listOf(
                ((if (prefs().getBoolean("feeds_tap_zones", true)) "☑" else "☐") + "  Tap sides to turn") to {
                    val on = prefs().getBoolean("feeds_tap_zones", true)
                    prefs().edit().putBoolean("feeds_tap_zones", !on).apply()
                    binding.articleTapZones.visibility = if (!on) View.VISIBLE else View.GONE
                },
                // The inline Now Playing transport in the drawer, off unless asked for — the ▦
                // hub's "▶️ Now Playing" row reaches the same controls from every surface, so the
                // card is a convenience, not the only door. Re-render immediately: a display
                // toggle whose effect waits for the next playback is a toggle that looks broken.
                ((if (nowPlayingCardOn()) "☑" else "☐") + "  Now Playing card in drawer") to {
                    prefs().edit().putBoolean("feeds_now_playing_card", !nowPlayingCardOn()).apply()
                    renderDirectory()
                },
                // How the words are set — face, size, spacing, width, colours (Aa Type, Michael
                // 2026-10-11). LAST, so the rows above keep their places; "Feed text size" below
                // stays the LIST's own size, untouched.
                "Aa  Type…" to { openAaType() }
            ),
            // Menu and dialog sizes moved to Settings → Text size…; this one stays because it
            // sizes the feed's own rows, which is this screen's business and nobody else's.
            "Feed text size" to listOf(
                "${ft("small")}  Small" to { setFeedTier("small") },
                "${ft("medium")}  Medium" to { setFeedTier("medium") },
                "${ft("large")}  Large" to { setFeedTier("large") }
            ),
            "Screen" to listOf(
                "🔄  Rotate screen" to { cycleScreenOrientation() },
                "🧭  Auto-rotate (gyro)" to { toggleAutoRotate() },
                // The quick-note toggle rides every wrench (the reader-only row stranded it).
                ((if ((activity as? com.toolsboox.ui.main.MainActivity)?.quickNoteVisible() != false)
                    "🖉  Hide the quick-note button" else "🖉  Show the quick-note button") to {
                    val main = activity as? com.toolsboox.ui.main.MainActivity
                    main?.setQuickNoteVisible(!(main.quickNoteVisible()))
                }),
                ((if (volumeTurnOn()) "☑" else "☐") + "  Volume keys scroll") to {
                    requireContext().getSharedPreferences("ledger_reader_nav", 0)
                        .edit().putBoolean("volume_turn", !volumeTurnOn()).apply()
                    Unit
                },
                // Every panel's More carries ☐ Tool labels (the one panel language, 2026-10-11).
                toolLabelsRow(feedsPanel).let { "${it.emoji}  ${it.label}" to it.action }
            ),
            "Settings" to listOf(
                "⚙  Feed settings…" to { showFeedSettings() }
            )
        ), drawerRow to { applyDirectoryDrawer(!drawerOpen) }))
    }

    // ── THE LEFT EDGE IN FEEDS AND THE MAIL (Michael 2026-10-12: "First swipe back email stuff
    // second swipe back main pane") — [com.toolsboox.ot.EdgeLadder]. An open article or letter is
    // closed by [articleBackCallback] first (it stands above the ladder); then the drawer opens;
    // with the drawer standing, the Hub opens beside it.

    override fun edgePane(): com.toolsboox.ot.EdgeLadder.Pane =
        if (binding.directoryScroll.visibility == View.VISIBLE) com.toolsboox.ot.EdgeLadder.Pane.SHOWING
        else com.toolsboox.ot.EdgeLadder.Pane.SHUT

    override fun openEdgePane() = applyDirectoryDrawer(true)

    override fun openHub() = showLedgerDirectory()

    /** Mark every entry currently in view as read on Miniflux, then refresh so they drop out. */
    private fun markAllRead() {
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty(); val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (url.isBlank() || token.isBlank()) { showMessage(R.string.feeds_need_creds); return }
        // Act on the list AS DISPLAYED — drilling into a feed/category/lens narrows the
        // adapter, not allEntries, and clearing "everything loaded" from inside a category
        // wiped articles the user never saw. Synthetic rows (negative ids: Later, Pickings,
        // local feeds) have no server entry — sending their ids would mark unrelated real
        // Miniflux entries read.
        val ids = adapter.current().map { it.id }.filter { it > 0 }
        if (ids.isEmpty()) { showMessage(R.string.feeds_nothing_to_mark); return }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { miniflux.setStatus(url, token, ids, "read") }
            if (ok is MinifluxClient.Result.Err) {
                android.widget.Toast.makeText(requireContext(), "⚠ Mark-read failed — ${ok.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            refresh()
            // Undoable — one Snackbar action flips them all back to unread.
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, "Cleared ${ids.size} — marked read", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction("Undo") {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { miniflux.setStatus(url, token, ids, "unread") }
                    refresh()
                }
            }.show()
        }
    }

    /**
     * A HOLD on a folder row in the left-hand directory — a lens, a category, or a single feed.
     *
     * "Clear" beside the VIEWS chips acts on the list AS DISPLAYED, which means emptying one
     * category meant first drilling into it. A folder is a thing in its own right, so it gets its
     * own gesture: hold it, and the option is right there for that folder and nothing else. The
     * menu itself is the deliberate second step (a hold on e-ink can be accidental), so choosing
     * the row does the thing — no second confirm, and an Undo that reaches both sides.
     */
    private fun showFolderHoldMenu(label: String, entries: List<FeedEntry>) {
        val unread = entries.filterNot { it.read || FeedReadState.isRead(it.id) }
        val items = arrayOf(
            if (unread.isEmpty()) "✓  Nothing unread here" else "✓  Mark all as read  ·  ${unread.size}"
        )
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(label)
            .setItems(items) { _, _ -> if (unread.isNotEmpty()) markFolderRead(label, unread) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Mark every unread entry of one folder read — locally first (so the rows grey out and the
     * drawer's counts drop at once, and so synthetic rows with no server side still clear), then
     * in ONE batched Miniflux call for the entries that really live there. Undoable.
     */
    private fun markFolderRead(label: String, unread: List<FeedEntry>) {
        val ids = unread.map { it.id }
        val idSet = ids.toSet()
        // Only real Miniflux entries have positive ids — Later / Pickings / local-feed rows are
        // synthetic and negative, and a small synthetic id would address someone ELSE's entry.
        val serverIds = ids.filter { it > 0 }
        ids.forEach { FeedReadState.mark(it) }
        allEntries = allEntries.map { if (it.id in idSet) it.copy(read = true) else it }
        adapter.submit(adapter.current().map { if (it.id in idSet) it.copy(read = true) else it })
        renderDirectory()

        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val onServer = serverIds.isNotEmpty() && url.isNotBlank() && token.isNotBlank()
        lifecycleScope.launch {
            if (onServer) {
                val res = withContext(Dispatchers.IO) { miniflux.setStatus(url, token, serverIds, "read") }
                if (res is MinifluxClient.Result.Err) {
                    android.widget.Toast.makeText(
                        requireContext(), "⚠ Mark-read didn't reach Miniflux — ${res.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, "Marked ${ids.size} read in “$label”",
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setAction("Undo") {
                ids.forEach { FeedReadState.unmark(it) }
                lifecycleScope.launch {
                    if (onServer) withContext(Dispatchers.IO) { miniflux.setStatus(url, token, serverIds, "unread") }
                    refresh()
                }
            }.show()
            // Reload so an Unread view drops them and the counts come back from the source.
            refresh()
        }
    }

    /** How opening/scrolling marks entries read: "open" (default) · "scroll" · "off". */
    private fun markReadMode(): String = prefs().getString("feeds_mark_read", "open") ?: "open"

    /** Whether the drawer seats the inline Now Playing card while something plays. Default OFF:
     *  the hub's "▶️ Now Playing" row already reaches the transport from every surface, and the
     *  card is a permanent block above the views for whoever didn't ask for it. */
    private fun nowPlayingCardOn(): Boolean = prefs().getBoolean("feeds_now_playing_card", false)

    /**
     * A HOLD on a list row — the row's own verb menu. Michael's walkthrough page: "Long-hold on
     * Email/Article list should offer Reply, Mark above as read, Star, Forward as options." The
     * hold used to go STRAIGHT to mark-above; that verb keeps its seat (second, his order), and
     * the row verbs that already existed elsewhere — the star glyph's toggle, the open letter's
     * ↩ on the rail — join it here so triage never requires opening the thing first.
     *
     * The menu is the deliberate second step (the showLaterRowMenu reasoning): on e-ink a hold
     * can be accidental, so the FIRST row must be harmless — Reply opens the two-door chooser a
     * stray press backs out of losing nothing, and on article rows the leader is mark-above,
     * which carries its own count-naming confirm. Reply and Forward are LETTER verbs; an article
     * row swaps them for the one honest translation it has — forwarding the piece's link through
     * the same compose door (see [forwardArticle]) — rather than wearing a ↩ that could only
     * apologize: an RSS entry has no reply address, and a verb that cannot act should not be
     * offered (the clearLaterAbove rule).
     */
    private fun showEntryHoldMenu(entry: FeedEntry) {
        // The in-progress shelf's rows have their own three doors (09-19).
        if (isShelfRow(entry)) { showShelfMenu(entry); return }
        // A Later List row keeps its OWN hold menu — its verbs are the list's (star / select /
        // clear-above / remove), and "mark above as read" is meaningless where nothing on disk
        // records read. Unchanged routing from when the hold went straight to markAboveAsRead.
        if (com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(entry.id)) { showLaterRowMenu(entry); return }
        val m = mailById[entry.id]
        val list = adapter.current()
        val idx = list.indexOfFirst { it.id == entry.id }
        // The same "above" set markAboveAsRead sweeps, counted the same way, so the row's face
        // and its confirm never name two different numbers.
        val above = if (idx > 0) list.take(idx).count { !FeedReadState.isRead(it.id) } else 0
        val items = mutableListOf<Pair<String, () -> Unit>>()
        // ⤴ A 🗑 Trash row's first verb is Move back (Michael 2026-10-11) — one hold, no opening.
        if (m != null && InboxStore.isCleared(requireContext(), m.id)) items.add("⤴  Move back to ${movedBackTo(m)}" to {
            InboxStore.restore(requireContext(), listOf(m.id))
            if (kindFilter == KIND_MAIL) submitMailRows()
            showMessage("⤴ Back in ${movedBackTo(m)}", binding.root)
        })
        if (m != null) items.add("↩  Reply" to {
            // The open letter's reply fork, verbatim (replyToOpenMail) — quick reply or the
            // draft-gram door — with the same refold on send: the answered mail became
            // keep-forever, and only a visible mail list needs repainting for it.
            com.toolsboox.plugin.mail.ui.MailVerbs.replyDoors(this, calendarDayService, documentsRoot(), m,
                fromAccount = composeFrom()) { afterMailReply() }
        })
        // ⏳ SAVE FOR LATER, from the row (Michael, 09-14, in Watch: "there isn't an easy way to
        // add either media type to the later list… I can pin it without making it a star"). Filed
        // to the row's own lane — watch, listen, read — through the one write path; the star
        // stays what it is.
        if (m == null && entry.url.startsWith("http", ignoreCase = true)) items.add("⏳  Save for later" to {
            val ctx = requireContext().applicationContext
            val lane = entry.kind.ifBlank { "read" }
            com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                ctx, java.time.LocalDate.now(), lane, entry.url, entry.title,
                excerpt = entry.content.take(300).ifBlank { null }, image = entry.imageUrl)
            // Later means having it (09-19): the row knows its enclosure, so the ask fileLink just
            // recorded gets it now and the download starts here, reason "you asked".
            directMedia(entry)?.let { audio ->
                com.toolsboox.plugin.calendar.ot.ListenStateStore.setWantOffline(ctx, entry.url, true, audioUrl = audio,
                    title = entry.title, show = entry.feedTitle,
                    artworkUrl = com.toolsboox.plugin.feeds.nw.ShowCovers.artFor(ctx, entry).orEmpty())
                com.toolsboox.plugin.feeds.nw.LaterMedia.undismiss(ctx, audio)
                com.toolsboox.plugin.feeds.nw.LaterMedia.download(ctx, audio, entry, com.toolsboox.plugin.feeds.nw.LaterMedia.REASON_ASKED)
                fetchTranscriptForOffline(entry)
            }
            val dl = if (directMedia(entry) != null) " · downloading" else if (lane == "watch") " · queued for download" else ""
            showMessage("⏳ Later · ${lane.replaceFirstChar { it.uppercase() }} · ${entry.title.take(50)}$dl", binding.root)
        })
        // 🗑 THE ROW CAN LET ITS FILE GO (09-19: "you can't delete them" — the Storage screen was
        // the only door). Removing withdraws the ask too, so no device fetches it again, and the
        // sweep leaves it alone until you ask.
        directMedia(entry)?.takeIf { m == null && com.toolsboox.plugin.feeds.nw.LaterMedia.isDownloaded(requireContext(), it) }?.let { audio ->
            val why = com.toolsboox.plugin.feeds.nw.LaterMedia.reasonLabel(requireContext(), audio)
            items.add("🗑  Remove download" + (why?.let { " · $it" } ?: "") to {
                val ctx = requireContext().applicationContext
                com.toolsboox.plugin.feeds.nw.LaterMedia.remove(ctx, audio, dismiss = true)
                val id = entry.url.ifBlank { audio }
                if (com.toolsboox.plugin.calendar.ot.ListenStateStore.get(ctx, id)?.wantOffline == true)
                    com.toolsboox.plugin.calendar.ot.ListenStateStore.setWantOffline(ctx, id, false)
                showMessage("Removed · ${entry.title.take(50)}", binding.root)
                if (currentArticle?.id == entry.id) renderArticle()
            })
        }
        items.add(
            (if (above > 0) "⇞  Mark $above above as read…" else "⇞  Mark above as read") to
                { markAboveAsRead(entry) }
        )
        items.add((if (entry.starred) "☆  Unstar" else "★  Star") to { toggleStar(entry) })
        when {
            m != null -> items.add("⤳  Forward…" to { forwardMail(m) })
            // A synthetic row with no http address (the 🔎 control row, ledger:// rows) has
            // nothing to put in a letter — the verb hides rather than apologizing.
            entry.url.startsWith("http", ignoreCase = true) ->
                items.add("⤳  Forward by email…" to { forwardArticle(entry) })
        }
        // ● Read / Unread from the row (Michael 2026-10-11) — a letter you've opened can go back
        // to waiting for you without opening it again. After his order (Reply · Mark above as read
        // · Star · Forward — review 2026-10-11), and only where it can act, as the letter's seat:
        // not your own sent rows (never unread), Trash rows (Move back is their verb) or Junk.
        if (m != null && !InboxStore.isSent(m.id) && !InboxStore.isCleared(requireContext(), m.id) &&
            !com.toolsboox.plugin.mail.JunkMail.isJunk(m.id)) {
            val read = entry.read || FeedReadState.isRead(entry.id) || InboxStore.isRead(requireContext(), m.id)
            items.add((if (read) "●  Mark unread" else "○  Mark read") to { setMailRead(entry, m, read = !read) })
        }
        showIconMenu(entry.title.take(80), items)
    }

    /**
     * Forward a letter: the compose screen, prefilled. [MailComposeFragment] already takes a
     * seeded subject and body (ARG_SUBJECT / ARG_BODY, built for the quick wins), so forwarding
     * is not a new machine — it is the compose door arriving with "Fwd: <subject>" and the
     * original under the classic forwarded-message header, To left blank for the rolodex
     * autocomplete. Hydrated off disk first, exactly as opening the letter does: the row often
     * carries only the envelope, and forwarding a snippet while the whole body sits in the keep
     * pile would silently send a third of the letter. (A message ALSO truncated on the server
     * forwards its bounded slice — the same slice reading it shows before "load the rest"; the
     * body lands in an editable field, so what will be sent is on screen before Send.)
     */
    private fun forwardMail(m: InboxMessage) {
        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) {
                runCatching { InboxStore.hydrate(requireContext().applicationContext, m) }.getOrDefault(m)
            }
            if (!isAdded) return@launch
            val subject = full.subject.trim().ifBlank { "(no subject)" }
            val fwdSubject = if (subject.lowercase().startsWith("fwd:")) subject else "Fwd: $subject"
            val from = if (full.fromName.isBlank()) full.fromEmail
            else "${full.fromName} <${full.fromEmail}>"
            val dateLine = java.time.Instant.ofEpochMilli(full.date)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.getDefault()))
            val body = buildString {
                // Leading blank lines: his words go ABOVE the forwarded block, so the cursor's
                // natural landing is the writing spot.
                append("\n\n---------- Forwarded message ----------\n")
                append("From: ").append(from).append('\n')
                append("Date: ").append(dateLine).append('\n')
                append("Subject: ").append(subject).append("\n\n")
                append(full.body.ifBlank { full.snippet })
            }
            // From: the letter's own account (the one From rule, Michael 2026-10-11), else the
            // Mail view's.
            val fromAccount = com.toolsboox.plugin.mail.MailThreads.replyAccountId(full)
                ?: composeFrom()?.takeIf { kindFilter == KIND_MAIL }
            runCatching {
                findNavController().navigate(
                    R.id.action_to_mail_compose,
                    androidx.core.os.bundleOf(
                        com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_SUBJECT to fwdSubject,
                        com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_BODY to body,
                        com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_FROM_ACCOUNT to fromAccount.orEmpty()
                    )
                )
            }.onFailure { showMessage("Couldn't open compose", binding.root) }
        }
    }

    /** Forward an article — the letter verb translated for a piece with no reply address: the
     *  same compose door, subject = the piece's title, the link (and the excerpt when the row
     *  has one) under two blank lines so the note-to-the-recipient writes above it. */
    private fun forwardArticle(entry: FeedEntry) {
        val body = "\n\n" + listOf(entry.url, entry.blurb).filter { it.isNotBlank() }.joinToString("\n\n")
        runCatching {
            findNavController().navigate(
                R.id.action_to_mail_compose,
                androidx.core.os.bundleOf(
                    com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_SUBJECT to entry.title.ifBlank { "(untitled)" },
                    com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_BODY to body
                )
            )
        }.onFailure { showMessage("Couldn't open compose", binding.root) }
    }

    /** A mailto: link in a letter (or an article) opens the composer addressed — subject and body
     *  too when the link carries them (Michael 2026-10-11). The pane can't load mailto: itself and
     *  showed an error page. The To line holds one address, so a multi-recipient link takes its
     *  first. Coming back reopens the piece through the trip stash, like every compose door. */
    private fun composeFromMailto(url: String) {
        val mt = runCatching { android.net.MailTo.parse(url) }.getOrNull()
        val to = mt?.to.orEmpty().split(',', ';').map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        runCatching {
            findNavController().navigate(
                R.id.action_to_mail_compose,
                androidx.core.os.bundleOf(
                    com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_TO_EMAIL to to,
                    com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_SUBJECT to mt?.subject.orEmpty(),
                    com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_BODY to mt?.body.orEmpty(),
                    // A link in a letter answers from the letter's door; else the Mail view's.
                    com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_FROM_ACCOUNT to (
                        openLetter()?.let { com.toolsboox.plugin.mail.MailThreads.replyAccountId(it) }
                            ?: composeFrom()?.takeIf { kindFilter == KIND_MAIL }).orEmpty()
                )
            )
        }.onFailure { showMessage("Couldn't open compose", binding.root) }
    }

    /** Mark every article ABOVE a row read (the common reader gesture; parity with the iPad's
     *  "Mark above as read" context item) — offered from the row's hold menu ([showEntryHoldMenu]).
     *  Confirmed first, because this is a bulk change reached through one hold. Marks the same
     *  "above" set the scroll-mode handler does — everything before this row in the current list. */
    private fun markAboveAsRead(entry: FeedEntry) {
        // A hold on a Later List row means something else entirely. "Mark above as read" is
        // meaningless here — nothing on disk records whether a filed link has been read, so the
        // gesture would grey a screenful of rows until the next load and no further. The list's own
        // two verbs take the gesture instead.
        if (com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(entry.id)) { showLaterRowMenu(entry); return }
        val list = adapter.current()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx <= 0) return
        val above = list.take(idx).filterNot { FeedReadState.isRead(it.id) }
        if (above.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Mark ${above.size} above as read?")
            .setPositiveButton("Mark read") { _, _ ->
                above.forEach { markEntryRead(it, notify = false) }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * A HOLD on a Later List row — the verbs the list is FOR: keep this one, or be done with it,
     * one at a time or by the screenful (Select… and Clear-above are "faster than one at a time",
     * as asked; both drain through [removeLaterRows], the same path as the single delete).
     *
     * Michael: "I would like to be able to star items from the later list tho and delete them."
     * Both shipped hidden, and the reasoning was sound as far as it went — a later-list entry is a
     * "title — url" LINE inside an intake day file, there is no server entry behind it, and a
     * gesture that writes nowhere is worse than a missing one. The answer was to build the places
     * (LaterStars, IntakePageStore.unfileLink) rather than to accept the absence.
     *
     * DELETE SITS LAST, and that is the iPad's "destructive before the star so a full swipe can't
     * reach it" translated into this fork's idiom. There are no swipes on a Boox row; the gestures
     * are a tap (open), a hold (this), and the star glyph. So the accident this menu has to defend
     * against is a different one: a hold on e-ink can be accidental — the panel is slow enough that
     * a tap you thought didn't register becomes a long press — and a menu that opens under a finger
     * already coming down gets its FIRST row chosen. So the first row is the harmless one, and
     * "I flicked it and it vanished" stays a thing this list cannot do to something you saved on
     * purpose. Same reason [showFolderHoldMenu] treats the menu itself as the deliberate second
     * step and asks for no further confirmation: choosing a row does the thing.
     */
    private fun showLaterRowMenu(entry: FeedEntry) {
        // Clearing-above only exists where there IS an above; on the top row the menu doesn't
        // offer a verb that can only apologize.
        val aboveCount = adapter.current().indexOfFirst { it.id == entry.id }
        val items = mutableListOf<Pair<String, () -> Unit>>(
            (if (entry.starred) "☆  Unstar" else "★  Star") to { toggleStar(entry) },
            // Select… puts the list in checkbox mode with THIS row already ticked — the row you
            // held is the row you meant. Harmless on its own: nothing is removed until the bar's
            // Delete, so it can sit second without weakening the first-row defence above.
            "☑  Select rows…" to { enterLaterSelection(entry.id) },
        )
        if (aboveCount > 0) items.add(
            // The feeds list's "mark above as read" gesture, translated to the list where "done
            // with it" means REMOVE (a filed link has no read flag) — and confirmed with its
            // count, same as over there, because it's one hold acting on a screenful.
            "⇞  Clear all above…" to { clearLaterAbove(entry) })
        items.add(
            "🗑  Remove from Later List" to { removeLaterRows(listOf(entry)) })
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle(entry.title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Clear above" on a Later List row: remove every row ABOVE this one in the CURRENT view —
     *  the lane/search narrowing included, so what clears is exactly what is on screen above your
     *  finger. Confirmed with the count first: it is one hold acting on a screenful, the same
     *  reasoning [markAboveAsRead] gives, and unlike a mark-read there is no Undo on the other
     *  side of this one. */
    private fun clearLaterAbove(entry: FeedEntry) {
        val list = adapter.current()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx <= 0) return
        val above = list.take(idx).filter { com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(it.id) }
        if (above.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Clear ${above.size} above this?")
            .setPositiveButton("Clear") { _, _ -> removeLaterRows(above) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Remove Later rows — one or many — through the ONE removal path the single delete has always
     * used: [com.toolsboox.plugin.feeds.nw.LaterFeed.remove] → `IntakePageStore.unfileLink`, which
     * writes the LaterRemovals tombstone per link so the cross-device merge can't resurrect a
     * bulk-cleared screen any more than a single delete. A private bulk writer here would be a
     * second opinion about what "removed" means.
     *
     * Rows whose line had already gone (reloaded list, another device got there first) stay
     * silent, as the single delete always has — the count reported is what actually came off.
     */
    private fun removeLaterRows(rows: List<FeedEntry>) {
        if (rows.isEmpty()) return
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            val removedIds = withContext(Dispatchers.IO) {
                rows.mapNotNull { r ->
                    if (com.toolsboox.plugin.feeds.nw.LaterFeed.remove(appCtx, r.id)) r.id else null
                }.toSet()
            }
            if (!isAdded || removedIds.isEmpty()) return@launch
            // Drop them from the list in place instead of reloading: loadLaterList re-walks
            // 120 day files and would flash the whole panel on e-ink to communicate the
            // disappearance of rows it already knows are gone.
            allEntries = allEntries.filterNot { it.id in removedIds }
            val shown = adapter.current().filterNot { it.id in removedIds }
            adapter.submit(shown)
            if (shown.isEmpty()) showEmpty(laterEmptyText())
            showMessage(
                if (removedIds.size == 1) "Removed from Later List"
                else "Removed ${removedIds.size} from Later List",
                binding.root
            )
        }
    }

    /** Enter Later List selection mode: checkboxes on every row, the held row pre-ticked, and the
     *  Delete/Cancel bar pinned above the list. Delete runs [removeLaterRows] on the ticked set —
     *  no further confirm: ticking boxes one by one IS the deliberate act, the same reasoning
     *  [showLaterRowMenu] gives for its own rows. */
    private fun enterLaterSelection(seedId: Long) {
        adapter.onSelectionChanged = { n ->
            binding.laterSelectCount.text = if (n == 1) "1 selected" else "$n selected"
            binding.laterSelectDelete.isEnabled = n > 0
        }
        adapter.beginSelection(seedId)
        binding.laterSelectBar.visibility = View.VISIBLE
        binding.laterSelectDivider.visibility = View.VISIBLE
        binding.laterSelectDelete.setOnClickListener {
            val chosen = adapter.current().filter { it.id in adapter.selectedIds }
            exitLaterSelection()
            removeLaterRows(chosen)
        }
        binding.laterSelectCancel.setOnClickListener { exitLaterSelection() }
    }

    private fun exitLaterSelection() {
        adapter.endSelection()
        binding.laterSelectBar.visibility = View.GONE
        binding.laterSelectDivider.visibility = View.GONE
    }

    /** Mark one entry read: locally (greys the row) + on Miniflux (durable). Idempotent. */
    private fun markEntryRead(entry: FeedEntry, notify: Boolean = true) {
        if (FeedReadState.isRead(entry.id)) return
        FeedReadState.mark(entry.id)
        if (notify) adapter.notifyDataSetChanged()
        // The open letter's ●/○ seat says what a tap makes it — it turns to "Unread" as it's read.
        if (currentArticle?.id == entry.id && mailById.containsKey(entry.id)) redressRail()
        // A mail row's durable read state lives in the mail store, not on Miniflux — write it
        // there so the Unread chip agrees with what just happened.
        // (Idempotent; covers the open tap, mark-above, and mark-read-on-scroll alike.)
        mailById[entry.id]?.let { m ->
            InboxStore.markRead(requireContext(), m.id)
            // …and the Mail pane's counts, while it shows (mark-read-on-scroll beside it).
            if (binding.directoryScroll.visibility == View.VISIBLE) recountMail()
        }
        // Only real Miniflux entries have positive ids — synthetic rows (Later, Pickings,
        // local feeds) are all negative and must never be pushed to the server, where a
        // small synthetic id would address someone ELSE's entry. The mark itself rides the
        // coalescing queue (see [queueServerRead]) — never a per-entry HTTP launch.
        if (entry.id > 0) queueServerRead(entry.id)
        // A Bluesky row has its own "read" on the far side — the site's `seen` flag, which is what
        // stops the same morning's posts coming back on every refresh and is shared with the iPad.
        // Collected rather than sent: see [pendingBskySeen].
        // `!entry.read` because the site already knows about a post it handed us as seen — the
        // session read-tracker above is per-launch, so without this every re-opened post would put
        // its URI back in the batch and buy a request that could only be a no-op.
        bskyUriById[entry.id]?.takeIf { !entry.read }?.let { uri ->
            val ready = synchronized(pendingBskySeen) {
                pendingBskySeen.add(uri); pendingBskySeen.size >= BSKY_SEEN_BATCH
            }
            if (ready) flushBlueskySeen()
        }
    }

    /**
     * Send the accumulated "seen" batch.
     *
     * On [lifecycleScope] and the APPLICATION context: the scope survives onPause (it is cancelled
     * at DESTROY, not at PAUSE), which is what lets the leaving-the-screen flush actually complete,
     * and the app context is what keeps the request from holding the fragment's own.
     *
     * The URIs leave the pending set only after the site has accepted them. A flush that fails
     * therefore leaves the work to be redone on the next one rather than dropping it — dropping a
     * batch means those posts come back unread, and he reads the same morning twice.
     */
    private fun flushBlueskySeen() {
        val batch = synchronized(pendingBskySeen) { pendingBskySeen.toList() }
        if (batch.isEmpty()) return
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            if (com.toolsboox.plugin.calendar.nw.BlueskyFeed.markSeen(appCtx, batch)) {
                synchronized(pendingBskySeen) { pendingBskySeen.removeAll(batch.toSet()) }
            }
        }
    }

    // (The pill-orientation and list-gutter machinery retired with the pills: the rail is in
    // the layout flow, so the list is clear of it by construction — no reserved margins, no
    // measured clearance, nothing to keep in step.)

    private fun showFeedSettings() {
        val ctx = requireContext()
        val p = prefs()
        fun label(t: String) = android.widget.TextView(ctx).apply {
            text = t; textSize = 14f; setTextColor(0xFF444444.toInt()); setPadding(0, dpPx(10), 0, dpPx(2))
        }
        val urlIn = android.widget.EditText(ctx).apply {
            setText(p.getString(KEY_URL, "")); hint = getString(R.string.feeds_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val tokIn = android.widget.EditText(ctx).apply {
            setText(p.getString(KEY_TOKEN, "")); hint = getString(R.string.feeds_token_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // The Settings screen's 52dp toggle shape, not the platform CheckBox — the stylus target
        // and the type match the page one tap away (EinkControls).
        val readerDefault = com.toolsboox.ot.EinkControls.toggle(ctx, "Reader view by default", showParsed) {}
        val pictures = com.toolsboox.ot.EinkControls.toggle(ctx, "Pictures in articles", p.getBoolean(KEY_PICTURES, true)) {}
        // On-star reactions (both off/empty by default) — see StarHooks.
        val hookUrlIn = android.widget.EditText(ctx).apply {
            setText(p.getString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_URL, ""))
            hint = "https://… (POST each star here)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val hookSecretIn = android.widget.EditText(ctx).apply {
            setText(p.getString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_SECRET, ""))
            hint = "Shared secret (optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val autoSynth = com.toolsboox.ot.EinkControls.toggle(
            ctx, "Ask 3 questions on every star (uses your AI key)",
            p.getBoolean(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_AUTOSYNTH, false)
        ) {}
        // ⏱ The fetch cycle (Michael, 08-23): mail + RSS arrive on a clock and stay readable
        // offline — no push exists on either lane (Miniflux polls, mail is IMAP), so the
        // cadence is his to set. Big radio rows, one per cadence, persisting on pick
        // (FetchCadence — the same block sits in the mail accounts dialog); Off keeps
        // fetch-on-open only.
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(4), dpPx(20), 0)
            addView(label(getString(R.string.feeds_url_hint))); addView(urlIn)
            addView(label(getString(R.string.feeds_token_hint))); addView(tokIn)
            addView(readerDefault)
            addView(pictures)
            addView(FetchCadence.view(ctx))
            addView(label("When you star an item"))
            addView(label("Send to a webhook")); addView(hookUrlIn); addView(hookSecretIn)
            addView(autoSynth)
        }
        // Guarded: typed hosts and secrets are work — a stray touch outside must not throw
        // them away.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Feed settings")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                p.edit().putString(KEY_URL, urlIn.text.toString().trim())
                    .putString(KEY_TOKEN, tokIn.text.toString().trim())
                    .putString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_URL, hookUrlIn.text.toString().trim())
                    .putString(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_WEBHOOK_SECRET, hookSecretIn.text.toString().trim())
                    .putBoolean(com.toolsboox.plugin.feeds.nw.StarHooks.KEY_AUTOSYNTH, autoSynth.isChecked)
                    .putBoolean(KEY_PICTURES, pictures.isChecked)
                    .apply()
                // (The fetch cadence persisted and re-armed itself on pick — FetchCadence.)
                showParsed = readerDefault.isChecked
                if (currentArticle != null) renderArticle()
                showMessage(R.string.feeds_saved); refresh()
            }
            .setNeutralButton("Refresh") { _, _ -> refresh() }
            .setNegativeButton("Close", null)
            .create())
    }

    /**
     * Populate the persistent left directory pane (two-pane feed). The drawer reads top-down as
     * Michael specced it: VIEWS (All/Unread/Read/Starred/Later — a state toggle that COMPOSES
     * with everything below — plus Clear, which acts on the visible list) → FEEDS (The Read/
     * Watch/Listen as two-level accordions: medium → categories → feed names, plus Asks &
     * Answers) → SEARCH (search + smart feeds) → MORE. Selecting a row filters the right list.
     */
    private fun renderDirectory() {
        if (!::binding.isInitialized) return
        val container = binding.feedsDirectory
        container.removeAllViews()
        // SLIM per-feed pane: just the INDIVIDUAL FEEDS of the current lens, small type, unread
        // counts. The full tree (lenses, categories, Later, local, OPML…) lives in the accordion
        // drawer (☰) — one directory style; this pane is a quick filter, toggled by the RSS button.
        val ctx = requireContext()
        // ONE ROW STYLE, BOTH ROOMS (Michael 2026-10-12, on the iOS Feeds pane: "The aesthetic/
        // design weight/language needs a bit of tweaking for readability/usability"): every row of
        // this drawer and The Mail's goes through [RoomPane] — one size and weight (bold + underline
        // only for what's on screen), counts first in one column, monochrome ic_pf_* faces in place
        // of the colour emoji, comfortable height, long names on two lines, black on white for
        // e-ink. Selecting a row while reading returns to the list (the pane's beforeTap).
        val pane = RoomPane(container) {
            if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
        }
        // One directory, one style, no ladder: SEARCH → the doors → FEEDS → SOURCES → ADD, all in
        // this single drawer. Replaces the old slim-pane ⇄ accordion split you had to climb between.
        fun section(title: String) = pane.section(title)


        // Now Playing at the very top while a podcast/read-aloud is going — the TRANSPORT ITSELF,
        // inline, not a row that opens the modal: Michael wants the controls "a layer sooner (the
        // sidebar), not a popup from the sidebar". The card drives whichever backend is live
        // (podcast audio or TTS), keeps its own 1s clock while attached, and asks for a re-render
        // when playback ends so it folds away. (Surfaced when playback starts, see playEntryAudio.)
        //
        // Behind a wrench toggle now, OFF by default — Michael: the strip "becomes redundant/
        // should be hideable since we have the now-playing in the sidebar available pretty much
        // everywhere". The hub's "▶️ Now Playing" row (ledgerDirectoryFolders, reachable from this
        // page's own ▦ button) carries the full transport modal whenever anything is playing, so
        // hiding the card loses no control — only the permanent block above the views. The fold
        // survives for whoever turns the card back on.
        if (com.toolsboox.ui.plugin.LedgerPlayer.isActive && nowPlayingCardOn()) {
            container.addView(NowPlayingCard.build(ctx) { if (isAdded) renderDirectory() })
        }

        // 📧 THE MAIL'S DRAWER — its own room's (Michael 2026-10-11: "You can move mail ... out
        // of incoming" · "Mail can have its own accordion then"; 10-11 later: "Inbox then starred
        // then @gmail", each account's own Sent · Junk · Trash under it, 👤 Contacts in from the
        // Desk). Search first (with the ⇅ sort dial beside it), then the mailboxes in his order,
        // the permanent accounts door (rule of 09-07), every account's Sent and Trash under ALL
        // ACCOUNTS, a one-tap hop back to Feeds (it was one tap while Mail was a lens here) and
        // The Log. No RSS control lives here, so nothing in this drawer can swap the letters for
        // articles.
        if (isMailRoom) {
            section("SEARCH")
            // MAIL SEARCH STAYS IN MAIL (Michael 2026-10-11): typing filters the letters on hand
            // with the 🔎 server row beneath; Enter asks the mail servers (critique 4: the shared
            // field's Enter ran an RSS smart search, swapping the letters for articles).
            val serverSearch = mailServerSearchHere()
            val mailField = android.widget.EditText(ctx).apply {
                hint = if (serverSearch) "Search letters · Enter asks the servers" else "Search letters"
                textSize = RoomPane.TEXT_SP; setSingleLine()
                // The hint at the e-ink floor (#444), not the theme's light grey.
                setTextColor(RoomPane.INK); setHintTextColor(RoomPane.HINT)
                setPadding(dpPx(10), dpPx(7), dpPx(10), dpPx(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpPx(18).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dpPx(1), 0xFF000000.toInt())
                }
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            }
            mailField.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim()?.lowercase().orEmpty()
                    if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
                    val base = applyKind(allEntries)
                    val matches = if (q.isEmpty()) base else base.filter {
                        it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q) || it.feedTitle.lowercase().contains(q)
                    }
                    mailSearchQuery = q
                    adapter.submit(if (q.isEmpty() || !serverSearch) matches else matches + mailServerSearchRow(q))
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            mailField.setOnEditorActionListener { v, actionId, _ ->
                if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                val q = v.text.toString().trim()
                if (q.isNotBlank()) { mailSearchQuery = q.lowercase(); if (serverSearch) runMailServerSearch() }
                true
            }
            // THE SORT DIAL, beside the field it orders (review 2026-10-12: as a row among the
            // mailboxes it read like a third pile) — where the Feeds pane's search scope sits, one
            // tap as before: Date → Sender → Subject, persisted (see MailSort).
            val sort = mailSort()
            val sortChip = android.widget.TextView(ctx).apply {
                text = sort.label; textSize = 13f; setTextColor(RoomPane.INK)
                gravity = android.view.Gravity.CENTER_VERTICAL
                val icon = androidx.core.content.ContextCompat.getDrawable(ctx, RoomPane.SORT.iconRes)?.mutate()?.apply {
                    setTint(RoomPane.INK); setBounds(0, 0, dpPx(16), dpPx(16))
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dpPx(4)
                setPadding(dpPx(8), dpPx(4), dpPx(6), dpPx(4)); isClickable = true
                contentDescription = "Sort letters by ${sort.label} — tap for ${sort.next().label}"
                setOnClickListener { setMailSort(sort.next()); renderDirectory(); submitMailRows() }
            }
            container.addView(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(mailField, android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sortChip, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dpPx(RoomPane.ROW_MIN_DP)))
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpPx(4), dpPx(2), dpPx(4), dpPx(2)) })

            section("MAILBOXES")
            val accounts = com.toolsboox.plugin.mail.MailAccountStore.all(ctx)
            val starred = mode == "stars"
            // The unread letters — 📥 Inbox's, each account's and its Inbox's (iOS MailPane's
            // counts): the last count on hand, and a fresh one off the main thread that redraws
            // the pane only if it moved.
            val unread = mailCounts
            recountMail()
            // A mailbox is a place to stand: the view you keep (👁) comes along, except over
            // ⭐ Starred, which is a view of its own (every account's kept pile).
            fun pickMailbox(box: String?) {
                setMailMailbox(box)
                if (mode !in MailPlace.VIEWS) mode = placeView
                renderDirectory(); refresh()
            }
            // THE MAIL PANE'S ROWS (Michael 2026-10-12: "emails getting an RSS like pane for
            // accounts" — the iOS Mail pane (MailTree) copies this drawer's set, row for row):
            // Inbox (every account) · Starred · each @account ▸ (its Inbox · Sent · Junk · Trash)
            // · Contacts · Mail accounts… · ALL ACCOUNTS: Sent / Trash · then FEEDS: the cross row
            // Feeds ↗ and its lenses, and The Log. In the Hub fold's order (Michael 2026-10-11:
            // "Inbox then starred then @gmail" · "individual sents, junks, trash … a drop down
            // under each account") and its words ([MailPlace.Box.words] — one list for the Hub and
            // this pane); only the faces differ — monochrome here, where the Hub keeps its glyphs.
            val here = MailPlace.where(mailMailbox)
            for (box in MailPlace.Box.TOP) when (box) {
                MailPlace.Box.STARRED -> pane.row(box.words(accounts.size), RoomPane.boxFace(box), selected = starred) {
                    setMailMailbox(null); mode = "stars"; renderDirectory(); refresh()
                }
                else -> pane.row(box.words(accounts.size), RoomPane.boxFace(box), count = unread.total,
                    selected = !starred && mailMailbox == null) { pickMailbox(null) }
            }
            // Every account, even the only one — its drop-down is where its Sent, Junk and Trash
            // live. A FOLDER, the Feeds pane's gesture (review 2026-10-12, iOS MailTree.tap): shut,
            // a tap opens it AND its Inbox loads; open, a tap folds it and the list stays where it
            // is (a category's rule). The fold is remembered beside the Hub's (one state, both).
            val mainPrefs = ctx.getSharedPreferences("MAIN", 0)
            // The Hub's labels, unique per account ([MailPlace.AccountLabel]) — the fold's state
            // is filed under the label, so two same-named accounts must not share one.
            val names = MailPlace.AccountLabel.all(accounts.map { Triple(it.id, it.display, it.email) })
            for (a in accounts) {
                val label = names.getValue(a.id)
                val key = com.toolsboox.ot.HubSubFold.prefKey(label)
                val open = mainPrefs.getBoolean(key, false)
                val mine = unread.byAccount[a.id] ?: 0
                // ONE ROW SAYS WHERE YOU ARE (review 2026-10-12): shut, the account row (its Inbox
                // or one of its piles); open, the Inbox or pile row beneath it does instead.
                val inThis = !starred && here.account == a.id
                pane.row(MailPlace.AccountLabel.plain(label), RoomPane.ACCOUNT, count = mine,
                    selected = inThis && !open, folder = open,
                ) {
                    mainPrefs.edit().putBoolean(key, !open).apply()
                    if (open) renderDirectory() else pickMailbox(a.id)
                }
                if (!open) continue
                for (box in MailPlace.Box.PER_ACCOUNT) {
                    val token = MailPlace.of(box, a.id)
                    val marked = !starred && here.account == a.id && here.box == box &&
                        (box != MailPlace.Box.INBOX || mailMailbox == a.id)
                    pane.row(box.words(), RoomPane.boxFace(box), count = if (box == MailPlace.Box.INBOX) mine else 0,
                        level = 1, run = "box:${a.id}", selected = marked) { pickMailbox(token) }
                }
            }
            // CONTACTS, in from the Desk (Michael 2026-10-11: "Can you put contacts in mail
            // (currently in desk)?") — the Rolodex, the people the letters go to.
            pane.row("Contacts", RoomPane.CONTACTS) { findNavController().navigate(R.id.action_to_rolodex) }
            // ALWAYS a door to the accounts (Michael, 09-07: "I can't figure out how to add an
            // email address anymore") — the gear as on iOS: every account row wears @, and the
            // settings door must not read as one more account.
            if (accounts.isEmpty()) pane.row("Add a mail account…", RoomPane.ADD) { showMailAccounts() }
            else pane.row("Mail accounts…", RoomPane.SETTINGS) { showMailAccounts() }
            // EVERY ACCOUNT'S SENT AND TRASH, under their own heading (review 2026-10-11 — no
            // feature lost; 2026-10-12 — ALL ACCOUNTS, iOS MailTree's three headings): the fold
            // lists each account's own since Michael's "individual sents, junks, trash", but a
            // letter sent through a site's FluentCRM ("sent:crm:<host>"), a reply whose account
            // wasn't known, a letter from an account since removed, and the sample letters belong
            // to no account — these two piles are their only door (and the CRM opened/clicked marks
            // show only on Sent). Listed even with no account at all, so cleared samples can still
            // be put back. The heading says whose they are, so with one account they read plain
            // ("Sent") and never pass for that account's own.
            section("ALL ACCOUNTS")
            for (box in MailPlace.Box.EVERY_ACCOUNT - MailPlace.Box.INBOX) {
                val token = MailPlace.of(box, null)
                pane.row(box.words(accounts.size), RoomPane.boxFace(box),
                    selected = !starred && mailMailbox == token) { pickMailbox(token) }
            }
            // FEEDS ↗, the cross row — one tap from here as The Mail ↗ is one tap from there; lands
            // where you were, and replaces this room (Back goes where it went before, as the lens
            // swap did). Its own heading, as The Mail ↗ leads the Feeds pane's FEEDS.
            section("FEEDS")
            pane.row("Feeds ↗", RoomPane.FEEDS) { openFeedView(findNavController(), "both", null, popMail = true) }
            // …and its lenses, one tap each, as they were while The Mail was a lens of that drawer
            // (2026-10-11 review; canon: no path gets worse — Mail → The Read had become two taps).
            // Doors, not RSS controls: each replaces this room with Feeds on that lens. The shared
            // names and order ([IncomingDoors]); this pane shows fewer, never others.
            for (door in IncomingDoors.inOrder(listOf(IncomingDoors.Door.BLUESKY, IncomingDoors.Door.READ,
                    IncomingDoors.Door.WATCH, IncomingDoors.Door.LISTEN, IncomingDoors.Door.LATER))) {
                val (lensMode, lensKind) = when (door) {
                    IncomingDoors.Door.BLUESKY -> "bsky" to null
                    IncomingDoors.Door.READ -> "feed" to "read"
                    IncomingDoors.Door.WATCH -> "feed" to "watch"
                    IncomingDoors.Door.LISTEN -> "feed" to "listen"
                    else -> "later" to null
                }
                pane.row(door.title, RoomPane.doorFace(door)) { openFeedView(findNavController(), lensMode, lensKind, popMail = true) }
            }
            pane.row("The Log", RoomPane.LOG) {
                com.toolsboox.plugin.calendar.ui.ReadingLogSelection.origin = null
                findNavController().navigate(R.id.action_to_reading_log)
            }
            pane.finish()
            return
        }

        // (The VIEWS chips — All · Unread · Read · Starred · Later — retired 09-12; their two
        // builders went with the room panes' one row style, 2026-10-12.)
        // Search at the very TOP — it was buried. A "SEARCH" section: a LIVE-filtering field (types
        // filter as you go; the keyboard Search action alone didn't fire reliably on e-ink) + three
        // tiny scope radios — All · This feed · This category — starting on All.
        section("SEARCH")
        val searchField = android.widget.EditText(ctx).apply {
            hint = "Search"; textSize = RoomPane.TEXT_SP; setSingleLine()
            // The hint at the e-ink floor (#444), not the theme's light grey.
            setTextColor(RoomPane.INK); setHintTextColor(RoomPane.HINT)
            setPadding(dpPx(10), dpPx(7), dpPx(10), dpPx(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpPx(18).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dpPx(1), 0xFF000000.toInt())
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        fun searchBase(): List<FeedEntry> = when (searchScope) {
            "feed" -> slimFeedFilter?.let { f -> allEntries.filter { it.feedTitle == f } } ?: applyKind(allEntries)
            "category" -> kindFilter?.let { k -> allEntries.filter { it.kind == k } } ?: applyKind(allEntries)
            else -> applyKind(allEntries)
        }
        fun runSearch() {
            val q = searchField.text.toString().trim().lowercase()
            if (binding.articlePane.visibility == View.VISIBLE) closeArticlePane()
            val matches = if (q.isEmpty()) searchBase() else searchBase().filter {
                it.title.lowercase().contains(q) || it.blurb.lowercase().contains(q) || it.feedTitle.lowercase().contains(q)
            }
            // The Mail keeps the deep layer its retired standalone screen had: this instant filter
            // only sees the loaded pool (the fetch window plus the keep piles), so under the local
            // matches — even zero of them — rides the door that asks the SERVERS. A row, not a
            // keystroke: IMAP round-trips are a tap you choose, never a per-letter-typed cost.
            if (kindFilter == KIND_MAIL) {
                mailSearchQuery = q
                adapter.submit(if (q.isEmpty()) matches else matches + mailServerSearchRow(q))
            } else adapter.submit(matches)
        }
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { runSearch() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        container.addView(android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            searchField.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            searchField.setSingleLine()
            searchField.hint = "Search \u00b7 Enter asks the whole server"
            searchField.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    val q = searchField.text.toString().trim()
                    if (q.isNotBlank()) {
                        // An unsaved smart feed: the server-wide search without the keeping —
                        // the ＃ chip is the keeping.
                        switchToSmart(com.toolsboox.plugin.feeds.nw.SmartFeed("", q, q.lowercase()))
                    }
                    true
                } else false
            }
            addView(searchField, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // The SCOPE, one word wide (09-03: the stacked radios "take up too much space") —
            // tap cycles All → Feed → Category; the search re-runs on each turn.
            addView(android.widget.TextView(ctx).apply {
                fun label() = when (searchScope) {
                    "feed" -> "Feed"; "category" -> "Cat."; else -> "All" }
                text = label(); textSize = 13f; setTextColor(0xFF000000.toInt())
                // No pressed shade (an e-ink flash per tap), as the rows below.
                setPadding(dpPx(6), dpPx(4), dpPx(6), dpPx(4)); isClickable = true
                contentDescription = "Search scope"
                setOnClickListener {
                    searchScope = when (searchScope) {
                        "all" -> "feed"; "feed" -> "category"; else -> "all" }
                    text = label(); runSearch()
                }
            })
            // ＃ keep — the query becomes a smart feed.
            addView(android.widget.TextView(ctx).apply {
                text = "＃"; textSize = 17f; setTextColor(0xFF000000.toInt())
                setPadding(dpPx(8), dpPx(4), dpPx(8), dpPx(4)); isClickable = true
                contentDescription = "Keep this search as a smart feed"
                setOnClickListener {
                    val q = searchField.text.toString().trim()
                    if (q.isNotBlank()) {
                        com.toolsboox.plugin.feeds.nw.SmartFeedStore.add(
                            ctx, com.toolsboox.plugin.feeds.nw.SmartFeed(
                                java.util.UUID.randomUUID().toString(), q, q.lowercase()))
                        renderDirectory()
                        showMessage("#  \u201c$q\u201d kept", binding.root)
                    }
                }
            })
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dpPx(4), dpPx(2), dpPx(4), dpPx(2)) })
        // The saved searches, right under the field they came from.
        com.toolsboox.plugin.feeds.nw.SmartFeedStore.all(ctx).forEach { sf ->
            pane.row(sf.name, RoomPane.doorFace(IncomingDoors.Door.SMART),
                selected = mode == "smart" && smartFeed?.name == sf.name) { switchToSmart(sf) }
        }
        // VIEWS and ACTIONS are gone (09-12 — parity with the iOS Incoming fold: "it still has
        // starred and read and unread right there in the panel"). All/Unread/Read were a second
        // axis nobody needed; ⭐ Starred lives in the hub ("All Stars"); Clear is each lens's
        // hold menu (Mark all as read) and, for mail, the rail's Sweep. What stays is the one
        // ACTION he kept reaching for, where his thumb already goes: the intake — one press.
        // (➕ Save for later now heads the shared door loop below — still the first door here.)

        // ── FEEDS ── the media folders. One tap on The Read does BOTH things — loads that
        // medium's All (within the active view) in the right panel AND drops its categories out
        // right beneath (the trailing chevron; its categories in the lens's title column, their
        // sites one step in — the room pane's one row style, no outline). A second tap folds it
        // back AND clears the medium — that's the exit back to the view's plain All. Opening one
        // lens folds the others (single-open).
        val a11y = requireContext().getSharedPreferences("ledger_a11y", 0)
        val lensOpen = a11y.getString("feeds_lens_open", "") ?: ""
        // Views the medium filter can ride on; anything else (smart/local/…) snaps back to All.
        // "later" is NOT one of them any more. The Later List has its own axis — the four lanes
        // under 🔖, which are where a link was FILED rather than what a row guesses it is — and
        // letting the media lens compose on top meant tapping 📖 The Read while standing in the
        // Later List silently intersected the two and could empty the list outright. Tapping a lens
        // from here now means what it says: go and read that lens.
        val composable = mode in setOf("feed", "read", "both", "edition", "stars")
        fun lensRow(door: IncomingDoors.Door, lens: String) {
            val open = lensOpen == lens
            // The dropdown: this medium's categories, unread-counted, drawn from ALL loaded
            // entries (pool may already be narrowed to this lens; that's fine — same rows). Feeds
            // with no Miniflux folder still need a home now that the flat SOURCES list is gone —
            // they gather under an Uncategorised pseudo-category at the bottom of each medium,
            // behaving exactly like a real one. One pass, and only when asked for — an open lens,
            // or a pick to place (every lens row draws on every pane redraw, a tap at a time).
            val inLens = allEntries.filter { it.kind == lens }
            val groups: List<Pair<String, List<FeedEntry>>> by lazy {
                val byCat = LinkedHashMap<String, MutableList<FeedEntry>>()
                val strays = ArrayList<FeedEntry>()
                for (e in inLens) {
                    val c = e.categoryLabel
                    if (!c.isNullOrBlank()) byCat.getOrPut(c) { ArrayList() }.add(e)
                    else if (e.feedTitle.isNotBlank()) strays.add(e)
                }
                val out: MutableList<Pair<String, List<FeedEntry>>> =
                    byCat.entries.sortedBy { it.key.lowercase() }.mapTo(ArrayList()) { it.key to it.value }
                if (strays.isNotEmpty()) out.add(UNCATEGORISED to strays)
                out
            }
            // ONE MARKED ROW (review 2026-10-12; iOS FeedTree's one isCurrent): what narrows the
            // list — the lens, a category of it, or a site — and while that row's folder is shut,
            // the nearest row that holds it instead, so the pane never marks two rows or none.
            // The holder is the picked category; a feed picked with none (go-to-feed from an
            // article) is held by the first category it is filed under.
            val holder: String? = if (kindFilter != lens || pickShowing() == null) null
                else slimCatFilter?.takeIf { c -> groups.any { it.first == c } }
                    ?: slimFeedFilter?.takeIf { slimCatFilter == null }?.let { f ->
                        groups.firstOrNull { (_, inCat) -> inCat.any { it.feedTitle == f } }?.first
                    }
            // The lens's own count (iOS lens.unread) — the widest in its pane, so the count column
            // is the same width with every folder shut or open.
            pane.row(door.title, RoomPane.doorFace(door), count = inLens.count { !it.read },
                selected = kindFilter == lens && (holder == null || !open),
                folder = open,
                onLongPress = { showFolderHoldMenu(door.title, inLens) }
            ) {
                // Redraw the drawer NOW (refresh()'s re-render is async and skips some error
                // paths), then retune the right panel.
                if (open) {
                    a11y.edit().putString("feeds_lens_open", "").apply()
                    kindFilter = null
                    renderDirectory(); refresh()
                } else {
                    a11y.edit().putString("feeds_lens_open", lens).apply()
                    if (composable) { mode = "both"; kindFilter = lens; renderDirectory(); refresh() }
                    else { renderDirectory(); switchTo("both", lens) }
                }
            }
            if (!open) return
            // The categories stand in the lens's own title column (the empty face slot sets them
            // under it), their sites one step in — the one visual indent (design v2 §3, iOS
            // isSubRow). The categories share a count column of their own, as each category's
            // sites do, never narrower than the pane's, so opening a lens or a category never
            // slides the rows around it (review 2026-10-12).
            // The categories fold too, same dual gesture one level down ("those feed lists
            // should be collapsable, but still pull up the all when you select it"): tap a
            // category → its All loads on the right AND its feed names drop out beneath,
            // indented deeper. Tap again → just folds. One open category per medium, remembered
            // beside the lens state. A HOLD on a folder is the folder's own gesture: mark
            // everything in it read.
            val catOpen = a11y.getString("feeds_cat_open_$lens", "") ?: ""
            if (groups.isEmpty()) pane.row(
                // While a load is in flight the honest label is "loading", not an empty verdict —
                // the field report was this exact row appearing during corpus switches.
                if (loading) "(loading…)" else "(nothing in this lens yet)"
            ) {
                clearPick()
                adapter.submit(filterByNavDay(inLens))
            }
            for ((c, inCat) in groups) {
                val cOpen = catOpen == c
                // Drawn picked while it narrows the list — folded or not — so a remembered category
                // never narrows Incoming without a sign of it (2026-10-11 review); and while one of
                // its sites narrows it with the category folded, the category holds the mark.
                pane.row(c, count = inCat.count { !it.read }, run = "cats:$lens",
                    selected = holder == c && (slimFeedFilter == null || !cOpen),
                    folder = cOpen,
                    onLongPress = { showFolderHoldMenu(c, inCat) }
                ) {
                    if (cOpen) {
                        // Fold only — the right panel stays where the reader left it.
                        a11y.edit().putString("feeds_cat_open_$lens", "").apply()
                        renderDirectory()
                    } else {
                        a11y.edit().putString("feeds_cat_open_$lens", c).apply()
                        pickFeed(c, null)
                        renderDirectory()
                        adapter.submit(filterByNavDay(inCat))
                    }
                }
                if (!cOpen) continue
                // A long feed name WRAPS to a second line instead of truncating — no marquee (its
                // constant repaint ghosts on e-ink), just two static lines that stay readable.
                for (f in inCat.map { it.feedTitle }.filter { it.isNotBlank() }
                    .distinct().sortedBy { it.lowercase() }) {
                    val inFeed = inCat.filter { it.feedTitle == f }
                    // Marked only under the category (and lens) that holds the pick — a feed of the
                    // same name elsewhere stays plain.
                    pane.row(f, count = inFeed.count { !it.read }, level = 1, run = "sites:$lens:$c",
                        selected = holder == c && slimFeedFilter == f,
                        onLongPress = { showFolderHoldMenu(f, inFeed) }
                    ) {
                        pickFeed(c, f)
                        renderDirectory()
                        adapter.submit(filterByNavDay(inFeed))
                    }
                }
            }
        }
        /**
         * "🔖 Later List" — the links you filed, as a feed among the feeds.
         *
         * Michael: "I'd love later list to appear in feeds like a feed instead of this, instead
         * under '🎧 The Listen' like '🔖 Later List'." So it sits directly under The Listen, at the
         * end of the media block and above everything that isn't a lens — not down in MORE with
         * Pickings and Bluesky, which are sources you look AT; this is the one you keep.
         *
         * It borrows the media lenses' SHAPE — a folder that opens a list and drops its children
         * out beneath — but its children are the intake LANES rather than Miniflux categories. That
         * grouping is the one thing the list would have lost by being flat, and the drawer already
         * speaks the axis, so it comes across as folders rather than as headers inside a list. It
         * costs nothing to serve: a lane is a filter over rows synthesized from the same store, not
         * four more sources.
         *
         * Same dual gesture as a lens: opening it shows the WHOLE Later List and drops its lanes
         * out; folding it goes back to All, because unlike a lens the folder is not a filter over
         * the current list — it IS the list, so there is nothing left to be standing in once it
         * closes.
         */
        fun laterFolder() {
            val open = a11y.getBoolean("feeds_later_open", false)
            val door = IncomingDoors.Door.LATER
            // One marked row: the folder while the whole list shows — or while its picked lane is
            // folded away beneath it (iOS FeedTree: the Later folder holds its lane).
            pane.row(door.title, RoomPane.doorFace(door), selected = mode == "later" && (laterLane == null || !open),
                folder = open) {
                if (open) {
                    a11y.edit().putBoolean("feeds_later_open", false).apply()
                    laterLane = null
                    renderDirectory()
                    if (mode == "later") switchTo("both", null)
                } else {
                    a11y.edit().putBoolean("feeds_later_open", true).apply()
                    laterLane = null
                    renderDirectory()
                    switchTo("later", null)
                }
            }
            if (!open) return
            // The lanes, one step in, each wearing its lens's face (Email: the envelope) and its
            // short word — Read · Watch · Listen · Email (iOS's paneWord, review 2026-10-12): "The
            // Read" here sat a fold under the RSS lens of the same name.
            for ((lane, _, plain) in com.toolsboox.plugin.feeds.nw.LaterFeed.LANES) {
                pane.row(com.toolsboox.plugin.feeds.nw.LaterFeed.paneWord(plain), RoomPane.laneFace(lane),
                    level = 1, selected = mode == "later" && laterLane == lane) {
                    laterLane = lane
                    renderDirectory()
                    switchTo("later", null)
                }
            }
        }

        // THE SHARED DOOR ORDER (Michael 2026-10-11: "the shared order everywhere") — the drawer
        // walks [IncomingDoors] instead of typing its own list, so Mail-first-with-The-Net-in-
        // SOURCES can't come back: Save for later · The Net · The Mail ↗ · then FEEDS from Bluesky
        // down. The `when` is exhaustive on purpose — a door added to the list has to be seated or
        // skipped here. Each door wears its monochrome face ([RoomPane.doorFace]) and its one name.
        for (door in IncomingDoors.ORDER) when (door) {
            IncomingDoors.Door.SAVE_FOR_LATER -> pane.row(door.title, RoomPane.doorFace(door)) { intakeLinkFromClipboard() }
            // 🌐 The Net, up beside ➕ as on the Hub (09-13: "+ for later just before The Net,
            // the net above The Mail") — it was down in SOURCES as "The Net · surf…".
            IncomingDoors.Door.THE_NET -> pane.row(door.title, RoomPane.doorFace(door)) {
                findNavController().navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                    com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true))
            }
            // 📧 The Mail has its own room now (Michael 2026-10-11: "You can move mail ... out of
            // incoming"): its fold left this drawer; one row keeps the hop one tap, where it was.
            // The FEEDS heading moves down to 🦋 — what follows it is RSS and its kin only.
            IncomingDoors.Door.THE_MAIL -> {
                pane.row("${door.title} ↗", RoomPane.doorFace(door)) { openMailRoom(findNavController(), popFeeds = true) }
                section("FEEDS")
            }
            // 🦋 Bluesky's TIMELINE, read like a feed (mode "bsky") — a different screen from the
            // Hub's 🦋 row, which moved to the Desk on 2026-10-11 (Michael: "Can Bluesky move to
            // desk?") and opens the replies-and-pad screen. Kept here until he says otherwise: this
            // row is the only door to the timeline view (iOS's Desk 🦋 opens the timeline instead —
            // flagged for him). A plain row, no caret: it is a source, not a medium (no "starred
            // Bluesky" on the server side), so the lens grammar doesn't fit it.
            IncomingDoors.Door.BLUESKY -> pane.row(door.title, RoomPane.doorFace(door), selected = mode == "bsky") { switchTo("bsky", null) }
            IncomingDoors.Door.READ -> lensRow(door, "read")
            IncomingDoors.Door.WATCH -> lensRow(door, "watch")
            IncomingDoors.Door.LISTEN -> lensRow(door, "listen")
            IncomingDoors.Door.LATER -> laterFolder()
            // ⭐ Starred left the drawer 09-12 (it lives in the hub as "All Stars").
            IncomingDoors.Door.STARRED -> Unit
            // Asks & Answers rides with the media folders per Michael's list, but it's a different
            // corpus (the local AskFeedStore Q&A log, mode "asklog") with no Miniflux categories —
            // so it's a plain folder row: no caret, no dropdown, and the view toggle doesn't compose.
            IncomingDoors.Door.ASKS -> pane.row(door.title, RoomPane.doorFace(door), selected = mode == "asklog") { switchTo("asklog", null) }
            // # The saved searches ride under the search field at the drawer's head (09-03).
            IncomingDoors.Door.SMART -> Unit
            // 📡 Local closes the list from SOURCES below, with its subscriptions beneath it.
            IncomingDoors.Door.LOCAL -> Unit
        }
        // 🕘 THE LOG, reachable from inside the room too (09-03: "it does a good service of
        // tracking where you've been in ledger — what you've read, listened, watched") — the
        // same door the hub menu carries, one tap from the piles it records.
        pane.row("The Log", RoomPane.LOG) {
            com.toolsboox.plugin.calendar.ui.ReadingLogSelection.origin = null
            findNavController().navigate(R.id.action_to_reading_log)
        }

        // (SEARCH section retired 09-03 — "why is there a search above views and a search
        // below asks": the LIVE field at the drawer's top is the one search. Saved searches
        // ride right under that field; keeping one is the ＃ chip beside it.)

        // (No SOURCES section anymore: it had become a full duplicate of the FEEDS accordion —
        // "theres just a second list below it that says all feeds" — since categories now nest
        // their feeds right where they are. Clear moved up beside the VIEWS chips; feeds with
        // no folder live under each medium's Uncategorised. The old SHOW section is likewise
        // gone — Unread/Read/All are the VIEWS axis. Smart feeds live under SEARCH.)

        // "MORE" retired (Michael, 09-03: a junk-drawer label). What lived there was two real
        // things wearing one vague name: SOURCES you read from, and the doors that ADD new ones.
        section("SOURCES")
        // 🎙 Podcasts lives with the SOURCES (his seat, 09-03: "move that to sources and just
        // say 'Podcasts'") — the finder, with By-interest inside it, one door.
        pane.row("Podcasts\u2026", RoomPane.PODCASTS) { showFindShow() }
        // (🌐 The Net and 🦋 Bluesky moved up into the shared door order — Michael 2026-10-11.)
        // 📡 Local Feeds, the last door in the shared order, keeps its seat here because its
        // subscriptions hang beneath it.
        pane.row(IncomingDoors.Door.LOCAL.title, RoomPane.doorFace(IncomingDoors.Door.LOCAL),
            selected = mode == "local" && localSub == null) { switchToLocal(null) }
        com.toolsboox.plugin.feeds.nw.LocalFeedStore.subscriptions(ctx).forEach { sub ->
            pane.row(sub.title, level = 1, selected = mode == "local" && localSub?.id == sub.id) { switchToLocal(sub) }
        }
        section("ADD")
        // (Podcasts wears the microphone, not a magnifier — a second magnifier beside SEARCH read
        // as "two searches", 09-03: this door FINDS A PODCAST to subscribe to.)
        // ⤴ The stranded-sub rescue (09-03: his first found podcast landed in Local Feeds
        // because of the encrypted-prefs bug): with a server configured and local subs
        // standing, one row moves them all up where every device sees them.
        run {
            val (mfBase, mfTok) = com.toolsboox.plugin.feeds.nw.FeedPrefs.miniflux(ctx)
            val locals = com.toolsboox.plugin.feeds.nw.LocalFeedStore.subscriptions(ctx)
            if (mfBase.isNotBlank() && mfTok.isNotBlank() && locals.isNotEmpty()) {
                pane.row("Local feeds \u2192 your server (${locals.size})…", RoomPane.TO_SERVER) {
                    lifecycleScope.launch {
                        var moved = 0
                        withContext(Dispatchers.IO) {
                            for (sub in locals) {
                                val res = runCatching {
                                    com.toolsboox.plugin.feeds.nw.MinifluxClient()
                                        .createFeed(mfBase, mfTok, sub.url, null)
                                }.getOrNull()
                                if (res is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok) {
                                    com.toolsboox.plugin.feeds.nw.LocalFeedStore.remove(
                                        ctx.applicationContext, sub.id)
                                    moved++
                                }
                            }
                        }
                        if (isAdded) {
                            showMessage("\u2934 $moved moved \u2014 every device sees them now", binding.root)
                            renderDirectory()
                        }
                    }
                }
            }
        }
        // ALWAYS a door to the feed server (the settings panel hid itself after the first save —
        // the same emptiness gate that hid mail accounts; rule of 09-07).
        pane.row("Feed server…", RoomPane.SETTINGS) { showFeedSettings() }
        pane.row("Add feed by URL…", RoomPane.ADD_LINK) { showAddLocalFeed() }
        pane.row("Import OPML…", RoomPane.IMPORT) { opmlPicker.launch("*/*") }
        pane.row("Export OPML…", RoomPane.EXPORT) { exportOpml() }
        pane.finish()
    }

    /**
     * FILE WHATEVER LINK IS ON THE CLIPBOARD, then show the Later List so the row you just made is
     * the confirmation.
     *
     * This is what the 🔖 Later chip became. Michael: "the current later button can become a later
     * list intake spot." Reading the list moved down to the 🔖 Later List folder with the rest of
     * the feeds; the button stayed where his thumb already goes and changed jobs, from a door into
     * the list to the place you put something IN it.
     *
     * It PASTES rather than asking, because a link you want to keep is nearly always the thing you
     * just copied, and the alternative — a text field in the drawer — is a keyboard and two taps on
     * an e-ink panel to do what the clipboard already knows. No modal confirmation either: the
     * thing that proves it worked is the list it landed in, one row down in this same drawer, with
     * the item at the top of it.
     *
     * The lane is inferred from the host by [ShareTextParser.inferKind] — the same function the
     * share-to-file flow uses, so a YouTube link files under 📺 The Watch whether it arrives by
     * share sheet or by hand, and filing goes through [IntakePageStore.fileLink], the same write
     * path the share target and both readers take. So a link filed here is dressed with its og:
     * face, cached for offline reading, published to the personal RSS feed and merged across
     * devices exactly like every other one. Nothing about it is a second, lesser kind of filing.
     */
    private fun intakeLinkFromClipboard() {
        val ctx = requireContext()
        val clip = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager)?.primaryClip
        val text = (0 until (clip?.itemCount ?: 0))
            .mapNotNull { clip?.getItemAt(it)?.coerceToText(ctx)?.toString() }
            .joinToString("\n")
        val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.extractUrls(text).firstOrNull()
        if (url == null) {
            // The honest report, not a shrug: he pressed a button and it has to say what happened.
            showMessage("Nothing on the clipboard to file — copy a link first.", binding.root)
            return
        }
        val kind = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.inferKind(url)
        val lane = com.toolsboox.plugin.feeds.nw.LaterFeed.LANES.firstOrNull { it.first == kind }?.third ?: "The Read"
        // fileLink does network work (the og: fetch, the article cache, the publish) on its own
        // daemon threads, but the day-file read/merge/write in front of them is disk — off main.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                    ctx.applicationContext, java.time.LocalDate.now(), kind, url, null)
            }
            if (!isAdded) return@launch
            val host = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: "link"
            showMessage("Filed to $lane · $host", binding.root)
            // Open the list ON the lane it landed in, and drop the folder open so the lane it chose
            // is visible rather than merely asserted by a toast that is already fading.
            requireContext().getSharedPreferences("ledger_a11y", 0)
                .edit().putBoolean("feeds_later_open", true).apply()
            laterLane = kind
            renderDirectory()
            switchTo("later", null)
        }
    }

    /**
     * Opening "the feed directory" now just opens the ONE unified drawer (VIEWS · FEEDS · SEARCH · MORE) —
     * there's no separate accordion to ladder into anymore. Kept as a named entry point because a
     * few arrival paths (onResume, smart-feed-added) call it to pop the drawer open.
     */
    private fun showFeedDirectory() {
        applyDirectoryDrawer(true)
        renderDirectory()
    }

    /**
     * The broader Ledger directory (second header button): Almanac, History, Later, and
     * Personal, plus the surfaces to jump to — so you can leave the feed for the rest of the
     * Ledger without going through the day page.
     */
    private fun showLedgerDirectory() {
        if (isAdded) showAccordion(ledgerDirectoryFolders(this), anchorEnd = railDockedRight())
    }

    private fun toggleStar(entry: FeedEntry) {
        // A Mail row stars through the MAIL ceremony — InboxStore's keep pile, the to-do on
        // today's page, and the All Stars intake gram — not the Miniflux/ReadingEvent one.
        // MailVerbs is the single owner of that ceremony, so the star means the same thing
        // whichever surface holds the letter.
        mailById[entry.id]?.let { toggleMailStar(entry, it); return }
        if (com.toolsboox.plugin.feeds.nw.LocalFeedStore.isLocal(entry.id)) {
            com.toolsboox.plugin.feeds.nw.LocalFeedStore.setStar(requireContext(), entry.url, !entry.starred)
            // Newly starred → log to today's Ledger too, exactly like a Miniflux star, so local-feed
            // stars aren't silently missing from the Notes / Ledger Log.
            if (!entry.starred) lifecycleScope.launch(Dispatchers.IO) { logStar(entry) }
            showMessage(if (entry.starred) R.string.feeds_unstarred else R.string.feeds_starred)
            reflectStar(entry, !entry.starred)
            return
        }
        if (com.toolsboox.plugin.feeds.nw.LaterFeed.isLater(entry.id)) {
            // A Later List row stars into its OWN sidecar, and deliberately skips the ceremony
            // every other star performs. There is no Miniflux entry to POST to and never will be —
            // that much it shares with the Pickings rows below — but the important half is what it
            // does NOT do: no logStar, so no Log line, no Star Sort gram, no webhook. A filed link
            // is already IN the ledger, because filing it is what put it there and minted its gram.
            // Starring it again is triage inside a list you already keep, not a new act of keeping,
            // and running the ceremony would drop a DUPLICATE gram on Star Sort for something
            // already sitting on Star Sort. (See LaterFeed.setStar, which is the only writer.)
            com.toolsboox.plugin.feeds.nw.LaterFeed.setStar(requireContext(), entry.url, !entry.starred)
            showMessage(if (entry.starred) R.string.feeds_unstarred else R.string.feeds_starred)
            reflectStar(entry, !entry.starred)
            return
        }
        if (entry.id <= 0) {
            // Pickings / Bluesky / Ask-log rows have synthetic negative ids and no Miniflux entry:
            // star into the Ledger corpus only — never send a synthetic id to the server.
            if (!entry.starred) lifecycleScope.launch(Dispatchers.IO) { logStar(entry) }
            showMessage(if (entry.starred) R.string.feeds_unstarred else R.string.feeds_starred)
            reflectStar(entry, !entry.starred)
            return
        }
        val p = prefs()
        val url = p.getString(KEY_URL, "").orEmpty()
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val wasStarred = entry.starred
        lifecycleScope.launch {
            // Honest UI: only toast success and log to the corpus when the server call
            // succeeded. Discarding the Result meant offline stars toasted "Starred",
            // entered the Ledger, and silently never reached Miniflux — shelf and corpus
            // then disagreed forever.
            val res = withContext(Dispatchers.IO) { miniflux.toggleStar(url, token, entry.id) }
            when (res) {
                is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Ok -> {
                    // Newly starred → log to today's Ledger so it joins the corpus + sync.
                    if (!wasStarred) withContext(Dispatchers.IO) { logStar(entry) }
                    showMessage(if (wasStarred) R.string.feeds_unstarred else R.string.feeds_starred)
                    reflectStar(entry, !wasStarred)
                }
                is com.toolsboox.plugin.feeds.nw.MinifluxClient.Result.Err -> {
                    android.widget.Toast.makeText(
                        requireContext(), "⚠ Star didn't reach Miniflux — ${res.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Reflect a star LOCALLY — flip the model + repaint the single row, and never call
     *  refresh(): that reloads the feed and closes any open in-pane article, which is what
     *  kicked you out of what you were reading when you starred it. Counts don't change on a
     *  star (unread membership is unchanged), so the drawer is left untouched — no e-ink flash. */
    private fun reflectStar(entry: FeedEntry, nowStarred: Boolean) {
        allEntries = allEntries.map { if (it.id == entry.id) it.copy(starred = nowStarred) else it }
        adapter.setStarred(entry.id, nowStarred)
        if (currentArticle?.id == entry.id) {
            currentArticle = currentArticle?.copy(starred = nowStarred)
            // Reflect the filled/hollow star on the reader toolbar immediately.
            binding.feedsStar.setImageResource(if (nowStarred) R.drawable.ic_starred else R.drawable.ic_star)
            // …and on the rail, which wears the same star.
            redressRail()
        }
    }

    /** Append a starred article to today's CalendarDay as a ReadingEvent(article) — and mint
     *  its gram onto today's Intake page. Every newly-starred path (Miniflux, local feed,
     *  synthetic Later/Pickings row) comes through here, on Dispatchers.IO. */
    private fun logStar(entry: FeedEntry) {
        try {
            val root = documentsRoot()
            val today = LocalDate.now()
            // Idempotent by id: star → unstar → star used to append duplicate art-<id>
            // events that rode sync to every device.
            val id = "art-${entry.id}"
            val pubDay = runCatching { LocalDate.parse(entry.publishedAt.take(10)) }.getOrNull()
            val pubDate = pubDay?.atTime(12, 0)?.atZone(java.time.ZoneId.of("UTC"))
                ?.toInstant()?.let { Date.from(it) }
            // Each day's load→save under that day's lock (design Phase 0, Michael 2026-10-12): the
            // ★ and the one-tap Highlight both write today's file from background threads, and an
            // unlocked save here could drop the highlight's Log line or its gram pick.
            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
            // A day file that won't read throws here (caught below) — never a fresh day saved over it.
            val day = calendarDayService.loadForWriteOrThrow(root, today, null, Locale.getDefault())
            day.readingEvents.removeAll { it.id == id }
            day.readingEvents.add(
                ReadingEvent(
                    id = id,
                    kind = ReadingEvent.Kind.ARTICLE,
                    date = Date(),
                    title = entry.title,
                    source = entry.feedTitle.ifBlank { null },
                    url = entry.url.ifBlank { null },
                    note = entry.blurb.ifBlank { null },
                    starred = true,
                    published = pubDate
                )
            )
            calendarDayService.save(root, today, day)
            }
            // A star lives on TWO days: today (when you starred it) and the day the piece
            // was published — the Log's windowed scan finds each in its own day file. The
            // companion carries the star date in its note as the clarifying label.
            if (pubDay != null && pubDay != today && pubDate != null) com.toolsboox.plugin.calendar.ot.DayLocks.withDay(pubDay) {
                val pub = calendarDayService.loadForWriteOrThrow(root, pubDay, null, Locale.getDefault())
                val pubId = "$id-pub"
                pub.readingEvents.removeAll { it.id == pubId }
                pub.readingEvents.add(
                    ReadingEvent(
                        id = pubId,
                        kind = ReadingEvent.Kind.ARTICLE,
                        date = pubDate,
                        title = entry.title,
                        source = entry.feedTitle.ifBlank { null },
                        url = entry.url.ifBlank { null },
                        note = "★ starred ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}",
                        starred = true,
                        published = pubDate
                    )
                )
                calendarDayService.save(root, pubDay, pub)
            }
        } catch (e: Exception) {
            Timber.w(e, "failed to log starred article")
        }
        // Grams for stars: the ★ also lands the entry as a movable link-card gram on today's
        // Intake page — the read-later board Michael arranges and writes pen notes around.
        // Render + place stay on this IO thread; the row's cached thumbnail is reused when the
        // list already loaded it; deduped by sourceLink inside (re-stars don't stack twins).
        runCatching {
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.placeStarGram(
                this, calendarDayService, documentsRoot(),
                title = entry.title, feedTitle = entry.feedTitle, url = entry.url,
                kind = entry.kind, imageUrl = entry.imageUrl,
                thumb = entry.imageUrl?.let { FeedThumbCache.get(it) },
                excerpt = entry.blurb
            )
        }.onFailure { Timber.w(it, "intake gram failed") }
        // Generic, opt-in on-star reactions (webhook-out / in-app synthesis). No-ops unless
        // the user configured them in Feed settings.
        runCatching {
            com.toolsboox.plugin.feeds.nw.StarHooks.onNewStar(
                requireContext().applicationContext, prefs(), entry
            ) { title, body, srcUrl -> writeCorrespondence(title, body, srcUrl) }
        }.onFailure { Timber.w(it, "star hooks failed") }
    }

    /** Write a correspondence ReadingEvent ("the Ledger wrote back") into today's day. The
     *  "↩ " source prefix makes the Log render it as a reply and keeps it out of the feed.
     *  Today's page that won't read is never written over — and the reply is not dropped in
     *  silence for it (2026-10-16 review, item 9): "Couldn't keep the reply — today's page won't
     *  read" (iOS's words), with [Try again] writing THIS reply, not asking for another. Off the
     *  main thread. */
    private fun writeCorrespondence(title: String, body: String, sourceUrl: String?) {
        val attempt = runCatching {
            val root = documentsRoot()
            val today = LocalDate.now()
            // Under today's lock, like every other Log writer here (design Phase 0).
            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
            val day = calendarDayService.loadForWriteOrThrow(root, today, null, Locale.getDefault())
            day.readingEvents.add(
                ReadingEvent(
                    id = "reply-${java.util.UUID.randomUUID()}",
                    kind = ReadingEvent.Kind.ARTICLE,        // wire kind (Boox/iOS both decode)
                    date = Date(),
                    title = title,
                    source = "↩ the Ledger wrote back",       // "↩" flags it as correspondence
                    url = sourceUrl?.ifBlank { null },
                    excerpt = body.ifBlank { null }
                )
            )
            calendarDayService.save(root, today, day)
            }
        }.onFailure { Timber.w(it, "failed to write correspondence") }
        val refused = com.toolsboox.plugin.calendar.fi.CalendarDayService.refusal(attempt.exceptionOrNull(), KEEP_THE_REPLY)
            ?: return
        val act = activity ?: return
        act.runOnUiThread {
            // (This fragment's `view` is its layout id — the standing view is requireView's.)
            val v = if (isAdded) runCatching { requireView() }.getOrNull() else null
            if (v != null) {
                com.google.android.material.snackbar.Snackbar.make(v, refused, REPLY_RETRY_MS)
                    .setAction("Try again") {
                        lifecycleScope.launch(Dispatchers.IO) { writeCorrespondence(title, body, sourceUrl) }
                    }.show()
            } else android.widget.Toast.makeText(act.applicationContext, refused, android.widget.Toast.LENGTH_LONG).show()
        }
    }


    private fun prefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            requireContext(), PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun showLoading() {}
    override fun hideLoading() {}

    companion object {
        /** The on-star reply refused by today's page — iOS's verb (FeedStore's "keep the reply"). */
        const val KEEP_THE_REPLY = "keep the reply"
        /** A door with a button stays long enough for a pen to reach it on e-ink. */
        private const val REPLY_RETRY_MS = 8_000
        const val PREFS = "ledger_feeds_prefs"
        /** Plain prefs for the letter reader's two choices — Settings writes them, the open reads. */
        const val MAIL_PREFS = "ledger_mail_prefs"
        const val MAIL_PREF_PICTURES = "mail_load_pictures"
        const val MAIL_PREF_AS_SENT = "mail_open_as_sent"
        const val KEY_URL = "miniflux_url"
        const val KEY_TOKEN = "miniflux_token"
        const val KEY_DIR_OPEN = "feeds_dir_open"
        /** The Mail room's own drawer state (seeded from [KEY_DIR_OPEN] on its first visit). */
        const val KEY_MAIL_DIR_OPEN = "mail_dir_open"
        /** The nav argument naming the room — MailFragment's default is [ROOM_MAIL]. */
        const val ARG_ROOM = "room"
        const val ROOM_MAIL = "mail"
        /** The list's search field is SUMMONED, not standing (09-14): the rail's 🔍 shows it. */
        const val KEY_LIST_SEARCH = "feeds_list_search_summoned"
        /** Pictures in articles (on). The letter has its own switch; this one is the article's. */
        const val KEY_PICTURES = "feeds_pictures"
        /** How long a clean 🚫 Junk read stands before a plain refresh asks the server again. */
        private const val JUNK_FRESH_MS = 5 * 60_000L

        // Synthetic-row id bands, below LocalFeedStore's (-1000 .. -1e9) so a made-up id
        // can never collide with a real Miniflux or local-feed entry. Rows count DOWN
        // from their base (id--), so each band stays disjoint.
        private const val PICKINGS_ID_BASE = -1_100_000_000L
        // (The Later List's band is -1.2e9 and the 99 million below it — it owns its ids now that
        // it hashes them for stability rather than counting down. See LaterFeed.ID_BASE.)
        private const val BSKY_ID_BASE = -1_300_000_000L

        /** How many read posts are worth a request. Small enough that a normal reading session
         *  flushes at least once before he leaves; large enough that skimming a screenful is one
         *  round trip and not twenty. */
        private const val BSKY_SEEN_BATCH = 20

        /** The in-reader "answer this" link. Not a real scheme anyone registers — it exists only to
         *  be recognised by [openEntry]'s navigation interceptor and turned into a fragment jump.
         *  Which post it means is the one the reader has open, so it carries no argument. */
        const val BSKY_REPLY_SCHEME = "ledger://bsky-reply"

        /** ▶ on an episode page — start THIS episode, at its remembered spot. */
        const val LISTEN_PLAY_SCHEME = "ledger://listen-play"

        /** ⬇ on an episode page — file it for offline, a choice that SYNCS
         *  ([ListenState.wantOffline]) so the ask reaches the other devices too. */
        const val LISTEN_SAVE_SCHEME = "ledger://listen-save"

        /** The Mail lens's kindFilter token — a corpus lens beside read/watch/listen, resolved by
         *  the mail store rather than by what a row guesses about itself (see applyKind). */
        private const val KIND_MAIL = "mail"
        /** The in-progress shelf (09-19): its rows' ids live below this, in their own range. */
        private const val SHELF_ID_BASE = -3_000_000_000L
        private const val SHELF_LABEL = "⏯ In progress · across your devices"
        private const val SHELF_RESUME_MIN_MS = 30_000L

        // (The pile tokens live in [MailPlace]: [MailPlace.of] mints an account's Sent / Junk /
        // Trash, [MailPlace.where] reads any token back.)

        /** Mail's synthetic-row band. Minted as BASE − (32-bit hash of the message id), so the
         *  band spans −1.4e9 down to about −5.7e9 — beneath every other band (local feeds bottom
         *  out at −1e9; Pickings, Later and Bluesky all live in the −1.1e9…−1.3e9 shelf) — and a
         *  reload keeps each letter's row id stable, which is what lets stars repaint in place. */
        private const val MAIL_ID_BASE = -1_400_000_000L

        /** The open letter's two ledger:// doors — recognised by the mail pane's interceptor the
         *  way [BSKY_REPLY_SCHEME] is; which letter they mean is the one that's open. */
        private const val MAIL_IMAGES_SCHEME = "ledger://mail-images"
        private const val MAIL_MORE_SCHEME = "ledger://mail-more"
        private const val MAIL_ATT_SCHEME = "ledger://mail-att/"
        /** A thread card under the letter (2026-10-10): `ledger://mail-thread/<key>`, or `/back`. */
        private const val MAIL_THREAD_SCHEME = "ledger://mail-thread/"

        /** "Try again" on a live page that wouldn't load ([SynthSources.ARTICLE_WONT_LOAD]). */
        private const val ARTICLE_RETRY_SCHEME = "ledger://article-retry"

        /** The 🔎 server-search row's address — recognised by [openEntry] before any other
         *  routing; which query it means rides in [mailSearchQuery], named on the row's face. */
        private const val MAIL_SEARCH_SCHEME = "ledger://mail-search"

        /** The 🔎 row's fixed id, parked far beneath mail's own synthetic band (−1.4e9…−5.7e9)
         *  so no letter's hashed id can ever collide with the one control row in the list. */
        private const val MAIL_SEARCH_ROW_ID = -7_000_000_000L

        /** The drawer's pseudo-category for feeds with no Miniflux folder. */
        private const val UNCATEGORISED = "Uncategorised"
    }
}

/** Hand the tapped entry to the article fragment without stuffing it through nav args. */
object FeedSelection {
    var entry: FeedEntry? = null

    /** Set by the day-page directory: open the feeds directory drawer as soon as entries load. */
    var openDirectoryOnArrival = false

    /** Back-ladder: land on the day page with the today menu open, Feed Ledger expanded. */
    var openTodayHubOnArrival = false

    /** An article to open directly in the feed's in-pane reader on next open (e.g.
     *  from a gram's "Go to source"). Consumed once by FeedsFragment. */
    var pendingInPaneEntry: FeedEntry? = null

    /** A highlight to land on when that article opens — the Log line's id or the mark's (Michael
     *  2026-10-12: "Log → the passage", one tap). Consumed once by the opening reader. */
    var pendingMarkId: String? = null

    /** The text the Log's article was highlighted in ("feed" / "reader" / "live"), so it reopens in
     *  that view (design critique #7). Consumed once with [pendingMarkId]. */
    var pendingView: String? = null

    /** ▶ Continue — a listen-state id (episode page URL) to resume playing on next open,
     *  set by the directory's Continue row. Consumed once by FeedsFragment. */
    var pendingResumeListenId: String? = null

    /** The plain 📰 Feeds door: land where you were — the remembered place (IncomingPlace,
     *  Michael 2026-10-11), the drawer's lens fold following it — whatever view rides beside it. */
    var restorePlaceOnArrival = false

    /** The list the article was opened from, so the reader can page prev/next. */
    var list: List<FeedEntry> = emptyList()

    /** A feed title the Feed Ledger should filter to on next open (set from the day-page hub). */
    var filterFeedTitle: String? = null

    /** Which view to open: "feed" (RSS unread), "stars", or "later" (read-later intake). */
    var mode: String? = null
    /** Optional read/watch/listen lens to filter to. */
    var kind: String? = null
    /** Optional Later List LANE ("read"/"watch"/"listen"/"educate") to open narrowed to. Its own
     *  channel and not [kind]: a lane is where a link was filed, a kind is what a row looks like. */
    var laterLane: String? = null

    /** The Mail room's MAILBOX to arrive on — null = the one you were in (the remembered place,
     *  [MailPlace]), [MailPlace.ALL_ACCOUNTS] = every account (📥 Inbox), an account id = that
     *  inbox, one account's ➤ Sent / 🚫 Junk / 🗑 Trash ([MailPlace.of]) or the every-account
     *  [MailPlace.SENT] / [MailPlace.TRASH] — read only by The Mail's room
     *  (MailFragment, reached through [openMailRoom]). The standalone inbox screen retired; its
     *  account narrowing used to ride a preference instead of a channel like this one. */
    var mailMailbox: String? = null

    /** A letter to open in the article pane on arrival — the id out of a mail://<id> source link,
     *  set by the gram router the way [pendingInPaneEntry] is set for articles. Consumed by the
     *  lens once, after its warm load, and cleared even when the store no longer holds the
     *  letter, so a stale request can't fire on a later ordinary visit. */
    var pendingMailOpenId: String? = null

    /** Set by the article's ☰ so the feed list pops the RSS directory open on return. */
    var openDirectory: Boolean = false

    /** Consume the pending mode/kind (one-shot) after the fragment applies it. */
    fun consume(): Pair<String, String?> {
        val m = mode ?: "feed"; val k = kind
        mode = null; kind = null
        return m to k
    }

    /** Consume the pending Later List lane (one-shot), alongside [consume]. */
    fun consumeLaterLane(): String? {
        val l = laterLane; laterLane = null; return l
    }

    /** Consume the pending Mail mailbox (one-shot), alongside [consume]. */
    fun consumeMailMailbox(): String? {
        val b = mailMailbox; mailMailbox = null; return b
    }
}

/**
 * How the mail lens orders its pile — the directory dial's grammar (a ⇅ row that cycles, the
 * choice persisted) pointed at letters: by the letter's own DATE (newest first, the standing
 * default), by SENDER (one correspondent's letters shelved together, newest within), or by
 * SUBJECT (threads fall together once Re:/Fwd: dressing is ignored). Persisted in the mail
 * lens's own prefs file under a key of its own — see [FeedsFragment.setMailSort].
 */
private enum class MailSort(val id: String, val label: String) {
    DATE("date", "Date"), SENDER("sender", "Sender"), SUBJECT("subject", "Subject");

    fun next(): MailSort = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String?): MailSort = entries.firstOrNull { it.id == id } ?: DATE
    }
}
