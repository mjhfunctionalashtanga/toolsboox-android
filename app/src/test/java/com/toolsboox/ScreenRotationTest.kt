package com.toolsboox

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import com.toolsboox.ot.ScreenRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dead first tap.
 *
 * `requestedOrientation` is UNSPECIFIED until the activity asks for something, so the old
 * `indexOf(current) ?: -1` fell to `cycle[0]` — portrait — while portrait was already on screen.
 * Nothing moved, and the button read as broken until you pressed it twice.
 */
class ScreenRotationTest {

    private val all = ScreenRotation.cycleFor(0b1111)

    @Test
    fun `the first press moves the screen`() {
        // Fresh activity, portrait on screen: must NOT ask for portrait again.
        val next = ScreenRotation.next(all, SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_PORTRAIT)
        assertNotEquals(SCREEN_ORIENTATION_PORTRAIT, next)
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, next)
    }

    @Test
    fun `the first press moves the screen from landscape too`() {
        val next = ScreenRotation.next(all, SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_LANDSCAPE)
        assertNotEquals(SCREEN_ORIENTATION_LANDSCAPE, next)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, next)   // landscape is last, so it wraps
    }

    @Test
    fun `once the activity has asked, that is what we step from`() {
        assertEquals(
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ScreenRotation.next(all, SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SCREEN_ORIENTATION_PORTRAIT)
        )
    }

    @Test
    fun `four presses return to where they started`() {
        var cur = SCREEN_ORIENTATION_PORTRAIT
        repeat(4) { cur = ScreenRotation.next(all, cur, cur) }
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, cur)
    }

    @Test
    fun `a restricted cycle only offers what was allowed`() {
        // Portrait + landscape only.
        val cycle = ScreenRotation.cycleFor(0b1001)
        assertEquals(listOf(SCREEN_ORIENTATION_PORTRAIT, SCREEN_ORIENTATION_LANDSCAPE), cycle)
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, ScreenRotation.next(cycle, SCREEN_ORIENTATION_PORTRAIT, SCREEN_ORIENTATION_PORTRAIT))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, ScreenRotation.next(cycle, SCREEN_ORIENTATION_LANDSCAPE, SCREEN_ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `being in an orientation you have excluded still moves you out of it`() {
        val cycle = ScreenRotation.cycleFor(0b1001)  // no reverse-anything
        val next = ScreenRotation.next(cycle, SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_REVERSE_PORTRAIT)
        assertTrue(next in cycle)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, next)
    }

    @Test
    fun `an empty mask still gives somewhere to go`() {
        val cycle = ScreenRotation.cycleFor(0)
        assertEquals(listOf(SCREEN_ORIENTATION_PORTRAIT), cycle)
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, ScreenRotation.next(cycle, SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_PORTRAIT))
    }

    @Test
    fun `surface rotations map to what is on screen`() {
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, ScreenRotation.displayedBy(0))
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, ScreenRotation.displayedBy(1))
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, ScreenRotation.displayedBy(2))
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ScreenRotation.displayedBy(3))
    }

    @Test
    fun `every step lands on something allowed`() {
        for (mask in 0..0b1111) {
            val cycle = ScreenRotation.cycleFor(mask)
            for (displayed in ScreenRotation.ALL) {
                val next = ScreenRotation.next(cycle, SCREEN_ORIENTATION_UNSPECIFIED, displayed)
                assertTrue("mask=$mask displayed=$displayed gave $next", next in cycle)
            }
        }
    }
}
