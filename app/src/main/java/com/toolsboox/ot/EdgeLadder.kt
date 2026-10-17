package com.toolsboox.ot

/**
 * THE LEFT EDGE'S BACK LADDER on Android (Michael 2026-10-12: "Those too." — Hub and Feeds off every
 * panel — then "Make sure we can get to hub with back swipe in the reader or touch or something as
 * needed" → plan → "Awesome"). The twin of iOS `EdgeBack.Ladder`, run on BACK WITH AN EDGE: where
 * nothing claims the left edge (the Feeds list) a flick in from it is system Back (gesture
 * navigation, a 56px band down both edges on the B6), and the activity is opted in to the edge
 * (the manifest's enableOnBackInvokedCallback; activity-ktx hands an enabled OnBackPressedCallback
 * the swipe's edge in handleOnBackStarted → BackEventCompat.swipeEdge). Where the page claims the
 * edge from the system — every ink page, so the pen can write in the margin — the system never
 * sees the flick, and the in-app [LeftEdgeCatcher] (finger only) hands the same Back-from-the-left
 * to the same callbacks instead (Michael 2026-10-12, on the B6: "The b6 doesn't have the left
 * right working for bring up the hub it seems rn"; see [Bar]). A pen stroke written out of the
 * margin stays a stroke either way.
 *
 * One Back that started at the LEFT edge climbs one rung:
 *  - the BOOK READER: its contents first, then back to the shelf (Michael 2026-10-12: "Content then
 *    shelf") — the reader's pane is its table of contents ([Pane.SHUT] → [Rung.OPEN_PANE]); the
 *    contents' own left-edge Back closes it AND goes back to the shelf ([contentsBack], no window —
 *    the shelf's next flick opens the Hub, its own first rung); then the Hub, as everywhere;
 *  - any other READER (the standalone article; the in-room article and letter are closed by the
 *    Feeds room's own Back first): plain Back — back to the list or the shelf (the 09-11 rule);
 *  - a ROOM whose pane is SHUT (Feeds, The Mail): the pane opens ("First swipe back email stuff");
 *  - a room whose pane is SHOWING, and every other panel: the HUB opens ("second swipe back main
 *    pane"); Android's pane stands BESIDE the list, so it stays where it is under the Hub;
 *  - the Hub showing: Back closes it AND goes back one place, arming the ladder for [WINDOW_MS]
 *    ([Ladder.hubBack]) — Michael 2026-10-12 on iPhone 247: "Then two swipes back instead of
 *    three." The old "close" rung is gone: today was open → close → back, now open → back; a room
 *    was pane → Hub → close → back, now pane → Hub → back. With nothing behind the page (the day
 *    page you started on) the Hub only closes — iOS's closeAtEnd — so putting the Hub away never
 *    also leaves Ledgable (ScreenFragment.hasSomewhereBack);
 *  - inside that window: plain Back, one place, re-arming the window — so "back → back" walks the
 *    back stack the way iOS's trail does. With nothing further back the flick is KEPT ([Rung.END],
 *    iOS's endOfTrail): the window's flicks walk to the start and stop there, never out of Ledgable
 *    (review 2026-10-12 — before, the flick after closeAtEnd left the app). Let the window pass and
 *    the next flick opens the pane or the Hub again.
 * A Back from the RIGHT edge, a Back with no edge (3-button navigation; Android 11–13, where no
 * BackEvent is sent; a device that reports EDGE_NONE), and a programmatic Back are plain Back,
 * always — on the open Hub that is a plain close, nothing more; there the touch route is More ›
 * Hub, the first row of every panel's More.
 *
 * THE HUB ALSO CLOSES LIKE A DRAWER (Michael 2026-10-12: "When swiping right to left near the hub
 * panel it should close it"): a finger drag toward the edge the Hub hangs from, on it or beside it,
 * puts it away — [drawerDrag] claims it at the touch slop (the row under the finger is cancelled,
 * so a short or slow swipe never runs a row or its hold), [drawerSwipeCloses] / [drawerReleaseCloses]
 * shut it on iOS's numbers (EdgeBack.HubShut). That is a close only: no rung, no window, no back.
 *
 * Pure (ints for the edge, so the JVM test needs no Android): EdgeLadderTest holds the rungs.
 */
object EdgeLadder {

    /** How long after the Hub's Back (or a ladder step back) the next left-edge Back still goes
     *  back — iOS's `EdgeBack.window`, 3 s. */
    const val WINDOW_MS = 3_000L

    /** BackEventCompat's edges, as ints ([UNKNOWN]: no BackEvent arrived for this Back). */
    const val EDGE_LEFT = 0
    const val EDGE_RIGHT = 1
    const val EDGE_NONE = 2
    const val EDGE_UNKNOWN = -1

    fun edgeName(edge: Int): String = when (edge) {
        EDGE_LEFT -> "left"
        EDGE_RIGHT -> "right"
        EDGE_NONE -> "none"
        else -> "unreported"
    }

    /** The room's pane as the ladder sees it — read live by the screen. */
    enum class Pane {
        /** No pane here: every panel but Feeds and The Mail. */
        NONE,
        /** The room has a pane and it is shut — the first left-edge Back opens it. */
        SHUT,
        /** The pane is showing — the next left-edge Back opens the Hub beside it. */
        SHOWING,
    }

    /** What one Back on a PAGE does (the open Hub's own Back is [HubBack]). */
    enum class Rung {
        /** Plain Back: the right edge, no edge, a reader's first step. */
        PLAIN,
        /** The room's pane opens (Feeds drawer, Mail drawer). */
        OPEN_PANE,
        /** The Hub opens over the page. */
        HUB,
        /** Inside the window after the Hub's Back: plain Back, one place, the window re-armed. */
        BACK,
        /** Inside the window with nothing further back: the Back is KEPT — nothing happens, the
         *  window re-armed (iOS's `.endOfTrail`). Never a way out of Ledgable. */
        END,
    }

    /** What one Back on the OPEN HUB does. */
    enum class HubBack {
        /** A plain close — the right edge, no edge, a Hub opened by touch and closed edgelessly. */
        CLOSE,
        /** The ladder's rung: the Hub closes AND the page goes back one place, the window armed
         *  ("two swipes back instead of three"). */
        CLOSE_AND_BACK,
    }

    /** The ladder's memory: only the window. Android's back stack is the trail. */
    class Ladder {
        var armedUntil: Long = Long.MIN_VALUE
            private set

        fun isArmed(now: Long): Boolean = now < armedUntil

        /** The open Hub hears a Back from [edge] at [now]. From the left edge it closes AND goes
         *  back one place, arming the window. A Hub the ladder itself opened ([byLadder] — a
         *  left-edge Back already climbed to it) takes any Back but a right-edge one as that rung:
         *  if a device tells the page the edge but not the Hub's dialog (review 2026-10-12, a B6
         *  unknown), a plain close would loop Hub → close → Hub on every left-edge flick. */
        fun hubBack(now: Long, edge: Int, byLadder: Boolean = false): HubBack {
            if (edge == EDGE_LEFT || (byLadder && edge != EDGE_RIGHT)) {
                armedUntil = now + WINDOW_MS
                return HubBack.CLOSE_AND_BACK
            }
            return HubBack.CLOSE
        }

        /** One Back from [edge] at [now], on a screen whose room pane reads [pane]; [reader] for a
         *  screen you read (its first left-edge Back goes back to its list — or, for a reader with a
         *  contents pane that is shut, opens the contents: the book's "Content then shelf").
         *  [somewhereBack]: is there a place behind this page (false on the day page you started
         *  on) — inside the window, without one the flick is [Rung.END]. */
        fun swipe(now: Long, edge: Int, pane: Pane, reader: Boolean, somewhereBack: Boolean = true): Rung {
            if (edge != EDGE_LEFT) return Rung.PLAIN
            if (now < armedUntil) {
                armedUntil = now + WINDOW_MS
                return if (somewhereBack) Rung.BACK else Rung.END
            }
            if (reader) return if (pane == Pane.SHUT) Rung.OPEN_PANE else Rung.PLAIN
            return if (pane == Pane.SHUT) Rung.OPEN_PANE else Rung.HUB
        }

        /**
         * A Back on a reader's open CONTENTS (the book's table of contents, Michael 2026-10-12:
         * "Content then shelf"): from the left edge it closes the contents AND goes back to the
         * shelf — true; from anywhere else it only closes them. Inside the window (a Hub's Back a
         * moment ago) it is the window's back step and re-arms it, as every left-edge flick there
         * is. Outside it, it arms nothing: on the shelf the next flick is the shelf's own first
         * rung, the Hub.
         */
        fun contentsBack(now: Long, edge: Int): Boolean {
            if (edge != EDGE_LEFT) return false
            if (now < armedUntil) armedUntil = now + WINDOW_MS
            return true
        }
    }

    /** The activity's one ladder: its window outlives the screen a Back leaves. */
    val shared = Ladder()

    // ── The flick in from the left edge, caught in-app (the B6, Michael 2026-10-12) ──────────────
    //
    // "The b6 doesn't have the left right working for bring up the hub it seems rn." Probed on his
    // B6 (Android 14, gesture navigation, -322): wherever an ink page is up the system never sees
    // the swipe. The surface claims its whole area from the back gesture
    // (SurfaceFragment surfaceChanged → systemGestureExclusionRects, so the pen can write in the
    // margins), and with the bars hidden Android honours all of it — not just 200dp — so a finger
    // flick from x = 1…32 px reached the page as a plain drag: no Back, no edge, nothing (Bigme's
    // xSettings logs onGestureEvent 9/10 for some of them and does nothing either). The Hub
    // dialog hung over an ink page gets no system Back either. Where nothing claims the edge (the
    // Feeds list) the system's own band (x < 56 px) still takes the swipe and hands the ladder its
    // edge — so this catcher is the system's stand-in exactly where the system stands aside:
    // [LeftEdgeCatcher] on the activity's window and on the Hub's. The twin of iOS's
    // LeftEdgeDrawerCatcher / LeftEdgeFlickRecognizer, on the same bars (EdgeBack.Bar), in dp.

    /**
     * What a one-finger touch from the window's left edge must do to be the swipe back: land
     * within [zoneDp] of the glass, travel [travelDp] rightward within [windowMs] of touch-down,
     * level (|dy| ≤ dx × [level]). iOS EdgeBack.Bar's four bars, the off-ink zones widened to the
     * B6's own back band:
     *  - [ON_INK]: a page the FINGER inks (Finger mode, an element being moved) — a bezel flick
     *    only (iOS's 10 / 70 in 0.18 s), so a finger stroke out of the margin stays a stroke;
     *  - [ARMED_ON_INK]: the same page inside the 3 s window after the Hub's Back — the flick
     *    further back, iOS's 20 / 60 in 0.3 s;
     *  - [OFF_INK]: anywhere else (the page under a pen, the panel, a list) — any edge swipe:
     *    28 dp, just inside the system's 56 px (≈ 30 dp) band on the B6 — measured: `wm density`
     *    300 (1.875 px/dp), the left systemGestures inset [0,0][56,1448] — so the edge is the same
     *    width on an ink page as on the Feeds list where the system takes it; 50 dp in 0.8 s;
     *  - [ARMED_OFF_INK]: the same, armed — iOS's 32 dp zone.
     */
    data class Bar(val name: String, val zoneDp: Float, val travelDp: Float, val windowMs: Long, val level: Float) {

        /** Why a touch from the edge wasn't the swipe back (logged while the ladder is armed). */
        enum class Miss(val why: String) {
            OUTSIDE_ZONE("landed too far in"),
            TOO_SLOW("too slow"),
            WENT_LEFT("went left"),
            NOT_LEVEL("not level"),
            VERTICAL("heading up or down"),
            LIFTED_SHORT("lifted short"),
        }

        /** [Miss] is null unless [kind] is MISS. */
        data class Verdict(val kind: Kind, val miss: Miss? = null) {
            enum class Kind { UNDECIDED, FLICK, MISS }
        }

        fun lands(xDp: Float): Boolean = xDp <= zoneDp

        /** The touch so far: [dxDp] rightward, [dyDp] down, [elapsedMs] since touch-down — iOS's
         *  order: the clock, a turn back left, the distance with its level test, a clear turn up
         *  or down. */
        fun judge(dxDp: Float, dyDp: Float, elapsedMs: Long): Verdict {
            val ady = kotlin.math.abs(dyDp)
            if (elapsedMs > windowMs) return Verdict(Verdict.Kind.MISS, Miss.TOO_SLOW)
            if (dxDp < -WENT_LEFT_DP) return Verdict(Verdict.Kind.MISS, Miss.WENT_LEFT)
            if (dxDp >= travelDp) {
                return if (ady <= dxDp * level) Verdict(Verdict.Kind.FLICK)
                    else Verdict(Verdict.Kind.MISS, Miss.NOT_LEVEL)
            }
            if (ady > VERTICAL_DP && ady > dxDp) return Verdict(Verdict.Kind.MISS, Miss.VERTICAL)
            return Verdict(Verdict.Kind.UNDECIDED)
        }

        companion object {
            /** A turn back left past this is never the flick (iOS: 8 pt). */
            const val WENT_LEFT_DP = 8f
            /** Up or down past this, and more than across: a scroll, not the flick (iOS: 24 pt). */
            const val VERTICAL_DP = 24f

            val ON_INK = Bar("bezel on ink", zoneDp = 10f, travelDp = 70f, windowMs = 180, level = 0.4f)
            val ARMED_ON_INK = Bar("armed on ink", zoneDp = 20f, travelDp = 60f, windowMs = 300, level = 0.5f)
            val OFF_INK = Bar("edge swipe", zoneDp = 28f, travelDp = 50f, windowMs = 800, level = 0.5f)
            val ARMED_OFF_INK = Bar("armed edge swipe", zoneDp = 32f, travelDp = 50f, windowMs = 800, level = 0.5f)

            fun pick(inks: Boolean, armed: Boolean): Bar = when {
                inks && armed -> ARMED_ON_INK
                inks -> ON_INK
                armed -> ARMED_OFF_INK
                else -> OFF_INK
            }

            /** The widest zone any bar takes at that arming. */
            fun widestZoneDp(armed: Boolean): Float =
                if (armed) maxOf(ARMED_ON_INK.zoneDp, ARMED_OFF_INK.zoneDp) else maxOf(ON_INK.zoneDp, OFF_INK.zoneDp)

            /** Armed, a touch landing up to this far in is followed past its bar's zone — never to
             *  fire, only so a flick that missed by landing too far in says so in the log (iOS's
             *  LeftEdgeFlickRecognizer.armedWatchZone, 44 pt; review of f0035e3b, 2026-10-12). */
            const val ARMED_WATCH_DP = 44f

            /** The first cut at touch-down: unarmed the widest bar, armed the watch zone. */
            fun firstCutDp(armed: Boolean): Float = if (armed) ARMED_WATCH_DP else widestZoneDp(armed = false)
        }
    }

    // ── One swipe, one rung ──────────────────────────────────────────────────────────────────
    //
    // Should the system (or Bigme's xSettings) ALSO turn a swipe the catcher took into a Back, that
    // Back is the same swipe heard twice and is swallowed (MainActivity's window guard and key
    // path, the ladder, the Hub's own Back). Both would send it at the LIFT, not at the fire: the
    // system's gesture invokes Back when the finger comes up (probe: CANCEL at .165, Back at .415),
    // and xSettings logged its gesture end 735–893 ms after the catcher fired (b6edge/
    // live-probe.log). So the echo window runs from the fire through the lift and [ECHO_MS] past it
    // (review of f0035e3b, 2026-10-12; iOS answered(dragBegan:), PageSkipper.swift:983-993):
    //  - fired, finger still down: open, for at most [ECHO_HOLD_MS] — the lift may never be seen
    //    (the Hub's window goes away under its own flick);
    //  - the swallowed touch lifts: [ECHO_MS] from the lift;
    //  - the touch is CANCELLED while swallowed — the system took it too, after the catcher fired
    //    (a fast real flick the system judges late): its Back comes at a lift this window never
    //    sees, so open for [ECHO_HOLD_MS];
    //  - any new touch-down shuts it: a Back after that belongs to the new touch.
    // On the B6 today nothing echoes — where the system takes the edge it cancels the touch long
    // before the catcher's travel — so this is the guard the task asked for, not a fix for a seen
    // double.

    /** After the flick's finger lifts, a Back this soon is its echo. */
    const val ECHO_MS = 300L

    /** The longest the echo window stays open while the lift is unseen (iOS stands aside 0.8 s
     *  from the fire AND for the whole drag; 2 s covers a flick held to watch the Hub arrive). */
    const val ECHO_HOLD_MS = 2_000L

    /** When the catcher last fired (uptime ms). */
    @Volatile var catcherFiredAt: Long = Long.MIN_VALUE
        private set

    /** Until when (uptime ms) a Back is the last flick's echo; MIN_VALUE: shut. */
    @Volatile var echoUntil: Long = Long.MIN_VALUE
        private set

    /** True while the catcher hands its own Back to a dispatcher — never an echo. */
    @Volatile var catcherDispatching: Boolean = false

    /** The flick was made at [now]: the window opens, held until the lift (at most [ECHO_HOLD_MS]). */
    fun catcherFired(now: Long) {
        catcherFiredAt = now
        echoUntil = now + ECHO_HOLD_MS
    }

    /** The flick's finger lifted at [now]: an echo comes now, if at all — [ECHO_MS] from here. */
    fun catcherLifted(now: Long) {
        if (catcherFiredAt == Long.MIN_VALUE) return
        echoUntil = now + ECHO_MS
    }

    /** The flick's touch was cancelled at [now] — the system took it too, and its Back comes at a
     *  lift this app won't see: held open [ECHO_HOLD_MS], shut by the next touch-down. */
    fun catcherOverlapped(now: Long) {
        if (catcherFiredAt == Long.MIN_VALUE) return
        echoUntil = now + ECHO_HOLD_MS
    }

    /** A new touch came down: whatever Back follows is that touch's, not the last flick's. */
    fun touchDown() {
        echoUntil = Long.MIN_VALUE
    }

    /** Is a Back heard at [now] the echo of the catcher's swipe? */
    fun isEcho(now: Long): Boolean {
        val until = echoUntil
        return !catcherDispatching && until != Long.MIN_VALUE && now >= catcherFiredAt && now <= until
    }

    /** How much longer (ms) the echo window stays open from [now]; 0 when shut. */
    fun echoRemainingMs(now: Long): Long {
        val until = echoUntil
        return if (until == Long.MIN_VALUE || now > until) 0L else until - now + 1
    }

    // ── The Hub as a drawer ──────────────────────────────────────────────────────────────────
    //
    // iOS's numbers (EdgeBack.HubShut, review 2026-10-12: a 50–89 pt pull shut the Hub here and
    // sprang back on the iPhone). E-ink can't draw the Hub following the finger, so the close lands
    // mid-drag once the distance is reached; a release short of it shuts only on a throw, and
    // otherwise does nothing at all — the gesture was the Hub's from the slop on.

    /** Drag this far toward the Hub's edge and it shuts — iOS's `HubShut.distance` (90 pt). */
    const val DRAWER_SWIPE_DP = 90

    /** Or a throw: at least this much real travel (iOS's `throwFloor`, 30 pt) … */
    const val DRAWER_THROW_FLOOR_DP = 30

    /** … and the finger's projected end this far (iOS's `throwReach`, 180 pt). */
    const val DRAWER_THROW_REACH_DP = 180

    /** Android has no predicted end: it is projected as travel + release velocity × this. */
    const val DRAWER_THROW_LOOKAHEAD_S = 0.25f

    /** How much more sideways than up-or-down a drag must be at the slop for the Hub to claim
     *  it — a scroll of the Hub's rows is never a close. */
    const val DRAWER_SWIPE_SLANT = 1.5f

    /** What a drag on or beside the open Hub is, decided once at the touch slop. */
    enum class DrawerDrag {
        /** Still inside the slop: the rows keep the touch. */
        UNDECIDED,
        /** The Hub's: the row under the finger is cancelled, the rest of the drag is the close's. */
        CLAIM,
        /** The rows' (a scroll, a drag away from the edge): the Hub never takes it after. */
        LEAVE,
    }

    /**
     * Michael 2026-10-12 ("When swiping right to left near the hub panel it should close it"),
     * review 2026-10-12: a drag of ([dx], [dy]) px that has reached the touch slop ([slopPx])
     * toward the edge the Hub hangs from — the left, or the right when the column docks right
     * ([hubOnRight]) — and is clearly more sideways than vertical is CLAIMED, there and then, so
     * the row under the finger is cancelled before its tap (a short swipe) or its hold (a slow
     * one, the B6's) can fire. Any other drag past the slop is the rows'.
     */
    fun drawerDrag(dx: Float, dy: Float, hubOnRight: Boolean, slopPx: Float): DrawerDrag {
        val toward = if (hubOnRight) dx else -dx
        if (toward >= slopPx && toward > DRAWER_SWIPE_SLANT * kotlin.math.abs(dy)) return DrawerDrag.CLAIM
        return if (kotlin.math.abs(dx) >= slopPx || kotlin.math.abs(dy) >= slopPx) DrawerDrag.LEAVE
            else DrawerDrag.UNDECIDED
    }

    /**
     * Mid-drag, on a claimed drag: does ([dx], [dy]) px shut the Hub now? At least [thresholdPx]
     * toward its edge, more across than up or down (iOS's `HubShut.shuts`). The way it came, never
     * the other way: a drag away from the edge is a reach across the page, not a close.
     */
    fun drawerSwipeCloses(dx: Float, dy: Float, hubOnRight: Boolean, thresholdPx: Float): Boolean {
        val toward = if (hubOnRight) dx else -dx
        return toward >= thresholdPx && toward > kotlin.math.abs(dy)
    }

    /**
     * On the lift of a claimed drag: does it shut the Hub? Past the distance, or a throw — at least
     * [DRAWER_THROW_FLOOR_DP] of travel and a projected end ([vx] px/s × [DRAWER_THROW_LOOKAHEAD_S])
     * past [DRAWER_THROW_REACH_DP]. [density] is px per dp. Short of both: nothing (iOS springs back).
     */
    fun drawerReleaseCloses(dx: Float, dy: Float, vx: Float, hubOnRight: Boolean, density: Float): Boolean {
        if (drawerSwipeCloses(dx, dy, hubOnRight, DRAWER_SWIPE_DP * density)) return true
        val toward = if (hubOnRight) dx else -dx
        val flung = if (hubOnRight) vx else -vx
        return toward > kotlin.math.abs(dy) &&
            toward >= DRAWER_THROW_FLOOR_DP * density &&
            toward + flung * DRAWER_THROW_LOOKAHEAD_S >= DRAWER_THROW_REACH_DP * density
    }
}
