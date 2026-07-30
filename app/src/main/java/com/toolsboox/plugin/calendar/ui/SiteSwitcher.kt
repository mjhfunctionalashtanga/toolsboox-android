package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.toolsboox.plugin.calendar.nw.LedgerSite
import com.toolsboox.plugin.calendar.nw.SiteBoard
import com.toolsboox.plugin.calendar.nw.WPPublish
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The site switcher, keyed on SITE the way Mail's inbox is keyed on account (see
 * [com.toolsboox.plugin.mail.ui.MailInboxFragment]): a "🌐 All sites ▸/▾" accordion header that
 * unfolds to one row per configured WordPress site. `null` filter = every site (the surfaces
 * aggregate); picking a site narrows. One shared builder so Posts, Publish and Site-boards wear
 * the exact same chrome — black-on-white "selected", no animation, single redraws, e-ink-plain.
 *
 * The switcher only draws the rows; the owning fragment holds the (persisted) filter + open state
 * and re-renders on the callbacks — identical to how MailInboxFragment keeps `accountFilter` /
 * `accountsOpen` in its own prefs.
 */
object SiteSwitcherBar {

    /**
     * Build the switcher block for [container]-style stacking (returns a vertical LinearLayout the
     * caller adds at the top of its list). Falls back gracefully: no sites → a single "add a site"
     * row; one site → a plain name label (no accordion — nothing to switch between).
     *
     * @param filter the current site id, or null for "All sites".
     * @param open   whether the accordion rows are unfolded.
     * @param onToggleOpen fold/unfold the rows (also selects All, mirroring Mail's ✉ All chip).
     * @param onPick narrow to a site id, or null for All.
     * @param onManage open the Sites settings surface (add / edit / passwords).
     */
    fun build(
        context: Context,
        sites: List<LedgerSite>,
        filter: String?,
        open: Boolean,
        onToggleOpen: (Boolean) -> Unit,
        onPick: (String?) -> Unit,
        onManage: () -> Unit,
    ): View {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(6) }
        }

        // No sites yet — the one door out is to add one.
        if (sites.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "🌐  Add a site to begin"
                textSize = 15f; setTextColor(0xFF2F6F96.toInt()); setPadding(px(4), px(8), px(4), px(8))
                setOnClickListener { onManage() }
            })
            return root
        }

        // One site — no switching to do; name it plainly (tap to manage) and let the surface work
        // against that single site exactly as it did before multi-site.
        if (sites.size == 1) {
            root.addView(TextView(context).apply {
                text = "🌐  ${sites.first().display}"
                textSize = 14f; setTextColor(0xFF000000.toInt())
                setTypeface(typeface, Typeface.BOLD); setPadding(px(4), px(6), px(4), px(6))
                setOnClickListener { onManage() }
            })
            return root
        }

        // The header row: the ▸/▾ accordion chip (feed-drawer idiom) + a folded-state face naming
        // the narrowed site + a trailing ⚙ door to Sites settings.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }

        fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label; textSize = 14f
            setPadding(px(14), px(7), px(14), px(7))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setStroke(px(1), 0xFF000000.toInt())
                setColor(if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            if (selected) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, px(8), 0) }
            setOnClickListener { onClick() }
        }

        // "🌐 All sites ▸/▾" — one tap both selects All and unfolds the rows (a second folds them),
        // exactly as Mail's ✉ All behaves.
        header.addView(chip("🌐 All sites  " + (if (open) "▾" else "▸"), filter == null) {
            onPick(null); onToggleOpen(!open)
        })
        // The folded face of a narrowed filter: one chip naming the site so the narrowing is never
        // a surprise. Tapping it unfolds the rows to change it.
        val current = sites.firstOrNull { it.id == filter }
        if (current != null && !open) header.addView(chip("@ ${current.display}", true) { onToggleOpen(true) })

        // Push a ⚙ Sites affordance to the right edge.
        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, px(1), 1f)
        })
        header.addView(TextView(context).apply {
            text = "⚙"; textSize = 18f; setTextColor(0xFF000000.toInt())
            setPadding(px(8), px(4), px(4), px(4)); setOnClickListener { onManage() }
        })
        root.addView(header)

        // The unfolded rows: "All sites" + one row per site, indented inside the accent outline like
        // every accordion dropdown in the app (Mail's account rows).
        if (open) {
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(px(1), com.toolsboox.ot.LedgerTheme.accent(context))
                    cornerRadius = px(8).toFloat()
                }
                setPadding(px(2), px(2), px(2), px(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(6) }
            }
            fun row(label: String, selected: Boolean, onClick: () -> Unit) = panel.addView(TextView(context).apply {
                text = label; textSize = 14f
                setPadding(px(24), px(8), px(10), px(8))
                if (selected) {
                    background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = px(8).toFloat() }
                    setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
                } else setTextColor(0xFF000000.toInt())
                setOnClickListener { onClick() }
            })
            row("🌐  All sites", filter == null) { onPick(null) }
            for (s in sites) row("@  ${s.display}", filter == s.id) { onPick(s.id) }
            row("＋  Add / manage sites…", false) { onManage() }
            root.addView(panel)
        }
        return root
    }
}

/**
 * Per-site reads for the aggregating surfaces. Where [WPPublish] and [com.toolsboox.plugin.calendar.nw.LedgerBoards]
 * always talk to the ACTIVE site (they read the single-cred bridge keys), these take an explicit
 * site + its application password — so "All sites" can fan out across every configured site IN
 * PARALLEL, each request carrying its own creds, with no global-state mutation and per-site failure
 * isolation. Blocking wire calls: invoke from Dispatchers.IO. Failures collapse to empty, quietly.
 *
 * Writes (trash, publish/save) and the deep single-board interactions still go through the existing
 * active-site clients: the surfaces activate the picked/tapped site first, then reuse that proven
 * code unchanged.
 */
object SiteFetch {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** A post carrying the site it came from — the Mail-row idiom of tagging each item with its origin. */
    data class SitePost(val site: LedgerSite, val post: WPPublish.WpPost)

    /** A board carrying its site — boards ids collide across sites (each FluentBoards starts at 1),
     *  so the site is what keeps them apart in the aggregate. */
    data class SiteBoardRow(val site: LedgerSite, val board: SiteBoard)

    private fun ready(site: LedgerSite, pass: String) =
        site.url.isNotBlank() && site.username.isNotBlank() && pass.isNotBlank()

    private fun get(site: LedgerSite, pass: String, wpPath: String): okhttp3.Response? {
        val base = site.url.trim().trimEnd('/')
        return try {
            client.newCall(
                Request.Builder().url("$base/$wpPath")
                    .header("Authorization", Credentials.basic(site.username.trim(), pass)).build()
            ).execute()
        } catch (e: Exception) {
            Timber.w(e, "site GET %s failed (%s)", wpPath, site.url); null
        }
    }

    /**
     * Recent posts of [type] (rest_base) on [site], filtered by [statuses]. Empty on any failure.
     *
     * [after] / [before] scope the fetch to a date range — the browser's almanac window, as
     * "yyyy-MM-ddTHH:mm:ss". Without them the site only ever sends its thirty most recent posts of
     * a type, so stepping the strip back a month landed on an empty list: the posts were there,
     * they were simply never asked for, and the filter would have been cosmetic.
     *
     * WP compares both against `post_date` — the SITE's wall clock, there is no `after_gmt` — so on
     * a site in another zone the edge can sit a few hours out. That is why the browser still
     * filters what comes back: the range is how the right posts get FETCHED, not what decides the
     * window.
     */
    fun listPosts(
        site: LedgerSite, pass: String, type: String, statuses: List<String>,
        after: String? = null, before: String? = null
    ): List<WPPublish.WpPost> {
        if (!ready(site, pass)) return emptyList()
        var path = "wp-json/wp/v2/$type?context=edit&per_page=30&orderby=date&order=desc&_fields=id,title,status,date,link"
        if (statuses.isNotEmpty()) path += "&status=${statuses.joinToString(",")}"
        if (!after.isNullOrBlank()) path += "&after=$after"
        if (!before.isNullOrBlank()) path += "&before=$before"
        val resp = get(site, pass, path) ?: return emptyList()
        return resp.use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = try { JSONArray(r.body?.string() ?: return emptyList()) } catch (e: Exception) { return emptyList() }
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = p.optInt("id", 0); if (id <= 0) return@mapNotNull null
                WPPublish.WpPost(
                    id = id, type = type,
                    title = decode(p.optJSONObject("title")?.optString("rendered", "") ?: ""),
                    status = p.optString("status", ""), date = p.optString("date", ""),
                    link = p.optString("link", ""), content = "", featuredMedia = 0,
                    categories = emptyList(), tags = emptyList(),
                )
            }
        }
    }

    /** Public, creatable post types on [site] (posts, pages, public CPTs). Empty on any failure. */
    fun postTypes(site: LedgerSite, pass: String): List<WPPublish.PostType> {
        if (!ready(site, pass)) return emptyList()
        val resp = get(site, pass, "wp-json/wp/v2/types?context=edit") ?: return emptyList()
        return resp.use { r ->
            if (!r.isSuccessful) return emptyList()
            val obj = try { JSONObject(r.body?.string() ?: return emptyList()) } catch (e: Exception) { return emptyList() }
            val skip = setOf(
                "attachment", "nav_menu_item", "wp_block", "wp_template",
                "wp_template_part", "wp_navigation", "wp_font_family", "wp_font_face", "wp_global_styles"
            )
            val out = mutableListOf<WPPublish.PostType>()
            for (slug in obj.keys()) {
                if (slug in skip) continue
                val t = obj.optJSONObject(slug) ?: continue
                val rest = t.optString("rest_base", ""); if (rest.isBlank()) continue
                out.add(WPPublish.PostType(rest, t.optString("name", slug), t.optBoolean("hierarchical", false)))
            }
            out.sortedBy { it.name.lowercase() }
        }
    }

    /** Every FluentBoards board on [site] (via the ledgr-fb-bridge plugin). Empty on any failure. */
    fun boards(site: LedgerSite, pass: String): List<SiteBoard> {
        if (!ready(site, pass)) return emptyList()
        val resp = get(site, pass, "wp-json/ledgr/v1/boards") ?: return emptyList()
        return resp.use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = try { JSONObject(r.body?.string() ?: return emptyList()).optJSONArray("boards") ?: return emptyList() }
            catch (e: Exception) { return emptyList() }
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SiteBoard(
                    o.optInt("id", 0), o.optString("title", "Untitled"),
                    o.optString("color", "").takeIf { s -> s.isNotBlank() && s != "null" },
                    o.optInt("task_count", 0), o.optInt("stage_count", 0),
                )
            }
        }
    }

    /** The handful of entities WP double-encodes in `title.rendered`, so titles read cleanly. */
    private fun decode(s: String): String {
        var out = s
        val map = listOf(
            "&amp;" to "&", "&#038;" to "&", "&#8217;" to "’", "&#8216;" to "‘",
            "&#8220;" to "“", "&#8221;" to "”", "&lt;" to "<", "&gt;" to ">",
            "&hellip;" to "…", "&#8211;" to "–", "&#8212;" to "—", "&quot;" to "\"",
            "&#039;" to "'", "&nbsp;" to " ",
        )
        for ((a, b) in map) out = out.replace(a, b)
        return out
    }
}
