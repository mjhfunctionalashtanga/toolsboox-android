package com.toolsboox.ot

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
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
 * TUCKED = a thin strip flush on the edge, nothing else — no floating tab, no pill grip. The
 * strip is the summon affordance the Weeks hand already knows (tap it and the rail comes out);
 * it wears a hairline on its page side and the pills' muted grip dots, so it reads as a
 * deliberate handle rather than the bare gray band the upstream collapse leaves.
 *
 * E-INK: open and tuck are single width/visibility flips — no animation frames — followed by the
 * house full-panel clean (`repaintEveryThing(GC)`, as `reapplyPillScale` does after a resize,
 * because a chrome change leaves its old outline ghosted).
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
    gutter: ConstraintLayout? = null
) {

    /**
     * One rail button. [activeId] marks the mutually-exclusive tool buttons (pen/eraser/lasso)
     * so [markActive] can box the live one; [longPress] is the button's second act (the pill
     * precedent: pen holds the style picker, eraser holds clear-page) — buttons without one
     * toast their [name] on a long-press, which is the only labelling a rail of bare icons gets.
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
        val action: () -> Unit
    )

    private val context get() = host.requireContext()
    private val prefs get() = context.getSharedPreferences("ledger_widgets", 0)
    private val openKey = "tuck_${surfaceKey}_open"

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** The rail rides the PILL dial: it replaces the floating pills, so the dial that sized
     *  them ("Compact slims chrome into the reading margin") must keep its promise here. */
    private val scale get() = ModalScale.pillScale(context)

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

    private val activeById = HashMap<String, ImageButton>()

    /** Column content: top cluster (tools), a breathing gap, bottom cluster (nav/surface) —
     *  the Weeks rail's two-cluster anatomy. fillViewport keeps the gap elastic when everything
     *  fits; when the pill dial or a short landscape screen overflows the column, it scrolls
     *  instead of clipping buttons off the end. */
    private val topCluster = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bottomCluster = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rail = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(topCluster, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Space(context), LinearLayout.LayoutParams(0, dp(16), 1f))
            addView(bottomCluster, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The tucked strip's handle mark — the pills' grip dots, centered on the strip. */
    private val stripMark = ImageView(context).apply {
        setImageResource(R.drawable.ic_grip)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Actions"
    }

    var isOpen: Boolean = prefs.getBoolean(openKey, true)
        private set

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
     * GUTTER-HOSTED RAILS ONLY, deliberately. On the ink pages the tucked strip holds a real
     * column and the page ends where it begins — that is the pen-honesty argument at the top of
     * this file — and a 24dp invisible band over the page edge would swallow finger input on
     * ink the drawn chrome doesn't claim. The list surfaces have no pen and their content
     * already runs under the strip; a band over their edge is the same bargain the overlay
     * already struck.
     */
    private var stripHit: View? = null

    init {
        // Children of the toolbar's OWN root — one level below drawing_layout, so the
        // ConstraintSet that CalendarUtils clones over drawing_layout never sees them and the
        // 0×0 rule (see updateToolbar's comment) doesn't apply. They still need constraining
        // within this ConstraintLayout, which is just "fill me" / "center on me".
        root.addView(rail, ConstraintLayout.LayoutParams(0, 0).apply {
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
            // The tucked strip hugs a screen edge, exactly where the system back gesture
            // lives; the Weeks toolbar excludes its collapsed strip for the same reason (its
            // comment: makes it "reliably tappable without needing to be wide").
            v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
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
        root.setOnClickListener { if (isOpen) tuck() else open() }
        applyState()
    }

    /**
     * Give the rail its buttons. [top] is the tools cluster, [bottom] the nav/surface cluster;
     * the collapse chevron rides at the very foot on its own — the rail owns its tuck the way
     * the upstream toolbar owned its toggle.
     */
    fun setClusters(top: List<Item>, bottom: List<Item>) {
        topCluster.removeAllViews()
        bottomCluster.removeAllViews()
        activeById.clear()
        for (item in top) topCluster.addView(makeButton(item))
        for (item in bottom) bottomCluster.addView(makeButton(item))
        // The way out is an ✕, not a chevron (Michael: "turn the < at the bottom of the panel
        // into a ✕ close button") — a chevron promises sliding, and the rail doesn't slide, it
        // tucks; ✕ is the house glyph for "put this away" (the image chips, the tag pills).
        bottomCluster.addView(makeButton(Item(0, "Tuck away", glyph = "✕") { tuck() }))
        applyState()
    }

    /** Box the live tool (pen/eraser/lasso) the way the floating pill marked its active tool —
     *  same drawable, same one-boxed-at-a-time rule, so the language carries over unchanged. */
    fun markActive(id: String?) {
        for ((key, btn) in activeById) {
            btn.setBackgroundResource(if (key == id) R.drawable.tool_active_bg else 0)
        }
    }

    fun open() {
        if (!isOpen) {
            isOpen = true
            prefs.edit().putBoolean(openKey, true).apply()
        }
        applyState()
        epdClean()
    }

    fun tuck() {
        if (isOpen) {
            isOpen = false
            prefs.edit().putBoolean(openKey, false).apply()
        }
        applyState()
        epdClean()
    }

    /** The rail's current footprint in the layout flow. */
    fun currentWidth(): Int =
        if (isOpen) railWidth(buttonSide(), dp(6)) else dp(STRIP_DP)

    /**
     * One button's side: the toolbar button dimen on the pill dial — SHRUNK to fit when the
     * column doesn't have the height (a landscape Palma is ~412dp for fourteen buttons). The
     * upstream toolbar answers short screens by tiling extra columns; a rail is one column by
     * definition, so it keeps its shape and gives up size instead, floored where the icons
     * stop being targets — past that the ScrollView underneath scrolls rather than clips.
     */
    private fun buttonSide(): Int {
        val base = Math.round(context.resources.getDimensionPixelSize(R.dimen.ledger_toolbar_button) * scale)
        val count = topCluster.childCount + bottomCluster.childCount
        if (count == 0) return base
        // Height really available to buttons: the screen minus the cluster gap's minimum and
        // each button's margins.
        val avail = context.resources.displayMetrics.heightPixels - dp(16) - count * dp(2)
        return minOf(base, avail / count).coerceAtLeast(dp(26))
    }

    /**
     * Lay the current state onto the toolbar root: open = the icon column at button width,
     * tucked = the thin strip. Width is set on the root's layout params — the same mutation
     * upstream's collapse makes — and the constraint chain does the rest: the page's surface
     * ends where the rail begins, which is what keeps the pen honest with no exclude rects.
     */
    fun applyState() {
        rail.visibility = if (isOpen) View.VISIBLE else View.GONE
        stripMark.visibility = if (isOpen) View.GONE else View.VISIBLE
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
            setStroke(dp(1), 0xFF333333.toInt())
        }
        val off = dp(2)
        root.background = android.graphics.drawable.LayerDrawable(arrayOf(edged)).apply {
            // Docked left → the page is to the right, so only the right stroke survives.
            if (sideIsLeft()) setLayerInset(0, -off, -off, 0, -off)
            else setLayerInset(0, 0, -off, -off, -off)
        }
        root.requestLayout()
        updateStripHit()
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
        if (isOpen || !overlaysWhenTucked) return
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

    private fun makeButton(item: Item): ImageButton {
        val side = buttonSide()
        val pad = Math.round(dp(8) * scale)
        return ImageButton(context).apply {
            if (item.glyph != null) setImageBitmap(drawGlyph(item.glyph))
            else setImageResource(item.iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(0)
            contentDescription = item.name
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                topMargin = dp(1); bottomMargin = dp(1)
            }
            setOnClickListener { item.action() }
            setOnLongClickListener {
                val second = item.longPress
                if (second != null) second()
                // A rail of bare icons has no labels anywhere; holding a button saying its name
                // is the cheapest honest substitute, and only for buttons with no second act.
                else android.widget.Toast.makeText(context, item.name, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            if (item.activeId != null) activeById[item.activeId] = this
        }
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

    companion object {
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
         * a band held out of any page. On the ink pages the strip still holds its column — the
         * pen-honesty argument is theirs alone.
         */
        const val STRIP_DP = 7

        /**
         * The tucked strip's TOUCH band in dp — what the finger gets while the eye keeps
         * [STRIP_DP]. 24dp is the floor Android's own a11y guidance draws under any target
         * (48dp is the comfortable number; 24 is the minimum that stops being a stab), and it
         * stays under every list surface's content padding + first glyph, so the band never
         * sits over a row's tap-worthy leading content — only its margin. Served by the
         * transparent [stripHit] view on gutter-hosted rails; the drawn hairline + grip are
         * untouched (see updateStripHit for why ink rails keep the bare strip).
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
