package com.toolsboox.plugin.feeds.ui

/**
 * ONE INCOMING RAIL — the seats Incoming's column wears, in the one order, for RSS and The Mail
 * alike (Michael 2026-10-11, item 1 of the Incoming plan: "the rail changes shape between RSS and
 * Mail"). RSS and Mail used to build two different columns, so the same verb sat in a different
 * place depending on which lens you stood in, and « » sat at the bottom in Mail against his 09-11
 * rule ("article next / article back … near the top middle").
 *
 * The rule: the SHARED seats come first, always in this order; then the seats of the moment (a
 * list's 🔍 · 👁, or a piece's ★ · ✎ · 🔎 · Reader · Listen); then the lens's own seats, appended.
 * Nothing is removed — every verb either rail carried has a seat here (the From→To table in the
 * plan). FeedsFragment maps each [Seat] to its button; the player's transport, when something is
 * playing, rides ahead of all of it.
 *
 * Pure, so IncomingRailTest can hold the order and the counts.
 *
 * TWO ROOMS, ONE RAIL (Michael 2026-10-11: "You can move mail ... out of incoming"): Feeds and
 * The Mail are separate rooms now, and each wears these same seats in this same order — [mail]
 * says which room, not which lens. The room's drawer seat ("Feeds" in Feeds, "Mail" in The Mail)
 * led them until Michael 2026-10-12 ("Those too."): the drawer is the left edge's first Back now
 * ([com.toolsboox.ot.EdgeLadder]) and More's second row, under ☰ Hub.
 *
 * ONE PANEL LANGUAGE (Michael 2026-10-11): the Feeds room has moved onto
 * [com.toolsboox.ot.PanelSeats] (the FEED_LIST and ARTICLE dresses, the same column as iOS); The
 * Mail still wears this table until its own step moves it onto the MAIL_LIST and LETTER dresses.
 */
object IncomingRail {

    enum class Seat {
        // Shared — every dress, this order. (The room's drawer seat, FEEDS, left 2026-10-12: the
        // left edge's first Back opens the drawer, and More carries it as its second row.)
        TODAY, PAGE_UP, PAGE_DOWN, PREV, NEXT, REFRESH, MORE,
        // On a list.
        SEARCH, SHOW,
        // While reading.
        STAR, NOTE, FIND, READER, TYPE, LISTEN,
        // The Mail's list. (✍ Letter folded into ✉ Compose with the one compose screen.)
        SWEEP, COMPOSE, ACCOUNTS,
        // A letter. (⤴ RESTORE stands in DELETE's seat inside 🗑 Trash — Move back.) ● UNREAD
        // (Read / Unread) took 🕸 Rhizome's seat (Michael 2026-10-11: "Rhizome still doesn't sound
        // useful" → "Ok" — Rhizome leaves mail only; tasks, contacts, the Map and Missed Rhizomes
        // keep it).
        REPLY, TAG, PICK, TO_SYNTH, THREAD, LINKS, UNREAD, DELETE, RESTORE,
        // An article.
        LATER, SYNTH,
    }

    private val SHARED = listOf(
        Seat.TODAY, Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREV, Seat.NEXT, Seat.REFRESH, Seat.MORE,
    )
    private val READING = listOf(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.LISTEN)
    private val LETTER_SEATS = listOf(
        Seat.REPLY, Seat.TAG, Seat.PICK, Seat.TO_SYNTH, Seat.THREAD, Seat.LINKS, Seat.UNREAD, Seat.DELETE,
    )
    private val ARTICLE_SEATS = listOf(Seat.LATER, Seat.SYNTH)

    /**
     * The seats for one moment of the screen.
     *  - [mail]: The Mail lens (else RSS and its sibling corpora).
     *  - [reading]: a piece is open in the pane.
     *  - [showScope]: the list's view is one 👁 can cycle (Unread / Read / All).
     *  - [canSweep]: 🧹 means something here — never over the kept pile, Sent or Trash.
     *  - [trash]: the OPEN LETTER is in 🗑 Trash (cleared on this device, wherever it was opened
     *    from) — its 🗑 seat becomes ⤴ Move back (Michael 2026-10-11, "accounts, starred, inbox,
     *    sent, trash"); a cleared letter can't be cleared again.
     *  - [junk]: the OPEN LETTER was read out of an account's 🚫 Junk folder (Michael 2026-10-11:
     *    "individual sents, junks, trash") — read-only, so no 🗑 seat: nothing here can delete it
     *    on the server, and clearing it locally would file it in a Trash it never reaches.
     *  - [sent]: the OPEN LETTER is one you sent, which always reads as read.
     *
     * ● Read / Unread stands only where it can act (review 2026-10-11, iOS's mail swipe): never on
     * a letter you sent (never unread), in 🗑 Trash (whose one verb is ⤴ Move back) or in 🚫 Junk
     * (read-only — iOS offers it nothing).
     */
    fun seats(
        mail: Boolean, reading: Boolean, showScope: Boolean, canSweep: Boolean,
        trash: Boolean = false, junk: Boolean = false, sent: Boolean = false,
    ): List<Seat> {
        // Aa Type rides beside Reader view on an article (Aa Type merge, 2026-10-11); a letter
        // keeps its own dress (As sent / Reader), so the seat stays off The Mail.
        val reads = if (mail) READING else READING.flatMap { if (it == Seat.READER) listOf(it, Seat.TYPE) else listOf(it) }
        val moment = if (reading) reads else listOfNotNull(Seat.SEARCH, Seat.SHOW.takeIf { showScope })
        val letter = if (sent || trash || junk) LETTER_SEATS - Seat.UNREAD else LETTER_SEATS
        val lens = when {
            mail && reading && junk -> letter - Seat.DELETE
            mail && reading && trash -> letter.map { if (it == Seat.DELETE) Seat.RESTORE else it }
            mail && reading -> letter
            // One ✉ Compose (Michael 2026-10-11, Q2): the one compose screen takes the pen on its
            // pad and a finger on its body, so ✍ Letter's seat folded into it.
            // ❖ over the list (synth-list-links §7, iOS UnifiedInboxView's "Synthesize this list").
            mail -> listOfNotNull(Seat.SWEEP.takeIf { canSweep }, Seat.COMPOSE, Seat.SYNTH, Seat.ACCOUNTS)
            reading -> ARTICLE_SEATS
            else -> emptyList()
        }
        return SHARED + moment + lens
    }
}
