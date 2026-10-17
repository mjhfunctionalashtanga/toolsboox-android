package com.toolsboox.plugin.about.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.toolsboox.R
import com.toolsboox.databinding.FragmentAboutBinding
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Ledgable's About: the version in hand, the door to the latest build, and the lineage.
 *
 * This page used to be the upstream project's own about ("the about is still about the
 * original author", 08-22) — it is Ledgable's now, and its one working job is the update:
 * the same R2 address every cabled install ships from, one tap from inside the app. The
 * lineage section keeps the debt honest: Ledgable grew from Gábor AUTH's open-source
 * Toolsboox, and the fork's history carries his work in every file.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a> (upstream)
 */
@AndroidEntryPoint
class AboutFragment @Inject constructor() : ScreenFragment() {

    /**
     * The injected presenter.
     */
    @Inject
    lateinit var presenter: AboutPresenter

    /**
     * The inflated layout.
     */
    override val view = R.layout.fragment_about

    /**
     * The view binding.
     */
    private lateinit var binding: FragmentAboutBinding

    companion object {
        /** The fleet's one build address — the same file `reference_ledger_apk_publish`
         *  ships to; the browser downloads it and the system installer upgrades in place
         *  (same debug signature on every build). */
        private const val APK_URL =
            "https://pub-fd963781ad094611b656cc774c6dc163.r2.dev/ledger/ledger.apk"
        private const val UPSTREAM_URL = "https://github.com/gaborauth/toolsboox-android/"
    }

    /**
     * OnViewCreated hook.
     *
     * @param view the parent view
     * @param savedInstanceState the saved instance state
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentAboutBinding.bind(view)

        val version = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "?"
        binding.aboutVersion.text = getString(R.string.about_version_line, version)

        binding.aboutUpdateButton.setOnClickListener {
            Toast.makeText(requireContext(),
                getString(R.string.about_update_toast, version), Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)))
            }.onFailure {
                Toast.makeText(requireContext(), APK_URL, Toast.LENGTH_LONG).show()
            }
        }

        htmlLinks(binding.aboutLineageLink, R.string.about_lineage_link, UPSTREAM_URL)
    }

    /**
     * OnResume hook.
     */
    override fun onResume() {
        super.onResume()

        toolbar.root.title = getString(R.string.drawer_title, getString(R.string.app_name), getString(R.string.about_title))
    }

    /**
     * Show the progress bar.
     */
    override fun showLoading() {
        binding.mainProgress.visibility = View.VISIBLE
    }

    /**
     * Hide the progress bar.
     */
    override fun hideLoading() {
        binding.mainProgress.visibility = View.INVISIBLE
    }

    /**
     * Update textView content to clickable link.
     *
     * @param linkView the link holder textView
     * @param messageResId the message resource id
     * @param link the link
     */
    private fun htmlLinks(linkView: TextView, messageResId: Int, link: String) {
        val linkMessage = getString(messageResId)
        val linkHtml = "$linkMessage <a href=\"$link\">$link</a>"
        linkView.text = Html.fromHtml(linkHtml, Html.FROM_HTML_MODE_COMPACT)
        linkView.setOnClickListener {
            this.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }
    }
}
