package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * A WordPress site and the whole Fluent suite that lives on it — one URL + login powers its
 * FluentBoards, FluentCommunity (and FluentSupport / FluentCRM / FluentBooking as they come online),
 * because on WordPress they're all the same site behind one application password. Keeping this as a
 * list of SITES (not a separate account per plugin) matches how the sites actually work: add a site
 * once, then point the app at whichever site you mean.
 *
 * Mirrors the iPad model in App/LedgerSites.swift so the two devices reason about sites identically.
 * The application password is NEVER stored in the object — it lives in EncryptedSharedPreferences
 * under `site_pass_<id>`, exactly as iOS keeps it in the Keychain.
 */
data class LedgerSite(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",            // https://ashtanga.tech
    var username: String = "",       // WP user (the app password's owner)
    var boardId: String = "",        // default FluentBoards board (optional)
    // Forward-facing (member/customer) portal slugs — vary per site, so each is settable. Blank falls
    // back to the default. Android has no web-view consumer for these yet; they round-trip so a site
    // configured on the iPad keeps its slugs here and vice-versa.
    var portalPath: String = "portal",       // FluentCommunity portal
    var boardsPath: String = "projects",     // FluentBoards front portal
    var bookingPath: String = "booking",     // FluentBooking public page
    var supportPath: String = "support",     // FluentSupport customer portal
    var shopPath: String = "shop",           // FluentCart storefront
    var crmPath: String = "",                // FluentCRM front page (if any)
) {
    val display: String
        get() = if (name.isNotBlank()) name else if (url.isNotBlank()) url else "Untitled site"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("username", username)
        .put("boardId", boardId).put("portalPath", portalPath).put("boardsPath", boardsPath)
        .put("bookingPath", bookingPath).put("supportPath", supportPath)
        .put("shopPath", shopPath).put("crmPath", crmPath)

    companion object {
        fun fromJson(o: JSONObject) = LedgerSite(
            o.optString("id", UUID.randomUUID().toString()),
            o.optString("name", ""),
            o.optString("url", ""),
            o.optString("username", ""),
            o.optString("boardId", ""),
            o.optString("portalPath", "portal"),
            o.optString("boardsPath", "projects"),
            o.optString("bookingPath", "booking"),
            o.optString("supportPath", "support"),
            o.optString("shopPath", "shop"),
            o.optString("crmPath", ""),
        )
    }
}

/**
 * The configured sites. Persisted as a JSON array in the SAME encrypted store the bridges read
 * (`ledgr_bridge_prefs`); each site's application password lives under `site_pass_<id>`. One site is
 * ACTIVE, and [activate] WRITE-THROUGHS its credentials into the existing single-cred keys the Fluent
 * consumers already read (`site`/`user`/`pass`/`boardId` for [LedgerWebBridge] & [LedgerBoards] &
 * [LedgerBooking], and `communitySite`/`communityUser`/`communityPass` for [LedgerCommunityBridge] &
 * [LedgerCorrespondence]) — so multi-site works without touching any of that code. Switching the
 * active site re-points them all.
 *
 * Mirrors `SiteStore` in App/LedgerSites.swift.
 */
object SiteStore {

    private const val PREFS = "ledgr_bridge_prefs"
    private const val KEY_SITES = "ledger_sites"
    private const val KEY_ACTIVE = "active_site_id"
    private const val KEY_SEEDED = "seeded_known_sites_v1"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences = cached ?: EncryptedSharedPreferences.create(
        context, PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).also { cached = it }

    fun all(context: Context): List<LedgerSite> = try {
        val raw = prefs(context).getString(KEY_SITES, "") ?: ""
        if (raw.isBlank()) emptyList()
        else JSONArray(raw).let { arr -> (0 until arr.length()).map { LedgerSite.fromJson(arr.getJSONObject(it)) } }
    } catch (e: Exception) {
        Timber.w(e, "sites read failed")
        emptyList()
    }

    fun save(context: Context, sites: List<LedgerSite>) {
        val arr = JSONArray()
        sites.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_SITES, arr.toString()).apply()
    }

    fun upsert(context: Context, s: LedgerSite) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        save(context, list)
        if (activeId(context) == s.id) activate(context, s.id)   // keep live creds in step with an edit
    }

    fun delete(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
        setPassword(context, id, "")
        if (activeId(context) == id) all(context).firstOrNull()?.let { activate(context, it.id) }
    }

    private fun passKey(id: String) = "site_pass_$id"
    fun password(context: Context, id: String): String = prefs(context).getString(passKey(id), "") ?: ""
    fun setPassword(context: Context, id: String, p: String) {
        prefs(context).edit().putString(passKey(id), p).apply()
    }

    fun activeId(context: Context): String = prefs(context).getString(KEY_ACTIVE, "") ?: ""
    fun active(context: Context): LedgerSite? = all(context).firstOrNull { it.id == activeId(context) }

    /** Make [id] the live site: write its creds into every legacy key the Fluent consumers read. */
    fun activate(context: Context, id: String) {
        val s = all(context).firstOrNull { it.id == id } ?: return
        val pass = password(context, id)
        val url = s.url.trim().trimEnd('/')
        val user = s.username.trim()
        val e = prefs(context).edit()
        // Boards + Community share the site's WP login (one application password on one site).
        e.putString("site", url).putString("user", user).putString("pass", pass)
        e.putString("communitySite", url).putString("communityUser", user).putString("communityPass", pass)
        // boardId is an Int in the bridge; only overwrite when the site names one.
        s.boardId.trim().toIntOrNull()?.let { e.putInt("boardId", it) }
        // Forward-facing portal slugs → mirror iOS's keys (blank falls back to the default). No Android
        // web-view reads these yet, but they round-trip cross-device and cost nothing to carry.
        fun slug(v: String, def: String): String { val t = v.trim().trim('/'); return t.ifBlank { def } }
        e.putString("communityPortal", slug(s.portalPath, "portal"))
        e.putString("boardsPortal", slug(s.boardsPath, "projects"))
        e.putString("bookingPortal", slug(s.bookingPath, "booking"))
        e.putString("supportPortal", slug(s.supportPath, "support"))
        e.putString("shopPortal", slug(s.shopPath, "shop"))
        e.putString("crmPortal", s.crmPath.trim().trim('/'))
        e.putString(KEY_ACTIVE, id)
        e.apply()
    }

    /**
     * First run after the multi-site update: fold the existing single-site creds into one site so
     * nothing breaks. Runs once (only when there are no sites yet but legacy bridge creds exist).
     * Mirrors iOS `seedIfNeeded`.
     */
    fun seedIfNeeded(context: Context) {
        if (all(context).isNotEmpty()) return
        val p = prefs(context)
        val url = (p.getString("site", "") ?: "").ifBlank { p.getString("communitySite", "") ?: "" }
        if (url.isBlank()) return
        val user = (p.getString("user", "") ?: "").ifBlank { p.getString("communityUser", "") ?: "" }
        val boardId = p.getInt("boardId", 0)
        val s = LedgerSite(
            name = url.removePrefix("https://").removePrefix("http://"),
            url = url,
            username = user,
            boardId = if (boardId != 0) boardId.toString() else "",
        )
        save(context, listOf(s))
        // Carry whichever password the legacy consumers already had.
        val pass = (p.getString("pass", "") ?: "").ifBlank { p.getString("communityPass", "") ?: "" }
        setPassword(context, s.id, pass)
        p.edit().putString(KEY_ACTIVE, s.id).apply()
    }

    /**
     * Seed the owner's three sites. URLs only — add each site's application password on-device.
     * Runs once (a boolean flag), non-destructively (never touches a site already present).
     * Makes ashtanga.tech active if nothing is active yet. Mirrors iOS `seedKnownSites`.
     */
    fun seedKnownSites(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SEEDED, false)) return
        p.edit().putBoolean(KEY_SEEDED, true).apply()
        val list = all(context).toMutableList()
        val known = listOf(
            "Ashtanga.tech" to "https://ashtanga.tech",
            "The Yoga Club" to "https://theyoga.club",
            "michaeljoelhall.com" to "https://michaeljoelhall.com",
        )
        for ((name, url) in known) {
            if (list.none { it.url == url }) list.add(LedgerSite(name = name, url = url))
        }
        save(context, list)
        if (activeId(context).isBlank()) {
            (list.firstOrNull { it.url == "https://ashtanga.tech" } ?: list.firstOrNull())?.let { activate(context, it.id) }
        }
    }
}
