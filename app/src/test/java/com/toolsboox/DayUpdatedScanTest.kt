package com.toolsboox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `updated` scan that replaced a full Moshi parse in the WebDAV inventory.
 *
 * Measured cause: 144 day files took 35,951 ms — 250 ms each — because each was deserialised whole
 * to read one number, and his day files reach 12.8 MB. These pin the regex that replaced it,
 * including the shape actually written to his device.
 */
class DayUpdatedScanTest {

    private val re = Regex("\"updated\"\\s*:\\s*(\\d+)")
    private fun scan(s: String) = re.find(s)?.groupValues?.get(1)?.toLongOrNull()

    @Test
    fun `finds updated in a real day json head`() {
        // Byte-for-byte the head of day-2026-08-25-v2.json off the Palma.
        val head = """{"calendarStrokes":{"Default":[]},"locale":"en-US","readingEvents":[],""" +
            """"deletedStrokeIds":[],"readingProgress":[],"updated":1784241869732,"noteStrokes":{}"""
        assertEquals(1784241869732L, scan(head))
    }

    @Test
    fun `tolerates whitespace around the colon`() {
        assertEquals(17L, scan("""{"updated" : 17}"""))
        assertEquals(17L, scan("""{"updated":17}"""))
    }

    @Test
    fun `a day with no updated yields null, so the caller can fall back to mtime`() {
        assertNull(scan("""{"locale":"en-US","noteStrokes":{}}"""))
        assertNull(scan(""))
    }

    @Test
    fun `a truncated window does not half-read a number`() {
        // The window can cut mid-file; a partial match must not produce a WRONG timestamp. The
        // regex needs the digits to be followed by something in-window, so a clean cut yields null
        // rather than a truncated number — and the caller re-reads or falls back.
        assertNull(scan("""{"a":1,"updated":"""))
    }

    @Test
    fun `the first match is the day's own, since nothing nested declares updated`() {
        // CalendarDay is the only type on the wire with this field (Stroke and the element types
        // do not), so first-match is unambiguous. This pins the assumption the scan rests on.
        val doc = """{"noteStrokes":{"0":[{"strokeId":"x","points":[]}]},"updated":42}"""
        assertEquals(42L, scan(doc))
    }
}
