package com.toolsboox.ui.plugin

import android.content.Context
import com.toolsboox.R
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

        // MONOCHROME FACES, ONE WORD EACH (Michael 2026-10-11, the one panel language: "Icons lack
        // design language"). The emoji transport (⏮ ⏸ ▶ ⏭ 🌙 ★ 📝 ⏹) drew in colour on LCD and in
        // grey dither on e-ink; these are the house's vector faces, captioned Back 30 · Play/Pause ·
        // Fwd 30 · Sleep · <speed> · Star ep · Note ep · Stop. The ep captions (and ringed faces)
        // keep the moment's ★/✎ from wearing the reading Star's and Note's faces on the same column.
        out += TuckPanel.Item(R.drawable.ic_pf_back30, "Back 30s", caption = "Back 30") {
            LedgerPlayer.skipBack(30); onChanged()
        }
        out += TuckPanel.Item(
            if (LedgerPlayer.isPlaying) R.drawable.ic_pf_pause else R.drawable.ic_pf_play,
            if (LedgerPlayer.isPlaying) "Pause" else "Play",
            caption = if (LedgerPlayer.isPlaying) "Pause" else "Play",
        ) {
            LedgerPlayer.toggle(); onChanged()
        }
        out += TuckPanel.Item(R.drawable.ic_pf_fwd30, "Forward 30s", caption = "Fwd 30") {
            LedgerPlayer.skipForward(30); onChanged()
        }
        // With Tool labels off there is no caption to carry a value, so the value IS the face —
        // the speed ("1.25×") and the time left ("12m") — never a state hidden with the words
        // (review 2026-10-11, F6). Labels on, the faces stay the monochrome drawables.
        val bare = !TuckPanel.labelsOn(context)
        // The timer's caption is what's left ("12m", "end") while it runs — and boxed, it's on.
        val sleep = LedgerPlayer.sleepLabel()
        out += TuckPanel.Item(if (bare && sleep != null) 0 else R.drawable.ic_pf_moon, "Sleep timer",
            glyph = if (bare && sleep != null) sleep else null,
            caption = sleep ?: "Sleep", on = sleep != null) {
            LedgerPlayer.cycleSleep(); onChanged()   // the box and the minutes follow at once
        }
        // Speed reads as its own value rather than a label, because the answer to "what speed am I
        // on" is the thing you want and a button marked "Speed" makes you press it to find out.
        val pace = "${LedgerPlayer.speed}×"
        out += TuckPanel.Item(if (bare) 0 else R.drawable.ic_pf_speed, "Speed · $pace",
            glyph = if (bare) pace else null, caption = pace) {
            LedgerPlayer.cycleSpeed(); onChanged()
        }

        // ★ and ✎ only for a track that carries a capturable identity — read-aloud of a page you
        // are already standing on has nothing to star that you do not already have.
        if (LedgerPlayer.capture != null) {
            out += TuckPanel.Item(R.drawable.ic_pf_star_ep, "Star this", caption = "Star ep") {
                LedgerPlayerCapture.starNow(context); onChanged()
            }
            out += TuckPanel.Item(R.drawable.ic_pf_note_ep, "Note on this", caption = "Note ep") {
                LedgerPlayerCapture.annotateNow(context); onChanged()
            }
        }

        out += TuckPanel.Item(R.drawable.ic_pf_stop, "Stop", caption = "Stop") {
            LedgerPlayer.stop(); onChanged()
        }
        return out
    }
}
