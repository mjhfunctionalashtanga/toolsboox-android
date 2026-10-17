package com.toolsboox.ot

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.toolsboox.R
import com.toolsboox.databinding.ToolbarDrawingBinding

/**
 * The tucked action rail — the Weeks side toolbar's idiom, taken over for a surface's whole
 * action/nav layer.
 *
 * The reference is upstream toolsBoox's `toolbar_drawing` column as it still lives on the Weeks
 * page: a slim icon rail, one button wide, full height, sitting IN ITS OWN GUTTER beside the
 * page (in the layout flow — `CalendarUtils.updateToolbar` constrains the navigator, template
 * and drawing surface BESIDE it, so the page narrows rather than being covered), side-switchable
 * left↔right, and collapsible to nothing but a thin strip on the edge. Michael: "it tucks in and
 * tucks right out, and you could just put it on any side… It can be fully hidden like the weeks
 * panel." This class rebuilds that rail on the toolbar's own root view with the app's current
 * vocabulary — the house icon set, pill-scale sizing, the pill's active-tool treatment — instead
 * of upstream's fixed XML button chain, so a converted surface can put ANY actions on it.
 *
 * WHY IN-FLOW, SPELLED OUT ONCE: an overlay panel floats over ink, needs raw-pen exclude rects,
 * a modal pen pause while open, and rotation compensation for its remembered position. A column
 * in the layout flow needs none of that — the drawing surface simply ends where the rail begins,
 * so the pen is live right up to the rail's edge by construction, and a rotation is just a
 * relayout. That is the whole argument for taking over `toolbarDrawing`'s slot in the constraint
 * chain rather than floating something new over the page.
 *
 * TUCKED (the list surfaces only, until their S3 builds — ink pages have ⛶ full screen instead,
 * below) = a thin strip flush on the edge, nothing else — no floating tab, no pill grip. The
 * strip is the summon affordance the Weeks hand already knows (tap it and the rail comes out);
 * it wears a hairline on its page side and the pills' muted grip dots, so it reads as a
 * deliberate handle rather than the bare gray band the upstream collapse leaves.
 *
 * E-INK: open and tuck are single width/visibility flips — no animation frames — followed by the
 * house full-panel clean (`repaintEveryThing(GC)`, as `reapplyPillScale` does after a resize,
 * because a chrome change leaves its old outline ghosted).
 *
 * INK PAGES DON'T TUCK (plan v6 S1.2, Michael 2026-10-10: "⛶ replaces ✕ Tuck and the edge strip").
 * The date strip spans the whole top and the column starts beneath it (Michael 2026-10-10: "The
 * date strip should take up the whole top, the full should start beneath it"), led by a ⛶ cell
 * pinned above the rest. Where the seats outrun the column, the seats under ⛶ scroll (Michael
 * 2026-10-10: "scrollable if there are more buttons") — captions stay, nothing folds. Full screen
 * hides the column; the way back is the BOTTOM PILL's corner half ([BottomPill], Michael
 * 2026-10-11: "The one top left and the one bottom right" — the top-left pill and the notes circle
 * became one oblong at the bottom), which this rail lends as its [BottomPill.Corner]: tap repeats
 * the last-chosen action (⛶ Full screen, Pen, Eraser, Undo), hold picks another. Every ink page
 * offers all four, the faces too, as iOS's corner does (PARITY, review 2026-10-11). The date strip
 * keeps the whole top row in both states. The column's slot goes
 * to the drawing surface and the page RE-CENTRES and fits it (Michael 2026-10-11: "Recentering/
 * zooming after closing the tuck panel makes sense"): portrait shows the whole page bigger and
 * centred, landscape fills the new width keeping its top row, a hand's zoom keeps its
 * magnification, and opening puts the docked fit back ([InkViewport], SurfaceFragment). That is
 * one surface resize per toggle — one B6 reconnect, the plan's budget. ✕, the strip and its hit
 * band remain on the list surfaces until their own S3 builds.
 */
class TuckPanel(
    private val host: Fragment,
    /** The upstream drawing toolbar whose slot the rail takes over on an ink surface — null on
     *  a surface that never had one, where the rail lives on a bare [gutter] instead. */
    private val toolbar: ToolbarDrawingBinding?,
    /** Prefs are keyed per surface: the day page tucking its rail must not tuck the Weeks
     *  toolbar, whose collapsed state lives under upstream's own `toolbarCollapsed` key. */
    private val surfaceKey: String,
    /** Which edge the rail is docked on right now — the host reads upstream's
     *  `calendarToolbarSide` pref, the same one the Weeks ⇄ button flips, so the rail hops
     *  edges with the exact affordance (and the exact remembered side) the toolbar always had. */
    private val sideIsLeft: () -> Boolean,
    /** Fired after every state application with the rail's current width, so the host can move
     *  anything of its own out of the gutter (the day page's top-left hamburger). */
    private val onStateApplied: (widthPx: Int, open: Boolean) -> Unit = { _, _ -> },
    /** The rail's slot on a surface WITHOUT an upstream drawing toolbar: an empty
     *  ConstraintLayout column the host layout keeps in its own flow (first or last in a
     *  horizontal row). OPEN, the in-flow argument holds — the page ends where the rail
     *  begins; TUCKED, the strip overlays the page edge instead (see `overlaysWhenTucked`
     *  below). Ignored when [toolbar] is present, which brings its own root. */
    gutter: ConstraintLayout? = null,
    /** A page you WRITE on (the ink pages, whose rail takes the drawing toolbar's slot). Its
     *  seats pack from the top and the spare room goes to the foot (Michael 2026-10-10, buttons
     *  on top; see [PALM_COMFORT_DP]). */
    private val writing: Boolean = toolbar != null,
    /** Ink pages: the date strip across the top, edge to edge in both states (the full-screen
     *  pill left its row for the bottom, Michael 2026-10-11). The column starts beneath it
     *  (Michael 2026-10-10) — its height never gives way, so the page under it never moves. */
    private val almanac: View? = null,
    /** Ink pages: the host's pen pause around the corner's picker (its onModalShown/Dismissed) —
     *  "pen paused while open", plan v6 §2.5. Closing, [picked] = a cell closed it on the lift, so
     *  the pen may come back at once; false = an outside tap or a hold, mid-contact. */
    private val onFlyout: (open: Boolean, picked: Boolean) -> Unit = { _, _ -> },
    /** Ink pages: the chrome that lies over the surface (the bottom pill, the picker) came, went
     *  or moved — the host re-cuts the B6 daemon's write paths around it. */
    private val onChromeChanged: () -> Unit = {},
    /** Ink pages: the bottom pill came, went or moved over the page — the host re-feeds the Onyx
     *  raw reader its exclude rects (once; nothing when they already agree) and keeps the pill's
     *  corner clear in its own repaints. */
    private val onPillMoved: () -> Unit = {},
    /** Ink pages: ms until a ⛶ toggle may run — the B6 daemon's layer stays live just after a
     *  stroke, and Michael's rule is never to toggle while it is on. 0 = now. */
    private val toggleWaitMs: () -> Long = { 0L },
    /** A list or reading surface on the ONE PANEL LANGUAGE ([PanelSeats], Michael 2026-10-11: one
     *  design language for every tuck panel): ⛶ heads the column in place of the ✕ at its foot,
     *  the blocks pack from the top with fixed gaps ([setBlocks]), and full screen hides the gutter
     *  and stands a small [⛶] pill in [fullPillHost]. Needs [fullPillHost]; ignored on ink pages. */
    fullCapable: Boolean = false,
    /** Where the full-screen [⛶] pill stands: the screen's top row (a LinearLayout — the pill takes
     *  the end the column docks on) or its page frame (a FrameLayout — the pill takes that side's
     *  top corner). [placeListPill], review 2026-10-11 F2. */
    private val fullPillHost: ViewGroup? = null
) : BottomPill.Corner {

    /** This panel speaks the one panel language as a list/reading surface — see [fullCapable]. */
    private val listFull: Boolean = fullCapable && toolbar == null && !writing && fullPillHost != null

    /**
     * One rail button. [activeId] marks the mutually-exclusive tool buttons (pen/eraser/lasso)
     * so [markActive] can box the live one; [longPress] is the button's second act (the pill
     * precedent: pen holds the full pen chooser, eraser holds clear-page) — buttons without one
     * toast their [name] on a long-press, the only labelling a rail gets with Tool labels off.
     */
    class Item(
        val iconRes: Int,
        val name: String,
        val activeId: String? = null,
        val longPress: (() -> Unit)? = null,
        /** A character to wear instead of a drawable — the house treatment for glyphs that
         *  have no icon (the chips' ✕): drawn crisp into a bitmap, pixel-aligned with the
         *  drawable icons, exactly as the directory rows draw theirs. */
        val glyph: String? = null,
        /** The word under the icon when Tool labels are on (≤ 8 characters, plan v6 §1). Null =
         *  derived from [name] by [captionFor]; [name] stays the hold-toast. */
        val caption: String? = null,
        /** THE SEAT IS ON (the one panel language, Michael 2026-10-11: "a box + bold caption marks
         *  what's on") — a drawer open, a search summoned, Reader view on. Drawn as the live tool's
         *  2dp outline and a bold caption; a redress flips it in place, one cell, no column
         *  repaint. Tool seats keep [activeId] and [markActive] for their one-of-three box. */
        val on: Boolean = false,
        val action: () -> Unit
    )

    private val context get() = host.requireContext()
    private val prefs get() = context.getSharedPreferences("ledger_widgets", 0)
    private val openKey = "tuck_${surfaceKey}_open"

    /** Ink pages: full screen, remembered per surface (plan v6 N22, Michael's Q1d — each surface
     *  its own), read before the first measure. */
    private val fullKey = "chrome_full_${surfaceKey}"

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /**
     * This ink surface's full-screen flag. The first read on a device that only knows the old tuck
     * key migrates it 1:1 — saved tucked → full screen (plan v6 N22; today's tucked look is "column
     * off, almanac on, pager pill", which full screen keeps) — and writes the new key, so the
     * migration runs once per surface and a later tuck key can never undo it.
     */
    private fun readFull(): Boolean {
        val p = prefs
        val full = migratedFull(p.contains(fullKey), p.getBoolean(fullKey, false),
            p.contains(openKey), p.getBoolean(openKey, true))
        if (!p.contains(fullKey)) p.edit().putBoolean(fullKey, full).apply()
        return full
    }

    /** The rail rides the PILL dial: it replaces the floating pills, so the dial that sized
     *  them ("Compact slims chrome into the reading margin") must keep its promise here. */
    private val scale get() = ModalScale.pillScale(context)

    /** The captions' face: the reading font the menus wear ([LedgerFonts]; System by default, so
     *  nothing changes unless a reader picked one — Atkinson Hyperlegible then reaches the
     *  captions too, review 2026-10-10). */
    private val captionFace: android.graphics.Typeface by lazy {
        LedgerFonts.typeface(context) ?: android.graphics.Typeface.DEFAULT
    }

    private val root: ConstraintLayout = toolbar?.root
        ?: requireNotNull(gutter) { "a rail needs either a toolbar slot or a gutter to live in" }

    /**
     * A GUTTER-HOSTED rail (the list surfaces) stops holding a column of the page when tucked:
     * the strip overlays the content's edge instead. The in-flow argument at the top of this
     * file is an INK argument — the drawing surface must end where the rail begins so the pen
     * is honest without exclude rects — and the list surfaces have no pen, so their tucked
     * strip was paying that argument's cost (every row stopped STRIP_DP short of the screen,
     * Michael 08-07: "side of screen inaccessible… visually short") for none of its benefit.
     * 1.06.45 halved the cost; this removes it, per Michael's ruling. The OPEN rail stays a
     * real column even here: open, it holds icons that must not sit over row text, and
     * narrowing the page while chrome is out is the deal every toolbar makes. Toolbar-hosted
     * rails (the ink pages) keep the column in both states — the pen argument is theirs.
     */
    private val overlaysWhenTucked = toolbar == null

    /** One seat on screen: the cell (the touch target, the outline when live), its icon, and
     *  the caption under it (GONE only while Tool labels are off). */
    private class Seat(var item: Item, val cell: LinearLayout, val icon: ImageView, val caption: TextView)

    private val activeById = HashMap<String, Seat>()

    /** The tool boxed right now — re-applied when a relabel rebuilds the cells. */
    private var activeId: String? = null

    /** Every seat in column order (⛶, top cluster, bottom cluster, the ✕) — what [applyFit]
     *  sizes. */
    private val seats = ArrayList<Seat>()

    /** Seats whose ON is the PAGE's, by name — [markOn]. Kept across a rebuild of the cells. */
    private val pageOn = HashSet<String>()

    /** A seat's face as the PAGE dresses it — [reface]. [inPlace]: its tap acts where it is, with
     *  no drag to follow (a Zoom ＋/− step), so full screen's row may stay up for a run of taps.
     *  [dim]: its tap would do nothing now (− at the whole page, ＋ at the ceiling) — the face drawn
     *  faint, as iOS draws it secondary; the caption and the cell stay as they are. [holdSpoken]:
     *  what a screen reader names its hold (iOS's "Hold to choose: …"), null = the platform's own. */
    private data class PageFace(val iconRes: Int, val caption: String, val spoken: String,
                                val inPlace: Boolean, val dim: Boolean, val holdSpoken: String? = null)

    /** Seats whose FACE is the page's, by name — Zoom wearing its last choice ([reface]). Kept
     *  across a rebuild of the cells, so a relabel rebuilding from the items it was first handed
     *  (and full screen's row) still wears the choice. */
    private val pageFaces = HashMap<String, PageFace>()

    /** The fit on screen, and the column height it was computed for. */
    private var fit: Fit? = null
    private var fitAvailPx = 0

    /** The clusters last handed to [setClusters], and the Tool labels state they were built
     *  under — a relabel rebuilds from these without asking the host again. */
    private var lastTop: List<Item> = emptyList()
    private var lastBottom: List<Item> = emptyList()
    private var builtLabels = false

    /** Column content: top cluster (tools), a breathing gap, bottom cluster (nav/surface) —
     *  the Weeks rail's two-cluster anatomy. fillViewport keeps the gap elastic. Where the seats
     *  outrun the column it SCROLLS (Michael 2026-10-10: "scrollable if there are more buttons"):
     *  captions stay and no seat folds into More for height ([fitColumn]). Plain scrolling — no
     *  palm stop at the foot (Michael 2026-10-10: the hand-rest idea was not what he meant).
     *
     *  On a WRITING page the elastic space moves to the foot instead (Michael 2026-10-10:
     *  buttons on top): the bottom cluster follows the tools after a fixed gap and the spare
     *  room gathers under it. The foot is inside the ScrollView, which swallows a palm on it —
     *  no tuck, no tap. The column sits beside the SurfaceView, never over it, so a scroll is a
     *  plain View redraw: no surfaceChanged, no exclude rect, nothing for the B6 daemon or the
     *  Onyx reader to hear (their rects cover the pill and flyout only, [chromeViews]). */
    private val topCluster = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bottomCluster = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Where this surface's column was last scrolled, put back at this panel's first layout
     *  (review 2026-10-10: a page turn builds a new panel, and the walk seats sat below the fold
     *  again every time). -1 once spent. */
    private var pendingScrollY = lastScrollY[columnKey()] ?: -1

    private val rail = object : ScrollView(context) {
        // No fling: a drag moves the column 1:1 and stops where the finger lifts. A fling glides
        // over many frames — a run of partial e-ink refreshes (plan v6 §2.7: no animation).
        override fun fling(velocityY: Int) {}

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            lastScrollY[columnKey()] = t
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            // Inside the layout pass already running: no extra pass, only a redraw.
            if (pendingScrollY > 0) scrollTo(0, pendingScrollY)
            pendingScrollY = -1
        }
    }.apply {
        isFillViewport = true
        // No edge glow: a translucent fade that dithers on e-ink (plan v6 §2.7).
        overScrollMode = View.OVER_SCROLL_NEVER
        // The only sign more seats sit below (review 2026-10-10): a still, solid-black thumb on
        // the column's edge — never fading (no animation, §2.7), and drawn only when the seats
        // outrun the column. Pre-Q the thumb can't be set and the stock one is translucent, so
        // the bar stays off there.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            verticalScrollbarThumbDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.BLACK)
            }
            scrollBarSize = dp(3)
            isScrollbarFadingEnabled = false
            isVerticalScrollBarEnabled = true
        } else {
            isVerticalScrollBarEnabled = false
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // MATCH_PARENT: every cell spans the column, so the caption's room is the column's.
            addView(topCluster, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // The one panel language (Michael 2026-10-11) has NO elastic gap: its blocks carry their
            // own fixed 12dp gaps inside [topCluster] ([setBlocks]), so this one shrinks to nothing.
            addView(Space(context), LinearLayout.LayoutParams(0, if (listFull) 0 else dp(CLUSTER_GAP_DP),
                if (writing || listFull) 0f else 1f))
            addView(bottomCluster, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Elastic only: it takes what a fitting column leaves and nothing once it scrolls —
            // spare room collects under Side, the buttons stay on top.
            if (writing || listFull) addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Ink pages: the ⛶ seat's own row, pinned ABOVE the scrolling seats, so ⛶ stays the first
     *  seat directly under the date strip however far the column is scrolled (plan v6 §3,
     *  "never hidden: ⛶"; Michael 2026-10-10). Empty on the list surfaces. */
    private val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The one panel language's FOOT (Side), pinned UNDER the scrolling seats while the column
     *  outruns the screen (review 2026-10-11, F1: on the B6 in landscape Side was the seat that
     *  fell off the end — the very seat Michael's field report asked for: "Side changed on tuck not
     *  available"). While the column fits, the foot cells ride in [topCluster] after the room, one
     *  gap on, so the spare room still collects under Side ([placeFoot]). Empty elsewhere. */
    private val footCluster = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The whole open column: [head] over the scrolling [rail], the pinned foot under it. */
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footCluster, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The tucked strip's handle mark — the pills' grip dots, centered on the strip. */
    private val stripMark = ImageView(context).apply {
        setImageResource(R.drawable.ic_grip)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Actions"
    }

    /** The column is showing. On an ink page the alternative is full screen ([fullKey]), never a
     *  tuck; elsewhere it is today's tucked strip. */
    var isOpen: Boolean = if (writing || listFull) !readFull() else prefs.getBoolean(openKey, true)
        private set

    /** Ink pages: the ⛶ cell — the column's first seat, directly under the date strip, pinned in
     *  [head] so it never scrolls away (plan v6 §3, "never hidden: ⛶"; Michael 2026-10-10: the
     *  strip takes the whole top, ⛶ starts beneath it). It replaces the ✕ that sat at the foot,
     *  so the seat count, and with it the column's width, is what S1.1 left. */
    private val fullItem = Item(R.drawable.ic_fullscreen, "Full screen", caption = "Full") {
        requestFull(true)
    }

    /** Ink pages: the pen tools the bottom pill's corner offers — the column's own pen, eraser,
     *  undo and redo items, and [pickUpPen]: the pen back in hand WITHOUT its chooser (out of the
     *  eraser or the lasso — the column's Pen tap opens the chooser while the lasso keeps the pen
     *  state). Every ink page passes them, the faces too (PARITY with iOS, review 2026-10-11).
     *  [zoom]: the column's Zoom seat (Michael 2026-10-12), offered in full screen's row — with
     *  the column put away it is the only way to the box and the steps (iOS's corner hold carries
     *  it too); the row's cell wears the seat's last choice ([reface]). */
    class PenTools(val pen: Item, val eraser: Item, val undo: Item, val redo: Item, val pickUpPen: () -> Unit,
                   val zoom: Item? = null)
    private var penTools: PenTools? = null

    /** The page has handed over its pen tools (or said it has none) — [fullHeadWanted]. */
    private var penToolsSaid = false

    /** Where the bottom pill last stood on screen while this rail lent it its corner (null while
     *  hidden): each new spot is handed to the host's cut-outs and exclude rects, once. */
    private var lastPillRect: Rect? = null

    /** The corner's picker (the four to choose from) while it hangs open over the page, and its
     *  rect on screen — the host cuts that rect out of the B6 daemon's writes while it is up.
     *  [flyoutPicked]: the close under way was a cell's (on the lift), not an outside tap. */
    private var flyout: android.widget.PopupWindow? = null
    private var flyoutScreenRect: Rect? = null
    private var flyoutPicked = false

    /** The notes half's tray while it hangs open over this page ([BottomPill.Corner.trayOpened]) —
     *  cut out and excluded like the picker. */
    private var trayView: View? = null

    /** The ⛶ debounce (1.5s from the tap, plan v6 §2.5) and a toggle waiting on the B6 daemon. */
    private var lastToggleMs = 0L
    private var pendingToggle: Runnable? = null

    /** The Tool labels width change, held to the same rule (review 2026-10-10): debounced 1.5s
     *  and waiting out the B6 layer — see [relabelWhenQuiet]. */
    private var lastRelabelMs = 0L
    private var pendingRelabel: Runnable? = null

    /**
     * The tucked strip's WIDENED touch target — a transparent hit band beside the gutter in its
     * host row, [STRIP_HIT_DP] wide where the drawn strip stays [STRIP_DP].
     *
     * The 1.06.50 overlay made the strip honest to the page and dishonest to the finger: 7dp
     * draws as the divider it is, but ~11px of glass is a target you stab at three times.
     * Widening the DRAWN strip back would undo the overlay's whole point, so the drawing and
     * the touching part company: this view lies over the overlay margin area (the band the
     * tucked strip already hands back to the content, so covering it costs no layout), takes
     * the tap, and forwards it to the root's own summon. It draws nothing — no background, no
     * e-ink repaint debt — and exists only while a gutter-hosted rail is tucked.
     *
     * GUTTER-HOSTED RAILS ONLY, deliberately. A 24dp invisible band over an ink page's edge
     * would swallow finger input on ink the drawn chrome doesn't claim — and since S1.2 an ink
     * page has no strip at all (⛶ and the pill replace it, Michael 2026-10-10). The list
     * surfaces have no pen and their content already runs under the strip; a band over their
     * edge is the same bargain the overlay already struck.
     */
    private var stripHit: View? = null

    init {
        // Children of the toolbar's OWN root — one level below drawing_layout, so the
        // ConstraintSet that CalendarUtils clones over drawing_layout never sees them and the
        // 0×0 rule (see updateToolbar's comment) doesn't apply. They still need constraining
        // within this ConstraintLayout, which is just "fill me" / "center on me".
        root.addView(column, ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })
        root.addView(stripMark, ConstraintLayout.LayoutParams(dp(10), dp(26)).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })

        if (overlaysWhenTucked) {
            // The tucked strip must DRAW and TOUCH above the content it now overlaps. In the
            // host row the gutter stands first when docked left, and a LinearLayout paints
            // children in order — the content would paint over the strip. A whisper of Z
            // re-sorts both the draw pass and the touch walk (ViewGroup orders by Z since 21)
            // without touching the child order the dock() re-parenting relies on. The outline
            // provider goes with it, or the elevation would grow a shadow — a gray smear on
            // an e-ink panel that only does gray by dithering.
            root.translationZ = 1f
            root.outlineProvider = null
        }

        // Before the first takeover lays the state on: the bottom pill wears this page's corner in
        // the same pass the column (or full screen) first shows.
        if (writing) registerCorner()

        assertTakeover()

        // The base class re-dresses the upstream toolbar from OUTSIDE onViewCreated — its
        // onResume re-wires the root tap and re-applies the shared `toolbarCollapsed` state
        // (which sets the button group VISIBLE), and the rotation path in surfaceChanged does
        // the same re-lay. The host calls [assertTakeover] after its own onResume, and this
        // watcher catches every other path: the moment the upstream group stands back up, the
        // rail takes the toolbar over again. No loop — the takeover leaves the group GONE, so
        // the next layout pass finds nothing to heal.
        root.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (toolbar != null && toolbar.toolbarButtonGroup.visibility == View.VISIBLE) assertTakeover()
            // The real column height has arrived (or changed): re-fit the cells against it.
            // Posted — never re-lay children from inside the pass that measured them.
            val h = columnPx(b - t)
            if (seats.isNotEmpty() && h > 0 && h != fitAvailPx) v.post { refit() }
            // The tucked strip hugs a screen edge, exactly where the system back gesture
            // lives; the Weeks toolbar excludes its collapsed strip for the same reason (its
            // comment: makes it "reliably tappable without needing to be wide").
            // The OPEN column claims nothing (review 2026-10-12): its seats are taps, and the left
            // edge's Back is now the way to the Hub (EdgeLadder) — Android honours the bottom-most
            // 200dp of a window's exclusion per edge, so a full-height claim here took the low
            // band of the column's edge away from that swipe.
            v.systemGestureExclusionRects =
                if (isOpen) emptyList() else listOf(Rect(0, 0, r - l, b - t))
        }

        // Tool labels is one global switch (plan v6 §2.2). A page that comes back to the front
        // after Settings › Legibility flipped it re-dresses before its first layout, so the
        // width changes once, with the surface it is already re-creating — never a second
        // B6 reconnect of its own.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (seats.isNotEmpty() && labelsOn(v.context) != builtLabels) relabel()
                if (writing) registerCorner()
                // A reader back on screen holds the notes pill away again if its full screen does.
                if (reader) applyReaderChrome()
            }
            override fun onViewDetachedFromWindow(v: View) {
                // The page is going: the pill's corner half goes with it, unless the next page has
                // already taken it (a page turn builds the new page first — the owner check).
                if (writing) bottomPill()?.clearCorner(this@TuckPanel)
                // The page is going (a page turn rebuilds the fragment): a toggle still waiting on
                // the daemon dies with it, and an open flyout must not outlive its window.
                pendingToggle?.let { root.removeCallbacks(it) }
                pendingToggle = null
                // (A relabel still waiting is picked up by the attach check above on the way back.)
                pendingRelabel?.let { root.removeCallbacks(it) }
                pendingRelabel = null
                flyout?.dismiss()
                // The notes tray is the activity's and may outlive this page: its pause is undone
                // here, while the page can still take it (the tray's own close finds it done).
                trayClosed()
                // A reader leaving gives the notes pill back to whatever comes next (the owner
                // check keeps a reader that already took it holding it).
                holdNotesAway(false)
            }
        })
        // (The tucked strip's palm-band listener left with the strip, S1.2: an ink page has no
        // strip, and in full screen the slot is the drawing surface's (Michael 2026-10-11) — it
        // answers the page's own pen and finger gestures, like the rest of the surface.)
    }

    /**
     * Take (or re-take) the toolbar's slot for the rail.
     *
     * The upstream toolbar's own chrome stands down on a converted surface: its button group
     * and its collapse toggle go GONE (the buttons keep answering performClick — that is how
     * the rail drives the real ink actions), and the rail becomes the root's only visible
     * content. The base class wires root taps to upstream's shared `toolbarCollapsed` state
     * EVERY resume; re-setting the root listener here is what unhooks that, or a tap on this
     * rail would fold the Weeks toolbar with it.
     */
    fun assertTakeover() {
        // On a gutter-hosted rail there is no upstream chrome to stand down — the slot was
        // built empty for the rail, so takeover is just wiring the summon tap and laying out.
        toolbar?.toolbarButtonGroup?.visibility = View.GONE
        toolbar?.toolbarToggle?.visibility = View.GONE
        root.visibility = View.VISIBLE
        if (listFull) {
            // The one panel language (Michael 2026-10-11): ⛶ and its pill are the only switches —
            // no root tap that tucks — and the remembered full screen is read again on resume.
            root.setOnClickListener(null)
            root.isClickable = false
            if (pendingToggle == null) isOpen = !readFull()
            applyState()
            return
        }
        if (writing) {
            // Ink pages (plan v6 S1.2, Michael 2026-10-10): the root tap goes. ⛶ and the pill are
            // the only switches, and the base class's shared toolbarCollapsed tap must not survive
            // the resume that re-wired it. The flag is read again, so a resume (or the rotation
            // re-lay) never brings the column back over a page left in full screen.
            root.setOnClickListener(null)
            root.isClickable = false
            if (pendingToggle == null) isOpen = !readFull()
            // A page back from another (a sheet, a pause) takes the pill's corner back.
            registerCorner()
        } else {
            root.setOnClickListener { if (isOpen) tuck() else open() }
        }
        applyState()
    }

    /**
     * Give the rail its buttons. [top] is the tools cluster, [bottom] the nav/surface cluster;
     * the collapse chevron rides at the very foot on its own — the rail owns its tuck the way
     * the upstream toolbar owned its toggle. On an ink page the rail owns ⛶ instead, at the head.
     */
    fun setClusters(top: List<Item>, bottom: List<Item>) {
        // On the one panel language the two clusters are simply two blocks (Michael 2026-10-11).
        if (listFull) { setBlocks(listOf(top, bottom)); return }
        // The way out is an ✕, not a chevron (Michael: "turn the < at the bottom of the panel
        // into a ✕ close button") — a chevron promises sliding, and the rail doesn't slide, it
        // tucks; ✕ is the house glyph for "put this away" (the image chips, the tag pills).
        val tuckItem = Item(0, "Tuck away", glyph = "✕") { tuck() }
        // Ink pages (plan v6 S1.2, Michael 2026-10-10): ⛶ replaces the ✕ — at the column's head,
        // directly under the date strip, not at the foot where the writing hand rests — while the
        // bottom pill's corner wears something else ([fullHeadWanted], Michael 2026-10-12).
        val headSeats = if (fullHeadWanted()) listOf(fullItem) else emptyList()
        val bottomSeats = if (writing) bottom else bottom + tuckItem
        val all = headSeats + top + bottomSeats
        // THE REDRESS RULE (B6, Michael 2026-10-11): a re-ask that hands back the same seats moves
        // no pixel — only the actions change hands.
        if (redress(all, listOf(top.size, bottom.size))) { lastTop = top; lastBottom = bottom; return }
        head.removeAllViews()
        topCluster.removeAllViews()
        bottomCluster.removeAllViews()
        footCluster.removeAllViews()
        footCells.clear()
        activeById.clear()
        seats.clear()
        lastTop = top
        lastBottom = bottom
        lastBlocks = emptyList()
        lastFoot = emptyList()
        builtShape = listOf(top.size, bottom.size)
        builtScale = scale
        builtLabels = labelsOn(context)
        // PRE-LAYOUT FIT (plan v6 §2.1, the TP:242-305 fix, Michael 2026-10-10). The old sizing
        // counted buttons while the column was still filling and budgeted against the whole
        // screen, so the first buttons came out full size and the tail was pushed off the
        // bottom into a scroll. Now the whole list is known first: the cells come from the real
        // column when it has been measured, else the column this surface last measured in this
        // orientation (review 2026-10-10: the display height counts a status bar the column never
        // gets, so a page turn drew the column twice), else the display height; every button is
        // built at the one fitted size, and the width stays the old rule's ([currentWidth]).
        val measured = columnPx(root.height)
        val avail = if (measured > 0) measured.also { lastColumnPx[columnKey()] = it }
            else lastColumnPx[columnKey()]
                // A writing column stands beneath the date strip (Michael 2026-10-10).
                ?: columnBeneathStrip(context.resources.displayMetrics.heightPixels, if (writing) bandPx() else 0)
        val f = computeFit(avail, all)
        // Column order is [seats]' order: ⛶, the tools, the nav cluster.
        for (item in headSeats) head.addView(makeButton(item, f.cellPx))
        for (item in top) topCluster.addView(makeButton(item, f.cellPx))
        for (item in bottomSeats) bottomCluster.addView(makeButton(item, f.cellPx))
        applyFit(f, avail)
        // A rebuild (relabel, a state-driven re-dress) keeps the live tool boxed.
        markActive(activeId)
        applyState()
        // The column's width is known now (and a relabel moves it): the pill re-seats clear of it.
        if (writing) bottomPill()?.takeIf { it.corner === this }?.onClearanceChanged?.invoke()
    }

    /**
     * Re-dress the column after the Tool labels switch flipped (More › Tool labels, or a page
     * returning from Settings › Legibility). Labels change the column's WIDTH, so on an ink page
     * this is exactly one surfaceChanged → one B6 reconnect, the cost of a tuck (plan v6 §2.8);
     * the post-layout refit after it moves only cell heights.
     */
    fun relabel() {
        if (lastTop.isEmpty() && lastBottom.isEmpty() && lastBlocks.isEmpty() && lastFoot.isEmpty()) return
        if (labelsOn(context) == builtLabels) return
        if (lastBlocks.isNotEmpty() || lastFoot.isNotEmpty()) setBlocks(lastBlocks, lastFoot)
        else setClusters(lastTop, lastBottom)
        epdClean()
    }

    /** The blocks and foot last handed to [setBlocks] — what a relabel rebuilds from. */
    private var lastBlocks: List<List<Item>> = emptyList()
    private var lastFoot: List<Item> = emptyList()

    /** The foot's cells and the gap that leads them while they ride in the scroll ([placeFoot]). */
    private val footCells = ArrayList<View>()
    private var footGap: View? = null

    /** The block sizes the cells on screen were built in — a redress must keep the same shape —
     *  and the pill dial they were sized at (a dial change rebuilds). */
    private var builtShape: List<Int> = emptyList()
    private var builtScale = 0f

    /**
     * THE ONE PANEL LANGUAGE's column (Michael 2026-10-11, [PanelSeats]): ⛶ pinned at the head,
     * then [blocks] — frame · hand · room · foot — packed from the top with a fixed
     * [BLOCK_GAP_DP] between each and no elastic gap (the spare room collects under the last
     * block, Side). Empty blocks take no gap. A column that outruns the screen scrolls, captions
     * kept, as every rail does. No ✕: full screen is ⛶'s, and its way back is the [⛶] pill.
     *
     * [foot] (Side) is never scrolled away: while the column fits it follows the room after one
     * gap; once the seats outrun the screen it is pinned under the scroll, as ⛶ is pinned over it
     * (review 2026-10-11, F1 — Side fell off the B6's landscape column).
     */
    fun setBlocks(blocks: List<List<Item>>, foot: List<Item> = emptyList()) {
        val kept = blocks.filter { it.isNotEmpty() }
        val headSeats = if (listFull) listOf(fullItem) else emptyList()
        val all = headSeats + kept.flatten() + foot
        val shape = kept.map { it.size } + foot.size
        // THE REDRESS RULE (B6): the same seats again move no pixel.
        if (redress(all, shape)) { lastBlocks = kept; lastFoot = foot; return }
        head.removeAllViews()
        topCluster.removeAllViews()
        bottomCluster.removeAllViews()
        footCluster.removeAllViews()
        footCells.clear()
        footGap = null
        activeById.clear()
        seats.clear()
        lastBlocks = kept
        lastFoot = foot
        lastTop = emptyList()
        lastBottom = emptyList()
        builtShape = shape
        builtScale = scale
        builtLabels = labelsOn(context)
        val measured = columnPx(root.height)
        val avail = if (measured > 0) measured.also { lastColumnPx[columnKey()] = it }
            else lastColumnPx[columnKey()]
                ?: columnBeneathStrip(context.resources.displayMetrics.heightPixels, if (writing) bandPx() else 0)
        val gapPx = dp(BLOCK_GAP_DP)
        val f = computeFit(avail, all, gapsPx = gapPx * blockGaps(kept.size, foot.isNotEmpty()))
        for (item in headSeats) head.addView(makeButton(item, f.cellPx))
        kept.forEachIndexed { i, block ->
            if (i > 0) topCluster.addView(Space(context), LinearLayout.LayoutParams(0, gapPx))
            for (item in block) topCluster.addView(makeButton(item, f.cellPx))
        }
        for (item in foot) footCells += makeButton(item, f.cellPx)
        applyFit(f, avail)
        markActive(activeId)
        applyState()
    }

    /**
     * THE REDRESS RULE (Michael 2026-10-11, B6: "panels redraw only when seats change"). A surface
     * re-asks its seats whenever its state moves — a book pairing with its recording, a page
     * finishing — and most of the time it hands back the very same seats. Rebuilding the cells then
     * repaints the whole column for nothing, an e-ink flash and a GC clean on the B6. When [all]
     * matches the cells on screen face for face (icon, glyph, caption, tool box, block shape) and
     * the Tool labels switch hasn't moved, only the actions and hold names change hands, and no
     * view is touched beyond its content description. Gutter-hosted rails only: the ink rails keep
     * their own rebuild path exactly as it was.
     */
    private fun redress(all: List<Item>, shape: List<Int>): Boolean {
        if (toolbar != null || seats.isEmpty()) return false
        if (labelsOn(context) != builtLabels || shape != builtShape || all.size != seats.size) return false
        if (scale != builtScale) return false
        for (i in all.indices) if (!sameFace(seats[i].item, all[i])) return false
        for (i in all.indices) {
            val seat = seats[i]
            val wasOn = seat.item.on
            seat.item = all[i]
            val spoken = spokenFor(all[i].name)
            if (seat.cell.contentDescription != spoken) seat.cell.contentDescription = spoken
            // An on/off change is one cell's outline and caption weight — never the column.
            if (all[i].activeId == null && all[i].on != wasOn) styleOn(seat, all[i].on)
        }
        return true
    }

    /**
     * The foot's place for a fit that [scrolls] or not: pinned under the scroll (always in view)
     * while the seats outrun the column, else riding in [topCluster] one gap after the room, so the
     * spare room collects under Side and the buttons stay on top. Moves views only when the answer
     * changes — one relayout, no rebuild.
     */
    private fun placeFoot(scrolls: Boolean) {
        if (footCells.isEmpty()) return
        val pinned = footPinned(scrolls)
        val target: LinearLayout = if (pinned) footCluster else topCluster
        if (footCells.first().parent === target) return
        footGap?.let { (it.parent as? ViewGroup)?.removeView(it) }
        for (c in footCells) (c.parent as? ViewGroup)?.removeView(c)
        if (!pinned && topCluster.childCount > 0) {
            val gap = footGap ?: Space(context).also { footGap = it }
            topCluster.addView(gap, LinearLayout.LayoutParams(0, dp(BLOCK_GAP_DP)))
        }
        for (c in footCells) target.addView(c)
    }

    /** Box a seat that is ON — the live tool's outline and a bold caption (Michael 2026-10-11:
     *  "a box + bold caption marks what's on"); off, the plain pressed-state selector. */
    private fun styleOn(seat: Seat, on: Boolean) {
        seat.cell.setBackgroundResource(
            if (on) R.drawable.rail_active_outline else android.R.drawable.list_selector_background)
        seat.caption.typeface = android.graphics.Typeface.create(captionFace,
            if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /**
     * More › Tool labels: [relabel], held to ⛶'s rule (plan v6 §2.8, review 2026-10-10) — it is a
     * width change, so one B6 reconnect, and Michael's rule is never to reconnect while the
     * daemon's layer is live after a stroke. It waits that out (at most [FULL_WAIT_TRIES] looks,
     * so a resting pen can't hold it forever) and keeps 1.5s from the last relabel. One waits at a
     * time; it reads the switch when it runs, so a quick double flip costs nothing.
     */
    fun relabelWhenQuiet() {
        if (pendingRelabel != null) return
        attemptRelabel(FULL_WAIT_TRIES)
    }

    private fun attemptRelabel(triesLeft: Int) {
        pendingRelabel = null
        if (!host.isAdded || !root.isAttachedToWindow) return
        val now = android.os.SystemClock.uptimeMillis()
        val debounce = (lastRelabelMs + FULL_DEBOUNCE_MS - now).coerceAtLeast(0L)
        val wait = maxOf(debounce, if (triesLeft > 0) toggleWaitMs() else 0L)
        if (wait > 0) {
            val r = Runnable { attemptRelabel((triesLeft - 1).coerceAtLeast(0)) }
            pendingRelabel = r
            root.postDelayed(r, wait)
            return
        }
        lastRelabelMs = now
        relabel()
    }

    /** The column's inner height for a root of [rootHeight] px; 0 before the first layout. */
    private fun columnPx(rootHeight: Int): Int =
        if (rootHeight <= 0) 0 else rootHeight - root.paddingTop - root.paddingBottom

    /** Where this surface's measured column height is remembered, per orientation. */
    private fun columnKey(): String = "$surfaceKey/${context.resources.configuration.orientation}"

    /** [fitColumn] for [items] in [availPx]: labelled cells while Tool labels are on, and a
     *  writing page's comfortable foot asked of the big cells only. */
    private fun computeFit(availPx: Int, items: List<Item>, gapsPx: Int = currentGapsPx()): Fit = fitColumn(
        availPx = availPx,
        density = context.resources.displayMetrics.density,
        baseBarePx = baseSide(),
        seats = items.size,
        labelled = builtLabels,
        comfortPx = if (writing) dp(PALM_COMFORT_DP) else 0,
        gapsPx = gapsPx
    )

    /** The fixed gaps the column on screen spends: one cluster gap, or (the one panel language)
     *  [BLOCK_GAP_DP] between each of its blocks. */
    private fun currentGapsPx(): Int =
        if (lastBlocks.isNotEmpty() || lastFoot.isNotEmpty())
            dp(BLOCK_GAP_DP) * blockGaps(lastBlocks.size, lastFoot.isNotEmpty())
        else dp(CLUSTER_GAP_DP)

    /** Re-fit against the measured column (rotation relayout, the first real layout). Only
     *  cell heights move — the width stays what [setClusters] decided. */
    private fun refit() {
        if (!host.isAdded) return   // posted: the page may have gone since
        val h = columnPx(root.height)
        if (h <= 0 || seats.isEmpty()) return
        lastColumnPx[columnKey()] = h
        val f = computeFit(h, seats.map { it.item })
        if (f == fit) { fitAvailPx = h; return }
        applyFit(f, h)
    }

    /** Lay [f] onto the seats: every seat shows, at the fitted height and caption size. Nothing
     *  folds and no caption hides for height (Michael 2026-10-10: "scrollable if there are more
     *  buttons") — a column that outruns its room scrolls ([rail]). */
    private fun applyFit(f: Fit, availPx: Int) {
        fit = f
        fitAvailPx = availPx
        placeFoot(f.scrolls)
        val labelled = f.captionSp > 0f
        val gauge = if (labelled) CaptionGauge(f) else null
        val barePad = Math.round(dp(8) * scale)
        for (seat in seats) {
            val cell = seat.cell
            val lp = cell.layoutParams
            if (lp.height != f.cellPx) { lp.height = f.cellPx; cell.layoutParams = lp }
            if (gauge != null) {
                sizeCaption(seat, gauge)
                seat.caption.visibility = View.VISIBLE
                // The icon keeps roughly its bare size (≈ 22-29dp) above the word.
                seat.icon.setPadding(dp(6), dp(5), dp(6), dp(2))
            } else {
                // Bare: the icon fills the cell exactly as the ImageButton did.
                seat.caption.visibility = View.GONE
                seat.icon.setPadding(barePad, barePad, barePad, barePad)
            }
        }
    }

    /** What a labelled caption is sized against under the fit [f]: its size, the 8sp floor and the
     *  column's room, and a BOLD paint to measure with — worked out once per pass. */
    private inner class CaptionGauge(f: Fit) {
        private val metrics = context.resources.displayMetrics
        val captionPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, f.captionSp, metrics)
        // Shrink-to-fit stops at 8sp (review 2026-10-10: a long word at the Compact dial fell to
        // ~7sp, unreadable on e-ink); past that the caption ellipsizes and the hold says it whole.
        val floorPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, MIN_CAPTION_SP, metrics)
        // The caption's room: the column (every cell spans it) less a hair each side.
        val roomPx = (currentOpenWidth() - dp(4)).toFloat()
        val measure = android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.create(captionFace, android.graphics.Typeface.BOLD)
        }
    }

    /** [seat]'s word and its size under [gauge] — [applyFit]'s pass, and [reface]'s one cell. */
    private fun sizeCaption(seat: Seat, gauge: CaptionGauge) {
        val word = captionFor(seat.item)
        seat.caption.text = word
        // Shrink to fit (plan v6 §1), measured BOLD so the live tool's bold caption fits too.
        gauge.measure.textSize = gauge.captionPx
        seat.caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
            maxOf(fittedGlyphTextSize(gauge.captionPx, gauge.measure.measureText(word), gauge.roomPx), gauge.floorPx))
    }

    /**
     * Box the live tool (pen/eraser/lasso): a 2dp outline round its cell and a bold caption
     * (plan v6 §2.7, Michael 2026-10-10) — one boxed at a time, as the pill marked its tool.
     * The grey fill it replaces dithered on e-ink; the outline reads at full strength.
     */
    fun markActive(id: String?) {
        activeId = id
        for ((key, seat) in activeById) {
            val on = key == id
            seat.cell.setBackgroundResource(
                if (on) R.drawable.rail_active_outline
                else android.R.drawable.list_selector_background)   // keep the pressed state
            seat.caption.typeface = android.graphics.Typeface.create(captionFace,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        // The bottom pill's corner says the same truth (review 2026-10-11): its face boxed when it
        // is the tool in hand, else a badge for the eraser or lasso — in full screen the column
        // that boxes it is put away. Nothing redraws when the corner's look didn't change.
        if (writing) bottomPill()?.takeIf { it.corner === this }?.faceChanged()
    }

    /**
     * Box a seat whose ON belongs to the page rather than to the seat's own item — Zoom while the
     * page is magnified or its box is armed (Michael 2026-10-12: "Add a zoom button with marquee
     * zoom"). The ink rails rebuild their cells on every [setClusters] and never redress, so the
     * state can't ride on [Item.on]; [name] is the seat's [Item.name]. One cell's outline and caption
     * weight, never the column — and nothing redraws when the answer didn't change, so a host may
     * ask on every transform.
     */
    fun markOn(name: String, on: Boolean) {
        if ((name in pageOn) == on) return
        if (on) pageOn.add(name) else pageOn.remove(name)
        seats.firstOrNull { it.item.name == name && it.item.activeId == null }?.let { styleOn(it, on) }
    }

    /**
     * Dress a seat in a face its PAGE chooses — the Zoom seat wearing its last choice (Michael
     * 2026-10-12: "We can make the zoom button the last used option for one click zoom in / out
     * adjustments. Or zoom to box :)"): [iconRes] over [caption], spoken as [spoken]; [inPlace] when
     * its tap now acts where it stands, [dim] while it would do nothing, [holdSpoken] its hold's
     * spoken name ([PageFace]). [name] is the seat's [Item.name], which stays its key ([markOn]).
     * One cell's icon and word, never the column, and nothing at all when the face didn't change — a
     * host may ask on every transform. Kept for every rebuild after ([dressed]) and for full
     * screen's row.
     */
    fun reface(name: String, iconRes: Int, caption: String, spoken: String,
               inPlace: Boolean = false, dim: Boolean = false, holdSpoken: String? = null) {
        val face = PageFace(iconRes, caption, spoken, inPlace, dim, holdSpoken)
        val was = pageFaces[name]
        if (was == face) return
        pageFaces[name] = face
        for (seat in seats) {
            if (seat.item.name != name || seat.item.activeId != null) continue
            if (seat.cell.contentDescription != spoken) seat.cell.contentDescription = spoken
            if (was?.holdSpoken != holdSpoken) nameHold(seat.cell, holdSpoken)
            val alpha = if (dim) DIM_FACE_ALPHA else 255
            if (seat.icon.imageAlpha != alpha) seat.icon.imageAlpha = alpha
            if (seat.item.iconRes == iconRes && seat.item.glyph == null && captionFor(seat.item) == caption) continue
            seat.item = dressed(seat.item)
            seat.icon.setImageResource(iconRes)
            fit?.let { f -> if (f.captionSp > 0f) sizeCaption(seat, CaptionGauge(f)) }
        }
    }

    /** [item] in the face its page chose for it ([reface]), else as it came. */
    private fun dressed(item: Item): Item {
        val f = pageFaces[item.name] ?: return item
        if (item.activeId != null) return item
        if (item.iconRes == f.iconRes && item.glyph == null && item.caption == f.caption) return item
        return Item(f.iconRes, item.name, activeId = null, longPress = item.longPress, glyph = null,
            caption = f.caption, on = item.on, action = item.action)
    }

    /** The spoken name a seat named [name] wears: its page's ([reface]), else the name itself. */
    private fun spokenFor(name: String): String = pageFaces[name]?.spoken ?: name

    /** TalkBack names [cell]'s hold [hold] ("double-tap and hold to …") — the bottom pill corner's
     *  way; the hold itself stays the cell's own long click. Null leaves the platform's name. */
    private fun nameHold(cell: View, hold: String?) {
        if (hold == null) return
        androidx.core.view.ViewCompat.replaceAccessibilityAction(cell,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            hold, null)
    }

    fun open() {
        if (writing) { setFull(false); return }
        if (listFull) { requestFull(false); return }
        if (!isOpen) {
            isOpen = true
            prefs.edit().putBoolean(openKey, true).apply()
        }
        applyState()
        epdClean()
    }

    fun tuck() {
        if (writing) { setFull(true); return }
        if (listFull) { requestFull(true); return }
        if (isOpen) {
            isOpen = false
            prefs.edit().putBoolean(openKey, false).apply()
        }
        applyState()
        epdClean()
    }

    /**
     * The rail's current footprint in the layout flow. The open width comes from the display
     * height and the seat count only — never from the measured column — so a re-fit moves cell
     * HEIGHTS, never the width: a width change re-sizes the SurfaceView, and on the
     * B6 every surfaceChanged is a daemon detach + 700ms reattach (plan v6 §2.8).
     *
     * Tool labels widen it to [LABELLED_SIDE_DP] (a 56dp column at the Standard dial), so a
     * caption reads at 9-10sp; the width follows the SWITCH, never the fit, so a short column
     * (which scrolls, captions kept) moves no geometry either.
     *
     * An ink page's root keeps this width in full screen too, but goes GONE ([applyState]), so the
     * slot is the surface's while the column is away (Michael 2026-10-11) and the same width comes
     * back with it — the Normal slot [borrowedRoom] measures the lent room against.
     */
    fun currentWidth(): Int = if (isOpen || writing || listFull) currentOpenWidth() else dp(STRIP_DP)

    private fun currentOpenWidth(): Int {
        // An ink column is as wide with its ⛶ head as without it ([fullHeadWanted]): a corner pick
        // that takes the head away (or brings it back) redraws the column but never resizes the
        // page — no surface change, no B6 reconnect.
        val counted = seats.size + (if (writing && seats.isNotEmpty() && seats.none { it.item === fullItem }) 1 else 0)
        val bare = widthSide(context.resources.displayMetrics.heightPixels,
            context.resources.displayMetrics.density, baseSide(), counted)
        val side = if (builtLabels) maxOf(bare, Math.round(dp(LABELLED_SIDE_DP) * scale)) else bare
        return railWidth(side, dp(6))
    }

    /**
     * One button's full side: the toolbar button dimen on the pill dial. A bare column
     * ([fitColumn]) shrinks from here when it doesn't have the height (a landscape Palma is
     * ~412dp for fourteen buttons) — the upstream toolbar tiled extra columns; a rail is one
     * column by definition, so it gives up size down to the finger floor, then scrolls.
     */
    private fun baseSide(): Int =
        Math.round(context.resources.getDimensionPixelSize(R.dimen.ledger_toolbar_button) * scale)

    /**
     * Lay the current state onto the toolbar root: open = the icon column at button width,
     * tucked = the thin strip. Width is set on the root's layout params — the same mutation
     * upstream's collapse makes — and the constraint chain does the rest: the page's surface
     * ends where the rail begins, which is what keeps the pen honest with no exclude rects.
     */
    fun applyState() {
        column.visibility = if (isOpen) View.VISIBLE else View.GONE
        // Full screen on an ink page lends the slot to the drawing surface (Michael 2026-10-11: "So,
        // yeah it can grow as necessary"): the root goes GONE, CalendarUtils' constraints collapse
        // it to the screen's edge and the SurfaceView + template run under where the column stood.
        // One resize per toggle, and the page re-centres and fits the surface it now has (Michael
        // 2026-10-11: "Recentering/zooming after closing the tuck panel makes sense" —
        // [InkViewport], SurfaceFragment); opening puts the docked fit back.
        root.visibility = if (writing && !isOpen) View.GONE else View.VISIBLE
        // No strip on an ink page (S1.2): full screen's way back is the bottom pill's corner.
        stripMark.visibility = if (isOpen || writing) View.GONE else View.VISIBLE
        val width = currentWidth()
        root.layoutParams = root.layoutParams.apply { this.width = width }
        // THE OVERLAY, in one move: a tucked gutter-hosted strip keeps its width for drawing
        // and tapping, but a negative margin on its PAGE side hands the same width straight
        // back to the row — the weighted content cell measures as if the strip were not there,
        // so every list row runs to the true edge and the strip (Z-lifted in init) draws over
        // the row's own edge padding. Open, or on a toolbar-hosted ink rail, the margins are
        // zero and the rail is the honest in-flow column it always was. Pure arithmetic in the
        // companion so the test can pin it without a View in sight.
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val (l, r) = overlapMargins(isOpen, overlaysWhenTucked, width, sideIsLeft())
            lp.leftMargin = l
            lp.rightMargin = r
        }
        // One hairline on the PAGE side only — the house divider between the gutter and the
        // paper. Drawn as a stroked rect shoved off the other three edges (negative insets clip
        // them off-screen), because a border all round would read as a floating card, and a
        // borderless gray band is exactly what read as "stray" on the upstream collapse this
        // improves on.
        //
        // The fill: paper-white behind the OPEN rail's icons always, but a tucked OVERLAY strip
        // goes CLEAR — an opaque tucked strip over full-bleed content would white out the first
        // STRIP_DP of every row, which is the very clipping this overlay exists to end. The
        // hairline and the grip dots still draw, so the strip still reads as the divider — now
        // a divider ON the page edge rather than a column of it. (The list rows' own start
        // padding is 8-10dp, past the 7dp strip, so the hairline lives in whitespace the rows
        // already keep.)
        val edged = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (overlaysWhenTucked && !isOpen) Color.TRANSPARENT else Color.WHITE)
            // (Full screen on an ink page: the root is GONE and its slot is the surface's — no
            // divider for a column that isn't there.)
            if (isOpen || !writing) setStroke(dp(1), 0xFF333333.toInt())
        }
        val off = dp(2)
        root.background = android.graphics.drawable.LayerDrawable(arrayOf(edged)).apply {
            // Docked left → the page is to the right, so only the right stroke survives.
            if (sideIsLeft()) setLayerInset(0, -off, -off, 0, -off)
            else setLayerInset(0, 0, -off, -off, -off)
        }
        root.requestLayout()
        updateStripHit()
        applyListFull()
        // A reader's full screen: the notes pill away with the ⛶ (Michael 2026-10-12).
        if (reader) applyReaderChrome()
        onStateApplied(width, isOpen)
    }

    /**
     * Seat (or clear) the strip's hit band for the current state — called from [applyState], so
     * every path that changes the rail (open, tuck, the ⇄ edge-hop's dock() + applyState) also
     * re-seats it. Re-inserted from scratch each time rather than patched in place: the ⇄ hop
     * re-parents the gutter by remove/add, and an index held across that is a lie.
     *
     * The band takes NO layout space — the same negative-margin give-back the tucked strip
     * itself rides ([overlapMargins] with the band's width): docked left it sits after the
     * gutter spanning x∈[0, hit); docked right, before it, spanning the last hit px. Z-lifted
     * past the strip's own 1f so a tap anywhere in the band — including over the drawn strip —
     * lands here and summons the rail; the content beneath loses its first ~24dp of edge to
     * taps while tucked, which Michael accepted as the overlay's bargain ("overlaps content
     * harmlessly" — the rows' own start padding keeps their text clear of it anyway).
     */
    private fun updateStripHit() {
        val row = root.parent as? LinearLayout ?: return
        stripHit?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (isOpen || !overlaysWhenTucked || listFull) return
        val hit = stripHit ?: View(context).apply {
            contentDescription = "Actions"
            setOnClickListener { root.performClick() }
            // The band hugs the same screen edge the strip does, where the system back gesture
            // lives — excluded for the same reason the root excludes its own bounds in init.
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
            }
            translationZ = 2f
        }.also { stripHit = it }
        val dockedLeft = sideIsLeft()
        val w = dp(STRIP_HIT_DP)
        val lp = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
        val (l, r) = overlapMargins(open = false, overlaysWhenTucked = true, widthPx = w, dockedLeft = dockedLeft)
        lp.leftMargin = l
        lp.rightMargin = r
        row.addView(hit, row.indexOfChild(root) + if (dockedLeft) 1 else 0, lp)
    }

    // ── FULL SCREEN ON A LIST OR READING SURFACE (the one panel language, Michael 2026-10-11) ──
    //
    // ⛶ replaces ✕ Tuck and the edge strip here too (spec §1: "⛶ replaces Tuck. Its one meaning is
    // 'more room'"). Full screen is one visibility flip: the gutter goes GONE, the page takes the
    // whole width, and a small [⛶] pill stands in the screen's top row (the Net's header, at its
    // leading end) or, where there is no row, alone in the page's top-left corner (the book reader).
    // No strip, no hit band, no animation — one EPD clean, as a tuck was. A page that remembered
    // being tucked comes back in full screen ([migratedFull], the ink pages' migration). These
    // surfaces have no B6 ink daemon: a WebView resizes, nothing reconnects.

    /** The full-screen [⛶] pill of a list/reading surface, built on first need in [fullPillHost]. */
    private var listPill: View? = null

    /**
     * A ROW's pill floats now (Michael 2026-10-12, of the B6's list ⛶: "Parity here, please. They
     * should move" — iOS's list pills drag). The pill itself stands in the screen's page frame
     * ([floatFrame]); in the row it leaves this SEAT, a blank its size, so the row lays out exactly
     * as before and the pill sits over its old place until you drag it. Once dragged, the seat
     * folds away and the pill stays where you put it — the one spot every floating [⛶] pill on the
     * device shares (iOS's `pill_spot_full`).
     */
    private var listPillSeat: View? = null

    /** The frame a row's pill floats in: the nearest FrameLayout above the row, inside this
     *  screen's own view (never the activity's — a pill must leave with its screen). Null: none,
     *  and the pill keeps its place in the row. */
    private fun floatFrame(): android.widget.FrameLayout? {
        val host = fullPillHost ?: return null
        if (host is android.widget.FrameLayout) return host
        val top = this.host.view ?: return null
        var p: android.view.ViewParent? = host.parent
        while (p is ViewGroup) {
            if (p is android.widget.FrameLayout) return p
            if (p === top) return null
            p = p.parent
        }
        return null
    }

    /** Lay full screen on a list/reading surface: the gutter GONE and the pill shown, or neither.
     *  Runs at the end of [applyState], so a resume or a ⇄ hop re-lays it with everything else. */
    private fun applyListFull() {
        if (!listFull) return
        val full = !isOpen
        if (full) root.visibility = View.GONE
        stripMark.visibility = View.GONE
        val pill = listPill ?: (if (full) fullPillHost?.let { buildListPill(it) } else null) ?: return
        // The row's seat holds the pill's place only while the pill stands over it (full screen,
        // never dragged); otherwise the row closes up, as it did when the pill lived in it.
        listPillSeat?.visibility = if (full && draggedSpot() == null) View.INVISIBLE else View.GONE
        if (full) placeListPill(pill)
        val want = listPillVisibility(full)
        if (pill.visibility != want) pill.visibility = want
    }

    // ── A READER'S FULL SCREEN LEAVES NOTHING ON THE PAGE (Michael 2026-10-12: "I need the full
    //    screen button to be able to be fully hidden within a reader" + "IT cant block the comics
    //    :P" → "yeas") ─────────────────────────────────────────────────────────────────────────────
    //
    // On a surface that is a READER right now ([reader]), full screen puts the [⛶] pill and the
    // notes pill away too ([ReaderFullScreen], the rule both apps share). The reader's controls tap
    // — the centre of the page — brings them back ([readerControlsTap]); a page turn puts them away
    // again ([readerPageTurn]). On a tucked-strip reader (the letter, the standalone article) there
    // is no ⛶: the notes pill goes, and the strip itself stays as the way back — its tap opens the
    // column, which is leaving full screen. Showing and hiding are visibility flips only: the pill's
    // dragged spot (and the notes pill's) is written by a drag alone, so it comes back where it was.
    // E-ink: no animation; a hide that no page turn covers gets the house panel clean.
    //
    // THE MIDDLE TAP (Michael 2026-10-12, "should the B6 do the same [as the iPhone]?" → "yes"):
    // the same tap in NORMAL reading is the way INTO full screen, as on iOS ([readerMiddleTap],
    // [ReaderFullScreen.middleTap]) — through the ⛶ seat's own door ([tuck]: the list column's
    // [requestFull], the strip reader's ✕), so the B6 pays exactly the ⛶'s one transition. Review
    // 2026-10-12: only from iOS's middle 44% (a book's "centre" is the tap-zone setting's), and the
    // piece's alone — never remembered, gone when it closes ([readerPieceOver]), as iOS keeps it.

    /** This surface is a reader right now: the book reader always, Feeds while a piece is open, the
     *  standalone article. Never an ink page. Changing it starts clean (nothing peeking). */
    var reader: Boolean = false
        set(v) {
            if (field == v || writing) return
            // The piece closed (Feeds' article, the Mail's letter): a full screen its middle tap
            // brought goes with it, as on iOS ([readerPieceOver]).
            if (!v) readerPieceOver()
            field = v
            readerControls = false
            applyReaderChrome()
        }

    /** What the controls tap brought back while in full screen ([ReaderFullScreen.State.controlsShown]). */
    private var readerControls = false

    /** The full screen [readerControls] belongs to — when it changes, the peek is over. */
    private var readerFull = false

    /** The notes pill is held away by this panel right now (so a repeat asks the activity nothing). */
    private var notesHeldAway = false

    /** The reader's controls are back over a full-screen page right now. */
    val readerControlsShown: Boolean get() = reader && !isOpen && readerControls

    /** Full screen came by the middle tap and is still up (review 2026-10-12). iOS keeps a reader's
     *  full screen per piece — every book, article and letter opens in normal reading — so this one
     *  is never remembered ([readerMiddleTap]) and goes when the piece does ([readerPieceOver]). The
     *  ⛶ and the ✕ are remembered per surface, as they always were. */
    private var fullByMiddleTap = false

    private fun readerState() = ReaderFullScreen.State(fullScreen = readerFull, controlsShown = readerControls)

    /** The [⛶] pill's visibility for [full]: hidden in a reader's full screen until the controls
     *  tap. In a row (Feeds' top row) it keeps its room while away, so the date strip beside it never
     *  re-lays (no e-ink redraw of the strip per page); over a page it simply goes. */
    private fun listPillVisibility(full: Boolean): Int {
        syncReaderFull()
        return when {
            !full -> View.GONE
            !reader || ReaderFullScreen.pillShown(readerState()) -> View.VISIBLE
            fullPillHost is LinearLayout -> View.INVISIBLE
            else -> View.GONE
        }
    }

    /** Full screen came or went since the last look: [ReaderFullScreen.fullScreen] — it starts clean. */
    private fun syncReaderFull() {
        val full = !isOpen
        // Out of full screen by any door: the next way in is the ⛶'s, remembered as before.
        if (!full) fullByMiddleTap = false
        if (full == readerFull) return
        val s = ReaderFullScreen.fullScreen(readerState(), full)
        readerFull = s.fullScreen
        readerControls = s.controlsShown
    }

    /** Lay the reader rule on: the [⛶] pill and the notes pill, each flipped only if it changed. */
    private fun applyReaderChrome() {
        syncReaderFull()
        if (listFull) listPill?.let { pill ->
            val want = listPillVisibility(!isOpen)
            if (pill.visibility != want) {
                // Coming back where it was dragged to — a peek re-seats nothing it doesn't have to.
                if (want == View.VISIBLE) placeListPill(pill)
                pill.visibility = want
            }
        }
        val away = reader && !writing && !ReaderFullScreen.pillShown(readerState())
        holdNotesAway(away)
        if (away != readerChromeAway) {
            readerChromeAway = away
            onReaderChrome?.invoke(!away)
        }
    }

    /** The reader's own chrome follows the pills (review 2026-10-12): told [shown] = false when full
     *  screen has put them away, true when they are back (the peek, or normal reading) — a comic's
     *  "12 / 48", an inline read-aloud transport. Called on a change only. */
    var onReaderChrome: ((shown: Boolean) -> Unit)? = null

    /** What [onReaderChrome] was last told (inverted: true = away). */
    private var readerChromeAway = false

    /** The reader's chrome is on screen right now — what [onReaderChrome] last said. */
    val readerChromeShown: Boolean get() = !readerChromeAway

    private fun holdNotesAway(away: Boolean) {
        if (away == notesHeldAway) return
        notesHeldAway = away
        (host.activity as? BottomPill.Host)?.readerAway(this, away)
    }

    /**
     * The reader's controls tap (the centre of the page): in full screen the [⛶] and the notes pill
     * come back, or go again. A hide that no page turn covers gets the house panel clean, as every
     * chrome change does (on the B6 the next page repaints it).
     */
    fun readerControlsTap() {
        if (!reader) return
        syncReaderFull()
        val before = ReaderFullScreen.pillShown(readerState())
        readerControls = ReaderFullScreen.controlsTap(readerState()).controlsShown
        applyReaderChrome()
        if (before && !ReaderFullScreen.pillShown(readerState())) epdClean()
    }

    /**
     * The reader's MIDDLE TAP — the tap in the middle of the page, past each reader's guards (links,
     * a selection, a mark, a field or player, a comic's double-tap window and its walk): iOS's
     * `ReaderFullScreen.middleTap`. In normal reading it goes INTO full screen through the ⛶ seat's
     * own door ([tuck] — the column's [requestFull], or the strip reader's ✕: the same debounce and
     * the same single e-ink transition, nothing on the page after it) — never remembered, and gone
     * with the piece ([enterByMiddleTap], [readerPieceOver]); in full screen it is the controls tap
     * ([readerControlsTap]). A comic walk's whole page is never this — it must not go into full
     * screen (iOS's guard): it is [readerWalkStep].
     */
    fun readerMiddleTap(inMiddle: Boolean = true) {
        if (!reader) return
        syncReaderFull()
        // [inMiddle]: the tap's x is inside iOS's middle 44% ([ReaderFullScreen.middleTap]) — a
        // book's or comic's "centre" is the tap-zone setting's, nearly the whole page by default.
        val next = ReaderFullScreen.middleTap(readerState(), inMiddle)
        when {
            next.fullScreen != readerFull -> enterByMiddleTap()
            next != readerState() -> readerControlsTap()
        }
    }

    /** INTO full screen by the middle tap: the ⛶ seat's own door ([tuck]), but the piece's alone —
     *  the surface keeps remembering normal reading, so the next book, the next article, a restart
     *  all open as iOS opens them ([fullByMiddleTap]). */
    private fun enterByMiddleTap() {
        tuck()
        if (isOpen) return   // the ⛶'s debounce ate it: nothing came, nothing to give back
        fullByMiddleTap = true
        if (listFull) prefs.edit().putBoolean(fullKey, false).apply()
        else prefs.edit().putBoolean(openKey, true).apply()
    }

    /**
     * The piece being read is over — closed ([reader] going false), or another book opened in its
     * place: a full screen its middle tap brought goes with it, as iOS's does (its full screen lives
     * with each book, article and letter). Straight to normal — no debounce, no receipt: the piece
     * changing redraws the page anyway. Nothing when the ⛶ (or the ✕) put full screen there, or it
     * has already gone.
     */
    fun readerPieceOver() {
        if (!fullByMiddleTap) return
        fullByMiddleTap = false
        if (isOpen) return
        if (listFull) setFull(false) else open()
    }

    /** A comic's walk step ([ReaderFullScreen.walkStep]): it acts only in full screen — the whole
     *  page arriving brings the controls, back to the panel toggles them, a turn puts them away; in
     *  normal reading it changes nothing (iOS's guard). */
    fun readerWalkStep(move: ReaderFullScreen.WalkMove) {
        if (!reader) return
        syncReaderFull()
        when {
            move == ReaderFullScreen.WalkMove.TURN -> readerPageTurn()
            ReaderFullScreen.walkStep(readerState(), move) != readerState() -> readerControlsTap()
        }
    }

    /** A page turn: whatever the controls tap brought back goes. Call it BEFORE the page moves, so
     *  the new page covers the pill's patch in the same e-ink update. Nothing at all when nothing
     *  was showing. */
    fun readerPageTurn() {
        if (!reader || !readerControls) return
        syncReaderFull()
        readerControls = ReaderFullScreen.pageTurn(readerState()).controlsShown
        applyReaderChrome()
    }

    /**
     * The pill stands at the column's own end (review 2026-10-11, F2; iOS's pill follows RailSide
     * the same way): the row's leading end and the page's top-left while the column docks left,
     * the row's trailing end and the top-right while it docks right — the way back is where the
     * hand already is. Moved only when the side changed since it last stood.
     */
    private fun placeListPill(pill: View) {
        val host = fullPillHost ?: return
        val left = sideIsLeft()
        val margin = dp(3)
        // A row's pill that floats: its seat moves to the side's end of the row, and the pill
        // stands over the seat — or at the spot it was dragged to.
        val seat = listPillSeat
        if (host is LinearLayout && seat != null) {
            val want = if (left) 0 else host.childCount - 1
            if (host.indexOfChild(seat) != want) {
                host.removeView(seat)
                host.addView(seat, if (left) 0 else host.childCount, LinearLayout.LayoutParams(
                    seat.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    seat.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    if (left) marginEnd = margin else marginStart = margin
                })
            }
            if (draggedSpot() != null) seatDraggedListPill(pill) else seatOverRowSeat(pill, seat)
            return
        }
        when (host) {
            is LinearLayout -> {
                val want = if (left) 0 else host.childCount - 1
                if (host.indexOfChild(pill) != want) {
                    host.removeView(pill)
                    host.addView(pill, if (left) 0 else host.childCount, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        if (left) marginEnd = margin else marginStart = margin
                    })
                }
            }
            is android.widget.FrameLayout -> {
                val lp = pill.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
                // A spot you dragged it to wins over the column's side (Michael 2026-10-12: "The
                // Fullscreen button … could be movable too"): it hangs from the top-left and is
                // carried there ([seatDraggedListPill]); never dragged, it keeps the side's corner.
                val gravity = if (draggedSpot() != null) Gravity.TOP or Gravity.START
                    else Gravity.TOP or (if (left) Gravity.START else Gravity.END)
                if (lp.gravity != gravity) { lp.gravity = gravity; pill.layoutParams = lp }
                seatDraggedListPill(pill)
            }
        }
    }

    // ── THE FLOATING [⛶] PILL MOVES (Michael 2026-10-12, iPhone 247: "The Fullscreen button/notes
    //    nib in the feed list could be movable too") ─────────────────────────────────────────────
    //
    // Where the list pill FLOATS — over a page frame (the book reader) — a drag carries it anywhere
    // on that page and it stays there: ONE SPOT SHARED by every floating [⛶] pill on this device
    // (iOS keeps one `pill_spot_full` for all its lists' pills — PARITY; on Android the book
    // reader's and, since 2026-10-12, every row's), kept as fractions of the room it can travel, so a rotation or a
    // resized page puts it at the same relative place. A tap still leaves full screen and a hold
    // still names it; a drag never fires the tap. The ROW pills move too now (Michael 2026-10-12:
    // "Parity here, please. They should move"): the Feeds and Mail lists' top row, the Net's
    // header and the shelf's nav row keep a seat the pill's size, and the pill floats over it in
    // the page frame until you drag it ([listPillSeat]) — so the row looks as it did and no list
    // row's ★ is covered until you choose to put the pill there. (The notes pill is the activity's
    // and already moves — one spot per device, MainActivity.makeFloatButtonDraggable.)
    // E-ink: a drag is translation only, then the house panel clean once it lands, as the notes
    // pill's drag does.

    private val listPillXKey = "pill_spot_full_fx"
    private val listPillYKey = "pill_spot_full_fy"

    /** The spot a floating [⛶] pill was last dragged to on this device, as (fx, fy) fractions —
     *  null: never dragged (it keeps the column side's top corner). */
    private fun draggedSpot(): Pair<Float, Float>? {
        val p = prefs
        if (!p.contains(listPillXKey) || !p.contains(listPillYKey)) return null
        return p.getFloat(listPillXKey, 0f) to p.getFloat(listPillYKey, 0f)
    }

    /** Carry a dragged pill to its spot ([listPillTranslation]) — from where its top-left gravity
     *  lays it (the frame's padding and its margins, never a stale `left` mid-relayout), once it
     *  and its frame have sizes. */
    private fun seatDraggedListPill(pill: View) {
        val spot = draggedSpot() ?: run { pill.translationX = 0f; pill.translationY = 0f; return }
        val frame = pill.parent as? View
        if (frame == null || frame.width <= 0 || pill.width <= 0) {
            pill.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int) {
                    if (r <= l) return
                    v.removeOnLayoutChangeListener(this)
                    seatDraggedListPill(pill)
                }
            })
            return
        }
        val lp = pill.layoutParams as? ViewGroup.MarginLayoutParams
        val (tx, ty) = listPillTranslation(spot.first, spot.second,
            frame.paddingLeft + (lp?.leftMargin ?: 0), frame.paddingTop + (lp?.topMargin ?: 0),
            frame.width - pill.width, frame.height - pill.height)
        pill.translationX = tx
        pill.translationY = ty
        excludePillFromBack(pill, pill.width, pill.height)
    }

    /** Stand a never-dragged floating pill exactly over its seat in the row — where it stood when
     *  it lived there — once both have been laid out (and again whenever the seat moves). */
    private fun seatOverRowSeat(pill: View, seat: View) {
        val frame = pill.parent as? View ?: return
        if (seat.width <= 0 || frame.width <= 0 || pill.width <= 0) {
            // Wait on whichever is NOT laid out yet (review 2026-10-12 #14): the seat lays out
            // before the pill (the pill is added to the frame last), so waiting on the seat while
            // only the pill was unsized never fired again — the pill sat at the frame's top-left
            // until some later re-layout. The pill (and the frame under it) lay out together.
            val waitOn = if (pill.width <= 0 || frame.width <= 0) pill else seat
            waitOn.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int) {
                    if (r <= l) return
                    v.removeOnLayoutChangeListener(this)
                    if (draggedSpot() == null) seatOverRowSeat(pill, seat)
                }
            })
            return
        }
        val s = IntArray(2).also { seat.getLocationInWindow(it) }
        val f = IntArray(2).also { frame.getLocationInWindow(it) }
        val lp = pill.layoutParams as? ViewGroup.MarginLayoutParams
        val laidX = frame.paddingLeft + (lp?.leftMargin ?: 0)
        val laidY = frame.paddingTop + (lp?.topMargin ?: 0)
        pill.translationX = (s[0] - f[0] - laidX).toFloat()
        pill.translationY = (s[1] - f[1] - laidY).toFloat()
        excludePillFromBack(pill, pill.width, pill.height)
    }

    /** The floating pill's own bounds out of the system Back gesture — re-set after every move so
     *  the window re-reads them where the pill now stands (the rects ride its translation). A no-op
     *  before Android 10, which has no edge gesture. */
    private fun excludePillFromBack(pill: View, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        androidx.core.view.ViewCompat.setSystemGestureExclusionRects(pill, listOf(Rect(0, 0, w, h)))
    }

    /**
     * Make the floating pill draggable by its [cell]: tap = [cell]'s click, hold = its long click,
     * a drag past the touch slop moves the whole [pill] (clamped inside its frame) and is saved
     * on the lift — one spot on this device for every floating [⛶] pill. CANCEL (an ancestor took
     * the gesture) keeps a drag and
     * still honours a hold, as the notes pill's drag does.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeListPillDraggable(cell: View, pill: View) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f; var dragging = false
        // Its drag is a move, never the in-app left-edge swipe back either (LeftEdgeCatcher stands
        // aside for a tagged pill; Michael 2026-10-12).
        pill.setTag(R.id.tag_edge_stand_aside, true)
        // Parked against the page's left or right edge, the pill sits in the system Back band, and a
        // drag off it was Back (reader → shelf) instead of a move (review 2026-10-12): it claims its
        // own small bounds from the gesture, as the tucked strip does ([excludePillFromBack]).
        pill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> excludePillFromBack(v, r - l, b - t) }
        // A rotation or a resized page: the same relative spot on the new frame (or, never
        // dragged, over its seat in the row again).
        (pill.parent as? View)?.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if ((r - l != or_ - ol || b - t != ob - ot) && pill.visibility == View.VISIBLE) {
                val seat = listPillSeat
                if (seat != null && draggedSpot() == null) seatOverRowSeat(pill, seat) else seatDraggedListPill(pill)
            }
        }
        fun save() {
            val frame = pill.parent as? View ?: return
            val (fx, fy) = listPillFractions(pill.left + pill.translationX, pill.top + pill.translationY,
                frame.width - pill.width, frame.height - pill.height)
            val wasCorner = draggedSpot() == null
            prefs.edit().putFloat(listPillXKey, fx).putFloat(listPillYKey, fy).apply()
            excludePillFromBack(pill, pill.width, pill.height)
            // The first drag takes it off the side's corner: hang it from the top-left from now
            // on (placeListPill), carried to the same spot — no visible jump. A row's pill leaves
            // its seat: the row closes up behind it (one relayout, on the lift).
            if (wasCorner) {
                val lp = pill.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null && lp.gravity != (Gravity.TOP or Gravity.START)) {
                    lp.gravity = Gravity.TOP or Gravity.START
                    pill.layoutParams = lp
                }
                listPillSeat?.visibility = View.GONE
                seatDraggedListPill(pill)
            }
            epdClean()
        }
        cell.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                // No pressed highlight (review 2026-10-12): it drew at every drag's start, an e-ink
                // update the notes pill never pays; the tap's own action is the feedback.
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startTx = pill.translationX; startTy = pill.translationY
                    dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        val frame = pill.parent as? View
                        if (frame != null) {
                            pill.translationX = (startTx + dx).coerceIn(PillBounds.range(pill.left, pill.right, frame.width))
                            pill.translationY = (startTy + dy).coerceIn(PillBounds.range(pill.top, pill.bottom, frame.height))
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    when {
                        dragging -> save()
                        e.eventTime - e.downTime >= HOLD_MS -> v.performLongClick()
                        else -> v.performClick()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) save() else if (e.eventTime - e.downTime >= HOLD_MS) v.performLongClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun buildListPill(host: ViewGroup): View {
        val cellPx = Math.round(dp(LIST_PILL_CELL_DP) * scale)
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_fullscreen)
            // Black on the white pill in every theme: ic_fullscreen tints with colorControlNormal,
            // near-white at night (review 2026-10-11, #9).
            imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cell = android.widget.FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            // It shows only in full screen, and its tap leaves it: named for what it does (review
            // 2026-10-12; iOS's ⛶ in a peek says the same).
            contentDescription = ReaderFullScreen.LEAVE_NAME
            addView(icon, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { requestFull(false) }
            setOnLongClickListener { toast(ReaderFullScreen.LEAVE_NAME); true }
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(3)
            setPadding(pad, pad, pad, pad)
            // The ink pill's dress: white, a 2dp outline, 12dp corners (plan v6 §2.5).
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
            isClickable = true
            visibility = View.GONE
            // Above the page in a frame; no outline, or the lift would grow a shadow (e-ink smear).
            translationZ = 3f
            outlineProvider = null
            addView(cell, LinearLayout.LayoutParams(cellPx, cellPx))
        }
        val margin = dp(3)
        // First placed at the leading end / top-left; [placeListPill] moves it to the column's side.
        val frame = if (host is LinearLayout) floatFrame() else null
        when {
            // A row with a frame above it: a seat in the row, the pill floating in the frame over
            // it — and so it drags like every other floating pill (Michael 2026-10-12: "Parity
            // here, please. They should move").
            host is LinearLayout && frame != null -> {
                val size = cellPx + 2 * dp(3)   // the pill's own size: the cell and its 3dp padding
                val seat = View(context).apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                host.addView(seat, 0, LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginEnd = margin
                })
                listPillSeat = seat
                // The row re-laid (its controls changed, the side hopped): a never-dragged pill
                // follows its seat.
                seat.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
                    if ((l != ol || t != ot || r != or_ || b != ob) && r > l &&
                        pill.visibility == View.VISIBLE && draggedSpot() == null) seatOverRowSeat(pill, seat)
                }
                frame.addView(pill, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START))
                makeListPillDraggable(cell, pill)
            }
            host is LinearLayout -> host.addView(pill, 0, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = margin
            })
            host is android.widget.FrameLayout -> {
                host.addView(pill, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START).apply { setMargins(margin, margin, margin, margin) })
                // Floating over the page: it moves (Michael 2026-10-12, [makeListPillDraggable]).
                makeListPillDraggable(cell, pill)
            }
            else -> host.addView(pill)
        }
        listPill = pill
        return pill
    }

    // ── FULL SCREEN (ink pages, plan v6 S1.2, Michael 2026-10-10) ─────────────────────────────
    //
    // ⛶ hides the column in one frame. The way back is the BOTTOM PILL's corner half (Michael
    // 2026-10-11: "The one top left and the one bottom right" — the top-left pill and the notes
    // circle merged into one oblong at the bottom, the date strip keeping the whole top row): this
    // rail lends it as its [BottomPill.Corner], wearing the last-chosen of ⛶ / Pen / Eraser / Undo
    // on every page of ink (⛶ alone on a read-only station). Pick anything but ⛶ and the way back
    // is the hold's "Leave full screen" — Michael's one-item corner ("Put only one tuck item … and
    // let you hold the button to select one of the other options"), as on iOS. The surface takes
    // the column's room and the page re-centres and fits it (Michael 2026-10-11: "Recentering/
    // zooming after closing the tuck panel makes sense",
    // replacing Q1a's "page keeps its size" and the same morning's "needn't center nor move");
    // each surface remembers its own state (Q1d). The pill floats over the page in both states and
    // stays clear of the column's slot, so a ⛶ never moves it; the picker hangs over the page only
    // while it is open.

    /**
     * A tap on either ⛶ — the column's, or the pill's corner wearing ⛶ ([fromPill]). Debounced 1.5s
     * from the tap, and the ⛶ that ends up showing inverts for that window as the receipt (plan v6
     * §2.5). On the B6 the toggle waits while the daemon's layer is still live after a stroke
     * ([toggleWaitMs]; Michael's rule), the tapped cell inverted meanwhile so the wait reads as
     * heard.
     */
    private fun requestFull(full: Boolean, fromPill: Boolean = false) {
        if (!(writing || listFull) || full == !isOpen || pendingToggle != null) return
        val now = android.os.SystemClock.uptimeMillis()
        // A list's ⛶ answers at once — no daemon to wait out (review 2026-10-11, F14).
        if (now - lastToggleMs < (if (writing) FULL_DEBOUNCE_MS else LIST_DEBOUNCE_MS)) return
        lastToggleMs = now
        attemptToggle(full, FULL_WAIT_TRIES, waited = false, fromPill = fromPill)
    }

    private fun attemptToggle(full: Boolean, triesLeft: Int, waited: Boolean, fromPill: Boolean) {
        pendingToggle = null
        if (!host.isAdded || !root.isAttachedToWindow) return
        val wait = toggleWaitMs()
        if (wait > 0 && triesLeft > 0) {
            invertFull(inPill = fromPill, on = true)
            val r = Runnable { attemptToggle(full, triesLeft - 1, waited = true, fromPill = fromPill) }
            pendingToggle = r
            root.postDelayed(r, wait)
            return
        }
        invertFull(inPill = fromPill, on = false)
        setFull(full)
        // A B6 wait already showed the receipt on the tapped ⛶: the corner's own would cost a
        // third e-ink update for the same news (review 2026-10-10), so it shows only on a toggle
        // that ran at once — in the frame the column goes, restored once the debounce is up.
        // A list has no delay to acknowledge, so no receipt: two e-ink updates saved per return
        // (review 2026-10-11, F14).
        if (waited || listFull) return
        val landed = full   // full screen: the pill's ⛶; Normal: the column's
        invertFull(inPill = landed, on = true)
        val left = FULL_DEBOUNCE_MS - (android.os.SystemClock.uptimeMillis() - lastToggleMs)
        root.postDelayed({ if (host.isAdded) invertFull(inPill = landed, on = false) },
            left.coerceAtLeast(RECEIPT_MIN_MS))
    }

    /** Lay full screen on (or off) and remember it for this surface. Visibility flips only, but
     *  the root's slot changes hands (Michael 2026-10-11), so the surface resizes once: one
     *  surfaceChanged — one silent B6 detach, one settled reattach, the cost of a page turn — and
     *  the host refits the page to it in that same pass (re-centred and fitted, or the docked fit
     *  restored), drawing once. The pill doesn't move ([avoidPx]), so it adds nothing. */
    private fun setFull(full: Boolean) {
        flyout?.dismiss()
        isOpen = !full
        prefs.edit().putBoolean(fullKey, full).apply()
        applyState()
        // The corner's spoken name follows: "Leave full screen" while it is the way back.
        bottomPill()?.takeIf { it.corner === this }?.faceChanged()
        epdClean()
        onChromeChanged()
    }

    /** The receipt: the pill corner's ⛶ ([inPill]; only while the corner wears ⛶) else the
     *  column's, drawn black-on-white reversed, or restored. */
    private fun invertFull(inPill: Boolean, on: Boolean) {
        if (inPill) {
            val bp = bottomPill()?.takeIf { it.corner === this } ?: return
            if (on && bp.chosen() != BottomPill.Action.FULL) return
            bp.invertCorner(on)
            return
        }
        // No ⛶ on the column (the corner wears it — [fullHeadWanted]): the receipt is the corner's.
        val seat = seats.firstOrNull { it.item === fullItem } ?: run { if (writing) invertFull(inPill = true, on = on); return }
        if (on) {
            seat.cell.setBackgroundColor(Color.BLACK)
            seat.icon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        } else {
            seat.cell.setBackgroundResource(android.R.drawable.list_selector_background)
            seat.icon.clearColorFilter()
        }
        seat.caption.setTextColor(if (on) Color.WHITE else Color.BLACK)
    }

    // ── THE BOTTOM PILL'S CORNER (Michael 2026-10-11) ─────────────────────────────────────────

    /** The activity's bottom pill, if this page has one to lend a corner to. */
    private fun bottomPill(): BottomPill? = (host.activity as? BottomPill.Host)?.bottomPill

    /** Lend the pill this page's corner (idempotent — the face is re-read each time). Only while
     *  this rail is in a window: a rail whose view never attaches gets no detach either, and would
     *  leave the pill wearing a dead page's corner (review 2026-10-11). The attach listener lends
     *  it on the way in, still before the page's first frame. */
    private fun registerCorner() {
        if (!writing || !root.isAttachedToWindow) return
        bottomPill()?.setCorner(this, this)
        // The pick is the device's: another page may have changed it while this one was away.
        syncFullHead()
    }

    /**
     * NO ⛶ TWICE (Michael 2026-10-12, iPhone 247: "No need to see the full screen expand button on
     * the tuck if its on the note nib"). On an ink page the column's ⛶ head stands only while the
     * bottom pill's corner wears something else — Pen, Eraser or Undo — so full screen is one tap
     * from either state, never two ⛶ on one screen and never a hold away. Read from this page's
     * own pick ([BottomPill.columnWearsFull]), so it is right before the page lends the corner.
     * No pill (a host without one): the column keeps its ⛶.
     */
    private fun fullHeadWanted(): Boolean {
        if (!writing) return false
        val bp = bottomPill() ?: return true
        // Before the page has said ([setPenTools] runs after [setClusters] at setup), read the pick
        // as an ink page's — every page that takes ink passes its tools — so a stored Pen, Eraser or
        // Undo builds the head in the first pass, not a thrown-away build and a second one (review
        // 2026-10-12). A read-only station's null then drops the head once, still before a frame.
        return BottomPill.columnWearsFull(bp.storedPick(), hasPenTools || !penToolsSaid)
    }

    /** The corner's face may have moved (a pick from its hold, the pen tools arriving, a page
     *  back from another): re-lay the column when its ⛶ head should come or go — the column's own
     *  cells redrawn, no width change ([currentOpenWidth]) and no panel clean (the pick already
     *  redraws the pill; at setup it lands before the first frame), nothing when it agrees. */
    private fun syncFullHead() {
        if (!writing || seats.isEmpty()) return
        if (seats.any { it.item === fullItem } == fullHeadWanted()) return
        setClusters(lastTop, lastBottom)
    }

    /**
     * The page's pen tools for the pill's corner (the column seats' own items, so a tap there and
     * a tap here are one action). Every ink page passes them, its face included — iOS's corner
     * offers the four on every page of ink (PARITY, review 2026-10-11; the corner flipping between
     * ⛶ and Pen at each walk step was one e-ink update and one changed tap per step). Null = ⛶
     * only. Set before the first layout, so the pill's face is right in the first frame.
     */
    fun setPenTools(tools: PenTools?) {
        penTools = tools
        penToolsSaid = true
        if (tools == null) flyout?.dismiss()
        bottomPill()?.takeIf { it.corner === this }?.refresh()
        // The corner's face follows the pen tools (⛶ alone without them): so does the column's ⛶.
        syncFullHead()
    }

    override val hasPenTools: Boolean get() = penTools != null
    override val inFullScreen: Boolean get() = writing && !isOpen
    override val toolInHand: String? get() = activeId

    /**
     * The corner's TAP (iOS's performCorner): ⛶ in or out; Pen = the column's Pen tap (the full
     * chooser when the pen is already in hand), else the pen picked up; ↶ the column's Undo.
     * ERASER differs from its column seat on purpose, as on iOS: with the eraser already in hand
     * the tap puts it down for the pen — the old pill's pen ⇄ eraser, one tap each way.
     */
    override fun perform(action: BottomPill.Action) {
        val tools = penTools
        if (tools == null || action == BottomPill.Action.FULL) { requestFull(isOpen, fromPill = true); return }
        when (action) {
            BottomPill.Action.PEN -> if (activeId == BottomPill.HAND_PEN) tools.pen.action() else tools.pickUpPen()
            BottomPill.Action.ERASER -> if (activeId == BottomPill.HAND_ERASER) tools.pickUpPen() else tools.eraser.action()
            BottomPill.Action.UNDO -> tools.undo.action()
            BottomPill.Action.FULL -> Unit
        }
    }

    /** A pick from the hold, once it is the tap: run it — unless it is the tool already in hand,
     *  which a pick only keeps ([BottomPill.pickRuns]; review 2026-10-11). */
    private fun runPick(action: BottomPill.Action) {
        if (BottomPill.pickRuns(action, activeId)) perform(action)
    }

    /**
     * The corner's hold: the four in one row above the pill, the current pick boxed (plan v6
     * §2.7's outline). A tap picks — it becomes the corner's tap, remembered on this device — and
     * runs it once, unless it is the tool already in hand ([runPick]); a hold on a cell is that
     * tool's own second act, as on its column seat: Pen's full chooser (Michael 2026-10-11: "not
     * just a radio button for either :P"), Eraser's clear page, and Undo's is Redo. ↶ keeps the
     * row open for a run, as the old flyout's ↶ ↷ did, the box moving to it.
     *
     * IN FULL SCREEN the row also carries, past a hairline, what the put-away column and the old
     * pill's pen ▾ flyout held — ↷ Redo (the row stays open), Choose a pen… and Zoom — once each, never
     * the tap: the tap stays one of Michael's four (review 2026-10-11, no-feature-loss rule; iOS's
     * cornerOnceRows). In Normal the column beside the page has them, so they don't show twice.
     *
     * The pen pauses first, before the window can take a tap (plan v6 §2.5), and the host cuts the
     * row's rect out of the B6 daemon's writes while it is up. Any tap outside closes it and is
     * spent (the sheets' rule, §2.6). A page with ⛶ its only action has nothing to pick: the hold
     * says so.
     */
    override fun showPicker() {
        val tools = penTools
        val bp = bottomPill() ?: return
        if (tools == null) { toast("Full screen is this page's one choice"); return }
        val anchor = bp.container
        if (flyout?.isShowing == true || !anchor.isAttachedToWindow || anchor.height <= 0) return
        val cellPx = maxOf(bp.noteButton.height.takeIf { bp.noteButton.visibility == View.VISIBLE }
            ?: bp.cornerButton.height, dp(MIN_CELL_DP))
        val full = !isOpen
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(PILL_PAD_DP)
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
        }
        val picks = LinkedHashMap<BottomPill.Action, ViewGroup>()
        fun boxPick(pick: BottomPill.Action) {
            for ((a, c) in picks) c.setBackgroundResource(
                if (a == pick) R.drawable.rail_active_outline else android.R.drawable.list_selector_background)
        }
        for (action in BottomPill.offered(hasPenTools = true)) {
            val (cell, _) = smallCell(action.iconRes, BottomPill.rowTitle(action, full), mark = null)
            picks[action] = cell
            cell.setOnClickListener {
                bp.choose(action)
                // ⛶ on the corner takes the column's away; anything else gives it back.
                syncFullHead()
                if (action == BottomPill.Action.UNDO) {
                    // ↶ stays open for a run of undos (the old flyout's rule); the daemon pushes
                    // around the open row.
                    if (flyout?.contentView === row) boxPick(action)
                    runPick(action)
                    return@setOnClickListener
                }
                // The face takes the pick in the frame the picker leaves — one e-ink update for
                // both (review 2026-10-10); the action itself runs after.
                flyoutPicked = true
                flyout?.dismiss()
                root.post { if (host.isAdded) runPick(action) }
            }
            cell.setOnLongClickListener {
                val hold: (() -> Unit)? = when (action) {
                    BottomPill.Action.FULL -> null
                    BottomPill.Action.PEN -> tools.pen.longPress
                    BottomPill.Action.ERASER -> tools.eraser.longPress
                    BottomPill.Action.UNDO -> tools.redo.action
                }
                when {
                    hold == null -> toast(BottomPill.rowTitle(action, full))
                    // Redo keeps the row open, as ↷ beside it does.
                    action == BottomPill.Action.UNDO -> hold()
                    // A hold fires with the contact still down: the usual deferred pen resume.
                    else -> { flyout?.dismiss(); root.post { if (host.isAdded) hold() } }
                }
                true
            }
            row.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx))
        }
        boxPick(bp.chosen())
        if (full) {
            row.addView(View(context).apply { setBackgroundColor(Color.BLACK) },
                LinearLayout.LayoutParams(maxOf(1, dp(1)), cellPx / 2).apply { marginStart = dp(3); marginEnd = dp(3) })
            val (redo, _) = smallCell(tools.redo.iconRes, "Redo", mark = null)
            // ↷ acts in place: the row stays up for a run, as the old flyout's did.
            redo.setOnClickListener { tools.redo.action() }
            redo.setOnLongClickListener { toast("Redo"); true }
            row.addView(redo, LinearLayout.LayoutParams(cellPx, cellPx))
            val (choose, _) = smallCell(tools.pen.iconRes, "Choose a pen…", mark = "…")
            choose.setOnClickListener {
                // The Pen cell's hold path, verbatim: the deferred resume, which the chooser's own
                // pause (before its show) cancels — no re-arm between the two windows.
                val open = tools.pen.longPress ?: tools.pen.action
                flyout?.dismiss()
                root.post { if (host.isAdded) open() }
            }
            choose.setOnLongClickListener { toast("Choose a pen…"); true }
            row.addView(choose, LinearLayout.LayoutParams(cellPx, cellPx))
            // Zoom (Michael 2026-10-12; review 2026-10-12: full screen hid the column and with it
            // the only Zoom): the seat's own tap and hold, wearing the seat's face — its last
            // choice ([reface]: Zoom box, Zoom + or Zoom −) — and boxed while the page stands
            // magnified or the box is armed, as on the column. For the box the row goes first, so
            // the box's drag isn't spent closing it; a ＋/− step acts in place and the row stays up
            // for a run of steps, as ↷ beside it does, the box following the page.
            tools.zoom?.let { handed ->
                val zoom = dressed(handed)
                val (z, zIcon) = smallCell(zoom.iconRes, spokenFor(zoom.name), mark = null)
                fun boxZoom() = z.setBackgroundResource(if (zoom.name in pageOn) R.drawable.rail_active_outline
                    else android.R.drawable.list_selector_background)
                fun dimZoom() {
                    val alpha = if (pageFaces[zoom.name]?.dim == true) DIM_FACE_ALPHA else 255
                    if (zIcon.imageAlpha != alpha) zIcon.imageAlpha = alpha
                }
                if (zoom.name in pageOn) boxZoom()
                dimZoom()
                nameHold(z, pageFaces[zoom.name]?.holdSpoken)
                z.setOnClickListener {
                    if (pageFaces[zoom.name]?.inPlace == true) {
                        zoom.action()
                        boxZoom()
                        dimZoom()
                        return@setOnClickListener
                    }
                    flyout?.dismiss()
                    root.post { if (host.isAdded) zoom.action() }
                }
                z.setOnLongClickListener {
                    val hold = zoom.longPress
                    if (hold == null) toast(spokenFor(zoom.name))
                    else { flyout?.dismiss(); root.post { if (host.isAdded) hold() } }
                    true
                }
                row.addView(z, LinearLayout.LayoutParams(cellPx, cellPx))
            }
        }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        // Above the pill (it floats at the foot of the screen), leading from its left, kept on
        // the glass — below it instead when it has been dragged up to the top, so the row never
        // lies over the pill it came from.
        val screenW = root.rootView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val x = loc[0].coerceAtMost(screenW - row.measuredWidth).coerceAtLeast(0)
        val above = loc[1] - row.measuredHeight - dp(4)
        val y = if (above >= 0) above else loc[1] + anchor.height + dp(4)
        val pw = android.widget.PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            animationStyle = 0   // e-ink: no animation frames
            elevation = 0f       // and no shadow smear
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                val picked = flyoutPicked
                flyoutPicked = false
                flyout = null
                flyoutScreenRect = null
                onFlyout(false, picked)
                onChromeChanged()
            }
        }
        flyout = pw
        flyoutScreenRect = Rect(x, y, x + row.measuredWidth, y + row.measuredHeight)
        onFlyout(true, false)
        if (runCatching { pw.showAtLocation(root, Gravity.NO_GRAVITY, x, y) }.isFailure) {
            // No window to show in (the page is going): undo the pause, or the pen stays dead.
            flyout = null
            flyoutScreenRect = null
            onFlyout(false, false)
            return
        }
        onChromeChanged()
        // The window manager may nudge a popup to keep it on screen: once laid out, cut out
        // where it really is.
        row.post {
            if (flyout !== pw || !row.isAttachedToWindow) return@post
            row.getLocationOnScreen(loc)
            val real = Rect(loc[0], loc[1], loc[0] + row.width, loc[1] + row.height)
            if (real != flyoutScreenRect && !real.isEmpty) { flyoutScreenRect = real; onChromeChanged() }
        }
    }

    /** The pill came, went, moved or changed width: hand the host its new spot, once. Posted, so
     *  positions are read after the pass that placed it. */
    override fun pillMoved() {
        if (!writing) return
        root.post {
            if (!host.isAdded || !root.isAttachedToWindow) return@post
            if (bottomPill()?.corner !== this) return@post
            val r = pillView()?.let { v ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            }
            if (r == lastPillRect) return@post
            lastPillRect = r
            onChromeChanged()
            onPillMoved()
        }
    }

    /** The column's Normal slot at its screen edge — the pill stands clear of it in both states. */
    override fun avoidPx(): Pair<Int, Int> {
        if (!writing) return 0 to 0
        val w = currentOpenWidth()
        return if (sideIsLeft()) w to 0 else 0 to w
    }

    /** The bottom pill as it stands over this page, or null while hidden. Only an ink page's. */
    private fun pillView(): View? = if (!writing) null
        else bottomPill()?.container?.takeIf { it.visibility == View.VISIBLE && it.isAttachedToWindow && it.width > 0 }

    /** The date strip's band — what a writing column stands beneath. */
    private fun bandPx(): Int = almanac?.layoutParams?.height?.takeIf { it > 0 }
        ?: context.resources.getDimensionPixelSize(R.dimen.ledger_navigator_height)

    /**
     * One cell of the picker: an icon filling the cell, and optionally a small [mark] in its
     * bottom corner.
     */
    private fun smallCell(iconRes: Int, name: String, mark: String?): Pair<ViewGroup, ImageView> {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cell = android.widget.FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            contentDescription = name
            addView(icon, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (mark != null) addView(TextView(context).apply {
                text = mark
                setTextColor(Color.BLACK)
                textSize = 9f
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(2); bottomMargin = dp(1)
            })
        }
        return cell to icon
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    /** Ink pages: the chrome over the surface now — the bottom pill, and the picker while it is up
     *  — for the host's Onyx exclude list (plan v6 S1.2, `provideExcludeViews`). */
    fun chromeViews(): List<View> = listOfNotNull(
        pillView(),
        flyout?.takeIf { it.isShowing }?.contentView?.takeIf { it.isAttachedToWindow },
        trayView?.takeIf { it.isAttachedToWindow })

    /**
     * The notes half's tray is opening over this page: the pen pauses BEFORE the window can take
     * a tap (plan v6 §2.5, as for the picker), and once the tray is laid out in its window its
     * rect is cut out of the B6 daemon's writes and excluded from the Onyx limit (review
     * 2026-10-11: with the pen hold on the pill, a pen opens the tray on the B6 now, and its
     * glyphs lay over the page with nothing between a tap and the daemon).
     */
    override fun trayOpened(content: View) {
        if (!writing) return
        // One pause per tray: a close that never came is settled first, or the pen stays dead.
        trayClosed()
        trayView = content
        onFlyout(true, false)
        content.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // Read after the window manager has placed it (a popup may be nudged on screen).
            v.post { if (trayView === v && host.isAdded) onChromeChanged() }
        }
    }

    /** The tray closed (or never showed): the deferred resume — past the dismissing tap, and
     *  cancelled by any window the picked glyph opens next — and its rect leaves the cut-outs. */
    override fun trayClosed() {
        if (trayView == null) return
        trayView = null
        onFlyout(false, false)
        onChromeChanged()
    }

    /**
     * Ink pages in full screen: the surface px the hidden column lends [surface], as (left, right)
     * (Michael 2026-10-11: "So, yeah it can grow as necessary"). Read from where the surface really
     * stands against the column's Normal slot, so it is (0, 0) in Normal and until the layout pass
     * after ⛶ has actually moved the surface — the host takes it with each surfaceChanged as a
     * DETECTOR (a ⛶ resize, never a turn — [InkViewport]); the page itself rests in the whole
     * surface, re-centred and fitted (Michael 2026-10-11).
     */
    fun borrowedRoom(surface: View): Pair<Int, Int> {
        if (!writing || isOpen || root.visibility != View.GONE) return 0 to 0
        val row = surface.parent as? View ?: return 0 to 0
        return InkViewport.borrowedRoom(surface.left, surface.right, row.width, currentOpenWidth(), sideIsLeft())
    }

    /**
     * The bottom pill alone as a rect over [surface], in its own pixels, clipped to it; null while
     * it is hidden or off the page. The surface is Z-ordered over its window, so the host keeps
     * this rect clear in its own repaints or page ink would draw over the pill (it floats over the
     * page now, Michael 2026-10-11). The picker needs no such room: it is a window of its own,
     * above the surface.
     */
    fun pillCutout(surface: View): Rect? {
        val v = pillView() ?: return null
        if (surface.width <= 0 || surface.height <= 0) return null
        val s = IntArray(2)
        surface.getLocationOnScreen(s)
        val p = IntArray(2)
        v.getLocationOnScreen(p)
        val r = Rect(p[0] - s[0], p[1] - s[1], p[0] - s[0] + v.width, p[1] - s[1] + v.height)
        return r.takeIf { it.intersect(0, 0, surface.width, surface.height) && !it.isEmpty }
    }

    /**
     * The same chrome as rects over [surface], in its own pixels, clipped to it — what the B6
     * daemon's writes must leave alone: the bottom pill, and the picker or the notes tray while
     * one hangs open.
     */
    fun chromeCutouts(surface: View): List<Rect> {
        if (surface.width <= 0 || surface.height <= 0) return emptyList()
        val s = IntArray(2)
        surface.getLocationOnScreen(s)
        val onScreen = ArrayList<Rect>()
        pillView()?.let { v ->
            val p = IntArray(2)
            v.getLocationOnScreen(p)
            onScreen += Rect(p[0], p[1], p[0] + v.width, p[1] + v.height)
        }
        flyoutScreenRect?.let { onScreen += Rect(it) }
        trayView?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let { v ->
            val p = IntArray(2)
            v.getLocationOnScreen(p)
            onScreen += Rect(p[0], p[1], p[0] + v.width, p[1] + v.height)
        }
        val out = ArrayList<Rect>()
        for (r in onScreen) {
            r.offset(-s[0], -s[1])
            if (r.intersect(0, 0, surface.width, surface.height) && !r.isEmpty) out += r
        }
        return out
    }

    /**
     * One seat's cell: the icon over its caption (plan v6 S1.1, Michael 2026-10-10 — "tiny
     * captions under icons", labels an option). [applyFit] sets the caption's size or hides it;
     * bare, the icon fills the cell with the old ImageButton's padding, so a rail with Tool
     * labels off looks exactly as it did.
     */
    private fun makeButton(handed: Item, side: Int): LinearLayout {
        // The face its page chose for it, if any (the Zoom seat's last choice — [reface]).
        val item = dressed(handed)
        // Monochrome faces only on the one panel language (Michael 2026-10-11: "Icons lack design
        // language"). A warning, not a crash, while the unmigrated rails still wear emoji (spec
        // critique #7: it becomes an assert once every panel has moved).
        if (listFull && item.glyph != null && !PanelSeats.isMonoGlyph(item.glyph))
            android.util.Log.w("TuckPanel", "colour face \"${item.glyph}\" on ${item.name} — use a PanelFaces drawable")
        val pad = Math.round(dp(8) * scale)
        val icon = ImageView(context).apply {
            if (item.glyph != null) setImageBitmap(drawGlyph(item.glyph))
            else {
                setImageResource(item.iconRes)
                // The panel is white in every theme, so its faces are black in every theme: the
                // shared ⛶ / Side / Delete drawables tint with colorControlNormal, near-white at
                // night (review 2026-10-11, #9). List panels only — the ink rails keep theirs.
                if (listFull) imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val caption = TextView(context).apply {
            text = captionFor(item)
            setTextColor(Color.BLACK)
            typeface = captionFace
            isSingleLine = true
            // Under the 8sp floor a long word ends in "…" rather than shrinking past legible.
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // A page-dressed face that would do nothing now is drawn faint ([reface]).
        if (pageFaces[item.name]?.dim == true) icon.imageAlpha = DIM_FACE_ALPHA
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            // A pressed state, not a bare 0 — on e-ink the action's own redraw can be hundreds
            // of milliseconds away, and a button that shows NOTHING in that window reads as a
            // button that did not hear you ("tuckpanel feels unresponsive at times"). The
            // selector paints immediately; the work can take its time.
            setBackgroundResource(android.R.drawable.list_selector_background)
            contentDescription = spokenFor(item.name)
            addView(icon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // 3dp under the word: the live tool's 2dp outline runs inside the cell's edge, and a
            // caption flush with the bottom sat on it (review 2026-10-10). The icon gives the room.
            addView(caption, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(3)
            })
            // MATCH the rail's width: the drawn icon stays [side] tall, but the whole
            // column answers a press — the extra horizontal dp are free touch target.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, side).apply {
                topMargin = dp(CELL_MARGINS_DP / 2); bottomMargin = dp(CELL_MARGINS_DP / 2)
            }
        }
        // A page-dressed hold names itself to TalkBack ([reface]: Zoom's three).
        nameHold(cell, pageFaces[item.name]?.holdSpoken)
        val seat = Seat(item, cell, icon, caption)
        // Through the seat, not the item: a redress ([redress]) hands the cell a fresh item.
        cell.setOnClickListener { seat.item.action() }
        cell.setOnLongClickListener {
            val second = seat.item.longPress
            if (second != null) second()
            // With Tool labels off the rail has no words anywhere; holding a button saying
            // its name is the cheapest honest substitute, and only for buttons with no
            // second act. Kept with captions on: the toast says the whole name.
            else android.widget.Toast.makeText(context, seat.item.name, android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        if (item.activeId != null) activeById[item.activeId] = seat
        else if (item.on || item.name in pageOn) styleOn(seat, true)
        seats.add(seat)
        return cell
    }

    /** Draw a character into a button-face bitmap — ScreenFragment.drawGlyphIcon's recipe
     *  (rendered at 2× so fitCenter always downscales, crisp at every dial step). */
    private fun drawGlyph(glyph: String): android.graphics.Bitmap {
        val size = dp(52).coerceAtLeast(48)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size * 0.7f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        // FIT THE FACE. The 0.7×box size is right for the single glyph this recipe was written
        // for — but the transport's speed button wears its VALUE as its face ("1.25×", by
        // design: the answer to "what speed am I on" should be readable without pressing
        // anything), and a five-character string at single-glyph size is wider than the square
        // bitmap it is drawn into. drawText clips at the bitmap's edge, so the face came out
        // oversized AND cropped — Michael's 08-12 punchlist: "Playback speed in the pop-out is
        // too big by just a bit." A bit is exactly what this trims: measure the string, and
        // only when it overruns the face, scale the text down to fit (a small side inset keeps
        // the digits off the rounded corners). Single glyphs measure well inside the box and
        // render precisely as before — the design he likes doesn't move.
        val fitted = fittedGlyphTextSize(paint.textSize, paint.measureText(glyph), size * 0.92f)
        if (fitted != paint.textSize) paint.textSize = fitted
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(glyph, size / 2f, y, paint)
        return bmp
    }

    /** The house EPD clean after a chrome change — same call, same reason as reapplyPillScale. */
    private fun epdClean() {
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(
                com.onyx.android.sdk.api.device.epd.UpdateMode.GC
            )
        } catch (t: Throwable) { /* non-Onyx device — no panel to clean */ }
    }

    /**
     * One column fit. [cellPx] is every seat's height; [captionSp] is 0 for a bare column (Tool
     * labels off — never for height); [scrolls] means the seats outrun the column and the
     * ScrollView carries the rest (Michael 2026-10-10: "scrollable if there are more buttons").
     */
    data class Fit(val cellPx: Int, val captionSp: Float, val scrolls: Boolean)

    companion object {
        /** Each surface's last measured column height, per orientation — the first fit of the
         *  next page uses it instead of the display height (see [setClusters]). Process-wide;
         *  a stale value only costs the one post-layout refit the display height always did. */
        private val lastColumnPx = HashMap<String, Int>()

        /** Each surface's last column scroll, per orientation (same key as [lastColumnPx]) — a
         *  new panel opens where the last one was left. ScrollView clamps it to the seats. */
        private val lastScrollY = HashMap<String, Int>()

        /** The cluster gap's minimum, and each cell's top + bottom margin. */
        const val CLUSTER_GAP_DP = 16
        const val CELL_MARGINS_DP = 2

        /** The one panel language's gap between blocks (frame · hand · room · foot) — 12dp, three
         *  of them, where the old column spent one 16dp gap (spec §1: three at 16 cost the B6 too
         *  much height). iOS spends 8pt. */
        const val BLOCK_GAP_DP = 12

        /** The full-screen [⛶] pill's cell on a list or reading surface, before the pill dial. */
        const val LIST_PILL_CELL_DP = 40

        /** A press this long without a drag is a hold (the notes pill's 550 ms). */
        const val HOLD_MS = 550L

        /**
         * Where a dragged floating pill stands, as fractions of the room it can travel ([travelX] =
         * frame width − pill width, [travelY] likewise), each 0..1 — so a rotation or a resized
         * page puts it at the same relative place. [x], [y]: its top-left in the frame.
         */
        fun listPillFractions(x: Float, y: Float, travelX: Int, travelY: Int): Pair<Float, Float> {
            fun f(v: Float, travel: Int) = if (travel <= 0) 0f else (v / travel).coerceIn(0f, 1f)
            return f(x, travelX) to f(y, travelY)
        }

        /** The translation that carries a pill laid out at ([left], [top]) to the fractions
         *  ([fx], [fy]) of its travel — always on the frame. */
        fun listPillTranslation(fx: Float, fy: Float, left: Int, top: Int, travelX: Int, travelY: Int): Pair<Float, Float> {
            val x = fx.coerceIn(0f, 1f) * travelX.coerceAtLeast(0)
            val y = fy.coerceIn(0f, 1f) * travelY.coerceAtLeast(0)
            return (x - left) to (y - top)
        }

        /** ⛶ on a list or reading surface: only a double tap's guard (review 2026-10-11, F14). The
         *  ink pages' 1.5s is the B6 daemon's — a list has none, and its pill must answer at once. */
        const val LIST_DEBOUNCE_MS = 400L

        /** The fixed gaps a one-panel-language column spends: one between each of its [blocks],
         *  and one more before a [hasFoot] that follows them. */
        fun blockGaps(blocks: Int, hasFoot: Boolean): Int =
            (blocks - 1).coerceAtLeast(0) + (if (hasFoot && blocks > 0) 1 else 0)

        /** The foot (Side) is pinned under the scroll exactly when the column scrolls — so it is in
         *  view at every height (review 2026-10-11, F1), and rides after the room otherwise. */
        fun footPinned(scrolls: Boolean): Boolean = scrolls

        /** What a cell's margins really take: half of [CELL_MARGINS_DP] above and below, each
         *  rounded down as [dp] does — 2px at the B6's 1.875, where px(2) said 3 (review
         *  2026-10-10). The fit counts this; [widthSide] keeps the old rule's px(2). */
        fun cellMarginsPx(density: Float): Int = 2 * (CELL_MARGINS_DP / 2 * density).toInt()

        /**
         * The smallest cell: a FINGER'S target, not a stylus's. 26dp answered a pen tip and
         * ignored a fingertip ("tuck panel seems to not respond well to finger but well to
         * stylus"); 36dp is the smallest square a thumb hits reliably on glass-over-e-ink.
         */
        const val MIN_CELL_DP = 36

        /**
         * The foot a writing column keeps before it takes the big 48dp labelled cells (review
         * 2026-10-10, on Michael's "when I'm writing by hand the buttons have to be at the top not
         * the bottom so they don't get touched accidentally"): on a real portrait B6 48dp cells
         * left the day rail a foot of a few dp, its nav seats down where the hand rests. It only
         * trades 48dp cells for 40dp ones — it never scrolls a column, folds a seat or hides a
         * caption, and a column that scrolls keeps no foot at all (Michael 2026-10-10: plain
         * scrolling, the hand-rest idea was not what he meant). Beneath the date strip a portrait
         * B6 day column keeps ~132dp (~108dp with a status bar).
         */
        const val PALM_COMFORT_DP = 120

        /** The smallest caption: below it a word ellipsizes instead of shrinking (review
         *  2026-10-10 — the Compact dial took long words to ~7sp, unreadable on e-ink). */
        const val MIN_CAPTION_SP = 8f

        /** A page-dressed face whose tap would do nothing now ([reface]'s dim) — about iOS's
         *  secondary: faint, still a solid grey line on e-ink, never gone. */
        const val DIM_FACE_ALPHA = 110

        /** The labelled column's side before its 6dp air: a 56dp column at the Standard dial,
         *  wide enough for an 8-character caption at 9-10sp (plan v6 §2.2). */
        const val LABELLED_SIDE_DP = 50

        /** Tool labels: ONE global switch in `ledger_widgets` (plan v6 §2.2), set from More ›
         *  Tool labels on the ink rails or Settings › Legibility › TOOL LABELS. */
        const val LABELS_KEY = "rail_labels"

        /** ⛶ debounce, from the tap (plan v6 §2.5): a second tap inside it is ignored, and the ⛶
         *  in the corner wears the inverted receipt for the rest of it. */
        const val FULL_DEBOUNCE_MS = 1500L

        /** The receipt's floor, when a B6 wait used the debounce up: still long enough to see. */
        const val RECEIPT_MIN_MS = 300L

        /** How many times a ⛶ toggle re-checks the B6 daemon before going anyway — a pen resting
         *  on the glass must not hold the switch forever. */
        const val FULL_WAIT_TRIES = 3

        /** The corner picker's padding round its cells. */
        const val PILL_PAD_DP = 3

        /**
         * Full screen for an ink surface, from what its prefs hold (plan v6 N22): the new key
         * when it exists; else a saved tucked state ([hasOpen] and not [open]) carries over as
         * full screen, 1:1; a surface that never tucked starts in Normal.
         */
        fun migratedFull(hasFull: Boolean, full: Boolean, hasOpen: Boolean, open: Boolean): Boolean =
            if (hasFull) full else hasOpen && !open

        /** A writing column's height before its first measure: the screen less the date strip
         *  it now stands beneath (Michael 2026-10-10), so the first fit is already the real one. */
        fun columnBeneathStrip(displayPx: Int, stripPx: Int): Int =
            (displayPx - maxOf(stripPx, 0)).coerceAtLeast(0)

        /** Tool labels, as set — or, never set, the default for this screen ([labelsDefault]). */
        fun labelsOn(context: android.content.Context): Boolean {
            val p = context.getSharedPreferences("ledger_widgets", 0)
            if (p.contains(LABELS_KEY)) return p.getBoolean(LABELS_KEY, true)
            val c = context.resources.configuration
            return labelsDefault(c.smallestScreenWidthDp, maxOf(c.screenWidthDp, c.screenHeightDp))
        }

        fun setLabelsOn(context: android.content.Context, on: Boolean) {
            context.getSharedPreferences("ledger_widgets", 0).edit().putBoolean(LABELS_KEY, on).apply()
        }

        /** On by default where captions have the room: smallest width ≥ 360dp and portrait
         *  height ≥ 600dp (plan v6 §2.2) — the B6, Palma and Go 6 all qualify. */
        fun labelsDefault(smallestWidthDp: Int, portraitHeightDp: Int): Boolean =
            smallestWidthDp >= 360 && portraitHeightDp >= 600

        /** Today's seat names whose caption isn't simply their first words — the plan v6 §1
         *  vocabulary (Side, More, Listen, Type, Compose, Thread, …), one word per meaning. */
        private val CAPTIONS = mapOf(
            "Quick tools" to "More", "Other side" to "Side", "Tuck away" to "Tuck",
            "Present" to "Today", "Today's page" to "Today",
            "Section up" to "Up", "Section down" to "Down",
            "Lasso select" to "Lasso", "Highlight" to "Mark", "Synthesize" to "Synth",
            "Page up" to "Page ↑", "Page down" to "Page ↓", "‹" to "Page ‹", "›" to "Page ›",
            "Feeds drawer" to "Feeds", "Reader view" to "Reader",
            "Read aloud" to "Listen", "Listen along" to "Listen",
            "This book" to "Book", "This page" to "Page", "Notes list" to "Notes",
            // The list surfaces' wrenches are their More-to-be (plan v6 §1, S3: "More = today's
            // wrench"), and their seats wear the S3 words already, so the captions now showing
            // don't change again in S3 (review 2026-10-10).
            "Shelf controls" to "More", "Screen controls" to "More", "Setup" to "More",
            "Back up" to "Up", "By stage" to "Stages", "New task" to "New", "New note" to "New",
            "Add a book" to "Add", "Card images" to "Images",
            "Aa" to "Type", "Reading size" to "Type",
            // The seat is named "Aa Type" now (the panel's name) and keeps its caption, on the
            // book reader and the article rails alike (review, Michael 2026-10-11).
            "Aa Type" to "Type",
            "New email" to "Compose", "Handwritten letter" to "Letter",
            "Where to?" to "Address", "Save for later" to "Later", "Add to Later" to "Later",
            "Marks & piles" to "Piles", "Links → Later" to "Links",
            "Assign to synthesis" to "To synth", "Send to All Picks" to "Pick",
            "All our letters" to "Thread", "Find in this piece" to "Find",
            "Last article" to "Previous", "First article." to "Previous", "Last article." to "Next",
            "Next article" to "Next", "Next email" to "Next", "Previous email" to "Previous",
            "Your year…" to "Year", "Star this" to "Star", "Note on this" to "Note",
            "Forward 30s" to "Fwd 30s", "Put the search away" to "Search"
        )

        /** [item]'s caption: its own, else derived from its name. */
        fun captionFor(item: Item): String = item.caption ?: captionFor(item.name)

        /** Two items wear the same face in a cell — icon, glyph, caption and tool box — so one can
         *  stand in for the other without a pixel moving (the redress rule). */
        fun sameFace(a: Item, b: Item): Boolean =
            a.iconRes == b.iconRes && a.glyph == b.glyph && a.activeId == b.activeId &&
                captionFor(a) == captionFor(b)

        /**
         * A seat's caption from its name, pure: the vocabulary first, else the name up to its
         * first separator ("Up / previous" → "Up", "Level: Week" → "Level"), whole while it is
         * short (≤ 10 characters — "All Picks" shrinks to fit), else its first word.
         */
        fun captionFor(name: String): String {
            CAPTIONS[name]?.let { return it }
            val bare = name.trimEnd('…', '.', ' ')
            CAPTIONS[bare]?.let { return it }
            val head = listOf(" — ", " · ", " / ", ": ", " (")
                .mapNotNull { sep -> bare.indexOf(sep).takeIf { it > 0 } }
                .minOrNull()?.let { bare.substring(0, it) } ?: bare
            return if (head.length <= 10) head else head.substringBefore(' ')
        }

        /**
         * The column fit, pure (Michael 2026-10-10: "scrollable if there are more buttons" —
         * overriding plan v6 §2.3's fold-and-drop-captions ladder). Every seat stays in the column
         * and keeps its caption; the cells give up size, and past that the column scrolls:
         *  - labelled: 48dp cells (10sp captions) when they fit with [comfortPx] to spare, else
         *    40dp cells (9sp), which scroll if they still don't fit;
         *  - bare (Tool labels off): [baseBarePx] shrunk to fit, floored at [MIN_CELL_DP], then
         *    scrolling.
         * Nothing folds into More for height; the More seat keeps only its own rows.
         */
        fun fitColumn(
            availPx: Int, density: Float, baseBarePx: Int,
            seats: Int, labelled: Boolean,
            comfortPx: Int = 0,
            /** The column's fixed gaps in px: the one cluster gap unless told (the one panel
             *  language spends [BLOCK_GAP_DP] between each of its blocks, Michael 2026-10-11). */
            gapsPx: Int = -1
        ): Fit {
            fun px(dp: Int) = (dp * density).toInt()
            val room = availPx - (if (gapsPx >= 0) gapsPx else px(CLUSTER_GAP_DP))
            val margins = cellMarginsPx(density)
            val floor = px(MIN_CELL_DP)
            if (seats <= 0) return Fit(maxOf(baseBarePx, floor), 0f, false)
            fun used(cell: Int) = seats * (cell + margins)
            if (labelled) {
                if (used(px(48)) + maxOf(comfortPx, 0) <= room) return Fit(px(48), 10f, false)
                return Fit(px(40), 9f, used(px(40)) > room)
            }
            val side = minOf(maxOf(baseBarePx, floor), room / seats - margins).coerceAtLeast(floor)
            return Fit(side, 0f, used(side) > room)
        }

        /**
         * The side the rail's WIDTH is built from: the bare cell for [seats] in [availPx] —
         * exactly the old rule's number, so the column keeps its width (plan v6 S1.0d changes no
         * geometry). The fit then moves only cell heights.
         */
        fun widthSide(availPx: Int, density: Float, baseBarePx: Int, seats: Int): Int {
            fun px(dp: Int) = (dp * density).toInt()
            val floor = px(MIN_CELL_DP)
            if (seats <= 0) return baseBarePx
            val fit = (availPx - px(CLUSTER_GAP_DP)) / seats - px(CELL_MARGINS_DP)
            return minOf(maxOf(baseBarePx, floor), fit).coerceAtLeast(floor)
        }

        /**
         * The text size that fits [measuredWidth] of glyph inside [maxWidth], scaling down from
         * [base] proportionally — and never up: a face that already fits keeps the house size,
         * so every single-glyph button in the rail stays pixel-identical. Text width scales
         * linearly with text size, which is the whole arithmetic. Pure, for [drawGlyph] and
         * for the unit test that pins the speed face ("1.25×") to its button.
         */
        fun fittedGlyphTextSize(base: Float, measuredWidth: Float, maxWidth: Float): Float =
            if (measuredWidth > maxWidth && measuredWidth > 0f) base * maxWidth / measuredWidth
            else base

        /**
         * The tucked strip's width in dp.
         *
         * Michael, 2026-08-07 and 08-10: "[the] side of screen inaccessible by 20 pixels as the
         * screen doesn't fill" — and, asked whether the strip was dead to touch or merely empty:
         * "visually short — it doesn't show text there."
         *
         * That is this number. 14dp is ~22px on the Tab X, which is exactly the band he measured
         * off the edge of the page. On the DAY surface the rail overlays the sheet, so nothing is
         * lost there; on the list surfaces — RSS, Notes & Tags, the Bookshelf — the gutter is a
         * real column in a LinearLayout, so every line of text stops short of the screen by this
         * much.
         *
         * Halved. A tucked rail is meant to be OUT OF THE WAY, and a strip that is out of the way
         * should not still be holding a column of the page; 7dp still reads as the divider it is
         * drawn to look like, and still takes a tap with the back-gesture exclusion around it.
         *
         * The halving REDUCED the loss; the overlay (see [overlapMargins] and the
         * `overlaysWhenTucked` note above) now removes it on the list surfaces: content runs
         * under the tucked strip, so this width is the strip's DRAWN-AND-TAPPED size, no longer
         * a band held out of any page. The ink pages have no strip since S1.2 (⛶ full screen
         * keeps their slot instead — the pen-honesty argument is theirs alone).
         */
        const val STRIP_DP = 7

        /**
         * The tucked strip's TOUCH band in dp — what the finger gets while the eye keeps
         * [STRIP_DP]. 24dp is the floor Android's own a11y guidance draws under any target
         * (48dp is the comfortable number; 24 is the minimum that stops being a stab), and it
         * stays under every list surface's content padding + first glyph, so the band never
         * sits over a row's tap-worthy leading content — only its margin. Served by the
         * transparent [stripHit] view on gutter-hosted rails; the drawn hairline + grip are
         * untouched (see updateStripHit for why ink rails never had the band).
         */
        const val STRIP_HIT_DP = 24

        /**
         * The overlay's whole arithmetic: (leftMargin, rightMargin) for the gutter in its host
         * row. A tucked gutter-hosted strip gives its width back to the row through a negative
         * margin on its page side — docked left, the page is to the right; docked right, to the
         * left — so the content cell measures full-width and the strip lies over its edge. Open
         * rails and toolbar-hosted (ink) rails owe nothing: zero margins, honest column.
         */
        fun overlapMargins(
            open: Boolean, overlaysWhenTucked: Boolean, widthPx: Int, dockedLeft: Boolean
        ): Pair<Int, Int> {
            if (open || !overlaysWhenTucked) return 0 to 0
            return if (dockedLeft) 0 to -widthPx else -widthPx to 0
        }

        /**
         * The open rail's width from its button side and its side air — pure arithmetic, kept
         * out of the view code so the sizing rule the pill dial drives can be pinned by a test:
         * the rail must always be exactly one button wide, never a second column, never a
         * clipped one.
         */
        fun railWidth(buttonSidePx: Int, airPx: Int): Int = buttonSidePx + airPx
    }
}
