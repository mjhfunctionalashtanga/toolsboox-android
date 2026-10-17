package com.toolsboox

import com.toolsboox.ot.ReaderFullScreen
import com.toolsboox.ot.ReaderFullScreen.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full screen in a reader leaves nothing on the page (Michael 2026-10-12: "I need the full screen
 * button to be able to be fully hidden within a reader" + "IT cant block the comics :P" → "yeas").
 * The SHARED CASES — the same eight on iOS (LedgerCore's ReaderFullScreenTests 1–8), in the same
 * order under the same names: pill shown ⇔ !fullScreen || controlsShown; the controls tap toggles;
 * a turn or the controls hiding puts them away; full screen coming or going starts clean. Then the
 * rule's invariant, the middle tap (iOS's `middleTap` and its doors' cases), the comic's walk and
 * double-tap window, and a finger's scroll as a turn — in both suites, the same names (iOS's are
 * camel-cased); then the Android readers' own touches.
 */
class ReaderFullScreenTest {

    private val normal = State(fullScreen = false)
    private val full = ReaderFullScreen.fullScreen(normal, on = true)

    // ── The shared cases ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `1 normal reading shows the pill`() {
        assertTrue(ReaderFullScreen.pillShown(normal))
    }

    @Test
    fun `2 entering full screen leaves nothing on the page`() {
        assertEquals(State(fullScreen = true, controlsShown = false), full)
        assertFalse(ReaderFullScreen.pillShown(full))
    }

    @Test
    fun `3 the controls tap brings the pill back and a second tap puts it away`() {
        val peek = ReaderFullScreen.controlsTap(full)
        assertTrue(ReaderFullScreen.pillShown(peek))
        assertFalse(ReaderFullScreen.pillShown(ReaderFullScreen.controlsTap(peek)))
    }

    @Test
    fun `4 a page turn puts the peek away`() {
        val peek = ReaderFullScreen.controlsTap(full)
        val turned = ReaderFullScreen.pageTurn(peek)
        assertEquals(full, turned)
        assertFalse(ReaderFullScreen.pillShown(turned))
    }

    @Test
    fun `5 a page turn with nothing peeking changes nothing`() {
        assertEquals(full, ReaderFullScreen.pageTurn(full))
        assertEquals(normal, ReaderFullScreen.pageTurn(normal))
    }

    @Test
    fun `6 the controls hiding on their own is a turn`() {
        val peek = ReaderFullScreen.controlsTap(full)
        assertEquals(ReaderFullScreen.pageTurn(peek), ReaderFullScreen.controlsHid(peek))
    }

    @Test
    fun `7 leaving full screen shows everything as before`() {
        val peek = ReaderFullScreen.controlsTap(full)
        val left = ReaderFullScreen.fullScreen(peek, on = false)
        assertEquals(normal, left)
        assertTrue(ReaderFullScreen.pillShown(left))
        // …from a hidden full screen too.
        assertEquals(normal, ReaderFullScreen.fullScreen(full, on = false))
    }

    @Test
    fun `8 full screen again after a peek starts clean`() {
        // A tap in normal reading changes nothing you can see, and never carries into full screen.
        val tappedInNormal = ReaderFullScreen.controlsTap(normal)
        assertTrue(ReaderFullScreen.pillShown(tappedInNormal))
        assertTrue(ReaderFullScreen.pillShown(ReaderFullScreen.pageTurn(tappedInNormal)))
        val again = ReaderFullScreen.fullScreen(tappedInNormal, on = true)
        assertFalse(ReaderFullScreen.pillShown(again))
        // Peek, leave, come back: nothing on the page.
        val back = ReaderFullScreen.fullScreen(
            ReaderFullScreen.fullScreen(ReaderFullScreen.controlsTap(full), on = false), on = true)
        assertFalse(ReaderFullScreen.pillShown(back))
    }

    // ── The rule's invariant, over every state it can reach (iOS's twin) ─────────────────────────

    @Test
    fun `pill shows exactly when not full screen or controls shown`() {
        val reached = mutableListOf<State>()
        var s = normal
        reached += s
        s = ReaderFullScreen.controlsTap(s); reached += s
        s = ReaderFullScreen.fullScreen(s, on = true); reached += s
        s = ReaderFullScreen.controlsTap(s); reached += s
        s = ReaderFullScreen.pageTurn(s); reached += s
        s = ReaderFullScreen.controlsHid(ReaderFullScreen.controlsTap(s)); reached += s
        s = ReaderFullScreen.fullScreen(s, on = false); reached += s
        for (r in reached) assertEquals("$r", !r.fullScreen || r.controlsShown, ReaderFullScreen.pillShown(r))
    }

    // ── The middle tap (iOS's `middleTap`; Michael 2026-10-12: the B6 does what the iPhone does) ──

    @Test
    fun `the middle tap goes into full screen, then brings the pill back`() {
        // iOS testMiddleTapIntoFullScreenThenBringsThePanelBack, case for case.
        var s = ReaderFullScreen.middleTap(normal)
        assertTrue("in normal reading the middle tap goes into full screen", s.fullScreen)
        assertFalse("…with nothing on the page", ReaderFullScreen.pillShown(s))
        assertEquals("the ⛶ seat's way in, exactly", full, s)
        s = ReaderFullScreen.middleTap(s)
        assertTrue("in full screen it brings the pill back over the page", s.fullScreen && s.controlsShown)
        assertTrue(ReaderFullScreen.pillShown(s))
        s = ReaderFullScreen.middleTap(s)
        assertFalse("and takes it away", ReaderFullScreen.pillShown(s))
        assertTrue("full screen stays", s.fullScreen)
    }

    @Test
    fun `in full screen the middle tap is the controls tap`() {
        assertEquals(ReaderFullScreen.controlsTap(full), ReaderFullScreen.middleTap(full))
        val peek = ReaderFullScreen.controlsTap(full)
        assertEquals(ReaderFullScreen.controlsTap(peek), ReaderFullScreen.middleTap(peek))
    }

    @Test
    fun `the full cell leaves from a peek and enters from normal`() {
        // iOS testFullCellLeavesFromAPeekAndEntersFromNormal, case for case: ⛶ in, the middle tap's
        // peek, ⛶ out.
        var s = ReaderFullScreen.fullScreen(normal, on = true)
        assertTrue("⛶ from normal reading goes into full screen", s.fullScreen)
        assertFalse(ReaderFullScreen.pillShown(s))
        s = ReaderFullScreen.middleTap(s)
        s = ReaderFullScreen.fullScreen(s, on = false)
        assertEquals("⛶ in the peek leaves full screen — the panel stays through turns", normal, s)
        s = ReaderFullScreen.pageTurn(s)
        assertTrue(ReaderFullScreen.pillShown(s))
    }

    @Test
    fun `a turn in the middle tap's peek puts the pill away, the next middle tap brings it back`() {
        val peek = ReaderFullScreen.middleTap(ReaderFullScreen.middleTap(normal))
        val turned = ReaderFullScreen.pageTurn(peek)
        assertEquals(full, turned)
        assertTrue(ReaderFullScreen.middleTap(turned).let { it.fullScreen && ReaderFullScreen.pillShown(it) })
    }

    @Test
    fun `a comic walk's whole page leaves normal reading unchanged`() {
        // The walk acts only in full screen (iOS: `fs.fullScreen && !fs.peeking`): in normal reading
        // neither the whole page arriving nor going back to the panel changes anything — never full
        // screen, and no hidden peek left in the state.
        val panel = ReaderFullScreen.WalkAt(page = 3, panel = 1, whole = false, zoomed = false)
        val whole = panel.copy(whole = true)
        val show = ReaderFullScreen.walkMove(panel, whole)
        val back = ReaderFullScreen.walkMove(whole, panel)
        assertEquals(ReaderFullScreen.WalkMove.SHOW, show)
        assertEquals(ReaderFullScreen.WalkMove.TOGGLE, back)
        assertEquals("the whole page in normal reading", normal, ReaderFullScreen.walkStep(normal, show))
        assertEquals("back to the panel in normal reading", normal, ReaderFullScreen.walkStep(normal, back))
        assertEquals(normal, ReaderFullScreen.walkStep(normal, ReaderFullScreen.WalkMove.TURN))
        // …where the middle tap (a page with no panels, art or margins) does go in.
        assertTrue(ReaderFullScreen.middleTap(normal).fullScreen)
    }

    @Test
    fun `in full screen the walk's whole page brings the controls, a turn takes them`() {
        val peek = ReaderFullScreen.controlsTap(full)
        assertEquals(peek, ReaderFullScreen.walkStep(full, ReaderFullScreen.WalkMove.SHOW))
        assertEquals("already peeking: the whole page brings nothing new",
            peek, ReaderFullScreen.walkStep(peek, ReaderFullScreen.WalkMove.SHOW))
        assertEquals("back to the panel toggles", full, ReaderFullScreen.walkStep(peek, ReaderFullScreen.WalkMove.TOGGLE))
        assertEquals(peek, ReaderFullScreen.walkStep(full, ReaderFullScreen.WalkMove.TOGGLE))
        assertEquals(full, ReaderFullScreen.walkStep(peek, ReaderFullScreen.WalkMove.TURN))
        assertEquals(peek, ReaderFullScreen.walkStep(peek, ReaderFullScreen.WalkMove.NONE))
        for (m in ReaderFullScreen.WalkMove.values())
            for (s in listOf(full, peek)) assertTrue("$m from $s stays in full screen",
                ReaderFullScreen.walkStep(s, m).fullScreen)
    }

    @Test
    fun `only iOS's middle goes into full screen, the setting's centre brings the controls`() {
        // Android's book, PDF and comic "centre" is the tap-zone setting's (by default all but the
        // bottom band): outside iOS's middle 44% — where iOS turns the page — normal reading stays.
        val edge = ReaderFullScreen.inCentre(0.1f)
        val middle = ReaderFullScreen.inCentre(0.5f)
        assertEquals("an edge tap in normal reading changes nothing", normal,
            ReaderFullScreen.middleTap(normal, inMiddle = edge))
        assertEquals("the middle goes in", full, ReaderFullScreen.middleTap(normal, inMiddle = middle))
        // In full screen it is the controls tap wherever the setting calls it the centre.
        assertEquals(ReaderFullScreen.controlsTap(full), ReaderFullScreen.middleTap(full, inMiddle = edge))
        val peek = ReaderFullScreen.controlsTap(full)
        assertEquals(full, ReaderFullScreen.middleTap(peek, inMiddle = edge))
        assertEquals(ReaderFullScreen.middleTap(peek), ReaderFullScreen.middleTap(peek, inMiddle = middle))
    }

    @Test
    fun `pill shows exactly when not full screen or controls shown, middle taps included`() {
        val reached = mutableListOf<State>()
        var s = normal
        repeat(5) { s = ReaderFullScreen.middleTap(s); reached += s }
        s = ReaderFullScreen.pageTurn(s); reached += s
        s = ReaderFullScreen.middleTap(ReaderFullScreen.fullScreen(s, on = false)); reached += s
        for (r in reached) assertEquals("$r", !r.fullScreen || r.controlsShown, ReaderFullScreen.pillShown(r))
        // The middle tap never leaves full screen: only ⛶ (or the strip) does.
        assertTrue(reached.all { it.fullScreen })
    }

    // ── The comic's walk and double-tap window (both suites) ────────────────────────────────────

    @Test
    fun `a panel walk's whole page brings the controls, every other step is a turn`() {
        // The walk's centre tap: panel → whole page.
        assertTrue(ReaderFullScreen.walkShowsControls(wasWhole = false, whole = true))
        // Back to the panel, the next panel, the next page, a zoom: turns.
        assertFalse(ReaderFullScreen.walkShowsControls(wasWhole = true, whole = false))
        assertFalse(ReaderFullScreen.walkShowsControls(wasWhole = false, whole = false))
        // A re-post while the whole page stays up shows nothing new.
        assertFalse(ReaderFullScreen.walkShowsControls(wasWhole = true, whole = true))
    }

    @Test
    fun `a comic waits its double-tap window before showing the pill`() {
        // comic-gestures.js G.DOUBLE_MS — the two must agree, or a double-tap zoom flashes the pill.
        val js = listOf(java.io.File("src/main/assets/foliate/comic-gestures.js"),
            java.io.File("app/src/main/assets/foliate/comic-gestures.js")).first { it.isFile }.readText()
        val double = Regex("""DOUBLE_MS:\s*(\d+)""").find(js)?.groupValues?.get(1)?.toLong()
        assertEquals(double, ReaderFullScreen.COMIC_DOUBLE_MS)
        // Android holds it a little longer: the zoom's step is posted only after its crop is laid.
        assertTrue(ReaderFullScreen.COMIC_CENTRE_WAIT_MS > ReaderFullScreen.COMIC_DOUBLE_MS)
    }

    // ── A finger's scroll as a turn (both suites, iOS's ScrollTurn) ──────────────────────────────

    @Test
    fun `a scroll is a turn once it goes a quarter screen`() {
        val t = ReaderFullScreen.ScrollTurn()
        t.began(offset = 100, height = 800)
        assertFalse(t.scrolled(150))
        assertFalse(t.scrolled(299))
        assertTrue("200 of 800 — a quarter screen", t.scrolled(300))
        assertFalse("once per drag", t.scrolled(900))
    }

    @Test
    fun `a scroll up counts too`() {
        val t = ReaderFullScreen.ScrollTurn()
        t.began(offset = 1000, height = 800)
        assertTrue(t.scrolled(790))
    }

    @Test
    fun `the app's own scroll is never a turn`() {
        val t = ReaderFullScreen.ScrollTurn()
        assertFalse("no finger took the page — read-aloud's follow, a restore, Find", t.scrolled(5000))
        t.began(offset = 0, height = 800)
        t.ended()
        assertFalse("after the drag and its fling, scrolling is the app's again", t.scrolled(5000))
    }

    @Test
    fun `each drag counts afresh`() {
        val t = ReaderFullScreen.ScrollTurn()
        t.began(offset = 0, height = 800)
        assertTrue(t.scrolled(400))
        t.ended()
        t.began(offset = 400, height = 800)
        assertFalse(t.scrolled(500))
        assertTrue(t.scrolled(600))
    }

    @Test
    fun `a not yet measured screen never counts`() {
        val t = ReaderFullScreen.ScrollTurn()
        t.began(offset = 0, height = 0)
        assertFalse(t.scrolled(10_000))
    }

    // ── The Android readers' touches ─────────────────────────────────────────────────────────────

    @Test
    fun `the centre is the middle of the page, the iPad's zones`() {
        assertTrue(ReaderFullScreen.inCentre(0.5f))
        assertTrue(ReaderFullScreen.inCentre(0.28f))
        assertTrue(ReaderFullScreen.inCentre(0.72f))
        assertFalse(ReaderFullScreen.inCentre(0.27f))
        assertFalse(ReaderFullScreen.inCentre(0.73f))
        assertFalse(ReaderFullScreen.inCentre(0f))
        assertFalse(ReaderFullScreen.inCentre(1f))
    }

    @Test
    fun `a comic's step reads against the one before it`() {
        val panel = ReaderFullScreen.WalkAt(page = 3, panel = 1, whole = false, zoomed = false)
        val whole = panel.copy(whole = true)
        // The walk's centre tap: the whole page arriving shows the controls…
        assertEquals(ReaderFullScreen.WalkMove.SHOW, ReaderFullScreen.walkMove(panel, whole))
        // …and the tap back to the panel toggles them — never an extra tap after a peek went
        // another way (full screen left and re-entered over the whole page).
        assertEquals(ReaderFullScreen.WalkMove.TOGGLE, ReaderFullScreen.walkMove(whole, panel))
        // The same place posted again (a re-lay, a balloon push) is neither.
        assertEquals(ReaderFullScreen.WalkMove.NONE, ReaderFullScreen.walkMove(panel, panel))
        assertEquals(ReaderFullScreen.WalkMove.NONE, ReaderFullScreen.walkMove(whole, whole))
        // The next panel, the next page, a zoom in or out, a hot zoom, a first step: turns.
        assertEquals(ReaderFullScreen.WalkMove.TURN, ReaderFullScreen.walkMove(panel, panel.copy(panel = 2)))
        assertEquals(ReaderFullScreen.WalkMove.TURN, ReaderFullScreen.walkMove(whole, panel.copy(panel = 2)))
        assertEquals(ReaderFullScreen.WalkMove.TURN,
            ReaderFullScreen.walkMove(panel, ReaderFullScreen.WalkAt(page = 4, panel = 0, whole = false, zoomed = false)))
        assertEquals(ReaderFullScreen.WalkMove.TURN, ReaderFullScreen.walkMove(panel, panel.copy(zoomed = true)))
        assertEquals(ReaderFullScreen.WalkMove.TURN, ReaderFullScreen.walkMove(panel.copy(zoomed = true), panel))
        assertEquals(ReaderFullScreen.WalkMove.TURN, ReaderFullScreen.walkMove(null, panel))
        // A book just opened: its first step may land before "loaded" — the whole page is still the
        // walk's centre tap.
        assertEquals(ReaderFullScreen.WalkMove.SHOW, ReaderFullScreen.walkMove(null, whole))
    }
}
