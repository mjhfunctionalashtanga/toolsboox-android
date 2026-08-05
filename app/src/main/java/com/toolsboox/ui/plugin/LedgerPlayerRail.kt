package com.toolsboox.ui.plugin

import android.content.Context
import com.toolsboox.ot.TuckPanel

/**
 * The transport, AS RAIL ITEMS.
 *
 * Michael, 2026-08-05: "instead of a pop-up for the player, building the player into the sub menu
 * makes sense." It does, and the reason is the same one that retired the floating pills: a modal
 * over the page is a second surface competing with the first. To skip back ten seconds you had to
 * summon a dialog, which covered the thing you were reading — and on a podcast the thing you are
 * reading is usually the episode's own notes, i.e. exactly what you opened the player to follow.
 *
 * The rail is the right home because it is already the answer to "what can I do here": it tucks
 * away, it scrolls, and it never covers the page. A transport that lives in it is available
 * without being present, which is what a transport should be.
 *
 * These items are STATE-DEPENDENT, so callers must hand them to `setupActionRail`'s provider (which
 * is re-asked on `rebuildActionRail`) rather than capturing them once — ▶ has to become ⏸ when the
 * track starts, and the whole block must vanish when nothing is playing.
 */
object LedgerPlayerRail {

    /**
     * The transport for whatever is playing, or EMPTY when nothing is.
     *
     * Empty rather than greyed: the rail is a list of what you can do, and five dead buttons on
     * every surface that has ever played something is how a rail becomes a toolbar.
     *
     * [onChanged] is called after any action that alters transport state, so the host can re-dress
     * the rail — without it, ▶ stays ▶ after you press it, which reads as the button not working.
     */
    fun items(context: Context, onChanged: () -> Unit = {}): List<TuckPanel.Item> {
        if (!LedgerPlayer.isActive) return emptyList()
        val out = mutableListOf<TuckPanel.Item>()

        out += TuckPanel.Item(0, "Back 30s", glyph = "⏮") {
            LedgerPlayer.skipBack(30); onChanged()
        }
        out += TuckPanel.Item(
            0, if (LedgerPlayer.isPlaying) "Pause" else "Play",
            glyph = if (LedgerPlayer.isPlaying) "⏸" else "▶",
        ) {
            LedgerPlayer.toggle(); onChanged()
        }
        out += TuckPanel.Item(0, "Forward 30s", glyph = "⏭") {
            LedgerPlayer.skipForward(30); onChanged()
        }
        // Speed reads as its own value rather than a label, because the answer to "what speed am I
        // on" is the thing you want and a button marked "Speed" makes you press it to find out.
        out += TuckPanel.Item(0, "Speed", glyph = "${LedgerPlayer.speed}×") {
            LedgerPlayer.cycleSpeed(); onChanged()
        }

        // ★ and 📝 only for a track that carries a capturable identity — read-aloud of a page you
        // are already standing on has nothing to star that you do not already have.
        if (LedgerPlayer.capture != null) {
            out += TuckPanel.Item(0, "Star this", glyph = "★") {
                LedgerPlayerCapture.starNow(context); onChanged()
            }
            out += TuckPanel.Item(0, "Note on this", glyph = "📝") {
                LedgerPlayerCapture.annotateNow(context); onChanged()
            }
        }

        out += TuckPanel.Item(0, "Stop", glyph = "⏹") {
            LedgerPlayer.stop(); onChanged()
        }
        return out
    }
}
