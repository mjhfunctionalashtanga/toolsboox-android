package com.toolsboox.ot

/**
 * The lenses the Map can be asked through.
 *
 * A mind map is one shape a thought can take, and it is the least interesting one — a hierarchy
 * says everything hangs off one idea, which is usually false and always flattering. These are
 * other shapes for the same material, and the shape is the argument: a causal loop insists you
 * say what feeds what, a tension map insists there are two sides, a question tree insists you
 * admit what you do not know.
 *
 * Every one of them returns MARKDOWN, because [Markmap] already turns that into a tree and every
 * model on earth writes it without being asked twice.
 */
object MapPersona {

    data class Persona(val key: String, val label: String, val blurb: String, val prompt: String)

    private const val FORMAT = """
Reply with a markdown outline and NOTHING else — no preamble, no code fence, no closing remark.
One "# " heading for the centre. Nest with "## " and "- ", two spaces per level.
Keep every line under 60 characters so it fits in a box on the page.
Go at most three levels deep."""

    val ALL = listOf(
        Persona(
            "mind", "🌿  Mind map",
            "The plain shape: one idea, and what hangs off it.",
            "Make a mind map of the material below. Group it the way it actually falls, not into " +
                "tidy equal branches — a lopsided map that is true beats a balanced one that is not.$FORMAT"
        ),
        Persona(
            "causal", "🔁  Causal loops",
            "What feeds what, and which circles run away or settle.",
            "Read the material below as a system of causes. Find the loops: chains where something " +
                "eventually feeds back into itself.\n" +
                "Use one \"## \" heading per loop, named for what it does, and mark it (R) if it " +
                "reinforces — it runs away in one direction — or (B) if it balances, meaning it " +
                "settles or pushes back.\n" +
                "Under each loop, list the steps in order as bullets, each written as \"A → B\", so " +
                "the last step closes the circle back to the first.\n" +
                "Say plainly if you can only find one loop. An invented loop is worse than a short " +
                "answer.$FORMAT"
        ),
        Persona(
            "tension", "⚖  Tensions",
            "The two sides of each pull, rather than a list of points.",
            "Find the real tensions in the material below — the places where two things that both " +
                "matter pull against each other. One \"## \" heading per tension, phrased as \"X vs Y\". " +
                "Under each, what each side is protecting, and what it costs. Do not resolve them; " +
                "naming a tension honestly is the work.$FORMAT"
        ),
        Persona(
            "questions", "❓  Question tree",
            "What you would have to know, broken into what you would have to ask.",
            "Turn the material below into a tree of questions. The centre is the real question " +
                "underneath it. Each branch is a question you would have to answer first, and its " +
                "children are the questions under that. Questions only — no answers anywhere.$FORMAT"
        ),
        Persona(
            "sequence", "⏱  Order of things",
            "What happens first, and what it makes possible.",
            "Lay the material below out in order. One \"## \" heading per stage, in the order they " +
                "happen. Under each, what is actually done, and what it unlocks. Where two things " +
                "can happen at once, say so rather than inventing an order.$FORMAT"
        ),
        Persona(
            "parts", "🧩  Parts and wholes",
            "What this is made of, all the way down.",
            "Break the material below into its parts, and those into theirs. Each level should be " +
                "a genuine part of its parent, not a related idea — if it is merely related, leave " +
                "it out.$FORMAT"
        )
    )

    fun byKey(key: String): Persona = ALL.firstOrNull { it.key == key } ?: ALL.first()
}
