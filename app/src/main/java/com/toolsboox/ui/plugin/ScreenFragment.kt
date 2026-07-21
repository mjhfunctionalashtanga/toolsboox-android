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
         * Accessibility: the modal text scale (Small / Medium / Large, set from the feed
         * wrench, applied to every Go modal / directory / accordion / context menu).
         */
        fun modalTextScale(context: android.content.Context): Float =
            when (context.getSharedPreferences("ledger_a11y", 0).getString("modal_text_size", "medium")) {
                "small" -> 0.85f
                "large" -> 1.25f
                else -> 1f
            }

        // Error-bar debounce (see showError): same message within 30s stays quiet.
        private var lastErrorResId = 0
        private var lastErrorShownAt = 0L

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

        // Auto-dismiss + debounce: the old LENGTH_INDEFINITE bar re-appeared on every retry of
        // a periodic load ("recurring network status menu") and had to be Okay'd each time.
        val now = System.currentTimeMillis()
        if (errorResId == lastErrorResId && now - lastErrorShownAt < 30_000L) return
        lastErrorResId = errorResId
        lastErrorShownAt = now

        val snackbar = Snackbar.make(
            parentView?.let { parentView } ?: toolbar.root, errorResId, Snackbar.LENGTH_LONG
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
     * Volume keys page whatever this screen is showing.
     *
     * Registered for EVERY screen rather than added one at a time. It had been wired on four —
     * the book reader, the article, the feed list and the day page — which meant the keys worked,
     * stopped, and worked again as you moved through the app. A hardware key that works on some
     * screens doesn't read as a feature with gaps; it reads as a key that is broken.
     *
     * A surface with its own idea of what a page turn means overrides [onVolumeKey]; one that
     * simply has a list or a web view needs to do nothing at all.
     */
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.volumeKeyHandler = handler@{ up -> onVolumeKey(up) }
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.volumeKeyHandler = null
    }

    /**
     * Page this screen. Return false to let the keys do their normal thing (change the volume).
     *
     * The default finds whatever on this screen scrolls and moves it by most of its own height —
     * most of, not all, so a line or two carries over and you can tell where you were.
     */
    protected open fun onVolumeKey(up: Boolean): Boolean {
        if (!volumeKeysPage()) return false
        // `view` on this class is the LAYOUT RESOURCE id, not the fragment's view — the name is
        // taken. getView() is the real one.
        val target = firstScrollable(getView() ?: return false) ?: return false
        val step = (target.height * 9 / 10).coerceAtLeast(1)
        when (target) {
            is androidx.recyclerview.widget.RecyclerView -> target.smoothScrollBy(0, if (up) -step else step)
            else -> target.scrollBy(0, if (up) -step else step)
        }
        return true
    }

    /** Shared with the readers, so one setting covers the whole app. */
    protected fun volumeKeysPage(): Boolean =
        requireContext().getSharedPreferences("ledger_reader_nav", 0).getBoolean("volume_turn", true)

    /**
     * The first thing under [root] that can actually scroll right now.
     *
     * "Can scroll" rather than "is a list": a RecyclerView with three rows in it has nothing to
     * page, and paging it while a WebView below could have moved would feel like the key had
     * failed. Asking each candidate whether it has anywhere to go picks the one the reader means.
     */
    private fun firstScrollable(root: View): View? {
        if (root !is ViewGroup) return null
        if (root.visibility != View.VISIBLE) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.canScrollVertically(1) || child.canScrollVertically(-1)) return child
            firstScrollable(child)?.let { return it }
        }
        return null
    }

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
        // Clamp once the pill actually HAS a size.
        //
        // This was a single `post {}`, which runs on the next frame whether or not layout has
        // happened — and `clampInParent` returns early on a zero-width view. So on the pass where
        // it mattered most, restoring a position saved by an earlier build, the clamp quietly did
        // nothing and the pill stayed wherever the prefs said, including off-screen. A layout
        // listener fires when there is something real to measure.
        pill.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int
            ) {
                if (v.width == 0 || (v.parent as? View)?.width ?: 0 == 0) return
                v.removeOnLayoutChangeListener(this)
                clampInParent(pill, handle)
            }
        })
        pill.post { clampInParent(pill, handle) }
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
                    clampInParent(pill, handle)
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
                        clampInParent(pill, handle)
                        prefs.edit().putFloat("${key}_px", pill.translationX)
                            .putFloat("${key}_py", pill.translationY).apply()
                    }
                    // The pill moved/collapsed → re-feed its bounds as a stylus exclude rect.
                    pill.post { (this@ScreenFragment as? SurfaceFragment)?.refreshRawExcludeRects() }
                    // A drag smears ghost trails across e-ink — clean the panel once it lands.
                    if (moved) pill.post { (this@ScreenFragment as? SurfaceFragment)?.forceFullEpdRefresh() }
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
        // period) brings down the Ledger directory. The accordion, not the old nine-row
        // `showSectionMenu()` list — this pill is on every almanac page, so that one call was most
        // of why last generation's drawer still appeared to be alive.
        navGoto.setOnClickListener {
            if (isAtPresent()) showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this))
            else onHome()
        }
        navWidget.bringToFront()

        // Vertical on narrow (phone) screens so it can't collide with the tool pill.
        val narrow = resources.configuration.screenWidthDp < 520
        val vertical = requireContext().getSharedPreferences("ledger_widgets", 0)
            .getBoolean("vertical", narrow)
        (navWidget as? LinearLayout)?.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        applyGripOrientation(navGrip, vertical)

        makeDraggable(navGrip, navWidget, "nav") { toggleNavPill(navUp, navDown) }
        applyNavPillCollapse(navUp, navDown)
    }

    /**
     * Cycle the screen orientation through the user's allowed set (rotationOrientationMask) — the
     * same rotate the day page's wrench does, shared so the feed/reader "quick controls" can rotate too.
     */
    protected fun cycleScreenOrientation() = stepScreenOrientation()

    /**
     * Step to the next allowed orientation.
     *
     * Advances from what is on SCREEN when the activity hasn't requested anything yet — see
     * [com.toolsboox.ot.ScreenRotation.next]. Without that, the first press asked for portrait
     * while portrait was already showing, and the button appeared dead until its second tap.
     */
    protected fun stepScreenOrientation() {
        val activity = requireActivity()
        val mask = requireContext().getSharedPreferences("MAIN", 0)
            .getInt("rotationOrientationMask", 0b1111)
        activity.requestedOrientation = com.toolsboox.ot.ScreenRotation.next(
            com.toolsboox.ot.ScreenRotation.cycleFor(mask),
            activity.requestedOrientation,
            com.toolsboox.ot.ScreenRotation.displayedBy(currentSurfaceRotation())
        )
    }

    /**
     * Hand the screen to the gyro, or take it back.
     *
     * The stepper and the sensor are the same control because they answer the same question —
     * "which way up is this" — and having them in two places meant setting one while the other
     * quietly overrode it.
     */
    protected fun toggleAutoRotate() {
        val activity = requireActivity()
        val auto = activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        if (auto) {
            // Leaving the sensor: hold whatever it had landed on, so the screen doesn't jump.
            activity.requestedOrientation =
                com.toolsboox.ot.ScreenRotation.displayedBy(currentSurfaceRotation())
            showMessage(getString(R.string.rotate_locked), requireView())
        } else {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            showMessage(getString(R.string.rotate_auto), requireView())
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSurfaceRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            requireContext().display?.rotation ?: 0
        else requireActivity().windowManager.defaultDisplay.rotation

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
    protected fun attachmentsDir(): File = com.toolsboox.ot.LedgerPaths.attachmentsDir(requireContext())

    /** Root of the day JSONs. Here so every screen shares one answer. */
    protected fun documentsRoot(): File = com.toolsboox.ot.LedgerPaths.documentsRoot(requireContext())

    private val annGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importGalleryPhoto(uri) else captureSink = null
        }

    private val annCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val src = pendingCameraFile
            if (ok && src != null && src.exists()) {
                val dest = File(attachmentsDir(), "photo-${UUID.randomUUID()}.jpg")
                // Only destroy the source and record the attachment when the copy actually
                // landed — a disk-full copy failure used to delete the ONLY copy and persist
                // an attachment pointing at a missing file (gallery import got this right).
                if (runCatching { src.copyTo(dest, overwrite = true) }.isSuccess) {
                    src.delete()
                    emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.PHOTO, dest.name, null, Date()))
                } else {
                    showMessage(R.string.reader_capture_failed)
                    captureSink = null; gramSink = null
                }
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
                if (runCatching { src.copyTo(dest, overwrite = true) }.isSuccess) {
                    src.delete()
                    emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.VIDEO, dest.name, null, Date()))
                } else {
                    showMessage(R.string.reader_capture_failed)
                    gramSink = null; captureSink = null
                }
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

    /**
     * Straight to one kind of capture, with no chooser in front of it — for callers that already
     * asked which kind (the page's "Add media…" menu), so you don't pick "voice" and then get
     * asked again.
     */
    protected fun captureAvGramDirect(kind: Attachment.Kind, onGram: (Attachment) -> Unit) {
        gramSink = onGram
        captureSink = null
        captureSelection = ""
        when (kind) {
            Attachment.Kind.AUDIO -> requestVoiceRecording()
            Attachment.Kind.VIDEO -> launchAnnVideo()
            Attachment.Kind.PHOTO -> launchAnnCamera()
        }
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

    /** Open the shared gram studio for a highlighted passage (formats + fit), then share. */
    private fun shareQuoteCard(quote: String, source: String?) {
        com.toolsboox.plugin.calendar.ot.GramStudio.show(
            this, quote, source = source,
            onShare = { cards -> shareCardBitmaps(cards) }
        )
    }

    /** Share rendered card bitmap(s) via ACTION_SEND / ACTION_SEND_MULTIPLE. */
    fun shareCardBitmaps(cards: List<Bitmap>) {
        try {
            val dir = java.io.File(requireContext().cacheDir, "cards").apply { mkdirs() }
            val uris = ArrayList<Uri>()
            for (card in cards) {
                val file = java.io.File(dir, "quote-${java.util.UUID.randomUUID()}.png")
                file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                uris.add(FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file))
            }
            val share = if (uris.size == 1)
                Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uris[0])
            else
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    //
    // The microphone, dialog and clock live in com.toolsboox.ot.VoiceRecorder, so the floating
    // pen button can record without a second copy of it. What stays here is only the part that
    // is this fragment's business: asking for the permission, and where the clip ends up.

    private fun requestVoiceRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startVoiceRecording()
        else annMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceRecording() {
        com.toolsboox.ot.VoiceRecorder.record(
            context = requireContext(),
            out = File(attachmentsDir(), "voice-${UUID.randomUUID()}.m4a"),
            recordingLabel = { clock -> getString(R.string.reader_capture_recording, clock) },
            stopLabel = getString(R.string.reader_capture_stop),
            onSaved = { file, seconds ->
                emitAttachment(Attachment(UUID.randomUUID().toString(), Attachment.Kind.AUDIO, file.name, seconds, Date()))
            },
            onDiscarded = { captureSink = null; gramSink = null }
        )
    }

    override fun onStop() {
        super.onStop()
        // Recording only ends via the dialog's buttons — navigating away or backgrounding while
        // recording held the mic, kept the file growing, and leaked the dialog's window. Saving
        // on the way out keeps the memo instead of discarding it.
        com.toolsboox.ot.VoiceRecorder.stop(save = true)
    }

    /**
     * Turn the grip with the pill.
     *
     * The grip is drawn as a bar across the pill's short side — 22×44 on a horizontal pill, where
     * it reads as "take hold here and slide". Flipping the pill to vertical left it at 22×44: a
     * tall thin sliver at the top of a tall thin pill, no longer distinguishable from the buttons
     * under it. Hence "missing handle" on a pill whose handle was there the whole time.
     *
     * Swapping the two dimensions is the whole fix; the drawable is symmetrical enough that it
     * reads correctly either way once it is the right shape.
     */
    protected fun applyGripOrientation(grip: View, vertical: Boolean) {
        val d = resources.displayMetrics.density
        val short = (22 * d).toInt()
        val long = (44 * d).toInt()
        grip.layoutParams = grip.layoutParams.apply {
            width = if (vertical) long else short
            height = if (vertical) short else long
        }
        grip.requestLayout()
    }

    /**
     * Keep [pill] within reach — translation can never strand it somewhere you can't get it back
     * from, and a pill longer than the screen can still be slid to either of its ends.
     * See [com.toolsboox.ot.PillBounds.range] for why that second half matters on a Palma.
     */
    private fun clampInParent(pill: View, handle: View? = null) {
        val parent = pill.parent as? View ?: return
        if (pill.width == 0 || parent.width == 0) return
        // Handle edges in the PARENT's space: the handle is laid out inside the pill, so its own
        // left/top are relative to the pill and have to be shifted by the pill's position.
        val hL = pill.left + (handle?.left ?: 0)
        val hR = pill.left + (handle?.right ?: pill.width)
        val hT = pill.top + (handle?.top ?: 0)
        val hB = pill.top + (handle?.bottom ?: pill.height)
        pill.translationX = pill.translationX.coerceIn(
            com.toolsboox.ot.PillBounds.rangeKeepingHandle(pill.left, pill.right, parent.width, hL, hR))
        pill.translationY = pill.translationY.coerceIn(
            com.toolsboox.ot.PillBounds.rangeKeepingHandle(pill.top, pill.bottom, parent.height, hT, hB))
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
     * Accessibility: the modal text scale (Small / Medium / Large, set from the feed wrench,
     * applied to every Go modal / directory / accordion / context menu app-wide).
     */
    protected fun modalTextScale(): Float =
        modalTextScale(requireContext())

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
        val textScale = modalTextScale()
        for ((header, items) in groups) {
            val tv = TextView(requireContext())
            tv.text = header.uppercase()
            tv.setTextColor(0xFF8A8A8A.toInt()); tv.textSize = 11f * textScale; tv.letterSpacing = 0.08f
            tv.setPadding(dp(14), dp(10), dp(14), dp(2))
            list.addView(tv)
            for (item in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<TextView>(R.id.go_label).apply {
                    text = applyRowIcon(r, "${item.emoji}  ${item.label}")
                    textSize = 18f * textScale
                }
                r.setOnClickListener { dialog.dismiss(); item.action() }
                list.addView(r)
            }
        }

        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            // Narrow, with a clear edge margin — never more than ~46% of the screen width.
            lp.width = minOf(dp(200), (resources.displayMetrics.widthPixels * 0.44f).toInt())
            // Always top-left, matching showAccordion/showDirectory — so the menu appears in the
            // SAME position on every screen (day, feeds, reader) instead of jumping to the pill.
            lp.gravity = Gravity.START or Gravity.TOP
            lp.x = dp(22); lp.y = dp(54)
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
        if (res != null) {
            icon.setImageResource(res); icon.visibility = View.VISIBLE
            val t = label.trimStart()
            val emoji = emojiIcons.keys.first { t.startsWith(it) }
            return t.removePrefix(emoji).trim()
        }
        // Unmapped leading glyph ("@  Correspondence") → same bitmap fallback as folder headers,
        // so child rows align identically. Convention: glyph + two spaces + label; indented rows
        // (leading whitespace) and plain sentences pass through untouched.
        if (!label.startsWith(" ")) {
            val idx = label.indexOf("  ")
            if (idx in 1..3) {
                drawGlyphIcon(icon, label.substring(0, idx))
                return label.substring(idx).trim()
            }
        }
        icon.visibility = View.GONE
        return label
    }

    /** Draw [glyph] into a bitmap the size of the icon slot — any character, pixel-aligned. */
    private fun drawGlyphIcon(icon: ImageView, glyph: String) {
        val size = (26 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size * 0.8f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(glyph, size / 2f, y, paint)
        icon.setImageBitmap(bmp)
        icon.visibility = View.VISIBLE
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

    fun showModal(dialog: AlertDialog) {
        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
    }

    /** Set a row's icon slot directly from an emoji (folder headers), else hide it. */
    /** Puts the row's glyph in the ICON SLOT: a mapped drawable when one exists, otherwise the
     *  character DRAWN into a bitmap of the same size — so @ / # / ‹ sit pixel-aligned with the
     *  drawable icons for any character. Returns false only for a blank glyph. */
    private fun setRowEmojiIcon(row: View, emoji: String): Boolean {
        val icon = row.findViewById<ImageView>(R.id.go_icon)
        val res = emojiIconRes(emoji)
        if (res != null) { icon.setImageResource(res); icon.visibility = View.VISIBLE; return true }
        if (emoji.isBlank()) { icon.visibility = View.GONE; return false }
        drawGlyphIcon(icon, emoji)
        return true
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

        // Unmapped glyph → carry it in the label as ENLARGED text (renders for any character,
        // sized to sit alongside the mapped drawable icons instead of shrinking into the label).
        fun glyphLabel(glyph: String, text: String): CharSequence {
            if (glyph.isBlank()) return text
            val s = android.text.SpannableString("$glyph  $text")
            s.setSpan(android.text.style.RelativeSizeSpan(1.35f), 0, glyph.length, 0)
            s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, glyph.length, 0)
            return s
        }

        val textScale = modalTextScale()
        for (folder in folders) {
            val header = layoutInflater.inflate(R.layout.item_go_to, list, false)
            val hasIcon = setRowEmojiIcon(header, folder.emoji)
            val glyph = if (!hasIcon && folder.emoji.isNotBlank()) folder.emoji else ""
            val headerLabel = header.findViewById<TextView>(R.id.go_label)
            headerLabel.textSize = 18f * textScale

            // A leaf entry (has [action]) is a plain tappable row — no caret, no children.
            if (folder.action != null) {
                headerLabel.text = glyphLabel(glyph, folder.title)
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
            // Glyph LEFT of the caret, matching where the mapped drawable icons sit.
            fun headerText(): CharSequence =
                if (glyph.isEmpty()) "${caret()}  ${folder.title}"
                else glyphLabel(glyph, "${caret()}  ${folder.title}")
            headerLabel.text = headerText()
            header.setOnClickListener {
                val show = children.visibility != View.VISIBLE
                children.visibility = if (show) View.VISIBLE else View.GONE
                children.background = if (show) outline else null
                headerLabel.text = headerText()
            }
            for ((label, action) in folder.items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, children, false)
                val text = applyRowIcon(r, label)
                r.findViewById<TextView>(R.id.go_label).apply {
                    this.text = text; textSize = 18f * textScale
                    setPadding(dp(24), paddingTop, paddingRight, paddingBottom)
                }
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
            // Left drawer: flush-left, full-height, scrolls internally. No animation (e-ink).
            val lp = w.attributes
            lp.gravity = Gravity.START or Gravity.TOP
            val metrics = resources.displayMetrics
            // Flush left, but BELOW the date-nav strip across the top (it stays usable).
            lp.x = 0; lp.y = dp(64)
            lp.width = minOf(dp(300), (metrics.widthPixels * 0.66f).toInt())
            lp.height = metrics.heightPixels - dp(64)
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
                // Same set, same ritual order as the day page's switcher (Intake → Pickings →
                // Gratitude → Synthesize → Write); Notes lives on the floating pen button.
                "" to listOf(
                    GoItem("☀︎", "Day") { nav.toDayPage(this, today, com.toolsboox.plugin.calendar.da.v2.CalendarDay.DEFAULT_STYLE) },
                    GoItem("🔖", "Intake") { nav.toDayNote(this, today, "intake") },
                    GoItem("❝", "Pickings") { nav.toDayNote(this, today, "pickings") },
                    GoItem("🙏", "Gratitude") { nav.toDayNote(this, today, "gratitude") },
                    GoItem("🔬", "Synthesize") { nav.toDayNote(this, today, "synthesize") },
                    GoItem("✍", "Write") { nav.toDayNote(this, today, "write") },
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
    /**
     * Grouped directory — now ONE visual language with the accordion: each named group becomes
     * a collapsible Folder (▸/▾), headerless groups flatten to plain rows. Keeping a single
     * renderer is what stops the app from growing two directory styles again.
     */
    protected fun showDirectory(groups: List<Pair<String, List<Pair<String, () -> Unit>>>>) {
        val folders = mutableListOf<Folder>()
        for ((header, items) in groups) {
            if (header.isEmpty()) {
                items.forEach { (label, action) -> folders.add(Folder("", label, action = action)) }
            } else {
                folders.add(Folder("", header, items, expanded = true))
            }
        }
        showAccordion(folders)
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
