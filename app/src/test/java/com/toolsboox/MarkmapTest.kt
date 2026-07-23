package com.toolsboox

import com.toolsboox.ot.Markmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an outline into a tree.
 *
 * The input is whatever a person or a language model actually types, which is never the tidy
 * case. So the rules under test are mostly about not losing anything: a map with a thought
 * missing is worse than a map that is slightly the wrong shape, because you cannot see the gap.
 */
class MarkmapTest {

    private fun labelOf(nodes: List<Markmap.Node>, label: String) = nodes.first { it.label == label }
    private fun parentLabel(nodes: List<Markmap.Node>, label: String): String? {
        val p = labelOf(nodes, label).parent ?: return null
        return nodes.first { it.id == p }.label
    }

    @Test
    fun `the first heading is the middle whatever level it is written at`() {
        val n = Markmap.parse("## Practice\n- Breath\n")
        assertEquals("Practice", n.first().label)
        assertNull(n.first().parent)
        assertEquals(n.first().id, Markmap.root(n))
    }

    @Test
    fun `headings nest by their level`() {
        val n = Markmap.parse("# Year\n## Summer\n### Oxford\n## Autumn\n")
        assertEquals("Year", parentLabel(n, "Summer"))
        assertEquals("Summer", parentLabel(n, "Oxford"))
        assertEquals("Year", parentLabel(n, "Autumn"))
    }

    @Test
    fun `bullets hang off the heading they follow`() {
        val n = Markmap.parse("# Day\n## Morning\n- Sit\n- Walk\n## Evening\n- Read\n")
        assertEquals("Morning", parentLabel(n, "Sit"))
        assertEquals("Morning", parentLabel(n, "Walk"))
        assertEquals("Evening", parentLabel(n, "Read"))
    }

    @Test
    fun `indented bullets nest`() {
        val n = Markmap.parse("# Trip\n- Pack\n  - Mat\n  - Towel\n- Book flights\n")
        assertEquals("Pack", parentLabel(n, "Mat"))
        assertEquals("Pack", parentLabel(n, "Towel"))
        assertEquals("Trip", parentLabel(n, "Book flights"))
    }

    @Test
    fun `tabs count as one level`() {
        val n = Markmap.parse("# T\n- A\n\t- B\n")
        assertEquals("A", parentLabel(n, "B"))
    }

    @Test
    fun `an outline with no heading still gets a middle`() {
        val n = Markmap.parse("- One\n- Two\n", fallbackRoot = "Ideas")
        assertEquals("Ideas", n.first().label)
        assertEquals("Ideas", parentLabel(n, "One"))
    }

    @Test
    fun `stars and pluses are bullets too`() {
        val n = Markmap.parse("# T\n* A\n+ B\n")
        assertEquals("T", parentLabel(n, "A"))
        assertEquals("T", parentLabel(n, "B"))
    }

    @Test
    fun `a bare line is kept rather than dropped`() {
        // Models write prose between the bullets constantly; losing it loses the map's content.
        val n = Markmap.parse("# T\n## Section\nA plain thought\n")
        assertEquals("Section", parentLabel(n, "A plain thought"))
    }

    @Test
    fun `blank lines and empty headings are ignored`() {
        val n = Markmap.parse("# T\n\n\n#\n-   \n- Real\n")
        assertEquals(2, n.size)
        assertEquals("Real", n.last().label)
    }

    @Test
    fun `nothing in means nothing out rather than a crash`() {
        assertTrue(Markmap.parse("").isEmpty())
        assertNull(Markmap.root(emptyList()))
    }

    @Test
    fun `adjacency is undirected so the map can be walked from anywhere`() {
        val n = Markmap.parse("# T\n- A\n")
        val adj = Markmap.adjacency(n)
        val t = labelOf(n, "T").id
        val a = labelOf(n, "A").id
        assertTrue(adj[t]!!.contains(a))
        assertTrue(adj[a]!!.contains(t))
    }

    @Test
    fun `the same outline parses identically every time`() {
        val md = "# T\n## S\n- one\n  - two\n"
        assertEquals(Markmap.parse(md), Markmap.parse(md))
        assertNotNull(Markmap.root(Markmap.parse(md)))
    }
}
