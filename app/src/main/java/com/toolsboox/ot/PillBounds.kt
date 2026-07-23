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

    /**
     * The range that also keeps the HANDLE reachable.
     *
     * Letting an oversized pill slide across its own overflow was only half an answer. The grip is
     * at the pill's leading edge, so sliding far enough to see the far end pushed the grip off the
     * screen — and the grip is the only thing that can drag it back. The pill wasn't lost, but it
     * was unreachable, which for the person holding the device is the same thing.
     *
     * So: when the pill FITS, keep the whole pill in view, as before. When it does not, keep the
     * HANDLE in view and let the rest overhang. That is the invariant worth defending — you can
     * always take hold of it — and it is strictly weaker than "keep everything in view", which is
     * unsatisfiable for a pill longer than the screen.
     *
     * [handleStart]/[handleEnd] are the handle's laid-out edges in the same axis and the same
     * coordinate space as [start]/[end] (i.e. relative to the pill's parent).
     */
    fun rangeKeepingHandle(
        start: Int, end: Int, parentSize: Int, handleStart: Int, handleEnd: Int
    ): ClosedFloatingPointRange<Float> {
        if (end - start <= parentSize) return range(start, end, parentSize)
        val lo = -handleStart.toFloat()                 // handle flush to the near edge
        val hi = (parentSize - handleEnd).toFloat()     // handle flush to the far edge
        return if (lo <= hi) lo..hi else hi..lo
    }
}
