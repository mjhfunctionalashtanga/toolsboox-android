package com.toolsboox.ot

/**
 * Where a floating pill is allowed to sit.
 *
 * Pure arithmetic, kept out of the fragment so it can be tested against the screen sizes that
 * actually break it. The bug it exists to fix was invisible in the old inline version: on a wide
 * screen every pill fits, so the clamp looked right for as long as nobody opened it on a phone.
 */
object PillBounds {

    /**
     * The range [pill] may be translated along one axis, given where it was laid out and how big
     * its parent is. Returns `min..max`, always with `min <= max`.
     *
     * [start] is the pill's laid-out edge (its `left`, or its `top`); [end] is the opposite edge.
     *
     * When the pill FITS, the range runs from flush against one edge to flush against the other,
     * which is what you'd expect.
     *
     * When the pill is BIGGER than its parent — a horizontal pill on a Palma is wider than the
     * screen — the two limits swap over: "flush right" is now further left than "flush left". The
     * old code resolved that with `max.coerceAtLeast(min)`, which collapsed the range to a single
     * point and pinned the pill where it sat, unmovable in that axis. That is the whole of "can't
     * move modals all the way over to the side" and "pills won't expand far right on horizontal":
     * not a rendering problem, a clamp that quietly forbade the only useful gesture.
     *
     * Keeping both limits and ordering them means an oversized pill slides across its own
     * overflow, so either end can be brought into view. It is the only sensible reading of "keep
     * it on screen" when the thing is longer than the screen.
     */
    fun range(start: Int, end: Int, parentSize: Int): ClosedFloatingPointRange<Float> {
        val toStart = -start.toFloat()
        val toEnd = (parentSize - end).toFloat()
        return if (toStart <= toEnd) toStart..toEnd else toEnd..toStart
    }
}
