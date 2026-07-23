package com.toolsboox.ot

/**
 * The shapes a Synthesize page can be turned into an outline as.
 *
 * The essay outline used to be one hardcoded prompt — a classic five-paragraph theme, which is
 * the right skeleton for some pieces and a straitjacket for others. These are the other skeletons
 * for the same gathered material, so the outline you carry to Write matches the thing you are
 * actually trying to write rather than the one shape the machine knew.
 *
 * Sibling of [MapPersona]: same idea, the other end of the Garden. A map is how the material
 * looks; a write persona is how it wants to be argued.
 *
 * Every one returns a bullet outline, one bullet per line, because that is what drops onto the
 * Write page as movable text boxes.
 */
object WritePersona {

    data class Persona(val key: String, val label: String, val blurb: String, val prompt: String)

    private const val FORMAT = """
Output ONLY the outline, one bullet per line, no preamble and no closing remark.
Keep each line short enough to read on a page at a glance.
Carry the reader's own provenance through: where a point rests on a specific picking, note, or
source from the material, name it in the bullet so the citation survives into the writing."""

    val ALL = listOf(
        Persona(
            "classic", "🗒  Classic five-paragraph",
            "Thesis, three body points, conclusion. The dependable skeleton.",
            "Sketch a CLASSIC bullet-pointed essay outline from the reader's notes below: a one-line " +
                "thesis, then Intro, three Body points each with 1–2 sub-bullets, and a Conclusion.$FORMAT"
        ),
        Persona(
            "longform", "📜  Long-form essay",
            "More room: five or six movements, each developed.",
            "Sketch a LONG-FORM essay outline from the reader's notes below. Open with the thesis, " +
                "then five or six sections, each a heading bullet with two or three developed " +
                "sub-bullets — the turn it makes, the evidence it rests on, and the objection it must " +
                "answer. Close with a section that earns the ending rather than restating the start.$FORMAT"
        ),
        Persona(
            "argument", "⚖  Argument",
            "Claim, reasons, the strongest objection, the reply.",
            "Sketch an ARGUMENT outline from the reader's notes below: the claim in one line, then the " +
                "reasons for it as bullets each with its evidence, then the single strongest objection " +
                "stated fairly, then the reply, then what follows if the claim holds.$FORMAT"
        ),
        Persona(
            "narrative", "📖  Narrative arc",
            "Tell it as a story: the turn, not the thesis.",
            "Sketch a NARRATIVE outline from the reader's notes below. Not a thesis but an arc: the " +
                "situation, the thing that disturbs it, what it forces, the turn, and where it leaves " +
                "you. Each beat a bullet, in the order a reader lives it, keeping the concrete detail " +
                "from the notes rather than abstracting it away.$FORMAT"
        ),
        Persona(
            "letter", "✉  Letter",
            "Written to one person, in your own voice.",
            "Sketch the outline of a LETTER from the reader's notes below — written to one particular " +
                "person about this material. What you want them to understand, told plainly and in " +
                "order, each move a bullet. Warm, direct, no thesis statement.$FORMAT"
        ),
        Persona(
            "talk", "🎙  Spoken",
            "For the ear: a hook, a few beats, a line to land on.",
            "Sketch the outline of a SHORT TALK from the reader's notes below — written to be heard, " +
                "not read. A hook that earns the first minute, three or four beats each a bullet with " +
                "the one example that makes it land, and a final line worth saying out loud.$FORMAT"
        )
    )

    fun byKey(key: String): Persona = ALL.firstOrNull { it.key == key } ?: ALL.first()
}
