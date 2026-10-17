package com.toolsboox

import com.toolsboox.plugin.calendar.ot.PendingSummon
import com.toolsboox.plugin.calendar.ot.PersonaRow
import com.toolsboox.plugin.calendar.ot.SynthCorrespondence
import com.toolsboox.plugin.calendar.ot.SynthPending
import com.toolsboox.plugin.calendar.ot.SynthPersonas
import com.toolsboox.plugin.calendar.ot.SynthVoice
import com.toolsboox.plugin.calendar.ot.SynthVoices
import com.toolsboox.plugin.calendar.ot.SynthWords
import com.toolsboox.plugin.calendar.ot.VoiceRow
import com.toolsboox.plugin.chat.nw.Persona
import com.toolsboox.plugin.chat.nw.PersonaStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ❖ SYNTH WITH A PERSONA — the parity pin (Michael, 2026-10-12: "you know I want parity").
 *
 * The persona set and the page voice both SYNC, so both apps must merge them to the same rows; and
 * every word either app says about a voice is one table. These fixtures are committed byte-
 * identically to both repos (`convergence-fixtures/synth-*.json`; iOS runs the same cases in
 * `Tests/LedgerCoreTests/SynthVoiceGoldenTests.swift`). Strings compare exactly; JSON results
 * compare field by field, keyed by name or by `date|key` — never byte for byte.
 */
class SynthVoiceGoldenTest {

    private fun fixture(name: String): String {
        javaClass.classLoader?.getResourceAsStream(name)?.use { return it.readBytes().decodeToString() }
        for (f in listOf(File("../convergence-fixtures/$name"), File("convergence-fixtures/$name"))) {
            if (f.exists()) return f.readText()
        }
        throw AssertionError("convergence fixture not found: $name")
    }

    private fun obj(name: String) = JSONObject(fixture(name))

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun rows(a: JSONArray): List<PersonaRow> = SynthPersonas.parse(a.toString())

    /** A persona row as it compares: an untouched built-in's prompt is THIS build's text. */
    private fun cmp(r: PersonaRow) = listOf(r.name, r.at.toString(), r.deleted.toString(), r.renamedTo, SynthPersonas.promptOf(r))

    private fun assertSameRows(why: String, expected: List<PersonaRow>, actual: List<PersonaRow>) {
        assertEquals("$why — the names", expected.map { it.name }.toSet(), actual.map { it.name }.toSet())
        val byName = actual.associateBy { it.name }
        for (e in expected) assertEquals("$why — ${e.name}", cmp(e), cmp(byName.getValue(e.name)))
    }

    // ── synth-voice-strings.json ────────────────────────────────────────────────────────────

    @Test
    fun `the built-ins, the Correspondent and ON THE PAGE are the pinned texts`() {
        val s = obj("synth-voice-strings.json")
        val builtIns = s.getJSONArray("builtIns")
        assertEquals(SynthPersonas.BUILT_IN.size, builtIns.length())
        for (i in 0 until builtIns.length()) {
            val b = builtIns.getJSONObject(i)
            assertEquals("built-in $i's name", b.getString("name"), SynthPersonas.BUILT_IN[i].name)
            assertEquals("${b.getString("name")}'s text", b.getString("prompt"), SynthPersonas.BUILT_IN[i].prompt)
        }
        assertEquals(s.getString("correspondentPrompt"), PersonaStore.CORRESPONDENT_PROMPT)
        assertEquals(s.getString("onThePage"), SynthVoice.ON_THE_PAGE)
        val menu = s.getJSONObject("menu")
        assertEquals(menu.getString("listTitle"), com.toolsboox.plugin.calendar.ui.CalendarDayFragment.SYNTH_WITH_TITLE)
        assertEquals(menu.getString("headerTitle"), com.toolsboox.plugin.calendar.ui.CalendarDayFragment.WHO_ANSWERS_TITLE)
        assertEquals(menu.getString("synthWithRow"), "Synth with ▸")
    }

    @Test
    fun `every voiced line is the table's line`() {
        val lines = obj("synth-voice-strings.json").getJSONArray("lines")
        for (i in 0 until lines.length()) {
            val c = lines.getJSONObject(i)
            val voice = c.getString("voice")
            val place = c.optString("place")
            val cont = if (c.has("continuedOnPage")) c.getInt("continuedOnPage") else null
            val got = when (val fn = c.getString("fn")) {
                "writingBack" -> SynthWords.writingBack(voice)
                "writtenBack" -> SynthWords.writtenBack(place, cont, voice)
                "voiceMissing" -> SynthWords.voiceMissing(voice, place)
                "answersNext" -> SynthWords.answersNext(voice)
                "conversationContinues" -> SynthWords.conversationContinues(voice)
                "writeBackRow" -> SynthVoice.writeBackRow(voice)
                "correspondRow" -> SynthVoice.correspondRow(voice)
                else -> throw AssertionError("unknown fn $fn")
            }
            assertEquals("${c.getString("fn")}($voice)", c.getString("expected"), got)
        }
        // The Correspondent's lines are today's, byte for byte.
        assertEquals(SynthWords.WRITING_BACK, SynthWords.writingBack(""))
        assertEquals(SynthWords.writtenBack("Synthesize", null), SynthWords.writtenBack("Synthesize", null, ""))
        assertEquals(SynthWords.CONVERSATION_CONTINUES, SynthWords.conversationContinues(""))
    }

    // ── synth-personas-*.json ───────────────────────────────────────────────────────────────

    @Test
    fun `the persona set merges to the expected rows, either way round`() {
        val a = SynthPersonas.parse(fixture("synth-personas-a.json"))
        val b = SynthPersonas.parse(fixture("synth-personas-b.json"))
        val expected = rows(obj("synth-personas-merged.expected.json").getJSONArray("merged"))
        assertSameRows("a + b", expected, SynthPersonas.merge(a, b))
        assertSameRows("b + a", expected, SynthPersonas.merge(b, a))
        // A merge of the merge changes nothing (idempotent — a second sync is quiet).
        val m = SynthPersonas.merge(a, b)
        assertEquals(m, SynthPersonas.merge(m, m))
    }

    @Test
    fun `the live order is the built-ins, then his own by name`() {
        val a = SynthPersonas.parse(fixture("synth-personas-a.json"))
        val b = SynthPersonas.parse(fixture("synth-personas-b.json"))
        val order = strings(obj("synth-personas-merged.expected.json").getJSONArray("order"))
        assertEquals(order, SynthPersonas.live(SynthPersonas.merge(a, b)).map { it.name })
    }

    @Test
    fun `the legacy list and its seed markers migrate to rows`() {
        val m = obj("synth-personas-merged.expected.json").getJSONObject("migrate")
        val legacyArr = m.getJSONArray("legacy")
        val legacy = (0 until legacyArr.length()).map {
            val o = legacyArr.getJSONObject(it); Persona(o.getString("name"), o.getString("prompt"))
        }
        val got = SynthPersonas.migrate(legacy, strings(m.getJSONArray("seeded")).toSet(), m.getLong("now"))
        assertSameRows("migrate", rows(m.getJSONArray("expected")), got)
    }

    @Test
    fun `an untouched seed speaks this build's text, an edit never loses to a seed`() {
        val seed = PersonaRow(PersonaStore.SPIRAL, "an older build's text", 0L)
        assertEquals(PersonaStore.SPIRAL_PROMPT, SynthPersonas.promptOf(seed))
        val edit = PersonaRow(PersonaStore.SPIRAL, "my spiral", 5L)
        assertEquals("my spiral", SynthPersonas.live(SynthPersonas.merge(listOf(seed), listOf(edit)))
            .first { it.name == PersonaStore.SPIRAL }.prompt)
        // An edit stamped now is never older than the row it replaces (a skewed clock can't lose it).
        val future = listOf(PersonaRow("Coach", "x", 9_000L))
        assertEquals(9_001L, SynthPersonas.upsert(future, Persona("Coach", "y"), 10L).first { it.name == "Coach" }.at)
        // A rename's tombstone points onward.
        val renamed = SynthPersonas.upsert(SynthPersonas.delete(future, "Coach", 10_000L, renamedTo = "Coach — the run-up"),
            Persona("Coach — the run-up", "y"), 10_000L)
        assertEquals("Coach — the run-up", SynthPersonas.resolve(renamed, "Coach"))
    }

    // ── synth-voices-*.json ─────────────────────────────────────────────────────────────────

    private fun cmpVoice(r: VoiceRow) = listOf(r.date, r.key, r.persona, r.at.toString(), r.voices.joinToString("|"))

    @Test
    fun `the page voices merge to the expected rows, newest first`() {
        val a = SynthVoices.parse(fixture("synth-voices-a.json"))
        val b = SynthVoices.parse(fixture("synth-voices-b.json"))
        val expected = SynthVoices.parse(fixture("synth-voices-merged.expected.json"))
        for ((why, got) in listOf("a + b" to SynthVoices.merge(a, b), "b + a" to SynthVoices.merge(b, a))) {
            assertEquals("$why — the pages", expected.map { SynthVoices.idOf(it) }.toSet(), got.map { SynthVoices.idOf(it) }.toSet())
            val byId = got.associateBy { SynthVoices.idOf(it) }
            for (e in expected) assertEquals("$why — ${SynthVoices.idOf(e)}", cmpVoice(e), cmpVoice(byId.getValue(SynthVoices.idOf(e))))
            assertEquals("$why — newest first", got.sortedByDescending { it.at }.map { it.at }, got.map { it.at })
        }
    }

    @Test
    fun `a voice is set on the document, joins the history, and the cap keeps the newest 200`() {
        // The sub-page is stripped at write and at read.
        var rows = SynthVoices.set(emptyList(), "2026-10-12", "synthesize#2", "Spiral", 100L, hasReply = true)
        assertEquals(listOf(VoiceRow("2026-10-12", "synthesize", "Spiral", 100L, listOf("", "Spiral"))), rows)
        assertEquals("Spiral", SynthVoices.rowFor(rows, "2026-10-12", "synthesize#5")?.persona)
        // A page with no reply card yet starts with the voice alone.
        assertEquals(listOf("Spiral"), SynthVoices.set(emptyList(), "2026-10-12", "synthesize", "Spiral", 1L, false)[0].voices)
        // Joining keeps the first voice; going back to the Correspondent travels as "".
        rows = SynthVoices.set(rows, "2026-10-12", "synthesize", "Interviewer", 200L, false)
        rows = SynthVoices.set(rows, "2026-10-12", "synthesize", "", 300L, false)
        assertEquals(VoiceRow("2026-10-12", "synthesize", "", 300L, listOf("", "Spiral", "Interviewer")), rows[0])
        // Eight voices at most — the page's first one stays.
        var many = SynthVoices.set(emptyList(), "d", "synthesize", "v0", 1L, false)
        for (i in 1..10) many = SynthVoices.set(many, "d", "synthesize", "v$i", 1L + i, false)
        assertEquals(listOf("v0", "v4", "v5", "v6", "v7", "v8", "v9", "v10"), many[0].voices)
        // 200 rows, newest first.
        var lots = emptyList<VoiceRow>()
        for (i in 0 until 230) lots = SynthVoices.set(lots, "2026-01-01", "synthesize-$i", "Spiral", 1_000L + i, false)
        assertEquals(SynthVoices.MAX_ROWS, lots.size)
        assertEquals("synthesize-229", lots.first().key)
        assertFalse(lots.any { it.key == "synthesize-29" })
        assertEquals(SynthVoices.MAX_ROWS, SynthVoices.merge(lots, lots).size)
    }

    // ── synth-voice-cases.json ──────────────────────────────────────────────────────────────

    @Test
    fun `base keys, short names, labels and headers are the table's`() {
        val c = obj("synth-voice-cases.json")
        c.getJSONArray("baseKeys").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                assertEquals(it.getString("base"), SynthVoice.base(it.getString("key")))
                assertEquals(it.getString("base"), SynthVoices.base(it.getString("key")))
            }
        }
        c.getJSONArray("shortName").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                assertEquals(it.getString("short"), SynthVoice.shortName(it.getString("name")))
            }
        }
        c.getJSONArray("label").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                assertEquals(it.getString("label"), SynthVoice.label(it.getString("name")))
            }
        }
        c.getJSONArray("header").let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                assertEquals(it.getString("expected"),
                    SynthVoice.header(it.getString("persona"), strings(it.getJSONArray("voices")), it.getString("missing")))
            }
        }
    }

    @Test
    fun `a reply is a reply on its own conversation, a breadcrumb is not`() {
        val a = obj("synth-voice-cases.json").getJSONArray("isReply")
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            assertEquals(it.getString("why"), it.getBoolean("expected"),
                SynthVoice.isReply(it.getString("label"), it.getString("link"), it.getString("page")))
        }
    }

    @Test
    fun `the voice list wears a check only on a Synthesize page`() {
        val c = obj("synth-voice-cases.json")
        val lists = c.getJSONArray("voiceList")
        for (i in 0 until lists.length()) lists.getJSONObject(i).let {
            val live = strings(it.getJSONArray("names")).map { n -> Persona(n, "a prompt") }
            val current = if (it.isNull("current")) null else it.getString("current")
            assertEquals(strings(it.getJSONArray("expected")), SynthVoice.voiceRows(live, current).map { r -> r.text })
        }
        val order = c.getJSONObject("order")
        val live = SynthPersonas.live(rows(order.getJSONArray("rows")))
        assertEquals(strings(order.getJSONArray("order")), live.map { it.name })
        val rowsShown = SynthVoice.voiceRows(live, null)
        assertEquals(SynthVoice.CORRESPONDENT, rowsShown.first().persona)
        assertEquals(strings(order.getJSONArray("voiceList")), rowsShown.drop(1).map { it.persona })
    }

    @Test
    fun `a page's voice resolves through renames, and a missing one says so`() {
        val r = obj("synth-voice-cases.json").getJSONObject("resolve")
        val rows = rows(r.getJSONArray("rows"))
        val cases = r.getJSONArray("cases")
        for (i in 0 until cases.length()) cases.getJSONObject(i).let {
            val got = SynthPersonas.voice(rows, it.getString("wanted"))
            assertEquals("wanted ${it.getString("wanted")}", it.getString("persona"), got.persona)
            assertEquals("wanted ${it.getString("wanted")}", it.getString("missing"), got.missing)
        }
    }

    // ── synth-prompt-cases.json ─────────────────────────────────────────────────────────────

    @Test
    fun `the system prompt is the Correspondent's verbatim, or the persona's and ON THE PAGE`() {
        val p = obj("synth-prompt-cases.json").getJSONArray("systemFor")
        for (i in 0 until p.length()) p.getJSONObject(i).let {
            val persona = it.getString("persona")
            val prompt = SynthPersonas.builtInPrompt(persona).orEmpty()
            assertEquals("systemFor($persona)", it.getString("expected"), SynthVoice.systemFor(persona, prompt))
        }
    }

    @Test
    fun `the transcript names each voice — a joining voice never claims another's words`() {
        val t = obj("synth-prompt-cases.json").getJSONArray("transcriptTail")
        for (i in 0 until t.length()) t.getJSONObject(i).let {
            val ta = it.getJSONArray("turns")
            val turns = (0 until ta.length()).map { j -> SynthCorrespondence.turnFrom(ta.getJSONObject(j)) }
            val n = if (it.has("count")) it.getInt("count") else 10
            assertEquals(it.getString("why"), it.getString("expected"), SynthCorrespondence.transcriptTail(turns, it.getString("voice"), n))
        }
    }

    @Test
    fun `a Correspondent turn is written with no persona key — today's bytes`() {
        val k = obj("synth-prompt-cases.json").getJSONObject("turnKeys")
        val plain = SynthCorrespondence.turnJson(SynthCorrespondence.Turn(1L, "machine", "a reply"))
        assertEquals(strings(k.getJSONArray("correspondent")), plain.keys().asSequence().toList().sorted())
        val voiced = SynthCorrespondence.turnJson(SynthCorrespondence.Turn(1L, "machine", "a reply", PersonaStore.SPIRAL))
        assertEquals(strings(k.getJSONArray("persona")), voiced.keys().asSequence().toList().sorted())
        assertEquals(PersonaStore.SPIRAL, SynthCorrespondence.turnFrom(voiced).persona)
        assertEquals("", SynthCorrespondence.turnFrom(plain).persona)
    }

    @Test
    fun `a pending record carries its voice, and an old record reads as the Correspondent`() {
        val p = obj("synth-prompt-cases.json").getJSONArray("pending")
        for (i in 0 until p.length()) p.getJSONObject(i).let {
            val rec: PendingSummon = SynthPending.fromJson(it.getJSONObject("json"))!!
            assertEquals(it.getString("persona"), rec.persona)
            assertEquals(rec, SynthPending.fromJson(SynthPending.toJson(rec)))
            assertTrue(SynthPending.toJson(rec).has("persona"))
        }
    }

    /**
     * THE MERGED WIRE SHAPE, pinned by the shared bytes (review of be907511 / 7d6f94fa, item 2): the
     * persona fields (persona, missing, tail/tailTurns) and the list run's (request, sources,
     * pickDate) on one record. Each case names the exact keys `toJson` writes — persona, request,
     * sources and pickDate always; missing and the tail only when they hold something — and every
     * field the case's record carries must come back out under its own name with its own value, so
     * neither app can rename, drop or start always-writing a field without this suite (and iOS's
     * twin in SynthVoiceGoldenTests) going red.
     */
    @Test
    fun `a pending record writes the one wire shape both apps share, every field back as it came`() {
        val p = obj("synth-prompt-cases.json").getJSONArray("pending")
        var pinned = 0
        for (i in 0 until p.length()) p.getJSONObject(i).let {
            val json = it.getJSONObject("json")
            val out = SynthPending.toJson(SynthPending.fromJson(json)!!)
            val id = json.getString("id")
            if (it.has("keys")) {
                assertEquals("$id — the keys toJson writes", strings(it.getJSONArray("keys")), out.keys().asSequence().toList().sorted())
                pinned++
            }
            for (k in json.keys()) {
                assertTrue("$id — $k is written back", out.has(k))
                assertEquals("$id — $k", json.get(k).toString(), out.get(k).toString())
            }
        }
        assertTrue("a case carries both field sets", (0 until p.length()).any { i ->
            p.getJSONObject(i).getJSONObject("json").let { j ->
                listOf("persona", "missing", "tail", "tailTurns", "request", "sources", "pickDate")
                    .all { k -> j.has(k) && j.get(k).toString().let { v -> v.isNotEmpty() && v != "0" } }
            }
        })
        assertEquals("every pending case names its keys", p.length(), pinned)
    }
}
