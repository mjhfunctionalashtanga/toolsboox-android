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
import dagger.hilt.android.AndroidEntryPoint
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSiteWebBinding.bind(view)

        explicitPath = arguments?.getString(ARG_PATH)?.takeIf { it.isNotBlank() }
        target = TARGETS[arguments?.getString(ARG_TARGET)] ?: TARGETS.getValue("community")
        binding.siteWebTitle.text = if (explicitPath != null)
            (arguments?.getString(ARG_TITLE)?.takeIf { it.isNotBlank() } ?: "Site") else target.title

        binding.siteWebClose.setOnClickListener { findNavController().popBackStack() }
        binding.siteWebReload.setOnClickListener { binding.siteWebView.reload() }
        binding.siteWebBack.setOnClickListener {
            if (binding.siteWebView.canGoBack()) binding.siteWebView.goBack()
        }

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
            override fun onPageFinished(v: WebView, url: String?) {
                if (::binding.isInitialized) binding.siteWebBack.alpha = if (v.canGoBack()) 1f else 0.3f
            }
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
    private fun resolveUrl(): String? {
        val p = prefs()
        explicitPath?.let { path ->
            val site = (p.getString("site", "") ?: "").trim()
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
        if (::binding.isInitialized) binding.siteWebView.destroy()
        super.onDestroyView()
    }
}
