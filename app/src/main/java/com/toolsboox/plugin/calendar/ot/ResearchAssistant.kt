package com.toolsboox.plugin.calendar.ot

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * The Ask output that goes and FINDS things, and leaves a page you can work from.
 *
 * Michael's brief for it, in his own order: "curate links, create pickings, construct synthesis
 * questions and writing prompts that link appropriately for a topic so a given topic can be
 * fleshed out easily using items within ledger and outside but cited also" — and then, on what it
 * should leave behind: "a nice jot note of connected grams literally and cliparty."
 *
 * So the deliverable is not an answer. It is a JOTS page: clusters and sources as grams, connectors
 * between them, every source carrying its own url as live provenance so the citation is a door
 * rather than a footnote. Scattered rather than gridded, because a research page is a pinboard —
 * the tidy lattice belongs to the Map, which is a different claim about the same material.
 *
 * Everything lands through [GramComposer], which means a research page is an ordinary Jots note
 * from the moment it exists: writable, erasable, searchable, and on the wire with no new fields.
 */
object ResearchAssistant {

    /**
     * What the model is asked for.
     *
     * Two things this insists on, both because the output is going onto a page that outlives the
     * conversation. CITE OR OMIT: an invented url is worse than a short list, because on the page
     * it is indistinguishable from a real one and it will be tapped. And CLUSTER FIRST: the
     * clusters are what the connectors are drawn from, so a flat list would compose into a page of
     * unconnected cards — the shape of the answer has to survive the trip onto paper.
     */
    const val PROMPT = """
You are a research assistant preparing a working page on a topic — not an essay, a PINBOARD.

Find the material worth having: primary sources, the strongest critiques, the standard reference,
and anything genuinely surprising. Prefer things a thoughtful person would actually read.

Reply with a markdown outline and NOTHING else — no preamble, no code fence, no closing remark:

## <cluster name>
- <title> :: <url> :: <one line on why this one>

Rules:
- Between two and four "## " clusters, named for what they contain, not "Sources" or "Background".
- Two to four items under each cluster.
- EVERY item must have a real, working url you are confident exists. If you are not sure of the
  url, leave the item out entirely. An invented citation is the one unforgivable output here,
  because on the finished page it looks exactly like a true one.
- Keep the title under 50 characters and the why-line under 80.
- No commentary anywhere outside the outline.
"""

    /** One curated thing, as the model returned it. */
    data class Item(val cluster: String, val title: String, val url: String, val why: String)

    /**
     * Pull the clusters and items out of the model's outline.
     *
     * Deliberately forgiving about everything except the url: a missing why-line is a page with a
     * bare label on it, which is fine, but an item with no url is not a citation and does not go
     * on the page at all.
     */
    fun parse(markdown: String): List<Item> {
        val out = mutableListOf<Item>()
        var cluster = ""
        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> cluster = line.removePrefix("## ").trim()
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val parts = line.drop(2).split("::").map { it.trim() }
                    if (parts.size < 2) continue
                    val url = parts[1]
                    if (!url.startsWith("http")) continue
                    out += Item(
                        cluster = cluster.ifBlank { "Sources" },
                        title = parts[0].trim('*', '_', ' '),
                        url = url,
                        why = parts.getOrNull(2).orEmpty(),
                    )
                }
            }
        }
        return out
    }

    /**
     * Turn parsed items into the graph [GramComposer] draws.
     *
     * The topic itself gets no node: the note is already named for it, and spending one of twelve
     * slots restating the title costs a source. Clusters become the hubs, sources hang off them.
     */
    fun graph(items: List<Item>): Pair<List<GramComposer.Node>, List<GramComposer.Edge>> {
        val clusters = items.map { it.cluster }.distinct()
        val nodes = mutableListOf<GramComposer.Node>()
        val edges = mutableListOf<GramComposer.Edge>()
        for (c in clusters) {
            nodes += GramComposer.Node(ref = "cluster:$c", label = c, kind = "◆")
        }
        for (item in items) {
            nodes += GramComposer.Node(
                ref = item.url,
                label = item.title,
                link = item.url,
                sourceLabel = item.title,
                note = item.why,
            )
            edges += GramComposer.Edge("cluster:${item.cluster}", item.url)
        }
        return nodes to edges
    }

    /**
     * The whole trip: ask, parse, compose. Runs on the caller's IO thread and returns the note.
     *
     * Returns null when the model gave nothing usable — which, given the cite-or-omit rule above,
     * is the honest outcome for a topic it has no real sources for, and better than a page of
     * plausible-looking dead links.
     */
    fun run(
        context: Context,
        service: com.toolsboox.plugin.calendar.fi.CalendarDayService,
        root: File,
        topic: String,
        date: LocalDate = LocalDate.now(),
    ): LedgerDocuments.NewNote? {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(context) ?: return null
        val (provider, key, model) = creds
        val result = com.toolsboox.plugin.chat.nw.LedgerChatService()
            .run(provider, key, model, PROMPT, topic)
        val answer = (result as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)?.answer
            ?: return null
        val items = parse(answer)
        if (items.isEmpty()) return null
        val (nodes, edges) = graph(items)
        return GramComposer.compose(
            context, service, root,
            LedgerDocuments.JOT, "Research · $topic",
            nodes, edges, GramComposer.Layout.COLLAGE, date,
        )
    }
}
