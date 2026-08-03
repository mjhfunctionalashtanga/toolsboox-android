package com.toolsboox

import com.toolsboox.plugin.calendar.ot.GridPageStore
import com.toolsboox.plugin.calendar.ot.JotPageStore
import com.toolsboox.plugin.calendar.ot.LedgerDocuments
import com.toolsboox.plugin.calendar.ot.PickingsStore
import com.toolsboox.plugin.calendar.ot.SynthPageStore
import com.toolsboox.plugin.calendar.ot.WritePageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── ✍ WRITE, RETIRED AS A HUB ROW — AND ITS DATA, NOT RETIRED AT ALL ──────────────────────
    //
    // August 2026: Write became the LINES TEMPLATE. The "✍ Write" row left the hub's Notes folder
    // and `showWritePicker` went with it, in the same change that made Lines real — never before,
    // which was the condition on the whole slice.
    //
    // NOTHING ABOUT WRITE'S STORAGE MOVED, and that is the claim these tests exist to keep true. The
    // store is still WritePageStore, the index is still `write-index/pages.json`, the daily key is
    // still "write" and named pieces are still "write-<millis>". Michael has five days of daily ink
    // and a shelf of named pieces addressed by those keys, from the Directory, from the day chip,
    // from `ledger://<date>/write-<millis>` links inside grams, and from the iPad reading the same
    // WebDAV account. A later tidy-up that decided the retired ROW meant the retired KEYS could go
    // would compile perfectly and lose all of it, so the keys are pinned here the way Synthesize's
    // are — same file, same reason.

    @Test
    fun `the daily Write page still resolves, under its own key`() {
        assertEquals("write", WritePageStore.DEFAULT_KEY)
        assertTrue(WritePageStore.isWrite("write"))
        assertEquals(LedgerDocuments.WRITE, LedgerDocuments.surfaceOf("write"))
        // The sub-page tail folds onto the base, so "write#1" is page two of the same document.
        assertEquals(LedgerDocuments.WRITE, LedgerDocuments.surfaceOf("write#1"))
    }

    @Test
    fun `a named Write piece still resolves, keys unchanged`() {
        // A real key shape off Michael's device — minted epoch millis, exactly as iOS mints them.
        val named = "write-1785354515034"
        assertTrue("the store must still recognise its own minted keys", WritePageStore.isWrite(named))
        assertEquals(LedgerDocuments.WRITE, LedgerDocuments.surfaceOf(named))
        assertEquals(LedgerDocuments.WRITE, LedgerDocuments.surfaceOf("$named#3"))
        // Still nameable and still deletable — a shelf you can list but not re-title is read-only.
        assertTrue(LedgerDocuments.canRename(LedgerDocuments.WRITE, named))
        assertTrue(LedgerDocuments.canDelete(LedgerDocuments.WRITE))
    }

    @Test
    fun `the index directories are the wire and have not moved`() {
        // These four strings are the contract with iOS, which reads the same paths off the same
        // WebDAV account. Renaming a template must never rename a directory.
        assertEquals("write-index", WritePageStore.DIR)
        assertEquals("grid-index", GridPageStore.dir)
        assertEquals("sketch-index", JotPageStore.dir)
        assertEquals("grid", GridPageStore.defaultKey)
        assertEquals("sketch", JotPageStore.defaultKey)
        assertEquals("pickings", PickingsStore.DEFAULT_KEY)
    }

    // ── THE FIVE TEMPLATES ────────────────────────────────────────────────────────────────────

    @Test
    fun `the five templates are Michael's five, in Michael's order`() {
        assertEquals(
            listOf(
                LedgerDocuments.PICKINGS,   // Pickings
                LedgerDocuments.JOT,        // Jots
                LedgerDocuments.WRITE,      // Lines
                LedgerDocuments.GRID,       // Grid
                LedgerDocuments.TEXT_NOTES, // Text
            ),
            LedgerDocuments.TEMPLATES
        )
        assertEquals(
            listOf("Pickings", "Jots", "Lines", "Grid", "Text"),
            LedgerDocuments.TEMPLATES.map { LedgerDocuments.label(it) }
        )
    }

    @Test
    fun `a template id is a page key family, not a label`() {
        // The whole reason this change touches no storage: the id IS the key prefix on disk. Jots
        // is the one that says it out loud — the label and the key disagree, and must keep doing so.
        assertEquals("sketch", LedgerDocuments.JOT)
        assertEquals("Jots", LedgerDocuments.label(LedgerDocuments.JOT))
        assertEquals("write", LedgerDocuments.WRITE)
        assertEquals("Lines", LedgerDocuments.label(LedgerDocuments.WRITE))
    }

    @Test
    fun `templateOf answers for the five and declines the retired surface`() {
        assertEquals(LedgerDocuments.WRITE, LedgerDocuments.templateOf("write-1785354515034#1"))
        assertEquals(LedgerDocuments.GRID, LedgerDocuments.templateOf("grid#1"))
        assertEquals(LedgerDocuments.JOT, LedgerDocuments.templateOf("sketch"))
        assertEquals(LedgerDocuments.PICKINGS, LedgerDocuments.templateOf("pickings-1785354515034"))
        // Synthesize is a retired SURFACE, not a template: its pages must still resolve…
        assertEquals(LedgerDocuments.SYNTHESIZE, LedgerDocuments.surfaceOf("synthesize"))
        // …and must never be offered as something to start a new note as.
        assertNull(LedgerDocuments.templateOf("synthesize"))
        assertNull(LedgerDocuments.templateOf("grampicks"))
        assertNull(LedgerDocuments.templateOf(null))
    }

    @Test
    fun `every template can be started programmatically, so Ask can fill one`() {
        // The seam, pinned: whatever mints a note — the picker, the page's template switch, Ask
        // writing a synthesis into a Text note or a harvest into a Pickings page — goes through
        // one call that knows all five. This asserts the CONTRACT (every template is accepted and
        // Text is the one whose key is not a page key) without touching a Context: the store calls
        // need Android, the shape does not, and the shape is what a later refactor would break.
        for (template in LedgerDocuments.TEMPLATES) assertTrue(LedgerDocuments.isTemplate(template))
        assertTrue(
            "Text's key is a note id, so callers must be able to tell it apart",
            LedgerDocuments.NewNote(LedgerDocuments.TEXT_NOTES, "tn-1", java.time.LocalDate.now(), "x")
                .isTextNote
        )
        assertTrue(
            LedgerDocuments.TEMPLATES.filterNot { it == LedgerDocuments.TEXT_NOTES }.all {
                !LedgerDocuments.NewNote(it, "k", java.time.LocalDate.now(), "x").isTextNote
            }
        )
    }

    /**
     * The real keys, off Michael's own device, opening on the right template.
     *
     * The brief named these six explicitly and they are here verbatim rather than paraphrased,
     * because the promise being kept is about HIS pages and not about a key shape in the abstract.
     * `CalendarDayPageNotes.drawPage` switches on exactly these families to decide what to draw, so
     * a key resolving to the right template here is the same fact as the page opening ruled,
     * squared or dotted the way he left it.
     */
    @Test
    fun `Michael's own keys open on the template they were written on`() {
        val expected = mapOf(
            "write" to LedgerDocuments.WRITE,                 // Lines — the day's own
            "write-1785354515034" to LedgerDocuments.WRITE,   // Lines — a named piece
            "grid" to LedgerDocuments.GRID,                   // Grid  — the day's own
            "grid#1" to LedgerDocuments.GRID,                 // Grid  — page two of it
            "sketch" to LedgerDocuments.JOT,                  // Jots  — key and label disagree
            "pickings" to LedgerDocuments.PICKINGS,           // Pickings — the day's board
        )
        for ((key, template) in expected) {
            assertEquals("$key must still open as ${LedgerDocuments.label(template)}",
                template, LedgerDocuments.templateOf(key))
        }
    }

    @Test
    fun `every template is still listed by the directory, under its template name`() {
        // The Directory's kind list is built from TEMPLATES, so this is really "the five kinds are
        // still five kinds" — the check that folding five hub rows into one door did not fold five
        // shelves into one shelf.
        for (template in LedgerDocuments.TEMPLATES) {
            assertNotNull(LedgerDocuments.glyph(template))
            assertTrue(LedgerDocuments.label(template).isNotBlank())
            assertEquals("note", LedgerDocuments.noun(template))
        }
    }
}
