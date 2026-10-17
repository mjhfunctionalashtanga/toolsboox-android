package com.toolsboox.ot

/**
 * FULL SCREEN IN A READER LEAVES NOTHING ON THE PAGE (Michael 2026-10-12: "I need the full screen
 * button to be able to be fully hidden within a reader" + "IT cant block the comics :P" → "yeas").
 *
 * In every reader (comics, books, PDFs, RSS articles, letters) full screen puts the ⛶ pill and the
 * notes pill away too, so neither can sit over a comic panel. The SAME tap that brings back the
 * reader's controls brings them back; turning the page puts them away again. Lists and ink pages
 * keep their pills as they were.
 *
 * The one rule, in the same words on both apps (iOS: LedgerCore's ReaderFullScreen), as a pure
 * state machine so both test suites can pin the same cases:
 *  - state = { fullScreen, controlsShown }
 *  - the pill shows ⇔ !fullScreen || controlsShown
 *  - the reader's controls tap toggles controlsShown
 *  - a page turn (or the controls hiding on their own) sets controlsShown = false
 *  - full screen coming or going starts clean: leaving it shows everything as before, entering it
 *    leaves nothing on the page.
 * Where the dragged pill stands is not part of the state: hiding only flips its visibility, and
 * its spot is written by a drag alone.
 *
 * THE MIDDLE TAP (Michael 2026-10-12, asked whether the B6 should do what the iPhone does: "yes").
 * The tap in the middle of the page is iOS's `middleTap`: in NORMAL reading it is the way INTO full
 * screen — the ⛶ seat's own door, the same e-ink transition ([TuckPanel.readerMiddleTap]); in full
 * screen it is the controls tap above. The comic walk's whole page is never a middle tap: it acts
 * only in full screen ([walkStep]; in normal reading it changes nothing at all), so a walk never goes
 * into full screen — iOS's guard (`walkShowsControls` acts only in full screen). A full screen the
 * middle tap brought is the piece's alone, as iOS keeps it per book, article and letter: never
 * remembered, and gone when the piece closes ([TuckPanel.readerPieceOver]).
 */
object ReaderFullScreen {

    data class State(val fullScreen: Boolean = false, val controlsShown: Boolean = false)

    /** The ⛶ pill (and the notes pill) show. */
    fun pillShown(s: State): Boolean = !s.fullScreen || s.controlsShown

    /** The reader's controls tap — the centre of the page. */
    fun controlsTap(s: State): State = s.copy(controlsShown = !s.controlsShown)

    /** A page turn: whatever the tap brought back goes away again. */
    fun pageTurn(s: State): State = if (s.controlsShown) s.copy(controlsShown = false) else s

    /** The reader's controls hid on their own — the same as a turn. */
    fun controlsHid(s: State): State = pageTurn(s)

    /** Full screen came ([on]) or went: nothing is peeking either way. */
    fun fullScreen(s: State, on: Boolean): State = State(fullScreen = on, controlsShown = false)

    /** The middle tap (iOS `ReaderFullScreen.middleTap`, case for case): in normal reading INTO full
     *  screen, nothing on the page; in full screen the controls tap — the ⛶ back, or away. */
    fun middleTap(s: State): State = if (s.fullScreen) controlsTap(s) else fullScreen(s, on = true)

    /**
     * The middle tap as the Android book, PDF and comic readers meet it (review 2026-10-12): their
     * "centre" is the reader's TAP-ZONE SETTING's — under the default 'corners' everything above the
     * bottom band, under 'off' the whole page — not iOS's middle 44%. So normal reading goes INTO
     * full screen only from inside iOS's middle ([inMiddle] = [inCentre] of the tap's x); outside it
     * (where iOS turns the page) the tap changes nothing, as before. In full screen the tap is the
     * controls tap wherever the setting calls it the centre, as before.
     */
    fun middleTap(s: State, inMiddle: Boolean): State = if (s.fullScreen || inMiddle) middleTap(s) else s

    // ── WHICH TOUCHES ARE WHICH EVENT (the Android readers' mapping) ───────────────────────────

    /** The centre of the page, as a fraction of its width: the middle 44%, the zones the iOS readers
     *  use (outer 28% each side turn the page there). */
    fun inCentre(xFraction: Float): Boolean = xFraction >= SIDE_ZONE && xFraction <= 1f - SIDE_ZONE

    /** A comic's panel walk has no centre tap of its own to hand over — its centre tap shows the
     *  WHOLE page — so that tap is the controls tap there: the whole page arriving brings the ⛶
     *  with it ([wasWhole] → [whole]: false → true). Any other walk step is a turn. */
    fun walkShowsControls(wasWhole: Boolean, whole: Boolean): Boolean = whole && !wasWhole

    /** Where a comic stands, as its "panelStep" says: the page, the walk's panel, the whole page
     *  (the walk's centre tap) and a zoom (a double-tap's, a drawn one, a spread's). */
    data class WalkAt(val page: Int, val panel: Int, val whole: Boolean, val zoomed: Boolean)

    /** What a comic's step means to full screen's peek ([walkMove]). */
    enum class WalkMove { NONE, SHOW, TOGGLE, TURN }

    /**
     * A comic's step, read against the one before it (review 2026-10-12, both apps):
     *  - the same place posted again (a re-lay, a balloon push) is neither a tap nor a turn;
     *  - the walk's centre tap — the same page and panel, the whole page flipping — is the controls
     *    tap: the whole page arriving brings the ⛶ ([walkShowsControls]); going back to the panel
     *    TOGGLES, so a peek that went another way (full screen left and re-entered over the whole
     *    page) never costs an extra tap;
     *  - anything else (the next panel or page, a zoom in or out, a hot zoom) is a turn.
     * With nothing to read against (a book just opened — its first step can land before "loaded"),
     * only the whole page's arrival is told apart: a page always lands with the walk on its panel,
     * so the whole page is the walk's centre tap.
     */
    fun walkMove(was: WalkAt?, now: WalkAt): WalkMove = when {
        was == now -> WalkMove.NONE
        was == null -> if (now.whole) WalkMove.SHOW else WalkMove.TURN
        was != null && was.page == now.page && was.panel == now.panel &&
            was.zoomed == now.zoomed && was.whole != now.whole ->
            if (walkShowsControls(was.whole, now.whole)) WalkMove.SHOW else WalkMove.TOGGLE
        else -> WalkMove.TURN
    }

    /**
     * A comic's step ([walkMove]) laid on the state — iOS's walk guard (review 2026-10-12): the walk
     * acts only in FULL SCREEN. The whole page arriving brings the controls when nothing is peeking
     * (iOS: `fs.fullScreen && !fs.peeking`); back to the panel toggles them (Android's: a peek that
     * went another way never costs an extra tap); a turn puts them away. In normal reading the walk's
     * taps change nothing — never full screen, and no hidden peek waiting in the state.
     */
    fun walkStep(s: State, move: WalkMove): State = when (move) {
        WalkMove.NONE -> s
        WalkMove.SHOW -> if (s.fullScreen && !s.controlsShown) controlsTap(s) else s
        WalkMove.TOGGLE -> if (s.fullScreen) controlsTap(s) else s
        WalkMove.TURN -> pageTurn(s)
    }

    /**
     * A FINGER'S SCROLL AS A TURN, in the readers that scroll rather than page (an article, a
     * letter) — iOS's `ReaderFullScreen.ScrollTurn`, the same rule and numbers (review 2026-10-12:
     * one rule on both apps): it counts once the page has moved a quarter of the screen since the
     * finger took it, once per drag (its fling included). The reader's own scrolling — read-aloud's
     * follow, a restored place, Find, a Page seat's own scroll (that one is a turn already) — never
     * starts one: only the finger's drag does.
     */
    class ScrollTurn {
        private var start: Int? = null
        private var height = 0
        private var spent = false

        /** The finger took the page at [offset], on a screen [height] tall. */
        fun began(offset: Int, height: Int) {
            start = offset
            this.height = height
            spent = false
        }

        /** The page is at [offset] now: true the once it has gone a quarter screen from where the
         *  finger took it. */
        fun scrolled(to: Int): Boolean {
            val s = start ?: return false
            if (spent || height <= 0) return false
            if (Math.abs(to - s) >= height * SCROLL_SHARE) {
                spent = true
                return true
            }
            return false
        }

        /** The drag (and its fling) is over: the next scroll is the app's until a finger takes it. */
        fun ended() {
            start = null
            spent = false
        }
    }

    /** How far, as a share of the screen's height, a finger's drag goes before it is a turn (iOS
     *  `ScrollTurn.share`). */
    const val SCROLL_SHARE = 0.25

    /** Each side zone's share of the page's width. */
    const val SIDE_ZONE = 0.28f

    /** A comic's double-tap window (comic-gestures.js `G.DOUBLE_MS`, iOS `comicDoubleMs`; the suites
     *  check the three agree): its first centre tap reaches the app before the second one zooms. */
    const val COMIC_DOUBLE_MS = 450L

    /** How long the app holds a comic's centre tap before showing the ⛶: the double-tap window and
     *  some slack, because the zoom's "panelStep" is posted only after its crop is laid (slow on the
     *  B6) — a second tap late in the window must still cancel the first, never flash the pill on
     *  and off (two e-ink updates; review 2026-10-12). */
    const val COMIC_CENTRE_WAIT_MS = COMIC_DOUBLE_MS + 150L

    /** The ⛶ pill's name while it is the way out of full screen (iOS `ReaderFullScreen.leaveName`). */
    const val LEAVE_NAME = "Leave full screen"
}
