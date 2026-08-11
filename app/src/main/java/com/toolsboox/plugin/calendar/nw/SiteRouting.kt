package com.toolsboox.plugin.calendar.nw

import android.content.Context

/**
 * WHERE A SURFACE POINTS, WITHOUT A GLOBAL LOCK.
 *
 * Michael's ruling (2026-08): "I want to be able to pull up Yoga Club events and Ashtanga Tech
 * messaging/groups, and to post to sites/site campaigns in FluentCRM, WITHOUT switching a dominant
 * global site." Until this pass, the Sites switcher was a LOCK: [SiteStore.activate] write-throughs
 * one site's creds into the single-cred keys every Fluent consumer reads, so pointing Messages at
 * ashtanga.tech silently re-pointed Bookings, Publish and the boards too — and the surfaces that
 * wanted a different site had to activate-then-restore around every action (PublishFragment kept an
 * `entryActiveId` just to undo its own picker).
 *
 * This object is the replacement rule, kept PURE (no Context, no prefs) so it is testable:
 * every surface resolves its own site as
 *
 *     remembered per-surface choice  →  the surface's NATURAL HOME  →  the global active site
 *     (the switcher's pick, now a DEFAULT rather than a lock)       →  the first configured site.
 *
 * Natural homes are where the thing actually lives on Michael's WordPress fleet:
 *  - Messages / Community / Site Boards → ashtanga.tech (FluentCommunity + fluent-messaging + the
 *    working boards are deployed there);
 *  - Bookings / Events / Roster → theyoga.club (FluentBooking runs the studio calendar there —
 *    there IS no booking calendar on ashtanga.tech, which is why "events" always meant a global
 *    switch before);
 *  - Publish / Posts → wherever you last published (no single natural home: essays go to
 *    michaeljoelhall.com, course notes to ashtanga.tech), which is what the remembered choice
 *    already is — so they get no host preference here.
 *
 * The COMPANION rule (enforced in the nw clients, not here): every network call accepts an explicit
 * per-call config, so two surfaces on two sites can run concurrently without fighting over the
 * write-through keys. [SiteStore.activate] survives untouched as "set all the defaults at once".
 */
object SiteRouting {

    // Surface keys — also the pref keys under which each surface remembers its choice, so keep
    // them stable across releases (a rename would silently forget everyone's per-surface picks).
    const val MESSAGES = "messages"
    const val CORRESPONDENCE = "correspondence"
    const val SITE_BOARDS = "site_boards"
    const val BOOKINGS = "bookings"
    const val ROSTER = "roster"
    const val PUBLISH = "publish"
    const val POSTS = "posts"

    /** One configured site, reduced to what routing needs — id + url — so the logic stays pure
     *  and a unit test doesn't have to build a whole [LedgerSite]. */
    data class Ref(val id: String, val url: String)

    /**
     * The host a surface belongs to when nothing is remembered, or null for "no preference"
     * (Publish/Posts, whose home is simply where you last worked). Matching is by HOST, not id:
     * site ids are random UUIDs minted per device, but ashtanga.tech is ashtanga.tech on every
     * device the ledger runs on.
     */
    fun naturalHomeHost(surface: String): String? = when (surface) {
        MESSAGES, CORRESPONDENCE, SITE_BOARDS -> "ashtanga.tech"
        BOOKINGS, ROSTER -> "theyoga.club"
        else -> null
    }

    /**
     * "https://www.TheYoga.Club/foo" → "theyoga.club". Blank stays blank. Deliberately tolerant:
     * these urls are typed by hand into the site editor, so scheme, case, a www., a trailing path
     * or port must not break the match between a surface and its home.
     */
    fun hostOf(url: String): String = url.trim()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore(':')
        .removePrefix("www.")
        .lowercase()

    /**
     * The site id [surface] should work against, or null when no sites exist.
     *
     * Resolution order, first hit wins:
     *  1. [rememberedId], if it still names a configured site — an explicit chip pick (or, for
     *     Publish/Posts, the last site actually used) beats every default; a pick that names a
     *     since-deleted site is ignored rather than half-honoured.
     *  2. The surface's natural home, by host match.
     *  3. [activeId] — the global switcher's site, demoted from lock to default.
     *  4. The first configured site, so a surface never dead-ends while any site exists.
     */
    fun resolveId(
        surface: String,
        rememberedId: String?,
        sites: List<Ref>,
        activeId: String?,
    ): String? {
        if (sites.isEmpty()) return null
        rememberedId?.takeIf { r -> sites.any { it.id == r } }?.let { return it }
        naturalHomeHost(surface)?.let { home ->
            sites.firstOrNull { hostOf(it.url) == home }?.let { return it.id }
        }
        activeId?.takeIf { a -> sites.any { it.id == a } }?.let { return it }
        return sites.first().id
    }
}

/**
 * The Context-facing half: the per-surface remembered pick (plain prefs — it holds site IDS, never
 * credentials, so it does not belong in the encrypted store) plus the resolution over [SiteStore].
 *
 * The config helpers return NULL — meaning "use the legacy active-site config" — in exactly one
 * subtle case beyond "no sites": when the resolved site IS the globally active one but carries no
 * stored password of its own. [SiteStore.seedKnownSites] seeds url-only sites, and
 * [SiteStore.activate] deliberately keeps pre-existing creds when a site's password is blank — so
 * the write-through keys can hold working creds for the active site while `site_pass_<id>` is
 * empty. Falling back to the legacy config there keeps every pre-multi-site install working
 * exactly as before this pass; a NON-active site without a password honestly resolves to a
 * not-ready config and the surface shows its usual "add the app password" hint.
 */
object SiteAffinity {

    private const val PREFS = "ledger_site_affinity"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The remembered pick for [surface], or null. */
    fun remembered(context: Context, surface: String): String? =
        prefs(context).getString("site_$surface", "")?.ifBlank { null }

    /** Remember [siteId] for [surface] (blank/null forgets, falling back to the defaults). */
    fun remember(context: Context, surface: String, siteId: String?) {
        prefs(context).edit().putString("site_$surface", siteId ?: "").apply()
    }

    /** The [LedgerSite] this surface works against, by [SiteRouting.resolveId]. Null = no sites. */
    fun siteFor(context: Context, surface: String): LedgerSite? {
        val sites = SiteStore.all(context)
        val id = SiteRouting.resolveId(
            surface,
            remembered(context, surface),
            sites.map { SiteRouting.Ref(it.id, it.url) },
            SiteStore.activeId(context).ifBlank { null },
        ) ?: return null
        return sites.firstOrNull { it.id == id }
    }

    /** True when [site]'s own stored password is blank AND it is the active site — the one case
     *  where the legacy write-through keys may hold creds this site's record doesn't. */
    private fun preferLegacy(context: Context, site: LedgerSite): Boolean =
        SiteStore.password(context, site.id).isBlank() && SiteStore.activeId(context) == site.id

    /** Community-flavoured config for [surface]'s site; null = "use the active-site config". */
    fun communityConfigFor(context: Context, surface: String): LedgerCommunityBridge.Config? {
        val site = siteFor(context, surface) ?: return null
        if (preferLegacy(context, site)) return null
        return LedgerCommunityBridge.configFor(context, site)
    }

    /** Boards/booking/WP-flavoured config for [surface]'s site; null = "use the active-site config". */
    fun boardsConfigFor(context: Context, surface: String): LedgerWebBridge.Config? {
        val site = siteFor(context, surface) ?: return null
        if (preferLegacy(context, site)) return null
        return LedgerWebBridge.configFor(context, site)
    }
}
