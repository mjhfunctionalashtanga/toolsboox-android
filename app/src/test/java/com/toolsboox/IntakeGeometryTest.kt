package com.toolsboox

import com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The All Stars sheet budget, held by a test.
 *
 * These numbers are WIRE GEOMETRY: a gram's settled x/y is written into the day JSON and both
 * forks must resolve the identical bands from the identical counts, or every sync ping-pongs
 * positions — each device "correcting" the other forever. The 2026-08-11 punchlist ("All Stars
 * individual cards are too small to read") moved the canon table — six slots to a row, one tall
 * row per band — and this test pins the new table so the next well-meant nudge to a constant
 * fails HERE, loudly, instead of silently forking the geometry from iOS.
 *
 * Pure arithmetic only — the JVM unit-test android.jar throws on android.graphics, so the
 * RectF-shaped functions ([CalendarDayPageIntake.panels], slotRect, settledFrame) are exercised
 * on-device; what this test can and does hold is the arithmetic they are all built from.
 */
class IntakeGeometryTest {

    /** Five full bands must fit the 1404×1872 sheet — the whole budget, exactly. */
    @Test
    fun `worst case fills the sheet and not one point more`() {
        // 156 + 5·284 + 4·74 = 1872: the slot height was chosen as the largest card five full
        // one-row bands can carry, so the budget closing exactly is the design, not luck.
        assertEquals(1872f, CalendarDayPageIntake.worstCaseBottom(), 0.001f)
        assertTrue(CalendarDayPageIntake.worstCaseBottom() <= 1872f)
    }

    /** The legibility floor the punchlist asked for: roughly 2× the old 100×118 slot. */
    @Test
    fun `a slot is big enough to read`() {
        assertTrue("slot width ${CalendarDayPageIntake.slotW}", CalendarDayPageIntake.slotW >= 200f)
        assertTrue("slot height ${CalendarDayPageIntake.slotH}", CalendarDayPageIntake.slotH >= 240f)
    }

    /** The canon table's slot grid: six to the row, one row, so capacity is six before piling. */
    @Test
    fun `six slots to a band, then the pile`() {
        assertEquals(6, CalendarDayPageIntake.slotCols)
        assertEquals(1, CalendarDayPageIntake.maxRows)
        // (1324 − 32 − 5·8) / 6 ≈ 208.67 — the table's "≈ 208.7".
        assertEquals(208.67f, CalendarDayPageIntake.slotW, 0.5f)
    }

    /** Band heights straight from the table: the empty strip, and the one full row. */
    @Test
    fun `band heights match the canon table`() {
        assertEquals(56f, CalendarDayPageIntake.boxHeight(0), 0.001f)            // n = 0 → compact strip
        assertEquals(284f, CalendarDayPageIntake.boxHeight(1), 0.001f)           // 16 + 252 + 16
        assertEquals(284f, CalendarDayPageIntake.boxHeight(6), 0.001f)           // a full row, same height
        // Past capacity the band piles rather than growing — height must NOT climb, because the
        // sheet budget above is computed against exactly this ceiling.
        assertEquals(284f, CalendarDayPageIntake.boxHeight(40), 0.001f)
    }

    /** The register's five kinds, in reading order — the geometry is resolved per kind, so the
     *  vocabulary is part of the wire contract too. */
    @Test
    fun `five bands in canon order`() {
        assertEquals(
            listOf("read", "watch", "listen", "books", "educate"),
            CalendarDayPageIntake.kinds.map { it.kindKey }
        )
    }
}
