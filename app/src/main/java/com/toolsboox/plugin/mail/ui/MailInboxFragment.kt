package com.toolsboox.plugin.mail.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentMailInboxBinding
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailReaderHtml
import com.toolsboox.plugin.mail.MailAccountStore
import com.toolsboox.plugin.mail.MailMessageTooLarge
import com.toolsboox.plugin.mail.MailSync
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * The unified inbox: messages from every email place in one list, where an email is a first-class
 * object. Star one and it becomes a to-do (a real [LedgerItem] on today's page, exactly as the
 * Kanban "add card" does); open it to read it and reply (out the account it arrived on, via SMTP).
 * The gear opens Mail settings -- the accounts list and a per-account editor whose password is kept
 * in EncryptedSharedPreferences. Mirrors iOS UnifiedInboxView; matches CorrespondenceFragment's
 * conventions (programmatic rows in a ScrollView, ModalScale-wrapped dialogs, Dispatchers.IO work).
 */
@AndroidEntryPoint
class MailInboxFragment @Inject constructor() : ScreenFragment() {

    companion object {
        // The four positions of the mail view axis, named to match the iPad's sidebar chips so the
        // same four words mean the same four things on both devices.
        private const val VIEW_STARRED = "starred"
        private const val VIEW_UNREAD = "unread"
        private const val VIEW_READ = "read"
        private const val VIEW_ALL = "all"
    }


    override val view = R.layout.fragment_mail_inbox
    private lateinit var binding: FragmentMailInboxBinding

    @Inject
    lateinit var calendarDayService: CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    // The list's VIEW AXIS — unread · read · all · starred — matching the four the iPad's sidebar
    // chips offer, so the same four words mean the same four things on both devices.
    //
    // This was a single `onlyStarred` Boolean, which could say "the kept pile" or "everything" and
    // nothing in between: there was no way to ask the inbox what you hadn't read yet, which is the
    // question you most often have OF an inbox. Starred stays one of the four positions rather than
    // a separate switch, because it is the same kind of question as the other three — which subset
    // am I looking at — and two independent controls answering that would contradict each other.
    private var mailView: String = VIEW_STARRED
    /** The kept pile, as a Boolean — the shape the Clear guard and the empty state still read. */
    private val onlyStarred: Boolean get() = mailView == VIEW_STARRED

    /** Move the lens, remember it, redraw. Persisted so the inbox reopens on the question you left
     *  it asking — the account narrowing already survived the trip away, and the view axis is the
     *  same kind of choice. */
    private fun setMailView(view: String) {
        if (mailView == view) return
        mailView = view
        requireContext().getSharedPreferences("ledger_mail_inbox", 0).edit()
            .putString("mail_view", view).apply()
        renderChips(); render()
    }
    private var messages: List<InboxMessage> = emptyList()
    private var refreshing = false

    // Which account the unified list is narrowed to (null = every account). Persisted, so the
    // inbox reopens the way it was left — the unified view stays the default, the narrowing a
    // choice that survives the trip away.
    private var accountFilter: String? = null

    // Whether the ✉ All chip's account dropdown is unfolded (persisted, like the feed drawer's).
    private var accountsOpen = false

    // --- Search: one field, two layers. A submitted query filters the LOADED mail (fetch window +
    // starred pile) instantly; the 🔎 row under those results asks the servers (IMAP UID SEARCH)
    // and, once answered, the server matches replace the local ones until the search clears.
    // On-submit + ✕, not per-keystroke: e-ink pays a full redraw for every filter pass, so the
    // list changes once, when the reader says go. While a search is active the almanac window,
    // the To-dos/All chip, and the account chips stand aside (the account filter still narrows —
    // a narrowed inbox should search narrowed); clearing restores the normal inbox untouched.
    private var searchQuery = ""
    private var serverResults: List<InboxMessage>? = null   // null = local layer showing
    private var serverSearching = false
    // Stale-result guard: bumped on every submit/clear; a slow server search landing for an old
    // query checks it and stands down instead of stamping stale rows over a new search.
    private var searchSeq = 0

    // Almanac filter — the inbox is browsable by date exactly as the feed is. Day+today is the LIVE
    // view (all mail, so your to-dos never hide); any other window keeps only mail from that window.
    private var navBar: com.toolsboox.plugin.calendar.ui.CalendarNavBarHost? = null
    private var navGranularity = "day"
    private var navAnchor: LocalDate = LocalDate.now()

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMailInboxBinding.bind(view)
        binding.mailClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        // The ☰ by the almanac's left carat opens the main Ledger menu (the accordion hub every
        // surface carries), so Mail isn't a dead-end — the title bar it replaces is gone.
        binding.mailMenu.setOnClickListener { showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this)) }
        binding.mailRefresh.setOnClickListener { refresh() }
        binding.mailSettings.setOnClickListener { showAccountsList() }
        binding.mailClear.setOnClickListener { clearUnstarred() }
        val uiPrefs = requireContext().getSharedPreferences("ledger_mail_inbox", 0)
        accountFilter = uiPrefs.getString("account_filter", "")!!.ifBlank { null }
        accountsOpen = uiPrefs.getBoolean("accounts_open", false)
        // Starred stays the default — the kept pile is what an inbox is FOR here — but the lens is
        // remembered once moved, so an inbox left on Unread reopens on Unread.
        mailView = uiPrefs.getString("mail_view", VIEW_STARRED) ?: VIEW_STARRED

        // The search row (Reading Log's field idiom): ⏎ submits, ✕ clears back to the inbox.
        binding.mailSearchField.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) { submitSearch(v.text.toString()); true } else false
        }
        binding.mailSearchClear.setOnClickListener { clearSearch() }
        renderChips()

        // Same Almanac strip as the feed: arrows step the window in place, a period tap filters to
        // that day/week/month — the inbox never leaves for the calendar (it's a filter, not a jump).
        navBar = com.toolsboox.plugin.calendar.ui.CalendarNavBarHost(
            requireContext(), binding.mailNavigator, this,
            onStepDay = { d ->
                val dir = if (d.isBefore(navAnchor)) -1 else 1
                navAnchor = stepByGranularity(navAnchor, dir); renderNav(); renderChips(); render()
            },
            onSelectPeriod = { g, d -> navGranularity = g; navAnchor = d; renderNav(); renderChips(); render() }
        )
        renderNav()

        val ctx = requireContext()
        messages = InboxStore.messages(ctx)
        render()
        if (InboxStore.hasAccounts(ctx)) refresh()
    }

    /**
     * The state chips under the Almanac strip: To-dos / All, the per-account filter, and — when
     * the strip is parked off today — the active window, named plainly. Selected = solid black
     * chip with white text: bold-vs-regular was easy to miss, and any gray wash dithers away on
     * an e-ink panel; black-on-white inverted is the one treatment a Boox can't lose.
     */
    private fun renderChips() {
        val ctx = context ?: return
        val row = binding.mailChips
        row.removeAllViews()

        fun chip(label: String, selected: Boolean, onClick: () -> Unit) {
            row.addView(TextView(ctx).apply {
                text = label; textSize = 14f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xFF000000.toInt())
                    setColor(if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8), 0) }
                setOnClickListener { onClick() }
            })
        }

        chip("★ Starred", mailView == VIEW_STARRED) { setMailView(VIEW_STARRED) }
        // The two the inbox was missing. "What haven't I read" is the question you most often have
        // of a mailbox, and until now the only answers on offer were the kept pile or everything.
        chip("◦ Unread", mailView == VIEW_UNREAD) { setMailView(VIEW_UNREAD) }
        chip("● Read", mailView == VIEW_READ) { setMailView(VIEW_READ) }

        // ✉ All is the accordion header for the accounts (feed-drawer idiom: ▸/▾ caret; one tap
        // does BOTH — show the unified inbox and unfold the account rows; a second folds them).
        val accounts = MailAccountStore.all(ctx)
        val allLabel = if (accounts.isEmpty()) "✉ All" else "✉ All  " + (if (accountsOpen) "▾" else "▸")
        chip(allLabel, mailView == VIEW_ALL) {
            // The accordion still hangs off THIS chip, so its fold has to be decided before the
            // view flips — `onlyStarred` is derived now, and reading it after the flip would ask
            // about the state we just left.
            if (accounts.isNotEmpty()) accountsOpen = !accountsOpen || mailView != VIEW_ALL
            ctx.getSharedPreferences("ledger_mail_inbox", 0).edit()
                .putBoolean("accounts_open", accountsOpen).apply()
            setMailView(VIEW_ALL)
        }

        // The filter's face while the dropdown is folded: one selected chip naming the account,
        // so a narrowed inbox is never a surprise. Tapping it unfolds the rows to change it.
        val current = accounts.firstOrNull { it.id == accountFilter }
        if (current != null && !accountsOpen) chip("@ ${current.display}", true) {
            accountsOpen = true
            ctx.getSharedPreferences("ledger_mail_inbox", 0).edit().putBoolean("accounts_open", true).apply()
            renderChips()
        }

        // The window filter, named so there's no guessing what the strip has scoped to.
        if (navFiltered()) chip("🗓 ${windowLabel()}  ✕", true) {
            navGranularity = "day"; navAnchor = LocalDate.now()
            renderNav(); renderChips(); render()
        }

        // ✎ a fresh email — mail that isn't a reply now has a door from mail itself, not just
        // from a quick win. Never "selected": it's a verb chip, not a filter.
        chip("✎ Compose", false) {
            NavHostFragment.findNavController(this).navigate(R.id.action_to_mail_compose)
        }

        // Clear only makes sense while triaging All — it sweeps everything you didn't star.
        binding.mailClear.visibility = if (onlyStarred) View.GONE else View.VISIBLE

        renderAccountRows(accounts)
    }

    /** The unfolded account rows under the chip strip: "All accounts" + one row per inbox,
     *  indented inside the accent outline like every accordion dropdown in the app. Tapping a
     *  row narrows (or widens) the unified list; the choice is kept for next time. */
    private fun renderAccountRows(accounts: List<MailAccount>) {
        val ctx = context ?: return
        val panel = binding.mailAccounts
        panel.removeAllViews()
        if (!accountsOpen || accounts.isEmpty()) { panel.visibility = View.GONE; return }
        panel.visibility = View.VISIBLE
        panel.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(1), com.toolsboox.ot.LedgerTheme.accent(ctx))
            cornerRadius = dp(8).toFloat()
        }
        panel.setPadding(dp(2), dp(2), dp(2), dp(4))

        fun accountRow(label: String, selected: Boolean, onClick: () -> Unit) {
            panel.addView(TextView(ctx).apply {
                text = label; textSize = 14f
                setPadding(dp(24), dp(8), dp(10), dp(8))
                if (selected) {
                    // Same can't-miss-it treatment as the chips: solid black, white text.
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF000000.toInt()); cornerRadius = dp(8).toFloat()
                    }
                    setTextColor(0xFFFFFFFF.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                } else setTextColor(0xFF000000.toInt())
                setOnClickListener { onClick() }
            })
        }

        fun pick(id: String?) {
            accountFilter = id
            ctx.getSharedPreferences("ledger_mail_inbox", 0).edit()
                .putString("account_filter", id ?: "").apply()
            renderChips(); render()
        }
        accountRow("✉  All accounts", accountFilter == null) { pick(null) }
        for (a in accounts) accountRow("@  ${a.display}", accountFilter == a.id) { pick(a.id) }
    }

    private fun shown(): List<InboxMessage> {
        // A live search overrides the browse filters: server matches once they've landed, the
        // local layer until then. The almanac window and the To-dos/All split are suspended —
        // a search means "find it wherever it is", and hiding hits behind a parked date strip
        // would read as the search failing.
        if (searchActive()) return serverResults ?: localMatches()
        val ctx = requireContext()
        // The four positions of the view axis, on mail's own material. Unread/Read run off the same
        // read set that opening a message writes to, so the lens agrees with what the rows already
        // show — a list that says "unread" while displaying mail you've read reads as a bug even
        // when the filter is technically doing something defensible.
        var base = when (mailView) {
            VIEW_STARRED -> messages.filter { InboxStore.isStarred(ctx, it.id) }
            VIEW_UNREAD -> messages.filter { !InboxStore.isRead(ctx, it.id) }
            VIEW_READ -> messages.filter { InboxStore.isRead(ctx, it.id) }
            else -> messages
        }
        // Narrow to one account by ORIGIN (the acct:<id> prefix), not by display label — labels
        // get renamed; the id a message arrived through doesn't.
        accountFilter?.let { id -> base = base.filter { MailSync.accountId(it.id) == id } }
        return filterByWindow(base)
    }

    // --- The search layers.

    private fun searchActive() = searchQuery.isNotBlank()

    /** Case-insensitive match across everything a loaded message carries that reads as text. */
    private fun matches(m: InboxMessage, q: String): Boolean =
        m.subject.contains(q, ignoreCase = true) ||
            m.fromName.contains(q, ignoreCase = true) ||
            m.fromEmail.contains(q, ignoreCase = true) ||
            m.snippet.contains(q, ignoreCase = true) ||
            m.body.contains(q, ignoreCase = true)

    /** The instant layer: filter the loaded pool — [InboxStore.messages] is already the fetch
     *  window ∪ the starred pile — honoring only the account narrowing. */
    private fun localMatches(): List<InboxMessage> {
        var base = messages
        accountFilter?.let { id -> base = base.filter { MailSync.accountId(it.id) == id } }
        return base.filter { matches(it, searchQuery) }
    }

    private fun submitSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) { clearSearch(); return }
        searchQuery = q
        searchSeq += 1                      // an in-flight server search for the old query stands down
        serverResults = null
        serverSearching = false
        binding.mailSearchClear.visibility = View.VISIBLE
        hideKeyboard()
        render()
    }

    /** Back to the normal inbox: field, both layers, and any in-flight server search let go. */
    private fun clearSearch() {
        searchQuery = ""
        searchSeq += 1
        serverResults = null
        serverSearching = false
        binding.mailSearchField.setText("")
        binding.mailSearchClear.visibility = View.GONE
        hideKeyboard()
        render()
    }

    private fun hideKeyboard() {
        val imm = context?.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.mailSearchField.windowToken, 0)
    }

    /** The deep layer: IMAP UID SEARCH per account (the existing session scaffold; results come
     *  back through the size-probed bounded fetch, so they're real, openable, starrable messages).
     *  One broken account's error shows briefly; the others' results still land. */
    private fun runServerSearch() {
        val ctx = context ?: return
        val q = searchQuery
        if (q.isBlank() || serverSearching) return
        serverSearching = true
        val seq = searchSeq
        render()                            // redraw the 🔎 row as its progress state
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (results, errors) = withContext(Dispatchers.IO) {
                    MailSync.searchServer(ctx, q, accountFilter)
                }
                if (!isAdded || seq != searchSeq) return@launch   // cleared or re-submitted while away
                serverSearching = false
                serverResults = results
                render()
                if (errors.isNotEmpty()) toast(errors.first())
            } catch (e: Exception) {
                if (!isAdded || seq != searchSeq) return@launch
                serverSearching = false
                render()
                toast("Server search failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** True while the Almanac strip scopes the list (anything but day-on-today, the live view). */
    private fun navFiltered(): Boolean = !(navGranularity == "day" && navAnchor == LocalDate.now())

    /** The active window, named plainly for the chip and the empty state: "Week 30 · Jul 20–26". */
    private fun windowLabel(): String {
        val loc = Locale.getDefault()
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

    /** Keep only mail whose date falls in the navigator window; day+today is the live view (all). */
    private fun filterByWindow(list: List<InboxMessage>): List<InboxMessage> {
        if (navGranularity == "day" && navAnchor == LocalDate.now()) return list
        val (start, end) = navWindow()
        return list.filter {
            val d = java.time.Instant.ofEpochMilli(it.date)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            !d.isBefore(start) && d.isBefore(end)
        }
    }

    private fun stepByGranularity(date: LocalDate, dir: Int): LocalDate = when (navGranularity) {
        "week" -> date.plusWeeks(dir.toLong())
        "month" -> date.plusMonths(dir.toLong())
        "quarter" -> date.plusMonths(3L * dir)
        "year" -> date.plusYears(dir.toLong())
        else -> date.plusDays(dir.toLong())
    }

    /** [start, end) of the current window (granularity + anchor) — mirrors the feed's navWindow(). */
    private fun navWindow(): Pair<LocalDate, LocalDate> {
        val a = navAnchor
        return when (navGranularity) {
            "week" -> {
                val s = a.with(java.time.temporal.WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
                s to s.plusWeeks(1)
            }
            "month" -> { val s = a.withDayOfMonth(1); s to s.plusMonths(1) }
            "quarter" -> { val s = a.withDayOfMonth(1).withMonth((a.monthValue - 1) / 3 * 3 + 1); s to s.plusMonths(3) }
            "year" -> { val s = a.withDayOfYear(1); s to s.plusYears(1) }
            else -> a to a.plusDays(1)
        }
    }

    /** Redraw the Almanac strip for the current anchor (dots for filled days), like the feed does. */
    private fun renderNav() {
        val bar = navBar ?: return
        // The view's scope: this exists only to draw the strip, so back-navigation cancels it
        // instead of ghost-rendering into a dead view. (The writes below keep the fragment's
        // scope — a star or a filing must land even if the reader has already moved on.)
        viewLifecycleOwner.lifecycleScope.launch {
            val root = documentsRoot()
            val loc = Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, navAnchor, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        navAnchor.year, navAnchor.monthValue, navAnchor.dayOfMonth, startHour = null)
                cd to runCatching { calendarPatternService.load(root, navAnchor, loc) }.getOrNull()
            }
            // A missing/failed pattern must not kill the strip: render() with an empty pattern
            // rather than skip — an unrendered CalendarNavBarHost never sets its currentDay, and
            // a nav bar with no currentDay swallows every touch. That was the silent way the
            // date filter could "render once, then no-op" on a fresh year.
            val safePat = pat ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(navAnchor.year, loc).fill()
            if (isAdded) bar.render(day, safePat)
        }
    }

    /**
     * The Clear sweep, now with a held breath: it sweeps only what's actually ON SCREEN (the All
     * list as narrowed by account and window — never mail you weren't looking at), and the
     * snackbar's Undo puts the whole sweep back until it lapses.
     */
    private fun clearUnstarred() {
        val ctx = context ?: return
        // Keep-forever mail is never bulk-swept: starred, replied-to, OR sent all stay (the per-row
        // long-press Delete is the deliberate override for any single message). A sent row lives only
        // in its file-backed pile, so if it were swept into CLEARED nothing would ever un-clear it.
        val swept = shown().filter {
            !InboxStore.isStarred(ctx, it.id) && !InboxStore.isReplied(ctx, it.id) && !InboxStore.isSent(it.id)
        }.map { it.id }
        if (swept.isEmpty()) { toast("Nothing to clear — it's all kept"); return }
        InboxStore.clear(ctx, swept)
        messages = InboxStore.messages(ctx)
        render()
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, "Cleared ${swept.size} — starred mail kept",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction("Undo") {
            // The app context outlives the fragment; the sweep must be reversible even if the
            // reader has already wandered off this screen.
            InboxStore.restore(ctx.applicationContext, swept)
            if (isAdded) { messages = InboxStore.messages(ctx); render() }
        }.show()
    }

    /** Pull mail from the configured accounts, then reload the list. Surfaces the first problem gently. */
    private fun refresh() {
        if (refreshing) return
        val ctx = context ?: return
        if (!InboxStore.hasAccounts(ctx)) { render(); return }
        refreshing = true
        binding.mailStatus.text = "Fetching…"
        binding.mailStatus.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val problem = MailSync.refresh(ctx)      // suspends; fetch runs on Dispatchers.IO within
                if (!isAdded) return@launch
                messages = InboxStore.messages(ctx)
                binding.mailStatus.visibility = View.GONE
                render()
                if (problem != null) toast(problem.split("\n").first())
            } finally {
                // Also on cancellation — a wedged flag here would refuse every future refresh.
                refreshing = false
            }
        }
    }

    private fun render() {
        val ctx = context ?: return
        val container = binding.mailContainer
        container.removeAllViews()

        val list = shown()
        val searching = searchActive()

        // The subtle count line over search results — which layer answered, and how widely.
        if (searching && list.isNotEmpty()) container.addView(TextView(ctx).apply {
            val noun = if (list.size == 1) "match" else "matches"
            text = if (serverResults != null) "${list.size} $noun on the server for \"$searchQuery\""
            else "${list.size} $noun in loaded mail"
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(dp(4), 0, dp(4), dp(8))
        })

        if (list.isEmpty()) {
            container.addView(TextView(ctx).apply {
                // The empty pane names the filter that emptied it — a filtered-empty list that
                // just says "empty" reads as broken.
                text = when {
                    searching && serverResults != null ->
                        "The server found nothing for \"$searchQuery\". Tap ✕ to go back to the inbox."
                    searching -> "No matches in loaded mail for \"$searchQuery\"."
                    navFiltered() -> "No mail in ${windowLabel()}. Tap ✕ on the date chip for the live inbox."
                    accountFilter != null -> "No mail from this account yet. Tap the ▾ chip for all accounts."
                    // Each lens names ITSELF when it comes up empty. The pane already argued that a
                    // filtered-empty list saying only "empty" reads as broken — and two new lenses
                    // that fell through to the bare "Inbox empty." would have re-created exactly
                    // that, with the added cruelty that an empty Unread is usually GOOD news.
                    mailView == VIEW_STARRED ->
                        "No starred mail yet. Open a message and star it to keep it, or switch to All."
                    mailView == VIEW_UNREAD ->
                        "Nothing unread — you're caught up. Tap ✉ All to see everything that's come in."
                    mailView == VIEW_READ ->
                        "Nothing read yet in this window. Tap ✉ All to see everything that's come in."
                    InboxStore.hasAccounts(ctx) -> "Inbox empty."
                    else -> "No accounts yet. Tap the gear to add one — until then a few samples show here."
                }
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(dp(8), dp(24), dp(8), 0)
            })
        } else for (m in list) container.addView(row(m))

        // Under the local matches (even zero of them): the door to the deep layer. Gone once the
        // server has answered — the results ARE the server's then, until ✕ clears the search.
        if (searching && serverResults == null && InboxStore.hasAccounts(ctx)) {
            container.addView(TextView(ctx).apply {
                text = if (serverSearching) "🔎 Searching the server…"
                else "🔎 Search the server for \"$searchQuery\""
                textSize = 15f
                setTextColor(if (serverSearching) 0xFF888888.toInt() else 0xFF2F6F96.toInt())
                setPadding(dp(8), dp(16), dp(8), dp(16))
                if (!serverSearching) setOnClickListener { runServerSearch() }
            })
        }
        // The Clear sweep lives in the header now (🧹, visible in All), like the feed's Clear.
    }

    private fun row(m: InboxMessage): View {
        val ctx = requireContext()
        val unread = !InboxStore.isRead(ctx, m.id)
        val starred = InboxStore.isStarred(ctx, m.id)

        // Unread must survive monochrome: the old 5%-gray row tint was invisible on a Boox
        // panel, so unread is bold text PLUS a solid black dot on the sender line — a signal
        // with actual contrast. The tint is gone rather than darkened; a gray wash behind
        // body text is exactly what e-ink dithers into mud.
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }

        // The star rides the RIGHT edge, same as every feed row: one edge, one gesture, everywhere
        // — the star is how anything (article or email) graduates out of the passing stream, so
        // the thumb should never have to hunt for it. Big and black-on-white for e-ink.
        val star = TextView(ctx).apply {
            text = if (starred) "★" else "☆"
            textSize = 24f; setTextColor(if (starred) 0xFF000000.toInt() else 0xFF777777.toInt())
            setPadding(dp(14), 0, dp(4), 0); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (starred) unstar(m) else starToTodo(m) }
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(ctx).apply {
            text = (if (unread) "●  " else "") + m.fromName.ifBlank { m.fromEmail }
            textSize = 14f; setTextColor(0xFF000000.toInt())
            if (unread) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(ctx).apply {
            text = rel(m.date); textSize = 11f; setTextColor(0xFF888888.toInt())
        })
        col.addView(head)
        col.addView(TextView(ctx).apply {
            text = m.subject; textSize = 14f; setTextColor(0xFF000000.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            if (unread) setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(ctx).apply {
            text = m.snippet; textSize = 12f; setTextColor(0xFF666666.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(col)
        card.addView(star)

        card.setOnClickListener { InboxStore.markRead(ctx, m.id); openMessage(m) }
        // Long-press a row → Delete: sweeps this one message out of the inbox for good (unlike the
        // header 🧹 which only sweeps the unstarred pile, delete works on ANY row, starred included).
        card.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Delete this email?")
                .setMessage(m.subject.ifBlank { m.fromName.ifBlank { m.fromEmail } })
                .setPositiveButton("Delete") { _, _ ->
                    InboxStore.clear(ctx, listOf(m.id))
                    messages = InboxStore.messages(ctx)
                    render()
                    toast("Deleted")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        return card
    }

    /** An opened message: its body, and the moves that make it an assignable object. */
    private fun openMessage(m: InboxMessage) {
        val ctx = requireContext()
        lateinit var dialog: androidx.appcompat.app.AlertDialog   // referenced by the action rows below
        var cur = m   // the message as shown; the on-demand full fetch swaps the whole body in here
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        col.addView(TextView(ctx).apply {
            text = m.subject; textSize = 17f; setTextColor(0xFF000000.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(2), 0, dp(4))
        })
        col.addView(TextView(ctx).apply {
            text = listOf(m.fromName, m.fromEmail).filter { it.isNotBlank() }.joinToString("  ·  ")
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(8))
        })
        // Never a blank pane: a message whose MIME walk produced nothing readable still says
        // what it is (the list snippet if we caught one, else plain words) — a silent white
        // rectangle reads as the app failing, not the message being image-only.
        fun bodyText(msg: InboxMessage) = msg.body.ifBlank {
            msg.snippet.ifBlank { "(No readable text in this message — it may be images or attachments only.)" }
        }
        val bodyView = TextView(ctx).apply {
            text = bodyText(m)
            textSize = 15f; setTextColor(0xFF000000.toInt()); setTextIsSelectable(true)
        }

        // HOW TO READ IT — only a question when the sender actually sent markup.
        //
        // A designed letter used to arrive as a wall of run-together words, because the MIME walk
        // stripped the html and threw it away. It's kept now, so there are three readings: Text
        // (the stripped body, which is what every other surface uses), Reader (the markup reflowed
        // to a plain, high-contrast e-ink column) and As sent (the sender's own layout). Text stays
        // the default — on e-ink the reflowed or original layouts are a choice, not an upgrade.
        val web = if (cur.html.isNotBlank()) android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = false          // mail has no business running any
            settings.loadsImagesAutomatically = false   // see the images row below
            settings.blockNetworkImage = true
            setBackgroundColor(0xFFFFFFFF.toInt())
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (420 * resources.displayMetrics.density).toInt())
            visibility = View.GONE
        } else null

        if (web != null) {
            var mode = 0            // 0 = text, 1 = reader, 2 = as sent
            var imagesOn = false
            lateinit var modeRow: LinearLayout
            lateinit var imagesNote: TextView

            fun renderWeb() {
                val html = if (mode == 1) MailReaderHtml.reader(cur.html) else MailReaderHtml.asSent(cur.html)
                // Remote fetches are defused in the MARKUP, not just by the WebView setting: a
                // tracking pixel's whole payload is the REQUEST, and by the time a setting could be
                // undone the request is already gone. `data:` images are left alone — they came
                // with the letter and cost nothing.
                val safe = if (imagesOn) html else MailReaderHtml.blockRemote(html)
                web.settings.blockNetworkImage = !imagesOn
                web.settings.loadsImagesAutomatically = imagesOn
                web.loadDataWithBaseURL(null, safe, "text/html", "UTF-8", null)
            }
            fun applyMode() {
                bodyView.visibility = if (mode == 0) View.VISIBLE else View.GONE
                web.visibility = if (mode == 0) View.GONE else View.VISIBLE
                imagesNote.visibility =
                    if (mode != 0 && !imagesOn && MailReaderHtml.hasRemoteRefs(cur.html)) View.VISIBLE else View.GONE
                for (i in 0 until modeRow.childCount) {
                    (modeRow.getChildAt(i) as TextView).setTypeface(
                        null, if (i == mode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
                if (mode != 0) renderWeb()
            }

            modeRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
                listOf("Text", "Reader", "As sent").forEachIndexed { i, label ->
                    addView(TextView(ctx).apply {
                        text = label; textSize = 13f; setTextColor(0xFF2F6F96.toInt())
                        setPadding(0, 0, dp(18), 0)
                        setOnClickListener { mode = i; applyMode() }
                    })
                }
            }
            imagesNote = TextView(ctx).apply {
                text = "🖼  Images not loaded — tap to load"
                textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(6))
                visibility = View.GONE
                setOnClickListener { imagesOn = true; applyMode() }
            }
            col.addView(modeRow)
            col.addView(imagesNote)
            col.addView(web)
            applyMode()
        }

        col.addView(bodyView)
        if (m.truncated) col.addView(TextView(ctx).apply {
            // The fetch was bounded on purpose (the message is large — usually attachments);
            // say so, or a cut-off body reads as the parser failing. The note is also the door
            // to the rest: tap it and THIS one message is fetched whole, replaced in place —
            // plain text-swaps only, single clean redraws, as e-ink wants.
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(10), 0, 0)
            val note = this
            fun loadRest() {
                note.setOnClickListener(null)          // one fetch at a time; no double-taps
                note.text = "✂ Loading the rest…"
                lifecycleScope.launch {
                    try {
                        // Lands in InboxStore (fetch window + starred pile) inside fetchFull, so
                        // the full body sticks even if the reader has moved on by the time it comes.
                        val full = withContext(Dispatchers.IO) { MailSync.fetchFull(ctx, cur) }
                        cur = full
                        bodyView.text = bodyText(full)
                        col.removeView(note)           // whole now — the scissors' job is done
                        // Server-search results live outside InboxStore's window; swap the full
                        // body into that layer too or its re-render would show the stale slice.
                        serverResults = serverResults?.map { if (it.id == full.id) full else it }
                        if (isAdded) { messages = InboxStore.messages(ctx); render() }
                    } catch (e: MailMessageTooLarge) {
                        note.text = "✂ ${e.message}"   // final: no retry — it can never fit
                    } catch (e: Exception) {
                        note.text = "✂ Couldn't load the rest (${e.message ?: "fetch failed"}) — tap to retry."
                        note.setOnClickListener { loadRest() }
                    }
                }
            }
            if (m.uid != null && MailSync.accountId(m.id) != null) {
                text = "✂ Large message — tap to load the rest (attachments stay on the server)."
                setOnClickListener { loadRest() }
            } else {
                // No server id to fetch by (a starred mail kept from before UIDs persisted, or a
                // probe that never parsed one): the honest, un-tappable note.
                text = "✂ Large message — only the beginning was fetched (attachments stay on the server)."
            }
        })

        // The moves that let a message join the knowledge graph like any other Ledger object, mirroring
        // iOS MessageDetailView. AlertDialog only has three buttons (Close / star / Reply), so these ride
        // as tappable rows under the body -- the same plain-TextView idiom as "Clear un-starred".
        fun action(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, dp(14), 0, dp(2))
            setOnClickListener { onClick() }
        }
        // ⁂ is THE connect glyph on Android (Missed Rhizomes / Quick Wins / Daily Pile agree).
        // Every move takes `cur`, not `m` — after a load-the-rest, what stars / files / connects
        // must be the whole message, not the stale slice.
        // Until now an email could only be tagged by typing a # inside an annotation — writing a
        // note you didn't want in order to file mail you did.
        col.addView(action("🏷  Tag…") {
            com.toolsboox.ot.TagPicker.show(
                ctx, "email://${cur.id}", cur.subject, showModal = { showModal(it) })
        })
        col.addView(action("⁂  Rhizome — connect & open graph") { dialog.dismiss(); openRhizome(cur) })
        col.addView(action("🧩  Assign to synthesis") { dialog.dismiss(); assignToSynthesis(cur) })
        // 🖍 The universal annotation menu, on mail like on everything: text selected in the body
        // rides as the highlight (select-then-annotate, the reader's move); nothing selected means
        // "annotate the whole message". captureAnnotation already carries note / gram / photo /
        // voice AND the Ask-about-this + Educate-me rows, so mail gets the full menu for free.
        // The dialog stays open — like the reader, you annotate and you're still on the thing.
        col.addView(action("🖍  Annotate…") {
            val selection = if (bodyView.hasSelection()) {
                val a = bodyView.selectionStart.coerceAtLeast(0)
                val b = bodyView.selectionEnd.coerceAtLeast(0)
                bodyView.text.subSequence(minOf(a, b), maxOf(a, b)).toString().trim()
            } else ""
            captureAnnotation(selection, cur.subject.ifBlank { null }) { sel, note, attachment ->
                logMailAnnotation(cur, sel, note, attachment)
            }
        })
        // TODO(roots): a "rhymes / roots" action would open the semantic-roots surface seeded from this
        // message, but R.id.action_to_ledger_roots -> LedgerRootsFragment takes NO arguments (confirmed:
        // every call site navigates it bare) and shows the global roots view -- there is no seed hook. Per
        // the brief, skipping rather than inventing an entry point; wire it here if LedgerRootsFragment
        // ever gains a seed/URI argument.

        val canReply = MailSync.accountId(m.id) != null
        val builder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton(if (InboxStore.isStarred(ctx, m.id)) "Un-star" else "★ Star") { _, _ ->
                if (InboxStore.isStarred(ctx, m.id)) unstar(cur) else starToTodo(cur)
            }
        if (canReply) builder.setNegativeButton("Reply…") { _, _ -> showReply(cur) }
        dialog = builder.create()
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Join this message into the connection graph and open its rhizome -- the same move every other
     * Ledger object makes (mirrors QuickWinsFragment.openRhizome / DailyPileFragment.rhizome). The
     * message rides a stable `mail://<id>` URI, so any edge drawn on the rhizome surface sticks to it.
     */
    private fun openRhizome(m: InboxMessage) {
        NavHostFragment.findNavController(this).navigate(
            R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                com.toolsboox.plugin.calendar.ui.LedgerRhizomeFragment.ARG_URI to "mail://${m.id}",
                com.toolsboox.plugin.calendar.ui.LedgerRhizomeFragment.ARG_LABEL
                    to m.subject.ifBlank { m.fromName }.take(60)
            )
        )
    }

    /**
     * File the message onto today's synthesis pile (its subject + a body snippet), and drop a provenance
     * edge from its `mail://<id>` URI to today's Synthesize page so it shows up in the graph. Mirrors the
     * SynthesisIdeaStore.add usage in ReaderFragment / CalendarDayFragment; IO runs off the main thread.
     */
    private fun assignToSynthesis(m: InboxMessage) {
        val ctx = requireContext()
        val uri = "mail://${m.id}"
        val label = m.subject.ifBlank { m.fromName.ifBlank { m.fromEmail } }.take(60)
        val snippet = m.body.trim().replace(Regex("\\s+"), " ").take(280)
        val line = listOf(m.subject.trim(), snippet).filter { it.isNotBlank() }.joinToString(" — ")
        if (line.isBlank()) { toast("Nothing to file"); return }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(ctx, today, listOf(line), "note", label)
                com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                    ctx, uri, com.toolsboox.ot.LedgerUri.page(today.toString(), "synthesize"),
                    kind = com.toolsboox.plugin.calendar.da.v2.Connection.PLACED,
                    fromLabel = label, toLabel = "Synthesize · $today"
                )
            }
            if (!isAdded) return@launch
            toast("Filed to today's synthesis")
        }
    }

    /**
     * Append a mail annotation (highlighted passage, note, and/or media) to today's CalendarDay —
     * the exact move ReaderFragment.logHighlight makes for a book, with mail provenance instead:
     * a [com.toolsboox.plugin.calendar.da.v2.ReadingEvent] whose title is the subject, source the
     * sender, and url the message's `mail://` address (the same address its rhizome edges use).
     *
     * Kind stays [ReadingEvent.Kind.ARTICLE] rather than a new MAIL value: the enum is
     * wire-compatible with iOS and older builds, and a Moshi enum adapter fails the WHOLE day
     * JSON on an unknown name — the `mail://` url is the honest discriminator instead.
     */
    private fun logMailAnnotation(
        m: InboxMessage, text: String?, note: String?, attachment: com.toolsboox.da.Attachment?
    ) {
        // The fragment's own scope, not the view's — the write must land even if the reader has
        // already closed the message and moved on (the renderNav comment's same distinction).
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = documentsRoot()
                val today = LocalDate.now()
                // Same per-day serialization as the star path — this is one more background
                // whole-file load→mutate→save racing the open day page's per-pen-up save.
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    val day = calendarDayService.load(root, today, null, Locale.getDefault())
                    day.readingEvents.add(
                        com.toolsboox.plugin.calendar.da.v2.ReadingEvent(
                            id = "mailann-${UUID.randomUUID()}",
                            kind = com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.ARTICLE,
                            date = Date(),
                            title = m.subject.ifBlank { m.fromName.ifBlank { m.fromEmail } },
                            source = m.fromName.ifBlank { m.fromEmail }.ifBlank { null },
                            url = "mail://${m.id}",
                            excerpt = text?.ifBlank { null },
                            note = note?.ifBlank { null },
                            attachments = attachment?.let { mutableListOf(it) }
                        )
                    )
                    calendarDayService.save(root, today, day)
                }
            }.onFailure { timber.log.Timber.w(it, "failed to log mail annotation") }
        }
    }

    /** A plain reply, sent out the account the message arrived on (via SMTP). */
    private fun showReply(m: InboxMessage) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Write a reply…"; setSingleLine(false); minLines = 6; gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(TextView(ctx).apply {
                text = "To ${m.fromName.ifBlank { m.fromEmail }}\nRe: ${m.subject}"
                textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(8))
            })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { toast("Nothing to send"); return@setPositiveButton }
                lifecycleScope.launch {
                    val err = try { withContext(Dispatchers.IO) { MailSync.sendReply(ctx, text, m) }; null }
                    catch (e: Exception) { e.message ?: "Send failed" }
                    // A sent reply is a keep-forever event: the mail you answered persists its body and
                    // never prunes, exactly like a star. Recorded off the main thread (it writes a file).
                    // The reply text you wrote also persists to its own sent pile, so your outgoing
                    // words are kept forever and searchable alongside the mail you received.
                    if (err == null) withContext(Dispatchers.IO) {
                        InboxStore.markReplied(ctx.applicationContext, m.id)
                        InboxStore.recordSent(ctx.applicationContext, m, text)
                    }
                    if (!isAdded) return@launch          // send outlives the fragment; toast needs it attached
                    if (err == null) messages = InboxStore.messages(ctx)   // reflect the new keep-forever row
                    toast(if (err == null) "Reply sent" else "Send failed: $err")
                    if (err == null) render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Star -> to-do: create a task on today's page (like Kanban's add-card), and mark the mail starred.
    // Grams for stars, mail edition: the same star ALSO mints a link-face gram onto today's Intake.
    private fun starToTodo(m: InboxMessage) {
        val ctx = requireContext()
        val item = LedgerItem(
            id = "li-" + UUID.randomUUID().toString().lowercase(),
            kind = LedgerItem.Kind.TASK, text = m.subject, date = Date(), stage = "todo", source = "email"
        )
        lifecycleScope.launch {
            val placedGram = withContext(Dispatchers.IO) {
                InboxStore.setStarred(ctx, m, true)   // persists the message content; off the main thread
                val root = documentsRoot()
                val today = LocalDate.now()
                // The open day page's per-pen-up save does the same whole-file load→mutate→save;
                // unserialized, one of the two writes silently drops the other's items.
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    val day = calendarDayService.load(root, today, null, Locale.getDefault())
                    day.ledgerItems.add(item)
                    calendarDayService.save(root, today, day)
                }
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, item) }
                runCatching { placeMailStarGram(root, m, today) }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            toast(if (placedGram) "★ → Star Sort" else "Starred")
            messages = InboxStore.messages(ctx)
            render()
        }
    }

    /**
     * Grams for stars, mail edition — mirrors FeedNoteGram.placeStarGram: starring a message mints
     * a movable link-face gram onto TODAY's Intake page, so the mail sits on the board like a
     * starred article does. The face is rendered by [LinkCardRenderer] wearing the ✉ MAIL chip,
     * the subject as its title, and "sender · date" on the source line (the render's url stays blank —
     * a `mail://` address is no host to print; the address rides sourceLink instead, so tapping
     * the gram can resolve the message and dedupe works).
     *
     * Starring the same message twice must not stack twins: an intake gram already carrying this
     * sourceLink today wins. And unstarring deliberately does NOT remove the gram — once placed,
     * the Intake page is his board, not a mirror of the star state. Call OFF the main thread.
     *
     * @return true when a gram was placed, false when today's intake already had it.
     */
    private fun placeMailStarGram(root: java.io.File, m: InboxMessage, today: LocalDate): Boolean {
        val mailUri = "mail://${m.id}"
        // Dedupe by sourceLink. Read-before-place is unlocked, but stars arrive at human speed
        // and re-stars route through this same path, so a stale read can't stack twins in practice.
        val day = calendarDayService.load(root, today, null, Locale.getDefault())
        if (day.imageElements.any { it.page == "intake" && it.sourceLink == mailUri }) return false
        val sender = m.fromName.ifBlank { m.fromEmail }.ifBlank { "Unknown sender" }
        val dateLabel = java.time.Instant.ofEpochMilli(m.date)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
            url = "", title = m.subject.ifBlank { "(no subject)" }, kind = "mail",
            sourceName = "$sender · $dateLabel"
        )
        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
            calendarDayService, root, face, today, pageKey = "intake",
            sourceLink = mailUri, sourceLabel = sender, cardText = m.subject,
            // A starred email belongs to the EMAIL quarter of Star Sort — whose storage key is the
            // legacy "educate". Without this the gram carried NO intakeKind, matched no quarter, and
            // landed at the default spot, i.e. on top of THE READ.
            intakeKind = "educate"
        )
        return true
    }

    private fun unstar(m: InboxMessage) {
        val ctx = requireContext()
        InboxStore.setStarred(ctx, m, false)
        messages = InboxStore.messages(ctx)
        render()
    }

    // Mail settings -- accounts list + per-account editor.

    private fun showAccountsList() {
        val ctx = requireContext()
        val accounts = MailAccountStore.all(ctx)
        val labels = (accounts.map { it.display } + "➕  Add account").toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Mail accounts")
            .setItems(labels) { _, i ->
                if (i < accounts.size) showAccountEditor(accounts[i]) else showAccountEditor(MailAccount())
            }
            .setNegativeButton("Close", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showAccountEditor(account: MailAccount) {
        val ctx = requireContext()
        val a = account.copy()
        val exists = MailAccountStore.all(ctx).any { it.id == a.id }

        fun label(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(10), 0, dp(2))
        }
        fun field(value: String, numeric: Boolean = false) = EditText(ctx).apply {
            setText(value); setSingleLine()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        fun check(text: String, on: Boolean) = CheckBox(ctx).apply { this.text = text; isChecked = on }

        val displayName = field(a.displayName)
        val email = field(a.email).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val imapHost = field(a.imapHost)
        val imapPort = field(a.imapPort.toString(), numeric = true)
        val imapSSL = check("Use SSL/TLS (IMAP)", a.imapSSL)
        val username = field(a.username)
        val password = field(MailAccountStore.password(ctx, a.id)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val smtpHost = field(a.smtpHost)
        val smtpPort = field(a.smtpPort.toString(), numeric = true)
        val smtpSSL = check("Use SSL/TLS (SMTP)", a.smtpSSL)

        val preset = TextView(ctx).apply {
            text = "✨  Fill hosts from provider…"; textSize = 14f; setTextColor(0xFF2F6F96.toInt())
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener {
                val names = MailAccount.presets.map { it.first }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                    .setTitle("Provider")
                    .setItems(names) { _, i ->
                        val p = MailAccount.presets[i]
                        imapHost.setText(p.second); smtpHost.setText(p.third)
                        imapPort.setText("993"); smtpPort.setText("465")
                        imapSSL.isChecked = true; smtpSSL.isChecked = true
                    }
                    .show()
            }
        }

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(4))
            addView(label("Display name")); addView(displayName)
            addView(label("Email address")); addView(email)
            addView(preset)
            addView(label("Incoming — IMAP host")); addView(imapHost)
            addView(label("IMAP port")); addView(imapPort); addView(imapSSL)
            addView(label("Username (default: the address)")); addView(username)
            addView(label("Password (app-specific if 2FA)")); addView(password)
            addView(label("Outgoing — SMTP host")); addView(smtpHost)
            addView(label("SMTP port (implicit TLS, usually 465)")); addView(smtpPort); addView(smtpSSL)
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (exists) "Edit account" else "New account")
            .setView(ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                a.displayName = displayName.text.toString().trim()
                a.email = email.text.toString().trim()
                a.imapHost = imapHost.text.toString().trim()
                a.imapPort = imapPort.text.toString().toIntOrNull() ?: 993
                a.imapSSL = imapSSL.isChecked
                a.username = username.text.toString().trim()
                a.smtpHost = smtpHost.text.toString().trim()
                a.smtpPort = smtpPort.text.toString().toIntOrNull() ?: 465
                a.smtpSSL = smtpSSL.isChecked
                if (a.email.isBlank() || a.imapHost.isBlank()) { toast("An email address and IMAP host are required"); return@setPositiveButton }
                MailAccountStore.upsert(ctx, a)
                MailAccountStore.setPassword(ctx, a.id, password.text.toString())
                toast("Saved")
                renderChips()   // a first/renamed account changes the account-filter chip
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (exists) builder.setNeutralButton("Delete") { _, _ ->
            MailAccountStore.delete(ctx, a.id); toast("Account deleted")
            if (accountFilter == a.id) {
                // Never leave the list narrowed to an account that no longer exists.
                accountFilter = null
                ctx.getSharedPreferences("ledger_mail_inbox", 0).edit().putString("account_filter", "").apply()
            }
            messages = InboxStore.messages(ctx); renderChips(); render()
        }
        val dialog = builder.create()
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rel(millis: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()

    // ScreenFragment abstract members: this screen manages its own inline status, so these are no-ops
    // (the same stubs CorrespondenceFragment uses).
    override fun showLoading() {}
    override fun hideLoading() {}
}
