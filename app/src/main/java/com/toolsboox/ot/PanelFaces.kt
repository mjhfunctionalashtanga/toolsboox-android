package com.toolsboox.ot

import com.toolsboox.R

/**
 * The Android face of every [PanelSeats.Seat] — one glyph per meaning, monochrome only (Michael
 * 2026-10-11, the Net field report: "Icons lack design language"). A face is a vector drawable, or
 * a text glyph from [PanelSeats.MONO_GLYPHS] (‹ › ← ✕ ◈ ❖ @ ✉ ↥ ⟳ draw black in every font).
 * The iOS twin wears the SF Symbol named beside each in the spec's §3 vocabulary table.
 *
 * ONE FAMILY (review 2026-10-11, F5): every drawable face is an `ic_pf_*` outline on the ink tools'
 * own geometry — the 256 grid and 16-unit round strokes of Pen, Eraser, Lasso, Undo, Redo, Side, ⛶
 * and Delete — so a column no longer mixes solid Material shapes with thin outlines. A filled face
 * means ON (Star's fill), as on iOS (star → star.fill); everything at rest is an outline.
 *
 * Null = no Android face drawn yet: the seat belongs to a surface that moves onto the panel
 * language in a later phase (Move back, To synth, Thread, Rhizome — his spiderweb — Sweep, Read
 * as), or to iOS alone (Input, Your day, By hand, Order, ‹ Line, Line ›, Back to).
 */
object PanelFaces {

    /** [iconRes] a drawable, or [glyph] a whitelisted text face (then [iconRes] is 0). */
    data class Face(val iconRes: Int = 0, val glyph: String? = null)

    fun face(seat: PanelSeats.Seat): Face? = when (seat) {
        PanelSeats.Seat.FULL -> Face(R.drawable.ic_fullscreen)
        PanelSeats.Seat.HUB -> Face(R.drawable.ic_pf_hub)
        PanelSeats.Seat.TODAY -> Face(R.drawable.ic_pf_today)
        PanelSeats.Seat.MORE -> Face(R.drawable.ic_pf_more)
        PanelSeats.Seat.FEEDS -> Face(R.drawable.ic_pf_feeds)
        PanelSeats.Seat.NIB -> Face(R.drawable.ic_pf_nib)

        PanelSeats.Seat.PAGE_UP -> Face(R.drawable.ic_pf_up)
        PanelSeats.Seat.PAGE_DOWN -> Face(R.drawable.ic_pf_down)
        PanelSeats.Seat.PAGE_BACK -> Face(R.drawable.ic_pf_left)
        PanelSeats.Seat.PAGE_FORWARD -> Face(R.drawable.ic_pf_right)
        // « » drawn in the family's own stroke (iOS chevron.left.2 / .right.2) rather than set in a
        // font, whose weight no other face shares (review 2026-10-11, F5).
        PanelSeats.Seat.PREVIOUS -> Face(R.drawable.ic_pf_previous)
        PanelSeats.Seat.NEXT -> Face(R.drawable.ic_pf_next)
        PanelSeats.Seat.BACK -> Face(glyph = "←")
        PanelSeats.Seat.REFRESH -> Face(R.drawable.ic_pf_refresh)

        PanelSeats.Seat.PEN -> Face(R.drawable.ic_toolbar_pen)
        PanelSeats.Seat.ERASER -> Face(R.drawable.ic_toolbar_eraser)
        PanelSeats.Seat.LASSO -> Face(R.drawable.ic_toolbar_lasso)
        PanelSeats.Seat.UNDO -> Face(R.drawable.ic_toolbar_undo)
        PanelSeats.Seat.REDO -> Face(R.drawable.ic_toolbar_redo)
        // Zoom: the table's face is the box's, the first tap's — a marquee's corners and a lens (iOS
        // viewfinder; Michael 2026-10-12) — never Search's bare magnifier, never ⛶'s brackets. The
        // seat wears its last choice ([zoomFace]).
        PanelSeats.Seat.ZOOM -> Face(R.drawable.ic_pf_zoom)

        // Up/Down share Page ↑/↓'s faces on purpose ("up or down through what's in front of you");
        // no screen carries both.
        PanelSeats.Seat.UP -> Face(R.drawable.ic_pf_up)
        PanelSeats.Seat.DOWN -> Face(R.drawable.ic_pf_down)
        PanelSeats.Seat.NEW -> Face(R.drawable.ic_pf_add)
        PanelSeats.Seat.SECTIONS -> Face(R.drawable.ic_pf_sections)

        // The Mail's drawer seat: the room's two trays (iOS PanelSeat.mail, tray.2) — the face its
        // door wears in the Feeds pane; one tray is an Inbox (review 2026-10-12: one face, one meaning).
        PanelSeats.Seat.MAIL -> Face(R.drawable.ic_pf_mail2)
        PanelSeats.Seat.BOOK -> Face(R.drawable.ic_pf_book)
        PanelSeats.Seat.NOTES -> Face(R.drawable.ic_pf_book_notes)
        // Folders: the shelf's folder index — books standing on a shelf (iOS books.vertical); the
        // file-cabinet emoji retires (Michael 2026-10-11: monochrome faces only).
        PanelSeats.Seat.FOLDERS -> Face(R.drawable.ic_pf_books)
        PanelSeats.Seat.ADDRESS -> Face(R.drawable.ic_pf_address)
        PanelSeats.Seat.PILES -> Face(R.drawable.ic_pf_piles)

        PanelSeats.Seat.SEARCH -> Face(R.drawable.ic_pf_search)
        PanelSeats.Seat.SHOW -> Face(R.drawable.ic_pf_show)
        PanelSeats.Seat.COMPOSE -> Face(glyph = "✉")
        // Add shares New's ＋ face; no screen carries both.
        PanelSeats.Seat.ADD -> Face(R.drawable.ic_pf_add)
        // Continue: where your hand left off — play/pause (iOS playpause); ▶ now means Play only.
        PanelSeats.Seat.CONTINUE -> Face(R.drawable.ic_pf_continue)

        PanelSeats.Seat.STAR -> Face(R.drawable.ic_pf_star)
        PanelSeats.Seat.BOOKMARK -> Face(R.drawable.ic_pf_flag)
        PanelSeats.Seat.NOTE -> Face(R.drawable.ic_pf_note)
        PanelSeats.Seat.FIND -> Face(R.drawable.ic_pf_find)
        PanelSeats.Seat.READER -> Face(R.drawable.ic_pf_reader)
        PanelSeats.Seat.TYPE -> Face(R.drawable.ic_pf_type)
        PanelSeats.Seat.LISTEN -> Face(R.drawable.ic_pf_listen)
        PanelSeats.Seat.MARK -> Face(R.drawable.ic_pf_mark)
        PanelSeats.Seat.ANNOTATE -> Face(R.drawable.ic_pf_annotate)
        PanelSeats.Seat.PICTURES -> Face(R.drawable.ic_pf_pictures)

        PanelSeats.Seat.REPLY -> Face(R.drawable.ic_pf_reply)
        PanelSeats.Seat.DELETE -> Face(R.drawable.ic_toolbar_trash)
        PanelSeats.Seat.SYNTH -> Face(glyph = "❖")
        PanelSeats.Seat.LATER -> Face(R.drawable.ic_pf_later)
        PanelSeats.Seat.TAG -> Face(R.drawable.ic_pf_tag)
        PanelSeats.Seat.PICK -> Face(glyph = "◈")
        PanelSeats.Seat.LINKS -> Face(R.drawable.ic_pf_link)
        PanelSeats.Seat.ACCOUNTS -> Face(glyph = "@")

        PanelSeats.Seat.SIDE -> Face(R.drawable.ic_toolbar_switch_side)
        PanelSeats.Seat.CLOSE -> Face(glyph = "✕")

        PanelSeats.Seat.RESTORE, PanelSeats.Seat.TO_SYNTH, PanelSeats.Seat.THREAD,
        PanelSeats.Seat.RHIZOME, PanelSeats.Seat.SWEEP, PanelSeats.Seat.READ_AS,
        PanelSeats.Seat.INPUT, PanelSeats.Seat.YOUR_DAY, PanelSeats.Seat.BY_HAND,
        PanelSeats.Seat.ORDER, PanelSeats.Seat.LINE_BACK, PanelSeats.Seat.LINE_FORWARD,
        PanelSeats.Seat.BACK_TO -> null
    }

    /**
     * The Zoom seat's face for its last choice (Michael 2026-10-12: "We can make the zoom button the
     * last used option … Or zoom to box :)") — the seat and its full-screen cell say what the tap
     * will do: the marquee for Zoom box, the magnifier with ＋ or − for a step (iOS viewfinder /
     * plus.magnifyingglass / minus.magnifyingglass, ZoomBox.Choice.glyph).
     */
    fun zoomFace(choice: MarqueeZoom.Choice): Int = when (choice) {
        MarqueeZoom.Choice.BOX -> R.drawable.ic_pf_zoom
        MarqueeZoom.Choice.IN -> R.drawable.ic_pf_zoom_in
        MarqueeZoom.Choice.OUT -> R.drawable.ic_pf_zoom_out
    }

    /**
     * [seat] as a rail item: its face, its caption, and the surface's [action]. [name] is the hold
     * toast / screen-reader name — the surface's own exact words where it has them ("Previous
     * article"), else the vocabulary's. [longPress] is the seat's second act, if it has one.
     * [on] boxes the seat while what it toggles is on (a drawer open, a search summoned, Reader
     * view). [iconRes] swaps the face for its state where the face IS the state (Star's fill).
     */
    fun item(
        seat: PanelSeats.Seat,
        name: String = seat.voice,
        longPress: (() -> Unit)? = null,
        activeId: String? = null,
        caption: String = seat.caption,
        on: Boolean = false,
        iconRes: Int? = null,
        action: () -> Unit,
    ): TuckPanel.Item {
        val f = requireNotNull(face(seat)) { "${seat.id} has no Android face yet" }
        return TuckPanel.Item(iconRes ?: f.iconRes, name, activeId = activeId, longPress = longPress,
            glyph = if (iconRes != null) null else f.glyph, caption = caption, on = on, action = action)
    }
}
