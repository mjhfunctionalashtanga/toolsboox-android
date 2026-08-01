package com.toolsboox.ot

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.toolsboox.R

/**
 * The tucked action panel — the Weeks popout's tuck-in/tuck-out idiom, grown into a component.
 *
 * Michael, on the week page's old menu: "it tucks in and tucks right out, and you could just put
 * it on any side." That behavior lives in the floating-pill machinery (a grip you tap to fold and
 * drag to move — [com.toolsboox.ui.plugin.ScreenFragment.cyclePillOnTap] and `makeDraggable`),
 * and this class is the same bones rebuilt as the universal ACTION layer: everything on a surface
 * that is a place-to-go or a thing-to-invoke moves off the floating pills and into one panel that
 * tucks to a slim edge tab. The in-stroke TOOL pill stays floating — a tool you need between two
 * strokes must never become two taps — but the panel carries its options too, so hiding the pill
 * costs nothing but convenience.
 *
 * WHY EDGE-RELATIVE GEOMETRY, SPELLED OUT ONCE: the floating pills remember a translation in
 * pixels, measured against whatever screen they were dragged on, which is why ScreenFragment
 * carries a flip-rescale listener to keep them reachable through a rotation. This panel stores
 * only WHICH EDGE it lives on and lays itself out by gravity, centered along that edge — there is
 * no absolute coordinate anywhere, so a rotation simply re-runs layout and the tab is exactly
 * where it should be. Rotation-stable by construction, not by compensation. That is the whole
 * argument for the panel over the pills as the place actions live.
 *
 * E-INK DISCIPLINE: no slide animation — an animated tuck is a dozen ghosted half-frames on an
 * EPD. Open and tuck are single visibility flips followed by the house full-panel clean
 * (`EpdController.repaintEveryThing(GC)`, the same call `reapplyPillScale` makes after a resize,
 * because removing chrome leaves its outline ghosted exactly the way a drag does).
 *
 * STYLUS: when TUCKED the panel is one slim tab; the host feeds [excludeViews] to the Onyx raw
 * reader, so the pen is live everywhere except the tab's own pixels. When OPEN the host's
 * modal-pause runs (via [onOpened]/[onTucked] — the same onModalShown/onModalDismissed pause every
 * popover over a canvas uses), so a pen tap lands on rows instead of inking under them, and a tap
 * outside tucks the panel instead of leaving a stray dot.
 */
class TuckPanel(
    private val host: Fragment,
    private val container: ViewGroup,
    /** Prefs are keyed per surface (like the pills' per-surface position memory): each converted
     *  surface keeps its own docked side and open state, because the edge that is free on the day
     *  page may be exactly the edge a future surface draws on. */
    private val surfaceKey: String,
    /** The hub's row-dressing pass ([com.toolsboox.ui.plugin.ScreenFragment.applyRowIcon]): the
     *  leading emoji becomes the row's outline icon (or a drawn glyph), so panel rows read as kin
     *  to the directory/hub rather than a new species. Passed in because that vocabulary lives on
     *  the fragment, and the panel should borrow it, not fork it. */
    private val dressRow: (View, String) -> CharSequence,
    /** Fired when the panel opens/tucks — the host routes these to its modal pen pause. */
    private val onOpened: () -> Unit,
    private val onTucked: () -> Unit,
    /** Fired when the tab/panel's footprint changed (dock moved, open/tuck) so the host can
     *  re-feed the raw exclude rects. */
    private val onGeometryChanged: () -> Unit
) {

    /** One tappable row. [label] is a supplier because some labels state a toggle ("Hide the tool
     *  pill") and must read fresh on every open. */
    class Row(val emoji: String, val label: () -> String, val action: () -> Unit)

    /** One panel entry: a plain row ([action]), or a fold ([rows]) — the hub's sub-fold idiom
     *  (Mail's per-account rows), used here so the Tools section can unfurl the tool pill's own
     *  options without the panel being long on the days you don't want them. */
    class Entry(
        val emoji: String,
        val label: () -> String,
        val action: (() -> Unit)? = null,
        val rows: List<Row> = emptyList()
    )

    companion object {
        const val SIDE_LEFT = "left"
        const val SIDE_RIGHT = "right"
        const val SIDE_TOP = "top"
        const val SIDE_BOTTOM = "bottom"

        /**
         * Which edge a dragged tab should dock to, from where the finger let go of it.
         *
         * Pure arithmetic, kept out of the view code so it can be pinned by a unit test (the
         * PillBounds precedent). Nearest edge wins; on a perfect tie the vertical edges win,
         * because a vertical list of rows is the panel's native shape and the side docks are
         * where it reads best.
         */
        fun nearestSide(cx: Float, cy: Float, width: Float, height: Float): String {
            val dLeft = cx
            val dRight = width - cx
            val dTop = cy
            val dBottom = height - cy
            val min = minOf(dLeft, dRight, dTop, dBottom)
            return when (min) {
                dLeft -> SIDE_LEFT
                dRight -> SIDE_RIGHT
                dTop -> SIDE_TOP
                else -> SIDE_BOTTOM
            }
        }
    }

    private val context get() = host.requireContext()
    private val prefs get() = context.getSharedPreferences("ledger_widgets", 0)
    private val sideKey = "tuck_${surfaceKey}_side"
    private val openKey = "tuck_${surfaceKey}_open"

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Same text dial as the directories — one dial for everything that reads as a menu. */
    private val textScale get() = ModalScale.sizeScale(context)

    private var entries: List<Entry> = emptyList()

    /**
     * The overlay is a plain FrameLayout over the whole surface: gravity does the docking, so
     * every geometry decision is "which corner of which edge", never a coordinate. Not clickable
     * while tucked — an unclickable ViewGroup with no background passes touches straight through,
     * which is what keeps the page's own touch handling (and the pen) unaffected beyond the tab.
     */
    private val overlay = FrameLayout(context)

    /** The slim edge tab — the grip. Wears the pills' grip art and background so the hand that
     *  learned "this is a handle" on the pills already knows this one. */
    private val grip = ImageView(context).apply {
        setImageResource(R.drawable.ic_grip)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundResource(R.drawable.ledger_bar_bg)
        contentDescription = "Actions"
    }

    private val panelList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    /** Rows scroll rather than overflow — a Tools fold opened at Large text can outgrow a Palma. */
    private val panelBox = ScrollView(context).apply {
        setBackgroundResource(R.drawable.dialog_rounded_bg)
        isVerticalScrollBarEnabled = false
        addView(
            panelList,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        visibility = View.GONE
    }

    var isOpen: Boolean = false
        private set

    init {
        // Fill the container. On a ConstraintLayout, children must be constrained or a cloned
        // ConstraintSet elsewhere can zero them (the spiralLine/notePager lesson) — but this
        // overlay attaches to the fragment's ROOT layout, which CalendarUtils never clones, and
        // pins itself to all four parent edges so there is nothing left to describe.
        val lp = if (container is ConstraintLayout) {
            ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        } else {
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.addView(overlay, lp)
        // Panel first, grip second: the tab draws over the panel's docked edge, so open or tucked
        // it is always on top and always tappable.
        overlay.addView(panelBox)
        overlay.addView(grip)
        // The outside-tap catcher: only armed while open (see open()/tuck()), so while tucked the
        // overlay is inert glass. Listener FIRST, then clickable off — setOnClickListener flips
        // clickable back on as a side effect, and the other order would leave the tucked overlay
        // silently eating every touch on the page.
        overlay.setOnClickListener { tuck() }
        overlay.isClickable = false
        wireGrip()
        applySide(side())
    }

    /** The persisted dock, defaulting right — the edge the old nav pill anchored to, so the tab
     *  appears where the hand already goes. */
    private fun side(): String = prefs.getString(sideKey, SIDE_RIGHT) ?: SIDE_RIGHT

    fun setEntries(entries: List<Entry>) {
        this.entries = entries
    }

    /** The views the host feeds to the raw stylus reader as exclude rects. */
    fun excludeViews(): List<View> = listOf(grip, panelBox)

    /**
     * Re-open if the last session left the panel open. Called from the host's onResume (posted,
     * so the Onyx pen has finished arming first — opening earlier would pause a pen that then
     * re-arms underneath the panel). Idempotent: an already-open panel stays as it is.
     */
    fun applyPersistedOpen() {
        if (prefs.getBoolean(openKey, false) && !isOpen) open()
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        // Re-derive the geometry at open time: the panel's width is sized against the container,
        // and the container at construction time hadn't been laid out yet (width 0). Opening is
        // rare enough that re-anchoring is free, and it also covers a rotation since last open.
        applySide(side())
        rebuildRows()
        panelBox.visibility = View.VISIBLE
        overlay.isClickable = true
        prefs.edit().putBoolean(openKey, true).apply()
        onOpened()
        onGeometryChanged()
        epdClean()
    }

    fun tuck() {
        if (!isOpen) return
        isOpen = false
        panelBox.visibility = View.GONE
        overlay.isClickable = false
        prefs.edit().putBoolean(openKey, false).apply()
        onTucked()
        onGeometryChanged()
        epdClean()
    }

    /** Dock to [side] and remember it. Everything is gravity, so this is just re-anchoring. */
    fun dock(side: String) {
        prefs.edit().putString(sideKey, side).apply()
        applySide(side)
        onGeometryChanged()
        epdClean()
    }

    /**
     * Lay the tab and the panel out against [side]. The grip is centered along its edge rather
     * than remembering an along-edge offset: centered is the one position that means the same
     * thing at every screen size and orientation, which is the point of the exercise.
     */
    private fun applySide(side: String) {
        val scale = ModalScale.pillScale(context)
        val thick = Math.round(context.resources.getDimensionPixelSize(R.dimen.ledger_grip_short) * scale)
        // Twice the pill grip's long side: an edge tab is found by feel along a whole edge, so it
        // earns a little more length than a grip that sits on a pill you can already see.
        val length = Math.round(context.resources.getDimensionPixelSize(R.dimen.ledger_grip_long) * 2 * scale)
        // Physical gravities (LEFT, not START): the docked side is a physical edge of the slab in
        // your hands, and must not flip under an RTL locale.
        val vertical = side == SIDE_LEFT || side == SIDE_RIGHT
        grip.layoutParams = FrameLayout.LayoutParams(
            if (vertical) thick else length,
            if (vertical) length else thick,
            when (side) {
                SIDE_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                SIDE_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                SIDE_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        )
        // The panel rides beside its tab (margin = the tab's thickness) so the tab stays visible
        // as the way to tuck — the popout with its handle still on it, exactly like the Weeks
        // pill folded around its grip.
        //
        // Top/bottom docks keep the panel VERTICAL, anchored to that edge. The rows are the hub's
        // rows — an icon and a label — and rows laid on their side would break the kinship with
        // the directory that is the panel's whole visual argument; a horizontal dock changes
        // where the panel hangs, not what it is.
        val width = minOf(dp((300 * textScale).toInt()), (container.width * 0.8f).toInt().coerceAtLeast(dp(200)))
        val margin = thick + dp(2)
        grip.translationX = 0f
        grip.translationY = 0f
        panelBox.layoutParams = FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT,
            when (side) {
                SIDE_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                SIDE_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                SIDE_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        ).apply {
            when (side) {
                SIDE_LEFT -> leftMargin = margin
                SIDE_RIGHT -> rightMargin = margin
                SIDE_TOP -> topMargin = margin
                else -> bottomMargin = margin
            }
        }
    }

    /**
     * The grip is the whole control, as on the pills: a tap tucks/opens, a drag re-docks. Plain
     * drag rather than long-press-drag because that is what the pills already taught the hand —
     * `makeDraggable` moves a pill on a plain grip drag, and a panel that demanded a long-press
     * for the same gesture would make the one handle in the app that behaves differently.
     * On release the tab docks to the nearest edge; mid-drag it follows the finger (translation
     * only, discarded on dock) so the move is visible while it happens.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireGrip() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        grip.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        v.translationX = dx
                        v.translationY = dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        if (isOpen) tuck() else open()
                    } else {
                        val cx = v.left + v.translationX + v.width / 2f
                        val cy = v.top + v.translationY + v.height / 2f
                        dock(nearestSide(cx, cy, overlay.width.toFloat(), overlay.height.toFloat()))
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.translationX = 0f; v.translationY = 0f
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Rebuilt in full on every open — the house idiom for e-ink lists (the hub's find field, the
     * directory): a rebuild is one relayout, which is cheaper to reason about than any diffing,
     * and it is also what lets toggle labels ("Hide the tool pill") state the truth each time.
     */
    private fun rebuildRows() {
        panelList.removeAllViews()
        for (entry in entries) {
            if (entry.rows.isEmpty()) {
                panelList.addView(makeRow(entry.emoji, entry.label(), indent = 0) {
                    // Tuck first, then act: a row that navigates replaces this fragment, and a
                    // row that opens a menu wants the pen pause handed over cleanly (tuck's
                    // deferred resume is cancelled by the menu's own onModalShown).
                    tuck()
                    entry.action?.invoke()
                })
                continue
            }
            // A fold — the hub's second-level fold, with its remembered state: a fold you open
            // every time you open the panel is a fold the panel should have remembered.
            val foldKey = "tuck_${surfaceKey}_fold_${entry.label()}"
            val outline = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1), LedgerTheme.accent(context))
                cornerRadius = dp(8).toFloat()
            }
            val foldBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (prefs.getBoolean(foldKey, false)) View.VISIBLE else View.GONE
                background = if (visibility == View.VISIBLE) outline else null
                setPadding(dp(2), dp(2), dp(2), dp(4))
            }
            fun caret() = if (foldBox.visibility == View.VISIBLE) "▾" else "▸"
            lateinit var headerLabel: TextView
            val header = makeRow(entry.emoji, "${caret()}  ${entry.label()}", indent = 0) {
                // The header toggles, never tucks — unfolding Tools and having the panel vanish
                // under your finger would be the panel disagreeing with itself.
                val show = foldBox.visibility != View.VISIBLE
                foldBox.visibility = if (show) View.VISIBLE else View.GONE
                foldBox.background = if (show) outline else null
                prefs.edit().putBoolean(foldKey, show).apply()
                headerLabel.text = dressRow(headerLabel.parent as View, "${entry.emoji}  ${caret()}  ${entry.label()}")
                epdClean()
            }
            headerLabel = header.findViewById(R.id.go_label)
            for (row in entry.rows) {
                foldBox.addView(makeRow(row.emoji, row.label(), indent = dp(24)) {
                    tuck()
                    row.action()
                })
            }
            panelList.addView(header)
            panelList.addView(
                foldBox,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6), dp(1), dp(6), dp(4)) }
            )
        }
        LedgerFonts.applyTree(panelList)
    }

    /** One hub-vocabulary row: item_go_to inflated and dressed by the host's icon pass. */
    private fun makeRow(emoji: String, label: String, indent: Int, action: () -> Unit): View {
        val r = host.layoutInflater.inflate(R.layout.item_go_to, panelList, false)
        val tv = r.findViewById<TextView>(R.id.go_label)
        tv.text = dressRow(r, "$emoji  $label")
        tv.textSize = 16f * textScale   // item_go_to's row size, on the shared modal dial
        if (indent > 0) tv.setPadding(indent, tv.paddingTop, tv.paddingRight, tv.paddingBottom)
        val icon = r.findViewById<ImageView>(R.id.go_icon)
        val iconSide = (22f * textScale * context.resources.displayMetrics.density).toInt()
        icon.layoutParams = icon.layoutParams.apply { width = iconSide; height = iconSide }
        val pad = dp((10 * textScale).toInt())
        r.setPadding(r.paddingLeft, pad, r.paddingRight, pad)
        r.setOnClickListener { action() }
        return r
    }

    /** The house EPD clean after a chrome change — same call, same reason as reapplyPillScale. */
    private fun epdClean() {
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(
                com.onyx.android.sdk.api.device.epd.UpdateMode.GC
            )
        } catch (t: Throwable) { /* non-Onyx device — no panel to clean */ }
    }
}
