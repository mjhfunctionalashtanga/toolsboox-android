package com.toolsboox.plugin.feeds.ot

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

/**
 * The article pane's web view, with the Ledger's verbs on the long-hold selection menu —
 * **Highlight**, **Note**, **Search Ledger** — beside the system's Copy / Share (the iOS
 * reader's `SelectableWebView` menu, on Android; Michael, 09-11: "highlight, make pick, and
 * search ledger are great long-hold highlighted text options… would love that for android too").
 *
 * NO "MAKE PICK" (Michael 2026-10-12, looking at [Highlight | Make pick | Search Ledger ›]: "Not sure
 * we need the highlight and make a pick here. Highlight should make a gram pick anyway."): Highlight
 * keeps the passage in one tap AND places its one gram pick (ArticleHighlights). Note — the
 * approved plan's second verb — opens the chooser with the passage in hand; it stands where Make
 * pick stood, so nothing is pushed into the overflow on the B6 (design critique #11).
 *
 * The WebView starts its own ActionMode for a text selection; overriding `startActionMode` lets
 * the callback be wrapped so the three items are added to whatever menu it builds, and a tap on
 * one of them reaches [onSelectionAction] with the verb — the fragment then reads the selection
 * through [WebSelectionBridge] (which also carries the links inside it) and opens the chooser or
 * the search. The mode is finished after the tap so the page's selection collapses as it does for
 * Copy.
 */
class SelectionMenuWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    enum class Verb { HIGHLIGHT, NOTE, SEARCH }

    /** The fragment's handler for a tapped verb; null = the system menu alone. */
    var onSelectionAction: ((Verb) -> Unit)? = null

    /**
     * A PLAIN TAP on the page — one finger, no drag, no second tap, not on a link, a field, a
     * player, a button or anything else the page answers itself, and not while a selection is up —
     * with where it landed as a fraction of the view's width. The article readers' controls tap
     * (Michael 2026-10-12: full screen in a reader leaves nothing on the page, and the centre tap
     * brings the ⛶ back — com.toolsboox.ot.ReaderFullScreen). Only watched: the page still gets
     * every touch, so its links, selection, double-tap zoom and scrolling are as they were.
     * Confirmed after the double-tap window, so a double-tap zoom never counts. Null = not
     * listened for.
     */
    var onPlainTap: ((xFraction: Float) -> Unit)? = null

    /** A finger came down on the page (true) or the last one left it (false) — what tells a
     *  finger's scroll from the reader's own ([com.toolsboox.ot.ReaderFullScreen.ScrollTurn]).
     *  Only watched, like [onPlainTap]. */
    var onFinger: ((down: Boolean) -> Unit)? = null

    /** A selection's action mode is up — a tap then only puts the selection down. */
    private var selecting = false

    /** The pending end of [selecting], cancelled when a new selection starts inside its window. */
    private val selectingSettled = Runnable { selecting = false }

    private val tapWatch = android.view.GestureDetector(context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (onPlainTap == null) return false
                if (selecting || width <= 0 || height <= 0) return false
                when (hitTestResult?.type) {
                    HitTestResult.SRC_ANCHOR_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                    HitTestResult.EDIT_TEXT_TYPE -> return false
                }
                val fx = e.x / width
                val fy = e.y / height
                // WHAT THE PAGE ANSWERS ITSELF (review 2026-10-12): the hit test knows links and
                // fields only — a video's controls, a YouTube player (an iframe), a chapter row
                // (an onclick div), a button, a <summary> all read as plain page to it, so play /
                // pause in full screen popped the pills over the video. The page is asked what
                // lies under the tap; no answer (scripts off, an error) keeps the plain tap.
                runCatching {
                    evaluateJavascript(plainTapJs(fx, fy)) { r ->
                        if (r != "false") onPlainTap?.invoke(fx)
                    }
                }.onFailure { onPlainTap?.invoke(fx) }
                return false
            }
        })

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (onPlainTap != null) runCatching { tapWatch.onTouchEvent(ev) }
        onFinger?.let { f ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> runCatching { f(true) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    runCatching { f(false) }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The custom accessibility action standing in for the centre tap (TalkBack has no centre to
     *  find), or [android.view.View.NO_ID]. */
    private var controlsActionId = android.view.View.NO_ID

    /**
     * A NAMED WAY BACK for TalkBack (review 2026-10-12): in a reader's full screen nothing is on
     * the page and the only way back is a tap in its middle, which a screen reader can't aim at.
     * [action] is the reader's middle tap (into full screen from normal reading, the controls in
     * it); null takes the action away. Replaces any earlier one.
     */
    fun setControlsAction(action: (() -> Unit)?) {
        if (controlsActionId != android.view.View.NO_ID)
            androidx.core.view.ViewCompat.removeAccessibilityAction(this, controlsActionId)
        controlsActionId = android.view.View.NO_ID
        if (action == null) return
        controlsActionId = androidx.core.view.ViewCompat.addAccessibilityAction(
            this, CONTROLS_ACTION_LABEL) { _, _ -> action(); true }
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private fun wrap(inner: ActionMode.Callback?): ActionMode.Callback? {
        // Wrapped even with no handler (2026-10-10, C9): the page's own menu must never list
        // Ledgable's "Ledgable" text door beside ❝ Save highlight — and Chromium refills its
        // text-processing items on prepare, so they're hidden after BOTH create and prepare.
        if (inner == null) return inner
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // A new selection inside the last one's settle window keeps the flag up.
                removeCallbacks(selectingSettled)
                selecting = true
                val ok = inner.onCreateActionMode(mode, menu)
                com.toolsboox.ui.share.OwnProcessText.strip(menu, context)
                if (onSelectionAction == null) return ok
                // Ours FIRST: the verbs you came for, ahead of Copy / Share / Select all.
                menu.add(Menu.NONE, ID_HIGHLIGHT, 0, "Highlight")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, ID_NOTE, 1, "Note")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, ID_SEARCH, 2, "Search Ledger")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return ok
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val ok = inner.onPrepareActionMode(mode, menu)
                com.toolsboox.ui.share.OwnProcessText.strip(menu, context)
                return ok
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val verb = when (item.itemId) {
                    ID_HIGHLIGHT -> Verb.HIGHLIGHT
                    ID_NOTE -> Verb.NOTE
                    ID_SEARCH -> Verb.SEARCH
                    else -> null
                }
                if (verb == null) return inner.onActionItemClicked(mode, item)
                // Read the selection BEFORE the mode finishes — finishing collapses it. The
                // handler's probe is a JS round trip, so the finish is POSTED a beat later
                // rather than run on the same tick: run at once, the selection was gone before
                // the probe's script ever executed (09-13, "doesn't immediately give me the
                // highlighted area").
                onSelectionAction?.invoke(verb)
                postDelayed({ runCatching { mode.finish() } }, 350)
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                // The tap that puts a selection down lands after this — let it pass first.
                removeCallbacks(selectingSettled)
                postDelayed(selectingSettled, SELECTION_SETTLE_MS)
                inner.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }
    }

    private companion object {
        const val ID_HIGHLIGHT = 0x4C01
        const val ID_NOTE = 0x4C02
        const val ID_SEARCH = 0x4C03
        /** How long after a selection's menu closes a tap still counts as putting it down. */
        const val SELECTION_SETTLE_MS = 600L

        /** TalkBack's name for the middle tap ([setControlsAction]): in normal reading it goes into
         *  full screen, in full screen it shows or hides the controls (review 2026-10-12). */
        const val CONTROLS_ACTION_LABEL = "Full screen, or show the controls"

        /** What lies under a tap at ([fx], [fy]) of the view, asked of the page: false when it is
         *  something the page answers itself (or a selection is up), true for plain page. In the
         *  visual viewport, so a pinch-zoomed page asks about the spot under the finger. */
        fun plainTapJs(fx: Float, fy: Float): String = """(function(fx,fy){try{
            var v=window.visualViewport,x=v?v.offsetLeft+fx*v.width:fx*innerWidth,
                y=v?v.offsetTop+fy*v.height:fy*innerHeight;
            var s=window.getSelection&&getSelection();
            if(s&&!s.isCollapsed&&String(s).trim())return false;
            var el=document.elementFromPoint(x,y);
            if(!el||!el.closest)return true;
            return !el.closest('a[href],button,input,select,textarea,label,summary,video,audio,iframe,object,embed,[onclick],[role=button],[role=link],[contenteditable=""],[contenteditable=true]');
        }catch(e){return true}})($fx,$fy)"""
    }
}
