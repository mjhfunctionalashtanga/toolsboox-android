package com.toolsboox

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.da.Attachment
import com.toolsboox.da.FaceSlot
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayMerger
import com.toolsboox.plugin.calendar.ot.AddToPickGolden
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import com.toolsboox.plugin.calendar.ot.ArticlePickIndex
import com.toolsboox.plugin.calendar.ot.PickFaceLayout
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.reader.ui.BookNote
import com.toolsboox.plugin.reader.ui.MarkFace
import com.toolsboox.plugin.reader.ui.MarkJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale

/**
 * ONE RECORD (add-to-pick pin §3): the mark's `face`, the pick's `faceSlots` and the line's
 * attachments decode and re-encode unchanged (the golden's [records], held the same on iOS), and the
 * day merge pools a line's attachments instead of racing them.
 */
class AddToPickRecordsTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()

    /** Keys sorted, no spaces, numbers as org.json prints them — the golden's canonical JSON. */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Number -> JSONObject.numberToString(v)
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }

    private fun canonText(json: String): String =
        canon(if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json))

    @Test
    fun `the samples are canonical`() {
        for (s in listOf(AddToPickGolden.RECORD_FACE, AddToPickGolden.RECORD_SLOTS, AddToPickGolden.RECORD_LINE))
            assertEquals(s, canonText(s))
    }

    @Test
    fun `a mark's face decodes and re-encodes unchanged - absent is free, shrink, picture on`() {
        val face = MarkFace.fromJson(JSONObject(AddToPickGolden.RECORD_FACE))!!
        assertEquals(MarkFace("portrait", "pullquote", false), face)
        assertEquals(AddToPickGolden.RECORD_FACE, canon(face.toJson()))
        assertNull(MarkFace.fromJson(null))
        assertEquals(MarkFace("free", "shrink", true), MarkFace.fromJson(JSONObject("{}")))
    }

    @Test
    fun `a pick's face slots decode and re-encode unchanged`() {
        val type = Types.newParameterizedType(List::class.java, FaceSlot::class.java)
        val adapter = moshi.adapter<List<FaceSlot>>(type)
        val slots = adapter.fromJson(AddToPickGolden.RECORD_SLOTS)!!
        assertEquals(listOf("photo", "voice"), slots.map { it.k })
        assertEquals(AddToPickGolden.RECORD_SLOTS, canonText(adapter.toJson(slots)))
        // A tap at the photo's middle hits it; outside every slot hits nothing.
        assertTrue(slots[0].hit(500f, 560f, 1000f, 1000f))
        assertFalse(slots[0].hit(50f, 50f, 1000f, 1000f))
    }

    @Test
    fun `an old card has no face slots and writes none`() {
        val el = ImageElement(x = 0f, y = 0f, width = 10f, height = 10f, data = "")
        val json = moshi.adapter(ImageElement::class.java).toJson(el)
        assertFalse(json.contains("faceSlots"))
        assertNull(moshi.adapter(ImageElement::class.java).fromJson(json)!!.faceSlots)
    }

    @Test
    fun `the line decodes and re-encodes unchanged`() {
        val adapter = moshi.adapter(ReadingEvent::class.java)
        val line = adapter.fromJson(AddToPickGolden.RECORD_LINE)!!
        assertEquals(listOf(Attachment.Kind.PHOTO, Attachment.Kind.AUDIO, Attachment.Kind.VIDEO), line.attachments!!.map { it.kind })
        assertEquals(AddToPickGolden.RECORD_LINE, canonText(adapter.toJson(line)))
    }

    private fun day(updated: Long) = CalendarDay(year = 2026, month = 10, day = 16, locale = Locale.US,
        startHour = null, updated = Date(updated))

    private fun att(id: String, kind: Attachment.Kind = Attachment.Kind.PHOTO) =
        Attachment(id, kind, "$id.bin", null, Date(1_000L))

    private fun line(updatedAt: Long, vararg atts: Attachment) = ReadingEvent(
        id = "mfhl-42-0a1b2c3d", kind = ReadingEvent.Kind.ARTICLE, date = Date(1_000L), title = "T",
        excerpt = "the passage", attachments = atts.toMutableList(), updatedAt = Date(updatedAt))

    @Test
    fun `a line's attachments are pooled by id - the add made on the other device is kept`() {
        // The iPhone added a photo; the B6 a voice note — both before either synced. The B6's copy
        // is newer, so it wins the line; the photo rides along.
        val iphone = day(2_000L).apply { readingEvents.add(line(5_000L, att("p1"))) }
        val b6 = day(1_000L).apply { readingEvents.add(line(6_000L, att("a1", Attachment.Kind.AUDIO))) }
        for ((x, y) in listOf(iphone to b6, b6 to iphone)) {
            val merged = CalendarDayMerger.merge(x, y).readingEvents.single()
            assertEquals(listOf("a1", "p1"), merged.attachments!!.map { it.id })
        }
    }

    @Test
    fun `an attachment taken off the pick stays off on both sides`() {
        val removed = day(3_000L).apply {
            readingEvents.add(line(7_000L, att("a1", Attachment.Kind.AUDIO)))
            ReadingEventEdit.tombstone(this, "p1")
        }
        val stale = day(1_000L).apply { readingEvents.add(line(5_000L, att("p1"), att("a1", Attachment.Kind.AUDIO))) }
        assertEquals(listOf("a1"), CalendarDayMerger.merge(removed, stale).readingEvents.single().attachments!!.map { it.id })
        assertEquals(listOf("a1"), CalendarDayMerger.merge(stale, removed).readingEvents.single().attachments!!.map { it.id })
    }

    @Test
    fun `a removal leaves att colon id - the one marker both apps write`() {
        val d = day(1_000L)
        ReadingEventEdit.tombstoneAttachment(d, "AB12-CD")
        ReadingEventEdit.tombstoneAttachment(d, "ab12-cd")
        assertEquals(listOf("att:ab12-cd"), d.deletedElementIds)
        assertEquals("att:ab12-cd", CalendarDayMerger.attachmentTombstone("AB12-CD"))
    }

    /**
     * THE OTHER APP'S REMOVAL STAYS REMOVED (review 2026-10-16 #1). The vector iOS's
     * AddToPickWordsTests merges too: the iPhone took an upper-case-id voice note off its line and
     * wrote `att:<id>`; the B6's older copy still has it — the merge keeps it off, both ways round.
     * The bare id (this app's first builds, and an A/V gram removed from that day) counts as well,
     * as it does on iOS.
     */
    @Test
    fun `the other app's marker is honoured - att colon id or the bare id, any case`() {
        val adapter = moshi.adapter(CalendarDay::class.java)
        val iphone = adapter.fromJson("""{"year":2026,"month":10,"day":16,"locale":"en-US","updated":7000,
            "deletedElementIds":["att:ab12-cd"],"readingEvents":[{"id":"mfhl-1-a","kind":"article","date":1,
            "title":"T","attachments":[],"updatedAt":300}]}""")!!
        val voice = Attachment("AB12-CD", Attachment.Kind.AUDIO, "voice-AB12-CD.m4a", 23.0, Date(20L))
        val b6 = day(5_000L).apply {
            readingEvents.add(ReadingEvent(id = "mfhl-1-a", kind = ReadingEvent.Kind.ARTICLE, date = Date(1L), title = "T",
                attachments = mutableListOf(att("att-photo"), voice), updatedAt = Date(100L)))
        }
        assertEquals(listOf("att-photo"), CalendarDayMerger.merge(iphone, b6).readingEvents.single().attachments!!.map { it.id })
        assertEquals(listOf("att-photo"), CalendarDayMerger.merge(b6, iphone).readingEvents.single().attachments!!.map { it.id })
        val bare = day(7_000L).apply {
            readingEvents.add(line(300L)); deletedElementIds.add("ab12-cd")
        }
        val stale = day(5_000L).apply { readingEvents.add(line(100L, att("att-photo"), voice)) }
        assertEquals(listOf("att-photo"), CalendarDayMerger.merge(bare, stale).readingEvents.single().attachments!!.map { it.id })
        assertEquals(listOf("att-photo"), CalendarDayMerger.merge(stale, bare).readingEvents.single().attachments!!.map { it.id })
        assertTrue(CalendarDayMerger.isAttachmentTombstoned("AB12-CD", setOf("att:ab12-cd")))
        assertFalse(CalendarDayMerger.isAttachmentTombstoned("AB12-CD", setOf("att:other")))
    }

    @Test
    fun `a series' copies ride the mark and are written only when there are some`() {
        val m = BookNote(id = "mfhl-1-aaaa", type = BookNote.ANNOTATION, text = "p", url = "https://example.com/a",
            pickCopies = listOf("0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90", "5b7e1c40-2d9a-4e6f-8a13-c4d2e9f07b61"))
        val back = MarkJson.fromJson(MarkJson.toJson(m))!!
        assertEquals(m.pickCopies, back.pickCopies)
        assertEquals("", back.extra)
        assertFalse(MarkJson.toJson(m.copy(pickCopies = emptyList())).has("pickCopies"))
    }

    @Test
    fun `a line with nothing to pool comes back as it was`() {
        val only = line(5_000L, att("p1"))
        val a = day(2_000L).apply { readingEvents.add(only) }
        val b = day(1_000L)
        assertSame(only, CalendarDayMerger.merge(a, b).readingEvents.single())
    }

    @Test
    fun `a mark carries its face through the mark file, and a book mark writes none`() {
        val m = BookNote(id = "mfhl-1-aaaa", type = BookNote.ANNOTATION, text = "p", url = "https://example.com/a",
            face = MarkFace("story", "series", true))
        val back = MarkJson.fromJson(MarkJson.toJson(m))!!
        assertEquals(MarkFace("story", "series", true), back.face)
        assertEquals("", back.extra)
        val book = BookNote(id = "bn-1", type = BookNote.ANNOTATION, text = "p")
        assertFalse(MarkJson.toJson(book).has("face"))
        assertNull(MarkJson.fromJson(MarkJson.toJson(book))!!.face)
    }

    @Test
    fun `slots map onto the stored image - through the fit and the tape`() {
        val l = PickFaceLayout.layout(AddToPickGolden.input(PickFaceLayout.Shape.STORY, AddToPickGolden.CASES[4]),
            PickFaceLayout.FakeMeasure)
        // Untaped: the face itself (a fit to 1200 changes nothing per-mille).
        val bare = PickingsPlacement.storedSlots(l, treatment = false)
        assertEquals(l.slots.map { PickFaceLayout.perMille(it.box, l.w, l.h) }.map { it.map { v -> v / 4 } },
            bare.map { it.r.map { v -> v / 4 } })
        // Taped: every slot sits inside the card, inside its paper margin, and a tap at a slot's
        // centre on the element hits that slot.
        val taped = PickingsPlacement.storedSlots(l, treatment = true)
        for ((s, raw) in taped.zip(l.slots)) {
            assertEquals(raw.kind.key, s.k); assertEquals(raw.id, s.a)
            assertTrue(s.r[0] > 0 && s.r[1] > 0 && s.r[0] + s.r[2] < 1000 && s.r[1] + s.r[3] < 1000)
            val w = 600f; val h = 900f
            assertTrue(s.hit((s.r[0] + s.r[2] / 2f) * w / 1000f, (s.r[1] + s.r[3] / 2f) * h / 1000f, w, h))
        }
    }

    @Test
    fun `the annotations glyphs come from what rides on the line`() {
        assertEquals("✍ 📷 🎥 🎤", ArticlePickIndex.glyphsOf(listOf("ink-1.png", "photo-2.jpg", "video-3.mp4", "voice-4.m4a")))
        assertEquals("📷", ArticlePickIndex.glyphsOf(listOf("photo-2.jpg")))
        assertEquals("", ArticlePickIndex.glyphsOf(emptyList()))
    }
}
