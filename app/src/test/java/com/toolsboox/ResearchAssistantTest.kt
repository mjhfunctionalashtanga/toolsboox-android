package com.toolsboox

import com.toolsboox.plugin.calendar.ot.ResearchAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The research assistant's parser, and specifically the one rule the whole feature rests on.
 *
 * Everything this produces ends up on a page as a tappable gram, which means a fabricated url is
 * not a cosmetic error — it is indistinguishable from a real citation once it is drawn, and it
 * will be tapped. So the parser drops anything without a real url rather than trusting the model
 * to have obeyed the prompt, and that behaviour is pinned here.
 */
class ResearchAssistantTest {

    @Test
    fun `items without a url are dropped, not guessed at`() {
        val md = """
            ## Primary sources
            - Real thing :: https://example.com/a :: worth reading
            - No url here :: because the model hedged
            - Also fine :: https://example.com/b :: the counterargument
        """.trimIndent()
        val items = ResearchAssistant.parse(md)
        assertEquals(2, items.size)
        assertTrue(items.all { it.url.startsWith("http") })
    }

    @Test
    fun `a non-url in the url slot is dropped`() {
        // The failure mode this guards: a model that writes "see the Smith paper" where the url
        // goes. That parses structurally but is not a citation, and must not reach the page.
        val md = """
            ## Background
            - Smith on method :: see the Smith paper :: foundational
        """.trimIndent()
        assertTrue(ResearchAssistant.parse(md).isEmpty())
    }

    @Test
    fun `clusters carry through and a missing why-line is tolerated`() {
        val md = """
            ## Critiques
            - Sharp objection :: https://example.com/c
            ## Reference
            - The standard text :: https://example.com/d :: start here
        """.trimIndent()
        val items = ResearchAssistant.parse(md)
        assertEquals(listOf("Critiques", "Reference"), items.map { it.cluster })
        assertEquals("", items[0].why)
        assertEquals("start here", items[1].why)
    }

    @Test
    fun `items before any heading still land, under a fallback cluster`() {
        val items = ResearchAssistant.parse("- Loose one :: https://example.com/e :: no heading")
        assertEquals(1, items.size)
        assertEquals("Sources", items[0].cluster)
    }

    @Test
    fun `the graph hangs every source off its cluster and spends no slot on the topic`() {
        val items = listOf(
            ResearchAssistant.Item("A", "one", "https://x/1", ""),
            ResearchAssistant.Item("A", "two", "https://x/2", ""),
            ResearchAssistant.Item("B", "three", "https://x/3", ""),
        )
        val (nodes, edges) = ResearchAssistant.graph(items)
        // Two cluster hubs + three sources. No node for the topic itself — the note is named for it.
        assertEquals(5, nodes.size)
        assertEquals(2, nodes.count { it.ref.startsWith("cluster:") })
        assertEquals(3, edges.size)
        assertTrue(edges.all { it.from.startsWith("cluster:") })
        // Every source is a live door; hubs are not, because a cluster name has no address.
        assertTrue(nodes.filter { !it.ref.startsWith("cluster:") }.all { it.link.startsWith("http") })
        assertTrue(nodes.filter { it.ref.startsWith("cluster:") }.all { it.link.isEmpty() })
    }

    @Test
    fun `edges point at refs that exist, so no connector dangles`() {
        val items = ResearchAssistant.parse(
            """
            ## Cluster one
            - a :: https://x/1 :: why
            ## Cluster two
            - b :: https://x/2 :: why
            """.trimIndent()
        )
        val (nodes, edges) = ResearchAssistant.graph(items)
        val refs = nodes.map { it.ref }.toSet()
        assertTrue(edges.all { it.from in refs && it.to in refs })
    }
}
