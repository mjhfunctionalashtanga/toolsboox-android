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
            tier(context, "modal_text_size")

        /**
         * The MENU text scale — directories, accordions, icon menus, context menus.
         *
         * Separate from the modal size because they are different things to read. A menu is a
         * list of destinations you scan and hit; a dialog is a sentence you read and answer. One
         * dial for both meant sizing a menu for your thumb and getting shouting dialogs with it.
         */
        fun menuTextScale(context: android.content.Context): Float =
            tier(context, "menu_text_size")

        private fun tier(context: android.content.Context, key: String): Float =
            when (context.getSharedPreferences("ledger_a11y", 0).getString(key, "medium")) {
                "small" -> 0.85f
                "large" -> 1.25f
                else -> 1f
            }

        // The modal's own measurements, in the same units as the layouts they came from, so the
        // scale above moves the BOX as well as the type. Text that grows inside a fixed box only
        // ellipsizes, which reads as the setting doing nothing.
        /** The pill shape you last chose, used as the default for a surface that has none yet. */
        private const val PILL_LAST_VERTICAL = "pill_last_vertical"

        /**
         * PUT THEM ALL AWAY. The master switch over every floating pill on every surface.
         *
         * Each pill already folds on its own — tap the handle and it walks the four states — but
         * folding leaves the handle behind, which is the point of folding and exactly wrong when
         * the complaint is not about any single pill. Michael, 07-30: "hide pill leaves the
         * pageskipper. It should hide." The tool capsule and the paging pill are one piece of
         * furniture to him, and a switch that takes away one of the two is a switch that doesn't
         * work.
         *
         * So this is over ALL of them, in the one seam every pill already passes through
         * ([makeDraggable]) rather than remembered per-surface. It costs nothing to hide them:
         * the wrench's rows, the ▦ hub and the almanac strip all still reach everything the pills
         * do. Kept in `ledger_widgets` beside the per-pill `_collapsed`/`_vertical` keys, which
         * stay untouched — turning the pills back on restores whatever shape each one was left in
         * rather than flattening them all.
         *
         * The twin of iOS's `DaySkipperChrome.pillsHiddenKey`.
         */
        const val PILLS_HIDDEN = "pills_hidden"

        /** The master switch's state, for callers that have a context but no fragment (Settings). */
        fun pillsHidden(context: android.content.Context): Boolean =
            context.getSharedPreferences("ledger_widgets", 0).getBoolean(PILLS_HIDDEN, false)

        private const val TITLE_SP = 20f        // dialog_go_to's go_to_title
        private const val ROW_SP = 16f          // item_go_to's go_label
        private const val ICON_DP = 22f         // item_go_to's go_icon, square
        private const val ICON_MENU_DP = 340f   // showIconMenu's card
        private const val GO_MODAL_DP = 170f    // showGoModal's narrower card
        /**
         * showAccordion's left drawer. 320 made "🔬 Synthesize" and its neighbours wrap to a
         * second line on the tablets — Michael: "the hamburger menu is too narrow by default."
         * The width a row actually needs, added up from item_go_to and the accordion's own
         * chrome: 14+14 row padding + 22 icon + 18 label margin + up to 48 of sub-fold indent
         * ≈ 116, plus ≈ 44 for the caret target a door-with-children header wears on the right,
         * and ≈ 200 for the longest shipping label at 16sp with its enlarged leading glyph
         * ("☑ Tasks & Events", "✧ Missed Rhizomes") — ≈ 360 all told, padded to 400 so a label
         * one word longer doesn't reopen this. The 66%-of-screen cap below still governs narrow
         * devices (the Palma), where wrapping is the acceptable trade.
         */
        private const val ACCORDION_DP = 400f

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
        val handler: (Boolean) -> Boolean = { up -> onVolumeKey(up) }
        registeredVolumeKeyHandler = handler
        (activity as? MainActivity)?.volumeKeyHandler = handler
        // The switch may have been thrown in Settings while this surface sat in the back stack —
        // Settings can't reach into a fragment's views, so the fragment asks on the way back in.
        applyPillsHidden()
    }

    /**
     * Every floating pill this surface has wired, so the master switch has something to act on.
     *
     * Rebuilt with the view: a fragment that comes back off the back stack inflates fresh views,
     * and holding the dead ones would mean toggling the pills wrote visibility into a tree nobody
     * is looking at while the live pills stayed as they were.
     */
    private val floatingPills = mutableListOf<View>()

    override fun onDestroyView() {
        floatingPills.clear()
        super.onDestroyView()
    }

    /** The master switch's state on this surface. */
    protected fun pillsHidden(): Boolean = pillsHidden(requireContext())

    /** Show or hide every floating pill on this surface, per the master switch. */
    protected fun applyPillsHidden() {
        val hidden = pillsHidden()
        for (pill in floatingPills) pill.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    /**
     * Throw the master switch and act on it now.
     *
     * Offered from the wrench modals, which is where the Boox has always kept this kind of dial —
     * and mirrored in Settings → Legibility, because the wrench on some surfaces lives ON a pill,
     * and a control you can only reach through the thing you just hid is no way back at all.
     */
    protected fun togglePillsHidden() {
        val next = !pillsHidden()
        requireContext().getSharedPreferences("ledger_widgets", 0)
            .edit().putBoolean(PILLS_HIDDEN, next).apply()
        applyPillsHidden()
        showMessage(
            if (next) "Floating pills hidden — Settings → Legibility brings them back"
            else "Floating pills shown",
            null
        )
    }

    override fun onPause() {
        super.onPause()
        // Only clear OUR OWN registration. With reordered transactions (Navigation always uses
        // them) the INCOMING fragment's onResume runs before the outgoing one's onPause —
        // FragmentStore.moveToExpectedState walks mAdded (the new fragment, up to RESUMED) before
        // it tears down removed fragments. So this onPause runs after the next screen has already
        // registered, and unconditionally nulling here wiped the handler that screen just set:
        // the keys were dead on arrival on every surface that relied on this base registration
        // (Roots, Sprouts, the garden lists) and only revived after an activity-level pause/resume.
        val main = activity as? MainActivity
        if (main != null && registeredVolumeKeyHandler != null &&
            main.volumeKeyHandler === registeredVolumeKeyHandler
        ) main.volumeKeyHandler = null
        registeredVolumeKeyHandler = null
    }

    /** The lambda THIS fragment registered, so onPause can tell its own registration from a successor's. */
    private var registeredVolumeKeyHandler: ((Boolean) -> Boolean)? = null

    /**
     * Entry point for [MainActivity]'s fallback: when no handler is registered at all (a screen
     * with its own direct registration nulled the seam on the way out), the activity asks the
     * fragment on screen to page itself. Guarded on [isResumed] so a mid-transaction key can
     * never page a screen that is leaving.
     */
    /**
     * Something OTHER than this fragment just wrote a gram into the day file.
     *
     * A placement from the in-pane feed reader goes straight to disk under the day lock, while this
     * fragment is still RESUMED holding its own copy of the day — so the card is saved and invisible,
     * and the next pen-up save writes the stale copy back over it. Michael: "I saved an item from the
     * feed with a written note and it said it was added to today's note and it is not there." It was
     * there; it just wasn't in the copy on screen, and then it wasn't anywhere.
     *
     * The standalone reader never showed this because leaving and returning reloads the day.
     *
     * REMOVAL IS THE SAME EVENT, and is told the same way rather than through a hook of its own.
     * Deleting a document ([com.toolsboox.plugin.calendar.ot.LedgerDocumentPages.erase]) rewrites the
     * day file under a surface that may be showing one of those very pages — and the failure is
     * worse in that direction, because the stale copy still holds the ink that was just deleted and
     * the next pen-up save would put every stroke of it back. What the surface has to do is
     * identical: re-read, never save. A third near-identical hook would only be a third place for
     * that rule to be forgotten.
     */
    open fun onExternalGramPlaced(pageKey: String) {}

    /**
     * Something OTHER than this fragment just wrote a text note for [date].
     *
     * The same failure as [onExternalGramPlaced], one store over. Text Notes keeps the whole day's
     * note LIST in the fragment and writes all of it back on every autosave — so a note appended to
     * disk by the activity (the 📷 Capture → OCR path, which is reachable from every screen) is a
     * note the fragment has never heard of, and the next keystroke's 400ms autosave serialises the
     * list without it. The note is written, then un-written, and nothing says so.
     *
     * Same rule as its sibling: RE-READ, never blind-save.
     */
    open fun onExternalNoteAdded(date: java.time.LocalDate) {}

    internal fun dispatchVolumeKey(up: Boolean): Boolean = isResumed && onVolumeKey(up)

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
        if (root.visibility != View.VISIBLE) return null
        // The root ITSELF may be the scroller — a fragment whose layout is a RecyclerView or a
        // NestedScrollView at the top level. Only iterating children missed exactly those: a
        // LinearLayout inside a ScrollView reports it cannot scroll, because the ScrollView is
        // what scrolls, so the search returned nothing and the keys did nothing on those screens.
        if (root.canScrollVertically(1) || root.canScrollVertically(-1)) return root
        if (root !is ViewGroup) return null
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
     *
     * Every pill's movement runs through here — the drag, the clamp, the per-surface position
     * memory, the flip-rescale — one implementation so a pill moves the same way on every
     * surface that has one. (The Weeks popout can additionally DOCK to a chosen side and tuck
     * away, which is in some ways the better behavior; if the pills ever learn edge-docking,
     * this is the seam it lands in, so it arrives on all of them at once.)
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    protected fun makeDraggable(handle: View, pill: View, key: String, onTap: (() -> Unit)? = null) {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        // Every floating pill passes through here, so this is the one seam where the modal-size
        // dial reaches ALL of them — at Compact a vertical pill slims to margin width and can
        // park in a reading gutter without covering the text. (The grip is sized separately by
        // applyGripOrientation, which already scales.)
        applyPillSizing(pill, skip = handle)
        // …and the same seam is where the master hide switch reaches all of them. Registering the
        // pill here rather than at each call site is what makes [PILLS_HIDDEN] a promise about the
        // WHOLE screen: a pill added to some surface next month is covered the day it is written,
        // because being draggable and being hideable are the same membership.
        if (floatingPills.none { it === pill }) floatingPills.add(pill)
        pill.visibility = if (pillsHidden()) View.GONE else View.VISIBLE
        // Its drag is a move, never the left edge's swipe back (LeftEdgeCatcher; Michael 2026-10-12).
        pill.setTag(R.id.tag_edge_stand_aside, true)
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
        // Keep the pill through a screen flip. Rotation never recreates this fragment (the
        // manifest declares configChanges for orientation/screenSize), so the pill's dragged
        // translation simply survives — measured against the OLD screen. A bottom-anchored pill
        // mostly rides its anchor, but one dragged along the long axis (parked near the top of a
        // portrait page, say) lands past the new, shorter edge and is unreachable — which reads
        // as "the pill lost its position". Nothing re-clamped: the one-shot layout listener above
        // removes itself after the first real layout.
        //
        // On a parent resize, SCALE the translation to the new size and clamp. Scaling rather
        // than only clamping keeps the pill at the same RELATIVE spot — halfway up stays halfway
        // up — and makes portrait→landscape→portrait return it to where it started. Persisted,
        // so the stylus exclude rects and the next cold open agree with what's on screen.
        if (pill.getTag(R.id.tag_pill_rotation_listener) == null) {
            pill.setTag(R.id.tag_pill_rotation_listener, true)
            pill.post postParent@{
                val parent = pill.parent as? View ?: return@postParent
                var lastW = parent.width
                var lastH = parent.height
                parent.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                    val w = r - l
                    val h = b - t
                    if (w <= 0 || h <= 0 || lastW <= 0 || lastH <= 0) {
                        if (w > 0 && h > 0) { lastW = w; lastH = h }
                        return@addOnLayoutChangeListener
                    }
                    // Only a genuine FLIP rescales-and-persists. The parent also resizes a
                    // little when the system bars come and go (immersive enter/leave around
                    // onResume/onPause) — rescaling and saving on those near-1.0 ratios would
                    // compound a small drift into the stored position every session. A flip is
                    // the long axis changing sides; everything else just re-clamps, unsaved.
                    val flipped = (w < h) != (lastW < lastH)
                    if (flipped) {
                        pill.translationX = pill.translationX * w / lastW
                        pill.translationY = pill.translationY * h / lastH
                        // Clamp once the pill itself has re-laid-out inside the new parent —
                        // clampInParent reads pill.left/right, which are stale mid-pass.
                        pill.post {
                            clampInParent(pill, handle)
                            prefs.edit().putFloat("${key}_px", pill.translationX)
                                .putFloat("${key}_py", pill.translationY).apply()
                        }
                    } else if (w != lastW || h != lastH) {
                        pill.post { clampInParent(pill, handle) }
                    }
                    lastW = w
                    lastH = h
                }
            }
        }
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
     * Size a floating pill from the Modal-size dial — buttons, icon boxes and padding together.
     *
     * The dial already drove every floating MENU; the pills it summoned stayed at their XML size,
     * so Compact never made the one thing that permanently floats over reading surfaces any
     * smaller. Scaling the geometry here (not just text) is what lets the smallest step keep its
     * promise: a vertical pill at Compact is ~39-42dp wide — inside the 44dp article gutter and
     * the ~46dp book-reader margin on a Tab8 — instead of 49-53dp parked over the prose.
     *
     * Idempotent: each view's unscaled dims/padding are remembered in a tag the first time it's
     * seen, so re-applying sets absolute sizes rather than compounding (same trick as
     * [com.toolsboox.ot.ReadingSize.apply]).
     *
     * [skip] is the drag handle: its dims belong to [applyGripOrientation], which scales them
     * itself and would otherwise fight this over who sized the grip last.
     */
    protected fun applyPillSizing(pill: View, skip: View? = null) {
        val scale = com.toolsboox.ot.ModalScale.pillScale(requireContext())
        scaleChrome(pill, scale)
        if (pill is ViewGroup) {
            for (i in 0 until pill.childCount) {
                val c = pill.getChildAt(i)
                if (c !== skip) scaleChrome(c, scale)
            }
        }
        // Drawn smaller than 44dp → touched at 44-ish anyway: the pill's padding rim fans out to
        // the nearest button, so the whole slimmed strip stays a target even when the art does not.
        if (pill is LinearLayout) installPillTouchDelegate(pill, scale)
        pill.requestLayout()
    }

    /** Scale one view's fixed layout dims and padding, remembering the unscaled base in a tag. */
    private fun scaleChrome(v: View, scale: Float) {
        val base = (v.getTag(R.id.tag_base_chrome) as? IntArray) ?: intArrayOf(
            v.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            v.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom
        ).also { v.setTag(R.id.tag_base_chrome, it) }
        fun s(px: Int) = Math.round(px * scale)
        v.layoutParams?.let { lp ->
            // Only explicit sizes scale; WRAP_CONTENT/MATCH_PARENT (negative) pass through.
            if (base[0] > 0) lp.width = s(base[0])
            if (base[1] > 0) lp.height = s(base[1])
        }
        v.setPadding(s(base[2]), s(base[3]), s(base[4]), s(base[5]))
    }

    /**
     * Below 1.0 the buttons draw under 44dp, so each visible button's TOUCH rect is widened to
     * the pill's full cross-section (the padding rim included). Rebuilt on every layout, because
     * folding/turning the pill moves and hides children. At 1.0+ any delegate is removed.
     */
    private fun installPillTouchDelegate(pill: LinearLayout, scale: Float) {
        if (scale >= 1f) { pill.touchDelegate = null; return }
        if (pill.getTag(R.id.tag_pill_delegate) != null) return // listener already attached
        pill.setTag(R.id.tag_pill_delegate, true)
        pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val vertical = pill.orientation == LinearLayout.VERTICAL
            val delegates = ArrayList<android.view.TouchDelegate>()
            for (i in 0 until pill.childCount) {
                val c = pill.getChildAt(i)
                if (c.visibility != View.VISIBLE || !c.isClickable) continue
                val r = android.graphics.Rect(c.left, c.top, c.right, c.bottom)
                if (vertical) { r.left = 0; r.right = pill.width } else { r.top = 0; r.bottom = pill.height }
                delegates.add(android.view.TouchDelegate(r, c))
            }
            pill.touchDelegate = if (delegates.isEmpty()) null else FanOutTouchDelegate(delegates, pill)
        }
    }

    /** One TouchDelegate that tries several — only the one whose rect held the DOWN targets. */
    private class FanOutTouchDelegate(
        private val delegates: List<android.view.TouchDelegate>, anchor: View
    ) : android.view.TouchDelegate(android.graphics.Rect(), anchor) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            for (d in delegates) {
                val copy = MotionEvent.obtain(event)
                val handled = d.onTouchEvent(copy)
                copy.recycle()
                if (handled) return true
            }
            return false
        }
    }

    // (The shared almanac nav pill retired with the rail conversion — the almanac pages carry
    // their nav on the tucked rail now, built by SurfaceFragment.setupAlmanacRail.)

    // --- The tucked action rail on non-ink surfaces --------------------------------------------
    //
    // The rail idiom (see [com.toolsboox.ot.TuckPanel]) began on the ink pages, where it takes
    // over the upstream drawing toolbar's slot. A list/reader surface has no such slot, so its
    // layout provides an empty gutter instead: a ConstraintLayout column standing first (or
    // last) in a horizontal row whose other cell is the whole original page. The same in-flow
    // argument carries over — the page ends where the rail begins, nothing overlays content,
    // and the tucked strip is always reachable because it IS part of the layout.

    /** Which edge a rail docks on — upstream's `calendarToolbarSide`, the ONE side truth every
     *  rail in the app shares, so hopping the day page's rail hops them all. */
    protected fun railDockedRight(): Boolean =
        requireContext().getSharedPreferences("MAIN", 0)
            .getString("calendarToolbarSide", "LEFT") == "RIGHT"

    /**
     * Build a rail in a layout-provided [gutter] for a surface with no ink tools: More leads, the
     * surface's own [actions] follow (whatever its nav pill or header buttons did, unpacked into
     * icons), then the ⇄ edge-hop and the rail's own ✕. The ⇄ moves the gutter to the row's other
     * end and flips the shared side pref, so every rail in the app hops together.
     *
     * HUB OFF THE RAIL (Michael 2026-10-12: "Those too." → "Make sure we can get to hub with back
     * swipe in the reader or touch or something as needed" → "Awesome"): ☰ Hub led this rail; now
     * More does, and Hub is More's first row (☐ Tool labels under it) — and the left edge's Back
     * opens it ([installEdgeLadder], installed here). [hub] is the Hub's door — the shared Ledger
     * directory unless the surface has a richer hub of its own.
     *  - [ownMore]: the surface seats a More of its own among [actions] (The Mail's wrench), which
     *    carries Hub first — no second wrench here.
     *  - [keepHub]: a reader read OUTSIDE its list (the standalone article,
     *    PanelSeats.Flags.outsideList): ☰ Hub keeps its seat at the head — the left edge goes back
     *    to whatever opened the piece, never to a list — and no More is added under it.
     */
    protected fun setupActionRail(
        gutter: androidx.constraintlayout.widget.ConstraintLayout,
        surfaceKey: String,
        /** A provider, not a list: surfaces whose actions depend on state (the feed list vs an
         *  open article) re-ask it through [rebuildActionRail] and the rail re-dresses. */
        actions: () -> List<com.toolsboox.ot.TuckPanel.Item>,
        hub: (() -> Unit)? = null,
        ownMore: Boolean = false,
        keepHub: Boolean = false
    ): com.toolsboox.ot.TuckPanel {
        val rail = com.toolsboox.ot.TuckPanel(
            host = this,
            toolbar = null,
            surfaceKey = surfaceKey,
            sideIsLeft = { !railDockedRight() },
            gutter = gutter
        )
        // The gutter is authored on the row's start edge; a remembered RIGHT dock moves it
        // before first paint. LinearLayout order is the whole mechanism — no constraints to
        // rewrite, the weighted content cell simply takes whatever the rail leaves.
        fun dock() {
            val row = gutter.parent as? android.widget.LinearLayout ?: return
            val end = railDockedRight()
            row.removeView(gutter)
            row.addView(gutter, if (end) row.childCount else 0)
        }
        dock()
        fun rebuild() = rail.setClusters(
            top = when {
                keepHub -> listOf(com.toolsboox.ot.TuckPanel.Item(R.drawable.ic_bar_sections, "Hub") {
                    hub?.invoke() ?: openHub()
                })
                ownMore -> emptyList()
                // The shared More: ☰ Hub first, then ☐ Tool labels (the column's caption switch,
                // as every other panel's More carries it).
                else -> listOf(com.toolsboox.ot.PanelFaces.item(com.toolsboox.ot.PanelSeats.Seat.MORE, "More") {
                    showGoModal(withHub(listOf("Layout" to listOf(toolLabelsRow(rail))), hub),
                        anchorTop = true, anchorEnd = railDockedRight())
                })
            },
            bottom = actions() + com.toolsboox.ot.TuckPanel.Item(
                R.drawable.ic_toolbar_switch_side, "Other side"
            ) {
                val p = requireContext().getSharedPreferences("MAIN", 0)
                p.edit().putString("calendarToolbarSide", if (railDockedRight()) "LEFT" else "RIGHT").apply()
                dock()
                rail.applyState()
            }
        )
        rebuild()
        actionRailRebuilds[surfaceKey] = { rebuild() }
        installEdgeLadder()
        return rail
    }

    /**
     * THE ONE PANEL LANGUAGE (Michael 2026-10-11: "The tuck panels are not working with the same
     * design language hierarchy" → plan → "Yes"). A list or reading surface's column, built from
     * [com.toolsboox.ot.PanelSeats] — the same seats, order and captions as iOS:
     *  ⛶ | Today · Nib · More | the hand | the room, by kind | Side.
     * Hub and Feeds are off the column (Michael 2026-10-12: "Those too."): the screen's More carries
     * ☰ Hub as its first row ([withHub]) and the left edge's Back opens it ([installEdgeLadder],
     * installed here).
     * The frame and foot seats come with shared doors ([panelDoor]); [seat] answers the screen's
     * own (and may override a door — return null for a frame seat to take the shared one, null for
     * any other seat to leave it off this moment, e.g. Later on a piece already kept). The column
     * honours the ONE side setting (`calendarToolbarSide`, the same pref every rail hops by) and
     * carries Side last. Full screen hides the gutter and stands a [⛶] pill in [fullPillHost].
     * [rebuildActionRail] re-asks the seats; the panel redraws only when they changed (the redress
     * rule — no repaint, no B6 flash, for the same seats again).
     *
     * [dressNow] is for a surface whose dress follows its state — Feeds wears the list's dress, or
     * the article's while a piece is open over it — re-read on every rebuild. [afterFrame] rides
     * between the frame and the hand as a block of its own (Feeds: the player's transport while
     * something plays — spec §1, "the player block sits directly under the frame"). The foot (Side)
     * goes to the panel as its own block, pinned under the scroll whenever the column outruns the
     * screen, so it is never the seat that falls off the end (review 2026-10-11, F1). [onFull]
     * hears full screen come and go (the Net shows its header ⟳ and Piles only then, F3).
     */
    protected fun setupPanel(
        gutter: androidx.constraintlayout.widget.ConstraintLayout,
        surfaceKey: String,
        dress: com.toolsboox.ot.PanelSeats.Dress,
        fullPillHost: android.view.ViewGroup,
        flags: () -> com.toolsboox.ot.PanelSeats.Flags = { com.toolsboox.ot.PanelSeats.Flags() },
        dressNow: () -> com.toolsboox.ot.PanelSeats.Dress = { dress },
        afterFrame: () -> List<com.toolsboox.ot.TuckPanel.Item> = { emptyList() },
        onFull: (full: Boolean) -> Unit = {},
        seat: (com.toolsboox.ot.PanelSeats.Seat) -> com.toolsboox.ot.TuckPanel.Item?
    ): com.toolsboox.ot.TuckPanel {
        val rail = com.toolsboox.ot.TuckPanel(
            host = this,
            toolbar = null,
            surfaceKey = surfaceKey,
            sideIsLeft = { !railDockedRight() },
            onStateApplied = { _, open -> onFull(!open) },
            gutter = gutter,
            fullCapable = true,
            fullPillHost = fullPillHost
        )
        // The gutter moves to the row's other end on a remembered RIGHT dock, before first paint —
        // setupActionRail's mechanism (LinearLayout order is the whole of it).
        fun dock() {
            val row = gutter.parent as? android.widget.LinearLayout ?: return
            val end = railDockedRight()
            if (row.indexOfChild(gutter) == (if (end) row.childCount - 1 else 0)) return
            row.removeView(gutter)
            row.addView(gutter, if (end) row.childCount else 0)
        }
        dock()
        fun rebuild() {
            val layout = com.toolsboox.ot.PanelSeats.layout(
                dressNow(), com.toolsboox.ot.PanelSeats.Platform.ANDROID, flags()) ?: return
            fun items(block: List<com.toolsboox.ot.PanelSeats.Seat>) =
                block.mapNotNull { s -> seat(s) ?: panelDoor(s, rail) { dock() } }
            val body = listOf(layout.frame, layout.hand, layout.room).map { items(it) }
            val extra = afterFrame()
            rail.setBlocks(if (extra.isEmpty()) body else listOf(body.first(), extra) + body.drop(1),
                foot = items(layout.foot))
        }
        rebuild()
        actionRailRebuilds[surfaceKey] = { rebuild() }
        installEdgeLadder()
        return rail
    }

    /**
     * The shared doors every panel wears unless its screen answers the seat itself:
     *  - Hub: the Ledger directory (worn only by a reader outside its list now — PanelSeats).
     *  - Today: on a reader, today's day page (spec §3, Q2: "Readers and the Net — the day page";
     *    tap again or hold, the same).
     *  - Notes (the nib seat; captioned and named "Notes" since Michael 2026-10-12: "In the reader
     *    the nib should be called notes"): back to your note — the floating notes button's own
     *    tap, whatever it is set to; hold, the notes menu (its tray).
     *  - Feeds: the Feeds room, where you were.
     *  - Side: the column to the other edge, every rail with it.
     * Null for any other seat — the screen's own.
     */
    protected fun panelDoor(
        s: com.toolsboox.ot.PanelSeats.Seat,
        rail: com.toolsboox.ot.TuckPanel,
        dock: () -> Unit
    ): com.toolsboox.ot.TuckPanel.Item? = when (s) {
        com.toolsboox.ot.PanelSeats.Seat.HUB -> com.toolsboox.ot.PanelFaces.item(s) { openHub() }
        com.toolsboox.ot.PanelSeats.Seat.TODAY -> com.toolsboox.ot.PanelFaces.item(s, "Today's page",
            longPress = { openTodaysPage() }) { openTodaysPage() }
        com.toolsboox.ot.PanelSeats.Seat.NIB -> com.toolsboox.ot.PanelFaces.item(s,
            longPress = { showNotesDoorMenu() }) { openNotesDoor() }
        com.toolsboox.ot.PanelSeats.Seat.FEEDS -> com.toolsboox.ot.PanelFaces.item(s) {
            com.toolsboox.plugin.feeds.ui.openFeedView(androidx.navigation.fragment.NavHostFragment.findNavController(this), "feed", null)
        }
        com.toolsboox.ot.PanelSeats.Seat.SIDE -> com.toolsboox.ot.PanelFaces.item(s) {
            val p = requireContext().getSharedPreferences("MAIN", 0)
            p.edit().putString("calendarToolbarSide", if (railDockedRight()) "LEFT" else "RIGHT").apply()
            dock()
            rail.applyState()
        }
        else -> null
    }

    // ── THE HUB WITHOUT A SEAT (Michael 2026-10-12) ─────────────────────────────────────────────
    //
    // "Those too." took ☰ Hub and the Feeds door off every panel; "Make sure we can get to hub with
    // back swipe in the reader or touch or something as needed" → plan → "Awesome":
    //  (1) touch — every panel's More (wrench) carries ☰ Hub as its FIRST row ([withHub]); it opens
    //      the main menu over the page, never leaving it;
    //  (2) the left edge's Back — [installEdgeLadder]: a reader back to its list first, a room its
    //      pane first, everywhere else the Hub at once ([com.toolsboox.ot.EdgeLadder]).

    /** The Hub over this screen — the Ledger directory, hung from the column's side. The day page
     *  answers with its own (its recent books folded in), Feeds and The Mail with theirs. */
    open fun openHub() {
        if (!isAdded) return
        showAccordion(com.toolsboox.plugin.feeds.ui.ledgerDirectoryFolders(this), anchorEnd = railDockedRight())
    }

    /** More's first row on every panel: ☰ Hub (Michael 2026-10-12). [hub] overrides the door. */
    protected fun hubRow(hub: (() -> Unit)? = null): GoItem = GoItem("☰", "Hub") { hub?.invoke() ?: openHub() }

    /** [groups] for a [showGoModal] More, with ☰ Hub as the first row, above every heading. */
    protected fun withHub(groups: List<Pair<String, List<GoItem>>>, hub: (() -> Unit)? = null): List<Pair<String, List<GoItem>>> =
        listOf("" to listOf(hubRow(hub))) + groups

    /** The same for a [showDirectory] More (a header-less group is leaf rows). */
    protected fun withHubRows(
        groups: List<Pair<String, List<Pair<String, () -> Unit>>>>,
        vararg next: Pair<String, () -> Unit>
    ): List<Pair<String, List<Pair<String, () -> Unit>>>> =
        listOf("" to (listOf<Pair<String, () -> Unit>>("☰  Hub" to { openHub() }) + next)) + groups

    /** A screen you READ: its first left-edge Back goes back to its list or shelf (the 09-11
     *  rule) — the book reader, the standalone article. */
    protected open val edgeReader: Boolean get() = false

    /** This screen's room pane as the ladder sees it — Feeds and The Mail answer. */
    protected open fun edgePane(): com.toolsboox.ot.EdgeLadder.Pane = com.toolsboox.ot.EdgeLadder.Pane.NONE

    /** Open the room's pane — the ladder's first rung where [edgePane] says SHUT. */
    protected open fun openEdgePane() {}

    /**
     * The view the FINGER inks on this screen right now, or null — read by the activity's
     * left-edge catcher at a touch-down in the edge zone ([com.toolsboox.ot.LeftEdgeCatcher];
     * Michael 2026-10-12, the B6): landing on it, the flick must be a bezel flick, so a finger
     * stroke out of the margin stays a stroke. The ink pages answer in Finger mode.
     */
    open fun edgeInkView(): View? = null

    /** The left-edge flick took the finger that was writing: drop its stroke under way, so the
     *  CANCEL the catcher hands the page commits no tick of ink. The ink pages answer. */
    open fun edgeDropFingerInk() {}

    /** The view the ladder is installed on — once per view, however many builders run. */
    private var edgeLadderView: View? = null

    /** True while the ladder itself is opening the Hub — read by [showAccordion]'s Back. */
    private var hubByLadder = false

    /** The screen's own Back callbacks ([addOwnBackCallback]) on [ownBackView]. */
    private val ownBackCallbacks = mutableListOf<androidx.activity.OnBackPressedCallback>()
    private var ownBackView: View? = null

    /**
     * A screen's OWN Back — close the open article, walk the web page's history. Registered
     * through here, it always stands ABOVE the left edge's ladder whichever of the two
     * onViewCreated reaches first: [installEdgeLadder] re-seats any registered before it (review
     * 2026-10-12: the order was only a comment; now it is the rule).
     */
    protected fun addOwnBackCallback(callback: androidx.activity.OnBackPressedCallback) {
        val v = getView()
        if (ownBackView !== v) { ownBackCallbacks.clear(); ownBackView = v }
        ownBackCallbacks += callback
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    /**
     * THE LEFT EDGE'S BACK LADDER on this screen (Michael 2026-10-12; [com.toolsboox.ot.EdgeLadder]).
     * One OnBackPressedCallback for the view's life, registered by every panel builder
     * ([setupPanel], [setupActionRail], the ink rail) — and the screen's own Back callbacks
     * ([addOwnBackCallback]) always stand above it, so they still come first (the open article
     * closes, the Net goes back a page).
     * It hears where a Back began (handleOnBackStarted → swipeEdge; a B6 probe line goes to the
     * log on every Back, edge named), and a Back that began at the LEFT edge climbs the ladder:
     * pane → Hub → back (the Hub's own Back closes it AND goes back one place, arming the window —
     * Michael 2026-10-12: "Then two swipes back instead of three") → back. Every other Back —
     * the right edge, 3-button nav, Android 11–13, a programmatic one — is passed straight on to
     * the next callback (the nav controller, the activity), exactly as before.
     * The left edge comes from the system where nothing claims it (the Feeds list), and from the
     * activity's in-app catcher where the page does — every ink page (MainActivity.edgeCatcher,
     * [com.toolsboox.ot.LeftEdgeCatcher]; Michael 2026-10-12, the B6) — the same Back either way.
     * A screen with no panel (Settings, Mail compose, a portal on the Net…) never wore a Hub
     * seat and keeps plain Back on every edge — on purpose (review 2026-10-12).
     */
    protected fun installEdgeLadder() {
        val v = getView() ?: return
        if (edgeLadderView === v) return
        edgeLadderView = v
        val ladder = object : androidx.activity.OnBackPressedCallback(true) {
            private var edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN

            override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                edge = backEvent.swipeEdge
            }

            override fun handleOnBackCancelled() {
                edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
            }

            override fun handleOnBackPressed() {
                val e = edge
                edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
                // One swipe, one rung: a Back right after the in-app left-edge flick that is not the
                // flick's own is the same swipe heard again (Michael 2026-10-12, the B6).
                if (com.toolsboox.ot.EdgeLadder.isEcho(SystemClock.uptimeMillis())) {
                    Timber.i("EdgeLadder: a Back on %s right after the left-edge flick — the same swipe, swallowed",
                        this@ScreenFragment.javaClass.simpleName)
                    return
                }
                // A reader's pane is its contents, when it has one (the book's — "Content then
                // shelf", Michael 2026-10-12); the default NONE keeps every other reader's plain Back.
                val pane = edgePane()
                // Asked only inside the window, where a flick with nowhere behind it is kept (END).
                val rung = com.toolsboox.ot.EdgeLadder.shared.swipe(SystemClock.uptimeMillis(), e, pane, edgeReader,
                    somewhereBack = e != com.toolsboox.ot.EdgeLadder.EDGE_LEFT || hasSomewhereBack())
                // The probe Michael's B6 needs (does Bigme's Android 14 report the edge?): one line
                // per Back, the edge named — "unreported" is a Back that sent no BackEvent at all.
                Timber.i("EdgeLadder: Back on %s from the %s edge, pane %s → %s%s",
                    this@ScreenFragment.javaClass.simpleName, com.toolsboox.ot.EdgeLadder.edgeName(e), pane, rung,
                    if (rung == com.toolsboox.ot.EdgeLadder.Rung.END) " — nothing further back, the Back is kept" else "")
                when (rung) {
                    // iOS's endOfTrail (review 2026-10-12): the window's flicks walk back to the page
                    // you started on and stop there — never out of Ledgable. The next flick after the
                    // window opens the Hub again; the right edge is still the plain Back.
                    com.toolsboox.ot.EdgeLadder.Rung.END -> Unit
                    com.toolsboox.ot.EdgeLadder.Rung.OPEN_PANE -> openEdgePane()
                    // Told to the Hub's dialog ([showAccordion]), so its close arms the window even
                    // when the dialog never hears the edge (review 2026-10-12).
                    com.toolsboox.ot.EdgeLadder.Rung.HUB -> {
                        hubByLadder = true
                        try { openHub() } finally { hubByLadder = false }
                    }
                    com.toolsboox.ot.EdgeLadder.Rung.PLAIN,
                    com.toolsboox.ot.EdgeLadder.Rung.BACK -> passOn()
                }
            }

            /** Plain Back: stand aside for one dispatch, so the next callback (or the activity's
             *  own) takes it — the dispatcher has already let go of this one. */
            private fun passOn() {
                val dispatcher = activity?.onBackPressedDispatcher ?: return
                isEnabled = false
                try { dispatcher.onBackPressed() } finally { isEnabled = true }
            }
        }
        val dispatcher = requireActivity().onBackPressedDispatcher
        dispatcher.addCallback(viewLifecycleOwner, ladder)
        // The screen's own Back registered ahead of the ladder steps back above it: the newest
        // callback is asked first, so remove + add re-seats it (its enabled state goes with it).
        if (ownBackView === v) for (own in ownBackCallbacks) {
            own.remove()
            dispatcher.addCallback(viewLifecycleOwner, own)
        }
    }

    /** Today's day page — the directory's ☀ Today door, its return anchor first. */
    protected fun openTodaysPage() {
        if (!isAdded) return
        (this as? ReturnAnchorProvider)?.prepareReturnAnchor()
        runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_calendar_day) }
    }

    /** The Nib's tap: the floating notes button's own tap, so the door does exactly what that
     *  button is set to (Notes, where you left off, by default). */
    protected fun openNotesDoor() {
        val button = activity?.findViewById<View>(R.id.floatNoteButton)
        if (button != null) button.performClick()
        else com.toolsboox.plugin.calendar.CalendarNavigator.toLastDayNote(this)
    }

    /** The Nib's hold: the notes button's own tray while it is on screen; while it is put away, the
     *  same doors as a short list (spec §3: Notes · Text notes · Feeds · Today). */
    protected fun showNotesDoorMenu() {
        val button = activity?.findViewById<View>(R.id.floatNoteButton)
        if (button != null && button.visibility == View.VISIBLE && button.isAttachedToWindow && button.width > 0) {
            button.performLongClick()
            return
        }
        val ctx = context ?: return
        val rows = listOf<Pair<String, () -> Unit>>(
            "Notes" to { com.toolsboox.plugin.calendar.CalendarNavigator.toLastDayNote(this) },
            "Text notes" to { runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.action_to_text_notes) }; Unit },
            "Feeds" to { com.toolsboox.plugin.feeds.ui.openFeedView(androidx.navigation.fragment.NavHostFragment.findNavController(this), "feed", null) },
            "Today" to { openTodaysPage() },
        )
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setItems(rows.map { it.first }.toTypedArray()) { _, w -> rows[w].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Re-ask a rail's action provider and re-dress it — for surfaces whose actions follow
     *  their state, called wherever the retired pill used to re-show its buttons. */
    protected fun rebuildActionRail(surfaceKey: String) {
        actionRailRebuilds[surfaceKey]?.invoke()
    }

    private val actionRailRebuilds = HashMap<String, () -> Unit>()

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
        // Through `apply`, so the step is remembered: rotating with the button and then restarting
        // used to snap back to whatever the manifest said.
        com.toolsboox.ot.ScreenRotation.apply(
            activity,
            com.toolsboox.ot.ScreenRotation.next(
                com.toolsboox.ot.ScreenRotation.cycleFor(mask),
                activity.requestedOrientation,
                com.toolsboox.ot.ScreenRotation.displayedBy(currentSurfaceRotation())
            )
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
            // Leaving the sensor: hold whatever it had landed on, so the screen doesn't jump — and
            // remember THAT, not merely "not-sensor", or a restart would have nothing to lock to.
            com.toolsboox.ot.ScreenRotation.apply(
                activity, com.toolsboox.ot.ScreenRotation.displayedBy(currentSurfaceRotation()))
            showMessage(getString(R.string.rotate_locked), requireView())
        } else {
            com.toolsboox.ot.ScreenRotation.apply(
                activity, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR)
            showMessage(getString(R.string.rotate_auto), requireView())
        }
        // The settings switch reads this key, so the two controls agree next time it's opened.
        requireContext().getSharedPreferences("MAIN", 0).edit()
            .putBoolean("autoRotate", !auto).apply()
    }

    @Suppress("DEPRECATION")
    private fun currentSurfaceRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            requireContext().display?.rotation ?: 0
        else requireActivity().windowManager.defaultDisplay.rotation

    /** Collapse the almanac nav pill to grip + centre glyph (↑↓ hide); tap the grip to toggle. */
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
                // The attachment's id IS the filename's uuid (iOS's rule; review 2026-10-16 #7), so
                // a tap place on a pick finds the file from its id alone, on either app.
                val id = UUID.randomUUID().toString()
                val dest = File(attachmentsDir(), "photo-$id.jpg")
                // Only destroy the source and record the attachment when the copy actually
                // landed — a disk-full copy failure used to delete the ONLY copy and persist
                // an attachment pointing at a missing file (gallery import got this right).
                if (runCatching { src.copyTo(dest, overwrite = true) }.isSuccess) {
                    src.delete()
                    emitAttachment(Attachment(id, Attachment.Kind.PHOTO, dest.name, null, Date()))
                } else {
                    if (isPickCapture) showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.OUT_OF_SPACE)
                    else showMessage(R.string.reader_capture_failed)
                    captureSink = null; gramSink = null
                }
            } else captureSink = null
            pendingCameraFile = null; pendingCameraUri = null
        }

    private val annMicPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceRecording()
            else {
                if (isPickCapture) com.toolsboox.plugin.feeds.ot.AddToPick.micOff(this)
                else showMessage(R.string.reader_capture_mic_denied)
                captureSink = null; gramSink = null
            }
        }

    private val annVideoLauncher =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> onAnnVideo(ok) }

    /** A pick's video: the camera stops it at 3:00 (add-to-pick pin §7 — "the most a pick holds"). */
    private val pickVideoLauncher =
        registerForActivityResult(object : ActivityResultContracts.CaptureVideo() {
            override fun createIntent(context: android.content.Context, input: Uri): Intent =
                super.createIntent(context, input)
                    .putExtra(MediaStore.EXTRA_DURATION_LIMIT, com.toolsboox.plugin.calendar.ot.AddToPickWords.VIDEO_MAX_S)
        }) { ok -> onAnnVideo(ok) }

    private fun onAnnVideo(ok: Boolean) {
        val src = pendingVideoFile
        pendingVideoFile = null
        if (!(ok && src != null && src.exists())) { gramSink = null; captureSink = null; return }
        val id = UUID.randomUUID().toString()
        val dest = File(attachmentsDir(), "video-$id.mp4")
        val picked = isPickCapture
        // A take of up to 3:00 is copied, and its length read, OFF the main thread (review
        // 2026-10-16: both ran here and risked the app freezing after a long take); what follows —
        // the sinks, the words — is back on it.
        Thread {
            val copied = runCatching { src.copyTo(dest, overwrite = true) }.isSuccess
            // Its length rides on the attachment, so the pick's ▶ pill and chip read 0:41 without
            // opening the file again.
            val secs = if (copied) { src.delete(); com.toolsboox.plugin.calendar.ot.AvPoster.durationMs(dest) / 1000.0 } else 0.0
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (copied) {
                    if (picked && secs >= com.toolsboox.plugin.calendar.ot.AddToPickWords.VIDEO_MAX_S - 1)
                        showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.VIDEO_CAPPED)
                    emitAttachment(Attachment(id, Attachment.Kind.VIDEO, dest.name, secs.takeIf { it > 0 }, Date()))
                } else {
                    if (picked) showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.OUT_OF_SPACE)
                    else showMessage(R.string.reader_capture_failed)
                    gramSink = null; captureSink = null
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ── "＋ Add to this pick" (add-to-pick pin §1/§7) ──────────────────────────────────────────

    /** The sink a pick's capture runs through — while it is the one waiting, failures say the
     *  pin's words and the A/V gram toast stays quiet (the pick says where it went). */
    private var pickSink: ((Attachment) -> Unit)? = null
    private val isPickCapture: Boolean get() = gramSink != null && gramSink === pickSink
    /** A recording the fragment stopped on its way out (a call, the lock): its clock, for the
     *  "kept" line. */
    private var pickInterruptedAt: Int = -1

    /**
     * Straight to one medium for a pick — 🎤 the recorder, 📷 the camera (no take-or-upload
     * question: 🖼 is its own row), 🖼 the library, 🎥 the camera's video (3:00 at most). No camera
     * on this device (the B6): "No camera on this device — choose a picture instead.", then the
     * library opens.
     */
    fun captureForPick(kind: com.toolsboox.plugin.feeds.ot.AddToPick.Capture, onAttachment: (Attachment) -> Unit) {
        val sink: (Attachment) -> Unit = { att -> pickSink = null; onAttachment(att) }
        pickSink = sink
        gramSink = sink
        captureSink = null
        captureSelection = ""
        pickInterruptedAt = -1
        val noCamera = !requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        when (kind) {
            com.toolsboox.plugin.feeds.ot.AddToPick.Capture.VOICE -> requestVoiceRecording()
            com.toolsboox.plugin.feeds.ot.AddToPick.Capture.GALLERY -> annGalleryLauncher.launch("image/*")
            com.toolsboox.plugin.feeds.ot.AddToPick.Capture.CAMERA, com.toolsboox.plugin.feeds.ot.AddToPick.Capture.VIDEO -> {
                if (noCamera) { pickNoCamera(); return }
                if (kind == com.toolsboox.plugin.feeds.ot.AddToPick.Capture.CAMERA) launchAnnCamera()
                else try {
                    val dir = File(requireContext().cacheDir, "camera").apply { mkdirs() }
                    val vid = File(dir, "clip-${SystemClock.elapsedRealtimeNanos()}.mp4")
                    val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", vid)
                    pendingVideoFile = vid
                    pickVideoLauncher.launch(uri)
                } catch (e: SecurityException) {
                    com.toolsboox.plugin.feeds.ot.AddToPick.cameraOff(this, video = true) { annGalleryLauncher.launch("image/*") }
                } catch (e: Exception) {
                    Timber.w(e, "pick video unavailable"); pickNoCamera()
                }
            }
        }
    }

    /** No camera here: say so, then the library — the pick's capture still waiting on it. */
    private fun pickNoCamera() {
        showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.NO_CAMERA)
        runCatching { annGalleryLauncher.launch("image/*") }.onFailure { gramSink = null; pickSink = null }
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
            // "Picture" is one medium with two doors, and the caller only chose the medium —
            // take-or-upload is a question nobody has answered yet, not a repeat of one.
            Attachment.Kind.PHOTO -> showIconMenu(getString(R.string.reader_capture_title), listOf(
                getString(R.string.reader_capture_photo) to { launchAnnCamera() },
                getString(R.string.reader_capture_upload) to { annGalleryLauncher.launch("image/*") }
            ))
        }
    }

    /** 🖼 Choose a picture — the gallery straight, no take-or-upload question (the article ladder's
     *  own row, iOS's "Choose a picture"; design critique #9, Michael 2026-10-12). */
    protected fun captureGalleryDirect(onGram: (Attachment) -> Unit) {
        gramSink = onGram
        captureSink = null
        captureSelection = ""
        annGalleryLauncher.launch("image/*")
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
        /** ★ Star it — the surface's own star ceremony; null hides the row. */
        onStar: (() -> Unit)? = null,
        /** 📝 Onto today's note — the surface's own "place this on the page I'm writing"; null
         *  hides the row. */
        onPlaceGram: (() -> Unit)? = null,
        onCapture: (String?, String?, Attachment?) -> Unit
    ) {
        captureSelection = selection
        captureSource = sourceTitle
        captureSink = onCapture
        gramSink = null
        // THE CANON (Michael, 09-10: "make sure every annotation surface has 'Save highlight'
        // with the handwriting, audio capture, etc."): the same seven rows, in the same order,
        // as the feed chooser (FeedNoteGram.show) — so a book, a comic panel and an article all
        // teach one ladder. A bare highlight is a complete annotation, hence ❝ first: one tap
        // files the passage through the sink with no note and no media.
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (selection.isNotBlank()) options.add("❝  Save highlight" to {
            captureSink?.invoke(selection, null, null)
            captureSink = null
            showMessage("Highlight saved")
        })
        // Handwriting is a FIRST-CLASS way to annotate on a handwriting-forward device —
        // the pad was only ever missing its door here. The ink lands as a photo attachment,
        // so every surface that takes a photo annotation takes a written one.
        options.add("✍  Handwrite a note" to { showHandwriteCapture() })
        options.add("🎤  Record a voice note" to { requestVoiceRecording() })
        options.add(com.toolsboox.plugin.calendar.ot.AddToPickWords.TAKE_PHOTO to { launchAnnCamera() })
        options.add("⌨  Type a note" to { showNoteDialog() })
        if (onStar != null) options.add("★  Star it" to { captureSink = null; onStar() })
        if (onPlaceGram != null) options.add("📝  Onto today's note" to { captureSink = null; onPlaceGram() })
        // The extras follow the seven rather than interleaving with them.
        options.add(getString(R.string.reader_capture_upload) to { annGalleryLauncher.launch("image/*") })
        // A highlight can become a shareable quote card (parity with the iPad annotation composer).
        if (selection.isNotBlank()) options.add("🃏  Create pick" to { shareQuoteCard(selection, sourceTitle) })
        // Ask/Educate ride every annotation menu: a highlight (or the whole item, when blank)
        // goes to Ask with provenance, or grams onto the intake sheet's Educate Me panel.
        options.add("🔎  Ask about this" to {
            com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
                this, selection.ifBlank { null },
                title = sourceTitle ?: "", link = "", sourceLabel = sourceTitle ?: "")
        })
        options.add("🎓  Educate me" to {
            com.toolsboox.plugin.calendar.ot.AskBridge.gramToEducateMe(
                this, text = selection.ifBlank { sourceTitle ?: "" },
                title = sourceTitle ?: "", link = "", sourceLabel = sourceTitle ?: "")
        })
        showIconMenu(getString(R.string.reader_capture_title), options)
    }

    /**
     * One capture verb, straight — no chooser in front of it. For surfaces that already asked
     * (the reader's mark panel picks the style/verb and the mark in the same breath, so a second
     * menu here would be the very panel the merge removed).
     */
    protected fun captureAnnotationVerb(
        verb: String,
        selection: String,
        sourceTitle: String? = null,
        onCapture: (String?, String?, Attachment?) -> Unit
    ) {
        captureSelection = selection
        captureSource = sourceTitle
        captureSink = onCapture
        gramSink = null
        when (verb) {
            "note" -> showNoteDialog()
            "handwrite" -> showHandwriteCapture()
            "gram" -> shareQuoteCard(selection, sourceTitle)
            "ask" -> com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
                this, selection.ifBlank { null },
                title = sourceTitle ?: "", link = "", sourceLabel = sourceTitle ?: "")
            "educate" -> com.toolsboox.plugin.calendar.ot.AskBridge.gramToEducateMe(
                this, text = selection.ifBlank { sourceTitle ?: "" },
                title = sourceTitle ?: "", link = "", sourceLabel = sourceTitle ?: "")
            "photo" -> launchAnnCamera()
            "upload" -> annGalleryLauncher.launch("image/*")
            "voice" -> requestVoiceRecording()
            else -> showNoteDialog()
        }
    }

    /**
     * A hand-written annotation: the ink pad, rendered to a PNG, emitted through the same
     * attachment sink every other capture uses. Buttons at the TOP, above the pad — the rule
     * every handwriting panel follows: the writing hand rests below the pad, and dialog
     * buttons down there get palm-tapped mid-word.
     */
    private fun showHandwriteCapture() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val pad = com.toolsboox.ot.InkPadView(ctx)
        val inkFrame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(px(2), px(2), px(2), px(2))
            addView(pad, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, px(300)))
        }
        lateinit var dialog: AlertDialog
        val cancelBtn = TextView(ctx).apply {
            text = "CANCEL"; textSize = 15f; setTextColor(0xFF555555.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(2), px(24), px(6))
            setOnClickListener { captureSink = null; gramSink = null; dialog.dismiss() }
        }
        val saveBtn = TextView(ctx).apply {
            text = "SAVE"; textSize = 15f; setTextColor(0xFF2F6F96.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(24), px(2), 0, px(6))
            setOnClickListener {
                isEnabled = false   // first tap wins — the encode is slow enough to palm-bounce
                val bmp = pad.render()
                if (bmp == null) { captureSink = null; dialog.dismiss(); return@setOnClickListener }
                val dest = File(attachmentsDir(), "photo-${UUID.randomUUID()}.png")
                val ok = runCatching {
                    dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }.isSuccess
                dialog.dismiss()
                if (ok) emitAttachment(Attachment(
                    UUID.randomUUID().toString(), Attachment.Kind.PHOTO, dest.name, null, Date()))
                else { showMessage(R.string.reader_capture_failed); captureSink = null }
            }
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn)
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(saveBtn)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(px(14), px(12), px(14), px(4))
            addView(topRow)
            addView(com.toolsboox.ot.InkPadView.penBar(ctx, pad))
            addView(inkFrame)
        }
        dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setView(root).create()
        // This dialog collects work — a stray palm outside it must not cost the ink.
        dialog.setCanceledOnTouchOutside(false)
        showModal(dialog)
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
        val b = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
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
        } catch (e: SecurityException) {
            if (isPickCapture) com.toolsboox.plugin.feeds.ot.AddToPick.cameraOff(this, video = false) { annGalleryLauncher.launch("image/*") }
            else { showMessage(R.string.reader_capture_no_camera); captureSink = null }
        } catch (e: Exception) {
            Timber.w(e, "camera capture unavailable")
            if (isPickCapture) pickNoCamera()
            else { showMessage(R.string.reader_capture_no_camera); captureSink = null }
        }
    }

    private fun importGalleryPhoto(uri: Uri) {
        val id = UUID.randomUUID().toString()
        val dest = File(attachmentsDir(), "photo-$id.jpg")
        val ok = runCatching {
            requireContext().contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }.isSuccess
        if (ok) emitAttachment(Attachment(id, Attachment.Kind.PHOTO, dest.name, null, Date()))
        else {
            if (isPickCapture) showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.OUT_OF_SPACE)
            else showMessage(R.string.reader_capture_failed)
            captureSink = null
        }
    }

    private fun emitAttachment(att: Attachment) {
        // A pick's capture says where it went itself ("Photo saved · its pick in All Picks"); a
        // recording stopped on the way out says it was kept (add-to-pick pin §7).
        if (isPickCapture) {
            val sink = gramSink; gramSink = null
            if (pickInterruptedAt >= 0) showMessage(com.toolsboox.plugin.calendar.ot.AddToPickWords.recordingStopped(
                com.toolsboox.plugin.calendar.ot.PickFaceLayout.clock(pickInterruptedAt)))
            pickInterruptedAt = -1
            sink?.invoke(att)
            return
        }
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
        val id = UUID.randomUUID().toString()
        com.toolsboox.ot.VoiceRecorder.record(
            context = requireContext(),
            out = File(attachmentsDir(), "voice-$id.m4a"),
            recordingLabel = { clock -> getString(R.string.reader_capture_recording, clock) },
            stopLabel = getString(R.string.reader_capture_stop),
            onSaved = { file, seconds ->
                emitAttachment(Attachment(id, Attachment.Kind.AUDIO, file.name, seconds, Date()))
            },
            onDiscarded = { captureSink = null; gramSink = null }
        )
    }

    override fun onStop() {
        super.onStop()
        // Recording only ends via the dialog's buttons — navigating away or backgrounding while
        // recording held the mic, kept the file growing, and leaked the dialog's window. Saving
        // on the way out keeps the memo instead of discarding it.
        if (isPickCapture && com.toolsboox.ot.VoiceRecorder.isRecording)
            pickInterruptedAt = com.toolsboox.ot.VoiceRecorder.elapsedSeconds
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
    /**
     * The handle IS the control: tap it again and again and a pill walks its four states.
     *
     *   wide open → wide folded → tall open → tall folded → wide open …
     *
     * There are only four ways a pill can be, so a dedicated Horizontal/Vertical row in a menu —
     * and a switch buried in Settings — were controls for reaching a thing you are already
     * touching. Folding and turning became the same gesture, and three separate controls went
     * away with it.
     *
     * Each tap changes exactly ONE thing: it folds, and only once folded does the next tap turn
     * it. A cycle you can predict is worth more than a cycle that is quick to get round.
     *
     * Returns the new (collapsed, vertical) so the caller can apply whatever "folded" means on
     * its own pill — the states are shared, the appearance is not.
     */
    protected fun advancePillState(
        collapseKey: String, verticalKey: String, defaultVertical: Boolean
    ): Pair<Boolean, Boolean> {
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        val collapsed = prefs.getBoolean(collapseKey, false)
        val vertical = prefs.getBoolean(verticalKey, defaultVertical)
        val nextCollapsed = !collapsed
        val nextVertical = if (collapsed) !vertical else vertical
        prefs.edit()
            .putBoolean(collapseKey, nextCollapsed)
            .putBoolean(verticalKey, nextVertical)
            // Remember the SHAPE you last chose, so a surface you haven't set yet opens the way
            // the one before it looked rather than guessing from screen width.
            .putBoolean(PILL_LAST_VERTICAL, nextVertical)
            .apply()
        return nextCollapsed to nextVertical
    }

    /**
     * Wire a pill so its handle cycles all four states, and apply the stored one now.
     *
     * [collapsible] are the views that disappear when it folds — the grip itself never does, or
     * there would be nothing left to tap.
     */
    /**
     * The layout a pill takes when this surface has no opinion of its own yet.
     *
     * Every surface keeps its own `<key>_vertical` preference, which is right — you may want the
     * feed pill upright and the day pill along the bottom. But the FIRST time a surface is opened
     * it had no preference, so it fell back to a screen-width guess and could come up the opposite
     * way round from the one you were just looking at. Michael: "Books modal should be horizontal
     * if the other model before it was horizontal, same same vertical."
     *
     * So the fallback is now the last layout you CHOSE anywhere, and the width guess only applies
     * before you have ever flipped one. Once a surface has its own setting it keeps it — inheriting
     * would mean flipping the feed pill silently re-flipped the reader's.
     */
    /** The pill fallback, for callers that build their own pills rather than using
     *  [cyclePillOnTap] — the day page's nav/tool widgets. One rule, one place. */
    protected fun pillDefaultVertical(): Boolean =
        lastChosenVertical(resources.configuration.screenWidthDp < 520)

    private fun lastChosenVertical(default: Boolean): Boolean =
        requireContext().getSharedPreferences("ledger_widgets", 0)
            .let { if (it.contains(PILL_LAST_VERTICAL)) it.getBoolean(PILL_LAST_VERTICAL, default) else default }

    protected fun cyclePillOnTap(
        grip: View, pill: View, key: String, verticalKey: String = "${key}_vertical",
        collapsible: List<View> = emptyList(),
        defaultVertical: Boolean = lastChosenVertical(resources.configuration.screenWidthDp < 520),
        alsoOnTap: (() -> Boolean)? = null
    ) {
        // Default: everything in the pill folds away except the handle. Naming the children at
        // each call site meant every new button had to be remembered in a second place, and the
        // one that was forgotten just stayed on screen looking broken.
        fun folds(): List<View> = collapsible.ifEmpty {
            (pill as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
                .orEmpty().filter { it !== grip }
        }
        fun apply(collapsed: Boolean, vertical: Boolean) {
            (pill as? LinearLayout)?.orientation =
                if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            applyGripOrientation(grip, vertical)
            for (v in folds()) v.visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        val prefs = requireContext().getSharedPreferences("ledger_widgets", 0)
        apply(
            prefs.getBoolean("${key}_collapsed", false),
            prefs.getBoolean(verticalKey, defaultVertical)
        )
        makeDraggable(grip, pill, key) {
            // A screen may claim the tap for something more urgent (the feed pill shrinks to a
            // reading drawer); if it does, the cycle stays out of the way.
            if (alsoOnTap?.invoke() == true) return@makeDraggable
            val (c, v) = advancePillState("${key}_collapsed", verticalKey, defaultVertical)
            apply(c, v)
        }
    }

    protected fun applyGripOrientation(grip: View, vertical: Boolean) {
        // From the dimens, not from hard-coded dp — otherwise this silently undoes the
        // large-screen sizing every time a pill is flipped, and the grip alone shrinks back to
        // phone size on a Tab X while the buttons beside it stay large.
        //
        // Times the PILL dial, matching applyPillSizing on the buttons beside it — at Compact the
        // whole pill (grip included) slims to reading-margin width. It must be the same dial as
        // those buttons, whichever that is: when the pills were split onto their own setting this
        // line kept reading the modal one for a moment, which would have re-created the exact
        // failure the note above describes — a grip sized off one number and its neighbours off
        // another, disagreeing the moment the two dials differ.
        val scale = com.toolsboox.ot.ModalScale.pillScale(requireContext())
        val short = Math.round(resources.getDimensionPixelSize(R.dimen.ledger_grip_short) * scale)
        val long = Math.round(resources.getDimensionPixelSize(R.dimen.ledger_grip_long) * scale)
        grip.layoutParams = grip.layoutParams.apply {
            width = if (vertical) long else short
            height = if (vertical) short else long
        }
        grip.requestLayout()
    }

    /**
     * Re-dress a pill in place after the shared PILL dial changes — the same sizing pass
     * [makeDraggable] runs when a surface opens, for the surfaces that let you turn the dial
     * WHILE standing on them (the reader's wrench). Buttons and grip resize together (the grip
     * off its own orientation-aware pass, or the two disagree the moment the dials differ — see
     * [applyGripOrientation]), and then one re-clamp: the pill just changed size under a
     * translation measured against its old footprint, so growing while parked at an edge could
     * otherwise carry it past the screen. One relayout, one settle, no animation frames —
     * followed by a full panel clean on Onyx, because a resize leaves the old outline ghosted
     * on e-ink exactly the way a drag does.
     */
    protected fun reapplyPillScale(grip: View, pill: View, key: String) {
        applyPillSizing(pill, skip = grip)
        applyGripOrientation(grip, requireContext().getSharedPreferences("ledger_widgets", 0)
            .getBoolean("${key}_vertical", pillDefaultVertical()))
        pill.post {
            clampInParent(pill, grip)
            (this as? SurfaceFragment)?.refreshRawExcludeRects()
            try {
                com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(
                    com.onyx.android.sdk.api.device.epd.UpdateMode.GC
                )
            } catch (t: Throwable) { /* non-Onyx device — no panel to clean */ }
        }
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
     * One entry in the [showAccordion] directory. With [action] set and no [items] it renders as
     * a standalone tappable row (no caret) — for top-level items like All / Stars that aren't
     * folders; with [items] alone it's a collapsible folder. BOTH together make a door with a
     * caret: tapping the label fires [action] (the row's primary destination), and only the
     * caret at the row's end unfolds [items] — the Search·Ask·Directory row is why this exists.
     *
     * [subFolds] hangs a SECOND-level fold off a row, keyed by that row's exact label
     * ("🔖  Later List" holds its lanes; The Mail is a folder of its own since 2026-10-11). A map
     * beside [items] rather than a recursive row
     * type because the ~25 call sites that build items as plain label→action pairs get to stay
     * exactly as they are — a folder opts a row in by naming it, and the sub-rows ride wherever
     * the caller ordered the parent. An empty list is the same as no entry, so builders never
     * need to conditionally assemble the map.
     *
     * [holds] gives a row a LONG-PRESS, keyed by label the way [subFolds] is — the chrome canon's
     * hold-to-act ("✒ Notes": tap = today's dated note, hold = the family sheet). Accordion rows
     * only: the find-field's flattened matches stay tap-only, because a filtered list is a list
     * of places to go.
     *
     * [hold] is the same hold-to-act for a LEAF entry itself (action set, no items) — the four
     * homepages are leaf rows, and the ✒ Notes homepage needs its family-sheet hold without
     * having children to key a [holds] map by.
     */
    data class Folder(
        val emoji: String, val title: String,
        val items: List<Pair<String, () -> Unit>> = emptyList(),
        val expanded: Boolean = false,
        val action: (() -> Unit)? = null,
        val subFolds: Map<String, List<Pair<String, () -> Unit>>> = emptyMap(),
        val field: FolderField? = null,
        val holds: Map<String, () -> Unit> = emptyMap(),
        val hold: (() -> Unit)? = null,
    )

    /**
     * A TYPING FIELD the drawer carries on behalf of a folder — Ask's quick search of the Ledger.
     *
     * Michael: "Leave a search field on top for a quick search of Ledgable."
     *
     * DECLARED by the folder that owns it, RENDERED at the head of the drawer (see [showAccordion]):
     * the accordion opens at most one folder at a time and Ask is usually not the open one, so a
     * field living inside its children would be a quick search you had to unfold a group to reach.
     *
     * NOT the same thing as the drawer's own "Find anything" box, and the distinction is the whole
     * reason this exists as a second mechanism rather than a reuse of that one. That box FILTERS
     * MENU ROWS — it answers "where is the door called Starred". This field searches HIS LEDGER —
     * the corpus behind the Search surface, which knows nothing about menus. One field doing both
     * would have to guess which you meant on every keystroke, and would be wrong about half the
     * time in the most annoying possible way.
     *
     * IT SUBMITS, IT DOES NOT LIVE-QUERY, and that is deliberate rather than provisional. A live
     * result list under the field would mean running the corpus search behind the keyboard on a
     * Palma, and the corpus search is the one query in this app that reads day files; the drawer
     * would stutter, and opening the hub would start paying for a search nobody asked for. So
     * nothing happens until you press the keyboard's search key: [onSubmit] takes the typed text,
     * the drawer closes, and you land in the Search surface with the query already run — the same
     * destination the "🔍 Search" row opens, one step further along. An empty submit is the Search
     * row exactly.
     */
    data class FolderField(val hint: String, val onSubmit: (String) -> Unit)

    /** One row in a [showGoModal] section/tools modal. */
    data class GoItem(val emoji: String, val label: String, val action: () -> Unit)

    /** More › ☐ Tool labels (plan v6 §2.2 / S1.1, Michael 2026-10-10): flips the one global
     *  caption switch and re-dresses [rail] — a width change, so one B6 reconnect, as a tuck. */
    protected fun toolLabelsRow(rail: com.toolsboox.ot.TuckPanel?): GoItem {
        val on = com.toolsboox.ot.TuckPanel.labelsOn(requireContext())
        return GoItem(if (on) "☑" else "☐", "Tool labels") {
            com.toolsboox.ot.TuckPanel.setLabelsOn(requireContext(), !on)
            // A width change: it waits out the B6 layer like ⛶ does (review 2026-10-10).
            rail?.relabelWhenQuiet()
        }
    }

    /**
     * Accessibility: the modal text scale (Small / Medium / Large, set from the feed wrench,
     * applied to every Go modal / directory / accordion / context menu app-wide).
     */
    protected fun modalTextScale(): Float =
        modalTextScale(requireContext())

    /** The menu dial — see [menuTextScale]. What the directories and icon menus size against. */
    protected fun menuTextScale(): Float =
        menuTextScale(requireContext())

    /**
     * The compact "Go to…" modal (grouped rows), anchored top-left (directories) or up from the
     * bottom pill (sections). Lifted from the day page so the almanac pages use the SAME modal
     * instead of the old accordion drawer.
     *
     * [anchorEnd] hangs it from the RIGHT edge instead. The spatial rule (Michael: "have it
     * come from the same side as the popout"): a menu opened from an edge control grows from
     * that edge — the day rail docked right opens its menus on the right, so the hand and the
     * eye stay where the tap was. Doors that aren't edge controls keep the left default.
     */
    protected fun showGoModal(
        groups: List<Pair<String, List<GoItem>>>, anchorTop: Boolean, anchorEnd: Boolean = false
    ) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        // ONE dial: Modal size. Stacking the menu dial on top (times the system font scale
        // underneath) shrank the words while the box's chrome held — "text shrinking and
        // shrinking" inside modals that stayed huge. The Interface dial still governs the
        // wrapped stock dialogs; these floating menus follow Modal size alone.
        val textScale = com.toolsboox.ot.ModalScale.sizeScale(requireContext())
        // Group headers carry the vibe's accent — a solid, so it stays a crisp gray on a
        // monochrome Boox rather than dithering. The rows themselves stay black.
        val accent = com.toolsboox.ot.LedgerTheme.accent(requireContext())
        val rowLabels = mutableListOf<TextView>()
        for ((header, items) in groups) {
            // A blank heading is no heading: More's ☰ Hub row stands above every group
            // ([withHub], Michael 2026-10-12) — the first row, not a "HUB" section of one.
            if (header.isNotBlank()) {
                val tv = TextView(requireContext())
                tv.text = header.uppercase()
                tv.setTextColor(accent); tv.textSize = 11f * textScale; tv.letterSpacing = 0.08f
                tv.setPadding(dp(14), dp(10), dp(14), dp(2))
                list.addView(tv)
            }
            for (item in items) {
                val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
                r.findViewById<TextView>(R.id.go_label).apply {
                    text = applyRowIcon(r, "${item.emoji}  ${item.label}")
                    textSize = ROW_SP * textScale
                    rowLabels.add(this)
                }
                scaleRowIcon(r, textScale)
                scaleRowPadding(r, textScale)
                r.setOnClickListener { dialog.dismiss(); item.action() }
                list.addView(r)
            }
        }

        // Menu text honours the chosen reading font too. SYSTEM is a no-op (leaves rows untouched).
        com.toolsboox.ot.LedgerFonts.applyTree(root)

        // Measured AFTER the reading face lands on the rows — each label with its own final
        // paint — so the card below can be cut to its longest word rather than to a guess.
        // Measuring before the font swap under-counted by exactly the width of the reading
        // face, which is how "Synthesize" kept wrapping after the first fix.
        val widestLabelPx = rowLabels.maxOfOrNull { it.paint.measureText(it.text.toString()) } ?: 0f

        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            // Sized to the LONGEST label, within a hard screen cap — not to a fixed card
            // width. The fixed card ("never more than ~46%") cut "Synthesize" mid-word on a
            // Palma, and a menu that hyphenates its own destinations reads as broken. The
            // fixed width stays as the FLOOR (short menus keep their familiar shape); the
            // measured widest row (its icon slot, margins and padding added back, plus slack
            // for the reading face applied after measuring) can widen it, and 60% of the
            // screen is the line past which wrapping honestly beats eating the page.
            // Everything standing between the window's edges and the label's own box, all of
            // it real and counted: dialog_go_to's ScrollView margins (16+16), the card's
            // horizontal padding (12+12), the row's (14+14), the icon slot and its 18dp label
            // margin, plus a little slack. The first cut guessed 58dp, came up 44dp short, and
            // "Synthesize" kept wrapping by exactly those five pixels.
            val rowChrome = dp((ICON_DP * textScale).toInt()) + dp(32 + 24 + 28 + 18 + 8)
            lp.width = minOf(
                maxOf(dp((GO_MODAL_DP * textScale).toInt()), widestLabelPx.toInt() + rowChrome),
                (resources.displayMetrics.widthPixels * 0.60f).toInt()
            )
            // Through setLayout, as showAccordion does — width written onto the attributes
            // alone never reached the already-shown window, which is why the first cut of the
            // measure-to-widest fix appeared to change nothing on device.
            w.setLayout(lp.width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            // Top-left, matching showAccordion/showDirectory — so the menu appears in the SAME
            // position on every screen (day, feeds, reader) instead of jumping to the pill —
            // except when the opener is an edge control on the other side (see [anchorEnd]).
            lp.gravity = (if (anchorEnd) Gravity.END else Gravity.START) or Gravity.TOP
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
            // One glyph, one drawable: 📚/📖/🎓 and 🗒/🗎 used to collapse onto ic_book and
            // ic_reader_view, so four different doors in an open accordion wore the same icon
            // and the icons stopped carrying information. Each book-ish emoji now has its own.
            "📰" to R.drawable.ic_feed, "⭐" to R.drawable.ic_starred, "📖" to R.drawable.ic_book_open,
            // 📧 The Mail beside 📰 Feeds: both rooms' folders draw their mark as an outline.
            "📧" to R.drawable.ic_mail,
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
            "🎓" to R.drawable.ic_grad_cap, "🗎" to R.drawable.ic_reader_view, "🗒" to R.drawable.ic_note_page,
            // 📋 stays the card (Boards · Local); 🗃 is Boards · Site's file box; 🕰 is the Log
            // folder's History child — distinct from the 🕘/🕓 clock the folder itself wears.
            "📋" to R.drawable.ic_card, "🗃" to R.drawable.ic_clipboard, "🕰" to R.drawable.ic_history,
            "❤" to R.drawable.ic_heart, "📝" to R.drawable.ic_go_notes, "✍" to R.drawable.ic_edit,
            "🧠" to R.drawable.ic_swap,
            // Flow (the daily catch→make spine) wears the hierarchy/flow-chart glyph.
            "⤳" to R.drawable.ic_flowchart
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

    /** Draw [glyph] into a bitmap for the icon slot — any character, pixel-aligned. Rendered at
     *  2× the base slot so fitCenter always DOWNscales (crisp at every modal-size step). */
    private fun drawGlyphIcon(icon: ImageView, glyph: String) {
        val size = (52 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
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
    /**
     * A folder picker any surface can use, with the callback supplied at call time.
     *
     * `registerForActivityResult` has to happen before the fragment starts, so a launcher cannot be
     * created inside the handler that needs it. The slot holds the "what to do with it" until the
     * system comes back — which is what lets a directory ROW ask for a folder, when a row has no
     * lifecycle of its own to register anything against.
     */
    private var treePickHandler: ((android.net.Uri) -> Unit)? = null

    private val treePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val handler = treePickHandler
        treePickHandler = null
        if (uri != null) handler?.invoke(uri)
    }

    fun pickTree(intent: android.content.Intent, onPicked: (android.net.Uri) -> Unit) {
        treePickHandler = onPicked
        runCatching { treePicker.launch(intent) }
    }

    /** [showIconMenu] with an explanatory line above the rows — for a menu whose RULE matters more
     *  than its options (the Markdown mirror: text comes back, ink does not). */
    fun showIconMenuWithNote(
        title: CharSequence?, note: CharSequence, items: List<Pair<String, () -> Unit>>,
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val head = android.widget.TextView(ctx).apply {
            text = note
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(title)
            .setCustomTitle(null)
            .setView(head)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    protected fun showIconMenu(title: CharSequence?, items: List<Pair<String, () -> Unit>>) {
        val ctx = requireContext()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        // Reuse the rounded directory card so contextual menus (lasso Create task/event/card,
        // Extract, etc.) read the same as the iPad's action menus instead of a stock AlertDialog.
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        val titleView = root.findViewById<TextView>(R.id.go_to_title)
        val textScale = menuTextScale()
        if (title.isNullOrEmpty()) titleView.visibility = View.GONE else {
            titleView.text = title; titleView.textSize = TITLE_SP * textScale
        }
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        for ((label, action) in items) {
            val r = layoutInflater.inflate(R.layout.item_go_to, list, false)
            r.findViewById<TextView>(R.id.go_label).apply {
                text = applyRowIcon(r, label)
                textSize = ROW_SP * textScale
            }
            scaleRowIcon(r, textScale)
            r.setOnClickListener { dialog.dismiss(); action() }
            list.addView(r)
        }
        showModal(dialog)
        // The box grows with the type. Rows are single-line and ellipsized, so a fixed width at
        // Large just truncated the labels — the setting looked like it did nothing but crop.
        // Capped against the screen so the widest step still leaves an edge margin.
        dialog.window?.let { w ->
            w.attributes = w.attributes.apply {
                width = minOf(dp((ICON_MENU_DP * textScale).toInt()),
                    (resources.displayMetrics.widthPixels * 0.92f).toInt())
            }
        }
    }

    /** Keep a row's leading icon in step with its text, so the row stays balanced at every step. */
    private fun scaleRowIcon(row: View, scale: Float) {
        val icon = row.findViewById<ImageView>(R.id.go_icon) ?: return
        val side = (ICON_DP * scale * resources.displayMetrics.density).toInt()
        icon.layoutParams = icon.layoutParams.apply { width = side; height = side }
    }

    /**
     * Scale a row's VERTICAL padding with the dial too. Text and width already followed it, but
     * item_go_to's fixed 14dp top+bottom meant 28dp of dead height per row at every setting —
     * so a long menu stayed a long menu and Compact read as "nothing changed" on device.
     */
    private fun scaleRowPadding(row: View, scale: Float) {
        val d = resources.displayMetrics.density
        val v = (12 * scale * d).toInt()
        row.setPadding(row.paddingLeft, v, row.paddingRight, v)
        // The row must hold its icon AND its text with clear air — a row sized by text alone
        // crowded the glyph against the label ("needs to leave enough room", on device).
        row.minimumHeight = ((ICON_DP + 26) * scale * d).toInt()
    }

    /**
     * A modal shown over a drawing surface must PAUSE the Onyx hardware pen, or the stylus taps
     * fall through to the ink layer and the popup "freezes". Drawing fragments override
     * [onModalShown]/[onModalDismissed]; elsewhere these are no-ops. Route every popover through
     * [showModal] so this happens uniformly.
     */
    protected open fun onModalShown() {}
    protected open fun onModalDismissed() {}

    /** Where a placement's snackbar should sit ABOVE, on a screen whose lower half is an ink pad
     *  (the palm zone): a "Go to it" there takes a pen tap as ink. Null = the usual bottom edge. */
    open val snackAnchor: View? get() = null

    /**
     * Present a dialog over a making surface, with the pen bookkeeping around it.
     *
     * ⚠ IT TAKES THE DIALOG'S SHOW-LISTENER. A dialog holds exactly one, so any listener the
     * caller set is REPLACED here — silently, and with no way to chain (AlertDialog exposes no
     * getter to read the old one back). A dialog that wires its buttons in `setOnShowListener`
     * therefore arrives with them unwired and every button falling back to "dismiss and do
     * nothing". That is not hypothetical: it is exactly how the site editor's Save button came to
     * do nothing at all (see `SitesSettingsDialog`), and `LedgerItemsFragment` avoids
     * `showGuardedModal` for the same reason.
     *
     * If you need work done when the dialog appears, present it here FIRST and do that work
     * afterwards — by then it is showing and `getButton` returns real views.
     */
    fun showModal(dialog: AlertDialog) {
        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        // Ledgable never offers itself in its own dialogs' selection menus (C9, 2026-10-10):
        // MainActivity's onActionModeStarted never hears a dialog's window, so guard it here.
        runCatching { com.toolsboox.ui.share.OwnProcessText.guardTree(dialog.window?.decorView) }
    }

    /**
     * [showModal], and [onDismiss] when the dialog goes away (after the pen's own resume). The one
     * way to hear a modal's dismissal: [showModal] TAKES the dismiss listener too, so one set before
     * it is replaced in silence — which is how the Pick sheet's "keep what was added" never ran
     * (merge-addpick review #4).
     */
    fun showModal(dialog: AlertDialog, onDismiss: () -> Unit) {
        showModal(dialog)
        dialog.setOnDismissListener {
            onModalDismissed()
            runCatching(onDismiss).onFailure { Timber.w(it, "modal dismiss") }
        }
    }

    /**
     * [showModal] for a builder chain: the one-word swap for a bare `.show()`, returning the
     * dialog just as `Builder.show()` does. Plan v6 S1.0c (Michael, 2026-10-10): no dialog over
     * ink opens bare — a bare dialog leaves the pen live under it, the B6 freeze risk.
     */
    protected fun AlertDialog.Builder.showModal(): AlertDialog =
        create().also { this@ScreenFragment.showModal(it) }

    /**
     * The door for every dialog that COLLECTS WORK — an ink pad, a note being typed, a voice
     * recording in flight. On an e-ink slab a palm or stray finger lands outside the dialog
     * constantly, and the default tap-outside-to-dismiss throws the ink away with it. So work
     * dialogs must not die to an outside touch; the back gesture and the dialog's own ✕ /
     * Cancel / Save remain the ways out. Menus, pickers, and viewers keep [showModal] —
     * tapping outside IS how you close a menu.
     */
    fun showGuardedModal(dialog: AlertDialog) {
        dialog.setCanceledOnTouchOutside(false)
        showModal(dialog)
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
     *
     * [anchorEnd] hangs the drawer from the RIGHT edge instead — the spatial rule (Michael:
     * "have it come from the same side as the popout"): a menu opened from an edge control
     * grows from that edge, so the day rail docked right opens the hub on the right. Every
     * other door keeps the flush-left drawer.
     */
    protected fun showAccordion(folders: List<Folder>, anchorEnd: Boolean = false, hub: Boolean = true,
                                contents: Boolean = false) {
        val root = layoutInflater.inflate(R.layout.dialog_go_to, null)
        val list = root.findViewById<LinearLayout>(R.id.go_to_list)
        root.findViewById<TextView>(R.id.go_to_title).visibility = View.GONE
        val dialog = AlertDialog.Builder(requireContext()).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // THE HUB'S OWN BACK (Michael 2026-10-12, [com.toolsboox.ot.EdgeLadder]): a Back that began
        // at the LEFT edge closes the Hub AND goes back one place, arming the ladder — "Then two
        // swipes back instead of three" (iPhone 247): today open → back, a room pane → Hub → back;
        // the next left-edge Back inside three seconds goes back another place. The right edge, or
        // a Back with no edge, only closes it, as it always did. Not for a More menu drawn by this
        // renderer ([showDirectory]): closing one of those is no rung.
        // A Hub the ladder opened takes any Back but a right-edge one as the rung, in case the
        // dialog never hears the edge the page heard (review 2026-10-12) — else flicks would loop.
        val byLadder = hubByLadder
        if (hub) dialog.onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            private var edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
            override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                edge = backEvent.swipeEdge
            }
            override fun handleOnBackCancelled() {
                edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
            }
            override fun handleOnBackPressed() {
                // The Hub the left-edge flick just opened hears the same swipe as a Back (should the
                // system or xSettings make one of it): one swipe, one rung (Michael 2026-10-12, B6).
                if (com.toolsboox.ot.EdgeLadder.isEcho(SystemClock.uptimeMillis())) {
                    Timber.i("EdgeLadder: a Back on the Hub right after the left-edge flick — the same swipe, swallowed")
                    edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
                    return
                }
                val step = com.toolsboox.ot.EdgeLadder.shared.hubBack(SystemClock.uptimeMillis(), edge, byLadder)
                // Nothing behind this page (the day page you started on): the Hub closes and the
                // swipe stops there, as iOS's closeAtEnd does — a swipe that put the Hub away must
                // never also leave Ledgable. The window is armed all the same, and a flick inside it
                // with still nothing behind is kept too (the ladder's END, iOS's endOfTrail).
                val back = step == com.toolsboox.ot.EdgeLadder.HubBack.CLOSE_AND_BACK && isAdded && hasSomewhereBack()
                Timber.i("EdgeLadder: Back on the Hub from the %s edge (opened by the ladder: %s) → %s%s",
                    com.toolsboox.ot.EdgeLadder.edgeName(edge), byLadder, step,
                    if (step == com.toolsboox.ot.EdgeLadder.HubBack.CLOSE_AND_BACK && !back) " — nothing further back, the Hub only closes" else "")
                edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
                // The close lands first (synchronous on the main thread), so the Back below goes to
                // the page: the screen's own Back first (the open article closes, the Net steps back
                // a page), then the ladder — which passes an edgeless Back straight on — then the
                // nav controller. One place, exactly what a plain Back on the page would do.
                dialog.cancel()
                if (back) activity?.onBackPressedDispatcher?.onBackPressed()
            }
        })
        // A READER'S CONTENTS' OWN BACK (Michael 2026-10-12: "Content then shelf"): the book's
        // first left-edge Back opened these contents; the second — this one, from the left edge —
        // closes them AND goes back to the shelf. Any other Back only closes them.
        if (contents) dialog.onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            private var edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
            override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                edge = backEvent.swipeEdge
            }
            override fun handleOnBackCancelled() {
                edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
            }
            override fun handleOnBackPressed() {
                val back = com.toolsboox.ot.EdgeLadder.shared.contentsBack(SystemClock.uptimeMillis(), edge)
                Timber.i("EdgeLadder: Back on the contents from the %s edge → %s",
                    com.toolsboox.ot.EdgeLadder.edgeName(edge), if (back) "close and back to the shelf" else "close")
                edge = com.toolsboox.ot.EdgeLadder.EDGE_UNKNOWN
                dialog.cancel()
                // The close lands first, then one plain Back on the page: the ladder passes an
                // edgeless Back straight on — the nav controller, back to the shelf.
                if (back && isAdded) activity?.onBackPressedDispatcher?.onBackPressed()
            }
        })
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

        // ONE dial: Modal size (see showGoModal for why the menu dial no longer stacks here).
        val textScale = com.toolsboox.ot.ModalScale.sizeScale(requireContext())

        // Michael: "only one submenu in hamburger open at a time." A strict accordion — expanding
        // a folder folds whichever other folder is open (the Search·Ask·Directory group included),
        // so the drawer never becomes a wall of every submenu at once. Enforced at EXPANSION time
        // only: the initial expanded flags stay the caller's to choose (ledgerDirectoryFolders
        // opens at most one — the surface's home folder; showDirectory opens its first group).
        // Nothing persists at THIS level — a folder's fold state lives and dies with this one
        // opening of the drawer. (Second-level folds inside a folder are the opposite on both
        // counts: multi-open and remembered — see the subFolds block below.)
        val openFolds = mutableListOf<Pair<LinearLayout, () -> Unit>>()
        fun foldOthers(except: LinearLayout) {
            for ((view, fold) in openFolds) {
                if (view !== except && view.visibility == View.VISIBLE) fold()
            }
        }

        // "Find anything" — the hub's own filter field, on the same rebuilt-in-place idiom as the
        // root directory's (LedgerDirectory.showDirectoryList): no diffing, a full rebuild per
        // keystroke, which on e-ink is still cheaper than any incremental scheme. Typing swaps the
        // accordion for ONE flat list of every matching row across every folder, each captioned
        // with the folder it lives in ("Incoming · ⭐ Starred"); clearing the field puts the
        // accordion back as it opened. The root got a find field and the hub — more folders, more
        // rows — stayed a fold-and-scan; this is the same answer to the same problem.
        //
        // Same threshold as the document directory's field: on a drawer of six rows or fewer
        // (small showDirectory popovers ride this renderer too) a search box would be asking you
        // to type what you can already see. The hub is always far past it.
        val rowCount = folders.size +
            folders.sumOf { f -> f.items.size + f.subFolds.values.sumOf { it.size } }

        // ── THE LEDGER SEARCH FIELD, AT THE HEAD OF THE DRAWER ────────────────────────────────
        //
        // Michael: "Leave a search field on top for a quick search of Ledgable."
        //
        // ON TOP, LITERALLY. A [FolderField] is DECLARED by the folder it belongs to — Ask owns
        // this one, and the hub's folder list is where you go to see that — but it is RENDERED
        // here, above everything, rather than inside that folder's children. The accordion opens at
        // most one folder and Ask is usually not the one, so a field inside it would have been a
        // quick search you first had to unfold a group to find, which is not a quick search.
        //
        // ABOVE the row filter, and never merged with it: two fields with two hints, doing two
        // things you can tell apart without being told. This one SEARCHES HIS LEDGER and only on
        // submit; the one below FILTERS THESE MENU ROWS and does it as you type. One box doing both
        // would have to guess which you meant on every keystroke.
        for (spec in folders.mapNotNull { it.field }) {
            list.addView(android.widget.EditText(requireContext()).apply {
                hint = spec.hint
                isSingleLine = true
                textSize = 15f * textScale
                setPadding(dp(14), dp(8), dp(14), dp(8))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                // No text watcher, deliberately — see [FolderField]. Nothing runs until the
                // keyboard's search key, which is the one moment the corpus is worth touching.
                setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        val q = v.text?.toString()?.trim().orEmpty()
                        dialog.dismiss()
                        spec.onSubmit(q)
                        true
                    } else false
                }
            })
        }

        val field = if (rowCount <= 6) null else android.widget.EditText(requireContext()).apply {
            hint = "Find anything"; isSingleLine = true; textSize = 15f * textScale
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        field?.let { list.addView(it) }
        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        list.addView(body)

        // One flattened match: the folder's glyph in the icon slot, "Folder · row" as the label,
        // and EXACTLY the tap the row itself would take — selecting a match navigates, never folds.
        fun matchRow(emoji: String, text: CharSequence, action: () -> Unit) {
            val r = layoutInflater.inflate(R.layout.item_go_to, body, false)
            setRowEmojiIcon(r, emoji)
            r.findViewById<TextView>(R.id.go_label).apply {
                this.text = text; textSize = ROW_SP * textScale
            }
            scaleRowIcon(r, textScale)
            scaleRowPadding(r, textScale)
            r.setOnClickListener { dialog.dismiss(); action() }
            body.addView(r)
        }

        // Matching a FOLDER's name surfaces all of its rows — "incoming" lays the whole Incoming
        // folder flat — and a door row (action set) is findable by its own name, which is the only
        // name a leaf like Today has. Folder headers that merely toggle are not rows here: a match
        // list is a list of places to go, and a header goes nowhere. Rows tucked into a second-
        // level fold stay findable — captioned with the whole path ("Incoming · 📧 Mail · @ …")
        // and riding directly under their parent row, so moving a row a level deeper never costs
        // it its place in the one flat list.
        fun buildMatches(q: String) {
            var any = false
            for (folder in folders) {
                val folderHit = folder.title.lowercase().contains(q)
                if (folder.action != null && folderHit) {
                    matchRow(folder.emoji, folder.title, folder.action); any = true
                }
                for ((label, action) in folder.items) {
                    val clean = label.trim()
                    val rowHit = folderHit || clean.lowercase().contains(q)
                    if (rowHit) {
                        matchRow(folder.emoji, "${folder.title}  ·  $clean", action); any = true
                    }
                    // A parent hit surfaces its sub-rows the way a folder hit surfaces its rows —
                    // "mail" lays every account flat; a sub-row also answers for its own name.
                    for ((subLabel, subAction) in folder.subFolds[label].orEmpty()) {
                        val subClean = subLabel.trim()
                        if (!rowHit && !subClean.lowercase().contains(q)) continue
                        matchRow(folder.emoji,
                            "${folder.title}  ·  $clean  ·  $subClean", subAction); any = true
                    }
                }
            }
            if (!any) body.addView(TextView(requireContext()).apply {
                text = "Nothing matches “$q”."
                textSize = 14f * textScale; setTextColor(0xFF888888.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
        }

        fun buildAccordion() {
            openFolds.clear()
            for (folder in folders) {
                val header = layoutInflater.inflate(R.layout.item_go_to, body, false)
                val hasIcon = setRowEmojiIcon(header, folder.emoji)
                val glyph = if (!hasIcon && folder.emoji.isNotBlank()) folder.emoji else ""
                val headerLabel = header.findViewById<TextView>(R.id.go_label)
                headerLabel.textSize = ROW_SP * textScale
                scaleRowIcon(header, textScale)
                scaleRowPadding(header, textScale)

                // A leaf entry ([action], no children) is a plain tappable row — no caret, no children.
                if (folder.action != null && folder.items.isEmpty()) {
                    headerLabel.text = glyphLabel(glyph, folder.title)
                    header.setOnClickListener { dialog.dismiss(); folder.action.invoke() }
                    // Hold-to-act on the leaf itself ([Folder.hold]) — the ✒ Notes homepage's
                    // family sheet rides here.
                    folder.hold?.let { held ->
                        header.setOnLongClickListener { dialog.dismiss(); held(); true }
                    }
                    body.addView(header)
                    continue
                }

                // An outline frames the expanded dropdown for clarity — in the vibe's accent, and as
                // a SOLID: the old 40%-black stroke was exactly the translucent gray that dithers
                // into mud on e-ink.
                val outline = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), com.toolsboox.ot.LedgerTheme.accent(requireContext()))
                    cornerRadius = dp(8).toFloat()
                }
                val children = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (folder.expanded) View.VISIBLE else View.GONE
                    background = if (folder.expanded) outline else null
                    setPadding(dp(2), dp(2), dp(2), dp(4))
                }
                fun caret() = if (children.visibility == View.VISIBLE) "▾" else "▸"
                if (folder.action != null) {
                    // BOTH action and children: the label is the door (one tap = the primary
                    // destination) and ONLY the caret at the row's end unfolds the sub-rows. A header
                    // that toggled would cost the destination its one-tap reach; one that navigated
                    // with no way in would orphan the children. The caret gets its own padded target
                    // (≈44dp with the row's height) so a fold is never a mis-tapped navigation.
                    headerLabel.text = glyphLabel(glyph, folder.title)
                    header.setOnClickListener { dialog.dismiss(); folder.action.invoke() }
                    // A door header can carry a hold too ([Folder.hold]) — ✒ Notes' family
                    // sheet rides the folder row now that the folder IS the door.
                    folder.hold?.let { held ->
                        header.setOnLongClickListener { dialog.dismiss(); held(); true }
                    }
                    val caretBtn = TextView(requireContext()).apply {
                        text = caret()
                        textSize = ROW_SP * textScale
                        setTextColor(Color.BLACK)
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener {
                            val show = children.visibility != View.VISIBLE
                            if (show) foldOthers(children)
                            children.visibility = if (show) View.VISIBLE else View.GONE
                            children.background = if (show) outline else null
                            text = caret()
                        }
                    }
                    // LEADING, not trailing (Michael, field, 08-30: "Ask and Today's accordion
                    // arrows are on the wrong side") — every plain folder draws its caret at the
                    // start of the row, so the door-with-caret rows do too: one drawer, one side
                    // for every arrow. The caret keeps its own padded target; the label stays the
                    // one-tap door.
                    (header as? LinearLayout)?.addView(caretBtn, 0)
                    openFolds += children to {
                        children.visibility = View.GONE
                        children.background = null
                        caretBtn.text = caret()
                    }
                } else {
                    // MATCH ASK/TODAY (Michael, field, post -67: "The accordion drop downs
                    // don't match, but Ask and Today are better for it anyway") — every folder
                    // row wears the door-with-caret dress: a separate leading caret with its
                    // own padded target and a clean glyph+title label, instead of a caret glued
                    // into the label text. A plain folder has no destination, so the whole row
                    // still toggles — the caret is the same act, just the same look everywhere.
                    headerLabel.text = glyphLabel(glyph, folder.title)
                    val caretBtn = TextView(requireContext()).apply {
                        text = caret()
                        textSize = ROW_SP * textScale
                        setTextColor(Color.BLACK)
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    }
                    (header as? LinearLayout)?.addView(caretBtn, 0)
                    fun toggle() {
                        val show = children.visibility != View.VISIBLE
                        if (show) foldOthers(children)
                        children.visibility = if (show) View.VISIBLE else View.GONE
                        children.background = if (show) outline else null
                        caretBtn.text = caret()
                    }
                    header.setOnClickListener { toggle() }
                    caretBtn.setOnClickListener { toggle() }
                    openFolds += children to {
                        children.visibility = View.GONE
                        children.background = null
                        caretBtn.text = caret()
                    }
                }
                for ((label, action) in folder.items) {
                    val r = layoutInflater.inflate(R.layout.item_go_to, children, false)
                    val text = applyRowIcon(r, label)
                    r.findViewById<TextView>(R.id.go_label).apply {
                        this.text = text; textSize = ROW_SP * textScale
                        setPadding(dp(24), paddingTop, paddingRight, paddingBottom)
                    }
                    scaleRowIcon(r, textScale)
                    scaleRowPadding(r, textScale)
                    r.setOnClickListener { dialog.dismiss(); action() }
                    // Hold-to-act, where the folder declared one for this row ([Folder.holds]).
                    folder.holds[label]?.let { held ->
                        r.setOnLongClickListener { dialog.dismiss(); held(); true }
                    }
                    children.addView(r)

                    // A row named in [Folder.subFolds] carries its own fold: the label stays the
                    // door (one tap navigates, exactly as above) and a caret at the row's end —
                    // the same ▸/▾ the folder headers wear — toggles the sub-rows inline beneath,
                    // one indent step past the child rows'. These folds live OUTSIDE the strict
                    // accordion: foldOthers never learns about them, because they sit inside the
                    // parent folder's container and disappear with it when the accordion folds
                    // that folder — closing for free beats bookkeeping. Among themselves they are
                    // multi-open (Mail and Later List can both stand open, as on the iPad's
                    // sidebar), and each remembers its state across drawer openings the way iOS's
                    // sidebar_mail_open does — a fold you open every time you open the drawer is
                    // a fold the drawer should have remembered.
                    val subRows = folder.subFolds[label].orEmpty()
                    if (subRows.isEmpty()) continue
                    val prefs = requireContext().getSharedPreferences("MAIN", 0)
                    val prefKey = com.toolsboox.ot.HubSubFold.prefKey(label)
                    val subBox = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = if (prefs.getBoolean(prefKey, false)) View.VISIBLE else View.GONE
                    }
                    for ((subLabel, subAction) in subRows) {
                        val sr = layoutInflater.inflate(R.layout.item_go_to, subBox, false)
                        val subText = applyRowIcon(sr, subLabel)
                        sr.findViewById<TextView>(R.id.go_label).apply {
                            this.text = subText; textSize = ROW_SP * textScale
                            setPadding(dp(48), paddingTop, paddingRight, paddingBottom)
                        }
                        scaleRowIcon(sr, textScale)
                        scaleRowPadding(sr, textScale)
                        sr.setOnClickListener { dialog.dismiss(); subAction() }
                        subBox.addView(sr)
                    }
                    fun subCaret() = if (subBox.visibility == View.VISIBLE) "▾" else "▸"
                    val subCaretBtn = TextView(requireContext()).apply {
                        // Qualified: the enclosing loop's `text` (the parent row's cleaned label)
                        // shadows this TextView's property inside the lambda.
                        this.text = subCaret()
                        textSize = ROW_SP * textScale
                        setTextColor(Color.BLACK)
                        setPadding(dp(16), dp(6), dp(16), dp(6))
                        setOnClickListener {
                            // Same repaint discipline as the folder fold above: flip visibility
                            // and the caret glyph, nothing else — one relayout per toggle is the
                            // whole e-ink budget.
                            val show = subBox.visibility != View.VISIBLE
                            subBox.visibility = if (show) View.VISIBLE else View.GONE
                            prefs.edit().putBoolean(prefKey, show).apply()
                            this.text = subCaret()
                        }
                    }
                    (r as? LinearLayout)?.addView(subCaretBtn)
                    children.addView(subBox)
                }
                val childLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6), dp(1), dp(6), dp(4)) }
                body.addView(header); body.addView(children, childLp)
            }
        }

        fun redraw() {
            body.removeAllViews()
            val q = field?.text?.toString()?.trim()?.lowercase().orEmpty()
            if (q.isEmpty()) buildAccordion() else buildMatches(q)
            // Rebuilt rows are new views — re-dress them in the chosen reading face each pass.
            com.toolsboox.ot.LedgerFonts.applyTree(body)
        }
        field?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { redraw() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        redraw()

        // Menu text honours the chosen reading font too. SYSTEM is a no-op (leaves rows untouched).
        com.toolsboox.ot.LedgerFonts.applyTree(root)

        dialog.setOnShowListener { onModalShown() }
        dialog.setOnDismissListener { onModalDismissed() }
        dialog.show()
        // THE HUB CLOSES LIKE A DRAWER (Michael 2026-10-12: "When swiping right to left near the hub
        // panel it should close it"): a finger drag toward its edge, on it or beside it. Wired once
        // it is showing, over whatever window callback the dialog settled on.
        if (hub) closeOnDrawerSwipe(dialog, hubOnRight = anchorEnd)
        // Wear the reader's chosen face. The rows are built programmatically, so nothing picks the
        // app font up from a layout — without this the ☰ menu that opens off the almanac nav was
        // the one surface still speaking in the system font.
        com.toolsboox.ot.LedgerFonts.applyTree(root)
        dialog.window?.let { w ->
            // Edge drawer: flush to its edge, scrolls internally. No animation (e-ink).
            val lp = w.attributes
            lp.gravity = (if (anchorEnd) Gravity.END else Gravity.START) or Gravity.TOP
            val metrics = resources.displayMetrics
            // Flush left, but BELOW the date-nav strip AND the directory chip the making surfaces
            // hang just under it — the "❝ Board ▾" label that says which Pickings board (or Write/
            // Synthesize/Grid/Jot document) you are standing in. The old fixed 64dp was tuned for
            // the phone's 50dp strip; the strip is 60dp on sw600 and 76dp on sw900, so on a Boox
            // the drawer's top edge landed inside the chip's band (strip bottom + ~30dp) on every
            // bucket and opening the ▦ hub covered the page's own label. MEASURED now, not summed
            // from constants: the chip anchors itself under the template's own header rect (see
            // CalendarDayFragment.bindDirectoryChip), so its band is no longer at a knowable fixed
            // offset — the drawer asks the chip where it actually is and opens beneath it, falling
            // back to the strip-plus-band arithmetic on surfaces that have no chip on screen.
            lp.x = 0
            lp.y = run {
                val chip = activity?.findViewById<android.view.View>(R.id.directoryChip)
                if (chip != null && chip.visibility == View.VISIBLE && chip.height > 0) {
                    val loc = IntArray(2)
                    chip.getLocationInWindow(loc)
                    loc[1] + chip.height + dp(6)
                } else {
                    resources.getDimensionPixelSize(R.dimen.ledger_navigator_height) + dp(36)
                }
            }
            // Grows with the text, like the other two menus. Pinned, the labels simply clipped:
            // item_go_to rows are single-line and ellipsized, so raising the size made the words
            // shorter rather than bigger and the setting looked like it did nothing at all.
            lp.width = minOf(dp((ACCORDION_DP * textScale).toInt()), (metrics.widthPixels * 0.66f).toInt())
            // As TALL as it needs to be, not as tall as the screen.
            //
            // This was `screenHeight - 64dp` regardless of contents, so a six-item directory came
            // up as a full-height sheet with most of it empty — fine on a phone, where the screen
            // is short, and absurd on a Tab X. It still scrolls internally when there IS a lot,
            // and it never grows past most of the panel.
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            w.setLayout(lp.width, lp.height)
            w.attributes = lp
        }
    }

    /**
     * Is there a place behind this page for the Hub's close-and-back (or a flick inside the
     * ladder's window) to go to — the screen's own Back (an open article, the Net's page trail), a
     * nav entry behind it, or a fragment on the activity's stack? False on the day page you started
     * on, where a plain Back leaves Ledgable.
     */
    private fun hasSomewhereBack(): Boolean {
        if (ownBackView != null && ownBackView === getView() && ownBackCallbacks.any { it.isEnabled }) return true
        val nav = runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this) }.getOrNull()
        if (nav?.previousBackStackEntry != null) return true
        return (activity?.supportFragmentManager?.backStackEntryCount ?: 0) > 0
    }

    /**
     * THE HUB AS A DRAWER (Michael 2026-10-12, iPhone 247: "When swiping right to left near the hub
     * panel it should close it"). The Hub is a dialog window hung from one edge, and a modal window
     * takes every touch on the screen — on its rows and beside them — so its window callback sees
     * the whole drag.
     *
     * CLAIMED AT THE SLOP (review 2026-10-12): the rows are full width, and Android cancels neither
     * a tap nor a hold while the finger stays inside its view — so a short swipe ran the row under
     * it on the lift, and a slow one (the B6's) fired its hold on the way. Now the drag is decided
     * once, at the touch slop ([com.toolsboox.ot.EdgeLadder.drawerDrag]): toward the Hub's own edge
     * and clearly sideways, it is the Hub's — the row gets one CANCEL there and nothing after, so
     * no tap and no hold. It shuts on iOS's numbers (EdgeBack.HubShut): mid-drag at the distance,
     * or on the lift past it or on a throw; released short, it does nothing (iOS springs back).
     * A tap, a scroll of the rows, a drag away from the edge, or a drag that starts in a find field
     * (moving the cursor) pass through untouched; a second finger on a claimed drag stops it
     * closing. A close only — no rung, no window, no back: Back is the ladder's. E-ink: one
     * dismiss, no animation frames; the rest of the drag reaches no window, so nothing lands on the
     * page under it.
     *
     * THE LEFT EDGE'S FLICK ON THE HUB ITSELF (Michael 2026-10-12, on the B6: "The b6 doesn't have
     * the left right working for bring up the hub it seems rn"): over an ink page the system makes
     * no Back of a swipe in from the left edge even on this dialog (probed: Bigme's xSettings hears
     * it, nothing more), so the Hub's own Back — close AND back a place, the ladder's rung — never
     * came. The Hub's window carries the same in-app catcher as the activity's
     * ([com.toolsboox.ot.LeftEdgeCatcher]), asked before the drawer: a quick level flick from the
     * left edge is that rung (it fires at 50 dp, before the drawer's 90 dp close, so with the Hub
     * hung on the right the flick is the rung and a slow drag toward it still only puts it away).
     * Where the system does take the edge it cancels the touch first, and its Back is the same rung.
     */
    private fun closeOnDrawerSwipe(dialog: AlertDialog, hubOnRight: Boolean) {
        val window = dialog.window ?: return
        val inner = window.callback ?: return
        val density = resources.displayMetrics.density
        val distance = com.toolsboox.ot.EdgeLadder.DRAWER_SWIPE_DP * density
        val slop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()
        window.callback = object : android.view.Window.Callback by inner {
            private var downX = 0f
            private var downY = 0f
            private var drag = com.toolsboox.ot.EdgeLadder.DrawerDrag.LEAVE
            /** The claimed drag may no longer close (a second finger came down on it). */
            private var spent = false
            private var velocity: android.view.VelocityTracker? = null

            /** The left edge's flick on the Hub: the Hub's Back from the left — close and back. */
            private val edgeFlick = com.toolsboox.ot.LeftEdgeCatcher(
                where = "the Hub",
                density = density,
                dispatcher = { dialog.onBackPressedDispatcher },
                deliver = { e -> inner.dispatchTouchEvent(e) },
                landing = { x, y ->
                    com.toolsboox.ot.LeftEdgeCatcher.Landing(standAside = inTextField(window.decorView, x, y) ||
                        com.toolsboox.ot.LeftEdgeCatcher.standsAside(
                            com.toolsboox.ot.LeftEdgeCatcher.hitChain(window.decorView, x, y)))
                },
                // The Hub's Back closes it (and goes back a place), so an echo of this swipe would
                // reach the activity's window: guard that one as the page's catcher does (review of
                // f0035e3b, 2026-10-12).
                afterFire = { (activity as? MainActivity)?.guardEdgeEcho() }
            )

            private fun reset() {
                drag = com.toolsboox.ot.EdgeLadder.DrawerDrag.LEAVE
                spent = false
                velocity?.recycle()
                velocity = null
            }

            private fun close(how: String) {
                reset()
                Timber.i("EdgeLadder: the Hub put away by a swipe toward the %s edge (%s)",
                    if (hubOnRight) "right" else "left", how)
                dialog.cancel()
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // The flick first: once made it is the ladder's rung, and the drawer forgets the drag.
                if (edgeFlick.onTouch(event)) { reset(); return true }
                val claimed = drag == com.toolsboox.ot.EdgeLadder.DrawerDrag.CLAIM
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        reset()
                        downX = event.rawX
                        downY = event.rawY
                        if (!inTextField(window.decorView, event.rawX, event.rawY)) {
                            drag = com.toolsboox.ot.EdgeLadder.DrawerDrag.UNDECIDED
                            velocity = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (claimed) { spent = true; return true }
                        drag = com.toolsboox.ot.EdgeLadder.DrawerDrag.LEAVE
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (claimed) {
                            velocity?.addMovement(event)
                            if (!spent && com.toolsboox.ot.EdgeLadder.drawerSwipeCloses(dx, dy, hubOnRight, distance)) close("past the distance")
                            return true
                        }
                        if (drag == com.toolsboox.ot.EdgeLadder.DrawerDrag.UNDECIDED) {
                            velocity?.addMovement(event)
                            drag = com.toolsboox.ot.EdgeLadder.drawerDrag(dx, dy, hubOnRight, slop)
                            if (drag == com.toolsboox.ot.EdgeLadder.DrawerDrag.CLAIM) {
                                // The row under the finger lets go here, before its tap or hold.
                                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                                inner.dispatchTouchEvent(cancel)
                                cancel.recycle()
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (claimed) {
                        val v = velocity
                        v?.addMovement(event)
                        v?.computeCurrentVelocity(1000)
                        if (!spent && com.toolsboox.ot.EdgeLadder.drawerReleaseCloses(event.rawX - downX, event.rawY - downY,
                                v?.xVelocity ?: 0f, hubOnRight, density)) close("on the lift")
                        else reset()   // short of it: nothing, the Hub stays (iOS springs back)
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> if (claimed) { reset(); return true }
                    else -> if (claimed) return true
                }
                return inner.dispatchTouchEvent(event)
            }
        }
    }

    /** A visible text field under the screen point ([x], [y]) in [v]'s tree. */
    private fun inTextField(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is android.widget.EditText) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (inTextField(v.getChildAt(i), x, y)) return true
        return false
    }

    /**
     * A directory popover (top-left, iPad-style) grouping labelled rows under headers —
     * used for the hamburger menus on Bookshelf/Feed to list the actual books/feeds plus
     * the surfaces to jump to. Each row is (label, action).
     */
    /**
     * Grouped directory — ONE visual language AND one doctrine with the accordion: each named
     * group becomes a collapsible Folder (▸/▾), headerless groups flatten to plain rows, and
     * only the FIRST named group opens. Every group used to come up spread, which made these
     * popovers the one place the one-open accordion rule didn't hold — a directory door that
     * opened as a wall of every submenu at once. Now the caller's first (most relevant) group
     * stands open and the rest wait behind their carets, exactly like the ▦ hub.
     *
     * [subFolds] hands a row a real second-level fold, keyed by that row's exact drawn label —
     * the same shape as [Folder.subFolds], so a directory can nest the way the hub does
     * (menu → group → sub-fold → leaves). One flat map for the whole call because row labels
     * are already the identity the fold state hangs on (see HubSubFold); each folder takes only
     * the entries naming its own rows, so the accordion's row count stays honest.
     */
    protected fun showDirectory(
        groups: List<Pair<String, List<Pair<String, () -> Unit>>>>,
        subFolds: Map<String, List<Pair<String, () -> Unit>>> = emptyMap(),
        /** This directory is a READER'S CONTENTS — the left edge's first rung on the book (Michael
         *  2026-10-12: "Content then shelf"): its own left-edge Back closes it and goes back to the
         *  shelf ([com.toolsboox.ot.EdgeLadder.Ladder.contentsBack]). */
        contents: Boolean = false,
    ) {
        val folders = mutableListOf<Folder>()
        var firstGroup = true
        for ((header, items) in groups) {
            if (header.isEmpty()) {
                items.forEach { (label, action) -> folders.add(Folder("", label, action = action)) }
            } else {
                folders.add(Folder(
                    "", header, items, expanded = firstGroup,
                    subFolds = subFolds.filterKeys { key -> items.any { it.first == key } }
                ))
                firstGroup = false
            }
        }
        showAccordion(folders, hub = false, contents = contents)
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
            val builder: AlertDialog.Builder =
                AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(this.requireContext()))
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
