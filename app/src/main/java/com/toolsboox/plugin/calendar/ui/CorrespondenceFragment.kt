package com.toolsboox.plugin.calendar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentCorrespondenceBinding
import com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge
import com.toolsboox.plugin.calendar.nw.LedgerCorrespondence
import com.toolsboox.plugin.calendar.nw.LedgerPost
import com.toolsboox.plugin.calendar.nw.LedgerReply
import com.toolsboox.plugin.calendar.nw.LedgerSite
import com.toolsboox.plugin.calendar.nw.SiteStore
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import com.toolsboox.ot.InkPadView

/**
 * The Correspondence page — what passed between you and other people. Replies to your community
 * posts and your Ledgr board cards, grouped BY EXCHANGE (thread), newest first. A community
 * thread takes a handwritten reply: "✍ Reply in ink" opens a white card you write on with the
 * stylus; Done posts the ink as your comment via the bridge. Mirrors iOS CorrespondenceView.
 * Records exchanges; shows no unread counts, ever.
 *
 * MULTI-SITE (Michael: "Need access to multiple Fluent accounts at once"): keyed on SITE the way
 * Mail's inbox is keyed on account — the 🌐 switcher at the top. "All sites" fans BOTH tabs out
 * across every configured site in parallel (each row tagged with where it came from); a pick
 * narrows to one. READS aggregate; WRITES never do — a reply, like or gram always targets the
 * ONE site its row came from (the row-tap focuses that site first, the PostsBrowser/SiteBoards
 * seam), and the compose surface names it, so nothing ever lands on the wrong community silently.
 */
@AndroidEntryPoint
class CorrespondenceFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_correspondence
    private lateinit var binding: FragmentCorrespondenceBinding

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    // --- The almanac strip as THIS PAGE'S DATE FILTER -----------------------------------------
    //
    // Correspondence is a planner page, so the strip belongs above it — but it was not among the
    // surfaces that filter, so its slots would have fallen through to "go to that almanac page".
    // Tapping Jul over your replies taking you to the month planner is what Michael was reporting.
    // Mail and the feed already made this move (over there the strip IS the date filter), so this
    // is the third surface to join a rule, not a new idea.
    //
    // EVERY LEVEL FILTERS, INCLUDING DAY — unlike a notes surface, where the Day slot means "go to
    // the day page". There is no day-page version of your replies to go to, so Day here can only
    // mean "the ones that landed that day", which is also what it means on mail and feeds.
    private var navBar: CalendarNavBarHost? = null
    // Opening on YEAR, matching iOS's `correspondenceScope`. The pile is small and slow-moving —
    // a conversation can go a fortnight between turns — so a page that opened on today would open
    // empty for most of the week and read as broken before you had touched anything.
    private var navGranularity = "year"
    private var navAnchor: java.time.LocalDate = java.time.LocalDate.now()

    /**
     * `[start, end)` of the window the strip is pointing at — the twin of the mailbox's and the
     * feed's `navWindow()`.
     */
    private fun navWindow(): Pair<java.time.LocalDate, java.time.LocalDate> {
        val a = navAnchor
        return when (navGranularity) {
            "week" -> {
                val s = a.with(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).dayOfWeek(), 1)
                s to s.plusWeeks(1)
            }
            "month" -> { val s = a.withDayOfMonth(1); s to s.plusMonths(1) }
            "quarter" -> { val s = a.withDayOfMonth(1).withMonth((a.monthValue - 1) / 3 * 3 + 1); s to s.plusMonths(3) }
            "year" -> { val s = a.withDayOfYear(1); s to s.plusYears(1) }
            else -> a to a.plusDays(1)
        }
    }

    /** One step of the active granularity — the carets walk the WINDOW's unit, not always a day. */
    private fun stepByGranularity(date: java.time.LocalDate, dir: Int): java.time.LocalDate =
        when (navGranularity) {
            "week" -> date.plusWeeks(dir.toLong())
            "month" -> date.plusMonths(dir.toLong())
            "quarter" -> date.plusMonths(3L * dir)
            "year" -> date.plusYears(dir.toLong())
            else -> date.plusDays(dir.toLong())
        }

    /**
     * Is this item inside the window?
     *
     * The bridge hands `created_at` down as the site wrote it ("2026-07-30 14:33:22", or the same
     * with a T), and every row on this page shows the first 16 characters of it verbatim. So the
     * filter reads the same first ten: whatever window a row appears to belong to by its own
     * printed date is the window it is filtered into, with no timezone shifted in between to make
     * a row disagree with the label beside it.
     *
     * An unreadable date PASSES. A reply we can't place is still correspondence, and dropping it
     * would make the filter quietly lossy in exactly the case nobody would think to check.
     */
    private fun inWindow(createdAt: String): Boolean {
        val d = runCatching { java.time.LocalDate.parse(createdAt.take(10)) }.getOrNull() ?: return true
        val (start, end) = navWindow()
        return !d.isBefore(start) && d.isBefore(end)
    }

    /** The window named the way the strip names it — for the "nothing here" line. */
    private fun windowLabel(): String = when (navGranularity) {
        "week" -> "week"
        "month" -> "month"
        "quarter" -> "quarter"
        "year" -> "year"
        else -> "day"
    }


    /** Stack two bitmaps vertically (either may be null) — the attached item above your ink. */
    private fun stackVertically(top: Bitmap?, bottom: Bitmap?): Bitmap? {
        if (top == null) return bottom
        if (bottom == null) return top
        val w = maxOf(top.width, bottom.width)
        val gap = (8 * resources.displayMetrics.density).toInt()
        val h = top.height + gap + bottom.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        c.drawBitmap(top, ((w - top.width) / 2f), 0f, null)
        c.drawBitmap(bottom, ((w - bottom.width) / 2f), (top.height + gap).toFloat(), null)
        return out
    }

    /** "replies" = the exchange inbox · "community" = a space's posts you can reply to. */
    private var mode = "replies"
    private var spaceId = 25L                 // MichaelFilter — the first space
    private var spaceTitle = "MichaelFilter"

    // --- Multi-site ---------------------------------------------------------------------------
    //
    // "Fluent accounts" here = the configured sites in [SiteStore] — one WordPress login per site
    // powers its whole Fluent suite, so the account IS the site. null filter = All sites (both
    // tabs aggregate, rows tagged with their site); an id narrows to one. Persisted in this
    // page's own prefs through the shared [SiteFilterState], exactly like the posts browser and
    // site-boards keep theirs — one holder, three surfaces, so the pattern can't drift.
    private val siteState = SiteFilterState("ledger_correspondence")

    // The last-fetched rows, kept so fold/unfold of the switcher redraws without a refetch.
    // A null site on a row = the single-site path (no tag to draw).
    private var replyRows: List<Pair<LedgerSite?, LedgerReply>> = emptyList()
    private var communityRows: List<Pair<LedgerSite?, LedgerPost>> = emptyList()

    private fun allSites() = SiteStore.all(requireContext())

    /** All-sites aggregation only exists once there is more than one site to aggregate. */
    private fun aggregate(): Boolean = context != null && siteState.filter == null && allSites().size > 1

    /**
     * Point every ACTIVE-site consumer (thread reader, replies, likes, grams) at [site] — the
     * seam the sister surfaces use: an aggregated row's tap activates ITS site first, then the
     * proven single-site code runs unchanged. Also swings this page's space choice to the site's
     * own, so "share as gram" from a reply lands in that site's space, never another site's.
     */
    private fun focusSite(site: LedgerSite?) {
        val ctx = context ?: return
        if (site == null) return
        if (SiteStore.activeId(ctx) != site.id) SiteStore.activate(ctx, site.id)
        val sid = spaceIdFor(site.id)
        if (sid > 0) {
            spaceId = sid
            spaceTitle = spaceTitleFor(site.id).ifBlank { "Space $sid" }
        }
    }

    // A community space id only means anything on its own site, so the choice is kept PER SITE
    // (`space_id_<siteId>`). The unqualified legacy keys predate multi-site; migrateSpacePrefs
    // stamps them onto whichever site they actually belonged to (the active one), once.
    private fun spaceIdFor(siteId: String): Long = prefs().getLong("space_id_$siteId", 0L)
    private fun spaceTitleFor(siteId: String): String = prefs().getString("space_title_$siteId", "") ?: ""
    private fun setSpaceFor(siteId: String, id: Long, title: String) =
        prefs().edit().putLong("space_id_$siteId", id).putString("space_title_$siteId", title).apply()

    private fun migrateSpacePrefs() {
        if (prefs().getBoolean("space_prefs_migrated", false)) return
        prefs().edit().putBoolean("space_prefs_migrated", true).apply()
        val active = SiteStore.active(requireContext()) ?: return
        if (spaceIdFor(active.id) == 0L) setSpaceFor(
            active.id,
            prefs().getLong("space_id", 25L),
            prefs().getString("space_title", "MichaelFilter") ?: "MichaelFilter"
        )
    }

    /** The shared 🌐 switcher (Mail's account-switcher idiom) — same block Posts and Boards wear. */
    private fun switcherBar(): View = SiteSwitcherBar.build(
        context = requireContext(),
        sites = allSites(),
        filter = siteState.filter,
        open = siteState.open,
        onToggleOpen = { open -> siteState.setOpen(requireContext(), open); renderCurrent() },
        onPick = { id ->
            siteState.setFilter(requireContext(), id)
            load()
        },
        onManage = { SitesSettingsDialog.show(requireContext()) { load() } },
    )

    /** Redraw the current tab from the cached rows (fold/unfold, no refetch). */
    private fun renderCurrent() {
        if (mode == "community") renderCommunity(communityRows) else render(replyRows)
    }

    /** "· 🌐 site" for compose surfaces — naming the write target whenever there is more than one
     *  place a write COULD go. Blank with a single site: nothing to disambiguate. */
    private fun activeSiteLabel(): String {
        val ctx = context ?: return ""
        if (SiteStore.all(ctx).size < 2) return ""
        return SiteStore.active(ctx)?.display ?: ""
    }

    // Thread reader state — so a reply posted from inside it can reopen it fresh.
    private var threadReaderOpen = false
    private var threadReaderDialog: androidx.appcompat.app.AlertDialog? = null

    private fun prefs() = requireContext().getSharedPreferences("ledger_correspondence", Context.MODE_PRIVATE)

    /** Decode HTML entities (&hellip; &#039; &amp; …) so excerpts read cleanly. */
    private fun deHtml(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

    /** Rendered HTML with formatting KEPT (bold/italic/links/paragraphs) — for post bodies.
     *  (img tags render as nothing here; the featured image is shown separately.) */
    private fun richHtml(s: String): CharSequence =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).trim()

    /** Add an inline image view to [container] and load [url] into it off-thread. */
    private fun addImage(container: LinearLayout, url: String, heightDp: Int = 160) {
        val ctx = container.context
        val dp = resources.displayMetrics.density
        val img = android.widget.ImageView(ctx).apply {
            adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
            maxHeight = (heightDp * dp).toInt()
        }
        container.addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx, img))
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
            if (bmp != null && isAdded) {
                img.setImageBitmap(bmp)
                com.toolsboox.ot.ImageZoom.makeTappable(img)
            }
        }
    }

    /** Locally-tracked "you replied to this thread" set — a ✓ marker without a server round-trip. */
    /**
     * Clearing an item out of the inbox.
     *
     * Michael: "need to be able to delete correspondence", and from the handwritten list, "Need the
     * ability to delete low quality or uninteresting correspondence." These are other people's posts
     * on a community site, so DELETE is not ours to do — what is ours is to stop showing it. This is
     * a local dismiss, kept per (source, id) in the same prefs that remember what you've replied to,
     * and the menu says "Hide" rather than "Delete" so it never implies something happened on the
     * server that didn't. Own replies keep their real Delete, which does reach the server.
     */
    private fun hiddenKey(source: String, id: String) = "hidden_${source}_$id"

    // Post/comment ids collide across sites (each Fluent install counts from 1), so the marker
    // keys carry the site id when one is known. Reads also accept the old unqualified key, so a
    // hide or ✓ made before multi-site keeps holding.
    private fun qualify(siteId: String, id: String) = if (siteId.isBlank()) id else "${siteId}_$id"

    private fun isHidden(source: String, id: String, siteId: String = "") =
        prefs().getBoolean(hiddenKey(source, qualify(siteId, id)), false) ||
            prefs().getBoolean(hiddenKey(source, id), false)

    private fun hide(source: String, id: String, siteId: String = "") =
        prefs().edit().putBoolean(hiddenKey(source, qualify(siteId, id)), true).apply()

    private fun unhideAll() =
        prefs().all.keys.filter { it.startsWith("hidden_") }
            .let { keys -> prefs().edit().apply { keys.forEach { remove(it) } }.apply() }

    /** Long-press anywhere on a card offers to clear it (and to bring everything back). */
    private fun wireHide(card: View, source: String, id: String, what: String, siteId: String = "") {
        card.setOnLongClickListener {
            val ctx = context ?: return@setOnLongClickListener false
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Hide this?")
                .setMessage(what.take(160).ifBlank { "This item stops showing in your correspondence." })
                .setPositiveButton("Hide") { _, _ -> hide(source, id, siteId); load() }
                .setNeutralButton("Show hidden again") { _, _ -> unhideAll(); load() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun markReplied(source: String, id: Long, siteId: String = "") {
        val set = prefs().getStringSet("repliedThreads", emptySet())!!.toMutableSet()
        set.add(if (siteId.isBlank()) "$source-$id" else "$siteId-$source-$id")
        prefs().edit().putStringSet("repliedThreads", set).apply()
    }
    private fun hasReplied(source: String, id: Long, siteId: String = ""): Boolean {
        val set = prefs().getStringSet("repliedThreads", emptySet())!!
        return set.contains("$source-$id") || (siteId.isNotBlank() && set.contains("$siteId-$source-$id"))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentCorrespondenceBinding.bind(view)
        // Fold any legacy single-site creds into the site list, seed the known sites (both no-ops
        // after first run), and pick the page's site scope back up the way it was left.
        SiteStore.seedIfNeeded(requireContext())
        SiteStore.seedKnownSites(requireContext())
        migrateSpacePrefs()
        siteState.load(requireContext())
        // A filter naming a site that has since been deleted = All.
        siteState.dropMissing(allSites().map { it.id })
        // The space choice belongs to the active site; the unqualified prefs are the pre-multi-site
        // fallback so nothing moves for a single-site install.
        val act = SiteStore.active(requireContext())
        spaceId = act?.let { spaceIdFor(it.id) }?.takeIf { it > 0 } ?: prefs().getLong("space_id", 25L)
        spaceTitle = act?.let { spaceTitleFor(it.id) }?.takeIf { it.isNotBlank() }
            ?: (prefs().getString("space_title", "MichaelFilter") ?: "MichaelFilter")
        binding.correspondenceClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        // The ▦ hub, top-left as on every other list surface — the same directory accordion the
        // feed, the mailbox and the garden pages carry. Until now the ✕ was the only way out of
        // here, and popBackStack lands wherever you came from, which is nearly always the almanac
        // day page; that is not a route to anywhere else, it is the absence of one.
        binding.correspondenceHubButton.setOnClickListener {
            showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
        }
        binding.correspondenceRefresh.setOnClickListener { load() }
        // Step the reading size. The page re-lays out at the new size rather than being
        // magnified, so the text stays as sharp as the panel can draw it.
        binding.correspondenceTextSize.setOnClickListener {
            val next = com.toolsboox.ot.ReadingSize.cycle(requireContext())
            com.toolsboox.ot.ReadingSize.apply(binding.correspondenceContainer, next)
            android.widget.Toast.makeText(
                requireContext(), com.toolsboox.ot.ReadingSize.label(requireContext()),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // The strip, wired to FILTER: passing onSelectPeriod is what tells CalendarNavBarHost that
        // a period tap narrows this page instead of leaving for the calendar, and it also makes the
        // strip tell the truth about the filter — the active level becomes the focal slot, and the
        // carets step by that level rather than always by a day.
        navBar = CalendarNavBarHost(
            requireContext(), binding.correspondenceNavigator, this,
            onStepDay = { d ->
                val dir = if (d.isBefore(navAnchor)) -1 else 1
                navAnchor = stepByGranularity(navAnchor, dir); renderNav(); load()
            },
            onSelectPeriod = { g, d -> navGranularity = g; navAnchor = d; renderNav(); load() }
        )
        // The host opens filtering surfaces on "day"; this one opens on the year (see navGranularity).
        navBar?.setGranularity(navGranularity)
        renderNav()

        load()
    }

    /** Redraw the Almanac strip for the current anchor (dots for filled days), as the feed does. */
    private fun renderNav() {
        val bar = navBar ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, navAnchor, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        navAnchor.year, navAnchor.monthValue, navAnchor.dayOfMonth, startHour = null)
                cd to runCatching { calendarPatternService.load(root, navAnchor, loc) }.getOrNull()
            }
            // A missing/failed pattern must not kill the strip: render with an empty one rather
            // than skip. An unrendered CalendarNavBarHost never sets its currentDay, and a nav bar
            // with no currentDay swallows every touch — the silent way a date filter can render
            // once and then no-op for good (the mailbox hit exactly this on a fresh year).
            val safePat = pat ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(navAnchor.year, loc).fill()
            if (isAdded) bar.render(day, safePat)
        }
    }

    /**
     * Finish a freshly-built page: size the text to the reader's choice, and let any picture on
     * it be opened full-screen. Called after every render, so new rows get both.
     */
    private fun applyReadingAids(root: View) {
        com.toolsboox.ot.ReadingSize.apply(root)
        wireImageZoom(root)
    }

    private fun wireImageZoom(v: View) {
        if (com.toolsboox.ot.ImageZoom.isZoomable(v)) {
            com.toolsboox.ot.ImageZoom.makeTappable(v as android.widget.ImageView)
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) wireImageZoom(v.getChildAt(i))
    }

    // Two loads can be in flight (step the strip twice, fast); the last one asked for wins.
    private var loadSeq = 0

    private fun load() {
        val ctx = context ?: return
        val seq = ++loadSeq
        // Narrowed → that site becomes the live one, so the whole proven single-site path (thread
        // reader, replies, likes, grams) targets it unchanged — the SiteBoards seam.
        siteState.filter?.let { id -> focusSite(allSites().firstOrNull { it.id == id }) }
        lifecycleScope.launch {
            if (mode == "community") {
                if (aggregate()) {
                    // All sites: each site paired with ITS chosen space (a space id means nothing
                    // off its own site), fanned out in parallel, per-site failure collapsing to
                    // empty so one unreachable site never empties the others.
                    val plans = allSites().map { s ->
                        Triple(s, spaceIdFor(s.id), LedgerCommunityBridge.configFor(ctx, s))
                    }
                    val rows = withContext(Dispatchers.IO) {
                        coroutineScope {
                            plans.map { (s, sid, cfg) ->
                                async {
                                    try {
                                        if (sid <= 0L) emptyList()
                                        else LedgerCorrespondence.fetchSpaceFeed(ctx, sid, cfg = cfg).map { s to it }
                                    } catch (e: Exception) { emptyList() }
                                }
                            }.awaitAll().flatten()
                        }
                    }.sortedByDescending { it.second.createdAt }
                    if (seq != loadSeq || !isAdded) return@launch
                    communityRows = rows
                } else {
                    val posts = withContext(Dispatchers.IO) { LedgerCorrespondence.fetchSpaceFeed(ctx, spaceId) }
                    if (seq != loadSeq || !isAdded) return@launch
                    communityRows = posts.map { null to it }
                }
                renderCommunity(communityRows)
            } else {
                if (aggregate()) {
                    val plans = allSites().map { it to LedgerCommunityBridge.configFor(ctx, it) }
                    val rows = withContext(Dispatchers.IO) {
                        coroutineScope {
                            plans.map { (s, cfg) ->
                                async {
                                    try { LedgerCorrespondence.fetch(ctx, cfg).map { s to it } }
                                    catch (e: Exception) { emptyList() }
                                }
                            }.awaitAll().flatten()
                        }
                    }.sortedByDescending { it.second.createdAt }
                    if (seq != loadSeq || !isAdded) return@launch
                    replyRows = rows
                } else {
                    val replies = withContext(Dispatchers.IO) { LedgerCorrespondence.fetch(ctx) }
                    if (seq != loadSeq || !isAdded) return@launch
                    replyRows = replies.map { null to it }
                }
                render(replyRows)
            }
        }
    }

    /** The Replies | Community tab row + (in community) the current-space chip. Prepended to both views. */
    private fun addTabs(container: LinearLayout) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(4), px(4), px(4), px(10))
        }
        fun tab(label: String, key: String) = TextView(ctx).apply {
            text = label; textSize = 16f; isAllCaps = false
            setPadding(px(12), px(6), px(12), px(6))
            val on = mode == key
            setTextColor(if (on) 0xFF000000.toInt() else 0xFF888888.toInt())
            if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { if (mode != key) { mode = key; load() } }
        }
        row.addView(tab("Replies", "replies"))
        row.addView(tab("Community", "community"))
        container.addView(row)
        if (mode == "community") {
            if (aggregate()) {
                // All sites: one ❝ chip per site, each naming ITS space (or asking for one) — a
                // single space chip can't speak for several sites at once.
                val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (s in allSites()) {
                    val t = spaceTitleFor(s.id).ifBlank { if (spaceIdFor(s.id) > 0) "Space ${spaceIdFor(s.id)}" else "pick a space" }
                    chips.addView(TextView(ctx).apply {
                        text = "❝ ${s.display}: $t ▾"
                        textSize = 13f; setTextColor(0xFF2F6F96.toInt())
                        setPadding(px(6), 0, px(14), px(10))
                        setOnClickListener { pickSpace(s) }
                    })
                }
                container.addView(android.widget.HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false; addView(chips)
                })
            } else {
                container.addView(TextView(ctx).apply {
                    text = "❝  $spaceTitle   ▾"
                    textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setPadding(px(6), 0, px(6), px(10))
                    setOnClickListener { pickSpace() }
                })
            }
        }
    }

    /** Choose which community space to browse (persists, PER SITE).
     *  [site] targets a specific site's spaces (the aggregate's chips); null = the active site. */
    private fun pickSpace(site: LedgerSite? = null) {
        val ctx = requireContext()
        val target = site ?: SiteStore.active(ctx)
        lifecycleScope.launch {
            val cfg = target?.let { LedgerCommunityBridge.configFor(ctx, it) }
            val spaces = withContext(Dispatchers.IO) { LedgerCommunityBridge.spaces(ctx, cfg) }
            if (spaces.isEmpty()) {
                android.widget.Toast.makeText(ctx, "No spaces" + (target?.let { " on ${it.display}" } ?: "") + " (check the site's app password — 🌐 above)", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = spaces.map { it.title }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Community space" + (target?.let { " · ${it.display}" } ?: ""))
                .setItems(labels) { _, i ->
                    val chosen = spaces[i]
                    target?.let { setSpaceFor(it.id, chosen.id, chosen.title) }
                    // The unqualified legacy keys keep tracking the ACTIVE site's choice, so the
                    // single-site path (and anything else still reading them) stays truthful.
                    if (target == null || target.id == SiteStore.activeId(ctx)) {
                        spaceId = chosen.id; spaceTitle = chosen.title
                        prefs().edit().putLong("space_id", spaceId).putString("space_title", spaceTitle).apply()
                    }
                    load()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun renderCommunity(rows: List<Pair<LedgerSite?, LedgerPost>>) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val container = binding.correspondenceContainer
        container.removeAllViews()
        // The 🌐 switcher first — All sites aggregates, a pick narrows (the Mail-inbox idiom).
        container.addView(switcherBar())
        addTabs(container)
        fun sid(site: LedgerSite?) = site?.id ?: SiteStore.activeId(ctx)

        if (!aggregate() && !com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx).ready) {
            container.addView(TextView(ctx).apply {
                text = "Add the site's application password (tap 🌐 above, or Calendar Settings), then a space's posts appear here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(16), px(8), 0)
            })
            return
        }
        if (rows.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = if (aggregate())
                    "No posts on any site yet.\n\nEach ❝ chip above names the space read on that site — a site without one (or without its app password, 🌐) contributes nothing here."
                else "No posts in $spaceTitle yet."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(16), px(8), 0)
            })
            return
        }
        // The strip sits above BOTH tabs, so it filters both. A date control that visibly did
        // nothing on one of the two piles under it would read as broken on that tab, and the
        // question "what was said in July" is the same question whichever pile you ask it of.
        val shown = rows.filterNot { (s, p) -> isHidden("community", p.id.toString(), sid(s)) }
            .filter { inWindow(it.second.createdAt) }
        if (shown.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "Nothing in this ${windowLabel()}.\n\nNo posts " +
                    (if (aggregate()) "on any site" else "in $spaceTitle") + " landed in the " +
                    "period the strip above is pointing at. Step it, or widen it, to see more."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(16), px(8), 0)
            })
            return
        }
        for ((site, post) in shown) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(8), px(10), px(8))
                setBackgroundColor(0xFFF3F3F3.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, px(8)) }
            }
            card.addView(TextView(ctx).apply {
                // The site tag only in the aggregate (site != null) — a narrowed list already
                // names its site in the switcher above, so repeating it would be noise.
                text = listOfNotNull(post.author.ifBlank { null }, post.createdAt.take(16).ifBlank { null })
                    .joinToString("   ·   ") + (if (post.commentsCount > 0) "   ·   ${post.commentsCount}💬" else "") +
                    (if (hasReplied("community", post.id, sid(site))) "   ·   ✓ replied" else "") +
                    (site?.let { "   ·   🌐 ${it.display}" } ?: "")
                textSize = 12f; setTextColor(0xFF666666.toInt())
            })
            if (post.title.isNotBlank()) card.addView(TextView(ctx).apply {
                text = deHtml(post.title); textSize = 15f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            // Body with formatting kept (falls back to the plain excerpt).
            card.addView(TextView(ctx).apply {
                text = if (post.html.isNotBlank()) richHtml(post.html) else deHtml(post.excerpt)
                textSize = 14f; setTextColor(0xFF000000.toInt())
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            })
            // The message's featured / embedded image.
            post.imageUrl?.let { addImage(card, it, heightDp = 200) }
            // Horizontal-scrollable so the extra reply actions never push buttons off-screen.
            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val actionsScroll = android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false; addView(actions)
            }
            // Like — the FluentCommunity reaction, toggled straight from the Ledger.
            var liked = post.liked
            var likeCount = post.reactionsCount
            actions.addView(TextView(ctx).apply {
                fun label() = (if (liked) "♥" else "♡") + (if (likeCount > 0) "  $likeCount" else "")
                text = label()
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener {
                    // The like must land on the post's OWN site — focus it first (a no-op when
                    // it's already the active one), then the active-site client is right.
                    focusSite(site)
                    val was = liked; val count0 = likeCount
                    liked = !liked; likeCount = (likeCount + if (liked) 1 else -1).coerceAtLeast(0)
                    text = label()   // optimistic flip
                    lifecycleScope.launch {
                        val res = withContext(Dispatchers.IO) { LedgerCorrespondence.reactPost(ctx, post.id) }
                        if (res == null) { liked = was; likeCount = count0; text = label() }   // revert
                        else { liked = res.first; likeCount = res.second; text = label() }
                    }
                }
            })
            val provDefault = "↩ In reply to ${post.author}" +
                post.excerpt.trim().take(90).let { if (it.isNotBlank()) ": “$it”" else "" }
            val quotedPost = post.author.ifBlank { "Post" } + ":  " + post.excerpt
            actions.addView(TextView(ctx).apply {
                text = "↩  Reply"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener {
                    // The reply composes AGAINST this row's site: focus it first, and the dialog
                    // names it — a reply never lands on the wrong community silently.
                    focusSite(site)
                    showReplyDialog(
                        post.id, post.title.ifBlank { spaceTitle },
                        replyingTo = quotedPost, provenanceDefault = provDefault, provUrl = post.url
                    )
                }
            })
            actions.addView(TextView(ctx).apply {
                text = "📄  Post & replies"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener { focusSite(site); showThread("community", post.id, post.title.ifBlank { spaceTitle }) }
            })
            actions.addView(TextView(ctx).apply {
                text = "▸  Related"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), px(18), 0)
                setOnClickListener { focusSite(site); showPostRelated(post) }
            })
            if (post.url.isNotBlank() && post.public) actions.addView(TextView(ctx).apply {
                text = "↗  Open"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), 0, 0)
                setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(post.url))) }
            })
            card.addView(actionsScroll)
            wireHide(card, "community", post.id.toString(), post.title.ifBlank { deHtml(post.excerpt) }, sid(site))
            container.addView(card)
        }
        // Size the freshly-built page to the reader's choice, and let its pictures open.
        applyReadingAids(container)
    }

    private fun render(rows: List<Pair<LedgerSite?, LedgerReply>>) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val container = binding.correspondenceContainer
        container.removeAllViews()
        // The 🌐 switcher first — All sites aggregates, a pick narrows (the Mail-inbox idiom).
        container.addView(switcherBar())
        addTabs(container)
        fun sid(site: LedgerSite?) = site?.id ?: SiteStore.activeId(ctx)

        if (!aggregate() && !com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.config(ctx).ready) {
            container.addView(TextView(ctx).apply {
                text = "Add the site's application password (tap 🌐 above, or Calendar Settings), and replies to your posts and cards gather here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(24), px(8), 0)
            })
            return
        }
        if (rows.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = (if (aggregate()) "No correspondence on any site yet. " else "No correspondence yet. ") +
                    "When someone answers a post or a card of yours, the exchange appears here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(24), px(8), 0)
            })
            return
        }

        // Group by exchange, newest activity first (items arrive newest-first from the bridge).
        // The site is part of the grouping key — thread ids collide across sites (each Fluent
        // install counts from 1), and two sites' threads folding into one would be a quiet lie.
        //
        // THE WINDOW FILTERS THE REPLIES, and then any thread left with none drops out — rather
        // than testing a thread's own newest activity. A conversation that ran across a month
        // boundary should show you the half that happened in the month you are looking at, not
        // vanish because its last word came later.
        val threads = rows.filter { inWindow(it.second.createdAt) }
            .groupBy { "${it.first?.id ?: ""}|${it.second.source}-${it.second.threadId}" }.values
            .sortedByDescending { it.first().second.createdAt }

        if (threads.isEmpty()) {
            // There IS correspondence — the window just doesn't hold any of it. Saying so, and
            // saying WHICH window, is the difference between a filter and a page that looks broken.
            // The strip above is both the way out and the thing that put you here.
            container.addView(TextView(ctx).apply {
                text = "Nothing in this ${windowLabel()}.\n\nNo replies landed in the period the " +
                    "strip above is pointing at. Step it, or widen it, to see more."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(8), px(24), px(8), 0)
            })
            return
        }

        for (thread in threads) {
            val site = thread.first().first
            val head = thread.first().second
            val replied = hasReplied(head.source, head.threadId, sid(site))
            container.addView(TextView(ctx).apply {
                // The site tag only in the aggregate (site != null) — a narrowed list already
                // names its site in the switcher above.
                text = (if (head.source == "boards") "📋  " else "👥  ") +
                    deHtml(head.thread).ifBlank { "Untitled thread" } + (if (replied) "   ✓ replied" else "") +
                    (site?.let { "   ·  🌐 ${it.display}" } ?: "")
                textSize = 16f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(px(4), px(14), px(4), px(6))
                // Tap the thread title → open the original post (and its replies) in-app,
                // read from the thread's OWN site.
                setOnClickListener { focusSite(site); showThread(head.source, head.threadId, head.thread) }
            })
            for (r in thread.map { it.second }.filterNot { isHidden("replies", it.id, sid(site)) }) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(10), px(8), px(10), px(8))
                    setBackgroundColor(0xFFF3F3F3.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, px(6)) }
                }
                card.addView(TextView(ctx).apply {
                    text = "${r.author}   ·   ${r.createdAt.take(16)}"
                    textSize = 12f; setTextColor(0xFF666666.toInt())
                })
                card.addView(TextView(ctx).apply {
                    // The whole message, not the trimmed excerpt — reading it here is the point.
                    text = deHtml(r.content.ifBlank { r.excerpt }); textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                r.imageUrl?.let { addImage(card, it, heightDp = 180) }
                wireHide(card, "replies", r.id, deHtml(r.content.ifBlank { r.excerpt }), sid(site))
                container.addView(card)
            }
            container.addView(TextView(ctx).apply {
                text = "📄  Open post & replies"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                setPadding(px(10), px(2), px(10), px(4))
                setOnClickListener { focusSite(site); showThread(head.source, head.threadId, head.thread) }
            })
            if (head.source == "community" && head.threadUrl.isNotBlank() && head.public) {
                container.addView(TextView(ctx).apply {
                    text = "↗  Open the thread"
                    textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setPadding(px(10), px(2), px(10), px(4))
                    setOnClickListener {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(head.threadUrl)))
                    }
                })
            }
            if (head.source == "community") {
                val quoted = head.author + ":  " + head.content.ifBlank { head.excerpt }
                container.addView(TextView(ctx).apply {
                    text = "↩  Reply"; textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                    setPadding(px(10), px(2), px(10), px(10))
                    // Compose against the thread's own site — focused first, named in the dialog.
                    setOnClickListener { focusSite(site); showReplyDialog(head.threadId, head.thread, replyingTo = quoted) }
                })
            }
        }
        // Size the freshly-built page to the reader's choice, and let its pictures open.
        applyReadingAids(container)
    }

    /** Read the whole exchange in-app: every comment, yours marked "· you", uploads shown (📎 + image). */
    private fun showThread(source: String, threadId: Long, title: String) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(8), px(18), px(8)) }
        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        col.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, 0) })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            // With several sites configured, the reader names which one this thread lives on —
            // its Reply/Delete actions all land there.
            .setTitle(deHtml(title).ifBlank { "Thread" } +
                activeSiteLabel().let { if (it.isBlank()) "" else "  ·  🌐 $it" })
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        threadReaderDialog = dialog
        threadReaderOpen = true
        dialog.setOnDismissListener { if (threadReaderDialog === dialog) { threadReaderOpen = false; threadReaderDialog = null } }
        dialog.show()
        // The reader earns the whole screen width — long messages were cramped in the
        // stock dialog's narrow column.
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lifecycleScope.launch {
            val bundle = withContext(Dispatchers.IO) { LedgerCorrespondence.threadBundle(ctx, source, threadId) }
            if (!isAdded) return@launch
            val items = bundle.comments
            col.removeAllViews()

            // The ORIGINAL post/card first — this is the "bring up the post in-app" the row opens.
            bundle.post?.let { p ->
                col.addView(TextView(ctx).apply {
                    text = p.author + "   ·   " + p.createdAt.take(16)
                    textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, px(2), 0, px(1))
                })
                if (p.title.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(p.title); textSize = 17f; setTextColor(0xFF000000.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, px(2), 0, px(2))
                })
                if (p.content.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(p.content); textSize = 15f; setTextColor(0xFF000000.toInt())
                    setTextIsSelectable(true); setPadding(0, px(2), 0, px(4))
                })
                p.imageUrl?.let { url ->
                    val img = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(px(240), LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = px(4) }
                    }
                    col.addView(img)
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
                        if (bmp != null && isAdded) {
                            img.setImageBitmap(bmp)
                            com.toolsboox.ot.ImageZoom.makeTappable(img)
                        }
                    }
                }
                // Divider before the replies.
                col.addView(View(ctx).apply {
                    setBackgroundColor(0xFFDDDDDD.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                        .apply { topMargin = px(10); bottomMargin = px(2) }
                })
                col.addView(TextView(ctx).apply {
                    text = if (items.isEmpty()) "No replies yet." else "${items.size} " + (if (items.size == 1) "reply" else "replies")
                    textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, px(4), 0, px(2))
                })
            }
            if (bundle.post == null && items.isEmpty()) {
                col.addView(TextView(ctx).apply { text = "No replies yet."; setTextColor(0xFF888888.toInt()) })
            }

            // Render one comment (indented when it's a nested reply), with its own actions.
            /**
             * A voice/video reply, mounted the way the web side mounts it: a bordered box with a
             * play mark on it. Pressing it streams from the same URL the browser would use, so
             * nothing is downloaded by merely scrolling a thread.
             */
            fun soundbox(c: com.toolsboox.plugin.calendar.nw.ThreadComment, leftPad: Int): View {
                val isVideo = c.mediaKind == "video"
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(10), px(8), px(14), px(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt()); setStroke(px(2), 0xFF111111.toInt()); cornerRadius = px(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = px(6); bottomMargin = px(2); leftMargin = leftPad }
                }
                box.addView(TextView(ctx).apply {
                    text = "▶"; textSize = 16f; setTextColor(0xFF000000.toInt())
                    setPadding(0, 0, px(10), 0)
                })
                box.addView(TextView(ctx).apply {
                    text = if (isVideo) "Video gram" else "Voice gram"
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                box.setOnClickListener {
                    if (c.mediaUrl.isBlank()) {
                        android.widget.Toast.makeText(ctx, "No media on this reply", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        com.toolsboox.plugin.calendar.ot.AvPlayback.play(
                            ctx, c.mediaKind, null, c.mediaUrl,
                            (if (isVideo) "🎥 " else "🎤 ") + c.author, 0
                        )
                    }
                }
                return box
            }

            fun renderComment(c: com.toolsboox.plugin.calendar.nw.ThreadComment, indent: Boolean) {
                val leftPad = if (indent) px(22) else 0
                col.addView(TextView(ctx).apply {
                    val clip = when (c.mediaKind) { "audio" -> "   🎤"; "video" -> "   🎥"; else -> if (c.hasImage) "   📎" else "" }
                    text = (if (indent) "↳ " else "") + c.author + (if (c.mine) "  · you" else "") +
                        "   ·   " + c.createdAt.take(16) + clip
                    textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(leftPad, px(12), 0, px(1))
                })
                val body = c.content.ifBlank { c.excerpt }
                if (body.isNotBlank()) col.addView(TextView(ctx).apply {
                    text = deHtml(body); textSize = 14f; setTextColor(0xFF000000.toInt())
                    setTextIsSelectable(true); setPadding(leftPad, 0, 0, 0)
                })
                // A voice or video reply reads as a small box you press, not a media player
                // sitting open in the middle of a conversation. Nothing loads until it's pressed.
                if (c.mediaKind == "audio" || c.mediaKind == "video") {
                    col.addView(soundbox(c, leftPad))
                } else c.imageUrl?.let { url ->
                    val img = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(px(180), LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = px(4); leftMargin = leftPad }
                    }
                    col.addView(img)
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) { LedgerCorrespondence.loadImage(ctx, url) }
                        if (bmp != null && isAdded) {
                            img.setImageBitmap(bmp)
                            com.toolsboox.ot.ImageZoom.makeTappable(img)
                        }
                    }
                }
                // Per-comment actions row: Reply (community, nests under this comment) + Delete (own).
                val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(leftPad, px(2), 0, px(2)) }
                if (source == "community") actions.addView(TextView(ctx).apply {
                    text = "↩ Reply"; textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(2), px(20), px(2))
                    setOnClickListener {
                        showTextReplyDialog(threadId, title, replyingTo = c.author + ":  " + c.content.ifBlank { c.excerpt }, parentId = c.id)
                    }
                })
                if (c.mine) actions.addView(TextView(ctx).apply {
                    text = "✕ Delete"; textSize = 13f; setTextColor(0xFFB00020.toInt()); setPadding(0, px(2), 0, px(2))
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                            .setMessage("Delete this reply?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) { LedgerCorrespondence.deleteThreadComment(ctx, source, c.id) }
                                    android.widget.Toast.makeText(ctx, if (ok) "Deleted" else "Couldn't delete", android.widget.Toast.LENGTH_SHORT).show()
                                    if (ok) { afterReplyPosted(source, threadId, title) }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
                if (actions.childCount > 0) col.addView(actions)
            }

            // Top-level comments in order; each followed by its nested replies (one level).
            val topLevel = items.filter { it.parentId == 0L }
            val childrenOf = items.filter { it.parentId != 0L }.groupBy { it.parentId }
            for (c in topLevel) {
                renderComment(c, indent = false)
                childrenOf[c.id]?.forEach { renderComment(it, indent = true) }
            }
            // Orphaned nested replies (parent not in this page) still show, indented.
            items.filter { it.parentId != 0L && topLevel.none { t -> t.id == it.parentId } }
                .forEach { renderComment(it, indent = true) }

            // Bottom reply bar — reply to the POST from inside the reader (community only).
            if (source == "community") {
                col.addView(View(ctx).apply {
                    setBackgroundColor(0xFFDDDDDD.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                        .apply { topMargin = px(12); bottomMargin = px(6) }
                })
                col.addView(TextView(ctx).apply {
                    text = "↩  Reply"; textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(4), 0, px(4))
                    setOnClickListener { showReplyDialog(threadId, title) }
                })
            }
            // The thread fills in asynchronously, so this belongs at the end of the load —
            // sizing an empty column would do nothing.
            applyReadingAids(col)
        }
    }


    /** ▸ Related for a gram/post: its provenance (what it answered) + your neighbouring posts. */
    private fun showPostRelated(post: LedgerPost) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun open(url: String) = startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(8), px(18), px(8)) }
        col.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, 0) })
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("▸ Related · ${deHtml(post.title).ifBlank { spaceTitle }.take(32)}")
            .setView(android.widget.ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Close", null)
            .show()
        fun sectionLabel(t: String) = TextView(ctx).apply {
            text = t; textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, px(3))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { LedgerCorrespondence.communityRelated(ctx, post.id) }
            if (!isAdded) return@launch
            col.removeAllViews()
            if (r == null) { col.addView(TextView(ctx).apply { text = "Couldn't load." }); return@launch }
            r.provenance?.let { p ->
                col.addView(sectionLabel("FROM"))
                col.addView(TextView(ctx).apply {
                    // A real, easy-to-hit button (not a tiny ↩) when there's a source to jump to.
                    text = (if (p.url != null) "↩  Go to source" else "") + (if (p.url != null) "\n" else "") + deHtml(p.label)
                    textSize = 15f
                    setTextColor(if (p.url != null) 0xFF2F6F96.toInt() else 0xFF333333.toInt())
                    setPadding(px(12), px(10), px(12), px(10))
                    if (p.url != null) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setStroke(px(1), 0xFF2F6F96.toInt()); cornerRadius = px(8).toFloat()
                        }
                        setOnClickListener { open(p.url) }
                    }
                })
            }
            col.addView(sectionLabel("YOUR POSTS IN THIS SPACE"))
            if (r.related.isEmpty()) col.addView(TextView(ctx).apply {
                text = "None yet."; setTextColor(0xFF888888.toInt()); textSize = 14f
            })
            r.related.forEach { rc ->
                col.addView(TextView(ctx).apply {
                    text = "•  ${deHtml(rc.title)}"
                    textSize = 14f; setTextColor(if (rc.url != null) 0xFF2F6F96.toInt() else 0xFF000000.toInt())
                    setPadding(0, px(3), 0, px(1))
                    if (rc.url != null) setOnClickListener { open(rc.url) }
                })
            }
        }
    }

    // Thin wrappers so every call site keeps working — both open the ONE unified reply surface.
    @SuppressLint("ClickableViewAccessibility")
    private fun showInkReplyDialog(
        feedId: Long, thread: String, provenanceDefault: String? = null, provUrl: String? = null,
        replyingTo: String? = null, parentId: Long = 0
    ) = showReplyDialog(feedId, thread, replyingTo, parentId, provenanceDefault, provUrl, startText = false)

    private fun showTextReplyDialog(
        feedId: Long, thread: String, replyingTo: String? = null, parentId: Long = 0
    ) = showReplyDialog(feedId, thread, replyingTo, parentId, null, null, startText = true)

    /**
     * The ONE reply surface (the "merging surface"): a ⅓-page canvas with a Draw / Type
     * toggle. Draw = stylus ink (post as annotation, or Share as gram with provenance);
     * Type = markdown text (bridge renders a safe subset), with a basics cheat-sheet.
     * Nests under [parentId] when replying to a specific comment.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showReplyDialog(
        feedId: Long, thread: String, replyingTo: String? = null, parentId: Long = 0,
        provenanceDefault: String? = null, provUrl: String? = null, startText: Boolean = false
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val ink = InkPadView(ctx)
        var textMode = startText
        var shareAsGram: (() -> Unit)? = null

        // "Reply with Gram / Picking / Log": an existing object attached to this reply, KEEPING its
        // provenance, combined with the small handwriting box as one reply.
        var attachedBitmap: Bitmap? = null   // picking/gram image, stacked above the ink
        // An attached voice/video gram: the bitmap above is its poster, this is the clip itself.
        var attachedAvFile: java.io.File? = null
        var attachedAvKind: String = ""
        var attachedAvTitle: String = ""
        var attachedCaption = ""             // markdown provenance/quote that rides with the reply
        // Rhizome loop: also save this reply into your Ledger (a Pickings gram whose provenance
        // points BACK at this thread), so a comment becomes a Ledger object you can rework.
        var saveToLedger = false

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, 0, 0, px(4))
        }
        // Mode toggle — Draw vs Type; the active one is bold+underlined.
        val drawTab = TextView(ctx).apply { text = "✍ Draw"; textSize = 15f; setPadding(0, px(2), px(20), px(6)) }
        val typeTab = TextView(ctx).apply { text = "⌨ Type"; textSize = 15f; setPadding(0, px(2), 0, px(6)) }
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; addView(drawTab); addView(typeTab) }

        // Draw surface: the shared pen toolbar (undo · colours · width) over a bold-framed
        // ⅓-page pad — the creation-page niceties that don't need the Onyx engine.
        val penBar = InkPadView.penBar(ctx, ink)

        val inkFrame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()); setPadding(px(2), px(2), px(2), px(2))
            addView(ink, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, (340 * dp).toInt()
            ))
        }
        val inkPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; addView(penBar); addView(inkFrame)
        }
        // Type surface: markdown editor + collapsible basics.
        val input = android.widget.EditText(ctx).apply {
            hint = "Write a reply… (markdown)"; setSingleLine(false); minLines = 6; gravity = android.view.Gravity.TOP
            setPadding(px(10), px(10), px(10), px(10))
        }
        val cheatToggle = TextView(ctx).apply {
            text = "ⓘ  Markdown basics"; textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(6), 0, px(2))
        }
        val cheat = TextView(ctx).apply {
            text = "**bold**   *italic*   `code`\n[text](https://link)\n- bullet    1. number\n# Heading    > quote"
            textSize = 12f; setTextColor(0xFF666666.toInt()); typeface = android.graphics.Typeface.MONOSPACE
            setPadding(px(8), px(4), px(8), px(6)); visibility = View.GONE
        }
        cheatToggle.setOnClickListener {
            cheat.visibility = if (cheat.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        val textPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; addView(input); addView(cheatToggle); addView(cheat)
        }

        val contentFrame = android.widget.FrameLayout(ctx).apply { addView(inkPane); addView(textPane) }

        // Attachment preview (shown once you attach a Gram/Picking/Log) + the "Reply with…" row.
        val attachPreview = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(px(6), px(6), px(6), px(6)); setBackgroundColor(0xFFEFF4F7.toInt())
        }
        fun clearAttachment() {
            // Detach the preview BEFORE recycling — an attached ImageView drawing a
            // recycled bitmap is a hard crash.
            attachPreview.removeAllViews(); attachPreview.visibility = View.GONE
            attachedBitmap?.recycle(); attachedBitmap = null; attachedCaption = ""
            attachedAvFile = null; attachedAvKind = ""; attachedAvTitle = ""
        }
        fun setAttachment(label: String, bmp: Bitmap?, caption: String) {
            attachPreview.removeAllViews()
            attachedBitmap?.recycle()
            attachedBitmap = bmp; attachedCaption = caption
            attachPreview.addView(TextView(ctx).apply {
                text = "📎  $label     ✕ remove"; textSize = 12f; setTextColor(0xFF2F6F96.toInt())
                setOnClickListener { clearAttachment() }
            })
            if (bmp != null) attachPreview.addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx,
                android.widget.ImageView(ctx).apply {
                    setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    maxHeight = (140 * dp).toInt()
                }, taped = false))
            if (caption.isNotBlank()) attachPreview.addView(TextView(ctx).apply {
                text = richHtml(caption.replace("\n", "<br>")); textSize = 12f; setTextColor(0xFF444444.toInt())
                setPadding(0, px(4), 0, 0)
            })
            attachPreview.visibility = View.VISIBLE
        }
        val withRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(2), px(8), px(2), px(2))
        }
        fun withBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 14f; setTextColor(0xFF2F6F96.toInt()); setPadding(0, px(2), px(16), px(2))
            setOnClickListener { onTap() }
        }
        withRow.addView(withBtn("🎴 with Gram") { pickGramForReply { l, b, c -> setAttachment(l, b, c) } })
        withRow.addView(withBtn("❝ with Picking") { pickPickingForReply { l, b, c -> setAttachment(l, b, c) } })
        withRow.addView(withBtn("🕘 with Log") { pickLogForReply { l, c -> setAttachment(l, null, c) } })
        withRow.addView(withBtn("🎤 with Voice") {
            pickAvGramForReply { label, poster, file, kind, title ->
                setAttachment(label, poster, "")
                attachedAvFile = file; attachedAvKind = kind; attachedAvTitle = title
            }
        })

        // Save-to-Ledger toggle: the reply also lands as a Pickings gram citing this thread.
        val saveToggle = TextView(ctx).apply {
            textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(2), px(4), px(2), px(2))
            fun label() = (if (saveToLedger) "☑" else "☐") + "  Save this reply to my Ledger"
            text = label()
            setOnClickListener { saveToLedger = !saveToLedger; text = label() }
        }

        val gramRow = TextView(ctx).apply {
            text = "↗  Share as gram instead…"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(2), px(10), 0, px(4))
            setOnClickListener { shareAsGram?.invoke() }
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), 0)
            addView(actionRow)
            addView(tabRow)
            // With several sites configured, the compose surface NAMES where Send will land —
            // the write target is the row you tapped, chosen there, never ambient.
            activeSiteLabel().takeIf { it.isNotBlank() }?.let { name ->
                addView(TextView(ctx).apply {
                    text = "→  posts to 🌐 $name"
                    textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(px(2), 0, 0, px(4))
                })
            }
            if (!replyingTo.isNullOrBlank()) addView(TextView(ctx).apply {
                text = deHtml(replyingTo); textSize = 13f; setTextColor(0xFF333333.toInt())
                setPadding(px(8), px(4), px(8), px(6)); maxHeight = (120 * dp).toInt()
                movementMethod = android.text.method.ScrollingMovementMethod()
            })
            addView(attachPreview)
            // EVERY tappable above the canvas, nothing below it — the handwriting-panel rule
            // (see LedgerTitlePad): the attach pickers, the save-to-Ledger toggle and the
            // share-as-gram door all used to sit under the pad, square under the writing hand.
            addView(withRow)
            addView(saveToggle)
            if (provenanceDefault != null) addView(gramRow)
            addView(contentFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        fun applyMode() {
            inkPane.visibility = if (textMode) View.GONE else View.VISIBLE
            textPane.visibility = if (textMode) View.VISIBLE else View.GONE
            gramRow.visibility = if (!textMode && provenanceDefault != null) View.VISIBLE else View.GONE
            drawTab.setTypeface(null, if (textMode) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
            drawTab.paintFlags = if (textMode) 0 else android.graphics.Paint.UNDERLINE_TEXT_FLAG
            typeTab.setTypeface(null, if (textMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            typeTab.paintFlags = if (textMode) android.graphics.Paint.UNDERLINE_TEXT_FLAG else 0
        }
        drawTab.setOnClickListener { textMode = false; applyMode() }
        typeTab.setOnClickListener { textMode = true; applyMode() }
        applyMode()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle((if (parentId > 0) "Reply · " else "Reply · ") + deHtml(thread).ifBlank { "thread" }.take(28))
            .setView(box)
            .create()

        fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(16), px(4), px(16), px(4)); setOnClickListener { onTap() }
        }
        actionRow.addView(actionBtn("Clear") { if (textMode) input.setText("") else ink.clear() })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        actionRow.addView(actionBtn("Send") {
            if (textMode) {
                // Type mode: the typed body, plus any attached item's provenance caption.
                // An attached IMAGE (gram/picking) rides too — switching Draw→Type must not
                // silently drop what you picked.
                val typed = input.text.toString().trim()
                val text = listOf(typed, attachedCaption).filter { it.isNotBlank() }.joinToString("\n\n")
                if (text.isBlank() && attachedBitmap == null) {
                    android.widget.Toast.makeText(ctx, "Nothing to send", android.widget.Toast.LENGTH_SHORT).show(); return@actionBtn
                }
                val png = attachedBitmap?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        if (attachedAvFile != null) LedgerCorrespondence.postInkReply(
                            ctx, feedId, null, parentId, text,
                            attachedAvFile, attachedAvKind, attachedAvTitle, png)
                        else if (png != null) LedgerCorrespondence.postInkReply(ctx, feedId, png, parentId, text)
                        else LedgerCorrespondence.postTextReply(ctx, feedId, text, parentId)
                    }
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status == "Reply posted") { markReplied("community", feedId, SiteStore.activeId(ctx)); dialog.dismiss(); afterReplyPosted("community", feedId, thread) }
                }
            } else {
                // Draw mode: [attached image] stacked above [your ink], one combined PNG, with the
                // attachment's provenance as the caption. If nothing is drawn AND only a Log (text)
                // is attached, post it as a text reply instead.
                val inkBmp = ink.render()
                val combined = stackVertically(attachedBitmap, inkBmp)
                if (combined == null && attachedCaption.isBlank()) {
                    android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
                    return@actionBtn
                }
                val png = combined?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val cap = attachedCaption
                // With a clip attached, the ink stands on its own as the picture and the poster
                // stays the clip's own face — stacking them would make a poster of your notes.
                val avFile = attachedAvFile
                val avKind = attachedAvKind
                val avTitle = attachedAvTitle
                val inkOnlyPng = if (avFile == null) null else inkBmp?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val posterPng = if (avFile == null) null else attachedBitmap?.let {
                    val baos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.PNG, 100, baos); baos.toByteArray()
                }
                val alsoSave = saveToLedger && combined != null
                val saveBmp = if (alsoSave) combined!!.copy(combined.config ?: Bitmap.Config.ARGB_8888, false) else null
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        if (avFile != null) LedgerCorrespondence.postInkReply(
                            ctx, feedId, inkOnlyPng, parentId, cap, avFile, avKind, avTitle, posterPng)
                        else if (png != null) LedgerCorrespondence.postInkReply(ctx, feedId, png, parentId, cap)
                        else LedgerCorrespondence.postTextReply(ctx, feedId, cap, parentId)
                    }
                    // Rhizome: the reply also becomes a Pickings gram whose provenance points back here.
                    if (status == "Reply posted" && saveBmp != null) withContext(Dispatchers.IO) {
                        runCatching {
                            com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                                calendarDayService, documentsRoot(), saveBmp, java.time.LocalDate.now(),
                                com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY,
                                sourceLink = provUrl ?: "", sourceLabel = "↩ Reply · ${deHtml(thread).take(40)}"
                            )
                        }
                        saveBmp.recycle()
                    }
                    // NEVER recycle the attachment here — stackVertically ALIASES it when there's
                    // no ink, and the still-open dialog's preview (failed post) or the dismiss
                    // animation would then draw a recycled bitmap → crash. clearAttachment owns it.
                    if (inkBmp !== combined) inkBmp?.recycle()
                    if (combined !== attachedBitmap && combined !== inkBmp) combined?.recycle()
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status == "Reply posted") { markReplied("community", feedId, SiteStore.activeId(ctx)); dialog.dismiss(); afterReplyPosted("community", feedId, thread) }
                }
            }
        })
        shareAsGram = {
            val bmp = ink.render()
            if (bmp == null) android.widget.Toast.makeText(ctx, "Nothing written", android.widget.Toast.LENGTH_SHORT).show()
            else { dialog.dismiss(); showGramProvenanceEditor(bmp, provenanceDefault ?: "", provUrl) }
        }
        // The reply surface holds ink, typed markdown and attachments mid-compose — a palm
        // outside the dialog must not cost them. Cancel / Send above the pad and the back
        // gesture remain the ways out.
        showGuardedModal(dialog)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** After any reply/delete: refresh the inbox, and if the thread reader is open, reopen it
     *  so the change (new reply, nesting, removal) shows immediately. */
    private fun afterReplyPosted(source: String, id: Long, thread: String) {
        load()
        if (threadReaderOpen) { threadReaderDialog?.dismiss(); showThread(source, id, thread) }
    }

    /**
     * Confirm-and-edit step before a reply goes out as a gram: the provenance line is editable
     * (redact or reword it) — outward-facing, so nothing shares until you say so.
     */
    private fun showGramProvenanceEditor(bmp: Bitmap, provenanceDefault: String, provUrl: String?) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val preview = com.toolsboox.ot.InkMount.wrapInColumn(ctx, android.widget.ImageView(ctx).apply {
            setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(150))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        })
        val input = android.widget.EditText(ctx).apply {
            setText(provenanceDefault); setSelection(text.length)
            textSize = 14f; setPadding(px(12), px(10), px(12), px(10)); minLines = 2
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), 0)
            addView(preview)
            addView(TextView(ctx).apply {
                text = "PROVENANCE (edit before sharing)"
                textSize = 11f; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, px(3))
            })
            addView(input)
        }
        // The share carries rendered ink and an edited provenance line — work a stray touch
        // outside must not throw away (and an outside dismiss would skip the Cancel path that
        // recycles the bitmap). Cancel and the back gesture remain the ways out.
        showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            // Names both the space AND (multi-site) the site it posts into — outward-facing.
            .setTitle("Share as gram · $spaceTitle" +
                activeSiteLabel().let { if (it.isBlank()) "" else " · 🌐 $it" })
            .setView(box)
            .setPositiveButton("Share ↗") { _, _ ->
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                val provenance = input.text.toString().trim()
                val uuid = "gram-" + java.util.UUID.randomUUID().toString().lowercase()
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge.postGram(
                            ctx, b64, "", uuid, spaceId, provenance.ifBlank { null }, provUrl
                        )
                    }
                    android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
                    if (status.startsWith("Posted")) load()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> bmp.recycle() }
            .create())
    }

    /** Minimal stylus pad: white background, black ink, no Onyx pipeline needed for a short reply. */

    private data class LogPick(val kind: String, val title: String, val excerpt: String, val source: String?, val url: String?)

    /** Walk the most recent [days] day-files and gather log items (feeds deduped, pickings, items). */
    private fun gatherLog(days: Int): List<LogPick> {
        val out = mutableListOf<LogPick>()
        val cal = java.io.File(documentsRoot(), "calendar")
        if (!cal.exists()) return out
        cal.walkTopDown()
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .sortedByDescending { it.name }
            .take(days)
            .forEach { f ->
                // Slim decode — the log never needs the stroke arrays, and "All" walks ~1000 files.
                val day = runCatching { calendarDayService.loadLogSlice(f) }.getOrNull() ?: return@forEach
                val seenUrl = HashSet<String>()
                for (e in day.readingEvents) {
                    val u = e.url
                    if (!u.isNullOrBlank() && !seenUrl.add(u)) continue
                    val t = (e.excerpt?.takeIf { it.isNotBlank() } ?: e.title).trim()
                    if (t.isNotBlank()) out.add(LogPick("📰", e.title, t, e.source, e.url))
                }
                for (t in day.textElements.filter { it.pageKey == "pickings" && it.text.isNotBlank() })
                    out.add(LogPick("❝", "Picking", t.text.trim(), null, null))
                for (li in day.ledgerItems.filter { it.text.isNotBlank() })
                    out.add(LogPick("🗒", li.text.trim().take(40), li.text.trim(), null, null))
            }
        return out
    }

    /** Pick a Log item → its quote + source ride the reply as a markdown provenance caption.
     *  Scroll-safe (tappable rows, not an AlertDialog list), searchable, with a widening window
     *  so it loads a small recent slice fast and you can reach back. */
    private fun pickLogForReply(onPicked: (String, String) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val ranges = intArrayOf(14, 60, 180, 1000)   // ~2wk · 2mo · 6mo · all
        val rangeNames = arrayOf("2 wk", "2 mo", "6 mo", "All")
        var rangeIdx = 0
        var all: List<LogPick> = emptyList()

        val search = android.widget.EditText(ctx).apply {
            hint = "Search log…"; setSingleLine(true); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rangeBtn = TextView(ctx).apply {
            text = "▸ ${rangeNames[rangeIdx]}"; textSize = 14f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(10), px(6), px(6), px(6))
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(search); addView(rangeBtn)
        }
        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), 0)
            addView(topRow); addView(scroll)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply with Log")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()

        fun captionFor(p: LogPick) = buildString {
            append("> ").append(p.excerpt.take(400))
            val src = p.source?.takeIf { it.isNotBlank() }; val url = p.url?.takeIf { it.isNotBlank() }
            if (url != null) append("\n\n↩ from [").append(src ?: "source").append("](").append(url).append(")")
            else if (src != null) append("\n\n↩ from ").append(src)
        }
        fun render() {
            val q = search.text.toString().trim().lowercase()
            val shown = (if (q.isBlank()) all else all.filter { it.excerpt.lowercase().contains(q) || (it.source ?: "").lowercase().contains(q) }).take(400)
            listCol.removeAllViews()
            if (shown.isEmpty()) listCol.addView(TextView(ctx).apply {
                text = "No matching log items."; setTextColor(0xFF888888.toInt()); setPadding(px(4), px(12), px(4), 0)
            })
            for (p in shown) listCol.addView(TextView(ctx).apply {
                text = "${p.kind}  ${p.excerpt.take(90)}" + (p.source?.let { "\n      · $it" } ?: "")
                textSize = 14f; setTextColor(0xFF000000.toInt()); setPadding(px(6), px(10), px(6), px(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onPicked("Log · ${p.title.take(30)}", captionFor(p)); dialog.dismiss() }
            })
        }
        fun reload() {
            listCol.removeAllViews()
            listCol.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) })
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { gatherLog(ranges[rangeIdx]) }
                if (!isAdded) return@launch
                all = loaded; render()
            }
        }
        rangeBtn.setOnClickListener { rangeIdx = (rangeIdx + 1) % ranges.size; rangeBtn.text = "▸ ${rangeNames[rangeIdx]}"; reload() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        reload()
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private data class PickPage(val date: java.time.LocalDate, val key: String, val name: String)

    private fun dayFileDate(name: String): java.time.LocalDate? =
        runCatching { java.time.LocalDate.parse(name.removePrefix("day-").removeSuffix("-v2.json")) }.getOrNull()

    /** Fullscreen lightbox: inspect the image BIG before committing — [label] on top,
     *  Attach / Close on the action row. The inspect step both visual pickers share. */
    private fun showAttachLightbox(bmp: Bitmap, label: String, onAttach: () -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val img = android.widget.ImageView(ctx).apply {
            setImageBitmap(bmp); adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(com.toolsboox.ot.InkMount.wrapInColumn(ctx, img))
            setPadding(px(8), px(4), px(8), px(4))
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(label.take(40))
            .setView(scroll)
            .setPositiveButton("Attach") { _, _ -> onAttach() }
            .setNegativeButton("Close", null)
            .show()
            .window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
    }

    /** Pick a Pickings page — VISUALLY: thumbnail rows (rendered lazily, batched) and a
     *  tap-to-zoom lightbox before committing ("it's all titles — who can remember"). */
    private fun pickPickingForReply(onPicked: (String, Bitmap?, String) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply with Picking").setView(scroll).setNegativeButton("Cancel", null).create()

        // Render the chosen page fresh at full width and hand it to the lightbox → attach.
        fun inspect(p: PickPage) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val day = runCatching { calendarDayService.load(documentsRoot(), p.date, null, java.util.Locale.getDefault()) }.getOrNull() ?: return@withContext null
                    com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderPageToBitmap(
                        day.noteStrokes[p.key] ?: emptyList(),
                        day.imageElements.filter { it.page == p.key },
                        day.textElements.filter { it.pageKey == p.key },
                        targetWidth = 1000,
                        context = context
                    )
                }
                if (!isAdded) return@launch
                if (bmp == null) { android.widget.Toast.makeText(ctx, "That picking is empty", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
                showAttachLightbox(bmp, "❝ ${p.name} · ${p.date}") {
                    dialog.dismiss()
                    onPicked("Picking · ${p.name}", bmp, "❝ from your Picking “${p.name}” · ${p.date}")
                }
            }
        }

        listCol.addView(TextView(ctx).apply { text = "Loading pickings…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) })
        lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                val out = mutableListOf<PickPage>()
                val cal = java.io.File(documentsRoot(), "calendar")
                if (cal.exists()) cal.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                    .sortedByDescending { it.name }.take(120)
                    .forEach { f ->
                        val d = dayFileDate(f.name) ?: return@forEach
                        val day = runCatching { calendarDayService.load(f) }.getOrNull() ?: return@forEach
                        val names = runCatching { com.toolsboox.plugin.calendar.ot.PickingsStore.list(ctx, d).associate { it.key to it.name } }.getOrNull() ?: emptyMap()
                        val keys = (day.noteStrokes.keys + day.imageElements.map { it.page } + day.textElements.map { it.pageKey })
                            .filter { com.toolsboox.plugin.calendar.ot.PickingsStore.isPickings(it) }.toSet()
                        for (k in keys) {
                            val hasContent = (day.noteStrokes[k]?.isNotEmpty() == true) ||
                                day.imageElements.any { it.page == k } || day.textElements.any { it.pageKey == k && it.text.isNotBlank() }
                            if (hasContent) out.add(PickPage(d, k, names[k] ?: "Pickings"))
                        }
                    }
                out
            }
            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (pages.isEmpty()) { listCol.addView(TextView(ctx).apply { text = "No pickings with content yet."; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) }); return@launch }

            // Thumbnail rows in lazy batches: placeholder first, small renders fill in as they
            // finish (all 120 up-front would stall the dialog for many seconds on e-ink).
            val batch = 20
            var shown = 0
            lateinit var appendBatch: () -> Unit
            appendBatch = {
                val slice = pages.drop(shown).take(batch)
                shown += slice.size
                val moreBtn: TextView? = if (shown < pages.size) TextView(ctx).apply {
                    text = "＋ ${pages.size - shown} more"
                    textSize = 13f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(8), px(12), px(8), px(12))
                } else null
                for (p in slice) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(px(6), px(8), px(6), px(8)); setBackgroundResource(android.R.drawable.list_selector_background)
                    }
                    val thumbView = android.widget.ImageView(ctx).apply {
                        adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
                        layoutParams = FrameLayout.LayoutParams(px(110), px(80))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    row.addView(com.toolsboox.ot.InkMount.wrap(ctx, thumbView, taped = false).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = px(10) }
                    })
                    row.addView(TextView(ctx).apply {
                        text = "❝ ${p.name}\n· ${p.date}"; textSize = 14f; setTextColor(0xFF000000.toInt())
                    })
                    row.setOnClickListener { inspect(p) }
                    if (moreBtn != null) listCol.addView(row, listCol.childCount)
                    else listCol.addView(row)
                    // Lazy thumb: small render off-main, fills in when ready.
                    lifecycleScope.launch {
                        val thumb = withContext(Dispatchers.IO) {
                            runCatching {
                                val day = calendarDayService.load(documentsRoot(), p.date, null, java.util.Locale.getDefault())
                                com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.renderPageToBitmap(
                                    day.noteStrokes[p.key] ?: emptyList(),
                                    day.imageElements.filter { it.page == p.key },
                                    day.textElements.filter { it.pageKey == p.key },
                                    targetWidth = 320,
                                    context = context
                                )
                            }.getOrNull()
                        }
                        if (isAdded && thumb != null) thumbView.setImageBitmap(thumb)
                    }
                }
                moreBtn?.let { btn ->
                    btn.setOnClickListener { listCol.removeView(btn); appendBatch() }
                    listCol.addView(btn)
                }
            }
            appendBatch()
        }
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // Carries BOTH faces of the gram — inline base64 and the media-store ref — so the picker's
    // decode can resolve whichever the element actually has (data → dataRef → nothing).
    private data class GramPick(
        val date: java.time.LocalDate, val data: String, val dataRef: String,
        val label: String, val link: String
    )

    /** Pick an existing Gram (a dropped image element, carrying its own provenance) → attach it
     *  with that provenance. Scroll-safe thumbnail list over the recent window. */
    private fun pickGramForReply(onPicked: (String, Bitmap?, String) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (440 * dp).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply with Gram").setView(scroll).setNegativeButton("Cancel", null).create()

        listCol.addView(TextView(ctx).apply { text = "Loading grams…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) })
        lifecycleScope.launch {
            val grams = withContext(Dispatchers.IO) {
                val out = mutableListOf<GramPick>()
                val cal = java.io.File(documentsRoot(), "calendar")
                if (cal.exists()) cal.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                    .sortedByDescending { it.name }.take(120)
                    .forEach { f ->
                        val d = dayFileDate(f.name) ?: return@forEach
                        val day = runCatching { calendarDayService.load(f) }.getOrNull() ?: return@forEach
                        for (e in day.imageElements) {
                            if (e.data.isBlank() && e.dataRef.isBlank()) continue
                            val label = e.sourceLabel.ifBlank { "Gram" }
                            out.add(GramPick(d, e.data, e.dataRef, label, e.sourceLink))
                            if (out.size >= 200) return@withContext out
                        }
                    }
                out
            }
            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (grams.isEmpty()) { listCol.addView(TextView(ctx).apply { text = "No grams yet."; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0) }); return@launch }
            for (g in grams) {
                // Downsampled decode — 200 full-res bitmaps in one list is an OOM on e-ink RAM.
                // The bounded pattern lives in LedgerMedia now, which also resolves a ref'd face.
                val thumb = com.toolsboox.ot.LedgerMedia.resolveThumb(ctx, g.data, g.dataRef, px(72)) ?: continue
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(8), px(6), px(8)); setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(com.toolsboox.ot.InkMount.wrap(ctx,
                    android.widget.ImageView(ctx).apply {
                        setImageBitmap(thumb); adjustViewBounds = true
                        layoutParams = FrameLayout.LayoutParams(px(72), px(72))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }, taped = false).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = px(10) }
                })
                row.addView(TextView(ctx).apply {
                    text = "${g.label}\n· ${g.date}"; textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    val bmp = com.toolsboox.ot.LedgerMedia.resolveBitmap(ctx, g.data, g.dataRef)
                    val cap = if (g.link.startsWith("http")) "🎴 gram · [${g.label}](${g.link})" else "🎴 gram · ${g.label}"
                    onPicked("Gram · ${g.label.take(24)}", bmp, cap)
                }
                listCol.addView(row)
            }
        }
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** One recording available to attach to a reply. */
    private data class AvPick(
        val date: java.time.LocalDate,
        val file: java.io.File,
        val kind: String,
        val title: String,
        val durationMs: Int
    )

    /**
     * A recording made from inside a reply.
     *
     * It is saved to today's day first, so the clip is a Ledger object you can find again on the
     * board rather than something that exists only as an attachment on someone else's thread.
     * Then it comes back as the reply's attachment. Bidirectional, same as "Reply with…".
     */
    private fun attachFreshAvGram(
        att: com.toolsboox.da.Attachment,
        onPicked: (String, Bitmap?, java.io.File, String, String) -> Unit
    ) {
        val kind = when (att.kind) {
            com.toolsboox.da.Attachment.Kind.AUDIO -> "audio"
            com.toolsboox.da.Attachment.Kind.VIDEO -> "video"
            else -> return   // a photo isn't an A/V gram; the ink pad already takes pictures
        }
        val file = java.io.File(attachmentsDir(), att.filename)
        if (!file.exists()) return

        lifecycleScope.launch {
            val today = java.time.LocalDate.now()
            val durationMs = att.duration?.let { (it * 1000).toInt() }?.takeIf { it > 0 }
                ?: withContext(Dispatchers.IO) { com.toolsboox.plugin.calendar.ot.AvPoster.durationMs(file) }
            val title = (if (kind == "video") "🎥 Video gram · " else "🎤 Audio gram · ") + today

            val poster = withContext(Dispatchers.IO) {
                runCatching {
                    val day = calendarDayService.load(documentsRoot(), today, null, java.util.Locale.getDefault())
                    day.avGrams.add(att)
                    calendarDayService.save(documentsRoot(), today, day)
                }
                com.toolsboox.plugin.calendar.ot.AvPoster.poster(file, att.kind, durationMs, title)
            }

            if (!isAdded) { poster?.recycle(); return@launch }
            onPicked((if (kind == "video") "Video · " else "Voice · ") + today, poster, file, kind, title)
        }
    }

    /**
     * Attach a voice or video gram you've already made to a reply.
     *
     * Reads the recordings off recent days rather than offering to record a new one here: the
     * capture surfaces already exist, and a reply is for sending something, not making it. The
     * poster comes back with it so the composer can show what's attached, and so the posted reply
     * carries the clip's own still face.
     */
    private fun pickAvGramForReply(
        onPicked: (String, Bitmap?, java.io.File, String, String) -> Unit
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (400 * dp).toInt())
        }
        // "Record one now" sits ABOVE the list rather than in it, so reloading the list can't
        // take it away with the placeholder.
        val recordRow = TextView(ctx).apply {
            text = "＋  Record one now…"
            textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(6), px(12), px(6), px(12))
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(recordRow)
            addView(scroll)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply with Voice or Video").setView(holder).setNegativeButton("Cancel", null).create()

        // Make one right here. The recording still lands in today's Ledger on the way past —
        // a reply is backed by a real object, not a blob that exists only inside a comment.
        recordRow.setOnClickListener {
            dialog.dismiss()
            captureAvGram { att -> attachFreshAvGram(att, onPicked) }
        }

        listCol.addView(TextView(ctx).apply {
            text = "Looking for recordings…"; setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0)
        })

        lifecycleScope.launch {
            val picks = withContext(Dispatchers.IO) {
                val out = mutableListOf<AvPick>()
                val dir = attachmentsDir()
                val cal = java.io.File(documentsRoot(), "calendar")
                if (cal.exists()) cal.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
                    .sortedByDescending { it.name }.take(120)
                    .forEach { f ->
                        val d = dayFileDate(f.name) ?: return@forEach
                        val day = runCatching { calendarDayService.load(f) }.getOrNull() ?: return@forEach
                        for (a in day.avGrams) {
                            val kind = when (a.kind) {
                                com.toolsboox.da.Attachment.Kind.AUDIO -> "audio"
                                com.toolsboox.da.Attachment.Kind.VIDEO -> "video"
                                else -> continue
                            }
                            val file = java.io.File(dir, a.filename)
                            if (!file.exists()) continue
                            // Prefer the title the gram was given on its picking card.
                            val titled = day.imageElements.firstOrNull { it.attachmentId == a.id }
                            out.add(AvPick(
                                d, file, kind,
                                titled?.mediaTitle?.ifBlank { null } ?: a.filename,
                                titled?.durationMs?.takeIf { it > 0 }
                                    ?: a.duration?.let { (it * 1000).toInt() } ?: 0
                            ))
                            if (out.size >= 80) return@withContext out
                        }
                    }
                out
            }

            if (!isAdded) return@launch
            listCol.removeAllViews()
            if (picks.isEmpty()) {
                listCol.addView(TextView(ctx).apply {
                    text = "No recordings yet.\nMake one from the day page, then it'll show up here."
                    setTextColor(0xFF888888.toInt()); setPadding(px(6), px(12), px(6), 0)
                })
                return@launch
            }

            for (p in picks) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(6), px(10), px(6), px(10))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(TextView(ctx).apply {
                    text = if (p.kind == "video") "🎥" else "🎤"; textSize = 22f
                    setPadding(0, 0, px(12), 0)
                })
                val clock = com.toolsboox.plugin.calendar.ot.AvPoster.clock(p.durationMs)
                row.addView(TextView(ctx).apply {
                    text = p.title.take(40) + "\n· " + p.date + (if (clock.isNotBlank()) "   ·   $clock" else "")
                    textSize = 14f; setTextColor(0xFF000000.toInt())
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val poster = withContext(Dispatchers.IO) {
                            val kind = if (p.kind == "video") com.toolsboox.da.Attachment.Kind.VIDEO
                                else com.toolsboox.da.Attachment.Kind.AUDIO
                            com.toolsboox.plugin.calendar.ot.AvPoster.poster(p.file, kind, p.durationMs, p.title)
                        }
                        if (!isAdded) { poster?.recycle(); return@launch }
                        val label = (if (p.kind == "video") "Video · " else "Voice · ") + p.title.take(24)
                        onPicked(label, poster, p.file, p.kind, p.title)
                    }
                }
                listCol.addView(row)
            }
        }

        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
