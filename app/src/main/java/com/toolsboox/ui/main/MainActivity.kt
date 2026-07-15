package com.toolsboox.ui.main

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.ActionBar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.toolsboox.BuildConfig
import com.toolsboox.R
import com.toolsboox.databinding.ActivityMainBinding
import com.toolsboox.databinding.ToolbarBinding
import com.toolsboox.di.MainSharedPreferencesModule
import com.toolsboox.nw.CredentialService
import com.toolsboox.ui.BaseActivity
import com.toolsboox.utils.ReleaseTree
import dagger.hilt.android.AndroidEntryPoint
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.time.Instant
import java.util.*
import javax.inject.Inject

/**
 * A dashboard screen that offers the main menu.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */

@AndroidEntryPoint
class MainActivity : BaseActivity<MainPresenter>(), MainView {

    /**
     * Optional volume-key page-turn handler set by the active reader fragment. Returns true
     * if it consumed the key (up = back/page-up, down = forward/page-down).
     */
    var volumeKeyHandler: ((up: Boolean) -> Boolean)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val h = volumeKeyHandler
        if (h != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (h(true)) return true
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (h(false)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The view model.
     */
    private val viewModel by viewModels<MainViewModel>()

    /**
     * The Firebase analytics.
     */
    @Inject
    lateinit var firebaseAnalytics: FirebaseAnalytics

    /**
     * The injected shared preferences.
     */
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    /**
     * The credential service.
     */
    @Inject
    lateinit var credentialService: CredentialService

    /**
     * The view binding.
     */
    private lateinit var binding: ActivityMainBinding

    /**
     * OnCreate hook.
     *
     * @param savedInstanceState the saved state of the instance
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAnalytics = Firebase.analytics

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        setSupportActionBar(binding.mainToolbar.root)

        val actionbar: ActionBar? = supportActionBar
        actionbar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        // Temporary (?) fix for https://github.com/gaborauth/toolsboox-android/issues/305
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        sharedPreferences.edit().putString("androidId", androidId).apply()

        val preferences = MainSharedPreferencesModule.provideSharedPreferences(this)
        preferences.edit().putLong("lastTimestamp", Date().time).apply()

        binding.mainToolbar.toolbarBack.setOnClickListener {
            onBackPressed()
        }

        presenter.onViewCreated()
    }

    /**
     * Soft-wrap shared text for an on-canvas text box: text boxes only break on
     * newlines, so long lines (especially URLs) are folded to stay on the page.
     */
    private fun wrapForTextBox(raw: String, maxLine: Int = 48): String =
        raw.lines().joinToString("\n") { line ->
            if (line.length <= maxLine) return@joinToString line
            val out = StringBuilder()
            var current = StringBuilder()
            for (word in line.split(" ")) {
                var token = word
                // Hard-chunk unbreakable tokens (URLs) to the line width.
                while (token.length > maxLine) {
                    if (current.isNotEmpty()) {
                        out.append(current).append('\n'); current = StringBuilder()
                    }
                    out.append(token.take(maxLine)).append('\n')
                    token = token.drop(maxLine)
                }
                if (current.isEmpty()) current.append(token)
                else if (current.length + 1 + token.length <= maxLine) current.append(' ').append(token)
                else {
                    out.append(current).append('\n'); current = StringBuilder(token)
                }
            }
            out.append(current).toString()
        }

    /**
     * A shared link arrived — offer to file it into the reading pipeline (read later /
     * watch / listen / educate), or drop it as a box on the day page. Filing appends it to
     * today's intake sidecar (so it shows in Notes & Annotations) and enqueues it.
     */
    private fun offerToFileLink(url: String, title: String?, sharedText: String?) {
        // (iconRes, label, action). Monochrome outline icons for e-ink contrast.
        val fileAction: (String, String) -> Unit = { kind, label ->
            com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
                .fileLink(applicationContext, java.time.LocalDate.now(), kind, url, title)
            android.widget.Toast.makeText(this, getString(R.string.ledger_share_filed, label), android.widget.Toast.LENGTH_SHORT).show()
        }
        val items = listOf(
            Triple(R.drawable.ic_book, getString(R.string.ledger_share_read), { fileAction("read", getString(R.string.ledger_share_read)) }),
            Triple(R.drawable.ic_tv, getString(R.string.ledger_share_watch), { fileAction("watch", getString(R.string.ledger_share_watch)) }),
            Triple(R.drawable.ic_headphones, getString(R.string.ledger_share_listen), { fileAction("listen", getString(R.string.ledger_share_listen)) }),
            Triple(R.drawable.ic_book, getString(R.string.ledger_share_educate), { fileAction("educate", getString(R.string.ledger_share_educate)) }),
            Triple(R.drawable.ic_add, getString(R.string.ledger_share_drop_on_page), {
                val boxText = wrapForTextBox(listOfNotNull(title, url).joinToString("\n").ifBlank { sharedText?.trim().orEmpty() })
                if (boxText.isNotBlank()) dropTextOnDay(boxText, url); Unit
            })
        )
        // Defer to after the first layout — a dialog straight from onResume on a share
        // cold-start can be swallowed before the window is ready.
        binding.fragmentContent.post {
            val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ledger_share_file_title)
                .setView(androidx.core.widget.NestedScrollView(this).apply { addView(list) })
                .create()
            for ((iconRes, label, action) in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<android.widget.ImageView>(R.id.go_icon).apply { setImageResource(iconRes); visibility = android.view.View.VISIBLE }
                r.findViewById<android.widget.TextView>(R.id.go_label).text = label
                r.setOnClickListener { dialog.dismiss(); action() }
                list.addView(r)
            }
            dialog.show()
        }
    }

    /** Drop shared text as a movable box on today's day page (the pre-existing behavior). */
    private fun dropTextOnDay(boxText: String, url: String?) {
        val bundle = bundleOf("sharedText" to boxText)
        if (url != null) {
            bundle.putString("sharedUrl", url)
            bundle.putString("notePage", "intake")
        }
        val navOptions = androidx.navigation.navOptions {
            popUpTo(R.id.CalendarDayFragment) { inclusive = true }
        }
        binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundle, navOptions)
    }

    /**
     * Activity onResume.
     */
    override fun onResume() {
        super.onResume()

        // Share-to-Ledger (text/link): the shared text lands as a movable text box.
        // A link lands on today's INTAKE page — nothing is queued on share; dropping
        // the box onto a panel (THE READ / WATCH / LISTEN / EDUCATE) is what files it.
        if (intent?.action == android.content.Intent.ACTION_SEND && intent?.type == "text/plain") {
            val sharedText = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
            val sharedSubject = intent?.getStringExtra(android.content.Intent.EXTRA_SUBJECT)
            // Consume the intent so re-resume doesn't re-navigate.
            intent?.action = null

            val parsed = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.parse(sharedText, sharedSubject)
            Timber.i("Share to ledger (text): url=${parsed.url}")
            // A shared LINK → offer to file it (read later / watch / listen); plain text
            // with no link falls back to dropping a movable box on the day page.
            if (parsed.url != null) {
                offerToFileLink(parsed.url!!, parsed.title, sharedText)
            } else {
                val boxText = wrapForTextBox(
                    listOfNotNull(parsed.title, parsed.leftoverText).joinToString("\n")
                        .ifBlank { sharedText?.trim().orEmpty() }
                )
                if (boxText.isNotBlank()) dropTextOnDay(boxText, null)
            }
        }

        // Share-to-Ledger target: an image shared from Gallery or any app lands on
        // today's day page as a movable image element.
        if (intent?.action == android.content.Intent.ACTION_SEND && intent?.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val streamUri = intent?.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            // Consume the intent so re-resume doesn't re-insert.
            intent?.action = null

            if (streamUri != null) {
                Timber.i("Share to ledger: $streamUri")
                val bundle = bundleOf("sharedImageUri" to streamUri.toString())
                // Pop any existing day fragment first: on a share cold-start the nav graph has
                // already created the start-destination day page, and two stacked day fragments
                // means two SurfaceViews fighting over the window — the stale one can win and
                // hide the freshly inserted image until the next reload.
                val navOptions = androidx.navigation.navOptions {
                    popUpTo(R.id.CalendarDayFragment) { inclusive = true }
                }
                binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundle, navOptions)
            }
        }

        val host = intent?.data?.host
        val path = intent?.data?.path
        if (host == "app") {
            if (path?.startsWith("/calendar") == true) {
                val defaultCalendarStartActionId = when (sharedPreferences.getInt("calendarStartView", 0)) {
                    0 -> R.id.action_to_calendar_day
                    1 -> R.id.action_to_calendar_week
                    2 -> R.id.action_to_calendar_month
                    3 -> R.id.action_to_calendar_quarter
                    4 -> R.id.action_to_calendar_year
                    else -> R.id.action_to_calendar_day
                }

                val calendarStartActionId = when (path) {
                    "/calendar/day" -> R.id.action_to_calendar_day
                    "/calendar/week" -> R.id.action_to_calendar_week
                    "/calendar/month" -> R.id.action_to_calendar_month
                    "/calendar/quarter" -> R.id.action_to_calendar_quarter
                    "/calendar/year" -> R.id.action_to_calendar_year
                    else -> defaultCalendarStartActionId
                }

                val bundle = Bundle()
                binding.fragmentContent.findNavController().navigate(calendarStartActionId, bundle)
            }
        }

        val refreshToken = sharedPreferences.getString("refreshToken", null)
        val refreshTokenLastUpdate = sharedPreferences.getLong("refreshTokenLastUpdate", 0L)
        val accessTokenLastUpdate = sharedPreferences.getLong("accessTokenLastUpdate", 0L)
        val now = Date.from(Instant.now()).time

        if (refreshToken != null) {
            // Request new access token after 8 hours.
            if (accessTokenLastUpdate + 60 * 60 * 8L * 1000L < now) {
                presenter.accessToken(this, refreshToken)
            }
            // Request new refresh token after 7 days.
            if (refreshTokenLastUpdate + 60 * 60 * 24 * 7L * 1000L < now) {
                presenter.refreshToken(this, refreshToken)
            }

            // Remove all tokens after 30 days.
            if (refreshTokenLastUpdate + 60 * 60 * 24 * 30L * 1000L < now) {
                sharedPreferences.edit().remove("refreshToken").apply()
                sharedPreferences.edit().remove("refreshTokenLastUpdate").apply()
                sharedPreferences.edit().remove("accessToken").apply()
                sharedPreferences.edit().remove("accessTokenLastUpdate").apply()
            }
        }
    }

    /**
     * Process access token result.
     *
     * @param accessToken the access token
     */
    fun accessTokenResult(accessToken: String) {
        sharedPreferences.edit().putString("accessToken", accessToken).apply()
        sharedPreferences.edit().putLong("accessTokenLastUpdate", Date.from(Instant.now()).time).apply()
        Timber.i("Store the new access token in shared preferences: $accessToken")
    }

    /**
     * Process refresh token result.
     *
     * @param refreshToken the refresh token
     */
    fun refreshTokenResult(refreshToken: String) {
        sharedPreferences.edit().putString("refreshToken", refreshToken).apply()
        sharedPreferences.edit().putLong("refreshTokenLastUpdate", Date.from(Instant.now()).time).apply()
        Timber.i("Store the new refresh token in shared preferences: $refreshToken")
    }

    /**
     * Get the main toolbar.
     *
     * @return the toolbar
     */
    fun getToolbar(): ToolbarBinding = binding.mainToolbar

    /**
     * Displays an error in the view.
     *
     * @param t the optional throwable
     * @param errorResId the resource id of the error
     */
    override fun showError(t: Throwable?, @StringRes errorResId: Int) {
        t?.let { Timber.e(it, getString(errorResId)) }
    }

    /**
     * Displays an error in the view.
     *
     * @param messageResId the resource id of the error
     */
    override fun showMessage(@StringRes messageResId: Int) {
        Snackbar.make(binding.mainToolbar.root, messageResId, Snackbar.LENGTH_LONG).show()
    }

    /**
     * Show progress and hide login form.
     */
    override fun showLoading() {
    }

    /**
     * Hide progress and show login form.
     */
    override fun hideLoading() {
    }

    /**
     * Instantiate the presenter.
     */
    override fun presenter(): MainPresenter {
        return MainPresenter(this, credentialService)
    }

    /**
     * OnBackPressed hook.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            orientateFragment(null)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Orientate the fragment by name.
     *
     * @param fragment the fragment
     */
    private fun orientateFragment(fragment: Fragment?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        Timber.i("Sensor portrait: ${fragment?.javaClass?.name}")
    }
}
