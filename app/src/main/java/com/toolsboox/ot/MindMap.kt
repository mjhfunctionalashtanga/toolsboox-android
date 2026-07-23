package com.toolsboox.ot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the things on a mind map go.
 *
 * Deliberately not a physics simulation. A force-directed graph settles by jiggling, which on
 * e-ink means a screen full of ghosting to arrive somewhere arbitrary, and it puts the same
 * thought in a different place every time you open it — so you can never learn the shape of your
 * own map. This is a plain radial layout: what you are looking at sits in the middle, what it
 * touches rings it, and what those touch rings them. Same input, same picture, every time.
 *
 * Pure geometry in unit space (-1..1 on both axes), so it can be tested without a screen and the
 * view is left with nothing to do but draw.
 */
object MindMap {

    data class Placed(
        val uri: String,
        val label: String,
        val depth: Int,
        val x: Float,
        val y: Float,
        /** Which depth-1 node this hangs off, for drawing the line back. Focus for depth 1. */
        val parent: String
    )

    /** How many are shown before the ring is called full. Beyond this it stops being readable. */
    const val MAX_INNER = 10
    const val MAX_PER_BRANCH = 3

    private const val INNER_R = 0.52f
    private const val OUTER_R = 0.92f

    /**
     * Lay out [focus] and two rings around it.
     *
     * [neighbours] is the whole adjacency map; [label] names a node. Anything already placed is
     * skipped rather than drawn twice — a thought that connects to two things on the map is one
     * thought, and duplicating it would tell you the opposite.
     */
    fun layout(
        focus: String,
        neighbours: Map<String, List<String>>,
        label: (String) -> String
    ): List<Placed> {
        val out = mutableListOf<Placed>()
        val seen = mutableSetOf(focus)
        out += Placed(focus, label(focus), 0, 0f, 0f, focus)

        val inner = neighbours[focus].orEmpty().distinct().take(MAX_INNER)
        if (inner.isEmpty()) return out

        // Start at the top and go clockwise: a map you read like a clock face is a map you can
        // describe to yourself out loud.
        val step = (2 * PI / inner.size).toFloat()
        val start = (-PI / 2).toFloat()

        inner.forEachIndexed { i, uri ->
            if (!seen.add(uri)) return@forEachIndexed
            val a = start + step * i
            out += Placed(uri, label(uri), 1, INNER_R * cos(a), INNER_R * sin(a), focus)
        }

        // The outer ring hangs off its own branch, inside a wedge no wider than the gap to the
        // next branch — so a line never crosses a neighbour's territory to reach its parent.
        val wedge = min(step * 0.8f, (PI / 3).toFloat())
        for ((i, parent) in inner.withIndex()) {
            val kids = neighbours[parent].orEmpty().distinct()
                .filter { it !in seen }
                .take(MAX_PER_BRANCH)
            if (kids.isEmpty()) continue
            val a = start + step * i
            val spread = if (kids.size == 1) 0f else wedge / (kids.size - 1)
            val from = a - (if (kids.size == 1) 0f else wedge / 2f)
            kids.forEachIndexed { k, uri ->
                if (!seen.add(uri)) return@forEachIndexed
                val ka = from + spread * k
                out += Placed(uri, label(uri), 2, OUTER_R * cos(ka), OUTER_R * sin(ka), parent)
            }
        }
        return out
    }
}
