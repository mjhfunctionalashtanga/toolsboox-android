package com.toolsboox

import com.toolsboox.ot.EdgeLadder
import com.toolsboox.ot.EdgeLadder.Bar
import com.toolsboox.ot.EdgeLadder.Bar.Verdict.Kind as K
import com.toolsboox.ot.EdgeLadder.HubBack
import com.toolsboox.ot.EdgeLadder.Pane
import com.toolsboox.ot.EdgeLadder.Rung
import com.toolsboox.plugin.feeds.nw.LaterFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The left edge's Back ladder on Android (Michael 2026-10-12: "Make sure we can get to hub with back
 * swipe in the reader or touch or something as needed" → plan → "Awesome"; then, on iPhone 247:
 * "When swiping right to left near the hub panel it should close it. Then two swipes back instead
 * of three."): room pane → Hub → back (the Hub's Back closes it AND goes back a place), a reader
 * back first, the right edge and an edgeless Back plain — iOS EdgeBackTests' rungs.
 */
class EdgeLadderTest {

    private val L = EdgeLadder.EDGE_LEFT

    @Test
    fun `a room - pane, then the Hub, then the Hub's Back closes it and goes back, then back, back`() {
        val ladder = EdgeLadder.Ladder()
        var t = 1_000L
        // "First swipe back email stuff second swipe back main pane."
        assertEquals(Rung.OPEN_PANE, ladder.swipe(t, L, Pane.SHUT, reader = false))
        t += 500
        assertEquals(Rung.HUB, ladder.swipe(t, L, Pane.SHOWING, reader = false))
        // The Hub's own Back closes it AND goes back one place — no "close" rung of its own ("Then
        // two swipes back instead of three") — and arms the window…
        t += 500
        assertEquals(HubBack.CLOSE_AND_BACK, ladder.hubBack(t, L))
        assertTrue(ladder.isArmed(t + 1))
        // …so the next left-edge Back goes back another place, and every one after it inside the window.
        t += 800
        assertEquals(Rung.BACK, ladder.swipe(t, L, Pane.SHOWING, reader = false))
        t += 2_900
        assertEquals("each step re-arms", Rung.BACK, ladder.swipe(t, L, Pane.NONE, reader = false))
        // Let the window pass: the next flick opens the pane or the Hub again.
        t += EdgeLadder.WINDOW_MS
        assertFalse(ladder.isArmed(t))
        assertEquals(Rung.HUB, ladder.swipe(t, L, Pane.NONE, reader = false))
    }

    @Test
    fun `today - open, then back - two swipes, not three`() {
        val ladder = EdgeLadder.Ladder()
        // Swipe 1: the Hub opens over the page.
        assertEquals(Rung.HUB, ladder.swipe(0, L, Pane.NONE, reader = false))
        // Swipe 2: the Hub closes and the page goes back one place — in the same swipe.
        assertEquals(HubBack.CLOSE_AND_BACK, ladder.hubBack(400, L))
        // Swipe 3 inside the window: one place further back (never the Hub again).
        assertEquals(Rung.BACK, ladder.swipe(900, L, Pane.NONE, reader = false))
    }

    @Test
    fun `everywhere else - one left-edge Back opens the Hub`() {
        val ladder = EdgeLadder.Ladder()
        assertEquals(Rung.HUB, ladder.swipe(0, L, Pane.NONE, reader = false))
        // The pane standing beside the list: the Hub opens beside it.
        assertEquals(Rung.HUB, ladder.swipe(10, L, Pane.SHOWING, reader = false))
    }

    @Test
    fun `the book - contents, then the shelf, then the Hub - and the window still rules`() {
        // Michael 2026-10-12: "Content then shelf".
        val ladder = EdgeLadder.Ladder()
        // Swipe 1 on the open book: its contents (the reader's pane, shut).
        assertEquals(Rung.OPEN_PANE, ladder.swipe(0, L, Pane.SHUT, reader = true))
        assertFalse("opening the contents arms nothing", ladder.isArmed(1))
        // Swipe 2 lands on the open contents: close them AND back to the shelf, arming nothing —
        assertTrue(ladder.contentsBack(400, L))
        assertFalse(ladder.isArmed(401))
        // — so swipe 3, on the shelf (no pane), is the Hub.
        assertEquals(Rung.HUB, ladder.swipe(800, L, Pane.NONE, reader = false))
        // A right-edge or edgeless Back on the contents only closes them.
        assertFalse(ladder.contentsBack(900, EdgeLadder.EDGE_RIGHT))
        assertFalse(ladder.contentsBack(900, EdgeLadder.EDGE_NONE))
        assertFalse(ladder.contentsBack(900, EdgeLadder.EDGE_UNKNOWN))
        // Inside the 3 s window (a Hub's Back a moment ago) the book's flick is the window's back
        // step, as every left-edge flick there is — the window rules first.
        val armed = EdgeLadder.Ladder()
        armed.hubBack(0, L)
        assertEquals(Rung.BACK, armed.swipe(500, L, Pane.SHUT, reader = true))
        assertTrue(armed.contentsBack(900, L))
        assertTrue("the window's step re-arms", armed.isArmed(900 + EdgeLadder.WINDOW_MS - 1))
        // A reader with no contents (the standalone article) keeps its plain first Back.
        assertEquals(Rung.PLAIN, EdgeLadder.Ladder().swipe(0, L, Pane.NONE, reader = true))
    }

    @Test
    fun `a reader goes back to its list first, and does not arm`() {
        val ladder = EdgeLadder.Ladder()
        assertEquals(Rung.PLAIN, ladder.swipe(0, L, Pane.NONE, reader = true))
        assertFalse(ladder.isArmed(1))
        // On the list it came back to, the next left-edge Back opens the pane (or the Hub).
        assertEquals(Rung.OPEN_PANE, ladder.swipe(400, L, Pane.SHUT, reader = false))
    }

    @Test
    fun `the right edge, no edge and an unreported Back are plain Back - and on the Hub a plain close`() {
        val ladder = EdgeLadder.Ladder()
        for (edge in listOf(EdgeLadder.EDGE_RIGHT, EdgeLadder.EDGE_NONE, EdgeLadder.EDGE_UNKNOWN)) {
            for (pane in Pane.entries) for (reader in listOf(false, true))
                assertEquals(Rung.PLAIN, ladder.swipe(0, edge, pane, reader))
            assertEquals("the Hub from ${EdgeLadder.edgeName(edge)} only closes",
                HubBack.CLOSE, ladder.hubBack(0, edge))
            assertFalse("closing the Hub from ${EdgeLadder.edgeName(edge)} arms nothing", ladder.isArmed(1))
        }
        // A right-edge Back inside the window stays plain and leaves the window as it was.
        assertEquals(HubBack.CLOSE_AND_BACK, ladder.hubBack(0, L))
        assertEquals(Rung.PLAIN, ladder.swipe(100, EdgeLadder.EDGE_RIGHT, Pane.NONE, reader = false))
        assertEquals(EdgeLadder.WINDOW_MS, ladder.armedUntil)
    }

    @Test
    fun `a Hub the ladder opened takes a Back whose edge the dialog never heard as the rung - never from the right`() {
        // Review 2026-10-12: a device that tells the page the edge but not the Hub's dialog would
        // otherwise loop Hub → close → Hub on every left-edge flick.
        for (edge in listOf(EdgeLadder.EDGE_UNKNOWN, EdgeLadder.EDGE_NONE)) {
            val ladder = EdgeLadder.Ladder()
            assertEquals(Rung.HUB, ladder.swipe(0, L, Pane.NONE, reader = false))
            assertEquals(HubBack.CLOSE_AND_BACK, ladder.hubBack(100, edge, byLadder = true))
            assertTrue(ladder.isArmed(101))
            assertEquals(Rung.BACK, ladder.swipe(600, L, Pane.NONE, reader = false))
        }
        val right = EdgeLadder.Ladder()
        assertEquals(HubBack.CLOSE, right.hubBack(100, EdgeLadder.EDGE_RIGHT, byLadder = true))
        assertFalse("a right-edge close is a plain close", right.isArmed(101))
        // A Hub opened by touch (More › Hub) closes and goes back only from the left edge.
        val touch = EdgeLadder.Ladder()
        assertEquals(HubBack.CLOSE, touch.hubBack(100, EdgeLadder.EDGE_UNKNOWN))
        assertFalse(touch.isArmed(101))
        assertEquals(HubBack.CLOSE_AND_BACK, touch.hubBack(200, L))
    }

    @Test
    fun `the Hub claims a drag toward its edge at the slop - so a short or slow swipe never runs a row`() {
        // Review 2026-10-12: the rows are full width and Android cancels neither a tap nor a hold
        // while the finger stays inside the row, so the drag is decided once, at the touch slop.
        val slop = 16f
        assertEquals(EdgeLadder.DrawerDrag.UNDECIDED, EdgeLadder.drawerDrag(-10f, 2f, hubOnRight = false, slopPx = slop))
        assertEquals(EdgeLadder.DrawerDrag.CLAIM, EdgeLadder.drawerDrag(-16f, 0f, hubOnRight = false, slopPx = slop))
        assertEquals(EdgeLadder.DrawerDrag.CLAIM, EdgeLadder.drawerDrag(-20f, 10f, hubOnRight = false, slopPx = slop))
        // A scroll of the rows, a slanted drag, a drag away from the edge: the rows', for good.
        assertEquals("a scroll", EdgeLadder.DrawerDrag.LEAVE, EdgeLadder.drawerDrag(-3f, 16f, hubOnRight = false, slopPx = slop))
        assertEquals("too slanted", EdgeLadder.DrawerDrag.LEAVE, EdgeLadder.drawerDrag(-18f, 14f, hubOnRight = false, slopPx = slop))
        assertEquals("away from the edge", EdgeLadder.DrawerDrag.LEAVE, EdgeLadder.drawerDrag(16f, 0f, hubOnRight = false, slopPx = slop))
        // A column docked right hangs the Hub from the right: the claim runs left to right.
        assertEquals(EdgeLadder.DrawerDrag.CLAIM, EdgeLadder.drawerDrag(16f, 0f, hubOnRight = true, slopPx = slop))
        assertEquals(EdgeLadder.DrawerDrag.LEAVE, EdgeLadder.drawerDrag(-16f, 0f, hubOnRight = true, slopPx = slop))
    }

    @Test
    fun `the Hub swiped toward its edge shuts on iOS's numbers - a drawer, nothing more`() {
        // "When swiping right to left near the hub panel it should close it." iOS EdgeBack.HubShut:
        // 90 at the lift (here mid-drag too), or a throw — 30 of travel, a projected end of 180.
        assertEquals(90, EdgeLadder.DRAWER_SWIPE_DP)
        assertEquals(30, EdgeLadder.DRAWER_THROW_FLOOR_DP)
        assertEquals(180, EdgeLadder.DRAWER_THROW_REACH_DP)
        val d = 2f                      // px per dp
        val t = EdgeLadder.DRAWER_SWIPE_DP * d
        assertTrue(EdgeLadder.drawerSwipeCloses(-t, 0f, hubOnRight = false, thresholdPx = t))
        assertTrue(EdgeLadder.drawerSwipeCloses(-200f, 40f, hubOnRight = false, thresholdPx = t))
        // Short of it, away from the edge, or more up-and-down than across: no close.
        assertFalse(EdgeLadder.drawerSwipeCloses(-t + 1, 0f, hubOnRight = false, thresholdPx = t))
        assertFalse(EdgeLadder.drawerSwipeCloses(200f, 0f, hubOnRight = false, thresholdPx = t))
        assertFalse(EdgeLadder.drawerSwipeCloses(-190f, 200f, hubOnRight = false, thresholdPx = t))
        assertTrue(EdgeLadder.drawerSwipeCloses(200f, 0f, hubOnRight = true, thresholdPx = t))
        assertFalse(EdgeLadder.drawerSwipeCloses(-200f, 0f, hubOnRight = true, thresholdPx = t))
        // The 50–89 pull that shut it here and sprang back on the iPhone (review 2026-10-12): no more.
        assertFalse(EdgeLadder.drawerReleaseCloses(-70 * d, 0f, vx = 0f, hubOnRight = false, density = d))
        // The lift past the distance shuts it even when the last move fell short.
        assertTrue(EdgeLadder.drawerReleaseCloses(-90 * d, 0f, vx = 0f, hubOnRight = false, density = d))
        // A throw: 30 of travel at 600 dp/s projects 30 + 150 = 180.
        assertTrue(EdgeLadder.drawerReleaseCloses(-30 * d, 0f, vx = -600 * d, hubOnRight = false, density = d))
        assertFalse("a twitch is no throw", EdgeLadder.drawerReleaseCloses(-29 * d, 0f, vx = -2000 * d, hubOnRight = false, density = d))
        assertFalse("too slow to be a throw", EdgeLadder.drawerReleaseCloses(-30 * d, 0f, vx = -500 * d, hubOnRight = false, density = d))
        assertFalse("thrown the other way", EdgeLadder.drawerReleaseCloses(-40 * d, 0f, vx = 900 * d, hubOnRight = false, density = d))
        assertTrue(EdgeLadder.drawerReleaseCloses(40 * d, 0f, vx = 900 * d, hubOnRight = true, density = d))
    }

    @Test
    fun `with nothing further back the window's flick is kept - never out of Ledgable`() {
        // Review 2026-10-12, iOS's closeAtEnd → endOfTrail: on the day page you started on the
        // Hub's Back only closes it (ScreenFragment) and arms the window; the next left-edge flick
        // inside it is END — kept — not the plain Back that left the app.
        val ladder = EdgeLadder.Ladder()
        assertEquals(Rung.HUB, ladder.swipe(0, L, Pane.NONE, reader = false, somewhereBack = false))
        assertEquals(HubBack.CLOSE_AND_BACK, ladder.hubBack(400, L))
        assertEquals(Rung.END, ladder.swipe(900, L, Pane.NONE, reader = false, somewhereBack = false))
        assertTrue("the end re-arms, as iOS's does", ladder.isArmed(900 + EdgeLadder.WINDOW_MS - 1))
        assertEquals(Rung.END, ladder.swipe(2_000, L, Pane.NONE, reader = false, somewhereBack = false))
        // A walk that still has somewhere to go keeps going back.
        assertEquals(Rung.BACK, ladder.swipe(2_500, L, Pane.NONE, reader = false, somewhereBack = true))
        // The right edge is the plain Back, always.
        assertEquals(Rung.PLAIN, ladder.swipe(2_600, EdgeLadder.EDGE_RIGHT, Pane.NONE, reader = false, somewhereBack = false))
        // The window passed: the flick opens the Hub again.
        assertEquals(Rung.HUB, ladder.swipe(2_500 + EdgeLadder.WINDOW_MS, L, Pane.NONE, reader = false, somewhereBack = false))
    }

    @Test
    fun `the window is iOS's three seconds, and its edges are BackEventCompat's`() {
        assertEquals(3_000L, EdgeLadder.WINDOW_MS)
        // androidx.activity.BackEventCompat.EDGE_LEFT / EDGE_RIGHT / EDGE_NONE.
        assertEquals(0, EdgeLadder.EDGE_LEFT)
        assertEquals(1, EdgeLadder.EDGE_RIGHT)
        assertEquals(2, EdgeLadder.EDGE_NONE)
        assertEquals("unreported", EdgeLadder.edgeName(EdgeLadder.EDGE_UNKNOWN))
    }

    // ── The in-app left-edge flick (Michael 2026-10-12, the B6: "The b6 doesn't have the left
    //    right working for bring up the hub it seems rn") — iOS EdgeBack.Bar's bars, in dp ──────

    @Test
    fun `the four bars - bezel on ink, wider off ink, wider again while armed`() {
        assertEquals(Bar.ON_INK, Bar.pick(inks = true, armed = false))
        assertEquals(Bar.ARMED_ON_INK, Bar.pick(inks = true, armed = true))
        assertEquals(Bar.OFF_INK, Bar.pick(inks = false, armed = false))
        assertEquals(Bar.ARMED_OFF_INK, Bar.pick(inks = false, armed = true))
        // iOS's numbers on ink; off ink the zone is just inside the B6's 56 px (≈ 30 dp) back band.
        assertEquals(10f, Bar.ON_INK.zoneDp); assertEquals(70f, Bar.ON_INK.travelDp); assertEquals(180L, Bar.ON_INK.windowMs)
        assertEquals(20f, Bar.ARMED_ON_INK.zoneDp); assertEquals(60f, Bar.ARMED_ON_INK.travelDp); assertEquals(300L, Bar.ARMED_ON_INK.windowMs)
        assertEquals(28f, Bar.OFF_INK.zoneDp); assertEquals(50f, Bar.OFF_INK.travelDp); assertEquals(800L, Bar.OFF_INK.windowMs)
        assertEquals(32f, Bar.ARMED_OFF_INK.zoneDp)
        // Measured on the B6 (review of f0035e3b): `wm density` 300 → 1.875 px/dp; dumpsys window's
        // left systemGestures inset is [0,0][56,1448] — 56 px ≈ 29.9 dp.
        val b6Density = 1.875f
        assertTrue("inside the system's band", Bar.OFF_INK.zoneDp * b6Density < 56f)
        assertEquals(28f, Bar.widestZoneDp(armed = false))
        assertEquals(32f, Bar.widestZoneDp(armed = true))
        // Where it lands: the probe's x = 1…32 px on the B6 are all in the off-ink zone.
        for (px in listOf(1, 4, 8, 12, 16, 24, 32)) assertTrue("x=$px", Bar.OFF_INK.lands(px / b6Density))
        assertFalse(Bar.ON_INK.lands(24 / b6Density))
        assertFalse("past the band", Bar.OFF_INK.lands(56 / b6Density))
    }

    @Test
    fun `a quick level flick is the swipe back - a stroke, a scroll or a slow drag is not`() {
        val b = Bar.OFF_INK
        assertEquals(K.UNDECIDED, b.judge(20f, 2f, 40).kind)
        assertEquals(K.FLICK, b.judge(50f, 10f, 200).kind)
        assertEquals(K.FLICK, b.judge(120f, 60f, 300).kind)            // |dy| ≤ dx × 0.5
        assertEquals(Bar.Miss.NOT_LEVEL, b.judge(60f, 40f, 200).miss)  // 40 > 30
        assertEquals(Bar.Miss.TOO_SLOW, b.judge(60f, 0f, 801).miss)
        assertEquals(Bar.Miss.WENT_LEFT, b.judge(-9f, 0f, 50).miss)
        assertEquals(Bar.Miss.VERTICAL, b.judge(10f, 30f, 100).miss)   // a scroll from the edge
        // On ink: only a bezel flick — handwriting's 50 dp in 0.4 s stays ink.
        assertEquals(Bar.Miss.TOO_SLOW, Bar.ON_INK.judge(50f, 0f, 400).miss)
        assertEquals(K.UNDECIDED, Bar.ON_INK.judge(60f, 0f, 150).kind)
        assertEquals(K.FLICK, Bar.ON_INK.judge(70f, 20f, 170).kind)
        assertEquals(Bar.Miss.NOT_LEVEL, Bar.ON_INK.judge(70f, 30f, 170).miss)   // 30 > 28
    }

    @Test
    fun `one swipe, one rung - a Back right after the flick is its echo, the flick's own is not`() {
        EdgeLadder.touchDown()
        EdgeLadder.catcherFired(10_000)
        assertTrue(EdgeLadder.isEcho(10_000))
        assertFalse("before the flick", EdgeLadder.isEcho(9_999))
        EdgeLadder.catcherDispatching = true
        try {
            assertFalse("the flick's own Back", EdgeLadder.isEcho(10_001))
        } finally {
            EdgeLadder.catcherDispatching = false
        }
        // The finger is still down: an echo comes at the lift, however long the flick is held
        // (b6edge/live-probe.log: xSettings' gesture end 735–893 ms after the fire) …
        assertTrue("held, 900 ms on", EdgeLadder.isEcho(10_900))
        // … capped, for a lift that is never seen (the Hub's window gone under its own flick).
        assertTrue(EdgeLadder.isEcho(10_000 + EdgeLadder.ECHO_HOLD_MS))
        assertFalse(EdgeLadder.isEcho(10_000 + EdgeLadder.ECHO_HOLD_MS + 1))
        // The lift: ECHO_MS from there, not from the fire.
        EdgeLadder.catcherLifted(10_900)
        assertTrue("an echo at the lift", EdgeLadder.isEcho(10_950))
        assertTrue(EdgeLadder.isEcho(10_900 + EdgeLadder.ECHO_MS))
        assertFalse(EdgeLadder.isEcho(10_900 + EdgeLadder.ECHO_MS + 1))
        assertEquals(EdgeLadder.ECHO_MS + 1, EdgeLadder.echoRemainingMs(10_900))
        assertEquals(0L, EdgeLadder.echoRemainingMs(10_900 + EdgeLadder.ECHO_MS + 1))
        assertEquals(300L, EdgeLadder.ECHO_MS)
        assertEquals(2_000L, EdgeLadder.ECHO_HOLD_MS)
    }

    @Test
    fun `the system judging the flick's swipe late - its Back at an unseen lift is swallowed, until a new touch`() {
        EdgeLadder.touchDown()
        EdgeLadder.catcherFired(20_000)
        EdgeLadder.catcherOverlapped(20_060)   // the swallowed touch cancelled: the system took it too
        assertTrue("its Back at the lift, 1.5 s on", EdgeLadder.isEcho(21_500))
        assertFalse(EdgeLadder.isEcho(20_060 + EdgeLadder.ECHO_HOLD_MS + 1))
        EdgeLadder.catcherOverlapped(22_000)
        EdgeLadder.touchDown()                 // a new touch: what follows is its own Back
        assertFalse(EdgeLadder.isEcho(22_100))
        assertEquals(0L, EdgeLadder.echoRemainingMs(22_100))
    }

    @Test
    fun `armed, a touch up to 44 dp in is followed for the miss line - iOS's watch zone`() {
        assertEquals(28f, Bar.firstCutDp(armed = false))
        assertEquals(44f, Bar.firstCutDp(armed = true))
        assertTrue(Bar.ARMED_WATCH_DP > Bar.widestZoneDp(armed = true))
    }

    @Test
    fun `the Later lanes wear their short words in a pane - Read, Watch, Listen, Email`() {
        // iOS FeedTree.Lane.paneWord (review 2026-10-12): "The Read" sat a fold under the RSS lens
        // of the same name. The list's own title keeps the whole name.
        assertEquals(listOf("Read", "Watch", "Listen", "Email"), LaterFeed.LANES.map { LaterFeed.paneWord(it.third) })
        assertEquals("Later · The Read", LaterFeed.title("read"))
    }
}
