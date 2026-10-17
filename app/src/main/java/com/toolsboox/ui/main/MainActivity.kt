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
class MainActivity : BaseActivity<MainPresenter>(), MainView, com.toolsboox.ot.BottomPill.Host {

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

    /**
     * THE LEFT EDGE'S FLICK, WHERE THE SYSTEM NEVER SEES IT (Michael 2026-10-12, on the B6 on -322:
     * "The b6 doesn't have the left right working for bring up the hub it seems rn"). Every ink
     * page claims its surface from the back gesture so the pen can write in the margins, and with
     * the bars hidden the B6 honours the whole claim: a finger flick in from the left edge reached
     * the page as a plain drag and the ladder never ran. This catcher sees the window's touches
     * first ([dispatchTouchEvent]), passes every one on as it comes, and when a finger makes the
     * flick hands the SAME Back-from-the-left a system swipe would to this activity's dispatcher —
     * the screen's own Back first (an open article), then its ladder ([com.toolsboox.ot.EdgeLadder]:
     * room pane → Hub → back). Where the system does take the edge (the Feeds list), it cancels the
     * touch before the catcher's travel, so one swipe is never two rungs.
     * [com.toolsboox.ot.LeftEdgeCatcher] has the rules; the Hub's dialog carries its own
     * (ScreenFragment.closeOnDrawerSwipe).
     */
    private val edgeCatcher by lazy {
        com.toolsboox.ot.LeftEdgeCatcher(
            where = "the page",
            density = resources.displayMetrics.density,
            dispatcher = { onBackPressedDispatcher },
            deliver = { e -> deliverTouch(e) },
            landing = { x, y -> edgeLanding(x, y) },
            beforeFire = { currentScreen()?.edgeDropFingerInk() },
            afterFire = { guardEdgeEcho() }
        )
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val took = edgeCatcher.onTouch(ev)
        // A touch-down shuts the echo window, a lift or a cancel re-times it: the guard follows.
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> refreshEchoGuard()
        }
        if (took) return true
        return super.dispatchTouchEvent(ev)
    }

    /** The views' own dispatch — the catcher's CANCEL goes this way. */
    private fun deliverTouch(e: android.view.MotionEvent) {
        super.dispatchTouchEvent(e)
    }

    /** The screen on top (as [handleVolumeKey] resolves it). */
    private fun currentScreen(): com.toolsboox.ui.plugin.ScreenFragment? {
        val navHost = supportFragmentManager.primaryNavigationFragment ?: return null
        return navHost.childFragmentManager.fragments.lastOrNull { it.isResumed } as? com.toolsboox.ui.plugin.ScreenFragment
    }

    /** What a touch-down at the left edge landed on: the page the finger inks (the bezel bar), or
     *  a floating pill / a sideways scroller (not the flick's). The ink is asked by its own screen
     *  rectangle, not by the hit chain, so a transparent view laid over the page can't drop the
     *  finger to the easier off-ink bar (review of f0035e3b, 2026-10-12). */
    private fun edgeLanding(x: Float, y: Float): com.toolsboox.ot.LeftEdgeCatcher.Landing {
        val chain = com.toolsboox.ot.LeftEdgeCatcher.hitChain(window.decorView, x, y)
        val ink = currentScreen()?.edgeInkView()
        return com.toolsboox.ot.LeftEdgeCatcher.Landing(
            inks = ink != null && com.toolsboox.ot.LeftEdgeCatcher.isOn(ink, x, y),
            standAside = com.toolsboox.ot.LeftEdgeCatcher.standsAside(chain)
        )
    }

    /**
     * One swipe, one rung: while [com.toolsboox.ot.EdgeLadder]'s echo window is open — from the
     * flick through its lift and ECHO_MS past it, or held while the lift is unseen — a Back the
     * system (or Bigme's xSettings) makes of the same swipe is swallowed on this window, at the top
     * of its back callbacks (Android 13+; the key path is [dispatchKeyEvent]'s). Both would send
     * it at the lift, so the guard stays up until the window shuts, not 300 ms from the fire
     * (review of f0035e3b, 2026-10-12). Called by the page's catcher and by the Hub's, whose
     * close-and-back leaves this window the one an echo would reach.
     */
    fun guardEdgeEcho() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (echoGuard == null) {
            val guard = android.window.OnBackInvokedCallback {
                val now = android.os.SystemClock.uptimeMillis()
                if (com.toolsboox.ot.EdgeLadder.isEcho(now)) {
                    Timber.i("EdgeLadder: a Back %d ms after the left-edge flick — the same swipe, swallowed",
                        now - com.toolsboox.ot.EdgeLadder.catcherFiredAt)
                } else {
                    // The window shut a moment before the guard came down: not an echo — hand it on
                    // as the plain Back it would have been.
                    dropEchoGuard()
                    onBackPressedDispatcher.onBackPressed()
                }
            }
            runCatching {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, guard)
            }.onSuccess { echoGuard = guard }
        }
        refreshEchoGuard()
    }

    /** The guard on this window's back callbacks while an echo may come (an OnBackInvokedCallback,
     *  held as Any so older Androids never load the class). */
    private var echoGuard: Any? = null
    private val echoGuardCheck = Runnable { refreshEchoGuard() }

    /** Re-time the guard to the echo window: down when it has shut, else checked again as it ends. */
    private fun refreshEchoGuard() {
        if (echoGuard == null) return
        val decor = window.decorView
        decor.removeCallbacks(echoGuardCheck)
        val left = com.toolsboox.ot.EdgeLadder.echoRemainingMs(android.os.SystemClock.uptimeMillis())
        if (left <= 0L) dropEchoGuard() else decor.postDelayed(echoGuardCheck, left)
    }

    private fun dropEchoGuard() {
        val g = echoGuard ?: return
        echoGuard = null
        window.decorView.removeCallbacks(echoGuardCheck)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(g as android.window.OnBackInvokedCallback) }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // The same swipe heard twice (Michael 2026-10-12): a Back key within ECHO_MS of the left
        // edge's in-app flick is that flick's echo — swallowed, so one swipe climbs one rung.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            com.toolsboox.ot.EdgeLadder.isEcho(android.os.SystemClock.uptimeMillis())) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN)
                Timber.i("EdgeLadder: a Back key right after the left-edge flick — the same swipe, swallowed")
            return true
        }
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
    /** A one-shot taker for the NEXT camera result. The transport's 📷 (LedgerPlayerCapture) has
     *  no fragment and so no launcher of its own; it borrows the activity's through
     *  [capturePhotoThen]. Null = the pen button's ordinary ingest. */
    private var cameraSink: ((java.io.File) -> Unit)? = null
    private val captureCameraLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val sink = cameraSink
        cameraSink = null
        if (ok) pendingCameraFile?.let { f -> if (sink != null) sink(f) else ingestPhotoFile(f) }
    }

    /** Take ONE photo for a fragmentless caller — the file is handed to [onFile] instead of the
     *  pen button's ingest. */
    fun capturePhotoThen(onFile: (java.io.File) -> Unit) {
        try {
            val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
            val photo = java.io.File(dir, "capture-${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            cameraSink = onFile
            pendingCameraFile = photo
            captureCameraLauncher.launch(uri)
        } catch (e: Exception) {
            cameraSink = null
            toast("No camera available")
        }
    }

    /** A one-shot continuation for the NEXT microphone grant — [withMicrophone]'s. Null = the pen
     *  button's own voice gram. */
    private var micSink: (() -> Unit)? = null

    /** Run [onGranted] with RECORD_AUDIO in hand, asking first when needed — for a fragmentless
     *  caller (the transport's 🎤) that cannot register a permission launcher of its own. */
    fun withMicrophone(onGranted: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) onGranted()
        else { micSink = onGranted; micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
    }
    private val capturePickLauncher = registerForActivityResult(
        // More than one picture at a time (Michael, field, 08-28) — each selected image rides
        // the single-photo ingest in order, minting its own pick; the -44 ruling routes every
        // one to All Picks, where the inbox slotting lays them out side by side.
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> uris.forEach { ingestPhotoUri(it) } }

    /**
     * The view binding.
     */
    private lateinit var binding: ActivityMainBinding

    /** The pen button's unscaled square and padding (its XML values), captured before any re-size. */
    private var penButtonBase: IntArray? = null

    /** THE BOTTOM PILL (Michael 2026-10-11): the notes button grown into `[corner | notes]` — the
     *  corner half lent by whichever ink page is up ([com.toolsboox.ot.BottomPill.Corner]). */
    private var pill: com.toolsboox.ot.BottomPill? = null
    override val bottomPill: com.toolsboox.ot.BottomPill? get() = pill

    /**
     * True when this copy was RESTORED (process death, then back to Ledgable) until a new intent
     * arrives: the system hands a restore the ORIGINAL launch intent, not the one we consumed, so a
     * card handoff in it was already followed and must not re-navigate (the 09-07 rule, 2026-10-10).
     */
    private var staleLaunchIntent = false

    /** True from onNewIntent to the next onResume: the intent reached this LIVE copy, so a
     *  foreign SEND in it sits in Ledgable's own task (forwardForeignShare's warm case). */
    private var intentArrivedWarm = false

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
     * Both halves of the bottom pill take the same size (Michael 2026-10-11: one oblong).
     */
    private fun applyPenButtonScale() {
        val v = binding.floatNoteButton
        val base = penButtonBase ?: intArrayOf(v.layoutParams.width, v.layoutParams.height, v.paddingLeft)
            .also { penButtonBase = it }
        val mul = com.toolsboox.ot.ModalScale.pillScale(this)
        for (half in listOf<android.view.View>(binding.floatNoteButton, binding.floatCornerButton)) {
            half.layoutParams = half.layoutParams.apply {
                width = Math.round(base[0] * mul)
                height = Math.round(base[1] * mul)
            }
            val pad = Math.round(base[2] * mul)
            half.setPadding(pad, pad, pad, pad)
        }
        refreshPenTouchDelegate()
    }

    /**
     * Keep the pen button's TOUCH at its unscaled square even when drawn smaller. The delegate
     * rect lives in the parent's coordinates and does NOT follow view translation, so this must
     * be re-run after every drag and restore — a stale rect would tap where the button used to be.
     * It grows the whole pill and hands each touch to the half nearest it ([PillTouchDelegate]) —
     * the stock TouchDelegate re-aims every touch at its view's centre, the seam between halves.
     */
    private fun refreshPenTouchDelegate() {
        val v = binding.floatNotePill
        v.post {
            val parent = v.parent as? android.view.View ?: return@post
            val base = penButtonBase ?: return@post
            val half = binding.floatNoteButton.takeIf { it.visibility == android.view.View.VISIBLE }
                ?: binding.floatCornerButton
            val growW = (base[0] - half.width).coerceAtLeast(0)
            val growH = (base[1] - half.height).coerceAtLeast(0)
            if ((growW == 0 && growH == 0) || v.visibility != android.view.View.VISIBLE) {
                parent.touchDelegate = null; return@post
            }
            val r = android.graphics.Rect(
                v.left + v.translationX.toInt() - growW / 2,
                v.top + v.translationY.toInt() - growH / 2,
                v.right + v.translationX.toInt() + growW / 2,
                v.bottom + v.translationY.toInt() + growH / 2
            )
            parent.touchDelegate = PillTouchDelegate(r, v)
        }
    }

    /** A TouchDelegate that keeps the touch's place: it lands in [target] clamped to its edge
     *  nearest the finger, so the pill's frame hands it to the half under (or beside) it. */
    private class PillTouchDelegate(private val bounds: android.graphics.Rect, private val target: android.view.View) :
        android.view.TouchDelegate(bounds, target) {
        private var routing = false
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> routing = bounds.contains(event.x.toInt(), event.y.toInt())
            }
            if (!routing) return false
            val e = android.view.MotionEvent.obtain(event)
            val left = target.left + target.translationX
            val top = target.top + target.translationY
            e.setLocation(
                (event.x - left).coerceIn(0f, (target.width - 1).coerceAtLeast(0).toFloat()),
                (event.y - top).coerceIn(0f, (target.height - 1).coerceAtLeast(0).toFloat()))
            val handled = target.dispatchTouchEvent(e)
            e.recycle()
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) routing = false
            return handled
        }
    }

    /**
     * Where the pill may stand across: on screen, and on an ink page clear of the tuck panel's
     * column (Michael 2026-10-11: the pill keeps clear of the rail) — the column's Normal width in
     * both states, so a ⛶ never moves the pill and costs the page no second cut.
     */
    private fun pillXRange(v: android.view.View, parentWidth: Int): ClosedFloatingPointRange<Float> {
        val (aL, aR) = bottomPill?.corner?.avoidPx() ?: (0 to 0)
        val gap = (6 * resources.displayMetrics.density).toInt()
        return com.toolsboox.ot.PillBounds.rangeAvoiding(v.left, v.right, parentWidth,
            if (aL > 0) aL + gap else 0, if (aR > 0) aR + gap else 0)
    }

    /**
     * Seat the pill again, always from the spot you last dragged it to (the stored one): an ink
     * page's column came or went (the pill steps clear of it, and back once it has gone), the
     * pill changed width (a half came or went), the screen resized. Never saved: your spot stays
     * yours, so no detour can drift it (review 2026-10-11). At once when the pill is laid out — an
     * ink page registers before its first frame, so the pill lands clear of the column in that
     * frame and the page cuts round it once, not twice.
     */
    private fun clampPill() {
        val v = binding.floatNotePill
        val p = v.parent as? android.view.View
        if (p == null || p.width == 0 || p.height == 0 || v.width == 0) { v.post { clampPillNow() }; return }
        clampPillNow()
    }

    private fun clampPillNow() {
        val v = binding.floatNotePill
        val p = v.parent as? android.view.View ?: return
        if (p.width == 0 || p.height == 0 || v.width == 0) return
        // The touch target follows whatever the pill just did (a half came or went) either way.
        refreshPenTouchDelegate()
        val prefs = getSharedPreferences("MAIN", MODE_PRIVATE)
        val nx = prefs.getFloat("floatNoteTx", 0f).coerceIn(pillXRange(v, p.width))
        val ny = prefs.getFloat("floatNoteTy", 0f).coerceIn(com.toolsboox.ot.PillBounds.range(v.top, v.bottom, p.height))
        if (nx == v.translationX && ny == v.translationY) return
        v.translationX = nx
        v.translationY = ny
        refreshPenTouchDelegate()
        // A translation lays nothing out: the page under it hears the move here.
        bottomPill?.notifyMoved()
    }

    /**
     * Make the floating pen-note button draggable: a tap still opens Notes, but a drag repositions
     * it and persists where you put it — so it can move off whatever it's covering (e.g. the feed
     * drawer's lower-left). Clamped on-screen; restored on next launch. The whole bottom pill
     * moves as one ([pill]); [handle] is the half under the finger, whose tap and hold fire.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeFloatButtonDraggable(handle: android.view.View, pill: android.view.View, restore: Boolean) {
        val prefs = getSharedPreferences("MAIN", MODE_PRIVATE)
        val view = pill
        // A drag of the pill parked at the left edge moves it — never the left edge's swipe back
        // (LeftEdgeCatcher stands aside for it; Michael 2026-10-12).
        pill.setTag(R.id.tag_edge_stand_aside, true)
        // Restore through the same clamp the live drag uses: the persisted translation was saved
        // against SOME parent size, not necessarily this one, and applied raw it can park the
        // button off-screen with no handle left to drag it back. Needs the laid-out parent —
        // re-post until it has dimensions.
        if (restore) view.post(object : Runnable {
            override fun run() {
                val parent = view.parent as? android.view.View ?: return
                if (parent.width == 0 || parent.height == 0) { view.post(this); return }
                view.translationX = prefs.getFloat("floatNoteTx", 0f)
                    .coerceIn(pillXRange(view, parent.width))
                view.translationY = prefs.getFloat("floatNoteTy", 0f)
                    .coerceIn(com.toolsboox.ot.PillBounds.range(view.top, view.bottom, parent.height))
                refreshPenTouchDelegate()
                bottomPill?.notifyMoved()
            }
        })
        // Keep the button through a screen flip, the way every pill is kept — the same rule as
        // ScreenFragment.makeDraggable, which this button cannot ride only because it lives on
        // the activity. It used to keep its raw translation across a rotation, so a pen parked
        // down a portrait edge landed past the shorter landscape edge, unreachable — the pills
        // all rescaled to the new screen and the pen button alone "lost its position". A genuine
        // flip scales the translation (halfway up stays halfway up) and persists; any other
        // parent resize (system bars coming and going) just re-clamps, unsaved, so a near-1.0
        // ratio can't compound drift into the stored spot.
        if (restore) view.post {
            val parent = view.parent as? android.view.View ?: return@post
            var lastW = parent.width
            var lastH = parent.height
            parent.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val w = r - l
                val h = b - t
                if (w <= 0 || h <= 0 || lastW <= 0 || lastH <= 0) {
                    if (w > 0 && h > 0) { lastW = w; lastH = h }
                    return@addOnLayoutChangeListener
                }
                val flipped = (w < h) != (lastW < lastH)
                if (flipped) {
                    // YOUR spot is what scales and is saved — on screen only, never the spot an ink
                    // page's column pushed it to (review 2026-10-11: saving the pushed spot moved
                    // your pill each rotation); the column's clearance is laid on after.
                    val sx = prefs.getFloat("floatNoteTx", 0f) * w / lastW
                    val sy = prefs.getFloat("floatNoteTy", 0f) * h / lastH
                    view.post {
                        val p = view.parent as? android.view.View ?: return@post
                        if (p.width > 0 && p.height > 0) prefs.edit()
                            .putFloat("floatNoteTx", sx.coerceIn(com.toolsboox.ot.PillBounds.range(view.left, view.right, p.width)))
                            .putFloat("floatNoteTy", sy.coerceIn(com.toolsboox.ot.PillBounds.range(view.top, view.bottom, p.height)))
                            .apply()
                        clampPillNow()
                    }
                } else if (w != lastW || h != lastH) {
                    // System bars coming and going: re-seat from your spot, unsaved.
                    view.post { clampPillNow() }
                }
                lastW = w
                lastH = h
            }
            // The pill's own width moves when an ink page lends (or takes back) the corner half:
            // re-seat it from YOUR spot with its new width, so a widened pill can't hang past the
            // edge and a narrowed one goes back where you put it (review 2026-10-11: re-clamped
            // from where it stood, it stayed a corner's width short of your spot after leaving an
            // ink page — the clearance clamp had run against the wide pill still laid out).
            view.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
                if (r - l != or - ol && r > l) clampPill()
            }
        }
        var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f; var dragging = false
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { h, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startTx = view.translationX; startTy = view.translationY; dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        val parent = view.parent as android.view.View
                        // Shared with the pills. This was its own inline copy of the same clamp,
                        // and the copy was worse: with the limits reversed `coerceIn(min, max)`
                        // throws rather than pinning, so a button laid out wider than its parent
                        // took the app down mid-drag instead of merely refusing to move.
                        view.translationX = (startTx + dx).coerceIn(pillXRange(view, parent.width))
                        view.translationY = (startTy + dy)
                            .coerceIn(com.toolsboox.ot.PillBounds.range(view.top, view.bottom, parent.height))
                    }
                    true
                }
                // CANCEL arrives instead of UP when an ancestor takes the gesture — which the ink
                // surfaces do. It fell through to `else -> false`, so on exactly the pages you
                // most want the pen button on, neither the tap nor the hold ever fired.
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putFloat("floatNoteTx", view.translationX)
                            .putFloat("floatNoteTy", view.translationY).apply()
                        refreshPenTouchDelegate()
                        // One report per drag, at its end: the ink page re-cuts round the new spot.
                        bottomPill?.notifyMoved()
                    } else if (e.eventTime - e.downTime >= 550L) {
                        h.performLongClick()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putFloat("floatNoteTx", view.translationX).putFloat("floatNoteTy", view.translationY).apply()
                        refreshPenTouchDelegate()
                        bottomPill?.notifyMoved()
                        // Clean the drag's ghost trail off the e-ink panel.
                        try {
                            com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(
                                com.onyx.android.sdk.api.device.epd.UpdateMode.GC
                            )
                        } catch (t: Throwable) { /* non-Onyx device — no panel to clean */ }
                    } else if (e.eventTime - e.downTime >= 550L) {
                        h.performLongClick()   // hold-in-place → the half's menu
                    } else {
                        h.performClick()
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

    // One face per label — the array runs parallel to QUICK_NOTE_LABELS, and a face short of
    // the labels meant Gram Picks fell through getOrElse to the pencil: the button remembered
    // the choice but wore the wrong coat. ◈ borrows the ❝ quote face the gram rows already wear
    // ("To Pickings", the share sheet). The week/month slots wear the pencil deliberately: they
    // are the same note surface as slot 0, a period deep rather than a day deep.
    private val quickNoteFaces = intArrayOf(
        R.drawable.ic_pencil, R.drawable.ic_reader_view, R.drawable.ic_edit,
        R.drawable.ic_toolbar_text, R.drawable.ic_camera, R.drawable.ic_mic, R.drawable.ic_quote,
        R.drawable.ic_pencil, R.drawable.ic_pencil, R.drawable.ic_edit,
        R.drawable.ic_quote,  // 10 · Synthesize
        R.drawable.ic_feed,   // 11 · Feeds (never worn: ephemeral)
        R.drawable.ic_nav_today   // 12 · Today (never worn: ephemeral)
    )
    /** The glyph for each style, shown in the hold-out picker. Same order as the labels/faces. */
    private val quickNoteGlyphs = arrayOf("✒", "📈", "⌱", "Ⓣ", "📷", "🎤", "◈", "Wk", "Mo", "＋", "❖", "📰", "☀")

    companion object {
        /** Hold-picker entries; the index is stored as the remembered tap action, so keep order stable. */
        // GRID (1) and JOTS (2) are FOLDED (Michael 08-23: "the additional surfaces still exist for
        // the graph and jot"). The Notes IA (DESIGN-notes-ia-2026-08-22) made a template a per-PAGE
        // PROPERTY chosen on the page through the ▤ picker ([NotePageTemplates]), not a door of its
        // own — so a quick-jump that opens a standalone "grid" / "sketch" surface is exactly the
        // redundant destination that ruling retires. The two slots STAY (the index IS the remembered
        // tap action; dropping them would silently re-point everyone's stored choice at a different
        // surface), they are just [FOLDED_QUICK_NOTE_ACTIONS]: never offered in the tray, and a
        // button that still remembers one falls back to Notes. No ink is touched — existing grid/jot
        // documents are still reached through the Notes directory (Notes › All notes › Grid / Jots).
        //
        // THIS IS STILL THE FAST DOOR. Its remaining rows keep their one-tap capture — Notes, Text,
        // photo, voice, Gram Picks — hold once to pick, tap for ever after.
        val QUICK_NOTE_LABELS = arrayOf(
            "Notes — where you left off",
            "Grid",
            "Jots",
            // "Text Notes" — Michael's own word for it, and iOS's row (2026-10-11 parity).
            "Text Notes",
            "Capture a photo",
            "Record a voice pick",
            // APPENDED, never inserted: the index IS the remembered tap action, so putting Gram Picks
            // anywhere but the end would silently re-point everyone's remembered button at a
            // different surface. Michael: "the note modal quick jump button does not have a gram
            // picks on long hold" — it is a note surface, so it belongs in the notes picker.
            "All Picks",
            // A NOTE FOR A WEEK, A NOTE FOR A MONTH (Michael 08-27: "create a note for today, a
            // note for a week, a note for a month when using the note button"). The dormant
            // toolsboox period surfaces, promoted (DESIGN-notes-room-2026-08-27 §2c). Appended,
            // same law as above.
            "This week's note",
            "This month's note",
            // ＋ NEW NOTE from the pen tray (Michael, field, 08-27: "Long hold on note button
            // opens the slide out" and there was no way to MINT from it). Appended, same law.
            "New note…",
            // APPENDED (index 10): the third seat of the trimmed button — see FOLDED below.
            "Synthesize",
            // APPENDED (11, 12) with the bottom pill (Michael 2026-10-11): the hold menu is
            // "Notes, text notes, back to feeds" and gains Today — iOS's noteMenuRows' names
            // (Back to your reading, Today), drawn in its order ([QUICK_NOTE_TRAY_ORDER]). 📰 lands
            // where you were (the Hub's Feeds door, openFeedView: your lens, feed and place); ☀
            // Today is the panel's Today seat — today's page, or today's version of the page you're on.
            "Back to your reading",
            "Today"
        )

        /** Slots that RUN but are never REMEMBERED as the tap action: a picker as the tap would
         *  be a mode. ＋ New note (9), and the two doors AWAY from writing — 📰 Feeds (11) and
         *  ☀ Today (12): the notes half's tap stays a way into your notes. */
        val EPHEMERAL_QUICK_NOTE_ACTIONS = setOf(9, 11, 12)

        /** The order the tray DRAWS its slots in — iOS's noteMenuRows (PARITY, review 2026-10-11):
         *  ✒ Notes · 📰 Back to your reading · Ⓣ Text Notes · ❖ Synthesize · ☀ Today, Today last so
         *  the rows Michael already reaches for keep their places. The slot indices stay the stored
         *  tap's; any slot not named here (none offered today) follows in index order. */
        val QUICK_NOTE_TRAY_ORDER = listOf(0, 11, 3, 10, 12)

        /** The retired standalone-surface slots — Grid (1) and Jots (2). Kept in the arrays for
         *  index stability, but skipped when the tray is drawn and coerced to Notes when read/run,
         *  so the pen never opens a "grid"/"sketch" surface as its own destination again. */
        // THE BUTTON IS THREE THINGS NOW — Notes · Text · Synthesize (Michael, 09-05: "we only need a
        // notes and text notes and synthesize right? … the jump notes ain't right, and maybe that's
        // part of where we get some of the weird lines clobbering"). Every other face folds; a
        // remembered folded action falls back to Notes (quickNoteAction). Their code stays.
        val FOLDED_QUICK_NOTE_ACTIONS = setOf(1, 2, 4, 5, 6, 7, 8, 9)
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

    /**
     * A whole-screen writing page asks the pen pill to stand aside while it is up (Michael
     * 2026-10-11, the one compose screen): the pill's corner is the bottom of the page — the palm
     * zone, and over the ink pad, where the Bigme daemon would take its taps for ink. Transient,
     * never saved: the page clears it on the way out and the pill's own switch is untouched.
     */
    var quickNoteSuppressed = false
        set(v) { if (field != v) { field = v; applyQuickNoteVisibility() } }

    /**
     * The reader holding the notes pill away while its full screen leaves nothing on the page
     * (Michael 2026-10-12, [com.toolsboox.ot.ReaderFullScreen]) — null when none is. A sibling of
     * [quickNoteSuppressed], never saved, so the pill's own switch and its dragged spot are untouched.
     */
    private var readerAwayOwner: Any? = null

    override fun readerAway(owner: Any, away: Boolean) {
        if (away) {
            if (readerAwayOwner === owner) return
            readerAwayOwner = owner
        } else {
            if (readerAwayOwner !== owner) return
            readerAwayOwner = null
        }
        applyQuickNoteVisibility()
        // The read-aloud / audiobook chip stands aside with it, back with the peek (review
        // 2026-10-12, as iOS's Now Playing does) — the same owner rule, so a reader that goes
        // gives it back.
        runCatching { com.toolsboox.plugin.feeds.ui.NowPlayingCard.summonAway(this, readerAwayOwner != null) }
    }

    /**
     * THE KEYBOARD'S HEIGHT over the content frame (0 when it's down), for a screen that makes
     * room for it itself (Michael 2026-10-11: "the keyboard stops covering the body"). Nothing
     * app-wide moves for the keyboard — the frame keeps padding only for the system bars, and an
     * ink pad must never change size under the Bigme daemon — so the one screen that listens
     * decides what may give way.
     */
    var imeInset = 0
        private set
    var imeInsetListener: ((Int) -> Unit)? = null

    private fun applyQuickNoteVisibility() {
        // The notes half only: on an ink page the corner half stays — it is full screen's way
        // back (Michael 2026-10-11), and a hidden convenience must never take the exit with it.
        bottomPill?.notesShown = quickNoteVisible() && !quickNoteSuppressed && readerAwayOwner == null
        // The touch delegate outlives visibility on its own — a hidden button would keep
        // swallowing taps in its old corner. Keep the two in step.
        refreshPenTouchDelegate()
    }

    private fun quickNoteAction(): Int =
        getSharedPreferences("MAIN", MODE_PRIVATE).getInt("quick_note_action", 0)
            .coerceIn(0, QUICK_NOTE_LABELS.size - 1)
            // A button that still remembers the folded Grid/Jots surface falls back to Notes,
            // and so does an ephemeral slot that should never have been remembered.
            .let { if (it in FOLDED_QUICK_NOTE_ACTIONS || it in EPHEMERAL_QUICK_NOTE_ACTIONS) 0 else it }

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
        // The pen pauses over an ink page before the tray can take a tap, and the page cuts it out
        // of the B6 daemon's writes and the Onyx limit while it is up (review 2026-10-11) — the
        // corner's picker's own protection. Off the ink pages there is no corner and nothing to do.
        val corner = bottomPill?.corner
        val order = QUICK_NOTE_TRAY_ORDER + quickNoteGlyphs.indices.filter { it !in QUICK_NOTE_TRAY_ORDER }
        for (i in order) {
            val glyph = quickNoteGlyphs.getOrNull(i) ?: continue
            // Grid/Jots are folded: not offered as standalone surfaces (template is a ▤ page choice now).
            if (i in FOLDED_QUICK_NOTE_ACTIONS) continue
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
                    // Ephemeral slots run without being remembered — the tap keeps its one job.
                    if (i !in EPHEMERAL_QUICK_NOTE_ACTIONS) setQuickNoteAction(i)
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
            setOnDismissListener { corner?.trayClosed() }
        }
        // Unfurl to the RIGHT of the pen button, vertically centred on it.
        val anchor = binding.floatNoteButton
        row.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
        corner?.trayOpened(row)
        if (runCatching { popup.showAsDropDown(anchor, anchor.width + px(6),
                -(anchor.height + row.measuredHeight) / 2) }.isFailure) corner?.trayClosed()
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
            // 1 (Grid) and 2 (Jots) are folded onto Notes: no standalone grid/sketch surface — the
            // template is a per-page choice via ▤. Defensive here too (the tray already skips them).
            0, 1, 2 -> openLastNotePage()
            3 -> binding.fragmentContent.findNavController().navigate(R.id.action_to_text_notes)
            4 -> startCapture()
            5 -> requestVoiceGram()
            6 -> navigateToDayNote(com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS)
            7 -> navigateToWeekNote()
            8 -> navigateToMonthNote()
            9 -> mintNewNote()
            10 -> navigateToDayNote("synthesize")
            // From inside The Mail's room the hop REPLACES that room, as its drawer's "📰 Feeds ↗"
            // does — Back goes where it went before, and two rooms' WebViews don't pile up.
            11 -> binding.fragmentContent.findNavController().let { nav ->
                com.toolsboox.plugin.feeds.ui.openFeedView(nav, "both", null,
                    popMail = nav.currentDestination?.id == R.id.MailFragment)
            }
            12 -> openToday()
        }
    }

    /** ☀ Today from the notes tray — the SAME act as the panel's Today seat (Michael 2026-10-11):
     *  on a day page it is that page's own Today (today's version of the section you're on, or
     *  today's page; "You're on today" when you are); anywhere else, today's page. */
    private fun openToday() {
        val host = supportFragmentManager.findFragmentById(R.id.fragmentContent)
        val day = host?.childFragmentManager?.primaryNavigationFragment as?
            com.toolsboox.plugin.calendar.ui.CalendarDayFragment
        if (day != null && day.isResumed) { day.goToday(); return }
        val d = java.time.LocalDate.now()
        binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundleOf(
            "year" to "${d.year}", "month" to "${d.monthValue}", "day" to "${d.dayOfMonth}"))
    }

    /** ＋ New note from the tray: the name-pad mint on whatever screen is up; with no resumed
     *  screen to hang the pad on, land on today's notes first — minting lives one tap away there. */
    private fun mintNewNote() {
        val screen = resumedScreen()
        if (screen != null)
            com.toolsboox.plugin.feeds.ui.showNewNotePicker(screen, java.time.LocalDate.now())
        else openLastNotePage()
    }

    /** This week's note — the toolsboox week surface, promoted into the Notes room (08-27).
     *  The week number is calendar-year weekOfYear in the CALENDAR'S locale ("calendarLocale"
     *  pref) — the exact pair CalendarWeekFragment resolves with, so this never opens a
     *  neighbouring week at a year boundary or under a locale override. */
    private fun navigateToWeekNote() {
        val d = java.time.LocalDate.now()
        val tag = getSharedPreferences("MAIN", MODE_PRIVATE).getString("calendarLocale", null)
        val locale = tag?.let { t ->
            java.util.Locale.forLanguageTag(t).takeIf { it.toLanguageTag() == t }
        } ?: java.util.Locale.getDefault()
        val wf = java.time.temporal.WeekFields.of(locale)
        binding.fragmentContent.findNavController().navigate(
            R.id.action_to_calendar_week, androidx.core.os.bundleOf(
                "year" to d.year.toString(),
                "weekOfYear" to d.get(wf.weekOfYear()).toString(),
                "notePage" to "0"))
    }

    /** This month's note — same promotion, month surface. */
    private fun navigateToMonthNote() {
        val d = java.time.LocalDate.now()
        binding.fragmentContent.findNavController().navigate(
            R.id.action_to_calendar_month, androidx.core.os.bundleOf(
                "year" to d.year.toString(),
                "month" to d.monthValue.toString(),
                "notePage" to "0"))
    }

    /** Note button, two-tap toggle:
     *  • not on a note → resume the last note page you were writing on;
     *  • already on that note → flip to that day's page ("days").
     *  The view state (on-note? which date?) is recorded by CalendarDayFragment on every load. */
    private fun openLastNotePage() {
        val p = getSharedPreferences("ledger_notes", 0)
        val nav = binding.fragmentContent.findNavController()
        // Live check — are we ACTUALLY standing on a note page right now? A persisted flag went
        // stale once and flipped this button to the schedule unexpectedly, so the question is asked
        // of the surface itself rather than of anything remembered.
        //
        // It used to be asked of the NAV DESTINATION, because the pen button had a destination of
        // its own (the "Scratch" alias of the day fragment). That alias is retired with the rest of
        // the Scratch vocabulary and the button lands on the ordinary day destination now — so the
        // live question moves one step closer to the truth: the fragment on screen knows which page
        // it is showing.
        //
        // "ON YOUR NOTE" MEANS THE NUMERIC NOTES PAGE, NOT ANY NOTE-KEYED SURFACE. Michael's 08-12
        // punchlist: "The Note jump button goes first to the today page, then Notes on a second
        // push." The double-hop was this toggle answering its question too broadly: `notePage !=
        // null` is true on EVERY note-keyed surface — All Stars ("intake"), Pickings, Gratitude,
        // Gram Picks, a Write/Grid/Sketch document — so pressing Notes while standing on ANY of
        // them took the "you're already on your note → flip to the day page" branch. First tap:
        // today's day page (never asked for); second tap (now genuinely off a note): Notes at
        // last. But the toggle's own memory says what "your note" is: rememberNoteLocation stores
        // NUMERIC pages only (the free-form lined pages this button resumes), so the flip-to-day
        // branch is honest exactly when the surface on screen is one of those. Everything else is
        // "not on your note" and one tap lands on the note directly. `substringBefore('#')` keeps
        // a numeric sub-page counting as its base, the same convention the pager uses.
        if (currentDayNotePage()?.substringBefore('#')?.toIntOrNull() != null) {
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
            nav.navigate(R.id.action_to_calendar_day, bundleOf(
                "year" to "${today.year}", "month" to "${today.monthValue}", "day" to "${today.dayOfMonth}",
                "notePage" to page))
        }
    }

    /** The note-page key of the day surface on screen, or null when we're not on one (another
     *  screen entirely, or the day page itself). Read straight off the fragment, so it cannot go
     *  stale the way a remembered flag could. */
    private fun currentDayNotePage(): String? {
        val host = supportFragmentManager.findFragmentById(R.id.fragmentContent)
        val current = host?.childFragmentManager?.primaryNavigationFragment
        return (current as? com.toolsboox.plugin.calendar.ui.CalendarDayFragment)?.currentNotePage()
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
    ) { granted ->
        val sink = micSink
        micSink = null
        if (granted) (sink ?: ::startVoiceGram)() else toast("Microphone permission is needed to record")
    }

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
                    toast(if (placed) "🎤 Voice pick → today's Pickings" else "Voice pick saved")
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

    /**
     * Consume the Task Checklist widget's done-marks — the app end of the single-writer channel
     * (see [com.toolsboox.plugin.calendar.ot.TaskDoneQueue] for the corruption story that forced
     * it). The widget's ✓ never writes a day file; it queues, and THIS is the only consumer:
     * called from [onResume] (the foreground drain) and from the queue's [onEnqueued] nudge
     * while this activity is resumed (the tap-lands-in-a-breath drain). The drain itself runs
     * each load→mark→save under DayLocks on IO, like every other writer in this activity.
     *
     * After a real change, the same telling as every other background writer here (see the
     * "THE WHOLE ACTIVITY IS A BACKGROUND WRITER" essay above): a resumed day page is holding a
     * pre-drain copy of its day, and its next pen-up save would write the un-done task straight
     * back. Re-read, never save.
     */
    private fun drainWidgetDoneQueue() {
        lifecycleScope.launch {
            val changed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.TaskDoneQueue.drain(
                        this@MainActivity, calendarDayService, documentsRoot()
                    )
                }.getOrDefault(emptySet())
            }
            if (changed.isNotEmpty()) {
                // The done flag lives in the day JSON the page has open; same telling (and same
                // pageKey) as the OCR task filing above — the fragment re-reads its currentDate.
                tellSurfaceGramPlaced(com.toolsboox.plugin.calendar.ot.PickingsStore.DEFAULT_KEY)
                // And the widget family re-renders off the now-saved files, so the optimistic
                // face and the truth converge.
                runCatching {
                    com.toolsboox.plugin.calendar.widget.CalendarWidgetProvider.refreshAllDebounced(this@MainActivity)
                }
            }
        }
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

    /** Place the photo as a Ledger object and offer OCR. The destination is the REMEMBERED one —
     *  the shutter must stay one tap, so the memory does the routing (Gram Picks whenever the
     *  remembered place no longer exists today). */
    private fun ingestBitmap(bmp: android.graphics.Bitmap?) {
        if (bmp == null) { toast("Couldn't read that image"); return }
        lifecycleScope.launch {
            val dest = com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(this@MainActivity)
            val placed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        calendarDayService, documentsRoot(), bmp, java.time.LocalDate.now(),
                        dest.key, sourceLabel = "📷 Photo · ${java.time.LocalDate.now()}",
                        intakeKind = dest.kind
                    )
                }.isSuccess
            }
            // Same telling as the OCR path below, and needed a step earlier: the photo gram itself
            // goes into today's day file from up here, so a day page on screen is stale from the
            // moment the shutter closes — before the OCR dialog has even been offered.
            if (placed) tellSurfaceGramPlaced(dest.key)
            toast("Added to ${dest.name}")
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
                        com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                            // Throws (→ filed = false) rather than save over a today-file that won't read.
                            val day = calendarDayService.loadForWriteOrThrow(root, today, null, java.util.Locale.getDefault())
                            day.ledgerItems.add(com.toolsboox.plugin.calendar.da.v2.LedgerItem(
                                id = "photo-" + java.util.UUID.randomUUID().toString().lowercase(),
                                kind = if (asEvent) com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.EVENT
                                       else com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
                                text = text, date = java.util.Date(), stage = "todo"))
                            calendarDayService.save(root, today, day)
                        }
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

    /**
     * The human reading of a destination for the 🕘 History fold — the hub's vocabulary
     * (emoji + name), enriched from args where the args are what make it a place: a day page
     * is WHICH day (and which note page), not "CalendarDayFragment". Unknown destinations fall
     * back to the graph label so a future surface is never invisible to history, just plain.
     */
    private fun historyLabel(dest: androidx.navigation.NavDestination, args: Bundle?): String = when (dest.id) {
        R.id.CalendarDayFragment -> {
            val date = runCatching {
                java.time.LocalDate.of(
                    args?.getString("year")!!.toInt(), args.getString("month")!!.toInt(), args.getString("day")!!.toInt()
                )
            }.getOrNull()
            val note = args?.getString("notePage")
            buildString {
                append("📅  Day")
                if (date != null) append(" · " + date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")))
                if (!note.isNullOrBlank()) append(" · $note")
            }
        }
        R.id.CalendarWeekFragment -> "🗓  Week"
        R.id.CalendarMonthFragment -> "🗓  Month"
        R.id.CalendarQuarterFragment -> "🗓  Quarter"
        R.id.CalendarYearFragment -> "🗓  Year"
        R.id.FeedsFragment -> "📰  Feeds"
        // The Mail's own room (Michael 2026-10-11) — its visits read as The Mail on the trail.
        R.id.MailFragment -> "📧  The Mail"
        R.id.FeedArticleFragment -> "📄  Article"
        R.id.LedgerChatFragment -> "💬  Ask"
        R.id.BookshelfFragment -> "📚  Bookshelf"
        R.id.ReaderFragment -> "📖  Reader"
        R.id.TextNotesFragment -> "🗒  Text Notes"
        R.id.ReadingLogFragment -> "🔍  Search"
        R.id.YearInLedgerFragment -> "📊  Your year"
        R.id.LedgerItemsFragment -> "☑  Items"
        R.id.LedgerMapFragment -> "🗺  Map"
        R.id.LedgerRhizomeFragment -> "🕸  Rhizome"
        R.id.QuickWinsFragment -> "⚡  Quick Wins"
        R.id.MissedRhizomesFragment -> "🔀  Missed Connections"
        R.id.RolodexFragment -> "📇  Rolodex"
        R.id.CorrespondenceFragment -> "✉️  Correspondence"
        R.id.SiteBoardsFragment -> "🗂  Site Boards"
        R.id.MessagesFragment -> "💬  Messages"
        R.id.BlueskyFragment -> "🦋  Bluesky"
        R.id.SiteWebFragment -> "🌐  Site"
        R.id.RosterFragment -> "👥  Roster"
        R.id.PublishFragment -> "📤  Publish"
        R.id.PostsBrowserFragment -> "📄  Posts"
        R.id.MailComposeFragment -> "✉  Compose"
        // The screen's own name (strings.xml notes_tags_title) and the iPhone's — "Tags" here read
        // as the tag index, a different room (Michael 2026-10-11: "'Notes and tags' doesn't go
        // anywhere"). 🗓 as on iOS and the hub's own row: 🏷 is "Tag this page…" in both apps.
        R.id.NotesTagsFragment -> "🗓  Notes & Tags"
        R.id.CalendarSettingsFragment -> "⚙️  Settings"
        else -> dest.label?.toString() ?: "Surface"
    }

    override fun onPause() {
        // Unhook the widget-queue nudge: a paused activity must not be the one draining (its
        // fragments can't be told to re-read), and holding a lambda over a dying activity in a
        // process-lifetime object would leak it. Marks enqueued while paused wait for onResume.
        com.toolsboox.plugin.calendar.ot.TaskDoneQueue.onEnqueued = null
        com.toolsboox.ui.share.ShareNoteQueue.onEnqueued = null
        com.toolsboox.ui.share.ShareActions.onDayWritten = null
        // Words a ❖ summon finishes with from here on are owed, not toasted into the void.
        com.toolsboox.plugin.calendar.ui.SynthSummons.foreground = false
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    /**
     * A re-delivered intent must become THE intent, or it never happened. Every widget row and
     * share lands its payload in extras that [onResume] reads through getIntent() — but when the
     * task already exists, the system may hand the tap to the LIVE activity through here instead
     * of recreating it (standard launchMode + CLEAR_TOP leaves it the system's choice), and
     * without setIntent the new extras die in this parameter while onResume keeps re-reading the
     * stale launch intent. That was the Feed List widget's "tap a row, arrive at the app,
     * no article" (08-17 page).
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        staleLaunchIntent = false
        intentArrivedWarm = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        staleLaunchIntent = savedInstanceState != null
        // Recents relaunching a task whose ROOT is a card launch (no Ledgable task existed, then
        // Back emptied it) hands back that same intent, token and all — a replay too.
        val fromHistory = ((intent?.flags ?: 0) and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (staleLaunchIntent || fromHistory) scrubReplayedLaunch()
        // A share aimed straight at this component (EinkBro's remembered target, C8 — it only
        // gets here on Android 12 and below, see forwardForeignShare) is the card's: hand it over
        // before the day page inflates, and leave no second Ledgable in the sender.
        if (savedInstanceState == null && forwardForeignShare()) {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
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
        // ⏱ The mail + RSS fetch cycle (user-timed in Feed settings; idempotent re-arm) — so
        // both lanes are already offline-readable when the surfaces open, not only after.
        runCatching { com.toolsboox.plugin.feeds.nw.FeedFetchWorker.schedule(applicationContext) }
        // Which way up, before the first frame. The three controls that turn the screen all set
        // `requestedOrientation`, which is activity state and dies with the process — so a cold
        // start fell back to the manifest's `sensorPortrait` no matter what had been chosen, and
        // the gyro switch appeared to turn itself back on overnight. Re-asserted here rather than
        // after `setContentView` so the window is laid out once, in the right orientation: doing it
        // later costs a visible re-layout, which on e-ink is a full flashing redraw.
        com.toolsboox.ot.ScreenRotation.restore(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // THE OLD PAGE FLASHING UP ON LAUNCH IS ANDROID'S TASK SNAPSHOT (Michael, 09-14, B6, after
        // a cold power-off and a clean daemon: "it still flashes the notes"). Every time the app
        // goes to the background Android screenshots its last frame; the next launch paints that
        // screenshot as the starting window until our first frame lands — the notes page you
        // left on, then the today page, and on e-ink the swap leaves ghosting. The snapshot is
        // persisted to disk, which is why no reboot and no daemon clear ever touched it. Off:
        // the starting window is the theme's plain background, and the recents card shows the
        // app icon instead of a page (a fair trade on an e-ink app). API 33 has the public
        // setter; before it the same method is hidden — the exemption is taken here first (the
        // one further down in onCreate runs too late for this call).
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("")
            if (Build.VERSION.SDK_INT < 33) android.app.Activity::class.java
                .getMethod("setDisablePreviewScreenshots", Boolean::class.javaPrimitiveType)
                .invoke(this, true)
        }.onFailure { Timber.w(it, "task snapshot could not be disabled") }

        // 🕘 History's ONE recorder. There are 112 navigate() call sites and no router, so the
        // NavController itself is the only true choke point — every hop, from every surface,
        // ends here, and the hub's History fold reads what this wrote (LedgerHistory). Looked
        // up through the NavHostFragment, not binding.fragmentContent.findNavController():
        // with a FragmentContainerView the view-based lookup throws until the first layout.
        (supportFragmentManager.findFragmentById(R.id.fragmentContent)
            as? androidx.navigation.fragment.NavHostFragment)?.navController
            ?.addOnDestinationChangedListener { _, dest, args ->
                runCatching {
                    com.toolsboox.ui.plugin.LedgerHistory.record(dest.id, historyLabel(dest, args), args)
                }
            }

        // EDGE-TO-EDGE, APP-WIDE. The window draws behind the system bars and the insets pad
        // the content frame back — ALL FOUR SIDES now.
        //
        // The sides were DROPPED here from 1.06.22 to 1.06.49, on the bet that a transparent
        // navigationBarColor (below) made the landscape side bar a window: the Palma's 90px
        // landscape inset read as "a dead white band past the rail" (Michael: "extra space
        // being used for no reason"), so the listener consumed it and let content run to the
        // physical edge. Michael's 2026-08-10 landscape screenshot of the feeds surface is the
        // bet losing: the Boox panel paints the navigation bar OPAQUE WHITE no matter what
        // color the window asks for, so the freed band is a wall, not a window — a blank
        // ~40dp column down the LEFT edge with the tucked rail's 7dp strip invisible under it
        // and the directory pane's first ~40dp swallowed ("This feed" reading "…is feed", the
        // radio circles and chip left-halves gone). The day page never showed it because ink
        // surfaces go immersive (SurfaceFragment.onResume hides the bars — zero insets, truly
        // full-bleed); the list surfaces keep the bars, so they laid out under the wall.
        //
        // So: a side inset the system reports is a band the system OWNS and Onyx paints —
        // content must stop at it, exactly as it stops at the top and bottom bars. The true
        // edge survives everywhere it is real: portrait (the bar is a bottom bar, side insets
        // zero) and every immersive ink page. The Palma's landscape band comes back — but as
        // an honest system bar beside complete content, not a wall over the first two letters
        // of every row.
        // The ink surfaces' immersive dance (SurfaceFragment.onResume/onPause) rides this same
        // listener: hiding the bars dispatches zero insets, showing them pads the drawer back.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A side/bottom bar over the page must be see-through, or the freed band would just
            // repaint as an opaque bar; and the system must not scrim it back for "contrast" —
            // on a monochrome panel there is no contrast to enforce.
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
        }
        // The padding goes on mainContentFrame, NOT the DrawerLayout: DrawerLayout measures and
        // lays out its content child against its full bounds, ignoring its own padding (its
        // fitsSystemWindows path was special-cased internally, never padding-based) — padding
        // set on it lands nowhere, verified live on the Palma.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.mainContentFrame.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // The keyboard's height is PASSED ON, never padded in here: an ink surface must not
            // change size under the Bigme daemon (see [imeInset]).
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val over = (ime - bars.bottom).coerceAtLeast(0)
            if (over != imeInset) { imeInset = over; imeInsetListener?.invoke(over) }
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        // Global "pull up the Notes surface" button — available on every screen. It reopens the
        // note page you last had open (same memory the menus' "✒ Notes" uses), falling back to
        // today's first page. Hold it instead to switch over to Text Notes.
        binding.floatNoteButton.setOnClickListener { runQuickNoteAction(quickNoteAction()) }
        binding.floatNoteButton.setOnLongClickListener { showQuickNoteSelector(); true }
        // THE BOTTOM PILL (Michael 2026-10-11: "The one top left and the one bottom right"): the
        // notes button and full screen's old top-left pill, one oblong. The corner half's tap
        // repeats the last-chosen action, its hold offers the four (the ink page answers both).
        pill = com.toolsboox.ot.BottomPill(binding.floatNotePill, binding.floatCornerButton,
            binding.floatNoteDivider, binding.floatNoteButton).also { bp ->
            bp.onClearanceChanged = { clampPill() }
            binding.floatCornerButton.setOnClickListener { bp.corner?.perform(bp.chosen()) }
            binding.floatCornerButton.setOnLongClickListener { bp.corner?.showPicker(); true }
        }
        makeFloatButtonDraggable(binding.floatNoteButton, binding.floatNotePill, restore = true)
        makeFloatButtonDraggable(binding.floatCornerButton, binding.floatNotePill, restore = false)
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

        // If the last run ended in a crash, say so once — a toast, never a dialog, and never
        // again for the same crash (Diagnostics remembers which file it announced).
        com.toolsboox.ot.Diagnostics.noticeCrashOnce(this)

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

        // A card row that opens Ledgable on a COLD start: route now, before the start destination
        // (today's day page) ever gets a view — the B6 then never draws today first (P2).
        if (savedInstanceState == null) routeCardHandoff(warm = false)
    }

    /**
     * C8 — EinkBro's "share to last target" stores the component the user once picked, and before
     * 2026-10-10 that was this activity. With the SEND filters moved to the card it fires an
     * explicit SEND here, which would open the old chooser inside a second Ledgable in EinkBro's
     * task. So a SEND from another package that the card didn't send on (EXTRA_FROM_CARD) is
     * forwarded to the card — referrer carried, so it still says "via EinkBro", and the read
     * grant passed on. True when forwarded.
     *
     * ANDROID 12 AND BELOW ONLY (review, 2026-10-10): Ledgable targets 36, and from Android 13 an
     * explicit intent from another app must match one of the receiving component's filters —
     * MainActivity has no SEND filter now, so the system drops EinkBro's remembered SEND before it
     * arrives (EinkBro should fall back to its chooser, where Ledgable is the card — field-check;
     * the "2 taps with last-target" figure holds only on 12 and below). The forward is for the
     * Android 11/12 Boox slabs.
     *
     * The card must float over the SENDER, never over our own day page: a SEND that arrived with
     * NEW_TASK — or into this live copy ([warm], onNewIntent) — is sitting in Ledgable's task, so
     * the card gets a task of its own and ours goes to the back, leaving the sender behind it.
     */
    private fun forwardForeignShare(warm: Boolean = false): Boolean {
        val i = intent ?: return false
        if (i.action != android.content.Intent.ACTION_SEND) return false
        if (i.getBooleanExtra(com.toolsboox.ui.share.ShareActions.EXTRA_FROM_CARD, false)) return false
        val from = referrer?.takeIf { it.scheme == "android-app" }?.host
        if (from == packageName) return false
        val fwd = android.content.Intent(i).setClass(this, com.toolsboox.ui.share.ShareCatchActivity::class.java)
        fwd.flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val ownTask = warm || (i.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        if (ownTask) fwd.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        fwd.putExtra(com.toolsboox.ui.share.ShareActions.EXTRA_FORWARDED, true)
        referrer?.let { fwd.putExtra(android.content.Intent.EXTRA_REFERRER, it) }
        return runCatching {
            startActivity(fwd, android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
            if (ownTask) moveTaskToBack(true)
        }.onFailure { Timber.w(it, "share → card forward failed") }.isSuccess
    }

    /**
     * A RESTORED copy (process death, or a recreate) is handed the ORIGINAL launch intent, and a
     * card handoff or a share in it was already followed: strip them before anything reads them,
     * so a reopen never replays the chooser over a share whose cached image may be swept by now
     * (review, 2026-10-10; the 09-07 rule).
     */
    private fun scrubReplayedLaunch() {
        val i = intent ?: return
        val sa = com.toolsboox.ui.share.ShareActions
        i.removeExtra(sa.EXTRA_CARD_OPEN)
        i.removeExtra(sa.EXTRA_CARD_SEND)
        if (i.action == android.content.Intent.ACTION_SEND) i.action = null
    }

    /**
     * ⋯ More (and a share from Ledgable to itself): the card's SEND arrives whole inside a
     * launcher-shaped intent (ShareActions.EXTRA_CARD_SEND) — unwrap it into THE intent, so the
     * share arms in [onResume] read it exactly as before. Only with this process's card token.
     */
    private fun unwrapCardSend() {
        val outer = intent ?: return
        val sa = com.toolsboox.ui.share.ShareActions
        if (!outer.hasExtra(sa.EXTRA_CARD_SEND)) return
        @Suppress("DEPRECATION")
        val send = runCatching { outer.getParcelableExtra<android.content.Intent>(sa.EXTRA_CARD_SEND) }.getOrNull()
        val trusted = sa.isCardToken(outer.getStringExtra(sa.EXTRA_CARD_TOKEN))
        outer.removeExtra(sa.EXTRA_CARD_SEND)
        if (send == null || !trusted || staleLaunchIntent) return
        setIntent(send)
    }

    /**
     * Arriving at the day page from a share: REPLACE it when it's the screen you're on (one day
     * surface, never two stacked), PUSH it over anything else so Back still returns to the book or
     * article you left. Card handoffs and ⋯ More land in your live session now, not a throwaway
     * copy in the sender's task (review, 2026-10-10: "not losing features, not having too many
     * clicks").
     */
    private fun shareDayNavOptions(): androidx.navigation.NavOptions? {
        val nav = (supportFragmentManager.findFragmentById(R.id.fragmentContent)
            as? androidx.navigation.fragment.NavHostFragment)?.navController
        return if (nav?.currentDestination?.id == R.id.CalendarDayFragment)
            androidx.navigation.navOptions { popUpTo(R.id.CalendarDayFragment) { inclusive = true } }
        else null
    }

    /**
     * The card's "take me there" rows land here (ShareActions.OPEN_*). CONSUMED on first sight —
     * a B6 rotation recreates the activity with the same intent, and an unconsumed handoff would
     * re-navigate (the 09-07 rule). [warm] = the activity was already up: execute the navigation
     * NOW, while the fragments are still only started, so the old screen never resumes (never
     * re-attaches the ink daemon) and the target page primes its own template first (P2).
     * Followed only with this process's card token — the activity is exported (review, 2026-10-10).
     */
    private fun routeCardHandoff(warm: Boolean) {
        val i = intent ?: return
        val sa = com.toolsboox.ui.share.ShareActions
        val what = i.getStringExtra(sa.EXTRA_CARD_OPEN) ?: return
        val trusted = sa.isCardToken(i.getStringExtra(sa.EXTRA_CARD_TOKEN))
        i.removeExtra(sa.EXTRA_CARD_OPEN)
        if (!trusted) { Timber.w("card handoff without the card's token — ignored"); return }
        val host = supportFragmentManager.findFragmentById(R.id.fragmentContent)
            as? androidx.navigation.fragment.NavHostFragment ?: return
        val nav = host.navController
        val dayNav = shareDayNavOptions()
        runCatching {
            when (what) {
                sa.OPEN_NOTE -> {
                    val date = java.time.LocalDate.parse(i.getStringExtra(sa.EXTRA_DATE))
                    nav.navigate(R.id.action_to_calendar_day, bundleOf(
                        "year" to "${date.year}", "month" to "${date.monthValue}", "day" to "${date.dayOfMonth}",
                        "notePage" to i.getStringExtra(sa.EXTRA_KEY)))
                }
                sa.OPEN_DAY -> nav.navigate(R.id.action_to_calendar_day, null, dayNav)
                sa.OPEN_TEXT_ON_DAY -> {
                    val bundle = bundleOf("sharedText" to wrapForTextBox(i.getStringExtra(sa.EXTRA_TEXT).orEmpty()))
                    i.getStringExtra(sa.EXTRA_URL)?.let {
                        bundle.putString("sharedUrl", it); bundle.putString("notePage", "intake")
                    }
                    nav.navigate(R.id.action_to_calendar_day, bundle, dayNav)
                }
                sa.OPEN_IMAGE_ON_DAY -> {
                    val img = i.getStringExtra(sa.EXTRA_IMAGE)
                    if (sa.isOwnShareImage(this, img))
                        nav.navigate(R.id.action_to_calendar_day, bundleOf("sharedImageUri" to img), dayNav)
                }
                sa.OPEN_ASK -> nav.navigate(R.id.action_to_ledger_chat, bundleOf(
                    "ask_passage" to i.getStringExtra(sa.EXTRA_ASK_PASSAGE),
                    "ask_context" to i.getStringExtra(sa.EXTRA_ASK_CONTEXT)))
            }
            if (warm) host.childFragmentManager.executePendingTransactions()
        }.onFailure { Timber.w(it, "card handoff ($what) failed") }
    }

    /** Tell a resumed Text Notes screen about notes the card wrote — re-read, never save. */
    private fun drainSharedNotes() {
        for (d in com.toolsboox.ui.share.ShareNoteQueue.drain(this)) tellSurfaceNoteAdded(d)
    }

    /**
     * Ledgable never offers itself in its own selection menus (C9): once it answers PROCESS_TEXT,
     * Android lists "Ledgable" in every text selection on the device — here it would open the
     * card over our own ink surface. This hears every action mode in the activity's window (the
     * reader's web views, the day page's text); dialog windows guard their fields with
     * [com.toolsboox.ui.share.OwnProcessText.guard] (ScreenFragment.showModal does it for every
     * routed dialog).
     *
     * Web views are left to themselves (review, 2026-10-10): the reader, article and Site web
     * views are SelectionMenuWebViews, which hide the item on every prepare; for a plain one an
     * invalidate here only re-runs Chromium's prepare, which puts the item straight back — a
     * second toolbar redraw on e-ink for nothing.
     */
    override fun onActionModeStarted(mode: android.view.ActionMode?) {
        super.onActionModeStarted(mode)
        if (currentFocus is android.webkit.WebView) return
        if (mode != null && com.toolsboox.ui.share.OwnProcessText.strip(mode.menu, this)) {
            runCatching { mode.invalidate() }
        }
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
     * The resumed screen fragment, when there is one — the share chrome needs it for the
     * modal door below. Same resolution as [handleVolumeKey], and for the same reason: the
     * fragment on screen is a fact best read at the moment it's needed.
     */
    private fun resumedScreenFragment(): com.toolsboox.ui.plugin.ScreenFragment? {
        val navHost = supportFragmentManager.primaryNavigationFragment ?: return null
        return navHost.childFragmentManager.fragments.lastOrNull { it.isResumed }
            as? com.toolsboox.ui.plugin.ScreenFragment
    }

    /**
     * THE MODAL DOOR FOR THE SHARE CHROME — never a bare show(). A share lands over whatever
     * screen was up, and on a cold start that is the day page: an ink-capable surface whose
     * hardware pen stays armed under an unrouted dialog, so every stylus tap fell through to
     * the ink layer and the chooser "froze" — the tap that should have filed the link filed
     * nothing. That was "Later-list shares/saves don't import" (08-17 queue), the same freeze
     * the reader's note menu had, and the same cure: [ScreenFragment.showModal] runs the
     * present hook that pauses the pen. With no resumed screen there is no pen to pause.
     */
    private fun showShareModal(dialog: androidx.appcompat.app.AlertDialog) {
        val screen = resumedScreenFragment()
        if (screen != null) screen.showModal(dialog) else dialog.show()
    }

    /**
     * ⏳ Later — the local Instapaper, and it NEVER ASKS A QUESTION. One tap saves silently:
     * the lane is derived from the URL by the same table every other filing path reads
     * (youtube/vimeo → watch, podcast hosts and audio files → listen, else read), so a saved
     * item already knows whether it reads, plays, or watches and the Later List badges it
     * accordingly. The save is [IntakePageStore.fileLink] — the one write path — so the link
     * is dressed with its og: face, cached for offline reading, published to the personal
     * feed, and merged across devices like every other filing. The toast names the lane it
     * landed in: the save stays verifiable without becoming a decision.
     */
    private fun saveSharedToLater(url: String, title: String?, excerpt: String?) {
        // The store half is shared with the send-from-any-app card (ShareActions, 2026-10-10).
        lifecycleScope.launch {
            val lane = com.toolsboox.ui.share.ShareActions.saveToLater(applicationContext, url, title, excerpt)
            android.widget.Toast.makeText(
                this@MainActivity, "⏳ Saved for later · $lane", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * A shared link arrived — offer to file it into the reading pipeline (read later /
     * watch / listen / educate), or drop it as a box on the day page. Filing appends it to
     * today's intake sidecar (so it shows in Notes & Annotations) and enqueues it.
     * The explicit-lane room: ⏳ Later infers the lane and never asks, so this chooser is
     * reached only from its own "File to a lane…" row — for the shares where the lane IS the
     * point (an article he wants under 📧 Email, a video he'll read the transcript of).
     */
    private fun offerToFileLink(url: String, title: String?, sharedText: String?) {
        // (iconRes, label, action). Monochrome outline icons for e-ink contrast.
        val fileAction: (String, String) -> Unit = { kind, label ->
            com.toolsboox.ui.share.ShareActions.fileToLane(applicationContext, kind, url, title)
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
            showShareModal(dialog)
        }
    }

    /** Drop shared text as a movable box on today's day page (the pre-existing behavior). */
    private fun dropTextOnDay(boxText: String, url: String?) {
        val bundle = bundleOf("sharedText" to boxText)
        if (url != null) {
            bundle.putString("sharedUrl", url)
            bundle.putString("notePage", "intake")
        }
        // Replace the day page only when it's the screen you're on — the chooser now runs in your
        // live session (⋯ More), so Back must still reach the book you left (2026-10-10).
        binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundle, shareDayNavOptions())
    }

    /** One row of the share fork. [big] marks the fast default — ⏳ Later — which reads first
     *  and largest so it's reachable without reading the rest. */
    private data class ShareRow(val iconRes: Int, val label: String, val big: Boolean = false, val action: () -> Unit)

    /**
     * THE FORK CHROME — sharing into Ledgable, any type, lands on this ONE chooser, and the two
     * lanes fork here in our own chrome rather than in the system sheet (which stays one entry:
     * "Ledgable"). ⏳ Later is the top row, big, one tap, no questions — the local Instapaper,
     * for article AND video AND audio links alike. Below it, the gram lane (the iOS share-in's
     * richness: place on this page, to the remembered board, or anywhere in the destination
     * vocabulary), then the existing text/link behaviors as their own rows. This reorganizes the
     * front door; it deletes no rooms.
     */
    private fun offerShareDestinations(
        url: String?, title: String?, leftoverText: String?, sharedText: String?, imageUri: android.net.Uri?,
        via: String? = null,
    ) {
        // The one-tap gram row goes where the LAST gram went (Gram Picks when the remembered
        // place no longer exists today); the "Place as pick…" row below it opens the whole
        // destination vocabulary for the shares where the place is the point. Every explicit
        // chooser in the app teaches that memory.
        val dest = com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(this)
        val preview = (title ?: url ?: leftoverText ?: sharedText)?.trim().orEmpty()
        val items = mutableListOf<ShareRow>()

        // THE ORDER IS THE WANT (Michael, 09-13: "I never really want to put something on today
        // when I add — I tend to want to make a pick or a note page… or save for later"; of a
        // picture: "save it as a task, save it on to a new note page, make a task, connect to
        // gram"). So: Later · the pick place · a fresh note page · a task card, and today's page
        // LAST, for the share that really is about today. iOS 198 wears the same order.
        if (imageUri != null) {
            items += ShareRow(R.drawable.ic_quote, "◈  To ${dest.name}") {
                val bmp = decodeSharedImage(imageUri)
                if (bmp != null) placeGram(bmp, dest, "", "Shared image", openDay = false)
                else android.widget.Toast.makeText(this, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT).show()
            }
            items += ShareRow(R.drawable.ic_edit, "✎  On a new note page") {
                val bmp = decodeSharedImage(imageUri)
                if (bmp != null) placeOnNewNotePage(bmp, "", "Shared image")
                else android.widget.Toast.makeText(this, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT).show()
            }
            items += ShareRow(R.drawable.ic_bookmark, "☐  Task card") {
                val bmp = decodeSharedImage(imageUri)
                if (bmp != null) pinBitmapAsTask(bmp, "Shared image")
                else android.widget.Toast.makeText(this, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT).show()
            }
            // The page-choice richness the iOS share has: the full destination vocabulary —
            // Gram Picks, today's notes (the day's freeform sheet), any Pickings board, the All
            // Stars bands, Synthesize — remembered-last-first, through the one funnel.
            items += ShareRow(R.drawable.ic_quote, "Place as pick…") {
                com.toolsboox.plugin.calendar.ot.GramDestinations.choose(
                    this, title = "Place this image on",
                    fragment = resumedScreenFragment()
                ) { d ->
                    val bmp = decodeSharedImage(imageUri)
                    if (bmp != null) placeGram(bmp, d, "", "Shared image", openDay = false)
                    else android.widget.Toast.makeText(this, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            items += ShareRow(R.drawable.ic_image, "On this page") { openSharedImageOnDay(imageUri) }
        } else if (url != null) {
            val t = title ?: url
            // What the sharing app sent BESIDES the link and its title is the link's own blurb —
            // that's the excerpt band on the card. (ShareTextParser already pulled the url and
            // title out of the shared text; what's left is the description the app offered.)
            val blurb = leftoverText?.trim().orEmpty()
            // ⏳ Later leads: the fast default, one silent tap, lane derived from the URL.
            items += ShareRow(R.drawable.ic_bookmark, "⏳  Later", big = true) { saveSharedToLater(url, title, blurb) }
            items += ShareRow(R.drawable.ic_quote, "◈  To ${dest.name}") { renderAndPlaceLink(url, t, blurb, dest, openDay = false) }
            items += ShareRow(R.drawable.ic_edit, "✎  On a new note page") {
                lifecycleScope.launch { renderLinkCard(url, t, blurb)?.let { placeOnNewNotePage(it, url, t) } }
            }
            items += ShareRow(R.drawable.ic_bookmark, "☐  Task card") {
                lifecycleScope.launch { renderLinkCard(url, t, blurb)?.let { pinBitmapAsTask(it, t) } }
            }
            items += ShareRow(R.drawable.ic_quote, "Place as pick…") {
                com.toolsboox.plugin.calendar.ot.GramDestinations.choose(
                    this, title = "Place this card on",
                    fragment = resumedScreenFragment()
                ) { d -> renderAndPlaceLink(url, t, blurb, d, openDay = false) }
            }
            // The explicit-lane room ⏳ Later replaced as the default — still one row away for
            // the shares where the lane is the point (see offerToFileLink).
            items += ShareRow(R.drawable.ic_book, "File to a lane…") { offerToFileLink(url, title, sharedText) }
            items += ShareRow(R.drawable.ic_edit, "As text") {
                val boxText = wrapForTextBox(listOfNotNull(title, url).joinToString("\n").ifBlank { sharedText?.trim().orEmpty() })
                if (boxText.isNotBlank()) dropTextOnDay(boxText, url)
            }
            items += ShareRow(R.drawable.ic_reader_view, "On this page") { renderAndPlaceLink(url, t, blurb, null, openDay = true) }
        } else {
            // THE PLAIN-TEXT CARD, FIXED (2026-10-10): it was the passage cut at 600 characters with
            // no word of it, no date, no source, and no cardText — the words existed only as pixels,
            // so search, Synthesize and the iPad couldn't read them. Now it is the in-app ❝ Save
            // highlight face: the whole passage (a visible "…" if the face must shorten), the
            // "Oct 10, 2026 · via EinkBro" footer, and every word in cardText. The text BOX keeps
            // its soft wrap; the card gets the unwrapped passage.
            val plain = listOfNotNull(title, leftoverText).joinToString("\n").ifBlank { sharedText?.trim().orEmpty() }
            val text = wrapForTextBox(plain)
            if (plain.isBlank()) return
            val footer = com.toolsboox.ui.share.ShareActions.footer(null, via)
            val label = com.toolsboox.ui.share.ShareActions.sourceLabel(null, via, "Shared note")
            val feed = via.orEmpty()
            suspend fun stickyCard() = com.toolsboox.ui.share.ShareActions.textCard(plain, footer)
            items += ShareRow(R.drawable.ic_quote, "◈  To ${dest.name}") {
                lifecycleScope.launch { stickyCard()?.let { placeGram(it, dest, "", label, openDay = false, cardText = plain, sourceFeed = feed) } }
            }
            items += ShareRow(R.drawable.ic_edit, "✎  On a new note page") {
                lifecycleScope.launch {
                    stickyCard()?.let {
                        placeOnNewNotePage(it, "", label, cardText = plain, sourceFeed = feed,
                            noteName = plain.lineSequence().firstOrNull()?.take(48))
                    }
                }
            }
            items += ShareRow(R.drawable.ic_bookmark, "☐  Task card") {
                lifecycleScope.launch { stickyCard()?.let { pinBitmapAsTask(it, plain.lineSequence().firstOrNull()?.take(80) ?: "Shared note") } }
            }
            items += ShareRow(R.drawable.ic_edit, "On this page (text)") { dropTextOnDay(text, null) }
        }

        // Defer to after the first layout — a dialog straight from onResume on a share cold-start can
        // be swallowed before the window is ready. (Same pattern as offerToFileLink.)
        binding.fragmentContent.post {
            val dp = resources.displayMetrics.density
            val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this))
                .setTitle("Add to Ledgable")
                .setView(androidx.core.widget.NestedScrollView(this).apply { addView(list) })
                .create()
            if (preview.isNotBlank()) list.addView(android.widget.TextView(this).apply {
                text = preview.take(140)
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
                setTextColor(0xFF666666.toInt()); textSize = 13f
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            for (row in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<android.widget.ImageView>(R.id.go_icon).apply { setImageResource(row.iconRes); visibility = android.view.View.VISIBLE }
                r.findViewById<android.widget.TextView>(R.id.go_label).apply {
                    text = row.label
                    // The fast default earns its size: readable without reading, per the canon.
                    if (row.big) { textSize = 21f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
                }
                r.setOnClickListener { dialog.dismiss(); row.action() }
                list.addView(r)
            }
            showShareModal(dialog)
        }
    }

    /** Render a shared link into a card and place it as a gram (with provenance) on [destination]
     *  — null means the day page itself (the "On this page" row). */
    private fun renderAndPlaceLink(
        url: String, title: String, excerpt: String,
        destination: com.toolsboox.plugin.calendar.ot.GramDestinations.Destination?, openDay: Boolean
    ) {
        lifecycleScope.launch {
            // The kind was hard-coded "read", so a shared YouTube link wore 🔖 READ and landed in
            // the wrong Intake quarter — ShareActions.renderLinkCard infers it from the host, the
            // iPad's FeedNoteGram.kind(for:) table.
            val card = com.toolsboox.ui.share.ShareActions.renderLinkCard(url, title, excerpt)
            if (card != null) placeGram(card, destination, url, title, openDay)
            else android.widget.Toast.makeText(this@MainActivity, "Couldn't make a card", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** The shared link's card face — the same renderer the pick doors use, kind inferred from the host. */
    private suspend fun renderLinkCard(url: String, title: String, excerpt: String): android.graphics.Bitmap? {
        val card = com.toolsboox.ui.share.ShareActions.renderLinkCard(url, title, excerpt)
        if (card == null) android.widget.Toast.makeText(this, "Couldn't make a card", android.widget.Toast.LENGTH_SHORT).show()
        return card
    }

    /**
     * A FRESH grid note page under today with [bitmap] placed on it as a pick, then the page opened
     * (Michael, 09-13: "save it on to a new note page"). The same start-a-note + place + open walk
     * the Ask page's cited grams take (LedgerDirectory.placeCitedSiteGramsOntoNewNote).
     */
    private fun placeOnNewNotePage(
        bitmap: android.graphics.Bitmap, sourceLink: String, sourceLabel: String,
        cardText: String = "", sourceFeed: String = "", noteName: String? = null,
    ) {
        lifecycleScope.launch {
            // The store half is the card's too (ShareActions); opening the page stays here.
            val note = com.toolsboox.ui.share.ShareActions.placeOnNewNotePage(
                applicationContext, bitmap, sourceLink, sourceLabel,
                cardText = cardText, sourceFeed = sourceFeed, service = calendarDayService, noteName = noteName)
            if (note == null) {
                android.widget.Toast.makeText(this@MainActivity, "Couldn't start a note page", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val f = resumedScreenFragment()
            if (f != null) com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(f, note.date, note.key)
            else android.widget.Toast.makeText(this@MainActivity, "✎ On a new note page · ${note.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * [bitmap] as a task card's ink face — unfiled, today (Michael, 09-13: "save it as a task").
     * The day page's "Pin to board" door, minus the board question: same LedgerItem shape, same
     * day-lock discipline, same push to the task sync.
     */
    private fun pinBitmapAsTask(bitmap: android.graphics.Bitmap, label: String) {
        lifecycleScope.launch {
            val saved = com.toolsboox.ui.share.ShareActions.pinBitmapAsTask(
                applicationContext, bitmap, label, calendarDayService)
            if (saved) tellSurfaceGramPlaced("default")
            android.widget.Toast.makeText(this@MainActivity,
                if (saved) "☐ Saved as a task card" else "Couldn't save that", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Place [bitmap] as a gram (with provenance) on [destination] (null = the day page itself);
     *  reload the day when it landed there. */
    private fun placeGram(
        bitmap: android.graphics.Bitmap,
        destination: com.toolsboox.plugin.calendar.ot.GramDestinations.Destination?,
        sourceLink: String, sourceLabel: String, openDay: Boolean,
        cardText: String = "", sourceFeed: String = "",
    ) {
        val pageKey = destination?.key ?: "default"
        lifecycleScope.launch {
            val placed = com.toolsboox.ui.share.ShareActions.placeGram(
                applicationContext, bitmap, destination, sourceLink, sourceLabel,
                cardText = cardText, sourceFeed = sourceFeed, service = calendarDayService)
            // Only the STAY branch needs telling — `openDay` navigates, and arriving at the day page
            // loads the day fresh. A share that stays put drops the caller back onto whatever screen
            // it interrupted, holding a day that predates the card by a second.
            if (placed && !openDay) tellSurfaceGramPlaced(pageKey)
            if (openDay) {
                runCatching { binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, null, shareDayNavOptions()) }
            } else {
                // One vocabulary with the card and iOS: "◈ Added to All Picks" (2026-10-10).
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (!placed) "Couldn't save that"
                    else destination?.let { "◈ Added to ${it.name}" } ?: "Added to the page",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Land a shared PDF on the bookshelf, wherever the shelf currently is (BookshelfSource
     * follows a declared folder). Streamed, temp-then-rename inside writeInto — a half-copied
     * PDF must never sit on the shelf looking like a book. Named by the sender's display name,
     * with the extension pinned so the shelf (which lists by extension) can see it.
     */
    private fun importSharedPdf(uri: android.net.Uri) {
        lifecycleScope.launch {
            // Streamed temp-then-rename and pushed on add — ShareActions.importPdf, shared with the card.
            val name = com.toolsboox.ui.share.ShareActions.importPdf(this@MainActivity, uri)
            android.widget.Toast.makeText(
                this@MainActivity,
                if (name != null) "On the shelf · $name" else "Couldn't import that PDF",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** The pre-existing "shared image onto today's page" behavior — a movable image element. */
    private fun openSharedImageOnDay(imageUri: android.net.Uri) {
        val bundle = bundleOf("sharedImageUri" to imageUri.toString())
        binding.fragmentContent.findNavController().navigate(R.id.action_to_calendar_day, bundle, shareDayNavOptions())
    }

    /** A shared image, decoded downsampled — never full-size (see [com.toolsboox.ui.share.ShareActions.decodeImage]). */
    private fun decodeSharedImage(uri: android.net.Uri): android.graphics.Bitmap? =
        com.toolsboox.ui.share.ShareActions.decodeImage(this, uri)

    /**
     * Activity onResume.
     */
    override fun onResume() {
        super.onResume()

        // A row on the send-from-any-app card that opens Ledgable (✎ New note page…, 🔎 Ask…, On
        // this page…) — routed BEFORE the fragments resume, so the screen you were last on never
        // re-attaches its ink only to be replaced (critique P2; a cold start routes in onCreate).
        if (!staleLaunchIntent) routeCardHandoff(warm = true)

        // Text Notes written from the card, announced to a resumed Text Notes screen so its next
        // autosave can't un-write them. Posted: the fragments resume after this method returns.
        com.toolsboox.ui.share.ShareNoteQueue.onEnqueued = { runOnUiThread { drainSharedNotes() } }
        binding.root.post { drainSharedNotes() }
        // And picks / task cards the card writes into the day file while we stay RESUMED beside
        // the sender (Boox split screen, a floating window): re-read, never save (2026-10-10).
        com.toolsboox.ui.share.ShareActions.onDayWritten = { key -> runOnUiThread { tellSurfaceGramPlaced(key) } }

        // ❖ The summon that outlived its process (Michael, 2026-10-12, B6 -322): say what a summon
        // that finished in the background still owes, and re-ask / land any summon a killed,
        // rebooted or reinstalled process left mid-flight. Posted, so the day page resuming under
        // us is the live page a landing finds.
        com.toolsboox.plugin.calendar.ui.SynthSummons.foreground = true
        binding.root.post { runCatching { com.toolsboox.plugin.calendar.ui.SynthSummons.onForeground(this) } }

        // The app end of the widget ✓ write-channel: drain whatever queued while we were away,
        // and stay registered for taps that land while we're up. Registered here (not onCreate)
        // and cleared in onPause so the nudge only ever fires into a resumed activity.
        com.toolsboox.plugin.calendar.ot.TaskDoneQueue.onEnqueued = { drainWidgetDoneQueue() }
        drainWidgetDoneQueue()

        // Open-with-Ledgable (09-03): a mailto: link from anywhere opens the composer with the
        // address standing; a web link opens the Web room on that page. Consumed like the share
        // intents so a re-resume doesn't re-navigate.
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val data = intent?.data
            when {
                // A pairing string from ledgable.cc/pair: the site lands in Settings › Sites, active,
                // password and all. Consumed like the others so a re-resume doesn't re-pair.
                com.toolsboox.plugin.calendar.nw.PairLink.isPair(data) -> {
                    intent?.action = null
                    val added = com.toolsboox.plugin.calendar.nw.PairLink.apply(this, data.toString())
                    android.widget.Toast.makeText(this,
                        if (added != null) "Site added: $added — it's now active under Settings › Sites"
                        else "That pairing string didn't work — check it and try again",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                data?.scheme == "mailto" -> {
                    intent?.action = null
                    val nav = (supportFragmentManager.findFragmentById(R.id.fragmentContent)
                        as? androidx.navigation.fragment.NavHostFragment)?.navController
                    nav?.navigate(R.id.action_to_mail_compose, androidx.core.os.bundleOf(
                        com.toolsboox.plugin.mail.ui.MailComposeFragment.ARG_TO_EMAIL
                            to data.schemeSpecificPart.substringBefore('?')))
                }
                data?.scheme == "http" || data?.scheme == "https" -> {
                    // Not the app-link host (toolsboox deep links keep their own route below).
                    if (data?.host != "app.toolsboox.com") {
                        intent?.action = null
                        getSharedPreferences("browser_room", 0).edit()
                            .putString("last_url", data.toString()).apply()
                        val nav = (supportFragmentManager.findFragmentById(R.id.fragmentContent)
                            as? androidx.navigation.fragment.NavHostFragment)?.navController
                        nav?.navigate(R.id.action_to_site_web, androidx.core.os.bundleOf(
                            com.toolsboox.plugin.calendar.ui.SiteWebFragment.ARG_OPEN_WEB to true))
                    }
                }
            }
        }

        // Share-to-Ledger (text/link): a chooser offers where it lands — a GRAM (a card with the
        // source clickable) on this page or in Pickings, filed to read/watch/listen, or as a text box.
        // A SEND that didn't come from the card — EinkBro's remembered last target names THIS
        // component (Android 12 and below; 13+ drops it before delivery) — goes to the card
        // instead (C8), so it never opens a second Ledgable inside the sender. ⋯ More
        // (EXTRA_FROM_CARD, unwrapped first) and Ledgable's own shares fall through to the arms.
        unwrapCardSend()
        val arrivedWarm = intentArrivedWarm.also { intentArrivedWarm = false }
        if (!staleLaunchIntent && intent?.action == android.content.Intent.ACTION_SEND && forwardForeignShare(warm = arrivedWarm)) intent?.action = null
        val shareVia = intent?.getStringExtra(com.toolsboox.ui.share.ShareActions.EXTRA_VIA)

        if (intent?.action == android.content.Intent.ACTION_SEND && intent?.type == "text/plain") {
            val sharedText = intent?.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
            val sharedSubject = intent?.getCharSequenceExtra(android.content.Intent.EXTRA_SUBJECT)?.toString()
            // Consume the intent so re-resume doesn't re-navigate.
            intent?.action = null

            val parsed = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.parse(sharedText, sharedSubject)
            Timber.i("Share to ledger (text): url=${parsed.url}")
            offerShareDestinations(parsed.url, parsed.title, parsed.leftoverText, sharedText, null, shareVia)
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
                offerShareDestinations(null, null, null, null, streamUri, shareVia)
            }
        }

        // Share-to-Ledger target: a PDF lands on the BOOKSHELF — the shelf already reads PDFs
        // (READABLE lists the extension); the share sheet just had no door for one. Same
        // consume-the-intent contract as the two share arms above.
        if (intent?.action == android.content.Intent.ACTION_SEND && intent?.type == "application/pdf") {
            @Suppress("DEPRECATION")
            val streamUri = intent?.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            intent?.action = null

            if (streamUri != null) {
                Timber.i("Share to ledger (pdf): $streamUri")
                importSharedPdf(streamUri)
            }
        }

        val host = intent?.data?.host
        val path = intent?.data?.path
        if (host == "app") {
            if (path?.startsWith("/calendar") == true) {
                // The day page, always. This used to read a "Start view" dropdown on Settings
                // whose default was the day page and which the directory, the sidebar and every
                // other door ignored — a setting that governed only this deep link and the home
                // tile. The row retired (settings IA, 09-10); the default it had is now the rule.
                val defaultCalendarStartActionId = R.id.action_to_calendar_day

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
                // CONSUMED, like every sibling arm here. It never was: a session opened from a
                // `…/app/calendar` link re-ran this on EVERY onResume — and the B6 recreates the
                // activity on a flip — so each rotation navigated to today's day page with an
                // empty bundle over whatever page you were on (09-07: "whenever I rotate … it
                // flashes the today page").
                intent?.data = null
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
                    // THE DOORS WIDGET (09-13): seven rooms by name, each the drawer's own move.
                    // 📧 The Mail's tile opens The Mail's own room (Michael 2026-10-11) at your
                    // Unread / Read / All, on the mailbox you were in — one tap, as before.
                    "door_mail" -> com.toolsboox.plugin.feeds.ui.openMailRoom(nav, mode = "feed")
                    "door_read", "door_watch", "door_listen" -> {
                        com.toolsboox.plugin.feeds.ui.FeedSelection.filterFeedTitle = null
                        com.toolsboox.plugin.feeds.ui.FeedSelection.mode = "feed"
                        com.toolsboox.plugin.feeds.ui.FeedSelection.kind = dest.removePrefix("door_")
                        nav.navigate(R.id.action_to_feeds)
                    }
                    "door_continue" -> {
                        // The article mid-flight (the media log's newest READ not done), reopened in
                        // the pane — the hub's ⏯ Continue row, from the home screen.
                        val e = runCatching {
                            com.toolsboox.plugin.calendar.ot.MediaLogStore.list(this, com.toolsboox.plugin.calendar.da.v2.MediaLogEntry.READ)
                                .firstOrNull { !it.done && it.title.isNotBlank() }
                        }.getOrNull()
                        if (e != null) {
                            val known = runCatching { com.toolsboox.plugin.feeds.nw.FeedCache.loadFreshestEntries(this) }.getOrDefault(emptyList())
                            val entry = known.firstOrNull { it.id == e.feedId && e.feedId != 0L }
                                ?: known.firstOrNull { it.url.isNotBlank() && it.url == e.url }
                                ?: com.toolsboox.plugin.feeds.da.FeedEntry(
                                    id = e.feedId, title = e.title, feedTitle = e.source, url = e.url, author = null,
                                    content = runCatching { com.toolsboox.plugin.feeds.nw.FeedCache.loadContent(this, e.feedId) }.getOrNull().orEmpty(),
                                    publishedAt = "", starred = false, read = true)
                            com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry = entry
                            com.toolsboox.plugin.feeds.ui.FeedSelection.mode = "feed"
                            com.toolsboox.plugin.feeds.ui.FeedSelection.kind = entry.kind
                        }
                        nav.navigate(R.id.action_to_feeds)
                    }
                    "door_notes", "door_newnote" -> {
                        // The day page is the start destination; once it stands, the Notebook (or
                        // the new-note picker) opens over it — the hub's own rows.
                        binding.root.postDelayed({
                            val host = supportFragmentManager.findFragmentById(R.id.fragmentContent) as? androidx.navigation.fragment.NavHostFragment
                            val frag = host?.childFragmentManager?.primaryNavigationFragment as? com.toolsboox.ui.plugin.ScreenFragment ?: return@postDelayed
                            if (dest == "door_notes") com.toolsboox.plugin.feeds.ui.showNotebook(frag)
                            else com.toolsboox.plugin.feeds.ui.showNewNotePicker(frag)
                        }, 500)
                    }
                    // The Mail widget's face is the kept pile, so its tap lands in The Mail's own
                    // room (Michael 2026-10-11) on ⭐ Starred — where it always landed.
                    "mail" -> {
                        // The face is the kept pile across EVERY account, so the tap says so — a
                        // bare null means "the mailbox you were in" (2026-10-11 review).
                        val mailbox = com.toolsboox.plugin.feeds.ui.MailPlace.ALL_ACCOUNTS
                        // A Mail List widget ROW names the letter it shows (mailOpenId, via the
                        // collection's fill-in intent); the header — and the bitmap Mail widget —
                        // name none and land on the lens as before. Rides the SAME one-shot
                        // handoff the gram router's mail:// links set: the pane consumes
                        // pendingMailOpenId after its warm load, and an id the store no longer
                        // holds falls through to the plain lens rather than erroring. Consumed
                        // like widgetDest itself, so a re-resume doesn't re-open it.
                        val mailId = intent?.getStringExtra("mailOpenId")
                        intent?.removeExtra("mailOpenId")
                        com.toolsboox.plugin.feeds.ui.openMailRoom(
                            nav, mailbox = mailbox, mode = "stars", letterId = mailId?.takeIf { it.isNotBlank() })
                    }
                    // A Task Checklist widget ROW opens the day page that owns its task — a
                    // lookback row lands on the day it was written, not today. The date rides
                    // widgetDay (ISO), set by TaskDoneReceiver's open arm; unparseable or
                    // missing falls back to today, which is the day the header already opens.
                    "day" -> {
                        val d = runCatching {
                            java.time.LocalDate.parse(intent?.getStringExtra("widgetDay") ?: "")
                        }.getOrNull() ?: java.time.LocalDate.now()
                        intent?.removeExtra("widgetDay")
                        nav.navigate(R.id.action_to_calendar_day, bundleOf(
                            "year" to "${d.year}", "month" to "${d.monthValue}",
                            "day" to "${d.dayOfMonth}"))
                    }
                    "feeds" -> {
                        // A Feed List widget ROW names the entry it shows (feedEntryId, via the
                        // collection's fill-in intent); the header — and the old bitmap Feed
                        // widget — name none and land on the surface as before. The lookup runs
                        // against the SAME freshest cached list the widget's factory rendered
                        // from, so the row tapped is the row opened; an id that has since left
                        // the cache falls through to the plain surface rather than erroring.
                        // Consumed like widgetDest itself, so a re-resume doesn't re-open it.
                        val entryId = intent?.getLongExtra("feedEntryId", 0L) ?: 0L
                        intent?.removeExtra("feedEntryId")
                        if (entryId != 0L) runCatching {
                            val cached = com.toolsboox.plugin.feeds.nw.FeedCache.loadFreshestEntries(this)
                            cached.firstOrNull { it.id == entryId }?.let { e ->
                                com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry = e
                                com.toolsboox.plugin.feeds.ui.FeedSelection.list = cached
                            }
                        }
                        nav.navigate(R.id.action_to_feeds)
                    }
                    // The door widgets: straight to the surface, no arguments to carry.
                    "publish" -> nav.navigate(R.id.action_to_publish)
                    "roster" -> nav.navigate(R.id.action_to_roster)
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
