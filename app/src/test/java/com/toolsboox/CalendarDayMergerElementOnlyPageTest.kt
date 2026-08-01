package com.toolsboox

import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PARITY PIN, not an endorsement — the element-only-page edge of the deleted-page rule
 * (`AUDIT-2026-07-31.md`: "deleting the last card off a one-sided page can drop the page key —
 * the `erasedPages` arm fires with zero strokes; identical on both forks, untested on both").
 *
 * A page whose only content was ever elements has no strokes for guard 3's stroke half to weigh:
 * when its last card is tombstoned and the key sits on one side only, the `erasedPages` arm of
 * [CalendarDayMerger.isTombstonedPage] fires and the key goes — even though nothing distinguishes
 * "the page was deleted" from "the page's one card was deleted and the page was meant to stay".
 * Both forks do exactly this today, so convergence holds; this test exists so that any future
 * change to the edge is a DECISION (made on both forks together) rather than a drift.
 */
class CalendarDayMergerElementOnlyPageTest {

    private val pageKey = "jot-1"

    private fun day(updated: Long) = CalendarDay(
        year = 2026, month = 7, day = 31, locale = Locale.US, startHour = null,
        updated = Date(updated)
    )

    private fun card(id: UUID) = TextElement(
        elementId = id, x = 10f, y = 10f, text = "the last card", pageKey = pageKey
    )

    /**
     * The edge itself: key on ONE side with zero strokes, its only card tombstoned (the other
     * side still carries the card, which is how `erasedPages` learns the page had one) → the
     * key is dropped. Current behavior on both forks; pinned as-is.
     */
    @Test
    fun oneSidedElementOnlyPageKey_dropsWhenItsLastCardIsTombstoned() {
        val cardId = UUID.randomUUID()

        // Side A deleted the card: element gone from its list, id tombstoned, key still held
        // (the user deleted a card, not the page) — but with no strokes, ever.
        val a = day(updated = 2_000L).apply {
            noteStrokes[pageKey] = emptyList()
            deletedElementIds.add(cardId.toString())
        }
        // Side B has not seen the deletion: it still carries the card, and never had the key.
        val b = day(updated = 1_000L).apply {
            textElements.add(card(cardId))
        }

        val merged = CalendarDayMerger.merge(a, b)

        assertTrue("the tombstoned card must not survive", merged.textElements.isEmpty())
        assertFalse(
            "PARITY PIN: the one-sided element-only page key is dropped today (erasedPages arm, zero strokes)",
            merged.noteStrokes.containsKey(pageKey)
        )
        // Same answer from either direction — the rule reads sides symmetrically.
        assertEquals(merged.noteStrokes.keys, CalendarDayMerger.merge(b, a).noteStrokes.keys)
    }

    /**
     * The boundary the edge sits against: the SAME deletion evidence with the key on BOTH sides
     * keeps the key (guard 1 — two sides holding the key is an erase, not a page deletion). If
     * either this or the test above flips, the rule's shape has changed.
     */
    @Test
    fun sameEvidenceOnBothSides_keepsTheKey() {
        val cardId = UUID.randomUUID()

        val a = day(updated = 2_000L).apply {
            noteStrokes[pageKey] = emptyList()
            deletedElementIds.add(cardId.toString())
        }
        val b = day(updated = 1_000L).apply {
            noteStrokes[pageKey] = emptyList()
            textElements.add(card(cardId))
        }

        val merged = CalendarDayMerger.merge(a, b)

        assertTrue("the tombstoned card must not survive", merged.textElements.isEmpty())
        assertEquals(
            "a key held on both sides stays, as the empty page it now is",
            emptyList<com.toolsboox.da.Stroke>(), merged.noteStrokes[pageKey]
        )
    }
}
