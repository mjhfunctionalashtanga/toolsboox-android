package com.toolsboox.plugin.calendar.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.R
import com.toolsboox.databinding.FragmentSiteWebBinding
import com.toolsboox.ui.plugin.ScreenFragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The active site's own forward-facing Vue app in a persistent-session WebView — sign in once and
 * the cookies stick (WebView's default disk-backed [CookieManager]), so the FluentCommunity portal,
 * FluentCRM, FluentCart storefront, FluentBooking page, FluentSupport portal or FluentBoards front
 * render exactly as the site draws them rather than being reimplemented natively.
 *
 * A FORWARD-FACING (member/customer) portal — deliberately not wp-admin. Its slug varies per site,
 * so each target reads the active site's configured slug (a key the [com.toolsboox.plugin.calendar.nw.SiteStore]
 * write-throughs into `ledgr_bridge_prefs`), falling back to a sensible default. Community/Courses
 * hang off `communitySite`; the rest off `site`. Mirrors iOS `App/SiteWebView.swift`.
 */
@AndroidEntryPoint
class SiteWebFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    /** One reachable portal: its title, the pref key holding this site's front slug, a default
     *  slug, an optional sub-route appended after the slug, and which base URL it hangs off. */
    private data class Target(
        val title: String, val slugKey: String, val defaultSlug: String,
        val suffix: String = "", val usesCommunitySite: Boolean = false
    )

    companion object {
        const val ARG_TARGET = "site_web_target"

        /** An explicit path off the active site's base (e.g. a wp-admin CRM deep link), opened in this
         *  same in-app WebView instead of one of the fixed forward-facing portals. Mirrors the iOS
         *  SiteWebTarget with a dynamic `defaultSlug`. When set, [ARG_TARGET] is ignored. */
        const val ARG_PATH = "site_web_path"
        const val ARG_TITLE = "site_web_title"

        /** An explicit BASE URL for [ARG_PATH] — the caller's own site, e.g. the Roster's
         *  theyoga.club CRM deep link — instead of the active site's `site` key. Part of the
         *  per-surface affinity pass: a surface bound to its own site must be able to open that
         *  site's pages without first making it globally active. Cookies are per-host in the
         *  shared WebView jar, so each site's login sticks independently. */
        const val ARG_BASE = "site_web_base"

        /** THE BROWSER ROOM (the EinkBro conversation, 09-02). Open-web mode: the title bar
         *  becomes the address bar (tap = go somewhere, hold = this page's verbs), a link CLICK
         *  records a rhizome edge (the trail is the history), and a link HOLD keeps it for
         *  later instead of opening a tab — same habit, same outcome, into the ecosystem.
         *  Everything is gram-pickable. */
        const val ARG_OPEN_WEB = "site_web_open"

        /** The Net panel's Bookmark seat, boxed while the page is bookmarked. */
        private const val BOOKMARK_ID = "bookmark"

        // Mirrors the SiteWebTarget statics in iOS App/SiteWebView.swift. The pref keys are the ones
        // SiteStore.activate() write-throughs (communityPortal/boardsPortal/bookingPortal/…).
        private val TARGETS = mapOf(
            "community" to Target("Community", "communityPortal", "portal", usesCommunitySite = true),
            "courses" to Target("Courses", "communityPortal", "portal", suffix = "courses", usesCommunitySite = true),
            "board" to Target("Board", "boardsPortal", "projects"),
            "booking" to Target("Booking", "bookingPortal", "booking"),
            "support" to Target("Support", "supportPortal", "support"),
            "crm" to Target("CRM", "crmPortal", ""),
            "shop" to Target("Shop", "shopPortal", "shop"),
        )
    }

    override val view = R.layout.fragment_site_web

    private lateinit var binding: FragmentSiteWebBinding
    private var target: Target = TARGETS.getValue("community")
    /** When present, an explicit path off the `site` base wins over the [target] portal. */
    private var explicitPath: String? = null
    /** When present, [explicitPath] rides off THIS base instead of the active site's. */
    private var explicitBase: String? = null
    /** Open-web browser mode ([ARG_OPEN_WEB]) — the room, not a portal. */
    private var openWeb = false
    /** The page the reader is ON, for the trail's from-side and the page verbs. */
    private var pageUrl: String? = null
    private var pageTouchDownX = 0f
    private var pageTouchDownY = 0f
    /** Enabled only while the WebView has history, so system back walks pages before popping. */
    private var webBackCallback: androidx.activity.OnBackPressedCallback? = null

    /** The Net's panel (open-web mode only), and whether its Bookmark seat is boxed right now —
     *  re-marked only when the page's bookmark state really changes (one cell, no column redraw). */
    private var netRail: com.toolsboox.ot.TuckPanel? = null
    private var bookmarkBoxed = false

    /**
     * The Net's own seats (spec §4.13; Michael 2026-10-11). The frame's Hub, Today and Nib and the
     * foot's Side are the shared doors ([setupPanel]); everything here is this room's.
     */
    private fun netSeat(s: com.toolsboox.ot.PanelSeats.Seat): com.toolsboox.ot.TuckPanel.Item? =
        when (s) {
            com.toolsboox.ot.PanelSeats.Seat.MORE ->
                com.toolsboox.ot.PanelFaces.item(s, "This page") { showPageVerbs() }
            com.toolsboox.ot.PanelSeats.Seat.PAGE_UP -> com.toolsboox.ot.PanelFaces.item(s) { pageWeb(-1) }
            com.toolsboox.ot.PanelSeats.Seat.PAGE_DOWN -> com.toolsboox.ot.PanelFaces.item(s) { pageWeb(1) }
            com.toolsboox.ot.PanelSeats.Seat.REFRESH ->
                com.toolsboox.ot.PanelFaces.item(s, "Reload this page") { binding.siteWebView.reload() }
            com.toolsboox.ot.PanelSeats.Seat.ADDRESS -> com.toolsboox.ot.PanelFaces.item(s) { promptAddress() }
            com.toolsboox.ot.PanelSeats.Seat.PILES -> com.toolsboox.ot.PanelFaces.item(s) { showMarksLauncher() }
            // ★ → ⚑: a standing address, boxed while this page is one (the flag means "remember
            // this page"; the ribbon is Later's).
            com.toolsboox.ot.PanelSeats.Seat.BOOKMARK ->
                com.toolsboox.ot.PanelFaces.item(s, activeId = BOOKMARK_ID) { toggleBookmark() }
            com.toolsboox.ot.PanelSeats.Seat.LISTEN -> com.toolsboox.ot.PanelFaces.item(s) { readPageAloud() }
            com.toolsboox.ot.PanelSeats.Seat.ANNOTATE -> com.toolsboox.ot.PanelFaces.item(s) { annotateSelection() }
            // ONE TAP keeps the page you're on for later (Michael, 09-13: "an easy save for later
            // from within our browser") — the Later List's own write path.
            com.toolsboox.ot.PanelSeats.Seat.LATER -> com.toolsboox.ot.PanelFaces.item(s) {
                val url = pageUrl ?: return@item
                keepLink(url, binding.siteWebView.title?.takeIf { it.isNotBlank() } ?: url)
            }
            else -> null
        }

    /** ⚑ Bookmark: this page as a standing address, or not. */
    private fun toggleBookmark() {
        val url = pageUrl ?: return
        val title = binding.siteWebView.title?.takeIf { it.isNotBlank() } ?: url
        val marked = bookmarks().any { it.second == url }
        setBookmark(title, url, keep = !marked)
        showMessage(if (marked) "⚑ Bookmark removed" else "⚑ Bookmarked", binding.root)
        boxBookmark(!marked)
    }

    /** Box the Bookmark seat while this page is bookmarked — only when that truth changes. */
    private fun boxBookmark(on: Boolean) {
        if (on == bookmarkBoxed) return
        bookmarkBoxed = on
        netRail?.markActive(if (on) BOOKMARK_ID else null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSiteWebBinding.bind(view)

        explicitPath = arguments?.getString(ARG_PATH)?.takeIf { it.isNotBlank() }
        explicitBase = arguments?.getString(ARG_BASE)?.takeIf { it.isNotBlank() }
        openWeb = arguments?.getBoolean(ARG_OPEN_WEB) == true
        target = TARGETS[arguments?.getString(ARG_TARGET)] ?: TARGETS.getValue("community")
        binding.siteWebTitle.text = when {
            openWeb -> "🌐  The Net \u00b7 where to?"
            explicitPath != null -> (arguments?.getString(ARG_TITLE)?.takeIf { it.isNotBlank() } ?: "Site")
            else -> target.title
        }
        if (openWeb) {
            binding.siteWebTitle.setOnClickListener { promptAddress() }
            binding.siteWebTitle.setOnLongClickListener { showPageVerbs(); true }
            // The header's Piles (the 🔖 launcher, now wearing the seat's monochrome face) shows only
            // in full screen, where the panel's Piles seat is away (review 2026-10-11, F3).
            binding.siteWebMarks.text = ""
            binding.siteWebMarks.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pf_piles, 0, 0, 0)
            binding.siteWebMarks.contentDescription = "Marks & piles"
            binding.siteWebMarks.setOnClickListener { showMarksLauncher() }
            // TOUCH PAGINATION (09-03: "the net browser needs… touch pagination"): tap the left
            // third to page up, the right third to page down — the reader's own tap grammar, so
            // e-ink readers turn the web page the way they turn a book. The middle stays live for
            // links. A JS scroll, instant (no smooth glide on e-ink).
            // FINGER SCROLL KEPT (Michael 2026-10-11, "the net stops scrolling with a finger"):
            // swallowing the edge-tap UP left the page believing a finger was still down, so it
            // began refusing drags. Hand it a CANCEL first so the touch always ends; actionMasked
            // so a second finger's pointer events never read as DOWN/UP.
            binding.siteWebView.setOnTouchListener { view, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && !view.canScrollHorizontally(1)) {
                    val x = ev.x; val w = view.width.toFloat()
                    val downMs = ev.eventTime - ev.downTime
                    val moved = kotlin.math.abs(ev.x - pageTouchDownX) > 40f ||
                        kotlin.math.abs(ev.y - pageTouchDownY) > 40f
                    if (downMs < 250 && !moved) {
                        val dir = when {
                            x < w * 0.28f -> -1
                            x > w * 0.72f -> 1
                            else -> 0
                        }
                        if (dir != 0) {
                            val cancel = android.view.MotionEvent.obtain(ev)
                            cancel.action = android.view.MotionEvent.ACTION_CANCEL
                            view.onTouchEvent(cancel)
                            cancel.recycle()
                            pageWeb(dir)
                            return@setOnTouchListener true
                        }
                    }
                } else if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    pageTouchDownX = ev.x; pageTouchDownY = ev.y
                }
                false
            }
            // Pull the kept bookmarks from the fleet on open (09-03: "needs to keep its
            // bookmarks list" — synced, so the list is the same on every device).
            com.toolsboox.plugin.calendar.ot.WebBookmarkStore.sync(requireContext())
            // THE TUCK PANEL (09-03: "the net doesn't have the tuck panel and bookmarks and
            // annotations…"), now in the ONE PANEL LANGUAGE (Michael 2026-10-11, the Net field
            // report: "Side changed on tuck not available. Icons lack design language."):
            //   ⛶ | Today · Nib · More | Page ↑ · Page ↓ · Refresh |
            //   Address · Piles · Bookmark · Listen · Annotate · Later | Side
            // Monochrome faces (no 🌐 ✍ 🔖 ⏫ ⏬), the side setting honoured, Side last. More is
            // this page's verbs (the title's hold, as before). ❝ Annotate reads the page's live
            // selection into the same medium chooser RSS uses. ☰ Hub left the column 2026-10-12
            // (Michael: "Those too.") — More's first row, and the left edge's Back once the page
            // trail is walked.
            // A rebuilt view starts with every seat unboxed, so the Bookmark box is re-told by the
            // next page finish (review 2026-10-11, F4: the flag outlived the view and the box never
            // came back after Hub › Back).
            bookmarkBoxed = false
            // The header's ⟳ and Piles are the panel's Refresh and Piles a second time while the
            // column shows (spec §4.13: the header keeps ‹ Back · title · ✕ Close) — and the only
            // way to them in full screen, where they come back so neither costs a second tap
            // (review 2026-10-11, F3). A portal keeps its ⟳ always — it has no panel.
            netRail = setupPanel(
                binding.siteWebRail, "thenet", com.toolsboox.ot.PanelSeats.Dress.NET,
                fullPillHost = binding.siteWebHeader,
                onFull = { full ->
                    if (::binding.isInitialized) {
                        val vis = if (full) View.VISIBLE else View.GONE
                        if (binding.siteWebReload.visibility != vis) binding.siteWebReload.visibility = vis
                        if (binding.siteWebMarks.visibility != vis) binding.siteWebMarks.visibility = vis
                    }
                },
            ) { s -> netSeat(s) }
        }

        binding.siteWebClose.setOnClickListener { findNavController().popBackStack() }
        binding.siteWebReload.setOnClickListener { binding.siteWebView.reload() }
        binding.siteWebBack.setOnClickListener {
            if (binding.siteWebView.canGoBack()) binding.siteWebView.goBack()
        }

        // System back walks the WEB history first — popping the whole fragment mid-login or
        // mid-checkout threw away the session's page trail. Enablement tracks canGoBack() (updated
        // in onPageFinished below); when the trail is empty the callback stays disabled and back
        // pops the fragment as before.
        val webBack = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                binding.siteWebView.goBack()
            }
        }
        // Through addOwnBackCallback: it stands above the left edge's ladder, so the page trail comes first.
        addOwnBackCallback(webBack)
        webBackCallback = webBack

        // Persistent cookies so a one-time WP login sticks across visits and app restarts.
        CookieManager.getInstance().setAcceptCookie(true)

        val web = binding.siteWebView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (!::binding.isInitialized) return
                binding.siteWebProgress.progress = newProgress
                binding.siteWebProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(
                v: WebView, request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                if (openWeb && adBlockOn() && isAdHost(request.url?.host)) {
                    // An empty 200 rather than a failure — a blocked tracker should vanish, not
                    // trip the page's error handlers.
                    return android.webkit.WebResourceResponse(
                        "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (::binding.isInitialized) binding.siteWebBack.alpha = if (v.canGoBack()) 1f else 0.3f
                webBackCallback?.isEnabled = v.canGoBack()
                // The highlight must survive the pen-nib tap on ✍ (the Onyx collapse race —
                // see WebSelectionBridge); The Net probed live and lost it.
                com.toolsboox.plugin.feeds.ot.WebSelectionBridge.wire(v)
                if (openWeb && url != null && url.startsWith("http")) {
                    pageUrl = url
                    if (::binding.isInitialized) binding.siteWebTitle.text =
                        v.title?.takeIf { it.isNotBlank() } ?: url
                    v.context.getSharedPreferences("browser_room", 0).edit()
                        .putString("last_url", url).apply()
                    applyEinkSkin(v)
                    // The Bookmark seat says whether this page is one (a box, Michael 2026-10-11).
                    if (::binding.isInitialized) boxBookmark(bookmarks().any { it.second == url })
                }
            }

            /** http(s) stays in the portal; everything else (mailto:, tel:, intent:, payment-app
             *  schemes…) hands off to whatever the device has for it instead of erroring in-view. */
            override fun shouldOverrideUrlLoading(
                v: WebView, request: android.webkit.WebResourceRequest
            ): Boolean {
                val uri = request.url ?: return false
                // A TRACKER'S OAUTH LANDING (09-13, comic trackers as extensions): the sign-in
                // page Suwayomi's client opened here redirects to `tachiyomi://…-auth` or
                // `suwayomi.org/tracker-oauth`; the whole URL is the token, handed to Suwayomi.
                val trackerId = com.toolsboox.plugin.reader.nw.TrackerBridge.pendingLogin(v.context)
                if (trackerId > 0 && com.toolsboox.plugin.reader.nw.TrackerBridge.isOAuthCallback(uri.toString())) {
                    val ctx = v.context.applicationContext
                    val url = uri.toString()
                    Thread {
                        val ok = runCatching {
                            val srv = com.toolsboox.plugin.reader.nw.TrackerBridge.server(ctx) ?: return@runCatching false
                            com.toolsboox.plugin.reader.nw.TrackerBridge.loginOAuth(srv, trackerId, url)
                        }.getOrDefault(false)
                        com.toolsboox.plugin.reader.nw.TrackerBridge.clearPendingLogin(ctx)
                        v.post { runCatching {
                            android.widget.Toast.makeText(v.context, if (ok) "📈 Tracker signed in" else "Tracker sign-in didn't take", android.widget.Toast.LENGTH_SHORT).show()
                            if (ok) requireActivity().onBackPressedDispatcher.onBackPressed()
                        } }
                    }.start()
                    return true
                }
                if (uri.scheme in listOf("http", "https")) {
                    // THE TRAIL IS THE HISTORY (Michael, 09-02: "we can follow the rhizomes
                    // back as link clicks"): a click in the browser room ties where you were
                    // to where you went. Main-frame clicks only — an iframe's plumbing is not
                    // a step you took.
                    val from = pageUrl
                    if (openWeb && request.isForMainFrame && from != null &&
                        from != uri.toString()) {
                        val ctx = v.context.applicationContext
                        val fromLabel = (v.title ?: from).take(60)
                        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
                            com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                                ctx, "web://$from", "web://$uri",
                                fromLabel = fromLabel, toLabel = uri.host ?: "$uri")
                        }
                    }
                    return false
                }
                return try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) {
                    // The view's own context: this can fire while the fragment is detaching.
                    android.widget.Toast.makeText(
                        v.context, "Nothing on this device opens ${uri.scheme}: links",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
        }

        // A link HELD is a link KEPT (the tab-replacement): file it for later, tag it, pick
        // it — without leaving the page you're on. Navigating is what a TAP is for.
        //
        // This used to be gated on `openWeb`, so holding a link did nothing on the community
        // portal and every other Fluent surface — the one room where you are most likely to be
        // reading something worth keeping. The room is the same WebView either way; the verbs
        // belong to the link, not to which door you came in through.
        web.setOnLongClickListener {
            val hit = web.hitTestResult
            val link = hit.extra
            if (link != null && link.startsWith("http") &&
                hit.type in listOf(WebView.HitTestResult.SRC_ANCHOR_TYPE,
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                showLinkVerbs(link); true
            } else false
        }

        val url = resolveUrl()
        if (url == null) {
            binding.siteWebView.visibility = View.GONE
            binding.siteWebEmpty.visibility = View.VISIBLE
        } else {
            web.loadUrl(url)
        }
    }

    /** base (communitySite | site, with the other as fallback) + slug (configured | default) + suffix.
     *  An [explicitPath] short-circuits this: it rides directly off the `site` base (wp-admin lives on
     *  the main site, not the community front), so callers can open any same-site page in-app. */
    // ── The browser room's verbs ──────────────────────────────────────────────────────────────

    /** Tap the title: go somewhere. A dot and no spaces reads as an address; anything else is a
     *  search, on DuckDuckGo's lite page — the one built like an e-ink page already. */
    /** The room's bookmarks — standing addresses (unlike 🔖 Keep's to-reads), sorted by name,
     *  stored as "title|url" lines in the room's own prefs ("we should sort bookmarks", 09-03). */
    private fun bookmarks(): List<Pair<String, String>> =
        com.toolsboox.plugin.calendar.ot.WebBookmarkStore.all(requireContext())
            .map { it.title to it.url }.sortedBy { it.first.lowercase() }

    private fun setBookmark(title: String, url: String, keep: Boolean) {
        com.toolsboox.plugin.calendar.ot.WebBookmarkStore.set(
            requireContext(), title.take(60), url, keep)
    }

    /** The field, and the bookmarks sorted beneath it — tap goes, hold removes. */
    private fun buildAddressBox(
        ctx: android.content.Context, input: android.widget.EditText, dismiss: () -> Unit
    ): android.view.View = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(48, 8, 48, 0)
        addView(input)
        val marks = bookmarks()
        if (marks.isEmpty()) return@apply
        addView(android.widget.TextView(ctx).apply {
            text = "⚑  Bookmarks"; textSize = 12f; setTextColor(0xFF666666.toInt())
            setPadding(0, 24, 0, 4)
        })
        val list = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        for ((t, u) in marks) list.addView(android.widget.TextView(ctx).apply {
            text = t; textSize = 15f; setTextColor(0xFF000000.toInt())
            setPadding(8, 14, 8, 14); isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { dismiss(); binding.siteWebView.loadUrl(u) }
            setOnLongClickListener {
                setBookmark(t, u, keep = false)
                (parent as? android.view.ViewGroup)?.removeView(this)
                android.widget.Toast.makeText(ctx, "⚑ Bookmark removed", android.widget.Toast.LENGTH_SHORT).show()
                if (u == pageUrl) boxBookmark(false)
                true
            }
        })
        addView(android.widget.ScrollView(ctx).apply { addView(list) },
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.35f).toInt()))
    }

    /** 🔖 THE LAUNCHER (Michael, 09-03: the bookmark icon "can bring up bookmarks, or the
     *  laterlist… we can load the later list in the reader there. It could also pull up the
     *  rss feeds, the watch, the listen from there" — less jumping around). One sheet: the
     *  bookmarks, the Later List's rows loading in THIS reader, and the three lens doors. */
    private fun showMarksLauncher() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val later = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.feeds.nw.LaterFeed.rows(ctx, null).take(12)
                }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            val rows = mutableListOf<Pair<String, () -> Unit>>()
            // ⚑ is the bookmark (the seat's flag); ★ now means only "Star to All Stars" (F8).
            for ((t, u) in bookmarks()) rows += "⚑  $t" to { binding.siteWebView.loadUrl(u) }
            for (e in later) {
                val u = e.url.ifBlank { e.mediaUrl.orEmpty() }
                if (u.isNotBlank()) rows += "🔖  ${e.title.take(60)}" to { binding.siteWebView.loadUrl(u) }
            }
            rows += (if (adBlockOn()) "🛡  Ad-blocker: on" else "🛡  Ad-blocker: off") to {
                requireContext().getSharedPreferences("browser_room", 0).edit()
                    .putBoolean("adblock", !adBlockOn()).apply()
                binding.siteWebView.reload()
                showMessage(if (adBlockOn()) "🛡 Ad-blocker on" else "Ad-blocker off", binding.root)
            }
            rows += (if (einkOn()) "◑  E-ink reading: on" else "◑  E-ink reading: off") to {
                requireContext().getSharedPreferences("browser_room", 0).edit()
                    .putBoolean("eink_skin", !einkOn()).apply()
                applyEinkSkin(binding.siteWebView)
                showMessage(if (einkOn()) "◑ White ground, black bold text" else "E-ink reading off", binding.root)
            }
            // The lens doors in the shared order, with the shared names (Michael 2026-10-11:
            // "the shared order everywhere") — the launcher names which doors, never their order.
            rows += com.toolsboox.plugin.feeds.ui.IncomingDoors.rows(mapOf(
                com.toolsboox.plugin.feeds.ui.IncomingDoors.Door.READ to { openLens("read") },
                com.toolsboox.plugin.feeds.ui.IncomingDoors.Door.WATCH to { openLens("watch") },
                com.toolsboox.plugin.feeds.ui.IncomingDoors.Door.LISTEN to { openLens("listen") },
            ))
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("🔖  Marks & piles")
                .setItems(rows.map { it.first }.toTypedArray()) { _, w -> rows[w].second() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** A lens door through the normal one ([com.toolsboox.plugin.feeds.ui.openFeedView]) — the
     *  same feed/kind/drawer-open it set by hand, plus the stale Later lane cleared. */
    private fun openLens(kind: String) {
        com.toolsboox.plugin.feeds.ui.openFeedView(findNavController(), "feed", kind)
    }

    private fun promptAddress() {
        val ctx = context ?: return
        val dlgHolder = arrayOfNulls<androidx.appcompat.app.AlertDialog>(1)
        val input = android.widget.EditText(ctx).apply {
            hint = "Address or search…"
            isSingleLine = true
            setText(pageUrl.orEmpty())
            setSelectAllOnFocus(true)
            // The keyboard's own key says Go and goes (Michael 2026-10-11, the Net field report:
            // "Keyboard isn't called" — the sheet showed a cursor and no keyboard).
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN
                // Any action key submits — Go, Done, Search, Send — whatever this keyboard draws
                // for imeOptions (review 2026-10-11, #10); IME_NULL only with a hardware Enter.
                if (actionId != android.view.inputmethod.EditorInfo.IME_NULL || enter) {
                    goTo(text.toString())
                    dlgHolder[0]?.dismiss()
                    true
                } else false
            }
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(com.toolsboox.plugin.feeds.ui.IncomingDoors.Door.THE_NET.label)
            .setView(buildAddressBox(ctx, input) { dlgHolder[0]?.dismiss() })
            .setPositiveButton("Go") { _, _ -> goTo(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlgHolder[0] = dialog
        // CALL THE KEYBOARD: the field takes focus the moment the sheet is up, and the window asks
        // for the soft keyboard (an AlertDialog leaves it hidden until the field is tapped).
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            input.requestFocus()
            input.postDelayed({
                if (!input.isAttachedToWindow) return@postDelayed
                val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                val shown = imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) == true
                // Belt and braces for IMEs that ignore an implicit ask from a dialog: the window's
                // own insets controller asks for the IME by type.
                if (!shown) dialog.window?.let {
                    androidx.core.view.WindowCompat.getInsetsController(it, input)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
            }, 120)
        }
        dialog.show()
    }

    /** Go: an address when it reads as one (a scheme, or a dot and no spaces), else a search on
     *  DuckDuckGo's lite page — the one built like an e-ink page already. */
    private fun goTo(raw: String) {
        val q = raw.trim()
        if (q.isBlank() || !::binding.isInitialized) return
        val dest = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            !q.contains(' ') && q.contains('.') -> "https://$q"
            else -> "https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        }
        binding.siteWebView.loadUrl(dest)
    }

    /** Hold the title: this PAGE's verbs — keep, pick, tag, trail. Every surface is a source
     *  for gram picks; the browser is no exception. */
    private fun showPageVerbs() {
        val ctx = context ?: return
        // More on the Net's panel (Michael 2026-10-11): it always opens — before a page has loaded
        // it carries only ☐ Tool labels, every panel's More row.
        val labels = toolLabelsRow(netRail)
        val labelsRow: Pair<String, () -> Unit> = "${labels.emoji}  ${labels.label}" to labels.action
        // ☰ Hub, the first row (Michael 2026-10-12): the column's Hub seat went — this row and the
        // left edge's Back (after the page's own history) are the way to the main menu now.
        val hubRow: Pair<String, () -> Unit> = "☰  Hub" to { openHub() }
        val url = pageUrl ?: run {
            val first = listOf(hubRow, labelsRow)
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("The Net")
                .setItems(first.map { it.first }.toTypedArray()) { _, w -> first[w].second() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val title = binding.siteWebView.title?.takeIf { it.isNotBlank() } ?: url
        val marked = bookmarks().any { it.second == url }
        // (🔖 Keep for later left this list: the panel's Later seat is the same keep, one tap —
        // spec §4.13, both apps.)
        val rows = listOf(
            hubRow,
            (if (marked) "⚑  Unbookmark" else "⚑  Bookmark this page") to {
                setBookmark(title, url, keep = !marked)
                showMessage(if (marked) "⚑ Bookmark removed" else "⚑ Bookmarked", binding.root)
                boxBookmark(!marked)
            },
            "⭐  Star to All Stars" to {
                val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = runCatching {
                        val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                            url = url, title = title, kind = "read", sourceName = host, excerpt = "")
                        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                            calendarDayService, documentsRoot(), face, java.time.LocalDate.now(),
                            pageKey = com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.INTAKE_PAGE,
                            sourceLink = "web://$url", sourceLabel = host, cardText = title,
                            intakeKind = "read")
                        true
                    }.getOrDefault(false)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        context?.let { showMessage(if (ok) "⭐ On All Stars" else "Couldn't star it", binding.root) }
                    }
                }
            },
            "⧉  Copy the link" to { copyOut(url, "Link") },
            "☑  Make a task" to { taskFromLink(url, title) },
            "📆  Make an event" to { eventFromLink(url, title) },
            "📕  Save as a book" to { savePageAsBook(url, title) },
            "◈  Pick this page" to { pickPage(url, title) },
            "🏷  Tag it…" to {
                com.toolsboox.ot.TagPicker.show(ctx, "web://$url", title,
                    showModal = { showModal(it) })
            },
            "🔖  Links here \u2192 Later" to { harvestLinksToLater() },
            "🕸  Its trail" to {
                findNavController().navigate(
                    R.id.action_to_ledger_rhizome,
                    androidx.core.os.bundleOf(
                        LedgerRhizomeFragment.ARG_URI to "web://$url",
                        LedgerRhizomeFragment.ARG_LABEL to title.take(60)))
                Unit
            },
            labelsRow,
        )
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title.take(80))
            .setItems(rows.map { it.first }.toTypedArray()) { _, w -> rows[w].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Hold a link: the kept-not-tabbed menu. */
    private fun showLinkVerbs(link: String) {
        val ctx = context ?: return
        val label = link.substringAfter("://").take(80)
        val host = runCatching { java.net.URI(link).host }.getOrNull().orEmpty()
        // The same ladder the page itself offers, aimed at the held link — so a thing worth
        // keeping can be kept the same way wherever you meet it.
        val rows = listOf(
            "🔖  Keep for later" to { keepLink(link, label) },
            "◈  Pick it" to { pickPage(link, label) },
            "⭐  Star to All Stars" to { starLink(link, label) },
            "🏷  Tag it…" to {
                com.toolsboox.ot.TagPicker.show(ctx, "web://$link", label, showModal = { showModal(it) })
            },
            "🕸  Its trail" to {
                findNavController().navigate(
                    R.id.action_to_ledger_rhizome,
                    androidx.core.os.bundleOf(
                        LedgerRhizomeFragment.ARG_URI to "web://$link",
                        LedgerRhizomeFragment.ARG_LABEL to label.take(60)))
                Unit
            },
            "⧉  Copy the link" to { copyOut(link, "Link") },
            "☑  Make a task" to { taskFromLink(link, label) },
            "📆  Make an event" to { eventFromLink(link, label) },
            "↗  Go" to { binding.siteWebView.loadUrl(link); Unit },
        )
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(link.take(80))
            .setItems(rows.map { it.first }.toTypedArray()) { _, w -> rows[w].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 📕 The EinkBro crown jewel, Ledgable-shaped: the page becomes an EPUB on the
     *  Bookshelf under Web/ — carried by the library hub, annotatable, resumable. The page's
     *  own HTML is lifted from the live document; images fetched in beside it. */
    private fun savePageAsBook(url: String, title: String) {
        val appCtx = requireContext().applicationContext
        binding.siteWebView.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML;})()"
        ) { raw ->
            val html = runCatching {
                org.json.JSONTokener(raw).nextValue() as? String
            }.getOrNull().orEmpty()
            if (html.isBlank()) {
                showMessage("Couldn't read the page.", binding.root); return@evaluateJavascript
            }
            showMessage("📕 Binding \u201c${title.take(40)}\u201d\u2026", binding.root)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val safe = title.replace(Regex("[^A-Za-z0-9 ._\\-]"), "_").trim().take(70)
                    .ifBlank { "page" }
                val ok = runCatching {
                    com.toolsboox.plugin.reader.ui.BookshelfSource.writeInto(
                        appCtx, "Web", "$safe.epub"
                    ) { out ->
                        com.toolsboox.ot.WebEpub.write(appCtx, out, title, url, html)
                    }
                }.getOrDefault(false)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (context != null) showMessage(
                        if (ok) "📕 On the shelf \u00b7 Web/$safe" else "The binding failed.",
                        binding.root)
                }
            }
        }
    }

    // ── E-ink reading skin (09-03: "the EinkBro features of removing background color and
    // setting text to black or bold… like an e-ink version") — white ground, black text, bold,
    // no images-as-noise kept but their boxes calmed. A style tag we (re)inject each page load;
    // toggled in the launcher. Idempotent by id.
    private fun einkOn(): Boolean =
        requireContext().getSharedPreferences("browser_room", 0).getBoolean("eink_skin", false)

    private fun applyEinkSkin(v: WebView) {
        val css = if (einkOn())
            "*{background:#fff !important;background-image:none !important;color:#000 !important;" +
            "text-shadow:none !important;box-shadow:none !important;border-color:#000 !important;}" +
            "p,li,span,div,a,td,th,h1,h2,h3,h4,h5,h6{font-weight:700 !important;}" +
            "a{text-decoration:underline !important;}"
        else ""
        val js = "(function(){var id='__ledger_eink';var e=document.getElementById(id);" +
            "if(!e){e=document.createElement('style');e.id=id;document.head.appendChild(e);}" +
            "e.textContent=" + org.json.JSONObject.quote(css) + ";})()"
        v.evaluateJavascript(js, null)
    }

    /** One viewport step of the web page — instant on e-ink, the reader's page-turn feel. */
    private fun pageWeb(dir: Int) {
        binding.siteWebView.evaluateJavascript(
            "window.scrollBy({top: window.innerHeight * 0.92 * $dir, behavior: 'instant'})", null)
    }

    // ── Ad-blocker (09-03: "the net browser needs… ad blocker stuff") ─────────────────────────
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "adnxs.com",
        "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "scorecardresearch.com", "quantserve.com", "moatads.com", "adsafeprotected.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "advertising.com",
        "facebook.net", "connect.facebook.net", "hotjar.com", "mixpanel.com", "segment.com",
        "branch.io", "adcolony.com", "applovin.com", "chartbeat.com")

    private fun adBlockOn(): Boolean =
        requireContext().getSharedPreferences("browser_room", 0).getBoolean("adblock", true)

    private fun isAdHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return adHosts.any { h == it || h.endsWith(".$it") }
    }

    /** ▶ READ ALOUD (09-03) — the page's readable text into the shared TTS transport. */
    private fun readPageAloud() {
        binding.siteWebView.evaluateJavascript(
            "(function(){var a=document.querySelector('article')||document.body;" +
            "return a?a.innerText:'';})()"
        ) { raw ->
            val text = runCatching {
                org.json.JSONTokener(raw).nextValue() as? String
            }.getOrNull()?.trim().orEmpty()
            if (text.length < 40) { showMessage("Not much to read on this page.", binding.root); return@evaluateJavascript }
            val title = binding.siteWebView.title?.takeIf { it.isNotBlank() } ?: "The Net"
            com.toolsboox.ui.plugin.LedgerPlayer.start(requireContext(), title, null, null, text)
            com.toolsboox.plugin.feeds.ui.NowPlayingCard.summon(requireActivity())
            showMessage("▶ Reading aloud", binding.root)
        }
    }

    /** ❝ ANNOTATE — the page's live selection into the SAME medium chooser RSS uses
     *  (highlight → save/handwrite/ask/educate/pick), the page standing as the article. */
    private fun annotateSelection() {
        // The whole canon rides here now: the recorders used to be a stub ("Recording lives on
        // the note surfaces") and nothing was logged, so a highlight kept from The Net never
        // reached the reading log or the fleet. captureAvGramDirect is the same shared capture
        // every other surface hands the chooser; the ReadingEvent files the page as an ARTICLE
        // with its host standing as the feed.
        com.toolsboox.plugin.feeds.ot.WebSelectionBridge.probe(binding.siteWebView) { sel ->
            val url = pageUrl.orEmpty()
            val title = binding.siteWebView.title?.takeIf { it.isNotBlank() } ?: url
            val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "The Net"
            com.toolsboox.plugin.feeds.ot.FeedNoteGram.show(
                this, calendarDayService, documentsRoot(),
                selection = sel, articleTitle = title,
                feedTitle = host, articleUrl = url,
                captureAv = { kind, sink -> captureAvGramDirect(kind, sink) },
                logEvent = { excerpt, note -> logPageEvent(title, host, url, excerpt, note) },
                // Star it: the page onto All Stars' READ band, the shape a starred article
                // takes, so a page from The Net sits on the register beside the feeds' stars.
                star = {
                    Thread {
                        runCatching {
                            com.toolsboox.plugin.feeds.ot.FeedNoteGram.placeStarGram(
                                this, calendarDayService, documentsRoot(),
                                title = title, feedTitle = host, url = url, kind = "read",
                                imageUrl = null, excerpt = sel.take(400))
                        }
                    }.apply { isDaemon = true }.start()
                }
            )
        }
    }

    /** The page's ReadingEvent: a highlight or note kept from The Net joins the reading log
     *  (and syncs) exactly as an article's does, kind ARTICLE, the host standing as the feed. */
    private fun logPageEvent(title: String, host: String, url: String, excerpt: String?, note: String?) {
        if (excerpt.isNullOrBlank() && note.isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = documentsRoot()
                val today = java.time.LocalDate.now()
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    // Throws (inside the runCatching) rather than save over a today-file that won't read.
                    val day = calendarDayService.loadForWriteOrThrow(root, today, null, java.util.Locale.getDefault())
                    day.readingEvents.add(
                        com.toolsboox.plugin.calendar.da.v2.ReadingEvent(
                            id = "net-${java.util.UUID.randomUUID().toString().take(8)}",
                            kind = com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.ARTICLE,
                            date = java.util.Date(), title = title,
                            source = host, url = url.ifBlank { null },
                            excerpt = excerpt?.ifBlank { null }, note = note?.ifBlank { null }
                        )
                    )
                    calendarDayService.save(root, today, day)
                }
            }
        }
    }

    /** 🔗 Every anchor on the page, filed to Later (09-03: "send all links to later list…
     *  emails, rss, and the net"). Capped kindly; the toast counts what landed. */
    private fun harvestLinksToLater() {
        val appCtx = requireContext().applicationContext
        binding.siteWebView.evaluateJavascript(
            "JSON.stringify([...document.querySelectorAll('a[href^=http]')]" +
                ".map(a=>[a.textContent.trim().slice(0,80),a.href]))"
        ) { raw ->
            val pairs = runCatching {
                val arr = org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as String)
                (0 until arr.length()).map { i ->
                    arr.getJSONArray(i).let { it.getString(0) to it.getString(1) }
                }
            }.getOrDefault(emptyList())
            if (pairs.isEmpty()) { showMessage("No links found", binding.root); return@evaluateJavascript }
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                var n = 0
                for ((t, u) in pairs.distinctBy { it.second }.take(40)) {
                    runCatching {
                        com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                            appCtx, java.time.LocalDate.now(), "read", u, t.ifBlank { u })
                        n++
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    context?.let { showMessage("\ud83d\udd17 $n links \u2192 Later", binding.root) }
                }
            }
        }
    }

    /** 🔖 The tab-replacement itself: the link into the Later List, where the promise to come
     *  back actually gets kept. */
    private fun keepLink(link: String, title: String) {
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                    appCtx, java.time.LocalDate.now(), "read", link, title)
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                context?.let { showMessage("🔖 Kept for later", binding.root) }
            }
        }
    }

    /** ◈ The page's link-face into All Picks — the same door a letter walks through. */
    /** ⭐ A held link onto All Stars — the same placement the page's own star performs, aimed
     *  at the link under the finger rather than the page around it. */
    private fun starLink(link: String, title: String) {
        val appCtx = requireContext().applicationContext
        val host = runCatching { java.net.URI(link).host }.getOrNull().orEmpty()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                    url = link, title = title, kind = "read", sourceName = host, excerpt = "")
                com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                    calendarDayService, documentsRoot(), face, java.time.LocalDate.now(),
                    pageKey = com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake.INTAKE_PAGE,
                    sourceLink = "web://$link", sourceLabel = host, cardText = title,
                    intakeKind = "read")
                true
            }.getOrDefault(false)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                context?.let { showMessage(if (ok) "⭐ On All Stars" else "Couldn't star it", binding.root) }
            }
        }
    }

    /**
     * ☑ A task from a link — the piece the link-verbs handoff left out on purpose ("a row that
     * half-works is worse than an absent one"). This is the log's real path
     * (ReadingLogFragment.makeTaskFromLogItem), aimed at a link: a TASK LedgerItem on today,
     * deduped by its words, pushed to the fleet; the link rides in the task's text so the task
     * can be followed back. No picture — a link has none to hand.
     */
    private fun taskFromLink(link: String, title: String) {
        val ctx = context ?: return
        val words = title.trim().ifBlank { link.substringAfter("://") }
            .let { if (it.length > 120) it.take(119).trimEnd() + "…" else it }
        val text = "$words\n$link"
        val task = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
            id = "web-" + java.util.UUID.randomUUID().toString().lowercase(),
            kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
            text = text, date = java.util.Date(), crop = null,
            display = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Display.TEXT,
            source = "web", stage = "todo")
        lifecycleScope.launch {
            // null = today's file is there but won't read: nothing written, nothing pushed.
            val already: Boolean? = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val today = java.time.LocalDate.now()
                    val root = documentsRoot()
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                        val day = calendarDayService.loadForWrite(root, today, null, java.util.Locale.getDefault())
                            ?: return@withDay null
                        if (com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe.containsTask(day.ledgerItems, text)) true
                        else { day.ledgerItems.add(task); calendarDayService.save(root, today, day); false }
                    }
                }.getOrDefault(false)
            }
            if (already == null) {
                if (isAdded) showMessage("Couldn't make the task — today's page won't read", binding.root)
                return@launch
            }
            if (!already) withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, task) }
            }
            if (isAdded) showMessage(if (already) "🗒 Already on today" else "🗒 Task made on today", binding.root)
        }
    }

    /**
     * 📆 An event from a link — the last of "grams, picks, tasks and events on anything under the
     * Fluent banner". The items screen's own entry (LedgerItemsFragment.showItemEntry → createManual)
     * aimed at a link: title prefilled, a Date button, a ＋ Time button; the EVENT LedgerItem lands
     * in THAT day's file (noon UTC, the same date convention) with the link in its text, and rides
     * to Google Calendar through LedgerEventSync when the calendar is connected.
     */
    private fun eventFromLink(link: String, title: String) {
        val ctx = context ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(ctx).apply {
            setText(title.trim().ifBlank { link.substringAfter("://").take(80) }); setSingleLine(false); maxLines = 3
        }
        var chosenTime: String? = null
        val timeButton = android.widget.Button(ctx).apply {
            text = "＋ Time"; isAllCaps = false; textSize = 15f
            setBackgroundResource(R.drawable.dialog_rounded_bg)
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0); addView(input)
            addView(timeButton, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad })
        }
        timeButton.setOnClickListener {
            val now = java.time.LocalTime.now()
            android.app.TimePickerDialog(ctx, { _, h, m -> chosenTime = "%02d:%02d".format(h, m); timeButton.text = "🕓  $chosenTime" },
                now.hour, now.minute, true).show()
        }
        var due = java.time.LocalDate.now()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("New event").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val t = input.text.toString().trim(); if (t.isEmpty()) return@setPositiveButton
                val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                    id = "li-" + java.util.UUID.randomUUID().toString().lowercase(),
                    kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT,
                    text = "$t\n$link",
                    date = java.util.Date(due.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()),
                    time = chosenTime, source = "web")
                val appCtx = ctx.applicationContext
                lifecycleScope.launch {
                    val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val root = documentsRoot()
                            com.toolsboox.plugin.calendar.ot.DayLocks.withDay(due) {
                                val cd = calendarDayService.loadForWriteOrThrow(root, due, null, java.util.Locale.getDefault())
                                cd.ledgerItems.add(item)
                                calendarDayService.save(root, due, cd)
                            }
                            runCatching { com.toolsboox.plugin.calendar.nw.LedgerEventSync.pushEvent(appCtx, item) }
                            true
                        }.getOrDefault(false)
                    }
                    if (isAdded) showMessage(if (ok) "📆 Event on ${due.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}" +
                        (chosenTime?.let { " · $it" } ?: "") else "Couldn't make the event", binding.root)
                }
            }
            .setNeutralButton("Date", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                android.app.DatePickerDialog(ctx, { _, y, m, d ->
                    due = java.time.LocalDate.of(y, m + 1, d)
                    dialog.setTitle("New event · ${due.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}")
                }, due.year, due.monthValue - 1, due.dayOfMonth).show()
            }
        }
        showModal(dialog)
    }

    /** ⧉ Out to the system clipboard, so a link can leave the room for a message or a browser. */
    private fun copyOut(text: String, what: String) {
        val ctx = context ?: return
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger", text))
        showMessage("⧉ $what copied", binding.root)
    }

    private fun pickPage(link: String, title: String) {
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                val host = java.net.URI(link).host ?: ""
                val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                    url = link, title = title, kind = "web", sourceName = host, excerpt = "")
                com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                    calendarDayService, documentsRoot(), face, java.time.LocalDate.now(),
                    pageKey = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS,
                    sourceLink = "web://$link", sourceLabel = host, cardText = title)
                true
            }.getOrDefault(false)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                context?.let { showMessage(if (ok) "◈ In All Picks" else "Couldn't pick it", binding.root) }
            }
        }
    }

    private fun resolveUrl(): String? {
        if (openWeb) {
            val last = requireContext().getSharedPreferences("browser_room", 0)
                .getString("last_url", null)
            if (last == null) binding.siteWebTitle.post { promptAddress() }
            return last ?: "https://lite.duckduckgo.com/lite/"
        }
        val p = prefs()
        explicitPath?.let { path ->
            val site = explicitBase?.trim()?.trimEnd('/')
                ?: (p.getString("site", "") ?: "").trim()
                    .ifBlank { (p.getString("communitySite", "") ?: "").trim() }
            if (site.isBlank()) return null
            val base = if (site.endsWith("/")) site else "$site/"
            return base + path.trimStart('/')
        }
        val primaryKey = if (target.usesCommunitySite) "communitySite" else "site"
        val fallbackKey = if (target.usesCommunitySite) "site" else "communitySite"
        val primary = (p.getString(primaryKey, "") ?: "").trim()
        val fallback = (p.getString(fallbackKey, "") ?: "").trim()
        val siteUrl = (primary.ifBlank { fallback }).trim()
        if (siteUrl.isBlank()) return null
        val base = if (siteUrl.endsWith("/")) siteUrl else "$siteUrl/"
        val stored = (p.getString(target.slugKey, "") ?: "").trim().trim('/')
        val slug = stored.ifBlank { target.defaultSlug }
        val path = when {
            target.suffix.isBlank() -> slug
            slug.isBlank() -> target.suffix
            else -> "$slug/${target.suffix}"
        }
        return base + path
    }

    private fun prefs() = EncryptedSharedPreferences.create(
        requireContext(), "ledgr_bridge_prefs",
        MasterKey.Builder(requireContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun showLoading() {}
    override fun hideLoading() {}

    override fun onDestroyView() {
        webBackCallback = null
        netRail = null   // the panel goes with the view; a box set meanwhile has nowhere to land
        if (::binding.isInitialized) binding.siteWebView.destroy()
        super.onDestroyView()
    }
}
