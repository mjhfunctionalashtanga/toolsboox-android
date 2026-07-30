package com.toolsboox.plugin.calendar.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentPostsBrowserBinding
import com.toolsboox.plugin.calendar.nw.LedgerSite
import com.toolsboox.plugin.calendar.nw.SiteStore
import com.toolsboox.plugin.calendar.nw.WPPublish
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Browse posts (any type) by status — drafts, scheduled, published, private, trash — across EVERY
 * configured WordPress site at once, or narrowed to one. Mirrors how [MailInboxFragment] treats the
 * inbox: a "🌐 All sites" accordion at the top aggregates the per-site lists (each row tagged with
 * its site), and picking a site narrows; the choice survives the trip away. Opening a post or
 * composing hands off to [PublishFragment]; trashing and editing operate on the row's OWN site.
 *
 * The read/manage side of Publish; mirrors iOS `PostsBrowserView.swift` (iOS multi-site parity is a
 * separate follow-up). When "All", the per-site fetches fan out in parallel with per-site try/catch,
 * so one unreachable site never empties the others.
 */
@AndroidEntryPoint
class PostsBrowserFragment @Inject constructor() : ScreenFragment() {

    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    @Inject
    lateinit var calendarPatternService: com.toolsboox.plugin.calendar.fi.CalendarPatternService

    override val view = R.layout.fragment_posts_browser
    private lateinit var binding: FragmentPostsBrowserBinding

    private var types: List<WPPublish.PostType> = emptyList()
    private var type = "posts"
    private var filterKey = "all"

    // The almanac anchor and how wide its window is. MONTH rather than day, because this pane opens
    // on "what have I got" and not "what is today": a day-wide window would meet you with an empty
    // list on every day you didn't publish, which is most of them. The Type and Show pickers stay —
    // neither is a date filter, and "drafts" is a different question from "July" — but WHEN is the
    // strip's now, where before this pane had no answer to it at all.
    private var navBar: CalendarNavBarHost? = null
    private var navPeriod: String = "month"
    private var anchor: java.time.LocalDate = java.time.LocalDate.now()
    private var items: List<com.toolsboox.plugin.calendar.ui.SiteFetch.SitePost> = emptyList()
    private var loading = false
    /** Bumped by every fetch; only the newest one is allowed to paint. See [reload]. */
    private var loadSeq = 0

    // Which site the list is narrowed to (null = every configured site). Persisted, so the browser
    // reopens the way it was left — the aggregate stays the default, the narrowing a kept choice.
    // Same prefs shape as MailInboxFragment's account_filter / accounts_open.
    private var siteFilter: String? = null
    private var sitesOpen = false
    private fun uiPrefs() = requireContext().getSharedPreferences("ledger_posts_browser", 0)

    private data class Filter(val key: String, val label: String, val statuses: List<String>)

    private val filters = listOf(
        Filter("all", "All", listOf("publish", "future", "draft", "pending", "private")),
        Filter("draft", "Drafts", listOf("draft", "pending")),
        Filter("future", "Scheduled", listOf("future")),
        Filter("publish", "Published", listOf("publish")),
        Filter("private", "Private", listOf("private")),
        Filter("trash", "Trash", listOf("trash")),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPostsBrowserBinding.bind(view)
        binding.postsClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.postsCompose.setOnClickListener {
            NavHostFragment.findNavController(this).navigate(R.id.action_to_publish)
        }
        siteFilter = uiPrefs().getString("site_filter", "")!!.ifBlank { null }
        sitesOpen = uiPrefs().getBoolean("sites_open", false)

        // Passing onSelectPeriod is what makes the strip a FILTER rather than a way out of the
        // pane: a slot tap scopes the list in place instead of jumping to that period's calendar
        // page (see CalendarNavBarHost.select). The arrows step by the active window, so a month
        // steps a month — the thing a bespoke ‹ › pair always gets wrong.
        navBar = CalendarNavBarHost(requireContext(), binding.postsNavigator, this,
            onStepDay = { d -> anchor = d; reload(force = true) },
            onSelectPeriod = { period, d -> navPeriod = period; anchor = d; reload(force = true) })
        navBar?.setGranularity(navPeriod)
        reload()   // draws the strip on its way past — see renderNav()
    }

    /** Redraw the Almanac strip for the anchor (dots for filled days), as every hosting surface does. */
    private fun renderNav() {
        val bar = navBar ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val root = documentsRoot()
            val loc = java.util.Locale.getDefault()
            val at = anchor
            val (day, pat) = withContext(Dispatchers.IO) {
                val cd = runCatching { calendarDayService.load(root, at, null, loc) }.getOrNull()
                    ?: com.toolsboox.plugin.calendar.da.v2.CalendarDay(
                        at.year, at.monthValue, at.dayOfMonth, startHour = null)
                cd to runCatching { calendarPatternService.load(root, at, loc) }.getOrNull()
            }
            // A missing pattern must not kill the strip: an unrendered CalendarNavBarHost never
            // sets its currentDay, and a bar with no currentDay swallows every touch.
            val safePat = pat ?: com.toolsboox.plugin.calendar.da.v1.CalendarPattern(at.year, loc).fill()
            if (isAdded) bar.render(day, safePat)
        }
    }

    /** Inclusive day range for the active window — the same shape [LedgerItemsFragment] uses. */
    private fun periodRange(): Pair<java.time.LocalDate, java.time.LocalDate> = when (navPeriod) {
        "week" -> {
            val s = anchor.with(java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).dayOfWeek(), 1)
            s to s.plusDays(6)
        }
        "month" -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        "quarter" -> {
            val s = anchor.withMonth((anchor.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
            val e = s.plusMonths(2)
            s to e.withDayOfMonth(e.lengthOfMonth())
        }
        "year" -> java.time.LocalDate.of(anchor.year, 1, 1) to java.time.LocalDate.of(anchor.year, 12, 31)
        else -> anchor to anchor
    }

    /**
     * The window as WP's `after`/`before` pair, in the site's wall-clock shape.
     *
     * `after` is exclusive on WP's side, so the lower bound goes out by a second — a post published
     * at the stroke of midnight belongs to the window it starts, not the one before it.
     */
    private fun windowBounds(): Pair<String, String> {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val (start, end) = periodRange()
        return start.atStartOfDay().minusSeconds(1).format(fmt) to end.plusDays(1).atStartOfDay().format(fmt)
    }

    /**
     * Is this post inside the window? The fetch already asked for the range, but WP compares
     * `after`/`before` against the SITE's wall clock, so on a site in another zone the edge sits a
     * few hours out — the range is how the right posts get fetched, this is what decides the window.
     *
     * A post whose date won't parse is KEPT rather than hidden: a floating draft with no date isn't
     * in some other month, it's nowhere, and this browser is the only way back to it.
     */
    private fun inWindow(p: WPPublish.WpPost): Boolean {
        val d = runCatching { java.time.LocalDate.parse(p.date.take(10)) }.getOrNull() ?: return true
        val (start, end) = periodRange()
        return !d.isBefore(start) && !d.isAfter(end)
    }

    override fun onResume() {
        super.onResume()
        // A publish/edit/trash happens in a pushed destination (which may have re-pointed the active
        // site); coming back should re-fetch so the change shows. The loading guard collapses the
        // harmless double-fire with onViewCreated's first load.
        reload()
    }

    /** The site the type list + the narrowed fetch reference: the narrowed site, else the active one. */
    private fun sitesInScope(): List<LedgerSite> {
        val all = SiteStore.all(requireContext())
        val f = siteFilter
        return if (f != null) all.filter { it.id == f } else all
    }

    private fun referenceSite(): LedgerSite? {
        val ctx = requireContext()
        val scope = sitesInScope()
        return scope.firstOrNull { it.id == siteFilter } ?: SiteStore.active(ctx) ?: scope.firstOrNull()
    }

    private fun typeName(rest: String) = types.firstOrNull { it.restBase == rest }?.name ?: rest

    /**
     * @param force fetch even while one is in flight. The `loading` guard exists to collapse the
     *   harmless double-fire of onViewCreated + onResume; a move of the almanac is not that, and
     *   swallowing one would leave the strip pointing at a month the list has never been asked for.
     *   Two fetches in flight are settled by [loadSeq] — the last one asked for is the one that
     *   lands, so a slow month arriving after you stepped on cannot stamp itself over the pane.
     */
    private fun reload(force: Boolean = false) {
        val ctx = context ?: return
        renderNav()
        if (loading && !force) return
        loading = true
        val seq = ++loadSeq
        render()
        // The view's scope: this exists only to draw the list; a back-navigation mid-fetch cancels
        // the render rather than ghost-writing into a dead view.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val scope = sitesInScope()
                val statuses = filters.first { it.key == filterKey }.statuses
                // Post types come from a single reference site (posts/pages are universal; a CPT the
                // chosen type names simply returns nothing on a site that lacks it).
                val ref = referenceSite()
                if (types.isEmpty() && ref != null) {
                    types = withContext(Dispatchers.IO) { SiteFetch.postTypes(ref, SiteStore.password(ctx, ref.id)) }
                    if (types.isNotEmpty() && types.none { it.restBase == type }) type = types.first().restBase
                }
                // The window scopes the FETCH as well as the list: every site sends only its thirty
                // most recent posts of a type, so a step back to last month would otherwise land on
                // an empty pane with the posts sitting right there, never asked for.
                val (after, before) = windowBounds()
                // Fan out across the sites in scope, each with its own creds, in parallel — one bad
                // site's failure is caught to an empty list, never emptying the others.
                val merged = withContext(Dispatchers.IO) {
                    coroutineScope {
                        scope.map { s ->
                            async {
                                try {
                                    SiteFetch.listPosts(s, SiteStore.password(ctx, s.id), type, statuses,
                                        after = after, before = before)
                                        .map { SiteFetch.SitePost(s, it) }
                                } catch (e: Exception) { emptyList() }
                            }
                        }.awaitAll().flatten()
                    }
                }
                // An overtaken fetch drops its answer rather than painting it: it was asked about a
                // window nobody is looking at any more.
                if (seq != loadSeq) return@launch
                items = merged.filter { inWindow(it.post) }.sortedByDescending { it.post.date }
                if (isAdded) { loading = false; render() }
            } finally {
                // Also on cancellation — a wedged flag here would refuse every future reload.
                loading = false
            }
        }
    }

    private fun render() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val c = binding.postsContainer
        c.removeAllViews()

        // The site switcher, exactly as Mail's account switcher — All aggregates, a pick narrows.
        c.addView(
            SiteSwitcherBar.build(
                context = ctx,
                sites = SiteStore.all(ctx),
                filter = siteFilter,
                open = sitesOpen,
                onToggleOpen = { open ->
                    sitesOpen = open; uiPrefs().edit().putBoolean("sites_open", open).apply(); render()
                },
                onPick = { id ->
                    siteFilter = id
                    uiPrefs().edit().putString("site_filter", id ?: "").apply()
                    types = emptyList()   // the reference site changed — re-derive its types
                    reload()
                },
                onManage = { SitesSettingsDialog.show(ctx) { types = emptyList(); reload() } },
            )
        )

        // Type + status controls
        c.addView(TextView(ctx).apply {
            text = "Type:  ${typeName(type)}   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(4), px(6), px(4), px(6)); setOnClickListener { pickType() }
        })
        val filterLabel = filters.first { f -> f.key == filterKey }.label
        c.addView(TextView(ctx).apply {
            text = "Show:  $filterLabel   ▾"; textSize = 16f; setTextColor(0xFF2F6F96.toInt())
            setPadding(px(4), px(2), px(4), px(8)); setOnClickListener { pickFilter() }
        })
        c.addView(View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
                .apply { bottomMargin = px(6) }
        })

        if (SiteStore.all(ctx).isEmpty()) {
            c.addView(TextView(ctx).apply {
                text = "Add a site + application password (tap 🌐 above, or Calendar Settings), then your posts appear here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(px(4), px(16), px(4), 0)
            })
            return
        }
        if (loading) {
            c.addView(TextView(ctx).apply { text = "Loading…"; setTextColor(0xFF888888.toInt()); setPadding(px(4), px(16), px(4), 0) })
            return
        }
        if (items.isEmpty()) {
            // Name the window, not just the emptiness: "Nothing here" over a list that is scoped to
            // a month you happened to step onto reads as a broken pane rather than a quiet one.
            c.addView(TextView(ctx).apply {
                text = if (siteFilter != null) "Nothing in this $navPeriod for this site."
                else "Nothing in this $navPeriod on any site."
                setTextColor(0xFF888888.toInt()); setPadding(px(4), px(16), px(4), 0)
            })
            return
        }

        // Show the per-row site tag only in the aggregate — a narrowed list already names its site
        // in the chip above, so repeating it on every row would be noise.
        val showSiteTag = siteFilter == null && SiteStore.all(ctx).size > 1
        for (sp in items) {
            val p = sp.post
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(10), px(8), px(10), px(8)); setBackgroundColor(0xFFF3F3F3.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, px(6)) }
                setOnClickListener { openEditor(sp) }
            }
            card.addView(TextView(ctx).apply {
                text = p.title.ifBlank { "(no title)" }; textSize = 16f; setTextColor(0xFF000000.toInt()); maxLines = 2
            })
            val meta = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(3), 0, 0) }
            meta.addView(TextView(ctx).apply {
                text = statusLabel(p.status); textSize = 11f
                setPadding(px(6), px(1), px(6), px(1))
                setTextColor(statusTint(p.status))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = px(8).toFloat(); setColor(statusTint(p.status) and 0x22FFFFFF.toInt())
                }
            })
            meta.addView(TextView(ctx).apply {
                text = "   " + p.date.take(10); textSize = 11f; setTextColor(0xFF666666.toInt())
                setPadding(px(6), px(1), 0, px(1))
            })
            if (showSiteTag) meta.addView(TextView(ctx).apply {
                text = "   🌐 ${sp.site.display}"; textSize = 11f; setTextColor(0xFF2F6F96.toInt())
                setPadding(px(6), px(1), 0, px(1)); maxLines = 1
            })
            card.addView(meta)
            if (p.status != "trash") {
                card.addView(TextView(ctx).apply {
                    text = "🗑  Trash"; textSize = 14f; setTextColor(0xFFB00020.toInt()); setPadding(0, px(4), 0, 0)
                    setOnClickListener { confirmTrash(sp) }
                })
            }
            c.addView(card)
        }
    }

    /** Open a post to edit — on its OWN site: activate that site so [PublishFragment] (and its
     *  WP client, which reads the active creds) targets the right place. */
    private fun openEditor(sp: com.toolsboox.plugin.calendar.ui.SiteFetch.SitePost) {
        SiteStore.activate(requireContext(), sp.site.id)
        NavHostFragment.findNavController(this).navigate(
            R.id.action_to_publish,
            bundleOf(PublishFragment.ARG_TYPE to sp.post.type, PublishFragment.ARG_POST_ID to sp.post.id)
        )
    }

    private fun pickType() {
        val ctx = context ?: return
        if (types.isEmpty()) return
        val labels = types.map { it.name }.toTypedArray()
        val current = types.indexOfFirst { it.restBase == type }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Post type")
            .setSingleChoiceItems(labels, current) { d, which -> type = types[which].restBase; d.dismiss(); reload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickFilter() {
        val ctx = context ?: return
        val labels = filters.map { it.label }.toTypedArray()
        val current = filters.indexOfFirst { it.key == filterKey }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Show")
            .setSingleChoiceItems(labels, current) { d, which -> filterKey = filters[which].key; d.dismiss(); reload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Trash a post on its OWN site: activate it, then reuse the active-site WP client. A single,
     *  confirmed action, so the transient re-point is harmless and reload() reflects the result. */
    private fun confirmTrash(sp: com.toolsboox.plugin.calendar.ui.SiteFetch.SitePost) {
        val ctx = context ?: return
        val p = sp.post
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setMessage("Move “${p.title.ifBlank { "(no title)" }}” to Trash on ${sp.site.display}?")
            .setPositiveButton("Trash") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        SiteStore.activate(ctx, sp.site.id)
                        WPPublish.trash(ctx, p.type, p.id)
                    }
                    android.widget.Toast.makeText(ctx, err ?: "Trashed", android.widget.Toast.LENGTH_SHORT).show()
                    if (err == null) reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun statusLabel(s: String): String = when (s) {
        "publish" -> "Published"; "future" -> "Scheduled"; "draft", "pending" -> "Draft"
        "private" -> "Private"; "trash" -> "Trash"; else -> s.replaceFirstChar { it.uppercase() }
    }

    private fun statusTint(s: String): Int = when (s) {
        "publish" -> 0xFF1B7A3D.toInt(); "future" -> 0xFFB4690E.toInt(); "private" -> 0xFF6A3FB0.toInt()
        "trash" -> 0xFFB00020.toInt(); else -> 0xFF2F6F96.toInt()
    }

    override fun showLoading() {}
    override fun hideLoading() {}
}
