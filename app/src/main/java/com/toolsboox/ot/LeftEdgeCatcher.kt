package com.toolsboox.ot

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.toolsboox.R
import timber.log.Timber

/**
 * THE LEFT EDGE, CAUGHT IN-APP (Michael 2026-10-12, on the B6 on -322: "The b6 doesn't have the left
 * right working for bring up the hub it seems rn"). Android's twin of iOS's LeftEdgeDrawerCatcher /
 * LeftEdgeFlickRecognizer (ledger-ipad PageSkipper.swift): the swipe in from the left edge, made by a
 * finger, recognised by the app itself wherever the system never sees it — and handed to the very
 * callbacks a system Back from the left edge reaches, so [EdgeLadder]'s rungs (room pane → Hub →
 * back, the 3 s window, the end that never leaves Ledgable) run unchanged.
 *
 * WHY (probed on his B6, [EdgeLadder.Bar]): every ink page claims its whole surface from the back
 * gesture so the pen can write in the margins, and with the bars hidden Android honours all of it;
 * the Hub's dialog over such a page gets no system Back either. There a flick from x = 1…32 px
 * arrived as a plain drag and nothing happened. On the Feeds list, where nothing claims the edge,
 * the system's own band (x < 56 px) still takes the swipe — it cancels the touch here after 24–40 px
 * of travel, long before this catcher's 50 dp — so the two never both answer one swipe.
 *
 * WHAT IT TAKES — one finger, never the pen (the pen's ink comes through the Bigme daemon or as
 * stylus events, neither of them looked at here), landing within its bar's zone of the screen's
 * left edge, moving right far enough, quickly enough, levelly enough ([EdgeLadder.Bar.judge]).
 * The bar is picked at touch-down: a page the finger INKS gets the bezel bar (a finger stroke out
 * of the margin stays a stroke), anything else the edge-swipe bar, each a little wider while the
 * ladder is armed. It stands aside at touch-down for a floating pill (its drag is a move, tagged
 * [R.id.tag_edge_stand_aside]) and for a sideways scroller that can still scroll back (that drag
 * is the scroller's) — iOS's two exceptions.
 *
 * SIMULTANEOUS, NEVER DELAYED: every event goes on to the views as it comes, so a tap on a seat of
 * a left-docked panel or the tucked strip lands at once, and a stroke or a scroll that starts
 * further in is never touched. Only when the flick is made does it act, mid-move as iOS does:
 * [beforeFire] (drop a finger stroke under way, so its CANCEL commits no tick), one CANCEL to the
 * views (the page's day swipe, judged at the lift, never hears it end; the row under the finger
 * lets go), then Back from the LEFT edge on [dispatcher] — dispatchOnBackStarted(EDGE_LEFT) and
 * onBackPressed, the order a system swipe takes — and the rest of the touch goes nowhere. One swipe,
 * one rung: should anything also make a Back of the same swipe, [EdgeLadder.isEcho] swallows it.
 */
class LeftEdgeCatcher(
    /** Said in the log ("the page", "the Hub"). */
    private val where: String,
    /** px per dp. */
    private val density: Float,
    /** Where the catcher's Back goes — the activity's dispatcher, or the Hub dialog's. */
    private val dispatcher: () -> androidx.activity.OnBackPressedDispatcher?,
    /** Delivers an event to the window's views (the CANCEL, when the flick is made). */
    private val deliver: (MotionEvent) -> Unit,
    /** What the finger landed on (screen px) — asked once, at a touch-down inside the widest zone. */
    private val landing: (rawX: Float, rawY: Float) -> Landing = { _, _ -> Landing() },
    /** Just before the views hear the CANCEL — drop a finger stroke under way. */
    private val beforeFire: () -> Unit = {},
    /** Just after the Back was handed on — guard the window against an echo of it. */
    private val afterFire: () -> Unit = {},
) {

    /** What a touch-down in the zone landed on. */
    data class Landing(
        /** A page the finger inks: the bezel bar. */
        val inks: Boolean = false,
        /** A floating pill or a sideways scroller that can still scroll back: not this gesture. */
        val standAside: Boolean = false,
    )

    private var tracking = false
    /** Followed only to say why it missed: it landed outside its bar's zone, the ladder armed. */
    private var watchOnly = false
    /** The flick was made: the rest of this touch goes nowhere. */
    private var swallowing = false
    private var armed = false
    private var bar = EdgeLadder.Bar.OFF_INK
    private var downX = 0f
    private var downY = 0f

    /** One event of the window's touch stream. True: the catcher took it — don't deliver it. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A new touch: a Back from here on is this touch's, not the last flick's echo.
                EdgeLadder.touchDown()
                swallowing = false
                tracking = false
                begin(ev)
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger (a pinch, a two-finger swipe) is never this gesture.
                if (swallowing) return true
                tracking = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (swallowing) return true
                if (!tracking) return false
                val dx = (ev.rawX - downX) / density
                val dy = (ev.rawY - downY) / density
                val elapsed = ev.eventTime - ev.downTime
                val v = bar.judge(dx, dy, elapsed)
                when (v.kind) {
                    EdgeLadder.Bar.Verdict.Kind.UNDECIDED -> return false
                    EdgeLadder.Bar.Verdict.Kind.MISS -> {
                        tracking = false
                        if (!watchOnly) sayMiss(v.miss ?: EdgeLadder.Bar.Miss.TOO_SLOW, dx, dy, elapsed)
                        return false
                    }
                    EdgeLadder.Bar.Verdict.Kind.FLICK -> {
                        tracking = false
                        if (watchOnly) {
                            // It would have been the flick further back but for where it landed.
                            sayMiss(EdgeLadder.Bar.Miss.OUTSIDE_ZONE, dx, dy, elapsed)
                            return false
                        }
                        swallowing = true
                        fire(ev, elapsed)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swallowing) {
                    // The lift is when the system or xSettings would send their Back of this swipe:
                    // the echo window runs ECHO_MS from here (review of f0035e3b, 2026-10-12).
                    swallowing = false
                    val now = SystemClock.uptimeMillis()
                    EdgeLadder.catcherLifted(now)
                    Timber.i("EdgeLadder: the left-edge flick lifted on %s, %d ms after it fired — an echo is swallowed for %d ms more",
                        where, now - EdgeLadder.catcherFiredAt, EdgeLadder.ECHO_MS)
                    return true
                }
                if (tracking && !watchOnly) {
                    sayMiss(EdgeLadder.Bar.Miss.LIFTED_SHORT, (ev.rawX - downX) / density,
                        (ev.rawY - downY) / density, ev.eventTime - ev.downTime)
                }
                tracking = false
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                // The system took it (its own back band where nothing claims the edge), or the
                // window went: either way, not ours.
                tracking = false
                if (swallowing) {
                    // …after the flick fired: the system judged the same swipe late and will send
                    // its own Back at a lift this window never sees — held open for it, shut by
                    // the next touch-down (review of f0035e3b, 2026-10-12).
                    swallowing = false
                    EdgeLadder.catcherOverlapped(SystemClock.uptimeMillis())
                    Timber.i("EdgeLadder: the left-edge flick on %s was cancelled after it fired — a Back of the same swipe will be swallowed", where)
                    return true
                }
                return false
            }
            else -> return swallowing
        }
    }

    private fun begin(ev: MotionEvent) {
        if (ev.pointerCount != 1 || ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        val now = SystemClock.uptimeMillis()
        armed = EdgeLadder.shared.isArmed(now)
        val xDp = ev.rawX / density
        if (xDp > EdgeLadder.Bar.firstCutDp(armed)) return
        val land = runCatching { landing(ev.rawX, ev.rawY) }.getOrElse { Landing() }
        if (land.standAside) return
        bar = EdgeLadder.Bar.pick(land.inks, armed)
        watchOnly = !bar.lands(xDp)
        if (watchOnly && !armed) return
        downX = ev.rawX
        downY = ev.rawY
        tracking = true
    }

    // dispatchOnBackStarted is marked @VisibleForTesting in androidx.activity 1.11.0 (pinned exactly
    // in app/build.gradle) — the one public way to hand a dispatcher the edge a Back began at, which
    // the ladder's handleOnBackStarted reads. If activity moves to its navigation-event system and
    // drops it, the ladder would hear an edgeless Back (a plain back): re-check this on that bump
    // (review of f0035e3b, 2026-10-12).
    @android.annotation.SuppressLint("VisibleForTests")
    private fun fire(ev: MotionEvent, elapsed: Long) {
        val now = SystemClock.uptimeMillis()
        EdgeLadder.catcherFired(now)
        Timber.i("EdgeLadder: left-edge flick caught on %s — %s (landed x %d px, +%d px across in %d ms%s) → Back from the left edge",
            where, bar.name, downX.toInt(), (ev.rawX - downX).toInt(), elapsed, if (armed) ", ladder armed" else "")
        runCatching { beforeFire() }.onFailure { Timber.w(it, "EdgeLadder: dropping the finger's stroke failed") }
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        try { deliver(cancel) } finally { cancel.recycle() }
        val d = dispatcher() ?: return
        EdgeLadder.catcherDispatching = true
        try {
            d.dispatchOnBackStarted(androidx.activity.BackEventCompat(downX, downY, 0f,
                androidx.activity.BackEventCompat.EDGE_LEFT))
            d.onBackPressed()
        } finally {
            EdgeLadder.catcherDispatching = false
        }
        runCatching { afterFire() }
    }

    /** One line for a rightward touch from the edge that wasn't the swipe back — armed only (every
     *  margin stroke would otherwise write one), as iOS's sayMiss. */
    private fun sayMiss(why: EdgeLadder.Bar.Miss, dx: Float, dy: Float, elapsed: Long) {
        if (!armed || dx < MISS_WORTH_SAYING_DP) return
        Timber.i("EdgeLadder: back flick missed on %s (ladder armed) — %s: landed x %d px, +%d dp across / %d dp down in %d ms; bar %s (%d dp, %d dp in %d ms)",
            where, why.why, downX.toInt(), dx.toInt(), kotlin.math.abs(dy).toInt(), elapsed, bar.name,
            bar.zoneDp.toInt(), bar.travelDp.toInt(), bar.windowMs)
    }

    companion object {
        /** The least rightward travel that makes a miss worth a log line (iOS: 20 pt). */
        private const val MISS_WORTH_SAYING_DP = 20f

        /**
         * The views under the screen point ([rawX], [rawY]) from [root] down — at each level the
         * visible child on top there (highest Z, then the last drawn) — the chain a touch-down
         * there travels.
         */
        fun hitChain(root: View, rawX: Float, rawY: Float): List<View> {
            val out = ArrayList<View>()
            var v: View? = root
            while (v != null) {
                out += v
                val g = v as? ViewGroup ?: break
                var top: View? = null
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.visibility != View.VISIBLE || !contains(c, rawX, rawY)) continue
                    if (top == null || c.z >= top.z) top = c
                }
                v = top
            }
            return out
        }

        /** Is the screen point ([rawX], [rawY]) on [v], shown? Asked of the ink view itself rather
         *  than through [hitChain], so a transparent view above the page can't hide the ink from the
         *  bar (review of f0035e3b, 2026-10-12). */
        fun isOn(v: View, rawX: Float, rawY: Float): Boolean = v.isShown && contains(v, rawX, rawY)

        private fun contains(v: View, rawX: Float, rawY: Float): Boolean {
            if (v.width <= 0 || v.height <= 0) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return rawX >= loc[0] && rawX < loc[0] + v.width && rawY >= loc[1] && rawY < loc[1] + v.height
        }

        /** A floating pill being taken by the finger, or a sideways scroller that can still scroll
         *  back (the tag strip, a pager past its first page, a wide web page): their drag. */
        fun standsAside(chain: List<View>): Boolean =
            chain.any { it.getTag(R.id.tag_edge_stand_aside) == true || it.canScrollHorizontally(-1) }
    }
}
