package com.toolsboox.ot

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import com.toolsboox.R

/**
 * THE BOTTOM PILL — one oblong floating where the notes circle always floated (Michael
 * 2026-10-11, looking at the full-screen Synthesize page: "I feel like we could share the same
 * pill menu thing and be at the bottom and still have the long hold work on it, and it just be
 * more of an oblong than a circle" → "The one top left and the one bottom right").
 *
 * Two halves, `[corner | notes]`:
 *  - CORNER (ink pages only): wears the last-chosen action — ⛶ Full screen, Pen, Eraser or Undo
 *    ("Put only one tuck item … and let you hold the button to select one of the other options",
 *    "repeat whatever was last, long hold you change"). Tap repeats it; hold opens the four, and
 *    the pick becomes the tap, remembered on this device. Every ink page offers the four — the day
 *    and period faces too, as iOS's corner does on every page of ink (PARITY; its CornerAction) —
 *    and a page with no pen tools would offer Full screen only. With the eraser or the lasso in
 *    hand and the face not already saying so, the corner wears a small badge of that tool: in full
 *    screen the column that boxes the live tool is put away (review 2026-10-11). The page lends
 *    the half by registering a [Corner]; everywhere else the half is gone and the pill is the
 *    notes circle it always was.
 *  - NOTES: the notes button, unchanged — tap does what it did, hold opens its tray (Notes · Back
 *    to your reading · Text Notes · Synthesize · Today, iOS's rows in iOS's order).
 *
 * It replaces the full-screen pill that stood top-left in the date strip's row, so the strip has
 * the whole top row in both states. ⛶ stays the column's first seat in Normal only while the
 * corner wears something else (Michael 2026-10-12: "No need to see the full screen expand button
 * on the tuck if its on the note nib" — [columnWearsFull]).
 *
 * The whole pill moves: a drag on either half carries it anywhere on screen, clear of the column,
 * and it stays where you put it (one spot per device, on every screen — MainActivity's
 * makeFloatButtonDraggable; Michael 2026-10-12: "The note nib should be movable"). A tap and a
 * hold still fire; a drag never fires the tap.
 *
 * One view in the activity, over every screen. On an ink page it floats over the surface, so the
 * page cuts it out of its repaints, out of the B6 daemon's writes and out of the Onyx reader's
 * limit — the full-screen chrome seams the old pill rode ([Corner.pillMoved] tells the page each
 * time it comes, goes, moves or changes width). Every change here is one small View update: no
 * animation, one cheap e-ink redraw.
 */
class BottomPill(
    /** The oblong: the white rounded frame both halves sit in, and what a drag moves. */
    val container: LinearLayout,
    /** The corner half — GONE off the ink pages. */
    val cornerButton: ImageButton,
    /** The hairline between the halves, shown only while both are. */
    val divider: View,
    /** The notes half — the old floating notes button, kept whole. */
    val noteButton: ImageButton,
) {

    /** The corner half's actions, in the order the hold offers them. [key] is what is stored. */
    enum class Action(val key: String, val iconRes: Int, val label: String) {
        FULL("full", R.drawable.ic_fullscreen, "Full screen"),
        PEN("pen", R.drawable.ic_toolbar_pen, "Pen"),
        ERASER("eraser", R.drawable.ic_toolbar_eraser, "Eraser"),
        UNDO("undo", R.drawable.ic_toolbar_undo, "Undo"),
    }

    /** The ink page that lends the pill its corner half (its tuck panel). */
    interface Corner {
        /** An ink page with pen tools: the corner offers all four actions. False: Full screen only. */
        val hasPenTools: Boolean

        /** The page is in full screen — the corner's ⛶ is the way back ("Leave full screen"). */
        val inFullScreen: Boolean

        /** The tool in hand — [HAND_PEN], [HAND_ERASER], [HAND_LASSO] — or null (the column's
         *  boxed seat, which full screen puts away; the corner's face and badge say it then). */
        val toolInHand: String?

        /** Run one action — the corner's tap. */
        fun perform(action: Action)

        /** The corner's hold: show the four to pick from. */
        fun showPicker()

        /** The pill came, went, moved or changed width over this page: re-cut the surface. */
        fun pillMoved()

        /** The px this page's column holds at the screen's (left, right) edges — the pill stays
         *  clear of it, in Normal and full screen alike, so a ⛶ never moves the pill. */
        fun avoidPx(): Pair<Int, Int>

        /** The notes half's tray is about to open over this page ([content], not yet shown): the
         *  page pauses the pen before the window can take a tap and cuts the tray out of the B6
         *  daemon's writes and the Onyx limit while it is up — as it does for the corner's picker
         *  (review 2026-10-11: a pen tap on a tray glyph reached the daemon and inked the page). */
        fun trayOpened(content: View)

        /** The tray closed — a glyph, an outside tap, or a show that failed. */
        fun trayClosed()
    }

    /** The activity that carries the pill. */
    interface Host {
        val bottomPill: BottomPill?

        /** A reader in full screen with its controls put away (Michael 2026-10-12: full screen in a
         *  reader leaves nothing on the page — [ReaderFullScreen]): the notes half stands aside, or
         *  comes back. Transient, never saved — the pill's own switch and its dragged spot stay as
         *  they are. [owner] is the reader's panel: only the owner that put it away gives it back,
         *  so a reader built before the last one's view went keeps it away. */
        fun readerAway(owner: Any, away: Boolean) {}
    }

    private val context get() = container.context
    private val prefs get() = context.getSharedPreferences("ledger_widgets", 0)

    /** The page lending the corner half now, and who registered it (a page turn builds the new
     *  page before the old one's view goes, so only the owner may clear it). */
    var corner: Corner? = null
        private set
    private var owner: Any? = null

    /** The notes half's own switch: the quick-note toggle in the wrenches, and the one compose
     *  screen's stand-aside. The corner half ignores it — on an ink page it is the way back out
     *  of full screen, and that must never hide with a convenience switch. */
    var notesShown: Boolean = true
        set(v) { if (field != v) { field = v; refresh() } }

    /** Whether the corner half is drawn reversed (⛶'s receipt). */
    private var inverted = false

    /** What the corner half last drew — its face, its receipt, its box and its badge — so a
     *  resume or a re-registration that changes nothing invalidates nothing (each invalidate is
     *  an e-ink update over the page). */
    private var drawnFace: Action? = null
    private var drawnInverted: Boolean? = null
    private var drawnWithNotes: Boolean? = null
    private var drawnBoxed: Boolean? = null
    private var drawnBadge: String? = null

    /** The host re-clamps the pill's spot when the column it must stay clear of comes or goes. */
    var onClearanceChanged: () -> Unit = {}

    init {
        // Width changes (the corner half arriving with an ink page) and every re-lay are handed to
        // the page once laid out; it compares rects, so a re-lay that moved nothing costs nothing.
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> notifyMoved() }
        // The badge rides the corner cell's top-end corner, over the face, at its own size.
        cornerButton.foregroundGravity = Gravity.TOP or Gravity.END
        // TalkBack names the hold too (iOS: "Hold to choose: …"); the old pen cell wore a ▾.
        androidx.core.view.ViewCompat.replaceAccessibilityAction(cornerButton,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            "Choose: Full screen, Pen, Eraser, Undo", null)
    }

    fun setCorner(owner: Any, corner: Corner) {
        val changed = this.corner !== corner
        this.owner = owner
        this.corner = corner
        // A receipt the last page left lit (its restore died with it) never carries over.
        if (changed) inverted = false
        refresh()
        if (changed) onClearanceChanged()
    }

    fun clearCorner(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        corner = null
        refresh()
        onClearanceChanged()
    }

    /** What the corner's tap does right now. */
    fun chosen(): Action = chosenFor(storedPick(), corner?.hasPenTools == true)

    /** The corner's remembered pick on this device, raw (null: never picked — Full screen). */
    fun storedPick(): String? = prefs.getString(CHOSEN_KEY, null)

    /** A pick from the hold: it becomes the tap, on this device. */
    fun choose(action: Action) {
        prefs.edit().putString(CHOSEN_KEY, action.key).apply()
        refresh()
    }

    /** Lay the halves on: which show, and the corner's face. */
    fun refresh() {
        val cornerOn = corner != null
        val notesOn = notesShown
        setShown(cornerButton, cornerOn)
        setShown(noteButton, notesOn)
        setShown(divider, cornerOn && notesOn)
        setShown(container, cornerOn || notesOn)
        if (cornerOn) {
            val a = chosen()
            if (a != drawnFace) {
                drawnFace = a
                cornerButton.setImageResource(a.iconRes)
            }
        } else inverted = false
        paintCorner()
        // A half going GONE re-lays the frame, but the whole pill going GONE lays nothing out:
        // the page hears it here (it compares rects, so a repeat is free).
        notifyMoved()
    }

    /** The page's tool in hand or its full-screen state changed: re-dress the corner's box, badge
     *  and spoken name — nothing at all when none of them moved. */
    fun faceChanged() {
        if (corner != null) paintCorner()
    }

    /** ⛶'s receipt on the corner half: drawn black-on-white reversed, or restored. */
    fun invertCorner(on: Boolean) {
        if (inverted == on) return
        inverted = on
        paintCorner()
    }

    /** Tell the page lending the corner that the pill may have moved. */
    fun notifyMoved() {
        corner?.pillMoved()
    }

    private fun paintCorner() {
        val c = corner
        val shown = c != null && cornerButton.visibility == View.VISIBLE
        val face = if (shown) chosen() else null
        val hand = c?.toolInHand
        if (face != null) {
            val title = cornerTitle(face, c?.inFullScreen == true, hand)
            if (cornerButton.contentDescription?.toString() != title) cornerButton.contentDescription = title
        }
        val on = inverted && shown
        val withNotes = noteButton.visibility == View.VISIBLE
        val boxed = face != null && !on && boxedFor(face, hand)
        val badge = face?.let { badgeFor(it, hand) }
        if (badge != drawnBadge) {
            drawnBadge = badge
            cornerButton.foreground = badge?.let { badgeDrawable(it) }
        }
        if (on == drawnInverted && boxed == drawnBoxed && (!on || withNotes == drawnWithNotes)) return
        drawnInverted = on
        drawnBoxed = boxed
        drawnWithNotes = withNotes
        val density = context.resources.displayMetrics.density
        when {
            on -> {
                val r = 24 * density
                // Rounded on the outer side only while the notes half stands beside it, so the
                // black stays inside the oblong's own outline.
                val radii = if (withNotes)
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else FloatArray(8) { r }
                cornerButton.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadii = radii
                }
                cornerButton.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            boxed -> {
                // The live tool boxed as its column seat is (plan v6 §2.7's outline), inset so the
                // box never rides the oblong's own outline.
                val inset = (5 * density).toInt()
                cornerButton.background = android.graphics.drawable.InsetDrawable(
                    androidx.core.content.ContextCompat.getDrawable(context, R.drawable.rail_active_outline), inset)
                cornerButton.clearColorFilter()
            }
            else -> {
                cornerButton.setBackgroundColor(Color.TRANSPARENT)
                cornerButton.clearColorFilter()
            }
        }
    }

    /** The tool-in-hand badge: the tool's own glyph in a small outlined circle, top-end. */
    private fun badgeDrawable(hand: String): android.graphics.drawable.Drawable {
        val d = context.resources.displayMetrics.density
        val size = (17 * d).toInt()
        val circle = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(maxOf(1, d.toInt()), Color.BLACK)
            setSize(size, size)
        }
        val icon = androidx.core.content.ContextCompat.getDrawable(context,
            if (hand == HAND_LASSO) R.drawable.ic_toolbar_lasso else R.drawable.ic_toolbar_eraser)
            ?.mutate()?.apply { setTint(Color.BLACK) }
        val layers = android.graphics.drawable.LayerDrawable(listOfNotNull(circle, icon).toTypedArray())
        if (icon != null) {
            val inner = (11 * d).toInt()
            layers.setLayerSize(1, inner, inner)
            layers.setLayerGravity(1, Gravity.CENTER)
        }
        val air = (3 * d).toInt()
        return android.graphics.drawable.InsetDrawable(layers, 0, air, air, 0)
    }

    private fun setShown(v: View, on: Boolean) {
        val want = if (on) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }

    companion object {
        /** The corner's remembered pick, one per device (`ledger_widgets`, as the tool labels). */
        const val CHOSEN_KEY = "pill_corner_action"

        /** The tools in hand, as the column's seats name them ([TuckPanel.markActive] ids). */
        const val HAND_PEN = "pen"
        const val HAND_ERASER = "eraser"
        const val HAND_LASSO = "lasso"

        /** The actions the corner's hold offers: all four with pen tools, else Full screen. */
        fun offered(hasPenTools: Boolean): List<Action> =
            if (hasPenTools) Action.entries.toList() else listOf(Action.FULL)

        /**
         * The corner's tap for a stored pick, pure: the pick while this page offers it, else Full
         * screen — a page with no pen tools always wears ⛶, and keeps the pick for the next ink
         * page (it is never overwritten by being unavailable). Nothing stored = Full screen.
         */
        fun chosenFor(stored: String?, hasPenTools: Boolean): Action {
            val pick = Action.entries.firstOrNull { it.key == stored } ?: Action.FULL
            return if (pick in offered(hasPenTools)) pick else Action.FULL
        }

        /**
         * Does an ink page's column still wear its own ⛶ head? Not while the corner wears ⛶
         * (Michael 2026-10-12: "No need to see the full screen expand button on the tuck if its on
         * the note nib") — one ⛶ per screen. With Pen, Eraser or Undo on the corner the column
         * keeps it, so full screen never sits behind the corner's hold (the no-feature-loss rule:
         * one tap from either state, as before).
         */
        fun columnWearsFull(stored: String?, hasPenTools: Boolean): Boolean =
            chosenFor(stored, hasPenTools) != Action.FULL

        /**
         * Does a pick from the hold RUN, or only become the tap? Picking a tool only ever picks it
         * up (review 2026-10-11; iOS's pickCorner): with that tool already in hand the pick just
         * becomes the tap, so choosing Pen never throws the chooser up and choosing Eraser never
         * puts the eraser down.
         */
        fun pickRuns(action: Action, hand: String?): Boolean = when (action) {
            Action.PEN -> hand != HAND_PEN
            Action.ERASER -> hand != HAND_ERASER
            else -> true
        }

        /** The face is the tool in hand: boxed, as its column seat is. */
        fun boxedFor(face: Action, hand: String?): Boolean =
            (face == Action.PEN && hand == HAND_PEN) || (face == Action.ERASER && hand == HAND_ERASER)

        /** The badge the corner wears: the eraser or lasso in hand, unless the face already says
         *  so (an Eraser face with the eraser in hand is boxed instead). Null with the pen. */
        fun badgeFor(face: Action, hand: String?): String? = when (hand) {
            HAND_ERASER -> hand.takeIf { face != Action.ERASER }
            HAND_LASSO -> hand
            else -> null
        }

        /** The corner's spoken name — what the TAP does now, and the tool in hand when the badge
         *  says it (iOS's cornerTitle, word for word). */
        fun cornerTitle(face: Action, full: Boolean, hand: String?): String {
            val base = when (face) {
                Action.FULL -> if (full) "Leave full screen" else "Full screen"
                Action.ERASER -> if (hand == HAND_ERASER) "Eraser — tap for the pen" else "Eraser"
                Action.PEN -> if (hand == HAND_PEN) "Pen — tap to choose a pen" else "Pen"
                Action.UNDO -> "Undo"
            }
            return when (badgeFor(face, hand)) {
                HAND_ERASER -> "$base · eraser in hand"
                HAND_LASSO -> "$base · lasso in hand"
                else -> base
            }
        }

        /** The hold's cell names: the action's plain name, Full screen saying which way it goes. */
        fun rowTitle(action: Action, full: Boolean): String =
            if (action == Action.FULL && full) "Leave full screen" else action.label
    }
}
