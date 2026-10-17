package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.OpenTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * The offer-never-mint ruling (Michael, Aug 2026): auto-task faucets drop PROPOSED candidates, not
 * live tasks. These pin the two load-bearing halves of that — a proposed item is invisible to the
 * active-task reads (proven on [OpenTasks], the pure-Kotlin ghost-row gather), and the [proposed]
 * flag survives the JSON round-trip AND the sync merge (including promotion's monotonicity, the
 * parity with `done` that keeps a promoted task from sliding back onto the shelf).
 */
class ProposedItemTest {

    private val service = CalendarDayService(com.toolsboox.ot.LedgerMoshi.standard())

    private fun task(id: String, text: String, proposed: Boolean = false) = LedgerItem(
        id = id, kind = LedgerItem.Kind.TASK, text = text, date = Date(0L)
    ).apply { this.proposed = proposed }

    private fun root(): File = File(
        System.getProperty("java.io.tmpdir"), "proposed-${System.nanoTime()}"
    ).apply { mkdirs() }

    private fun save(root: File, date: LocalDate, items: List<LedgerItem>) {
        val day = CalendarDay(date.year, date.monthValue, date.dayOfMonth, startHour = null)
        day.ledgerItems.addAll(items)
        service.save(root, date, day)
    }

    private fun day(date: LocalDate, updated: Long, items: List<LedgerItem>) =
        CalendarDay(date.year, date.monthValue, date.dayOfMonth, locale = Locale.US,
            startHour = null, updated = Date(updated)).apply { ledgerItems.addAll(items) }

    // ── Exclusion from the active-task reads ─────────────────────────────────────────────────────

    @Test
    fun `a proposed candidate does not ghost as an open task`() {
        val root = root()
        val today = LocalDate.of(2026, 8, 22)
        save(root, today.minusDays(1), listOf(
            task("live", "call Dad"),
            task("cand", "reply to Bob", proposed = true)
        ))
        OpenTasks.invalidate()
        val open = OpenTasks.fresh(service, root, today, emptyList())
        // Only the live task surfaces; the proposed candidate waits on the shelf.
        assertEquals(listOf("call Dad"), open.map { it.item.text })
    }

    // ── The flag survives persistence ────────────────────────────────────────────────────────────

    @Test
    fun `proposed round-trips through save and load, default stays false`() {
        val root = root()
        val date = LocalDate.of(2026, 8, 22)
        save(root, date, listOf(
            task("cand", "reply to Bob", proposed = true),
            task("live", "call Dad")   // default false — the current behaviour
        ))
        val loaded = service.loadForDisplay(root, date, null, Locale.US)
        assertTrue(loaded.ledgerItems.first { it.id == "cand" }.proposed)
        assertFalse(loaded.ledgerItems.first { it.id == "live" }.proposed)
    }

    // ── The merge ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `merge carries the proposed flag per item`() {
        val date = LocalDate.of(2026, 8, 22)
        val a = day(date, 2000L, listOf(task("cand", "reply to Bob", proposed = true)))
        val b = day(date, 1000L, emptyList())
        val merged = CalendarDayMerger.merge(a, b)
        val item = merged.ledgerItems.first { it.id == "cand" }
        assertTrue("a candidate stays a candidate through the union", item.proposed)
    }

    @Test
    fun `promotion is monotonic — a stale candidate copy cannot re-shelf a promoted task`() {
        val date = LocalDate.of(2026, 8, 22)
        // The stale device (B) still holds the candidate AND has the newer stamp, so its copy would
        // win the newer-first union outright; A promoted it. Monotonic promotion must still win.
        val promoted = day(date, 1000L, listOf(task("cand", "reply to Bob", proposed = false)))
        val stale = day(date, 2000L, listOf(task("cand", "reply to Bob", proposed = true)))
        val merged = CalendarDayMerger.merge(promoted, stale)
        assertFalse(
            "once promoted anywhere, it stays a live task",
            merged.ledgerItems.first { it.id == "cand" }.proposed
        )
    }
}
