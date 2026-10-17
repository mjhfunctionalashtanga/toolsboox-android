package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.net.Uri
import timber.log.Timber

/**
 * THE PAIRING STRING — `ledgable://pair?site=…&auth=basic&user=…&pass=…[&name=…][&portal=…]`.
 *
 * ledgable.cc/pair (and the plugin's wp-admin wizard) print one after minting an application
 * password, so a site can be added with one tap instead of four typed lines. Applying one is
 * idempotent: a site with the same host and username is updated in place (new password, kept id),
 * anything else becomes a new site. The site is made ACTIVE, because a person who just paired is
 * about to use it. Only `auth=basic` is understood here: the plugin's device token is for
 * publishing to one's own site and the Android app has no consumer for it yet.
 */
object PairLink {
    const val SCHEME = "ledgable"
    const val HOST = "pair"

    fun isPair(uri: Uri?): Boolean = uri != null && uri.scheme.equals(SCHEME, true) && uri.host.equals(HOST, true)

    /** Parse a pairing string. Null when it is not one, or names no site or user. */
    /** The string as it may arrive: pasted whole, inside the rest of the credential block the page
     *  prints, or with ampersands still HTML-escaped. Found inside the text, not demanded as the
     *  whole of it (iOS twin: PairLink.extract). */
    fun extract(text: String): String? =
        PAIR_IN_TEXT.find(text.replace("&amp;", "&"))?.value

    private val PAIR_IN_TEXT = Regex("""ledgable://pair\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Parsed? {
        val found = extract(text) ?: return null
        val uri = runCatching { Uri.parse(found.trim()) }.getOrNull() ?: return null
        if (!isPair(uri)) return null
        val site = uri.getQueryParameter("site")?.trim()?.trimEnd('/') ?: return null
        val user = uri.getQueryParameter("user")?.trim() ?: return null
        if (site.isBlank() || user.isBlank()) return null
        val auth = uri.getQueryParameter("auth") ?: "basic"
        if (auth != "basic") return null
        val pass = uri.getQueryParameter("pass")?.trim() ?: return null
        val host = runCatching { Uri.parse(site).host }.getOrNull() ?: site
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { host }
        val portal = uri.getQueryParameter("portal")?.trim().orEmpty()
        return Parsed(site, user, pass, name, portal)
    }

    data class Parsed(val site: String, val user: String, val pass: String, val name: String, val portal: String)

    /** Apply a pairing string: upsert the site, store the password, activate it. Returns the
     *  site's display name, or null when the string was not a pairing string or the store refused. */
    fun apply(context: Context, text: String): String? {
        val p = parse(text) ?: return null
        val host = runCatching { Uri.parse(p.site).host }.getOrNull()
        val existing = SiteStore.all(context).firstOrNull { s ->
            runCatching { Uri.parse(s.url).host }.getOrNull() == host && s.username.equals(p.user, true)
        }
        val site = (existing ?: LedgerSite(name = p.name)).apply {
            url = p.site
            username = p.user
            if (p.portal.isNotBlank()) portalPath = p.portal
            if (name.isBlank()) name = p.name
        }
        if (!SiteStore.upsert(context, site)) { Timber.w("pair: site store refused ${p.site}"); return null }
        SiteStore.setPassword(context, site.id, p.pass)
        SiteStore.activate(context, site.id)
        Timber.i("pair: ${if (existing != null) "updated" else "added"} ${site.display} (${p.user})")
        return site.display
    }
}
