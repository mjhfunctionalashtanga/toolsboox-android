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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
                // Boox page-turn buttons emit either volume OR page keycodes depending on device;
                // accept both so the hardware buttons page everywhere a handler is registered.
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP -> if (h(true)) return true
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> if (h(false)) return true
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

    /** For placing a captured photo into the Ledger (today's Pickings). */
    @Inject
    lateinit var calendarDayService: com.toolsboox.plugin.calendar.fi.CalendarDayService

    // 📷 Capture — photograph handwritten content and ingest it as a Ledger object.
    private var pendingCameraFile: java.io.File? = null
    private val captureCameraLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) pendingCameraFile?.let { ingestPhotoFile(it) } }
    private val capturePickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { ingestPhotoUri(it) } }

    /**
     * The view binding.
     */
    private lateinit var binding: ActivityMainBinding

    /**
     * OnCreate hook.
     *
     * @param savedInstanceState the saved state of the instance
     */
    /**
     * Make the floating pen-note button draggable: a tap still opens Notes, but a drag repositions
     * it and persists where you put it — so it can move off whatever it's covering (e.g. the feed
     * drawer's lower-left). Clamped on-screen; restored on next launch.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeFloatButtonDraggable(view: android.view.View) {
        val prefs = getSharedPreferences("MAIN", MODE_PRIVATE)
        view.post {
            view.translationX = prefs.getFloat("floatNoteTx", 0f)
            view.translationY = prefs.getFloat("floatNoteTy", 0f)
        }
        var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f; var dragging = false
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startTx = v.translationX; startTy = v.translationY; dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        val parent = v.parent as android.view.View
                        v.translationX = (startTx + dx).coerceIn(-v.left.toFloat(), (parent.width - v.right).toFloat())
                        v.translationY = (startTy + dy).coerceIn(-v.top.toFloat(), (parent.height - v.bottom).toFloat())
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putFloat("floatNoteTx", v.translationX).putFloat("floatNoteTy", v.translationY).apply()
                        // Clean the drag's ghost trail off the e-ink panel.
                        try {
                            com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(
                                com.onyx.android.sdk.api.device.epd.UpdateMode.GC
                            )
                        } catch (t: Throwable) { /* non-Onyx device — no panel to clean */ }
                    } else if (e.eventTime - e.downTime >= 550L) {
                        v.performLongClick()   // hold-in-place → the alternate surface (Text Notes)
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun documentsRoot(): java.io.File =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)!!
        else
            java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    /** Chooser: take a photo, or pick one — then ingest it as a Ledger object. */
    private fun startCapture() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Capture to Ledger")
            .setItems(arrayOf("📷  Take a photo", "🖼  Choose from gallery")) { _, which ->
                when (which) {
                    0 -> try {
                        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
                        val photo = java.io.File(dir, "capture-${System.currentTimeMillis()}.jpg")
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
                        pendingCameraFile = photo
                        captureCameraLauncher.launch(uri)
                    } catch (e: Exception) { toast("No camera available") }
                    1 -> capturePickLauncher.launch("image/*")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ingestPhotoFile(f: java.io.File) {
        val bmp = runCatching { decodeSampled(android.net.Uri.fromFile(f)) }.getOrNull()
        f.delete()
        ingestBitmap(bmp)
    }
    private fun ingestPhotoUri(uri: android.net.Uri) = ingestBitmap(runCatching { decodeSampled(uri) }.getOrNull())

    /** Decode a photo downsampled to a sane size for a page object (avoids OOM on 12MP shots). */
    private fun decodeSampled(uri: android.net.Uri): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 2200) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Place the photo as a Ledger object (today's Pickings gram) and offer OCR. */
    private fun ingestBitmap(bmp: android.graphics.Bitmap?) {
        if (bmp == null) { toast("Couldn't read that image"); return }
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bmp, java.time.LocalDate.now(),
                        com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY,
                        sourceLabel = "📷 Photo · ${java.time.LocalDate.now()}"
                    )
                }
            }
            toast("Added to today's Pickings")
            offerOcr(bmp)   // recycles bmp when done
        }
    }

    /** If AI creds are set, offer to extract the handwriting's text and file it where it belongs. */
    private fun offerOcr(bmp: android.graphics.Bitmap) {
        val creds = aiCreds()
        if (creds == null) { bmp.recycle(); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Extract the text too?")
            .setMessage("Read the handwriting and file it — a to-do becomes a task, longer writing becomes a note.")
            .setPositiveButton("Extract text") { _, _ ->
                lifecycleScope.launch {
                    // The QUALITY GATE: structured recognition discards illegible / low-confidence /
                    // confabulated output, and classifies what's left (task | event | note | prose)
                    // so a recipe stays a recipe instead of becoming a bunk task.
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.toolsboox.plugin.calendar.nw.VisionOcr.recognizeStructured(bmp, creds.first, creds.second, creds.third)
                        }.getOrNull()
                    }
                    bmp.recycle()
                    if (result == null) { toast("Couldn't read it clearly — kept just the image"); return@launch }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val root = documentsRoot(); val today = java.time.LocalDate.now()
                            when (result.kind) {
                                "task", "event" -> {
                                    val day = calendarDayService.load(root, today, null, java.util.Locale.getDefault())
                                    day.ledgerItems.add(com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                                        id = "photo-" + java.util.UUID.randomUUID().toString().lowercase(),
                                        kind = if (result.kind == "event") com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT
                                               else com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                                        text = result.text, date = java.util.Date(), stage = "todo"))
                                    calendarDayService.save(root, today, day)
                                }
                                else -> {   // note | prose → a searchable Text Note, not a task
                                    com.toolsboox.plugin.textnotes.TextNotesStore.addNote(
                                        this@MainActivity, today, "📷 Photo · $today", result.text)
                                }
                            }
                        }
                    }
                    toast(when (result.kind) {
                        "task" -> "Filed as a task on today"
                        "event" -> "Filed as an event on today"
                        else -> "Saved as a note"
                    })
                }
            }
            .setNegativeButton("Just the image") { _, _ -> bmp.recycle() }
            // Dismissed without choosing (back / tap-outside) → still free the bitmap (no leak).
            // Only fires on cancel, not on a button tap, so the extract path keeps its bitmap.
            .setOnCancelListener { if (!bmp.isRecycled) bmp.recycle() }
            .show()
    }

    private fun aiCreds(): Triple<String, String, String>? = try {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            this, "ledger_chat_encrypted_prefs",
            androidx.security.crypto.MasterKey.Builder(this).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val provider = prefs.getString("ledger_chat_provider", "anthropic") ?: "anthropic"
        val key = prefs.getString("ledger_chat_api_key_$provider", "")?.trim().orEmpty()
        if (key.isBlank()) null else {
            val default = if (provider == "openai") "gpt-4o" else "claude-sonnet-5"
            val model = prefs.getString("ledger_chat_model_$provider", default) ?: default
            Triple(provider, key, model)
        }
    } catch (e: Exception) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Global "pull up the Notes surface" button — available on every screen. It reopens the
        // note page you last had open (same memory the menus' "✒ Notes" uses), falling back to
        // today's first page. Hold it instead to switch over to Text Notes.
        binding.floatNoteButton.setOnClickListener {
            val p = getSharedPreferences("ledger_notes", 0)
            val date = runCatching {
                java.time.LocalDate.parse(p.getString("last_note_date", "") ?: "")
            }.getOrNull() ?: java.time.LocalDate.now()
            val bundle = bundleOf(
                "year" to "${date.year}", "month" to "${date.monthValue}", "day" to "${date.dayOfMonth}",
                "notePage" to (p.getString("last_note_page", "0") ?: "0")
            )
            binding.fragmentContent.findNavController().navigate(R.id.action_to_scratch, bundle)
        }
        binding.floatNoteButton.setOnLongClickListener {
            // Hold the pen button → a small menu: Text Notes, or capture a photo into the Ledger.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(arrayOf("✎  Text Notes", "📷  Capture a photo")) { _, which ->
                    when (which) {
                        0 -> binding.fragmentContent.findNavController().navigate(R.id.action_to_text_notes)
                        1 -> startCapture()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        makeFloatButtonDraggable(binding.floatNoteButton)

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
