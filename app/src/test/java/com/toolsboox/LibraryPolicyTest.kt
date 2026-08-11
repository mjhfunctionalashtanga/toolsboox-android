package com.toolsboox

import com.toolsboox.plugin.reader.ui.LibraryPolicy
import com.toolsboox.plugin.reader.ui.LibraryPolicy.Carry
import com.toolsboox.plugin.reader.ui.LibraryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judgment a device makes about a book it doesn't have.
 *
 * These are the sentences the fleet's behaviour hangs on: a Palma must not eat a data plan, an
 * e-ink tablet must not swallow 20GB of PDFs it will never open, and Michael's fleet must "just
 * work" with nothing configured — which means the DEFAULTS are as load-bearing as the logic, and
 * get their own assertions here so a well-meaning refactor can't quietly flip one.
 */
class LibraryPolicyTest {

    private val mb = 1024L * 1024

    // ---- the happy default: fetch --------------------------------------------------------

    @Test
    fun `an ordinary epub on wifi in a carrying folder fetches itself`() {
        assertEquals(
            Decision.FETCH,
            LibraryPolicy.decide(2 * mb, Carry.AUTO, wifiOnly = true, unmetered = true)
        )
    }

    // ---- wifi ----------------------------------------------------------------------------

    @Test
    fun `wifi-only on a metered network waits rather than spends`() {
        assertEquals(
            Decision.WAIT_FOR_WIFI,
            LibraryPolicy.decide(2 * mb, Carry.AUTO, wifiOnly = true, unmetered = false)
        )
    }

    @Test
    fun `wifi-only off spends the metered network on purpose`() {
        assertEquals(
            Decision.FETCH,
            LibraryPolicy.decide(2 * mb, Carry.AUTO, wifiOnly = false, unmetered = false)
        )
    }

    // ---- size ----------------------------------------------------------------------------

    @Test
    fun `a book over the ceiling is offered, not fetched`() {
        assertEquals(
            Decision.TOO_BIG,
            LibraryPolicy.decide(101 * mb, Carry.AUTO, wifiOnly = true, unmetered = true)
        )
    }

    @Test
    fun `the ceiling is a boundary, not a neighbourhood`() {
        // Exactly AT the ceiling fetches — "up to 100MB" should mean what it says.
        assertEquals(
            Decision.FETCH,
            LibraryPolicy.decide(
                LibraryPolicy.DEFAULT_SIZE_CEILING_BYTES, Carry.AUTO,
                wifiOnly = true, unmetered = true
            )
        )
    }

    @Test
    fun `a custom ceiling is honoured`() {
        assertEquals(
            Decision.TOO_BIG,
            LibraryPolicy.decide(
                6 * mb, Carry.AUTO, wifiOnly = true, unmetered = true, ceilingBytes = 5 * mb
            )
        )
    }

    // ---- carry ---------------------------------------------------------------------------

    @Test
    fun `an on-open folder waits for the tap even when everything else says go`() {
        assertEquals(
            Decision.ON_OPEN,
            LibraryPolicy.decide(2 * mb, Carry.ON_OPEN, wifiOnly = true, unmetered = true)
        )
    }

    @Test
    fun `the person's selection outranks the book's size and the network's mood`() {
        // A 2GB audiobook in an on-open folder on LTE is ON_OPEN, not TOO_BIG: the ghost card
        // cites the reason the person chose, not the ones they didn't.
        assertEquals(
            Decision.ON_OPEN,
            LibraryPolicy.decide(2048 * mb, Carry.ON_OPEN, wifiOnly = true, unmetered = false)
        )
    }

    // ---- the defaults themselves ---------------------------------------------------------

    @Test
    fun `the fleet defaults are carry-everything, wifi-only, 100MB`() {
        assertTrue(LibraryPolicy.DEFAULT_WIFI_ONLY)
        assertEquals(100L * mb, LibraryPolicy.DEFAULT_SIZE_CEILING_BYTES)
        // An unknown or absent wire value means AUTO — carry-everything is what "just works".
        assertEquals(Carry.AUTO, Carry.of(null))
        assertEquals(Carry.AUTO, Carry.of("someday-mode"))
        assertEquals(Carry.ON_OPEN, Carry.of("on-open"))
        assertEquals(Carry.AUTO, Carry.of("auto"))
    }
}
