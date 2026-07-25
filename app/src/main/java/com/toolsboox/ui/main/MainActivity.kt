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
                    } else if (e.eventTime - e.downTime >= 550L) {
                        v.performLongClick()
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
    private val quickNoteGlyphs = arrayOf("✒", "📈", "⌱", "⌗", "📷", "🎤")

    companion object {
        /** Hold-picker entries; the index is stored as the remembered tap action, so keep order stable. */
        val QUICK_NOTE_LABELS = arrayOf(
            "Notes — where you left off",
            "Grid Notes",
            "Jot Notes",
            "Text Notes",
            "Capture a photo",
            "Record a voice gram"
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
            elevation = 10f
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

    /** Photo and voice are the same gesture — getting a thing down when there isn't time to write. */
    private fun showQuickMediaSelector() {
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
            .setTitle(R.string.quick_note_media)
            .setItems(
                arrayOf(
                    getString(R.string.quick_note_photo),
                    getString(R.string.quick_note_voice)
                )
            ) { _, which -> if (which == 0) startCapture() else requestVoiceGram() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runQuickNoteAction(which: Int) {
        when (which) {
            0 -> openLastNotePage()
            1 -> navigateToDayNote("grid")
            2 -> navigateToDayNote("sketch")
            3 -> binding.fragmentContent.findNavController().navigate(R.id.action_to_text_notes)
            4 -> startCapture()
            5 -> requestVoiceGram()
        }
    }

    /** The original behaviour: back to the page you were last writing on. */
    private fun openLastNotePage() {
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
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val root = documentsRoot(); val today = java.time.LocalDate.now()
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
                }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (The saved light/dark scheme is applied in BaseApplication.onCreate — before any activity
        // exists. Applying it here, after super.onCreate, forced a wrong-scheme first frame plus a
        // full recreate on every cold launch.)
        // Bound the re-creatable caches (article copies, later media, temp shots) —
        // daily, off-main, never touching user media or day JSONs.
        com.toolsboox.ot.CacheJanitor.runDaily(this)
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
            items += Triple(R.drawable.ic_reader_view, "On this page") { renderAndPlaceLink(url, t, "default", openDay = true) }
            items += Triple(R.drawable.ic_quote, "To Pickings") { renderAndPlaceLink(url, t, pickings, openDay = false) }
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
    private fun renderAndPlaceLink(url: String, title: String, pageKey: String, openDay: Boolean) {
        lifecycleScope.launch {
            val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(url, title, "read") }.getOrNull()
            }
            if (card != null) placeGram(card, pageKey, url, title, openDay)
            else android.widget.Toast.makeText(this@MainActivity, "Couldn't make a card", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Place [bitmap] as a gram (with provenance) on [pageKey]; reload the day when it landed there. */
    private fun placeGram(bitmap: android.graphics.Bitmap, pageKey: String, sourceLink: String, sourceLabel: String, openDay: Boolean) {
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bitmap, java.time.LocalDate.now(), pageKey,
                        sourceLink = sourceLink, sourceLabel = sourceLabel
                    )
                }
            }
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        Timber.i("Sensor portrait: ${fragment?.javaClass?.name}")
    }
}
