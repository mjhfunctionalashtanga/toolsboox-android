package com.toolsboox.ot

/**
 * A markdown outline as a tree, in the spirit of markmap.
 *
 * Headings and list items are the two ways everyone already writes an outline, so this is the
 * cheapest possible way to hand a map to the app — typed by you, or asked for from Ask, which
 * returns markdown whether you want it to or not.
 *
 * Pure text in, plain nodes out. The layout and the drawing are somebody else's problem, which
 * is what lets all three be tested and changed apart.
 */
object Markmap {

    /** [id] is positional, so the same outline always yields the same tree and the same picture. */
    data class Node(val id: String, val label: String, val parent: String?)

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")

    /**
     * Parse [markdown] into a tree.
     *
     * The first heading becomes the root when the outline starts with one, because that is what
     * people mean by writing a title. Otherwise a root is supplied, so the map always has a
     * middle — a forest would need somewhere to stand to look at it.
     */
    fun parse(markdown: String, fallbackRoot: String = "Map"): List<Node> {
        val out = mutableListOf<Node>()
        // (depth, id) of the open ancestors, outermost first.
        val stack = ArrayDeque<Pair<Int, String>>()
        var n = 0
        var section = 0          // heading depth we are currently inside, for bullets to hang off

        fun add(depth: Int, label: String): String {
            while (stack.isNotEmpty() && stack.last().first >= depth) stack.removeLast()
            val id = "n${n++}"
            out += Node(id, label, stack.lastOrNull()?.second)
            stack.addLast(depth to id)
            return id
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue

            // A line that is only its own marker — "#", or a bullet whose text was all spaces —
            // is punctuation, not a thought. Without this it fell through to the keep-bare-lines
            // rule below and drew a node called "#" or "-".
            val bare = line.trim()
            if (bare.all { it == '#' } || bare in setOf("-", "*", "+")) continue

            val heading = HEADING.find(line)
            if (heading != null) {
                val depth = heading.groupValues[1].length
                val text = heading.groupValues[2].trim()
                if (text.isEmpty()) continue
                if (out.isEmpty()) {
                    // Whatever level it is written at, the first heading is the middle.
                    add(0, text); section = 0
                } else {
                    add(depth, text); section = depth
                }
                continue
            }

            val bullet = BULLET.find(line)
            if (bullet != null) {
                val text = bullet.groupValues[2].trim()
                if (text.isEmpty()) continue
                // Two spaces per level is the common convention; tabs count as one level.
                val indent = bullet.groupValues[1].replace("\t", "  ").length / 2
                if (out.isEmpty()) add(0, fallbackRoot)
                add(section + 1 + indent, text)
                continue
            }

            // A bare line is a child of wherever we are — a paragraph under a heading is a thought
            // about that heading, and throwing it away would lose the half of an outline that
            // people write without noticing.
            if (out.isEmpty()) add(0, line.trim()) else add(section + 1, line.trim())
        }
        return out
    }

    /** The tree as the undirected adjacency [MindMap] wants. */
    fun adjacency(nodes: List<Node>): Map<String, List<String>> {
        val adj = HashMap<String, MutableList<String>>()
        for (node in nodes) {
            val p = node.parent ?: continue
            adj.getOrPut(p) { mutableListOf() }.add(node.id)
            adj.getOrPut(node.id) { mutableListOf() }.add(p)
        }
        return adj
    }

    fun labels(nodes: List<Node>): Map<String, String> = nodes.associate { it.id to it.label }

    fun root(nodes: List<Node>): String? = nodes.firstOrNull { it.parent == null }?.id
}
