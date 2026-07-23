package com.toolsboox

import com.toolsboox.ot.MindMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The shape of a map you can learn.
 *
 * The property worth defending is that the same graph always draws the same picture. A layout
 * that settles by jiggling puts a thought somewhere new every time it is opened, and then the
 * map can never become familiar — which is most of what a map is for.
 */
class MindMapTest {

    private val g = mapOf(
        "a" to listOf("b", "c", "d"),
        "b" to listOf("a", "e", "f"),
        "c" to listOf("a"),
        "d" to listOf("a", "b"),
        "e" to listOf("b")
    )
    private val name: (String) -> String = { it.uppercase() }

    @Test
    fun `the focus sits in the middle`() {
        val p = MindMap.layout("a", g, name).first()
        assertEquals("a", p.uri)
        assertEquals(0, p.depth)
        assertEquals(0f, p.x, 0.0001f)
        assertEquals(0f, p.y, 0.0001f)
    }

    @Test
    fun `the same graph lays out identically every time`() {
        assertEquals(MindMap.layout("a", g, name), MindMap.layout("a", g, name))
    }

    @Test
    fun `nothing is placed twice`() {
        val uris = MindMap.layout("a", g, name).map { it.uri }
        assertEquals(uris.size, uris.distinct().size)
    }

    @Test
    fun `the focus never reappears in a ring`() {
        // "b" lists "a" as a neighbour; drawing the middle again out on the rim would say there
        // are two of it.
        assertEquals(1, MindMap.layout("a", g, name).count { it.uri == "a" })
    }

    @Test
    fun `rings sit at their own radius`() {
        for (p in MindMap.layout("a", g, name)) {
            val r = hypot(p.x.toDouble(), p.y.toDouble()).toFloat()
            when (p.depth) {
                0 -> assertEquals(0f, r, 0.0001f)
                1 -> assertEquals(0.52f, r, 0.0001f)
                2 -> assertEquals(0.92f, r, 0.0001f)
            }
        }
    }

    @Test
    fun `everything stays inside the unit square`() {
        for (p in MindMap.layout("a", g, name)) {
            assertTrue("x out of bounds: ${p.x}", p.x in -1f..1f)
            assertTrue("y out of bounds: ${p.y}", p.y in -1f..1f)
        }
    }

    @Test
    fun `an outer node remembers the branch it hangs off`() {
        val e = MindMap.layout("a", g, name).first { it.uri == "e" }
        assertEquals(2, e.depth)
        assertEquals("b", e.parent)
    }

    @Test
    fun `a lonely node is just itself`() {
        assertEquals(1, MindMap.layout("z", g, name).size)
    }

    @Test
    fun `a crowded ring is capped rather than becoming a smear`() {
        val many = (1..40).map { "n$it" }
        val big = mapOf("hub" to many) + many.associateWith { listOf("hub") }
        val laid = MindMap.layout("hub", big, name)
        assertEquals(MindMap.MAX_INNER, laid.count { it.depth == 1 })
    }

    @Test
    fun `labels come from the caller`() {
        assertEquals("B", MindMap.layout("a", g, name).first { it.uri == "b" }.label)
    }
}
