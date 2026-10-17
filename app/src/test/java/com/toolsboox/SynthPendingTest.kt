package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.da.TextElement
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.DiagnosticsLog
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.SynthCorrespondence
import com.toolsboox.plugin.calendar.ot.PendingSummon
import com.toolsboox.plugin.calendar.ot.SynthFace
import com.toolsboox.plugin.calendar.ot.SynthLanded
import com.toolsboox.plugin.calendar.ot.SynthLanding
import com.toolsboox.plugin.calendar.ot.SynthPending
import com.toolsboox.plugin.calendar.ot.SynthVoice
import com.toolsboox.plugin.calendar.ot.SynthWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * THE ❖ SUMMON THAT MUST NOT VANISH (Michael, 2026-10-12, B6 -322: "It says reading. It says writing
 * back. It does not put in it's write back pic." — and "you know I want parity").
 *
 * Pins the pure half of the fix: the pending-summon store (a record written before the network call
 * so a killed / rebooted / reinstalled app can re-ask or land on the next foreground), the resume
 * ladder, the ONE placement both the live page and the day file use (below everything, flowing to
 * the next sub-page, never past the canvas foot), the day-file landing — including the field bug
 * itself's heir, a misconfigured CalendarDayService that reads every day as unreadable and writes nothing — and
 * the words both apps say.
 */
class SynthPendingTest {

    private val date = LocalDate.of(2026, 10, 12)

    private fun rec(
        id: String = UUID.randomUUID().toString(), stage: String = SynthPending.ASKING,
        content: String = "He just wrote this in ink — THIS is the turn you are answering:\nhello",
        attempts: Int = 1, answer: String = "", startedAt: Long = 1_000_000L, notice: String = "",
    ) = PendingSummon(
        id = id, date = date.toString(), pageKey = "synthesize", mode = "delta", startedAt = startedAt,
        stage = stage, pageName = "Synthesize", content = content, recordText = "hello",
        seen = listOf("a-1", "b-2"), attempts = attempts, answer = answer, notice = notice,
    )

    private fun service(): CalendarDayService =
        CalendarDayService(Moshi.Builder().add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build())

    private fun root(): File = Files.createTempDirectory("synth-pending").toFile()

    private fun day(): CalendarDay = CalendarDay(year = date.year, month = date.monthValue, day = date.dayOfMonth,
        locale = Locale.US, startHour = 7)

    /** A stroke down the page to [toY] on [key] — ink the reply must never sit over. */
    private fun ink(day: CalendarDay, key: String, toY: Float) {
        day.noteStrokes[key] = day.noteStrokes[key].orEmpty() + Stroke(
            strokeId = UUID.randomUUID(),
            strokePoints = listOf(StrokePoint(200f, 160f, 1f), StrokePoint(240f, toY, 1f)))
    }

    private fun face(text: String, aspect: Float = 0.5f) = SynthFace(text, "iVBORw0K", aspect)

    // ── The store ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pending record round-trips through the wire whole`() {
        val r = rec(answer = "A reply.\n\nWith ❖ and “quotes”.", notice = "")
        val back = SynthPending.parse(SynthPending.serialize(listOf(r)))
        assertEquals(listOf(r), back)
        // ❖ Synthesize this list's fields ride too — the pick's day included, so a landing resumed
        // on a later day finds the pick it already placed.
        val list = r.copy(mode = "list", request = "{\"kind\":\"feeds\"}", sources = "[1] A <https://a.example/>",
            pickDate = "2026-10-12")
        assertEquals(listOf(list), SynthPending.parse(SynthPending.serialize(listOf(list))))
    }

    @Test
    fun `one record carries both the voice and the list run - the wire shape both apps write`() {
        // A follow-up in Spiral's voice on a page born from a list (merge of synth-personas and
        // synth-list-links, 2026-10-16): the voice, the gone voice, the tail AND the page's numbers.
        val both = rec(id = "both").copy(persona = com.toolsboox.plugin.chat.nw.PersonaStore.SPIRAL,
            missing = "Coach — the run-up", tail = "He wrote: hello", tailTurns = 10,
            request = "{\"kind\":\"feeds\"}", sources = "[1] A <https://a.example/>", pickDate = "2026-10-13")
        assertEquals(listOf(both), SynthPending.parse(SynthPending.serialize(listOf(both))))
        val o = SynthPending.toJson(both)
        for (k in listOf("persona", "missing", "tail", "tailTurns", "request", "sources", "pickDate")) assertTrue(k, o.has(k))
        // The shape iOS's toJSON writes: persona, request, sources and pickDate always; missing and
        // the tail only when they hold something.
        val plain = SynthPending.toJson(rec(id = "plain"))
        for (k in listOf("persona", "request", "sources", "pickDate")) assertTrue(k, plain.has(k))
        for (k in listOf("missing", "tail", "tailTurns")) assertFalse(k, plain.has(k))
        // Either generation's record reads, the other's fields defaulting — tolerant both ways.
        val personasOnly = """[{"id":"p","date":"2026-10-12","key":"synthesize","stage":"asking","content":"c","persona":"Spiral — what came back around","tail":"t","tailTurns":6}]"""
        val p = SynthPending.parse(personasOnly).single()
        assertEquals("Spiral — what came back around", p.persona)
        assertEquals(6, p.tailTurns)
        assertEquals("", p.request); assertEquals("", p.sources); assertEquals("", p.pickDate)
        val listOnly = """[{"id":"l","date":"2026-10-12","key":"synthesize-1760290000000","mode":"list","stage":"replied","answer":"a","request":"{}","pickDate":"2026-10-12"}]"""
        val l = SynthPending.parse(listOnly).single()
        assertEquals("2026-10-12", l.pickDate)
        assertEquals("", l.persona); assertEquals("", l.missing); assertEquals(0, l.tailTurns)
    }

    @Test
    fun `the parse is tolerant — garbage and keyless rows are dropped, never fatal`() {
        assertTrue(SynthPending.parse("not json").isEmpty())
        assertTrue(SynthPending.parse("").isEmpty())
        val mixed = """[{"id":"x","date":"2026-10-12"},{"id":"y","date":"2026-10-12","key":"synthesize"},7]"""
        val back = SynthPending.parse(mixed)
        assertEquals(1, back.size)
        assertEquals("y", back[0].id)
        assertEquals(SynthPending.READING, back[0].stage)   // a row with no stage reads as the stub
    }

    @Test
    fun `the file store puts, replaces by id, and clears to nothing`() {
        val f = File(root(), "pending.json")
        val a = rec(id = "a"); val b = rec(id = "b")
        SynthPending.put(f, a)
        SynthPending.put(f, b)
        SynthPending.put(f, a.copy(stage = SynthPending.REPLIED, answer = "back"))
        val all = SynthPending.all(f)
        assertEquals(setOf("a", "b"), all.map { it.id }.toSet())
        assertEquals("back", all.first { it.id == "a" }.answer)
        SynthPending.remove(f, "a")
        SynthPending.remove(f, "b")
        assertTrue(SynthPending.all(f).isEmpty())
        assertFalse("an empty store leaves no file", f.exists())
        assertFalse("no temp file is stranded", File(f.parentFile, "pending.json.tmp").exists())
    }

    // ── The resume ladder ───────────────────────────────────────────────────────────────────

    @Test
    fun `resume — a summon this process still carries is left alone`() {
        val r = rec(id = "live")
        assertEquals(SynthPending.Action.WAIT, SynthPending.actionFor(r, setOf("live"), 2_000_000L))
    }

    @Test
    fun `resume — a reply that came back before the kill is landed, not re-asked, however old`() {
        val r = rec(stage = SynthPending.REPLIED, answer = "the reply", startedAt = 0L)
        assertEquals(SynthPending.Action.LAND, SynthPending.actionFor(r, emptySet(), SynthPending.MAX_AGE_MS * 10))
    }

    @Test
    fun `resume — a request in flight at the kill is asked again, once`() {
        assertEquals(SynthPending.Action.ASK, SynthPending.actionFor(rec(attempts = 1), emptySet(), 2_000_000L))
        assertEquals(SynthPending.Action.GIVE_UP,
            SynthPending.actionFor(rec(attempts = SynthPending.MAX_ATTEMPTS), emptySet(), 2_000_000L))
    }

    @Test
    fun `resume — killed mid-read is cut off, too old is expired, owed words are said`() {
        assertEquals(SynthPending.Action.CUT_OFF,
            SynthPending.actionFor(rec(stage = SynthPending.READING, content = ""), emptySet(), 2_000_000L))
        assertEquals(SynthPending.Action.EXPIRED,
            SynthPending.actionFor(rec(startedAt = 0L), emptySet(), SynthPending.MAX_AGE_MS + 1))
        assertEquals(SynthPending.Action.SAY,
            SynthPending.actionFor(rec(stage = SynthPending.NOTICE, notice = "❖ Written back on Synthesize."), emptySet(), 0L))
    }

    @Test
    fun `the reply bound is the same two minutes on both apps`() {
        assertEquals(120_000L, SynthPending.REPLY_TIMEOUT_MS)
        // …and the site-reference lookup gets 25 s of it, never the whole (iOS referencesBudget).
        assertEquals(25_000L, SynthPending.REFERENCES_BUDGET_MS)
    }

    @Test
    fun `re-ask — a lost connection is asked again wherever he is, once`() {
        val lost = SynthPending.Failure.LOST_CONNECTION
        assertTrue(SynthPending.reasks(lost, away = false, attempts = 1))
        assertTrue(SynthPending.reasks(lost, away = true, attempts = 1))
        assertFalse(SynthPending.reasks(lost, away = true, attempts = SynthPending.MAX_ATTEMPTS))
    }

    @Test
    fun `re-ask — a timeout is his answer only when he was on the app, and a refusal never re-asks`() {
        val timeout = SynthPending.Failure.TIMEOUT
        assertFalse(SynthPending.reasks(timeout, away = false, attempts = 1))
        assertTrue(SynthPending.reasks(timeout, away = true, attempts = 1))
        assertFalse(SynthPending.reasks(timeout, away = true, attempts = 2))
        assertFalse(SynthPending.reasks(SynthPending.Failure.REFUSED, away = true, attempts = 1))
        assertFalse(SynthPending.reasks(SynthPending.Failure.REFUSED, away = false, attempts = 1))
    }

    @Test
    fun `one summon per page — a second tap finds the first still on its way`() {
        val first = rec(stage = SynthPending.ASKING)
        val carried = listOf(first)
        assertEquals(first, SynthPending.carrying(carried, date.toString(), "synthesize"))
        // Another page, another day, or only words still owed: free.
        assertNull(SynthPending.carrying(carried, date.toString(), "synthesize#1"))
        assertNull(SynthPending.carrying(carried, date.minusDays(1).toString(), "synthesize"))
        assertNull(SynthPending.carrying(listOf(rec(stage = SynthPending.NOTICE)), date.toString(), "synthesize"))
        assertEquals("❖ Still writing back on Synthesize…", SynthWords.stillWriting("Synthesize"))
    }

    // ── The cards ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a reply packs into card-sized chunks, a monster paragraph splits, blank stays one`() {
        val short = SynthPending.replyChunks("One.\n\nTwo.")
        assertEquals(listOf("One.\n\nTwo."), short)
        val monster = (1..60).joinToString(" ") { "Sentence number $it is here." }
        val chunks = SynthPending.replyChunks(monster)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 620 })
        assertEquals(monster.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertEquals(1, SynthPending.replyChunks("   ").size)
    }

    // ── The placement ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the reply lands below everything on its page — strokes, text and cards — and never over ink`() {
        val d = day()
        ink(d, "synthesize", 600f)
        d.textElements.add(TextElement(x = 100f, y = 650f, height = 60f, text = "typed", pageKey = "synthesize"))
        // Other pages' content must not push it down.
        ink(d, "pickings", 1500f)
        val landed = SynthLanding.place(d, "synthesize", listOf(face("a reply")), "ledger://$date/synthesize")
        assertEquals("synthesize", landed.firstKey)
        assertEquals(710f + SynthLanding.BELOW, landed.y, 0.01f)
        val card = d.imageElements.single()
        assertEquals("synthesize", card.page)
        assertEquals("❖ The Correspondent", card.sourceLabel)
        assertEquals("a reply", card.cardText)
        assertEquals("ledger://$date/synthesize", card.sourceLink)
    }

    @Test
    fun `a full page flows the reply to a fresh sub-page — never past the canvas foot`() {
        val d = day()
        ink(d, "synthesize", 1700f)
        ink(d, "synthesize#1", 300f)   // an existing sub-page: the flow takes the NEXT unused one
        val faces = listOf(face("first", 0.6f), face("second", 0.6f))
        val landed = SynthLanding.place(d, "synthesize", faces, "ledger://$date/synthesize")
        assertEquals("synthesize#2", landed.firstKey)
        assertEquals(SynthLanding.TOP, landed.y, 0.01f)
        assertEquals("synthesize#2", landed.lastKey)
        assertEquals(2, landed.cards)
        assertTrue(d.imageElements.all { it.y + it.height <= SynthLanding.FOOT })
        assertNull("stayed on its first page", SynthWords.continuedOn(landed))
    }

    @Test
    fun `a long reply that overruns the page continues on the next one and says so`() {
        val d = day()
        ink(d, "synthesize", 900f)
        val faces = listOf(face("one", 1.0f), face("two", 1.0f))   // 560 tall each
        val landed = SynthLanding.place(d, "synthesize", faces, "ledger://$date/synthesize")
        assertEquals("synthesize", landed.firstKey)
        assertEquals("synthesize#1", landed.lastKey)
        assertEquals(2, SynthWords.continuedOn(landed))
        assertTrue(d.imageElements.all { it.y + it.height <= SynthLanding.FOOT })
    }

    @Test
    fun `a resumed landing whose first try already stood is recognised, not doubled`() {
        val d = day()
        ink(d, "synthesize", 400f)
        val faces = listOf(face("Yes."), face("And more."))
        assertNull(SynthLanding.alreadyLanded(d, "synthesize", faces))
        SynthLanding.place(d, "synthesize", faces, "ledger://$date/synthesize")
        val again = SynthLanding.alreadyLanded(d, "synthesize", faces)
        assertNotNull(again)
        assertEquals("synthesize", again!!.firstKey)
        // Flagged, so the trail says "already standing — not landed twice" rather than "landed".
        assertTrue(again.already)
        // A different reply that merely shares a short first card is NOT the same landing.
        assertNull(SynthLanding.alreadyLanded(d, "synthesize", listOf(face("Yes."), face("Something new."))))
    }

    @Test
    fun `a persona's reply lands under its own name, and a resume finds it as that voice`() {
        val d = day()
        ink(d, "synthesize", 400f)
        val faces = listOf(face("It came back around."), face("Look again."))
        val spiral = SynthVoice.label(com.toolsboox.plugin.chat.nw.PersonaStore.SPIRAL)
        val landed = SynthLanding.place(d, "synthesize#1", faces, "ledger://$date/synthesize#1", spiral)
        val card = d.imageElements.last()
        assertEquals("❖ Spiral", card.sourceLabel)
        assertEquals("synthesize#1", landed.firstKey)
        // A reply on its own conversation — it threads like the Correspondent's.
        assertTrue(SynthVoice.isReply(card.sourceLabel, card.sourceLink, card.page))
        // Found again as Spiral; never mistaken for a Correspondent reply.
        assertNotNull(SynthLanding.alreadyLanded(d, "synthesize", faces, spiral))
        assertNull(SynthLanding.alreadyLanded(d, "synthesize", faces))
    }

    @Test
    fun `the pending store keeps the voice the summon was put to`() {
        val f = File(root(), "pending.json")
        SynthPending.put(f, rec(id = "v").copy(persona = com.toolsboox.plugin.chat.nw.PersonaStore.SPIRAL))
        assertEquals(com.toolsboox.plugin.chat.nw.PersonaStore.SPIRAL, SynthPending.all(f).single().persona)
        // A record written before personas reads as the Correspondent.
        val old = """[{"id":"o","date":"2026-10-12","key":"synthesize","mode":"delta","stage":"asking","content":"c"}]"""
        assertEquals("", SynthPending.parse(old).single().persona)
    }

    @Test
    fun `a drive-by's long reply flows onto the topic's next sub-page — never past the foot`() {
        val opening = ImageElement(x = 90f, y = 140f, width = 1000f, height = 700f, data = "",
            page = "synthesize-1791826118000", sourceLabel = "circled on $date")
        // Four tall faces (504 each): one fits under the opening card's foot at 840, three flow on.
        val faces = (1..4).map { face("Card $it.", aspect = 0.9f) }
        val (cards, landed) = SynthLanding.placeUnder(date, opening, faces, "ledger://$date/synthesize-1791826118000")
        assertEquals(4, cards.size)
        assertEquals(4, landed.cards)
        assertEquals(1, cards.count { it.page == "synthesize-1791826118000" })
        assertTrue(cards.all { it.y + it.height <= SynthLanding.FOOT })
        assertTrue(cards.first().y >= opening.y + opening.height)
        assertEquals("synthesize-1791826118000", landed.firstKey)
        assertEquals("synthesize-1791826118000#1", landed.lastKey)
        assertTrue(cards.all { it.sourceLabel == "❖ The Correspondent" })
        assertFalse(cards.contains(opening))
    }

    @Test
    fun `a resumed landing records its turn once — a fresh summon that repeats itself still counts`() {
        val row = SynthCorrespondence.Row(date.toString(), "synthesize", 0L, mutableSetOf(), mutableListOf(
            SynthCorrespondence.Turn(1L, "ink", "hello"), SynthCorrespondence.Turn(2L, "machine", "A reply.")))
        assertTrue(SynthCorrespondence.isRepeat(row, "hello", "A reply."))
        assertFalse(SynthCorrespondence.isRepeat(row, "hello again", "A reply."))
        assertFalse(SynthCorrespondence.isRepeat(row, "hello", "Another reply."))
        assertFalse(SynthCorrespondence.isRepeat(null, "hello", "A reply."))
    }

    // ── The day-file landing — and the field bug ────────────────────────────────────────────

    @Test
    fun `the detached landing writes the reply into the day file, below the ink`() {
        val root = root()
        val svc = service()
        val d = day(); ink(d, "synthesize", 800f)
        svc.save(root, date, d)

        val landed = SynthLanding.landInDayFile(svc, root, date, "synthesize", listOf(face("from the day file")))
        assertNotNull(landed)
        val back = service().loadOrNull(root, date, Locale.US)!!
        val card = back.imageElements.single { it.sourceLabel == "❖ The Correspondent" }
        assertEquals("synthesize", card.page)
        assertEquals("from the day file", card.cardText)
        assertTrue(card.y >= 800f + SynthLanding.BELOW)
        // The ink is untouched.
        assertEquals(1, back.noteStrokes["synthesize"]!!.size)
    }

    /**
     * THE -322 FIELD BUG, pinned: the detached landing used a bare `CalendarDayService()`. Its Moshi
     * was field-injected, so the load threw "lateinit property moshi has not been initialized", the
     * service read that as a corrupt day, and the reply was never written — silently. The bare
     * constructor no longer exists (the Moshi is a constructor value); the nearest thing left is a
     * service handed the WRONG Moshi (no ledger adapters). It must land NOTHING, never half-write —
     * and say so to its caller with the day-file guard's refusal (UnreadableDayException, naming the
     * page), which is what keeps the reply owed instead of dropped (SynthSummons.land).
     */
    @Test
    fun `a misconfigured day service — the field bug's heir — lands nothing and says so with the refusal`() {
        val root = root()
        val d = day(); ink(d, "synthesize", 800f)
        service().save(root, date, d)
        val before = File(root, "calendar/2026/10/day-2026-10-12-v2.json").readText()

        val wrong = CalendarDayService(Moshi.Builder().build())   // no Locale/Date/UUID adapters
        assertNull("the misconfigured service can't read the day it must write", wrong.loadOrNull(root, date, Locale.US))
        val refused = runCatching { SynthLanding.landInDayFile(wrong, root, date, "synthesize", listOf(face("lost"))) }
            .exceptionOrNull()
        assertTrue("refused, not landed: $refused", refused is CalendarDayService.UnreadableDayException)
        assertEquals("the day file is untouched", before,
            File(root, "calendar/2026/10/day-2026-10-12-v2.json").readText())
    }

    /** A day that isn't on this device: nothing landed and nothing minted — null, as before. */
    @Test
    fun `the detached landing on a day that isn't here lands nothing and mints no day`() {
        val root = root()
        assertNull(SynthLanding.landInDayFile(service(), root, date, "synthesize", listOf(face("nowhere"))))
        assertFalse(service().exists(root, date))
    }

    /** The words of a reply kept because its page won't read — the guard's "Couldn't … — <page>
     *  won't read" shape, and the promise that it lands later. */
    @Test
    fun `a reply whose page won't read is kept, and the words say so`() {
        assertEquals("❖ Couldn't write back on Synthesize — today's page won't read. The reply is kept; it lands once the page reads.",
            SynthWords.replyKept("Synthesize", "today's page"))
        // A drive-by is refused before its ask — the guard's own line (iOS's, string for string).
        assertEquals("❖ Couldn't write back — the Oct 3 page won't read", SynthWords.driveByRefused("the Oct 3 page"))
        assertEquals("❖ Couldn't add the cited cards — today's page won't read. They're kept and land once it does.",
            SynthWords.citedCardsKept("today's page"))
    }

    // ── The words (both apps) ───────────────────────────────────────────────────────────────

    @Test
    fun `the words say where — page, sub-page, and the day only when it is not the one in view`() {
        assertEquals("Synthesize", SynthWords.place("Synthesize", "synthesize", date, date))
        assertEquals("Synthesize · page 2", SynthWords.place("Synthesize", "synthesize#1", date, date))
        assertEquals("Synthesize · Oct 11", SynthWords.place("Synthesize", "synthesize", date.minusDays(1), date))
        assertEquals("Synthesize · Dec 31, 2025",
            SynthWords.place("Synthesize", "synthesize", LocalDate.of(2025, 12, 31), date))
        // A named topic is reached by its name, never by its home day.
        assertEquals("Hip hinge · page 3",
            SynthWords.place("Hip hinge", "synthesize-1791826118000#2", date.minusDays(9), date))

        assertEquals("❖ Written back on Synthesize.", SynthWords.writtenBack("Synthesize"))
        assertEquals("❖ Written back on Synthesize — continued on page 2.", SynthWords.writtenBack("Synthesize", 2))
        assertEquals("❖ No reply came back — try the summon again.", SynthWords.NO_REPLY)
        assertEquals("❖ No reply came back for Synthesize · Oct 11 — try the summon again.",
            SynthWords.noReply("Synthesize · Oct 11"))
        assertEquals("❖ The reply came back but couldn't be written on Synthesize — try the summon again.",
            SynthWords.couldNotWrite("Synthesize"))
        assertEquals("❖ The summon on Synthesize stopped before it read the page — try it again.",
            SynthWords.cutOff("Synthesize"))
        assertEquals("Synthesize", SynthWords.pageName("synthesize", null))
        assertEquals("Synthesize", SynthWords.pageName("synthesize-1791826118000", ""))
        assertEquals("Hip hinge", SynthWords.pageName("synthesize-1791826118000", "Hip hinge"))
        assertEquals(SynthLanded("synthesize", 0f, 0f, 0f, 0f, "synthesize#3", 2).let(SynthWords::continuedOn), 4)
    }

    @Test
    fun `the rest of the summon's words are one table — no app keeps its own literal`() {
        assertEquals("❖ Write something first — there's no new ink to read.", SynthWords.NOTHING_NEW)
        assertEquals("❖ That ink didn't come back readable — try underlining it.", SynthWords.INK_UNREADABLE)
        assertEquals("❖ No AI key set — Settings › Ask my Ledger.", SynthWords.noKey(SynthWords.AI_KEY_ROOM_ANDROID))
        assertEquals("❖ “Hip hinge” — the reply is on your page; open the sticky to continue.",
            SynthWords.driveByHere("Hip hinge"))
        // Another day reads as the day, never as an ISO date.
        assertEquals("❖ “Hip hinge” — the sticky is on Oct 11.",
            SynthWords.driveByElsewhere("Hip hinge", SynthWords.day(date.minusDays(1), date)))
        assertEquals("Dec 31, 2025", SynthWords.day(LocalDate.of(2025, 12, 31), date))
        // Several owed lines are one message, a line each — never each replacing the last.
        assertEquals("❖ Written back on Synthesize.\n❖ No reply came back for Hip hinge — try the summon again.",
            SynthWords.joined(listOf("❖ Written back on Synthesize.", "", "❖ No reply came back for Hip hinge — try the summon again.")))
    }

    @Test
    fun `the summon's log trail is kept by the diagnostics ring, not left to logcat`() {
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "SynthSummons", "synth[ab12cd34] request sent page=synthesize"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "CalendarDayFragment", "synth[ab12cd34] start page=synthesize"))
    }

    @Test
    fun `a stroke-free page still lands its reply at the top margin, not at zero`() {
        val d = day()
        d.imageElements.add(ImageElement(x = 0f, y = 0f, width = 10f, height = 10f, data = "", page = "other"))
        val landed = SynthLanding.place(d, "synthesize", listOf(face("x")), "ledger://$date/synthesize")
        assertEquals(SynthLanding.TOP, landed.y, 0.01f)
    }
}
