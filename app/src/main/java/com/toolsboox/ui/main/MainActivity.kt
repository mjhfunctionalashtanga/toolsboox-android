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

    /** Last page-key press: which key, and when — for the double-press-to-go-back gesture. */
    private var lastPageKeyCode = 0
    private var lastPageKeyAt = 0L

    /** How close two presses must be to count as one gesture. */
    private val doublePressWindowMs = 320L

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Boox page-turn buttons emit either volume OR page keycodes depending on device;
                // accept both so the hardware buttons page everywhere a handler is registered.
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                    // DOUBLE-PRESS GOES BACK, and we have to implement it ourselves.
                    //
                    // The Boox does this at system level, but only for presses the foreground app
                    // lets through — and since [ScreenFragment.onResume] began registering a page
                    // handler on EVERY screen (it used to be four), this activity consumes every
                    // volume DOWN, so the system never sees a second press and the gesture died
                    // app-wide. Michael: "double clicking the volume on Android no longer takes you
                    // back." Nothing in this app ever implemented it; it was the device's, and we
                    // started swallowing it.
                    //
                    // The first press still pages IMMEDIATELY — paging must not wait out a
                    // double-click window to find out whether it was one. So a double-press turns a
                    // page and then goes back, which is the right trade: instant paging is felt on
                    // every single press, a stray page turn on the screen you are leaving is not.
                    val now = android.os.SystemClock.uptimeMillis()
                    val isDouble = event.keyCode == lastPageKeyCode &&
                        now - lastPageKeyAt <= doublePressWindowMs
                    lastPageKeyCode = event.keyCode
                    lastPageKeyAt = now
                    if (isDouble) {
                        lastPageKeyCode = 0          // a triple press is two gestures, not three
                        onBackPressedDispatcher.onBackPressed()
                        return true
                    }
                    val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                        event.keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP
                    if (handleVolumeKey(up)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Route a page key: the registered handler when there is one, otherwise ask the fragment on
     * screen directly. The fallback matters because screens that register a handler of their own
     * (reader, feeds, day page) null the seam unconditionally in their onPause — and reordered
     * transactions run that onPause AFTER the next screen's onResume, wiping the registration the
     * next screen just made. Resolving the resumed fragment at key time can't be clobbered.
     */
    private fun handleVolumeKey(up: Boolean): Boolean {
        volumeKeyHandler?.let { return it(up) }
        val navHost = supportFragmentManager.primaryNavigationFragment ?: return false
        val current = navHost.childFragmentManager.fragments.lastOrNull { it.isResumed }
        return (current as? com.toolsboox.ui.plugin.ScreenFragment)?.dispatchVolumeKey(up) ?: false
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

    /** The pen button's unscaled square and padding (its XML values), captured before any re-size. */
    private var penButtonBase: IntArray? = null

    /**
     * Kept as a field: SharedPreferences holds listeners weakly, so an inline lambda would be
     * collected and the dial would silently stop reaching the button.
     */
    private val a11yListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Both keys: the pen button rides the PILL dial now, but that dial INHERITS the modal one
        // while it has no value of its own — so a change to either can be the one that moves it.
        // Watching only the modal key left the button stale the moment the pill dial was touched.
        if (key == com.toolsboox.ot.ModalScale.SIZE_KEY ||
            key == com.toolsboox.ot.ModalScale.PILL_SIZE_KEY) applyPenButtonScale()
    }

    /**
     * Size the floating pen button from the modal-size dial. The pills get the same treatment in
     * ScreenFragment.applyPillSizing; this is the one floating control that lives on the activity
     * instead. At Compact (0.8) the 54dp square draws at ~43dp — inside the 44dp article gutter
     * and the ~46dp book margin — while a TouchDelegate on its parent keeps the full 54dp square
     * tappable. Idempotent: scales from the remembered XML base, never from the current size.
     */
    private fun applyPenButtonScale() {
        val v = binding.floatNoteButton
        val base = penButtonBase ?: intArrayOf(v.layoutParams.width, v.layoutParams.height, v.paddingLeft)
            .also { penButtonBase = it }
        val mul = com.toolsboox.ot.ModalScale.pillScale(this)
        v.layoutParams = v.layoutParams.apply {
            width = Math.round(base[0] * mul)
            height = Math.round(base[1] * mul)
        }
        val pad = Math.round(base[2] * mul)
        v.setPadding(pad, pad, pad, pad)
        refreshPenTouchDelegate()
    }

    /**
     * Keep the pen button's TOUCH at its unscaled square even when drawn smaller. The delegate
     * rect lives in the parent's coordinates and does NOT follow view translation, so this must
     * be re-run after every drag and restore — a stale rect would tap where the button used to be.
     */
    private fun refreshPenTouchDelegate() {
        val v = binding.floatNoteButton
        v.post {
            val parent = v.parent as? android.view.View ?: return@post
            val base = penButtonBase ?: return@post
            val growW = (base[0] - v.width).coerceAtLeast(0)
            val growH = (base[1] - v.height).coerceAtLeast(0)
            if ((growW == 0 && growH == 0) || v.visibility != android.view.View.VISIBLE) {
                parent.touchDelegate = null; return@post
            }
            val r = android.graphics.Rect(
                v.left + v.translationX.toInt() - growW / 2,
                v.top + v.translationY.toInt() - growH / 2,
                v.right + v.translationX.toInt() + growW / 2,
                v.bottom + v.translationY.toInt() + growH / 2
            )
            parent.touchDelegate = android.view.TouchDelegate(r, v)
        }
    }

    /**
     * Make the floating pen-note button draggable: a tap still opens Notes, but a drag repositions
     * it and persists where you put it — so it can move off whatever it's covering (e.g. the feed
     * drawer's lower-left). Clamped on-screen; restored on next launch.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeFloatButtonDraggable(view: android.view.View) {
        val prefs = getSharedPreferences("MAIN", MODE_PRIVATE)
        // Restore through the same clamp the live drag uses: the persisted translation was saved
        // against SOME parent size, not necessarily this one, and applied raw it can park the
        // button off-screen with no handle left to drag it back. Needs the laid-out parent —
        // re-post until it has dimensions.
        view.post(object : Runnable {
            override fun run() {
                val parent = view.parent as? android.view.View ?: return
                if (parent.width == 0 || parent.height == 0) { view.post(this); return }
                view.translationX = prefs.getFloat("floatNoteTx", 0f)
                    .coerceIn(com.toolsboox.ot.PillBounds.range(view.left, view.right, parent.width))
                view.translationY = prefs.getFloat("floatNoteTy", 0f)
                    .coerceIn(com.toolsboox.ot.PillBounds.range(view.top, view.bottom, parent.height))
                refreshPenTouchDelegate()
            }
        })
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
                        // Shared with the pills. This was its own inline copy of the same clamp,
                        // and the copy was worse: with the limits reversed `coerceIn(min, max)`
                        // throws rather than pinning, so a button laid out wider than its parent
                        // took the app down mid-drag instead of merely refusing to move.
                        v.translationX = (startTx + dx)
                            .coerceIn(com.toolsboox.ot.PillBounds.range(v.left, v.right, parent.width))
                        v.translationY = (startTy + dy)
                            .coerceIn(com.toolsboox.ot.PillBounds.range(v.top, v.bottom, parent.height))
                    }
                    true
                }
                // CANCEL arrives instead of UP when an ancestor takes the gesture — which the ink
                // surfaces do. It fell through to `else -> false`, so on exactly the pages you
                // most want the pen button on, neither the tap nor the hold ever fired.
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putFloat("floatNoteTx", v.translationX)
                            .putFloat("floatNoteTy", v.translationY).apply()
                        refreshPenTouchDelegate()
                    } else if (e.eventTime - e.downTime >= 550L) {
                        v.performLongClick()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putFloat("floatNoteTx", v.translationX).putFloat("floatNoteTy", v.translationY).apply()
                        refreshPenTouchDelegate()
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

    private fun documentsRoot(): java.io.File = com.toolsboox.ot.LedgerPaths.documentsRoot(this)

    // --- The quick-note button -------------------------------------------------------------
    //
    // Hold it to choose what it does; it then WEARS that choice, so going back to the same thing
    // is a tap. The four faces are the four ways of getting something down in a hurry.

    private val quickNoteFaces = intArrayOf(
        R.drawable.ic_pencil, R.drawable.ic_reader_view, R.drawable.ic_edit,
        R.drawable.ic_toolbar_text, R.drawable.ic_camera, R.drawable.ic_mic
    )
    /** The glyph for each style, shown in the hold-out picker. Same order as the labels/faces. */
    private val quickNoteGlyphs = arrayOf("✒", "📈", "⌱", "Ⓣ", "📷", "🎤", "◈")

    companion object {
        /** Hold-picker entries; the index is stored as the remembered tap action, so keep order stable. */
        val QUICK_NOTE_LABELS = arrayOf(
            "Notes — where you left off",
            "Grid Notes",
            "Jot Notes",
            "Text Notes",
            "Capture a photo",
            "Record a voice gram",
            // APPENDED, never inserted: the index IS the remembered tap action, so putting Gram Picks
            // anywhere but the end would silently re-point everyone's remembered button at a
            // different surface. Michael: "the note modal quick jump button does not have a gram
            // picks on long hold" — it is a note surface, so it belongs in the notes picker.
            "Gram Picks"
        )
    }

    /**
     * Whether the quick-note button is shown at all.
     *
     * It floats over every screen, which is right for someone who writes constantly and wrong for
     * someone who just wants to read — so it can be put away, from the reader's wrench.
     */
    fun quickNoteVisible(): Boolean =
        getSharedPreferences("MAIN", MODE_PRIVATE).getBoolean("quick_note_visible", true)

    fun setQuickNoteVisible(visible: Boolean) {
        getSharedPreferences("MAIN", MODE_PRIVATE).edit().putBoolean("quick_note_visible", visible).apply()
        applyQuickNoteVisibility()
    }

    private fun applyQuickNoteVisibility() {
        binding.floatNoteButton.visibility =
            if (quickNoteVisible()) android.view.View.VISIBLE else android.view.View.GONE
        // The touch delegate outlives visibility on its own — a hidden button would keep
        // swallowing taps in its old corner. Keep the two in step.
        refreshPenTouchDelegate()
    }

    private fun quickNoteAction(): Int =
        getSharedPreferences("MAIN", MODE_PRIVATE).getInt("quick_note_action", 0)
            .coerceIn(0, QUICK_NOTE_LABELS.size - 1)

    private fun setQuickNoteAction(which: Int) {
        getSharedPreferences("MAIN", MODE_PRIVATE).edit().putInt("quick_note_action", which).apply()
        applyQuickNoteFace()
    }

    /** Put the current choice on the button, so you can see what a tap will do. */
    private fun applyQuickNoteFace() {
        val which = quickNoteAction()
        binding.floatNoteButton.setImageResource(quickNoteFaces.getOrElse(which) { R.drawable.ic_pencil })
        binding.floatNoteButton.contentDescription = QUICK_NOTE_LABELS.getOrElse(which) { "Notes" }
    }

    /**
     * Hold the pen button → where do you want to go.
     *
     * It used to double as a mode switch: picking an entry also re-pointed the TAP at it and
     * changed the button's face, so holding felt less like choosing a destination than like
     * flipping the button between hand notes and text notes — which is exactly what it looked
     * like from the outside, and why the selector seemed not to appear at all.
     *
     * Now the hold only ever chooses where to go THIS time. Tap keeps its one job, and the three
     * places worth reaching are the three the ledger actually keeps writing in: the note page you
     * were on, something caught as media, and the typed notes.
     */
    /**
     * Hold the pen button → a slider of note glyphs. Pick one and it becomes what a TAP does from
     * now on — the button remembers your last style and goes back to it — so the common case is a
     * single tap and only a change of style needs the hold.
     */
    private fun showQuickNoteSelector() {
        // A SLIDE-OUT tray, not a dialog (Michael: "instead of having a popup to select from,
        // have a slide out from the modal that gives those same options and then tucks back in").
        // The glyph row unfurls anchored beside the pen button — no title, no cancel, no dimmed
        // page — and any outside tap tucks it away. No animation: one clean e-ink redraw.
        val dp = resources.displayMetrics.density
        val current = quickNoteAction()
        val mul = com.toolsboox.ot.ModalScale.sizeScale(this)
        fun px(v: Int) = (v * dp * mul).toInt()
        val glyphSize = 26f * mul
        lateinit var popup: android.widget.PopupWindow
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(px(8), px(6), px(8), px(6))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity, R.drawable.dialog_rounded_bg)
        }
        quickNoteGlyphs.forEachIndexed { i, glyph ->
            row.addView(android.widget.TextView(this).apply {
                text = glyph
                textSize = glyphSize
                gravity = android.view.Gravity.CENTER
                setPadding(px(12), px(8), px(12), px(8))
                setTextColor(if (i == current) 0xFF000000.toInt() else 0xFF999999.toInt())
                if (i == current) setBackgroundResource(R.drawable.tool_active_bg)
                contentDescription = QUICK_NOTE_LABELS.getOrElse(i) { "" }
                setOnClickListener {
                    popup.dismiss()
                    setQuickNoteAction(i)   // remember it — a tap returns here next time
                    runQuickNoteAction(i)   // …and go there now
                }
            })
        }
        com.toolsboox.ot.LedgerFonts.applyTree(row)
        popup = android.widget.PopupWindow(row,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 0f   // flat e-ink modal — no drop shadow (matches the app's other surfaces)
        }
        // Unfurl to the RIGHT of the pen button, vertically centred on it.
        val anchor = binding.floatNoteButton
        row.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(anchor, anchor.width + px(6),
            -(anchor.height + row.measuredHeight) / 2)
    }

    /** Jump to today's page for a given note-page key (grid/sketch), from the pen-button menu. */
    private fun navigateToDayNote(notePage: String) {
        val d = java.time.LocalDate.now()
        val bundle = androidx.core.os.bundleOf(
            "year" to d.year.toString(), "month" to d.monthValue.toString(),
            "day" to d.dayOfMonth.toString(), "notePage" to notePage)
        binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundle)
    }

    private fun runQuickNoteAction(which: Int) {
        when (which) {
            0 -> openLastNotePage()
            1 -> navigateToDayNote("grid")
            2 -> navigateToDayNote("sketch")
            3 -> binding.fragmentContent.findNavController().navigate(R.id.action_to_text_notes)
            4 -> startCapture()
            5 -> requestVoiceGram()
            6 -> navigateToDayNote(com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS)
        }
    }

    /** Note button, two-tap toggle:
     *  • not on a note → resume the last note page you were writing on;
     *  • already on that note → flip to that day's page ("days").
     *  The view state (on-note? which date?) is recorded by CalendarDayFragment on every load. */
    private fun openLastNotePage() {
        val p = getSharedPreferences("ledger_notes", 0)
        val nav = binding.fragmentContent.findNavController()
        // Live check — are we ACTUALLY on the note surface right now? (The scratch note is its own
        // nav destination.) A persisted flag went stale and flipped to the schedule unexpectedly.
        if (nav.currentDestination?.id == R.id.CalendarScratchFragment) {
            // Second tap — you're on your note → open that day's day page.
            val d = runCatching { java.time.LocalDate.parse(p.getString("current_view_date", "") ?: "") }
                .getOrNull() ?: java.time.LocalDate.now()
            nav.navigate(R.id.action_to_calendar_day, bundleOf(
                "year" to "${d.year}", "month" to "${d.monthValue}", "day" to "${d.dayOfMonth}"))
        } else {
            // First tap — TODAY's notes, resuming the page only if you were on it today.
            //
            // Same fix as CalendarNavigator.toLastDayNote, and for the same reason: resuming a
            // remembered date with no staleness bound stranded this button on whatever day was last
            // written on, so new ink piled onto an old day's page. See that function's comment.
            val today = java.time.LocalDate.now()
            val remembered = runCatching { java.time.LocalDate.parse(p.getString("last_note_date", "") ?: "") }
                .getOrNull()
            val page = if (remembered == today) (p.getString("last_note_page", "0") ?: "0") else "0"
            nav.navigate(R.id.action_to_scratch, bundleOf(
                "year" to "${today.year}", "month" to "${today.monthValue}", "day" to "${today.dayOfMonth}",
                "notePage" to page))
        }
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    /** Chooser: take a photo, or pick one — then ingest it as a Ledger object. */
    private fun startCapture() {
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
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

    // 🎤 Record a voice gram from the floating pen button — reachable from anywhere in the app,
    // not just a page you happen to be standing on.
    private val micPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoiceGram() else toast("Microphone permission is needed to record") }

    private fun requestVoiceGram() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) startVoiceGram()
        else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceGram() {
        val dir = com.toolsboox.ot.LedgerPaths.attachmentsDir(this)
        com.toolsboox.ot.VoiceRecorder.record(
            context = this,
            out = java.io.File(dir, "voice-${UUID.randomUUID()}.m4a"),
            recordingLabel = { clock -> getString(R.string.reader_capture_recording, clock) },
            stopLabel = getString(R.string.reader_capture_stop),
            onSaved = { file, seconds ->
                val att = com.toolsboox.da.Attachment(
                    UUID.randomUUID().toString(), com.toolsboox.da.Attachment.Kind.AUDIO,
                    file.name, seconds, Date()
                )
                lifecycleScope.launch {
                    val placed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.toolsboox.plugin.calendar.ot.AvGrams.file(
                            calendarDayService, documentsRoot(), file, att
                        )
                    }
                    // AvGrams.file writes BOTH the attachment and the poster gram into today's day
                    // file. The mic is on the floating pen button, so this fires from every screen
                    // in the app — including the day page you were writing on when you hit record.
                    if (placed) tellSurfaceGramPlaced(com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY)
                    toast(if (placed) "🎤 Voice gram → today's Pickings" else "Voice gram saved")
                }
            }
        )
    }

    override fun onStop() {
        super.onStop()
        // Never leave the microphone open behind a backgrounded app; keep the memo.
        com.toolsboox.ot.VoiceRecorder.stop(save = true)
    }

    /**
     * The screen the user is actually looking at, or null.
     *
     * Same resolution [handleVolumeKey] uses, and for the same reason: the activity has no direct
     * handle on the current screen, and anything it caches goes stale across a navigation. Ask the
     * nav host at the moment it matters.
     */
    private fun resumedScreen(): com.toolsboox.ui.plugin.ScreenFragment? {
        val navHost = supportFragmentManager.primaryNavigationFragment ?: return null
        val current = navHost.childFragmentManager.fragments.lastOrNull { it.isResumed }
        return current as? com.toolsboox.ui.plugin.ScreenFragment
    }

    /**
     * We just wrote something into today's day file from up here. Tell the screen below.
     *
     * THE WHOLE ACTIVITY IS A BACKGROUND WRITER. Capture, voice grams, shared links and OCR filing
     * all run in [lifecycleScope] against today's day JSON while some fragment — very often today's
     * own day page — sits RESUMED holding its own copy of that day. The write lands, the toast says
     * it landed, and then the fragment's next pen-up save serialises the copy it has been holding
     * since it loaded, which does not contain what we just wrote. Michael's rule for this whole
     * class: a write that succeeds while the surface never learns about it is a write that is going
     * to be lost. [ScreenFragment.onExternalGramPlaced] is the telling; the surface re-reads and
     * never saves, because saving is the thing that destroys the record.
     *
     * Only ever called after the write actually SUCCEEDED — an unnecessary re-read is a full e-ink
     * redraw, and on a failed write there is nothing new on disk to go and get.
     */
    private fun tellSurfaceGramPlaced(pageKey: String) {
        runCatching { resumedScreen()?.onExternalGramPlaced(pageKey) }
    }

    /** The same telling for the text-note store — see [ScreenFragment.onExternalNoteAdded]. */
    private fun tellSurfaceNoteAdded(date: java.time.LocalDate) {
        runCatching { resumedScreen()?.onExternalNoteAdded(date) }
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
            val key = com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY
            val placed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bmp, java.time.LocalDate.now(),
                        key, sourceLabel = "📷 Photo · ${java.time.LocalDate.now()}"
                    )
                }.isSuccess
            }
            // Same telling as the OCR path below, and needed a step earlier: the photo gram itself
            // goes into today's day file from up here, so a day page on screen is stale from the
            // moment the shutter closes — before the OCR dialog has even been offered.
            if (placed) tellSurfaceGramPlaced(key)
            toast("Added to today's Pickings")
            offerOcr(bmp)   // recycles bmp when done
        }
    }

    /** If AI creds are set, offer to extract the handwriting's text and file it where it belongs. */
    private fun offerOcr(bmp: android.graphics.Bitmap) {
        val creds = aiCreds()
        if (creds == null) { bmp.recycle(); return }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
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
                    // PREVIEW-AND-CONFIRM (converged with iOS): nothing files without a glance.
                    // The model's kind is only the SUGGESTION (the lead button); the human is
                    // the final classifier — this is what makes bunk tasks structurally impossible.
                    confirmOcrFiling(result)
                }
            }
            .setNegativeButton("Just the image") { _, _ -> bmp.recycle() }
            // Dismissed without choosing (back / tap-outside) → still free the bitmap (no leak).
            // Only fires on cancel, not on a button tap, so the extract path keeps its bitmap.
            .setOnCancelListener { if (!bmp.isRecycled) bmp.recycle() }
            .show()
    }

    /** Show what was read; the user confirms where it files (suggested kind leads). */
    private fun confirmOcrFiling(result: com.toolsboox.plugin.calendar.nw.VisionOcr.OcrResult) {
        val suggestTask = result.kind == "task" || result.kind == "event"
        val suggested = when (result.kind) {
            "task" -> "Save as a task"; "event" -> "Save as an event"; else -> "Save as a note"
        }
        val alternate = if (suggestTask) "Save as a note" else "Save as a task"
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
            .setTitle("Reads:")
            .setMessage(result.text.take(400))
            .setPositiveButton(suggested) { _, _ -> fileOcrText(result.text, asTask = suggestTask, asEvent = result.kind == "event") }
            .setNeutralButton(alternate) { _, _ -> fileOcrText(result.text, asTask = !suggestTask, asEvent = false) }
            .setNegativeButton("Just the image", null)
            .show()
    }

    private fun fileOcrText(text: String, asTask: Boolean, asEvent: Boolean) {
        lifecycleScope.launch {
            val today = java.time.LocalDate.now()
            val filed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot()
                    if (asTask) {
                        val day = calendarDayService.load(root, today, null, java.util.Locale.getDefault())
                        day.ledgerItems.add(com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                            id = "photo-" + java.util.UUID.randomUUID().toString().lowercase(),
                            kind = if (asEvent) com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT
                                   else com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                            text = text, date = java.util.Date(), stage = "todo"))
                        calendarDayService.save(root, today, day)
                    } else {
                        com.toolsboox.plugin.textnotes.TextNotesStore.addNote(
                            this@MainActivity, today, "📷 Photo · $today", text)
                    }
                }.isSuccess
            }
            // Tell the surface BEFORE the toast, so the words "filed as a task on today" are true of
            // the page as well as of the file. A photographed to-do went into today's day JSON from
            // this IO block while today's day page was very plausibly the screen it was photographed
            // from — and that page's next pen-up wrote its pre-OCR copy of the day straight back
            // over the task. Confirmed silent loss; the task was saved and then quietly unsaved.
            if (filed) {
                if (asTask) tellSurfaceGramPlaced(com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY)
                else tellSurfaceNoteAdded(today)
            }
            toast(if (asEvent) "Filed as an event on today" else if (asTask) "Filed as a task on today" else "Saved as a note")
        }
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

    /** Screen going dark = the honest commit point for sync: the pen is down, the case is
     *  closing. Fires the CHEAP day mirror only — see [com.toolsboox.plugin.calendar.nw.QuickDayMirror]. */
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == android.content.Intent.ACTION_SCREEN_OFF) {
                // BOTH barrels, deliberately. The direct thread wins when the network survives
                // the screen going dark — but Onyx's power manager cuts app networking AT
                // screen-off (observed live: every DNS lookup, ours and googleapis alike, dies
                // with EAI_NODATA the moment the panel sleeps, appops notwithstanding). So the
                // WorkManager one-shot rides along: it holds the CONNECTED constraint and runs
                // the moment the system allows — usually seconds later in the maintenance
                // window, at worst on next wake. Late by moments beats lost by hours.
                com.toolsboox.plugin.calendar.nw.QuickDayMirror.fire(context, "screen-off")
                com.toolsboox.plugin.calendar.nw.UltrabridgeSyncWorker.syncNow(context)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ACTION_SCREEN_OFF is delivery-only-to-registered receivers (no manifest route), and the
        // registration dies with the activity — which is exactly the scope wanted: an app that
        // isn't running has nothing unsynced to push.
        registerReceiver(screenOffReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        // (The saved light/dark scheme is applied in BaseApplication.onCreate — before any activity
        // exists. Applying it here, after super.onCreate, forced a wrong-scheme first frame plus a
        // full recreate on every cold launch.)
        // Bound the re-creatable caches (article copies, later media, temp shots) —
        // daily, off-main, never touching user media or day JSONs.
        com.toolsboox.ot.CacheJanitor.runDaily(this)
        // Which way up, before the first frame. The three controls that turn the screen all set
        // `requestedOrientation`, which is activity state and dies with the process — so a cold
        // start fell back to the manifest's `sensorPortrait` no matter what had been chosen, and
        // the gyro switch appeared to turn itself back on overnight. Re-asserted here rather than
        // after `setContentView` so the window is laid out once, in the right orientation: doing it
        // later costs a visible re-layout, which on e-ink is a full flashing redraw.
        com.toolsboox.ot.ScreenRotation.restore(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Global "pull up the Notes surface" button — available on every screen. It reopens the
        // note page you last had open (same memory the menus' "✒ Notes" uses), falling back to
        // today's first page. Hold it instead to switch over to Text Notes.
        binding.floatNoteButton.setOnClickListener { runQuickNoteAction(quickNoteAction()) }
        binding.floatNoteButton.setOnLongClickListener { showQuickNoteSelector(); true }
        makeFloatButtonDraggable(binding.floatNoteButton)
        applyQuickNoteFace()
        applyQuickNoteVisibility()
        // The pen button honours the modal-size dial like every floating pill — at Compact its
        // drawn square slims to reading-gutter width. Re-applied live when the dial changes
        // (one relayout, no animation — a single clean e-ink redraw).
        applyPenButtonScale()
        getSharedPreferences("ledger_a11y", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(a11yListener)

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
            val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
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
     * The share chooser — a shared item becomes a GRAM (a card with the source clickable back to it)
     * on the current page or in Pickings, gets filed to read-later, or drops as a text box. Mirrors
     * the iPad "Add to Ledger" chooser. Which destinations show depends on what was shared.
     */
    private fun offerShareDestinations(
        url: String?, title: String?, leftoverText: String?, sharedText: String?, imageUri: android.net.Uri?
    ) {
        val pickings = com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY
        val preview = (title ?: url ?: leftoverText ?: sharedText)?.trim().orEmpty()
        val items = mutableListOf<Triple<Int, String, () -> Unit>>()

        if (imageUri != null) {
            items += Triple(R.drawable.ic_image, "On this page") { openSharedImageOnDay(imageUri) }
            items += Triple(R.drawable.ic_quote, "To Pickings") {
                val bmp = decodeSharedImage(imageUri)
                if (bmp != null) placeGram(bmp, pickings, "", "Shared image", openDay = false)
                else android.widget.Toast.makeText(this, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else if (url != null) {
            val t = title ?: url
            // What the sharing app sent BESIDES the link and its title is the link's own blurb —
            // that's the excerpt band on the card. (ShareTextParser already pulled the url and
            // title out of the shared text; what's left is the description the app offered.)
            val blurb = leftoverText?.trim().orEmpty()
            items += Triple(R.drawable.ic_reader_view, "On this page") { renderAndPlaceLink(url, t, blurb, "default", openDay = true) }
            items += Triple(R.drawable.ic_quote, "To Pickings") { renderAndPlaceLink(url, t, blurb, pickings, openDay = false) }
            items += Triple(R.drawable.ic_bookmark, "Later — read / watch / listen") { offerToFileLink(url, title, sharedText) }
            items += Triple(R.drawable.ic_edit, "As text") {
                val boxText = wrapForTextBox(listOfNotNull(title, url).joinToString("\n").ifBlank { sharedText?.trim().orEmpty() })
                if (boxText.isNotBlank()) dropTextOnDay(boxText, url)
            }
        } else {
            val text = wrapForTextBox(listOfNotNull(title, leftoverText).joinToString("\n").ifBlank { sharedText?.trim().orEmpty() })
            if (text.isBlank()) return
            items += Triple(R.drawable.ic_edit, "On this page (text)") { dropTextOnDay(text, null) }
            items += Triple(R.drawable.ic_quote, "To Pickings") {
                lifecycleScope.launch {
                    val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(text.take(600), "", "", 1080, 0) }.getOrNull()
                    }
                    if (card != null) placeGram(card, pickings, "", "Shared note", openDay = false)
                }
            }
        }

        // Defer to after the first layout — a dialog straight from onResume on a share cold-start can
        // be swallowed before the window is ready. (Same pattern as offerToFileLink.)
        binding.fragmentContent.post {
            val dp = resources.displayMetrics.density
            val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
                .setTitle("Add to Ledger")
                .setView(androidx.core.widget.NestedScrollView(this).apply { addView(list) })
                .create()
            if (preview.isNotBlank()) list.addView(android.widget.TextView(this).apply {
                text = preview.take(140)
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
                setTextColor(0xFF666666.toInt()); textSize = 13f
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            for ((iconRes, itemLabel, action) in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<android.widget.ImageView>(R.id.go_icon).apply { setImageResource(iconRes); visibility = android.view.View.VISIBLE }
                r.findViewById<android.widget.TextView>(R.id.go_label).text = itemLabel
                r.setOnClickListener { dialog.dismiss(); action() }
                list.addView(r)
            }
            dialog.show()
        }
    }

    /** Render a shared link into a card and place it as a gram (with provenance) on [pageKey]. */
    private fun renderAndPlaceLink(url: String, title: String, excerpt: String, pageKey: String, openDay: Boolean) {
        lifecycleScope.launch {
            val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // The kind was hard-coded "read", so a shared YouTube link wore 🔖 READ and landed
                // in the wrong Intake quarter — the iPad has always inferred it from the host
                // (FeedNoteGram.kind(for:)). ShareTextParser.inferKind is the Boox's copy of that
                // same table, already used by the day page's "Intake a link".
                val kind = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.inferKind(url)
                runCatching {
                    com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                        url, title, kind, excerpt = excerpt)
                }.getOrNull()
            }
            if (card != null) placeGram(card, pageKey, url, title, openDay)
            else android.widget.Toast.makeText(this@MainActivity, "Couldn't make a card", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Place [bitmap] as a gram (with provenance) on [pageKey]; reload the day when it landed there. */
    private fun placeGram(bitmap: android.graphics.Bitmap, pageKey: String, sourceLink: String, sourceLabel: String, openDay: Boolean) {
        lifecycleScope.launch {
            val placed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bitmap, java.time.LocalDate.now(), pageKey,
                        sourceLink = sourceLink, sourceLabel = sourceLabel
                    )
                }.isSuccess
            }
            // Only the STAY branch needs telling — `openDay` navigates, and arriving at the day page
            // loads the day fresh. A share that stays put drops the caller back onto whatever screen
            // it interrupted, holding a day that predates the card by a second.
            if (placed && !openDay) tellSurfaceGramPlaced(pageKey)
            if (openDay) {
                val navOptions = androidx.navigation.navOptions { popUpTo(R.id.CalendarDayFragment) { inclusive = true } }
                runCatching { binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, null, navOptions) }
            } else {
                android.widget.Toast.makeText(this@MainActivity, "Added to Pickings", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The pre-existing "shared image onto today's page" behavior — a movable image element. */
    private fun openSharedImageOnDay(imageUri: android.net.Uri) {
        val bundle = bundleOf("sharedImageUri" to imageUri.toString())
        val navOptions = androidx.navigation.navOptions { popUpTo(R.id.CalendarDayFragment) { inclusive = true } }
        binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundle, navOptions)
    }

    /**
     * Decode a shared image DOWNSAMPLED, never full-size. A 12MP camera share decoded whole is a
     * ~48MB bitmap — enough to OOM a Palma — and whatever lands here gets base64'd into the day
     * JSON by the gram placement path, so oversized pixels become permanent oversized JSON (the
     * known media-bloat problem). Bounds first, then a power-of-two sample that brings the longest
     * edge inside 2048px, then a JPEG ~85 round-trip: the lossy pass smooths sensor noise, which
     * is what lets the placement path's PNG re-encode actually compress a photograph.
     */
    private fun decodeSharedImage(uri: android.net.Uri): android.graphics.Bitmap? = runCatching {
        val maxEdge = 2048
        // Pass 1: bounds only — no pixels allocated yet.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        // Smallest power of two that lands the longest edge inside maxEdge.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        // Pass 2: the real decode at the computed sample.
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null
        // Pass 3: the JPEG round-trip that keeps the eventual day-JSON payload sane.
        val baos = java.io.ByteArrayOutputStream()
        if (!decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)) return@runCatching decoded
        val settled = android.graphics.BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
        if (settled != null && settled !== decoded) decoded.recycle()
        settled ?: decoded
    }.getOrNull()

    /**
     * Activity onResume.
     */
    override fun onResume() {
        super.onResume()

        // Share-to-Ledger (text/link): a chooser offers where it lands — a GRAM (a card with the
        // source clickable) on this page or in Pickings, filed to read/watch/listen, or as a text box.
        if (intent?.action == android.content.Intent.ACTION_SEND && intent?.type == "text/plain") {
            val sharedText = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
            val sharedSubject = intent?.getStringExtra(android.content.Intent.EXTRA_SUBJECT)
            // Consume the intent so re-resume doesn't re-navigate.
            intent?.action = null

            val parsed = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.parse(sharedText, sharedSubject)
            Timber.i("Share to ledger (text): url=${parsed.url}")
            offerShareDestinations(parsed.url, parsed.title, parsed.leftoverText, sharedText, null)
        }

        // Share-to-Ledger target: an image shared from Gallery or any app — same chooser (on this
        // page as a movable image, or into Pickings as a gram).
        if (intent?.action == android.content.Intent.ACTION_SEND && intent?.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val streamUri = intent?.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            // Consume the intent so re-resume doesn't re-insert.
            intent?.action = null

            if (streamUri != null) {
                Timber.i("Share to ledger (image): $streamUri")
                offerShareDestinations(null, null, null, null, streamUri)
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

        // Widget tap-through: each home-screen widget names the surface it is a window onto
        // ("mail" / "feeds" / "allstars"; the day-page widgets carry no name and land on the start
        // destination, which IS the day page). Consumed like the share intents above, so a
        // re-resume doesn't re-navigate.
        intent?.getStringExtra("widgetDest")?.let { dest ->
            intent?.removeExtra("widgetDest")
            val nav = binding.fragmentContent.findNavController()
            runCatching {
                when (dest) {
                    "mail" -> nav.navigate(R.id.action_to_mail_inbox)
                    "feeds" -> nav.navigate(R.id.action_to_feeds)
                    "allstars" -> {
                        val d = java.time.LocalDate.now()
                        nav.navigate(R.id.action_to_calendar_day, bundleOf(
                            "year" to "${d.year}", "month" to "${d.monthValue}",
                            "day" to "${d.dayOfMonth}", "notePage" to "intake"))
                    }
                }
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
        Timber.i("Stored a new access token (${accessToken.length} chars)")
    }

    /**
     * Process refresh token result.
     *
     * @param refreshToken the refresh token
     */
    fun refreshTokenResult(refreshToken: String) {
        sharedPreferences.edit().putString("refreshToken", refreshToken).apply()
        sharedPreferences.edit().putLong("refreshTokenLastUpdate", Date.from(Instant.now()).time).apply()
        Timber.i("Stored a new refresh token (${refreshToken.length} chars)")
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
        // Re-assert the user's CHOICE, not sensor-portrait.
        //
        // This hardcoded SENSOR_PORTRAIT and runs on every back-press that pops the stack, so
        // going back silently undid a locked or gyro-driven screen MID-SESSION — rotate, press
        // Back, and you were portrait again with nothing on screen to say why. Together with the
        // orientation never surviving a restart, that made the rotate controls look broken from
        // two directions at once.
        //
        // The remembered orientation is the honest thing to return to; with nothing remembered it
        // falls back to sensor-portrait, which is what this line was reaching for all along.
        com.toolsboox.ot.ScreenRotation.restore(this, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        Timber.i("Orientation re-asserted after back: ${fragment?.javaClass?.name}")
    }
}
