package com.toolsboox.ui.plugin

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.toolsboox.R
import com.toolsboox.da.Attachment
import com.toolsboox.databinding.ToolbarBinding
import com.toolsboox.ui.main.MainActivity
import timber.log.Timber
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * The fragment base class.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
abstract class ScreenFragment : Fragment() {
    companion object {

        /**
         * Result code of ask permissions.
         */
        const val REQUEST_PERMISSIONS = 12345

        /**
         * User already asked for permissions.
         */
        private var askedForPermissions: Boolean = false
    }

    /**
     * The toolbar of the parent activity.
     */
    lateinit var toolbar: ToolbarBinding

    /**
     * The view resource.
     */
    protected open val view: Int? = null

    /**
     * OnCreateView hook.
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val createdView = view?.let { inflater.inflate(it, container, false) }
        toolbar = (requireActivity() as MainActivity).getToolbar()
        return createdView
    }

    /**
     * Show the 'something happened' error...
     *
     * @param silent log only the error
     * @param t the optional throwable
     * @param parentView the optional parent view of the snackbar
     */
    fun somethingHappened(silent: Boolean = false, t: Throwable? = null, parentView: View? = null) {
        runOnActivity {
            showError(t, R.string.something_happened_error, parentView, silent)
        }
    }

    /**
     * Show the 'something happened' error...
     *
     * @param t the optional throwable
     * @param parentView the optional parent view of the snackbar
     */
    fun somethingHappened(t: Throwable? = null, parentView: View? = null) {
        runOnActivity {
            showError(t, R.string.something_happened_error, parentView)
        }
    }

    /**
     * Displays an error in the view.
     *
     * @param t the optional throwable
     * @param errorResId the resource id of the error
     * @param parentView the optional parent view of the snackbar
     * @param silent log only the error
     */
    open fun showError(t: Throwable?, @StringRes errorResId: Int, parentView: View? = null, silent: Boolean = false) {
        t?.let { Timber.e(it, getString(errorResId)) }
        if (silent) return

        val snackbar = Snackbar.make(
            parentView?.let { parentView } ?: toolbar.root, errorResId, Snackbar.LENGTH_INDEFINITE
        )
        snackbar.setAction(R.string.something_happened_action) {}
        snackbar.show()
    }

    /**
     * Displays an error in the view.
     *
     * @param message the message
     * @param parentView the optional parent view of the snackbar
     */
    open fun showMessage(message: String, parentView: View? = null) {
        Snackbar.make(parentView?.let { parentView } ?: toolbar.root, message, Snackbar.LENGTH_LONG).show()
    }

    /**
     * Displays an error in the view.
     *
     * @param messageResId the resource id of the error
     * @param parentView the optional parent view of the snackbar
     */
    open fun showMessage(@StringRes messageResId: Int, parentView: View? = null) {
        showMessage(getString(messageResId), parentView)
    }

    /**
     * Call the function on the foreground activity only.
     *
     * @param call the function
     */
    fun runOnActivity(call: () -> Unit) {
        activity?.let {
            if (isAdded) call()
        }
    }

    /**
     * Displays the loading indicator of the view.
     */
    abstract fun showLoading()

    /**
     * Hides the loading indicator of the view.
     */
    abstract fun hideLoading()

    /**
     * Drag [handle] to move [pill] freely (via translation), persisted under [key].
     * A plain tap on the handle (no drag) fires [onTap] — used to collapse/expand the
     * pill, so the grip itself is the obvious affordance, not just the small caret.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    protected fun makeDraggable(handle: View, pill: View, key: String, onTap: (() -> Unit)? = null) {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        // NOTE: keys are versioned (`_px`/`_py`). The pill redesign changed each pill's
        // anchored home, so positions saved by earlier builds are meaningless and would
        // strand a pill off-screen — discard them by not reading the old `_tx`/`_ty` keys.
        pill.translationX = prefs.getFloat("${key}_px", 0f)
        pill.translationY = prefs.getFloat("${key}_py", 0f)
        // Clamp on first layout so a restored position can never leave the pill clipped.
        pill.post { clampInParent(pill) }
        val slop = 12f * resources.displayMetrics.density
        var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f
        var downAt = 0L; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startTx = pill.translationX; startTy = pill.translationY
                    downAt = System.currentTimeMillis(); moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    pill.translationX = startTx + (e.rawX - downX)
                    pill.translationY = startTy + (e.rawY - downY)
                    clampInParent(pill)
                    if (kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop) moved = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (onTap != null && !moved && e.actionMasked == MotionEvent.ACTION_UP &&
                        System.currentTimeMillis() - downAt < 350
                    ) {
                        // Tap (not a drag) → toggle collapse; snap the pill back to where it was.
                        pill.translationX = startTx; pill.translationY = startTy
                        onTap()
                    } else {
                        clampInParent(pill)
                        prefs.edit().putFloat("${key}_px", pill.translationX)
                            .putFloat("${key}_py", pill.translationY).apply()
                    }
                    // The pill moved/collapsed → re-feed its bounds as a stylus exclude rect.
                    pill.post { (this@ScreenFragment as? SurfaceFragment)?.refreshRawExcludeRects() }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Wire the shared floating nav pill on an almanac page (month/quarter/week/year) so it
     * behaves like the day-page pill: the grip drags and taps-to-collapse, ↑ performs the
     * page's swipe-up, ↓ its swipe-down, and the centre glyph jumps to the current period.
     * All the almanac pages reuse this so navigation feels identical across the ledger.
     */
    protected fun setupAlmanacNavPill(
        navWidget: View, navGrip: View, navUp: View, navDown: View,
        navGoto: ImageView, swipeUp: View, swipeDown: View,
        @androidx.annotation.DrawableRes iconRes: Int,
        isAtPresent: () -> Boolean = { false }, onHome: () -> Unit
    ) {
        navWidget.visibility = View.VISIBLE
        navUp.setOnClickListener { swipeUp.performClick() }
        navDown.setOnClickListener { swipeDown.performClick() }
        navGoto.setImageResource(iconRes)
        // First tap → jump to the present period; a second tap (already on the present
        // period) brings down the Ledger section menu.
        navGoto.setOnClickListener {
            if (isAtPresent()) showSectionMenu()
            else onHome()
        }
        navWidget.bringToFront()

        // Vertical on narrow (phone) screens so it can't collide with the tool pill.
        val narrow = resources.configuration.screenWidthDp < 520
        val vertical = requireContext().getSharedPreferences("ledger_widgets", 0)
            .getBoolean("vertical", narrow)
        (navWidget as? LinearLayout)?.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        makeDraggable(navGrip, navWidget, "nav") { toggleNavPill(navUp, navDown) }
        applyNavPillCollapse(navUp, navDown)
    }

    /**
     * Cycle the screen orientation through the user's allowed set (rotationOrientationMask) — the
     * same rotate the day page's wrench does, shared so the feed/reader "quick controls" can rotate too.
     */
    protected fun cycleScreenOrientation() {
        val activity = requireActivity()
        val current = activity.requestedOrientation
        val prefs = requireContext().getSharedPreferences("MAIN", 0)
        val mask = prefs.getInt("rotationOrientationMask", 0b1111)
        val cycle = mutableListOf<Int>()
        if (mask and 0b0001 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        if (mask and 0b0010 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        if (mask and 0b0100 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
        if (mask and 0b1000 != 0) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        if (cycle.isEmpty()) cycle.add(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val idx = cycle.indexOf(current).takeIf { it >= 0 } ?: -1
        activity.requestedOrientation = cycle[(idx + 1) % cycle.size]
    }

    /** Collapse the almanac nav pill to grip + centre glyph (↑↓ hide); tap the grip to toggle. */
    private fun toggleNavPill(navUp: View, navDown: View) {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        prefs.edit().putBoolean("nav_collapsed", !prefs.getBoolean("nav_collapsed", false)).apply()
        applyNavPillCollapse(navUp, navDown)
    }

    private fun applyNavPillCollapse(navUp: View, navDown: View) {
        val collapsed = requireContext().getSharedPreferences("ledger_widgets", 0)
            .getBoolean("nav_collapsed", false)
        val vis = if (collapsed) View.GONE else View.VISIBLE
        navUp.visibility = vis
        navDown.visibility = vis
    }

    // --- Shared annotation capture (highlight / photo / voice), used by book + feed readers ---

    /** Sink for a completed capture: (highlight excerpt, typed note, media attachment). */
    private var captureSink: ((String?, String?, Attachment?) -> Unit)? = null
    /** Sink for a standalone A/V gram (no highlight/note) — takes precedence when set. */
    private var gramSink: ((Attachment) -> Unit)? = null
    private var captureSelection: String = ""
    private var captureSource: String? = null
    private var pendingCameraFile: File? = null
    private var pendingCameraUri: Uri? = null
    private var pendingVideoFile: File? = null

    /** Persistent per-app store for annotation media; referenced by filename in the day JSON. */
    protected fun attachmentsDir(): File =
        File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "attachments").apply { mkdirs() }

    private val annGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importGalleryPhoto(uri) else captureSink = null
        }

    private val annCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val src = pendingCameraFile
            if (ok && src != null && src.exists()) {
                val dest = File(attachmentsDir(), "photo-${UUID.randomUUID()}.jpg")
                runCatching { src.copyTo(dest, overwrite = true) }
                src.delete()
                emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.PHOTO, dest.name, null, Date()))
            } else captureSink = null
            pendingCameraFile = null; pendingCameraUri = null
        }

    private val annMicPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceRecording()
            else { showMessage(R.string.reader_capture_mic_denied); captureSink = null; gramSink = null }
        }

    private val annVideoLauncher =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
            val src = pendingVideoFile
            if (ok && src != null && src.exists()) {
                val dest = File(attachmentsDir(), "video-${UUID.randomUUID()}.mp4")
                runCatching { src.copyTo(dest, overwrite = true) }; src.delete()
                emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.VIDEO, dest.name, null, Date()))
            } else { gramSink = null; captureSink = null }
            pendingVideoFile = null
        }

    /**
     * Capture a standalone A/V gram (a photo / voice / video note-to-self) for the day. Reuses
     * the shared photo & voice machinery; [onGram] persists the resulting attachment into the
     * day's avGrams.
     */
    protected fun captureAvGram(onGram: (Attachment) -> Unit) {
        gramSink = onGram
        captureSink = null
        captureSelection = ""
        showIconMenu(getString(R.string.gram_capture_title), listOf(
            getString(R.string.reader_capture_photo) to { launchAnnCamera() },
            getString(R.string.reader_capture_upload) to { annGalleryLauncher.launch("image/*") },
            getString(R.string.reader_capture_voice) to { requestVoiceRecording() },
            getString(R.string.gram_capture_video) to { launchAnnVideo() }
        ))
    }

    private fun launchAnnVideo() {
        try {
            val dir = File(requireContext().cacheDir, "camera").apply { mkdirs() }
            val vid = File(dir, "clip-${SystemClock.elapsedRealtimeNanos()}.mp4")
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", vid)
            pendingVideoFile = vid
            annVideoLauncher.launch(uri)
        } catch (e: Exception) {
            Timber.w(e, "video capture unavailable")
            showMessage(R.string.reader_capture_no_camera); gramSink = null
        }
    }

    /**
     * Open the capture menu for a reader annotation: keep the highlight [selection] (may be
     * blank), then let the reader add a note, take/upload a photo, or record a voice memo.
     * [onCapture] persists the result for the calling surface (book vs article ReadingEvent).
     */
    protected fun captureAnnotation(
        selection: String,
        sourceTitle: String? = null,
        onCapture: (String?, String?, Attachment?) -> Unit
    ) {
        captureSelection = selection
        captureSource = sourceTitle
        captureSink = onCapture
        gramSink = null
        val noteLabel = if (selection.isNotBlank()) getString(R.string.reader_capture_highlight_note)
        else getString(R.string.reader_capture_note)
        val options = mutableListOf<Pair<String, () -> Unit>>(
            "🖍  $noteLabel" to { showNoteDialog() }
        )
        // A highlight can become a shareable quote card (parity with the iPad annotation composer).
        if (selection.isNotBlank()) options.add("🃏  Create gram" to { shareQuoteCard(selection, sourceTitle) })
        options.add(getString(R.string.reader_capture_photo) to { launchAnnCamera() })
        options.add(getString(R.string.reader_capture_upload) to { annGalleryLauncher.launch("image/*") })
        options.add(getString(R.string.reader_capture_voice) to { requestVoiceRecording() })
        showIconMenu(getString(R.string.reader_capture_title), options)
    }

    /** Render a highlighted passage into a quote card and share it (ACTION_SEND png). */
    private fun shareQuoteCard(quote: String, source: String?) {
        try {
            val card = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(quote, source)
            val dir = java.io.File(requireContext().cacheDir, "cards").apply { mkdirs() }
            val file = java.io.File(dir, "quote-${java.util.UUID.randomUUID()}.png")
            file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, getString(R.string.reader_capture_title)))
        } catch (e: Exception) {
            Timber.w(e, "quote card render/share failed")
            showMessage(R.string.card_render_failed)
        }
    }

    private fun showNoteDialog() {
        val selection = captureSelection
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.reader_capture_note_hint); setLines(3); gravity = Gravity.TOP
        }
        val b = AlertDialog.Builder(requireContext())
            .setTitle(if (selection.isNotBlank()) R.string.reader_capture_highlight_note else R.string.reader_capture_note)
            .setView(input)
            .setPositiveButton(R.string.reader_capture_save) { _, _ ->
                val text = input.text.toString().trim()
                if (selection.isBlank() && text.isEmpty()) { captureSink = null; return@setPositiveButton }
                captureSink?.invoke(selection.ifBlank { null }, text.ifBlank { null }, null)
                captureSink = null
                showMessage(R.string.reader_capture_saved)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> captureSink = null }
        if (selection.isNotBlank()) b.setMessage("“${selection.take(400)}”")
        b.show()
    }

    private fun launchAnnCamera() {
        try {
            val dir = File(requireContext().cacheDir, "camera").apply { mkdirs() }
            val photo = File(dir, "capture-${SystemClock.elapsedRealtimeNanos()}.jpg")
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", photo
            )
            pendingCameraFile = photo; pendingCameraUri = uri
            annCameraLauncher.launch(uri)
        } catch (e: Exception) {
            Timber.w(e, "camera capture unavailable")
            showMessage(R.string.reader_capture_no_camera); captureSink = null
        }
    }

    private fun importGalleryPhoto(uri: Uri) {
        val dest = File(attachmentsDir(), "photo-${UUID.randomUUID()}.jpg")
        val ok = runCatching {
            requireContext().contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        if (ok) emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.PHOTO, dest.name, null, Date()))
        else { showMessage(R.string.reader_capture_failed); captureSink = null }
    }

    private fun emitAttachment(att: Attachment) {
        // A standalone A/V gram takes precedence over the annotation sink when active.
        gramSink?.let { it(att); gramSink = null; showMessage(R.string.gram_capture_saved); return }
        captureSink?.invoke(captureSelection.ifBlank { null }, null, att)
        captureSink = null
        showMessage(R.string.reader_capture_saved)
    }

    // --- Voice memo ---

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartAt: Long = 0L
    private var recordDialog: AlertDialog? = null
    private val recordHandler = Handler(Looper.getMainLooper())

    private fun requestVoiceRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startVoiceRecording()
        else annMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceRecording() {
        val out = File(attachmentsDir(), "voice-${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(requireContext())
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(out.absolutePath)
            rec.prepare(); rec.start()
        } catch (e: Exception) {
            Timber.w(e, "voice recording failed to start")
            runCatching { rec.release() }
            showMessage(R.string.reader_capture_failed); captureSink = null; return
        }
        recorder = rec; recordFile = out; recordStartAt = SystemClock.elapsedRealtime()

        val label = TextView(requireContext()).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(40, 48, 40, 24)
            text = getString(R.string.reader_capture_recording, "0:00")
        }
        recordDialog = AlertDialog.Builder(requireContext())
            .setView(label)
            .setPositiveButton(R.string.reader_capture_stop) { _, _ -> stopVoiceRecording(save = true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> stopVoiceRecording(save = false) }
            .setCancelable(false)
            .show()

        val tick = object : Runnable {
            override fun run() {
                if (recorder == null) return
                val s = ((SystemClock.elapsedRealtime() - recordStartAt) / 1000).toInt()
                label.text = getString(R.string.reader_capture_recording, "%d:%02d".format(s / 60, s % 60))
                recordHandler.postDelayed(this, 500)
            }
        }
        recordHandler.postDelayed(tick, 500)
    }

    private fun stopVoiceRecording(save: Boolean) {
        recordHandler.removeCallbacksAndMessages(null)
        val secs = (SystemClock.elapsedRealtime() - recordStartAt) / 1000.0
        val file = recordFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null; recordFile = null
        recordDialog?.dismiss(); recordDialog = null
        if (save && file != null && file.exists() && secs >= 0.5) {
            emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.AUDIO, file.name, secs, Date()))
        } else {
            file?.delete(); captureSink = null
        }
    }

    /** Keep [pill] fully inside its parent — translation can never strand it off-screen. */
    private fun clampInParent(pill: View) {
        val parent = pill.parent as? View ?: return
        if (pill.width == 0 || parent.width == 0) return
        val minTx = -pill.left.toFloat()
        val maxTx = (parent.width - pill.right).toFloat()
        val minTy = -pill.top.toFloat()
        val maxTy = (parent.height - pill.bottom).toFloat()
        pill.translationX = pill.translationX.coerceIn(minTx, maxTx.coerceAtLeast(minTx))
        pill.translationY = pill.translationY.coerceIn(minTy, maxTy.coerceAtLeast(minTy))
    }

    /**
     * One entry in the [showAccordion] directory. With [action] set it renders as a standalone
     * tappable row (no caret) — for top-level items like All / Stars that aren't folders;
     * otherwise it's a collapsible folder over [items].
     */
    data class Folder(
        val emoji: String, val title: String,
        val items: List<Pair<String, () -> Unit>> = emptyList(),
        val expanded: Boolean = false,
        val action: (() -> Unit)? = null
    )

    /** One row in a [showGoModal] section/tools modal. */
    data class GoItem(val emoji: String, val label: String, val action: () -> Unit)

    /**
     * The compact "Go to…" modal (grouped rows), anchored top-left (directories) or up from the
     * bottom pill (sections). Lifted from the day page so the almanac pages use the SAME modal
     * instead of the old accordion drawer.
     */
    protected fun showGoModal(groups: List<Pair<String, List<GoItem>>>, anchorTop: Boolean) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        for ((header, items) in groups) {
            val tv = TextView(requireContext())
            tv.text = header.uppercase()
            tv.setTextColor(0xFF8A8A8A.toInt()); tv.textSize = 11f; tv.letterSpacing = 0.08f
            tv.setPadding(dp(14), dp(10), dp(14), dp(2))
            list.addView(tv)
            for (item in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<TextView>(R.id.go_label).text = applyRowIcon(r, "${item.emoji}  ${item.label}")
                r.setOnClickListener { dialog.dismiss(); item.action() }
                list.addView(r)
            }
        }

        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            // Narrower, and never wider than the screen minus a comfortable margin (fits the Palma).
            lp.width = minOf(dp(200), resources.displayMetrics.widthPixels - dp(40))
            if (anchorTop) {
                lp.gravity = Gravity.START or Gravity.TOP
                lp.x = dp(12); lp.y = dp(54)
            } else {
                lp.gravity = Gravity.END or Gravity.BOTTOM
                lp.x = dp(12); lp.y = dp(80)
            }
            w.attributes = lp
        }
    }

    /**
     * Leading-emoji → monochrome outline icon, so every directory/menu row renders a crisp
     * high-contrast glyph on e-ink instead of a colour emoji. Base code points (no variation
     * selector) so "⚙️"/"⚙" and "🗓️"/"🗓" both match.
     */
    private val emojiIcons: Map<String, Int> by lazy {
        mapOf(
            "📰" to R.drawable.ic_feed, "⭐" to R.drawable.ic_starred, "📖" to R.drawable.ic_book,
            "📺" to R.drawable.ic_tv, "🎧" to R.drawable.ic_headphones, "🔖" to R.drawable.ic_bookmark,
            "🗂" to R.drawable.ic_folder, "🕓" to R.drawable.ic_clock, "🕘" to R.drawable.ic_clock,
            "📆" to R.drawable.ic_calendar_today, "📅" to R.drawable.ic_calendar_today,
            "📊" to R.drawable.ic_calendar_today, "🗓" to R.drawable.ic_calendar_today,
            "👤" to R.drawable.ic_person, "🪞" to R.drawable.ic_person, "❝" to R.drawable.ic_quote,
            "🙏" to R.drawable.ic_heart, "🎬" to R.drawable.ic_film, "📚" to R.drawable.ic_book,
            "↪" to R.drawable.ic_nav_right, "⚙" to R.drawable.ic_settings, "☁" to R.drawable.ic_cloud,
            "▶" to R.drawable.ic_play, "⏸" to R.drawable.ic_pause, "⏹" to R.drawable.ic_stop,
            "🔊" to R.drawable.ic_speaker, "🌐" to R.drawable.ic_globe, "＋" to R.drawable.ic_add,
            "💬" to R.drawable.ic_chat, "☀" to R.drawable.ic_nav_today, "✒" to R.drawable.ic_pencil,
            "🖍" to R.drawable.ic_pencil, "📷" to R.drawable.ic_camera, "🖼" to R.drawable.ic_image,
            "🎤" to R.drawable.ic_mic, "🎥" to R.drawable.ic_video, "📤" to R.drawable.ic_share,
            "📌" to R.drawable.ic_pin, "🛰" to R.drawable.ic_send, "🖊" to R.drawable.ic_pencil,
            "🃏" to R.drawable.ic_card, "👆" to R.drawable.ic_toolbar_hand_touch,
            "🔄" to R.drawable.ic_toolbar_rotate, "🔀" to R.drawable.ic_swap, "🎯" to R.drawable.ic_refresh,
            "🎓" to R.drawable.ic_book, "🗎" to R.drawable.ic_reader_view, "🗒" to R.drawable.ic_reader_view,
            "📋" to R.drawable.ic_card,
            "❤" to R.drawable.ic_heart, "📝" to R.drawable.ic_go_notes, "✍" to R.drawable.ic_edit,
            "🔬" to R.drawable.ic_swap, "🧠" to R.drawable.ic_swap
        )
    }

    /** The drawable for a label's leading emoji (base code point), or null. */
    private fun emojiIconRes(text: String): Int? {
        val t = text.trimStart()
        for ((emoji, res) in emojiIcons) if (t.startsWith(emoji)) return res
        return null
    }

    /** Put the row's leading-emoji icon into its icon slot; return the label minus that emoji. */
    protected fun applyRowIcon(row: View, label: String): String {
        val icon = row.findViewById<ImageView>(R.id.go_icon)
        val res = emojiIconRes(label)
        if (res == null) { icon.visibility = View.GONE; return label }
        icon.setImageResource(res); icon.visibility = View.VISIBLE
        val t = label.trimStart()
        val emoji = emojiIcons.keys.first { t.startsWith(it) }
        return t.removePrefix(emoji).trim()
    }

    /**
     * A tappable menu with the same leading-emoji → outline-icon rows as the directories, so
     * pop-up menus read high-contrast on e-ink instead of colour emoji. Rows whose leading glyph
     * isn't mapped (e.g. ☑/☐ toggles) keep their text.
     */
    protected fun showIconMenu(title: CharSequence?, items: List<Pair<String, () -> Unit>>) {
        val ctx = requireContext()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        // Reuse the rounded directory card so contextual menus (lasso Create task/event/card,
        // Extract, etc.) read the same as the iPad's action menus instead of a stock AlertDialog.
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        val titleView = root.findViewById<TextView>(R.id.go_to_title)
        if (title.isNullOrEmpty()) titleView.visibility = View.GONE else titleView.text = title
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        for ((label, action) in items) {
            val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
            r.findViewById<TextView>(R.id.go_label).text = applyRowIcon(r, label)
            r.setOnClickListener { dialog.dismiss(); action() }
            list.addView(r)
        }
        showModal(dialog)
        dialog.window?.let { w -> w.attributes = w.attributes.apply { width = dp(300) } }
    }

    /**
     * A modal shown over a drawing surface must PAUSE the Onyx hardware pen, or the stylus taps
     * fall through to the ink layer and the popup "freezes". Drawing fragments override
     * [onModalShown]/[onModalDismissed]; elsewhere these are no-ops. Route every popover through
     * [showModal] so this happens uniformly.
     */
    protected open fun onModalShown() {}
    protected open fun onModalDismissed() {}

    protected fun showModal(dialog: AlertDialog) {
        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
    }

    /** Set a row's icon slot directly from an emoji (folder headers), else hide it. */
    private fun setRowEmojiIcon(row: View, emoji: String) {
        val icon = row.findViewById<ImageView>(R.id.go_icon)
        val res = emojiIconRes(emoji)
        if (res == null) icon.visibility = View.GONE
        else { icon.setImageResource(res); icon.visibility = View.VISIBLE }
    }

    /**
     * Collapsible-folder directory popover (top-left). Each folder header toggles its
     * children — Almanac, Feed, Bookshelf, Ask, Settings, etc.
     */
    protected fun showAccordion(folders: List<Folder>) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        for (folder in folders) {
            val header = layoutInflater.inflate(R.layout.item_go_to, list, false)
            setRowEmojiIcon(header, folder.emoji)
            val headerLabel = header.findViewById<TextView>(R.id.go_label)

            // A leaf entry (has [action]) is a plain tappable row — no caret, no children.
            if (folder.action != null) {
                headerLabel.text = folder.title
                header.setOnClickListener { dialog.dismiss(); folder.action.invoke() }
                list.addView(header)
                continue
            }

            // An outline frames the expanded dropdown for clarity.
            val outline = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0x66000000)
                cornerRadius = dp(8).toFloat()
            }
            val children = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (folder.expanded) View.VISIBLE else View.GONE
                background = if (folder.expanded) outline else null
                setPadding(dp(2), dp(2), dp(2), dp(4))
            }
            fun caret() = if (children.visibility == View.VISIBLE) "▾" else "▸"
            headerLabel.text = "${caret()}  ${folder.title}"
            header.setOnClickListener {
                val show = children.visibility != View.VISIBLE
                children.visibility = if (show) View.VISIBLE else View.GONE
                children.background = if (show) outline else null
                headerLabel.text = "${caret()}  ${folder.title}"
            }
            for ((label, action) in folder.items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, children, false)
                val text = applyRowIcon(r, label)
                r.findViewById<TextView>(R.id.go_label).apply { this.text = text; setPadding(dp(24), paddingTop, paddingRight, paddingBottom) }
                r.setOnClickListener { dialog.dismiss(); action() }
                children.addView(r)
            }
            val childLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(1), dp(6), dp(4)) }
            list.addView(header); list.addView(children, childLp)
        }

        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.gravity = Gravity.START or Gravity.TOP
            val metrics = resources.displayMetrics
            lp.x = dp(8); lp.y = dp(54)
            lp.width = minOf(dp(260), metrics.widthPixels - dp(16))
            // Size to content, but clamp to the visible area below y so a tall menu scrolls
            // within the screen instead of running off the bottom (small screens like the Palma).
            val avail = metrics.heightPixels - lp.y - dp(16)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            lp.height = if (root.measuredHeight > avail) avail
                        else android.view.WindowManager.LayoutParams.WRAP_CONTENT
            w.attributes = lp
        }
    }

    /**
     * The section switcher — the same "go to a surface" list the day page offers, so the almanac
     * pages open THIS instead of the old ledger-directory drawer. Uses CalendarNavigator (works
     * from any fragment) and the global feed/AV nav actions, so it's safe to call anywhere.
     */
    protected fun showSectionMenu() {
        val nav = com.toolsboox.plugin.calendar.CalendarNavigator
        val today = java.time.LocalDate.now()
        val locale = java.util.Locale.getDefault()
        fun go(action: Int) = androidx.navigation.Navigation.findNavController(requireView()).navigate(action)
        showGoModal(
            listOf(
                "" to listOf(
                    GoItem("☀︎", "Day") { nav.toDayPage(this, today, com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE) },
                    GoItem("🔖", "Intake") { nav.toDayNote(this, today, "intake") },
                    GoItem("🙏", "Gratitude") { nav.toDayNote(this, today, "gratitude") },
                    GoItem("❝", "Pickings") { nav.toDayNote(this, today, "pickings") },
                    GoItem("✒️", "Notes") { nav.toDayNote(this, today, "0") },
                    GoItem("📰", "Feed") { go(com.toolsboox.R.id.action_to_feeds) },
                    GoItem("🎬", "AV") { go(com.toolsboox.R.id.action_to_reading_log) },
                    GoItem("📆", "Almanac") { nav.toWeekPage(this, today, locale) }
                )
            ),
            anchorTop = false
        )
    }

    /**
     * A directory popover (top-left, iPad-style) grouping labelled rows under headers —
     * used for the hamburger menus on Bookshelf/Feed to list the actual books/feeds plus
     * the surfaces to jump to. Each row is (label, action).
     */
    protected fun showDirectory(groups: List<Pair<String, List<Pair<String, () -> Unit>>>>) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        for ((header, items) in groups) {
            if (header.isNotEmpty()) {
                val tv = TextView(requireContext())
                tv.text = header.uppercase()
                tv.setTextColor(0xFF8A8A8A.toInt()); tv.textSize = 11f; tv.letterSpacing = 0.08f
                tv.setPadding(dp(14), dp(12), dp(14), dp(2))
                list.addView(tv)
            }
            for ((label, action) in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<TextView>(R.id.go_label).text = applyRowIcon(r, label)
                r.setOnClickListener { dialog.dismiss(); action() }
                list.addView(r)
            }
        }
        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.gravity = Gravity.START or Gravity.TOP
            lp.x = dp(8); lp.y = dp(54); lp.width = dp(250)
            lp.height = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            w.attributes = lp
        }
    }

    /**
     * Persistent "Go to" surfaces menu, available on every screen (day, Bookshelf, Feed,
     * Ask). Lets you jump between the Ledger surfaces from anywhere.
     */
    protected fun showSurfacesMenu() {
        val nav = androidx.navigation.fragment.NavHostFragment.findNavController(this)
        showIconMenu(getString(R.string.go_to_title), listOf(
            "📅 Day" to { nav.navigate(R.id.action_to_calendar_day) },
            "📚 Bookshelf" to { nav.navigate(R.id.action_to_reader) },
            "📰 Feed Ledger" to { nav.navigate(R.id.action_to_feeds) },
            "💬 Ask my Ledger" to { nav.navigate(R.id.action_to_ledger_chat) },
        ))
    }

    /**
     * Result of request permission.
     */
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                var allGranted = true
                var someGranted = false

                grantResults.forEach {
                    allGranted = allGranted and (it == PackageManager.PERMISSION_GRANTED)
                    someGranted = someGranted or (it == PackageManager.PERMISSION_GRANTED)
                }

                val message = if (allGranted) {
                    R.string.main_all_granted_message
                } else if (someGranted) {
                    R.string.main_some_granted_message
                } else {
                    R.string.main_all_denied_message
                }

                Toast.makeText(this.context, message, Toast.LENGTH_LONG).show()
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * Check if permission granted or start the request process.
     */
    fun askAppPermissions() {
        if (askedForPermissions) return
        askedForPermissions = true

        val permissionsNeeded = mutableListOf<String>()
        val permissionsList = mutableListOf<String>()

        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.INTERNET)
        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.READ_CALENDAR)
        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.READ_EXTERNAL_STORAGE)
        checkAndAddPermission(permissionsList, permissionsNeeded, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permissionsList.isEmpty()) return

        if (permissionsNeeded.isEmpty()) {
            requestPermissions(permissionsList.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            val message = getString(R.string.main_ask_permissions_message, permissionsNeeded.joinToString { it })
            val builder: AlertDialog.Builder = AlertDialog.Builder(this.requireContext())
            builder.setTitle(R.string.main_ask_permissions_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(permissionsList.toTypedArray(), REQUEST_PERMISSIONS)
                }
            builder.create().show()
        }
    }

    /**
     * Check permission.
     *
     * @param permissionName the name of the permission
     * @return true, if granted
     */
    fun checkPermission(permissionName: String): Boolean {
        if (Manifest.permission.READ_EXTERNAL_STORAGE == permissionName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return true
            }
        }
        if (Manifest.permission.WRITE_EXTERNAL_STORAGE == permissionName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return true
            }
        }

        return ContextCompat.checkSelfPermission(requireContext(), permissionName) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check and add permission to the lists.
     *
     * @param permissionsList the list of permissions to acquire
     * @param permissionNeeded the list of permissions to ask about
     * @param permissionName the name of the permission
     */
    private fun checkAndAddPermission(
        permissionsList: MutableList<String>,
        permissionNeeded: MutableList<String>,
        permissionName: String
    ) {
        if (checkPermission(permissionName)) return

        permissionsList.add(permissionName)
        if (ActivityCompat.shouldShowRequestPermissionRationale(this.requireActivity(), permissionName)) {
            permissionNeeded.add(permissionName)
        }
    }
}
