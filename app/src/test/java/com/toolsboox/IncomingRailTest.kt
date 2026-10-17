package com.toolsboox

import com.toolsboox.plugin.feeds.ui.IncomingRail
import com.toolsboox.plugin.feeds.ui.IncomingRail.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One Incoming rail (Michael 2026-10-11): the shared seats lead in one order in every dress,
 * then the moment's seats, then the lens's own — and nothing either old rail carried is lost.
 * The counts are the plan's "After" column less the frame's three (Hub, Other side, Tuck); The
 * Mail's list landed on the plan's 16 once the one compose screen folded ✍ into ✉. Since Michael
 * 2026-10-12 ("Those too.") Hub and the room's drawer seat are off the column — the left edge's
 * Back ladder and More's first two rows have them — so each count is two lower, frame two.
 */
class IncomingRailTest {

    private val shared = listOf(
        Seat.TODAY, Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREV, Seat.NEXT, Seat.REFRESH, Seat.MORE,
    )

    @Test
    fun `every dress opens with the shared seats in the one order`() {
        for (mail in listOf(false, true)) for (reading in listOf(false, true))
            for (scope in listOf(false, true)) for (sweep in listOf(false, true))
                assertEquals(shared, IncomingRail.seats(mail, reading, scope, sweep).take(shared.size))
    }

    @Test
    fun `RSS list gains search and show`() {
        val s = IncomingRail.seats(mail = false, reading = false, showScope = true, canSweep = true)
        assertEquals(shared + listOf(Seat.SEARCH, Seat.SHOW), s)
        assertEquals(11, s.size + 2)
    }

    @Test
    fun `Mail list keeps every verb, prev and next up top`() {
        val s = IncomingRail.seats(mail = true, reading = false, showScope = true, canSweep = true)
        assertEquals(shared + listOf(Seat.SEARCH, Seat.SHOW, Seat.SWEEP, Seat.COMPOSE, Seat.SYNTH, Seat.ACCOUNTS), s)
        assertEquals(15, s.size + 2)
        // No sweep over the kept pile or Sent.
        assertFalse(Seat.SWEEP in IncomingRail.seats(mail = true, reading = false, showScope = false, canSweep = false))
    }

    @Test
    fun `an article reads then acts`() {
        val s = IncomingRail.seats(mail = false, reading = true, showScope = true, canSweep = true)
        assertEquals(
            shared + listOf(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.TYPE, Seat.LISTEN, Seat.LATER, Seat.SYNTH), s)
        assertEquals(17, s.size + 2)
    }

    @Test
    fun `a letter reads like an article, then its own verbs`() {
        val s = IncomingRail.seats(mail = true, reading = true, showScope = true, canSweep = true)
        assertEquals(
            shared + listOf(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.LISTEN,
                Seat.REPLY, Seat.TAG, Seat.PICK, Seat.TO_SYNTH, Seat.THREAD, Seat.LINKS, Seat.UNREAD, Seat.DELETE), s)
        assertEquals(22, s.size + 2)
    }

    @Test
    fun `a letter in Trash moves back where Delete stood`() {
        // Michael 2026-10-11: The Mail's rows are "accounts, starred, inbox, sent, trash".
        val s = IncomingRail.seats(mail = true, reading = true, showScope = true, canSweep = false, trash = true)
        // No Read / Unread in Trash: Move back is its one verb (iOS's mail swipe, review 2026-10-11).
        assertEquals(
            shared + listOf(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READER, Seat.LISTEN,
                Seat.REPLY, Seat.TAG, Seat.PICK, Seat.TO_SYNTH, Seat.THREAD, Seat.LINKS, Seat.RESTORE), s)
        // The Trash list keeps compose, ❖ and accounts, with no sweep (it is already swept).
        assertEquals(shared + listOf(Seat.SEARCH, Seat.COMPOSE, Seat.SYNTH, Seat.ACCOUNTS),
            IncomingRail.seats(mail = true, reading = false, showScope = false, canSweep = false, trash = true))
        // Outside The Mail the flag means nothing.
        assertEquals(IncomingRail.seats(false, true, true, true), IncomingRail.seats(false, true, true, true, trash = true))
    }

    @Test
    fun `a Junk letter is read-only - no Delete or Read-Unread seat, every other letter verb kept`() {
        val letter = IncomingRail.seats(mail = true, reading = true, showScope = false, canSweep = false)
        val junk = IncomingRail.seats(mail = true, reading = true, showScope = false, canSweep = false, junk = true)
        // iOS's Junk offers nothing to mark (review 2026-10-11).
        assertEquals(letter - Seat.DELETE - Seat.UNREAD, junk)
        assertFalse(Seat.RESTORE in junk)
        // The list over Junk is the piles' list: no sweep (❖ over it, as over every mail list).
        assertEquals(shared + listOf(Seat.SEARCH, Seat.COMPOSE, Seat.SYNTH, Seat.ACCOUNTS),
            IncomingRail.seats(mail = true, reading = false, showScope = false, canSweep = false, junk = true))
    }

    @Test
    fun `Rhizome is off the letter, Read-Unread stands in its seat`() {
        // Michael 2026-10-11: Rhizome leaves mail only ("Rhizome still doesn't sound useful" →
        // "Ok"); the letter gains ✉ Read / Unread where 🕸 stood, as the iOS mail row's swipe does.
        assertFalse(Seat.entries.any { it.name == "RHIZOME" })
        val letter = IncomingRail.seats(mail = true, reading = true, showScope = true, canSweep = true)
        assertEquals(letter.indexOf(Seat.LINKS) + 1, letter.indexOf(Seat.UNREAD))
        assertFalse(Seat.UNREAD in IncomingRail.seats(mail = false, reading = true, showScope = true, canSweep = true))
    }

    @Test
    fun `a letter you sent has no Read-Unread seat`() {
        // It always reads as read: a seat offering "Mark unread" could only apologize (review
        // 2026-10-11) — the row's hold already left it off.
        val sent = IncomingRail.seats(mail = true, reading = true, showScope = false, canSweep = false, sent = true)
        val letter = IncomingRail.seats(mail = true, reading = true, showScope = false, canSweep = false)
        assertEquals(letter - Seat.UNREAD, sent)
    }

    @Test
    fun `no seat is lost across the dresses`() {
        val all = listOf(false, true).flatMap { mail -> listOf(false, true).flatMap { reading ->
            listOf(false, true).flatMap { trash ->
                IncomingRail.seats(mail, reading, showScope = true, canSweep = true, trash = trash) } } }.toSet()
        assertEquals(Seat.entries.toSet(), all)
        // And none appears twice in one dress.
        for (mail in listOf(false, true)) for (reading in listOf(false, true)) {
            val s = IncomingRail.seats(mail, reading, showScope = true, canSweep = true)
            assertTrue(s.size == s.toSet().size)
        }
    }
}
