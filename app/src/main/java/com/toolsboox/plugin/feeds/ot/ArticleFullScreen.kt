package com.toolsboox.plugin.feeds.ot

import com.toolsboox.ot.ReaderFullScreen
import com.toolsboox.ot.TuckPanel

/**
 * The article readers' share of [ReaderFullScreen] (Michael 2026-10-12: full screen in a reader
 * leaves nothing on the page; the controls tap brings the ⛶ back, a page turn puts it away).
 *
 * The MIDDLE TAP is a plain tap in the middle of the page — the zones the iOS article and letter
 * readers use (their outer 28% each side turn the page) — never on a link, a field, a player, a
 * button or a selection, and never the first half of a double-tap zoom
 * ([SelectionMenuWebView.onPlainTap]). It is iOS's `middleTap` (Michael 2026-10-12, "should the
 * B6 do the same?" → "yes"): in normal reading INTO full screen — the ⛶ seat's own door, the Feeds
 * column's ⛶ or the tucked-strip reader's ✕ (the letter, the standalone article) — and in full
 * screen the controls tap ([TuckPanel.readerMiddleTap]).
 *
 * A FINGER'S SCROLL is a turn once that drag has moved a quarter of the screen
 * ([ReaderFullScreen.ScrollTurn], iOS's rule, review 2026-10-12): counted per drag, from where the
 * finger took the page, its fling included — the reader's own scrolling (read-aloud's follow, a
 * restored place, Find) never counts. The page jumps (tap zones, volume keys, the rail's Page
 * seats) call [TuckPanel.readerPageTurn] themselves, before they move the page.
 */
object ArticleFullScreen {

    /** Wire [web]'s centre tap and scroll to [panel] while [reading] (a piece is open in it). */
    fun wire(web: SelectionMenuWebView, panel: () -> TuckPanel?, reading: () -> Boolean = { true }) {
        web.onPlainTap = { fx ->
            val p = panel()
            if (p != null && reading() && ReaderFullScreen.inCentre(fx)) p.readerMiddleTap()
        }
        // TalkBack has no centre tap to find: a named action does the same (review 2026-10-12).
        web.setControlsAction { panel()?.takeIf { reading() }?.readerMiddleTap() }
        val turn = ReaderFullScreen.ScrollTurn()
        // The drag's fling carries on after the finger lifts: the drag stays live until the page has
        // been still this long, then scrolling is the app's again.
        var settle: Runnable? = null
        fun armSettle() {
            settle?.let { web.removeCallbacks(it) }
            val r = Runnable { settle = null; turn.ended() }
            settle = r
            web.postDelayed(r, FLING_SETTLE_MS)
        }
        web.onFinger = { down ->
            if (down) {
                settle?.let { web.removeCallbacks(it) }
                settle = null
                turn.began(web.scrollY, web.height)
            } else armSettle()
        }
        web.setOnScrollChangeListener { _, _, y, _, _ ->
            if (settle != null) armSettle()   // still flinging
            val p = panel() ?: return@setOnScrollChangeListener
            if (turn.scrolled(y) && p.readerControlsShown) p.readerPageTurn()
        }
    }

    /** A lifted finger's fling is over once the page has been still this long. */
    private const val FLING_SETTLE_MS = 300L
}
