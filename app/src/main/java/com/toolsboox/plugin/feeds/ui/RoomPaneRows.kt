package com.toolsboox.plugin.feeds.ui

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.toolsboox.R
import com.toolsboox.ot.PanelFaces
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ONE ROW STYLE FOR THE ROOM PANES (Michael 2026-10-12, on the iOS Feeds pane in 245: "It's great!
 * The aesthetic/design weight/language needs a bit of tweaking for readability/usability"). The
 * Feeds drawer and The Mail's drawer draw every row through [RoomPane], so the two rooms read alike
 * and alike to the iOS pane (RoomPaneKit's PaneRowView):
 *  - ONE size and ONE weight for every row ([TEXT_SP], regular). Bold + underline is only ever
 *    "this is what's on screen" — one row at a time, never a kind of row.
 *  - Counts FIRST, in a compact column at the row's head ([countText]: blank at 0, "12k" from
 *    10,000), regular weight, on the title's first line. The column is never narrower than one
 *    digit, so it never folds away; rows that share a [row] `run` (one category's sites, one
 *    account's mailboxes) share its width, every other row shares one ([finish], iOS
 *    `CountColumn.digits(byRow:)`), and a run is never narrower than the pane's — opening or
 *    closing a folder never slides the rows around it, and no child starts left of its parent.
 *  - MONOCHROME faces only — the panel's own ic_pf_* outline family ([PanelFaces.Face]). No colour
 *    emoji (🌐 📧 🔖 📡 🦋 📖 📺 🎧 render in colour on LCD and as grey dither on e-ink).
 *  - ONE TITLE COLUMN: every row keeps the face slot, face or not, so a category's name starts
 *    where its lens's name does and nothing ever sits left of its parent; the one visual indent
 *    is a sub-row's ([INDENT_DP] per [row] `level`: a site, a Later lane, a local feed, an
 *    account's mailbox — design v2 §3, iOS `isSubRow`).
 *  - Comfortable rows ([ROW_MIN_DP]); a long name wraps to two lines, then ends in "…"; the face,
 *    the count and the chevron sit on the first line. Faces and row height follow the system text
 *    size, as the titles do (iOS @ScaledMetric).
 *  - One section header style ([section]).
 *  - E-ink contrast: ink is black; nothing is drawn lighter than #444; no pressed shade.
 *
 * Pure numbers live in the companion so RoomPaneRowsTest can hold them.
 */
class RoomPane(
    private val container: LinearLayout,
    /** Run before a row's tap (FeedsFragment: a row picked while reading returns to the list). */
    private val beforeTap: () -> Unit = {},
) {
    private val ctx = container.context
    private val density = ctx.resources.displayMetrics.density
    /** The system text size the titles (sp) already follow; the faces and the row height follow it
     *  too, so a face never shrinks beside a large title. */
    private val scale = ctx.resources.configuration.fontScale.coerceIn(MIN_SCALE, MAX_SCALE)
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun scaled(v: Int) = (v * density * scale).roundToInt()

    private val rowMin = scaled(ROW_MIN_DP)
    private val faceSize = scaled(FACE_DP)
    private val chevronSize = scaled(CHEVRON_DP)

    /** Every row's count cell and the run it shares a width with (null = the pane's own). */
    private class Cell(val view: TextView, val run: String?)
    private val counts = ArrayList<Cell>()

    /** A section header — the same caption in both rooms: small, bold, tracked, black. */
    fun section(title: String) {
        container.addView(TextView(ctx).apply {
            text = title.uppercase()
            textSize = HEADER_SP
            letterSpacing = 0.08f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(PAD_START_DP), dp(16), dp(PAD_END_DP), dp(4))
            ViewCompat.setAccessibilityHeading(this, true)
        })
    }

    /**
     * One row: [count] · indent · face slot ([face], or empty) · [title] · chevron.
     *  - [level] 0 = a door, lens, category, mailbox or account; 1 = a sub-row: a site under its
     *    category, a Later lane, a local feed, an account's own mailbox.
     *  - [run]: rows that share a count-column width (one category's sites, one account's
     *    mailboxes); null = the pane's own column.
     *  - [folder] null = not a folder; true = open (chevron down); false = shut (chevron right).
     *    The whole row is the tap — a folder's chevron is part of it, as on iOS.
     *  - [selected]: what the list on screen is — bold + underline, the one emphasis.
     */
    fun row(
        title: String,
        face: PanelFaces.Face? = null,
        count: Int = 0,
        level: Int = 0,
        run: String? = null,
        selected: Boolean = false,
        folder: Boolean? = null,
        onLongPress: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Baseline-aligned (LinearLayout's default, for the TOP-gravity children below): the
            // count stands on the title's first line, however many lines the title takes.
            minimumHeight = rowMin
            setPadding(dp(PAD_START_DP), 0, if (folder != null) 0 else dp(PAD_END_DP), 0)
            // No pressed shade: on e-ink a selector is a flash per tap (the rows never had one).
            isClickable = true
            setOnClickListener { beforeTap(); onClick() }
            if (onLongPress != null) setOnLongClickListener { onLongPress(); true }
            contentDescription = title + if (count > 0) ", $count unread" else ""
            if (folder != null) ViewCompat.setStateDescription(this, if (folder) "expanded" else "collapsed")
            if (selected) isSelected = true
        }
        // The count column — first; its width is set in finish(), by run.
        val c = TextView(ctx).apply {
            text = countText(count)
            textSize = COUNT_SP
            setTextColor(INK)
            typeface = Typeface.DEFAULT   // regular, as iOS's count
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        counts += Cell(c, run)
        line.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        })
        // The indent (sub-rows only), then the face slot — kept even without a face, so every
        // title at one level starts in one column. The face is centred on the first line.
        val fv: View = when {
            face == null -> View(ctx)
            face.iconRes != 0 -> ImageView(ctx).apply {
                setImageResource(face.iconRes)
                // Black whatever the drawable's own tint (Delete's trash tints with the theme).
                imageTintList = ColorStateList.valueOf(INK)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            else -> TextView(ctx).apply {
                text = face.glyph.orEmpty()
                textSize = GLYPH_SP
                setTextColor(INK)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
        }
        fv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        line.addView(fv, LinearLayout.LayoutParams(faceSize, faceSize).apply {
            gravity = Gravity.TOP
            topMargin = (rowMin - faceSize) / 2
            marginStart = dp(INDENT_DP) * level.coerceAtLeast(0)
            marginEnd = dp(FACE_GAP_DP)
        })
        line.addView(TextView(ctx).apply {
            text = title
            textSize = TEXT_SP
            setTextColor(INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (selected) {
                setTypeface(typeface, Typeface.BOLD)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            }
            // One line fills the row exactly; a second line grows it downward, so the first line —
            // and the face, count and chevron beside it — stays where a one-line row's is.
            val padV = ((rowMin - lineHeight) / 2).coerceAtLeast(dp(4))
            setPadding(0, padV, 0, padV)
            minHeight = rowMin
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.TOP
        })
        if (folder != null) {
            line.addView(ImageView(ctx).apply {
                setImageResource(if (folder) R.drawable.ic_pf_down else R.drawable.ic_pf_right)
                imageTintList = ColorStateList.valueOf(INK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val padV = (rowMin - chevronSize) / 2
                setPadding(dp(8), padV, dp(8) + dp(PAD_END_DP), padV)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(chevronSize + dp(16) + dp(PAD_END_DP), rowMin).apply {
                gravity = Gravity.TOP
            })
        }
        container.addView(line, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return line
    }

    /**
     * Size the count column, once per run, by the run's widest count — never narrower than one
     * digit ([digits]; the hidden "8888" trick: the digits are tabular, so the widest is the
     * longest). The pane's own rows share one width; a run (one category's sites, one account's
     * mailboxes) has its own, so a folder's big counts never push the rest of the pane sideways —
     * but a run is never narrower than the pane's own column, so a sub-row's title always stands
     * its indent right of its parent's, whatever the parent counts (review 2026-10-12: nothing
     * sits left of its parent).
     */
    fun finish() {
        if (counts.isEmpty()) return
        val gap = dp(COUNT_GAP_DP)
        fun width(cells: List<Cell>): Int {
            val paint = cells.first().view.paint
            return ceil(maxOf(
                paint.measureText("8".repeat(digits(cells.map { it.view.text.toString() }))),
                cells.maxOf { it.view.paint.measureText(it.view.text.toString()) })).toInt()
        }
        val runs = counts.groupBy { it.run }
        val paneWidth = runs[null]?.let { width(it) } ?: 0
        for ((run, cells) in runs) {
            val w = if (run == null) paneWidth else maxOf(width(cells), paneWidth)
            for (cell in cells) {
                val lp = cell.view.layoutParams as? LinearLayout.LayoutParams ?: continue
                lp.width = w
                lp.marginEnd = gap
                cell.view.layoutParams = lp
            }
        }
    }

    companion object {
        /** Every row's one size (sp). */
        const val TEXT_SP = 15f
        const val COUNT_SP = 13f
        const val HEADER_SP = 12f
        const val GLYPH_SP = 16f
        const val ROW_MIN_DP = 44
        const val PAD_START_DP = 10
        const val PAD_END_DP = 6
        const val FACE_DP = 20
        const val FACE_GAP_DP = 10
        const val COUNT_GAP_DP = 8
        const val INDENT_DP = 16
        const val CHEVRON_DP = 16
        /** The system text sizes the faces and rows follow, at the ends. */
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.6f
        /** Ink: black. Nothing in a room pane is drawn lighter than #444 (e-ink). */
        const val INK = 0xFF000000.toInt()
        /** The lightest the panes go: a search field's hint (the theme's is a light grey). */
        const val HINT = 0xFF444444.toInt()

        /** The count column's words (iOS `CountColumn.text`): blank at 0, the number up to 9999,
         *  then thousands as "12k" — four characters at most, so the column stays compact. */
        fun countText(n: Int): String = when {
            n <= 0 -> ""
            n < 10_000 -> n.toString()
            else -> "${n / 1000}k"
        }

        /** How many digits a run's column holds for these shown counts — the widest, 1…4 (iOS
         *  `CountColumn.digits`): one digit even when nothing is counted, so the column never
         *  appears or vanishes as folders open. */
        fun digits(shown: List<String>): Int = (shown.maxOfOrNull { it.length } ?: 0).coerceIn(1, 4)

        /** The faces of the doors the room panes carry — one per door, monochrome (iOS twin: the
         *  SF Symbol in each comment). */
        fun doorFace(door: IncomingDoors.Door): PanelFaces.Face = when (door) {
            IncomingDoors.Door.SAVE_FOR_LATER -> PanelFaces.Face(R.drawable.ic_pf_add)        // plus.circle
            IncomingDoors.Door.THE_NET -> PanelFaces.Face(R.drawable.ic_pf_address)           // globe
            IncomingDoors.Door.THE_MAIL -> PanelFaces.Face(R.drawable.ic_pf_mail2)            // tray.2
            IncomingDoors.Door.BLUESKY -> PanelFaces.Face(R.drawable.ic_pf_bluesky)           // (iOS: cloud)
            IncomingDoors.Door.READ -> PanelFaces.Face(R.drawable.ic_pf_book)                 // book
            IncomingDoors.Door.WATCH -> PanelFaces.Face(R.drawable.ic_pf_watch)               // tv
            IncomingDoors.Door.LISTEN -> PanelFaces.Face(R.drawable.ic_pf_headphones)         // headphones
            IncomingDoors.Door.LATER -> PanelFaces.Face(R.drawable.ic_pf_later)               // bookmark
            IncomingDoors.Door.STARRED -> PanelFaces.Face(R.drawable.ic_pf_star)              // star
            IncomingDoors.Door.ASKS -> PanelFaces.Face(R.drawable.ic_pf_asks)                 // bubble.left.and.bubble.right
            IncomingDoors.Door.SMART -> PanelFaces.Face(R.drawable.ic_pf_smart)               // number
            IncomingDoors.Door.LOCAL -> PanelFaces.Face(R.drawable.ic_pf_local)               // antenna.radiowaves.left.and.right
        }

        /** The mailboxes' faces (iOS `MailFolder` symbols: tray · star · paperplane · xmark.bin · trash). */
        fun boxFace(box: MailPlace.Box): PanelFaces.Face = when (box) {
            MailPlace.Box.INBOX -> PanelFaces.Face(R.drawable.ic_pf_mail)
            MailPlace.Box.STARRED -> PanelFaces.Face(R.drawable.ic_pf_star)
            MailPlace.Box.SENT -> PanelFaces.Face(R.drawable.ic_pf_sent)
            MailPlace.Box.JUNK -> PanelFaces.Face(R.drawable.ic_pf_junk)
            MailPlace.Box.TRASH -> PanelFaces.Face(R.drawable.ic_toolbar_trash)
        }

        /** A Later lane's face — the lens it files under; 📧 Email wears the envelope (iOS
         *  `FeedTreeInput.laneSymbol`), never The Mail's trays. */
        fun laneFace(lane: String): PanelFaces.Face = when (lane) {
            "read" -> doorFace(IncomingDoors.Door.READ)
            "watch" -> doorFace(IncomingDoors.Door.WATCH)
            "listen" -> doorFace(IncomingDoors.Door.LISTEN)
            else -> PanelFaces.Face(R.drawable.ic_pf_envelope)
        }

        /** The other faces the panes wear: an account, Contacts, the setup doors, the Log, the sort
         *  dial, the podcast finder, OPML in and out, the move up to the server. */
        val ACCOUNT = PanelFaces.Face(R.drawable.ic_pf_at)              // at
        val CONTACTS = PanelFaces.Face(R.drawable.ic_pf_contacts)       // person.crop.rectangle.stack
        val SETTINGS = PanelFaces.Face(R.drawable.ic_pf_settings)       // gearshape
        val ADD = PanelFaces.Face(R.drawable.ic_pf_add)                 // plus.circle
        val ADD_LINK = PanelFaces.Face(R.drawable.ic_pf_link_add)       // link.badge.plus
        /** The Log wears the books, as on iOS (FeedTree, MailTree and the Hub: books.vertical) —
         *  design v2 A7: the clock means History on iOS. The shelf's Folders seat wears them too,
         *  on both apps; the two never share a screen. */
        val LOG = PanelFaces.Face(R.drawable.ic_pf_books)               // books.vertical
        val SORT = PanelFaces.Face(R.drawable.ic_pf_sort)               // arrow.up.arrow.down
        val PODCASTS = PanelFaces.Face(R.drawable.ic_pf_podcast)        // mic
        val IMPORT = PanelFaces.Face(R.drawable.ic_pf_import)           // square.and.arrow.down
        val EXPORT = PanelFaces.Face(R.drawable.ic_pf_export)           // square.and.arrow.up
        val TO_SERVER = PanelFaces.Face(R.drawable.ic_pf_up)            // arrow.up
        val FEEDS = PanelFaces.Face(R.drawable.ic_pf_feeds)             // RSSGlyph
    }
}
