package com.toolsboox.ot

import android.content.pm.ActivityInfo

/**
 * Which way the screen turns next.
 *
 * The rotate button used to do nothing the first time it was pressed. `requestedOrientation`
 * starts as `SCREEN_ORIENTATION_UNSPECIFIED` — the activity has never asked for anything, the
 * system is simply showing the natural orientation — so `cycle.indexOf(current)` returned -1, and
 * `cycle[(-1 + 1) % size]` is `cycle[0]`, which is portrait. Asking for portrait while portrait is
 * already on screen is a no-op. The second press worked, because by then the activity had
 * requested something the cycle knew about.
 *
 * The fix is to fall back to what is actually being DISPLAYED rather than to the head of the list,
 * so the first press advances from where the eye already is.
 */
object ScreenRotation {

    /** The four the cycle is built from, in the order the button steps through them. */
    val ALL = listOf(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    )

    /** The orientations the user allows, as a bitmask over [ALL]. Never empty. */
    fun cycleFor(mask: Int): List<Int> {
        val out = ALL.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
        return out.ifEmpty { listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) }
    }

    /**
     * Map a `Surface.ROTATION_*` value to the orientation it puts on screen, for a device whose
     * natural orientation is portrait (every Boox we run on).
     */
    fun displayedBy(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE          // ROTATION_90
        2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT   // ROTATION_180
        3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE  // ROTATION_270
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT        // ROTATION_0
    }

    /**
     * The orientation to request next.
     *
     * [requested] is what the activity last asked for (often `UNSPECIFIED`), [displayed] what is
     * actually on screen. Prefers to step on from what was requested; falls back to stepping on
     * from what is displayed, which is what makes the first press move something.
     *
     * If neither is in the cycle — the user has excluded the orientation they're currently in —
     * the first allowed one is right, because that IS a change from where they are.
     */
    fun next(cycle: List<Int>, requested: Int, displayed: Int): Int {
        if (cycle.isEmpty()) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val from = cycle.indexOf(requested).takeIf { it >= 0 }
            ?: cycle.indexOf(displayed).takeIf { it >= 0 }
            ?: return cycle[0]
        return cycle[(from + 1) % cycle.size]
    }
}
