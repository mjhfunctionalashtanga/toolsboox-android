package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.chat.nw.Persona
import com.toolsboox.plugin.chat.nw.PersonaStore

/**
 * ❖ WHO WRITES BACK — the persona a Synthesize page answers in (Michael, 2026-10-12).
 *
 * His rulings, in order: "Synth with ▸" lists the Correspondent and his personas (1); a Synthesize
 * page REMEMBERS its voice, so writing + ❖ Synth answers in it, back and forth (2); the voice is a
 * header at the top of the page, tap it to switch who answers next (3); a second voice JOINS the
 * conversation and answers next, earlier cards keep their own names (4); his personas follow him
 * between devices (5); and the same words on both apps (6).
 *
 * Everything here is pure — no Context — so the golden fixtures (`convergence-fixtures/synth-*.json`,
 * byte-identical with iOS's LedgerCore `SynthCorrespondent` extension) pin it on the JVM:
 *  • the voice's NAME is its identity (PersonaStore keys on it on both apps); `""` is the
 *    Correspondent, whose prompt and card label are today's, byte for byte;
 *  • [shortName] / [label] — what a card and a header call the voice;
 *  • [isReply] — the card a voice wrote on its own conversation, vs a breadcrumb that points away;
 *  • [systemFor] — the Correspondent's prompt, or a persona's own prompt + [ON_THE_PAGE];
 *  • [header] / [voiceRows] — the page's top line and the "Synth with…" list.
 */
object SynthVoice {

    /** The Correspondent, as a voice value: no persona. */
    const val CORRESPONDENT = ""

    /** How every card, header and list names the Correspondent. */
    const val CORRESPONDENT_NAME = "The Correspondent"

    /** A short name is at most this many characters — it rides a card's source line. */
    const val MAX_SHORT = 32

    /**
     * A persona's prompt is written for Ask — a chat. This is what makes it a correspondent on
     * paper (the -68 framing the Correspondent's own prompt already carries), and the line that
     * keeps a joining voice from claiming another voice's turns. Byte-identical on both apps.
     */
    const val ON_THE_PAGE = "ON THE PAGE. You are writing back on a page of Michael's handwriting notebook: your reply is placed on the page as a card beneath his ink, and he answers in ink. This is a correspondence on paper, not a chat. What he just wrote is transcribed for you, with the page's earlier turns when there are any. A turn marked with another voice's name was written by that voice, not by you — never claim it and never speak for that voice. Answer the turn marked as the one you are answering, first sentence, in your own voice; everything above it is context. A card or two at most. Plain text: no markdown, no bullet points, no emojis. Never invent. End when the page should go back to him — on paper, the human holds the pen."

    /** "Spiral — what came back around" → "Spiral"; "" → "The Correspondent". The part before
     *  " — ", trimmed, at most [MAX_SHORT] characters. */
    fun shortName(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return CORRESPONDENT_NAME
        return n.substringBefore(" — ").trim().ifEmpty { n }.take(MAX_SHORT)
    }

    /** "" — or a persona that calls itself the Correspondent, which is treated as the Correspondent. */
    fun isCorrespondent(name: String?): Boolean =
        name == null || name.isBlank() || shortName(name) == CORRESPONDENT_NAME

    /** The voice value a name stands for: "" for the Correspondent, else the name itself. */
    fun normalize(name: String?): String = if (isCorrespondent(name)) CORRESPONDENT else name!!

    /** A reply card's label: "❖ The Correspondent" (unchanged) / "❖ Spiral". */
    fun label(persona: String): String = "❖ " + shortName(persona)

    /** A page key's document: "synthesize#2" → "synthesize". */
    fun base(key: String): String = key.substringBefore('#')

    /**
     * A card the correspondence itself wrote, on its own conversation — the threaded-sticky gate:
     *  1. the label ends with "The Correspondent" — every old card, both glyph generations (⟡, ❖);
     *  2. a ❖/⟡ label whose `ledger://<date>/<key>` (or legacy `synth://`) is THIS card's own
     *     document — a reply points at its own conversation; a drive-by breadcrumb points away;
     *  3. otherwise not.
     */
    fun isReply(label: String, sourceLink: String, page: String): Boolean {
        if (label.endsWith(CORRESPONDENT_NAME)) return true
        if (!(label.startsWith("❖ ") || label.startsWith("⟡ "))) return false
        val ref = when {
            sourceLink.startsWith("ledger://") -> sourceLink.removePrefix("ledger://")
            sourceLink.startsWith("synth://") -> sourceLink.removePrefix("synth://")
            else -> return false
        }
        val slash = ref.indexOf('/')
        if (slash <= 0) return false
        val key = ref.substring(slash + 1)
        if (key.isBlank()) return false
        return base(key) == base(page)
    }

    /** [isReply] for a card in hand. */
    fun isReply(el: com.toolsboox.da.ImageElement): Boolean = isReply(el.sourceLabel, el.sourceLink, el.page)

    /** The system prompt a summon asks with: the Correspondent's own, verbatim — or the persona's
     *  [prompt] + [ON_THE_PAGE]. A persona with no prompt answers as the Correspondent. */
    fun systemFor(persona: String, prompt: String): String =
        if (isCorrespondent(persona) || prompt.isBlank()) PersonaStore.CORRESPONDENT_PROMPT
        else prompt + "\n\n" + ON_THE_PAGE

    /**
     * The page's top line: "❖ The Correspondent" · "❖ Spiral" · "❖ Spiral · answering next" (the
     * page has had two voices or more) · "❖ The Correspondent · Spiral isn't on this device"
     * ([missing] = the page's voice, not on this device — the Correspondent answers instead).
     */
    fun header(persona: String, voices: List<String>, missing: String): String {
        if (!isCorrespondent(missing)) return "❖ $CORRESPONDENT_NAME · ${shortName(missing)} isn't on this device"
        val name = shortName(normalize(persona))
        return if (voices.map { normalize(it) }.distinct().size >= 2) "❖ $name · answering next" else "❖ $name"
    }

    /** The page hold's write-back row, after its glyph: "Synth — write back" / "Synth — Spiral writes back". */
    fun writeBackRow(persona: String): String =
        if (isCorrespondent(persona)) "Synth — write back" else "Synth — ${shortName(persona)} writes back"

    /** The engines' correspond door, after its glyph: "The Correspondent replies…" / "Spiral replies…". */
    fun correspondRow(persona: String): String = "${shortName(normalize(persona))} replies…"

    /** One row of the "Synth with…" / "Who answers next?" list: what it reads, and the voice it picks. */
    data class Row(val text: String, val persona: String)

    /**
     * The voice list: the Correspondent first, then the live personas in [live]'s order (PersonaStore
     * orders them: the built-ins, then his own by name). A persona with no prompt, or one that calls
     * itself the Correspondent, is not offered. [current] = the page's voice on a Synthesize page
     * (its row reads "✓  "); null off one (no ✓). Full names — the "— tagline" says what each is for.
     */
    fun voiceRows(live: List<Persona>, current: String?): List<Row> {
        val voices = listOf(CORRESPONDENT) + live
            .filter { it.prompt.isNotBlank() && !isCorrespondent(it.name) }
            .map { it.name }.distinct()
        val now = current?.let { normalize(it) }
        return voices.map { v ->
            Row((if (now != null && v == now) "✓  " else "❖  ") + (if (v.isEmpty()) CORRESPONDENT_NAME else v), v)
        }
    }
}
