package com.toolsboox

import com.toolsboox.plugin.calendar.ot.LedgerDocuments
import com.toolsboox.plugin.calendar.ot.SynthPageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line this debloat is not allowed to cross: **retire the surface, never the data.**
 *
 * Synthesize lost its hub row, its place in the ritual walk and its "New synthesis…" picker in
 * August 2026. It did NOT lose its pages — Michael has synthesis ink and four synth grams — and the
 * promise made when the surface went was that every one of them still opens: from the root
 * Directory, from the day chip, and from any `ledger://<date>/synthesize` address a gram or a link
 * already carries.
 *
 * That promise rests on three facts that a future tidy-up could quietly break while everything still
 * compiles, because nothing else in the app would fail loudly if they went. So they are pinned here:
 *
 *  1. `synthesize` still RESOLVES to a document surface, which is what makes the day chip appear on
 *     one of those pages and what the whole naming/paging/renaming machinery hangs off.
 *  2. The named topic pages (`synthesize-<millis>`) resolve to the same surface, so a named synthesis
 *     is not a stranger to its own kind.
 *  3. The surface still has a glyph, a label and a plural — the vocabulary the root Directory prints
 *     beside the count. A kind with no name renders as a blank row you cannot identify.
 *
 * The nav side of the promise is one line in `CalendarDayFragment.onImageSource`: a `ledger://` link
 * is split into a date and a page key and handed to `CalendarNavigator.toDayNote` with no allowlist
 * anywhere in the path — no page key can be "retired" out of being openable. That is deliberately
 * NOT mocked here (it needs a fragment and a nav controller); it is stated so the next reader knows
 * where to look before deciding a key can be deleted.
 */
class RetiredSurfaceReachabilityTest {

    @Test
    fun `the daily synthesis page still belongs to a document surface`() {
        assertEquals(
            "the bare 'synthesize' key must still resolve, or existing daily syntheses lose their chip",
            LedgerDocuments.SYNTHESIZE,
            LedgerDocuments.surfaceOf(SynthPageStore.DEFAULT_KEY)
        )
    }

    @Test
    fun `a named synthesis topic still belongs to the same surface`() {
        val named = "synthesize-1753900000000"
        assertTrue("the store must still recognise its own minted keys", SynthPageStore.isSynth(named))
        assertEquals(LedgerDocuments.SYNTHESIZE, LedgerDocuments.surfaceOf(named))
        // A sub-page folds onto its base, the way every other multi-page surface does.
        assertEquals(LedgerDocuments.SYNTHESIZE, LedgerDocuments.surfaceOf("$named#2"))
    }

    @Test
    fun `the retired surface keeps the vocabulary the directory prints`() {
        assertEquals("🔬", LedgerDocuments.glyph(LedgerDocuments.SYNTHESIZE))
        assertEquals("Synthesize", LedgerDocuments.label(LedgerDocuments.SYNTHESIZE))
        assertNotNull(LedgerDocuments.noun(LedgerDocuments.SYNTHESIZE))
        // Renaming a named synthesis has to keep working: the directory offers it, and a document you
        // can list but cannot re-title is a read-only shelf.
        assertTrue(LedgerDocuments.canRename(LedgerDocuments.SYNTHESIZE, "synthesize-1753900000000"))
    }

    /**
     * The surfaces that really are gone owned no data to lose — Roots, Seeds and Sprouts were all
     * derived from the tag index and the connection graph. This is the guard that the DERIVED half
     * of that claim is still true of what remains: the two stores their material actually lived in
     * are untouched, so nothing that fed them has been deleted along with them.
     */
    @Test
    fun `the material the retired garden read from is still a first-class kind`() {
        // Pickings — where a gram becomes something, and the surface Michael uses second-most. It was
        // never at risk, and that is the point: the sieve survived the garden built on top of it.
        assertEquals(LedgerDocuments.PICKINGS, LedgerDocuments.surfaceOf("pickings"))
        assertEquals(LedgerDocuments.WRITE, LedgerDocuments.surfaceOf("write"))
        assertEquals(LedgerDocuments.GRID, LedgerDocuments.surfaceOf("grid"))
        assertEquals(LedgerDocuments.JOT, LedgerDocuments.surfaceOf("sketch"))
    }
}
