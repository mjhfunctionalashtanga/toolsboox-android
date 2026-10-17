package com.toolsboox

import com.toolsboox.plugin.calendar.ot.PendingSummon
import com.toolsboox.plugin.calendar.ot.PersonaRow
import com.toolsboox.plugin.calendar.ot.SynthCorrespondence
import com.toolsboox.plugin.calendar.ot.SynthPending
import com.toolsboox.plugin.calendar.ot.SynthPersonas
import com.toolsboox.plugin.calendar.ot.SynthVoice
import com.toolsboox.plugin.calendar.ot.SynthVoiceHeader
import com.toolsboox.plugin.chat.nw.Persona
import com.toolsboox.plugin.chat.nw.PersonaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review of b29cabbc (synth-personas, Android), pinned on the JVM. These cases are Android's own
 * until both apps carry them — the shared `convergence-fixtures/synth-*.json` stay byte-identical
 * with iOS, so nothing here is added to them.
 */
class SynthVoiceReviewTest {

    private val spiral = PersonaStore.SPIRAL

    // ── A live persona with a blank prompt is missing (iOS SynthVoice.resolve) ──────────────

    @Test
    fun `a live persona with nothing to say answers as the Correspondent, and says it is missing`() {
        val rows = SynthPersonas.ensure(listOf(PersonaRow("Blank", "   ", 5L), PersonaRow("Coach", "Coach me.", 6L)))
        assertEquals(SynthPersonas.Resolved("", "Blank"), SynthPersonas.voice(rows, "Blank"))
        assertEquals(SynthPersonas.Resolved("Coach", ""), SynthPersonas.voice(rows, "Coach"))
        // An untouched seed's stored prompt may be anything: it speaks this build's text, so it is live.
        val seed = SynthPersonas.ensure(listOf(PersonaRow(spiral, "", 0L)))
        assertEquals(SynthPersonas.Resolved(spiral, ""), SynthPersonas.voice(seed, spiral))
        // A rename onto a name that calls itself the Correspondent is the Correspondent.
        val toC = SynthPersonas.ensure(listOf(
            PersonaRow("Old", "", 7L, deleted = true, renamedTo = "The Correspondent — mine"),
            PersonaRow("The Correspondent — mine", "x", 7L)))
        assertEquals(SynthPersonas.Resolved("", ""), SynthPersonas.voice(toC, "Old"))
    }

    // ── A renamed persona owns its own turns, and the header counts it once ────────────────

    private val renamed = SynthPersonas.ensure(listOf(
        PersonaRow("Coach", "", 10L, deleted = true, renamedTo = "Coach — v2"),
        PersonaRow("Coach — v2", "Run coach.", 10L),
        PersonaRow("Gone", "", 11L, deleted = true),
    ))

    @Test
    fun `canonical follows renames and keeps a gone voice's own name`() {
        assertEquals("Coach — v2", SynthPersonas.canonical(renamed, "Coach"))
        assertEquals("Coach — v2", SynthPersonas.canonical(renamed, "Coach — v2"))
        assertEquals("Gone", SynthPersonas.canonical(renamed, "Gone"))
        assertEquals("Never Was", SynthPersonas.canonical(renamed, "Never Was"))
        assertEquals("", SynthPersonas.canonical(renamed, ""))
        assertEquals("", SynthPersonas.canonical(renamed, "The Correspondent"))
    }

    @Test
    fun `the transcript calls a renamed voice's old turns its own`() {
        val turns = listOf(
            SynthCorrespondence.Turn(1L, "ink", "a"),
            SynthCorrespondence.Turn(2L, "machine", "b", "Coach"),
            SynthCorrespondence.Turn(3L, "ink", "c"),
            SynthCorrespondence.Turn(4L, "machine", "d", ""),
        )
        val canon: (String) -> String = { SynthPersonas.canonical(renamed, it) }
        assertEquals("He wrote: a\nYou replied: b\nHe wrote: c\nThe Correspondent replied: d",
            SynthCorrespondence.transcriptTail(turns, "Coach — v2", 10, canon))
        assertEquals("He wrote: a\nCoach replied: b\nHe wrote: c\nYou replied: d",
            SynthCorrespondence.transcriptTail(turns, "", 10, canon))
        // Without a persona set the old reading stands (the golden fixture's cases).
        assertEquals("He wrote: a\nCoach replied: b\nHe wrote: c\nThe Correspondent replied: d",
            SynthCorrespondence.transcriptTail(turns, "Coach — v2", 10))
    }

    @Test
    fun `a page whose one voice was renamed and picked again is not answering next`() {
        val voices = listOf("Coach", "Coach — v2").map { SynthPersonas.canonical(renamed, it) }.distinct()
        assertEquals("❖ Coach", SynthVoice.header("Coach — v2", voices, ""))
        val two = listOf("", "Coach", "Coach — v2").map { SynthPersonas.canonical(renamed, it) }.distinct()
        assertEquals("❖ Coach · answering next", SynthVoice.header("Coach — v2", two, ""))
    }

    // ── Saving a built-in unchanged keeps it a seed ─────────────────────────────────────────

    @Test
    fun `saving an untouched built-in with its own text changes nothing`() {
        val rows = SynthPersonas.ensure(emptyList())
        val same = SynthPersonas.upsert(rows, Persona(spiral, PersonaStore.SPIRAL_PROMPT), 99L)
        assertEquals(0L, same.first { it.name == spiral }.at)
        assertEquals(rows, same)
        // A real edit is stamped…
        val edited = SynthPersonas.upsert(rows, Persona(spiral, "my spiral"), 100L)
        assertEquals(100L, edited.first { it.name == spiral }.at)
        // …and editing it BACK to the built-in text is an edit too (it must travel).
        val back = SynthPersonas.upsert(edited, Persona(spiral, PersonaStore.SPIRAL_PROMPT), 200L)
        assertEquals(200L, back.first { it.name == spiral }.at)
        // A deleted built-in re-made with its text is a new edit, not a seed.
        val dead = SynthPersonas.delete(rows, spiral, 300L)
        val again = SynthPersonas.upsert(dead, Persona(spiral, PersonaStore.SPIRAL_PROMPT), 400L)
        assertEquals(400L, again.first { it.name == spiral }.at)
        assertFalse(again.first { it.name == spiral }.deleted)
    }

    // ── The pending record carries the missing voice and the tail ──────────────────────────

    @Test
    fun `a record keeps the missing voice and its tail through the wire, and omits them when empty`() {
        val base = PendingSummon("id1", "2026-10-12", "synthesize#1", "delta", 1L, persona = "")
        val plain = SynthPending.toJson(base)
        assertFalse(plain.has("missing")); assertFalse(plain.has("tail")); assertFalse(plain.has("tailTurns"))
        assertEquals(base, SynthPending.fromJson(plain))
        val rich = base.copy(missing = spiral, tail = "He wrote: a\nYou replied: b", tailTurns = 10,
            content = "The correspondence so far, oldest first:\nHe wrote: a\nYou replied: b\n\nHe just wrote…")
        val json = SynthPending.toJson(rich)
        assertTrue(json.has("missing")); assertTrue(json.has("tail"))
        assertEquals(rich, SynthPending.fromJson(json))
        assertEquals(rich, SynthPending.parse(SynthPending.serialize(listOf(rich))).single())
    }

    // ── The header fits its room: smaller first, then the half that matters ────────────────

    /** Every character is half the size wide — a stand-in for Paint.measureText. */
    private val measure: (String, Float) -> Float = { t, size -> t.length * size * 0.5f }

    @Test
    fun `the header steps its size down before it cuts anything`() {
        val line = "❖ The Correspondent · Spiral isn't on this device"   // 49 characters
        assertEquals(SynthVoiceHeader.Fit(line, 25f), SynthVoiceHeader.fit(line, spiral, 49 * 12.5f, measure))
        assertEquals(SynthVoiceHeader.Fit(line, 19f), SynthVoiceHeader.fit(line, spiral, 49 * 9.5f, measure))
        // Too long at 19: the lead goes, the missing voice's name and "isn't on this device" stay.
        val short = "❖ Spiral isn't on this device"   // 29
        assertEquals(SynthVoiceHeader.Fit(short, 25f), SynthVoiceHeader.fit(line, spiral, 29 * 12.5f, measure))
        // Tighter still: the NAME is cut, never the end of the line.
        val cut = SynthVoiceHeader.fit(line, spiral, 27 * 9.5f, measure)
        assertEquals("❖ Spi… isn't on this device", cut.text)
        assertEquals(19f, cut.size)
        // A voiced line keeps " · answering next" whole.
        val voiced = SynthVoiceHeader.fit("❖ Spiral · answering next", "", 24 * 9.5f, measure)
        assertEquals("❖ Spir… · answering next", voiced.text)
        assertTrue(measure(voiced.text, voiced.size) <= 24 * 9.5f)
        // A line that fits is never touched.
        assertEquals(SynthVoiceHeader.Fit("❖ Spiral", 25f), SynthVoiceHeader.fit("❖ Spiral", "", 500f, measure))
    }
}
